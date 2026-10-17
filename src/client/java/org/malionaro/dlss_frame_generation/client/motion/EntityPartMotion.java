package org.malionaro.dlss_frame_generation.client.motion;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4f;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Model-part poses of entities for per-pixel motion of limbs, heads and
 * turning bodies.
 *
 * <p>Minecraft entity models are rigid cuboid parts, so the previous position
 * of any visible model point follows from its part's current and previous
 * transform: {@code previous = P_prev * inverse(P_cur) * current}. Every frame
 * the poses of drawn parts are recorded (camera-relative, as submitted) with
 * the local bounds of their cubes. The GPU gets one entry per cube; the motion
 * shader looks for the cube whose face holds a pixel's surface point and moves
 * the point through that part's transforms. Nothing extra is rasterised, so
 * this needs no hooks into entity shaders and does not depend on how a
 * renderer mod draws the cubes.</p>
 *
 * <p>Parts are keyed by entity id plus the part object, which models keep for
 * their lifetime. {@link #beginFrame} must run once per world frame, before
 * that frame's entities are drawn; it turns the last frame's poses into
 * history. {@link #record} may come from any thread.</p>
 */
public final class EntityPartMotion {
    public static final EntityPartMotion INSTANCE = new EntityPartMotion();

    /** GPU entries, one per cube. */
    public static final int MAX_PARTS = 2048;
    /** Per cube, std430: vec4 toLocal[3], vec4 toPrevious[3], vec4 boundsMin, vec4 boundsMax. */
    public static final int PART_BYTES = 128;
    public static final int BUFFER_BYTES = MAX_PARTS * PART_BYTES;
    /** Parts kept per frame; guards against growth while no frame boundary comes. */
    static final int MAX_RECORDS = 8192;

    /**
     * Pose of one drawn part, relative to that frame's camera, and the local
     * bounds of its cubes as {minX, minY, minZ, maxX, maxY, maxZ} per cube.
     */
    public record Pose(Matrix4f pose, float[] cubes) {}

    /**
     * A part seen in both frames. {@code toLocal} takes a point relative to the
     * current camera into part space, {@code toPrevious} takes it from part
     * space to its previous position, again relative to the current camera.
     */
    public record Moved(Matrix4f toLocal, Matrix4f toPrevious, float[] cubes) {
        public int cubeCount() {
            return cubes.length / 6;
        }
    }

    /** Per entity, per part object. Insertion order keeps the GPU list stable. */
    private Map<Integer, Map<Object, Pose>> current = new HashMap<>();
    private Map<Integer, Map<Object, Pose>> previous = new HashMap<>();
    private int recordCount;
    private double cameraMoveX;
    private double cameraMoveY;
    private double cameraMoveZ;
    private volatile boolean enabled;

    EntityPartMotion() {}

    /** Capture is off with shader packs: their entity passes are not ours to model. */
    public void setEnabled(boolean enabled) {
        if (this.enabled == enabled) {
            return;
        }
        synchronized (this) {
            this.enabled = enabled;
            current = new HashMap<>();
            previous = new HashMap<>();
            recordCount = 0;
        }
    }

    public boolean enabled() {
        return enabled;
    }

    /**
     * Starts a world frame: the poses recorded since the last call become the
     * history.
     *
     * @param reset discontinuous change: drop all history
     */
    public synchronized void beginFrame(double previousCameraMinusCurrentX,
                                        double previousCameraMinusCurrentY,
                                        double previousCameraMinusCurrentZ,
                                        boolean reset) {
        previous = reset ? new HashMap<>() : current;
        current = new HashMap<>();
        recordCount = 0;
        cameraMoveX = previousCameraMinusCurrentX;
        cameraMoveY = previousCameraMinusCurrentY;
        cameraMoveZ = previousCameraMinusCurrentZ;
    }

    /**
     * Stores the pose a part is drawn with this frame; the first one wins.
     * {@code cubes} is kept, not copied.
     */
    public synchronized void record(int entityId, Object part, Matrix4fc pose, float[] cubes) {
        if (!enabled || recordCount >= MAX_RECORDS || cubes.length < 6) {
            return;
        }
        Map<Object, Pose> parts = current.computeIfAbsent(entityId, id -> new LinkedHashMap<>());
        if (parts.containsKey(part)) {
            return;
        }
        parts.put(part, new Pose(new Matrix4f(pose), cubes));
        recordCount++;
    }

    /** Entities whose parts were recorded in the last frame (candidates for motion). */
    public synchronized Set<Integer> animatedEntities() {
        return new HashSet<>(previous.keySet());
    }

    /** Parts of {@code entityId} drawn in this frame and the last one. */
    public synchronized List<Moved> moved(int entityId) {
        Map<Object, Pose> now = current.get(entityId);
        Map<Object, Pose> before = previous.get(entityId);
        if (now == null || before == null) {
            return List.of();
        }
        List<Moved> result = new ArrayList<>(now.size());
        for (Map.Entry<Object, Pose> entry : now.entrySet()) {
            Pose old = before.get(entry.getKey());
            if (old == null) {
                continue;
            }
            Moved moved = pair(entry.getValue(), old, cameraMoveX, cameraMoveY, cameraMoveZ);
            if (moved != null) {
                result.add(moved);
            }
        }
        return result;
    }

    /** Null when the current pose cannot be inverted (part scaled to nothing). */
    static Moved pair(Pose now, Pose before, double cameraMoveX, double cameraMoveY, double cameraMoveZ) {
        if (Math.abs(now.pose().determinant3x3()) < 1.0e-12f) {
            return null;
        }
        Matrix4f toLocal = new Matrix4f(now.pose()).invertAffine();
        if (!toLocal.isFinite()) {
            return null;
        }
        Matrix4f toPrevious = new Matrix4f()
                .translation((float) cameraMoveX, (float) cameraMoveY, (float) cameraMoveZ)
                .mul(before.pose());
        return new Moved(toLocal, toPrevious, now.cubes());
    }

    /**
     * Writes one entry per cube for each entity in {@code entityIds} (in
     * order) until the buffer is full. Returns {first, count} per entity.
     */
    public int[][] writeParts(ByteBuffer target, List<Integer> entityIds) {
        int[][] ranges = new int[entityIds.size()][2];
        int written = 0;
        for (int index = 0; index < entityIds.size(); index++) {
            ranges[index][0] = written;
            for (Moved part : moved(entityIds.get(index))) {
                for (int cube = 0; cube < part.cubeCount() && written < MAX_PARTS; cube++) {
                    writeCube(target, written * PART_BYTES, part, cube);
                    written++;
                }
            }
            ranges[index][1] = written - ranges[index][0];
        }
        return ranges;
    }

    static void writeCube(ByteBuffer target, int offset, Moved part, int cube) {
        Vector4f row = new Vector4f();
        for (int r = 0; r < 3; r++) {
            part.toLocal().getRow(r, row);
            putVec4(target, offset + r * 16, row.x, row.y, row.z, row.w);
            part.toPrevious().getRow(r, row);
            putVec4(target, offset + 48 + r * 16, row.x, row.y, row.z, row.w);
        }
        float[] bounds = part.cubes();
        int at = cube * 6;
        putVec4(target, offset + 96, bounds[at], bounds[at + 1], bounds[at + 2], 0.0f);
        putVec4(target, offset + 112, bounds[at + 3], bounds[at + 4], bounds[at + 5], 0.0f);
    }

    private static void putVec4(ByteBuffer target, int at, float x, float y, float z, float w) {
        target.putFloat(at, x).putFloat(at + 4, y).putFloat(at + 8, z).putFloat(at + 12, w);
    }
}
