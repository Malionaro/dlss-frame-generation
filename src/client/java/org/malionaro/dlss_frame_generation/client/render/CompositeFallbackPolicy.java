package org.malionaro.dlss_frame_generation.client.render;

/**
 * Decides whether the H4 composite fallback blit must run in Java because the
 * frame-generation capture path bailed for this frame.
 */
public final class CompositeFallbackPolicy {
    public enum Decision { NOT_NEEDED, FALLBACK_BLIT_REQUIRED }
    public static Decision evaluate(boolean frameResourcesCaptured, boolean worldTargetDiffersFromDisplay) {
        if (worldTargetDiffersFromDisplay && !frameResourcesCaptured) return Decision.FALLBACK_BLIT_REQUIRED;
        return Decision.NOT_NEEDED;
    }
    private CompositeFallbackPolicy() {}
}
