/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OverlayCompositeTest {

    private static final Identifier BODY = Identifier.withDefaultNamespace("textures/entity/villager/villager.png");
    private static final Identifier BADGE = Identifier.withDefaultNamespace("textures/entity/villager/profession_level/gold.png");

    @Test
    void anOverlayCoversTheBodyWhereItIsAboveVanillasCut() {
        byte[] body = {10, 20, 30, (byte) 255, 40, 50, 60, (byte) 255};
        byte[] overlay = {(byte) 200, (byte) 100, 50, (byte) 255, 1, 2, 3, 10};
        assertTrue(OverlayComposite.paint(body, 2, 1, overlay, 2, 1, 0xFFFFFF));
        assertArrayEquals(new byte[] {(byte) 200, (byte) 100, 50, (byte) 255, 40, 50, 60, (byte) 255}, body);
    }

    @Test
    void aTintedOverlayIsMultipliedAndAMismatchedOneIsLeftOut() {
        byte[] body = new byte[4];
        byte[] white = {(byte) 255, (byte) 255, (byte) 255, (byte) 255};
        assertTrue(OverlayComposite.paint(body, 1, 1, white, 1, 1, 0x80FF00));
        assertEquals(128, body[0] & 0xFF);
        assertEquals(255, body[1] & 0xFF);
        assertEquals(0, body[2] & 0xFF);
        assertFalse(OverlayComposite.paint(body, 1, 1, new byte[16], 2, 2, -1));
    }

    @Test
    void quantizingKeepsWhiteWhiteAndBlackBlack() {
        assertEquals(0xFFFFFF, OverlayComposite.quantizeTint(-1));
        assertEquals(0x000000, OverlayComposite.quantizeTint(0xFF000000));
        assertEquals(OverlayComposite.quantizeTint(0x123456), OverlayComposite.quantizeTint(0x133556));
    }

    @Test
    void oneCombinationNamesOneTexture() {
        Identifier[] overlays = {BADGE};
        Identifier first = OverlayComposite.texture(BODY, overlays, new int[] {-1}, 1);
        assertEquals(first, OverlayComposite.texture(BODY, overlays, new int[] {-1}, 1));
        assertNotEquals(first, OverlayComposite.texture(BODY, overlays, new int[] {0xFF0000}, 1));
        assertEquals(BODY, OverlayComposite.sourceTexture(first));
        assertEquals(BADGE, OverlayComposite.spec(first).overlays()[0]);
        assertNull(OverlayComposite.spec(BODY));
        assertEquals(BODY, OverlayComposite.sourceTexture(BODY));
    }

    @Test
    void cuboidsMatchByValueNotIdentity() {
        ModelPart.Polygon[] a = {quad(0f, 0f)};
        ModelPart.Polygon[] same = {quad(0f, 0f)};
        ModelPart.Polygon[] moved = {quad(1f, 0f)};
        ModelPart.Polygon[] otherUv = {quad(0f, 0.5f)};
        assertTrue(OverlayComposite.sameCuboid(a, a));
        assertTrue(OverlayComposite.sameCuboid(a, same));
        assertFalse(OverlayComposite.sameCuboid(a, moved));
        assertFalse(OverlayComposite.sameCuboid(a, otherUv));
        assertFalse(OverlayComposite.sameCuboid(a, null));
    }

    /** Two bones with the same identity transform, as two layers of one model see them. */
    private static final float[] BONES = {
            1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0,
            1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0};

    /** A layer drawn vertex by vertex, as GeckoLib draws through custom geometry. */
    private static RemixCapturingConsumer quadLayer(float uOffset) {
        RemixCapturingConsumer consumer = new RemixCapturingConsumer(null, true);
        Matrix4f pose = new Matrix4f();
        float[][] corners = {{0, 0, 0}, {1, 0, 0}, {1, 0, 1}, {0, 0, 1}};
        for (float[] c : corners) {
            consumer.addVertex(pose, c[0], c[1], c[2])
                    .setColor(-1)
                    .setUv(c[0] * 0.25f + uOffset, c[2] * 0.25f)
                    .setOverlay(OverlayTexture.NO_OVERLAY)
                    .setLight(0)
                    .setNormal(0, 1, 0);
        }
        consumer.finishOpenVertex();
        return consumer;
    }

    @Test
    void aLayerRepeatingTheBodyVertexByVertexRepeatsItAndOneOnOtherCoordinatesDoesNot() {
        EntityRenderContext.get().begin(true);
        try {
            RemixCapturingConsumer body = quadLayer(0.0f);
            assertEquals(1, body.getPartCount());
            assertTrue(OverlayComposite.repeatsBase(body, quadLayer(0.0f), BONES));
            assertFalse(OverlayComposite.repeatsBase(body, quadLayer(0.5f), BONES));
        } finally {
            EntityRenderContext.get().reset();
        }
    }

    private static ModelPart.Polygon quad(float x, float u) {
        ModelPart.Vertex[] vertices = {
                new ModelPart.Vertex(x, 0f, 0f, u, 0f),
                new ModelPart.Vertex(x + 1f, 0f, 0f, u + 0.25f, 0f),
                new ModelPart.Vertex(x + 1f, 1f, 0f, u + 0.25f, 0.25f),
                new ModelPart.Vertex(x, 1f, 0f, u, 0.25f)};
        return new ModelPart.Polygon(vertices, new Vector3f(0f, 0f, 1f));
    }
}
