package com.msv.pte.parser;

import com.msv.pte.parser.ConfirmationNumberProcessor.ConfirmationNumberResult;
import com.msv.pte.parser.DesiredDateProcessor.DateResult;
import com.msv.pte.parser.ModelCatalog.ModelResult;
import com.msv.pte.parser.OrderNumberProcessor.OrderNumberResult;
import com.msv.pte.parser.PdfDocumentReader.DocumentData;
import com.msv.pte.parser.PdfDocumentReader.PageData;
import com.msv.pte.parser.PdfDocumentReader.TextFragment;
import com.msv.pte.parser.PdfDocumentReader.TextLine;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses Auftragsinformation: group identifiers and individual article rows. */
public final class SecondApproachRefinedParser {

    private static final int FLAGS =
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;

    private static final Pattern SPACE =
            Pattern.compile("[\\s\\p{Z}]+");

    private static final Pattern POSITION = Pattern.compile(
            "^\\s*[»›>]*\\s*([0-9]{3,6})(?=\\s|$)\\s*"
    );

    private static final Pattern EAN_TOKEN = Pattern.compile(
            "(?<![0-9])[0-9]{13}(?![0-9])"
    );

    private static final Pattern FOOTER = Pattern.compile(
            "^[»›>\\s]*(?:Änderungen|Aenderungen|Bei\\s+weiteren\\s+Fragen"
                    + "|Mit\\s+freundlichen|Erläuterungen|Legende|Sie\\s+erreichen)\\b",
            FLAGS
    );

    private static final Pattern WISH = Pattern.compile(
            "Wunsch\\s*liefer\\s*-?\\s*(?:termin)?",
            FLAGS
    );

    private static final Pattern EXPECTED = Pattern.compile(
            "voraussicht(?:licher|l\\.?)\\s*(?:liefer\\s*-?\\s*termin)?",
            FLAGS
    );

    private static final Pattern DATE_TOKEN = Pattern.compile(
            "(?<![\\p{L}\\p{N}./-])(?:"
                    + "[0-9]{1,2}\\s*\\.\\s*[0-9]{1,2}\\s*\\.\\s*[0-9]{4}"
                    + "|(?:KW|WOCHE)\\s*:?\\s*[0-9]{1,2}(?:[./-]|\\s+)[0-9]{4}"
                    + "|[0-9]{1,2}\\.[0-9]{4}|[0-9]{4}-W[0-9]{1,2}|[0-9]{6}"
                    + "|\\**(?:ausgelaufen|auslauf|neuanlauf|ersatzlos|unbekannt"
                    + "|nicht\\s+bekannt|nicht\\s+bezogen|bezugsberechtigung|berechtigung)\\**"
                    + ")(?![\\p{L}\\p{N}./-])",
            FLAGS
    );

    private SecondApproachRefinedParser() {
    }

    public record SourceLine(
            int pageNumber,
            int lineNumber,
            String text
    ) {
    }

    public record ParseResult(
            List<SecondApproachResultRow> rows,
            List<String> problems
    ) {
        public ParseResult {
            rows = List.copyOf(rows);
            problems = List.copyOf(problems);
        }

        public boolean requiresReview() {
            return !problems.isEmpty()
                    || rows.stream()
                    .anyMatch(SecondApproachResultRow::requiresReview);
        }
    }

    /** Legacy fields remain available; typed results retain validation details. */
    public static final class SecondApproachResultRow {

        public String auftragsNummer;
        public String modelName;
        public String abNummer;
        public String datum;

        private OrderNumberResult orderResult =
                OrderNumberProcessor.parseValue(null);

        private ConfirmationNumberResult confirmationResult =
                ConfirmationNumberProcessor.parseValue(null);

        private ModelResult modelResult;

        private DateResult desiredDate =
                DesiredDateProcessor.parseValue(null);

        private DateResult expectedDate =
                DesiredDateProcessor.parseValue(null);

