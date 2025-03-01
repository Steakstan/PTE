package com.msv.pte.parser;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts confirmation numbers from PDF text.
 * A valid confirmation number consists of 9 to 11 digits and starts with either 144 or 145.
 */
public class ConfrmationNumberProcessor {

    // Regex pattern: Matches confirmation numbers starting with 144 or 145 followed by 6 to 8 digits.
    private static final Pattern CONFIRMATION_PATTERN = Pattern.compile("\\b(144|145)\\d{6,8}\\b");

    /**
     * Searches for the first occurrence of a confirmation number in the provided PDF text.
     *
     * @param pdfText Full text extracted from a PDF.
     * @return The found confirmation number, or null if none is found.
     */
    public static String findConfirmationNumber(String pdfText) {
        Matcher matcher = CONFIRMATION_PATTERN.matcher(pdfText);
        if (matcher.find()) {
            return matcher.group();
        }
        return null;
    }
}
