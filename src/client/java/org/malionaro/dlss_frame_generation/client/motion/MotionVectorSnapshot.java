package org.malionaro.dlss_frame_generation.client.motion;

import java.util.Objects;

/**
 * Sparse CPU snapshot of the renderer-owned RG16F motion-vector image.
 *
 * <p>The native image stores the SDK convention (current pixel to previous
 * pixel). Debug arrows deliberately keep that convention: for camera motion it
 * points in the direction the camera turned, and it matches the values sent to
 * DLSS/FSR. The heatmap only uses vector magnitude.</p>
 */
public final class MotionVectorSnapshot {
    public record Vector(float x, float y) {
        public float magnitude() {
            return (float) Math.hypot(x, y);
        }

        public Vector negated() {
            return new Vector(-x, -y);
        }
    }

    private final int sourceWidth;
    private final int sourceHeight;
    private final int sampleStep;
    private final int columns;
    private final int rows;
    private final float[] vectorX;
    private final float[] vectorY;
    private final long frameNumber;
    private final float maximumMagnitude;

    public MotionVectorSnapshot(
            int sourceWidth,
            int sourceHeight,
            int sampleStep,
            int columns,
            int rows,
            float[] vectorX,
            float[] vectorY,
            long frameNumber
    ) {
        if (sourceWidth <= 0 || sourceHeight <= 0 || sampleStep <= 0
                || columns <= 0 || rows <= 0) {
            throw new IllegalArgumentException("Motion-vector snapshot dimensions must be positive");
        }
        int expectedSamples = Math.multiplyExact(columns, rows);
        if (vectorX.length != expectedSamples || vectorY.length != expectedSamples) {
            throw new IllegalArgumentException("Motion-vector snapshot array size mismatch");
        }
        this.sourceWidth = sourceWidth;
        this.sourceHeight = sourceHeight;
        this.sampleStep = sampleStep;
        this.columns = columns;
        this.rows = rows;
        this.vectorX = Objects.requireNonNull(vectorX, "vectorX").clone();
        this.vectorY = Objects.requireNonNull(vectorY, "vectorY").clone();
        this.frameNumber = frameNumber;

        float maximum = 0.0f;
        for (int index = 0; index < expectedSamples; index++) {
            maximum = Math.max(maximum,
                    (float) Math.hypot(this.vectorX[index], this.vectorY[index]));
        }
        this.maximumMagnitude = maximum;
    }

    public Vector sampleRaw(int displayX, int displayY, int displayWidth, int displayHeight) {
        if (displayWidth <= 0 || displayHeight <= 0) {
            return new Vector(0.0f, 0.0f);
        }
        int sourceX = clamp(
                Math.round(displayX * (sourceWidth - 1.0f) / Math.max(1, displayWidth - 1)),
                0,
                sourceWidth - 1
        );
        int sourceY = clamp(
                Math.round(displayY * (sourceHeight - 1.0f) / Math.max(1, displayHeight - 1)),
                0,
                sourceHeight - 1
        );
        int column = clamp(sourceX / sampleStep, 0, columns - 1);
        int row = clamp(sourceY / sampleStep, 0, rows - 1);
        int index = row * columns + column;
        float scaleX = displayWidth / (float) sourceWidth;
        float scaleY = displayHeight / (float) sourceHeight;
        return new Vector(vectorX[index] * scaleX, vectorY[index] * scaleY);
    }

    public Vector sampleVisualFlow(
            int displayX, int displayY, int displayWidth, int displayHeight
    ) {
        return sampleRaw(displayX, displayY, displayWidth, displayHeight);
    }

    public int sourceWidth() {
        return sourceWidth;
    }

    public int sourceHeight() {
        return sourceHeight;
    }

    public int sampleStep() {
        return sampleStep;
    }

    public long frameNumber() {
        return frameNumber;
    }

    public float maximumMagnitude() {
        return maximumMagnitude;
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
