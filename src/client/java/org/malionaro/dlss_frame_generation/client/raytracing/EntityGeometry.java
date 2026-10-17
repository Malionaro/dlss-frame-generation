package org.malionaro.dlss_frame_generation.client.raytracing;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.malionaro.dlss_frame_generation.client.motion.EntityPartMotion;
import org.malionaro.dlss_frame_generation.client.raytracing.scene.RtBoxFormat;
import org.malionaro.dlss_frame_generation.client.raytracing.scene.RtTriangleFormat;

import java.nio.ByteBuffer;
import java.util.List;

/**
 * Entity model parts as RT triangles: every cuboid of every drawn part,
 * transformed by the pose it was drawn with this frame, so rotated limbs and
 * heads cast exact shadows and show up in reflections and under water.
 *
 * <p>Poses are camera-relative; adding the camera's offset from the scene
 * origin puts the triangles in scene space. Entity textures are not in the
 * block atlas, so parts get a neutral untextured albedo.</p>
 */
final class EntityGeometry {
    /** Neutral, slightly warm grey: reads as "a mob" in reflections without a texture. */
    static final int ALBEDO = 0x8C847C;
    /** Lit like open sky; entities move between light levels anyway. */
    static final int FLAGS = RtBoxFormat.withSkyLight(0, 15);

    // Cube corner order: bit 0 = x, bit 1 = y, bit 2 = z (0 = min, 1 = max).
    private static final int[][] FACES = {
            {0, 4, 6, 2}, // -X
            {1, 3, 7, 5}, // +X
            {0, 1, 5, 4}, // -Y
            {2, 6, 7, 3}, // +Y
            {0, 2, 3, 1}, // -Z
            {4, 5, 7, 6}, // +Z
    };

    private EntityGeometry() {}

    /**
     * @return triangles written, at most {@code maxTriangles}
     */
    static int write(List<EntityPartMotion.Pose> poses,
                     float cameraX, float cameraY, float cameraZ,
                     ByteBuffer positions, ByteBuffer attributes, int maxTriangles) {
        int triangles = 0;
        Vector3f[] corners = new Vector3f[8];
        for (int index = 0; index < 8; index++) {
            corners[index] = new Vector3f();
        }
        Vector3f edgeA = new Vector3f();
        Vector3f edgeB = new Vector3f();
        Vector3f normal = new Vector3f();
        int tint = (ALBEDO & 0xFFFFFF) | RtTriangleFormat.UNTEXTURED_BIT;
        for (EntityPartMotion.Pose pose : poses) {
            Matrix4f matrix = pose.pose();
            float[] cubes = pose.cubes();
            for (int cube = 0; cube + 5 < cubes.length; cube += 6) {
                if (triangles + 12 > maxTriangles) {
                    return triangles;
                }
                for (int corner = 0; corner < 8; corner++) {
                    corners[corner].set(
                            (corner & 1) == 0 ? cubes[cube] : cubes[cube + 3],
                            (corner & 2) == 0 ? cubes[cube + 1] : cubes[cube + 4],
                            (corner & 4) == 0 ? cubes[cube + 2] : cubes[cube + 5]);
                    matrix.transformPosition(corners[corner]);
                    corners[corner].add(cameraX, cameraY, cameraZ);
                }
                for (int[] face : FACES) {
                    Vector3f a = corners[face[0]];
                    Vector3f b = corners[face[1]];
                    Vector3f c = corners[face[2]];
                    Vector3f d = corners[face[3]];
                    b.sub(a, edgeA);
                    c.sub(a, edgeB);
                    edgeA.cross(edgeB, normal);
                    if (normal.lengthSquared() < 1.0e-12f || !normal.isFinite()) {
                        continue;
                    }
                    normal.normalize();
                    RtTriangleFormat.putPositions(positions, a.x, a.y, a.z, b.x, b.y, b.z, c.x, c.y, c.z);
                    RtTriangleFormat.putAttributes(attributes, 0, 0, 0, 0, 0, 0,
                            tint, FLAGS, normal.x, normal.y, normal.z);
                    RtTriangleFormat.putPositions(positions, a.x, a.y, a.z, c.x, c.y, c.z, d.x, d.y, d.z);
                    RtTriangleFormat.putAttributes(attributes, 0, 0, 0, 0, 0, 0,
                            tint, FLAGS, normal.x, normal.y, normal.z);
                    triangles += 2;
                }
            }
        }
        return triangles;
    }
}
