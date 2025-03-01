package com.msv.pte.ui;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;

public class RoundedTextArea extends JTextArea {

    public RoundedTextArea(int rows, int columns) {
        super(rows, columns);
        setOpaque(false);
        setBorder(new EmptyBorder(5, 5, 5, 5));
        setForeground(Color.WHITE); // Теперь текст белый
    }

    @Override
    public Insets getInsets() {
        return getBorder().getBorderInsets(this);
    }

    @Override
    protected void paintComponent(Graphics g) {
        // Paint the custom rounded background with non-transparent color
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        // Используем полностью непрозрачный фон (альфа=255) для логовой области
        g2.setColor(new Color(60, 63, 65));
        g2.fillRoundRect(0, 0, getWidth(), getHeight(), 0, 0);
        g2.dispose();
        // Then paint the text on top
        super.paintComponent(g);
    }
}