        private List<DateResult> dateCandidates = List.of();
        private List<String> problems = List.of();
        private List<SourceLine> sourceLines = List.of();

        private String positionNumber;
        private String ean;
        private int pageNumber;
        private int lineNumber;
        private boolean dateBoundary;

        public OrderNumberResult getOrderResult() {
            return orderResult;
        }

        public ConfirmationNumberResult getConfirmationResult() {
            return confirmationResult;
        }

        public ModelResult getModelResult() {
            return modelResult;
        }

        public DateResult getDesiredDateResult() {
            return desiredDate;
        }

        public DateResult getExpectedDateResult() {
            return expectedDate;
        }

        public List<DateResult> getDateCandidates() {
            return dateCandidates;
        }

        public List<String> getProblems() {
            return problems;
        }

        public List<SourceLine> getSourceLines() {
            return sourceLines;
        }

        public String getPositionNumber() {
            return positionNumber;
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

        public boolean isModelRed() {
            return modelResult == null || modelResult.requiresReview();
        }

        public boolean isDateRed() {
            return dateBoundary || expectedDate.requiresReview();
        }

        public boolean requiresReview() {
            return !problems.isEmpty() || isModelRed() || isDateRed();
        }
    }

    private record Span(
            float left,
            float top,
            float right,
            float bottom
    ) {
    }

    private record Range(float left, float right) {
    }

    private record Cell(String value, boolean cut) {
    }

    private record Columns(
            Range ab,
            Range order,
            Range reference,
            Range position,
            Range model,
            Range ean,
            Range quantity,
            Range desired,
            Range expected,
            float bodyTop
    ) {
    }

    private static final class Group {

        private final OrderNumberResult order;
        private final ConfirmationNumberResult ab;
        private final SourceLine source;
        private final List<String> problems;
        private final Set<String> positions = new HashSet<>();

        private int count;

        private Group(
                String order,
                String ab,
                SourceLine source,
                List<String> problems
        ) {
            this.order = OrderNumberProcessor.parseValue(order);
            this.ab = ConfirmationNumberProcessor.parseValue(ab);
            this.source = source;
            this.problems = List.copyOf(problems);
        }
    }

    private static final class Pending {

        private final Group group;
        private final SourceLine source;
        private final String position;
        private final boolean textMode;
        private final Boolean expectedRight;

        private final List<SourceLine> lines = new ArrayList<>();
        private final List<String> problems = new ArrayList<>();

        private String model = "";
        private String ean = "";
        private String desired = "";
        private String expected = "";

        private boolean modelBoundary;
        private boolean dateBoundary;

        private Pending(
                Group group,
                SourceLine source,
                String position,
                boolean textMode,
                Boolean expectedRight
        ) {
            this.group = group;
            this.source = source;
            this.position = position;
            this.textMode = textMode;
            this.expectedRight = expectedRight;
        }
    }

    private static final class State {

        private final ModelCatalog catalog;
        private final List<SecondApproachResultRow> rows = new ArrayList<>();
        private final List<String> problems = new ArrayList<>();

        private Group group;
        private Pending pending;
        private Boolean expectedRight;
        private boolean textActive = true;

        private State(ModelCatalog catalog) {
            this.catalog = catalog;
        }

        private void group(
                String order,
                String ab,
                SourceLine source,
                List<String> issues
        ) {
            Group next = new Group(order, ab, source, issues);

            if (group != null
                    && (next.order.hasValue() || next.ab.hasValue())
                    && Objects.equals(
                    group.order.orderNumber(),
                    next.order.orderNumber()
            )
                    && Objects.equals(
                    group.ab.confirmationNumber(),
                    next.ab.confirmationNumber()
            )) {
                problems.addAll(issues);
                return;
            }

            flush();
            emptyGroup();
            group = next;
        }

