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
import com.eteks.sweethome3d.model.Level;
import com.eteks.sweethome3d.model.Room;
import com.eteks.sweethome3d.model.Selectable;
import com.eteks.sweethome3d.model.Wall;
import com.eteks.sweethome3d.plugin.Plugin;
import com.eteks.sweethome3d.plugin.PluginAction;
import com.eteks.sweethome3d.tools.URLContent;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.Set;
import javax.swing.JOptionPane;
import javax.swing.undo.AbstractUndoableEdit;

/**
 * Plugin entry point: with floors or walls selected, asks for the template objects ({@link ObjectPicker}),
 * whether to surround the floor, the walls or both when both are selected ({@link FloorOrWallsChooser}) and
 * the settings ({@link SurroundConfigFrame}); then runs {@link SurroundGeometryEngine} as one undoable edit,
 * offering to draw again when something is random.
 */
public class SurroundRoomPlugin extends Plugin {

    /** Active configuration frame instance. */
    private SurroundConfigFrame configFrame;

    /** Active floating object picker widget. */
    private ObjectPicker objectPicker;

    /** Registers the plugin's toolbar action and menu entry in Sweet Home 3D. */
    @Override
    public PluginAction[] getActions() {
        return new PluginAction[]{
            new PluginAction("surr_room.ApplicationPlugin", "SURROUND_ROOM", getPluginClassLoader(), false) {
                {
                    java.net.URL iconUrl = UiKit.pluginIconUrl(getPluginClassLoader());
                    if (iconUrl != null) {
                        putPropertyValue(Property.SMALL_ICON, new URLContent(iconUrl));
                    }
                    putPropertyValue(Property.TOOL_BAR, true);
                    setEnabled(true);
                }

                @Override
                public void execute() {
                    launchPlugin();
                }
            }
        };
    }

