package org.malionaro.dlss_frame_generation.client;

import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanGpuSurface;
import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.GameRenderState;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.malionaro.dlss_frame_generation.client.access.VulkanDeviceAccess;
import org.malionaro.dlss_frame_generation.client.motion.MotionVectorResource;
import org.malionaro.dlss_frame_generation.client.motion.MotionVectorSnapshot;
import org.malionaro.dlss_frame_generation.client.motion.MotionVectorValidator;
import org.malionaro.dlss_frame_generation.client.motion.VulkanFrameGenerationPipeline;
import org.malionaro.dlss_frame_generation.client.raytracing.RayReconstructionResources;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class VulkanRegistry {
    private static final Logger LOGGER = LoggerFactory.getLogger("DLSS_FG_Registry");
    private static VulkanDevice activeDevice;
    private static VulkanGpuSurface activeSurface;
    private static VulkanCommandEncoder activeEncoder;
    private static long activeColorImage;
    private static VulkanFrameGenerationPipeline frameGenerationPipeline;
    private static MotionVectorResource activeMotionVectors;
    private static MotionVectorSnapshot activeMotionVectorSnapshot;
    private static MotionVectorValidator.ValidationResult motionVectorValidation;
    private static final MotionVectorValidator MOTION_VECTOR_VALIDATOR =
            new MotionVectorValidator();
    private static long previousCaptureNanos;
    private static Boolean lastProviderFrameAccepted;
    private static NvidiaDlssRenderResolution.Resolution activeResolution =
            new NvidiaDlssRenderResolution.Resolution(1, 1, 1, 1, 0, false);

    public static void registerDevice(VulkanDevice device) {
        activeDevice = device;
        LOGGER.info("Registered active VulkanDevice context: {}", device);
        frameGenerationPipeline = new VulkanFrameGenerationPipeline(device, LOGGER);

        if (FrameGenMod.rayTracingManager() != null) {
            FrameGenMod.rayTracingManager().onDeviceRegistered(device);
        }
        
        // Dynamic initialization of the selected vendor-neutral native provider.
        CompatibilityManager comp = FrameGenMod.compatibilityManager();
        if (comp != null) {
            comp.activateVulkanAdapter();
            RendererBackendAdapter adapter = comp.adapter();
            if (adapter != null) {
                long instance = adapter.getVulkanInstance();
                long physicalDevice = adapter.getPhysicalDevice();
                long logicalDevice = adapter.getVulkanDevice();
                long queue = adapter.getGraphicsQueue();
                int queueFamily = adapter.getGraphicsQueueFamilyIndex();

                int vendorId = 0;
                if (device instanceof VulkanDeviceAccess access
                        && access.dlss_fg$getPhysicalDevice() != null) {
                    vendorId = access.dlss_fg$getPhysicalDevice()
                            .vkPhysicalDeviceProperties().vendorID();
                }
                comp.selectBackendForVendorId(vendorId);
                FrameGenerationBackend.GpuVendor detectedVendor =
                        FrameGenerationBackend.GpuVendor.fromVendorId(vendorId);
                OptiScalerIntegration.Status optiScalerStatus =
                        OptiScalerIntegration.loadConfiguredForVendor(
                                FrameGenConfig.getInstance(),
                                detectedVendor,
                                LOGGER
                        );
                LOGGER.info(
                        "Optional OptiScaler integration: {}",
                        optiScalerStatus.detail()
                );
                
                if (instance != 0 && logicalDevice != 0 && queue != 0) {
                    LOGGER.info("Initializing native frame-generation provider (instance={}, dev={}, queue={})...",
                            Long.toHexString(instance), Long.toHexString(logicalDevice), Long.toHexString(queue));
                    try {
                        String pluginsPath = NativeLoader.getExtractedNativesDir();
                        boolean success = StreamlineBridge.nInit(
                            instance, physicalDevice, logicalDevice, queue,
                            queueFamily, vendorId, pluginsPath
                        );
                        LOGGER.info("Native Vulkan backend initialization status: {}",
                                success ? "SUCCESS" : "FAILED/NO SUPPORTED FEATURE");
                        LOGGER.info("Selected Vulkan compatibility route: {}",
                                StreamlineBridge.nGetActiveProviderName());
                        if (success) {
                            int capabilities =
                                    StreamlineBridge.nGetBackendCapabilities(vendorId);
                            int supportedCapabilities =
                                    vendorId == FrameGenerationBackend.GpuVendor.NVIDIA.vendorId()
                                            ? StreamlineBridge.nGetBackendSupportedCapabilities(vendorId)
                                            : capabilities;
                            FrameGenConfig config = FrameGenConfig.getInstance();
                            if (vendorId == FrameGenerationBackend.GpuVendor.NVIDIA.vendorId()
                                    && (supportedCapabilities & StreamlineBridge.CAP_LOW_LATENCY) != 0) {
                                StreamlineBridge.nSetReflexMode(
                                        config.reflexMode, config.targetFps
                                );
                                LOGGER.info("Initial Reflex mode configured: {}",
                                        config.reflexMode);
                            }
                            if (vendorId == FrameGenerationBackend.GpuVendor.NVIDIA.vendorId()
                                    && (supportedCapabilities & StreamlineBridge.CAP_UPSCALING) != 0) {
                                StreamlineBridge.nSetDlssMode(
                                        config.dlssUpscalingMode,
                                        0.0f
                                );
                                LOGGER.info(
                                        "Initial DLSS reconstruction configured: {}",
                                        config.dlssUpscalingMode
                                );
                            } else if (vendorId == FrameGenerationBackend.GpuVendor.AMD.vendorId()
                                    && (capabilities & StreamlineBridge.CAP_UPSCALING) != 0) {
                                StreamlineBridge.nSetUpscalingMode(
                                        vendorId,
                                        config.amdFsrMode,
                                        config.amdFsrSharpness
                                );
                                LOGGER.info(
                                        "Initial FidelityFX/OptiScaler Native-AA mode configured: {}",
                                        config.amdFsrMode
                                );
                            } else if (vendorId == FrameGenerationBackend.GpuVendor.INTEL.vendorId()
                                    && (capabilities & StreamlineBridge.CAP_UPSCALING) != 0) {
                                StreamlineBridge.nSetUpscalingMode(
                                        vendorId,
                                        config.intelXessMode,
                                        config.intelXessSharpness
                                );
                                LOGGER.info(
                                        "Initial cross-vendor FidelityFX/OptiScaler Native-AA mode configured: {}",
                                        config.intelXessMode
                                );
                            }
                            if ((capabilities & StreamlineBridge.CAP_FRAME_GENERATION) == 0) {
                                LOGGER.info(
                                        "Frame Generation remains disabled until the selected route reports it ready: {}",
                                        StreamlineBridge.nGetBackendStatus(vendorId)
                                );
                            }
                        }
                    } catch (UnsatisfiedLinkError e) {
                        LOGGER.error("Failed to call native nInit (bridge not loaded yet): {}", e.getMessage());
                    }
                } else {
                    LOGGER.warn("Vulkan context incomplete; postponing native JNI initialization");
                }
            }
        }
    }

    public static VulkanDevice getActiveDevice() {
        return activeDevice;
    }

    public static void registerSurface(VulkanGpuSurface surface) {
        activeSurface = surface;
        LOGGER.info("Registered active VulkanGpuSurface context: {}", surface);
    }

    public static VulkanGpuSurface getActiveSurface() {
        return activeSurface;
    }

    public static void registerCommandEncoder(VulkanCommandEncoder encoder) {
        activeEncoder = encoder;
    }

    public static void registerEncoder(VulkanCommandEncoder encoder) {
        registerCommandEncoder(encoder);
    }

    public static VulkanCommandEncoder getActiveCommandEncoder() {
        return activeEncoder;
    }

    public static VulkanCommandEncoder getActiveEncoder() {
        return activeEncoder;
    }

    public static void notifySwapchainReconfigureStarting() {
        CompatibilityManager comp = FrameGenMod.compatibilityManager();
        if (comp == null || comp.activeVendorBackend() == null
                || comp.activeVendorBackend().vendor()
                != FrameGenerationBackend.GpuVendor.NVIDIA) {
            return;
        }
        try {
            // NVIDIA requires DLSS-G to be disabled before resize/fullscreen
            // swapchain work starts. notifySwapchainRecreated restores the
            // configured mode after the replacement is live.
            StreamlineBridge.nSetFgMode(
                    false,
                    FrameGenConfig.getInstance().frameMultiplier,
                    FrameGenConfig.getInstance().uiRecomposition
            );
        } catch (UnsatisfiedLinkError error) {
            LOGGER.warn(
                    "Could not disable DLSS-G before swapchain reconfiguration: {}",
                    error.getMessage()
            );
        }
    }

    public static void notifySwapchainRecreated() {
        CompatibilityManager comp = FrameGenMod.compatibilityManager();
        if (comp != null) {
            RendererBackendAdapter adapter = comp.adapter();
            if (adapter != null && adapter.getSwapchain() != 0) {
                LOGGER.info("Notifying JNI bridge of Vulkan Swapchain recreation: target={}, dim={}x{}",
                        Long.toHexString(adapter.getSwapchain()), adapter.getRenderWidth(), adapter.getRenderHeight());
                try {
                    StreamlineBridge.nRecreateSwapchain(
                        adapter.getSwapchain(),
                        adapter.getRenderWidth(),
                        adapter.getRenderHeight()
                    );
                    FrameGenerationBackend backend = comp.activeVendorBackend();
                    if (backend != null
                            && backend.vendor()
                            == FrameGenerationBackend.GpuVendor.NVIDIA) {
                        StreamlineBridge.nSetFgMode(
                                FrameGenConfig.getInstance().dlssFgEnabled,
                                FrameGenConfig.getInstance().frameMultiplier,
                                FrameGenConfig.getInstance().uiRecomposition
                        );
                    }
                } catch (UnsatisfiedLinkError e) {
                    LOGGER.warn("UnsatisfiedLinkError on swapchain recreation notification: {}", e.getMessage());
                }
            }
        }
    }

    private static final Matrix4f prevViewMatrix = new Matrix4f();
    private static final Matrix4f prevProjMatrix = new Matrix4f();
    private static final Matrix4f currentViewMatrix = new Matrix4f();
    private static final Matrix4f currentProjMatrix = new Matrix4f();

    private static final float[] viewToClipArr = new float[16];
    private static final float[] clipToViewArr = new float[16];
    private static final float[] clipToPrevClipArr = new float[16];
    private static final float[] prevClipToClipArr = new float[16];
    private static final float[] worldToViewArr = new float[16];
    private static final float[] viewToWorldArr = new float[16];

    private static float camPosX, camPosY, camPosZ;
    private static float camUpX, camUpY, camUpZ;
    private static float camRightX, camRightY, camRightZ;
    private static float camFwdX, camFwdY, camFwdZ;
    private static float nearPlane = 0.05f;
    private static float farPlane = 512.0f;
    private static float cameraFov = 70.0f;
    private static float cameraAspect = 1.6f;
    private static boolean worldReset = true;
    private static boolean resetForCurrentFrame = true;

    public static void setWorldReset() {
        worldReset = true;
        if (frameGenerationPipeline != null) {
            frameGenerationPipeline.resetHistory();
        }
    }

    private static void updateCameraState(GameRenderState gameRenderState) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.level == null || gameRenderState == null) {
            worldReset = true;
            return;
        }
        net.minecraft.client.Camera camera = null;
        if (mc.gameRenderer instanceof org.malionaro.dlss_frame_generation.client.mixin.GameRendererAccessor accessor) {
            camera = accessor.dlss_fg$getMainCamera();
        }
        if (camera == null || !(camera instanceof org.malionaro.dlss_frame_generation.client.mixin.CameraAccessor camAccessor)) return;

        resetForCurrentFrame = worldReset;

        // Save previous matrices
        prevViewMatrix.set(currentViewMatrix);
        prevProjMatrix.set(currentProjMatrix);
        float previousCameraX = camPosX;
        float previousCameraY = camPosY;
        float previousCameraZ = camPosZ;
        float previousForwardX = camFwdX;
        float previousForwardY = camFwdY;
        float previousForwardZ = camFwdZ;
        float previousFov = cameraFov;

        // Fetch camera vectors
        net.minecraft.world.phys.Vec3 pos = camAccessor.dlss_fg$getPosition();
        Vector3f fwd = camAccessor.dlss_fg$getForwards();
        Vector3f up = camAccessor.dlss_fg$getUp();
        Vector3f left = camAccessor.dlss_fg$getLeft();

        camPosX = (float) pos.x;
        camPosY = (float) pos.y;
        camPosZ = (float) pos.z;

        camFwdX = fwd.x;
        camFwdY = fwd.y;
        camFwdZ = fwd.z;

        camUpX = up.x;
        camUpY = up.y;
        camUpZ = up.z;

        camRightX = -left.x;
        camRightY = -left.y;
        camRightZ = -left.z;

        net.minecraft.client.renderer.state.level.CameraRenderState cameraState =
                gameRenderState.levelRenderState.cameraRenderState;
        Integer renderDist = null;
        if (mc.options instanceof org.malionaro.dlss_frame_generation.client.mixin.OptionsAccessor optAccessor) {
            if (optAccessor.dlss_fg$getRenderDistance() != null) {
                renderDist = optAccessor.dlss_fg$getRenderDistance().get();
            }
        }

        nearPlane = 0.05f;
        farPlane = cameraState.depthFar > nearPlane
                ? cameraState.depthFar
                : renderDist != null ? renderDist.floatValue() * 16.0f : 256.0f;

        // Build a complete world-to-view matrix. Omitting camera translation
        // makes clipToPrevClip blind to player movement.
        currentViewMatrix.identity().lookAt(
            camPosX, camPosY, camPosZ,
            camPosX + fwd.x, camPosY + fwd.y, camPosZ + fwd.z,
            up.x, up.y, up.z
        );

        // Capture Minecraft's exact unjittered projection. This includes
        // dynamic FOV effects and any renderer projection correction while
        // keeping the matrices sent to Streamline free of temporal jitter.
        currentProjMatrix.set(cameraState.projectionMatrix);
        float projectionY = Math.abs(currentProjMatrix.m11());
        float projectionX = Math.abs(currentProjMatrix.m00());
        float verticalFovRadians = projectionY > 1.0e-6f
                ? 2.0f * (float) Math.atan(1.0f / projectionY)
                : (float) Math.toRadians(70.0);
        cameraFov = (float) Math.toDegrees(verticalFovRadians);
        cameraAspect = projectionX > 1.0e-6f
                ? projectionY / projectionX
                : activeResolution.outputHeight() > 0
                        ? (float) activeResolution.outputWidth()
                                / activeResolution.outputHeight()
                        : 1.0f;

        if (!worldReset) {
            float deltaX = camPosX - previousCameraX;
            float deltaY = camPosY - previousCameraY;
            float deltaZ = camPosZ - previousCameraZ;
            float distanceSquared =
                    deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ;
            float forwardDot = camFwdX * previousForwardX
                    + camFwdY * previousForwardY
                    + camFwdZ * previousForwardZ;
            if (distanceSquared > 16.0f
                    || forwardDot < 0.70710677f
                    || Math.abs(cameraFov - previousFov) > 12.0f) {
                resetForCurrentFrame = true;
            }
        }

        // Discontinuous camera changes must not reuse temporal DLSS/FG
        // history. Initializing previous to current also produces a zero
        // motion field for the reset frame.
        if (resetForCurrentFrame) {
            prevViewMatrix.set(currentViewMatrix);
            prevProjMatrix.set(currentProjMatrix);
            worldReset = false;
        }

        // Calculate clipToPrevClip & prevClipToClip JNI matrices
        Matrix4f clipToPrev = new Matrix4f();
        clipToPrev.set(prevProjMatrix);
        clipToPrev.mul(prevViewMatrix);

        Matrix4f temp = new Matrix4f();
        currentViewMatrix.invert(temp);
        clipToPrev.mul(temp);

        currentProjMatrix.invert(temp);
        clipToPrev.mul(temp);

        clipToPrev.get(clipToPrevClipArr);

        Matrix4f prevToCurr = new Matrix4f();
        clipToPrev.invert(prevToCurr);
        prevToCurr.get(prevClipToClipArr);

        // Feature: AI Optical Flow Artifact Corrector
        org.malionaro.dlss_frame_generation.client.motion.OpticalFlowArtifactCorrector.updateCameraRotation(camFwdX, camFwdY, camFwdZ);

        // Get viewToClip and clipToView float arrays
        currentProjMatrix.get(viewToClipArr);
        currentProjMatrix.invert(temp);
        temp.get(clipToViewArr);
        currentViewMatrix.get(worldToViewArr);
        currentViewMatrix.invert(temp);
        temp.get(viewToWorldArr);
    }

    private static int jitterIndex = 0;
    private static float jitterX = 0.0f;
    private static float jitterY = 0.0f;
    private static float previousJitterX = 0.0f;
    private static float previousJitterY = 0.0f;

    // Halton 2,3 sub-pixel sequence for projection matrix jitter
    private static float halton(int index, int base) {
        float f = 1.0f;
        float r = 0.0f;
        while (index > 0) {
            f /= (float) base;
            r += f * (float) (index % base);
            index /= base;
        }
        return r;
    }

    private static void updateSubPixelJitter(int renderWidth, int renderHeight) {
        previousJitterX = jitterX;
        previousJitterY = jitterY;
        if (!FrameGenConfig.getInstance().projectionJitterEnabled || renderWidth <= 0 || renderHeight <= 0) {
            jitterX = 0.0f;
            jitterY = 0.0f;
            return;
        }
        jitterIndex = (jitterIndex + 1) % 8;
        jitterX = halton(jitterIndex + 1, 2) - 0.5f;
        jitterY = halton(jitterIndex + 1, 3) - 0.5f;
    }

    public static NvidiaDlssRenderResolution.Resolution resolveWorldResolution(
            int outputWidth,
            int outputHeight
    ) {
        FrameGenConfig config = FrameGenConfig.getInstance();
        boolean dlssSupported = nvidiaFeatureSupported(
                StreamlineBridge.CAP_UPSCALING
        );
        boolean rrSupported = nvidiaFeatureSupported(
                StreamlineBridge.CAP_RAY_RECONSTRUCTION
        );
        activeResolution = NvidiaDlssRenderResolution.resolve(
                outputWidth,
                outputHeight,
                config.dlssUpscalingMode,
                dlssSupported,
                config.dlssRayReconstructionEnabled,
                rrSupported
        );
        return activeResolution;
    }

    public static void beginWorldFrame(GameRenderState gameRenderState) {
        updateCameraState(gameRenderState);
        updateSubPixelJitter(
                activeResolution.renderWidth(),
                activeResolution.renderHeight()
        );
        if (!FrameGenConfig.getInstance().projectionJitterEnabled
                || activeResolution.mode() == 0) {
            return;
        }

        Matrix4f projection =
                gameRenderState.levelRenderState.cameraRenderState.projectionMatrix;
        // JOML's perspective matrix contributes m20/m21 through clip-space Z.
        // These signs produce the requested screen-space pixel offset for
        // Vulkan's top-left image convention.
        projection.m20(projection.m20()
                - 2.0f * jitterX / activeResolution.renderWidth());
        projection.m21(projection.m21()
                + 2.0f * jitterY / activeResolution.renderHeight());
    }

    public static boolean shouldSeparateUi() {
        FrameGenConfig config = FrameGenConfig.getInstance();
        Minecraft minecraft = Minecraft.getInstance();
        return config.uiRecomposition
                && config.dlssFgEnabled
                && minecraft != null
                && !minecraft.isPaused()
                && nvidiaFeatureSupported(StreamlineBridge.CAP_FRAME_GENERATION);
    }

    private static boolean nvidiaFeatureSupported(int capability) {
        CompatibilityManager compatibility = FrameGenMod.compatibilityManager();
        FrameGenerationBackend backend = compatibility != null
                ? compatibility.activeVendorBackend()
                : null;
        if (backend == null
                || backend.vendor() != FrameGenerationBackend.GpuVendor.NVIDIA) {
            return false;
        }
        try {
            return (StreamlineBridge.nGetBackendSupportedCapabilities(
                    FrameGenerationBackend.GpuVendor.NVIDIA.vendorId()
            ) & capability) != 0;
        } catch (UnsatisfiedLinkError ignored) {
            return false;
        }
    }

    public static void onPresent() {
        // Feature 3: Auto-Reflex Latency Pacing
        ReflexPacer.paceFrame();

        // Feature: Dynamic VRAM Budget Optimizer
        org.malionaro.dlss_frame_generation.client.vulkan.VramBudgetOptimizer.checkVramPressure();

        // Provider work was recorded at the pre-HUD capture point. Streamline
        // and FidelityFX insert generated frames from their present hooks.
    }

    public static void onPresentCompleted() {
        if (frameGenerationPipeline != null) {
            frameGenerationPipeline.onPresentCompleted();
        }
    }

    public static void registerColorImage(long image) {
        activeColorImage = image;
    }

    public static long getActiveColorImage() {
        return activeColorImage;
    }

    public static void captureFrameGenerationInputs(
            com.mojang.blaze3d.pipeline.RenderTarget renderTarget,
            com.mojang.blaze3d.pipeline.RenderTarget outputTarget,
            boolean uiWillBeProvided
    ) {
        Minecraft minecraft = Minecraft.getInstance();
        CompatibilityManager compatibility = FrameGenMod.compatibilityManager();
        if (minecraft == null || minecraft.level == null || compatibility == null
                || frameGenerationPipeline == null || renderTarget == null
                || outputTarget == null
                || !(renderTarget.getColorTextureView() instanceof
                        com.mojang.blaze3d.vulkan.VulkanGpuTextureView colorView)
                || !(renderTarget.getDepthTextureView() instanceof
                        com.mojang.blaze3d.vulkan.VulkanGpuTextureView depthView)
                || !(outputTarget.getColorTextureView() instanceof
                        com.mojang.blaze3d.vulkan.VulkanGpuTextureView outputView)
                || !(activeEncoder instanceof
                        org.malionaro.dlss_frame_generation.client.mixin.VulkanCommandEncoderAccessor
                        encoderAccess)) {
            return;
        }

        org.lwjgl.vulkan.VkCommandBuffer commandBuffer =
                encoderAccess.dlss_fg$getCurrentCommandBuffer();
        if (commandBuffer == null) return;

        long now = System.nanoTime();
        float frameTimeMs = previousCaptureNanos == 0
                ? 16.6667f
                : Math.max(1.0f, Math.min(250.0f,
                        (now - previousCaptureNanos) / 1_000_000.0f));
        previousCaptureNanos = now;

        FrameGenerationBackend backend = compatibility.activeVendorBackend();
        if (backend == null) return;
        FrameGenConfig config = FrameGenConfig.getInstance();
        boolean paused = minecraft.isPaused();
        if (paused) {
            try {
                StreamlineBridge.nSetFrameGenerationMode(
                        backend.vendor().vendorId(),
                        false,
                        backend.vendor()
                                == FrameGenerationBackend.GpuVendor.NVIDIA
                                ? config.frameMultiplier
                                : 2,
                        false
                );
            } catch (UnsatisfiedLinkError ignored) {
                // The normal unsupported-provider path remains fail-closed.
            }
        }
        long backbufferImage = 0;
        int backbufferFormat = 0;
        if (activeSurface instanceof
                org.malionaro.dlss_frame_generation.client.mixin.VulkanGpuSurfaceAccessor
                surfaceAccess) {
            int imageIndex = surfaceAccess.dlss_fg$getCurrentImageIndex();
            if (imageIndex >= 0
                    && imageIndex < surfaceAccess.dlss_fg$getSwapchainImages().size()) {
                backbufferImage = surfaceAccess.dlss_fg$getSwapchainImages()
                        .getLong(imageIndex);
                backbufferFormat = surfaceAccess.dlss_fg$getSwapchainImageFormat();
            }
        }
        boolean enabled;
        int multiplier;
        switch (backend.vendor()) {
            case NVIDIA -> {
                enabled = config.dlssFgEnabled && !paused;
                multiplier = config.frameMultiplier;
            }
            case AMD -> {
                enabled = config.amdFsrFgEnabled && !paused;
                multiplier = config.amdFsrMultiplier;
            }
            case INTEL -> {
                enabled = config.intelXessFgEnabled && !paused;
                multiplier = 2;
            }
            default -> {
                return;
            }
        }

        try {
            RayReconstructionResources rayReconstructionResources = null;
            if (FrameGenMod.rayTracingManager() != null) {
                rayReconstructionResources =
                        FrameGenMod.rayTracingManager().renderBeforeUpscale(
                        commandBuffer,
                        colorView,
                        depthView
                );
            }
            boolean debugReadbackRequested = FrameGenMod.motionVectorDebugRenderer() != null
                    && FrameGenMod.motionVectorDebugRenderer().getDebugMode()
                    != org.malionaro.dlss_frame_generation.client.motion.MotionVectorDebugRenderer.DebugMode.OFF;
            long acceptedBefore = frameGenerationPipeline.providerAcceptedFrameCount();
            activeMotionVectors = frameGenerationPipeline.captureAndDispatch(
                    commandBuffer,
                    colorView,
                    depthView,
                    outputView,
                    rayReconstructionResources,
                    activeResolution.mode(),
                    backend.vendor().vendorId(),
                    backbufferImage,
                    backbufferFormat,
                    viewToClipArr,
                    clipToViewArr,
                    clipToPrevClipArr,
                    prevClipToClipArr,
                    worldToViewArr,
                    viewToWorldArr,
                    jitterX,
                    jitterY,
                    previousJitterX,
                    previousJitterY,
                    resetForCurrentFrame || frameTimeMs > 100.0f,
                    frameTimeMs,
                    nearPlane,
                    farPlane,
                    (float) Math.toRadians(cameraFov),
                    cameraAspect,
                    camPosX, camPosY, camPosZ,
                    camUpX, camUpY, camUpZ,
                    camRightX, camRightY, camRightZ,
                    camFwdX, camFwdY, camFwdZ,
                    enabled,
                    multiplier,
                    config.uiRecomposition,
                    uiWillBeProvided,
                    debugReadbackRequested
            );
            activeMotionVectorSnapshot = frameGenerationPipeline.latestDebugSnapshot();
            motionVectorValidation = MOTION_VECTOR_VALIDATOR.validate(activeMotionVectors);
            boolean providerAccepted =
                    frameGenerationPipeline.providerAcceptedFrameCount() > acceptedBefore;
            if (lastProviderFrameAccepted == null
                    || lastProviderFrameAccepted != providerAccepted) {
                String providerStatus;
                try {
                    providerStatus = StreamlineBridge.nGetBackendStatus(
                            backend.vendor().vendorId()
                    );
                } catch (UnsatisfiedLinkError error) {
                    providerStatus = error.getMessage();
                }
                if (providerAccepted) {
                    LOGGER.info(
                            "Native provider accepted renderer-owned frame inputs: {}",
                            providerStatus
                    );
                } else {
                    LOGGER.warn(
                            "Native provider rejected renderer-owned frame inputs: {}",
                            providerStatus
                    );
                }
                lastProviderFrameAccepted = providerAccepted;
            }
        } catch (UnsatisfiedLinkError error) {
            LOGGER.warn("Native frame-generation input entry point unavailable: {}",
                    error.getMessage());
        }
    }

    public static void captureFrameGenerationUi(
            com.mojang.blaze3d.pipeline.RenderTarget uiTarget
    ) {
        CompatibilityManager compatibility = FrameGenMod.compatibilityManager();
        FrameGenerationBackend backend = compatibility != null
                ? compatibility.activeVendorBackend()
                : null;
        if (frameGenerationPipeline == null || uiTarget == null
                || backend == null
                || backend.vendor() != FrameGenerationBackend.GpuVendor.NVIDIA
                || !(uiTarget.getColorTextureView() instanceof
                        com.mojang.blaze3d.vulkan.VulkanGpuTextureView uiView)
                || !(activeEncoder instanceof
                        org.malionaro.dlss_frame_generation.client.mixin.VulkanCommandEncoderAccessor
                        encoderAccess)) {
            return;
        }
        org.lwjgl.vulkan.VkCommandBuffer commandBuffer =
                encoderAccess.dlss_fg$getCurrentCommandBuffer();
        if (commandBuffer == null) {
            return;
        }
        FrameGenConfig config = FrameGenConfig.getInstance();
        try {
            frameGenerationPipeline.captureUiAndFinalize(
                    commandBuffer,
                    uiView,
                    backend.vendor().vendorId(),
                    config.dlssFgEnabled,
                    config.frameMultiplier,
                    config.uiRecomposition
            );
        } catch (UnsatisfiedLinkError error) {
            LOGGER.warn(
                    "Native frame-generation UI entry point unavailable: {}",
                    error.getMessage()
            );
        }
    }

    public static MotionVectorResource getActiveMotionVectors() {
        return activeMotionVectors;
    }

    public static MotionVectorValidator.ValidationResult getMotionVectorValidation() {
        return motionVectorValidation;
    }

    public static MotionVectorSnapshot getActiveMotionVectorSnapshot() {
        return activeMotionVectorSnapshot;
    }

    public static String getFrameGenerationPipelineStatus() {
        return frameGenerationPipeline == null
                ? "Frame-generation pipeline is not initialized"
                : frameGenerationPipeline.status();
    }

    public static long getCapturedFrameCount() {
        return frameGenerationPipeline == null
                ? 0L : frameGenerationPipeline.capturedFrameCount();
    }

    public static long getProviderAcceptedFrameCount() {
        return frameGenerationPipeline == null
                ? 0L : frameGenerationPipeline.providerAcceptedFrameCount();
    }

    public static long getCompletedPresentCount() {
        return frameGenerationPipeline == null
                ? 0L : frameGenerationPipeline.completedPresentCount();
    }

    public static void shutdown() {
        if (frameGenerationPipeline != null) {
            frameGenerationPipeline.close();
            frameGenerationPipeline = null;
        }
        activeMotionVectors = null;
        activeMotionVectorSnapshot = null;
        motionVectorValidation = null;
        lastProviderFrameAccepted = null;
    }
}
