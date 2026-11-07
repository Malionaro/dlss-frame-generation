/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.weather;

/**
 * Where rain pools on open ground, as a noise field that tiles every {@link #TILE_BLOCKS} blocks.
 *
 * <p>Three octaves of value noise, finer ones weighing less, sum to between 0 and 16. As
 * the ground gets wetter the sum is scaled up, so puddles first appear where the noise
 * peaks and spread outward from there, and dry back the same way. Their edges fade over a
 * band of the sum rather than stopping, so a puddle thins into wet ground.
 */
final class PuddleMask {

    static final int TILE_BLOCKS = 32;
    static final int TEXELS_PER_BLOCK = 16;
    static final int SIZE = TILE_BLOCKS * TEXELS_PER_BLOCK;
    /** Lattice cells across the tile per octave, about 1.6, 3.2 and 6.4 blocks apart; each divides the tile. */
    private static final int[] CELLS = {20, 10, 5};
    private static final float[] WEIGHTS = {3.0f, 5.0f, 8.0f};
    /** The largest noise sum, the weights' total. */
    static final float MAX_SUM = 16.0f;
    /** Sum above which water stands once the ground is soaked; half the range. */
    private static final float THRESHOLD = 7.0f;
    /** Width of the band of the sum a puddle's edge fades over. */
    private static final float EDGE = 2.5f;

    private PuddleMask() {
    }

    /** The noise sum, 0 to 16, of every texel of the tile. */
    static float[] noiseSum(long seed) {
        float[] sum = new float[SIZE * SIZE];
        for (int octave = 0; octave < CELLS.length; octave++) {
            int cells = CELLS[octave];
            float cellTexels = SIZE / (float) cells;
            long octaveSeed = seed + octave * 0x9E3779B97F4A7C15L;
            for (int y = 0; y < SIZE; y++) {
                float fy = (y + 0.5f) / cellTexels;
                int cy = (int) fy;
                float ty = smooth(fy - cy);
                for (int x = 0; x < SIZE; x++) {
                    float fx = (x + 0.5f) / cellTexels;
                    int cx = (int) fx;
                    float tx = smooth(fx - cx);
                    float a = lattice(cx % cells, cy % cells, octaveSeed);
                    float b = lattice((cx + 1) % cells, cy % cells, octaveSeed);
                    float c = lattice(cx % cells, (cy + 1) % cells, octaveSeed);
                    float d = lattice((cx + 1) % cells, (cy + 1) % cells, octaveSeed);
                    float top = a + (b - a) * tx;
                    float bottom = c + (d - c) * tx;
                    sum[y * SIZE + x] += WEIGHTS[octave] * (top + (bottom - top) * ty);
                }
            }
        }
        return sum;
    }

    /**
     * How much water stands at a texel whose noise sum is {@code sum}, 0 to 1, when the ground
     * is {@code wetness} wet, 0 to 1. Dry ground holds none anywhere.
     */
    static float puddle(float sum, float wetness) {
        return Math.clamp(depth(sum, wetness), 0.0f, 1.0f);
    }

    /**
     * {@link #puddle} before it is clamped: below 0 on ground that holds no water, by how far
     * it falls short in widths of a puddle's edge.
     */
    static float depth(float sum, float wetness) {
        return (sum * scale(wetness) - THRESHOLD) / EDGE;
    }

    /**
     * The noise sum above which {@link #puddle} exceeds {@code threshold} at {@code wetness};
     * above the largest sum, so no texel, while the ground is too dry for any.
     */
    static float sumThreshold(float threshold, float wetness) {
        return (THRESHOLD + EDGE * threshold) / scale(wetness);
    }

    /** How far wetness stretches the noise sum toward the threshold. */
    private static float scale(float wetness) {
        float w = Math.clamp(wetness, 0.0f, 1.0f);
        return 0.4375f + 0.5625f * w * (2.0f - w);
    }

    private static float smooth(float t) {
        return t * t * (3.0f - 2.0f * t);
    }

    private static float lattice(int x, int y, long seed) {
        long h = seed ^ x * 0xC2B2AE3D27D4EB4FL ^ y * 0x165667B19E3779F9L;
        h = (h ^ (h >>> 31)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 29)) * 0x94D049BB133111EBL;
        h ^= h >>> 32;
        return (h >>> 40) / (float) (1L << 24);
    }
}
