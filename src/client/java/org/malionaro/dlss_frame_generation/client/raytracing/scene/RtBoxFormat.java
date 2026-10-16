package org.malionaro.dlss_frame_generation.client.raytracing.scene;

import java.nio.ByteBuffer;

/**
 * GPU layout of one ray-traced box, shared with {@code hybrid_ray_query.comp}
 * ({@code struct BlockAabb}). 48 bytes, std430:
 *
 * <pre>
 * vec4  minX minY minZ maxX      (first 24 bytes double as VkAabbPositionsKHR)
 * vec4  maxY maxZ color flags    (color: packed 0xRRGGBB map colour, flags: FLAG_*)
 * uvec4 faces                    x = -X | +X << 16, y = -Y | +Y << 16, z = -Z | +Z << 16
 *                                w = tint 0xRRGGBB | tinted-face mask << 24 | cutout << 30
 * </pre>
 *
 * <p>Face entries are sprite ids from {@link SpriteTable}; 0 means "no
 * texture, use the map colour".</p>
 */
public final class RtBoxFormat {
    public static final int BYTES_PER_BOX = 48;

    public static final int FLAG_REFLECTIVE = 1;
    public static final int FLAG_WATER = 2;
    public static final int FLAG_GLASS = 4;
    public static final int FLAG_EMISSIVE = 8;
    public static final int FLAG_METALLIC = 16;

    /** Face order used everywhere: -X, +X, -Y, +Y, -Z, +Z. */
    public static final int FACE_COUNT = 6;
    public static final int MAX_SPRITE_ID = 0xFFFF;

    private RtBoxFormat() {}

    public static int packFacePair(int negativeSprite, int positiveSprite) {
        return (clampSprite(negativeSprite)) | (clampSprite(positiveSprite) << 16);
    }

    /**
     * @param tint          0xRRGGBB biome/block tint, applied to tinted faces
     * @param tintedFaces   bit i set when face i (see face order) is tinted
     * @param cutout        alpha-tested texture (leaves, flowers, panes)
     */
    public static int packTint(int tint, int tintedFaces, boolean cutout) {
        return (tint & 0xFFFFFF)
                | ((tintedFaces & 0x3F) << 24)
                | (cutout ? 1 << 30 : 0);
    }

    public static void putBox(
            ByteBuffer target,
            float minX, float minY, float minZ,
            float maxX, float maxY, float maxZ,
            int color, int flags,
            int facesX, int facesY, int facesZ, int tintWord
    ) {
        target.putFloat(minX).putFloat(minY).putFloat(minZ).putFloat(maxX);
        target.putFloat(maxY).putFloat(maxZ)
                .putFloat(Float.intBitsToFloat(color))
                .putFloat(Float.intBitsToFloat(flags));
        target.putInt(facesX).putInt(facesY).putInt(facesZ).putInt(tintWord);
    }

    private static int clampSprite(int sprite) {
        return sprite < 0 || sprite > MAX_SPRITE_ID ? 0 : sprite;
    }
}
