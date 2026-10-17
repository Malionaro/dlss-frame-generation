package org.malionaro.dlss_frame_generation.client.raytracing;

import org.junit.jupiter.api.Test;
import org.malionaro.dlss_frame_generation.client.raytracing.scene.RtTriangleFormat;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ParticleGeometryTest {
    @Test
    void particleCubeSpansItsSize() {
        ByteBuffer positions = ByteBuffer.allocate(12 * RtTriangleFormat.BYTES_PER_POSITION)
                .order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer attributes = ByteBuffer.allocate(12 * RtTriangleFormat.BYTES_PER_ATTRIBUTE)
                .order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(12, ParticleGeometry.cube(positions, attributes, 1, 2, 3, 0.25f, 0xFFFFFF, 0));
        float min = Float.MAX_VALUE;
        float max = -Float.MAX_VALUE;
        for (int vertex = 0; vertex < 36; vertex++) {
            float x = positions.getFloat(vertex * 12);
            min = Math.min(min, x);
            max = Math.max(max, x);
        }
        assertEquals(0.75f, min, 1.0e-6f);
        assertEquals(1.25f, max, 1.0e-6f);
    }

    @Test
    void colourFollowsTheParticleTint() {
        assertEquals(0xFF0000, ParticleGeometry.multiply(0xFFFFFF, 1.0f, 0.0f, 0.0f));
        assertEquals(0x6C6C6C, ParticleGeometry.multiply(0xD8D8D8, 0.5f, 0.5f, 0.5f));
    }
}
