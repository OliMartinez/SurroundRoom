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
import com.eteks.sweethome3d.model.UserPreferences;
import com.eteks.sweethome3d.model.Wall;
import com.eteks.sweethome3d.viewcontroller.HomeController;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.ComponentOrientation;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.geom.Area;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
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
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JToggleButton;
import javax.swing.KeyStroke;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;

/**
 * Main configuration window: placement/topology bar on top, type cards and live 2D preview/editor
 * on the left, and type-dependent placement, opening, collision, stacking, and corner options on the right.
 */
public class SurroundConfigFrame extends JFrame {

    private final ResourceBundle resource;
    private final UiKit.Texts texts;
    private final UserPreferences userPreferences;
    private final Consumer<SurroundConfig> onConfirmCallback;

    private Home home;
    private List<HomePieceOfFurniture> selectedFurniture = new ArrayList<>();
    private HomeController homeController;
    private List<HomePieceOfFurniture> homeObstacles = new ArrayList<>();
    private double objectDepth = 30;
    private final double defaultHalfObjectLength;

    private final List<SurroundTarget> baseTargets;
    List<SurroundTarget> targets;
    Map<Room, SurroundConfig.SideEnds> sideEnds = new HashMap<>();
    final Set<Room> excludedRooms = new HashSet<>();
    Map<Room, double[]> type7StartPoints = new HashMap<>();

    private boolean lastMergeCollinear;
    private Area contactWalls;

    final ModePanel modePanel;
    final TypeCardsPanel typePanel;
    final Type7Panel type7Panel;
    final UniformPanel uniformPanel;
    final OptionsPanel optionsPanel;
    private final JPanel type7Section, uniformSection;

    private final OutlinePreview outlinePreview = new OutlinePreview();
    private SideEndsPanel sideEndsPanel;
    private JPanel previewCardPanel, leftColumn, previewContainer;
    private DynamicCardLayout previewCardLayout;

    final JCheckBox showPreviewCheckBox;
    private JToggleButton tabPreview, tabRooms, tabSides, tabSegments, tabStartPoint;
    private JPanel surfaceSelectorPanel;
    private JComboBox<Room> surfaceCombo;
    private boolean updatingSurfaceCombo, editingModeActive, enableExcludedSegments;
    private ObjectPicker cornerPicker;

    private final Timer previewTimer = new Timer(120, e -> updatePreviewAsync());
    private final Timer loadingBadgeTimer;
    private volatile int previewSeq;
    private volatile SurroundGeometryEngine runningPreviewEngine;

    public SurroundConfigFrame(ResourceBundle resource, UserPreferences userPreferences,
            int selectedFurnitureCount, List<SurroundTarget> targets, double defaultHalfObjectLength,
            boolean upperLevel, Consumer<SurroundConfig> onConfirmCallback) {
        super(resource.getString("SURROUND_ROOM.TITLE"));
        this.resource = resource;
        this.userPreferences = userPreferences;
        this.texts = new UiKit.Texts(resource);
        this.onConfirmCallback = onConfirmCallback;
        this.baseTargets = targets != null ? new ArrayList<>(targets) : new ArrayList<SurroundTarget>();
        this.targets = new ArrayList<>(this.baseTargets);
        this.defaultHalfObjectLength = Math.max(1.0, defaultHalfObjectLength);
        this.showPreviewCheckBox = new JCheckBox(texts.get("showLivePreview", "Mostrar objetos en vista previa"), true);
        this.loadingBadgeTimer = new Timer(180, e -> outlinePreview.setLoading(true, texts.get("previewLoading", "Cargando\u2026")));
        this.loadingBadgeTimer.setRepeats(false);

        boolean openWalls = false;
        List<Room> rawRooms = new ArrayList<>();
        List<Wall> rawWalls = new ArrayList<>();
        for (SurroundTarget target : this.targets) {
            if (target.getKind() == SurroundTarget.ROOM) {
                rawRooms.add(target.getRoom());
            } else {
                openWalls |= !target.isClosed();
                rawWalls.addAll(target.getWalls());
            }
        }
        boolean multipleTouchingClosed = SurroundTarget.hasMultipleTouchingClosedSets(rawRooms, rawWalls);

        modePanel = new ModePanel(texts, !this.targets.isEmpty(), openWalls, multipleTouchingClosed,
                this::modeChanged, this::topologyOptionChanged);
        typePanel = new TypeCardsPanel(texts, this::typeChanged);
        type7Panel = new Type7Panel(texts, this::settingsChanged);
        uniformPanel = new UniformPanel(texts, this::settingsChanged);
        optionsPanel = new OptionsPanel(texts, userPreferences, !this.targets.isEmpty(), upperLevel,
                this::settingsChanged);
        optionsPanel.setOnConfigureRandomProps(() -> showPropsDialog(false));
        optionsPanel.setOnConfigureGradualProps(() -> showPropsDialog(true));
        optionsPanel.setOnPickCornerObject(this::pickCornerObject);
        type7Section = UiKit.section(texts.get("type7Settings", "Type 7 settings"), type7Panel);
        uniformSection = UiKit.section(texts.get("uniformSettings", "Around the whole outline"), uniformPanel);

        syncTargetSideDefaults();
        initComponents();
    }