        private void start(
                SourceLine source,
                String position,
                boolean textMode
        ) {
            flush();

            if (group == null) {
                group = new Group(
                        null,
                        null,
                        source,
                        List.of(
                                "Artikelzeile ohne vorausgehenden Auftragsblock."
                        )
                );
            }

            pending = new Pending(
                    group,
                    source,
                    position,
                    textMode,
                    expectedRight
            );

            if (position == null || position.isBlank()) {
                pending.problems.add("Positionsnummer fehlt.");
            } else if (!position.matches("[0-9]{3}")) {
                pending.problems.add("Ungewöhnliche Positionsnummer.");
            }

            if (position != null
                    && !position.isBlank()
                    && !group.positions.add(position)) {
                pending.problems.add(
                        "Position im Auftragsblock mehrfach vorhanden; "
                                + "alle Zeilen erhalten."
                );
            }
        }

        private void flush() {
            if (pending == null) {
                return;
            }

            rows.add(build(pending, catalog));
            pending.group.count++;
            pending = null;
        }

        private void emptyGroup() {
            if (group == null || group.count > 0) {
                return;
            }

            Pending empty = new Pending(
                    group,
                    group.source,
                    null,
                    false,
                    null
            );

            empty.problems.add(
                    "Auftragsblock enthält keine lesbare Artikelzeile."
            );

            rows.add(build(empty, catalog));
            group.count++;
        }

        private ParseResult finish() {
            flush();
            emptyGroup();

            if (rows.isEmpty()) {
                problems.add("Keine Artikelzeilen gefunden; PDF prüfen.");
            }

            return new ParseResult(rows, distinct(problems));
        }
    }

    /** Preferred API: processes an existing snapshot without reopening the PDF. */
    public static ParseResult parseDocument(DocumentData document) {
        return parseDocument(document, ModelCatalog.getDefault());
    }

    public static ParseResult parseDocument(
            DocumentData document,
            ModelCatalog catalog
    ) {
        Objects.requireNonNull(document, "document");

        State state = new State(
                Objects.requireNonNull(catalog, "catalog")
        );

        state.problems.addAll(document.problems());

        if (isConfirmation(document.text())) {
            state.problems.add(
                    "Auftragsbestätigung benötigt den Positionsparser."
            );

            return new ParseResult(
                    List.of(),
                    distinct(state.problems)
            );
        }

        for (PageData page : document.pages()) {
            Columns columns = columns(page);

            if (columns == null) {
                if (page.hasText()) {
                    state.problems.add(
                            "Tabellenspalten auf Seite "
                                    + page.pageNumber()
                                    + " fehlen; nur unsichere Textzuordnung möglich."
                    );
                }

                for (TextLine line : page.lines()) {
                    textLine(
                            state,
                            new SourceLine(
                                    page.pageNumber(),
                                    line.lineNumber(),
                                    line.text()
                            )
                    );
                }
            } else {
                if (columns.expected() == null) {
                    state.problems.add(
                            "Spalte für den voraussichtlichen Liefertermin "
                                    + "auf Seite "
                                    + page.pageNumber()
                                    + " fehlt."
                    );
                }

                for (TextLine line : page.lines()) {
                    if (line.top() <= columns.bodyTop()) {
                        continue;
                    }

                    String text = clean(line.text());

                    if (FOOTER.matcher(text).find()) {
                        break;
                    }

                    if (text.isEmpty() || text.matches("[\\s*]+")) {
                        continue;
                    }

                    layoutLine(
                            state,
                            columns,
                            line,
                            new SourceLine(
                                    page.pageNumber(),
                                    line.lineNumber(),
                                    line.text()
                            )
                    );
                }
            }
        }

        return state.finish();
    }

    /** Existing entry point. Prefer parseDocument when coordinates are available. */
    public static List<SecondApproachResultRow> parse(String pdfText) {
        return parseText(
                pdfText,
                ModelCatalog.getDefault()
        ).rows();
    }

