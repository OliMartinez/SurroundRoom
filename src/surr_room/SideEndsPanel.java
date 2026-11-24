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
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.KeyboardFocusManager;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.concurrent.CancellationException;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFormattedTextField;
import javax.swing.JLabel;
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
import javax.swing.border.Border;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.text.DefaultFormatter;
import javax.swing.text.JTextComponent;

/**
 * Embedded interactive 2D editor for excluding rooms ({@link #DIALOG_ROOMS}), excluding sides
 * ({@link #DIALOG_SIDES}), excluding sub-stretches ({@link #DIALOG_SEGMENTS}), or setting the
 * Type 7 starting point ({@link #DIALOG_START_POINT}).
 */
public class SideEndsPanel extends JPanel {

    public static final int DIALOG_SIDES = 0;
    public static final int DIALOG_SEGMENTS = 1;
    public static final int DIALOG_START_POINT = 2;
    public static final int DIALOG_ROOMS = 3;

    private static final Color ACCENT = UiKit.ACCENT;
    private static final Color WALL = OutlinePreview.WALL;
    private static final Color SELECTED_SEG_COLOR = new Color(255, 195, 0);
    private static final Color OVERLAP_COLOR = new Color(180, 120, 40);
    private static final Color DOOR_HINT = new Color(22, 160, 145, 155);
    private static final Color WINDOW_HINT = new Color(0, 180, 216, 165);
    private static final Color COLLISION_HINT = new Color(230, 126, 34, 155);
    private static final Color WALL_OVERLAP_HINT = new Color(142, 68, 173, 165);
    private static final Color FLOOR_OVERLAP_HINT = new Color(216, 27, 96, 160);
    private static final Color PICK_MARKER_COLOR = new Color(39, 174, 96);

    public interface ConfirmListener {
        void onConfirm(Map<Room, SurroundConfig.SideEnds> result, int dialogMode);
    }

    private final ResourceBundle resource;
    private final ConfirmListener confirmListener;
    private final Runnable cancelListener;

    private List<Room> rooms = Collections.emptyList();
    private List<String> roomNames = Collections.emptyList();
    private boolean interior;
    private boolean mergeCollinear;
    private Set<Room> alwaysExterior = Collections.emptySet();
    private int dialogMode = DIALOG_SIDES;
    private double defaultHalfObjectLength = 15.0;

    private Home home;
    private List<HomePieceOfFurniture> selectedFurniture = Collections.emptyList();
    private SurroundConfig activeConfig = new SurroundConfig();
    private List<HomePieceOfFurniture> obstacles = Collections.emptyList();
    private double objectDepth = 30;
    private double baseWidth = 30;
    private boolean livePreviewEnabled = true;

    private final Map<Room, List<List<double[]>>> obstacleHintsCache = new HashMap<>();
    private final Map<Room, SurroundConfig.SideEnds> working = new HashMap<>();
    private final Set<Room> workingExcludedRooms = new HashSet<>();
    private final Map<Room, double[]> workingStartPoints = new HashMap<>();
    private final Map<Room, List<float[][]>> sidePolylines = new HashMap<>();
    private Room currentRoom;

    private final RoomPreview preview = new RoomPreview();
    private final JTable table;
    private final SidesTableModel tableModel = new SidesTableModel();
    private JPanel segmentsTreePanel;

    private int activeSegmentSide = 0;
    private int selectedSegmentSide = -1;
    private int selectedSegmentIndex = -1;

    private JSpinner startSpin, endSpin;
    private JButton addOrUpdateBtn, clearAllBtn;
    private JLabel activeSideInfoLabel;
    private boolean updatingSegmentSpinners;

    // Direct drawing drag/click state for DIALOG_SEGMENTS
    private int pickSide = -1;
    private double pickPos1 = Double.NaN, pickPos2 = Double.NaN;
    private int draggingHandle, hoveredHandle; // 0 = none, 1 = pickPos1, 2 = pickPos2
    private int draggingNewSide = -1;
    private double dragOriginNative = Double.NaN;
    private Point dragPressScreen;
    private boolean dragMoved;
    private int draggingOrigStoredIdx = -1;
    private double[] draggingOrigBackup;

    // Hover readout state (local side position only) in DIALOG_SEGMENTS
    private int hoverSide = -1;
    private double hoverNativePos = Double.NaN;
    private Point hoverScreenPt;

    private JSpinner startPointSpinner;
    private boolean updatingStartSpinner;

    private volatile SurroundGeometryEngine runningEngine;
    private volatile int asyncSeq;
    private List<HomePieceOfFurniture> asyncPlaced = Collections.emptyList();
    private SurroundGeometryEngine asyncCompletedEngine;
    private final Timer loadingOverlayTimer = new Timer(180, e -> preview.setLoading(true, text("previewLoading", "Cargando\u2026")));

