package org.malionaro.dlss_frame_generation.client.raytracing;

import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;
import org.malionaro.dlss_frame_generation.client.config.FrameGenConfig;
import org.malionaro.dlss_frame_generation.client.access.VulkanDeviceAccess;
import org.malionaro.dlss_frame_generation.client.raytracing.scene.RtBoxFormat;
import org.malionaro.dlss_frame_generation.client.raytracing.scene.SceneLights;
import org.malionaro.dlss_frame_generation.client.raytracing.scene.SectionMesher;
import org.malionaro.dlss_frame_generation.client.raytracing.scene.SectionScene;
import org.malionaro.dlss_frame_generation.client.raytracing.scene.SpriteTable;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
 *
 * <p>The scene is streamed per 16³ section ({@link SectionScene}): every
 * section owns one BLAS over its boxes, and each frame slot owns a TLAS over
 * all resident sections plus a table of their box addresses. A slot rebuilds
 * its TLAS only when the section set changed, after its fence proved it idle,
 * so streaming never waits on the device or rebuilds the whole world.</p>
 */
public final class VulkanRayQueryRenderer implements AutoCloseable {
    private static final int COLOR_FORMAT = VK_FORMAT_R8G8B8A8_UNORM;
    private static final int HDR_FORMAT = VK_FORMAT_R16G16B16A16_SFLOAT;
    private static final int HIT_DISTANCE_FORMAT = VK_FORMAT_R16_SFLOAT;
    /** Sections turned into a BLAS per frame at most. */
    private static final int SECTION_UPLOADS_PER_FRAME = 8;
    private static final int FRAME_SLOT_COUNT = 3;

    // One fence per frame slot, created signalled. markUniformSlotInFlight
    // re-arms it with an empty submit on the graphics queue, which signals
    // once the GPU finished everything queued before it. render() waits on it
    // before reusing the slot and skips the frame on timeout instead of
    // overwriting data a frame in flight still reads. The renderer records into
    // Minecraft's command buffer and never submits a frame itself, so this
    // marker submit is the earliest point a fence of our own can observe.
    private final long[] slotFences = new long[FRAME_SLOT_COUNT];
    private final VkCommandBuffer[] slotMarkerCommandBuffers =
            new VkCommandBuffer[FRAME_SLOT_COUNT];
    private long commandPool;
    private boolean fenceSyncAvailable;
    private final boolean[] slotInFlight = new boolean[FRAME_SLOT_COUNT];
    private final long[] slotSubmittedFrame = new long[FRAME_SLOT_COUNT];
    private final int[] slotTimeline = new int[FRAME_SLOT_COUNT];
    private long nextTimelineValue = 1L;
    private long lastUniformWaitWarnNanos;
    /** Resident sections, each with its own BLAS. */
    private final Map<Long, GpuSection> sections = new HashMap<>();
    /** Sections replaced or dropped, freed once no frame in flight can use them. */
    private final ArrayDeque<GpuSection> retiredSections = new ArrayDeque<>();
    /** Bumped whenever the resident set changes; slots rebuild their TLAS to match. */
    private long sceneVersion;
    private int sceneOriginX;
    private int sceneOriginY;
    private int sceneOriginZ;
    private final SlotScene[] slotScenes = new SlotScene[FRAME_SLOT_COUNT];
    private long scratchAlignment = 256L;
    private long atlasView;
    private long atlasSampler;
    private boolean loggedFirstScene;
    /** Bumped whenever descriptor contents change; each slot catches up when idle. */
    private long descriptorVersion;
    private final long[] slotDescriptorVersion = new long[FRAME_SLOT_COUNT];
    /** RT output images replaced by a resize, freed once their frames finished. */
    private final java.util.ArrayDeque<RetiredOutput> retiredOutputs = new java.util.ArrayDeque<>();

    {
        java.util.Arrays.fill(slotDescriptorVersion, -1L);
    }

    private final Logger logger;
    private final VulkanDevice minecraftDevice;
    private final VkDevice device;
    private final VkPhysicalDevice physicalDevice;
    private final SectionScene scene = new SectionScene();

