/*
 * Copyright (c) 2024-2026 Oliver Mart\u00ednez (Ollie_Jess)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package surr_room;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Image;
import java.awt.Insets;
import java.awt.Window;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.MissingResourceException;
import java.util.ResourceBundle;
import java.util.function.Function;
import javax.swing.Box;
import javax.swing.DefaultListCellRenderer;
import javax.swing.ImageIcon;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JSeparator;
import javax.swing.JSpinner;
import javax.swing.ListCellRenderer;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.ToolTipManager;
import javax.swing.border.EmptyBorder;

/** Shared color palette, localized text lookups, and Swing layout helpers for the plugin UI. */
final class UiKit {

    static final Color ACCENT = new Color(47, 111, 222);
    static final Color ACCENT_BACKGROUND = new Color(232, 240, 252);
    static final Color CARD_BORDER = new Color(205, 210, 218);
    static final Color HINT_TEXT = new Color(105, 110, 118);
    static final int CARD_WIDTH = 168;
    static final int GAP = 8;
    static final int INDENT = 24;

    static String text(ResourceBundle resource, String key, String fallback) {
        if (resource != null) {
            try {
                return resource.getString(key).trim();
            } catch (MissingResourceException | NullPointerException ignored) {
            }
        }
        return fallback;
    }

    static final class Texts {
        private final ResourceBundle resource;

        Texts(ResourceBundle resource) {
            this.resource = resource;
        }

        String get(String key, String fallback) {
            return text(resource, key, fallback);
        }
    }

    private UiKit() {
    }

    static JPanel section(String title, JComponent content) {
        // Cap max height so parent BoxLayout does not stretch sections vertically
        JPanel section = new JPanel(new BorderLayout(0, 6)) {
            @Override
            public Dimension getMaximumSize() {
                return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
            }
        };
        section.setAlignmentX(Component.LEFT_ALIGNMENT);
        JPanel header = new JPanel(new GridBagLayout());
        JLabel titleLabel = new JLabel(title);
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, titleLabel.getFont().getSize2D() + 1f));
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.insets = new Insets(0, 0, 0, 8);
        header.add(titleLabel, c);
        c.gridx = 1;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(0, 0, 0, 0);
        header.add(new JSeparator(), c);
        content.setBorder(new EmptyBorder(0, 6, 0, 0));
        section.add(header, BorderLayout.NORTH);
        section.add(content, BorderLayout.CENTER);
        return section;
    }

    static String wrap(String text, int width) {
        String escaped = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        return "<html><div style='width:" + width + "px'>" + escaped + "</div></html>";
    }

    static JLabel hint(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(HINT_TEXT);
        label.setFont(label.getFont().deriveFont(11f));
        return label;
    }

    static JLabel infoIcon(String tooltip) {
        ToolTipManager.sharedInstance().setInitialDelay(150);
        ToolTipManager.sharedInstance().setDismissDelay(12000);
        JLabel icon = new JLabel("\u24d8");
        icon.setForeground(ACCENT);
        icon.setFont(icon.getFont().deriveFont(Font.BOLD, 12f));
        icon.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        if (tooltip != null && !tooltip.isEmpty()) {
            icon.setToolTipText(wrap(tooltip, 280));
        }
        return icon;
    }

    static JLabel subtitle(String text) {
        JLabel label = new JLabel(text);
        label.setFont(label.getFont().deriveFont(Font.BOLD));
        return label;
    }

    static JSpinner spinner(SpinnerNumberModel model, int columns) {
        JSpinner spinner = new JSpinner(model);
        if (spinner.getEditor() instanceof JSpinner.DefaultEditor) {
            ((JSpinner.DefaultEditor) spinner.getEditor()).getTextField().setColumns(columns);
        }
        return spinner;
    }

    static JSpinner percentSpinner(double value) {
        return spinner(new SpinnerNumberModel(value, 0.25, 2500, 0.25), 5);
    }

    /** Keeps {@code min <= max} across a paired range of numeric spinners. */
    static void linkRange(JSpinner min, JSpinner max) {
        min.addChangeListener(e -> {
            if (((Number) min.getValue()).doubleValue() > ((Number) max.getValue()).doubleValue()) {
                max.setValue(min.getValue());
            }
        });
        max.addChangeListener(e -> {
            if (((Number) max.getValue()).doubleValue() < ((Number) min.getValue()).doubleValue()) {
                min.setValue(max.getValue());
            }
        });
    }

    static JPanel addOptionRow(JPanel panel, int row, int level, Component... components) {
        JPanel line = row(2, components);
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = row;
        c.anchor = GridBagConstraints.LINE_START;
        c.insets = new Insets(1, level * INDENT, 1, 0);
        panel.add(line, c);
        return line;
    }

    static JPanel row(int gap, Component... components) {
        JPanel line = new JPanel(new FlowLayout(FlowLayout.LEADING, gap, 0));
        for (Component component : components) {
            line.add(component);
        }
        return line;
    }

    static JPanel lines(JPanel... lines) {
        JPanel panel = new JPanel(new GridLayout(lines.length, 1, 0, 4));
        for (JPanel line : lines) {
            panel.add(line);
        }
        return panel;
    }

    static void addSpacerRow(JPanel panel, int row) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = row;
        panel.add(Box.createVerticalStrut(6), c);
    }

    static void addFiller(JPanel panel) {
        GridBagConstraints filler = new GridBagConstraints();
        filler.gridx = 1;
        filler.gridy = 0;
        filler.weightx = 1;
        panel.add(Box.createHorizontalGlue(), filler);
    }

    static URL pluginIconUrl(ClassLoader classLoader) {
        for (String path : new String[]{"surround.png", "/surround.png", "/surr_room/surround.png"}) {
            URL url = UiKit.class.getResource(path);
            if (url != null) {
                return url;
            }
        }
        if (classLoader != null) {
            for (String path : new String[]{"surr_room/surround.png", "surround.png"}) {
                URL url = classLoader.getResource(path);
                if (url != null) {
                    return url;
                }
            }
        }
        return null;
    }

    static <T> ListCellRenderer<Object> renderer(Class<T> type, Function<T, String> text, boolean tooltip) {
        return new DefaultListCellRenderer() {
            private static final long serialVersionUID = 1L;

            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                    boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (type.isInstance(value)) {
                    setText(text.apply(type.cast(value)));
                    if (tooltip) {
                        setToolTipText(getText());
                    }
                }
                return this;
            }
        };
    }

    static void refit(Component component) {
        Window window = SwingUtilities.getWindowAncestor(component);
        if (window != null && window.isShowing()) {
            window.pack();
        }
    }

    static void applyWindowIcon(Window window) {
        if (window == null) {
            return;
        }
        try {
            URL url = pluginIconUrl(UiKit.class.getClassLoader());
            if (url != null) {
                Image base = new ImageIcon(url).getImage();
                if (base != null) {
                    List<Image> icons = new ArrayList<>();
                    for (int sz : new int[]{16, 24, 32, 48, 64, 140}) {
                        icons.add(base.getScaledInstance(sz, sz, Image.SCALE_SMOOTH));
                    }
                    window.setIconImages(icons);
                }
            }
        } catch (RuntimeException ignored) {
        }
    }
}
