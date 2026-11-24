/*
 * Copyright (c) 2024-2026 Oliver Mart\u00ednez (Ollie_Jess)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package surr_room;

import com.eteks.sweethome3d.model.HomePieceOfFurniture;
import com.eteks.sweethome3d.model.UserPreferences;
import java.awt.Component;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ActionListener;
import java.text.MessageFormat;
import javax.swing.AbstractButton;
import javax.swing.Box;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;

/**
 * Right-hand options panel: distance to edge, grouping, modified dimensions, direction, margins,
 * openings, collisions, stacking, random/gradual properties, and corners. Irrelevant options are hidden.
 */
class OptionsPanel extends JPanel {

    final JSpinner distanceSpinner;

    final JCheckBox enableGroupingCheckBox;
    final JPanel groupingSubPanel;
    final JRadioButton groupAllRadio, groupPerSideRadio;

    final JPanel dimModifySection, dimSelectedChecksRow;
    final JRadioButton dimProportionalRadio, dimSelectedRadio, dimWidthOnlyRadio;
    final JCheckBox dimCheckW, dimCheckD, dimCheckH;

    final JPanel dirAndEndsSection;
    final JCheckBox reverseDirectionCheckBox, symmetricBothEndsCheckBox;

    final JPanel pieceMarginSection, pieceMarginSubRow;
    final JCheckBox enablePieceMarginCheckBox;
    final JSpinner pieceMarginSpinner;

    final JCheckBox splitAtObstaclesCheckBox, respectWindowsCheckBox, gapHeightRangeCheckBox;
    final JPanel gapsSubPanel, gapHeightRow, gapToleranceHeaderRow, gapFitToleranceSubRow;
    final JSpinner gapMinHeightSpinner, gapMaxHeightSpinner, gapFitToleranceSpinner;
    final JCheckBox enableGapFitToleranceCheckBox, shortSidesCheckBox;
    final JCheckBox avoidCollisionsCheckBox, avoidNearbyFloorsCheckBox, avoidNewlyGeneratedCheckBox;
    final JCheckBox enableCollisionFitToleranceCheckBox;
    final JPanel avoidCollisionsSubPanel, collisionToleranceHeaderRow, collisionFitToleranceSubRow;
    final JSpinner collisionFitToleranceSpinner;
    final JPanel upperLevelHolesSection;
    final JRadioButton floorHolesPassRadio, floorHolesNoPassRadio;

    final JCheckBox enableVerticalStackingCheckBox, useCustomStackElevationCheckBox;
    final JPanel verticalStackingSubPanel, customStackElevationSubRow;
    final JSpinner verticalStackCountSpinner, customStackElevationSpinner;

    final JCheckBox enableRandomPropsCheckBox, enableGradualPropsCheckBox;
    final JPanel randomPropsSubRow, gradualPropsSubRow;
    final JButton configureRandomPropsButton, configureGradualPropsButton;
    final JLabel randomPropsSummaryLabel, gradualPropsSummaryLabel;
    SurroundRandomSettings randomSettings = new SurroundRandomSettings();
    SurroundRandomSettings gradualSettings = new SurroundRandomSettings();
    private Runnable onConfigureRandomProps, onConfigureGradualProps;

    final JPanel cornerHeaderRow, cornerRows, orthoSubPanel, cornerTouchVertexRow, cornerKeepSizeRow, catalogCombosRow;
    final JCheckBox enableCornerHandlingCheckBox, cornerTouchVertexCheckBox, cornerKeepSizeCheckBox, useCatalogCornerCheckBox;
    final JRadioButton cornerAvoidOverlapRadio, cornerPlaceOrthogonalRadio, cornerCustomRadio;
    final JRadioButton orthoOverlapRadio, orthoSeparateRadio, orthoFirstVertexRadio, orthoSecondVertexRadio, orthoMidVertexRadio;
    final JSpinner cornerMarginSpinner;
    final JLabel cornerMarginUnitLabel, cornerMarginSignHint, cornerObjectLabel;
    final JButton pickCornerObjectButton;
    private HomePieceOfFurniture customCornerPiece;
    private Runnable onPickCornerObject;

    final JPanel customEndsSubPanel, extraObjectRespectRow, extraObjectCornersRow;
    final JComboBox<Integer> globalStartEndCombo, globalEndEndCombo;
    final JSpinner globalStartEndDistanceSpinner, globalEndEndDistanceSpinner;
    final JLabel globalStartEndCmLabel, globalStartEndInfoIcon, globalEndEndCmLabel, globalEndEndInfoIcon;
    final JButton presetCornersNoOverlapButton;
    final JCheckBox extraObjectRespectObstaclesCheckBox, extraObjectRespectCornersCheckBox;

    private final UiKit.Texts texts;
    private final boolean hasFloors, hasUpperLevel;
    private int currentType = 1;
    private boolean uniformSizeActive;
    private boolean dimModeChosenByUser;

