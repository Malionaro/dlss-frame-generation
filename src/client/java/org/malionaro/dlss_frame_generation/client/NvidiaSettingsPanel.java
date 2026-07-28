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
        boolean frameGenerationConflict =
                config.conflictingFrameGenerationVendor(
                        FrameGenerationBackend.GpuVendor.NVIDIA
                ) != FrameGenerationBackend.GpuVendor.UNKNOWN;
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
                        Component.translatable("dlss_fg.option.nvidia.frame_generation"), (button, value) -> {
                            config.setFrameGenerationEnabled(
                                    FrameGenerationBackend.GpuVendor.NVIDIA,
                                    value
                            );
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
        btnFg.active = fgSupported
                && !frameGenerationConflict
                && !config.hybridD3D12PresentationEnabled;
        screen.addSettingsWidget(
                btnFg,
                screen.frameGenerationTooltip(
                        FrameGenerationBackend.GpuVendor.NVIDIA,
                        "dlss_fg.tooltip.nvidia.frame_generation"
                )
        );

        // Row 1 Right: NVIDIA Reflex 2.0 Mode
        var btnReflex = CycleButton.builder((Integer value) -> {
                    return switch (value) {
                        case 0 -> Component.translatable("dlss_fg.value.off");
                        case 1 -> Component.translatable("dlss_fg.value.on");
                        case 2 -> Component.translatable("dlss_fg.value.on_boost");
                        default -> Component.translatable("dlss_fg.value.unknown");
                    };
                }, config.reflexMode)
                .withValues(0, 1, 2)
                .create(rightX, topY, columnWidth, 20,
                        Component.translatable("dlss_fg.option.nvidia.reflex"), (button, value) -> {
                            config.reflexMode = value;
                            if (value == 0 && config.dlssFgEnabled) {
                                config.setFrameGenerationEnabled(
                                        FrameGenerationBackend.GpuVendor.NVIDIA,
                                        false
                                );
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
        screen.addSettingsWidget(
                btnReflex,
                screen.capabilityTooltip(
                        FrameGenerationBackend.GpuVendor.NVIDIA,
                        capabilities,
                        StreamlineBridge.CAP_LOW_LATENCY,
                        "dlss_fg.tooltip.nvidia.reflex"
                )
        );

        var btnDlssMode = CycleButton.builder((Integer value) -> {
                    return switch (value) {
                        case 0 -> Component.translatable("dlss_fg.value.off");
                        case 1 -> Component.translatable("dlss_fg.value.dlaa");
                        case 2 -> Component.translatable("dlss_fg.value.quality");
                        case 3 -> Component.translatable("dlss_fg.value.balanced");
                        case 4 -> Component.translatable("dlss_fg.value.performance");
                        case 5 -> Component.translatable("dlss_fg.value.ultra_performance");
                        case 6 -> Component.translatable("dlss_fg.value.ultra_quality");
                        default -> Component.translatable("dlss_fg.value.unknown");
                    };
                }, config.dlssUpscalingMode)
                .withValues(0, 1, 2, 3, 4, 5, 6)
                .create(leftX, topY + 25, columnWidth, 20,
                        Component.translatable("dlss_fg.option.nvidia.dlss_mode"), (button, value) -> {
                            config.setUpscalerMode(
                                    FrameGenerationBackend.GpuVendor.NVIDIA,
                                    value
                            );
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
        screen.addSettingsWidget(
                btnDlssMode,
                screen.capabilityTooltip(
                        FrameGenerationBackend.GpuVendor.NVIDIA,
                        capabilities,
                        StreamlineBridge.CAP_UPSCALING,
                        "dlss_fg.tooltip.nvidia.dlss_mode"
                )
        );

        Component dlssStatusLabel = upscalingSupported
                ? Component.translatable("dlss_fg.status.dlss.supported")
                : reflexSupported
                        ? Component.translatable("dlss_fg.status.dlss.unavailable")
                        : Component.translatable("dlss_fg.status.streamline.unavailable");
        var sharpeningStatus = net.minecraft.client.gui.components.Button.builder(
                        dlssStatusLabel,
                        button -> {})
                .bounds(rightX, topY + 25, columnWidth, 20)
                .build();
        sharpeningStatus.active = false;
        screen.addSettingsWidget(sharpeningStatus, "dlss_fg.tooltip.nvidia.dlss_status");

        // Row 3 Left: RTX 50 Multi-Frame Gen Multiplier (2x, 3x, 4x)
        java.util.List<Integer> multiplierValues =
                java.util.stream.IntStream.rangeClosed(2, maximumMultiplier)
                        .boxed()
                        .toList();
        int displayedMultiplier = Math.min(config.frameMultiplier, maximumMultiplier);
        var btnMultiplier = CycleButton.builder(
                        (Integer value) -> Component.translatable(
                                "dlss_fg.value.multi_frame", value
                        ),
                        displayedMultiplier)
                .withValues(multiplierValues)
                .create(leftX, topY + 50, columnWidth, 20,
                        Component.translatable("dlss_fg.option.nvidia.fg_multiplier"), (button, value) -> {
                            config.frameMultiplier = value;
                            config.save();
                            try {
                                StreamlineBridge.nSetFgMode(config.dlssFgEnabled, value, config.uiRecomposition);
                            } catch (UnsatisfiedLinkError e) {
                                // Ignore
                            }
                        });
        btnMultiplier.active = fgSupported
                && config.dlssFgEnabled
                && !frameGenerationConflict
                && !config.hybridD3D12PresentationEnabled;
        screen.addSettingsWidget(
                btnMultiplier,
                screen.frameGenerationDependentTooltip(
                        FrameGenerationBackend.GpuVendor.NVIDIA,
                        "dlss_fg.tooltip.nvidia.fg_multiplier"
                )
        );

        // Row 3 Right: Auto-Reflex Latency Pacing
        var btnPacing = CycleButton.onOffBuilder(config.reflexPacingEnabled)
                .create(rightX, topY + 50, columnWidth, 20,
                        Component.translatable("dlss_fg.option.nvidia.reflex_pacing"), (button, value) -> {
                            config.reflexPacingEnabled = value;
                            config.save();
                        });
        btnPacing.active = reflexSupported;
        screen.addSettingsWidget(
                btnPacing,
                screen.capabilityTooltip(
                        FrameGenerationBackend.GpuVendor.NVIDIA,
                        capabilities,
                        StreamlineBridge.CAP_LOW_LATENCY,
                        "dlss_fg.tooltip.nvidia.reflex_pacing"
                )
        );
    }
}
