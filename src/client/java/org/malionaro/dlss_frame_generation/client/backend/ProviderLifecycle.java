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

    public synchronized void detected(
            FrameGenerationCompatibilityLayer.ProviderId detectedProvider,
            String detectionDetail
    ) {
        Objects.requireNonNull(detectedProvider, "detectedProvider");
        if (detectedProvider
                == FrameGenerationCompatibilityLayer.ProviderId.NONE) {
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
    }

    public synchronized void initializing() {
        requireState(State.DETECTED);
        state = State.INITIALIZING;
        detail = "Provider initialization in progress";
    }

    public synchronized void ready(String readinessDetail) {
        requireState(State.INITIALIZING, State.DRAINING, State.READY);
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
        requireState(State.INITIALIZING, State.DRAINING, State.READY);
        swapchainGeneration = rebuiltSwapchainGeneration;
        state = State.READY;
        detail = nonBlank(readinessDetail, "Provider initialized");
    }

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
        requireState(State.READY, State.ACTIVE);
        if (acceptedFrame == null || !acceptedFrame.isValid()) {
            throw new IllegalArgumentException(
                    "Provider activation requires a valid accepted frame"
            );
        }
        if (swapchainGeneration != 0L
                && acceptedFrame.swapchainGeneration()
                        != swapchainGeneration) {
            throw new IllegalStateException(
                    "Accepted frame belongs to a stale swapchain generation"
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
        detail = nonBlank(failureDetail, "Provider failed");
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
