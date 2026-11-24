/*
 * Copyright (c) 2024-2026 Oliver Mart\u00ednez (Ollie_Jess)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package surr_room;

import java.awt.GridBagLayout;
import javax.swing.ButtonGroup;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;

/**
 * Same size around the outline (types 3 and 4: one size for all objects so each stretch is covered; 3 only
 * enlarges, 4 only reduces; with limits and a fallback: adjust, center or leave empty) or same spaces (types
 * 1 and 2: equal spaces between objects or at the ends, within a maximum difference).
 */
class UniformPanel extends JPanel {

    final JPanel sizeSection, gapsSection;
    final JCheckBox sameSizeCheckBox, sameGapsCheckBox;
    private final JPanel sizeOptions, gapsOptions;
    final JSpinner maxSizeChangeSpinner, maxSizeDifferenceSpinner, maxJointSpinner, maxGapDifferenceSpinner;
    final JRadioButton adjustRadio, centerRadio, emptyRadio;

    UniformPanel(UiKit.Texts t, Runnable onChanged) {
        super(new GridBagLayout());

        sizeSection = new JPanel(new GridBagLayout());
        sameSizeCheckBox = new JCheckBox(t.get("uniformSize", "Same dimensions around the whole outline:"), false);
        UiKit.addOptionRow(sizeSection, 0, 0, sameSizeCheckBox);

        sizeOptions = new JPanel(new GridBagLayout());
        int sr = 0;
        UiKit.addOptionRow(sizeOptions, sr++, 0, UiKit.subtitle(t.get("uniformLimits", "Limits")));
        maxSizeChangeSpinner = UiKit.spinner(new SpinnerNumberModel(20.0, 1.0, 90.0, 1.0), 4);
        UiKit.addOptionRow(sizeOptions, sr++, 1,
                new JLabel(t.get("uniformMaxSizeChange", "Max. size change:")), maxSizeChangeSpinner, new JLabel("%"));
        maxSizeDifferenceSpinner = UiKit.spinner(new SpinnerNumberModel(1.0, 0.0, 20.0, 0.1), 4);
        UiKit.addOptionRow(sizeOptions, sr++, 1,
                new JLabel(t.get("uniformMaxSideDifference", "Max. difference between largest and smallest object:")),
                maxSizeDifferenceSpinner, new JLabel("%"));
        maxJointSpinner = UiKit.spinner(new SpinnerNumberModel(0.2, 0.0, 5.0, 0.05), 4);
        UiKit.addOptionRow(sizeOptions, sr++, 1,
                new JLabel(t.get("uniformMaxJoint", "Max. spacing (joint) between objects:")), maxJointSpinner, new JLabel("cm"));

        UiKit.addOptionRow(sizeOptions, sr++, 0,
                UiKit.subtitle(t.get("uniformFallback", "If a stretch exceeds the limits:")));
        adjustRadio = new JRadioButton(t.get("uniformAdjust", "Adjust size as little as needed (no gaps)"), true);
        centerRadio = new JRadioButton(t.get("uniformCenter", "Keep common size centered"));
        emptyRadio = new JRadioButton(t.get("uniformEmpty", "Leave empty"));
        ButtonGroup group = new ButtonGroup();
        for (JRadioButton r : new JRadioButton[]{adjustRadio, centerRadio, emptyRadio}) {
            group.add(r);
            UiKit.addOptionRow(sizeOptions, sr++, 1, r);
            r.addActionListener(e -> onChanged.run());
        }
        UiKit.addOptionRow(sizeSection, 1, 1, sizeOptions);

        gapsSection = new JPanel(new GridBagLayout());
        sameGapsCheckBox = new JCheckBox(t.get("uniformGaps", "Same spaces around the whole outline:"), false);
        UiKit.addOptionRow(gapsSection, 0, 0, sameGapsCheckBox);
        gapsOptions = new JPanel(new GridBagLayout());
        maxGapDifferenceSpinner = UiKit.spinner(new SpinnerNumberModel(1.0, 0.0, 100.0, 0.1), 4);
        UiKit.addOptionRow(gapsOptions, 0, 1,
                new JLabel(t.get("uniformMaxGapDifference", "Max. space difference:")), maxGapDifferenceSpinner, new JLabel("cm"));
        UiKit.addOptionRow(gapsSection, 1, 1, gapsOptions);

        UiKit.addOptionRow(this, 0, 0, sizeSection);
        UiKit.addOptionRow(this, 1, 0, gapsSection);
        UiKit.addFiller(this);

        for (JCheckBox cb : new JCheckBox[]{sameSizeCheckBox, sameGapsCheckBox}) {
            cb.addActionListener(e -> {
                updateVisibility();
                onChanged.run();
            });
        }
        for (JSpinner sp : new JSpinner[]{maxSizeChangeSpinner, maxSizeDifferenceSpinner, maxJointSpinner, maxGapDifferenceSpinner}) {
            sp.addChangeListener(e -> onChanged.run());
        }
        updateVisibility();
    }

    void showParts(boolean size, boolean gaps) {
        sizeSection.setVisible(size);
        gapsSection.setVisible(gaps);
        revalidate();
        repaint();
    }

    private void updateVisibility() {
        sizeOptions.setVisible(sameSizeCheckBox.isSelected());
        gapsOptions.setVisible(sameGapsCheckBox.isSelected());
        revalidate();
        repaint();
    }

    void fill(SurroundConfig cfg) {
        cfg.uniformSize = sizeSection.isVisible() && sameSizeCheckBox.isSelected();
        cfg.uniformMaxSizeChange = ((Number) maxSizeChangeSpinner.getValue()).doubleValue();
        cfg.uniformMaxSizeDifference = ((Number) maxSizeDifferenceSpinner.getValue()).doubleValue();
        cfg.uniformMaxJoint = ((Number) maxJointSpinner.getValue()).doubleValue();
        cfg.uniformFallback = centerRadio.isSelected() ? SurroundConfig.UNIFORM_CENTER
                : emptyRadio.isSelected() ? SurroundConfig.UNIFORM_EMPTY : SurroundConfig.UNIFORM_ADJUST;
        cfg.uniformGaps = gapsSection.isVisible() && sameGapsCheckBox.isSelected();
        cfg.uniformMaxGapDifference = ((Number) maxGapDifferenceSpinner.getValue()).doubleValue();
    }
}
