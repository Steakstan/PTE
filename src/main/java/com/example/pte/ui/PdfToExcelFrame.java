package com.example.pte.ui;

import com.example.pte.core.PdfToExcelController;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.io.File;

/**
 * Main UI frame for PDF to Excel processing.
 * Delegates business logic to PdfToExcelController.
 */
public class PdfToExcelFrame extends JFrame {

    // Fixed size for labels so that text fields start at the same horizontal position
    private static final Dimension LABEL_SIZE = new Dimension(200, 30);

    private final JTextField pdfDirectoryField = createRoundedTextField();
    private final JTextField excelFileField = createRoundedTextField();
    private final JCheckBox useAuftragsinfoCheckBox = new JCheckBox("Zweite Verarbeitungsart (Auftragsinfo) verwenden");
    private final PdfToExcelController controller = new PdfToExcelController();
    private final RoundedProgressBar progressBar = new RoundedProgressBar(0, 100);
    private final RoundedTextArea logArea = new RoundedTextArea(8, 40);
    private final JButton processButton = createRoundedButton("Verarbeiten");

    public PdfToExcelFrame() {
        super("PDF to Excel Processor");
        initializeFrame();
        initializeComponents();
    }

    private void initializeFrame() {
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(800, 500); // Increased height to accommodate log area and progress bar
        setResizable(false);
        setLayout(new GridBagLayout());
        getContentPane().setBackground(new Color(40, 40, 40));
    }

    private void initializeComponents() {
        GridBagConstraints gbc = createDefaultGridBagConstraints();

        // PDF folder selection
        JLabel pdfLabel = createLabel("PDF-Ordner auswählen:");
        pdfLabel.setPreferredSize(LABEL_SIZE);
        JButton pdfButton = createRoundedButton("Durchsuchen");
        pdfButton.addActionListener(e -> chooseDirectory(pdfDirectoryField));

        // For label and button, weightx = 0 (fixed size)
        gbc.weightx = 0;
        addComponent(pdfLabel, gbc, 0, 0, 1);
        // For text field, weightx = 1 to fill space between label and button
        gbc.weightx = 1;
        addComponent(pdfDirectoryField, gbc, 1, 0, 3);
        gbc.weightx = 0;
        addComponent(pdfButton, gbc, 4, 0, 1);

        // Excel output file selection
        JLabel excelLabel = createLabel("Speicherort für die Excel-Tabelle:");
        excelLabel.setPreferredSize(LABEL_SIZE);
        JButton excelButton = createRoundedButton("Durchsuchen");
        excelButton.addActionListener(e -> chooseFile(excelFileField));
        gbc.gridy = 1;
        gbc.weightx = 0;
        addComponent(excelLabel, gbc, 0, 1, 1);
        gbc.weightx = 1;
        addComponent(excelFileField, gbc, 1, 1, 3);
        gbc.weightx = 0;
        addComponent(excelButton, gbc, 4, 1, 1);

        // Checkbox for processing method
        gbc.gridy = 2;
        gbc.gridx = 0;
        gbc.gridwidth = 5;
        useAuftragsinfoCheckBox.setFont(new Font("Segoe UI", Font.PLAIN, 14));
        useAuftragsinfoCheckBox.setForeground(Color.WHITE);
        useAuftragsinfoCheckBox.setBackground(new Color(40, 40, 40));
        add(useAuftragsinfoCheckBox, gbc);

        // Process button
        processButton.addActionListener(e -> processFiles());
        gbc.gridy = 3;
        gbc.gridx = 0;
        gbc.gridwidth = 5;
        add(processButton, gbc);

        // Progress bar
        progressBar.setStringPainted(true);
        progressBar.setForeground(new Color(60, 200, 60));
        gbc.gridy = 4;
        gbc.gridx = 0;
        gbc.gridwidth = 5;
        add(progressBar, gbc);

        // Log area inside a scroll pane with a custom viewport for rounded background
        logArea.setFont(new Font("Monospaced", Font.PLAIN, 12));
        logArea.setEditable(false);
        JScrollPane scrollPane = new JScrollPane(logArea);
        //scrollPane.setViewport(new RoundedViewport());
        scrollPane.setBorder(new EmptyBorder(0, 0, 0, 0));
        gbc.gridy = 5;
        gbc.gridx = 0;
        gbc.gridwidth = 5;
        gbc.fill = GridBagConstraints.BOTH;
        gbc.weighty = 1; // Allow vertical expansion
        add(scrollPane, gbc);
    }

