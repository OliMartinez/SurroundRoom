/*
 * Copyright (c) 2024-2026 Oliver Mart\u00ednez (Ollie_Jess)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package surr_room;

import java.awt.FlowLayout;
import javax.swing.ButtonGroup;
import javax.swing.JCheckBox;
import javax.swing.JPanel;
import javax.swing.JRadioButton;

/**
 * Top bar: exterior or interior placement, and options to treat touching
 * closed sets as one and to merge aligned (0&deg;) sides.
 */
class ModePanel extends JPanel {

    final JRadioButton extOfRoom;
    final JRadioButton intOfRoom;
    final JCheckBox mergeCollinearCheckBox;
    final JCheckBox mergeTouchingClosedSetsCheckBox;

    ModePanel(UiKit.Texts t, boolean hasTargets, boolean hasOpenWalls, boolean hasMultipleTouchingClosed,
            Runnable onModeChanged, Runnable onTopologyChanged) {
        super(new FlowLayout(FlowLayout.LEADING, 10, 0));

        extOfRoom = new JRadioButton(t.get("externally", "Outside"), true);
        intOfRoom = new JRadioButton(t.get("internally", "Inside"));
        ButtonGroup group = new ButtonGroup();
        group.add(extOfRoom);
        group.add(intOfRoom);
        add(extOfRoom);
        add(intOfRoom);

        if (hasOpenWalls) {
            add(UiKit.hint(t.get("openWallsShortHint", "Open walls: always outside")));
        }

        mergeTouchingClosedSetsCheckBox = new JCheckBox(t.get("mergeTouchingClosedSets", "Treat touching closed sets as one"), false);
        mergeTouchingClosedSetsCheckBox.addActionListener(e -> onTopologyChanged.run());
        mergeTouchingClosedSetsCheckBox.setVisible(hasMultipleTouchingClosed);
        if (hasMultipleTouchingClosed) {
            add(mergeTouchingClosedSetsCheckBox);
        }

        mergeCollinearCheckBox = new JCheckBox(t.get("mergeCollinearSides", "Merge aligned sides (0\u00b0) into one"), false);
        mergeCollinearCheckBox.addActionListener(e -> onTopologyChanged.run());
        add(mergeCollinearCheckBox);

        extOfRoom.addActionListener(e -> onModeChanged.run());
        intOfRoom.addActionListener(e -> onModeChanged.run());
    }

    boolean isInterior() {
        return intOfRoom.isSelected();
    }
}
