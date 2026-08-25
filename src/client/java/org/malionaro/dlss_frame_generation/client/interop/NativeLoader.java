package org.malionaro.dlss_frame_generation.client.interop;

import org.malionaro.dlss_frame_generation.client.StreamlineBridge;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

public final class NativeLoader {
    private static final String LIBRARY_BASE_NAME = "dlss_fg_native";
    private static final Map<String, String> TRUSTED_RUNTIME_SHA256 =
            Map.ofEntries(
                    Map.entry("sl.interposer.dll", "2a79db6857ae8c75bbd871a9489c48bc6a39f7fcc88b9b02afd53d0376cbec66"),
                    Map.entry("sl.common.dll", "c57930ef5a8a3fe9be85efdf71a61d8107c1148e8a6aed456464547128f7f4ae"),
                    Map.entry("sl.dlss_g.dll", "1fec3f8fdfc59d78c4445c276c1a0fb798bf251985f348597dc2b44d0c995e52"),
                    Map.entry("sl.dlss.dll", "a997022d2b93601e0eefc3ddb3067c36df386dd3163ae71e11095191fb14f8e4"),
                    Map.entry("sl.dlss_d.dll", "35e4f8fc8396a08514a4493b87ba507445f886dad4b7c6c024d3305824236be6"),
                    Map.entry("sl.reflex.dll", "7e6e4ccc4b561bd449fb0da90709d9b96b08c3f6f4697362caaa359e72a58a67"),
                    Map.entry("sl.pcl.dll", "699ab461e64e95189a7fe6a21c79ad237cf56b60ea748cb6c840cd5431ba91d1"),
                    Map.entry("nvngx_dlssg.dll", "135eaf0733c1e37381a8c28abcf7a862404a54132b81787c04e35d09efc5e36f"),
                    Map.entry("nvngx_dlss.dll", "be6e434a94ca32499515eb62ca0e6c274526055d568d0426e4c652dcdfb6ee6e"),
                    Map.entry("nvngx_dlssd.dll", "f4e97624f70fbb769acb11ebd751b512ecc9463d4bd6aef04896d3956e6084a0"),
                    Map.entry("NvLowLatencyVk.dll", "2a77dc3e1c724b7eea5755be0ae7423752e79a2459fae72181a9f00e3507e5d6"),
                    Map.entry("amd_fidelityfx_vk.dll", "a1624cc4238fef046f30c4d80ce3f47be63fc5f5373f49e3ee9edb9960f54c78")
            );
    private static Path extractedDir;
    private static boolean earlyLoadDone = false;

    private NativeLoader() {
    }

