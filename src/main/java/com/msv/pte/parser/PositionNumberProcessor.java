package com.msv.pte.parser;

import com.msv.pte.parser.DesiredDateProcessor.DateResult;
import com.msv.pte.parser.ModelCatalog.ModelResult;
import com.msv.pte.parser.PdfDocumentReader.DocumentData;
import com.msv.pte.parser.PdfDocumentReader.PageData;
import com.msv.pte.parser.PdfDocumentReader.TextFragment;
import com.msv.pte.parser.PdfDocumentReader.TextLine;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Confirmation parser.
 * Delivery-update tables are handled by a separate parser.
 */
public final class PositionNumberProcessor {

    private static final int FLAGS =
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;

    private static final Pattern POSITION = Pattern.compile(
            "^\\s*[»›>]*\\s*"
                    + "(?:([0-9]{3,6})(?=\\s|$)"
                    + "|([0-9]{3})(?=[A-Za-z]))\\s*"
    );

    private static final Pattern POSITION_CELL = Pattern.compile(
            "[»›>]*\\s*([0-9]{1,6})"
    );

    private static final Pattern EAN = Pattern.compile(
            "E\\s*A\\s*N\\s*[:=]\\s*"
                    + "|(?<![\\p{L}\\p{N}])EAN\\s+",
            FLAGS
    );

    private static final Pattern DATE_LABEL = Pattern.compile(
            "(?<![\\p{L}\\p{N}])Wunsch\\s*liefer\\s*-?\\s*termin"
                    + "(?:[123](?=\\s|:))?(?!\\p{L})\\s*[:=]?\\s*",
            FLAGS
    );

    private static final Pattern EXPECTED = Pattern.compile(
            "voraussicht(?:licher|l\\.?)\\s*liefer\\s*-?\\s*termin",
            FLAGS
    );

    private static final Pattern STATUS = Pattern.compile(
            "(?<![\\p{L}\\p{N}])"
                    + "(?:ausgelaufen|auslauf|neuanlauf|ersatzlos"
                    + "|bezugsberechtigung|nicht\\s+bezogen|berechtigung"
                    + "|nicht\\s+bekannt|unbekannt)"
                    + "(?![\\p{L}\\p{N}])",
            FLAGS
    );

    private static final Pattern KIT = Pattern.compile(
            "BESTEHEND\\s+AUS\\s*:?",
            FLAGS
    );

    private static final Pattern END = Pattern.compile(
            "^(?:Gesamt(?:betrag|wert)?|Nettowert|Mehrwertsteuer"
                    + "|Zwischensumme|Mit\\s+freundlichen"
                    + "|Wir\\s+danken|Zahlungsbedingungen)\\b",
            FLAGS
    );

    private static final Pattern POS_HEADER = Pattern.compile(
            "(?<!\\p{L})Pos\\.?(?!\\p{L})",
            FLAGS
    );

    private static final Pattern MODEL_HEADER = Pattern.compile(
            "\\bBezeichnung\\b",
            FLAGS
    );

    private static final Pattern QUANTITY_HEADER = Pattern.compile(
            "\\bMenge\\b",
            FLAGS
    );

    private static final Pattern SPACE = Pattern.compile(
            "[\\s\\p{Z}]+"
    );

    private PositionNumberProcessor() {
    }

    public record ParseResult(
            List<PositionData> positions,
            List<String> problems
    ) {
        public ParseResult {
            positions = List.copyOf(positions);
            problems = List.copyOf(problems);
        }

        public boolean requiresReview() {
            return !problems.isEmpty()
                    || positions.stream()
                    .anyMatch(PositionData::requiresReview);
        }
    }

    /**
     * One model row.
     * Components retain their parent's position number.
     */
    public static final class PositionData {

        private final String positionNumber;

        private final List<ModelEntry> models = new ArrayList<>();
        private final List<String> lines = new ArrayList<>();
        private final List<String> problems = new ArrayList<>();

        private DateResult dateResult =
                DesiredDateProcessor.parseValue(null);

        private List<DateResult> dateCandidates = List.of();

        private ModelResult modelResult;
        private String ean;
        private int pageNumber;
        private int lineNumber;
        private boolean component;

        public PositionData(String positionNumber) {
            this.positionNumber = positionNumber;
        }

