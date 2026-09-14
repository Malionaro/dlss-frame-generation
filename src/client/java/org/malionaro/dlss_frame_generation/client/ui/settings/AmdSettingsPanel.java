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
                            // D1: native push only via backend.
                            FrameGenerationBackend.applyActiveConfig(config);
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
        // fake multi-frame mode and must not be exposed. The multiplier is
        // shown as a read-only fixed value instead of a cycle button.
        var lblMultFixed = net.minecraft.client.gui.components.Button.builder(
                        Component.translatable("dlss_fg.option.frame_multiplier_fixed", 2),
                        button -> {})
                .bounds(rightX, topY, columnWidth, 20)
                .build();
        lblMultFixed.active = false;
        screen.addSettingsWidget(lblMultFixed, "dlss_fg.tooltip.frame_multiplier_fixed");

        // Row 2 Left: FSR Mode. Only 0=Off and 1=Native-AA are real Vulkan
        // modes (C2: sanitize maps anything higher to Off), so no higher
        // values are offered.
        var btnFsrMode = CycleButton.builder((Integer value) -> {
                    return switch (value) {
                        case 0 -> Component.translatable("dlss_fg.value.off");
                        case 1 -> Component.translatable("dlss_fg.value.native_aa");
                        default -> Component.translatable("dlss_fg.value.unknown");
                    };
                }, Math.min(config.amdFsrMode, 1))
                .withValues(0, 1)
                .create(leftX, topY + 25, columnWidth, 20,
                        Component.translatable("dlss_fg.option.amd.upscaling"), (button, value) -> {
                            config.setUpscalerMode(
                                    FrameGenerationBackend.GpuVendor.AMD,
                                    value
                            );
                            config.save();
                            // D1: native push only via backend.
                            FrameGenerationBackend.applyActiveConfig(config);
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
                // D2/D3: cheap per-tick field update only (finite + clamped).
                // Disk persists coalesced (C9 saveSoon, flushed on release);
                // native apply stays throttled to avoid per-pixel JNI flapping.
                double raw = this.value;
                if (!Double.isFinite(raw)) {
                    return;
                }
                config.amdFsrSharpness = (float) Math.max(0.0, Math.min(1.0, raw));
                config.saveSoon();
                long now = System.nanoTime();
                if (now - lastSharpnessApplyNanos < 200_000_000L) {
                    return;
                }
                lastSharpnessApplyNanos = now;
                // D1: native push only via backend.
                FrameGenerationBackend.applyActiveConfig(config);
            }
            @Override
            public void onRelease(net.minecraft.client.input.MouseButtonEvent event) {
                super.onRelease(event);
                lastSharpnessApplyNanos = 0L;
                config.save();
                // D1: native push only via backend.
                FrameGenerationBackend.applyActiveConfig(config);
            }
            private long lastSharpnessApplyNanos;
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
                            // D1: native push only via backend.
                            FrameGenerationBackend.applyActiveConfig(config);
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
