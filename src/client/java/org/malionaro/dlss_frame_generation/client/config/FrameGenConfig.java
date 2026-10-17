package org.malionaro.dlss_frame_generation.client.config;
// Unresourceful brainless shithead
// Original work by Malionaro. First commit 9f3c9994a3cd (2026-07-28), see PROOF.md.
import org.malionaro.dlss_frame_generation.client.backend.FrameGenerationBackend;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public class FrameGenConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("dlss_fg.config");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Path getConfigPath() {
        Path override = configPathOverride;
        if (override != null) {
            return override;
        }
        try {
            if (FabricLoader.getInstance() != null) {
                return FabricLoader.getInstance().getConfigDir().resolve("frame_gen.json");
            }
        } catch (Throwable t) {
            // Fallback for the headless unit test runner. Loud on purpose:
            // silently splitting productive and test config hides divergence.
            LOGGER.warn("Config directory unavailable, falling back to build/tmp/frame_gen.json: {}",
                    t.toString());
        }
        return Path.of("build", "tmp", "frame_gen.json");
    }

    private static volatile FrameGenConfig instance;
    /** Test-only override so unit tests never touch the real config file. */
    private static volatile Path configPathOverride;

    /** Test seam (same package): redirect the config file into a temp dir. */
    static void overrideConfigPathForTesting(Path path) {
        configPathOverride = path;
    }

    /** Test seam (same package): restore the regular config path. */
    static void clearConfigPathOverrideForTesting() {
        configPathOverride = null;
    }

    /** Test seam (same package): drop the cached singleton for isolation. */
    static synchronized void resetInstanceForTesting() {
        instance = null;
    }

    public int configVersion = 5;

    /**
     * True once a provider choice was ever made explicitly (UI toggle,
     * keybinding, preset) or resolved via first-run device detection.
     * Guards first-run auto-enable so a user who turned everything off is
     * never force-enabled again. Files predating this flag migrate to
     * {@code true} (existing file = preserved user intent).
     */
    public boolean userHasChosenProvider = false;
    /** Re-entrancy guard: sanitize/preset internals must not mark user choice. */
    private transient boolean internalAdjust;

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
    /** Switch FG and upscaling on when a shader pack starts (see ShaderPackBoost). */
    public boolean shaderPackAutoBoost = true;
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
        markUserChoice();
        if (enabled) {
            // Until a real D3D12 FG runtime owns the DXGI swapchain, no Vulkan
            // FG proxy may compete for presentation ownership.
            setFrameGenerationEnabled(
                    FrameGenerationBackend.GpuVendor.UNKNOWN, false);
        }
    }

    public void applyPreset(Preset preset) {
        this.activePreset = preset;
        markUserChoice();
        FrameGenerationBackend.GpuVendor vendor = selectedFrameGenerationVendor();
        if (vendor == FrameGenerationBackend.GpuVendor.UNKNOWN) {
            vendor = selectedUpscalerVendor();
        }
        if (vendor == FrameGenerationBackend.GpuVendor.UNKNOWN) {
            vendor = FrameGenerationBackend.GpuVendor.NVIDIA;
        }
        switch (preset) {
            case ESPORTS -> {
                this.reflexMode = 2; // Reflex + Boost
                this.reflexPacingEnabled = true;
                this.reflexInputMarkerEnabled = true;
                this.targetFps = 240;
                this.motionBlurEnabled = false;
                setUpscalerMode(vendor, 1); // Native AA at native resolution
                this.projectionJitterEnabled = true;
                // Keep DRS and motion blur off for esports, low latency matters most.
                this.drsEnabled = false;
            }
            case CINEMA -> {
                this.dlaaEnabled = true;
                this.hdrEnabled = true;
                this.motionBlurEnabled = true;
                this.motionBlurIntensity = 0.6f;
                setUpscalerMode(vendor, 2); // Quality
                this.projectionJitterEnabled = true;
                // Cinema needs quality, so DRS stays off.
                this.drsEnabled = false;
            }
            case MAX_PERFORMANCE -> {
                // Only enable FG when a vendor is already selected.
                FrameGenerationBackend.GpuVendor selectedVendor =
                        selectedFrameGenerationVendor();
                if (selectedVendor
                        != FrameGenerationBackend.GpuVendor.UNKNOWN) {
                    setFrameGenerationEnabled(selectedVendor, true);
                } else {
                    LOGGER.warn("applyPreset MAX_PERFORMANCE skipped frame-generation enable - no vendor selected");
                    // No silent fallback to NVIDIA. Preset stays, sanitize checks later if FG is really active.
                }
                this.frameMultiplier = 4; // 4x MFG
                this.amdFsrMultiplier = 2;
                this.intelXessMfgMultiplier = 2;
                setUpscalerMode(vendor, 4); // Performance
                this.projectionJitterEnabled = true;
                this.drsEnabled = true;
                this.targetFps = 144;
                // Motion blur costs frames, which this preset is about.
                this.motionBlurEnabled = false;
            }
            case CUSTOM -> {}
        }
        // Clamp UI values after applying a preset. Target is 30 to 360, intensity 0 to 1.
        this.targetFps = clamp(this.targetFps, 30, 360);
        this.motionBlurIntensity = clamp(this.motionBlurIntensity, 0.0f, 1.0f);
        this.motionBlurSamples = clamp(this.motionBlurSamples, 1, 64);
        sanitize();
        save();
    }

    // Motion blur from dense Vulkan motion vectors.
    public boolean motionBlurEnabled = false;
    public float motionBlurIntensity = 0.5f;
    public int motionBlurSamples = 8;

    // Projection jitter for DLAA/DLSS. The selected Halton sample goes into
    // Minecraft's world projection before the world render and is reported to
    // Streamline in pixel space.
    public boolean dlaaEnabled = false;
    public boolean projectionJitterEnabled = true;

    // Pacing to keep Reflex at the target frame rate.
    public boolean reflexPacingEnabled = true;

    // HDR tone mapping.
    public boolean hdrEnabled = false;

    // On-screen performance overlay.
    public boolean osdEnabled = false;

    // Dynamic resolution scaling target.
    public boolean drsEnabled = false;
    public int targetFps = 144;

    // Kept for configuration-file compatibility. Streamline's old mouse input
    // sample marker is deprecated; the runtime uses the supported per-frame
    // simulation/render/present markers instead.
    public boolean reflexInputMarkerEnabled = false;
    public boolean dlssRayReconstructionEnabled = false;

    /**
     * Sets the RT mode. Switching RT on also turns on DLSS Ray Reconstruction
     * when the GPU supports it, so the traced lighting is denoised; it needs at
     * least DLSS Quality. The player can turn RR off again afterwards.
     */
    /**
     * Ray Reconstruction stays opt-in: on some setups it accepted frames but
     * never wrote its output, which froze the picture as soon as RT was on.
     * Turning RT on only makes sure DLSS reconstructs (Quality), so the RT
     * noise is accumulated by DLSS Super Resolution.
     */
    public void setRayTracingMode(RayTracingMode mode, boolean rayReconstructionSupported) {
        boolean turnedOn = rayTracingMode == RayTracingMode.OFF && mode != RayTracingMode.OFF;
        rayTracingMode = mode;
        if (turnedOn && rayReconstructionSupported && dlssUpscalingMode < 2) {
            setUpscalerMode(FrameGenerationBackend.GpuVendor.NVIDIA, 2);
            projectionJitterEnabled = true;
        }
    }

    // Single source for the RT scene radius: the scene and sanitize both use it.
    /**
     * Highest DLSS-G multiplier the config accepts. DLSS 4.5 dynamic multi
     * frame generation reaches 6x on RTX 50; the native bridge still clamps
     * every request to numFramesToGenerateMax + 1 the driver reports, so
     * older cards never get more than they support.
     */
    public static final int MAX_FRAME_MULTIPLIER = 6;
    /** Far RT range costs much more; the default stays at 48 blocks. */
    public static final int MAX_RAY_TRACING_DISTANCE = 96;
    public static final int DEFAULT_RAY_TRACING_DISTANCE = 48;
    public static final int MIN_RAY_TRACING_DISTANCE = 16;

    // Hybrid hardware ray tracing (raster frame + Vulkan ray-query pass).
    public RayTracingMode rayTracingMode = RayTracingMode.OFF;
    public RayTracingQuality rayTracingQuality = RayTracingQuality.MEDIUM;
    public int rayTracingMaxDistance = DEFAULT_RAY_TRACING_DISTANCE;
    public int rayTracingRaysPerPixel = 1;
    /**
     * Legacy JSON field name retained for compatibility.
     * Despite the name, this controls stable multi-sampling (minimum four rays
     * when enabled) and not a temporal denoiser. Default {@code true} preserves
     * historic behaviour where stable sampling is on.
     */
    public boolean rayTracingDenoiserEnabled = true;
    /**
     * RT engine: near terrain the RT pass fully covers is not rasterised by
     * vanilla (saves its cost). Turn off if the near world ever looks wrong.
     */
    public boolean rayTracingReplaceTerrain = false;
    /** RT shader pack folder or zip in rtshaderpacks/; empty = built-in. */
    public String rayTracingShaderPack = "";

    // NVIDIA Streamline options. The runtime-reported maximum multiplier is
    // applied before the option is sent to DLSS-G.
    // All providers default to off: first-run device detection enables the
    // detected vendor instead of shipping an ambiguous all-on state.
    public boolean dlssFgEnabled = false;
    public int reflexMode = 1; // 0 = Off, 1 = Low Latency, 2 = Low Latency + Boost
    // 0 Off, 1 DLAA, 2 Quality, 3 Balanced, 4 Performance,
    // 5 Ultra Performance, 6 Ultra Quality.
    public int dlssUpscalingMode = 0;
    /**
     * @deprecated Streamline's DLSS sharpness option is deprecated and no longer
     * exposed or applied. Retained only to read older config files; sanitize
     * clamps to 0..1 and the value is otherwise unused.
     */
    @Deprecated(since = "1.0", forRemoval = false)
    public float dlssSharpness = 0.0f;
    public int frameMultiplier = 2; // 2x up to 6x; native clamps to the driver-reported maximum

    // AMD renderer options. FSR 4.1.1 upscaling and FSR 4.0.1 frame
    // generation are currently D3D12-only in AMD's public SDK. The bundled
    // Vulkan provider is FSR 3.1.
    // Minecraft still renders its combined scene/UI target at display size, so
    // the only valid Vulkan mode is a real 1:1 temporal Native-AA dispatch.
    public int amdFsrMode = 0; // 0 = Off, 1 = Native AA
    public float amdFsrSharpness = 0.5f; // 0.0 to 1.0
    public boolean amdFsrFgEnabled = false;
    public int amdFsrMultiplier = 2; // FSR 3.1 Vulkan supports one generated frame (2x)

    // Intel Vulkan route. XeSS-SR/FG stay disabled until their native modules
    // or a D3D12 renderer exist; the current FG path is an FSR 3.1 fallback.
    public int intelXessMode = 0; // 0 = Off, 1 = cross-vendor Native AA
    public float intelXessSharpness = 0.5f; // 0.0 to 1.0
    public boolean intelXessFgEnabled = false;
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
        markUserChoice();
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
        markUserChoice();
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

    private void markUserChoice() {
        if (!internalAdjust) {
            userHasChosenProvider = true;
        }
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

    public static synchronized FrameGenConfig getInstance() {
        if (instance == null) {
            instance = load();
        }
        return instance;
    }

    // Package-private for same-package unit tests.
    static FrameGenConfig load() {
        Path path = getConfigPath();
        if (Files.exists(path)) {
            try (var reader = Files.newBufferedReader(path)) {
                FrameGenConfig config = GSON.fromJson(reader, FrameGenConfig.class);
                if (config != null) {
                    config.migrateIfNeeded();
                    config.sanitize();
                    return config;
                }
            } catch (IOException | RuntimeException e) {
                LOGGER.error("Failed to load Frame Generation config, using defaults", e);
                try {
                    Path backup = path.resolveSibling(path.getFileName() + ".bak");
                    Files.move(path, backup, StandardCopyOption.REPLACE_EXISTING);
                    LOGGER.warn("Corrupt config moved to backup: {}", backup);
                } catch (IOException moveEx) {
                    LOGGER.error("Failed to backup corrupt config", moveEx);
                }
            }
        }
        FrameGenConfig defaultConfig = new FrameGenConfig();
        defaultConfig.save();
        return defaultConfig;
    }

    private void migrateIfNeeded() {
        if (configVersion < 2) {
            // Migration stub for configVersion 2: sanitize will clamp/normalize new fields
            configVersion = 2;
        }
        if (configVersion < 3) {
            // Files predating userHasChosenProvider keep their flags exactly
            // as-is and count as an explicit choice, so first-run auto-enable
            // never overrides preserved user intent.
            userHasChosenProvider = true;
            configVersion = 3;
        }
        if (configVersion < 4) {
            // Builds up to nightly 3 switched Ray Reconstruction on together
            // with RT, which froze the picture; start from RR off once.
            dlssRayReconstructionEnabled = false;
            configVersion = 4;
        }
        if (configVersion < 5) {
            // Nightly 4 switched the RT terrain replacement on by default; on
            // setups where the RT pass draws nothing, near chunks vanished.
            rayTracingReplaceTerrain = false;
            configVersion = 5;
        }
    }

    public void sanitize() {
        boolean previousAdjust = internalAdjust;
        internalAdjust = true;
        try {
            sanitizeInner();
        } finally {
            internalAdjust = previousAdjust;
        }
    }

    private void sanitizeInner() {
        if (activePreset == null) {
            LOGGER.warn("Sanitize: activePreset was null, resetting to CUSTOM");
            activePreset = Preset.CUSTOM;
        }
        if (rayTracingMode == null) {
            LOGGER.warn("Sanitize: rayTracingMode was null, resetting to OFF");
            rayTracingMode = RayTracingMode.OFF;
        }
        if (rayTracingShaderPack == null) {
            rayTracingShaderPack = "";
        }
        if (rayTracingQuality == null) {
            LOGGER.warn("Sanitize: rayTracingQuality was null, resetting to MEDIUM");
            rayTracingQuality = RayTracingQuality.MEDIUM;
        }
        if (activeVendorId != 0
                && activeVendorId != FrameGenerationBackend.GpuVendor.NVIDIA.vendorId()
                && activeVendorId != FrameGenerationBackend.GpuVendor.AMD.vendorId()
                && activeVendorId != FrameGenerationBackend.GpuVendor.INTEL.vendorId()) {
            LOGGER.warn("Sanitize: activeVendorId {} is invalid, resetting to Auto (0)", activeVendorId);
            activeVendorId = 0;
        }
        if (activeVendorId != 0) {
            boolean changed = retainFrameGenerationForVendor(
                    FrameGenerationBackend.GpuVendor.fromVendorId(
                            activeVendorId
                    )
            );
            if (changed) {
                LOGGER.warn("Sanitize: removed incompatible frame-generation flags for activeVendorId {}", activeVendorId);
            }
        } else {
            int enabledFrameGenerators = (dlssFgEnabled ? 1 : 0)
                    + (amdFsrFgEnabled ? 1 : 0)
                    + (intelXessFgEnabled ? 1 : 0);
            if (enabledFrameGenerators > 1) {
                LOGGER.warn("Sanitize: multiple frame-generation providers enabled in Auto mode ({}), preferring NVIDIA", enabledFrameGenerators);
                if (dlssFgEnabled) {
                    amdFsrFgEnabled = false;
                    intelXessFgEnabled = false;
                } else if (amdFsrFgEnabled) {
                    dlssFgEnabled = false;
                    intelXessFgEnabled = false;
                } else if (intelXessFgEnabled) {
                    dlssFgEnabled = false;
                    amdFsrFgEnabled = false;
                } else {
                    setFrameGenerationEnabled(
                            FrameGenerationBackend.GpuVendor.UNKNOWN,
                            false
                    );
                }
            }
        }
        if (hybridD3D12PresentationEnabled) {
            if (dlssFgEnabled || amdFsrFgEnabled || intelXessFgEnabled) {
                LOGGER.warn("Sanitize: hybridD3D12PresentationEnabled is true, disabling all frame-generation providers");
            }
            setFrameGenerationEnabled(
                    FrameGenerationBackend.GpuVendor.UNKNOWN, false);
        }
        int oldReflex = reflexMode;
        reflexMode = clamp(reflexMode, 0, 2);
        if (oldReflex != reflexMode) {
            LOGGER.warn("Sanitize: reflexMode clamped from {} to {}", oldReflex, reflexMode);
        }
        int oldDlssMode = dlssUpscalingMode;
        dlssUpscalingMode = clamp(dlssUpscalingMode, 0, 6);
        if (oldDlssMode != dlssUpscalingMode) {
            LOGGER.warn("Sanitize: dlssUpscalingMode clamped from {} to {}", oldDlssMode, dlssUpscalingMode);
        }
        int oldAmdMode = amdFsrMode;
        // Only 0=Off and 1=Native-AA are real Vulkan modes; anything higher
        // is silently unusable natively, so fail closed to Off with a warning.
        amdFsrMode = amdFsrMode > 1 ? 0 : clamp(amdFsrMode, 0, 1);
        if (oldAmdMode != amdFsrMode) {
            LOGGER.warn("Sanitize: amdFsrMode clamped from {} to {} (Vulkan supports 0=Off, 1=Native-AA)", oldAmdMode, amdFsrMode);
        }
        int oldIntelMode = intelXessMode;
        intelXessMode = intelXessMode > 1 ? 0 : clamp(intelXessMode, 0, 1);
        if (oldIntelMode != intelXessMode) {
            LOGGER.warn("Sanitize: intelXessMode clamped from {} to {} (Vulkan supports 0=Off, 1=Native-AA)", oldIntelMode, intelXessMode);
        }
        int enabledUpscalers = (dlssUpscalingMode == 0 ? 0 : 1)
                + (amdFsrMode == 0 ? 0 : 1)
                + (intelXessMode == 0 ? 0 : 1);
        if (enabledUpscalers > 1) {
            LOGGER.warn("Sanitize: multiple upscalers enabled (dlss={}, amd={}, intel={}) with activeVendorId={}, resolving", dlssUpscalingMode, amdFsrMode, intelXessMode, activeVendorId);
            if (activeVendorId == 0) {
                if (dlssUpscalingMode != 0) {
                    int retained = dlssUpscalingMode;
                    setUpscalerMode(
                            FrameGenerationBackend.GpuVendor.NVIDIA, retained
                    );
                    LOGGER.warn("Sanitize: Auto mode - retained NVIDIA upscaler mode {}", retained);
                } else {
                    setUpscalerMode(
                            FrameGenerationBackend.GpuVendor.UNKNOWN, 0
                    );
                    LOGGER.warn("Sanitize: Auto mode - cleared all upscalers (no NVIDIA mode to prefer)");
                }
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
                LOGGER.warn("Sanitize: retaining {} upscaler mode {} for vendor {}", vendor, retainedMode, activeVendorId);
                setUpscalerMode(vendor, retainedMode);
            }
        }
        int oldFrameMult = frameMultiplier;
        frameMultiplier = clamp(frameMultiplier, 2, MAX_FRAME_MULTIPLIER);
        if (oldFrameMult != frameMultiplier) {
            LOGGER.warn("Sanitize: frameMultiplier clamped from {} to {}", oldFrameMult, frameMultiplier);
        }
        int oldAmdMult = amdFsrMultiplier;
        int oldIntelMult = intelXessMfgMultiplier;
        if (hybridD3D12PresentationEnabled) {
            amdFsrMultiplier = clamp(amdFsrMultiplier, 2, 4);
            intelXessMfgMultiplier = clamp(intelXessMfgMultiplier, 2, 4);
        } else {
            if (amdFsrMultiplier != 2) {
                LOGGER.warn("Sanitize: amdFsrMultiplier {} clamped to 2 (Vulkan-only, use hybrid D3D12 for 3/4x)", amdFsrMultiplier);
            }
            amdFsrMultiplier = 2;
            if (intelXessMfgMultiplier != 2) {
                LOGGER.warn("Sanitize: intelXessMfgMultiplier {} clamped to 2 (Vulkan-only)", intelXessMfgMultiplier);
            }
            intelXessMfgMultiplier = 2;
        }
        if (oldAmdMult != amdFsrMultiplier) {
            LOGGER.warn("Sanitize: amdFsrMultiplier adjusted from {} to {}", oldAmdMult, amdFsrMultiplier);
        }
        if (oldIntelMult != intelXessMfgMultiplier) {
            LOGGER.warn("Sanitize: intelXessMfgMultiplier adjusted from {} to {}", oldIntelMult, intelXessMfgMultiplier);
        }
        float oldDlssSharp = dlssSharpness;
        dlssSharpness = clamp(dlssSharpness, 0.0f, 1.0f);
        if (oldDlssSharp != dlssSharpness) {
            LOGGER.warn("Sanitize: dlssSharpness clamped from {} to {}", oldDlssSharp, dlssSharpness);
        }
        float oldAmdSharp = amdFsrSharpness;
        amdFsrSharpness = clamp(amdFsrSharpness, 0.0f, 1.0f);
        if (oldAmdSharp != amdFsrSharpness) {
            LOGGER.warn("Sanitize: amdFsrSharpness clamped from {} to {}", oldAmdSharp, amdFsrSharpness);
        }
        float oldIntelSharp = intelXessSharpness;
        intelXessSharpness = clamp(intelXessSharpness, 0.0f, 1.0f);
        if (oldIntelSharp != intelXessSharpness) {
            LOGGER.warn("Sanitize: intelXessSharpness clamped from {} to {}", oldIntelSharp, intelXessSharpness);
        }
        float oldBlurIntensity = motionBlurIntensity;
        motionBlurIntensity = clamp(motionBlurIntensity, 0.0f, 1.0f);
        if (oldBlurIntensity != motionBlurIntensity) {
            LOGGER.warn("Sanitize: motionBlurIntensity clamped from {} to {}", oldBlurIntensity, motionBlurIntensity);
        }
        int oldBlurSamples = motionBlurSamples;
        motionBlurSamples = clamp(motionBlurSamples, 1, 64);
        if (oldBlurSamples != motionBlurSamples) {
            LOGGER.warn("Sanitize: motionBlurSamples clamped from {} to {}", oldBlurSamples, motionBlurSamples);
        }
        int oldTargetFps = targetFps;
        // Clamp UI values. Slider is 30 to 360, not 1000.
        targetFps = clamp(targetFps, 30, 360);
        if (oldTargetFps != targetFps) {
            LOGGER.warn("Sanitize: targetFps clamped from {} to {}", oldTargetFps, targetFps);
        }
        // Max performance needs a selected vendor.
        if (activePreset == Preset.MAX_PERFORMANCE) {
            if (selectedFrameGenerationVendor() == FrameGenerationBackend.GpuVendor.UNKNOWN) {
                LOGGER.warn("Sanitize: MAX_PERFORMANCE preset requires selected vendor, resetting to CUSTOM");
                activePreset = Preset.CUSTOM;
            }
        }
        // Cross check DRS and motion blur.
        if (drsEnabled && !Float.isFinite((float) targetFps)) {
            LOGGER.warn("Sanitize: drsEnabled but targetFps invalid, resetting to 144");
            targetFps = 144;
        }
        if (motionBlurEnabled) {
            if (!Float.isFinite(motionBlurIntensity) || motionBlurIntensity < 0.0f || motionBlurIntensity > 1.0f) {
                LOGGER.warn("Sanitize: motionBlurIntensity {} out of UI range 0..1, clamping", motionBlurIntensity);
                motionBlurIntensity = clamp(motionBlurIntensity, 0.0f, 1.0f);
            }
            if (motionBlurSamples < 1 || motionBlurSamples > 64) {
                LOGGER.warn("Sanitize: motionBlurSamples {} out of UI range 1..64, clamping", motionBlurSamples);
                motionBlurSamples = clamp(motionBlurSamples, 1, 64);
            }
            // Motion blur without an active upscaler does nothing, but it is not fatal. Just warn.
            if (selectedUpscalerVendor() == FrameGenerationBackend.GpuVendor.UNKNOWN && selectedFrameGenerationVendor() == FrameGenerationBackend.GpuVendor.UNKNOWN) {
                LOGGER.warn("Sanitize: motionBlurEnabled without any active vendor/upscaler - keeping but requires Vulkan feature");
            }
        }
        if (drsEnabled && targetFps < 30) {
            LOGGER.warn("Sanitize: drsEnabled but targetFps {} <30, clamping to 30", targetFps);
            targetFps = 30;
        }
        int oldMaxDist = rayTracingMaxDistance;
        rayTracingMaxDistance = clamp(rayTracingMaxDistance, MIN_RAY_TRACING_DISTANCE, MAX_RAY_TRACING_DISTANCE);
        if (oldMaxDist != rayTracingMaxDistance) {
            LOGGER.warn("Sanitize: rayTracingMaxDistance clamped from {} to {}", oldMaxDist, rayTracingMaxDistance);
        }
        int oldRays = rayTracingRaysPerPixel;
        rayTracingRaysPerPixel = clamp(rayTracingRaysPerPixel, 1, 8);
        if (oldRays != rayTracingRaysPerPixel) {
            LOGGER.warn("Sanitize: rayTracingRaysPerPixel clamped from {} to {}", oldRays, rayTracingRaysPerPixel);
        }
        // Clamp again: the block above only runs with motion blur enabled.
        motionBlurIntensity = clamp(motionBlurIntensity, 0.0f, 1.0f);
        motionBlurSamples = clamp(motionBlurSamples, 1, 64);
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static float clamp(float value, float minimum, float maximum) {
        if (!Float.isFinite(value)) return minimum;
        return Math.max(minimum, Math.min(maximum, value));
    }

    public synchronized void save() {
        sanitize();
        try {
            Path path = getConfigPath();
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path temp = parent != null
                    ? Files.createTempFile(parent, "frame_gen", ".tmp")
                    : Files.createTempFile("frame_gen", ".tmp");
            try (var writer = Files.newBufferedWriter(temp)) {
                GSON.toJson(this, writer);
            }
            try {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFail) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            LOGGER.error("Failed to save Frame Generation config", e);
        }
    }

    // Coalesced persistence for per-tick writers (sliders). Discrete
    // clicks keep using synchronous save() (crash-safe); high-frequency
    // applyValue paths use saveSoon() and flush on release/close, so dragging
    // a slider performs one disk write instead of one per pixel. Daemon thread:
    // dies with the JVM, no shutdown hook required.
    private static final java.util.concurrent.ScheduledExecutorService SAVE_DEBOUNCER =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "dlss-fg-config-save");
                thread.setDaemon(true);
                return thread;
            });
    private static final long SAVE_DEBOUNCE_MILLIS = 300L;
    private static volatile java.util.concurrent.ScheduledFuture<?> pendingSave;

    /** Coalesced save: persists ~300ms after the last call. Never throws. */
    public void saveSoon() {
        try {
            synchronized (FrameGenConfig.class) {
                java.util.concurrent.ScheduledFuture<?> previous = pendingSave;
                if (previous != null) {
                    previous.cancel(false);
                }
                pendingSave = SAVE_DEBOUNCER.schedule(() -> {
                    try {
                        save();
                    } catch (Throwable ignored) {
                    }
                }, SAVE_DEBOUNCE_MILLIS, java.util.concurrent.TimeUnit.MILLISECONDS);
            }
        } catch (Throwable ignored) {
            save();
        }
    }

    /** Cancels a pending coalesced save and persists synchronously. Never throws. */
    public static void flushPendingSave() {
        try {
            synchronized (FrameGenConfig.class) {
                java.util.concurrent.ScheduledFuture<?> previous = pendingSave;
                pendingSave = null;
                if (previous != null) {
                    previous.cancel(false);
                }
            }
            getInstance().save();
        } catch (Throwable ignored) {
        }
    }
}
