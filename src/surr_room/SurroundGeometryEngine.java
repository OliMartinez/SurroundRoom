/*
 * Copyright (c) 2024-2026 Oliver Mart\u00ednez (Ollie_Jess)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package surr_room;

import com.eteks.sweethome3d.model.Home;
import com.eteks.sweethome3d.model.HomeFurnitureGroup;
import com.eteks.sweethome3d.model.HomePieceOfFurniture;
import com.eteks.sweethome3d.model.Level;
import com.eteks.sweethome3d.model.Room;
import com.eteks.sweethome3d.model.Wall;
import com.eteks.sweethome3d.viewcontroller.HomeController;
import java.awt.geom.Area;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Places copies of the chosen objects along the sides of floors and walls, in memory before pasting them:
 * <ol>
 *   <li>Takes the size of the templates and leaves them out of the furniture that can be hit.</li>
 *   <li>With the same size or the same spaces around the outline (types 1 to 4), first measures every
 *       stretch and solves one common scale or spacing.</li>
 *   <li>Splits each face into sides, and each side into stretches free of wall contacts, door and window
 *       openings, furniture and excluded stretches.</li>
 *   <li>Fills them with the chosen type ({@link SurroundConfig#surroundType}), with the corners (margins or
 *       corner pieces) and side ends.</li>
 *   <li>Stacks levels, applies the random and gradual properties, and pastes everything (grouped per side,
 *       all together or loose) on the level of each face.</li>
 * </ol>
 */
public class SurroundGeometryEngine {

    /** Numerical tolerance threshold for geometric equality checks and distance comparisons (in cm). */
    private static final double EPSILON = 1e-4;

    /** Flag allowing asynchronous preview workers to abort an in-progress computation cleanly. */
    private volatile boolean cancelled = false;

    /** Cancels an ongoing geometry calculation from another thread. */
    public void cancel() {
        this.cancelled = true;
    }

    /** Throws a cancellation exception if this engine run has been cancelled. */
    private void checkCancelled() {
        if (cancelled || Thread.currentThread().isInterrupted()) {
            throw new java.util.concurrent.CancellationException();
        }
    }

    /** Precomputed 2D polygons and bounding boxes for existing home furniture to accelerate collision checks. */
    private float[][][] existingPolys;
    private float[][] existingBounds;
    /** Precomputed 2D polygons and bounding boxes for home walls on the active level. */
    private Wall[] activeWalls;
    private float[][][] wallPolys;
    private float[][] wallBounds;
    /** Precomputed 2D polygons and bounding boxes for nearby floors on the active level. */
    private float[][][] nearbyFloorPolys;
    private float[][] nearbyFloorBounds;

    /** Active Sweet Home 3D model containing the architectural elements and furniture scene graph. */
    private final Home home;

    /** Main application controller for executing undoable user actions (such as paste and selection changes). */
    private final HomeController homeController;

    /** Target rooms, floors, or perimeter boundary polygons being surrounded. */
    private final List<Room> selectedRooms;

    /** Template furniture items selected by the user to replicate along perimeters. */
    private final List<HomePieceOfFurniture> selectedFurniture;

    /** Pre-existing furniture pieces in the home, excluding selected templates and their descendants. */
    private final List<HomePieceOfFurniture> existingHomeFurniture = new ArrayList<>();

    /** Accumulator of all newly generated furniture instances across all levels to be added to the home. */
    private final List<HomePieceOfFurniture> newlyPlacedFurniture = new ArrayList<>();

    /** Furniture pieces placed on the base (first) level before vertical stacking or upward replication. */
    private final List<HomePieceOfFurniture> firstLevelPlacedFurniture = new ArrayList<>();

    // ---- Tracking and Grouping Structures (Item 1) ----

    /** Map of side grouping keys to the list of furniture pieces placed along that specific side. */
    private final Map<String, List<HomePieceOfFurniture>> sidePlacedGroups = new LinkedHashMap<>();

    /** Lookup map mapping each base piece instance to its corresponding side grouping key. */
    private final Map<HomePieceOfFurniture, String> basePieceToSideKey = new LinkedHashMap<>();

    /** Level of the face each placed piece belongs to (where it must be pasted in the scene graph). */
    private final Map<HomePieceOfFurniture, Level> placedLevels = new HashMap<>();

    /** Clean un-randomized copy of each first-level piece so stacked levels can draw independently. */
    private final Map<HomePieceOfFurniture, HomePieceOfFurniture> cleanBasePieces = new IdentityHashMap<>();

    /** Set tracking which furniture pieces were instantiated specifically as corner pieces. */
    private final Set<HomePieceOfFurniture> cornerBasePieces = Collections.newSetFromMap(new IdentityHashMap<HomePieceOfFurniture, Boolean>());

    /** Mapping from a placed piece to the Room / floor boundary it was generated for. */
    private final Map<HomePieceOfFurniture, Room> pieceRooms = new IdentityHashMap<>();

    /** Mapping from a placed piece to the zero-based logical side index within its room. */
    private final Map<HomePieceOfFurniture, Integer> pieceSideIndices = new IdentityHashMap<>();

    /** Mapping from a placed piece to the vertex index where it originated (for Type 7 vertex placements). */
    private final Map<HomePieceOfFurniture, Integer> pieceVertexIndices = new IdentityHashMap<>();

    /** Mapping from a placed piece to the total number of sides in its owning room. */
    private final Map<HomePieceOfFurniture, Integer> pieceRoomSideCounts = new IdentityHashMap<>();

    /** Current side index being processed by the traversal algorithm (-1 when inactive). */
    private int currentSideIndex = -1;

    /** Current vertex index being processed (-1 when inactive). */
    private int currentVertexIndex = -1;

    /** Total number of logical sides of the room currently being processed. */
    private int currentRoomSideCount = 0;

    /** Pseudorandom number generator for stochastic variation passes. */
    private Random randomPropsRng = new Random();

    /** Current side grouping identifier string used during placement iteration. */
    private String currentSideGroupKey = null;

    /** Primary representative template item derived from the user's furniture selection. */
    private HomePieceOfFurniture primaryTemplate;

    /** Room boundary polygon currently being traversed and populated with furniture. */
    private Room currentProcessingRoom;

    /** Active configuration settings snapshot governing the placement pipeline. */
    private SurroundConfig activeConfig;

    /** Doors, windows, and obstacles that interrupt the perimeter sides of the active face (Item 6). */
    private List<HomePieceOfFurniture> currentObstacles = Collections.emptyList();

    /** Maximum bounding width (front length) among all selected template furniture pieces (in cm). */
    private double maxSurroundingWidth;

    /** Maximum bounding depth (across thickness) among all selected template furniture pieces (in cm). */
    private double maxSurroundingDepth;

    // ---- Curve Layout Coordinate Normalization Parameters ----

    /**
     * Coordinate origin along a curve layout: from layoutFrom, the straight extension
     * before the curve (layoutPre long, 1:1), the curve from layoutCurveStart to layoutCurveEnd laid
     * out on layoutCurveLayout (its narrowest line, stretched back by layoutStretch), then the
     * straight extension after it (1:1).
     */
    private double layoutFrom;

    /** Stretching factor applied to curve arc lengths to normalize internal vs external radii. */
    private double layoutStretch = 1;

    /** Linear extension length preceding the start of a curved corner segment. */
    private double layoutPre;

    /** Arc-length coordinate marking the start of the curved portion of a side. */
    private double layoutCurveStart;

    /** Arc-length coordinate marking the end of the curved portion of a side. */
    private double layoutCurveEnd;

    /** Effective layout length of the curved segment at its narrowest boundary line. */
    private double layoutCurveLayout;

    /** Minimum arc-length position placed along the current side during placement. */
    private double currentSidePlacedMin = Double.MAX_VALUE;

    /** Maximum arc-length position placed along the current side during placement. */
    private double currentSidePlacedMax = -Double.MAX_VALUE;

    /** Lateral tolerance available at the start boundary of the current stretch (in cm). */
    private double currentStretchStartTol = 0.0;

    /** Lateral tolerance available at the end boundary of the current stretch (in cm). */
    private double currentStretchEndTol = 0.0;

    /** Perpendicular distance offset from the perimeter edge for the current side being surrounded. */
    private double currentSideOffset = 0;

    /** Array of perpendicular distance offsets indexed by side for multi-sided polygons. */
    private double[] sideOffsets;

    /** Arc length scaling factor for parallel offset curves (length along offset curve / length along base curve). */
    private double curveWidthFactor = 1;

    // ---- Uniform Sizing and Gap Distribution Solving Parameters ----

    /**
     * Flag indicating whether the engine is currently in the measurement pre-pass:
     * with the same size (types 3 and 4) or the same spaces (types 1 and 2) around the whole outline,
     * a first pass only measures the stretches to cover (nothing is placed), the common size or
     * spaces and the count of each stretch are solved for all of them, then a second pass places.
     */
    private boolean measuringUniform;

    /** Collected list of perimeter stretches evaluated during the uniform measurement pass. */
    private final List<UniformStretch> uniformStretches = new ArrayList<>();

    /** Current index pointer into uniformStretches during the placement playback pass. */
    private int uniformIndex;

    /** Computed global uniform scale factor for Type 3 (enlarge) layout algorithm. */
    private double uniformScale3 = 1;

    /** Computed global uniform scale factor for Type 4 (reduce) layout algorithm. */
    private double uniformScale4 = 1;

    /** Scale factor applied to corner pieces when derived from the selected template furniture. */
    private double cornerPieceScale = 1;

    /**
     * Boolean table indicating which side ends ([side][0 = start, 1 = end]) abut a corner piece
     * that defines its own distinct margin, preventing double-margin accumulation.
     */
    private boolean[][] cornerOwnMargin = new boolean[0][0];

    /**
     * Constructs a new geometry engine bound to the specified home and target entities.
     *
     * @param home              the active Sweet Home 3D project model
     * @param homeController    the active window controller for committing edits
     */
    public SurroundGeometryEngine(Home home, HomeController homeController,
            List<Room> selectedRooms, List<HomePieceOfFurniture> selectedFurniture) {
        this.home = home;
        this.homeController = homeController;
        this.selectedRooms = selectedRooms;
        this.selectedFurniture = selectedFurniture;
    }

    /**
     * Executes the complete surround placement pipeline:
     * 1. Generates the base layout on the first level in-memory.
     * 2. Replicates pieces upwards if vertical stacking is enabled.
     * 3. Groups and commits the resulting items to the Sweet Home 3D home model.
     */
    public void execute(SurroundConfig config) {
        computeInMemory(config);
        commitToHome(config);
    }

    /** Computes all levels of placed furniture in memory without touching the Sweet Home 3D model (safe for background thread). */
    public void computeInMemory(SurroundConfig config) {
        runFirstLevel(config, new Random().nextLong());
        applyVerticalStacking(config);
    }

    /** Commits the previously computed furniture to the Sweet Home 3D Home model on the EDT. */
    public void commitToHome(SurroundConfig config) {
        commitPlacedFurniture(config);
    }

    /**
     * Computes the first-level surround placement in-memory without altering the home model,
     * returning the placed pieces for display in the interactive 2D preview panel of SideEndsDialog.
     */
    public List<HomePieceOfFurniture> previewFirstLevel(SurroundConfig config) {
        if (selectedFurniture == null || selectedFurniture.isEmpty() || selectedRooms == null || selectedRooms.isEmpty()) {
            return Collections.emptyList();
        }
        // Use a deterministic seed for preview consistency across UI redraws
        runFirstLevel(config, 12345L);
        return new ArrayList<>(firstLevelPlacedFurniture);
    }

    /**
     * Executes the first-level perimeter placement in memory using the supplied PRNG seed.
     * Handles uniform measurement pre-passes, obstacle detection, side traversal, and gradual property assignment.
     *
     * @param seed   the random seed for deterministic or stochastic property variation
     */
    private void runFirstLevel(SurroundConfig config, long seed) {
        this.activeConfig = config;
        config.skippedDueToNoFloorBelow = false;
        this.primaryTemplate = asSingleTemplate(selectedFurniture);
        double[] dims = getPieceSurroundingDimensions(this.primaryTemplate);
        maxSurroundingWidth = dims[0];
        maxSurroundingDepth = dims[1];

        // Collect all furniture pieces belonging to the selected templates (including grouped children)
        Set<HomePieceOfFurniture> templateSet = new HashSet<>();
        for (HomePieceOfFurniture selPiece : selectedFurniture) {
            collectTemplatePieces(selPiece, templateSet);
        }
        // Populate existingHomeFurniture excluding templates to prevent false collisions
        existingHomeFurniture.clear();
        if (home != null) {
            for (HomePieceOfFurniture existing : home.getFurniture()) {
                if (!templateSet.contains(existing) && existing.isVisible()) {
                    existingHomeFurniture.add(existing);
                }
            }
        }
        clearPlacements();
        this.randomPropsRng = new Random(seed);
        cornerPieceScale = 1;
        uniformScale3 = 1;
        uniformScale4 = 1;
        uniformStretches.clear();
        resetUniformReport(config);

        // If uniform sizing or uniform gaps are enabled, execute a pre-measurement pass
        if (usesUniform(config)) {
            prepareUniform(config);
            clearPlacements();
            config.skippedDueToNoFloorBelow = false;
            this.randomPropsRng = new Random(seed);
        }
        uniformIndex = 0;
        // Execute the main placement pass
        surroundFirstLevel(config);
        // Apply gradual property progression (colors, dimensions) across the placed pieces
        applyGradualToFirstLevel(config);
    }

    /** Checks whether the given furniture instance was placed as an orthogonal or catalog corner piece. */
    public boolean isCornerPiece(HomePieceOfFurniture piece) {
        return cornerBasePieces.contains(piece);
    }

    /**
     * Clears all placement tracking maps, accumulators, and cached surface normal orientations.
     * Invoked prior to a new layout run or between uniform measurement and placement passes.
     */
    private void clearPlacements() {
        newlyPlacedFurniture.clear();
        firstLevelPlacedFurniture.clear();
        sidePlacedGroups.clear();
        basePieceToSideKey.clear();
        placedLevels.clear();
        cleanBasePieces.clear();
        cornerBasePieces.clear();
        pieceRooms.clear();
        pieceSideIndices.clear();
        pieceVertexIndices.clear();
        pieceRoomSideCounts.clear();
        currentSideIndex = -1;
        currentVertexIndex = -1;
        currentRoomSideCount = 0;
        MaterialUtils.clearOutwardNormals();
    }

    /** Resets diagnostic counters and statistics in the configuration DTO for uniform sizing and gap reports. */
    private static void resetUniformReport(SurroundConfig config) {
        config.sizeAdjustedStretches = 0;
        config.sizeSpread = 0;
        config.sizeCenteredStretches = 0;
        config.sizeUnfittedStretches = 0;
        config.gapAdjustedStretches = 0;
        config.gapSpread = 0;
    }

    /** Traverses each selected room boundary and places furniture along its perimeter sides. */
    private void surroundFirstLevel(SurroundConfig config) {
        int roomIdx = 0;
        for (Room face : selectedRooms) {
            checkCancelled();
            roomIdx++;
            currentProcessingRoom = face;
            currentObstacles = obstaclesFor(face);
            prepareCollisionCaches(face);
            List<SidePath> sides = buildSides(face);
            if (sides.isEmpty()) {
                continue;
            }
            if (config.surroundType == 7) {
                surroundType7(face, sides, roomIdx);
            } else {
                surroundUnified(face, sides, roomIdx);
            }
        }
    }

    // ---- The same size (types 3 and 4) or the same spaces (types 1 and 2) around the whole outline ----

    /**
     * Data transfer model representing a single continuous linear stretch along a perimeter
     * side during uniform sizing (Types 3 and 4) or uniform spacing (Types 1 and 2) optimization.
     * Encapsulates the available linear length, candidate furniture template sequences, prefix sum
     * tables for fast dimensional queries, and solved layout attributes (scale, piece count, joint, start gap).
     */
    private static final class UniformStretch {
        /** Algorithmic surround mode (1: uniform gaps, 2: uniform end gaps, 3: enlarged uniform size, 4: reduced uniform size). */
        final int type;

        /** Total linear length available to cover along this stretch (in cm). */
        final double length;

        /** True if this stretch represents the entire undivided logical side of the room boundary. */
        final boolean wholeSide;

        /** Candidate template furniture instances arranged in sequence (in user selection or randomized order). */
        final List<HomePieceOfFurniture> sequence;

        /** Prefix sum array of piece widths: prefix[n] represents the cumulative unscaled width of the first n pieces. */
        final double[] prefix;

        /** Boolean flags [width, depth, height] indicating which spatial dimensions adapt to the uniform scale factor. */
        boolean[] dims;

        /** Smallest permissible gap margin (cm) between pieces (derived from piece margin settings). */
        double minGap;

        /** Solved count of furniture pieces allocated to this stretch (0 if no piece can fit within scale constraints). */
        int count;

        /** Solved uniform scale factor applied to furniture pieces along this stretch. */
        double scale = 1;

        /** Solved inter-piece spacing gap (cm) placed between adjacent furniture models. */
        double joint;

        /** Fixed inter-piece joint distance (cm) for Type 2 layout (2 * pieceMargin) separating adjacent models. */
        double fixedJoint;

        /** Leading gap offset (cm) placed before the first furniture model to center or align the run. */
        double startGap;

        /** Positional shift applied to the starting coordinate when obstacle/corner tolerance is utilized. */
        double startShift;

        /**
         * Constructs a new UniformStretch container.
         *
         * @param type      the surround algorithm type (1 to 4)
         * @param length    the linear length of the stretch (cm)
         * @param wholeSide true if this stretch spans the entire side
         */
        UniformStretch(int type, double length, boolean wholeSide, List<HomePieceOfFurniture> sequence, double[] prefix) {
            this.type = type;
            this.length = length;
            this.wholeSide = wholeSide;
            this.sequence = sequence;
            this.prefix = prefix;
        }

        /**
         * Checks whether this stretch operates under uniform sizing (Types 3 and 4).
         *
         * @return true for Type 3 (enlarge) and Type 4 (reduce); false for spacing types
         */
        boolean sameSize() {
            return type == 3 || type == 4;
        }

        /**
         * Calculates the total unscaled width occupied by n pieces including any fixed internal joint margins.
         *
         * @return cumulative occupied width (cm)
         */
        double occupiedWidth(int n) {
            return prefix[n] + (type == 2 && n > 1 ? (n - 1) * fixedJoint : 0.0);
        }

        /**
         * Returns the number of variable spacing gaps created by n pieces along this stretch.
         * For Type 2, exactly 2 end gaps exist (start and end); for Type 1, n - 1 inter-piece gaps exist.
         */
        int gapCount(int n) {
            return type == 2 ? 2 : n - 1;
        }

        /**
         * Calculates the individual gap width resulting from placing n pieces across this stretch.
         *
         * @return the resulting gap dimension (cm), or Double.NaN if no variable gaps exist or pieces exceed length
         */
        double gap(int n) {
            int gaps = gapCount(n);
            double gap = gaps > 0 ? (length - occupiedWidth(n)) / gaps : Double.NaN;
            return gap >= minGap - 1e-6 ? gap : Double.NaN;
        }
    }

    /**
     * Determines whether the specified surround type engages uniform multi-pass optimization.
     *
     * @param type the surround algorithm type (1 to 7)
     */
    private boolean usesUniform(int type) {
        return ((type == 3 || type == 4) && activeConfig.uniformSize)
                || ((type == 1 || type == 2) && activeConfig.uniformGaps);
    }

    /** Determines whether the active configuration engages uniform multi-pass optimization. */
    private boolean usesUniform(SurroundConfig config) {
        return usesUniform(config.surroundType);
    }

    /** Checks whether orthogonal corner pieces are enabled in the configuration. */
    private static boolean usesCornerPieces(SurroundConfig config) {
        return config.enableCornerHandling && config.placeOrthogonalCornerPiece;
    }

    /** {minScale, maxScale}: type 3 from 1 to 1 + max change, type 4 from 1 - max change (at least 0.05) to 1. */
    private static double[] scaleLimits(SurroundConfig config, int type) {
        double change = Math.max(0, config.uniformMaxSizeChange) / 100.0;
        return type == 3 ? new double[]{1, 1 + change} : new double[]{Math.max(0.05, 1 - change), 1};
    }

    /**
     * Returns the solved global uniform scale factor corresponding to the given surround type.
     *
     * @param type the surround type (3 or 4)
     */
    private double uniformScale(int type) {
        return type == 3 ? uniformScale3 : uniformScale4;
    }

    /**
     * Executes the iterative pre-measurement pass for uniform sizing and spacing optimization.
     * Because corner geometry and side depths depend on the scaling of the furniture models
     * (especially when model depth scales with width), the engine performs an iterative feedback
     * loop: it measures stretches, solves for optimal common scales, updates the effective depth
     * and corner scale factors, and repeats until convergence or an iteration cap (8) is reached.
     */
    private void prepareUniform(SurroundConfig config) {
        double baseDepth = maxSurroundingDepth;
        double tolerance = Math.max(0, config.uniformMaxSizeDifference) / 100.0;
        double maxScale = 1 + Math.max(0, config.uniformMaxSizeChange) / 100.0;
        boolean scaledCornerPiece = config.enableCornerHandling && usesCornerPieces(config)
                && config.customCornerCatalogPiece == null && !config.cornerKeepSize && config.uniformSize
                && (config.surroundType == 3 || config.surroundType == 4);
        double depthFactor = 1;
        cornerPieceScale = 1;
        try {
            for (int iteration = 0; ; iteration++) {
                // Adjust maxSurroundingDepth based on current depth factor estimate
                maxSurroundingDepth = baseDepth * Math.max(1, depthFactor);
                uniformStretches.clear();
                measuringUniform = true;
                try {
                    // Execute dry-run layout pass to populate uniformStretches
                    surroundFirstLevel(config);
                } finally {
                    measuringUniform = false;
                }
                // Solve the optimal global scale/gaps across all collected stretches
                solveUniform(config);

                // Determine the largest scale factor needed across stretches modifying depth
                double needed = 1;
                for (UniformStretch stretch : uniformStretches) {
                    if (stretch.sameSize() && stretch.dims[1]) {
                        needed = Math.max(needed, stretch.count > 0 ? stretch.scale : uniformScale(stretch.type));
                    }
                }
                double cornerScale = scaledCornerPiece ? uniformScale(config.surroundType) : 1;
                boolean depthCovered = needed <= Math.max(1, depthFactor) + 1e-9;
                boolean cornerMatches = Math.abs(cornerPieceScale - cornerScale) < 1e-4;

                // Stop if depth and corner scales have stabilized or max iterations reached
                if ((depthCovered && cornerMatches) || iteration >= 8) {
                    break;
                }
                if (!depthCovered) {
                    // Update depth factor with a safety margin for the next convergence step
                    depthFactor = iteration < 6 ? needed * (1 + tolerance) : maxScale * (1 + tolerance);
                }
                cornerPieceScale = cornerScale;
            }
        } finally {
            // Lock in the final effective depth for the actual placement pass
            maxSurroundingDepth = baseDepth * Math.max(1, depthFactor);
        }
    }

    /**
     * Handles uniform stretch recording (during measurement pass) or furniture instantiation (during placement pass).
     *
     * @param wholeSide  true if this stretch spans the entire logical side
     * @param type       surround algorithm type (1 to 4)
     */
    private void layOutUniform(SidePath side, double from, double wallLength, boolean wholeSide, int type) {
        double maxExtraTol = currentStretchStartTol + currentStretchEndTol;
        double minScale = (type == 3 || type == 4) ? scaleLimits(activeConfig, type)[0] : 1.0;
        double minSingleW = getPieceSurroundingDimensions(primaryTemplate)[0] * minScale;
        double shiftStart = 0.0;

        // If linear length is slightly below minimum single piece width, check if boundary tolerance can bridge it
        if (maxExtraTol > EPSILON && wallLength < minSingleW && wallLength + maxExtraTol >= minSingleW - EPSILON) {
            double needed = minSingleW - wallLength;
            shiftStart = (currentStretchStartTol + currentStretchEndTol) > EPSILON
                    ? needed * (currentStretchStartTol / (currentStretchStartTol + currentStretchEndTol)) : 0.0;
            double usedEnd = needed - shiftStart;
            wallLength += shiftStart + usedEnd;
        }

        // Pass 1: Measurement pass - record stretch parameters and exit
        if (measuringUniform) {
            List<HomePieceOfFurniture> sequence = uniformSequence(wallLength, type);
            double[] prefix = new double[sequence.size() + 1];
            for (int i = 0; i < sequence.size(); i++) {
                prefix[i + 1] = prefix[i] + getPieceSurroundingDimensions(sequence.get(i))[0];
            }
            UniformStretch stretch = new UniformStretch(type, wallLength, wholeSide, sequence, prefix);
            stretch.startShift = shiftStart;
            stretch.dims = dimensionFlags();
            if (type == 1) {
                stretch.minGap = Math.max(-minSingleW * 0.9, 2.0 * getEffectivePieceMargin());
            } else if (type == 2) {
                stretch.fixedJoint = Math.max(-minSingleW * 0.9, 2.0 * getEffectivePieceMargin());
                stretch.minGap = 0.0;
            }
            uniformStretches.add(stretch);
            return;
        }

        // Pass 2: Placement pass - retrieve precomputed stretch solution
        if (uniformIndex >= uniformStretches.size()) {
            return;
        }
        UniformStretch stretch = uniformStretches.get(uniformIndex++);
        from -= stretch.startShift * layoutStretch;
        wallLength = stretch.length;
        boolean[] dims = dimensionFlags();

        // Handle cases where no pieces fit within the solved constraints
        if (stretch.count == 0) {
            if (activeConfig.shortSidesOneObject && wholeSide) {
                if (stretch.sameSize()) {
                    placeUniformSingle(side, from + wallLength / 2, dims, type);
                } else {
                    placeCentered(side, from + wallLength / 2);
                }
            }
            return;
        }

        // Place solved pieces sequentially along the stretch
        double cursor = stretch.startGap;
        for (int i = 0; i < stretch.count; i++) {
            HomePieceOfFurniture piece = stretch.sequence.get(i);
            double[] d = getPieceSurroundingDimensions(piece);
            if (stretch.sameSize()) {
                // Types 3 and 4: Place resized piece according to solved uniform scale
                cursor += placeResized(side, from + cursor, piece, d, stretch.scale, dims);
            } else {
                // Types 1 and 2: Place standard unscaled piece with solved joint spacing
                place(side, from + cursor + d[0] / 2.0, d[1], piece, -1, -1, -1, true, false);
                cursor += d[0];
            }
            cursor += stretch.joint;
        }
    }

