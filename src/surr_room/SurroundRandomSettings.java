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

    public static final String[] RANDOM_PROPS = {
        "shininess", "light_power", "look", "elevation", "height", "depth", "pitch", "roll", "angle_jitter"
    };

    public static final int MODE_VALUE = 0, MODE_ADD = 1, MODE_PERCENT = 2;
    public static final int PATTERN_LINEAR = 0, PATTERN_PING_PONG = 1, PATTERN_STEP = 2;
    public static final int SCOPE_WHOLE_OUTLINE = 0, SCOPE_PER_SIDE = 1, SCOPE_PER_LEVEL = 2;

    public static final class RandomLook {

        public static final class Choice {
            public Integer color;
            public HomeTexture texture;
            public double weight = 1;
            public boolean vary = true;

            public Choice(Integer color) {
                this.color = color;
            }

            public Choice(HomeTexture texture) {
                this.texture = texture;
            }

            public Choice copy() {
                Choice c = new Choice(color);
                c.texture = texture;
                c.weight = weight;
                c.vary = vary;
                return c;
            }
        }

        public boolean varyComponents = true;
        /** When true, applies the same delta to R, G and B simultaneously. */
        public boolean sameComponents;
        public Integer startColor = 0x3A7BD5;
        public Integer endColor = 0xE67E22;
        public int[] modes = {MODE_ADD, MODE_ADD, MODE_ADD};
        public double[] mins = {-30, -30, -30};
        public double[] maxs = {30, 30, 30};
        public boolean fromList;
        public List<Choice> choices = new ArrayList<>();
        public boolean initialized;

        public boolean hasAnything() {
            return varyComponents || (fromList && totalWeight() > 0);
        }

        public double totalWeight() {
            double sum = 0;
            for (Choice c : choices) {
                if (c.weight > 0 && (c.color != null || c.texture != null)) {
                    sum += c.weight;
                }
            }
            return sum;
        }

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
                    if ((r -= c.weight) <= 0) {
                        return c;
                    }
                }
            }
            return last;
        }

        public int varyColor(int rgb, Random rng) {
            int[] parts = {(rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF};
            double sharedDraw = sameComponents ? drawComponent(0, rng) : 0;
            boolean zeroShared = sameComponents && mins[0] == 0 && maxs[0] == 0;
            for (int c = 0; c < 3; c++) {
                double next = sameComponents
                        ? (zeroShared ? parts[c] : applyMode(modes[0], parts[c], sharedDraw))
                        : applyMode(modes[c], parts[c], drawComponent(c, rng));
                parts[c] = Math.max(0, Math.min(255, (int) Math.round(next)));
            }
            return (parts[0] << 16) | (parts[1] << 8) | parts[2];
        }

        private double drawComponent(int c, Random rng) {
            double lo = Math.min(mins[c], maxs[c]), hi = Math.max(mins[c], maxs[c]);
            return lo + (hi - lo) * rng.nextDouble();
        }

        public RandomLook copy() {
            RandomLook copy = new RandomLook();
            copy.copyFrom(this);
            return copy;
        }

        public void copyFrom(RandomLook src) {
            varyComponents = src.varyComponents;
            sameComponents = src.sameComponents;
            modes = src.modes.clone();
            mins = src.mins.clone();
            maxs = src.maxs.clone();
            fromList = src.fromList;
            initialized = src.initialized;
            startColor = src.startColor;
            endColor = src.endColor;
            choices.clear();
            for (Choice c : src.choices) {
                choices.add(c.copy());
            }
        }
    }

    public final Map<String, Boolean> enabled = new LinkedHashMap<>();
    public final Map<String, Integer> modes = new LinkedHashMap<>();
    public final Map<String, Double> mins = new LinkedHashMap<>();
    public final Map<String, Double> maxs = new LinkedHashMap<>();
    public final Map<String, Boolean> whole = new LinkedHashMap<>();
    public final Map<String, Set<String>> materials = new LinkedHashMap<>();
    public final Map<String, Map<HomePieceOfFurniture, Set<String>>> pieceMaterials = new LinkedHashMap<>();
    public RandomLook look = new RandomLook();
    public boolean mirror;
    public double mirrorProbability = 50;
    public boolean randomObjectSubset;
    public double objectProbability = 50;
    public boolean independentStackRandom = true;
    public boolean applyToCorners = true;
    public int gradualPattern = PATTERN_LINEAR;
    public int gradualScope = SCOPE_WHOLE_OUTLINE;

    public SurroundRandomSettings() {
        for (String prop : RANDOM_PROPS) {
            enabled.put(prop, false);
            whole.put(prop, true);
            materials.put(prop, new LinkedHashSet<String>());
            pieceMaterials.put(prop, new IdentityHashMap<HomePieceOfFurniture, Set<String>>());
            if ("shininess".equals(prop) || "light_power".equals(prop)) {
                setDefaults(prop, MODE_VALUE, 0.0, 100.0);
            } else if ("height".equals(prop) || "depth".equals(prop)) {
                setDefaults(prop, MODE_PERCENT, 85.0, 115.0);
            } else if ("elevation".equals(prop)) {
                setDefaults(prop, MODE_ADD, 0.0, 20.0);
            } else if ("pitch".equals(prop) || "roll".equals(prop) || "angle_jitter".equals(prop)) {
                setDefaults(prop, MODE_ADD, -5.0, 5.0);
            } else {
                setDefaults(prop, MODE_ADD, 0.0, 0.0);
            }
        }
    }

    private void setDefaults(String prop, int mode, double min, double max) {
        modes.put(prop, mode);
        mins.put(prop, min);
        maxs.put(prop, max);
    }

    private static double applyMode(int mode, double current, double value) {
        return mode == MODE_VALUE ? value : mode == MODE_ADD ? current + value : current * (value / 100.0);
    }

    public static boolean supportsMaterials(String prop) {
        return "shininess".equals(prop) || "look".equals(prop);
    }

    public Set<String> materialsForPiece(String prop, HomePieceOfFurniture piece) {
        Map<HomePieceOfFurniture, Set<String>> byPiece = pieceMaterials.get(prop);
        Set<String> set = (byPiece != null && piece != null) ? byPiece.get(piece) : null;
        return set != null ? set : materials.get(prop);
    }

    public Set<String> ensureMaterialsForPiece(String prop, HomePieceOfFurniture piece) {
        return pieceMaterials.computeIfAbsent(prop, k -> new IdentityHashMap<>())
                .computeIfAbsent(piece, k -> new LinkedHashSet<>());
    }

    private Set<String> unionPerPieceMaterials(String prop) {
        Set<String> union = new LinkedHashSet<>();
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

    public boolean hasPerPieceMaterials(String prop) {
        return !unionPerPieceMaterials(prop).isEmpty();
    }

    public int totalMaterialsCount(String prop) {
        Set<String> perPiece = unionPerPieceMaterials(prop);
        if (!perPiece.isEmpty()) {
            return perPiece.size();
        }
        Set<String> flat = materials.get(prop);
        return flat != null ? flat.size() : 0;
    }

    public Collection<String> allSelectedMaterials(String prop) {
        Set<String> union = new LinkedHashSet<>();
        Set<String> flat = materials.get(prop);
        if (flat != null) {
            union.addAll(flat);
        }
        union.addAll(unionPerPieceMaterials(prop));
        return union;
    }

    public boolean isWholeTarget(String prop) {
        return !Boolean.FALSE.equals(whole.get(prop))
                || (materials.get(prop).isEmpty() && !hasPerPieceMaterials(prop));
    }

    public boolean isActive(String prop) {
        if (!Boolean.TRUE.equals(enabled.get(prop))) {
            return false;
        }
        if ("look".equals(prop)) {
            return look.hasAnything();
        }
        int mode = modes.get(prop);
        double min = mins.get(prop), max = maxs.get(prop);
        return mode == MODE_ADD ? (min != 0.0 || max != 0.0)
                : mode == MODE_PERCENT ? (min != 100.0 || max != 100.0) : true;
    }

    public boolean hasAny() {
        return activeFeatureCount() > 0;
    }

    public int activeFeatureCount() {
        int count = (mirror && mirrorProbability > 0) ? 1 : 0;
        for (String prop : RANDOM_PROPS) {
            if (isActive(prop)) {
                count++;
            }
        }
        return count;
    }

    public double draw(String prop, double current, Random rng) {
        double lo = Math.min(mins.get(prop), maxs.get(prop)), hi = Math.max(mins.get(prop), maxs.get(prop));
        return applyMode(modes.get(prop), current, lo + (hi - lo) * rng.nextDouble());
    }

    public double evalGradual(String prop, double current, double t, int itemIndex) {
        double startVal = mins.get(prop), endOrStep = maxs.get(prop);
        double effT = gradualPattern == PATTERN_PING_PONG ? (t <= 0.5 ? t * 2.0 : 2.0 * (1.0 - t)) : t;
        double v = gradualPattern == PATTERN_STEP
                ? startVal + itemIndex * endOrStep
                : startVal + (endOrStep - startVal) * Math.max(0.0, Math.min(1.0, effT));
        return applyMode(modes.get(prop), current, v);
    }

    public boolean hasAnyGradual() {
        return activeGradualCount() > 0;
    }

    public int activeGradualCount() {
        int count = 0;
        for (String prop : RANDOM_PROPS) {
            if (Boolean.TRUE.equals(enabled.get(prop))) {
                count++;
            }
        }
        return count;
    }

    public SurroundRandomSettings copy() {
        SurroundRandomSettings copy = new SurroundRandomSettings();
        copy.copyFrom(this);
        return copy;
    }

    private static <K, V> void replaceMap(Map<K, V> dst, Map<K, V> src) {
        dst.clear();
        dst.putAll(src);
    }

    public void copyFrom(SurroundRandomSettings src) {
        replaceMap(enabled, src.enabled);
        replaceMap(modes, src.modes);
        replaceMap(mins, src.mins);
        replaceMap(maxs, src.maxs);
        replaceMap(whole, src.whole);
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
