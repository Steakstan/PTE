package com.msv.pte;

import com.formdev.flatlaf.themes.FlatMacDarkLaf;
import com.msv.pte.ui.PdfToExcelFrameFlatLaf;
import javax.swing.SwingUtilities;

public final class PdfToExcelApplication {
    private PdfToExcelApplication() { }
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            FlatMacDarkLaf.setup();
            new PdfToExcelFrameFlatLaf().setVisible(true);
        });
    }
}
