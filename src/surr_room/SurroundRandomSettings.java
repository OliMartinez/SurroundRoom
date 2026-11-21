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
import com.eteks.sweethome3d.model.HomeTexture;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Random and gradual property settings, and the drawing of their values. Only properties that don't change
 * the layout along the side vary: look, shininess, light power, elevation, height, depth, tilts and angle.
 */
public class SurroundRandomSettings {

    /**
     * Properties that can vary: shininess and light_power (%), look (colors or textures), elevation, height and
     * depth (cm), pitch and roll (degrees, rotatable pieces only) and angle_jitter (degrees around its angle).
     */
    public static final String[] RANDOM_PROPS = {
        "shininess", "light_power", "look", "elevation", "height", "depth", "pitch", "roll", "angle_jitter"
    };

    /** Direct absolute value assignment mode (e.g. exact degrees or exact percentage). */
    public static final int MODE_VALUE = 0;

    /** Additive relative offset mode (currentValue + delta). */
    public static final int MODE_ADD = 1;

    /** Multiplicative percentage scaling mode (currentValue * percent / 100). */
    public static final int MODE_PERCENT = 2;

    // ---- Gradual Progression Patterns ----

    /** Linear transition from Start value at t=0 to End value at t=1. */
    public static final int PATTERN_LINEAR = 0;

    /** Symmetric triangular wave: transitions from Start to End at midpoint (t=0.5), returning to Start at t=1. */
    public static final int PATTERN_PING_PONG = 1;

    /** Discrete arithmetic step progression: Start + (objectIndex * stepIncrement). */
    public static final int PATTERN_STEP = 2;

    // ---- Gradual Progression Scopes ----

    /** Progresses normalized t continuously across all objects of the entire room/wall outline. */
    public static final int SCOPE_WHOLE_OUTLINE = 0;

    /** Resets normalized progression (t=0 to t=1) on each individual logical side. */
    public static final int SCOPE_PER_SIDE = 1;

    /** Progresses values across stacked vertical levels from bottom tier to top tier. */
    public static final int SCOPE_PER_LEVEL = 2;

    /** Settings governing color and texture randomization and gradual interpolation. */
    public static final class RandomLook {

        /** A candidate color or texture choice within a weighted random draw list. */
        public static final class Choice {
            /** 24-bit RGB packed integer color, or null. */
            public Integer color;

            /** Sweet Home 3D HomeTexture instance, or null. */
            public HomeTexture texture;

            /** Relative probability weight (must be &gt; 0 to participate in draws). */
            public double weight = 1;

            /** Whether this specific choice is eligible for additional RGB color jitter. */
            public boolean vary = true;

            /** Creates a solid color choice. */
            public Choice(Integer color) {
                this.color = color;
            }

            /** Creates a texture choice. */
            public Choice(HomeTexture texture) {
                this.texture = texture;
            }

            /** Clones this Choice. */
            public Choice copy() {
                Choice c = new Choice(color);
                c.texture = texture;
                c.weight = weight;
                c.vary = vary;
                return c;
            }
        }

        /** Whether to apply random channel variations to RGB components. */
        public boolean varyComponents = true;

        /** Whether to apply the same random delta to R, G, and B simultaneously (preserving hue/saturation). */
        public boolean sameComponents;

        /** Starting RGB color for gradual gradient interpolation. */
        public Integer startColor = 0x3A7BD5;

        /** Ending RGB color for gradual gradient interpolation. */
        public Integer endColor = 0xE67E22;

        /** Modification mode per channel [R, G, B] (MODE_VALUE, MODE_ADD, or MODE_PERCENT). */
        public int[] modes = {MODE_ADD, MODE_ADD, MODE_ADD};

        /** Minimum bounds per channel [R, G, B]. */
        public double[] mins = {-30, -30, -30};

        /** Maximum bounds per channel [R, G, B]. */
        public double[] maxs = {30, 30, 30};

        /** Whether to select looks from the weighted choices list. */
        public boolean fromList;

        /** List of weighted color/texture choices. */
        public List<Choice> choices = new ArrayList<>();

        /** Initialization flag. */
        public boolean initialized;

        /** Checks if any look randomization feature is actively configured. */
        public boolean hasAnything() {
            return varyComponents || (fromList && totalWeight() > 0);
        }

        /** Computes the total cumulative weight of valid choices in the list. */
        public double totalWeight() {
            double sum = 0;
            for (Choice c : choices) {
                if (c.weight > 0 && (c.color != null || c.texture != null)) {
                    sum += c.weight;
                }
            }
            return sum;
        }

