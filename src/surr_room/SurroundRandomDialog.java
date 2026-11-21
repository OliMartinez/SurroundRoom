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
import com.eteks.sweethome3d.model.HomeTexture;
import com.eteks.sweethome3d.model.UserPreferences;
import com.eteks.sweethome3d.viewcontroller.HomeController;
import com.eteks.sweethome3d.viewcontroller.TextureChoiceController;
import com.eteks.sweethome3d.viewcontroller.TextureChoiceView;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Image;
import java.awt.Insets;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.ResourceBundle;
import java.util.Set;
import javax.swing.AbstractCellEditor;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.DefaultCellEditor;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JColorChooser;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableCellEditor;
import javax.swing.table.TableColumn;

/**
 * Dialogs of the random properties ({@link #showRandomDialog}: values, offsets or percentages, a share of the
 * objects, mirroring, draws per stacked level) and of the gradual ones ({@link #showGradualDialog}: linear,
 * ping-pong or steps, along the outline, each side or the levels). Properties: color or texture, shininess,
 * light power, elevation, height, depth, tilts and orientation.
 */
public final class SurroundRandomDialog {

    /** Private constructor preventing instantiation of static utility class. */
    private SurroundRandomDialog() {
    }

    /** Property naming lookup table: maps internal property identifier to localization bundle key and fallback text. */
    private static final String[][] PROPERTY_NAMES = {
        {"look", "color_texture", "Color/texture"},
        {"shininess", "shininess", "Shininess"},
        {"light_power", "light_power", "Light power"},
        {"elevation", "prop_elevation", "Elevation above floor"},
        {"height", "t7Height", "Height"},
        {"depth", "t7Depth", "Depth"},
        {"pitch", "prop_pitch", "X tilt (pitch)"},
        {"roll", "prop_roll", "Y tilt (roll)"},
        {"angle_jitter", "prop_angle_jitter", "Z orientation offset"}};

    /** Resolves the localized display name for the specified property identifier. */
    public static String propertyDisplayName(ResourceBundle resource, String prop) {
        for (String[] name : PROPERTY_NAMES) {
            if (name[0].equals(prop)) {
                return UiKit.text(resource, name[1], name[2]);
            }
        }
        return UiKit.text(resource, prop, prop);
    }

    /**
     * Resolves localized labels for the three numerical variation modes:
     * Absolute Value (0), Additive Offset (1), and Percentage Scaling (2).
     *
     * @return array of 3 localized mode strings
     */
    private static String[] modeLabels(ResourceBundle resource) {
        return new String[]{
            UiKit.text(resource, "randomValue", "Value between"),
            UiKit.text(resource, "randomAdd", "Add between"),
            UiKit.text(resource, "randomPercent", "Percentage between")
        };
    }

    /**
     * Resolves the measurement unit symbol for a property.
     *
     * @return unit string (e.g. "%", "cm", "&deg;", or empty string)
     */
    public static String propertyUnit(String prop) {
        if ("shininess".equals(prop) || "light_power".equals(prop)) {
            return "%";
        }
        if ("elevation".equals(prop) || "height".equals(prop) || "depth".equals(prop)) {
            return "cm";
        }
        if ("pitch".equals(prop) || "roll".equals(prop) || "angle_jitter".equals(prop)) {
            return "\u00b0";
        }
        return "";
    }

    /**
     * Filters {@link SurroundRandomSettings#RANDOM_PROPS} to only those properties that are applicable given the
     * selected template objects, current layout geometry settings, and the counterpart mode's active properties.
     */
    public static String[] applicableProperties(List<HomePieceOfFurniture> templatePieces,
                                                SurroundConfig currentConfig,
                                                SurroundRandomSettings otherActiveSettings) {
        boolean hasLight = MaterialUtils.containsLight(templatePieces);
        boolean canTilt = MaterialUtils.containsHorizontallyRotatable(templatePieces);
        boolean canResize = MaterialUtils.containsResizable(templatePieces);

        int type = currentConfig != null ? currentConfig.surroundType : 1;
        boolean typeModifiesH = (type == 3 || type == 4 || type == 6) && currentConfig != null
                && (currentConfig.dimModifyMode == SurroundConfig.DIM_PROPORTIONAL
                || (currentConfig.dimModifyMode == SurroundConfig.DIM_SELECTED && currentConfig.dimModifyH));
        boolean typeModifiesD = (type == 3 || type == 4 || type == 6) && currentConfig != null
                && (currentConfig.dimModifyMode == SurroundConfig.DIM_PROPORTIONAL
                || (currentConfig.dimModifyMode == SurroundConfig.DIM_SELECTED && currentConfig.dimModifyD));
        boolean type7RandomizesH = type == 7 && currentConfig != null
                && (currentConfig.randomizeAll || currentConfig.randomizeHeight);
        boolean type7RandomizesD = type == 7 && currentConfig != null
                && (currentConfig.randomizeAll || currentConfig.randomizeDepth);
        boolean stackUsesPieceHeight = currentConfig != null && currentConfig.enableVerticalStacking
                && !currentConfig.useCustomStackElevation;
        boolean stackControlsElevation = currentConfig != null && currentConfig.enableVerticalStacking;
        boolean cornerUsesPieceDepth = currentConfig != null && type != 7 && (currentConfig.enableCornerHandling || currentConfig.customSideEnds);

        List<String> list = new ArrayList<>();
        for (String p : SurroundRandomSettings.RANDOM_PROPS) {
            if ("light_power".equals(p) && !hasLight) {
                continue;
            }
            if (("pitch".equals(p) || "roll".equals(p)) && !canTilt) {
                continue;
            }
            if ("height".equals(p) && (!canResize || typeModifiesH || type7RandomizesH || stackUsesPieceHeight)) {
                continue;
            }
            if ("depth".equals(p) && (!canResize || typeModifiesD || type7RandomizesD || cornerUsesPieceDepth)) {
                continue;
            }
            if ("elevation".equals(p) && stackControlsElevation) {
                continue;
            }
            if (otherActiveSettings != null && Boolean.TRUE.equals(otherActiveSettings.enabled.get(p))) {
                continue;
            }
            list.add(p);
        }
        return list.toArray(new String[0]);
    }

