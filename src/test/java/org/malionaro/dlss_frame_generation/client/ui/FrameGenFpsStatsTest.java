package org.malionaro.dlss_frame_generation.client.ui;

import org.malionaro.dlss_frame_generation.client.backend.FrameGenerationBackend;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FrameGenFpsStatsTest {
    @Test
    void activeFrameGenerationMultipliesBaseFps() {
        assertEquals(120, FrameGenFpsStats.effectiveFps(60, true, 2));
        assertEquals(240, FrameGenFpsStats.effectiveFps(60, true, 4));
    }

    @Test
    void inactiveFrameGenerationKeepsBaseFps() {
        assertEquals(60, FrameGenFpsStats.effectiveFps(60, false, 4));
        assertEquals(60, FrameGenFpsStats.effectiveFps(60, true, 1));
    }

    @Test
    void invalidBaseFpsNeverGoesNegative() {
        assertEquals(0, FrameGenFpsStats.effectiveFps(0, true, 2));
        assertEquals(0, FrameGenFpsStats.effectiveFps(-5, true, 2));
    }

    @Test
    void recordReportsOutputFpsAndTechnology() {
        FrameGenFpsStats stats = new FrameGenFpsStats(
                72, true, 3, FrameGenerationBackend.GpuVendor.NVIDIA, "");
        assertEquals(216, stats.outputFps());
        assertEquals("DLSS-G", stats.technologyName());
    }
}
