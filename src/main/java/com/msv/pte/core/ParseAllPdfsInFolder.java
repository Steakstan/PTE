package com.msv.pte.core;

import com.msv.pte.parser.ConfirmationNumberProcessor;
import com.msv.pte.parser.DesiredDateProcessor.DateResult;
import com.msv.pte.parser.ModelCatalog;
import com.msv.pte.parser.ModelEntry;
import com.msv.pte.parser.OrderNumberProcessor;
import com.msv.pte.parser.PdfDocumentReader;
import com.msv.pte.parser.PdfDocumentReader.DocumentData;
import com.msv.pte.parser.PdfMetaProcessor;
import com.msv.pte.parser.PdfMetaProcessor.DocumentMeta;
import com.msv.pte.parser.PdfMetaProcessor.DocumentType;
import com.msv.pte.parser.PositionNumberProcessor;
import com.msv.pte.secondapproach.SecondApproachRefinedProcessor;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;

import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Reads each PDF once, records every file outcome and exports data with an audit. */
public final class ParseAllPdfsInFolder {

    private ParseAllPdfsInFolder() { }

    @FunctionalInterface
    public interface ProgressListener { void onProgress(int progress); }

    @FunctionalInterface
    public interface LogListener { void onLog(String message); }

    public enum FileStatus { OK, REVIEW, ERROR }

    public record FileResult(
            Path source,
            DocumentType documentType,
            FileStatus status,
            int pageCount,
            int firstExcelRow,
            int rowCount,
            int reviewRowCount,
            List<String> problems
    ) {
        public FileResult { problems = List.copyOf(problems); }
    }

    public record BatchResult(
            Path outputFile,
            List<DataRow> rows,
            List<FileResult> files
    ) {
        public BatchResult {
            rows = List.copyOf(rows);
            files = List.copyOf(files);
        }

        public long errorFileCount() {
            return files.stream().filter(file -> file.status() == FileStatus.ERROR).count();
        }

        public long reviewFileCount() {
            return files.stream().filter(file -> file.status() == FileStatus.REVIEW).count();
        }

        public long okFileCount() {
            return files.stream().filter(file -> file.status() == FileStatus.OK).count();
        }

        public boolean requiresReview() {
            return files.stream().anyMatch(file -> file.status() != FileStatus.OK)
                    || rows.stream().anyMatch(DataRow::requiresReview);
        }
    }

    private record ParsedFile(DocumentType type, int pages, List<DataRow> rows,
                              List<String> problems, boolean error) { }

    /** Keeps the controller's existing call. The flag is a fallback for unknown types. */
    public static void processPdfFolder(
            String pdfFolderPath,
            String excelOutputPath,
            boolean useAuftragsinfo,
            ProgressListener progressListener,
            LogListener logListener
    ) throws IOException {
        if (!DataRow.hasText(pdfFolderPath) || !DataRow.hasText(excelOutputPath)) {
            throw new IOException("PDF-Ordner und Excel-Datei müssen angegeben werden.");
        }
        processFolder(Path.of(pdfFolderPath), Path.of(excelOutputPath), useAuftragsinfo,
                progressListener, logListener);
    }

