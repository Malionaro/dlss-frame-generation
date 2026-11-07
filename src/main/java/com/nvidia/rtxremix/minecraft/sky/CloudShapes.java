/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.sky;

/**
 * The shaped clouds of the Unbound field, as cloud_field.glsl reads them: now and then, on a
 * calm day, a cloud in the shape of a creeper's head. Each is a function of its cell in the
 * clouds' wrapped frame, the weather ({@link CloudWeather}) and the world's day, so every client
 * builds the same one with no state of its own, and it forms and dissolves over the day instead
 * of appearing at once.
 *
 * <p>A shape is a head inside a bounding sphere the march clips its ray to. Render thread only.
 */
public final class CloudShapes {

    public static final int MAX_SHAPES = 1;
    private static final float KIND_CREEPER = 1.0f;
    /** Cells along each side of the wrap period, so cells line up across the wrap: 4500 blocks each. */
    private static final int CELLS_PER_PERIOD = 40;
    /** The chance a calm cell holds a creeper's head on a given day. */
    private static final double CREEPER_CHANCE = 0.03;
    /** Blocks of a creeper's head's half size, at cloud size 1. */
    private static final float CREEPER_SIZE = 64.0f;

    /** Per shape, four floats each, as the shader's uniform arrays of the same names. */
    final float[] bounds = new float[MAX_SHAPES * 4];
    final float[] form = new float[MAX_SHAPES * 4];
    final float[] size = new float[MAX_SHAPES * 4];
    int count;

    private final float[] candidateDistance = new float[9];
    private final int[] candidateOrder = new int[9];

    /**
     * Rebuilds the shapes near the camera.
     *
     * @param cameraX the camera in the clouds' wrapped frame
     * @param period blocks the frame wraps at
     * @param base the small cumulus layer's base
     * @param thickness the large cumulus layer's thickness
     * @param scale the cloud size
     * @param calm how settled the day is, 0 to 1
     * @param days the world's time in days
     */
    void update(double cameraX, double cameraZ, double period, float base, float thickness, float scale,
                float calm, double days) {
        double cell = period / CELLS_PER_PERIOD;
        int centreX = (int) Math.floor(cameraX / cell);
        int centreZ = (int) Math.floor(cameraZ / cell);
        // The nine cells around the camera, nearest first.
        for (int i = 0; i < 9; i++) {
            double x = (centreX + i % 3 - 1 + 0.5) * cell - cameraX;
            double z = (centreZ + i / 3 - 1 + 0.5) * cell - cameraZ;
            candidateDistance[i] = (float) (x * x + z * z);
            candidateOrder[i] = i;
        }
        for (int i = 1; i < 9; i++) {
            for (int j = i; j > 0 && candidateDistance[candidateOrder[j]] < candidateDistance[candidateOrder[j - 1]]; j--) {
                int swap = candidateOrder[j];
                candidateOrder[j] = candidateOrder[j - 1];
                candidateOrder[j - 1] = swap;
            }
        }
        count = 0;
        long day = (long) Math.floor(days);
        float strength = (float) (lifecycle(days - day) * Math.max(calm - 0.5f, 0.0f) * 2.0f);
        for (int k = 0; k < 9 && count < MAX_SHAPES && strength > 0.02f; k++) {
            int i = candidateOrder[k];
            int cellX = centreX + i % 3 - 1;
            int cellZ = centreZ + i / 3 - 1;
            int keyX = Math.floorMod(cellX, CELLS_PER_PERIOD);
            int keyZ = Math.floorMod(cellZ, CELLS_PER_PERIOD);
            if (hash(keyX, keyZ, day, 7) < CREEPER_CHANCE) {
                addCreeper(cellX * cell + cell * (0.3 + 0.4 * hash(keyX, keyZ, day, 8)),
                        cellZ * cell + cell * (0.3 + 0.4 * hash(keyX, keyZ, day, 9)),
                        base + thickness * 2.2f, CREEPER_SIZE * scale,
                        (float) (Math.floor(hash(keyX, keyZ, day, 10) * 4.0) * Math.PI * 0.5), strength);
            }
        }
    }

    private void addCreeper(double x, double z, float height, float halfSize, float facing, float strength) {
        int shape = count;
        double centreY = height + halfSize * 1.2;
        put(bounds, shape, x, centreY, z, halfSize * 1.8);
        put(form, shape, KIND_CREEPER, 0, 0, centreY - halfSize);
        put(size, shape, halfSize * 2.0, halfSize, facing, strength);
        count++;
    }

    private static void put(float[] array, int index, double x, double y, double z, double w) {
        array[index * 4] = (float) x;
        array[index * 4 + 1] = (float) y;
        array[index * 4 + 2] = (float) z;
        array[index * 4 + 3] = (float) w;
    }

    /** A shape's strength through its life, 0 to 1: it forms, holds, and dissolves. */
    static double lifecycle(double life) {
        return smoothstep(0.0, 0.25, life) * (1.0 - smoothstep(0.75, 1.0, life));
    }

    /** A number in [0, 1) for a cell, a step of time and a purpose, the same on every client. */
    static double hash(int x, int z, long step, int salt) {
        long h = x * 0x9E3779B97F4A7C15L ^ z * 0xC2B2AE3D27D4EB4FL ^ step * 0x165667B19E3779F9L ^ salt * 0x27D4EB2F165667C5L;
        h ^= h >>> 31;
        h *= 0x7FB5D329728EA185L;
        h ^= h >>> 27;
        h *= 0x81DADEF4BC2DD44DL;
        h ^= h >>> 33;
        return (h >>> 11) * 0x1.0p-53;
    }

    private static double smoothstep(double edge0, double edge1, double x) {
        double t = Math.clamp((x - edge0) / (edge1 - edge0), 0.0, 1.0);
        return t * t * (3.0 - 2.0 * t);
    }
}
