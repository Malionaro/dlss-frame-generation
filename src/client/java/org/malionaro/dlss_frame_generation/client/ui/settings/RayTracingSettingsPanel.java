package org.malionaro.dlss_frame_generation.client.ui.settings;
import org.malionaro.dlss_frame_generation.client.backend.FrameGenerationBackend;
import org.malionaro.dlss_frame_generation.client.compat.ForeignShaderPipeline;
import org.malionaro.dlss_frame_generation.client.config.FrameGenConfig;
import org.malionaro.dlss_frame_generation.client.FrameGenMod;
import org.malionaro.dlss_frame_generation.client.StreamlineBridge;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.network.chat.Component;
import org.malionaro.dlss_frame_generation.client.raytracing.RayTracingCapabilities;

/**
 * Capability-gated settings for the hybrid hardware ray-query renderer.
 * Locked while a shader pack renders the world: the pack lights the scene
 * itself and our RT pass stays off.
 */
public final class RayTracingSettingsPanel {
    private RayTracingSettingsPanel() {}

    public static void addWidgets(
            MultiVendorSettingsScreen screen,
            FrameGenConfig config,
            RayTracingCapabilities capabilities
    ) {
        int columnWidth = screen.settingsColumnWidth();
        boolean shaderPack = ForeignShaderPipeline.active();
        boolean usable = capabilities.backendReady() && !shaderPack;
        boolean rayReconstructionSupported = false;
        if (usable
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
                        shaderPack ? FrameGenConfig.RayTracingMode.OFF : config.rayTracingMode)
                .withValues(
                        FrameGenConfig.RayTracingMode.OFF,
                        FrameGenConfig.RayTracingMode.SHADOWS,
                        FrameGenConfig.RayTracingMode.SHADOWS_AND_REFLECTIONS)
                .create(0, 0, columnWidth, 20,
                        Component.translatable("dlss_fg.option.rt.mode"), (button, value) -> {
                            boolean rrBefore = config.dlssRayReconstructionEnabled;
                            config.setRayTracingMode(value, rayReconstructionSupportedNow());
                            config.save();
                            applyAndRefresh(config, runtimeStatusButton);
                            if (rrBefore != config.dlssRayReconstructionEnabled) {
                                // Show the RR switch and DLSS mode it just changed.
                                FrameGenerationBackend.applyActiveConfig(config);
                                screen.refreshSettingsWidgets();
                            }
                        });
        modeButton.active = usable;
        screen.place(modeButton, screen.rayTracingTooltip(
                capabilities, "dlss_fg.tooltip.rt.mode"));

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
                .create(0, 0, columnWidth, 20,
                        Component.translatable("dlss_fg.option.rt.quality"), (button, value) -> {
                            config.rayTracingQuality = value;
                            config.save();
                            applyAndRefresh(config, runtimeStatusButton);
                        });
        qualityButton.active = usable;
        screen.place(qualityButton, screen.rayTracingTooltip(
                capabilities, "dlss_fg.tooltip.rt.quality"));

        var distance = new SteppedSlider(
                columnWidth,
                FrameGenConfig.MIN_RAY_TRACING_DISTANCE,
                FrameGenConfig.MAX_RAY_TRACING_DISTANCE,
                4,
                config.rayTracingMaxDistance,
                value -> Component.translatable("dlss_fg.option.rt.distance_slider", value),
                value -> {
                    config.rayTracingMaxDistance = value;
                    config.saveSoon();
                },
                value -> {
                    config.save();
                    applyAndRefresh(config, runtimeStatusButton);
                });
        distance.active = usable;
        screen.place(distance, screen.rayTracingTooltip(
                capabilities, "dlss_fg.tooltip.rt.distance"));

        var samples = new SteppedSlider(
                columnWidth, 1, 8, 1,
                config.rayTracingRaysPerPixel,
                value -> Component.translatable("dlss_fg.option.rt.samples_slider", value),
                value -> {
                    config.rayTracingRaysPerPixel = value;
                    config.saveSoon();
                },
                value -> {
                    config.save();
                    applyAndRefresh(config, runtimeStatusButton);
                });
        samples.active = usable;
        screen.place(samples, screen.rayTracingTooltip(
                capabilities, "dlss_fg.tooltip.rt.samples"));

        // Our own RT shader packs (rtshaderpacks/); Iris packs are separate.
        java.util.List<String> packs = new java.util.ArrayList<>();
        packs.add(org.malionaro.dlss_frame_generation.client.raytracing.RtShaderPacks.BUILT_IN);
        packs.addAll(org.malionaro.dlss_frame_generation.client.raytracing.RtShaderPacks.available());
        String currentPack = packs.contains(config.rayTracingShaderPack)
                ? config.rayTracingShaderPack
                : org.malionaro.dlss_frame_generation.client.raytracing.RtShaderPacks.BUILT_IN;
        var packButton = CycleButton.builder(
                        (String pack) -> pack.isEmpty()
                                ? Component.translatable("dlss_fg.value.rt_shader_pack.builtin")
                                : Component.literal(pack),
                        currentPack)
                .withValues(packs)
                .create(0, 0, columnWidth, 20,
                        Component.translatable("dlss_fg.option.rt.shader_pack"), (button, value) -> {
                            config.rayTracingShaderPack = value;
                            config.save();
                            if (FrameGenMod.rayTracingManager() != null) {
                                FrameGenMod.rayTracingManager().reloadShaderPack();
                            }
                            applyAndRefresh(config, runtimeStatusButton);
                        });
        packButton.active = usable;
        screen.place(packButton, screen.rayTracingTooltip(
                capabilities, "dlss_fg.tooltip.rt.shader_pack"));

        var denoiserButton = CycleButton.onOffBuilder(config.rayTracingDenoiserEnabled)
                .create(0, 0, columnWidth, 20,
                        Component.translatable("dlss_fg.option.rt.stable_sampling"), (button, value) -> {
                            config.rayTracingDenoiserEnabled = value;
                            config.save();
                            applyAndRefresh(config, runtimeStatusButton);
                        });
        denoiserButton.active = usable;
        screen.place(denoiserButton, screen.rayTracingTooltip(
                capabilities, "dlss_fg.tooltip.rt.stable_sampling"));

        var rayReconstructionButton = CycleButton.onOffBuilder(config.dlssRayReconstructionEnabled)
                .create(0, 0, columnWidth, 20,
                        Component.translatable("dlss_fg.option.rt.ray_reconstruction"), (button, value) -> {
                            config.dlssRayReconstructionEnabled = value;
                            if (value && config.dlssUpscalingMode < 2) {
                                config.setUpscalerMode(
                                        FrameGenerationBackend.GpuVendor.NVIDIA,
                                        2
                                );
                                config.projectionJitterEnabled = true;
                            }
                            config.save();
                            applyAndRefresh(config, runtimeStatusButton);
                        });
        rayReconstructionButton.active = rayReconstructionSupported;
        screen.place(rayReconstructionButton, usable
                ? screen.capabilityTooltip(
                        FrameGenerationBackend.GpuVendor.NVIDIA,
                        rayReconstructionSupported
                                ? StreamlineBridge.CAP_RAY_RECONSTRUCTION
                                : 0,
                        StreamlineBridge.CAP_RAY_RECONSTRUCTION,
                        "dlss_fg.tooltip.rt.ray_reconstruction")
                : screen.rayTracingTooltip(
                        capabilities, "dlss_fg.tooltip.rt.ray_reconstruction"));

        Button backendButton = Button.builder(runtimeLabel(config), button -> {})
                .bounds(0, 0, columnWidth, 20)
                .build();
        runtimeStatusButton[0] = backendButton;
        backendButton.active = false;
        screen.place(backendButton, Component.translatable("dlss_fg.tooltip.rt.runtime_status"));

        Button pipelineButton = Button.builder(
                        Component.translatable(
                                capabilities.rayTracingPipelineSupported()
                                        ? "dlss_fg.status.rt_pipeline.detected"
                                        : "dlss_fg.status.rt_pipeline.unavailable"
                        ),
                        button -> {})
                .bounds(0, 0, columnWidth, 20)
                .build();
        pipelineButton.active = false;
        screen.place(pipelineButton, Component.translatable("dlss_fg.tooltip.rt.pipeline_status"));
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
        if (ForeignShaderPipeline.active()) {
            return Component.translatable("dlss_fg.status.rt_runtime.shader_pack");
        }
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

    public static boolean rayReconstructionSupportedNow() {
        try {
            return (StreamlineBridge.nGetBackendSupportedCapabilities(
                    FrameGenerationBackend.GpuVendor.NVIDIA.vendorId())
                    & StreamlineBridge.CAP_RAY_RECONSTRUCTION) != 0;
        } catch (UnsatisfiedLinkError ignored) {
            return false;
        }
    }
}