    OptionsPanel(UiKit.Texts t, UserPreferences userPreferences, boolean hasTargets, boolean hasFloors,
            boolean hasUpperLevel, Runnable onCornerChanged) {
        super(new GridBagLayout());
        this.texts = t;
        this.hasFloors = hasFloors;
        this.hasUpperLevel = hasUpperLevel;
        int row = 0;

        UiKit.addOptionRow(this, row++, 0, UiKit.subtitle(t.get("groupingTitle", "Position, grouping and dimensions")));
        distanceSpinner = UiKit.spinner(new SpinnerNumberModel(0.0, -10000.0, 10000.0, 0.5), 6);
        UiKit.addOptionRow(this, row++, 1,
                new JLabel(t.get("t7EdgeDistance", "Distance from the edge:")),
                distanceSpinner, new JLabel("cm"),
                UiKit.hint(t.get("edgeDistanceSignHint", "(+ away, \u2212 inward)")));

        enableGroupingCheckBox = new JCheckBox(t.get("enableGrouping", "Group generated models:"), false);
        UiKit.addOptionRow(this, row++, 1, enableGroupingCheckBox);
        groupingSubPanel = new JPanel(new GridBagLayout());
        groupAllRadio = new JRadioButton(t.get("groupAll", "Into a single group"), true);
        groupPerSideRadio = new JRadioButton(t.get("groupPerSide", "Per side"));
        group(groupAllRadio, groupPerSideRadio);
        UiKit.addOptionRow(groupingSubPanel, 0, 2, groupAllRadio);
        UiKit.addOptionRow(groupingSubPanel, 1, 2, groupPerSideRadio);
        addSubPanel(groupingSubPanel, row++);

        dimModifySection = new JPanel(new GridBagLayout());
        int dr = 0;
        UiKit.addOptionRow(dimModifySection, dr++, 1, new JLabel(t.get("dimModifyTitle", "Dimensions that change:")));
        dimProportionalRadio = new JRadioButton(t.get("dimProportional", "Proportional (all)"), true);
        dimSelectedRadio = new JRadioButton(t.get("dimSelectedOnly", "Width and selected:"));
        dimWidthOnlyRadio = new JRadioButton(t.get("dimWidthOnly", "Width only"));
        group(dimProportionalRadio, dimSelectedRadio, dimWidthOnlyRadio);
        UiKit.addOptionRow(dimModifySection, dr++, 2, dimProportionalRadio);
        UiKit.addOptionRow(dimModifySection, dr++, 2, dimSelectedRadio);
        dimCheckW = new JCheckBox(t.get("t7Width", "Width"), true);
        dimCheckW.setEnabled(false);
        dimCheckD = new JCheckBox(t.get("t7Depth", "Depth"), true);
        dimCheckH = new JCheckBox(t.get("t7Height", "Height"), false);
        dimSelectedChecksRow = UiKit.addOptionRow(dimModifySection, dr++, 3,
                dimCheckW, Box.createHorizontalStrut(6), dimCheckD, Box.createHorizontalStrut(6), dimCheckH);
        UiKit.addOptionRow(dimModifySection, dr++, 2, dimWidthOnlyRadio);
        addSubPanel(dimModifySection, row++);
        for (JRadioButton radio : new JRadioButton[]{dimProportionalRadio, dimSelectedRadio, dimWidthOnlyRadio}) {
            radio.addActionListener(e -> dimModeChosenByUser = true);
        }

        dirAndEndsSection = new JPanel(new GridBagLayout());
        reverseDirectionCheckBox = new JCheckBox(t.get("reverseDirection", "Reverse the direction (from end to start)"), false);
        symmetricBothEndsCheckBox = new JCheckBox(t.get("symmetricBothEnds", "Adjust both ends of each side"), false);
        UiKit.addOptionRow(dirAndEndsSection, 0, 1, reverseDirectionCheckBox);
        UiKit.addOptionRow(dirAndEndsSection, 1, 1, symmetricBothEndsCheckBox);
        addSubPanel(dirAndEndsSection, row++);

        pieceMarginSection = new JPanel(new GridBagLayout());
        enablePieceMarginCheckBox = new JCheckBox(t.get("enablePieceMargin", "Leave margin around the piece:"), false);
        pieceMarginSpinner = UiKit.spinner(new SpinnerNumberModel(2.0, -5000.0, 5000.0, 0.5), 5);
        UiKit.addOptionRow(pieceMarginSection, 0, 1, enablePieceMarginCheckBox);
        pieceMarginSubRow = UiKit.addOptionRow(pieceMarginSection, 1, 2,
                new JLabel(t.get("pieceMarginValue", "Margin:")), pieceMarginSpinner, new JLabel("cm"),
                UiKit.hint(t.get("pieceMarginSignHint", "(+ separates, \u2212 overlaps)")));
        addSubPanel(pieceMarginSection, row++);
        UiKit.addSpacerRow(this, row++);

        UiKit.addOptionRow(this, row++, 0, UiKit.subtitle(t.get("sidesAndObstaclesTitle", "Openings and collisions")));
        splitAtObstaclesCheckBox = new JCheckBox(t.get("splitAtObstacles", "Respect door openings"), true);
        UiKit.addOptionRow(this, row++, 1, splitAtObstaclesCheckBox);
        respectWindowsCheckBox = new JCheckBox(t.get("respectWindows", "Respect window openings"), false);
        UiKit.addOptionRow(this, row++, 1, respectWindowsCheckBox);

        gapsSubPanel = new JPanel(new GridBagLayout());
        gapHeightRangeCheckBox = new JCheckBox(t.get("gapHeightRange", "Only openings between these heights:"), false);
        UiKit.addOptionRow(gapsSubPanel, 0, 2, gapHeightRangeCheckBox);
        gapMinHeightSpinner = UiKit.spinner(new SpinnerNumberModel(0.0, -1000.0, 10000.0, 1.0), 5);
        gapMaxHeightSpinner = UiKit.spinner(new SpinnerNumberModel(250.0, -1000.0, 10000.0, 1.0), 5);
        gapHeightRow = UiKit.addOptionRow(gapsSubPanel, 1, 3, gapMinHeightSpinner,
                new JLabel(t.get("gapHeightTo", "to")), gapMaxHeightSpinner, new JLabel("cm"));
        enableGapFitToleranceCheckBox = new JCheckBox(t.get("enableGapFitTolerance", "Allow overlap if it does not fit:"), false);
        gapFitToleranceSpinner = UiKit.spinner(new SpinnerNumberModel(5.0, 0.5, 500.0, 0.5), 5);
        gapToleranceHeaderRow = UiKit.addOptionRow(gapsSubPanel, 2, 2, enableGapFitToleranceCheckBox);
        gapFitToleranceSubRow = UiKit.addOptionRow(gapsSubPanel, 3, 3,
                new JLabel(t.get("fitToleranceValue", "Max.:")), gapFitToleranceSpinner, new JLabel("cm"));
        addSubPanel(gapsSubPanel, row++);

        shortSidesCheckBox = new JCheckBox(t.get("shortSidesOneObject", "Place one object on sides shorter than it, even if it sticks out"), false);
        UiKit.addOptionRow(this, row++, 1, shortSidesCheckBox);

        avoidCollisionsCheckBox = new JCheckBox(t.get("avoidCollisions", "Avoid collisions with furniture and walls:"), false);
        UiKit.addOptionRow(this, row++, 1, avoidCollisionsCheckBox);
        avoidCollisionsSubPanel = new JPanel(new GridBagLayout());
        int acr = 0;
        avoidNearbyFloorsCheckBox = new JCheckBox(t.get("avoidNearbyFloors", "Also avoid collisions with other nearby floors"), false);
        if (hasFloors) {
            UiKit.addOptionRow(avoidCollisionsSubPanel, acr++, 2, avoidNearbyFloorsCheckBox);
        }
        avoidNewlyGeneratedCheckBox = new JCheckBox(t.get("avoidNewlyGenerated", "Include the objects being placed (other sides and rooms)"), false);
        UiKit.addOptionRow(avoidCollisionsSubPanel, acr++, 2, avoidNewlyGeneratedCheckBox);
        enableCollisionFitToleranceCheckBox = new JCheckBox(t.get("enableCollisionFitTolerance", "Allow overlap if it does not fit:"), false);
        collisionFitToleranceSpinner = UiKit.spinner(new SpinnerNumberModel(5.0, 0.5, 500.0, 0.5), 5);
        collisionToleranceHeaderRow = UiKit.addOptionRow(avoidCollisionsSubPanel, acr++, 2, enableCollisionFitToleranceCheckBox);
        collisionFitToleranceSubRow = UiKit.addOptionRow(avoidCollisionsSubPanel, acr++, 3,
                new JLabel(t.get("fitToleranceValue", "Max.:")), collisionFitToleranceSpinner, new JLabel("cm"));
        addSubPanel(avoidCollisionsSubPanel, row++);

        upperLevelHolesSection = new JPanel(new GridBagLayout());
        UiKit.addOptionRow(upperLevelHolesSection, 0, 1, new JLabel(t.get("upperLevelHolesTitle", "Floor openings (upper levels):")));
        floorHolesPassRadio = new JRadioButton(t.get("floorHolesPass", "Pass over floor holes"), true);
        floorHolesNoPassRadio = new JRadioButton(t.get("floorHolesNoPass", "Do not cross the openings (warns if there is no floor below)"));
        group(floorHolesPassRadio, floorHolesNoPassRadio);
        UiKit.addOptionRow(upperLevelHolesSection, 1, 2, floorHolesPassRadio);
        UiKit.addOptionRow(upperLevelHolesSection, 2, 2, floorHolesNoPassRadio);
        upperLevelHolesSection.setVisible(hasUpperLevel);
        addSubPanel(upperLevelHolesSection, row++);

        enableVerticalStackingCheckBox = new JCheckBox(t.get("enableVerticalStacking", "Stack vertically:"), false);
        UiKit.addOptionRow(this, row++, 0, enableVerticalStackingCheckBox);
        verticalStackingSubPanel = new JPanel(new GridBagLayout());
        JLabel stackTimesLabel = new JLabel(t.get("verticalStackTimes", "Levels:"));
        verticalStackCountSpinner = UiKit.spinner(new SpinnerNumberModel(1, 1, 500, 1), 4);
        stackTimesLabel.setLabelFor(verticalStackCountSpinner);
        UiKit.addOptionRow(verticalStackingSubPanel, 0, 1, stackTimesLabel, verticalStackCountSpinner);
        useCustomStackElevationCheckBox = new JCheckBox(t.get("useCustomStackElevation", "Custom vertical spacing:"), false);
        customStackElevationSpinner = UiKit.spinner(new SpinnerNumberModel(100.0, -10000.0, 10000.0, 1.0), 6);
        UiKit.addOptionRow(verticalStackingSubPanel, 1, 1, useCustomStackElevationCheckBox);
        customStackElevationSubRow = UiKit.addOptionRow(verticalStackingSubPanel, 2, 2, customStackElevationSpinner, new JLabel("cm"));
        addSubPanel(verticalStackingSubPanel, row++);
        UiKit.addSpacerRow(this, row++);

        enableRandomPropsCheckBox = new JCheckBox(t.get("enableRandomProps", "Random properties:"), false);
        configureRandomPropsButton = new JButton(t.get("configureRandomPropsButton", "Configure\u2026"));
        randomPropsSummaryLabel = UiKit.hint("");
        enableRandomPropsCheckBox.addActionListener(e -> {
            if (enableRandomPropsCheckBox.isSelected() && !randomSettings.hasAny()) {
                randomSettings.enabled.put("look", true);
            }
            updateRandomPropsSummary();
            updateDependentVisibility();
        });
        configureRandomPropsButton.addActionListener(e -> {
            if (onConfigureRandomProps != null) {
                onConfigureRandomProps.run();
                updateRandomPropsSummary();
            }
        });
        UiKit.addOptionRow(this, row++, 0, enableRandomPropsCheckBox);
        randomPropsSubRow = UiKit.addOptionRow(this, row++, 1, configureRandomPropsButton,
                Box.createHorizontalStrut(8), randomPropsSummaryLabel);

        enableGradualPropsCheckBox = new JCheckBox(t.get("enableGradualProps", "Gradual property change:"), false);
        configureGradualPropsButton = new JButton(t.get("configureGradualPropsButton", "Configure\u2026"));
        gradualPropsSummaryLabel = UiKit.hint("");
        enableGradualPropsCheckBox.addActionListener(e -> {
            if (enableGradualPropsCheckBox.isSelected() && !gradualSettings.hasAnyGradual()) {
                gradualSettings.enabled.put(Boolean.TRUE.equals(randomSettings.enabled.get("look")) ? "shininess" : "look", true);
            }
            updateGradualPropsSummary();
            updateDependentVisibility();
        });
        configureGradualPropsButton.addActionListener(e -> {
            if (onConfigureGradualProps != null) {
                onConfigureGradualProps.run();
                updateGradualPropsSummary();
            }
        });
        UiKit.addOptionRow(this, row++, 0, enableGradualPropsCheckBox);
        gradualPropsSubRow = UiKit.addOptionRow(this, row++, 1, configureGradualPropsButton,
                Box.createHorizontalStrut(8), gradualPropsSummaryLabel);
        UiKit.addSpacerRow(this, row++);

        enableCornerHandlingCheckBox = new JCheckBox(t.get("enableCornerHandling", "Corner handling:"), false);
        cornerHeaderRow = UiKit.addOptionRow(this, row++, 0, enableCornerHandlingCheckBox);

        cornerRows = new JPanel(new GridBagLayout());
        int cr = 0;
        cornerAvoidOverlapRadio = new JRadioButton(t.get("cornerAvoid", "Leave the corners empty"), true);
        cornerPlaceOrthogonalRadio = new JRadioButton(t.get("cornerPlaceOrthogonal", "Place a perpendicular piece in each corner:"));
        cornerCustomRadio = new JRadioButton(t.get("cornerCustom", "Set the start and end of each side:"));
        group(cornerAvoidOverlapRadio, cornerPlaceOrthogonalRadio, cornerCustomRadio);
        UiKit.addOptionRow(cornerRows, cr++, 0, cornerAvoidOverlapRadio);
        UiKit.addOptionRow(cornerRows, cr++, 0, cornerPlaceOrthogonalRadio);

        orthoSubPanel = new JPanel(new GridBagLayout());
        int or = 0;
        orthoOverlapRadio = new JRadioButton(t.get("orthoOverlap", "No adjustment (may overlap)"), true);
        orthoSeparateRadio = new JRadioButton(t.get("orthoSeparate", "Leave margin around the piece:"));
        cornerMarginSpinner = UiKit.spinner(new SpinnerNumberModel(2.0, -5000.0, 5000.0, 0.5), 5);
        cornerMarginUnitLabel = new JLabel("cm");
        cornerMarginSignHint = UiKit.hint(t.get("pieceMarginSignHint", "(+ separates, \u2212 overlaps)"));
        orthoFirstVertexRadio = new JRadioButton(t.get("orthoFirstVertex", "Up to 1st vertex of the piece"));
        orthoSecondVertexRadio = new JRadioButton(t.get("orthoSecondVertex", "Up to 2nd vertex of the piece"));
        orthoMidVertexRadio = new JRadioButton(t.get("orthoMidVertex", "Up to midpoint between 1st and 2nd vertex"));
        group(orthoOverlapRadio, orthoSeparateRadio, orthoFirstVertexRadio, orthoSecondVertexRadio, orthoMidVertexRadio);
        UiKit.addOptionRow(orthoSubPanel, or++, 1, orthoOverlapRadio);
        UiKit.addOptionRow(orthoSubPanel, or++, 1, orthoSeparateRadio, cornerMarginSpinner, cornerMarginUnitLabel, cornerMarginSignHint);
        UiKit.addOptionRow(orthoSubPanel, or++, 1, orthoFirstVertexRadio,
                UiKit.infoIcon(t.get("orthoFirstVertexTip",
                        "The objects of the sides reach the vertex of the corner piece nearest to them. The piece stays square to the corner and takes the size of the objects of its side.")));
        UiKit.addOptionRow(orthoSubPanel, or++, 1, orthoSecondVertexRadio,
                UiKit.infoIcon(t.get("orthoSecondVertexTip",
                        "The objects of the sides reach the second nearest vertex of the corner piece, partly under it. The piece stays square to the corner and takes the size of the objects of its side.")));
        UiKit.addOptionRow(orthoSubPanel, or++, 1, orthoMidVertexRadio,
                UiKit.infoIcon(t.get("orthoMidVertexTip",
                        "Adjacent objects reach the midpoint between the first and second vertex of the piece (adjusting its angle if needed).")));

        cornerTouchVertexCheckBox = new JCheckBox(t.get("cornerTouchVertex", "No gap between piece and vertex (angles < 180\u00b0)"), false);
        cornerTouchVertexRow = UiKit.addOptionRow(orthoSubPanel, or++, 1, cornerTouchVertexCheckBox,
                UiKit.infoIcon(t.get("cornerTouchVertexTip",
                        "On corners under 180\u00b0, places the bottom edge of the piece directly against the vertex (allowing overlap with the sides without crossing the vertex into the wall or floor).")));
        cornerKeepSizeCheckBox = new JCheckBox(t.get("cornerKeepSize", "Keep original size of the corner piece"), false);
        cornerKeepSizeRow = UiKit.addOptionRow(orthoSubPanel, or++, 1, cornerKeepSizeCheckBox);

        useCatalogCornerCheckBox = new JCheckBox(t.get("useCatalogCorner", "Use another object for the corners:"), false);
        pickCornerObjectButton = new JButton(t.get("pickCornerObjectButton", "Select object\u2026"));
        pickCornerObjectButton.addActionListener(e -> {
            if (onPickCornerObject != null) {
                onPickCornerObject.run();
            }
        });
        cornerObjectLabel = UiKit.hint(t.get("cornerObjectNone", "None selected"));
        UiKit.addOptionRow(orthoSubPanel, or++, 1, useCatalogCornerCheckBox);
        catalogCombosRow = UiKit.addOptionRow(orthoSubPanel, or++, 2, pickCornerObjectButton,
                Box.createHorizontalStrut(8), cornerObjectLabel);
        addSubPanel(cornerRows, orthoSubPanel, cr++, 0);

        UiKit.addOptionRow(cornerRows, cr++, 0, cornerCustomRadio);
        customEndsSubPanel = new JPanel(new GridBagLayout());
        int cer = 0;
        String[] endModeNames = {
            t.get("endNormal", "At the vertex"),
            t.get("endExtra", "Object next to the vertex"),
            t.get("endDistance", "Move the end (cm)"),
            t.get("endFit", "Fit to the other strip"),
            t.get("endAdjacentToObjects", "Object next to those of the side")
        };
        globalStartEndCombo = createEndModeCombo(endModeNames);
        globalStartEndDistanceSpinner = UiKit.spinner(new SpinnerNumberModel(0.0, -10000.0, 10000.0, 1.0), 5);
        globalStartEndCmLabel = new JLabel("cm");
        globalStartEndInfoIcon = UiKit.infoIcon(t.get("endStartHelpTip",
                "At the vertex: starts at the vertex. Object next to the vertex: one more object just before the vertex. Move (+/\u2212): extends or shortens the start. Fit to the other strip: extends to cover the corner (> 180\u00b0) or shortens to avoid overlapping (< 180\u00b0). Object next to those of the side: one more object right before the first one."));
        globalEndEndCombo = createEndModeCombo(endModeNames);
        globalEndEndCombo.setSelectedItem(SurroundConfig.END_FIT);
        globalEndEndDistanceSpinner = UiKit.spinner(new SpinnerNumberModel(0.0, -10000.0, 10000.0, 1.0), 5);
        globalEndEndCmLabel = new JLabel("cm");
        globalEndEndInfoIcon = UiKit.infoIcon(t.get("endEndHelpTip",
                "At the vertex: ends at the vertex. Object next to the vertex: one more object just past the vertex. Move (+/\u2212): extends or shortens the end. Fit to the other strip: extends to cover the corner (> 180\u00b0) or shortens to avoid overlapping (< 180\u00b0). Object next to those of the side: one more object right after the last one."));

        UiKit.addOptionRow(customEndsSubPanel, cer++, 1,
                new JLabel(t.get("sidesStart", "Start:")), globalStartEndCombo,
                globalStartEndDistanceSpinner, globalStartEndCmLabel, globalStartEndInfoIcon);
        UiKit.addOptionRow(customEndsSubPanel, cer++, 1,
                new JLabel(t.get("sidesEnd", "End:")), globalEndEndCombo,
                globalEndEndDistanceSpinner, globalEndEndCmLabel, globalEndEndInfoIcon);

        presetCornersNoOverlapButton = new JButton(t.get("sidesPresetCorners", "Corners without overlap (start at the vertex, end fitted)"));
        presetCornersNoOverlapButton.addActionListener(e -> {
            globalStartEndCombo.setSelectedItem(SurroundConfig.END_NORMAL);
            globalEndEndCombo.setSelectedItem(SurroundConfig.END_FIT);
            updateDependentVisibility();
        });
        UiKit.addOptionRow(customEndsSubPanel, cer++, 1, presetCornersNoOverlapButton);

        extraObjectRespectObstaclesCheckBox = new JCheckBox(
                t.get("extraObjectRespectObstacles", "The extra object respects openings and collisions:"), true);
        extraObjectRespectRow = UiKit.addOptionRow(customEndsSubPanel, cer++, 1, extraObjectRespectObstaclesCheckBox);
        extraObjectRespectCornersCheckBox = new JCheckBox(
                t.get("extraObjectRespectCorners", "Without crossing the vertex to the other side (concave corners)"), true);
        extraObjectCornersRow = UiKit.addOptionRow(customEndsSubPanel, cer++, 2, extraObjectRespectCornersCheckBox);

        addSubPanel(cornerRows, customEndsSubPanel, cr++, 0);
        UiKit.addFiller(cornerRows);
        addSubPanel(this, cornerRows, row++, UiKit.INDENT);

        ActionListener stateUpdater = e -> {
            updateDependentVisibility();
            onCornerChanged.run();
        };
        for (AbstractButton b : new AbstractButton[]{
            enableGroupingCheckBox, groupAllRadio, groupPerSideRadio,
            dimProportionalRadio, dimSelectedRadio, dimWidthOnlyRadio, dimCheckD, dimCheckH,
            reverseDirectionCheckBox, symmetricBothEndsCheckBox,
            enablePieceMarginCheckBox,
            splitAtObstaclesCheckBox, respectWindowsCheckBox, gapHeightRangeCheckBox, enableGapFitToleranceCheckBox,
            shortSidesCheckBox,
            avoidCollisionsCheckBox, avoidNearbyFloorsCheckBox, avoidNewlyGeneratedCheckBox, enableCollisionFitToleranceCheckBox,
            floorHolesPassRadio, floorHolesNoPassRadio,
            enableVerticalStackingCheckBox, useCustomStackElevationCheckBox,
            enableCornerHandlingCheckBox, cornerAvoidOverlapRadio, cornerPlaceOrthogonalRadio, cornerCustomRadio,
            orthoOverlapRadio, orthoSeparateRadio, orthoFirstVertexRadio, orthoSecondVertexRadio, orthoMidVertexRadio,
            cornerTouchVertexCheckBox, cornerKeepSizeCheckBox, useCatalogCornerCheckBox,
            extraObjectRespectObstaclesCheckBox, extraObjectRespectCornersCheckBox
        }) {
            b.addActionListener(stateUpdater);
        }
        globalStartEndCombo.addActionListener(stateUpdater);
        globalEndEndCombo.addActionListener(stateUpdater);
        for (JSpinner sp : new JSpinner[]{distanceSpinner, pieceMarginSpinner, gapMinHeightSpinner, gapMaxHeightSpinner,
            gapFitToleranceSpinner, collisionFitToleranceSpinner, verticalStackCountSpinner, customStackElevationSpinner,
            cornerMarginSpinner, globalStartEndDistanceSpinner, globalEndEndDistanceSpinner}) {
            sp.addChangeListener(e -> onCornerChanged.run());
        }
        UiKit.addFiller(this);
        updateDependentVisibility();
    }

