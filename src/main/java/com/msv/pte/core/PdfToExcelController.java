package com.msv.pte.core;

/**
 * Controller for handling PDF to Excel processing business logic.
 */
public class PdfToExcelController {

    /**
     * Processes the PDF folder and generates an Excel file.
     *
     * @param pdfFolderPath   Path to the folder containing PDF files.
     * @param excelOutputPath Path to the output Excel file.
     * @param useAuftragsinfo Flag indicating which processing method to use.
     * @param progressListener Listener for progress updates.
     * @param logListener      Listener for log messages.
     */
    public void process(String pdfFolderPath, String excelOutputPath, boolean useAuftragsinfo,
                        ParseAllPdfsInFolder.ProgressListener progressListener,
                        ParseAllPdfsInFolder.LogListener logListener) {
        // Delegate processing to business logic
        ParseAllPdfsInFolder.processPdfFolder(pdfFolderPath, excelOutputPath, useAuftragsinfo, progressListener, logListener);
    }
}
