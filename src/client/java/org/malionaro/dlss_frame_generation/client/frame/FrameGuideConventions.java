package org.malionaro.dlss_frame_generation.client.frame;

import java.util.Locale;

/**
 * Explicit coordinate contract for temporal renderer inputs.
 *
 * <p>The values described here are the values stored in the renderer-owned
 * guide images, before provider-specific normalization. Keeping this contract
 * next to the frame identity prevents a provider from silently interpreting
 * reversed depth, Y direction, jitter, or resolution differently.</p>
 */
public record FrameGuideConventions(
        DepthConvention depth,
        MotionDirection motionDirection,
        MotionUnits motionUnits,
        AxisDirection positiveY,
        boolean jitterRemovedFromMotion,
        ObjectMotionSource objectMotionSource,
        float cameraNear,
        float cameraFar,
        int renderWidth,
        int renderHeight,
        int displayWidth,
        int displayHeight
) {
    public enum DepthConvention {
        REVERSED_ZERO_TO_ONE
    }

    public enum MotionDirection {
        CURRENT_PIXEL_TO_PREVIOUS_PIXEL
    }

    public enum MotionUnits {
        RENDER_PIXELS
    }

    public enum AxisDirection {
        DOWN
    }

    public enum ObjectMotionSource {
        CAMERA_REPROJECTION_WITH_TEMPORAL_IMAGE_ESTIMATE
    }

    public Validation validate() {
        if (depth != DepthConvention.REVERSED_ZERO_TO_ONE) {
            return Validation.failure("unsupported depth convention");
        }
        if (motionDirection
                != MotionDirection.CURRENT_PIXEL_TO_PREVIOUS_PIXEL
                || motionUnits != MotionUnits.RENDER_PIXELS
                || positiveY != AxisDirection.DOWN) {
            return Validation.failure(
                    "motion-vector convention is incomplete"
            );
        }
        if (!jitterRemovedFromMotion) {
            return Validation.failure(
                    "motion vectors still contain projection jitter"
            );
        }
        if (!Float.isFinite(cameraNear)
                || !Float.isFinite(cameraFar)
                || cameraNear <= 0.0f
                || cameraFar <= cameraNear) {
            return Validation.failure("camera near/far planes are invalid");
        }
        if (renderWidth <= 0
                || renderHeight <= 0
                || displayWidth <= 0
                || displayHeight <= 0) {
            return Validation.failure(
                    "render/display dimensions are invalid"
            );
        }
        return Validation.success();
    }

    public String summary() {
        return String.format(
                Locale.ROOT,
                "depth=reversed 1..0 (near=%.3f, far=%.1f), "
                        + "motion=current-to-previous render pixels, "
                        + "+Y=down, jitter removed, "
                        + "object motion=temporal estimate, "
                        + "render=%dx%d, display=%dx%d",
                cameraNear,
                cameraFar,
                renderWidth,
                renderHeight,
                displayWidth,
                displayHeight
        );
    }

    public record Validation(boolean accepted, String reason) {
        private static Validation success() {
            return new Validation(
                    true,
                    "depth and motion conventions are explicit"
            );
        }

        private static Validation failure(String reason) {
            return new Validation(false, reason);
        }
    }
}
