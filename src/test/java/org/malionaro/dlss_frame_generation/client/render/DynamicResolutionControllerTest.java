package org.malionaro.dlss_frame_generation.client.render;

import org.junit.jupiter.api.Test;
import org.malionaro.dlss_frame_generation.client.render.DynamicResolutionController;
import org.malionaro.dlss_frame_generation.client.render.NvidiaDlssRenderResolution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DynamicResolutionControllerTest {
    @Test
    public void sustainedLoadReducesResolutionAndRecoveryIsConservative() {
        DynamicResolutionController controller =
                new DynamicResolutionController();
        NvidiaDlssRenderResolution.Resolution base =
                NvidiaDlssRenderResolution.resolveTemporal(
                        1920, 1080, 1, true
                );

        for (int index = 0; index < 36; index++) {
            controller.recordFrameTime(20.0f, 120, true);
        }
        NvidiaDlssRenderResolution.Resolution reduced =
                controller.apply(base, true);
        assertTrue(reduced.renderWidth() < 1920);
        assertTrue(reduced.renderHeight() < 1080);
        assertEquals(0, reduced.renderWidth() % 8);
        assertEquals(0, reduced.renderHeight() % 8);

        float loadedScale = controller.scale();
        for (int index = 0; index < 12; index++) {
            controller.recordFrameTime(2.0f, 120, true);
        }
        assertTrue(controller.scale() <= loadedScale + 0.0251f);
    }

    @Test
    public void disabledControllerReturnsBaseAndResetsScale() {
        DynamicResolutionController controller =
                new DynamicResolutionController();
        for (int index = 0; index < 40; index++) {
            controller.recordFrameTime(40.0f, 144, true);
        }
        controller.recordFrameTime(16.0f, 144, false);
        NvidiaDlssRenderResolution.Resolution base =
                NvidiaDlssRenderResolution.resolveTemporal(
                        2560, 1440, 2, true
                );
        assertEquals(base, controller.apply(base, false));
        assertEquals(1.0f, controller.scale());
    }

    @Test
    public void temporalModesResolveToExpectedOrdering() {
        NvidiaDlssRenderResolution.Resolution quality =
                NvidiaDlssRenderResolution.resolveTemporal(
                        1920, 1080, 2, true
                );
        NvidiaDlssRenderResolution.Resolution performance =
                NvidiaDlssRenderResolution.resolveTemporal(
                        1920, 1080, 4, true
                );
        assertTrue(quality.renderWidth() > performance.renderWidth());
        assertTrue(quality.reduced());
        assertTrue(performance.reduced());
    }

    @Test
    public void dynamicScaleNeverDropsBelowUltraPerformanceRatio() {
        DynamicResolutionController controller =
                new DynamicResolutionController();
        for (int index = 0; index < 200; index++) {
            controller.recordFrameTime(80.0f, 144, true);
        }
        NvidiaDlssRenderResolution.Resolution ultraPerformance =
                NvidiaDlssRenderResolution.resolveTemporal(
                        1920, 1080, 5, true
                );
        NvidiaDlssRenderResolution.Resolution result =
                controller.apply(ultraPerformance, true);
        assertTrue(result.renderWidth() >= 640);
        assertTrue(result.renderHeight() >= 360);
    }
}
