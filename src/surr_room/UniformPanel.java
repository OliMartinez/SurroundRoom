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
 * 1 and 2: equal spaces between objects or at the ends, within a maximum difference). Sub-controls show
 * when their option is checked.
 */
class UniformPanel extends JPanel {

    /** Container section for uniform sizing options (Types 3 and 4). */
    final JPanel sizeSection;

    /** Checkbox toggling uniform dimensions across the whole outline. */
    final JCheckBox sameSizeCheckBox;

    /** Nested sub-panel containing limit spinners and fallback radio buttons. */
    private final JPanel sizeOptions;

    /** Spinner for maximum permissible percentage change in piece width relative to original. */
    final JSpinner maxSizeChangeSpinner;

    /** Spinner for maximum allowed percentage difference between the largest and smallest pieces across stretches. */
    final JSpinner maxSizeDifferenceSpinner;

    /** Spinner for maximum acceptable joint overlap/gap shared between pieces (in cm). */
    final JSpinner maxJointSpinner;

    /** Fallback option: slightly adjust piece width on non-compliant stretches to avoid gaps. */
    final JRadioButton adjustRadio;

    /** Fallback option: keep the uniform piece width centered on non-compliant stretches, leaving end gaps. */
    final JRadioButton centerRadio;

    /** Fallback option: leave non-compliant stretches empty. */
    final JRadioButton emptyRadio;

    /** Container section for uniform gap options (Types 1 and 2). */
    final JPanel gapsSection;

    /** Checkbox toggling uniform spacing around the whole outline. */
    final JCheckBox sameGapsCheckBox;

    /** Nested sub-panel containing gap limit spinners. */
    private final JPanel gapsOptions;

    /** Spinner for maximum difference between the widest and narrowest gap across the outline (in cm). */
    final JSpinner maxGapDifferenceSpinner;

    /**
     * Constructs a UniformPanel with all controls and event listeners.
     *
     * @param onChanged callback executed whenever any uniform setting is toggled or modified
     */
    UniformPanel(UiKit.Texts t, Runnable onChanged) {
        super(new GridBagLayout());

        // ---- Types 3 and 4: Uniform size section ----
        sizeSection = new JPanel(new GridBagLayout());
        int row = 0;
        sameSizeCheckBox = new JCheckBox(t.get("uniformSize", "Same dimensions around the whole outline:"), false);
        UiKit.addOptionRow(sizeSection, row++, 0, sameSizeCheckBox,
                UiKit.infoIcon(t.get("uniformSizeHint",
                        "One size for all the objects; the count of each stretch is computed to cover it without gaps. Type 3 only enlarges; type 4 only reduces.")));

        sizeOptions = new JPanel(new GridBagLayout());
        int sr = 0;
        UiKit.addOptionRow(sizeOptions, sr++, 0, UiKit.subtitle(t.get("uniformLimits", "Limits")));

        // Limit: Max % size change
        maxSizeChangeSpinner = UiKit.spinner(new SpinnerNumberModel(20.0, 1.0, 90.0, 1.0), 4);
        UiKit.addOptionRow(sizeOptions, sr++, 1,
                new JLabel(t.get("uniformMaxSizeChange", "Max. size change:")),
                maxSizeChangeSpinner, new JLabel("%"),
                UiKit.infoIcon(t.get("uniformMaxSizeChangeTip", "How much larger (type 3) or smaller (type 4) than the original the objects may become.")));

        // Limit: Max % difference between stretches
        maxSizeDifferenceSpinner = UiKit.spinner(new SpinnerNumberModel(1.0, 0.0, 20.0, 0.1), 4);
        UiKit.addOptionRow(sizeOptions, sr++, 1,
                new JLabel(t.get("uniformMaxSideDifference", "Max. difference between stretches:")),
                maxSizeDifferenceSpinner, new JLabel("%"),
                UiKit.infoIcon(t.get("uniformMaxSideDifferenceTip", "Largest difference between the biggest and the smallest object; 1% is imperceptible.")));

        // Limit: Max joint between objects
        maxJointSpinner = UiKit.spinner(new SpinnerNumberModel(0.2, 0.0, 5.0, 0.05), 4);
        UiKit.addOptionRow(sizeOptions, sr++, 1,
                new JLabel(t.get("uniformMaxJoint", "Max. joint between objects:")),
                maxJointSpinner, new JLabel("cm"),
                UiKit.infoIcon(t.get("uniformMaxJointTip", "Space shared between objects when changing their size is not enough.")));

        // Fallback options
        UiKit.addOptionRow(sizeOptions, sr++, 0,
                UiKit.subtitle(t.get("uniformFallback", "If a stretch exceeds the limits:")),
                UiKit.infoIcon(t.get("uniformFallbackHint", "When the lengths are not multiples of each other, exactly the same size is not always possible.")));
        adjustRadio = new JRadioButton(t.get("uniformAdjust", "Adjust size as little as needed (no gaps)"), true);
        centerRadio = new JRadioButton(t.get("uniformCenter", "Keep common size centered"));
        emptyRadio = new JRadioButton(t.get("uniformEmpty", "Leave empty"));
        ButtonGroup group = new ButtonGroup();
        group.add(adjustRadio);
        group.add(centerRadio);
        group.add(emptyRadio);
        UiKit.addOptionRow(sizeOptions, sr++, 1, adjustRadio);
        UiKit.addOptionRow(sizeOptions, sr++, 1, centerRadio);
        UiKit.addOptionRow(sizeOptions, sr++, 1, emptyRadio);
        UiKit.addOptionRow(sizeSection, row++, 1, sizeOptions);

        // ---- Types 1 and 2: Uniform gaps section ----
        gapsSection = new JPanel(new GridBagLayout());
        row = 0;
        sameGapsCheckBox = new JCheckBox(t.get("uniformGaps", "Same spaces around the whole outline:"), false);
        UiKit.addOptionRow(gapsSection, row++, 0, sameGapsCheckBox,
                UiKit.infoIcon(t.get("uniformGapsHint",
                        "The objects keep their size; the count of each stretch is computed so that the spaces (between objects in type 1, at the ends in type 2) are equal.")));

        gapsOptions = new JPanel(new GridBagLayout());
        maxGapDifferenceSpinner = UiKit.spinner(new SpinnerNumberModel(1.0, 0.0, 100.0, 0.1), 4);
        UiKit.addOptionRow(gapsOptions, 0, 1,
                new JLabel(t.get("uniformMaxGapDifference", "Max. space difference:")),
                maxGapDifferenceSpinner, new JLabel("cm"),
                UiKit.infoIcon(t.get("uniformMaxGapDifferenceTip", "Largest difference between the widest and the narrowest space of the whole outline.")));
        UiKit.addOptionRow(gapsSection, row++, 1, gapsOptions);

        // Assemble into main panel
        add(sizeSection, constraints(0));
        add(gapsSection, constraints(1));
        UiKit.addFiller(this);

        // Wire listener callbacks
        sameSizeCheckBox.addActionListener(e -> {
            updateVisibility();
            onChanged.run();
        });
        sameGapsCheckBox.addActionListener(e -> {
            updateVisibility();
            onChanged.run();
        });
        for (JRadioButton r : new JRadioButton[]{adjustRadio, centerRadio, emptyRadio}) {
            r.addActionListener(e -> onChanged.run());
        }
        for (JSpinner sp : new JSpinner[]{maxSizeChangeSpinner, maxSizeDifferenceSpinner, maxJointSpinner, maxGapDifferenceSpinner}) {
            sp.addChangeListener(e -> onChanged.run());
        }

        updateVisibility();
    }

