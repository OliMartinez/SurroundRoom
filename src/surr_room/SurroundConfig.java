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
 * Data Transfer Object (DTO) holding all configuration settings and user preferences
 * for the SurroundObj (SurroundRoom) generation process.
 * 
 * This object is configured by {@link SurroundConfigFrame} and passed to
 * {@link SurroundGeometryEngine} to govern mathematical placement, scaling,
 * obstacle avoidance, corner fitting, multi-piece distribution, and material randomization.
 */
public class SurroundConfig {

    // ---- Grouping Modes ----

    /** Do not group placed furniture pieces in the Sweet Home 3D plan. */
    public static final int GROUP_NONE = 0;

    /** Group all newly generated pieces across all sides into a single Sweet Home 3D furniture group. */
    public static final int GROUP_ALL = 1;

    /** Group generated pieces into separate Sweet Home 3D furniture groups per logical side. */
    public static final int GROUP_PER_SIDE = 2;

    // ---- Dimension Modification Modes ----

    /** Scale Width, Depth, and Height proportionally to maintain aspect ratio. */
    public static final int DIM_PROPORTIONAL = 0;

    /** Modify only explicitly selected dimensions (e.g. Depth or Height independently). */
    public static final int DIM_SELECTED = 1;

    /** Modify only object width (length along the side); keep depth and height untouched. */
    public static final int DIM_WIDTH_ONLY = 2;

    // ---- Upper-level Floor Holes Modes (Upper Levels > 0) ----

    /** Allow furniture placement over holes or voids on upper levels. */
    public static final int FLOOR_HOLES_PASS = 0;

    /** Inhibit furniture placement over voids/holes on upper levels where no solid floor exists below. */
    public static final int FLOOR_HOLES_NO_PASS = 1;

    // ---- Adjacent Objects Behavior Next to Perpendicular Corner Piece ----

    /** Normal placement ignoring the corner piece footprint (may overlap). */
    public static final int ORTHO_OVERLAP = 0;

    /** Leave a defined clearance margin around the corner piece (negative values create deliberate overlap). */
    public static final int ORTHO_SEPARATE = 1;

    /** Side objects extend until reaching the first (nearest) vertex of the perpendicular corner piece. */
    public static final int ORTHO_FIRST_VERTEX = 2;

    /** Side objects extend until reaching the second (farther) vertex of the perpendicular corner piece. */
    public static final int ORTHO_SECOND_VERTEX = 3;

    /** Side objects extend until reaching the midpoint between the 1st and 2nd vertices of the corner piece. */
    public static final int ORTHO_MID_VERTEX = 4;

    // ---- Side Ends Modes (Global Start / End Behavior) ----

    /** Terminate object placement exactly at the natural polygon vertex. */
    public static final int END_NORMAL = 0;

    /** Place an additional extra piece centered directly outside the corner vertex. */
    public static final int END_EXTRA_OBJECT = 1;

    /** Shift the termination point by a specified linear distance (+ extends past vertex, - shortens before vertex). */
    public static final int END_DISTANCE = 2;

    /** Fit side termination to the perpendicular strip width of the adjacent side. */
    public static final int END_FIT = 3;

    /** Place an extra object immediately adjoining the last placed piece along the side. */
    public static final int END_ADJACENT_TO_OBJECTS = 4;

    // ---- Uniform Sizing Fallback Behaviors (Types 3 and 4) ----

    /** Flexibly resize objects by the smallest required delta so that no gaps remain. */
    public static final int UNIFORM_ADJUST = 0;

    /** Preserve the common uniform size, centering the objects and leaving gaps at side ends. */
    public static final int UNIFORM_CENTER = 1;

    /** Leave the non-fitting stretch completely empty. */
    public static final int UNIFORM_EMPTY = 2;

    /**
     * Stores inclusion/exclusion states, custom excluded intervals, and physical wall contact zones
     * for all logical sides of a specific room or wall face.
     */
    public static class SideEnds {

        /** Flags whether each logical side is skipped (true = do not surround this side). */
        public final boolean[] skipped;

        /**
         * Flags whether each side is strictly forbidden from selection (e.g. interior partition walls
         * when in exterior surrounding mode).
         */
        public final boolean[] forbidden;