        public String getPositionNumber() {
            return positionNumber;
        }

        public List<ModelEntry> getModels() {
            return models;
        }

        public List<String> getLines() {
            return lines;
        }

        public String getDesiredDate() {
            return dateResult.rawValue();
        }

        public DateResult getDateResult() {
            return dateResult;
        }

        public List<DateResult> getDateCandidates() {
            return dateCandidates;
        }

        public ModelResult getModelResult() {
            return modelResult;
        }

        public String getEan() {
            return ean;
        }

        public int getPageNumber() {
            return pageNumber;
        }

        public int getLineNumber() {
            return lineNumber;
        }

        public boolean isComponent() {
            return component;
        }

        public List<String> getProblems() {
            return List.copyOf(problems);
        }

        public void setDesiredDate(String desiredDate) {
            dateResult = DesiredDateProcessor.parseValue(desiredDate);
            dateCandidates = List.of(dateResult);
        }

        public boolean requiresReview() {
            return !problems.isEmpty()
                    || dateResult.requiresReview()
                    || models.isEmpty()
                    || models.stream().anyMatch(ModelEntry::red);
        }
    }

    private record Span(
            float left,
            float top,
            float right,
            float bottom
    ) {
    }

    private record Columns(
            float positionLeft,
            float modelLeft,
            float right,
            float top
    ) {
    }

    private record Row(
            String text,
            String modelText,
            String position,
            int page,
            int line
    ) {
    }

    private record Delivery(
            int row,
            DateResult value
    ) {
    }

    private static final class Item {

        private String rawModel;
        private String ean;

        private final Row source;
        private final int row;
        private final boolean component;

        private final List<String> problems = new ArrayList<>();

        private Item(
                String rawModel,
                String ean,
                Row source,
                int row,
                boolean component
        ) {
            this.rawModel = rawModel;
            this.ean = ean;
            this.source = source;
            this.row = row;
            this.component = component;
        }
    }

    private static final class Block {

        private final String position;
        private final Row source;

        private final List<Row> rows = new ArrayList<>();
        private final List<Item> items = new ArrayList<>();
        private final List<String> problems = new ArrayList<>();

        private boolean components;

        private Block(String position, Row source) {
            this.position = position;
            this.source = source;

            if (position == null) {
                problems.add("Positionsnummer fehlt.");
            } else if (position.length() != 3) {
                problems.add(
                        "Ungewöhnliche Länge der Positionsnummer."
                );
            }
        }
    }

    /**
     * Uses coordinates and the shared catalog.
     * Does not reopen the PDF.
     */
    public static ParseResult parse(DocumentData document) {
        return parse(document, ModelCatalog.getDefault());
    }

    public static ParseResult parse(
            DocumentData document,
            ModelCatalog catalog
    ) {
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(catalog, "catalog");

        List<String> problems = new ArrayList<>(
                document.problems()
        );

        List<Row> rows = new ArrayList<>();

        for (PageData page : document.pages()) {
            Columns columns = columns(page);

            if (columns == null && page.hasText()) {
                problems.add(
                        "Positionsspalten auf Seite "
                                + page.pageNumber()
                                + " nicht gefunden; Textzuordnung prüfen."
                );
            }

            boolean ended = false;

            for (TextLine line : page.lines()) {
                if (columns != null
                        && line.top() <= columns.top()) {
                    continue;
                }

                String text = normalize(line.text());

                if (END.matcher(text.strip()).find()) {
                    ended = true;
                }

                if (ended || text.isBlank()) {
                    continue;
                }

                if (columns == null) {
                    rows.add(
                            textRow(
                                    text,
                                    page.pageNumber(),
                                    line.lineNumber()
                            )
                    );
                } else {
                    String cell = clean(
                            line.textBetween(
                                    Math.max(
                                            0,
                                            columns.positionLeft() - 3
                                    ),
                                    columns.modelLeft() - 3
                            )
                    );

                    Matcher position =
                            POSITION_CELL.matcher(cell);

                    String number = position.matches()
                            ? position.group(1)
                            : null;

                    String model = normalize(
                            line.textBetween(
                                    Math.max(
                                            0,
                                            columns.modelLeft() - 2
                                    ),
                                    columns.right()
                            )
                    );

                    rows.add(
                            new Row(
                                    text,
                                    model,
                                    number,
                                    page.pageNumber(),
                                    line.lineNumber()
                            )
                    );
                }
            }
        }

        return process(
                rows,
                document.text(),
                catalog,
                problems
        );
    }

