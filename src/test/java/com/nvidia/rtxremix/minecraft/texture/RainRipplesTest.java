/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.texture;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RainRipplesTest {

    private static final int FRAME_PX = 32;
    private static final float TILE_BLOCKS = 2f;
    private static final RainRipples RIPPLES = RainRipples.generate(FRAME_PX, TILE_BLOCKS, 7L);

    @Test
    void framesAreOctahedralNormalsOutOfTheWater() {
        byte[] rgba = new byte[FRAME_PX * FRAME_PX * 4];
        int tilted = 0;
        for (int frame = 0; frame < RainRipples.FRAMES; frame++) {
            RIPPLES.writeNormals(frame, 1f, rgba);
            for (int i = 0; i < rgba.length; i += 4) {
                float x = (rgba[i] & 0xFF) / 127.5f - 1f;
                float y = (rgba[i + 1] & 0xFF) / 127.5f - 1f;
                float z = 1f - Math.abs(x) - Math.abs(y);
                assertTrue(z > 0.3f, "normals point out of the water");
                assertEquals((byte) 0xFF, rgba[i + 3]);
                if (z < 0.98f) tilted++;
            }
        }
        assertTrue(tilted > 0, "some rings disturb the surface");
    }

    @Test
    void lighterRainTiltsTheNormalsLess() {
        byte[] strong = new byte[FRAME_PX * FRAME_PX * 4];
        byte[] light = new byte[FRAME_PX * FRAME_PX * 4];
        RIPPLES.writeNormals(9, 1f, strong);
        RIPPLES.writeNormals(9, 0.4f, light);
        for (int i = 0; i < strong.length; i += 4) {
            int strongTilt = Math.abs((strong[i] & 0xFF) - 128) + Math.abs((strong[i + 1] & 0xFF) - 128);
            int lightTilt = Math.abs((light[i] & 0xFF) - 128) + Math.abs((light[i + 1] & 0xFF) - 128);
            assertTrue(lightTilt <= strongTilt + 1, "tilt " + lightTilt + " against " + strongTilt);
        }
    }

    @Test
    void withoutRainTheWaterIsCalm() {
        byte[] normals = new byte[FRAME_PX * FRAME_PX * 4];
        for (int frame = 0; frame < RainRipples.FRAMES; frame += 7) {
            RIPPLES.writeNormals(frame, 0f, normals);
            for (int i = 0; i < normals.length; i += 4) {
                assertEquals(128, normals[i] & 0xFF);
                assertEquals(128, normals[i + 1] & 0xFF);
            }
        }
    }

    @Test
    void mostOfTheWaterStaysCalmBetweenRings() {
        int disturbed = 0, calm = 0;
        for (int frame = 0; frame < RainRipples.FRAMES; frame++) {
            byte[] rg = RIPPLES.normals(frame);
            for (int i = 0; i < rg.length; i += 2) {
                int tilt = Math.abs((rg[i] & 0xFF) - 128) + Math.abs((rg[i + 1] & 0xFF) - 128);
                if (tilt > 8) disturbed++; else calm++;
            }
        }
        assertTrue(disturbed > 0, "some rings disturb the water");
        assertTrue(calm > disturbed, disturbed + " disturbed, " + calm + " calm");
    }

    @Test
    void theLoopRunsFromItsLastFrameIntoItsFirst() {
        float[][] drops = RainRipples.drops(TILE_BLOCKS, 11L, 1f);
        float texel = TILE_BLOCKS / FRAME_PX;
        float[] start = new float[FRAME_PX * FRAME_PX];
        float[] end = new float[FRAME_PX * FRAME_PX];
        RainRipples.heightField(start, FRAME_PX, texel, drops, 0f, 1f);
        RainRipples.heightField(end, FRAME_PX, texel, drops, RainRipples.LOOP_SECONDS, 1f);
        assertArrayEquals(start, end, 1.0e-7f);
    }

    @Test
    void aRingCrossingTheTileEdgeWrapsToTheOtherSide() {
        float texel = TILE_BLOCKS / FRAME_PX;
        float[][] drop = {{0.01f, TILE_BLOCKS / 2f, 0f}};
        float[] height = new float[FRAME_PX * FRAME_PX];
        RainRipples.heightField(height, FRAME_PX, texel, drop, 0.2f, 1f);
        int row = FRAME_PX / 2;
        float lastColumn = 0f;
        for (int x = FRAME_PX - 8; x < FRAME_PX; x++) lastColumn += Math.abs(height[row * FRAME_PX + x]);
        assertTrue(lastColumn > 0f, "the ring reaches the far edge through the wrap");
    }

    @Test
    void theAnimationLoopsAtItsFrameRate() {
        assertEquals(RainRipples.FRAMES, Math.round(RainRipples.FPS * RainRipples.LOOP_SECONDS));
        assertEquals(0, RainRipples.frameAt(0));
        assertEquals(0, RainRipples.frameAt(RainRipples.LOOP_SECONDS));
        assertEquals(1, RainRipples.frameAt(1.0 / RainRipples.FPS + 1.0e-6));
        int wrapped = RainRipples.frameAt(-0.01);
        assertEquals(RainRipples.FRAMES - 1, wrapped);
    }
}
