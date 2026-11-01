package org.malionaro.dlss_frame_generation.client.raytracing;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.malionaro.dlss_frame_generation.client.raytracing.cloud.VanillaCloudMeshGenerator;
import org.malionaro.dlss_frame_generation.client.raytracing.scene.RtBoxFormat;
import org.malionaro.dlss_frame_generation.client.raytracing.scene.RtTriangleFormat;

import java.io.InputStream;
import java.nio.ByteBuffer;

/**
 * Vanilla's {@code clouds.png} extruded into square cloud blocks as RT
 * triangles in their own instance mask, so clouds show up in reflections and
 * refraction and cast shadows like any geometry. The extrusion is the
 * voxel mesher from the RTX Remix for Minecraft fork (Apache-2.0, see
 * NOTICE). The mesh covers a window around the camera and is rebuilt only
 * when the camera crosses a cloud cell; the drift is applied as an offset.
 * Render thread.
 */
final class CloudGeometry {
    /** Matches the shader's volumetric cloud base. */
    private static final float CLOUD_BASE = 192.33f;
    private static final float CLOUD_THICKNESS = 4.0f;
    /** Half the window in cells; 2 * 8 cells of 12 blocks around the camera. */
    private static final int HALF_WINDOW = 8;
    private static final double DRIFT_PER_TICK = 0.03;
    private static final double Z_PHASE = 0.33 * VanillaCloudMeshGenerator.CELL_SIZE;
    private static final int TINT = 0xF0F0F0 | RtTriangleFormat.UNTEXTURED_BIT;
    private static final Identifier CLOUDS = Identifier.withDefaultNamespace("textures/environment/clouds.png");

    private static boolean[] occupied;
    private static int textureWidth;
    private static int textureHeight;
    private static boolean loadFailed;

    private static VanillaCloudMeshGenerator.GeneratedMesh mesh;
    private static int meshCellX = Integer.MIN_VALUE;
    private static int meshCellZ = Integer.MIN_VALUE;

    private CloudGeometry() {}

    /**
     * @param sceneOffsetX camera minus scene origin
     * @return triangles written
     */
    static int write(Minecraft minecraft, Vec3 camera,
                     float sceneOffsetX, float sceneOffsetY, float sceneOffsetZ,
                     ByteBuffer positions, ByteBuffer attributes, int maxTriangles) {
        if (minecraft.level == null || minecraft.level.dimension() != Level.OVERWORLD
                || minecraft.options.getCloudStatus() == CloudStatus.OFF || !loadMask(minecraft)) {
            return 0;
        }
        double cell = VanillaCloudMeshGenerator.CELL_SIZE;
        double drift = minecraft.level.getGameTime() * DRIFT_PER_TICK;
        int cellX = (int) Math.floor((camera.x + drift) / cell) - HALF_WINDOW;
        int cellZ = (int) Math.floor((camera.z + Z_PHASE) / cell) - HALF_WINDOW;
        if (mesh == null || cellX != meshCellX || cellZ != meshCellZ) {
            mesh = VanillaCloudMeshGenerator.generate(occupied, textureWidth, textureHeight,
                    cellX, cellZ, 2 * HALF_WINDOW, 2 * HALF_WINDOW,
                    (float) cell, (float) cell, CLOUD_THICKNESS, 0.0f);
            meshCellX = cellX;
            meshCellZ = cellZ;
        }
        // World position of the window's corner, then into scene space.
        float baseX = (float) (cellX * cell - drift - camera.x) + sceneOffsetX;
        float baseY = CLOUD_BASE - (float) camera.y + sceneOffsetY;
        float baseZ = (float) (cellZ * cell - Z_PHASE - camera.z) + sceneOffsetZ;
        float[] vertices = mesh.vertices();
        int[] indices = mesh.indices();
        int flags = RtBoxFormat.withSkyLight(0, 15);
        int triangles = 0;
        for (int index = 0; index + 2 < indices.length && triangles < maxTriangles; index += 3) {
            int a = indices[index] * 6;
            int b = indices[index + 1] * 6;
            int c = indices[index + 2] * 6;
            RtTriangleFormat.putPositions(positions,
                    baseX + vertices[a], baseY + vertices[a + 1], baseZ + vertices[a + 2],
                    baseX + vertices[b], baseY + vertices[b + 1], baseZ + vertices[b + 2],
                    baseX + vertices[c], baseY + vertices[c + 1], baseZ + vertices[c + 2]);
            RtTriangleFormat.putAttributes(attributes, 0, 0, 0, 0, 0, 0, TINT, flags,
                    vertices[a + 3], vertices[a + 4], vertices[a + 5]);
            triangles++;
        }
        return triangles;
    }

    private static boolean loadMask(Minecraft minecraft) {
        if (occupied != null) {
            return true;
        }
        if (loadFailed) {
            return false;
        }
        try {
            var resource = minecraft.getResourceManager().getResource(CLOUDS);
            if (resource.isEmpty()) {
                loadFailed = true;
                return false;
            }
            try (InputStream input = resource.get().open(); NativeImage image = NativeImage.read(input)) {
                textureWidth = image.getWidth();
                textureHeight = image.getHeight();
                int[] pixels = image.getPixelsABGR();
                boolean[] mask = new boolean[pixels.length];
                for (int i = 0; i < pixels.length; i++) {
                    mask[i] = (pixels[i] >>> 24) > 0;
                }
                occupied = mask;
            }
            return true;
        } catch (Exception | LinkageError exception) {
            loadFailed = true;
            return false;
        }
    }
}