        /** Indicates whether default side inclusion/exclusion flags have already been initialized. */
        public boolean sideDefaultsApplied;

        /**
         * User-defined excluded sub-intervals [startDist, endDist] in cm measured from the start
         * of each logical side.
         */
        public final List<List<double[]>> excludedSegments;

        /** True if the user has manually edited excluded segments in the SideEndsDialog. */
        public boolean excludedSegmentsModifiedByUser = false;

        /**
         * Sub-intervals [startDist, endDist] in cm where adjacent walls physically touch or overlap
         * this side, preventing furniture placement.
         */
        public final List<List<double[]>> overlapSegments;

        /** Constructs a SideEnds structure sized for the given number of logical sides. */
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

        /**
         * Initializes default exclusion zones at side endpoints. By default, no segments are excluded
         * unless explicitly added by the user (e.g. via the "1/2 object at ends" button or drawing selection).
         */
        public void initDefaultExcludedSegments(List<float[][]> sidePolylines, double halfObjectLength, boolean cornerHandlingActive) {
            if (excludedSegmentsModifiedByUser) {
                return;
            }
            for (int i = 0; i < sidePolylines.size() && i < excludedSegments.size(); i++) {
                excludedSegments.get(i).clear();
            }
        }

        /** Creates an independent deep copy of this SideEnds instance. */
        public SideEnds copy() {
            SideEnds c = new SideEnds(skipped.length);
            for (int i = 0; i < skipped.length; i++) {
                c.skipped[i] = skipped[i];
                c.forbidden[i] = forbidden[i];
                for (double[] seg : excludedSegments.get(i)) {
                    c.excludedSegments.get(i).add(seg.clone());
                }
                for (double[] ov : overlapSegments.get(i)) {
                    c.overlapSegments.get(i).add(ov.clone());
                }
            }
            c.sideDefaultsApplied = this.sideDefaultsApplied;
            c.excludedSegmentsModifiedByUser = this.excludedSegmentsModifiedByUser;
            return c;
        }

        /** Counts the number of sides marked as skipped. */
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

    // ---- Surround Mode & Type Settings ----

    /** True for interior room placement (facing inward); false for exterior perimeter placement. */
    public boolean interior = false;

    /**
     * Surround type: 1 equal spaces, ending at the vertices; 2 contiguous and centered on the side; 3 enlarged
     * to cover the side; 4 reduced to cover the side; 5 contiguous from the start; 6 contiguous, with the last
     * one resized to end at the vertex; 7 a number of objects, or one every distance, around the outline.
     */
    public int surroundType = 1; // 1..7

    // ---- Grouping & Dimension Settings ----

    /** Sweet Home 3D piece grouping behavior ({@link #GROUP_NONE}, {@link #GROUP_ALL}, or {@link #GROUP_PER_SIDE}). */
    public int groupingMode = GROUP_NONE;

    /** Dimension scaling behavior ({@link #DIM_PROPORTIONAL}, {@link #DIM_SELECTED}, or {@link #DIM_WIDTH_ONLY}). */
    public int dimModifyMode = DIM_PROPORTIONAL;

    /** When modifying selected dimensions, whether depth is enabled for resizing. */
    public boolean dimModifyD = true;

    /** When modifying selected dimensions, whether height is enabled for resizing. */
    public boolean dimModifyH = false;

    // ---- Direction & Symmetry (Types 5, 6, 7) ----

    /** Inverts the traversal direction of object sequence progression. */
    public boolean reverseDirection = false;

    /** Forces identical symmetrical piece distribution from both ends toward center. */
    public boolean symmetricBothEnds = false;

    // ---- Piece Clearance Margins ----

    /** Whether an inter-piece margin is applied. */
    public boolean enablePieceMargin = false;

    /** Distance in cm between adjacent pieces (negative values produce deliberate overlap). */
    public double pieceMargin = 0.0;

    // ---- Uniform Size & Gap Distribution Across Entire Perimeter (Types 1-4) ----

    /** Enforce identical piece width across all sides of the room/wall outline. */
    public boolean uniformSize = false;

    /** Maximum allowed percentage increase or decrease when sizing pieces uniformly (e.g. 20%). */
    public double uniformMaxSizeChange = 20.0;

