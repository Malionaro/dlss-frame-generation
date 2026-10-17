package org.malionaro.dlss_frame_generation.client.raytracing;

import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleGroup;
import net.minecraft.core.SectionPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.malionaro.dlss_frame_generation.client.mixin.ParticleAccessor;
import org.malionaro.dlss_frame_generation.client.mixin.ParticleEngineAccessor;
import org.malionaro.dlss_frame_generation.client.mixin.ParticleGroupAccessor;
import org.malionaro.dlss_frame_generation.client.mixin.SingleQuadParticleAccessor;
import org.malionaro.dlss_frame_generation.client.raytracing.scene.RtBoxFormat;
import org.malionaro.dlss_frame_generation.client.raytracing.scene.RtTriangleFormat;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Live particles as small RT cubes in their own instance mask, so smoke,
 * flames and splashes show up in RT reflections and water, and the RT pass
 * knows where a particle sits in front of a surface (it keeps the textured
 * raster particle there). Flames, lava, sparks and glow particles are
 * emissive. Render thread.
 */
final class ParticleGeometry {
    static final int MAX_PARTICLES = 1024;
    private static final int NEUTRAL = 0xD8D8D8;

    private record Sample(double x, double y, double z, float half, int rgb, boolean emissive, double distanceSq) {}

    /** Sections holding particles in the last collected frame (raster terrain stays there). */
    private static volatile Set<Long> particleSections = Set.of();

    private ParticleGeometry() {}

    static boolean sectionHasParticles(long key) {
        return particleSections.contains(key);
    }