    /** Text-only compatibility path for the existing core. */
    public static List<PositionData> extractPositionsModelsAndDates(
            String pdfText
    ) {
        return parseText(
                pdfText,
                ModelCatalog.getDefault()
        ).positions();
    }

    public static ParseResult parseText(
            String pdfText,
            ModelCatalog catalog
    ) {
        Objects.requireNonNull(catalog, "catalog");

        List<Row> rows = new ArrayList<>();
        String[] pages = normalize(pdfText).split("\\f", -1);

        for (int page = 0; page < pages.length; page++) {
            String[] lines = pages[page].split("\\R", -1);
            boolean ended = false;
            int start = 0;

            for (int i = 0; i < lines.length; i++) {
                if (POS_HEADER.matcher(lines[i]).find()
                        && MODEL_HEADER.matcher(lines[i]).find()) {
                    start = i + 1;
                    break;
                }

                if (POSITION.matcher(lines[i]).find()
                        && EAN.matcher(lines[i]).find()) {
                    break;
                }
            }

            for (int line = start; line < lines.length; line++) {
                if (lines[line].toLowerCase(Locale.ROOT)
                        .contains("auftragseingangsbest")) {
                    int nextHeader = nextHeader(
                            lines,
                            line + 1
                    );

                    if (nextHeader >= 0) {
                        line = nextHeader;
                        ended = false;
                        continue;
                    }
                }

                if (END.matcher(lines[line].strip()).find()) {
                    ended = true;
                }

                if (!ended) {
                    rows.add(
                            textRow(
                                    lines[line],
                                    page + 1,
                                    line + 1
                            )
                    );
                }
            }
        }

        return process(
                rows,
                pdfText,
                catalog,
                new ArrayList<>(
                        List.of(
                                "Textmodus ohne Spaltenkoordinaten; "
                                        + "Zuordnung prüfen."
                        )
                )
        );
    }

    private static Row textRow(
            String text,
            int page,
            int line
    ) {
        Matcher matcher = POSITION.matcher(text);

        if (!matcher.find()) {
            return new Row(text, text, null, page, line);
        }

        String number = matcher.group(1) != null
                ? matcher.group(1)
                : matcher.group(2);

        String rest = text.substring(matcher.end());

        if (number.length() > 3 && rest.isBlank()) {
            return new Row(text, text, null, page, line);
        }

        return new Row(text, rest, number, page, line);
    }

    private static int nextHeader(
            String[] lines,
            int start
    ) {
        for (int i = start; i < lines.length; i++) {
            if (POS_HEADER.matcher(lines[i]).find()
                    && MODEL_HEADER.matcher(lines[i]).find()) {
                return i;
            }

            if (POSITION.matcher(lines[i]).find()
                    && EAN.matcher(lines[i]).find()) {
                return -1;
            }
        }

        return -1;
    }