    /** Maximum allowed size variance in centimeters between sides. */
    public double uniformMaxSizeDifference = 1.0;

    /** Maximum allowed joint overlap/gap in centimeters between consecutive objects. */
    public double uniformMaxJoint = 0.2;

    /** Fallback action when a stretch cannot satisfy uniform size limits ({@link #UNIFORM_ADJUST}, {@link #UNIFORM_CENTER}, or {@link #UNIFORM_EMPTY}). */
    public int uniformFallback = UNIFORM_ADJUST;

    /** Enforce equal spacing/gaps between objects across all sides. */
    public boolean uniformGaps = false;

    /** Maximum allowed gap difference in centimeters between different sides. */
    public double uniformMaxGapDifference = 1.0;

    // Diagnostic metrics populated by the geometry engine during generation
    public transient int sizeAdjustedStretches = 0;
    public transient double sizeSpread = 0.0;
    public transient int sizeCenteredStretches = 0;
    public transient int sizeUnfittedStretches = 0;
    public transient int gapAdjustedStretches = 0;
    public transient double gapSpread = 0.0;

    // ---- Collisions, Obstacles, Gaps, and Floor Voids ----

    /** Prevent pieces from overlapping other pre-existing furniture in the room. */
    public boolean avoidCollisions = false;

    /** Prevent pieces from crossing over into adjacent room floor surfaces. */
    public boolean avoidNearbyFloors = false;

    /** Prevent newly placed pieces in the current operation from colliding with one another (deselected by default). */
    public boolean avoidNewlyGenerated = false;

    /** Split side intervals into separate sub-stretches around doors, windows, and obstacles. */
    public boolean splitAtObstacles = true;

    /** Treat windows as wall openings / obstacles (in addition to doors). */
    public boolean respectWindows = false;

    /** Restrict gap avoidance to a specific vertical elevation window. */
    public boolean gapHeightRange = false;

    /** Lower boundary in cm for vertical gap detection. */
    public double gapMinHeight = 0.0;

    /** Upper boundary in cm for vertical gap detection. */
    public double gapMaxHeight = 250.0;

    /** Enable tolerance margin when fitting objects near wall openings. */
    public boolean enableGapFitTolerance = false;

    /** Gap tolerance margin in centimeters. */
    public double gapFitTolerance = 5.0;

    /** Enable tolerance margin when fitting objects near furniture obstacles. */
    public boolean enableCollisionFitTolerance = false;

    /** Collision tolerance margin in centimeters. */
    public double collisionFitTolerance = 5.0;

    /** Force placement of at least one centered object on sides shorter than normal object length. */
    public boolean shortSidesOneObject = false;

    /** Upper-level floor void behavior ({@link #FLOOR_HOLES_PASS} or {@link #FLOOR_HOLES_NO_PASS}). */
    public int upperLevelFloorHolesMode = FLOOR_HOLES_PASS;

    /** Diagnostic flag set when pieces are omitted due to lack of supporting floor beneath. */
    public transient boolean skippedDueToNoFloorBelow = false;

    // ---- Collinear Merging, Touching Closed Sets, and Excluded Segments ----

    /** Merge adjacent collinear wall segments forming 0-degree angles into single sides (Item 11). */
    public boolean mergeCollinearSides = false;

    /** Merge touching closed wall loops or rooms into a single combined target (Items 3 &amp; 11). */
    public boolean mergeTouchingClosedSets = false;

    /** Enable user-defined exclusion intervals along sides. */
    public boolean enableExcludedSegments = false;

    // ---- Vertical Stacking ----

    /** Enable vertical repetition (stacking tiers of objects). */
    public boolean enableVerticalStacking = false;

    /** Number of vertical levels/tiers to stack. */
    public int verticalStackCount = 1;

    /** Use custom explicit elevation increment between vertical tiers rather than piece height. */
    public boolean useCustomStackElevation = false;

    /** Custom vertical elevation step in centimeters between stacked tiers. */
    public double customStackElevation = 0.0;

    // ---- Corner Handling & Side Termination ----

    /** Enable specialized corner fitting algorithms. */
    public boolean enableCornerHandling = false;

