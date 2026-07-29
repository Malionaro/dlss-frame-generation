package org.malionaro.dlss_frame_generation.client.config;
import org.malionaro.dlss_frame_generation.client.backend.FrameGenerationBackend;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class FrameGenConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("FrameGen_Config");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Path getConfigPath() {
        try {
            if (FabricLoader.getInstance() != null) {
                return FabricLoader.getInstance().getConfigDir().resolve("frame_gen.json");
            }
        } catch (Throwable t) {
            // Fallback for headless unit test runner
        }
        return Path.of("build", "tmp", "frame_gen.json");
    }

    private static FrameGenConfig instance;

    public enum Preset {
        CUSTOM("Custom"),
        ESPORTS("Esports (Low Latency)"),
        CINEMA("Cinema (High Quality)"),
        MAX_PERFORMANCE("Max Performance (4x MFG)");

        private final String displayName;
        Preset(String displayName) { this.displayName = displayName; }
        public String displayName() { return displayName; }
    }

    public enum RayTracingMode {
        OFF("Off"),
        SHADOWS("RT Shadows"),
        SHADOWS_AND_REFLECTIONS("Shadows + Reflections");

        private final String displayName;
        RayTracingMode(String displayName) { this.displayName = displayName; }
        public String displayName() { return displayName; }
    }

    public enum RayTracingQuality {
        LOW("Low"),
        MEDIUM("Medium"),
        HIGH("High"),
        ULTRA("Ultra");

        private final String displayName;
        RayTracingQuality(String displayName) { this.displayName = displayName; }
        public String displayName() { return displayName; }
    }

    public Preset activePreset = Preset.CUSTOM;

    // Common / Global Config
    public int activeVendorId = 0; // 0 = Auto-detect GPU, 0x10DE = NVIDIA, 0x1002 = AMD, 0x8086 = Intel
    public boolean uiRecomposition = true; // Separate UI overlay rendering
    // Enables a session-only degradation chain. When the requested
    // frame-generation feature is known to be unavailable, rendering continues
    // through temporal reconstruction or the native Vulkan presenter.
    public boolean autoFallbackChainEnabled = false;
    // OptiScaler is bundled as a separate GPLv3 work and enabled by default,
    // but the runtime vendor gate loads it only on an actual AMD/Intel device.
    public boolean optiScalerEnabled = true;
    // Optional Windows presentation transport. It replaces Vulkan WSI with
    // shared Vulkan/D3D12 images and DXGI Present, but does not by itself
    // provide FSR 4 or XeSS frame generation.
    public boolean hybridD3D12PresentationEnabled = false;

    public void setHybridD3D12PresentationEnabled(boolean enabled) {
        hybridD3D12PresentationEnabled = enabled;
        if (enabled) {
            // Until a real D3D12 FG runtime owns the DXGI swapchain, no Vulkan
            // FG proxy may compete for presentation ownership.
            setFrameGenerationEnabled(
                    FrameGenerationBackend.GpuVendor.UNKNOWN, false);
        }
    }

    public void applyPreset(Preset preset) {
        this.activePreset = preset;
        switch (preset) {
            case ESPORTS -> {
                this.reflexMode = 2; // Reflex + Boost
                this.reflexPacingEnabled = true;
                this.reflexInputMarkerEnabled = true;
                this.targetFps = 240;
                this.motionBlurEnabled = false;
                setUpscalerMode(
                        FrameGenerationBackend.GpuVendor.NVIDIA, 1
                ); // DLAA at native resolution
                this.projectionJitterEnabled = true;
            }
            case CINEMA -> {
                this.dlaaEnabled = true;
                this.hdrEnabled = true;
                this.motionBlurEnabled = true;
                this.motionBlurIntensity = 0.6f;
                setUpscalerMode(
                        FrameGenerationBackend.GpuVendor.NVIDIA, 2
                ); // Quality
                this.projectionJitterEnabled = true;
            }
            case MAX_PERFORMANCE -> {
                FrameGenerationBackend.GpuVendor selectedVendor =
                        selectedFrameGenerationVendor();
                if (selectedVendor
                        != FrameGenerationBackend.GpuVendor.UNKNOWN) {
                    setFrameGenerationEnabled(selectedVendor, true);
                }
                this.frameMultiplier = 4; // 4x MFG
                this.amdFsrMultiplier = 2;
                this.intelXessMfgMultiplier = 2;
                setUpscalerMode(
                        FrameGenerationBackend.GpuVendor.NVIDIA, 4
                ); // Performance
                this.projectionJitterEnabled = true;
                this.drsEnabled = true;
                this.targetFps = 144;
            }
            case CUSTOM -> {}
        }
        save();
    }

    // Next-Gen Feature 1: Vulkan Motion Vector Driven Motion Blur
    public boolean motionBlurEnabled = false;
    public float motionBlurIntensity = 0.5f;
    public int motionBlurSamples = 8;

    // Next-Gen Feature 2: DLAA/DLSS projection-matrix jitter. The selected
    // Halton sample is injected into Minecraft's world projection before the
    // world render and reported to Streamline in pixel space.
    public boolean dlaaEnabled = false;
    public boolean projectionJitterEnabled = true;

    // Next-Gen Feature 3: Auto-Reflex Latency Pacing
    public boolean reflexPacingEnabled = true;

    // Next-Level Feature 1: Auto-HDR Tone Mapping
    public boolean hdrEnabled = false;

    // Next-Level Feature 2: In-Game Performance Benchmark OSD
    public boolean osdEnabled = false;

    // Next-Level Feature 3: Dynamic Resolution Scaling (DRS) Target Lock
    public boolean drsEnabled = false;
    public int targetFps = 144;

    // Kept for configuration-file compatibility. Streamline's old mouse input
    // sample marker is deprecated; the runtime uses the supported per-frame
    // simulation/render/present markers instead.
    public boolean reflexInputMarkerEnabled = false;
    public boolean dlssRayReconstructionEnabled = false;

    // Hybrid hardware ray tracing (raster frame + Vulkan ray-query pass).
    public RayTracingMode rayTracingMode = RayTracingMode.OFF;
    public RayTracingQuality rayTracingQuality = RayTracingQuality.MEDIUM;
    public int rayTracingMaxDistance = 48;
    public int rayTracingRaysPerPixel = 1;
    // Kept under its original JSON field name for config compatibility. This
    // is stable multi-sampling (minimum four rays), not a temporal denoiser.
    public boolean rayTracingDenoiserEnabled = true;

    // NVIDIA Streamline options. The runtime-reported maximum multiplier is
    // applied before the option is sent to DLSS-G.
    public boolean dlssFgEnabled = true;
    public int reflexMode = 1; // 0 = Off, 1 = Low Latency, 2 = Low Latency + Boost
    // 0 Off, 1 DLAA, 2 Quality, 3 Balanced, 4 Performance,
    // 5 Ultra Performance, 6 Ultra Quality.
    public int dlssUpscalingMode = 0;
    // Retained only to read older config files. Streamline's DLSS sharpness
    // option is deprecated and is no longer exposed or applied.
    public float dlssSharpness = 0.0f;
    public int frameMultiplier = 2; // 2x, 3x or 4x when reported by the driver

    // AMD renderer options. FSR 4.1.1 upscaling and FSR 4.0.1 frame
    // generation are currently D3D12-only in AMD's public SDK. The bundled
    // Vulkan provider is FSR 3.1.
    // Minecraft still renders its combined scene/UI target at display size, so
    // the only valid Vulkan mode is a real 1:1 temporal Native-AA dispatch.
    public int amdFsrMode = 0; // 0 = Off, 1 = Native AA
    public float amdFsrSharpness = 0.5f; // 0.0 to 1.0
    public boolean amdFsrFgEnabled = true;
    public int amdFsrMultiplier = 2; // FSR 3.1 Vulkan supports one generated frame (2x)

    // Intel Vulkan route. XeSS-SR/FG stay disabled until their native modules
    // or a D3D12 renderer exist; the current FG path is an FSR 3.1 fallback.
    public int intelXessMode = 0; // 0 = Off, 1 = cross-vendor Native AA
    public float intelXessSharpness = 0.5f; // 0.0 to 1.0
    public boolean intelXessFgEnabled = true;
    public int intelXessMfgMultiplier = 2; // FSR 3.1 fallback is 2x only

    /**
     * Enables at most one frame-generation provider. Upscaling, ray tracing,
     * the OSD and motion diagnostics remain independent because they do not
     * insert a second presented frame.
     */
    public void setFrameGenerationEnabled(
            FrameGenerationBackend.GpuVendor vendor,
            boolean enabled
    ) {
        if (enabled && hybridD3D12PresentationEnabled) {
            dlssFgEnabled = false;
            amdFsrFgEnabled = false;
            intelXessFgEnabled = false;
            return;
        }
        if (enabled) {
            dlssFgEnabled = vendor == FrameGenerationBackend.GpuVendor.NVIDIA;
            amdFsrFgEnabled = vendor == FrameGenerationBackend.GpuVendor.AMD;
            intelXessFgEnabled =
                    vendor == FrameGenerationBackend.GpuVendor.INTEL;
            return;
        }

        switch (vendor) {
            case NVIDIA -> dlssFgEnabled = false;
            case AMD -> amdFsrFgEnabled = false;
            case INTEL -> intelXessFgEnabled = false;
            case UNKNOWN -> {
                dlssFgEnabled = false;
                amdFsrFgEnabled = false;
                intelXessFgEnabled = false;
            }
        }
    }

    public boolean isFrameGenerationEnabled(
            FrameGenerationBackend.GpuVendor vendor
    ) {
        return switch (vendor) {
            case NVIDIA -> dlssFgEnabled;
            case AMD -> amdFsrFgEnabled;
            case INTEL -> intelXessFgEnabled;
            case UNKNOWN -> false;
        };
    }

    /**
     * Selects one temporal upscaler. A non-zero mode atomically disables the
     * other vendor routes so two reconstruction passes cannot process the same
     * output image.
     */
    public void setUpscalerMode(
            FrameGenerationBackend.GpuVendor vendor,
            int mode
    ) {
        int normalizedMode = Math.max(0, mode);
        if (normalizedMode != 0) {
            dlssUpscalingMode = 0;
            amdFsrMode = 0;
            intelXessMode = 0;
        }
        switch (vendor) {
            case NVIDIA -> dlssUpscalingMode =
                    clamp(normalizedMode, 0, 6);
            case AMD -> amdFsrMode = clamp(normalizedMode, 0, 6);
            case INTEL -> intelXessMode = clamp(normalizedMode, 0, 6);
            case UNKNOWN -> {
                dlssUpscalingMode = 0;
                amdFsrMode = 0;
                intelXessMode = 0;
            }
        }
    }

    public FrameGenerationBackend.GpuVendor selectedUpscalerVendor() {
        FrameGenerationBackend.GpuVendor selected =
                FrameGenerationBackend.GpuVendor.UNKNOWN;
        if (dlssUpscalingMode != 0) {
            selected = FrameGenerationBackend.GpuVendor.NVIDIA;
        }
        if (amdFsrMode != 0) {
            if (selected != FrameGenerationBackend.GpuVendor.UNKNOWN) {
                return FrameGenerationBackend.GpuVendor.UNKNOWN;
            }
            selected = FrameGenerationBackend.GpuVendor.AMD;
        }
        if (intelXessMode != 0) {
            if (selected != FrameGenerationBackend.GpuVendor.UNKNOWN) {
                return FrameGenerationBackend.GpuVendor.UNKNOWN;
            }
            selected = FrameGenerationBackend.GpuVendor.INTEL;
        }
        return selected;
    }

    public FrameGenerationBackend.GpuVendor conflictingFrameGenerationVendor(
            FrameGenerationBackend.GpuVendor candidate
    ) {
        for (FrameGenerationBackend.GpuVendor vendor :
                FrameGenerationBackend.GpuVendor.values()) {
            if (vendor != candidate
                    && vendor != FrameGenerationBackend.GpuVendor.UNKNOWN
                    && isFrameGenerationEnabled(vendor)) {
                return vendor;
            }
        }
        return FrameGenerationBackend.GpuVendor.UNKNOWN;
    }

    public FrameGenerationBackend.GpuVendor selectedFrameGenerationVendor() {
        FrameGenerationBackend.GpuVendor selected =
                FrameGenerationBackend.GpuVendor.UNKNOWN;
        for (FrameGenerationBackend.GpuVendor vendor :
                FrameGenerationBackend.GpuVendor.values()) {
            if (vendor == FrameGenerationBackend.GpuVendor.UNKNOWN
                    || !isFrameGenerationEnabled(vendor)) {
                continue;
            }
            if (selected != FrameGenerationBackend.GpuVendor.UNKNOWN) {
                return FrameGenerationBackend.GpuVendor.UNKNOWN;
            }
            selected = vendor;
        }
        return selected;
    }

    /**
     * Once Vulkan reports the physical-device vendor, discard provider flags
     * that can never be used on that device. The selected provider's existing
     * on/off choice is preserved.
     *
     * @return true when an incompatible saved flag was removed
     */
    public boolean retainFrameGenerationForVendor(
            FrameGenerationBackend.GpuVendor detectedVendor
    ) {
        boolean oldNvidia = dlssFgEnabled;
        boolean oldAmd = amdFsrFgEnabled;
        boolean oldIntel = intelXessFgEnabled;
        int oldDlssMode = dlssUpscalingMode;
        int oldAmdMode = amdFsrMode;
        int oldIntelMode = intelXessMode;

        switch (detectedVendor) {
            case NVIDIA -> {
                amdFsrFgEnabled = false;
                intelXessFgEnabled = false;
                amdFsrMode = 0;
                intelXessMode = 0;
            }
            case AMD -> {
                dlssFgEnabled = false;
                intelXessFgEnabled = false;
                dlssUpscalingMode = 0;
                intelXessMode = 0;
            }
            case INTEL -> {
                dlssFgEnabled = false;
                amdFsrFgEnabled = false;
                dlssUpscalingMode = 0;
                amdFsrMode = 0;
            }
            case UNKNOWN -> {
                dlssFgEnabled = false;
                amdFsrFgEnabled = false;
                intelXessFgEnabled = false;
                dlssUpscalingMode = 0;
                amdFsrMode = 0;
                intelXessMode = 0;
            }
        }

        return oldNvidia != dlssFgEnabled
                || oldAmd != amdFsrFgEnabled
                || oldIntel != intelXessFgEnabled
                || oldDlssMode != dlssUpscalingMode
                || oldAmdMode != amdFsrMode
                || oldIntelMode != intelXessMode;
    }

    public static FrameGenConfig getInstance() {
        if (instance == null) {
            instance = load();
        }
        return instance;
    }

    private static FrameGenConfig load() {
        Path path = getConfigPath();
        if (Files.exists(path)) {
            try (var reader = Files.newBufferedReader(path)) {
                FrameGenConfig config = GSON.fromJson(reader, FrameGenConfig.class);
                if (config != null) {
                    config.sanitize();
                    return config;
                }
            } catch (IOException e) {
                LOGGER.error("Failed to load Frame Generation config, using defaults", e);
            }
        }
        FrameGenConfig defaultConfig = new FrameGenConfig();
        defaultConfig.save();
        return defaultConfig;
    }

    public void sanitize() {
        if (activePreset == null) activePreset = Preset.CUSTOM;
        if (rayTracingMode == null) rayTracingMode = RayTracingMode.OFF;
        if (rayTracingQuality == null) rayTracingQuality = RayTracingQuality.MEDIUM;
        if (activeVendorId != 0
                && activeVendorId != FrameGenerationBackend.GpuVendor.NVIDIA.vendorId()
                && activeVendorId != FrameGenerationBackend.GpuVendor.AMD.vendorId()
                && activeVendorId != FrameGenerationBackend.GpuVendor.INTEL.vendorId()) {
            activeVendorId = 0;
        }
        if (activeVendorId != 0) {
            retainFrameGenerationForVendor(
                    FrameGenerationBackend.GpuVendor.fromVendorId(
                            activeVendorId
                    )
            );
        } else {
            int enabledFrameGenerators = (dlssFgEnabled ? 1 : 0)
                    + (amdFsrFgEnabled ? 1 : 0)
                    + (intelXessFgEnabled ? 1 : 0);
            if (enabledFrameGenerators > 1) {
                // An auto-detected device cannot safely pick between multiple
                // saved presentation owners before VkPhysicalDevice exists.
                setFrameGenerationEnabled(
                        FrameGenerationBackend.GpuVendor.UNKNOWN,
                        false
                );
            }
        }
        if (hybridD3D12PresentationEnabled) {
            setFrameGenerationEnabled(
                    FrameGenerationBackend.GpuVendor.UNKNOWN, false);
        }
        reflexMode = clamp(reflexMode, 0, 2);
        dlssUpscalingMode = clamp(dlssUpscalingMode, 0, 6);
        amdFsrMode = clamp(amdFsrMode, 0, 6);
        intelXessMode = clamp(intelXessMode, 0, 6);
        int enabledUpscalers = (dlssUpscalingMode == 0 ? 0 : 1)
                + (amdFsrMode == 0 ? 0 : 1)
                + (intelXessMode == 0 ? 0 : 1);
        if (enabledUpscalers > 1) {
            if (activeVendorId == 0) {
                setUpscalerMode(
                        FrameGenerationBackend.GpuVendor.UNKNOWN, 0
                );
            } else {
                FrameGenerationBackend.GpuVendor vendor =
                        FrameGenerationBackend.GpuVendor.fromVendorId(
                                activeVendorId
                        );
                int retainedMode = switch (vendor) {
                    case NVIDIA -> dlssUpscalingMode;
                    case AMD -> amdFsrMode;
                    case INTEL -> intelXessMode;
                    case UNKNOWN -> 0;
                };
                setUpscalerMode(vendor, retainedMode);
            }
        }
        frameMultiplier = clamp(frameMultiplier, 2, 4);
        amdFsrMultiplier = 2;
        intelXessMfgMultiplier = 2;
        dlssSharpness = clamp(dlssSharpness, 0.0f, 1.0f);
        amdFsrSharpness = clamp(amdFsrSharpness, 0.0f, 1.0f);
        intelXessSharpness = clamp(intelXessSharpness, 0.0f, 1.0f);
        motionBlurIntensity = clamp(motionBlurIntensity, 0.0f, 1.0f);
        motionBlurSamples = clamp(motionBlurSamples, 1, 64);
        targetFps = clamp(targetFps, 30, 1000);
        rayTracingMaxDistance = clamp(rayTracingMaxDistance, 16, 48);
        rayTracingRaysPerPixel = clamp(rayTracingRaysPerPixel, 1, 8);
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static float clamp(float value, float minimum, float maximum) {
        if (!Float.isFinite(value)) return minimum;
        return Math.max(minimum, Math.min(maximum, value));
    }

    public void save() {
        sanitize();
        try {
            Path path = getConfigPath();
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            try (var writer = Files.newBufferedWriter(path)) {
                GSON.toJson(this, writer);
            }
        } catch (IOException e) {
            LOGGER.error("Failed to save Frame Generation config", e);
        }
    }
}