    public static ParseResult parseText(
            String pdfText,
            ModelCatalog catalog
    ) {
        State state = new State(
                Objects.requireNonNull(catalog, "catalog")
        );

        state.problems.add(
                "Textmodus ohne Spaltenkoordinaten; alle Zuordnungen prüfen."
        );

        if (isConfirmation(pdfText)) {
            state.problems.add(
                    "Auftragsbestätigung benötigt den Positionsparser."
            );

            return new ParseResult(List.of(), state.problems);
        }

        String[] pages = normalize(pdfText).split("\\f", -1);

        for (int page = 0; page < pages.length; page++) {
            String[] lines = pages[page].split("\\R", -1);
            state.expectedRight = null;

            for (int line = 0; line < lines.length; line++) {
                textLine(
                        state,
                        new SourceLine(
                                page + 1,
                                line + 1,
                                lines[line]
                        )
                );
            }
        }

        return state.finish();
    }

    private static void layoutLine(
            State state,
            Columns columns,
            TextLine line,
            SourceLine source
    ) {
        Cell ab = cell(line, columns.ab());
        Cell order = cell(line, columns.order());
        Cell reference = cell(line, columns.reference());
        Cell pos = cell(line, columns.position());
        Cell model = cell(line, columns.model());
        Cell ean = cell(line, columns.ean());
        Cell desired = cell(line, columns.desired());
        Cell expected = cell(line, columns.expected());

        String position = pos.value().replaceFirst(
                "^[»›>\\s]+",
                ""
        );

        boolean groupNumber =
                ab.value().matches("[0-9]{7,}")
                        || ab.value().matches("(?:144|145)\\S+")
                        && !ean.value().matches("[0-9]{13}")
                        || (!position.matches("[0-9]{1,6}")
                        && ab.value().matches("[A-Za-z0-9/-]{7,}"));

        boolean referenceMatch = isReference(
                reference.value(),
                order.value()
        );

        boolean missingAb =
                ab.value().isEmpty()
                        && position.isEmpty()
                        && (referenceMatch
                        || order.value().matches("[A-Za-z0-9]{5,}")
                        && !ean.value().matches("[0-9]{13}")
                        && desired.value().isEmpty()
                        && expected.value().isEmpty());

        if (groupNumber || referenceMatch || missingAb) {
            List<String> issues = new ArrayList<>();
            String value = order.value();

            if (value.isEmpty() && !reference.value().isEmpty()) {
                value = OrderNumberProcessor.findOrderNumber(
                        "Bestelltext: " + reference.value()
                );

                if (value != null) {
                    issues.add(
                            "Bestellnummer nur aus Bestelltext ermittelt; prüfen."
                    );
                }
            }

            if (ab.cut() || order.cut()) {
                issues.add(
                        "Auftragsfelder an einer Spaltengrenze getrennt; prüfen."
                );
            }

            state.group(value, ab.value(), source, issues);
            return;
        }

        boolean evidence =
                !ean.value().isEmpty()
                        || !desired.value().isEmpty()
                        || !expected.value().isEmpty()
                        || !cell(line, columns.quantity()).value().isEmpty();

        boolean start =
                !position.isEmpty()
                        && (position.matches("[0-9]{1,6}") || evidence)
                        || !model.value().isEmpty() && evidence
                        || state.pending == null
                        && (evidence || looksModel(model.value(), state.catalog))
                        || state.pending != null
                        && state.pending.ean.matches("[0-9]{13}")
                        && ean.value().matches("[0-9]{13}");

        if (start
                && position.isEmpty()
                && state.pending != null
                && state.pending.ean.isEmpty()
                && state.catalog.isKnown(
                (state.pending.model + model.value()).replace(" ", "")
        )) {
            start = false;
        }

        if (start) {
            state.start(
                    source,
                    emptyToNull(position),
                    false
            );
        }

        if (state.pending == null) {
            return;
        }

        Pending item = state.pending;

        item.lines.add(source);
        item.model = append(item.model, model.value());
        item.ean = append(item.ean, ean.value());
        item.desired = append(item.desired, desired.value());
        item.expected = append(item.expected, expected.value());

        item.modelBoundary |= model.cut();
        item.dateBoundary |= desired.cut() || expected.cut();
    }