    private void initComponents() {
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setAlwaysOnTop(true);
        UiKit.applyWindowIcon(this);

        previewCardLayout = new DynamicCardLayout();
        previewCardPanel = new JPanel(previewCardLayout);
        JPanel normalPreviewCard = new JPanel(new BorderLayout());
        normalPreviewCard.add(outlinePreview, BorderLayout.CENTER);

        JPanel previewHeader = new JPanel();
        previewHeader.setLayout(new BoxLayout(previewHeader, BoxLayout.Y_AXIS));
        previewHeader.setOpaque(false);
        previewHeader.setBorder(new EmptyBorder(0, 0, 4, 0));

        tabPreview = createModeTab(texts.get("livePreview", "Vista previa"), true);
        tabRooms = createModeTab(texts.get("chooseRoomsLong", "Excluir habitaciones"), false);
        tabSides = createModeTab(texts.get("chooseSidesLong", "Excluir lados"), false);
        tabSegments = createModeTab(texts.get("chooseStretchesLong", "Excluir tramos"), false);
        tabStartPoint = createModeTab(texts.get("chooseStartPointLong", "Inicio"), false);
        tabStartPoint.setVisible(false);

        ButtonGroup modeTabGroup = new ButtonGroup();
        JPanel modeRow = new JPanel(new FlowLayout(FlowLayout.LEADING, 4, 0));
        modeRow.setOpaque(false);
        modeRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        boolean hasTargets = !this.targets.isEmpty();
        for (JToggleButton tab : new JToggleButton[]{tabPreview, tabRooms, tabSides, tabSegments, tabStartPoint}) {
            modeTabGroup.add(tab);
            modeRow.add(tab);
            if (tab != tabPreview) {
                tab.setEnabled(hasTargets);
            }
        }
        tabPreview.addActionListener(e -> {
            if (editingModeActive) {
                commitPendingEditsIfEditing();
                showNormalPreview();
            } else {
                updateModeTabStyles();
            }
        });
        tabRooms.addActionListener(e -> switchToEditorMode(SideEndsPanel.DIALOG_ROOMS));
        tabSides.addActionListener(e -> switchToEditorMode(SideEndsPanel.DIALOG_SIDES));
        tabSegments.addActionListener(e -> switchToEditorMode(SideEndsPanel.DIALOG_SEGMENTS));
        tabStartPoint.addActionListener(e -> switchToEditorMode(SideEndsPanel.DIALOG_START_POINT));

        showPreviewCheckBox.setOpaque(false);
        showPreviewCheckBox.addActionListener(e -> {
            boolean enabled = showPreviewCheckBox.isSelected();
            if (sideEndsPanel != null) {
                sideEndsPanel.setLivePreviewEnabled(enabled);
            }
            if (!enabled) {
                cancelAllPreviewWork();
                outlinePreview.show(currentFaces(), buildConfig(), Collections.emptyList(), p -> false, 0, 0);
            } else {
                settingsChanged();
            }
        });

        surfaceSelectorPanel = new JPanel(new FlowLayout(FlowLayout.LEADING, 4, 0));
        surfaceSelectorPanel.setOpaque(false);
        surfaceSelectorPanel.add(new JLabel(texts.get("sidesRoom", "Superficie:")));
        surfaceCombo = new JComboBox<>();
        surfaceCombo.setRenderer(UiKit.renderer(Room.class, this::faceDisplayName, false));
        surfaceCombo.addActionListener(e -> {
            if (!updatingSurfaceCombo && sideEndsPanel != null && editingModeActive) {
                Room selected = (Room) surfaceCombo.getSelectedItem();
                if (selected != null) {
                    sideEndsPanel.selectRoom(selected);
                }
            }
        });
        surfaceSelectorPanel.add(surfaceCombo);
        surfaceSelectorPanel.setVisible(false);

        JPanel cameraRight = new JPanel(new FlowLayout(FlowLayout.TRAILING, 4, 0));
        cameraRight.setOpaque(false);
        cameraRight.add(surfaceSelectorPanel);
        cameraRight.add(createZoomButton("\u2212", "zoomOutTip", "Alejar vista previa", 6,
                () -> sideEndsPanel.zoomOut(), outlinePreview::zoomOut));
        cameraRight.add(createZoomButton("1:1", "zoomResetTip", "Restablecer zoom", 5,
                () -> sideEndsPanel.resetZoom(), outlinePreview::resetZoom));
        cameraRight.add(createZoomButton("+", "zoomInTip", "Acercar vista previa", 6,
                () -> sideEndsPanel.zoomIn(), outlinePreview::zoomIn));

        JPanel cameraRow = new JPanel(new BorderLayout(6, 0));
        cameraRow.setOpaque(false);
        cameraRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        cameraRow.add(showPreviewCheckBox, BorderLayout.WEST);
        cameraRow.add(cameraRight, BorderLayout.EAST);

        previewHeader.add(modeRow);
        previewHeader.add(Box.createVerticalStrut(4));
        previewHeader.add(cameraRow);

        sideEndsPanel = new SideEndsPanel(resource,
                (result, mode) -> {
                    applyEditorResult(result, mode);
                    showNormalPreview();
                },
                this::showNormalPreview);
        previewCardPanel.add(normalPreviewCard, "NORMAL");
        previewCardPanel.add(sideEndsPanel, "EDIT");

        previewContainer = new JPanel(new BorderLayout(0, 4));
        previewContainer.setOpaque(false);
        previewContainer.add(previewHeader, BorderLayout.NORTH);
        previewContainer.add(previewCardPanel, BorderLayout.CENTER);
        updateModeTabStyles();

        leftColumn = new JPanel();
        leftColumn.setLayout(new BoxLayout(leftColumn, BoxLayout.Y_AXIS));
        leftColumn.add(UiKit.section(texts.get("opt", "Distribution type"), typePanel));
        leftColumn.add(Box.createVerticalStrut(10));
        leftColumn.add(UiKit.section(texts.get("livePreview", "Preview"), previewContainer));

        JPanel right = new JPanel();
        right.setLayout(new BoxLayout(right, BoxLayout.Y_AXIS));
        right.add(type7Section);
        right.add(uniformSection);
        right.add(Box.createVerticalStrut(10));
        right.add(UiKit.section(texts.get("moreOptionsTitle", "Settings and corners"), optionsPanel));

        JPanel configSplitPanel = new JPanel(new GridBagLayout());
        GridBagConstraints sc = new GridBagConstraints();
        sc.anchor = GridBagConstraints.FIRST_LINE_START;
        sc.fill = GridBagConstraints.HORIZONTAL;
        sc.gridy = 0; sc.gridx = 0; sc.insets = new Insets(4, 0, 0, 16);
        configSplitPanel.add(leftColumn, sc);
        sc.gridx = 1; sc.weightx = 1; sc.insets = new Insets(4, 0, 0, 0);
        configSplitPanel.add(right, sc);
        sc.gridy = 1; sc.weighty = 1;
        configSplitPanel.add(Box.createVerticalGlue(), sc);

        JPanel body = new JPanel(new BorderLayout(0, 10));
        body.setBorder(new EmptyBorder(10, 12, 8, 12));
        body.add(UiKit.section(texts.get("settings", "Placement and sides"), modePanel), BorderLayout.NORTH);
        body.add(configSplitPanel, BorderLayout.CENTER);

        JScrollPane scroll = new JScrollPane(body,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getVerticalScrollBar().setUnitIncrement(16);

        JPanel content = new JPanel(new BorderLayout());
        content.add(scroll, BorderLayout.CENTER);
        content.add(createFooter(), BorderLayout.SOUTH);
        setContentPane(content);

        typeChanged();
        updateSidesSummary();

        applyComponentOrientation(ComponentOrientation.getOrientation(Locale.getDefault()));
        getRootPane().registerKeyboardAction(e -> {
            if (editingModeActive && sideEndsPanel != null) {
                if (sideEndsPanel.isPickingInDrawing()) {
                    sideEndsPanel.cancelPicking();
                } else {
                    sideEndsPanel.discardAndClose();
                }
            } else {
                cancelAllPreviewWork();
                dispose();
            }
        }, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        fitToScreen();
    }

    private void fitToScreen() {
        boolean type7Visible = type7Section.isVisible();
        type7Section.setVisible(true);
        pack();
        type7Section.setVisible(type7Visible);
        Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        int width = Math.min(getWidth() + 24, screen.width - 40);
        int height = Math.min(getHeight(), screen.height - 40);
        setSize(width, height);
        setMinimumSize(new Dimension(Math.min(640, width), Math.min(480, height)));
        setLocationRelativeTo(null);
    }

    private JPanel createFooter() {
        JPanel footer = new JPanel(new BorderLayout(12, 0));
        footer.setBorder(new CompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, UiKit.CARD_BORDER),
                new EmptyBorder(8, 12, 8, 12)));

        JPanel donatePanel = new JPanel(new FlowLayout(FlowLayout.LEADING, 4, 0));
        JButton donateButton = new JButton(texts.get("donateButton", "Donate"));
        donateButton.setFont(donateButton.getFont().deriveFont(11f));
        donateButton.setFocusable(false);
        donateButton.addActionListener(e -> {
            try {
                Desktop.getDesktop().browse(new URI("https://www.paypal.com/donate/?hosted_button_id=4MAYRTRRMQYE2"));
            } catch (IOException | URISyntaxException | UnsupportedOperationException ignored) {
            }
        });
        donatePanel.add(UiKit.hint(texts.get("donateLabel", "Support development with a donation:")));
        donatePanel.add(donateButton);

        String cancelText = UIManager.getString("OptionPane.cancelButtonText", getLocale());
        JButton cancelButton = new JButton(cancelText != null ? cancelText : "Cancel");
        cancelButton.addActionListener(e -> {
            cancelAllPreviewWork();
            dispose();
        });
        JButton surroundButton = new JButton(texts.get("undoSurround", "Surround"));
        surroundButton.setFont(surroundButton.getFont().deriveFont(Font.BOLD));
        surroundButton.setMargin(new Insets(4, 22, 4, 22));
        surroundButton.addActionListener(e -> {
            commitPendingEditsIfEditing();
            cancelAllPreviewWork();
            if (onConfirmCallback != null) {
                onConfirmCallback.accept(buildConfig());
            }
        });
        getRootPane().setDefaultButton(surroundButton);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.TRAILING, 8, 0));
        actions.add(cancelButton);
        actions.add(surroundButton);
        footer.add(donatePanel, BorderLayout.CENTER);
        footer.add(actions, BorderLayout.LINE_END);
        return footer;
    }

    private void modeChanged() {
        int activeMode = editingModeActive && sideEndsPanel != null ? sideEndsPanel.getDialogMode() : -1;
        commitPendingEditsIfEditing();
        typePanel.setInside(modePanel.isInterior());
        syncTargetSideDefaults();
        settingsChanged();
        updateSidesSummary();
        if (activeMode >= 0) {
            startEmbeddedSideEditing(activeMode);
        }
    }

    /** Rebuilds merged targets while preserving previously user-excluded sides by matching midpoints. */
    private void topologyOptionChanged() {
        int activeMode = editingModeActive && sideEndsPanel != null ? sideEndsPanel.getDialogMode() : -1;
        commitPendingEditsIfEditing();
        List<double[]> excludedMidpoints = collectExcludedSideMidpoints(lastMergeCollinear);
        List<SurroundTarget> previousTargets = this.targets;
        boolean mergeClosed = modePanel.mergeTouchingClosedSetsCheckBox.isSelected();
        List<Wall> allWalls = new ArrayList<>();
        List<SurroundTarget> roomOnes = new ArrayList<>();
        for (SurroundTarget t : baseTargets) {
            if (t.getKind() == SurroundTarget.ROOM) {
                roomOnes.add(t);
            } else {
                allWalls.addAll(t.getWalls());
            }
        }
        List<SurroundTarget> rebuilt = new ArrayList<>(mergeClosed ? SurroundTarget.mergeTouchingRoomTargets(roomOnes) : roomOnes);
        rebuilt.addAll(SurroundTarget.forWalls(allWalls, mergeClosed));
        this.targets = rebuilt;
        contactWalls = null;
        keepSettingsOfUnchangedFaces(previousTargets);
        syncTargetSideDefaults();
        restoreExcludedSidesByMidpoints(excludedMidpoints);
        updateSidesSummary();
        settingsChanged();
        if (activeMode == SideEndsPanel.DIALOG_ROOMS && currentFaces().size() <= 1) {
            showNormalPreview();
        } else if (activeMode >= 0) {
            startEmbeddedSideEditing(activeMode);
        }
    }

    private List<double[]> collectExcludedSideMidpoints(boolean prevMergeCollinear) {
        List<double[]> midpoints = new ArrayList<>();
        boolean interior = modePanel != null && modePanel.isInterior();
        for (SurroundTarget target : targets) {
            for (Room face : target.getFaces(interior)) {
                SurroundConfig.SideEnds ends = sideEnds.get(face);
                if (ends == null) {
                    continue;
                }
                List<float[][]> polys = SideModel.polylines(face, prevMergeCollinear);
                if (ends.skipped.length != polys.size()) {
                    continue;
                }
                for (int i = 0; i < polys.size(); i++) {
                    if (ends.skipped[i] && !(i < ends.forbidden.length && ends.forbidden[i])) {
                        float[][] poly = polys.get(i);
                        for (int k = 0; k + 1 < poly.length; k++) {
                            midpoints.add(new double[]{
                                0.5 * (poly[k][0] + poly[k + 1][0]), 0.5 * (poly[k][1] + poly[k + 1][1])
                            });
                        }
                    }
                }
            }
        }
        return midpoints;
    }

    private void restoreExcludedSidesByMidpoints(List<double[]> excludedMidpoints) {
        if (excludedMidpoints.isEmpty()) {
            return;
        }
        boolean interior = modePanel != null && modePanel.isInterior();
        boolean mergeCollinear = modePanel != null && modePanel.mergeCollinearCheckBox.isSelected();
        for (SurroundTarget target : targets) {
            for (Room face : target.getFaces(interior)) {
                SurroundConfig.SideEnds ends = sideEnds.get(face);
                if (ends == null) {
                    continue;
                }
                List<float[][]> polys = SideModel.polylines(face, mergeCollinear);
                for (int i = 0; i < polys.size() && i < ends.skipped.length; i++) {
                    float[][] poly = polys.get(i);
                    for (int k = 0; k + 1 < poly.length; k++) {
                        double mx = 0.5 * (poly[k][0] + poly[k + 1][0]), my = 0.5 * (poly[k][1] + poly[k + 1][1]);
                        for (double[] oldMid : excludedMidpoints) {
                            if (Math.hypot(mx - oldMid[0], my - oldMid[1]) < 1.0) {
                                ends.skipped[i] = true;
                                break;
                            }
                        }
                    }
                }
            }
        }
    }

    private void keepSettingsOfUnchangedFaces(List<SurroundTarget> previousTargets) {
        List<Room> previousFaces = new ArrayList<>();
        for (SurroundTarget target : previousTargets) {
            previousFaces.addAll(target.getFaces(false));
            previousFaces.addAll(target.getFaces(true));
        }
        Set<Room> prevExcluded = new HashSet<>(excludedRooms);
        excludedRooms.clear();
        for (SurroundTarget target : targets) {
            for (boolean interior : new boolean[]{false, true}) {
                for (Room face : target.getFaces(interior)) {
                    for (Room previous : previousFaces) {
                        if (sameGeometry(previous, face)) {
                            if (!sideEnds.containsKey(face) && sideEnds.containsKey(previous)) {
                                sideEnds.put(face, sideEnds.get(previous).copy());
                            }
                            if (prevExcluded.contains(previous)) {
                                excludedRooms.add(face);
                            }
                            break;
                        }
                    }
                }
            }
        }
    }

    private static boolean sameGeometry(Room a, Room b) {
        float[][] pa = a.getPoints(), pb = b.getPoints();
        if (pa.length != pb.length || pa.length == 0) {
            return false;
        }
        int n = pa.length;
        for (int shift = 0; shift < n; shift++) {
            boolean same = true;
            for (int i = 0; i < n && same; i++) {
                float[] p = pa[i], q = pb[(i + shift) % n];
                same = Math.abs(p[0] - q[0]) < 0.01f && Math.abs(p[1] - q[1]) < 0.01f;
            }
            if (same) {
                return true;
            }
        }
        return false;
    }

    private void syncTargetSideDefaults() {
        boolean interior = modePanel != null && modePanel.isInterior();
        boolean mergeCollinear = modePanel != null && modePanel.mergeCollinearCheckBox.isSelected();
        this.lastMergeCollinear = mergeCollinear;
        if (contactWalls == null) {
            contactWalls = SurroundTarget.wallsArea(targets);
        }
        for (SurroundTarget target : targets) {
            for (Room face : target.getFaces(interior)) {
                int sideCount = SideModel.count(face, mergeCollinear);
                SurroundConfig.SideEnds ends = sideEnds.get(face);
                if (ends == null || ends.skipped.length != sideCount) {
                    ends = new SurroundConfig.SideEnds(sideCount);
                    sideEnds.put(face, ends);
                }
                target.configureSideDefaults(face, ends, interior, mergeCollinear, contactWalls);
            }
        }
    }

    private void typeChanged() {
        int selectedType = typePanel.getSelectedType();
        boolean type7 = (selectedType == 7);
        optionsPanel.setSelectedType(selectedType);
        if (tabStartPoint != null) {
            tabStartPoint.setVisible(type7);
            if (!type7 && editingModeActive && sideEndsPanel != null
                    && sideEndsPanel.getDialogMode() == SideEndsPanel.DIALOG_START_POINT) {
                commitPendingEditsIfEditing();
                showNormalPreview();
            }
        }
        type7Section.setVisible(type7);
        type7Section.revalidate();
        type7Section.repaint();
        settingsChanged();
    }

    private void settingsChanged() {
        updateUniformSection();
        if (editingModeActive && sideEndsPanel != null) {
            sideEndsPanel.refreshPreviewConfig(buildConfig());
        }
        if (showPreviewCheckBox != null && !showPreviewCheckBox.isSelected()) {
            cancelAllPreviewWork();
            outlinePreview.show(currentFaces(), buildConfig(), Collections.emptyList(), p -> false, 0, 0);
            return;
        }
        SurroundGeometryEngine running = runningPreviewEngine;
        if (running != null) {
            running.cancel();
        }
        previewTimer.setRepeats(false);
        previewTimer.restart();
    }

    private void cancelAllPreviewWork() {
        previewSeq++;
        previewTimer.stop();
        loadingBadgeTimer.stop();
        outlinePreview.setLoading(false, null);
        SurroundGeometryEngine running = runningPreviewEngine;
        if (running != null) {
            running.cancel();
            runningPreviewEngine = null;
        }
        if (sideEndsPanel != null) {
            sideEndsPanel.cancelAsyncPreview();
        }
    }

    public void setProcessingOverlay(boolean active) {
        if (!active) {
            getGlassPane().setVisible(false);
            return;
        }
        final String msg = texts.get("surroundProcessing", "Procesando\u2026");
        JComponent glass = new JComponent() {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(new Color(255, 255, 255, 110));
                g2.fillRect(0, 0, getWidth(), getHeight());
                OutlinePreview.drawLoadingOverlay(g2, getWidth(), getHeight(), msg);
                g2.dispose();
            }
        };
        glass.setOpaque(false);
        glass.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        glass.addMouseListener(new MouseAdapter() {});
        setGlassPane(glass);
        glass.setVisible(true);
        glass.repaint();
    }

    @Override
    public void dispose() {
        if (cornerPicker != null) {
            cornerPicker.close();
            cornerPicker = null;
        }
        cancelAllPreviewWork();
        super.dispose();
    }

    private void updatePreviewAsync() {
        if (showPreviewCheckBox != null && !showPreviewCheckBox.isSelected()) {
            return;
        }
        final int seq = ++previewSeq;
        SurroundGeometryEngine prev = runningPreviewEngine;
        if (prev != null) {
            prev.cancel();
        }
        final SurroundConfig config = buildConfig();
        final List<Room> faces = currentFaces();
        final HomePieceOfFurniture primary = SurroundGeometryEngine.asSingleTemplate(selectedFurniture);
        final double bWidth = primary != null ? primary.getWidth() : 0;
        final double bDepth = primary != null ? primary.getDepth() : 0;

        if (home == null || selectedFurniture.isEmpty() || faces.isEmpty()) {
            loadingBadgeTimer.stop();
            outlinePreview.show(faces, config, Collections.emptyList(), p -> false, bWidth, bDepth);
            return;
        }

        final SurroundGeometryEngine engine = new SurroundGeometryEngine(home, null, faces, selectedFurniture);
        runningPreviewEngine = engine;
        loadingBadgeTimer.restart();

        Thread worker = new Thread(() -> {
            try {
                List<HomePieceOfFurniture> placed = engine.previewFirstLevel(config);
                SwingUtilities.invokeLater(() -> finishNormalPreview(seq, faces, config, placed, engine, bWidth, bDepth));
            } catch (CancellationException ignored) {
            } catch (RuntimeException ex) {
                SwingUtilities.invokeLater(() -> finishNormalPreview(seq, faces, config, Collections.emptyList(), null, bWidth, bDepth));
            }
        }, "SurroundPreviewWorker-" + seq);
        worker.setDaemon(true);
        worker.start();
    }

    private void finishNormalPreview(int seq, List<Room> faces, SurroundConfig config,
            List<HomePieceOfFurniture> placed, SurroundGeometryEngine engine, double bWidth, double bDepth) {
        if (seq != previewSeq) {
            return;
        }
        loadingBadgeTimer.stop();
        runningPreviewEngine = null;
        outlinePreview.show(faces, config, placed, engine != null ? engine::isCornerPiece : p -> false, bWidth, bDepth);
    }

    private void updateUniformSection() {
        int sel = typePanel.getSelectedType();
        boolean size = sel == 3 || sel == 4, gaps = sel == 1 || sel == 2;
        uniformPanel.showParts(size, gaps);
        optionsPanel.setUniformState(size && uniformPanel.sameSizeCheckBox.isSelected(), gaps && uniformPanel.sameGapsCheckBox.isSelected());
        if (uniformSection.isVisible() != (size || gaps)) {
            uniformSection.setVisible(size || gaps);
            uniformSection.revalidate();
            uniformSection.repaint();
        }
    }

    List<Room> currentFaces() {
        List<Room> faces = new ArrayList<>();
        boolean interior = modePanel.isInterior();
        for (SurroundTarget target : targets) {
            faces.addAll(target.getFaces(interior));
        }
        return faces;
    }

    private List<String> currentFaceNames() {
        List<String> names = new ArrayList<>();
        boolean interior = modePanel.isInterior();
        for (SurroundTarget target : targets) {
            for (Room face : target.getFaces(interior)) {
                if (target.getKind() == SurroundTarget.ROOM) {
                    String base = MessageFormat.format(texts.get("sidesRoomDefault", "Room {0}"), target.getNumber());
                    String name = face.getName();
                    names.add(name != null && !name.trim().isEmpty() ? base + ": " + name.trim() : base);
                } else {
                    int inner = target.getInnerFaceIndex(face);
                    names.add(inner > 0
                            ? MessageFormat.format(texts.get("wallsInner", "Walls {0}, inner face {1}"), target.getNumber(), inner)
                            : MessageFormat.format(texts.get("wallsName", "Walls {0}"), target.getNumber()));
                }
            }
        }
        return names;
    }

    private Set<Room> alwaysExteriorFaces() {
        Set<Room> faces = new HashSet<>();
        for (SurroundTarget target : targets) {
            for (Room face : target.getFaces(false)) {
                if (target.isAlwaysExterior(face)) {
                    faces.add(face);
                }
            }
        }
        return faces;
    }

    private String faceDisplayName(Room face) {
        List<Room> faces = currentFaces();
        List<String> names = currentFaceNames();
        int idx = faces.indexOf(face);
        String base = (idx >= 0 && idx < names.size()) ? names.get(idx)
                : (face != null && face.getName() != null ? face.getName() : texts.get("sidesRoomDefault", "Surface"));
        return (face != null && excludedRooms.contains(face))
                ? base + " (" + texts.get("roomExcludedTag", "excluida") + ")" : base;
    }

    private void updateSidesSummary() {
        boolean mergeCollinear = modePanel.mergeCollinearCheckBox.isSelected();
        List<Room> faces = currentFaces();
        int excludedCount = 0, skipped = 0, customSegs = 0;
        for (Room face : faces) {
            if (excludedRooms.contains(face)) {
                excludedCount++;
                continue;
            }
            SurroundConfig.SideEnds ends = sideEnds.get(face);
            if (ends == null) {
                continue;
            }
            if (ends.skipped.length == SideModel.count(face, mergeCollinear)) {
                skipped += ends.skippedCount();
            }
            if (enableExcludedSegments) {
                for (List<double[]> list : ends.excludedSegments) {
                    customSegs += list.size();
                }
            }
        }
        if (tabRooms != null) {
            tabRooms.setVisible(faces.size() > 1);
            setTabCounter(tabRooms, "chooseRoomsLong", "Exclude rooms", excludedCount);
        }
        setTabCounter(tabSides, "chooseSidesLong", "Exclude sides", skipped);
        setTabCounter(tabSegments, "chooseStretchesLong", "Exclude stretches", customSegs);
        if (tabStartPoint != null) {
            tabStartPoint.setText(stripEllipsis(texts.get("chooseStartPointLong", "Start")));
        }
    }

    private void setTabCounter(JToggleButton tab, String key, String fallback, int count) {
        if (tab != null) {
            String base = stripEllipsis(texts.get(key, fallback));
            tab.setText(count > 0 ? base + " (" + count + ")" : base);
        }
    }

    private JButton createZoomButton(String label, String tipKey, String tipFallback, int padX,
            Runnable onEditing, Runnable onNormal) {
        JButton btn = new JButton(label);
        btn.setMargin(new Insets(1, padX, 1, padX));
        btn.setToolTipText(texts.get(tipKey, tipFallback));
        btn.addActionListener(e -> {
            if (sideEndsPanel != null && editingModeActive) {
                onEditing.run();
            } else {
                onNormal.run();
            }
        });
        return btn;
    }

    private static String stripEllipsis(String s) {
        String r = s != null ? s.trim() : "";
        while (r.endsWith("\u2026") || r.endsWith(".")) {
            r = r.substring(0, r.length() - 1).trim();
        }
        return r;
    }

    private JToggleButton createModeTab(String text, boolean selected) {
        JToggleButton btn = new JToggleButton(stripEllipsis(text), selected);
        btn.setFocusPainted(false);
        btn.setMargin(new Insets(2, 8, 2, 8));
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return btn;
    }

    private void updateModeTabStyles() {
        Color activeBg = new Color(232, 241, 252);
        for (JToggleButton tab : new JToggleButton[]{tabPreview, tabRooms, tabSides, tabSegments, tabStartPoint}) {
            if (tab == null) {
                continue;
            }
            boolean sel = tab.isSelected();
            tab.setFont(tab.getFont().deriveFont(sel ? Font.BOLD : Font.PLAIN));
            tab.setForeground(sel ? UiKit.ACCENT.darker() : UIManager.getColor("ToggleButton.foreground"));
            tab.setBackground(sel ? activeBg : UIManager.getColor("ToggleButton.background"));
        }
        if (previewContainer != null) {
            previewContainer.setBorder(editingModeActive
                    ? new CompoundBorder(BorderFactory.createLineBorder(UiKit.ACCENT, 2), new EmptyBorder(4, 4, 4, 4))
                    : null);
            previewContainer.revalidate();
            previewContainer.repaint();
        }
    }

    private void applyEditorResult(Map<Room, SurroundConfig.SideEnds> result, int mode) {
        if (mode == SideEndsPanel.DIALOG_ROOMS && sideEndsPanel != null) {
            Set<Room> next = sideEndsPanel.getWorkingExcludedRooms();
            if (next.size() < currentFaces().size()) {
                excludedRooms.clear();
                excludedRooms.addAll(next);
            }
        } else if (result != null) {
            sideEnds.putAll(result);
            if (mode == SideEndsPanel.DIALOG_SEGMENTS) {
                enableExcludedSegments = hasAnyExcludedSegments();
            } else if (mode == SideEndsPanel.DIALOG_START_POINT && sideEndsPanel != null) {
                type7StartPoints.clear();
                type7StartPoints.putAll(sideEndsPanel.getWorkingStartPoints());
            }
        }
        updateSidesSummary();
        settingsChanged();
    }

    private void commitPendingEditsIfEditing() {
        if (editingModeActive && sideEndsPanel != null) {
            applyEditorResult(sideEndsPanel.commitSilently(), sideEndsPanel.getDialogMode());
        }
    }

    private void switchToEditorMode(int dialogMode) {
        if (editingModeActive && sideEndsPanel != null) {
            if (sideEndsPanel.getDialogMode() == dialogMode) {
                updateModeTabStyles();
                return;
            }
            commitPendingEditsIfEditing();
        }
        startEmbeddedSideEditing(dialogMode);
    }

    private boolean hasAnyExcludedSegments() {
        for (SurroundConfig.SideEnds ends : sideEnds.values()) {
            if (ends != null) {
                for (List<double[]> list : ends.excludedSegments) {
                    if (!list.isEmpty()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private void startEmbeddedSideEditing(int dialogMode) {
        syncTargetSideDefaults();
        List<Room> faces = currentFaces();
        sideEndsPanel.setLivePreviewEnabled(showPreviewCheckBox == null || showPreviewCheckBox.isSelected());
        sideEndsPanel.startEditing(faces, currentFaceNames(), sideEnds, excludedRooms,
                modePanel.isInterior(), alwaysExteriorFaces(), dialogMode,
                modePanel.mergeCollinearCheckBox.isSelected(), defaultHalfObjectLength);
        sideEndsPanel.setPreviewContext(home, selectedFurniture, buildConfig(), homeObstacles, objectDepth);

        editingModeActive = true;
        JToggleButton targetTab = dialogMode == SideEndsPanel.DIALOG_ROOMS ? tabRooms
                : dialogMode == SideEndsPanel.DIALOG_SIDES ? tabSides
                : dialogMode == SideEndsPanel.DIALOG_SEGMENTS ? tabSegments : tabStartPoint;
        if (targetTab != null) {
            targetTab.setSelected(true);
        }

        if (surfaceCombo != null && surfaceSelectorPanel != null) {
            updatingSurfaceCombo = true;
            try {
                surfaceCombo.removeAllItems();
                int firstIncluded = 0;
                for (int i = 0; i < faces.size(); i++) {
                    Room r = faces.get(i);
                    surfaceCombo.addItem(r);
                    if (firstIncluded == 0 && excludedRooms.contains(faces.get(0)) && !excludedRooms.contains(r)) {
                        firstIncluded = i;
                    }
                }
                if (!faces.isEmpty()) {
                    surfaceCombo.setSelectedIndex(firstIncluded);
                }
            } finally {
                updatingSurfaceCombo = false;
            }
            surfaceSelectorPanel.setVisible(dialogMode != SideEndsPanel.DIALOG_ROOMS && faces.size() > 1);
        }

        showPreviewCard("EDIT");
        SwingUtilities.invokeLater(() -> {
            if (previewContainer != null && previewContainer.isShowing()) {
                previewContainer.scrollRectToVisible(new Rectangle(0, 0,
                        previewContainer.getWidth(), previewContainer.getHeight()));
            }
        });
    }

    private void showNormalPreview() {
        editingModeActive = false;
        if (tabPreview != null) {
            tabPreview.setSelected(true);
        }
        if (surfaceSelectorPanel != null) {
            surfaceSelectorPanel.setVisible(false);
        }
        showPreviewCard("NORMAL");
    }

    private void showPreviewCard(String card) {
        previewCardLayout.show(previewCardPanel, card);
        updateModeTabStyles();
        previewCardPanel.revalidate();
        previewCardPanel.repaint();
        if (leftColumn != null) {
            leftColumn.revalidate();
            leftColumn.repaint();
        }
    }

    private void pickCornerObject() {
        if (home == null) {
            return;
        }
        if (cornerPicker != null) {
            cornerPicker.close();
        }
        setVisible(false);
        cornerPicker = ObjectPicker.showForCorner(this, home, homeController, texts, getTitle(),
                picked -> {
                    cornerPicker = null;
                    if (picked != null && !picked.isEmpty()) {
                        HomePieceOfFurniture template = SurroundGeometryEngine.asSingleTemplate(picked);
                        if (template != null) {
                            optionsPanel.setCustomCornerPiece(template.clone());
                        }
                    }
                    setVisible(true);
                    toFront();
                    settingsChanged();
                },
                () -> {
                    cornerPicker = null;
                    if (isDisplayable()) {
                        setVisible(true);
                        toFront();
                    }
                });
    }

    private List<HomePieceOfFurniture> collectActiveTemplates() {
        List<HomePieceOfFurniture> templates = new ArrayList<>(selectedFurniture);
        if (optionsPanel.cornerPlaceOrthogonalRadio.isSelected()
                && optionsPanel.useCatalogCornerCheckBox.isSelected()
                && optionsPanel.getCustomCornerPiece() != null) {
            templates.add(optionsPanel.getCustomCornerPiece());
        }
        return templates;
    }

    private SurroundConfig currentSnapshotConfig() {
        SurroundConfig snap = new SurroundConfig();
        snap.interior = modePanel.isInterior();
        snap.surroundType = typePanel.getSelectedType();
        snap.mergeCollinearSides = modePanel.mergeCollinearCheckBox.isSelected();
        snap.mergeTouchingClosedSets = modePanel.mergeTouchingClosedSetsCheckBox.isVisible()
                && modePanel.mergeTouchingClosedSetsCheckBox.isSelected();
        optionsPanel.fill(snap);
        snap.enableExcludedSegments = this.enableExcludedSegments && hasAnyExcludedSegments();
        snap.excludedRooms.addAll(this.excludedRooms);
        type7Panel.fill(snap);
        uniformPanel.fill(snap);
        for (Map.Entry<Room, double[]> e : type7StartPoints.entrySet()) {
            if (e.getValue() != null) {
                snap.type7StartPoints.put(e.getKey(), e.getValue().clone());
            }
        }
        return snap;
    }

    private void showPropsDialog(boolean gradual) {
        boolean wasOnTop = isAlwaysOnTop();
        setAlwaysOnTop(false);
        try {
            SurroundConfig snap = currentSnapshotConfig();
            JCheckBox otherCheck = gradual ? optionsPanel.enableRandomPropsCheckBox : optionsPanel.enableGradualPropsCheckBox;
            SurroundRandomSettings edited = gradual ? optionsPanel.gradualSettings : optionsPanel.randomSettings;
            SurroundRandomSettings other = !otherCheck.isSelected() ? null
                    : gradual ? optionsPanel.randomSettings : optionsPanel.gradualSettings;
            if (gradual) {
                SurroundRandomDialog.showGradualDialog(this, resource, userPreferences, homeController,
                        edited, collectActiveTemplates(), snap, other);
                optionsPanel.updateGradualPropsSummary();
            } else {
                SurroundRandomDialog.showRandomDialog(this, resource, userPreferences, homeController,
                        edited, collectActiveTemplates(), snap, other);
                optionsPanel.updateRandomPropsSummary();
            }
            settingsChanged();
        } finally {
            setAlwaysOnTop(wasOnTop);
        }
    }

    public void setTemplateContext(Home home, List<HomePieceOfFurniture> selectedFurniture, HomeController homeController) {
        this.home = home;
        this.selectedFurniture = selectedFurniture != null
                ? new ArrayList<>(selectedFurniture) : new ArrayList<HomePieceOfFurniture>();
        this.homeController = homeController;
        settingsChanged();
    }

    SurroundConfig buildConfig() {
        syncTargetSideDefaults();
        SurroundConfig cfg = currentSnapshotConfig();
        for (Map.Entry<Room, SurroundConfig.SideEnds> entry : sideEnds.entrySet()) {
            cfg.sideEnds.put(entry.getKey(), entry.getValue().copy());
        }
        cfg.alwaysExterior.addAll(alwaysExteriorFaces());
        for (SurroundTarget target : targets) {
            if (target.getKind() == SurroundTarget.ROOM) {
                cfg.faceSourceRooms.put(target.getRoom(), target.getSourceRooms());
            } else if (home != null) {
                // Floors enclosed by the walls are not "other nearby floors" for them
                List<Room> enclosed = target.getEnclosedRooms(home.getRooms());
                for (Room face : target.getFaces(false)) {
                    cfg.faceSourceRooms.put(face, enclosed);
                }
                for (Room face : target.getFaces(true)) {
                    cfg.faceSourceRooms.put(face, enclosed);
                }
            }
        }
        return cfg;
    }

    public void setHomeObstacles(List<HomePieceOfFurniture> obstacles, double objectDepth) {
        this.homeObstacles = obstacles != null ? obstacles : new ArrayList<HomePieceOfFurniture>();
        this.objectDepth = objectDepth;
    }

    public List<SurroundTarget> getActiveTargets() {
        return Collections.unmodifiableList(targets);
    }

    /** CardLayout sizing itself to the currently visible card rather than the largest card. */
    private static class DynamicCardLayout extends CardLayout {
        private Dimension visibleSize(Container parent, boolean preferred) {
            for (Component comp : parent.getComponents()) {
                if (comp.isVisible()) {
                    Insets in = parent.getInsets();
                    Dimension d = preferred ? comp.getPreferredSize() : comp.getMinimumSize();
                    return new Dimension(d.width + in.left + in.right, d.height + in.top + in.bottom);
                }
            }
            return preferred ? super.preferredLayoutSize(parent) : super.minimumLayoutSize(parent);
        }

        @Override
        public Dimension preferredLayoutSize(Container parent) {
            return visibleSize(parent, true);
        }

        @Override
        public Dimension minimumLayoutSize(Container parent) {
            return visibleSize(parent, false);
        }
    }
}
