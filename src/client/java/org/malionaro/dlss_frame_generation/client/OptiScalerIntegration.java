package org.malionaro.dlss_frame_generation.client;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Loads OptiScaler before FidelityFX creates its Vulkan contexts. The official
 * runtime is bundled in the mod JAR as a separate GPL program; a user-provided
 * installation in the config directory takes precedence.
 */
public final class OptiScalerIntegration {
    private static final String EMBEDDED_RESOURCE_ROOT =
            "/optiscaler/windows/";
    private static final String[] EMBEDDED_RUNTIME_FILES = {
            "OptiScaler.dll",
            "OptiScaler.ini",
            "amd_fidelityfx_dx12.dll",
            "amd_fidelityfx_upscaler_dx12.dll",
            "amd_fidelityfx_vk.dll",
            "libxess.dll",
            "D3D12_Optiscaler/D3D12Core.dll",
            "Licenses/DirectX_LICENSE.txt",
            "Licenses/FidelityFX_v1_LICENSE.md",
            "Licenses/FidelityFX_v2_LICENSE.md",
            "Licenses/XeSS_LICENSE.txt"
    };
    private static final Map<String, String> EMBEDDED_SHA256 = Map.ofEntries(
            Map.entry("OptiScaler.dll", "fbfb6676b829dad7e020fb830586a16aa0ec6add78016db48ef12e2ae1803231"),
            Map.entry("OptiScaler.ini", "6d7ed07e2e9df3d611b6f59047716bcbc0b67834296b8e4414816189b1233aa7"),
            Map.entry("amd_fidelityfx_dx12.dll", "e2d85aa05a9bd9ed8b38935fdf5199372cca6f74c12015143bb6f945ee1608aa"),
            Map.entry("amd_fidelityfx_upscaler_dx12.dll", "d0dcccc74a43c44ba435b7a369b456e0970d8a4464e4bd683119b374f2c9fb46"),
            Map.entry("amd_fidelityfx_vk.dll", "a1624cc4238fef046f30c4d80ce3f47be63fc5f5373f49e3ee9edb9960f54c78"),
            Map.entry("libxess.dll", "251659dd84a3e84de67c886a4186e01f3eca49b00641906fe38bb6b807e5d5b7"),
            Map.entry("D3D12_Optiscaler/D3D12Core.dll", "07d286c306f8117321422affd9e6388c12d0fb4be1c7fc689d9e899324feeb24"),
            Map.entry("Licenses/DirectX_LICENSE.txt", "5239850894610071566f7ecee0b751fde43c862032d92b99d7d0f596b3433ebd"),
            Map.entry("Licenses/FidelityFX_v1_LICENSE.md", "a335c2bb2bd28184b9a30b6e35b0c7e4aa7826abd1c388fc2aa02405e9d04f03"),
            Map.entry("Licenses/FidelityFX_v2_LICENSE.md", "f0da09d71ad5c82759a179e774535d4a829e5c96c49294167c1152402b2cb400"),
            Map.entry("Licenses/XeSS_LICENSE.txt", "784be42c39a4a4d03cf85d88e15e59d75828fca773ce45b8dbfffbd7eaf208df")
    );

    public enum State {
        DISABLED,
        NOT_APPLICABLE_VENDOR,
        UNSUPPORTED_PLATFORM,
        NOT_INSTALLED,
        LOADED,
        LOAD_FAILED
    }

    public record Status(State state, String detail, Path installDirectory) {
        public boolean loaded() {
            return state == State.LOADED;
        }
    }

    private static volatile Status status = new Status(
            State.DISABLED,
            "OptiScaler bridge is disabled",
            installDirectory()
    );

    private OptiScalerIntegration() {
    }

    public static Path installDirectory() {
        try {
            return FabricLoader.getInstance().getConfigDir()
                    .resolve("dlss_fg")
                    .resolve("optiscaler")
                    .toAbsolutePath()
                    .normalize();
        } catch (Throwable ignored) {
            return Path.of("build", "tmp", "dlss_fg", "optiscaler")
                    .toAbsolutePath()
                    .normalize();
        }
    }

    public static Status status() {
        return status;
    }

