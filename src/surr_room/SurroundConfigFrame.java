/*
 * Copyright (c) 2024-2026 Oliver Mart\u00ednez (Ollie_Jess)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package surr_room;

import com.eteks.sweethome3d.model.Room;
import com.eteks.sweethome3d.model.UserPreferences;
import com.eteks.sweethome3d.model.Wall;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.ComponentOrientation;
import java.awt.Container;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.event.KeyEvent;
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
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.KeyStroke;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;

/**
 * Main window: the mode bar ({@link ModePanel}) on top; the type cards and the preview ({@link OutlinePreview})
 * on the left; the type 7, same size or spaces ({@link UniformPanel}) and option panels on the right; the
 * donation link, Cancel and Surround at the bottom. Includes an optional live preview toggle ("See preview")
 * and non-blocking asynchronous background calculation with a centered "Loading..." indicator.
 */
public class SurroundConfigFrame extends JFrame {

    /** Localized text bundle helper. */
    private final UiKit.Texts texts;

    /** Resource bundle for internationalization. */
    private final ResourceBundle resource;

    /** User preferences providing catalog access and unit settings. */
    private final UserPreferences userPreferences;

    /** Callback invoked with the completed {@link SurroundConfig} upon user confirmation. */
    private final Consumer<SurroundConfig> onConfirmCallback;

    /** Active Sweet Home 3D Home instance. */
    private com.eteks.sweethome3d.model.Home home;

    /** List of template furniture pieces to place around the perimeter. */
    private List<com.eteks.sweethome3d.model.HomePieceOfFurniture> selectedFurniture = new ArrayList<>();

    /** Parent HomeController instance. */
    private com.eteks.sweethome3d.viewcontroller.HomeController homeController;

    /** Potential obstacle furniture pieces in the home (doors, windows, furniture). */
    private List<com.eteks.sweethome3d.model.HomePieceOfFurniture> homeObstacles = new ArrayList<>();

    /** Depth of the primary template piece in cm. */
    private double objectDepth = 30;

    /** Half-length of the primary template piece in cm (used for default corner margins). */
    private final double defaultHalfObjectLength;

    /** Original target entities as initially selected prior to topological merging. */
    private final List<SurroundTarget> baseTargets;

    /** Active target entities, updated when touching closed sets or walls are merged. */
    List<SurroundTarget> targets;

    /** Map storing per-room side end configurations and exclusions. */
    Map<Room, SurroundConfig.SideEnds> sideEnds = new HashMap<>();

    /** Map storing per-room Type 7 starting points {logicalSideIndex, nativeDistAlongSide}. */
    Map<Room, double[]> type7StartPoints = new HashMap<>();

    /** Cached state of the collinear merge toggle to detect transitions. */
    private boolean lastMergeCollinear = false;

    /** Cached 2D Area of all wall footprints used to detect contact zones. */
    private java.awt.geom.Area contactWalls;

    /** Top bar panel for orientation, side selection, and topology merging. */
    final ModePanel modePanel;

    /** Card selection panel for surrounding Types 1 through 7. */
    final TypeCardsPanel typePanel;

    /** Sub-panel for Type 7 distribution and random sizing options. */
    final Type7Panel type7Panel;

    /** Sub-panel for uniform sizing (Types 3 &amp; 4) and uniform gaps (Types 1 &amp; 2). */
    final UniformPanel uniformPanel;

    /** Options panel for auxiliary placement parameters, collisions, stacking, and corners. */
    final OptionsPanel optionsPanel;

    /** Collapsible container section hosting {@link #type7Panel}. */
    private final JPanel type7Section;

    /** Collapsible container section hosting {@link #uniformPanel}. */
    private final JPanel uniformSection;

    /** 2D vector preview panel showing top-down plan and placed furniture. */
    private final OutlinePreview outlinePreview = new OutlinePreview();

    /** Embedded interactive editor panel for selecting sides, excluded stretches, and Type 7 start point. */
    private SideEndsPanel sideEndsPanel;

    /** CardLayout container panel hosting normal preview and embedded editor. */
    private JPanel previewCardPanel;

    /** Dynamic card layout managing transitions between normal preview and editor. */
    private DynamicCardLayout previewCardLayout;

    /** Left column container hosting type cards and preview section. */
    private JPanel leftColumn;

    /** Summary label displayed inside the normal preview card. */
    private JLabel previewSidesSummary;

