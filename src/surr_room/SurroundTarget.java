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
import com.eteks.sweethome3d.model.Wall;
import java.awt.BasicStroke;
import java.awt.geom.Area;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Surroundable target: either a room floor or a cluster of touching walls. Closed wall sets expose
 * outer and inner cavity faces, partition walls are excluded from exterior mode, and touching closed
 * sets can optionally be fused.
 */
public class SurroundTarget {

    public static final int ROOM = 0;
    public static final int WALLS = 1;

    private static final double TOUCH_TOLERANCE = 1.5;
    /** Dilation stroke (cm) bridging tiny coordinate gaps when fusing touching walls. */
    static final double FUSE_DILATION = 0.25;
    private static final double MIN_HOLE_WIDTH = 4.0;
    private static final double PROBE_NEAR = 0.3, PROBE_FAR = 1.5;

    private final int kind;
    private int number;
    private final Room room;
    private final Room outerFace;
    private final List<Room> innerFaces;
    private final boolean closed;
    private List<Wall> walls = Collections.emptyList();
    private List<Wall> perimeterWalls = Collections.emptyList();
    private List<Wall> interiorWalls = Collections.emptyList();
    /** Per-face cache indexed by (mergeCollinear, interior): 0..3 overlap lists, 4..7 interior-wall side masks. */
    private final Map<Room, Object[]> faceCache = new HashMap<>();
    private List<Room> sourceRooms = Collections.emptyList();

    private SurroundTarget(int kind, int number, Room room, Room outerFace, List<Room> innerFaces, boolean closed) {
        this.kind = kind;
        this.number = number;
        this.room = room;
        this.outerFace = outerFace;
        this.innerFaces = innerFaces;
        this.closed = closed;
    }

    public static SurroundTarget forRoom(Room room, int number) {
        SurroundTarget target = new SurroundTarget(ROOM, number, room, null, Collections.<Room>emptyList(), true);
        target.sourceRooms = Collections.singletonList(room);
        return target;
    }

    private static SurroundTarget forMergedRooms(Room unified, List<Room> cluster, int number) {
        SurroundTarget target = forRoom(unified, number);
        target.sourceRooms = new ArrayList<>(cluster);
        return target;
    }

    public int getKind() { return kind; }
    public int getNumber() { return number; }
    public Room getRoom() { return room; }
    public List<Room> getSourceRooms() { return sourceRooms; }
    public List<Wall> getWalls() { return walls; }
    public boolean isClosed() { return closed; }

    public List<Room> getFaces(boolean interior) {
        if (kind == ROOM) {
            return Collections.singletonList(room);
        }
        return (closed && interior) ? innerFaces : Collections.singletonList(outerFace);
    }

    public boolean isAlwaysExterior(Room face) {
        return kind == WALLS && !closed && face == outerFace;
    }

    public int getInnerFaceIndex(Room face) {
        int index = innerFaces.indexOf(face);
        return index >= 0 && innerFaces.size() > 1 ? index + 1 : 0;
    }

    /** Populates wall contact overlaps and marks internal partitions as forbidden in exterior mode. */
    public void configureSideDefaults(Room face, SurroundConfig.SideEnds ends, boolean interior,
            boolean mergeCollinear, Area contactWalls) {
        boolean faceInterior = interior && !isAlwaysExterior(face);
        List<List<double[]>> overlaps = getOverlapIntervals(face, mergeCollinear, faceInterior, contactWalls);
        for (int i = 0; i < ends.skipped.length && i < overlaps.size(); i++) {
            ends.overlapSegments.get(i).clear();
            for (double[] interval : overlaps.get(i)) {
                ends.overlapSegments.get(i).add(interval.clone());
            }
        }
        boolean[] interiorSides = kind == WALLS && closed && !interiorWalls.isEmpty()
                ? interiorWallSides(face, mergeCollinear, faceInterior) : new boolean[0];
        for (int i = 0; i < ends.skipped.length; i++) {
            boolean interiorSide = i < interiorSides.length && interiorSides[i];
            ends.forbidden[i] = interiorSide && !faceInterior;
            if (interiorSide && (!ends.sideDefaultsApplied || !faceInterior)) {
                ends.skipped[i] = true;
            }
        }
        ends.sideDefaultsApplied = true;
    }

