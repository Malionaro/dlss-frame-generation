package org.malionaro.dlss_frame_generation.client.motion;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EntityTextureColorsTest {
    @Test
    void tintMultipliesEachChannel() {
        assertEquals(0x804020, EntityTextureColors.multiply(0xFF8040, 0xFF808080));
        assertEquals(0x123456, EntityTextureColors.multiply(0x123456, 0xFFFFFFFF));
    }
}
