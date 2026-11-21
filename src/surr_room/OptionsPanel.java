/*
 * Copyright (c) 2024-2026 Oliver Mart\u00ednez (Ollie_Jess)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package surr_room;

import com.eteks.sweethome3d.model.CatalogPieceOfFurniture;
import com.eteks.sweethome3d.model.FurnitureCatalog;
import com.eteks.sweethome3d.model.FurnitureCategory;
import com.eteks.sweethome3d.model.UserPreferences;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ActionListener;
import java.text.MessageFormat;
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
 * Options on the right: distance to the edge, grouping, dimensions that change (types 3, 4 and 6),
 * direction, margins, door and window openings, collisions, stacking, random and gradual properties,
 * corners and side ends. Options that don't apply to the current type or choices are hidden.
 */
class OptionsPanel extends JPanel {

    /** Spinner adjusting perpendicular distance offset from the perimeter edge in cm. */
    final JSpinner distanceSpinner;

    // 1. Model grouping
    /** Checkbox toggling whether generated furniture pieces are grouped together. */
    final JCheckBox enableGroupingCheckBox;
    /** Sub-panel hosting grouping mode radio buttons. */
    final JPanel groupingSubPanel;
    /** Group all generated furniture across all sides into a single {@link com.eteks.sweethome3d.model.HomeFurnitureGroup}. */
    final JRadioButton groupAllRadio;
    /** Group generated furniture separately per perimeter side. */
    final JRadioButton groupPerSideRadio;

    // 2. Dimension modification (Types 3, 4, 6)
    /** Section container for dimension modification controls. */
    final JPanel dimModifySection;
    /** Proportional mode: scales width, depth, and height equally. */
    final JRadioButton dimProportionalRadio;
    /** Selected dimensions mode: width scales automatically, while depth and height are selectively enabled. */
    final JRadioButton dimSelectedRadio;
    /** Width-only mode: only piece width is scaled to cover the side. */
    final JRadioButton dimWidthOnlyRadio;
    /** Row containing checkboxes for depth and height modification. */
    final JPanel dimSelectedChecksRow;
    /** Checkboxes for allowing depth and height modification. */
    final JCheckBox dimCheckD, dimCheckH;

    // 3. Direction & symmetric ends (Types 5, 6, 7)
    /** Section container for direction reversal and symmetric end options. */
    final JPanel dirAndEndsSection;
    /** Reverses traversal direction from end vertex to start vertex. */
    final JCheckBox reverseDirectionCheckBox;
    /** Balances piece spacing symmetrically at both ends of each side (Type 6). */
    final JCheckBox symmetricBothEndsCheckBox;

    // 4. Margin around each piece (negative = overlap)
    /** Section container for inter-piece margin settings. */
    final JPanel pieceMarginSection;
    /** Checkbox enabling margin between consecutive pieces. */
    final JCheckBox enablePieceMarginCheckBox;
    /** Row containing the piece margin spinner and unit label. */
    final JPanel pieceMarginSubRow;
    /** Spinner specifying piece margin in cm (positive = gap, negative = overlap). */
    final JSpinner pieceMarginSpinner;

    // 5. Gaps, collisions, upper-level floor holes, and excluded sub-stretches
    /** Checkbox enabling side splitting around door and window openings. */
    final JCheckBox splitAtObstaclesCheckBox;
    /** Sub-panel hosting obstacle opening options. */
    final JPanel gapsSubPanel;
    /** Include windows as splitting obstacles (default is doors only). */
    final JCheckBox respectWindowsCheckBox;
    /** Restrict obstacle detection to openings within a vertical height range. */
    final JCheckBox gapHeightRangeCheckBox;
    /** Row holding min and max height spinners for obstacle filtering. */
    final JPanel gapHeightRow;
    /** Min and max height spinners for obstacle detection in cm. */
    final JSpinner gapMinHeightSpinner, gapMaxHeightSpinner;
    /** Header row for gap fit tolerance. */
    final JPanel gapToleranceHeaderRow;
    /** Checkbox enabling fit tolerance when placing objects near openings. */
    final JCheckBox enableGapFitToleranceCheckBox;
    /** Sub-row holding the gap fit tolerance spinner. */
    final JPanel gapFitToleranceSubRow;
    /** Maximum allowed overlap distance with an opening in cm. */
    final JSpinner gapFitToleranceSpinner;
    /** Checkbox forcing placement of at least one object on sides shorter than the piece width. */
    final JCheckBox shortSidesCheckBox;
    /** Checkbox enabling collision avoidance against furniture, walls, and floors. */
    final JCheckBox avoidCollisionsCheckBox;
    /** Sub-panel hosting collision avoidance options. */
    final JPanel avoidCollisionsSubPanel;
    /** Also check collision against adjacent floor surfaces. */
    final JCheckBox avoidNearbyFloorsCheckBox;
    /** Also avoid colliding with newly generated objects on other sides or rooms. */
    final JCheckBox avoidNewlyGeneratedCheckBox;
    /** Header row for collision fit tolerance. */
    final JPanel collisionToleranceHeaderRow;
    /** Checkbox enabling fit tolerance for collision avoidance. */
    final JCheckBox enableCollisionFitToleranceCheckBox;
    /** Sub-row holding the collision fit tolerance spinner. */
    final JPanel collisionFitToleranceSubRow;
    /** Maximum allowed overlap tolerance when avoiding collisions in cm. */
    final JSpinner collisionFitToleranceSpinner;
    /** Section containing floor hole handling on upper levels. */
    final JPanel upperLevelHolesSection;
    /** Radio button allowing pieces to pass over floor openings. */
    final JRadioButton floorHolesPassRadio;
    /** Radio button preventing pieces from spanning unsupported floor openings. */
    final JRadioButton floorHolesNoPassRadio;

    // 6. Vertical stacking
    /** Checkbox enabling multi-tier vertical stacking. */
    final JCheckBox enableVerticalStackingCheckBox;
    /** Sub-panel hosting vertical stacking controls. */
    final JPanel verticalStackingSubPanel;
    /** Spinner specifying number of stacked vertical tiers. */
    final JSpinner verticalStackCountSpinner;
    /** Checkbox enabling custom vertical elevation stride between tiers. */
    final JCheckBox useCustomStackElevationCheckBox;
    /** Sub-row holding custom vertical elevation spinner. */
    final JPanel customStackElevationSubRow;
    /** Spinner specifying custom elevation stride in cm. */
    final JSpinner customStackElevationSpinner;