    public static synchronized Status loadConfiguredForVendor(
            FrameGenConfig config,
            FrameGenerationBackend.GpuVendor vendor,
            Logger logger
    ) {
        if (vendor != FrameGenerationBackend.GpuVendor.AMD
                && vendor != FrameGenerationBackend.GpuVendor.INTEL) {
            status = new Status(
                    State.NOT_APPLICABLE_VENDOR,
                    "OptiScaler is reserved for the AMD/Intel compatibility route",
                    installDirectory()
            );
            return status;
        }
        return loadConfigured(config, vendor, logger);
    }

    public static synchronized Status loadConfigured(
            FrameGenConfig config,
            Logger logger
    ) {
        return loadConfigured(
                config,
                FrameGenerationBackend.GpuVendor.UNKNOWN,
                logger
        );
    }

    private static Status loadConfigured(
            FrameGenConfig config,
            FrameGenerationBackend.GpuVendor vendor,
            Logger logger
    ) {
        Path directory = installDirectory();
        if (!config.optiScalerEnabled) {
            status = new Status(
                    State.DISABLED,
                    "OptiScaler bridge is disabled; enable it and restart Minecraft",
                    directory
            );
            return status;
        }

        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String architecture = System.getProperty("os.arch", "")
                .toLowerCase(Locale.ROOT);
        if (!os.contains("win") || !(architecture.contains("64")
                || architecture.equals("amd64"))) {
            status = new Status(
                    State.UNSUPPORTED_PLATFORM,
                    "OptiScaler integration currently requires 64-bit Windows",
                    directory
            );
            return status;
        }

        try {
            directory = prepareRuntimeDirectory(logger);
        } catch (IOException error) {
            status = new Status(
                    State.LOAD_FAILED,
                    "Embedded OptiScaler extraction failed: "
                            + error.getMessage(),
                    directory
            );
            logger.error("[OptiScaler] Could not extract embedded runtime",
                    error);
            return status;
        }

        Path dll = directory.resolve("OptiScaler.dll").normalize();
        if (!dll.getParent().equals(directory) || !Files.isRegularFile(dll)) {
            status = new Status(
                    State.NOT_INSTALLED,
                    "OptiScaler.dll is missing from " + directory,
                    directory
            );
            logger.warn("[OptiScaler] {}", status.detail());
            return status;
        }

        try {
            Files.createDirectories(directory.resolve("plugins"));
            ensureIntegrationConfiguration(directory, vendor);
            System.load(dll.toString());
            status = new Status(
                    State.LOADED,
                    "OptiScaler loaded; FidelityFX Vulkan calls can be intercepted",
                    directory
            );
            logger.info("[OptiScaler] Loaded optional bridge from {}", dll);
        } catch (IOException | UnsatisfiedLinkError | SecurityException error) {
            status = new Status(
                    State.LOAD_FAILED,
                    "OptiScaler load failed: " + error.getMessage(),
                    directory
            );
            logger.error("[OptiScaler] Could not load optional bridge", error);
        }
        return status;
    }

    private static Path prepareRuntimeDirectory(Logger logger)
            throws IOException {
        Path externalDirectory = installDirectory();
        if (Files.isRegularFile(
                externalDirectory.resolve("OptiScaler.dll"))) {
            logger.info(
                    "[OptiScaler] Using external override from {}",
                    externalDirectory
            );
            return externalDirectory;
        }

        String extractedNatives = NativeLoader.getExtractedNativesDir();
        if (extractedNatives.isBlank()) {
            return externalDirectory;
        }
        Path embeddedDirectory = Path.of(extractedNatives)
                .resolve("optiscaler")
                .toAbsolutePath()
                .normalize();
        extractEmbeddedBundle(embeddedDirectory);
        logger.info(
                "[OptiScaler] Extracted bundled runtime to {}",
                embeddedDirectory
        );
        return embeddedDirectory;
    }