    /**
     * Auto exposure, shared by all slots: uint logSum[2], uint count[2],
     * float exposure[2]. Frame parity p accumulates into [p] while the shader
     * exposes with what frame p-1 left in [1-p].
     */
    private static final int EXPOSURE_BYTES = 24;
    /** GPU timestamps around the trace, two per slot, read once the slot is idle. */
    private long timestampPool = NULL;
    private double timestampPeriodNanos = 1.0;
    private final boolean[] slotTimed = new boolean[FRAME_SLOT_COUNT];
    private double gpuMillisSum;
    private double cpuMillisSum;
    private int gpuSamples;
    private int cpuSamples;
    private long lastTimingLogNanos = System.nanoTime();
    private Allocation exposureBuffer;
    private boolean exposureCleared;
    private final Allocation[] uniformBuffers =
            new Allocation[FRAME_SLOT_COUNT];

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
            long cpuStart = System.nanoTime();
            ensureOutput(width, height);
            // CPU side first: section selection, snapshots and finished
            // meshes never wait on the GPU.
            Minecraft minecraft = Minecraft.getInstance();
            scene.update(minecraft, config.rayTracingMaxDistance,
                    RayTracingFrameData.cameraPosition(minecraft));
            if (scene.originX() != sceneOriginX || scene.originY() != sceneOriginY
                    || scene.originZ() != sceneOriginZ) {
                // Every instance offset is relative to the origin.
                sceneOriginX = scene.originX();
                sceneOriginY = scene.originY();
                sceneOriginZ = scene.originZ();
                sceneVersion++;
            }
            int frameSlot = (int) (frameNumber % FRAME_SLOT_COUNT);
            // Never overwrite a slot the GPU may still read. This wait
            // also proves every frame before (frameNumber - FRAME_SLOT_COUNT)
            // finished: the slot fence was armed while that frame was
            // recorded. Section retirement and the slot's TLAS, scratch and
            // tables below rely on exactly that guarantee.
            if (!awaitUniformSlotFence(frameSlot)) {
                return false;
            }
            readSlotTiming(frameSlot);
            releaseRetiredOutputs();
            releaseRetiredSections();
            long view = currentAtlasView();
            if (view == NULL) {
                return false;
            }
            if (view != atlasView) {
                atlasView = view;
                descriptorsDirty = true;
            }
            boolean recordedBuilds = applySceneChanges(commandBuffer, frameSlot);
            if (sections.isEmpty()) {
                finishWithoutTrace(frameSlot, recordedBuilds);
                return false;
            }
            if (!loggedFirstScene) {
                loggedFirstScene = true;
                logger.info("Hardware RT scene streaming per section ({} resident)", sections.size());
            }
            SlotScene slotScene = slotScenes[frameSlot];
            if (slotScene.version != sceneVersion) {
                rebuildSlotTlas(commandBuffer, slotScene);
                recordedBuilds = true;
            }
            uploadSlotTables(slotScene);

