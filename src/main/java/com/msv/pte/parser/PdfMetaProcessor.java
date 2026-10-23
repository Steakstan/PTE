package com.msv.pte.parser;

import com.msv.pte.parser.PdfDocumentReader.DocumentData;
import com.msv.pte.parser.PdfDocumentReader.PageData;
import com.msv.pte.parser.PdfDocumentReader.TextFragment;
import com.msv.pte.parser.PdfDocumentReader.TextLine;

import java.io.File;
import java.io.IOException;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Extracts document headers; order rows in updates belong to the table parser. */
public final class PdfMetaProcessor {

    private static final int FLAGS =
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;

    private static final String DATE =
            "(\\d{1,2}\\.\\d{1,2}\\.\\d{4})(?!\\d)";

    private static final Pattern EMAIL = Pattern.compile(
            "(?<![\\p{L}\\p{N}])E[- ]?Mail\\s*:?\\s*"
                    + "([A-Z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Z0-9.-]+\\.[A-Z]{2,})",
            FLAGS
    );

    private static final Pattern VOM_DATE = Pattern.compile(
            "\\bvom\\s*:?\\s*" + DATE,
            FLAGS
    );

    private static final Pattern CHANGE_DATE = Pattern.compile(
            "(?:Änderung|Aenderung)\\s*:?\\s*vom\\s*:?\\s*" + DATE,
            FLAGS
    );

    private static final Pattern LABEL = Pattern.compile(
            "(?<![\\p{L}\\p{N}])"
                    + "(Auftragseingangsbest(?:ä|ae|a)tigung|Auftragsinformation"
                    + "|Empfänger|Empfaenger|Auftraggeber|Versandanschrift|Besteller"
                    + "|Belegnummer|Kundennummer|Datum|Bestellung|Bestelltext"
                    + "|Komplettlieferung|Bezeichnung|Artikel|Menge|Pos\\.?)"
                    + "(?![\\p{L}\\p{N}])[\\t ]*[:=]?",
            FLAGS
    );

    private static final Pattern BODY = Pattern.compile(
            "^(?:Pos\\.?(?:\\s|$)|Sehr\\s+geehrt|Wir\\s+danken|Im\\s+Folgenden)",
            FLAGS
    );

    private static final DateTimeFormatter INPUT_DATE = DateTimeFormatter
            .ofPattern("d.M.uuuu", Locale.ROOT)
            .withResolverStyle(ResolverStyle.STRICT);

    private static final DateTimeFormatter OUTPUT_DATE = DateTimeFormatter
            .ofPattern("dd.MM.uuuu", Locale.ROOT);

    private PdfMetaProcessor() {
    }

    public enum DocumentType {
        ORDER_CONFIRMATION,
        ORDER_UPDATE,
        UNKNOWN
    }

    public record DocumentMeta(
            String email,
            String recipient,
            String shippingAddress,
            String orderNumber,
            String documentDate,
            DocumentType documentType,
            List<String> problems
    ) {
        public DocumentMeta {
            Objects.requireNonNull(documentType, "documentType");
            problems = List.copyOf(problems);
        }

        /** Keeps source compatibility with callers constructing the old record. */
        public DocumentMeta(
                String email,
                String recipient,
                String shippingAddress,
                String orderNumber,
                String documentDate
        ) {
            this(
                    email,
                    recipient,
                    shippingAddress,
                    orderNumber,
                    documentDate,
                    DocumentType.UNKNOWN,
                    List.of()
            );
        }

        public boolean requiresReview() {
            return !problems.isEmpty();
        }
    }

    private record Anchor(
            String name,
            float left,
            float top,
            float right,
            float bottom,
            TextLine line
    ) {
        float height() {
            return bottom - top;
        }
    }

    private record Header(
            PageData page,
            List<Anchor> anchors,
            float bodyTop
    ) {
    }