    private static void textLine(
            State state,
            SourceLine source
    ) {
        String text = clean(source.text());

        if (text.isEmpty() || text.matches("[\\s*]+")) {
            return;
        }

        Matcher wish = WISH.matcher(text);
        Matcher forecast = EXPECTED.matcher(text);

        if (wish.find() && forecast.find() && headerText(text)) {
            state.expectedRight = forecast.start() > wish.start();
            state.textActive = true;
            return;
        }

        if (text.toLowerCase(Locale.ROOT)
                .contains("auftragsinformation")) {
            state.textActive = false;
            state.expectedRight = null;
            return;
        }

        if (text.matches("(?iu).*\\bPos\\.?.*\\bArtikel\\b.*")) {
            state.textActive = true;
            return;
        }

        if (FOOTER.matcher(text).find()) {
            state.flush();
            state.textActive = false;
            return;
        }

        String[] tokens = text.split("\\s+");

        boolean ab = tokens[0].matches(
                "(?:144|145)[^\\s]{4,}"
        );

        String[] groupFields = text.split("\\s+", 3);

        boolean repeatedOrder =
                groupFields.length == 3
                        && isReference(groupFields[2], groupFields[1]);

        if (state.textActive
                && tokens[0].matches("[A-Za-z0-9]{5,}")
                && isReference(
                text.substring(tokens[0].length()).strip(),
                tokens[0]
        )) {
            state.group(
                    tokens[0],
                    null,
                    source,
                    List.of(
                            "Auftragsfelder nur aus Texttokens ermittelt; prüfen."
                    )
            );
            return;
        }

        if (ab
                || state.textActive
                && repeatedOrder
                && tokens[0].matches("[0-9]{7,}")) {
            state.group(
                    tokens.length > 1 ? tokens[1] : null,
                    tokens[0],
                    source,
                    List.of(
                            "Auftragsfelder nur aus Texttokens ermittelt; prüfen."
                    )
            );

            state.textActive = true;
            return;
        }

        if (!state.textActive) {
            return;
        }

        Matcher position = POSITION.matcher(text);

        if (position.find()) {
            state.start(
                    source,
                    position.group(1),
                    true
            );

            String rest = text.substring(position.end());
            String[] fields = rest.split("\\s+");

            int modelIndex =
                    fields.length > 1
                            && fields[0].matches("[0-9]{1,3}")
                            ? 1
                            : 0;

            state.pending.model =
                    fields.length > modelIndex
                            ? fields[modelIndex]
                            : "";
        }

        if (state.pending != null) {
            state.pending.lines.add(source);
        }
    }

