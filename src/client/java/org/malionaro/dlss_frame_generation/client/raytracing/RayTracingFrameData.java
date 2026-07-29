package org.malionaro.dlss_frame_generation.client.raytracing;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
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
        boolean reflections, int samples, int quality
) {
    public static final int BYTE_SIZE = 96;

    public static RayTracingFrameData capture(
            Minecraft minecraft,
            int width,
            int height,
            FrameGenConfig config
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
        double fovDegrees = minecraft.options.fov().get();
        float tangentHalfFov = (float) Math.tan(Math.toRadians(fovDegrees) * 0.5);
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

        return new RayTracingFrameData(
                (float) position.x,
                (float) position.y,
                (float) position.z,
                forward,
                right,
                up,
                sunDirection,
                tangentHalfFov,
                height > 0 ? (float) width / height : 1.0f,
                config.rayTracingMaxDistance,
                0.05f,
                Math.max(
                        config.rayTracingMaxDistance,
                        minecraft.options.getEffectiveRenderDistance() * 16.0f
                ),
                config.rayTracingMode == FrameGenConfig.RayTracingMode.SHADOWS_AND_REFLECTIONS,
                config.rayTracingDenoiserEnabled
                        ? Math.max(4, config.rayTracingRaysPerPixel)
                        : config.rayTracingRaysPerPixel,
                config.rayTracingQuality.ordinal()
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
        return data.flip();
    }

    private static void putVec4(ByteBuffer target, float x, float y, float z, float w) {
        target.putFloat(x).putFloat(y).putFloat(z).putFloat(w);
    }
}
