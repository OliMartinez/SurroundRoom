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
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTable;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;

/**
 * Drawing and table to choose the sides to surround ({@link #DIALOG_SIDES}: click a side or its box), the
 * stretches to exclude ({@link #DIALOG_SEGMENTS}: with the placed objects, clickable hints of doors/windows,
 * furniture, and overlapping walls/floors, interactive point-to-point picking on the drawing, or ranges typed
 * in cm), or the starting point for Type 7 ({@link #DIALOG_START_POINT}). Shown inside SurroundConfigFrame,
 * its Accept and Cancel buttons return to the normal preview.
 */
public class SideEndsPanel extends JPanel {

    /** Mode constant: Include or exclude entire sides. */
    public static final int DIALOG_SIDES = 0;

    /** Mode constant: Exclude specific sub-stretches/intervals along sides. */
    public static final int DIALOG_SEGMENTS = 1;

    /** Mode constant: Select starting point along the perimeter for Type 7. */
    public static final int DIALOG_START_POINT = 2;

    /** Accent color used for selected sides and UI highlights. */
    private static final Color ACCENT = UiKit.ACCENT;

    /** Color used for standard wall outlines. */
    private static final Color WALL = OutlinePreview.WALL;

    /** Color used for highlighting the active sub-stretch in the list (gold). */
    private static final Color SELECTED_SEG_COLOR = new Color(255, 195, 0);

    /** Color used for rendering contact/overlap zones in sides mode. */
    private static final Color OVERLAP_COLOR = new Color(180, 120, 40);

    /** Color for door opening hints in segments mode (translucent teal). */
    private static final Color DOOR_HINT = new Color(22, 160, 145, 155);

    /** Color for window opening hints in segments mode (translucent cyan/sky blue, distinct from doors). */
    private static final Color WINDOW_HINT = new Color(0, 180, 216, 165);

    /** Color for furniture collision obstacle hints in segments mode (translucent orange). */
    private static final Color COLLISION_HINT = new Color(230, 126, 34, 155);

    /** Color for walls touching or overlapping the selected room/walls in segments mode (translucent purple). */
    private static final Color WALL_OVERLAP_HINT = new Color(142, 68, 173, 165);

    /** Color for floors touching or overlapping the selected room/walls in segments mode (translucent magenta/rose, distinct from walls). */
    private static final Color FLOOR_OVERLAP_HINT = new Color(216, 27, 96, 160);

    /** Bright green color for Type 7 start marker and interactive stretch picking markers. */
    private static final Color PICK_MARKER_COLOR = new Color(39, 174, 96);

    /** Functional listener interface for confirmation callbacks. */
    public interface ConfirmListener {
        /**
         * Invoked when the user confirms side, stretch, or start-point modifications.
         *
         * @param result     map of modified SideEnds per Room
         * @param dialogMode the active mode ({@link #DIALOG_SIDES}, {@link #DIALOG_SEGMENTS}, or {@link #DIALOG_START_POINT})
         */
        void onConfirm(Map<Room, SurroundConfig.SideEnds> result, int dialogMode);
    }

    /** Localized resource bundle. */
    private final ResourceBundle resource;

    /** Callback invoked upon confirmation with the working copy of SideEnds. */
    private final ConfirmListener confirmListener;

    /** Callback invoked upon cancellation. */
    private final Runnable cancelListener;

    /** List of target rooms/walls being managed. */
    private List<Room> rooms = Collections.emptyList();

    /** User-friendly display names corresponding to the rooms. */
    private List<String> roomNames = Collections.emptyList();

    /** Whether the surround orientation is interior. */
    private boolean interior;

    /** Set of rooms that are always forced to exterior orientation (e.g. open wall groups). */
    private Set<Room> alwaysExterior = Collections.emptySet();

    /** Operational mode ({@link #DIALOG_SIDES}, {@link #DIALOG_SEGMENTS}, or {@link #DIALOG_START_POINT}). */
    private int dialogMode = DIALOG_SIDES;

    /** Half-length of the template object, used as default corner margin (in cm). */
    private double defaultHalfObjectLength = 15.0;

    /** Whether corner handling is active in the current surround configuration. */
    private boolean cornerHandlingActive;

    /** Reference to the Sweet Home 3D Home model for live preview generation. */
    private Home home;

    /** Selected template furniture pieces. */
    private List<HomePieceOfFurniture> selectedFurniture = Collections.emptyList();

    /** Snapshot configuration DTO used for live preview calculation. */
    private SurroundConfig activeConfig = new SurroundConfig();

    /** Obstacle furniture pieces used for collision and opening detection. */
    private List<HomePieceOfFurniture> obstacles = Collections.emptyList();

    /** Bounding depth of template furniture in cm. */
    private double objectDepth = 30;

    /** Base width of the primary template piece in cm. */
    private double baseWidth = 30;

    /** Whether the global live preview checkbox is enabled. */
    private boolean livePreviewEnabled = true;

    /** Cached obstacle hints (doors, windows, furniture footprints, overlapping walls/floors) per room. */
    private final Map<Room, List<List<double[]>>> obstacleHintsCache = new HashMap<>();

    /** Working copy of {@link SurroundConfig.SideEnds} models per room, modified in place. */
    private final Map<Room, SurroundConfig.SideEnds> working = new HashMap<>();

    /** Working copy of Type 7 start points per room: {logicalSideIndex, nativeDistAlongSide}. */
    private final Map<Room, double[]> workingStartPoints = new HashMap<>();

    /** Cached polyline segments per room. */
    private final Map<Room, List<float[][]>> sidePolylines = new HashMap<>();

    /** Currently active room displayed in the editor. */
    private Room currentRoom;

    /** 2D interactive canvas. */
    private final RoomPreview preview = new RoomPreview();

    /** Table displaying sides. */
    private final JTable table;

    /** Custom table model for sides. */
    private final SidesTableModel tableModel = new SidesTableModel();

    /** List model for excluded intervals of the selected side. */
    private final DefaultListModel<String> segmentListModel = new DefaultListModel<>();

    /** List component for excluded intervals. */
    private JList<String> segmentList;

    /** Interactive drawing-pick state for DIALOG_SEGMENTS ("Seleccionar tramo en el dibujo"). */
    private boolean pickingInDrawing = false;
    private int pickSide = -1;
    private double pickPos1 = Double.NaN;
    private double pickPos2 = Double.NaN;
    private JButton btnPickInDrawing;
    private JButton btnAcceptPick;
    private JButton btnCancelPick;

    /** Spinner for Type 7 start distance in DIALOG_START_POINT mode. */
    private JSpinner startPointSpinner;
    private boolean updatingStartSpinner = false;

    /** Asynchronous preview computation state for smooth non-blocking UI. */
    private volatile SurroundGeometryEngine runningEngine;
    private volatile int asyncSeq = 0;
    private List<HomePieceOfFurniture> asyncPlaced = Collections.emptyList();
    private SurroundGeometryEngine asyncCompletedEngine;
    private final Timer loadingOverlayTimer = new Timer(180, e -> preview.setLoading(true, text("previewLoading", "Cargando\u2026")));

    /** Warning label for empty selections. */
    private final JLabel warningLabel = new JLabel();

    /** Confirm button. */
    private final JButton btnConfirm = new JButton();

    /** Cancel button. */
    private final JButton btnCancel = new JButton();

