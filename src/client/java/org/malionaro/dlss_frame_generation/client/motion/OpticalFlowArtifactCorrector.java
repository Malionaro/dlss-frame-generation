package org.malionaro.dlss_frame_generation.client.motion;

import org.joml.Vector3f;

public class OpticalFlowArtifactCorrector {
    private static Vector3f prevFwd = new Vector3f(0, 0, 1);
    private static float dampeningFactor = 1.0f; // 1.0 = Full FG, 0.0 = Dampened for zero artifacts

    /**
     * Current dampening factor, consumed by the motion pipeline to reset
     * temporal history while whipping the camera (reprojection is unreliable
     * then; static vectors beat tearing). Render-thread confined, like the
     * camera state that feeds it.
     */
    public static float dampeningFactor() {
        return dampeningFactor;
    }

    public static void updateCameraRotation(float fwdX, float fwdY, float fwdZ) {
        Vector3f currFwd = new Vector3f(fwdX, fwdY, fwdZ).normalize();
        
        // Calculate angular velocity via dot product
        float dot = Math.max(-1.0f, Math.min(1.0f, prevFwd.dot(currFwd)));
        float angleRad = (float) Math.acos(dot);
        prevFwd.set(currFwd);

        // If angular velocity > 0.35 rad/frame (~20 deg/frame turn), dampen optical flow to prevent tearing
        if (angleRad > 0.35f) {
            dampeningFactor = Math.max(0.2f, 1.0f - (angleRad - 0.35f) * 2.0f);
        } else {
            // Smoothly recover back to 1.0
            dampeningFactor = Math.min(1.0f, dampeningFactor + 0.1f);
        }
    }
}
