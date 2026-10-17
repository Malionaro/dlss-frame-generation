package org.malionaro.dlss_frame_generation.client.motion;

import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ObjectMotionTest {
    private static ObjectMotion.Box box(double distanceSquared) {
        return new ObjectMotion.Box(-1, 0, -1, 1, 2, 1, 0.1f, 0, 0, distanceSquared, 0);
    }

    @Test
    void keepsTheNearestEntities() {
        List<ObjectMotion.Box> candidates = new ArrayList<>();
        for (int index = ObjectMotion.MAX_ENTITIES + 10; index > 0; index--) {
            candidates.add(box(index));
        }
        List<ObjectMotion.Box> nearest = ObjectMotion.nearest(candidates, ObjectMotion.MAX_ENTITIES);
        assertEquals(ObjectMotion.MAX_ENTITIES, nearest.size());
        assertEquals(1.0, nearest.getFirst().distanceSquared());
    }

    @Test
    void writesStd140EntityBlock() {
        ByteBuffer buffer = ByteBuffer.allocate(ObjectMotion.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        ObjectMotion.writeBoxes(buffer, 0, List.of(
                new ObjectMotion.Box(1, 2, 3, 4, 5, 6, 0.5f, -0.25f, 0.125f, 1, 7)));
        assertEquals(1, buffer.getInt(0));
        assertEquals(1.0f, buffer.getFloat(16));
        assertEquals(4.0f, buffer.getFloat(32));
        assertEquals(6.0f, buffer.getFloat(40));
        assertEquals(0.5f, buffer.getFloat(48));
        assertEquals(0.125f, buffer.getFloat(56));
        assertEquals(0, buffer.getInt(28));
        assertEquals(0, buffer.getInt(44));
    }

    @Test
    void boxesCarryTheirPartRange() {
        ByteBuffer buffer = ByteBuffer.allocate(ObjectMotion.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        ObjectMotion.writeBoxes(buffer, 0, List.of(
                new ObjectMotion.Box(1, 2, 3, 4, 5, 6, 0, 0, 0, 1, 7),
                new ObjectMotion.Box(1, 2, 3, 4, 5, 6, 0, 0, 0, 2, 8)),
                new int[][] {{0, 5}, {5, 3}});
        // min.w = first part, max.w = part count, read as uint bits by the shader.
        assertEquals(0, buffer.getInt(16 + 12));
        assertEquals(5, buffer.getInt(16 + 28));
        assertEquals(5, buffer.getInt(16 + 48 + 12));
        assertEquals(3, buffer.getInt(16 + 48 + 28));
        assertEquals(4.0f, buffer.getFloat(16 + 48 + 16));
    }

    @Test
    void matricesRoundTripCameraRelativePoints() {
        Matrix4f projection = new Matrix4f().perspective((float) Math.toRadians(70), 16f / 9f, 0.05f, 512f);
        Vector3f forward = new Vector3f(0, 0, -1);
        Vector3f up = new Vector3f(0, 1, 0);
        ObjectMotion motion = ObjectMotion.INSTANCE;
        motion.update(null, new Vec3(1000, 64, 1000), forward, up, projection, true);
        // Camera stepped 1 block forward; a static point 10 blocks ahead of the
        // old camera is now 9 blocks ahead.
        motion.update(null, new Vec3(1000, 64, 999), forward, up, projection, false);

        Vector4f point = new Vector4f(0, 0, -9, 1);
        Vector4f clip = new Matrix4f(projection).transform(new Vector4f(point));
        Vector4f ndc = new Vector4f(clip.x / clip.w, clip.y / clip.w, clip.z / clip.w, 1);
        Vector4f back = new Matrix4f().set(motion.clipToCameraRelative()).transform(new Vector4f(ndc));
        assertEquals(-9.0f, back.z / back.w, 1.0e-3f);

        Vector4f previous = new Matrix4f().set(motion.cameraRelativeToPreviousClip()).transform(new Vector4f(point));
        Vector4f expected = new Matrix4f(projection).transform(new Vector4f(0, 0, -10, 1));
        assertEquals(expected.z / expected.w, previous.z / previous.w, 1.0e-5f);
        assertEquals(expected.w, previous.w, 1.0e-3f);
    }
}
