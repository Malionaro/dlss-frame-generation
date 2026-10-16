package org.malionaro.dlss_frame_generation.client.compat;

import net.fabricmc.loader.api.FabricLoader;
import org.malionaro.dlss_frame_generation.client.render.VulkanRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.List;

/**
 * Compatibility mode for shader-pack engines that render on Vulkan: Vitrail,
 * Vulcade and Iris builds that run shader packs on the Vulkan backend.
 *
 * <p>These engines own the world render target. Vitrail's render scale wraps
 * the first colour-and-depth clear in {@code GameRenderer.render} and switches
 * back at the depth-only clear before the HUD. This mod used to swap
 * {@code mainRenderTarget} at those same two points (reduced DLSS world
 * target, separate UI target) and composite its own RT pass on top. Two mods
 * swapping one target left a Vitrail pass open and crashed in
 * {@code render3dHud} ("Close the existing render pass before creating a new
 * one!").</p>
 *
 * <p>While such an engine is active the mod never swaps the world target and
 * never runs its RT pass: the world, its lighting and its scale belong to the
 * shader pack. It reads the pack's finished pre-HUD image for DLSS and frame
 * generation, and routes the HUD into the DLSS-G UI layer without touching the
 * target field. With Vitrail, DLSS/FSR additionally drives Vitrail's own render
 * scale ({@link VitrailBridge}) and replaces its FSR-1 upscale. Camera jitter
 * stays off because packs run their own temporal passes.</p>
 *
 * <p>Iris has no render-scale hook. Handing it a reduced world target does
 * not work: it sizes its colour buffers and its depth attachment from
 * different sources and crashes with "invalid framebuffer attachments". So
 * with Iris the world is captured at window size (DLAA-like, no upscaling
 * gain) and frame generation supplies the extra frames.</p>
 *
 * <p>Vitrail and Vulcade are always shader engines when loaded. Iris is only
 * one while it actually runs a pack on Vulkan, which can change at runtime, so
 * that state is queried live through the public {@code IrisApi}.</p>
 */
public final class ForeignShaderPipeline {
    private static final Logger LOGGER = LoggerFactory.getLogger("dlss_fg.compat");
    private static final List<String> VULKAN_ENGINE_MOD_IDS = List.of("vitrail", "vulcade");
    private static final String IRIS = "iris";

    private static volatile boolean resolved;
    private static volatile String engineModId;
    private static volatile boolean irisLoaded;
    private static Method irisGetInstance;
    private static Method irisIsShaderPackInUse;
    private static volatile boolean irisApiBroken;
    private static boolean lastIrisActive;

    private ForeignShaderPipeline() {
    }

    /** The active shader-pack engine's mod id, or {@code null}. */
    public static String modId() {
        if (!resolved) {
            resolve();
        }
        if (engineModId != null) {
            return engineModId;
        }
        return irisVulkanShaderPackActive() ? IRIS : null;
    }

    public static boolean active() {
        return modId() != null;
    }

    /**
     * True while an engine owns the world render target, so this mod must not
     * swap {@code mainRenderTarget}. Unlike {@link #active()} this errs on the
     * side of "owned" whenever Iris cannot confirm that no pack runs.
     */
    public static boolean ownsWorldTarget() {
        if (!resolved) {
            resolve();
        }
        if (engineModId != null) {
            return true;
        }
        if (!irisLoaded) {
            return false;
        }
        // Fail safe: handing Iris a reduced target crashes it ("invalid
        // framebuffer attachments"). Unless the API positively reports that
        // no pack runs, keep the target untouched, also on the first frames
        // before the Vulkan device is registered and after an API failure.
        if (irisApiBroken) {
            return true;
        }
        try {
            Object api = irisGetInstance.invoke(null);
            return !Boolean.FALSE.equals(irisIsShaderPackInUse.invoke(api));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            return true;
        }
    }

    /** True while Iris runs a shader pack on Minecraft's Vulkan device. */
    private static boolean irisVulkanShaderPackActive() {
        if (!irisLoaded || irisApiBroken || VulkanRegistry.getActiveDevice() == null) {
            return false;
        }
        boolean active;
        try {
            Object api = irisGetInstance.invoke(null);
            active = Boolean.TRUE.equals(irisIsShaderPackInUse.invoke(api));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            irisApiBroken = true;
            LOGGER.warn("IrisApi query failed; treating Iris as not running a Vulkan pack: {}",
                    error.toString());
            return false;
        }
        if (active != lastIrisActive) {
            lastIrisActive = active;
            LOGGER.info(active
                    ? "Iris runs a shader pack on Vulkan: cooperating (DLSS and frame generation"
                            + " on the pack's image, no world-target swap, hardware RT off)"
                    : "Iris shader pack stopped; normal Vulkan integration resumes");
        }
        return active;
    }

    private static synchronized void resolve() {
        if (resolved) {
            return;
        }
        String found = null;
        boolean iris = false;
        try {
            FabricLoader loader = FabricLoader.getInstance();
            for (String candidate : VULKAN_ENGINE_MOD_IDS) {
                if (loader.isModLoaded(candidate)) {
                    found = candidate;
                    break;
                }
            }
            iris = loader.isModLoaded(IRIS);
        } catch (RuntimeException | LinkageError ignored) {
            found = null;
            iris = false;
        }
        if (iris) {
            try {
                Class<?> api = Class.forName(
                        "net.irisshaders.iris.api.v0.IrisApi",
                        false,
                        ForeignShaderPipeline.class.getClassLoader());
                irisGetInstance = api.getMethod("getInstance");
                irisIsShaderPackInUse = api.getMethod("isShaderPackInUse");
            } catch (ReflectiveOperationException | LinkageError error) {
                iris = false;
                LOGGER.warn("Iris is loaded but IrisApi v0 is unavailable: {}", error.toString());
            }
        }
        engineModId = found;
        irisLoaded = iris;
        resolved = true;
        if (found != null) {
            LOGGER.info(
                    "{} owns the Vulkan world renderer: cooperating with it instead of"
                            + " swapping the world target; hardware RT stays off",
                    found);
        }
    }
}