    private static ParseResult process(
            List<Row> rows,
            String text,
            ModelCatalog catalog,
            List<String> problems
    ) {
        String typeText = clean(text)
                .toLowerCase(Locale.ROOT)
                .replace(" ", "");

        if (typeText.contains("auftragsinformation")
                && !typeText.contains("auftragseingangsbest")) {
            problems.add(
                    "Auftragsinformation benötigt den Parser "
                            + "für Liefertermin-Tabellen."
            );

            return new ParseResult(
                    List.of(),
                    distinct(problems)
            );
        }

        List<PositionData> result = new ArrayList<>();
        Block block = null;

        Map<String, Integer> occurrences =
                new LinkedHashMap<>();

        for (Row row : rows) {
            if (row.position() != null
                    && row.position().length() != 3
                    && !EAN.matcher(row.modelText()).find()
                    && !isStandaloneModel(
                    row.modelText(),
                    catalog
            )) {
                continue;
            }

            if (row.position() != null) {
                if (block != null) {
                    finish(block, catalog, result);
                }

                block = new Block(
                        row.position(),
                        row
                );

                if (occurrences.merge(
                        row.position(),
                        1,
                        Integer::sum
                ) > 1) {
                    block.problems.add(
                            "Positionsnummer kommt mehrfach vor; "
                                    + "beide Vorkommen erhalten."
                    );

                    problems.add(
                            "Wiederholte Positionsnummern; mögliche "
                                    + "Fortsetzung oder Dublette prüfen."
                    );
                }
            }

            Matcher ean = EAN.matcher(row.modelText());
            boolean hasEan = ean.find();

            if (block == null && hasEan) {
                block = new Block(null, row);
            }

            if (block == null) {
                continue;
            }

            int index = block.rows.size();
            block.rows.add(row);

            if (KIT.matcher(row.modelText()).find()) {
                block.components = true;
            }

            if (hasEan) {
                String model = clean(
                        row.modelText().substring(
                                0,
                                ean.start()
                        )
                );

                String after = clean(
                        row.modelText().substring(ean.end())
                );

                String value = after.isEmpty()
                        ? null
                        : after.split("\\s+", 2)[0];

                Item previous = block.items.isEmpty()
                        ? null
                        : block.items.get(
                        block.items.size() - 1
                );

                if (previous != null
                        && previous.ean == null
                        && index == previous.row + 1
                        && (model.isEmpty()
                        || canJoin(
                        previous.rawModel,
                        model,
                        catalog
                ))) {
                    if (!model.isEmpty()) {
                        previous.rawModel =
                                previous.rawModel + " " + model;

                        previous.problems.add(
                                "Modellbezeichnung aus zwei Zeilen "
                                        + "zusammengesetzt; prüfen."
                        );
                    }

                    previous.ean = value;
                } else {
                    block.items.add(
                            new Item(
                                    model,
                                    value,
                                    row,
                                    index,
                                    block.components
                            )
                    );
                }
            } else if (row.position() != null
                    && !row.modelText().isBlank()) {
                addWithoutEan(
                        block,
                        row,
                        index,
                        catalog
                );
            } else if (block.components
                    && isStandaloneModel(
                    row.modelText(),
                    catalog
            )) {
                addWithoutEan(
                        block,
                        row,
                        index,
                        catalog
                );
            }
        }

        if (block != null) {
            finish(block, catalog, result);
        }

        if (result.isEmpty()) {
            problems.add(
                    "Keine Gerätepositionen gefunden; "
                            + "PDF muss geprüft werden."
            );
        }

        return new ParseResult(
                result,
                distinct(problems)
        );
    }

    private static void addWithoutEan(
            Block block,
            Row row,
            int index,
            ModelCatalog catalog
    ) {
        String value = clean(row.modelText());

        if (!catalog.isKnown(value)) {
            String first = value.split("\\s+", 2)[0];

            if (catalog.isKnown(first)
                    || !value.contains(" ")) {
                value = first;
            } else {
                value = first;

                block.problems.add(
                        "Modell ohne EAN nur aus dem ersten "
                                + "Feldwort ermittelt; prüfen."
                );
            }
        }

        block.items.add(
                new Item(
                        value,
                        null,
                        row,
                        index,
                        block.components
                )
        );
    }

    private static boolean isStandaloneModel(
            String text,
            ModelCatalog catalog
    ) {
        String value = clean(text);

        return catalog.isKnown(value)
                || (value.matches(
                "[A-Za-z0-9][A-Za-z0-9./_-]*"
        )
                && value.matches(".*[A-Za-z].*")
                && value.matches(".*[0-9].*"));
    }

    private static boolean canJoin(
            String first,
            String second,
            ModelCatalog catalog
    ) {
        return first != null
                && (catalog.isKnown(
                first + " " + second
        )
                || catalog.isKnown(
                (first + second).replace(" ", "")
        ));
    }

    private static ModelResult model(
            String raw,
            ModelCatalog catalog
    ) {
        ModelResult result = catalog.parseValue(raw);
        String value = clean(raw);

        if (!result.knownModel() && value.contains(" ")) {
            String repaired = catalog.findCanonicalName(
                    value.replace(" ", "")
            );

            if (repaired != null) {
                return new ModelResult(
                        raw,
                        repaired,
                        true,
                        List.of(
                                "Leerzeichen in der Modellbezeichnung "
                                        + "rekonstruiert; prüfen."
                        )
                );
            }
        }

        return result;
    }

