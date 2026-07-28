package org.malionaro.dlss_frame_generation.client;

public class ReflexPacer {
    private static long lastFrameTimeNano = System.nanoTime();
    private static long smoothedFrameDurationNano = 16_666_666L; // 60 FPS default (16.6ms)
    private static int configuredNvidiaTargetFps = -1;

    public static void paceFrame() {
        FrameGenConfig config = FrameGenConfig.getInstance();
        if (!config.reflexPacingEnabled) return;

        long now = System.nanoTime();
        long frameDurationNano = now - lastFrameTimeNano;
        lastFrameTimeNano = now;

        // Exponential moving average for smooth frame duration tracking
        smoothedFrameDurationNano = (long) (smoothedFrameDurationNano * 0.9 + frameDurationNano * 0.1);

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
            if (configuredNvidiaTargetFps != targetFps) {
                try {
                    StreamlineBridge.nSetReflexMode(config.reflexMode, targetFps);
                    configuredNvidiaTargetFps = targetFps;
                } catch (UnsatisfiedLinkError ignored) {
                    // The normal non-NVIDIA fallback below remains available.
                }
            }
            return;
        }

        // Provider-neutral fallback for hardware without Reflex.
        long targetPaceNano = 1_000_000_000L / targetFps;
        if (smoothedFrameDurationNano < targetPaceNano) {
            long sleepDurationNano = targetPaceNano - smoothedFrameDurationNano;
            if (sleepDurationNano > 500_000L) { // Only sleep if > 0.5ms to avoid thread starvation
                try {
                    Thread.sleep(sleepDurationNano / 1_000_000L, (int) (sleepDurationNano % 1_000_000L));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    public static double getAverageFrameTimeMs() {
        return (double) smoothedFrameDurationNano / 1_000_000.0;
    }
}
