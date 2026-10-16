package org.malionaro.dlss_frame_generation.client.compat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Reflective bridge to Vitrail's {@code dev.vitrail.render.RenderScale}.
 *
 * <p>Vitrail scales the world by swapping the textures inside the game's own
 * main {@code RenderTarget} ({@code beginWorld}) and restores and FSR-1
 * upscales them at the depth-only clear before the HUD ({@code endWorld}).
 * Instead of swapping {@code mainRenderTarget} ourselves, the DLSS/FSR path
 * drives that scale: it asks Vitrail for the DLSS render ratio through the
 * public {@code wanted(int)} and reads the scaled world before
 * {@code endWorld}.</p>
 *
 * <p>Only reflection, no compile dependency. Any lookup failure disables the
 * bridge, which leaves the read-only compatibility mode in place.</p>
 */
public final class VitrailBridge {
    private static final Logger LOGGER = LoggerFactory.getLogger("dlss_fg.vitrail");
    private static final String RENDER_SCALE = "dev.vitrail.render.RenderScale";

    private static volatile boolean resolved;
    private static volatile boolean available;
    private static Method upscaling;
    private static Method wanted;
    private static Field percent;
    private static int whole = 100;

    /** Vitrail's own percentage before this mod first overrode it, or -1. */
    private static int userPercent = -1;
    private static int lastRequested = -1;

    private VitrailBridge() {
    }

    public static boolean available() {
        if (!resolved) {
            resolve();
        }
        return available;
    }

    /** True while Vitrail holds the scaled world textures in the main target. */
    public static boolean worldScaled() {
        if (!available()) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(upscaling.invoke(null));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            disable("RenderScale.upscaling() failed", error);
            return false;
        }
    }

    /**
     * Requests the world scale for the next frame, as a percentage of the
     * window. {@code -1} hands the scale back to Vitrail's own setting.
     */
    public static synchronized void requestScale(int requestedPercent) {
        if (!available() || requestedPercent == lastRequested) {
            return;
        }
        try {
            if (requestedPercent < 0) {
                if (userPercent >= 0) {
                    wanted.invoke(null, userPercent);
                    LOGGER.info("Returned the world render scale to Vitrail's setting ({}%)",
                            userPercent);
                }
                userPercent = -1;
            } else {
                if (userPercent < 0) {
                    userPercent = percent != null ? percent.getInt(null) : whole;
                }
                int clamped = Math.max(1, Math.min(whole, requestedPercent));
                wanted.invoke(null, clamped);
                LOGGER.info("Driving Vitrail's world render scale from the upscaler: {}%",
                        clamped);
            }
            lastRequested = requestedPercent;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            disable("RenderScale.wanted(int) failed", error);
        }
    }

    public static int wholePercent() {
        return available() ? whole : 100;
    }

    private static synchronized void resolve() {
        if (resolved) {
            return;
        }
        try {
            Class<?> renderScale = Class.forName(
                    RENDER_SCALE, false, VitrailBridge.class.getClassLoader());
            Method upscalingMethod = renderScale.getDeclaredMethod("upscaling");
            upscalingMethod.setAccessible(true);
            Method wantedMethod = renderScale.getDeclaredMethod("wanted", int.class);
            wantedMethod.setAccessible(true);
            Field percentField = null;
            try {
                percentField = renderScale.getDeclaredField("percent");
                percentField.setAccessible(true);
            } catch (NoSuchFieldException ignored) {
                // Restoring the user's slider then falls back to 100 %.
            }
            try {
                Field wholeField = renderScale.getDeclaredField("WHOLE");
                wholeField.setAccessible(true);
                whole = wholeField.getInt(null);
            } catch (NoSuchFieldException ignored) {
                whole = 100;
            }
            upscaling = upscalingMethod;
            wanted = wantedMethod;
            percent = percentField;
            available = true;
            LOGGER.info("Vitrail render-scale bridge ready (whole={})", whole);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            available = false;
            LOGGER.warn("Vitrail render-scale bridge unavailable, using read-only mode: {}",
                    error.toString());
        } finally {
            resolved = true;
        }
    }

    private static void disable(String what, Throwable error) {
        available = false;
        LOGGER.warn("{}; Vitrail bridge disabled, using read-only mode: {}",
                what, error.toString());
    }
}