    /** Checkbox toggling whether the live preview calculates and displays changes. */
    final JCheckBox showPreviewCheckBox;

    /** Button in the preview toolbar for selecting sides. */
    private JButton btnChooseSides;

    /** Button in the preview toolbar for selecting excluded stretches. */
    private JButton btnChooseSegments;

    /** Button in the preview toolbar for selecting the Type 7 starting point. */
    private JButton btnChooseStartPoint;

    /** Holds the three editor buttons; hidden while one of the editors is open. */
    private JPanel editorButtons;

    /** Tracks whether excluded segments are active in the surround configuration. */
    private boolean enableExcludedSegments = false;

    /** Debounce timer (120 ms) scheduling an asynchronous live preview update when settings settle. */
    private final Timer previewTimer = new Timer(120, e -> updatePreviewAsync());

    /** Timer (180 ms) showing the "Loading..." overlay only when a preview run takes noticeable time. */
    private final Timer loadingBadgeTimer;

    /** Sequence counter and reference for cancelling or queuing background preview runs. */
    private volatile int previewSeq = 0;
    private volatile SurroundGeometryEngine runningPreviewEngine;

    /**
     * Constructs a new SurroundConfigFrame.
     *
     * @param userPreferences        Sweet Home 3D user preferences
     * @param defaultHalfObjectLength half object length in cm
     * @param upperLevel             true if targets belong to an upper level with potential floor holes
     */
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
        this.showPreviewCheckBox = new JCheckBox(texts.get("showLivePreview", "Ver vista previa"), true);
        this.loadingBadgeTimer = new Timer(180, e -> outlinePreview.setLoading(true, texts.get("previewLoading", "Cargando\u2026")));
        this.loadingBadgeTimer.setRepeats(false);

        // Analyze targets for open wall segments and touching closed sets
        boolean openWalls = false, floors = false;
        List<Room> rawRooms = new ArrayList<>();
        List<Wall> rawWalls = new ArrayList<>();
        for (SurroundTarget target : this.targets) {
            if (target.getKind() == SurroundTarget.ROOM) {
                floors = true;
                rawRooms.add(target.getRoom());
            } else {
                openWalls |= !target.isClosed();
                rawWalls.addAll(target.getWalls());
            }
        }
        boolean multipleTouchingClosed = SurroundTarget.hasMultipleTouchingClosedSets(rawRooms, rawWalls);

        // Instantiate sub-panels
        modePanel = new ModePanel(texts, !this.targets.isEmpty(), openWalls, multipleTouchingClosed,
                this::modeChanged, this::topologyOptionChanged);
        typePanel = new TypeCardsPanel(texts, this::typeChanged);
        type7Panel = new Type7Panel(texts, this::settingsChanged);
        uniformPanel = new UniformPanel(texts, this::settingsChanged);
        optionsPanel = new OptionsPanel(texts, userPreferences, !this.targets.isEmpty(), floors, upperLevel,
                this::settingsChanged);
        optionsPanel.setOnConfigureRandomProps(() -> showPropsDialog(false));
        optionsPanel.setOnConfigureGradualProps(() -> showPropsDialog(true));
        type7Section = UiKit.section(texts.get("type7Settings", "Type 7 settings"), type7Panel);
        uniformSection = UiKit.section(texts.get("uniformSettings", "Around the whole outline"), uniformPanel);

