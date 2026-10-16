package org.malionaro.dlss_frame_generation.client.ui.settings;
import org.malionaro.dlss_frame_generation.client.backend.FrameGenerationBackend;
import org.malionaro.dlss_frame_generation.client.config.FrameGenConfig;
import org.malionaro.dlss_frame_generation.client.StreamlineBridge;

import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.network.chat.Component;

public final class NvidiaSettingsPanel {
    private static final FrameGenerationBackend.GpuVendor NVIDIA =
            FrameGenerationBackend.GpuVendor.NVIDIA;

    private NvidiaSettingsPanel() {}

    /** Frame Generation tab: DLSS-G, multiplier slider, Reflex, pacing, UI layer. */
    public static void addFrameGeneration(MultiVendorSettingsScreen screen, FrameGenConfig config, int capabilities) {
        int columnWidth = screen.settingsColumnWidth();
        boolean fgSupported = (capabilities & StreamlineBridge.CAP_FRAME_GENERATION) != 0;
        boolean reflexSupported = (capabilities & StreamlineBridge.CAP_LOW_LATENCY) != 0;
        boolean mfgAvailable = (capabilities & StreamlineBridge.CAP_MULTI_FRAME_GENERATION) != 0;
        boolean frameGenerationConflict =
                config.conflictingFrameGenerationVendor(NVIDIA)
                        != FrameGenerationBackend.GpuVendor.UNKNOWN;
        boolean fgUsable = fgSupported
                && !frameGenerationConflict
                && !config.hybridD3D12PresentationEnabled;

        var btnFg = CycleButton.onOffBuilder(config.dlssFgEnabled)
                .create(0, 0, columnWidth, 20,
                        Component.translatable("dlss_fg.option.nvidia.frame_generation"), (button, value) -> {
                            config.setFrameGenerationEnabled(NVIDIA, value);
                            if (value && config.reflexMode == 0) {
                                config.reflexMode = 1;
                            }
                            config.save();
                            // Native push only via backend (reflex forcing
                            // etc. live there exactly once).
                            FrameGenerationBackend.applyActiveConfig(config);
                            screen.refreshSettingsWidgets();
                        });
        btnFg.active = fgUsable;
        screen.place(btnFg, screen.frameGenerationTooltip(
                NVIDIA, "dlss_fg.tooltip.nvidia.frame_generation"));

        // Multi-frame generation is a slider like the volume controls: drag
        // between 2x and the driver-reported maximum (up to 6x on RTX 50).
        int ceiling = mfgAvailable ? maximumMultiplier() : 2;
        var multiplier = new SteppedSlider(
                columnWidth, 2, ceiling, 1,
                Math.min(Math.max(config.frameMultiplier, 2), ceiling),
                value -> Component.translatable("dlss_fg.option.fg_multiplier_slider", value),
                value -> {
                    config.frameMultiplier = value;
                    config.saveSoon();
                    // Discrete and rare: push right away so keyboard steps
                    // take effect without a mouse release.
                    FrameGenerationBackend.applyActiveConfig(config);
                },
                value -> config.save());
        multiplier.active = fgUsable && config.dlssFgEnabled && ceiling > 2;
        screen.place(multiplier, screen.frameGenerationDependentTooltip(
                NVIDIA, "dlss_fg.tooltip.nvidia.fg_multiplier"));

        var btnReflex = CycleButton.builder((Integer value) -> switch (value) {
                    case 0 -> Component.translatable("dlss_fg.value.off");
                    case 1 -> Component.translatable("dlss_fg.value.on");
                    case 2 -> Component.translatable("dlss_fg.value.on_boost");
                    default -> Component.translatable("dlss_fg.value.unknown");
                }, config.reflexMode)
                .withValues(0, 1, 2)
                .create(0, 0, columnWidth, 20,
                        Component.translatable("dlss_fg.option.nvidia.reflex"), (button, value) -> {
                            config.reflexMode = value;
                            if (value == 0 && config.dlssFgEnabled) {
                                config.setFrameGenerationEnabled(NVIDIA, false);
                            }
                            config.save();
                            // Native push only via backend.
                            FrameGenerationBackend.applyActiveConfig(config);
                            screen.refreshSettingsWidgets();
                        });
        btnReflex.active = reflexSupported;
        screen.place(btnReflex, screen.capabilityTooltip(
                NVIDIA, capabilities, StreamlineBridge.CAP_LOW_LATENCY,
                "dlss_fg.tooltip.nvidia.reflex"));

        var btnPacing = CycleButton.onOffBuilder(config.reflexPacingEnabled)
                .create(0, 0, columnWidth, 20,
                        Component.translatable("dlss_fg.option.nvidia.reflex_pacing"), (button, value) -> {
                            config.reflexPacingEnabled = value;
                            config.save();
                        });
        btnPacing.active = reflexSupported;
        screen.place(btnPacing, screen.capabilityTooltip(
                NVIDIA, capabilities, StreamlineBridge.CAP_LOW_LATENCY,
                "dlss_fg.tooltip.nvidia.reflex_pacing"));

        screen.addUiRecomposition(config, NVIDIA, fgUsable && config.dlssFgEnabled);
    }

