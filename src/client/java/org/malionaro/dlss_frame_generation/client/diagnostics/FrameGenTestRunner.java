package org.malionaro.dlss_frame_generation.client.diagnostics;
import org.malionaro.dlss_frame_generation.client.compat.ShaderPipelineState;

import net.fabricmc.loader.api.FabricLoader;
import org.malionaro.dlss_frame_generation.client.diagnostics.BackendStatusFormatter;
import org.malionaro.dlss_frame_generation.client.compat.CompatibilityManager;
import org.malionaro.dlss_frame_generation.client.interop.D3D12PresentationBridge;
import org.malionaro.dlss_frame_generation.client.interop.D3D12ProviderRuntimeAudit;
import org.malionaro.dlss_frame_generation.client.config.FrameGenConfig;
import org.malionaro.dlss_frame_generation.client.FrameGenMod;
import org.malionaro.dlss_frame_generation.client.frame.FrameGuideConventions;
import org.malionaro.dlss_frame_generation.client.backend.FrameGenerationBackend;
import org.malionaro.dlss_frame_generation.client.backend.FrameGenerationCompatibilityLayer;
import org.malionaro.dlss_frame_generation.client.frame.FrameIdentity;
import org.malionaro.dlss_frame_generation.client.frame.FrameResourceSet;
import org.malionaro.dlss_frame_generation.client.backend.ProviderLifecycle;
import org.malionaro.dlss_frame_generation.client.compat.RendererDetection;
import org.malionaro.dlss_frame_generation.client.StreamlineBridge;
import org.malionaro.dlss_frame_generation.client.render.VulkanRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.lwjgl.vulkan.VK10.VK_VERSION_MAJOR;
import static org.lwjgl.vulkan.VK10.VK_VERSION_MINOR;
import static org.lwjgl.vulkan.VK10.VK_VERSION_PATCH;

/**
 * Read-only runtime diagnostics.
 *
 * <p>This is intentionally not a collection of synthetic algorithm unit
 * tests. PASS means that the named runtime condition was observed. INFO and
 * SKIPPED never contribute to the green pass count. End-to-end validation
 * additionally requires a native provider to accept a renderer-owned frame
 * and the corresponding presentation path to complete.</p>
 */
public final class FrameGenTestRunner {
    private static final Logger LOGGER =
            LoggerFactory.getLogger("dlss_fg.diagnostics");

    public enum Outcome {
        PASS,
        FAIL,
        INFO,
        SKIPPED
    }

    public record BackendCheck(
            FrameGenerationBackend.GpuVendor vendor,
            int capabilities,
            String nativeStatus,
            boolean selected
    ) {
    }

    public record TestResult(
            String name,
            Outcome outcome,
            String message,
            List<BackendCheck> backendChecks
    ) {
        public TestResult {
            outcome = outcome == null ? Outcome.FAIL : outcome;
            message = message == null ? "" : message;
            backendChecks = List.copyOf(backendChecks);
        }

        public TestResult(
                String name,
                Outcome outcome,
                String message
        ) {
            this(name, outcome, message, List.of());
        }

        public boolean passed() {
            return outcome == Outcome.PASS;
        }

        public boolean failed() {
            return outcome == Outcome.FAIL;
        }
    }

    private FrameGenTestRunner() {
    }

    public static List<TestResult> runAllDiagnostics() {
        List<TestResult> results = new ArrayList<>();
        CompatibilityManager compatibility = FrameGenMod.compatibilityManager();

        results.add(environmentResult(compatibility));
        results.add(rendererResult(compatibility));
        results.add(nativeProviderResult(compatibility));
        results.add(resourceResult());
        results.add(guideConventionResult());
        results.add(d3d12Result());
        results.add(providerRuntimeFilesResult());
        results.add(adaptiveRenderingResult());
        results.add(endToEndResult(compatibility));

        for (TestResult result : results) {
            LOGGER.info(
                    "[DIAGNOSTIC] [{}] {} - {}",
                    result.outcome(),
                    result.name(),
                    result.message()
            );
        }
        return List.copyOf(results);
    }

