package org.malionaro.dlss_frame_generation.client.ui.settings;
import org.malionaro.dlss_frame_generation.client.backend.FrameGenerationBackend;
import org.malionaro.dlss_frame_generation.client.backend.FrameGenerationCompatibilityLayer;
import org.malionaro.dlss_frame_generation.client.config.FrameGenConfig;
import org.malionaro.dlss_frame_generation.client.StreamlineBridge;

import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.network.chat.Component;

public final class AmdSettingsPanel {
    private static final FrameGenerationBackend.GpuVendor AMD =
            FrameGenerationBackend.GpuVendor.AMD;

    private AmdSettingsPanel() {}

    /** Frame Generation tab: FSR 3.1 FG, fixed 2x, UI layer, proxy status. */
    public static void addFrameGeneration(MultiVendorSettingsScreen screen, FrameGenConfig config, int capabilities) {
        int columnWidth = screen.settingsColumnWidth();
        boolean amdSupported = (capabilities & StreamlineBridge.CAP_FRAME_GENERATION) != 0;
        boolean proxyReady = (capabilities & StreamlineBridge.CAP_PROXY_SWAPCHAIN) != 0;
        boolean frameGenerationConflict =
                config.conflictingFrameGenerationVendor(AMD)
                        != FrameGenerationBackend.GpuVendor.UNKNOWN;
        boolean fgUsable = amdSupported
                && !frameGenerationConflict
                && !config.hybridD3D12PresentationEnabled;
        FrameGenerationCompatibilityLayer.Route route =
                FrameGenerationCompatibilityLayer.queryVulkanRoute(AMD);

        // The current AMD SDK exposes FSR 4 frame generation only for D3D12.
        // Minecraft's Vulkan renderer therefore uses the FSR 3.1 provider.
        var btnFgFsr = CycleButton.onOffBuilder(config.amdFsrFgEnabled)
                .create(0, 0, columnWidth, 20,
                        Component.translatable("dlss_fg.option.amd.frame_generation"), (button, value) -> {
                            config.setFrameGenerationEnabled(AMD, value);
                            config.save();
                            // Native push only via backend.
                            FrameGenerationBackend.applyActiveConfig(config);
                            screen.refreshSettingsWidgets();
                        });
        btnFgFsr.active = fgUsable;
        screen.place(btnFgFsr, screen.frameGenerationTooltip(
                AMD, "dlss_fg.tooltip.amd.frame_generation"));

        // FSR 3.1 inserts exactly one interpolated frame: the slider sits
        // locked at 2x so it reads the same as the NVIDIA multiplier.
        screen.place(screen.fixedMultiplierSlider(),
                Component.translatable("dlss_fg.tooltip.frame_multiplier_fixed"));

        screen.addUiRecomposition(config, AMD, fgUsable && config.amdFsrFgEnabled);

        var proxyStatus = CycleButton.onOffBuilder(proxyReady)
                .create(0, 0, columnWidth, 20,
                        Component.translatable(
                                route.provider()
                                        == FrameGenerationCompatibilityLayer.ProviderId.AMD_FSR31_VULKAN
                                        ? "dlss_fg.status.provider.fsr31"
                                        : "dlss_fg.status.provider.fsr"
                        ), (button, value) -> {});
        proxyStatus.active = false;
        screen.place(proxyStatus, Component.translatable("dlss_fg.tooltip.provider_status"));
    }

    /** Upscaling tab: FSR mode (Off / Native AA) and sharpness. */
    public static void addUpscaling(MultiVendorSettingsScreen screen, FrameGenConfig config, int capabilities) {
        int columnWidth = screen.settingsColumnWidth();
        boolean upscalingSupported = (capabilities & StreamlineBridge.CAP_UPSCALING) != 0;

        // Only 0=Off and 1=Native-AA are real Vulkan modes (sanitize maps
        // anything higher to Off), so no higher values are offered.
        var btnFsrMode = CycleButton.builder((Integer value) -> switch (value) {
                    case 0 -> Component.translatable("dlss_fg.value.off");
                    case 1 -> Component.translatable("dlss_fg.value.native_aa");
                    default -> Component.translatable("dlss_fg.value.unknown");
                }, Math.min(config.amdFsrMode, 1))
                .withValues(0, 1)
                .create(0, 0, columnWidth, 20,
                        Component.translatable("dlss_fg.option.amd.upscaling"), (button, value) -> {
                            config.setUpscalerMode(AMD, value);
                            config.save();
                            // Native push only via backend.
                            FrameGenerationBackend.applyActiveConfig(config);
                        });
        btnFsrMode.active = upscalingSupported;
        screen.place(btnFsrMode, screen.capabilityTooltip(
                AMD, capabilities, StreamlineBridge.CAP_UPSCALING,
                "dlss_fg.tooltip.amd.upscaling"));

        var sharpness = screen.sharpnessSlider(
                () -> config.amdFsrSharpness,
                value -> config.amdFsrSharpness = value);
        sharpness.active = upscalingSupported;
        screen.place(sharpness, screen.capabilityTooltip(
                AMD, capabilities, StreamlineBridge.CAP_UPSCALING,
                "dlss_fg.tooltip.aa_sharpness"));
    }
}
