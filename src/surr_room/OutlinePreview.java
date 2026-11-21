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
import com.eteks.sweethome3d.model.Room;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;
import javax.swing.BorderFactory;
import javax.swing.JPanel;

/**
 * Plan view of the surrounded faces under the type cards, with the objects of the first level where the
 * current settings place them: floors in beige, sides in dark gray (skipped ones dashed), excluded
 * stretches in red, objects in blue (light blue when resized) and corner pieces in orange.
 * Supports mouse-wheel zooming, click-and-drag panning, double-click reset, and +/-/1:1 toolbar buttons.
 * The side editors (SideEndsPanel) extend it to reuse the fitted view, zoom, pan, floors, objects and
 * loading badge, and only replace the drawing of the sides ({@link #paintPlan}) and the clicks ({@link #clicked}).
 */
class OutlinePreview extends JPanel {

    /** Charcoal color for surrounded wall perimeters. */
    static final Color WALL = new Color(55, 62, 72);

    /** Soft warm beige color for room floor polygons. */
    static final Color FLOOR = new Color(243, 240, 233);

    /** Vivid red highlight color for user-excluded sub-stretches. */
    static final Color EXCLUDED = new Color(210, 62, 62);

    /** Subdued gray color for skipped sides. */
    private static final Color SKIPPED = new Color(180, 185, 194);

    /** Semi-transparent blue fill for standard placed furniture pieces. */
    private static final Color PIECE_FILL = new Color(72, 133, 232, 185);

    /** Darker blue border for standard placed pieces. */
    private static final Color PIECE_BORDER = new Color(28, 72, 154, 220);

    /** Semi-transparent sky blue fill for pieces scaled to fit. */
    private static final Color RESIZED_FILL = new Color(130, 175, 250, 185);

    /** Darker sky blue border for scaled pieces. */
    private static final Color RESIZED_BORDER = new Color(55, 104, 192, 220);

    /** Semi-transparent amber/orange fill for corner pieces. */
    private static final Color CORNER_FILL = new Color(240, 138, 35, 205);

    /** Deep orange border for corner pieces. */
    private static final Color CORNER_BORDER = new Color(185, 95, 12, 230);

