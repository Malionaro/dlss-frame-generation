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

    public static Decision resolve(
            FrameGenerationBackend.GpuVendor vendor,
            boolean autoFallbackEnabled,
            boolean requestedFrameGeneration,
            int requestedUpscalerMode,
            int supportedCapabilities,
            ProviderLifecycle.State providerState
    ) {
        int safeMode = Math.max(0, Math.min(6, requestedUpscalerMode));
        boolean providerFailed = providerState == ProviderLifecycle.State.FAILED
                || providerState == ProviderLifecycle.State.UNAVAILABLE
                || providerState == ProviderLifecycle.State.SHUTDOWN;
        // NVIDIA exposes a separate, stable hardware-support query. The FFX
        // routes expose runtime readiness, so a zero bit there must not disable
        // the first frames which are required to create their contexts.
        boolean knownFrameGenerationUnavailable =
                vendor == FrameGenerationBackend.GpuVendor.NVIDIA
                        && (supportedCapabilities
                                & StreamlineBridge.CAP_FRAME_GENERATION) == 0;
        boolean activateFallback = autoFallbackEnabled
                && requestedFrameGeneration
                && (providerFailed || knownFrameGenerationUnavailable);
        if (!activateFallback) {
            return new Decision(
                    requestedFrameGeneration,
                    safeMode,
                    false,
                    "Preferred provider remains selected"
            );
        }

        boolean reconstructionAvailable = (supportedCapabilities
                & StreamlineBridge.CAP_UPSCALING) != 0;
        int effectiveMode = reconstructionAvailable && safeMode == 0
                ? 1
                : safeMode;
        String detail = reconstructionAvailable
                ? "Frame generation unavailable; using temporal reconstruction"
                : "Frame generation unavailable; using native Vulkan presentation";
        return new Decision(false, effectiveMode, true, detail);
    }
}
