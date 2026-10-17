package org.malionaro.dlss_frame_generation.client.raytracing;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.malionaro.dlss_frame_generation.client.render.VulkanRegistry;
import org.malionaro.dlss_frame_generation.client.config.FrameGenConfig;
import org.malionaro.dlss_frame_generation.client.mixin.CameraAccessor;
import org.malionaro.dlss_frame_generation.client.mixin.GameRendererAccessor;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

public record RayTracingFrameData(
        float cameraX, float cameraY, float cameraZ,
        Vector3f forward, Vector3f right, Vector3f up,
        Vector3f sunDirection,
        float tangentHalfFov, float aspect, float maxDistance,
        float nearPlane, float farPlane,
        boolean reflections, int samples, int quality,
        float reflectionRange, // meters
        float jitterX, float jitterY,
        float rain, float thunder, float timeSeconds, int frameIndex,
        int originX, int originY, int originZ, boolean overworld, float cameraSkyLight,
        float[] entityBoxes, int entityCount,
        float[] clipToView
) {
    /** Entities cast RT shadows as boxes; the nearest ones are kept. */
    public static final int MAX_ENTITIES = 32;
    public static final int BYTE_SIZE = 112 + 48 + 64 + MAX_ENTITIES * 32;
    private static final long START_NANOS = System.nanoTime();
    private static int frameCounter;

    /** Camera position of the current frame, or null without a camera. */
    public static Vec3 cameraPosition(Minecraft minecraft) {
        if (minecraft == null
                || !(minecraft.gameRenderer instanceof GameRendererAccessor renderer)
                || !(renderer.dlss_fg$getMainCamera() instanceof CameraAccessor cameraAccess)) {
            return null;
        }
        return cameraAccess.dlss_fg$getPosition();
    }

    /**
     * @param originX scene origin the slot's TLAS was built for; the camera is
     *                sent relative to it so it stays exact far from world origin
     */
    public static RayTracingFrameData capture(
            Minecraft minecraft,
            int width,
            int height,
            FrameGenConfig config,
            int originX,
            int originY,
            int originZ
    ) {
        if (minecraft == null
                || !(minecraft.gameRenderer instanceof GameRendererAccessor renderer)
                || !(renderer.dlss_fg$getMainCamera() instanceof CameraAccessor cameraAccess)) {
            return null;
        }

        Camera camera = (Camera) cameraAccess;
        Vec3 position = cameraAccess.dlss_fg$getPosition();
        Vector3f forward = new Vector3f(cameraAccess.dlss_fg$getForwards()).normalize();
        Vector3f up = new Vector3f(cameraAccess.dlss_fg$getUp()).normalize();
        Vector3f right = new Vector3f(cameraAccess.dlss_fg$getLeft()).negate().normalize();
        // The real projection, not the FOV option: sprinting, speed effects and
        // the spyglass change the field of view and the rays must follow.
        Matrix4f projection = VulkanRegistry.cameraProjection();
        float projectionY = Math.abs(projection.m11());
        float projectionX = Math.abs(projection.m00());
        float tangentHalfFov = projectionY > 1.0e-6f
                ? 1.0f / projectionY
                : (float) Math.tan(Math.toRadians(minecraft.options.fov().get()) * 0.5);
        float aspect = projectionX > 1.0e-6f && projectionY > 1.0e-6f
                ? projectionY / projectionX
                : height > 0 ? (float) width / height : 1.0f;
        double dayFraction = Math.floorMod(
                minecraft.level.getOverworldClockTime(),
                24_000L
        ) / 24_000.0;
        double sunAngle = (dayFraction - 0.25) * Math.PI * 2.0;
        // Below the horizon at night; the shader lights with the moon then.
        Vector3f sunDirection = new Vector3f(
                (float) Math.sin(sunAngle),
                (float) Math.cos(sunAngle),
                0.22f
        ).normalize();
        float partialTick = minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(true);
        float[] entityBoxes = new float[MAX_ENTITIES * 6];
        int entityCount = collectEntityBoxes(minecraft, position, config.rayTracingMaxDistance,
                partialTick, originX, originY, originZ, entityBoxes);

        float maxDistance = config.rayTracingMaxDistance;
        // Quality selects an explicit reflection range instead of leaking
        // the ordinal into the shader through cameraForward.w.
        FrameGenConfig.RayTracingQuality quality = config.rayTracingQuality != null
                ? config.rayTracingQuality
                : FrameGenConfig.RayTracingQuality.MEDIUM;
        float reflectionFraction = switch (quality) {
            case LOW -> 0.25f;
            case MEDIUM -> 0.4f;
            case HIGH -> 0.6f;
            case ULTRA -> 0.8f;
        };

        return new RayTracingFrameData(
                (float) (position.x - originX),
                (float) (position.y - originY),
                (float) (position.z - originZ),
                forward,
                right,
                up,
                sunDirection,
                tangentHalfFov,
                aspect,
                maxDistance,
                0.05f,
                Math.max(
                        maxDistance,
                        minecraft.options.getEffectiveRenderDistance() * 16.0f
                ),
                config.rayTracingMode == FrameGenConfig.RayTracingMode.SHADOWS_AND_REFLECTIONS,
                // The user's rays per pixel; a denoiser no longer forces 4,
                // which made RT far too slow.
                config.rayTracingRaysPerPixel,
                quality.ordinal(),
                maxDistance * reflectionFraction,
                // Rays go through the same jittered sample point as the raster.
                VulkanRegistry.jitterPixelsX(),
                VulkanRegistry.jitterPixelsY(),
                minecraft.level.getRainLevel(partialTick),
                minecraft.level.getThunderLevel(partialTick),
                (float) (((System.nanoTime() - START_NANOS) / 1.0e9) % 3600.0),
                frameCounter++,
                originX, originY, originZ,
                minecraft.level.dimension() == Level.OVERWORLD,
                // Light shafts only where the camera itself sees the sky.
                minecraft.level.getBrightness(LightLayer.SKY, BlockPos.containing(position)) / 15.0f,
                entityBoxes,
                entityCount,
                // The raster's own inverse projection: depth is converted
                // exactly as it was written, whatever the depth convention.
                new Matrix4f(projection).invert().get(new float[16])
        );
    }

    /** Writes the nearest entity boxes, relative to the scene origin, and returns their count. */
    private static int collectEntityBoxes(Minecraft minecraft, Vec3 camera, float range, float partialTick,
                                          int originX, int originY, int originZ, float[] boxes) {
        List<AABB> candidates = new ArrayList<>();
        List<Double> distances = new ArrayList<>();
        for (Entity entity : minecraft.level.entitiesForRendering()) {
            if (entity.isRemoved() || entity.isInvisible()) {
                continue;
            }
            Vec3 position = entity.getPosition(partialTick);
            double distanceSquared = position.distanceToSqr(camera);
            if (distanceSquared > range * (double) range) {
                continue;
            }
            candidates.add(entity.getBoundingBox().move(position.subtract(entity.position())));
            distances.add(distanceSquared);
        }
        Integer[] order = new Integer[candidates.size()];
        for (int index = 0; index < order.length; index++) {
            order[index] = index;
        }
        java.util.Arrays.sort(order, (a, b) -> Double.compare(distances.get(a), distances.get(b)));
        int count = Math.min(MAX_ENTITIES, order.length);
        for (int slot = 0; slot < count; slot++) {
            AABB box = candidates.get(order[slot]);
            int at = slot * 6;
            boxes[at] = (float) (box.minX - originX);
            boxes[at + 1] = (float) (box.minY - originY);
            boxes[at + 2] = (float) (box.minZ - originZ);
            boxes[at + 3] = (float) (box.maxX - originX);
            boxes[at + 4] = (float) (box.maxY - originY);
            boxes[at + 5] = (float) (box.maxZ - originZ);
        }
        return count;
    }

    public ByteBuffer toNativeBuffer() {
        ByteBuffer data = ByteBuffer.allocateDirect(BYTE_SIZE)
                .order(ByteOrder.nativeOrder());
        putVec4(data, cameraX, cameraY, cameraZ, 1.0f);
        putVec4(data, forward.x, forward.y, forward.z, quality);
        putVec4(data, right.x, right.y, right.z, nearPlane);
        putVec4(data, up.x, up.y, up.z, farPlane);
        putVec4(data, tangentHalfFov, aspect, maxDistance, reflections ? 1.0f : 0.0f);
        putVec4(
                data,
                sunDirection.x,
                sunDirection.y,
                sunDirection.z,
                samples
        );
        putVec4(data, reflectionRange, jitterX, jitterY, 0.0f);
        data.putFloat(rain).putFloat(thunder).putFloat(timeSeconds).putInt(frameIndex);
        data.putFloat(originX).putFloat(originY).putFloat(originZ).putInt(entityCount);
        putVec4(data, overworld ? 1.0f : 0.0f, cameraSkyLight, 0.0f, 0.0f);
        for (float value : clipToView) {
            data.putFloat(value);
        }
        for (int slot = 0; slot < MAX_ENTITIES; slot++) {
            int at = slot * 6;
            boolean used = slot < entityCount;
            putVec4(data, used ? entityBoxes[at] : 0, used ? entityBoxes[at + 1] : 0,
                    used ? entityBoxes[at + 2] : 0, 0.0f);
            putVec4(data, used ? entityBoxes[at + 3] : 0, used ? entityBoxes[at + 4] : 0,
                    used ? entityBoxes[at + 5] : 0, 0.0f);
        }
        return data.flip();
    }

    private static void putVec4(ByteBuffer target, float x, float y, float z, float w) {
        target.putFloat(x).putFloat(y).putFloat(z).putFloat(w);
    }
}
