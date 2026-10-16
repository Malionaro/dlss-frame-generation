package org.malionaro.dlss_frame_generation.client.diagnostics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrameGenerationUiStatusTest {

    private static final String DISABLED_NATIVE =
            "DLSS-G inputs valid; Frame Generation is disabled in settings";

    @Test
    void reportsActiveOnlyWhenTheProviderReallyRuns() {
        assertEquals(
                FrameGenerationUiStatus.Kind.ACTIVE,
                FrameGenerationUiStatus.resolve(
                        true, true, false, true, "DLSS-G active", true
                )
        );
    }

    @Test
    void distinguishesMenuFromPauseFromDisabled() {
        // The whole point: these three all used to render as "No" and were
        // indistinguishable from a broken pipeline.
        assertEquals(
                FrameGenerationUiStatus.Kind.NO_WORLD,
                FrameGenerationUiStatus.resolve(
                        true, false, false, true, "", false
                )
        );
        assertEquals(
                FrameGenerationUiStatus.Kind.PAUSED,
                FrameGenerationUiStatus.resolve(
                        true, true, true, true, "", false
                )
        );
        assertEquals(
                FrameGenerationUiStatus.Kind.DISABLED_IN_SETTINGS,
                FrameGenerationUiStatus.resolve(
                        true, true, false, false, "", false
                )
        );
    }

    @Test
    void realFaultsAreNotHiddenBehindMenuState() {
        // Unsupported must win over "no world", otherwise the user chases a
        // problem that is actually a missing adapter.
        assertEquals(
                FrameGenerationUiStatus.Kind.UNSUPPORTED,
                FrameGenerationUiStatus.resolve(
                        false, false, false, true, "", false
                )
        );
        assertEquals(
                FrameGenerationUiStatus.Kind.UNSUPPORTED,
                FrameGenerationUiStatus.resolve(
                        true,
                        true,
                        false,
                        true,
                        "DLSS-G=Result::eErrorNoSupportedAdapterFound",
                        false
                )
        );
    }

    @Test
    void enabledAndRunningButNoInputsIsPending() {
        assertEquals(
                FrameGenerationUiStatus.Kind.WAITING_FOR_INPUTS,
                FrameGenerationUiStatus.resolve(
                        true,
                        true,
                        false,
                        true,
                        DISABLED_NATIVE,
                        false
                )
        );
    }

    @Test
    void activeBeatsEveryOtherReason() {
        // Native truth outranks the transient menu states.
        assertEquals(
                FrameGenerationUiStatus.Kind.ACTIVE,
                FrameGenerationUiStatus.resolve(
                        true, false, true, false, "anything", true
                )
        );
    }

    @Test
    void zeroVramIsOnlyMeaningfulWhileActive() {
        assertTrue(FrameGenerationUiStatus.vramReadingIsMeaningful(
                FrameGenerationUiStatus.Kind.ACTIVE, 512L
        ));
        // DLSS-G reports 0 the moment it stops; that zero is not a measurement.
        assertFalse(FrameGenerationUiStatus.vramReadingIsMeaningful(
                FrameGenerationUiStatus.Kind.PAUSED, 0L
        ));
        assertFalse(FrameGenerationUiStatus.vramReadingIsMeaningful(
                FrameGenerationUiStatus.Kind.ACTIVE, 0L
        ));
        assertFalse(FrameGenerationUiStatus.vramReadingIsMeaningful(
                FrameGenerationUiStatus.Kind.NO_WORLD, 4096L
        ));
    }
}