    /** Probes a thin strip on the placement side of each segment against other walls to find blocked intervals. */
    private List<List<double[]>> getOverlapIntervals(Room face, boolean mergeCollinear, boolean faceInterior,
            Area contactWalls) {
        Object[] cache = faceCache.computeIfAbsent(face, f -> new Object[8]);
        int key = (mergeCollinear ? 1 : 0) + (faceInterior ? 2 : 0);
        if (cache[key] != null) {
            @SuppressWarnings("unchecked")
            List<List<double[]>> cached = (List<List<double[]>>) cache[key];
            return cached;
        }
        List<float[][]> polylines = SideModel.polylines(face, mergeCollinear);
        List<List<double[]>> result = new ArrayList<>(polylines.size());
        for (int i = 0; i < polylines.size(); i++) {
            result.add(new ArrayList<double[]>());
        }
        if (contactWalls != null && !contactWalls.isEmpty()) {
            double sign = SurroundGeometryEngine.isTraversalReversed(face, faceInterior) ? -1 : 1;
            Area otherWalls = new Area(contactWalls);
            for (Wall ownWall : this.walls) {
                otherWalls.subtract(new Area(toPath(ownWall.getPoints())));
            }
            for (int s = 0; s < polylines.size(); s++) {
                float[][] poly = polylines.get(s);
                double cumulative = 0;
                for (int k = 0; k + 1 < poly.length; k++) {
                    float[] a = poly[k], b = poly[k + 1];
                    double length = Math.hypot(b[0] - a[0], b[1] - a[1]);
                    if (length < 1e-4) {
                        continue;
                    }
                    double ux = (b[0] - a[0]) / length, uy = (b[1] - a[1]) / length;
                    double nx = sign * uy, ny = -sign * ux;
                    Path2D.Double probe = new Path2D.Double();
                    probe.moveTo(a[0] + nx * PROBE_NEAR, a[1] + ny * PROBE_NEAR);
                    probe.lineTo(b[0] + nx * PROBE_NEAR, b[1] + ny * PROBE_NEAR);
                    probe.lineTo(b[0] + nx * PROBE_FAR, b[1] + ny * PROBE_FAR);
                    probe.lineTo(a[0] + nx * PROBE_FAR, a[1] + ny * PROBE_FAR);
                    probe.closePath();
                    Area hit = new Area(probe);
                    hit.intersect(otherWalls);
                    if (!hit.isEmpty()) {
                        for (float[][] loop : loops(hit)) {
                            double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
                            for (float[] pt : loop) {
                                double t = (pt[0] - a[0]) * ux + (pt[1] - a[1]) * uy;
                                min = Math.min(min, t);
                                max = Math.max(max, t);
                            }
                            min = Math.max(0, min);
                            max = Math.min(length, max);
                            if (max - min > 1.0) {
                                result.get(s).add(new double[]{cumulative + min, cumulative + max});
                            }
                        }
                    }
                    cumulative += length;
                }
                mergeIntervals(result.get(s));
            }
        }
        cache[key] = result;
        return result;
    }

    public static Area wallsArea(List<SurroundTarget> targets) {
        Area area = new Area();
        for (SurroundTarget target : targets) {
            for (Wall wall : target.getWalls()) {
                area.add(new Area(toPath(wall.getPoints())));
            }
        }
        return area;
    }