    static void extractEmbeddedBundle(Path directory) throws IOException {
        Path normalizedDirectory = directory.toAbsolutePath().normalize();
        for (String relativeName : EMBEDDED_RUNTIME_FILES) {
            String resourceName = EMBEDDED_RESOURCE_ROOT + relativeName;
            try (var input =
                         OptiScalerIntegration.class.getResourceAsStream(
                                 resourceName)) {
                if (input == null) {
                    throw new IOException(
                            "Missing JAR resource " + resourceName);
                }
                Path target = normalizedDirectory
                        .resolve(relativeName)
                        .normalize();
                if (!target.startsWith(normalizedDirectory)) {
                    throw new IOException(
                            "Unsafe embedded OptiScaler path "
                                    + relativeName);
                }
                Files.createDirectories(target.getParent());
                Files.copy(
                        input,
                        target,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING
                );
                verifyEmbeddedHash(target, relativeName);
                target.toFile().deleteOnExit();
            }
        }
    }

    private static void verifyEmbeddedHash(Path target, String relativeName)
            throws IOException {
        String expected = EMBEDDED_SHA256.get(relativeName);
        if (expected == null) {
            throw new IOException(
                    "No trusted SHA-256 recorded for " + relativeName);
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String actual = HexFormat.of().formatHex(
                    digest.digest(Files.readAllBytes(target)));
            if (!expected.equals(actual)) {
                throw new IOException(
                        "SHA-256 mismatch for embedded OptiScaler file "
                                + relativeName);
            }
        } catch (NoSuchAlgorithmException impossible) {
            throw new IOException("SHA-256 is unavailable", impossible);
        }
    }

    static void ensureIntegrationConfiguration(Path directory)
            throws IOException {
        ensureIntegrationConfiguration(
                directory,
                FrameGenerationBackend.GpuVendor.UNKNOWN
        );
    }

    static void ensureIntegrationConfiguration(
            Path directory,
            FrameGenerationBackend.GpuVendor vendor
    ) throws IOException {
        Path ini = directory.resolve("OptiScaler.ini");
        String normalizedDirectory = directory.toAbsolutePath()
                .normalize()
                .toString();
        String normalizedPlugins = directory.resolve("plugins")
                .toAbsolutePath()
                .normalize()
                .toString();

        if (Files.exists(ini)) {
            String contents = Files.readString(ini);
            String configured = replaceAutoValue(
                    contents, "OptiDllPath", normalizedDirectory);
            String vulkanUpscaler = switch (vendor) {
                case AMD -> "fsr31";
                case INTEL -> "xess";
                default -> "auto";
            };
            configured = replaceAutoValue(
                    configured, "VulkanUpscaler", vulkanUpscaler);
            configured = replaceAutoValue(
                    configured, "Path", normalizedPlugins);
            configured = replaceAutoValue(
                    configured, "EnableFfxInputs", "true");
            configured = replaceAutoValue(
                    configured, "UseFfxInputs", "true");
            configured = replaceAutoValue(
                    configured, "StreamlineSpoofing", "false");
            configured = replaceAutoValue(configured, "Dxgi", "false");
            configured = replaceAutoValue(
                    configured, "OverrideNvapiDll", "false");
            configured = replaceAutoValue(
                    configured,
                    "DontUseFakenvapiForXeLLOnNvidia",
                    "true"
            );
            if (!configured.equals(contents)) {
                Files.writeString(ini, configured);
            }
            return;
        }

        String contents = """
                ; Generated by the Minecraft Vulkan Frame Generation mod.
                ; Existing files are never overwritten.
                [Upscalers]
                VulkanUpscaler=%s

                [Inputs]
                EnableFfxInputs=true
                UseFfxInputs=true

                [Libraries]
                OptiDllPath=%s

                [Plugins]
                Path=%s

                [Spoofing]
                StreamlineSpoofing=false
                Dxgi=false

                [NvApi]
                OverrideNvapiDll=false
                DontUseFakenvapiForXeLLOnNvidia=true
                """.formatted(
                        switch (vendor) {
                            case AMD -> "fsr31";
                            case INTEL -> "xess";
                            default -> "auto";
                        },
                        normalizedDirectory,
                        normalizedPlugins
                );
        Files.writeString(ini, contents);
    }

    private static String replaceAutoValue(
            String contents,
            String key,
            String value
    ) {
        Pattern line = Pattern.compile(
                "(?m)^(" + Pattern.quote(key) + ")=auto\\s*$");
        return line.matcher(contents)
                .replaceFirst(java.util.regex.Matcher.quoteReplacement(
                        key + "=" + value));
    }
}
