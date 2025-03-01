package com.example.pte.ui;

import javax.swing.*;
import javax.swing.plaf.basic.BasicProgressBarUI;
import javax.swing.border.EmptyBorder;
import java.awt.*;

public class RoundedProgressBar extends JProgressBar {
    private final int arcWidth = 25;
    private final int arcHeight = 25;

    public RoundedProgressBar(int min, int max) {
        super(min, max);
        setOpaque(false);
        setBorder(new EmptyBorder(0, 0, 0, 0));
        setStringPainted(true);
        setUI(new BasicProgressBarUI() {
            @Override
            protected void paintDeterminate(Graphics g, JComponent c) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                Insets b = progressBar.getInsets();
                int width = progressBar.getWidth() - b.left - b.right;
                int height = progressBar.getHeight() - b.top - b.bottom;
                // Draw background (same as button color)
                g2.setColor(new Color(60, 63, 65));
                g2.fillRoundRect(b.left, b.top, width, height, arcWidth, arcHeight);
                // Draw progress fill (green)
                int fillWidth = (int) (width * progressBar.getPercentComplete());
                g2.setColor(new Color(60, 200, 60));
                g2.fillRoundRect(b.left, b.top, fillWidth, height, arcWidth, arcHeight);
                // Draw progress string
                if (progressBar.isStringPainted()) {
                    String progressString = progressBar.getString();
                    FontMetrics fm = g2.getFontMetrics();
                    int stringWidth = fm.stringWidth(progressString);
                    int stringHeight = fm.getAscent();
                    int x = (progressBar.getWidth() - stringWidth) / 2;
                    int y = (progressBar.getHeight() + stringHeight) / 2 - 2;
                    // Determine text color based on fill coverage:
                    // If the fill covers the center of the text, use black; otherwise, bright blue.
                    int textCenter = x + stringWidth / 2;
                    Color textColor = (fillWidth > textCenter) ? Color.BLACK : new Color(0, 191, 255);
                    g2.setColor(textColor);
                    g2.drawString(progressString, x, y);
                }
                g2.dispose();
            }

            @Override
            protected Color getSelectionForeground() {
                return new Color(0, 191, 255);
            }
        });
    }
}