    private static SecondApproachResultRow build(
            Pending item,
            ModelCatalog catalog
    ) {
        SecondApproachResultRow row =
                new SecondApproachResultRow();

        List<String> issues = new ArrayList<>(
                item.group.problems
        );
        issues.addAll(item.problems);

        row.orderResult = item.group.order;
        row.confirmationResult = item.group.ab;
        row.positionNumber = item.position;
        row.pageNumber = item.source.pageNumber();
        row.lineNumber = item.source.lineNumber();

        row.modelResult = model(
                item.model,
                catalog,
                item.modelBoundary
        );

        row.dateBoundary = item.dateBoundary;

        if (item.textMode) {
            issues.add(
                    "Textmodus: Modell- und Terminzellen "
                            + "sind nicht sicher abgegrenzt."
            );

            String all = item.lines.stream()
                    .map(SourceLine::text)
                    .reduce(
                            "",
                            SecondApproachRefinedParser::append
                    );

            Matcher ean = EAN_TOKEN.matcher(all);
            boolean hasEan = ean.find();

            row.ean = hasEan ? ean.group() : null;

            String suffix = hasEan
                    ? all.substring(ean.end())
                    : all;

            row.dateCandidates = tokens(suffix);

            String expected = labeled(suffix, EXPECTED);
            String desired = labeled(suffix, WISH);

            if (expected != null || desired != null) {
                row.expectedDate =
                        DesiredDateProcessor.parseValue(expected);

                row.desiredDate =
                        DesiredDateProcessor.parseValue(desired);
            } else if (hasEan
                    && item.expectedRight != null
                    && row.dateCandidates.size() == 2) {
                row.expectedDate = row.dateCandidates.get(
                        item.expectedRight ? 1 : 0
                );

                row.desiredDate = row.dateCandidates.get(
                        item.expectedRight ? 0 : 1
                );
            } else {
                issues.add(
                        "Gewünschter und voraussichtlicher Termin "
                                + "aus Text nicht eindeutig zuordenbar."
                );
            }
        } else {
            row.ean = emptyToNull(item.ean);

            row.desiredDate = DesiredDateProcessor.parseValue(
                    emptyToNull(item.desired)
            );

            row.expectedDate = DesiredDateProcessor.parseValue(
                    emptyToNull(item.expected)
            );

            row.dateCandidates = List.of(
                    row.desiredDate,
                    row.expectedDate
            );
        }

        issues.addAll(row.orderResult.problems());
        issues.addAll(row.confirmationResult.problems());
        issues.addAll(row.modelResult.problems());

        if (row.ean == null
                || !row.ean.matches("[0-9]{13}")) {
            issues.add(
                    "EAN fehlt oder ist nicht 13-stellig."
            );
        }

        if (row.expectedDate.requiresReview()) {
            issues.add(row.expectedDate.problem());
        }

        if (row.dateBoundary) {
            issues.add(
                    "Terminfeld an einer Spaltengrenze getrennt; prüfen."
            );
        }

        row.auftragsNummer = row.orderResult.orderNumber();
        row.abNummer = row.confirmationResult.confirmationNumber();
        row.modelName = row.modelResult.modelName();
        row.datum = row.expectedDate.rawValue();
        row.problems = distinct(issues);

        List<SourceLine> lines = new ArrayList<>();
        lines.add(item.group.source);

        for (SourceLine line : item.lines) {
            if (!lines.contains(line)) {
                lines.add(line);
            }
        }

        row.sourceLines = List.copyOf(lines);
        return row;
    }

    private static ModelResult model(
            String raw,
            ModelCatalog catalog,
            boolean cut
    ) {
        ModelResult result = catalog.parseValue(raw);

        List<String> problems = new ArrayList<>(
                result.problems()
        );

        if (!result.knownModel()
                && clean(raw).contains(" ")) {
            String repaired = catalog.findCanonicalName(
                    clean(raw).replace(" ", "")
            );

            if (repaired != null) {
                result = new ModelResult(
                        raw,
                        repaired,
                        true,
                        List.of()
                );

                problems.clear();
                problems.add(
                        "Leerzeichen in der Modellbezeichnung "
                                + "rekonstruiert; prüfen."
                );
            }
        }

        if (cut) {
            problems.add(
                    "Modellfeld an einer Spaltengrenze getrennt; "
                            + "vollständigen Code prüfen."
            );
        }

        return new ModelResult(
                raw,
                result.modelName(),
                result.knownModel(),
                distinct(problems)
        );
    }

    private static List<DateResult> tokens(String text) {
        List<DateResult> result = new ArrayList<>();

        Matcher matcher = DATE_TOKEN.matcher(
                Normalizer.normalize(
                        text,
                        Normalizer.Form.NFKC
                )
        );

        while (matcher.find()) {
            result.add(
                    DesiredDateProcessor.parseValue(
                            matcher.group()
                    )
            );
        }

        return List.copyOf(result);
    }

