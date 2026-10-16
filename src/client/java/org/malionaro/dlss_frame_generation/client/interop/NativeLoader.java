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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

public final class NativeLoader {
    private static final String LIBRARY_BASE_NAME = "dlss_fg_native";
    private static final Map<String, String> TRUSTED_RUNTIME_SHA256 =
            Map.ofEntries(
                    Map.entry("sl.interposer.dll", "8c87c9499461da561edd529aa9bf7831d67d7b94ebb1c1a5ed54ef4934e1ea4c"),
                    Map.entry("sl.common.dll", "82924a8954dd671e09351c5de0eb87ad0eb25b944cc9f9ab955ca1d9950de15d"),
                    Map.entry("sl.dlss_g.dll", "f4a6b2b14dcc0b1485989e430d3b4e3a44ac1800b92ba1ad74f476e64fb2b09c"),
                    Map.entry("sl.dlss.dll", "73bf52c0cfaa5900a8f3f4a91306e4625e7cca696dfb305aae44c9f97b582e1f"),
                    Map.entry("sl.dlss_d.dll", "026b9f4f9ea848f2f5aa0e2024fc6b1c885f57eea5d41078124dba14f873b86b"),
                    Map.entry("sl.reflex.dll", "0ce9725e3e03ea9e7f81d008b57f33ee365973d2e349131c8b1c3e3378fe2db0"),
                    Map.entry("sl.pcl.dll", "f13d51cfa05f4cd514df2026049e2db8adf359221713170ad386fd499915b582"),
                    Map.entry("nvngx_dlssg.dll", "ff6e90eb78b827927dff5b4ecc6b1c870c2e9bca29ed9f48c7d348cc9e170b82"),
                    Map.entry("nvngx_dlss.dll", "3975567b8943c53acce397f2b72380092f84f162d00b0d2c7d08a1025c563983"),
                    Map.entry("nvngx_dlssd.dll", "4bc7ea5fcb2f32cf86bc2cb072e8d2860914a0be511cbdcb2fc206c1d9d83b80"),
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
            List<String> rejected = new ArrayList<>();
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
                        targetPath.toFile().deleteOnExit();
                        try {
                            verifyTrustedRuntime(targetPath, dllName);
                            logger.info("[DLSS FG] Extracted Streamline runtime: {}", dllName);
                        } catch (IOException untrusted) {
                            // Fail closed for this file only. A rejected DLL is
                            // deleted so nothing downstream can load it, but it
                            // must not take the remaining runtimes with it: the
                            // whole point of the check is to keep a single
                            // tampered file from silently disabling the mod.
                            Files.deleteIfExists(targetPath);
                            rejected.add(dllName);
                            logger.error("[DLSS FG] Rejected {}: {}",
                                    dllName, untrusted.getMessage());
                        }
                    } else {
                        logger.debug("[DLSS FG] Optional DLL not bundled: {}", dllName);
                    }
                }
            }
            if (!rejected.isEmpty()) {
                logger.error("[DLSS FG] {} bundled runtime file(s) failed SHA-256 "
                                + "verification and were not loaded: {}",
                        rejected.size(), String.join(", ", rejected));
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
                // Without the interposer Streamline never sees the Vulkan
                // instance/device/surface lifecycle, so every feature that
                // needs interception reports eErrorFeatureNotSupported. Reflex
                // still reports support, which makes this look like a partial
                // or flaky failure rather than a missing runtime file.
                logger.error("[DLSS FG] sl.interposer.dll not available!"
                        + " DLSS Super Resolution, Frame Generation and Ray"
                        + " Reconstruction stay unsupported; only Reflex can work.");
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
            earlyLoadDone = true;
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

            // Extract the JNI bridge DLL, digesting the bundled bytes while
            // copying so the extracted file can be re-verified below.
            String bridgeDll = platform.libraryFileName();
            String resourcePath = "/natives/" + platform.resourceDirectory() + "/" + bridgeDll;
            String bundledDigest;
            try (InputStream input = NativeLoader.class.getResourceAsStream(resourcePath)) {
                if (input != null) {
                    Path targetPath = extractedDir.resolve(bridgeDll);
                    bundledDigest = extractWithDigest(input, targetPath);
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

            // Load the JNI bridge. B6: the bridge is built locally, so no fixed
            // SHA can be embedded — instead re-digest the extracted file
            // immediately before loading and fail closed unless it still
            // matches the bundled bytes (defeats truncation, partial writes
            // and temp-file replacement between extraction and load).
            Path nativeBridgePath = extractedDir.resolve(bridgeDll);
            verifyExtractedDigest(nativeBridgePath, bundledDigest, bridgeDll, logger);
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

    /**
     * B6: streaming copy that returns the SHA-256 of the bundled bytes as
     * {@code hex:byteCount}. No fixed hash is embedded for the locally built
     * bridge — identity is established against the JAR resource itself.
     */
    private static String extractWithDigest(InputStream input, Path target)
            throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long total = 0L;
            byte[] buffer = new byte[8192];
            try (var output = Files.newOutputStream(target,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
                    java.nio.file.StandardOpenOption.WRITE)) {
                int read;
                while ((read = input.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                    output.write(buffer, 0, read);
                    total += read;
                }
            }
            if (total == 0L) {
                throw new IOException("Bundled native bridge resource is empty: " + target);
            }
            return HexFormat.of().formatHex(digest.digest()) + ":" + total;
        } catch (NoSuchAlgorithmException impossible) {
            throw new IOException("SHA-256 is unavailable", impossible);
        }
    }

    /** B6: re-digest the extracted file immediately before loading. */
    private static void verifyExtractedDigest(
            Path file, String bundledDigest, String fileName, Logger logger)
            throws IOException {
        String actual;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long total = 0L;
            try (InputStream is = Files.newInputStream(file);
                 DigestInputStream dis = new DigestInputStream(is, digest)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = dis.read(buffer)) != -1) {
                    total += read;
                }
            }
            actual = HexFormat.of().formatHex(digest.digest()) + ":" + total;
        } catch (NoSuchAlgorithmException impossible) {
            throw new IOException("SHA-256 is unavailable", impossible);
        }
        if (!bundledDigest.equals(actual)) {
            String message = "Extracted native bridge " + fileName
                    + " no longer matches the bundled bytes (bundled=" + bundledDigest
                    + ", extracted=" + actual + "); refusing to load";
            logger.error("[DLSS FG] {}", message);
            throw new IOException(message);
        }
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
                                // B8: never traverse symlinks — a planted link
                                // named like our temp dir must not redirect
                                // deletion into foreign trees. A stale link
                                // itself is removed; anything else must be a
                                // real directory or it is left alone.
                                if (Files.isSymbolicLink(p)) {
                                    long linkTime = Files.getLastModifiedTime(
                                            p, java.nio.file.LinkOption.NOFOLLOW_LINKS).toMillis();
                                    if (linkTime < cutoffMillis) {
                                        Files.deleteIfExists(p);
                                    }
                                    return;
                                }
                                if (!Files.isDirectory(p)) {
                                    return;
                                }
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
