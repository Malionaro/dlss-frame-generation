package org.malionaro.dlss_frame_generation.client.motion;

import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView;
import org.joml.Matrix4f;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;
import org.malionaro.dlss_frame_generation.client.frame.FrameIdentity;
import org.malionaro.dlss_frame_generation.client.frame.FrameResourceSet;
import org.malionaro.dlss_frame_generation.client.backend.FrameGenerationBackend;
import org.malionaro.dlss_frame_generation.client.StreamlineBridge;
import org.malionaro.dlss_frame_generation.client.access.VulkanDeviceAccess;
import org.malionaro.dlss_frame_generation.client.raytracing.RayReconstructionResources;
import org.malionaro.dlss_frame_generation.client.render.VulkanRegistry;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Renderer-owned frame-generation input pipeline.
 *
 * <p>It snapshots color and depth before Minecraft clears depth and draws the
 * GUI, then computes dense render-resolution RG16F motion vectors from camera
 * reprojection plus a local temporal color/depth search for object motion.</p>
 */
public final class VulkanFrameGenerationPipeline implements AutoCloseable {
    private static final int SLOT_COUNT = 3;
    private static final int MOTION_FORMAT = VK_FORMAT_R16G16_SFLOAT;
    private static final int RAY_RECONSTRUCTION_FORMAT =
            VK_FORMAT_R16G16B16A16_SFLOAT;
    /** Camera block (96), two camera-relative matrices (128), then the entity block. */
    private static final int UNIFORM_SIZE = 224 + ObjectMotion.BYTES;
    private static final int MOTION_BYTES_PER_PIXEL = 4;
    private static final int DEBUG_SAMPLE_STEP = 8;

    private final Logger logger;
    private final VkDevice device;
    private final VkPhysicalDevice physicalDevice;

    private final FrameSlot[] slots = new FrameSlot[SLOT_COUNT];
    private long shaderModule;
    private long sampler;
    private long descriptorSetLayout;
    private long pipelineLayout;
    private long pipeline;
    private long descriptorPool;
    private final long[] descriptorSets = new long[SLOT_COUNT];
    private long tonemapShaderModule;
    private long tonemapDescriptorSetLayout;
    private long tonemapPipelineLayout;
    private long tonemapPipeline;
    private long tonemapDescriptorPool;
    private final long[] tonemapDescriptorSets = new long[SLOT_COUNT];
    private long motionBlurShaderModule;
    private long motionBlurDescriptorSetLayout;
    private long motionBlurPipelineLayout;
    private long motionBlurPipeline;
    private long motionBlurDescriptorPool;
    private final long[] motionBlurDescriptorSets = new long[SLOT_COUNT];
    private int width;
    private int height;
    private int outputWidth;
    private int outputHeight;
    private int colorFormat;
    private int depthFormat;
    private int outputFormat;
    private long frameNumber;
    private boolean historyValid;
    private boolean failed;
    // OOM (retry) vs validation (latch) distinction.
    // Hard validation failures remain latched forever; OOM is retryable after swapchain generation change.
    private boolean failedDueToOutOfMemory;
    private long failedAtSwapchainGeneration = -1L;
    private String status = "Frame-generation resources are not initialized";
    private MotionVectorSnapshot latestDebugSnapshot;
    private long completedPresentSerial;
    private long unconfirmedPresentCount;
    private long capturedFrameCount;
    private long providerAcceptedFrameCount;
    /** Whether the last capture wrote a DLSS/FSR (or DLSS-RR) result to the output. */
    private boolean lastUpscalingAccepted;
    /**
     * Whether the last capture left a current image in the output target.
     * False whenever it bailed out early (slot still in flight after a
     * swapchain rebuild, resource rebuild, failure); the caller then has to
     * blit the world itself or the screen keeps showing an old frame.
     */
    private boolean lastOutputWritten;
    private int pendingUiSlot = -1;
    private FrameIdentity pendingUiIdentity = FrameIdentity.INVALID;
    private long activeSwapchainGeneration;
    private FrameIdentity lastCapturedIdentity = FrameIdentity.INVALID;
    private FrameIdentity lastProviderAcceptedIdentity = FrameIdentity.INVALID;
    /** Render images replaced by a resize, freed once their frames completed. */
    private final java.util.ArrayDeque<Retired> retiredResources = new java.util.ArrayDeque<>();
    /** Set by a resize; the new render images get their initial layout next frame. */
    private boolean renderImagesNeedInit;
    /**
     * Set by a resize until the next captured frame: the previous slot's
     * colour and depth have the old size, so that frame uses camera
     * reprojection only. Temporal history stays valid on purpose, because
     * DLSS and FSR handle dynamic resolution themselves and a reset on every
     * DRS step made the picture visibly pulse.
     */
    private boolean renderSizeChanged;
    /**
     * Set per frame by the registry while a menu covers the world. The
     * background is blurred and flat, so the colour search only finds noise
     * that makes the picture swim; camera reprojection alone is exact there.
     */
    private boolean cameraOnlyMotion;

    public void setCameraOnlyMotion(boolean cameraOnly) {
        cameraOnlyMotion = cameraOnly;
    }

    public VulkanFrameGenerationPipeline(VulkanDevice minecraftDevice, Logger logger) {
        this.logger = logger;
        this.device = minecraftDevice.vkDevice();
        if (!(minecraftDevice instanceof VulkanDeviceAccess access)
                || access.dlss_fg$getPhysicalDevice() == null) {
            throw new IllegalArgumentException("Vulkan physical device is unavailable");
        }
        this.physicalDevice = access.dlss_fg$getPhysicalDevice().vkPhysicalDevice();
    }

