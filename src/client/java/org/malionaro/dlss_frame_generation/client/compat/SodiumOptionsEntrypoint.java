package org.malionaro.dlss_frame_generation.client.compat;

import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.StorageEventHandler;
import net.caffeinemc.mods.sodium.api.config.option.OptionImpact;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.OptionGroupBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.malionaro.dlss_frame_generation.client.FrameGenMod;
import org.malionaro.dlss_frame_generation.client.StreamlineBridge;
import org.malionaro.dlss_frame_generation.client.backend.FrameGenerationBackend;
import org.malionaro.dlss_frame_generation.client.backend.FrameGenerationBackend.GpuVendor;
import org.malionaro.dlss_frame_generation.client.config.FrameGenConfig;
import org.malionaro.dlss_frame_generation.client.config.FrameGenConfig.RayTracingMode;
import org.malionaro.dlss_frame_generation.client.config.FrameGenConfig.RayTracingQuality;
import org.malionaro.dlss_frame_generation.client.ui.settings.MultiVendorSettingsScreen;

/**
 * Adds this mod's own pages to Sodium's video settings through Sodium's public
 * config API ({@code sodium:config_api_user} entrypoint). Only loaded when
 * Sodium calls the entrypoint, so the mod still runs without Sodium.
 *
 * <p>Every option writes {@link FrameGenConfig}; Sodium's apply button then
 * runs {@link #STORAGE}, which persists the config and pushes it through the
 * same single native path the in-game screen uses
 * ({@link FrameGenerationBackend#applyActiveConfig}). Vendor-specific options
 * resolve the active GPU when they are read or written, because Sodium builds
 * this structure before the Vulkan device exists. Features the hardware does
 * not support are still gated at runtime exactly as before.</p>
 */
public final class SodiumOptionsEntrypoint implements ConfigEntryPoint {
    private static final String NAMESPACE = "dlss_fg";

    /** Upscaler mode as stored in the config (0..6), named per vendor. */
    public enum UpscalerMode {
        OFF, NATIVE, QUALITY, BALANCED, PERFORMANCE, ULTRA_PERFORMANCE, ULTRA_QUALITY;

        static UpscalerMode fromConfig(int mode) {
            UpscalerMode[] values = values();
            return mode >= 0 && mode < values.length ? values[mode] : OFF;
        }
    }

    public enum ReflexMode { OFF, ON, BOOST }

    private static final StorageEventHandler STORAGE = () -> {
        FrameGenConfig config = FrameGenConfig.getInstance();
        config.save();
        FrameGenerationBackend.applyActiveConfig(config);
        if (FrameGenMod.rayTracingManager() != null) {
            FrameGenMod.rayTracingManager().applyConfiguration(config);
        }
    };

    @Override
    public void registerConfigLate(ConfigBuilder builder) {
        FrameGenConfig config = FrameGenConfig.getInstance();

        builder.registerOwnModOptions()
                .setName("Frame Generation")
                // Same tabs as the in-game screen: Frame Generation,
                // Upscaling, Graphics.
                .addPage(builder.createOptionPage()
                        .setName(Component.translatable("dlss_fg.section.frame_generation"))
                        .addOptionGroup(frameGenerationGroup(builder, config))
                        .addOptionGroup(displayGroup(builder, config)))
                .addPage(builder.createOptionPage()
                        .setName(Component.translatable("dlss_fg.tab.upscaling"))
                        .addOptionGroup(upscalingGroup(builder, config))
                        .addOptionGroup(effectsGroup(builder, config)))
                .addPage(builder.createOptionPage()
                        .setName(Component.translatable("dlss_fg.section.graphics"))
                        .addOptionGroup(rayTracingGroup(builder, config))
                        .addOptionGroup(builder.createOptionGroup()
                                .addOption(builder.createExternalButtonOption(id("full_settings"))
                                        .setName(Component.translatable("dlss_fg.option.sodium.full_settings"))
                                        .setTooltip(Component.translatable("dlss_fg.tooltip.sodium.full_settings"))
                                        .setScreenConsumer(parent -> Minecraft.getInstance()
                                                .setScreenAndShow(new MultiVendorSettingsScreen(parent))))));
    }