    private static void finish(
            Block block,
            ModelCatalog catalog,
            List<PositionData> result
    ) {
        if (block.items.isEmpty()) {
            block.items.add(
                    new Item(
                            null,
                            null,
                            block.source,
                            0,
                            false
                    )
            );
        }

        List<Delivery> dates = dates(block);

        Map<String, DateResult> unique =
                new LinkedHashMap<>();

        for (Delivery date : dates) {
            unique.putIfAbsent(
                    dateKey(date.value()),
                    date.value()
            );
        }

        List<String> sourceLines = block.rows.stream()
                .map(Row::text)
                .toList();

        for (int i = 0; i < block.items.size(); i++) {
            Item item = block.items.get(i);

            PositionData output =
                    new PositionData(block.position);

            output.lines.addAll(sourceLines);
            output.problems.addAll(block.problems);
            output.problems.addAll(item.problems);

            output.pageNumber = item.source.page();
            output.lineNumber = item.source.line();
            output.ean = item.ean;
            output.component = item.component;

            output.modelResult = model(
                    item.rawModel,
                    catalog
            );

            output.problems.addAll(
                    output.modelResult.problems()
            );

            if (item.ean == null) {
                output.problems.add(
                        "EAN zur Modellzeile fehlt."
                );
            } else if (!item.ean.matches("[0-9]{13}")) {
                output.problems.add(
                        "EAN ist nicht 13-stellig oder "
                                + "enthält ungültige Zeichen."
                );
            }

            List<DateResult> assigned = new ArrayList<>();

            if (unique.size() == 1) {
                assigned.add(
                        unique.values().iterator().next()
                );
            } else if (unique.size() > 1) {
                int end = i + 1 < block.items.size()
                        ? block.items.get(i + 1).row
                        : Integer.MAX_VALUE;

                for (Delivery date : dates) {
                    if (date.row() >= item.row
                            && date.row() < end) {
                        assigned.add(date.value());
                    }
                }

                if (assigned.isEmpty()) {
                    output.problems.add(
                            "Mehrere Termine in der Position; "
                                    + "kein eindeutiger Termin "
                                    + "für dieses Modell."
                    );
                }

                long distinctDates = assigned.stream()
                        .map(PositionNumberProcessor::dateKey)
                        .distinct()
                        .count();

                if (distinctDates > 1) {
                    output.problems.add(
                            "Widersprüchliche Termine für dieselbe "
                                    + "Modellzeile; erster Wert erhalten."
                    );
                }
            }

            output.dateCandidates = List.copyOf(assigned);

            output.dateResult = assigned.isEmpty()
                    ? DesiredDateProcessor.parseValue(null)
                    : assigned.get(0);

            if (output.dateResult.requiresReview()) {
                output.problems.add(
                        output.dateResult.problem()
                );
            }

            List<String> cleaned =
                    distinct(output.problems);

            output.problems.clear();
            output.problems.addAll(cleaned);

            output.models.add(
                    new ModelEntry(
                            output.modelResult.modelName(),
                            output.modelResult.requiresReview()
                                    || !output.problems.isEmpty()
                    )
            );

            result.add(output);
        }
    }

