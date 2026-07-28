package org.malionaro.dlss_frame_generation.client;

import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.network.chat.Component;

public final class NvidiaSettingsPanel {
    private NvidiaSettingsPanel() {}

    public static void addWidgets(MultiVendorSettingsScreen screen, FrameGenConfig config, int capabilities, int topY) {
        int leftX = screen.settingsLeftX();
        int rightX = screen.settingsRightX();
        int columnWidth = screen.settingsColumnWidth();
        boolean fgSupported = (capabilities & StreamlineBridge.CAP_FRAME_GENERATION) != 0;
        boolean upscalingSupported = (capabilities & StreamlineBridge.CAP_UPSCALING) != 0;
        boolean reflexSupported = (capabilities & StreamlineBridge.CAP_LOW_LATENCY) != 0;
        int maximumMultiplier = 2;
        if (fgSupported) {
            try {
                maximumMultiplier = Math.max(
                        2,
                        Math.min(
                                4,
                                StreamlineBridge.nGetMaxFrameMultiplier(
                                        FrameGenerationBackend.GpuVendor.NVIDIA.vendorId()
                                )
                        )
                );
            } catch (UnsatisfiedLinkError ignored) {
                maximumMultiplier = 2;
            }
        }

        // Row 1 Left: DLSS 4 Frame Generation Switch
        var btnFg = CycleButton.onOffBuilder(config.dlssFgEnabled)
                .create(leftX, topY, columnWidth, 20,
                        Component.literal("DLSS 4 Frame Gen"), (button, value) -> {
                            config.dlssFgEnabled = value;
                            if (value && config.reflexMode == 0) {
                                config.reflexMode = 1;
                            }
                            config.save();
                            try {
                                if (value) {
                                    StreamlineBridge.nSetReflexMode(
                                            config.reflexMode, config.targetFps
                                    );
                                }
                                StreamlineBridge.nSetFgMode(value, config.frameMultiplier, config.uiRecomposition);
                            } catch (UnsatisfiedLinkError e) {
                                // Ignore
                            }
                            screen.refreshSettingsWidgets();
                        });
        btnFg.active = fgSupported;
        screen.addSettingsWidget(btnFg);

        // Row 1 Right: NVIDIA Reflex 2.0 Mode
        var btnReflex = CycleButton.builder((Integer value) -> {
                    return switch (value) {
                        case 0 -> Component.literal("Off");
                        case 1 -> Component.literal("On");
                        case 2 -> Component.literal("On + Boost");
                        default -> Component.literal("Unknown");
                    };
                }, config.reflexMode)
                .withValues(0, 1, 2)
                .create(rightX, topY, columnWidth, 20,
                        Component.literal("NVIDIA Reflex"), (button, value) -> {
                            config.reflexMode = value;
                            if (value == 0 && config.dlssFgEnabled) {
                                config.dlssFgEnabled = false;
                            }
                            config.save();
                            try {
                                if (value == 0) {
                                    StreamlineBridge.nSetFgMode(
                                            false,
                                            config.frameMultiplier,
                                            config.uiRecomposition
                                    );
                                }
                                StreamlineBridge.nSetReflexMode(
                                        value, config.targetFps
                                );
                            } catch (UnsatisfiedLinkError e) {
                                // Ignore
                            }
                            screen.refreshSettingsWidgets();
                        });
        btnReflex.active = reflexSupported;
        screen.addSettingsWidget(btnReflex);

        var btnDlssMode = CycleButton.builder((Integer value) -> {
                    return switch (value) {
                        case 0 -> Component.literal("Off");
                        case 1 -> Component.literal("DLAA (Native)");
                        case 2 -> Component.literal("Quality");
                        case 3 -> Component.literal("Balanced");
                        case 4 -> Component.literal("Performance");
                        case 5 -> Component.literal("Ultra Performance");
                        case 6 -> Component.literal("Ultra Quality");
                        default -> Component.literal("Unknown");
                    };
                }, config.dlssUpscalingMode)
                .withValues(0, 1, 2, 3, 4, 5, 6)
                .create(leftX, topY + 25, columnWidth, 20,
                        Component.literal("DLSS Reconstruction"), (button, value) -> {
                            config.dlssUpscalingMode = value;
                            config.projectionJitterEnabled = value != 0;
                            if (value < 2) {
                                // Ray Reconstruction only accepts a
                                // low-resolution HDR input.
                                config.dlssRayReconstructionEnabled = false;
                            }
                            config.save();
                            try {
                                StreamlineBridge.nSetDlssMode(value, 0.0f);
                            } catch (UnsatisfiedLinkError e) {
                                // Ignore
                            }
                        });
        btnDlssMode.active = upscalingSupported;
        screen.addSettingsWidget(btnDlssMode);

        String dlssStatusLabel = upscalingSupported
                ? "DLSS: hardware supported"
                : reflexSupported
                        ? "DLSS: unavailable (see log)"
                        : "Streamline: unavailable";
        var sharpeningStatus = net.minecraft.client.gui.components.Button.builder(
                        Component.literal(dlssStatusLabel),
                        button -> {})
                .bounds(rightX, topY + 25, columnWidth, 20)
                .build();
        sharpeningStatus.active = false;
        screen.addSettingsWidget(sharpeningStatus);

        // Row 3 Left: RTX 50 Multi-Frame Gen Multiplier (2x, 3x, 4x)
        java.util.List<Integer> multiplierValues =
                java.util.stream.IntStream.rangeClosed(2, maximumMultiplier)
                        .boxed()
                        .toList();
        int displayedMultiplier = Math.min(config.frameMultiplier, maximumMultiplier);
        var btnMultiplier = CycleButton.builder((Integer value) -> Component.literal(value + "x Multi-Frame"), displayedMultiplier)
                .withValues(multiplierValues)
                .create(leftX, topY + 50, columnWidth, 20,
                        Component.literal("FG Multiplier"), (button, value) -> {
                            config.frameMultiplier = value;
                            config.save();
                            try {
                                StreamlineBridge.nSetFgMode(config.dlssFgEnabled, value, config.uiRecomposition);
                            } catch (UnsatisfiedLinkError e) {
                                // Ignore
                            }
                        });
        btnMultiplier.active = fgSupported;
        screen.addSettingsWidget(btnMultiplier);

        // Row 3 Right: Auto-Reflex Latency Pacing
        var btnPacing = CycleButton.onOffBuilder(config.reflexPacingEnabled)
                .create(rightX, topY + 50, columnWidth, 20,
                        Component.literal("Reflex Pacing"), (button, value) -> {
                            config.reflexPacingEnabled = value;
                            config.save();
                        });
        btnPacing.active = reflexSupported;
        screen.addSettingsWidget(btnPacing);
    }
}
