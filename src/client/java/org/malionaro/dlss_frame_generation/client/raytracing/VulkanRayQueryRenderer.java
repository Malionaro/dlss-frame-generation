package org.malionaro.dlss_frame_generation.client.raytracing;

import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;
import org.malionaro.dlss_frame_generation.client.config.FrameGenConfig;
import org.malionaro.dlss_frame_generation.client.access.VulkanDeviceAccess;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.vulkan.KHRAccelerationStructure.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK12.*;

/**
 * Hybrid raster/ray-query pass.
 *
 * <p>Minecraft renders the normal frame first. This pass copies it to a
 * storage image, traces the block AABB scene for shadows/reflections, and
 * copies the result back before the surface performs its normal present blit.</p>
 */
public final class VulkanRayQueryRenderer implements AutoCloseable {
    private static final int COLOR_FORMAT = VK_FORMAT_R8G8B8A8_UNORM;
    private static final int HDR_FORMAT = VK_FORMAT_R16G16B16A16_SFLOAT;
    private static final int HIT_DISTANCE_FORMAT = VK_FORMAT_R16_SFLOAT;
    private static final int BLOCK_CAPACITY = RayTracingScene.MAX_BLOCKS;
    private static final int FRAME_SLOT_COUNT = 3;

    // Phase 3 K1: GPU-Synchronisation – echte per-slot VkFences. Jede Fence wird
    // beim Ressourcenaufbau mit VK_FENCE_CREATE_SIGNALED_BIT erzeugt und vor dem
    // Upload per vkResetFences bewaffnet; ein minimaler Drain-Marker-Submit auf
    // der Graphics-Queue signalisiert sie, sobald die GPU alle zuvor eingereihten
    // Command Buffers (inkl. des Frames, der den Slot zuletzt konsumierte)
    // abgearbeitet hat. awaitUniformSlotFence wartet dann echt via
    // vkWaitForFences (100ms Budget) statt einer CPU-Sleep-Heuristik.
    // WIP: Timeline-Semaphoren (vkWaitSemaphores) und ein HOST_VISIBLE-Staging-Ring
    // via vkCmdCopyBuffer bleiben offen; der Renderer besitzt keinen eigenen
    // Frame-Submit, daher ersetzt der Marker-Submit die fehlende Fence-Zuordnung.
    private final long[] slotFences = new long[FRAME_SLOT_COUNT];
    private final VkCommandBuffer[] slotMarkerCommandBuffers =
            new VkCommandBuffer[FRAME_SLOT_COUNT];
    private long commandPool;
    private boolean fenceSyncAvailable;
    private final boolean[] slotInFlight = new boolean[FRAME_SLOT_COUNT];
    private final long[] slotSubmittedFrame = new long[FRAME_SLOT_COUNT];
    private final int[] slotTimeline = new int[FRAME_SLOT_COUNT];
    private long nextTimelineValue = 1L;

    private final Logger logger;
    private final VulkanDevice minecraftDevice;
    private final VkDevice device;
    private final VkPhysicalDevice physicalDevice;
    private final RayTracingScene scene = new RayTracingScene();

    private Allocation sceneBuffer;
    private final Allocation[] uniformBuffers =
            new Allocation[FRAME_SLOT_COUNT];
    private Allocation instanceBuffer;
    private Allocation blasStorage;
    private Allocation tlasStorage;
    private Allocation blasScratch;
    private Allocation tlasScratch;
    private long blas;
    private long tlas;

    private Allocation outputMemory;
    private long outputImage;
    private long outputView;
    private int outputWidth;
    private int outputHeight;
    private boolean outputInitialized;
    private StorageImage noisyColor;
    private StorageImage diffuseAlbedo;
    private StorageImage specularAlbedo;
    private StorageImage normalRoughness;
    private StorageImage specularHitDistance;

    private long shaderModule;
    private long descriptorSetLayout;
    private long pipelineLayout;
    private long pipeline;
    private long descriptorPool;
    private final long[] descriptorSets = new long[FRAME_SLOT_COUNT];
    private long depthSampler;
    private long descriptorDepthView;
    private long frameNumber;
    private boolean descriptorsDirty = true;
    private boolean initialized;
    private boolean failed;
    private boolean renderedFrame;
    private String failureMessage = "";

    public VulkanRayQueryRenderer(VulkanDevice minecraftDevice, Logger logger) {
        this.minecraftDevice = minecraftDevice;
        this.device = minecraftDevice.vkDevice();
        this.logger = logger;
        if (!(minecraftDevice instanceof VulkanDeviceAccess access)
                || access.dlss_fg$getPhysicalDevice() == null) {
            throw new IllegalArgumentException("Vulkan physical device is unavailable");
        }
        this.physicalDevice = access.dlss_fg$getPhysicalDevice().vkPhysicalDevice();
    }

    public boolean render(
            VkCommandBuffer commandBuffer,
            VulkanGpuTextureView colorView,
            VulkanGpuTextureView depthView,
            FrameGenConfig config
    ) {
        if (failed || commandBuffer == null
                || colorView == null || colorView.texture() == null
                || depthView == null || depthView.texture() == null) {
            return false;
        }

        int width = colorView.texture().getWidth(colorView.baseMipLevel());
        int height = colorView.texture().getHeight(colorView.baseMipLevel());
        int depthWidth = depthView.texture().getWidth(depthView.baseMipLevel());
        int depthHeight = depthView.texture().getHeight(depthView.baseMipLevel());
        if (width <= 0 || height <= 0
                || depthWidth != width || depthHeight != height) {
            return false;
        }

        try {
            ensureInitialized();
            ensureOutput(width, height);
            scene.updateIfNeeded(
                    Minecraft.getInstance(),
                    config.rayTracingMaxDistance
            );
            if (scene.blockCount() == 0) {
                return false;
            }
            if (scene.consumeDirty() || tlas == NULL) {
                rebuildScene(commandBuffer);
            }

            RayTracingFrameData frame = RayTracingFrameData.capture(
                    Minecraft.getInstance(), width, height, config);
            if (frame == null) {
                return false;
            }
            int frameSlot = (int) (frameNumber % FRAME_SLOT_COUNT);
            awaitUniformSlotFence(frameSlot);
            upload(uniformBuffers[frameSlot], frame.toNativeBuffer());
            markUniformSlotInFlight(frameSlot);
            if (descriptorDepthView != depthView.vkImageView()) {
                if (descriptorDepthView != NULL) {
                    // Descriptor sets from previous frames may still be in
                    // flight when Minecraft recreates an equal-sized target.
                    // This only occurs on target/fullscreen transitions.
                    vkDeviceWaitIdle(device);
                }
                descriptorDepthView = depthView.vkImageView();
                descriptorsDirty = true;
            }
            if (descriptorsDirty) {
                updateDescriptors(descriptorDepthView);
            }

            recordHybridPass(
                    commandBuffer,
                    colorView.texture().vkImage(),
                    depthView.texture().vkImage(),
                    descriptorSets[frameSlot],
                    width,
                    height
            );
            frameNumber++;
            renderedFrame = true;
            return true;
        } catch (Throwable throwable) {
            failed = true;
            failureMessage = throwable.getClass().getSimpleName()
                    + ": " + String.valueOf(throwable.getMessage());
            logger.error("Hardware ray-query renderer failed and was disabled for this session", throwable);
            return false;
        }
    }