    /** Preferred API: uses the existing snapshot and never opens the PDF again. */
    public static DocumentMeta extractMeta(DocumentData document) {
        Objects.requireNonNull(document, "document");

        List<String> problems = new ArrayList<>(document.problems());

        PageData page = document.pages().stream()
                .filter(PageData::hasText)
                .findFirst()
                .orElse(null);

        if (page == null) {
            problems.add(
                    "Dokumentkopf fehlt; kein extrahierbarer Text vorhanden."
            );

            return result(
                    null, null, null, null, null,
                    DocumentType.UNKNOWN, problems
            );
        }

        if (page.pageNumber() != 1) {
            problems.add(
                    "Dokumentkopf wurde erst auf Seite "
                            + page.pageNumber() + " gefunden."
            );
        }

        Header header = scan(page);
        DocumentType type = detectType(page.text());

        if (type == DocumentType.UNKNOWN) {
            problems.add("Dokumenttyp ist unbekannt oder nicht eindeutig.");
        }

        String recipient = address(header, "empfaenger", problems);

        if (recipient == null) {
            recipient = address(header, "auftraggeber", problems);
        }

        String shipping = address(header, "versandanschrift", problems);
        String date = headerDate(header, problems);
        String order = null;

        if (type != DocumentType.ORDER_UPDATE) {
            Set<String> values = new LinkedHashSet<>();

            for (PageData candidate : document.pages()) {
                Header current = candidate == page
                        ? header
                        : scan(candidate);

                for (Anchor anchor : current.anchors()) {
                    if (anchor.name().equals("bestellung")
                            && anchor.top() < current.bodyTop()) {
                        String value = field(current, anchor);

                        if (value != null) {
                            values.add(value);
                        }
                    }
                }
            }

            if (!values.isEmpty()) {
                var parsed = OrderNumberProcessor.parseValue(
                        values.iterator().next()
                );

                order = parsed.orderNumber();
                problems.addAll(parsed.problems());

                long distinctValues = values.stream()
                        .map(value -> value.toUpperCase(Locale.ROOT))
                        .distinct()
                        .count();

                if (distinctValues > 1) {
                    problems.add(
                            "Mehrere unterschiedliche Bestellnummern "
                                    + "im Dokumentkopf; Zuordnung prüfen."
                    );
                }
            } else {
                var parsed = OrderNumberProcessor.findOrderNumberResult(
                        page.text()
                );

                order = parsed.orderNumber();
                problems.addAll(parsed.problems());

                if (parsed.hasValue()) {
                    problems.add(
                            "Bestellnummer nur aus Fließtext oder "
                                    + "Bestelltext ermittelt; prüfen."
                    );
                }
            }
        }

        return result(
                findEmail(document.text()),
                recipient,
                shipping,
                order,
                date,
                type,
                problems
        );
    }

    /** Legacy API; read failures remain visible when fallback text is usable. */
    public static DocumentMeta extractMeta(
            File pdfFile,
            String fallbackPdfText
    ) {
        if (pdfFile != null) {
            try {
                DocumentData document = PdfDocumentReader.read(pdfFile);

                if (!document.hasText()
                        && !normalize(fallbackPdfText).isBlank()) {
                    DocumentMeta fallback = fromText(
                            fallbackPdfText,
                            "PDF enthält keinen extrahierbaren Text; "
                                    + "nur Ersatztext verwendet."
                    );

                    List<String> problems = new ArrayList<>(
                            document.problems()
                    );
                    problems.addAll(fallback.problems());

                    return result(
                            fallback.email(),
                            null,
                            null,
                            fallback.orderNumber(),
                            fallback.documentDate(),
                            fallback.documentType(),
                            problems
                    );
                }

                return extractMeta(document);
            } catch (IOException failure) {
                return fromText(
                        fallbackPdfText,
                        "PDF-Kopf konnte nicht gelesen werden ("
                                + failure.getClass().getSimpleName()
                                + "); nur Ersatztext verwendet."
                );
            }
        }

        return fromText(
                fallbackPdfText,
                "Keine PDF-Datei übergeben; nur Ersatztext verwendet."
        );
    }