    /** Strokes created once: sides, skipped sides (dashed), excluded stretches and object outlines. */
    private static final BasicStroke SIDE_STROKE = new BasicStroke(2.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
    private static final BasicStroke SKIPPED_STROKE = new BasicStroke(1.6f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10f, new float[]{5f, 4f}, 0f);
    private static final BasicStroke EXCLUDED_STROKE = new BasicStroke(3.2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND);
    private static final BasicStroke PIECE_STROKE = new BasicStroke(1.1f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);

    /** Viewport padding margin in pixels around the bounding box. */
    private static final double PAD = 16;

    /** Pixels the mouse must move before a press becomes a pan, so a slightly shaky click still counts as a click. */
    private static final int DRAG_THRESHOLD = 4;

    /** Faces, settings and objects drawn (package access so the side editors can set them before painting). */
    List<Room> faces = Collections.emptyList();
    SurroundConfig config = new SurroundConfig();
    List<HomePieceOfFurniture> placed = Collections.emptyList();

    /** Predicate identifying whether a placed piece is a corner piece. */
    Predicate<HomePieceOfFurniture> isCorner = p -> false;

    /** Baseline width and depth of the template piece, used to detect scaling. */
    double baseWidth, baseDepth;

    /** World (cm) to screen (pixels) transform of the last paint, also used for hit tests. */
    AffineTransform view = new AffineTransform();

    /** Whether an asynchronous preview computation is currently taking a noticeable time. */
    private boolean loading = false;

    /** Localized text displayed in the center of the plan while loading. */
    private String loadingText = "Cargando…";

    /** User zoom factor (1.0 = fit to panel), pan offset in pixels and drag state. */
    private double zoomFactor = 1.0;
    private double panX = 0.0, panY = 0.0;
    private Point dragPoint;
    private boolean dragged;

    /**
     * Constructs an OutlinePreview panel with white background, interactive zoom/pan mouse listeners,
     * and standardized preferred dimensions.
     */
    OutlinePreview() {
        setOpaque(true);
        setBackground(Color.WHITE);
        setBorder(BorderFactory.createLineBorder(UiKit.CARD_BORDER));
        setPreferredSize(new Dimension(UiKit.CARD_WIDTH * 4 + UiKit.GAP * 3, 250));
        setMinimumSize(getPreferredSize());

        MouseAdapter zoomPanHandler = new MouseAdapter() {
            @Override
            public void mouseWheelMoved(MouseWheelEvent e) {
                zoomAt(e.getX(), e.getY(), Math.pow(1.18, -e.getPreciseWheelRotation()));
            }

            @Override
            public void mousePressed(MouseEvent e) {
                dragPoint = e.getPoint();
                dragged = false;
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                dragPoint = null;
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (dragPoint == null) {
                    return;
                }
                int dx = e.getX() - dragPoint.x, dy = e.getY() - dragPoint.y;
                if (dragged || Math.hypot(dx, dy) > DRAG_THRESHOLD) {
                    dragged = true;
                    panX += dx;
                    panY += dy;
                    dragPoint = e.getPoint();
                    repaint();
                }
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                if (!dragged) {
                    clicked(e);
                }
            }
        };
        addMouseWheelListener(zoomPanHandler);
        addMouseListener(zoomPanHandler);
        addMouseMotionListener(zoomPanHandler);
    }

    /** Click on the plan that wasn't a pan: a double click restores the fitted view. */
    void clicked(MouseEvent e) {
        if (e.getClickCount() == 2) {
            resetZoom();
        }
    }

    /** Zooms in or out centered on the specified pixel coordinates. */
    void zoomAt(double px, double py, double factor) {
        double nextZoom = Math.max(0.25, Math.min(25.0, zoomFactor * factor));
        double actualFactor = nextZoom / zoomFactor;
        if (Math.abs(actualFactor - 1.0) < 1e-4) {
            return;
        }
        double cx = getWidth() / 2.0 + panX;
        double cy = getHeight() / 2.0 + panY;
        panX = (px - getWidth() / 2.0) - (px - cx) * actualFactor;
        panY = (py - getHeight() / 2.0) - (py - cy) * actualFactor;
        zoomFactor = nextZoom;
        repaint();
    }

    /** Zooms in centered on the viewport. */
    void zoomIn() {
        zoomAt(getWidth() / 2.0, getHeight() / 2.0, 1.25);
    }

    /** Zooms out centered on the viewport. */
    void zoomOut() {
        zoomAt(getWidth() / 2.0, getHeight() / 2.0, 1.0 / 1.25);
    }

    /** Resets zoom and pan to fit the entire scene inside the viewport. */
    void resetZoom() {
        zoomFactor = 1.0;
        panX = 0.0;
        panY = 0.0;
        repaint();
    }

    /** Updates the preview model data and triggers a repaint. */
    void show(List<Room> faces, SurroundConfig config, List<HomePieceOfFurniture> placed,
            Predicate<HomePieceOfFurniture> isCorner, double baseWidth, double baseDepth) {
        this.faces = faces;
        this.config = config;
        this.placed = placed;
        this.isCorner = isCorner;
        this.baseWidth = baseWidth;
        this.baseDepth = baseDepth;
        this.loading = false;
        repaint();
    }

    /** Sets whether the centered "Loading..." badge is displayed over the plan. */
    void setLoading(boolean loading, String text) {
        if (text != null && !text.isEmpty()) {
            this.loadingText = text;
        }
        if (this.loading != loading) {
            this.loading = loading;
            repaint();
        }
    }

    /** Paints the floors, then {@link #paintPlan} over them, then the loading badge if needed. */
    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        if (fitView()) {
            g2.setColor(FLOOR);
            for (Room face : faces) {
                g2.fill(path(face.getPoints(), true));
            }
            paintPlan(g2);
        }
        if (loading) {
            drawLoadingOverlay(g2, getWidth(), getHeight(), loadingText);
        }
        g2.dispose();
    }

    /**
     * Updates {@link #view} so the faces and objects fit the panel with equal aspect ratio, plus the
     * user zoom and pan. Returns false when there is nothing to draw.
     */
    private boolean fitView() {
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (int k = 0; k < faces.size() + placed.size(); k++) {
            float[][] points = k < faces.size() ? faces.get(k).getPoints() : placed.get(k - faces.size()).getPoints();
            for (float[] p : points) {
                minX = Math.min(minX, p[0]);
                minY = Math.min(minY, p[1]);
                maxX = Math.max(maxX, p[0]);
                maxY = Math.max(maxY, p[1]);
            }
        }
        if (minX > maxX) {
            return false;
        }
        double scale = zoomFactor * Math.min((getWidth() - 2 * PAD) / Math.max(1, maxX - minX),
                (getHeight() - 2 * PAD) / Math.max(1, maxY - minY));
        view.setTransform(scale, 0, 0, scale, getWidth() / 2.0 + panX - (minX + maxX) / 2.0 * scale,
                getHeight() / 2.0 + panY - (minY + maxY) / 2.0 * scale);
        return true;
    }