    // 7. Random & Gradual non-shape-affecting properties
    /** Checkbox enabling stochastic variation of visual and physical properties. */
    final JCheckBox enableRandomPropsCheckBox;
    /** Sub-row holding random configuration button and summary label. */
    final JPanel randomPropsSubRow;
    /** Button opening the random properties configuration dialog. */
    final JButton configureRandomPropsButton;
    /** Label displaying active random property count summary. */
    final JLabel randomPropsSummaryLabel;
    /** Active stochastic variation settings model. */
    SurroundRandomSettings randomSettings = new SurroundRandomSettings();
    /** Callback invoked to launch random properties dialog. */
    private Runnable onConfigureRandomProps;

    /** Checkbox enabling gradual linear/progressive variation of properties. */
    final JCheckBox enableGradualPropsCheckBox;
    /** Sub-row holding gradual configuration button and summary label. */
    final JPanel gradualPropsSubRow;
    /** Button opening the gradual properties configuration dialog. */
    final JButton configureGradualPropsButton;
    /** Label displaying active gradual property count summary. */
    final JLabel gradualPropsSummaryLabel;
    /** Active gradual variation settings model. */
    SurroundRandomSettings gradualSettings = new SurroundRandomSettings();
    /** Callback invoked to launch gradual properties dialog. */
    private Runnable onConfigureGradualProps;

    // 8. Corners & Global Side Ends
    /** Row holding corner handling master checkbox. */
    final JPanel cornerHeaderRow;
    /** Checkbox enabling corner treatment. */
    final JCheckBox enableCornerHandlingCheckBox;
    /** Sub-panel hosting corner radio choices and sub-panels. */
    final JPanel cornerRows;
    /** Radio button leaving corners empty (avoiding overlapping pieces). */
    final JRadioButton cornerAvoidOverlapRadio;
    /** Radio button placing a dedicated orthogonal corner piece at vertices. */
    final JRadioButton cornerPlaceOrthogonalRadio;
    /** Radio button configuring custom start and end shifts for side strips. */
    final JRadioButton cornerCustomRadio;
    /** Sub-panel hosting orthogonal corner piece options. */
    final JPanel orthoSubPanel;
    /** Orthogonal corner pieces placed without adjustment (may overlap sides). */
    final JRadioButton orthoOverlapRadio;
    /** Orthogonal corner pieces placed with dedicated separation margin. */
    final JRadioButton orthoSeparateRadio;
    /** Spinner adjusting corner piece separation margin in cm. */
    final JSpinner cornerMarginSpinner;
    /** Unit label for corner margin spinner. */
    final JLabel cornerMarginUnitLabel;
    /** Info badge for corner margin spinner. */
    final JLabel cornerMarginInfoIcon;
    /** Side pieces extend up to the 1st vertex of the corner piece. */
    final JRadioButton orthoFirstVertexRadio;
    /** Side pieces extend up to the 2nd vertex of the corner piece. */
    final JRadioButton orthoSecondVertexRadio;
    /** Side pieces extend up to the midpoint between 1st and 2nd vertices of the corner piece. */
    final JRadioButton orthoMidVertexRadio;
    /** Row holding corner touch vertex checkbox. */
    final JPanel cornerTouchVertexRow;
    /** Place corner piece directly touching vertex on acute/obtuse corners (&lt; 180&deg;). */
    final JCheckBox cornerTouchVertexCheckBox;
    /** Row holding corner piece size retention checkbox. */
    final JPanel cornerKeepSizeRow;
    /** Keep original corner piece dimensions rather than matching side object size (Types 3 &amp; 4). */
    final JCheckBox cornerKeepSizeCheckBox;
    /** Checkbox allowing a custom piece from the furniture catalog to be used as corner piece. */
    final JCheckBox useCatalogCornerCheckBox;
    /** Row holding catalog category and piece dropdowns. */
    final JPanel catalogCombosRow;
    /** Dropdown selecting catalog furniture category for corner pieces. */
    final JComboBox<FurnitureCategory> catalogCategoryCombo;
    /** Dropdown selecting specific catalog furniture piece for corner pieces. */
    final JComboBox<CatalogPieceOfFurniture> catalogPieceCombo;

    // Global Side Ends sub-panel
    /** Sub-panel hosting global side start and end termination options. */
    final JPanel customEndsSubPanel;
    /** Dropdown selecting start termination mode (normal, extra object, distance shift, fit). */
    final JComboBox<Integer> globalStartEndCombo;
    /** Spinner for start shift distance in cm. */
    final JSpinner globalStartEndDistanceSpinner;
    /** Unit label for start shift distance. */
    final JLabel globalStartEndCmLabel;
    /** Info badge for start termination mode. */
    final JLabel globalStartEndInfoIcon;
    /** Dropdown selecting end termination mode (normal, extra object, distance shift, fit). */
    final JComboBox<Integer> globalEndEndCombo;
    /** Spinner for end shift distance in cm. */
    final JSpinner globalEndEndDistanceSpinner;
    /** Unit label for end shift distance. */
    final JLabel globalEndEndCmLabel;
    /** Info badge for end termination mode. */
    final JLabel globalEndEndInfoIcon;
    /** Quick preset button configuring non-overlapping corner ends (Start: Normal, End: Fit). */
    final JButton presetCornersNoOverlapButton;
    /** Row holding obstacle respect toggle for extra end objects. */
    final JPanel extraObjectRespectRow;
    /** Checkbox ensuring extra corner objects respect doors, windows, and collisions. */
    final JCheckBox extraObjectRespectObstaclesCheckBox;
    /** Row holding corner boundary respect toggle for extra end objects. */
    final JPanel extraObjectCornersRow;
    /** Checkbox ensuring extra corner objects do not cross into adjacent sides at concave corners. */
    final JCheckBox extraObjectRespectCornersCheckBox;

