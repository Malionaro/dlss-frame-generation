package org.malionaro.dlss_frame_generation.client;

import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED;

/**
 * Atomic renderer snapshot consumed by frame generation.
 *
 * <p>Every handle in this record is captured at the same pre-HUD frame
 * boundary. It deliberately includes image layouts, queue ownership and the
 * swapchain generation so stale or cross-frame resources can be rejected
 * before native code sees them.</p>
 */
public record FrameResourceSet(
        FrameIdentity identity,
        Source source,
        long commandBuffer,
        ImageResource color,
        ImageResource depth,
        ImageResource output,
        ImageResource backbuffer,
        long acquireSemaphore,
        long presentSemaphore,
        boolean outsideRenderPass
) {
    public enum Source {
        VANILLA_VULKAN,
        SODIUM_VULKAN,
        RENDERER_INTEGRATION
    }

    public record ImageResource(
            long image,
            long view,
            int format,
            int layout,
            int queueFamilyIndex,
            int width,
            int height
    ) {
        public boolean isValidTexture() {
            return image != 0L
                    && view != 0L
                    && format != 0
                    && layout != VK_IMAGE_LAYOUT_UNDEFINED
                    && queueFamilyIndex >= 0
                    && width > 0
                    && height > 0;
        }

        public boolean isValidSwapchainImage() {
            return image != 0L
                    && format != 0
                    && queueFamilyIndex >= 0
                    && width > 0
                    && height > 0;
        }
    }

    public FrameResourceSet {
        if (identity == null) {
            identity = FrameIdentity.INVALID;
        }
        if (source == null) {
            source = Source.RENDERER_INTEGRATION;
        }
    }

    public Validation validate() {
        if (!identity.isValid()) {
            return Validation.failure("frame identity is incomplete");
        }
        if (commandBuffer == 0L) {
            return Validation.failure("no active Vulkan command buffer");
        }
        if (!outsideRenderPass) {
            return Validation.failure(
                    "capture was requested while a render pass was active"
            );
        }
        if (color == null || !color.isValidTexture()) {
            return Validation.failure("color resource is incomplete");
        }
        if (depth == null || !depth.isValidTexture()) {
            return Validation.failure("depth resource is incomplete");
        }
        if (output == null || !output.isValidTexture()) {
            return Validation.failure("output resource is incomplete");
        }
        if (backbuffer == null || !backbuffer.isValidSwapchainImage()) {
            return Validation.failure("acquired swapchain image is incomplete");
        }
        if (color.width() != depth.width()
                || color.height() != depth.height()) {
            return Validation.failure(
                    "color and depth dimensions belong to different passes"
            );
        }
        int owner = color.queueFamilyIndex();
        if (depth.queueFamilyIndex() != owner
                || output.queueFamilyIndex() != owner
                || backbuffer.queueFamilyIndex() != owner) {
            return Validation.failure(
                    "resources have incompatible Vulkan queue ownership"
            );
        }
        return Validation.success();
    }

    public record Validation(boolean accepted, String reason) {
        private static Validation success() {
            return new Validation(true, "renderer resources are frame-atomic");
        }

        private static Validation failure(String reason) {
            return new Validation(false, reason);
        }
    }
}
