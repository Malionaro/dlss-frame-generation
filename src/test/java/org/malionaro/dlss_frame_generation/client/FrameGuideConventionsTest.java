package org.malionaro.dlss_frame_generation.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrameGuideConventionsTest {
    @Test
    void acceptsTheMinecraft262ReversedZMotionContract() {
        FrameGuideConventions conventions = validConventions();

        assertTrue(conventions.validate().accepted());
        assertTrue(conventions.summary().contains("depth=reversed 1..0"));
        assertTrue(conventions.summary().contains(
                "motion=current-to-previous render pixels"));
        assertTrue(conventions.summary().contains("+Y=down"));
        assertTrue(conventions.summary().contains(
                "object motion=temporal estimate"));
    }

    @Test
    void rejectsInvalidPlanesDimensionsOrJitteredMotion() {
        FrameGuideConventions valid = validConventions();
        assertFalse(new FrameGuideConventions(
                valid.depth(),
                valid.motionDirection(),
                valid.motionUnits(),
                valid.positiveY(),
                true,
                valid.objectMotionSource(),
                0.05f,
                0.01f,
                1280,
                720,
                1920,
                1080
        ).validate().accepted());
        assertFalse(new FrameGuideConventions(
                valid.depth(),
                valid.motionDirection(),
                valid.motionUnits(),
                valid.positiveY(),
                false,
                valid.objectMotionSource(),
                0.05f,
                512.0f,
                1280,
                720,
                1920,
                1080
        ).validate().accepted());
        assertFalse(new FrameGuideConventions(
                valid.depth(),
                valid.motionDirection(),
                valid.motionUnits(),
                valid.positiveY(),
                true,
                valid.objectMotionSource(),
                0.05f,
                512.0f,
                0,
                720,
                1920,
                1080
        ).validate().accepted());
    }

    private static FrameGuideConventions validConventions() {
        return new FrameGuideConventions(
                FrameGuideConventions.DepthConvention
                        .REVERSED_ZERO_TO_ONE,
                FrameGuideConventions.MotionDirection
                        .CURRENT_PIXEL_TO_PREVIOUS_PIXEL,
                FrameGuideConventions.MotionUnits.RENDER_PIXELS,
                FrameGuideConventions.AxisDirection.DOWN,
                true,
                FrameGuideConventions.ObjectMotionSource
                        .CAMERA_REPROJECTION_WITH_TEMPORAL_IMAGE_ESTIMATE,
                0.05f,
                512.0f,
                1280,
                720,
                1920,
                1080
        );
    }
}
