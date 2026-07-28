package org.malionaro.dlss_frame_generation.client.motion;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MotionVectorSnapshotTest {
    @Test
    void samplesTheNearestGpuGridCellAndScalesToTheDisplay() {
        MotionVectorSnapshot snapshot = new MotionVectorSnapshot(
                16,
                16,
                8,
                2,
                2,
                new float[] {1.0f, 2.0f, 3.0f, 4.0f},
                new float[] {-1.0f, -2.0f, -3.0f, -4.0f},
                7
        );

        MotionVectorSnapshot.Vector sample =
                snapshot.sampleRaw(31, 31, 32, 32);
        assertEquals(8.0f, sample.x(), 0.0001f);
        assertEquals(-8.0f, sample.y(), 0.0001f);
        assertEquals(7, snapshot.frameNumber());
    }

    @Test
    void debugDirectionMatchesCurrentToPreviousSdkMotion() {
        MotionVectorSnapshot snapshot = new MotionVectorSnapshot(
                8,
                8,
                8,
                1,
                1,
                new float[] {6.0f},
                new float[] {-3.0f},
                1
        );

        MotionVectorSnapshot.Vector visual =
                snapshot.sampleVisualFlow(4, 4, 8, 8);
        assertEquals(6.0f, visual.x(), 0.0001f);
        assertEquals(-3.0f, visual.y(), 0.0001f);
    }

    @Test
    void reportsTheLargestMotionMagnitude() {
        MotionVectorSnapshot snapshot = new MotionVectorSnapshot(
                16,
                8,
                8,
                2,
                1,
                new float[] {3.0f, 5.0f},
                new float[] {4.0f, 12.0f},
                2
        );

        assertEquals(13.0f, snapshot.maximumMagnitude(), 0.0001f);
    }
}
