/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import com.nvidia.rtxremix.minecraft.util.LongObjectMap;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.resources.Identifier;

/**
 * Cutout overlay layers painted into the texture of the body they lie on.
 *
 * <p>Vanilla draws a villager's biome, profession and level layers and a sheep's undercoat
 * as the body's own cuboids again, with a mostly transparent cutout texture. Drawn as shells
 * a hair outside the body, a ray through a transparent overlay texel is restarted past the
 * gap and starts inside the body's closed box, which renders it black. A layer whose every
 * part repeats a body part instead becomes part of a derived copy of the body texture,
 * painted in paint order, and the body is drawn once with it.
 *
 * <p>Render thread only.
 */
final class OverlayComposite {

    private static final String NAMESPACE = "rtx-remix-renderer";
    private static final String PATH_PREFIX = "overlay_composites/";
    /** Vanilla's entity-cutout cut: an overlay texel below this alpha is discarded. */
    private static final int VANILLA_ALPHA_CUTOUT = 26;

    /** One composite: the body texture and its overlays, with their tints, in paint order. */
    record Spec(Identifier base, Identifier[] overlays, int[] tints) { }

    private static final LongObjectMap<Identifier> ID_BY_KEY = new LongObjectMap<>();
    private static final Map<Identifier, Spec> SPEC_BY_ID = new HashMap<>();

    private OverlayComposite() { }

    /**
     * The derived texture for {@code base} with the first {@code count} overlays painted over
     * it; allocates only on the first sight of a combination.
     */
    static Identifier texture(Identifier base, Identifier[] overlays, int[] tints, int count) {
        long key = mix(base.hashCode());
        for (int i = 0; i < count; i++) {
            key = mix(key ^ overlays[i].hashCode());
            key = mix(key ^ quantizeTint(tints[i]));
        }
        Identifier id = ID_BY_KEY.get(key);
        if (id == null) {
            id = Identifier.fromNamespaceAndPath(NAMESPACE,
                    PATH_PREFIX + Long.toUnsignedString(key, 36));
            int[] quantized = new int[count];
            for (int i = 0; i < count; i++) {
                quantized[i] = quantizeTint(tints[i]);
            }
            SPEC_BY_ID.put(id, new Spec(base, Arrays.copyOf(overlays, count), quantized));
            ID_BY_KEY.put(key, id);
        }
        return id;
    }

    /** The composite {@code textureId} names, or null when it is not one. */
    static Spec spec(Identifier textureId) {
        return SPEC_BY_ID.get(textureId);
    }

    /** The body texture {@code textureId} is derived from, or {@code textureId} itself. */
    static Identifier sourceTexture(Identifier textureId) {
        Spec spec = SPEC_BY_ID.get(textureId);
        return spec != null ? spec.base() : textureId;
    }

    /**
     * Whether every part of {@code overlay} repeats a part of {@code base}: the same cuboid
     * faces, or for geometry captured vertex by vertex the same vertices, with the same
     * positions and texture coordinates, hung off a bone with the same transform. Only then
     * can the overlay be painted into the body's texture. GeckoLib's layers draw the whole
     * model again that way, a no-armour tier with a fully transparent texture.
     */
    static boolean repeatsBase(RemixCapturingConsumer base, RemixCapturingConsumer overlay, float[] boneData) {
        int parts = overlay.getPartCount();
        if (parts == 0) return false;
        for (int p = 0; p < parts; p++) {
            ModelPart.Polygon[] sides = overlay.getPartSides(p);
            boolean found = false;
            for (int q = 0; q < base.getPartCount() && !found; q++) {
                found = (sides != null
                                ? sameCuboid(sides, base.getPartSides(q))
                                : base.getPartSides(q) == null && sameVertices(overlay, p, base, q))
                        && sameBone(boneData, overlay.getPartBone(p), base.getPartBone(q));
            }
            if (!found) return false;
        }
        return true;
    }

    static boolean sameVertices(RemixCapturingConsumer a, int partA, RemixCapturingConsumer b, int partB) {
        int count = a.getPartVertexCount(partA);
        if (count == 0 || count != b.getPartVertexCount(partB)) return false;
        int startA = a.getPartStart(partA);
        int startB = b.getPartStart(partB);
        return Arrays.equals(a.getPx(), startA, startA + count, b.getPx(), startB, startB + count)
                && Arrays.equals(a.getPy(), startA, startA + count, b.getPy(), startB, startB + count)
                && Arrays.equals(a.getPz(), startA, startA + count, b.getPz(), startB, startB + count)
                && Arrays.equals(a.getTu(), startA, startA + count, b.getTu(), startB, startB + count)
                && Arrays.equals(a.getTv(), startA, startA + count, b.getTv(), startB, startB + count);
    }

    static boolean sameCuboid(ModelPart.Polygon[] a, ModelPart.Polygon[] b) {
        if (a == b) return true;
        if (b == null || a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) {
            ModelPart.Vertex[] va = a[i].vertices();
            ModelPart.Vertex[] vb = b[i].vertices();
            if (va.length != vb.length) return false;
            for (int j = 0; j < va.length; j++) {
                if (!va[j].equals(vb[j])) return false;
            }
        }
        return true;
    }

    private static boolean sameBone(float[] boneData, int a, int b) {
        if (a == b) return true;
        if (a < 0 || b < 0 || (Math.max(a, b) + 1) * 12 > boneData.length) return false;
        for (int i = 0; i < 12; i++) {
            if (boneData[a * 12 + i] != boneData[b * 12 + i]) return false;
        }
        return true;
    }

    /**
     * Paints an RGBA overlay over an RGBA body of the same size, as vanilla draws the cutout
     * layer: a texel under the cut shows the body, any other covers it, tinted. An overlay of
     * another size is left out, because only a matching layout puts it where the body expects.
     *
     * @return whether the overlay was painted
     */
    static boolean paint(byte[] body, int width, int height, byte[] overlay, int overlayWidth, int overlayHeight,
                         int tint) {
        if (overlayWidth != width || overlayHeight != height || overlay.length < width * height * 4) {
            return false;
        }
        float r = ((tint >> 16) & 0xFF) / 255f;
        float g = ((tint >> 8) & 0xFF) / 255f;
        float b = (tint & 0xFF) / 255f;
        for (int i = 0; i < width * height * 4; i += 4) {
            if ((overlay[i + 3] & 0xFF) < VANILLA_ALPHA_CUTOUT) continue;
            body[i] = (byte) Math.round((overlay[i] & 0xFF) * r);
            body[i + 1] = (byte) Math.round((overlay[i + 1] & 0xFF) * g);
            body[i + 2] = (byte) Math.round((overlay[i + 2] & 0xFF) * b);
            body[i + 3] = (byte) 0xFF;
        }
        return true;
    }

    /**
     * The tint rounded to four bits a channel, full scale kept full, so a sheep whose wool
     * cycles through the dyes makes a bounded set of composites rather than a new texture on
     * every frame.
     */
    static int quantizeTint(int tint) {
        int quantized = 0;
        for (int shift = 0; shift <= 16; shift += 8) {
            int channel = (tint >> shift) & 0xFF;
            quantized |= Math.min(15, (channel + 8) >> 4) * 17 << shift;
        }
        return quantized;
    }

    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
