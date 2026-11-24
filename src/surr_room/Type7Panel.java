/*
 * Copyright (c) 2024-2026 Oliver Mart\u00ednez (Ollie_Jess)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package surr_room;

import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagLayout;
import javax.swing.AbstractButton;
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

    final JRadioButton fixedCountRadio, everyRadio;
    final JSpinner type7ObjectCountSpinner, type7SpacingSpinner;
    final JCheckBox randomizeSizeCheckBox;
    final JPanel randomSubPanel, proportionalRangeRow, perMeasureRows;
    final JRadioButton proportionalRadio, perMeasureRadio;
    final JSpinner limitsAllMinSpinner, limitsAllMaxSpinner;
    final JCheckBox randomizeSizeCheckBoxW, randomizeSizeCheckBoxD, randomizeSizeCheckBoxH;
    final JSpinner limitsWidthMinSpinner, limitsWidthMaxSpinner;
    final JSpinner limitsDepthMinSpinner, limitsDepthMaxSpinner;
    final JSpinner limitsHeightMinSpinner, limitsHeightMaxSpinner;
    private final JLabel[] rangeLabels = new JLabel[9];
    private final Runnable updateUi;

    Type7Panel(UiKit.Texts t, Runnable onChanged) {
        super(new GridBagLayout());
        int row = 0;

        UiKit.addOptionRow(this, row++, 0, UiKit.subtitle(t.get("t7HowMany", "Number of objects")));
        fixedCountRadio = new JRadioButton(t.get("t7FixedCount", "Fixed number:"));
        everyRadio = new JRadioButton(t.get("t7Every", "One every"), true);
        groupRadios(fixedCountRadio, everyRadio);
        type7ObjectCountSpinner = UiKit.spinner(new SpinnerNumberModel(12, 1, 10000, 1), 5);
        type7SpacingSpinner = UiKit.spinner(new SpinnerNumberModel(300, 1, 10000.0, 0.5), 6);
        JLabel countSuffix = new JLabel(t.get("t7FixedCountSuffix", "evenly spaced objects"));
        JLabel everySuffix = new JLabel(t.get("t7EverySuffix", "cm"));
        UiKit.addOptionRow(this, row++, 1, fixedCountRadio, type7ObjectCountSpinner, countSuffix);
        UiKit.addOptionRow(this, row++, 1, everyRadio, type7SpacingSpinner, everySuffix);
        UiKit.addSpacerRow(this, row++);

        randomizeSizeCheckBox = new JCheckBox(t.get("t7VarySize", "Random size:"));
        randomizeSizeCheckBox.setFont(randomizeSizeCheckBox.getFont().deriveFont(Font.BOLD));
        UiKit.addOptionRow(this, row++, 0, randomizeSizeCheckBox);

        randomSubPanel = new JPanel(new GridBagLayout());
        int rr = 0;
        proportionalRadio = new JRadioButton(t.get("t7Proportional", "Proportional:"), true);
        perMeasureRadio = new JRadioButton(t.get("t7PerMeasure", "Per dimension:"));
        groupRadios(proportionalRadio, perMeasureRadio);
        String between = t.get("t7Between", "between"), and = t.get("t7And", "and"), pct = t.get("t7PercentOfSize", "%");

        limitsAllMinSpinner = UiKit.percentSpinner(75);
        limitsAllMaxSpinner = UiKit.percentSpinner(125);
        UiKit.addOptionRow(randomSubPanel, rr++, 1, proportionalRadio);
        proportionalRangeRow = UiKit.addOptionRow(randomSubPanel, rr++, 2, new JLabel(between), limitsAllMinSpinner,
                new JLabel(and), limitsAllMaxSpinner, new JLabel(pct));
        UiKit.addOptionRow(randomSubPanel, rr++, 1, perMeasureRadio);

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

        JCheckBox[] dimChecks = {randomizeSizeCheckBoxW, randomizeSizeCheckBoxD, randomizeSizeCheckBoxH};
        JSpinner[][] dimSpinners = {{limitsWidthMinSpinner, limitsWidthMaxSpinner},
            {limitsDepthMinSpinner, limitsDepthMaxSpinner}, {limitsHeightMinSpinner, limitsHeightMaxSpinner}};
        int checkWidth = 0;
        for (JCheckBox check : dimChecks) {
            checkWidth = Math.max(checkWidth, check.getPreferredSize().width);
        }
        for (int i = 0; i < 3; i++) {
            dimChecks[i].setPreferredSize(new Dimension(checkWidth, dimChecks[i].getPreferredSize().height));
            UiKit.addOptionRow(perMeasureRows, i, 2, dimChecks[i], label(3 * i, between), dimSpinners[i][0],
                    label(3 * i + 1, and), dimSpinners[i][1], label(3 * i + 2, "%"));
            UiKit.linkRange(dimSpinners[i][0], dimSpinners[i][1]);
        }
        UiKit.linkRange(limitsAllMinSpinner, limitsAllMaxSpinner);

        UiKit.addOptionRow(randomSubPanel, rr++, 0, perMeasureRows);
        UiKit.addOptionRow(this, row++, 0, randomSubPanel);
        UiKit.addFiller(this);

        updateUi = () -> {
            boolean fixed = fixedCountRadio.isSelected();
            type7ObjectCountSpinner.setVisible(fixed);
            countSuffix.setVisible(fixed);
            type7SpacingSpinner.setVisible(!fixed);
            everySuffix.setVisible(!fixed);

            boolean random = randomizeSizeCheckBox.isSelected();
            boolean perMeasure = random && perMeasureRadio.isSelected();
            randomSubPanel.setVisible(random);
            proportionalRangeRow.setVisible(random && proportionalRadio.isSelected());
            perMeasureRows.setVisible(perMeasure);
            for (int i = 0; i < 3; i++) {
                boolean on = perMeasure && dimChecks[i].isSelected();
                dimSpinners[i][0].setVisible(on);
                dimSpinners[i][1].setVisible(on);
                rangeLabels[3 * i].setVisible(on);
                rangeLabels[3 * i + 1].setVisible(on);
                rangeLabels[3 * i + 2].setVisible(on);
            }
            revalidate();
            repaint();
        };

        for (AbstractButton b : new AbstractButton[]{fixedCountRadio, everyRadio, randomizeSizeCheckBox,
            proportionalRadio, perMeasureRadio, randomizeSizeCheckBoxW, randomizeSizeCheckBoxD, randomizeSizeCheckBoxH}) {
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

    private static void groupRadios(JRadioButton a, JRadioButton b) {
        ButtonGroup g = new ButtonGroup();
        g.add(a);
        g.add(b);
    }

    private JLabel label(int index, String text) {
        return rangeLabels[index] = new JLabel(text);
    }

    void fill(SurroundConfig cfg) {
        cfg.type7ObjectCount = fixedCountRadio.isSelected() ? ((Number) type7ObjectCountSpinner.getValue()).intValue() : 0;
        cfg.type7Spacing = number(type7SpacingSpinner);
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

    private static double number(JSpinner spinner) {
        return ((Number) spinner.getValue()).doubleValue();
    }
}
