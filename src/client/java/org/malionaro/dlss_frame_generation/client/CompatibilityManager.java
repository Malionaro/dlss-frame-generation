package org.malionaro.dlss_frame_generation.client;

import org.slf4j.Logger;

public final class CompatibilityManager {
    private final NativeLoader.NativeLoadResult nativeLoadResult;
    private final RendererDetection rendererDetection;
    private volatile RendererBackendAdapter adapter;
    private volatile FrameGenerationBackend activeBackend;

    private CompatibilityManager(NativeLoader.NativeLoadResult nativeLoadResult, RendererDetection rendererDetection, RendererBackendAdapter adapter, FrameGenerationBackend activeBackend) {
        this.nativeLoadResult = nativeLoadResult;
        this.rendererDetection = rendererDetection;
        this.adapter = adapter;
        this.activeBackend = activeBackend;
    }

    public static CompatibilityManager initialize(Logger logger) {
        NativeLoader.NativeLoadResult nativeLoadResult = NativeLoader.load(logger);
        if (nativeLoadResult.loaded()) {
            try {
                boolean coreReady = StreamlineBridge.nInitCore(NativeLoader.getExtractedNativesDir());
                logger.info("Streamline core early initialization: {}", coreReady ? "READY" : "UNAVAILABLE");
            } catch (UnsatisfiedLinkError error) {
                logger.warn("Streamline core entry point is unavailable: {}", error.getMessage());
            }
        }
        RendererDetection rendererDetection = RendererDetector.detect();
        
        RendererBackendAdapter adapter;
        switch (rendererDetection.backend()) {
            case VANILLA_VULKAN:
                adapter = new VanillaVulkanAdapter();
                break;
            case SODIUM_VULKAN:
                adapter = new SodiumVulkanAdapter();
                break;
            case OPENGL:
            case IRIS_OPENGL:
            default:
                adapter = new OpenGLFallbackAdapter();
                break;
        }

        FrameGenerationBackend activeBackend = resolveBackend(rendererDetection.backendDescription());

        CompatibilityManager manager = new CompatibilityManager(nativeLoadResult, rendererDetection, adapter, activeBackend);
        logger.info("DLSS FG renderer detection: backend={}, description='{}', sodium={}, iris={}", 
                rendererDetection.backend(), rendererDetection.backendDescription(), rendererDetection.sodiumPresent(), rendererDetection.irisPresent());
        logger.info("DLSS FG active adapter resolved to: {}, vendor backend: {}", adapter.getClass().getSimpleName(), activeBackend.vendor().displayName());
        return manager;
    }

    public static FrameGenerationBackend resolveBackend(String rendererDescription) {
        int configuredVendor = FrameGenConfig.getInstance().activeVendorId;
        if (configuredVendor != 0) {
            FrameGenerationBackend.GpuVendor vendor = FrameGenerationBackend.GpuVendor.fromVendorId(configuredVendor);
            switch (vendor) {
                case NVIDIA: return new NvidiaBackend();
                case AMD: return new AmdBackend();
                case INTEL: return new IntelBackend();
                default: return new FallbackBackend();
            }
        }

        // Renderer strings are not reliable GPU identifiers. The real vendor
        // is selected later from VkPhysicalDeviceProperties.vendorID.
        return new FallbackBackend();
    }

    public RendererBackend backend() { 
        return rendererDetection.backend(); 
    }

    public RendererBackendAdapter adapter() {
        return adapter;
    }

    /**
     * Minecraft chooses and constructs the graphics backend after Fabric client
     * entrypoints have run. The Vulkan mixin is therefore the authoritative
     * signal that replaces the provisional startup adapter.
     */
    public synchronized void activateVulkanAdapter() {
        if (!(adapter instanceof VanillaVulkanAdapter)
                && !(adapter instanceof SodiumVulkanAdapter)) {
            adapter = rendererDetection.sodiumPresent()
                    ? new SodiumVulkanAdapter()
                    : new VanillaVulkanAdapter();
        }
    }

    public FrameGenerationBackend activeVendorBackend() {
        return activeBackend;
    }

    public FrameGenerationCompatibilityLayer.Route activeRoute() {
        return FrameGenerationCompatibilityLayer.queryVulkanRoute(
                activeBackend.vendor()
        );
    }

    public synchronized void selectBackendForVendorId(int detectedVendor) {
        // VkPhysicalDeviceProperties is authoritative. A configuration value
        // must never route a real NVIDIA/AMD/Intel device into another
        // vendor's native ABI.
        FrameGenerationBackend.GpuVendor vendor =
                FrameGenerationBackend.GpuVendor.fromVendorId(detectedVendor);
        FrameGenConfig config = FrameGenConfig.getInstance();
        if (config.retainFrameGenerationForVendor(vendor)) {
            config.save();
        }
        activeBackend = switch (vendor) {
            case NVIDIA -> new NvidiaBackend();
            case AMD -> new AmdBackend();
            case INTEL -> new IntelBackend();
            default -> new FallbackBackend();
        };
    }

    public boolean isFrameGenerationSupported() { 
        return nativeLoadResult.loaded() && activeBackend.isSupported() && adapter.isFrameReadyForFG();
    }

    public String statusDetail() { 
        if (!nativeLoadResult.loaded()) {
            return "Native loader issue: " + nativeLoadResult.detail();
        }
        if (!adapter.isFrameReadyForFG()) {
            return "Rendering backend does not support frame generation (" + adapter.getClass().getSimpleName() + ")";
        }
        return activeRoute().status();
    }
}
