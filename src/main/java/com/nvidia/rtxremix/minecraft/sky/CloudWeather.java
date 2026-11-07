/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.sky;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.saveddata.WeatherData;

/**
 * The weather the Unbound clouds show, which changes from day to day and around the rain: how
 * much of the sky each layer covers, and how calm the day is. Every layer keeps the share of
 * the Cloud Coverage setting it has always had, and each day's two moods, which turn into the
 * next day's overnight, move it a little either way: how unstable the air is grows the
 * cumulus, and how moist it is spreads the large cumulus and the altostratus. Before the rain the large cumulus thicken and the
 * altostratus draws over; after it broken cumulus linger while the sheet has gone.
 *
 * <p>The day and the hour come from the world's clock, so every player sees the same sky.
 * Only the integrated server can say when the rain will come, so the sky closes ahead of it in
 * single-player alone; the clearing after it runs from when this client saw the rain end. A
 * clear spell set by command shows neither. Render thread only; it reads the integrated
 * server's weather timers without a lock, which only ever lags them by a tick.
 */
final class CloudWeather {

    private static final long TICKS_PER_DAY = 24000L;
    /** Ticks before the rain over which the sky closes, a quarter of a day and more. */
    private static final double AHEAD_TICKS = 9000.0;
    /** Ticks after the rain over which the sky clears again. */
    private static final double AFTER_TICKS = 9000.0;
    /**
     * Ticks the closing ahead of the rain takes at least to go all the way: the server's timer
     * jumps when the rain ends, for a tick after a night is slept through, and on a weather
     * command, and the sky turned into other clouds at once each time.
     */
    private static final double AHEAD_EASE_TICKS = 2400.0;
    /** Rain at which the sky counts as rained on, for the clearing after it. */
    private static final float WET_RAIN = 0.2f;
    private static final int UNSTABLE_SALT = 21;
    private static final int MOIST_SALT = 22;
    /** The large cumulus's and the altostratus's coverage on an ordinary day, as shares of the setting. */
    private static final double LARGE_SHARE = 0.8;
    private static final double ALTO_SHARE = 0.55;
    /** The coverage the rain closes every layer toward. */
    private static final double OVERCAST = 0.95;

    /** Coverage of the small cumulus, the large cumulus and the altostratus, before the rain closes them. */
    final float[] coverage = new float[3];
    /** How settled the day is, 0 to 1: still enough for a figure to hold together. */
    float calm;

    private int levelIdentity;
    private long lastWetTick = Long.MIN_VALUE;
    private double ahead = Double.NaN;
    private long aheadTick;

    /**
     * Works out this moment's weather.
     *
     * @param rain the rain's strength, 0 to 1
     * @param thunder the thunder's strength, 0 to 1
     * @param coverageSetting the Cloud Coverage setting, the small cumulus's coverage on an
     *                        ordinary day
     */
    void update(ClientLevel level, float rain, float thunder, float coverageSetting) {
        long tick = level.getGameTime();
        int identity = System.identityHashCode(level);
        if (identity != levelIdentity || (lastWetTick != Long.MIN_VALUE && tick < lastWetTick) || tick < aheadTick) {
            levelIdentity = identity;
            lastWetTick = Long.MIN_VALUE;
            ahead = Double.NaN;
        }
        if (rain > WET_RAIN) {
            lastWetTick = tick;
        }
        long clock = level.getOverworldClockTime();
        double unstable = mood(clock, UNSTABLE_SALT);
        double moist = mood(clock, MOIST_SALT);
        double untilRain = ticksUntilRain(level);
        boolean commandedClear = untilRain < 0.0;
        ahead = ease(ahead, commandedClear ? 0.0 : smoothstep(AHEAD_TICKS, 0.0, untilRain),
                (tick - aheadTick) / AHEAD_EASE_TICKS);
        aheadTick = tick;
        double after = commandedClear || lastWetTick == Long.MIN_VALUE
                ? 0.0 : 1.0 - Math.clamp((tick - lastWetTick) / AFTER_TICKS, 0.0, 1.0);

        double small = coverageSetting * (0.8 + 0.4 * unstable);
        double large = coverageSetting * LARGE_SHARE * (0.75 + 0.5 * moist);
        double alto = coverageSetting * ALTO_SHARE * (0.6 + 0.8 * moist);
        // Ahead of the rain the large cumulus and the sheet close in; after it the cumulus
        // linger broken while the sheet has gone.
        small += (OVERCAST - small) * (0.3 * ahead + 0.25 * after);
        large += (OVERCAST - large) * (0.6 * ahead + 0.3 * after);
        alto += (OVERCAST - alto) * 0.7 * ahead;
        coverage[0] = (float) Math.clamp(small, 0.0, 1.0);
        coverage[1] = (float) Math.clamp(large, 0.0, 1.0);
        coverage[2] = (float) Math.clamp(alto, 0.0, 1.0);
        calm = (float) ((1.0 - Math.max(rain, thunder)) * (1.0 - smoothstep(0.3, 0.6, unstable))
                * (1.0 - smoothstep(0.4, 0.7, moist)) * (1.0 - ahead) * (1.0 - after));
    }

    /** {@code value} moved toward {@code target} by at most {@code step}; the target when the value is unset. */
    static double ease(double value, double target, double step) {
        if (Double.isNaN(value)) return target;
        return value + Math.clamp(target - value, -step, step);
    }

    /**
     * One of the day's moods at a clock time, 0 to 1. Each day's holds through its daylight and
     * turns into the next between dusk and midnight, in the dark.
     */
    static double mood(long clock, int salt) {
        // Days counted from midnight, clock 18000.
        long fromMidnight = clock + 6000L;
        long day = Math.floorDiv(fromMidnight, TICKS_PER_DAY);
        double hour = Math.floorMod(fromMidnight, TICKS_PER_DAY) / (double) TICKS_PER_DAY;
        double turn = smoothstep(0.8, 1.0, hour);
        double today = CloudShapes.hash(0, 0, day, salt);
        return today + (CloudShapes.hash(0, 0, day + 1, salt) - today) * turn;
    }

    /**
     * Ticks until the rain starts, read from the integrated server: 0 while it rains, infinite
     * when it cannot be known or the weather does not advance, and negative during a clear
     * spell set by command.
     */
    private static double ticksUntilRain(ClientLevel level) {
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null) {
            return Double.POSITIVE_INFINITY;
        }
        ServerLevel serverLevel = server.getLevel(level.dimension());
        if (serverLevel == null || !serverLevel.getGameRules().get(GameRules.ADVANCE_WEATHER)) {
            return Double.POSITIVE_INFINITY;
        }
        WeatherData weather = server.getWeatherData();
        if (weather.isRaining()) {
            return 0.0;
        }
        return weather.getClearWeatherTime() > 0 ? -1.0 : weather.getRainTime();
    }

    private static double smoothstep(double edge0, double edge1, double x) {
        double t = Math.clamp((x - edge0) / (edge1 - edge0), 0.0, 1.0);
        return t * t * (3.0 - 2.0 * t);
    }
}
