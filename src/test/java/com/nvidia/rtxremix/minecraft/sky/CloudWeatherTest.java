/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.sky;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CloudWeatherTest {
    private static final long DAY = 24000L;
    private static final int SALT = 21;

    @Test
    void aDaysMoodHoldsFromDawnToDusk() {
        for (long day = 0; day < 20; day++) {
            double dawn = CloudWeather.mood(day * DAY, SALT);
            assertEquals(dawn, CloudWeather.mood(day * DAY + 6000L, SALT), 1.0e-12);
            assertEquals(dawn, CloudWeather.mood(day * DAY + 12000L, SALT), 1.0e-12);
        }
    }

    @Test
    void theMoodTurnsIntoTheNextDaysOvernightWithoutAJump() {
        long day = 3L;
        double evening = CloudWeather.mood(day * DAY + 12000L, SALT);
        double nextMorning = CloudWeather.mood((day + 1) * DAY, SALT);
        assertEquals(evening, CloudWeather.mood(day * DAY + 13000L, SALT), 1.0e-12);
        assertEquals(nextMorning, CloudWeather.mood(day * DAY + 18000L, SALT), 1.0e-12);
        double previous = evening;
        for (long tick = day * DAY + 13000L; tick <= day * DAY + 18000L; tick += 50L) {
            double mood = CloudWeather.mood(tick, SALT);
            assertTrue(Math.abs(mood - previous) < 0.02, "the mood jumped at " + tick);
            previous = mood;
        }
    }

    @Test
    void theTwoMoodsAreIndependent() {
        int differing = 0;
        for (long day = 0; day < 20; day++) {
            if (Math.abs(CloudWeather.mood(day * DAY, 21) - CloudWeather.mood(day * DAY, 22)) > 0.2) {
                differing++;
            }
        }
        assertTrue(differing > 5);
    }

    @Test
    void theClosingAheadOfTheRainEasesAfterAJumpInTheTimer() {
        assertEquals(0.7, CloudWeather.ease(Double.NaN, 0.7, 0.01), 1.0e-12);
        double ahead = 1.0;
        for (int tick = 0; tick < 100; tick++) {
            ahead = CloudWeather.ease(ahead, 0.0, 1.0 / 2400.0);
        }
        assertEquals(1.0 - 100.0 / 2400.0, ahead, 1.0e-9);
        assertEquals(0.5, CloudWeather.ease(0.4, 0.5, 0.2), 1.0e-12);
    }
}