    /** Builds GridBagConstraints anchored to the start of the row. */
    private static java.awt.GridBagConstraints constraints(int row) {
        java.awt.GridBagConstraints c = new java.awt.GridBagConstraints();
        c.gridx = 0;
        c.gridy = row;
        c.anchor = java.awt.GridBagConstraints.LINE_START;
        return c;
    }

    /**
     * Controls visibility of the size and gap sections based on the currently active surrounding type.
     *
     * @param size whether to show the uniform size section (Types 3 &amp; 4)
     * @param gaps whether to show the uniform gaps section (Types 1 &amp; 2)
     */
    void showParts(boolean size, boolean gaps) {
        sizeSection.setVisible(size);
        gapsSection.setVisible(gaps);
        revalidate();
        repaint();
    }

    /** Updates visibility of nested sub-options based on whether their master checkboxes are checked. */
    private void updateVisibility() {
        sizeOptions.setVisible(sameSizeCheckBox.isSelected());
        gapsOptions.setVisible(sameGapsCheckBox.isSelected());
        revalidate();
        repaint();
    }

    /** Populates the given {@link SurroundConfig} with values from the UI controls. */
    void fill(SurroundConfig cfg) {
        cfg.uniformSize = sizeSection.isVisible() && sameSizeCheckBox.isSelected();
        cfg.uniformMaxSizeChange = ((Number) maxSizeChangeSpinner.getValue()).doubleValue();
        cfg.uniformMaxSizeDifference = ((Number) maxSizeDifferenceSpinner.getValue()).doubleValue();
        cfg.uniformMaxJoint = ((Number) maxJointSpinner.getValue()).doubleValue();
        cfg.uniformFallback = centerRadio.isSelected() ? SurroundConfig.UNIFORM_CENTER
                : emptyRadio.isSelected() ? SurroundConfig.UNIFORM_EMPTY
                : SurroundConfig.UNIFORM_ADJUST;
        cfg.uniformGaps = gapsSection.isVisible() && sameGapsCheckBox.isSelected();
        cfg.uniformMaxGapDifference = ((Number) maxGapDifferenceSpinner.getValue()).doubleValue();
    }

    /** Loads UI control states from the given {@link SurroundConfig}. */
    void load(SurroundConfig cfg) {
        sameSizeCheckBox.setSelected(cfg.uniformSize);
        maxSizeChangeSpinner.setValue(cfg.uniformMaxSizeChange);
        maxSizeDifferenceSpinner.setValue(cfg.uniformMaxSizeDifference);
        maxJointSpinner.setValue(cfg.uniformMaxJoint);
        if (cfg.uniformFallback == SurroundConfig.UNIFORM_CENTER) {
            centerRadio.setSelected(true);
        } else if (cfg.uniformFallback == SurroundConfig.UNIFORM_EMPTY) {
            emptyRadio.setSelected(true);
        } else {
            adjustRadio.setSelected(true);
        }
        sameGapsCheckBox.setSelected(cfg.uniformGaps);
        maxGapDifferenceSpinner.setValue(cfg.uniformMaxGapDifference);
        updateVisibility();
    }
}
