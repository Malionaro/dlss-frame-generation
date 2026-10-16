package org.malionaro.dlss_frame_generation.client.compat;
import org.malionaro.dlss_frame_generation.client.backend.AmdBackend;
import org.malionaro.dlss_frame_generation.client.backend.FallbackBackend;
import org.malionaro.dlss_frame_generation.client.backend.FrameGenerationBackend;
import org.malionaro.dlss_frame_generation.client.backend.FrameGenerationCompatibilityLayer;
import org.malionaro.dlss_frame_generation.client.backend.IntelBackend;
import org.malionaro.dlss_frame_generation.client.backend.NvidiaBackend;
import org.malionaro.dlss_frame_generation.client.backend.ProviderLifecycle;
import org.malionaro.dlss_frame_generation.client.config.FrameGenConfig;
import org.malionaro.dlss_frame_generation.client.frame.FrameIdentity;
import org.malionaro.dlss_frame_generation.client.interop.NativeLoader;
import org.malionaro.dlss_frame_generation.client.render.adapter.OpenGLFallbackAdapter;
import org.malionaro.dlss_frame_generation.client.render.adapter.RendererBackend;
import org.malionaro.dlss_frame_generation.client.render.adapter.RendererBackendAdapter;
import org.malionaro.dlss_frame_generation.client.render.adapter.SodiumVulkanAdapter;
import org.malionaro.dlss_frame_generation.client.render.adapter.VanillaVulkanAdapter;
import org.malionaro.dlss_frame_generation.client.render.VulkanRegistry;
import org.malionaro.dlss_frame_generation.client.StreamlineBridge;

