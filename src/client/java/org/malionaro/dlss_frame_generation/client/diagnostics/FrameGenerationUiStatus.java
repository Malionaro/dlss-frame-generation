package org.malionaro.dlss_frame_generation.client.diagnostics;

import java.util.Locale;

/**
 * Resolves why frame generation is or is not running right now.
 *
 * <p>The settings screen is opened from the main menu, from the pause menu and
 * while playing. "Frame Generation active: No" means something completely
 * different in each of those, and a bare No cannot be told apart from a
 * genuinely broken pipeline. This resolver names the actual reason instead.</p>
 */
public final class FrameGenerationUiStatus {

    /** Why the provider is currently active or idle. */
    public enum Kind {
        /** Native provider reports generated frames. */
        ACTIVE("dlss_fg.backend_status.active"),
        /** No world is loaded, so the renderer submits no frames at all. */
        NO_WORLD("dlss_fg.backend_status.no_world"),
        /** A screen is open and singleplayer is paused; FG is off by design. */
        PAUSED("dlss_fg.backend_status.paused"),
        /** User has frame generation switched off. */
        DISABLED_IN_SETTINGS("dlss_fg.backend_status.disabled"),
        /** Enabled and running, but the provider has no valid inputs yet. */
        WAITING_FOR_INPUTS("dlss_fg.backend_status.inputs_pending"),
        /** Not usable on this machine. */
        UNSUPPORTED("dlss_fg.backend_status.unavailable");

        private final String translationKey;

        Kind(String translationKey) {
            this.translationKey = translationKey;
        }

        public String translationKey() {
            return translationKey;
        }
    }

    private FrameGenerationUiStatus() {
    }

    /**
     * Decides the status kind. Order matters: an unusable GPU is reported
     * before the transient menu/pause states so a real problem is not hidden
     * behind "no world loaded".
     */
    public static Kind resolve(
            boolean supported,
            boolean levelLoaded,
            boolean paused,
            boolean enabledInSettings,
            String nativeStatus,
            boolean nativeActive
    ) {
        if (!supported) {
            return Kind.UNSUPPORTED;
        }
        if (nativeActive) {
            return Kind.ACTIVE;
        }
        if (!levelLoaded) {
            return Kind.NO_WORLD;
        }
        if (!enabledInSettings) {
            return Kind.DISABLED_IN_SETTINGS;
        }
        if (paused) {
            return Kind.PAUSED;
        }

        BackendStatusFormatter.StatusKind fromNative =
                BackendStatusFormatter.classify(nativeStatus, true, false);
        return switch (fromNative) {
            case INPUTS_PENDING, AVAILABLE, DISABLED -> Kind.WAITING_FOR_INPUTS;
            case NO_SUPPORTED_ADAPTER, D3D12_REQUIRED,
                 NATIVE_BRIDGE_UNAVAILABLE, UNAVAILABLE -> Kind.UNSUPPORTED;
            case ACTIVE -> Kind.ACTIVE;
        };
    }

    /**
     * Whether a VRAM reading is meaningful for the given status.
     *
     * <p>DLSS-G reports {@code 0} whenever it is not running, so a raw zero is
     * indistinguishable from "no measurement". Callers keep the last non-zero
     * sample instead of showing a misleading {@code 0.00 MB}.</p>
     */
    public static boolean vramReadingIsMeaningful(Kind kind, long bytes) {
        return bytes > 0L && kind == Kind.ACTIVE;
    }

    /** Lower-cased status text for logging and tests. */
    public static String describe(Kind kind) {
        return kind.name().toLowerCase(Locale.ROOT);
    }
}