    public static BatchResult processFolder(
            Path pdfFolder,
            Path excelOutput,
            boolean useAuftragsinfo,
            ProgressListener progressListener,
            LogListener logListener
    ) throws IOException {
        Path folder = Objects.requireNonNull(pdfFolder, "pdfFolder").toAbsolutePath().normalize();
        Path output = outputPath(excelOutput);
        ProgressListener progress = progressListener == null ? value -> { } : progressListener;
        LogListener log = logListener == null ? message -> { } : logListener;
        progress.onProgress(0);
        if (!Files.isDirectory(folder)) throw new IOException("PDF-Ordner nicht gefunden: " + folder);
        List<Path> pdfs = collectPdfs(folder);
        if (pdfs.isEmpty()) throw new IOException("Keine PDF-Dateien im Ordner gefunden: " + folder);

        List<DataRow> rows = new ArrayList<>();
        List<FileResult> files = new ArrayList<>();
        ModelCatalog catalog = ModelCatalog.getDefault();
        log.onLog("[INFO] " + pdfs.size() + " PDF-Dateien gefunden.");

        for (int i = 0; i < pdfs.size(); i++) {
            checkInterrupted();
            Path source = pdfs.get(i);
            log.onLog("[INFO] PDF " + (i + 1) + "/" + pdfs.size() + ": " + source);
            ParsedFile parsed = parseFile(source, useAuftragsinfo, catalog);
            List<String> issues = distinct(parsed.problems());
            for (DataRow row : parsed.rows()) row.documentReview = !issues.isEmpty();
            int reviewRows = (int) parsed.rows().stream().filter(DataRow::requiresReview).count();
            FileStatus status = parsed.error() || parsed.rows().isEmpty() ? FileStatus.ERROR
                    : !issues.isEmpty() || reviewRows > 0 ? FileStatus.REVIEW : FileStatus.OK;
            int firstRow = parsed.rows().isEmpty() ? 0 : rows.size() + 2;
            rows.addAll(parsed.rows());
            files.add(new FileResult(source, parsed.type(), status, parsed.pages(),
                    firstRow, parsed.rows().size(), reviewRows, issues));
            String level = status == FileStatus.ERROR ? "ERROR" : status == FileStatus.REVIEW ? "WARN" : "INFO";
            log.onLog("[" + level + "] " + source.getFileName() + ": " + status
                    + ", " + parsed.rows().size() + " Datenzeilen, " + reviewRows + " zu prüfen.");
            for (String issue : issues) log.onLog("[WARN] " + source.getFileName() + ": " + issue);
            progress.onProgress((int) ((i + 1L) * 95 / pdfs.size()));
        }

        checkInterrupted();
        BatchResult result = new BatchResult(output, rows, files);
        log.onLog("[INFO] Excel wird geschrieben: " + output);
        progress.onProgress(97);
        ExcelGenerationService.exportToExcel(result);
        progress.onProgress(100);
        log.onLog("[" + (result.requiresReview() ? "WARN" : "INFO") + "] Excel gespeichert: "
                + result.okFileCount() + " PDF OK, " + result.reviewFileCount() + " zu prüfen, "
                + result.errorFileCount() + " mit Fehler; " + rows.size() + " Datenzeilen. "
                + "Details: Übersicht, Prüfung und Dateiprotokoll.");
        return result;
    }

