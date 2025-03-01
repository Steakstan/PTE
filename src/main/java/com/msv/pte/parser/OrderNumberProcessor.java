package com.msv.pte.parser;

import com.msv.pte.database.BranchNumbers;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts an order number ("Auftragsnummer") from PDF text.
 * It searches for the keywords "Bestellung" or "Bestelltext" followed by a 5-6 character token,
 * ensuring that the token's first two characters match a valid branch code.
 */
public class OrderNumberProcessor {

    // Pattern to match "Bestellung" or "Bestelltext" followed by a 5-6 character order number.
    private static final Pattern ORDER_PATTERN = Pattern.compile("(?i)(Bestellung|Bestelltext)\\s+(\\S{5,6})");

    /**
     * Finds the order number in the provided PDF text.
     *
     * @param pdfText Full text extracted from a PDF.
     * @return The order number if found; otherwise, null.
     */
    public static String findOrderNumber(String pdfText) {
        Matcher matcher = ORDER_PATTERN.matcher(pdfText);
        while (matcher.find()) {
            String candidate = matcher.group(2);
            if (candidate != null && candidate.length() >= 2) {
                String branchCode = candidate.substring(0, 2).toUpperCase();
                if (isValidBranch(branchCode)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    /**
     * Checks if the provided branch code is valid.
     *
     * @param branchCode A two-character branch code.
     * @return True if the branch code is valid; otherwise, false.
     */
    private static boolean isValidBranch(String branchCode) {
        for (String branch : BranchNumbers.BRANCH_NUMBERS) {
            if (branch.equalsIgnoreCase(branchCode)) {
                return true;
            }
        }
        return false;
    }
}
