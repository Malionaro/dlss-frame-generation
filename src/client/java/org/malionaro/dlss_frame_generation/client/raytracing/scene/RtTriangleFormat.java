package org.malionaro.dlss_frame_generation.client.raytracing.scene;

import java.nio.ByteBuffer;

/**
 * Triangle geometry of a section for blocks that are not full cubes (plants,
 * torches, stairs, panes, leaves): real model quads instead of boxes.
 *
 * <p>Positions feed the triangle BLAS: three float3 per triangle, section-local
 * and not indexed. Attributes are read by the shader per primitive, std430,
 * {@value #BYTES_PER_ATTRIBUTE} bytes:</p>
 * <pre>
 * vec4  uv0.xy uv1.xy         (block atlas)
 * vec4  uv2.xy tint flags     (tint: rgb | cutout &lt;&lt; 24, flags: RtBoxFormat FLAG_* and sky light)
 * vec4  normal.xyz pad        (face normal of the quad)
 * </pre>
 */
public final class RtTriangleFormat {
    public static final int BYTES_PER_POSITION = 36;
    public static final int BYTES_PER_ATTRIBUTE = 48;
    public static final int CUTOUT_BIT = 1 << 24;

    private RtTriangleFormat() {}

    public static void putPositions(ByteBuffer target,
                                    float x0, float y0, float z0,
                                    float x1, float y1, float z1,
                                    float x2, float y2, float z2) {
        target.putFloat(x0).putFloat(y0).putFloat(z0)
                .putFloat(x1).putFloat(y1).putFloat(z1)
                .putFloat(x2).putFloat(y2).putFloat(z2);
    }

    public static void putAttributes(ByteBuffer target,
                                     float u0, float v0, float u1, float v1, float u2, float v2,
                                     int tint, int flags,
                                     float normalX, float normalY, float normalZ) {
        target.putFloat(u0).putFloat(v0).putFloat(u1).putFloat(v1)
                .putFloat(u2).putFloat(v2)
                .putFloat(Float.intBitsToFloat(tint)).putFloat(Float.intBitsToFloat(flags))
                .putFloat(normalX).putFloat(normalY).putFloat(normalZ).putFloat(0.0f);
    }
}
