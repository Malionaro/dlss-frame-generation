/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.lens;

/**
 * User-tunable looks drawn over the Remix image: how far the depth of field blurs what is out
 * of focus, how strongly bright lights flare, and how strongly the floors of shallow water show
 * caustics. Zero turns any of them off.
 */
public final class LensLook {

    public static final float DEFAULT_DEPTH_OF_FIELD = 0.15f;
    public static final float MAX_DEPTH_OF_FIELD = 2.0f;
    public static final float DEFAULT_FLARE = 0.2f;
    public static final float MAX_FLARE = 2.0f;
    public static final float DEFAULT_CAUSTICS = 1.0f;
    public static final float MAX_CAUSTICS = 2.0f;

    private static float depthOfField = DEFAULT_DEPTH_OF_FIELD;
    private static float flare = DEFAULT_FLARE;
    private static float caustics = DEFAULT_CAUSTICS;

    private LensLook() {}

    public static float getDepthOfField() { return depthOfField; }
    public static void setDepthOfField(float v) { depthOfField = Math.clamp(v, 0.0f, MAX_DEPTH_OF_FIELD); }
    public static float getFlare() { return flare; }
    public static void setFlare(float v) { flare = Math.clamp(v, 0.0f, MAX_FLARE); }
    public static float getCaustics() { return caustics; }
    public static void setCaustics(float v) { caustics = Math.clamp(v, 0.0f, MAX_CAUSTICS); }
}