    public static String findEmail(String text) {
        Matcher matcher = EMAIL.matcher(normalize(text));
        return matcher.find() ? matcher.group(1) : null;
    }

    /** For single-order text; updates have a separate order for each block. */
    public static String findOrderFromText(String text) {
        return detectType(text) == DocumentType.ORDER_UPDATE
                ? null
                : OrderNumberProcessor.findOrderNumber(text);
    }

    /** Returns a validated header date, never a delivery date from the body. */
    public static String findDocumentDate(String text) {
        return dateFromTitle(normalize(text), new ArrayList<>());
    }

    private static DocumentMeta fromText(String text, String reason) {
        List<String> problems = new ArrayList<>();
        problems.add(reason);

        DocumentType type = detectType(text);

        if (type == DocumentType.UNKNOWN) {
            problems.add("Dokumenttyp ist unbekannt oder nicht eindeutig.");
        }

        String order = null;

        if (type != DocumentType.ORDER_UPDATE) {
            var parsed = OrderNumberProcessor.findOrderNumberResult(text);
            order = parsed.orderNumber();
            problems.addAll(parsed.problems());
        }

        String date = dateFromTitle(normalize(text), problems);

        if (date == null) {
            problems.add("Dokumentdatum fehlt oder ist ungültig.");
        }

        return result(
                findEmail(text),
                null,
                null,
                order,
                date,
                type,
                problems
        );
    }

    private static DocumentMeta result(
            String email,
            String recipient,
            String shipping,
            String order,
            String date,
            DocumentType type,
            List<String> problems
    ) {
        return new DocumentMeta(
                email,
                recipient,
                shipping,
                order,
                date,
                type,
                List.copyOf(new LinkedHashSet<>(problems))
        );
    }

    private static DocumentType detectType(String text) {
        String value = normalize(text)
                .toLowerCase(Locale.ROOT)
                .replace("ä", "ae")
                .replace(" ", "")
                .replace("\n", "")
                .replace("\r", "");

        boolean confirmation =
                value.contains("auftragseingangsbestaetigung")
                        || value.contains("auftragseingangsbestatigung");

        boolean update = value.contains("auftragsinformation");

        if (confirmation == update) {
            return DocumentType.UNKNOWN;
        }

        return confirmation
                ? DocumentType.ORDER_CONFIRMATION
                : DocumentType.ORDER_UPDATE;
    }

    private static Header scan(PageData page) {
        List<Anchor> anchors = new ArrayList<>();
        float bodyTop = page.height();

        for (TextLine line : page.lines()) {
            String value = normalize(line.text()).strip();
            String lower = value.toLowerCase(Locale.ROOT);

            if (BODY.matcher(value).find()
                    || (lower.contains("belegnummer")
                    && lower.contains("bestellung")
                    && lower.contains("bestelltext"))) {
                bodyTop = Math.min(bodyTop, line.top());
            }

            StringBuilder text = new StringBuilder();
            List<TextFragment> positions = new ArrayList<>();
            int previousWord = -1;

            for (TextFragment fragment : line.fragments()) {
                if (previousWord >= 0
                        && previousWord != fragment.wordIndex()) {
                    text.append(' ');
                    positions.add(null);
                }

                String fragmentText = normalize(fragment.text());
                text.append(fragmentText);

                for (int i = 0; i < fragmentText.length(); i++) {
                    positions.add(fragment);
                }

                previousWord = fragment.wordIndex();
            }

            Matcher matcher = LABEL.matcher(text);

            while (matcher.find()) {
                float left = Float.POSITIVE_INFINITY;
                float top = Float.POSITIVE_INFINITY;
                float right = 0;
                float bottom = 0;

                for (int i = matcher.start(); i < matcher.end(); i++) {
                    TextFragment fragment = positions.get(i);

                    if (fragment == null) {
                        continue;
                    }

                    left = Math.min(left, fragment.x());
                    top = Math.min(top, fragment.y());
                    right = Math.max(right, fragment.right());
                    bottom = Math.max(bottom, fragment.bottom());
                }

                if (Float.isFinite(left)) {
                    String name = matcher.group(1)
                            .toLowerCase(Locale.ROOT)
                            .replace("ä", "ae");

                    anchors.add(
                            new Anchor(
                                    name, left, top, right, bottom, line
                            )
                    );
                }
            }
        }

        anchors.sort(
                Comparator.comparingDouble(Anchor::top)
                        .thenComparingDouble(Anchor::left)
        );

        return new Header(page, List.copyOf(anchors), bodyTop);
    }

