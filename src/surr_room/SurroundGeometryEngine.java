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

    private static final double EPSILON = 1e-4;
    private volatile boolean cancelled = false;

    public void cancel() {
        this.cancelled = true;
    }

    private void checkCancelled() {
        if (cancelled || Thread.currentThread().isInterrupted()) {
            throw new java.util.concurrent.CancellationException();
        }
    }

    // Per-face collision caches populated by prepareCollisionCaches
    private HomePieceOfFurniture[] existingPieces;
    private float[][][] existingPolys;
    private float[][] existingBounds;
    private Wall[] activeWalls;
    private float[][][] wallPolys;
    private float[][] wallBounds;
    private float[][][] nearbyFloorPolys;
    private float[][] nearbyFloorBounds;

    private final Home home;
    private final HomeController homeController;
    private final List<Room> selectedRooms;
    private final List<HomePieceOfFurniture> selectedFurniture;
    private final List<HomePieceOfFurniture> existingHomeFurniture = new ArrayList<>();
    private final List<HomePieceOfFurniture> newlyPlacedFurniture = new ArrayList<>();
    private final List<HomePieceOfFurniture> firstLevelPlacedFurniture = new ArrayList<>();

    private final Map<String, List<HomePieceOfFurniture>> sidePlacedGroups = new LinkedHashMap<>();
    private final Map<HomePieceOfFurniture, String> basePieceToSideKey = new LinkedHashMap<>();
    private final Map<HomePieceOfFurniture, Level> placedLevels = new HashMap<>();
    /** Clean un-randomized copy of each first-level piece so stacked levels can draw independently. */
    private final Map<HomePieceOfFurniture, HomePieceOfFurniture> cleanBasePieces = new IdentityHashMap<>();
    private final Set<HomePieceOfFurniture> cornerBasePieces = Collections.newSetFromMap(new IdentityHashMap<HomePieceOfFurniture, Boolean>());
    private final Map<HomePieceOfFurniture, Room> pieceRooms = new IdentityHashMap<>();
    private final Map<HomePieceOfFurniture, Integer> pieceSideIndices = new IdentityHashMap<>();
    private final Map<HomePieceOfFurniture, Integer> pieceVertexIndices = new IdentityHashMap<>();
    private final Map<HomePieceOfFurniture, Integer> pieceRoomSideCounts = new IdentityHashMap<>();

    private int currentSideIndex = -1;
    private int currentVertexIndex = -1;
    private int currentRoomSideCount = 0;
    private Random randomPropsRng = new Random();
    private String currentSideGroupKey = null;
    private HomePieceOfFurniture primaryTemplate;
    private Room currentProcessingRoom;
    private SurroundConfig activeConfig;
    private List<HomePieceOfFurniture> currentObstacles = Collections.emptyList();
    private double maxSurroundingWidth;
    private double maxSurroundingDepth;

    /**
     * Curve layout normalization: from layoutFrom, straight pre-extension (layoutPre, 1:1), curve from
     * layoutCurveStart to layoutCurveEnd laid out on its narrowest line (layoutCurveLayout, stretched back
     * by layoutStretch), then straight post-extension (1:1).
     */
    private double layoutFrom, layoutStretch = 1, layoutPre, layoutCurveStart, layoutCurveEnd, layoutCurveLayout;
    private double currentSidePlacedMin = Double.MAX_VALUE;
    private double currentSidePlacedMax = -Double.MAX_VALUE;
    private double currentStretchStartTol = 0.0, currentStretchEndTol = 0.0;
    private double currentSideOffset = 0;
    private double[] sideOffsets;
    /** Ratio of base curve length to offset curve length for parallel offset curves. */
    private double curveWidthFactor = 1;

    /**
     * True during the dry-run measurement pre-pass used when uniform size (types 3-4) or uniform gaps
     * (types 1-2) is enabled: collects stretches without placing, solves a global scale/gap, then places.
     */
    private boolean measuringUniform;
    private final List<UniformStretch> uniformStretches = new ArrayList<>();
    private int uniformIndex;
    private double uniformScale3 = 1, uniformScale4 = 1, cornerPieceScale = 1;
    /** [side][0=start, 1=end]: true when an abutting corner piece already reserves its own margin. */
    private boolean[][] cornerOwnMargin = new boolean[0][0];

    public SurroundGeometryEngine(Home home, HomeController homeController,
            List<Room> selectedRooms, List<HomePieceOfFurniture> selectedFurniture) {
        this.home = home;
        this.homeController = homeController;
        this.selectedRooms = selectedRooms;
        this.selectedFurniture = selectedFurniture;
    }

    public void execute(SurroundConfig config) {
        computeInMemory(config);
        commitToHome(config);
    }

    /** Computes all levels in memory without modifying Home (safe for background threads). */
    public void computeInMemory(SurroundConfig config) {
        runFirstLevel(config, new Random().nextLong());
        applyVerticalStacking(config);
    }

    public void commitToHome(SurroundConfig config) {
        commitPlacedFurniture(config);
    }

    /** Computes the first level in memory with a fixed seed for deterministic 2D previews. */
    public List<HomePieceOfFurniture> previewFirstLevel(SurroundConfig config) {
        if (selectedFurniture == null || selectedFurniture.isEmpty() || selectedRooms == null || selectedRooms.isEmpty()) {
            return Collections.emptyList();
        }
        runFirstLevel(config, 12345L);
        return new ArrayList<>(firstLevelPlacedFurniture);
    }

    private void runFirstLevel(SurroundConfig config, long seed) {
        this.activeConfig = config;
        config.skippedDueToNoFloorBelow = false;
        this.primaryTemplate = asSingleTemplate(selectedFurniture);
        double[] dims = getPieceSurroundingDimensions(this.primaryTemplate);
        maxSurroundingWidth = dims[0];
        maxSurroundingDepth = dims[1];

        Set<HomePieceOfFurniture> templateSet = new HashSet<>();
        for (HomePieceOfFurniture selPiece : selectedFurniture) {
            collectTemplatePieces(selPiece, templateSet);
        }
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

        if (usesUniform(config)) {
            prepareUniform(config);
            clearPlacements();
            config.skippedDueToNoFloorBelow = false;
            this.randomPropsRng = new Random(seed);
        }
        uniformIndex = 0;
        surroundFirstLevel(config);
        applyGradualToFirstLevel(config);
    }

    public boolean isCornerPiece(HomePieceOfFurniture piece) {
        return cornerBasePieces.contains(piece);
    }

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

    private static void resetUniformReport(SurroundConfig config) {
        config.sizeAdjustedStretches = 0;
        config.sizeSpread = 0;
        config.sizeCenteredStretches = 0;
        config.sizeUnfittedStretches = 0;
        config.gapAdjustedStretches = 0;
        config.gapSpread = 0;
    }

    private void surroundFirstLevel(SurroundConfig config) {
        int roomIdx = 0;
        for (Room face : selectedRooms) {
            checkCancelled();
            if (config.excludedRooms != null && config.excludedRooms.contains(face)) {
                continue;
            }
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

    // ---- Uniform size (types 3-4) and uniform spaces (types 1-2) ----

    /** Linear perimeter stretch collected during the uniform pre-pass and solved before placement. */
    private static final class UniformStretch {
        final int type;
        final double length;
        final boolean wholeSide;
        final List<HomePieceOfFurniture> sequence;
        /** Cumulative unscaled widths: prefix[n] = total width of the first n pieces. */
        final double[] prefix;
        boolean[] dims;
        double minGap, scale = 1, joint, fixedJoint, startGap, startShift;
        int count;

        UniformStretch(int type, double length, boolean wholeSide, List<HomePieceOfFurniture> sequence, double[] prefix) {
            this.type = type;
            this.length = length;
            this.wholeSide = wholeSide;
            this.sequence = sequence;
            this.prefix = prefix;
        }

        boolean sameSize() {
            return type == 3 || type == 4;
        }

        double occupiedWidth(int n) {
            return prefix[n] + (type == 2 && n > 1 ? (n - 1) * fixedJoint : 0.0);
        }

        int gapCount(int n) {
            return type == 2 ? 2 : n - 1;
        }

        double gap(int n) {
            int gaps = gapCount(n);
            double gap = gaps > 0 ? (length - occupiedWidth(n)) / gaps : Double.NaN;
            return gap >= minGap - 1e-6 ? gap : Double.NaN;
        }
    }

    private boolean usesUniform(int type) {
        return ((type == 3 || type == 4) && activeConfig.uniformSize)
                || ((type == 1 || type == 2) && activeConfig.uniformGaps);
    }

    private boolean usesUniform(SurroundConfig config) {
        return usesUniform(config.surroundType);
    }

    private static boolean usesCornerPieces(SurroundConfig config) {
        return config.enableCornerHandling && config.placeOrthogonalCornerPiece;
    }

    /** {minScale, maxScale}: type 3 from 1 to 1 + max change, type 4 from 1 - max change (at least 0.05) to 1. */
    private static double[] scaleLimits(SurroundConfig config, int type) {
        double change = Math.max(0, config.uniformMaxSizeChange) / 100.0;
        return type == 3 ? new double[]{1, 1 + change} : new double[]{Math.max(0.05, 1 - change), 1};
    }

    private double uniformScale(int type) {
        return type == 3 ? uniformScale3 : uniformScale4;
    }

    /**
     * Iterative measurement pre-pass: since corner margins and piece depths depend on the solved scale,
     * repeats measure -> solve -> update depth/corner scale until convergence (up to 8 passes).
     */
    private void prepareUniform(SurroundConfig config) {
        double baseDepth = maxSurroundingDepth;
        double tolerance = Math.max(0, config.uniformMaxSizeDifference) / 100.0;
        double maxScale = 1 + Math.max(0, config.uniformMaxSizeChange) / 100.0;
        boolean scaledCornerPiece = config.enableCornerHandling && usesCornerPieces(config)
                && config.customCornerPiece == null
                && !config.cornerKeepSize && config.uniformSize
                && (config.surroundType == 3 || config.surroundType == 4);
        double depthFactor = 1;
        cornerPieceScale = 1;
        try {
            for (int iteration = 0; ; iteration++) {
                maxSurroundingDepth = baseDepth * Math.max(1, depthFactor);
                uniformStretches.clear();
                measuringUniform = true;
                try {
                    surroundFirstLevel(config);
                } finally {
                    measuringUniform = false;
                }
                solveUniform(config);

                double needed = 1;
                for (UniformStretch stretch : uniformStretches) {
                    if (stretch.sameSize() && stretch.dims[1]) {
                        needed = Math.max(needed, stretch.count > 0 ? stretch.scale : uniformScale(stretch.type));
                    }
                }
                double cornerScale = scaledCornerPiece ? uniformScale(config.surroundType) : 1;
                boolean depthCovered = needed <= Math.max(1, depthFactor) + 1e-9;
                boolean cornerMatches = Math.abs(cornerPieceScale - cornerScale) < 1e-4;
                if ((depthCovered && cornerMatches) || iteration >= 8) {
                    break;
                }
                if (!depthCovered) {
                    depthFactor = iteration < 6 ? needed * (1 + tolerance) : maxScale * (1 + tolerance);
                }
                cornerPieceScale = cornerScale;
            }
        } finally {
            maxSurroundingDepth = baseDepth * Math.max(1, depthFactor);
        }
    }

    private void layOutUniform(SidePath side, double from, double wallLength, boolean wholeSide, int type) {
        double maxExtraTol = currentStretchStartTol + currentStretchEndTol;
        double minScale = (type == 3 || type == 4) ? scaleLimits(activeConfig, type)[0] : 1.0;
        double minSingleW = getPieceSurroundingDimensions(primaryTemplate)[0] * minScale;
        double shiftStart = 0.0;

        if (maxExtraTol > EPSILON && wallLength < minSingleW && wallLength + maxExtraTol >= minSingleW - EPSILON) {
            double needed = minSingleW - wallLength;
            shiftStart = (currentStretchStartTol + currentStretchEndTol) > EPSILON
                    ? needed * (currentStretchStartTol / (currentStretchStartTol + currentStretchEndTol)) : 0.0;
            wallLength += needed;
        }

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

        if (uniformIndex >= uniformStretches.size()) {
            return;
        }
        UniformStretch stretch = uniformStretches.get(uniformIndex++);
        from -= stretch.startShift * layoutStretch;
        wallLength = stretch.length;
        boolean[] dims = dimensionFlags();

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

        double cursor = stretch.startGap;
        for (int i = 0; i < stretch.count; i++) {
            HomePieceOfFurniture piece = stretch.sequence.get(i);
            double[] d = getPieceSurroundingDimensions(piece);
            if (stretch.sameSize()) {
                cursor += placeResized(side, from + cursor, piece, d, stretch.scale, dims);
            } else {
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
     * widths), sharing a common scale s within uniformMaxSizeDifference. When they can't, UNIFORM_ADJUST takes
     * the narrowest scale window and absorbs residuals in joints up to uniformMaxJoint; UNIFORM_CENTER /
     * UNIFORM_EMPTY keep s and center or skip unfitting stretches.
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
            if (n > 0 && deviation <= band + 1e-9) {
                stretch.count = n;
                stretch.scale = stretch.length / stretch.prefix[n];
                continue;
            }
            // Out of band: try absorbing the residual in small joints up to maxJoint
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
            if (adjust && n > 0) {
                stretch.count = n;
                stretch.scale = stretch.length / stretch.prefix[n];
                config.sizeAdjustedStretches++;
                continue;
            }
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
            config.sizeUnfittedStretches++;
        }

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
        values.sort((x, y) -> Double.compare(x[0], y[0]));
        int[] inWindow = new int[participants];
        int covered = 0, left = 0;
        double bestWidth = Double.MAX_VALUE, bestMiddle = 0;
        double bestFitting = Double.NaN;

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

    /** Picks the scale maximizing total stretch length covered within the tolerance band (ties closest to 1). */
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

    private static int closestCount(UniformStretch stretch, double scale) {
        int lo = 1, hi = stretch.prefix.length - 1;
        if (hi < 1) {
            return 0;
        }
        while (lo < hi) {
            int mid = (lo + hi + 1) / 2;
            if (scale * stretch.prefix[mid] <= stretch.length) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return (lo + 1 < stretch.prefix.length
                && Math.abs(scaleDeviation(stretch, scale, lo + 1)) < Math.abs(scaleDeviation(stretch, scale, lo)))
                ? lo + 1 : lo;
    }

    private static double scaleDeviation(UniformStretch stretch, double scale, int n) {
        return n < 1 ? Double.MAX_VALUE : Math.log(stretch.length / (scale * stretch.prefix[n]));
    }

    /** Solves uniform gap spacing (types 1-2) via sliding window over candidate gaps within uniformMaxGapDifference. */
    private void solveSameGaps(SurroundConfig config, List<UniformStretch> group) {
        double tolerance = Math.max(0, config.uniformMaxGapDifference);
        double maxWidth = 0;
        for (HomePieceOfFurniture piece : selectedFurniture) {
            maxWidth = Math.max(maxWidth, getPieceSurroundingDimensions(piece)[0]);
        }
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
        List<double[]> values = new ArrayList<>();   // {gap, participantIndex}
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
                // Single piece with no variable spaces to share: center it
                stretch.count = fit[j];
                stretch.startGap = Math.max(0, (stretch.length - stretch.prefix[fit[j]]) / 2);
            }
        }
        if (participants.isEmpty()) {
            return;
        }
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
                    bestFitting = (right[0] + low) / 2;   // narrowest viable gaps
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

    private void placeUniformSingle(SidePath side, double s, boolean[] dims, int type) {
        HomePieceOfFurniture piece = selectedFurniture.get(0);
        double[] d = getPieceSurroundingDimensions(piece);
        double scale = uniformScale(type);
        double across = dims[1] ? scale : 1;
        float[] pieceDims = pieceDimensionsFor(piece, scale, across);
        place(side, s, d[1] * across, piece, pieceDims[0], pieceDims[1],
                (float) (piece.getHeight() * (dims[2] ? scale : 1)), true, true);
    }

    /** Pastes placed pieces on their face's level (switching and restoring selected level), grouped per groupingMode. */
    private void commitPlacedFurniture(SurroundConfig config) {
        if (newlyPlacedFurniture.isEmpty()) {
            return;
        }
        Map<Level, List<HomePieceOfFurniture>> toPaste = new LinkedHashMap<>();
        String baseName = primaryTemplate.getName();

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

    // ---- Sides & traversal order ----

    List<SidePath> buildSides(Room face) {
        List<SidePath> sides = buildSidePaths(face);
        for (SidePath side : sides) {
            // Curve ends follow the chord of their end objects on the offset curve
            double offset = activeConfig != null ? activeConfig.distanceOffset : 0;
            double backLength = side.isCurved() && offset != 0 ? side.frontLength(0, side.length, offset, 0) : side.length;
            side.endSpan = maxSurroundingWidth * (backLength > 1e-6 ? side.length / backLength : 1);
        }
        return sides;
    }

    private List<SidePath> buildSidePaths(Room face) {
        float[][] points = face.getPoints();
        List<int[]> logical = SideModel.logicalSides(points, activeConfig != null && activeConfig.mergeCollinearSides);
        boolean reversed = isTraversalReversed(face, interiorFor(face));
        List<SidePath> sides = new ArrayList<>();
        if (!reversed) {
            for (int i = 0; i < logical.size(); i++) {
                sides.add(new SidePath(SideModel.polyline(points, logical.get(i)), i, false));
            }
        } else {
            for (int i = logical.size() - 1; i >= 0; i--) {
                float[][] poly = SideModel.polyline(points, logical.get(i));
                float[][] backwards = new float[poly.length][];
                for (int k = 0; k < poly.length; k++) {
                    backwards[k] = poly[poly.length - 1 - k];
                }
                sides.add(new SidePath(backwards, i, true));
            }
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
            // Inside, start at the exterior start vertex so walking backwards (shown order) starts at the same place
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

    private static int exteriorStartVertex(Room room, float[][] points, List<int[]> logical) {
        if (!isShownReversed(room)) {
            return logical.get(0)[0];
        }
        int[] first = logical.get(reversedFirstSide(points, logical));
        return (first[0] + first[1]) % points.length;
    }

    private boolean interiorFor(Room room) {
        return activeConfig.interior && !activeConfig.alwaysExterior.contains(room);
    }

    /** Whether room points are walked backwards so the normal (uy, -ux) points toward the chosen side. */
    static boolean isTraversalReversed(Room room, boolean interior) {
        return room.isClockwise() == interior;
    }

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

    /** Default Type 7 start as {logicalSideIndex, nativeDistance}: matches the exterior start vertex. */
    static double[] defaultType7Start(Room room, boolean mergeCollinear) {
        float[][] points = room.getPoints();
        List<int[]> logical = SideModel.logicalSides(points, mergeCollinear);
        if (logical.isEmpty() || !isShownReversed(room)) {
            return new double[]{0, 0};
        }
        int first = reversedFirstSide(points, logical);
        return new double[]{first, SideModel.length(SideModel.polyline(points, logical.get(first)))};
    }

    /** Direction shown to the user: always the exterior walk, so inside and outside follow the same orientation. */
    static boolean isShownReversed(Room room) {
        return isTraversalReversed(room, false);
    }

    /** Inside, traversal runs opposite to the shown direction, so reverseDirection is inverted. */
    private boolean isReverseDirection(Room room) {
        return activeConfig.reverseDirection != interiorFor(room);
    }

    private boolean isSideExcluded(Room face, SidePath side, int sideCount) {
        if (face == null) {
            return false;
        }
        SurroundConfig.SideEnds ends = activeConfig.sideEnds.get(face);
        return ends != null && ends.skipped.length == sideCount
                && (ends.skipped[side.index] || (side.index < ends.forbidden.length && ends.forbidden[side.index]));
    }

    // ---- Free stretches & obstacles ----

    /** Fit tolerance in cm (disabled for non-uniform Type 4 since pieces shrink to fit). */
    static double fitTolerance(SurroundConfig config, boolean gap) {
        boolean on = gap ? (config.splitAtObstacles || config.respectWindows) && config.enableGapFitTolerance
                : config.avoidCollisions && config.enableCollisionFitTolerance;
        return on && (config.surroundType != 4 || config.uniformSize)
                ? Math.max(0.0, gap ? config.gapFitTolerance : config.collisionFitTolerance) : 0.0;
    }

    private List<HomePieceOfFurniture> obstaclesFor(Room face) {
        if (!activeConfig.splitAtObstacles && !activeConfig.respectWindows && !activeConfig.avoidCollisions) {
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

    private static float[] heightRange(List<HomePieceOfFurniture> objects) {
        float bottom = Float.MAX_VALUE, top = -Float.MAX_VALUE;
        for (HomePieceOfFurniture piece : objects) {
            bottom = Math.min(bottom, piece.getElevation());
            top = Math.max(top, piece.getElevation() + piece.getHeight());
        }
        return bottom > top ? new float[]{0f, 250f} : new float[]{bottom, top};
    }

    private static boolean interrupts(SurroundConfig config, HomePieceOfFurniture piece, Level level, float[] heights) {
        boolean isGap = piece.isDoorOrWindow();
        if (isGap ? (isWindow(piece) ? !config.respectWindows : !config.splitAtObstacles) : !config.avoidCollisions) {
            return false;
        }
        return (level == null || piece.getLevel() == null || piece.isAtLevel(level))
                && overlapsHeightBand(config, piece, heights, isGap, fitTolerance(config, isGap));
    }

    private static boolean overlapsHeightBand(SurroundConfig config, HomePieceOfFurniture piece,
            float[] heights, boolean isGap, double tol) {
        boolean chosenHeights = isGap && config != null && config.gapHeightRange;
        double low = chosenHeights ? config.gapMinHeight : heights[0];
        double high = chosenHeights ? config.gapMaxHeight : heights[1];
        double zTol = chosenHeights ? 0.1 : Math.min((high - low) * 0.45, tol + 0.1);
        float z0 = piece.getElevation(), z1 = z0 + piece.getHeight();
        return z1 > low + zTol && z0 < high - zTol;
    }

    private static void addBlockedIntervals(List<double[]> dst, List<double[]> raw, double sideLength, boolean reversed) {
        for (double[] b : raw) {
            double start = reversed ? sideLength - b[1] : b[0];
            double end = reversed ? sideLength - b[0] : b[1];
            if (end - start > 1.0) {
                dst.add(new double[]{start, end});
            }
        }
    }

    /**
     * Exact obstacle sub-stretches per side for SideEndsPanel display, tagged by kind:
     * 0 = doors, 1 = windows, 2 = furniture, 3 = touching walls (excluding the side's own wall), 4 = touching floors.
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
            @SuppressWarnings("unchecked")
            List<double[]>[] byKind = new List[]{
                new ArrayList<double[]>(), new ArrayList<double[]>(), new ArrayList<double[]>(),
                new ArrayList<double[]>(), new ArrayList<double[]>()
            };

            float[][] points = sides.get(i);
            if (reversed) {
                points = points.clone();
                Collections.reverse(java.util.Arrays.asList(points));
            }
            SidePath side = new SidePath(points, i, reversed);

            // Show doors, windows, and furniture regardless of toggles so the user can pick their intervals
            for (HomePieceOfFurniture piece : pieces) {
                boolean isGap = piece.isDoorOrWindow();
                double tol = isGap ? gapTol : colTol;
                if ((roomLevel != null && piece.getLevel() != null && !piece.isAtLevel(roomLevel))
                        || !overlapsHeightBand(config, piece, heights, isGap, tol)) {
                    continue;
                }
                List<double[]> targetList = byKind[isGap ? (isWindow(piece) ? 1 : 0) : 2];
                addBlockedIntervals(targetList, blockedAlong(side, piece, 0, side.length, objectDepth, offset, tol), side.length, reversed);
            }

            if (ends != null && i < ends.overlapSegments.size()) {
                addBlockedIntervals(byKind[3], ends.overlapSegments.get(i), side.length, false);
            }
            if (home != null && home.getWalls() != null) {
                for (Wall wall : home.getWalls()) {
                    if (roomLevel == null || wall.getLevel() == null || wall.isAtLevel(roomLevel)) {
                        addBlockedIntervals(byKind[3],
                                blockedPolygonAlong(side, wall.getPoints(), 0, side.length, objectDepth, offset, colTol, true),
                                side.length, reversed);
                    }
                }
            }

            if (home != null && home.getRooms() != null) {
                for (Room otherRoom : home.getRooms()) {
                    if (otherRoom != face && (sourceRooms == null || !sourceRooms.contains(otherRoom))
                            && (roomLevel == null || otherRoom.getLevel() == null || otherRoom.isAtLevel(roomLevel))) {
                        addBlockedIntervals(byKind[4],
                                blockedPolygonAlong(side, otherRoom.getPoints(), 0, side.length, objectDepth, offset, colTol, false),
                                side.length, reversed);
                    }
                }
            }

            List<double[]> sideStretches = new ArrayList<>();
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

    static boolean isWindow(HomePieceOfFurniture piece) {
        return piece.isDoorOrWindow() && piece.getElevation() > 1f;
    }

    /**
     * Stretches of [from, to] free of openings, furniture collisions, wall contacts, and excluded segments:
     * {start, end, startsAtObstacle (1/0), endsAtObstacle (1/0), startFitTol, endFitTol}.
     */
    private List<double[]> freeStretches(Room face, SidePath side, double from, double to, double stripDepth, double offset) {
        List<double[]> stretches = new ArrayList<>();
        if (to - from <= EPSILON) {
            return stretches;
        }
        double gapTol = fitTolerance(activeConfig, true);
        double colTol = fitTolerance(activeConfig, false);
        List<double[]> blocked = new ArrayList<>();   // {start, end, lateralTol}

        for (HomePieceOfFurniture obstacle : currentObstacles) {
            blocked.addAll(blockedAlong(side, obstacle, from, to, stripDepth, offset, obstacle.isDoorOrWindow() ? gapTol : colTol));
        }

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
            if (b[0] - cursor > 1 || (b[0] + b[2]) - (cursor - prevEndTol) > 1) {
                stretches.add(new double[]{cursor, b[0], startsAtObstacle ? 1 : 0, 1, prevEndTol, b[2]});
            }
            if (b[1] > cursor) {
                cursor = b[1];
                prevEndTol = b[2];
            }
            startsAtObstacle = true;
        }
        if (to - cursor > 1 || (!startsAtObstacle && to > cursor) || (to - (cursor - prevEndTol) > 1 && prevEndTol > 0)) {
            stretches.add(new double[]{cursor, to, startsAtObstacle ? 1 : 0, 0, prevEndTol, 0.0});
        }
        return stretches;
    }

    private static float[][] segmentBand(float[] origin, double[] u, double nx, double ny,
            double lo, double hi, double near, double far) {
        double p0x = origin[0] + u[0] * lo, p0y = origin[1] + u[1] * lo;
        double p1x = origin[0] + u[0] * hi, p1y = origin[1] + u[1] * hi;
        return new float[][]{
            {(float) (p0x + nx * near), (float) (p0y + ny * near)},
            {(float) (p1x + nx * near), (float) (p1y + ny * near)},
            {(float) (p1x + nx * far), (float) (p1y + ny * far)},
            {(float) (p0x + nx * far), (float) (p0y + ny * far)}};
    }

    /**
     * Where along the side an obstacle crosses the placement strip (SAT + local clip), as
     * {start, end, lateralTolerance}; depth tolerance shrinks the front of the strip by up to tol (keeping >=20%).
     */
    private static List<double[]> blockedAlong(SidePath side, HomePieceOfFurniture obstacle, double from, double to,
            double stripDepth, double offset, double tol) {
        List<double[]> blocked = new ArrayList<>();
        boolean isGap = obstacle.isDoorOrWindow();
        float[][] polygon = obstacle.getPoints();
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
            if (!polygonsOverlapSAT(segmentBand(side.points[i], u, nx, ny, lo, hi, -back, front), polygon)) {
                continue;
            }
            // Clip obstacle polygon in local (along, across) coordinates so oblique/long items only block the crossed span
            List<double[]> localPoly = new ArrayList<>(polygon.length);
            for (float[] point : polygon) {
                double dx = point[0] - side.points[i][0], dy = point[1] - side.points[i][1];
                localPoly.add(new double[]{dx * u[0] + dy * u[1], dx * nx + dy * ny});
            }
            localPoly = clip(clip(clip(clip(localPoly, 1, -back, 1), 1, front, -1), 0, lo, 1), 0, hi, -1);
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
                // Cap lateral tolerance at 45% of obstacle width so narrow obstacles are never ignored completely
                blocked.add(new double[]{side.cumulative[i] + min, side.cumulative[i] + max, Math.min(tol, (max - min) * 0.45)});
            }
        }
        return blocked;
    }

    /** Where a 2D polygon (wall or adjacent floor) intersects the placement strip in front of a side. */
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
            if (ignoreCoincidentWall && isCoincidentParallelWall(polygon, side.points[i], u, nx, ny, segmentLength)) {
                continue;
            }
            double lo = i == 0 ? Math.min(0, from) : 0;
            double hi = i == side.segmentCount() - 1 ? Math.max(segmentLength, to - side.cumulative[i]) : segmentLength;
            java.awt.geom.Path2D.Float bandPath = SurroundTarget.toPath(segmentBand(side.points[i], u, nx, ny, lo, hi, near, far));
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

    /** True if wallPoly is the structural wall running parallel along and hugging this segment itself. */
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
            return false;
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
        return minAcross <= 1.5 && maxAcross >= -1.5 && overlapAlong > Math.min(25.0, segmentLength * 0.5);
    }

    private static void addBlockedIntervals(SidePath side, List<double[]> intervals, List<double[]> blocked, double tol) {
        for (double[] interval : intervals) {
            double s0 = side.reversed ? (side.length - interval[1]) : interval[0];
            double s1 = side.reversed ? (side.length - interval[0]) : interval[1];
            if (s1 > s0 + EPSILON) {
                blocked.add(new double[]{s0, s1, Math.min(tol, (s1 - s0) * 0.45)});
            }
        }
    }

    // ---- Types 1 to 6 ----

    private void surroundUnified(Room face, List<SidePath> sides, int roomIdx) {
        int count = sides.size();
        // Each side's objects follow a line moved by distanceOffset; side ends meet where adjacent offset lines cross
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

    private void surroundUnifiedSides(Room face, List<SidePath> sides, int roomIdx) {
        int count = sides.size();
        currentRoomSideCount = count;
        double[][] cornerMargins = new double[count][2];
        int[][] extraObjects = new int[count][2]; // 0 = none, 1 = END_EXTRA_OBJECT, 2 = END_ADJACENT_TO_OBJECTS
        List<double[]> extras = new ArrayList<>(); // {sideIdx, pos, forward (1/0), endType (0=start, 1=end), anchorIdx}
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
            double from = range[0], to = range[1];
            currentSidePlacedMin = Double.MAX_VALUE;
            currentSidePlacedMax = -Double.MAX_VALUE;

            if (to - from <= EPSILON) {
                if (activeConfig.shortSidesOneObject && !measuringUniform) {
                    placeShortSideObjectWithHierarchy(face, side, from - pieceMargin, to + pieceMargin, effectiveType);
                }
            } else {
                List<double[]> stretches = freeStretches(face, side, from, to, maxSurroundingDepth, currentSideOffset);
                for (double[] stretch : stretches) {
                    currentStretchStartTol = stretch.length > 4 ? stretch[4] : 0.0;
                    currentStretchEndTol = stretch.length > 5 ? stretch[5] : 0.0;
                    fillStretch(side, stretch[0], stretch[1], stretch[2] == 0 && stretch[3] == 0, effectiveType);
                }
                currentStretchStartTol = 0.0;
                currentStretchEndTol = 0.0;
            }

            if (!measuringUniform) {
                double stepGap = 2.0 * pieceMargin;
                // Deferred until all sides are placed so extra end objects can be collision-checked against neighbor sides
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
        placeExtraObjects(face, sides, roomIdx, extras);
        currentSideIndex = -1;
        currentVertexIndex = -1;
        currentSideOffset = 0;
    }

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
                        margins[t][end] = -dist;   // + prolongs past vertex, - shortens
                        break;
                    case SurroundConfig.END_FIT: {
                        int v = (end == 0) ? t : (t + 1) % count;
                        int neighbourIdx = (end == 0) ? (t - 1 + count) % count : (t + 1) % count;
                        double[] geom = vertexGeometry(sides, v);
                        if (count < 2 || isSideExcluded(face, sides.get(neighbourIdx), count)
                                || geom == null || Math.abs(geom[5]) <= 1e-4) {
                            break;
                        }
                        int partnerMode = (end == 0) ? endMode : startMode;
                        if (geom[5] < 0) {
                            // Convex corner: extend to bisector (capped at stripDepth for acute star points);
                            // when both ends fit, only the incoming side extends to avoid overlap
                            boolean partnerCovers = partnerMode == SurroundConfig.END_FIT && end == 0;
                            margins[t][end] = partnerCovers ? 0.0 : -stripDepth * Math.min(geom[4], 1.0);
                            break;
                        }
                        double cosGamma = geom[2], sinGamma = geom[3], tanGamma = geom[4];
                        double bisectorClearance = stripDepth * tanGamma;
                        if (partnerMode == SurroundConfig.END_FIT) {
                            margins[t][end] = bisectorClearance;
                        } else {
                            double partnerMargin = partnerMode == SurroundConfig.END_DISTANCE ? -((end == 0) ? endDist : startDist) : 0.0;
                            // Interior angle theta = pi - 2*gamma -> sin(theta) = 2*sin*cos, cos(theta) = 2*sin^2 - 1
                            double sinTheta = 2.0 * sinGamma * cosGamma;
                            double cosTheta = 2.0 * sinGamma * sinGamma - 1.0;
                            margins[t][end] = Math.max(bisectorClearance, partnerMargin * cosTheta + stripDepth * sinTheta);
                        }
                        break;
                    }
                    default:
                        break;
                }
            }
        }
    }

    /** Places end extras first (so the ending side wins contested corners), then splices them back after their side. */
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

    private void placeExtraObject(Room face, List<SidePath> sides, int t, double at, boolean forward, int vertex) {
        SidePath side = sides.get(t);
        HomePieceOfFurniture piece = primaryTemplate;
        double[] d = getPieceSurroundingDimensions(piece);
        double center = at + (forward ? 1 : -1) * d[0] / 2.0;
        double objStart = center - d[0] / 2.0;
        double objEnd = center + d[0] / 2.0;
        boolean ignoreCollisions = true;
        if (activeConfig.extraObjectRespectObstacles) {
            List<double[]> free = freeStretches(face, side, Math.min(0.0, objStart), Math.max(side.length, objEnd),
                    maxSurroundingDepth, currentSideOffset);
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
            ignoreCollisions = !checksCollisions();
            if (activeConfig.extraObjectRespectCorners && sides.size() > 1
                    && getsIntoFigure(sides, vertex, straightFootprint(side, center, d[1], d[0] / 2.0, d[1] / 2.0))) {
                return;
            }
        }
        place(side, center, d[1], piece, -1, -1, -1, true, ignoreCollisions);
    }

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

    private double neighbourOffset(Room face, List<SidePath> sides, int i) {
        return isSideExcluded(face, sides.get(i), sides.size()) ? 0 : activeConfig.distanceOffset;
    }

    private double[] offsetCorner(List<SidePath> sides, int k) {
        int count = sides.size();
        return offsetCorner(sides, k, sideOffsets != null ? sideOffsets[(k - 1 + count) % count] : 0,
                sideOffsets != null ? sideOffsets[k] : 0);
    }

    /** Intersection of the incoming (offset d1) and outgoing (offset d2) lines at vertex k: {x, y, pastIn, alongOut}. */
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
        double t = Math.abs(det) < 1e-6 ? 0 : (-rx * uOut[1] + uOut[0] * ry) / det;
        double sAlong = Math.abs(det) < 1e-6 ? 0 : (uIn[0] * ry - uIn[1] * rx) / det;
        return new double[]{v[0] + uIn[1] * d1 + uIn[0] * t, v[1] - uIn[0] * d1 + uIn[1] * t, t, sAlong};
    }

    private double getEffectivePieceMargin() {
        return activeConfig.enablePieceMargin ? activeConfig.pieceMargin : 0.0;
    }

    /**
     * Lays out a stretch [from, to]: on curves, measures along the narrowest line (front on concave curves)
     * and stretches positions back onto the side.
     */
    private void fillStretch(SidePath side, double from, double to, boolean wholeSide, int surroundType) {
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
     * Types 1-6 layout: 1 equal gaps, 2 centered with 2*pieceMargin joints, 3 enlarged to fill, 4 reduced to fill,
     * 5 contiguous from start/end, 6 whole pieces with resized end(s). Slight overflows use stretch end tolerances.
     */
    private void layOutStretch(SidePath side, double from, double wallLength, boolean wholeSide, int surroundType) {
        if (usesUniform(surroundType)) {
            layOutUniform(side, from, wallLength, wholeSide, surroundType);
            return;
        }
        double pieceMargin = getEffectivePieceMargin();

        List<HomePieceOfFurniture> wallPieces = new ArrayList<>();
        List<double[]> wallPieceDims = new ArrayList<>();
        double accumulatedWidth = 0.0;
        HomePieceOfFurniture overflowPiece = null;
        double maxExtraTol = (surroundType != 4) ? (currentStretchStartTol + currentStretchEndTol) : 0.0;
        while (true) {
            checkCancelled();
            HomePieceOfFurniture candidate = primaryTemplate;
            double[] dims = getPieceSurroundingDimensions(candidate);
            double candW = dims[0];
            if (candW <= EPSILON) {
                break;
            }
            // Positive margin separates by 2*pieceMargin, negative overlaps by 2*|pieceMargin|
            double stepW = (!wallPieces.isEmpty() ? Math.max(-candW * 0.9, 2.0 * pieceMargin) : 0.0) + candW;
            boolean fitsDirectly = accumulatedWidth + stepW <= wallLength + EPSILON;
            boolean fitsWithTol = !fitsDirectly && maxExtraTol > EPSILON
                    && accumulatedWidth + stepW <= wallLength + maxExtraTol + EPSILON;
            if (fitsWithTol) {
                double neededExtra = (accumulatedWidth + stepW) - wallLength;
                double totalTol = currentStretchStartTol + currentStretchEndTol;
                double usedStart = Math.min(currentStretchStartTol, totalTol > EPSILON ? neededExtra * (currentStretchStartTol / totalTol) : 0.0);
                double usedEnd = Math.min(currentStretchEndTol, neededExtra - usedStart);
                if (usedStart + usedEnd < neededExtra - 1e-6) {
                    usedStart = Math.min(currentStretchStartTol, neededExtra - usedEnd);
                }
                from -= usedStart * layoutStretch;
                wallLength += usedStart + usedEnd;
                maxExtraTol = 0.0;
            }
            if (fitsDirectly || fitsWithTol || surroundType == 4) {
                wallPieces.add(candidate);
                wallPieceDims.add(dims);
                accumulatedWidth += stepW;
                if (!fitsDirectly) {
                    break;
                }
            } else {
                overflowPiece = candidate;
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
        boolean[] dims = dimensionFlags();

        switch (surroundType) {
            case 1:
            case 2:
            case 5: {
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
                placePieceRow(side, from, cursor, gap, wallPieces, wallPieceDims);
                break;
            }
            case 3:
            case 4: {
                double scale = wallLength / Math.max(EPSILON, accumulatedWidth);
                double cursor = 0.0;
                for (int i = 0; i < m; i++) {
                    cursor += placeResized(side, from + cursor, wallPieces.get(i), wallPieceDims.get(i), scale, dims);
                }
                break;
            }
            case 6: {
                // Type 6: whole pieces + resized end(s).
                // - symmetricBothEnds: when indivisible, ensure distinct resized pieces at both ends.
                // - END_FIT on single-end mode: keep the natural-length end piece width and fill newly extended space before it.
                double remainingGap = wallLength - accumulatedWidth;
                boolean adjustStart = symmetricEnds || reverseDir;
                boolean adjustEnd = symmetricEnds || !reverseDir;
                HomePieceOfFurniture extraPiece = overflowPiece != null ? overflowPiece : wallPieces.get(0);
                double[] extraDims = getPieceSurroundingDimensions(extraPiece);

                if (symmetricEnds) {
                    double cursor = 0.0;
                    if (remainingGap > EPSILON) {
                        if (m >= 2 && remainingGap / 2.0 < wallPieceDims.get(0)[0] / 2.0) {
                            accumulatedWidth -= wallPieceDims.remove(--m)[0];
                            wallPieces.remove(m);
                            remainingGap = wallLength - accumulatedWidth;
                        }
                        double endScale = (remainingGap / 2.0) / extraDims[0];
                        cursor += placeResized(side, from + cursor, extraPiece, extraDims, endScale, dims);
                        cursor = placePieceRow(side, from, cursor, 0.0, wallPieces, wallPieceDims);
                        placeResized(side, from + cursor, extraPiece, extraDims, endScale, dims);
                    } else {
                        placePieceRow(side, from, 0.0, 0.0, wallPieces, wallPieceDims);
                    }
                    break;
                }

                double targetLastWidth = -1.0;
                if (wholeSide && activeConfig.customSideEnds && !side.isCurved()
                        && (activeConfig.globalStartEndMode == SurroundConfig.END_FIT
                                || activeConfig.globalEndEndMode == SurroundConfig.END_FIT)) {
                    double baseLen = side.length, w0 = extraDims[0];
                    if (w0 > EPSILON && baseLen > EPSILON && Math.abs(wallLength - baseLen) > EPSILON) {
                        int baseM = (int) Math.floor((baseLen + EPSILON) / w0);
                        double baseRem = baseLen - baseM * w0;
                        targetLastWidth = baseRem <= EPSILON ? w0
                                : (baseRem < w0 / 2.0 && baseM >= 1) ? w0 + baseRem : baseRem;
                        if (targetLastWidth > wallLength + EPSILON) {
                            targetLastWidth = -1.0;
                        }
                    }
                }

                if (targetLastWidth > EPSILON) {
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
                    double extra = (i == 0 && widenStart ? gapPerEnd : 0) + (i == m - 1 && widenEnd ? gapPerEnd : 0);
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

    private double placePieceRow(SidePath side, double from, double cursor, double gap,
            List<HomePieceOfFurniture> pieces, List<double[]> dims) {
        for (int i = 0; i < pieces.size(); i++) {
            double[] d = dims.get(i);
            place(side, from + cursor + d[0] / 2.0, d[1], pieces.get(i), -1, -1, -1, true, false);
            cursor += d[0] + gap;
        }
        return cursor;
    }

    private boolean[] dimensionFlags() {
        int mode = activeConfig.dimModifyMode;
        boolean depth = mode == SurroundConfig.DIM_PROPORTIONAL || (mode == SurroundConfig.DIM_SELECTED && activeConfig.dimModifyD);
        boolean height = mode == SurroundConfig.DIM_PROPORTIONAL || (mode == SurroundConfig.DIM_SELECTED && activeConfig.dimModifyH);
        return new boolean[]{true, depth, height};
    }

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

    /** Unrotated {width, depth} needed so the rotated piece matches (scaleAlong, scaleAcross) on the side. */
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

    private void placeShortSideObjectWithHierarchy(Room face, SidePath side, double rawFrom, double rawTo, int effectiveType) {
        double spanStart = Math.min(rawFrom, rawTo), spanEnd = Math.max(rawFrom, rawTo);
        List<double[]> free = freeStretches(face, side, 0, side.length, maxSurroundingDepth, currentSideOffset);
        if (free.isEmpty()) {
            return;
        }
        double[] best = free.get(0);
        for (double[] st : free) {
            if ((st[1] - st[0]) > (best[1] - best[0])) {
                best = st;
            }
        }
        double lo = Math.max(best[0], spanStart), hi = Math.min(best[1], spanEnd);
        double center = lo > hi ? 0.5 * (best[0] + best[1]) : 0.5 * (lo + hi);
        if ((effectiveType == 3 || effectiveType == 4) && usesUniform(effectiveType)) {
            placeUniformSingle(side, center, dimensionFlags(), effectiveType);
        } else {
            placeCentered(side, center);
        }
    }

    private void placeCentered(SidePath side, double s) {
        double[] dims = getPieceSurroundingDimensions(primaryTemplate);
        place(side, s, dims[1], primaryTemplate, -1, -1, -1, true, !checksCollisions());
    }

    private double currentWallNormalX = 0.0;
    private double currentWallNormalY = -1.0;

    /** Places a piece at distance s along the side, compensating curve bulge. Dimensions of -1 keep template values. */
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
        double across = currentSideOffset + depth / 2.0;
        createSurroundingFromTemplate(piece, (float) (point[0] + nx * across), (float) (point[1] + ny * across),
                (float) Math.atan2(u[1], u[0]), width, pieceDepth, height, applyTemplateBaseAngle, ignoreCollisions, false);
    }

    /** Clearance needed at acute (<90 deg) concave corners so depth doesn't cross the adjacent side. */
    private static double acuteCornerMargin(List<SidePath> sides, int k, double depth) {
        double[] geometry = vertexGeometry(sides, k);
        if (geometry == null || geometry[5] <= 1e-4 || geometry[4] <= 1e-9) {
            return 0;
        }
        double tanGamma = geometry[4];
        return Math.max(0, depth * (tanGamma * tanGamma - 1) / (2 * tanGamma));
    }

    /** Vertex k geometry: {bisectorX, bisectorY, cosGamma, sinGamma, tanGamma, turn}; turn>0 concave, <0 convex, null collinear. */
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
     * Computes side end margins [side][0=start, 1=end] and places orthogonal corner pieces.
     * In non-uniform types 3-4, side scale and corner piece scale depend on each other and relax up to 12 passes.
     */
    private double[][] computeCornerMarginsAndPlacePieces(Room face, List<SidePath> sides, int roomIdx) {
        int count = sides.size();
        double[][] sideMargins = new double[count][2];
        cornerOwnMargin = new boolean[count][2];
        if (!activeConfig.enableCornerHandling || count < 2) {
            return sideMargins;
        }
        double wallStripDepth = Math.max(0.0, maxSurroundingDepth);
        if (!activeConfig.placeOrthogonalCornerPiece) {
            for (int k = 0; k < count; k++) {
                int prevIdx = (k - 1 + count) % count;
                double[] geometry = vertexGeometry(sides, k);
                double m = geometry != null && geometry[5] > 1e-4
                        && !isSideExcluded(face, sides.get(prevIdx), count) && !isSideExcluded(face, sides.get(k), count)
                        ? wallStripDepth * geometry[4] : 0.0;
                sideMargins[prevIdx][1] = m;
                sideMargins[k][0] = m;
            }
            return sideMargins;
        }

        // Each corner piece belongs to the departing side (or previous side if departing is excluded)
        HomePieceOfFurniture[] bases = new HomePieceOfFurniture[count];
        int[] ownerSide = new int[count];
        boolean[] placeCorner = new boolean[count];
        for (int k = 0; k < count; k++) {
            int prevIdx = (k - 1 + count) % count;
            boolean previousSkipped = isSideExcluded(face, sides.get(prevIdx), count);
            boolean nextSkipped = isSideExcluded(face, sides.get(k), count);
            if (vertexGeometry(sides, k) == null) {
                continue;
            }
            bases[k] = getCornerTemplatePiece();
            ownerSide[k] = nextSkipped ? prevIdx : k;
            placeCorner[k] = !nextSkipped || !previousSkipped;
        }

        boolean sizedBySide = (activeConfig.surroundType == 3 || activeConfig.surroundType == 4)
                && !usesUniform(activeConfig) && activeConfig.customCornerPiece == null
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
            double change = 0;
            for (int t = 0; t < count; t++) {
                double scale = estimatedSideScale(face, sides, t, sideMargins);
                change = Math.max(change, Math.abs(scale - sideScale[t]));
                sideScale[t] = scale;
            }
            if (change < 1e-4) {
                break;
            }
        }

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

    /** Pose {x, y, angle} on the bisector at vertex k and the resulting sideMargins; nudges via VertexFit if blocked. */
    private double[] cornerPlacement(Room face, List<SidePath> sides, int k, HomePieceOfFurniture piece,
            double[][] sideMargins, List<double[]> placed) {
        int count = sides.size();
        int prevIdx = (k - 1 + count) % count;
        double[] geometry = vertexGeometry(sides, k);
        double bx = geometry[0], by = geometry[1], tanGamma = geometry[4], cornerTurn = geometry[5];
        double rW = piece.getWidth() / 2.0, rD = piece.getDepth() / 2.0;
        double[] corner = offsetCorner(sides, k);
        double vx = corner[0], vy = corner[1];
        double[] uIn = sides.get(prevIdx).lastDirection();
        double[] uOut = sides.get(k).firstDirection();
        double angle = Math.atan2(uIn[1] + uOut[1], uIn[0] + uOut[0]);

        // At concave corners, push along bisector by rW*tanGamma so rear corners meet both walls unless cornerTouchVertex
        double bisectorDist = cornerTurn > 1e-4 && !activeConfig.cornerTouchVertex ? rD + rW * tanGamma : rD;
        double cx = vx + bx * bisectorDist;
        double cy = vy + by * bisectorDist;

        if (placed != null) {
            double[] footprint = {cx, cy, Math.cos(angle), Math.sin(angle), rW, rD};
            if (cornerBlocked(sides, k, piece, footprint, placed)) {
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

        int separation = activeConfig.orthoSeparation;
        double[] back = {-uIn[0], -uIn[1]};
        double marginIn, marginOut;
        switch (separation) {
            case SurroundConfig.ORTHO_SEPARATE: {
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

    /** {nearest, farthest} distances along `away` from (vx, vy) of the piece portion inside strip [0, depth]. */
    static double[] stripSpan(double cx, double cy, double angle, double rW, double rD, double vx, double vy,
            double[] away, double[] normal, double depth) {
        double ca = Math.cos(angle), sa = Math.sin(angle);
        List<double[]> polygon = new ArrayList<>();
        for (double[] c : new double[][]{{-1, -1}, {1, -1}, {1, 1}, {-1, 1}}) {
            double px = cx + ca * rW * c[0] - sa * rD * c[1] - vx;
            double py = cy + sa * rW * c[0] + ca * rD * c[1] - vy;
            polygon.add(new double[]{px * away[0] + py * away[1], px * normal[0] + py * normal[1]});
        }
        polygon = clip(clip(polygon, 1, 0, 1), 1, depth, -1);
        double nearest = polygon.isEmpty() ? 0 : Double.MAX_VALUE, extent = 0;
        for (double[] point : polygon) {
            nearest = Math.min(nearest, point[0]);
            extent = Math.max(extent, point[0]);
        }
        return new double[]{nearest, extent};
    }

    /** Sutherland-Hodgman clip of a convex polygon to sign * (point[axis] - limit) >= 0. */
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
        return objects <= 0 ? 1.0 : length / (objects * width + (objects - 1) * gap);
    }

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

    private boolean cornerBlocked(List<SidePath> sides, int k, HomePieceOfFurniture piece, double[] footprint,
            List<double[]> placed) {
        double clearance = activeConfig.orthoSeparation == SurroundConfig.ORTHO_SEPARATE ? Math.max(0, activeConfig.cornerPieceMargin) : 0;
        for (double[] other : placed) {
            if (footprintGap(footprint, other) < clearance - 0.05) {
                return true;
            }
        }
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
                double alongSide = Math.sin(obstacle.getAngle()) * u[0] - Math.cos(obstacle.getAngle()) * u[1];
                if (obstacle.isDoorOrWindow() && Math.abs(alongSide) < 0.1 && to - from > 0.5) {
                    for (double[] b : blockedAlong(side, obstacle, from, to, depth, sideOffsets != null ? sideOffsets[t] : 0,
                            fitTolerance(activeConfig, true))) {
                        if (Math.min(b[1], to) - Math.max(b[0], from) > 0.5) {
                            return true;
                        }
                    }
                }
            }
        }
        if (!checksCollisions()) {
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

    /** Projected distances from (vx, vy) along uIn to the 4 piece corners, sorted descending. */
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

    private HomePieceOfFurniture getCornerTemplatePiece() {
        if (activeConfig.customCornerPiece != null) {
            HomePieceOfFurniture cornerPiece = activeConfig.customCornerPiece.clone();
            cornerPiece.setElevation(primaryTemplate.getElevation());
            return cornerPiece;
        }
        return scaledPiece(primaryTemplate, cornerPieceScale);
    }

    // ---- Type 7 ----

    /**
     * Type 7: joins free sides into one continuous 1D path, spreads a fixed count or fixed spacing across
     * vertices, optionally rotates to a custom start point, and fits vertex-overhanging objects with VertexFit.
     */
    private void surroundType7(Room face, List<SidePath> sides, int roomIdx) {
        SurroundConfig config = activeConfig;
        int count = sides.size();
        int objectCountSetting = config.type7ObjectCount;
        boolean reverse = isReverseDirection(face);

        // {sideIdx, from, to, isBoundaryStart, isBoundaryEnd, userOffset, sTol, eTol}
        List<double[]> stretches = new ArrayList<>();
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

            // Apply acute corner margin only at open/interrupted chain ends, not intermediate contiguous vertices
            double from = prevSkipped ? acuteCornerMargin(sides, t, frontDepth) : 0.0;
            double to = side.length - (nextSkipped ? acuteCornerMargin(sides, kNext, frontDepth) : 0.0);

            if (userOffset != 0) {
                double previousOffset = Math.min(userOffset, neighbourOffset(face, sides, kPrev));
                double nextOffset = Math.min(userOffset, neighbourOffset(face, sides, kNext));
                from += offsetCorner(sides, t, previousOffset, userOffset)[3];
                to += offsetCorner(sides, kNext, userOffset, nextOffset)[2];
            }
            if (to - from <= EPSILON) {
                continue;
            }

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

        // Rotate the 1D path when a custom Type 7 start point is configured for this face
        double[] customStart = config.type7StartPoints != null ? config.type7StartPoints.get(face) : null;
        if (customStart != null && customStart.length >= 2) {
            int targetLogicalSide = (int) Math.round(customStart[0]);
            double targetNativeDist = customStart[1];
            int targetT = -1;
            for (int t = 0; t < count; t++) {
                if (sides.get(t).index == targetLogicalSide) {
                    targetT = t;
                    break;
                }
            }
            if (targetT >= 0) {
                SidePath targetSide = sides.get(targetT);
                double targetS = Math.max(0.0, Math.min(targetSide.length,
                        targetSide.reversed ? (targetSide.length - targetNativeDist) : targetNativeDist));
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
                        splitIdx = (splitIdx + 1) % stretches.size();
                        st = stretches.get(splitIdx);
                        splitCoord = st[1];
                    }
                    if (splitCoord > st[1] + EPSILON && splitCoord < st[2] - EPSILON) {
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

        List<Type7Object> planned = new ArrayList<>();
        for (int i = 0; i < objectCount; i++) {
            checkCancelled();
            HomePieceOfFurniture activePiece = primaryTemplate;
            double[] activeDims = getPieceSurroundingDimensions(activePiece);
            double activeSurrWidth = activeDims[0], activeSurrDepth = activeDims[1];

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

            double position = i * spacing + activeSurrWidth / 2.0;
            if (reverse) {
                position = total - position;
            }
            position = Math.max(0.0, Math.min(total, position));

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
            double sTol = stretch.length > 6 ? stretch[6] : 0.0;
            double eTol = stretch.length > 7 ? stretch[7] : 0.0;
            double minS = stretch[3] == 1 ? (from - sTol) + halfWidth : from;
            double maxS = stretch[4] == 1 ? (to + eTol) - halfWidth : to;
            if (maxS >= minS - 1e-6) {
                s = Math.max(minS, Math.min(maxS, s));
            } else if (config.shortSidesOneObject) {
                s = (from + to) / 2;
            }
            double effDepth = activeSurrDepth * (finalDepth / activePiece.getDepth());

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

        if (config.enableCornerHandling) {
            findType7VertexObjects(face, sides, planned);
            for (Type7Object object : planned) {
                if (object.vertex >= 0) {
                    fitType7VertexObject(sides, object, planned);
                }
            }
        }

        for (Type7Object object : planned) {
            currentRoomSideCount = count;
            currentSideIndex = sides.indexOf(object.side);
            currentVertexIndex = -1;
            currentSideGroupKey = "R" + roomIdx + "_S" + (object.side.index + 1);
            sidePlacedGroups.putIfAbsent(currentSideGroupKey, new ArrayList<HomePieceOfFurniture>());
            if (object.vertex >= 0 && object.atVertex) {
                currentSideIndex = -1;
                currentVertexIndex = object.vertex;
                double[] f = object.footprint;
                createSurroundingFromTemplate(object.piece, (float) f[0], (float) f[1], (float) Math.atan2(f[3], f[2]),
                        object.width, object.depth, object.height, true, true, true);
                currentVertexIndex = -1;
            } else {
                place(object.side, object.s, object.depthOffset, object.piece, object.width, object.depth, object.height,
                        true, object.shortStretch);
            }
        }
    }

    private static final double VERTEX_OVERHANG = 0.1;
    /** Max turn (deg) from bisector normal; beyond 45 deg a side face would face the corner. */
    private static final double MAX_VERTEX_TURN = 44;
    /** Minimum margin (cm) by which the piece's rear face must cover the corner vertex. */
    private static final double VERTEX_COVER = 1.0;

    private static final class Type7Object {
        HomePieceOfFurniture piece;
        float width, depth, height;
        SidePath side;
        double s, depthOffset, userOffset;
        boolean shortStretch, settled = true, atVertex;
        /** [cx, cy, cos(angle), sin(angle), halfWidth, halfDepth] */
        double[] footprint;
        int vertex = -1;
    }

    private double[] straightFootprint(SidePath side, double s, double depthOffset, double halfWidth, double halfDepth) {
        double[] point = side.pointAt(s);
        double[] u = side.directionAt(s);
        double across = currentSideOffset + depthOffset / 2.0;
        return new double[]{point[0] + u[1] * across, point[1] - u[0] * across, u[0], u[1], halfWidth, halfDepth};
    }

    /** Marks the closest object overhanging each vertex past VERTEX_OVERHANG for vertex fitting. */
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

    /** Fits an object over a vertex: touching both neighbors, or squared where it stands, or left straight. */
    private void fitType7VertexObject(List<SidePath> sides, Type7Object object, List<Type7Object> planned) {
        int n = sides.size();
        SidePath prevSide = sides.get((object.vertex - 1 + n) % n);
        SidePath nextSide = sides.get(object.vertex);
        VertexFit fit = new VertexFit(sides, object.vertex, offsetCorner(sides, object.vertex, object.userOffset, object.userOffset),
                object.footprint[4], object.footprint[5]);

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
            List<double[]> others = new ArrayList<>();
            double near = 3 * (fit.halfWidth + fit.halfDepth);
            for (Type7Object other : planned) {
                double[] f = other.footprint;
                if (other != object && other != before && other != after && other.settled
                        && Math.hypot(f[0] - fit.vx, f[1] - fit.vy) < near + f[4] + f[5]) {
                    others.add(f);
                }
            }
            best = fit.touching(before.footprint, after.footprint, others);
        }
        if (best == null) {
            best = fit.squareWhereItIs(object.footprint[0], object.footprint[1]);
            if (best != null && fit.cover(best) < 0) {
                best = null;
            }
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

    /** Large solid interior wedge behind a convex vertex for SAT separation. */
    private static double[][] wedge(double vx, double vy, double[] uIn, double[] uOut) {
        double far = 1e5;
        return new double[][]{{vx, vy}, {vx - uIn[0] * far, vy - uIn[1] * far}, {vx + uOut[0] * far, vy + uOut[1] * far}};
    }

    /** Searches rotation/shift poses over a corner vertex touching the face without penetrating it. */
    private static final class VertexFit {
        final double vx, vy, bx, by;
        final double[] uIn, uOut;
        final boolean concave;
        final double halfWidth, halfDepth, baseAngle;

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

        /** Coarse grid (+/-44 deg, +/-reach) picks 8 distinct seeds, each refined 5 times on a 5x finer grid. */
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

        /** Footprint rotated to angle, shifted across the bisector, and pushed along it to just touch the walls. */
        double[] footprint(double angle, double shift) {
            double cos = Math.cos(angle), sin = Math.sin(angle);
            double baseX = vx - by * shift, baseY = vy + bx * shift;
            double d;
            if (concave) {
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
                // Separate from the solid wall wedge behind both sides via SAT
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
                    double t = along > 1e-9 ? (max + radius - center) / along
                            : along < -1e-9 ? (min - radius - center) / along
                            : (center - radius >= max || center + radius <= min ? -Double.MAX_VALUE : Double.MAX_VALUE);
                    d = Math.min(d, t);
                }
                if (d <= -1e8 || d >= 1e8) {
                    return null;
                }
            }
            return new double[]{baseX + bx * d, baseY + by * d, cos, sin, halfWidth, halfDepth};
        }

        private double radius(double cos, double sin, double nx, double ny) {
            return halfWidth * Math.abs(cos * nx + sin * ny) + halfDepth * Math.abs(-sin * nx + cos * ny);
        }

        /** Footprint squared to the bisector from (x, y) and translated minimally to touch the walls. */
        double[] squareWhereItIs(double x, double y) {
            double cos = Math.cos(baseAngle), sin = Math.sin(baseAngle);
            double[] center;
            if (concave) {
                double[][] normals = {{uIn[1], -uIn[0]}, {uOut[1], -uOut[0]}};
                double[] radius = {radius(cos, sin, normals[0][0], normals[0][1]), radius(cos, sin, normals[1][0], normals[1][1])};
                double n1x = normals[0][0], n1y = normals[0][1], n2x = normals[1][0], n2y = normals[1][1];
                double det = n1x * n2y - n1y * n2x;
                if (Math.abs(det) < 1e-9) {
                    return null;
                }
                double apexX = vx + (radius[0] * n2y - radius[1] * n1y) / det;
                double apexY = vy + (n1x * radius[1] - n2x * radius[0]) / det;
                center = new double[]{apexX, apexY};
                double bestDistance = Math.hypot(apexX - x, apexY - y);

                for (int i = 0; i < 2; i++) {
                    double nx = normals[i][0], ny = normals[i][1];
                    double move = radius[i] - ((x - vx) * nx + (y - vy) * ny);
                    double px = x + nx * move, py = y + ny * move;
                    double[] other = normals[1 - i];
                    if ((px - vx) * other[0] + (py - vy) * other[1] >= radius[1 - i] - 1e-9 && Math.abs(move) < bestDistance) {
                        bestDistance = Math.abs(move);
                        center = new double[]{px, py};
                    }
                }
            } else {
                // Project center onto boundary of Minkowski sum of wedge and piece
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

        /** Signed margin (>= 0 when rear face encloses vertex by at least VERTEX_COVER). */
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

    private static List<double[]> convexHull(List<double[]> points) {
        List<double[]> sorted = new ArrayList<>(points);
        Collections.sort(sorted, (a, b) -> a[0] != b[0] ? Double.compare(a[0], b[0]) : Double.compare(a[1], b[1]));
        int n = sorted.size();
        double[][] hull = new double[2 * n][];
        int k = 0;
        for (int i = 0; i < n; i++) {
            while (k >= 2 && cross(hull[k - 2], hull[k - 1], sorted.get(i)) <= 0) {
                k--;
            }
            hull[k++] = sorted.get(i);
        }
        for (int i = n - 2, lower = k + 1; i >= 0; i--) {
            while (k >= lower && cross(hull[k - 2], hull[k - 1], sorted.get(i)) <= 0) {
                k--;
            }
            hull[k++] = sorted.get(i);
        }
        return new ArrayList<>(java.util.Arrays.asList(hull).subList(0, Math.max(1, k - 1)));
    }

    private static double cross(double[] o, double[] a, double[] b) {
        return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0]);
    }

    private static double[][] footprintCorners(double[] f) {
        double ax = f[2] * f[4], ay = f[3] * f[4], dx = -f[3] * f[5], dy = f[2] * f[5];
        return new double[][]{{f[0] - ax - dx, f[1] - ay - dy}, {f[0] + ax - dx, f[1] + ay - dy},
            {f[0] + ax + dx, f[1] + ay + dy}, {f[0] - ax + dx, f[1] - ay + dy}};
    }

    /** Positive distance when disjoint, negative penetration depth when overlapping. */
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

    /** SAT penetration depth (> 0 if overlapping, <= 0 if separated). */
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

    private static double pointSegmentDistance(double[] p, double[] a, double[] b) {
        double dx = b[0] - a[0], dy = b[1] - a[1], l = dx * dx + dy * dy;
        double t = l == 0 ? 0 : Math.max(0, Math.min(1, ((p[0] - a[0]) * dx + (p[1] - a[1]) * dy) / l));
        return Math.hypot(p[0] - a[0] - t * dx, p[1] - a[1] - t * dy);
    }

    // ---- Gradual properties & vertical stacking ----

    private static boolean appliesTo(SurroundRandomSettings settings, boolean isCorner) {
        return settings != null && (!isCorner || settings.applyToCorners);
    }

    /** Corner pieces disable depth and angle jitter so they stay anchored to the corner without hitting side rows. */
    private static SurroundRandomSettings forPiece(SurroundRandomSettings settings, boolean isCorner) {
        if (!isCorner) {
            return settings;
        }
        SurroundRandomSettings safe = settings.copy();
        safe.enabled.put("depth", false);
        safe.enabled.put("angle_jitter", false);
        return safe;
    }

    private void applyGradualToFirstLevel(SurroundConfig config) {
        if (!config.enableGradualProps || config.gradualSettings == null || !config.gradualSettings.hasAnyGradual()
                || firstLevelPlacedFurniture.isEmpty()) {
            return;
        }
        SurroundRandomSettings grad = config.gradualSettings;
        if (grad.gradualScope == SurroundRandomSettings.SCOPE_PER_LEVEL && config.enableVerticalStacking) {
            for (HomePieceOfFurniture piece : firstLevelPlacedFurniture) {
                boolean isCorner = cornerBasePieces.contains(piece);
                if (appliesTo(grad, isCorner)) {
                    MaterialUtils.applyGradualProperties(piece, forPiece(grad, isCorner), 0.0, 0);
                }
            }
        } else if (grad.gradualScope == SurroundRandomSettings.SCOPE_PER_SIDE) {
            for (List<HomePieceOfFurniture> side : sidePlacedGroups.values()) {
                applyGradualInOrder(inShownOrder(side), grad, config.reverseDirection);
            }
        } else {
            applyGradualInOrder(inShownOrder(firstLevelPlacedFurniture), grad, config.reverseDirection);
        }
    }

    /** Reverses each interior room's run so pieces follow the user-visible direction. */
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

    /** Stacks copies above level 1, optionally re-drawing random properties from cleanBasePieces. */
    private void applyVerticalStacking(SurroundConfig config) {
        if (!config.enableVerticalStacking || config.verticalStackCount <= 0 || firstLevelPlacedFurniture.isEmpty()) {
            return;
        }
        int count = firstLevelPlacedFurniture.size();
        float[] elevations = new float[count];
        for (int i = 0; i < count; i++) {
            elevations[i] = firstLevelPlacedFurniture.get(i).getElevation();
        }
        boolean independentRandom = config.enableRandomProps && config.randomSettings != null
                && config.randomSettings.independentStackRandom && config.randomSettings.hasAny();
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
                elevations[i] += config.useCustomStackElevation ? (float) config.customStackElevation : basePiece.getHeight();
                stackedCopy.setElevation(elevations[i]);
                pieceRooms.put(stackedCopy, pieceRooms.get(basePiece));
                pieceSideIndices.put(stackedCopy, pieceSideIndices.get(basePiece));
                pieceVertexIndices.put(stackedCopy, pieceVertexIndices.get(basePiece));
                pieceRoomSideCounts.put(stackedCopy, pieceRoomSideCounts.get(basePiece));
                if (checksCollisions() && hasCollision(stackedCopy)) {
                    continue;
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

    // ---- Piece creation & collision checks ----

    private void createSurroundingFromTemplate(HomePieceOfFurniture template, float x, float y, float angle,
            float width, float depth, float height, boolean applyTemplateBaseAngle, boolean ignoreCollisions,
            boolean isCornerPiece) {
        if (measuringUniform) {
            return;
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

        if (isBlockedByUpperLevelFloorHole(fCopy)
                || (checksCollisions() && !ignoreCollisions && hasCollision(fCopy))) {
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
     * On upper levels with FLOOR_HOLES_NO_PASS, drops pieces over floor openings and flags
     * {@link SurroundConfig#skippedDueToNoFloorBelow} when no lower floor exists beneath them.
     */
    private boolean isBlockedByUpperLevelFloorHole(HomePieceOfFurniture candidate) {
        if (activeConfig.upperLevelFloorHolesMode != SurroundConfig.FLOOR_HOLES_NO_PASS || home == null) {
            return false;
        }
        Level roomLevel = (currentProcessingRoom != null) ? currentProcessingRoom.getLevel() : candidate.getLevel();
        if (roomLevel == null || !isAboveLowestLevel(roomLevel)) {
            return false;
        }
        List<Room> sameLevelRooms = new ArrayList<>(), lowerLevelRooms = new ArrayList<>();
        for (Room r : home.getRooms()) {
            Level rl = r.getLevel();
            if (rl == roomLevel) {
                sameLevelRooms.add(r);
            } else if (rl == null || rl.getElevation() < roomLevel.getElevation() - 1e-3) {
                lowerLevelRooms.add(r);
            }
        }
        if (sameLevelRooms.isEmpty()) {
            return false;
        }
        float[][] corners = getScaledPolygon(candidate, -2f, -2f);
        List<float[]> points = new ArrayList<>(5);
        points.add(new float[]{candidate.getX(), candidate.getY()});
        Collections.addAll(points, corners);
        boolean overHole = false, hasFloorBelow = true;
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
        return overHole;
    }

    private static boolean isOnAnyFloor(float x, float y, List<Room> rooms) {
        for (Room r : rooms) {
            if (SurroundTarget.toPath(r.getPoints()).contains(x, y)) {
                return true;
            }
        }
        return false;
    }

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

    /** Exact Area intersection for non-convex/curved polygons (thresholds overlap > 0.01 x 0.01 cm). */
    private static boolean polygonsOverlapExact(float[][] a, float[][] b) {
        if (!SurroundTarget.toPath(a).getBounds2D().intersects(SurroundTarget.toPath(b).getBounds2D())) {
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

    /** Peak wall height over the portion of a (possibly sloping) wall spanned by polygon. */
    private float wallHeightAlong(Wall wall, float[][] polygon) {
        Float height = wall.getHeight();
        float h = height != null ? height : (home != null ? home.getWallHeight() : 250f);
        Float heightAtEnd = wall.getHeightAtEnd();
        if (heightAtEnd != null && Math.abs(heightAtEnd - h) > 1e-3f) {
            double dx = wall.getXEnd() - wall.getXStart(), dy = wall.getYEnd() - wall.getYStart();
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

    private static boolean overlapsInHeight(float z0, float z1, HomePieceOfFurniture piece, float zTol) {
        float pieceZ0 = piece.getElevation(), pieceH = piece.getHeight();
        float effTol = Math.min(zTol, Math.min(z1 - z0, pieceH) * 0.45f);
        return z1 > pieceZ0 + effTol && z0 < pieceZ0 + pieceH - effTol;
    }

    /** True when placed belongs to the same side or an incident corner vertex (ignored by avoidNewlyGenerated). */
    private boolean isSameSideOrVertexEnd(Room candRoom, int candSide, int candVert, int candCount,
            HomePieceOfFurniture placed) {
        if (candRoom == null || pieceRooms.get(placed) != candRoom || candCount <= 0) {
            return false;
        }
        int placedSide = pieceSideIndices.getOrDefault(placed, -1);
        int placedVert = pieceVertexIndices.getOrDefault(placed, -1);
        if (candSide >= 0 && (placedSide == candSide || placedVert == candSide || placedVert == (candSide + 1) % candCount)) {
            return true;
        }
        return candVert >= 0 && (placedVert == candVert || placedSide == candVert || placedSide == (candVert - 1 + candCount) % candCount);
    }

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

    private static boolean bboxOverlap(float[] a, float[] b) {
        return a[2] >= b[0] && a[0] <= b[2] && a[3] >= b[1] && a[1] <= b[3];
    }

    private void prepareCollisionCaches(Room face) {
        Level roomLevel = face != null ? face.getLevel() : null;
        List<HomePieceOfFurniture> exList = new ArrayList<>();
        List<float[][]> exP = new ArrayList<>();
        List<float[]> exB = new ArrayList<>();
        for (HomePieceOfFurniture existing : existingHomeFurniture) {
            if (!existing.isDoorOrWindow() && (roomLevel == null || existing.getLevel() == null || existing.getLevel() == roomLevel)) {
                float[][] pts = existing.getPoints();
                exList.add(existing);
                exP.add(pts);
                exB.add(bbox(pts));
            }
        }
        existingPieces = exList.toArray(new HomePieceOfFurniture[0]);
        existingPolys = exP.toArray(new float[0][][]);
        existingBounds = exB.toArray(new float[0][]);

        List<Wall> wList = new ArrayList<>();
        List<float[][]> wP = new ArrayList<>();
        List<float[]> wB = new ArrayList<>();
        if (home != null && home.getWalls() != null) {
            for (Wall wall : home.getWalls()) {
                if (roomLevel == null || wall.getLevel() == null || wall.isAtLevel(roomLevel)) {
                    float[][] pts = wall.getPoints();
                    wList.add(wall);
                    wP.add(pts);
                    wB.add(bbox(pts));
                }
            }
        }
        activeWalls = wList.toArray(new Wall[0]);
        wallPolys = wP.toArray(new float[0][][]);
        wallBounds = wB.toArray(new float[0][]);

        List<float[][]> fP = new ArrayList<>();
        List<float[]> fB = new ArrayList<>();
        List<Room> currentSourceRooms = face != null && activeConfig.faceSourceRooms != null
                ? activeConfig.faceSourceRooms.get(face) : null;
        if (activeConfig.avoidNearbyFloors && currentSourceRooms != null && home != null && home.getRooms() != null) {
            for (Room otherRoom : home.getRooms()) {
                if (otherRoom != face && !currentSourceRooms.contains(otherRoom)
                        && (roomLevel == null || otherRoom.getLevel() == null || otherRoom.isAtLevel(roomLevel))) {
                    float[][] pts = otherRoom.getPoints();
                    fP.add(pts);
                    fB.add(bbox(pts));
                }
            }
        }
        nearbyFloorPolys = fP.toArray(new float[0][][]);
        nearbyFloorBounds = fB.toArray(new float[0][]);
    }

    /** Whether the placed objects are checked for collisions: with furniture and walls, or with nearby floors. */
    private boolean checksCollisions() {
        return activeConfig.avoidCollisions || activeConfig.avoidNearbyFloors;
    }

    /**
     * Checks collisions against: (1) existing furniture (doors/windows are gaps), (2) level walls,
     * (3) nearby unmerged floors (avoidNearbyFloors), and (4) pieces placed on other sides/rooms (avoidNewlyGenerated).
     */
    private boolean hasCollision(HomePieceOfFurniture candidate) {
        checkCancelled();
        float colTol = (float) fitTolerance(activeConfig, false);
        // Shrink slightly by 0.5 cm + 2*colTol to avoid rejecting pieces grazing a boundary
        float shrinkW = -Math.min(candidate.getWidth() * 0.8f, 0.5f + 2.0f * colTol);
        float shrinkD = -Math.min(candidate.getDepth() * 0.8f, 0.5f + 2.0f * colTol);
        float[][] candidatePoly = getScaledPolygon(candidate, shrinkW, shrinkD);
        float[] candBox = bbox(candidatePoly);
        float candZ0 = candidate.getElevation(), candZ1 = candZ0 + candidate.getHeight();
        float zTol = 0.1f + colTol;
        Level roomLevel = (currentProcessingRoom != null) ? currentProcessingRoom.getLevel() : null;

        if (activeConfig.avoidCollisions && existingPieces != null) {
            for (int i = 0; i < existingPieces.length; i++) {
                if (bboxOverlap(candBox, existingBounds[i])
                        && overlapsInHeight(candZ0, candZ1, existingPieces[i], zTol)
                        && polygonsOverlapSAT(candidatePoly, existingPolys[i])) {
                    return true;
                }
            }
        }

        if (activeConfig.avoidCollisions && activeWalls != null && activeWalls.length > 0) {
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
                if (candZ1 > wallBottom + effWallZTol && candZ0 < wallBottom + wallH - effWallZTol
                        && (wp.length == 4 ? polygonsOverlapSAT(candidatePoly, wp) : polygonsOverlapExact(candidatePoly, wp))) {
                    return true;
                }
            }
        }

        if (nearbyFloorPolys != null && nearbyFloorPolys.length > 0) {
            for (int i = 0; i < nearbyFloorPolys.length; i++) {
                if (bboxOverlap(candBox, nearbyFloorBounds[i]) && polygonsOverlapExact(candidatePoly, nearbyFloorPolys[i])) {
                    return true;
                }
            }
        }

        if (activeConfig.avoidCollisions && activeConfig.avoidNewlyGenerated) {
            Room candRoom = pieceRooms.getOrDefault(candidate, currentProcessingRoom);
            int candSide = pieceSideIndices.getOrDefault(candidate, currentSideIndex);
            int candVert = pieceVertexIndices.getOrDefault(candidate, currentVertexIndex);
            int candCount = pieceRoomSideCounts.getOrDefault(candidate, currentRoomSideCount);
            for (HomePieceOfFurniture placed : newlyPlacedFurniture) {
                if (isSameSideOrVertexEnd(candRoom, candSide, candVert, candCount, placed)
                        || !overlapsInHeight(candZ0, candZ1, placed, zTol)) {
                    continue;
                }
                float maxSpan = (candidate.getWidth() + candidate.getDepth() + placed.getWidth() + placed.getDepth()) * 0.6f;
                if (Math.abs(candidate.getX() - placed.getX()) > maxSpan || Math.abs(candidate.getY() - placed.getY()) > maxSpan) {
                    continue;
                }
                if (polygonsOverlapSAT(candidatePoly, getScaledPolygon(placed, -0.5f, -0.5f))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Piece footprint grown/shrunk along its own axes (computed from vertices so it works for non-resizable pieces). */
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
        double ux = points[1][0] - points[0][0], uy = points[1][1] - points[0][1];
        double vx = points[2][0] - points[1][0], vy = points[2][1] - points[1][1];
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

    private static boolean polygonsOverlapSAT(float[][] polyA, float[][] polyB) {
        return !hasSeparatingAxis(polyA, polyB) && !hasSeparatingAxis(polyB, polyA);
    }

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
                return true;
            }
        }
        return false;
    }

    double[] getPieceSurroundingDimensions(HomePieceOfFurniture piece) {
        float pieceAngle = (((float) Math.toDegrees(piece.getAngle()) % 360f) + 360f) % 360f;
        if (Math.abs(pieceAngle) < 1e-3 || Math.abs(pieceAngle - 180f) < 1e-3 || Math.abs(pieceAngle - 360f) < 1e-3) {
            return new double[]{piece.getWidth(), piece.getDepth()};
        }
        if (Math.abs(pieceAngle - 90f) < 1e-3 || Math.abs(pieceAngle - 270f) < 1e-3) {
            return new double[]{piece.getDepth(), piece.getWidth()};
        }
        float[] b = bbox(piece.getPoints());
        return new double[]{b[2] - b[0], b[3] - b[1]};
    }

    /**
     * Groups 2+ selected pieces into a single template group; if all are unplaced (e.g. fresh from catalog),
     * arranges them side-by-side along X first.
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
        return new HomeFurnitureGroup(clones, clones.get(0).getName() + " (" + clones.size() + ")");
    }

    public static void collectTemplatePieces(HomePieceOfFurniture piece, Set<HomePieceOfFurniture> targetSet) {
        targetSet.add(piece);
        if (piece instanceof HomeFurnitureGroup) {
            for (HomePieceOfFurniture child : ((HomeFurnitureGroup) piece).getFurniture()) {
                collectTemplatePieces(child, targetSet);
            }
        }
    }

    private double getRandomScale(double minPercent, double maxPercent) {
        double min = minPercent / 100.0;
        return min + Math.random() * (maxPercent / 100.0 - min);
    }
}