    /**
     * Templates for a stretch, as many as could fit at the smallest allowed scale or spacing; always starting
     * with the primary template so the prefix sums don't depend on earlier stretches.
     */
    private List<HomePieceOfFurniture> uniformSequence(double length, int type) {
        double minWidth = getPieceSurroundingDimensions(primaryTemplate)[0];
        double minScale = type == 3 || type == 4 ? scaleLimits(activeConfig, type)[0] : 1;
        double effStep = minWidth * minScale;
        if (type == 1 || type == 2) {
            effStep = Math.max(minWidth * 0.1, minWidth + 2.0 * getEffectivePieceMargin());
        }
        int count = effStep > EPSILON ? (int) Math.min(20000, Math.ceil(length / effStep) + 2) : 0;
        return new ArrayList<>(Collections.nCopies(count, primaryTemplate));
    }

    /**
     * Solves uniform optimization models across all collected stretches:
     * <ul>
     *   <li>Types 3 and 4: Solves global uniform scaling factors independently for Type 3 (enlarge) and Type 4 (reduce).</li>
     *   <li>Types 1 and 2: Solves uniform inter-piece or end gap spacing across stretches.</li>
     * </ul>
     */
    void solveUniform(SurroundConfig config) {
        resetUniformReport(config);
        for (int type = 1; type <= 4; type++) {
            List<UniformStretch> group = new ArrayList<>();
            for (UniformStretch stretch : uniformStretches) {
                if (stretch.type == type) {
                    group.add(stretch);
                }
            }
            if (group.isEmpty()) {
                continue;
            }
            if (type >= 3) {
                double scale = solveSameSize(config, group, type);
                if (type == 3) {
                    uniformScale3 = scale;
                } else {
                    uniformScale4 = scale;
                }
            } else {
                solveSameGaps(config, group);
            }
        }
    }

    /**
     * Same size for types 3 and 4: n objects cover a stretch of length L at scale L / P(n) (P: sum of their
     * widths), and all stretches must share a scale s within uniformMaxSizeDifference. When they can't,
     * UNIFORM_ADJUST takes the narrowest window of scales and absorbs the rest in joints up to uniformMaxJoint,
     * and UNIFORM_CENTER or UNIFORM_EMPTY keep s and center or empty the stretches that don't fit.
     *
     * @return the common scale s
     */
    private double solveSameSize(SurroundConfig config, List<UniformStretch> group, int type) {
        double[] limits = scaleLimits(config, type);
        double minScale = limits[0], maxScale = limits[1];
        double band = Math.log(1 + Math.max(0, config.uniformMaxSizeDifference) / 100.0) / 2;
        List<UniformStretch> eligible = new ArrayList<>();
        for (UniformStretch stretch : group) {
            stretch.count = 0;
            stretch.scale = 1;
            stretch.joint = 0;
            stretch.startGap = 0;
            // Only consider stretches that can fit at least one piece within the scale limits
            if (stretch.prefix.length > 1 && stretch.length >= minScale * stretch.prefix[1] - EPSILON) {
                eligible.add(stretch);
            }
        }
        boolean adjust = config.uniformFallback == SurroundConfig.UNIFORM_ADJUST;
        double commonScale = adjust
                ? windowScale(eligible, minScale, maxScale, band)
                : coverageScale(eligible, minScale, maxScale, band);

        double maxJoint = Math.max(0, config.uniformMaxJoint);
        for (UniformStretch stretch : eligible) {
            // Find the piece count whose exact scale is closest to the common scale within limits
            int n = 0;
            double deviation = Double.MAX_VALUE;
            for (int k = 1; k < stretch.prefix.length; k++) {
                double scale = stretch.length / stretch.prefix[k];
                if (scale < minScale - 1e-9) {
                    break;
                }
                double d = Math.abs(Math.log(scale / commonScale));
                if (scale <= maxScale + 1e-9 && d < deviation) {
                    n = k;
                    deviation = d;
                }
            }
            // Case 1: Exact fit within tolerance band
            if (n > 0 && deviation <= band + 1e-9) {
                stretch.count = n;
                stretch.scale = stretch.length / stretch.prefix[n];   // covers stretch exactly
                continue;
            }
            // Case 2: Out of band, but tiny inter-piece joints can absorb the residual difference
            double jointScale = Math.max(minScale, Math.min(maxScale, commonScale * Math.exp(band)));
            int fit = 0;
            while (fit + 1 < stretch.prefix.length && jointScale * stretch.prefix[fit + 1] <= stretch.length + 1e-6) {
                fit++;
            }
            double rest = fit > 0 ? stretch.length - jointScale * stretch.prefix[fit] : Double.MAX_VALUE;
            if (fit >= 2 && rest / (fit - 1) <= maxJoint + 1e-9) {
                stretch.count = fit;
                stretch.scale = jointScale;
                stretch.joint = Math.max(0, rest / (fit - 1));
                continue;
            }
            if (fit == 1 && rest / 2 <= maxJoint + 1e-9) {
                stretch.count = 1;
                stretch.scale = jointScale;
                stretch.startGap = Math.max(0, rest / 2);
                continue;
            }
            // Case 3: Adjust fallback - scale piece as needed outside tolerance band
            if (adjust && n > 0) {
                stretch.count = n;
                stretch.scale = stretch.length / stretch.prefix[n];
                config.sizeAdjustedStretches++;
                continue;
            }
            // Case 4: Center fallback - place integer count at common scale centered with symmetrical end gaps
            int centred = 0;
            while (centred + 1 < stretch.prefix.length && commonScale * stretch.prefix[centred + 1] <= stretch.length + 1e-6) {
                centred++;
            }
            if (config.uniformFallback != SurroundConfig.UNIFORM_EMPTY && centred > 0) {
                stretch.count = centred;
                stretch.scale = commonScale;
                stretch.startGap = Math.max(0, (stretch.length - commonScale * stretch.prefix[centred]) / 2);
                config.sizeCenteredStretches++;
                continue;
            }
            // Case 5: Empty fallback - stretch remains uncovered
            config.sizeUnfittedStretches++;
        }

        // Calculate and record diagnostic scale spread statistics
        double smallest = Double.MAX_VALUE, largest = 0;
        for (UniformStretch stretch : eligible) {
            if (stretch.count > 0) {
                smallest = Math.min(smallest, stretch.scale);
                largest = Math.max(largest, stretch.scale);
            }
        }
        config.sizeSpread = Math.max(config.sizeSpread, largest > 0 ? (largest / smallest - 1) * 100 : 0);
        return commonScale;
    }

    /**
     * The common scale among the allowed scales L / P(n) of every stretch: the window holding one of each that
     * fits the band and is closest to 1, or else the geometric middle of the narrowest window.
     */
    private static double windowScale(List<UniformStretch> stretches, double minScale, double maxScale, double band) {
        List<double[]> values = new ArrayList<>();   // entries: {log(scale), stretchIndex}
        int participants = 0;
        for (UniformStretch stretch : stretches) {
            boolean any = false;
            for (int k = 1; k < stretch.prefix.length; k++) {
                double scale = stretch.length / stretch.prefix[k];
                if (scale < minScale - 1e-9) {
                    break;
                }
                if (scale <= maxScale + 1e-9) {
                    values.add(new double[]{Math.log(scale), participants});
                    any = true;
                }
            }
            if (any) {
                participants++;
            }
        }
        if (participants == 0) {
            return Math.max(minScale, Math.min(maxScale, 1));
        }
        // Sort candidate scales in ascending logarithmic order
        values.sort((x, y) -> Double.compare(x[0], y[0]));
        int[] inWindow = new int[participants];
        int covered = 0, left = 0;
        double bestWidth = Double.MAX_VALUE, bestMiddle = 0;
        double bestFitting = Double.NaN;

        // Two-pointer sliding window over sorted candidate scale values
        for (double[] right : values) {
            if (inWindow[(int) right[1]]++ == 0) {
                covered++;
            }
            while (covered == participants) {
                double low = values.get(left)[0], width = right[0] - low;
                double middle = (right[0] + low) / 2;
                if (width < bestWidth - 1e-12 || (width < bestWidth + 1e-12 && Math.abs(middle) < Math.abs(bestMiddle))) {
                    bestWidth = width;
                    bestMiddle = middle;
                }
                if (width <= 2 * band + 1e-12) {
                    // Candidate fits within tolerance band; pick scale closest to 1.0 (log = 0)
                    double closest = Math.max(right[0] - band, Math.min(low + band, 0));
                    if (Double.isNaN(bestFitting) || Math.abs(closest) < Math.abs(bestFitting)) {
                        bestFitting = closest;
                    }
                }
                if (--inWindow[(int) values.get(left)[1]] == 0) {
                    covered--;
                }
                left++;
            }
        }
        return Math.exp(Double.isNaN(bestFitting) ? bestMiddle : bestFitting);
    }

    /**
     * Solves for the common scale that maximizes the total linear perimeter length covered
     * within the tolerance band, breaking ties by choosing the scale closest to 1.0.
     */
    private static double coverageScale(List<UniformStretch> stretches, double minScale, double maxScale, double band) {
        List<Double> candidates = new ArrayList<>();
        candidates.add(minScale);
        candidates.add(maxScale);
        for (UniformStretch stretch : stretches) {
            for (int n = 1; n < stretch.prefix.length; n++) {
                double exact = stretch.length / stretch.prefix[n];
                if (exact < minScale * Math.exp(-band) - EPSILON) {
                    break;
                }
                for (double candidate : new double[]{exact, exact * Math.exp(-band), exact * Math.exp(band)}) {
                    if (candidate >= minScale - 1e-12 && candidate <= maxScale + 1e-12) {
                        candidates.add(candidate);
                    }
                }
            }
        }
        double bestScale = Math.max(minScale, Math.min(maxScale, 1)), bestCovered = -1, bestDistance = Double.MAX_VALUE;
        for (double candidate : candidates) {
            double covered = 0;
            for (UniformStretch stretch : stretches) {
                if (Math.abs(scaleDeviation(stretch, candidate, closestCount(stretch, candidate))) <= band + 1e-9) {
                    covered += stretch.length;
                }
            }
            double distance = Math.abs(Math.log(candidate));
            if (covered > bestCovered + 1e-6 || (covered > bestCovered - 1e-6 && distance < bestDistance - 1e-12)) {
                bestScale = candidate;
                bestCovered = covered;
                bestDistance = distance;
            }
        }
        return bestScale;
    }

