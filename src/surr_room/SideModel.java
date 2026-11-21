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

    /** Maximum length in centimeters of a single segment considered part of a curve (120 cm). */
    static final double CURVE_MAX_SEGMENT = 120;

    /** Maximum angular deviation at a vertex (25 degrees) to still be considered part of the same curve. */
    static final double CURVE_MAX_TURN = Math.toRadians(25);

    /** Maximum angular tolerance (~1.5 degrees) for classifying two adjacent segments as collinear. */
    static final double COLLINEAR_MAX_TURN = Math.toRadians(1.5);

    /** Private constructor preventing instantiation of utility class. */
    private SideModel() {
    }

    /**
     * The sides of a closed polygon as {firstPointIndex, segmentCount}: a side runs from point firstPointIndex
     * to (firstPointIndex + segmentCount) % n. With mergeCollinear, turns under {@link #COLLINEAR_MAX_TURN}
     * aren't corners.
     */
    static List<int[]> logicalSides(float[][] points, boolean mergeCollinear) {
        int n = points.length;
        List<int[]> sides = new ArrayList<>();
        // Degenerate polygons (less than 3 vertices) cannot form closed corners
        if (n < 3) {
            for (int i = 0; i < n; i++) {
                sides.add(new int[]{i, 1});
            }
            return sides;
        }

        // Flag each vertex as a true corner or a smooth/collinear transition
        boolean[] corner = new boolean[n];
        int corners = 0;
        for (int j = 0; j < n; j++) {
            corner[j] = !(isSmoothJunction(points, j) || (mergeCollinear && isCollinearJunction(points, j)));
            if (corner[j]) {
                corners++;
            }
        }

        // Full circle or closed smooth loop with no sharp corners: treated as one continuous perimeter
        if (corners == 0) {
            sides.add(new int[]{0, n});   // a circle: one side all around
            return sides;
        }

        // Walk from each corner to the next, grouping intermediate smooth segments
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

    /**
     * Determines whether vertex {@code j} joins two segments in a nearly collinear direction
     * (turn angle &lt;= {@link #COLLINEAR_MAX_TURN}).
     */
    static boolean isCollinearJunction(float[][] p, int j) {
        double[] junction = junction(p, j);
        return junction == null || junction[2] <= COLLINEAR_MAX_TURN;
    }

    /**
     * Determines whether vertex {@code j} joins two segments of a smooth curve
     * (both segment lengths &lt;= {@link #CURVE_MAX_SEGMENT} and turn angle &lt;= {@link #CURVE_MAX_TURN}).
     */
    static boolean isSmoothJunction(float[][] p, int j) {
        double[] junction = junction(p, j);
        return junction == null || (junction[0] <= CURVE_MAX_SEGMENT && junction[1] <= CURVE_MAX_SEGMENT
                && junction[2] <= CURVE_MAX_TURN);
    }

    /**
     * Analyzes geometry at vertex {@code j}, measuring incoming length, outgoing length, and turn angle.
     *
     * @return array {@code {lengthBefore, lengthAfter, turnAngleInRadians}}, or null if either segment is zero-length
     */
    private static double[] junction(float[][] p, int j) {
        int n = p.length;
        float[] prev = p[(j - 1 + n) % n], cur = p[j], next = p[(j + 1) % n];
        double ax = cur[0] - prev[0], ay = cur[1] - prev[1];
        double bx = next[0] - cur[0], by = next[1] - cur[1];
        double la = Math.hypot(ax, ay), lb = Math.hypot(bx, by);
        if (la < 1e-6 || lb < 1e-6) {
            return null;
        }
        // Dot product normalized by lengths yields cosine of turn angle
        double cos = (ax * bx + ay * by) / (la * lb);
        return new double[]{la, lb, Math.acos(Math.max(-1, Math.min(1, cos)))};
    }

    /**
     * Extracts the coordinates of a logical side as an ordered polyline from its starting vertex
     * to its terminating vertex.
     *
     * @param side   integer pair {@code {startIndex, segmentCount}}
     * @return 2D coordinate array of length {@code segmentCount + 1}
     */
    static float[][] polyline(float[][] points, int[] side) {
        int n = points.length;
        float[][] poly = new float[side[1] + 1][];
        for (int i = 0; i <= side[1]; i++) {
            poly[i] = points[(side[0] + i) % n];
        }
        return poly;
    }

    /** Calculates the total perimeter length of a polyline across all its segments. */
    static double length(float[][] polyline) {
        double length = 0;
        for (int i = 1; i < polyline.length; i++) {
            length += Math.hypot(polyline[i][0] - polyline[i - 1][0], polyline[i][1] - polyline[i - 1][1]);
        }
        return length;
    }

    /**
     * Counts how many logical sides exist on the given room face.
     * Corresponds to the array size needed for {@link SurroundConfig.SideEnds}.
     *
     * @param face            the Room or wall face
     * @param mergeCollinear  true if 0-degree collinear walls should be merged
     */
    static int count(Room face, boolean mergeCollinear) {
        return logicalSides(face.getPoints(), mergeCollinear).size();
    }

    /**
     * Extracts all logical sides of a room face as individual polylines in clockwise/counterclockwise order.
     *
     * @param face           the Room or wall face
     * @param mergeCollinear true if 0-degree collinear walls should be merged
     * @return list of polylines, one per logical side
     */
    static List<float[][]> polylines(Room face, boolean mergeCollinear) {
        float[][] points = face.getPoints();
        List<float[][]> result = new ArrayList<>();
        for (int[] side : logicalSides(points, mergeCollinear)) {
            result.add(polyline(points, side));
        }
        return result;
    }
}
