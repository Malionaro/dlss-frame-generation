package org.malionaro.dlss_frame_generation.client.render;

import org.junit.jupiter.api.Test;
import org.malionaro.dlss_frame_generation.client.backend.FrameGenerationBackend;
import org.malionaro.dlss_frame_generation.client.backend.ProviderLifecycle;
import org.malionaro.dlss_frame_generation.client.render.RuntimeFallbackPolicy;
import org.malionaro.dlss_frame_generation.client.StreamlineBridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class RuntimeFallbackPolicyTest {
    @Test
    public void nvidiaFallsBackToNativeAaWhenFgIsUnsupported() {
        RuntimeFallbackPolicy.Decision decision =
                RuntimeFallbackPolicy.resolve(
                        FrameGenerationBackend.GpuVendor.NVIDIA,
                        true,
                        true,
                        0,
                        StreamlineBridge.CAP_UPSCALING,
                        ProviderLifecycle.State.READY
                );
        assertTrue(decision.fallbackActive());
        assertFalse(decision.frameGenerationEnabled());
        assertEquals(1, decision.upscalerMode());
    }

    @Test
    public void fidelityFxIsNotDisabledBeforeFirstAcceptedInputs() {
        RuntimeFallbackPolicy.Decision decision =
                RuntimeFallbackPolicy.resolve(
                        FrameGenerationBackend.GpuVendor.AMD,
                        true,
                        true,
                        2,
                        StreamlineBridge.CAP_UPSCALING,
                        ProviderLifecycle.State.READY
                );
        assertFalse(decision.fallbackActive());
        assertTrue(decision.frameGenerationEnabled());
        assertEquals(2, decision.upscalerMode());
    }

    @Test
    public void disabledFallbackPreservesExplicitRequest() {
        RuntimeFallbackPolicy.Decision decision =
                RuntimeFallbackPolicy.resolve(
                        FrameGenerationBackend.GpuVendor.NVIDIA,
                        false,
                        true,
                        0,
                        StreamlineBridge.CAP_UPSCALING,
                        ProviderLifecycle.State.READY
                );
        assertFalse(decision.fallbackActive());
        assertTrue(decision.frameGenerationEnabled());
        assertEquals(0, decision.upscalerMode());
    }

    @Test
    public void drainingIsExcludedFromFallback() {
        // DRAINING must not trigger fallback when FG capability is present; only FAILED/UNAVAILABLE are failures
        RuntimeFallbackPolicy.Decision decision =
                RuntimeFallbackPolicy.resolve(
                        FrameGenerationBackend.GpuVendor.NVIDIA,
                        true,
                        true,
                        0,
                        StreamlineBridge.CAP_FRAME_GENERATION | StreamlineBridge.CAP_UPSCALING,
                        ProviderLifecycle.State.DRAINING
                );
        assertFalse(decision.fallbackActive(),
                "DRAINING is an intentional transition, not a failure; fallback must stay inactive");
        assertTrue(decision.frameGenerationEnabled());
        assertEquals(0, decision.upscalerMode());
    }

    @Test
    public void safeModeZeroRespectedWhenReconstructionUnavailable() {
        RuntimeFallbackPolicy.Decision decision =
                RuntimeFallbackPolicy.resolve(
                        FrameGenerationBackend.GpuVendor.NVIDIA,
                        true,
                        true,
                        0,
                        0, // neither FG nor UPSCALING available
                        ProviderLifecycle.State.FAILED
                );
        assertTrue(decision.fallbackActive());
        assertFalse(decision.frameGenerationEnabled());
        assertEquals(0, decision.upscalerMode(),
                "safeMode==0 must stay 0 when reconstruction is unavailable (no promotion to 1)");
    }
}
