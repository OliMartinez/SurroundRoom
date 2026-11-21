/*
 * Copyright (c) 2024-2026 Oliver Mart\u00ednez (Ollie_Jess)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package surr_room;

import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import javax.swing.ButtonGroup;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;

/**
 * Type 7 settings: a number of objects or one every distance, and random size (proportional within a
 * percentage range, or per dimension); sub-controls show only when they apply.
 */
class Type7Panel extends JPanel {

    /** Radio button selecting a fixed total number of objects. */
    final JRadioButton fixedCountRadio;

    /** Radio button selecting discrete placement every X centimeters. */
    final JRadioButton everyRadio;

    /** Spinner specifying total number of objects (active when {@link #fixedCountRadio} is selected). */
    final JSpinner type7ObjectCountSpinner;

    /** Spinner specifying distance interval between objects in cm (active when {@link #everyRadio} is selected). */
    final JSpinner type7SpacingSpinner;

    /** Checkbox toggling random size variations for placed objects. */
    final JCheckBox randomizeSizeCheckBox;

    /** Nested container hosting random variation controls. */
    final JPanel randomSubPanel;

    /** Radio button selecting proportional uniform scaling across all 3 dimensions. */
    final JRadioButton proportionalRadio;

    /** Container panel holding the proportional min/max percentage range spinners. */
    final JPanel proportionalRangeRow;

    /** Radio button selecting independent per-dimension scaling. */
    final JRadioButton perMeasureRadio;

    /** Container panel holding individual width, depth, and height variation rows. */
    final JPanel perMeasureRows;

    /** Spinners for proportional min and max scaling percentages. */
    final JSpinner limitsAllMinSpinner, limitsAllMaxSpinner;

    /** Checkboxes enabling independent random scaling for Width, Depth, and Height. */
    final JCheckBox randomizeSizeCheckBoxW, randomizeSizeCheckBoxD, randomizeSizeCheckBoxH;

    /** Spinners for independent Width min and max scaling percentages. */
    final JSpinner limitsWidthMinSpinner, limitsWidthMaxSpinner;

    /** Spinners for independent Depth min and max scaling percentages. */
    final JSpinner limitsDepthMinSpinner, limitsDepthMaxSpinner;

    /** Spinners for independent Height min and max scaling percentages. */
    final JSpinner limitsHeightMinSpinner, limitsHeightMaxSpinner;

    /** Cached text labels in the per-dimension rows to control visibility dynamically. */
    private final JLabel[] rangeLabels = new JLabel[9];

    /** Runnable delegate updating control visibility and layout reflow. */
    private final Runnable updateUi;

    /** Default constructor with a no-op change listener. */
    Type7Panel(UiKit.Texts t) {
        this(t, () -> {});
    }

