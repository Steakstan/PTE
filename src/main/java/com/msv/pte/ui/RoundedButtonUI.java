package com.msv.pte.ui;

import javax.swing.*;
import javax.swing.plaf.basic.BasicButtonUI;
import java.awt.*;

/**
 * Custom UI for rounded buttons.
 * This class provides modern, smooth rendering for buttons with rounded corners.
 * The preferred size is computed based on the button's font metrics with additional padding.
 */
public class RoundedButtonUI extends BasicButtonUI {

    @Override
    public void paint(Graphics g, JComponent c) {
        JButton button = (JButton) c;
        Graphics2D g2 = (Graphics2D) g.create();

        // Enable antialiasing for smoother edges.
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // Fill the button background with rounded corners.
        g2.setColor(button.getBackground());
        int arcSize = 25;
        g2.fillRoundRect(0, 0, c.getWidth(), c.getHeight(), arcSize, arcSize);

        // Draw a rollover effect when the mouse hovers over the button.
        if (button.getModel().isRollover()) {
            g2.setColor(new Color(90, 93, 95));
            g2.fillRoundRect(0, 0, c.getWidth(), c.getHeight(), arcSize, arcSize);
        }

        g2.dispose();

        // Paint the button's label and focus indicator.
        super.paint(g, c);
    }

    @Override
    public Dimension getPreferredSize(JComponent c) {
        JButton button = (JButton) c;
        Font font = button.getFont();
        FontMetrics fm = button.getFontMetrics(font);
        String text = button.getText();

        int textWidth = (text != null) ? fm.stringWidth(text) : 0;
        int textHeight = fm.getHeight();

        // Calculate preferred dimensions with added padding.
        // extra space added to the text width
        int horizontalPadding = 20;
        int preferredWidth = textWidth + horizontalPadding;
        // extra space added to the text height
        int verticalPadding = 10;
        int preferredHeight = textHeight + verticalPadding;
        return new Dimension(preferredWidth, preferredHeight);
    }
}
