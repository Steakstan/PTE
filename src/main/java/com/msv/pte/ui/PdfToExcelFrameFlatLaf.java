package com.msv.pte.ui;

import com.msv.pte.core.ParseAllPdfsInFolder.BatchResult;
import com.msv.pte.core.PdfToExcelController;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;

/** Create on the EDT after installing the application's FlatLaf look and feel. */
public class PdfToExcelFrameFlatLaf extends JFrame {

    private static final Color SUCCESS = new Color(60, 180, 100);
    private static final Color WARNING = new Color(220, 155, 45);
    private static final Color ERROR = new Color(225, 85, 85);
    private static final Color RUNNING = new Color(80, 150, 230);
    private static final int MAX_LOG_CHARS = 1_000_000;

    private final PdfToExcelController controller;
    private final JTextField pdfDirectoryField = new JTextField();
    private final JTextField excelFileField = new JTextField();
    private final JComboBox<String> fallbackType = new JComboBox<>(
            new String[]{"Bestätigung", "Auftragsinformation"});
    private final JButton pdfBrowseButton = new JButton("Durchsuchen …");
    private final JButton excelBrowseButton = new JButton("Durchsuchen …");
    private final JButton processButton = new JButton("Verarbeiten");
    private final JButton cancelButton = new JButton("Abbrechen");
    private final JButton openResultButton = new JButton("Excel öffnen");
    private final JProgressBar progressBar = new JProgressBar(0, 100);
    private final JLabel statusLabel = new JLabel("Bereit");
    private final JTextArea summaryArea = new JTextArea(4, 1);
    private final JTextArea logArea = new JTextArea();

    private ProcessingWorker activeWorker;
    private Path lastOutput;
    private boolean closeWhenFinished;
    private boolean openingReport;

    public PdfToExcelFrameFlatLaf() {
        this(new PdfToExcelController());
    }