    public MotionVectorResource captureAndDispatch(
            FrameResourceSet frameResources,
            VkCommandBuffer commandBuffer,
            VulkanGpuTextureView sourceColor,
            VulkanGpuTextureView sourceDepth,
            VulkanGpuTextureView outputColor,
            RayReconstructionResources rayReconstructionResources,
            int reconstructionMode,
            int vendorId,
            float[] viewToClip,
            float[] clipToView,
            float[] clipToPreviousClip,
            float[] previousClipToClip,
            float[] worldToView,
            float[] viewToWorld,
            float jitterX,
            float jitterY,
            float previousJitterX,
            float previousJitterY,
            boolean reset,
            float frameTimeMs,
            float cameraNear,
            float cameraFar,
            float verticalFov,
            float aspect,
            float cameraX,
            float cameraY,
            float cameraZ,
            float cameraUpX,
            float cameraUpY,
            float cameraUpZ,
            float cameraRightX,
            float cameraRightY,
            float cameraRightZ,
            float cameraForwardX,
            float cameraForwardY,
            float cameraForwardZ,
            boolean enabled,
            int multiplier,
            boolean uiRecomposition,
            boolean uiWillBeProvided,
            boolean debugReadbackRequested,
            boolean motionBlurEnabled,
            float motionBlurIntensity,
            int motionBlurSamples
    ) {
        lastUpscalingAccepted = false;
        lastOutputWritten = false;
        // Lazy OOM-retry – if previous failure was OOM and generation has changed, clear latch
        if (failed && failedDueToOutOfMemory && failedAtSwapchainGeneration != activeSwapchainGeneration) {
            logger.info("OOM recovery: clearing retryable latch (failed at gen {} -> now gen {})",
                    failedAtSwapchainGeneration, activeSwapchainGeneration);
            failed = false;
            failedDueToOutOfMemory = false;
            failedAtSwapchainGeneration = -1L;
            status = "Retrying frame-generation after OOM recovery (generation " + activeSwapchainGeneration + ")";
        }
        if (failed || frameResources == null
                || commandBuffer == null || sourceColor == null || sourceDepth == null
                || outputColor == null || sourceColor.texture() == null
                || sourceDepth.texture() == null || outputColor.texture() == null) {
            // Distinguish waiting for retry: keep informative status for OOM case
            if (failed && failedDueToOutOfMemory) {
                status = "Frame skipped: OOM failure pending retry after swapchain rebuild (failed at gen "
                        + failedAtSwapchainGeneration + ")";
            }
            return null;
        }

        FrameResourceSet.Validation validation = frameResources.validate();
        if (!validation.accepted()) {
            status = "Frame skipped: " + validation.reason();
            return null;
        }
        if (frameResources.commandBuffer() != commandBuffer.address()
                || frameResources.color().image()
                        != sourceColor.texture().vkImage()
                || frameResources.depth().image()
                        != sourceDepth.texture().vkImage()
                || frameResources.output().image()
                        != outputColor.texture().vkImage()) {
            status = "Frame skipped: renderer handles changed during capture";
            return null;
        }
        FrameIdentity identity = frameResources.identity();
        if (lastCapturedIdentity.isValid()
                && identity.swapchainGeneration()
                        == lastCapturedIdentity.swapchainGeneration()
                && identity.frameSerial()
                        <= lastCapturedIdentity.frameSerial()) {
            status = "Frame skipped: duplicate or out-of-order frame identity";
            return null;
        }
        if (activeSwapchainGeneration != identity.swapchainGeneration()) {
            onSwapchainGenerationChanged(identity.swapchainGeneration());
        }

        int renderWidth = frameResources.color().width();
        int renderHeight = frameResources.color().height();
        int sourceColorFormat = frameResources.color().format();
        int sourceDepthFormat = frameResources.depth().format();
        int displayWidth = frameResources.output().width();
        int displayHeight = frameResources.output().height();
        int destinationFormat = frameResources.output().format();
        if (renderWidth <= 0 || renderHeight <= 0
                || displayWidth <= 0 || displayHeight <= 0) return null;

        try {
            ensureResources(
                    renderWidth,
                    renderHeight,
                    displayWidth,
                    displayHeight,
                    sourceColorFormat,
                    sourceDepthFormat,
                    destinationFormat
            );
            // Resized render images first: they also satisfy the layout check
            // in initializeHistoryImages, which must only run after a full
            // rebuild because it re-clears the output-sized images too.
            initializeResizedRenderImages(commandBuffer);
            initializeHistoryImages(commandBuffer);
            int currentIndex = (int) (frameNumber % SLOT_COUNT);
            int previousIndex = (currentIndex + SLOT_COUNT - 1) % SLOT_COUNT;
            FrameSlot current = slots[currentIndex];
            FrameSlot previous = slots[previousIndex];
            if (current.inUseUntilPresentSerial > completedPresentSerial) {
                status = "Waiting for present before reusing frame-generation resources";
                return null;
            }
            if (debugReadbackRequested) {
                readLatestDebugSnapshot();
            }
            current.debugReadbackPending = false;

            copySnapshots(
                    commandBuffer,
                    frameResources.color(),
                    frameResources.depth(),
                    current
            );
            uploadFrameParameters(
                    current.uniformMemory,
                    clipToPreviousClip,
                    jitterX,
                    jitterY,
                    previousJitterX,
                    previousJitterY,
                    renderSizeChanged || cameraOnlyMotion,
                    // Fast camera turns invalidate temporal matching — reset
                    // history while the artifact corrector reports dampening
                    // instead of smearing stale vectors across providers.
                    reset || !historyValid
                            || OpticalFlowArtifactCorrector.dampeningFactor() < 0.99f
            );
            dispatchMotionVectors(commandBuffer, currentIndex, current);

            boolean rayReconstructionAccepted = false;
            if (vendorId == FrameGenerationBackend.GpuVendor.NVIDIA.vendorId()
                    && reconstructionMode >= 2
                    && rayReconstructionResources != null
                    && rayReconstructionResources.valid()) {
                RayReconstructionResources.ImageResource noisy =
                        rayReconstructionResources.noisyColor();
                RayReconstructionResources.ImageResource albedo =
                        rayReconstructionResources.diffuseAlbedo();
                RayReconstructionResources.ImageResource specular =
                        rayReconstructionResources.specularAlbedo();
                RayReconstructionResources.ImageResource normalRoughness =
                        rayReconstructionResources.normalRoughness();
                RayReconstructionResources.ImageResource specularHitDistance =
                        rayReconstructionResources.specularHitDistance();
                rayReconstructionAccepted =
                        StreamlineBridge.nSubmitRayReconstructionInputs(
                                commandBuffer.address(),
                                noisy.image(), noisy.memory(), noisy.view(),
                                noisy.format(),
                                albedo.image(), albedo.memory(), albedo.view(),
                                albedo.format(),
                                specular.image(), specular.memory(),
                                specular.view(), specular.format(),
                                normalRoughness.image(),
                                normalRoughness.memory(),
                                normalRoughness.view(),
                                normalRoughness.format(),
                                specularHitDistance.image(),
                                specularHitDistance.memory(),
                                specularHitDistance.view(),
                                specularHitDistance.format(),
                                current.depth.image, current.depth.memory,
                                current.depth.view, depthFormat,
                                current.motion.image, current.motion.memory,
                                current.motion.view, MOTION_FORMAT,
                                current.rayReconstructionOutput.image,
                                current.rayReconstructionOutput.memory,
                                current.rayReconstructionOutput.view,
                                RAY_RECONSTRUCTION_FORMAT,
                                width, height,
                                outputWidth, outputHeight,
                                reconstructionMode,
                                viewToClip,
                                clipToView,
                                clipToPreviousClip,
                                previousClipToClip,
                                worldToView,
                                viewToWorld,
                                frameTimeMs,
                                cameraNear, cameraFar, verticalFov, aspect,
                                jitterX, jitterY,
                                cameraX, cameraY, cameraZ,
                                cameraUpX, cameraUpY, cameraUpZ,
                                cameraRightX, cameraRightY, cameraRightZ,
                                cameraForwardX, cameraForwardY,
                                cameraForwardZ,
                                reset || !historyValid
                        );
                if (rayReconstructionAccepted) {
                    dispatchRayReconstructionTonemap(
                            commandBuffer, currentIndex, current);
                }
            }

            // Mode 0: the world arrives at window size with nothing to
            // reconstruct (DLSS off, or a shader pack without a scale hook).
            // Running DLSS there anyway stacked its temporal accumulation on
            // the pack's own TAA and made the picture swim.
            boolean upscalingAccepted = rayReconstructionAccepted
                    || reconstructionMode != 0 && StreamlineBridge.nSubmitUpscalingInputs(
                            vendorId,
                            commandBuffer.address(),
                            current.color.image, current.color.memory,
                            current.color.view, colorFormat,
                            current.depth.image, current.depth.memory,
                            current.depth.view, depthFormat,
                            current.motion.image, current.motion.memory,
                            current.motion.view, MOTION_FORMAT,
                            current.upscaled.image, current.upscaled.memory,
                            current.upscaled.view, outputFormat,
                            width, height,
                            outputWidth, outputHeight,
                            viewToClip,
                            clipToView,
                            clipToPreviousClip,
                            previousClipToClip,
                            frameTimeMs,
                            cameraNear, cameraFar, verticalFov, aspect,
                            jitterX, jitterY,
                            cameraX, cameraY, cameraZ,
                            cameraUpX, cameraUpY, cameraUpZ,
                            cameraRightX, cameraRightY, cameraRightZ,
                            cameraForwardX, cameraForwardY, cameraForwardZ,
                            reset || !historyValid
                    );
            lastUpscalingAccepted = upscalingAccepted;
            boolean fullResolutionFallback = !upscalingAccepted;
            if (fullResolutionFallback) {
                blitSnapshotToOwnedOutput(
                        commandBuffer,
                        current.color,
                        current.upscaled
                );
            }
            boolean motionBlurAccepted = motionBlurEnabled
                    && outputFormat == VK_FORMAT_R8G8B8A8_UNORM;
            NativeImage finalColor = current.upscaled;
            if (motionBlurAccepted) {
                dispatchMotionBlur(
                        commandBuffer,
                        currentIndex,
                        current,
                        motionBlurIntensity,
                        motionBlurSamples
                );
                finalColor = current.motionBlurOutput;
            }
            if (upscalingAccepted || motionBlurAccepted
                    || frameResources.color().image()
                            != frameResources.output().image()) {
                copyUpscaledToSource(
                        commandBuffer,
                        finalColor,
                        frameResources.output()
                );
            }
            // Either copied above or rendered in place (same image).
            lastOutputWritten = true;

            NativeImage hudlessColor = finalColor;
            int hudlessWidth = outputWidth;
            int hudlessHeight = outputHeight;
            boolean accepted = StreamlineBridge.nSubmitFrameGenerationInputs(
                    vendorId,
                    commandBuffer.address(),
                    frameResources.backbuffer().image(),
                    frameResources.backbuffer().format(),
                    hudlessColor.image, hudlessColor.memory, hudlessColor.view,
                    outputFormat,
                    current.depth.image, current.depth.memory, current.depth.view, depthFormat,
                    current.motion.image, current.motion.memory, current.motion.view, MOTION_FORMAT,
                    hudlessWidth, hudlessHeight,
                    width, height,
                    viewToClip,
                    clipToView,
                    clipToPreviousClip,
                    previousClipToClip,
                    frameTimeMs,
                    cameraNear, cameraFar, verticalFov, aspect,
                    jitterX, jitterY,
                    cameraX, cameraY, cameraZ,
                    cameraUpX, cameraUpY, cameraUpZ,
                    cameraRightX, cameraRightY, cameraRightZ,
                    cameraForwardX, cameraForwardY, cameraForwardZ,
                    reset || !historyValid,
                    enabled, multiplier,
                    uiRecomposition && uiWillBeProvided
            );
            pendingUiSlot = uiRecomposition && uiWillBeProvided
                    ? currentIndex
                    : -1;
            pendingUiIdentity = pendingUiSlot >= 0
                    ? identity
                    : FrameIdentity.INVALID;
            if (debugReadbackRequested) {
                recordDebugReadback(commandBuffer, current);
            }

            frameNumber++;
            capturedFrameCount++;
            lastCapturedIdentity = identity;
            if (accepted) {
                providerAcceptedFrameCount++;
                lastProviderAcceptedIdentity = identity;
            }
            // Hold the slot for two confirmed presents. The completion gate
            // confirms GPU progress through the frame submitted two presents ago,
            // so margin 2 means a slot is recycled only after the GPU verifiably
            // finished the frame that filled it (with 3 rotating slots this still
            // leaves one spare slot per frame in steady state).
            current.inUseUntilPresentSerial = completedPresentSerial + 2;
            historyValid = true;
            renderSizeChanged = false;
            status = accepted
                    ? "Dense GPU motion vectors accepted by native provider"
                            + (rayReconstructionAccepted
                                    ? "; DLSS Ray Reconstruction output applied"
                                    : upscalingAccepted
                                             ? "; temporal reconstruction applied"
                                             : motionBlurAccepted
                                                     ? "; motion blur applied"
                                             : "")
                    : "Dense GPU inputs ready; native provider did not accept this frame";
            return new MotionVectorResource(
                    MotionVectorResource.ResourceType.VK_IMAGE,
                    current.motion.image,
                    width,
                    height,
                    MotionVectorResource.MotionSpace.SCREEN_SPACE_PIXELS,
                    false,
                    true,
                    true
            );
        } catch (Throwable throwable) {
            boolean isOom = isOutOfMemoryFailure(throwable);
            if (isOom) {
                failed = true;
                failedDueToOutOfMemory = true;
                failedAtSwapchainGeneration = activeSwapchainGeneration;
                status = "Frame-generation OOM (retryable after swapchain rebuild, gen "
                        + activeSwapchainGeneration + "): " + throwable.getMessage();
                logger.warn("Frame-generation OOM (retryable, gen {}), will retry after swapchain change: {}",
                        activeSwapchainGeneration, throwable.toString(), throwable);
            } else {
                failed = true;
                failedDueToOutOfMemory = false;
                failedAtSwapchainGeneration = -1L;
                status = "Frame-generation input pipeline disabled (fatal/validation, gen "
                        + activeSwapchainGeneration + "): " + throwable.getMessage();
                logger.error("Frame-generation input pipeline failed and was disabled (fatal latch, gen {})",
                        activeSwapchainGeneration, throwable);
            }
            return null;
        }
    }

    public String status() {
        return status;
    }

    public boolean captureUiAndFinalize(
            FrameIdentity identity,
            VkCommandBuffer commandBuffer,
            VulkanGpuTextureView uiColor,
            int vendorId,
            boolean enabled,
            int multiplier,
            boolean uiRecomposition
    ) {
        // Allow lazy OOM recovery for UI path as well
        if (failed && failedDueToOutOfMemory && failedAtSwapchainGeneration != activeSwapchainGeneration) {
            logger.info("OOM recovery (UI): clearing retryable latch (failed at gen {} -> now gen {})",
                    failedAtSwapchainGeneration, activeSwapchainGeneration);
            failed = false;
            failedDueToOutOfMemory = false;
            failedAtSwapchainGeneration = -1L;
        }
        if (failed || pendingUiSlot < 0 || commandBuffer == null
                || identity == null
                || !pendingUiIdentity.belongsToSameFrame(identity)
                || uiColor == null || uiColor.texture() == null) {
            if (pendingUiSlot >= 0) {
                status = "UI recomposition skipped: UI and world resources "
                        + "do not belong to the same frame";
                pendingUiSlot = -1;
                pendingUiIdentity = FrameIdentity.INVALID;
            }
            return false;
        }
        FrameSlot slot = slots[pendingUiSlot];
        if (slot == null
                || uiColor.getWidth(0) != outputWidth
                || uiColor.getHeight(0) != outputHeight) {
            pendingUiSlot = -1;
            pendingUiIdentity = FrameIdentity.INVALID;
            status = "UI recomposition skipped because UI dimensions changed";
            return false;
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            transitionExternalImage(
                    commandBuffer,
                    uiColor.texture().vkImage(),
                    VK_IMAGE_ASPECT_COLOR_BIT,
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
                    slot.ui,
                    slot.ui.layout,
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    VK_ACCESS_SHADER_READ_BIT,
                    VK_ACCESS_TRANSFER_WRITE_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK_PIPELINE_STAGE_TRANSFER_BIT,
                    stack
            );
            copyImageWithLayouts(
                    commandBuffer,
                    uiColor.texture().vkImage(),
                    VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    slot.ui.image,
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    VK_IMAGE_ASPECT_COLOR_BIT,
                    outputWidth,
                    outputHeight,
                    stack
            );
            transitionImage(
                    commandBuffer,
                    slot.ui,
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                    VK_ACCESS_TRANSFER_WRITE_BIT,
                    VK_ACCESS_SHADER_READ_BIT,
                    VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    stack
            );
            transitionExternalImage(
                    commandBuffer,
                    uiColor.texture().vkImage(),
                    VK_IMAGE_ASPECT_COLOR_BIT,
                    VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    VK_IMAGE_LAYOUT_GENERAL,
                    VK_ACCESS_TRANSFER_READ_BIT,
                    VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT
                            | VK_ACCESS_SHADER_READ_BIT,
                    VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                    stack
            );
        }

        boolean accepted = StreamlineBridge.nSubmitFrameGenerationUi(
                vendorId,
                commandBuffer.address(),
                slot.ui.image,
                slot.ui.memory,
                slot.ui.view,
                outputFormat,
                outputWidth,
                outputHeight,
                enabled,
                multiplier,
                uiRecomposition
        );
        pendingUiSlot = -1;
        pendingUiIdentity = FrameIdentity.INVALID;
        if (accepted) {
            status = "Dense GPU inputs and full-resolution UI accepted by native provider";
        } else {
            status = "Full-resolution UI captured; native provider rejected UI recomposition";
        }
        return accepted;
    }

