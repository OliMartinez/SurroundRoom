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
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.MissingResourceException;
import java.util.ResourceBundle;
import javax.swing.Box;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSeparator;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.border.EmptyBorder;

/**
 * Centralized UI toolkit for the SurroundObj plugin.
 * 
 * Provides unified color schemes, typography standards, localized string accessors,
 * and layout construction utilities shared across configuration panels, preview widgets,
 * and modal dialogs.
 */
final class UiKit {

    /** Primary brand/accent color used for active borders, focus highlights, and icons (light blue). */
    static final Color ACCENT = new Color(47, 111, 222);

    /** Soft tinted background color for selected cards, active mode indicators, and highlight bands. */
    static final Color ACCENT_BACKGROUND = new Color(232, 240, 252);

    /** Neutral gray border color for unselected cards and container frames. */
    static final Color CARD_BORDER = new Color(205, 210, 218);

    /** Subdued secondary text color for explanatory captions and unit hints. */
    static final Color HINT_TEXT = new Color(105, 110, 118);

    /** Default fixed width in pixels for selection cards in TypeCardsPanel. */
    static final int CARD_WIDTH = 168;

    /** Standard pixel spacing between related interactive components. */
    static final int GAP = 8;

    /** Standard indentation step (in pixels) for nested options in GridBagLayout rows. */
    static final int INDENT = 24;

    /**
     * Retrieves a trimmed localized string from a resource bundle by key,
     * safely falling back to a default value if missing or if the bundle is null.
     *
     * @param resource the ResourceBundle to read from, or null
     * @param fallback the default string to return if key is not found or resource is null
     * @return the localized string trimmed of leading/trailing whitespace, or fallback
     */
    static String text(ResourceBundle resource, String key, String fallback) {
        if (resource != null) {
            try {
                return resource.getString(key).trim();
            } catch (MissingResourceException | NullPointerException ex) {
                // Fallback below
            }
        }
        return fallback;
    }

    /**
     * Lightweight wrapper around a ResourceBundle that simplifies fetching trimmed
     * localized strings without repeating bundle null-checks or trim operations.
     */
    static final class Texts {
        private final ResourceBundle resource;

        /** Creates a new Texts helper bound to the specified ResourceBundle. */
        Texts(ResourceBundle resource) {
            this.resource = resource;
        }

        /**
         * Looks up a localized string with a safe fallback.
         *
         * @param fallback value returned if key is missing or bundle is null
         * @return trimmed localized string or fallback
         */
        String get(String key, String fallback) {
            return text(resource, key, fallback);
        }
    }

    /** Private constructor preventing instantiation of utility class. */
    private UiKit() {
    }

    /**
     * Builds a structured section container with a bold header title, an adjoining horizontal
     * separator line, and content nested underneath.
     * Overrides {@link JPanel#getMaximumSize()} to constrain vertical expansion, preventing
     * parent {@link javax.swing.BoxLayout} containers from artificially stretching section headers.
     *
     * @param content the child component or panel placed below the header
     */
    static JPanel section(String title, JComponent content) {
        // Never taller than needed, so BoxLayout doesn't stretch the sections vertically
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

    /**
     * Wraps raw text in HTML markup constrained to a maximum pixel width.
     * Special HTML characters (&amp;, &lt;, &gt;) are escaped to prevent rendering errors.
     *
     * @return HTML string formatted as &lt;html&gt;&lt;div style='width:...px'&gt;...&lt;/div&gt;&lt;/html&gt;
     */
    static String wrap(String text, int width) {
        String escaped = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        return "<html><div style='width:" + width + "px'>" + escaped + "</div></html>";
    }

    /**
     * Creates a subdued informational label styled in 11pt font and hint-gray foreground color.
     *
     * @return styled JLabel for hints or secondary descriptions
     */
    static JLabel hint(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(HINT_TEXT);
        label.setFont(label.getFont().deriveFont(11f));
        return label;
    }

    /**
     * Creates a compact informational icon (&#x24D8;) that displays an explanatory
     * HTML-wrapped tooltip when hovered over by the mouse.
     */
    static JLabel infoIcon(String tooltip) {
        javax.swing.ToolTipManager.sharedInstance().setInitialDelay(150);
        javax.swing.ToolTipManager.sharedInstance().setDismissDelay(12000);
        JLabel icon = new JLabel("\u24d8");
        icon.setForeground(ACCENT);
        icon.setFont(icon.getFont().deriveFont(Font.BOLD, 12f));
        icon.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        if (tooltip != null && !tooltip.isEmpty()) {
            icon.setToolTipText(wrap(tooltip, 280));
        }
        return icon;
    }

    /** Creates a bold-styled label used as a group header or subsection caption. */
    static JLabel subtitle(String text) {
        JLabel label = new JLabel(text);
        label.setFont(label.getFont().deriveFont(Font.BOLD));
        return label;
    }

    /**
     * Creates a numeric spinner whose text editor width is defined by character columns
     * rather than an arbitrary pixel dimension, improving cross-platform DPI scaling.
     */
    static JSpinner spinner(SpinnerNumberModel model, int columns) {
        JSpinner spinner = new JSpinner(model);
        JComponent editor = spinner.getEditor();
        if (editor instanceof JSpinner.DefaultEditor) {
            ((JSpinner.DefaultEditor) editor).getTextField().setColumns(columns);
        }
        return spinner;
    }

    /**
     * Helper to create a percentage spinner configured between 0.25% and 2500% with a step of 0.25%.
     *
     * @param value initial percentage value
     * @return 5-column wide JSpinner
     */
    static JSpinner percentSpinner(double value) {
        return spinner(new SpinnerNumberModel(value, 0.25, 2500, 0.25), 5);
    }

    /**
     * Synchronizes two numerical spinners so that the minimum spinner's value never
     * exceeds the maximum spinner's value, and vice versa.
     */
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

    /**
     * Adds an indented row of components to a GridBagLayout container.
     *
     * @param row        the zero-based gridy row index
     */
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

    /**
     * Places multiple components horizontally in a single row with leading alignment
     * and a specified horizontal gap between elements.
     */
    static JPanel row(int gap, Component... components) {
        JPanel line = new JPanel(new FlowLayout(FlowLayout.LEADING, gap, 0));
        for (Component component : components) {
            line.add(component);
        }
        return line;
    }

    /**
     * Stacks multiple single-row panels vertically using a single-column GridLayout
     * with uniform height per row.
     */
    static JPanel lines(JPanel... lines) {
        JPanel panel = new JPanel(new java.awt.GridLayout(lines.length, 1, 0, 4));
        for (JPanel line : lines) {
            panel.add(line);
        }
        return panel;
    }

    /** Inserts an invisible 6-pixel vertical spacer row into a GridBagLayout panel. */
    static void addSpacerRow(JPanel panel, int row) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = row;
        panel.add(Box.createVerticalStrut(6), c);
    }

