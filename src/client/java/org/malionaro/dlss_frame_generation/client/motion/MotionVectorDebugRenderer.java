package org.malionaro.dlss_frame_generation.client.motion;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Camera;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.malionaro.dlss_frame_generation.client.backend.FrameGenerationBackend;
import org.malionaro.dlss_frame_generation.client.mixin.CameraAccessor;
import org.malionaro.dlss_frame_generation.client.mixin.GameRendererAccessor;

/**
 * In-game debug visualization for the currently available motion-vector source.
 * Until a dense native buffer is available, it renders a camera-motion preview
 * whose arrows follow the direction in which the camera moves on screen.
 */
public class MotionVectorDebugRenderer {
    private static final int ZERO_SNAPSHOT_WARNING_THRESHOLD = 3;
    private static final long CAMERA_PREVIEW_HOLD_NANOS =
            3_000_000_000L;

    public enum DebugMode {
        OFF,
        VECTOR_FIELD,
        VECTOR_HEATMAP,
        RAW_MOTION_VECTORS
    }

    public enum SamplingDensity {
        LOW(32),
        MEDIUM(16),
        HIGH(8);

        private final int stepPixels;

        SamplingDensity(int stepPixels) {
            this.stepPixels = stepPixels;
        }

        public int stepPixels() {
            return stepPixels;
        }
    }

    private DebugMode debugMode = DebugMode.OFF;
    private SamplingDensity density = SamplingDensity.MEDIUM;
    private final MotionVectorValidator validator = new MotionVectorValidator();
    private MotionVectorResource currentResource;
    private MotionVectorValidator.ValidationResult currentValidation;
    private boolean cameraSampleInitialized;
    private double previousCameraX;
    private double previousCameraY;
    private double previousCameraZ;
    private float previousYaw;
    private float previousPitch;
    private float motionX;
    private float motionY;
    private float motionMagnitude;
    private long lastCameraMotionNanos;
    private long lastEvaluatedSnapshotFrame = -1L;
    private int consecutiveZeroSnapshots;

    public void setDebugMode(DebugMode debugMode) {
        this.debugMode = debugMode;
    }

    public DebugMode getDebugMode() {
        return debugMode;
    }

    public void setSamplingDensity(SamplingDensity density) {
        this.density = density;
    }

    public void updateCameraMotion(Minecraft mc, int screenWidth, int screenHeight) {
        if (mc == null || mc.level == null || mc.gameRenderer == null) {
            resetCameraSample();
            return;
        }

        Camera camera = null;
        if (mc.gameRenderer instanceof GameRendererAccessor rendererAccessor) {
            camera = rendererAccessor.dlss_fg$getMainCamera();
        }
        if (!(camera instanceof CameraAccessor cameraAccessor)) {
            resetCameraSample();
            return;
        }

        Vec3 position = cameraAccessor.dlss_fg$getPosition();
        Vector3f forwards = cameraAccessor.dlss_fg$getForwards();
        Vector3f up = cameraAccessor.dlss_fg$getUp();
        Vector3f left = cameraAccessor.dlss_fg$getLeft();
        float yaw = (float) Math.atan2(forwards.x, forwards.z);
        float pitch = (float) Math.asin(clamp(forwards.y, -1.0f, 1.0f));

        if (!cameraSampleInitialized) {
            cameraSampleInitialized = true;
            previousCameraX = position.x;
            previousCameraY = position.y;
            previousCameraZ = position.z;
            previousYaw = yaw;
            previousPitch = pitch;
            motionX = 0.0f;
            motionY = 0.0f;
        } else {
            float deltaYaw = wrapRadians(yaw - previousYaw);
            float deltaPitch = pitch - previousPitch;
            float deltaX = (float) (position.x - previousCameraX);
            float deltaY = (float) (position.y - previousCameraY);
            float deltaZ = (float) (position.z - previousCameraZ);

            float rightX = -left.x;
            float rightY = -left.y;
            float rightZ = -left.z;
            float lateralMovement = deltaX * rightX + deltaY * rightY + deltaZ * rightZ;
            float verticalMovement = deltaX * up.x + deltaY * up.y + deltaZ * up.z;

            float rawMotionX = cameraPreviewMotionX(deltaYaw, lateralMovement, screenWidth);
            float rawMotionY = cameraPreviewMotionY(deltaPitch, verticalMovement, screenHeight);
            boolean receivedMotion = Math.abs(rawMotionX) > 0.02f || Math.abs(rawMotionY) > 0.02f;
            if (receivedMotion) {
                motionX = motionX * 0.30f + rawMotionX * 0.70f;
                motionY = motionY * 0.30f + rawMotionY * 0.70f;
                lastCameraMotionNanos = System.nanoTime();
            } else if (System.nanoTime() - lastCameraMotionNanos
                    < CAMERA_PREVIEW_HOLD_NANOS) {
                // Keep the last direction stable long enough to inspect.
            } else {
                motionX *= 0.90f;
                motionY *= 0.90f;
            }

            if (Math.abs(motionX) < 0.02f) motionX = 0.0f;
            if (Math.abs(motionY) < 0.02f) motionY = 0.0f;

            previousCameraX = position.x;
            previousCameraY = position.y;
            previousCameraZ = position.z;
            previousYaw = yaw;
            previousPitch = pitch;
        }

        motionMagnitude = (float) Math.hypot(motionX, motionY);
        boolean cameraMoving = motionMagnitude > 0.15f;
        currentResource = new MotionVectorResource(
                MotionVectorResource.ResourceType.CAMERA_MATRIX_APPROXIMATION,
                0,
                screenWidth,
                screenHeight,
                MotionVectorResource.MotionSpace.SCREEN_SPACE_PIXELS,
                false,
                cameraMoving,
                false
        );
        currentValidation = validator.validate(currentResource);
    }

