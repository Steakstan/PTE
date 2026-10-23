package com.msv.pte.core;

import com.msv.pte.core.ParseAllPdfsInFolder.BatchResult;
import com.msv.pte.core.ParseAllPdfsInFolder.LogListener;
import com.msv.pte.core.ParseAllPdfsInFolder.ProgressListener;

import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/**
 * Connects the UI to batch processing without keeping mutable request state.
 * Run processing outside the Swing event dispatch thread. Listeners execute
 * on the calling thread; the UI is responsible for updating Swing components.
 */
public class PdfToExcelController {

    /** Keeps the existing UI call compatible. */
    public void process(
            String pdfFolderPath,
            String excelOutputPath,
            boolean useAuftragsinfo,
            ProgressListener progressListener,
            LogListener logListener
    ) throws IOException {
        processWithResult(pdfFolderPath, excelOutputPath, useAuftragsinfo,
                progressListener, logListener);
    }

    /**
     * Returns the saved workbook path, extracted rows and every PDF outcome.
     * The flag only selects a fallback parser when the document type is unknown.
     * Both listeners may be null.
     *
     * Individual PDF failures are recorded in the result and workbook. Folder
     * access, export and cancellation failures propagate as IOException.
     * Returning a result means the workbook was saved; inspect requiresReview()
     * and errorFileCount() before presenting the batch as fully successful.
     */
    public BatchResult processWithResult(
            String pdfFolderPath,
            String excelOutputPath,
            boolean useAuftragsinfo,
            ProgressListener progressListener,
            LogListener logListener
    ) throws IOException {
        Path folder = parsePath(pdfFolderPath, "PDF-Ordner");
        Path output = parsePath(excelOutputPath, "Excel-Datei");

        return ParseAllPdfsInFolder.processFolder(folder, output, useAuftragsinfo,
                progressListener, logListener);
    }

    private static Path parsePath(String value, String label) throws IOException {
        if (value == null || value.isBlank()) {
            throw new IOException(label + " muss angegeben werden.");
        }
        try {
            return Path.of(value.strip());
        } catch (InvalidPathException error) {
            throw new IOException("Ungültiger Pfad für " + label + ": " + error.getReason(), error);
        }
    }
}