    PdfToExcelFrameFlatLaf(PdfToExcelController controller) {
        super("PDF to Excel BSHG");
        this.controller = Objects.requireNonNull(controller, "controller");
        initializeComponents();
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) { requestClose(); }
        });
        setMinimumSize(new Dimension(920, 620));
        setSize(1080, 760);
        setLocationRelativeTo(null);
        getRootPane().setDefaultButton(processButton);
        updateControls();
    }

    private void initializeComponents() {
        JPanel root = new JPanel(new BorderLayout(0, 16));
        root.setBorder(new EmptyBorder(18, 18, 18, 18));
        setContentPane(root);

        JPanel form = new JPanel(new GridBagLayout());
        addFormRow(form, 0, "PDF-Ordner:", pdfDirectoryField, pdfBrowseButton);
        addFormRow(form, 1, "Excel-Datei:", excelFileField, excelBrowseButton);
        addFormRow(form, 2, "Unbekannter Dokumenttyp:", fallbackType, null);
        GridBagConstraints hint = constraints(1, 3);
        hint.gridwidth = 2;
        form.add(new JLabel("Die Auswahl gilt nur, wenn der Dokumenttyp nicht automatisch erkannt wird."), hint);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        actions.add(processButton);
        actions.add(cancelButton);
        actions.add(openResultButton);
        JPanel top = new JPanel(new BorderLayout(0, 14));
        top.add(form, BorderLayout.CENTER);
        top.add(actions, BorderLayout.SOUTH);
        root.add(top, BorderLayout.NORTH);

        statusLabel.setFont(statusLabel.getFont().deriveFont(Font.BOLD, 16f));
        progressBar.setStringPainted(true);
        progressBar.setString("Bereit");
        summaryArea.setEditable(false);
        summaryArea.setOpaque(false);
        summaryArea.setLineWrap(true);
        summaryArea.setWrapStyleWord(true);
        summaryArea.setFont(UIManager.getFont("Label.font"));
        summaryArea.setText("Nach der Verarbeitung erscheinen hier die Dateizahlen und der Speicherort.");

        JPanel result = new JPanel(new BorderLayout(0, 10));
        result.add(statusLabel, BorderLayout.NORTH);
        result.add(progressBar, BorderLayout.CENTER);
        result.add(summaryArea, BorderLayout.SOUTH);
        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        logArea.setMargin(new Insets(8, 8, 8, 8));
        logArea.setToolTipText("Bei langen Läufen werden ältere Einträge ausgeblendet. Vollständige Hinweise stehen in Excel.");
        JScrollPane logScroll = new JScrollPane(logArea);
        logScroll.setBorder(BorderFactory.createTitledBorder("Protokoll"));
        JPanel center = new JPanel(new BorderLayout(0, 12));
        center.add(result, BorderLayout.NORTH);
        center.add(logScroll, BorderLayout.CENTER);
        root.add(center, BorderLayout.CENTER);

        pdfBrowseButton.addActionListener(event -> chooseDirectory());
        excelBrowseButton.addActionListener(event -> chooseFile());
        processButton.addActionListener(event -> processFiles());
        cancelButton.addActionListener(event -> requestCancellation());
        openResultButton.addActionListener(event -> openResult());
    }

    private static GridBagConstraints constraints(int column, int row) {
        GridBagConstraints value = new GridBagConstraints();
        value.gridx = column;
        value.gridy = row;
        value.insets = new Insets(4, 4, 4, 4);
        value.anchor = GridBagConstraints.WEST;
        value.fill = GridBagConstraints.HORIZONTAL;
        return value;
    }

    private static void addFormRow(JPanel form, int row, String label,
                                   JComponent input, JButton button) {
        form.add(new JLabel(label), constraints(0, row));
        GridBagConstraints field = constraints(1, row);
        field.weightx = 1;
        form.add(input, field);
        if (button != null) form.add(button, constraints(2, row));
    }

    private void chooseDirectory() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("PDF-Ordner auswählen");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            pdfDirectoryField.setText(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    private void chooseFile() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Excel-Datei speichern");
        chooser.setAcceptAllFileFilterUsed(false);
        chooser.setFileFilter(new FileNameExtensionFilter("Excel-Dateien (*.xlsx)", "xlsx"));
        chooser.setSelectedFile(new File(excelFileField.getText().isBlank()
                ? "Ergebnis.xlsx" : excelFileField.getText().strip()));
        if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            try {
                excelFileField.setText(excelPath(chooser.getSelectedFile().getPath()).toString());
            } catch (IOException error) {
                showError(error.getMessage());
            }
        }
    }

    private static Path excelPath(String value) throws IOException {
        if (value.isBlank()) throw new IOException("Bitte eine Excel-Datei angeben.");
        try {
            Path path = Path.of(value.strip()).toAbsolutePath().normalize();
            if (path.getFileName() == null || Files.isDirectory(path)) {
                throw new IOException("Bitte eine Datei statt eines Ordners als Excel-Ziel wählen.");
            }
            String name = path.getFileName().toString();
            return name.toLowerCase(Locale.ROOT).endsWith(".xlsx")
                    ? path : path.resolveSibling(name + ".xlsx");
        } catch (InvalidPathException error) {
            throw new IOException("Ungültiger Excel-Pfad: " + error.getReason(), error);
        }
    }

    private void processFiles() {
        if (activeWorker != null) return;
        String folder = pdfDirectoryField.getText().strip();
        Path output;
        try {
            if (folder.isBlank() || !Files.isDirectory(Path.of(folder))) {
                throw new IOException("Bitte einen vorhandenen PDF-Ordner auswählen.");
            }
            output = excelPath(excelFileField.getText());
        } catch (IOException | InvalidPathException error) {
            showError(error.getMessage());
            return;
        }
        if (Files.exists(output) && !confirm("Die Excel-Datei existiert bereits. Ersetzen?\n" + output,
                "Datei ersetzen")) return;

        pdfDirectoryField.setText(folder);
        excelFileField.setText(output.toString());
        lastOutput = null;
        closeWhenFinished = false;
        logArea.setText("");
        progressBar.setValue(0);
        progressBar.setString("0 %");
        setStatus("Verarbeitung läuft …", RUNNING);
        summaryArea.setText("Die Ergebnisse erscheinen, sobald die Excel-Datei gespeichert ist.");
        activeWorker = new ProcessingWorker(folder, output.toString(), fallbackType.getSelectedIndex() == 1);
        updateControls();
        activeWorker.execute();
    }

    private void updateControls() {
        boolean busy = activeWorker != null;
        pdfDirectoryField.setEnabled(!busy);
        excelFileField.setEnabled(!busy);
        fallbackType.setEnabled(!busy);
        pdfBrowseButton.setEnabled(!busy);
        excelBrowseButton.setEnabled(!busy);
        processButton.setEnabled(!busy);
        cancelButton.setEnabled(busy && !activeWorker.cancelRequested);
        openResultButton.setEnabled(!busy && !openingReport && lastOutput != null);
    }

    private void setStatus(String text, Color color) {
        statusLabel.setText(text);
        statusLabel.setForeground(color);
        progressBar.setForeground(color);
    }

    private void appendLog(String text) {
        logArea.append(text);
        int overflow = logArea.getDocument().getLength() - MAX_LOG_CHARS;
        if (overflow > 0) logArea.replaceRange("", 0, overflow);
        logArea.setCaretPosition(logArea.getDocument().getLength());
    }

    private void requestCancellation() {
        if (activeWorker == null || activeWorker.cancelRequested) return;
        activeWorker.requestCancellation();
        setStatus("Abbruch wird ausgeführt …", WARNING);
        progressBar.setString("Abbruch wird ausgeführt …");
        appendLog("[WARN] Abbruch angefordert. Laufende Verarbeitung wird beendet.\n");
        updateControls();
    }

    private void requestClose() {
        if (activeWorker == null) {
            dispose();
        } else if (!closeWhenFinished && confirm("Verarbeitung abbrechen und das Fenster anschließend schließen?",
                "Verarbeitung läuft")) {
            closeWhenFinished = true;
            requestCancellation();
        }
    }

    private void showResult(BatchResult result) {
        lastOutput = result.outputFile();
        boolean allFailed = !result.files().isEmpty() && result.errorFileCount() == result.files().size();
        Color color = allFailed ? ERROR : result.requiresReview() ? WARNING : SUCCESS;
        String status = allFailed ? "Fehlerprotokoll gespeichert – keine Daten extrahiert"
                : result.requiresReview() ? "Excel gespeichert – Prüfung erforderlich"
                : "Fertig – alle PDF-Dateien verarbeitet";
        setStatus(status, color);
        progressBar.setValue(100);
        progressBar.setString("100 % – Excel gespeichert");
        summaryArea.setText(String.format(Locale.GERMAN,
                "PDF insgesamt: %d  |  OK: %d  |  Zu prüfen: %d  |  Fehler: %d\n"
                        + "Datenzeilen: %d  |  Zeilen zu prüfen: %d\n"
                        + "Details: Übersicht, Prüfung und Dateiprotokoll in Excel.\nGespeichert: %s",
                result.files().size(), result.okFileCount(), result.reviewFileCount(), result.errorFileCount(),
                result.rows().size(), result.rows().stream().filter(row -> row.requiresReview()).count(), lastOutput));
        appendLog("[" + (result.requiresReview() ? "WARN" : "INFO") + "] " + status + "\n");
    }

    private void showCancelled() {
        setStatus("Verarbeitung abgebrochen", WARNING);
        progressBar.setString("Abgebrochen");
        summaryArea.setText("Verarbeitung abgebrochen. Details stehen im Protokoll.");
        appendLog("[WARN] Verarbeitung abgebrochen.\n");
    }

    private void showFailure(Throwable error) {
        String message = Objects.toString(error.getMessage(), error.getClass().getSimpleName());
        setStatus("Verarbeitung fehlgeschlagen", ERROR);
        progressBar.setString("Fehler");
        summaryArea.setText("Verarbeitung fehlgeschlagen.\n" + message);
        appendLog("[ERROR] " + message + "\n");
        if (!closeWhenFinished) showError(message);
    }

    void showError(String message) {
        JOptionPane.showMessageDialog(this, message, "Fehler", JOptionPane.ERROR_MESSAGE);
    }

    boolean confirm(String message, String title) {
        return JOptionPane.showConfirmDialog(this, message, title, JOptionPane.YES_NO_OPTION,
                JOptionPane.QUESTION_MESSAGE) == JOptionPane.YES_OPTION;
    }

    private void openResult() {
        if (lastOutput == null || openingReport || activeWorker != null) return;
        Path output = lastOutput;
        openingReport = true;
        updateControls();
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws IOException {
                if (!Files.isRegularFile(output)) throw new IOException("Excel-Datei nicht gefunden: " + output);
                if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                    throw new IOException("Automatisches Öffnen wird auf diesem System nicht unterstützt.\n" + output);
                }
                Desktop.getDesktop().open(output.toFile());
                return null;
            }

            @Override
            protected void done() {
                try {
                    get();
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException | CancellationException error) {
                    Throwable cause = error.getCause() == null ? error : error.getCause();
                    if (isDisplayable()) showError("Excel-Datei konnte nicht geöffnet werden.\n"
                            + Objects.toString(cause.getMessage(), cause.getClass().getSimpleName()));
                } finally {
                    openingReport = false;
                    updateControls();
                }
            }
        }.execute();
    }

    private record UiUpdate(Integer progress, String message) { }

    private final class ProcessingWorker extends SwingWorker<BatchResult, UiUpdate> {
        private final String folder, output;
        private final boolean fallbackUpdate;
        private volatile boolean cancelRequested;
        private Thread processingThread;
        private boolean finished;

        private ProcessingWorker(String folder, String output, boolean fallbackUpdate) {
            this.folder = folder;
            this.output = output;
            this.fallbackUpdate = fallbackUpdate;
        }

        private synchronized void requestCancellation() {
            cancelRequested = true;
            if (processingThread != null) processingThread.interrupt();
        }

        @Override
        protected BatchResult doInBackground() throws IOException {
            synchronized (this) {
                processingThread = Thread.currentThread();
                if (cancelRequested) processingThread.interrupt();
            }
            try {
                return controller.processWithResult(folder, output, fallbackUpdate,
                        progress -> publish(new UiUpdate(progress, null)),
                        message -> publish(new UiUpdate(null, message)));
            } finally {
                synchronized (this) { processingThread = null; }
            }
        }

        @Override
        protected void process(List<UiUpdate> chunks) {
            if (activeWorker != this || finished) return;
            Integer latestProgress = null;
            StringBuilder messages = new StringBuilder();
            for (UiUpdate update : chunks) {
                if (update.progress() != null) latestProgress = update.progress();
                if (update.message() != null) messages.append(update.message()).append('\n');
            }
            if (latestProgress != null && !cancelRequested) {
                int value = Math.max(0, Math.min(99, latestProgress));
                progressBar.setValue(value);
                progressBar.setString(value + " %");
            }
            if (!messages.isEmpty()) appendLog(messages.toString());
        }

        @Override
        protected void done() {
            if (activeWorker != this) return;
            finished = true;
            try {
                showResult(get());
            } catch (CancellationException error) {
                showCancelled();
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                showFailure(error);
            } catch (ExecutionException error) {
                Throwable cause = error.getCause() == null ? error : error.getCause();
                if (cause instanceof InterruptedIOException || cause instanceof CancellationException) {
                    showCancelled();
                } else {
                    showFailure(cause);
                }
            } finally {
                activeWorker = null;
                updateControls();
                if (closeWhenFinished) dispose();
            }
        }
    }
}