    /**
     * Adds an invisible horizontal expansion filler in column 1 of a GridBagLayout panel,
     * ensuring left-aligned components in column 0 stay pinned to the leading edge.
     */
    static void addFiller(JPanel panel) {
        GridBagConstraints filler = new GridBagConstraints();
        filler.gridx = 1;
        filler.gridy = 0;
        filler.weightx = 1;
        panel.add(Box.createHorizontalGlue(), filler);
    }

    /**
     * Resolves the resource URL for the plugin's icon ("surround.png") by checking multiple
     * prospective package and root classpath locations across available ClassLoaders.
     *
     * @param classLoader the plugin ClassLoader, or null to check default classloader
     * @return resolved URL of the icon image, or null if not found
     */
    static java.net.URL pluginIconUrl(ClassLoader classLoader) {
        java.net.URL url = UiKit.class.getResource("surround.png");
        if (url == null) {
            url = UiKit.class.getResource("/surround.png");
        }
        if (url == null) {
            url = UiKit.class.getResource("/surr_room/surround.png");
        }
        if (url == null && classLoader != null) {
            url = classLoader.getResource("surr_room/surround.png");
            if (url == null) {
                url = classLoader.getResource("surround.png");
            }
        }
        return url;
    }

    /**
     * Factory for typed {@link javax.swing.ListCellRenderer} instances, formatting item labels
     * using a supplied string mapping function and optionally copying the label to the item tooltip.
     *
     * @param tooltip true to also set the rendered text as the cell's tooltip
     * @return a ListCellRenderer for JComboBox or JList
     */
    static <T> javax.swing.ListCellRenderer<Object> renderer(Class<T> type, java.util.function.Function<T, String> text,
            boolean tooltip) {
        return new javax.swing.DefaultListCellRenderer() {
            private static final long serialVersionUID = 1L;

            @Override
            public Component getListCellRendererComponent(javax.swing.JList<?> list, Object value, int index,
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

    /**
     * Recomputes the packed bounds of the top-level window ancestor containing the given component.
     * Necessary when child sub-panels dynamically show or hide components (such as conditional options),
     * preventing content clipping or orphaned empty space.
     */
    static void refit(Component component) {
        java.awt.Window window = javax.swing.SwingUtilities.getWindowAncestor(component);
        if (window != null && window.isShowing()) {
            window.pack();
        }
    }

    /**
     * Applies the plugin's "surround.png" icon across standard multi-resolution dimensions
     * (16, 24, 32, 48, 64, 140 px) to a window/dialog, ensuring crisp rendering in OS taskbars,
     * alt-tab switchers, and title bars.
     *
     * @param window the JFrame, JDialog, or Window to assign icons to
     */
    static void applyWindowIcon(java.awt.Window window) {
        if (window == null) {
            return;
        }
        try {
            java.net.URL url = pluginIconUrl(UiKit.class.getClassLoader());
            if (url != null) {
                java.awt.Image base = new javax.swing.ImageIcon(url).getImage();
                if (base != null) {
                    java.util.List<java.awt.Image> icons = new java.util.ArrayList<>();
                    for (int sz : new int[]{16, 24, 32, 48, 64, 140}) {
                        icons.add(base.getScaledInstance(sz, sz, java.awt.Image.SCALE_SMOOTH));
                    }
                    window.setIconImages(icons);
                }
            }
        } catch (RuntimeException ex) {
            // Ignore icon load failure
        }
    }

}
