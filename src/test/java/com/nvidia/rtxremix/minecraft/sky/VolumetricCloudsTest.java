/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.sky;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VolumetricCloudsTest {

    private static final int WIDTH = VolumetricCloudGenerator.WIDTH;
    private static final int HEIGHT = VolumetricCloudGenerator.HEIGHT;

    /** A readback as the generator returns it: RGBA half floats in native order. */
    private static ByteBuffer readback() {
        return ByteBuffer.allocate(VolumetricCloudGenerator.OUTPUT_BYTES).order(ByteOrder.nativeOrder());
    }

    private static void setOpacity(ByteBuffer pixels, int x, int y, float opacity) {
        pixels.putShort(((y * WIDTH + x) * 4 + 3) * Short.BYTES, Float.floatToFloat16(opacity));
    }

    @Test
    void theLightIsShadedByTheCloudInItsDirectionOnly() {
        ByteBuffer pixels = readback();
        // An opaque cloud across the panorama's top rows, around the zenith.
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < WIDTH; x++) {
                setOpacity(pixels, x, y, 1.0f);
            }
        }
        assertEquals(1.0f, VolumetricClouds.opacityToward(pixels, new float[] {0.0f, 1.0f, 0.0f}), 1e-6f);
        assertEquals(0.0f, VolumetricClouds.opacityToward(pixels, new float[] {1.0f, 0.2f, 0.0f}), 1e-6f);
        assertEquals(0.0f, VolumetricClouds.opacityToward(pixels, new float[] {0.0f, -0.5f, 1.0f}), 1e-6f);
    }

    @Test
    void thePanoramaRowsRunFromTheZenithThroughTheSplitToTheNadir() {
        assertEquals(90.0, VolumetricCloudGenerator.elevationDegrees(0.0), 1e-9);
        assertEquals(-90.0, VolumetricCloudGenerator.elevationDegrees(1.0), 1e-9);
        double split = VolumetricCloudGenerator.UPPER_ROWS / (double) HEIGHT;
        assertEquals(VolumetricCloudGenerator.MIN_ELEVATION_DEGREES,
                VolumetricCloudGenerator.elevationDegrees(split), 1e-9);
        for (double v = 0.0; v <= 1.0; v += 0.01) {
            assertEquals(v, VolumetricCloudGenerator.rowCoordinate(VolumetricCloudGenerator.elevationDegrees(v)), 1e-9);
        }
    }

    @Test
    void anOpacityTowardTheEastReadsTheQuarterTurnColumn() {
        ByteBuffer pixels = readback();
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = WIDTH / 4 - 4; x <= WIDTH / 4 + 4; x++) {
                setOpacity(pixels, x, y, 0.5f);
            }
        }
        float[] east = {(float) Math.cos(Math.toRadians(30.0)), (float) Math.sin(Math.toRadians(30.0)), 0.0f};
        assertEquals(0.5f, VolumetricClouds.opacityToward(pixels, east), 1e-3f);
        assertEquals(0.0f, VolumetricClouds.opacityToward(pixels, new float[] {-east[0], east[1], 0.0f}), 1e-6f);
    }

    @Test
    void everyNoisePeriodDividesTheWrap() throws IOException {
        String source;
        try (InputStream in = VolumetricCloudsTest.class.getResourceAsStream(
                "/assets/rtx-remix-renderer/shaders/cloud_field.glsl")) {
            source = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        Matcher period = Pattern.compile("const float (\\w+_PERIOD) = ([0-9.]+)(?: / ([0-9.]+))?;").matcher(source);
        int found = 0;
        while (period.find()) {
            double blocks = Double.parseDouble(period.group(2))
                    / (period.group(3) == null ? 1.0 : Double.parseDouble(period.group(3)));
            double tiles = VolumetricClouds.WRAP_PERIOD / blocks;
            assertEquals(Math.rint(tiles), tiles, 1e-6, period.group(1) + " does not divide the wrap");
            found++;
        }
        assertTrue(found >= 7, "found " + found + " periods");
    }
}
