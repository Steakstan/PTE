package com.msv.pte.secondapproach;

import com.msv.pte.parser.ConfirmationNumberProcessor;
import com.msv.pte.parser.ConfirmationNumberProcessor.ConfirmationNumberResult;
import com.msv.pte.parser.DesiredDateProcessor;
import com.msv.pte.parser.DesiredDateProcessor.DateResult;
import com.msv.pte.parser.ModelCatalog;
import com.msv.pte.parser.ModelCatalog.ModelResult;
import com.msv.pte.parser.OrderNumberProcessor;
import com.msv.pte.parser.OrderNumberProcessor.OrderNumberResult;
import com.msv.pte.parser.PdfDocumentReader.DocumentData;
import com.msv.pte.parser.SecondApproachRefinedParser;
import com.msv.pte.parser.SecondApproachRefinedParser.SecondApproachResultRow;
import com.msv.pte.parser.SecondApproachRefinedParser.SourceLine;

import java.util.ArrayList;
import java.util.List;

/** Adapts the update parser's results for Excel and the existing core. */
public final class SecondApproachRefinedProcessor {

    private SecondApproachRefinedProcessor() {
    }

    public record ProcessingResult(
            List<ResultRow> rows,
            List<String> problems
    ) {
        public ProcessingResult {
            rows = List.copyOf(rows);
            problems = List.copyOf(problems);
        }

        public boolean requiresReview() {
            return !problems.isEmpty()
                    || rows.stream().anyMatch(ResultRow::requiresReview);
        }
    }

    /**
     * The four public fields preserve the existing API.
     * For parsed rows they contain export values; getters retain source details.
     */
    public static final class ResultRow {

        public String vertragsNummer;
        public String modellBezeichnung;
        public String abNummer;
        public String datum;

        private final SecondApproachResultRow parsed;
        private final List<String> documentProblems;

        /** Preserves the former public no-argument constructor. */
        public ResultRow() {
            parsed = null;
            documentProblems = List.of();
        }

        private ResultRow(
                SecondApproachResultRow parsed,
                List<String> documentProblems
        ) {
            this.parsed = parsed;
            this.documentProblems = documentProblems;

            vertragsNummer = parsed.getOrderResult().orderNumber();
            modellBezeichnung = parsed.getModelResult().modelName();
            abNummer = parsed.getConfirmationResult().confirmationNumber();
            datum = parsed.getExpectedDateResult().excelValue();
        }

        public OrderNumberResult getOrderResult() {
            return parsed == null
                    ? OrderNumberProcessor.parseValue(vertragsNummer)
                    : parsed.getOrderResult();
        }

        public ConfirmationNumberResult getConfirmationResult() {
            return parsed == null
                    ? ConfirmationNumberProcessor.parseValue(abNummer)
                    : parsed.getConfirmationResult();
        }

        public ModelResult getModelResult() {
            return parsed == null
                    ? ModelCatalog.getDefault().parseValue(modellBezeichnung)
                    : parsed.getModelResult();
        }

        public DateResult getDesiredDateResult() {
            return parsed == null
                    ? DesiredDateProcessor.parseValue(null)
                    : parsed.getDesiredDateResult();
        }

        public DateResult getExpectedDateResult() {
            return parsed == null
                    ? DesiredDateProcessor.parseValue(datum)
                    : parsed.getExpectedDateResult();
        }

        public List<DateResult> getDateCandidates() {
            return parsed == null
                    ? List.of(getExpectedDateResult())
                    : parsed.getDateCandidates();
        }

        public String getPositionNumber() {
            return parsed == null ? null : parsed.getPositionNumber();
        }

        public String getEan() {
            return parsed == null ? null : parsed.getEan();
        }

        /** Zero means that the row was constructed without a PDF source. */
        public int getPageNumber() {
            return parsed == null ? 0 : parsed.getPageNumber();
        }

        public int getLineNumber() {
            return parsed == null ? 0 : parsed.getLineNumber();
        }

        public List<SourceLine> getSourceLines() {
            return parsed == null ? List.of() : parsed.getSourceLines();
        }

        /** Document warnings remain accessible through the legacy list API. */
        public List<String> getDocumentProblems() {
            return documentProblems;
        }

        public List<String> getProblems() {
            if (parsed != null) {
                return parsed.getProblems();
            }

            List<String> problems = new ArrayList<>();
            problems.addAll(getOrderResult().problems());
            problems.addAll(getConfirmationResult().problems());
            problems.addAll(getModelResult().problems());

            DateResult date = getExpectedDateResult();

            if (date.requiresReview()) {
                problems.add(date.problem());
            }

            return problems.stream().distinct().toList();
        }

        public boolean isOrderRed() {
            return getOrderResult().requiresReview();
        }

        public boolean isConfirmationRed() {
            return getConfirmationResult().requiresReview();
        }

        public boolean isModelRed() {
            return parsed == null
                    ? getModelResult().requiresReview()
                    : parsed.isModelRed();
        }

        public boolean isDateRed() {
            return parsed == null
                    ? getExpectedDateResult().requiresReview()
                    : parsed.isDateRed();
        }

        public boolean requiresReview() {
            return !documentProblems.isEmpty()
                    || !getProblems().isEmpty()
                    || isOrderRed()
                    || isConfirmationRed()
                    || isModelRed()
                    || isDateRed();
        }
    }

    /** Preferred entry point: reuses a PDF snapshot, including its coordinates. */
    public static ProcessingResult processDocument(DocumentData document) {
        return processDocument(document, ModelCatalog.getDefault());
    }

    public static ProcessingResult processDocument(
            DocumentData document,
            ModelCatalog catalog
    ) {
        return convert(
                SecondApproachRefinedParser.parseDocument(document, catalog)
        );
    }

    /** Text-only entry point that also returns document-level warnings. */
    public static ProcessingResult processText(String pdfText) {
        return processText(pdfText, ModelCatalog.getDefault());
    }

    public static ProcessingResult processText(
            String pdfText,
            ModelCatalog catalog
    ) {
        return convert(
                SecondApproachRefinedParser.parseText(pdfText, catalog)
        );
    }

    /** Compatibility entry point used by the existing ParseAllPdfsInFolder. */
    public static List<ResultRow> parseText(String pdfText) {
        return processText(pdfText).rows();
    }

    /** Valid dates become WWYY; null, statuses and invalid text are preserved. */
    public static String transformDateToFourDigits(String dateValue) {
        return DesiredDateProcessor.transformDate(dateValue);
    }

    private static ProcessingResult convert(
            SecondApproachRefinedParser.ParseResult parsed
    ) {
        List<String> problems = List.copyOf(parsed.problems());
        List<ResultRow> rows = new ArrayList<>(parsed.rows().size());

        for (SecondApproachResultRow row : parsed.rows()) {
            rows.add(new ResultRow(row, problems));
        }

        return new ProcessingResult(rows, problems);
    }
}