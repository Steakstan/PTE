package com.msv.pte.parser;

import com.msv.pte.database.DeviceModels;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Immutable model lookup shared by PDF parsers.
 * Unknown values remain available for export and manual review.
 * The default dictionary is loaded once, on the first getDefault() call.
 */
public final class ModelCatalog {

    private static final Pattern WHITESPACE = Pattern.compile(
            "[\\s\\p{Z}]+",
            Pattern.UNICODE_CHARACTER_CLASS
    );

    private final Map<String, String> modelsByKey;

    /**
     * Copies the source collection. Null and blank entries are ignored.
     * Equivalent entries share one key; the first spelling is preserved.
     * A null source produces an empty catalog without disabling extraction.
     */
    public ModelCatalog(Collection<String> models) {
        Map<String, String> index = new HashMap<>();

        if (models != null) {
            for (String rawModel : models) {
                String model = normalize(rawModel);

                if (!model.isEmpty()) {
                    index.putIfAbsent(key(model), model);
                }
            }
        }

        modelsByKey = Map.copyOf(index);
    }

    private static final class DefaultHolder {

        private static final ModelCatalog INSTANCE = createDefault();

        private static ModelCatalog createDefault() {
            String[] models = DeviceModels.getDeviceModels();

            return new ModelCatalog(
                    models == null
                            ? List.of()
                            : Arrays.asList(models)
            );
        }
    }

    public static ModelCatalog getDefault() {
        return DefaultHolder.INSTANCE;
    }

    public record ModelResult(
            String rawValue,
            String modelName,
            boolean knownModel,
            List<String> problems
    ) {
        public ModelResult {
            problems = List.copyOf(problems);
        }

        public boolean hasValue() {
            return modelName != null && !modelName.isBlank();
        }

        public boolean requiresReview() {
            return !problems.isEmpty();
        }

        public String problem() {
            return String.join(" ", problems);
        }

        public ModelEntry toModelEntry() {
            return new ModelEntry(
                    modelName,
                    requiresReview()
            );
        }
    }

    public int size() {
        return modelsByKey.size();
    }

    public boolean isEmpty() {
        return modelsByKey.isEmpty();
    }

    public boolean isKnown(String value) {
        return findCanonicalName(value) != null;
    }

    /**
     * Returns the dictionary spelling or null if there is no exact match.
     * For extracted fields use parseValue to retain unknown models.
     */
    public String findCanonicalName(String value) {
        String normalized = normalize(value);

        if (normalized.isEmpty()) {
            return null;
        }

        return modelsByKey.get(key(normalized));
    }

    /**
     * Accepts one complete model field, not a description or a document.
     * Case and PDF formatting are normalized. Punctuation, internal word
     * boundaries and suffixes remain significant; no fuzzy replacement occurs.
     */
    public ModelResult parseValue(String rawValue) {
        String value = normalize(rawValue);

        if (value.isEmpty()) {
            return new ModelResult(
                    rawValue,
                    null,
                    false,
                    List.of("Modellbezeichnung fehlt.")
            );
        }

        String canonicalName = modelsByKey.get(key(value));

        if (canonicalName != null) {
            return new ModelResult(
                    rawValue,
                    canonicalName,
                    true,
                    List.of()
            );
        }

        List<String> problems = new ArrayList<>();

        problems.add(
                isEmpty()
                        ? "Modellverzeichnis ist leer; Modell konnte nicht geprüft werden."
                        : "Modell ist nicht im Verzeichnis enthalten; Bezeichnung prüfen."
        );

        if (value.indexOf(' ') >= 0) {
            problems.add(
                    "Modellbezeichnung enthält Leerzeichen; Textaufteilung prüfen."
            );
        }

        return new ModelResult(
                rawValue,
                value,
                false,
                problems
        );
    }

    /**
     * Folds ASCII letter case without merging different Unicode identifiers.
     */
    private static String key(String value) {
        char[] characters = value.toCharArray();

        for (int i = 0; i < characters.length; i++) {
            char character = characters[i];

            if (character >= 'a' && character <= 'z') {
                characters[i] = (char) (character - 'a' + 'A');
            }
        }

        return new String(characters);
    }

    private static String normalize(String text) {
        if (text == null) {
            return "";
        }

        String normalized =
                Normalizer.normalize(text, Normalizer.Form.NFC)
                        .replace("\u00AD", "")
                        .replace("\u200B", "")
                        .replace("\u2060", "")
                        .replace("\uFEFF", "");

        return WHITESPACE.matcher(normalized)
                .replaceAll(" ")
                .trim();
    }
}