package org.malionaro.dlss_frame_generation.client.interop;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every bundled Streamline DLL must match the hash the loader trusts. Updating
 * the SDK without updating NativeLoader made every NVIDIA DLL get rejected at
 * startup, so the check lives in the build.
 */
class NativeRuntimeHashTest {
    private static final Path STREAMLINE_BIN = Path.of("native/streamline/bin/x64");

    @Test
    void bundledStreamlineDllsMatchTrustedHashes() throws Exception {
        if (!Files.isDirectory(STREAMLINE_BIN)) {
            return;
        }
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (Stream<Path> files = Files.list(STREAMLINE_BIN)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".dll")).toList()) {
                String name = file.getFileName().toString();
                String expected = NativeLoader.trustedRuntimeHashes().get(name);
                assertTrue(expected != null, name + " has no trusted hash in NativeLoader");
                String actual = HexFormat.of().formatHex(digest.digest(Files.readAllBytes(file)));
                assertEquals(expected, actual, name + " does not match its trusted hash");
            }
        }
    }
}