    public boolean failed() {
        return failed;
    }

    public void onBlockChanged(BlockPos position) {
        scene.invalidate(position);
    }

    public boolean hasRenderedFrame() {
        return renderedFrame;
    }

    public String failureMessage() {
        return failureMessage;
    }

    public RayReconstructionResources rayReconstructionResources() {
        if (!renderedFrame || noisyColor == null || diffuseAlbedo == null
                || specularAlbedo == null || normalRoughness == null
                || specularHitDistance == null) {
            return null;
        }
        return new RayReconstructionResources(
                noisyColor.external(),
                diffuseAlbedo.external(),
                specularAlbedo.external(),
                normalRoughness.external(),
                specularHitDistance.external(),
                outputWidth,
                outputHeight
        );
    }

    private void ensureInitialized() throws IOException {
        if (initialized) {
            return;
        }
        sceneBuffer = createBuffer(
                (long) BLOCK_CAPACITY * RayTracingScene.BYTES_PER_BLOCK,
                VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
                        | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT
                        | VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                true
        );
        for (int index = 0; index < FRAME_SLOT_COUNT; index++) {
            uniformBuffers[index] = createBuffer(
                    RayTracingFrameData.BYTE_SIZE,
                    VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT,
                    VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT
                            | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                    false
            );
        }
        instanceBuffer = createBuffer(
                VkAccelerationStructureInstanceKHR.SIZEOF,
                VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT
                        | VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                true
        );
        createPipeline();
        setupSlotSynchronization();
        initialized = true;
    }