    /**
     * Green hardware validation is deliberately stricter than "no failures".
     */
    public static boolean hardwarePathValidated(
            List<TestResult> results
    ) {
        return results.stream().anyMatch(result ->
                result.name().equals("End-to-end frame generation")
                        && result.outcome() == Outcome.PASS);
    }

    private static TestResult environmentResult(
            CompatibilityManager compatibility
    ) {
        String minecraftVersion = modVersion("minecraft");
        String modVersion = modVersion(FrameGenMod.MOD_ID);
        if (compatibility == null) {
            return new TestResult(
                    "Runtime environment",
                    Outcome.INFO,
                    "Minecraft=" + minecraftVersion
                            + ", mod=" + modVersion
                            + "; Fabric client initialization has not run "
                            + "(normal in the isolated unit-test JVM)"
            );
        }
        RendererDetection detection = compatibility.rendererDetection();
        String apiVersion = apiVersion(
                VulkanRegistry.getPhysicalDeviceApiVersion());
        return new TestResult(
                "Runtime environment",
                Outcome.INFO,
                "Minecraft=" + minecraftVersion
                        + ", mod=" + modVersion
                        + ", Sodium=" + versionOrAbsent(
                                detection.sodiumPresent(),
                                detection.sodiumVersion())
                        + ", Iris=" + versionOrAbsent(
                                detection.irisPresent(),
                                detection.irisVersion())
                        + ", Aperture=" + versionOrAbsent(
                                detection.aperturePresent(),
                                detection.apertureVersion())
                        + " | GPU=" + VulkanRegistry.getPhysicalDeviceName()
                        + " (vendor=0x"
                        + Integer.toHexString(
                                VulkanRegistry.getPhysicalDeviceVendorId())
                        + "), Vulkan=" + apiVersion
                        + ", LUID=" + valueOrUnavailable(
                                D3D12PresentationBridge.status().adapterLuid())
        );
    }

    private static TestResult rendererResult(
            CompatibilityManager compatibility
    ) {
        if (compatibility == null) {
            return new TestResult(
                    "Renderer integration",
                    Outcome.INFO,
                    "Renderer integration is not initialized"
            );
        }
        RendererDetection detection = compatibility.rendererDetection();
        boolean allowed = detection.allowsVulkanFeatures();
        String detail = "backend=" + detection.backend()
                + ", GpuDevice=" + detection.gpuDeviceClass()
                + ", Sodium draw backend="
                + valueOrUnavailable(detection.sodiumDrawBackend())
                + ", shader state=" + detection.shaderPipelineState();
        if (!allowed) {
            detail += " | blocked: " + detection.blockingReason();
        }
        return new TestResult(
                "Renderer integration",
                allowed ? Outcome.PASS : Outcome.FAIL,
                detail
        );
    }

