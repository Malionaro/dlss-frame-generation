package org.malionaro.dlss_frame_generation.client;
import org.malionaro.dlss_frame_generation.client.backend.ProviderLifecycle;

/**
 * JNI Bridge to interface with the native NVIDIA Streamline C++ SDK library.
 */
public final class StreamlineBridge {
    public static final int CAP_UPSCALING = 1;
    public static final int CAP_FRAME_GENERATION = 1 << 1;
    public static final int CAP_MULTI_FRAME_GENERATION = 1 << 2;
    public static final int CAP_LOW_LATENCY = 1 << 3;
    public static final int CAP_PROXY_SWAPCHAIN = 1 << 4;
    public static final int CAP_RAY_RECONSTRUCTION = 1 << 5;
    public static final int NATIVE_ABI_VERSION = 2;
    public static final int NATIVE_NOT_HANDLED = Integer.MIN_VALUE;
    public static final int VULKAN_REQUIREMENT_INSTANCE_EXTENSIONS = 0;
    public static final int VULKAN_REQUIREMENT_DEVICE_EXTENSIONS = 1;
    public static final int VULKAN_REQUIREMENT_FEATURES_12 = 2;
    public static final int VULKAN_REQUIREMENT_FEATURES_13 = 3;

    private StreamlineBridge() {
    }

    /**
     * ABI handshake: returns {@link #NATIVE_ABI_VERSION} of the loaded DLL.
     * Missing on stale DLLs, which lets the loader fail closed.
     */
    public static native int nGetNativeAbiVersion();

    /**
     * Initializes Streamline before Vulkan creates an instance or device.
     * Calling this late prevents the Vulkan interposer from observing the
     * swapchain lifecycle required by DLSS Frame Generation.
     */
    public static native boolean nInitCore(String pluginsPath);

    /**
     * Redirects Streamline's own log to a persistent directory.
     *
     * <p>Must be called before {@link #nInitCore}. The plugins path is a temp
     * directory that is deleted when the JVM exits, so a log written there
     * cannot be inspected after a failure — and the reason a feature is
     * rejected is exactly what is missing from our own log.
     */
    public static native void nSetLogsPath(String logsPath);

    /**
     * Initializes the Streamline SDK with Vulkan instance, device, and graphics queue handles.
     */
    public static native boolean nInit(
        long vkInstance,
        long vkPhysicalDevice,
        long vkDevice,
        long vkQueue,
        int queueFamilyIndex,
        int vendorId,
        String pluginsPath
    );

    /**
     * Returns a bit mask composed from the CAP_* constants above.
     */
    public static native int nGetBackendCapabilities(int vendorId);

    /**
     * Returns feature support without requiring per-frame renderer inputs.
     * This is used by settings screens; nGetBackendCapabilities remains the
     * fail-closed runtime-readiness query.
     */
    public static native int nGetBackendSupportedCapabilities(int vendorId);

    /**
     * Returns Streamline's Vulkan requirements after nInitCore and before
     * Minecraft creates its Vulkan instance/device.
     */
    public static native String[] nGetVulkanRequirements(int kind);

    /**
     * Returns the maximum supported presented-frame multiplier (2 means 2x).
     */
    public static native int nGetMaxFrameMultiplier(int vendorId);

    /**
     * Human-readable native status for diagnostics and the settings screen.
     */
    public static native String nGetBackendStatus(int vendorId);

    /**
     * Shuts down Streamline and releases resources.
     */
    public static native void nShutdown();

    /**
     * Informs the SDK that the Vulkan swapchain has been created or resized.
     */
    public static native void nRecreateSwapchain(long swapchain, int width, int height);

    /**
     * Checks if Frame Generation has been successfully initialized and is active.
     */
    public static native boolean nIsActive();

    /**
     * Configures the active NVIDIA Reflex mode (0 = Off, 1 = Low Latency, 2 = Low Latency + Boost).
     */
    public static native void nSetReflexMode(int mode, int targetFps);

    /**
     * Places a low-latency marker at critical stages of the main game/render loop.
     */
    public static native void nReflexMarker(int markerType);

    /**
     * Configures the active NVIDIA DLSS Upscaling (Super Resolution) mode and sharpness level.
     */
    public static native void nSetDlssMode(int mode, float sharpness);

    /**
     * Returns Streamline's optimal input dimensions for the selected DLSS
     * mode and output size. An empty array means the queried mode is not
     * available and the renderer must stay at display resolution.
     */
    public static native int[] nGetOptimalRenderSize(
        int mode, int outputWidth, int outputHeight, boolean rayReconstruction
    );

