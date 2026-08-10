package com.msv.pte.ui;

import com.formdev.flatlaf.FlatClientProperties;import com.formdev.flatlaf.themes.FlatMacDarkLaf;
import com.msv.pte.core.PdfToExcelController;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.io.File;

/**
 * Main UI frame for PDF to Excel processing, rewritten to use FlatLaf.
 * This class uses standard Swing components globally styled by FlatLaf for a modern look.
 * Make sure to call UIManager.setLookAndFeel(new FlatMacDarkLaf()) before creating this frame.
 */
public class PdfToExcelFrameFlatLaf extends JFrame {

    static {

        UIManager.put("TextComponent.arc", 999);
        UIManager.put("Button.arc", 999);
        UIManager.put("ProgressBar.arc", 999);
        UIManager.put("CheckBox.arc", 5);
    }


    private static final Dimension LABEL_SIZE = new Dimension(220, 30);
    private static final Color PROGRESS_COMPLETE_COLOR = new Color(60, 200, 60);

    private final JTextField pdfDirectoryField = createTextField();
    private final JTextField excelFileField = createTextField();
    private final JCheckBox useAuftragsinfoCheckBox = new JCheckBox("Zweite Verarbeitungsart (Auftragsinfo) verwenden");
    private final PdfToExcelController controller = new PdfToExcelController();
    private final JProgressBar progressBar = new JProgressBar(0, 100);
    private final JTextArea logArea = new JTextArea(10, 50);
    private final JButton processButton = createButton("Verarbeiten", null);

    public PdfToExcelFrameFlatLaf() {
        super("PDF to Excel BSHG");
        initializeFrame();
        initializeComponents();
    }

    private void initializeFrame() {
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(850, 550);
        setResizable(false);
        setLayout(new GridBagLayout());

    }

    private void initializeComponents() {
        GridBagConstraints gbc = createDefaultGridBagConstraints();

        gbc.gridy = 0;
        gbc.weightx = 0;
        gbc.gridwidth = 1;
        addComponent(createLabel("PDF-Ordner auswählen:"), gbc);

        gbc.gridx = 1;
        gbc.weightx = 1;
        gbc.gridwidth = 3;
        addComponent(pdfDirectoryField, gbc);

        gbc.gridx = 4;
        gbc.weightx = 0;
        gbc.gridwidth = 1;
        JButton pdfBrowseButton = createButton("Durchsuchen", e -> chooseDirectory(pdfDirectoryField));
        addComponent(pdfBrowseButton, gbc);

        gbc.gridy = 1;
        gbc.gridx = 0;
        gbc.weightx = 0;
        gbc.gridwidth = 1;
        addComponent(createLabel("Speicherort der Excel-Tabelle:"), gbc);

        gbc.gridx = 1;
        gbc.weightx = 1;
        gbc.gridwidth = 3;
        addComponent(excelFileField, gbc);

        gbc.gridx = 4;
        gbc.weightx = 0;
        gbc.gridwidth = 1;
        JButton excelBrowseButton = createButton("Durchsuchen", e -> chooseFile(excelFileField));
        addComponent(excelBrowseButton, gbc);

        gbc.gridy = 2;
        gbc.gridx = 0;
        gbc.gridwidth = 5;
        gbc.weightx = 1;
        gbc.insets = new Insets(20, 20, 20, 20); // More padding
        useAuftragsinfoCheckBox.setFont(new Font("Segoe UI", Font.PLAIN, 14));
        add(useAuftragsinfoCheckBox, gbc);

        gbc.gridy = 3;
        gbc.gridx = 0;
        gbc.gridwidth = 5;
        processButton.addActionListener(e -> processFiles());
        add(processButton, gbc);


        gbc.gridy = 4;
        gbc.gridx = 0;
        gbc.gridwidth = 5;
        gbc.weightx = 1;
        gbc.insets = new Insets(20, 20, 10, 20);
        progressBar.setStringPainted(true);
        progressBar.setForeground(PROGRESS_COMPLETE_COLOR);
        add(progressBar, gbc);

        logArea.setFont(new Font("Monospaced", Font.PLAIN, 12));
        logArea.setEditable(false);
        logArea.setMargin(new Insets(10, 10, 10, 10));;

        JScrollPane scrollPane = new JScrollPane(logArea);
        scrollPane.setBorder(new EmptyBorder(0, 0, 0, 0));
        gbc.gridy = 5;
        gbc.gridx = 0;
        gbc.gridwidth = 5;
        gbc.fill = GridBagConstraints.BOTH;
        gbc.weighty = 1;
        gbc.insets = new Insets(10, 20, 20, 20);
        add(scrollPane, gbc);
    }

    private GridBagConstraints createDefaultGridBagConstraints() {
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(10, 20, 10, 20);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1;
        gbc.gridx = 0;
        gbc.gridy = 0;
        return gbc;
    }

    private JLabel createLabel(String text) {
        JLabel label = new JLabel(text);
        label.setFont(new Font("Segoe UI", Font.PLAIN, 14));
        label.setPreferredSize(LABEL_SIZE);
        // FlatLaf handle colors.
        return label;
    }

    private JTextField createTextField() {
        JTextField textField = new JTextField();
        textField.setFont(new Font("Segoe UI", Font.PLAIN, 14));

        return textField;
    }

    private JButton createButton(String text, java.awt.event.ActionListener listener) {
        JButton button = new JButton(text);
        button.setFocusPainted(false);
        button.setFont(new Font("Segoe UI", Font.BOLD, 14));
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        if (listener != null) {
            button.addActionListener(listener);
        }
        return button;
    }

    private void addComponent(Component comp, GridBagConstraints gbc) {
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