    private static TestResult nativeProviderResult(
            CompatibilityManager compatibility
    ) {
        List<BackendCheck> checks = new ArrayList<>();
        try {
            FrameGenerationCompatibilityLayer.ProviderId activeProvider =
                    FrameGenerationCompatibilityLayer.ProviderId.fromNativeId(
                            StreamlineBridge.nGetActiveProviderId());
            FrameGenerationBackend.GpuVendor activeVendor =
                    vendorForProvider(activeProvider);
            int nativeStateOrdinal =
                    StreamlineBridge.nGetProviderLifecycleState();
            ProviderLifecycle.State nativeState =
                    providerState(nativeStateOrdinal);
            String nativeDetail =
                    StreamlineBridge.nGetProviderLifecycleDetail();

            for (FrameGenerationBackend.GpuVendor vendor :
                    List.of(
                            FrameGenerationBackend.GpuVendor.NVIDIA,
                            FrameGenerationBackend.GpuVendor.AMD,
                            FrameGenerationBackend.GpuVendor.INTEL
                    )) {
                int capabilities =
                        vendor == FrameGenerationBackend.GpuVendor.NVIDIA
                                ? StreamlineBridge
                                        .nGetBackendSupportedCapabilities(
                                                vendor.vendorId())
                                : StreamlineBridge.nGetBackendCapabilities(
                                        vendor.vendorId());
                checks.add(new BackendCheck(
                        vendor,
                        capabilities,
                        StreamlineBridge.nGetBackendStatus(vendor.vendorId()),
                        vendor == activeVendor
                ));
            }

            boolean usable = checks.stream()
                    .filter(BackendCheck::selected)
                    .anyMatch(check -> BackendStatusFormatter.isUsable(
                            check.nativeStatus(),
                            check.capabilities() != 0));
            Outcome outcome;
            if ((nativeState == ProviderLifecycle.State.READY
                    || nativeState == ProviderLifecycle.State.ACTIVE)
                    && activeProvider
                    != FrameGenerationCompatibilityLayer.ProviderId.NONE
                    && usable) {
                outcome = Outcome.PASS;
            } else if (nativeState == ProviderLifecycle.State.FAILED) {
                outcome = Outcome.FAIL;
            } else {
                outcome = Outcome.INFO;
            }

            String javaState = compatibility == null
                    ? "unavailable"
                    : compatibility.providerSnapshot().state()
                            + " ("
                            + compatibility.providerSnapshot().detail()
                            + ")";
            return new TestResult(
                    "Native provider lifecycle",
                    outcome,
                    "provider=" + activeProvider.displayName()
                            + ", native=" + nativeState
                            + " (" + nativeDetail + ")"
                            + ", Java=" + javaState,
                    checks
            );
        } catch (Throwable error) {
            return new TestResult(
                    "Native provider lifecycle",
                    Outcome.INFO,
                    "Native bridge is not available in this process: "
                            + errorMessage(error),
                    checks
            );
        }
    }

    private static TestResult resourceResult() {
        long captured = VulkanRegistry.getCapturedFrameCount();
        long accepted = VulkanRegistry.getProviderAcceptedFrameCount();
        FrameIdentity capturedIdentity =
                VulkanRegistry.getLastCapturedFrameIdentity();
        FrameIdentity acceptedIdentity =
                VulkanRegistry.getLastAcceptedFrameIdentity();
        FrameResourceSet current = VulkanRegistry.getCurrentFrameResources();
        String rejection = VulkanRegistry.getLastFrameResourceRejection();

        Outcome outcome;
        if (captured > 0L
                && capturedIdentity != null
                && capturedIdentity.isValid()
                && capturedIdentity.swapchainGeneration()
                == VulkanRegistry.getSwapchainGeneration()) {
            outcome = Outcome.PASS;
        } else if (captured > 0L) {
            outcome = Outcome.FAIL;
        } else {
            outcome = Outcome.INFO;
        }

        String currentState = current == null
                ? "none"
                : current.validate().accepted()
                        ? current.identity().toString()
                        : "invalid: " + current.validate().reason();
        return new TestResult(
                "Atomic Vulkan frame resources",
                outcome,
                "swapchain generation="
                        + VulkanRegistry.getSwapchainGeneration()
                        + ", captured=" + captured
                        + ", accepted=" + accepted
                        + ", current=" + currentState
                        + ", last captured=" + capturedIdentity
                        + ", last accepted=" + acceptedIdentity
                        + " | last rejection=" + rejection
        );
    }

    private static TestResult d3d12Result() {
        D3D12PresentationBridge.Status status =
                D3D12PresentationBridge.status();
        Outcome outcome = status.presentationReady()
                ? Outcome.PASS
                : status.stage() == D3D12PresentationBridge.Stage.FAILED
                        ? Outcome.INFO
                        : Outcome.INFO;
        return new TestResult(
                "Hybrid Vulkan to D3D12 presentation",
                outcome,
                "stage=" + status.stage()
                        + ", capabilities=0x"
                        + Integer.toHexString(status.capabilities())
                        + ", LUID="
                        + valueOrUnavailable(status.adapterLuid())
                        + " | " + status.detail()
                        + (status.presentationReady()
                                ? ""
                                : " | DXGI presentation and zero-copy frame "
                                        + "transport are not active")
        );
    }

