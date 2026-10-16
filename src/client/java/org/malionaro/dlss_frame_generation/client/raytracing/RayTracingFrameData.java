package org.malionaro.dlss_frame_generation.client.raytracing;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.malionaro.dlss_frame_generation.client.render.VulkanRegistry;
import org.malionaro.dlss_frame_generation.client.config.FrameGenConfig;
import org.malionaro.dlss_frame_generation.client.mixin.CameraAccessor;
import org.malionaro.dlss_frame_generation.client.mixin.GameRendererAccessor;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public record RayTracingFrameData(
        float cameraX, float cameraY, float cameraZ,
        Vector3f forward, Vector3f right, Vector3f up,
        Vector3f sunDirection,
        float tangentHalfFov, float aspect, float maxDistance,
        float nearPlane, float farPlane,
        boolean reflections, int samples, int quality,
        float reflectionRange, // meters
        float jitterX, float jitterY
) {
    public static final int BYTE_SIZE = 112;

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
        Vector3f sunDirection = new Vector3f(
                (float) Math.sin(sunAngle),
                (float) Math.cos(sunAngle),
                0.22f
        ).normalize();
        // Clamped horizon - when sun is below horizon (<0) clamp Y to keep shading stable (ambient fallback)
        if (sunDirection.y < 0.0f) {
            sunDirection.y = Math.max(sunDirection.y, 0.05f);
            if (sunDirection.y < 0.05f) {
                sunDirection.y = 0.05f;
            }
            sunDirection.normalize();
        }

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
                config.rayTracingDenoiserEnabled
                        ? Math.max(4, config.rayTracingRaysPerPixel)
                        : config.rayTracingRaysPerPixel,
                quality.ordinal(),
                maxDistance * reflectionFraction,
                // Rays go through the same jittered sample point as the raster.
                VulkanRegistry.jitterPixelsX(),
                VulkanRegistry.jitterPixelsY()
        );
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
        return data.flip();
    }

    private static void putVec4(ByteBuffer target, float x, float y, float z, float w) {
        target.putFloat(x).putFloat(y).putFloat(z).putFloat(w);
    }
}
