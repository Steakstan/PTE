package com.msv.pte.parser;

import com.msv.pte.database.BranchNumbers;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Validates order numbers without discarding unusual values.
 * Table parsers should pass the extracted Bestellung cell to parseValue.
 * The label-based methods remain available for existing callers.
 */
public final class OrderNumberProcessor {

    private static final int FLAGS =
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;

    private static final Pattern LABEL = Pattern.compile(
            "(?<![\\p{L}\\p{N}])(Bestellung|Bestelltext)(?![\\p{L}\\p{N}(])"
                    + "[\\t ]*([:=])?[\\t ]*",
            FLAGS
    );

    private static final Pattern FIRST_TOKEN =
            Pattern.compile("^([^\\s]+)");

    private static final Pattern WHITESPACE =
            Pattern.compile("[\\s\\p{Z}]+");

    private static final Pattern ALPHANUMERIC =
            Pattern.compile("[A-Za-z0-9]+");

    private static final Pattern FIELD_HEADER = Pattern.compile(
            "(?<![\\p{L}\\p{N}])(?:Belegnummer|Kundennummer|Bestellung"
                    + "|Bestelltext|Komplettlieferung)(?![\\p{L}\\p{N}])",
            FLAGS
    );

    private static final Set<String> STOP_TOKENS = Set.of(
            "BESTELLUNG",
            "BESTELLTEXT",
            "BELEGNUMMER",
            "KUNDENNUMMER",
            "DATUM",
            "POS",
            "POSITION",
            "BEZEICHNUNG",
            "MENGE",
            "ARTIKEL",
            "EAN",
            "WOCHE",
            "KW",
            "VERSANDANSCHRIFT",
            "EMPFÄNGER",
            "BESTELLER",
            "KOMPLETTLIEFERUNG",
            "WIR",
            "BEI",
            "BITTE",
            "SEITE",
            "AUFTRAGSEINGANGSBESTÄTIGUNG",
            "AUFTRAGSINFORMATION"
    );

    private static final Set<String> BRANCH_CODES = loadBranchCodes();

    private OrderNumberProcessor() {
    }

