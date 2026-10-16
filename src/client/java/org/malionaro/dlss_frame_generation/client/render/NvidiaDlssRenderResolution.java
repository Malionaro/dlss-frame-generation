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
            int[] size = aspectPreservingSize(
                    safeWidth, safeHeight, fallbackRatio(mode));
            renderWidth = size[0];
            renderHeight = size[1];
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
        int[] size = aspectPreservingSize(
                safeWidth, safeHeight, fallbackRatio(mode));
        int renderWidth = size[0];
        int renderHeight = size[1];
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

    /**
     * A render size for {@code ratio} of the output that keeps the output's
     * aspect ratio.
     *
     * <p>Minecraft builds the world projection from the window size, so the
     * render target must have the window's aspect ratio or the upscaled image
     * is stretched. Rounding width and height up to multiples of eight
     * independently changed that ratio by up to 0.8 % from one size to the
     * next, which made the picture visibly wobble at every dynamic-resolution
     * step. When the reduced ratio is small (16:9 is 16×9 units) the size is a
     * whole number of those units and the ratio is exact; otherwise both sides
     * come from the same scale, rounded to even values.</p>
     */
    public static int[] aspectPreservingSize(int outputWidth, int outputHeight, double ratio) {
        int safeWidth = Math.max(1, outputWidth);
        int safeHeight = Math.max(1, outputHeight);
        double clamped = Double.isFinite(ratio) ? Math.max(0.0, Math.min(1.0, ratio)) : 1.0;
        int divisor = gcd(safeWidth, safeHeight);
        int unitWidth = safeWidth / divisor;
        int unitHeight = safeHeight / divisor;
        int width;
        int height;
        if (unitWidth <= 64 && unitHeight <= 64) {
            int units = Math.max(1, (int) Math.round(divisor * clamped));
            if (((unitWidth | unitHeight) & 1) != 0 && units < divisor) {
                // 16:9 has an odd unit height: keep both sides even (848x477
                // at Ultra Performance tripped the upscaler and motion passes).
                units = Math.max(2, (int) Math.round(divisor * clamped / 2.0) * 2);
            }
            width = unitWidth * units;
            height = unitHeight * units;
        } else {
            width = even((int) Math.round(safeWidth * clamped));
            height = even((int) Math.round(safeHeight * clamped));
        }
        width = Math.max(Math.min(32, safeWidth), Math.min(safeWidth, width));
        height = Math.max(Math.min(32, safeHeight), Math.min(safeHeight, height));
        return new int[] {width, height};
    }

    private static int gcd(int a, int b) {
        while (b != 0) {
            int next = a % b;
            a = b;
            b = next;
        }
        return Math.max(1, a);
    }

    private static int even(int value) {
        return Math.max(2, value + (value & 1));
    }
}
