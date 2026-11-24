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
import java.net.URL;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.Set;
import javax.swing.JComponent;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.undo.AbstractUndoableEdit;

/**
 * Plugin entry point: with floors or walls selected, asks for template objects ({@link ObjectPicker}),
 * resolves mixed floor/wall groups ({@link FloorOrWallsChooser}), opens {@link SurroundConfigFrame},
 * and runs {@link SurroundGeometryEngine} as a single undoable edit with optional re-roll when random.
 */
public class SurroundRoomPlugin extends Plugin {

    private SurroundConfigFrame configFrame;
    private ObjectPicker objectPicker;

    @Override
    public PluginAction[] getActions() {
        return new PluginAction[]{
            new PluginAction("surr_room.ApplicationPlugin", "SURROUND_ROOM", getPluginClassLoader(), false) {
                {
                    URL iconUrl = UiKit.pluginIconUrl(getPluginClassLoader());
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

    /** Reads selected rooms/walls first so objects already inside a marquee selection do not interfere. */
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
                    new UiKit.Texts(resource), title, selectedRooms.size(), selectedWalls.size(),
                    furniture -> continueWithObjects(selectedRooms, selectedWalls, furniture, resource, title));
        } catch (Exception e) {
            JOptionPane.showMessageDialog(null, "Error loading resources: " + e.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private static void showNoFloorOrWall(ResourceBundle resource, String title) {
        JOptionPane.showMessageDialog(null, UiKit.text(resource, "msgNoRoomOrWall",
                "<html>No floors or walls are selected.<br>Select at least one floor or wall; the object is chosen next."),
                title, JOptionPane.INFORMATION_MESSAGE);
    }

    private void continueWithObjects(List<Room> rooms, List<Wall> walls, List<HomePieceOfFurniture> selectedFurniture,
            ResourceBundle resource, String title) {
        objectPicker = null;
        try {
            List<Room> selectedRooms = new ArrayList<>(rooms);
            selectedRooms.retainAll(getHome().getRooms());
            List<Wall> selectedWalls = new ArrayList<>(walls);
            selectedWalls.retainAll(getHome().getWalls());
            if (selectedRooms.isEmpty() && selectedWalls.isEmpty()) {
                showNoFloorOrWall(resource, title);
                return;
            }

            List<SurroundTarget> roomTargets = new ArrayList<>();
            for (int i = 0; i < selectedRooms.size(); i++) {
                roomTargets.add(SurroundTarget.forRoom(selectedRooms.get(i), i + 1));
            }
            List<SurroundTarget> targets = FloorOrWallsChooser.choose(roomTargets,
                    SurroundTarget.forWalls(selectedWalls, false), resource, title);
            if (targets == null) {
                return;
            }

            HomePieceOfFurniture firstPiece = SurroundGeometryEngine.asSingleTemplate(selectedFurniture);
            double angle = firstPiece.getAngle();
            double lengthAlongSide = Math.abs(firstPiece.getWidth() * Math.cos(angle))
                    + Math.abs(firstPiece.getDepth() * Math.sin(angle));

            configFrame = new SurroundConfigFrame(resource, getUserPreferences(), selectedFurniture.size(),
                    targets, Math.max(1.0, lengthAlongSide / 2.0), isAboveLowestLevel(targets),
                    config -> executeSurroundOperation(config, configFrame.getActiveTargets(), selectedFurniture, resource));
            configFrame.setTemplateContext(getHome(), selectedFurniture, getHomeController());
            configFrame.setHomeObstacles(homeObstacles(selectedFurniture),
                    depthAcross(Collections.singletonList(firstPiece)));
            configFrame.setVisible(true);
        } catch (RuntimeException e) {
            e.printStackTrace();
            showSurroundError(null, resource, title, e);
        }
    }

    private static void showSurroundError(SurroundConfigFrame parent, ResourceBundle resource, String title, RuntimeException ex) {
        JOptionPane.showMessageDialog(parent, MessageFormat.format(UiKit.text(resource, "msgSurroundError",
                "The surround could not be completed: {0}"), String.valueOf(ex)), title, JOptionPane.ERROR_MESSAGE);
    }

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

    /** Computes geometry off-EDT with a delayed processing overlay, then commits atomically on the EDT. */
    private void runSingleSurroundPass(final SurroundConfig config, final List<Room> faces,
            final List<HomePieceOfFurniture> selectedFurniture, final ResourceBundle resource) {
        final SurroundConfigFrame frame = configFrame;
        final Timer processingTimer = new Timer(120, e -> {
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
                SwingUtilities.invokeLater(() -> {
                    processingTimer.stop();
                    if (frame != null) {
                        frame.setProcessingOverlay(false);
                    }
                    showSurroundError(frame, resource, resource.getString("SURROUND_ROOM.TITLE"), ex);
                });
                return;
            }

            SwingUtilities.invokeLater(() -> {
                processingTimer.stop();
                if (frame != null && frame.isDisplayable()) {
                    frame.setProcessingOverlay(true);
                    ((JComponent) frame.getGlassPane()).paintImmediately(0, 0, frame.getWidth(), frame.getHeight());
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
                    showSurroundError(frame, resource, resource.getString("SURROUND_ROOM.TITLE"), ex);
                    return;
                } finally {
                    getUndoableEditSupport().endUpdate();
                    if (frame != null) {
                        frame.setProcessingOverlay(false);
                    }
                }

                String title = resource.getString("SURROUND_ROOM.TITLE");
                if (config.skippedDueToNoFloorBelow) {
                    JOptionPane.showMessageDialog(frame,
                            UiKit.text(resource, "msgFloorHolesSkippedWarning",
                                    "Some objects were not placed over floor openings because there is no floor below."),
                            title, JOptionPane.WARNING_MESSAGE);
                }
                String uniformReport = uniformReport(config, resource);
                if (uniformReport != null) {
                    JOptionPane.showMessageDialog(frame, uniformReport, title,
                            config.sizeUnfittedStretches > 0 ? JOptionPane.WARNING_MESSAGE : JOptionPane.INFORMATION_MESSAGE);
                }

                if (hasAnyRandomness(config)) {
                    Object[] options = {UiKit.text(resource, "accept", "OK"), UiKit.text(resource, "reroll", "Draw again")};
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

    private static boolean hasAnyRandomness(SurroundConfig config) {
        if (config == null) {
            return false;
        }
        return (config.enableRandomProps && config.randomSettings != null && config.randomSettings.hasAny())
                || (config.surroundType == 7 && (config.randomizeAll || config.randomizeWidth
                        || config.randomizeDepth || config.randomizeHeight));
    }

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

    private static double depthAcross(List<HomePieceOfFurniture> selectedFurniture) {
        double depth = 0;
        for (HomePieceOfFurniture piece : selectedFurniture) {
            double angle = piece.getAngle();
            depth = Math.max(depth, Math.abs(piece.getWidth() * Math.sin(angle)) + Math.abs(piece.getDepth() * Math.cos(angle)));
        }
        return depth;
    }

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
