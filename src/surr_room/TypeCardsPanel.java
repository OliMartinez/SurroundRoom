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
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;
import javax.swing.UIManager;
import javax.swing.border.Border;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;

/**
 * The seven types as cards in a horizontal strip, each with its picture (type1.png to type7.png); the
 * selected card is highlighted and kept in view, with detailed descriptions provided in tooltips.
 */
class TypeCardsPanel extends JPanel {

    /** Radio button for Type 1: Original size with distributed inter-object gaps. */
    final JRadioButton type1;

    /** Radio button for Type 2: Stretched/compressed to eliminate gaps at joints. */
    final JRadioButton type2;

    /** Radio button for Type 3: Enlarged uniform size across all sides without gaps. */
    final JRadioButton type3;

    /** Radio button for Type 4: Reduced uniform size across all sides without gaps. */
    final JRadioButton type4;

    /** Radio button for Type 5: Aligned layout maintaining unbroken continuity across corners. */
    final JRadioButton type5;

    /** Radio button for Type 6: Centered layout progressing symmetrically outward from side midpoints. */
    final JRadioButton type6;

    /** Radio button for Type 7: Discrete spacing interval or explicit object count distribution. */
    final JRadioButton type7;

    /** Ordered list of radio buttons for index-based access (0 to 6 corresponding to Types 1 to 7). */
    final List<JRadioButton> typeRadios = new ArrayList<>();

    /** List of card panels corresponding to each surrounding type. */
    private final List<JPanel> typeCards = new ArrayList<>();

    /** Horizontal scroll pane hosting the card strip. */
    private final JScrollPane cardsScroll;

    /** Constructs the TypeCardsPanel and initializes the card strip. */
    TypeCardsPanel(UiKit.Texts t, Runnable onTypeChanged) {
        super(new FlowLayout(FlowLayout.LEADING, 0, 0));
        type1 = new JRadioButton(t.get("type1", "Type 1"), true);
        type2 = new JRadioButton(t.get("type2", "Type 2"));
        type3 = new JRadioButton(t.get("type3", "Type 3"));
        type4 = new JRadioButton(t.get("type4", "Type 4"));
        type5 = new JRadioButton(t.get("type5", "Type 5"));
        type6 = new JRadioButton(t.get("type6", "Type 6"));
        type7 = new JRadioButton(t.get("type7", "Type 7"));

        ButtonGroup group = new ButtonGroup();
        JRadioButton[] radios = {type1, type2, type3, type4, type5, type6, type7};
        JPanel grid = new JPanel(new GridLayout(1, 0, UiKit.GAP, 0));
        for (int i = 0; i < radios.length; i++) {
            group.add(radios[i]);
            typeRadios.add(radios[i]);
            grid.add(createTypeCard(radios[i], i + 1, t.get("info" + (i + 1), "")));
            radios[i].addActionListener(e -> {
                updateSelection();
                onTypeChanged.run();
            });
        }

        // Place the seven cards in a horizontal scroll pane, sized to show four cards simultaneously
        cardsScroll = new JScrollPane(grid, ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_ALWAYS);
        cardsScroll.setBorder(BorderFactory.createEmptyBorder());
        cardsScroll.getHorizontalScrollBar().setUnitIncrement(UiKit.CARD_WIDTH / 4);
        cardsScroll.setPreferredSize(new Dimension(UiKit.CARD_WIDTH * 4 + UiKit.GAP * 3,
                grid.getPreferredSize().height + cardsScroll.getHorizontalScrollBar().getPreferredSize().height + 2));

        add(cardsScroll);
        updateSelection();
    }

    /**
     * Builds an individual clickable card component for a surrounding type.
     *
     * @param typeIndex   algorithm type index (1-based, 1 to 7)
     */
    private JPanel createTypeCard(JRadioButton radioButton, int typeIndex, String info) {
        JPanel card = new JPanel(new BorderLayout(0, 2));
        card.setPreferredSize(new Dimension(UiKit.CARD_WIDTH, 188));
        card.setToolTipText(info);
        card.putClientProperty("info", info);

        radioButton.setOpaque(false);
        radioButton.setFont(radioButton.getFont().deriveFont(Font.BOLD));
        radioButton.setFocusPainted(false);
        radioButton.setToolTipText(info);

        // Load illustration image from bundle resources
        java.net.URL image = TypeCardsPanel.class.getResource("type" + typeIndex + ".png");
        JLabel imageLabel = image != null ? new JLabel(new ImageIcon(image)) : new JLabel();
        imageLabel.setHorizontalAlignment(SwingConstants.CENTER);
        imageLabel.setToolTipText(info);

        card.add(radioButton, BorderLayout.NORTH);
        card.add(imageLabel, BorderLayout.CENTER);

        // Allow clicking anywhere on the card or image to select the radio button
        MouseAdapter selectOnClick = new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (!radioButton.isSelected()) {
                    radioButton.doClick();
                }
            }
        };
        card.addMouseListener(selectOnClick);
        imageLabel.addMouseListener(selectOnClick);
        card.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        card.putClientProperty("radio", radioButton);
        typeCards.add(card);
        return card;
    }

    /** Synchronizes visual borders and background colors with the active selection. */
    private void updateSelection() {
        for (JPanel card : typeCards) {
            boolean selected = ((JRadioButton) card.getClientProperty("radio")).isSelected();
            if (selected) {
                card.scrollRectToVisible(new java.awt.Rectangle(0, 0, card.getWidth(), card.getHeight()));
            }
            // Accent highlight border for the selected card
            Border outer = selected ? new LineBorder(UiKit.ACCENT, 2, true) : new LineBorder(UiKit.CARD_BORDER, 1, true);
            int pad = selected ? 5 : 6;
            card.setBorder(new CompoundBorder(outer, new EmptyBorder(pad, pad, pad, pad)));
            card.setBackground(selected ? UiKit.ACCENT_BACKGROUND : UIManager.getColor("Panel.background"));
            card.setOpaque(true);
        }
    }

    /**
     * Returns the 1-based index of the currently selected surrounding type.
     *
     * @return integer between 1 and 7
     */
    int getSelectedType() {
        for (int i = 0; i < typeRadios.size(); i++) {
            if (typeRadios.get(i).isSelected()) {
                return i + 1;
            }
        }
        return 1;
    }

    /**
     * Programmatically selects the specified surrounding type.
     *
     * @param type 1-based type index (1 to 7)
     */
    void setSelectedType(int type) {
        int idx = Math.max(0, Math.min(typeRadios.size() - 1, type - 1));
        typeRadios.get(idx).setSelected(true);
        updateSelection();
    }
}
