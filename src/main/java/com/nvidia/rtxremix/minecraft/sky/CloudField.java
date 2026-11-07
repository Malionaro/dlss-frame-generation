/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.sky;

import org.lwjgl.opengl.GL20;
import org.lwjgl.system.MemoryStack;

import java.io.IOException;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;

/**
 * The Unbound clouds as the lens needs them to march them per pixel: the shader code of the
 * density field, lighting and march, the noise and cirrus textures it reads, the panorama the
 * dome shows, the atmosphere behind it, and this frame's inputs. {@link VolumetricClouds} publishes it while the clouds
 * draw and withdraws it when they stop, so the lens marches the same clouds the dome shows,
 * lit the same way, and can take the dome's own out of the image where it draws its own.
 *
 * <p>Render thread only.
 */
public final class CloudField {

    private static final String SOURCE_RESOURCE = "/assets/rtx-remix-renderer/shaders/cloud_field.glsl";
    private static final String INCLUDE = "#include \"cloud_field.glsl\"";

    /** The camera in the clouds' wrapped frame, x, y and z. */
    private static final float[] CAMERA = new float[3];
    /**
     * CloudBase, CloudThickness, the three of LayerCoverage, Density, FeatureScale and Detail, as
     * the shader names them.
     */
    private static final float[] LAYERS = new float[8];
    /** LightDirection, LightIrradiance, AmbientTop and AmbientBottom, three each. */
    private static final float[] LIGHT = new float[12];
    /** A lightning flash's position in the clouds' frame and its irradiance, as Flash. */
    private static final float[] FLASH = new float[4];
    private static String source;
    private static int noiseTexture;
    private static int cirrusTexture;
    private static int panoramaTexture;
    private static int skyTexture;
    private static float skyRadiance;
    private static float brightness;
    private static float cirrusAngle;
    private static float cirrusStrength;
    private static float shadowStrength;
    private static float hazeDistance = 1.0f;
    private static boolean framed;
    private static CloudShapes shapes;

    private CloudField() {}

    /** The field's GLSL; see cloud_field.glsl for what an includer declares. */
    public static String source() {
        if (source == null) {
            try (InputStream stream = CloudField.class.getResourceAsStream(SOURCE_RESOURCE)) {
                if (stream == null) {
                    throw new IllegalStateException("Missing cloud field shader " + SOURCE_RESOURCE);
                }
                source = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to read cloud field shader " + SOURCE_RESOURCE, e);
            }
        }
        return source;
    }

    /** {@code shader} with its include line replaced by the field. */
    static String expand(String shader) {
        if (!shader.contains(INCLUDE)) {
            throw new IllegalStateException("Shader does not include cloud_field.glsl");
        }
        return shader.replace(INCLUDE, source());
    }

    /**
     * GLSL defines for the panorama's layout, as {@link VolumetricCloudGenerator#rowCoordinate}
     * lays it out (CLOUD_WIDTH, CLOUD_HEIGHT, CLOUD_UPPER_ROWS and MIN_ELEVATION_DEGREES), for
     * the cirrus layer's lowest elevation (CIRRUS_MIN_ELEVATION_DEGREES), and for the most
     * shaped clouds {@link CloudShapes} hands over (CLOUD_MAX_SHAPES).
     */
    public static String defines() {
        return "#define CLOUD_WIDTH " + VolumetricCloudGenerator.WIDTH + "\n"
                + "#define CLOUD_HEIGHT " + VolumetricCloudGenerator.HEIGHT + "\n"
                + "#define CLOUD_UPPER_ROWS " + VolumetricCloudGenerator.UPPER_ROWS + "\n"
                + "#define MIN_ELEVATION_DEGREES " + VolumetricCloudGenerator.MIN_ELEVATION_DEGREES + "\n"
                + "#define CIRRUS_MIN_ELEVATION_DEGREES " + SkyLayerTextures.CIRRUS_MIN_ELEVATION_DEGREES + "\n"
                + "#define CLOUD_MAX_SHAPES " + CloudShapes.MAX_SHAPES + "\n";
    }

    /** Whether clouds are published: the textures exist, a march has set the inputs and the dome shows one. */
    public static boolean ready() {
        return noiseTexture != 0 && panoramaTexture != 0 && framed;
    }

    /** The 3D noise the field reads. */
    public static int noiseTexture() {
        return noiseTexture;
    }