    /**
     * Toggles and configures native DLSS Frame Generation parameters.
     */
    public static native void nSetFgMode(boolean enabled, int multiplier, boolean uiRecomposition);

    /**
     * Retrieves the estimated VRAM usage by DLSS Frame Generation in bytes.
     */
    public static native long nGetDlssgVramUsage();

    /**
     * Configures upscaling mode for a specific GPU vendor ID (0x10DE = NVIDIA, 0x1002 = AMD, 0x8086 = Intel).
     */
    public static native void nSetUpscalingMode(int vendorId, int mode, float sharpness);

    /**
     * Records the vendor-native Vulkan temporal-upscaling dispatch.
     *
     * <p>NVIDIA uses Streamline/DLSS. AMD and Intel use the FidelityFX Vulkan
     * input route (which an explicitly enabled OptiScaler installation may
     * replace).</p>
     */
    public static native boolean nSubmitUpscalingInputs(
        int vendorId,
        long commandBuffer,
        long colorImage, long colorMemory, long colorView, int colorFormat,
        long depthImage, long depthMemory, long depthView, int depthFormat,
        long motionImage, long motionMemory, long motionView, int motionFormat,
        long outputImage, long outputMemory, long outputView, int outputFormat,
        int renderWidth, int renderHeight,
        int outputWidth, int outputHeight,
        float[] viewToClip,
        float[] clipToView,
        float[] clipToPreviousClip,
        float[] previousClipToClip,
        float frameTimeMs,
        float cameraNear, float cameraFar, float verticalFov, float aspect,
        float jitterX, float jitterY,
        float cameraX, float cameraY, float cameraZ,
        float cameraUpX, float cameraUpY, float cameraUpZ,
        float cameraRightX, float cameraRightY, float cameraRightZ,
        float cameraForwardX, float cameraForwardY, float cameraForwardZ,
        boolean reset
    );

    /**
     * Evaluates DLSS Ray Reconstruction with the low-resolution HDR lighting
     * and material guide buffers produced by the hardware RT pass.
     */
    public static native boolean nSubmitRayReconstructionInputs(
        long commandBuffer,
        long noisyImage, long noisyMemory, long noisyView, int noisyFormat,
        long albedoImage, long albedoMemory, long albedoView, int albedoFormat,
        long specularImage, long specularMemory, long specularView, int specularFormat,
        long normalRoughnessImage, long normalRoughnessMemory,
        long normalRoughnessView, int normalRoughnessFormat,
        long specularHitDistanceImage, long specularHitDistanceMemory,
        long specularHitDistanceView, int specularHitDistanceFormat,
        long depthImage, long depthMemory, long depthView, int depthFormat,
        long motionImage, long motionMemory, long motionView, int motionFormat,
        long outputImage, long outputMemory, long outputView, int outputFormat,
        int renderWidth, int renderHeight,
        int outputWidth, int outputHeight,
        int dlssMode,
        float[] viewToClip,
        float[] clipToView,
        float[] clipToPreviousClip,
        float[] previousClipToClip,
        float[] worldToView,
        float[] viewToWorld,
        float frameTimeMs,
        float cameraNear, float cameraFar, float verticalFov, float aspect,
        float jitterX, float jitterY,
        float cameraX, float cameraY, float cameraZ,
        float cameraUpX, float cameraUpY, float cameraUpZ,
        float cameraRightX, float cameraRightY, float cameraRightZ,
        float cameraForwardX, float cameraForwardY, float cameraForwardZ,
        boolean reset
    );

    /**
     * Toggles and configures frame generation for a specific GPU vendor ID.
     */
    public static native void nSetFrameGenerationMode(int vendorId, boolean enabled, int multiplier, boolean uiRecomposition);

    /**
     * Configures low latency mode for a specific GPU vendor ID.
     */
    public static native void nSetReflexOrLowLatencyMode(int vendorId, int mode);

    /**
     * Retrieves estimated VRAM usage for a specific GPU vendor ID.
     */
    public static native long nGetVramUsage(int vendorId);

    /**
     * Queries the real VRAM budget via VK_EXT_memory_budget. On success the
     * budget and usage of the largest device-local heap are written into the
     * first element of each array and true is returned.
     */
    public static native boolean nQueryMemoryBudget(
        long vkPhysicalDevice,
        long[] outBudgetBytes,
        long[] outUsageBytes
    );

    /**
     * Active provider selected by the native compatibility layer.
     */
    public static native int nGetActiveProviderId();

    public static native String nGetActiveProviderName();

    /**
     * Native provider state using ProviderLifecycle.State ordinal values.
     */
    public static native int nGetProviderLifecycleState();

