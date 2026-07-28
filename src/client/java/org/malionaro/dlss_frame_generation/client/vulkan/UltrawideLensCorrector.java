package org.malionaro.dlss_frame_generation.client.vulkan;

import org.joml.Matrix4f;

public class UltrawideLensCorrector {

    public static void applyLensCorrection(Matrix4f projMatrix, float aspect) {
        if (aspect < 2.0f) {
            return; // Normal 16:9 / 16:10 monitor
        }

        // Apply non-linear compression to M00 (X projection scale) to eliminate ultrawide edge stretching
        float compressionFactor = 1.0f / (1.0f + (aspect - 1.777f) * 0.15f);
        projMatrix.m00(projMatrix.m00() * compressionFactor);
    }
}