        /**
         * Performs a weighted pseudo-random draw from the choices list.
         *
         * @return selected Choice or null
         */
        public Choice drawChoice(Random rng) {
            double total = totalWeight();
            if (!fromList || total <= 0) {
                return null;
            }
            double r = rng.nextDouble() * total;
            Choice last = null;
            for (Choice c : choices) {
                if (c.weight > 0 && (c.color != null || c.texture != null)) {
                    last = c;
                    r -= c.weight;
                    if (r <= 0) {
                        return c;
                    }
                }
            }
            return last;
        }

        /**
         * Applies randomized perturbation to an RGB color according to component settings.
         *
         * @param rgb input 24-bit RGB packed integer
         * @return perturbed 24-bit RGB packed integer clamped to [0, 255] per channel
         */
        public int varyColor(int rgb, Random rng) {
            int[] parts = {(rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF};
            Double sharedDraw = sameComponents ? drawComponent(0, parts[0], rng) : null;
            for (int c = 0; c < 3; c++) {
                double next;
                if (sameComponents) {
                    double lo = Math.min(mins[0], maxs[0]);
                    double hi = Math.max(mins[0], maxs[0]);
                    if (modes[0] == MODE_VALUE) {
                        next = sharedDraw;
                    } else if (modes[0] == MODE_ADD) {
                        next = parts[c] + sharedDraw;
                    } else {
                        next = parts[c] * (sharedDraw / 100.0);
                    }
                    if (lo == 0 && hi == 0) {
                        next = parts[c];
                    }
                } else {
                    next = applyComponent(c, parts[c], rng);
                }
                parts[c] = Math.max(0, Math.min(255, (int) Math.round(next)));
            }
            return (parts[0] << 16) | (parts[1] << 8) | parts[2];
        }

        /** Generates a random delta for color component c. */
        private double drawComponent(int c, int current, Random rng) {
            double lo = Math.min(mins[c], maxs[c]);
            double hi = Math.max(mins[c], maxs[c]);
            return lo + (hi - lo) * rng.nextDouble();
        }

        /** Applies component delta using the configured mode. */
        private double applyComponent(int c, int current, Random rng) {
            double d = drawComponent(c, current, rng);
            if (modes[c] == MODE_VALUE) {
                return d;
            } else if (modes[c] == MODE_ADD) {
                return current + d;
            } else {
                return current * (d / 100.0);
            }
        }

        /** Deep clone of RandomLook. */
        public RandomLook copy() {
            RandomLook copy = new RandomLook();
            copy.varyComponents = varyComponents;
            copy.sameComponents = sameComponents;
            copy.modes = modes.clone();
            copy.mins = mins.clone();
            copy.maxs = maxs.clone();
            copy.fromList = fromList;
            copy.initialized = initialized;
            copy.startColor = startColor;
            copy.endColor = endColor;
            for (Choice c : choices) {
                copy.choices.add(c.copy());
            }
            return copy;
        }
    }

    // ---- Property Settings Maps ----

    /** Flags whether each property is active. */
    public final Map<String, Boolean> enabled = new LinkedHashMap<>();

    /** Modification mode per property (MODE_VALUE, MODE_ADD, or MODE_PERCENT). */
    public final Map<String, Integer> modes = new LinkedHashMap<>();

    /** Minimum random / start gradual value per property. */
    public final Map<String, Double> mins = new LinkedHashMap<>();

    /** Maximum random / end gradual value per property. */
    public final Map<String, Double> maxs = new LinkedHashMap<>();

    /** Whether property variation applies to the whole piece (true) or specific sub-materials (false). */
    public final Map<String, Boolean> whole = new LinkedHashMap<>();

    /** Global set of material names targeted for this property. */
    public final Map<String, Set<String>> materials = new LinkedHashMap<>();

    /** Per-piece-type overrides of targeted material names. */
    public final Map<String, Map<HomePieceOfFurniture, Set<String>>> pieceMaterials = new LinkedHashMap<>();

    /** Look (color/texture) settings model. */
    public RandomLook look = new RandomLook();

    /** Random horizontal mirroring across 3D model width without altering bounding placement. */
    public boolean mirror;

    /** Probability percentage (0-100%) for applying mirror flip. */
    public double mirrorProbability = 50;

    /** Apply random properties only to a random percentage subset of placed objects. */
    public boolean randomObjectSubset;

    /** Probability percentage (0-100%) for an object to receive randomized properties. */
    public double objectProbability = 50;

    /** In vertical stacking, whether each tier receives an independent random draw. */
    public boolean independentStackRandom = true;

    /** Whether perpendicular corner pieces also receive randomized/gradual properties. */
    public boolean applyToCorners = true;

    /** Progression pattern for gradual mode (PATTERN_LINEAR, PATTERN_PING_PONG, or PATTERN_STEP). */
    public int gradualPattern = PATTERN_LINEAR;

    /** Progression scope for gradual mode (SCOPE_WHOLE_OUTLINE, SCOPE_PER_SIDE, or SCOPE_PER_LEVEL). */
    public int gradualScope = SCOPE_WHOLE_OUTLINE;