    private static OptionGroupBuilder frameGenerationGroup(ConfigBuilder builder, FrameGenConfig config) {
        return builder.createOptionGroup()
                .setName(Component.translatable("dlss_fg.section.frame_generation"))
                .addOption(builder.createBooleanOption(id("frame_generation"))
                        .setName(Component.translatable("dlss_fg.option.sodium.frame_generation"))
                        .setTooltip(Component.translatable("dlss_fg.tooltip.sodium.frame_generation"))
                        .setImpact(OptionImpact.VARIES)
                        .setStorageHandler(STORAGE)
                        .setDefaultValue(false)
                        .setBinding(enabled -> {
                            GpuVendor vendor = activeVendor();
                            config.setFrameGenerationEnabled(vendor, enabled);
                            // DLSS-G requires Reflex; mirror the in-game screen.
                            if (enabled && vendor == GpuVendor.NVIDIA && config.reflexMode == 0) {
                                config.reflexMode = 1;
                            }
                        }, () -> config.isFrameGenerationEnabled(activeVendor())))
                .addOption(builder.createIntegerOption(id("fg_multiplier"))
                        .setName(Component.translatable("dlss_fg.option.nvidia.fg_multiplier"))
                        .setTooltip(Component.translatable("dlss_fg.tooltip.nvidia.fg_multiplier"))
                        .setImpact(OptionImpact.MEDIUM)
                        .setStorageHandler(STORAGE)
                        .setRange(2, FrameGenConfig.MAX_FRAME_MULTIPLIER, 1)
                        .setDefaultValue(2)
                        .setValueFormatter(value -> Component.translatable("dlss_fg.value.multiplier", value))
                        .setBinding(value -> {
                            // FSR 3.1 inserts exactly one frame; only DLSS-G scales.
                            if (activeVendor() == GpuVendor.NVIDIA) {
                                config.frameMultiplier = Math.min(value, nvidiaMaxMultiplier());
                            }
                        }, () -> activeVendor() == GpuVendor.NVIDIA
                                ? Math.max(2, Math.min(config.frameMultiplier, FrameGenConfig.MAX_FRAME_MULTIPLIER))
                                : 2))
                .addOption(builder.createEnumOption(id("reflex"), ReflexMode.class)
                        .setName(Component.translatable("dlss_fg.option.nvidia.reflex"))
                        .setTooltip(Component.translatable("dlss_fg.tooltip.nvidia.reflex"))
                        .setImpact(OptionImpact.LOW)
                        .setStorageHandler(STORAGE)
                        .setDefaultValue(ReflexMode.ON)
                        .setElementNameProvider(mode -> Component.translatable(switch (mode) {
                            case OFF -> "dlss_fg.value.off";
                            case ON -> "dlss_fg.value.on";
                            case BOOST -> "dlss_fg.value.on_boost";
                        }))
                        .setBinding(mode -> {
                            config.reflexMode = mode.ordinal();
                            if (mode == ReflexMode.OFF && config.dlssFgEnabled) {
                                config.setFrameGenerationEnabled(GpuVendor.NVIDIA, false);
                            }
                        }, () -> ReflexMode.values()[Math.max(0, Math.min(config.reflexMode, 2))]))
                .addOption(builder.createBooleanOption(id("reflex_pacing"))
                        .setName(Component.translatable("dlss_fg.option.nvidia.reflex_pacing"))
                        .setTooltip(Component.translatable("dlss_fg.tooltip.nvidia.reflex_pacing"))
                        .setImpact(OptionImpact.LOW)
                        .setStorageHandler(STORAGE)
                        .setDefaultValue(true)
                        .setBinding(value -> config.reflexPacingEnabled = value,
                                () -> config.reflexPacingEnabled));
    }

