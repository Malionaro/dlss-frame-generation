/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.sky;

/**
 * User-tunable look of the generated sky, applied on top of the physical scattering:
 * colour saturation, a warm sunset band near the horizon, the strength of the glare
 * around the sun, rainbows after rain, and star brightness. Every value at its neutral
 * setting leaves the physical sky as it was, except the rainbow, which only appears
 * after rain.
 */
public final class SkyLook {

    public static final float DEFAULT_SATURATION = 1.2f;
    public static final float MAX_SATURATION = 2.0f;
    public static final float DEFAULT_SUNSET_WARMTH = 0.7f;
    public static final float MAX_SUNSET_WARMTH = 2.0f;
    public static final float DEFAULT_SUN_GLARE = 0.6f;
    public static final float MAX_SUN_GLARE = 4.0f;
    public static final float DEFAULT_RAINBOW = 1.0f;
    public static final float MAX_RAINBOW = 3.0f;
    public static final float DEFAULT_STAR_BRIGHTNESS = 1.0f;
    public static final float MAX_STAR_BRIGHTNESS = 5.0f;
    /** Off: seen from below, the streaks fan out from the zenith and the sun like smears. */
    public static final float DEFAULT_CIRRUS_COVERAGE = 0.0f;
    public static final float DEFAULT_CIRRUS_BRIGHTNESS = 1.0f;
    public static final float MAX_CIRRUS_BRIGHTNESS = 3.0f;
    public static final boolean DEFAULT_AURORA_ENABLED = true;
    public static final float DEFAULT_AURORA_INTENSITY = 1.0f;
    public static final float MAX_AURORA_INTENSITY = 4.0f;
    public static final boolean DEFAULT_AURORA_EVERYWHERE = false;
    /** How thick the air's haze over distant land is; 1 hazes land at sea level halfway by about 800 blocks. */
    public static final float DEFAULT_AERIAL_HAZE = 1.0f;
    public static final float MAX_AERIAL_HAZE = 3.0f;

    private static float saturation = DEFAULT_SATURATION;
    private static float sunsetWarmth = DEFAULT_SUNSET_WARMTH;
    private static float sunGlare = DEFAULT_SUN_GLARE;
    private static float rainbow = DEFAULT_RAINBOW;
    private static float starBrightness = DEFAULT_STAR_BRIGHTNESS;
    private static float cirrusCoverage = DEFAULT_CIRRUS_COVERAGE;
    private static float cirrusBrightness = DEFAULT_CIRRUS_BRIGHTNESS;
    private static boolean auroraEnabled = DEFAULT_AURORA_ENABLED;
    private static float auroraIntensity = DEFAULT_AURORA_INTENSITY;
    private static boolean auroraEverywhere = DEFAULT_AURORA_EVERYWHERE;
    private static float aerialHaze = DEFAULT_AERIAL_HAZE;

    private SkyLook() { }

    /** Share of the high sky the cirrus layer streaks, 0 (none) to 1. */
    public static float getCirrusCoverage() { return cirrusCoverage; }
    public static void setCirrusCoverage(float v) { cirrusCoverage = Math.clamp(v, 0.0f, 1.0f); }
    public static float getCirrusBrightness() { return cirrusBrightness; }
    public static void setCirrusBrightness(float v) { cirrusBrightness = Math.clamp(v, 0.0f, MAX_CIRRUS_BRIGHTNESS); }
    public static boolean isAuroraEnabled() { return auroraEnabled; }
    public static void setAuroraEnabled(boolean v) { auroraEnabled = v; }
    public static float getAuroraIntensity() { return auroraIntensity; }
    public static void setAuroraIntensity(float v) { auroraIntensity = Math.clamp(v, 0.0f, MAX_AURORA_INTENSITY); }
    /** Whether the aurora shows in every biome rather than only where it is cold enough to snow. */
    public static boolean isAuroraEverywhere() { return auroraEverywhere; }
    public static void setAuroraEverywhere(boolean v) { auroraEverywhere = v; }

    public static float getAerialHaze() { return aerialHaze; }
    public static void setAerialHaze(float v) { aerialHaze = Math.clamp(v, 0.0f, MAX_AERIAL_HAZE); }

    public static float getSaturation() { return saturation; }
    public static void setSaturation(float v) { saturation = Math.clamp(v, 0.0f, MAX_SATURATION); }
    public static float getSunsetWarmth() { return sunsetWarmth; }
    public static void setSunsetWarmth(float v) { sunsetWarmth = Math.clamp(v, 0.0f, MAX_SUNSET_WARMTH); }
    public static float getSunGlare() { return sunGlare; }
    public static void setSunGlare(float v) { sunGlare = Math.clamp(v, 0.0f, MAX_SUN_GLARE); }
    public static float getRainbow() { return rainbow; }
    public static void setRainbow(float v) { rainbow = Math.clamp(v, 0.0f, MAX_RAINBOW); }
    public static float getStarBrightness() { return starBrightness; }
    public static void setStarBrightness(float v) { starBrightness = Math.clamp(v, 0.0f, MAX_STAR_BRIGHTNESS); }

    /**
     * Whether a rainbow shows, from the weather over time. Rain primes it; once the rain
     * thins out it shows, and it fades over {@link #FADE_SECONDS}. A rainbow forms opposite
     * the sun, at about 42 degrees from the anti-solar point, so it is only above the
     * horizon while the sun is low; it needs the sun up.
     */
    static final class Rainbow {
        static final float FADE_SECONDS = 150.0f;
        /** Visibility steps, so a slow fade regenerates the sky a few times a second at most. */
        private static final float STEPS = 64.0f;

        private float afterRain;

        /**
         * Advances by {@code seconds} under rain of strength {@code rain} with the sun at
         * {@code sunHeight} (the y of its unit direction), and returns visibility 0 to 1.
         */
        float update(float rain, float sunHeight, float seconds) {
            if (rain > 0.5f) {
                afterRain = 1.0f;
            } else {
                afterRain = Math.max(0.0f, afterRain - Math.max(0.0f, seconds) / FADE_SECONDS);
            }
            float clearing = 1.0f - smoothstep(0.05f, 0.4f, rain);
            float lowSun = smoothstep(0.0f, 0.06f, sunHeight) * (1.0f - smoothstep(0.5f, 0.7f, sunHeight));
            return Math.round(afterRain * clearing * lowSun * STEPS) / STEPS;
        }

        void reset() {
            afterRain = 0.0f;
        }
    }

    static float smoothstep(float edge0, float edge1, float x) {
        float t = Math.clamp((x - edge0) / (edge1 - edge0), 0.0f, 1.0f);
        return t * t * (3.0f - 2.0f * t);
    }
}
