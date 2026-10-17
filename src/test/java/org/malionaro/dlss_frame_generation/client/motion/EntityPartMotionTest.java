package org.malionaro.dlss_frame_generation.client.motion;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityPartMotionTest {
    private static final float[] UNIT_CUBE = {-0.25f, 0, -0.25f, 0.25f, 0.75f, 0.25f};
    private static final Object ARM = new Object();
    private static final Object HEAD = new Object();

    private static EntityPartMotion enabled() {
        EntityPartMotion motion = new EntityPartMotion();
        motion.setEnabled(true);
        return motion;
    }

    /** Applies the three rows the shader reads (row-major 3x4). */
    private static Vector3f applyRows(ByteBuffer buffer, int at, Vector3f point) {
        float[] out = new float[3];
        for (int row = 0; row < 3; row++) {
            int base = at + row * 16;
            out[row] = buffer.getFloat(base) * point.x + buffer.getFloat(base + 4) * point.y
                    + buffer.getFloat(base + 8) * point.z + buffer.getFloat(base + 12);
        }
        return new Vector3f(out[0], out[1], out[2]);
    }

    @Test
    void swingingLimbMapsSurfacePointToItsPreviousPosition() {
        EntityPartMotion motion = enabled();
        // Shoulder 5 blocks ahead of the camera; the arm swings 30 degrees about X.
        Matrix4f before = new Matrix4f().translation(0, 1.5f, -5).rotateX((float) Math.toRadians(-15));
        Matrix4f now = new Matrix4f().translation(0, 1.5f, -5).rotateX((float) Math.toRadians(15));
        motion.beginFrame(0, 0, 0, false);
        motion.record(1, ARM, before, UNIT_CUBE);
        motion.beginFrame(0, 0, 0, false);
        motion.record(1, ARM, now, UNIT_CUBE);

        Vector3f hand = new Vector3f(0, -0.75f, 0);
        Vector3f current = now.transformPosition(new Vector3f(hand));
        Vector3f expected = before.transformPosition(new Vector3f(hand));

        ByteBuffer buffer = ByteBuffer.allocate(EntityPartMotion.BUFFER_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        int[][] ranges = motion.writeParts(buffer, List.of(1));
        assertEquals(0, ranges[0][0]);
        assertEquals(1, ranges[0][1]);
        Vector3f local = applyRows(buffer, 0, current);
        assertEquals(hand.y, local.y, 1.0e-5f);
        Vector3f previous = applyRows(buffer, 48, local);
        assertEquals(expected.x, previous.x, 1.0e-5f);
        assertEquals(expected.y, previous.y, 1.0e-5f);
        assertEquals(expected.z, previous.z, 1.0e-5f);
        assertEquals(-0.25f, buffer.getFloat(96));
        assertEquals(0.75f, buffer.getFloat(112 + 4));
    }

    @Test
    void cameraMoveExpressesHistoryRelativeToTheCurrentCamera() {
        EntityPartMotion motion = enabled();
        // A static entity at world z = -10. Last frame the camera was at z = 0,
        // now it is at z = -1, so the submitted pose moved one block closer.
        motion.beginFrame(0, 0, 0, false);
        motion.record(1, HEAD, new Matrix4f().translation(0, 0, -10), UNIT_CUBE);
        motion.beginFrame(0, 0, 0 - (-1), false);
        motion.record(1, HEAD, new Matrix4f().translation(0, 0, -9), UNIT_CUBE);

        EntityPartMotion.Moved moved = motion.moved(1).getFirst();
        Vector3f point = new Vector3f(0, 0.5f, -9);
        Vector3f previous = moved.toPrevious().transformPosition(
                moved.toLocal().transformPosition(new Vector3f(point)));
        // Static in the world: same position relative to the current camera.
        assertEquals(point.x, previous.x, 1.0e-5f);
        assertEquals(point.y, previous.y, 1.0e-5f);
        assertEquals(point.z, previous.z, 1.0e-5f);
    }

    @Test
    void writesOneEntryPerCubeAndStopsWhenFull() {
        EntityPartMotion motion = enabled();
        float[] twoCubes = {0, 0, 0, 1, 1, 1, 2, 2, 2, 3, 3, 3};
        motion.beginFrame(0, 0, 0, false);
        motion.record(1, ARM, new Matrix4f(), twoCubes);
        motion.record(2, HEAD, new Matrix4f(), UNIT_CUBE);
        motion.beginFrame(0, 0, 0, false);
        motion.record(1, ARM, new Matrix4f(), twoCubes);
        motion.record(2, HEAD, new Matrix4f(), UNIT_CUBE);

        ByteBuffer buffer = ByteBuffer.allocate(EntityPartMotion.BUFFER_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        int[][] ranges = motion.writeParts(buffer, List.of(2, 1, 99));
        assertEquals(0, ranges[0][0]);
        assertEquals(1, ranges[0][1]);
        assertEquals(1, ranges[1][0]);
        assertEquals(2, ranges[1][1]);
        assertEquals(0, ranges[2][1]);
        assertEquals(2.0f, buffer.getFloat(2 * EntityPartMotion.PART_BYTES + 96));
        assertEquals(3.0f, buffer.getFloat(2 * EntityPartMotion.PART_BYTES + 112));
    }

    @Test
    void capsTheGpuListAtMaxParts() {
        EntityPartMotion motion = enabled();
        float[] manyCubes = new float[6 * (EntityPartMotion.MAX_PARTS + 5)];
        motion.beginFrame(0, 0, 0, false);
        motion.record(1, ARM, new Matrix4f(), manyCubes);
        motion.beginFrame(0, 0, 0, false);
        motion.record(1, ARM, new Matrix4f(), manyCubes);
        ByteBuffer buffer = ByteBuffer.allocate(EntityPartMotion.BUFFER_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        int[][] ranges = motion.writeParts(buffer, List.of(1, 1));
        assertEquals(EntityPartMotion.MAX_PARTS, ranges[0][1]);
        assertEquals(EntityPartMotion.MAX_PARTS, ranges[1][0]);
        assertEquals(0, ranges[1][1]);
    }

    @Test
    void resetDropsHistoryAndNewPartsWaitAFrame() {
        EntityPartMotion motion = enabled();
        motion.beginFrame(0, 0, 0, false);
        motion.record(1, ARM, new Matrix4f(), UNIT_CUBE);
        motion.beginFrame(0, 0, 0, true);
        motion.record(1, ARM, new Matrix4f(), UNIT_CUBE);
        assertTrue(motion.moved(1).isEmpty());
        assertTrue(motion.animatedEntities().isEmpty());

        motion.beginFrame(0, 0, 0, false);
        motion.record(1, ARM, new Matrix4f(), UNIT_CUBE);
        motion.record(1, HEAD, new Matrix4f(), UNIT_CUBE);
        assertEquals(1, motion.moved(1).size());
        assertEquals(Set.of(1), motion.animatedEntities());
    }

    @Test
    void firstPoseOfAFrameWinsAndDisabledRecordsNothing() {
        EntityPartMotion motion = enabled();
        motion.beginFrame(0, 0, 0, false);
        motion.record(1, ARM, new Matrix4f().translation(0, 0, -2), UNIT_CUBE);
        motion.beginFrame(0, 0, 0, false);
        motion.record(1, ARM, new Matrix4f().translation(0, 0, -3), UNIT_CUBE);
        motion.record(1, ARM, new Matrix4f().translation(0, 0, -9), UNIT_CUBE);
        Vector3f origin = motion.moved(1).getFirst().toLocal().transformPosition(new Vector3f(0, 0, -3));
        assertEquals(0.0f, origin.z, 1.0e-6f);

        motion.setEnabled(false);
        motion.beginFrame(0, 0, 0, false);
        motion.record(1, ARM, new Matrix4f(), UNIT_CUBE);
        motion.beginFrame(0, 0, 0, false);
        motion.record(1, ARM, new Matrix4f(), UNIT_CUBE);
        assertTrue(motion.moved(1).isEmpty());
    }

    @Test
    void partScaledToNothingIsSkipped() {
        EntityPartMotion.Pose flat = new EntityPartMotion.Pose(new Matrix4f().scale(0, 1, 1), UNIT_CUBE);
        EntityPartMotion.Pose normal = new EntityPartMotion.Pose(new Matrix4f(), UNIT_CUBE);
        assertNull(EntityPartMotion.pair(flat, normal, 0, 0, 0));
    }

    @Test
    void writtenRowsMatchJomlRows() {
        Matrix4f toLocal = new Matrix4f().translation(1, 2, 3).rotateY(0.5f);
        EntityPartMotion.Moved moved = new EntityPartMotion.Moved(toLocal, new Matrix4f(), UNIT_CUBE);
        ByteBuffer buffer = ByteBuffer.allocate(EntityPartMotion.PART_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        EntityPartMotion.writeCube(buffer, 0, moved, 0);
        Vector4f row = toLocal.getRow(0, new Vector4f());
        assertEquals(row.x, buffer.getFloat(0));
        assertEquals(1.0f, buffer.getFloat(12));
        assertEquals(3.0f, buffer.getFloat(32 + 12));
    }

    @Test
    void boundsGrowToEveryPoint() {
        float[] bounds = new float[12];
        PartBounds.reset(bounds, 6);
        assertTrue(PartBounds.isEmpty(bounds, 6));
        PartBounds.include(bounds, 6, 1, -2, 3);
        PartBounds.include(bounds, 6, -1, 2, 0);
        assertEquals(-1.0f, bounds[6]);
        assertEquals(-2.0f, bounds[7]);
        assertEquals(0.0f, bounds[8]);
        assertEquals(1.0f, bounds[9]);
        assertEquals(2.0f, bounds[10]);
        assertEquals(3.0f, bounds[11]);
        assertEquals(0.0f, bounds[0]);
    }
}