    /**
     * Initiates the plugin workflow by reading selected rooms/walls and opening the ObjectPicker.
     * Furniture objects are intentionally selected in step 2 so that objects already inside the room
     * do not need to be painstakingly deselected from a broad marquee selection.
     */
    private void launchPlugin() {
        try {
            ResourceBundle resource = ResourceBundle.getBundle(
                    "surr_room.ApplicationPlugin", Locale.getDefault(), getPluginClassLoader());
            String title = resource.getString("SURROUND_ROOM.TITLE");
            List<Room> selectedRooms = getSelected(Room.class);
            List<Wall> selectedWalls = getSelected(Wall.class);
            if (selectedRooms.isEmpty() && selectedWalls.isEmpty()) {
                showNoFloorOrWall(resource, title);
                return;
            }
            if (objectPicker != null) {
                objectPicker.close();
            }
            if (configFrame != null && configFrame.isDisplayable()) {
                configFrame.dispose();
            }
            objectPicker = ObjectPicker.show(getHome(), getHomeController(), getUserPreferences(),
                    new UiKit.Texts(resource), title,
                    selectedRooms.size(), selectedWalls.size(),
                    furniture -> continueWithObjects(selectedRooms, selectedWalls, furniture, resource, title));
        } catch (Exception e) {
            JOptionPane.showMessageDialog(null, "Error loading resources: " + e.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    /** Displays an informational message when no rooms or walls were selected before invoking the plugin. */
    private static void showNoFloorOrWall(ResourceBundle resource, String title) {
        JOptionPane.showMessageDialog(null, UiKit.text(resource, "msgNoRoomOrWall",
                "<html>No floors or walls are selected.<br>Select at least one floor or wall; the object is chosen next."),
                title, JOptionPane.INFORMATION_MESSAGE);
    }

    /**
     * Resumes workflow once template furniture pieces are chosen in the {@link ObjectPicker}.
     * Disambiguates mixed room/wall selections and opens {@link SurroundConfigFrame}.
     */
    private void continueWithObjects(List<Room> rooms, List<Wall> walls, List<HomePieceOfFurniture> selectedFurniture,
            ResourceBundle resource, String title) {
        objectPicker = null;
        try {
            // Filter out any rooms or walls that were deleted while the object picker was open
            List<Room> selectedRooms = new ArrayList<>(rooms);
            selectedRooms.retainAll(getHome().getRooms());
            List<Wall> selectedWalls = new ArrayList<>(walls);
            selectedWalls.retainAll(getHome().getWalls());
            if (selectedRooms.isEmpty() && selectedWalls.isEmpty()) {
                showNoFloorOrWall(resource, title);
                return;
            }

            // Wrap rooms and walls into SurroundTarget models
            List<SurroundTarget> roomTargets = new ArrayList<>();
            for (int i = 0; i < selectedRooms.size(); i++) {
                roomTargets.add(SurroundTarget.forRoom(selectedRooms.get(i), i + 1));
            }
            // Disambiguate adjacent rooms and walls (floor, walls, or both)
            List<SurroundTarget> targets = FloorOrWallsChooser.choose(roomTargets,
                    SurroundTarget.forWalls(selectedWalls, false), resource, title);
            if (targets == null) {
                return; // User cancelled
            }

            // Estimate longitudinal length of the primary furniture template (or group footprint)
            HomePieceOfFurniture firstPiece = SurroundGeometryEngine.asSingleTemplate(selectedFurniture);
            double angle = firstPiece.getAngle();
            double lengthAlongSide = Math.abs(firstPiece.getWidth() * Math.cos(angle))
                    + Math.abs(firstPiece.getDepth() * Math.sin(angle));
            double defaultHalfObjectLength = Math.max(1.0, lengthAlongSide / 2.0);

            // Construct and display the main configuration frame
            configFrame = new SurroundConfigFrame(
                    resource,
                    getUserPreferences(),
                    selectedFurniture.size(),
                    targets,
                    defaultHalfObjectLength,
                    isAboveLowestLevel(targets),
                    config -> executeSurroundOperation(config, configFrame.getActiveTargets(), selectedFurniture, resource)
            );
            configFrame.setTemplateContext(getHome(), selectedFurniture, getHomeController());
            configFrame.setHomeObstacles(homeObstacles(selectedFurniture),
                    depthAcross(java.util.Collections.singletonList(firstPiece)));
            configFrame.setVisible(true);
        } catch (RuntimeException e) {
            e.printStackTrace();
            JOptionPane.showMessageDialog(null, MessageFormat.format(UiKit.text(resource, "msgSurroundError",
                    "The surround could not be completed: {0}"), String.valueOf(e)), title, JOptionPane.ERROR_MESSAGE);
        }
    }

    /**
     * Executes the surrounding geometry placement asynchronously so that if calculation takes a noticeable time,
     * a "Processing..." ("Procesando...") legend is displayed over the window without freezing the UI, and then
     * commits the result within a single atomic Sweet Home 3D UndoableEdit transaction.
     */
    private void executeSurroundOperation(final SurroundConfig config, List<SurroundTarget> targets,
            final List<HomePieceOfFurniture> selectedFurniture, final ResourceBundle resource) {
        final List<Room> faces = new ArrayList<>();
        for (SurroundTarget target : targets) {
            for (Room face : target.getFaces(config.interior)) {
                faces.add(face);
                if (target.isAlwaysExterior(face)) {
                    config.alwaysExterior.add(face);
                }
            }
        }
        runSingleSurroundPass(config, faces, selectedFurniture, resource);
    }

    /** Runs one pass of the surround calculation in a background thread (showing "Procesando..." if slow) and commits on the EDT. */
    private void runSingleSurroundPass(final SurroundConfig config, final List<Room> faces,
            final List<HomePieceOfFurniture> selectedFurniture, final ResourceBundle resource) {
        final SurroundConfigFrame frame = configFrame;
        final javax.swing.Timer processingTimer = new javax.swing.Timer(120, e -> {
            if (frame != null && frame.isDisplayable()) {
                frame.setProcessingOverlay(true);
            }
        });
        processingTimer.setRepeats(false);
        processingTimer.start();

        Thread worker = new Thread(() -> {
            final SurroundGeometryEngine engine = new SurroundGeometryEngine(
                    getHome(), getHomeController(), faces, selectedFurniture);
            try {
                engine.computeInMemory(config);
            } catch (final RuntimeException ex) {
                ex.printStackTrace();
                javax.swing.SwingUtilities.invokeLater(() -> {
                    processingTimer.stop();
                    if (frame != null) {
                        frame.setProcessingOverlay(false);
                    }
                    JOptionPane.showMessageDialog(frame,
                            MessageFormat.format(UiKit.text(resource, "msgSurroundError",
                                    "The surround could not be completed: {0}"), String.valueOf(ex)),
                            resource.getString("SURROUND_ROOM.TITLE"), JOptionPane.ERROR_MESSAGE);
                });
                return;
            }

            javax.swing.SwingUtilities.invokeLater(() -> {
                processingTimer.stop();
                if (frame != null && frame.isDisplayable()) {
                    frame.setProcessingOverlay(true);
                    ((javax.swing.JComponent) frame.getGlassPane()).paintImmediately(0, 0, frame.getWidth(), frame.getHeight());
                }
                getUndoableEditSupport().beginUpdate();
                try {
                    engine.commitToHome(config);
                    getUndoableEditSupport().postEdit(new AbstractUndoableEdit() {
                        private static final long serialVersionUID = 1L;

                        @Override
                        public String getPresentationName() {
                            return resource.getString("undoSurround");
                        }
                    });
                } catch (RuntimeException ex) {
                    ex.printStackTrace();
                    if (frame != null) {
                        frame.setProcessingOverlay(false);
                    }
                    JOptionPane.showMessageDialog(frame,
                            MessageFormat.format(UiKit.text(resource, "msgSurroundError",
                                    "The surround could not be completed: {0}"), String.valueOf(ex)),
                            resource.getString("SURROUND_ROOM.TITLE"), JOptionPane.ERROR_MESSAGE);
                    return;
                } finally {
                    getUndoableEditSupport().endUpdate();
                    if (frame != null) {
                        frame.setProcessingOverlay(false);
                    }
                }

                if (config.skippedDueToNoFloorBelow) {
                    JOptionPane.showMessageDialog(frame,
                            UiKit.text(resource, "msgFloorHolesSkippedWarning",
                                    "Some objects were not placed over floor openings because there is no floor below."),
                            resource.getString("SURROUND_ROOM.TITLE"), JOptionPane.WARNING_MESSAGE);
                }

                String uniformReport = uniformReport(config, resource);
                if (uniformReport != null) {
                    JOptionPane.showMessageDialog(frame, uniformReport, resource.getString("SURROUND_ROOM.TITLE"),
                            config.sizeUnfittedStretches > 0 ? JOptionPane.WARNING_MESSAGE : JOptionPane.INFORMATION_MESSAGE);
                }

                if (hasAnyRandomness(config)) {
                    Object[] options = {
                        UiKit.text(resource, "accept", "OK"),
                        UiKit.text(resource, "reroll", "Draw again")
                    };
                    int choice = JOptionPane.showOptionDialog(frame, resource.getString("success"),
                            resource.getString("completed"), JOptionPane.DEFAULT_OPTION,
                            JOptionPane.INFORMATION_MESSAGE, null, options, options[0]);
                    if (choice == 1) {
                        getHomeController().undo();
                        runSingleSurroundPass(config, faces, selectedFurniture, resource);
                        return;
                    }
                } else {
                    JOptionPane.showMessageDialog(frame, resource.getString("success"),
                            resource.getString("completed"), JOptionPane.INFORMATION_MESSAGE);
                }
                if (frame != null) {
                    frame.dispose();
                }
            });
        }, "SurroundExecuteWorker");
        worker.setDaemon(true);
        worker.start();
    }

    /** Checks if the configuration activates any stochastic random properties or random dimension sizing. */
    private static boolean hasAnyRandomness(SurroundConfig config) {
        if (config == null) {
            return false;
        }
        return (config.enableRandomProps && config.randomSettings != null && config.randomSettings.hasAny())
                || (config.surroundType == 7 && (config.randomizeAll || config.randomizeWidth
                        || config.randomizeDepth || config.randomizeHeight));
    }

    /**
     * Generates an informational diagnostic report summarizing uniform sizing and gap compensations.
     *
     * @return multiline report string, or null if all stretches fit cleanly within limits
     */
    private static String uniformReport(SurroundConfig config, ResourceBundle resource) {
        StringBuilder report = new StringBuilder();
        if (config.sizeAdjustedStretches > 0) {
            report.append(MessageFormat.format(UiKit.text(resource, "msgUniformSizeAdjusted",
                    "{0} stretch(es) needed a larger size change to be covered without gaps: the largest and the smallest object differ by {1}%."),
                    config.sizeAdjustedStretches, String.format(Locale.getDefault(), "%.1f", config.sizeSpread)));
        }
        if (config.sizeCenteredStretches > 0) {
            report.append(report.length() > 0 ? "\n" : "").append(MessageFormat.format(UiKit.text(resource, "msgUniformSizeCentered",
                    "{0} stretch(es) keep the common size, centred, with gaps at their ends."), config.sizeCenteredStretches));
        }
        if (config.sizeUnfittedStretches > 0) {
            report.append(report.length() > 0 ? "\n" : "").append(MessageFormat.format(UiKit.text(resource, "msgUniformSizeEmpty",
                    "{0} stretch(es) were left empty because they cannot be covered within the limits."), config.sizeUnfittedStretches));
        }
        if (config.gapAdjustedStretches > 0) {
            report.append(report.length() > 0 ? "\n" : "").append(MessageFormat.format(UiKit.text(resource, "msgUniformGapsAdjusted",
                    "{0} stretch(es) could not get the same space within the limit: the widest and the narrowest space differ by {1} cm."),
                    config.gapAdjustedStretches, String.format(Locale.getDefault(), "%.1f", config.gapSpread)));
        }
        return report.length() > 0 ? report.toString() : null;
    }

    /**
     * Gathers all pre-existing furniture, doors, and windows in the home to act as collision obstacles,
     * excluding the template furniture pieces being replicated.
     */
    private List<HomePieceOfFurniture> homeObstacles(List<HomePieceOfFurniture> selectedFurniture) {
        Set<HomePieceOfFurniture> selected = new HashSet<>();
        for (HomePieceOfFurniture piece : selectedFurniture) {
            SurroundGeometryEngine.collectTemplatePieces(piece, selected);
        }
        List<HomePieceOfFurniture> obstacles = new ArrayList<>();
        for (HomePieceOfFurniture piece : getHome().getFurniture()) {
            if (piece.isVisible() && !selected.contains(piece)) {
                obstacles.add(piece);
            }
        }
        return obstacles;
    }

    /**
     * Measures the maximum perpendicular depth across the wall for the chosen template furniture pieces.
     *
     * @return maximum depth in cm
     */
    private static double depthAcross(List<HomePieceOfFurniture> selectedFurniture) {
        double depth = 0;
        for (HomePieceOfFurniture piece : selectedFurniture) {
            double angle = piece.getAngle();
            depth = Math.max(depth, Math.abs(piece.getWidth() * Math.sin(angle)) + Math.abs(piece.getDepth() * Math.cos(angle)));
        }
        return depth;
    }

    /** Checks if any of the target faces reside on an elevated floor level above the home's ground level (Item 6). */
    private boolean isAboveLowestLevel(List<SurroundTarget> targets) {
        List<Level> levels = getHome().getLevels();
        if (levels == null || levels.size() < 2) {
            return false;
        }
        float lowest = Float.MAX_VALUE;
        for (Level level : levels) {
            lowest = Math.min(lowest, level.getElevation());
        }
        for (SurroundTarget target : targets) {
            for (Room face : target.getFaces(false)) {
                Level level = face.getLevel();
                if (level != null && level.getElevation() > lowest + 1e-3f) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Helper retrieving all items currently selected in the Home that are instances of the specified type. */
    private <T> List<T> getSelected(Class<T> type) {
        List<T> items = new ArrayList<>();
        for (Selectable item : getHome().getSelectedItems()) {
            if (type.isInstance(item)) {
                items.add(type.cast(item));
            }
        }
        return items;
    }
}