    public MotionVectorResource currentResource() {
        return currentResource;
    }

    public MotionVectorValidator.ValidationResult currentValidation() {
        return currentValidation;
    }

    /**
     * Called from GuiHudMixin.onExtractRenderState() to draw the debug overlay
     * using MC 26.2's GuiGraphicsExtractor API.
     */
    public void renderHudOverlay(GuiGraphicsExtractor extractor, int screenWidth, int screenHeight,
                                 FrameGenerationBackend activeBackend, MotionVectorResource mvResource,
                                 MotionVectorValidator.ValidationResult validationResult,
                                 MotionVectorSnapshot gpuSnapshot) {
        if (debugMode == DebugMode.OFF || extractor == null) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.font == null) return;

        // A submitted GPU snapshot trails the camera sample, and a broken or
        // unsupported readback can stay zero. Keep the debug modes useful by
        // drawing the camera preview until a non-zero GPU field arrives.
        MotionVectorSnapshot visualSnapshot =
                gpuSnapshot != null
                        && !(motionMagnitude > 0.15f
                        && gpuSnapshot.maximumMagnitude() < 0.05f)
                        ? gpuSnapshot
                        : null;

        // Render Vector Field / Heatmap Overlay depending on active debug mode
        if (debugMode == DebugMode.VECTOR_FIELD) {
            renderVectorField(
                    extractor, screenWidth, screenHeight, visualSnapshot, mc);
        } else if (debugMode == DebugMode.VECTOR_HEATMAP) {
            renderHeatmap(
                    extractor, screenWidth, screenHeight, visualSnapshot, mc);
        } else if (debugMode == DebugMode.RAW_MOTION_VECTORS) {
            renderRawMotionView(
                    extractor, screenWidth, screenHeight, visualSnapshot, mc);
        }

        // Render Info Panel Overlay
        int x = 4;
        int y = 4;
        int colorHeader = 0xFFFFFF00;
        int colorText = 0xFFFFFFFF;
        int colorWarn = 0xFFFF5555;

