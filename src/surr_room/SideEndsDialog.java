/*
 * Copyright (c) 2024-2026 Oliver Mart\u00ednez (Ollie_Jess)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package surr_room;

import com.eteks.sweethome3d.model.Home;
import com.eteks.sweethome3d.model.HomePieceOfFurniture;
import com.eteks.sweethome3d.model.Room;
import java.awt.Window;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.Set;
import javax.swing.JDialog;

/**
 * Modal dialog wrapper hosting an embedded {@link SideEndsPanel}.
 *
 * Provides standalone modal window capability for editing side inclusion/exclusion
 * or excluded sub-stretches, while delegating all 2D vector rendering and editing logic
 * to {@link SideEndsPanel}.
 */
class SideEndsDialog extends JDialog {

    /** Mode constant: Include or exclude entire sides. */
    static final int DIALOG_SIDES = SideEndsPanel.DIALOG_SIDES;

    /** Mode constant: Exclude specific sub-stretches/intervals along sides. */
    static final int DIALOG_SEGMENTS = SideEndsPanel.DIALOG_SEGMENTS;

    /** Embedded interactive panel component. */
    private final SideEndsPanel panel;

    /** Whether the dialog was confirmed via OK button. */
    private boolean confirmed;

    /** Confirmed configuration result map. */
    private Map<Room, SurroundConfig.SideEnds> result;

    /**
     * Constructs a new SideEndsDialog.
     *
     * @param dialogMode              {@link #DIALOG_SIDES} or {@link #DIALOG_SEGMENTS}
     * @param defaultHalfObjectLength half object length in cm
     */
    SideEndsDialog(Window owner, ResourceBundle resource, List<Room> rooms, List<String> roomNames,
            Map<Room, SurroundConfig.SideEnds> current, boolean interior, Set<Room> alwaysExterior,
            int dialogMode, boolean mergeCollinear, double defaultHalfObjectLength, boolean cornerHandlingActive) {
        super(owner, dialogMode == DIALOG_SEGMENTS
                ? UiKit.text(resource, "segmentsTitle", "Excluded stretches")
                : UiKit.text(resource, "sidesTitle", "Sides to surround"),
                ModalityType.DOCUMENT_MODAL);
        this.panel = new SideEndsPanel(resource,
                (res, mode) -> {
                    this.result = res;
                    this.confirmed = true;
                    dispose();
                },
                () -> {
                    this.confirmed = false;
                    dispose();
                });
        this.panel.startEditing(rooms, roomNames, current, interior, alwaysExterior, dialogMode,
                mergeCollinear, defaultHalfObjectLength, cornerHandlingActive);
        UiKit.applyWindowIcon(this);
        setContentPane(this.panel);
        pack();
        setLocationRelativeTo(owner);
    }

    /**
     * Injects context dependencies required for live preview calculation in segments mode.
     *
     * @param objectDepth       depth of the template piece in cm
     */
    void setPreviewContext(Home home, List<HomePieceOfFurniture> selectedFurniture, SurroundConfig snapshotConfig,
            List<HomePieceOfFurniture> obstacles, double objectDepth) {
        panel.setPreviewContext(home, selectedFurniture, snapshotConfig, obstacles, objectDepth);
    }

    /**
     * Returns the confirmed configuration map, or {@code null} if cancelled.
     *
     * @return map of Room to SideEnds, or null
     */
    Map<Room, SurroundConfig.SideEnds> getResult() {
        return confirmed ? result : null;
    }
}