    /** Constructs a new SideEndsPanel. */
    public SideEndsPanel(ResourceBundle resource, ConfirmListener confirmListener, Runnable cancelListener) {
        super(new BorderLayout(0, 6));
        this.resource = resource;
        this.confirmListener = confirmListener;
        this.cancelListener = cancelListener;
        this.loadingOverlayTimer.setRepeats(false);

        table = new JTable(tableModel);
        table.setRowHeight(22);
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.getTableHeader().setReorderingAllowed(false);
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                if (dialogMode == DIALOG_START_POINT && currentRoom != null && table.getSelectedRow() >= 0 && !updatingStartSpinner) {
                    int row = table.getSelectedRow();
                    double[] cur = workingStartPoints.get(currentRoom);
                    if (cur == null || (int) Math.round(cur[0]) != row) {
                        setType7StartShown(row, 0.0);
                    }
                }
                updateSegmentList();
                preview.repaint();
            }
        });

        btnConfirm.addActionListener(e -> {
            cancelAsyncPreview();
            if (table.isEditing()) {
                table.getCellEditor().stopCellEditing();
            }
            if (this.confirmListener != null) {
                this.confirmListener.onConfirm(working, dialogMode);
            }
        });
        btnCancel.addActionListener(e -> {
            cancelAsyncPreview();
            if (this.cancelListener != null) {
                this.cancelListener.run();
            }
        });

        // Register ESC key to cancel
        registerKeyboardAction(e -> {
            if (pickingInDrawing) {
                setPickingMode(false);
            } else if (this.cancelListener != null) {
                cancelAsyncPreview();
                this.cancelListener.run();
            }
        }, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);

        setPreferredSize(new Dimension(UiKit.CARD_WIDTH * 4 + UiKit.GAP * 3, 455));
    }

    @Override
    public Dimension getPreferredSize() {
        int w = UiKit.CARD_WIDTH * 4 + UiKit.GAP * 3;
        int topH = (rooms.size() > 1 ? 48 : 24);
        int previewH = 250;
        int bottomH = (dialogMode == DIALOG_SEGMENTS ? 148 : 94);
        int southH = 36;
        int h = topH + previewH + bottomH + southH + 18;
        return new Dimension(w, h);
    }

    @Override
    public Dimension getMinimumSize() {
        return getPreferredSize();
    }

    /** Zooms in on the embedded room preview canvas. */
    public void zoomIn() {
        preview.zoomIn();
    }

    /** Zooms out on the embedded room preview canvas. */
    public void zoomOut() {
        preview.zoomOut();
    }

    /** Resets zoom and pan on the embedded room preview canvas. */
    public void resetZoom() {
        preview.resetZoom();
    }

    /** Returns the confirmed or working Type 7 start points map. */
    public Map<Room, double[]> getWorkingStartPoints() {
        Map<Room, double[]> copy = new HashMap<>();
        for (Map.Entry<Room, double[]> entry : workingStartPoints.entrySet()) {
            if (entry.getValue() != null) {
                copy.put(entry.getKey(), entry.getValue().clone());
            }
        }
        return copy;
    }

    /** Sets whether live furniture preview is enabled in the main window. */
    public void setLivePreviewEnabled(boolean enabled) {
        this.livePreviewEnabled = enabled;
        if (!enabled) {
            cancelAsyncPreview();
            asyncPlaced = Collections.emptyList();
            asyncCompletedEngine = null;
        }
        triggerAsyncPreview();
    }

    /** Cancels any in-progress background preview computation. */
    public void cancelAsyncPreview() {
        asyncSeq++;
        loadingOverlayTimer.stop();
        preview.setLoading(false, null);
        SurroundGeometryEngine eng = runningEngine;
        if (eng != null) {
            eng.cancel();
            runningEngine = null;
        }
    }

    /**
     * Starts an interactive editing session for sides, stretches, or Type 7 starting point.
     *
     * @param dialogMode              {@link #DIALOG_SIDES}, {@link #DIALOG_SEGMENTS}, or {@link #DIALOG_START_POINT}
     * @param defaultHalfObjectLength half object length in cm
     */
    public void startEditing(List<Room> rooms, List<String> roomNames,
            Map<Room, SurroundConfig.SideEnds> current, boolean interior, Set<Room> alwaysExterior,
            int dialogMode, boolean mergeCollinear, double defaultHalfObjectLength, boolean cornerHandlingActive) {
        this.rooms = rooms != null ? rooms : Collections.emptyList();
        this.roomNames = roomNames != null ? roomNames : Collections.emptyList();
        this.interior = interior;
        this.alwaysExterior = alwaysExterior != null ? alwaysExterior : Collections.emptySet();
        this.dialogMode = dialogMode;
        this.defaultHalfObjectLength = Math.max(1.0, defaultHalfObjectLength);
        this.cornerHandlingActive = cornerHandlingActive;
        this.pickingInDrawing = false;
        this.pickSide = -1;
        this.pickPos1 = Double.NaN;
        this.pickPos2 = Double.NaN;

        sidePolylines.clear();
        working.clear();
        obstacleHintsCache.clear();

        for (Room room : this.rooms) {
            List<float[][]> polys = SideModel.polylines(room, mergeCollinear);
            sidePolylines.put(room, polys);
            int n = polys.size();
            SurroundConfig.SideEnds existing = current != null ? current.get(room) : null;
            SurroundConfig.SideEnds copy = (existing != null && existing.skipped.length == n)
                    ? existing.copy()
                    : new SurroundConfig.SideEnds(n);
            if (dialogMode == DIALOG_SEGMENTS) {
                copy.initDefaultExcludedSegments(polys, this.defaultHalfObjectLength, this.cornerHandlingActive);
            }
            working.put(room, copy);
        }
        this.currentRoom = this.rooms.isEmpty() ? null : this.rooms.get(0);

        buildUi();
        tableModel.fireTableStructureChanged();
        configureTableColumns();
        tableModel.fireTableDataChanged();
        if (table.getRowCount() > 0) {
            int selRow = 0;
            if (dialogMode == DIALOG_START_POINT && currentRoom != null && workingStartPoints.containsKey(currentRoom)) {
                selRow = Math.max(0, Math.min(table.getRowCount() - 1, (int) Math.round(workingStartPoints.get(currentRoom)[0])));
            }
            table.setRowSelectionInterval(selRow, selRow);
        }
        updateSegmentList();
        triggerAsyncPreview();
    }

    /**
     * Injects context dependencies required for live preview calculation in segments or start-point mode.
     *
     * @param objectDepth       depth of the template piece in cm
     */
    public void setPreviewContext(Home home, List<HomePieceOfFurniture> selectedFurniture, SurroundConfig snapshotConfig,
            List<HomePieceOfFurniture> obstacles, double objectDepth) {
        this.home = home;
        this.selectedFurniture = selectedFurniture != null ? selectedFurniture : Collections.emptyList();
        this.activeConfig = snapshotConfig != null ? snapshotConfig : new SurroundConfig();
        this.obstacles = obstacles != null ? obstacles : Collections.emptyList();
        this.objectDepth = Math.max(1.0, objectDepth);
        HomePieceOfFurniture primary = SurroundGeometryEngine.asSingleTemplate(this.selectedFurniture);
        if (primary != null) {
            this.baseWidth = primary.getWidth();
        }
        workingStartPoints.clear();
        if (this.activeConfig.type7StartPoints != null) {
            for (Map.Entry<Room, double[]> e : this.activeConfig.type7StartPoints.entrySet()) {
                if (e.getValue() != null) {
                    workingStartPoints.put(e.getKey(), e.getValue().clone());
                }
            }
        }
        if (dialogMode == DIALOG_START_POINT && currentRoom != null) {
            syncStartPointControls();
        }
        this.obstacleHintsCache.clear();
        triggerAsyncPreview();
    }

    /** Triggers a background calculation of placed furniture for the active room so the UI never freezes. */
    private void triggerAsyncPreview() {
        cancelAsyncPreview();
        if (currentRoom == null || !livePreviewEnabled
                || (dialogMode != DIALOG_SEGMENTS && dialogMode != DIALOG_START_POINT)
                || selectedFurniture.isEmpty()) {
            asyncPlaced = Collections.emptyList();
            asyncCompletedEngine = null;
            preview.repaint();
            return;
        }
        final int seq = ++asyncSeq;
        final Room room = currentRoom;
        final SurroundConfig cfg = cloneConfigForPreview(room);
        final SurroundGeometryEngine engine = new SurroundGeometryEngine(
                home, null, Collections.singletonList(room), selectedFurniture);
        runningEngine = engine;
        loadingOverlayTimer.restart();

        Thread worker = new Thread(() -> {
            try {
                List<HomePieceOfFurniture> computed = engine.previewFirstLevel(cfg);
                SwingUtilities.invokeLater(() -> {
                    if (seq != asyncSeq) {
                        return;
                    }
                    loadingOverlayTimer.stop();
                    preview.setLoading(false, null);
                    runningEngine = null;
                    asyncPlaced = computed;
                    asyncCompletedEngine = engine;
                    preview.repaint();
                });
            } catch (CancellationException ignored) {
                // Superseded by newer change or dialog action
            } catch (RuntimeException ex) {
                SwingUtilities.invokeLater(() -> {
                    if (seq != asyncSeq) {
                        return;
                    }
                    loadingOverlayTimer.stop();
                    preview.setLoading(false, null);
                    runningEngine = null;
                    preview.repaint();
                });
            }
        }, "SurroundSideEndsPreview-" + seq);
        worker.setDaemon(true);
        worker.start();
        preview.repaint();
    }

    /** Builds an isolated config snapshot for background preview computation. */
    private SurroundConfig cloneConfigForPreview(Room room) {
        SurroundConfig cfg = activeConfig;
        SurroundConfig.SideEnds wEnds = working.get(room);
        if (wEnds != null) {
            cfg.sideEnds.put(room, wEnds.copy());
        }
        if (dialogMode == DIALOG_SEGMENTS) {
            cfg.enableExcludedSegments = true;
        } else if (dialogMode == DIALOG_START_POINT) {
            cfg.surroundType = 7;
            double[] sp = workingStartPoints.get(room);
            if (sp != null) {
                cfg.type7StartPoints.put(room, sp.clone());
            } else {
                cfg.type7StartPoints.remove(room);
            }
        }
        return cfg;
    }

    /** Reconstructs the internal subcomponents based on the active {@code dialogMode}. */
    private void buildUi() {
        removeAll();

        // 1. Top bar: Room selector (if multiple rooms) and instruction hints
        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.setOpaque(false);

        if (rooms.size() > 1) {
            JPanel roomBar = new JPanel(new FlowLayout(FlowLayout.LEADING, 6, 0));
            roomBar.setOpaque(false);
            roomBar.setAlignmentX(Component.LEFT_ALIGNMENT);
            roomBar.add(new JLabel(text("sidesRoom", "Surface:")));
            JComboBox<Room> combo = new JComboBox<>(rooms.toArray(new Room[0]));
            combo.setRenderer(UiKit.renderer(Room.class, this::roomName, false));
            combo.addActionListener(e -> {
                if (table.isEditing()) {
                    table.getCellEditor().stopCellEditing();
                }
                setPickingMode(false);
                currentRoom = (Room) combo.getSelectedItem();
                tableModel.fireTableDataChanged();
                if (table.getRowCount() > 0) {
                    table.setRowSelectionInterval(0, 0);
                }
                if (dialogMode == DIALOG_START_POINT) {
                    syncStartPointControls();
                }
                updateSegmentList();
                triggerAsyncPreview();
            });
            roomBar.add(combo);
            top.add(roomBar);
            top.add(Box.createVerticalStrut(3));
        }

        String modeTitle;
        String summaryText;
        String helpTip;
        if (dialogMode == DIALOG_SEGMENTS) {
            modeTitle = text("segmentsTitle", "Excluded stretches");
            summaryText = text("segmentsShortHint", "Select a side to manage its excluded stretches.");
            helpTip = text("segmentsDefaultHint", "By default no stretches are excluded; use the buttons or click in the drawing to add them.")
                    + " " + text("segmentsObstaclesHint", "Click doors/windows (teal), furniture (orange) or overlapping walls/floors (purple) in the drawing to exclude or include them.");
        } else if (dialogMode == DIALOG_START_POINT) {
            modeTitle = text("t7StartTitle", "Starting point (Type 7)");
            summaryText = text("t7StartShortHint", "Click a position on any side in the drawing or set its distance.");
            helpTip = text("t7StartHelpTip", "The Type 7 distribution starts at the chosen position and follows the traversal direction around the outline.");
        } else {
            modeTitle = text("sidesTitle", "Sides to surround");
            summaryText = text("sidesShortHint", "Select the sides to include in the surround.");
            helpTip = text("sidesClickHint", "Click a side in the drawing or use the table checkboxes to include or exclude it.")
                    + " " + text("sidesDirectionHint", "The top-left arrow indicates the traversal direction.");
        }

        JLabel titleLabel = new JLabel(modeTitle + " \u2014 ");
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD));
        JPanel hintRow = UiKit.row(4, titleLabel, UiKit.hint(summaryText), UiKit.infoIcon(helpTip));
        hintRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        top.add(hintRow);
        add(top, BorderLayout.NORTH);

        // 2. Center: 2D preview canvas on top, color classification legend (in segments mode), and controls below

        JScrollPane scroll = new JScrollPane(table);
        int tableHeight = (dialogMode == DIALOG_SEGMENTS) ? 120 : 90;
        scroll.setPreferredSize(new Dimension(265, tableHeight));

        JPanel bottomControls;
        if (dialogMode == DIALOG_SEGMENTS) {
            bottomControls = new JPanel(new BorderLayout(8, 4));
            bottomControls.setOpaque(false);
            bottomControls.add(scroll, BorderLayout.WEST);
            bottomControls.add(createSegmentsEditorPanel(), BorderLayout.CENTER);
            bottomControls.add(createSegmentsLegendBar(), BorderLayout.SOUTH);
        } else if (dialogMode == DIALOG_START_POINT) {
            bottomControls = new JPanel(new BorderLayout(8, 0));
            bottomControls.setOpaque(false);
            bottomControls.add(scroll, BorderLayout.WEST);
            bottomControls.add(createStartPointEditorPanel(), BorderLayout.CENTER);
        } else {
            bottomControls = new JPanel(new BorderLayout(8, 0));
            bottomControls.setOpaque(false);
            bottomControls.add(scroll, BorderLayout.WEST);
            JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.LEADING, 6, 4));
            btnPanel.setOpaque(false);
            btnPanel.add(createSidesButtonsPanel());
            bottomControls.add(btnPanel, BorderLayout.CENTER);
        }

        JPanel centerPanel = new JPanel(new BorderLayout(0, 6));
        centerPanel.setOpaque(false);
        centerPanel.add(preview, BorderLayout.CENTER);
        centerPanel.add(bottomControls, BorderLayout.SOUTH);
        add(centerPanel, BorderLayout.CENTER);

        // 3. Bottom bar: Warning indicator and Action buttons (Cancel / Accept)
        warningLabel.setForeground(new Color(185, 95, 0));
        String okText = UIManager.getString("OptionPane.okButtonText", getLocale());
        String cancelText = UIManager.getString("OptionPane.cancelButtonText", getLocale());
        btnConfirm.setText(okText != null ? okText : text("accept", "Aceptar"));
        btnCancel.setText(cancelText != null ? cancelText : text("cancel", "Cancelar"));

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.TRAILING, 8, 0));
        buttons.setOpaque(false);
        buttons.add(btnCancel);
        buttons.add(btnConfirm);

        JPanel south = new JPanel(new BorderLayout());
        south.setBorder(new CompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, UiKit.CARD_BORDER),
                new EmptyBorder(6, 0, 0, 0)));
        south.add(warningLabel, BorderLayout.CENTER);
        south.add(buttons, BorderLayout.LINE_END);
        add(south, BorderLayout.SOUTH);

        revalidate();
        repaint();
    }

    /**
     * Builds the main color classification legend bar below the panel in DIALOG_SEGMENTS mode,
     * explaining what each sub-stretch color represents by category.
     */
    private JPanel createSegmentsLegendBar() {
        JPanel legend = new JPanel(new FlowLayout(FlowLayout.LEADING, 8, 2));
        legend.setOpaque(false);
        legend.add(colorSwatchLabel(OutlinePreview.EXCLUDED, text("legendExcluded", "Tramo excluido")));
        legend.add(colorSwatchLabel(SELECTED_SEG_COLOR, text("legendSelectedSeg", "Seleccionado")));
        legend.add(colorSwatchLabel(DOOR_HINT, text("legendDoors", "Puertas")));
        legend.add(colorSwatchLabel(WINDOW_HINT, text("legendWindows", "Ventanas")));
        legend.add(colorSwatchLabel(COLLISION_HINT, text("legendFurniture", "Muebles")));
        legend.add(colorSwatchLabel(WALL_OVERLAP_HINT, text("legendWalls", "Paredes que tocan/traslapan")));
        legend.add(colorSwatchLabel(FLOOR_OVERLAP_HINT, text("legendFloors", "Suelos que traslapan")));
        return legend;
    }

    /** Creates a compact label with a colored rectangular icon swatch for the classification legend. */
    private static JLabel colorSwatchLabel(final Color color, String labelText) {
        Icon icon = new Icon() {
            @Override
            public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 235));
                g2.fillRoundRect(x, y + 1, 12, 8, 3, 3);
                g2.setColor( new Color(0, 0, 0, 70));
                g2.drawRoundRect(x, y + 1, 12, 8, 3, 3);
                g2.dispose();
            }

            @Override
            public int getIconWidth() {
                return 13;
            }

            @Override
            public int getIconHeight() {
                return 10;
            }
        };
        JLabel lbl = new JLabel(labelText, icon, SwingConstants.LEADING);
        lbl.setFont(lbl.getFont().deriveFont(11f));
        lbl.setForeground(UiKit.HINT_TEXT);
        lbl.setIconTextGap(4);
        return lbl;
    }

    /** Configures table columns and centered alignment renderers. */
    private void configureTableColumns() {
        DefaultTableCellRenderer center = new DefaultTableCellRenderer();
        center.setHorizontalAlignment(SwingConstants.CENTER);
        int firstCentered = dialogMode == DIALOG_SIDES ? 1 : 0;
        for (int c = firstCentered; c <= 1 && c < table.getColumnCount(); c++) {
            table.getColumnModel().getColumn(c).setCellRenderer(center);
        }
        if (dialogMode == DIALOG_SEGMENTS || dialogMode == DIALOG_START_POINT) {
            table.getColumnModel().getColumn(0).setPreferredWidth(38);
            table.getColumnModel().getColumn(1).setPreferredWidth(76);
            table.getColumnModel().getColumn(2).setPreferredWidth(150);
        } else {
            table.getColumnModel().getColumn(0).setPreferredWidth(50);
            table.getColumnModel().getColumn(1).setPreferredWidth(45);
            table.getColumnModel().getColumn(2).setPreferredWidth(100);
        }
    }

    /** Builds the action buttons panel for whole-side selection mode. */
    private JPanel createSidesButtonsPanel() {
        JButton includeBtn = selectionButton(text("sidesSurroundSelected", "Include"), rows -> setSkipped(rows, false));
        JButton excludeBtn = selectionButton(text("sidesSkipSelected", "Exclude"), rows -> setSkipped(rows, true));
        return UiKit.row(4, includeBtn, excludeBtn);
    }

    /** Builds the editor sub-panel for managing Type 7 start point in DIALOG_START_POINT mode. */
    private JPanel createStartPointEditorPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setOpaque(false);
        startPointSpinner = UiKit.spinner(new SpinnerNumberModel(0.0, 0.0, 100000.0, 1.0), 5);
        JButton resetStartBtn = new JButton(text("t7StartReset", "Al inicio del contorno"));

        startPointSpinner.addChangeListener(e -> {
            if (updatingStartSpinner || currentRoom == null) {
                return;
            }
            int side = Math.max(0, table.getSelectedRow());
            double val = ((Number) startPointSpinner.getValue()).doubleValue();
            setType7StartShown(side, val);
        });

        resetStartBtn.addActionListener(e -> {
            if (currentRoom != null) {
                workingStartPoints.remove(currentRoom);
                if (table.getRowCount() > 0) {
                    updatingStartSpinner = true;
                    try {
                        table.setRowSelectionInterval(0, 0);
                        startPointSpinner.setValue(0.0);
                    } finally {
                        updatingStartSpinner = false;
                    }
                }
                tableModel.fireTableDataChanged();
                triggerAsyncPreview();
            }
        });

        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = 0;
        c.anchor = GridBagConstraints.LINE_START;
        c.insets = new Insets(2, 0, 2, 0);
        panel.add(UiKit.row(4, new JLabel(text("t7StartPosLabel", "Posici\u00f3n en el lado:")),
                startPointSpinner, new JLabel("cm")), c);
        c.gridy = 1;
        panel.add(UiKit.row(4, resetStartBtn), c);
        UiKit.addFiller(panel);
        syncStartPointControls();
        return panel;
    }

    /** Updates the Type 7 start point for the current room from user-facing (shown) distance along {@code side}. */
    private void setType7StartShown(int side, double shownDist) {
        if (currentRoom == null) {
            return;
        }
        List<float[][]> polys = sidePolylines.get(currentRoom);
        if (polys == null || side < 0 || side >= polys.size()) {
            return;
        }
        double len = SideModel.length(polys.get(side));
        double clampedShown = Math.max(0.0, Math.min(len, shownDist));
        double nativeDist = isReversed(currentRoom) ? (len - clampedShown) : clampedShown;
        workingStartPoints.put(currentRoom, new double[]{side, nativeDist});
        updatingStartSpinner = true;
        try {
            if (table.getSelectedRow() != side) {
                table.setRowSelectionInterval(side, side);
            }
            if (startPointSpinner != null) {
                startPointSpinner.setValue(Math.round(clampedShown * 10.0) / 10.0);
            }
        } finally {
            updatingStartSpinner = false;
        }
        tableModel.fireTableDataChanged();
        updatingStartSpinner = true;
        try {
            table.setRowSelectionInterval(side, side);
        } finally {
            updatingStartSpinner = false;
        }
        triggerAsyncPreview();
    }

    /** Synchronizes the Type 7 start spinner and table row selection with {@code workingStartPoints}. */
    private void syncStartPointControls() {
        if (currentRoom == null || startPointSpinner == null) {
            return;
        }
        List<float[][]> polys = sidePolylines.get(currentRoom);
        if (polys == null || polys.isEmpty()) {
            return;
        }
        double[] sp = workingStartPoints.get(currentRoom);
        int side = sp != null ? Math.max(0, Math.min(polys.size() - 1, (int) Math.round(sp[0]))) : 0;
        double len = SideModel.length(polys.get(side));
        double nativeDist = sp != null ? Math.max(0.0, Math.min(len, sp[1])) : (isReversed(currentRoom) ? len : 0.0);
        double shownDist = isReversed(currentRoom) ? (len - nativeDist) : nativeDist;
        updatingStartSpinner = true;
        try {
            if (table.getRowCount() > side) {
                table.setRowSelectionInterval(side, side);
            }
            startPointSpinner.setValue(Math.round(shownDist * 10.0) / 10.0);
        } finally {
            updatingStartSpinner = false;
        }
    }

    /** Builds the editor sub-panel for managing excluded intervals in segments mode. */
    private JPanel createSegmentsEditorPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 3));
        segmentList = new JList<>(segmentListModel);
        segmentList.setVisibleRowCount(2);
        segmentList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                preview.repaint();
            }
        });
        JScrollPane listScroll = new JScrollPane(segmentList);
        listScroll.setPreferredSize(new Dimension(270, 42));

        JSpinner startSpin = UiKit.spinner(new SpinnerNumberModel(0.0, 0.0, 100000.0, 1.0), 4);
        JSpinner endSpin = UiKit.spinner(new SpinnerNumberModel(defaultHalfObjectLength, 0.0, 100000.0, 1.0), 4);
        JButton addBtn = new JButton(text("segmentAdd", "A\u00f1adir"));
        JButton removeBtn = new JButton(text("segmentRemove", "Quitar"));
        JButton resetEndsBtn = new JButton(text("segmentResetEnds", "Extremos (\u00bd obj.)"));
        JButton clearBtn = new JButton(text("segmentClear", "Vaciar"));

        // Interactive drawing selection buttons ("Seleccionar tramo en el dibujo", "Aceptar", "Cancelar")
        btnPickInDrawing = new JButton(text("segmentPickInDrawing", "Seleccionar tramo en el dibujo"));
        btnAcceptPick = new JButton(text("accept", "Aceptar"));
        btnCancelPick = new JButton(text("cancel", "Cancelar"));
        btnAcceptPick.setVisible(false);
        btnCancelPick.setVisible(false);
        btnAcceptPick.setEnabled(false);

        btnPickInDrawing.addActionListener(e -> setPickingMode(true));
        btnAcceptPick.addActionListener(e -> confirmPickedStretch());
        btnCancelPick.addActionListener(e -> setPickingMode(false));

        addBtn.addActionListener(e -> {
            double a = ((Number) startSpin.getValue()).doubleValue();
            double b = ((Number) endSpin.getValue()).doubleValue();
            double s = Math.min(a, b), end = Math.max(a, b);
            if (end - s < 0.1) {
                return;
            }
            editSelectedSides((segs, maxLen) -> {
                double[] stored = shownToStored(new double[]{s, end}, maxLen);
                double cs = Math.max(0.0, Math.min(maxLen, stored[0]));
                double ce = Math.max(0.0, Math.min(maxLen, stored[1]));
                if (ce - cs >= 0.1) {
                    segs.add(new double[]{cs, ce});
                    SurroundTarget.mergeIntervals(segs);
                }
            });
        });

        removeBtn.addActionListener(e -> {
            int side = table.getSelectedRow();
            int idx = segmentList.getSelectedIndex();
            if (currentRoom != null && side >= 0 && idx >= 0) {
                SurroundConfig.SideEnds ends = working.get(currentRoom);
                ends.excludedSegmentsModifiedByUser = true;
                List<double[]> segs = ends.excludedSegments.get(side);
                if (idx < segs.size()) {
                    segs.remove(isReversed(currentRoom) ? segs.size() - 1 - idx : idx);
                    tableModel.fireTableRowsUpdated(side, side);
                    updateSegmentList();
                    triggerAsyncPreview();
                }
            }
        });

        resetEndsBtn.addActionListener(e -> editSelectedSides((segs, totalLen) -> {
            segs.clear();
            if (totalLen > 0) {
                double d = Math.min(defaultHalfObjectLength, totalLen / 2.0);
                segs.add(new double[]{0.0, d});
                if (totalLen - d > d + 1e-3) {
                    segs.add(new double[]{totalLen - d, totalLen});
                } else if (totalLen > d) {
                    segs.set(0, new double[]{0.0, totalLen});
                }
            }
        }));

        clearBtn.addActionListener(e -> editSelectedSides((segs, length) -> segs.clear()));

        JPanel controls = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = 0;
        c.anchor = GridBagConstraints.LINE_START;
        c.insets = new Insets(1, 0, 1, 0);
        controls.add(UiKit.row(3, new JLabel(text("segmentFrom", "Desde:")), startSpin,
                new JLabel(text("segmentTo", "cm  Hasta:")), endSpin, new JLabel("cm"), addBtn), c);
        c.gridy = 1;
        controls.add(UiKit.row(3, removeBtn, resetEndsBtn, clearBtn), c);
        c.gridy = 2;
        controls.add(UiKit.row(4, btnPickInDrawing, btnAcceptPick, btnCancelPick), c);
        UiKit.addFiller(controls);

        panel.add(listScroll, BorderLayout.CENTER);
        panel.add(controls, BorderLayout.SOUTH);
        return panel;
    }

    /** Activates or deactivates interactive stretch picking directly on the 2D drawing. */
    private void setPickingMode(boolean active) {
        this.pickingInDrawing = active;
        this.pickSide = -1;
        this.pickPos1 = Double.NaN;
        this.pickPos2 = Double.NaN;
        if (btnPickInDrawing != null) {
            btnPickInDrawing.setEnabled(!active);
            btnAcceptPick.setVisible(active);
            btnCancelPick.setVisible(active);
            btnAcceptPick.setEnabled(false);
        }
        preview.setCursor(Cursor.getPredefinedCursor(active ? Cursor.CROSSHAIR_CURSOR : Cursor.DEFAULT_CURSOR));
        if (active) {
            warningLabel.setText(text("segmentPickClickHint", "Haga clic en el inicio y fin del tramo sobre un lado del dibujo."));
        } else {
            warningLabel.setText("");
        }
        preview.repaint();
    }

    /** Commits the 2-point stretch picked on the drawing to the excluded segments of {@code pickSide}. */
    private void confirmPickedStretch() {
        if (!pickingInDrawing || currentRoom == null || pickSide < 0 || Double.isNaN(pickPos1) || Double.isNaN(pickPos2)) {
            return;
        }
        double s0 = Math.min(pickPos1, pickPos2);
        double s1 = Math.max(pickPos1, pickPos2);
        double maxLen = SideModel.length(sidePolylines.get(currentRoom).get(pickSide));
        s0 = Math.max(0.0, Math.min(maxLen, s0));
        s1 = Math.max(0.0, Math.min(maxLen, s1));
        if (s1 - s0 >= 0.1) {
            SurroundConfig.SideEnds ends = working.get(currentRoom);
            ends.excludedSegmentsModifiedByUser = true;
            List<double[]> segs = ends.excludedSegments.get(pickSide);
            segs.add(new double[]{s0, s1});
            SurroundTarget.mergeIntervals(segs);
            table.setRowSelectionInterval(pickSide, pickSide);
            tableModel.fireTableRowsUpdated(pickSide, pickSide);
            updateSegmentList();
            triggerAsyncPreview();
        }
        setPickingMode(false);
    }

    private JButton selectionButton(String text, Consumer<int[]> action) {
        JButton btn = new JButton(text);
        btn.addActionListener(e -> {
            int[] rows = table.getSelectedRows();
            if (rows.length > 0) {
                action.accept(rows);
            }
        });
        return btn;
    }

    private void setSkipped(int[] rows, boolean skip) {
        if (currentRoom == null) {
            return;
        }
        SurroundConfig.SideEnds ends = working.get(currentRoom);
        for (int row : rows) {
            if (row < ends.forbidden.length && ends.forbidden[row]) {
                continue;
            }
            ends.skipped[row] = skip;
        }
        tableModel.fireTableRowsUpdated(0, ends.skipped.length - 1);
        preview.repaint();
        validateSidesSelection();
    }

    private void validateSidesSelection() {
        if (currentRoom == null) {
            return;
        }
        SurroundConfig.SideEnds ends = working.get(currentRoom);
        boolean anyIncluded = false;
        for (boolean s : ends.skipped) {
            if (!s) {
                anyIncluded = true;
                break;
            }
        }
        if (!anyIncluded) {
            warningLabel.setText(text("sidesNoneSelectedWarning", "Al menos un lado debe ser incluido."));
            btnConfirm.setEnabled(false);
        } else {
            warningLabel.setText("");
            btnConfirm.setEnabled(true);
        }
    }

    private String text(String key, String fallback) {
        return UiKit.text(resource, key, fallback);
    }

    private boolean isReversed(Room room) {
        return SurroundGeometryEngine.isTraversalReversed(room, interior && !alwaysExterior.contains(room));
    }

    private List<List<double[]>> obstacleHints(Room face) {
        if (face == null) {
            return Collections.emptyList();
        }
        List<List<double[]>> cached = obstacleHintsCache.get(face);
        if (cached != null) {
            return cached;
        }
        List<List<double[]>> hints = SurroundGeometryEngine.obstacleStretches(home, activeConfig, face, sidePolylines.get(face),
                isReversed(face), obstacles, selectedFurniture, objectDepth);
        obstacleHintsCache.put(face, hints);
        return hints;
    }

    private void toggleExcluded(int side, double[] interval) {
        SurroundConfig.SideEnds ends = working.get(currentRoom);
        ends.excludedSegmentsModifiedByUser = true;
        List<double[]> excluded = ends.excludedSegments.get(side);
        double a = interval[0], b = interval[1];
        boolean covered = false;
        for (double[] seg : excluded) {
            if (seg[0] <= a + 0.5 && seg[1] >= b - 0.5) {
                covered = true;
                break;
            }
        }
        if (covered) {
            List<double[]> kept = new ArrayList<>();
            for (double[] seg : excluded) {
                if (seg[1] <= a + 0.5 || seg[0] >= b - 0.5) {
                    kept.add(seg);
                } else {
                    if (a - seg[0] > 1.0) {
                        kept.add(new double[]{seg[0], a});
                    }
                    if (seg[1] - b > 1.0) {
                        kept.add(new double[]{b, seg[1]});
                    }
                }
            }
            excluded.clear();
            excluded.addAll(kept);
        } else {
            excluded.add(new double[]{a, b});
            SurroundTarget.mergeIntervals(excluded);
        }
        segmentsChanged();
    }

    private List<double[]> shownSegments(List<double[]> stored, double length) {
        List<double[]> shown = new ArrayList<>();
        boolean reversed = isReversed(currentRoom);
        for (int i = 0; i < stored.size(); i++) {
            double[] s = stored.get(reversed ? stored.size() - 1 - i : i);
            shown.add(reversed ? new double[]{length - s[1], length - s[0]} : new double[]{s[0], s[1]});
        }
        return shown;
    }

    private double[] shownToStored(double[] shown, double length) {
        return isReversed(currentRoom) ? new double[]{length - shown[1], length - shown[0]} : shown.clone();
    }

    private interface SideSegmentsEdit {
        void apply(List<double[]> segments, double sideLength);
    }

    private void editSelectedSides(SideSegmentsEdit edit) {
        if (currentRoom == null || table.getSelectedRowCount() == 0) {
            return;
        }
        SurroundConfig.SideEnds ends = working.get(currentRoom);
        ends.excludedSegmentsModifiedByUser = true;
        List<float[][]> polys = sidePolylines.get(currentRoom);
        for (int row : table.getSelectedRows()) {
            edit.apply(ends.excludedSegments.get(row), SideModel.length(polys.get(row)));
        }
        segmentsChanged();
    }

    private void segmentsChanged() {
        int[] rows = table.getSelectedRows();
        for (int row : rows) {
            tableModel.fireTableRowsUpdated(row, row);
        }
        updateSegmentList();
        triggerAsyncPreview();
    }

    private void updateSegmentList() {
        if (segmentListModel == null) {
            return;
        }
        segmentListModel.clear();
        int side = table.getSelectedRow();
        if (currentRoom != null && side >= 0 && dialogMode == DIALOG_SEGMENTS) {
            SurroundConfig.SideEnds ends = working.get(currentRoom);
            if (side < ends.excludedSegments.size()) {
                double length = SideModel.length(sidePolylines.get(currentRoom).get(side));
                for (double[] s : shownSegments(ends.excludedSegments.get(side), length)) {
                    segmentListModel.addElement(String.format("%.1f - %.1f cm", s[0], s[1]));
                }
            }
        }
    }

    private String roomName(Room room) {
        int idx = rooms.indexOf(room);
        if (idx >= 0 && idx < roomNames.size()) {
            return roomNames.get(idx);
        }
        return room.getName() != null ? room.getName() : text("sidesRoomDefault", "Superficie");
    }

    /** Table model backing the side selection, stretch exclusion, and Type 7 start point tables. */
    private class SidesTableModel extends AbstractTableModel {

        @Override
        public int getRowCount() {
            return currentRoom != null && sidePolylines.containsKey(currentRoom)
                    ? sidePolylines.get(currentRoom).size() : 0;
        }

        @Override
        public int getColumnCount() {
            return 3;
        }

        @Override
        public String getColumnName(int c) {
            if (dialogMode == DIALOG_SEGMENTS) {
                String[] cols = {
                    text("sidesSide", "Lado"),
                    text("sidesLength", "Longitud (cm)"),
                    text("segmentsCol", "Tramos excluidos (cm)")
                };
                return cols[c];
            } else if (dialogMode == DIALOG_START_POINT) {
                String[] cols = {
                    text("sidesSide", "Lado"),
                    text("sidesLength", "Longitud (cm)"),
                    text("t7StartCol", "Inicio (cm)")
                };
                return cols[c];
            }
            String[] cols = {
                text("sidesSurround", "Incluir"),
                text("sidesSide", "Lado"),
                text("sidesLength", "Longitud (cm)")
            };
            return cols[c];
        }

        @Override
        public Class<?> getColumnClass(int c) {
            return (dialogMode == DIALOG_SIDES && c == 0) ? Boolean.class : String.class;
        }

        private String formatSideLength(int row) {
            float[][] poly = sidePolylines.get(currentRoom).get(row);
            String len = String.format("%.1f", SideModel.length(poly));
            return poly.length > 2 ? len + " (" + text("sidesCurve", "curva") + ")" : len;
        }

        @Override
        public Object getValueAt(int row, int c) {
            SurroundConfig.SideEnds ends = working.get(currentRoom);
            if (dialogMode == DIALOG_SEGMENTS) {
                switch (c) {
                    case 0:
                        return String.valueOf(row + 1);
                    case 1:
                        return formatSideLength(row);
                    default: {
                        List<double[]> segs = ends.excludedSegments.get(row);
                        if (segs.isEmpty()) {
                            return "\u2014";
                        }
                        List<String> parts = new ArrayList<>();
                        double length = SideModel.length(sidePolylines.get(currentRoom).get(row));
                        for (double[] s : shownSegments(segs, length)) {
                            parts.add(String.format("[%.1f - %.1f]", s[0], s[1]));
                        }
                        return String.join(", ", parts);
                    }
                }
            } else if (dialogMode == DIALOG_START_POINT) {
                switch (c) {
                    case 0:
                        return String.valueOf(row + 1);
                    case 1:
                        return formatSideLength(row);
                    default: {
                        double[] sp = workingStartPoints.get(currentRoom);
                        int startSide = sp != null ? (int) Math.round(sp[0]) : 0;
                        if (row != startSide) {
                            return "\u2014";
                        }
                        double length = SideModel.length(sidePolylines.get(currentRoom).get(row));
                        double nativeDist = sp != null ? sp[1] : (isReversed(currentRoom) ? length : 0.0);
                        double shown = isReversed(currentRoom) ? (length - nativeDist) : nativeDist;
                        return String.format("%.1f cm", Math.max(0.0, Math.min(length, shown)));
                    }
                }
            }
            switch (c) {
                case 0:
                    return !ends.skipped[row];
                case 1:
                    return String.valueOf(row + 1);
                default:
                    return formatSideLength(row);
            }
        }

        @Override
        public boolean isCellEditable(int row, int c) {
            if (dialogMode == DIALOG_SEGMENTS || dialogMode == DIALOG_START_POINT) {
                return false;
            }
            SurroundConfig.SideEnds ends = working.get(currentRoom);
            return c == 0 && (row >= ends.forbidden.length || !ends.forbidden[row]);
        }

        @Override
        public void setValueAt(Object value, int row, int c) {
            if (dialogMode == DIALOG_SIDES && c == 0) {
                setSkipped(new int[]{row}, !Boolean.TRUE.equals(value));
            }
        }
    }

    /**
     * Drawing of the editor: the preview drawing ({@link OutlinePreview}: fitted view, zoom, pan, floor,
     * objects and loading badge) of the current face only, with the sides drawn for the mode in use:
     * selection in every mode, overlaps outside the stretches mode, hints, excluded stretches and picking
     * in the stretches mode, and the start marker in the type 7 mode. Objects are only computed for the last two.
     */
    private class RoomPreview extends OutlinePreview {

        @Override
        void clicked(MouseEvent e) {
            int side = sideAt(e.getPoint());
            if (side < 0) {
                return;
            }
            // 1. Type 7 starting point mode: click anywhere along a side to set start point
            if (dialogMode == DIALOG_START_POINT) {
                double nativeAt = positionAlong(side, e.getPoint());
                double len = SideModel.length(sidePolylines.get(currentRoom).get(side));
                setType7StartShown(side, isReversed(currentRoom) ? (len - nativeAt) : nativeAt);
                return;
            }
            // 2. Interactive stretch picking mode in DIALOG_SEGMENTS
            if (dialogMode == DIALOG_SEGMENTS && pickingInDrawing) {
                double nativeAt = positionAlong(side, e.getPoint());
                if (pickSide < 0 || pickSide != side || (!Double.isNaN(pickPos1) && !Double.isNaN(pickPos2))) {
                    pickSide = side;
                    pickPos1 = nativeAt;
                    pickPos2 = Double.NaN;
                    table.setRowSelectionInterval(side, side);
                    btnAcceptPick.setEnabled(false);
                } else {
                    pickPos2 = nativeAt;
                    btnAcceptPick.setEnabled(Math.abs(pickPos2 - pickPos1) >= 0.1);
                }
                repaint();
                return;
            }
            // 3. Normal click on obstacle hints in DIALOG_SEGMENTS
            if (dialogMode == DIALOG_SEGMENTS && !e.isControlDown() && !e.isShiftDown()) {
                double at = positionAlong(side, e.getPoint());
                double tolerance = 8 / Math.max(1e-4, view.getScaleX());
                List<List<double[]>> allHints = obstacleHints(currentRoom);
                if (side < allHints.size()) {
                    for (double[] hint : allHints.get(side)) {
                        if (at >= hint[0] - tolerance && at <= hint[1] + tolerance) {
                            table.setRowSelectionInterval(side, side);
                            toggleExcluded(side, hint);
                            return;
                        }
                    }
                }
            }
            if (dialogMode == DIALOG_SIDES && !e.isControlDown() && !e.isShiftDown()) {
                setSkipped(new int[]{side}, !working.get(currentRoom).skipped[side]);
                table.setRowSelectionInterval(side, side);
            } else if (e.isControlDown() || e.isShiftDown()) {
                if (table.isRowSelected(side)) {
                    table.removeRowSelectionInterval(side, side);
                } else {
                    table.addRowSelectionInterval(side, side);
                }
            } else {
                table.setRowSelectionInterval(side, side);
            }
        }

        /** Feeds the shared drawing with the current face and, when computed, its objects. */
        @Override
        protected void paintComponent(Graphics g) {
            SurroundGeometryEngine engine = livePreviewEnabled ? asyncCompletedEngine : null;
            faces = currentRoom != null ? Collections.singletonList(currentRoom) : Collections.<Room>emptyList();
            placed = engine != null && currentRoom != null ? asyncPlaced : Collections.<HomePieceOfFurniture>emptyList();
            isCorner = engine != null ? engine::isCornerPiece : p -> false;
            baseWidth = SideEndsPanel.this.baseWidth;
            baseDepth = objectDepth;
            super.paintComponent(g);
        }

        private Point2D.Double toScreen(float[] pt) {
            return (Point2D.Double) view.transform(new Point2D.Double(pt[0], pt[1]), new Point2D.Double());
        }

        /** Screen direction of a side at its middle, to orient the direction arrow. */
        private double[] middleDirection(float[][] poly) {
            double total = 0;
            double[] lengths = new double[poly.length - 1];
            for (int i = 0; i < lengths.length; i++) {
                lengths[i] = Math.hypot(poly[i + 1][0] - poly[i][0], poly[i + 1][1] - poly[i][1]);
                total += lengths[i];
            }
            double half = total / 2;
            for (int i = 0; i < lengths.length; i++) {
                if (half <= lengths[i] || i == lengths.length - 1) {
                    double dx = poly[i + 1][0] - poly[i][0], dy = poly[i + 1][1] - poly[i][1];
                    double len = Math.max(1e-6, Math.hypot(dx, dy));
                    return new double[]{dx / len, dy / len};
                }
                half -= lengths[i];
            }
            return new double[]{1, 0};
        }

        /** Distance (cm) along side i of the point of the side nearest to a screen point. */
        private double positionAlong(int i, Point point) {
            float[][] poly = sidePolylines.get(currentRoom).get(i);
            double bestDist = Double.MAX_VALUE, bestAlong = 0, along = 0;
            for (int k = 0; k + 1 < poly.length; k++) {
                Point2D.Double a = toScreen(poly[k]), b = toScreen(poly[k + 1]);
                double segWorld = Math.hypot(poly[k + 1][0] - poly[k][0], poly[k + 1][1] - poly[k][1]);
                double d = Line2D.ptSegDist(a.x, a.y, b.x, b.y, point.x, point.y);
                if (d < bestDist) {
                    bestDist = d;
                    double dx = b.x - a.x, dy = b.y - a.y, len2 = dx * dx + dy * dy;
                    double f = len2 > 1e-6 ? Math.max(0, Math.min(1, ((point.x - a.x) * dx + (point.y - a.y) * dy) / len2)) : 0;
                    bestAlong = along + f * segWorld;
                }
                along += segWorld;
            }
            return bestAlong;
        }

        /** Index of the side within 14 pixels of a screen point, or -1. */
        private int sideAt(Point point) {
            if (currentRoom == null) {
                return -1;
            }
            List<float[][]> sides = sidePolylines.get(currentRoom);
            int best = -1;
            double bestDist = 14;
            for (int i = 0; i < sides.size(); i++) {
                float[][] poly = sides.get(i);
                for (int k = 0; k + 1 < poly.length; k++) {
                    Point2D.Double a = toScreen(poly[k]), b = toScreen(poly[k + 1]);
                    double d = Line2D.ptSegDist(a.x, a.y, b.x, b.y, point.x, point.y);
                    if (d < bestDist) {
                        bestDist = d;
                        best = i;
                    }
                }
            }
            return best;
        }

        private void interval(Graphics2D g2, float[][] poly, double d0, double d1, Color color, float width) {
            drawStretch(g2, poly, d0, d1, color, new BasicStroke(width, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND));
        }

        private void marker(Graphics2D g2, float[][] poly, double dist) {
            SidePath sp = new SidePath(poly, 0, false);
            double[] pt = sp.pointAt(Math.max(0.0, Math.min(sp.length, dist)));
            Point2D.Double s = toScreen(new float[]{(float) pt[0], (float) pt[1]});
            double r = 5.0;
            g2.setColor(Color.WHITE);
            g2.fill(new Ellipse2D.Double(s.x - r - 1.5, s.y - r - 1.5, 2 * (r + 1.5), 2 * (r + 1.5)));
            g2.setColor(PICK_MARKER_COLOR);
            g2.fill(new Ellipse2D.Double(s.x - r, s.y - r, 2 * r, 2 * r));
        }

        private void drawTopLeftDirectionArrow(Graphics2D g2, double dirX, double dirY) {
            double cx = 18, cy = 18;
            g2.setColor(new Color(245, 247, 250, 220));
            g2.fill(new Ellipse2D.Double(cx - 12, cy - 12, 24, 24));
            g2.setColor(UiKit.CARD_BORDER);
            g2.setStroke(new BasicStroke(1f));
            g2.draw(new Ellipse2D.Double(cx - 12, cy - 12, 24, 24));

            g2.setColor(ACCENT);
            g2.setStroke(new BasicStroke(2.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.draw(new Line2D.Double(cx - dirX * 7, cy - dirY * 7, cx + dirX * 6, cy + dirY * 6));
            Path2D.Double head = new Path2D.Double();
            head.moveTo(cx + dirX * 8.5, cy + dirY * 8.5);
            head.lineTo(cx + dirX * 2.0 - dirY * 3.8, cy + dirY * 2.0 + dirX * 3.8);
            head.lineTo(cx + dirX * 2.0 + dirY * 3.8, cy + dirY * 2.0 - dirX * 3.8);
            head.closePath();
            g2.fill(head);
        }

        /** Objects first, then the sides of the mode in use (selected ones on top), markers, vertices and arrow. */
        @Override
        void paintPlan(Graphics2D g2) {
            paintPieces(g2);
            List<float[][]> sides = sidePolylines.get(currentRoom);
            int n = sides != null ? sides.size() : 0;
            SurroundConfig.SideEnds ends = working.get(currentRoom);
            boolean reversed = isReversed(currentRoom);
            boolean anySelected = table.getSelectedRowCount() > 0;
            boolean segmentsMode = dialogMode == DIALOG_SEGMENTS;
            List<List<double[]>> allHints = segmentsMode ? obstacleHints(currentRoom) : Collections.emptyList();

            for (int pass = 0; pass < 2; pass++) {
                for (int i = 0; i < n; i++) {
                    boolean sel = table.isRowSelected(i);
                    if (sel != (pass == 1)) {
                        continue;
                    }
                    float[][] poly = sides.get(i);
                    Path2D.Double path = path(poly, false);

                    if (ends.skipped[i]) {
                        if (sel) {
                            g2.setColor(new Color(ACCENT.getRed(), ACCENT.getGreen(), ACCENT.getBlue(), 45));
                            g2.setStroke(new BasicStroke(8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                            g2.draw(path);
                        }
                        g2.setColor(sel ? ACCENT : new Color(180, 185, 194, anySelected ? 110 : 210));
                        g2.setStroke(new BasicStroke(sel ? 3.2f : 1.8f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND,
                                10f, new float[]{5f, 4f}, 0f));
                        g2.draw(path);
                        continue;
                    }

                    if (sel) {
                        g2.setColor(new Color(ACCENT.getRed(), ACCENT.getGreen(), ACCENT.getBlue(), 60));
                        g2.setStroke(new BasicStroke(9.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                        g2.draw(path);
                        g2.setColor(ACCENT);
                        g2.setStroke(new BasicStroke(4.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    } else {
                        g2.setColor(new Color(WALL.getRed(), WALL.getGreen(), WALL.getBlue(), anySelected ? 110 : 235));
                        g2.setStroke(new BasicStroke(anySelected ? 2.2f : 2.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    }
                    g2.draw(path);

                    if (!segmentsMode) {
                        if (i < ends.overlapSegments.size()) {
                            for (double[] ov : ends.overlapSegments.get(i)) {
                                interval(g2, poly, ov[0], ov[1], OVERLAP_COLOR, sel ? 5f : 3.5f);
                            }
                        }
                        continue;
                    }
                    if (i < allHints.size()) {
                        for (double[] hint : allHints.get(i)) {
                            int kind = (int) Math.round(hint[2]);
                            Color hintColor = kind == 0 ? DOOR_HINT
                                    : kind == 1 ? WINDOW_HINT
                                    : kind == 2 ? COLLISION_HINT
                                    : kind == 3 ? WALL_OVERLAP_HINT
                                    : FLOOR_OVERLAP_HINT;
                            interval(g2, poly, hint[0], hint[1], hintColor, 10f);
                        }
                    }
                    if (i < ends.excludedSegments.size()) {
                        List<double[]> segs = ends.excludedSegments.get(i);
                        int selSegIdx = (sel && segmentList != null) ? segmentList.getSelectedIndex() : -1;
                        int highlightStoredIdx = selSegIdx >= 0 ? (reversed ? segs.size() - 1 - selSegIdx : selSegIdx) : -1;
                        for (int sIdx = 0; sIdx < segs.size(); sIdx++) {
                            double[] ex = segs.get(sIdx);
                            if (sIdx == highlightStoredIdx) {
                                interval(g2, poly, ex[0], ex[1], SELECTED_SEG_COLOR, 8.5f);
                            }
                            interval(g2, poly, ex[0], ex[1], EXCLUDED, sel ? 5.2f : 4f);
                        }
                    }
                }
            }

            // Interactive picking markers in DIALOG_SEGMENTS
            if (segmentsMode && pickingInDrawing && pickSide >= 0 && pickSide < n) {
                float[][] poly = sides.get(pickSide);
                if (!Double.isNaN(pickPos1) && !Double.isNaN(pickPos2)) {
                    interval(g2, poly, Math.min(pickPos1, pickPos2), Math.max(pickPos1, pickPos2), PICK_MARKER_COLOR, 6.0f);
                }
                if (!Double.isNaN(pickPos1)) {
                    marker(g2, poly, pickPos1);
                }
                if (!Double.isNaN(pickPos2)) {
                    marker(g2, poly, pickPos2);
                }
            }

            // Type 7 start marker in DIALOG_START_POINT
            if (dialogMode == DIALOG_START_POINT && n > 0) {
                double[] sp = workingStartPoints.get(currentRoom);
                int sSide = sp != null ? Math.max(0, Math.min(n - 1, (int) Math.round(sp[0]))) : 0;
                float[][] poly = sides.get(sSide);
                marker(g2, poly, sp != null ? sp[1] : (reversed ? SideModel.length(poly) : 0.0));
            }

            if (n > 1) {
                for (int i = 0; i < n; i++) {
                    Point2D.Double s = toScreen(sides.get(i)[0]);
                    boolean adjSelected = table.isRowSelected(i) || table.isRowSelected((i - 1 + n) % n);
                    g2.setColor(adjSelected ? ACCENT : new Color(WALL.getRed(), WALL.getGreen(), WALL.getBlue(), anySelected ? 95 : 190));
                    double r = adjSelected ? 3.2 : 2.2;
                    g2.fill(new Ellipse2D.Double(s.x - r, s.y - r, 2 * r, 2 * r));
                }
            }

            if (n > 0) {
                int refSide = table.getSelectedRow();
                for (int i = 0; refSide < 0 && i < n; i++) {
                    if (!ends.skipped[i]) {
                        refSide = i;
                    }
                }
                double[] d = middleDirection(sides.get(Math.max(0, refSide)));
                drawTopLeftDirectionArrow(g2, reversed ? -d[0] : d[0], reversed ? -d[1] : d[1]);
            }
        }
    }
}
