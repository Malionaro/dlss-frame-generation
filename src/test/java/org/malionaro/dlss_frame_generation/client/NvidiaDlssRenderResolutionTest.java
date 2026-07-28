package org.malionaro.dlss_frame_generation.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NvidiaDlssRenderResolutionTest {
    @Test
    void unsupportedFeatureNeverReducesWorldResolution() {
        var resolution = NvidiaDlssRenderResolution.resolve(
                3840, 2160, 4, false, false, false);

        assertEquals(3840, resolution.renderWidth());
        assertEquals(2160, resolution.renderHeight());
        assertFalse(resolution.reduced());
    }

    @Test
    void dlaaKeepsNativeResolution() {
        var resolution = NvidiaDlssRenderResolution.resolve(
                2560, 1440, 1, true, false, false);

        assertEquals(2560, resolution.renderWidth());
        assertEquals(1440, resolution.renderHeight());
        assertFalse(resolution.reduced());
    }

    @Test
    void qualityFallbackProducesAlignedReducedTarget() {
        var resolution = NvidiaDlssRenderResolution.resolve(
                1920, 1080, 2, true, false, false);

        assertTrue(resolution.reduced());
        assertEquals(0, resolution.renderWidth() % 8);
        assertEquals(0, resolution.renderHeight() % 8);
        assertTrue(resolution.renderWidth() < resolution.outputWidth());
        assertTrue(resolution.renderHeight() < resolution.outputHeight());
    }

    @Test
    void rayReconstructionPromotesDlaaToQuality() {
        var resolution = NvidiaDlssRenderResolution.resolve(
                1920, 1080, 1, true, true, true);

        assertEquals(2, resolution.mode());
        assertTrue(resolution.reduced());
    }
}
