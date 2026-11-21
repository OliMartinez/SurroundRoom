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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Something to surround: a floor, or a set of touching walls. Walls join through their connections or
 * because they touch or overlap, and the contact zones are left out. Walls enclosing an area form a closed
 * set (surroundable outside and inside); its partitions can't be surrounded from outside, and from inside
 * each cavity is its own face. Touching closed sets and aligned walls can be merged.
 */
public class SurroundTarget {

    /** Target kind identifier for a Room (floor surface). */
    public static final int ROOM = 0;

    /** Target kind identifier for a Wall or collection of walls. */
    public static final int WALLS = 1;

    /** Proximity tolerance in cm below which two wall or room polygons are considered touching. */
    private static final double TOUCH_TOLERANCE = 1.5; // cm: walls / rooms closer than this are candidates to touch

    /**
     * Dilation stroke offset in cm used during polygon fusion to bridge tiny gaps
     * and coordinate discrepancies between visually touching walls.
     */
    static final double FUSE_DILATION = 0.25;

    /**
     * Minimum average width in cm for an internal cavity to be recognized as a valid room/hole
     * rather than an accidental structural gap between adjacent walls.
     */
    private static final double MIN_HOLE_WIDTH = 4.0;

    /** Near distance boundary for the contact probe polygon in centimeters. */
    private static final double PROBE_NEAR = 0.3;

    /** Far distance boundary for the contact probe polygon in centimeters. */
    private static final double PROBE_FAR = 1.5;

    /** Kind of target (either {@link #ROOM} or {@link #WALLS}). */
    private final int kind;

    /** 1-based ordinal sequence number among targets of the same kind. */
    private int number;

    /** Room object if kind == {@link #ROOM}; null for wall targets. */
    private final Room room;

    /** Outer perimeter boundary face (or full polyline for open wall chains). */
    private final Room outerFace;

    /** Inner boundary faces representing interior cavities/holes in closed wall sets. */
    private final List<Room> innerFaces;

    /** True if this target represents a closed loop enclosing at least one inner cavity. */
    private final boolean closed;

    /** Complete list of individual Wall instances constituting this target. */
    private List<Wall> walls = Collections.emptyList();

    /** Subset of walls situated along the outer exterior perimeter loop. */
    private List<Wall> perimeterWalls = Collections.emptyList();

    /** Subset of walls situated entirely inside the closed loop as internal partitions (Item 8). */
    private List<Wall> interiorWalls = Collections.emptyList();

    /**
     * Cache for geometric calculations per face, indexed by (mergeCollinear, interior):
     * slots 0..3: contact overlap intervals List&lt;List&lt;double[]&gt;&gt;
     * slots 4..7: boolean[] indicating which sides belong to interior walls.
     */
    private final Map<Room, Object[]> faceCache = new HashMap<>();

    /** Original source rooms from the home that this target represents (multiple if merged). */
    private List<Room> sourceRooms = Collections.emptyList();

    /** Private constructor initializing immutable fields. */
    private SurroundTarget(int kind, int number, Room room, Room outerFace, List<Room> innerFaces, boolean closed) {
        this.kind = kind;
        this.number = number;
        this.room = room;
        this.outerFace = outerFace;
        this.innerFaces = innerFaces;
        this.closed = closed;
    }

    /**
     * Creates a SurroundTarget representing a single room floor.
     *
     * @param room   the Sweet Home 3D Room model
     * @param number 1-based display number
     */
    public static SurroundTarget forRoom(Room room, int number) {
        SurroundTarget target = new SurroundTarget(ROOM, number, room, null, Collections.<Room>emptyList(), true);
        target.sourceRooms = Collections.singletonList(room);
        return target;
    }

    /**
     * Creates a SurroundTarget representing multiple unified/merged rooms.
     *
     * @param number  1-based display number
     */
    private static SurroundTarget forMergedRooms(Room unified, List<Room> cluster, int number) {
        SurroundTarget target = forRoom(unified, number);
        target.sourceRooms = new ArrayList<>(cluster);
        return target;
    }

    /** Returns target kind: {@link #ROOM} or {@link #WALLS}. */
    public int getKind() {
        return kind;
    }

