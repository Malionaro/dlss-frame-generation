package org.malionaro.dlss_frame_generation.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProviderLifecycleTest {
    @Test
    void providerReachesActiveOnlyAfterAcceptedFrame() {
        ProviderLifecycle lifecycle = new ProviderLifecycle();
        lifecycle.detected(
                FrameGenerationCompatibilityLayer.ProviderId
                        .NVIDIA_STREAMLINE_VULKAN,
                "detected"
        );
        lifecycle.initializing();
        lifecycle.ready("ready");
        FrameIdentity accepted = new FrameIdentity(4L, 2L, 1);
        lifecycle.activated(accepted, true, "accepted");

        ProviderLifecycle.Snapshot snapshot = lifecycle.snapshot();
        assertEquals(ProviderLifecycle.State.ACTIVE, snapshot.state());
        assertEquals(accepted, snapshot.lastAcceptedFrame());
    }

    @Test
    void unsafeActivationAndStaleGenerationAreRejected() {
        ProviderLifecycle lifecycle = readyLifecycle();
        assertThrows(
                IllegalStateException.class,
                () -> lifecycle.activated(
                        new FrameIdentity(1L, 1L, 0), false, "unsafe"
                )
        );

        lifecycle.activated(
                new FrameIdentity(1L, 1L, 0), true, "first"
        );
        lifecycle.draining(1L, true);
        lifecycle.ready(2L, "rebuilt");
        assertThrows(
                IllegalStateException.class,
                () -> lifecycle.activated(
                        new FrameIdentity(2L, 1L, 0), true, "wrong generation"
                )
        );
    }

    @Test
    void secondProviderCannotReplaceActiveProviderDirectly() {
        ProviderLifecycle lifecycle = readyLifecycle();
        lifecycle.activated(
                new FrameIdentity(1L, 1L, 0), true, "active"
        );

        assertThrows(
                IllegalStateException.class,
                () -> lifecycle.detected(
                        FrameGenerationCompatibilityLayer.ProviderId
                                .AMD_FSR31_VULKAN,
                        "replacement"
                )
        );
    }

    private static ProviderLifecycle readyLifecycle() {
        ProviderLifecycle lifecycle = new ProviderLifecycle();
        lifecycle.detected(
                FrameGenerationCompatibilityLayer.ProviderId
                        .NVIDIA_STREAMLINE_VULKAN,
                "detected"
        );
        lifecycle.initializing();
        lifecycle.ready("ready");
        return lifecycle;
    }
}
