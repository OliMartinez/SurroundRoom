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
import java.util.Arrays;
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

    static final int FLOOR = 0, WALLS = 1, BOTH = 2;

    private FloorOrWallsChooser() {
    }

    static List<SurroundTarget> choose(List<SurroundTarget> rooms, List<SurroundTarget> walls,
            ResourceBundle resource, String title) {
        List<List<Integer>> mixed = new ArrayList<>();
        for (List<Integer> group : groupsTogether(rooms, walls)) {
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
            String cancel = UIManager.getString("OptionPane.cancelButtonText");
            if (cancel == null) {
                cancel = "Cancel";
            }
            String floorLabel = UiKit.text(resource, "floorOption", "Floor");
            int choice = JOptionPane.showOptionDialog(null,
                    UiKit.text(resource, "askFloorOrWalls", "Adjacent rooms and walls are selected. What would you like to surround?"),
                    title, JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null,
                    new Object[]{floorLabel, UiKit.text(resource, "wallsOption", "Walls"),
                        UiKit.text(resource, "bothOption", "Both"), cancel},
                    floorLabel);
            if (choice < 0 || choice > BOTH) {
                return null;
            }
            choices[0] = choice;
        } else if (mixed.size() > 1) {
            choices = askGroupByGroup(mixed, rooms, walls, resource, title);
            if (choices == null) {
                return null;
            }
        }

        boolean[] keep = new boolean[rooms.size() + walls.size()];
        Arrays.fill(keep, true);
        for (int g = 0; g < mixed.size(); g++) {
            for (int index : mixed.get(g)) {
                boolean isRoom = index < rooms.size();
                keep[index] = choices[g] == BOTH || (isRoom ? choices[g] == FLOOR : choices[g] == WALLS);
            }
        }

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

    /** Partitions rooms (0..r-1) and walls (r..r+w-1) into touching connected components via Union-Find. */
    static List<List<Integer>> groupsTogether(List<SurroundTarget> rooms, List<SurroundTarget> walls) {
        int r = rooms.size();
        int[] parent = new int[r + walls.size()];
        for (int i = 0; i < parent.length; i++) {
            parent[i] = i;
        }
        for (int i = 0; i < r; i++) {
            for (int j = 0; j < walls.size(); j++) {
                if (SurroundTarget.areTogether(Collections.singletonList(rooms.get(i).getRoom()), walls.get(j).getWalls())) {
                    parent[SurroundTarget.find(parent, i)] = SurroundTarget.find(parent, r + j);
                }
            }
        }
        Map<Integer, List<Integer>> byRoot = new LinkedHashMap<>();
        for (int i = 0; i < parent.length; i++) {
            byRoot.computeIfAbsent(SurroundTarget.find(parent, i), k -> new ArrayList<>()).add(i);
        }
        return new ArrayList<>(byRoot.values());
    }

    private static int[] askGroupByGroup(List<List<Integer>> mixed, List<SurroundTarget> rooms,
            List<SurroundTarget> walls, ResourceBundle resource, String title) {
        String[] options = {UiKit.text(resource, "floorOption", "Floor"), UiKit.text(resource, "wallsOption", "Walls"),
            UiKit.text(resource, "bothOption", "Both")};
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0; c.gridy = 0; c.gridwidth = 2;
        c.anchor = GridBagConstraints.LINE_START;
        c.insets = new Insets(0, 0, 8, 0);
        panel.add(new JLabel(UiKit.text(resource, "askFloorOrWallsGroups",
                "Adjacent rooms and walls are selected. Indicate what to surround in each group:")), c);
        c.gridwidth = 1;
        c.insets = new Insets(2, 0, 2, 10);
        List<JComboBox<String>> combos = new ArrayList<>();
        for (int g = 0; g < mixed.size(); g++) {
            c.gridy++; c.gridx = 0;
            panel.add(new JLabel(describe(mixed.get(g), rooms, walls, resource, g + 1)), c);
            c.gridx = 1;
            JComboBox<String> combo = new JComboBox<>(options);
            combos.add(combo);
            panel.add(combo, c);
        }
        c.gridy++; c.gridx = 0; c.gridwidth = 2; c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(8, 0, 6, 0);
        panel.add(new JSeparator(), c);
        c.gridy++; c.gridwidth = 1; c.fill = GridBagConstraints.NONE;
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
        if (JOptionPane.showConfirmDialog(null, panel, title, JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.QUESTION_MESSAGE) != JOptionPane.OK_OPTION) {
            return null;
        }
        int[] choices = new int[mixed.size()];
        for (int g = 0; g < mixed.size(); g++) {
            choices[g] = Math.max(0, combos.get(g).getSelectedIndex());
        }
        return choices;
    }

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
