package org.malionaro.dlss_frame_generation.client.motion;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import org.malionaro.dlss_frame_generation.client.mixin.RenderSetupAccessor;
import org.malionaro.dlss_frame_generation.client.mixin.RenderTypeAccessor;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Average colour of each cube of an entity model part, from the texture the
 * model is drawn with, so mobs in RT reflections and refraction have their
 * colours (heads, bodies, legs apart) instead of plain grey.
 *
 * <p>Textures are read once per texture on a background thread; until one
 * is loaded its parts use {@link #FALLBACK}. Best effort: anything that
 * cannot be read (dynamic textures, player skins still downloading) keeps the
 * fallback.</p>
 */
public final class EntityTextureColors {
    public static final int FALLBACK = 0x8C847C;
    private static final int SAMPLES = 6;

    private record Texture(int width, int height, int[] abgr) {}

    private static final Map<Identifier, Optional<Texture>> TEXTURES = new ConcurrentHashMap<>();
    private static final Map<ModelPart, Map<Identifier, int[]>> CUBE_COLORS =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final ExecutorService LOADER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "dlss_fg entity texture colours");
        thread.setDaemon(true);
        return thread;
    });
    private static volatile Method bindingLocation;

    private EntityTextureColors() {}

    /** Main texture of a render type, or null. */
    public static Identifier textureOf(RenderType type) {
        if (!(type instanceof RenderTypeAccessor access)) {
            return null;
        }
        try {
            Map<String, ?> textures = ((RenderSetupAccessor) (Object) access.dlss_fg$setup()).dlss_fg$textures();
            Object binding = textures.get("Sampler0");
            if (binding == null && !textures.isEmpty()) {
                binding = textures.values().iterator().next();
            }
            if (binding == null) {
                return null;
            }
            Method location = bindingLocation;
            if (location == null) {
                location = binding.getClass().getDeclaredMethod("location");
                location.setAccessible(true);
                bindingLocation = location;
            }
            return location.invoke(binding) instanceof Identifier identifier ? identifier : null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            return null;
        }
    }

    /**
     * Per-cube rgb of {@code part} under {@code texture}, multiplied by the
     * submit's ARGB tint; null while the texture is not loaded yet.
     */
    public static int[] cubeColors(ModelPart part, List<ModelPart.Cube> cubes, Identifier texture, int tint) {
        if (texture == null) {
            return null;
        }
        Optional<Texture> loaded = TEXTURES.get(texture);
        if (loaded == null) {
            TEXTURES.put(texture, Optional.empty());
            LOADER.execute(() -> load(texture));
            return null;
        }
        if (loaded.isEmpty()) {
            return null;
        }
        int[] colors;
        synchronized (CUBE_COLORS) {
            colors = CUBE_COLORS.computeIfAbsent(part, key -> new ConcurrentHashMap<>())
                    .computeIfAbsent(texture, key -> average(cubes, loaded.get()));
        }
        if ((tint & 0xFFFFFF) == 0xFFFFFF) {
            return colors;
        }
        int[] tinted = new int[colors.length];
        for (int index = 0; index < colors.length; index++) {
            tinted[index] = multiply(colors[index], tint);
        }
        return tinted;
    }

    private static void load(Identifier texture) {
        try {
            var resource = Minecraft.getInstance().getResourceManager().getResource(texture);
            if (resource.isEmpty()) {
                return;
            }
            try (InputStream input = resource.get().open(); NativeImage image = NativeImage.read(input)) {
                TEXTURES.put(texture, Optional.of(
                        new Texture(image.getWidth(), image.getHeight(), image.getPixelsABGR())));
            }
        } catch (Exception | LinkageError ignored) {
            // Keeps the fallback colour.
        }
    }

    static int[] average(List<ModelPart.Cube> cubes, Texture texture) {
        int[] colors = new int[cubes.size()];
        for (int index = 0; index < cubes.size(); index++) {
            long red = 0;
            long green = 0;
            long blue = 0;
            int count = 0;
            for (ModelPart.Polygon polygon : cubes.get(index).polygons) {
                float minU = 1.0f;
                float minV = 1.0f;
                float maxU = 0.0f;
                float maxV = 0.0f;
                for (ModelPart.Vertex vertex : polygon.vertices()) {
                    minU = Math.min(minU, vertex.u());
                    minV = Math.min(minV, vertex.v());
                    maxU = Math.max(maxU, vertex.u());
                    maxV = Math.max(maxV, vertex.v());
                }
                for (int sy = 0; sy < SAMPLES; sy++) {
                    for (int sx = 0; sx < SAMPLES; sx++) {
                        float u = minU + (maxU - minU) * (sx + 0.5f) / SAMPLES;
                        float v = minV + (maxV - minV) * (sy + 0.5f) / SAMPLES;
                        int x = Math.clamp((int) (u * texture.width()), 0, texture.width() - 1);
                        int y = Math.clamp((int) (v * texture.height()), 0, texture.height() - 1);
                        int abgr = texture.abgr()[y * texture.width() + x];
                        if ((abgr >>> 24) < 128) {
                            continue;
                        }
                        red += abgr & 0xFF;
                        green += (abgr >> 8) & 0xFF;
                        blue += (abgr >> 16) & 0xFF;
                        count++;
                    }
                }
            }
            colors[index] = count == 0 ? FALLBACK
                    : (int) (red / count) << 16 | (int) (green / count) << 8 | (int) (blue / count);
        }
        return colors;
    }

    static int multiply(int rgb, int argbTint) {
        int red = ((rgb >> 16) & 0xFF) * ((argbTint >> 16) & 0xFF) / 255;
        int green = ((rgb >> 8) & 0xFF) * ((argbTint >> 8) & 0xFF) / 255;
        int blue = (rgb & 0xFF) * (argbTint & 0xFF) / 255;
        return red << 16 | green << 8 | blue;
    }
}