    /** Initializes default configurations for all registered random properties. */
    public SurroundRandomSettings() {
        for (String prop : RANDOM_PROPS) {
            enabled.put(prop, false);
            whole.put(prop, true);
            materials.put(prop, new LinkedHashSet<String>());
            pieceMaterials.put(prop, new IdentityHashMap<HomePieceOfFurniture, Set<String>>());
            if ("shininess".equals(prop) || "light_power".equals(prop)) {
                modes.put(prop, MODE_VALUE);
                mins.put(prop, 0.0);
                maxs.put(prop, 100.0);
            } else if ("height".equals(prop) || "depth".equals(prop)) {
                modes.put(prop, MODE_PERCENT);
                mins.put(prop, 85.0);
                maxs.put(prop, 115.0);
            } else if ("elevation".equals(prop)) {
                modes.put(prop, MODE_ADD);
                mins.put(prop, 0.0);
                maxs.put(prop, 20.0);
            } else if ("pitch".equals(prop) || "roll".equals(prop) || "angle_jitter".equals(prop)) {
                modes.put(prop, MODE_ADD);
                mins.put(prop, -5.0);
                maxs.put(prop, 5.0);
            } else {
                modes.put(prop, MODE_ADD);
                mins.put(prop, 0.0);
                maxs.put(prop, 0.0);
            }
        }
    }

    /** Checks if a property supports fine-grained sub-material targeting (shininess and look). */
    public static boolean supportsMaterials(String prop) {
        return "shininess".equals(prop) || "look".equals(prop);
    }

    /** Retrieves the set of materials targeted for a specific piece of furniture. */
    public Set<String> materialsForPiece(String prop, HomePieceOfFurniture piece) {
        Map<HomePieceOfFurniture, Set<String>> byPiece = pieceMaterials.get(prop);
        if (byPiece != null && piece != null) {
            Set<String> set = byPiece.get(piece);
            if (set != null) {
                return set;
            }
        }
        return materials.get(prop);
    }

    /** Ensures and returns the per-piece material set for the specified piece. */
    public Set<String> ensureMaterialsForPiece(String prop, HomePieceOfFurniture piece) {
        Map<HomePieceOfFurniture, Set<String>> byPiece = pieceMaterials.get(prop);
        if (byPiece == null) {
            byPiece = new IdentityHashMap<>();
            pieceMaterials.put(prop, byPiece);
        }
        Set<String> set = byPiece.get(piece);
        if (set == null) {
            set = new LinkedHashSet<>();
            byPiece.put(piece, set);
        }
        return set;
    }