    /**
     * Builds and configures the interactive property variation JTable.
     *
     * @param gradual        true for gradual dialog, false for random dialog
     * @param prefs          Sweet Home 3D user preferences
     */
    private static JTable buildPropertyTable(final ResourceBundle resource, final String[] props,
            final SurroundRandomSettings working, final List<HomePieceOfFurniture> templatePieces,
            final String[] modeLabels, final boolean gradual, final JComboBox<String> patternCombo,
            final UserPreferences prefs, final HomeController homeController) {
        final boolean hasLight = MaterialUtils.containsLight(templatePieces);
        final String wholeText = UiKit.text(resource, "randomWholeShort", "Whole object");
        final String materialsPattern = UiKit.text(resource, "randomMaterialsShort", "{0} materials");
        final String configureText = UiKit.text(resource, "randomLookConfigure", "Configure\u2026");

        final AbstractTableModel model = new AbstractTableModel() {
            @Override
            public int getRowCount() {
                return props.length;
            }

            @Override
            public int getColumnCount() {
                return 6;
            }

            @Override
            public String getColumnName(int col) {
                switch (col) {
                    case 0: return "";
                    case 1: return UiKit.text(resource, "randomProperty", "Property");
                    case 2: return UiKit.text(resource, "randomApplyToColumn", "Apply to");
                    case 3: return UiKit.text(resource, "randomMode", "Mode");
                    case 4: return gradual ? UiKit.text(resource, "gradualStartCol", "Start") : UiKit.text(resource, "randomMin", "Min");
                    default:
                        if (!gradual) {
                            return UiKit.text(resource, "randomMax", "Max");
                        }
                        return (patternCombo != null && patternCombo.getSelectedIndex() == SurroundRandomSettings.PATTERN_STEP)
                                ? UiKit.text(resource, "gradualStepCol", "Step")
                                : UiKit.text(resource, "gradualEndCol", "End");
                }
            }

            @Override
            public Class<?> getColumnClass(int col) {
                if (col == 0) return Boolean.class;
                if (col == 4 || col == 5) return Double.class;
                return String.class;
            }

            @Override
            public boolean isCellEditable(int row, int col) {
                if ("light_power".equals(props[row]) && !hasLight) return false;
                if (col == 0) return true;
                if (!Boolean.TRUE.equals(working.enabled.get(props[row]))) return false;
                if (col == 2) return SurroundRandomSettings.supportsMaterials(props[row]);
                if ("look".equals(props[row])) return col == 3;
                return col >= 3;
            }

            @Override
            public Object getValueAt(int row, int col) {
                String p = props[row];
                switch (col) {
                    case 0:
                        return working.enabled.get(p);
                    case 1: {
                        String unit = propertyUnit(p);
                        String label = propertyDisplayName(resource, p);
                        return unit.isEmpty() ? label : label + " (" + unit + ")";
                    }
                    case 2:
                        if (!Boolean.TRUE.equals(working.enabled.get(p)) || !SurroundRandomSettings.supportsMaterials(p)) {
                            return "";
                        }
                        return working.isWholeTarget(p) ? wholeText
                                : MessageFormat.format(materialsPattern, working.totalMaterialsCount(p));
                    case 3:
                        if (!Boolean.TRUE.equals(working.enabled.get(p))) {
                            return "";
                        }
                        if ("look".equals(p)) {
                            if (gradual) {
                                int c0 = working.look.startColor != null ? working.look.startColor : 0x3A7BD5;
                                int c1 = working.look.endColor != null ? working.look.endColor : 0xE67E22;
                                return String.format("#%06X \u2192 #%06X", c0 & 0xFFFFFF, c1 & 0xFFFFFF);
                            }
                            return formatLookSummary(resource, working.look);
                        }
                        return modeLabels[working.modes.get(p)];
                    case 4:
                        return (!Boolean.TRUE.equals(working.enabled.get(p)) || "look".equals(p)) ? null : working.mins.get(p);
                    case 5:
                        return (!Boolean.TRUE.equals(working.enabled.get(p)) || "look".equals(p)) ? null : working.maxs.get(p);
                    default:
                        return null;
                }
            }

            @Override
            public void setValueAt(Object val, int row, int col) {
                String p = props[row];
                if (col == 0) {
                    working.enabled.put(p, Boolean.TRUE.equals(val));
                    fireTableRowsUpdated(row, row);
                } else if (col == 3 && !"look".equals(p)) {
                    int prev = working.modes.get(p), next = prev;
                    for (int i = 0; i < modeLabels.length; i++) {
                        if (modeLabels[i].equals(val)) {
                            next = i;
                            break;
                        }
                    }
                    if (next != prev) {
                        working.modes.put(p, next);
                        if (next == SurroundRandomSettings.MODE_PERCENT) {
                            working.mins.put(p, gradual ? 80.0 : 100.0);
                            working.maxs.put(p, gradual ? 120.0 : 100.0);
                        } else if (next == SurroundRandomSettings.MODE_ADD) {
                            working.mins.put(p, 0.0);
                            working.maxs.put(p, gradual ? 20.0 : 0.0);
                        } else {
                            working.mins.put(p, 0.0);
                            working.maxs.put(p, 100.0);
                        }
                        fireTableRowsUpdated(row, row);
                    }
                } else if (col == 4 && val instanceof Number) {
                    working.mins.put(p, clampFor(p, working.modes.get(p), ((Number) val).doubleValue()));
                } else if (col == 5 && val instanceof Number) {
                    working.maxs.put(p, clampFor(p, working.modes.get(p), ((Number) val).doubleValue()));
                }
            }
        };

        final JTable table = new JTable(model);
        table.setRowHeight(26);
        table.setFillsViewportHeight(true);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);

        checkColumn(table);
        columnWidths(table, 34, gradual ? 165 : 150, 130, 190, 75, 75);

        table.getColumnModel().getColumn(2).setCellRenderer(new DefaultTableCellRenderer() {
            private final JButton button = new JButton();

            @Override
            public Component getTableCellRendererComponent(JTable tbl, Object value, boolean isSelected,
                    boolean hasFocus, int row, int column) {
                String p = props[row];
                if (Boolean.TRUE.equals(working.enabled.get(p)) && SurroundRandomSettings.supportsMaterials(p)) {
                    button.setText(value != null ? value.toString() + " \u2026" : wholeText);
                    button.setHorizontalAlignment(SwingConstants.LEFT);
                    button.setMargin(new Insets(1, 6, 1, 6));
                    return button;
                }
                return super.getTableCellRendererComponent(tbl, "", isSelected, hasFocus, row, column);
            }
        });
        table.getColumnModel().getColumn(2).setCellEditor(new ButtonLaunchEditor(table, row -> {
            String p = props[row];
            if (SurroundRandomSettings.supportsMaterials(p)) {
                showRandomTargetDialog(table, resource, p, working, templatePieces);
                model.fireTableRowsUpdated(row, row);
            }
        }));

        final DefaultCellEditor comboEditor = new DefaultCellEditor(new JComboBox<>(modeLabels));
        final ButtonLaunchEditor lookEditor = new ButtonLaunchEditor(table, row -> {
            if (gradual) {
                Color initStart = new Color(working.look.startColor != null ? working.look.startColor : 0x3A7BD5);
                Color c0 = JColorChooser.showDialog(table, UiKit.text(resource, "gradualPickStartColor", "Start color"), initStart);
                if (c0 != null) {
                    working.look.startColor = c0.getRGB() & 0xFFFFFF;
                    Color initEnd = new Color(working.look.endColor != null ? working.look.endColor : 0xE67E22);
                    Color c1 = JColorChooser.showDialog(table, UiKit.text(resource, "gradualPickEndColor", "End color"), initEnd);
                    if (c1 != null) {
                        working.look.endColor = c1.getRGB() & 0xFFFFFF;
                    }
                    model.fireTableRowsUpdated(row, row);
                }
            } else {
                if (!working.look.initialized) {
                    working.look.choices.clear();
                    working.look.choices.addAll(MaterialUtils.defaultLookChoices(templatePieces,
                            working.isWholeTarget("look"), working.allSelectedMaterials("look")));
                    working.look.initialized = true;
                }
                if (showRandomLookDialog(table, resource, prefs, homeController, working.look)) {
                    if (working.look.hasAnything()) {
                        working.enabled.put("look", true);
                    }
                    model.fireTableRowsUpdated(row, row);
                }
            }
        });