    private boolean[] interiorWallSides(Room face, boolean mergeCollinear, boolean faceInterior) {
        Object[] cache = faceCache.computeIfAbsent(face, f -> new Object[8]);
        int key = 4 + (mergeCollinear ? 1 : 0) + (faceInterior ? 2 : 0);
        if (cache[key] != null) {
            return (boolean[]) cache[key];
        }
        List<float[][]> polylines = SideModel.polylines(face, mergeCollinear);
        boolean[] result = new boolean[polylines.size()];
        for (int i = 0; i < polylines.size(); i++) {
            SidePath sp = new SidePath(polylines.get(i), i, false);
            double[] middle = sp.pointAt(sp.length / 2.0);
            boolean nearInterior = false;
            for (Wall wall : interiorWalls) {
                if (distancePointToPolygon(middle[0], middle[1], wall.getPoints()) <= FUSE_DILATION + 1.0) {
                    nearInterior = true;
                    break;
                }
            }
            if (!nearInterior) {
                continue;
            }
            boolean onPerimeter = false;
            for (Wall wall : perimeterWalls) {
                if (distancePointToPolygon(middle[0], middle[1], wall.getPoints()) <= FUSE_DILATION + 0.5) {
                    onPerimeter = true;
                    break;
                }
            }
            result[i] = !onPerimeter;
        }
        cache[key] = result;
        return result;
    }

    static void mergeIntervals(List<double[]> intervals) {
        if (intervals.size() <= 1) {
            return;
        }
        intervals.sort((a, b) -> Double.compare(a[0], b[0]));
        List<double[]> merged = new ArrayList<>();
        double[] cur = intervals.get(0);
        for (int i = 1; i < intervals.size(); i++) {
            double[] next = intervals.get(i);
            if (next[0] <= cur[1] + 0.5) {
                cur[1] = Math.max(cur[1], next[1]);
            } else {
                merged.add(cur);
                cur = next;
            }
        }
        merged.add(cur);
        intervals.clear();
        intervals.addAll(merged);
    }

    private static double distancePointToPolygon(double px, double py, float[][] poly) {
        return toPath(poly).contains(px, py) ? 0.0 : distanceToOutline(px, py, poly);
    }

    static final class Shape {
        final float[][] outer;
        final List<float[][]> holes = new ArrayList<>();

        Shape(float[][] outer) {
            this.outer = outer;
        }
    }

    /**
     * Boolean union of polygons into shapes (largest first). Prefers a dilated union when it bridges
     * sub-millimeter gaps to merge more components or close real cavities without shrinking net area.
     */
    static List<Shape> fuse(List<float[][]> polygons) {
        Area exact = new Area();
        for (float[][] polygon : polygons) {
            exact.add(new Area(toPath(polygon)));
        }
        List<Shape> exactShapes = shapes(exact);
        if (polygons.size() <= 1) {
            return exactShapes;
        }
        Area dilated = new Area();
        BasicStroke stroke = new BasicStroke((float) (2 * FUSE_DILATION), BasicStroke.CAP_SQUARE,
                BasicStroke.JOIN_MITER, 50f);
        for (float[][] polygon : polygons) {
            Path2D.Float path = toPath(polygon);
            dilated.add(new Area(path));
            dilated.add(new Area(stroke.createStrokedShape(path)));
        }
        List<Shape> dilatedShapes = shapes(dilated);
        return joinsMore(dilatedShapes, exactShapes) ? dilatedShapes : exactShapes;
    }

    private static boolean joinsMore(List<Shape> dilated, List<Shape> exact) {
        if (coveredArea(dilated) < coveredArea(exact) - 1) {
            return false;
        }
        return dilated.size() < exact.size()
                || (dilated.size() == exact.size() && holeCount(dilated) > holeCount(exact));
    }

    private static int holeCount(List<Shape> shapes) {
        int holes = 0;
        for (Shape shape : shapes) {
            holes += shape.holes.size();
        }
        return holes;
    }

    private static double coveredArea(List<Shape> shapes) {
        double area = 0;
        for (Shape shape : shapes) {
            area += Math.abs(signedArea(shape.outer));
            for (float[][] hole : shape.holes) {
                area -= Math.abs(signedArea(hole));
            }
        }
        return area;
    }

