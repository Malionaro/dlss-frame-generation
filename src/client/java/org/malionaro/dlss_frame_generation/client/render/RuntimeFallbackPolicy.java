package org.malionaro.dlss_frame_generation.client.render;
import org.malionaro.dlss_frame_generation.client.backend.FrameGenerationBackend;
import org.malionaro.dlss_frame_generation.client.backend.ProviderLifecycle;
import org.malionaro.dlss_frame_generation.client.StreamlineBridge;

/**
 * Computes a session-only degradation path without rewriting the user's
 * persistent vendor preferences.
 */
public final class RuntimeFallbackPolicy {
    public record Decision(
            boolean frameGenerationEnabled,
            int upscalerMode,
            boolean fallbackActive,
            String detail
    ) {
    }

    private RuntimeFallbackPolicy() {
    }

    // C10: last computed decision per process (one active provider at a time).
    // Lets status surfaces report the effective mode instead of only logging it.
    private static volatile Decision lastDecision;
    private static volatile FrameGenerationBackend.GpuVendor lastVendor =
            FrameGenerationBackend.GpuVendor.UNKNOWN;

    /** Most recent fallback decision, or null if resolve() never ran. */
    public static Decision lastDecision() {
        return lastDecision;
    }

    /** Human-readable fallback state for status surfaces ("" when inactive). */
    public static String lastDecisionDescription() {
        Decision decision = lastDecision;
        if (decision == null || !decision.fallbackActive()) {
            return "";
        }
        return "Fallback active (" + lastVendor + "): " + decision.detail()
                + " [upscaler mode " + decision.upscalerMode() + "]";
    }

    public static Decision resolve(
            FrameGenerationBackend.GpuVendor vendor,
            boolean autoFallbackEnabled,
            boolean requestedFrameGeneration,
            int requestedUpscalerMode,
            int supportedCapabilities,
            ProviderLifecycle.State providerState
    ) {
        int safeMode = Math.max(0, Math.min(6, requestedUpscalerMode));
        // DRAINING and SHUTDOWN are intentional transitions, not failures; only
        // FAILED/UNAVAILABLE should trigger fallback (vendor-agnostic).
        boolean providerFailed = providerState == ProviderLifecycle.State.FAILED
                || providerState == ProviderLifecycle.State.UNAVAILABLE;
        // Vendor-agnostic fallback: originally NVIDIA-only, but keep NVIDIA gate
        // for FFX runtime readiness (first frames need context creation). The
        // providerFailed branch is already vendor-agnostic and covers AMD/Intel.
        boolean knownFrameGenerationUnavailable =
                vendor == FrameGenerationBackend.GpuVendor.NVIDIA
                        && (supportedCapabilities
                                & StreamlineBridge.CAP_FRAME_GENERATION) == 0;
        boolean activateFallback = autoFallbackEnabled
                && requestedFrameGeneration
                && (providerFailed || knownFrameGenerationUnavailable);
        if (!activateFallback) {
            Decision noFallback = new Decision(
                    requestedFrameGeneration,
                    safeMode,
                    false,
                    "Preferred provider remains selected"
            );
            lastVendor = vendor;
            lastDecision = noFallback;
            return noFallback;
        }

        boolean reconstructionAvailable = (supportedCapabilities
                & StreamlineBridge.CAP_UPSCALING) != 0;
        // Respect safeMode==0: keep 0 as native presentation when reconstruction unavailable,
        // otherwise promote 0->1 for temporal reconstruction (preserves original intent).
        // C10: the promotion is named in the detail so it never happens silently.
        int effectiveMode = reconstructionAvailable && safeMode == 0
                ? 1
                : safeMode;
        String detail = reconstructionAvailable
                ? (safeMode == 0
                        ? "Frame generation unavailable; upscaler was off, promoted to temporal reconstruction (mode 1)"
                        : "Frame generation unavailable; using temporal reconstruction")
                : "Frame generation unavailable; using native Vulkan presentation";
        Decision fallback = new Decision(false, effectiveMode, true, detail);
        lastVendor = vendor;
        lastDecision = fallback;
        return fallback;
    }
}