    private void createPipeline() throws IOException {
        ByteBuffer spirv = RayQueryShaderCompiler.compile(
                "/assets/dlss_fg/shaders/source/raytracing/hybrid_ray_query.comp");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer result = stack.mallocLong(1);
            VkShaderModuleCreateInfo shaderInfo = VkShaderModuleCreateInfo.calloc(stack)
                    .sType$Default()
                    .pCode(spirv);
            check(vkCreateShaderModule(device, shaderInfo, null, result), "create RT shader module");
            shaderModule = result.get(0);

            VkDescriptorSetLayoutBinding.Buffer bindings =
                    VkDescriptorSetLayoutBinding.calloc(10, stack);
            bindings.get(0)
                    .binding(0)
                    .descriptorType(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR)
                    .descriptorCount(1)
                    .stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            bindings.get(1)
                    .binding(1)
                    .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(1)
                    .stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            bindings.get(2)
                    .binding(2)
                    .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .descriptorCount(1)
                    .stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            bindings.get(3)
                    .binding(3)
                    .descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                    .descriptorCount(1)
                    .stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            for (int binding = 4; binding <= 8; binding++) {
                bindings.get(binding)
                        .binding(binding)
                        .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                        .descriptorCount(1)
                        .stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            }
            bindings.get(9)
                    .binding(9)
                    .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1)
                    .stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);

            VkDescriptorSetLayoutCreateInfo layoutInfo =
                    VkDescriptorSetLayoutCreateInfo.calloc(stack)
                            .sType$Default()
                            .pBindings(bindings);
            check(vkCreateDescriptorSetLayout(device, layoutInfo, null, result),
                    "create RT descriptor layout");
            descriptorSetLayout = result.get(0);

            VkPipelineLayoutCreateInfo pipelineLayoutInfo =
                    VkPipelineLayoutCreateInfo.calloc(stack)
                            .sType$Default()
                            .pSetLayouts(stack.longs(descriptorSetLayout));
            check(vkCreatePipelineLayout(device, pipelineLayoutInfo, null, result),
                    "create RT pipeline layout");
            pipelineLayout = result.get(0);

            VkPipelineShaderStageCreateInfo stage =
                    VkPipelineShaderStageCreateInfo.calloc(stack)
                            .sType$Default()
                            .stage(VK_SHADER_STAGE_COMPUTE_BIT)
                            .module(shaderModule)
                            .pName(stack.UTF8("main"));
            VkComputePipelineCreateInfo.Buffer pipelineInfo =
                    VkComputePipelineCreateInfo.calloc(1, stack);
            pipelineInfo.get(0)
                    .sType$Default()
                    .stage(stage)
                    .layout(pipelineLayout);
            check(vkCreateComputePipelines(
                    device, NULL, pipelineInfo, null, result), "create RT compute pipeline");
            pipeline = result.get(0);

            VkSamplerCreateInfo samplerInfo = VkSamplerCreateInfo.calloc(stack)
                    .sType$Default()
                    .magFilter(VK_FILTER_NEAREST)
                    .minFilter(VK_FILTER_NEAREST)
                    .mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST)
                    .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .minLod(0.0f)
                    .maxLod(0.0f);
            check(vkCreateSampler(device, samplerInfo, null, result),
                    "create RT depth sampler");
            depthSampler = result.get(0);

            VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(5, stack);
            poolSizes.get(0)
                    .type(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR)
                    .descriptorCount(FRAME_SLOT_COUNT);
            poolSizes.get(1)
                    .type(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(6 * FRAME_SLOT_COUNT);
            poolSizes.get(2)
                    .type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .descriptorCount(FRAME_SLOT_COUNT);
            poolSizes.get(3)
                    .type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                    .descriptorCount(FRAME_SLOT_COUNT);
            poolSizes.get(4)
                    .type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(FRAME_SLOT_COUNT);
            VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack)
                    .sType$Default()
                    .maxSets(FRAME_SLOT_COUNT)
                    .pPoolSizes(poolSizes);
            check(vkCreateDescriptorPool(device, poolInfo, null, result),
                    "create RT descriptor pool");
            descriptorPool = result.get(0);

            VkDescriptorSetAllocateInfo allocateInfo =
                    VkDescriptorSetAllocateInfo.calloc(stack)
                            .sType$Default()
                            .descriptorPool(descriptorPool)
                            .pSetLayouts(stack.mallocLong(FRAME_SLOT_COUNT));
            for (int index = 0; index < FRAME_SLOT_COUNT; index++) {
                allocateInfo.pSetLayouts().put(index, descriptorSetLayout);
            }
            LongBuffer descriptorResults =
                    stack.mallocLong(FRAME_SLOT_COUNT);
            check(vkAllocateDescriptorSets(
                    device, allocateInfo, descriptorResults),
                    "allocate RT descriptor sets");
            for (int index = 0; index < FRAME_SLOT_COUNT; index++) {
                descriptorSets[index] = descriptorResults.get(index);
            }
        } finally {
            MemoryUtil.memFree(spirv);
        }
    }

    private void ensureOutput(int width, int height) {
        if (outputImage != NULL && width == outputWidth && height == outputHeight) {
            return;
        }
        vkDeviceWaitIdle(device);
        destroyOutput();

        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer result = stack.mallocLong(1);
            VkImageCreateInfo imageInfo = VkImageCreateInfo.calloc(stack)
                    .sType$Default()
                    .imageType(VK_IMAGE_TYPE_2D)
                    .format(COLOR_FORMAT)
                    .extent(extent -> extent.width(width).height(height).depth(1))
                    .mipLevels(1)
                    .arrayLayers(1)
                    .samples(VK_SAMPLE_COUNT_1_BIT)
                    .tiling(VK_IMAGE_TILING_OPTIMAL)
                    .usage(VK_IMAGE_USAGE_STORAGE_BIT
                            | VK_IMAGE_USAGE_TRANSFER_SRC_BIT
                            | VK_IMAGE_USAGE_TRANSFER_DST_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                    .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            check(vkCreateImage(device, imageInfo, null, result), "create RT output image");
            outputImage = result.get(0);

            VkMemoryRequirements requirements = VkMemoryRequirements.calloc(stack);
            vkGetImageMemoryRequirements(device, outputImage, requirements);
            outputMemory = allocateMemory(
                    requirements,
                    VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
                    false
            );
            check(vkBindImageMemory(device, outputImage, outputMemory.memory, 0),
                    "bind RT output image");

            VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack)
                    .sType$Default()
                    .image(outputImage)
                    .viewType(VK_IMAGE_VIEW_TYPE_2D)
                    .format(COLOR_FORMAT)
                    .subresourceRange(range -> range
                            .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                            .baseMipLevel(0)
                            .levelCount(1)
                            .baseArrayLayer(0)
                            .layerCount(1));
            check(vkCreateImageView(device, viewInfo, null, result), "create RT output view");
            outputView = result.get(0);
        }
        noisyColor = createStorageImage(
                HDR_FORMAT, width, height, "RT noisy HDR color");
        diffuseAlbedo = createStorageImage(
                HDR_FORMAT, width, height, "RT diffuse albedo");
        specularAlbedo = createStorageImage(
                HDR_FORMAT, width, height, "RT specular albedo");
        normalRoughness = createStorageImage(
                HDR_FORMAT, width, height, "RT normal and roughness");
        specularHitDistance = createStorageImage(
                HIT_DISTANCE_FORMAT, width, height, "RT specular hit distance");
        outputWidth = width;
        outputHeight = height;
        outputInitialized = false;
        descriptorsDirty = true;
    }

    private StorageImage createStorageImage(
            int format,
            int width,
            int height,
            String label
    ) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer result = stack.mallocLong(1);
            VkImageCreateInfo imageInfo = VkImageCreateInfo.calloc(stack)
                    .sType$Default()
                    .imageType(VK_IMAGE_TYPE_2D)
                    .format(format)
                    .extent(extent -> extent.width(width).height(height).depth(1))
                    .mipLevels(1)
                    .arrayLayers(1)
                    .samples(VK_SAMPLE_COUNT_1_BIT)
                    .tiling(VK_IMAGE_TILING_OPTIMAL)
                    .usage(VK_IMAGE_USAGE_STORAGE_BIT
                            | VK_IMAGE_USAGE_SAMPLED_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                    .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            check(vkCreateImage(device, imageInfo, null, result),
                    "create " + label + " image");
            long image = result.get(0);

            VkMemoryRequirements requirements =
                    VkMemoryRequirements.calloc(stack);
            vkGetImageMemoryRequirements(device, image, requirements);
            Allocation memory = allocateMemory(
                    requirements,
                    VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
                    false
            );
            check(vkBindImageMemory(device, image, memory.memory, 0),
                    "bind " + label + " image");

            VkImageViewCreateInfo viewInfo =
                    VkImageViewCreateInfo.calloc(stack)
                            .sType$Default()
                            .image(image)
                            .viewType(VK_IMAGE_VIEW_TYPE_2D)
                            .format(format)
                            .subresourceRange(range -> range
                                    .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                                    .baseMipLevel(0)
                                    .levelCount(1)
                                    .baseArrayLayer(0)
                                    .layerCount(1));
            check(vkCreateImageView(device, viewInfo, null, result),
                    "create " + label + " view");
            return new StorageImage(
                    image,
                    memory,
                    result.get(0),
                    format
            );
        }
    }

    private void rebuildScene(VkCommandBuffer commandBuffer) {
        vkDeviceWaitIdle(device);
        destroyAccelerationStructures();
        upload(sceneBuffer, scene.blocks());

        try (MemoryStack stack = MemoryStack.stackPush()) {
            int count = scene.blockCount();
            long sceneAddress = bufferAddress(sceneBuffer.buffer, stack);

            VkAccelerationStructureGeometryKHR.Buffer blasGeometry =
                    createAabbGeometry(stack, sceneAddress);
            VkAccelerationStructureBuildGeometryInfoKHR blasBuild =
                    VkAccelerationStructureBuildGeometryInfoKHR.calloc(stack)
                            .sType$Default()
                            .type(VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR)
                            .flags(VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR)
                            .mode(VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR)
                            .pGeometries(blasGeometry);
            VkAccelerationStructureBuildSizesInfoKHR blasSizes =
                    VkAccelerationStructureBuildSizesInfoKHR.calloc(stack).sType$Default();
            vkGetAccelerationStructureBuildSizesKHR(
                    device,
                    VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR,
                    blasBuild,
                    stack.ints(count),
                    blasSizes
            );
            blasStorage = createBuffer(
                    blasSizes.accelerationStructureSize(),
                    VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR
                            | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,
                    VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
                    true
            );
            blas = createAccelerationStructure(
                    blasStorage.buffer,
                    blasSizes.accelerationStructureSize(),
                    VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR,
                    stack
            );
            blasScratch = createBuffer(
                    blasSizes.buildScratchSize(),
                    VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,
                    VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
                    true
            );
            blasBuild
                    .dstAccelerationStructure(blas)
                    .scratchData(address -> address.deviceAddress(
                            bufferAddress(blasScratch.buffer, stack)));
            recordBuild(commandBuffer, blasBuild, count, stack);

            VkAccelerationStructureDeviceAddressInfoKHR addressInfo =
                    VkAccelerationStructureDeviceAddressInfoKHR.calloc(stack)
                            .sType$Default()
                            .accelerationStructure(blas);
            writeInstance(vkGetAccelerationStructureDeviceAddressKHR(device, addressInfo));

            memoryBarrier(
                    commandBuffer,
                    VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                    VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                    VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR,
                    VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR,
                    stack
            );

            long instanceAddress = bufferAddress(instanceBuffer.buffer, stack);
            VkAccelerationStructureGeometryKHR.Buffer tlasGeometry =
                    createInstanceGeometry(stack, instanceAddress);
            VkAccelerationStructureBuildGeometryInfoKHR tlasBuild =
                    VkAccelerationStructureBuildGeometryInfoKHR.calloc(stack)
                            .sType$Default()
                            .type(VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR)
                            .flags(VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR)
                            .mode(VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR)
                            .pGeometries(tlasGeometry);
            VkAccelerationStructureBuildSizesInfoKHR tlasSizes =
                    VkAccelerationStructureBuildSizesInfoKHR.calloc(stack).sType$Default();
            vkGetAccelerationStructureBuildSizesKHR(
                    device,
                    VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR,
                    tlasBuild,
                    stack.ints(1),
                    tlasSizes
            );
            tlasStorage = createBuffer(
                    tlasSizes.accelerationStructureSize(),
                    VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR
                            | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,
                    VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
                    true
            );
            tlas = createAccelerationStructure(
                    tlasStorage.buffer,
                    tlasSizes.accelerationStructureSize(),
                    VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR,
                    stack
            );
            tlasScratch = createBuffer(
                    tlasSizes.buildScratchSize(),
                    VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,
                    VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
                    true
            );
            tlasBuild
                    .dstAccelerationStructure(tlas)
                    .scratchData(address -> address.deviceAddress(
                            bufferAddress(tlasScratch.buffer, stack)));
            recordBuild(commandBuffer, tlasBuild, 1, stack);
            memoryBarrier(
                    commandBuffer,
                    VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR,
                    VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR,
                    stack
            );
        }
        descriptorsDirty = true;
        logger.info("Built hardware RT scene with {} block AABBs", scene.blockCount());
    }

    private static VkAccelerationStructureGeometryKHR.Buffer createAabbGeometry(
            MemoryStack stack,
            long deviceAddress
    ) {
        VkAccelerationStructureGeometryKHR.Buffer geometry =
                VkAccelerationStructureGeometryKHR.calloc(1, stack);
        geometry.get(0)
                .sType$Default()
                .geometryType(VK_GEOMETRY_TYPE_AABBS_KHR)
                .flags(VK_GEOMETRY_OPAQUE_BIT_KHR);
        geometry.get(0).geometry().aabbs()
                .sType$Default()
                .data(data -> data.deviceAddress(deviceAddress))
                .stride(RayTracingScene.BYTES_PER_BLOCK);
        return geometry;
    }

    private static VkAccelerationStructureGeometryKHR.Buffer createInstanceGeometry(
            MemoryStack stack,
            long deviceAddress
    ) {
        VkAccelerationStructureGeometryKHR.Buffer geometry =
                VkAccelerationStructureGeometryKHR.calloc(1, stack);
        geometry.get(0)
                .sType$Default()
                .geometryType(VK_GEOMETRY_TYPE_INSTANCES_KHR)
                .flags(VK_GEOMETRY_OPAQUE_BIT_KHR);
        geometry.get(0).geometry().instances()
                .sType$Default()
                .arrayOfPointers(false)
                .data(data -> data.deviceAddress(deviceAddress));
        return geometry;
    }

    private static void recordBuild(
            VkCommandBuffer commandBuffer,
            VkAccelerationStructureBuildGeometryInfoKHR build,
            int primitiveCount,
            MemoryStack stack
    ) {
        VkAccelerationStructureBuildGeometryInfoKHR.Buffer builds =
                VkAccelerationStructureBuildGeometryInfoKHR.calloc(1, stack);
        builds.get(0).set(build);
        VkAccelerationStructureBuildRangeInfoKHR.Buffer range =
                VkAccelerationStructureBuildRangeInfoKHR.calloc(1, stack);
        range.get(0)
                .primitiveCount(primitiveCount)
                .primitiveOffset(0)
                .firstVertex(0)
                .transformOffset(0);
        PointerBuffer ranges = stack.mallocPointer(1).put(0, range.address());
        vkCmdBuildAccelerationStructuresKHR(commandBuffer, builds, ranges);
    }

    private void writeInstance(long blasAddress) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer mapped = stack.mallocPointer(1);
            check(vkMapMemory(
                    device, instanceBuffer.memory, 0,
                    VkAccelerationStructureInstanceKHR.SIZEOF, 0, mapped), "map RT instance");
            VkAccelerationStructureInstanceKHR instance =
                    VkAccelerationStructureInstanceKHR.create(mapped.get(0));
            instance.transform().matrix(stack.floats(
                    1, 0, 0, 0,
                    0, 1, 0, 0,
                    0, 0, 1, 0
            ));
            instance.instanceCustomIndex(0)
                    .mask(0xff)
                    .instanceShaderBindingTableRecordOffset(0)
                    .flags(VK_GEOMETRY_INSTANCE_TRIANGLE_FACING_CULL_DISABLE_BIT_KHR)
                    .accelerationStructureReference(blasAddress);
            vkUnmapMemory(device, instanceBuffer.memory);
        }
    }

    private void updateDescriptors(long depthView) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkWriteDescriptorSetAccelerationStructureKHR accelerationInfo =
                    VkWriteDescriptorSetAccelerationStructureKHR.calloc(stack)
                            .sType$Default()
                            .pAccelerationStructures(stack.longs(tlas));
            VkDescriptorImageInfo.Buffer imageInfo =
                    VkDescriptorImageInfo.calloc(6, stack);
            long[] imageViews = {
                    outputView,
                    noisyColor.view,
                    diffuseAlbedo.view,
                    specularAlbedo.view,
                    normalRoughness.view,
                    specularHitDistance.view
            };
            for (int index = 0; index < imageViews.length; index++) {
                imageInfo.get(index)
                        .imageView(imageViews[index])
                        .imageLayout(VK_IMAGE_LAYOUT_GENERAL);
            }
            VkDescriptorImageInfo.Buffer depthInfo =
                    VkDescriptorImageInfo.calloc(1, stack);
            depthInfo.get(0)
                    .sampler(depthSampler)
                    .imageView(depthView)
                    .imageLayout(VK_IMAGE_LAYOUT_GENERAL);
            VkDescriptorBufferInfo.Buffer sceneInfo = VkDescriptorBufferInfo.calloc(1, stack);
            sceneInfo.get(0)
                    .buffer(sceneBuffer.buffer)
                    .offset(0)
                    .range(VK_WHOLE_SIZE);
            for (int slot = 0; slot < FRAME_SLOT_COUNT; slot++) {
                VkDescriptorBufferInfo.Buffer frameInfo =
                        VkDescriptorBufferInfo.calloc(1, stack);
                frameInfo.get(0)
                        .buffer(uniformBuffers[slot].buffer)
                        .offset(0)
                        .range(RayTracingFrameData.BYTE_SIZE);
                VkWriteDescriptorSet.Buffer writes =
                        VkWriteDescriptorSet.calloc(10, stack);
                long targetSet = descriptorSets[slot];
                writes.get(0)
                        .sType$Default()
                        .pNext(accelerationInfo.address())
                        .dstSet(targetSet)
                        .dstBinding(0)
                        .descriptorCount(1)
                        .descriptorType(
                                VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR);
                writes.get(1)
                        .sType$Default()
                        .dstSet(targetSet)
                        .dstBinding(1)
                        .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                        .pImageInfo(imageInfo.position(0).limit(1));
                imageInfo.position(0).limit(6);
                writes.get(2)
                        .sType$Default()
                        .dstSet(targetSet)
                        .dstBinding(2)
                        .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                        .pBufferInfo(sceneInfo);
                writes.get(3)
                        .sType$Default()
                        .dstSet(targetSet)
                        .dstBinding(3)
                        .descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                        .pBufferInfo(frameInfo);
                for (int binding = 4; binding <= 8; binding++) {
                    int imageIndex = binding - 3;
                    writes.get(binding)
                            .sType$Default()
                            .dstSet(targetSet)
                            .dstBinding(binding)
                            .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                            .pImageInfo(imageInfo
                                    .position(imageIndex)
                                    .limit(imageIndex + 1));
                }
                writes.get(9)
                        .sType$Default()
                        .dstSet(targetSet)
                        .dstBinding(9)
                        .descriptorType(
                                VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                        .pImageInfo(depthInfo);
                imageInfo.position(0).limit(6);
                vkUpdateDescriptorSets(device, writes, null);
            }
        }
        descriptorsDirty = false;
    }

    private void recordHybridPass(
            VkCommandBuffer commandBuffer,
            long colorImage,
            long depthImage,
            long descriptorSet,
            int width,
            int height
    ) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // Minecraft keeps its render target in GENERAL layout. Establish
            // an explicit render-write -> transfer-read dependency before
            // copying it into the ray-query output. Without this barrier the
            // compute pass could read an old frame and appear to do nothing.
            transitionImage(
                    commandBuffer,
                    colorImage,
                    VK_IMAGE_LAYOUT_GENERAL,
                    VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT
                            | VK_ACCESS_SHADER_WRITE_BIT
                            | VK_ACCESS_MEMORY_WRITE_BIT,
                    VK_ACCESS_TRANSFER_READ_BIT,
                    VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                    VK_PIPELINE_STAGE_TRANSFER_BIT,
                    stack
            );
            transitionImage(
                    commandBuffer,
                    outputImage,
                    outputInitialized ? VK_IMAGE_LAYOUT_GENERAL : VK_IMAGE_LAYOUT_UNDEFINED,
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    outputInitialized ? VK_ACCESS_SHADER_WRITE_BIT : 0,
                    VK_ACCESS_TRANSFER_WRITE_BIT,
                    outputInitialized ? VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT
                            : VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                    VK_PIPELINE_STAGE_TRANSFER_BIT,
                    stack
            );
            blit(commandBuffer, colorImage, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    outputImage, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, width, height, stack);
            transitionImage(
                    commandBuffer,
                    outputImage,
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    VK_IMAGE_LAYOUT_GENERAL,
                    VK_ACCESS_TRANSFER_WRITE_BIT,
                    VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT,
                    VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    stack
            );
            transitionGuideForWrite(commandBuffer, noisyColor, stack);
            transitionGuideForWrite(commandBuffer, diffuseAlbedo, stack);
            transitionGuideForWrite(commandBuffer, specularAlbedo, stack);
            transitionGuideForWrite(commandBuffer, normalRoughness, stack);
            transitionGuideForWrite(
                    commandBuffer, specularHitDistance, stack);
            imageMemoryBarrier(
                    commandBuffer,
                    depthImage,
                    VK_IMAGE_ASPECT_DEPTH_BIT,
                    VK_IMAGE_LAYOUT_GENERAL,
                    VK_IMAGE_LAYOUT_GENERAL,
                    VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT
                            | VK_ACCESS_SHADER_WRITE_BIT,
                    VK_ACCESS_SHADER_READ_BIT,
                    VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    stack
            );

            vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
            vkCmdBindDescriptorSets(
                    commandBuffer,
                    VK_PIPELINE_BIND_POINT_COMPUTE,
                    pipelineLayout,
                    0,
                    stack.longs(descriptorSet),
                    null
            );
            vkCmdDispatch(commandBuffer, (width + 7) / 8, (height + 7) / 8, 1);
            transitionGuideForRead(commandBuffer, noisyColor, stack);
            transitionGuideForRead(commandBuffer, diffuseAlbedo, stack);
            transitionGuideForRead(commandBuffer, specularAlbedo, stack);
            transitionGuideForRead(commandBuffer, normalRoughness, stack);
            transitionGuideForRead(
                    commandBuffer, specularHitDistance, stack);

            transitionImage(
                    commandBuffer,
                    outputImage,
                    VK_IMAGE_LAYOUT_GENERAL,
                    VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    VK_ACCESS_SHADER_WRITE_BIT,
                    VK_ACCESS_TRANSFER_READ_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK_PIPELINE_STAGE_TRANSFER_BIT,
                    stack
            );
            transitionImage(
                    commandBuffer,
                    colorImage,
                    VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    VK_ACCESS_TRANSFER_READ_BIT,
                    VK_ACCESS_TRANSFER_WRITE_BIT,
                    VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK_PIPELINE_STAGE_TRANSFER_BIT,
                    stack
            );
            blit(commandBuffer, outputImage, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    colorImage, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, width, height, stack);
            transitionImage(
                    commandBuffer,
                    colorImage,
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    VK_IMAGE_LAYOUT_GENERAL,
                    VK_ACCESS_TRANSFER_WRITE_BIT,
                    VK_ACCESS_TRANSFER_READ_BIT | VK_ACCESS_MEMORY_READ_BIT,
                    VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                    stack
            );
            transitionImage(
                    commandBuffer,
                    outputImage,
                    VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    VK_IMAGE_LAYOUT_GENERAL,
                    VK_ACCESS_TRANSFER_READ_BIT,
                    VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT,
                    VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    stack
            );
        }
        outputInitialized = true;
    }

    private static void transitionGuideForWrite(
            VkCommandBuffer commandBuffer,
            StorageImage image,
            MemoryStack stack
    ) {
        int oldLayout = image.layout;
        transitionImage(
                commandBuffer,
                image.image,
                oldLayout,
                VK_IMAGE_LAYOUT_GENERAL,
                oldLayout == VK_IMAGE_LAYOUT_UNDEFINED
                        ? 0 : VK_ACCESS_SHADER_READ_BIT,
                VK_ACCESS_SHADER_WRITE_BIT,
                oldLayout == VK_IMAGE_LAYOUT_UNDEFINED
                        ? VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT
                        : VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                stack
        );
        image.layout = VK_IMAGE_LAYOUT_GENERAL;
    }

    private static void transitionGuideForRead(
            VkCommandBuffer commandBuffer,
            StorageImage image,
            MemoryStack stack
    ) {
        transitionImage(
                commandBuffer,
                image.image,
                image.layout,
                VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                VK_ACCESS_SHADER_WRITE_BIT,
                VK_ACCESS_SHADER_READ_BIT,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                stack
        );
        image.layout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    }

    private static void blit(
            VkCommandBuffer commandBuffer,
            long source,
            int sourceLayout,
            long destination,
            int destinationLayout,
            int width,
            int height,
            MemoryStack stack
    ) {
        VkImageBlit.Buffer region = VkImageBlit.calloc(1, stack);
        region.get(0).srcSubresource(subresource -> subresource
                        .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                        .mipLevel(0)
                        .baseArrayLayer(0)
                        .layerCount(1))
                .dstSubresource(subresource -> subresource
                        .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                        .mipLevel(0)
                        .baseArrayLayer(0)
                        .layerCount(1));
        region.get(0).srcOffsets(0).set(0, 0, 0);
        region.get(0).srcOffsets(1).set(width, height, 1);
        region.get(0).dstOffsets(0).set(0, 0, 0);
        region.get(0).dstOffsets(1).set(width, height, 1);
        vkCmdBlitImage(
                commandBuffer,
                source,
                sourceLayout,
                destination,
                destinationLayout,
                region,
                VK_FILTER_NEAREST
        );
    }

    private static void transitionImage(
            VkCommandBuffer commandBuffer,
            long image,
            int oldLayout,
            int newLayout,
            int sourceAccess,
            int destinationAccess,
            int sourceStage,
            int destinationStage,
            MemoryStack stack
    ) {
        VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack);
        barrier.get(0)
                .sType$Default()
                .oldLayout(oldLayout)
                .newLayout(newLayout)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .image(image)
                .srcAccessMask(sourceAccess)
                .dstAccessMask(destinationAccess)
                .subresourceRange(range -> range
                        .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                        .baseMipLevel(0)
                        .levelCount(1)
                        .baseArrayLayer(0)
                        .layerCount(1));
        vkCmdPipelineBarrier(
                commandBuffer,
                sourceStage,
                destinationStage,
                0,
                null,
                null,
                barrier
        );
    }

    private static void imageMemoryBarrier(
            VkCommandBuffer commandBuffer,
            long image,
            int aspectMask,
            int oldLayout,
            int newLayout,
            int sourceAccess,
            int destinationAccess,
            int sourceStage,
            int destinationStage,
            MemoryStack stack
    ) {
        VkImageMemoryBarrier.Buffer barrier =
                VkImageMemoryBarrier.calloc(1, stack);
        barrier.get(0)
                .sType$Default()
                .oldLayout(oldLayout)
                .newLayout(newLayout)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .image(image)
                .srcAccessMask(sourceAccess)
                .dstAccessMask(destinationAccess)
                .subresourceRange(range -> range
                        .aspectMask(aspectMask)
                        .baseMipLevel(0)
                        .levelCount(1)
                        .baseArrayLayer(0)
                        .layerCount(1));
        vkCmdPipelineBarrier(
                commandBuffer,
                sourceStage,
                destinationStage,
                0,
                null,
                null,
                barrier
        );
    }

    private static void memoryBarrier(
            VkCommandBuffer commandBuffer,
            int sourceStage,
            int destinationStage,
            int sourceAccess,
            int destinationAccess,
            MemoryStack stack
    ) {
        VkMemoryBarrier.Buffer barrier = VkMemoryBarrier.calloc(1, stack);
        barrier.get(0)
                .sType$Default()
                .srcAccessMask(sourceAccess)
                .dstAccessMask(destinationAccess);
        vkCmdPipelineBarrier(
                commandBuffer,
                sourceStage,
                destinationStage,
                0,
                barrier,
                null,
                null
        );
    }

    private Allocation createBuffer(
            long size,
            int usage,
            int memoryProperties,
            boolean deviceAddress
    ) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer result = stack.mallocLong(1);
            VkBufferCreateInfo createInfo = VkBufferCreateInfo.calloc(stack)
                    .sType$Default()
                    .size(Math.max(size, 256))
                    .usage(usage)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            check(vkCreateBuffer(device, createInfo, null, result), "create RT buffer");
            long buffer = result.get(0);
            VkMemoryRequirements requirements = VkMemoryRequirements.calloc(stack);
            vkGetBufferMemoryRequirements(device, buffer, requirements);
            Allocation allocation = allocateMemory(requirements, memoryProperties, deviceAddress);
            allocation.buffer = buffer;
            check(vkBindBufferMemory(device, buffer, allocation.memory, 0), "bind RT buffer");
            return allocation;
        }
    }

    private Allocation allocateMemory(
            VkMemoryRequirements requirements,
            int properties,
            boolean deviceAddress
    ) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkMemoryAllocateFlagsInfo flags = null;
            VkMemoryAllocateInfo allocateInfo = VkMemoryAllocateInfo.calloc(stack)
                    .sType$Default()
                    .allocationSize(requirements.size())
                    .memoryTypeIndex(findMemoryType(requirements.memoryTypeBits(), properties, stack));
            if (deviceAddress) {
                flags = VkMemoryAllocateFlagsInfo.calloc(stack)
                        .sType$Default()
                        .flags(VK_MEMORY_ALLOCATE_DEVICE_ADDRESS_BIT);
                allocateInfo.pNext(flags.address());
            }
            LongBuffer result = stack.mallocLong(1);
            check(vkAllocateMemory(device, allocateInfo, null, result), "allocate RT memory");
            return new Allocation(NULL, result.get(0), requirements.size());
        }
    }

    private int findMemoryType(int typeBits, int properties, MemoryStack stack) {
        VkPhysicalDeviceMemoryProperties memoryProperties =
                VkPhysicalDeviceMemoryProperties.calloc(stack);
        vkGetPhysicalDeviceMemoryProperties(physicalDevice, memoryProperties);
        for (int index = 0; index < memoryProperties.memoryTypeCount(); index++) {
            if ((typeBits & (1 << index)) != 0
                    && (memoryProperties.memoryTypes(index).propertyFlags() & properties)
                    == properties) {
                return index;
            }
        }
        throw new IllegalStateException("No compatible Vulkan memory type for RT resource");
    }

    private long createAccelerationStructure(
            long buffer,
            long size,
            int type,
            MemoryStack stack
    ) {
        VkAccelerationStructureCreateInfoKHR createInfo =
                VkAccelerationStructureCreateInfoKHR.calloc(stack)
                        .sType$Default()
                        .buffer(buffer)
                        .offset(0)
                        .size(size)
                        .type(type);
        LongBuffer result = stack.mallocLong(1);
        check(vkCreateAccelerationStructureKHR(device, createInfo, null, result),
                "create acceleration structure");
        return result.get(0);
    }

    private long bufferAddress(long buffer, MemoryStack stack) {
        VkBufferDeviceAddressInfo info = VkBufferDeviceAddressInfo.calloc(stack)
                .sType$Default()
                .buffer(buffer);
        return vkGetBufferDeviceAddress(device, info);
    }

    private void awaitUniformSlotFence(int slot) {
        if (slot < 0 || slot >= FRAME_SLOT_COUNT) {
            return;
        }
        if (!slotInFlight[slot]) {
            return;
        }
        long lastFrame = slotSubmittedFrame[slot];
        if (!fenceSyncAvailable || slotFences[slot] == NULL) {
            // WIP: requires VkDevice handle + graphics-queue plumbing for the
            // drain-marker submit; without them only the CPU frame-retired
            // heuristic remains as a conservative fallback.
            awaitUniformSlotCpuFallback(slot, lastFrame);
            return;
        }
        // Real GPU gate: vkWaitForFences blocks until the drain-marker
        // submission attached to this slot's fence completes, i.e. until the
        // GPU has finished every command buffer queued before it - including
        // the earlier frame that read this uniform buffer.
        int result;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            result = VK13.vkWaitForFences(
                    device, stack.longs(slotFences[slot]), true, 100_000_000L);
        } catch (Throwable throwable) {
            logger.warn("Uniform slot {} VkFence wait threw; forcing retire", slot, throwable);
            retireSlot(slot);
            return;
        }
        if (result == VK_SUCCESS) {
            retireSlot(slot);
        } else if (result == VK_TIMEOUT) {
            logger.warn(
                    "Uniform slot {} VkFence wait timeout (frame {} last {}); forcing retire to avoid overwrite",
                    slot, frameNumber, lastFrame);
            retireSlot(slot);
        } else {
            logger.warn(
                    "Uniform slot {} VkFence wait failed with VkResult {}; forcing retire to avoid overwrite",
                    slot, result);
            retireSlot(slot);
        }
    }

    private void retireSlot(int slot) {
        slotInFlight[slot] = false;
        slotTimeline[slot]++;
    }

    /**
     * Legacy CPU fallback used only when fence synchronisation could not be
     * set up (no queue handle or VK object creation failed).
     */
    private void awaitUniformSlotCpuFallback(int slot, long lastFrame) {
        if (frameNumber - lastFrame >= FRAME_SLOT_COUNT) {
            retireSlot(slot);
            return;
        }
        int spins = 0;
        while (slotInFlight[slot] && spins < 200) {
            Thread.yield();
            try {
                Thread.sleep(1);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
            // Re-evaluate after short wait – allow retire one frame earlier to avoid deadlock in CPU stub
            if (frameNumber - lastFrame >= FRAME_SLOT_COUNT - 1) {
                retireSlot(slot);
                break;
            }
            spins++;
            if (spins >= 100) {
                logger.warn("Uniform slot {} fence wait timeout (frame {} last {}); forcing retire to avoid overwrite",
                        slot, frameNumber, lastFrame);
                retireSlot(slot);
                break;
            }
        }
    }

    private void setupSlotSynchronization() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var queueState = minecraftDevice.graphicsQueue();
            if (queueState == null) {
                logger.warn("Vulkan graphics queue unavailable; RT uniform slots use CPU frame-retire tracking");
                return;
            }
            LongBuffer result = stack.mallocLong(1);
            VkCommandPoolCreateInfo poolInfo = VkCommandPoolCreateInfo.calloc(stack)
                    .sType$Default()
                    .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT)
                    .queueFamilyIndex(queueState.queueFamilyIndex());
            check(vkCreateCommandPool(device, poolInfo, null, result),
                    "create RT uniform-slot command pool");
            commandPool = result.get(0);

            VkCommandBufferAllocateInfo allocateInfo =
                    VkCommandBufferAllocateInfo.calloc(stack)
                            .sType$Default()
                            .commandPool(commandPool)
                            .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                            .commandBufferCount(FRAME_SLOT_COUNT);
            PointerBuffer buffers = stack.mallocPointer(FRAME_SLOT_COUNT);
            check(vkAllocateCommandBuffers(device, allocateInfo, buffers),
                    "allocate RT uniform-slot marker command buffers");
            for (int index = 0; index < FRAME_SLOT_COUNT; index++) {
                slotMarkerCommandBuffers[index] =
                        new VkCommandBuffer(buffers.get(index), device);
            }

            for (int index = 0; index < FRAME_SLOT_COUNT; index++) {
                VkFenceCreateInfo fenceInfo = VkFenceCreateInfo.calloc(stack)
                        .sType$Default()
                        .flags(VK_FENCE_CREATE_SIGNALED_BIT);
                check(vkCreateFence(device, fenceInfo, null, result),
                        "create RT uniform-slot fence");
                slotFences[index] = result.get(0);
            }
        } catch (Throwable throwable) {
            destroySynchronization();
            logger.warn(
                    "RT per-slot VkFence synchronisation unavailable ({}); using CPU frame-retire tracking",
                    throwable.toString()
            );
            return;
        }
        fenceSyncAvailable = true;
    }

    private VkQueue graphicsQueue() {
        var queueState = minecraftDevice != null
                ? minecraftDevice.graphicsQueue() : null;
        return queueState != null ? queueState.vkQueue() : null;
    }

    private void markUniformSlotInFlight(int slot) {
        if (slot < 0 || slot >= FRAME_SLOT_COUNT) {
            return;
        }
        slotInFlight[slot] = true;
        slotSubmittedFrame[slot] = frameNumber;
        slotTimeline[slot] = (int) (nextTimelineValue++);
        long fence = slotFences[slot];
        VkCommandBuffer marker = slotMarkerCommandBuffers[slot];
        VkQueue queue = graphicsQueue();
        if (!fenceSyncAvailable || fence == NULL || marker == null || queue == null) {
            return;
        }
        // Real VkFence lifecycle: arm the slot fence and attach it to a
        // minimal drain-marker submit on Minecraft's graphics queue. The
        // renderer records into Minecraft's command buffer and never owns
        // the frame submission itself, so this marker is the earliest point
        // at which a self-owned fence can observe GPU progress: it signals
        // once every previously queued command buffer completed, including
        // the frame that consumed this slot's uniform buffer.
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VK13.vkResetFences(device, stack.longs(fence));
            check(vkBeginCommandBuffer(marker, VkCommandBufferBeginInfo.calloc(stack)
                            .sType$Default()),
                    "begin RT slot marker command buffer");
            check(vkEndCommandBuffer(marker), "end RT slot marker command buffer");
            VkSubmitInfo submitInfo = VkSubmitInfo.calloc(stack)
                    .sType$Default()
                    .pCommandBuffers(stack.pointers(marker.address()));
            check(vkQueueSubmit(queue, submitInfo, fence),
                    "submit RT slot fence marker");
        } catch (Throwable throwable) {
            logger.warn(
                    "RT slot {} fence marker submit failed; disabling fence synchronisation",
                    slot, throwable
            );
            fenceSyncAvailable = false;
        }
    }

    private void destroySynchronization() {
        for (int index = 0; index < FRAME_SLOT_COUNT; index++) {
            if (slotFences[index] != NULL) {
                vkDestroyFence(device, slotFences[index], null);
                slotFences[index] = NULL;
            }
            slotMarkerCommandBuffers[index] = null;
        }
        fenceSyncAvailable = false;
        if (commandPool != NULL) {
            vkDestroyCommandPool(device, commandPool, null);
            commandPool = NULL;
        }
    }

    private void upload(Allocation allocation, ByteBuffer source) {
        if (source == null || !source.hasRemaining()) {
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer mapped = stack.mallocPointer(1);
            check(vkMapMemory(
                    device,
                    allocation.memory,
                    0,
                    source.remaining(),
                    0,
                    mapped
            ), "map RT upload buffer");
            MemoryUtil.memCopy(
                    MemoryUtil.memAddress(source),
                    mapped.get(0),
                    source.remaining()
            );
            vkUnmapMemory(device, allocation.memory);
        }
    }

    private static void check(int result, String action) {
        if (result != VK_SUCCESS) {
            throw new IllegalStateException(action + " failed with VkResult " + result);
        }
    }

    private void destroyOutput() {
        destroyStorageImage(noisyColor);
        destroyStorageImage(diffuseAlbedo);
        destroyStorageImage(specularAlbedo);
        destroyStorageImage(normalRoughness);
        destroyStorageImage(specularHitDistance);
        noisyColor = null;
        diffuseAlbedo = null;
        specularAlbedo = null;
        normalRoughness = null;
        specularHitDistance = null;
        if (outputView != NULL) {
            vkDestroyImageView(device, outputView, null);
            outputView = NULL;
        }
        if (outputImage != NULL) {
            vkDestroyImage(device, outputImage, null);
            outputImage = NULL;
        }
        destroyAllocation(outputMemory, false);
        outputMemory = null;
    }

    private void destroyStorageImage(StorageImage image) {
        if (image == null) return;
        if (image.view != NULL) {
            vkDestroyImageView(device, image.view, null);
        }
        if (image.image != NULL) {
            vkDestroyImage(device, image.image, null);
        }
        destroyAllocation(image.memory, false);
    }

    private void destroyAccelerationStructures() {
        if (tlas != NULL) {
            vkDestroyAccelerationStructureKHR(device, tlas, null);
            tlas = NULL;
        }
        if (blas != NULL) {
            vkDestroyAccelerationStructureKHR(device, blas, null);
            blas = NULL;
        }
        destroyAllocation(tlasStorage, true);
        destroyAllocation(blasStorage, true);
        destroyAllocation(tlasScratch, true);
        destroyAllocation(blasScratch, true);
        tlasStorage = null;
        blasStorage = null;
        tlasScratch = null;
        blasScratch = null;
    }

    private void destroyAllocation(Allocation allocation, boolean destroyBuffer) {
        if (allocation == null) {
            return;
        }
        if (destroyBuffer && allocation.buffer != NULL) {
            vkDestroyBuffer(device, allocation.buffer, null);
        }
        if (allocation.memory != NULL) {
            vkFreeMemory(device, allocation.memory, null);
        }
    }

    @Override
    public void close() {
        if (device == null) {
            return;
        }
        vkDeviceWaitIdle(device);
        // Phase 3 K1: after vkDeviceWaitIdle every marker submit has finished,
        // so fences and the marker command pool can be destroyed safely.
        destroySynchronization();
        java.util.Arrays.fill(slotInFlight, false);
        java.util.Arrays.fill(slotSubmittedFrame, 0L);
        java.util.Arrays.fill(slotTimeline, 0);
        nextTimelineValue = 1L;
        destroyAccelerationStructures();
        destroyOutput();
        destroyAllocation(instanceBuffer, true);
        for (int index = 0; index < FRAME_SLOT_COUNT; index++) {
            destroyAllocation(uniformBuffers[index], true);
            uniformBuffers[index] = null;
        }
        destroyAllocation(sceneBuffer, true);
        if (depthSampler != NULL) vkDestroySampler(device, depthSampler, null);
        if (descriptorPool != NULL) vkDestroyDescriptorPool(device, descriptorPool, null);
        if (pipeline != NULL) vkDestroyPipeline(device, pipeline, null);
        if (pipelineLayout != NULL) vkDestroyPipelineLayout(device, pipelineLayout, null);
        if (descriptorSetLayout != NULL) {
            vkDestroyDescriptorSetLayout(device, descriptorSetLayout, null);
        }
        if (shaderModule != NULL) vkDestroyShaderModule(device, shaderModule, null);
    }

    private static final class Allocation {
        private long buffer;
        private final long memory;
        @SuppressWarnings("unused")
        private final long size;

        private Allocation(long buffer, long memory, long size) {
            this.buffer = buffer;
            this.memory = memory;
            this.size = size;
        }
    }

    private static final class StorageImage {
        private final long image;
        private final Allocation memory;
        private final long view;
        private final int format;
        private int layout = VK_IMAGE_LAYOUT_UNDEFINED;

        private StorageImage(
                long image,
                Allocation memory,
                long view,
                int format
        ) {
            this.image = image;
            this.memory = memory;
            this.view = view;
            this.format = format;
        }

        private RayReconstructionResources.ImageResource external() {
            return new RayReconstructionResources.ImageResource(
                    image,
                    memory.memory,
                    view,
                    format
            );
        }
    }
}