    /**
     * Finds the piece count whose total scaled width is closest to the target stretch length
     * using binary search (bisection) over prefix sums.
     */
    private static int closestCount(UniformStretch stretch, double scale) {
        int lo = 1, hi = stretch.prefix.length - 1;
        if (hi < 1) {
            return 0;
        }
        // Largest n with scale * P(n) <= length, by bisection (P increases with n)
        while (lo < hi) {
            int mid = (lo + hi + 1) / 2;
            if (scale * stretch.prefix[mid] <= stretch.length) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        int best = lo;
        if (lo + 1 < stretch.prefix.length
                && Math.abs(scaleDeviation(stretch, scale, lo + 1)) < Math.abs(scaleDeviation(stretch, scale, lo))) {
            best = lo + 1;
        }
        return best;
    }

    /**
     * Computes the logarithmic deviation between the candidate scale and the exact scale required
     * to cover the stretch with n pieces: ln(L / (scale * P(n))).
     */
    private static double scaleDeviation(UniformStretch stretch, double scale, int n) {
        return n < 1 ? Double.MAX_VALUE : Math.log(stretch.length / (scale * stretch.prefix[n]));
    }

    /**
     * Solves uniform gap spacing (Types 1 and 2) across a group of perimeter stretches.
     * Keeps piece dimensions unscaled and adjusts inter-piece spacing (Type 1) or start/end margins (Type 2)
     * so that gaps remain consistent across all stretches within uniformMaxGapDifference tolerance.
     * Uses a sliding window over candidate gap values to minimize gap variance while placing as many pieces as possible.
     */
    private void solveSameGaps(SurroundConfig config, List<UniformStretch> group) {
        double tolerance = Math.max(0, config.uniformMaxGapDifference);
        double maxWidth = 0;
        for (HomePieceOfFurniture piece : selectedFurniture) {
            maxWidth = Math.max(maxWidth, getPieceSurroundingDimensions(piece)[0]);
        }
        // Determine maximum piece capacity and resulting gap limits for each stretch
        double cap = 0;
        int[] fit = new int[group.size()];
        for (int j = 0; j < group.size(); j++) {
            UniformStretch stretch = group.get(j);
            stretch.count = 0;
            stretch.joint = 0;
            stretch.startGap = 0;
            int n = 0;
            while (n + 1 < stretch.prefix.length
                    && stretch.occupiedWidth(n + 1) + stretch.minGap * Math.max(0, stretch.gapCount(n + 1)) <= stretch.length + 1e-6) {
                n++;
            }
            fit[j] = n;
            if (n > 0 && !Double.isNaN(stretch.gap(n))) {
                cap = Math.max(cap, stretch.gap(n));
            }
        }
        cap += maxWidth;
        List<double[]> values = new ArrayList<>();   // entries: {gapDimension, participantIndex}
        List<UniformStretch> participants = new ArrayList<>();
        for (int j = 0; j < group.size(); j++) {
            UniformStretch stretch = group.get(j);
            boolean any = false;
            for (int n = fit[j]; n >= 1; n--) {
                double gap = stretch.gap(n);
                if (Double.isNaN(gap)) {
                    continue;
                }
                if (gap > cap + 1e-9) {
                    break;
                }
                values.add(new double[]{gap, participants.size()});
                any = true;
            }
            if (any) {
                participants.add(stretch);
            } else if (fit[j] > 0) {
                // Single piece with no variable spaces to share: center it along the stretch
                stretch.count = fit[j];
                stretch.startGap = Math.max(0, (stretch.length - stretch.prefix[fit[j]]) / 2);
            }
        }
        if (participants.isEmpty()) {
            return;
        }
        // Sort candidate gaps and evaluate optimal window
        values.sort((x, y) -> Double.compare(x[0], y[0]));
        int[] inWindow = new int[participants.size()];
        int covered = 0, left = 0;
        double bestWidth = Double.MAX_VALUE, bestMiddle = 0, bestFitting = Double.NaN;
        for (double[] right : values) {
            if (inWindow[(int) right[1]]++ == 0) {
                covered++;
            }
            while (covered == participants.size()) {
                double low = values.get(left)[0], width = right[0] - low;
                if (width < bestWidth - 1e-9) {
                    bestWidth = width;
                    bestMiddle = (right[0] + low) / 2;
                }
                if (width <= tolerance + 1e-9 && Double.isNaN(bestFitting)) {
                    bestFitting = (right[0] + low) / 2;   // choose narrowest viable gaps
                }
                if (--inWindow[(int) values.get(left)[1]] == 0) {
                    covered--;
                }
                left++;
            }
        }
        double common = Double.isNaN(bestFitting) ? bestMiddle : bestFitting;
        double smallest = Double.MAX_VALUE, largest = -Double.MAX_VALUE;
        for (int j = 0; j < participants.size(); j++) {
            UniformStretch stretch = participants.get(j);
            int best = 0;
            double bestGap = Double.NaN;
            for (double[] value : values) {
                if ((int) value[1] == j && (Double.isNaN(bestGap) || Math.abs(value[0] - common) < Math.abs(bestGap - common))) {
                    bestGap = value[0];
                }
            }
            for (int n = 1; n < stretch.prefix.length; n++) {
                if (!Double.isNaN(stretch.gap(n)) && Math.abs(stretch.gap(n) - bestGap) < 1e-9) {
                    best = n;
                    break;
                }
            }
            stretch.count = best;
            stretch.joint = stretch.type == 1 ? bestGap : stretch.fixedJoint;
            stretch.startGap = stretch.type == 1 ? 0 : bestGap;
            smallest = Math.min(smallest, bestGap);
            largest = Math.max(largest, bestGap);
            if (Math.abs(bestGap - common) > tolerance / 2 + 1e-9) {
                config.gapAdjustedStretches++;
            }
        }
        config.gapSpread = Math.max(config.gapSpread, largest - smallest);
    }

    /**
     * Fallback placement for short sides: places a single template object at the solved uniform scale
     * centered at arc-length coordinate s.
     *
     * @param dims boolean array [width, depth, height] indicating which dimensions scale
     * @param type surround algorithm type (3 or 4)
     */
    private void placeUniformSingle(SidePath side, double s, boolean[] dims, int type) {
        HomePieceOfFurniture piece = selectedFurniture.get(0);
        double[] d = getPieceSurroundingDimensions(piece);
        double scale = uniformScale(type);
        double across = dims[1] ? scale : 1;
        float[] pieceDims = pieceDimensionsFor(piece, scale, across);
        place(side, s, d[1] * across, piece, pieceDims[0], pieceDims[1],
                (float) (piece.getHeight() * (dims[2] ? scale : 1)), true, true);
    }

    /**
     * Pastes the placed pieces on the level of their face (switching the selected level and restoring it after),
     * grouped per side, all together per level, or loose ({@code groupingMode}).
     */
    private void commitPlacedFurniture(SurroundConfig config) {
        if (newlyPlacedFurniture.isEmpty()) {
            return;
        }
        Map<Level, List<HomePieceOfFurniture>> toPaste = new LinkedHashMap<>();
        String baseName = primaryTemplate.getName();

        // 1. Organize pieces by grouping mode into per-level lists
        if (config.groupingMode == SurroundConfig.GROUP_PER_SIDE) {
            int sideNum = 1;
            for (List<HomePieceOfFurniture> sideList : sidePlacedGroups.values()) {
                if (!sideList.isEmpty()) {
                    HomePieceOfFurniture item = sideList.size() == 1 ? sideList.get(0)
                            : new HomeFurnitureGroup(new ArrayList<>(sideList), (baseName != null ? baseName : "Side") + " - " + (sideNum++));
                    toPaste.computeIfAbsent(placedLevels.get(sideList.get(0)), k -> new ArrayList<>()).add(item);
                }
            }
        } else {
            for (HomePieceOfFurniture piece : newlyPlacedFurniture) {
                toPaste.computeIfAbsent(placedLevels.get(piece), k -> new ArrayList<>()).add(piece);
            }
            if (config.groupingMode == SurroundConfig.GROUP_ALL) {
                for (Map.Entry<Level, List<HomePieceOfFurniture>> entry : toPaste.entrySet()) {
                    if (entry.getValue().size() > 1) {
                        entry.setValue(Collections.<HomePieceOfFurniture>singletonList(new HomeFurnitureGroup(
                                new ArrayList<>(entry.getValue()), baseName != null ? baseName : "Surround")));
                    }
                }
            }
        }

        // 2. Commit items to the home model, preserving active level selection
        Level selectedLevel = home != null ? home.getSelectedLevel() : null;
        try {
            for (Map.Entry<Level, List<HomePieceOfFurniture>> entry : toPaste.entrySet()) {
                if (home != null && entry.getKey() != null && home.getSelectedLevel() != entry.getKey()) {
                    home.setSelectedLevel(entry.getKey());
                }
                if (homeController != null) {
                    homeController.paste(entry.getValue());
                } else if (home != null) {
                    for (HomePieceOfFurniture p : entry.getValue()) {
                        p.setLevel(entry.getKey());
                        home.addPieceOfFurniture(p);
                    }
                }
            }
        } finally {
            if (home != null && home.getSelectedLevel() != selectedLevel) {
                home.setSelectedLevel(selectedLevel);
            }
        }
    }

    // ---- Sides Geometry Construction & Traversal Ordering ----

    /**
     * Constructs and parameterizes the list of {@link SidePath} perimeters for the given face
     * in active traversal order (either native polygon order or reversed order).
     * Ensures that outward/inward normal vectors (uy, -ux) consistently point toward the side where
     * furniture pieces will be placed. Computes curved side end spans taking into account perpendicular
     * edge distance offsets. Supports 0-degree collinear side merging (Item 11).
     */
    List<SidePath> buildSides(Room face) {
        List<SidePath> sides = buildSidePaths(face);
        for (SidePath side : sides) {
            // The corners of a curve follow the chord of its end objects, on the curve where they go
            // when they're moved by a distance from the edge
            double offset = activeConfig != null ? activeConfig.distanceOffset : 0;
            double backLength = side.isCurved() && offset != 0 ? side.frontLength(0, side.length, offset, 0) : side.length;
            side.endSpan = maxSurroundingWidth * (backLength > 1e-6 ? side.length / backLength : 1);
        }
        return sides;
    }

    /**
     * Decomposes the vertices of a Room boundary into logical polyline sides and handles
     * orientation reversal when placing items on the interior vs exterior.
     * If traversal is reversed, reverses each sub-polyline vertex sequence and rotates the
     * overall side ordering to preserve vertex alignment with original polygon indices.
     */
    private List<SidePath> buildSidePaths(Room face) {
        float[][] points = face.getPoints();
        boolean mergeCollinear = activeConfig != null && activeConfig.mergeCollinearSides;
        List<int[]> logical = SideModel.logicalSides(points, mergeCollinear);
        boolean reversed = isTraversalReversed(face, interiorFor(face));
        List<SidePath> sides = new ArrayList<>();
        if (!reversed) {
            for (int i = 0; i < logical.size(); i++) {
                sides.add(new SidePath(SideModel.polyline(points, logical.get(i)), i, false));
            }
        } else {
            // Traverse logical sides in reverse order and reverse each sub-polyline's point array
            for (int i = logical.size() - 1; i >= 0; i--) {
                float[][] poly = SideModel.polyline(points, logical.get(i));
                float[][] backwards = new float[poly.length][];
                for (int k = 0; k < poly.length; k++) {
                    backwards[k] = poly[poly.length - 1 - k];
                }
                sides.add(new SidePath(backwards, i, true));
            }
            // Rotate the collection so that the starting vertex aligns with the original polygon start
            int first = reversedFirstSide(points, logical);
            int startIndex = 0;
            for (int t = 0; t < sides.size(); t++) {
                if (sides.get(t).index == first) {
                    startIndex = t;
                }
            }
            Collections.rotate(sides, -startIndex);
        }
        if (interiorFor(face) && !sides.isEmpty()) {
            // Inside, the outline is walked from the vertex where it starts outside, so that walked
            // backwards (in the direction shown) the objects start at the same place
            int n = points.length;
            int start = exteriorStartVertex(face, points, logical);
            for (int t = 0; t < sides.size(); t++) {
                SidePath side = sides.get(t);
                int[] ls = logical.get(side.index);
                if ((side.reversed ? (ls[0] + ls[1]) % n : ls[0]) == start) {
                    Collections.rotate(sides, -t);
                    break;
                }
            }
        }
        return sides;
    }

    /** Returns the index of the point where the exterior traversal of the room starts. */
    private static int exteriorStartVertex(Room room, float[][] points, List<int[]> logical) {
        if (!isShownReversed(room)) {
            return logical.get(0)[0];
        }
        int[] first = logical.get(reversedFirstSide(points, logical));
        return (first[0] + first[1]) % points.length;
    }

    /**
     * Determines whether interior placement mode applies to the specified Room face.
     * Open walls (e.g. wall chains that do not form a closed loop) are always treated as exterior.
     *
     * @return true for interior placement; false for exterior
     */
    private boolean interiorFor(Room room) {
        return activeConfig.interior && !activeConfig.alwaysExterior.contains(room);
    }

    /**
     * Whether the points of the room are walked backwards so that the normal (uy, -ux) points inside (interior)
     * or outside (exterior), whatever the winding of the polygon.
     */
    static boolean isTraversalReversed(Room room, boolean interior) {
        boolean clockWise = room.isClockwise();
        return (!clockWise && !interior) || (clockWise && interior);
    }

    /** Returns the index of the logical side walked first when the points are walked backwards. */
    private static int reversedFirstSide(float[][] points, List<int[]> logical) {
        int n = points.length;
        int wanted = ((n - 2) % n + n) % n;
        int first = 0;
        for (int i = 0; i < logical.size(); i++) {
            int[] side = logical.get(i);
            for (int k = 0; k < side[1]; k++) {
                if ((side[0] + k) % n == wanted) {
                    first = i;
                }
            }
        }
        return first;
    }

    /**
     * Returns the default start of Type 7 as {logical side index, distance from the first point of that side}:
     * the vertex where the exterior traversal starts, which is also the start used inside.
     */
    static double[] defaultType7Start(Room room, boolean mergeCollinear) {
        float[][] points = room.getPoints();
        List<int[]> logical = SideModel.logicalSides(points, mergeCollinear);
        if (logical.isEmpty() || !isShownReversed(room)) {
            return new double[]{0, 0};
        }
        int first = reversedFirstSide(points, logical);
        return new double[]{first, SideModel.length(SideModel.polyline(points, logical.get(first)))};
    }

    /**
     * Whether the points of the room are walked backwards in the direction shown to the user: the one of the
     * exterior, so that inside and outside the objects follow the outline in the same direction.
     */
    static boolean isShownReversed(Room room) {
        return isTraversalReversed(room, false);
    }

    /**
     * Whether the objects of the room go from the end to the start of the traversal: inside, the traversal goes
     * against the direction shown, so the reverse direction option is inverted there.
     */
    private boolean isReverseDirection(Room room) {
        return activeConfig.reverseDirection != interiorFor(room);
    }

    /**
     * Checks whether the specified side is excluded by user selection or marked as a forbidden
     * interior shared partition wall.
     */
    private boolean isSideExcluded(Room face, SidePath side, int sideCount) {
        if (face == null) {
            return false;
        }
        SurroundConfig.SideEnds ends = activeConfig.sideEnds.get(face);
        if (ends == null || ends.skipped.length != sideCount) {
            return false;
        }
        return ends.skipped[side.index] || (side.index < ends.forbidden.length && ends.forbidden[side.index]);
    }

    // ---- Free Stretches & Obstacle Clipping (Doors, Windows, Furniture, Contact Zones) ----

    /**
     * Returns the active lateral fit tolerance for door and window openings.
     *
     * @return fit tolerance in cm (or 0.0 if disabled)
     */
    private double effectiveGapFitTolerance() {
        return fitTolerance(activeConfig, true);
    }

    /**
     * Returns the active lateral fit tolerance for existing furniture collisions.
     *
     * @return fit tolerance in cm (or 0.0 if disabled)
     */
    private double effectiveCollisionFitTolerance() {
        return fitTolerance(activeConfig, false);
    }

    /**
     * Resolves the allowable fit tolerance for obstacle boundaries.
     * In Type 4 (reduce), fit tolerance is only permitted if uniform sizing is enabled.
     *
     * @param gap    true for door/window openings; false for furniture collisions
     * @return allowable tolerance (cm)
     */
    static double fitTolerance(SurroundConfig config, boolean gap) {
        boolean on = gap ? config.splitAtObstacles && config.enableGapFitTolerance
                : config.avoidCollisions && config.enableCollisionFitTolerance;
        return on && (config.surroundType != 4 || config.uniformSize)
                ? Math.max(0.0, gap ? config.gapFitTolerance : config.collisionFitTolerance) : 0.0;
    }

    /**
     * Identifies all architectural pieces (doors, windows, pre-existing furniture) at the current
     * floor level that physically intersect the vertical height range of the template objects.
     */
    private List<HomePieceOfFurniture> obstaclesFor(Room face) {
        if (!activeConfig.splitAtObstacles && !activeConfig.avoidCollisions) {
            return Collections.emptyList();
        }
        float[] heights = heightRange(selectedFurniture);
        List<HomePieceOfFurniture> obstacles = new ArrayList<>();
        for (HomePieceOfFurniture piece : existingHomeFurniture) {
            if (interrupts(activeConfig, piece, face.getLevel(), heights)) {
                obstacles.add(piece);
            }
        }
        return obstacles;
    }

    /**
     * Computes the global vertical elevation interval [bottom, top] across all selected template pieces.
     *
     * @return array containing {lowestElevation, highestElevation + height}
     */
    private static float[] heightRange(List<HomePieceOfFurniture> objects) {
        float bottom = Float.MAX_VALUE, top = -Float.MAX_VALUE;
        for (HomePieceOfFurniture piece : objects) {
            bottom = Math.min(bottom, piece.getElevation());
            top = Math.max(top, piece.getElevation() + piece.getHeight());
        }
        return bottom > top ? new float[]{0f, 250f} : new float[]{bottom, top};
    }

    /**
     * Determines whether a candidate furniture piece vertically collides with the template pieces,
     * taking into account door/window filters, custom height ranges, and vertical clearance tolerances.
     */
    private static boolean interrupts(SurroundConfig config, HomePieceOfFurniture piece, Level level, float[] heights) {
        boolean isGap = piece.isDoorOrWindow();
        if (isGap ? !config.splitAtObstacles || (!config.respectWindows && isWindow(piece)) : !config.avoidCollisions) {
            return false;
        }
        if (level != null && piece.getLevel() != null && !piece.isAtLevel(level)) {
            return false;
        }
        boolean chosenHeights = isGap && config.gapHeightRange;
        double low = chosenHeights ? config.gapMinHeight : heights[0];
        double high = chosenHeights ? config.gapMaxHeight : heights[1];
        double zTol = chosenHeights ? 0.1 : Math.min((high - low) * 0.45, fitTolerance(config, isGap) + 0.1);
        float z0 = piece.getElevation(), z1 = z0 + piece.getHeight();
        return z1 > low + zTol && z0 < high - zTol;
    }

    /**
     * Calculates the linear obstacle intervals along each side of a face for interactive display in SideEndsDialog.
     *
     * @param objectDepth template depth in cm
     * @return list of lists of blocked intervals {start, end, obstacleType (0=opening, 1=furniture)}
     */
    static List<List<double[]>> obstacleStretches(SurroundConfig config, Room face, List<float[][]> sides, boolean reversed,
            List<HomePieceOfFurniture> pieces, List<HomePieceOfFurniture> objects, double objectDepth) {
        return obstacleStretches( null, config, face, sides, reversed, pieces, objects, objectDepth);
    }

    /**
     * Calculates the exact linear obstacle sub-stretches along each side of a face for interactive display
     * in SideEndsPanel, classified into 5 distinct categories:
     * <ul>
     *   <li>0 = doors</li>
     *   <li>1 = windows</li>
     *   <li>2 = furniture / objects</li>
     *   <li>3 = walls touching or overlapping the side</li>
     *   <li>4 = floors touching or overlapping the side</li>
     * </ul>
     * Only the exact sub-interval where each collision occurs is returned (never coloring the entire side
     * because of the room's own perimeter wall or a parallel wall running along the back of the side).
     */
    static List<List<double[]>> obstacleStretches(Home home, SurroundConfig config, Room face, List<float[][]> sides, boolean reversed,
            List<HomePieceOfFurniture> pieces, List<HomePieceOfFurniture> objects, double objectDepth) {
        float[] heights = heightRange(objects);
        SurroundConfig.SideEnds ends = config != null && config.sideEnds != null ? config.sideEnds.get(face) : null;
        List<Room> sourceRooms = config != null && config.faceSourceRooms != null ? config.faceSourceRooms.get(face) : null;
        Level roomLevel = face != null ? face.getLevel() : null;
        double colTol = config != null ? fitTolerance(config, false) : 0.0;
        double gapTol = config != null ? fitTolerance(config, true) : 0.0;
        double offset = config != null ? config.distanceOffset : 0.0;
        List<List<double[]>> stretches = new ArrayList<>(sides.size());
        for (int i = 0; i < sides.size(); i++) {
            List<double[]> doorIntervals = new ArrayList<>();
            List<double[]> windowIntervals = new ArrayList<>();
            List<double[]> furnitureIntervals = new ArrayList<>();
            List<double[]> wallIntervals = new ArrayList<>();
            List<double[]> floorIntervals = new ArrayList<>();

            float[][] points = sides.get(i);
            if (reversed) {
                points = points.clone();
                Collections.reverse(java.util.Arrays.asList(points));
            }
            SidePath side = new SidePath(points, i, reversed);

            // 0 (doors), 1 (windows), and 2 (furniture): always show doors, windows, and furniture in the
            // Excluded Stretches editor so the user can see and click their exact sub-stretches regardless of toggles
            for (HomePieceOfFurniture piece : pieces) {
                if (roomLevel != null && piece.getLevel() != null && !piece.isAtLevel(roomLevel)) {
                    continue;
                }
                boolean isGap = piece.isDoorOrWindow();
                boolean chosenHeights = isGap && config != null && config.gapHeightRange;
                double low = chosenHeights ? config.gapMinHeight : heights[0];
                double high = chosenHeights ? config.gapMaxHeight : heights[1];
                double tol = isGap ? gapTol : colTol;
                double zTol = chosenHeights ? 0.1 : Math.min((high - low) * 0.45, tol + 0.1);
                float z0 = piece.getElevation(), z1 = z0 + piece.getHeight();
                if (z1 <= low + zTol || z0 >= high - zTol) {
                    continue;
                }
                List<double[]> targetList = isGap ? (isWindow(piece) ? windowIntervals : doorIntervals) : furnitureIntervals;
                for (double[] b : blockedAlong(side, piece, 0, side.length, objectDepth, offset, tol)) {
                    double start = reversed ? side.length - b[1] : b[0];
                    double end = reversed ? side.length - b[0] : b[1];
                    if (end - start > 1.0) {
                        targetList.add(new double[]{start, end});
                    }
                }
            }

            // 3: Walls touching or overlapping the side (excluding any wall that runs parallel along this side itself!)
            if (ends != null && i < ends.overlapSegments.size()) {
                for (double[] ov : ends.overlapSegments.get(i)) {
                    if (ov[1] - ov[0] > 1.0) {
                        wallIntervals.add(new double[]{ov[0], ov[1]});
                    }
                }
            }
            if (home != null && home.getWalls() != null) {
                for (Wall wall : home.getWalls()) {
                    if (roomLevel != null && wall.getLevel() != null && !wall.isAtLevel(roomLevel)) {
                        continue;
                    }
                    for (double[] b : blockedPolygonAlong(side, wall.getPoints(), 0, side.length, objectDepth, offset, colTol, true)) {
                        double start = reversed ? side.length - b[1] : b[0];
                        double end = reversed ? side.length - b[0] : b[1];
                        if (end - start > 1.0) {
                            wallIntervals.add(new double[]{start, end});
                        }
                    }
                }
            }

            // 4: Floors (rooms) touching or overlapping the side
            if (home != null && home.getRooms() != null) {
                for (Room otherRoom : home.getRooms()) {
                    if (otherRoom == face || (sourceRooms != null && sourceRooms.contains(otherRoom))) {
                        continue;
                    }
                    if (roomLevel != null && otherRoom.getLevel() != null && !otherRoom.isAtLevel(roomLevel)) {
                        continue;
                    }
                    for (double[] b : blockedPolygonAlong(side, otherRoom.getPoints(), 0, side.length, objectDepth, offset, colTol, false)) {
                        double start = reversed ? side.length - b[1] : b[0];
                        double end = reversed ? side.length - b[0] : b[1];
                        if (end - start > 1.0) {
                            floorIntervals.add(new double[]{start, end});
                        }
                    }
                }
            }

            List<double[]> sideStretches = new ArrayList<>();
            @SuppressWarnings("unchecked")
            List<double[]>[] byKind = new List[]{doorIntervals, windowIntervals, furnitureIntervals, wallIntervals, floorIntervals};
            for (int kind = 0; kind < byKind.length; kind++) {
                SurroundTarget.mergeIntervals(byKind[kind]);
                for (double[] iv : byKind[kind]) {
                    sideStretches.add(new double[]{iv[0], iv[1], kind});
                }
            }
            stretches.add(sideStretches);
        }
        return stretches;
    }

    /**
     * Determines whether an architectural opening is a window (starts elevated above floor level > 1 cm)
     * or a door (starts flush with the floor).
     */
    static boolean isWindow(HomePieceOfFurniture piece) {
        return piece.isDoorOrWindow() && piece.getElevation() > 1f;
    }

    /**
     * Stretches of [from, to] free of door and window openings, furniture (with "Avoid collisions"), contacts
     * between walls and excluded stretches, as
     * {start, end, startsAtObstacle (1/0), endsAtObstacle (1/0), startFitTol, endFitTol}.
     */
    private List<double[]> freeStretches(Room face, SidePath side, double from, double to, double stripDepth, double offset) {
        List<double[]> stretches = new ArrayList<>();
        if (to - from <= EPSILON) {
            return stretches;
        }
        double gapTol = effectiveGapFitTolerance();
        double colTol = effectiveCollisionFitTolerance();
        // Each blocked entry: {startAlongSide, endAlongSide, lateralTolAllowed}
        List<double[]> blocked = new ArrayList<>();

        // 1. Doors and windows ("Respect door and window openings") and existing furniture ("Avoid collisions"), Item 6
        for (HomePieceOfFurniture obstacle : currentObstacles) {
            blocked.addAll(blockedAlong(side, obstacle, from, to, stripDepth, offset, obstacle.isDoorOrWindow() ? gapTol : colTol));
        }

        // 2. Partial wall-to-wall contact/overlap zones and user-excluded segments (Item 12)
        if (face != null) {
            SurroundConfig.SideEnds ends = activeConfig.sideEnds.get(face);
            if (ends != null && side.index < ends.skipped.length) {
                if (side.index < ends.overlapSegments.size()) {
                    addBlockedIntervals(side, ends.overlapSegments.get(side.index), blocked, colTol);
                }
                if (activeConfig.enableExcludedSegments && side.index < ends.excludedSegments.size()) {
                    addBlockedIntervals(side, ends.excludedSegments.get(side.index), blocked, 0.0);
                }
            }
        }

        // Sort blocked intervals in ascending order along side coordinate
        blocked.sort((a, b) -> Double.compare(a[0], b[0]));
        double cursor = from;
        boolean startsAtObstacle = false;
        double prevEndTol = 0.0;
        for (double[] b : blocked) {
            if (b[1] <= cursor) {
                if (b[1] >= cursor - EPSILON) {
                    prevEndTol = Math.min(prevEndTol, b[2]);
                }
                continue;
            }
            if (b[0] >= to) {
                break;
            }
            // If there is a free interval between cursor and obstacle start, record stretch
            if (b[0] - cursor > 1 || (b[0] + b[2]) - (cursor - prevEndTol) > 1) {
                stretches.add(new double[]{cursor, b[0], startsAtObstacle ? 1 : 0, 1, prevEndTol, b[2]});
            }
            if (b[1] > cursor) {
                cursor = b[1];
                prevEndTol = b[2];
            }
            startsAtObstacle = true;
        }
        // Record final trailing free stretch up to 'to' coordinate
        if (to - cursor > 1 || (!startsAtObstacle && to > cursor) || (to - (cursor - prevEndTol) > 1 && prevEndTol > 0)) {
            stretches.add(new double[]{cursor, to, startsAtObstacle ? 1 : 0, 0, prevEndTol, 0.0});
        }
        return stretches;
    }

    /**
     * Where along the side an obstacle crosses the strip of the objects (SAT on its footprint), as
     * {start, end, lateral tolerance}; the tolerance shrinks the front of the strip by up to tol (keeping 20%).
     */
    private static List<double[]> blockedAlong(SidePath side, HomePieceOfFurniture obstacle, double from, double to,
            double stripDepth, double offset, double tol) {
        List<double[]> blocked = new ArrayList<>();
        boolean isGap = obstacle.isDoorOrWindow();
        float[][] polygon = obstacle.getPoints();
        // Depth tolerance: shrink the front of the detection band by up to tol (keeping at least 20% of stripDepth)
        double effStripDepth = Math.max(stripDepth * 0.2, stripDepth - tol);
        double back = isGap ? Math.max(30.0, -offset) : (0.5 - offset - Math.min(tol * 0.5, stripDepth * 0.3));
        double front = isGap ? Math.max(0, offset) + effStripDepth : offset + effStripDepth;
        if (front <= -back + EPSILON) {
            return blocked;
        }
        for (int i = 0; i < side.segmentCount(); i++) {
            double segmentLength = side.cumulative[i + 1] - side.cumulative[i];
            if (segmentLength < EPSILON) {
                continue;
            }
            double[] u = side.direction(i);
            double nx = u[1], ny = -u[0];
            double lo = i == 0 ? Math.min(0, from) : 0;
            double hi = i == side.segmentCount() - 1 ? Math.max(segmentLength, to - side.cumulative[i]) : segmentLength;
            double[] p0 = {side.points[i][0] + u[0] * lo, side.points[i][1] + u[1] * lo};
            double[] p1 = {side.points[i][0] + u[0] * hi, side.points[i][1] + u[1] * hi};
            float[][] band = {
                {(float) (p0[0] - nx * back), (float) (p0[1] - ny * back)},
                {(float) (p1[0] - nx * back), (float) (p1[1] - ny * back)},
                {(float) (p1[0] + nx * front), (float) (p1[1] + ny * front)},
                {(float) (p0[0] + nx * front), (float) (p0[1] + ny * front)}};
            // Test polygon overlap using 2D Separating Axis Theorem (SAT)
            if (!polygonsOverlapSAT(band, polygon)) {
                continue;
            }
            // Clip the obstacle polygon to the transverse strip [-back, front] in local (along, across) coordinates
            // so oblique obstacles or long walls only block the exact sub-stretch where they cross the strip
            List<double[]> localPoly = new ArrayList<>(polygon.length);
            for (float[] point : polygon) {
                double dx = point[0] - side.points[i][0], dy = point[1] - side.points[i][1];
                localPoly.add(new double[]{dx * u[0] + dy * u[1], dx * nx + dy * ny});
            }
            localPoly = clip(localPoly, 1, -back, 1);
            localPoly = clip(localPoly, 1, front, -1);
            localPoly = clip(localPoly, 0, lo, 1);
            localPoly = clip(localPoly, 0, hi, -1);
            if (localPoly.isEmpty()) {
                continue;
            }
            double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
            for (double[] pt : localPoly) {
                min = Math.min(min, pt[0]);
                max = Math.max(max, pt[0]);
            }
            min = Math.max(lo, min);
            max = Math.min(hi, max);
            if (max > min + EPSILON) {
                // Lateral tolerance at each end of the obstacle (capped at 45% of the obstacle width so it is never completely ignored)
                blocked.add(new double[]{side.cumulative[i] + min, side.cumulative[i] + max, Math.min(tol, (max - min) * 0.45)});
            }
        }
        return blocked;
    }

    /**
     * Computes where along a side a 2D polygon (such as a wall or adjacent room floor) intersects
     * the placement strip in front of the side (from 0.5 cm off the boundary line to offset + effStripDepth).
     */
    private static List<double[]> blockedPolygonAlong(SidePath side, float[][] polygon, double from, double to,
            double stripDepth, double offset, double tol, boolean ignoreCoincidentWall) {
        List<double[]> blocked = new ArrayList<>();
        if (polygon == null || polygon.length < 3) {
            return blocked;
        }
        double effStripDepth = Math.max(stripDepth * 0.2, stripDepth - tol);
        double near = Math.max(0.5, offset + 0.5);
        double far = Math.max(near + 0.5, offset + effStripDepth);
        Area polyArea = null;
        java.awt.geom.Rectangle2D polyBounds = SurroundTarget.toPath(polygon).getBounds2D();
        for (int i = 0; i < side.segmentCount(); i++) {
            double segmentLength = side.cumulative[i + 1] - side.cumulative[i];
            if (segmentLength < EPSILON) {
                continue;
            }
            double[] u = side.direction(i);
            double nx = u[1], ny = -u[0];
            // If this polygon is a wall whose own long axis runs parallel and coincident with this segment
            // (i.e. the wall that forms this room edge itself), skip it so we never paint the whole side!
            if (ignoreCoincidentWall && isCoincidentParallelWall(polygon, side.points[i], u, nx, ny, segmentLength)) {
                continue;
            }
            double lo = i == 0 ? Math.min(0, from) : 0;
            double hi = i == side.segmentCount() - 1 ? Math.max(segmentLength, to - side.cumulative[i]) : segmentLength;
            double[] p0 = {side.points[i][0] + u[0] * lo, side.points[i][1] + u[1] * lo};
            double[] p1 = {side.points[i][0] + u[0] * hi, side.points[i][1] + u[1] * hi};
            float[][] band = {
                {(float) (p0[0] + nx * near), (float) (p0[1] + ny * near)},
                {(float) (p1[0] + nx * near), (float) (p1[1] + ny * near)},
                {(float) (p1[0] + nx * far), (float) (p1[1] + ny * far)},
                {(float) (p0[0] + nx * far), (float) (p0[1] + ny * far)}};
            java.awt.geom.Path2D.Float bandPath = SurroundTarget.toPath(band);
            if (!bandPath.getBounds2D().intersects(polyBounds)) {
                continue;
            }
            if (polyArea == null) {
                polyArea = new Area(SurroundTarget.toPath(polygon));
            }
            Area hit = new Area(bandPath);
            hit.intersect(polyArea);
            if (!hit.isEmpty()) {
                for (float[][] loop : SurroundTarget.loops(hit)) {
                    double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
                    for (float[] pt : loop) {
                        double t = (pt[0] - side.points[i][0]) * u[0] + (pt[1] - side.points[i][1]) * u[1];
                        min = Math.min(min, t);
                        max = Math.max(max, t);
                    }
                    min = Math.max(lo, min);
                    max = Math.min(hi, max);
                    if (max - min > 1.0) {
                        blocked.add(new double[]{side.cumulative[i] + min, side.cumulative[i] + max, Math.min(tol, (max - min) * 0.45)});
                    }
                }
            }
        }
        return blocked;
    }

    /**
     * Checks whether a wall polygon is the structural wall forming the segment itself (runs parallel
     * along {@code u} and straddles or touches the boundary line {@code across == 0} over most of the segment).
     */
    private static boolean isCoincidentParallelWall(float[][] wallPoly, float[] segStart, double[] u,
            double nx, double ny, double segmentLength) {
        double maxEdge = 0;
        double[] longDir = null;
        for (int k = 0; k < wallPoly.length; k++) {
            float[] a = wallPoly[k], b = wallPoly[(k + 1) % wallPoly.length];
            double len = Math.hypot(b[0] - a[0], b[1] - a[1]);
            if (len > maxEdge) {
                maxEdge = len;
                longDir = new double[]{(b[0] - a[0]) / len, (b[1] - a[1]) / len};
            }
        }
        if (longDir == null || Math.abs(longDir[0] * u[0] + longDir[1] * u[1]) < 0.96) {
            return false; // Not parallel to the segment
        }
        double minAcross = Double.MAX_VALUE, maxAcross = -Double.MAX_VALUE;
        double minAlong = Double.MAX_VALUE, maxAlong = -Double.MAX_VALUE;
        for (float[] p : wallPoly) {
            double dx = p[0] - segStart[0], dy = p[1] - segStart[1];
            double along = dx * u[0] + dy * u[1];
            double across = dx * nx + dy * ny;
            minAlong = Math.min(minAlong, along);
            maxAlong = Math.max(maxAlong, along);
            minAcross = Math.min(minAcross, across);
            maxAcross = Math.max(maxAcross, across);
        }
        double overlapAlong = Math.min(segmentLength, maxAlong) - Math.max(0, minAlong);
        // If the parallel wall straddles or hugs the boundary line (minAcross <= 1.5 cm) and covers > 50% of the segment
        return minAcross <= 1.5 && maxAcross >= -1.5 && overlapAlong > Math.min(25.0, segmentLength * 0.5);
    }

    /**
     * Translates excluded or contact intervals stored in room-native vertex order into side-relative arc lengths.
     *
     * @param intervals list of intervals [s0, s1] in native room order
     */
    private static void addBlockedIntervals(SidePath side, List<double[]> intervals, List<double[]> blocked, double tol) {
        for (double[] interval : intervals) {
            double s0 = side.reversed ? (side.length - interval[1]) : interval[0];
            double s1 = side.reversed ? (side.length - interval[0]) : interval[1];
            if (s1 > s0 + EPSILON) {
                double edgeTol = Math.min(tol, (s1 - s0) * 0.45);
                blocked.add(new double[]{s0, s1, edgeTol});
            }
        }
    }

    // ---- Types 1 to 6 (Unified Surround Algorithm & Per-Side Dispatch) ----

    /**
     * Executes the unified surround placement across all perimeter sides of a face (Types 1 to 6).
     * Computes perpendicular distance offsets for each side (allowing furniture runs to expand
     * outward or inward by activeConfig.distanceOffset), establishes corner intersections, and
     * delegates to {@link #surroundUnifiedSides(Room, List, int)}. Offsets are safely cleaned up
     * in a {@code finally} block upon completion.
     */
    private void surroundUnified(Room face, List<SidePath> sides, int roomIdx) {
        int count = sides.size();
        // Distance from the edge of each side: its objects go along a line moved by that distance,
        // and the ends of each side go where its line meets the line of the next side
        sideOffsets = new double[count];
        boolean offsets = false;
        for (int t = 0; t < count; t++) {
            sideOffsets[t] = isSideExcluded(face, sides.get(t), count) ? 0 : activeConfig.distanceOffset;
            offsets |= sideOffsets[t] != 0;
        }
        if (!offsets) {
            sideOffsets = null;
        }
        try {
            surroundUnifiedSides(face, sides, roomIdx);
        } finally {
            sideOffsets = null;
            currentSideOffset = 0;
        }
    }

    /**
     * Fills each side of a face that isn't excluded: works out its ends (corner margins or side end modes),
     * places one object on short sides if asked, splits it into free stretches and fills them. The extra
     * objects of the side ends are placed once all sides are.
     */
    private void surroundUnifiedSides(Room face, List<SidePath> sides, int roomIdx) {
        int count = sides.size();
        currentRoomSideCount = count;
        double[][] cornerMargins = new double[count][2];
        int[][] extraObjects = new int[count][2]; // 0 = none, 1 = END_EXTRA_OBJECT, 2 = END_ADJACENT_TO_OBJECTS
        List<double[]> extras = new ArrayList<>(); // entries: {sideIndex, position, forwardDirection (1/0), endType (0=start, 1=end), anchorIndex}
        if (activeConfig.customSideEnds) {
            cornerOwnMargin = new boolean[count][2];
            computeCustomSideEnds(face, sides, cornerMargins, extraObjects);
        } else {
            cornerMargins = computeCornerMarginsAndPlacePieces(face, sides, roomIdx);
        }
        int effectiveType = activeConfig.surroundType;

        for (int t = 0; t < count; t++) {
            SidePath side = sides.get(t);
            currentSideOffset = 0;
            if (side.length <= 0 || isSideExcluded(face, side, count)) {
                continue;
            }
            currentSideIndex = t;
            currentVertexIndex = -1;
            currentSideGroupKey = "R" + roomIdx + "_S" + (side.index + 1);
            sidePlacedGroups.putIfAbsent(currentSideGroupKey, new ArrayList<HomePieceOfFurniture>());

            currentSideOffset = sideOffsets != null ? sideOffsets[t] : 0;
            double pieceMargin = getEffectivePieceMargin();
            double[] range = sideRange(sides, t, cornerMargins[t][0], cornerMargins[t][1]);
            double from = range[0];
            double to = range[1];

            currentSidePlacedMin = Double.MAX_VALUE;
            currentSidePlacedMax = -Double.MAX_VALUE;

            if (to - from <= EPSILON) {
                // Side is shorter than the space required by corner margins
                if (activeConfig.shortSidesOneObject && !measuringUniform) {
                    placeShortSideObjectWithHierarchy(face, side, from - pieceMargin, to + pieceMargin, effectiveType);
                }
            } else {
                // Side has positive traversable length: extract free stretches avoiding obstacles
                List<double[]> stretches = freeStretches(face, side, from, to, maxSurroundingDepth, currentSideOffset);
                for (int st = 0; st < stretches.size(); st++) {
                    double[] stretch = stretches.get(st);
                    boolean wholeSide = stretch[2] == 0 && stretch[3] == 0;
                    currentStretchStartTol = stretch.length > 4 ? stretch[4] : 0.0;
                    currentStretchEndTol = stretch.length > 5 ? stretch[5] : 0.0;
                    fillStretch(side, stretch[0], stretch[1], wholeSide, effectiveType);
                }
                currentStretchStartTol = 0.0;
                currentStretchEndTol = 0.0;
            }

            // Record extra corner/end objects if requested by custom side ends
            if (!measuringUniform) {
                double stepGap = 2.0 * pieceMargin;
                // "Object next to the vertex": one more object outside the side, before its first vertex
                // or after its last one (the side itself is filled from vertex to vertex). "Next to the
                // objects of the side": one more object right before the first one or after the last one.
                // Both are placed once all the sides are, to be checked against the objects of the other sides
                int anchor = firstLevelPlacedFurniture.size();
                if (extraObjects[t][0] == 1) {
                    extras.add(new double[]{t, from - stepGap, 0, 0, anchor});
                } else if (extraObjects[t][0] == 2 && currentSidePlacedMin < Double.MAX_VALUE / 2) {
                    extras.add(new double[]{t, currentSidePlacedMin - stepGap, 0, 0, anchor});
                }
                if (extraObjects[t][1] == 1) {
                    extras.add(new double[]{t, to + stepGap, 1, 1, anchor});
                } else if (extraObjects[t][1] == 2 && currentSidePlacedMax > -Double.MAX_VALUE / 2) {
                    extras.add(new double[]{t, currentSidePlacedMax + stepGap, 1, 1, anchor});
                }
            }
        }
        // Place and verify all accumulated extra corner/end objects
        placeExtraObjects(face, sides, roomIdx, extras);
        currentSideIndex = -1;
        currentVertexIndex = -1;
        currentSideOffset = 0;
    }

    /**
     * Evaluates start and end margin offsets and extra object flags for each side when customSideEnds is enabled.
     * Handles modes: END_FIT (miter bisector fit), END_DISTANCE, END_EXTRA_OBJECT, and END_ADJACENT_TO_OBJECTS.
     *
     * @param margins      output margin array [side][0=start, 1=end] in cm
     * @param extraObjects output extra object flags [side][0=start, 1=end]
     */
    private void computeCustomSideEnds(Room face, List<SidePath> sides, double[][] margins, int[][] extraObjects) {
        int count = sides.size();
        // Inside, the start of a side in the direction shown is the end of its traversal
        boolean swap = interiorFor(face);
        int startMode = swap ? activeConfig.globalEndEndMode : activeConfig.globalStartEndMode;
        double startDist = swap ? activeConfig.globalEndEndDistance : activeConfig.globalStartEndDistance;
        int endMode = swap ? activeConfig.globalStartEndMode : activeConfig.globalEndEndMode;
        double endDist = swap ? activeConfig.globalStartEndDistance : activeConfig.globalEndEndDistance;
        double stripDepth = Math.max(0.0, maxSurroundingDepth);

        for (int t = 0; t < count; t++) {
            for (int end = 0; end <= 1; end++) {
                int mode = (end == 0) ? startMode : endMode;
                double dist = (end == 0) ? startDist : endDist;
                switch (mode) {
                    case SurroundConfig.END_EXTRA_OBJECT:
                        extraObjects[t][end] = 1;
                        break;
                    case SurroundConfig.END_ADJACENT_TO_OBJECTS:
                        extraObjects[t][end] = 2;
                        break;
                    case SurroundConfig.END_DISTANCE:
                        margins[t][end] = -dist;   // + prolongs the side past its vertex, - shortens it
                        break;
                    case SurroundConfig.END_FIT: {
                        int v = (end == 0) ? t : (t + 1) % count;
                        int neighbourIdx = (end == 0) ? (t - 1 + count) % count : (t + 1) % count;
                        double[] geom = vertexGeometry(sides, v);
                        if (count < 2 || isSideExcluded(face, sides.get(neighbourIdx), count)
                                || geom == null || Math.abs(geom[5]) <= 1e-4) {
                            break;   // nothing surrounded beyond, or collinear: nothing to cover or avoid
                        }
                        int partnerMode = (end == 0) ? endMode : startMode;
                        if (geom[5] < 0) {
                            // Convex corner: the strips leave an uncovered corner outside the vertex; this
                            // side goes on up to the strip of the other one. When both ends there fit, only
                            // the side that ends at the vertex goes on, so that they don't overlap.
                            // Up to a right angle it goes on to the bisector (depth * tan(gamma)); when the
                            // sides open more than that (the points of a star), the bisector runs away and
                            // the uncovered corner ends one strip depth past the vertex, so it stops there
                            boolean partnerCovers = partnerMode == SurroundConfig.END_FIT && end == 0;
                            margins[t][end] = partnerCovers ? 0.0 : -stripDepth * Math.min(geom[4], 1.0);
                            break;
                        }
                        double cosGamma = geom[2], sinGamma = geom[3], tanGamma = geom[4];
                        double bisectorClearance = stripDepth * tanGamma;
                        if (partnerMode == SurroundConfig.END_FIT) {
                            margins[t][end] = bisectorClearance;
                        } else {
                            // An extra object fills back to the vertex
                            double partnerMargin = partnerMode == SurroundConfig.END_DISTANCE ? -((end == 0) ? endDist : startDist) : 0.0;
                            // Interior angle theta = pi - 2*gamma -> sin(theta) = 2*sin*cos, cos(theta) = 2*sin^2 - 1
                            double sinTheta = 2.0 * sinGamma * cosGamma;
                            double cosTheta = 2.0 * sinGamma * sinGamma - 1.0;
                            double projFromPartner = partnerMargin * cosTheta + stripDepth * sinTheta;
                            margins[t][end] = Math.max(bisectorClearance, projFromPartner);
                        }
                        break;
                    }
                    default:   // at the vertex
                        break;
                }
            }
        }
    }

    /**
     * Places the extra objects of the sides of a face once all its sides are: those at the end of a side
     * first, so that at a corner where both ends ask for one, the side that ends there keeps it. They
     * then take their place in the order of the placed objects, after the objects of their side.
     *
     * @param roomIdx zero-based Room index
     */
    private void placeExtraObjects(Room face, List<SidePath> sides, int roomIdx, List<double[]> extras) {
        if (extras.isEmpty()) {
            return;
        }
        List<HomePieceOfFurniture> placed = new ArrayList<>();
        List<Integer> anchors = new ArrayList<>();
        for (int end = 1; end >= 0; end--) {
            for (double[] extra : extras) {
                if ((int) extra[3] != end) {
                    continue;
                }
                int t = (int) extra[0];
                currentSideIndex = t;
                currentVertexIndex = -1;
                currentSideOffset = sideOffsets != null ? sideOffsets[t] : 0;
                currentSideGroupKey = "R" + roomIdx + "_S" + (sides.get(t).index + 1);
                int before = firstLevelPlacedFurniture.size();
                placeExtraObject(face, sides, t, extra[1], extra[2] == 1, end == 1 ? (t + 1) % sides.size() : t);
                if (firstLevelPlacedFurniture.size() > before) {
                    placed.add(firstLevelPlacedFurniture.remove(firstLevelPlacedFurniture.size() - 1));
                    anchors.add((int) extra[4]);
                }
            }
        }
        // Restore placed extra objects after the models of their respective sides
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < placed.size(); i++) {
            order.add(i);
        }
        Collections.sort(order, (a, b) -> Integer.compare(anchors.get(a), anchors.get(b)));
        int inserted = 0;
        for (int i : order) {
            firstLevelPlacedFurniture.add(anchors.get(i) + inserted++, placed.get(i));
        }
    }

