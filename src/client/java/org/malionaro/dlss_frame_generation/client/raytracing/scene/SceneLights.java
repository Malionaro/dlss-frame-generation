package org.malionaro.dlss_frame_generation.client.raytracing.scene;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Point lights of the ray-traced scene. Each section keeps its own emitters;
 * the shader gets the {@link #MAX_LIGHTS} nearest to the camera, laid out as
 * {@code uint count, 3 x pad, PointLight[count]} with
 * {@code PointLight = vec4 position+radius, vec4 colour+intensity}.
 */
public final class SceneLights {
    public static final int MAX_LIGHTS = 32;
    public static final int BYTES_PER_LIGHT = 32;
    public static final int HEADER_BYTES = 16;
    public static final int BUFFER_BYTES = HEADER_BYTES + MAX_LIGHTS * BYTES_PER_LIGHT;

    public record Emitter(float x, float y, float z, float radius,
                          float red, float green, float blue, float intensity) {
        float distanceSquared(double cameraX, double cameraY, double cameraZ) {
            double dx = x - cameraX;
            double dy = y - cameraY;
            double dz = z - cameraZ;
            return (float) (dx * dx + dy * dy + dz * dz);
        }
    }

    private SceneLights() {}

    /**
     * Light for a block emitting {@code emission} (1..15) with colour
     * {@code rgb} (0xRRGGBB); warm or cool tints for the usual light blocks.
     */
    public static Emitter emitter(float x, float y, float z, int emission, int rgb, LightTint tint) {
        float radius = Math.max(5.0f, emission);
        float intensity = Math.min(2.5f, 0.4f + emission * 0.12f);
        float red;
        float green;
        float blue;
        switch (tint) {
            case FIRE -> { red = 1.0f; green = 0.65f; blue = 0.25f; }
            case COOL -> { red = 0.25f; green = 0.70f; blue = 1.0f; }
            case RED -> { red = 1.0f; green = 0.15f; blue = 0.1f; }
            case WATER -> { red = 0.75f; green = 0.95f; blue = 1.0f; }
            case GOLD -> { red = 1.0f; green = 0.82f; blue = 0.45f; }
            default -> {
                red = Math.max(0.2f, ((rgb >> 16) & 0xFF) / 255.0f);
                green = Math.max(0.2f, ((rgb >> 8) & 0xFF) / 255.0f);
                blue = Math.max(0.2f, (rgb & 0xFF) / 255.0f);
            }
        }
        return new Emitter(x, y, z, radius, red, green, blue, intensity);
    }

    public enum LightTint { FIRE, COOL, RED, WATER, GOLD, BLOCK_COLOR }

    /** The nearest {@link #MAX_LIGHTS} of all sections' emitters, in shader layout. */
    public static ByteBuffer nearest(Collection<List<Emitter>> perSection,
                                     double cameraX, double cameraY, double cameraZ) {
        List<Emitter> all = new ArrayList<>();
        for (List<Emitter> emitters : perSection) {
            all.addAll(emitters);
        }
        all.sort((a, b) -> Float.compare(
                a.distanceSquared(cameraX, cameraY, cameraZ),
                b.distanceSquared(cameraX, cameraY, cameraZ)));
        int count = Math.min(MAX_LIGHTS, all.size());
        ByteBuffer buffer = ByteBuffer.allocateDirect(BUFFER_BYTES).order(ByteOrder.nativeOrder());
        buffer.putInt(count).putInt(0).putInt(0).putInt(0);
        for (int index = 0; index < count; index++) {
            Emitter light = all.get(index);
            buffer.putFloat(light.x()).putFloat(light.y()).putFloat(light.z()).putFloat(light.radius());
            buffer.putFloat(light.red()).putFloat(light.green()).putFloat(light.blue())
                    .putFloat(light.intensity());
        }
        buffer.position(0);
        buffer.limit(BUFFER_BYTES);
        return buffer;
    }
}
