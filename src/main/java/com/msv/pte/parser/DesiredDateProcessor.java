package com.msv.pte.parser;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.WeekFields;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Processes and transforms desired delivery dates extracted from PDF text.
 * It detects dates specified with keywords such as "WUNSCHLIEFERTERMIN" or "WOCHE"
 * and converts them into a standardized "KWJJ" format (e.g., "4524" for week 45 of 2024).
 */
public class DesiredDateProcessor {

    private static final Set<String> KEYWORDS = new HashSet<>(Arrays.asList(
            "auslauf", "neuanlauf", "ersatzlos", "ausgelaufen",
            "bezugsberechtigung", "nicht bezogen", "berechtigung",
            "nicht bekannt", "unbekannt"
    ));

    private static final String INVALID_DATE = "00.00.0000";

    // Precompiled pattern for finding desired date in a text line.
    private static final Pattern DATE_PATTERN = Pattern.compile(
            "(?i)(?:WUNSCHLIEFERTERMIN|WOCHE)\\s*:?\\s*(\\d{1,2}\\.\\d{1,2}\\.\\d{4}|\\d{1,2}\\.\\d{4})"
    );

    /**
     * Searches for a desired delivery date in the given text line.
     *
     * @param line the text line to search
     * @return the found date string, or null if none is found
     */
    public static String findDesiredDate(String line) {
        Matcher matcher = DATE_PATTERN.matcher(line);
        return matcher.find() ? matcher.group(1) : null;
    }

    /**
     * Transforms a raw date string into the standardized "KWJJ" format.
     * Supports both full date format (dd.mm.yyyy) and week-year format (d{1,2}.yyyy).
     * If conversion fails or the input is a special keyword, the original value is returned.
     *
     * @param rawDate the raw date string
     * @return the transformed date string, or the original value if conversion is not possible
     */
    public static String transformDate(String rawDate) {
        if (rawDate == null || INVALID_DATE.equals(rawDate)) {
            return rawDate;
        }
        if (KEYWORDS.contains(rawDate.toLowerCase())) {
            return rawDate;
        }
        // Check for full date format (dd.mm.yyyy) allowing one or two digits for day and month.
        if (rawDate.matches("^\\d{1,2}\\.\\d{1,2}\\.\\d{4}$")) {
            return transformFullDate(rawDate);
        }
        // Check for week-year format (d{1,2}.yyyy)
        if (rawDate.matches("^\\d{1,2}\\.\\d{4}$")) {
            return transformWeekYear(rawDate);
        }
        return rawDate;
    }

    /**
     * Converts a full date (dd.mm.yyyy) into the "KWJJ" format.
     *
     * @param fullDate the date in dd.mm.yyyy format
     * @return the date in "KWJJ" format, or the original string if parsing fails
     */
    private static String transformFullDate(String fullDate) {
        try {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("d.M.yyyy");
            LocalDate date = LocalDate.parse(fullDate, formatter);
            int week = date.get(WeekFields.ISO.weekOfYear());
            int yearShort = date.getYear() % 100;
            return String.format("%02d%02d", week, yearShort);
        } catch (DateTimeParseException ex) {
            return fullDate;
        }
    }

    /**
     * Converts a week-year date (d{1,2}.yyyy) into the "KWJJ" format.
     *
     * @param weekYear the date in d{1,2}.yyyy format
     * @return the date in "KWJJ" format, or the original string if parsing fails
     */
    private static String transformWeekYear(String weekYear) {
        try {
            String[] parts = weekYear.split("\\.");
            int week = Integer.parseInt(parts[0]);
            int year = Integer.parseInt(parts[1]);
            return String.format("%02d%02d", week, year % 100);
        } catch (NumberFormatException ex) {
            return weekYear;
        }
    }

    /**
     * Determines whether the given date string should be highlighted in red.
     * A date is marked red if it equals an invalid date or is one of the special keywords.
     *
     * @param date the date string to check
     * @return true if the date should be highlighted, false otherwise
     */
    public static boolean isDateRed(String date) {
        if (date == null) {
            return false;
        }
        return INVALID_DATE.equals(date) || KEYWORDS.contains(date.toLowerCase());
    }
}