        table.getColumnModel().getColumn(3).setCellRenderer(new DefaultTableCellRenderer() {
            private final JPanel lookPanel = new JPanel(new BorderLayout(6, 0));
            private final JLabel summaryLabel = new JLabel();
            private final JButton cfgBtn = new JButton(configureText);
            {
                lookPanel.setBorder(new EmptyBorder(1, 4, 1, 2));
                cfgBtn.setMargin(new Insets(1, 8, 1, 8));
                lookPanel.add(summaryLabel, BorderLayout.CENTER);
                lookPanel.add(cfgBtn, BorderLayout.EAST);
            }

            @Override
            public Component getTableCellRendererComponent(JTable tbl, Object value, boolean isSelected,
                    boolean hasFocus, int row, int column) {
                if (!Boolean.TRUE.equals(working.enabled.get(props[row]))) {
                    return super.getTableCellRendererComponent(tbl, "", isSelected, hasFocus, row, column);
                }
                if ("look".equals(props[row])) {
                    summaryLabel.setText(value != null ? value.toString() : "");
                    summaryLabel.setForeground(isSelected ? tbl.getSelectionForeground() : tbl.getForeground());
                    lookPanel.setBackground(isSelected ? tbl.getSelectionBackground() : tbl.getBackground());
                    return lookPanel;
                }
                return super.getTableCellRendererComponent(tbl, value, isSelected, hasFocus, row, column);
            }
        });
        table.getColumnModel().getColumn(3).setCellEditor(new AbstractCellEditorWrapper(row ->
                "look".equals(props[row]) ? lookEditor : comboEditor));

