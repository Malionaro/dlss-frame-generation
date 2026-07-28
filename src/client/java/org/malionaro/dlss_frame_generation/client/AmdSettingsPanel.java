package org.malionaro.dlss_frame_generation.client;

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
        FrameGenerationCompatibilityLayer.Route route =
                FrameGenerationCompatibilityLayer.queryVulkanRoute(
                        FrameGenerationBackend.GpuVendor.AMD
                );

        // The current AMD SDK exposes FSR 4 frame generation only for D3D12.
        // Minecraft's Vulkan renderer therefore uses the FSR 3.1 provider.
        var btnFgFsr = CycleButton.onOffBuilder(config.amdFsrFgEnabled)
                .create(leftX, topY, columnWidth, 20,
                        Component.literal("FSR 3.1 FG (Vulkan)"), (button, value) -> {
                            config.amdFsrFgEnabled = value;
                            config.save();
                            try {
                                StreamlineBridge.nSetFrameGenerationMode(FrameGenerationBackend.GpuVendor.AMD.vendorId(), value, config.amdFsrMultiplier, config.uiRecomposition);
                            } catch (UnsatisfiedLinkError e) {
                                // Ignore
                            }
                        });
        btnFgFsr.active = amdSupported;
        screen.addSettingsWidget(btnFgFsr);

        // FSR 3.1 inserts one interpolated frame. Values above 2x would be a
        // fake multi-frame mode and must not be exposed.
        var btnMult = CycleButton.builder((Integer value) -> Component.literal(value + "x"), config.amdFsrMultiplier)
                .withValues(2)
                .create(rightX, topY, columnWidth, 20,
                        Component.literal("FSR FG Factor"), (button, value) -> {
                            config.amdFsrMultiplier = value;
                            config.save();
                            try {
                                StreamlineBridge.nSetFrameGenerationMode(FrameGenerationBackend.GpuVendor.AMD.vendorId(), config.amdFsrFgEnabled, value, config.uiRecomposition);
                            } catch (UnsatisfiedLinkError e) {
                                // Ignore
                            }
                        });
        btnMult.active = amdSupported;
        screen.addSettingsWidget(btnMult);

        // Row 2 Left: FSR Mode
        var btnFsrMode = CycleButton.builder((Integer value) -> {
                    return switch (value) {
                        case 0 -> Component.literal("Off");
                        case 1 -> Component.literal("Native AA");
                        default -> Component.literal("Unknown");
                    };
                }, config.amdFsrMode)
                .withValues(0, 1)
                .create(leftX, topY + 25, columnWidth, 20,
                        Component.literal("FSR / OptiScaler"), (button, value) -> {
                            config.amdFsrMode = value;
                            config.save();
                            try {
                                StreamlineBridge.nSetUpscalingMode(FrameGenerationBackend.GpuVendor.AMD.vendorId(), value, config.amdFsrSharpness);
                            } catch (UnsatisfiedLinkError e) {
                                // Ignore
                            }
                        });
        btnFsrMode.active = upscalingSupported;
        screen.addSettingsWidget(btnFsrMode);

        // Row 2 Right: FSR Sharpness (Slider)
        var sliderFsrSharpness = new net.minecraft.client.gui.components.AbstractSliderButton(
                rightX, topY + 25, columnWidth, 20,
                Component.literal("AA Sharpness: " + (int)(config.amdFsrSharpness * 100) + "%"),
                config.amdFsrSharpness) {
            {
                this.updateMessage();
            }
            @Override
            protected void updateMessage() {
                this.setMessage(Component.literal("AA Sharpness: " + (int)(this.value * 100) + "%"));
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
        screen.addSettingsWidget(sliderFsrSharpness);

        // Row 3 Left: UI Recomposition
        var btnRecomp = CycleButton.onOffBuilder(config.uiRecomposition)
                .create(leftX, topY + 50, columnWidth, 20,
                        Component.literal("UI Recomposition"), (button, value) -> {
                            config.uiRecomposition = value;
                            config.save();
                            try {
                                StreamlineBridge.nSetFrameGenerationMode(FrameGenerationBackend.GpuVendor.AMD.vendorId(), config.amdFsrFgEnabled, config.amdFsrMultiplier, value);
                            } catch (UnsatisfiedLinkError e) {
                                // Ignore
                            }
                        });
        btnRecomp.active = amdSupported;
        screen.addSettingsWidget(btnRecomp);

        var proxyStatus = CycleButton.onOffBuilder(proxyReady)
                .create(rightX, topY + 50, columnWidth, 20,
                        Component.literal(shortProviderName(route.provider())), (button, value) -> {});
        proxyStatus.active = false;
        screen.addSettingsWidget(proxyStatus);
    }

    private static String shortProviderName(
            FrameGenerationCompatibilityLayer.ProviderId provider
    ) {
        return provider == FrameGenerationCompatibilityLayer.ProviderId.AMD_FSR31_VULKAN
                ? "FSR 3.1 Vulkan Provider"
                : "FSR Vulkan Provider";
    }
}
