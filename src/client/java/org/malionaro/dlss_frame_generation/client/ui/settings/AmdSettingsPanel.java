package org.malionaro.dlss_frame_generation.client.ui.settings;
import org.malionaro.dlss_frame_generation.client.backend.FrameGenerationBackend;
import org.malionaro.dlss_frame_generation.client.backend.FrameGenerationCompatibilityLayer;
import org.malionaro.dlss_frame_generation.client.config.FrameGenConfig;
import org.malionaro.dlss_frame_generation.client.StreamlineBridge;

import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.network.chat.Component;

public final class AmdSettingsPanel {
    private AmdSettingsPanel() {}

    public static void addWidgets(MultiVendorSettingsScreen screen, FrameGenConfig config, int capabilities, int topY) {
        int leftX = screen.settingsLeftX();
        int rightX = screen.settingsRightX();
        int columnWidth = screen.settingsColumnWidth();
        boolean amdSupported = (capabilities & StreamlineBridge.CAP_FRAME_GENERATION) != 0;
        boolean upscalingSupported =
                (capabilities & StreamlineBridge.CAP_UPSCALING) != 0;
        boolean proxyReady = (capabilities & StreamlineBridge.CAP_PROXY_SWAPCHAIN) != 0;
        boolean frameGenerationConflict =
                config.conflictingFrameGenerationVendor(
                        FrameGenerationBackend.GpuVendor.AMD
                ) != FrameGenerationBackend.GpuVendor.UNKNOWN;
        FrameGenerationCompatibilityLayer.Route route =
                FrameGenerationCompatibilityLayer.queryVulkanRoute(
                        FrameGenerationBackend.GpuVendor.AMD
                );

        // The current AMD SDK exposes FSR 4 frame generation only for D3D12.
        // Minecraft's Vulkan renderer therefore uses the FSR 3.1 provider.
        var btnFgFsr = CycleButton.onOffBuilder(config.amdFsrFgEnabled)
                .create(leftX, topY, columnWidth, 20,
                        Component.translatable("dlss_fg.option.amd.frame_generation"), (button, value) -> {
                            config.setFrameGenerationEnabled(
                                    FrameGenerationBackend.GpuVendor.AMD,
                                    value
                            );
                            config.save();
                            try {
                                StreamlineBridge.nSetFrameGenerationMode(FrameGenerationBackend.GpuVendor.AMD.vendorId(), value, config.amdFsrMultiplier, config.uiRecomposition);
                            } catch (UnsatisfiedLinkError e) {
                                // Ignore
                            }
                            screen.refreshSettingsWidgets();
                        });
        btnFgFsr.active = amdSupported
                && !frameGenerationConflict
                && !config.hybridD3D12PresentationEnabled;
        screen.addSettingsWidget(
                btnFgFsr,
                screen.frameGenerationTooltip(
                        FrameGenerationBackend.GpuVendor.AMD,
                        "dlss_fg.tooltip.amd.frame_generation"
                )
        );

        // FSR 3.1 inserts one interpolated frame. Values above 2x would be a
        // fake multi-frame mode and must not be exposed.
        var btnMult = CycleButton.builder(
                        (Integer value) -> Component.translatable(
                                "dlss_fg.value.multiplier", value
                        ),
                        config.amdFsrMultiplier)
                .withValues(2)
                .create(rightX, topY, columnWidth, 20,
                        Component.translatable("dlss_fg.option.fsr.fg_factor"), (button, value) -> {
                            config.amdFsrMultiplier = value;
                            config.save();
                            try {
                                StreamlineBridge.nSetFrameGenerationMode(FrameGenerationBackend.GpuVendor.AMD.vendorId(), config.amdFsrFgEnabled, value, config.uiRecomposition);
                            } catch (UnsatisfiedLinkError e) {
                                // Ignore
                            }
                        });
        btnMult.active = amdSupported
                && config.amdFsrFgEnabled
                && !frameGenerationConflict
                && !config.hybridD3D12PresentationEnabled;
        screen.addSettingsWidget(
                btnMult,
                screen.frameGenerationDependentTooltip(
                        FrameGenerationBackend.GpuVendor.AMD,
                        "dlss_fg.tooltip.fsr.fg_factor"
                )
        );

        // Row 2 Left: FSR Mode
        var btnFsrMode = CycleButton.builder((Integer value) -> {
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
                }, config.amdFsrMode)
                .withValues(0, 1, 2, 3, 4, 5, 6)
                .create(leftX, topY + 25, columnWidth, 20,
                        Component.translatable("dlss_fg.option.amd.upscaling"), (button, value) -> {
                            config.setUpscalerMode(
                                    FrameGenerationBackend.GpuVendor.AMD,
                                    value
                            );
                            config.save();
                            try {
                                StreamlineBridge.nSetUpscalingMode(FrameGenerationBackend.GpuVendor.AMD.vendorId(), value, config.amdFsrSharpness);
                            } catch (UnsatisfiedLinkError e) {
                                // Ignore
                            }
                        });
        btnFsrMode.active = upscalingSupported;
        screen.addSettingsWidget(
                btnFsrMode,
                screen.capabilityTooltip(
                        FrameGenerationBackend.GpuVendor.AMD,
                        capabilities,
                        StreamlineBridge.CAP_UPSCALING,
                        "dlss_fg.tooltip.amd.upscaling"
                )
        );

        // Row 2 Right: FSR Sharpness (Slider)
        var sliderFsrSharpness = new net.minecraft.client.gui.components.AbstractSliderButton(
                rightX, topY + 25, columnWidth, 20,
                Component.translatable(
                        "dlss_fg.option.aa_sharpness",
                        (int)(config.amdFsrSharpness * 100)
                ),
                config.amdFsrSharpness) {
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
                config.amdFsrSharpness = (float) this.value;
                config.save();
                try {
                    StreamlineBridge.nSetUpscalingMode(FrameGenerationBackend.GpuVendor.AMD.vendorId(), config.amdFsrMode, config.amdFsrSharpness);
                } catch (UnsatisfiedLinkError e) {
                    // Ignore
                }
            }
        };
        sliderFsrSharpness.active = upscalingSupported;
        screen.addSettingsWidget(
                sliderFsrSharpness,
                screen.capabilityTooltip(
                        FrameGenerationBackend.GpuVendor.AMD,
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
                                StreamlineBridge.nSetFrameGenerationMode(FrameGenerationBackend.GpuVendor.AMD.vendorId(), config.amdFsrFgEnabled, config.amdFsrMultiplier, value);
                            } catch (UnsatisfiedLinkError e) {
                                // Ignore
                            }
                        });
        btnRecomp.active = amdSupported
                && config.amdFsrFgEnabled
                && !frameGenerationConflict
                && !config.hybridD3D12PresentationEnabled;
        screen.addSettingsWidget(
                btnRecomp,
                screen.frameGenerationDependentTooltip(
                        FrameGenerationBackend.GpuVendor.AMD,
                        "dlss_fg.tooltip.ui_recomposition"
                )
        );

        var proxyStatus = CycleButton.onOffBuilder(proxyReady)
                .create(rightX, topY + 50, columnWidth, 20,
                        Component.translatable(
                                route.provider()
                                        == FrameGenerationCompatibilityLayer.ProviderId.AMD_FSR31_VULKAN
                                        ? "dlss_fg.status.provider.fsr31"
                                        : "dlss_fg.status.provider.fsr"
                        ), (button, value) -> {});
        proxyStatus.active = false;
        screen.addSettingsWidget(proxyStatus, "dlss_fg.tooltip.provider_status");
    }
}
