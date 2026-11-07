/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.weather;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PuddlesTest {

    @Test
    void groundGrowsWetBeforeWaterStandsOnIt() {
        assertEquals(0.0f, Puddles.wet(-2.0f), 1e-6f);
        assertEquals(1.0f, Puddles.wet(0.3f), 1e-6f);
        assertEquals(0.0f, Puddles.water(0.3f), 1e-6f);
        assertEquals(1.0f, Puddles.water(1.1f), 1e-6f);
        float previous = 0.0f;
        for (float depth = -1.0f; depth <= 1.2f; depth += 0.01f) {
            float water = Puddles.water(depth);
            assertTrue(water >= previous && water - previous < 0.05f, "no step at " + depth);
            assertTrue(water == 0.0f || Puddles.wet(depth) == 1.0f, "water only on soaked ground at " + depth);
            previous = water;
        }
    }

    @Test
    void aTurnedTopMapsOntoItsTextureTurned() {
        // A top face turned a quarter: (0,0) shows the texture's top right, (1,0) its bottom right.
        float[] x = {0f, 1f, 1f};
        float[] z = {0f, 0f, 1f};
        float[] s = {1f, 1f, 0f};
        float[] t = {0f, 1f, 1f};
        float[] map = new float[6];
        assertTrue(Puddles.groundMap(x, z, s, t, map, 0));
        // So s runs against z and t along x; the fourth corner (0, 1) shows the top left.
        assertEquals(0.0f, map[0] * 0.0f + map[1] * 1.0f + map[2], 1e-6f);
        assertEquals(0.0f, map[3] * 0.0f + map[4] * 1.0f + map[5], 1e-6f);
        assertEquals(0.5f, map[0] * 0.5f + map[1] * 0.5f + map[2], 1e-6f);
        assertEquals(0.5f, map[3] * 0.5f + map[4] * 0.5f + map[5], 1e-6f);
        assertFalse(Puddles.groundMap(new float[] {0f, 1f, 2f}, new float[] {0f, 0f, 0f}, s, t, map, 0),
                "corners in a line span nothing");
    }
}