    /**
     * Early-load phase: Extract and load the Streamline interposer DLL BEFORE
     * Vulkan initialization. This is critical because Streamline needs to intercept
     * vkCreateInstance/vkCreateDevice calls.
     *
     * Called from VulkanDeviceMixin or a Mixin plugin as early as possible.
     */
    public static synchronized void earlyLoadInterposer(Logger logger) {
        if (earlyLoadDone) return;

        Platform platform = Platform.current();
        if (platform != Platform.WINDOWS) {
            logger.info("[DLSS FG] Streamline is only supported on Windows. Skipping early interposer load.");
            earlyLoadDone = true;
            return;
        }

        try {
            cleanupOldTempDirs(logger);
            // Create extraction directory early
            extractedDir = Files.createTempDirectory("dlss_fg_natives-");
            extractedDir.toFile().deleteOnExit();

            // Extract ALL Streamline DLLs needed at runtime
            // Order matters: interposer first, then common, then plugins, then NVIDIA NGX binaries
            String[] runtimeDlls = {
                "sl.interposer.dll",   // CRITICAL: Must be present before Vulkan init
                "sl.common.dll",       // Core Streamline runtime
                "sl.dlss_g.dll",       // DLSS Frame Generation plugin
                "sl.dlss.dll",         // DLSS Super Resolution plugin
                "sl.dlss_d.dll",       // DLSS Ray Reconstruction plugin
                "sl.reflex.dll",       // NVIDIA Reflex plugin
                "sl.pcl.dll",          // PC Latency stats plugin
                "nvngx_dlssg.dll",     // NVIDIA NGX DLSS-G binary (Frame Gen)
                "nvngx_dlss.dll",      // NVIDIA NGX DLSS binary (Super Resolution)
                "nvngx_dlssd.dll",     // NVIDIA NGX DLSS Ray Reconstruction binary
                "NvLowLatencyVk.dll",  // Vulkan low-latency layer
                "amd_fidelityfx_vk.dll", // AMD FSR 3.1 Vulkan proxy swapchain runtime
            };

            for (String dllName : runtimeDlls) {
                String resourcePath = "/natives/" + platform.resourceDirectory() + "/" + dllName;
                try (InputStream input = NativeLoader.class.getResourceAsStream(resourcePath)) {
                    if (input != null) {
                        Path targetPath = extractedDir.resolve(dllName);
                        Files.copy(input, targetPath, StandardCopyOption.REPLACE_EXISTING);
                        verifyTrustedRuntime(targetPath, dllName);
                        targetPath.toFile().deleteOnExit();

                        logger.info("[DLSS FG] Extracted Streamline runtime: {}", dllName);
                    } else {
                        logger.debug("[DLSS FG] Optional DLL not bundled: {}", dllName);
                    }
                }
            }

            // Pre-load the interposer so Streamline can hook Vulkan API calls
            Path interposerPath = extractedDir.resolve("sl.interposer.dll");
            if (Files.exists(interposerPath)) {
                String configuredVulkanLibrary =
                        System.getProperty("org.lwjgl.vulkan.libname", "").trim();
                if (configuredVulkanLibrary.isEmpty()) {
                    // LWJGL resolves every Vulkan entry point through this
                    // library. Pointing it at the Streamline interposer before
                    // org.lwjgl.vulkan.VK is initialized gives Streamline the
                    // complete instance/device/surface/swapchain lifecycle,
                    // including vkDeviceWaitIdle and GLFW-created Win32
                    // surfaces.
                    System.setProperty(
                            "org.lwjgl.vulkan.libname",
                            interposerPath.toAbsolutePath().toString()
                    );
                    logger.info(
                            "[DLSS FG] Configured LWJGL Vulkan loader to use the Streamline interposer"
                    );
                } else if (!Path.of(configuredVulkanLibrary)
                        .toAbsolutePath()
                        .normalize()
                        .equals(interposerPath.toAbsolutePath().normalize())) {
                    logger.warn(
                            "[DLSS FG] org.lwjgl.vulkan.libname is already set to {}; "
                                    + "automatic Streamline Vulkan hooking may be unavailable",
                            configuredVulkanLibrary
                    );
                }
                System.load(interposerPath.toAbsolutePath().toString());
                logger.info("[DLSS FG] Loaded sl.interposer.dll (Streamline Vulkan hook layer)");
            } else {
                logger.warn("[DLSS FG] sl.interposer.dll not found! Frame Generation will NOT work.");
            }

            // Pre-load sl.common.dll (core runtime needed by all plugins)
            Path slCommonPath = extractedDir.resolve("sl.common.dll");
            if (Files.exists(slCommonPath)) {
                System.load(slCommonPath.toAbsolutePath().toString());
                logger.info("[DLSS FG] Pre-loaded sl.common.dll");
            }

            earlyLoadDone = true;
        } catch (IOException | UnsatisfiedLinkError | SecurityException e) {
            logger.warn("[DLSS FG] Early interposer load failed: {}", e.toString());
            // Leave earlyLoadDone false so a later initialization phase can retry.
        }
    }

    /**
     * Main load phase: Load the JNI bridge DLL (dlss_fg_native.dll).
     * Called during mod initialization after earlyLoadInterposer has run.
     */
    public static NativeLoadResult load(Logger logger) {
        Platform platform = Platform.current();
        if (platform == null) {
            return NativeLoadResult.failure("Unsupported operating system");
        }

        // Ensure early load ran
        earlyLoadInterposer(logger);

        try {
            // Ensure extraction directory exists
            if (extractedDir == null) {
                cleanupOldTempDirs(logger);
                extractedDir = Files.createTempDirectory("dlss_fg_natives-");
                extractedDir.toFile().deleteOnExit();
            }

            // Extract the JNI bridge DLL
            String bridgeDll = platform.libraryFileName();
            String resourcePath = "/natives/" + platform.resourceDirectory() + "/" + bridgeDll;
            try (InputStream input = NativeLoader.class.getResourceAsStream(resourcePath)) {
                if (input != null) {
                    Path targetPath = extractedDir.resolve(bridgeDll);
                    Files.copy(input, targetPath, StandardCopyOption.REPLACE_EXISTING);
                    targetPath.toFile().deleteOnExit();
                    logger.info("[DLSS FG] Extracted JNI bridge: {}", bridgeDll);
                } else {
                    return NativeLoadResult.failure("Native bridge is not bundled for " + platform.resourceDirectory());
                }
            }

            // Pre-load dependent Streamline DLLs into process memory before loading JNI bridge
            String[] bridgeDeps = { "sl.interposer.dll", "sl.common.dll" };
            for (String dep : bridgeDeps) {
                Path depPath = extractedDir.resolve(dep);
                if (Files.exists(depPath)) {
                    try {
                        System.load(depPath.toAbsolutePath().toString());
                    } catch (UnsatisfiedLinkError alreadyLoaded) {
                        // Loading the same absolute DLL twice is harmless for the
                        // current class loader. Other linker errors are reported
                        // when the JNI bridge itself is loaded below.
                        logger.debug("[DLSS FG] Dependency {} already loaded: {}", dep, alreadyLoaded.getMessage());
                    }
                }
            }

            // Load the JNI bridge
            Path nativeBridgePath = extractedDir.resolve(bridgeDll);
            System.load(nativeBridgePath.toAbsolutePath().toString());
            logger.info("[DLSS FG] Loaded JNI bridge from {}", nativeBridgePath);

            // ABI handshake: a stale DLL may lack symbols Java declares, which
            // previously crashed the JVM with NULL JNI function pointers. Probe
            // a version export first and fail closed on any mismatch.
            try {
                int abi = StreamlineBridge.nGetNativeAbiVersion();
                if (abi != StreamlineBridge.NATIVE_ABI_VERSION) {
                    String message = "Native bridge ABI mismatch: DLL reports " + abi
                            + ", mod expects " + StreamlineBridge.NATIVE_ABI_VERSION
                            + ". Delete old dlss_fg_native.dll / reinstall the mod JAR.";
                    logger.error("[DLSS FG] {}", message);
                    return NativeLoadResult.failure(message);
                }
                logger.info("[DLSS FG] Native bridge ABI version {} verified", abi);
            } catch (UnsatisfiedLinkError | RuntimeException exception) {
                String message = "Native bridge does not report an ABI version"
                        + " (stale dlss_fg_native.dll?). Delete old dlss_fg_native.dll"
                        + " / reinstall the mod JAR. Cause: "
                        + exception.getClass().getSimpleName() + ": " + exception.getMessage();
                logger.error("[DLSS FG] {}", message);
                return NativeLoadResult.failure(message);
            }

            return NativeLoadResult.success();
        } catch (IOException | UnsatisfiedLinkError | SecurityException exception) {
            logger.warn("[DLSS FG] Native bridge unavailable: {}", exception.toString());
            return NativeLoadResult.failure(exception.getClass().getSimpleName() + ": " + exception.getMessage());
        }
    }

