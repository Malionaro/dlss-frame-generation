package org.malionaro.dlss_frame_generation.client.motion;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Exact motion for moving entities, fed to the motion-vector pass.
 *
 * <p>Camera reprojection alone treats a walking mob as static scenery, and the
 * colour search only guesses. Here every nearby moving entity contributes its
 * bounding box and its translation since the last frame; the shader moves
 * pixels inside a box back by that translation before reprojecting. Positions
 * are relative to the current camera and the matrices are built in double
 * precision, so far from the world origin nothing jitters. Limb animation and
 * turning are not covered, only translation.</p>
 *
 * <p>Render thread only.</p>
 */
public final class ObjectMotion {
    public static final ObjectMotion INSTANCE = new ObjectMotion();

    public static final int MAX_ENTITIES = 64;
    /** std140: uint count + pad, then per entity vec4 min, vec4 max, vec4 delta. */
    public static final int BYTES = 16 + MAX_ENTITIES * 48;
    private static final double RANGE = 96.0;
    /** Limbs, held items and nametags reach outside the hitbox. */
    private static final double BOX_MARGIN = 0.2;

    public record Box(float minX, float minY, float minZ,
                      float maxX, float maxY, float maxZ,
                      float deltaX, float deltaY, float deltaZ,
                      double distanceSquared) {}

    private final Map<Integer, Vec3> lastPositions = new HashMap<>();
    private List<Box> boxes = List.of();
    private final float[] clipToCameraRelative = new float[16];
    private final float[] cameraRelativeToPreviousClip = new float[16];
    private final Matrix4d previousViewProjection = new Matrix4d();
    private double previousCameraX;
    private double previousCameraY;
    private double previousCameraZ;
    private boolean havePrevious;

    private ObjectMotion() {
        new Matrix4f().get(clipToCameraRelative);
        new Matrix4f().get(cameraRelativeToPreviousClip);
    }

    /**
     * @param projection unjittered projection of this frame
     * @param reset      discontinuous camera change: no history to reproject to
     */
    public void update(Minecraft minecraft, Vec3 camera, Vector3f forward, Vector3f up,
                       Matrix4f projection, boolean reset) {
        Matrix4d viewProjection = new Matrix4d(projection).mul(new Matrix4d().lookAt(
                0.0, 0.0, 0.0, forward.x, forward.y, forward.z, up.x, up.y, up.z));
        new Matrix4f(new Matrix4d(viewProjection).invert()).get(clipToCameraRelative);
        if (reset || !havePrevious) {
            previousViewProjection.set(viewProjection);
            previousCameraX = camera.x;
            previousCameraY = camera.y;
            previousCameraZ = camera.z;
        }
        // Current camera-relative point -> world -> previous camera-relative -> previous clip.
        Matrix4d toPrevious = new Matrix4d(previousViewProjection).translate(
                camera.x - previousCameraX, camera.y - previousCameraY, camera.z - previousCameraZ);
        new Matrix4f(toPrevious).get(cameraRelativeToPreviousClip);

        previousViewProjection.set(viewProjection);
        previousCameraX = camera.x;
        previousCameraY = camera.y;
        previousCameraZ = camera.z;
        havePrevious = true;

        collectEntities(minecraft, camera, reset);
    }

    private void collectEntities(Minecraft minecraft, Vec3 camera, boolean reset) {
        if (minecraft == null || minecraft.level == null) {
            lastPositions.clear();
            boxes = List.of();
            return;
        }
        float partialTick = minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(true);
        Entity cameraEntity = minecraft.getCameraEntity();
        boolean firstPerson = minecraft.options.getCameraType().isFirstPerson();
        List<Box> candidates = new ArrayList<>();
        Map<Integer, Vec3> seen = new HashMap<>();
        for (Entity entity : minecraft.level.entitiesForRendering()) {
            if (entity.isRemoved() || (firstPerson && entity == cameraEntity)) {
                continue;
            }
            Vec3 position = entity.getPosition(partialTick);
            seen.put(entity.getId(), position);
            Vec3 last = lastPositions.get(entity.getId());
            if (reset || last == null) {
                continue;
            }
            double dx = position.x - last.x;
            double dy = position.y - last.y;
            double dz = position.z - last.z;
            if (dx * dx + dy * dy + dz * dz < 1.0e-8) {
                continue;
            }
            double distanceSquared = position.distanceToSqr(camera);
            if (distanceSquared > RANGE * RANGE) {
                continue;
            }
            Vec3 offset = position.subtract(entity.position());
            AABB box = entity.getBoundingBox().move(offset).inflate(BOX_MARGIN);
            candidates.add(new Box(
                    (float) (box.minX - camera.x), (float) (box.minY - camera.y), (float) (box.minZ - camera.z),
                    (float) (box.maxX - camera.x), (float) (box.maxY - camera.y), (float) (box.maxZ - camera.z),
                    (float) dx, (float) dy, (float) dz, distanceSquared));
        }
        lastPositions.clear();
        lastPositions.putAll(seen);
        boxes = nearest(candidates, MAX_ENTITIES);
    }

    /** The {@code max} boxes closest to the camera. */
    static List<Box> nearest(List<Box> candidates, int max) {
        List<Box> sorted = new ArrayList<>(candidates);
        sorted.sort((a, b) -> Double.compare(a.distanceSquared(), b.distanceSquared()));
        return List.copyOf(sorted.subList(0, Math.min(max, sorted.size())));
    }

    public float[] clipToCameraRelative() {
        return clipToCameraRelative;
    }

    public float[] cameraRelativeToPreviousClip() {
        return cameraRelativeToPreviousClip;
    }

    public List<Box> boxes() {
        return boxes;
    }

    /** Writes the entity block of the motion uniform at {@code offset}. */
    public static void writeBoxes(ByteBuffer target, int offset, List<Box> boxes) {
        int count = Math.min(MAX_ENTITIES, boxes.size());
        target.putInt(offset, count);
        target.putInt(offset + 4, 0);
        target.putInt(offset + 8, 0);
        target.putInt(offset + 12, 0);
        int base = offset + 16;
        for (int index = 0; index < count; index++) {
            Box box = boxes.get(index);
            int at = base + index * 48;
            target.putFloat(at, box.minX()).putFloat(at + 4, box.minY())
                    .putFloat(at + 8, box.minZ()).putFloat(at + 12, 0.0f);
            target.putFloat(at + 16, box.maxX()).putFloat(at + 20, box.maxY())
                    .putFloat(at + 24, box.maxZ()).putFloat(at + 28, 0.0f);
            target.putFloat(at + 32, box.deltaX()).putFloat(at + 36, box.deltaY())
                    .putFloat(at + 40, box.deltaZ()).putFloat(at + 44, 0.0f);
        }
    }
}
