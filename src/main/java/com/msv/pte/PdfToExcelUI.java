package com.msv.pte;

import com.msv.pte.ui.PdfToExcelFrame;

import javax.swing.UIManager;
import javax.swing.SwingUtilities;

/**
 * Entry point for the PDF to Excel Processor application.
 * Sets the native look and feel and initializes the main UI frame.
 */
public class PdfToExcelUI {

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            setNativeLookAndFeel();
            PdfToExcelFrame mainFrame = new PdfToExcelFrame();
            mainFrame.setVisible(true);
        });
    }


    private static void setNativeLookAndFeel() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }
}
