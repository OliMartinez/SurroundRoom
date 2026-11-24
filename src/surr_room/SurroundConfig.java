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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Configuration DTO produced by {@link SurroundConfigFrame} and consumed by {@link SurroundGeometryEngine}
 * to govern placement, scaling, obstacle avoidance, corners, stacking, and property variation.
 */
public class SurroundConfig {

    public static final int GROUP_NONE = 0, GROUP_ALL = 1, GROUP_PER_SIDE = 2;
    public static final int DIM_PROPORTIONAL = 0, DIM_SELECTED = 1, DIM_WIDTH_ONLY = 2;
    public static final int FLOOR_HOLES_PASS = 0, FLOOR_HOLES_NO_PASS = 1;
    public static final int ORTHO_OVERLAP = 0, ORTHO_SEPARATE = 1, ORTHO_FIRST_VERTEX = 2,
            ORTHO_SECOND_VERTEX = 3, ORTHO_MID_VERTEX = 4;
    public static final int END_NORMAL = 0, END_EXTRA_OBJECT = 1, END_DISTANCE = 2,
            END_FIT = 3, END_ADJACENT_TO_OBJECTS = 4;
    public static final int UNIFORM_ADJUST = 0, UNIFORM_CENTER = 1, UNIFORM_EMPTY = 2;

    /** Per-face side exclusions, forbidden partition masks, excluded stretches, and wall contact zones. */
    public static class SideEnds {

        public final boolean[] skipped;
        /** Sides forbidden from selection (e.g. interior partition walls in exterior mode). */
        public final boolean[] forbidden;
        public boolean sideDefaultsApplied;
        public final List<List<double[]>> excludedSegments;
        public final List<List<double[]>> overlapSegments;

        public SideEnds(int sideCount) {
            this.skipped = new boolean[sideCount];
            this.forbidden = new boolean[sideCount];
            this.excludedSegments = new ArrayList<>(sideCount);
            this.overlapSegments = new ArrayList<>(sideCount);
            for (int i = 0; i < sideCount; i++) {
                this.excludedSegments.add(new ArrayList<double[]>());
                this.overlapSegments.add(new ArrayList<double[]>());
            }
        }

        public SideEnds copy() {
            SideEnds c = new SideEnds(skipped.length);
            System.arraycopy(skipped, 0, c.skipped, 0, skipped.length);
            System.arraycopy(forbidden, 0, c.forbidden, 0, forbidden.length);
            for (int i = 0; i < skipped.length; i++) {
                for (double[] seg : excludedSegments.get(i)) {
                    c.excludedSegments.get(i).add(seg.clone());
                }
                for (double[] ov : overlapSegments.get(i)) {
                    c.overlapSegments.get(i).add(ov.clone());
                }
            }
            c.sideDefaultsApplied = this.sideDefaultsApplied;
            return c;
        }

        public int skippedCount() {
            int n = 0;
            for (boolean b : skipped) {
                if (b) {
                    n++;
                }
            }
            return n;
        }
    }

    public boolean interior = false;
    /** 1 equal spaces; 2 centered; 3 enlarge; 4 reduce; 5 from start; 6 resize last; 7 fixed count/spacing. */
    public int surroundType = 1;

    public int groupingMode = GROUP_NONE;
    public int dimModifyMode = DIM_PROPORTIONAL;
    public boolean dimModifyD = true;
    public boolean dimModifyH = false;

    public boolean reverseDirection = false;
    public boolean symmetricBothEnds = false;

    public boolean enablePieceMargin = false;
    public double pieceMargin = 0.0;

    public boolean uniformSize = false;
    public double uniformMaxSizeChange = 20.0;
    public double uniformMaxSizeDifference = 1.0;
    public double uniformMaxJoint = 0.2;
    public int uniformFallback = UNIFORM_ADJUST;
    public boolean uniformGaps = false;
    public double uniformMaxGapDifference = 1.0;

    // Diagnostic metrics populated during generation
    public transient int sizeAdjustedStretches = 0;
    public transient double sizeSpread = 0.0;
    public transient int sizeCenteredStretches = 0;
    public transient int sizeUnfittedStretches = 0;
    public transient int gapAdjustedStretches = 0;
    public transient double gapSpread = 0.0;

    public boolean avoidCollisions = false;
    public boolean avoidNearbyFloors = false;
    public boolean avoidNewlyGenerated = false;
    public boolean splitAtObstacles = true;
    public boolean respectWindows = false;
    public boolean gapHeightRange = false;
    public double gapMinHeight = 0.0;
    public double gapMaxHeight = 250.0;
    public boolean enableGapFitTolerance = false;
    public double gapFitTolerance = 5.0;
    public boolean enableCollisionFitTolerance = false;
    public double collisionFitTolerance = 5.0;
    public boolean shortSidesOneObject = false;
    public int upperLevelFloorHolesMode = FLOOR_HOLES_PASS;
    public transient boolean skippedDueToNoFloorBelow = false;

    public boolean mergeCollinearSides = false;
    public boolean mergeTouchingClosedSets = false;
    public boolean enableExcludedSegments = false;

    public boolean enableVerticalStacking = false;
    public int verticalStackCount = 1;
    public boolean useCustomStackElevation = false;
    public double customStackElevation = 0.0;

    public boolean enableCornerHandling = false;
    public boolean placeOrthogonalCornerPiece = false;
    public int orthoSeparation = ORTHO_OVERLAP;
    public boolean cornerTouchVertex = false;
    public boolean cornerKeepSize = false;
    public double cornerPieceMargin = 0.0;
    public HomePieceOfFurniture customCornerPiece = null;
    public boolean customSideEnds = false;
    public int globalStartEndMode = END_NORMAL;
    public double globalStartEndDistance = 0.0;
    public int globalEndEndMode = END_FIT;
    public double globalEndEndDistance = 0.0;
    public boolean extraObjectRespectObstacles = true;
    public boolean extraObjectRespectCorners = true;

    public Map<Room, SideEnds> sideEnds = new HashMap<>();
    public Set<Room> excludedRooms = new HashSet<>();
    /** Faces forced to exterior mode regardless of the global interior toggle (e.g. open wall chains). */
    public Set<Room> alwaysExterior = new HashSet<>();
    public Map<Room, List<Room>> faceSourceRooms = new HashMap<>();

    public double distanceOffset = 0.0;
    public int type7ObjectCount = 0;
    public double type7Spacing = 300.0;
    /** Custom Type 7 start positions per face: {logicalSideIndex, nativeDistAlongSide}. */
    public Map<Room, double[]> type7StartPoints = new HashMap<>();

    public boolean randomizeAll = false, randomizeWidth = false, randomizeDepth = false, randomizeHeight = false;
    public double minAll = 75.0, maxAll = 125.0;
    public double minWidth = 75.0, maxWidth = 125.0;
    public double minDepth = 75.0, maxDepth = 125.0;
    public double minHeight = 75.0, maxHeight = 125.0;

    public boolean enableRandomProps = false;
    public SurroundRandomSettings randomSettings = new SurroundRandomSettings();
    public boolean enableGradualProps = false;
    public SurroundRandomSettings gradualSettings = new SurroundRandomSettings();
}