    private final JLabel warningLabel = new JLabel();
    private final JButton btnConfirm = new JButton();
    private final JButton btnCancel = new JButton();

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
                rebuildSegmentsTree();
                preview.repaint();
            }
        });

        btnConfirm.addActionListener(e -> {
            stopEditingAndCancelAsync();
            if (this.confirmListener != null) {
                this.confirmListener.onConfirm(working, dialogMode);
            }
        });
        btnCancel.addActionListener(e -> discardAndClose());

        registerKeyboardAction(e -> {
            if (KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner() instanceof JTextComponent) {
                return;
            }
            if (dialogMode == DIALOG_SEGMENTS && selectedSegmentSide >= 0 && selectedSegmentIndex >= 0) {
                deleteStretch(selectedSegmentSide, selectedSegmentIndex);
            }
        }, KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);

        setPreferredSize(new Dimension(UiKit.CARD_WIDTH * 4 + UiKit.GAP * 3, 470));
    }

    private void stopEditingAndCancelAsync() {
        cancelAsyncPreview();
        if (table.isEditing()) {
            table.getCellEditor().stopCellEditing();
        }
    }

    public int getDialogMode() {
        return dialogMode;
    }

    /** True while a Click-1 pending stretch or interactive handle/side drag is active on the canvas. */
    public boolean isPickingInDrawing() {
        return dialogMode == DIALOG_SEGMENTS && (draggingHandle != 0
                || (draggingNewSide >= 0 && dragMoved)
                || (pickSide >= 0 && !Double.isNaN(pickPos1) && Double.isNaN(pickPos2)));
    }

    public void cancelPicking() {
        if (draggingHandle != 0 && draggingOrigStoredIdx >= 0 && draggingOrigBackup != null && currentRoom != null) {
            SurroundConfig.SideEnds ends = working.get(currentRoom);
            if (ends != null && pickSide >= 0 && pickSide < ends.excludedSegments.size()) {
                List<double[]> segs = ends.excludedSegments.get(pickSide);
                if (draggingOrigStoredIdx < segs.size()) {
                    segs.set(draggingOrigStoredIdx, draggingOrigBackup.clone());
                }
            }
        }
        resetDragState();
        if (selectedSegmentSide >= 0 && selectedSegmentIndex >= 0) {
            selectExistingStretch(selectedSegmentSide, selectedSegmentIndex);
        } else {
            clearDraftStretch();
            preview.repaint();
        }
    }

    private void resetDragState() {
        draggingHandle = 0;
        hoveredHandle = 0;
        draggingNewSide = -1;
        dragOriginNative = Double.NaN;
        dragPressScreen = null;
        dragMoved = false;
        draggingOrigStoredIdx = -1;
        draggingOrigBackup = null;
    }

    public void discardAndClose() {
        stopEditingAndCancelAsync();
        if (cancelListener != null) {
            cancelListener.run();
        }
    }

    public Map<Room, SurroundConfig.SideEnds> commitSilently() {
        stopEditingAndCancelAsync();
        return working;
    }

    public void selectRoom(Room room) {
        if (room == null || room == currentRoom || !rooms.contains(room)) {
            return;
        }
        if (table.isEditing()) {
            table.getCellEditor().stopCellEditing();
        }
        resetDragState();
        clearDraftStretch();
        currentRoom = room;
        activeSegmentSide = 0;
        selectedSegmentSide = -1;
        selectedSegmentIndex = -1;
        tableModel.fireTableDataChanged();
        if (table.getRowCount() > 0) {
            table.setRowSelectionInterval(0, 0);
        }
        if (dialogMode == DIALOG_START_POINT) {
            syncStartPointControls();
        }
        rebuildSegmentsTree();
        validateSidesSelection();
        triggerAsyncPreview();
    }

    public void refreshPreviewConfig(SurroundConfig snapshotConfig) {
        if (snapshotConfig != null) {
            this.activeConfig = snapshotConfig;
            this.obstacleHintsCache.clear();
            triggerAsyncPreview();
        }
    }

    @Override
    public Dimension getPreferredSize() {
        int w = UiKit.CARD_WIDTH * 4 + UiKit.GAP * 3;
        int bottomH = dialogMode == DIALOG_SEGMENTS ? 176 : 94;
        return new Dimension(w, 24 + 250 + bottomH + 36 + 18);
    }

    @Override
    public Dimension getMinimumSize() {
        return getPreferredSize();
    }

    public void zoomIn() {
        preview.zoomIn();
    }

    public void zoomOut() {
        preview.zoomOut();
    }

    public void resetZoom() {
        preview.resetZoom();
    }

    public Set<Room> getWorkingExcludedRooms() {
        return new HashSet<>(workingExcludedRooms);
    }

    public Map<Room, double[]> getWorkingStartPoints() {
        Map<Room, double[]> copy = new HashMap<>();
        for (Map.Entry<Room, double[]> entry : workingStartPoints.entrySet()) {
            if (entry.getValue() != null) {
                copy.put(entry.getKey(), entry.getValue().clone());
            }
        }
        return copy;
    }

    public void setLivePreviewEnabled(boolean enabled) {
        this.livePreviewEnabled = enabled;
        if (!enabled) {
            cancelAsyncPreview();
            asyncPlaced = Collections.emptyList();
            asyncCompletedEngine = null;
        }
        triggerAsyncPreview();
    }

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

    public void startEditing(List<Room> rooms, List<String> roomNames,
            Map<Room, SurroundConfig.SideEnds> current, Set<Room> excludedRooms, boolean interior, Set<Room> alwaysExterior,
            int dialogMode, boolean mergeCollinear, double defaultHalfObjectLength) {
        this.rooms = rooms != null ? rooms : Collections.emptyList();
        this.roomNames = roomNames != null ? roomNames : Collections.emptyList();
        this.interior = interior;
        this.mergeCollinear = mergeCollinear;
        this.alwaysExterior = alwaysExterior != null ? alwaysExterior : Collections.emptySet();
        this.dialogMode = dialogMode;
        this.defaultHalfObjectLength = Math.max(1.0, defaultHalfObjectLength);
        resetDragState();
        this.pickSide = -1;
        this.pickPos1 = Double.NaN;
        this.pickPos2 = Double.NaN;
        this.hoverSide = -1;
        this.hoverNativePos = Double.NaN;
        this.hoverScreenPt = null;
        this.activeSegmentSide = 0;
        this.selectedSegmentSide = -1;
        this.selectedSegmentIndex = -1;

        sidePolylines.clear();
        working.clear();
        workingExcludedRooms.clear();
        obstacleHintsCache.clear();
        if (excludedRooms != null) {
            for (Room r : excludedRooms) {
                if (this.rooms.contains(r)) {
                    workingExcludedRooms.add(r);
                }
            }
        }

        for (Room room : this.rooms) {
            List<float[][]> polys = SideModel.polylines(room, mergeCollinear);
            sidePolylines.put(room, polys);
            int n = polys.size();
            SurroundConfig.SideEnds existing = current != null ? current.get(room) : null;
            working.put(room, (existing != null && existing.skipped.length == n)
                    ? existing.copy() : new SurroundConfig.SideEnds(n));
        }
        this.currentRoom = null;
        for (Room r : this.rooms) {
            if (!workingExcludedRooms.contains(r)) {
                this.currentRoom = r;
                break;
            }
        }
        if (this.currentRoom == null && !this.rooms.isEmpty()) {
            this.currentRoom = this.rooms.get(0);
        }

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
        rebuildSegmentsTree();
        validateSidesSelection();
        triggerAsyncPreview();
    }

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

    /** Runs off-EDT placement preview for every editor mode, superseding stale runs cleanly. */
    private void triggerAsyncPreview() {
        cancelAsyncPreview();
        if (!livePreviewEnabled || selectedFurniture.isEmpty()) {
            asyncPlaced = Collections.emptyList();
            asyncCompletedEngine = null;
            preview.repaint();
            return;
        }
        final List<Room> targetFaces;
        if (dialogMode == DIALOG_ROOMS) {
            targetFaces = new ArrayList<>();
            for (Room r : rooms) {
                if (!workingExcludedRooms.contains(r)) {
                    targetFaces.add(r);
                }
            }
        } else {
            targetFaces = currentRoom != null ? Collections.singletonList(currentRoom) : Collections.<Room>emptyList();
        }
        if (targetFaces.isEmpty()) {
            asyncPlaced = Collections.emptyList();
            asyncCompletedEngine = null;
            preview.repaint();
            return;
        }
        final int seq = ++asyncSeq;
        final SurroundConfig cfg = cloneConfigForPreview(dialogMode == DIALOG_ROOMS ? null : currentRoom);
        final SurroundGeometryEngine engine = new SurroundGeometryEngine(home, null, targetFaces, selectedFurniture);
        runningEngine = engine;
        loadingOverlayTimer.restart();

        Thread worker = new Thread(() -> {
            try {
                List<HomePieceOfFurniture> computed = engine.previewFirstLevel(cfg);
                SwingUtilities.invokeLater(() -> finishAsyncPreview(seq, computed, engine));
            } catch (CancellationException ignored) {
            } catch (RuntimeException ex) {
                SwingUtilities.invokeLater(() -> finishAsyncPreview(seq, asyncPlaced, asyncCompletedEngine));
            }
        }, "SurroundSideEndsPreview-" + seq);
        worker.setDaemon(true);
        worker.start();
        preview.repaint();
    }

    private void finishAsyncPreview(int seq, List<HomePieceOfFurniture> computed, SurroundGeometryEngine engine) {
        if (seq != asyncSeq) {
            return;
        }
        loadingOverlayTimer.stop();
        preview.setLoading(false, null);
        runningEngine = null;
        asyncPlaced = computed;
        asyncCompletedEngine = engine;
        preview.repaint();
    }

    private SurroundConfig cloneConfigForPreview(Room room) {
        SurroundConfig cfg = activeConfig;
        if (dialogMode == DIALOG_ROOMS) {
            cfg.excludedRooms = new HashSet<>(workingExcludedRooms);
            return cfg;
        }
        if (room != null) {
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
        }
        return cfg;
    }

    private void buildUi() {
        removeAll();
        String modeTitle, summaryText;
        if (dialogMode == DIALOG_SEGMENTS) {
            modeTitle = text("segmentsTitle", "Excluded stretches");
            summaryText = text("segmentsShortHint", "Drag or click a side to exclude a stretch, or drag its endpoints to adjust.");
        } else if (dialogMode == DIALOG_START_POINT) {
            modeTitle = text("t7StartTitle", "Starting point (Type 7)");
            summaryText = text("t7StartShortHint", "Click a position on any side in the drawing or set its distance.");
        } else if (dialogMode == DIALOG_ROOMS) {
            modeTitle = text("roomsTitle", "Exclude rooms");
            summaryText = text("roomsShortHint", "Click a room in the drawing or check boxes to exclude rooms.");
        } else {
            modeTitle = text("sidesTitle", "Exclude sides");
            summaryText = text("sidesShortHint", "Click a side in the drawing or check boxes to exclude sides.");
        }

        JLabel titleLabel = new JLabel(modeTitle + " \u2014 ");
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD));
        JPanel hintRow = UiKit.row(4, titleLabel, UiKit.hint(summaryText));
        hintRow.setOpaque(false);
        add(hintRow, BorderLayout.NORTH);

        JPanel bottomControls;
        if (dialogMode == DIALOG_SEGMENTS) {
            segmentsTreePanel = new JPanel();
            segmentsTreePanel.setLayout(new BoxLayout(segmentsTreePanel, BoxLayout.Y_AXIS));
            segmentsTreePanel.setBackground(Color.WHITE);
            JScrollPane treeScroll = new JScrollPane(segmentsTreePanel);
            treeScroll.setPreferredSize(new Dimension(380, 140));
            treeScroll.getVerticalScrollBar().setUnitIncrement(14);

            bottomControls = new JPanel(new BorderLayout(10, 4));
            bottomControls.setOpaque(false);
            bottomControls.add(treeScroll, BorderLayout.WEST);
            bottomControls.add(createSegmentsEditorPanel(), BorderLayout.CENTER);
            bottomControls.add(createSegmentsLegendBar(), BorderLayout.SOUTH);
        } else {
            JScrollPane scroll = new JScrollPane(table);
            bottomControls = new JPanel(new BorderLayout(8, 0));
            bottomControls.setOpaque(false);
            if (dialogMode == DIALOG_START_POINT) {
                scroll.setPreferredSize(new Dimension(265, 90));
                bottomControls.add(scroll, BorderLayout.WEST);
                bottomControls.add(createStartPointEditorPanel(), BorderLayout.CENTER);
            } else {
                scroll.setPreferredSize(new Dimension(0, 90));
                bottomControls.add(scroll, BorderLayout.CENTER);
            }
        }

        JPanel centerPanel = new JPanel(new BorderLayout(0, 6));
        centerPanel.setOpaque(false);
        centerPanel.add(preview, BorderLayout.CENTER);
        centerPanel.add(bottomControls, BorderLayout.SOUTH);
        add(centerPanel, BorderLayout.CENTER);

        warningLabel.setForeground(new Color(185, 95, 0));
        btnConfirm.setText(text("apply", "Aplicar"));
        btnCancel.setText(text("discard", "Descartar"));
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

    private JPanel createSegmentsLegendBar() {
        JPanel legend = new JPanel(new FlowLayout(FlowLayout.LEADING, 5, 1));
        legend.setOpaque(false);
        JLabel[] items = {
            colorSwatchLabel(OutlinePreview.EXCLUDED, text("legendExcluded", "Tramo excluido")),
            colorSwatchLabel(SELECTED_SEG_COLOR, text("legendSelectedSeg", "Seleccionado")),
            colorSwatchLabel(DOOR_HINT, text("legendDoors", "Puertas")),
            colorSwatchLabel(WINDOW_HINT, text("legendWindows", "Ventanas")),
            colorSwatchLabel(COLLISION_HINT, text("legendFurniture", "Muebles")),
            colorSwatchLabel(WALL_OVERLAP_HINT, text("legendWalls", "Paredes que tocan/traslapan")),
            colorSwatchLabel(FLOOR_OVERLAP_HINT, text("legendFloors", "Suelos que traslapan"))
        };
        for (JLabel item : items) {
            item.setFont(item.getFont().deriveFont(10.5f));
            legend.add(item);
        }
        return legend;
    }

    private static JLabel colorSwatchLabel(final Color color, String labelText) {
        Icon icon = new Icon() {
            @Override
            public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 235));
                g2.fillRoundRect(x, y + 1, 12, 8, 3, 3);
                g2.setColor(new Color(0, 0, 0, 70));
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

    private void configureTableColumns() {
        DefaultTableCellRenderer center = new DefaultTableCellRenderer();
        center.setHorizontalAlignment(SwingConstants.CENTER);
        if (dialogMode == DIALOG_ROOMS) {
            table.getColumnModel().getColumn(0).setPreferredWidth(65);
            table.getColumnModel().getColumn(0).setMaxWidth(85);
            table.getColumnModel().getColumn(1).setPreferredWidth(460);
            table.getColumnModel().getColumn(2).setPreferredWidth(75);
            table.getColumnModel().getColumn(2).setMaxWidth(100);
            table.getColumnModel().getColumn(2).setCellRenderer(center);
            return;
        }
        int firstCentered = dialogMode == DIALOG_SIDES ? 1 : 0;
        for (int c = firstCentered; c <= 1 && c < table.getColumnCount(); c++) {
            table.getColumnModel().getColumn(c).setCellRenderer(center);
        }
        if (dialogMode == DIALOG_START_POINT) {
            int[] widths = {38, 76, 150};
            for (int c = 0; c < widths.length; c++) {
                table.getColumnModel().getColumn(c).setPreferredWidth(widths[c]);
            }
        } else {
            table.getColumnModel().getColumn(0).setPreferredWidth(65);
            table.getColumnModel().getColumn(0).setMaxWidth(85);
            table.getColumnModel().getColumn(1).setPreferredWidth(75);
            table.getColumnModel().getColumn(1).setMaxWidth(100);
            table.getColumnModel().getColumn(2).setPreferredWidth(460);
        }
    }

    private JPanel createStartPointEditorPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setOpaque(false);
        startPointSpinner = UiKit.spinner(new SpinnerNumberModel(0.0, 0.0, 100000.0, 1.0), 5);
        JButton resetStartBtn = new JButton(text("t7StartReset", "Al inicio del contorno"));

        startPointSpinner.addChangeListener(e -> {
            if (!updatingStartSpinner && currentRoom != null) {
                setType7StartShown(Math.max(0, table.getSelectedRow()), ((Number) startPointSpinner.getValue()).doubleValue());
            }
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

        UiKit.addOptionRow(panel, 0, 0, new JLabel(text("t7StartPosLabel", "Posici\u00f3n en el lado:")),
                startPointSpinner, new JLabel("cm"));
        UiKit.addOptionRow(panel, 1, 0, resetStartBtn);
        UiKit.addFiller(panel);
        syncStartPointControls();
        return panel;
    }

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
        workingStartPoints.put(currentRoom, new double[]{side, nativeToShown(clampedShown, len)});
        updatingStartSpinner = true;
        try {
            if (startPointSpinner != null) {
                startPointSpinner.setValue(Math.round(clampedShown * 10.0) / 10.0);
            }
            tableModel.fireTableDataChanged();
            table.setRowSelectionInterval(side, side);
        } finally {
            updatingStartSpinner = false;
        }
        triggerAsyncPreview();
    }

    private void syncStartPointControls() {
        if (currentRoom == null || startPointSpinner == null) {
            return;
        }
        List<float[][]> polys = sidePolylines.get(currentRoom);
        if (polys == null || polys.isEmpty()) {
            return;
        }
        double[] sp = startPoint(currentRoom);
        int side = Math.max(0, Math.min(polys.size() - 1, (int) Math.round(sp[0])));
        double len = SideModel.length(polys.get(side));
        double shownDist = nativeToShown(sp[1], len);
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

    private static String formatCm(double v) {
        double r = Math.round(v * 10.0) / 10.0;
        return Math.abs(r - Math.rint(r)) < 1e-6
                ? String.format(Locale.US, "%.0f", r) : String.format(Locale.US, "%.1f", r);
    }

    /** Symmetric mapping between native polyline distance and visual shown distance along a side. */
    private double nativeToShown(double dist, double length) {
        double clamped = Math.max(0.0, Math.min(length, dist));
        return isShownReversed(currentRoom) ? (length - clamped) : clamped;
    }

    private int mirrorIndex(int idx, int size) {
        return isShownReversed(currentRoom) ? (size - 1 - idx) : idx;
    }

    private void setSegmentSpinnerValues(double fromShown, double toShown) {
        if (startSpin == null || endSpin == null) {
            return;
        }
        updatingSegmentSpinners = true;
        try {
            startSpin.setValue(Math.round(Math.max(0.0, fromShown) * 10.0) / 10.0);
            endSpin.setValue(Math.round(Math.max(0.0, toShown) * 10.0) / 10.0);
        } finally {
            updatingSegmentSpinners = false;
        }
    }

    private void clearDraftStretch() {
        pickSide = -1;
        pickPos1 = Double.NaN;
        pickPos2 = Double.NaN;
        setSegmentSpinnerValues(0.0, 0.0);
        updateSegmentRightPanelState();
    }

    private void syncDrawingFromSpinners(boolean fromEndSpinner) {
        if (updatingSegmentSpinners || currentRoom == null || dialogMode != DIALOG_SEGMENTS) {
            return;
        }
        List<float[][]> polys = sidePolylines.get(currentRoom);
        if (polys == null || polys.isEmpty()) {
            return;
        }
        int side = Math.max(0, Math.min(polys.size() - 1, activeSegmentSide));
        double len = SideModel.length(polys.get(side));
        double a = ((Number) startSpin.getValue()).doubleValue();
        double b = ((Number) endSpin.getValue()).doubleValue();
        if (a <= 1e-4 && b <= 1e-4) {
            pickSide = -1;
            pickPos1 = Double.NaN;
            pickPos2 = Double.NaN;
        } else if (!fromEndSpinner && b <= 1e-4 && Double.isNaN(pickPos2) && selectedSegmentIndex < 0) {
            pickSide = side;
            pickPos1 = nativeToShown(a, len);
            pickPos2 = Double.NaN;
        } else {
            pickSide = side;
            pickPos1 = nativeToShown(a, len);
            pickPos2 = nativeToShown(b, len);
        }
        preview.repaint();
    }

    private static void enableLiveSpinnerCommits(JSpinner spinner, Runnable onEnter) {
        if (spinner.getEditor() instanceof JSpinner.DefaultEditor) {
            JFormattedTextField tf = ((JSpinner.DefaultEditor) spinner.getEditor()).getTextField();
            if (tf.getFormatter() instanceof DefaultFormatter) {
                ((DefaultFormatter) tf.getFormatter()).setCommitsOnValidEdit(true);
            }
            if (onEnter != null) {
                tf.addActionListener(e -> onEnter.run());
            }
        }
    }

    private void selectSegmentSide(int side) {
        if (currentRoom == null) {
            return;
        }
        List<float[][]> polys = sidePolylines.get(currentRoom);
        if (polys == null || side < 0 || side >= polys.size()) {
            return;
        }
        activeSegmentSide = side;
        selectedSegmentSide = -1;
        selectedSegmentIndex = -1;
        resetDragState();
        clearDraftStretch();
        rebuildSegmentsTree();
        preview.repaint();
    }

    private void selectExistingStretch(int side, int shownIdx) {
        if (currentRoom == null) {
            return;
        }
        List<float[][]> polys = sidePolylines.get(currentRoom);
        SurroundConfig.SideEnds ends = working.get(currentRoom);
        if (polys == null || ends == null || side < 0 || side >= polys.size() || side >= ends.excludedSegments.size()) {
            return;
        }
        double len = SideModel.length(polys.get(side));
        List<double[]> shownList = shownSegments(ends.excludedSegments.get(side), len);
        if (shownIdx < 0 || shownIdx >= shownList.size()) {
            return;
        }
        resetDragState();
        activeSegmentSide = side;
        selectedSegmentSide = side;
        selectedSegmentIndex = shownIdx;
        double[] seg = shownList.get(shownIdx);
        setSegmentSpinnerValues(seg[0], seg[1]);
        pickSide = side;
        pickPos1 = nativeToShown(seg[0], len);
        pickPos2 = nativeToShown(seg[1], len);
        rebuildSegmentsTree();
        preview.repaint();
    }

    private void deleteStretch(int side, int shownIdx) {
        if (currentRoom == null) {
            return;
        }
        SurroundConfig.SideEnds ends = working.get(currentRoom);
        if (ends == null || side < 0 || side >= ends.excludedSegments.size()) {
            return;
        }
        List<double[]> segs = ends.excludedSegments.get(side);
        if (shownIdx < 0 || shownIdx >= segs.size()) {
            return;
        }
        segs.remove(mirrorIndex(shownIdx, segs.size()));
        activeSegmentSide = side;
        selectedSegmentSide = -1;
        selectedSegmentIndex = -1;
        resetDragState();
        clearDraftStretch();
        rebuildSegmentsTree();
        triggerAsyncPreview();
        preview.repaint();
    }

    /** Adds both ends without destroying existing middle stretches on that side. */
    private void addEndsToSide(int side) {
        editSegmentSide(side, (segs, totalLen) -> {
            if (totalLen > 0) {
                double d = Math.min(defaultHalfObjectLength, totalLen / 2.0);
                segs.add(new double[]{0.0, d});
                if (totalLen - d > d + 1e-3) {
                    segs.add(new double[]{totalLen - d, totalLen});
                } else if (totalLen > d) {
                    segs.add(new double[]{0.0, totalLen});
                }
                SurroundTarget.mergeIntervals(segs);
            }
        });
    }

    private void clearAllSidesStretches() {
        if (currentRoom == null) {
            return;
        }
        SurroundConfig.SideEnds ends = working.get(currentRoom);
        if (ends == null) {
            return;
        }
        for (List<double[]> segs : ends.excludedSegments) {
            segs.clear();
        }
        selectedSegmentSide = -1;
        selectedSegmentIndex = -1;
        resetDragState();
        clearDraftStretch();
        rebuildSegmentsTree();
        triggerAsyncPreview();
        preview.repaint();
    }

    private boolean hasAnyStretchInCurrentRoom() {
        SurroundConfig.SideEnds ends = currentRoom != null ? working.get(currentRoom) : null;
        if (ends != null) {
            for (List<double[]> segs : ends.excludedSegments) {
                if (!segs.isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }

    private void updateSegmentRightPanelState() {
        if (dialogMode != DIALOG_SEGMENTS) {
            return;
        }
        if (activeSideInfoLabel != null && currentRoom != null && sidePolylines.containsKey(currentRoom)) {
            List<float[][]> polys = sidePolylines.get(currentRoom);
            if (!polys.isEmpty()) {
                int s = Math.max(0, Math.min(polys.size() - 1, activeSegmentSide));
                activeSideInfoLabel.setText(text("sidesSide", "Lado") + " " + (s + 1)
                        + "  \u00b7  " + formatCm(SideModel.length(polys.get(s))) + " cm");
            }
        }
        if (addOrUpdateBtn != null) {
            boolean editingExisting = selectedSegmentSide >= 0 && selectedSegmentIndex >= 0;
            addOrUpdateBtn.setText(editingExisting
                    ? text("segmentUpdate", "Actualizar") : text("segmentAdd", "A\u00f1adir"));
        }
        if (clearAllBtn != null) {
            clearAllBtn.setEnabled(hasAnyStretchInCurrentRoom());
        }
    }

    private static JButton createInlineMiniButton(String caption, String tooltip, final Color hoverFg,
            final Color hoverBg, final boolean bordered) {
        final JButton btn = new JButton(caption);
        btn.setFont(btn.getFont().deriveFont(10.5f));
        btn.setFocusPainted(false);
        btn.setContentAreaFilled(false);
        btn.setOpaque(false);
        final Color normalFg = new Color(85, 92, 104);
        final EmptyBorder innerPad = bordered ? new EmptyBorder(1, 5, 1, 5) : new EmptyBorder(0, 4, 0, 4);
        final Border normalBorder = new CompoundBorder(
                BorderFactory.createLineBorder(bordered ? new Color(204, 210, 220) : new Color(0, 0, 0, 0), 1), innerPad);
        final Border hoverBorder = new CompoundBorder(BorderFactory.createLineBorder(hoverFg, 1), innerPad);
        btn.setForeground(normalFg);
        btn.setBorder(normalBorder);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        if (tooltip != null && !tooltip.isEmpty()) {
            btn.setToolTipText(tooltip);
        }
        btn.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                btn.setOpaque(true);
                btn.setForeground(hoverFg);
                btn.setBackground(hoverBg);
                btn.setBorder(hoverBorder);
            }

            @Override
            public void mouseExited(MouseEvent e) {
                btn.setOpaque(false);
                btn.setForeground(normalFg);
                btn.setBorder(normalBorder);
            }
        });
        return btn;
    }

    private static void attachClick(Runnable action, Component... comps) {
        MouseAdapter listener = new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                action.run();
            }
        };
        for (Component c : comps) {
            c.addMouseListener(listener);
        }
    }

    private void rebuildSegmentsTree() {
        if (segmentsTreePanel == null || dialogMode != DIALOG_SEGMENTS) {
            return;
        }
        segmentsTreePanel.removeAll();
        if (currentRoom == null || !sidePolylines.containsKey(currentRoom)) {
            segmentsTreePanel.revalidate();
            segmentsTreePanel.repaint();
            return;
        }
        List<float[][]> polys = sidePolylines.get(currentRoom);
        SurroundConfig.SideEnds ends = working.get(currentRoom);
        int n = polys.size();
        if (activeSegmentSide >= n) {
            activeSegmentSide = Math.max(0, n - 1);
        }
        if (selectedSegmentSide >= n) {
            selectedSegmentSide = -1;
            selectedSegmentIndex = -1;
        }
        String sideWord = text("sidesSide", "Lado");
        String endsShortLabel = MessageFormat.format(text("segmentSideEndsShort", "Extremos ({0} cm)"), formatCm(defaultHalfObjectLength));
        String endsTip = MessageFormat.format(text("segmentResetEndsCm", "Excluir extremos ({0} cm)"), formatCm(defaultHalfObjectLength));
        String clearSideLabel = text("segmentClearSide", "Vaciar lado");
        String removeTip = text("segmentRemove", "Quitar");
        Color hoverDangerFg = new Color(190, 42, 42), hoverDangerBg = new Color(255, 232, 232);
        Color hoverAccentBg = new Color(224, 236, 255);

        for (int i = 0; i < n; i++) {
            final int sideIdx = i;
            float[][] poly = polys.get(i);
            double len = SideModel.length(poly);
            String lenText = formatCm(len) + " cm" + (poly.length > 2 ? " (" + text("sidesCurve", "curva") + ")" : "");
            List<double[]> shownSegs = (ends != null && i < ends.excludedSegments.size())
                    ? shownSegments(ends.excludedSegments.get(i), len) : Collections.<double[]>emptyList();
            boolean isActiveSide = (i == activeSegmentSide);

            JPanel sideRow = new JPanel(new BorderLayout(4, 0));
            sideRow.setOpaque(true);
            sideRow.setBackground(isActiveSide ? new Color(230, 240, 252) : (i % 2 == 0 ? Color.WHITE : new Color(248, 250, 252)));
            sideRow.setBorder(new CompoundBorder(
                    BorderFactory.createMatteBorder(0, isActiveSide ? 3 : 0, 1, 0, isActiveSide ? ACCENT : new Color(232, 236, 242)),
                    new EmptyBorder(3, isActiveSide ? 5 : 8, 3, 5)));
            sideRow.setAlignmentX(Component.LEFT_ALIGNMENT);
            sideRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
            sideRow.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

            JPanel sideLeft = new JPanel(new FlowLayout(FlowLayout.LEADING, 4, 1));
            sideLeft.setOpaque(false);
            JLabel sideLbl = new JLabel(sideWord + " " + (i + 1) + " (" + lenText + ")");
            sideLbl.setFont(sideLbl.getFont().deriveFont(isActiveSide ? Font.BOLD : Font.PLAIN, 11.5f));
            sideLbl.setForeground(ends != null && i < ends.skipped.length && ends.skipped[i]
                    ? UiKit.HINT_TEXT : (isActiveSide ? ACCENT.darker() : Color.DARK_GRAY));
            sideLeft.add(sideLbl);
            if (!shownSegs.isEmpty()) {
                JLabel badge = new JLabel("[" + shownSegs.size() + "]");
                badge.setFont(badge.getFont().deriveFont(Font.BOLD, 10.5f));
                badge.setForeground(OutlinePreview.EXCLUDED);
                sideLeft.add(badge);
            }
            sideRow.add(sideLeft, BorderLayout.CENTER);

            JPanel sideActions = new JPanel(new FlowLayout(FlowLayout.TRAILING, 3, 0));
            sideActions.setOpaque(false);
            JButton sideEndsBtn = createInlineMiniButton(endsShortLabel, endsTip, ACCENT.darker(), hoverAccentBg, true);
            sideEndsBtn.addActionListener(e -> addEndsToSide(sideIdx));
            sideActions.add(sideEndsBtn);
            if (!shownSegs.isEmpty()) {
                JButton sideClearBtn = createInlineMiniButton(clearSideLabel, clearSideLabel, hoverDangerFg, hoverDangerBg, true);
                sideClearBtn.addActionListener(e -> editSegmentSide(sideIdx, (segs, length) -> segs.clear()));
                sideActions.add(sideClearBtn);
            }
            sideRow.add(sideActions, BorderLayout.EAST);
            attachClick(() -> selectSegmentSide(sideIdx), sideRow, sideLeft, sideLbl);
            segmentsTreePanel.add(sideRow);

            for (int sIdx = 0; sIdx < shownSegs.size(); sIdx++) {
                final int segIndex = sIdx;
                double[] seg = shownSegs.get(sIdx);
                boolean isSelectedSeg = (i == selectedSegmentSide && sIdx == selectedSegmentIndex);

                JPanel segRow = new JPanel(new BorderLayout(4, 0));
                segRow.setOpaque(true);
                segRow.setBackground(isSelectedSeg ? new Color(255, 243, 196) : new Color(252, 244, 244));
                segRow.setBorder(new CompoundBorder(new EmptyBorder(2, 14, 2, 5), new CompoundBorder(
                        BorderFactory.createLineBorder(isSelectedSeg ? new Color(212, 160, 0) : new Color(230, 196, 196), 1),
                        new EmptyBorder(1, 6, 1, 3))));
                segRow.setAlignmentX(Component.LEFT_ALIGNMENT);
                segRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
                segRow.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

                JLabel segLbl = colorSwatchLabel(isSelectedSeg ? SELECTED_SEG_COLOR : OutlinePreview.EXCLUDED,
                        String.format(Locale.US, "%s \u2013 %s cm", formatCm(seg[0]), formatCm(seg[1])));
                segLbl.setFont(segLbl.getFont().deriveFont(isSelectedSeg ? Font.BOLD : Font.PLAIN, 11f));
                segLbl.setForeground(isSelectedSeg ? new Color(110, 72, 0) : new Color(65, 35, 35));

                JPanel segRight = new JPanel(new FlowLayout(FlowLayout.TRAILING, 5, 0));
                segRight.setOpaque(false);
                JLabel lenLbl = new JLabel("(" + formatCm(seg[1] - seg[0]) + " cm)");
                lenLbl.setFont(lenLbl.getFont().deriveFont(10.5f));
                lenLbl.setForeground(UiKit.HINT_TEXT);
                JButton delBtn = createInlineMiniButton("\u00d7", removeTip, hoverDangerFg, hoverDangerBg, false);
                delBtn.setFont(delBtn.getFont().deriveFont(Font.BOLD, 12f));
                delBtn.addActionListener(e -> deleteStretch(sideIdx, segIndex));
                segRight.add(lenLbl);
                segRight.add(delBtn);

                segRow.add(segLbl, BorderLayout.CENTER);
                segRow.add(segRight, BorderLayout.EAST);
                attachClick(() -> selectExistingStretch(sideIdx, segIndex), segRow, segLbl, lenLbl);
                segmentsTreePanel.add(segRow);
            }
        }
        segmentsTreePanel.add(Box.createVerticalGlue());
        updateSegmentRightPanelState();
        segmentsTreePanel.revalidate();
        segmentsTreePanel.repaint();
    }

    /** Adds or replaces an excluded interval on {@code side} (enabling shortening when {@code replaceStoredIdx >= 0}). */
    private void commitSegmentRange(int side, double native0, double native1, int replaceStoredIdx, boolean selectResult) {
        if (currentRoom == null) {
            return;
        }
        List<float[][]> polys = sidePolylines.get(currentRoom);
        if (polys == null || side < 0 || side >= polys.size()) {
            return;
        }
        double maxLen = SideModel.length(polys.get(side));
        double s0 = Math.max(0.0, Math.min(maxLen, Math.min(native0, native1)));
        double s1 = Math.max(0.0, Math.min(maxLen, Math.max(native0, native1)));
        if (s1 - s0 < 0.1) {
            return;
        }
        List<double[]> segs = working.get(currentRoom).excludedSegments.get(side);
        if (replaceStoredIdx >= 0 && replaceStoredIdx < segs.size()) {
            segs.remove(replaceStoredIdx);
        }
        segs.add(new double[]{s0, s1});
        SurroundTarget.mergeIntervals(segs);
        activeSegmentSide = side;
        if (selectResult) {
            double mid = 0.5 * (s0 + s1);
            for (int k = 0; k < segs.size(); k++) {
                if (mid >= segs.get(k)[0] - 0.1 && mid <= segs.get(k)[1] + 0.1) {
                    selectExistingStretch(side, mirrorIndex(k, segs.size()));
                    triggerAsyncPreview();
                    return;
                }
            }
        }
        selectedSegmentSide = -1;
        selectedSegmentIndex = -1;
        clearDraftStretch();
        rebuildSegmentsTree();
        triggerAsyncPreview();
        preview.repaint();
    }

    private void applySpinnerStretch() {
        if (currentRoom == null) {
            return;
        }
        List<float[][]> polys = sidePolylines.get(currentRoom);
        if (polys == null || polys.isEmpty()) {
            return;
        }
        int side = Math.max(0, Math.min(polys.size() - 1, activeSegmentSide));
        double maxLen = SideModel.length(polys.get(side));
        double a = ((Number) startSpin.getValue()).doubleValue(), b = ((Number) endSpin.getValue()).doubleValue();
        if (Math.abs(b - a) < 0.1) {
            return;
        }
        double[] stored = shownToStored(new double[]{Math.min(a, b), Math.max(a, b)}, maxLen);
        int replaceIdx = -1;
        boolean keepSelected = false;
        if (selectedSegmentSide == side && selectedSegmentIndex >= 0) {
            SurroundConfig.SideEnds ends = working.get(currentRoom);
            if (ends != null && side < ends.excludedSegments.size() && selectedSegmentIndex < ends.excludedSegments.get(side).size()) {
                replaceIdx = mirrorIndex(selectedSegmentIndex, ends.excludedSegments.get(side).size());
                keepSelected = true;
            }
        }
        commitSegmentRange(side, stored[0], stored[1], replaceIdx, keepSelected);
    }

    private JPanel createSegmentsEditorPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 4));
        panel.setOpaque(false);

        activeSideInfoLabel = new JLabel();
        activeSideInfoLabel.setFont(activeSideInfoLabel.getFont().deriveFont(Font.BOLD, 11.5f));
        activeSideInfoLabel.setForeground(ACCENT.darker());

        JLabel localLegend = UiKit.hint(text("segmentsLocalPosLegend", "Posiciones locales sobre el lado (desde 0 cm)"));
        localLegend.setFont(localLegend.getFont().deriveFont(10.5f));

        startSpin = UiKit.spinner(new SpinnerNumberModel(0.0, 0.0, 100000.0, 1.0), 4);
        endSpin = UiKit.spinner(new SpinnerNumberModel(0.0, 0.0, 100000.0, 1.0), 4);
        enableLiveSpinnerCommits(startSpin, this::applySpinnerStretch);
        enableLiveSpinnerCommits(endSpin, this::applySpinnerStretch);
        startSpin.addChangeListener(e -> syncDrawingFromSpinners(false));
        endSpin.addChangeListener(e -> syncDrawingFromSpinners(true));

        addOrUpdateBtn = new JButton(text("segmentAdd", "A\u00f1adir"));
        addOrUpdateBtn.addActionListener(e -> applySpinnerStretch());
        clearAllBtn = new JButton(text("segmentClearAll", "Vaciar todo"));
        clearAllBtn.addActionListener(e -> clearAllSidesStretches());

        JPanel controls = new JPanel(new GridBagLayout());
        controls.setOpaque(false);
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0; c.anchor = GridBagConstraints.LINE_START;
        c.gridy = 0; c.insets = new Insets(0, 0, 2, 0);
        controls.add(activeSideInfoLabel, c);
        c.gridy = 1; c.insets = new Insets(0, 0, 8, 0);
        controls.add(localLegend, c);
        c.gridy = 2; c.insets = new Insets(2, 0, 10, 0);
        controls.add(UiKit.row(3, new JLabel(text("segmentFrom", "Desde:")), startSpin,
                new JLabel(text("segmentTo", "cm  Hasta:")), endSpin, new JLabel("cm")), c);
        c.gridy = 3; c.insets = new Insets(2, 0, 2, 0);
        controls.add(UiKit.row(8, addOrUpdateBtn, clearAllBtn), c);
        UiKit.addFiller(controls);

        panel.add(controls, BorderLayout.CENTER);
        updateSegmentRightPanelState();
        return panel;
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
        validateSidesSelection();
        triggerAsyncPreview();
    }

    private void setRoomsExcluded(int[] rows, boolean exclude) {
        if (rooms.isEmpty()) {
            return;
        }
        for (int row : rows) {
            if (row >= 0 && row < rooms.size()) {
                Room r = rooms.get(row);
                if (exclude) {
                    workingExcludedRooms.add(r);
                } else {
                    workingExcludedRooms.remove(r);
                }
            }
        }
        tableModel.fireTableRowsUpdated(0, rooms.size() - 1);
        validateSidesSelection();
        triggerAsyncPreview();
    }

    private void validateSidesSelection() {
        if (dialogMode == DIALOG_ROOMS) {
            boolean anyIncluded = false;
            for (Room r : rooms) {
                if (!workingExcludedRooms.contains(r)) {
                    anyIncluded = true;
                    break;
                }
            }
            warningLabel.setText(anyIncluded ? "" : text("roomsNoneSelectedWarning", "Al menos una habitaci\u00f3n debe ser incluida."));
            btnConfirm.setEnabled(anyIncluded);
            return;
        }
        if (currentRoom == null || dialogMode != DIALOG_SIDES) {
            warningLabel.setText("");
            btnConfirm.setEnabled(true);
            return;
        }
        boolean anyIncluded = false;
        for (boolean s : working.get(currentRoom).skipped) {
            if (!s) {
                anyIncluded = true;
                break;
            }
        }
        warningLabel.setText(anyIncluded ? "" : text("sidesNoneSelectedWarning", "Al menos un lado debe ser incluido."));
        btnConfirm.setEnabled(anyIncluded);
    }

    private String text(String key, String fallback) {
        return UiKit.text(resource, key, fallback);
    }

    private boolean isReversed(Room room) {
        return SurroundGeometryEngine.isTraversalReversed(room, interior && !alwaysExterior.contains(room));
    }

    private double[] startPoint(Room room) {
        double[] sp = workingStartPoints.get(room);
        return sp != null ? sp : SurroundGeometryEngine.defaultType7Start(room, mergeCollinear);
    }

    private boolean isShownReversed(Room room) {
        return SurroundGeometryEngine.isShownReversed(room);
    }

    private List<List<double[]>> obstacleHints(Room face) {
        if (face == null) {
            return Collections.emptyList();
        }
        return obstacleHintsCache.computeIfAbsent(face, f -> SurroundGeometryEngine.obstacleStretches(
                home, activeConfig, f, sidePolylines.get(f), isReversed(f), obstacles, selectedFurniture, objectDepth));
    }

    private void toggleExcluded(int side, double[] interval) {
        List<double[]> excluded = working.get(currentRoom).excludedSegments.get(side);
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
        activeSegmentSide = side;
        selectedSegmentSide = -1;
        selectedSegmentIndex = -1;
        clearDraftStretch();
        rebuildSegmentsTree();
        triggerAsyncPreview();
    }

    private List<double[]> shownSegments(List<double[]> stored, double length) {
        List<double[]> shown = new ArrayList<>(stored.size());
        boolean reversed = isShownReversed(currentRoom);
        for (int i = 0; i < stored.size(); i++) {
            double[] s = stored.get(reversed ? stored.size() - 1 - i : i);
            shown.add(reversed ? new double[]{length - s[1], length - s[0]} : new double[]{s[0], s[1]});
        }
        return shown;
    }

    private double[] shownToStored(double[] shown, double length) {
        return isShownReversed(currentRoom) ? new double[]{length - shown[1], length - shown[0]} : shown.clone();
    }

    private interface SideSegmentsEdit {
        void apply(List<double[]> segments, double sideLength);
    }

    private void editSegmentSide(int side, SideSegmentsEdit edit) {
        if (currentRoom == null) {
            return;
        }
        List<float[][]> polys = sidePolylines.get(currentRoom);
        if (polys == null || side < 0 || side >= polys.size()) {
            return;
        }
        edit.apply(working.get(currentRoom).excludedSegments.get(side), SideModel.length(polys.get(side)));
        activeSegmentSide = side;
        selectedSegmentSide = -1;
        selectedSegmentIndex = -1;
        resetDragState();
        clearDraftStretch();
        rebuildSegmentsTree();
        triggerAsyncPreview();
        preview.repaint();
    }

    private String roomName(Room room) {
        int idx = rooms.indexOf(room);
        if (idx >= 0 && idx < roomNames.size()) {
            return roomNames.get(idx);
        }
        return room.getName() != null ? room.getName() : text("sidesRoomDefault", "Superficie");
    }

    private class SidesTableModel extends AbstractTableModel {

        @Override
        public int getRowCount() {
            if (dialogMode == DIALOG_ROOMS) {
                return rooms.size();
            }
            return currentRoom != null && sidePolylines.containsKey(currentRoom)
                    ? sidePolylines.get(currentRoom).size() : 0;
        }

        @Override
        public int getColumnCount() {
            return 3;
        }

        @Override
        public String getColumnName(int c) {
            String[] cols = dialogMode == DIALOG_ROOMS
                    ? new String[]{text("sidesSkipSelected", "Excluir"), text("roomsSurfaceCol", "Superficie"), text("roomsSidesCountCol", "Lados")}
                    : dialogMode == DIALOG_START_POINT
                    ? new String[]{text("sidesSide", "Lado"), text("sidesLength", "Longitud (cm)"), text("t7StartCol", "Inicio (cm)")}
                    : new String[]{text("sidesSkipSelected", "Excluir"), text("sidesSide", "Lado"), text("sidesLength", "Longitud (cm)")};
            return cols[c];
        }

        @Override
        public Class<?> getColumnClass(int c) {
            return ((dialogMode == DIALOG_SIDES || dialogMode == DIALOG_ROOMS) && c == 0) ? Boolean.class : String.class;
        }

        private String formatSideLength(int row) {
            float[][] poly = sidePolylines.get(currentRoom).get(row);
            String len = String.format("%.1f", SideModel.length(poly));
            return poly.length > 2 ? len + " (" + text("sidesCurve", "curva") + ")" : len;
        }

        @Override
        public Object getValueAt(int row, int c) {
            if (dialogMode == DIALOG_ROOMS) {
                Room r = rooms.get(row);
                return c == 0 ? workingExcludedRooms.contains(r)
                        : c == 1 ? roomName(r)
                        : String.valueOf(sidePolylines.get(r) != null ? sidePolylines.get(r).size() : 0);
            }
            if (dialogMode == DIALOG_START_POINT) {
                if (c == 0) {
                    return String.valueOf(row + 1);
                }
                if (c == 1) {
                    return formatSideLength(row);
                }
                double[] sp = startPoint(currentRoom);
                if (row != (int) Math.round(sp[0])) {
                    return "\u2014";
                }
                return String.format("%.1f cm", nativeToShown(sp[1], SideModel.length(sidePolylines.get(currentRoom).get(row))));
            }
            return c == 0 ? working.get(currentRoom).skipped[row]
                    : c == 1 ? String.valueOf(row + 1) : formatSideLength(row);
        }

        @Override
        public boolean isCellEditable(int row, int c) {
            if (dialogMode == DIALOG_ROOMS) {
                return c == 0;
            }
            if (dialogMode != DIALOG_SIDES) {
                return false;
            }
            SurroundConfig.SideEnds ends = working.get(currentRoom);
            return c == 0 && (row >= ends.forbidden.length || !ends.forbidden[row]);
        }

        @Override
        public void setValueAt(Object value, int row, int c) {
            if (c != 0) {
                return;
            }
            if (dialogMode == DIALOG_ROOMS) {
                setRoomsExcluded(new int[]{row}, Boolean.TRUE.equals(value));
            } else if (dialogMode == DIALOG_SIDES) {
                setSkipped(new int[]{row}, Boolean.TRUE.equals(value));
            }
        }
    }

    /** Interactive editor canvas extending {@link OutlinePreview} with direct stretch drag/click, handles, and room picking. */
    private class RoomPreview extends OutlinePreview {

        RoomPreview() {
            super();
            MouseAdapter hoverTracker = new MouseAdapter() {
                @Override
                public void mouseMoved(MouseEvent e) {
                    if (dialogMode == DIALOG_ROOMS) {
                        setCursor(roomAt(e.getPoint()) >= 0
                                ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());
                        return;
                    }
                    if (dialogMode != DIALOG_SEGMENTS || currentRoom == null) {
                        if (hoverSide >= 0 || hoveredHandle != 0) {
                            clearHover();
                        }
                        return;
                    }
                    int h = handleAt(e.getPoint());
                    if (h != 0) {
                        hoveredHandle = h;
                        hoverSide = -1;
                        hoverNativePos = Double.NaN;
                        hoverScreenPt = null;
                        setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                        repaint();
                        return;
                    }
                    boolean handleChanged = hoveredHandle != 0;
                    hoveredHandle = 0;
                    int side = sideAt(e.getPoint());
                    if (side >= 0) {
                        hoverSide = side;
                        hoverNativePos = positionAlong(side, e.getPoint());
                        hoverScreenPt = e.getPoint();
                        setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
                        repaint();
                    } else if (hoverSide >= 0 || handleChanged) {
                        clearHover();
                    }
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    if (draggingHandle == 0 && draggingNewSide < 0 && (hoverSide >= 0 || hoveredHandle != 0)) {
                        clearHover();
                    }
                }
            };
            addMouseMotionListener(hoverTracker);
            addMouseListener(hoverTracker);
        }

        private void clearHover() {
            hoverSide = -1;
            hoverNativePos = Double.NaN;
            hoverScreenPt = null;
            hoveredHandle = 0;
            if (draggingHandle == 0 && draggingNewSide < 0) {
                setCursor(Cursor.getDefaultCursor());
            }
            repaint();
        }

        @Override
        double viewPadding() {
            return dialogMode == DIALOG_SEGMENTS ? 42.0 : super.viewPadding();
        }

        private int handleAt(Point p) {
            if (dialogMode != DIALOG_SEGMENTS || currentRoom == null || pickSide < 0 || p == null) {
                return 0;
            }
            List<float[][]> sides = sidePolylines.get(currentRoom);
            if (sides == null || pickSide >= sides.size()) {
                return 0;
            }
            float[][] poly = sides.get(pickSide);
            double d1 = Double.isNaN(pickPos1) ? Double.MAX_VALUE : p.distance(screenPointAt(poly, pickPos1));
            double d2 = Double.isNaN(pickPos2) ? Double.MAX_VALUE : p.distance(screenPointAt(poly, pickPos2));
            return (d1 <= 11.0 && d1 <= d2) ? 1 : (d2 <= 11.0 ? 2 : 0);
        }

        @Override
        boolean onMousePressed(MouseEvent e) {
            if (dialogMode != DIALOG_SEGMENTS || currentRoom == null
                    || !SwingUtilities.isLeftMouseButton(e) || e.isControlDown() || e.isShiftDown()) {
                return false;
            }
            int h = handleAt(e.getPoint());
            if (h != 0) {
                draggingHandle = h;
                hoveredHandle = h;
                dragPressScreen = e.getPoint();
                dragMoved = false;
                draggingOrigStoredIdx = -1;
                draggingOrigBackup = null;
                if (selectedSegmentSide == pickSide && selectedSegmentIndex >= 0) {
                    SurroundConfig.SideEnds ends = working.get(currentRoom);
                    if (ends != null && pickSide < ends.excludedSegments.size()) {
                        List<double[]> segs = ends.excludedSegments.get(pickSide);
                        if (selectedSegmentIndex < segs.size()) {
                            draggingOrigStoredIdx = mirrorIndex(selectedSegmentIndex, segs.size());
                            draggingOrigBackup = segs.get(draggingOrigStoredIdx).clone();
                        }
                    }
                }
                hoverSide = -1;
                hoverNativePos = Double.NaN;
                hoverScreenPt = null;
                setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                repaint();
                return true;
            }
            int side = sideAt(e.getPoint());
            if (side >= 0) {
                draggingNewSide = side;
                dragOriginNative = positionAlong(side, e.getPoint());
                dragPressScreen = e.getPoint();
                dragMoved = false;
                return true;
            }
            return false;
        }

        private boolean passedDragThreshold(MouseEvent e) {
            return dragMoved || dragPressScreen == null
                    || Math.hypot(e.getX() - dragPressScreen.x, e.getY() - dragPressScreen.y) > 4.0;
        }

        private void syncSpinnersFromPick(double len) {
            double s1 = nativeToShown(pickPos1, len), s2 = nativeToShown(pickPos2, len);
            setSegmentSpinnerValues(Math.min(s1, s2), Math.max(s1, s2));
        }

        @Override
        void onMouseDragged(MouseEvent e) {
            if (dialogMode != DIALOG_SEGMENTS || currentRoom == null || !passedDragThreshold(e)) {
                return;
            }
            List<float[][]> sides = sidePolylines.get(currentRoom);
            if (sides == null) {
                return;
            }
            if (draggingHandle != 0 && pickSide >= 0 && pickSide < sides.size()) {
                dragMoved = true;
                double nativeAt = positionAlong(pickSide, e.getPoint());
                if (draggingHandle == 1) {
                    pickPos1 = nativeAt;
                } else {
                    pickPos2 = nativeAt;
                }
                double len = SideModel.length(sides.get(pickSide));
                if (!Double.isNaN(pickPos1) && !Double.isNaN(pickPos2)) {
                    syncSpinnersFromPick(len);
                    if (draggingOrigStoredIdx >= 0) {
                        List<double[]> segs = working.get(currentRoom).excludedSegments.get(pickSide);
                        if (draggingOrigStoredIdx < segs.size()) {
                            segs.get(draggingOrigStoredIdx)[0] = Math.min(pickPos1, pickPos2);
                            segs.get(draggingOrigStoredIdx)[1] = Math.max(pickPos1, pickPos2);
                        }
                    }
                } else if (!Double.isNaN(pickPos1)) {
                    setSegmentSpinnerValues(nativeToShown(pickPos1, len), 0.0);
                }
                repaint();
            } else if (draggingNewSide >= 0 && draggingNewSide < sides.size()) {
                if (!dragMoved) {
                    dragMoved = true;
                    activeSegmentSide = draggingNewSide;
                    selectedSegmentSide = -1;
                    selectedSegmentIndex = -1;
                    pickSide = draggingNewSide;
                    pickPos1 = dragOriginNative;
                    hoverSide = -1;
                    hoverNativePos = Double.NaN;
                    hoverScreenPt = null;
                    setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
                    updateSegmentRightPanelState();
                }
                pickPos2 = positionAlong(draggingNewSide, e.getPoint());
                syncSpinnersFromPick(SideModel.length(sides.get(draggingNewSide)));
                repaint();
            }
        }

        @Override
        void onMouseReleased(MouseEvent e) {
            boolean wasHandle = draggingHandle != 0;
            boolean wasNew = draggingNewSide >= 0;
            if (!wasHandle && !wasNew) {
                return;
            }
            boolean moved = dragMoved;
            int side = wasHandle ? pickSide : draggingNewSide;
            double p1 = pickPos1, p2 = pickPos2;
            int replaceIdx = wasHandle ? draggingOrigStoredIdx : -1;
            double[] backup = wasHandle ? draggingOrigBackup : null;
            resetDragState();
            setCursor(Cursor.getDefaultCursor());
            if (moved && side >= 0 && !Double.isNaN(p1) && !Double.isNaN(p2)) {
                if (Math.abs(p2 - p1) >= 0.5) {
                    commitSegmentRange(side, p1, p2, replaceIdx, true);
                } else if (replaceIdx >= 0 && backup != null && currentRoom != null) {
                    List<double[]> segs = working.get(currentRoom).excludedSegments.get(side);
                    if (replaceIdx < segs.size()) {
                        segs.set(replaceIdx, backup.clone());
                        selectExistingStretch(side, mirrorIndex(replaceIdx, segs.size()));
                    }
                } else if (wasNew) {
                    clearDraftStretch();
                    rebuildSegmentsTree();
                    repaint();
                } else {
                    repaint();
                }
            } else if (wasHandle) {
                repaint();
            }
        }

        private void toggleRowSelection(int row) {
            if (table.isRowSelected(row)) {
                table.removeRowSelectionInterval(row, row);
            } else {
                table.addRowSelectionInterval(row, row);
            }
        }

        @Override
        void clicked(MouseEvent e) {
            if (dialogMode == DIALOG_ROOMS) {
                int rIdx = roomAt(e.getPoint());
                if (rIdx < 0) {
                    super.clicked(e);
                    return;
                }
                if (e.isControlDown() || e.isShiftDown()) {
                    toggleRowSelection(rIdx);
                } else {
                    setRoomsExcluded(new int[]{rIdx}, !workingExcludedRooms.contains(rooms.get(rIdx)));
                    table.setRowSelectionInterval(rIdx, rIdx);
                }
                return;
            }
            if (dialogMode == DIALOG_SEGMENTS && handleAt(e.getPoint()) != 0) {
                return;
            }
            int side = sideAt(e.getPoint());
            if (side < 0) {
                super.clicked(e);
                return;
            }
            if (dialogMode == DIALOG_START_POINT) {
                double nativeAt = positionAlong(side, e.getPoint());
                setType7StartShown(side, nativeToShown(nativeAt, SideModel.length(sidePolylines.get(currentRoom).get(side))));
                return;
            }
            if (dialogMode == DIALOG_SEGMENTS && !e.isControlDown() && !e.isShiftDown()) {
                double nativeAt = positionAlong(side, e.getPoint());
                double len = SideModel.length(sidePolylines.get(currentRoom).get(side));
                if (pickSide == side && !Double.isNaN(pickPos1) && Double.isNaN(pickPos2)) {
                    if (Math.abs(nativeAt - pickPos1) >= 0.5) {
                        commitSegmentRange(side, pickPos1, nativeAt, -1, true);
                    } else {
                        clearDraftStretch();
                        rebuildSegmentsTree();
                        repaint();
                    }
                    return;
                }
                double tolerance = 8 / Math.max(1e-4, view.getScaleX());
                List<List<double[]>> allHints = obstacleHints(currentRoom);
                if (side < allHints.size()) {
                    for (double[] hint : allHints.get(side)) {
                        if (nativeAt >= hint[0] - tolerance && nativeAt <= hint[1] + tolerance) {
                            toggleExcluded(side, hint);
                            return;
                        }
                    }
                }
                SurroundConfig.SideEnds ends = working.get(currentRoom);
                if (ends != null && side < ends.excludedSegments.size()) {
                    List<double[]> segs = ends.excludedSegments.get(side);
                    for (int sIdx = 0; sIdx < segs.size(); sIdx++) {
                        double[] ex = segs.get(sIdx);
                        if (nativeAt >= ex[0] - tolerance && nativeAt <= ex[1] + tolerance) {
                            selectExistingStretch(side, mirrorIndex(sIdx, segs.size()));
                            return;
                        }
                    }
                }
                activeSegmentSide = side;
                selectedSegmentSide = -1;
                selectedSegmentIndex = -1;
                pickSide = side;
                pickPos1 = nativeAt;
                pickPos2 = Double.NaN;
                setSegmentSpinnerValues(nativeToShown(nativeAt, len), 0.0);
                rebuildSegmentsTree();
                repaint();
                return;
            }
            if (dialogMode == DIALOG_SIDES && !e.isControlDown() && !e.isShiftDown()) {
                setSkipped(new int[]{side}, !working.get(currentRoom).skipped[side]);
                table.setRowSelectionInterval(side, side);
            } else if (e.isControlDown() || e.isShiftDown()) {
                toggleRowSelection(side);
            } else {
                table.setRowSelectionInterval(side, side);
            }
        }

        @Override
        protected void paintComponent(Graphics g) {
            SurroundGeometryEngine engine = livePreviewEnabled ? asyncCompletedEngine : null;
            if (dialogMode == DIALOG_ROOMS) {
                faces = rooms;
                SurroundConfig roomCfg = new SurroundConfig();
                roomCfg.excludedRooms.addAll(workingExcludedRooms);
                config = roomCfg;
                placed = engine != null ? asyncPlaced : Collections.<HomePieceOfFurniture>emptyList();
            } else {
                faces = currentRoom != null ? Collections.singletonList(currentRoom) : Collections.<Room>emptyList();
                config = activeConfig;
                placed = engine != null && currentRoom != null ? asyncPlaced : Collections.<HomePieceOfFurniture>emptyList();
            }
            isCorner = engine != null ? engine::isCornerPiece : p -> false;
            baseWidth = SideEndsPanel.this.baseWidth;
            baseDepth = objectDepth;
            super.paintComponent(g);
        }

        private double minDistToPolylines(List<float[][]> sides, Point point) {
            double best = Double.MAX_VALUE;
            if (sides != null) {
                for (float[][] poly : sides) {
                    for (int k = 0; k + 1 < poly.length; k++) {
                        Point2D.Double a = toScreen(poly[k]), b = toScreen(poly[k + 1]);
                        best = Math.min(best, Line2D.ptSegDist(a.x, a.y, b.x, b.y, point.x, point.y));
                    }
                }
            }
            return best;
        }

        private int roomAt(Point point) {
            if (rooms.isEmpty() || point == null) {
                return -1;
            }
            int bestInside = -1;
            double bestArea = Double.MAX_VALUE;
            for (int i = 0; i < rooms.size(); i++) {
                Path2D.Double polyPath = path(rooms.get(i).getPoints(), true);
                if (polyPath.contains(point.x, point.y)) {
                    Rectangle2D b = polyPath.getBounds2D();
                    double area = b.getWidth() * b.getHeight();
                    if (area < bestArea) {
                        bestArea = area;
                        bestInside = i;
                    }
                }
            }
            if (bestInside >= 0) {
                return bestInside;
            }
            int bestEdge = -1;
            double bestDist = 14.0;
            for (int i = 0; i < rooms.size(); i++) {
                double d = minDistToPolylines(sidePolylines.get(rooms.get(i)), point);
                if (d < bestDist) {
                    bestDist = d;
                    bestEdge = i;
                }
            }
            return bestEdge;
        }

        private void drawSidePath(Graphics2D g2, Path2D.Double p, boolean skipped, boolean sel, boolean anySelected, boolean roomMode) {
            if (sel) {
                g2.setColor(new Color(ACCENT.getRed(), ACCENT.getGreen(), ACCENT.getBlue(), skipped ? 45 : 62));
                g2.setStroke(new BasicStroke(roomMode ? 8.5f : 9.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g2.draw(p);
            }
            if (skipped) {
                g2.setColor(sel ? ACCENT : new Color(172, 178, 188, anySelected ? (roomMode ? 150 : 110) : 215));
                g2.setStroke(new BasicStroke(sel ? (roomMode ? 2.8f : 3.2f) : 1.8f, BasicStroke.CAP_BUTT,
                        BasicStroke.JOIN_ROUND, 10f, new float[]{5f, 4f}, 0f));
            } else {
                g2.setColor(sel ? ACCENT : new Color(WALL.getRed(), WALL.getGreen(), WALL.getBlue(), anySelected ? (roomMode ? 145 : 110) : 235));
                g2.setStroke(new BasicStroke(sel ? (roomMode ? 3.8f : 4.6f) : (anySelected ? 2.2f : 2.8f),
                        BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            }
            g2.draw(p);
        }

        private void drawPill(Graphics2D g2, double x, double y, int w, int h, int padX, int padY,
                FontMetrics fm, String label, Color bg, Color border, Color fg, float strokeW) {
            RoundRectangle2D.Double box = new RoundRectangle2D.Double(x, y, w, h, 7, 7);
            g2.setColor(bg);
            g2.fill(box);
            g2.setColor(border);
            g2.setStroke(new BasicStroke(strokeW));
            g2.draw(box);
            g2.setColor(fg);
            g2.drawString(label, (float) (x + padX), (float) (y + padY + fm.getAscent() - 1));
        }

        private void paintRoomsMode(Graphics2D g2) {
            paintPieces(g2);
            boolean anySelected = table.getSelectedRowCount() > 0;
            for (int pass = 0; pass < 2; pass++) {
                for (int i = 0; i < rooms.size(); i++) {
                    boolean sel = table.isRowSelected(i);
                    if (sel != (pass == 1)) {
                        continue;
                    }
                    Room r = rooms.get(i);
                    boolean excluded = workingExcludedRooms.contains(r);
                    List<float[][]> sides = sidePolylines.get(r);
                    SurroundConfig.SideEnds ends = working.get(r);
                    if (sides == null) {
                        continue;
                    }
                    for (int s = 0; s < sides.size(); s++) {
                        boolean skipped = excluded || (ends != null && s < ends.skipped.length && ends.skipped[s]);
                        drawSidePath(g2, path(sides.get(s), false), skipped, sel, anySelected, true);
                    }
                }
            }
            Font prevFont = g2.getFont();
            g2.setFont(prevFont.deriveFont(Font.BOLD, 10.5f));
            FontMetrics fm = g2.getFontMetrics();
            for (int i = 0; i < rooms.size(); i++) {
                Room r = rooms.get(i);
                boolean excluded = workingExcludedRooms.contains(r), sel = table.isRowSelected(i);
                Rectangle2D bounds = path(r.getPoints(), true).getBounds2D();
                String label = roomName(r);
                if (label.length() > 22) {
                    label = label.substring(0, 21) + "\u2026";
                }
                int w = fm.stringWidth(label) + 12, h = fm.getHeight() + 4;
                double cx = Math.max(4, Math.min(getWidth() - w - 4, bounds.getCenterX() - w / 2.0));
                double cy = Math.max(4, Math.min(getHeight() - h - 4, bounds.getCenterY() - h / 2.0));
                drawPill(g2, cx, cy, w, h, 6, 3, fm, label,
                        excluded ? new Color(245, 246, 249, 232) : new Color(255, 255, 255, 236),
                        sel ? ACCENT : (excluded ? new Color(175, 180, 190) : UiKit.CARD_BORDER),
                        excluded ? UiKit.HINT_TEXT : (sel ? ACCENT.darker() : Color.DARK_GRAY), sel ? 1.4f : 1.0f);
            }
            g2.setFont(prevFont);
        }

        private Point2D.Double toScreen(float[] pt) {
            return (Point2D.Double) view.transform(new Point2D.Double(pt[0], pt[1]), new Point2D.Double());
        }

        private Point2D.Double screenPointAt(float[][] poly, double dist) {
            SidePath sp = new SidePath(poly, 0, false);
            double[] pt = sp.pointAt(Math.max(0.0, Math.min(sp.length, dist)));
            return toScreen(new float[]{(float) pt[0], (float) pt[1]});
        }

        /** Returns {sx, sy, tx, ty, nx, ny} in screen space with (nx, ny) strictly outward from {@code roomPoly}. */
        private double[] sideGeometry(float[][] poly, double nativeDist, Path2D.Double roomPoly, Point2D.Double roomCenter) {
            SidePath sp = new SidePath(poly, 0, false);
            double clamped = Math.max(0.0, Math.min(sp.length, nativeDist));
            double[] p = sp.pointAt(clamped);
            Point2D.Double s = toScreen(new float[]{(float) p[0], (float) p[1]});
            double eps = Math.min(2.0, Math.max(0.2, sp.length * 0.02));
            double[] pa = sp.pointAt(Math.max(0.0, clamped - eps)), pb = sp.pointAt(Math.min(sp.length, clamped + eps));
            Point2D.Double sa = toScreen(new float[]{(float) pa[0], (float) pa[1]});
            Point2D.Double sb = toScreen(new float[]{(float) pb[0], (float) pb[1]});
            double dx = sb.x - sa.x, dy = sb.y - sa.y, tLen = Math.hypot(dx, dy);
            double tx = tLen > 1e-6 ? dx / tLen : 1.0, ty = tLen > 1e-6 ? dy / tLen : 0.0;

            double n1x = ty, n1y = -tx, n2x = -ty, n2y = tx, nx = n1x, ny = n1y;
            boolean decided = false;
            if (roomPoly != null) {
                for (double probe : new double[]{4.0, 12.0}) {
                    boolean in1 = roomPoly.contains(s.x + n1x * probe, s.y + n1y * probe);
                    boolean in2 = roomPoly.contains(s.x + n2x * probe, s.y + n2y * probe);
                    if (in1 != in2) {
                        if (in1) {
                            nx = n2x;
                            ny = n2y;
                        }
                        decided = true;
                        break;
                    }
                }
            }
            if (!decided && roomCenter != null
                    && (s.x - roomCenter.x) * n2x + (s.y - roomCenter.y) * n2y > (s.x - roomCenter.x) * n1x + (s.y - roomCenter.y) * n1y) {
                nx = n2x;
                ny = n2y;
            }
            return new double[]{s.x, s.y, tx, ty, nx, ny};
        }

        private double[] middleDirection(float[][] poly) {
            SidePath sp = new SidePath(poly, 0, false);
            return sp.directionAt(sp.length / 2.0);
        }

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

        private int sideAt(Point point) {
            List<float[][]> sides = currentRoom != null ? sidePolylines.get(currentRoom) : null;
            if (sides == null) {
                return -1;
            }
            int best = -1;
            double bestDist = 14;
            for (int i = 0; i < sides.size(); i++) {
                double d = minDistToPolylines(Collections.singletonList(sides.get(i)), point);
                if (d < bestDist) {
                    bestDist = d;
                    best = i;
                }
            }
            return best;
        }

        private void interval(Graphics2D g2, float[][] poly, double d0, double d1, Color color, float width) {
            drawStretch(g2, poly, d0, d1, color, new BasicStroke(width, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND));
        }

        private Point2D.Double marker(Graphics2D g2, float[][] poly, double dist, boolean emphasized) {
            Point2D.Double s = screenPointAt(poly, dist);
            double r = emphasized ? 6.2 : 5.2;
            if (emphasized) {
                g2.setColor(new Color(PICK_MARKER_COLOR.getRed(), PICK_MARKER_COLOR.getGreen(), PICK_MARKER_COLOR.getBlue(), 75));
                g2.fill(new Ellipse2D.Double(s.x - r - 4.0, s.y - r - 4.0, 2 * (r + 4.0), 2 * (r + 4.0)));
            }
            g2.setColor(Color.WHITE);
            g2.fill(new Ellipse2D.Double(s.x - r - 1.6, s.y - r - 1.6, 2 * (r + 1.6), 2 * (r + 1.6)));
            g2.setColor(PICK_MARKER_COLOR);
            g2.fill(new Ellipse2D.Double(s.x - r, s.y - r, 2 * r, 2 * r));
            return s;
        }

        /**
         * Places a callout pill strictly OUTSIDE the room along outward normal {@code (nx, ny)}.
         * Endpoint/hover notes sit beside points (never directly above on horizontal walls), and
         * collision avoidance only steps further outward or sideways.
         */
        private void drawOutwardCallout(Graphics2D g2, double sx, double sy,
                double nx, double ny, double flankX, double flankY, boolean centerOnPoint,
                String label, Color bg, Color border, Color fg, List<Rectangle2D.Double> avoid) {
            Font prevFont = g2.getFont();
            g2.setFont(prevFont.deriveFont(Font.BOLD, 10.5f));
            FontMetrics fm = g2.getFontMetrics();
            int padX = 6, padY = 3, w = fm.stringWidth(label) + padX * 2, h = fm.getHeight() + padY * 2 - 2;

            List<double[]> candidates = new ArrayList<>(5);
            if (Math.abs(nx) >= Math.abs(ny)) {
                double baseX = nx >= 0 ? (sx + 10.0) : (sx - 10.0 - w);
                double baseY = sy - h / 2.0;
                double dyStep = (flankY >= 0 ? 1.0 : -1.0) * (h + 4.0);
                double dxOut = (nx >= 0 ? 1.0 : -1.0) * (w + 6.0);
                candidates.add(new double[]{baseX, baseY});
                candidates.add(new double[]{baseX, baseY + dyStep});
                candidates.add(new double[]{baseX, baseY - dyStep});
                candidates.add(new double[]{baseX + dxOut, baseY});
                candidates.add(new double[]{baseX + dxOut, baseY + dyStep});
            } else {
                double outY = ny < 0 ? (sy - h - 4.0) : (sy + 4.0);
                double outStepY = ny < 0 ? -(h + 4.0) : (h + 4.0);
                if (centerOnPoint) {
                    double baseX = sx - w / 2.0;
                    candidates.add(new double[]{baseX, outY});
                    candidates.add(new double[]{baseX, outY + outStepY});
                    candidates.add(new double[]{baseX - w * 0.65, outY + outStepY});
                    candidates.add(new double[]{baseX + w * 0.65, outY + outStepY});
                } else {
                    double signX = Math.abs(flankX) > 1e-3 ? Math.signum(flankX) : 1.0;
                    double xPref = signX < 0 ? (sx - 9.0 - w) : (sx + 9.0);
                    double xAlt = signX < 0 ? (sx + 9.0) : (sx - 9.0 - w);
                    double firstX = (xPref >= 4.0 && xPref + w <= getWidth() - 4.0) ? xPref : xAlt;
                    double secondX = (firstX == xPref) ? xAlt : xPref;
                    candidates.add(new double[]{firstX, outY});
                    candidates.add(new double[]{firstX, outY + outStepY});
                    candidates.add(new double[]{secondX, outY});
                    candidates.add(new double[]{secondX, outY + outStepY});
                }
            }

            double chosenX = Math.max(4.0, Math.min(getWidth() - w - 4.0, candidates.get(0)[0]));
            double chosenY = Math.max(4.0, Math.min(getHeight() - h - 4.0, candidates.get(0)[1]));
            for (double[] candPos : candidates) {
                double cx = Math.max(4.0, Math.min(getWidth() - w - 4.0, candPos[0]));
                double cy = Math.max(4.0, Math.min(getHeight() - h - 4.0, candPos[1]));
                Rectangle2D.Double cand = new Rectangle2D.Double(cx - 2, cy - 2, w + 4, h + 4);
                boolean hits = false;
                for (Rectangle2D.Double other : avoid) {
                    if (cand.intersects(other)) {
                        hits = true;
                        break;
                    }
                }
                if (!hits) {
                    chosenX = cx;
                    chosenY = cy;
                    break;
                }
            }
            drawPill(g2, chosenX, chosenY, w, h, padX, padY, fm, label, bg, border, fg, 1.1f);
            g2.setFont(prevFont);
            avoid.add(new Rectangle2D.Double(chosenX, chosenY, w, h));
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

        @Override
        void paintPlan(Graphics2D g2) {
            if (dialogMode == DIALOG_ROOMS) {
                paintRoomsMode(g2);
                return;
            }
            paintPieces(g2);
            List<float[][]> sides = sidePolylines.get(currentRoom);
            int n = sides != null ? sides.size() : 0;
            SurroundConfig.SideEnds ends = working.get(currentRoom);
            boolean reversed = isShownReversed(currentRoom);
            boolean segmentsMode = dialogMode == DIALOG_SEGMENTS;
            boolean anySelected = segmentsMode ? (activeSegmentSide >= 0 && activeSegmentSide < n) : (table.getSelectedRowCount() > 0);
            List<List<double[]>> allHints = segmentsMode ? obstacleHints(currentRoom) : Collections.emptyList();
            Color[] hintColors = {DOOR_HINT, WINDOW_HINT, COLLISION_HINT, WALL_OVERLAP_HINT, FLOOR_OVERLAP_HINT};

            for (int pass = 0; pass < 2; pass++) {
                for (int i = 0; i < n; i++) {
                    boolean sel = segmentsMode ? (i == activeSegmentSide) : table.isRowSelected(i);
                    if (sel != (pass == 1)) {
                        continue;
                    }
                    float[][] poly = sides.get(i);
                    drawSidePath(g2, path(poly, false), ends.skipped[i], sel, anySelected, false);
                    if (ends.skipped[i]) {
                        continue;
                    }
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
                            int kind = Math.max(0, Math.min(hintColors.length - 1, (int) Math.round(hint[2])));
                            interval(g2, poly, hint[0], hint[1], hintColors[kind], 10f);
                        }
                    }
                    if (i < ends.excludedSegments.size()) {
                        List<double[]> segs = ends.excludedSegments.get(i);
                        int highlightIdx = (i == selectedSegmentSide && selectedSegmentIndex >= 0 && selectedSegmentIndex < segs.size())
                                ? mirrorIndex(selectedSegmentIndex, segs.size()) : -1;
                        for (int sIdx = 0; sIdx < segs.size(); sIdx++) {
                            double[] ex = segs.get(sIdx);
                            if (sIdx == highlightIdx) {
                                interval(g2, poly, ex[0], ex[1], SELECTED_SEG_COLOR, 9.0f);
                            }
                            interval(g2, poly, ex[0], ex[1], EXCLUDED, sel ? 5.2f : 4f);
                        }
                    }
                }
            }

            Path2D.Double roomPoly = (currentRoom != null) ? path(currentRoom.getPoints(), true) : null;
            Point2D.Double roomCenter = roomPoly != null
                    ? new Point2D.Double(roomPoly.getBounds2D().getCenterX(), roomPoly.getBounds2D().getCenterY()) : null;
            List<Rectangle2D.Double> placedCallouts = new ArrayList<>(5);
            placedCallouts.add(new Rectangle2D.Double(4, 4, 28, 28));
            boolean activeDrag = (draggingHandle != 0) || (draggingNewSide >= 0 && dragMoved);

            if (segmentsMode && pickSide >= 0 && pickSide < n) {
                float[][] poly = sides.get(pickSide);
                double sideLen = SideModel.length(poly);
                boolean has1 = !Double.isNaN(pickPos1), has2 = !Double.isNaN(pickPos2);
                boolean hasSpan = has1 && has2 && Math.abs(pickPos2 - pickPos1) > 0.05;
                if (hasSpan) {
                    interval(g2, poly, Math.min(pickPos1, pickPos2), Math.max(pickPos1, pickPos2), PICK_MARKER_COLOR, 6.0f);
                } else if (has1 && !has2 && hoverSide == pickSide && !Double.isNaN(hoverNativePos)) {
                    interval(g2, poly, Math.min(pickPos1, hoverNativePos), Math.max(pickPos1, hoverNativePos),
                            new Color(PICK_MARKER_COLOR.getRed(), PICK_MARKER_COLOR.getGreen(), PICK_MARKER_COLOR.getBlue(), 145), 5.0f);
                }

                double[] g1 = has1 ? sideGeometry(poly, pickPos1, roomPoly, roomCenter) : null;
                double[] g2pt = (has2 && (!has1 || hasSpan)) ? sideGeometry(poly, pickPos2, roomPoly, roomCenter) : null;
                if (has1) {
                    marker(g2, poly, pickPos1, draggingHandle == 1 || hoveredHandle == 1);
                }
                if (g2pt != null) {
                    marker(g2, poly, pickPos2, draggingHandle == 2 || hoveredHandle == 2);
                }

                Color ptBg = new Color(255, 255, 255, 242), ptFg = new Color(25, 110, 60);
                if (g1 != null) {
                    double flankX = g2pt != null ? (g1[0] - g2pt[0])
                            : (hoverSide == pickSide && hoverScreenPt != null) ? (g1[0] - hoverScreenPt.x) : -g1[2];
                    double flankY = g2pt != null ? (g1[1] - g2pt[1])
                            : (hoverSide == pickSide && hoverScreenPt != null) ? (g1[1] - hoverScreenPt.y) : -g1[3];
                    drawOutwardCallout(g2, g1[0], g1[1], g1[4], g1[5], flankX, flankY, false,
                            formatCm(nativeToShown(pickPos1, sideLen)) + " cm", ptBg, PICK_MARKER_COLOR, ptFg, placedCallouts);
                }
                if (g2pt != null) {
                    double flankX = g1 != null ? (g2pt[0] - g1[0]) : g2pt[2];
                    double flankY = g1 != null ? (g2pt[1] - g1[1]) : g2pt[3];
                    drawOutwardCallout(g2, g2pt[0], g2pt[1], g2pt[4], g2pt[5], flankX, flankY, false,
                            formatCm(nativeToShown(pickPos2, sideLen)) + " cm", ptBg, PICK_MARKER_COLOR, ptFg, placedCallouts);
                }
                // Show stretch length only once finalized/selected, never while dragging or hovering before Click 2
                if (hasSpan && !activeDrag) {
                    double[] gMid = sideGeometry(poly, 0.5 * (pickPos1 + pickPos2), roomPoly, roomCenter);
                    String spanText = (g2.getFont().canDisplay('\u2194') ? "\u2194 " : "")
                            + formatCm(Math.abs(pickPos2 - pickPos1)) + " cm";
                    drawOutwardCallout(g2, gMid[0], gMid[1], gMid[4], gMid[5], 0.0, 0.0, true,
                            spanText, new Color(255, 248, 224, 245), new Color(205, 148, 0), new Color(115, 76, 0), placedCallouts);
                }
            }

            if (dialogMode == DIALOG_START_POINT && n > 0) {
                double[] sp = startPoint(currentRoom);
                marker(g2, sides.get(Math.max(0, Math.min(n - 1, (int) Math.round(sp[0])))), sp[1], false);
            }

            if (n > 1) {
                for (int i = 0; i < n; i++) {
                    Point2D.Double s = toScreen(sides.get(i)[0]);
                    boolean adjSelected = segmentsMode
                            ? (i == activeSegmentSide || (i - 1 + n) % n == activeSegmentSide)
                            : (table.isRowSelected(i) || table.isRowSelected((i - 1 + n) % n));
                    g2.setColor(adjSelected ? ACCENT : new Color(WALL.getRed(), WALL.getGreen(), WALL.getBlue(), anySelected ? 95 : 190));
                    double r = adjSelected ? 3.2 : 2.2;
                    g2.fill(new Ellipse2D.Double(s.x - r, s.y - r, 2 * r, 2 * r));
                }
            }

            if (n > 0) {
                int refSide = segmentsMode ? activeSegmentSide : table.getSelectedRow();
                for (int i = 0; refSide < 0 && i < n; i++) {
                    if (!ends.skipped[i]) {
                        refSide = i;
                    }
                }
                double[] d = middleDirection(sides.get(Math.max(0, Math.min(n - 1, refSide))));
                drawTopLeftDirectionArrow(g2, reversed ? -d[0] : d[0], reversed ? -d[1] : d[1]);
            }

            if (segmentsMode && !activeDrag && hoveredHandle == 0
                    && hoverSide >= 0 && hoverSide < n && !Double.isNaN(hoverNativePos)) {
                float[][] poly = sides.get(hoverSide);
                double[] gh = sideGeometry(poly, hoverNativePos, roomPoly, roomCenter);
                g2.setColor(ACCENT);
                g2.fill(new Ellipse2D.Double(gh[0] - 3.2, gh[1] - 3.2, 6.4, 6.4));
                double flankX = gh[2], flankY = gh[3];
                if (pickSide == hoverSide && !Double.isNaN(pickPos1) && Double.isNaN(pickPos2)) {
                    Point2D.Double s1 = screenPointAt(poly, pickPos1);
                    flankX = gh[0] - s1.x;
                    flankY = gh[1] - s1.y;
                }
                String hoverLabel = text("sidesSide", "Lado") + " " + (hoverSide + 1) + ": "
                        + formatCm(nativeToShown(hoverNativePos, SideModel.length(poly))) + " cm";
                drawOutwardCallout(g2, gh[0], gh[1], gh[4], gh[5], flankX, flankY, false,
                        hoverLabel, new Color(32, 41, 56, 232), new Color(18, 24, 34), Color.WHITE, placedCallouts);
            }
        }
    }
}
