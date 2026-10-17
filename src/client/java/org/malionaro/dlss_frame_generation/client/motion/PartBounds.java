package org.malionaro.dlss_frame_generation.client.motion;

/** Axis-aligned boxes packed as {minX, minY, minZ, maxX, maxY, maxZ} from an offset. */
final class PartBounds {
    private PartBounds() {}

    static void reset(float[] bounds, int at) {
        bounds[at] = bounds[at + 1] = bounds[at + 2] = Float.POSITIVE_INFINITY;
        bounds[at + 3] = bounds[at + 4] = bounds[at + 5] = Float.NEGATIVE_INFINITY;
    }

    static void include(float[] bounds, int at, float x, float y, float z) {
        bounds[at] = Math.min(bounds[at], x);
        bounds[at + 1] = Math.min(bounds[at + 1], y);
        bounds[at + 2] = Math.min(bounds[at + 2], z);
        bounds[at + 3] = Math.max(bounds[at + 3], x);
        bounds[at + 4] = Math.max(bounds[at + 4], y);
        bounds[at + 5] = Math.max(bounds[at + 5], z);
    }

    static boolean isEmpty(float[] bounds, int at) {
        return bounds[at] > bounds[at + 3];
    }
}