    /** Returns the 1-based display number among targets of the same kind. */
    public int getNumber() {
        return number;
    }

    /** Returns the underlying Room instance, or null if kind == {@link #WALLS}. */
    public Room getRoom() {
        return room;
    }

    /**
     * Returns the list of original Sweet Home 3D rooms represented by this target
     * (either a single room or the merged group).
     */
    public List<Room> getSourceRooms() {
        return sourceRooms;
    }

    /** Returns the list of walls forming this wall group. */
    public List<Wall> getWalls() {
        return walls;
    }

    /**
     * Indicates whether this wall target forms a closed loop enclosing internal space.
     *
     * @return true for closed loops or rooms; false for open wall chains
     */
    public boolean isClosed() {
        return closed;
    }

    /**
     * Returns the polygon faces to surround according to interior/exterior mode.
     *
     * @param interior true for interior mode, false for exterior mode
     */
    public List<Room> getFaces(boolean interior) {
        if (kind == ROOM) {
            return Collections.singletonList(room);
        } else if (closed && interior) {
            return innerFaces;
        } else {
            return Collections.singletonList(outerFace);
        }
    }

    /**
     * Determines whether a given face is always surrounded from the outside.
     * Open wall chains do not enclose interior volume and are always exterior.
     */
    public boolean isAlwaysExterior(Room face) {
        return kind == WALLS && !closed && face == outerFace;
    }

    /**
     * Returns the 1-based cavity index for an inner face, or 0 if single/outer face.
     *
     * @return 1-based index or 0
     */
    public int getInnerFaceIndex(Room face) {
        int index = innerFaces.indexOf(face);
        return index >= 0 && innerFaces.size() > 1 ? index + 1 : 0;
    }