    private static float bottom(Header header, Anchor anchor) {
        float bottom = header.bodyTop();

        for (Anchor other : header.anchors()) {
            if (other.top() > anchor.bottom() + 1
                    && Math.abs(other.left() - anchor.left())
                    <= Math.max(16, anchor.height() * 2)) {
                bottom = Math.min(bottom, other.top());
            }
        }

        return bottom;
    }

    private static float right(
            Header header,
            Anchor anchor,
            float bottom
    ) {
        float right = header.page().width();

        for (Anchor other : header.anchors()) {
            if (other.left() > anchor.right() + 8
                    && other.top() < bottom
                    && other.bottom() >= anchor.top()
                    - Math.max(18, anchor.height() * 3)) {
                right = Math.min(right, other.left() - 3);
            }
        }

        return right;
    }

    private static String field(Header header, Anchor anchor) {
        float bottom = bottom(header, anchor);
        float right = right(header, anchor, bottom);

        if (anchor.right() + 0.5f < right) {
            String sameLine = clean(
                    anchor.line().textBetween(
                            anchor.right() + 0.5f,
                            right
                    )
            );

            if (!sameLine.isEmpty()) {
                return sameLine;
            }
        }

        return header.page().lines().stream()
                .filter(line ->
                        line.top() >= anchor.bottom() - 1
                                && line.top() < bottom
                )
                .filter(line ->
                        line.top() - anchor.bottom()
                                <= Math.max(30, anchor.height() * 4)
                )
                .sorted(Comparator.comparingDouble(TextLine::top))
                .map(line -> clean(
                        line.textBetween(
                                Math.max(0, anchor.left() - 2),
                                right
                        )
                ))
                .filter(value -> !value.isEmpty())
                .findFirst()
                .orElse(null);
    }

    private static String address(
            Header header,
            String name,
            List<String> problems
    ) {
        Anchor anchor = header.anchors().stream()
                .filter(candidate ->
                        candidate.name().equals(name)
                                && candidate.top() < header.bodyTop()
                )
                .findFirst()
                .orElse(null);

        if (anchor == null) {
            return null;
        }

        float bottom = bottom(header, anchor);
        float right = right(header, anchor, bottom);
        List<String> lines = new ArrayList<>();
        float previousBottom = anchor.bottom();

        List<TextLine> sorted = header.page().lines().stream()
                .sorted(Comparator.comparingDouble(TextLine::top))
                .toList();

        for (TextLine line : sorted) {
            if (line.top() < anchor.top() - 1
                    || line.top() >= bottom) {
                continue;
            }

            String value = clean(
                    line.textBetween(
                            Math.max(0, anchor.left() - 2),
                            right
                    )
            );

            if (value.isEmpty()) {
                continue;
            }

            if (line == anchor.line()) {
                value = anchor.right() + 0.5f < right
                        ? clean(line.textBetween(
                        anchor.right() + 0.5f,
                        right
                ))
                        : "";
            }

            if (value.isEmpty()) {
                continue;
            }

            if (line.top() - previousBottom
                    > Math.max(30, anchor.height() * 4)
                    || BODY.matcher(value).find()
                    || value.toLowerCase(Locale.ROOT)
                    .startsWith("bei rückfragen")) {
                break;
            }

            lines.add(value);
            previousBottom = line.bottom();
        }

        if (lines.isEmpty()) {
            problems.add(
                    "Adressfeld '" + name
                            + "' ist leer oder konnte nicht abgegrenzt werden."
            );
            return null;
        }

        return String.join(", ", lines);
    }

