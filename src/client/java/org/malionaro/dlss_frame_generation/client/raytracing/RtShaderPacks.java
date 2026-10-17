package org.malionaro.dlss_frame_generation.client.raytracing;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * RT shader packs for our own RT engine, loaded from {@code rtshaderpacks/}
 * in the game folder. Separate from Iris: Iris keeps loading OptiFine-style
 * packs from {@code shaderpacks/}; with an Iris pack active our RT is off.
 *
 * <p>A pack is a folder or a .zip whose root (or single top folder) holds
 * {@value #ENTRY}, a compute shader with the same bindings and frame block
 * as the built-in one. The folder {@value #TEMPLATE} is a copy of the
 * built-in shader to start from.</p>
 */
public final class RtShaderPacks {
    public static final String BUILT_IN = "";
    public static final String ENTRY = "hybrid_ray_query.comp";
    public static final String TEMPLATE = "template";
    static final String BUILT_IN_RESOURCE = "/assets/dlss_fg/shaders/source/raytracing/" + ENTRY;

    public record Source(String name, String code) {}

    private RtShaderPacks() {}

    public static Path directory() {
        return FabricLoader.getInstance().getGameDir().resolve("rtshaderpacks");
    }

    /** Pack names in the folder, sorted; the built-in shader is not listed. */
    public static List<String> available() {
        Path directory = directory();
        List<String> names = new ArrayList<>();
        if (!Files.isDirectory(directory)) {
            return names;
        }
        try (Stream<Path> entries = Files.list(directory)) {
            for (Path entry : entries.toList()) {
                String name = entry.getFileName().toString();
                if (Files.isDirectory(entry) && Files.isRegularFile(entry.resolve(ENTRY))) {
                    names.add(name);
                } else if (name.toLowerCase(Locale.ROOT).endsWith(".zip") && zipEntry(entry) != null) {
                    names.add(name);
                }
            }
        } catch (IOException ignored) {
            // An unreadable folder just lists nothing.
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    /** True when {@code name} cannot leave the pack folder. */
    static boolean isSafeName(String name) {
        return name != null && !name.isEmpty() && !name.contains("/") && !name.contains("\\")
                && !name.contains("..") && !name.contains(":");
    }

    /** Source of pack {@code name}, or the built-in shader for {@link #BUILT_IN}. */
    public static Source load(String name) throws IOException {
        if (name == null || name.isEmpty()) {
            return new Source(BUILT_IN, builtIn());
        }
        if (!isSafeName(name)) {
            throw new IOException("Invalid RT shader pack name: " + name);
        }
        Path pack = directory().resolve(name);
        if (Files.isDirectory(pack)) {
            return new Source(name, Files.readString(pack.resolve(ENTRY), StandardCharsets.UTF_8));
        }
        try (ZipFile zip = new ZipFile(pack.toFile())) {
            ZipEntry entry = find(zip);
            if (entry == null) {
                throw new IOException(name + " has no " + ENTRY);
            }
            try (InputStream input = zip.getInputStream(entry)) {
                return new Source(name, new String(input.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
    }

    public static String builtIn() throws IOException {
        try (InputStream input = RtShaderPacks.class.getResourceAsStream(BUILT_IN_RESOURCE)) {
            if (input == null) {
                throw new IOException("Missing shader resource " + BUILT_IN_RESOURCE);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Creates the folder with a README and the template pack the first time. */
    public static void ensureFolder(Logger logger) {
        Path directory = directory();
        if (Files.isDirectory(directory)) {
            return;
        }
        try {
            Path template = directory.resolve(TEMPLATE);
            Files.createDirectories(template);
            Files.writeString(template.resolve(ENTRY), builtIn(), StandardCharsets.UTF_8);
            Files.writeString(directory.resolve("README.txt"), """
                    RT shader packs for Vulkan Frame Generation's own RT engine.

                    Iris shader packs stay in "shaderpacks" and are loaded by Iris.
                    The packs here replace our RT lighting shader instead.

                    A pack is a folder (or .zip) containing hybrid_ray_query.comp.
                    "template" is a copy of the built-in shader: copy it, rename the
                    folder, edit the shader and pick it under Ray tracing > Shader pack.
                    Keep the bindings and the FrameData block unchanged. If a pack does
                    not compile, the built-in shader is used and latest.log has the error.
                    """, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            logger.warn("Could not create the RT shader pack folder: {}", exception.toString());
        }
    }

    private static ZipEntry find(ZipFile zip) {
        ZipEntry root = zip.getEntry(ENTRY);
        if (root != null) {
            return root;
        }
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            String path = entry.getName();
            int slash = path.indexOf('/');
            if (slash > 0 && path.indexOf('/', slash + 1) < 0 && path.endsWith("/" + ENTRY)) {
                return entry;
            }
        }
        return null;
    }

    private static ZipEntry zipEntry(Path zipPath) {
        try (ZipFile zip = new ZipFile(zipPath.toFile())) {
            return find(zip);
        } catch (IOException exception) {
            return null;
        }
    }
}
