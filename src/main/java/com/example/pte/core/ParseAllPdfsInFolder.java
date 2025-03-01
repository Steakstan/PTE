package com.example.pte.core;

import com.example.pte.parser.ConfrmationNumberProcessor;
import com.example.pte.parser.OrderNumberProcessor;
import com.example.pte.parser.PositionNumberProcessor;
import com.example.pte.parser.DesiredDateProcessor;
import com.example.pte.parser.ModelEntry;
import com.example.pte.secondapproach.SecondApproachRefinedProcessor;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Orchestrates PDF processing and Excel export.
 */
public class ParseAllPdfsInFolder {

    /** Listener for progress updates. */
    public interface ProgressListener {
        void onProgress(int progress);
    }

    /** Listener for logging messages. */
    public interface LogListener {
        void onLog(String message);
    }

    /**
     * Processes a folder of PDF files and exports extracted data to an Excel file.
     * Updates progress and log messages via provided listeners.
     *
     * @param pdfFolderPath   Path to the folder with PDF files.
     * @param excelOutputPath Output Excel file path.
     * @param useAuftragsinfo Flag to select processing approach.
     * @param progressListener Listener for progress (0-100).
     * @param logListener      Listener for log messages.
     */
    public static void processPdfFolder(String pdfFolderPath, String excelOutputPath, boolean useAuftragsinfo,
                                        ProgressListener progressListener, LogListener logListener) {
        File pdfFolder = new File(pdfFolderPath);
        if (!pdfFolder.isDirectory()) {
            logListener.onLog("[ERROR] Invalid folder: " + pdfFolder.getAbsolutePath());
            return;
        }
        File[] pdfFiles = pdfFolder.listFiles((dir, name) -> name.toLowerCase().endsWith(".pdf"));
        if (pdfFiles == null || pdfFiles.length == 0) {
            logListener.onLog("[WARN] No PDF files found in the folder.");
            return;
        }
        List<DataRow> dataRows = new ArrayList<>();
        PDFParsingService pdfParser = new PDFParsingService();
        int totalFiles = pdfFiles.length;

        for (int i = 0; i < totalFiles; i++) {
            File pdfFile = pdfFiles[i];
            logListener.onLog("[INFO] Processing PDF: " + pdfFile.getName());
            String pdfText = pdfParser.extractText(pdfFile);
            if (pdfText == null) continue;

            if (useAuftragsinfo) {
                List<SecondApproachRefinedProcessor.ResultRow> resultRows = SecondApproachRefinedProcessor.parseText(pdfText);
                for (SecondApproachRefinedProcessor.ResultRow rr : resultRows) {
                    DataRow dr = new DataRow();
                    dr.setOrderNumber(rr.vertragsNummer);
                    dr.setModel(rr.modellBezeichnung);
                    dr.setConfirmationNumber(rr.abNummer);
                    dr.setDesiredDate(rr.datum);
                    dr.setHighlightModel(false);
                    dr.setHighlightDate(false);
                    dataRows.add(dr);
                }
            } else {
                dataRows.addAll(processConfirmationApproach(pdfText));
            }
            int progress = (int) (((i + 1) / (double) totalFiles) * 100);
            progressListener.onProgress(progress);
        }
        ExcelGenerationService.exportToExcel(dataRows, excelOutputPath);
        progressListener.onProgress(100);
        logListener.onLog("[INFO] Processing complete. Output file: " + excelOutputPath);
    }

    private static List<DataRow> processConfirmationApproach(String pdfText) {
        List<DataRow> rows = new ArrayList<>();
        String orderNumber = OrderNumberProcessor.findOrderNumber(pdfText);
        String confirmationNumber = ConfrmationNumberProcessor.findConfirmationNumber(pdfText);
        List<PositionNumberProcessor.PositionData> posDataList = PositionNumberProcessor.extractPositionsModelsAndDates(pdfText);

        for (PositionNumberProcessor.PositionData posData : posDataList) {
            String finalDate = DesiredDateProcessor.transformDate(posData.getDesiredDate());
            for (ModelEntry model : posData.getModels()) {
                DataRow row = new DataRow();
                row.setOrderNumber(orderNumber);
                row.setModel(model.modelName());
                row.setConfirmationNumber(confirmationNumber);
                row.setDesiredDate(finalDate);
                row.setHighlightModel(model.red());
                row.setHighlightDate(DesiredDateProcessor.isDateRed(finalDate));
                rows.add(row);
            }
        }
        return rows;
    }

