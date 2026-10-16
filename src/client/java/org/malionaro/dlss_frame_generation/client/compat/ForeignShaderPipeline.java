package org.malionaro.dlss_frame_generation.client.compat;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Compatibility mode for Vulkan shader-pack engines (Vitrail, Vulcade).
 *
 * <p>Vitrail owns the world render target: its render scale wraps the first
 * colour-and-depth clear in {@code GameRenderer.render} and switches back at
 * the depth-only clear before the HUD. This mod used to swap
 * {@code mainRenderTarget} at those same two points (reduced DLSS world
 * target, separate UI target) and composite its own RT pass on top. Two mods
 * swapping one target left a Vitrail pass open and crashed in
 * {@code render3dHud} ("Close the existing render pass before creating a new
 * one!").</p>
 *
 * <p>While such an engine is loaded the mod never swaps the world target.
 * It cooperates instead: DLSS/FSR drives Vitrail's own render scale
 * ({@link VitrailBridge}), reads the pack's finished world before
 * {@code endWorld}, and replaces Vitrail's FSR-1 upscale with its result
 * afterwards. The HUD is redirected into the UI layer only after
 * {@code endWorld}, and RT runs on the pack's image before upscaling. Camera
 * jitter stays off because packs run their own temporal passes. Without the
 * bridge (Vulcade, or a Vitrail version whose internals changed) the world is
 * captured at its native size.</p>
 */
public final class ForeignShaderPipeline {
    private static final Logger LOGGER = LoggerFactory.getLogger("dlss_fg.compat");
    private static final List<String> MOD_IDS = List.of("vitrail", "vulcade");

    private static volatile boolean resolved;
    private static volatile String modId;

    private ForeignShaderPipeline() {
    }

    /** The loaded shader-pack engine's mod id, or {@code null}. */
    public static String modId() {
        if (!resolved) {
            resolve();
        }
        return modId;
    }

    public static boolean active() {
        return modId() != null;
    }

    private static synchronized void resolve() {
        if (resolved) {
            return;
        }
        String found = null;
        try {
            FabricLoader loader = FabricLoader.getInstance();
            for (String candidate : MOD_IDS) {
                if (loader.isModLoaded(candidate)) {
                    found = candidate;
                    break;
                }
            }
        } catch (RuntimeException | LinkageError ignored) {
            found = null;
        }
        modId = found;
        resolved = true;
        if (found != null) {
            LOGGER.info(
                    "{} owns the Vulkan world renderer: cooperating with its render scale"
                            + " instead of swapping the world target",
                    found);
        }
    }
}
