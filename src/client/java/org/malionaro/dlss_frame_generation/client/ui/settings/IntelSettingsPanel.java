package org.malionaro.dlss_frame_generation.client.ui.settings;
import org.malionaro.dlss_frame_generation.client.backend.FrameGenerationBackend;
import org.malionaro.dlss_frame_generation.client.backend.FrameGenerationCompatibilityLayer;
import org.malionaro.dlss_frame_generation.client.config.FrameGenConfig;
import org.malionaro.dlss_frame_generation.client.StreamlineBridge;

import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.network.chat.Component;

public final class IntelSettingsPanel {
    private static final FrameGenerationBackend.GpuVendor INTEL =
            FrameGenerationBackend.GpuVendor.INTEL;

    private IntelSettingsPanel() {}

    /** Frame Generation tab: FSR 3.1 fallback, fixed 2x, UI layer, transport status. */
    public static void addFrameGeneration(MultiVendorSettingsScreen screen, FrameGenConfig config, int capabilities) {
        int columnWidth = screen.settingsColumnWidth();
        boolean fsrFallbackReady =
                (capabilities & StreamlineBridge.CAP_FRAME_GENERATION) != 0;
        boolean fsrProxyReady = (capabilities & StreamlineBridge.CAP_PROXY_SWAPCHAIN) != 0;
        boolean frameGenerationConflict =
                config.conflictingFrameGenerationVendor(INTEL)
                        != FrameGenerationBackend.GpuVendor.UNKNOWN;
        boolean fgUsable = fsrFallbackReady
                && !frameGenerationConflict
                && !config.hybridD3D12PresentationEnabled;
        FrameGenerationCompatibilityLayer.Route route =
                FrameGenerationCompatibilityLayer.queryVulkanRoute(INTEL);

        // True XeSS-FG is D3D12-only. On Minecraft's Vulkan renderer this
        // control activates the explicitly named FSR 3.1 fallback instead.
        var btnFgXeSS = CycleButton.onOffBuilder(config.intelXessFgEnabled)
                .create(0, 0, columnWidth, 20,
                        Component.translatable("dlss_fg.option.intel.frame_generation"), (button, value) -> {
                            config.setFrameGenerationEnabled(INTEL, value);
                            config.save();
                            // D1: native push only via backend.
                            FrameGenerationBackend.applyActiveConfig(config);
                            screen.refreshSettingsWidgets();
                        });
        btnFgXeSS.active = fgUsable;
        screen.place(btnFgXeSS, screen.frameGenerationTooltip(
                INTEL, "dlss_fg.tooltip.intel.frame_generation"));

        // FSR 3.1 inserts one interpolated frame and therefore exposes 2x only.
        screen.place(screen.fixedMultiplierSlider(),
                Component.translatable("dlss_fg.tooltip.frame_multiplier_fixed"));

        screen.addUiRecomposition(config, INTEL, fgUsable && config.intelXessFgEnabled);

        // XeSS-FG cannot consume a Vulkan swapchain. Show the compatible
        // cross-vendor FSR transport separately so it is never mislabeled.
        var fsrFallbackStatus = CycleButton.onOffBuilder(fsrProxyReady)
                .create(0, 0, columnWidth, 20,
                        Component.translatable(route.isFallback()
                                ? "dlss_fg.status.provider.cross_vendor"
                                : "dlss_fg.status.provider.fallback"), (button, value) -> {});
        fsrFallbackStatus.active = false;
        screen.place(fsrFallbackStatus, Component.translatable("dlss_fg.tooltip.provider_status"));
    }

    /** Upscaling tab: XeSS/FSR mode (Off / Native AA) and sharpness. */
    public static void addUpscaling(MultiVendorSettingsScreen screen, FrameGenConfig config, int capabilities) {
        int columnWidth = screen.settingsColumnWidth();
        boolean xessUpscalingSupported =
                (capabilities & StreamlineBridge.CAP_UPSCALING) != 0;

        // OptiScaler consumes the standard FFX Vulkan input on Intel. Only
        // 0=Off and 1=Native-AA are real (C2, see AmdSettingsPanel).
        var btnXessMode = CycleButton.builder((Integer value) -> switch (value) {
                    case 0 -> Component.translatable("dlss_fg.value.off");
                    case 1 -> Component.translatable("dlss_fg.value.native_aa");
                    default -> Component.translatable("dlss_fg.value.unknown");
                }, Math.min(config.intelXessMode, 1))
                .withValues(0, 1)
                .create(0, 0, columnWidth, 20,
                        Component.translatable("dlss_fg.option.intel.upscaling"), (button, value) -> {
                            config.setUpscalerMode(INTEL, value);
                            config.save();
                            // D1: native push only via backend.
                            FrameGenerationBackend.applyActiveConfig(config);
                        });
        btnXessMode.active = xessUpscalingSupported;
        screen.place(btnXessMode, screen.capabilityTooltip(
                INTEL, capabilities, StreamlineBridge.CAP_UPSCALING,
                "dlss_fg.tooltip.intel.upscaling"));

        var sharpness = screen.sharpnessSlider(
                () -> config.intelXessSharpness,
                value -> config.intelXessSharpness = value);
        sharpness.active = xessUpscalingSupported;
        screen.place(sharpness, screen.capabilityTooltip(
                INTEL, capabilities, StreamlineBridge.CAP_UPSCALING,
                "dlss_fg.tooltip.aa_sharpness"));
    }
}