    private GridBagConstraints createDefaultGridBagConstraints() {
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(10, 10, 10, 10);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1;
        gbc.gridy = 0;
        return gbc;
    }

    private JLabel createLabel(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(Color.WHITE);
        label.setFont(new Font("Segoe UI", Font.PLAIN, 14));
        return label;
    }

    private JTextField createRoundedTextField() {
        JTextField textField = new JTextField();
        // Не устанавливаем фиксированную ширину, чтобы поле адаптировалось горизонтально
        textField.setFont(new Font("Segoe UI", Font.PLAIN, 14));
        textField.setForeground(Color.WHITE);
        textField.setBackground(new Color(60, 63, 65));
        textField.setCaretColor(Color.WHITE);
        textField.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));
        textField.setOpaque(false);
        textField.setUI(new RoundedTextFieldUI());
        return textField;
    }

    private JButton createRoundedButton(String text) {
        JButton button = new JButton(text);
        // Фиксированный размер, вычисляемый через RoundedButtonUI.getPreferredSize()
        button.setPreferredSize(new Dimension(120, 35));
        button.setFocusPainted(false);
        button.setBorder(new EmptyBorder(5, 10, 5, 10));
        button.setBackground(new Color(60, 63, 65));
        button.setForeground(Color.WHITE);
        button.setFont(new Font("Segoe UI", Font.PLAIN, 14));
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.setBorderPainted(false);
        button.setOpaque(true);
        button.setContentAreaFilled(false);
        button.setUI(new RoundedButtonUI());
        return button;
    }

    private void addComponent(Component comp, GridBagConstraints gbc, int gridx, int gridy, int gridwidth) {
        gbc.gridx = gridx;
        gbc.gridy = gridy;
        gbc.gridwidth = gridwidth;
        add(comp, gbc);
    }

    private void chooseDirectory(JTextField targetField) {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            File selectedDirectory = chooser.getSelectedFile();
            targetField.setText(selectedDirectory.getAbsolutePath());
        }
    }

    private void chooseFile(JTextField targetField) {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        FileNameExtensionFilter filter = new FileNameExtensionFilter("Excel-Dateien", "xlsx");
        chooser.setFileFilter(filter);
        if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            File selectedFile = chooser.getSelectedFile();
            if (!selectedFile.getName().endsWith(".xlsx")) {
                selectedFile = new File(selectedFile.getAbsolutePath() + ".xlsx");
            }
            targetField.setText(selectedFile.getAbsolutePath());
        }
    }

    private void processFiles() {
        String pdfFolderPath = pdfDirectoryField.getText();
        String excelOutputPath = excelFileField.getText();

        if (pdfFolderPath.isEmpty() || excelOutputPath.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "Bitte füllen Sie beide Felder aus!",
                    "Fehler",
                    JOptionPane.ERROR_MESSAGE);
            return;
        }

        boolean useAuftragsinfo = useAuftragsinfoCheckBox.isSelected();
        processButton.setEnabled(false);
        progressBar.setValue(0);
        logArea.setText("");

        SwingWorker<Void, Integer> worker = new SwingWorker<>() {
            @Override
            protected Void doInBackground() throws Exception {
                controller.process(pdfFolderPath, excelOutputPath, useAuftragsinfo,
                        progress -> publish(progress),
                        message -> appendLog(message));
                return null;
            }

            @Override
            protected void process(java.util.List<Integer> chunks) {
                int latestProgress = chunks.get(chunks.size() - 1);
                progressBar.setValue(latestProgress);
            }

            @Override
            protected void done() {
                processButton.setEnabled(true);
                progressBar.setValue(100);
            }
        };
        worker.execute();
    }

    private void appendLog(String message) {
        SwingUtilities.invokeLater(() -> {
            logArea.append(message + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }
}