    private static List<Shape> shapes(Area area) {
        List<float[][]> loops = loops(area);
        int n = loops.size();
        double[] areas = new double[n];
        Path2D.Float[] paths = new Path2D.Float[n];
        for (int i = 0; i < n; i++) {
            areas[i] = Math.abs(signedArea(loops.get(i)));
            paths[i] = toPath(loops.get(i));
        }
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) {
            parent[i] = -1;
            for (int j = 0; j < n; j++) {
                if (j != i && areas[j] > areas[i] && isInside(loops.get(i), paths[j], loops.get(j))
                        && (parent[i] < 0 || areas[j] < areas[parent[i]])) {
                    parent[i] = j;
                }
            }
        }
        List<Shape> shapes = new ArrayList<>();
        Map<Integer, Shape> byLoop = new HashMap<>();
        for (int i = 0; i < n; i++) {
            // Even nesting depths (0, 2, ...) are outer boundaries; odd depths are interior cavities
            if (depth(parent, i) % 2 == 0) {
                Shape shape = new Shape(loops.get(i));
                shapes.add(shape);
                byLoop.put(i, shape);
            }
        }
        for (int i = 0; i < n; i++) {
            if (depth(parent, i) % 2 == 1 && isSignificantHole(loops.get(i), areas[i])) {
                Shape owner = byLoop.get(parent[i]);
                if (owner != null) {
                    owner.holes.add(loops.get(i));
                }
            }
        }
        shapes.sort((s1, s2) -> Double.compare(Math.abs(signedArea(s2.outer)), Math.abs(signedArea(s1.outer))));
        return shapes;
    }

    private static int depth(int[] parent, int i) {
        int depth = 0;
        for (int p = parent[i]; p >= 0 && depth < parent.length; p = parent[p]) {
            depth++;
        }
        return depth;
    }

    private static boolean isInside(float[][] loop, Path2D.Float container, float[][] containerPoints) {
        boolean strictlyInside = false;
        for (float[] point : loop) {
            boolean inside = container.contains(point[0], point[1]);
            double distance = distanceToOutline(point[0], point[1], containerPoints);
            if (!inside && distance > 0.05) {
                return false;
            }
            if (inside && distance > 0.05) {
                strictlyInside = true;
            }
        }
        return strictlyInside;
    }

    private static double distanceToOutline(double px, double py, float[][] poly) {
        double min = Double.MAX_VALUE;
        for (int i = 0; i < poly.length; i++) {
            float[] p1 = poly[i], p2 = poly[(i + 1) % poly.length];
            min = Math.min(min, Line2D.ptSegDist(p1[0], p1[1], p2[0], p2[1], px, py));
        }
        return min;
    }

    /** Rejects accidental wall-join slivers: requires > 1 dm^2 area and hydraulic width > MIN_HOLE_WIDTH. */
    private static boolean isSignificantHole(float[][] loop, double area) {
        double perimeter = 0;
        for (int i = 0; i < loop.length; i++) {
            float[] a = loop[i], b = loop[(i + 1) % loop.length];
            perimeter += Math.hypot(b[0] - a[0], b[1] - a[1]);
        }
        return area > 100 && perimeter > 0 && area / (perimeter / 2) > MIN_HOLE_WIDTH;
    }

    /** Groups walls into targets; when {@code mergeTouchingClosedSets} is false, touching closed loops stay separate. */
    public static List<SurroundTarget> forWalls(List<Wall> walls, boolean mergeTouchingClosedSets) {
        if (walls == null || walls.isEmpty()) {
            return Collections.emptyList();
        }
        List<SurroundTarget> result = new ArrayList<>();
        if (mergeTouchingClosedSets) {
            for (List<Wall> group : groupWalls(walls, true)) {
                result.addAll(buildWallTargets(group));
            }
        } else {
            List<List<Wall>> closedGroups = new ArrayList<>();
            List<Wall> openWalls = new ArrayList<>();
            for (List<Wall> group : groupWalls(walls, false)) {
                for (SurroundTarget target : buildWallTargets(group)) {
                    if (target.isClosed()) {
                        closedGroups.add(new ArrayList<>(target.getWalls()));
                    } else {
                        openWalls.addAll(target.getWalls());
                    }
                }
            }
            List<SurroundTarget> openTargets = new ArrayList<>();
            for (List<Wall> group : groupWalls(openWalls, true)) {
                for (SurroundTarget target : buildWallTargets(group)) {
                    if (target.isClosed()) {
                        closedGroups.addAll(splitIndependentClosedSets(target));
                    } else {
                        openTargets.add(target);
                    }
                }
            }
            for (SurroundTarget open : openTargets) {
                int best = -1;
                for (int i = 0; i < closedGroups.size() && best < 0; i++) {
                    if (wallsTouch(open.getWalls(), closedGroups.get(i))) {
                        best = i;
                    }
                }
                if (best >= 0) {
                    closedGroups.get(best).addAll(open.getWalls());
                } else {
                    result.add(open);
                }
            }
            List<SurroundTarget> closedResult = new ArrayList<>();
            for (List<Wall> cg : closedGroups) {
                closedResult.addAll(buildWallTargets(cg));
            }
            closedResult.addAll(result);
            result = closedResult;
        }
        for (int i = 0; i < result.size(); i++) {
            result.get(i).number = i + 1;
        }
        return result;
    }

    private static List<List<Wall>> splitIndependentClosedSets(SurroundTarget target) {
        List<Room> cavities = target.innerFaces;
        List<Wall> walls = target.getWalls();
        if (cavities.size() < 2) {
            return Collections.singletonList(new ArrayList<>(walls));
        }
        int n = cavities.size();
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) {
            parent[i] = i;
        }
        List<List<Integer>> bordered = new ArrayList<>();
        for (Wall wall : walls) {
            List<Integer> list = new ArrayList<>();
            for (int c = 0; c < n; c++) {
                if (bordersOutline(wall.getPoints(), cavities.get(c).getPoints())) {
                    list.add(c);
                }
            }
            bordered.add(list);
            for (int k = 1; k < list.size(); k++) {
                parent[find(parent, list.get(0))] = find(parent, list.get(k));
            }
        }
        Map<Integer, List<Wall>> sets = new LinkedHashMap<>();
        List<Wall> loose = new ArrayList<>();
        for (int i = 0; i < walls.size(); i++) {
            List<Integer> list = bordered.get(i);
            if (list.isEmpty()) {
                loose.add(walls.get(i));
            } else {
                sets.computeIfAbsent(find(parent, list.get(0)), k -> new ArrayList<>()).add(walls.get(i));
            }
        }
        List<List<Wall>> result = new ArrayList<>(sets.values());
        if (result.size() < 2) {
            return Collections.singletonList(new ArrayList<>(walls));
        }
        for (Wall wall : loose) {
            List<Wall> owner = result.get(0);
            for (List<Wall> set : result) {
                if (wallsTouch(Collections.singletonList(wall), set)) {
                    owner = set;
                    break;
                }
            }
            owner.add(wall);
        }
        return result;
    }

    private static boolean bordersOutline(float[][] wallPoints, float[][] outline) {
        double tolerance = FUSE_DILATION + 0.6;
        int near = 0;
        for (int i = 0; i < wallPoints.length; i++) {
            float[] a = wallPoints[i], b = wallPoints[(i + 1) % wallPoints.length];
            int steps = Math.max(1, (int) (Math.hypot(b[0] - a[0], b[1] - a[1]) / 5));
            for (int k = 1; k < steps; k++) { // Inner points only so a corner touch doesn't count
                double t = (double) k / steps;
                if (distanceToOutline(a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, outline) <= tolerance
                        && ++near >= 2) {
                    return true;
                }
            }
        }
        return false;
    }

    static int find(int[] parent, int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }
        return i;
    }

    public static boolean hasMultipleTouchingClosedSets(List<Room> rooms, List<Wall> walls) {
        List<SurroundTarget> independentWalls = forWalls(walls, false);
        for (int i = 0; i < independentWalls.size(); i++) {
            for (int j = i + 1; j < independentWalls.size(); j++) {
                if (independentWalls.get(i).isClosed() && independentWalls.get(j).isClosed()
                        && wallsTouch(independentWalls.get(i).getWalls(), independentWalls.get(j).getWalls())) {
                    return true;
                }
            }
        }
        if (rooms != null && rooms.size() >= 2) {
            for (int i = 0; i < rooms.size(); i++) {
                for (int j = i + 1; j < rooms.size(); j++) {
                    if (sameLevel(rooms.get(i), rooms.get(j))
                            && polygonsWithin(rooms.get(i).getPoints(), rooms.get(j).getPoints(), TOUCH_TOLERANCE)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean sameLevel(Room a, Room b) {
        return a.getLevel() == null || b.getLevel() == null || a.getLevel() == b.getLevel();
    }

    public static List<SurroundTarget> mergeTouchingRoomTargets(List<SurroundTarget> targets) {
        List<SurroundTarget> rooms = new ArrayList<>(), others = new ArrayList<>();
        for (SurroundTarget t : targets) {
            (t.getKind() == ROOM ? rooms : others).add(t);
        }
        if (rooms.size() <= 1) {
            return targets;
        }
        int m = rooms.size();
        boolean[] used = new boolean[m];
        List<SurroundTarget> mergedRooms = new ArrayList<>();
        for (int i = 0; i < m; i++) {
            if (used[i]) {
                continue;
            }
            List<Room> cluster = new ArrayList<>();
            List<Integer> queue = new ArrayList<>();
            queue.add(i);
            used[i] = true;
            while (!queue.isEmpty()) {
                Room rCur = rooms.get(queue.remove(queue.size() - 1)).getRoom();
                cluster.add(rCur);
                for (int j = 0; j < m; j++) {
                    if (!used[j]) {
                        Room rOther = rooms.get(j).getRoom();
                        if (sameLevel(rCur, rOther) && polygonsWithin(rCur.getPoints(), rOther.getPoints(), TOUCH_TOLERANCE)) {
                            used[j] = true;
                            queue.add(j);
                        }
                    }
                }
            }
            List<Shape> shapes = Collections.emptyList();
            if (cluster.size() > 1) {
                List<float[][]> polygons = new ArrayList<>(cluster.size());
                for (Room r : cluster) {
                    polygons.add(r.getPoints());
                }
                shapes = fuse(polygons);
            }
            if (shapes.size() == 1) {
                Room unified = new Room(shapes.get(0).outer);
                unified.setLevel(cluster.get(0).getLevel());
                unified.setName(cluster.get(0).getName());
                mergedRooms.add(forMergedRooms(unified, cluster, 0));
            } else {
                for (Room r : cluster) {
                    mergedRooms.add(forRoom(r, 0));
                }
            }
        }
        for (int i = 0; i < mergedRooms.size(); i++) {
            mergedRooms.get(i).number = i + 1;
        }
        List<SurroundTarget> all = new ArrayList<>(mergedRooms);
        all.addAll(others);
        return all;
    }

    private static boolean wallsTouch(List<Wall> a, List<Wall> b) {
        for (Wall wa : a) {
            for (Wall wb : b) {
                if ((wa.getLevel() == null || wb.getLevel() == null || wa.getLevel() == wb.getLevel())
                        && polygonsWithin(wa.getPoints(), wb.getPoints(), TOUCH_TOLERANCE)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static List<List<Wall>> groupWalls(List<Wall> walls, boolean includeGeometricContact) {
        List<List<Wall>> groups = new ArrayList<>();
        Set<Wall> remaining = new LinkedHashSet<>(walls);
        while (!remaining.isEmpty()) {
            Wall first = remaining.iterator().next();
            List<Wall> group = new ArrayList<>(), stack = new ArrayList<>();
            stack.add(first);
            remaining.remove(first);
            while (!stack.isEmpty()) {
                Wall wall = stack.remove(stack.size() - 1);
                group.add(wall);
                for (Wall next : new Wall[]{wall.getWallAtStart(), wall.getWallAtEnd()}) {
                    if (next != null && remaining.remove(next)) {
                        stack.add(next);
                    }
                }
                if (includeGeometricContact) {
                    List<Wall> touched = new ArrayList<>();
                    for (Wall cand : remaining) {
                        if ((wall.getLevel() == null || cand.getLevel() == null || cand.isAtLevel(wall.getLevel()))
                                && polygonsWithin(wall.getPoints(), cand.getPoints(), TOUCH_TOLERANCE)) {
                            touched.add(cand);
                        }
                    }
                    remaining.removeAll(touched);
                    stack.addAll(touched);
                }
            }
            groups.add(group);
        }
        return groups;
    }

    private static List<float[][]> wallPolygons(List<Wall> walls) {
        List<float[][]> polygons = new ArrayList<>(walls.size());
        for (Wall w : walls) {
            polygons.add(w.getPoints());
        }
        return polygons;
    }

    private static List<SurroundTarget> buildWallTargets(List<Wall> group) {
        List<Shape> shapes = fuse(wallPolygons(group));
        List<SurroundTarget> targets = new ArrayList<>();
        if (shapes.isEmpty()) {
            return targets;
        }
        if (shapes.size() == 1) {
            targets.add(buildWallTarget(group, shapes.get(0)));
            return targets;
        }
        List<List<Wall>> parts = new ArrayList<>(shapes.size());
        List<Area> outlines = new ArrayList<>(shapes.size());
        for (Shape shape : shapes) {
            parts.add(new ArrayList<Wall>());
            outlines.add(new Area(toPath(shape.outer)));
        }
        for (Wall wall : group) {
            Area wallArea = new Area(toPath(wall.getPoints()));
            int best = 0;
            double bestOverlap = -1;
            for (int i = 0; i < outlines.size(); i++) {
                Area overlap = new Area(wallArea);
                overlap.intersect(outlines.get(i));
                double size = areaOf(overlap);
                if (size > bestOverlap) {
                    bestOverlap = size;
                    best = i;
                }
            }
            parts.get(best).add(wall);
        }
        for (List<Wall> part : parts) {
            if (!part.isEmpty()) {
                List<Shape> partShapes = fuse(wallPolygons(part));
                if (!partShapes.isEmpty()) {
                    targets.add(buildWallTarget(part, partShapes.get(0)));
                }
            }
        }
        return targets;
    }

    private static double areaOf(Area area) {
        double total = 0;
        for (float[][] loop : loops(area)) {
            total += Math.abs(signedArea(loop));
        }
        return total;
    }

    private static SurroundTarget buildWallTarget(List<Wall> group, Shape shape) {
        float[][] outerLoop = shape.outer;
        Room outer = face(outerLoop, group);
        List<Room> inner = new ArrayList<>(shape.holes.size());
        for (float[][] hole : shape.holes) {
            inner.add(face(hole, group));
        }
        boolean isClosed = !inner.isEmpty();

        List<Wall> perimWalls = new ArrayList<>(), intWalls = new ArrayList<>();
        if (isClosed) {
            for (Wall w : group) {
                (isWallOnOuterPerimeter(w, outerLoop) ? perimWalls : intWalls).add(w);
            }
            // Rebuild outer face from perimeter walls only so protruding partitions aren't surrounded outside
            if (!intWalls.isEmpty() && !perimWalls.isEmpty()) {
                List<Shape> perimeterShapes = fuse(wallPolygons(perimWalls));
                if (!perimeterShapes.isEmpty()) {
                    outer = face(perimeterShapes.get(0).outer, group);
                }
            }
        } else {
            perimWalls.addAll(group);
        }

        SurroundTarget target = new SurroundTarget(WALLS, 0, null, outer, inner, isClosed);
        target.walls = new ArrayList<>(group);
        target.perimeterWalls = perimWalls;
        target.interiorWalls = intWalls;
        return target;
    }

    private static boolean isWallOnOuterPerimeter(Wall wall, float[][] outerLoop) {
        float[][] wp = wall.getPoints();
        int n = wp.length;
        double maxEdge = 0;
        for (int i = 0; i < n; i++) {
            float[] a = wp[i], b = wp[(i + 1) % n];
            maxEdge = Math.max(maxEdge, Math.hypot(b[0] - a[0], b[1] - a[1]));
        }
        for (int i = 0; i < n; i++) {
            float[] a = wp[i], b = wp[(i + 1) % n];
            double len = Math.hypot(b[0] - a[0], b[1] - a[1]);
            if (len < maxEdge * 0.5 && len < 20.0) {
                continue; // Short wall-thickness end cap, not a longitudinal side
            }
            if (distanceToOutline((a[0] + b[0]) / 2.0, (a[1] + b[1]) / 2.0, outerLoop) <= TOUCH_TOLERANCE + 1.5) {
                return true;
            }
        }
        return false;
    }

    private static Room face(float[][] points, List<Wall> group) {
        Room face = new Room(points);
        face.setLevel(group.get(0).getLevel());
        return face;
    }

    static Path2D.Float toPath(float[][] points) {
        Path2D.Float path = new Path2D.Float();
        for (int i = 0; i < points.length; i++) {
            if (i == 0) {
                path.moveTo(points[i][0], points[i][1]);
            } else {
                path.lineTo(points[i][0], points[i][1]);
            }
        }
        path.closePath();
        return path;
    }

    static List<float[][]> loops(Area area) {
        List<float[][]> loops = new ArrayList<>();
        List<float[]> current = new ArrayList<>();
        float[] coords = new float[6];
        for (PathIterator it = area.getPathIterator(null, 0.5); !it.isDone(); it.next()) {
            switch (it.currentSegment(coords)) {
                case PathIterator.SEG_MOVETO:
                    current = new ArrayList<>();
                    current.add(new float[]{coords[0], coords[1]});
                    break;
                case PathIterator.SEG_LINETO:
                    current.add(new float[]{coords[0], coords[1]});
                    break;
                case PathIterator.SEG_CLOSE:
                    float[][] loop = clean(current);
                    if (loop.length >= 3) {
                        loops.add(loop);
                    }
                    current = new ArrayList<>();
                    break;
                default:
                    break;
            }
        }
        return loops;
    }

    private static float[][] clean(List<float[]> points) {
        List<float[]> result = new ArrayList<>(points);
        boolean changed = true;
        while (changed && result.size() >= 3) {
            changed = false;
            for (int i = 0; i < result.size() && result.size() >= 3; i++) {
                float[] prev = result.get((i - 1 + result.size()) % result.size());
                float[] cur = result.get(i);
                float[] next = result.get((i + 1) % result.size());
                double dx1 = cur[0] - prev[0], dy1 = cur[1] - prev[1];
                double dx2 = next[0] - cur[0], dy2 = next[1] - cur[1];
                double l1 = Math.hypot(dx1, dy1), l2 = Math.hypot(dx2, dy2);
                if (l1 < 0.5 || (l2 > 1e-6 && Math.abs(dx1 * dy2 - dy1 * dx2) / (l1 * l2) < 1e-3 && dx1 * dx2 + dy1 * dy2 > 0)) {
                    result.remove(i--);
                    changed = true;
                }
            }
        }
        return result.toArray(new float[0][]);
    }

    static double signedArea(float[][] p) {
        double area = 0;
        for (int i = 0; i < p.length; i++) {
            float[] a = p[i], b = p[(i + 1) % p.length];
            area += a[0] * b[1] - b[0] * a[1];
        }
        return area / 2;
    }

    public static boolean areTogether(List<Room> rooms, List<Wall> walls) {
        for (Room room : rooms) {
            float[][] rp = room.getPoints();
            for (Wall wall : walls) {
                if ((room.getLevel() == null || wall.isAtLevel(room.getLevel()))
                        && polygonsWithin(rp, wall.getPoints(), 2)) {
                    return true;
                }
            }
        }
        return false;
    }

    static boolean polygonsWithin(float[][] a, float[][] b, double tolerance) {
        Area overlap = new Area(toPath(a));
        overlap.intersect(new Area(toPath(b)));
        if (!overlap.isEmpty()) {
            return true;
        }
        for (int i = 0; i < a.length; i++) {
            float[] a1 = a[i], a2 = a[(i + 1) % a.length];
            for (int j = 0; j < b.length; j++) {
                float[] b1 = b[j], b2 = b[(j + 1) % b.length];
                double d = Math.min(Math.min(
                        Line2D.ptSegDist(a1[0], a1[1], a2[0], a2[1], b1[0], b1[1]),
                        Line2D.ptSegDist(a1[0], a1[1], a2[0], a2[1], b2[0], b2[1])), Math.min(
                        Line2D.ptSegDist(b1[0], b1[1], b2[0], b2[1], a1[0], a1[1]),
                        Line2D.ptSegDist(b1[0], b1[1], b2[0], b2[1], a2[0], a2[1])));
                if (d <= tolerance) {
                    return true;
                }
            }
        }
        return false;
    }
}
