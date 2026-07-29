package org.malionaro.dlss_frame_generation.client.interop;
import org.malionaro.dlss_frame_generation.client.config.FrameGenConfig;
import org.malionaro.dlss_frame_generation.client.FrameGenMod;
import org.malionaro.dlss_frame_generation.client.StreamlineBridge;

import org.lwjgl.vulkan.VkDependencyInfo;

import static org.lwjgl.vulkan.KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_GENERAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED;
import static org.lwjgl.vulkan.VK11.VK_QUEUE_FAMILY_EXTERNAL;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_NONE;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_NONE;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_TRANSFER_BIT;

/**
 * Read-only Java view of the Windows Vulkan/D3D12 interop bootstrap.
 *
 * <p>{@link Stage#D3D12_DEVICE_READY} is intentionally not equivalent to an
 * active presentation path. Provider controls must require
 * {@link Stage#PRESENTATION_READY} plus their own runtime capabilities.</p>
 */
public final class D3D12PresentationBridge {
    public static final int CAP_LUID_MATCH = 1;
    public static final int CAP_EXTERNAL_MEMORY = 1 << 1;
    public static final int CAP_EXTERNAL_SEMAPHORE = 1 << 2;
    public static final int CAP_D3D12_DEVICE = 1 << 3;
    public static final int CAP_D3D12_QUEUE = 1 << 4;
    public static final int CAP_DXGI_SWAPCHAIN = 1 << 5;
    public static final int CAP_ZERO_COPY_FRAMES = 1 << 6;

    public enum Stage {
        UNAVAILABLE,
        VULKAN_INTEROP_READY,
        ADAPTER_MATCHED,
        D3D12_DEVICE_READY,
        PRESENTATION_READY,
        FAILED;

        static Stage fromNative(int value) {
            return value >= 0 && value < values().length
                    ? values()[value]
                    : FAILED;
        }
    }

    public record Status(
            Stage stage,
            int capabilities,
            String adapterLuid,
            String detail
    ) {
        public boolean presentationReady() {
            int required = CAP_LUID_MATCH
                    | CAP_EXTERNAL_MEMORY
                    | CAP_EXTERNAL_SEMAPHORE
                    | CAP_D3D12_DEVICE
                    | CAP_D3D12_QUEUE
                    | CAP_DXGI_SWAPCHAIN
                    | CAP_ZERO_COPY_FRAMES;
            return stage == Stage.PRESENTATION_READY
                    && (capabilities & required) == required;
        }
    }

    private static volatile Status status = new Status(
            Stage.UNAVAILABLE, 0, "", "D3D12 probe has not run"
    );
    private static volatile String lastReportedDebugDetail = "";

    private D3D12PresentationBridge() {
    }

    public static Status probe(
            long physicalDevice,
            long logicalDevice,
            long graphicsQueue,
            int graphicsQueueFamily
    ) {
        try {
            StreamlineBridge.nProbeD3D12Presentation(
                    physicalDevice,
                    logicalDevice,
                    graphicsQueue,
                    graphicsQueueFamily
            );
            refresh();
        } catch (UnsatisfiedLinkError error) {
            status = new Status(
                    Stage.FAILED,
                    0,
                    "",
                    "D3D12 JNI entry point unavailable: "
                            + error.getMessage()
            );
        }
        return status;
    }

    public static Status status() {
        return status;
    }

    public static boolean canCreateProxySwapchain() {
        boolean requested = FrameGenConfig.getInstance()
                .hybridD3D12PresentationEnabled
                || Boolean.getBoolean(
                        "dlss_fg.hybridD3D12Present");
        if (!requested) {
            return false;
        }
        try {
            return StreamlineBridge
                    .nCanCreateD3D12PresentationSwapchain();
        } catch (UnsatisfiedLinkError ignored) {
            return false;
        }
    }

    public static boolean ownsSwapchain(long swapchain) {
        if (swapchain == 0L) {
            return false;
        }
        try {
            return StreamlineBridge
                    .nOwnsD3D12PresentationSwapchain(swapchain);
        } catch (UnsatisfiedLinkError ignored) {
            return false;
        }
    }

