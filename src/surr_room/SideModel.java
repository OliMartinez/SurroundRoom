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
import java.util.ArrayList;
import java.util.List;

/**
 * Splits a face outline into the sides the user sees: the many short segments of a curved wall or room
 * form one curved side, sharp turns are corners, and with "merge aligned sides" collinear neighbors
 * form one side.
 */
final class SideModel {

    static final double CURVE_MAX_SEGMENT = 120;
    static final double CURVE_MAX_TURN = Math.toRadians(25);
    static final double COLLINEAR_MAX_TURN = Math.toRadians(1.5);

    private SideModel() {
    }

    /** Returns {firstPointIndex, segmentCount} per logical side; circles with no sharp corners yield one full-loop side. */
    static List<int[]> logicalSides(float[][] points, boolean mergeCollinear) {
        int n = points.length;
        List<int[]> sides = new ArrayList<>();
        if (n < 3) {
            for (int i = 0; i < n; i++) {
                sides.add(new int[]{i, 1});
            }
            return sides;
        }
        boolean[] corner = new boolean[n];
        int corners = 0;
        for (int j = 0; j < n; j++) {
            corner[j] = !(isSmoothJunction(points, j) || (mergeCollinear && isCollinearJunction(points, j)));
            if (corner[j]) {
                corners++;
            }
        }
        if (corners == 0) {
            sides.add(new int[]{0, n});
            return sides;
        }
        for (int j = 0; j < n; j++) {
            if (corner[j]) {
                int count = 1;
                while (!corner[(j + count) % n]) {
                    count++;
                }
                sides.add(new int[]{j, count});
            }
        }
        return sides;
    }

    static boolean isCollinearJunction(float[][] p, int j) {
        double[] junction = junction(p, j);
        return junction == null || junction[2] <= COLLINEAR_MAX_TURN;
    }

    static boolean isSmoothJunction(float[][] p, int j) {
        double[] junction = junction(p, j);
        return junction == null || (junction[0] <= CURVE_MAX_SEGMENT && junction[1] <= CURVE_MAX_SEGMENT
                && junction[2] <= CURVE_MAX_TURN);
    }

    /** Returns {lengthBefore, lengthAfter, turnAngleRadians} at vertex j, or null for zero-length segments. */
    private static double[] junction(float[][] p, int j) {
        int n = p.length;
        float[] prev = p[(j - 1 + n) % n], cur = p[j], next = p[(j + 1) % n];
        double ax = cur[0] - prev[0], ay = cur[1] - prev[1];
        double bx = next[0] - cur[0], by = next[1] - cur[1];
        double la = Math.hypot(ax, ay), lb = Math.hypot(bx, by);
        if (la < 1e-6 || lb < 1e-6) {
            return null;
        }
        double cos = (ax * bx + ay * by) / (la * lb);
        return new double[]{la, lb, Math.acos(Math.max(-1, Math.min(1, cos)))};
    }

    static float[][] polyline(float[][] points, int[] side) {
        int n = points.length;
        float[][] poly = new float[side[1] + 1][];
        for (int i = 0; i <= side[1]; i++) {
            poly[i] = points[(side[0] + i) % n];
        }
        return poly;
    }

    static double length(float[][] polyline) {
        double length = 0;
        for (int i = 1; i < polyline.length; i++) {
            length += Math.hypot(polyline[i][0] - polyline[i - 1][0], polyline[i][1] - polyline[i - 1][1]);
        }
        return length;
    }

    static int count(Room face, boolean mergeCollinear) {
        return logicalSides(face.getPoints(), mergeCollinear).size();
    }

    static List<float[][]> polylines(Room face, boolean mergeCollinear) {
        float[][] points = face.getPoints();
        List<float[][]> result = new ArrayList<>();
        for (int[] side : logicalSides(points, mergeCollinear)) {
            result.add(polyline(points, side));
        }
        return result;
    }
}