    /** Place a dedicated perpendicular corner piece at 90-degree polygon junctions. */
    public boolean placeOrthogonalCornerPiece = false;

    /** Corner piece separation mode ({@link #ORTHO_OVERLAP}, {@link #ORTHO_SEPARATE}, etc.). */
    public int orthoSeparation = ORTHO_OVERLAP;

    /** Align corner piece directly touching the vertex point. */
    public boolean cornerTouchVertex = false;

    /** In Types 3 and 4, keep corner pieces at their original catalog size without uniform scaling. */
    public boolean cornerKeepSize = false;

    /** Margin around perpendicular corner piece in centimeters. */
    public double cornerPieceMargin = 0.0;

    /** Optional custom furniture template picked from the plan, furniture list, or catalog for corners. */
    public HomePieceOfFurniture customCornerPiece = null;

    /** Enable per-side custom start and end distance overrides. */
    public boolean customSideEnds = false;

    /** Default termination mode at the start of sides ({@link #END_NORMAL}, {@link #END_EXTRA_OBJECT}, etc.). */
    public int globalStartEndMode = END_NORMAL;

    /** Linear offset distance in cm at the start of sides. */
    public double globalStartEndDistance = 0.0;

    /** Default termination mode at the end of sides ({@link #END_FIT}, {@link #END_DISTANCE}, etc.). */
    public int globalEndEndMode = END_FIT;

    /** Linear offset distance in cm at the end of sides. */
    public double globalEndEndDistance = 0.0;

    /** Extra objects respect doors, windows, and obstacle collisions. */
    public boolean extraObjectRespectObstacles = true;

    /** Extra objects do not penetrate past corner vertices into adjacent walls. */
    public boolean extraObjectRespectCorners = true;

    /** SideEnds configurations mapped by room face. */
    public Map<Room, SideEnds> sideEnds = new HashMap<>();

    /** Set of faces that must always be treated as exterior (e.g. open wall polylines). */
    public Set<Room> alwaysExterior = new HashSet<>();

    /** Mapping from target face to original source rooms in the home. */
    public Map<Room, List<Room>> faceSourceRooms = new HashMap<>();

    // ---- Distance Offset & Type 7 Controls ----

    /** Perpendicular distance offset in cm from the wall/floor edge. */
    public double distanceOffset = 0.0;

    /** Fixed number of objects to distribute along each side for Type 7. */
    public int type7ObjectCount = 0;

    /** Fixed step distance in cm between consecutive objects for Type 7. */
    public double type7Spacing = 300.0;

    /** Custom start positions per face for Type 7: maps Room face to {logicalSideIndex, distFromSideStartInNativeOrder}. */
    public Map<Room, double[]> type7StartPoints = new HashMap<>();

    // ---- Dimension Randomization ----

    /** Randomize all three dimensions (width, depth, height) concurrently. */
    public boolean randomizeAll = false;

    /** Randomize width independently. */
    public boolean randomizeWidth = false;

    /** Randomize depth independently. */
    public boolean randomizeDepth = false;

    /** Randomize height independently. */
    public boolean randomizeHeight = false;

    /** Min and max percentage scaling bounds for overall size randomization. */
    public double minAll = 75.0, maxAll = 125.0;

    /** Min and max percentage scaling bounds for width randomization. */
    public double minWidth = 75.0, maxWidth = 125.0;

    /** Min and max percentage scaling bounds for depth randomization. */
    public double minDepth = 75.0, maxDepth = 125.0;

    /** Min and max percentage scaling bounds for height randomization. */
    public double minHeight = 75.0, maxHeight = 125.0;

    // ---- Random & Gradual Non-Shape-Affecting Properties ----

    /** Enable discrete non-geometric property randomization (colors, textures, yaw rotation, elevation, etc.). */
    public boolean enableRandomProps = false;

    /** Configuration parameters for discrete property randomization. */
    public SurroundRandomSettings randomSettings = new SurroundRandomSettings();

    /** Enable smooth progressive (gradual) property variation from start to end of sides. */
    public boolean enableGradualProps = false;

    /** Configuration parameters for gradual property transitions. */
    public SurroundRandomSettings gradualSettings = new SurroundRandomSettings();
}