    /** Represents a generic row of data for Excel export. */
    public static class DataRow {
        private String orderNumber;
        private String model;
        private String confirmationNumber;
        private String desiredDate;
        private boolean highlightModel;
        private boolean highlightDate;
        public String getOrderNumber() { return orderNumber; }
        public void setOrderNumber(String orderNumber) { this.orderNumber = orderNumber; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public String getConfirmationNumber() { return confirmationNumber; }
        public void setConfirmationNumber(String confirmationNumber) { this.confirmationNumber = confirmationNumber; }
        public String getDesiredDate() { return desiredDate; }
        public void setDesiredDate(String desiredDate) { this.desiredDate = desiredDate; }
        public boolean isHighlightModel() { return highlightModel; }
        public void setHighlightModel(boolean highlightModel) { this.highlightModel = highlightModel; }
        public boolean isHighlightDate() { return highlightDate; }
        public void setHighlightDate(boolean highlightDate) { this.highlightDate = highlightDate; }
    }

    /** Service for extracting text from PDF files using PDFBox. */
    public static class PDFParsingService {
        public String extractText(File pdfFile) {
            try (PDDocument document = PDDocument.load(pdfFile)) {
                PDFTextStripper stripper = new PDFTextStripper();
                return stripper.getText(document);
            } catch (IOException e) {
                e.printStackTrace();
                return null;
            }
        }
    }

    /** Service for exporting data rows to an Excel file using Apache POI. */
    public static class ExcelGenerationService {
        public static void exportToExcel(List<DataRow> dataRows, String excelOutputPath) {
            try (Workbook workbook = new XSSFWorkbook()) {
                Sheet sheet = workbook.createSheet("Extracted Data");
                createHeaderRow(sheet, workbook);
                int rowIndex = 1;
                for (DataRow row : dataRows) {
                    Row excelRow = sheet.createRow(rowIndex++);
                    createCell(excelRow, 0, row.getOrderNumber(), workbook, false);
                    createCell(excelRow, 1, row.getModel(), workbook, row.isHighlightModel());
                    createCell(excelRow, 2, row.getConfirmationNumber(), workbook, false);
                    createCell(excelRow, 3, row.getDesiredDate(), workbook, row.isHighlightDate());
                }
                for (int col = 0; col < 4; col++) {
                    sheet.autoSizeColumn(col);
                }
                try (FileOutputStream fos = new FileOutputStream(excelOutputPath)) {
                    workbook.write(fos);
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        private static void createHeaderRow(Sheet sheet, Workbook workbook) {
            Row headerRow = sheet.createRow(0);
            String[] headers = {"Auftragsnummer", "Modell", "Bestätigungsnummer", "Wunschliefertermin"};
            CellStyle headerStyle = createCenteredCellStyle(workbook);
            for (int i = 0; i < headers.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(headerStyle);
            }
        }

        private static void createCell(Row row, int colIndex, String value, Workbook workbook, boolean highlightRed) {
            Cell cell = row.createCell(colIndex);
            cell.setCellValue(value != null ? value : "");
            CellStyle style = createCenteredCellStyle(workbook);
            if (highlightRed) {
                Font font = workbook.createFont();
                font.setColor(IndexedColors.RED.getIndex());
                style.setFont(font);
            }
            cell.setCellStyle(style);
        }

        private static CellStyle createCenteredCellStyle(Workbook workbook) {
            CellStyle style = workbook.createCellStyle();
            style.setAlignment(HorizontalAlignment.CENTER);
            style.setVerticalAlignment(VerticalAlignment.CENTER);
            style.setBorderTop(BorderStyle.THIN);
            style.setBorderBottom(BorderStyle.THIN);
            style.setBorderLeft(BorderStyle.THIN);
            style.setBorderRight(BorderStyle.THIN);
            return style;
        }
    }
}
