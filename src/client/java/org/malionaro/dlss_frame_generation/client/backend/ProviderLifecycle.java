package org.malionaro.dlss_frame_generation.client.backend;
import org.malionaro.dlss_frame_generation.client.frame.FrameIdentity;

import java.util.EnumSet;
import java.util.Objects;

/**
 * Fail-closed lifecycle for the single frame-generation provider that owns the
 * active swapchain route.
 */
public final class ProviderLifecycle {
    public enum State {
        UNAVAILABLE,
        DETECTED,
        INITIALIZING,
        READY,
        ACTIVE,
        DRAINING,
        FAILED,
        SHUTDOWN
    }

    public record Snapshot(
            FrameGenerationCompatibilityLayer.ProviderId provider,
            State state,
            long swapchainGeneration,
            FrameIdentity lastAcceptedFrame,
            String detail
    ) {
    }

    private FrameGenerationCompatibilityLayer.ProviderId provider =
            FrameGenerationCompatibilityLayer.ProviderId.NONE;
    private State state = State.UNAVAILABLE;
    private long swapchainGeneration;
    private FrameIdentity lastAcceptedFrame = FrameIdentity.INVALID;
    private String detail = "No provider detected";
    // Transient failures (e.g. swapchain drain timeouts) are retryable on
    // the next swapchain generation; hard failures latch until re-detection.
    private boolean failureTransient;
    private long failureGeneration = -1L;

    public synchronized void detected(
            FrameGenerationCompatibilityLayer.ProviderId detectedProvider,
            String detectionDetail
    ) {
        Objects.requireNonNull(detectedProvider, "detectedProvider");
        if (detectedProvider
                == FrameGenerationCompatibilityLayer.ProviderId.NONE) {
            if (state == State.ACTIVE) {
                throw new IllegalStateException(
                        "Illegal provider transition from ACTIVE to UNAVAILABLE via NONE; drain required"
                );
            }
            provider = detectedProvider;
            state = State.UNAVAILABLE;
            detail = nonBlank(detectionDetail, "No provider detected");
            return;
        }
        requireState(
                State.UNAVAILABLE, State.DETECTED, State.READY,
                State.FAILED, State.SHUTDOWN
        );
        provider = detectedProvider;
        state = State.DETECTED;
        detail = nonBlank(detectionDetail, "Provider detected");
        lastAcceptedFrame = FrameIdentity.INVALID;
        failureTransient = false;
        failureGeneration = -1L;
    }

    public synchronized void initializing() {
        requireState(State.DETECTED);
        state = State.INITIALIZING;
        detail = "Provider initialization in progress";
    }

    public synchronized void ready(String readinessDetail) {
        requireState(State.DETECTED, State.INITIALIZING, State.DRAINING, State.READY);
        state = State.READY;
        detail = nonBlank(readinessDetail, "Provider initialized");
    }

    public synchronized void ready(
            long rebuiltSwapchainGeneration,
            String readinessDetail
    ) {
        if (rebuiltSwapchainGeneration <= 0L) {
            throw new IllegalArgumentException(
                    "Swapchain generation must be positive"
            );
        }
        requireState(State.DETECTED, State.INITIALIZING, State.DRAINING, State.READY);
        swapchainGeneration = rebuiltSwapchainGeneration;
        state = State.READY;
        detail = nonBlank(readinessDetail, "Provider initialized");
    }

