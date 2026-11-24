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
 * Interactive 2D plan view of the surrounded faces and first-level placed objects (zoom, pan, double-click reset).
 * {@link SideEndsPanel} extends it to reuse fitted view, camera controls, floors, objects and loading badge.
 */
class OutlinePreview extends JPanel {

    static final Color WALL = new Color(55, 62, 72);
    static final Color FLOOR = new Color(243, 240, 233);
    static final Color EXCLUDED_FLOOR = new Color(235, 237, 241);
    static final Color EXCLUDED = new Color(210, 62, 62);
    private static final Color SKIPPED = new Color(180, 185, 194);
    private static final Color PIECE_FILL = new Color(72, 133, 232, 185);
    private static final Color PIECE_BORDER = new Color(28, 72, 154, 220);
    private static final Color RESIZED_FILL = new Color(130, 175, 250, 185);
    private static final Color RESIZED_BORDER = new Color(55, 104, 192, 220);
    private static final Color CORNER_FILL = new Color(240, 138, 35, 205);
    private static final Color CORNER_BORDER = new Color(185, 95, 12, 230);

    private static final BasicStroke SIDE_STROKE = new BasicStroke(2.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
    private static final BasicStroke SKIPPED_STROKE = new BasicStroke(1.6f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10f, new float[]{5f, 4f}, 0f);
    private static final BasicStroke EXCLUDED_STROKE = new BasicStroke(3.2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND);
    private static final BasicStroke PIECE_STROKE = new BasicStroke(1.1f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);

    private static final double PAD = 16;
    /** Pixels before a press becomes a drag so a shaky click still registers as a click. */
    private static final int DRAG_THRESHOLD = 4;

    List<Room> faces = Collections.emptyList();
    SurroundConfig config = new SurroundConfig();
    List<HomePieceOfFurniture> placed = Collections.emptyList();
    Predicate<HomePieceOfFurniture> isCorner = p -> false;
    double baseWidth, baseDepth;
    /** World (cm) to screen (px) transform from the last paint, reused for hit tests. */
    AffineTransform view = new AffineTransform();

    private boolean loading;
    private String loadingText = "Cargando\u2026";
    private double zoomFactor = 1.0, panX, panY;
    private Point dragPoint;
    private boolean dragged, customDrag;

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
                dragged = false;
                customDrag = onMousePressed(e);
                dragPoint = e.getPoint();
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                dragPoint = null;
                if (customDrag) {
                    customDrag = false;
                    onMouseReleased(e);
                }
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (customDrag) {
                    if (dragPoint != null && Math.hypot(e.getX() - dragPoint.x, e.getY() - dragPoint.y) > DRAG_THRESHOLD) {
                        dragged = true;
                    }
                    onMouseDragged(e);
                    return;
                }
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

    /** Subclass hook returning true to claim the drag gesture instead of panning the canvas. */
    boolean onMousePressed(MouseEvent e) {
        return false;
    }

    void onMouseDragged(MouseEvent e) {
    }

    void onMouseReleased(MouseEvent e) {
    }

    double viewPadding() {
        return PAD;
    }

    void clicked(MouseEvent e) {
        if (e.getClickCount() == 2) {
            resetZoom();
        }
    }

    void zoomAt(double px, double py, double factor) {
        double nextZoom = Math.max(0.25, Math.min(25.0, zoomFactor * factor));
        double actualFactor = nextZoom / zoomFactor;
        if (Math.abs(actualFactor - 1.0) < 1e-4) {
            return;
        }
        double cx = getWidth() / 2.0 + panX, cy = getHeight() / 2.0 + panY;
        panX = (px - getWidth() / 2.0) - (px - cx) * actualFactor;
        panY = (py - getHeight() / 2.0) - (py - cy) * actualFactor;
        zoomFactor = nextZoom;
        repaint();
    }

    void zoomIn() {
        zoomAt(getWidth() / 2.0, getHeight() / 2.0, 1.25);
    }

    void zoomOut() {
        zoomAt(getWidth() / 2.0, getHeight() / 2.0, 1.0 / 1.25);
    }

    void resetZoom() {
        zoomFactor = 1.0;
        panX = 0.0;
        panY = 0.0;
        repaint();
    }

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

    void setLoading(boolean loading, String text) {
        if (text != null && !text.isEmpty()) {
            this.loadingText = text;
        }
        if (this.loading != loading) {
            this.loading = loading;
            repaint();
        }
    }

    boolean isRoomExcluded(Room face) {
        return config != null && config.excludedRooms != null && config.excludedRooms.contains(face);
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        if (fitView()) {
            for (Room face : faces) {
                g2.setColor(isRoomExcluded(face) ? EXCLUDED_FLOOR : FLOOR);
                g2.fill(path(face.getPoints(), true));
            }
            paintPlan(g2);
        }
        if (loading) {
            drawLoadingOverlay(g2, getWidth(), getHeight(), loadingText);
        }
        g2.dispose();
    }

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
        double pad = viewPadding();
        double scale = zoomFactor * Math.min((getWidth() - 2 * pad) / Math.max(1, maxX - minX),
                (getHeight() - 2 * pad) / Math.max(1, maxY - minY));
        view.setTransform(scale, 0, 0, scale, getWidth() / 2.0 + panX - (minX + maxX) / 2.0 * scale,
                getHeight() / 2.0 + panY - (minY + maxY) / 2.0 * scale);
        return true;
    }

    void paintPlan(Graphics2D g2) {
        for (Room face : faces) {
            boolean roomExcluded = isRoomExcluded(face);
            List<float[][]> sides = SideModel.polylines(face, config.mergeCollinearSides);
            SurroundConfig.SideEnds ends = config.sideEnds.get(face);
            for (int i = 0; i < sides.size(); i++) {
                boolean skipped = roomExcluded || (ends != null && i < ends.skipped.length && ends.skipped[i]);
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

    void drawStretch(Graphics2D g2, float[][] poly, double d0, double d1, Color color, BasicStroke stroke) {
        if (d1 <= d0 + 1e-4 || poly == null || poly.length < 2) {
            return;
        }
        SidePath side = new SidePath(poly, 0, false);
        double s0 = Math.max(0.0, Math.min(side.length, d0)), s1 = Math.max(0.0, Math.min(side.length, d1));
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

    static void drawLoadingOverlay(Graphics2D g2, int width, int height, String text) {
        g2.setFont(g2.getFont().deriveFont(Font.BOLD, 13f));
        FontMetrics fm = g2.getFontMetrics();
        int bw = fm.stringWidth(text) + 32, bh = fm.getAscent() + 16;
        int bx = (width - bw) / 2, by = (height - bh) / 2;
        g2.setColor(new Color(255, 255, 255, 230));
        g2.fill(new RoundRectangle2D.Double(bx, by, bw, bh, 12, 12));
        g2.setColor(UiKit.ACCENT);
        g2.setStroke(new BasicStroke(1.5f));
        g2.draw(new RoundRectangle2D.Double(bx, by, bw, bh, 12, 12));
        g2.setColor(WALL);
        g2.drawString(text, bx + 16, by + 6 + fm.getAscent());
    }
}