    /**
     * @param sceneOffset camera minus scene origin
     * @return triangles written
     */
    static int write(Minecraft minecraft, Vec3 camera, float range,
                     float sceneOffsetX, float sceneOffsetY, float sceneOffsetZ,
                     ByteBuffer positions, ByteBuffer attributes, int maxTriangles) {
        List<Sample> samples = collect(minecraft, camera, range);
        Set<Long> sections = new HashSet<>();
        int triangles = 0;
        for (Sample sample : samples) {
            if (triangles + 12 > maxTriangles) {
                break;
            }
            int sectionX = SectionPos.blockToSectionCoord(sample.x());
            int sectionY = SectionPos.blockToSectionCoord(sample.y());
            int sectionZ = SectionPos.blockToSectionCoord(sample.z());
            // The section and its neighbours: what is seen through the particle.
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        sections.add(SectionPos.asLong(sectionX + dx, sectionY + dy, sectionZ + dz));
                    }
                }
            }
            float cx = (float) (sample.x() - camera.x) + sceneOffsetX;
            float cy = (float) (sample.y() - camera.y) + sceneOffsetY;
            float cz = (float) (sample.z() - camera.z) + sceneOffsetZ;
            int flags = RtBoxFormat.withSkyLight(sample.emissive() ? RtBoxFormat.FLAG_EMISSIVE : 0, 15);
            int tint = (sample.rgb() & 0xFFFFFF) | RtTriangleFormat.UNTEXTURED_BIT;
            triangles += cube(positions, attributes, cx, cy, cz, sample.half(), tint, flags);
        }
        particleSections = sections;
        return triangles;
    }

    private static List<Sample> collect(Minecraft minecraft, Vec3 camera, float range) {
        List<Sample> samples = new ArrayList<>();
        if (minecraft == null || minecraft.particleEngine == null) {
            return samples;
        }
        double rangeSq = (double) range * range;
        for (ParticleGroup<?> group : ((ParticleEngineAccessor) minecraft.particleEngine).dlss_fg$particles().values()) {
            for (Particle particle : ((ParticleGroupAccessor) group).dlss_fg$particles()) {
                if (!particle.isAlive()) {
                    continue;
                }
                ParticleAccessor access = (ParticleAccessor) particle;
                double x = access.dlss_fg$x();
                double y = access.dlss_fg$y();
                double z = access.dlss_fg$z();
                double dx = x - camera.x;
                double dy = y - camera.y;
                double dz = z - camera.z;
                double distanceSq = dx * dx + dy * dy + dz * dz;
                if (distanceSq > rangeSq) {
                    continue;
                }
                float half;
                int rgb;
                if (particle instanceof SingleQuadParticleAccessor quad) {
                    if (quad.dlss_fg$alpha() < 0.1f) {
                        continue;
                    }
                    half = Math.clamp(quad.dlss_fg$quadSize(), 0.02f, 1.0f) * 0.5f;
                    rgb = multiply(NEUTRAL, quad.dlss_fg$red(), quad.dlss_fg$green(), quad.dlss_fg$blue());
                } else {
                    AABB box = particle.getBoundingBox();
                    half = (float) Math.clamp(box.getXsize() * 0.5, 0.02, 1.0);
                    rgb = NEUTRAL;
                }
                samples.add(new Sample(x, y, z, half, rgb, emissive(particle), distanceSq));
            }
        }
        samples.sort(Comparator.comparingDouble(Sample::distanceSq));
        return samples.size() > MAX_PARTICLES ? samples.subList(0, MAX_PARTICLES) : samples;
    }

    private static boolean emissive(Particle particle) {
        String name = particle.getClass().getSimpleName().toLowerCase(Locale.ROOT);
        return name.contains("flame") || name.contains("lava") || name.contains("spark")
                || name.contains("glow") || name.contains("fire") || name.contains("soul")
                || name.contains("electric") || name.contains("end_rod") || name.contains("endrod");
    }

    static int multiply(int rgb, float red, float green, float blue) {
        int r = Math.clamp(Math.round(((rgb >> 16) & 0xFF) * red), 0, 255);
        int g = Math.clamp(Math.round(((rgb >> 8) & 0xFF) * green), 0, 255);
        int b = Math.clamp(Math.round((rgb & 0xFF) * blue), 0, 255);
        return r << 16 | g << 8 | b;
    }

    /** Axis-aligned cube as 12 triangles; returns 12. */
    static int cube(ByteBuffer positions, ByteBuffer attributes,
                    float cx, float cy, float cz, float half, int tint, int flags) {
        float x0 = cx - half;
        float y0 = cy - half;
        float z0 = cz - half;
        float x1 = cx + half;
        float y1 = cy + half;
        float z1 = cz + half;
        face(positions, attributes, x0, y0, z0, x0, y1, z0, x0, y1, z1, x0, y0, z1, -1, 0, 0, tint, flags);
        face(positions, attributes, x1, y0, z0, x1, y0, z1, x1, y1, z1, x1, y1, z0, 1, 0, 0, tint, flags);
        face(positions, attributes, x0, y0, z0, x0, y0, z1, x1, y0, z1, x1, y0, z0, 0, -1, 0, tint, flags);
        face(positions, attributes, x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1, 0, 1, 0, tint, flags);
        face(positions, attributes, x0, y0, z0, x1, y0, z0, x1, y1, z0, x0, y1, z0, 0, 0, -1, tint, flags);
        face(positions, attributes, x0, y0, z1, x0, y1, z1, x1, y1, z1, x1, y0, z1, 0, 0, 1, tint, flags);
        return 12;
    }

    private static void face(ByteBuffer positions, ByteBuffer attributes,
                             float ax, float ay, float az, float bx, float by, float bz,
                             float cx, float cy, float cz, float dx, float dy, float dz,
                             float nx, float ny, float nz, int tint, int flags) {
        RtTriangleFormat.putPositions(positions, ax, ay, az, bx, by, bz, cx, cy, cz);
        RtTriangleFormat.putAttributes(attributes, 0, 0, 0, 0, 0, 0, tint, flags, nx, ny, nz);
        RtTriangleFormat.putPositions(positions, ax, ay, az, cx, cy, cz, dx, dy, dz);
        RtTriangleFormat.putAttributes(attributes, 0, 0, 0, 0, 0, 0, tint, flags, nx, ny, nz);
    }
}
