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
import java.awt.Insets;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSeparator;
import javax.swing.UIManager;

/**
 * Asks what to surround when floors and their walls are selected together: the floor, the walls or both.
 * With several separate groups of touching floors and walls, it asks per group, with "Apply to all".
 */
final class FloorOrWallsChooser {

    /** Option index indicating that only the room floors should be surrounded. */
    static final int FLOOR = 0;

    /** Option index indicating that only the wall outlines should be surrounded. */
    static final int WALLS = 1;

    /** Option index indicating that both room floors and wall outlines should be surrounded. */
    static final int BOTH = 2;

    /** Private constructor preventing instantiation of utility class. */
    private FloorOrWallsChooser() {
    }

    /**
     * Identifies mixed room/wall groups and prompts the user to select whether to surround
     * the floor, the walls, or both for each group.
     *
     * @return the filtered list of {@link SurroundTarget}s to surround, or null if cancelled
     */
    static List<SurroundTarget> choose(List<SurroundTarget> rooms, List<SurroundTarget> walls,
            ResourceBundle resource, String title) {
        // Group rooms and walls that touch each other using geometric proximity
        List<List<Integer>> groups = groupsTogether(rooms, walls);
        List<List<Integer>> mixed = new ArrayList<>();
        for (List<Integer> group : groups) {
            boolean hasRoom = false, hasWalls = false;
            for (int index : group) {
                hasRoom |= index < rooms.size();
                hasWalls |= index >= rooms.size();
            }
            if (hasRoom && hasWalls) {
                mixed.add(group);
            }
        }

        int[] choices = new int[mixed.size()];
        if (mixed.size() == 1) {
            // Exactly one mixed cluster: show a simple modal dialog with 3 option buttons + Cancel
            String cancel = UIManager.getString("OptionPane.cancelButtonText") != null
                    ? UIManager.getString("OptionPane.cancelButtonText") : "Cancel";
            int choice = JOptionPane.showOptionDialog(null,
                    UiKit.text(resource, "askFloorOrWalls", "Adjacent rooms and walls are selected. What would you like to surround?"),
                    title, JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null,
                    new Object[]{UiKit.text(resource, "floorOption", "Floor"), UiKit.text(resource, "wallsOption", "Walls"),
                        UiKit.text(resource, "bothOption", "Both"), cancel},
                    UiKit.text(resource, "floorOption", "Floor"));
            if (choice < 0 || choice > BOTH) {
                return null; // User closed dialog or clicked Cancel
            }
            choices[0] = choice;
        } else if (mixed.size() > 1) {
            // Multiple disjoint clusters: present a multi-row combo box chooser
            int[] chosen = askGroupByGroup(mixed, rooms, walls, resource, title);
            if (chosen == null) {
                return null;
            }
            choices = chosen;
        }

        // Mark which targets to retain: unmixed targets are retained automatically
        boolean[] keep = new boolean[rooms.size() + walls.size()];
        java.util.Arrays.fill(keep, true);
        for (int g = 0; g < mixed.size(); g++) {
            for (int index : mixed.get(g)) {
                boolean isRoom = index < rooms.size();
                keep[index] = choices[g] == BOTH || (isRoom ? choices[g] == FLOOR : choices[g] == WALLS);
            }
        }

        // Assemble the final filtered target list
        List<SurroundTarget> targets = new ArrayList<>();
        for (int i = 0; i < rooms.size(); i++) {
            if (keep[i]) {
                targets.add(rooms.get(i));
            }
        }
        for (int j = 0; j < walls.size(); j++) {
            if (keep[rooms.size() + j]) {
                targets.add(walls.get(j));
            }
        }
        return targets;
    }

    /**
     * Partitions rooms and walls into connected components using a disjoint-set (Union-Find) algorithm.
     * Room indices run from {@code 0} to {@code r - 1}; wall indices run from {@code r} to {@code r + walls.size() - 1}.
     */
    static List<List<Integer>> groupsTogether(List<SurroundTarget> rooms, List<SurroundTarget> walls) {
        int r = rooms.size();
        int[] parent = new int[r + walls.size()];
        for (int i = 0; i < parent.length; i++) {
            parent[i] = i;
        }
        // Connect rooms with touching walls
        for (int i = 0; i < r; i++) {
            for (int j = 0; j < walls.size(); j++) {
                if (SurroundTarget.areTogether(Collections.singletonList(rooms.get(i).getRoom()), walls.get(j).getWalls())) {
                    parent[SurroundTarget.find(parent, i)] = SurroundTarget.find(parent, r + j);
                }
            }
        }
        // Collect clusters by their union-find root
        Map<Integer, List<Integer>> byRoot = new LinkedHashMap<>();
        for (int i = 0; i < parent.length; i++) {
            byRoot.computeIfAbsent(SurroundTarget.find(parent, i), k -> new ArrayList<>()).add(i);
        }
        return new ArrayList<>(byRoot.values());
    }