    /** Localized text bundle. */
    private final UiKit.Texts texts;
    /** Flag indicating whether target rooms/walls exist. */
    private final boolean hasTargets;
    /** Flag indicating whether floor polygons are present. */
    private final boolean hasFloors;
    /** Flag indicating whether targets belong to an upper level with potential floor holes. */
    private final boolean hasUpperLevel;
    /** Currently selected surrounding type index (1 to 7). */
    private int currentType = 1;
    /** Flag indicating whether uniform size mode is active. */
    private boolean uniformSizeActive = false;
    /** Flag tracking whether the user manually picked a dimension modification radio button. */
    private boolean dimModeChosenByUser = false;

    /** @param onCornerChanged             called when an option that changes the layout changes */
    OptionsPanel(UiKit.Texts t, UserPreferences userPreferences, boolean hasTargets, boolean hasFloors,
            boolean hasUpperLevel, Runnable onCornerChanged) {
        super(new GridBagLayout());
        this.texts = t;
        this.hasTargets = hasTargets;
        this.hasFloors = hasFloors;
        this.hasUpperLevel = hasUpperLevel;
        int row = 0;

        // ---- Distance from the edge ----
        UiKit.addOptionRow(this, row++, 0, UiKit.subtitle(t.get("groupingTitle", "Position, grouping and dimensions")));
        distanceSpinner = UiKit.spinner(new SpinnerNumberModel(0.0, -10000.0, 10000.0, 0.5), 6);
        UiKit.addOptionRow(this, row++, 1,
                new JLabel(t.get("t7EdgeDistance", "Distance from the edge:")),
                distanceSpinner, new JLabel("cm"),
                UiKit.infoIcon(t.get("t7EdgeHint", "0: back on the edge. Positive: moves away. Negative: moves inward.")));

        // ---- 1. Model grouping ----
        enableGroupingCheckBox = new JCheckBox(t.get("enableGrouping", "Group generated models:"), false);
        UiKit.addOptionRow(this, row++, 1, enableGroupingCheckBox);

        groupingSubPanel = new JPanel(new GridBagLayout());
        groupAllRadio = new JRadioButton(t.get("groupAll", "Into a single group"), true);
        groupPerSideRadio = new JRadioButton(t.get("groupPerSide", "Per side"));
        group(groupAllRadio, groupPerSideRadio);
        UiKit.addOptionRow(groupingSubPanel, 0, 2, groupAllRadio);
        UiKit.addOptionRow(groupingSubPanel, 1, 2, groupPerSideRadio);
        addSubPanel(groupingSubPanel, row++);

        // ---- 2. Dimension modification (Types 3, 4, 6) ----
        dimModifySection = new JPanel(new GridBagLayout());
        int dr = 0;
        UiKit.addOptionRow(dimModifySection, dr++, 1, new JLabel(t.get("dimModifyTitle", "Dimensions that change:")));
        dimProportionalRadio = new JRadioButton(t.get("dimProportional", "Proportional (all)"), true);
        dimSelectedRadio = new JRadioButton(t.get("dimSelectedOnly", "Only the selected ones:"));
        dimWidthOnlyRadio = new JRadioButton(t.get("dimWidthOnly", "Width only"));
        group(dimProportionalRadio, dimSelectedRadio, dimWidthOnlyRadio);
        UiKit.addOptionRow(dimModifySection, dr++, 2, dimProportionalRadio);
        UiKit.addOptionRow(dimModifySection, dr++, 2, dimSelectedRadio);

        dimCheckD = new JCheckBox(t.get("t7Depth", "Depth"), true);
        dimCheckH = new JCheckBox(t.get("t7Height", "Height"), false);
        dimSelectedChecksRow = UiKit.addOptionRow(dimModifySection, dr++, 3,
                dimCheckD, Box.createHorizontalStrut(6), dimCheckH,
                UiKit.infoIcon(t.get("dimWidthAlwaysTip", "The width always changes so that the objects cover the side.")));
        UiKit.addOptionRow(dimModifySection, dr++, 2, dimWidthOnlyRadio);
        addSubPanel(dimModifySection, row++);
        for (JRadioButton radio : new JRadioButton[]{dimProportionalRadio, dimSelectedRadio, dimWidthOnlyRadio}) {
            radio.addActionListener(e -> dimModeChosenByUser = true);
        }

        // ---- 3. Direction (Types 5, 6, 7) and symmetric ends (Type 6 only; excluded for Type 5 as it would duplicate Type 2) ----
        dirAndEndsSection = new JPanel(new GridBagLayout());
        int er = 0;
        reverseDirectionCheckBox = new JCheckBox(t.get("reverseDirection", "Reverse the direction (from end to start)"), false);
        symmetricBothEndsCheckBox = new JCheckBox(t.get("symmetricBothEnds", "Adjust both ends of each side"), false);
        UiKit.addOptionRow(dirAndEndsSection, er++, 1, reverseDirectionCheckBox);
        UiKit.addOptionRow(dirAndEndsSection, er++, 1, symmetricBothEndsCheckBox);
        addSubPanel(dirAndEndsSection, row++);

        // ---- 4. Margin around each piece (negative = overlap) ----
        pieceMarginSection = new JPanel(new GridBagLayout());
        enablePieceMarginCheckBox = new JCheckBox(t.get("enablePieceMargin", "Leave margin around the piece:"), false);
        pieceMarginSpinner = UiKit.spinner(new SpinnerNumberModel(2.0, -5000.0, 5000.0, 0.5), 5);
        UiKit.addOptionRow(pieceMarginSection, 0, 1, enablePieceMarginCheckBox,
                UiKit.infoIcon(t.get("pieceMarginTip", "Positive: leaves space around the piece. Negative: overlaps by that distance.")));
        pieceMarginSubRow = UiKit.addOptionRow(pieceMarginSection, 1, 2,
                new JLabel(t.get("pieceMarginValue", "Margin:")), pieceMarginSpinner, new JLabel("cm"));
        addSubPanel(pieceMarginSection, row++);
        UiKit.addSpacerRow(this, row++);

        // ---- 5. Gaps, collisions and excluded sub-stretches ----
        UiKit.addOptionRow(this, row++, 0, UiKit.subtitle(t.get("sidesAndObstaclesTitle", "Openings and collisions")));

        splitAtObstaclesCheckBox = new JCheckBox(t.get("splitAtObstacles", "Respect door and window openings:"), true);
        UiKit.addOptionRow(this, row++, 1, splitAtObstaclesCheckBox,
                UiKit.infoIcon(t.get("splitAtObstaclesTip", "Each free stretch is filled separately. Furniture is handled by \"Avoid collisions\".")));

        gapsSubPanel = new JPanel(new GridBagLayout());
        respectWindowsCheckBox = new JCheckBox(t.get("respectWindows", "Windows too"), false);
        UiKit.addOptionRow(gapsSubPanel, 0, 2, respectWindowsCheckBox,
                UiKit.infoIcon(t.get("respectWindowsTip", "Unchecked: doors only. A window starts above the floor; a door starts on it.")));
        gapHeightRangeCheckBox = new JCheckBox(t.get("gapHeightRange", "Only openings between these heights:"), false);
        UiKit.addOptionRow(gapsSubPanel, 1, 2, gapHeightRangeCheckBox,
                UiKit.infoIcon(t.get("gapHeightRangeTip", "By default, an opening counts where it crosses the height of the objects.")));
        gapMinHeightSpinner = UiKit.spinner(new SpinnerNumberModel(0.0, -1000.0, 10000.0, 1.0), 5);
        gapMaxHeightSpinner = UiKit.spinner(new SpinnerNumberModel(250.0, -1000.0, 10000.0, 1.0), 5);
        gapHeightRow = UiKit.addOptionRow(gapsSubPanel, 2, 3, gapMinHeightSpinner,
                new JLabel(t.get("gapHeightTo", "to")), gapMaxHeightSpinner, new JLabel("cm"));
        enableGapFitToleranceCheckBox = new JCheckBox(t.get("enableGapFitTolerance", "Tolerance when not fitting completely:"), false);
        gapFitToleranceSpinner = UiKit.spinner(new SpinnerNumberModel(5.0, 0.5, 500.0, 0.5), 5);
        gapToleranceHeaderRow = UiKit.addOptionRow(gapsSubPanel, 3, 2, enableGapFitToleranceCheckBox,
                UiKit.infoIcon(t.get("gapFitToleranceTip",
                        "Allows placing objects in free spaces where they fall short of fitting by up to this distance, permitting that overlap laterally, in depth or in height with the opening.")));
        gapFitToleranceSubRow = UiKit.addOptionRow(gapsSubPanel, 4, 3,
                new JLabel(t.get("fitToleranceValue", "Max.:")), gapFitToleranceSpinner, new JLabel("cm"));
        addSubPanel(gapsSubPanel, row++);

        shortSidesCheckBox = new JCheckBox(t.get("shortSidesOneObject", "Place one object on sides shorter than it, even if it sticks out"), false);
        UiKit.addOptionRow(this, row++, 1, shortSidesCheckBox);

        avoidCollisionsCheckBox = new JCheckBox(t.get("avoidCollisions", "Avoid collisions with furniture and walls:"), false);
        UiKit.addOptionRow(this, row++, 1, avoidCollisionsCheckBox,
                UiKit.infoIcon(t.get("avoidCollisionsTip", "Sides interrupted by a piece of furniture are filled in stretches.")));

        avoidCollisionsSubPanel = new JPanel(new GridBagLayout());
        int acr = 0;
        avoidNearbyFloorsCheckBox = new JCheckBox(t.get("avoidNearbyFloors", "Also avoid collisions with other nearby floors"), false);
        if (hasFloors) {
            UiKit.addOptionRow(avoidCollisionsSubPanel, acr++, 2, avoidNearbyFloorsCheckBox);
        }
        avoidNewlyGeneratedCheckBox = new JCheckBox(t.get("avoidNewlyGenerated", "Include the objects being placed (other sides and rooms)"), false);
        UiKit.addOptionRow(avoidCollisionsSubPanel, acr++, 2, avoidNewlyGeneratedCheckBox,
                UiKit.infoIcon(t.get("avoidNewlyGeneratedTip",
                        "Only avoids the objects placed on other sides or other selected surfaces (never those of the same side or its corner pieces).")));
        enableCollisionFitToleranceCheckBox = new JCheckBox(t.get("enableCollisionFitTolerance", "Tolerance when not fitting completely:"), false);
        collisionFitToleranceSpinner = UiKit.spinner(new SpinnerNumberModel(5.0, 0.5, 500.0, 0.5), 5);
        collisionToleranceHeaderRow = UiKit.addOptionRow(avoidCollisionsSubPanel, acr++, 2, enableCollisionFitToleranceCheckBox,
                UiKit.infoIcon(t.get("collisionFitToleranceTip",
                        "Allows placing objects in free spaces where they do not completely fit by up to this tolerance, permitting that overlap laterally, in depth or in height with furniture, walls or floors.")));
        collisionFitToleranceSubRow = UiKit.addOptionRow(avoidCollisionsSubPanel, acr++, 3,
                new JLabel(t.get("fitToleranceValue", "Max.:")), collisionFitToleranceSpinner, new JLabel("cm"));
        addSubPanel(avoidCollisionsSubPanel, row++);

        // Upper level floor openings
        upperLevelHolesSection = new JPanel(new GridBagLayout());
        int ulr = 0;
        UiKit.addOptionRow(upperLevelHolesSection, ulr++, 1, new JLabel(t.get("upperLevelHolesTitle", "Floor openings (upper levels):")));
        floorHolesPassRadio = new JRadioButton(t.get("floorHolesPass", "Pass over floor holes"), true);
        floorHolesNoPassRadio = new JRadioButton(t.get("floorHolesNoPass", "Do not cross the openings (warns if there is no floor below)"));
        group(floorHolesPassRadio, floorHolesNoPassRadio);
        UiKit.addOptionRow(upperLevelHolesSection, ulr++, 2, floorHolesPassRadio);
        UiKit.addOptionRow(upperLevelHolesSection, ulr++, 2, floorHolesNoPassRadio);
        upperLevelHolesSection.setVisible(hasUpperLevel);
        addSubPanel(upperLevelHolesSection, row++);


        // ---- 6. Vertical stacking ----
        enableVerticalStackingCheckBox = new JCheckBox(t.get("enableVerticalStacking", "Stack vertically:"), false);
        UiKit.addOptionRow(this, row++, 0, enableVerticalStackingCheckBox);

        verticalStackingSubPanel = new JPanel(new GridBagLayout());
        JLabel stackTimesLabel = new JLabel(t.get("verticalStackTimes", "Levels:"));
        verticalStackCountSpinner = UiKit.spinner(new SpinnerNumberModel(1, 1, 500, 1), 4);
        stackTimesLabel.setLabelFor(verticalStackCountSpinner);
        UiKit.addOptionRow(verticalStackingSubPanel, 0, 1, stackTimesLabel, verticalStackCountSpinner);
        useCustomStackElevationCheckBox = new JCheckBox(
                t.get("useCustomStackElevation", "Custom vertical spacing:"), false);
        customStackElevationSpinner = UiKit.spinner(new SpinnerNumberModel(100.0, -10000.0, 10000.0, 1.0), 6);
        UiKit.addOptionRow(verticalStackingSubPanel, 1, 1, useCustomStackElevationCheckBox);
        customStackElevationSubRow = UiKit.addOptionRow(verticalStackingSubPanel, 2, 2, customStackElevationSpinner, new JLabel("cm"));
        addSubPanel(verticalStackingSubPanel, row++);
        UiKit.addSpacerRow(this, row++);

        // ---- 7. Random & Gradual properties ----
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
        UiKit.addOptionRow(this, row++, 0, enableRandomPropsCheckBox,
                UiKit.infoIcon(t.get("enableRandomPropsTip", "Randomly varies color/texture, shininess, light, elevation, height, tilt or mirror depending on active settings.")));
        randomPropsSubRow = UiKit.addOptionRow(this, row++, 1, configureRandomPropsButton,
                Box.createHorizontalStrut(8), randomPropsSummaryLabel);

        enableGradualPropsCheckBox = new JCheckBox(t.get("enableGradualProps", "Gradual property change:"), false);
        configureGradualPropsButton = new JButton(t.get("configureGradualPropsButton", "Configure\u2026"));
        gradualPropsSummaryLabel = UiKit.hint("");
        enableGradualPropsCheckBox.addActionListener(e -> {
            if (enableGradualPropsCheckBox.isSelected() && !gradualSettings.hasAnyGradual()) {
                if (!Boolean.TRUE.equals(randomSettings.enabled.get("look"))) {
                    gradualSettings.enabled.put("look", true);
                } else {
                    gradualSettings.enabled.put("shininess", true);
                }
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
        UiKit.addOptionRow(this, row++, 0, enableGradualPropsCheckBox,
                UiKit.infoIcon(t.get("enableGradualPropsTip", "Applies a gradual progression (linear, back-and-forth or fixed step) to chosen properties along the outline, per side or per stacked level.")));
        gradualPropsSubRow = UiKit.addOptionRow(this, row++, 1, configureGradualPropsButton,
                Box.createHorizontalStrut(8), gradualPropsSummaryLabel);
        UiKit.addSpacerRow(this, row++);

        // ---- 8. Corners & Global Side Ends ----
        enableCornerHandlingCheckBox = new JCheckBox(t.get("enableCornerHandling", "Corner handling:"), false);
        cornerHeaderRow = UiKit.addOptionRow(this, row++, 0, enableCornerHandlingCheckBox,
                UiKit.infoIcon(t.get("cornerHandlingTip",
                        "Types 1 to 6: configures empty corners or perpendicular pieces. Type 7: rotates and shifts any object landing on a vertex to touch the separation margins of both sides.")));

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
        cornerMarginInfoIcon = UiKit.infoIcon(t.get("pieceMarginTip", "Positive: leaves space around the piece. Negative: overlaps by that distance."));
        orthoFirstVertexRadio = new JRadioButton(t.get("orthoFirstVertex", "Up to 1st vertex of the piece"));
        orthoSecondVertexRadio = new JRadioButton(t.get("orthoSecondVertex", "Up to 2nd vertex of the piece"));
        orthoMidVertexRadio = new JRadioButton(t.get("orthoMidVertex", "Up to midpoint between 1st and 2nd vertex"));
        group(orthoOverlapRadio, orthoSeparateRadio, orthoFirstVertexRadio, orthoSecondVertexRadio, orthoMidVertexRadio);
        UiKit.addOptionRow(orthoSubPanel, or++, 1, orthoOverlapRadio);
        UiKit.addOptionRow(orthoSubPanel, or++, 1, orthoSeparateRadio, cornerMarginSpinner, cornerMarginUnitLabel, cornerMarginInfoIcon);
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
        cornerKeepSizeCheckBox = new JCheckBox(t.get("cornerKeepSize", "Keep the size of the corner piece"), false);
        cornerKeepSizeRow = UiKit.addOptionRow(orthoSubPanel, or++, 1, cornerKeepSizeCheckBox,
                UiKit.infoIcon(t.get("cornerKeepSizeTip",
                        "Types 3 and 4: the corner piece isn't resized with the objects of its side.")));

        // Custom catalog piece for corners
        useCatalogCornerCheckBox = new JCheckBox(t.get("useCatalogCorner", "Use a catalog piece for the corners:"), false);
        catalogCategoryCombo = new JComboBox<>();
        catalogPieceCombo = new JComboBox<>();
        catalogCategoryCombo.setPreferredSize(new Dimension(170, catalogCategoryCombo.getPreferredSize().height));
        catalogPieceCombo.setPreferredSize(new Dimension(240, catalogPieceCombo.getPreferredSize().height));
        UiKit.addOptionRow(orthoSubPanel, or++, 1, useCatalogCornerCheckBox);
        catalogCombosRow = UiKit.addOptionRow(orthoSubPanel, or++, 2, catalogCategoryCombo, Box.createHorizontalStrut(6), catalogPieceCombo);

        addSubPanel(cornerRows, orthoSubPanel, cr++, 0);

        // Global Side Ends sub-panel
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
        globalStartEndCombo = createEndModeCombo(endModeNames, true);
        globalStartEndDistanceSpinner = UiKit.spinner(new SpinnerNumberModel(0.0, -10000.0, 10000.0, 1.0), 5);
        globalStartEndCmLabel = new JLabel("cm");
        globalStartEndInfoIcon = UiKit.infoIcon(t.get("endStartHelpTip",
                "At the vertex: starts at the vertex. Object next to the vertex: one more object just before the vertex. Move (+/\u2212): extends or shortens the start. Fit to the other strip: extends to cover the corner (> 180\u00b0) or shortens to avoid overlapping (< 180\u00b0). Object next to those of the side: one more object right before the first one."));

        globalEndEndCombo = createEndModeCombo(endModeNames, false);
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
        extraObjectRespectRow = UiKit.addOptionRow(customEndsSubPanel, cer++, 1, extraObjectRespectObstaclesCheckBox,
                UiKit.infoIcon(t.get("extraObjectRespectObstaclesTip",
                        "The extra object isn't placed over doors, windows or excluded stretches, nor colliding with what collision avoidance checks (the objects of the other sides only when it includes the generated objects).")));
        extraObjectRespectCornersCheckBox = new JCheckBox(
                t.get("extraObjectRespectCorners", "Without getting into the other sides at the corners"), true);
        extraObjectCornersRow = UiKit.addOptionRow(customEndsSubPanel, cer++, 2, extraObjectRespectCornersCheckBox,
                UiKit.infoIcon(t.get("extraObjectRespectCornersTip",
                        "The extra object isn't placed where it would get past the vertex into the wall or floor of the other side, as at concave corners.")));

        addSubPanel(cornerRows, customEndsSubPanel, cr++, 0);
        UiKit.addFiller(cornerRows);
        addSubPanel(this, cornerRows, row++, UiKit.INDENT);

        // Populate catalog dropdowns
        catalogCategoryCombo.setRenderer(UiKit.renderer(FurnitureCategory.class, FurnitureCategory::getName, false));
        catalogPieceCombo.setRenderer(UiKit.renderer(CatalogPieceOfFurniture.class,
                piece -> String.format("%s (%.0fx%.0f cm)", piece.getName(), piece.getWidth(), piece.getDepth()), true));
        if (userPreferences != null && userPreferences.getFurnitureCatalog() != null) {
            FurnitureCatalog catalog = userPreferences.getFurnitureCatalog();
            for (FurnitureCategory category : catalog.getCategories()) {
                if (!category.getFurniture().isEmpty()) {
                    catalogCategoryCombo.addItem(category);
                }
            }
        }
        ActionListener updateCategoryPieces = e -> {
            catalogPieceCombo.removeAllItems();
            FurnitureCategory selectedCat = (FurnitureCategory) catalogCategoryCombo.getSelectedItem();
            if (selectedCat != null) {
                for (CatalogPieceOfFurniture piece : selectedCat.getFurniture()) {
                    catalogPieceCombo.addItem(piece);
                }
            }
        };
        catalogCategoryCombo.addActionListener(updateCategoryPieces);
        if (catalogCategoryCombo.getItemCount() > 0) {
            catalogCategoryCombo.setSelectedIndex(0);
            updateCategoryPieces.actionPerformed(null);
        }

        // Attach listeners across all controls
        ActionListener stateUpdater = e -> {
            updateDependentVisibility();
            onCornerChanged.run();
        };
        for (javax.swing.AbstractButton b : new javax.swing.AbstractButton[]{
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
        for (JComboBox<?> cb : new JComboBox<?>[]{globalStartEndCombo, globalEndEndCombo, catalogCategoryCombo, catalogPieceCombo}) {
            cb.addActionListener(stateUpdater);
        }
        for (JSpinner sp : new JSpinner[]{distanceSpinner, pieceMarginSpinner, gapMinHeightSpinner, gapMaxHeightSpinner,
            gapFitToleranceSpinner, collisionFitToleranceSpinner, verticalStackCountSpinner, customStackElevationSpinner,
            cornerMarginSpinner, globalStartEndDistanceSpinner, globalEndEndDistanceSpinner}) {
            sp.addChangeListener(e -> onCornerChanged.run());
        }
        UiKit.addFiller(this);
        updateDependentVisibility();
    }

    /**
     * Builds and initializes a JComboBox for selecting start or end termination modes.
     *
     * @param isStart true for start mode, false for end mode
     */
    private JComboBox<Integer> createEndModeCombo(final String[] names, final boolean isStart) {
        JComboBox<Integer> combo = new JComboBox<>(new Integer[]{
            SurroundConfig.END_NORMAL,
            SurroundConfig.END_EXTRA_OBJECT,
            SurroundConfig.END_DISTANCE,
            SurroundConfig.END_FIT,
            SurroundConfig.END_ADJACENT_TO_OBJECTS
        });
        combo.setRenderer(UiKit.renderer(Integer.class, v -> names[Math.max(0, Math.min(names.length - 1, v))], false));
        return combo;
    }

    /** Adds a sub-panel without left indent. */
    private void addSubPanel(JPanel sub, int row) {
        addSubPanel(this, sub, row, 0);
    }

    /** Adds a sub-panel with the specified left margin indent. */
    private static void addSubPanel(JPanel parent, JPanel sub, int row, int indent) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = row;
        c.anchor = GridBagConstraints.LINE_START;
        c.insets = new Insets(0, indent, 0, 0);
        parent.add(sub, c);
    }

    /** Groups multiple radio buttons into a single logical ButtonGroup. */
    private static void group(JRadioButton... radios) {
        ButtonGroup group = new ButtonGroup();
        for (JRadioButton radio : radios) {
            group.add(radio);
        }
    }

    /**
     * Notifies this panel of the currently selected surrounding type (1 to 7)
     * and updates dependent visibility accordingly.
     *
     * @param type 1-based surrounding type index
     */
    void setSelectedType(int type) {
        this.currentType = type;
        if (!dimModeChosenByUser) {
            if (type == 6) {
                dimWidthOnlyRadio.setSelected(true);
            } else if (type == 3 || type == 4) {
                dimProportionalRadio.setSelected(true);
            }
        }
        updateDependentVisibility();
    }

    /** Updates uniform size state, affecting fit tolerance visibility. */
    void setUniformState(boolean uniformSize, boolean uniformGaps) {
        if (this.uniformSizeActive != uniformSize) {
            this.uniformSizeActive = uniformSize;
            updateDependentVisibility();
        }
    }

    /** Dynamically updates the visibility of dependent controls based on active options and surrounding type. */
    private void updateDependentVisibility() {
        boolean type7 = (currentType == 7);

        groupingSubPanel.setVisible(enableGroupingCheckBox.isSelected());

        boolean resizingType = (currentType == 3 || currentType == 4 || currentType == 6);
        dimModifySection.setVisible(resizingType);
        dimSelectedChecksRow.setVisible(resizingType && dimSelectedRadio.isSelected());

        // Reverse direction is available for Types 5, 6, and 7 (never for Type 2).
        // Symmetric both ends is available only for Type 6 (never for Type 5 as it would equal Type 2).
        boolean showReverse = (currentType == 5 || currentType == 6 || currentType == 7);
        boolean showSymmetric = (currentType == 6);
        reverseDirectionCheckBox.setVisible(showReverse);
        symmetricBothEndsCheckBox.setVisible(showSymmetric);
        dirAndEndsSection.setVisible(showReverse || showSymmetric);

        // Piece margin is available in Types 1, 2, and 5 (including when uniformGaps is active in Type 1,
        // where the number of objects and equal gaps are calculated over the remaining space after the margins)
        boolean supportsPieceMargin = (currentType == 1 || currentType == 2 || currentType == 5);
        pieceMarginSection.setVisible(supportsPieceMargin);
        pieceMarginSubRow.setVisible(supportsPieceMargin && enablePieceMarginCheckBox.isSelected());

        boolean supportsFitTolerance = (currentType != 4 || uniformSizeActive);
        gapsSubPanel.setVisible(splitAtObstaclesCheckBox.isSelected());
        gapHeightRow.setVisible(gapHeightRangeCheckBox.isSelected());
        gapToleranceHeaderRow.setVisible(supportsFitTolerance);
        gapFitToleranceSubRow.setVisible(supportsFitTolerance && enableGapFitToleranceCheckBox.isSelected());

        avoidCollisionsSubPanel.setVisible(avoidCollisionsCheckBox.isSelected());
        avoidNewlyGeneratedCheckBox.setVisible(true);
        collisionToleranceHeaderRow.setVisible(supportsFitTolerance);
        collisionFitToleranceSubRow.setVisible(supportsFitTolerance && enableCollisionFitToleranceCheckBox.isSelected());


        cornerHeaderRow.setVisible(true);
        boolean cornersActive = !type7 && enableCornerHandlingCheckBox.isSelected();
        cornerRows.setVisible(cornersActive);
        if (cornersActive) {
            boolean perp = cornerPlaceOrthogonalRadio.isSelected();
            orthoSubPanel.setVisible(perp);
            boolean sepSelected = perp && orthoSeparateRadio.isSelected();
            cornerMarginSpinner.setVisible(sepSelected);
            cornerMarginUnitLabel.setVisible(sepSelected);
            cornerMarginInfoIcon.setVisible(sepSelected);
            cornerTouchVertexRow.setVisible(perp);
            cornerKeepSizeRow.setVisible(perp && (currentType == 3 || currentType == 4));

            boolean hasCatalog = catalogCategoryCombo.getItemCount() > 0;
            useCatalogCornerCheckBox.setVisible(hasCatalog);
            boolean catalog = perp && hasCatalog && useCatalogCornerCheckBox.isSelected();
            catalogCombosRow.setVisible(catalog);

            boolean customEnds = cornerCustomRadio.isSelected();
            customEndsSubPanel.setVisible(customEnds);
            if (customEnds) {
                int sMode = globalStartEndCombo.getSelectedItem() instanceof Integer ? (Integer) globalStartEndCombo.getSelectedItem() : 0;
                int eMode = globalEndEndCombo.getSelectedItem() instanceof Integer ? (Integer) globalEndEndCombo.getSelectedItem() : 0;
                boolean sDist = (sMode == SurroundConfig.END_DISTANCE);
                boolean eDist = (eMode == SurroundConfig.END_DISTANCE);
                globalStartEndDistanceSpinner.setVisible(sDist);
                globalStartEndCmLabel.setVisible(sDist);
                globalEndEndDistanceSpinner.setVisible(eDist);
                globalEndEndCmLabel.setVisible(eDist);
                boolean hasExtraObj = (sMode == SurroundConfig.END_EXTRA_OBJECT
                        || sMode == SurroundConfig.END_ADJACENT_TO_OBJECTS
                        || eMode == SurroundConfig.END_EXTRA_OBJECT
                        || eMode == SurroundConfig.END_ADJACENT_TO_OBJECTS);
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

    /**
     * Resolves the selected orthogonal separation strategy constant.
     *
     * @return one of {@link SurroundConfig#ORTHO_SEPARATE}, {@link SurroundConfig#ORTHO_FIRST_VERTEX},
     *         {@link SurroundConfig#ORTHO_SECOND_VERTEX}, {@link SurroundConfig#ORTHO_MID_VERTEX}, or {@link SurroundConfig#ORTHO_OVERLAP}
     */
    int orthoSeparation() {
        return orthoSeparateRadio.isSelected() ? SurroundConfig.ORTHO_SEPARATE
                : orthoFirstVertexRadio.isSelected() ? SurroundConfig.ORTHO_FIRST_VERTEX
                : orthoSecondVertexRadio.isSelected() ? SurroundConfig.ORTHO_SECOND_VERTEX
                : orthoMidVertexRadio.isSelected() ? SurroundConfig.ORTHO_MID_VERTEX
                : SurroundConfig.ORTHO_OVERLAP;
    }

    /** Populates the target {@link SurroundConfig} with values configured in this panel. */
    void fill(SurroundConfig cfg) {
        cfg.distanceOffset = ((Number) distanceSpinner.getValue()).doubleValue();

        if (!enableGroupingCheckBox.isSelected()) {
            cfg.groupingMode = SurroundConfig.GROUP_NONE;
        } else {
            cfg.groupingMode = groupPerSideRadio.isSelected() ? SurroundConfig.GROUP_PER_SIDE : SurroundConfig.GROUP_ALL;
        }

        cfg.dimModifyMode = dimSelectedRadio.isSelected() ? SurroundConfig.DIM_SELECTED
                : dimWidthOnlyRadio.isSelected() ? SurroundConfig.DIM_WIDTH_ONLY
                : SurroundConfig.DIM_PROPORTIONAL;
        cfg.dimModifyD = dimCheckD.isSelected();
        cfg.dimModifyH = dimCheckH.isSelected();

        cfg.reverseDirection = (currentType == 5 || currentType == 6 || currentType == 7) && reverseDirectionCheckBox.isSelected();
        cfg.symmetricBothEnds = currentType == 6 && symmetricBothEndsCheckBox.isSelected();

        boolean supportsPieceMargin = (currentType == 1 || currentType == 2 || currentType == 5);
        cfg.enablePieceMargin = supportsPieceMargin && enablePieceMarginCheckBox.isSelected();
        cfg.pieceMargin = cfg.enablePieceMargin ? ((Number) pieceMarginSpinner.getValue()).doubleValue() : 0.0;

        boolean supportsFitTolerance = (currentType != 4 || uniformSizeActive);
        cfg.splitAtObstacles = splitAtObstaclesCheckBox.isSelected();
        cfg.respectWindows = respectWindowsCheckBox.isSelected();
        cfg.gapHeightRange = gapHeightRangeCheckBox.isSelected();
        cfg.gapMinHeight = ((Number) gapMinHeightSpinner.getValue()).doubleValue();
        cfg.gapMaxHeight = Math.max(cfg.gapMinHeight, ((Number) gapMaxHeightSpinner.getValue()).doubleValue());
        cfg.enableGapFitTolerance = cfg.splitAtObstacles && supportsFitTolerance && enableGapFitToleranceCheckBox.isSelected();
        cfg.gapFitTolerance = cfg.enableGapFitTolerance ? ((Number) gapFitToleranceSpinner.getValue()).doubleValue() : 0.0;
        cfg.shortSidesOneObject = shortSidesCheckBox.isSelected();
        cfg.avoidCollisions = avoidCollisionsCheckBox.isSelected();
        cfg.avoidNearbyFloors = cfg.avoidCollisions && hasFloors && avoidNearbyFloorsCheckBox.isSelected();
        cfg.avoidNewlyGenerated = cfg.avoidCollisions && avoidNewlyGeneratedCheckBox.isSelected();
        cfg.enableCollisionFitTolerance = cfg.avoidCollisions && supportsFitTolerance && enableCollisionFitToleranceCheckBox.isSelected();
        cfg.collisionFitTolerance = cfg.enableCollisionFitTolerance ? ((Number) collisionFitToleranceSpinner.getValue()).doubleValue() : 0.0;
        cfg.upperLevelFloorHolesMode = (hasUpperLevel && floorHolesNoPassRadio.isSelected())
                ? SurroundConfig.FLOOR_HOLES_NO_PASS : SurroundConfig.FLOOR_HOLES_PASS;

        cfg.enableVerticalStacking = enableVerticalStackingCheckBox.isSelected();
        cfg.verticalStackCount = ((Number) verticalStackCountSpinner.getValue()).intValue();
        cfg.useCustomStackElevation = useCustomStackElevationCheckBox.isSelected();
        cfg.customStackElevation = ((Number) customStackElevationSpinner.getValue()).doubleValue();

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
                ? ((Number) cornerMarginSpinner.getValue()).doubleValue() : 0.0;
        cfg.customCornerCatalogPiece = (cfg.placeOrthogonalCornerPiece && useCatalogCornerCheckBox.isSelected())
                ? (CatalogPieceOfFurniture) catalogPieceCombo.getSelectedItem() : null;
        cfg.globalStartEndMode = globalStartEndCombo.getSelectedItem() instanceof Integer
                ? (Integer) globalStartEndCombo.getSelectedItem() : SurroundConfig.END_NORMAL;
        cfg.globalStartEndDistance = ((Number) globalStartEndDistanceSpinner.getValue()).doubleValue();
        cfg.globalEndEndMode = globalEndEndCombo.getSelectedItem() instanceof Integer
                ? (Integer) globalEndEndCombo.getSelectedItem() : SurroundConfig.END_FIT;
        cfg.globalEndEndDistance = ((Number) globalEndEndDistanceSpinner.getValue()).doubleValue();
        cfg.extraObjectRespectObstacles = extraObjectRespectObstaclesCheckBox.isSelected();
        cfg.extraObjectRespectCorners = cfg.extraObjectRespectObstacles && extraObjectRespectCornersCheckBox.isSelected();
    }

    /** Sets the callback for configuring random properties and updates the summary badge. */
    void setOnConfigureRandomProps(Runnable callback) {
        this.onConfigureRandomProps = callback;
        updateRandomPropsSummary();
    }

    /** Sets the callback for configuring gradual properties and updates the summary badge. */
    void setOnConfigureGradualProps(Runnable callback) {
        this.onConfigureGradualProps = callback;
        updateGradualPropsSummary();
    }

    /** Updates the summary label displaying the number and percentage of active stochastic properties. */
    void updateRandomPropsSummary() {
        int active = randomSettings.activeFeatureCount();
        String text = activeCount(active);
        if (randomSettings.randomObjectSubset && active > 0) {
            text += String.format(" (%.0f%%)", randomSettings.objectProbability);
        }
        randomPropsSummaryLabel.setText(text);
    }

    /** Updates the summary label displaying the count of active gradual properties. */
    void updateGradualPropsSummary() {
        gradualPropsSummaryLabel.setText(activeCount(gradualSettings.activeGradualCount()));
    }

    /**
     * Formats an integer count into a localized summary string.
     *
     * @return localized string (e.g. "3 active" or "None active")
     */
    private String activeCount(int active) {
        return active > 0
                ? MessageFormat.format(texts.get("randomActiveCount", "{0} active"), active)
                : texts.get("randomNoneActive", "None active");
    }
}
