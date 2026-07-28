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
                        (FrameGenConfig.RayTracingMode mode) -> Component.literal(mode.displayName()),
                        config.rayTracingMode)
                .withValues(
                        FrameGenConfig.RayTracingMode.OFF,
                        FrameGenConfig.RayTracingMode.SHADOWS,
                        FrameGenConfig.RayTracingMode.SHADOWS_AND_REFLECTIONS)
                .create(leftX, topY, columnWidth, 20,
                        Component.literal("Hardware Raytracing"), (button, value) -> {
                            config.rayTracingMode = value;
                            config.save();
                            if (FrameGenMod.rayTracingManager() != null) {
                                FrameGenMod.rayTracingManager().applyConfiguration(config);
                            }
                            if (runtimeStatusButton[0] != null) {
                                runtimeStatusButton[0].setMessage(
                                        Component.literal(runtimeLabel(config))
                                );
                            }
                        });
        modeButton.active = backendReady;
        screen.addSettingsWidget(modeButton);

        var qualityButton = CycleButton.builder(
                        (FrameGenConfig.RayTracingQuality quality) -> Component.literal(quality.displayName()),
                        config.rayTracingQuality)
                .withValues(
                        FrameGenConfig.RayTracingQuality.LOW,
                        FrameGenConfig.RayTracingQuality.MEDIUM,
                        FrameGenConfig.RayTracingQuality.HIGH,
                        FrameGenConfig.RayTracingQuality.ULTRA)
                .create(rightX, topY, columnWidth, 20,
                        Component.literal("RT Quality"), (button, value) -> {
                            config.rayTracingQuality = value;
                            config.save();
                            applyAndRefresh(config, runtimeStatusButton);
                        });
        qualityButton.active = backendReady;
        screen.addSettingsWidget(qualityButton);

        var distanceButton = CycleButton.builder(
                        (Integer value) -> Component.literal(value + " blocks"),
                        config.rayTracingMaxDistance)
                .withValues(24, 32, 40, 48)
                .create(leftX, topY + 25, columnWidth, 20,
                        Component.literal("RT Distance"), (button, value) -> {
                            config.rayTracingMaxDistance = value;
                            config.save();
                            applyAndRefresh(config, runtimeStatusButton);
                        });
        distanceButton.active = backendReady;
        screen.addSettingsWidget(distanceButton);

        var raysButton = CycleButton.builder(
                        (Integer value) -> Component.literal(value + (value == 1 ? " ray/pixel" : " rays/pixel")),
                        config.rayTracingRaysPerPixel)
                .withValues(1, 2, 4, 8)
                .create(rightX, topY + 25, columnWidth, 20,
                        Component.literal("RT Samples"), (button, value) -> {
                            config.rayTracingRaysPerPixel = value;
                            config.save();
                            applyAndRefresh(config, runtimeStatusButton);
                        });
        raysButton.active = backendReady;
        screen.addSettingsWidget(raysButton);

        var denoiserButton = CycleButton.onOffBuilder(config.rayTracingDenoiserEnabled)
                .create(leftX, topY + 50, columnWidth, 20,
                        Component.literal("RT Stable Sampling"), (button, value) -> {
                            config.rayTracingDenoiserEnabled = value;
                            config.save();
                            applyAndRefresh(config, runtimeStatusButton);
                        });
        denoiserButton.active = backendReady;
        screen.addSettingsWidget(denoiserButton);

        Button backendButton = Button.builder(
                        Component.literal(runtimeLabel(config)),
                        button -> {})
                .bounds(rightX, topY + 50, columnWidth, 20)
                .build();
        runtimeStatusButton[0] = backendButton;
        backendButton.active = false;
        screen.addSettingsWidget(backendButton);

        var rayReconstructionButton = CycleButton.onOffBuilder(config.dlssRayReconstructionEnabled)
                .create(leftX, topY + 75, columnWidth, 20,
                        Component.literal("DLSS Ray Reconstruction"), (button, value) -> {
                            config.dlssRayReconstructionEnabled = value;
                            if (value && config.dlssUpscalingMode < 2) {
                                config.dlssUpscalingMode = 2;
                                config.projectionJitterEnabled = true;
                            }
                            config.save();
                            applyAndRefresh(config, runtimeStatusButton);
                        });
        rayReconstructionButton.active = rayReconstructionSupported;
        screen.addSettingsWidget(rayReconstructionButton);

        Button pipelineButton = Button.builder(
                        Component.literal(capabilities.rayTracingPipelineSupported()
                                ? "RT Pipeline: detected"
                                : "RT Pipeline: unavailable"),
                        button -> {})
                .bounds(rightX, topY + 75, columnWidth, 20)
                .build();
        pipelineButton.active = false;
        screen.addSettingsWidget(pipelineButton);

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
            runtimeStatusButton[0].setMessage(
                    Component.literal(runtimeLabel(config))
            );
        }
    }

    private static String runtimeLabel(FrameGenConfig config) {
        if (config.rayTracingMode == FrameGenConfig.RayTracingMode.OFF) {
            return "RT Runtime: off";
        }
        if (FrameGenMod.rayTracingManager() == null) {
            return "RT Runtime: unavailable";
        }
        String status = FrameGenMod.rayTracingManager().runtimeStatus();
        if (status.contains("failed") || status.contains("VkResult")) {
            return "RT Runtime: failed (see log)";
        }
        if (status.contains("active")) {
            return "RT Runtime: active";
        }
        return "RT Runtime: waiting";
    }
}