    /**
     * Main constructor initializing all controls and change listeners.
     *
     * @param onChanged callback executed when any Type 7 parameter changes
     */
    Type7Panel(UiKit.Texts t, Runnable onChanged) {
        super(new GridBagLayout());
        int row = 0;

        // Header: Distribution mode
        UiKit.addOptionRow(this, row++, 0,
                UiKit.subtitle(t.get("t7HowMany", "Number of objects")),
                UiKit.infoIcon(t.get("t7SpreadHint", "Distributed continuously between the first and last vertex, skipping openings and excluded sides.")));
        fixedCountRadio = new JRadioButton(t.get("t7FixedCount", "Fixed number:"));
        everyRadio = new JRadioButton(t.get("t7Every", "One every"), true);
        ButtonGroup countGroup = new ButtonGroup();
        countGroup.add(fixedCountRadio);
        countGroup.add(everyRadio);
        type7ObjectCountSpinner = UiKit.spinner(new SpinnerNumberModel(12, 1, 10000, 1), 5);
        type7SpacingSpinner = UiKit.spinner(new SpinnerNumberModel(300, 1, 10000.0, 0.5), 6);
        JLabel countSuffix = new JLabel(t.get("t7FixedCountSuffix", "evenly spaced objects"));
        JLabel everySuffix = new JLabel(t.get("t7EverySuffix", "cm"));
        UiKit.addOptionRow(this, row++, 1, fixedCountRadio, type7ObjectCountSpinner, countSuffix);
        UiKit.addOptionRow(this, row++, 1, everyRadio, type7SpacingSpinner, everySuffix);
        UiKit.addSpacerRow(this, row++);

        // Random size header checkbox
        randomizeSizeCheckBox = new JCheckBox(t.get("t7VarySize", "Random size:"));
        randomizeSizeCheckBox.setFont(randomizeSizeCheckBox.getFont().deriveFont(java.awt.Font.BOLD));
        UiKit.addOptionRow(this, row++, 0, randomizeSizeCheckBox);

        randomSubPanel = new JPanel(new GridBagLayout());
        int rr = 0;
        proportionalRadio = new JRadioButton(t.get("t7Proportional", "Proportional:"), true);
        perMeasureRadio = new JRadioButton(t.get("t7PerMeasure", "Per dimension:"));
        ButtonGroup sizeGroup = new ButtonGroup();
        sizeGroup.add(proportionalRadio);
        sizeGroup.add(perMeasureRadio);
        String between = t.get("t7Between", "between");
        String and = t.get("t7And", "and");
        String percentOfSize = t.get("t7PercentOfSize", "%");

        // Proportional min/max spinners
        limitsAllMinSpinner = UiKit.percentSpinner(75);
        limitsAllMaxSpinner = UiKit.percentSpinner(125);
        UiKit.addOptionRow(randomSubPanel, rr++, 1, proportionalRadio);
        proportionalRangeRow = UiKit.addOptionRow(randomSubPanel, rr++, 2, new JLabel(between), limitsAllMinSpinner,
                new JLabel(and), limitsAllMaxSpinner, new JLabel(percentOfSize));
        UiKit.addOptionRow(randomSubPanel, rr++, 1, perMeasureRadio);

        // Per-dimension controls
        perMeasureRows = new JPanel(new GridBagLayout());
        randomizeSizeCheckBoxW = new JCheckBox(t.get("t7Width", "Width"), true);
        randomizeSizeCheckBoxD = new JCheckBox(t.get("t7Depth", "Depth"), true);
        randomizeSizeCheckBoxH = new JCheckBox(t.get("t7Height", "Height"), true);
        limitsWidthMinSpinner = UiKit.percentSpinner(75);
        limitsWidthMaxSpinner = UiKit.percentSpinner(125);
        limitsDepthMinSpinner = UiKit.percentSpinner(75);
        limitsDepthMaxSpinner = UiKit.percentSpinner(125);
        limitsHeightMinSpinner = UiKit.percentSpinner(75);
        limitsHeightMaxSpinner = UiKit.percentSpinner(125);

        // Normalize checkbox widths for aligned columnar layout
        int checkWidth = Math.max(randomizeSizeCheckBoxW.getPreferredSize().width,
                Math.max(randomizeSizeCheckBoxD.getPreferredSize().width, randomizeSizeCheckBoxH.getPreferredSize().width));
        for (JCheckBox check : new JCheckBox[]{randomizeSizeCheckBoxW, randomizeSizeCheckBoxD, randomizeSizeCheckBoxH}) {
            check.setPreferredSize(new java.awt.Dimension(checkWidth, check.getPreferredSize().height));
        }
        UiKit.addOptionRow(perMeasureRows, 0, 2, randomizeSizeCheckBoxW, label(0, between), limitsWidthMinSpinner,
                label(1, and), limitsWidthMaxSpinner, label(2, "%"));
        UiKit.addOptionRow(perMeasureRows, 1, 2, randomizeSizeCheckBoxD, label(3, between), limitsDepthMinSpinner,
                label(4, and), limitsDepthMaxSpinner, label(5, "%"));
        UiKit.addOptionRow(perMeasureRows, 2, 2, randomizeSizeCheckBoxH, label(6, between), limitsHeightMinSpinner,
                label(7, and), limitsHeightMaxSpinner, label(8, "%"));

        GridBagConstraints pmc = new GridBagConstraints();
        pmc.gridx = 0;
        pmc.gridy = rr++;
        pmc.anchor = GridBagConstraints.LINE_START;
        randomSubPanel.add(perMeasureRows, pmc);

        GridBagConstraints rsc = new GridBagConstraints();
        rsc.gridx = 0;
        rsc.gridy = row++;
        rsc.anchor = GridBagConstraints.LINE_START;
        add(randomSubPanel, rsc);

        UiKit.addFiller(this);

        // Link spinner pairs so min <= max
        UiKit.linkRange(limitsAllMinSpinner, limitsAllMaxSpinner);
        UiKit.linkRange(limitsWidthMinSpinner, limitsWidthMaxSpinner);
        UiKit.linkRange(limitsDepthMinSpinner, limitsDepthMaxSpinner);
        UiKit.linkRange(limitsHeightMinSpinner, limitsHeightMaxSpinner);

        // Dynamic visibility reflow logic
        updateUi = () -> {
            boolean fixed = fixedCountRadio.isSelected();
            type7ObjectCountSpinner.setVisible(fixed);
            countSuffix.setVisible(fixed);
            type7SpacingSpinner.setVisible(!fixed);
            everySuffix.setVisible(!fixed);

            boolean random = randomizeSizeCheckBox.isSelected();
            boolean proportional = random && proportionalRadio.isSelected();
            boolean perMeasure = random && perMeasureRadio.isSelected();
            randomSubPanel.setVisible(random);
            proportionalRangeRow.setVisible(proportional);
            perMeasureRows.setVisible(perMeasure);
            JCheckBox[] checks = {randomizeSizeCheckBoxW, randomizeSizeCheckBoxD, randomizeSizeCheckBoxH};
            JSpinner[][] spinners = {{limitsWidthMinSpinner, limitsWidthMaxSpinner},
                {limitsDepthMinSpinner, limitsDepthMaxSpinner}, {limitsHeightMinSpinner, limitsHeightMaxSpinner}};
            for (int i = 0; i < 3; i++) {
                boolean on = perMeasure && checks[i].isSelected();
                spinners[i][0].setVisible(on);
                spinners[i][1].setVisible(on);
                rangeLabels[3 * i].setVisible(on);
                rangeLabels[3 * i + 1].setVisible(on);
                rangeLabels[3 * i + 2].setVisible(on);
            }
            revalidate();
            repaint();
        };

        // Wire change listeners
        for (javax.swing.AbstractButton b : new javax.swing.AbstractButton[]{fixedCountRadio, everyRadio,
            randomizeSizeCheckBox, proportionalRadio, perMeasureRadio, randomizeSizeCheckBoxW, randomizeSizeCheckBoxD,
            randomizeSizeCheckBoxH}) {
            b.addActionListener(e -> {
                updateUi.run();
                onChanged.run();
            });
        }
        for (JSpinner sp : new JSpinner[]{type7ObjectCountSpinner, type7SpacingSpinner,
            limitsAllMinSpinner, limitsAllMaxSpinner, limitsWidthMinSpinner, limitsWidthMaxSpinner,
            limitsDepthMinSpinner, limitsDepthMaxSpinner, limitsHeightMinSpinner, limitsHeightMaxSpinner}) {
            sp.addChangeListener(e -> onChanged.run());
        }
        updateUi.run();
    }