    public static int createSwapchain(
            long window,
            long createInfoAddress,
            long swapchainAddress
    ) {
        int result = StreamlineBridge
                .nCreateD3D12PresentationSwapchain(
                        window, createInfoAddress, swapchainAddress);
        refresh();
        return result;
    }

    public static void destroySwapchain(long swapchain) {
        StreamlineBridge.nDestroyD3D12PresentationSwapchain(
                swapchain);
        refresh();
    }

    public static int getSwapchainImages(
            long swapchain,
            long countAddress,
            long imagesAddress
    ) {
        return StreamlineBridge
                .nGetD3D12PresentationSwapchainImages(
                        swapchain, countAddress, imagesAddress);
    }

    public static int acquireNextImage(
            long swapchain,
            long semaphore,
            long imageIndexAddress
    ) {
        return StreamlineBridge.nAcquireD3D12PresentationImage(
                swapchain, semaphore, imageIndexAddress);
    }

    public static int queuePresent(
            long queue,
            long presentInfoAddress
    ) {
        int result = StreamlineBridge.nQueuePresentD3D12(
                queue, presentInfoAddress);
        refresh();
        if (status.detail().contains("D3D12 debug")
                && !status.detail().equals(lastReportedDebugDetail)) {
            FrameGenMod.LOGGER.warn(
                    "Hybrid presentation validation: {}",
                    status.detail()
            );
            lastReportedDebugDetail = status.detail();
        }
        return result;
    }

    /**
     * Minecraft's Vulkan WSI blit transitions its destination to
     * PRESENT_SRC_KHR. A shared D3D12 resource is not a Vulkan presentable
     * image, so the hybrid route instead performs explicit EXTERNAL queue
     * ownership transfer and leaves the image in GENERAL for D3D12.
     */
    public static void adjustBlitBarrier(
            VkDependencyInfo dependencyInfo,
            int graphicsQueueFamily
    ) {
        if (status.stage() != Stage.PRESENTATION_READY) {
            return;
        }
        var barriers = dependencyInfo.pImageMemoryBarriers();
        if (barriers == null) {
            return;
        }
        for (int index = 0; index < barriers.remaining(); ++index) {
            var barrier = barriers.get(index);
            if (barrier.newLayout()
                    == VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL) {
                barrier.oldLayout(VK_IMAGE_LAYOUT_UNDEFINED);
                barrier.srcQueueFamilyIndex(
                        VK_QUEUE_FAMILY_EXTERNAL);
                barrier.dstQueueFamilyIndex(
                        graphicsQueueFamily);
                barrier.srcStageMask(VK_PIPELINE_STAGE_2_NONE);
                barrier.srcAccessMask(VK_ACCESS_2_NONE);
                barrier.dstStageMask(
                        VK_PIPELINE_STAGE_2_TRANSFER_BIT);
                barrier.dstAccessMask(
                        VK_ACCESS_2_TRANSFER_WRITE_BIT);
            } else if (barrier.newLayout()
                    == VK_IMAGE_LAYOUT_PRESENT_SRC_KHR) {
                barrier.oldLayout(
                        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
                barrier.newLayout(VK_IMAGE_LAYOUT_GENERAL);
                barrier.srcQueueFamilyIndex(
                        graphicsQueueFamily);
                barrier.dstQueueFamilyIndex(
                        VK_QUEUE_FAMILY_EXTERNAL);
                barrier.srcStageMask(
                        VK_PIPELINE_STAGE_2_TRANSFER_BIT);
                barrier.srcAccessMask(
                        VK_ACCESS_2_TRANSFER_WRITE_BIT);
                barrier.dstStageMask(VK_PIPELINE_STAGE_2_NONE);
                barrier.dstAccessMask(VK_ACCESS_2_NONE);
            }
        }
    }

    private static void refresh() {
        status = new Status(
                Stage.fromNative(
                        StreamlineBridge.nGetD3D12PresentationStage()),
                StreamlineBridge.nGetD3D12PresentationCapabilities(),
                StreamlineBridge.nGetD3D12AdapterLuid(),
                StreamlineBridge.nGetD3D12PresentationStatus()
        );
    }
}
