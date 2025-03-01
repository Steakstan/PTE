package com.msv.pte.parser;

import com.msv.pte.database.BranchNumbers;
import com.msv.pte.database.DeviceModels;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Refined parser for the second approach.
 * Parses PDF text line by line to extract:
 * - Contract number (Auftragsnummer)
 * - Model names (up to the date token)
 * - AB number (starting with 144 or 145)
 * - Date in format WW.JJJJ (the last date token in the line)
 */
public class SecondApproachRefinedParser {

    private static final Pattern AB_PATTERN = Pattern.compile("\\b(144|145)\\d{6,8}\\b");
    private static final Pattern DATE_PATTERN = Pattern.compile("\\b\\d{1,2}\\.\\d{4}\\b");

    /**
     * Container for a parsed result row.
     * Column A: Contract number (auftragsNummer)
     * Column B: Model name (modelName)
     * Column C: AB number (abNummer)
     * Column D: Date (datum)
     */
    public static class SecondApproachResultRow {
        public String auftragsNummer;
        public String modelName;
        public String abNummer;
        public String datum;
    }

    /**
     * Parses the given PDF text and returns a list of result rows.
     *
     * @param pdfText Full text extracted from a PDF.
     * @return List of parsed result rows.
     */
    public static List<SecondApproachResultRow> parse(String pdfText) {
        List<SecondApproachResultRow> resultRows = new ArrayList<>();
        String currentAB = null;
        String currentAuftrag = null;

        String[] lines = pdfText.split("\\r?\\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;

            // Update current AB if found
            currentAB = updateCurrentAB(trimmed, currentAB);

            // Extract contract number if available
            String foundAuftrag = extractContractNumber(trimmed);
            if (foundAuftrag != null) {
                currentAuftrag = foundAuftrag;
            }

            // Extract the last date token from the line
            String lastDate = extractLastDate(trimmed);

            // Extract model tokens before the date token
            List<String> models = extractModelsBeforeDate(trimmed, lastDate);

            // Create a result row for each found model
            for (String model : models) {
                SecondApproachResultRow row = new SecondApproachResultRow();
                row.auftragsNummer = currentAuftrag;
                row.modelName = model;
                row.abNummer = currentAB;
                row.datum = lastDate;
                resultRows.add(row);
            }
        }
        return resultRows;
    }

    /**
     * Updates and returns the current AB number if found in the line.
     *
     * @param line      The input line.
     * @param currentAB The current AB value.
     * @return Updated AB value if found, otherwise returns currentAB.
     */
    private static String updateCurrentAB(String line, String currentAB) {
        Matcher matcher = AB_PATTERN.matcher(line);
        if (matcher.find()) {
            return matcher.group();
        }
        return currentAB;
    }

    /**
     * Extracts the contract number (Auftragsnummer) from the line.
     * A valid contract number has 5-6 characters and its first two characters must match a branch code.
     *
     * @param line The input line.
     * @return The contract number if found, otherwise null.
     */
    private static String extractContractNumber(String line) {
        String[] tokens = line.split("\\s+");
        for (String token : tokens) {
            if (token.length() >= 5 && token.length() <= 6) {
                String code = token.substring(0, 2).toUpperCase();
                if (isValidBranch(code)) {
                    return token;
                }
            }
        }
        return null;
    }

    /**
     * Checks if the given branch code is valid.
     *
     * @param code The branch code.
     * @return True if valid, false otherwise.
     */
    private static boolean isValidBranch(String code) {
        for (String branch : BranchNumbers.BRANCH_NUMBERS) {
            if (branch.equalsIgnoreCase(code)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Extracts the last date token from the line.
     *
     * @param line The input line.
     * @return The last date token if found, otherwise null.
     */
    private static String extractLastDate(String line) {
        String lastDate = null;
        Matcher matcher = DATE_PATTERN.matcher(line);
        while (matcher.find()) {
            lastDate = matcher.group();
        }
        return lastDate;
    }

    /**
     * Extracts model tokens from the line up to the first occurrence of the date token.
     *
     * @param line     The input line.
     * @param lastDate The date token.
     * @return List of model tokens.
     */
    private static List<String> extractModelsBeforeDate(String line, String lastDate) {
        List<String> models = new ArrayList<>();
        String[] tokens = line.split("\\s+");
        for (String token : tokens) {
            if (token.equals(lastDate)) break;
            if (isKnownModel(token)) {
                models.add(token);
            }
        }
        return models;
    }

    /**
     * Checks if the token is a known device model.
     *
     * @param token The token to check.
     * @return True if the token is a known model, false otherwise.
     */
    private static boolean isKnownModel(String token) {
        for (String model : DeviceModels.getDeviceModels()) {
            if (token.equalsIgnoreCase(model)) {
                return true;
            }
        }
        return false;
    }
}
