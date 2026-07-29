package org.malionaro.dlss_frame_generation.client.ui.settings;
import org.malionaro.dlss_frame_generation.client.backend.FrameGenerationBackend;
import org.malionaro.dlss_frame_generation.client.backend.FrameGenerationCompatibilityLayer;
import org.malionaro.dlss_frame_generation.client.config.FrameGenConfig;
import org.malionaro.dlss_frame_generation.client.StreamlineBridge;

import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.network.chat.Component;

public final class IntelSettingsPanel {
    private IntelSettingsPanel() {}

    public static void addWidgets(MultiVendorSettingsScreen screen, FrameGenConfig config, int capabilities, int topY) {
        int leftX = screen.settingsLeftX();
        int rightX = screen.settingsRightX();
        int columnWidth = screen.settingsColumnWidth();
        boolean fsrFallbackReady =
                (capabilities & StreamlineBridge.CAP_FRAME_GENERATION) != 0;
        boolean xessUpscalingSupported =
                (capabilities & StreamlineBridge.CAP_UPSCALING) != 0;
        boolean fsrProxyReady = (capabilities & StreamlineBridge.CAP_PROXY_SWAPCHAIN) != 0;
        boolean frameGenerationConflict =
                config.conflictingFrameGenerationVendor(
                        FrameGenerationBackend.GpuVendor.INTEL
                ) != FrameGenerationBackend.GpuVendor.UNKNOWN;
        FrameGenerationCompatibilityLayer.Route route =
                FrameGenerationCompatibilityLayer.queryVulkanRoute(
                        FrameGenerationBackend.GpuVendor.INTEL
                );

        // True XeSS-FG is D3D12-only. On Minecraft's Vulkan renderer this
        // control activates the explicitly named FSR 3.1 fallback instead.
        var btnFgXeSS = CycleButton.onOffBuilder(config.intelXessFgEnabled)
                .create(leftX, topY, columnWidth, 20,
                        Component.translatable("dlss_fg.option.intel.frame_generation"), (button, value) -> {
                            config.setFrameGenerationEnabled(
                                    FrameGenerationBackend.GpuVendor.INTEL,
                                    value
                            );
                            config.save();
                            try {
                                StreamlineBridge.nSetFrameGenerationMode(FrameGenerationBackend.GpuVendor.INTEL.vendorId(), value, config.intelXessMfgMultiplier, config.uiRecomposition);
                            } catch (UnsatisfiedLinkError e) {
                                // Ignore
                            }
                            screen.refreshSettingsWidgets();
                        });
        btnFgXeSS.active = fsrFallbackReady
                && !frameGenerationConflict
                && !config.hybridD3D12PresentationEnabled;
        screen.addSettingsWidget(
                btnFgXeSS,
                screen.frameGenerationTooltip(
                        FrameGenerationBackend.GpuVendor.INTEL,
                        "dlss_fg.tooltip.intel.frame_generation"
                )
        );

        // FSR 3.1 inserts one interpolated frame and therefore exposes 2x only.
        var btnMfg = CycleButton.builder(
                        (Integer value) -> Component.translatable(
                                "dlss_fg.value.multiplier", value
                        ),
                        config.intelXessMfgMultiplier)
                .withValues(2)
                .create(rightX, topY, columnWidth, 20,
                        Component.translatable("dlss_fg.option.fsr.fg_factor"), (button, value) -> {
                            config.intelXessMfgMultiplier = value;
                            config.save();
                            try {
                                StreamlineBridge.nSetFrameGenerationMode(FrameGenerationBackend.GpuVendor.INTEL.vendorId(), config.intelXessFgEnabled, value, config.uiRecomposition);
                            } catch (UnsatisfiedLinkError e) {
                                // Ignore
                            }
                        });
        btnMfg.active = fsrFallbackReady
                && config.intelXessFgEnabled
                && !frameGenerationConflict
                && !config.hybridD3D12PresentationEnabled;
        screen.addSettingsWidget(
                btnMfg,
                screen.frameGenerationDependentTooltip(
                        FrameGenerationBackend.GpuVendor.INTEL,
                        "dlss_fg.tooltip.fsr.fg_factor"
                )
        );

        // OptiScaler consumes the standard FFX Vulkan input on Intel. The
        // renderer-owned world target can run below the display resolution.
        var btnXessMode = CycleButton.builder((Integer value) -> {
                    return switch (value) {
                        case 0 -> Component.translatable("dlss_fg.value.off");
                        case 1 -> Component.translatable("dlss_fg.value.native_aa");
                        case 2 -> Component.translatable("dlss_fg.value.quality");
                        case 3 -> Component.translatable("dlss_fg.value.balanced");
                        case 4 -> Component.translatable("dlss_fg.value.performance");
                        case 5 -> Component.translatable("dlss_fg.value.ultra_performance");
                        case 6 -> Component.translatable("dlss_fg.value.ultra_quality");
                        default -> Component.translatable("dlss_fg.value.unknown");
                    };
                }, config.intelXessMode)
                .withValues(0, 1, 2, 3, 4, 5, 6)
                .create(leftX, topY + 25, columnWidth, 20,
                        Component.translatable("dlss_fg.option.intel.upscaling"), (button, value) -> {
                            config.setUpscalerMode(
                                    FrameGenerationBackend.GpuVendor.INTEL,
                                    value
                            );
                            config.save();
                            try {
                                StreamlineBridge.nSetUpscalingMode(FrameGenerationBackend.GpuVendor.INTEL.vendorId(), value, config.intelXessSharpness);
                            } catch (UnsatisfiedLinkError e) {
                                // Ignore
                            }
                        });
        btnXessMode.active = xessUpscalingSupported;
        screen.addSettingsWidget(
                btnXessMode,
                screen.capabilityTooltip(
                        FrameGenerationBackend.GpuVendor.INTEL,
                        capabilities,
                        StreamlineBridge.CAP_UPSCALING,
                        "dlss_fg.tooltip.intel.upscaling"
                )
        );

        // Row 2 Right: XeSS Sharpness Slider
        var sliderXessSharpness = new AbstractSliderButton(
                rightX, topY + 25, columnWidth, 20,
                Component.translatable(
                        "dlss_fg.option.aa_sharpness",
                        (int)(config.intelXessSharpness * 100)
                ),
                config.intelXessSharpness) {
            {
                this.updateMessage();
            }
            @Override
            protected void updateMessage() {
                this.setMessage(Component.translatable(
                        "dlss_fg.option.aa_sharpness",
                        (int)(this.value * 100)
                ));
            }
            @Override
            protected void applyValue() {
                config.intelXessSharpness = (float) this.value;
                config.save();
                try {
                    StreamlineBridge.nSetUpscalingMode(FrameGenerationBackend.GpuVendor.INTEL.vendorId(), config.intelXessMode, config.intelXessSharpness);
                } catch (UnsatisfiedLinkError e) {
                    // Ignore
                }
            }
        };
        sliderXessSharpness.active = xessUpscalingSupported;
        screen.addSettingsWidget(
                sliderXessSharpness,
                screen.capabilityTooltip(
                        FrameGenerationBackend.GpuVendor.INTEL,
                        capabilities,
                        StreamlineBridge.CAP_UPSCALING,
                        "dlss_fg.tooltip.aa_sharpness"
                )
        );

        // Row 3 Left: UI Recomposition
        var btnRecomp = CycleButton.onOffBuilder(config.uiRecomposition)
                .create(leftX, topY + 50, columnWidth, 20,
                        Component.translatable("dlss_fg.option.ui_recomposition"), (button, value) -> {
                            config.uiRecomposition = value;
                            config.save();
                            try {
                                StreamlineBridge.nSetFrameGenerationMode(FrameGenerationBackend.GpuVendor.INTEL.vendorId(), config.intelXessFgEnabled, config.intelXessMfgMultiplier, value);
                            } catch (UnsatisfiedLinkError e) {
                                // Ignore
                            }
                        });
        btnRecomp.active = fsrFallbackReady
                && config.intelXessFgEnabled
                && !frameGenerationConflict
                && !config.hybridD3D12PresentationEnabled;
        screen.addSettingsWidget(
                btnRecomp,
                screen.frameGenerationDependentTooltip(
                        FrameGenerationBackend.GpuVendor.INTEL,
                        "dlss_fg.tooltip.ui_recomposition"
                )
        );

        // XeSS-FG cannot consume a Vulkan swapchain. Show the compatible
        // cross-vendor FSR transport separately so it is never mislabeled.
        var fsrFallbackStatus = CycleButton.onOffBuilder(fsrProxyReady)
                .create(rightX, topY + 50, columnWidth, 20,
                        Component.translatable(route.isFallback()
                                ? "dlss_fg.status.provider.cross_vendor"
                                : "dlss_fg.status.provider.fallback"), (button, value) -> {});
        fsrFallbackStatus.active = false;
        screen.addSettingsWidget(fsrFallbackStatus, "dlss_fg.tooltip.provider_status");
    }
}
