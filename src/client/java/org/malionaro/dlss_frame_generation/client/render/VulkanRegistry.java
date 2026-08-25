package org.malionaro.dlss_frame_generation.client.render;
import org.malionaro.dlss_frame_generation.client.backend.FrameGenerationBackend;
import org.malionaro.dlss_frame_generation.client.backend.FrameGenerationCompatibilityLayer;
import org.malionaro.dlss_frame_generation.client.backend.ProviderLifecycle;
import org.malionaro.dlss_frame_generation.client.compat.CompatibilityManager;
import org.malionaro.dlss_frame_generation.client.compat.RendererDetection;
import org.malionaro.dlss_frame_generation.client.config.FrameGenConfig;
import org.malionaro.dlss_frame_generation.client.frame.FrameGuideConventions;
import org.malionaro.dlss_frame_generation.client.frame.FrameIdentity;
import org.malionaro.dlss_frame_generation.client.frame.FrameResourceSet;
import org.malionaro.dlss_frame_generation.client.FrameGenMod;
import org.malionaro.dlss_frame_generation.client.interop.D3D12PresentationBridge;
import org.malionaro.dlss_frame_generation.client.interop.NativeLoader;
import org.malionaro.dlss_frame_generation.client.interop.OptiScalerIntegration;
import org.malionaro.dlss_frame_generation.client.render.adapter.RendererBackendAdapter;
import org.malionaro.dlss_frame_generation.client.StreamlineBridge;

