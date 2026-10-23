package com.msv.pte.parser;

import java.text.Normalizer;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.time.temporal.WeekFields;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses delivery dates without choosing between desired and expected dates.
 * Date values for Excel use WWYY, for example week 5 of 2030 becomes "0530".
 * Existing findDesiredDate, transformDate and isDateRed calls remain supported.
 */
public final class DesiredDateProcessor {

    private static final int FLAGS =
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;

    private static final WeekFields ISO = WeekFields.ISO;

    private static final DateTimeFormatter FULL_DATE_FORMAT =
            DateTimeFormatter.ofPattern("d.M.uuuu", Locale.ROOT)
                    .withResolverStyle(ResolverStyle.STRICT);

    private static final String FULL_DATE_BODY =
            "\\d{1,2}\\s*\\.\\s*\\d{1,2}\\s*\\.\\s*\\d{4}";

    private static final String NAMED_WEEK_BODY =
            "(?:KW|WOCHE)\\s*:?\\s*\\d{1,2}(?:\\s*[./-]\\s*|\\s+)\\d{4}";

    private static final String WEEK_YEAR_BODY =
            "\\d{1,2}\\s*\\.\\s*\\d{4}";

    private static final String ISO_WEEK_BODY =
            "\\d{4}\\s*-\\s*W\\s*\\d{1,2}";

    private static final String STATUS_BODY =
            "\\**(?:ausgelaufen|auslauf|neuanlauf|ersatzlos|bezugsberechtigung"
                    + "|nicht\\s+bezogen|berechtigung|nicht\\s+bekannt|unbekannt)\\**";

    private static final Pattern WHITESPACE =
            Pattern.compile("[\\s\\p{Z}]+");

    private static final Pattern DOT_SPACES =
            Pattern.compile("\\s*\\.\\s*");

    private static final Pattern FULL_DATE =
            Pattern.compile(FULL_DATE_BODY);

    private static final Pattern WEEK_YEAR =
            Pattern.compile("(\\d{1,2})\\s*\\.\\s*(\\d{4})");

    private static final Pattern NAMED_WEEK = Pattern.compile(
            "(?:KW|WOCHE)\\s*:?\\s*(\\d{1,2})(?:\\s*[./-]\\s*|\\s+)(\\d{4})",
            FLAGS
    );

    private static final Pattern ISO_WEEK =
            Pattern.compile("(\\d{4})\\s*-\\s*W\\s*(\\d{1,2})", FLAGS);

    private static final Pattern YEAR_WEEK =
            Pattern.compile("(\\d{4})(\\d{2})");

    private static final Pattern WEEK_CODE =
            Pattern.compile("(\\d{2})(\\d{2})");

    private static final Pattern STATUS =
            Pattern.compile(STATUS_BODY, FLAGS);

    private static final Pattern DESIRED_LABEL = Pattern.compile(
            "(?<![\\p{L}\\p{N}])Wunsch\\s*liefer\\s*-?\\s*termin"
                    + "(?:[123](?=\\s|:))?(?!\\p{L})",
            FLAGS
    );

    private static final Pattern EXPECTED_LABEL = Pattern.compile(
            "(?<![\\p{L}\\p{N}])voraussicht(?:licher|l\\.?)\\s*"
                    + "liefer\\s*-?\\s*termin(?!\\p{L})",
            FLAGS
    );

    private static final Pattern WEEK_LABEL =
            Pattern.compile(
                    "(?<![\\p{L}\\p{N}])WOCHE(?!\\p{L})",
                    FLAGS
            );

    private static final Pattern VALUE_AFTER_LABEL = Pattern.compile(
            "\\s*[:=]?\\s*("
                    + NAMED_WEEK_BODY + "|" + FULL_DATE_BODY + "|"
                    + WEEK_YEAR_BODY + "|" + ISO_WEEK_BODY + "|"
                    + "\\d{6}|\\d{4}|" + STATUS_BODY
                    + ")(?![\\p{L}\\p{N}./-])",
            FLAGS
    );

    private DesiredDateProcessor() {
    }

    public enum DateKind {
        FULL_DATE,
        CALENDAR_WEEK,
        STATUS,
        MISSING,
        INVALID
    }

    /**
     * A non-date value is preserved so that it can be reviewed in Excel.
     */
    public record DateResult(
            DateKind kind,
            String rawValue,
            LocalDate exactDate,
            Integer calendarWeek,
            Integer weekBasedYear,
            String problem
    ) {
        public boolean hasDate() {
            return kind == DateKind.FULL_DATE
                    || kind == DateKind.CALENDAR_WEEK;
        }

        public boolean requiresReview() {
            return !hasDate();
        }

        public String excelValue() {
            if (!hasDate()) {
                return rawValue;
            }

            return String.format(
                    Locale.ROOT,
                    "%02d%02d",
                    calendarWeek,
                    weekBasedYear % 100
            );
        }
    }

    /**
     * Reads a value immediately after Wunschliefertermin or a standalone WOCHE
     * label. Expected-delivery labels are never used as a fallback.
     */
    public static String findDesiredDate(String text) {
        String normalized = normalize(text);

        String value = findAfterLabel(normalized, DESIRED_LABEL);
        if (value != null) {
            return value;
        }

        if (DESIRED_LABEL.matcher(normalized).find()
                || EXPECTED_LABEL.matcher(normalized).find()) {
            return null;
        }

        return findAfterLabel(normalized, WEEK_LABEL);
    }