    private static String labeled(
            String text,
            Pattern pattern
    ) {
        Matcher matcher = pattern.matcher(text);

        if (!matcher.find()) {
            return null;
        }

        String rest = text.substring(matcher.end())
                .replaceFirst("^\\s*[:=]?\\s*", "");

        Matcher other = pattern == EXPECTED
                ? WISH.matcher(rest)
                : EXPECTED.matcher(rest);

        if (other.find()) {
            rest = rest.substring(0, other.start());
        }

        return emptyToNull(
                rest.replaceFirst("[;\\s]+$", "")
        );
    }

    private static Columns columns(PageData page) {
        for (TextLine line : page.lines()) {
            Span article = span(
                    line,
                    "\\bArtikel(?:nummer)?\\b"
            );

            Span description = span(
                    line,
                    "\\bBezeichnung\\b"
            );

            Span ean = span(line, "\\bEAN\\b");

            if (article == null
                    || description == null
                    || ean == null) {
                continue;
            }

            Span pos = span(
                    line,
                    "\\bPos\\.?(?!\\p{L})"
            );

            Span set = span(line, "\\bSet\\b");
            Span quantity = span(line, "\\bMenge\\b");

            Span wish = null;
            Span expected = null;
            Span ab = null;
            Span order = null;
            Span reference = null;

            float bottom = line.bottom();
            float unit = Math.max(
                    4,
                    line.bottom() - line.top()
            );

            for (TextLine nearby : page.lines()) {
                if (nearby.bottom() < line.top() - unit * 8
                        || nearby.top() > line.bottom() + unit * 4) {
                    continue;
                }

                Span a = span(
                        nearby,
                        "\\bBelegnummer\\b"
                );

                Span o = span(
                        nearby,
                        "\\bBestellung\\b"
                );

                Span r = span(
                        nearby,
                        "\\bBestelltext\\b"
                );

                if (a != null) {
                    ab = a;
                }

                if (o != null) {
                    order = o;
                }

                if (r != null) {
                    reference = r;
                }

                Span w = span(nearby, WISH.pattern());
                Span x = span(nearby, EXPECTED.pattern());

                if (w != null) {
                    wish = w;
                    bottom = Math.max(bottom, w.bottom());
                }

                if (x != null) {
                    expected = x;
                    bottom = Math.max(bottom, x.bottom());
                }

                if (nearby.top() > line.top()
                        && nearby.top() < line.bottom() + unit * 4
                        && clean(nearby.text()).matches(
                        "(?iu)(?:(?:termin|Liefertermin)\\s*)+"
                )) {
                    bottom = Math.max(
                            bottom,
                            nearby.bottom()
                    );
                }
            }

            if (ab == null
                    || order == null
                    || reference == null
                    || pos == null
                    || article.left() >= description.left()
                    || description.left() >= ean.left()) {
                continue;
            }

            List<Span> groups = List.of(
                    ab,
                    order,
                    reference
            );

            List<Span> items = new ArrayList<>(
                    List.of(
                            pos,
                            article,
                            description,
                            ean
                    )
            );

            if (set != null) {
                items.add(set);
            }

            if (quantity != null) {
                items.add(quantity);
            }

            if (wish != null) {
                items.add(wish);
            }

            if (expected != null) {
                items.add(expected);
            }

            return new Columns(
                    range(ab, groups, page.width()),
                    range(order, groups, page.width()),
                    range(reference, groups, page.width()),
                    range(pos, items, page.width()),
                    range(article, items, page.width()),
                    range(ean, items, page.width()),
                    range(quantity, items, page.width()),
                    range(wish, items, page.width()),
                    range(expected, items, page.width()),
                    bottom
            );
        }

        return null;
    }