    /** The cirrus layer's density, in the sky layers' polar layout. */
    public static int cirrusTexture() {
        return cirrusTexture;
    }

    /** The panorama the dome shows this frame: premultiplied radiance and opacity, CLOUD_HEIGHT rows. */
    public static int panoramaTexture() {
        return panoramaTexture;
    }

    /**
     * The atmosphere the dome light shows behind the clouds: equirect radiance laid out as
     * sky_atmosphere.comp's skyDirection lays it, with a last row of statistics rather than sky.
     */
    public static int skyTexture() {
        return skyTexture;
    }

    /** The dome light's multiplier on {@link #skyTexture()}. */
    public static float skyRadiance() {
        return skyRadiance;
    }

    public static float[] camera() {
        return CAMERA;
    }

    public static float[] layers() {
        return LAYERS;
    }

    public static float[] light() {
        return LIGHT;
    }

    public static float[] flash() {
        return FLASH;
    }

    public static float brightness() {
        return brightness;
    }

    public static float hazeDistance() {
        return hazeDistance;
    }

    public static float cirrusAngle() {
        return cirrusAngle;
    }

    public static float cirrusStrength() {
        return cirrusStrength;
    }

    /** How dark the clouds' shadows on the land are, the Cloud Shadows setting, 0 to 1. */
    public static float shadowStrength() {
        return shadowStrength;
    }

    static void publish(int noise, int cirrus, double cameraX, double cameraY, double cameraZ, float shadow) {
        noiseTexture = noise;
        cirrusTexture = cirrus;
        shadowStrength = shadow;
        CAMERA[0] = (float) cameraX;
        CAMERA[1] = (float) cameraY;
        CAMERA[2] = (float) cameraZ;
    }

    /** Takes the inputs of the march just dispatched. */
    static void setFrame(VolumetricCloudGenerator.Frame frame) {
        LAYERS[0] = frame.base;
        LAYERS[1] = frame.thickness;
        System.arraycopy(frame.coverage, 0, LAYERS, 2, 3);
        LAYERS[5] = frame.density;
        LAYERS[6] = frame.featureScale;
        LAYERS[7] = frame.detail;
        System.arraycopy(frame.lightDirection, 0, LIGHT, 0, 3);
        System.arraycopy(frame.lightIrradiance, 0, LIGHT, 3, 3);
        System.arraycopy(frame.ambientTop, 0, LIGHT, 6, 3);
        System.arraycopy(frame.ambientBottom, 0, LIGHT, 9, 3);
        brightness = frame.brightness;
        hazeDistance = frame.hazeDistance;
        cirrusAngle = frame.cirrusAngle;
        cirrusStrength = frame.cirrusStrength;
        System.arraycopy(frame.flash, 0, FLASH, 0, 4);
        shapes = frame.shapes;
        framed = true;
    }

    /**
     * Sets the shaped clouds' uniforms (ShapeCount, ShapeBounds, ShapeForm, ShapeSize) on the
     * program in use.
     */
    public static void setShapeUniforms(int program) {
        setShapeUniforms(program, shapes);
    }

    static void setShapeUniforms(int program, CloudShapes shapes) {
        int count = shapes == null ? 0 : shapes.count;
        GL20.glUniform1i(GL20.glGetUniformLocation(program, "ShapeCount"), count);
        if (count == 0) {
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer values = stack.mallocFloat(CloudShapes.MAX_SHAPES * 4);
            setVectors(program, "ShapeBounds", shapes.bounds, count, values);
            setVectors(program, "ShapeForm", shapes.form, count, values);
            setVectors(program, "ShapeSize", shapes.size, count, values);
        }
    }

    private static void setVectors(int program, String name, float[] source, int vectors, FloatBuffer values) {
        values.clear().put(source, 0, vectors * 4).flip();
        GL20.glUniform4fv(GL20.glGetUniformLocation(program, name), values);
    }

    /** Takes the panorama the dome was just given. */
    static void show(int panorama) {
        panoramaTexture = panorama;
    }

    /** Takes the sky the dome light shows this frame. */
    static void showSky(int texture, float radiance) {
        skyTexture = texture;
        skyRadiance = radiance;
    }

    static void withdraw() {
        noiseTexture = 0;
        panoramaTexture = 0;
        skyTexture = 0;
        framed = false;
    }
}
