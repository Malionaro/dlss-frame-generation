package org.malionaro.dlss_frame_generation.client.raytracing;

import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import org.malionaro.dlss_frame_generation.client.motion.EntityPartMotion;
import org.malionaro.dlss_frame_generation.client.raytracing.scene.RtTriangleFormat;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EntityGeometryTest {
    @Test
    void cubeBecomesTwelveTrianglesInSceneSpace() {
        ByteBuffer positions = ByteBuffer.allocate(64 * RtTriangleFormat.BYTES_PER_POSITION)
                .order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer attributes = ByteBuffer.allocate(64 * RtTriangleFormat.BYTES_PER_ATTRIBUTE)
                .order(ByteOrder.LITTLE_ENDIAN);
        // A 1x2x1 part, drawn 3 blocks in front of a camera that sits at (10, 64, 10) in scene space.
        Matrix4f pose = new Matrix4f().translation(0, 0, -3);
        List<EntityPartMotion.Pose> poses = List.of(
                new EntityPartMotion.Pose(pose, new float[] {0, 0, 0, 1, 2, 1}));

        int triangles = EntityGeometry.write(poses, 10, 64, 10, positions, attributes, 64);

        assertEquals(12, triangles);
        float minY = Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        float minZ = Float.MAX_VALUE;
        for (int vertex = 0; vertex < triangles * 3; vertex++) {
            minY = Math.min(minY, positions.getFloat(vertex * 12 + 4));
            maxY = Math.max(maxY, positions.getFloat(vertex * 12 + 4));
            minZ = Math.min(minZ, positions.getFloat(vertex * 12 + 8));
        }
        assertEquals(64.0f, minY, 1.0e-5f);
        assertEquals(66.0f, maxY, 1.0e-5f);
        assertEquals(7.0f, minZ, 1.0e-5f);
        // Untextured: the tint carries the albedo.
        int tint = Float.floatToRawIntBits(attributes.getFloat(24));
        assertEquals(RtTriangleFormat.UNTEXTURED_BIT, tint & RtTriangleFormat.UNTEXTURED_BIT);
    }

    @Test
    void cubesCarryTheirTextureColour() {
        ByteBuffer positions = ByteBuffer.allocate(64 * RtTriangleFormat.BYTES_PER_POSITION);
        ByteBuffer attributes = ByteBuffer.allocate(64 * RtTriangleFormat.BYTES_PER_ATTRIBUTE)
                .order(ByteOrder.BIG_ENDIAN);
        List<EntityPartMotion.Pose> poses = List.of(new EntityPartMotion.Pose(new Matrix4f(),
                new float[] {0, 0, 0, 1, 1, 1}, new int[] {0x336699}));
        EntityGeometry.write(poses, 0, 0, 0, positions, attributes, 64);
        int tint = Float.floatToRawIntBits(attributes.getFloat(24));
        assertEquals(0x336699, tint & 0xFFFFFF);
    }

    @Test
    void stopsAtTheTriangleBudget() {
        ByteBuffer positions = ByteBuffer.allocate(64 * RtTriangleFormat.BYTES_PER_POSITION);
        ByteBuffer attributes = ByteBuffer.allocate(64 * RtTriangleFormat.BYTES_PER_ATTRIBUTE);
        List<EntityPartMotion.Pose> poses = List.of(new EntityPartMotion.Pose(new Matrix4f(),
                new float[] {0, 0, 0, 1, 1, 1, 2, 2, 2, 3, 3, 3}));
        assertEquals(12, EntityGeometry.write(poses, 0, 0, 0, positions, attributes, 20));
    }
}