    private static List<Delivery> dates(Block block) {
        List<Delivery> dates = new ArrayList<>();

        for (int i = 0; i < block.rows.size(); i++) {
            String text = dateText(
                    block.rows.get(i).modelText()
            );

            Matcher label = DATE_LABEL.matcher(text);
            boolean found = label.find();
            int valueLine = i;

            if (!found
                    && text.toLowerCase(Locale.ROOT).matches(
                    ".*wunsch\\s*liefer\\s*-?\\s*$"
            )
                    && i + 1 < block.rows.size()) {
                text += " " + dateText(
                        block.rows.get(i + 1).modelText()
                );

                label = DATE_LABEL.matcher(text);
                found = label.find();
                valueLine++;
            }

            if (found) {
                do {
                    int start = label.end();
                    Matcher next = DATE_LABEL.matcher(text);

                    int end = next.find(start)
                            ? next.start()
                            : text.length();

                    String rest = clean(
                            text.substring(start, end)
                    );

                    if (rest.isEmpty()
                            && valueLine + 1
                            < block.rows.size()) {
                        String following = clean(
                                dateText(
                                        block.rows.get(
                                                valueLine + 1
                                        ).modelText()
                                )
                        );

                        if (!DATE_LABEL.matcher(
                                following
                        ).find()
                                && !EAN.matcher(
                                following
                        ).find()) {
                            rest = following;
                        }
                    }

                    Matcher expected =
                            EXPECTED.matcher(rest);

                    if (expected.find()) {
                        rest = clean(
                                rest.substring(
                                        0,
                                        expected.start()
                                )
                        );
                    }

                    String recognized =
                            DesiredDateProcessor.findDesiredDate(
                                    "Wunschliefertermin: " + rest
                            );

                    String raw = recognized != null
                            ? recognized
                            : rest.isEmpty() ? null : rest;

                    dates.add(
                            new Delivery(
                                    i,
                                    DesiredDateProcessor.parseValue(
                                            raw
                                    )
                            )
                    );
                } while (label.find());

            } else if (
                    text.strip()
                            .toUpperCase(Locale.ROOT)
                            .startsWith("WOCHE ")
                            || text.strip()
                            .toUpperCase(Locale.ROOT)
                            .startsWith("WOCHE:")
            ) {
                dates.add(
                        new Delivery(
                                i,
                                DesiredDateProcessor.parseValue(
                                        text.strip()
                                )
                        )
                );
            }
        }

        if (dates.isEmpty()) {
            for (int i = 0; i < block.rows.size(); i++) {
                String text =
                        block.rows.get(i).modelText();

                if (EXPECTED.matcher(text).find()) {
                    continue;
                }

                Matcher status = STATUS.matcher(text);

                if (status.find()) {
                    dates.add(
                            new Delivery(
                                    i,
                                    DesiredDateProcessor.parseValue(
                                            status.group()
                                    )
                            )
                    );
                }
            }
        }

        return dates;
    }

    private static String dateKey(DateResult value) {
        if (value.hasDate()) {
            return value.kind()
                    + ":" + value.exactDate()
                    + ":" + value.calendarWeek()
                    + ":" + value.weekBasedYear();
        }

        return value.kind()
                + ":" + clean(value.rawValue())
                .toLowerCase(Locale.ROOT);
    }

    private static Columns columns(PageData page) {
        for (TextLine line : page.lines()) {
            if (!POS_HEADER.matcher(line.text()).find()) {
                continue;
            }

            Span pos = span(line, POS_HEADER);
            Span model = span(line, MODEL_HEADER);
            Span quantity = span(line, QUANTITY_HEADER);

            if (pos != null
                    && model != null
                    && model.left() > pos.right() + 4) {
                float right = quantity != null
                        && quantity.left() > model.right()
                        ? quantity.left() - 3
                        : page.width();

                return new Columns(
                        pos.left(),
                        model.left(),
                        right,
                        line.bottom()
                );
            }
        }

        return null;
    }

    private static Span span(
            TextLine line,
            Pattern pattern
    ) {
        StringBuilder text = new StringBuilder();
        List<TextFragment> positions = new ArrayList<>();
        int previousWord = -1;

        for (TextFragment fragment : line.fragments()) {
            if (previousWord >= 0
                    && previousWord != fragment.wordIndex()) {
                text.append(' ');
                positions.add(null);
            }

            String value = normalize(fragment.text());
            text.append(value);

            for (int i = 0; i < value.length(); i++) {
                positions.add(fragment);
            }

            previousWord = fragment.wordIndex();
        }

        Matcher matcher = pattern.matcher(text);

        if (!matcher.find()) {
            return null;
        }

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

        return Float.isFinite(left)
                ? new Span(left, top, right, bottom)
                : null;
    }

    private static List<String> distinct(
            List<String> values
    ) {
        return values.stream()
                .distinct()
                .toList();
    }

    private static String dateText(String text) {
        return Normalizer.normalize(
                normalize(text),
                Normalizer.Form.NFKC
        );
    }

    private static String clean(String text) {
        return SPACE.matcher(normalize(text)).replaceAll(" ").strip();
    }

    private static String normalize(String text) {
        return text == null
                ? ""
                : Normalizer.normalize(
                        text,
                        Normalizer.Form.NFC
                )
                .replace('\u00A0', ' ')
                .replace('\u202F', ' ')
                .replace('\u2007', ' ')
                .replace("\u00AD", "")
                .replace("\u200B", "")
                .replace("\u2060", "")
                .replace("\uFEFF", "");
    }
}