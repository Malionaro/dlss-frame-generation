package org.malionaro.dlss_frame_generation.client.render;

import com.mojang.blaze3d.pipeline.RenderTarget;

/**
 * Routes the HUD into the separate DLSS-G UI layer without touching
 * {@code GameRenderer.mainRenderTarget}.
 *
 * <p>Between the pre-HUD depth clear and {@code GuiRenderer.endFrame} the only
 * reader of the frame target is {@code GuiRenderer.draw}, which asks
 * {@code GameRenderer.mainRenderTarget()}. Redirecting that one call keeps the
 * field stable for everything else, including shader-pack engines such as
 * Vitrail that restore their world textures through it.</p>
 *
 * <p>Render-thread only.</p>
 */
public final class UiLayerRedirect {
    private static RenderTarget target;

    private UiLayerRedirect() {
    }

    public static void begin(RenderTarget uiTarget) {
        target = uiTarget;
    }

    public static void end() {
        target = null;
    }

    /** The UI layer while the HUD is being drawn this frame, else {@code null}. */
    public static RenderTarget target() {
        return target;
    }
}
