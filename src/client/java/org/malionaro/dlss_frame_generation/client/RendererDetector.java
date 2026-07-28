package org.malionaro.dlss_frame_generation.client;

import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.loader.api.FabricLoader;

import java.util.Locale;

public final class RendererDetector {
    private RendererDetector() {
    }

    public static RendererDetection detect() {
        boolean sodiumPresent = FabricLoader.getInstance().isModLoaded("sodium");
        boolean irisPresent = FabricLoader.getInstance().isModLoaded("iris");
        String description = readBackendDescription();
        String normalizedDescription = description.toLowerCase(Locale.ROOT);

        if (normalizedDescription.contains("vulkan")) {
            return new RendererDetection(sodiumPresent ? RendererBackend.SODIUM_VULKAN : RendererBackend.VANILLA_VULKAN,
                    description, sodiumPresent, irisPresent);
        }
        if (normalizedDescription.contains("opengl") || normalizedDescription.contains("open gl")) {
            return new RendererDetection(irisPresent ? RendererBackend.IRIS_OPENGL : RendererBackend.OPENGL,
                    description, sodiumPresent, irisPresent);
        }
        return new RendererDetection(RendererBackend.UNKNOWN, description, sodiumPresent, irisPresent);
    }

    private static String readBackendDescription() {
        try {
            String description = RenderSystem.getBackendDescription();
            return description == null || description.isBlank() ? "unavailable" : description;
        } catch (RuntimeException exception) {
            return "unavailable: " + exception.getClass().getSimpleName();
        }
    }
}
