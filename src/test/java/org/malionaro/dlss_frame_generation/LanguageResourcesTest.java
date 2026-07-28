package org.malionaro.dlss_frame_generation;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LanguageResourcesTest {
    private static final Path LANGUAGE_DIRECTORY = Path.of(
            "src", "main", "resources", "assets", "dlss_fg", "lang"
    );
    private static final Pattern TRANSLATION_KEY =
            Pattern.compile(
                    "dlss_fg\\.(?:video_settings|screen|section|tab|tooltip|"
                            + "option|value|status|diagnostics|osd|motion_debug)"
                            + "[a-zA-Z0-9_.]*"
            );

    @Test
    void germanAndEnglishExposeTheSameKeys() throws IOException {
        Set<String> english = languageKeys("en_us.json");
        Set<String> german = languageKeys("de_de.json");

        assertEquals(english, german);
        assertTrue(english.size() >= 120, "Expected the complete settings UI");
    }

    @Test
    void everyStaticUiTranslationReferenceExists() throws IOException {
        Set<String> english = languageKeys("en_us.json");
        Set<String> german = languageKeys("de_de.json");
        Set<String> referenced = new HashSet<>();

        try (Stream<Path> files = Files.walk(
                Path.of("src", "client", "java")
        )) {
            for (Path file : files.filter(
                    path -> path.toString().endsWith(".java")
            ).toList()) {
                Matcher matcher = TRANSLATION_KEY.matcher(
                        Files.readString(file)
                );
                while (matcher.find()) {
                    String key = matcher.group();
                    // Dynamic enum keys append their value at runtime and are
                    // covered by the exact German/English key-set comparison.
                    if (!key.endsWith(".")) {
                        referenced.add(key);
                    }
                }
            }
        }

        assertTrue(
                english.containsAll(referenced),
                () -> "Missing English keys: " + difference(referenced, english)
        );
        assertTrue(
                german.containsAll(referenced),
                () -> "Missing German keys: " + difference(referenced, german)
        );
    }

    private static Set<String> languageKeys(String fileName)
            throws IOException {
        JsonElement root = JsonParser.parseString(
                Files.readString(LANGUAGE_DIRECTORY.resolve(fileName))
        );
        return root.getAsJsonObject().keySet();
    }

    private static Set<String> difference(
            Set<String> expected,
            Set<String> actual
    ) {
        Set<String> missing = new HashSet<>(expected);
        missing.removeAll(actual);
        return missing;
    }
}