    /** Checks if any piece has a specific per-piece material configuration. */
    public boolean hasPerPieceMaterials(String prop) {
        Map<HomePieceOfFurniture, Set<String>> byPiece = pieceMaterials.get(prop);
        if (byPiece == null) {
            return false;
        }
        for (Set<String> set : byPiece.values()) {
            if (set != null && !set.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /** Returns the total count of distinct targeted material names for property prop. */
    public int totalMaterialsCount(String prop) {
        Map<HomePieceOfFurniture, Set<String>> byPiece = pieceMaterials.get(prop);
        if (byPiece != null && !byPiece.isEmpty()) {
            Set<String> union = new LinkedHashSet<>();
            for (Set<String> set : byPiece.values()) {
                if (set != null) {
                    union.addAll(set);
                }
            }
            if (!union.isEmpty()) {
                return union.size();
            }
        }
        Set<String> flat = materials.get(prop);
        return flat != null ? flat.size() : 0;
    }

    /** Returns a collection of all targeted material names across global and per-piece definitions. */
    public Collection<String> allSelectedMaterials(String prop) {
        Set<String> union = new LinkedHashSet<>();
        Set<String> flat = materials.get(prop);
        if (flat != null) {
            union.addAll(flat);
        }
        Map<HomePieceOfFurniture, Set<String>> byPiece = pieceMaterials.get(prop);
        if (byPiece != null) {
            for (Set<String> set : byPiece.values()) {
                if (set != null) {
                    union.addAll(set);
                }
            }
        }
        return union;
    }

    /** Checks if the property applies to the whole object rather than selective materials. */
    public boolean isWholeTarget(String prop) {
        return !Boolean.FALSE.equals(whole.get(prop))
                || (materials.get(prop).isEmpty() && !hasPerPieceMaterials(prop));
    }

    /** Determines whether property {@code prop} has an active, non-noop random configuration. */
    public boolean isActive(String prop) {
        if (!Boolean.TRUE.equals(enabled.get(prop))) {
            return false;
        }
        if ("look".equals(prop)) {
            return look.hasAnything();
        }
        int mode = modes.get(prop);
        double min = mins.get(prop);
        double max = maxs.get(prop);
        if (mode == MODE_ADD) {
            return min != 0.0 || max != 0.0;
        }
        if (mode == MODE_PERCENT) {
            return min != 100.0 || max != 100.0;
        }
        return true;
    }

    /** Checks if any random feature (including mirroring) is currently active. */
    public boolean hasAny() {
        if (mirror && mirrorProbability > 0) {
            return true;
        }
        for (String prop : RANDOM_PROPS) {
            if (isActive(prop)) {
                return true;
            }
        }
        return false;
    }

    /** Returns the count of active random features for summary display in the UI. */
    public int activeFeatureCount() {
        int count = 0;
        for (String prop : RANDOM_PROPS) {
            if (isActive(prop)) {
                count++;
            }
        }
        if (mirror && mirrorProbability > 0) {
            count++;
        }
        return count;
    }

    /** Draws a randomized value for property {@code prop} based on its mode and range bounds. */
    public double draw(String prop, double current, Random rng) {
        int mode = modes.get(prop);
        double a = mins.get(prop);
        double b = maxs.get(prop);
        double lo = Math.min(a, b);
        double hi = Math.max(a, b);
        double r = lo + (hi - lo) * rng.nextDouble();
        if (mode == MODE_VALUE) {
            return r;
        } else if (mode == MODE_ADD) {
            return current + r;
        } else {
            return current * (r / 100.0);
        }
    }

    /**
     * Evaluates the gradual interpolated value for property {@code prop} at normalized position {@code t} in [0, 1]
     * (or at 0-based object index {@code itemIndex} when {@code gradualPattern == PATTERN_STEP}).
     *
     * @param t         normalized position [0.0, 1.0]
     * @param itemIndex 0-based sequence index
     */
    public double evalGradual(String prop, double current, double t, int itemIndex) {
        int mode = modes.get(prop);
        double startVal = mins.get(prop);
        double endOrStep = maxs.get(prop);
        double v;
        if (gradualPattern == PATTERN_STEP) {
            v = startVal + itemIndex * endOrStep;
        } else {
            double effT = gradualPattern == PATTERN_PING_PONG
                    ? (t <= 0.5 ? (t * 2.0) : (2.0 * (1.0 - t)))
                    : t;
            v = startVal + (endOrStep - startVal) * Math.max(0.0, Math.min(1.0, effT));
        }
        if (mode == MODE_VALUE) {
            return v;
        } else if (mode == MODE_ADD) {
            return current + v;
        } else {
            return current * (v / 100.0);
        }
    }

    /** Returns true if any gradual property is enabled. */
    public boolean hasAnyGradual() {
        for (String prop : RANDOM_PROPS) {
            if (Boolean.TRUE.equals(enabled.get(prop))) {
                return true;
            }
        }
        return false;
    }

    /** Returns count of active gradual properties. */
    public int activeGradualCount() {
        int count = 0;
        for (String prop : RANDOM_PROPS) {
            if (Boolean.TRUE.equals(enabled.get(prop))) {
                count++;
            }
        }
        return count;
    }

    /** Creates a deep copy of this settings instance. */
    public SurroundRandomSettings copy() {
        SurroundRandomSettings copy = new SurroundRandomSettings();
        copy.copyFrom(this);
        return copy;
    }

    /** Copies all configurations from a source instance into this instance. */
    public void copyFrom(SurroundRandomSettings src) {
        enabled.clear();
        enabled.putAll(src.enabled);
        modes.clear();
        modes.putAll(src.modes);
        mins.clear();
        mins.putAll(src.mins);
        maxs.clear();
        maxs.putAll(src.maxs);
        whole.clear();
        whole.putAll(src.whole);
        materials.clear();
        for (Map.Entry<String, Set<String>> e : src.materials.entrySet()) {
            materials.put(e.getKey(), new LinkedHashSet<>(e.getValue()));
        }
        pieceMaterials.clear();
        for (Map.Entry<String, Map<HomePieceOfFurniture, Set<String>>> e : src.pieceMaterials.entrySet()) {
            Map<HomePieceOfFurniture, Set<String>> mapCopy = new IdentityHashMap<>();
            for (Map.Entry<HomePieceOfFurniture, Set<String>> pe : e.getValue().entrySet()) {
                mapCopy.put(pe.getKey(), new LinkedHashSet<>(pe.getValue()));
            }
            pieceMaterials.put(e.getKey(), mapCopy);
        }
        look = src.look.copy();
        mirror = src.mirror;
        mirrorProbability = src.mirrorProbability;
        randomObjectSubset = src.randomObjectSubset;
        objectProbability = src.objectProbability;
        independentStackRandom = src.independentStackRandom;
        applyToCorners = src.applyToCorners;
        gradualPattern = src.gradualPattern;
        gradualScope = src.gradualScope;
    }
}
