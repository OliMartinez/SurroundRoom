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
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.net.URL;
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
 * The seven types as cards in a horizontal strip, each with its picture (type1.png to type7.png when
 * surrounding outside, type1_in.png to type7_in.png when surrounding inside); the selected card is highlighted and kept in view.
 */
class TypeCardsPanel extends JPanel {

    final JRadioButton type1, type2, type3, type4, type5, type6, type7;
    final List<JRadioButton> typeRadios = new ArrayList<>();
    private final List<JPanel> typeCards = new ArrayList<>();
    private final List<JLabel> typeImages = new ArrayList<>();
    private boolean inside;

    TypeCardsPanel(UiKit.Texts t, Runnable onTypeChanged) {
        super(new FlowLayout(FlowLayout.LEADING, 0, 0));
        JRadioButton[] radios = new JRadioButton[7];
        ButtonGroup group = new ButtonGroup();
        JPanel grid = new JPanel(new GridLayout(1, 0, UiKit.GAP, 0));
        for (int i = 0; i < 7; i++) {
            int n = i + 1;
            radios[i] = new JRadioButton(t.get("type" + n, "Type " + n), i == 0);
            group.add(radios[i]);
            typeRadios.add(radios[i]);
            grid.add(createTypeCard(radios[i], n, t.get("info" + n, "")));
            radios[i].addActionListener(e -> {
                updateSelection();
                onTypeChanged.run();
            });
        }
        type1 = radios[0]; type2 = radios[1]; type3 = radios[2]; type4 = radios[3];
        type5 = radios[4]; type6 = radios[5]; type7 = radios[6];

        // Sized to show four cards simultaneously
        JScrollPane cardsScroll = new JScrollPane(grid, ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_ALWAYS);
        cardsScroll.setBorder(BorderFactory.createEmptyBorder());
        cardsScroll.getHorizontalScrollBar().setUnitIncrement(UiKit.CARD_WIDTH / 4);
        cardsScroll.setPreferredSize(new Dimension(UiKit.CARD_WIDTH * 4 + UiKit.GAP * 3,
                grid.getPreferredSize().height + cardsScroll.getHorizontalScrollBar().getPreferredSize().height + 2));

        add(cardsScroll);
        updateSelection();
    }

    private JPanel createTypeCard(JRadioButton radioButton, int typeIndex, String info) {
        JPanel card = new JPanel(new BorderLayout(0, 2));
        card.setPreferredSize(new Dimension(UiKit.CARD_WIDTH, 188));
        card.setToolTipText(info);
        card.putClientProperty("info", info);

        radioButton.setOpaque(false);
        radioButton.setFont(radioButton.getFont().deriveFont(Font.BOLD));
        radioButton.setFocusPainted(false);
        radioButton.setToolTipText(info);

        JLabel imageLabel = new JLabel(loadTypeImage(typeIndex, false));
        imageLabel.setHorizontalAlignment(SwingConstants.CENTER);
        imageLabel.setToolTipText(info);
        typeImages.add(imageLabel);

        card.add(radioButton, BorderLayout.NORTH);
        card.add(imageLabel, BorderLayout.CENTER);

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

    private static ImageIcon loadTypeImage(int typeIndex, boolean inside) {
        URL image = inside ? TypeCardsPanel.class.getResource("type" + typeIndex + "_in.png") : null;
        if (image == null) {
            image = TypeCardsPanel.class.getResource("type" + typeIndex + ".png");
        }
        return image != null ? new ImageIcon(image) : null;
    }

    void setInside(boolean inside) {
        if (this.inside != inside) {
            this.inside = inside;
            for (int i = 0; i < typeImages.size(); i++) {
                typeImages.get(i).setIcon(loadTypeImage(i + 1, inside));
            }
        }
    }

    private void updateSelection() {
        for (JPanel card : typeCards) {
            boolean selected = ((JRadioButton) card.getClientProperty("radio")).isSelected();
            if (selected) {
                card.scrollRectToVisible(new Rectangle(0, 0, card.getWidth(), card.getHeight()));
            }
            Border outer = selected ? new LineBorder(UiKit.ACCENT, 2, true) : new LineBorder(UiKit.CARD_BORDER, 1, true);
            int pad = selected ? 5 : 6;
            card.setBorder(new CompoundBorder(outer, new EmptyBorder(pad, pad, pad, pad)));
            card.setBackground(selected ? UiKit.ACCENT_BACKGROUND : UIManager.getColor("Panel.background"));
            card.setOpaque(true);
        }
    }

    int getSelectedType() {
        for (int i = 0; i < typeRadios.size(); i++) {
            if (typeRadios.get(i).isSelected()) {
                return i + 1;
            }
        }
        return 1;
    }
}