    /**
     * Places an extra object along side t from coordinate {@code at}, forward or backward.
     * Verifies that the piece does not violate door/window openings or user exclusions (if extraObjectRespectObstacles is active),
     * and ensures that the piece does not penetrate into the Room or Wall interior geometry (if extraObjectRespectCorners is active).
     *
     * @param forward true to place forward (+), false backward (-)
     */
    private void placeExtraObject(Room face, List<SidePath> sides, int t, double at, boolean forward, int vertex) {
        SidePath side = sides.get(t);
        HomePieceOfFurniture piece = getNextSelectedPiece();
        double[] d = getPieceSurroundingDimensions(piece);
        double center = at + (forward ? 1 : -1) * d[0] / 2.0;
        double objStart = center - d[0] / 2.0;
        double objEnd = center + d[0] / 2.0;
        boolean ignoreCollisions = true;
        if (activeConfig.extraObjectRespectObstacles) {
            double checkFrom = Math.min(0.0, objStart);
            double checkTo = Math.max(side.length, objEnd);
            List<double[]> free = freeStretches(face, side, checkFrom, checkTo, maxSurroundingDepth, currentSideOffset);
            boolean fitsInFreeStretch = false;
            for (double[] st : free) {
                double sTol = st.length > 4 ? st[4] : 0.0;
                double eTol = st.length > 5 ? st[5] : 0.0;
                if (objStart >= st[0] - sTol - EPSILON && objEnd <= st[1] + eTol + EPSILON) {
                    fitsInFreeStretch = true;
                    break;
                }
            }
            if (!fitsInFreeStretch) {
                return;
            }
            ignoreCollisions = !activeConfig.avoidCollisions;
            if (activeConfig.extraObjectRespectCorners && sides.size() > 1
                    && getsIntoFigure(sides, vertex, straightFootprint(side, center, d[1], d[0] / 2.0, d[1] / 2.0))) {
                return;
            }
        }
        place(side, center, d[1], piece, -1, -1, -1, true, ignoreCollisions);
    }

    /**
     * Checks whether a rectangular footprint penetrates into the architectural interior of a wall or floor figure at vertex k.
     *
     * @param footprint 2D rectangular bounding box coordinates
     */
    private boolean getsIntoFigure(List<SidePath> sides, int k, double[] footprint) {
        double[] geom = vertexGeometry(sides, k);
        if (geom == null || Math.abs(geom[5]) <= 1e-4) {
            return false;
        }
        int count = sides.size();
        double[] corner = offsetCorner(sides, k);
        double[] uIn = sides.get((k - 1 + count) % count).lastDirection();
        double[] uOut = sides.get(k).firstDirection();
        double[][] corners = footprintCorners(footprint);
        if (geom[5] > 0) {
            // Concave: the figure is behind either side
            for (double[] u : new double[][]{uIn, uOut}) {
                for (double[] c : corners) {
                    if ((c[0] - corner[0]) * u[1] - (c[1] - corner[1]) * u[0] < -0.5) {
                        return true;
                    }
                }
            }
            return false;
        }
        // Convex: the figure is the wedge behind both sides
        return convexOverlap(corners, wedge(corner[0], corner[1], uIn, uOut)) > 0.5;
    }

    /**
     * Queries the perpendicular distance offset of neighbor side i, returning 0 if excluded.
     *
     * @return offset in cm
     */
    private double neighbourOffset(Room face, List<SidePath> sides, int i) {
        return isSideExcluded(face, sides.get(i), sides.size()) ? 0 : activeConfig.distanceOffset;
    }

    /**
     * Computes the 2D intersection point where the offset lines of two adjacent sides meet at vertex k.
     *
     * @return 2D intersection coordinate array [x, y, t, sAlong]
     */
    private double[] offsetCorner(List<SidePath> sides, int k) {
        int count = sides.size();
        return offsetCorner(sides, k, sideOffsets != null ? sideOffsets[(k - 1 + count) % count] : 0,
                sideOffsets != null ? sideOffsets[k] : 0);
    }

    /**
     * Calculates the intersection of two offset lines meeting at vertex k:
     * the incoming side moved perpendicularly by d1 and the outgoing side moved by d2.
     *
     * @param d1    incoming offset distance (cm)
     * @param d2    outgoing offset distance (cm)
     * @return array containing {x, y, position past end of incoming side, position along outgoing side}
     */
    private static double[] offsetCorner(List<SidePath> sides, int k, double d1, double d2) {
        int count = sides.size();
        SidePath previous = sides.get((k - 1 + count) % count);
        SidePath next = sides.get(k);
        float[] v = next.start();
        if (count < 2 || (d1 == 0 && d2 == 0)) {
            return new double[]{v[0], v[1], 0, 0};
        }
        double[] uIn = previous.lastDirection();
        double[] uOut = next.firstDirection();
        // nIn * d1 + t * uIn = nOut * d2 + s * uOut, normals (u.y, -u.x)
        double rx = uOut[1] * d2 - uIn[1] * d1;
        double ry = -uOut[0] * d2 + uIn[0] * d1;
        double det = -uIn[0] * uOut[1] + uOut[0] * uIn[1];
        double t, sAlong;
        if (Math.abs(det) < 1e-6) {
            t = 0;
            sAlong = 0;
        } else {
            t = (-rx * uOut[1] + uOut[0] * ry) / det;
            sAlong = (uIn[0] * ry - uIn[1] * rx) / det;
        }
        return new double[]{v[0] + uIn[1] * d1 + uIn[0] * t, v[1] - uIn[0] * d1 + uIn[1] * t, t, sAlong};
    }

    /**
     * Returns the active inter-piece margin distance (in cm), or 0.0 if piece margin is disabled.
     *
     * @return effective piece margin in cm
     */
    private double getEffectivePieceMargin() {
        return activeConfig.enablePieceMargin ? activeConfig.pieceMargin : 0.0;
    }

    /**
     * Fills the free stretch [from, to] of a side with the given type. On a curve the fronts of the objects
     * don't take the same length as the side, so the stretch is laid out on its narrowest line and stretched back.
     */
    private void fillStretch(SidePath side, double from, double to, boolean wholeSide, int surroundType) {
        // On a concave curve the fronts of the objects come closer than their backs: the objects
        // are laid out on the front line and their positions stretched back onto the side. Moved by
        // a distance, the stretch may go straight beyond the ends of the curve (1:1 there)
        double wallLength = to - from;
        layoutFrom = from;
        layoutStretch = 1;
        layoutPre = 0;
        layoutCurveStart = from;
        layoutCurveEnd = to;
        layoutCurveLayout = to - from;
        if (side.isCurved()) {
            double curveStart = Math.max(from, 0);
            double curveEnd = Math.min(to, side.length);
            if (curveEnd > curveStart) {
                layoutCurveLayout = side.frontLength(curveStart, curveEnd, currentSideOffset, maxSurroundingDepth);
                layoutPre = curveStart - from;
                layoutCurveStart = curveStart;
                layoutCurveEnd = curveEnd;
                layoutStretch = (curveEnd - curveStart) / layoutCurveLayout;
                wallLength = layoutPre + layoutCurveLayout + (to - curveEnd);
                // Moved by a distance, the backs of the objects go along a curve shorter (concave) or
                // longer (convex) than the side: their widths are measured on it
                curveWidthFactor = currentSideOffset != 0
                        ? (curveEnd - curveStart) / side.frontLength(curveStart, curveEnd, currentSideOffset, 0) : 1;
            }
        }
        try {
            layOutStretch(side, from, wallLength, wholeSide, surroundType);
        } finally {
            layoutStretch = 1;
            curveWidthFactor = 1;
        }
    }

    /**
     * Lays out the objects of a free stretch with types 1 to 6 (same size or spaces: {@link #layOutUniform}).
     * If they slightly exceed it, the tolerances at its ends (currentStretchStartTol/EndTol) absorb it.
     * Type 1: equal spaces; 2: contiguous and centered (joints of 2 &times; pieceMargin); 3: enlarged to cover it;
     * 4: one more object than fits, reduced to cover it; 5: contiguous from the start (end if reversed);
     * 6: whole objects, with the ones at the ends resized to close it.
     *
     * @param from       start of the stretch along the side (cm)
     * @param wallLength length of the stretch (cm)
     */
    private void layOutStretch(SidePath side, double from, double wallLength, boolean wholeSide, int surroundType) {
        if (usesUniform(surroundType)) {
            layOutUniform(side, from, wallLength, wholeSide, surroundType);
            return;
        }
        double pieceMargin = getEffectivePieceMargin();

        // 1. Collect candidate pieces that fit within the stretch
        List<HomePieceOfFurniture> wallPieces = new ArrayList<>();
        List<double[]> wallPieceDims = new ArrayList<>();
        double accumulatedWidth = 0.0;
        HomePieceOfFurniture overflowPiece = null;
        double maxExtraTol = (surroundType != 4) ? (currentStretchStartTol + currentStretchEndTol) : 0.0;
        while (true) {
            checkCancelled();
            HomePieceOfFurniture candidate = getNextSelectedPiece();
            double[] dims = getPieceSurroundingDimensions(candidate);
            double candW = dims[0];
            if (candW <= EPSILON) {
                break;
            }
            // Margin around each piece: positive separates by 2 * pieceMargin, negative overlaps by 2 * |pieceMargin|
            double minGapNeeded = !wallPieces.isEmpty() ? Math.max(-candW * 0.9, 2.0 * pieceMargin) : 0.0;
            if (accumulatedWidth + minGapNeeded + candW <= wallLength + EPSILON) {
                wallPieces.add(candidate);
                wallPieceDims.add(dims);
                accumulatedWidth += minGapNeeded + candW;
            } else if (maxExtraTol > EPSILON && accumulatedWidth + minGapNeeded + candW <= wallLength + maxExtraTol + EPSILON) {
                // Fits within the allowed obstacle/collision overlap tolerance!
                double neededExtra = (accumulatedWidth + minGapNeeded + candW) - wallLength;
                double startShare = (currentStretchStartTol + currentStretchEndTol) > EPSILON
                        ? neededExtra * (currentStretchStartTol / (currentStretchStartTol + currentStretchEndTol))
                        : 0.0;
                double usedStart = Math.min(currentStretchStartTol, startShare);
                double usedEnd = Math.min(currentStretchEndTol, neededExtra - usedStart);
                if (usedStart + usedEnd < neededExtra - 1e-6) {
                    usedStart = Math.min(currentStretchStartTol, neededExtra - usedEnd);
                }
                from -= usedStart * layoutStretch;
                wallLength += usedStart + usedEnd;
                maxExtraTol = 0.0; // Tolerance consumed for this stretch
                wallPieces.add(candidate);
                wallPieceDims.add(dims);
                accumulatedWidth += minGapNeeded + candW;
                break;
            } else {
                if (surroundType == 4) {
                    // Type 4 reduces pieces: include the overflow piece so the total scale factor drops below 1.0
                    wallPieces.add(candidate);
                    wallPieceDims.add(dims);
                    accumulatedWidth += minGapNeeded + candW;
                } else {
                    overflowPiece = candidate;
                }
                break;
            }
        }

        int m = wallPieces.size();
        if (m == 0) {
            if (activeConfig.shortSidesOneObject && wholeSide) {
                placeCentered(side, from + wallLength / 2);
            }
            return;
        }

        boolean reverseDir = isReverseDirection(currentProcessingRoom);
        boolean symmetricEnds = activeConfig.symmetricBothEnds;

        // Item 2: Resolve which dimensions adapt in resizing modes (Types 3, 4, and 6)
        boolean[] dims = dimensionFlags();

        // 2. Dispatch to specific surround algorithm layout
        switch (surroundType) {
            case 1:
            case 2:
            case 5: {
                // Type 1: equal inter-piece gaps; Type 2: centered with equal end gaps; Type 5: continuous from start/end
                double gap = 2.0 * pieceMargin;
                double cursor = 0.0;
                if (surroundType == 1) {
                    double rawWidths = 0.0;
                    for (double[] d : wallPieceDims) {
                        rawWidths += d[0];
                    }
                    gap = m > 1 ? (wallLength - rawWidths) / (m - 1) : 0.0;
                } else if (surroundType == 2) {
                    cursor = Math.max(0.0, (wallLength - accumulatedWidth) / 2.0);
                } else if (reverseDir) {
                    cursor = Math.max(0.0, wallLength - accumulatedWidth);
                }
                for (int i = 0; i < m; i++) {
                    double[] d = wallPieceDims.get(i);
                    place(side, from + cursor + d[0] / 2.0, d[1], wallPieces.get(i), -1, -1, -1, true, false);
                    cursor += d[0] + gap;
                }
                break;
            }
            case 3:
            case 4: {
                // Types 3 and 4: Continuous proportional scaling without gaps
                double scale = wallLength / Math.max(EPSILON, accumulatedWidth);
                double cursor = 0.0;
                for (int i = 0; i < m; i++) {
                    double[] d = wallPieceDims.get(i);
                    cursor += placeResized(side, from + cursor, wallPieces.get(i), d, scale, dims);
                }
                break;
            }
            case 6: {
                // Type 6: Whole models with resized pieces at the adjusted end(s).
                // 1) When symmetricBothEnds is active and the stretch is not evenly divisible by the piece width,
                //    reserve at least 2 resized end pieces (reducing full middle pieces by 1 if remainingGap is 0
                //    or too small) so the last object at EACH end genuinely has a different (resized) width.
                // 2) When customSideEnds with END_FIT is active on an unadjusted end (or single-end adjustment),
                //    preserve the resized piece size that the natural side length (without the END_FIT extension)
                //    had at the adjusted end, inserting additional full pieces before it in the extended space.
                double remainingGap = wallLength - accumulatedWidth;
                boolean adjustStart = symmetricEnds || reverseDir;
                boolean adjustEnd = symmetricEnds || !reverseDir;
                HomePieceOfFurniture extraPiece = overflowPiece != null ? overflowPiece : wallPieces.get(0);
                double[] extraDims = getPieceSurroundingDimensions(extraPiece);

                if (symmetricEnds) {
                    // Check if the stretch is evenly divisible by the piece width; if not, both ends must get a resized piece
                    if (remainingGap > EPSILON) {
                        if (m >= 2 && remainingGap / 2.0 < wallPieceDims.get(0)[0] / 2.0) {
                            // Free one full piece so both ends receive distinct resized pieces rather than widening into 1
                            double removedW = wallPieceDims.get(m - 1)[0];
                            wallPieces.remove(m - 1);
                            wallPieceDims.remove(m - 1);
                            m--;
                            accumulatedWidth -= removedW;
                            remainingGap = wallLength - accumulatedWidth;
                        }
                        double gapPerEnd = remainingGap / 2.0;
                        double cursor = 0.0;
                        // Start end resized piece
                        cursor += placeResized(side, from + cursor, extraPiece, extraDims, gapPerEnd / extraDims[0], dims);
                        // Full intermediate pieces
                        for (int i = 0; i < m; i++) {
                            double[] d = wallPieceDims.get(i);
                            place(side, from + cursor + d[0] / 2.0, d[1], wallPieces.get(i), -1, -1, -1, true, false);
                            cursor += d[0];
                        }
                        // End resized piece
                        placeResized(side, from + cursor, extraPiece, extraDims, gapPerEnd / extraDims[0], dims);
                    } else {
                        // Exactly divisible: place all m full pieces
                        double cursor = 0.0;
                        for (int i = 0; i < m; i++) {
                            double[] d = wallPieceDims.get(i);
                            place(side, from + cursor + d[0] / 2.0, d[1], wallPieces.get(i), -1, -1, -1, true, false);
                            cursor += d[0];
                        }
                    }
                    break;
                }

                // Single-end adjustment (either end when !reverseDir, or start when reverseDir).
                // If END_FIT extended the side, keep the same last resized piece width as before END_FIT,
                // placing any newly added pieces before that last piece.
                double targetLastWidth = -1.0;
                if (wholeSide && activeConfig.customSideEnds && !side.isCurved()
                        && (activeConfig.globalStartEndMode == SurroundConfig.END_FIT
                                || activeConfig.globalEndEndMode == SurroundConfig.END_FIT)) {
                    double baseLen = side.length;
                    double w0 = extraDims[0];
                    if (w0 > EPSILON && baseLen > EPSILON && Math.abs(wallLength - baseLen) > EPSILON) {
                        int baseM = (int) Math.floor((baseLen + EPSILON) / w0);
                        double baseRem = baseLen - baseM * w0;
                        if (baseRem <= EPSILON) {
                            targetLastWidth = w0;
                        } else if (baseRem < w0 / 2.0 && baseM >= 1) {
                            targetLastWidth = w0 + baseRem;
                        } else {
                            targetLastWidth = baseRem;
                        }
                        if (targetLastWidth > wallLength + EPSILON) {
                            targetLastWidth = -1.0;
                        }
                    }
                }

                if (targetLastWidth > EPSILON) {
                    // Keep the last placed piece identical in size to what it was before END_FIT;
                    // fill the space before it with whole pieces and resize the piece right before it if needed.
                    double spaceBeforeLast = Math.max(0.0, wallLength - targetLastWidth);
                    double w0 = extraDims[0];
                    int countBefore = (int) Math.floor((spaceBeforeLast + EPSILON) / w0);
                    double remBefore = spaceBeforeLast - countBefore * w0;
                    List<Double> widths = new ArrayList<>();
                    for (int i = 0; i < countBefore; i++) {
                        widths.add(w0);
                    }
                    if (remBefore > EPSILON) {
                        if (remBefore < w0 / 2.0 && !widths.isEmpty()) {
                            widths.set(widths.size() - 1, widths.get(widths.size() - 1) + remBefore);
                        } else {
                            widths.add(remBefore);
                        }
                    }
                    widths.add(targetLastWidth);
                    if (reverseDir) {
                        Collections.reverse(widths);
                    }
                    double cursor = 0.0;
                    for (double wPiece : widths) {
                        if (Math.abs(wPiece - w0) > EPSILON) {
                            cursor += placeResized(side, from + cursor, extraPiece, extraDims, wPiece / w0, dims);
                        } else {
                            place(side, from + cursor + w0 / 2.0, extraDims[1], extraPiece, -1, -1, -1, true, false);
                            cursor += w0;
                        }
                    }
                    break;
                }

                double gapPerEnd = remainingGap;
                boolean widenStart = adjustStart && gapPerEnd < wallPieceDims.get(0)[0] / 2.0;
                boolean widenEnd = adjustEnd && gapPerEnd < wallPieceDims.get(m - 1)[0] / 2.0;
                double cursor = 0.0;
                if (adjustStart && !widenStart && gapPerEnd > EPSILON) {
                    cursor += placeResized(side, from + cursor, extraPiece, extraDims, gapPerEnd / extraDims[0], dims);
                }
                for (int i = 0; i < m; i++) {
                    double[] d = wallPieceDims.get(i);
                    double extra = 0;
                    if (i == 0 && widenStart) {
                        extra += gapPerEnd;
                    }
                    if (i == m - 1 && widenEnd) {
                        extra += gapPerEnd;
                    }
                    if (extra > EPSILON) {
                        cursor += placeResized(side, from + cursor, wallPieces.get(i), d, (d[0] + extra) / d[0], dims);
                    } else {
                        place(side, from + cursor + d[0] / 2.0, d[1], wallPieces.get(i), -1, -1, -1, true, false);
                        cursor += d[0];
                    }
                }
                if (adjustEnd && !widenEnd && gapPerEnd > EPSILON) {
                    placeResized(side, from + cursor, extraPiece, extraDims, gapPerEnd / extraDims[0], dims);
                }
                break;
            }
            default:
                break;
        }
    }