        syncTargetSideDefaults();
        initComponents();
    }

    /** Builds and arranges all Swing UI components, scroll panes, split layout, and footer actions. */
    private void initComponents() {
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setAlwaysOnTop(true);
        UiKit.applyWindowIcon(this);

        // Bottom preview card container: DynamicCardLayout hosting normal live preview and embedded editor
        previewCardLayout = new DynamicCardLayout();
        previewCardPanel = new JPanel(previewCardLayout);

        // 1. Normal preview card: 2D live canvas
        JPanel normalPreviewCard = new JPanel(new BorderLayout());
        normalPreviewCard.add(outlinePreview, BorderLayout.CENTER);

        // Preview toolbar containing "See preview" checkbox, side/stretch/start-point buttons, and summary at the top
        JPanel previewToolbar = new JPanel(new BorderLayout(6, 2));
        previewToolbar.setBorder(new EmptyBorder(0, 0, 4, 0));

        JPanel btnGroup = new JPanel(new FlowLayout(FlowLayout.LEADING, 6, 0));
        showPreviewCheckBox.addActionListener(e -> {
            boolean enabled = showPreviewCheckBox.isSelected();
            outlinePreview.setVisible(enabled);
            if (sideEndsPanel != null) {
                sideEndsPanel.setLivePreviewEnabled(enabled);
            }
            if (!enabled) {
                cancelAllPreviewWork();
                outlinePreview.show(Collections.emptyList(), new SurroundConfig(), Collections.emptyList(), p -> false, 0, 0);
            } else {
                settingsChanged();
            }
            if (leftColumn != null) {
                leftColumn.revalidate();
                leftColumn.repaint();
            }
        });

        btnChooseSides = new JButton(texts.get("chooseSidesLong", "Seleccionar lados a rodear\u2026"));
        btnChooseSides.setToolTipText(texts.get("sidesShortHint", "Seleccione los lados que se incluir\u00e1n en el rodeo."));
        btnChooseSides.addActionListener(e -> startEmbeddedSideEditing(SideEndsPanel.DIALOG_SIDES));

        btnChooseSegments = new JButton(texts.get("chooseStretchesLong", "Seleccionar tramos para no rodear\u2026"));
        btnChooseSegments.setToolTipText(texts.get("segmentsShortHint", "Seleccione un lado para gestionar sus tramos excluidos."));
        btnChooseSegments.addActionListener(e -> startEmbeddedSideEditing(SideEndsPanel.DIALOG_SEGMENTS));

        btnChooseStartPoint = new JButton(texts.get("chooseStartPointLong", "Seleccionar inicio\u2026"));
        btnChooseStartPoint.setToolTipText(texts.get("t7StartShortHint", "Haga clic en el dibujo o indique la distancia donde inicia el tipo 7."));
        btnChooseStartPoint.addActionListener(e -> startEmbeddedSideEditing(SideEndsPanel.DIALOG_START_POINT));
        btnChooseStartPoint.setVisible(false);

        boolean hasTargets = !this.targets.isEmpty();
        btnChooseSides.setEnabled(hasTargets);
        btnChooseSegments.setEnabled(hasTargets);
        btnChooseStartPoint.setEnabled(hasTargets);

        JButton btnZoomIn = new JButton("+");
        btnZoomIn.setMargin(new Insets(1, 6, 1, 6));
        btnZoomIn.setToolTipText(texts.get("zoomInTip", "Acercar vista previa"));
        btnZoomIn.addActionListener(e -> {
            if (sideEndsPanel != null && sideEndsPanel.isVisible()) {
                sideEndsPanel.zoomIn();
            } else {
                outlinePreview.zoomIn();
            }
        });

        JButton btnZoomOut = new JButton("−");
        btnZoomOut.setMargin(new Insets(1, 6, 1, 6));
        btnZoomOut.setToolTipText(texts.get("zoomOutTip", "Alejar vista previa"));
        btnZoomOut.addActionListener(e -> {
            if (sideEndsPanel != null && sideEndsPanel.isVisible()) {
                sideEndsPanel.zoomOut();
            } else {
                outlinePreview.zoomOut();
            }
        });

        JButton btnZoomReset = new JButton("1:1");
        btnZoomReset.setMargin(new Insets(1, 5, 1, 5));
        btnZoomReset.setToolTipText(texts.get("zoomResetTip", "Restablecer zoom"));
        btnZoomReset.addActionListener(e -> {
            if (sideEndsPanel != null && sideEndsPanel.isVisible()) {
                sideEndsPanel.resetZoom();
            } else {
                outlinePreview.resetZoom();
            }
        });

        btnGroup.add(showPreviewCheckBox);
        btnGroup.add(btnZoomIn);
        btnGroup.add(btnZoomOut);
        btnGroup.add(btnZoomReset);
        // Editor buttons share one panel so they can be hidden together while an editor is open
        editorButtons = new JPanel(new FlowLayout(FlowLayout.LEADING, 6, 0));
        editorButtons.add(btnChooseSides);
        editorButtons.add(btnChooseSegments);
        editorButtons.add(btnChooseStartPoint);
        btnGroup.add(editorButtons);

        previewSidesSummary = new JLabel();
        previewSidesSummary.setForeground(UiKit.HINT_TEXT);

        previewToolbar.add(btnGroup, BorderLayout.WEST);
        previewToolbar.add(previewSidesSummary, BorderLayout.EAST);

        // 2. Embedded side, stretch & start-point editor panel
        sideEndsPanel = new SideEndsPanel(resource,
                (result, mode) -> {
                    if (result != null) {
                        sideEnds.putAll(result);
                        if (mode == SideEndsPanel.DIALOG_SEGMENTS) {
                            enableExcludedSegments = hasAnyExcludedSegments();
                        } else if (mode == SideEndsPanel.DIALOG_START_POINT) {
                            type7StartPoints.clear();
                            type7StartPoints.putAll(sideEndsPanel.getWorkingStartPoints());
                        }
                    }
                    updateSidesSummary();
                    settingsChanged();
                    showNormalPreview();
                },
                this::showNormalPreview);

        previewCardPanel.add(normalPreviewCard, "NORMAL");
        previewCardPanel.add(sideEndsPanel, "EDIT");

        // Container holding the toolbar at the top and the preview/editor cards below it
        JPanel previewContainer = new JPanel(new BorderLayout(0, 4));
        previewContainer.add(previewToolbar, BorderLayout.NORTH);
        previewContainer.add(previewCardPanel, BorderLayout.CENTER);

        // Left column: Type cards carousel and adaptive preview panel
        leftColumn = new JPanel();
        leftColumn.setLayout(new BoxLayout(leftColumn, BoxLayout.Y_AXIS));
        leftColumn.add(UiKit.section(texts.get("opt", "Distribution type"), typePanel));
        leftColumn.add(Box.createVerticalStrut(10));
        leftColumn.add(UiKit.section(texts.get("livePreview", "Preview"), previewContainer));

        // Right column: Type 7 options, uniform size/gap limits, and auxiliary options
        JPanel right = new JPanel();
        right.setLayout(new BoxLayout(right, BoxLayout.Y_AXIS));
        right.add(type7Section);
        right.add(uniformSection);
        right.add(Box.createVerticalStrut(10));
        right.add(UiKit.section(texts.get("moreOptionsTitle", "Settings and corners"), optionsPanel));

        // GridBag layout pairing left and right columns
        JPanel configSplitPanel = new JPanel(new GridBagLayout());
        GridBagConstraints sc = new GridBagConstraints();
        sc.anchor = GridBagConstraints.FIRST_LINE_START;
        sc.fill = GridBagConstraints.HORIZONTAL;
        sc.gridy = 0;
        sc.gridx = 0;
        sc.insets = new Insets(4, 0, 0, 16);
        configSplitPanel.add(leftColumn, sc);
        sc.gridx = 1;
        sc.weightx = 1;
        sc.insets = new Insets(4, 0, 0, 0);
        configSplitPanel.add(right, sc);
        sc.gridy = 1;
        sc.weighty = 1;
        configSplitPanel.add(Box.createVerticalGlue(), sc);

        // Body containing top mode bar and dual-column split
        JPanel body = new JPanel(new BorderLayout(0, 10));
        body.setBorder(new EmptyBorder(10, 12, 8, 12));
        body.add(UiKit.section(texts.get("settings", "Placement and sides"), modePanel), BorderLayout.NORTH);
        body.add(configSplitPanel, BorderLayout.CENTER);

        // Scrollable content pane for small displays
        JScrollPane scroll = new JScrollPane(body,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getVerticalScrollBar().setUnitIncrement(16);

        // Main content pane with footer
        JPanel content = new JPanel(new BorderLayout());
        content.add(scroll, BorderLayout.CENTER);
        content.add(createFooter(), BorderLayout.SOUTH);
        setContentPane(content);

        typeChanged();
        updateSidesSummary();

        applyComponentOrientation(ComponentOrientation.getOrientation(Locale.getDefault()));
        getRootPane().registerKeyboardAction(e -> dispose(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        fitToScreen();
    }

    /** Packs and bounds the window dimensions to fit comfortably within the current screen display area. */
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

    /** Builds the footer panel containing donation button and action buttons (Cancel and Surround). */
    private JPanel createFooter() {
        JPanel footer = new JPanel(new BorderLayout(12, 0));
        footer.setBorder(new CompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, UiKit.CARD_BORDER),
                new EmptyBorder(8, 12, 8, 12)));

        // Left side donation link
        JPanel donatePanel = new JPanel(new FlowLayout(FlowLayout.LEADING, 4, 0));
        JLabel donateLabel = UiKit.hint(texts.get("donateLabel", "Support development with a donation:"));
        JButton donateButton = new JButton(texts.get("donateButton", "Donate"));
        donateButton.setFont(donateButton.getFont().deriveFont(11f));
        donateButton.setFocusable(false);
        donateButton.addActionListener(e -> {
            try {
                Desktop.getDesktop().browse(new URI("https://www.paypal.com/donate/?hosted_button_id=4MAYRTRRMQYE2"));
            } catch (IOException | URISyntaxException | UnsupportedOperationException ex) {
                // Ignore browser launch error
            }
        });
        donatePanel.add(donateLabel);
        donatePanel.add(donateButton);

        // Right side dialog actions
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
            // Immediately cancel any pending/running preview tasks when applying changes to the home
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

    /** Callback executed when Outside vs. Inside placement mode changes. */
    private void modeChanged() {
        syncTargetSideDefaults();
        settingsChanged();
        updateSidesSummary();
    }

    /**
     * Callback executed when topological merging toggles change (touching closed sets or collinear sides).
     * Collects previously excluded side midpoints and re-maps them to the newly partitioned sides.
     */
    private void topologyOptionChanged() {
        // Capture the midpoints of all currently user-excluded sides so we can preserve them across topology changes!
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
    }

    /**
     * Collects the 2D midpoints of every sub-segment belonging to a user-excluded (non-forbidden) side.
     *
     * @param prevMergeCollinear whether collinear merge was active when sides were configured
     * @return list of [x, y] midpoint coordinates
     */
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
                    boolean wasForbidden = i < ends.forbidden.length && ends.forbidden[i];
                    if (ends.skipped[i] && !wasForbidden) {
                        float[][] poly = polys.get(i);
                        for (int k = 0; k + 1 < poly.length; k++) {
                            midpoints.add(new double[]{
                                0.5 * (poly[k][0] + poly[k + 1][0]),
                                0.5 * (poly[k][1] + poly[k + 1][1])
                            });
                        }
                    }
                }
            }
        }
        return midpoints;
    }

    /**
     * Re-applies user exclusions to any side whose sub-segments lie on the previously excluded midpoints.
     *
     * @param excludedMidpoints list of [x, y] coordinates
     */
    private void restoreExcludedSidesByMidpoints(List<double[]> excludedMidpoints) {
        if (excludedMidpoints == null || excludedMidpoints.isEmpty()) {
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
                        double mx = 0.5 * (poly[k][0] + poly[k + 1][0]);
                        double my = 0.5 * (poly[k][1] + poly[k + 1][1]);
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

    /** Preserves configured side end settings for geometric faces that remained unchanged across a topology rebuild. */
    private void keepSettingsOfUnchangedFaces(List<SurroundTarget> previousTargets) {
        List<Room> previousFaces = new ArrayList<>();
        for (SurroundTarget target : previousTargets) {
            previousFaces.addAll(target.getFaces(false));
            previousFaces.addAll(target.getFaces(true));
        }
        for (SurroundTarget target : targets) {
            for (boolean interior : new boolean[]{false, true}) {
                for (Room face : target.getFaces(interior)) {
                    if (sideEnds.containsKey(face)) {
                        continue;
                    }
                    for (Room previous : previousFaces) {
                        SurroundConfig.SideEnds ends = sideEnds.get(previous);
                        if (ends != null && sameGeometry(previous, face)) {
                            sideEnds.put(face, ends.copy());
                            break;
                        }
                    }
                }
            }
        }
    }

    /**
     * Tests whether two Room polygons possess identical vertex geometry under cyclic permutation.
     *
     * @return true if geometries match within 0.01 cm tolerance
     */
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

    /** Synchronizes default side parameters, forbidden masks, and contact areas across all active target faces. */
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

    /** Callback executed when the selected surrounding type changes in the card strip. */
    private void typeChanged() {
        int selectedType = typePanel.getSelectedType();
        boolean type7 = (selectedType == 7);
        optionsPanel.setSelectedType(selectedType);
        if (btnChooseStartPoint != null) {
            btnChooseStartPoint.setVisible(type7);
        }
        type7Section.setVisible(type7);
        type7Section.revalidate();
        type7Section.repaint();
        settingsChanged();
    }

    /** Triggers debounced recalculation of the live preview whenever any option is modified. */
    private void settingsChanged() {
        updateUniformSection();
        if (showPreviewCheckBox != null && !showPreviewCheckBox.isSelected()) {
            return; // Do not compute or apply preview updates when live preview is disabled
        }
        // Cancel any currently running background engine so new changes supersede it immediately
        SurroundGeometryEngine running = runningPreviewEngine;
        if (running != null) {
            running.cancel();
        }
        previewTimer.setRepeats(false);
        previewTimer.restart();
    }

    /** Cancels all pending timers and in-flight background preview calculations. */
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

    /**
     * Shows or hides a full-window translucent GlassPane with the centered "Processing..." legend
     * while the final Surround operation is computing or committing to Sweet Home 3D.
     */
    public void setProcessingOverlay(boolean active) {
        if (!active) {
            getGlassPane().setVisible(false);
            return;
        }
        final String msg = texts.get("surroundProcessing", "Procesando…");
        JComponent glass = new JComponent() {
            @Override
            protected void paintComponent(java.awt.Graphics g) {
                java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
                g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(new java.awt.Color(255, 255, 255, 110));
                g2.fillRect(0, 0, getWidth(), getHeight());
                OutlinePreview.drawLoadingOverlay(g2, getWidth(), getHeight(), msg);
                g2.dispose();
            }
        };
        glass.setOpaque(false);
        glass.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.WAIT_CURSOR));
        glass.addMouseListener(new java.awt.event.MouseAdapter() {});
        setGlassPane(glass);
        glass.setVisible(true);
        glass.repaint();
    }

    @Override
    public void dispose() {
        cancelAllPreviewWork();
        super.dispose();
    }

    /**
     * Asynchronously recomputes placed furniture for the first vertical tier and updates the 2D vector preview canvas.
     * If a computation takes longer than 180 ms, displays "Cargando..." centered on the plan while keeping the UI responsive.
     * Newer changes automatically cancel and supersede earlier runs.
     */
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
        final com.eteks.sweethome3d.model.HomePieceOfFurniture primary = SurroundGeometryEngine.asSingleTemplate(selectedFurniture);
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
                List<com.eteks.sweethome3d.model.HomePieceOfFurniture> placed = engine.previewFirstLevel(config);
                SwingUtilities.invokeLater(() -> {
                    if (seq != previewSeq) {
                        return;
                    }
                    loadingBadgeTimer.stop();
                    runningPreviewEngine = null;
                    outlinePreview.show(faces, config, placed, engine::isCornerPiece, bWidth, bDepth);
                });
            } catch (CancellationException ignored) {
                // Superseded by newer change or Surround accept button
            } catch (RuntimeException ex) {
                SwingUtilities.invokeLater(() -> {
                    if (seq != previewSeq) {
                        return;
                    }
                    loadingBadgeTimer.stop();
                    runningPreviewEngine = null;
                    outlinePreview.show(faces, config, Collections.emptyList(), p -> false, bWidth, bDepth);
                });
            }
        }, "SurroundPreviewWorker-" + seq);
        worker.setDaemon(true);
        worker.start();
    }

    /** Synchronizes visibility of the uniform size and gaps section depending on the active surrounding type. */
    private void updateUniformSection() {
        int sel = typePanel.getSelectedType();
        boolean size = sel == 3 || sel == 4;
        boolean gaps = sel == 1 || sel == 2;
        uniformPanel.showParts(size, gaps);
        optionsPanel.setUniformState(size && uniformPanel.sameSizeCheckBox.isSelected(), gaps && uniformPanel.sameGapsCheckBox.isSelected());
        if (uniformSection.isVisible() != (size || gaps)) {
            uniformSection.setVisible(size || gaps);
            uniformSection.revalidate();
            uniformSection.repaint();
        }
    }

    /** Resolves the list of active Room perimeter faces corresponding to the current interior/exterior mode. */
    List<Room> currentFaces() {
        List<Room> faces = new ArrayList<>();
        boolean interior = modePanel.isInterior();
        for (SurroundTarget target : targets) {
            faces.addAll(target.getFaces(interior));
        }
        return faces;
    }

    /** Resolves user-friendly display names for all active perimeter faces. */
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

    /** Identifies all perimeter faces that must always remain in exterior mode (e.g. open wall groups). */
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

    /** Updates the summary label in the preview toolbar displaying excluded sides and stretches. */
    private void updateSidesSummary() {
        boolean mergeCollinear = modePanel.mergeCollinearCheckBox.isSelected();
        int skipped = 0;
        for (Room face : currentFaces()) {
            SurroundConfig.SideEnds ends = sideEnds.get(face);
            if (ends != null && ends.skipped.length == SideModel.count(face, mergeCollinear)) {
                skipped += ends.skippedCount();
            }
        }
        int customSegs = 0;
        if (enableExcludedSegments) {
            for (Room face : currentFaces()) {
                SurroundConfig.SideEnds ends = sideEnds.get(face);
                if (ends != null) {
                    for (List<double[]> list : ends.excludedSegments) {
                        customSegs += list.size();
                    }
                }
            }
        }
        String base;
        if (skipped == 0 && customSegs == 0) {
            base = texts.get("sidesAll", "All sides");
        } else if (skipped > 0 && customSegs == 0) {
            base = MessageFormat.format(texts.get("sidesSkippedCount", "{0} sides excluded"), skipped);
        } else if (skipped == 0 && customSegs > 0) {
            base = MessageFormat.format(texts.get("stretchesExcludedCount", "{0} stretches excluded"), customSegs);
        } else {
            base = MessageFormat.format(texts.get("sidesSkippedCount", "{0} sides excluded"), skipped)
                    + " \u2022 "
                    + MessageFormat.format(texts.get("stretchesExcludedCount", "{0} stretches excluded"), customSegs);
        }
        if (previewSidesSummary != null) {
            previewSidesSummary.setText(base);
        }
    }

    /** Returns {@code true} if any target room has user-configured excluded stretches. */
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

    /**
     * Activates the embedded side, stretch, or Type 7 start-point editing mode directly inside the bottom preview panel.
     *
     * @param dialogMode {@link SideEndsPanel#DIALOG_SIDES}, {@link SideEndsPanel#DIALOG_SEGMENTS}, or {@link SideEndsPanel#DIALOG_START_POINT}
     */
    private void startEmbeddedSideEditing(int dialogMode) {
        syncTargetSideDefaults();
        boolean mergeCollinear = modePanel.mergeCollinearCheckBox.isSelected();
        boolean cornerHandlingActive = optionsPanel.enableCornerHandlingCheckBox.isSelected();
        sideEndsPanel.setLivePreviewEnabled(showPreviewCheckBox == null || showPreviewCheckBox.isSelected());
        sideEndsPanel.startEditing(currentFaces(), currentFaceNames(), sideEnds,
                modePanel.isInterior(), alwaysExteriorFaces(), dialogMode, mergeCollinear,
                defaultHalfObjectLength, cornerHandlingActive);
        sideEndsPanel.setPreviewContext(home, selectedFurniture, buildConfig(), homeObstacles, objectDepth);
        editorButtons.setVisible(false);
        previewCardLayout.show(previewCardPanel, "EDIT");
        previewCardPanel.revalidate();
        previewCardPanel.repaint();
        if (leftColumn != null) {
            leftColumn.revalidate();
            leftColumn.repaint();
        }
    }

    /** Restores the bottom preview container to the normal live preview view. */
    private void showNormalPreview() {
        editorButtons.setVisible(true);
        previewCardLayout.show(previewCardPanel, "NORMAL");
        previewCardPanel.revalidate();
        previewCardPanel.repaint();
        if (leftColumn != null) {
            leftColumn.revalidate();
            leftColumn.repaint();
        }
    }


    /** Collects all active template pieces, including custom catalog corner pieces if configured. */
    private List<com.eteks.sweethome3d.model.HomePieceOfFurniture> collectActiveTemplates() {
        List<com.eteks.sweethome3d.model.HomePieceOfFurniture> templates = new ArrayList<>(selectedFurniture);
        if (optionsPanel.cornerPlaceOrthogonalRadio.isSelected()
                && optionsPanel.useCatalogCornerCheckBox.isSelected()
                && optionsPanel.catalogPieceCombo.getSelectedItem() instanceof com.eteks.sweethome3d.model.CatalogPieceOfFurniture) {
            com.eteks.sweethome3d.model.HomePieceOfFurniture cornerPiece = ObjectPicker.createHomePiece(
                    (com.eteks.sweethome3d.model.CatalogPieceOfFurniture) optionsPanel.catalogPieceCombo.getSelectedItem(),
                    homeController);
            if (cornerPiece != null) {
                templates.add(cornerPiece);
            }
        }
        return templates;
    }

    /** Creates an instantaneous snapshot of current UI control settings as a {@link SurroundConfig}. */
    private SurroundConfig currentSnapshotConfig() {
        SurroundConfig snap = new SurroundConfig();
        snap.interior = modePanel.isInterior();
        snap.surroundType = typePanel.getSelectedType();
        snap.mergeCollinearSides = modePanel.mergeCollinearCheckBox.isSelected();
        snap.mergeTouchingClosedSets = modePanel.mergeTouchingClosedSetsCheckBox.isVisible()
                && modePanel.mergeTouchingClosedSetsCheckBox.isSelected();
        optionsPanel.fill(snap);
        snap.enableExcludedSegments = this.enableExcludedSegments && hasAnyExcludedSegments();
        type7Panel.fill(snap);
        uniformPanel.fill(snap);
        for (Map.Entry<Room, double[]> e : type7StartPoints.entrySet()) {
            if (e.getValue() != null) {
                snap.type7StartPoints.put(e.getKey(), e.getValue().clone());
            }
        }
        return snap;
    }

    /**
     * Displays either the random or gradual property variation configuration dialog.
     *
     * @param gradual true for gradual progression dialog, false for random stochastic dialog
     */
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

    /** Injects template furniture and parent context into this configuration window. */
    public void setTemplateContext(com.eteks.sweethome3d.model.Home home,
            List<com.eteks.sweethome3d.model.HomePieceOfFurniture> selectedFurniture,
            com.eteks.sweethome3d.viewcontroller.HomeController homeController) {
        this.home = home;
        this.selectedFurniture = selectedFurniture != null
                ? new ArrayList<>(selectedFurniture)
                : new ArrayList<com.eteks.sweethome3d.model.HomePieceOfFurniture>();
        this.homeController = homeController;
        settingsChanged();
    }

    /** Builds and validates the complete {@link SurroundConfig} DTO from all UI panels and sub-dialog states. */
    SurroundConfig buildConfig() {
        syncTargetSideDefaults();
        SurroundConfig cfg = currentSnapshotConfig();
        boolean mergeCollinear = cfg.mergeCollinearSides;
        boolean cornerHandlingActive = cfg.enableCornerHandling || cfg.customSideEnds;
        for (Map.Entry<Room, SurroundConfig.SideEnds> entry : sideEnds.entrySet()) {
            Room face = entry.getKey();
            SurroundConfig.SideEnds copy = entry.getValue().copy();
            if (cfg.enableExcludedSegments) {
                copy.initDefaultExcludedSegments(SideModel.polylines(face, mergeCollinear), defaultHalfObjectLength, cornerHandlingActive);
            }
            cfg.sideEnds.put(face, copy);
        }
        cfg.alwaysExterior.addAll(alwaysExteriorFaces());
        for (SurroundTarget target : targets) {
            if (target.getKind() == SurroundTarget.ROOM) {
                cfg.faceSourceRooms.put(target.getRoom(), target.getSourceRooms());
            }
        }
        return cfg;
    }

    /**
     * Injects obstacle furniture pieces from the home for collision avoidance.
     *
     * @param objectDepth template piece depth in cm
     */
    public void setHomeObstacles(List<com.eteks.sweethome3d.model.HomePieceOfFurniture> obstacles, double objectDepth) {
        this.homeObstacles = obstacles != null ? obstacles : new ArrayList<com.eteks.sweethome3d.model.HomePieceOfFurniture>();
        this.objectDepth = objectDepth;
    }

    /** Returns an unmodifiable view of active target entities. */
    public List<SurroundTarget> getActiveTargets() {
        return Collections.unmodifiableList(targets);
    }

    /** CardLayout subclass that calculates preferred and minimum layout sizes based on the currently visible card. */
    private static class DynamicCardLayout extends CardLayout {
        @Override
        public Dimension preferredLayoutSize(Container parent) {
            for (Component comp : parent.getComponents()) {
                if (comp.isVisible()) {
                    Insets insets = parent.getInsets();
                    Dimension d = comp.getPreferredSize();
                    return new Dimension(d.width + insets.left + insets.right,
                                         d.height + insets.top + insets.bottom);
                }
            }
            return super.preferredLayoutSize(parent);
        }

        @Override
        public Dimension minimumLayoutSize(Container parent) {
            for (Component comp : parent.getComponents()) {
                if (comp.isVisible()) {
                    Insets insets = parent.getInsets();
                    Dimension d = comp.getMinimumSize();
                    return new Dimension(d.width + insets.left + insets.right,
                                         d.height + insets.top + insets.bottom);
                }
            }
            return super.minimumLayoutSize(parent);
        }
    }
}
