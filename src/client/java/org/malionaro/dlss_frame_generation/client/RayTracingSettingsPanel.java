package org.malionaro.dlss_frame_generation.client;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.network.chat.Component;
import org.malionaro.dlss_frame_generation.client.raytracing.RayTracingCapabilities;

/**
 * Capability-gated settings for the hybrid hardware ray-query renderer.
 */
public final class RayTracingSettingsPanel {
    private RayTracingSettingsPanel() {}

    public static int addWidgets(
            MultiVendorSettingsScreen screen,
            FrameGenConfig config,
            RayTracingCapabilities capabilities,
            int topY
    ) {
        int leftX = screen.settingsLeftX();
        int rightX = screen.settingsRightX();
        int columnWidth = screen.settingsColumnWidth();
        boolean backendReady = capabilities.backendReady();
        boolean rayReconstructionSupported = false;
        if (backendReady
                && capabilities.vendorId()
                == FrameGenerationBackend.GpuVendor.NVIDIA.vendorId()) {
            try {
                rayReconstructionSupported =
                        (StreamlineBridge.nGetBackendSupportedCapabilities(
                                FrameGenerationBackend.GpuVendor.NVIDIA.vendorId()
                        ) & StreamlineBridge.CAP_RAY_RECONSTRUCTION) != 0;
            } catch (UnsatisfiedLinkError ignored) {
                rayReconstructionSupported = false;
            }
        }
        Button[] runtimeStatusButton = new Button[1];

        var modeButton = CycleButton.builder(
                        (FrameGenConfig.RayTracingMode mode) -> Component.translatable(
                                "dlss_fg.value.rt_mode."
                                        + mode.name().toLowerCase(java.util.Locale.ROOT)
                        ),
                        config.rayTracingMode)
                .withValues(
                        FrameGenConfig.RayTracingMode.OFF,
                        FrameGenConfig.RayTracingMode.SHADOWS,
                        FrameGenConfig.RayTracingMode.SHADOWS_AND_REFLECTIONS)
                .create(leftX, topY, columnWidth, 20,
                        Component.translatable("dlss_fg.option.rt.mode"), (button, value) -> {
                            config.rayTracingMode = value;
                            config.save();
                            if (FrameGenMod.rayTracingManager() != null) {
                                FrameGenMod.rayTracingManager().applyConfiguration(config);
                            }
                            if (runtimeStatusButton[0] != null) {
                                runtimeStatusButton[0].setMessage(runtimeLabel(config));
                            }
                        });
        modeButton.active = backendReady;
        screen.addSettingsWidget(modeButton, "dlss_fg.tooltip.rt.mode");

        var qualityButton = CycleButton.builder(
                        (FrameGenConfig.RayTracingQuality quality) -> Component.translatable(
                                "dlss_fg.value.rt_quality."
                                        + quality.name().toLowerCase(java.util.Locale.ROOT)
                        ),
                        config.rayTracingQuality)
                .withValues(
                        FrameGenConfig.RayTracingQuality.LOW,
                        FrameGenConfig.RayTracingQuality.MEDIUM,
                        FrameGenConfig.RayTracingQuality.HIGH,
                        FrameGenConfig.RayTracingQuality.ULTRA)
                .create(rightX, topY, columnWidth, 20,
                        Component.translatable("dlss_fg.option.rt.quality"), (button, value) -> {
                            config.rayTracingQuality = value;
                            config.save();
                            applyAndRefresh(config, runtimeStatusButton);
                        });
        qualityButton.active = backendReady;
        screen.addSettingsWidget(qualityButton, "dlss_fg.tooltip.rt.quality");

        var distanceButton = CycleButton.builder(
                        (Integer value) -> Component.translatable(
                                "dlss_fg.value.blocks", value
                        ),
                        config.rayTracingMaxDistance)
                .withValues(24, 32, 40, 48)
                .create(leftX, topY + 25, columnWidth, 20,
                        Component.translatable("dlss_fg.option.rt.distance"), (button, value) -> {
                            config.rayTracingMaxDistance = value;
                            config.save();
                            applyAndRefresh(config, runtimeStatusButton);
                        });
        distanceButton.active = backendReady;
        screen.addSettingsWidget(distanceButton, "dlss_fg.tooltip.rt.distance");

        var raysButton = CycleButton.builder(
                        (Integer value) -> Component.translatable(
                                value == 1
                                        ? "dlss_fg.value.ray_per_pixel"
                                        : "dlss_fg.value.rays_per_pixel",
                                value
                        ),
                        config.rayTracingRaysPerPixel)
                .withValues(1, 2, 4, 8)
                .create(rightX, topY + 25, columnWidth, 20,
                        Component.translatable("dlss_fg.option.rt.samples"), (button, value) -> {
                            config.rayTracingRaysPerPixel = value;
                            config.save();
                            applyAndRefresh(config, runtimeStatusButton);
                        });
        raysButton.active = backendReady;
        screen.addSettingsWidget(raysButton, "dlss_fg.tooltip.rt.samples");

        var denoiserButton = CycleButton.onOffBuilder(config.rayTracingDenoiserEnabled)
                .create(leftX, topY + 50, columnWidth, 20,
                        Component.translatable("dlss_fg.option.rt.stable_sampling"), (button, value) -> {
                            config.rayTracingDenoiserEnabled = value;
                            config.save();
                            applyAndRefresh(config, runtimeStatusButton);
                        });
        denoiserButton.active = backendReady;
        screen.addSettingsWidget(denoiserButton, "dlss_fg.tooltip.rt.stable_sampling");

        Button backendButton = Button.builder(
                        runtimeLabel(config),
                        button -> {})
                .bounds(rightX, topY + 50, columnWidth, 20)
                .build();
        runtimeStatusButton[0] = backendButton;
        backendButton.active = false;
        screen.addSettingsWidget(backendButton, "dlss_fg.tooltip.rt.runtime_status");

        var rayReconstructionButton = CycleButton.onOffBuilder(config.dlssRayReconstructionEnabled)
                .create(leftX, topY + 75, columnWidth, 20,
                        Component.translatable("dlss_fg.option.rt.ray_reconstruction"), (button, value) -> {
                            config.dlssRayReconstructionEnabled = value;
                            if (value && config.dlssUpscalingMode < 2) {
                                config.dlssUpscalingMode = 2;
                                config.projectionJitterEnabled = true;
                            }
                            config.save();
                            applyAndRefresh(config, runtimeStatusButton);
                        });
        rayReconstructionButton.active = rayReconstructionSupported;
        screen.addSettingsWidget(
                rayReconstructionButton,
                "dlss_fg.tooltip.rt.ray_reconstruction"
        );

        Button pipelineButton = Button.builder(
                        Component.translatable(
                                capabilities.rayTracingPipelineSupported()
                                        ? "dlss_fg.status.rt_pipeline.detected"
                                        : "dlss_fg.status.rt_pipeline.unavailable"
                        ),
                        button -> {})
                .bounds(rightX, topY + 75, columnWidth, 20)
                .build();
        pipelineButton.active = false;
        screen.addSettingsWidget(pipelineButton, "dlss_fg.tooltip.rt.pipeline_status");

        return topY + 95;
    }

    private static void applyAndRefresh(
            FrameGenConfig config,
            Button[] runtimeStatusButton
    ) {
        if (FrameGenMod.rayTracingManager() != null) {
            FrameGenMod.rayTracingManager().applyConfiguration(config);
        }
        if (runtimeStatusButton[0] != null) {
            runtimeStatusButton[0].setMessage(runtimeLabel(config));
        }
    }

    private static Component runtimeLabel(FrameGenConfig config) {
        if (config.rayTracingMode == FrameGenConfig.RayTracingMode.OFF) {
            return Component.translatable("dlss_fg.status.rt_runtime.off");
        }
        if (FrameGenMod.rayTracingManager() == null) {
            return Component.translatable("dlss_fg.status.rt_runtime.unavailable");
        }
        String status = FrameGenMod.rayTracingManager().runtimeStatus();
        if (status.contains("failed") || status.contains("VkResult")) {
            return Component.translatable("dlss_fg.status.rt_runtime.failed");
        }
        if (status.contains("active")) {
            return Component.translatable("dlss_fg.status.rt_runtime.active");
        }
        return Component.translatable("dlss_fg.status.rt_runtime.waiting");
    }
}
