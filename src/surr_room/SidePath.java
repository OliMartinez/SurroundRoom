/*
 * Copyright (c) 2024-2026 Oliver Mart\u00ednez (Ollie_Jess)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package surr_room;

/**
 * One side of a face, straight or curved (several segments), measured by the distance along it from its
 * first point. Distances before 0 or past its length continue along its first or last segment, so a side
 * can go on past its vertices. Objects go on the side of the normal (uy, -ux).
 */
final class SidePath {

    /** Vertices in order of traversal along this side, where points[i] is [x, y]. */
    final float[][] points;

    /** Cumulative arc length from start (points[0]) to each vertex points[i]. */
    final double[] cumulative;

    /** Total cumulative length of this side along its segments. */
    final double length;

    /** Zero-based logical index of this side within the face's original order. */
    final int index;

    /** True if this side is traversed in reverse order relative to the face's vertex list. */
    final boolean reversed;

    /**
     * Chord length used to estimate effective orientation at curve endpoints.
     * When positive, endpoints align with the chord spanning the furniture width
     * rather than an instantaneous tangent (0 uses first/last segment direction).
     */
    double endSpan;

    /**
     * Constructs a SidePath from an array of vertices.
     *
     * @param reversed true if vertices are inverted relative to natural face winding
     */
    SidePath(float[][] points, int index, boolean reversed) {
        this.points = points;
        this.index = index;
        this.reversed = reversed;
        this.cumulative = new double[points.length];
        for (int i = 1; i < points.length; i++) {
            cumulative[i] = cumulative[i - 1]
                    + Math.hypot(points[i][0] - points[i - 1][0], points[i][1] - points[i - 1][1]);
        }
        this.length = cumulative[points.length - 1];
    }

    /**
     * Checks if this path represents a curved side composed of more than one segment.
     *
     * @return true if the path contains 3 or more vertices (2 or more segments)
     */
    boolean isCurved() {
        return points.length > 2;
    }

    /**
     * Returns the number of discrete line segments forming this path.
     *
     * @return points.length - 1
     */
    int segmentCount() {
        return points.length - 1;
    }

    /**
     * Finds the index of the segment enclosing arc length position s.
     * Extrapolates to segment 0 for s &lt; 0 or to the last segment for s &gt;= length.
     *
     * @return zero-based index of the containing segment
     */
    int segmentAt(double s) {
        int last = points.length - 2;
        for (int i = 0; i < last; i++) {
            if (s < cumulative[i + 1]) {
                return i;
            }
        }
        return last;
    }

    /**
     * Length left to the fronts of objects of that depth, at that offset, between from and to: a curve turning
     * toward them shrinks it, one turning away stretches it (never under 5% of the length).
     */
    double frontLength(double from, double to, double offset, double depth) {
        double length = to - from;
        for (int i = 1; i < points.length - 1; i++) {
            if (cumulative[i] <= from + 1e-9 || cumulative[i] >= to - 1e-9) {
                continue;
            }
            double[] u0 = direction(i - 1);
            double[] u1 = direction(i);
            // 2D cross product: determines turn direction (left vs right)
            double cross = u0[0] * u1[1] - u0[1] * u1[0];
            // Tangent of half the turn angle: scaling factor for miter expansion/contraction
            double halfTurn = Math.tan(Math.atan2(Math.abs(cross), u0[0] * u1[0] + u0[1] * u1[1]) / 2);
            if (cross < 0) {   // Turns towards the normal (uy, -ux) where objects are placed
                length -= 2 * (offset + depth) * halfTurn;
            } else if (cross > 0) { // Turns away from placement normal
                length += 2 * offset * halfTurn;
            }
        }
        return Math.max(length * 0.05, length);
    }

    /**
     * Calculates the normalized unit direction vector [dx, dy] of segment i.
     *
     * @return unit vector [ux, uy], or [1, 0] if the segment is degenerate
     */
    double[] direction(int i) {
        double dx = points[i + 1][0] - points[i][0];
        double dy = points[i + 1][1] - points[i][1];
        double len = Math.hypot(dx, dy);
        return len < 1e-9 ? new double[]{1, 0} : new double[]{dx / len, dy / len};
    }

    /**
     * Calculates the unit direction vector of the segment containing arc distance s.
     *
     * @return unit direction vector [ux, uy]
     */
    double[] directionAt(double s) {
        return direction(segmentAt(s));
    }

    /** Determines the tangent/chord direction at the very start of this path. */
    double[] firstDirection() {
        return isCurved() && endSpan > 0 ? chordDirection(0, Math.min(endSpan, length)) : direction(0);
    }

    /** Determines the tangent/chord direction at the end of this path. */
    double[] lastDirection() {
        return isCurved() && endSpan > 0 ? chordDirection(length - Math.min(endSpan, length), length) : direction(points.length - 2);
    }

    /**
     * Computes the normalized secant chord vector between two points at arc distances from and to.
     *
     * @return normalized secant vector [dx, dy]
     */
    private double[] chordDirection(double from, double to) {
        double[] a = pointAt(from);
        double[] b = pointAt(to);
        double len = Math.hypot(b[0] - a[0], b[1] - a[1]);
        return len < 1e-9 ? direction(0) : new double[]{(b[0] - a[0]) / len, (b[1] - a[1]) / len};
    }

    /**
     * Calculates 2D Cartesian coordinates [x, y] at arc length distance s from the start.
     * Performs linear interpolation within the matching segment or linear extrapolation beyond endpoints.
     *
     * @return coordinates array [x, y]
     */
    double[] pointAt(double s) {
        int i = segmentAt(s);
        double[] u = direction(i);
        double t = s - cumulative[i];
        return new double[]{points[i][0] + u[0] * t, points[i][1] + u[1] * t};
    }

    /**
     * Returns the coordinates of the first point of this side.
     *
     * @return [x, y] array of the starting vertex
     */
    float[] start() {
        return points[0];
    }
}
