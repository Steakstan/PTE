package com.msv.pte.parser;

import com.msv.pte.database.DeviceModels;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts position data from PDF text.
 * A position block starts with a three-digit number at the beginning of a line.
 * Each block contains a position number, models, a desired date, and the block's lines.
 */
public class PositionNumberProcessor {

    private static final Pattern POSITION_PATTERN = Pattern.compile("^\\s*(\\d{3})\\b");
    private static final Set<String> KEYWORDS = new HashSet<>(Arrays.asList(
            "auslauf", "neuanlauf", "ersatzlos", "ausgelaufen",
            "bezugsberechtigung", "nicht bezogen", "berechtigung",
            "nicht bekannt", "unbekannt"
    ));

    /**
     * Represents a block of position data.
     */
    public static class PositionData {
        private final List<ModelEntry> models = new ArrayList<>();
        private final String positionNumber;
        private String desiredDate;
        private final List<String> lines = new ArrayList<>();

        public PositionData(String positionNumber) {
            this.positionNumber = positionNumber;
        }
        

        public List<ModelEntry> getModels() {
            return models;
        }

        public String getDesiredDate() {
            return desiredDate;
        }

        public void setDesiredDate(String desiredDate) {
            this.desiredDate = desiredDate;
        }

        public List<String> getLines() {
            return lines;
        }
    }

    /**
     * Splits the PDF text into position blocks and extracts models and dates.
     *
     * @param pdfText Full text extracted from the PDF.
     * @return List of position blocks.
     */
    public static List<PositionData> extractPositionsModelsAndDates(String pdfText) {
        List<PositionData> positions = new ArrayList<>();
        String[] lines = pdfText.split("\\r?\\n");
        PositionData currentBlock = null;

        for (String line : lines) {
            Matcher matcher = POSITION_PATTERN.matcher(line);
            if (matcher.find()) {
                // Finalize previous block
                if (currentBlock != null) {
                    finalizeBlock(currentBlock);
                }
                currentBlock = new PositionData(matcher.group(1));
                positions.add(currentBlock);
                currentBlock.getLines().add(line);
                addFirstWordAfterPosition(line, currentBlock);
                addModelsAndDateFromLine(line, currentBlock);
            } else if (currentBlock != null) {
                currentBlock.getLines().add(line);
                addModelsAndDateFromLine(line, currentBlock);
            }
        }
        if (currentBlock != null) {
            finalizeBlock(currentBlock);
        }
        return positions;
    }
    /**
     * Finalizes a block by ensuring a desired date is set.
     * If none is found, searches the block lines for a keyword.
     *
     * @param block The position block to finalize.
     */
    private static void finalizeBlock(PositionData block) {
        if (block.getDesiredDate() == null || "00.00.0000".equals(block.getDesiredDate())) {
            String keyword = searchForKeyword(block.getLines());
            block.setDesiredDate(keyword != null ? keyword : "00.00.0000");
        }
    }

    /**
     * Searches through the block's lines for any predefined keyword.
     *
     * @param lines List of block lines.
     * @return The first found keyword or null.
     */
    private static String searchForKeyword(List<String> lines) {
        for (String line : lines) {
            String lowerLine = line.toLowerCase();
            for (String kw : KEYWORDS) {
                if (lowerLine.contains(kw)) {
                    return kw;
                }
            }
        }
        return null;
    }

    /**
     * Extracts models and desired date from the given line and adds them to the block.
     *
     * @param line  The input line.
     * @param block The current position block.
     */
    private static void addModelsAndDateFromLine(String line, PositionData block) {
        List<String> models = extractModelsFromLine(line);
        for (String model : models) {
            block.getModels().add(new ModelEntry(model, false));
        }
        String date = DesiredDateProcessor.findDesiredDate(line);
        if (date != null) {
            block.setDesiredDate(date);
        }
    }

    /**
     * Extracts the first word after the position number.
     * If it is not a known model, adds it as a model with red flag.
     *
     * @param line  The input line.
     * @param block The current position block.
     */
    private static void addFirstWordAfterPosition(String line, PositionData block) {
        Matcher matcher = POSITION_PATTERN.matcher(line);
        if (matcher.find()) {
            int end = matcher.end();
            String remaining = line.substring(end).trim();
            if (!remaining.isEmpty()) {
                String[] parts = remaining.split("\\s+", 2);
                String firstWord = parts[0];
                if (!isKnownModel(firstWord)) {
                    block.getModels().add(new ModelEntry(firstWord, true));
                }
            }
        }
    }
    /**
     * Extracts known model names from the line.
     *
     * @param line The input line.
     * @return List of known model names found.
     */
    private static List<String> extractModelsFromLine(String line) {
        List<String> models = new ArrayList<>();
        for (String knownModel : DeviceModels.getDeviceModels()) {
            String regex = "\\b" + Pattern.quote(knownModel) + "\\b";
            Pattern pattern = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
            Matcher matcher = pattern.matcher(line);
            if (matcher.find()) {
                models.add(knownModel);
            }
        }
        return models;
    }

    /**
     * Checks if the given candidate is a known model.
     *
     * @param candidate The candidate string.
     * @return True if it is a known model, false otherwise.
     */
    private static boolean isKnownModel(String candidate) {
        for (String model : DeviceModels.getDeviceModels()) {
            if (model.equalsIgnoreCase(candidate)) {
                return true;
            }
        }
        return false;
    }
}