    private static Range range(
            Span span,
            List<Span> columns,
            float pageWidth
    ) {
        if (span == null) {
            return null;
        }

        float left = Math.max(0, span.left() - 3);
        float right = pageWidth;

        for (Span other : columns) {
            if (other.left() > span.left() + 4) {
                right = Math.min(
                        right,
                        other.left() - 3
                );
            }
        }

        return right > left
                ? new Range(left, right)
                : null;
    }

    private static Cell cell(
            TextLine line,
            Range range
    ) {
        if (range == null) {
            return new Cell("", false);
        }

        Set<Integer> selected = new HashSet<>();

        for (TextFragment fragment : line.fragments()) {
            if (fragment.centerX() >= range.left()
                    && fragment.centerX() < range.right()
                    && !fragment.text().isBlank()) {
                selected.add(fragment.wordIndex());
            }
        }

        boolean cut = false;

        for (TextFragment fragment : line.fragments()) {
            if (selected.contains(fragment.wordIndex())
                    && !fragment.text().isBlank()
                    && (fragment.centerX() < range.left()
                    || fragment.centerX() >= range.right())) {
                cut = true;
            }
        }

        return new Cell(
                clean(
                        line.textBetween(
                                range.left(),
                                range.right()
                        )
                ),
                cut
        );
    }

    private static Span span(
            TextLine line,
            String expression
    ) {
        StringBuilder text = new StringBuilder();
        List<TextFragment> positions = new ArrayList<>();
        int previous = -1;

        for (TextFragment fragment : line.fragments()) {
            if (previous >= 0
                    && previous != fragment.wordIndex()) {
                text.append(' ');
                positions.add(null);
            }

            String value = normalize(fragment.text());
            text.append(value);

            for (int i = 0; i < value.length(); i++) {
                positions.add(fragment);
            }

            previous = fragment.wordIndex();
        }

        Matcher matcher = Pattern.compile(
                expression,
                FLAGS
        ).matcher(text);

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

    private static boolean looksModel(
            String value,
            ModelCatalog catalog
    ) {
        return catalog.isKnown(value)
                || value.matches(
                "(?=.*[A-Za-z])(?=.*[0-9])[A-Za-z0-9./_-]+"
        );
    }

    private static boolean isReference(
            String reference,
            String order
    ) {
        return !order.isEmpty()
                && (reference.startsWith(order + "/")
                || reference.startsWith(order + " /"));
    }

    private static boolean headerText(String text) {
        return clean(
                EXPECTED.matcher(
                        WISH.matcher(text).replaceAll("")
                ).replaceAll("")
        )
                .replaceAll(
                        "(?iu)\\b(?:Pos|Set|Artikel|Bezeichnung|EAN|Menge"
                                + "|termin|Liefertermin)\\b|[. -]",
                        ""
                )
                .isBlank();
    }

    private static boolean isConfirmation(String text) {
        String value = clean(text)
                .toLowerCase(Locale.ROOT)
                .replace(" ", "");

        return value.contains("auftragseingangsbest")
                && !value.contains("auftragsinformation");
    }

    private static List<String> distinct(List<String> values) {
        return values.stream().distinct().toList();
    }

    private static String emptyToNull(String value) {
        return clean(value).isEmpty() ? null : clean(value);
    }

    private static String append(String first, String second) {
        return clean(first + " " + second);
    }

    private static String clean(String text) {
        return SPACE.matcher(normalize(text)).replaceAll(" ").strip();
    }

    private static String normalize(String text) {
        return text == null
                ? ""
                : Normalizer.normalize(text, Normalizer.Form.NFC)
                .replace('\u00A0', ' ')
                .replace('\u202F', ' ')
                .replace('\u2007', ' ')
                .replace("\u00AD", "")
                .replace("\u200B", "")
                .replace("\u2060", "")
                .replace("\uFEFF", "");
    }
}