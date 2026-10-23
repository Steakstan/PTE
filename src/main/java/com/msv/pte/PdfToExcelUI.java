package com.msv.pte;

import com.formdev.flatlaf.themes.FlatMacDarkLaf;
import com.msv.pte.ui.PdfToExcelFrameFlatLaf;

import javax.swing.UIManager;
import javax.swing.SwingUtilities;

/**
 * Entry point for the PDF to Excel Processor application.
 * Sets the native look and feel and initializes the main UI frame.
 */
public class
PdfToExcelUI {

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {

            FlatMacDarkLaf.setup();

            PdfToExcelFrameFlatLaf mainFrame = new PdfToExcelFrameFlatLaf();

            mainFrame.setLocationRelativeTo(null);

            mainFrame.setVisible(true);
        });
    }


    private static void setNativeLookAndFeel() {
        try {
            UIManager.setLookAndFeel(new FlatMacDarkLaf());
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }
}