    /**
     * Resolves which bounding dimensions adapt when resizing pieces (Item 2): {width, depth, height}.
     * Width always scales in resizing modes (Types 3, 4, and 6).
     *
     * @return boolean array [widthScales, depthScales, heightScales]
     */
    private boolean[] dimensionFlags() {
        int mode = activeConfig.dimModifyMode;
        boolean depth = mode == SurroundConfig.DIM_PROPORTIONAL || (mode == SurroundConfig.DIM_SELECTED && activeConfig.dimModifyD);
        boolean height = mode == SurroundConfig.DIM_PROPORTIONAL || (mode == SurroundConfig.DIM_SELECTED && activeConfig.dimModifyH);
        return new boolean[]{true, depth, height};
    }

    /**
     * Places a furniture piece resized by the given scale factor, starting at arc-length coordinate {@code start}.
     *
     * @param start           starting arc-length position (cm)
     * @param surroundingDims original surrounding bounding dimensions [width, depth]
     * @param dims            boolean array [width, depth, height] indicating which dimensions scale
     * @return the linear width (cm) occupied along the side by the placed piece
     */
    private double placeResized(SidePath side, double start, HomePieceOfFurniture piece, double[] surroundingDims,
            double scale, boolean[] dims) {
        double scaleAcross = dims[1] ? scale : 1.0;
        double scaleHeight = dims[2] ? scale : 1.0;
        double newWidth = surroundingDims[0] * scale;
        double newDepth = surroundingDims[1] * scaleAcross;
        float[] pieceDims = pieceDimensionsFor(piece, scale, scaleAcross);
        place(side, start + newWidth / 2.0, newDepth, piece, pieceDims[0], pieceDims[1],
                (float) (piece.getHeight() * scaleHeight), true, false);
        return newWidth;
    }

    /**
     * Calculates the unrotated width and depth required for a furniture model such that,
     * when rotated by piece.getAngle(), its oriented bounding dimensions match
     * (scaleAlong * surroundingWidth) and (scaleAcross * surroundingDepth).
     *
     * @return array containing {newWidth, newDepth}
     */
    private static float[] pieceDimensionsFor(HomePieceOfFurniture piece, double scaleAlong, double scaleAcross) {
        double w = piece.getWidth(), d = piece.getDepth();
        if (Math.abs(scaleAlong - scaleAcross) < 1e-9) {
            return new float[]{(float) (w * scaleAlong), (float) (d * scaleAlong)};
        }
        double c = Math.abs(Math.cos(piece.getAngle())), s = Math.abs(Math.sin(piece.getAngle()));
        if (s < 1e-3) {
            return new float[]{(float) (w * scaleAlong), (float) (d * scaleAcross)};
        }
        if (c < 1e-3) {
            return new float[]{(float) (w * scaleAcross), (float) (d * scaleAlong)};
        }
        double surroundingW = (w * c + d * s) * scaleAlong;
        double surroundingD = (w * s + d * c) * scaleAcross;
        double det = c * c - s * s;
        if (Math.abs(det) > 1e-4) {
            double newW = (surroundingW * c - surroundingD * s) / det;
            double newD = (surroundingD * c - surroundingW * s) / det;
            if (newW > 0 && newD > 0) {
                return new float[]{(float) newW, (float) newD};
            }
        }
        return new float[]{(float) (w * scaleAlong), (float) (d * scaleAlong)};
    }

    /**
     * Fallback placement for short sides: positions a single template object centered within
     * the largest free interval that does not violate door/window openings or corner margins.
     */
    private void placeShortSideObjectWithHierarchy(Room face, SidePath side, double rawFrom, double rawTo, int effectiveType) {
        double spanStart = Math.min(rawFrom, rawTo);
        double spanEnd = Math.max(rawFrom, rawTo);
        List<double[]> free = freeStretches(face, side, 0, side.length, maxSurroundingDepth, currentSideOffset);
        if (free.isEmpty()) {
            return; // Completely blocked by a door/window gap or user-excluded segment (higher priority)
        }
        // Pick the largest free sub-stretch on this short side and center the object within the intersection with corner bounds
        double[] best = free.get(0);
        for (double[] st : free) {
            if ((st[1] - st[0]) > (best[1] - best[0])) {
                best = st;
            }
        }
        double center = 0.5 * (Math.max(best[0], spanStart) + Math.min(best[1], spanEnd));
        if (Math.max(best[0], spanStart) > Math.min(best[1], spanEnd)) {
            center = 0.5 * (best[0] + best[1]);
        }
        if ((effectiveType == 3 || effectiveType == 4) && usesUniform(effectiveType)) {
            placeUniformSingle(side, center, dimensionFlags(), effectiveType);
        } else {
            placeCentered(side, center);
        }
    }

    /** Places a single template object centered at arc-length coordinate s. */
    private void placeCentered(SidePath side, double s) {
        HomePieceOfFurniture piece = getNextSelectedPiece();
        double[] dims = getPieceSurroundingDimensions(piece);
        // Respect avoidCollisions if enabled (hasCollision already accounts for collisionFitTolerance)
        place(side, s, dims[1], piece, -1, -1, -1, true, !activeConfig.avoidCollisions);
    }

    /** Normalized X component of the current wall surface normal vector. */
    private double currentWallNormalX = 0.0;

    /** Normalized Y component of the current wall surface normal vector. */
    private double currentWallNormalY = -1.0;

    /**
     * Places a piece at distance s along the side, at its depth from the edge and facing out of it
     * (compensating the bulge of curves).
     *
     * @param width                  width to give it (-1 keeps the piece's); likewise pieceDepth and height
     */
    private void place(SidePath side, double s, double depth, HomePieceOfFurniture piece,
            float width, float pieceDepth, float height, boolean applyTemplateBaseAngle, boolean ignoreCollisions) {
        if (layoutStretch != 1) {
            double along = s - layoutFrom;
            s = along <= layoutPre ? layoutFrom + along
                    : along <= layoutPre + layoutCurveLayout ? layoutCurveStart + (along - layoutPre) * layoutStretch
                    : layoutCurveEnd + (along - layoutPre - layoutCurveLayout);
        }
        double[] point;
        double[] u;
        if (side.isCurved()) {
            double widthFactor = s >= layoutCurveStart && s <= layoutCurveEnd ? curveWidthFactor : 1;
            double halfWidth = (width > 0 ? width : getPieceSurroundingDimensions(piece)[0]) / 2.0 * widthFactor;
            double[] a = side.pointAt(s - halfWidth);
            double[] b = side.pointAt(s + halfWidth);
            double chord = Math.hypot(b[0] - a[0], b[1] - a[1]);
            u = chord > 1e-6 ? new double[]{(b[0] - a[0]) / chord, (b[1] - a[1]) / chord} : side.directionAt(s);
            point = new double[]{(a[0] + b[0]) / 2, (a[1] + b[1]) / 2};
            double[] onCurve = side.pointAt(s);
            double bulge = (onCurve[0] - point[0]) * u[1] + (onCurve[1] - point[1]) * -u[0];
            if (bulge > 0) {
                point[0] += u[1] * bulge;
                point[1] += -u[0] * bulge;
            }
        } else {
            point = side.pointAt(s);
            u = side.directionAt(s);
        }
        if (!measuringUniform) {
            double halfAlong = (width > 0 ? width : getPieceSurroundingDimensions(piece)[0]) / 2.0;
            currentSidePlacedMin = Math.min(currentSidePlacedMin, s - halfAlong);
            currentSidePlacedMax = Math.max(currentSidePlacedMax, s + halfAlong);
        }
        double nx = u[1], ny = -u[0];
        currentWallNormalX = nx;
        currentWallNormalY = ny;
        double across = currentSideOffset + depth / 2.0;   // distance from the edge, then half the depth
        createSurroundingFromTemplate(piece, (float) (point[0] + nx * across), (float) (point[1] + ny * across),
                (float) Math.atan2(u[1], u[0]), width, pieceDepth, height, applyTemplateBaseAngle, ignoreCollisions, false);
    }

    /**
     * Computes the clearance distance along concave sides meeting at vertex k required to ensure
     * that a model of the specified depth does not penetrate across the adjacent side when meeting
     * at an acute angle (< 90 degrees).
     *
     * @param depth model depth in cm
     * @return required clearance margin (cm)
     */
    private static double acuteCornerMargin(List<SidePath> sides, int k, double depth) {
        double[] geometry = vertexGeometry(sides, k);
        if (geometry == null || geometry[5] <= 1e-4 || geometry[4] <= 1e-9) {
            return 0;
        }
        double tanGamma = geometry[4];   // gamma: half the turn between both sides
        return Math.max(0, depth * (tanGamma * tanGamma - 1) / (2 * tanGamma));
    }

    /**
     * Geometry of vertex k (where side k starts): {bisector x, bisector y, cos, sin and tan of gamma (half the
     * turn between the normals), turn}; turn &gt; 0 at a concave corner, &lt; 0 at a convex one; null if collinear.
     */
    private static double[] vertexGeometry(List<SidePath> sides, int k) {
        int count = sides.size();
        if (count < 2) {
            return null;
        }
        double[] uIn = sides.get((k - 1 + count) % count).lastDirection();
        double[] uOut = sides.get(k).firstDirection();
        double nInX = uIn[1], nInY = -uIn[0];
        double bRawX = nInX + uOut[1], bRawY = nInY - uOut[0];
        double bLen = Math.hypot(bRawX, bRawY);
        if (bLen < 1e-4) {
            return null;
        }
        double bx = bRawX / bLen, by = bRawY / bLen;
        double cosGamma = Math.max(0.15, Math.min(1.0, nInX * bx + nInY * by));
        double sinGamma = Math.sqrt(Math.max(0.0, 1.0 - cosGamma * cosGamma));
        return new double[]{bx, by, cosGamma, sinGamma, sinGamma / cosGamma, uOut[0] * nInX + uOut[1] * nInY};
    }

    // ---- Corners ----

    /**
     * Margins of the sides at each vertex, and the corner pieces. Without pieces, at a concave corner the rows
     * stop depth &times; tan(gamma) before it so they don't enter the other strip. With pieces, each one sits
     * square on the bisector; in types 3 and 4 the size of the sides and of the piece depend on each other,
     * so it repeats (up to 12 times) until they settle.
     *
     * @return margins [side][0 at its first vertex, 1 at its last one]
     */
    private double[][] computeCornerMarginsAndPlacePieces(Room face, List<SidePath> sides, int roomIdx) {
        int count = sides.size();
        // sideMargins[side][0 = start at vertex t, 1 = end at vertex (t+1)%count]
        double[][] sideMargins = new double[count][2];
        cornerOwnMargin = new boolean[count][2];
        if (!activeConfig.enableCornerHandling || count < 2) {
            return sideMargins;
        }
        double wallStripDepth = Math.max(0.0, maxSurroundingDepth);
        if (!activeConfig.placeOrthogonalCornerPiece) {
            // Empty corners: at a concave corner each side stops before entering the depth strip of the other
            for (int k = 0; k < count; k++) {
                int prevIdx = (k - 1 + count) % count;
                double[] geometry = vertexGeometry(sides, k);
                // geometry[4] = tanGamma, geometry[5] = cornerTurn (>0 for concave / inward turns)
                double m = geometry != null && geometry[5] > 1e-4
                        && !isSideExcluded(face, sides.get(prevIdx), count) && !isSideExcluded(face, sides.get(k), count)
                        ? wallStripDepth * geometry[4] : 0.0;
                sideMargins[prevIdx][1] = m;
                sideMargins[k][0] = m;
            }
            return sideMargins;
        }

        // When orthogonal corner pieces are enabled, each corner piece is owned by the side that departs
        // from that vertex in traversal order (or by the previous side if the departing side is excluded).
        HomePieceOfFurniture[] bases = new HomePieceOfFurniture[count];
        int[] ownerSide = new int[count];
        boolean[] placeCorner = new boolean[count];
        for (int k = 0; k < count; k++) {
            int prevIdx = (k - 1 + count) % count;
            boolean previousSkipped = isSideExcluded(face, sides.get(prevIdx), count);
            boolean nextSkipped = isSideExcluded(face, sides.get(k), count);
            boolean nextExcluded = isSideExcluded(face, sides.get(k), count);
            if (vertexGeometry(sides, k) == null) {
                continue;
            }
            bases[k] = getCornerTemplatePiece();
            ownerSide[k] = nextSkipped ? prevIdx : k;
            placeCorner[k] = !nextSkipped || (nextExcluded && !previousSkipped);
        }

        // Types 3 and 4 size the objects of each side to cover it; corner pieces take that dynamic size,
        // which in turn alters the remaining longitudinal length available to the side. Both must be
        // solved iteratively until convergence (relaxation loop up to 12 passes).
        boolean sizedBySide = (activeConfig.surroundType == 3 || activeConfig.surroundType == 4)
                && !usesUniform(activeConfig) && activeConfig.customCornerCatalogPiece == null
                && !activeConfig.cornerKeepSize;
        double[] sideScale = new double[count];
        java.util.Arrays.fill(sideScale, 1.0);
        HomePieceOfFurniture[] corners = new HomePieceOfFurniture[count];
        double[][] placements = new double[count][];
        for (int iteration = 0; iteration < (sizedBySide ? 12 : 1); iteration++) {
            List<double[]> placed = new ArrayList<>();
            for (int k = 0; k < count; k++) {
                if (bases[k] != null) {
                    corners[k] = scaledPiece(bases[k], sideScale[ownerSide[k]]);
                    placements[k] = cornerPlacement(face, sides, k, corners[k], sideMargins, placeCorner[k] ? placed : null);
                }
            }
            if (!sizedBySide) {
                break;
            }
            // Check convergence of side scales across all sides
            double change = 0;
            for (int t = 0; t < count; t++) {
                double scale = estimatedSideScale(face, sides, t, sideMargins);
                change = Math.max(change, Math.abs(scale - sideScale[t]));
                sideScale[t] = scale;
            }
            if (change < 1e-4) {
                break; // Converged within numerical tolerance
            }
        }

        // Commit corner pieces into the scene graph
        for (int k = 0; k < count; k++) {
            if (!placeCorner[k] || corners[k] == null) {
                continue;
            }
            currentSideIndex = -1;
            currentVertexIndex = k;
            currentSideGroupKey = "R" + roomIdx + "_S" + (sides.get(ownerSide[k]).index + 1);
            sidePlacedGroups.putIfAbsent(currentSideGroupKey, new ArrayList<HomePieceOfFurniture>());
            createSurroundingFromTemplate(corners[k], (float) placements[k][0], (float) placements[k][1],
                    (float) placements[k][2], -1, -1, -1, false, false, true);
        }
        currentVertexIndex = -1;
        return sideMargins;
    }

    /**
     * Pose {x, y, angle} of the corner piece at vertex k, square on the bisector, and the margins it leaves to
     * both sides (in sideMargins). If it would hit another corner piece, a door or window, furniture or the
     * face, {@link VertexFit#nearest} looks for the smallest turn and shift over the vertex that avoids it.
     */
    private double[] cornerPlacement(Room face, List<SidePath> sides, int k, HomePieceOfFurniture piece,
            double[][] sideMargins, List<double[]> placed) {
        int count = sides.size();
        int prevIdx = (k - 1 + count) % count;
        double[] geometry = vertexGeometry(sides, k);
        double bx = geometry[0], by = geometry[1], tanGamma = geometry[4], cornerTurn = geometry[5];
        double rW = piece.getWidth() / 2.0;
        double rD = piece.getDepth() / 2.0;
        double[] corner = offsetCorner(sides, k);
        double vx = corner[0], vy = corner[1];
        double[] uIn = sides.get(prevIdx).lastDirection();
        double[] uOut = sides.get(k).firstDirection();
        double angle = Math.atan2(uIn[1] + uOut[1], uIn[0] + uOut[0]);

        // At a concave corner, the rear corners of the rectangular piece touch both wall lines simultaneously,
        // which pushes the center outward along the bisector by rD + rW * tanGamma.
        // If cornerTouchVertex is true, the center is kept at distance rD, allowing rear corners to clip into walls.
        double bisectorDist = cornerTurn > 1e-4 && !activeConfig.cornerTouchVertex ? rD + rW * tanGamma : rD;
        double cx = vx + bx * bisectorDist;
        double cy = vy + by * bisectorDist;

        // If collision avoidance or clearance against already placed corner pieces is needed
        if (placed != null) {
            double[] footprint = {cx, cy, Math.cos(angle), Math.sin(angle), rW, rD};
            if (cornerBlocked(sides, k, piece, footprint, placed)) {
                // Search for the closest valid placement using VertexFit
                double[] moved = new VertexFit(sides, k, corner, rW, rD).nearest(
                        f -> !getsIntoFace(f) && !cornerBlocked(sides, k, piece, f, placed));
                if (moved != null) {
                    footprint = moved;
                    cx = moved[0];
                    cy = moved[1];
                    angle = Math.atan2(moved[3], moved[2]);
                }
            }
            placed.add(footprint);
        }

        // Calculate clearance margins along incoming and outgoing sides according to orthoSeparation mode
        int separation = activeConfig.orthoSeparation;
        double[] back = {-uIn[0], -uIn[1]};
        double marginIn, marginOut;
        switch (separation) {
            case SurroundConfig.ORTHO_SEPARATE: {
                // Reserve margin up to where the corner piece exits the depth strip of each side, plus user margin
                double depth = Math.max(0.0, maxSurroundingDepth);
                double margin = activeConfig.cornerPieceMargin;
                marginIn = stripSpan(cx, cy, angle, rW, rD, vx, vy, back, new double[]{uIn[1], -uIn[0]}, depth)[1] + margin;
                marginOut = stripSpan(cx, cy, angle, rW, rD, vx, vy, uOut, new double[]{uOut[1], -uOut[0]}, depth)[1] + margin;
                cornerOwnMargin[prevIdx][1] = true;
                cornerOwnMargin[k][0] = true;
                break;
            }
            case SurroundConfig.ORTHO_FIRST_VERTEX:
            case SurroundConfig.ORTHO_SECOND_VERTEX:
            case SurroundConfig.ORTHO_MID_VERTEX:
                // Reserve margin based on distance to nearest, second-nearest, or midpoint of piece vertices
                double[] dIn = cornerPieceDistances(cx, cy, angle, rW, rD, vx, vy, uIn);
                double[] dOut = cornerPieceDistances(cx, cy, angle, rW, rD, vx, vy, new double[]{-uOut[0], -uOut[1]});
                int nearest = separation == SurroundConfig.ORTHO_SECOND_VERTEX ? 1 : 0;
                boolean halfway = separation == SurroundConfig.ORTHO_MID_VERTEX;
                marginIn = halfway ? 0.5 * (dIn[0] + dIn[1]) : dIn[nearest];
                marginOut = halfway ? 0.5 * (dOut[0] + dOut[1]) : dOut[nearest];
                break;
            default:
                marginIn = 0;
                marginOut = 0;
                break;
        }
        sideMargins[prevIdx][1] = marginIn;
        sideMargins[k][0] = marginOut;
        return new double[]{cx, cy, angle};
    }

    /**
     * {nearest, farthest} distances along `away` from the corner (vx, vy) of the part of a piece (centered at
     * cx, cy) inside the strip of a side, from 0 to depth along `normal` (Sutherland-Hodgman clipping).
     */
    static double[] stripSpan(double cx, double cy, double angle, double rW, double rD, double vx, double vy,
            double[] away, double[] normal, double depth) {
        double ca = Math.cos(angle), sa = Math.sin(angle);
        List<double[]> polygon = new ArrayList<>();
        // Construct the 4 rotated corners of the piece relative to the vertex (vx, vy)
        for (double[] c : new double[][]{{-1, -1}, {1, -1}, {1, 1}, {-1, 1}}) {
            double px = cx + ca * rW * c[0] - sa * rD * c[1] - vx;
            double py = cy + sa * rW * c[0] + ca * rD * c[1] - vy;
            // Project into (alongSide, acrossSide) local strip coordinates
            polygon.add(new double[]{px * away[0] + py * away[1], px * normal[0] + py * normal[1]});
        }
        // Clip against strip boundaries acrossSide >= 0 and acrossSide <= depth
        polygon = clip(polygon, 1, 0, 1);         // across >= 0
        polygon = clip(polygon, 1, depth, -1);    // across <= depth
        double nearest = polygon.isEmpty() ? 0 : Double.MAX_VALUE, extent = 0;
        for (double[] point : polygon) {
            nearest = Math.min(nearest, point[0]);
            extent = Math.max(extent, point[0]);
        }
        return new double[]{nearest, extent};
    }

    /** Sutherland-Hodgman clipping of a convex polygon to sign &times; (point[axis] - limit) &ge; 0. */
    private static List<double[]> clip(List<double[]> polygon, int axis, double limit, int sign) {
        List<double[]> out = new ArrayList<>();
        for (int i = 0; i < polygon.size(); i++) {
            double[] a = polygon.get(i), b = polygon.get((i + 1) % polygon.size());
            double da = sign * (a[axis] - limit), db = sign * (b[axis] - limit);
            if (da >= 0) {
                out.add(a);
            }
            if ((da >= 0) != (db >= 0)) {
                double t = da / (da - db);
                out.add(new double[]{a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t});
            }
        }
        return out;
    }

    /**
     * Estimates the piece scaling factor applied by Types 3 and 4 along side {@code t} based on the
     * currently reserved corner margins.
     * Used by the iterative relaxation loop in {@link #computeCornerMarginsAndPlacePieces} to achieve
     * mutual convergence of corner piece dimensions and adjacent side furniture scaling.
     *
     * @return estimated scaling factor (1.0 default if unplaced or no stretch)
     */
    private double estimatedSideScale(Room face, List<SidePath> sides, int t, double[][] sideMargins) {
        SidePath side = sides.get(t);
        int count = sides.size();
        if (side.length <= 0 || isSideExcluded(face, side, count)) {
            return 1.0;
        }
        double[] range = sideRange(sides, t, sideMargins[t][0], sideMargins[t][1]);
        List<double[]> stretches = freeStretches(face, side, range[0], range[1], maxSurroundingDepth,
                sideOffsets != null ? sideOffsets[t] : 0);
        if (stretches.isEmpty()) {
            return 1.0;
        }
        boolean nextSkipped = isSideExcluded(face, sides.get((t + 1) % count), count);
        double[] stretch = stretches.get(nextSkipped ? stretches.size() - 1 : 0);
        double length = stretch[1] - stretch[0];
        double width = getPieceSurroundingDimensions(primaryTemplate)[0];
        if (width <= EPSILON || length <= EPSILON) {
            return 1.0;
        }
        double gap = Math.max(-width * 0.9, 2.0 * getEffectivePieceMargin());
        int fitting = (int) Math.floor((length + gap) / (width + gap) + 1e-9);
        int objects = activeConfig.surroundType == 4 ? fitting + 1 : fitting;
        if (objects <= 0) {
            return 1.0;
        }
        return length / (objects * width + (objects - 1) * gap);
    }

    /**
     * Calculates the valid longitudinal start and end offsets {@code [from, to]} along side {@code t},
     * accounting for corner margins, user distance offsets, curved wall ends, and inter-piece margins.
     *
     * @param startMargin clearance margin at vertex {@code t}
     * @param endMargin   clearance margin at vertex {@code (t+1)%count}
     * @return a 2-element array {@code [from, to]} along the side's arc length
     */
    private double[] sideRange(List<SidePath> sides, int t, double startMargin, double endMargin) {
        int count = sides.size();
        SidePath side = sides.get(t);
        double offset = sideOffsets != null ? sideOffsets[t] : 0;
        double startShift = 0, endShift = 0;
        if (offset != 0) {
            double previousOffset = Math.min(offset, sideOffsets[(t - 1 + count) % count]);
            double nextOffset = Math.min(offset, sideOffsets[(t + 1) % count]);
            startShift = offsetCorner(sides, t, previousOffset, offset)[3];
            endShift = offsetCorner(sides, (t + 1) % count, offset, nextOffset)[2];
        }
        if (side.isCurved()) {
            startShift = Math.max(0, startShift);
            endShift = Math.min(0, endShift);
        }
        double pieceMargin = getEffectivePieceMargin();
        boolean[] ownMargin = t < cornerOwnMargin.length ? cornerOwnMargin[t] : new boolean[2];
        return new double[]{startMargin + startShift + (ownMargin[0] ? 0 : pieceMargin),
            side.length - endMargin + endShift - (ownMargin[1] ? 0 : pieceMargin)};
    }