    /** Draws over the floors: the sides (skipped ones dashed, excluded stretches in red) and the objects. */
    void paintPlan(Graphics2D g2) {
        for (Room face : faces) {
            List<float[][]> sides = SideModel.polylines(face, config.mergeCollinearSides);
            SurroundConfig.SideEnds ends = config.sideEnds.get(face);
            for (int i = 0; i < sides.size(); i++) {
                boolean skipped = ends != null && i < ends.skipped.length && ends.skipped[i];
                g2.setColor(skipped ? SKIPPED : WALL);
                g2.setStroke(skipped ? SKIPPED_STROKE : SIDE_STROKE);
                g2.draw(path(sides.get(i), false));
                if (!skipped && config.enableExcludedSegments && ends != null && i < ends.excludedSegments.size()) {
                    for (double[] range : ends.excludedSegments.get(i)) {
                        drawStretch(g2, sides.get(i), range[0], range[1], EXCLUDED, EXCLUDED_STROKE);
                    }
                }
            }
        }
        paintPieces(g2);
    }

    /** Draws the objects: corner pieces in amber, resized pieces in light blue, the others in blue. */
    void paintPieces(Graphics2D g2) {
        g2.setStroke(PIECE_STROKE);
        for (HomePieceOfFurniture piece : placed) {
            boolean corner = isCorner.test(piece);
            boolean resized = Math.abs(piece.getWidth() - baseWidth) > 0.25 || Math.abs(piece.getDepth() - baseDepth) > 0.25;
            Path2D.Double outline = path(piece.getPoints(), true);
            g2.setColor(corner ? CORNER_FILL : resized ? RESIZED_FILL : PIECE_FILL);
            g2.fill(outline);
            g2.setColor(corner ? CORNER_BORDER : resized ? RESIZED_BORDER : PIECE_BORDER);
            g2.draw(outline);
        }
    }

    /** Draws the part of a side between the distances d0 and d1 (cm), if not empty. */
    void drawStretch(Graphics2D g2, float[][] poly, double d0, double d1, Color color, BasicStroke stroke) {
        if (d1 <= d0 + 1e-4 || poly == null || poly.length < 2) {
            return;
        }
        SidePath side = new SidePath(poly, 0, false);
        double s0 = Math.max(0.0, Math.min(side.length, d0));
        double s1 = Math.max(0.0, Math.min(side.length, d1));
        if (s1 <= s0 + 1e-4) {
            return;
        }
        Path2D.Double path = new Path2D.Double();
        double[] start = side.pointAt(s0);
        path.moveTo(start[0], start[1]);
        for (int k = 1; k < side.cumulative.length - 1; k++) {
            double c = side.cumulative[k];
            if (c > s0 + 1e-4 && c < s1 - 1e-4) {
                path.lineTo(poly[k][0], poly[k][1]);
            }
        }
        double[] end = side.pointAt(s1);
        path.lineTo(end[0], end[1]);
        path.transform(view);
        g2.setColor(color);
        g2.setStroke(stroke);
        g2.draw(path);
    }

    /** Screen-space path of world points (cm) with the current {@link #view}. */
    Path2D.Double path(float[][] points, boolean closed) {
        Path2D.Double path = new Path2D.Double();
        for (int i = 0; i < points.length; i++) {
            if (i == 0) {
                path.moveTo(points[i][0], points[i][1]);
            } else {
                path.lineTo(points[i][0], points[i][1]);
            }
        }
        if (closed) {
            path.closePath();
        }
        path.transform(view);
        return path;
    }

    /** Renders a translucent pill badge with the localized "Loading..." legend in the center of the plan. */
    static void drawLoadingOverlay(Graphics2D g2, int width, int height, String text) {
        Font font = g2.getFont().deriveFont(Font.BOLD, 13f);
        g2.setFont(font);
        FontMetrics fm = g2.getFontMetrics();
        int tw = fm.stringWidth(text);
        int th = fm.getAscent();
        int padX = 16, padY = 8;
        int bw = tw + padX * 2, bh = th + padY * 2;
        int bx = (width - bw) / 2, by = (height - bh) / 2;
        g2.setColor(new Color(255, 255, 255, 230));
        g2.fill(new RoundRectangle2D.Double(bx, by, bw, bh, 12, 12));
        g2.setColor(UiKit.ACCENT);
        g2.setStroke(new BasicStroke(1.5f));
        g2.draw(new RoundRectangle2D.Double(bx, by, bw, bh, 12, 12));
        g2.setColor(WALL);
        g2.drawString(text, bx + padX, by + padY + th - 2);
    }
}