    public record OrderNumberResult(
            String rawValue,
            String orderNumber,
            String branchCode,
            boolean knownBranch,
            List<String> problems
    ) {
        public OrderNumberResult {
            problems = List.copyOf(problems);
        }

        public boolean hasValue() {
            return orderNumber != null && !orderNumber.isBlank();
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
            boolean fromBestellung
    ) {
    }

    /**
     * Keeps the existing String-returning API.
     */
    public static String findOrderNumber(String pdfText) {
        return findOrderNumberResult(pdfText).orderNumber();
    }

    /**
     * Intended for a single-order document or an already isolated order block.
     * Bestellung has priority over Bestelltext, regardless of validation status.
     * Different values are reported instead of silently choosing a "better" one.
     */
    public static OrderNumberResult findOrderNumberResult(String pdfText) {
        List<Candidate> candidates = findCandidates(pdfText);

        if (candidates.isEmpty()) {
            return parseValue(null);
        }

        Candidate selected = candidates.get(0);

        for (Candidate candidate : candidates) {
            if (candidate.fromBestellung()) {
                selected = candidate;
                break;
            }
        }

        OrderNumberResult result = parseValue(selected.value());

        Set<String> distinctNumbers = new HashSet<>();

        for (Candidate candidate : candidates) {
            distinctNumbers.add(
                    candidate.value().toUpperCase(Locale.ROOT)
            );
        }

        if (distinctNumbers.size() < 2) {
            return result;
        }

        List<String> problems = new ArrayList<>(result.problems());

        problems.add(
                "Unterschiedliche Auftragsnummern gefunden; Zuordnung prüfen."
        );

        return new OrderNumberResult(
                result.rawValue(),
                result.orderNumber(),
                result.branchCode(),
                result.knownBranch(),
                problems
        );
    }

    /**
     * Accepts a complete field value. No length limit or branch check truncates
     * the value. Internal whitespace and unexpected characters cause warnings.
     */
    public static OrderNumberResult parseValue(String rawValue) {
        String value = WHITESPACE.matcher(normalize(rawValue))
                .replaceAll(" ")
                .trim();

        List<String> problems = new ArrayList<>();

        if (value.isEmpty()) {
            problems.add("Auftragsnummer fehlt.");

            return new OrderNumberResult(
                    rawValue,
                    null,
                    null,
                    false,
                    problems
            );
        }

        if (value.length() != 5 && value.length() != 6) {
            problems.add(
                    "Ungewöhnliche Länge der Auftragsnummer: "
                            + value.length()
                            + " Zeichen; üblich sind 5 oder 6."
            );
        }

        if (!ALPHANUMERIC.matcher(value).matches()) {
            problems.add(
                    "Auftragsnummer enthält Leerzeichen oder andere Zeichen"
                            + " außerhalb von A-Z und 0-9."
            );
        }

        String branchCode = value.length() >= 2
                ? value.substring(0, 2).toUpperCase(Locale.ROOT)
                : null;

        boolean knownBranch = isValidBranch(branchCode);

        if (!knownBranch) {
            problems.add(
                    branchCode == null
                            ? "Filialcode fehlt oder ist unvollständig."
                            : "Filialcode ist im Verzeichnis nicht bekannt."
            );
        }

        return new OrderNumberResult(
                rawValue,
                value,
                branchCode,
                knownBranch,
                problems
        );
    }

    public static boolean isValidBranch(String branchCode) {
        String code = normalize(branchCode)
                .trim()
                .toUpperCase(Locale.ROOT);

        return code.length() == 2 && BRANCH_CODES.contains(code);
    }

    private static List<Candidate> findCandidates(String pdfText) {
        String[] lines = normalize(pdfText).split("\\R", -1);

        List<Candidate> candidates = new ArrayList<>();

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            Matcher labels = LABEL.matcher(line);

            while (labels.find()) {
                if (!isFieldContext(line, labels)) {
                    continue;
                }

                String valueText = line.substring(labels.end()).strip();

                if (valueText.isEmpty()) {
                    if (hasMultipleFieldHeaders(line)) {
                        continue;
                    }

                    valueText = nextNonBlankLine(lines, i + 1);
                }

                boolean fromBestellung = labels.group(1)
                        .equalsIgnoreCase("Bestellung");

                String value = firstValueToken(
                        valueText,
                        fromBestellung
                );

                if (value != null) {
                    candidates.add(
                            new Candidate(value, fromBestellung)
                    );
                }
            }
        }

        return candidates;
    }

    private static boolean isFieldContext(
            String line,
            Matcher label
    ) {
        if (line.substring(0, label.start()).isBlank()
                || label.group(2) != null) {
            return true;
        }

        int start = label.start();

        return start >= 2
                && Character.isWhitespace(line.charAt(start - 1))
                && Character.isWhitespace(line.charAt(start - 2));
    }

    private static boolean hasMultipleFieldHeaders(String line) {
        Matcher headers = FIELD_HEADER.matcher(line);

        return headers.find() && headers.find();
    }

    private static String nextNonBlankLine(
            String[] lines,
            int start
    ) {
        for (int i = start; i < lines.length; i++) {
            if (!lines[i].isBlank()) {
                return lines[i].strip();
            }
        }

        return "";
    }

    private static String firstValueToken(
            String text,
            boolean fromBestellung
    ) {
        Matcher matcher = FIRST_TOKEN.matcher(text);

        if (!matcher.find()) {
            return null;
        }

        String token = matcher.group(1);

        if (!fromBestellung) {
            int slash = token.indexOf('/');

            if (slash >= 0) {
                token = token.substring(0, slash);
            }
        }

        if (token.isEmpty()) {
            return null;
        }

        String header = token.toUpperCase(Locale.ROOT);

        while (header.endsWith(":") || header.endsWith(".")) {
            header = header.substring(0, header.length() - 1);
        }

        if (STOP_TOKENS.contains(header)
                || header.startsWith("WUNSCHLIEFERTERMIN")) {
            return null;
        }

        return token;
    }

    private static Set<String> loadBranchCodes() {
        Set<String> codes = new HashSet<>();

        if (BranchNumbers.BRANCH_NUMBERS != null) {
            for (String branch : BranchNumbers.BRANCH_NUMBERS) {
                String code = normalize(branch)
                        .trim()
                        .toUpperCase(Locale.ROOT);

                if (code.length() == 2) {
                    codes.add(code);
                }
            }
        }

        return Set.copyOf(codes);
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