        extractor.text(
                mc.font,
                net.minecraft.network.chat.Component.translatable(
                        "dlss_fg.motion_debug.title"
                ),
                x, y, colorHeader
        );
        y += 12;
        extractor.text(
                mc.font,
                net.minecraft.network.chat.Component.translatable(
                        "dlss_fg.motion_debug.backend",
                        activeBackend != null
                                ? activeBackend.vendor().displayName()
                                : net.minecraft.network.chat.Component.translatable(
                                        "dlss_fg.value.none"
                                )
                ),
                x, y, colorText
        );
        y += 12;
        extractor.text(
                mc.font,
                net.minecraft.network.chat.Component.translatable(
                        "dlss_fg.motion_debug.mode",
                        debugMode.name(),
                        density.name()
                ),
                x, y, colorText
        );
        y += 12;

        if (mvResource != null) {
            extractor.text(mc.font, net.minecraft.network.chat.Component.translatable(
                    "dlss_fg.motion_debug.resource",
                    mvResource.resourceType().name()
            ), x, y, colorText); y += 12;
            extractor.text(mc.font, net.minecraft.network.chat.Component.translatable(
                    "dlss_fg.motion_debug.resolution",
                    mvResource.width(),
                    mvResource.height()
            ), x, y, colorText); y += 12;
            extractor.text(mc.font, net.minecraft.network.chat.Component.translatable(
                    "dlss_fg.motion_debug.vector_space",
                    mvResource.motionSpace().name()
            ), x, y, colorText); y += 12;
            extractor.text(mc.font, net.minecraft.network.chat.Component.translatable(
                    "dlss_fg.motion_debug.y_inverted",
                    yesNo(mvResource.isYAxisInverted())
            ), x, y, colorText); y += 12;
            extractor.text(mc.font, net.minecraft.network.chat.Component.translatable(
                    "dlss_fg.motion_debug.camera_motion",
                    yesNo(mvResource.isCameraMotionIncluded())
            ), x, y, colorText); y += 12;
            extractor.text(mc.font, net.minecraft.network.chat.Component.translatable(
                    "dlss_fg.motion_debug.object_motion",
                    yesNo(mvResource.isObjectMotionIncluded())
            ), x, y, colorText); y += 12;
        } else {
            extractor.text(
                    mc.font,
                    net.minecraft.network.chat.Component.translatable(
                            "dlss_fg.motion_debug.resource_missing"
                    ),
                    x, y, colorWarn
            );
            y += 12;
        }