    private static String headerDate(
            Header header,
            List<String> problems
    ) {
        for (Anchor title : header.anchors()) {
            if (!title.name().startsWith("auftragseingangsbest")
                    && !title.name().equals("auftragsinformation")) {
                continue;
            }

            float bottom = Math.min(
                    header.bodyTop(),
                    title.bottom() + Math.max(36, title.height() * 5)
            );

            if (bottom <= title.top()) {
                continue;
            }

            String area = header.page().textInArea(
                    Math.max(0, title.left() - 12),
                    Math.max(0, title.top() - 2),
                    header.page().width(),
                    bottom
            );

            Matcher change = CHANGE_DATE.matcher(area);
            Matcher normal = VOM_DATE.matcher(area);

            if (change.find()) {
                return validDate(change.group(1), problems);
            }

            if (normal.find()) {
                return validDate(normal.group(1), problems);
            }
        }

        for (Anchor anchor : header.anchors()) {
            if (anchor.name().equals("datum")
                    && anchor.top() < header.bodyTop()) {
                String value = field(header, anchor);

                if (value != null) {
                    String date = validDate(value, problems);

                    if (date != null) {
                        problems.add(
                                "Dokumentdatum nur aus dem Feld Datum "
                                        + "ermittelt; Titelzeile fehlt."
                        );
                        return date;
                    }
                }
            }
        }

        problems.add("Dokumentdatum im Kopf fehlt oder ist ungültig.");
        return null;
    }

    private static String dateFromTitle(
            String text,
            List<String> problems
    ) {
        String[] lines = text.split("\\R");

        for (int i = 0; i < lines.length; i++) {
            if (detectType(lines[i]) == DocumentType.UNKNOWN) {
                continue;
            }

            StringBuilder block = new StringBuilder(lines[i]);
            int added = 0;

            for (int j = i + 1; j < lines.length && added < 3; j++) {
                if (lines[j].isBlank()) {
                    continue;
                }

                String lower = lines[j].toLowerCase(Locale.ROOT);

                if (LABEL.matcher(lines[j]).find()
                        || BODY.matcher(lines[j].strip()).find()
                        || lower.contains("liefertermin")) {
                    break;
                }

                block.append('\n').append(lines[j]);
                added++;
            }

            Matcher change = CHANGE_DATE.matcher(block);
            Matcher normal = VOM_DATE.matcher(block);

            if (change.find()) {
                return validDate(change.group(1), problems);
            }

            if (normal.find()) {
                return validDate(normal.group(1), problems);
            }
        }

        return null;
    }

    private static String validDate(
            String raw,
            List<String> problems
    ) {
        try {
            return LocalDate.parse(raw.strip(), INPUT_DATE)
                    .format(OUTPUT_DATE);
        } catch (DateTimeParseException failure) {
            problems.add(
                    "Dokumentdatum ist keine gültige Kalenderangabe."
            );
            return null;
        }
    }

    private static String clean(String text) {
        return normalize(text)
                .replaceAll("[\\s\\p{Z}]+", " ")
                .strip()
                .replaceFirst("^[:=]\\s*", "");
    }

    private static String normalize(String text) {
        return text == null
                ? ""
                : Normalizer.normalize(text, Normalizer.Form.NFKC)
                .replace("\u00AD", "")
                .replace("\u200B", "")
                .replace("\uFEFF", "");
    }
}