    private static TestResult guideConventionResult() {
        FrameGuideConventions conventions =
                VulkanRegistry.getLastGuideConventions();
        if (conventions == null) {
            return new TestResult(
                    "Depth and motion conventions",
                    Outcome.INFO,
                    "No in-world guide-buffer frame has been captured yet"
            );
        }
        FrameGuideConventions.Validation validation =
                conventions.validate();
        return new TestResult(
                "Depth and motion conventions",
                validation.accepted() ? Outcome.PASS : Outcome.FAIL,
                conventions.summary()
                        + (validation.accepted()
                                ? ""
                                : " | rejected: " + validation.reason())
        );
    }

    private static TestResult providerRuntimeFilesResult() {
        var fsr4 = D3D12ProviderRuntimeAudit.fsr4FrameGeneration();
        var xess = D3D12ProviderRuntimeAudit.xessFrameGeneration();
        boolean complete = fsr4.complete() || xess.complete();
        return new TestResult(
                "D3D12 provider runtimes",
                complete ? Outcome.INFO : Outcome.SKIPPED,
                fsr4.provider() + ": " + fsr4.detail()
                        + " | " + xess.provider() + ": " + xess.detail()
                        + " | An upscaler DLL alone does not provide frame "
                        + "generation"
        );
    }

    private static TestResult adaptiveRenderingResult() {
        FrameGenConfig config = FrameGenConfig.getInstance();
        var fallback = VulkanRegistry.runtimeFallbackDecision();
        boolean configured = config.motionBlurEnabled
                || config.drsEnabled
                || config.autoFallbackChainEnabled;
        return new TestResult(
                "Adaptive rendering",
                configured ? Outcome.INFO : Outcome.SKIPPED,
                "motion blur=" + onOff(config.motionBlurEnabled)
                        + ", DRS=" + onOff(config.drsEnabled)
                        + " (scale="
                        + String.format(
                                Locale.ROOT,
                                "%.3f",
                                VulkanRegistry.dynamicResolutionScale())
                        + ", smoothed="
                        + String.format(
                                Locale.ROOT,
                                "%.2f ms",
                                VulkanRegistry.dynamicResolutionFrameTimeMs())
                        + ", target=" + config.targetFps + " FPS)"
                        + ", safe fallback="
                        + (fallback.fallbackActive()
                                ? "ACTIVE: " + fallback.detail()
                                : onOff(config.autoFallbackChainEnabled))
        );
    }