    public static native String nGetProviderLifecycleDetail();

    public static native void nBeginProviderDrain();

    public static native boolean nProbeD3D12Presentation(
        long vkPhysicalDevice,
        long vkDevice,
        long vkGraphicsQueue,
        int graphicsQueueFamily
    );

    public static native int nGetD3D12PresentationStage();

    public static native int nGetD3D12PresentationCapabilities();

    public static native String nGetD3D12PresentationStatus();

    public static native String nGetD3D12AdapterLuid();

    public static native boolean
            nCanCreateD3D12PresentationSwapchain();

    public static native boolean
            nOwnsD3D12PresentationSwapchain(long swapchain);

    public static native int nCreateD3D12PresentationSwapchain(
        long window,
        long createInfoAddress,
        long swapchainAddress
    );

    public static native void nDestroyD3D12PresentationSwapchain(
        long swapchain
    );

    public static native int nGetD3D12PresentationSwapchainImages(
        long swapchain,
        long countAddress,
        long imagesAddress
    );

    public static native int nAcquireD3D12PresentationImage(
        long swapchain,
        long semaphore,
        long imageIndexAddress
    );

    public static native int nQueuePresentD3D12(
        long queue,
        long presentInfoAddress
    );

    /**
     * Generic proxy-swapchain API used by every provider that owns Vulkan
     * presentation. It currently routes AMD and Intel adapters through the
     * FidelityFX FSR 3.1 provider.
     */
    public static native boolean nProxySwapchainAvailable();

    public static native int nCreateProxySwapchain(long createInfoAddress, long allocatorAddress, long swapchainAddress);

    public static native void nDestroyProxySwapchain(long device, long swapchain, long allocatorAddress);

    public static native int nGetProxySwapchainImages(
        long device, long swapchain, long countAddress, long imagesAddress
    );

    public static native int nAcquireProxySwapchainImage(
        long device, long swapchain, long timeout, long semaphore, long fence, long imageIndexAddress
    );

    public static native int nQueueProxyPresent(long queue, long presentInfoAddress);

    /**
     * Submits the renderer-owned pre-HUD color, depth and dense motion-vector
     * resources to the active native provider.
     */
    public static native boolean nSubmitFrameGenerationInputs(
        int vendorId,
        long commandBuffer,
        long backbufferImage, int backbufferFormat,
        long colorImage, long colorMemory, long colorView, int colorFormat,
        long depthImage, long depthMemory, long depthView, int depthFormat,
        long motionImage, long motionMemory, long motionView, int motionFormat,
        int colorWidth, int colorHeight,
        int guideWidth, int guideHeight,
        float[] viewToClip,
        float[] clipToView,
        float[] clipToPreviousClip,
        float[] previousClipToClip,
        float frameTimeMs,
        float cameraNear, float cameraFar, float verticalFov, float aspect,
        float jitterX, float jitterY,
        float cameraX, float cameraY, float cameraZ,
        float cameraUpX, float cameraUpY, float cameraUpZ,
        float cameraRightX, float cameraRightY, float cameraRightZ,
        float cameraForwardX, float cameraForwardY, float cameraForwardZ,
        boolean reset,
        boolean enabled,
        int multiplier,
        boolean uiRecomposition
    );

    /**
     * Tags the full-resolution transparent UI layer after Minecraft has
     * rendered it and completes the DLSS-G configuration for this frame.
     */
    public static native boolean nSubmitFrameGenerationUi(
        int vendorId,
        long commandBuffer,
        long uiImage, long uiMemory, long uiView, int uiFormat,
        int width, int height,
        boolean enabled,
        int multiplier,
        boolean uiRecomposition
    );

    /**
     * @deprecated Use the provider-neutral proxy methods above.
     */
    @Deprecated(forRemoval = false)
    public static native boolean nAmdProxyAvailable();

    @Deprecated(forRemoval = false)
    public static native int nAmdCreateSwapchain(long createInfoAddress, long allocatorAddress, long swapchainAddress);

    @Deprecated(forRemoval = false)
    public static native void nAmdDestroySwapchain(long device, long swapchain, long allocatorAddress);

    @Deprecated(forRemoval = false)
    public static native int nAmdGetSwapchainImages(
        long device, long swapchain, long countAddress, long imagesAddress
    );

    @Deprecated(forRemoval = false)
    public static native int nAmdAcquireNextImage(
        long device, long swapchain, long timeout, long semaphore, long fence, long imageIndexAddress
    );

    @Deprecated(forRemoval = false)
    public static native int nAmdQueuePresent(long queue, long presentInfoAddress);
}
