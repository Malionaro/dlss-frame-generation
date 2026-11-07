/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.texture;

import java.util.Arrays;
import java.util.SplittableRandom;

/**
 * A looping animation of raindrop rings on still water, one frame per {@link #FPS}.
 *
 * <p>Each frame tiles {@code tileBlocks} blocks and wraps at its edges, so world-space
 * UVs repeat it seamlessly. Drops land at random places and times within one loop; a
 * drop whose ring is still spreading when the loop ends carries over into the frames at
 * its start, so the last frame runs into the first. Every frame is kept finished at full
 * strength; while the rain comes and goes, the rings are scaled down from those.
 */
public final class RainRipples {

    public static final int FRAMES = 64;
    public static final float LOOP_SECONDS = 2f;
    public static final int FPS = Math.round(FRAMES / LOOP_SECONDS);

    /** Drops landing per square block per second in a steady rain. */
    private static final float DROPS_PER_BLOCK_SECOND = 16f;
    // Stylised rather than real drop sizes: a wavelength under about three texels would alias
    // into noise at the water texture's density.
    private static final float RING_SPEED = 0.7f;    // blocks per second
    private static final float LIFETIME = 0.55f;     // seconds
    private static final float WAVELENGTH = 0.2f;    // blocks
    private static final float AMPLITUDE = 0.016f;   // blocks of surface height
    private static final float BASE_WIDTH = 0.05f;   // blocks
    private static final float WIDTH_GROWTH = 0.15f; // added width per block of radius

    private final int framePx;
    /**
     * Per frame, octahedrally encoded tangent-space normals at full strength, as red-green
     * byte pairs; see {@link #writeNormals}.
     */
    private final byte[][] normals;

    private RainRipples(int framePx, byte[][] normals) {
        this.framePx = framePx;
        this.normals = normals;
    }

    /** Simulates every frame of the loop over a tile {@code framePx} texels across. */
    public static RainRipples generate(int framePx, float tileBlocks, long seed) {
        return generate(framePx, tileBlocks, seed, 1f, 1f);
    }

    /**
     * Simulates every frame of the loop, its rings {@code size} times as large, with the
     * slopes they had, and {@code density} times as many drops.
     */
    public static RainRipples generate(int framePx, float tileBlocks, long seed, float size, float density) {
        float[][] drops = drops(tileBlocks, seed, density);
        float texel = tileBlocks / framePx;
        float[] height = new float[framePx * framePx];
        byte[][] normals = new byte[FRAMES][];
        for (int frame = 0; frame < FRAMES; frame++) {
            heightField(height, framePx, texel, drops, frame * (LOOP_SECONDS / FRAMES), size);
            normals[frame] = new byte[framePx * framePx * 2];
            finish(height, framePx, texel, normals[frame]);
        }
        return new RainRipples(framePx, normals);
    }

    public int framePx() {
        return framePx;
    }

    /** Frame {@code frame}'s full-strength normals as red-green byte pairs; not to be modified. */
    byte[] normals(int frame) {
        return normals[frame];
    }

    /** The frame showing at {@code seconds} into the animation. */
    public static int frameAt(double seconds) {
        return (int) Math.floorMod((long) Math.floor(seconds * FPS), (long) FRAMES);
    }

    /**
     * Writes {@code frame} as tangent-space normals, the rings' slopes scaled by
     * {@code strength}, octahedrally encoded in the red and green of RGBA8 texels: the
     * runtime's own normal encoding, which a texture update must already be in, because
     * the runtime converts a normal map only when the texture is created.
     */
    public void writeNormals(int frame, float strength, byte[] rgba) {
        byte[] full = normals[frame];
        float scale = Math.max(0f, Math.min(1f, strength));
        for (int i = 0; i < full.length / 2; i++) {
            // An upper-hemisphere normal is proportional to (px, py, 1 - |px| - |py|); scaling its
            // slopes by s scales px and py by s and re-projects onto the octahedron.
            float px = decode(full[i * 2]), py = decode(full[i * 2 + 1]);
            float z = Math.max(0f, 1f - Math.abs(px) - Math.abs(py));
            float sx = px * scale, sy = py * scale;
            float norm = Math.abs(sx) + Math.abs(sy) + z;
            rgba[i * 4] = encode(sx / norm);
            rgba[i * 4 + 1] = encode(sy / norm);
            rgba[i * 4 + 2] = 0;
            rgba[i * 4 + 3] = (byte) 0xFF;
        }
    }