        DefaultTableCellRenderer numRenderer = new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable tbl, Object value, boolean isSelected,
                    boolean hasFocus, int row, int column) {
                String p = props[row];
                if (!Boolean.TRUE.equals(working.enabled.get(p)) || "look".equals(p)) {
                    return super.getTableCellRendererComponent(tbl, "", isSelected, hasFocus, row, column);
                }
                String text = "";
                if (value instanceof Number) {
                    double d = ((Number) value).doubleValue();
                    String unit = (working.modes.get(p) == SurroundRandomSettings.MODE_PERCENT) ? "%" : propertyUnit(p);
                    text = (d == Math.rint(d) ? String.format("%.0f", d) : String.format("%.2f", d))
                            + (unit.isEmpty() ? "" : " " + unit);
                }
                JLabel lbl = (JLabel) super.getTableCellRendererComponent(tbl, text, isSelected, hasFocus, row, column);
                lbl.setHorizontalAlignment(SwingConstants.RIGHT);
                return lbl;
            }
        };
        table.getColumnModel().getColumn(4).setCellRenderer(numRenderer);
        table.getColumnModel().getColumn(5).setCellRenderer(numRenderer);
        for (int column = 4; column <= 5; column++) {
            boolean isMin = column == 4;
            table.getColumnModel().getColumn(column).setCellEditor(new NumberEditor((row, value) -> {
                String p = props[row];
                double[] limits = limits(p, working.modes.get(p));
                return new double[]{isMin ? working.mins.get(p) : working.maxs.get(p), limits[0], limits[1], limits[2]};
            }));
        }
        return table;
    }

    /**
     * Displays the modal configuration dialog for stochastic (random) property variations.
     *
     * @return true if user confirmed dialog with OK, false if cancelled
     */
    public static boolean showRandomDialog(Component parent, ResourceBundle resource,
                                           UserPreferences prefs, HomeController homeController,
                                           SurroundRandomSettings randomRanges,
                                           List<HomePieceOfFurniture> templatePieces,
                                           SurroundConfig currentConfig,
                                           SurroundRandomSettings activeGradualSettings) {
        final String[] props = applicableProperties(templatePieces, currentConfig, activeGradualSettings);
        final String[] modeLabels = modeLabels(resource);
        final SurroundRandomSettings working = randomRanges.copy();
        if (!MaterialUtils.containsLight(templatePieces)) {
            working.enabled.put("light_power", false);
        }
        final JTable table = buildPropertyTable(resource, props, working, templatePieces, modeLabels, false, null, prefs, homeController);

        final JCheckBox objectSubsetCheck = new JCheckBox(
                UiKit.text(resource, "randomObjectSubset", "Apply to random objects of the surround, probability:"),
                working.randomObjectSubset);
        final JSpinner objectProbSpinner = UiKit.spinner(new SpinnerNumberModel(working.objectProbability, 0.0, 100.0, 5.0), 4);
        JPanel objectSubsetRow = probabilityRow(objectSubsetCheck, objectProbSpinner);
        final JCheckBox mirrorCheck = new JCheckBox(
                UiKit.text(resource, "randomMirror", "Random mirroring, probability:"), working.mirror);
        final JSpinner mirrorProbSpinner = UiKit.spinner(new SpinnerNumberModel(working.mirrorProbability, 0.0, 100.0, 5.0), 4);
        JPanel mirrorRow = probabilityRow(mirrorCheck, mirrorProbSpinner);

        final JCheckBox stackRandomCheck = new JCheckBox(
                UiKit.text(resource, "randomStackIndependent", "Draw separately for each vertically stacked level"),
                working.independentStackRandom);
        final JCheckBox cornerRandomCheck = new JCheckBox(
                UiKit.text(resource, "randomApplyToCorners", "Apply random properties to corner pieces too"),
                working.applyToCorners);
        boolean stackActive = currentConfig == null || currentConfig.enableVerticalStacking;
        boolean cornersActive = hasCornerPieces(currentConfig);
        stackRandomCheck.setVisible(stackActive);
        cornerRandomCheck.setVisible(cornersActive);
        JPanel surroundExtrasRow = UiKit.row(8, stackRandomCheck, cornerRandomCheck);
        surroundExtrasRow.setVisible(stackActive || cornersActive);

        JPanel southPanel = new JPanel();
        southPanel.setLayout(new BoxLayout(southPanel, BoxLayout.Y_AXIS));
        for (JComponent comp : new JComponent[]{objectSubsetRow, mirrorRow, surroundExtrasRow}) {
            comp.setAlignmentX(Component.LEFT_ALIGNMENT);
            southPanel.add(comp);
        }

        JScrollPane scroll = new JScrollPane(table);
        scroll.setPreferredSize(new Dimension(640, 115));

        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.setBorder(new EmptyBorder(6, 6, 2, 6));
        panel.add(scroll, BorderLayout.CENTER);
        panel.add(southPanel, BorderLayout.SOUTH);

        int res = JOptionPane.showConfirmDialog(parent, panel,
                UiKit.text(resource, "randomTitle", "Random property variations"),
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (res == JOptionPane.OK_OPTION) {
            stopEditing(table);
            working.randomObjectSubset = objectSubsetCheck.isSelected();
            working.objectProbability = ((Number) objectProbSpinner.getValue()).doubleValue();
            working.mirror = mirrorCheck.isSelected();
            working.mirrorProbability = ((Number) mirrorProbSpinner.getValue()).doubleValue();
            working.independentStackRandom = stackRandomCheck.isSelected();
            working.applyToCorners = cornerRandomCheck.isSelected();
            randomRanges.copyFrom(working);
            return true;
        }
        return false;
    }

    /**
     * Whether objects are placed over the vertices: perpendicular pieces in the corners, or the objects of
     * type 7 turned over them (unknown configuration counts as yes).
     */
    private static boolean hasCornerPieces(SurroundConfig config) {
        return config == null || (config.enableCornerHandling
                && (config.surroundType == 7 || config.placeOrthogonalCornerPiece));
    }

    /** A check box followed by its probability, shown only while it's checked. */
    private static JPanel probabilityRow(JCheckBox check, JSpinner probability) {
        JLabel percent = new JLabel("%");
        Runnable sync = () -> {
            probability.setVisible(check.isSelected());
            percent.setVisible(check.isSelected());
            UiKit.refit(check);
        };
        check.addActionListener(e -> sync.run());
        sync.run();
        return UiKit.row(4, check, probability, percent);
    }

    /**
     * Displays the modal configuration dialog for deterministic gradual property changes.
     *
     * @return true if user confirmed with OK, false if cancelled
     */
    public static boolean showGradualDialog(Component parent, ResourceBundle resource,
                                            UserPreferences prefs, HomeController homeController,
                                            SurroundRandomSettings gradualSettings,
                                            List<HomePieceOfFurniture> templatePieces,
                                            SurroundConfig currentConfig,
                                            SurroundRandomSettings activeRandomSettings) {
        final String[] props = applicableProperties(templatePieces, currentConfig, activeRandomSettings);
        final String[] modeLabels = modeLabels(resource);
        final SurroundRandomSettings working = gradualSettings.copy();
        final String[] patternNames = {
            UiKit.text(resource, "gradualLinear", "Linear (start to end)"),
            UiKit.text(resource, "gradualPingPong", "Back and forth (start \u2192 end \u2192 start)"),
            UiKit.text(resource, "gradualStep", "Fixed step per object")
        };
        final JComboBox<String> patternCombo = new JComboBox<>(patternNames);
        patternCombo.setSelectedIndex(Math.max(0, Math.min(patternNames.length - 1, working.gradualPattern)));

        final boolean hasStacking = currentConfig != null && currentConfig.enableVerticalStacking;
        final String[] scopeNames = Arrays.copyOf(new String[]{
            UiKit.text(resource, "gradualScopeOutline", "Around the whole outline"),
            UiKit.text(resource, "gradualScopeSide", "Restart on each side"),
            UiKit.text(resource, "gradualScopeLevel", "Per vertically stacked level")
        }, hasStacking ? 3 : 2);
        final JComboBox<String> scopeCombo = new JComboBox<>(scopeNames);
        int initScope = (!hasStacking && working.gradualScope == SurroundRandomSettings.SCOPE_PER_LEVEL)
                ? SurroundRandomSettings.SCOPE_WHOLE_OUTLINE : working.gradualScope;
        scopeCombo.setSelectedIndex(Math.max(0, Math.min(scopeNames.length - 1, initScope)));

        final JTable table = buildPropertyTable(resource, props, working, templatePieces, modeLabels, true, patternCombo, prefs, homeController);
        patternCombo.addActionListener(e -> {
            working.gradualPattern = patternCombo.getSelectedIndex();
            table.getColumnModel().getColumn(5).setHeaderValue(table.getModel().getColumnName(5));
            table.getTableHeader().repaint();
        });
        scopeCombo.addActionListener(e -> working.gradualScope = scopeCombo.getSelectedIndex());

        final JCheckBox cornerGradualCheck = new JCheckBox(
                UiKit.text(resource, "gradualApplyToCorners", "Include corner pieces in the gradual progression too"),
                working.applyToCorners);
        boolean cornersRelevant = hasCornerPieces(currentConfig);
        cornerGradualCheck.setVisible(cornersRelevant);

        JPanel topControls = UiKit.row(8,
                new JLabel(UiKit.text(resource, "gradualPatternLabel", "Progression:")), patternCombo,
                Box.createHorizontalStrut(8),
                new JLabel(UiKit.text(resource, "gradualScopeLabel", "Scope:")), scopeCombo);

        JScrollPane scroll = new JScrollPane(table);
        scroll.setPreferredSize(new Dimension(660, Math.min(230, Math.max(125, props.length * 28 + 32))));

        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.setBorder(new EmptyBorder(6, 6, 2, 6));
        panel.add(topControls, BorderLayout.NORTH);
        panel.add(scroll, BorderLayout.CENTER);
        if (cornersRelevant) {
            panel.add(cornerGradualCheck, BorderLayout.SOUTH);
        }

        int res = JOptionPane.showConfirmDialog(parent, panel,
                UiKit.text(resource, "gradualTitle", "Gradual property changes"),
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (res == JOptionPane.OK_OPTION) {
            stopEditing(table);
            working.gradualPattern = patternCombo.getSelectedIndex();
            working.gradualScope = scopeCombo.getSelectedIndex();
            working.applyToCorners = cornerGradualCheck.isSelected();
            gradualSettings.copyFrom(working);
            return true;
        }
        return false;
    }

    private static String formatLookSummary(ResourceBundle resource, SurroundRandomSettings.RandomLook look) {
        List<String> parts = new ArrayList<>();
        if (look.fromList && !look.choices.isEmpty()) {
            parts.add(MessageFormat.format(UiKit.text(resource, "randomListCount", "{0} colors/textures"), look.choices.size()));
        }
        if (look.varyComponents) {
            parts.add(UiKit.text(resource, "randomComponentsShort", "Varies R, G, B"));
        }
        return parts.isEmpty() ? "\u2014" : String.join(" + ", parts);
    }

    /**
     * Displays a dialog allowing the user to choose whether a property variation applies to the whole object
     * or to specific extracted material parts of the template pieces.
     */
    public static void showRandomTargetDialog(Component parent, ResourceBundle resource, String property,
                                              SurroundRandomSettings working, List<HomePieceOfFurniture> templatePieces) {
        String propLabel = propertyDisplayName(resource, property);
        String title = MessageFormat.format(
                UiKit.text(resource, "randomTargetTitle", "Random values of {0}: materials"), propLabel);

        List<HomePieceOfFurniture> repTop = MaterialUtils.topPiecesOf(templatePieces);
        Map<HomePieceOfFurniture, Set<String>> initByPiece = new IdentityHashMap<>();
        Set<String> flatInit = working.materials.get(property);
        for (HomePieceOfFurniture p : repTop) {
            Set<String> perPiece = working.materialsForPiece(property, p);
            Set<String> init = new LinkedHashSet<>();
            if (perPiece != null && !perPiece.isEmpty()) {
                init.addAll(perPiece);
            } else if (flatInit != null) {
                init.addAll(flatInit);
            }
            initByPiece.put(p, init);
        }
        final MultiPieceTableGroup repGroup = buildMultiPieceTableGroup(
                resource, repTop, property, false, initByPiece, repTop.isEmpty()
                        ? UiKit.text(resource, "noReplacementMaterials", "Select the object first to list its materials.")
                        : UiKit.text(resource, "noModelMaterials", "The materials of this object couldn't be read."), null);

        boolean initialWhole = working.isWholeTarget(property) || !repGroup.hasAnyMaterials();
        final JRadioButton wholeBtn = new JRadioButton(
                UiKit.text(resource, "randomWholeModel", "Whole object (one value for the whole object)"), initialWhole);
        final JRadioButton matsBtn = new JRadioButton(
                UiKit.text(resource, "randomTheseMaterials", "These materials of the object (one random value for each):"),
                !initialWhole);
        matsBtn.setVisible(repGroup.hasAnyMaterials());
        ButtonGroup bg = new ButtonGroup();
        bg.add(wholeBtn);
        bg.add(matsBtn);

        // Without materials the panel stays visible to say why
        Runnable syncVisible = () -> {
            repGroup.component.setVisible(matsBtn.isSelected() || !repGroup.hasAnyMaterials());
            UiKit.refit(repGroup.component);
        };
        wholeBtn.addActionListener(e -> syncVisible.run());
        matsBtn.addActionListener(e -> syncVisible.run());
        syncVisible.run();

        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        JLabel applyLabel = new JLabel(UiKit.text(resource, "randomApplyTo", "Apply to:"));
        applyLabel.setFont(applyLabel.getFont().deriveFont(Font.BOLD));
        top.add(applyLabel);
        top.add(Box.createVerticalStrut(4));
        top.add(wholeBtn);
        top.add(matsBtn);

        JPanel panel = new JPanel(new BorderLayout(0, 6));
        panel.setBorder(new EmptyBorder(8, 8, 4, 8));
        panel.add(top, BorderLayout.NORTH);
        panel.add(repGroup.component, BorderLayout.CENTER);

        int res = JOptionPane.showConfirmDialog(parent, panel, title,
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (res == JOptionPane.OK_OPTION) {
            Map<HomePieceOfFurniture, Set<String>> chosenByPiece = repGroup.checkedByPiece();
            Set<String> union = repGroup.unionChecked();
            boolean useWhole = wholeBtn.isSelected() || union.isEmpty();
            working.whole.put(property, useWhole);
            Set<String> set = working.materials.get(property);
            set.clear();
            Map<HomePieceOfFurniture, Set<String>> targetMap = working.pieceMaterials.get(property);
            if (targetMap != null) {
                targetMap.clear();
            }
            if (!useWhole) {
                set.addAll(union);
                for (Map.Entry<HomePieceOfFurniture, Set<String>> e : chosenByPiece.entrySet()) {
                    working.ensureMaterialsForPiece(property, e.getKey()).addAll(e.getValue());
                }
            }
        }
    }

    /**
     * Displays a dialog for configuring weighted color/texture lists and RGB component variation.
     *
     * @return true if user confirmed with OK, false if cancelled
     */
    public static boolean showRandomLookDialog(Component parent, ResourceBundle resource,
                                               UserPreferences prefs, HomeController homeController,
                                               SurroundRandomSettings.RandomLook look) {
        final SurroundRandomSettings.RandomLook working = look.copy();
        final String[] modeLabels = modeLabels(resource);
        final String[] compLabels = {
            UiKit.text(resource, "randomRed", "Red") + " (0\u2013255)",
            UiKit.text(resource, "randomGreen", "Green") + " (0\u2013255)",
            UiKit.text(resource, "randomBlue", "Blue") + " (0\u2013255)"
        };

        final JCheckBox varyCheck = new JCheckBox(
                UiKit.text(resource, "randomVaryComponents", "Vary red, green and blue (of the drawn color or of the existing color):"),
                working.varyComponents);
        final JCheckBox sameCheck = new JCheckBox(
                UiKit.text(resource, "randomSameComponents", "Same draw for all three (red range): only lightens or darkens"),
                working.sameComponents);

        final AbstractTableModel compModel = new AbstractTableModel() {
            private final String[] cols = {
                UiKit.text(resource, "randomProperty", "Property"),
                UiKit.text(resource, "randomMode", "Mode"),
                UiKit.text(resource, "randomMin", "Min"),
                UiKit.text(resource, "randomMax", "Max")
            };

            @Override public int getRowCount() { return sameCheck.isSelected() ? 1 : 3; }
            @Override public int getColumnCount() { return 4; }
            @Override public String getColumnName(int c) { return cols[c]; }
            @Override public Class<?> getColumnClass(int c) { return c >= 2 ? Double.class : String.class; }

            @Override
            public boolean isCellEditable(int r, int c) {
                if (!varyCheck.isSelected() || c == 0) {
                    return false;
                }
                return !(sameCheck.isSelected() && r > 0);
            }

            @Override
            public Object getValueAt(int r, int c) {
                int src = (sameCheck.isSelected() && r > 0) ? 0 : r;
                if (c == 0) return compLabels[r];
                if (c == 1) return modeLabels[working.modes[src]];
                if (c == 2) return working.mins[src];
                return working.maxs[src];
            }

            @Override
            public void setValueAt(Object val, int r, int c) {
                if (c == 1) {
                    int prev = working.modes[r];
                    int next = prev;
                    for (int i = 0; i < modeLabels.length; i++) {
                        if (modeLabels[i].equals(val)) {
                            next = i;
                            break;
                        }
                    }
                    if (next != prev) {
                        working.modes[r] = next;
                        if (next == SurroundRandomSettings.MODE_PERCENT) {
                            working.mins[r] = 80.0;
                            working.maxs[r] = 120.0;
                        } else if (next == SurroundRandomSettings.MODE_ADD) {
                            working.mins[r] = -30.0;
                            working.maxs[r] = 30.0;
                        } else {
                            working.mins[r] = 0.0;
                            working.maxs[r] = 255.0;
                        }
                        fireTableDataChanged();
                    }
                } else if (c == 2 && val instanceof Number) {
                    working.mins[r] = clampRgb(working.modes[r], ((Number) val).doubleValue());
                    fireTableDataChanged();
                } else if (c == 3 && val instanceof Number) {
                    working.maxs[r] = clampRgb(working.modes[r], ((Number) val).doubleValue());
                    fireTableDataChanged();
                }
            }
        };

        final JTable compTable = new JTable(compModel);
        compTable.setRowHeight(24);
        compTable.setFillsViewportHeight(true);
        compTable.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);

        DefaultTableCellRenderer compNumRenderer = new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable tbl, Object value, boolean isSelected,
                                                           boolean hasFocus, int row, int column) {
                int src = (sameCheck.isSelected() && row > 0) ? 0 : row;
                String text = "";
                if (value instanceof Number) {
                    double d = ((Number) value).doubleValue();
                    String u = working.modes[src] == SurroundRandomSettings.MODE_PERCENT ? " %" : "";
                    text = String.format("%.0f%s", d, u);
                }
                JLabel lbl = (JLabel) super.getTableCellRendererComponent(tbl, text, isSelected, hasFocus, row, column);
                lbl.setHorizontalAlignment(SwingConstants.RIGHT);
                return lbl;
            }
        };
        // The columns are made again each time the rows change
        Runnable compColumns = () -> {
            columnWidths(compTable, 140, 175, 75, 75);
            compTable.getColumnModel().getColumn(1).setCellEditor(new DefaultCellEditor(new JComboBox<>(modeLabels)));
            for (int column = 2; column <= 3; column++) {
                boolean isMin = column == 2;
                compTable.getColumnModel().getColumn(column).setCellRenderer(compNumRenderer);
                compTable.getColumnModel().getColumn(column).setCellEditor(new NumberEditor((row, value) -> {
                    double[] limits = rgbLimits(working.modes[row]);
                    return new double[]{isMin ? working.mins[row] : working.maxs[row], limits[0], limits[1], 5.0};
                }));
            }
        };
        compColumns.run();

        JScrollPane compScroll = new JScrollPane(compTable);
        compScroll.setPreferredSize(new Dimension(520, 100));

        final JCheckBox fromListCheck = new JCheckBox(
                UiKit.text(resource, "randomFromList", "Draw a color or texture from this list:"), working.fromList);
        final ChoiceTableModel choiceModel = new ChoiceTableModel(resource, working.choices);
        final JTable choiceTable = new JTable(choiceModel);
        choiceTable.setRowHeight(26);
        choiceTable.setFillsViewportHeight(true);
        choiceTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        choiceTable.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
        choiceTable.getColumnModel().getColumn(0).setMinWidth(32);
        choiceTable.getColumnModel().getColumn(0).setMaxWidth(36);
        choiceTable.getColumnModel().getColumn(0).setCellRenderer(new IconRenderer());
        choiceTable.getColumnModel().getColumn(2).setPreferredWidth(75);
        choiceTable.getColumnModel().getColumn(2).setMaxWidth(90);
        choiceTable.getColumnModel().getColumn(2).setCellEditor(new NumberEditor((row, value) -> new double[]{
                value instanceof Number ? ((Number) value).doubleValue() : 1.0, 0.0, 1000.0, 0.5}));
        choiceTable.getColumnModel().getColumn(3).setPreferredWidth(85);
        choiceTable.getColumnModel().getColumn(3).setMaxWidth(100);
        choiceTable.getColumnModel().getColumn(4).setPreferredWidth(60);
        choiceTable.getColumnModel().getColumn(4).setMaxWidth(75);
        DefaultTableCellRenderer rightRenderer = new DefaultTableCellRenderer();
        rightRenderer.setHorizontalAlignment(SwingConstants.RIGHT);
        choiceTable.getColumnModel().getColumn(2).setCellRenderer(rightRenderer);
        choiceTable.getColumnModel().getColumn(3).setCellRenderer(rightRenderer);

        JScrollPane choiceScroll = new JScrollPane(choiceTable);
        choiceScroll.setPreferredSize(new Dimension(520, 150));

        final JButton addColorBtn = new JButton(UiKit.text(resource, "randomAddColor", "Add color\u2026"));
        addColorBtn.addActionListener(e -> {
            Color initial = Color.LIGHT_GRAY;
            int sel = choiceTable.getSelectedRow();
            if (sel >= 0 && sel < working.choices.size() && working.choices.get(sel).color != null) {
                initial = new Color(working.choices.get(sel).color);
            }
            Color picked = JColorChooser.showDialog(choiceTable, UiKit.text(resource, "color_texture", "Color/texture"), initial);
            if (picked != null) {
                working.choices.add(new SurroundRandomSettings.RandomLook.Choice(picked.getRGB() & 0xFFFFFF));
                choiceModel.fireTableDataChanged();
            }
        });

        final TextureChoiceController[] texCtrlHolder = new TextureChoiceController[1];
        JComponent texButtonsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        if (prefs != null) {
            try {
                com.eteks.sweethome3d.viewcontroller.ContentManager cm = homeController != null
                        ? homeController.getContentManager()
                        : new com.eteks.sweethome3d.swing.FileContentManager(prefs);
                final TextureChoiceController tc = new TextureChoiceController(
                        UiKit.text(resource, "color_texture", "Color/texture"), prefs,
                        new com.eteks.sweethome3d.swing.SwingViewFactory(), cm);
                texCtrlHolder[0] = tc;
                tc.addPropertyChangeListener(TextureChoiceController.Property.TEXTURE, new PropertyChangeListener() {
                    private boolean resetting;
                    @Override
                    public void propertyChange(PropertyChangeEvent evt) {
                        if (resetting) {
                            return;
                        }
                        HomeTexture newTex = tc.getTexture();
                        if (newTex != null) {
                            working.choices.add(new SurroundRandomSettings.RandomLook.Choice(newTex));
                            choiceModel.fireTableDataChanged();
                            resetting = true;
                            try {
                                tc.setTexture(null);
                            } finally {
                                resetting = false;
                            }
                        }
                    }
                });
                TextureChoiceView texView = tc.getView();
                if (texView instanceof JComponent) {
                    texButtonsPanel.add(new JLabel(UiKit.text(resource, "randomAddTexture", "Add texture:")));
                    texButtonsPanel.add((JComponent) texView);
                }
            } catch (Throwable ignored) {
            }
        }

        final JButton removeBtn = new JButton(UiKit.text(resource, "randomRemove", "Remove"));
        removeBtn.addActionListener(e -> {
            int sel = choiceTable.getSelectedRow();
            if (sel >= 0 && sel < working.choices.size()) {
                working.choices.remove(sel);
                choiceModel.fireTableDataChanged();
            }
        });

        JPanel listButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        listButtons.add(addColorBtn);
        listButtons.add(texButtonsPanel);
        listButtons.add(removeBtn);

        JPanel varyBox = new JPanel(new BorderLayout(0, 4));
        JPanel varyNorth = new JPanel();
        varyNorth.setLayout(new BoxLayout(varyNorth, BoxLayout.Y_AXIS));
        JPanel varyHeader = UiKit.row(4, varyCheck,
                UiKit.infoIcon(UiKit.text(resource, "randomVaryHint", "A textured material keeps its texture. Values stay within 0\u2013255.")));
        varyHeader.setAlignmentX(Component.LEFT_ALIGNMENT);
        sameCheck.setAlignmentX(Component.LEFT_ALIGNMENT);
        varyNorth.add(varyHeader);
        varyNorth.add(sameCheck);
        varyBox.add(varyNorth, BorderLayout.NORTH);
        varyBox.add(compScroll, BorderLayout.CENTER);

        JPanel listBox = new JPanel(new BorderLayout(0, 4));
        JPanel listHeader = UiKit.row(4, fromListCheck,
                UiKit.infoIcon(UiKit.text(resource, "randomListHint", "A double weight comes out twice as often. Only the list applies textures. Colors with \"Vary\" checked also receive the R, G, B variation.")));
        listBox.add(listHeader, BorderLayout.NORTH);
        listBox.add(choiceScroll, BorderLayout.CENTER);
        listBox.add(listButtons, BorderLayout.SOUTH);

        Runnable updateEnable = () -> {
            boolean varyOn = varyCheck.isSelected();
            working.varyComponents = varyOn;
            working.sameComponents = sameCheck.isSelected();
            working.fromList = fromListCheck.isSelected();
            sameCheck.setVisible(varyOn);
            compScroll.setVisible(varyOn);
            compModel.fireTableStructureChanged();
            compColumns.run();
            boolean listOn = fromListCheck.isSelected();
            choiceScroll.setVisible(listOn);
            listButtons.setVisible(listOn);
            UiKit.refit(varyBox);
        };
        varyCheck.addActionListener(e -> updateEnable.run());
        sameCheck.addActionListener(e -> updateEnable.run());
        fromListCheck.addActionListener(e -> updateEnable.run());
        updateEnable.run();

        JPanel main = new JPanel(new BorderLayout(0, 10));
        main.setBorder(new EmptyBorder(6, 6, 4, 6));
        main.add(listBox, BorderLayout.NORTH);
        main.add(varyBox, BorderLayout.CENTER);

        int res = JOptionPane.showConfirmDialog(parent, main,
                UiKit.text(resource, "randomLookTitle", "Random color/texture"),
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (res == JOptionPane.OK_OPTION) {
            stopEditing(compTable, choiceTable);
            look.varyComponents = working.varyComponents;
            look.sameComponents = working.sameComponents;
            look.modes = working.modes.clone();
            look.mins = working.mins.clone();
            look.maxs = working.maxs.clone();
            look.fromList = working.fromList;
            look.initialized = true;
            look.choices.clear();
            for (SurroundRandomSettings.RandomLook.Choice c : working.choices) {
                look.choices.add(c.copy());
            }
            return true;
        }
        return false;
    }

    /**
     * Resolves the bounding limits {min, max, step} of a property for the given variation mode.
     *
     * @param mode mode index (Value, Add, Percent)
     * @return array [min, max, step]
     */
    static double[] limits(String prop, int mode) {
        if (mode == SurroundRandomSettings.MODE_PERCENT) {
            return new double[]{0.0, 1000.0, 5.0};
        }
        if ("shininess".equals(prop) || "light_power".equals(prop)) {
            return new double[]{mode == SurroundRandomSettings.MODE_VALUE ? 0.0 : -100.0, 100.0, 5.0};
        }
        if ("pitch".equals(prop) || "roll".equals(prop) || "angle_jitter".equals(prop)) {
            // Full 360-degree rotation range so gradual change can sweep full circle
            return new double[]{-360.0, 360.0, 1.0};
        }
        if (("height".equals(prop) || "depth".equals(prop)) && mode == SurroundRandomSettings.MODE_VALUE) {
            return new double[]{0.5, 10000.0, 1.0};
        }
        return new double[]{-10000.0, 10000.0, 1.0};
    }

    /** Clamps a property value to legal limits for its mode. */
    static double clampFor(String prop, int mode, double v) {
        double[] limits = limits(prop, mode);
        return Math.max(limits[0], Math.min(limits[1], v));
    }

    /**
     * Resolves bounding limits {min, max} for RGB color components under the given mode.
     *
     * @return array [min, max]
     */
    private static double[] rgbLimits(int mode) {
        return new double[]{mode == SurroundRandomSettings.MODE_ADD ? -255.0 : 0.0,
                mode == SurroundRandomSettings.MODE_PERCENT ? 1000.0 : 255.0};
    }

    /** Clamps an RGB color component value to legal range and rounds to nearest integer. */
    static double clampRgb(int mode, double v) {
        double[] limits = rgbLimits(mode);
        return Math.max(limits[0], Math.min(limits[1], Math.rint(v)));
    }

    /** Concludes any active cell editing on the given tables. */
    private static void stopEditing(JTable... tables) {
        for (JTable table : tables) {
            if (table.isEditing()) {
                table.getCellEditor().stopCellEditing();
            }
        }
    }

    /** Sets preferred column widths across table columns. */
    private static void columnWidths(JTable table, int... widths) {
        for (int i = 0; i < widths.length; i++) {
            table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        }
    }

    /** Sets fixed narrow width for the leading checkbox column. */
    private static void checkColumn(JTable table) {
        TableColumn column = table.getColumnModel().getColumn(0);
        column.setMinWidth(32);
        column.setMaxWidth(36);
        column.setPreferredWidth(34);
    }

    // --- Helper table models and cell editors ---

    /** Container coordinating multi-piece tabbed material tables. */
    private static final class MultiPieceTableGroup {
        final JComponent component;
        final Map<HomePieceOfFurniture, CheckTableModel> modelsByPiece = new IdentityHashMap<>();

        MultiPieceTableGroup(JComponent component) {
            this.component = component;
        }

        boolean hasAnyMaterials() {
            for (CheckTableModel m : modelsByPiece.values()) {
                if (m.getRowCount() > 0) {
                    return true;
                }
            }
            return false;
        }

        Map<HomePieceOfFurniture, Set<String>> checkedByPiece() {
            Map<HomePieceOfFurniture, Set<String>> out = new IdentityHashMap<>();
            for (Map.Entry<HomePieceOfFurniture, CheckTableModel> e : modelsByPiece.entrySet()) {
                out.put(e.getKey(), e.getValue().checkedNames());
            }
            return out;
        }

        Set<String> unionChecked() {
            Set<String> out = new LinkedHashSet<>();
            for (CheckTableModel m : modelsByPiece.values()) {
                out.addAll(m.checkedNames());
            }
            return out;
        }
    }

    private static MultiPieceTableGroup buildMultiPieceTableGroup(
            ResourceBundle resource, List<HomePieceOfFurniture> pieces, String property, boolean forOrigin,
            Map<HomePieceOfFurniture, Set<String>> initialCheckedByPiece, String emptyText,
            Runnable onSelectionChange) {
        JTabbedPane tabs = pieces.size() > 1 ? new JTabbedPane(JTabbedPane.TOP, JTabbedPane.SCROLL_TAB_LAYOUT) : null;
        Map<HomePieceOfFurniture, CheckTableModel> models = new IdentityHashMap<>();
        JComponent single = null;
        int idx = 1;
        for (HomePieceOfFurniture piece : pieces.isEmpty() ? Collections.<HomePieceOfFurniture>singletonList(null) : pieces) {
            List<MaterialUtils.PieceMaterial> mats = piece != null
                    ? MaterialUtils.pieceMaterialsOf(piece, property, forOrigin)
                    : Collections.<MaterialUtils.PieceMaterial>emptyList();
            Set<String> init = piece != null && initialCheckedByPiece != null
                    ? initialCheckedByPiece.get(piece) : Collections.<String>emptySet();
            CheckTableModel model = new CheckTableModel(resource, mats, property, init);
            single = wrapMaterialTable(buildMaterialCheckTable(model, mats, property, onSelectionChange), mats.isEmpty(), emptyText);
            if (piece != null) {
                models.put(piece, model);
            }
            if (tabs != null) {
                String title = piece.getName() != null && !piece.getName().trim().isEmpty() ? piece.getName() : ("#" + idx);
                tabs.addTab(title, MaterialUtils.loadTextureIcon(piece.getIcon(), 22), single);
            }
            idx++;
        }
        MultiPieceTableGroup group = new MultiPieceTableGroup(tabs != null ? tabs : single);
        group.modelsByPiece.putAll(models);
        return group;
    }

    private static JComponent wrapMaterialTable(JTable table, boolean empty, String emptyText) {
        if (empty) {
            JLabel emptyLbl = new JLabel(emptyText, SwingConstants.CENTER);
            emptyLbl.setForeground(Color.GRAY);
            emptyLbl.setBorder(new EmptyBorder(18, 12, 18, 12));
            return emptyLbl;
        }
        JScrollPane sp = new JScrollPane(table);
        sp.setPreferredSize(new Dimension(500, 210));
        return sp;
    }

    private static JTable buildMaterialCheckTable(final CheckTableModel model,
                                                  final List<MaterialUtils.PieceMaterial> mats,
                                                  final String property,
                                                  final Runnable onSelectionChange) {
        final JTable table = new JTable(model);
        table.setRowHeight(52);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setFillsViewportHeight(true);

        checkColumn(table);
        TableColumn preview = table.getColumnModel().getColumn(1);
        preview.setMinWidth(54);
        preview.setMaxWidth(60);
        preview.setPreferredWidth(56);
        preview.setCellRenderer(new IconRenderer());

        table.getColumnModel().getColumn(3).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable tbl, Object value, boolean isSelected,
                                                           boolean hasFocus, int row, int column) {
                JLabel lbl = (JLabel) super.getTableCellRendererComponent(tbl, value, isSelected, hasFocus, row, column);
                if (row >= 0 && row < mats.size()) {
                    lbl.setIcon(MaterialUtils.swatchIcon(mats.get(row), property));
                } else {
                    lbl.setIcon(null);
                }
                return lbl;
            }
        });
        if (onSelectionChange != null) {
            table.getSelectionModel().addListSelectionListener(e -> {
                if (!e.getValueIsAdjusting()) {
                    onSelectionChange.run();
                }
            });
            model.addTableModelListener(e -> onSelectionChange.run());
        }
        return table;
    }

    /**
     * Table model presenting a checklist of materials extracted from a furniture piece,
     * including visual thumbnail previews and current property swatch icons.
     */
    private static final class CheckTableModel extends AbstractTableModel {
        private final List<MaterialUtils.PieceMaterial> items;
        private final boolean[] checked;
        private final Icon[] partIcons;
        private final String property;
        private final String[] columns;

        CheckTableModel(ResourceBundle res, List<MaterialUtils.PieceMaterial> items, String property, Collection<String> initiallyChecked) {
            this.items = items;
            this.property = property;
            this.checked = new boolean[items.size()];
            this.partIcons = new Icon[items.size()];
            Set<String> init = initiallyChecked != null ? new LinkedHashSet<>(initiallyChecked) : Collections.<String>emptySet();
            for (int i = 0; i < items.size(); i++) {
                MaterialUtils.PieceMaterial pm = items.get(i);
                checked[i] = init.contains(pm.name);
                Image part = pm.partPreview(48);
                if (part != null) {
                    partIcons[i] = new ImageIcon(part);
                }
            }
            this.columns = new String[]{
                "",
                UiKit.text(res, "materialPart", "Location"),
                UiKit.text(res, "materialName", "Material"),
                UiKit.text(res, "materialValue", "Value")
            };
        }

        @Override public int getRowCount() { return items.size(); }
        @Override public int getColumnCount() { return columns.length; }
        @Override public String getColumnName(int col) { return columns[col]; }

        @Override
        public Class<?> getColumnClass(int col) {
            if (col == 0) return Boolean.class;
            if (col == 1) return Icon.class;
            return String.class;
        }

        @Override
        public boolean isCellEditable(int row, int col) {
            return col == 0;
        }

        @Override
        public Object getValueAt(int row, int col) {
            MaterialUtils.PieceMaterial m = items.get(row);
            switch (col) {
                case 0: return checked[row];
                case 1: return partIcons[row];
                case 2: return m.name;
                case 3: return MaterialUtils.formatMaterialValue(m, property);
                default: return "";
            }
        }

        @Override
        public void setValueAt(Object val, int row, int col) {
            if (col == 0) {
                checked[row] = Boolean.TRUE.equals(val);
                fireTableCellUpdated(row, col);
            }
        }

        Set<String> checkedNames() {
            Set<String> out = new LinkedHashSet<>();
            for (int i = 0; i < items.size(); i++) {
                if (checked[i]) {
                    out.add(items.get(i).name);
                }
            }
            return out;
        }
    }

    /** Table model backing the weighted selection list for stochastic colors and textures. */
    private static final class ChoiceTableModel extends AbstractTableModel {
        private final List<SurroundRandomSettings.RandomLook.Choice> choices;
        private final String[] cols;
        private final Map<HomeTexture, Icon> texIcons = new IdentityHashMap<>();

        ChoiceTableModel(ResourceBundle res, List<SurroundRandomSettings.RandomLook.Choice> choices) {
            this.choices = choices;
            this.cols = new String[]{
                "",
                UiKit.text(res, "color_texture", "Color/texture"),
                UiKit.text(res, "randomWeight", "Weight"),
                UiKit.text(res, "randomProbability", "Probability"),
                UiKit.text(res, "randomVaryColumn", "Vary")
            };
        }

        @Override public int getRowCount() { return choices.size(); }
        @Override public int getColumnCount() { return cols.length; }
        @Override public String getColumnName(int c) { return cols[c]; }

        @Override
        public Class<?> getColumnClass(int c) {
            if (c == 0) return Icon.class;
            if (c == 2) return Double.class;
            if (c == 4) return Boolean.class;
            return String.class;
        }

        @Override
        public boolean isCellEditable(int r, int c) {
            if (c == 2) return true;
            if (c == 4) return choices.get(r).color != null;
            return false;
        }

        @Override
        public Object getValueAt(int r, int c) {
            SurroundRandomSettings.RandomLook.Choice ch = choices.get(r);
            if (c == 0) {
                if (ch.texture != null) {
                    return texIcons.computeIfAbsent(ch.texture, t -> MaterialUtils.loadTextureIcon(t.getImage(), 18));
                }
                if (ch.color != null) {
                    return new MaterialUtils.ColorIcon(new Color(ch.color));
                }
                return null;
            }
            if (c == 1) {
                if (ch.texture != null) {
                    return ch.texture.getName();
                }
                return ch.color != null ? String.format("#%06X", ch.color & 0xFFFFFF) : "";
            }
            if (c == 2) {
                return ch.weight;
            }
            if (c == 3) {
                double total = 0;
                for (SurroundRandomSettings.RandomLook.Choice x : choices) {
                    if (x.weight > 0) {
                        total += x.weight;
                    }
                }
                double pct = total > 0 && ch.weight > 0 ? (ch.weight * 100.0 / total) : 0.0;
                return String.format("%.1f %%", pct);
            }
            return ch.color != null && ch.vary;
        }

        @Override
        public void setValueAt(Object val, int r, int c) {
            if (c == 2 && val instanceof Number) {
                choices.get(r).weight = Math.max(0.0, Math.min(1000.0, ((Number) val).doubleValue()));
                fireTableDataChanged();
            } else if (c == 4) {
                choices.get(r).vary = Boolean.TRUE.equals(val);
                fireTableCellUpdated(r, c);
            }
        }
    }

    /** Consumer interface accepting an integer table row index. */
    private interface IntConsumer {
        void accept(int row);
    }

    /** Picker interface dynamically selecting the appropriate TableCellEditor for a given row. */
    private interface RowEditorPicker {
        TableCellEditor pick(int row);
    }

    /** TableCellEditor that displays a button launching specialized dialogs upon activation. */
    private static final class ButtonLaunchEditor extends AbstractCellEditor implements TableCellEditor {
        private final JButton button = new JButton();
        private final IntConsumer onLaunch;
        private int currentRow = -1;

        ButtonLaunchEditor(final JTable table, final IntConsumer onLaunch) {
            this.onLaunch = onLaunch;
            button.setHorizontalAlignment(SwingConstants.LEFT);
            button.setMargin(new Insets(1, 6, 1, 6));
            button.addActionListener(e -> {
                final int r = currentRow;
                fireEditingStopped();
                if (r >= 0) {
                    SwingUtilities.invokeLater(() -> this.onLaunch.accept(r));
                }
            });
        }

        @Override
        public Component getTableCellEditorComponent(JTable table, Object value, boolean isSelected, int row, int column) {
            currentRow = row;
            button.setText(value != null ? value.toString() : "");
            return button;
        }

        @Override
        public Object getCellEditorValue() {
            return button.getText();
        }
    }

    /** Cell editor wrapper selecting between multiple cell editors per row (e.g. look button vs mode dropdown). */
    private static final class AbstractCellEditorWrapper extends AbstractCellEditor implements TableCellEditor {
        private final RowEditorPicker picker;
        private TableCellEditor active;

        AbstractCellEditorWrapper(RowEditorPicker picker) {
            this.picker = picker;
        }

        @Override
        public Component getTableCellEditorComponent(JTable table, Object value, boolean isSelected, int row, int column) {
            active = picker.pick(row);
            return active.getTableCellEditorComponent(table, value, isSelected, row, column);
        }

        @Override
        public Object getCellEditorValue() {
            return active != null ? active.getCellEditorValue() : null;
        }

        @Override
        public boolean stopCellEditing() {
            return (active == null || active.stopCellEditing()) && super.stopCellEditing();
        }
    }

    /** Dynamic supplier for cell spinner limits: {initial value, min, max, step}. */
    private interface SpinnerRange {
        double[] of(int row, Object value);
    }

    /** TableCellEditor embedding a bounded JSpinner for editing numeric table cells. */
    private static final class NumberEditor extends AbstractCellEditor implements TableCellEditor {
        private final JSpinner spinner = new JSpinner(new SpinnerNumberModel(0.0, -10000.0, 10000.0, 1.0));
        private final SpinnerRange range;

        NumberEditor(SpinnerRange range) {
            this.range = range;
        }

        @Override
        public Component getTableCellEditorComponent(JTable table, Object value, boolean isSelected, int row, int column) {
            double[] r = range.of(row, value);
            spinner.setModel(new SpinnerNumberModel(Math.max(r[1], Math.min(r[2], r[0])), r[1], r[2], r[3]));
            return spinner;
        }

        @Override
        public Object getCellEditorValue() {
            try {
                spinner.commitEdit();
            } catch (java.text.ParseException ignored) {
            }
            return spinner.getValue();
        }
    }

    /** Centered cell renderer for swatch icons and thumbnail images. */
    private static final class IconRenderer extends DefaultTableCellRenderer {
        private static final long serialVersionUID = 1L;

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                boolean hasFocus, int row, int column) {
            JLabel label = (JLabel) super.getTableCellRendererComponent(table, "", isSelected, hasFocus, row, column);
            label.setHorizontalAlignment(SwingConstants.CENTER);
            label.setIcon(value instanceof Icon ? (Icon) value : null);
            return label;
        }
    }

}