    private static TestResult endToEndResult(
            CompatibilityManager compatibility
    ) {
        long captured = VulkanRegistry.getCapturedFrameCount();
        long accepted = VulkanRegistry.getProviderAcceptedFrameCount();
        long presented = VulkanRegistry.getCompletedPresentCount();
        boolean configured = compatibility != null
                && compatibility.activeVendorBackend() != null
                && FrameGenConfig.getInstance().isFrameGenerationEnabled(
                        compatibility.activeVendorBackend().vendor());
        ProviderLifecycle.State lifecycle = compatibility == null
                ? ProviderLifecycle.State.UNAVAILABLE
                : compatibility.providerSnapshot().state();
        var fallback = VulkanRegistry.runtimeFallbackDecision();

        // Inputs being accepted is NOT proof that frames are generated. Only
        // the native runtime flag (dlssgRuntimeReady / equivalent) proves the
        // provider actually interpolates. Without this the end-to-end check
        // reported PASS while the provider was still READY.
        FrameGenerationBackend backend = compatibility == null
                ? null
                : compatibility.activeVendorBackend();
        boolean runtimeActive = false;
        String runtimeDetail = "native runtime state unavailable";
        if (backend != null) {
            try {
                runtimeActive = backend.isFrameGenerationActive();
                runtimeDetail = backend.statusDescription();
            } catch (UnsatisfiedLinkError ignored) {
                runtimeDetail = "native bridge unavailable";
            }
        }

        Outcome outcome;
        String prefix;
        if (fallback.fallbackActive()) {
            outcome = Outcome.SKIPPED;
            prefix = fallback.detail();
        } else if (!configured) {
            outcome = Outcome.SKIPPED;
            prefix = "Frame generation is disabled for the detected GPU";
        } else if (lifecycle == ProviderLifecycle.State.ACTIVE
                && accepted > 0L && presented > 0L
                && runtimeActive) {
            outcome = Outcome.PASS;
            prefix = "Real renderer inputs were accepted and presentation "
                    + "completed in this session";
        } else if (lifecycle == ProviderLifecycle.State.ACTIVE
                && accepted > 0L && presented > 0L
                && !runtimeActive) {
            // Inputs flow but the provider never reached runtime: this is the
            // exact state that used to be reported as a green PASS.
            outcome = Outcome.FAIL;
            prefix = "Renderer inputs are accepted, but the provider never "
                    + "became runtime-active, so no frames are generated";
        } else if (captured > 0L
                && (accepted == 0L || presented == 0L)) {
            outcome = Outcome.FAIL;
            prefix = "The configured hardware path did not complete";
        } else {
            outcome = Outcome.INFO;
            prefix = "No complete in-world hardware frame has been observed "
                    + "yet";
        }
        return new TestResult(
                "End-to-end frame generation",
                outcome,
                prefix + " | lifecycle=" + lifecycle
                        + ", captured=" + captured
                        + ", provider accepted=" + accepted
                        + ", present completed=" + presented
                        + ", runtime="
                        + (runtimeActive ? "ACTIVE" : "NOT ACTIVE")
                        + ", native=\"" + runtimeDetail + "\""
                        + ", pipeline="
                        + VulkanRegistry.getFrameGenerationPipelineStatus()
        );
    }

    private static FrameGenerationBackend.GpuVendor vendorForProvider(
            FrameGenerationCompatibilityLayer.ProviderId provider
    ) {
        return switch (provider) {
            case NVIDIA_STREAMLINE_VULKAN ->
                    FrameGenerationBackend.GpuVendor.NVIDIA;
            case AMD_FSR31_VULKAN, AMD_FSR4_D3D12 ->
                    FrameGenerationBackend.GpuVendor.AMD;
            case INTEL_XESS_D3D12, CROSS_VENDOR_FSR31_VULKAN ->
                    FrameGenerationBackend.GpuVendor.INTEL;
            case NONE -> FrameGenerationBackend.GpuVendor.UNKNOWN;
        };
    }

    private static ProviderLifecycle.State providerState(int ordinal) {
        ProviderLifecycle.State[] values = ProviderLifecycle.State.values();
        return ordinal >= 0 && ordinal < values.length
                ? values[ordinal]
                : ProviderLifecycle.State.FAILED;
    }

    private static String modVersion(String id) {
        try {
            return FabricLoader.getInstance().getModContainer(id)
                    .map(container -> container.getMetadata()
                            .getVersion().getFriendlyString())
                    .orElse("not installed");
        } catch (Throwable ignored) {
            return "unavailable";
        }
    }

    private static String versionOrAbsent(
            boolean present,
            String version
    ) {
        return present ? valueOrUnavailable(version) : "not installed";
    }

    private static String valueOrUnavailable(String value) {
        return value == null || value.isBlank() ? "unavailable" : value;
    }

    private static String onOff(boolean value) {
        return value ? "on" : "off";
    }

    private static String apiVersion(int version) {
        if (version == 0) {
            return "unavailable";
        }
        return VK_VERSION_MAJOR(version) + "."
                + VK_VERSION_MINOR(version) + "."
                + VK_VERSION_PATCH(version);
    }

    private static String errorMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank()
                ? error.getClass().getSimpleName()
                : message;
    }
}
