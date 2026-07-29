package org.malionaro.dlss_frame_generation.client.backend;
import org.malionaro.dlss_frame_generation.client.StreamlineBridge;

/**
 * Vendor-neutral routing layer between Minecraft's renderer and native frame
 * generation providers. Renderer mixins depend only on this class, so adding a
 * future D3D12 FSR 4 or XeSS-FG provider does not require another swapchain
 * rewrite.
 */
public final class FrameGenerationCompatibilityLayer {
    public enum RenderApi {
        UNKNOWN(0),
        VULKAN(1),
        D3D12(2);

        private final int nativeId;

        RenderApi(int nativeId) {
            this.nativeId = nativeId;
        }

        public int nativeId() {
            return nativeId;
        }
    }

    public enum ProviderId {
        NONE(0, "No frame-generation provider"),
        NVIDIA_STREAMLINE_VULKAN(1, "NVIDIA Streamline (Vulkan)"),
        AMD_FSR31_VULKAN(2, "AMD FidelityFX FSR 3.1 (Vulkan)"),
        AMD_FSR4_D3D12(3, "AMD FidelityFX FSR 4 (D3D12)"),
        INTEL_XESS_D3D12(4, "Intel XeSS Frame Generation (D3D12)"),
        CROSS_VENDOR_FSR31_VULKAN(5, "Cross-vendor FidelityFX FSR 3.1 (Vulkan)");

        private final int nativeId;
        private final String displayName;

        ProviderId(int nativeId, String displayName) {
            this.nativeId = nativeId;
            this.displayName = displayName;
        }

        public int nativeId() {
            return nativeId;
        }

        public String displayName() {
            return displayName;
        }

        public static ProviderId fromNativeId(int nativeId) {
            for (ProviderId provider : values()) {
                if (provider.nativeId == nativeId) return provider;
            }
            return NONE;
        }
    }

    public record Route(
        FrameGenerationBackend.GpuVendor vendor,
        RenderApi renderApi,
        ProviderId provider,
        int capabilities,
        int maxFrameMultiplier,
        String status
    ) {
        public boolean proxySwapchainReady() {
            return (capabilities & StreamlineBridge.CAP_PROXY_SWAPCHAIN) != 0;
        }

        public boolean frameGenerationReady() {
            return (capabilities & StreamlineBridge.CAP_FRAME_GENERATION) != 0;
        }

        public boolean isFallback() {
            return provider == ProviderId.CROSS_VENDOR_FSR31_VULKAN;
        }
    }

    private FrameGenerationCompatibilityLayer() {
    }

    /**
     * Pure routing policy, kept separate from native readiness so it can be
     * unit-tested without loading JNI.
     */
    public static ProviderId selectPreferredProvider(
        FrameGenerationBackend.GpuVendor vendor,
        RenderApi renderApi,
        boolean fsr31VulkanAvailable,
        boolean fsr4D3D12Available,
        boolean xessFgD3D12Available
    ) {
        if (renderApi == RenderApi.VULKAN) {
            return switch (vendor) {
                case NVIDIA -> ProviderId.NVIDIA_STREAMLINE_VULKAN;
                case AMD -> fsr31VulkanAvailable
                    ? ProviderId.AMD_FSR31_VULKAN : ProviderId.NONE;
                case INTEL -> fsr31VulkanAvailable
                    ? ProviderId.CROSS_VENDOR_FSR31_VULKAN : ProviderId.NONE;
                case UNKNOWN -> ProviderId.NONE;
            };
        }
        if (renderApi == RenderApi.D3D12) {
            return switch (vendor) {
                case AMD -> fsr4D3D12Available
                    ? ProviderId.AMD_FSR4_D3D12 : ProviderId.NONE;
                case INTEL -> xessFgD3D12Available
                    ? ProviderId.INTEL_XESS_D3D12 : ProviderId.NONE;
                default -> ProviderId.NONE;
            };
        }
        return ProviderId.NONE;
    }

    public static Route queryVulkanRoute(FrameGenerationBackend.GpuVendor vendor) {
        try {
            int capabilities = StreamlineBridge.nGetBackendCapabilities(vendor.vendorId());
            int multiplier = StreamlineBridge.nGetMaxFrameMultiplier(vendor.vendorId());
            String status = StreamlineBridge.nGetBackendStatus(vendor.vendorId());
            ProviderId provider = ProviderId.fromNativeId(
                StreamlineBridge.nGetActiveProviderId()
            );
            if (provider == ProviderId.NONE
                    || (vendor == FrameGenerationBackend.GpuVendor.AMD
                        && provider != ProviderId.AMD_FSR31_VULKAN)
                    || (vendor == FrameGenerationBackend.GpuVendor.INTEL
                        && provider != ProviderId.CROSS_VENDOR_FSR31_VULKAN)
                    || (vendor == FrameGenerationBackend.GpuVendor.NVIDIA
                        && provider != ProviderId.NVIDIA_STREAMLINE_VULKAN)) {
                provider = selectPreferredProvider(
                    vendor, RenderApi.VULKAN,
                    (capabilities & StreamlineBridge.CAP_PROXY_SWAPCHAIN) != 0,
                    false, false
                );
            }
            return new Route(
                vendor, RenderApi.VULKAN, provider, capabilities,
                Math.max(1, multiplier), status
            );
        } catch (UnsatisfiedLinkError error) {
            return new Route(
                vendor, RenderApi.VULKAN, ProviderId.NONE, 0, 1,
                "Native compatibility layer unavailable: " + error.getMessage()
            );
        }
    }

    public static boolean proxySwapchainAvailable() {
        try {
            return StreamlineBridge.nProxySwapchainAvailable();
        } catch (UnsatisfiedLinkError ignored) {
            return false;
        }
    }

    public static int createProxySwapchain(
        long createInfoAddress, long allocatorAddress, long swapchainAddress
    ) {
        return StreamlineBridge.nCreateProxySwapchain(
            createInfoAddress, allocatorAddress, swapchainAddress
        );
    }

    public static void destroyProxySwapchain(
        long device, long swapchain, long allocatorAddress
    ) {
        StreamlineBridge.nDestroyProxySwapchain(device, swapchain, allocatorAddress);
    }

    public static int getProxySwapchainImages(
        long device, long swapchain, long countAddress, long imagesAddress
    ) {
        return StreamlineBridge.nGetProxySwapchainImages(
            device, swapchain, countAddress, imagesAddress
        );
    }

    public static int acquireProxySwapchainImage(
        long device,
        long swapchain,
        long timeout,
        long semaphore,
        long fence,
        long imageIndexAddress
    ) {
        return StreamlineBridge.nAcquireProxySwapchainImage(
            device, swapchain, timeout, semaphore, fence, imageIndexAddress
        );
    }

    public static int queueProxyPresent(long queue, long presentInfoAddress) {
        return StreamlineBridge.nQueueProxyPresent(queue, presentInfoAddress);
    }
}