    private static OptionGroupBuilder upscalingGroup(ConfigBuilder builder, FrameGenConfig config) {
        return builder.createOptionGroup()
                .setName(Component.translatable("dlss_fg.section.sodium.upscaling"))
                .addOption(builder.createEnumOption(id("upscaler"), UpscalerMode.class)
                        .setName(Component.translatable("dlss_fg.option.sodium.upscaler"))
                        .setTooltip(Component.translatable("dlss_fg.tooltip.sodium.upscaler"))
                        .setImpact(OptionImpact.HIGH)
                        .setStorageHandler(STORAGE)
                        .setDefaultValue(UpscalerMode.OFF)
                        .setElementNameProvider(SodiumOptionsEntrypoint::upscalerName)
                        .setAllowedValuesProvider(state -> activeVendor() == GpuVendor.NVIDIA
                                ? java.util.EnumSet.allOf(UpscalerMode.class)
                                : java.util.EnumSet.of(UpscalerMode.OFF, UpscalerMode.NATIVE))
                        .setBinding(mode -> {
                            GpuVendor vendor = activeVendor();
                            int value = mode.ordinal();
                            // The AMD/Intel Vulkan routes only implement Off and
                            // Native AA; FrameGenConfig.sanitize resets anything
                            // higher to Off on the next load.
                            if (vendor != GpuVendor.NVIDIA) {
                                value = Math.min(value, 1);
                            }
                            config.setUpscalerMode(vendor, value);
                            if (vendor == GpuVendor.NVIDIA) {
                                config.projectionJitterEnabled = value != 0;
                                if (value < 2) {
                                    // Ray Reconstruction needs a reduced HDR input.
                                    config.dlssRayReconstructionEnabled = false;
                                }
                            }
                        }, () -> UpscalerMode.fromConfig(upscalerModeOf(config, activeVendor()))))
                .addOption(builder.createBooleanOption(id("drs"))
                        .setName(Component.translatable("dlss_fg.option.drs"))
                        .setTooltip(Component.translatable("dlss_fg.tooltip.drs"))
                        .setImpact(OptionImpact.VARIES)
                        .setStorageHandler(STORAGE)
                        .setDefaultValue(false)
                        .setBinding(value -> config.drsEnabled = value, () -> config.drsEnabled))
                .addOption(builder.createIntegerOption(id("drs_target"))
                        .setName(Component.translatable("dlss_fg.option.sodium.drs_target"))
                        .setTooltip(Component.translatable("dlss_fg.tooltip.drs_target"))
                        .setImpact(OptionImpact.LOW)
                        .setStorageHandler(STORAGE)
                        .setRange(30, 360, 6)
                        .setDefaultValue(144)
                        .setValueFormatter(value -> Component.literal(value + " FPS"))
                        .setBinding(value -> config.targetFps = value, () -> config.targetFps));
    }

    private static OptionGroupBuilder displayGroup(ConfigBuilder builder, FrameGenConfig config) {
        return builder.createOptionGroup()
                .setName(Component.translatable("dlss_fg.section.sodium.display"))
                .addOption(builder.createBooleanOption(id("ui_recomposition"))
                        .setName(Component.translatable("dlss_fg.option.ui_recomposition"))
                        .setTooltip(Component.translatable("dlss_fg.tooltip.ui_recomposition"))
                        .setImpact(OptionImpact.LOW)
                        .setStorageHandler(STORAGE)
                        .setDefaultValue(true)
                        .setBinding(value -> config.uiRecomposition = value, () -> config.uiRecomposition))
                .addOption(builder.createBooleanOption(id("osd"))
                        .setName(Component.translatable("dlss_fg.option.performance_osd"))
                        .setTooltip(Component.translatable("dlss_fg.tooltip.performance_osd"))
                        .setImpact(OptionImpact.LOW)
                        .setStorageHandler(STORAGE)
                        .setDefaultValue(false)
                        .setBinding(value -> config.osdEnabled = value, () -> config.osdEnabled));
    }