            RayTracingFrameData frame = RayTracingFrameData.capture(
                    minecraft, width, height, config,
                    slotScene.originX, slotScene.originY, slotScene.originZ);
            if (frame == null) {
                finishWithoutTrace(frameSlot, recordedBuilds);
                return false;
            }
            ByteBuffer nativeData = frame.toNativeBuffer();
            vkCmdUpdateBuffer(commandBuffer, uniformBuffers[frameSlot].buffer, 0L, nativeData);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkBufferMemoryBarrier.Buffer barrier = VkBufferMemoryBarrier.calloc(1, stack)
                        .sType$Default()
                        .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                        .dstAccessMask(VK_ACCESS_UNIFORM_READ_BIT)
                        .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                        .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                        .buffer(uniformBuffers[frameSlot].buffer)
                        .offset(0L)
                        .size(RayTracingFrameData.BYTE_SIZE);
                vkCmdPipelineBarrier(
                        commandBuffer,
                        VK_PIPELINE_STAGE_TRANSFER_BIT,
                        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                        0,
                        null,
                        barrier,
                        null
                );
            }
            markUniformSlotInFlight(frameSlot);
            if (descriptorDepthView != depthView.vkImageView()) {
                // Earlier slots keep their old view until they come round
                // again (not in flight), so no device wait is needed.
                descriptorDepthView = depthView.vkImageView();
                descriptorsDirty = true;
            }
            if (descriptorsDirty) {
                descriptorVersion++;
                descriptorsDirty = false;
            }
            // Only this slot's set: it is guaranteed idle after the fence wait
            // above, while the other slots' sets may still be bound in flight.
            if (slotDescriptorVersion[frameSlot] != descriptorVersion
                    || slotScene.descriptorsStale) {
                updateDescriptors(descriptorDepthView, frameSlot);
                slotDescriptorVersion[frameSlot] = descriptorVersion;
                slotScene.descriptorsStale = false;
            }

            recordHybridPass(
                    commandBuffer,
                    frame.frameIndex() & 1,
                    frameSlot,
                    colorView.texture().vkImage(),
                    depthView.texture().vkImage(),
                    descriptorSets[frameSlot],
                    width,
                    height
            );
            frameNumber++;
            renderedFrame = true;
            cpuMillisSum += (System.nanoTime() - cpuStart) / 1.0e6;
            cpuSamples++;
            logTimingIfDue();
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

    /** Sections resident on the GPU and sections still queued or meshing. */
    public String sceneStatus() {
        return sections.size() + " sections, " + scene.pendingCount() + " pending";
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
        scratchAlignment = queryScratchAlignment();
        for (int index = 0; index < FRAME_SLOT_COUNT; index++) {
            SlotScene slotScene = new SlotScene();
            slotScene.lights = createBuffer(
                    SceneLights.BUFFER_BYTES,
                    VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                    VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                    false
            );
            slotScenes[index] = slotScene;
        }
        for (int index = 0; index < FRAME_SLOT_COUNT; index++) {
            uniformBuffers[index] = createBuffer(
                    RayTracingFrameData.BYTE_SIZE,
                    VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                    VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT
                            | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                    false
            );
        }
        exposureBuffer = createBuffer(
                EXPOSURE_BYTES,
                VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                false
        );
        createTimestampPool();
        createPipeline();
        setupSlotSynchronization();
        initialized = true;
    }

    private void createTimestampPool() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceProperties properties = VkPhysicalDeviceProperties.calloc(stack);
            vkGetPhysicalDeviceProperties(physicalDevice, properties);
            timestampPeriodNanos = properties.limits().timestampPeriod();
            VkQueryPoolCreateInfo info = VkQueryPoolCreateInfo.calloc(stack)
                    .sType$Default()
                    .queryType(VK_QUERY_TYPE_TIMESTAMP)
                    .queryCount(FRAME_SLOT_COUNT * 2);
            LongBuffer pool = stack.mallocLong(1);
            if (timestampPeriodNanos > 0.0
                    && vkCreateQueryPool(device, info, null, pool) == VK_SUCCESS) {
                timestampPool = pool.get(0);
            }
        }
    }

    /** The slot's previous trace has finished (fence waited): read its GPU time. */
    private void readSlotTiming(int frameSlot) {
        if (timestampPool == NULL || !slotTimed[frameSlot]) {
            return;
        }
        slotTimed[frameSlot] = false;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            java.nio.LongBuffer stamps = stack.mallocLong(2);
            if (vkGetQueryPoolResults(device, timestampPool, frameSlot * 2, 2, stamps,
                    Long.BYTES, VK_QUERY_RESULT_64_BIT) == VK_SUCCESS) {
                long ticks = stamps.get(1) - stamps.get(0);
                if (ticks > 0) {
                    gpuMillisSum += ticks * timestampPeriodNanos / 1.0e6;
                    gpuSamples++;
                }
            }
        }
    }

    /** Every 5 s: how long the RT pass takes, to tell RT cost from other slowdowns. */
    private void logTimingIfDue() {
        long now = System.nanoTime();
        if (now - lastTimingLogNanos < 5_000_000_000L || cpuSamples == 0) {
            return;
        }
        double seconds = (now - lastTimingLogNanos) / 1.0e9;
        logger.info("RT pass: {} frames/s, GPU {} ms, CPU {} ms per frame; {}",
                String.format(java.util.Locale.ROOT, "%.1f", cpuSamples / seconds),
                gpuSamples > 0 ? String.format(java.util.Locale.ROOT, "%.2f", gpuMillisSum / gpuSamples) : "n/a",
                String.format(java.util.Locale.ROOT, "%.2f", cpuMillisSum / cpuSamples),
                sceneStatus());
        lastTimingLogNanos = now;
        gpuMillisSum = 0.0;
        cpuMillisSum = 0.0;
        gpuSamples = 0;
        cpuSamples = 0;
    }

    /** RT shader pack in use; empty for the built-in shader. */
    private String activeShaderPack = RtShaderPacks.BUILT_IN;

    public String activeShaderPack() {
        return activeShaderPack;
    }

    /**
     * The selected RT shader pack, or the built-in shader when the pack is
     * missing or does not compile (the error goes to the log).
     */
    private ByteBuffer compileSelectedShader() throws IOException {
        String selected = org.malionaro.dlss_frame_generation.client.config.FrameGenConfig
                .getInstance().rayTracingShaderPack;
        if (selected != null && !selected.isEmpty()) {
            try {
                RtShaderPacks.Source pack = RtShaderPacks.load(selected);
                ByteBuffer spirv = RayQueryShaderCompiler.compileSource(pack.code(), pack.name());
                activeShaderPack = pack.name();
                logger.info("Using RT shader pack {}", pack.name());
                return spirv;
            } catch (IOException exception) {
                logger.error("RT shader pack {} failed, using the built-in shader: {}",
                        selected, exception.getMessage());
            }
        }
        activeShaderPack = RtShaderPacks.BUILT_IN;
        return RayQueryShaderCompiler.compile(RtShaderPacks.BUILT_IN_RESOURCE);
    }

    private void createPipeline() throws IOException {
        ByteBuffer spirv = compileSelectedShader();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer result = stack.mallocLong(1);
            VkShaderModuleCreateInfo shaderInfo = VkShaderModuleCreateInfo.calloc(stack)
                    .sType$Default()
                    .pCode(spirv);
            check(vkCreateShaderModule(device, shaderInfo, null, result), "create RT shader module");
            shaderModule = result.get(0);

            VkDescriptorSetLayoutBinding.Buffer bindings =
                    VkDescriptorSetLayoutBinding.calloc(14, stack);
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
            bindings.get(10)
                    .binding(10)
                    .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .descriptorCount(1)
                    .stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            bindings.get(11)
                    .binding(11)
                    .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .descriptorCount(1)
                    .stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            bindings.get(12)
                    .binding(12)
                    .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1)
                    .stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            bindings.get(13)
                    .binding(13)
                    .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
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
            // Block atlas: texel-exact like vanilla terrain, mip 0 only.
            check(vkCreateSampler(device, samplerInfo, null, result),
                    "create RT atlas sampler");
            atlasSampler = result.get(0);

            VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(5, stack);
            poolSizes.get(0)
                    .type(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR)
                    .descriptorCount(FRAME_SLOT_COUNT);
            poolSizes.get(1)
                    .type(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(6 * FRAME_SLOT_COUNT);
            poolSizes.get(2)
                    .type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .descriptorCount(4 * FRAME_SLOT_COUNT);
            poolSizes.get(3)
                    .type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                    .descriptorCount(FRAME_SLOT_COUNT);
            poolSizes.get(4)
                    .type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(2 * FRAME_SLOT_COUNT);
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
        // Only on actual resize/recreate, which follows every dynamic
        // resolution step. Frames in flight may still use the old images, so
        // they are retired and freed once the slot fences prove those frames
        // finished, instead of idling the whole device here.
        retireOutput();

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

    /**
     * Applies removed and finished sections. New sections get their BLAS
     * built into this slot's scratch arena (idle after the fence wait).
     *
     * @return whether build commands were recorded into {@code commandBuffer}
     */
    private boolean applySceneChanges(VkCommandBuffer commandBuffer, int slot) {
        boolean changed = false;
        for (Long key : scene.drainRemoved()) {
            GpuSection removed = sections.remove(key);
            if (removed != null) {
                retireSection(removed);
                changed = true;
            }
        }
        List<SectionMesher.SectionMesh> meshes = scene.pollReady(SECTION_UPLOADS_PER_FRAME);
        List<GpuSection> built = new ArrayList<>();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            for (SectionMesher.SectionMesh mesh : meshes) {
                GpuSection previous;
                if (mesh.boxes() == null || mesh.boxCount() == 0) {
                    previous = sections.remove(mesh.key());
                } else {
                    GpuSection section = createSection(mesh, stack);
                    section.key = mesh.key();
                    previous = sections.put(mesh.key(), section);
                    built.add(section);
                    changed = true;
                }
                if (previous != null) {
                    retireSection(previous);
                    changed = true;
                }
            }
            if (!built.isEmpty()) {
                recordSectionBuilds(commandBuffer, slotScenes[slot], built, stack);
            }
        }
        if (changed) {
            sceneVersion++;
        }
        return !built.isEmpty();
    }

    private GpuSection createSection(SectionMesher.SectionMesh mesh, MemoryStack stack) {
        GpuSection section = new GpuSection();
        try {
            section.boxCount = mesh.boxCount();
            long bytes = (long) mesh.boxCount() * RtBoxFormat.BYTES_PER_BOX;
            // Read by traversal on every candidate hit: device-local when the
            // GPU exposes host-visible VRAM (resizable BAR), else system memory.
            section.boxes = createHostVisibleBuffer(
                    bytes,
                    VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
                            | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT
                            | VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR
            );
            upload(section.boxes, mesh.boxes());
            section.boxesAddress = bufferAddress(section.boxes.buffer, stack);

            VkAccelerationStructureBuildGeometryInfoKHR build = blasBuildInfo(stack, section.boxesAddress);
            VkAccelerationStructureBuildSizesInfoKHR sizes =
                    VkAccelerationStructureBuildSizesInfoKHR.calloc(stack).sType$Default();
            vkGetAccelerationStructureBuildSizesKHR(
                    device,
                    VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR,
                    build,
                    stack.ints(section.boxCount),
                    sizes
            );
            section.storage = createBuffer(
                    sizes.accelerationStructureSize(),
                    VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR
                            | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,
                    VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
                    true
            );
            section.blas = createAccelerationStructure(
                    section.storage.buffer,
                    sizes.accelerationStructureSize(),
                    VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR,
                    stack
            );
            section.scratchSize = sizes.buildScratchSize();
            section.blasAddress = vkGetAccelerationStructureDeviceAddressKHR(
                    device,
                    VkAccelerationStructureDeviceAddressInfoKHR.calloc(stack)
                            .sType$Default()
                            .accelerationStructure(section.blas)
            );
            return section;
        } catch (RuntimeException error) {
            destroySection(section);
            throw error;
        }
    }

    private static VkAccelerationStructureBuildGeometryInfoKHR blasBuildInfo(
            MemoryStack stack,
            long boxesAddress
    ) {
        return VkAccelerationStructureBuildGeometryInfoKHR.calloc(stack)
                .sType$Default()
                .type(VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR)
                .flags(VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR)
                .mode(VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR)
                .pGeometries(createAabbGeometry(stack, boxesAddress));
    }

    /** Records the BLAS builds; each gets its own region of the slot's scratch arena. */
    private void recordSectionBuilds(
            VkCommandBuffer commandBuffer,
            SlotScene slotScene,
            List<GpuSection> built,
            MemoryStack stack
    ) {
        long total = 0L;
        for (GpuSection section : built) {
            total += align(section.scratchSize, scratchAlignment);
        }
        if (slotScene.blasScratch == null || slotScene.blasScratch.size < total) {
            destroyAllocation(slotScene.blasScratch, true);
            slotScene.blasScratch = null;
            slotScene.blasScratch = createBuffer(
                    total,
                    VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,
                    VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
                    true
            );
        }
        long scratchBase = align(bufferAddress(slotScene.blasScratch.buffer, stack), scratchAlignment);
        if (scratchBase + total > bufferAddress(slotScene.blasScratch.buffer, stack)
                + slotScene.blasScratch.size) {
            // Base address was not aligned: grow once with slack.
            destroyAllocation(slotScene.blasScratch, true);
            slotScene.blasScratch = createBuffer(
                    total + scratchAlignment,
                    VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,
                    VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
                    true
            );
            scratchBase = align(bufferAddress(slotScene.blasScratch.buffer, stack), scratchAlignment);
        }
        long offset = 0L;
        for (GpuSection section : built) {
            long scratchAddress = scratchBase + offset;
            VkAccelerationStructureBuildGeometryInfoKHR build = blasBuildInfo(stack, section.boxesAddress)
                    .dstAccelerationStructure(section.blas)
                    .scratchData(address -> address.deviceAddress(scratchAddress));
            recordBuild(commandBuffer, build, section.boxCount, stack);
            offset += align(section.scratchSize, scratchAlignment);
        }
        // The slot TLAS build reads these BLAS.
        memoryBarrier(
                commandBuffer,
                VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR,
                VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR,
                stack
        );
    }

    /** Rewrites this slot's instances and section table and rebuilds its TLAS. */
    private void rebuildSlotTlas(VkCommandBuffer commandBuffer, SlotScene slotScene) {
        int count = sections.size();
        ensureSlotCapacity(slotScene, count);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer mapped = stack.mallocPointer(1);
            check(vkMapMemory(device, slotScene.instances.memory, 0,
                    (long) count * VkAccelerationStructureInstanceKHR.SIZEOF, 0, mapped),
                    "map RT instances");
            long instanceBase = mapped.get(0);
            check(vkMapMemory(device, slotScene.sectionTable.memory, 0,
                    (long) count * Long.BYTES, 0, mapped), "map RT section table");
            long tableBase = mapped.get(0);
            int index = 0;
            slotScene.originX = sceneOriginX;
            slotScene.originY = sceneOriginY;
            slotScene.originZ = sceneOriginZ;
            for (GpuSection section : sections.values()) {
                VkAccelerationStructureInstanceKHR instance = VkAccelerationStructureInstanceKHR.create(
                        instanceBase + (long) index * VkAccelerationStructureInstanceKHR.SIZEOF);
                // Section-local boxes, placed relative to the scene origin:
                // small integers, exact in float anywhere in the world.
                float offsetX = SectionPos.sectionToBlockCoord(SectionPos.x(section.key)) - sceneOriginX;
                float offsetY = SectionPos.sectionToBlockCoord(SectionPos.y(section.key)) - sceneOriginY;
                float offsetZ = SectionPos.sectionToBlockCoord(SectionPos.z(section.key)) - sceneOriginZ;
                instance.transform().matrix(stack.floats(
                        1, 0, 0, offsetX,
                        0, 1, 0, offsetY,
                        0, 0, 1, offsetZ
                ));
                instance.instanceCustomIndex(index)
                        .mask(0xff)
                        .instanceShaderBindingTableRecordOffset(0)
                        .flags(VK_GEOMETRY_INSTANCE_TRIANGLE_FACING_CULL_DISABLE_BIT_KHR)
                        .accelerationStructureReference(section.blasAddress);
                // uvec2 in the shader: little-endian 64-bit address.
                MemoryUtil.memPutLong(tableBase + (long) index * Long.BYTES, section.boxesAddress);
                section.lastUsedFrame = frameNumber;
                index++;
            }
            vkUnmapMemory(device, slotScene.instances.memory);
            vkUnmapMemory(device, slotScene.sectionTable.memory);

            long instanceAddress = bufferAddress(slotScene.instances.buffer, stack);
            VkAccelerationStructureBuildGeometryInfoKHR build =
                    VkAccelerationStructureBuildGeometryInfoKHR.calloc(stack)
                            .sType$Default()
                            .type(VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR)
                            .flags(VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR)
                            .mode(VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR)
                            .pGeometries(createInstanceGeometry(stack, instanceAddress));
            VkAccelerationStructureBuildSizesInfoKHR sizes =
                    VkAccelerationStructureBuildSizesInfoKHR.calloc(stack).sType$Default();
            vkGetAccelerationStructureBuildSizesKHR(
                    device,
                    VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR,
                    build,
                    stack.ints(count),
                    sizes
            );
            if (slotScene.tlasStorage == null
                    || slotScene.tlasStorage.size < sizes.accelerationStructureSize()) {
                if (slotScene.tlas != NULL) {
                    vkDestroyAccelerationStructureKHR(device, slotScene.tlas, null);
                    slotScene.tlas = NULL;
                }
                destroyAllocation(slotScene.tlasStorage, true);
                // Headroom so streaming does not regrow it every few sections.
                long size = sizes.accelerationStructureSize() * 2L;
                slotScene.tlasStorage = createBuffer(
                        size,
                        VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR
                                | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,
                        VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
                        true
                );
                slotScene.tlas = createAccelerationStructure(
                        slotScene.tlasStorage.buffer,
                        size,
                        VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR,
                        stack
                );
                slotScene.descriptorsStale = true;
            }
            if (slotScene.tlasScratch == null
                    || slotScene.tlasScratch.size < sizes.buildScratchSize() + scratchAlignment) {
                destroyAllocation(slotScene.tlasScratch, true);
                slotScene.tlasScratch = createBuffer(
                        sizes.buildScratchSize() * 2L + scratchAlignment,
                        VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,
                        VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
                        true
                );
            }
            long scratchAddress = align(bufferAddress(slotScene.tlasScratch.buffer, stack), scratchAlignment);
            build.dstAccelerationStructure(slotScene.tlas)
                    .scratchData(address -> address.deviceAddress(scratchAddress));
            recordBuild(commandBuffer, build, count, stack);
            memoryBarrier(
                    commandBuffer,
                    VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR,
                    VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR,
                    stack
            );
        }
        slotScene.version = sceneVersion;
    }

    private void ensureSlotCapacity(SlotScene slotScene, int count) {
        if (slotScene.instances != null && slotScene.capacity >= count) {
            return;
        }
        int capacity = Math.max(256, Integer.highestOneBit(Math.max(1, count - 1)) << 1);
        destroyAllocation(slotScene.instances, true);
        destroyAllocation(slotScene.sectionTable, true);
        slotScene.instances = createBuffer(
                (long) capacity * VkAccelerationStructureInstanceKHR.SIZEOF,
                VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT
                        | VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                true
        );
        slotScene.sectionTable = createBuffer(
                (long) capacity * Long.BYTES,
                VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                false
        );
        slotScene.capacity = capacity;
        slotScene.descriptorsStale = true;
    }

    /** Lights and sprite rectangles for this slot; it is idle after the fence wait. */
    private void uploadSlotTables(SlotScene slotScene) {
        if (slotScene.lightsVersion != scene.lightsVersion()) {
            upload(slotScene.lights, scene.lightBuffer());
            slotScene.lightsVersion = scene.lightsVersion();
        }
        SpriteTable sprites = scene.sprites();
        long version = sprites.version();
        if (slotScene.sprites == null || slotScene.spriteVersion != version) {
            ByteBuffer data = sprites.snapshot();
            long needed = Math.max(SpriteTable.BYTES_PER_SPRITE, data.remaining());
            if (slotScene.sprites == null || slotScene.spriteCapacity < needed) {
                destroyAllocation(slotScene.sprites, true);
                long capacity = Math.max(64L * SpriteTable.BYTES_PER_SPRITE, needed * 2L);
                slotScene.sprites = createBuffer(
                        capacity,
                        VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                        false
                );
                slotScene.spriteCapacity = capacity;
                slotScene.descriptorsStale = true;
            }
            upload(slotScene.sprites, data);
            slotScene.spriteVersion = version;
        }
    }

    /**
     * Ends a frame that recorded scene builds but traces nothing. The builds
     * used this slot's scratch, so the slot counts as in flight like a traced
     * frame; otherwise the next frame would overwrite scratch the GPU still uses.
     */
    private void finishWithoutTrace(int frameSlot, boolean recordedBuilds) {
        if (recordedBuilds) {
            markUniformSlotInFlight(frameSlot);
            frameNumber++;
        }
    }

    private void retireSection(GpuSection section) {
        // Frames up to the previous one may still trace it through their TLAS.
        section.lastUsedFrame = Math.max(section.lastUsedFrame, frameNumber - 1);
        retiredSections.add(section);
    }

    /** Frees retired sections the slot-fence guarantee covers; call after the wait in render(). */
    private void releaseRetiredSections() {
        java.util.Iterator<GpuSection> iterator = retiredSections.iterator();
        while (iterator.hasNext()) {
            GpuSection section = iterator.next();
            if (section.lastUsedFrame >= frameNumber - FRAME_SLOT_COUNT) {
                continue;
            }
            destroySection(section);
            iterator.remove();
        }
    }

    private void destroySection(GpuSection section) {
        if (section == null) {
            return;
        }
        if (section.blas != NULL) {
            vkDestroyAccelerationStructureKHR(device, section.blas, null);
            section.blas = NULL;
        }
        destroyAllocation(section.storage, true);
        destroyAllocation(section.boxes, true);
        section.storage = null;
        section.boxes = null;
    }

    private void destroySlotScene(SlotScene slotScene) {
        if (slotScene == null) {
            return;
        }
        if (slotScene.tlas != NULL) {
            vkDestroyAccelerationStructureKHR(device, slotScene.tlas, null);
            slotScene.tlas = NULL;
        }
        destroyAllocation(slotScene.tlasStorage, true);
        destroyAllocation(slotScene.tlasScratch, true);
        destroyAllocation(slotScene.blasScratch, true);
        destroyAllocation(slotScene.instances, true);
        destroyAllocation(slotScene.sectionTable, true);
        destroyAllocation(slotScene.lights, true);
        destroyAllocation(slotScene.sprites, true);
    }

    /** The block atlas' Vulkan view, or NULL while it is not available. */
    private static long currentAtlasView() {
        try {
            AbstractTexture texture = Minecraft.getInstance().getTextureManager()
                    .getTexture(TextureAtlas.LOCATION_BLOCKS);
            if (texture != null
                    && texture.getTextureView() instanceof VulkanGpuTextureView view
                    && !view.isClosed()) {
                return view.vkImageView();
            }
        } catch (RuntimeException ignored) {
            // Resource reload in progress; try again next frame.
        }
        return NULL;
    }

    private long queryScratchAlignment() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceAccelerationStructurePropertiesKHR properties =
                    VkPhysicalDeviceAccelerationStructurePropertiesKHR.calloc(stack).sType$Default();
            VkPhysicalDeviceProperties2 properties2 = VkPhysicalDeviceProperties2.calloc(stack)
                    .sType$Default()
                    .pNext(properties.address());
            VK11.vkGetPhysicalDeviceProperties2(physicalDevice, properties2);
            long alignment = properties.minAccelerationStructureScratchOffsetAlignment();
            return alignment > 0 ? alignment : 256L;
        } catch (RuntimeException | LinkageError error) {
            return 256L;
        }
    }

    private static long align(long value, long alignment) {
        return (value + alignment - 1) / alignment * alignment;
    }

    /** Host-visible buffer, preferring VRAM the CPU can map (resizable BAR). */
    private Allocation createHostVisibleBuffer(long size, int usage) {
        int coherent = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
        try {
            return createBuffer(size, usage, coherent | VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, true);
        } catch (IllegalStateException unavailable) {
            return createBuffer(size, usage, coherent, true);
        }
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
                .stride(RtBoxFormat.BYTES_PER_BOX);
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

    /** Rewrites one slot's descriptor set; the caller guarantees it is not in flight. */
    private void updateDescriptors(long depthView, int slot) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkWriteDescriptorSetAccelerationStructureKHR accelerationInfo =
                    VkWriteDescriptorSetAccelerationStructureKHR.calloc(stack)
                            .sType$Default()
                            .pAccelerationStructures(stack.longs(slotScenes[slot].tlas));
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
            SlotScene slotScene = slotScenes[slot];
            VkDescriptorBufferInfo.Buffer sceneInfo = VkDescriptorBufferInfo.calloc(1, stack);
            sceneInfo.get(0)
                    .buffer(slotScene.sectionTable.buffer)
                    .offset(0)
                    .range(VK_WHOLE_SIZE);
            VkDescriptorBufferInfo.Buffer lightInfo = VkDescriptorBufferInfo.calloc(1, stack);
            lightInfo.get(0)
                    .buffer(slotScene.lights.buffer)
                    .offset(0)
                    .range(SceneLights.BUFFER_BYTES);
            VkDescriptorBufferInfo.Buffer spriteInfo = VkDescriptorBufferInfo.calloc(1, stack);
            spriteInfo.get(0)
                    .buffer(slotScene.sprites.buffer)
                    .offset(0)
                    .range(VK_WHOLE_SIZE);
            VkDescriptorBufferInfo.Buffer exposureInfo = VkDescriptorBufferInfo.calloc(1, stack);
            exposureInfo.get(0)
                    .buffer(exposureBuffer.buffer)
                    .offset(0)
                    .range(EXPOSURE_BYTES);
            VkDescriptorImageInfo.Buffer atlasInfo = VkDescriptorImageInfo.calloc(1, stack);
            atlasInfo.get(0)
                    .sampler(atlasSampler)
                    .imageView(atlasView)
                    // Minecraft keeps sampled textures in GENERAL.
                    .imageLayout(VK_IMAGE_LAYOUT_GENERAL);
            {
                VkDescriptorBufferInfo.Buffer frameInfo =
                        VkDescriptorBufferInfo.calloc(1, stack);
                frameInfo.get(0)
                        .buffer(uniformBuffers[slot].buffer)
                        .offset(0)
                        .range(RayTracingFrameData.BYTE_SIZE);
                VkWriteDescriptorSet.Buffer writes =
                        VkWriteDescriptorSet.calloc(14, stack);
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
                writes.get(10)
                        .sType$Default()
                        .dstSet(targetSet)
                        .dstBinding(10)
                        .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                        .pBufferInfo(lightInfo);
                writes.get(11)
                        .sType$Default()
                        .dstSet(targetSet)
                        .dstBinding(11)
                        .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                        .pBufferInfo(spriteInfo);
                writes.get(12)
                        .sType$Default()
                        .dstSet(targetSet)
                        .dstBinding(12)
                        .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                        .pImageInfo(atlasInfo);
                writes.get(13)
                        .sType$Default()
                        .dstSet(targetSet)
                        .dstBinding(13)
                        .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                        .pBufferInfo(exposureInfo);
                imageInfo.position(0).limit(6);
                vkUpdateDescriptorSets(device, writes, null);
            }
        }
    }

    private void recordHybridPass(
            VkCommandBuffer commandBuffer,
            int exposureParity,
            int frameSlot,
            long colorImage,
            long depthImage,
            long descriptorSet,
            int width,
            int height
    ) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // Animated atlas sprites are written earlier in the frame; make
            // those writes visible to the trace that samples the atlas.
            memoryBarrier(
                    commandBuffer,
                    VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK_ACCESS_TRANSFER_WRITE_BIT | VK_ACCESS_SHADER_WRITE_BIT,
                    VK_ACCESS_SHADER_READ_BIT,
                    stack
            );
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

            // Fresh accumulators for this frame's parity; the other half
            // holds last frame's luminance, which this frame exposes with.
            if (!exposureCleared) {
                vkCmdFillBuffer(commandBuffer, exposureBuffer.buffer, 0L, EXPOSURE_BYTES, 0);
                exposureCleared = true;
            } else {
                vkCmdFillBuffer(commandBuffer, exposureBuffer.buffer, exposureParity * 4L, 4L, 0);
                vkCmdFillBuffer(commandBuffer, exposureBuffer.buffer, 8L + exposureParity * 4L, 4L, 0);
            }
            memoryBarrier(
                    commandBuffer,
                    VK_PIPELINE_STAGE_TRANSFER_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK_ACCESS_TRANSFER_WRITE_BIT | VK_ACCESS_SHADER_WRITE_BIT,
                    VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT,
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
            if (timestampPool != NULL) {
                vkCmdResetQueryPool(commandBuffer, timestampPool, frameSlot * 2, 2);
                vkCmdWriteTimestamp(commandBuffer, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                        timestampPool, frameSlot * 2);
            }
            vkCmdDispatch(commandBuffer, (width + 7) / 8, (height + 7) / 8, 1);
            if (timestampPool != NULL) {
                vkCmdWriteTimestamp(commandBuffer, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
                        timestampPool, frameSlot * 2 + 1);
                slotTimed[frameSlot] = true;
            }
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
            Allocation allocation;
            try {
                allocation = allocateMemory(requirements, memoryProperties, deviceAddress);
            } catch (RuntimeException error) {
                // No such memory type or out of memory: callers may retry with
                // other properties, so do not leak the buffer.
                vkDestroyBuffer(device, buffer, null);
                throw error;
            }
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

    /**
     * Waits up to 100&nbsp;ms until the slot may be reused.
     *
     * @return {@code true} once the GPU finished with the slot (or it was not in
     *         use); {@code false} when the caller must skip the frame. A busy
     *         slot is never force-retired: the next frame waits again.
     */
    private boolean awaitUniformSlotFence(int slot) {
        if (slot < 0 || slot >= FRAME_SLOT_COUNT) {
            return true;
        }
        if (!slotInFlight[slot]) {
            return true;
        }
        long lastFrame = slotSubmittedFrame[slot];
        if (!fenceSyncAvailable || slotFences[slot] == NULL) {
            // No graphics queue for the marker submit: count frames instead.
            awaitUniformSlotCpuFallback(slot, lastFrame);
            return !slotInFlight[slot];
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
            warnUniformWaitThrottled(
                    "Uniform slot {} VkFence wait threw; keeping slot in flight ({})",
                    slot, throwable.toString());
            return false;
        }
        if (result == VK_SUCCESS) {
            retireSlot(slot);
            return true;
        }
        if (result == VK_TIMEOUT) {
            warnUniformWaitThrottled(
                    "Uniform slot {} still in flight after 100ms (frame {} last {}); skipping frame, retry next frame",
                    slot, frameNumber, lastFrame);
        } else {
            warnUniformWaitThrottled(
                    "Uniform slot {} VkFence wait failed with VkResult {}; skipping frame, retry next frame",
                    slot, result);
        }
        return false;
    }

    private void warnUniformWaitThrottled(String format, Object... args) {
        long now = System.nanoTime();
        if (now - lastUniformWaitWarnNanos < 1_000_000_000L) {
            return;
        }
        lastUniformWaitWarnNanos = now;
        logger.warn(format, args);
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
        if (frameNumber - lastFrame >= FRAME_SLOT_COUNT - 1) {
            retireSlot(slot);
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

    /** Moves the current output images to the retire queue (see render()). */
    private void retireOutput() {
        if (outputImage == NULL && outputMemory == null && noisyColor == null) {
            return;
        }
        RetiredOutput retired = new RetiredOutput();
        retired.outputImage = outputImage;
        retired.outputView = outputView;
        retired.outputMemory = outputMemory;
        retired.storageImages = new StorageImage[] {
                noisyColor, diffuseAlbedo, specularAlbedo, normalRoughness, specularHitDistance
        };
        // Frames up to frameNumber - 1 may have used these images.
        retired.lastUsedFrame = frameNumber - 1;
        retiredOutputs.add(retired);
        outputImage = NULL;
        outputView = NULL;
        outputMemory = null;
        noisyColor = diffuseAlbedo = specularAlbedo = normalRoughness = specularHitDistance = null;
    }

    /** Frees retired outputs the slot-fence guarantee covers; call after the wait in render(). */
    private void releaseRetiredOutputs() {
        java.util.Iterator<RetiredOutput> iterator = retiredOutputs.iterator();
        while (iterator.hasNext()) {
            RetiredOutput retired = iterator.next();
            if (retired.lastUsedFrame >= frameNumber - FRAME_SLOT_COUNT) {
                continue;
            }
            destroyRetiredOutput(retired);
            iterator.remove();
        }
    }

    private void destroyRetiredOutput(RetiredOutput retired) {
        for (StorageImage image : retired.storageImages) {
            destroyStorageImage(image);
        }
        if (retired.outputView != NULL) vkDestroyImageView(device, retired.outputView, null);
        if (retired.outputImage != NULL) vkDestroyImage(device, retired.outputImage, null);
        destroyAllocation(retired.outputMemory, false);
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
        // After vkDeviceWaitIdle every marker submit has finished,
        // so fences and the marker command pool can be destroyed safely.
        destroySynchronization();
        java.util.Arrays.fill(slotInFlight, false);
        java.util.Arrays.fill(slotSubmittedFrame, 0L);
        java.util.Arrays.fill(slotTimeline, 0);
        nextTimelineValue = 1L;
        // Every section, slot scene and retired output; the device is idle.
        scene.close();
        for (GpuSection section : sections.values()) {
            destroySection(section);
        }
        sections.clear();
        for (GpuSection section : retiredSections) {
            destroySection(section);
        }
        retiredSections.clear();
        for (int index = 0; index < FRAME_SLOT_COUNT; index++) {
            destroySlotScene(slotScenes[index]);
            slotScenes[index] = null;
        }
        for (RetiredOutput retired : retiredOutputs) {
            destroyRetiredOutput(retired);
        }
        retiredOutputs.clear();
        destroyOutput();
        for (int index = 0; index < FRAME_SLOT_COUNT; index++) {
            destroyAllocation(uniformBuffers[index], true);
            uniformBuffers[index] = null;
        }
        destroyAllocation(exposureBuffer, true);
        exposureBuffer = null;
        exposureCleared = false;
        if (depthSampler != NULL) vkDestroySampler(device, depthSampler, null);
        if (atlasSampler != NULL) vkDestroySampler(device, atlasSampler, null);
        if (descriptorPool != NULL) vkDestroyDescriptorPool(device, descriptorPool, null);
        if (timestampPool != NULL) {
            vkDestroyQueryPool(device, timestampPool, null);
            timestampPool = NULL;
        }
        java.util.Arrays.fill(slotTimed, false);
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

    /** One section's boxes and BLAS. */
    private static final class GpuSection {
        private long key;
        private Allocation boxes;
        private long boxesAddress;
        private int boxCount;
        private Allocation storage;
        private long blas;
        private long blasAddress;
        private long scratchSize;
        private long lastUsedFrame;
    }

    /** Per frame slot: TLAS over all sections plus the tables the shader reads. */
    private static final class SlotScene {
        private Allocation instances;
        private Allocation sectionTable;
        private int capacity;
        private Allocation tlasStorage;
        private long tlas;
        private Allocation tlasScratch;
        private Allocation blasScratch;
        private Allocation lights;
        private Allocation sprites;
        private long spriteCapacity;
        private long version = -1L;
        private long lightsVersion = -1L;
        private long spriteVersion = -1L;
        private boolean descriptorsStale = true;
        /** Scene origin this slot's TLAS was built for. */
        private int originX;
        private int originY;
        private int originZ;
    }

    private static final class RetiredOutput {
        private long outputImage;
        private long outputView;
        private Allocation outputMemory;
        private StorageImage[] storageImages;
        private long lastUsedFrame;
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
