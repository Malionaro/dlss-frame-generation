package org.malionaro.dlss_frame_generation.client.render;
import org.malionaro.dlss_frame_generation.client.backend.FrameGenerationBackend;
import org.malionaro.dlss_frame_generation.client.compat.CompatibilityManager;
import org.malionaro.dlss_frame_generation.client.config.FrameGenConfig;
import org.malionaro.dlss_frame_generation.client.FrameGenMod;
import org.malionaro.dlss_frame_generation.client.StreamlineBridge;

public class ReflexPacer {
    // C8: atomic read-modify-write cycles (EMA + last-frame delta). Readers
    // (OSD metrics) and writers (present path) must never see torn values.
    private static final java.util.concurrent.atomic.AtomicLong lastFrameTimeNano =
            new java.util.concurrent.atomic.AtomicLong(System.nanoTime());
    private static final java.util.concurrent.atomic.AtomicLong smoothedFrameDurationNano =
            new java.util.concurrent.atomic.AtomicLong(16_666_666L); // 60 FPS default (16.6ms)
    private static final java.util.concurrent.atomic.AtomicInteger configuredNvidiaTargetFps =
            new java.util.concurrent.atomic.AtomicInteger(-1);

    public static void paceFrame() {
        FrameGenConfig config = FrameGenConfig.getInstance();
        if (!config.reflexPacingEnabled) {
            // Turning pacing off must also lift the driver-side limiter set
            // while it was on.
            if (configuredNvidiaTargetFps.getAndSet(-1) > 0) {
                try {
                    StreamlineBridge.nSetReflexMode(config.reflexMode, 0);
                } catch (UnsatisfiedLinkError ignored) {
                    // No native Reflex: nothing to lift.
                }
            }
            return;
        }

        long now = System.nanoTime();
        long frameDurationNano = now - lastFrameTimeNano.getAndSet(now);

        // Exponential moving average for smooth frame duration tracking
        long previous;
        long next;
        do {
            previous = smoothedFrameDurationNano.get();
            next = (long) (previous * 0.9 + frameDurationNano * 0.1);
        } while (!smoothedFrameDurationNano.compareAndSet(previous, next));

        int targetFps = Math.max(30, config.targetFps);
        CompatibilityManager compatibility = FrameGenMod.compatibilityManager();
        FrameGenerationBackend backend = compatibility != null
                ? compatibility.activeVendorBackend()
                : null;
        if (backend != null
                && backend.vendor() == FrameGenerationBackend.GpuVendor.NVIDIA) {
            // NVIDIA pacing is performed by slReflexSleep at simulation start.
            // Keep its driver-side limiter synchronized without adding a
            // second host sleep at presentation time.
            if (configuredNvidiaTargetFps.get() != targetFps) {
                try {
                    StreamlineBridge.nSetReflexMode(config.reflexMode, targetFps);
                    configuredNvidiaTargetFps.set(targetFps);
                } catch (UnsatisfiedLinkError ignored) {
                    // The normal non-NVIDIA fallback below remains available.
                }
            }
            return;
        }

        // Provider-neutral fallback for hardware without Reflex.
        // P0 Fix: Present path must not block via Thread.sleep.
        // Keep duration tracking for metrics but do NOT sleep on present thread.
        // If pacing is required, it should be driven by a scheduler outside the
        // present path (e.g. LockSupport.parkNanos in a dedicated thread).
        long targetPaceNano = 1_000_000_000L / targetFps;
        // no-op: pacing intentionally disabled on present thread to avoid jank/starvation
        // long sleepDurationNano = targetPaceNano - smoothedFrameDurationNano;
        // if (sleepDurationNano > 500_000L) {
        //     java.util.concurrent.locks.LockSupport.parkNanos(sleepDurationNano);
        // }
    }

    public static double getAverageFrameTimeMs() {
        return (double) smoothedFrameDurationNano.get() / 1_000_000.0;
    }
}