    private JComboBox<Integer> createEndModeCombo(final String[] names) {
        JComboBox<Integer> combo = new JComboBox<>(new Integer[]{
            SurroundConfig.END_NORMAL, SurroundConfig.END_EXTRA_OBJECT,
            SurroundConfig.END_DISTANCE, SurroundConfig.END_FIT, SurroundConfig.END_ADJACENT_TO_OBJECTS
        });
        combo.setRenderer(UiKit.renderer(Integer.class, v -> names[Math.max(0, Math.min(names.length - 1, v))], false));
        return combo;
    }

    private void addSubPanel(JPanel sub, int row) {
        addSubPanel(this, sub, row, 0);
    }

    private static void addSubPanel(JPanel parent, JPanel sub, int row, int indent) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = row;
        c.anchor = GridBagConstraints.LINE_START;
        c.insets = new Insets(0, indent, 0, 0);
        parent.add(sub, c);
    }

    private static void group(JRadioButton... radios) {
        ButtonGroup group = new ButtonGroup();
        for (JRadioButton radio : radios) {
            group.add(radio);
        }
    }

    void setSelectedType(int type) {
        this.currentType = type;
        if (!dimModeChosenByUser) {
            if (type == 6) {
                dimWidthOnlyRadio.setSelected(true);
            } else if (type == 3 || type == 4) {
                dimProportionalRadio.setSelected(true);
            }
        }
        enableCornerHandlingCheckBox.setText(type == 7
                ? texts.get("cornerHandlingType7", "Fit objects at corners (rotate on vertices)")
                : texts.get("enableCornerHandling", "Corner handling:"));
        updateDependentVisibility();
    }

    void setUniformState(boolean uniformSize, boolean uniformGaps) {
        if (this.uniformSizeActive != uniformSize) {
            this.uniformSizeActive = uniformSize;
            updateDependentVisibility();
        }
    }

    private void updateDependentVisibility() {
        boolean type7 = (currentType == 7);
        groupingSubPanel.setVisible(enableGroupingCheckBox.isSelected());

        boolean resizingType = (currentType == 3 || currentType == 4 || currentType == 6);
        dimModifySection.setVisible(resizingType);
        dimSelectedChecksRow.setVisible(resizingType && dimSelectedRadio.isSelected());

        // Symmetric ends only applies to Type 6 (for Type 5 it would duplicate Type 2)
        boolean showReverse = (currentType == 5 || currentType == 6 || currentType == 7);
        boolean showSymmetric = (currentType == 6);
        reverseDirectionCheckBox.setVisible(showReverse);
        symmetricBothEndsCheckBox.setVisible(showSymmetric);
        dirAndEndsSection.setVisible(showReverse || showSymmetric);

        boolean supportsPieceMargin = (currentType == 1 || currentType == 2 || currentType == 5);
        pieceMarginSection.setVisible(supportsPieceMargin);
        pieceMarginSubRow.setVisible(supportsPieceMargin && enablePieceMarginCheckBox.isSelected());

        boolean supportsFitTolerance = (currentType != 4 || uniformSizeActive);
        gapsSubPanel.setVisible(splitAtObstaclesCheckBox.isSelected() || respectWindowsCheckBox.isSelected());
        gapHeightRow.setVisible(gapHeightRangeCheckBox.isSelected());
        gapToleranceHeaderRow.setVisible(supportsFitTolerance);
        gapFitToleranceSubRow.setVisible(supportsFitTolerance && enableGapFitToleranceCheckBox.isSelected());

        avoidCollisionsSubPanel.setVisible(avoidCollisionsCheckBox.isSelected());
        collisionToleranceHeaderRow.setVisible(supportsFitTolerance);
        collisionFitToleranceSubRow.setVisible(supportsFitTolerance && enableCollisionFitToleranceCheckBox.isSelected());

        boolean cornersActive = !type7 && enableCornerHandlingCheckBox.isSelected();
        cornerRows.setVisible(cornersActive);
        if (cornersActive) {
            boolean perp = cornerPlaceOrthogonalRadio.isSelected();
            orthoSubPanel.setVisible(perp);
            setVisibleAll(perp && orthoSeparateRadio.isSelected(),
                    cornerMarginSpinner, cornerMarginUnitLabel, cornerMarginSignHint);
            cornerTouchVertexRow.setVisible(perp);
            cornerKeepSizeRow.setVisible(perp && (currentType == 3 || currentType == 4));
            catalogCombosRow.setVisible(perp && useCatalogCornerCheckBox.isSelected());

            boolean customEnds = cornerCustomRadio.isSelected();
            customEndsSubPanel.setVisible(customEnds);
            if (customEnds) {
                int sMode = comboInt(globalStartEndCombo, 0);
                int eMode = comboInt(globalEndEndCombo, 0);
                setVisibleAll(sMode == SurroundConfig.END_DISTANCE, globalStartEndDistanceSpinner, globalStartEndCmLabel);
                setVisibleAll(eMode == SurroundConfig.END_DISTANCE, globalEndEndDistanceSpinner, globalEndEndCmLabel);
                boolean hasExtraObj = isExtraObjectMode(sMode) || isExtraObjectMode(eMode);
                extraObjectRespectRow.setVisible(hasExtraObj);
                extraObjectCornersRow.setVisible(hasExtraObj && extraObjectRespectObstaclesCheckBox.isSelected());
            }
        }

        boolean stackEnabled = enableVerticalStackingCheckBox.isSelected();
        verticalStackingSubPanel.setVisible(stackEnabled);
        customStackElevationSubRow.setVisible(stackEnabled && useCustomStackElevationCheckBox.isSelected());
        randomPropsSubRow.setVisible(enableRandomPropsCheckBox.isSelected());
        gradualPropsSubRow.setVisible(enableGradualPropsCheckBox.isSelected());

        revalidate();
        repaint();
        if (getParent() != null) {
            getParent().revalidate();
            getParent().repaint();
        }
    }

    int orthoSeparation() {
        return orthoSeparateRadio.isSelected() ? SurroundConfig.ORTHO_SEPARATE
                : orthoFirstVertexRadio.isSelected() ? SurroundConfig.ORTHO_FIRST_VERTEX
                : orthoSecondVertexRadio.isSelected() ? SurroundConfig.ORTHO_SECOND_VERTEX
                : orthoMidVertexRadio.isSelected() ? SurroundConfig.ORTHO_MID_VERTEX
                : SurroundConfig.ORTHO_OVERLAP;
    }

    void fill(SurroundConfig cfg) {
        cfg.distanceOffset = spinnerDouble(distanceSpinner);
        cfg.groupingMode = !enableGroupingCheckBox.isSelected() ? SurroundConfig.GROUP_NONE
                : (groupPerSideRadio.isSelected() ? SurroundConfig.GROUP_PER_SIDE : SurroundConfig.GROUP_ALL);
        cfg.dimModifyMode = dimSelectedRadio.isSelected() ? SurroundConfig.DIM_SELECTED
                : dimWidthOnlyRadio.isSelected() ? SurroundConfig.DIM_WIDTH_ONLY
                : SurroundConfig.DIM_PROPORTIONAL;
        cfg.dimModifyD = dimCheckD.isSelected();
        cfg.dimModifyH = dimCheckH.isSelected();
        cfg.reverseDirection = (currentType == 5 || currentType == 6 || currentType == 7) && reverseDirectionCheckBox.isSelected();
        cfg.symmetricBothEnds = currentType == 6 && symmetricBothEndsCheckBox.isSelected();
        cfg.enablePieceMargin = (currentType == 1 || currentType == 2 || currentType == 5) && enablePieceMarginCheckBox.isSelected();
        cfg.pieceMargin = cfg.enablePieceMargin ? spinnerDouble(pieceMarginSpinner) : 0.0;

        boolean supportsFitTolerance = (currentType != 4 || uniformSizeActive);
        cfg.splitAtObstacles = splitAtObstaclesCheckBox.isSelected();
        cfg.respectWindows = respectWindowsCheckBox.isSelected();
        boolean anyGap = cfg.splitAtObstacles || cfg.respectWindows;
        cfg.gapHeightRange = anyGap && gapHeightRangeCheckBox.isSelected();
        cfg.gapMinHeight = spinnerDouble(gapMinHeightSpinner);
        cfg.gapMaxHeight = Math.max(cfg.gapMinHeight, spinnerDouble(gapMaxHeightSpinner));
        cfg.enableGapFitTolerance = anyGap && supportsFitTolerance && enableGapFitToleranceCheckBox.isSelected();
        cfg.gapFitTolerance = cfg.enableGapFitTolerance ? spinnerDouble(gapFitToleranceSpinner) : 0.0;
        cfg.shortSidesOneObject = shortSidesCheckBox.isSelected();
        cfg.avoidCollisions = avoidCollisionsCheckBox.isSelected();
        cfg.avoidNearbyFloors = cfg.avoidCollisions && hasFloors && avoidNearbyFloorsCheckBox.isSelected();
        cfg.avoidNewlyGenerated = cfg.avoidCollisions && avoidNewlyGeneratedCheckBox.isSelected();
        cfg.enableCollisionFitTolerance = cfg.avoidCollisions && supportsFitTolerance && enableCollisionFitToleranceCheckBox.isSelected();
        cfg.collisionFitTolerance = cfg.enableCollisionFitTolerance ? spinnerDouble(collisionFitToleranceSpinner) : 0.0;
        cfg.upperLevelFloorHolesMode = (hasUpperLevel && floorHolesNoPassRadio.isSelected())
                ? SurroundConfig.FLOOR_HOLES_NO_PASS : SurroundConfig.FLOOR_HOLES_PASS;

        cfg.enableVerticalStacking = enableVerticalStackingCheckBox.isSelected();
        cfg.verticalStackCount = ((Number) verticalStackCountSpinner.getValue()).intValue();
        cfg.useCustomStackElevation = useCustomStackElevationCheckBox.isSelected();
        cfg.customStackElevation = spinnerDouble(customStackElevationSpinner);

        cfg.enableRandomProps = enableRandomPropsCheckBox.isSelected() && randomSettings.hasAny();
        cfg.randomSettings = randomSettings.copy();
        cfg.enableGradualProps = enableGradualPropsCheckBox.isSelected() && gradualSettings.hasAnyGradual();
        cfg.gradualSettings = gradualSettings.copy();

        boolean type7 = (currentType == 7);
        boolean cornersChecked = enableCornerHandlingCheckBox.isSelected();
        cfg.customSideEnds = !type7 && cornersChecked && cornerCustomRadio.isSelected();
        cfg.enableCornerHandling = cornersChecked && (type7 || !cornerCustomRadio.isSelected());
        cfg.placeOrthogonalCornerPiece = !type7 && cfg.enableCornerHandling && cornerPlaceOrthogonalRadio.isSelected();
        cfg.orthoSeparation = orthoSeparation();
        cfg.cornerTouchVertex = cfg.placeOrthogonalCornerPiece && cornerTouchVertexCheckBox.isSelected();
        cfg.cornerKeepSize = cfg.placeOrthogonalCornerPiece && (currentType == 3 || currentType == 4)
                && cornerKeepSizeCheckBox.isSelected();
        cfg.cornerPieceMargin = (cfg.placeOrthogonalCornerPiece && cfg.orthoSeparation == SurroundConfig.ORTHO_SEPARATE)
                ? spinnerDouble(cornerMarginSpinner) : 0.0;
        cfg.customCornerPiece = (cfg.placeOrthogonalCornerPiece && useCatalogCornerCheckBox.isSelected())
                ? customCornerPiece : null;
        cfg.globalStartEndMode = comboInt(globalStartEndCombo, SurroundConfig.END_NORMAL);
        cfg.globalStartEndDistance = spinnerDouble(globalStartEndDistanceSpinner);
        cfg.globalEndEndMode = comboInt(globalEndEndCombo, SurroundConfig.END_FIT);
        cfg.globalEndEndDistance = spinnerDouble(globalEndEndDistanceSpinner);
        cfg.extraObjectRespectObstacles = extraObjectRespectObstaclesCheckBox.isSelected();
        cfg.extraObjectRespectCorners = cfg.extraObjectRespectObstacles && extraObjectRespectCornersCheckBox.isSelected();
    }

    private static double spinnerDouble(JSpinner spinner) {
        return ((Number) spinner.getValue()).doubleValue();
    }

    private static int comboInt(JComboBox<Integer> combo, int fallback) {
        return combo.getSelectedItem() instanceof Integer ? (Integer) combo.getSelectedItem() : fallback;
    }

    private static boolean isExtraObjectMode(int mode) {
        return mode == SurroundConfig.END_EXTRA_OBJECT || mode == SurroundConfig.END_ADJACENT_TO_OBJECTS;
    }

    private static void setVisibleAll(boolean visible, Component... components) {
        for (Component c : components) {
            c.setVisible(visible);
        }
    }

    void setOnPickCornerObject(Runnable callback) {
        this.onPickCornerObject = callback;
    }

    void setCustomCornerPiece(HomePieceOfFurniture piece) {
        this.customCornerPiece = piece;
        if (piece != null) {
            String name = piece.getName() != null && !piece.getName().trim().isEmpty() ? piece.getName().trim() : "?";
            cornerObjectLabel.setText(String.format("%s (%.0f\u00d7%.0f cm)", name, piece.getWidth(), piece.getDepth()));
        } else {
            cornerObjectLabel.setText(texts.get("cornerObjectNone", "None selected"));
        }
    }

    HomePieceOfFurniture getCustomCornerPiece() {
        return customCornerPiece;
    }

    void setOnConfigureRandomProps(Runnable callback) {
        this.onConfigureRandomProps = callback;
        updateRandomPropsSummary();
    }

    void setOnConfigureGradualProps(Runnable callback) {
        this.onConfigureGradualProps = callback;
        updateGradualPropsSummary();
    }

    void updateRandomPropsSummary() {
        int active = randomSettings.activeFeatureCount();
        String text = activeCount(active);
        if (randomSettings.randomObjectSubset && active > 0) {
            text += String.format(" (%.0f%%)", randomSettings.objectProbability);
        }
        randomPropsSummaryLabel.setText(text);
    }

    void updateGradualPropsSummary() {
        gradualPropsSummaryLabel.setText(activeCount(gradualSettings.activeGradualCount()));
    }

    private String activeCount(int active) {
        return active > 0
                ? MessageFormat.format(texts.get("randomActiveCount", "{0} active"), active)
                : texts.get("randomNoneActive", "None active");
    }
}
