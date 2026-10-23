package com.msv.pte.parser;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts and validates AB numbers without truncating invalid field values.
 * Valid numbers have 9-11 ASCII digits and start with 144 or 145.
 */
public final class ConfirmationNumberProcessor {

    private static final int FLAGS =
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
                    | Pattern.UNICODE_CHARACTER_CLASS;

    private static final Pattern DIGITS =
            Pattern.compile("[0-9]+");

    private static final Pattern WHITESPACE =
            Pattern.compile("[\\s\\p{Z}]+", FLAGS);

    private static final Pattern LABELED_NUMBER = Pattern.compile(
            "(?<![\\p{L}\\p{N}])"
                    + "(?:Beleg\\h*nummer|AB\\h*-?\\h*(?:Nummer|Nr\\.?)"
                    + "|AB(?=\\h*[:=]))(?!\\p{L})"
                    + "\\h*[:=]?\\s*([0-9][^\\s]*)",
            FLAGS
    );

    private static final Pattern STANDALONE_NUMBER = Pattern.compile(
            "(?<![\\p{L}\\p{N}_/.-])(?:144|145)[0-9]{6,8}"
                    + "(?![\\p{L}\\p{N}_/-]|\\.[0-9])"
    );

    private ConfirmationNumberProcessor() {
    }

    public record ConfirmationNumberResult(
            String rawValue,
            String confirmationNumber,
            List<String> problems
    ) {
        public ConfirmationNumberResult {
            problems = List.copyOf(problems);
        }

        public boolean hasValue() {
            return confirmationNumber != null
                    && !confirmationNumber.isBlank();
        }

        public boolean requiresReview() {
            return !problems.isEmpty();
        }

        public String problem() {
            return String.join(" ", problems);
        }
    }

    private record Candidate(
            String value,
            int start,
            int end,
            boolean labeled
    ) {
    }

    /**
     * Keeps the existing String-returning API.
     */
    public static String findConfirmationNumber(String pdfText) {
        return findConfirmationNumberResult(pdfText)
                .confirmationNumber();
    }

    /**
     * Use for a single-order document or an isolated order block.
     * Explicitly labeled numbers take priority, including invalid values.
     * Different numbers trigger a warning; repeated page headers do not.
     */
    public static ConfirmationNumberResult findConfirmationNumberResult(
            String pdfText
    ) {
        List<Candidate> candidates = findCandidates(pdfText);

        if (candidates.isEmpty()) {
            return parseValue(null);
        }

        Candidate selected = candidates.get(0);

        for (Candidate candidate : candidates) {
            if (candidate.labeled()) {
                selected = candidate;
                break;
            }
        }

        ConfirmationNumberResult result =
                parseValue(selected.value());

        long distinctNumbers = candidates.stream()
                .map(Candidate::value)
                .distinct()
                .count();

        if (distinctNumbers < 2) {
            return result;
        }

        List<String> problems =
                new ArrayList<>(result.problems());

        problems.add(
                "Unterschiedliche AB-Nummern gefunden; Zuordnung prüfen."
        );

        return new ConfirmationNumberResult(
                result.rawValue(),
                result.confirmationNumber(),
                problems
        );
    }

    /**
     * Returns distinct candidates in document order, including invalid labeled
     * values. Multiple numbers are normal in Auftragsinformation documents,
     * so this method does not add a document-level ambiguity warning.
     * The table parser must associate each number with its own order block.
     */
    public static List<ConfirmationNumberResult> findAllConfirmationNumbers(
            String pdfText
    ) {
        Map<String, ConfirmationNumberResult> results =
                new LinkedHashMap<>();

        for (Candidate candidate : findCandidates(pdfText)) {
            results.computeIfAbsent(
                    candidate.value(),
                    ConfirmationNumberProcessor::parseValue
            );
        }

        return List.copyOf(results.values());
    }

    /**
     * Validates a complete field value. No digits are removed, corrected or
     * joined across whitespace. Invalid values remain available for review.
     */
    public static ConfirmationNumberResult parseValue(String rawValue) {
        String value = WHITESPACE.matcher(normalize(rawValue))
                .replaceAll(" ")
                .trim();

        List<String> problems = new ArrayList<>();

        if (value.isEmpty()) {
            problems.add("AB-Nummer fehlt.");

            return new ConfirmationNumberResult(
                    rawValue,
                    null,
                    problems
            );
        }

        if (!DIGITS.matcher(value).matches()) {
            problems.add(
                    "AB-Nummer enthält Zeichen außerhalb von 0-9."
            );
        }

        if (value.length() < 9 || value.length() > 11) {
            problems.add(
                    "Ungewöhnliche Länge der AB-Nummer: "
                            + value.length()
                            + " Zeichen; erwartet werden 9 bis 11 Ziffern."
            );
        }

        if (!value.startsWith("144") && !value.startsWith("145")) {
            problems.add(
                    "AB-Nummer beginnt nicht mit 144 oder 145."
            );
        }

        return new ConfirmationNumberResult(
                rawValue,
                value,
                problems
        );
    }

    private static List<Candidate> findCandidates(String pdfText) {
        String text = normalize(pdfText);

        List<Candidate> candidates = new ArrayList<>();

        Matcher labeled = LABELED_NUMBER.matcher(text);

        while (labeled.find()) {
            candidates.add(
                    new Candidate(
                            labeled.group(1),
                            labeled.start(1),
                            labeled.end(1),
                            true
                    )
            );
        }

        Matcher standalone = STANDALONE_NUMBER.matcher(text);

        while (standalone.find()) {
            if (!overlapsLabeledValue(
                    standalone.start(),
                    standalone.end(),
                    candidates
            )) {
                candidates.add(
                        new Candidate(
                                standalone.group(),
                                standalone.start(),
                                standalone.end(),
                                false
                        )
                );
            }
        }

        candidates.sort(
                Comparator.comparingInt(Candidate::start)
        );

        return candidates;
    }

    private static boolean overlapsLabeledValue(
            int start,
            int end,
            List<Candidate> candidates
    ) {
        for (Candidate candidate : candidates) {
            if (candidate.labeled()
                    && start < candidate.end()
                    && end > candidate.start()) {
                return true;
            }
        }

        return false;
    }

    private static String normalize(String text) {
        if (text == null) {
            return "";
        }

        return Normalizer.normalize(text, Normalizer.Form.NFC)
                .replace('\u00A0', ' ')
                .replace('\u202F', ' ')
                .replace('\u2007', ' ')
                .replace("\u200B", "")
                .replace("\uFEFF", "")
                .replace("\u00AD", "");
    }
}