    /**
     * Displays a multi-group selection dialog allowing individual configuration of each mixed cluster,
     * along with a master "Apply to all groups" dropdown.
     *
     * @return array of chosen option indices per mixed group, or null if cancelled
     */
    private static int[] askGroupByGroup(List<List<Integer>> mixed, List<SurroundTarget> rooms,
            List<SurroundTarget> walls, ResourceBundle resource, String title) {
        String[] options = {UiKit.text(resource, "floorOption", "Floor"), UiKit.text(resource, "wallsOption", "Walls"),
            UiKit.text(resource, "bothOption", "Both")};
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = 0;
        c.gridwidth = 2;
        c.anchor = GridBagConstraints.LINE_START;
        c.insets = new Insets(0, 0, 8, 0);
        panel.add(new JLabel(UiKit.text(resource, "askFloorOrWallsGroups",
                "Adjacent rooms and walls are selected. Indicate what to surround in each group:")), c);
        c.gridwidth = 1;
        c.insets = new Insets(2, 0, 2, 10);
        List<JComboBox<String>> combos = new ArrayList<>();
        for (int g = 0; g < mixed.size(); g++) {
            c.gridy++;
            c.gridx = 0;
            panel.add(new JLabel(describe(mixed.get(g), rooms, walls, resource, g + 1)), c);
            c.gridx = 1;
            JComboBox<String> combo = new JComboBox<>(options);
            combos.add(combo);
            panel.add(combo, c);
        }
        c.gridy++;
        c.gridx = 0;
        c.gridwidth = 2;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(8, 0, 6, 0);
        panel.add(new JSeparator(), c);
        c.gridy++;
        c.gridwidth = 1;
        c.fill = GridBagConstraints.NONE;
        c.insets = new Insets(2, 0, 2, 10);
        panel.add(new JLabel(UiKit.text(resource, "allGroupsOption", "Apply to all groups:")), c);
        c.gridx = 1;
        JComboBox<String> all = new JComboBox<>(options);
        all.setSelectedIndex(-1);
        all.addActionListener(e -> {
            if (all.getSelectedIndex() >= 0) {
                for (JComboBox<String> combo : combos) {
                    combo.setSelectedIndex(all.getSelectedIndex());
                }
            }
        });
        panel.add(all, c);
        int result = JOptionPane.showConfirmDialog(null, panel, title, JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.QUESTION_MESSAGE);
        if (result != JOptionPane.OK_OPTION) {
            return null;
        }
        int[] choices = new int[mixed.size()];
        for (int g = 0; g < mixed.size(); g++) {
            choices[g] = Math.max(0, combos.get(g).getSelectedIndex());
        }
        return choices;
    }

    /**
     * Formats a human-readable description for a mixed group, e.g. "Group 1: Room 1 (Kitchen), Walls 2".
     *
     * @param number   1-based group display index
     */
    private static String describe(List<Integer> group, List<SurroundTarget> rooms, List<SurroundTarget> walls,
            ResourceBundle resource, int number) {
        List<String> parts = new ArrayList<>();
        for (int index : group) {
            if (index < rooms.size()) {
                SurroundTarget room = rooms.get(index);
                String part = MessageFormat.format(UiKit.text(resource, "sidesRoomDefault", "Room {0}"), room.getNumber());
                String name = room.getRoom().getName();
                parts.add(name != null && !name.trim().isEmpty() ? part + " (" + name.trim() + ")" : part);
            } else {
                parts.add(MessageFormat.format(UiKit.text(resource, "wallsName", "Walls {0}"),
                        walls.get(index - rooms.size()).getNumber()));
            }
        }
        return MessageFormat.format(UiKit.text(resource, "groupLabel", "Group {0}:"), number) + " " + String.join(", ", parts);
    }
}
