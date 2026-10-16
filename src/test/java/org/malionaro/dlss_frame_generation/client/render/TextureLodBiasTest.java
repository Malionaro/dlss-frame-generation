package org.malionaro.dlss_frame_generation.client.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class TextureLodBiasTest {
    @Test
    public void biasMatchesOutputResolutionMips() {
        assertEquals(0.0f, TextureLodBias.forRatio(1.0));
        assertEquals(-0.5f, TextureLodBias.forRatio(0.667), 1e-6f);
        assertEquals(-1.0f, TextureLodBias.forRatio(0.5), 1e-6f);
        assertEquals(-1.5f, TextureLodBias.forRatio(1.0 / 3.0), 1e-6f);
        assertEquals(-2.0f, TextureLodBias.forRatio(0.1), 1e-6f);
        assertEquals(0.0f, TextureLodBias.forRatio(Double.NaN));
    }

    @Test
    public void jitterSequenceGrowsWithUpscaleFactor() {
        assertEquals(8, VulkanRegistry.jitterPhaseCount(2560, 2560));
        assertEquals(32, VulkanRegistry.jitterPhaseCount(1280, 2560));
        assertEquals(72, VulkanRegistry.jitterPhaseCount(1280, 3840));
    }
}