import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanGpuSurface;
import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.GameRenderState;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.vulkan.VK13;
import org.lwjgl.vulkan.VkDevice;
import org.malionaro.dlss_frame_generation.client.access.VulkanDeviceAccess;
import org.malionaro.dlss_frame_generation.client.motion.MotionVectorResource;
import org.malionaro.dlss_frame_generation.client.motion.MotionVectorSnapshot;
import org.malionaro.dlss_frame_generation.client.motion.MotionVectorValidator;
import org.malionaro.dlss_frame_generation.client.motion.VulkanFrameGenerationPipeline;
import org.malionaro.dlss_frame_generation.client.raytracing.RayReconstructionResources;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class VulkanRegistry {
    private static final Logger LOGGER = LoggerFactory.getLogger("dlss_fg.registry");
    private static volatile VulkanDevice activeDevice;
    private static volatile VulkanGpuSurface activeSurface;
    private static volatile VulkanCommandEncoder activeEncoder;
    private static volatile VulkanFrameGenerationPipeline frameGenerationPipeline;
    private static long swapchainGeneration;
    private static long currentFrameSerial;
    private static int physicalDeviceVendorId;
    private static volatile long activePhysicalDeviceHandle;
    private static String physicalDeviceName = "No Vulkan device";
    private static int physicalDeviceApiVersion;
    private static java.util.Set<String> enabledDeviceExtensions =
            java.util.Set.of();
    private static FrameResourceSet currentFrameResources;
    private static FrameIdentity lastAcceptedFrameIdentity =
            FrameIdentity.INVALID;
    private static FrameIdentity lastCapturedFrameIdentity =
            FrameIdentity.INVALID;
    private static String lastFrameResourceRejection =
            "No renderer frame has been captured";
    private static MotionVectorResource activeMotionVectors;
    private static MotionVectorSnapshot activeMotionVectorSnapshot;
    private static MotionVectorValidator.ValidationResult motionVectorValidation;
    private static final MotionVectorValidator MOTION_VECTOR_VALIDATOR =
            new MotionVectorValidator();
    private static long previousCaptureNanos;
    private static Boolean lastProviderFrameAccepted;
    private static NvidiaDlssRenderResolution.Resolution activeResolution =
            new NvidiaDlssRenderResolution.Resolution(1, 1, 1, 1, 0, false);
    private static final DynamicResolutionController DYNAMIC_RESOLUTION =
            new DynamicResolutionController();

    public static void registerDevice(VulkanDevice device) {
        VulkanFrameGenerationPipeline previous = frameGenerationPipeline;
        if (previous != null) {
            try {
                previous.close();
            } catch (RuntimeException e) {
                LOGGER.warn(
                        "Failed to release previous frame-generation pipeline before device registration",
                        e
                );
            }
        }
        activeDevice = device;
        LOGGER.info("Registered active VulkanDevice context: {}", device);
        frameGenerationPipeline = new VulkanFrameGenerationPipeline(device, LOGGER);

        CompatibilityManager comp = FrameGenMod.compatibilityManager();
        if (comp != null) {
            comp.activateVulkanAdapter(device);
        }

        if (device instanceof VulkanDeviceAccess access
                && access.dlss_fg$getPhysicalDevice() != null) {
            var properties = access.dlss_fg$getPhysicalDevice()
                    .vkPhysicalDeviceProperties();
            physicalDeviceVendorId = properties.vendorID();
            physicalDeviceName = properties.deviceNameString();
            physicalDeviceApiVersion = properties.apiVersion();
            enabledDeviceExtensions =
                    java.util.Set.copyOf(access.dlss_fg$getEnabledExtensions());
        } else {
            physicalDeviceVendorId = 0;
            physicalDeviceName = "Vulkan device metadata unavailable";
            physicalDeviceApiVersion = 0;
            enabledDeviceExtensions = java.util.Set.of();
        }

        if (FrameGenMod.rayTracingManager() != null
                && (comp == null
                    || comp.rendererDetection().allowsVulkanFeatures())) {
            FrameGenMod.rayTracingManager().onDeviceRegistered(device);
        }
        
        // Dynamic initialization of the selected vendor-neutral native provider.
        if (comp != null) {
            RendererBackendAdapter adapter = comp.adapter();
            if (adapter != null) {
                long instance = adapter.getVulkanInstance();
                long physicalDevice = adapter.getPhysicalDevice();
                activePhysicalDeviceHandle = physicalDevice;
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
                if (!comp.rendererDetection().allowsVulkanFeatures()) {
                    LOGGER.warn(
                            "Vulkan FG/RT initialization blocked: {}",
                            comp.rendererDetection().blockingReason()
                    );
                    return;
                }
                FrameGenerationCompatibilityLayer.ProviderId provider =
                        switch (detectedVendor) {
                            case NVIDIA ->
                                    FrameGenerationCompatibilityLayer.ProviderId
                                            .NVIDIA_STREAMLINE_VULKAN;
                            case AMD ->
                                    FrameGenerationCompatibilityLayer.ProviderId
                                            .AMD_FSR31_VULKAN;
                            case INTEL ->
                                    FrameGenerationCompatibilityLayer.ProviderId
                                            .CROSS_VENDOR_FSR31_VULKAN;
                            case UNKNOWN ->
                                    FrameGenerationCompatibilityLayer.ProviderId
                                            .NONE;
                        };
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
                    comp.beginProviderInitialization(
                            provider,
                            "Detected " + provider.displayName()
                    );
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
                        D3D12PresentationBridge.Status d3d12Status =
                                D3D12PresentationBridge.probe(
                                        physicalDevice,
                                        logicalDevice,
                                        queue,
                                        queueFamily
                                );
                        LOGGER.info(
                                "Hybrid D3D12 bootstrap: stage={}, capabilities=0x{}, LUID={}, detail={}",
                                d3d12Status.stage(),
                                Integer.toHexString(
                                        d3d12Status.capabilities()),
                                d3d12Status.adapterLuid(),
                                d3d12Status.detail()
                        );
                        comp.completeProviderInitialization(
                                success,
                                StreamlineBridge.nGetBackendStatus(vendorId)
                        );
                        if (success) {
                            int capabilities =
                                    StreamlineBridge.nGetBackendCapabilities(vendorId);
                            int supportedCapabilities =
                                    vendorId == FrameGenerationBackend.GpuVendor.NVIDIA.vendorId()
                                            ? StreamlineBridge.nGetBackendSupportedCapabilities(vendorId)
                                            : capabilities;
                            FrameGenConfig config = FrameGenConfig.getInstance();
                            RuntimeFallbackPolicy.Decision fallback =
                                    fallbackDecision(
                                            config,
                                            detectedVendor,
                                            supportedCapabilities
                                    );
                            if (fallback.fallbackActive()) {
                                LOGGER.warn(
                                        "Safe provider fallback active: {}",
                                        fallback.detail()
                                );
                            }
                            // D1: single native config path – all mode-setter calls must go through backend.applyConfig(config)
                            // Direct native setters here would be a duplicate path and are removed.
                            FrameGenerationBackend initBackend = comp.activeVendorBackend();
                            if (initBackend != null) {
                                initBackend.applyConfig(config);
                                LOGGER.info("Initial backend config applied via D1 applyConfig path: vendor={}, fallbackMode={}, supportedCaps=0x{}",
                                        initBackend.vendor(), fallback.upscalerMode(), Integer.toHexString(supportedCapabilities));
                            } else {
                                LOGGER.warn("No active vendor backend for initial config; skipping D1 applyConfig");
                            }
                            if ((capabilities & StreamlineBridge.CAP_FRAME_GENERATION) == 0) {
                                LOGGER.info(
                                        "Frame Generation remains disabled until the selected route reports it ready: {}",
                                        StreamlineBridge.nGetBackendStatus(vendorId)
                                );
                            }
                        }
                    } catch (UnsatisfiedLinkError e) {
                        comp.failProvider(
                                "Native initialization entry point unavailable: "
                                        + e.getMessage()
                        );
                        LOGGER.error("Failed to call native nInit (bridge not loaded yet): {}", e.getMessage());
                    }
                } else {
                    comp.detectProvider(
                            provider,
                            "Detected " + provider.displayName()
                                    + "; waiting for complete Vulkan context"
                    );
                    LOGGER.warn("Vulkan context incomplete; postponing native JNI initialization");
                }
            }
        }
    }

    public static VulkanDevice getActiveDevice() {
        return activeDevice;
    }

    private static volatile Boolean strictSyncEnabled =
            Boolean.getBoolean("dlss_fg.strictSync");

    public static boolean isStrictSyncEnabled() {
        return strictSyncEnabled;
    }

    /**
     * Bounded GPU-idle gate ahead of present-serial bookkeeping.
     *
     * <p>Returns {@code true} when GPU idleness was confirmed (or trivially,
     * when no Vulkan device is active), {@code false} when the caller must
     * fall back to a short spin because no wait was performed.</p>
     *
     * <p>WIP: a genuinely brief bounded wait needs a tracked VkFence or a
     * timeline semaphore exported from Minecraft's present submission
     * (vkWaitSemaphores); neither handle is plumbed through the surface yet.
     * Until then the only correct idle primitive is vkDeviceWaitIdle, which
     * blocks the render thread for the full queue depth and is therefore
     * opt-in via {@code -Ddlss_fg.strictSync=true}; the {@code timeoutMs}
     * parameter is reserved for that future fence/timeline wait.</p>
     */
    public static boolean waitUntilGpuIdleBrief(long timeoutMs) {
        VulkanDevice device = activeDevice;
        if (device == null) {
            return true;
        }
        try {
            VkDevice vkDevice = device.vkDevice();
            if (vkDevice == null || vkDevice.address() == 0L) {
                return true;
            }
            if (!strictSyncEnabled) {
                // Default: do not stall the render thread; callers spin briefly instead.
                return false;
            }
            int result = VK13.vkDeviceWaitIdle(vkDevice);
            if (result != VK13.VK_SUCCESS) {
                LOGGER.warn("vkDeviceWaitIdle failed with VkResult {}", result);
                return false;
            }
            return true;
        } catch (Throwable t) {
            LOGGER.warn("waitUntilGpuIdleBrief failed: {}", t.toString());
            return false;
        }
    }

    public static void registerSurface(VulkanGpuSurface surface) {
        activeSurface = surface;
        currentFrameResources = null;
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
        currentFrameResources = null;
        lastFrameResourceRejection =
                "Swapchain reconfiguration is in progress";
        CompatibilityManager comp = FrameGenMod.compatibilityManager();
        if (comp != null) {
            comp.beginProviderDrain(swapchainGeneration);
        }
        try {
            StreamlineBridge.nBeginProviderDrain();
        } catch (UnsatisfiedLinkError error) {
            LOGGER.warn(
                    "Native provider drain entry point unavailable: {}",
                    error.getMessage()
            );
        }
        if (comp == null || comp.activeVendorBackend() == null) {
            return;
        }
        // D1: lifecycle pre-drain disable must not be a direct native setter in VulkanRegistry
        // Delegate to backend so the single config path remains via applyConfig/lifecycle helper
        try {
            // Only NVIDIA needs explicit DLSS-G disable before resize; other vendors no-op
            comp.activeVendorBackend().disableForSwapchainReconfiguration();
        } catch (UnsatisfiedLinkError error) {
            LOGGER.warn(
                    "Could not disable frame generation before swapchain reconfiguration: {}",
                    error.getMessage()
            );
        } catch (Throwable t) {
            LOGGER.warn("Backend disableForSwapchainReconfiguration failed: {}", t.getMessage());
        }
    }

    public static void notifySwapchainRecreated() {
        swapchainGeneration++;
        currentFrameResources = null;
        lastAcceptedFrameIdentity = FrameIdentity.INVALID;
        lastCapturedFrameIdentity = FrameIdentity.INVALID;
        lastFrameResourceRejection =
                "Waiting for the first frame of swapchain generation "
                        + swapchainGeneration;
        if (frameGenerationPipeline != null) {
            frameGenerationPipeline.onSwapchainGenerationChanged(
                    swapchainGeneration
            );
        }
        CompatibilityManager comp = FrameGenMod.compatibilityManager();
        if (comp != null) {
            RendererBackendAdapter adapter = comp.adapter();
            if (adapter != null && adapter.getSwapchain() != 0) {
                if (D3D12PresentationBridge.ownsSwapchain(
                        adapter.getSwapchain())) {
                    LOGGER.info(
                            "DXGI owns swapchain generation {}; "
                                    + "skipping Vulkan provider swapchain notification",
                            swapchainGeneration
                    );
                    return;
                }
                comp.completeProviderSwapchainRebuild(
                        swapchainGeneration);
                LOGGER.info("Notifying JNI bridge of Vulkan Swapchain recreation: target={}, dim={}x{}",
                        Long.toHexString(adapter.getSwapchain()), adapter.getRenderWidth(), adapter.getRenderHeight());
                try {
                    StreamlineBridge.nRecreateSwapchain(
                        adapter.getSwapchain(),
                        adapter.getRenderWidth(),
                        adapter.getRenderHeight()
                    );
                    // D1: restore uses backend lifecycle helper, not direct nSetFgMode, to keep single config path
                    FrameGenerationBackend backend = comp.activeVendorBackend();
                    if (backend != null) {
                        backend.restoreAfterSwapchainRecreation(FrameGenConfig.getInstance());
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
    private static volatile FrameGuideConventions lastGuideConventions;

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
        CompatibilityManager compatibility = FrameGenMod.compatibilityManager();
        FrameGenerationBackend backend = compatibility != null
                ? compatibility.activeVendorBackend()
                : null;
        FrameGenerationBackend.GpuVendor vendor = backend != null
                ? backend.vendor()
                : FrameGenerationBackend.GpuVendor.fromVendorId(
                        physicalDeviceVendorId
                );
        int supportedCapabilities = supportedCapabilities(vendor);
        boolean upscalingSupported = (supportedCapabilities
                & StreamlineBridge.CAP_UPSCALING) != 0;
        RuntimeFallbackPolicy.Decision fallback = fallbackDecision(
                config,
                vendor,
                supportedCapabilities
        );
        activeResolution = switch (vendor) {
            case NVIDIA -> NvidiaDlssRenderResolution.resolve(
                    outputWidth,
                    outputHeight,
                    fallback.upscalerMode(),
                    upscalingSupported,
                    config.dlssRayReconstructionEnabled,
                    (supportedCapabilities
                            & StreamlineBridge.CAP_RAY_RECONSTRUCTION) != 0
            );
            case AMD -> NvidiaDlssRenderResolution.resolveTemporal(
                    outputWidth,
                    outputHeight,
                    config.amdFsrMode,
                    upscalingSupported
            );
            case INTEL -> NvidiaDlssRenderResolution.resolveTemporal(
                    outputWidth,
                    outputHeight,
                    config.intelXessMode,
                    upscalingSupported
            );
            case UNKNOWN -> NvidiaDlssRenderResolution.resolveTemporal(
                    outputWidth, outputHeight, 0, false
            );
        };
        activeResolution = DYNAMIC_RESOLUTION.apply(
                activeResolution,
                config.drsEnabled && upscalingSupported
        );
        return activeResolution;
    }

    private static RuntimeFallbackPolicy.Decision fallbackDecision(
            FrameGenConfig config,
            FrameGenerationBackend.GpuVendor vendor,
            int supportedCapabilities
    ) {
        int requestedMode = switch (vendor) {
            case NVIDIA -> config.dlssUpscalingMode;
            case AMD -> config.amdFsrMode;
            case INTEL -> config.intelXessMode;
            case UNKNOWN -> 0;
        };
        CompatibilityManager compatibility = FrameGenMod.compatibilityManager();
        ProviderLifecycle.State providerState = compatibility != null
                ? compatibility.providerSnapshot().state()
                : ProviderLifecycle.State.UNAVAILABLE;
        return RuntimeFallbackPolicy.resolve(
                vendor,
                config.autoFallbackChainEnabled,
                config.isFrameGenerationEnabled(vendor),
                requestedMode,
                supportedCapabilities,
                providerState
        );
    }

    private static int supportedCapabilities(
            FrameGenerationBackend.GpuVendor vendor
    ) {
        if (vendor == FrameGenerationBackend.GpuVendor.UNKNOWN) {
            return 0;
        }
        try {
            return vendor == FrameGenerationBackend.GpuVendor.NVIDIA
                    ? StreamlineBridge.nGetBackendSupportedCapabilities(
                            vendor.vendorId())
                    : StreamlineBridge.nGetBackendCapabilities(
                            vendor.vendorId());
        } catch (UnsatisfiedLinkError ignored) {
            return 0;
        }
    }

    public static float dynamicResolutionScale() {
        return DYNAMIC_RESOLUTION.scale();
    }

    public static float dynamicResolutionFrameTimeMs() {
        return DYNAMIC_RESOLUTION.smoothedFrameTimeMs();
    }

    public static RuntimeFallbackPolicy.Decision runtimeFallbackDecision() {
        FrameGenConfig config = FrameGenConfig.getInstance();
        CompatibilityManager compatibility = FrameGenMod.compatibilityManager();
        FrameGenerationBackend backend = compatibility != null
                ? compatibility.activeVendorBackend()
                : null;
        FrameGenerationBackend.GpuVendor vendor = backend != null
                ? backend.vendor()
                : FrameGenerationBackend.GpuVendor.fromVendorId(
                        physicalDeviceVendorId
                );
        return fallbackDecision(
                config,
                vendor,
                supportedCapabilities(vendor)
        );
    }

    public static void beginWorldFrame(GameRenderState gameRenderState) {
        currentFrameSerial++;
        currentFrameResources = null;
        updateCameraState(gameRenderState);
        // H2: jitter must only be reported when projection is actually mutated; otherwise keep 0
        if (!FrameGenConfig.getInstance().projectionJitterEnabled
                || activeResolution.mode() == 0) {
            previousJitterX = jitterX;
            previousJitterY = jitterY;
            jitterX = 0.0f;
            jitterY = 0.0f;
            return;
        }
        updateSubPixelJitter(
                activeResolution.renderWidth(),
                activeResolution.renderHeight()
        );

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
        CompatibilityManager compatibility = FrameGenMod.compatibilityManager();
        FrameGenerationBackend backend = compatibility != null
                ? compatibility.activeVendorBackend()
                : null;
        if (backend == null || !config.isFrameGenerationEnabled(backend.vendor()) || minecraft == null || minecraft.isPaused()) {
            return false;
        }
        return config.uiRecomposition && vendorFeatureSupported(backend.vendor(), StreamlineBridge.CAP_FRAME_GENERATION);
    }

    private static boolean vendorFeatureSupported(FrameGenerationBackend.GpuVendor vendor, int capability) {
        if (vendor == null || vendor == FrameGenerationBackend.GpuVendor.UNKNOWN) {
            return false;
        }
        try {
            return (StreamlineBridge.nGetBackendSupportedCapabilities(
                    vendor.vendorId()
            ) & capability) != 0;
        } catch (UnsatisfiedLinkError ignored) {
            return false;
        }
    }

    public static void onPresent() {
        // Feature 3: Auto-Reflex Latency Pacing
        ReflexPacer.paceFrame();

        // Feature: Dynamic VRAM Budget Optimizer
        org.malionaro.dlss_frame_generation.client.vulkan.VramBudgetOptimizer.checkVramPressure(
                activePhysicalDeviceHandle);

        // Provider work was recorded at the pre-HUD capture point. Streamline
        // and FidelityFX insert generated frames from their present hooks.
    }

    public static void onPresentCompleted() {
        if (frameGenerationPipeline != null) {
            frameGenerationPipeline.onPresentCompleted();
        }
        currentFrameResources = null;
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
        if (commandBuffer == null) {
            rejectFrameResources("No command buffer at the pre-HUD boundary");
            return;
        }

        FrameGenConfig config = FrameGenConfig.getInstance();
        long now = System.nanoTime();
        float frameTimeMs = previousCaptureNanos == 0
                ? 16.6667f
                : Math.max(1.0f, Math.min(250.0f,
                        (now - previousCaptureNanos) / 1_000_000.0f));
        previousCaptureNanos = now;
        DYNAMIC_RESOLUTION.recordFrameTime(
                frameTimeMs,
                config.targetFps,
                config.drsEnabled && activeResolution.mode() > 0
        );

        FrameGenerationBackend backend = compatibility.activeVendorBackend();
        if (backend == null) return;
        if (!compatibility.rendererDetection().allowsVulkanFeatures()) {
            rejectFrameResources(
                    compatibility.rendererDetection().blockingReason()
            );
            return;
        }
        boolean paused = minecraft.isPaused();
        if (paused) {
            try {
                // D1: lifecycle – delegate to backend so VulkanRegistry has no direct nSet* path
                backend.disableForPause(config);
            } catch (UnsatisfiedLinkError ignored) {
                // The normal unsupported-provider path remains fail-closed.
            } catch (Throwable t) {
                // Defensive: backend lifecycle should never crash frame capture
            }
        }
        RendererBackendAdapter adapter = compatibility.adapter();
        int imageIndex = adapter != null
                ? adapter.getCurrentFrameIndex()
                : -1;
        FrameIdentity identity = new FrameIdentity(
                currentFrameSerial,
                swapchainGeneration,
                imageIndex
        );
        FrameResourceSet resources = adapter == null
                ? null
                : adapter.captureFrameResources(
                        renderTarget, outputTarget, identity
                ).orElse(null);
        if (resources == null) {
            rejectFrameResources(
                    "Renderer adapter could not create an atomic frame snapshot"
            );
            return;
        }
        FrameResourceSet.Validation resourceValidation = resources.validate();
        if (!resourceValidation.accepted()) {
            rejectFrameResources(resourceValidation.reason());
            return;
        }
        FrameGuideConventions guideConventions =
                new FrameGuideConventions(
                        FrameGuideConventions.DepthConvention
                                .REVERSED_ZERO_TO_ONE,
                        FrameGuideConventions.MotionDirection
                                .CURRENT_PIXEL_TO_PREVIOUS_PIXEL,
                        FrameGuideConventions.MotionUnits.RENDER_PIXELS,
                        FrameGuideConventions.AxisDirection.DOWN,
                        true,
                        FrameGuideConventions.ObjectMotionSource
                                .CAMERA_REPROJECTION_WITH_TEMPORAL_IMAGE_ESTIMATE,
                        nearPlane,
                        farPlane,
                        renderTarget.width,
                        renderTarget.height,
                        outputTarget.width,
                        outputTarget.height
                );
        FrameGuideConventions.Validation guideValidation =
                guideConventions.validate();
        if (!guideValidation.accepted()) {
            rejectFrameResources(guideValidation.reason());
            return;
        }
        currentFrameResources = resources;
        lastCapturedFrameIdentity = resources.identity();
        lastFrameResourceRejection = "";
        lastGuideConventions = guideConventions;
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
        RuntimeFallbackPolicy.Decision fallback = fallbackDecision(
                config,
                backend.vendor(),
                supportedCapabilities(backend.vendor())
        );
        enabled = enabled && fallback.frameGenerationEnabled();

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
                    resources,
                    commandBuffer,
                    colorView,
                    depthView,
                    outputView,
                    rayReconstructionResources,
                    activeResolution.mode(),
                    backend.vendor().vendorId(),
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
                    debugReadbackRequested,
                    config.motionBlurEnabled,
                    config.motionBlurIntensity,
                    config.motionBlurSamples
            );
            activeMotionVectorSnapshot = frameGenerationPipeline.latestDebugSnapshot();
            motionVectorValidation = MOTION_VECTOR_VALIDATOR.validate(activeMotionVectors);
            boolean providerAccepted =
                    frameGenerationPipeline.providerAcceptedFrameCount() > acceptedBefore;
            if (providerAccepted) {
                lastAcceptedFrameIdentity = resources.identity();
                lastFrameResourceRejection = "";
                compatibility.markProviderFrameAccepted(
                        resources.identity(),
                        "Native provider accepted frame "
                                + resources.identity().frameSerial()
                );
            }
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
        } catch (Throwable error) {
            LOGGER.warn("Native frame-generation input submission failed: {}",
                    error.getMessage());
        }
    }

    private static void rejectFrameResources(String reason) {
        currentFrameResources = null;
        if (!reason.equals(lastFrameResourceRejection)) {
            LOGGER.warn("Skipping unsafe frame-generation capture: {}", reason);
        }
        lastFrameResourceRejection = reason;
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
                || currentFrameResources == null
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
        // H3: vendor-aware UI finalize - mirror captureInputs vendor switch, not hardcoded NVIDIA
        boolean uiEnabled;
        int uiMultiplier;
        switch (backend.vendor()) {
            case NVIDIA -> {
                uiEnabled = config.dlssFgEnabled;
                uiMultiplier = config.frameMultiplier;
            }
            case AMD -> {
                uiEnabled = config.amdFsrFgEnabled;
                uiMultiplier = config.amdFsrMultiplier;
            }
            case INTEL -> {
                uiEnabled = config.intelXessFgEnabled;
                uiMultiplier = 2;
            }
            default -> {
                return;
            }
        }
        try {
            frameGenerationPipeline.captureUiAndFinalize(
                    currentFrameResources.identity(),
                    commandBuffer,
                    uiView,
                    backend.vendor().vendorId(),
                    uiEnabled,
                    uiMultiplier,
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

    public static long getSwapchainGeneration() {
        return swapchainGeneration;
    }

    public static int getPhysicalDeviceVendorId() {
        return physicalDeviceVendorId;
    }

    public static String getPhysicalDeviceName() {
        return physicalDeviceName;
    }

    public static int getPhysicalDeviceApiVersion() {
        return physicalDeviceApiVersion;
    }

    public static java.util.Set<String> getEnabledDeviceExtensions() {
        return enabledDeviceExtensions;
    }

    public static FrameIdentity getLastAcceptedFrameIdentity() {
        return lastAcceptedFrameIdentity;
    }

    public static FrameGuideConventions getLastGuideConventions() {
        return lastGuideConventions;
    }

    public static FrameIdentity getLastCapturedFrameIdentity() {
        return lastCapturedFrameIdentity;
    }

    public static String getLastFrameResourceRejection() {
        return lastFrameResourceRejection;
    }

    public static FrameResourceSet getCurrentFrameResources() {
        return currentFrameResources;
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
        currentFrameResources = null;
        lastAcceptedFrameIdentity = FrameIdentity.INVALID;
        lastCapturedFrameIdentity = FrameIdentity.INVALID;
        lastGuideConventions = null;
        lastFrameResourceRejection = "Renderer has shut down";
        physicalDeviceVendorId = 0;
        activePhysicalDeviceHandle = 0;
        physicalDeviceName = "No Vulkan device";
        physicalDeviceApiVersion = 0;
        enabledDeviceExtensions = java.util.Set.of();
        CompatibilityManager compatibility =
                FrameGenMod.compatibilityManager();
        if (compatibility != null) {
            compatibility.shutdownProvider();
        }
    }
}
