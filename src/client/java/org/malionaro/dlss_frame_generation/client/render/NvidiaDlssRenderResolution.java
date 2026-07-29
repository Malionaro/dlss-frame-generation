package org.malionaro.dlss_frame_generation.client.render;
import org.malionaro.dlss_frame_generation.client.StreamlineBridge;

/**
 * Resolves the renderer-owned world resolution for NVIDIA DLSS.
 *
 * <p>The native query is authoritative because NGX can adjust optimal sizes
 * for the installed driver/model. Deterministic ratios are used only after
 * Streamline has reported the selected feature as supported and the query is
 * temporarily unavailable (for example during a swapchain transition).</p>
 */
public final class NvidiaDlssRenderResolution {
    public record Resolution(int renderWidth, int renderHeight, int outputWidth,
                             int outputHeight, int mode, boolean reduced) {
    }

    private static int cachedOutputWidth = -1;
    private static int cachedOutputHeight = -1;
    private static int cachedMode = -1;
    private static boolean cachedRayReconstruction;
    private static Resolution cachedResolution;

    private NvidiaDlssRenderResolution() {
    }

    public static Resolution resolve(
            int outputWidth,
            int outputHeight,
            int requestedMode,
            boolean dlssSupported,
            boolean rayReconstructionRequested,
            boolean rayReconstructionSupported
    ) {
        int safeWidth = Math.max(1, outputWidth);
        int safeHeight = Math.max(1, outputHeight);
        if (!dlssSupported || requestedMode <= 0) {
            return nativeResolution(safeWidth, safeHeight, 0);
        }

        int mode = Math.max(0, Math.min(6, requestedMode));
        boolean useRayReconstruction = rayReconstructionRequested
                && rayReconstructionSupported;
        if (useRayReconstruction && mode < 2) {
            // RR requires a low-resolution HDR input; Quality is the safest
            // automatic choice when an old config still contains Off/DLAA.
            mode = 2;
        }
        if (mode == 1) {
            return nativeResolution(safeWidth, safeHeight, mode);
        }
        if (cachedResolution != null
                && cachedOutputWidth == safeWidth
                && cachedOutputHeight == safeHeight
                && cachedMode == mode
                && cachedRayReconstruction == useRayReconstruction) {
            return cachedResolution;
        }

        int[] optimal = null;
        try {
            optimal = StreamlineBridge.nGetOptimalRenderSize(
                    mode,
                    safeWidth,
                    safeHeight,
                    useRayReconstruction
            );
        } catch (UnsatisfiedLinkError ignored) {
            // The signed SDK query is preferred; use the documented quality
            // ratios until the bridge is available again.
        }

        int renderWidth;
        int renderHeight;
        boolean nativeOptimal = optimal != null && optimal.length >= 2
                && optimal[0] > 0 && optimal[1] > 0;
        if (nativeOptimal) {
            renderWidth = optimal[0];
            renderHeight = optimal[1];
        } else {
            double ratio = fallbackRatio(mode);
            renderWidth = alignToEight((int) Math.round(safeWidth * ratio));
            renderHeight = alignToEight((int) Math.round(safeHeight * ratio));
        }

        renderWidth = Math.max(32, Math.min(safeWidth, renderWidth));
        renderHeight = Math.max(32, Math.min(safeHeight, renderHeight));
        Resolution resolved = new Resolution(
                renderWidth,
                renderHeight,
                safeWidth,
                safeHeight,
                mode,
                renderWidth != safeWidth || renderHeight != safeHeight
        );
        if (nativeOptimal) {
            cachedOutputWidth = safeWidth;
            cachedOutputHeight = safeHeight;
            cachedMode = mode;
            cachedRayReconstruction = useRayReconstruction;
            cachedResolution = resolved;
        }
        return resolved;
    }

    /**
     * Resolves a deterministic temporal-upscaler input size for Vulkan
     * providers which do not expose a vendor-specific optimal-size query.
     */
    public static Resolution resolveTemporal(
            int outputWidth,
            int outputHeight,
            int requestedMode,
            boolean upscalingSupported
    ) {
        int safeWidth = Math.max(1, outputWidth);
        int safeHeight = Math.max(1, outputHeight);
        if (!upscalingSupported || requestedMode <= 0) {
            return nativeResolution(safeWidth, safeHeight, 0);
        }
        int mode = Math.max(1, Math.min(6, requestedMode));
        if (mode == 1) {
            return nativeResolution(safeWidth, safeHeight, mode);
        }
        double ratio = fallbackRatio(mode);
        int renderWidth = Math.max(32, Math.min(
                safeWidth,
                alignToEight((int) Math.round(safeWidth * ratio))
        ));
        int renderHeight = Math.max(32, Math.min(
                safeHeight,
                alignToEight((int) Math.round(safeHeight * ratio))
        ));
        return new Resolution(
                renderWidth,
                renderHeight,
                safeWidth,
                safeHeight,
                mode,
                renderWidth != safeWidth || renderHeight != safeHeight
        );
    }

    private static Resolution nativeResolution(int width, int height, int mode) {
        return new Resolution(width, height, width, height, mode, false);
    }

    private static double fallbackRatio(int mode) {
        return switch (mode) {
            case 2 -> 2.0 / 3.0; // Quality
            case 3 -> 0.58;      // Balanced
            case 4 -> 0.50;      // Performance
            case 5 -> 1.0 / 3.0; // Ultra Performance
            case 6 -> 0.77;      // Ultra Quality
            default -> 1.0;
        };
    }

    private static int alignToEight(int value) {
        return Math.max(8, (value + 7) & ~7);
    }
}