    /**
     * Determines whether a piece's footprint penetrates into the interior of the room face (or escapes
     * it when surrounding in interior mode).
     *
     * @param footprint 6-element footprint array {@code [cx, cy, cos, sin, halfWidth, halfDepth]}
     * @return true if the footprint violates face boundary containment by more than 0.5 cm
     */
    private boolean getsIntoFace(double[] footprint) {
        float[][] points = new float[4][];
        double[][] corners = footprintCorners(footprint);
        for (int i = 0; i < 4; i++) {
            points[i] = new float[]{(float) corners[i][0], (float) corners[i][1]};
        }
        Area part = new Area(SurroundTarget.toPath(points));
        Area face = new Area(SurroundTarget.toPath(currentProcessingRoom.getPoints()));
        if (interiorFor(currentProcessingRoom)) {
            part.subtract(face);
        } else {
            part.intersect(face);
        }
        java.awt.geom.Rectangle2D bounds = part.getBounds2D();
        return !part.isEmpty() && bounds.getWidth() > 0.5 && bounds.getHeight() > 0.5;
    }

    /**
     * Whether a corner piece with that footprint {x, y, cos, sin, halfWidth, halfDepth} at vertex k would come
     * too close to a placed corner piece, block a door or window of either side, or hit furniture or walls
     * (with collision avoidance).
     */
    private boolean cornerBlocked(List<SidePath> sides, int k, HomePieceOfFurniture piece, double[] footprint,
            List<double[]> placed) {
        double clearance = activeConfig.orthoSeparation == SurroundConfig.ORTHO_SEPARATE ? Math.max(0, activeConfig.cornerPieceMargin) : 0;
        for (double[] other : placed) {
            if (footprintGap(footprint, other) < clearance - 0.05) {
                return true;
            }
        }
        // Along each incident side of the vertex, verify that the piece's footprint does not obstruct doors/windows
        int count = sides.size();
        double[] corner = offsetCorner(sides, k);
        double depth = Math.max(0.0, maxSurroundingDepth);
        double angle = Math.atan2(footprint[3], footprint[2]);
        for (int end = 0; end < 2; end++) {
            int t = end == 0 ? (k - 1 + count) % count : k;
            SidePath side = sides.get(t);
            double[] u = end == 0 ? side.lastDirection() : side.firstDirection();
            double[] span = stripSpan(footprint[0], footprint[1], angle, footprint[4], footprint[5], corner[0], corner[1],
                    end == 0 ? new double[]{-u[0], -u[1]} : u, new double[]{u[1], -u[0]}, depth);
            double from = end == 0 ? side.length + corner[2] - span[1] : corner[3] + span[0];
            double to = end == 0 ? side.length + corner[2] - span[0] : corner[3] + span[1];
            for (HomePieceOfFurniture obstacle : currentObstacles) {
                // Only consider doors and windows aligned with this side
                double alongSide = Math.sin(obstacle.getAngle()) * u[0] - Math.cos(obstacle.getAngle()) * u[1];
                if (obstacle.isDoorOrWindow() && Math.abs(alongSide) < 0.1 && to - from > 0.5) {
                    for (double[] b : blockedAlong(side, obstacle, from, to, depth, sideOffsets != null ? sideOffsets[t] : 0,
                            effectiveGapFitTolerance())) {
                        if (Math.min(b[1], to) - Math.max(b[0], from) > 0.5) {
                            return true;
                        }
                    }
                }
            }
        }
        if (!activeConfig.avoidCollisions) {
            return false;
        }
        HomePieceOfFurniture posed = piece.clone();
        posed.setX((float) footprint[0]);
        posed.setY((float) footprint[1]);
        posed.setAngle((float) angle);
        int side = currentSideIndex, vertex = currentVertexIndex;
        currentSideIndex = -1;
        currentVertexIndex = k;
        try {
            return hasCollision(posed);
        } finally {
            currentSideIndex = side;
            currentVertexIndex = vertex;
        }
    }

    /**
     * Clones a base furniture piece and scales its width by {@code scale}, optionally scaling depth
     * and height according to user configuration flags.
     */
    private HomePieceOfFurniture scaledPiece(HomePieceOfFurniture base, double scale) {
        HomePieceOfFurniture piece = base.clone();
        if (Math.abs(scale - 1) > 1e-9 && piece.isResizable()) {
            boolean[] dims = dimensionFlags();
            piece.setWidth((float) (base.getWidth() * scale));
            if (dims[1]) {
                piece.setDepth((float) (base.getDepth() * scale));
            }
            if (dims[2]) {
                piece.setHeight((float) (base.getHeight() * scale));
            }
        }
        return piece;
    }

    /**
     * Computes the longitudinal distances from the corner vertex {@code (vx, vy)} along an incoming
     * direction vector {@code uIn} to each of the 4 rotated corners of a corner piece, sorted in descending order.
     *
     * @param angle     piece rotation angle (radians)
     * @return 4-element array of projected distances sorted from largest to smallest
     */
    static double[] cornerPieceDistances(double cx, double cy, double angle, double halfWidth, double halfDepth,
            double vx, double vy, double[] uIn) {
        double ca = Math.cos(angle), sa = Math.sin(angle);
        double[] distances = new double[4];
        int i = 0;
        for (int sw : new int[]{-1, 1}) {
            for (int sd : new int[]{-1, 1}) {
                double px = cx + ca * halfWidth * sw - sa * halfDepth * sd;
                double py = cy + sa * halfWidth * sw + ca * halfDepth * sd;
                distances[i++] = (vx - px) * uIn[0] + (vy - py) * uIn[1];
            }
        }
        java.util.Arrays.sort(distances);
        return new double[]{distances[3], distances[2], distances[1], distances[0]};
    }

    /**
     * Obtains the template piece to be used for orthogonal corners, taking either the custom catalog
     * model (if designated by user) or a scaled copy of the primary selected piece.
     */
    private HomePieceOfFurniture getCornerTemplatePiece() {
        if (activeConfig.customCornerCatalogPiece != null) {
            HomePieceOfFurniture cornerPiece = new HomePieceOfFurniture(activeConfig.customCornerCatalogPiece);
            cornerPiece.setElevation(primaryTemplate.getElevation());
            return cornerPiece;
        }
        return scaledPiece(getNextSelectedPiece(), cornerPieceScale);
    }

    // ---- Type 7 ----

    /**
     * Type 7: the free sides form one path, along which the objects are spread, a fixed number evenly or one
     * every type7Spacing, across the vertices. With corner handling, an object left over a vertex
     * ({@link #findType7VertexObjects}) is fitted to it ({@link #fitType7VertexObject}). Sizes can vary at random.
     */
    private void surroundType7(Room face, List<SidePath> sides, int roomIdx) {
        SurroundConfig config = activeConfig;
        int count = sides.size();
        int objectCountSetting = config.type7ObjectCount;
        boolean reverse = isReverseDirection(face);

        // Step 1: Build continuous obstacle-free stretches across all valid sides without clamping at intermediate vertices
        List<double[]> stretches = new ArrayList<>(); // {sideIndexInSides, from, to, isBoundaryStart, isBoundaryEnd, userOffset, sTol, eTol}
        double total = 0;
        for (int t = 0; t < count; t++) {
            SidePath side = sides.get(t);
            if (side.length <= 0 || isSideExcluded(face, side, count)) {
                continue;
            }
            int kPrev = (t - 1 + count) % count;
            int kNext = (t + 1) % count;
            boolean prevSkipped = isSideExcluded(face, sides.get(kPrev), count);
            boolean nextSkipped = isSideExcluded(face, sides.get(kNext), count);
            double userOffset = activeConfig.distanceOffset;
            double frontDepth = Math.max(0, maxSurroundingDepth);

            // In Type 7, do NOT apply corner clearance margins at intermediate contiguous vertices;
            // only apply acute corner margins at the true start/end of an open or interrupted chain.
            double from = prevSkipped ? acuteCornerMargin(sides, t, frontDepth) : 0.0;
            double to = side.length - (nextSkipped ? acuteCornerMargin(sides, kNext, frontDepth) : 0.0);

            // Apply corner offsets if user distance offset is non-zero
            if (userOffset != 0) {
                double previousOffset = Math.min(userOffset, neighbourOffset(face, sides, kPrev));
                double nextOffset = Math.min(userOffset, neighbourOffset(face, sides, kNext));
                from += offsetCorner(sides, t, previousOffset, userOffset)[3];
                to += offsetCorner(sides, kNext, userOffset, nextOffset)[2];
            }
            if (to - from <= EPSILON) {
                continue;
            }

            // Extract unblocked sub-stretches free from doors, windows, and obstacles
            List<double[]> free = freeStretches(face, side, from, to, maxSurroundingDepth + Math.max(0, userOffset), 0);
            for (int fs = 0; fs < free.size(); fs++) {
                double[] stretch = free.get(fs);
                boolean boundaryStart = (fs == 0 && prevSkipped) || stretch[2] == 1;
                boolean boundaryEnd = (fs == free.size() - 1 && nextSkipped) || stretch[3] == 1;
                double sTol = stretch.length > 4 ? stretch[4] : 0.0;
                double eTol = stretch.length > 5 ? stretch[5] : 0.0;
                stretches.add(new double[]{t, stretch[0], stretch[1], boundaryStart ? 1 : 0, boundaryEnd ? 1 : 0, userOffset, sTol, eTol});
                total += stretch[1] - stretch[0];
            }
        }
        if (total <= 0 || stretches.isEmpty()) {
            return;
        }

        // Apply custom Type 7 start point if configured for this face (rotates the 1D perimeter path
        // so placement starts at the user-selected side and position along that side).
        double[] customStart = config.type7StartPoints != null ? config.type7StartPoints.get(face) : null;
        if (customStart != null && customStart.length >= 2) {
            int targetLogicalSide = (int) Math.round(customStart[0]);
            double targetNativeDist = customStart[1];
            // Find the side t with side.index == targetLogicalSide
            int targetT = -1;
            for (int t = 0; t < count; t++) {
                if (sides.get(t).index == targetLogicalSide) {
                    targetT = t;
                    break;
                }
            }
            if (targetT >= 0) {
                SidePath targetSide = sides.get(targetT);
                double targetS = targetSide.reversed ? (targetSide.length - targetNativeDist) : targetNativeDist;
                targetS = Math.max(0.0, Math.min(targetSide.length, targetS));
                // Locate the stretch containing (or nearest after) targetS on targetT
                int splitIdx = -1;
                double splitCoord = 0.0;
                for (int idx = 0; idx < stretches.size(); idx++) {
                    double[] st = stretches.get(idx);
                    if ((int) st[0] == targetT) {
                        if (targetS >= st[1] - EPSILON && targetS <= st[2] + EPSILON) {
                            splitIdx = idx;
                            splitCoord = Math.max(st[1], Math.min(st[2], targetS));
                            break;
                        } else if (targetS < st[1] && splitIdx < 0) {
                            splitIdx = idx;
                            splitCoord = st[1];
                            break;
                        }
                    }
                }
                if (splitIdx < 0) {
                    // Fallback: first stretch on or after targetT in traversal order
                    for (int step = 0; step < count && splitIdx < 0; step++) {
                        int candT = (targetT + step) % count;
                        for (int idx = 0; idx < stretches.size(); idx++) {
                            if ((int) stretches.get(idx)[0] == candT) {
                                splitIdx = idx;
                                splitCoord = stretches.get(idx)[1];
                                break;
                            }
                        }
                    }
                }
                if (splitIdx >= 0) {
                    List<double[]> rotated = new ArrayList<>();
                    double[] st = stretches.get(splitIdx);
                    if (splitCoord >= st[2] - EPSILON) {
                        // At the end of the stretch: the path starts with the next one
                        splitIdx = (splitIdx + 1) % stretches.size();
                        st = stretches.get(splitIdx);
                        splitCoord = st[1];
                    }
                    if (splitCoord > st[1] + EPSILON && splitCoord < st[2] - EPSILON) {
                        // Split the stretch at splitCoord: second half comes first, first half wraps to the end
                        double eTol = st.length > 7 ? st[7] : 0.0;
                        double sTol = st.length > 6 ? st[6] : 0.0;
                        rotated.add(new double[]{st[0], splitCoord, st[2], 0, st[4], st[5], 0.0, eTol});
                        for (int k = 1; k < stretches.size(); k++) {
                            rotated.add(stretches.get((splitIdx + k) % stretches.size()));
                        }
                        rotated.add(new double[]{st[0], st[1], splitCoord, st[3], 0, st[5], sTol, 0.0});
                    } else {
                        for (int k = 0; k < stretches.size(); k++) {
                            rotated.add(stretches.get((splitIdx + k) % stretches.size()));
                        }
                    }
                    stretches = rotated;
                }
            }
        }

        // Step 2: Determine longitudinal spacing and total piece count
        double spacing;
        int objectCount;
        if (objectCountSetting > 0) {
            objectCount = objectCountSetting;
            spacing = objectCount > 1 ? total / objectCount : 0;
        } else {
            spacing = Math.max(1, config.type7Spacing);
            objectCount = (int) Math.floor(total / spacing);
            if (objectCount < 1) {
                objectCount = 1;
            } else if (objectCount * spacing < total - 1e-6) {
                objectCount++;
            }
        }

        // Step 3: Plan object placements along the cumulative 1D path
        List<Type7Object> planned = new ArrayList<>();
        for (int i = 0; i < objectCount; i++) {
            checkCancelled();
            HomePieceOfFurniture activePiece = getNextSelectedPiece();
            double[] activeDims = getPieceSurroundingDimensions(activePiece);
            double activeSurrWidth = activeDims[0];
            double activeSurrDepth = activeDims[1];

            // Evaluate randomized dimensions (uniform or independent width/depth/height scaling)
            double scaleW = 1, scaleD = 1, scaleH = 1;
            if (config.randomizeAll) {
                scaleW = scaleD = scaleH = getRandomScale(config.minAll, config.maxAll);
            } else {
                scaleW = config.randomizeWidth ? getRandomScale(config.minWidth, config.maxWidth) : 1;
                scaleD = config.randomizeDepth ? getRandomScale(config.minDepth, config.maxDepth) : 1;
                scaleH = config.randomizeHeight ? getRandomScale(config.minHeight, config.maxHeight) : 1;
            }
            boolean isRandomized = config.randomizeAll || config.randomizeWidth || config.randomizeDepth || config.randomizeHeight;
            double finalWidth = activePiece.getWidth() * scaleW;
            double finalDepth = activePiece.getDepth() * scaleD;
            double finalHeight = activePiece.getHeight() * scaleH;
            if (finalWidth <= 0 || finalDepth <= 0) {
                continue;
            }
            double halfWidth = activeSurrWidth * (finalWidth / activePiece.getWidth()) / 2.0;

            // Compute center position along continuous cumulative length
            double position = i * spacing + activeSurrWidth / 2.0;
            if (reverse) {
                position = total - position;
            }
            // Clamp strictly to the overall [0, total] path, never snapping at intermediate vertices
            position = Math.max(0.0, Math.min(total, position));

            // Map cumulative 1D path coordinate back to its host side and local s coordinate
            double cumulative = 0;
            double[] stretch = null;
            for (double[] candidate : stretches) {
                double length = candidate[2] - candidate[1];
                if (position <= cumulative + length + 1e-6) {
                    stretch = candidate;
                    break;
                }
                cumulative += length;
            }
            if (stretch == null) {
                stretch = stretches.get(stretches.size() - 1);
                cumulative = total - (stretch[2] - stretch[1]);
            }
            SidePath side = sides.get((int) stretch[0]);
            double from = stretch[1], to = stretch[2];
            double s = from + (position - cumulative);

            // Clamp only at true external path boundaries (chain start/end or obstacle gaps)
            boolean clampStart = stretch[3] == 1;
            boolean clampEnd = stretch[4] == 1;
            double sTol = stretch.length > 6 ? stretch[6] : 0.0;
            double eTol = stretch.length > 7 ? stretch[7] : 0.0;
            double minS = clampStart ? (from - sTol) + halfWidth : from;
            double maxS = clampEnd ? (to + eTol) - halfWidth : to;
            if (maxS >= minS - 1e-6) {
                s = Math.max(minS, Math.min(maxS, s));
            } else if (config.shortSidesOneObject) {
                s = (from + to) / 2;
            }
            double effDepth = activeSurrDepth * (finalDepth / activePiece.getDepth());

            // Build planned Type7Object metadata
            Type7Object object = new Type7Object();
            object.piece = activePiece;
            object.width = isRandomized ? (float) finalWidth : -1;
            object.depth = isRandomized ? (float) finalDepth : -1;
            object.height = isRandomized ? (float) finalHeight : -1;
            object.side = side;
            object.s = s;
            object.depthOffset = 2 * (effDepth / 2.0 + stretch[5]);
            object.userOffset = stretch[5];
            object.shortStretch = to - from < 2 * halfWidth;
            object.footprint = straightFootprint(side, s, object.depthOffset, halfWidth, effDepth / 2.0);
            planned.add(object);
        }

        // Step 4: Corner vertex fitting (if enabled)
        if (config.enableCornerHandling) {
            findType7VertexObjects(face, sides, planned);
            for (Type7Object object : planned) {
                if (object.vertex >= 0) {
                    fitType7VertexObject(sides, object, planned);
                }
            }
        }

        // Step 5: Commit planned pieces to scene graph
        for (Type7Object object : planned) {
            currentRoomSideCount = count;
            currentSideIndex = sides.indexOf(object.side);
            currentVertexIndex = -1;
            currentSideGroupKey = "R" + roomIdx + "_S" + (object.side.index + 1);
            sidePlacedGroups.putIfAbsent(currentSideGroupKey, new ArrayList<HomePieceOfFurniture>());
            if (object.vertex >= 0 && object.atVertex) {
                // Placed at vertex with optimized non-linear fit
                currentSideIndex = -1;
                currentVertexIndex = object.vertex;
                double[] f = object.footprint;
                createSurroundingFromTemplate(object.piece, (float) f[0], (float) f[1], (float) Math.atan2(f[3], f[2]),
                        object.width, object.depth, object.height, true, true, true);
                currentVertexIndex = -1;
            } else {
                // Placed straight along its host side path
                place(object.side, object.s, object.depthOffset, object.piece, object.width, object.depth, object.height,
                        true, object.shortStretch);
            }
        }
    }

    /**
     * Minimum distance (in cm) an object's footprint must overhang past the end of its host side path
     * to be considered candidate for corner vertex fitting.
     */
    private static final double VERTEX_OVERHANG = 0.1;

    /**
     * Maximum allowable angular deflection (in degrees) that an object over a vertex may rotate away
     * from the corner bisector normal. Beyond 45 degrees, an orthogonal side face of the piece would
     * face the corner rather than its front/back.
     */
    private static final double MAX_VERTEX_TURN = 44;

    /**
     * Minimum depth margin (in cm) by which the rear edge of a piece over a vertex must enclose the
     * room corner vertex, preventing the corner from being exposed.
     */
    private static final double VERTEX_COVER = 1.0;

    /**
     * Represents a planned piece of furniture along the continuous Type 7 surrounding path before
     * committing to the scene graph.
     */
    private static final class Type7Object {
        /** Source furniture template piece. */
        HomePieceOfFurniture piece;
        /** Randomized width, depth, height (-1 if unscaled). */
        float width, depth, height;
        /** Host side path containing this object's reference center. */
        SidePath side;
        /** Longitudinal coordinate s along {@code side}. */
        double s;
        /** Total depth displacement across the side normal. */
        double depthOffset;
        /** User distance offset from wall. */
        double userOffset;
        /** Whether the hosting stretch is shorter than the piece width. */
        boolean shortStretch;
        /**
         * 6-element footprint representation:
         * {@code [center_x, center_y, cos(angle), sin(angle), half_width, half_depth]}.
         */
        double[] footprint;
        /** Index in the face polygon of the vertex this object lies over, or -1 if along a straight side. */
        int vertex = -1;
        /** Whether the footprint is final (false while undergoing corner fitting). */
        boolean settled = true;
        /** Whether the object was successfully fitted at its vertex (true) or placed straight (false). */
        boolean atVertex;
    }

    /**
     * Computes the 6-element footprint array {@code [cx, cy, cos, sin, halfWidth, halfDepth]}
     * for a piece placed straight along a side path at longitudinal coordinate {@code s}.
     *
     * @param s           longitudinal coordinate along {@code side}
     * @return 6-element footprint array
     */
    private double[] straightFootprint(SidePath side, double s, double depthOffset, double halfWidth, double halfDepth) {
        double[] point = side.pointAt(s);
        double[] u = side.directionAt(s);
        double across = currentSideOffset + depthOffset / 2.0;
        return new double[]{point[0] + u[1] * across, point[1] - u[0] * across, u[0], u[1], halfWidth, halfDepth};
    }

    /**
     * Marks the planned objects that stick out past a vertex by more than {@link #VERTEX_OVERHANG} (when objects
     * of both sides do, the closest one), leaving them unsettled to be fitted to the vertex.
     */
    private void findType7VertexObjects(Room face, List<SidePath> faceSides, List<Type7Object> planned) {
        int n = faceSides.size();
        if (measuringUniform || n < 2) {
            return;
        }
        Map<Integer, Type7Object> closest = new HashMap<>();
        Map<Integer, Double> closestDistance = new HashMap<>();
        for (Type7Object object : planned) {
            int k = faceSides.indexOf(object.side);
            double halfWidth = object.footprint[4];
            if (k < 0) {
                continue;
            }
            int vertex;
            double distance;
            if (object.s < halfWidth - VERTEX_OVERHANG) {
                vertex = k;
                distance = object.s;
            } else if (object.s > object.side.length - halfWidth + VERTEX_OVERHANG) {
                vertex = (k + 1) % n;
                distance = object.side.length - object.s;
            } else {
                continue;
            }
            if (isSideExcluded(face, faceSides.get((vertex - 1 + n) % n), n) || isSideExcluded(face, faceSides.get(vertex), n)
                    || vertexGeometry(faceSides, vertex) == null) {
                continue;
            }
            Double previous = closestDistance.get(vertex);
            if (previous == null || distance < previous) {
                closest.put(vertex, object);
                closestDistance.put(vertex, distance);
            }
        }
        for (Map.Entry<Integer, Type7Object> entry : closest.entrySet()) {
            entry.getValue().vertex = entry.getKey();
            entry.getValue().settled = false;
        }
    }

    /**
     * Fits an object over a vertex: turned and shifted (the face that was over the vertex stays over it) to
     * touch the face and the objects on both sides without overlapping them ({@link VertexFit#touching});
     * otherwise squared to the vertex where it is ({@link VertexFit#squareWhereItIs}), or left straight if
     * that overlaps.
     */
    private void fitType7VertexObject(List<SidePath> sides, Type7Object object, List<Type7Object> planned) {
        int n = sides.size();
        SidePath prevSide = sides.get((object.vertex - 1 + n) % n);
        SidePath nextSide = sides.get(object.vertex);
        VertexFit fit = new VertexFit(sides, object.vertex, offsetCorner(sides, object.vertex, object.userOffset, object.userOffset),
                object.footprint[4], object.footprint[5]);

        // Identify the closest settled neighbour objects on both incoming and outgoing sides
        Type7Object before = null, after = null;
        for (Type7Object other : planned) {
            if (other == object || !other.settled) {
                continue;
            }
            if (other.side == prevSide && (object.side != prevSide || other.s < object.s)
                    && (before == null || other.s > before.s)) {
                before = other;
            }
            if (other.side == nextSide && (object.side != nextSide || other.s > object.s)
                    && (after == null || other.s < after.s)) {
                after = other;
            }
        }

        double[] best = null;
        if (before != null && after != null) {
            // Collect any additional settled objects in the vicinity of the vertex
            List<double[]> others = new ArrayList<>();
            double near = 3 * (fit.halfWidth + fit.halfDepth);
            for (Type7Object other : planned) {
                double[] f = other.footprint;
                if (other != object && other != before && other != after && other.settled
                        && Math.hypot(f[0] - fit.vx, f[1] - fit.vy) < near + f[4] + f[5]) {
                    others.add(f);
                }
            }
            // Non-linear dual-contact optimization
            best = fit.touching(before.footprint, after.footprint, others);
        }
        if (best == null) {
            // Fallback: place square to the vertex where it currently stands and translate minimally to touch wall
            best = fit.squareWhereItIs(object.footprint[0], object.footprint[1]);
            if (best != null && fit.cover(best) < 0) {
                best = null;
            }
            // Check that fallback does not overlap settled objects
            for (int i = 0; best != null && i < planned.size(); i++) {
                Type7Object other = planned.get(i);
                if (other != object && other.settled && footprintGap(best, other.footprint) < -0.05) {
                    best = null;
                }
            }
        }
        if (best != null) {
            object.footprint = best;
            object.atVertex = true;
        }
        object.settled = true;
    }

    /**
     * Constructs a triangular bounding polygon representing the solid interior wedge at a convex vertex,
     * extending outward by a large distance (1e5 cm) along incident wall directions.
     *
     * @return 3-point polygon array representing the wedge
     */
    private static double[][] wedge(double vx, double vy, double[] uIn, double[] uOut) {
        double far = 1e5;
        return new double[][]{{vx, vy}, {vx - uIn[0] * far, vy - uIn[1] * far}, {vx + uOut[0] * far, vy + uOut[1] * far}};
    }

    /**
     * Non-linear geometric solver that determines optimal corner fitting poses for Type 7 objects.
     * Evaluates candidate poses parameterized by rotation deflection {@code deg} (from bisector normal)
     * and transverse shift {@code shift} across the bisector line.
     */
    private static final class VertexFit {
        final double vx, vy, bx, by;   // corner vertex coordinates and unit bisector vector
        final double[] uIn, uOut;      // unit directions of incoming and outgoing sides
        final boolean concave;         // true for concave / reflex corners
        final double halfWidth, halfDepth;
        final double baseAngle;        // square bisector angle

        VertexFit(List<SidePath> sides, int vertex, double[] corner, double halfWidth, double halfDepth) {
            int n = sides.size();
            double[] geom = vertexGeometry(sides, vertex);
            this.vx = corner[0];
            this.vy = corner[1];
            this.bx = geom[0];
            this.by = geom[1];
            this.concave = geom[5] > 0;
            this.uIn = sides.get((vertex - 1 + n) % n).lastDirection();
            this.uOut = sides.get(vertex).firstDirection();
            this.halfWidth = halfWidth;
            this.halfDepth = halfDepth;
            this.baseAngle = Math.atan2(uIn[1] + uOut[1], uIn[0] + uOut[0]);
        }