    public static String getExtractedNativesDir() {
        return extractedDir != null ? extractedDir.toAbsolutePath().toString() : "";
    }

    private static void verifyTrustedRuntime(Path file, String fileName)
            throws IOException {
        String expected = TRUSTED_RUNTIME_SHA256.get(fileName);
        if (expected == null) {
            throw new IOException(
                    "No trusted SHA-256 recorded for " + fileName);
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream is = Files.newInputStream(file);
                 DigestInputStream dis = new DigestInputStream(is, digest)) {
                byte[] buffer = new byte[8192];
                while (dis.read(buffer) != -1) {
                    // streaming through DigestInputStream updates digest incrementally
                }
            }
            String actual = HexFormat.of().formatHex(digest.digest());
            if (!expected.equals(actual)) {
                throw new IOException(
                        "SHA-256 mismatch for bundled native runtime "
                                + fileName);
            }
        } catch (NoSuchAlgorithmException impossible) {
            throw new IOException("SHA-256 is unavailable", impossible);
        }
    }

    private static void cleanupOldTempDirs(Logger logger) {
        try {
            Path tmp = Path.of(System.getProperty("java.io.tmpdir", "."));
            long cutoffMillis = System.currentTimeMillis() - 24L * 60L * 60L * 1000L;
            if (!Files.isDirectory(tmp)) return;
            try (Stream<Path> stream = Files.list(tmp)) {
                stream.filter(p -> p.getFileName().toString().startsWith("dlss_fg_natives-"))
                        .forEach(p -> {
                            try {
                                long lastMod = Files.getLastModifiedTime(p).toMillis();
                                if (lastMod < cutoffMillis) {
                                    try (Stream<Path> walk = Files.walk(p)) {
                                        walk.sorted(Comparator.reverseOrder())
                                                .forEach(path -> {
                                                    try {
                                                        Files.deleteIfExists(path);
                                                    } catch (IOException ignored) {
                                                    }
                                                });
                                    }
                                    if (logger != null) {
                                        logger.info("[DLSS FG] Cleaned old temp dir {}", p);
                                    }
                                }
                            } catch (IOException ignored) {
                            }
                        });
            }
        } catch (Exception ignored) {
        }
    }

    public record NativeLoadResult(boolean loaded, String detail) {
        public static NativeLoadResult success() {
            return new NativeLoadResult(true, "loaded");
        }

        public static NativeLoadResult failure(String detail) {
            return new NativeLoadResult(false, detail);
        }
    }

    private enum Platform {
        WINDOWS("windows", ".dll"),
        LINUX("linux", ".so"),
        MACOS("macos", ".dylib");

        private final String resourceDirectory;
        private final String fileSuffix;

        Platform(String resourceDirectory, String fileSuffix) {
            this.resourceDirectory = resourceDirectory;
            this.fileSuffix = fileSuffix;
        }

        static Platform current() {
            String name = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            if (name.contains("win")) return WINDOWS;
            if (name.contains("linux")) return LINUX;
            if (name.contains("mac") || name.contains("darwin")) return MACOS;
            return null;
        }

        String resourceDirectory() { return resourceDirectory; }
        String fileSuffix() { return fileSuffix; }
        String libraryFileName() { return this == WINDOWS ? LIBRARY_BASE_NAME + fileSuffix : "lib" + LIBRARY_BASE_NAME + fileSuffix; }
    }
}
