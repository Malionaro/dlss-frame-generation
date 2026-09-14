package org.malionaro.dlss_frame_generation.client.integration;

import org.malionaro.dlss_frame_generation.client.frame.FrameIdentity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RendererIntegrationTest {
    private static final FrameIdentity FRAME_A = new FrameIdentity(7L, 3L, 0);
    private static final FrameIdentity FRAME_B = new FrameIdentity(8L, 3L, 0);

    @Test
    void noOpIntegrationIsUnavailableAndCapturesNothing() {
        RendererIntegration integration = new NoOpRendererIntegration();
        assertEquals(NoOpRendererIntegration.ID, integration.id());
        assertEquals(NoOpRendererIntegration.VERSION, integration.version());
        assertFalse(integration.isAvailable());
        assertTrue(integration.captureFrame(FRAME_A).isEmpty());
        // Default lifecycle methods must be safe no-ops.
        assertDoesNotThrow(() -> integration.onSwapchainGenerationChanged(3L));
        assertDoesNotThrow(integration::close);
        assertTrue(integration.materialProvider().isEmpty());
    }

    @Test
    void materialResourcesRejectIncompleteOrForeignFrames() {
        var complete = new MaterialProvider.MaterialResources(
                FRAME_A, 11L, 12L, 13L, 320, 200);
        assertTrue(complete.isCompleteFor(FRAME_A));

        // Foreign identity.
        assertFalse(complete.isCompleteFor(FRAME_B));
        // Nulls.
        assertFalse(complete.isCompleteFor(null));
        var nullIdentity = new MaterialProvider.MaterialResources(
                null, 11L, 12L, 13L, 320, 200);
        assertFalse(nullIdentity.isCompleteFor(FRAME_A));
        // Zero handles.
        assertFalse(new MaterialProvider.MaterialResources(
                FRAME_A, 0L, 12L, 13L, 320, 200).isCompleteFor(FRAME_A));
        assertFalse(new MaterialProvider.MaterialResources(
                FRAME_A, 11L, 0L, 13L, 320, 200).isCompleteFor(FRAME_A));
        assertFalse(new MaterialProvider.MaterialResources(
                FRAME_A, 11L, 12L, 0L, 320, 200).isCompleteFor(FRAME_A));
        // Degenerate dimensions.
        assertFalse(new MaterialProvider.MaterialResources(
                FRAME_A, 11L, 12L, 13L, 0, 200).isCompleteFor(FRAME_A));
        assertFalse(new MaterialProvider.MaterialResources(
                FRAME_A, 11L, 12L, 13L, 320, 0).isCompleteFor(FRAME_A));
        // Invalid requested identity.
        assertFalse(complete.isCompleteFor(FrameIdentity.INVALID));
    }
}
