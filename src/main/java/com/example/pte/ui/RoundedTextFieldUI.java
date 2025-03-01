package com.example.pte.ui;

import javax.swing.plaf.basic.BasicTextFieldUI;
import java.awt.*;

/**
 * Custom UI for rounded text fields.
 */
public class RoundedTextFieldUI extends BasicTextFieldUI {

    @Override
    protected void paintSafely(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(getComponent().getBackground());
            g2.fillRoundRect(0, 0, getComponent().getWidth(), getComponent().getHeight(), 25, 25);
        } finally {
            g2.dispose();
        }
        super.paintSafely(g);
    }
}