    /** Helper to allocate and store range separator labels for visibility toggling. */
    private JLabel label(int index, String text) {
        rangeLabels[index] = new JLabel(text);
        return rangeLabels[index];
    }

    /** Populates the configuration DTO with values from Type 7 UI controls. */
    void fill(SurroundConfig cfg) {
        cfg.type7ObjectCount = fixedCountRadio.isSelected() ? ((Number) type7ObjectCountSpinner.getValue()).intValue() : 0;
        cfg.type7Spacing = ((Number) type7SpacingSpinner.getValue()).doubleValue();
        boolean random = randomizeSizeCheckBox.isSelected();
        cfg.randomizeAll = random && proportionalRadio.isSelected();
        boolean perMeasure = random && perMeasureRadio.isSelected();
        cfg.randomizeWidth = perMeasure && randomizeSizeCheckBoxW.isSelected();
        cfg.randomizeDepth = perMeasure && randomizeSizeCheckBoxD.isSelected();
        cfg.randomizeHeight = perMeasure && randomizeSizeCheckBoxH.isSelected();
        cfg.minAll = number(limitsAllMinSpinner);
        cfg.maxAll = number(limitsAllMaxSpinner);
        cfg.minWidth = number(limitsWidthMinSpinner);
        cfg.maxWidth = number(limitsWidthMaxSpinner);
        cfg.minDepth = number(limitsDepthMinSpinner);
        cfg.maxDepth = number(limitsDepthMaxSpinner);
        cfg.minHeight = number(limitsHeightMinSpinner);
        cfg.maxHeight = number(limitsHeightMaxSpinner);
    }

    /** Loads control states from the given configuration DTO. */
    void load(SurroundConfig cfg) {
        if (cfg.type7ObjectCount > 0) {
            fixedCountRadio.setSelected(true);
            type7ObjectCountSpinner.setValue(cfg.type7ObjectCount);
        } else {
            everyRadio.setSelected(true);
        }
        type7SpacingSpinner.setValue(cfg.type7Spacing);
        boolean anyRandom = cfg.randomizeAll || cfg.randomizeWidth || cfg.randomizeDepth || cfg.randomizeHeight;
        randomizeSizeCheckBox.setSelected(anyRandom);
        if (cfg.randomizeAll || !anyRandom) {
            proportionalRadio.setSelected(true);
        } else {
            perMeasureRadio.setSelected(true);
        }
        randomizeSizeCheckBoxW.setSelected(cfg.randomizeWidth);
        randomizeSizeCheckBoxD.setSelected(cfg.randomizeDepth);
        randomizeSizeCheckBoxH.setSelected(cfg.randomizeHeight);
        limitsAllMinSpinner.setValue(cfg.minAll);
        limitsAllMaxSpinner.setValue(cfg.maxAll);
        limitsWidthMinSpinner.setValue(cfg.minWidth);
        limitsWidthMaxSpinner.setValue(cfg.maxWidth);
        limitsDepthMinSpinner.setValue(cfg.minDepth);
        limitsDepthMaxSpinner.setValue(cfg.maxDepth);
        limitsHeightMinSpinner.setValue(cfg.minHeight);
        limitsHeightMaxSpinner.setValue(cfg.maxHeight);
        updateUi.run();
    }

    /** Extracts double value from a numeric JSpinner. */
    private static double number(JSpinner spinner) {
        return ((Number) spinner.getValue()).doubleValue();
    }
}
