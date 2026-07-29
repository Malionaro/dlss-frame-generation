package org.malionaro.dlss_frame_generation.client.frame;

/**
 * Immutable identity of one acquired presentation image and the world frame
 * rendered for it. Resources with different identities must never be combined.
 */
public record FrameIdentity(
        long frameSerial,
        long swapchainGeneration,
        int swapchainImageIndex
) {
    public static final FrameIdentity INVALID = new FrameIdentity(0L, 0L, -1);

    public boolean isValid() {
        return frameSerial > 0L
                && swapchainGeneration > 0L
                && swapchainImageIndex >= 0;
    }

    public boolean belongsToSameFrame(FrameIdentity other) {
        return other != null
                && frameSerial == other.frameSerial
                && swapchainGeneration == other.swapchainGeneration
                && swapchainImageIndex == other.swapchainImageIndex;
    }
}