    public static String findExpectedDate(String text) {
        return findAfterLabel(normalize(text), EXPECTED_LABEL);
    }

    /**
     * Parses a field value, not an entire document.
     * Supports d.M.yyyy, WW.yyyy, KW/WOCHE WW.yyyy, yyyy-Www,
     * yyyyWW and the existing WWyy Excel code (interpreted as 2000-2099).
     */
    public static DateResult parseValue(String rawValue) {
        String value = normalize(rawValue);

        if (value.isEmpty()) {
            return new DateResult(
                    DateKind.MISSING,
                    rawValue,
                    null,
                    null,
                    null,
                    "Liefertermin fehlt."
            );
        }

        if (STATUS.matcher(value).matches()) {
            return new DateResult(
                    DateKind.STATUS,
                    rawValue,
                    null,
                    null,
                    null,
                    "Lieferstatus statt eines Datums: " + value
            );
        }

        if (FULL_DATE.matcher(value).matches()) {
            try {
                String compactDate =
                        DOT_SPACES.matcher(value).replaceAll(".");

                LocalDate date =
                        LocalDate.parse(compactDate, FULL_DATE_FORMAT);

                if (date.getYear() < 1) {
                    return invalid(
                            rawValue,
                            "Ungültiges Kalenderjahr."
                    );
                }

                return new DateResult(
                        DateKind.FULL_DATE,
                        rawValue,
                        date,
                        date.get(ISO.weekOfWeekBasedYear()),
                        date.get(ISO.weekBasedYear()),
                        null
                );
            } catch (DateTimeException ex) {
                return invalid(
                        rawValue,
                        "Ungültiges Kalenderdatum."
                );
            }
        }

        Matcher matcher = NAMED_WEEK.matcher(value);
        if (matcher.matches()) {
            return weekResult(
                    rawValue,
                    Integer.parseInt(matcher.group(1)),
                    Integer.parseInt(matcher.group(2))
            );
        }

        matcher = WEEK_YEAR.matcher(value);
        if (matcher.matches()) {
            return weekResult(
                    rawValue,
                    Integer.parseInt(matcher.group(1)),
                    Integer.parseInt(matcher.group(2))
            );
        }

        matcher = ISO_WEEK.matcher(value);
        if (matcher.matches()) {
            return weekResult(
                    rawValue,
                    Integer.parseInt(matcher.group(2)),
                    Integer.parseInt(matcher.group(1))
            );
        }

        matcher = YEAR_WEEK.matcher(value);
        if (matcher.matches()) {
            return weekResult(
                    rawValue,
                    Integer.parseInt(matcher.group(2)),
                    Integer.parseInt(matcher.group(1))
            );
        }

        matcher = WEEK_CODE.matcher(value);
        if (matcher.matches()) {
            return weekResult(
                    rawValue,
                    Integer.parseInt(matcher.group(1)),
                    2000 + Integer.parseInt(matcher.group(2))
            );
        }

        return invalid(
                rawValue,
                "Liefertermin hat ein unbekanntes Format."
        );
    }

    public static String transformDate(String rawDate) {
        return parseValue(rawDate).excelValue();
    }

    public static boolean isDateRed(String date) {
        return parseValue(date).requiresReview();
    }

    private static DateResult weekResult(
            String rawValue,
            int week,
            int year
    ) {
        if (year < 1 || year > 9999) {
            return invalid(
                    rawValue,
                    "Ungültiges Kalenderjahr."
            );
        }

        int lastWeek = LocalDate.of(year, 12, 28)
                .get(ISO.weekOfWeekBasedYear());

        if (week < 1 || week > lastWeek) {
            return invalid(
                    rawValue,
                    "Ungültige Kalenderwoche "
                            + week
                            + " für das Jahr "
                            + year
                            + "."
            );
        }

        return new DateResult(
                DateKind.CALENDAR_WEEK,
                rawValue,
                null,
                week,
                year,
                null
        );
    }

    private static DateResult invalid(
            String rawValue,
            String problem
    ) {
        return new DateResult(
                DateKind.INVALID,
                rawValue,
                null,
                null,
                null,
                problem
        );
    }

    private static String findAfterLabel(
            String text,
            Pattern labelPattern
    ) {
        Matcher labels = labelPattern.matcher(text);

        while (labels.find()) {
            Matcher value = VALUE_AFTER_LABEL.matcher(text);
            value.region(labels.end(), text.length());

            if (value.lookingAt()) {
                return value.group(1).trim();
            }
        }

        return null;
    }

    private static String normalize(String text) {
        if (text == null) {
            return "";
        }

        String normalized =
                Normalizer.normalize(text, Normalizer.Form.NFKC)
                        .replace("\u00AD", "")
                        .replace("\u200B", "")
                        .replace("\uFEFF", "")
                        .replace('\u2010', '-')
                        .replace('\u2011', '-')
                        .replace('\u2013', '-');

        return WHITESPACE.matcher(normalized)
                .replaceAll(" ")
                .trim();
    }
}