    /**
     * Records a provider-accepted frame.
     *
     * <p>Idempotent re-entry: once ACTIVE, every subsequent accepted frame
     * re-enters this method (see
     * {@link org.malionaro.dlss_frame_generation.client.compat.CompatibilityManager#markProviderFrameAccepted}).
     * ACTIVE is therefore an allowed source state and simply refreshes
     * {@code lastAcceptedFrame}. Without it, every frame after the first
     * throws "Illegal provider transition from ACTIVE".</p>
     */
    public synchronized void activated(
            FrameIdentity acceptedFrame,
            boolean safeFrameBoundary,
            String activeDetail
    ) {
        if (!safeFrameBoundary) {
            throw new IllegalStateException(
                    "Provider activation requires a safe frame boundary"
            );
        }
        // An already-ACTIVE provider re-enters here every accepted frame, and
        // it may also outlive a swapchain rebuild that skipped the drain path.
        boolean reentry = state == State.ACTIVE;
        requireState(State.READY, State.DRAINING, State.ACTIVE);
        if (acceptedFrame == null || !acceptedFrame.isValid()) {
            throw new IllegalArgumentException(
                    "Provider activation requires a valid accepted frame"
            );
        }
        if (acceptedFrame.swapchainGeneration() == 0L) {
            throw new IllegalArgumentException(
                    "Accepted frame swapchain generation must be non-zero"
            );
        }
        if (swapchainGeneration != 0L
                && acceptedFrame.swapchainGeneration()
                        != swapchainGeneration
                // Re-entry re-binds to a newer swapchain; a strictly older
                // generation is still a genuine stale-frame rejection.
                && !(reentry
                        && acceptedFrame.swapchainGeneration()
                                > swapchainGeneration)) {
            throw new IllegalStateException(
                    "Accepted frame belongs to a stale swapchain generation"
            );
        }
        if (swapchainGeneration == 0L && acceptedFrame.swapchainGeneration() == 0L) {
            throw new IllegalStateException(
                    "Swapchain generation must be non-zero before activation"
            );
        }
        swapchainGeneration = acceptedFrame.swapchainGeneration();
        lastAcceptedFrame = acceptedFrame;
        state = State.ACTIVE;
        detail = nonBlank(activeDetail, "Provider accepted frame inputs");
    }

    public synchronized void draining(
            long generation,
            boolean safeFrameBoundary
    ) {
        if (!safeFrameBoundary) {
            throw new IllegalStateException(
                    "Provider draining requires a safe frame boundary"
            );
        }
        if (generation == 0L) {
            throw new IllegalArgumentException(
                    "Swapchain generation must be non-zero for draining"
            );
        }
        if (generation < 0L) {
            throw new IllegalArgumentException(
                    "Swapchain generation must be positive"
            );
        }
        requireState(State.READY, State.ACTIVE, State.DRAINING);
        state = State.DRAINING;
        swapchainGeneration = generation;
        detail = "Waiting for provider work before swapchain rebuild";
    }

    public synchronized void failed(String failureDetail) {
        if (state == State.SHUTDOWN) {
            return;
        }
        state = State.FAILED;
        failureTransient = false;
        failureGeneration = -1L;
        detail = nonBlank(failureDetail, "Provider failed");
    }

    /**
     * Transient failure: latched as FAILED like {@link #failed}, but
     * marked retryable once the swapchain generation advances (e.g. a drain
     * timeout during rebuild). See
     * {@link org.malionaro.dlss_frame_generation.client.compat.CompatibilityManager#completeProviderSwapchainRebuild(long)}.
     */
    public synchronized void failedTransient(String failureDetail) {
        if (state == State.SHUTDOWN) {
            return;
        }
        state = State.FAILED;
        failureTransient = true;
        failureGeneration = swapchainGeneration;
        detail = nonBlank(failureDetail, "Provider failed (transient)");
    }

    /** True only for a FAILED state that may be retried on a new generation. */
    public synchronized boolean isFailureTransient() {
        return state == State.FAILED && failureTransient;
    }

    /** Swapchain generation at which the transient failure was recorded. */
    public synchronized long failureGeneration() {
        return failureGeneration;
    }

    public synchronized void shutdown(boolean safeFrameBoundary) {
        if (!safeFrameBoundary) {
            throw new IllegalStateException(
                    "Provider shutdown requires a safe frame boundary"
            );
        }
        state = State.SHUTDOWN;
        detail = "Provider shut down";
        lastAcceptedFrame = FrameIdentity.INVALID;
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(
                provider,
                state,
                swapchainGeneration,
                lastAcceptedFrame,
                detail
        );
    }

    private void requireState(State... allowed) {
        if (!EnumSet.copyOf(java.util.List.of(allowed)).contains(state)) {
            throw new IllegalStateException(
                    "Illegal provider transition from " + state
            );
        }
    }

    private static String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
