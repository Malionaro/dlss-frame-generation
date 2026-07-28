package org.malionaro.dlss_frame_generation.client;

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
        FrameGenerationCompatibilityLayer.Route route =
                FrameGenerationCompatibilityLayer.queryVulkanRoute(
                        FrameGenerationBackend.GpuVendor.INTEL
                );

        // True XeSS-FG is D3D12-only. On Minecraft's Vulkan renderer this
        // control activates the explicitly named FSR 3.1 fallback instead.
        var btnFgXeSS = CycleButton.onOffBuilder(config.intelXessFgEnabled)
                .create(leftX, topY, columnWidth, 20,
                        Component.literal("FSR 3.1 FG Fallback"), (button, value) -> {
                            config.intelXessFgEnabled = value;
                            config.save();
                            try {
                                StreamlineBridge.nSetFrameGenerationMode(FrameGenerationBackend.GpuVendor.INTEL.vendorId(), value, config.intelXessMfgMultiplier, config.uiRecomposition);
                            } catch (UnsatisfiedLinkError e) {
                                // Ignore
                            }
                        });
        btnFgXeSS.active = fsrFallbackReady;
        screen.addSettingsWidget(btnFgXeSS);

        // FSR 3.1 inserts one interpolated frame and therefore exposes 2x only.
        var btnMfg = CycleButton.builder((Integer value) -> Component.literal(value + "x"), config.intelXessMfgMultiplier)
                .withValues(2)
                .create(rightX, topY, columnWidth, 20,
                        Component.literal("FSR FG Factor"), (button, value) -> {
                            config.intelXessMfgMultiplier = value;
                            config.save();
                            try {
                                StreamlineBridge.nSetFrameGenerationMode(FrameGenerationBackend.GpuVendor.INTEL.vendorId(), config.intelXessFgEnabled, value, config.uiRecomposition);
                            } catch (UnsatisfiedLinkError e) {
                                // Ignore
                            }
                        });
        btnMfg.active = fsrFallbackReady;
        screen.addSettingsWidget(btnMfg);

        // OptiScaler can consume the standard FFX Vulkan input on Intel, but
        // Minecraft currently exposes only a full-resolution combined target.
        var btnXessMode = CycleButton.builder((Integer value) -> {
                    return switch (value) {
                        case 0 -> Component.literal("Off");
                        case 1 -> Component.literal("Native AA");
                        default -> Component.literal("Unknown");
                    };
                }, config.intelXessMode)
                .withValues(0, 1)
                .create(leftX, topY + 25, columnWidth, 20,
                        Component.literal("XeSS / FSR Native AA"), (button, value) -> {
                            config.intelXessMode = value;
                            config.save();
                            try {
                                StreamlineBridge.nSetUpscalingMode(FrameGenerationBackend.GpuVendor.INTEL.vendorId(), value, config.intelXessSharpness);
                            } catch (UnsatisfiedLinkError e) {
                                // Ignore
                            }
                        });
        btnXessMode.active = xessUpscalingSupported;
        screen.addSettingsWidget(btnXessMode);

        // Row 2 Right: XeSS Sharpness Slider
        var sliderXessSharpness = new AbstractSliderButton(
                rightX, topY + 25, columnWidth, 20,
                Component.literal("AA Sharpness: " + (int)(config.intelXessSharpness * 100) + "%"),
                config.intelXessSharpness) {
            {
                this.updateMessage();
            }
            @Override
            protected void updateMessage() {
                this.setMessage(Component.literal("AA Sharpness: " + (int)(this.value * 100) + "%"));
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
        screen.addSettingsWidget(sliderXessSharpness);

        // Row 3 Left: UI Recomposition
        var btnRecomp = CycleButton.onOffBuilder(config.uiRecomposition)
                .create(leftX, topY + 50, columnWidth, 20,
                        Component.literal("UI Recomposition"), (button, value) -> {
                            config.uiRecomposition = value;
                            config.save();
                            try {
                                StreamlineBridge.nSetFrameGenerationMode(FrameGenerationBackend.GpuVendor.INTEL.vendorId(), config.intelXessFgEnabled, config.intelXessMfgMultiplier, value);
                            } catch (UnsatisfiedLinkError e) {
                                // Ignore
                            }
                        });
        btnRecomp.active = fsrFallbackReady;
        screen.addSettingsWidget(btnRecomp);

        // XeSS-FG cannot consume a Vulkan swapchain. Show the compatible
        // cross-vendor FSR transport separately so it is never mislabeled.
        var fsrFallbackStatus = CycleButton.onOffBuilder(fsrProxyReady)
                .create(rightX, topY + 50, columnWidth, 20,
                        Component.literal(route.isFallback()
                                ? "FSR 3.1 Cross-Vendor"
                                : "FSR 3.1 Fallback"), (button, value) -> {});
        fsrFallbackStatus.active = false;
        screen.addSettingsWidget(fsrFallbackStatus);
    }
}