    /** Each drop as {x, z, landing time}, in blocks and seconds within one loop. */
    static float[][] drops(float tileBlocks, long seed, float density) {
        SplittableRandom random = new SplittableRandom(seed);
        int count = Math.max(1, Math.round(DROPS_PER_BLOCK_SECOND * density * tileBlocks * tileBlocks * LOOP_SECONDS));
        float[][] drops = new float[count][];
        for (int i = 0; i < count; i++) {
            drops[i] = new float[] {
                    (float) random.nextDouble() * tileBlocks,
                    (float) random.nextDouble() * tileBlocks,
                    (float) random.nextDouble() * LOOP_SECONDS};
        }
        return drops;
    }

    /** Surface height in blocks over one frame, wrapping at the tile's edges. */
    static void heightField(float[] height, int framePx, float texel, float[][] drops, float time, float size) {
        Arrays.fill(height, 0f);
        for (float[] drop : drops) {
            float age = time - drop[2];
            if (age < 0f) age += LOOP_SECONDS;
            if (age >= LIFETIME) continue;
            float radius = RING_SPEED * size * age;
            float width = BASE_WIDTH * size + WIDTH_GROWTH * radius;
            float fade = 1f - age / LIFETIME;
            float amplitude = AMPLITUDE * size * fade * fade;
            float wavelength = WAVELENGTH * size;
            float reach = radius + 3f * width;
            int span = (int) Math.ceil(reach / texel);
            int centerX = (int) Math.floor(drop[0] / texel);
            int centerZ = (int) Math.floor(drop[1] / texel);
            for (int dz = -span; dz <= span; dz++) {
                for (int dx = -span; dx <= span; dx++) {
                    float px = (centerX + dx + 0.5f) * texel;
                    float pz = (centerZ + dz + 0.5f) * texel;
                    float r = (float) Math.hypot(px - drop[0], pz - drop[1]);
                    float offset = r - radius;
                    if (Math.abs(offset) > 3f * width) continue;
                    float envelope = (float) Math.exp(-(offset * offset) / (width * width));
                    float wave = (float) Math.cos(2.0 * Math.PI * offset / wavelength);
                    int x = Math.floorMod(centerX + dx, framePx);
                    int z = Math.floorMod(centerZ + dz, framePx);
                    height[z * framePx + x] += amplitude * envelope * wave;
                }
            }
        }
    }

    /** Turns a height field into full-strength normals, by central differences. */
    private static void finish(float[] height, int framePx, float texel, byte[] normals) {
        for (int z = 0; z < framePx; z++) {
            for (int x = 0; x < framePx; x++) {
                float left = height[z * framePx + Math.floorMod(x - 1, framePx)];
                float right = height[z * framePx + Math.floorMod(x + 1, framePx)];
                float up = height[Math.floorMod(z - 1, framePx) * framePx + x];
                float down = height[Math.floorMod(z + 1, framePx) * framePx + x];
                float sx = (right - left) / (2f * texel);
                float sy = (down - up) / (2f * texel);
                // The normal (-sx, -sy, 1), projected onto the octahedron |x| + |y| + |z| = 1.
                float norm = Math.abs(sx) + Math.abs(sy) + 1f;
                int i = z * framePx + x;
                normals[i * 2] = encode(-sx / norm);
                normals[i * 2 + 1] = encode(-sy / norm);
            }
        }
    }

    private static float decode(byte component) {
        return (component & 0xFF) / 127.5f - 1f;
    }

    private static byte encode(float component) {
        return (byte) Math.round((component * 0.5f + 0.5f) * 255f);
    }
}