    /** Upscaling tab: DLSS mode. Sharpening is driver-controlled for DLSS. */
    public static void addUpscaling(MultiVendorSettingsScreen screen, FrameGenConfig config, int capabilities) {
        int columnWidth = screen.settingsColumnWidth();
        boolean upscalingSupported = (capabilities & StreamlineBridge.CAP_UPSCALING) != 0;

        var btnDlssMode = CycleButton.builder((Integer value) -> switch (value) {
                    case 0 -> Component.translatable("dlss_fg.value.off");
                    case 1 -> Component.translatable("dlss_fg.value.dlaa");
                    case 2 -> Component.translatable("dlss_fg.value.quality");
                    case 3 -> Component.translatable("dlss_fg.value.balanced");
                    case 4 -> Component.translatable("dlss_fg.value.performance");
                    case 5 -> Component.translatable("dlss_fg.value.ultra_performance");
                    case 6 -> Component.translatable("dlss_fg.value.ultra_quality");
                    default -> Component.translatable("dlss_fg.value.unknown");
                }, config.dlssUpscalingMode)
                .withValues(0, 1, 6, 2, 3, 4, 5)
                .create(0, 0, columnWidth, 20,
                        Component.translatable("dlss_fg.option.nvidia.dlss_mode"), (button, value) -> {
                            config.setUpscalerMode(NVIDIA, value);
                            config.projectionJitterEnabled = value != 0;
                            if (value < 2) {
                                // Ray Reconstruction only accepts a
                                // low-resolution HDR input.
                                config.dlssRayReconstructionEnabled = false;
                            }
                            config.save();
                            // Native push only via backend.
                            FrameGenerationBackend.applyActiveConfig(config);
                            screen.refreshSettingsWidgets();
                        });
        btnDlssMode.active = upscalingSupported;
        screen.place(btnDlssMode, screen.capabilityTooltip(
                NVIDIA, capabilities, StreamlineBridge.CAP_UPSCALING,
                "dlss_fg.tooltip.nvidia.dlss_mode"));

        var status = net.minecraft.client.gui.components.Button.builder(
                        Component.translatable(upscalingSupported
                                ? "dlss_fg.status.dlss.supported"
                                : (capabilities & StreamlineBridge.CAP_LOW_LATENCY) != 0
                                        ? "dlss_fg.status.dlss.unavailable"
                                        : "dlss_fg.status.streamline.unavailable"),
                        button -> {})
                .bounds(0, 0, columnWidth, 20)
                .build();
        status.active = false;
        screen.place(status, Component.translatable("dlss_fg.tooltip.nvidia.dlss_status"));
    }

    private static int maximumMultiplier() {
        try {
            return Math.max(2, Math.min(FrameGenConfig.MAX_FRAME_MULTIPLIER,
                    StreamlineBridge.nGetMaxFrameMultiplier(NVIDIA.vendorId())));
        } catch (UnsatisfiedLinkError ignored) {
            return 2;
        }
    }
}
