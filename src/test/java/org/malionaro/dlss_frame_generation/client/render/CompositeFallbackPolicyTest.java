package org.malionaro.dlss_frame_generation.client.render;

import org.junit.jupiter.api.Test;
import org.malionaro.dlss_frame_generation.client.render.CompositeFallbackPolicy.Decision;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class CompositeFallbackPolicyTest {
    @Test
    public void capturedResourcesWithSeparateTargetsNeedNoBlit() {
        assertEquals(Decision.NOT_NEEDED,
                CompositeFallbackPolicy.evaluate(true, true),
                "capture succeeded; GPU-side composite path stays in charge");
    }

    @Test
    public void failedCaptureWithReducedResolutionRequiresFallbackBlit() {
        // H4 core case: capture bailed and we render at reduced resolution,
        // so HUD would otherwise composite over a black display target.
        assertEquals(Decision.FALLBACK_BLIT_REQUIRED,
                CompositeFallbackPolicy.evaluate(false, true));
    }

    @Test
    public void capturedResourcesWithSharedTargetNeedNoBlit() {
        assertEquals(Decision.NOT_NEEDED,
                CompositeFallbackPolicy.evaluate(true, false),
                "world already renders into the display target; nothing to composite");
    }

    @Test
    public void failedCaptureWithSharedTargetNeedNoBlit() {
        assertEquals(Decision.NOT_NEEDED,
                CompositeFallbackPolicy.evaluate(false, false),
                "no separate world target exists, so there is nothing to blit");
    }
}