    /**
     * Default side settings of a face: where other walls touch each side, and partitions forbidden from outside
     * and unselected at first.
     */
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
                ? interiorWallSides(face, mergeCollinear, faceInterior)
                : new boolean[0];
        for (int i = 0; i < ends.skipped.length; i++) {
            boolean interiorSide = i < interiorSides.length && interiorSides[i];
            ends.forbidden[i] = interiorSide && !faceInterior;   // never selectable in exterior mode
            if (interiorSide && (!ends.sideDefaultsApplied || !faceInterior)) {
                ends.skipped[i] = true;                          // deselected by default
            }
        }
        ends.sideDefaultsApplied = true;
    }

    /**
     * Calculates the sub-intervals along each logical side that are physically blocked by contact
     * with another selected wall (Item 3, Observation 1).
     * Creates a narrow 2D rectangular probe polygon extending outward from each segment in the direction
     * where furniture is placed, and intersects it with the union Area of all selected walls.
     *
     * @return list of overlap intervals [startDist, endDist] per logical side
     */
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
            // Side of the segments where the objects go (same rule as the geometry engine)
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
                    // Thin probe just in front of the segment, on the side of the objects
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

    /**
     * Builds a unified Java2D Area representing the combined footprint of all walls
     * across the provided target list.
     */
    public static Area wallsArea(List<SurroundTarget> targets) {
        Area area = new Area();
        for (SurroundTarget target : targets) {
            for (Wall wall : target.getWalls()) {
                area.add(new Area(toPath(wall.getPoints())));
            }
        }
        return area;
    }

    /**
     * Determines which logical sides of a face correspond to internal partition walls (Item 8).
     * Samples the midpoint of each side and tests proximity to interior walls versus perimeter walls.
     *
     * @return boolean array where true indicates the side is along an interior partition wall
     */
    private boolean[] interiorWallSides(Room face, boolean mergeCollinear, boolean faceInterior) {
        Object[] cache = faceCache.computeIfAbsent(face, f -> new Object[8]);
        int key = 4 + (mergeCollinear ? 1 : 0) + (faceInterior ? 2 : 0);
        if (cache[key] != null) {
            return (boolean[]) cache[key];
        }
        List<float[][]> polylines = SideModel.polylines(face, mergeCollinear);
        boolean[] result = new boolean[polylines.size()];
        for (int i = 0; i < polylines.size(); i++) {
            float[][] sidePoly = polylines.get(i);
            // Middle point of the side, measured along it
            double[] middle = pointAlong(sidePoly, SideModel.length(sidePoly) / 2);
            boolean nearInteriorWall = false;
            for (Wall wall : interiorWalls) {
                if (distancePointToPolygon(middle[0], middle[1], wall.getPoints()) <= FUSE_DILATION + 1.0) {
                    nearInteriorWall = true;
                    break;
                }
            }
            if (!nearInteriorWall) {
                continue;
            }
            boolean onPerimeterWall = false;
            for (Wall wall : perimeterWalls) {
                if (distancePointToPolygon(middle[0], middle[1], wall.getPoints()) <= FUSE_DILATION + 0.5) {
                    onPerimeterWall = true;
                    break;
                }
            }
            result[i] = !onPerimeterWall;
        }
        cache[key] = result;
        return result;
    }

    /**
     * Computes the Cartesian coordinates [x, y] of a point situated at a given arc distance
     * along a polyline.
     *
     * @return [x, y] coordinates
     */
    private static double[] pointAlong(float[][] poly, double distance) {
        double cumulative = 0;
        for (int k = 0; k + 1 < poly.length; k++) {
            float[] a = poly[k], b = poly[k + 1];
            double length = Math.hypot(b[0] - a[0], b[1] - a[1]);
            if (cumulative + length >= distance && length > 1e-9) {
                double t = (distance - cumulative) / length;
                return new double[]{a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t};
            }
            cumulative += length;
        }
        float[] last = poly[poly.length - 1];
        return new double[]{last[0], last[1]};
    }

    /**
     * In-place merges contiguous or overlapping 1D intervals [start, end].
     *
     * @param intervals list of 2-element double arrays representing ranges
     */
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

    /**
     * Measures the shortest distance from point (px, py) to a polygon.
     * Returns 0.0 if the point lies inside the polygon.
     *
     * @return shortest distance in cm (0.0 if interior)
     */
    private static double distancePointToPolygon(double px, double py, float[][] poly) {
        return toPath(poly).contains(px, py) ? 0.0 : distanceToOutline(px, py, poly);
    }

    // ---- Union of polygons: outer outlines and their enclosed areas ----

    /**
     * A topologically connected 2D geometric shape consisting of an outer perimeter loop
     * and zero or more enclosed internal hole loops (cavities).
     */
    static final class Shape {
        /** Outer boundary vertices forming the exterior loop. */
        final float[][] outer;

        /** List of internal hole boundary vertices (cavities/courtyards). */
        final List<float[][]> holes = new ArrayList<>();

        /** Creates a new Shape with the specified outer boundary loop. */
        Shape(float[][] outer) {
            this.outer = outer;
        }
    }

    /**
     * Union of polygons as separate shapes, largest first. Polygons closer than 2 &times; {@link #FUSE_DILATION}
     * are joined: a slightly dilated union is used when it joins more shapes or finds real cavities without
     * distorting them; otherwise the exact union.
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

    /**
     * Evaluates whether the dilated boolean union provides a superior topological fusion
     * compared to the exact union.
     *
     * @return true if dilated union forms fewer disjoint components or identifies more enclosed cavities
     */
    private static boolean joinsMore(List<Shape> dilated, List<Shape> exact) {
        if (coveredArea(dilated) < coveredArea(exact) - 1) {
            return false;
        }
        return dilated.size() < exact.size()
                || (dilated.size() == exact.size() && holeCount(dilated) > holeCount(exact));
    }

    /** Counts the total number of internal hole cavities across a list of shapes. */
    private static int holeCount(List<Shape> shapes) {
        int holes = 0;
        for (Shape shape : shapes) {
            holes += shape.holes.size();
        }
        return holes;
    }

    /**
     * Computes the net surface area covered by a list of shapes (outer area minus internal hole areas).
     *
     * @return net area in cm&sup2;
     */
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

    /**
     * Decomposes a Java2D {@link Area} into structured {@link Shape} records with nested outer loops
     * and internal hole loops based on geometric containment hierarchy.
     *
     * @param area the input 2D Area
     */
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
            // Outer outlines: depth 0, 2, 4... (an island inside a hole is a shape of its own)
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
        // Largest shapes first
        shapes.sort((s1, s2) -> Double.compare(Math.abs(signedArea(s2.outer)), Math.abs(signedArea(s1.outer))));
        return shapes;
    }

    /**
     * Calculates the containment nesting depth of loop {@code i} within the loop hierarchy.
     * Even depths (0, 2, ...) are exterior boundaries; odd depths (1, 3, ...) are interior holes.
     */
    private static int depth(int[] parent, int i) {
        int depth = 0;
        for (int p = parent[i]; p >= 0 && depth < parent.length; p = parent[p]) {
            depth++;
        }
        return depth;
    }

    /** Tests whether a loop is geometrically nested completely inside a candidate container loop. */
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

    /**
     * Calculates the minimum Euclidean distance from point (px, py) to any edge of a polygon outline.
     *
     * @return minimum distance in cm
     */
    private static double distanceToOutline(double px, double py, float[][] poly) {
        double min = Double.MAX_VALUE;
        for (int i = 0; i < poly.length; i++) {
            float[] p1 = poly[i], p2 = poly[(i + 1) % poly.length];
            min = Math.min(min, Line2D.ptSegDist(p1[0], p1[1], p2[0], p2[1], px, py));
        }
        return min;
    }

    /**
     * Evaluates whether an enclosed cavity loop is large enough to represent a real room interior
     * rather than an insignificant structural gap between walls (at least 1 dm&sup2; area and
     * average hydraulic radius &gt; {@link #MIN_HOLE_WIDTH}).
     *
     * @param area absolute area in cm&sup2;
     * @return true if significant hole
     */
    private static boolean isSignificantHole(float[][] loop, double area) {
        double perimeter = 0;
        for (int i = 0; i < loop.length; i++) {
            float[] a = loop[i], b = loop[(i + 1) % loop.length];
            perimeter += Math.hypot(b[0] - a[0], b[1] - a[1]);
        }
        return area > 100 && perimeter > 0 && area / (perimeter / 2) > MIN_HOLE_WIDTH;
    }

    // ---- Walls Grouping & Closed Set Detection (Items 3, 8, 11) ----

    /**
     * Clusters selected walls into distinct {@link SurroundTarget} instances (Items 3, 8, and 11).
     * <ul>
     *   <li>Walls joined directly in Sweet Home 3D, or touching/overlapping physically, belong to the same group.</li>
     *   <li>When {@code mergeTouchingClosedSets} is false, closed loops that touch at a boundary are maintained
     *       as separate independent sets, while open walls attach to the closed set they touch.</li>
     * </ul>
     */
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
            List<SurroundTarget> closedSets = new ArrayList<>();
            List<Wall> openWalls = new ArrayList<>();
            for (List<Wall> group : groupWalls(walls, false)) {
                for (SurroundTarget target : buildWallTargets(group)) {
                    if (target.isClosed()) {
                        closedSets.add(target);
                    } else {
                        openWalls.addAll(target.getWalls());
                    }
                }
            }
            List<List<Wall>> closedGroups = new ArrayList<>();
            for (SurroundTarget target : closedSets) {
                closedGroups.add(new ArrayList<>(target.getWalls()));
            }
            List<SurroundTarget> openTargets = new ArrayList<>();
            for (List<Wall> group : groupWalls(openWalls, true)) {
                for (SurroundTarget target : buildWallTargets(group)) {
                    if (target.isClosed()) {
                        // Closed by contact: sets that only touch each other stay independent
                        closedGroups.addAll(splitIndependentClosedSets(target));
                    } else {
                        openTargets.add(target);
                    }
                }
            }
            boolean[] changed = new boolean[closedGroups.size()];
            for (SurroundTarget open : openTargets) {
                int best = -1;
                for (int i = 0; i < closedGroups.size() && best < 0; i++) {
                    if (wallsTouch(open.getWalls(), closedGroups.get(i))) {
                        best = i;
                    }
                }
                if (best >= 0) {
                    closedGroups.get(best).addAll(open.getWalls());
                    changed[best] = true;
                } else {
                    result.add(open);
                }
            }
            List<SurroundTarget> closedResult = new ArrayList<>();
            for (int i = 0; i < closedGroups.size(); i++) {
                closedResult.addAll(buildWallTargets(closedGroups.get(i)));
            }
            closedResult.addAll(result);
            result = closedResult;
        }
        // Numbers 1, 2, 3... in the final order (never twice the same number)
        for (int i = 0; i < result.size(); i++) {
            result.get(i).number = i + 1;
        }
        return result;
    }

    /**
     * Partitions a composite closed set formed by geometric contact into sub-clusters that touch
     * only at shared walls or points.
     */
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
                union(parent, list.get(0), list.get(k));
            }
        }
        Map<Integer, List<Wall>> sets = new java.util.LinkedHashMap<>();
        List<Wall> loose = new ArrayList<>();
        for (int i = 0; i < walls.size(); i++) {
            List<Integer> list = bordered.get(i);
            if (list.isEmpty()) {
                loose.add(walls.get(i));
            } else {
                int root = find(parent, list.get(0));
                List<Wall> set = sets.get(root);
                if (set == null) {
                    set = new ArrayList<>();
                    sets.put(root, set);
                }
                set.add(walls.get(i));
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

    /**
     * Determines whether an edge of a wall runs along an outline boundary by testing sample points
     * along the wall edges (points sampled every 5 cm within tolerance).
     */
    private static boolean bordersOutline(float[][] wallPoints, float[][] outline) {
        double tolerance = FUSE_DILATION + 0.6;
        int near = 0;
        for (int i = 0; i < wallPoints.length; i++) {
            float[] a = wallPoints[i], b = wallPoints[(i + 1) % wallPoints.length];
            double length = Math.hypot(b[0] - a[0], b[1] - a[1]);
            int steps = Math.max(1, (int) (length / 5));
            for (int k = 1; k < steps; k++) {   // inner points only: a mere corner contact doesn't count
                double t = (double) k / steps;
                if (distanceToOutline(a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, outline) <= tolerance) {
                    near++;
                    if (near >= 2) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Finds the root representative of element i in a disjoint-set forest with path halving. */
    static int find(int[] parent, int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }
        return i;
    }

    /** Unites the disjoint sets containing elements a and b. */
    private static void union(int[] parent, int a, int b) {
        parent[find(parent, a)] = find(parent, b);
    }

    /**
     * Determines whether multiple touching/connected closed sets (wall loops or rooms) exist in the selection,
     * signaling to the configuration UI whether to show the "Merge touching sets" option (Items 3 &amp; 11).
     */
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

    /** Checks if two rooms reside on the same level (or if either has an unspecified level). */
    private static boolean sameLevel(Room a, Room b) {
        return a.getLevel() == null || b.getLevel() == null || a.getLevel() == b.getLevel();
    }

    /**
     * Merges touching or overlapping room targets into single unified room polygons when the user
     * enables unified set processing (Items 3 &amp; 11). Rooms that do not physically touch are preserved.
     */
    public static List<SurroundTarget> mergeTouchingRoomTargets(List<SurroundTarget> targets) {
        List<SurroundTarget> rooms = new ArrayList<>();
        List<SurroundTarget> others = new ArrayList<>();
        for (SurroundTarget t : targets) {
            if (t.getKind() == ROOM) {
                rooms.add(t);
            } else {
                others.add(t);
            }
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
                int cur = queue.remove(queue.size() - 1);
                Room rCur = rooms.get(cur).getRoom();
                cluster.add(rCur);
                for (int j = 0; j < m; j++) {
                    if (!used[j]) {
                        Room rOther = rooms.get(j).getRoom();
                        if (sameLevel(rCur, rOther)
                                && polygonsWithin(rCur.getPoints(), rOther.getPoints(), TOUCH_TOLERANCE)) {
                            used[j] = true;
                            queue.add(j);
                        }
                    }
                }
            }
            List<Shape> shapes = Collections.emptyList();
            if (cluster.size() > 1) {
                List<float[][]> polygons = new ArrayList<>();
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

    /** Checks if any wall in group {@code a} physically touches any wall in group {@code b} on the same level. */
    private static boolean wallsTouch(List<Wall> a, List<Wall> b) {
        for (Wall wa : a) {
            for (Wall wb : b) {
                if (wa.getLevel() != null && wb.getLevel() != null && wa.getLevel() != wb.getLevel()) {
                    continue;
                }
                if (polygonsWithin(wa.getPoints(), wb.getPoints(), TOUCH_TOLERANCE)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Groups walls by graph connectivity (start/end pointers) and optionally by physical geometric proximity.
     *
     * @param includeGeometricContact  true to include touching walls as connected
     */
    private static List<List<Wall>> groupWalls(List<Wall> walls, boolean includeGeometricContact) {
        List<List<Wall>> groups = new ArrayList<>();
        Set<Wall> remaining = new LinkedHashSet<>(walls);
        while (!remaining.isEmpty()) {
            Wall first = remaining.iterator().next();
            List<Wall> group = new ArrayList<>();
            List<Wall> stack = new ArrayList<>();
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
                        if (wall.getLevel() != null && cand.getLevel() != null && !cand.isAtLevel(wall.getLevel())) {
                            continue;
                        }
                        if (polygonsWithin(wall.getPoints(), cand.getPoints(), TOUCH_TOLERANCE)) {
                            touched.add(cand);
                        }
                    }
                    for (Wall t : touched) {
                        remaining.remove(t);
                        stack.add(t);
                    }
                }
            }
            groups.add(group);
        }
        return groups;
    }

    /**
     * Constructs target representations for a cluster of walls, producing one target per
     * distinct outer contour shape.
     */
    private static List<SurroundTarget> buildWallTargets(List<Wall> group) {
        List<float[][]> polygons = new ArrayList<>();
        for (Wall wall : group) {
            polygons.add(wall.getPoints());
        }
        List<Shape> shapes = fuse(polygons);
        List<SurroundTarget> targets = new ArrayList<>();
        if (shapes.isEmpty()) {
            return targets;
        }
        if (shapes.size() == 1) {
            targets.add(buildWallTarget(group, shapes.get(0)));
            return targets;
        }
        // Split the walls by the outline that contains them
        List<List<Wall>> parts = new ArrayList<>();
        List<Area> outlines = new ArrayList<>();
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
            if (part.isEmpty()) {
                continue;
            }
            List<float[][]> partPolygons = new ArrayList<>();
            for (Wall wall : part) {
                partPolygons.add(wall.getPoints());
            }
            List<Shape> partShapes = fuse(partPolygons);
            if (!partShapes.isEmpty()) {
                targets.add(buildWallTarget(part, partShapes.get(0)));
            }
        }
        return targets;
    }

    /**
     * Calculates the total surface area of all loops in an Area.
     *
     * @return total area in cm&sup2;
     */
    private static double areaOf(Area area) {
        double total = 0;
        for (float[][] loop : loops(area)) {
            total += Math.abs(signedArea(loop));
        }
        return total;
    }

    /**
     * Builds a single SurroundTarget from a wall list and its corresponding topological Shape.
     * Segregates outer perimeter walls from internal partition walls (Item 8).
     */
    private static SurroundTarget buildWallTarget(List<Wall> group, Shape shape) {
        float[][] outerLoop = shape.outer;
        Room outer = face(outerLoop, group);
        List<Room> inner = new ArrayList<>();
        for (float[][] hole : shape.holes) {
            inner.add(face(hole, group));
        }
        boolean isClosed = !inner.isEmpty();

        // Distinguish perimeter walls vs interior walls in closed wall sets (Item 8 & Observation 2)
        List<Wall> perimWalls = new ArrayList<>();
        List<Wall> intWalls = new ArrayList<>();
        if (isClosed) {
            for (Wall w : group) {
                if (isWallOnOuterPerimeter(w, outerLoop)) {
                    perimWalls.add(w);
                } else {
                    intWalls.add(w);
                }
            }
            // The exterior is surrounded along the perimeter walls only (a partition sticking out
            // of the outline isn't part of the outer face)
            if (!intWalls.isEmpty() && !perimWalls.isEmpty()) {
                List<float[][]> perimeterPolygons = new ArrayList<>();
                for (Wall pw : perimWalls) {
                    perimeterPolygons.add(pw.getPoints());
                }
                List<Shape> perimeterShapes = fuse(perimeterPolygons);
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

    /**
     * Checks whether a wall contributes a longitudinal side to the outer perimeter loop.
     * A partition wall that is strictly inside the outer boundary (or only touches the outer walls at its ends)
     * is classified as an interior wall (Item 8).
     */
    private static boolean isWallOnOuterPerimeter(Wall wall, float[][] outerLoop) {
        float[][] wp = wall.getPoints();
        // Check if any midpoint of the wall's long edges lies near the outerLoop boundary
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
                continue; // Short end cap of the wall (thickness), not a longitudinal side
            }
            double mx = (a[0] + b[0]) / 2.0, my = (a[1] + b[1]) / 2.0;
            for (int j = 0; j < outerLoop.length; j++) {
                float[] o1 = outerLoop[j], o2 = outerLoop[(j + 1) % outerLoop.length];
                if (Line2D.ptSegDist(o1[0], o1[1], o2[0], o2[1], mx, my) <= TOUCH_TOLERANCE + 1.5) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Creates a synthetic Room instance representing a face boundary, inheriting the level from the wall group. */
    private static Room face(float[][] points, List<Wall> group) {
        Room face = new Room(points);
        face.setLevel(group.get(0).getLevel());
        return face;
    }

    /**
     * Converts a 2D coordinate array into a closed Java2D {@link Path2D.Float} geometry.
     *
     * @param points 2D vertex array
     * @return Path2D.Float representation
     */
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

    /**
     * Extracts discrete closed loops from a Java2D {@link Area}, filtering redundant or collinear vertices.
     *
     * @param area input 2D Area
     */
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

    /** Removes duplicate adjacent vertices and collinear points along straight lines from a loop. */
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
                    result.remove(i);   // duplicated point, or aligned with its neighbours
                    changed = true;
                    i--;
                }
            }
        }
        return result.toArray(new float[0][]);
    }

    /**
     * Calculates the signed 2D polygon area using the Shoelace formula.
     * Positive indicates counter-clockwise winding; negative indicates clockwise winding.
     *
     * @return signed area in cm&sup2;
     */
    static double signedArea(float[][] p) {
        double area = 0;
        for (int i = 0; i < p.length; i++) {
            float[] a = p[i], b = p[(i + 1) % p.length];
            area += a[0] * b[1] - b[0] * a[1];
        }
        return area / 2;
    }

    // ---- Rooms and walls selected together ----

    /** Determines whether any wall in a list touches or crosses any room in a list within 2 cm tolerance. */
    public static boolean areTogether(List<Room> rooms, List<Wall> walls) {
        for (Room room : rooms) {
            float[][] rp = room.getPoints();
            for (Wall wall : walls) {
                if (room.getLevel() != null && !wall.isAtLevel(room.getLevel())) {
                    continue;
                }
                float[][] wp = wall.getPoints();
                if (polygonsWithin(rp, wp, 2)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Tests whether two polygons touch, overlap, or come within a given spatial tolerance in cm.
     *
     * @param tolerance distance threshold in cm
     * @return true if polygons overlap or lie within tolerance
     */
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
                        java.awt.geom.Line2D.ptSegDist(a1[0], a1[1], a2[0], a2[1], b1[0], b1[1]),
                        java.awt.geom.Line2D.ptSegDist(a1[0], a1[1], a2[0], a2[1], b2[0], b2[1])), Math.min(
                        java.awt.geom.Line2D.ptSegDist(b1[0], b1[1], b2[0], b2[1], a1[0], a1[1]),
                        java.awt.geom.Line2D.ptSegDist(b1[0], b1[1], b2[0], b2[1], a2[0], a2[1])));
                if (d <= tolerance) {
                    return true;
                }
            }
        }
        return false;
    }
}
