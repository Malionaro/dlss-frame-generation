package org.malionaro.dlss_frame_generation.client.motion;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MotionVectorDebugRendererTest {

    @Test
    void cameraPreviewFollowsLookDirectionOnBothAxes() {
        assertTrue(MotionVectorDebugRenderer.cameraPreviewMotionX(-0.1f, 0.0f, 100) > 0.0f,
                "Looking right must point right");
        assertTrue(MotionVectorDebugRenderer.cameraPreviewMotionX(0.1f, 0.0f, 100) < 0.0f,
                "Looking left must point left");
        assertTrue(MotionVectorDebugRenderer.cameraPreviewMotionY(0.1f, 0.0f, 100) < 0.0f,
                "Looking up must point up on a down-positive GUI axis");
        assertTrue(MotionVectorDebugRenderer.cameraPreviewMotionY(-0.1f, 0.0f, 100) > 0.0f,
                "Looking down must point down on a down-positive GUI axis");

        float upRightX = MotionVectorDebugRenderer.cameraPreviewMotionX(-0.1f, 0.0f, 100);
        float upRightY = MotionVectorDebugRenderer.cameraPreviewMotionY(0.1f, 0.0f, 100);
        assertTrue(upRightX > 0.0f && upRightY < 0.0f,
                "Looking up-right must produce an up-right diagonal");
    }

    @Test
    void cameraPreviewTranslationUsesTheSameScreenConvention() {
        assertTrue(MotionVectorDebugRenderer.cameraPreviewMotionX(0.0f, 1.0f, 100) > 0.0f,
                "Moving right must point right");
        assertTrue(MotionVectorDebugRenderer.cameraPreviewMotionY(0.0f, 1.0f, 100) < 0.0f,
                "Moving up must point up");
    }

    @Test
    void heatmapIsTransparentAtRestAndChangesWithoutEarlySaturation() {
        assertEquals(0.0f, MotionVectorDebugRenderer.heatIntensity(0.0f));
        float slow = MotionVectorDebugRenderer.heatIntensity(4.0f);
        float medium = MotionVectorDebugRenderer.heatIntensity(16.0f);
        float fast = MotionVectorDebugRenderer.heatIntensity(48.0f);
        assertTrue(slow > 0.0f);
        assertTrue(slow < medium);
        assertTrue(medium < fast);
        assertTrue(fast < 1.0f);
    }
}