        /**
         * A pose touching the face and both neighbors without overlapping them or the others: a grid of turns
         * (&plusmn;44&deg;) and shifts (&plusmn;reach) gives the 8 best distinct seeds, each refined 5 times on a
         * 5&times; finer grid.
         *
         * @return the footprint, or null if none fits
         */
        double[] touching(double[] before, double[] after, List<double[]> others) {
            final double maxDeg = MAX_VERTEX_TURN;
            final int seedCount = 8;
            double reach = halfWidth + halfDepth;
            double degStep = 2, shiftStep = Math.max(0.25, reach / 20);
            List<double[]> seeds = new ArrayList<>();   // {error, deg, shift}
            for (double deg = -maxDeg; deg <= maxDeg + 1e-9; deg += degStep) {
                for (double shift = -reach; shift <= reach + 1e-9; shift += shiftStep) {
                    double err = error(deg, shift, before, after, others);
                    if (err < Double.MAX_VALUE) {
                        seeds.add(new double[]{err, deg, shift});
                    }
                }
            }
            Collections.sort(seeds, (a, b) -> Double.compare(a[0], b[0]));
            List<double[]> chosen = new ArrayList<>();
            for (double[] seed : seeds) {
                boolean apart = true;
                for (double[] other : chosen) {
                    apart &= Math.abs(other[1] - seed[1]) > 3 * degStep || Math.abs(other[2] - seed[2]) > 3 * shiftStep;
                }
                if (apart) {
                    chosen.add(seed);
                    if (chosen.size() == seedCount) {
                        break;
                    }
                }
            }
            double[] best = null;
            double bestErr = Double.MAX_VALUE;
            for (double[] seed : chosen) {
                double deg = seed[1], shift = seed[2], err = seed[0];
                double dStep = degStep, sStep = shiftStep;
                for (int pass = 0; pass < 5; pass++) {
                    double centerDeg = deg, centerShift = shift;
                    for (int a = -5; a <= 5; a++) {
                        for (int b = -5; b <= 5; b++) {
                            double tryDeg = Math.max(-maxDeg, Math.min(maxDeg, centerDeg + a * dStep / 5));
                            double tryShift = Math.max(-reach, Math.min(reach, centerShift + b * sStep / 5));
                            double e = error(tryDeg, tryShift, before, after, others);
                            if (e < err) {
                                err = e;
                                deg = tryDeg;
                                shift = tryShift;
                            }
                        }
                    }
                    dStep /= 5;
                    sStep /= 5;
                }
                double[] footprint = footprint(baseAngle + Math.toRadians(deg), shift);
                double gapBefore = footprintGap(footprint, before);
                double gapAfter = footprintGap(footprint, after);
                boolean free = true;
                for (double[] other : others) {
                    free &= footprintGap(footprint, other) >= -0.05;
                }
                if (free && cover(footprint) >= 0 && gapBefore >= -0.05 && gapBefore <= 0.3
                        && gapAfter >= -0.05 && gapAfter <= 0.3 && err < bestErr) {
                    best = footprint;
                    bestErr = err;
                }
            }
            return best;
        }

        /**
         * Finds the footprint pose closest to square bisector placement that satisfies an acceptance predicate.
         *
         * @return closest acceptable footprint, or null if none found
         */
        double[] nearest(java.util.function.Predicate<double[]> accepts) {
            double reach = halfWidth + halfDepth;
            double degRange = MAX_VERTEX_TURN, shiftRange = reach, degStep = 2, shiftStep = Math.max(0.25, reach / 20);
            double[] best = null;   // {deg, shift}
            for (int pass = 0; pass < 3; pass++) {
                List<double[]> candidates = new ArrayList<>();
                double deg0 = best != null ? best[0] : 0, shift0 = best != null ? best[1] : 0;
                for (double deg = deg0 - degRange; deg <= deg0 + degRange + 1e-9; deg += degStep) {
                    for (double shift = shift0 - shiftRange; shift <= shift0 + shiftRange + 1e-9; shift += shiftStep) {
                        if (Math.abs(deg) <= MAX_VERTEX_TURN + 1e-9 && Math.abs(shift) <= reach + 1e-9) {
                            candidates.add(new double[]{deg, shift, Math.abs(deg) / MAX_VERTEX_TURN + Math.abs(shift) / reach});
                        }
                    }
                }
                Collections.sort(candidates, (a, b) -> Double.compare(a[2], b[2]));
                for (double[] candidate : candidates) {
                    double[] f = footprint(baseAngle + Math.toRadians(candidate[0]), candidate[1]);
                    if (f != null && cover(f) >= 0 && accepts.test(f)) {
                        best = candidate;
                        break;
                    }
                }
                if (best == null) {
                    return null;
                }
                degRange = degStep;
                shiftRange = shiftStep;
                degStep /= 5;
                shiftStep /= 5;
            }
            return footprint(baseAngle + Math.toRadians(best[0]), best[1]);
        }

        /**
         * Objective error penalty function quantifying the deviation of a candidate pose:
         * penalizes displacement, vertex exposure, and heavily penalizes neighbor/obstacle overlaps (10x penalty).
         *
         * @param deg    rotation angle deflection in degrees
         */
        private double error(double deg, double shift, double[] before, double[] after, List<double[]> others) {
            double[] footprint = footprint(baseAngle + Math.toRadians(deg), shift);
            if (footprint == null) {
                return Double.MAX_VALUE;
            }
            double err = 1e-4 * (Math.abs(deg) + Math.abs(shift)) + 10 * Math.max(0, -cover(footprint));
            for (double[] neighbour : new double[][]{before, after}) {
                double gap = footprintGap(footprint, neighbour);
                err += gap < 0 ? -10 * gap : gap;
            }
            double[][] corners = footprintCorners(footprint);
            for (double[] other : others) {
                err += 10 * Math.max(0, convexOverlap(corners, footprintCorners(other)));
            }
            return err;
        }

        /**
         * Computes the 6-element footprint at rotation {@code angle}, displaced transversely by {@code shift}
         * across the bisector, and translated along the bisector just enough to touch the wall geometry
         * without penetrating it.
         *
         * @param angle absolute orientation angle (radians)
         * @return 6-element footprint array, or null if wall contact cannot be established
         */
        double[] footprint(double angle, double shift) {
            double cos = Math.cos(angle), sin = Math.sin(angle);
            double baseX = vx - by * shift, baseY = vy + bx * shift;
            double d;
            if (concave) {
                // At concave corner: stay in front of both wall planes
                d = -Double.MAX_VALUE;
                for (double[] u : new double[][]{uIn, uOut}) {
                    double nx = u[1], ny = -u[0];
                    double along = bx * nx + by * ny;
                    if (along <= 1e-6) {
                        return null;
                    }
                    d = Math.max(d, (radius(cos, sin, nx, ny) - ((baseX - vx) * nx + (baseY - vy) * ny)) / along);
                }
            } else {
                // At convex corner: separate from the solid wall wedge behind both sides (Separating Axis Theorem)
                double[][] wedge = wedge(vx, vy, uIn, uOut);
                double[][] axes = {{cos, sin}, {-sin, cos}, {uIn[1], -uIn[0]}, {uOut[1], -uOut[0]},
                        {-(uIn[1] + uOut[1]), uIn[0] + uOut[0]}};
                d = Double.MAX_VALUE;
                for (double[] axis : axes) {
                    double length = Math.hypot(axis[0], axis[1]);
                    if (length < 1e-9) {
                        continue;
                    }
                    double ax = axis[0] / length, ay = axis[1] / length;
                    double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
                    for (double[] q : wedge) {
                        double p = q[0] * ax + q[1] * ay;
                        min = Math.min(min, p);
                        max = Math.max(max, p);
                    }
                    double radius = radius(cos, sin, ax, ay);
                    double center = baseX * ax + baseY * ay;
                    double along = bx * ax + by * ay;
                    double t;
                    if (along > 1e-9) {
                        t = (max + radius - center) / along;
                    } else if (along < -1e-9) {
                        t = (min - radius - center) / along;
                    } else {
                        t = center - radius >= max || center + radius <= min ? -Double.MAX_VALUE : Double.MAX_VALUE;
                    }
                    d = Math.min(d, t);
                }
                if (d <= -1e8 || d >= 1e8) {
                    return null;
                }
            }
            return new double[]{baseX + bx * d, baseY + by * d, cos, sin, halfWidth, halfDepth};
        }

        /**
         * Computes the directional half-extent (support radius) of the oriented rectangular piece along normal {@code (nx, ny)}.
         */
        private double radius(double cos, double sin, double nx, double ny) {
            return halfWidth * Math.abs(cos * nx + sin * ny) + halfDepth * Math.abs(-sin * nx + cos * ny);
        }

        /**
         * Computes the footprint aligned squarely to the vertex angle from its current center {@code (x, y)},
         * translated by the minimal distance required to touch the wall geometry without penetrating.
         *
         * @return adjusted 6-element footprint array, or null if invalid
         */
        double[] squareWhereItIs(double x, double y) {
            double cos = Math.cos(baseAngle), sin = Math.sin(baseAngle);
            double[] center;
            if (concave) {
                // Must stay in front of both wall normals
                double[][] normals = {{uIn[1], -uIn[0]}, {uOut[1], -uOut[0]}};
                double[] radius = {radius(cos, sin, normals[0][0], normals[0][1]), radius(cos, sin, normals[1][0], normals[1][1])};
                // Find apex intersection where piece center touches both sides simultaneously
                double n1x = normals[0][0], n1y = normals[0][1], n2x = normals[1][0], n2y = normals[1][1];
                double det = n1x * n2y - n1y * n2x;
                if (Math.abs(det) < 1e-9) {
                    return null;
                }
                double apexX = vx + (radius[0] * n2y - radius[1] * n1y) / det;
                double apexY = vy + (n1x * radius[1] - n2x * radius[0]) / det;
                center = new double[]{apexX, apexY};
                double bestDistance = Math.hypot(apexX - x, apexY - y);

                // Alternatively test touching only one of the sides if closer to (x, y)
                for (int i = 0; i < 2; i++) {
                    double nx = normals[i][0], ny = normals[i][1];
                    double move = radius[i] - ((x - vx) * nx + (y - vy) * ny);
                    double px = x + nx * move, py = y + ny * move;
                    double[] other = normals[1 - i];
                    boolean inFront = (px - vx) * other[0] + (py - vy) * other[1] >= radius[1 - i] - 1e-9;
                    if (inFront && Math.abs(move) < bestDistance) {
                        bestDistance = Math.abs(move);
                        center = new double[]{px, py};
                    }
                }
            } else {
                // At convex corner: project center out of the Minkowski sum of wedge and piece
                double[][] corners = footprintCorners(new double[]{0, 0, cos, sin, halfWidth, halfDepth});
                List<double[]> points = new ArrayList<>();
                for (double[] w : wedge(vx, vy, uIn, uOut)) {
                    for (double[] c : corners) {
                        points.add(new double[]{w[0] + c[0], w[1] + c[1]});
                    }
                }
                List<double[]> hull = convexHull(points);
                center = null;
                double bestDistance = Double.MAX_VALUE;
                for (int i = 0; i < hull.size(); i++) {
                    double[] a = hull.get(i), b = hull.get((i + 1) % hull.size());
                    double dx = b[0] - a[0], dy = b[1] - a[1], l = dx * dx + dy * dy;
                    double t = l == 0 ? 0 : Math.max(0, Math.min(1, ((x - a[0]) * dx + (y - a[1]) * dy) / l));
                    double[] q = {a[0] + t * dx, a[1] + t * dy};
                    double distance = Math.hypot(q[0] - x, q[1] - y);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        center = q;
                    }
                }
                if (center == null) {
                    return null;
                }
            }
            return new double[]{center[0], center[1], cos, sin, halfWidth, halfDepth};
        }