import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class CompatibilityManager {
    private static final Logger LOGGER =
            LoggerFactory.getLogger("dlss_fg.compat");
    private final NativeLoader.NativeLoadResult nativeLoadResult;
    private volatile RendererDetection rendererDetection;
    private volatile RendererBackendAdapter adapter;
    private volatile FrameGenerationBackend activeBackend;
    private final ProviderLifecycle providerLifecycle =
            new ProviderLifecycle();

    private CompatibilityManager(NativeLoader.NativeLoadResult nativeLoadResult, RendererDetection rendererDetection, RendererBackendAdapter adapter, FrameGenerationBackend activeBackend) {
        this.nativeLoadResult = nativeLoadResult;
        this.rendererDetection = rendererDetection;
        this.adapter = adapter;
        this.activeBackend = activeBackend;
    }

    /**
     * Points Streamline's own log at a directory that outlives the process.
     *
     * <p>The plugins path is a {@code deleteOnExit} temp directory, so the SDK
     * log would vanish together with the failure that produced it. When a
     * feature is rejected, this log holds the reason and the adapter data
     * Streamline resolved, which is the only place that appears.
     */
    private static void configureStreamlineLogging(Logger logger) {
        Path logsDir;
        try {
            logsDir = FabricLoader.getInstance()
                    .getConfigDir()
                    .resolve("dlss_fg")
                    .resolve("streamline");
            Files.createDirectories(logsDir);
        } catch (IOException | RuntimeException unavailable) {
            logger.warn(
                    "Could not create a persistent Streamline log directory,"
                            + " falling back to the plugins path: {}",
                    unavailable.toString()
            );
            return;
        }
        try {
            StreamlineBridge.nSetLogsPath(logsDir.toAbsolutePath().toString());
            logger.info("Streamline log directory: {}", logsDir);
        } catch (UnsatisfiedLinkError stale) {
            logger.warn(
                    "Native bridge has no Streamline log path setter"
                            + " (stale dlss_fg_native.dll?): {}",
                    stale.getMessage()
            );
        }
    }

    public static CompatibilityManager initialize(Logger logger) {
        NativeLoader.NativeLoadResult nativeLoadResult = NativeLoader.load(logger);
        if (nativeLoadResult.loaded()) {
            try {
                configureStreamlineLogging(logger);
                boolean coreReady = StreamlineBridge.nInitCore(NativeLoader.getExtractedNativesDir());
                logger.info("Streamline core early initialization: {}", coreReady ? "READY" : "UNAVAILABLE");
            } catch (UnsatisfiedLinkError error) {
                logger.warn("Streamline core entry point is unavailable: {}", error.getMessage());
            }
        }
        RendererDetection rendererDetection = RendererDetector.detect();
        
        RendererBackendAdapter adapter;
        switch (rendererDetection.backend()) {
            case VANILLA_VULKAN:
                adapter = new VanillaVulkanAdapter();
                break;
            case SODIUM_VULKAN:
                adapter = new SodiumVulkanAdapter();
                break;
            case OPENGL:
            case IRIS_OPENGL:
            default:
                adapter = new OpenGLFallbackAdapter();
                break;
        }

        FrameGenerationBackend activeBackend = resolveBackend(rendererDetection.backendDescription());

        CompatibilityManager manager = new CompatibilityManager(nativeLoadResult, rendererDetection, adapter, activeBackend);
        logger.info(
                "DLSS FG renderer detection: backend={}, description='{}', "
                        + "device={}, sodium={} ({}), iris={} ({}), "
                        + "shaderState={}",
                rendererDetection.backend(),
                rendererDetection.backendDescription(),
                rendererDetection.gpuDeviceClass(),
                rendererDetection.sodiumPresent(),
                rendererDetection.sodiumVersion(),
                rendererDetection.irisPresent(),
                rendererDetection.irisVersion(),
                rendererDetection.shaderPipelineState()
        );
        logger.info("DLSS FG active adapter resolved to: {}, vendor backend: {}", adapter.getClass().getSimpleName(), activeBackend.vendor().displayName());
        if (rendererDetection.backend() == RendererBackend.OPENGL
                || rendererDetection.backend() == RendererBackend.IRIS_OPENGL) {
            logger.warn(
                    "Minecraft is running on the OpenGL renderer, so DLSS/FSR/XeSS frame generation and Reflex stay "
                            + "disabled. Switch the graphics API to Vulkan in Options -> Video Settings (preferredGraphicsBackend), "
                            + "then restart the game. All mod features require Mojang's VulkanDevice.");
        }
        return manager;
    }

    public static FrameGenerationBackend resolveBackend(String rendererDescription) {
        int configuredVendor = FrameGenConfig.getInstance().activeVendorId;
        if (configuredVendor != 0) {
            FrameGenerationBackend.GpuVendor vendor = FrameGenerationBackend.GpuVendor.fromVendorId(configuredVendor);
            switch (vendor) {
                case NVIDIA: return new NvidiaBackend();
                case AMD: return new AmdBackend();
                case INTEL: return new IntelBackend();
                default: return new FallbackBackend();
            }
        }

        // Renderer strings are not reliable GPU identifiers. The real vendor
        // is selected later from VkPhysicalDeviceProperties.vendorID.
        return new FallbackBackend();
    }

    public RendererBackend backend() { 
        return rendererDetection.backend(); 
    }

    public RendererDetection rendererDetection() {
        return rendererDetection;
    }

    public NativeLoader.NativeLoadResult nativeLoadResult() {
        return nativeLoadResult;
    }

    public RendererBackendAdapter adapter() {
        return adapter;
    }

    /**
     * Minecraft chooses and constructs the graphics backend after Fabric client
     * entrypoints have run. The Vulkan mixin is therefore the authoritative
     * signal that replaces the provisional startup adapter.
     */
    public synchronized void activateVulkanAdapter() {
        activateVulkanAdapter(VulkanRegistry.getActiveDevice());
    }

    public synchronized void activateVulkanAdapter(VulkanDevice device) {
        rendererDetection = RendererDetector.detect(device);
        if (!(adapter instanceof VanillaVulkanAdapter)
                && !(adapter instanceof SodiumVulkanAdapter)) {
            adapter = rendererDetection.sodiumPresent()
                    ? new SodiumVulkanAdapter()
                    : new VanillaVulkanAdapter();
        } else if (rendererDetection.sodiumPresent()
                && !(adapter instanceof SodiumVulkanAdapter)) {
            adapter = new SodiumVulkanAdapter();
        } else if (!rendererDetection.sodiumPresent()
                && !(adapter instanceof VanillaVulkanAdapter)) {
            adapter = new VanillaVulkanAdapter();
        }
    }

    public FrameGenerationBackend activeVendorBackend() {
        return activeBackend;
    }

    public FrameGenerationCompatibilityLayer.Route activeRoute() {
        return FrameGenerationCompatibilityLayer.queryVulkanRoute(
                activeBackend.vendor()
        );
    }

    public ProviderLifecycle.Snapshot providerSnapshot() {
        return providerLifecycle.snapshot();
    }

    public synchronized void beginProviderInitialization(
            FrameGenerationCompatibilityLayer.ProviderId provider,
            String detail
    ) {
        providerLifecycle.detected(provider, detail);
        if (provider
                != FrameGenerationCompatibilityLayer.ProviderId.NONE) {
            providerLifecycle.initializing();
        }
    }

    public synchronized void detectProvider(
            FrameGenerationCompatibilityLayer.ProviderId provider,
            String detail
    ) {
        providerLifecycle.detected(provider, detail);
    }

    public synchronized void completeProviderInitialization(
            boolean success,
            String detail
    ) {
        if (success) {
            providerLifecycle.ready(detail);
        } else {
            providerLifecycle.failed(detail);
        }
    }

    public synchronized void beginProviderDrain(long generation) {
        ProviderLifecycle.State state =
                providerLifecycle.snapshot().state();
        if (generation <= 0L) {
            // C6: generation 0 means "no swapchain yet" — draining is
            // meaningless and ProviderLifecycle.draining would throw,
            // breaking swapchain configure. The very first configure of
            // every session lands here, so this is not worth a warning.
            LOGGER.debug(
                    "Ignoring provider drain request with non-positive generation {}",
                    generation);
            return;
        }
        if (state == ProviderLifecycle.State.READY
                || state == ProviderLifecycle.State.ACTIVE
                || state == ProviderLifecycle.State.DRAINING) {
            providerLifecycle.draining(generation, true);
        } else {
            LOGGER.debug(
                    "Ignoring provider drain in state {} (no provider work to drain)",
                    state);
        }
    }

    public synchronized void completeProviderSwapchainRebuild(
            long generation
    ) {
        if (providerLifecycle.snapshot().state()
                == ProviderLifecycle.State.DRAINING) {
            providerLifecycle.ready(
                    generation,
                    "Provider ready for swapchain generation " + generation
            );
            return;
        }
        // C5: a transient failure (e.g. drain timeout) is retryable once the
        // generation advanced — re-detect on the new generation instead of
        // staying latched until an unrelated re-detection happens.
        if (generation > 0
                && providerLifecycle.isFailureTransient()
                && generation != providerLifecycle.failureGeneration()) {
            FrameGenerationCompatibilityLayer.ProviderId current =
                    providerLifecycle.snapshot().provider();
            if (current != FrameGenerationCompatibilityLayer.ProviderId.NONE) {
                providerLifecycle.detected(current,
                        "Retrying after transient failure on generation " + generation);
                providerLifecycle.ready(generation,
                        "Provider ready for swapchain generation " + generation);
            }
        }
    }

    public synchronized void markProviderFrameAccepted(
            FrameIdentity identity,
            String detail
    ) {
        ProviderLifecycle.State state =
                providerLifecycle.snapshot().state();
        if (state == ProviderLifecycle.State.READY
                || state == ProviderLifecycle.State.ACTIVE) {
            providerLifecycle.activated(identity, true, detail);
        }
    }

    public synchronized void failProvider(String detail) {
        providerLifecycle.failed(detail);
    }

    /** Transient variant for drain/rebuild timeouts (C5: retryable, see above). */
    public synchronized void failProviderTransient(String detail) {
        providerLifecycle.failedTransient(detail);
    }

    public synchronized void shutdownProvider() {
        providerLifecycle.shutdown(true);
    }

    public synchronized void selectBackendForVendorId(int detectedVendor) {
        // VkPhysicalDeviceProperties is authoritative. A configuration value
        // must never route a real NVIDIA/AMD/Intel device into another
        // vendor's native ABI.
        FrameGenerationBackend.GpuVendor vendor =
                FrameGenerationBackend.GpuVendor.fromVendorId(detectedVendor);
        FrameGenConfig config = FrameGenConfig.getInstance();
        if (vendor == FrameGenerationBackend.GpuVendor.UNKNOWN) {
            // C12: transient detection gap (e.g. device metadata not ready
            // yet). Apply in-memory only — a valid saved config must never be
            // destroyed by a momentary vendorId==0.
            config.retainFrameGenerationForVendor(vendor);
            activeBackend = new FallbackBackend();
            return;
        }
        boolean changed = config.retainFrameGenerationForVendor(vendor);
        if (!config.userHasChosenProvider
                && config.selectedFrameGenerationVendor()
                        == FrameGenerationBackend.GpuVendor.UNKNOWN
                && config.selectedUpscalerVendor()
                        == FrameGenerationBackend.GpuVendor.UNKNOWN) {
            // C1 first-run: no choice ever made and nothing active — enable the
            // detected vendor instead of leaving everything silently off.
            // setFrameGenerationEnabled also records the choice, so this runs once.
            config.setFrameGenerationEnabled(vendor, true);
            changed = true;
        }
        if (changed) {
            config.save();
        }
        activeBackend = switch (vendor) {
            case NVIDIA -> new NvidiaBackend();
            case AMD -> new AmdBackend();
            case INTEL -> new IntelBackend();
            default -> new FallbackBackend();
        };
    }

    public boolean isFrameGenerationSupported() { 
        return nativeLoadResult.loaded()
                && rendererDetection.allowsVulkanFeatures()
                && activeBackend.isSupported()
                && adapter.isFrameReadyForFG();
    }

    public String statusDetail() { 
        if (!nativeLoadResult.loaded()) {
            return "Native loader issue: " + nativeLoadResult.detail();
        }
        if (!rendererDetection.allowsVulkanFeatures()) {
            return rendererDetection.blockingReason();
        }
        if (!adapter.isFrameReadyForFG()) {
            return "Rendering backend does not support frame generation (" + adapter.getClass().getSimpleName() + ")";
        }
        String routeStatus = activeRoute().status();
        // C10: an active session fallback is part of the status, not just logs.
        String fallback = org.malionaro.dlss_frame_generation.client.render.RuntimeFallbackPolicy
                .lastDecisionDescription();
        return fallback.isEmpty() ? routeStatus : routeStatus + " | " + fallback;
    }
}