    public MotionVectorSnapshot latestDebugSnapshot() {
        return latestDebugSnapshot;
    }

    public long capturedFrameCount() {
        return capturedFrameCount;
    }

    public long providerAcceptedFrameCount() {
        return providerAcceptedFrameCount;
    }

    public boolean lastUpscalingAccepted() {
        return lastUpscalingAccepted;
    }

    public boolean lastOutputWritten() {
        return lastOutputWritten;
    }

    public long completedPresentCount() {
        return completedPresentSerial;
    }

    public void onPresentCompleted() {
        // CompletedPresentSerial counts CONFIRMED presents only. It
        // drives slot reuse (inUseUntilPresentSerial), so it must advance only
        // once the GPU caught up with the frames that filled those slots.
        // waitUntilGpuIdleBrief performs a bounded (2ms here) wait on the
        // present-gate marker armed two presents ago; under -Ddlss_fg.strictSync
        // it issues a full vkDeviceWaitIdle instead (correct but expensive).
        // When unconfirmed, the serial is held: capture back-pressures with
        // "Waiting for present..." while vanilla rendering continues through the
        // guaranteed composite fallback (fail-closed, self-healing: the next
        // confirmed present resumes the serial, no latch, no force-advance).
        boolean confirmed = false;
        try {
            confirmed = VulkanRegistry.waitUntilGpuIdleBrief(2L);
        } catch (Throwable ignored) {
            confirmed = false;
        }
        if (confirmed) {
            completedPresentSerial++;
        } else {
            unconfirmedPresentCount++;
        }
    }

    public void resetHistory() {
        historyValid = false;
    }

    public void onSwapchainGenerationChanged(long generation) {
        if (generation <= 0L) {
            return;
        }
        activeSwapchainGeneration = generation;
        historyValid = false;
        pendingUiSlot = -1;
        pendingUiIdentity = FrameIdentity.INVALID;
        lastCapturedIdentity = FrameIdentity.INVALID;
        // OOM-retryable latch is cleared on generation change; hard latch stays.
        if (failed && failedDueToOutOfMemory && generation != failedAtSwapchainGeneration) {
            logger.info("OOM retryable failure cleared on swapchain generation change {} -> {}",
                    failedAtSwapchainGeneration, generation);
            failed = false;
            failedDueToOutOfMemory = false;
            failedAtSwapchainGeneration = -1L;
            status = "Retrying frame-generation after OOM recovery (generation " + generation + ")";
        } else {
            status = "Waiting for a frame from swapchain generation " + generation;
            // Hard latch keeps failed==true – no retry.
        }
    }

    public FrameIdentity lastCapturedIdentity() {
        return lastCapturedIdentity;
    }

    public FrameIdentity lastProviderAcceptedIdentity() {
        return lastProviderAcceptedIdentity;
    }

    // EnsureResources may throw OOM (VK_ERROR_OUT_OF_* -> IllegalStateException with VkResult -1/-2)
    // The caller captureAndDispatch distinguishes OOM-retryable (reset on generation change) vs validation fatal latch.
    private void ensureResources(
            int renderWidth,
            int renderHeight,
            int displayWidth,
            int displayHeight,
            int sourceColorFormat,
            int sourceDepthFormat,
            int destinationFormat
    ) throws IOException {
        releaseRetiredResources(false);
        boolean sameOutput = pipeline != NULL
                && outputWidth == displayWidth && outputHeight == displayHeight
                && colorFormat == sourceColorFormat
                && depthFormat == sourceDepthFormat
                && outputFormat == destinationFormat;
        if (sameOutput && width == renderWidth && height == renderHeight) {
            return;
        }
        if (sameOutput) {
            // Dynamic resolution and shader-pack render scale change only the
            // world size. Rebuilding everything here (device idle, three
            // shaderc compiles, new pipelines) stalled every DRS step.
            resizeRenderResources(renderWidth, renderHeight);
            return;
        }

        vkDeviceWaitIdle(device);
        releaseRetiredResources(true);
        destroyResources();
        try {
            width = renderWidth;
            height = renderHeight;
            outputWidth = displayWidth;
            outputHeight = displayHeight;
            colorFormat = sourceColorFormat;
            depthFormat = sourceDepthFormat;
            outputFormat = destinationFormat;

            for (int index = 0; index < SLOT_COUNT; index++) {
                FrameSlot slot = new FrameSlot();
                // Stored before allocation so destroyResources frees a
                // partially built slot if a later allocation throws.
                slots[index] = slot;
                allocateRenderImages(slot);
                slot.upscaled = createImage(
                        outputFormat,
                        VK_IMAGE_ASPECT_COLOR_BIT,
                        VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT
                                | VK_IMAGE_USAGE_TRANSFER_DST_BIT
                                | VK_IMAGE_USAGE_SAMPLED_BIT,
                        outputWidth,
                        outputHeight
                );
                slot.ui = createImage(
                        outputFormat,
                        VK_IMAGE_ASPECT_COLOR_BIT,
                        VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT,
                        outputWidth,
                        outputHeight
                );
                slot.rayReconstructionOutput = createImage(
                        RAY_RECONSTRUCTION_FORMAT,
                        VK_IMAGE_ASPECT_COLOR_BIT,
                        VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT,
                        outputWidth,
                        outputHeight
                );
                slot.motionBlurOutput = createImage(
                        VK_FORMAT_R8G8B8A8_UNORM,
                        VK_IMAGE_ASPECT_COLOR_BIT,
                        VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT
                                | VK_IMAGE_USAGE_TRANSFER_DST_BIT
                                | VK_IMAGE_USAGE_SAMPLED_BIT,
                        outputWidth,
                        outputHeight
                );
                Allocation uniform = createBuffer(
                        UNIFORM_SIZE,
                        VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT,
                        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT
                );
                slot.uniformBuffer = uniform.buffer;
                slot.uniformMemory = uniform.memory;
            }

            createPipeline();
            createDescriptors();
            createTonemapPipeline();
            createTonemapDescriptors();
            createMotionBlurPipeline();
            createMotionBlurDescriptors();
        } catch (Throwable error) {
            destroyResources();
            width = 0;
            height = 0;
            outputWidth = 0;
            outputHeight = 0;
            colorFormat = 0;
            depthFormat = 0;
            outputFormat = 0;
            historyValid = false;
            frameNumber = 0;
            completedPresentSerial = 0;
            unconfirmedPresentCount = 0;
            capturedFrameCount = 0;
            providerAcceptedFrameCount = 0;
            pendingUiSlot = -1;
            pendingUiIdentity = FrameIdentity.INVALID;
            latestDebugSnapshot = null;
            status = "Frame-generation resource allocation failed: " + error.getMessage();
            throw error;
        }
        historyValid = false;
        renderImagesNeedInit = false;
        frameNumber = 0;
        completedPresentSerial = 0;
        unconfirmedPresentCount = 0;
        capturedFrameCount = 0;
        providerAcceptedFrameCount = 0;
        pendingUiSlot = -1;
        pendingUiIdentity = FrameIdentity.INVALID;
        latestDebugSnapshot = null;
        status = "Dense GPU input resources allocated";
        logger.info(
                "Allocated frame-generation input pipeline: render={}x{}, output={}x{}, color={}, depth={}, motion=RG16F",
                width, height, outputWidth, outputHeight, colorFormat, depthFormat
        );
    }

    /** Render-resolution images of one slot (sized by {@link #width}/{@link #height}). */
    private void allocateRenderImages(FrameSlot slot) {
        slot.color = createImage(
                colorFormat,
                VK_IMAGE_ASPECT_COLOR_BIT,
                VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT
                        | VK_IMAGE_USAGE_TRANSFER_SRC_BIT,
                width,
                height
        );
        slot.depth = createImage(
                depthFormat,
                VK_IMAGE_ASPECT_DEPTH_BIT,
                VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT,
                width,
                height
        );
        slot.motion = createImage(
                MOTION_FORMAT,
                VK_IMAGE_ASPECT_COLOR_BIT,
                VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT
                        | VK_IMAGE_USAGE_TRANSFER_DST_BIT
                        | VK_IMAGE_USAGE_TRANSFER_SRC_BIT,
                width,
                height
        );
        Allocation debugReadback = createBuffer(
                Math.multiplyExact(
                        Math.multiplyExact((long) width, height),
                        MOTION_BYTES_PER_PIXEL
                ),
                VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT
                        | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT
        );
        slot.debugReadbackBuffer = debugReadback.buffer;
        slot.debugReadbackMemory = debugReadback.memory;
        slot.debugReadbackPending = false;
    }

    /**
     * Swaps only the render-resolution images when the world size changes but
     * the output size and formats stay. Pipelines, shaders, samplers, uniform
     * buffers and the output-sized images are kept; nothing waits for the
     * device. The replaced images and the descriptor pools that reference them
     * may still be used by frames in flight, so they are retired and freed
     * once the confirmed present serial passes every slot that could hold them.
     */
    private void resizeRenderResources(int renderWidth, int renderHeight) {
        long releaseAfter = completedPresentSerial + SLOT_COUNT;
        Retired retired = new Retired();
        for (FrameSlot slot : slots) {
            releaseAfter = Math.max(releaseAfter, slot.inUseUntilPresentSerial + 1);
            retired.images.add(slot.color);
            retired.images.add(slot.depth);
            retired.images.add(slot.motion);
            retired.buffers.add(new Allocation(
                    slot.debugReadbackBuffer, slot.debugReadbackMemory));
            slot.color = slot.depth = slot.motion = null;
            slot.debugReadbackBuffer = slot.debugReadbackMemory = NULL;
        }
        retired.descriptorPools.add(descriptorPool);
        retired.descriptorPools.add(motionBlurDescriptorPool);
        descriptorPool = motionBlurDescriptorPool = NULL;
        retired.releaseAfterPresentSerial = releaseAfter;
        retiredResources.add(retired);

        int previousWidth = width;
        int previousHeight = height;
        width = renderWidth;
        height = renderHeight;
        try {
            for (FrameSlot slot : slots) {
                allocateRenderImages(slot);
            }
            createDescriptors();
            createMotionBlurDescriptors();
        } catch (RuntimeException error) {
            // Rare (allocation failure): fall back to a clean full rebuild on
            // the next frame instead of leaving half-built slots behind.
            vkDeviceWaitIdle(device);
            releaseRetiredResources(true);
            destroyResources();
            width = height = outputWidth = outputHeight = 0;
            colorFormat = depthFormat = outputFormat = 0;
            historyValid = false;
            status = "Frame-generation resize failed: " + error.getMessage();
            throw error;
        }
        // The previous slot's colour/depth now has a different size: the next
        // frame skips the image search (camera reprojection only). History
        // stays valid so DLSS/FSR keep accumulating across the DRS step.
        renderSizeChanged = true;
        renderImagesNeedInit = true;
        latestDebugSnapshot = null;
        logger.debug(
                "Resized frame-generation render images {}x{} -> {}x{} without a device wait",
                previousWidth, previousHeight, width, height
        );
    }