        /**
         * Evaluates whether the corner vertex is properly covered by the rear face of the piece.
         *
         * @param footprint 6-element footprint array
         * @return signed distance of coverage surplus (&gt;= 0 when covered, &lt; 0 when vertex is exposed)
         */
        double cover(double[] footprint) {
            double qx = -by, qy = bx;
            double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
            double[][] corners = footprintCorners(footprint);
            for (double[] corner : new double[][]{corners[2], corners[3]}) {
                double p = (corner[0] - vx) * qx + (corner[1] - vy) * qy;
                min = Math.min(min, p);
                max = Math.max(max, p);
            }
            return Math.min(-min, max) - VERTEX_COVER;
        }
    }

    /**
     * Computes the 2D convex hull of a set of points in counter-clockwise order using Andrew's monotone chain algorithm.
     *
     * @param points list of 2D points {@code [x, y]}
     */
    private static List<double[]> convexHull(List<double[]> points) {
        List<double[]> sorted = new ArrayList<>(points);
        Collections.sort(sorted, (a, b) -> a[0] != b[0] ? Double.compare(a[0], b[0]) : Double.compare(a[1], b[1]));
        int n = sorted.size();
        double[][] hull = new double[2 * n][];
        int k = 0;
        // Build lower hull
        for (int i = 0; i < n; i++) {
            while (k >= 2 && cross(hull[k - 2], hull[k - 1], sorted.get(i)) <= 0) {
                k--;
            }
            hull[k++] = sorted.get(i);
        }
        // Build upper hull
        for (int i = n - 2, lower = k + 1; i >= 0; i--) {
            while (k >= lower && cross(hull[k - 2], hull[k - 1], sorted.get(i)) <= 0) {
                k--;
            }
            hull[k++] = sorted.get(i);
        }
        return new ArrayList<>(java.util.Arrays.asList(hull).subList(0, Math.max(1, k - 1)));
    }

    /**
     * 2D cross product of vectors {@code (a - o)} and {@code (b - o)}.
     *
     * @return positive if counter-clockwise turn, negative if clockwise, zero if collinear
     */
    private static double cross(double[] o, double[] a, double[] b) {
        return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0]);
    }

    /**
     * Computes the 4 corner coordinates of an oriented rectangular footprint.
     *
     * @param f 6-element footprint array {@code [cx, cy, cos, sin, halfWidth, halfDepth]}
     * @return 4x2 array of corner coordinates
     */
    private static double[][] footprintCorners(double[] f) {
        double ax = f[2] * f[4], ay = f[3] * f[4], dx = -f[3] * f[5], dy = f[2] * f[5];
        return new double[][]{{f[0] - ax - dx, f[1] - ay - dy}, {f[0] + ax - dx, f[1] + ay - dy},
            {f[0] + ax + dx, f[1] + ay + dy}, {f[0] - ax + dx, f[1] - ay + dy}};
    }

    /**
     * Calculates the minimum clearance gap between two footprints, or the negative penetration depth
     * if they overlap.
     *
     * @return Euclidean distance (&gt; 0) if disjoint, or negative penetration depth (&lt;= 0) if overlapping
     */
    private static double footprintGap(double[] a, double[] b) {
        double[][] pa = footprintCorners(a), pb = footprintCorners(b);
        double overlap = convexOverlap(pa, pb);
        if (overlap > 0) {
            return -overlap;
        }
        double gap = Double.MAX_VALUE;
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                gap = Math.min(gap, Math.min(
                        pointSegmentDistance(pa[i], pb[j], pb[(j + 1) % 4]),
                        pointSegmentDistance(pb[j], pa[i], pa[(i + 1) % 4])));
            }
        }
        return gap;
    }

    /**
     * Computes the penetration depth of two overlapping convex polygons using the Separating Axis Theorem (SAT).
     *
     * @param pa 2D vertices of polygon A
     * @param pb 2D vertices of polygon B
     * @return penetration depth (&gt; 0 if overlapping, &lt;= 0 if separated)
     */
    private static double convexOverlap(double[][] pa, double[][] pb) {
        double overlap = Double.MAX_VALUE;
        for (double[][] polygon : new double[][][]{pa, pb}) {
            for (int i = 0; i < polygon.length; i++) {
                double[] p = polygon[i], q = polygon[(i + 1) % polygon.length];
                double ax = q[1] - p[1], ay = p[0] - q[0], l = Math.hypot(ax, ay);
                if (l < 1e-12) {
                    continue;
                }
                ax /= l;
                ay /= l;
                double minA = Double.MAX_VALUE, maxA = -Double.MAX_VALUE, minB = Double.MAX_VALUE, maxB = -Double.MAX_VALUE;
                for (double[] c : pa) {
                    double v = c[0] * ax + c[1] * ay;
                    minA = Math.min(minA, v);
                    maxA = Math.max(maxA, v);
                }
                for (double[] c : pb) {
                    double v = c[0] * ax + c[1] * ay;
                    minB = Math.min(minB, v);
                    maxB = Math.max(maxB, v);
                }
                overlap = Math.min(overlap, Math.min(maxA, maxB) - Math.max(minA, minB));
            }
        }
        return overlap;
    }

    /** Calculates the shortest Euclidean distance from a 2D point {@code p} to a line segment {@code [a, b]}. */
    private static double pointSegmentDistance(double[] p, double[] a, double[] b) {
        double dx = b[0] - a[0], dy = b[1] - a[1], l = dx * dx + dy * dy;
        double t = l == 0 ? 0 : Math.max(0, Math.min(1, ((p[0] - a[0]) * dx + (p[1] - a[1]) * dy) / l));
        return Math.hypot(p[0] - a[0] - t * dx, p[1] - a[1] - t * dy);
    }

    // ---- Gradual property progression & Vertical stacking ----

    /**
     * Determines whether stochastic or gradual property variations should be applied to a piece.
     * Corner pieces only receive variations if explicitly allowed by {@link SurroundRandomSettings#applyToCorners}.
     *
     * @param isCorner true if the target piece is placed at a corner vertex
     */
    private static boolean appliesTo(SurroundRandomSettings settings, boolean isCorner) {
        return settings != null && (!isCorner || settings.applyToCorners);
    }

    /**
     * Produces a sanitized copy of random/gradual settings tailored for a specific piece.
     * For corner pieces, depth randomization and orientation jitter are forcibly disabled to prevent
     * corner pieces from detaching from wall surfaces or colliding with adjoining side furniture rows.
     *
     * @param isCorner true if the target piece is at a corner vertex
     */
    private static SurroundRandomSettings forPiece(SurroundRandomSettings settings, boolean isCorner) {
        if (!isCorner) {
            return settings;
        }
        SurroundRandomSettings safe = settings.copy();
        safe.enabled.put("depth", false);
        safe.enabled.put("angle_jitter", false);
        return safe;
    }

    /**
     * Applies gradual property progression (color gradients, size ramps, elevation steps) across the
     * base level of placed furniture according to the configured {@code gradualScope}.
     */
    private void applyGradualToFirstLevel(SurroundConfig config) {
        if (!config.enableGradualProps || config.gradualSettings == null || !config.gradualSettings.hasAnyGradual()
                || firstLevelPlacedFurniture.isEmpty()) {
            return;
        }
        SurroundRandomSettings grad = config.gradualSettings;
        if (grad.gradualScope == SurroundRandomSettings.SCOPE_PER_LEVEL && config.enableVerticalStacking) {
            // When scoped per-level, all pieces on the base level receive the t = 0.0 baseline value
            for (HomePieceOfFurniture piece : firstLevelPlacedFurniture) {
                boolean isCorner = cornerBasePieces.contains(piece);
                if (appliesTo(grad, isCorner)) {
                    MaterialUtils.applyGradualProperties(piece, forPiece(grad, isCorner), 0.0, 0);
                }
            }
        } else if (grad.gradualScope == SurroundRandomSettings.SCOPE_PER_SIDE) {
            // Progression restarts from 0.0 to 1.0 along each individual side group
            for (List<HomePieceOfFurniture> side : sidePlacedGroups.values()) {
                applyGradualInOrder(inShownOrder(side), grad, config.reverseDirection);
            }
        } else {
            // Global progression: spans sequentially across all placed furniture from first to last
            applyGradualInOrder(inShownOrder(firstLevelPlacedFurniture), grad, config.reverseDirection);
        }
    }

    /**
     * Returns the pieces in the direction shown to the user: the pieces of each interior room, placed
     * along its traversal, are taken backwards.
     */
    private List<HomePieceOfFurniture> inShownOrder(List<HomePieceOfFurniture> pieces) {
        List<HomePieceOfFurniture> ordered = new ArrayList<>(pieces.size());
        int i = 0;
        while (i < pieces.size()) {
            Room room = pieceRooms.get(pieces.get(i));
            int j = i;
            while (j < pieces.size() && pieceRooms.get(pieces.get(j)) == room) {
                j++;
            }
            List<HomePieceOfFurniture> run = new ArrayList<>(pieces.subList(i, j));
            if (room != null && interiorFor(room)) {
                Collections.reverse(run);
            }
            ordered.addAll(run);
            i = j;
        }
        return ordered;
    }

    /**
     * Iterates through an ordered list of pieces and applies gradual properties parameterized by
     * normalized progression ratio {@code t = idx / (m - 1)} in {@code [0.0, 1.0]}.
     *
     * @param reverse true if progression should be inverted from end to start
     */
    private void applyGradualInOrder(List<HomePieceOfFurniture> pieces, SurroundRandomSettings grad, boolean reverse) {
        List<HomePieceOfFurniture> eligible = new ArrayList<>();
        for (HomePieceOfFurniture p : pieces) {
            if (appliesTo(grad, cornerBasePieces.contains(p))) {
                eligible.add(p);
            }
        }
        int m = eligible.size();
        for (int i = 0; i < m; i++) {
            int idx = reverse ? (m - 1 - i) : i;
            HomePieceOfFurniture p = eligible.get(i);
            MaterialUtils.applyGradualProperties(p, forPiece(grad, cornerBasePieces.contains(p)),
                    m > 1 ? (double) idx / (m - 1) : 0.0, idx);
        }
    }

    /**
     * Stacks levels of copies above the first one: each copy (of the clean piece when each level draws its own
     * properties) goes up by the piece height or the chosen step, keeps the outward normal of its side, is
     * dropped if it collides, and gets its own random and gradual properties.
     */
    private void applyVerticalStacking(SurroundConfig config) {
        if (!config.enableVerticalStacking || config.verticalStackCount <= 0 || firstLevelPlacedFurniture.isEmpty()) {
            return;
        }
        int count = firstLevelPlacedFurniture.size();
        float[] elevations = new float[count];
        for (int i = 0; i < count; i++) {
            elevations[i] = firstLevelPlacedFurniture.get(i).getElevation();
        }
        boolean independentRandom = config.enableRandomProps
                && config.randomSettings != null
                && config.randomSettings.independentStackRandom
                && config.randomSettings.hasAny();
        // Place of each base piece in the direction shown, for the gradual properties of its copies
        Map<HomePieceOfFurniture, Integer> shownIndices = new IdentityHashMap<>();
        List<HomePieceOfFurniture> shownOrder = inShownOrder(firstLevelPlacedFurniture);
        for (int i = 0; i < count; i++) {
            shownIndices.put(shownOrder.get(i), i);
        }
        for (int level = 1; level <= config.verticalStackCount; level++) {
            for (int i = 0; i < count; i++) {
                HomePieceOfFurniture basePiece = firstLevelPlacedFurniture.get(i);
                HomePieceOfFurniture cleanSource = independentRandom ? cleanBasePieces.get(basePiece) : null;
                HomePieceOfFurniture stackedCopy = (cleanSource != null ? cleanSource : basePiece).clone();
                MaterialUtils.copyOutwardNormal(basePiece, stackedCopy);
                // Each copy ascends by custom elevation step or by the height of the base object
                elevations[i] += config.useCustomStackElevation ? (float) config.customStackElevation : basePiece.getHeight();
                stackedCopy.setElevation(elevations[i]);
                pieceRooms.put(stackedCopy, pieceRooms.get(basePiece));
                pieceSideIndices.put(stackedCopy, pieceSideIndices.get(basePiece));
                pieceVertexIndices.put(stackedCopy, pieceVertexIndices.get(basePiece));
                pieceRoomSideCounts.put(stackedCopy, pieceRoomSideCounts.get(basePiece));
                if (config.avoidCollisions && hasCollision(stackedCopy)) {
                    continue; // Skip piece if collision detected with walls or existing geometry
                }
                boolean isCorner = cornerBasePieces.contains(basePiece);
                if (independentRandom && appliesTo(config.randomSettings, isCorner)) {
                    MaterialUtils.applyRandomProperties(stackedCopy, forPiece(config.randomSettings, isCorner), randomPropsRng);
                }
                SurroundRandomSettings grad = config.gradualSettings;
                if (config.enableGradualProps && grad != null && grad.hasAnyGradual() && appliesTo(grad, isCorner)) {
                    if (grad.gradualScope == SurroundRandomSettings.SCOPE_PER_LEVEL) {
                        double levelT = config.verticalStackCount > 0 ? (double) level / config.verticalStackCount : 1.0;
                        MaterialUtils.applyGradualProperties(stackedCopy, forPiece(grad, isCorner), levelT, level);
                    } else if (independentRandom) {
                        int shown = shownIndices.get(basePiece);
                        int idx = config.reverseDirection ? (count - 1 - shown) : shown;
                        MaterialUtils.applyGradualProperties(stackedCopy, forPiece(grad, isCorner),
                                count > 1 ? (double) idx / (count - 1) : 0.0, idx);
                    }
                }
                newlyPlacedFurniture.add(stackedCopy);
                placedLevels.put(stackedCopy, placedLevels.get(basePiece));
                String sideKey = basePieceToSideKey.get(basePiece);
                if (sideKey != null && sidePlacedGroups.containsKey(sideKey)) {
                    sidePlacedGroups.get(sideKey).add(stackedCopy);
                }
            }
        }
    }

    // ---- Placing pieces & Collision / Floor Hole checks (Item 6) ----

    /**
     * Creates a placed piece from the template: sizes it (when resizable) and turns it, drops it if it crosses
     * a floor opening on an upper level or collides (unless ignored), remembers its outward normal and a clean
     * copy for stacking, applies the random properties and records it.
     *
     * @param width                  width to give it (-1 keeps the template's); likewise depth and height
     * @param isCornerPiece          whether it's a corner piece or a type 7 object fitted to a vertex
     */
    private void createSurroundingFromTemplate(HomePieceOfFurniture template, float x, float y, float angle,
            float width, float depth, float height, boolean applyTemplateBaseAngle, boolean ignoreCollisions,
            boolean isCornerPiece) {
        if (measuringUniform) {
            return;   // Type 8 first pass: dry-run to measure stretch lengths only
        }
        HomePieceOfFurniture fCopy = template.clone();
        float pieceAngle = applyTemplateBaseAngle ? fCopy.getAngle() : 0f;
        if (width != -1 && fCopy.isResizable()) {
            fCopy.setWidth(width);
            fCopy.setDepth(depth);
            if (height != -1) {
                fCopy.setHeight(height);
            }
        }
        fCopy.setX(x);
        fCopy.setY(y);
        if (angle < 0) {
            angle = (float) Math.toRadians(180) + ((float) Math.toRadians(180) + angle);
        }
        fCopy.setAngle(pieceAngle + angle);

        // Item 6: When working on levels above level 0, check floor holes behavior
        if (isBlockedByUpperLevelFloorHole(fCopy)) {
            return;
        }

        if (activeConfig.avoidCollisions && !ignoreCollisions && hasCollision(fCopy)) {
            return;
        }

        if (!isCornerPiece) {
            MaterialUtils.registerOutwardNormal(fCopy, currentWallNormalX, currentWallNormalY);
        }
        HomePieceOfFurniture cleanClone = fCopy.clone();
        MaterialUtils.copyOutwardNormal(fCopy, cleanClone);
        cleanBasePieces.put(fCopy, cleanClone);
        if (isCornerPiece) {
            cornerBasePieces.add(fCopy);
        }
        SurroundRandomSettings random = activeConfig.randomSettings;
        if (activeConfig.enableRandomProps && random != null && random.hasAny() && appliesTo(random, isCornerPiece)) {
            MaterialUtils.applyRandomProperties(fCopy, forPiece(random, isCornerPiece), randomPropsRng);
        }

        newlyPlacedFurniture.add(fCopy);
        firstLevelPlacedFurniture.add(fCopy);
        placedLevels.put(fCopy, currentProcessingRoom != null ? currentProcessingRoom.getLevel() : null);
        pieceRooms.put(fCopy, currentProcessingRoom);
        pieceSideIndices.put(fCopy, currentSideIndex);
        pieceVertexIndices.put(fCopy, currentVertexIndex);
        pieceRoomSideCounts.put(fCopy, currentRoomSideCount);
        if (currentSideGroupKey != null) {
            sidePlacedGroups.computeIfAbsent(currentSideGroupKey, k -> new ArrayList<>()).add(fCopy);
            basePieceToSideKey.put(fCopy, currentSideGroupKey);
        }
    }

    /**
     * On upper levels with "Do not cross openings", whether the piece is over a floor opening; if there's no
     * floor below it either, sets {@link SurroundConfig#skippedDueToNoFloorBelow} so the user is warned.
     */
    private boolean isBlockedByUpperLevelFloorHole(HomePieceOfFurniture candidate) {
        if (activeConfig.upperLevelFloorHolesMode != SurroundConfig.FLOOR_HOLES_NO_PASS || home == null) {
            return false;
        }
        Level roomLevel = (currentProcessingRoom != null) ? currentProcessingRoom.getLevel() : candidate.getLevel();
        if (roomLevel == null || !isAboveLowestLevel(roomLevel)) {
            return false;
        }
        List<Room> sameLevelRooms = new ArrayList<>();
        List<Room> lowerLevelRooms = new ArrayList<>();
        for (Room r : home.getRooms()) {
            Level rl = r.getLevel();
            if (rl == roomLevel) {
                sameLevelRooms.add(r);
            } else if (rl == null || rl.getElevation() < roomLevel.getElevation() - 1e-3) {
                lowerLevelRooms.add(r);
            }
        }
        if (sameLevelRooms.isEmpty()) {
            return false;   // No floor polygons drawn on this level: cannot verify
        }
        // Sample candidate footprint: center point plus 4 corners inset by 1 cm
        float[][] corners = getScaledPolygon(candidate, -2f, -2f);
        List<float[]> points = new ArrayList<>();
        points.add(new float[]{candidate.getX(), candidate.getY()});
        for (float[] corner : corners) {
            points.add(corner);
        }
        boolean overHole = false;
        boolean hasFloorBelow = true;
        for (float[] point : points) {
            if (isOnAnyFloor(point[0], point[1], sameLevelRooms)) {
                continue;
            }
            overHole = true;
            if (!isOnAnyFloor(point[0], point[1], lowerLevelRooms)) {
                hasFloorBelow = false;
            }
        }
        if (overHole && !hasFloorBelow) {
            activeConfig.skippedDueToNoFloorBelow = true;
        }
        return overHole;   // Do not pass over floor hole
    }

    /** Checks whether point {@code (x, y)} lies inside the polygon boundary of any room in the list. */
    private static boolean isOnAnyFloor(float x, float y, List<Room> rooms) {
        for (Room r : rooms) {
            if (SurroundTarget.toPath(r.getPoints()).contains(x, y)) {
                return true;
            }
        }
        return false;
    }

    /** Returns true if the level's elevation is strictly greater than the lowest level elevation in the home. */
    private boolean isAboveLowestLevel(Level level) {
        List<Level> levels = home.getLevels();
        if (levels == null || levels.isEmpty()) {
            return false;
        }
        float lowest = Float.MAX_VALUE;
        for (Level l : levels) {
            lowest = Math.min(lowest, l.getElevation());
        }
        return level.getElevation() > lowest + 1e-3f;
    }

    /**
     * Performs an exact 2D boolean area intersection test between two polygons using {@link java.awt.geom.Area}.
     * Unlike SAT (which tests convex hulls), this handles non-convex, curved, or L-shaped geometries accurately.
     *
     * @return true if polygons share an overlapping area greater than 0.01 x 0.01 cm
     */
    private static boolean polygonsOverlapExact(float[][] a, float[][] b) {
        java.awt.geom.Rectangle2D boundsA = SurroundTarget.toPath(a).getBounds2D();
        java.awt.geom.Rectangle2D boundsB = SurroundTarget.toPath(b).getBounds2D();
        if (!boundsA.intersects(boundsB)) {
            return false;
        }
        Area overlap = new Area(SurroundTarget.toPath(a));
        overlap.intersect(new Area(SurroundTarget.toPath(b)));
        if (overlap.isEmpty()) {
            return false;
        }
        java.awt.geom.Rectangle2D bounds = overlap.getBounds2D();
        return bounds.getWidth() > 0.01 && bounds.getHeight() > 0.01;
    }

    /**
     * Calculates the peak wall height along the longitudinal portion of a wall where a polygon footprint extends.
     * For sloping walls (walls with different start and end heights), projects the polygon vertices onto
     * the wall's baseline segment to interpolate the maximum top elevation over that specific span.
     */
    private float wallHeightAlong(Wall wall, float[][] polygon) {
        Float height = wall.getHeight();
        float h = height != null ? height : (home != null ? home.getWallHeight() : 250f);
        Float heightAtEnd = wall.getHeightAtEnd();
        if (heightAtEnd != null && Math.abs(heightAtEnd - h) > 1e-3f) {
            double dx = wall.getXEnd() - wall.getXStart();
            double dy = wall.getYEnd() - wall.getYStart();
            double len2 = dx * dx + dy * dy;
            if (len2 > 1e-6) {
                double tMin = 1, tMax = 0;
                for (float[] p : polygon) {
                    double t = ((p[0] - wall.getXStart()) * dx + (p[1] - wall.getYStart()) * dy) / len2;
                    tMin = Math.min(tMin, t);
                    tMax = Math.max(tMax, t);
                }
                tMin = Math.max(0, Math.min(1, tMin));
                tMax = Math.max(0, Math.min(1, tMax));
                h = (float) (h + (heightAtEnd - h) * (heightAtEnd > h ? tMax : tMin));
            } else {
                h = Math.max(h, heightAtEnd);
            }
        }
        return h > 0 ? h : 250f;
    }

    /**
     * Checks if vertical elevation intervals {@code [z0, z1]} and {@code [pieceZ0, pieceZ0 + pieceH]}
     * intersect within an effective vertical tolerance.
     */
    private static boolean overlapsInHeight(float z0, float z1, HomePieceOfFurniture piece, float zTol) {
        float pieceZ0 = piece.getElevation();
        float pieceH = piece.getHeight();
        float effTol = Math.min(zTol, Math.min(z1 - z0, pieceH) * 0.45f);
        return z1 > pieceZ0 + effTol && z0 < pieceZ0 + pieceH - effTol;
    }

    /**
     * Whether a placed piece is on the side of the candidate or is a corner piece at one of its ends: with
     * avoidNewlyGenerated, those don't block each other.
     *
     * @param candSide side of the candidate (-1 for a corner piece)
     * @param candVert vertex of the candidate (-1 for a side piece)
     */
    private boolean isSameSideOrVertexEnd(Room candRoom, int candSide, int candVert, int candCount,
            HomePieceOfFurniture placed) {
        Room placedRoom = pieceRooms.get(placed);
        if (candRoom == null || placedRoom != candRoom || candCount <= 0) {
            return false;
        }
        Integer placedSideObj = pieceSideIndices.get(placed);
        Integer placedVertObj = pieceVertexIndices.get(placed);
        int placedSide = placedSideObj != null ? placedSideObj : -1;
        int placedVert = placedVertObj != null ? placedVertObj : -1;

        if (candSide >= 0) {
            // Candidate is on side candSide (spans from vertex candSide to vertex (candSide + 1) % candCount)
            if (placedSide == candSide) {
                return true; // Same side (including extra end objects and stacked tiers of this side)
            }
            int vStart = candSide;
            int vEnd = (candSide + 1) % candCount;
            if (placedVert == vStart || placedVert == vEnd) {
                return true; // Corner piece at the start or end vertex of this side
            }
        }
        if (candVert >= 0) {
            // Candidate is a corner piece at vertex candVert
            if (placedVert == candVert) {
                return true; // Same vertex
            }
            int sPrev = (candVert - 1 + candCount) % candCount;
            int sNext = candVert;
            if (placedSide == sPrev || placedSide == sNext) {
                return true; // Either of the two sides meeting at this vertex
            }
        }
        return false;
    }

    /**
     * Whether the candidate hits existing furniture (doors and windows are gaps instead), walls (as prisms of
     * their height), nearby floors (avoidNearbyFloors, except rooms merged into the face) or objects placed on
     * other sides (avoidNewlyGenerated).
     */
    /** Computes fast 2D axis-aligned bounding box [minX, minY, maxX, maxY] for a polygon. */
    private static float[] bbox(float[][] poly) {
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (float[] p : poly) {
            if (p[0] < minX) minX = p[0];
            if (p[0] > maxX) maxX = p[0];
            if (p[1] < minY) minY = p[1];
            if (p[1] > maxY) maxY = p[1];
        }
        return new float[]{minX, minY, maxX, maxY};
    }

    /** Fast AABB overlap check between two [minX, minY, maxX, maxY] bounding boxes. */
    private static boolean bboxOverlap(float[] a, float[] b) {
        return a[2] >= b[0] && a[0] <= b[2] && a[3] >= b[1] && a[1] <= b[3];
    }

    /**
     * Precomputes polygon vertices and AABBs for existing furniture, walls, and nearby floors
     * on the active face's level so that per-piece collision checks avoid repeated allocations
     * and costly Area constructions when thousands of small surrounding objects are placed.
     */
    private void prepareCollisionCaches(Room face) {
        Level roomLevel = face != null ? face.getLevel() : null;
        // 1. Existing furniture
        List<float[][]> exP = new ArrayList<>();
        List<float[]> exB = new ArrayList<>();
        for (HomePieceOfFurniture existing : existingHomeFurniture) {
            if (existing.isDoorOrWindow()) {
                continue;
            }
            if (roomLevel != null && existing.getLevel() != null && existing.getLevel() != roomLevel) {
                continue;
            }
            float[][] pts = existing.getPoints();
            exP.add(pts);
            exB.add(bbox(pts));
        }
        existingPolys = exP.toArray(new float[0][][]);
        existingBounds = exB.toArray(new float[0][]);

        // 2. Walls on level
        List<Wall> wList = new ArrayList<>();
        List<float[][]> wP = new ArrayList<>();
        List<float[]> wB = new ArrayList<>();
        if (home != null && home.getWalls() != null) {
            for (Wall wall : home.getWalls()) {
                if (roomLevel != null && wall.getLevel() != null && !wall.isAtLevel(roomLevel)) {
                    continue;
                }
                float[][] pts = wall.getPoints();
                wList.add(wall);
                wP.add(pts);
                wB.add(bbox(pts));
            }
        }
        activeWalls = wList.toArray(new Wall[0]);
        wallPolys = wP.toArray(new float[0][][]);
        wallBounds = wB.toArray(new float[0][]);

        // 3. Nearby floors on level
        List<float[][]> fP = new ArrayList<>();
        List<float[]> fB = new ArrayList<>();
        List<Room> currentSourceRooms = face != null && activeConfig.faceSourceRooms != null
                ? activeConfig.faceSourceRooms.get(face) : null;
        if (activeConfig.avoidNearbyFloors && currentSourceRooms != null && home != null && home.getRooms() != null) {
            for (Room otherRoom : home.getRooms()) {
                if (otherRoom == face || currentSourceRooms.contains(otherRoom)) {
                    continue;
                }
                if (roomLevel != null && otherRoom.getLevel() != null && !otherRoom.isAtLevel(roomLevel)) {
                    continue;
                }
                float[][] pts = otherRoom.getPoints();
                fP.add(pts);
                fB.add(bbox(pts));
            }
        }
        nearbyFloorPolys = fP.toArray(new float[0][][]);
        nearbyFloorBounds = fB.toArray(new float[0][]);
    }

    private boolean hasCollision(HomePieceOfFurniture candidate) {
        checkCancelled();
        float colTol = (float) effectiveCollisionFitTolerance();
        // Shrink candidate footprint slightly by 0.5 cm default + 2 * colTol to prevent grazing boundary rejections
        float shrinkW = -Math.min(candidate.getWidth() * 0.8f, 0.5f + 2.0f * colTol);
        float shrinkD = -Math.min(candidate.getDepth() * 0.8f, 0.5f + 2.0f * colTol);
        float[][] candidatePoly = getScaledPolygon(candidate, shrinkW, shrinkD);
        float[] candBox = bbox(candidatePoly);
        float candZ0 = candidate.getElevation();
        float candZ1 = candZ0 + candidate.getHeight();
        float zTol = 0.1f + colTol;
        Level roomLevel = (currentProcessingRoom != null) ? currentProcessingRoom.getLevel() : null;

        // 1. Existing furniture/models of the home (using precomputed AABBs + SAT)
        if (existingPolys != null) {
            int idx = 0;
            for (HomePieceOfFurniture existing : existingHomeFurniture) {
                if (existing.isDoorOrWindow()) {
                    continue;
                }
                if (roomLevel != null && existing.getLevel() != null && existing.getLevel() != roomLevel) {
                    continue;
                }
                float[] eb = existingBounds[idx];
                float[][] ep = existingPolys[idx++];
                if (!bboxOverlap(candBox, eb) || !overlapsInHeight(candZ0, candZ1, existing, zTol)) {
                    continue;
                }
                if (polygonsOverlapSAT(candidatePoly, ep)) {
                    return true;
                }
            }
        }

        // 2. Nearby walls (fast AABB rejection + SAT for 4-vertex straight walls, Area only for curved walls)
        if (activeWalls != null && activeWalls.length > 0) {
            float levelElevation = roomLevel != null ? roomLevel.getElevation() : 0;
            for (int i = 0; i < activeWalls.length; i++) {
                if (!bboxOverlap(candBox, wallBounds[i])) {
                    continue;
                }
                Wall wall = activeWalls[i];
                float[][] wp = wallPolys[i];
                float wallBottom = wall.getLevel() != null && roomLevel != null ? wall.getLevel().getElevation() - levelElevation : 0;
                float wallH = wallHeightAlong(wall, candidatePoly);
                float effWallZTol = Math.min(zTol, Math.min(candZ1 - candZ0, wallH) * 0.45f);
                if (candZ1 <= wallBottom + effWallZTol || candZ0 >= wallBottom + wallH - effWallZTol) {
                    continue;
                }
                if (wp.length == 4 ? polygonsOverlapSAT(candidatePoly, wp) : polygonsOverlapExact(candidatePoly, wp)) {
                    return true;
                }
            }
        }

        // 3. Nearby floors when avoidNearbyFloors is enabled
        if (nearbyFloorPolys != null && nearbyFloorPolys.length > 0) {
            for (int i = 0; i < nearbyFloorPolys.length; i++) {
                if (!bboxOverlap(candBox, nearbyFloorBounds[i])) {
                    continue;
                }
                if (polygonsOverlapExact(candidatePoly, nearbyFloorPolys[i])) {
                    return true;
                }
            }
        }

        // 4. Newly generated objects during this surround operation, ONLY from OTHER sides or OTHER rooms
        if (activeConfig.avoidNewlyGenerated) {
            Room candRoom = pieceRooms.containsKey(candidate) ? pieceRooms.get(candidate) : currentProcessingRoom;
            int candSide = pieceSideIndices.containsKey(candidate) ? pieceSideIndices.get(candidate) : currentSideIndex;
            int candVert = pieceVertexIndices.containsKey(candidate) ? pieceVertexIndices.get(candidate) : currentVertexIndex;
            int candCount = pieceRoomSideCounts.containsKey(candidate) ? pieceRoomSideCounts.get(candidate) : currentRoomSideCount;

            for (HomePieceOfFurniture placed : newlyPlacedFurniture) {
                if (isSameSideOrVertexEnd(candRoom, candSide, candVert, candCount, placed)) {
                    continue;
                }
                if (!overlapsInHeight(candZ0, candZ1, placed, zTol)) {
                    continue;
                }
                // Quick center-distance filter before polygon scaling and SAT
                float dx = Math.abs(candidate.getX() - placed.getX());
                float dy = Math.abs(candidate.getY() - placed.getY());
                float maxSpan = (candidate.getWidth() + candidate.getDepth() + placed.getWidth() + placed.getDepth()) * 0.6f;
                if (dx > maxSpan || dy > maxSpan) {
                    continue;
                }
                if (polygonsOverlapSAT(candidatePoly, getScaledPolygon(placed, -0.5f, -0.5f))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * The footprint of the piece grown (positive) or shrunk (negative) by those amounts along its own axes,
     * half on each side; taken from its points, so it works for non-resizable pieces too.
     */
    private static float[][] getScaledPolygon(HomePieceOfFurniture piece, float deltaWidth, float deltaDepth) {
        float[][] points = piece.getPoints();
        if (points.length != 4) {
            return points;
        }
        double cx = 0, cy = 0;
        for (float[] point : points) {
            cx += point[0] / 4.0;
            cy += point[1] / 4.0;
        }
        double ux = points[1][0] - points[0][0], uy = points[1][1] - points[0][1];   // along the width
        double vx = points[2][0] - points[1][0], vy = points[2][1] - points[1][1];   // along the depth
        double lu = Math.hypot(ux, uy), lv = Math.hypot(vx, vy);
        if (lu < 1e-6 || lv < 1e-6) {
            return points;
        }
        ux /= lu;
        uy /= lu;
        vx /= lv;
        vy /= lv;
        double halfWidth = Math.max(0.5, lu / 2 + deltaWidth / 2);
        double halfDepth = Math.max(0.5, lv / 2 + deltaDepth / 2);
        float[][] scaled = new float[4][];
        for (int i = 0; i < 4; i++) {
            double a = (points[i][0] - cx) * ux + (points[i][1] - cy) * uy;
            double b = (points[i][0] - cx) * vx + (points[i][1] - cy) * vy;
            double na = Math.signum(a) * halfWidth, nb = Math.signum(b) * halfDepth;
            scaled[i] = new float[]{(float) (cx + na * ux + nb * vx), (float) (cy + na * uy + nb * vy)};
        }
        return scaled;
    }

    /**
     * Evaluates 2D polygon intersection between two convex polygons using the Separating Axis Theorem (SAT).
     *
     * @return true if polygons intersect
     */
    private static boolean polygonsOverlapSAT(float[][] polyA, float[][] polyB) {
        return !hasSeparatingAxis(polyA, polyB) && !hasSeparatingAxis(polyB, polyA);
    }

    /** Checks if a separating axis exists when projecting both polygons onto the edge normals of {@code polyA}. */
    private static boolean hasSeparatingAxis(float[][] polyA, float[][] polyB) {
        int n = polyA.length;
        for (int i = 0; i < n; i++) {
            int next = (i + 1) % n;
            double axisX = -(polyA[next][1] - polyA[i][1]);
            double axisY = polyA[next][0] - polyA[i][0];
            double len = Math.hypot(axisX, axisY);
            if (len < 1e-6) {
                continue;
            }
            axisX /= len;
            axisY /= len;
            double minA = Double.POSITIVE_INFINITY, maxA = Double.NEGATIVE_INFINITY;
            for (float[] p : polyA) {
                double proj = p[0] * axisX + p[1] * axisY;
                minA = Math.min(minA, proj);
                maxA = Math.max(maxA, proj);
            }
            double minB = Double.POSITIVE_INFINITY, maxB = Double.NEGATIVE_INFINITY;
            for (float[] p : polyB) {
                double proj = p[0] * axisX + p[1] * axisY;
                minB = Math.min(minB, proj);
                maxB = Math.max(maxB, proj);
            }
            if (maxA < minB || maxB < minA) {
                return true; // Separating axis found
            }
        }
        return false;
    }

    /**
     * Computes the effective surrounding width and depth of a piece based on its orientation angle.
     * For orthogonal angles (0, 90, 180, 270 degrees), returns exact model dimensions or transposed dimensions.
     * For arbitrary oblique angles, computes the bounding box encompassing all 4 rotated footprint vertices.
     *
     * @return 2-element array {@code [effectiveWidth, effectiveDepth]}
     */
    double[] getPieceSurroundingDimensions(HomePieceOfFurniture piece) {
        double sWidth, sDepth;
        float pieceAngle = (float) Math.toDegrees(piece.getAngle());
        pieceAngle = ((pieceAngle % 360f) + 360f) % 360f;
        if (Math.abs(pieceAngle) < 1e-3 || Math.abs(pieceAngle - 180f) < 1e-3 || Math.abs(pieceAngle - 360f) < 1e-3) {
            sWidth = piece.getWidth();
            sDepth = piece.getDepth();
        } else if (Math.abs(pieceAngle - 90f) < 1e-3 || Math.abs(pieceAngle - 270f) < 1e-3) {
            sWidth = piece.getDepth();
            sDepth = piece.getWidth();
        } else {
            // Turned by any other angle: size of the bounding box around its points
            float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
            for (float[] point : piece.getPoints()) {
                minX = Math.min(minX, point[0]);
                maxX = Math.max(maxX, point[0]);
                minY = Math.min(minY, point[1]);
                maxY = Math.max(maxY, point[1]);
            }
            sWidth = maxX - minX;
            sDepth = maxY - minY;
        }
        return new double[]{sWidth, sDepth};
    }

    /**
     * When 2 or more objects are selected, groups them into a single {@link HomeFurnitureGroup} so the
     * group is used as a unified surround template. If all objects share the same (x, y) position (e.g.
     * picked fresh from the catalog), arranges them side-by-side along the X-axis before grouping.
     *
     * @return unified template piece or group
     */
    public static HomePieceOfFurniture asSingleTemplate(List<HomePieceOfFurniture> pieces) {
        if (pieces == null || pieces.isEmpty()) {
            return null;
        }
        if (pieces.size() == 1) {
            return pieces.get(0);
        }
        List<HomePieceOfFurniture> clones = new ArrayList<>(pieces.size());
        boolean allUnplaced = true;
        float x0 = pieces.get(0).getX(), y0 = pieces.get(0).getY();
        for (HomePieceOfFurniture p : pieces) {
            boolean sameAsFirst = Math.abs(p.getX() - x0) < 1e-3f && Math.abs(p.getY() - y0) < 1e-3f;
            boolean atDefaultCatalogPos = Math.abs(p.getX() - p.getWidth() / 2f) < 1e-3f
                    && Math.abs(p.getY() - p.getDepth() / 2f) < 1e-3f;
            if (!sameAsFirst && !atDefaultCatalogPos) {
                allUnplaced = false;
            }
            clones.add(p.clone());
        }
        if (allUnplaced) {
            float curX = 0f;
            for (HomePieceOfFurniture c : clones) {
                c.setX(curX + c.getWidth() / 2f);
                c.setY(0f);
                c.setAngle(0f);
                curX += c.getWidth();
            }
        }
        String name = clones.get(0).getName() + " (" + clones.size() + ")";
        return new HomeFurnitureGroup(clones, name);
    }

    /** Returns the primary template piece used for generating surrounding furniture. */
    private HomePieceOfFurniture getNextSelectedPiece() {
        return primaryTemplate;
    }

    /**
     * Recursively traverses and collects all leaf furniture pieces contained within a piece or group hierarchy.
     *
     * @param piece     source piece or group
     */
    public static void collectTemplatePieces(HomePieceOfFurniture piece, Set<HomePieceOfFurniture> targetSet) {
        targetSet.add(piece);
        if (piece instanceof HomeFurnitureGroup) {
            for (HomePieceOfFurniture child : ((HomeFurnitureGroup) piece).getFurniture()) {
                collectTemplatePieces(child, targetSet);
            }
        }
    }

    /**
     * Computes a random scaling factor sampled uniformly between {@code minPercent}% and {@code maxPercent}%.
     *
     * @param minPercent minimum percentage (e.g. 80 for 0.8x)
     * @param maxPercent maximum percentage (e.g. 120 for 1.2x)
     */
    private double getRandomScale(double minPercent, double maxPercent) {
        double min = minPercent / 100.0;
        double max = maxPercent / 100.0;
        return min + Math.random() * (max - min);
    }
}