    private static OptionGroupBuilder rayTracingGroup(ConfigBuilder builder, FrameGenConfig config) {
        return builder.createOptionGroup()
                .setName(Component.translatable("dlss_fg.tab.ray_tracing"))
                .addOption(builder.createBooleanOption(id("shader_pack_auto_boost"))
                        .setName(Component.translatable("dlss_fg.option.shader_pack_auto_boost"))
                        .setTooltip(Component.translatable("dlss_fg.tooltip.shader_pack_auto_boost"))
                        .setImpact(OptionImpact.LOW)
                        .setStorageHandler(STORAGE)
                        .setDefaultValue(true)
                        .setBinding(value -> config.shaderPackAutoBoost = value,
                                () -> config.shaderPackAutoBoost))
                .addOption(builder.createEnumOption(id("rt_mode"), RayTracingMode.class)
                        .setEnabledProvider(state -> !ForeignShaderPipeline.active())
                        .setName(Component.translatable("dlss_fg.option.rt.mode"))
                        .setTooltip(Component.translatable("dlss_fg.tooltip.rt.mode"))
                        .setImpact(OptionImpact.HIGH)
                        .setStorageHandler(STORAGE)
                        .setDefaultValue(RayTracingMode.OFF)
                        .setElementNameProvider(mode -> Component.translatable(
                                "dlss_fg.value.rt_mode." + mode.name().toLowerCase(java.util.Locale.ROOT)))
                        .setBinding(mode -> config.rayTracingMode = mode, () -> config.rayTracingMode))
                .addOption(builder.createEnumOption(id("rt_quality"), RayTracingQuality.class)
                        .setEnabledProvider(state -> !ForeignShaderPipeline.active())
                        .setName(Component.translatable("dlss_fg.option.rt.quality"))
                        .setTooltip(Component.translatable("dlss_fg.tooltip.rt.quality"))
                        .setImpact(OptionImpact.HIGH)
                        .setStorageHandler(STORAGE)
                        .setDefaultValue(RayTracingQuality.MEDIUM)
                        .setElementNameProvider(quality -> Component.translatable(
                                "dlss_fg.value.rt_quality." + quality.name().toLowerCase(java.util.Locale.ROOT)))
                        .setBinding(quality -> config.rayTracingQuality = quality,
                                () -> config.rayTracingQuality))
                .addOption(builder.createIntegerOption(id("rt_distance"))
                        .setEnabledProvider(state -> !ForeignShaderPipeline.active())
                        .setName(Component.translatable("dlss_fg.option.rt.distance"))
                        .setTooltip(Component.translatable("dlss_fg.tooltip.rt.distance"))
                        .setImpact(OptionImpact.HIGH)
                        .setStorageHandler(STORAGE)
                        .setRange(FrameGenConfig.MIN_RAY_TRACING_DISTANCE,
                                FrameGenConfig.MAX_RAY_TRACING_DISTANCE, 4)
                        .setDefaultValue(FrameGenConfig.MAX_RAY_TRACING_DISTANCE)
                        .setValueFormatter(value -> Component.translatable("dlss_fg.value.blocks", value))
                        .setBinding(value -> config.rayTracingMaxDistance = value,
                                () -> config.rayTracingMaxDistance))
                .addOption(builder.createIntegerOption(id("rt_samples"))
                        .setEnabledProvider(state -> !ForeignShaderPipeline.active())
                        .setName(Component.translatable("dlss_fg.option.rt.samples"))
                        .setTooltip(Component.translatable("dlss_fg.tooltip.rt.samples"))
                        .setImpact(OptionImpact.HIGH)
                        .setStorageHandler(STORAGE)
                        .setRange(1, 8, 1)
                        .setDefaultValue(1)
                        .setValueFormatter(value -> Component.translatable(value == 1
                                ? "dlss_fg.value.ray_per_pixel"
                                : "dlss_fg.value.rays_per_pixel", value))
                        .setBinding(value -> config.rayTracingRaysPerPixel = value,
                                () -> config.rayTracingRaysPerPixel))
                .addOption(builder.createBooleanOption(id("ray_reconstruction"))
                        .setEnabledProvider(state -> !ForeignShaderPipeline.active())
                        .setName(Component.translatable("dlss_fg.option.rt.ray_reconstruction"))
                        .setTooltip(Component.translatable("dlss_fg.tooltip.rt.ray_reconstruction"))
                        .setImpact(OptionImpact.MEDIUM)
                        .setStorageHandler(STORAGE)
                        .setDefaultValue(false)
                        .setBinding(value -> config.dlssRayReconstructionEnabled = value,
                                () -> config.dlssRayReconstructionEnabled));
    }

