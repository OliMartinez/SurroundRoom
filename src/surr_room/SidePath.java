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

    final float[][] points;
    final double[] cumulative;
    final double length;
    final int index;
    final boolean reversed;

    /** When positive, curved endpoints align with the chord spanning this width rather than the first/last segment tangent. */
    double endSpan;

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

    boolean isCurved() {
        return points.length > 2;
    }

    int segmentCount() {
        return points.length - 1;
    }

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
     * Effective front length left for objects of {@code depth} at {@code offset} between {@code from} and {@code to}:
     * curves turning toward the placement normal shrink available length; curves turning away stretch it.
     */
    double frontLength(double from, double to, double offset, double depth) {
        double length = to - from;
        for (int i = 1; i < points.length - 1; i++) {
            if (cumulative[i] <= from + 1e-9 || cumulative[i] >= to - 1e-9) {
                continue;
            }
            double[] u0 = direction(i - 1), u1 = direction(i);
            double cross = u0[0] * u1[1] - u0[1] * u1[0];
            double halfTurn = Math.tan(Math.atan2(Math.abs(cross), u0[0] * u1[0] + u0[1] * u1[1]) / 2);
            if (cross < 0) {
                length -= 2 * (offset + depth) * halfTurn;
            } else if (cross > 0) {
                length += 2 * offset * halfTurn;
            }
        }
        return Math.max(length * 0.05, length);
    }

    double[] direction(int i) {
        double dx = points[i + 1][0] - points[i][0], dy = points[i + 1][1] - points[i][1];
        double len = Math.hypot(dx, dy);
        return len < 1e-9 ? new double[]{1, 0} : new double[]{dx / len, dy / len};
    }

    double[] directionAt(double s) {
        return direction(segmentAt(s));
    }

    double[] firstDirection() {
        return isCurved() && endSpan > 0 ? chordDirection(0, Math.min(endSpan, length)) : direction(0);
    }

    double[] lastDirection() {
        return isCurved() && endSpan > 0 ? chordDirection(length - Math.min(endSpan, length), length) : direction(points.length - 2);
    }

    private double[] chordDirection(double from, double to) {
        double[] a = pointAt(from), b = pointAt(to);
        double len = Math.hypot(b[0] - a[0], b[1] - a[1]);
        return len < 1e-9 ? direction(0) : new double[]{(b[0] - a[0]) / len, (b[1] - a[1]) / len};
    }

    double[] pointAt(double s) {
        int i = segmentAt(s);
        double[] u = direction(i);
        double t = s - cumulative[i];
        return new double[]{points[i][0] + u[0] * t, points[i][1] + u[1] * t};
    }

    float[] start() {
        return points[0];
    }
}