        if (validationResult != null) {
            int statusColor = validationResult.valid ? 0xFF55FF55 : colorWarn;
            extractor.text(
                    mc.font,
                    net.minecraft.network.chat.Component.translatable(
                            "dlss_fg.motion_debug.status",
                            validationResult.statusMessage
                    ),
                    x, y, statusColor
            );
            y += 12;
        }
        if (gpuSnapshot != null) {
            boolean cameraMotionExpected = motionMagnitude > 1.0f;
            boolean zeroSnapshot = gpuSnapshot.maximumMagnitude() < 0.05f;
            if (gpuSnapshot.frameNumber() != lastEvaluatedSnapshotFrame) {
                lastEvaluatedSnapshotFrame = gpuSnapshot.frameNumber();
                consecutiveZeroSnapshots = cameraMotionExpected && zeroSnapshot
                        ? consecutiveZeroSnapshots + 1
                        : 0;
            }
            // GPU readback intentionally trails the HUD camera sample by at
            // least one submitted frame. Do not report a failure for that
            // normal latency; require multiple distinct zero snapshots.
            boolean suspiciouslyZero = cameraMotionExpected
                    && zeroSnapshot
                    && consecutiveZeroSnapshots
                    >= ZERO_SNAPSHOT_WARNING_THRESHOLD;
            boolean waitingForMotion = cameraMotionExpected
                    && zeroSnapshot
                    && !suspiciouslyZero;
            String snapshotKey = suspiciouslyZero
                    ? "dlss_fg.motion_debug.snapshot.pending"
                    : waitingForMotion
                            ? "dlss_fg.motion_debug.snapshot.waiting"
                            : "dlss_fg.motion_debug.snapshot.ready";
            extractor.text(
                    mc.font,
                    net.minecraft.network.chat.Component.translatable(
                            snapshotKey,
                            gpuSnapshot.frameNumber(),
                            String.format(
                                    java.util.Locale.ROOT,
                                    "%.2f",
                                    gpuSnapshot.maximumMagnitude()
                            )
                    ),
                    x,
                    y,
                    suspiciouslyZero
                            ? colorHeader
                            : waitingForMotion ? colorHeader : 0xFF55FF55
            );
        }
    }

    private void renderVectorField(
            GuiGraphicsExtractor extractor,
            int width,
            int height,
            MotionVectorSnapshot gpuSnapshot,
            Minecraft mc
    ) {
        int step = density.stepPixels() * 2;
        int arrowColor = 0xFF00FF66;
        int idleColor = 0x80606060;
        boolean drewMotion = false;
        for (int y = step; y < height - step; y += step) {
            for (int x = step; x < width - step; x += step) {
                MotionVectorSnapshot.Vector vector = gpuSnapshot != null
                        ? gpuSnapshot.sampleVisualFlow(x, y, width, height)
                        : new MotionVectorSnapshot.Vector(motionX, motionY);
                float dx = clamp(vector.x(), -14.0f, 14.0f);
                float dy = clamp(vector.y(), -14.0f, 14.0f);
                if (vector.magnitude() > 0.15f) {
                    drawArrow(extractor, x, y, dx, dy, arrowColor);
                    drewMotion = true;
                } else {
                    extractor.fill(x, y, x + 2, y + 2, idleColor);
                }
            }
        }

        if (!drewMotion) {
            extractor.text(
                    mc.font,
                    net.minecraft.network.chat.Component.translatable(
                            "dlss_fg.motion_debug.move_hint"
                    ),
                    4, height - 14, 0xFFFFFFFF
            );
        } else if (gpuSnapshot != null) {
            extractor.text(
                    mc.font,
                    net.minecraft.network.chat.Component.translatable(
                            "dlss_fg.motion_debug.arrow_legend"
                    ),
                    4,
                    height - 14,
                    0xFFFFFFFF
            );
        }
    }

    private void renderHeatmap(
            GuiGraphicsExtractor extractor,
            int width,
            int height,
            MotionVectorSnapshot gpuSnapshot,
            Minecraft mc
    ) {
        int tile = Math.max(12, density.stepPixels());
        for (int y = 0; y < height; y += tile) {
            for (int x = 0; x < width; x += tile) {
                float magnitude = gpuSnapshot != null
                        ? gpuSnapshot.sampleRaw(
                                x + tile / 2,
                                y + tile / 2,
                                width,
                                height
                        ).magnitude()
                        : motionMagnitude;
                int color = heatColor(heatIntensity(magnitude));
                extractor.fill(x, y, Math.min(width, x + tile - 1), Math.min(height, y + tile - 1), color);
            }
        }

        int centerX = width / 2;
        int centerY = height / 2;
        MotionVectorSnapshot.Vector centerVector = gpuSnapshot != null
                ? gpuSnapshot.sampleVisualFlow(centerX, centerY, width, height)
                : new MotionVectorSnapshot.Vector(motionX, motionY);
        if (centerVector.magnitude() > 0.15f) {
            float arrowScale = Math.min(1.0f, 28.0f / centerVector.magnitude());
            drawArrow(
                    extractor,
                    centerX,
                    centerY,
                    centerVector.x() * arrowScale,
                    centerVector.y() * arrowScale,
                    0xFFFFFFFF
            );
        } else {
            extractor.fill(centerX - 1, centerY - 1, centerX + 2, centerY + 2, 0xA0FFFFFF);
        }

        if (mc != null && mc.font != null) {
            net.minecraft.network.chat.Component legend =
                    net.minecraft.network.chat.Component.translatable(
                            "dlss_fg.motion_debug.heatmap_legend",
                            String.format(
                                    java.util.Locale.ROOT,
                                    "%.2f",
                                    gpuSnapshot != null
                                            ? gpuSnapshot.maximumMagnitude()
                                            : motionMagnitude
                            )
            );
            extractor.text(mc.font, legend, 4, Math.max(4, height - 14), 0xFFFFFFFF);
        }
    }

    private void renderRawMotionView(
            GuiGraphicsExtractor extractor,
            int width,
            int height,
            MotionVectorSnapshot gpuSnapshot,
            Minecraft mc
    ) {
        int x1 = width / 4;
        int y1 = height / 4;
        int x2 = width * 3 / 4;
        int y2 = height * 3 / 4;
        extractor.fill(x1, y1, x2, y2, 0xD0101018);

        int cellSize = 10;
        for (int y = y1 + 34; y < y2 - 4; y += cellSize) {
            for (int x = x1 + 4; x < x2 - 4; x += cellSize) {
                MotionVectorSnapshot.Vector vector = gpuSnapshot != null
                        ? gpuSnapshot.sampleRaw(x, y, width, height)
                        : new MotionVectorSnapshot.Vector(motionX, motionY);
                int encodedColor = encodedMotionColor(
                        vector.x(),
                        vector.y(),
                        vector.magnitude()
                );
                int checkerColor = ((x + y) / cellSize & 1) == 0
                        ? encodedColor
                        : (encodedColor & 0x00FFFFFF) | 0xA0000000;
                extractor.fill(x, y, Math.min(x2 - 4, x + cellSize - 2), Math.min(y2 - 4, y + cellSize - 2), checkerColor);
            }
        }

        int centerX = (x1 + x2) / 2;
        int centerY = (y1 + y2) / 2 + 12;
        MotionVectorSnapshot.Vector centerVector = gpuSnapshot != null
                ? gpuSnapshot.sampleVisualFlow(centerX, centerY, width, height)
                : new MotionVectorSnapshot.Vector(motionX, motionY);
        drawArrow(
                extractor,
                centerX,
                centerY,
                clamp(centerVector.x(), -28.0f, 28.0f),
                clamp(centerVector.y(), -28.0f, 28.0f),
                0xFFFFFFFF
        );
        extractor.text(
                mc.font,
                net.minecraft.network.chat.Component.translatable(
                        gpuSnapshot != null
                                ? "dlss_fg.motion_debug.raw.gpu"
                                : "dlss_fg.motion_debug.raw.camera"
                ),
                x1 + 6,
                y1 + 6,
                0xFFFFFFFF
        );
        extractor.text(
                mc.font,
                net.minecraft.network.chat.Component.translatable(
                        "dlss_fg.motion_debug.raw.values",
                        String.format(
                                java.util.Locale.ROOT,
                                "%+.2f",
                                gpuSnapshot != null
                                    ? gpuSnapshot.sampleRaw(
                                            centerX, centerY, width, height
                                    ).x()
                                    : motionX
                        ),
                        String.format(
                                java.util.Locale.ROOT,
                                "%+.2f",
                                gpuSnapshot != null
                                    ? gpuSnapshot.sampleRaw(
                                            centerX, centerY, width, height
                                    ).y()
                                    : motionY
                        ),
                        String.format(
                                java.util.Locale.ROOT,
                                "%.2f",
                                centerVector.magnitude()
                        )
                ),
                x1 + 6,
                y1 + 18,
                0xFFDDDDDD
        );
    }

    private static net.minecraft.network.chat.Component yesNo(boolean value) {
        return net.minecraft.network.chat.Component.translatable(
                value ? "dlss_fg.value.yes" : "dlss_fg.value.no"
        );
    }

    private void drawArrow(GuiGraphicsExtractor extractor, int startX, int startY, float vectorX, float vectorY, int color) {
        float length = (float) Math.hypot(vectorX, vectorY);
        if (length < 0.15f) {
            extractor.fill(startX - 1, startY - 1, startX + 2, startY + 2, color);
            return;
        }

        float minimumScale = length < 4.0f ? 4.0f / length : 1.0f;
        int endX = startX + Math.round(vectorX * minimumScale);
        int endY = startY + Math.round(vectorY * minimumScale);
        drawLine(extractor, startX, startY, endX, endY, color);

        float unitX = (endX - startX) / Math.max(1.0f, (float) Math.hypot(endX - startX, endY - startY));
        float unitY = (endY - startY) / Math.max(1.0f, (float) Math.hypot(endX - startX, endY - startY));
        int head1X = endX + Math.round(-unitX * 4.0f - unitY * 3.0f);
        int head1Y = endY + Math.round(-unitY * 4.0f + unitX * 3.0f);
        int head2X = endX + Math.round(-unitX * 4.0f + unitY * 3.0f);
        int head2Y = endY + Math.round(-unitY * 4.0f - unitX * 3.0f);
        drawLine(extractor, endX, endY, head1X, head1Y, color);
        drawLine(extractor, endX, endY, head2X, head2Y, color);
    }

    private void drawLine(GuiGraphicsExtractor extractor, int x1, int y1, int x2, int y2, int color) {
        int dx = x2 - x1;
        int dy = y2 - y1;
        int steps = Math.max(Math.abs(dx), Math.abs(dy));
        if (steps == 0) {
            extractor.fill(x1, y1, x1 + 2, y1 + 2, color);
            return;
        }
        for (int i = 0; i <= steps; i++) {
            int x = x1 + Math.round(dx * (i / (float) steps));
            int y = y1 + Math.round(dy * (i / (float) steps));
            extractor.fill(x, y, x + 2, y + 2, color);
        }
    }

    static float cameraPreviewMotionX(float deltaYaw, float lateralMovement, int screenWidth) {
        // Minecraft's sampled yaw decreases when the player looks right.
        // Positive screen X points right.
        return -deltaYaw * screenWidth * 0.75f + lateralMovement * 8.0f;
    }

    static float cameraPreviewMotionY(float deltaPitch, float verticalMovement, int screenHeight) {
        // Minecraft's forward-vector pitch increases while looking up, whereas
        // GUI Y increases downwards. Both camera rotation and translation must
        // therefore be negated to follow the visible camera direction.
        return -deltaPitch * screenHeight * 0.75f - verticalMovement * 8.0f;
    }

    static float heatIntensity(float magnitude) {
        if (magnitude <= 0.15f) {
            return 0.0f;
        }
        // Avoid the previous hard saturation at 14 px. This response remains
        // visibly different for slow, medium and fast camera movement.
        return clamp(1.0f - (float) Math.exp(-magnitude / 32.0f), 0.0f, 1.0f);
    }

    private int heatColor(float intensity) {
        float value = clamp(intensity, 0.0f, 1.0f);
        if (value <= 0.0f) {
            return 0x00000000;
        }
        int red;
        int green;
        int blue;
        if (value < 0.33f) {
            float t = value / 0.33f;
            red = 24;
            green = Math.round(80 + 175 * t);
            blue = Math.round(255 - 180 * t);
        } else if (value < 0.66f) {
            float t = (value - 0.33f) / 0.33f;
            red = Math.round(24 + 231 * t);
            green = 255;
            blue = Math.round(75 - 75 * t);
        } else {
            float t = (value - 0.66f) / 0.34f;
            red = 255;
            green = Math.round(255 - 210 * t);
            blue = 0;
        }
        int alpha = Math.round(40.0f + 104.0f * value);
        return (alpha << 24) | (red << 16) | (green << 8) | blue;
    }

    private int encodedMotionColor(float vectorX, float vectorY, float magnitude) {
        int red = Math.round((clamp(vectorX / 20.0f, -1.0f, 1.0f) * 0.5f + 0.5f) * 255.0f);
        int green = Math.round((clamp(vectorY / 20.0f, -1.0f, 1.0f) * 0.5f + 0.5f) * 255.0f);
        int blue = Math.round(clamp(magnitude / 20.0f, 0.0f, 1.0f) * 255.0f);
        return 0xD0000000 | (red << 16) | (green << 8) | blue;
    }

    private void resetCameraSample() {
        cameraSampleInitialized = false;
        motionX = 0.0f;
        motionY = 0.0f;
        motionMagnitude = 0.0f;
        lastCameraMotionNanos = 0L;
        currentResource = null;
        currentValidation = validator.validate(null);
    }

    private static float wrapRadians(float value) {
        while (value > Math.PI) value -= (float) (Math.PI * 2.0);
        while (value < -Math.PI) value += (float) (Math.PI * 2.0);
        return value;
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
