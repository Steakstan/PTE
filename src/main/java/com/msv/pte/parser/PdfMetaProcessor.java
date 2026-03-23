package com.msv.pte.parser;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.text.PDFTextStripperByArea;

import java.awt.Rectangle;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts header metadata from structured PDF templates
 * using page regions instead of full-page text parsing.
 */
public class PdfMetaProcessor {

    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "(?i)\\bE-?MAIL\\s*:?\\s*([A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,})\\b"
    );

    private static final Pattern ORDER_TOKEN_PATTERN = Pattern.compile("\\b[A-Z0-9]{5,12}\\b");

    private static final Pattern DOCUMENT_DATE_PATTERN = Pattern.compile(
            "(?i)\\bvom\\s+(\\d{1,2}\\.\\d{1,2}\\.\\d{4})\\b"
    );

    private PdfMetaProcessor() {
    }

    public record DocumentMeta(
            String email,
            String recipient,
            String shippingAddress,
            String orderNumber,
            String documentDate
    ) {
    }

    public static DocumentMeta extractMeta(File pdfFile, String fallbackPdfText) {
        String emailFromText = findEmail(fallbackPdfText);
        String orderFromText = findOrderFromText(fallbackPdfText);
        String documentDateFromText = findDocumentDate(fallbackPdfText);

        try (PDDocument document = PDDocument.load(pdfFile)) {
            if (document.getNumberOfPages() == 0) {
                return new DocumentMeta(emailFromText, null, null, orderFromText, documentDateFromText);
            }

            PDPage page = document.getPage(0);
            PDRectangle box = page.getCropBox();

            int pageWidth = Math.round(box.getWidth());
            int pageHeight = Math.round(box.getHeight());

            PDFTextStripperByArea stripper = new PDFTextStripperByArea();
            stripper.setSortByPosition(true);

            Rectangle recipientRect = rect(pageWidth, pageHeight, 0.02f, 0.14f, 0.47f, 0.17f);
            Rectangle shippingRect = rect(pageWidth, pageHeight, 0.02f, 0.30f, 0.47f, 0.15f);
            Rectangle orderRect = rect(pageWidth, pageHeight, 0.50f, 0.29f, 0.44f, 0.15f);
            Rectangle footerRect = rect(pageWidth, pageHeight, 0.02f, 0.92f, 0.80f, 0.06f);

            stripper.addRegion("recipient", recipientRect);
            stripper.addRegion("shipping", shippingRect);
            stripper.addRegion("order", orderRect);
            stripper.addRegion("footer", footerRect);

            stripper.extractRegions(page);

            String recipientRaw = stripper.getTextForRegion("recipient");
            String shippingRaw = stripper.getTextForRegion("shipping");
            String orderRaw = stripper.getTextForRegion("order");
            String footerRaw = stripper.getTextForRegion("footer");

            String recipient = cleanupAddressBlock(
                    recipientRaw,
                    "Empfänger",
                    "Versandanschrift",
                    "Belegnummer",
                    "Kundennummer",
                    "Datum",
                    "Bei Rückfragen bitte angeben",
                    "Bestellung",
                    "Bestelltext"
            );

            String shipping = cleanupAddressBlock(
                    shippingRaw,
                    "Versandanschrift",
                    "Bestellung",
                    "Bestelltext",
                    "Pos.",
                    "Bezeichnung",
                    "Menge",
                    "Wir danken"
            );

            String orderNumber = extractOrderNumber(orderRaw);

            String email = findEmail(footerRaw);
            if (!hasText(email)) {
                email = emailFromText;
            }

            if (!hasText(orderNumber)) {
                orderNumber = orderFromText;
            }

            String documentDate = documentDateFromText;

            return new DocumentMeta(email, recipient, shipping, orderNumber, documentDate);

        } catch (IOException e) {
            return new DocumentMeta(emailFromText, null, null, orderFromText, documentDateFromText);
        }
    }

    public static String findEmail(String text) {
        if (!hasText(text)) {
            return null;
        }

        Matcher matcher = EMAIL_PATTERN.matcher(text);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    public static String findOrderFromText(String text) {
        if (!hasText(text)) {
            return null;
        }

        String[] lines = text.split("\\R");
        boolean afterBestellung = false;

        for (String rawLine : lines) {
            String line = normalizeLine(rawLine);
            if (!hasText(line)) {
                continue;
            }

            String lower = line.toLowerCase();

            if (lower.startsWith("bestellung")) {
                String rest = line.substring("Bestellung".length()).trim();
                String token = extractOrderTokenFromLine(rest);
                if (hasText(token)) {
                    return token;
                }
                afterBestellung = true;
                continue;
            }

            if (lower.startsWith("bestelltext")) {
                String rest = line.substring("Bestelltext".length()).trim();
                String token = extractOrderTokenFromLine(rest);
                if (hasText(token)) {
                    return token;
                }
                afterBestellung = true;
                continue;
            }

            if (afterBestellung) {
                String token = extractOrderTokenFromLine(line);
                if (hasText(token)) {
                    return token;
                }

                if (looksLikeSectionHeader(line)) {
                    break;
                }
            }
        }

        return null;
    }

    private static String extractOrderNumber(String orderAreaText) {
        if (!hasText(orderAreaText)) {
            return null;
        }

        String[] lines = orderAreaText.split("\\R");
        boolean afterBestellung = false;

        for (String rawLine : lines) {
            String line = normalizeLine(rawLine);
            if (!hasText(line)) {
                continue;
            }

            String lower = line.toLowerCase();

            if (lower.startsWith("bestellung")) {
                String rest = line.substring("Bestellung".length()).trim();
                String token = extractOrderTokenFromLine(rest);
                if (hasText(token)) {
                    return token;
                }
                afterBestellung = true;
                continue;
            }

            if (lower.startsWith("bestelltext")) {
                String rest = line.substring("Bestelltext".length()).trim();
                String token = extractOrderTokenFromLine(rest);
                if (hasText(token)) {
                    return token;
                }
                afterBestellung = true;
                continue;
            }

            if (afterBestellung) {
                String token = extractOrderTokenFromLine(line);
                if (hasText(token)) {
                    return token;
                }
            }
        }

        return extractOrderTokenFromLine(orderAreaText);
    }

    private static String extractOrderTokenFromLine(String text) {
        if (!hasText(text)) {
            return null;
        }

        Matcher matcher = ORDER_TOKEN_PATTERN.matcher(text);
        while (matcher.find()) {
            String token = matcher.group();

            if (isValidOrderToken(token)) {
                return token;
            }
        }

        return null;
    }

    private static boolean isValidOrderToken(String token) {
        if (!hasText(token)) {
            return false;
        }

        String upper = token.toUpperCase();

        if (upper.equals("BESTELLUNG")
                || upper.equals("BESTELLTEXT")
                || upper.equals("WUNSCHLIEFERTERMIN")
                || upper.equals("DATUM")
                || upper.equals("BELEGNUMMER")
                || upper.equals("KUNDENNUMMER")) {
            return false;
        }

        if (token.matches("\\d+")) {
            return false;
        }

        if (token.matches("\\d{1,2}\\.\\d{1,2}\\.\\d{2,4}")) {
            return false;
        }

        return true;
    }

    private static String cleanupAddressBlock(String raw, String ownHeader, String... stopHeaders) {
        if (!hasText(raw)) {
            return null;
        }

        String[] lines = raw.split("\\R");
        List<String> cleanedLines = new ArrayList<>();

        for (String rawLine : lines) {
            String line = normalizeLine(rawLine);
            if (!hasText(line)) {
                continue;
            }

            line = removeLeadingHeader(line, ownHeader);

            if (!hasText(line)) {
                continue;
            }

            String cutLine = cutAtStopHeader(line, stopHeaders);
            if (!hasText(cutLine)) {
                break;
            }

            if (isExactStopHeader(line, stopHeaders)) {
                break;
            }

            cleanedLines.add(cutLine);

            if (!cutLine.equals(line)) {
                break;
            }
        }

        return cleanedLines.isEmpty() ? null : String.join(", ", cleanedLines);
    }

    private static String removeLeadingHeader(String line, String ownHeader) {
        if (!hasText(line) || !hasText(ownHeader)) {
            return line;
        }

        if (line.equalsIgnoreCase(ownHeader)) {
            return "";
        }

        if (line.regionMatches(true, 0, ownHeader, 0, ownHeader.length())) {
            return line.substring(ownHeader.length()).trim();
        }

        return line;
    }

    private static String cutAtStopHeader(String line, String... stopHeaders) {
        if (!hasText(line)) {
            return line;
        }

        int cutIndex = -1;

        for (String stopHeader : stopHeaders) {
            int idx = indexOfIgnoreCase(line, stopHeader);
            if (idx >= 0 && (cutIndex == -1 || idx < cutIndex)) {
                cutIndex = idx;
            }
        }

        if (cutIndex == -1) {
            return line;
        }

        return line.substring(0, cutIndex).trim();
    }

    private static boolean isExactStopHeader(String line, String... stopHeaders) {
        if (!hasText(line)) {
            return false;
        }

        for (String stopHeader : stopHeaders) {
            if (line.equalsIgnoreCase(stopHeader)) {
                return true;
            }
        }

        return false;
    }

    private static boolean looksLikeSectionHeader(String line) {
        String lower = normalizeLine(line).toLowerCase();
        return lower.startsWith("pos.")
                || lower.startsWith("bezeichnung")
                || lower.startsWith("menge")
                || lower.startsWith("wunschliefertermin")
                || lower.startsWith("wir danken");
    }

    private static int indexOfIgnoreCase(String text, String search) {
        return text.toLowerCase().indexOf(search.toLowerCase());
    }

    private static Rectangle rect(int pageWidth, int pageHeight,
                                  float xRatio, float yRatio, float wRatio, float hRatio) {
        int x = Math.round(pageWidth * xRatio);
        int y = Math.round(pageHeight * yRatio);
        int w = Math.round(pageWidth * wRatio);
        int h = Math.round(pageHeight * hRatio);
        return new Rectangle(x, y, w, h);
    }

    public static String findDocumentDate(String text) {
        if (!hasText(text)) {
            return null;
        }

        Matcher matcher = DOCUMENT_DATE_PATTERN.matcher(text.replace('\n', ' '));
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    private static String normalizeLine(String line) {
        return line == null ? null
                : line.replace('\u00A0', ' ')
                .replaceAll("\\s{2,}", " ")
                .trim();
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}