    private static List<Path> collectPdfs(Path folder) throws IOException {
        List<Path> files = new ArrayList<>();
        Files.walkFileTree(folder, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes) throws IOException {
                checkInterrupted();
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                if (isPdf(file) && (attributes.isRegularFile() || attributes.isSymbolicLink())) files.add(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException error) throws IOException {
                if (isPdf(file)) {
                    files.add(file);
                    return FileVisitResult.CONTINUE;
                }
                throw new IOException("Ordnerstruktur konnte nicht vollständig gelesen werden: " + file, error);
            }
        });
        files.sort(Comparator.comparing(Path::toString));
        return files;
    }

    private static boolean isPdf(Path path) {
        return path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pdf");
    }

    private static ParsedFile parseFile(Path source, boolean fallbackUpdate, ModelCatalog catalog) {
        int pages = 0;
        DocumentType type = DocumentType.UNKNOWN;
        List<String> issues = new ArrayList<>();
        try {
            DocumentData document = PdfDocumentReader.read(source);
            pages = document.pageCount();
            issues.addAll(document.problems());
            if (!document.hasText()) {
                issues.add("Keine auslesbaren Texte vorhanden; PDF oder erforderliche Texterkennung prüfen.");
                return new ParsedFile(type, pages, List.of(), distinct(issues), true);
            }
            DocumentMeta meta = PdfMetaProcessor.extractMeta(document);
            type = meta.documentType();
            issues.addAll(meta.problems());
            DocumentType selected = type;
            if (selected == DocumentType.UNKNOWN) {
                selected = fallbackUpdate ? DocumentType.ORDER_UPDATE : DocumentType.ORDER_CONFIRMATION;
                issues.add("Unbekannter Dokumenttyp; Ersatzparser " + selected + " verwendet. Zuordnung prüfen.");
            }
            List<DataRow> rows = new ArrayList<>();
            if (selected == DocumentType.ORDER_UPDATE) {
                var parsed = SecondApproachRefinedProcessor.processDocument(document, catalog);
                issues.addAll(parsed.problems());
                for (var item : parsed.rows()) {
                    DataRow row = baseRow(source, meta);
                    row.orderNumber = item.vertragsNummer;
                    row.model = item.modellBezeichnung;
                    row.confirmationNumber = item.abNummer;
                    row.desiredDate = item.datum;
                    row.dateSource = "Voraussichtlich";
                    row.rawDate = item.getExpectedDateResult().rawValue();
                    row.rawDesiredDate = item.getDesiredDateResult().rawValue();
                    row.rawModel = item.getModelResult().rawValue();
                    row.dateCandidates = candidateText(item.getDateCandidates());
                    row.positionNumber = item.getPositionNumber();
                    row.ean = item.getEan();
                    row.pageNumber = item.getPageNumber();
                    row.lineNumber = item.getLineNumber();
                    row.highlightOrder = item.isOrderRed();
                    row.highlightConfirmation = item.isConfirmationRed();
                    row.highlightModel = item.isModelRed();
                    row.highlightDate = item.isDateRed();
                    row.problems = item.getProblems();
                    rows.add(row);
                }
            } else {
                var parsed = PositionNumberProcessor.parse(document, catalog);
                var order = OrderNumberProcessor.parseValue(meta.orderNumber());
                var ab = ConfirmationNumberProcessor.findConfirmationNumberResult(document.text());
                issues.addAll(parsed.problems());
                issues.addAll(ab.problems());
                for (var item : parsed.positions()) {
                    DataRow row = baseRow(source, meta);
                    row.orderNumber = order.orderNumber();
                    row.confirmationNumber = ab.confirmationNumber();
                    row.model = item.getModelResult().modelName();
                    row.desiredDate = item.getDateResult().excelValue();
                    row.dateSource = "Wunsch";
                    row.rawDate = item.getDateResult().rawValue();
                    row.rawDesiredDate = row.rawDate;
                    row.rawModel = item.getModelResult().rawValue();
                    row.dateCandidates = candidateText(item.getDateCandidates());
                    row.positionNumber = item.getPositionNumber();
                    row.ean = item.getEan();
                    row.pageNumber = item.getPageNumber();
                    row.lineNumber = item.getLineNumber();
                    row.highlightOrder = order.requiresReview();
                    row.highlightConfirmation = ab.requiresReview();
                    row.highlightModel = item.getModels().stream().anyMatch(ModelEntry::red);
                    row.highlightDate = item.getDateResult().requiresReview()
                            || item.getDateCandidates().stream().map(ParseAllPdfsInFolder::dateKey).distinct().count() > 1;
                    List<String> problems = new ArrayList<>(item.getProblems());
                    problems.addAll(order.problems());
                    problems.addAll(ab.problems());
                    row.problems = distinct(problems);
                    rows.add(row);
                }
            }
            if (rows.isEmpty()) issues.add("Keine Datenzeilen extrahiert; Datei wurde nicht als erfolgreich gewertet.");
            return new ParsedFile(type, pages, rows, distinct(issues), rows.isEmpty());
        } catch (IOException | RuntimeException error) {
            issues.add(error.getClass().getSimpleName() + ": " + Objects.toString(error.getMessage(), "Fehler ohne Meldung"));
            return new ParsedFile(type, pages, List.of(), distinct(issues), true);
        }
    }

    private static DataRow baseRow(Path source, DocumentMeta meta) {
        DataRow row = new DataRow();
        row.sourceFile = source.toString();
        row.documentType = meta.documentType();
        row.documentDate = meta.documentDate();
        row.email = meta.email();
        row.recipient = meta.recipient();
        row.shippingAddress = meta.shippingAddress();
        row.highlightDocumentDate = !DataRow.hasText(meta.documentDate());
        return row;
    }

    private static String dateKey(DateResult date) {
        return date.kind() + ":" + date.exactDate() + ":" + date.weekBasedYear()
                + ":" + date.calendarWeek() + (date.hasDate() ? "" : ":" + date.rawValue());
    }

    private static String candidateText(List<DateResult> values) {
        return String.join("; ", values.stream().map(DateResult::rawValue)
                .filter(DataRow::hasText).distinct().toList());
    }

    private static List<String> distinct(List<String> values) {
        return List.copyOf(new LinkedHashSet<>(values));
    }

    private static Path outputPath(Path output) throws IOException {
        Path path = Objects.requireNonNull(output, "excelOutput").toAbsolutePath().normalize();
        if (!path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
            throw new IOException("Ausgabedatei muss die Endung .xlsx haben: " + path);
        }
        if (Files.isDirectory(path)) throw new IOException("Ausgabepfad ist ein Ordner: " + path);
        return path;
    }

    private static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Verarbeitung abgebrochen.");
    }

    /** Keeps the original export DTO and adds provenance and review details. */
    public static class DataRow {
        private String orderNumber, model, confirmationNumber, desiredDate, documentDate;
        private String email, recipient, shippingAddress, sourceFile, positionNumber, ean;
        private String dateSource, rawDate, rawDesiredDate, rawModel, dateCandidates;
        private DocumentType documentType = DocumentType.UNKNOWN;
        private int pageNumber, lineNumber;
        private boolean highlightOrder, highlightConfirmation, highlightModel, highlightDate;
        private boolean highlightDocumentDate, documentReview;
        private List<String> problems = List.of();

        static boolean hasText(String value) { return value != null && !value.isBlank(); }
        public String getOrderNumber() { return orderNumber; }
        public void setOrderNumber(String value) { orderNumber = value; }
        public String getModel() { return model; }
        public void setModel(String value) { model = value; }
        public String getConfirmationNumber() { return confirmationNumber; }
        public void setConfirmationNumber(String value) { confirmationNumber = value; }
        public String getDesiredDate() { return desiredDate; }
        public void setDesiredDate(String value) { desiredDate = value; }
        public String getDocumentDate() { return documentDate; }
        public void setDocumentDate(String value) { documentDate = value; }
        public String getEmail() { return email; }
        public void setEmail(String value) { email = value; }
        public String getRecipient() { return recipient; }
        public void setRecipient(String value) { recipient = value; }
        public String getShippingAddress() { return shippingAddress; }
        public void setShippingAddress(String value) { shippingAddress = value; }
        public boolean isHighlightModel() { return highlightModel; }
        public void setHighlightModel(boolean value) { highlightModel = value; }
        public boolean isHighlightDate() { return highlightDate; }
        public void setHighlightDate(boolean value) { highlightDate = value; }
        public boolean isHighlightOrder() { return highlightOrder; }
        public boolean isHighlightConfirmation() { return highlightConfirmation; }
        public String getSourceFile() { return sourceFile; }
        public DocumentType getDocumentType() { return documentType; }
        public String getPositionNumber() { return positionNumber; }
        public String getEan() { return ean; }
        public int getPageNumber() { return pageNumber; }
        public int getLineNumber() { return lineNumber; }
        public String getDateSource() { return dateSource; }
        public String getRawDate() { return rawDate; }
        public String getRawDesiredDate() { return rawDesiredDate; }
        public String getRawModel() { return rawModel; }
        public String getDateCandidates() { return dateCandidates; }
        public List<String> getProblems() { return problems; }
        public boolean requiresReview() {
            return documentReview || !problems.isEmpty() || highlightOrder || highlightConfirmation
                    || highlightModel || highlightDate || highlightDocumentDate;
        }
    }

    /** Streaming export with a fixed style set and replacement after successful writing. */
    public static final class ExcelGenerationService {
        private static final int MAX_ROWS = 1_048_576;
        private ExcelGenerationService() { }

        public static void exportToExcel(List<DataRow> rows, String output) throws IOException {
            exportToExcel(new BatchResult(outputPath(Path.of(output)), rows, List.of()));
        }

        public static void exportToExcel(BatchResult result) throws IOException {
            Path output = outputPath(result.outputFile());
            Files.createDirectories(output.getParent());
            Path temporary = Files.createTempFile(output.getParent(), ".pdf-to-excel-", ".xlsx");
            try {
                writeWorkbook(result, temporary);
                checkInterrupted();
                try {
                    Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException unsupported) {
                    Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException | RuntimeException failure) {
                try { Files.deleteIfExists(temporary); }
                catch (IOException cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        }

        private static void writeWorkbook(BatchResult result, Path temporary) throws IOException {
            SXSSFWorkbook workbook = new SXSSFWorkbook(200);
            workbook.setCompressTempFiles(true);
            try (workbook; OutputStream output = Files.newOutputStream(temporary)) {
                Styles styles = new Styles(workbook);
                data(workbook, result.rows(), styles);
                review(workbook, result, styles);
                files(workbook, result.files(), styles);
                summary(workbook, result, styles);
                workbook.setActiveSheet(result.requiresReview() ? 3 : 0);
                workbook.write(output);
            } finally {
                workbook.dispose();
            }
        }

        private static void data(Workbook workbook, List<DataRow> rows, Styles styles) throws IOException {
            String[] headers = {"Auftragsnummer", "Modell", "Bestätigungsnummer", "Liefertermin (KW)",
                    "Erfassdatum", "E-Mail", "Empfänger", "Versandanschrift", "Prüfung", "Quelldatei",
                    "Dokumenttyp", "Position", "Seite", "Textzeile", "EAN", "Terminart", "Termin original",
                    "Wunschtermin original", "Modell original", "Terminkandidaten"};
            int[] widths = {18, 24, 22, 18, 16, 32, 48, 52, 15, 60, 26, 12, 10, 12, 20, 20, 25, 25, 26, 36};
            Sheet sheet = sheet(workbook, "Extracted Data", headers, widths, styles);
            int index = 1;
            for (DataRow value : rows) {
                if (index % 200 == 0) checkInterrupted();
                Row row = write(sheet, index++, styles.normal, value.orderNumber, value.model,
                        value.confirmationNumber, value.desiredDate, value.documentDate, value.email,
                        value.recipient, value.shippingAddress, value.requiresReview() ? "PRÜFEN" : "OK",
                        value.sourceFile, typeLabel(value.documentType), value.positionNumber, positive(value.pageNumber),
                        positive(value.lineNumber), value.ean, value.dateSource, value.rawDate,
                        value.rawDesiredDate, value.rawModel, value.dateCandidates);
                warn(row, 0, value.highlightOrder, styles);
                warn(row, 1, value.highlightModel, styles);
                warn(row, 2, value.highlightConfirmation, styles);
                warn(row, 3, value.highlightDate, styles);
                warn(row, 4, value.highlightDocumentDate, styles);
                warn(row, 8, value.requiresReview(), styles);
            }
            filter(sheet, headers.length, rows.size());
        }

        private static void review(Workbook workbook, BatchResult result, Styles styles) throws IOException {
            String[] headers = {"Ebene", "Quelldatei", "Excel-Zeile", "Seite", "Textzeile", "Position",
                    "Auftrag", "Modell", "AB", "Termin original", "Wunschtermin original",
                    "Modell original", "Terminkandidaten", "Hinweis"};
            Sheet sheet = sheet(workbook, "Prüfung", headers,
                    new int[]{12, 60, 14, 10, 12, 12, 18, 24, 22, 25, 25, 26, 36, 90}, styles);
            int index = 1;
            for (FileResult file : result.files()) {
                for (String problem : file.problems()) {
                    write(sheet, index++, styles.normal, "DATEI", file.source(), positive(file.firstExcelRow()),
                            null, null, null, null, null, null, null, null, null, null, problem);
                }
            }
            for (int i = 0; i < result.rows().size(); i++) {
                DataRow row = result.rows().get(i);
                List<String> problems = row.problems;
                if (problems.isEmpty() && (row.highlightOrder || row.highlightConfirmation
                        || row.highlightModel || row.highlightDate || row.highlightDocumentDate)) {
                    problems = List.of("Markierte Felder prüfen.");
                }
                for (String problem : problems) {
                    write(sheet, index++, styles.normal, "ZEILE", row.sourceFile, i + 2,
                            positive(row.pageNumber), positive(row.lineNumber), row.positionNumber,
                            row.orderNumber, row.model, row.confirmationNumber, row.rawDate,
                            row.rawDesiredDate, row.rawModel, row.dateCandidates, problem);
                }
            }
            filter(sheet, headers.length, index - 1);
        }

        private static void files(Workbook workbook, List<FileResult> files, Styles styles) throws IOException {
            String[] headers = {"Quelldatei", "Dokumenttyp", "Status", "Seiten", "Datenzeilen",
                    "Zeilen zu prüfen", "Erste Excel-Zeile", "Letzte Excel-Zeile", "Dateihinweise"};
            Sheet sheet = sheet(workbook, "Dateiprotokoll", headers,
                    new int[]{65, 26, 16, 12, 16, 20, 22, 22, 35}, styles);
            int index = 1;
            for (FileResult file : files) {
                Row row = write(sheet, index++, styles.normal, file.source(), typeLabel(file.documentType()),
                        statusLabel(file.status()), file.pageCount(), file.rowCount(), file.reviewRowCount(),
                        positive(file.firstExcelRow()), file.rowCount() == 0 ? "" : file.firstExcelRow() + file.rowCount() - 1,
                        file.problems().isEmpty() ? "" : file.problems().size() + " Hinweise; siehe Prüfung");
                warn(row, 2, file.status() != FileStatus.OK, styles);
            }
            filter(sheet, headers.length, files.size());
        }

        private static void summary(Workbook workbook, BatchResult result, Styles styles) throws IOException {
            Sheet sheet = sheet(workbook, "Übersicht", new String[]{"Kennzahl", "Wert"}, new int[]{48, 90}, styles);
            write(sheet, 1, styles.normal, "PDF-Dateien insgesamt", result.files().size());
            write(sheet, 2, styles.normal, "PDF ohne Hinweise", result.okFileCount());
            write(sheet, 3, result.reviewFileCount() > 0 ? styles.warning : styles.normal,
                    "PDF zu prüfen", result.reviewFileCount());
            write(sheet, 4, result.errorFileCount() > 0 ? styles.warning : styles.normal,
                    "PDF mit Fehler / ohne Daten", result.errorFileCount());
            write(sheet, 5, styles.normal, "Datenzeilen", result.rows().size());
            write(sheet, 6, styles.normal, "Datenzeilen zu prüfen", result.rows().stream().filter(DataRow::requiresReview).count());
            write(sheet, 7, styles.normal, "Ausgabedatei", result.outputFile());
            write(sheet, 8, styles.normal, "Prüfung", "Hinweise und betroffene Datenzeilen stehen im Blatt Prüfung.");
            write(sheet, 9, styles.normal, "Dateiprotokoll", "Jede gefundene PDF-Datei hat einen Eintrag, auch bei Lesefehlern.");
            write(sheet, 10, styles.normal, "Liefertermin", "Bestätigung: Wunschliefertermin. Auftragsinformation: voraussichtlicher Liefertermin.");
            write(sheet, 11, styles.normal, "Datumsformat", "WWYY bezeichnet ISO-Kalenderwoche und zugehöriges Wochenjahr.");
        }

        private static Sheet sheet(Workbook workbook, String name, String[] headers,
                                   int[] widths, Styles styles) throws IOException {
            Sheet sheet = workbook.createSheet(name);
            for (int i = 0; i < widths.length; i++) sheet.setColumnWidth(i, widths[i] * 256);
            write(sheet, 0, styles.header, (Object[]) headers);
            sheet.createFreezePane(0, 1);
            return sheet;
        }

        private static Row write(Sheet sheet, int index, CellStyle style, Object... values) throws IOException {
            if (index >= MAX_ROWS) throw new IOException("Excel-Zeilenlimit überschritten: " + sheet.getSheetName());
            Row row = sheet.createRow(index);
            int maxLines = 1;
            for (int i = 0; i < values.length; i++) {
                Object value = values[i];
                Cell cell = row.createCell(i);
                cell.setCellStyle(style);
                if (value instanceof Number number) cell.setCellValue(number.doubleValue());
                else {
                    String text = Objects.toString(value, "");
                    if (text.length() > 32767) throw new IOException("Excel-Zelllimit überschritten: "
                            + sheet.getSheetName() + ", Zeile " + (index + 1) + ", Spalte " + (i + 1));
                    cell.setCellValue(text);
                    int width = Math.max(1, sheet.getColumnWidth(i) / 256 - 2);
                    int lines = 0;
                    for (String part : text.split("\\R", -1)) lines += Math.max(1, (part.length() + width - 1) / width);
                    maxLines = Math.max(maxLines, lines);
                }
            }
            row.setHeightInPoints(Math.min(120, Math.max(28, maxLines * 14)));
            return row;
        }

        private static void filter(Sheet sheet, int columns, int dataRows) {
            sheet.setAutoFilter(new CellRangeAddress(0, dataRows, 0, columns - 1));
        }

        private static Object positive(int value) { return value > 0 ? value : ""; }

        private static String typeLabel(DocumentType type) {
            if (type == null) return "Unbekannt";
            return switch (type) {
                case ORDER_CONFIRMATION -> "Bestätigung";
                case ORDER_UPDATE -> "Auftragsinformation";
                case UNKNOWN -> "Unbekannt";
            };
        }

        private static String statusLabel(FileStatus status) {
            return switch (status) {
                case OK -> "OK";
                case REVIEW -> "PRÜFEN";
                case ERROR -> "FEHLER";
            };
        }

        private static void warn(Row row, int column, boolean warning, Styles styles) {
            if (warning) row.getCell(column).setCellStyle(styles.warning);
        }

        private static final class Styles {
            private final CellStyle normal, warning, header;

            private Styles(Workbook workbook) {
                normal = workbook.createCellStyle();
                normal.setWrapText(true);
                normal.setVerticalAlignment(VerticalAlignment.TOP);
                normal.setBorderTop(BorderStyle.THIN);
                normal.setBorderBottom(BorderStyle.THIN);
                normal.setBorderLeft(BorderStyle.THIN);
                normal.setBorderRight(BorderStyle.THIN);
                Font red = workbook.createFont();
                red.setColor(IndexedColors.RED.getIndex());
                warning = workbook.createCellStyle();
                warning.cloneStyleFrom(normal);
                warning.setFont(red);
                warning.setFillForegroundColor(IndexedColors.LIGHT_YELLOW.getIndex());
                warning.setFillPattern(FillPatternType.SOLID_FOREGROUND);
                Font bold = workbook.createFont();
                bold.setBold(true);
                bold.setColor(IndexedColors.WHITE.getIndex());
                header = workbook.createCellStyle();
                header.cloneStyleFrom(normal);
                header.setFont(bold);
                header.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
                header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            }
        }
    }
}