    /**
     * Frees retired render images once the GPU has confirmed the presents that
     * could still use them. {@code force} is only valid right after a device
     * idle (full rebuild, close).
     */
    private void releaseRetiredResources(boolean force) {
        java.util.Iterator<Retired> iterator = retiredResources.iterator();
        while (iterator.hasNext()) {
            Retired retired = iterator.next();
            if (!force && retired.releaseAfterPresentSerial > completedPresentSerial) {
                continue;
            }
            for (NativeImage image : retired.images) {
                destroyImage(image);
            }
            for (Allocation buffer : retired.buffers) {
                if (buffer.buffer() != NULL) vkDestroyBuffer(device, buffer.buffer(), null);
                if (buffer.memory() != NULL) vkFreeMemory(device, buffer.memory(), null);
            }
            for (long pool : retired.descriptorPools) {
                if (pool != NULL) vkDestroyDescriptorPool(device, pool, null);
            }
            iterator.remove();
        }
    }

    /**
     * Initial layout and clear for render images created by a resize. Unlike
     * {@link #initializeHistoryImages}, it never touches the output-sized
     * images, which in-flight frames and the present-time providers may still
     * read.
     */
    private void initializeResizedRenderImages(VkCommandBuffer commandBuffer) {
        if (!renderImagesNeedInit) return;
        renderImagesNeedInit = false;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkClearColorValue colorClear = VkClearColorValue.calloc(stack)
                    .float32(stack.floats(0.0f, 0.0f, 0.0f, 0.0f));
            VkClearDepthStencilValue depthClear = VkClearDepthStencilValue.calloc(stack)
                    .depth(0.0f)
                    .stencil(0);
            VkImageSubresourceRange colorRange = VkImageSubresourceRange.calloc(stack)
                    .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(0).levelCount(1)
                    .baseArrayLayer(0).layerCount(1);
            VkImageSubresourceRange depthRange = VkImageSubresourceRange.calloc(stack)
                    .aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT)
                    .baseMipLevel(0).levelCount(1)
                    .baseArrayLayer(0).layerCount(1);
            for (FrameSlot slot : slots) {
                for (NativeImage image : new NativeImage[] {slot.color, slot.depth, slot.motion}) {
                    transitionImage(
                            commandBuffer, image,
                            VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                            0, VK_ACCESS_TRANSFER_WRITE_BIT,
                            VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                            stack);
                }
                vkCmdClearColorImage(
                        commandBuffer, slot.color.image,
                        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, colorClear, colorRange);
                vkCmdClearDepthStencilImage(
                        commandBuffer, slot.depth.image,
                        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, depthClear, depthRange);
                vkCmdClearColorImage(
                        commandBuffer, slot.motion.image,
                        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, colorClear, colorRange);
                for (NativeImage image : new NativeImage[] {slot.color, slot.depth, slot.motion}) {
                    transitionImage(
                            commandBuffer, image,
                            VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                            VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                            VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT,
                            VK_PIPELINE_STAGE_TRANSFER_BIT,
                            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, stack);
                }
            }
        }
    }

    private void createPipeline() throws IOException {
        ByteBuffer spirv = MotionVectorShaderCompiler.compile(
                "/assets/dlss_fg/shaders/source/motion/frame_generation_motion.comp");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer result = stack.mallocLong(1);
            VkShaderModuleCreateInfo shaderInfo = VkShaderModuleCreateInfo.calloc(stack)
                    .sType$Default()
                    .pCode(spirv);
            check(vkCreateShaderModule(device, shaderInfo, null, result),
                    "create motion-vector shader module");
            shaderModule = result.get(0);

            VkDescriptorSetLayoutBinding.Buffer bindings =
                    VkDescriptorSetLayoutBinding.calloc(6, stack);
            for (int binding = 0; binding < 4; binding++) {
                bindings.get(binding)
                        .binding(binding)
                        .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                        .descriptorCount(1)
                        .stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            }
            bindings.get(4)
                    .binding(4)
                    .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(1)
                    .stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            bindings.get(5)
                    .binding(5)
                    .descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                    .descriptorCount(1)
                    .stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);

            VkDescriptorSetLayoutCreateInfo layoutInfo =
                    VkDescriptorSetLayoutCreateInfo.calloc(stack)
                            .sType$Default()
                            .pBindings(bindings);
            check(vkCreateDescriptorSetLayout(device, layoutInfo, null, result),
                    "create motion-vector descriptor layout");
            descriptorSetLayout = result.get(0);

            VkPipelineLayoutCreateInfo pipelineLayoutInfo =
                    VkPipelineLayoutCreateInfo.calloc(stack)
                            .sType$Default()
                            .pSetLayouts(stack.longs(descriptorSetLayout));
            check(vkCreatePipelineLayout(device, pipelineLayoutInfo, null, result),
                    "create motion-vector pipeline layout");
            pipelineLayout = result.get(0);

            VkPipelineShaderStageCreateInfo stage =
                    VkPipelineShaderStageCreateInfo.calloc(stack)
                            .sType$Default()
                            .stage(VK_SHADER_STAGE_COMPUTE_BIT)
                            .module(shaderModule)
                            .pName(stack.UTF8("main"));
            VkComputePipelineCreateInfo.Buffer pipelineInfo =
                    VkComputePipelineCreateInfo.calloc(1, stack);
            pipelineInfo.get(0).sType$Default().stage(stage).layout(pipelineLayout);
            check(vkCreateComputePipelines(device, NULL, pipelineInfo, null, result),
                    "create motion-vector compute pipeline");
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
                    "create motion-vector sampler");
            sampler = result.get(0);
        } finally {
            MemoryUtil.memFree(spirv);
        }
    }

    private void createDescriptors() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(3, stack);
            poolSizes.get(0)
                    .type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(4 * SLOT_COUNT);
            poolSizes.get(1)
                    .type(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(SLOT_COUNT);
            poolSizes.get(2)
                    .type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                    .descriptorCount(SLOT_COUNT);
            VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack)
                    .sType$Default()
                    .maxSets(SLOT_COUNT)
                    .pPoolSizes(poolSizes);
            LongBuffer result = stack.mallocLong(SLOT_COUNT);
            check(vkCreateDescriptorPool(device, poolInfo, null, result),
                    "create motion-vector descriptor pool");
            descriptorPool = result.get(0);

            VkDescriptorSetAllocateInfo allocateInfo =
                    VkDescriptorSetAllocateInfo.calloc(stack)
                            .sType$Default()
                            .descriptorPool(descriptorPool);
            LongBuffer descriptorLayouts = stack.mallocLong(SLOT_COUNT);
            for (int index = 0; index < SLOT_COUNT; index++) {
                descriptorLayouts.put(index, descriptorSetLayout);
            }
            allocateInfo.pSetLayouts(descriptorLayouts);
            check(vkAllocateDescriptorSets(device, allocateInfo, result),
                    "allocate motion-vector descriptor sets");
            for (int index = 0; index < SLOT_COUNT; index++) {
                descriptorSets[index] = result.get(index);
            }

            for (int currentIndex = 0; currentIndex < SLOT_COUNT; currentIndex++) {
                FrameSlot current = slots[currentIndex];
                FrameSlot previous =
                        slots[(currentIndex + SLOT_COUNT - 1) % SLOT_COUNT];
                VkDescriptorImageInfo.Buffer sampled = VkDescriptorImageInfo.calloc(4, stack);
                sampled.get(0).sampler(sampler).imageView(current.depth.view)
                        .imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                sampled.get(1).sampler(sampler).imageView(current.color.view)
                        .imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                sampled.get(2).sampler(sampler).imageView(previous.depth.view)
                        .imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                sampled.get(3).sampler(sampler).imageView(previous.color.view)
                        .imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                VkDescriptorImageInfo.Buffer storage = VkDescriptorImageInfo.calloc(1, stack);
                storage.get(0).imageView(current.motion.view)
                        .imageLayout(VK_IMAGE_LAYOUT_GENERAL);
                VkDescriptorBufferInfo.Buffer uniform = VkDescriptorBufferInfo.calloc(1, stack);
                uniform.get(0).buffer(current.uniformBuffer).offset(0).range(UNIFORM_SIZE);

                VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(6, stack);
                for (int binding = 0; binding < 4; binding++) {
                    writes.get(binding)
                            .sType$Default()
                            .dstSet(descriptorSets[currentIndex])
                            .dstBinding(binding)
                            .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                            .pImageInfo(sampled.position(binding).limit(binding + 1));
                }
                sampled.position(0).limit(4);
                writes.get(4)
                        .sType$Default()
                        .dstSet(descriptorSets[currentIndex])
                        .dstBinding(4)
                        .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                        .pImageInfo(storage);
                writes.get(5)
                        .sType$Default()
                        .dstSet(descriptorSets[currentIndex])
                        .dstBinding(5)
                        .descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                        .pBufferInfo(uniform);
                vkUpdateDescriptorSets(device, writes, null);
            }
        }
    }

    private void createTonemapPipeline() throws IOException {
        ByteBuffer spirv = MotionVectorShaderCompiler.compile(
                "/assets/dlss_fg/shaders/source/raytracing/ray_reconstruction_tonemap.comp");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer result = stack.mallocLong(1);
            VkShaderModuleCreateInfo shaderInfo =
                    VkShaderModuleCreateInfo.calloc(stack)
                            .sType$Default()
                            .pCode(spirv);
            check(vkCreateShaderModule(
                    device, shaderInfo, null, result),
                    "create RR tone-map shader module");
            tonemapShaderModule = result.get(0);

            VkDescriptorSetLayoutBinding.Buffer bindings =
                    VkDescriptorSetLayoutBinding.calloc(2, stack);
            for (int binding = 0; binding < 2; binding++) {
                bindings.get(binding)
                        .binding(binding)
                        .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                        .descriptorCount(1)
                        .stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            }
            VkDescriptorSetLayoutCreateInfo layoutInfo =
                    VkDescriptorSetLayoutCreateInfo.calloc(stack)
                            .sType$Default()
                            .pBindings(bindings);
            check(vkCreateDescriptorSetLayout(
                    device, layoutInfo, null, result),
                    "create RR tone-map descriptor layout");
            tonemapDescriptorSetLayout = result.get(0);

            VkPipelineLayoutCreateInfo pipelineLayoutInfo =
                    VkPipelineLayoutCreateInfo.calloc(stack)
                            .sType$Default()
                            .pSetLayouts(stack.longs(
                                    tonemapDescriptorSetLayout));
            check(vkCreatePipelineLayout(
                    device, pipelineLayoutInfo, null, result),
                    "create RR tone-map pipeline layout");
            tonemapPipelineLayout = result.get(0);

            VkPipelineShaderStageCreateInfo stage =
                    VkPipelineShaderStageCreateInfo.calloc(stack)
                            .sType$Default()
                            .stage(VK_SHADER_STAGE_COMPUTE_BIT)
                            .module(tonemapShaderModule)
                            .pName(stack.UTF8("main"));
            VkComputePipelineCreateInfo.Buffer pipelineInfo =
                    VkComputePipelineCreateInfo.calloc(1, stack);
            pipelineInfo.get(0)
                    .sType$Default()
                    .stage(stage)
                    .layout(tonemapPipelineLayout);
            check(vkCreateComputePipelines(
                    device, NULL, pipelineInfo, null, result),
                    "create RR tone-map compute pipeline");
            tonemapPipeline = result.get(0);
        } finally {
            MemoryUtil.memFree(spirv);
        }
    }

    private void createTonemapDescriptors() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer result = stack.mallocLong(SLOT_COUNT);
            VkDescriptorPoolSize.Buffer poolSize =
                    VkDescriptorPoolSize.calloc(1, stack);
            poolSize.get(0)
                    .type(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(SLOT_COUNT * 2);
            VkDescriptorPoolCreateInfo poolInfo =
                    VkDescriptorPoolCreateInfo.calloc(stack)
                            .sType$Default()
                            .maxSets(SLOT_COUNT)
                            .pPoolSizes(poolSize);
            check(vkCreateDescriptorPool(
                    device, poolInfo, null, result),
                    "create RR tone-map descriptor pool");
            tonemapDescriptorPool = result.get(0);

            LongBuffer layouts = stack.mallocLong(SLOT_COUNT);
            for (int index = 0; index < SLOT_COUNT; index++) {
                layouts.put(index, tonemapDescriptorSetLayout);
            }
            VkDescriptorSetAllocateInfo allocateInfo =
                    VkDescriptorSetAllocateInfo.calloc(stack)
                            .sType$Default()
                            .descriptorPool(tonemapDescriptorPool)
                            .pSetLayouts(layouts);
            check(vkAllocateDescriptorSets(
                    device, allocateInfo, result),
                    "allocate RR tone-map descriptor sets");

            for (int index = 0; index < SLOT_COUNT; index++) {
                tonemapDescriptorSets[index] = result.get(index);
                FrameSlot slot = slots[index];
                VkDescriptorImageInfo.Buffer imageInfo =
                        VkDescriptorImageInfo.calloc(2, stack);
                imageInfo.get(0)
                        .imageView(slot.rayReconstructionOutput.view)
                        .imageLayout(VK_IMAGE_LAYOUT_GENERAL);
                imageInfo.get(1)
                        .imageView(slot.upscaled.view)
                        .imageLayout(VK_IMAGE_LAYOUT_GENERAL);
                VkWriteDescriptorSet.Buffer writes =
                        VkWriteDescriptorSet.calloc(2, stack);
                for (int binding = 0; binding < 2; binding++) {
                    writes.get(binding)
                            .sType$Default()
                            .dstSet(tonemapDescriptorSets[index])
                            .dstBinding(binding)
                            .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                            .pImageInfo(imageInfo
                                    .position(binding)
                                    .limit(binding + 1));
                }
                imageInfo.position(0).limit(2);
                vkUpdateDescriptorSets(device, writes, null);
            }
        }
    }

    private void createMotionBlurPipeline() throws IOException {
        ByteBuffer spirv = MotionVectorShaderCompiler.compile(
                "/assets/dlss_fg/shaders/source/motion/motion_blur.comp");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer result = stack.mallocLong(1);
            VkShaderModuleCreateInfo shaderInfo =
                    VkShaderModuleCreateInfo.calloc(stack)
                            .sType$Default()
                            .pCode(spirv);
            check(vkCreateShaderModule(
                    device, shaderInfo, null, result),
                    "create motion-blur shader module");
            motionBlurShaderModule = result.get(0);

            VkDescriptorSetLayoutBinding.Buffer bindings =
                    VkDescriptorSetLayoutBinding.calloc(3, stack);
            for (int binding = 0; binding < 2; binding++) {
                bindings.get(binding)
                        .binding(binding)
                        .descriptorType(
                                VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                        .descriptorCount(1)
                        .stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            }
            bindings.get(2)
                    .binding(2)
                    .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(1)
                    .stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            VkDescriptorSetLayoutCreateInfo layoutInfo =
                    VkDescriptorSetLayoutCreateInfo.calloc(stack)
                            .sType$Default()
                            .pBindings(bindings);
            check(vkCreateDescriptorSetLayout(
                    device, layoutInfo, null, result),
                    "create motion-blur descriptor layout");
            motionBlurDescriptorSetLayout = result.get(0);

            VkPushConstantRange.Buffer pushConstant =
                    VkPushConstantRange.calloc(1, stack);
            pushConstant.get(0)
                    .stageFlags(VK_SHADER_STAGE_COMPUTE_BIT)
                    .offset(0)
                    .size(16);
            VkPipelineLayoutCreateInfo pipelineLayoutInfo =
                    VkPipelineLayoutCreateInfo.calloc(stack)
                            .sType$Default()
                            .pSetLayouts(stack.longs(
                                    motionBlurDescriptorSetLayout))
                            .pPushConstantRanges(pushConstant);
            check(vkCreatePipelineLayout(
                    device, pipelineLayoutInfo, null, result),
                    "create motion-blur pipeline layout");
            motionBlurPipelineLayout = result.get(0);

            VkPipelineShaderStageCreateInfo stage =
                    VkPipelineShaderStageCreateInfo.calloc(stack)
                            .sType$Default()
                            .stage(VK_SHADER_STAGE_COMPUTE_BIT)
                            .module(motionBlurShaderModule)
                            .pName(stack.UTF8("main"));
            VkComputePipelineCreateInfo.Buffer pipelineInfo =
                    VkComputePipelineCreateInfo.calloc(1, stack);
            pipelineInfo.get(0)
                    .sType$Default()
                    .stage(stage)
                    .layout(motionBlurPipelineLayout);
            check(vkCreateComputePipelines(
                    device, NULL, pipelineInfo, null, result),
                    "create motion-blur compute pipeline");
            motionBlurPipeline = result.get(0);
        } finally {
            MemoryUtil.memFree(spirv);
        }
    }

    private void createMotionBlurDescriptors() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer result = stack.mallocLong(SLOT_COUNT);
            VkDescriptorPoolSize.Buffer poolSizes =
                    VkDescriptorPoolSize.calloc(2, stack);
            poolSizes.get(0)
                    .type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(SLOT_COUNT * 2);
            poolSizes.get(1)
                    .type(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(SLOT_COUNT);
            VkDescriptorPoolCreateInfo poolInfo =
                    VkDescriptorPoolCreateInfo.calloc(stack)
                            .sType$Default()
                            .maxSets(SLOT_COUNT)
                            .pPoolSizes(poolSizes);
            check(vkCreateDescriptorPool(
                    device, poolInfo, null, result),
                    "create motion-blur descriptor pool");
            motionBlurDescriptorPool = result.get(0);

            LongBuffer layouts = stack.mallocLong(SLOT_COUNT);
            for (int index = 0; index < SLOT_COUNT; index++) {
                layouts.put(index, motionBlurDescriptorSetLayout);
            }
            VkDescriptorSetAllocateInfo allocateInfo =
                    VkDescriptorSetAllocateInfo.calloc(stack)
                            .sType$Default()
                            .descriptorPool(motionBlurDescriptorPool)
                            .pSetLayouts(layouts);
            check(vkAllocateDescriptorSets(
                    device, allocateInfo, result),
                    "allocate motion-blur descriptor sets");

            for (int index = 0; index < SLOT_COUNT; index++) {
                motionBlurDescriptorSets[index] = result.get(index);
                FrameSlot slot = slots[index];
                VkDescriptorImageInfo.Buffer sampled =
                        VkDescriptorImageInfo.calloc(2, stack);
                sampled.get(0)
                        .sampler(sampler)
                        .imageView(slot.upscaled.view)
                        .imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                sampled.get(1)
                        .sampler(sampler)
                        .imageView(slot.motion.view)
                        .imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                VkDescriptorImageInfo.Buffer storage =
                        VkDescriptorImageInfo.calloc(1, stack);
                storage.get(0)
                        .imageView(slot.motionBlurOutput.view)
                        .imageLayout(VK_IMAGE_LAYOUT_GENERAL);
                VkWriteDescriptorSet.Buffer writes =
                        VkWriteDescriptorSet.calloc(3, stack);
                for (int binding = 0; binding < 2; binding++) {
                    writes.get(binding)
                            .sType$Default()
                            .dstSet(motionBlurDescriptorSets[index])
                            .dstBinding(binding)
                            .descriptorType(
                                    VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                            .pImageInfo(sampled
                                    .position(binding)
                                    .limit(binding + 1));
                }
                sampled.position(0).limit(2);
                writes.get(2)
                        .sType$Default()
                        .dstSet(motionBlurDescriptorSets[index])
                        .dstBinding(2)
                        .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                        .pImageInfo(storage);
                vkUpdateDescriptorSets(device, writes, null);
            }
        }
    }

    private void copySnapshots(
            VkCommandBuffer commandBuffer,
            FrameResourceSet.ImageResource sourceColor,
            FrameResourceSet.ImageResource sourceDepth,
            FrameSlot current
    ) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            transitionExternalImage(
                    commandBuffer,
                    sourceColor.image(),
                    VK_IMAGE_ASPECT_COLOR_BIT,
                    sourceColor.layout(),
                    VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT,
                    VK_ACCESS_TRANSFER_READ_BIT,
                    VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                    VK_PIPELINE_STAGE_TRANSFER_BIT,
                    stack
            );
            transitionExternalImage(
                    commandBuffer,
                    sourceDepth.image(),
                    VK_IMAGE_ASPECT_DEPTH_BIT,
                    sourceDepth.layout(),
                    VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT,
                    VK_ACCESS_TRANSFER_READ_BIT,
                    VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                    VK_PIPELINE_STAGE_TRANSFER_BIT,
                    stack
            );
            transitionImage(
                    commandBuffer, current.color,
                    current.color.layout, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    current.color.layout == VK_IMAGE_LAYOUT_UNDEFINED ? 0 : VK_ACCESS_SHADER_READ_BIT,
                    VK_ACCESS_TRANSFER_WRITE_BIT,
                    current.color.layout == VK_IMAGE_LAYOUT_UNDEFINED
                            ? VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT
                            : VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK_PIPELINE_STAGE_TRANSFER_BIT, stack);
            transitionImage(
                    commandBuffer, current.depth,
                    current.depth.layout, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    current.depth.layout == VK_IMAGE_LAYOUT_UNDEFINED ? 0 : VK_ACCESS_SHADER_READ_BIT,
                    VK_ACCESS_TRANSFER_WRITE_BIT,
                    current.depth.layout == VK_IMAGE_LAYOUT_UNDEFINED
                            ? VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT
                            : VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK_PIPELINE_STAGE_TRANSFER_BIT, stack);

            copyImageWithLayouts(
                    commandBuffer,
                    sourceColor.image(),
                    VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    current.color.image,
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    VK_IMAGE_ASPECT_COLOR_BIT,
                    width,
                    height,
                    stack
            );
            copyImageWithLayouts(
                    commandBuffer,
                    sourceDepth.image(),
                    VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    current.depth.image,
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    VK_IMAGE_ASPECT_DEPTH_BIT,
                    width,
                    height,
                    stack
            );

            transitionImage(
                    commandBuffer, current.color,
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                    VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT,
                    VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, stack);
            transitionImage(
                    commandBuffer, current.depth,
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                    VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT,
                    VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, stack);
            transitionExternalImage(
                    commandBuffer,
                    sourceColor.image(),
                    VK_IMAGE_ASPECT_COLOR_BIT,
                    VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    sourceColor.layout(),
                    VK_ACCESS_TRANSFER_READ_BIT,
                    VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT,
                    VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                    stack
            );
            transitionExternalImage(
                    commandBuffer,
                    sourceDepth.image(),
                    VK_IMAGE_ASPECT_DEPTH_BIT,
                    VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    sourceDepth.layout(),
                    VK_ACCESS_TRANSFER_READ_BIT,
                    VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT,
                    VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                    stack
            );
        }
    }

    private void initializeHistoryImages(VkCommandBuffer commandBuffer) {
        if (slots[0].color.layout != VK_IMAGE_LAYOUT_UNDEFINED) return;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkClearColorValue colorClear = VkClearColorValue.calloc(stack)
                    .float32(stack.floats(0.0f, 0.0f, 0.0f, 0.0f));
            VkClearDepthStencilValue depthClear = VkClearDepthStencilValue.calloc(stack)
                    .depth(0.0f)
                    .stencil(0);
            for (FrameSlot slot : slots) {
                transitionImage(
                        commandBuffer, slot.color,
                        VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                        0, VK_ACCESS_TRANSFER_WRITE_BIT,
                        VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                        stack);
                transitionImage(
                        commandBuffer, slot.depth,
                        VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                        0, VK_ACCESS_TRANSFER_WRITE_BIT,
                        VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                        stack);
                transitionImage(
                        commandBuffer, slot.motion,
                        VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                        0, VK_ACCESS_TRANSFER_WRITE_BIT,
                        VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                        stack);
                transitionImage(
                        commandBuffer, slot.upscaled,
                        VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                        0, VK_ACCESS_TRANSFER_WRITE_BIT,
                        VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                        stack);
                transitionImage(
                        commandBuffer, slot.ui,
                        VK_IMAGE_LAYOUT_UNDEFINED,
                        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                        0, VK_ACCESS_TRANSFER_WRITE_BIT,
                        VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                        VK_PIPELINE_STAGE_TRANSFER_BIT,
                        stack);
                transitionImage(
                        commandBuffer, slot.rayReconstructionOutput,
                        VK_IMAGE_LAYOUT_UNDEFINED,
                        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                        0, VK_ACCESS_TRANSFER_WRITE_BIT,
                        VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                        VK_PIPELINE_STAGE_TRANSFER_BIT,
                        stack);
                transitionImage(
                        commandBuffer, slot.motionBlurOutput,
                        VK_IMAGE_LAYOUT_UNDEFINED,
                        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                        0, VK_ACCESS_TRANSFER_WRITE_BIT,
                        VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                        VK_PIPELINE_STAGE_TRANSFER_BIT,
                        stack);

                VkImageSubresourceRange colorRange = VkImageSubresourceRange.calloc(stack)
                        .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                        .baseMipLevel(0).levelCount(1)
                        .baseArrayLayer(0).layerCount(1);
                VkImageSubresourceRange depthRange = VkImageSubresourceRange.calloc(stack)
                        .aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT)
                        .baseMipLevel(0).levelCount(1)
                        .baseArrayLayer(0).layerCount(1);
                vkCmdClearColorImage(
                        commandBuffer, slot.color.image,
                        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, colorClear, colorRange);
                vkCmdClearDepthStencilImage(
                        commandBuffer, slot.depth.image,
                        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, depthClear, depthRange);
                vkCmdClearColorImage(
                        commandBuffer, slot.motion.image,
                        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, colorClear, colorRange);
                vkCmdClearColorImage(
                        commandBuffer, slot.upscaled.image,
                        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, colorClear, colorRange);
                vkCmdClearColorImage(
                        commandBuffer, slot.ui.image,
                        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, colorClear, colorRange);
                vkCmdClearColorImage(
                        commandBuffer, slot.rayReconstructionOutput.image,
                        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                        colorClear,
                        colorRange);
                vkCmdClearColorImage(
                        commandBuffer, slot.motionBlurOutput.image,
                        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                        colorClear,
                        colorRange);

                transitionImage(
                        commandBuffer, slot.color,
                        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                        VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                        VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT,
                        VK_PIPELINE_STAGE_TRANSFER_BIT,
                        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, stack);
                transitionImage(
                        commandBuffer, slot.depth,
                        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                        VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                        VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT,
                        VK_PIPELINE_STAGE_TRANSFER_BIT,
                        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, stack);
                transitionImage(
                        commandBuffer, slot.motion,
                        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                        VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                        VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT,
                        VK_PIPELINE_STAGE_TRANSFER_BIT,
                        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, stack);
                transitionImage(
                        commandBuffer, slot.upscaled,
                        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                        VK_IMAGE_LAYOUT_GENERAL,
                        VK_ACCESS_TRANSFER_WRITE_BIT,
                        VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT,
                        VK_PIPELINE_STAGE_TRANSFER_BIT,
                        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, stack);
                transitionImage(
                        commandBuffer, slot.ui,
                        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                        VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                        VK_ACCESS_TRANSFER_WRITE_BIT,
                        VK_ACCESS_SHADER_READ_BIT,
                        VK_PIPELINE_STAGE_TRANSFER_BIT,
                        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                        stack);
                transitionImage(
                        commandBuffer, slot.rayReconstructionOutput,
                        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                        VK_IMAGE_LAYOUT_GENERAL,
                        VK_ACCESS_TRANSFER_WRITE_BIT,
                        VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT,
                        VK_PIPELINE_STAGE_TRANSFER_BIT,
                        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                        stack);
                transitionImage(
                        commandBuffer, slot.motionBlurOutput,
                        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                        VK_IMAGE_LAYOUT_GENERAL,
                        VK_ACCESS_TRANSFER_WRITE_BIT,
                        VK_ACCESS_SHADER_WRITE_BIT,
                        VK_PIPELINE_STAGE_TRANSFER_BIT,
                        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                        stack);
            }
        }
    }

    private void copyUpscaledToSource(
            VkCommandBuffer commandBuffer,
            NativeImage upscaled,
            FrameResourceSet.ImageResource destination
    ) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            transitionImage(
                    commandBuffer,
                    upscaled,
                    VK_IMAGE_LAYOUT_GENERAL,
                    VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    VK_ACCESS_SHADER_WRITE_BIT,
                    VK_ACCESS_TRANSFER_READ_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK_PIPELINE_STAGE_TRANSFER_BIT,
                    stack
            );
            transitionExternalImage(
                    commandBuffer,
                    destination.image(),
                    VK_IMAGE_ASPECT_COLOR_BIT,
                    destination.layout(),
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT,
                    VK_ACCESS_TRANSFER_WRITE_BIT,
                    VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                    VK_PIPELINE_STAGE_TRANSFER_BIT,
                    stack
            );

            copyImageWithLayouts(
                    commandBuffer,
                    upscaled.image,
                    VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    destination.image(),
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    VK_IMAGE_ASPECT_COLOR_BIT,
                    outputWidth,
                    outputHeight,
                    stack
            );

            transitionExternalImage(
                    commandBuffer,
                    destination.image(),
                    VK_IMAGE_ASPECT_COLOR_BIT,
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    destination.layout(),
                    VK_ACCESS_TRANSFER_WRITE_BIT,
                    VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT,
                    VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                    stack
            );
            transitionImage(
                    commandBuffer,
                    upscaled,
                    VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    VK_IMAGE_LAYOUT_GENERAL,
                    VK_ACCESS_TRANSFER_READ_BIT,
                    VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT,
                    VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    stack
            );
        }
    }

    private void blitSnapshotToOwnedOutput(
            VkCommandBuffer commandBuffer,
            NativeImage source,
            NativeImage destination
    ) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            transitionImage(
                    commandBuffer,
                    source,
                    source.layout,
                    VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    VK_ACCESS_SHADER_READ_BIT,
                    VK_ACCESS_TRANSFER_READ_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK_PIPELINE_STAGE_TRANSFER_BIT,
                    stack
            );
            transitionImage(
                    commandBuffer,
                    destination,
                    destination.layout,
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT,
                    VK_ACCESS_TRANSFER_WRITE_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK_PIPELINE_STAGE_TRANSFER_BIT,
                    stack
            );

            VkImageBlit.Buffer region = VkImageBlit.calloc(1, stack);
            region.get(0)
                    .srcSubresource(subresource -> subresource
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
            region.get(0).dstOffsets(1).set(outputWidth, outputHeight, 1);
            vkCmdBlitImage(
                    commandBuffer,
                    source.image,
                    VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    destination.image,
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    region,
                    VK_FILTER_LINEAR
            );

            transitionImage(
                    commandBuffer,
                    destination,
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    VK_IMAGE_LAYOUT_GENERAL,
                    VK_ACCESS_TRANSFER_WRITE_BIT,
                    VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT,
                    VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    stack
            );
            transitionImage(
                    commandBuffer,
                    source,
                    VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                    VK_ACCESS_TRANSFER_READ_BIT,
                    VK_ACCESS_SHADER_READ_BIT,
                    VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    stack
            );
        }
    }

    private void dispatchMotionVectors(
            VkCommandBuffer commandBuffer, int currentIndex, FrameSlot current
    ) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            transitionImage(
                    commandBuffer, current.motion,
                    current.motion.layout, VK_IMAGE_LAYOUT_GENERAL,
                    current.motion.layout == VK_IMAGE_LAYOUT_UNDEFINED
                            ? 0 : VK_ACCESS_SHADER_READ_BIT,
                    VK_ACCESS_SHADER_WRITE_BIT,
                    current.motion.layout == VK_IMAGE_LAYOUT_UNDEFINED
                            ? VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT
                            : VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, stack);
            vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
            vkCmdBindDescriptorSets(
                    commandBuffer,
                    VK_PIPELINE_BIND_POINT_COMPUTE,
                    pipelineLayout,
                    0,
                    stack.longs(descriptorSets[currentIndex]),
                    null
            );
            vkCmdDispatch(commandBuffer, (width + 7) / 8, (height + 7) / 8, 1);
            transitionImage(
                    commandBuffer, current.motion,
                    VK_IMAGE_LAYOUT_GENERAL,
                    VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                    VK_ACCESS_SHADER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, stack);
        }
    }

    private void dispatchRayReconstructionTonemap(
            VkCommandBuffer commandBuffer,
            int currentIndex,
            FrameSlot current
    ) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageMemoryBarrier.Buffer barriers =
                    VkImageMemoryBarrier.calloc(2, stack);
            barriers.get(0)
                    .sType$Default()
                    .oldLayout(VK_IMAGE_LAYOUT_GENERAL)
                    .newLayout(VK_IMAGE_LAYOUT_GENERAL)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .image(current.rayReconstructionOutput.image)
                    .srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT)
                    .dstAccessMask(VK_ACCESS_SHADER_READ_BIT)
                    .subresourceRange(range -> range
                            .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                            .baseMipLevel(0).levelCount(1)
                            .baseArrayLayer(0).layerCount(1));
            barriers.get(1)
                    .sType$Default()
                    .oldLayout(VK_IMAGE_LAYOUT_GENERAL)
                    .newLayout(VK_IMAGE_LAYOUT_GENERAL)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .image(current.upscaled.image)
                    .srcAccessMask(VK_ACCESS_SHADER_READ_BIT)
                    .dstAccessMask(VK_ACCESS_SHADER_WRITE_BIT)
                    .subresourceRange(range -> range
                            .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                            .baseMipLevel(0).levelCount(1)
                            .baseArrayLayer(0).layerCount(1));
            vkCmdPipelineBarrier(
                    commandBuffer,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    0,
                    null,
                    null,
                    barriers
            );
            vkCmdBindPipeline(
                    commandBuffer,
                    VK_PIPELINE_BIND_POINT_COMPUTE,
                    tonemapPipeline
            );
            vkCmdBindDescriptorSets(
                    commandBuffer,
                    VK_PIPELINE_BIND_POINT_COMPUTE,
                    tonemapPipelineLayout,
                    0,
                    stack.longs(tonemapDescriptorSets[currentIndex]),
                    null
            );
            vkCmdDispatch(
                    commandBuffer,
                    (outputWidth + 7) / 8,
                    (outputHeight + 7) / 8,
                    1
            );
        }
    }

    private void dispatchMotionBlur(
            VkCommandBuffer commandBuffer,
            int currentIndex,
            FrameSlot current,
            float intensity,
            int samples
    ) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            transitionImage(
                    commandBuffer,
                    current.upscaled,
                    VK_IMAGE_LAYOUT_GENERAL,
                    VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                    VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_TRANSFER_WRITE_BIT,
                    VK_ACCESS_SHADER_READ_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT
                            | VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    stack
            );
            transitionImage(
                    commandBuffer,
                    current.motionBlurOutput,
                    current.motionBlurOutput.layout,
                    VK_IMAGE_LAYOUT_GENERAL,
                    VK_ACCESS_TRANSFER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT,
                    VK_ACCESS_SHADER_WRITE_BIT,
                    VK_PIPELINE_STAGE_TRANSFER_BIT
                            | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    stack
            );
            vkCmdBindPipeline(
                    commandBuffer,
                    VK_PIPELINE_BIND_POINT_COMPUTE,
                    motionBlurPipeline
            );
            vkCmdBindDescriptorSets(
                    commandBuffer,
                    VK_PIPELINE_BIND_POINT_COMPUTE,
                    motionBlurPipelineLayout,
                    0,
                    stack.longs(motionBlurDescriptorSets[currentIndex]),
                    null
            );
            ByteBuffer parameters = stack.malloc(16);
            parameters.putFloat(0, Math.max(0.0f, Math.min(1.0f, intensity)));
            parameters.putInt(4, Math.max(1, Math.min(64, samples)));
            parameters.putLong(8, 0L);
            vkCmdPushConstants(
                    commandBuffer,
                    motionBlurPipelineLayout,
                    VK_SHADER_STAGE_COMPUTE_BIT,
                    0,
                    parameters
            );
            vkCmdDispatch(
                    commandBuffer,
                    (outputWidth + 7) / 8,
                    (outputHeight + 7) / 8,
                    1
            );
            transitionImage(
                    commandBuffer,
                    current.upscaled,
                    VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                    VK_IMAGE_LAYOUT_GENERAL,
                    VK_ACCESS_SHADER_READ_BIT,
                    VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    stack
            );
        }
    }

    private void recordDebugReadback(VkCommandBuffer commandBuffer, FrameSlot current) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            transitionImage(
                    commandBuffer,
                    current.motion,
                    VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                    VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_SHADER_READ_BIT,
                    VK_ACCESS_TRANSFER_READ_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK_PIPELINE_STAGE_TRANSFER_BIT,
                    stack
            );

            VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
            region.get(0)
                    .bufferOffset(0)
                    .bufferRowLength(0)
                    .bufferImageHeight(0)
                    .imageSubresource(subresource -> subresource
                            .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                            .mipLevel(0)
                            .baseArrayLayer(0)
                            .layerCount(1))
                    .imageOffset(offset -> offset.set(0, 0, 0))
                    .imageExtent(extent -> extent.set(width, height, 1));
            vkCmdCopyImageToBuffer(
                    commandBuffer,
                    current.motion.image,
                    VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    current.debugReadbackBuffer,
                    region
            );

            VkBufferMemoryBarrier.Buffer readbackBarrier =
                    VkBufferMemoryBarrier.calloc(1, stack);
            readbackBarrier.get(0)
                    .sType$Default()
                    .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                    .dstAccessMask(VK_ACCESS_HOST_READ_BIT)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .buffer(current.debugReadbackBuffer)
                    .offset(0)
                    .size(VK_WHOLE_SIZE);
            vkCmdPipelineBarrier(
                    commandBuffer,
                    VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK_PIPELINE_STAGE_HOST_BIT,
                    0,
                    null,
                    readbackBarrier,
                    null
            );

            transitionImage(
                    commandBuffer,
                    current.motion,
                    VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                    VK_ACCESS_TRANSFER_READ_BIT,
                    VK_ACCESS_SHADER_READ_BIT,
                    VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    stack
            );
            current.debugReadbackPending = true;
            current.debugFrameNumber = frameNumber + 1;
        }
    }

    private void readLatestDebugSnapshot() {
        FrameSlot newest = null;
        for (FrameSlot slot : slots) {
            // Debug copy needs one EXTRA confirmed present beyond slot
            // retirement (inUseUntil + 1): the copy was recorded into the same
            // command buffer as the frame that filled the slot, and retirement
            // alone only proves the GPU reached the frame's submission — the
            // additional margin covers the copy-to-buffer tail. Debug-only
            // path; one frame staler is preferable to torn vectors.
            if (slot != null && slot.debugReadbackPending
                    && slot.inUseUntilPresentSerial + 1 <= completedPresentSerial
                    && (newest == null || slot.debugFrameNumber > newest.debugFrameNumber)) {
                newest = slot;
            }
        }
        if (newest == null || (latestDebugSnapshot != null
                && newest.debugFrameNumber <= latestDebugSnapshot.frameNumber())) {
            return;
        }

        long byteSize = Math.multiplyExact(
                Math.multiplyExact((long) width, height),
                MOTION_BYTES_PER_PIXEL
        );
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer mapped = stack.mallocPointer(1);
            check(vkMapMemory(
                    device,
                    newest.debugReadbackMemory,
                    0,
                    byteSize,
                    0,
                    mapped
            ), "map motion-vector debug readback");
            try {
                ByteBuffer source = MemoryUtil.memByteBuffer(
                        mapped.get(0),
                        Math.toIntExact(byteSize)
                ).order(java.nio.ByteOrder.nativeOrder());
                int columns = (width + DEBUG_SAMPLE_STEP - 1) / DEBUG_SAMPLE_STEP;
                int rows = (height + DEBUG_SAMPLE_STEP - 1) / DEBUG_SAMPLE_STEP;
                float[] vectorX = new float[columns * rows];
                float[] vectorY = new float[columns * rows];
                for (int row = 0; row < rows; row++) {
                    int pixelY = Math.min(height - 1,
                            row * DEBUG_SAMPLE_STEP + DEBUG_SAMPLE_STEP / 2);
                    for (int column = 0; column < columns; column++) {
                        int pixelX = Math.min(width - 1,
                                column * DEBUG_SAMPLE_STEP + DEBUG_SAMPLE_STEP / 2);
                        int byteOffset = (pixelY * width + pixelX)
                                * MOTION_BYTES_PER_PIXEL;
                        float x = Float.float16ToFloat(source.getShort(byteOffset));
                        float y = Float.float16ToFloat(source.getShort(byteOffset + 2));
                        int sampleIndex = row * columns + column;
                        vectorX[sampleIndex] = Float.isFinite(x) ? x : 0.0f;
                        vectorY[sampleIndex] = Float.isFinite(y) ? y : 0.0f;
                    }
                }
                latestDebugSnapshot = new MotionVectorSnapshot(
                        width,
                        height,
                        DEBUG_SAMPLE_STEP,
                        columns,
                        rows,
                        vectorX,
                        vectorY,
                        newest.debugFrameNumber
                );
            } finally {
                vkUnmapMemory(device, newest.debugReadbackMemory);
            }
        }
    }

    private void uploadFrameParameters(
            long memory,
            float[] clipToPreviousClip,
            float jitterX,
            float jitterY,
            float previousJitterX,
            float previousJitterY,
            boolean cameraOnly,
            boolean reset
    ) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer mapped = stack.mallocPointer(1);
            check(vkMapMemory(device, memory, 0, UNIFORM_SIZE, 0, mapped),
                    "map motion-vector uniform");
            ByteBuffer target = MemoryUtil.memByteBuffer(
                    mapped.get(0),
                    UNIFORM_SIZE
            ).order(java.nio.ByteOrder.nativeOrder());
            new Matrix4f().set(clipToPreviousClip).get(0, target);
            target.putFloat(64, width);
            target.putFloat(68, height);
            target.putFloat(72, jitterX - previousJitterX);
            target.putFloat(76, jitterY - previousJitterY);
            target.putInt(80, reset ? 1 : 0);
            // Resolution-proportional search radius — constant world-space
            // search window (4 at 1080p, up to 8 at 4K; the shader clamps to
            // 0..8) instead of a fixed radius that over-searches low
            // resolutions and under-searches high ones.
            // 0 = camera reprojection only (first frame after a resize).
            int searchRadius = cameraOnly ? 0 : Math.max(
                    2, Math.min(8, (Math.max(width, height) + 135) / 270));
            target.putInt(84, searchRadius);
            target.putLong(88, 0L);
            ObjectMotion objects = ObjectMotion.INSTANCE;
            new Matrix4f().set(objects.clipToCameraRelative()).get(96, target);
            new Matrix4f().set(objects.cameraRelativeToPreviousClip()).get(160, target);
            ObjectMotion.writeBoxes(target, 224, reset ? java.util.List.of() : objects.boxes());
            vkUnmapMemory(device, memory);
        }
    }

    private NativeImage createImage(
            int format,
            int aspectMask,
            int usage,
            int imageWidth,
            int imageHeight
    ) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer result = stack.mallocLong(1);
            VkImageCreateInfo imageInfo = VkImageCreateInfo.calloc(stack)
                    .sType$Default()
                    .imageType(VK_IMAGE_TYPE_2D)
                    .format(format)
                    .extent(extent -> extent
                            .width(imageWidth)
                            .height(imageHeight)
                            .depth(1))
                    .mipLevels(1)
                    .arrayLayers(1)
                    .samples(VK_SAMPLE_COUNT_1_BIT)
                    .tiling(VK_IMAGE_TILING_OPTIMAL)
                    .usage(usage)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                    .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            check(vkCreateImage(device, imageInfo, null, result), "create FG image");
            long image = result.get(0);

            VkMemoryRequirements requirements = VkMemoryRequirements.calloc(stack);
            vkGetImageMemoryRequirements(device, image, requirements);
            VkMemoryAllocateInfo allocationInfo = VkMemoryAllocateInfo.calloc(stack)
                    .sType$Default()
                    .allocationSize(requirements.size())
                    .memoryTypeIndex(findMemoryType(
                            requirements.memoryTypeBits(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, stack));
            check(vkAllocateMemory(device, allocationInfo, null, result),
                    "allocate FG image memory");
            long memory = result.get(0);
            check(vkBindImageMemory(device, image, memory, 0), "bind FG image memory");

            VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack)
                    .sType$Default()
                    .image(image)
                    .viewType(VK_IMAGE_VIEW_TYPE_2D)
                    .format(format)
                    .subresourceRange(range -> range
                            .aspectMask(aspectMask)
                            .baseMipLevel(0)
                            .levelCount(1)
                            .baseArrayLayer(0)
                            .layerCount(1));
            check(vkCreateImageView(device, viewInfo, null, result),
                    "create FG image view");
            return new NativeImage(
                    image,
                    memory,
                    result.get(0),
                    aspectMask,
                    imageWidth,
                    imageHeight
            );
        }
    }

    private Allocation createBuffer(long size, int usage, int memoryProperties) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer result = stack.mallocLong(1);
            VkBufferCreateInfo bufferInfo = VkBufferCreateInfo.calloc(stack)
                    .sType$Default()
                    .size(size)
                    .usage(usage)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            check(vkCreateBuffer(device, bufferInfo, null, result), "create FG buffer");
            long buffer = result.get(0);
            VkMemoryRequirements requirements = VkMemoryRequirements.calloc(stack);
            vkGetBufferMemoryRequirements(device, buffer, requirements);
            VkMemoryAllocateInfo allocationInfo = VkMemoryAllocateInfo.calloc(stack)
                    .sType$Default()
                    .allocationSize(requirements.size())
                    .memoryTypeIndex(findMemoryType(
                            requirements.memoryTypeBits(), memoryProperties, stack));
            check(vkAllocateMemory(device, allocationInfo, null, result),
                    "allocate FG buffer memory");
            long memory = result.get(0);
            check(vkBindBufferMemory(device, buffer, memory, 0), "bind FG buffer memory");
            return new Allocation(buffer, memory);
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
        throw new IllegalStateException("No compatible Vulkan memory type for FG resource");
    }

    private void copyImage(
            VkCommandBuffer commandBuffer,
            long source,
            long destination,
            int aspectMask,
            int copyWidth,
            int copyHeight,
            MemoryStack stack
    ) {
        copyImageWithLayouts(
                commandBuffer,
                source,
                VK_IMAGE_LAYOUT_GENERAL,
                destination,
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                aspectMask,
                copyWidth,
                copyHeight,
                stack
        );
    }

    private void copyImageWithLayouts(
            VkCommandBuffer commandBuffer,
            long source,
            int sourceLayout,
            long destination,
            int destinationLayout,
            int aspectMask,
            int copyWidth,
            int copyHeight,
            MemoryStack stack
    ) {
        VkImageSubresourceLayers layers = VkImageSubresourceLayers.calloc(stack)
                .aspectMask(aspectMask)
                .mipLevel(0)
                .baseArrayLayer(0)
                .layerCount(1);
        VkImageCopy.Buffer copy = VkImageCopy.calloc(1, stack);
        copy.get(0)
                .srcSubresource(layers)
                .dstSubresource(layers)
                .extent(extent -> extent
                        .width(copyWidth)
                        .height(copyHeight)
                        .depth(1));
        vkCmdCopyImage(
                commandBuffer,
                source,
                sourceLayout,
                destination,
                destinationLayout,
                copy
        );
    }

    private static void transitionImage(
            VkCommandBuffer commandBuffer,
            NativeImage image,
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
                .image(image.image)
                .srcAccessMask(sourceAccess)
                .dstAccessMask(destinationAccess)
                .subresourceRange(range -> range
                        .aspectMask(image.aspectMask)
                        .baseMipLevel(0)
                        .levelCount(1)
                        .baseArrayLayer(0)
                        .layerCount(1));
        vkCmdPipelineBarrier(
                commandBuffer, sourceStage, destinationStage,
                0, null, null, barrier);
        image.layout = newLayout;
    }

    private static void transitionExternalImage(
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

    private static int vulkanFormat(VulkanGpuTextureView view) {
        return switch (view.texture().getFormat()) {
            case RGBA8_UNORM -> VK_FORMAT_R8G8B8A8_UNORM;
            case RGBA8_SNORM -> VK_FORMAT_R8G8B8A8_SNORM;
            case RGBA16_FLOAT -> VK_FORMAT_R16G16B16A16_SFLOAT;
            case D32_FLOAT -> VK_FORMAT_D32_SFLOAT;
            case D32_FLOAT_S8_UINT -> VK_FORMAT_D32_SFLOAT_S8_UINT;
            case D24_UNORM_S8_UINT -> VK_FORMAT_D24_UNORM_S8_UINT;
            case D16_UNORM -> VK_FORMAT_D16_UNORM;
            default -> throw new IllegalArgumentException(
                    "Unsupported frame-generation texture format "
                            + view.texture().getFormat());
        };
    }

    private static void check(int result, String action) {
        if (result != VK_SUCCESS) {
            throw new IllegalStateException(action + " failed with VkResult " + result);
        }
    }

    // Out of memory is retryable; validation failures latch.
    private static boolean isOutOfMemoryFailure(Throwable throwable) {
        if (throwable == null) {
            return false;
        }
        if (throwable instanceof OutOfMemoryError) {
            return true;
        }
        String msg = throwable.getMessage();
        if (msg != null) {
            String lower = msg.toLowerCase(java.util.Locale.ROOT);
            if (lower.contains("out_of_host_memory") || lower.contains("out_of_device_memory")
                    || lower.contains("out of host memory") || lower.contains("out of device memory")
                    || lower.contains("out_of_memory") || lower.contains("outofmemory")) {
                return true;
            }
            // Vulkan VkResult codes: -1 OUT_OF_HOST_MEMORY, -2 OUT_OF_DEVICE_MEMORY
            if (lower.contains("vkresult -1") || lower.contains("vkresult -2")
                    || lower.contains("vkresult:-1") || lower.contains("vkresult:-2")) {
                return true;
            }
            if (msg.contains("VK_ERROR_OUT_OF_HOST_MEMORY") || msg.contains("VK_ERROR_OUT_OF_DEVICE_MEMORY")) {
                return true;
            }
            // Direct code check fallback: "failed with VkResult -2"
            if (lower.contains("failed with vkresult -1") || lower.contains("failed with vkresult -2")) {
                return true;
            }
        }
        Throwable cause = throwable.getCause();
        if (cause != null && cause != throwable) {
            return isOutOfMemoryFailure(cause);
        }
        // Check suppressed as well (some Vulkan wrappers add)
        for (Throwable sup : throwable.getSuppressed()) {
            if (isOutOfMemoryFailure(sup)) {
                return true;
            }
        }
        return false;
    }

    private void destroyResources() {
        for (FrameSlot slot : slots) {
            if (slot == null) continue;
            destroyImage(slot.motionBlurOutput);
            destroyImage(slot.ui);
            destroyImage(slot.rayReconstructionOutput);
            destroyImage(slot.motion);
            destroyImage(slot.upscaled);
            destroyImage(slot.depth);
            destroyImage(slot.color);
            if (slot.uniformBuffer != NULL) vkDestroyBuffer(device, slot.uniformBuffer, null);
            if (slot.uniformMemory != NULL) vkFreeMemory(device, slot.uniformMemory, null);
            if (slot.debugReadbackBuffer != NULL) {
                vkDestroyBuffer(device, slot.debugReadbackBuffer, null);
            }
            if (slot.debugReadbackMemory != NULL) {
                vkFreeMemory(device, slot.debugReadbackMemory, null);
            }
        }
        if (descriptorPool != NULL) vkDestroyDescriptorPool(device, descriptorPool, null);
        if (tonemapDescriptorPool != NULL) {
            vkDestroyDescriptorPool(device, tonemapDescriptorPool, null);
        }
        if (motionBlurDescriptorPool != NULL) {
            vkDestroyDescriptorPool(device, motionBlurDescriptorPool, null);
        }
        if (pipeline != NULL) vkDestroyPipeline(device, pipeline, null);
        if (tonemapPipeline != NULL) {
            vkDestroyPipeline(device, tonemapPipeline, null);
        }
        if (motionBlurPipeline != NULL) {
            vkDestroyPipeline(device, motionBlurPipeline, null);
        }
        if (pipelineLayout != NULL) vkDestroyPipelineLayout(device, pipelineLayout, null);
        if (tonemapPipelineLayout != NULL) {
            vkDestroyPipelineLayout(device, tonemapPipelineLayout, null);
        }
        if (motionBlurPipelineLayout != NULL) {
            vkDestroyPipelineLayout(device, motionBlurPipelineLayout, null);
        }
        if (descriptorSetLayout != NULL) {
            vkDestroyDescriptorSetLayout(device, descriptorSetLayout, null);
        }
        if (sampler != NULL) vkDestroySampler(device, sampler, null);
        if (shaderModule != NULL) vkDestroyShaderModule(device, shaderModule, null);
        if (tonemapDescriptorSetLayout != NULL) {
            vkDestroyDescriptorSetLayout(
                    device, tonemapDescriptorSetLayout, null);
        }
        if (tonemapShaderModule != NULL) {
            vkDestroyShaderModule(device, tonemapShaderModule, null);
        }
        if (motionBlurDescriptorSetLayout != NULL) {
            vkDestroyDescriptorSetLayout(
                    device, motionBlurDescriptorSetLayout, null);
        }
        if (motionBlurShaderModule != NULL) {
            vkDestroyShaderModule(device, motionBlurShaderModule, null);
        }
        descriptorPool = pipeline = pipelineLayout = descriptorSetLayout = sampler = shaderModule = NULL;
        tonemapDescriptorPool = tonemapPipeline = tonemapPipelineLayout =
                tonemapDescriptorSetLayout = tonemapShaderModule = NULL;
        motionBlurDescriptorPool = motionBlurPipeline =
                motionBlurPipelineLayout = motionBlurDescriptorSetLayout =
                        motionBlurShaderModule = NULL;
        java.util.Arrays.fill(slots, null);
        latestDebugSnapshot = null;
    }

    private void destroyImage(NativeImage image) {
        if (image == null) return;
        if (image.view != NULL) vkDestroyImageView(device, image.view, null);
        if (image.image != NULL) vkDestroyImage(device, image.image, null);
        if (image.memory != NULL) vkFreeMemory(device, image.memory, null);
    }

    @Override
    public void close() {
        vkDeviceWaitIdle(device);
        releaseRetiredResources(true);
        destroyResources();
    }

    private static final class FrameSlot {
        private NativeImage color;
        private NativeImage depth;
        private NativeImage motion;
        private NativeImage upscaled;
        private NativeImage ui;
        private NativeImage rayReconstructionOutput;
        private NativeImage motionBlurOutput;
        private long uniformBuffer;
        private long uniformMemory;
        private long debugReadbackBuffer;
        private long debugReadbackMemory;
        private long debugFrameNumber;
        private long inUseUntilPresentSerial;
        private boolean debugReadbackPending;
    }

    private static final class NativeImage {
        private final long image;
        private final long memory;
        private final long view;
        private final int aspectMask;
        private final int width;
        private final int height;
        private int layout = VK_IMAGE_LAYOUT_UNDEFINED;

        private NativeImage(
                long image,
                long memory,
                long view,
                int aspectMask,
                int width,
                int height
        ) {
            this.image = image;
            this.memory = memory;
            this.view = view;
            this.aspectMask = aspectMask;
            this.width = width;
            this.height = height;
        }
    }

    private record Allocation(long buffer, long memory) {
    }

    private static final class Retired {
        private final java.util.List<NativeImage> images = new java.util.ArrayList<>();
        private final java.util.List<Allocation> buffers = new java.util.ArrayList<>();
        private final java.util.List<Long> descriptorPools = new java.util.ArrayList<>();
        private long releaseAfterPresentSerial;
    }
}
