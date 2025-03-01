package com.msv.pte.secondapproach;

import com.msv.pte.database.DeviceModels;
import java.util.ArrayList;
import java.util.List;

/**
 * Refined processor for the second approach.
 * Merges tokens (e.g. "KW" with a following date or "auslauf" with "*")
 * and extracts AB, contract, model names, and date from the PDF text.
 */
public class SecondApproachRefinedProcessor {

    private static final String AB_REGEX = "(144|145)\\d{6,8}";
    private static final String DATE_REGEX = "\\d{1,2}\\.\\d{4}";
    private static final String[] SPECIAL_TOKENS = {
            "auslauf", "auslauf*", "*auslauf", "²", "Neuanlauf"
    };

    public static class ResultRow {
        public String vertragsNummer;
        public String modellBezeichnung;
        public String abNummer;
        public String datum;
    }

    /**
     * Parses the PDF text line by line and returns a list of result rows.
     *
     * @param pdfText Full text extracted from a PDF.
     * @return List of parsed result rows.
     */
    public static List<ResultRow> parseText(String pdfText) {
        List<ResultRow> resultRows = new ArrayList<>();
        String currentAB = null;
        String currentContract = null;
        List<String> modelsBuffer = new ArrayList<>();

        String[] lines = pdfText.split("\\r?\\n");
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;
            List<String> tokens = mergeTokens(line);
            String finalDate = null;
            int i = 0;
            while (i < tokens.size()) {
                String token = tokens.get(i);
                if (token.matches(AB_REGEX)) {
                    flushBuffer(resultRows, modelsBuffer, currentContract, currentAB, null);
                    modelsBuffer.clear();
                    currentAB = token;
                    if (i + 1 < tokens.size()) {
                        String potentialContract = tokens.get(i + 1);
                        if (isValidContract(potentialContract)) {
                            currentContract = potentialContract;
                            i += 2;
                            continue;
                        } else {
                            currentContract = null;
                        }
                    } else {
                        currentContract = null;
                    }
                } else if (isDateOrSpecial(token)) {
                    finalDate = token;
                } else if (isKnownModel(token)) {
                    modelsBuffer.add(token);
                }
                i++;
            }
            if (finalDate != null) {
                flushBuffer(resultRows, modelsBuffer, currentContract, currentAB, finalDate);
                modelsBuffer.clear();
            }
        }
        flushBuffer(resultRows, modelsBuffer, currentContract, currentAB, null);
        return resultRows;
    }

    // Merges adjacent tokens where applicable.
    private static List<String> mergeTokens(String line) {
        String[] rawTokens = line.split("\\s+");
        List<String> merged = new ArrayList<>();
        int i = 0;
        while (i < rawTokens.length) {
            String token = rawTokens[i];
            if (token.equalsIgnoreCase("KW") && i + 1 < rawTokens.length && rawTokens[i + 1].matches(DATE_REGEX)) {
                merged.add("KW " + rawTokens[i + 1]);
                i += 2;
                continue;
            }
            if (token.equalsIgnoreCase("auslauf") && i + 1 < rawTokens.length && "*".equals(rawTokens[i + 1])) {
                merged.add("Auslauf*");
                i += 2;
                continue;
            }
            merged.add(token);
            i++;
        }
        return merged;
    }

    private static boolean isDateOrSpecial(String token) {
        if (token.matches(DATE_REGEX) || token.matches("(?i)KW\\s+" + DATE_REGEX)) {
            return true;
        }
        String lower = token.toLowerCase();
        for (String special : SPECIAL_TOKENS) {
            if (lower.equals(special.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private static void flushBuffer(List<ResultRow> resultRows, List<String> buffer,
                                    String contract, String ab, String date) {
        for (String model : buffer) {
            ResultRow row = new ResultRow();
            row.vertragsNummer = contract;
            row.modellBezeichnung = model;
            row.abNummer = ab;
            row.datum = date;
            resultRows.add(row);
        }
    }

    // Placeholder for future contract validation logic.
    private static boolean isValidContract(String token) {
        return true;
    }

    private static boolean isKnownModel(String token) {
        for (String known : DeviceModels.getDeviceModels()) {
            if (token.equalsIgnoreCase(known)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Transforms a date string (e.g. "KW 05.2024" or "05.2024") into "0524" format.
     *
     * @param dateValue The input date string.
     * @return The transformed date string.
     */
    public static String transformDateToFourDigits(String dateValue) {
        String trimmed = dateValue.trim();
        if (trimmed.toLowerCase().startsWith("kw ")) {
            trimmed = trimmed.substring(3).trim();
        }
        if (trimmed.matches("\\d{1,2}\\.\\d{4}")) {
            String[] parts = trimmed.split("\\.");
            if (parts.length == 2) {
                String week = parts[0];
                String year = parts[1];
                if (week.length() == 1) {
                    week = "0" + week;
                }
                return week + year.substring(year.length() - 2);
            }
        }
        return dateValue;
    }
}