    private static OptionGroupBuilder effectsGroup(ConfigBuilder builder, FrameGenConfig config) {
        return builder.createOptionGroup()
                .setName(Component.translatable("dlss_fg.section.sodium.effects"))
                .addOption(builder.createBooleanOption(id("motion_blur"))
                        .setName(Component.translatable("dlss_fg.option.motion_blur"))
                        .setTooltip(Component.translatable("dlss_fg.tooltip.motion_blur"))
                        .setImpact(OptionImpact.MEDIUM)
                        .setStorageHandler(STORAGE)
                        .setDefaultValue(false)
                        .setBinding(value -> config.motionBlurEnabled = value,
                                () -> config.motionBlurEnabled))
                .addOption(builder.createIntegerOption(id("motion_blur_strength"))
                        .setName(Component.translatable("dlss_fg.option.sodium.motion_blur_strength"))
                        .setTooltip(Component.translatable("dlss_fg.tooltip.motion_blur_intensity"))
                        .setImpact(OptionImpact.LOW)
                        .setStorageHandler(STORAGE)
                        .setRange(0, 100, 5)
                        .setDefaultValue(50)
                        .setValueFormatter(value -> Component.literal(value + "%"))
                        .setBinding(value -> config.motionBlurIntensity = value / 100.0f,
                                () -> Math.round(config.motionBlurIntensity * 100.0f)));
    }

    private static Component upscalerName(UpscalerMode mode) {
        boolean nvidia = activeVendor() == GpuVendor.NVIDIA;
        return Component.translatable(switch (mode) {
            case OFF -> "dlss_fg.value.off";
            case NATIVE -> nvidia ? "dlss_fg.value.dlaa" : "dlss_fg.value.native_aa";
            case QUALITY -> "dlss_fg.value.quality";
            case BALANCED -> "dlss_fg.value.balanced";
            case PERFORMANCE -> "dlss_fg.value.performance";
            case ULTRA_PERFORMANCE -> "dlss_fg.value.ultra_performance";
            case ULTRA_QUALITY -> "dlss_fg.value.ultra_quality";
        });
    }

    private static int upscalerModeOf(FrameGenConfig config, GpuVendor vendor) {
        return switch (vendor) {
            case NVIDIA -> config.dlssUpscalingMode;
            case AMD -> config.amdFsrMode;
            case INTEL -> config.intelXessMode;
            case UNKNOWN -> 0;
        };
    }

    /** The active GPU vendor; NVIDIA until the Vulkan device is known. */
    private static GpuVendor activeVendor() {
        CompatibilityManager compatibility = FrameGenMod.compatibilityManager();
        FrameGenerationBackend backend = compatibility != null
                ? compatibility.activeVendorBackend()
                : null;
        GpuVendor vendor = backend != null ? backend.vendor() : GpuVendor.UNKNOWN;
        return vendor == GpuVendor.UNKNOWN ? GpuVendor.NVIDIA : vendor;
    }

    private static int nvidiaMaxMultiplier() {
        try {
            return Math.max(2, Math.min(FrameGenConfig.MAX_FRAME_MULTIPLIER,
                    StreamlineBridge.nGetMaxFrameMultiplier(GpuVendor.NVIDIA.vendorId())));
        } catch (UnsatisfiedLinkError ignored) {
            return 2;
        }
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(NAMESPACE, path);
    }
}
