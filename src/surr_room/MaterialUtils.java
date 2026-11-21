/*
 * Copyright (c) 2024-2026 Oliver Mart\u00ednez (Ollie_Jess)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package surr_room;

import com.eteks.sweethome3d.j3d.ModelManager;
import com.eteks.sweethome3d.model.Content;
import com.eteks.sweethome3d.model.HomeFurnitureGroup;
import com.eteks.sweethome3d.model.HomeLight;
import com.eteks.sweethome3d.model.HomeMaterial;
import com.eteks.sweethome3d.model.HomePieceOfFurniture;
import com.eteks.sweethome3d.model.HomeTexture;
import com.eteks.sweethome3d.tools.URLContent;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Image;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Function;
import javax.imageio.ImageIO;
import javax.media.j3d.Appearance;
import javax.media.j3d.BranchGroup;
import javax.media.j3d.Group;
import javax.media.j3d.Link;
import javax.media.j3d.Material;
import javax.media.j3d.Node;
import javax.media.j3d.Shape3D;
import javax.media.j3d.Texture;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.vecmath.Color3f;

/**
 * Materials of the models and properties of the placed pieces: reads (and caches) the named materials of
 * a model, applies colors, textures and shininess to the whole piece or to chosen materials, applies the
 * random and gradual properties, keeps the back of a piece on its side when its depth changes, and draws
 * the swatches and texture icons of the dialogs.
 */
public final class MaterialUtils {

    /** Sentinel string key denoting that property changes target the entire piece rather than a sub-material. */
    static final String WHOLE_PIECE = "[piece]";

    /** Private constructor preventing instantiation of utility class. */
    private MaterialUtils() {
    }

    /**
     * Resolves a unique cache key for a furniture piece's underlying 3D model.
     *
     * @return URL string, Content object, or name fallback key
     */
    static Object modelKey(HomePieceOfFurniture piece) {
        Content model = piece.getModel();
        if (model instanceof URLContent) {
            return ((URLContent) model).getURL().toString();
        } else if (model != null) {
            return model;
        } else {
            return "NAME:" + piece.getName();
        }
    }

    /** Immutable snapshot descriptor representing a material on a specific 3D furniture piece. */
    public static final class PieceMaterial {
        /** Material name as identified in the 3D model. */
        public final String name;

        /** Material key identifier. */
        public final String key;

        /** Base 24-bit RGB diffuse color, or null if uncolored. */
        public final Integer color;

        /** Base texture applied to this material, or null. */
        public final HomeTexture texture;

        /** Base specular glossiness coefficient [0.0, 1.0], or null. */
        public final Float shininess;

        /** Associated furniture piece instance. */
        private final HomePieceOfFurniture piece;

        /** Constructs a PieceMaterial record. */
        public PieceMaterial(String name, String key, Integer color, HomeTexture texture, Float shininess,
                             HomePieceOfFurniture piece) {
            this.name = name;
            this.key = key != null ? key : name;
            this.color = color;
            this.texture = texture;
            this.shininess = shininess;
            this.piece = piece;
        }

        /**
         * Generates an interactive 3D thumbnail preview with this material highlighted in orange.
         *
         * @return rendered preview image, or null
         */
        public Image partPreview(int size) {
            return piece != null ? MaterialPreview.highlighted(piece, name, size) : null;
        }
    }

    /**
     * Gathers all materials defined across a piece of furniture (including child pieces if a group).
     *
     * @param property  target property ("color", "texture", "look", or "shininess")
     */
    public static List<PieceMaterial> pieceMaterialsOf(HomePieceOfFurniture piece, String property, boolean forOrigin) {
        Map<String, PieceMaterial> map = new LinkedHashMap<>();
        collectPieceMaterials(piece, property, forOrigin, map);
        return new ArrayList<>(map.values());
    }

    /** Internal recursive helper collecting materials across furniture groups and composite models. */
    private static void collectPieceMaterials(HomePieceOfFurniture piece, String property, boolean forOrigin,
                                              Map<String, PieceMaterial> out) {
        if (piece == null) {
            return;
        }
        if (piece instanceof HomeFurnitureGroup) {
            for (HomePieceOfFurniture child : ((HomeFurnitureGroup) piece).getFurniture()) {
                collectPieceMaterials(child, property, forOrigin, out);
            }
            return;
        }

        Map<String, HomeMaterial> explicit = new LinkedHashMap<>();
        HomeMaterial[] mats = piece.getModelMaterials();
        if (mats != null) {
            for (HomeMaterial m : mats) {
                if (m != null && m.getName() != null) {
                    explicit.put(m.getName(), m);
                }
            }
        }

        Map<String, PieceMaterial> defaults = loadModelMaterials(piece);
        Set<String> allNames = new LinkedHashSet<>(defaults.keySet());
        allNames.addAll(explicit.keySet());

        Integer pieceColor = piece.getColor();
        HomeTexture pieceTex = piece.getTexture();
        Float pieceShine = piece.getShininess();

        for (String name : allNames) {
            PieceMaterial def = defaults.get(name);
            HomeMaterial exp = explicit.get(name);

            String key = exp != null && exp.getKey() != null ? exp.getKey()
                    : (def != null ? def.key : name);
            Integer color = exp != null && exp.getColor() != null ? exp.getColor()
                    : (pieceColor != null ? pieceColor : (def != null ? def.color : null));
            HomeTexture tex = exp != null && exp.getTexture() != null ? exp.getTexture()
                    : (pieceTex != null ? pieceTex : (def != null ? def.texture : null));
            if (exp != null && exp.getColor() != null && exp.getTexture() == null) {
                tex = null;
            }
            Float shine = exp != null && exp.getShininess() != null ? exp.getShininess()
                    : (pieceShine != null ? pieceShine : (def != null ? def.shininess : null));

            if (forOrigin) {
                if ("color".equals(property) && color == null) {
                    continue;
                }
                if ("texture".equals(property) && tex == null) {
                    continue;
                }
                if ("look".equals(property) && color == null && tex == null) {
                    continue;
                }
                if ("shininess".equals(property) && shine == null) {
                    continue;
                }
            }
            if (!out.containsKey(name)) {
                out.put(name, new PieceMaterial(name, key, color, tex, shine, piece));
            }
        }
    }

    /** Thread-safe LRU cache storing extracted materials maps for up to 64 distinct 3D model definitions. */
    private static final Map<Object, Map<String, PieceMaterial>> MODEL_MATERIALS = new LinkedHashMap<Object, Map<String, PieceMaterial>>(16, 0.75f, true) {
        private static final long serialVersionUID = 1L;

        @Override
        protected boolean removeEldestEntry(Map.Entry<Object, Map<String, PieceMaterial>> eldest) {
            return size() > 64;
        }
    };

    /** Loads default materials for a piece's model, using the LRU cache when available. */
    private static synchronized Map<String, PieceMaterial> loadModelMaterials(HomePieceOfFurniture piece) {
        Content model = piece.getModel();
        if (model == null) {
            return new LinkedHashMap<>();
        }
        Object key = modelKey(piece);
        Map<String, PieceMaterial> cached = MODEL_MATERIALS.get(key);
        if (cached == null) {
            cached = readModelMaterials(model);
            if (cached == null) {
                return new LinkedHashMap<>(); // Not kept in cache if read failed, allowing retry
            }
            MODEL_MATERIALS.put(key, cached);
        }
        return cached;
    }

    /**
     * Reads default materials directly from a 3D model Content using Sweet Home 3D's ModelManager.
     *
     * @return map of materials or null if load failed
     */
    private static Map<String, PieceMaterial> readModelMaterials(Content model) {
        final Map<String, PieceMaterial> result = new LinkedHashMap<>();
        final boolean[] failed = {false};
        try {
            // Synchronous, and reads from Sweet Home 3D's internal cache if already loaded
            ModelManager.getInstance().loadModel(model, true, new ModelManager.ModelObserver() {
                @Override
                public void modelUpdated(BranchGroup modelRoot) {
                    extractFromNode(modelRoot, result);
                    if (result.isEmpty()) {
                        addSweetHomeMaterials(modelRoot, result);
                    }
                }

                @Override
                public void modelError(Exception ex) {
                    failed[0] = true;
                }
            });
        } catch (Throwable ex) {
            failed[0] = true;
        }
        return failed[0] ? null : result;
    }

    /** Queries Sweet Home 3D 6.0+ ModelManager.getMaterials(Node) reflectively if supported. */
    private static void addSweetHomeMaterials(Node root, Map<String, PieceMaterial> out) {
        try {
            Object materials = ModelManager.class.getMethod("getMaterials", Node.class).invoke(ModelManager.getInstance(), root);
            if (materials instanceof HomeMaterial[]) {
                for (HomeMaterial m : (HomeMaterial[]) materials) {
                    if (m != null && m.getName() != null && !out.containsKey(m.getName())) {
                        Integer color = m.getColor() != null ? m.getColor() & 0xFFFFFF : null;
                        out.put(m.getName(), new PieceMaterial(m.getName(), m.getKey() != null ? m.getKey() : m.getName(),
                                color, m.getTexture(), m.getShininess(), null));
                    }
                }
            }
        } catch (Throwable ignored) {
            // Older Sweet Home 3D version: fallback to manual scene graph traversal
        }
    }

    /** Traverses a scene graph node extracting material names, diffuse colors, textures, and shininess. */
    private static void extractFromNode(Node node, Map<String, PieceMaterial> out) {
        if (node instanceof Group) {
            Group g = (Group) node;
            for (int i = 0, n = g.numChildren(); i < n; i++) {
                extractFromNode(g.getChild(i), out);
            }
        } else if (node instanceof Link) {
            // Shapes shared across multiple branches (common in DAE and 3DS files)
            extractFromNode(((Link) node).getSharedGroup(), out);
        } else if (node instanceof Shape3D) {
            Appearance app = ((Shape3D) node).getAppearance();
            if (app == null) {
                return;
            }
            String name = null;
            try {
                name = app.getName();
            } catch (Throwable ignored) {
            }
            if (name == null || name.isEmpty()) {
                try {
                    name = node.getName();
                } catch (Throwable ignored) {
                }
            }
            if (name == null || name.isEmpty() || out.containsKey(name)) {
                return;
            }
            Integer color = null;
            Float shininess = null;
            Material mat = app.getMaterial();
            if (mat != null) {
                Color3f diffuse = new Color3f();
                mat.getDiffuseColor(diffuse);
                int r = Math.min(255, Math.max(0, Math.round(diffuse.x * 255f)));
                int g = Math.min(255, Math.max(0, Math.round(diffuse.y * 255f)));
                int b = Math.min(255, Math.max(0, Math.round(diffuse.z * 255f)));
                color = (r << 16) | (g << 8) | b;
                shininess = Math.min(1f, Math.max(0f, mat.getShininess() / 128f));
            }
            HomeTexture homeTex = extractDefaultTexture(app, name);
            out.put(name, new PieceMaterial(name, name, color, homeTex, shininess, null));
        }
    }

    /** Extracts a default {@link HomeTexture} from a Java3D Appearance texture object. */
    private static HomeTexture extractDefaultTexture(Appearance app, String name) {
        try {
            Texture tex = app.getTexture();
            if (tex == null) {
                return null;
            }
            Object userData = tex.getUserData();
            Content content = null;
            if (userData instanceof Content) {
                content = (Content) userData;
            } else if (userData instanceof java.net.URL) {
                content = new URLContent((java.net.URL) userData);
            } else if (userData instanceof String) {
                try {
                    content = new URLContent(new java.net.URL((String) userData));
                } catch (Exception ignored) {
                }
            }
            if (content == null) {
                return null;
            }
            final Content imgContent = content;
            final float w = Math.max(1f, tex.getWidth());
            final float h = Math.max(1f, tex.getHeight());
            final String texName = name;
            return new HomeTexture(new com.eteks.sweethome3d.model.TextureImage() {
                private static final long serialVersionUID = 1L;
                @Override public String getName() { return texName; }
                @Override public String getCreator() { return null; }
                @Override public Content getImage() { return imgContent; }
                @Override public float getWidth() { return w; }
                @Override public float getHeight() { return h; }
            });
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Resolves the current value of a property on a piece of furniture, checking both
     * whole-piece settings and sub-materials.
     *
     * @param materialKeys collection of targeted material keys if not whole
     * @return resolved value object or null
     */
    public static Object valueOf(HomePieceOfFurniture piece, String property, boolean whole, Collection<String> materialKeys) {
        if (piece == null) {
            return null;
        }
        if (whole) {
            Object v = directPieceValue(piece, property);
            if (v != null) {
                return v;
            }
            if (piece instanceof HomeFurnitureGroup) {
                for (HomePieceOfFurniture child : ((HomeFurnitureGroup) piece).getFurniture()) {
                    Object cv = valueOf(child, property, true, materialKeys);
                    if (cv != null) {
                        return cv;
                    }
                }
            }
            for (PieceMaterial pm : pieceMaterialsOf(piece, property, true)) {
                Object mv = materialField(pm, property);
                if (mv != null) {
                    return mv;
                }
            }
            return null;
        }
        if (materialKeys == null || materialKeys.isEmpty()) {
            return null;
        }
        Map<String, PieceMaterial> byName = new LinkedHashMap<>();
        for (PieceMaterial pm : pieceMaterialsOf(piece, property, false)) {
            byName.put(pm.name, pm);
        }
        for (String key : materialKeys) {
            PieceMaterial pm = byName.get(key);
            if (pm != null) {
                Object mv = materialField(pm, property);
                if (mv != null) {
                    return mv;
                }
            }
        }
        return null;
    }

    /** Resolves the direct whole-piece property value (color, texture, look, or shininess). */
    private static Object directPieceValue(HomePieceOfFurniture piece, String property) {
        if ("color".equals(property)) {
            return piece.getColor();
        } else if ("texture".equals(property)) {
            return piece.getTexture();
        } else if ("look".equals(property)) {
            if (piece.getTexture() != null) {
                return piece.getTexture();
            }
            return piece.getColor();
        } else if ("shininess".equals(property)) {
            return piece.getShininess();
        }
        return null;
    }

    /** Extracts the specific field corresponding to {@code property} from a {@link PieceMaterial}. */
    public static Object materialField(PieceMaterial m, String property) {
        if (m == null) {
            return null;
        }
        if ("color".equals(property)) {
            return m.color;
        } else if ("texture".equals(property)) {
            return m.texture;
        } else if ("look".equals(property)) {
            return m.texture != null ? m.texture : m.color;
        } else if ("shininess".equals(property)) {
            return m.shininess;
        }
        return null;
    }

    /** Extracts the specific field corresponding to {@code property} from a {@link HomeMaterial}. */
    public static Object materialField(HomeMaterial m, String property) {
        if (m == null) {
            return null;
        }
        if ("color".equals(property)) {
            return m.getColor();
        } else if ("texture".equals(property)) {
            return m.getTexture();
        } else if ("look".equals(property)) {
            return m.getTexture() != null ? m.getTexture() : m.getColor();
        } else if ("shininess".equals(property)) {
            return m.getShininess();
        }
        return null;
    }

    /**
     * Assigns a property value directly to the whole piece, clearing conflicting material overrides.
     *
     * @param property property key ("color", "texture", "look", or "shininess")
     */
    public static void setPieceValue(HomePieceOfFurniture piece, String property, Object value) {
        if (value == null) {
            return;
        }
        if (piece instanceof HomeFurnitureGroup) {
            for (HomePieceOfFurniture child : ((HomeFurnitureGroup) piece).getFurniture()) {
                setPieceValue(child, property, value);
            }
        }
        if ("color".equals(property) && value instanceof Integer) {
            piece.setTexture(null);
            piece.setColor((Integer) value);
            clearMaterialOverride(piece, "color");
        } else if ("texture".equals(property) && value instanceof HomeTexture) {
            piece.setColor(null);
            piece.setTexture((HomeTexture) value);
            clearMaterialOverride(piece, "texture");
        } else if ("look".equals(property)) {
            if (value instanceof HomeTexture) {
                piece.setColor(null);
                piece.setTexture((HomeTexture) value);
                clearMaterialOverride(piece, "look");
            } else if (value instanceof Integer) {
                piece.setTexture(null);
                piece.setColor((Integer) value);
                clearMaterialOverride(piece, "look");
            }
        } else if ("shininess".equals(property) && value instanceof Float) {
            piece.setShininess((Float) value);
            clearMaterialOverride(piece, "shininess");
        }
    }

    /** Clears conflicting per-material overrides for the specified property so whole-piece styling takes effect. */
    private static void clearMaterialOverride(HomePieceOfFurniture piece, String property) {
        HomeMaterial[] mats = piece.getModelMaterials();
        if (mats == null || mats.length == 0) {
            return;
        }
        HomeMaterial[] updated = new HomeMaterial[mats.length];
        for (int i = 0; i < mats.length; i++) {
            HomeMaterial m = mats[i];
            if (m == null) {
                continue;
            }
            Integer color = ("color".equals(property) || "texture".equals(property) || "look".equals(property)) ? null : m.getColor();
            HomeTexture tex = ("color".equals(property) || "texture".equals(property) || "look".equals(property)) ? null : m.getTexture();
            Float shine = "shininess".equals(property) ? null : m.getShininess();
            updated[i] = new HomeMaterial(m.getName(), m.getKey(), color, tex, shine);
        }
        piece.setModelMaterials(updated);
    }

    /** Applies property values selectively to specific materials identified by {@code targetMaterialKeys}. */
    public static void applyToMaterials(HomePieceOfFurniture piece, String property,
                                        Collection<String> targetMaterialKeys,
                                        Function<HomeMaterial, Object> valueForMaterial) {
        if (piece == null || targetMaterialKeys == null || targetMaterialKeys.isEmpty()) {
            return;
        }
        if (piece instanceof HomeFurnitureGroup) {
            for (HomePieceOfFurniture child : ((HomeFurnitureGroup) piece).getFurniture()) {
                applyToMaterials(child, property, targetMaterialKeys, valueForMaterial);
            }
            return;
        }

        List<PieceMaterial> all = pieceMaterialsOf(piece, property, false);
        if (all.isEmpty()) {
            return;
        }
        Map<String, HomeMaterial> explicit = new LinkedHashMap<>();
        HomeMaterial[] current = piece.getModelMaterials();
        if (current != null) {
            for (HomeMaterial m : current) {
                if (m != null && m.getName() != null) {
                    explicit.put(m.getName(), m);
                }
            }
        }

        boolean setsLook = "color".equals(property) || "texture".equals(property) || "look".equals(property);
        boolean setsShine = "shininess".equals(property);
        Integer wholeColor = piece.getColor();
        HomeTexture wholeTex = piece.getTexture();
        Float wholeShine = piece.getShininess();
        if (setsLook && (wholeColor != null || wholeTex != null)) {
            piece.setColor(null);
            piece.setTexture(null);
        }
        if (setsShine && wholeShine != null) {
            piece.setShininess(null);
        }

        List<HomeMaterial> updated = new ArrayList<>(all.size());
        for (PieceMaterial pm : all) {
            HomeMaterial exp = explicit.get(pm.name);
            Integer baseColor = exp != null && exp.getColor() != null ? exp.getColor()
                    : (wholeColor != null ? wholeColor : pm.color);
            HomeTexture baseTex = exp != null && exp.getTexture() != null ? exp.getTexture()
                    : (wholeTex != null ? wholeTex : pm.texture);
            if (exp != null && exp.getColor() != null && exp.getTexture() == null) {
                baseTex = null;
            }
            Float baseShine = exp != null && exp.getShininess() != null ? exp.getShininess()
                    : (wholeShine != null ? wholeShine : pm.shininess);
            HomeMaterial baseMat = new HomeMaterial(pm.name, pm.key, baseColor, baseTex, baseShine);

            if (targetMaterialKeys.contains(pm.name)) {
                Object val = valueForMaterial.apply(baseMat);
                if (val != null) {
                    Integer color = exp != null ? exp.getColor() : (setsLook ? baseColor : null);
                    HomeTexture tex = exp != null ? exp.getTexture() : (setsLook ? baseTex : null);
                    Float shine = exp != null ? exp.getShininess() : (setsShine ? baseShine : null);
                    if ("color".equals(property) && val instanceof Integer) {
                        color = (Integer) val;
                        tex = null;
                    } else if ("texture".equals(property) && val instanceof HomeTexture) {
                        tex = (HomeTexture) val;
                        color = null;
                    } else if ("look".equals(property)) {
                        if (val instanceof HomeTexture) {
                            tex = (HomeTexture) val;
                            color = null;
                        } else if (val instanceof Integer) {
                            color = (Integer) val;
                            tex = null;
                        }
                    } else if ("shininess".equals(property) && val instanceof Float) {
                        shine = (Float) val;
                    }
                    updated.add(new HomeMaterial(pm.name, pm.key, color, tex, shine));
                    continue;
                }
            }
            if (exp != null) {
                Integer c = exp.getColor() != null ? exp.getColor() : (setsLook ? wholeColor : null);
                HomeTexture t = exp.getTexture() != null ? exp.getTexture() : (setsLook && exp.getColor() == null ? wholeTex : null);
                Float s = exp.getShininess() != null ? exp.getShininess() : (setsShine ? wholeShine : null);
                updated.add(new HomeMaterial(exp.getName(), exp.getKey(), c, t, s));
            } else if ((setsLook && (wholeColor != null || wholeTex != null)) || (setsShine && wholeShine != null)) {
                updated.add(new HomeMaterial(pm.name, pm.key,
                        setsLook ? wholeColor : null,
                        setsLook ? wholeTex : null,
                        setsShine ? wholeShine : null));
            }
        }
        piece.setModelMaterials(updated.toArray(new HomeMaterial[0]));
    }

    /**
     * Applies all active stochastic random properties to a placed furniture piece.
     * Respects subset sampling probability and model mirroring.
     */
    public static void applyRandomProperties(HomePieceOfFurniture piece, SurroundRandomSettings rnd, Random rng) {
        if (piece == null || rnd == null || !rnd.hasAny()) {
            return;
        }
        if (rnd.randomObjectSubset && rnd.objectProbability < 100.0) {
            if (rnd.objectProbability <= 0.0 || rng.nextDouble() * 100.0 >= rnd.objectProbability) {
                return;
            }
        }
        applyProperties(piece, rnd, rnd::isActive, (property, current) -> rnd.draw(property, current, rng),
                () -> applyRandomLook(piece, rnd, rng));
        if (rnd.mirror && rng.nextDouble() * 100.0 < rnd.mirrorProbability) {
            piece.setModelMirrored(!piece.isModelMirrored());
        }
    }

    /**
     * Applies gradual mathematical transitions to properties on a placed piece at parameter {@code t} in [0, 1].
     *
     * @param t         normalized position [0.0, 1.0]
     * @param itemIndex 0-based piece index along the progression
     */
    public static void applyGradualProperties(HomePieceOfFurniture piece, SurroundRandomSettings grad, double t, int itemIndex) {
        if (piece == null || grad == null || !grad.hasAnyGradual()) {
            return;
        }
        applyProperties(piece, grad, property -> Boolean.TRUE.equals(grad.enabled.get(property)),
                (property, current) -> grad.evalGradual(property, current, t, itemIndex),
                () -> applyGradualLook(piece, grad, t));
    }

    /** Functional interface computing the next property value. */
    private interface NextValue {
        double of(String property, double current);
    }

    /** Internal dispatcher applying numeric properties and looks in a deterministic order. */
    private static void applyProperties(HomePieceOfFurniture piece, SurroundRandomSettings settings,
            java.util.function.Predicate<String> on, NextValue next, Runnable look) {
        if (on.test("shininess")) {
            if (settings.isWholeTarget("shininess")) {
                Object current = valueOf(piece, "shininess", true, Collections.<String>emptySet());
                setPieceValue(piece, "shininess", percent(next.of("shininess", current instanceof Float ? (Float) current * 100.0 : 0.0)));
            } else {
                applyToMaterials(piece, "shininess", settings.allSelectedMaterials("shininess"),
                        m -> percent(next.of("shininess", m != null && m.getShininess() != null ? m.getShininess() * 100.0 : 0.0)));
            }
        }
        if (on.test("light_power")) {
            applyLightPower(piece, next);
        }
        if (on.test("look")) {
            look.run();
        }
        if (on.test("elevation")) {
            piece.setElevation((float) clamp(next.of("elevation", piece.getElevation()), -10000.0, 10000.0));
        }
        if (on.test("height") && piece.isResizable()) {
            piece.setHeight((float) clamp(next.of("height", piece.getHeight()), 0.5, 10000.0));
        }
        if (on.test("depth") && piece.isResizable()) {
            double oldDepth = piece.getDepth();
            applyAnchoredDepthChange(piece, oldDepth, clamp(next.of("depth", oldDepth), 0.5, 10000.0));
        }
        // Angles are unbounded in degrees; Sweet Home 3D normalizes them to [0, 2*PI)
        if (on.test("pitch") && piece.isHorizontallyRotatable()) {
            piece.setPitch((float) Math.toRadians(next.of("pitch", Math.toDegrees(piece.getPitch()))));
        }
        if (on.test("roll") && piece.isHorizontallyRotatable()) {
            piece.setRoll((float) Math.toRadians(next.of("roll", Math.toDegrees(piece.getRoll()))));
        }
        if (on.test("angle_jitter")) {
            piece.setAngle((float) Math.toRadians(next.of("angle_jitter", Math.toDegrees(piece.getAngle()))));
        }
    }

    /** Recursively updates emitter light power if the piece is a {@link HomeLight} or group. */
    private static void applyLightPower(HomePieceOfFurniture piece, NextValue next) {
        if (piece instanceof HomeLight) {
            HomeLight light = (HomeLight) piece;
            light.setPower(percent(next.of("light_power", light.getPower() * 100.0)));
        } else if (piece instanceof HomeFurnitureGroup) {
            for (HomePieceOfFurniture child : ((HomeFurnitureGroup) piece).getFurniture()) {
                applyLightPower(child, next);
            }
        }
    }

    /** Converts a 0-100 percentage value into a [0.0, 1.0] float fraction. */
    private static float percent(double value) {
        return (float) (clamp(value, 0.0, 100.0) / 100.0);
    }

    /** Numerical clamping helper. */
    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * Registry mapping placed furniture pieces to their local outward normal vectors (nx, ny)
     * pointing away from the wall/floor edge.
     */
    private static final Map<HomePieceOfFurniture, double[]> PIECE_OUTWARD_NORMALS = new IdentityHashMap<>();

    /** Registers the outward placement normal vector (nx, ny) for a furniture piece. */
    public static void registerOutwardNormal(HomePieceOfFurniture piece, double nx, double ny) {
        if (piece != null) {
            PIECE_OUTWARD_NORMALS.put(piece, new double[]{nx, ny});
        }
    }

    /** Copies registered normal vector from source piece to target piece (e.g. for stacked copies). */
    public static void copyOutwardNormal(HomePieceOfFurniture source, HomePieceOfFurniture target) {
        if (source != null && target != null) {
            double[] n = PIECE_OUTWARD_NORMALS.get(source);
            if (n != null) {
                PIECE_OUTWARD_NORMALS.put(target, n);
            }
        }
    }

    /** Clears the outward normal registry map. */
    public static void clearOutwardNormals() {
        PIECE_OUTWARD_NORMALS.clear();
    }

    /**
     * Resizes the depth of a placed piece while shifting its center coordinate (x, y) along the
     * wall's outward normal (nx, ny). This guarantees that the back face of the furniture piece
     * remains firmly anchored against the wall surface without penetrating into the wall or detaching.
     *
     * @param oldDepth prior depth in cm
     * @param newDepth updated depth in cm
     */
    private static void applyAnchoredDepthChange(HomePieceOfFurniture piece, double oldDepth, double newDepth) {
        if (Math.abs(newDepth - oldDepth) < 1e-4) {
            return;
        }
        double scaleAcross = newDepth / Math.max(0.5, oldDepth);
        double[] wallNormal = PIECE_OUTWARD_NORMALS.get(piece);
        double nx, ny;
        if (wallNormal != null) {
            nx = wallNormal[0];
            ny = wallNormal[1];
            double wallAngle = Math.atan2(nx, -ny);
            double relAngle = piece.getAngle() - wallAngle;
            double c = Math.abs(Math.cos(relAngle)), s = Math.abs(Math.sin(relAngle));
            double oldAcross = piece.getWidth() * s + piece.getDepth() * c;
            if (s > 0.707) {
                // Piece was rotated ~90 deg in the plan: its width lies across the wall!
                piece.setWidth((float) Math.max(0.5, piece.getWidth() * scaleAcross));
            } else {
                piece.setDepth((float) Math.max(0.5, piece.getDepth() * scaleAcross));
            }
            double newAcross = piece.getWidth() * s + piece.getDepth() * c;
            double delta = (newAcross - oldAcross) / 2.0;
            piece.setX((float) (piece.getX() + nx * delta));
            piece.setY((float) (piece.getY() + ny * delta));
        } else {
            double delta = (newDepth - oldDepth) / 2.0;
            double a = piece.getAngle();
            nx = Math.sin(a);
            ny = -Math.cos(a);
            piece.setDepth((float) newDepth);
            piece.setX((float) (piece.getX() + nx * delta));
            piece.setY((float) (piece.getY() + ny * delta));
        }
    }

    /** Interpolates RGB colors gradually from startColor to endColor based on normalized position t. */
    private static void applyGradualLook(HomePieceOfFurniture piece, SurroundRandomSettings grad, double t) {
        double effT = grad.gradualPattern == SurroundRandomSettings.PATTERN_PING_PONG
                ? (t <= 0.5 ? (t * 2.0) : (2.0 * (1.0 - t)))
                : Math.max(0.0, Math.min(1.0, t));
        int c0 = grad.look.startColor != null ? grad.look.startColor : 0x3A7BD5;
        int c1 = grad.look.endColor != null ? grad.look.endColor : 0xE67E22;
        int r = (int) Math.round(((c0 >> 16) & 0xFF) + (((c1 >> 16) & 0xFF) - ((c0 >> 16) & 0xFF)) * effT);
        int g = (int) Math.round(((c0 >> 8) & 0xFF) + (((c1 >> 8) & 0xFF) - ((c0 >> 8) & 0xFF)) * effT);
        int b = (int) Math.round((c0 & 0xFF) + ((c1 & 0xFF) - (c0 & 0xFF)) * effT);
        int rgb = (Math.max(0, Math.min(255, r)) << 16)
                | (Math.max(0, Math.min(255, g)) << 8)
                | Math.max(0, Math.min(255, b));
        if (grad.isWholeTarget("look")) {
            setPieceValue(piece, "look", rgb);
        } else {
            applyToMaterials(piece, "look", grad.allSelectedMaterials("look"), m -> rgb);
        }
    }

    /** Samples and applies a random color or texture from the configured random look model. */
    private static void applyRandomLook(HomePieceOfFurniture piece, SurroundRandomSettings rnd, Random rng) {
        SurroundRandomSettings.RandomLook look = rnd.look;
        if (rnd.isWholeTarget("look")) {
            SurroundRandomSettings.RandomLook.Choice picked = look.drawChoice(rng);
            if (picked != null) {
                if (picked.texture != null) {
                    setPieceValue(piece, "look", picked.texture);
                } else if (picked.color != null) {
                    int rgb = (look.varyComponents && picked.vary) ? look.varyColor(picked.color, rng) : picked.color;
                    setPieceValue(piece, "look", rgb);
                }
            } else if (look.varyComponents) {
                if (piece.getColor() != null) {
                    setPieceValue(piece, "look", look.varyColor(piece.getColor(), rng));
                } else if (piece.getTexture() == null) {
                    Integer base = firstMaterialColor(piece);
                    int varied = look.varyColor(base != null ? base : 0x808080, rng);
                    setPieceValue(piece, "look", varied);
                }
            }
        } else {
            applyToMaterials(piece, "look", rnd.allSelectedMaterials("look"), m -> {
                SurroundRandomSettings.RandomLook.Choice picked = look.drawChoice(rng);
                if (picked != null) {
                    if (picked.texture != null) {
                        return picked.texture;
                    }
                    if (picked.color != null) {
                        return (look.varyComponents && picked.vary) ? look.varyColor(picked.color, rng) : picked.color;
                    }
                }
                if (look.varyComponents && (m == null || m.getTexture() == null)) {
                    int base = (m != null && m.getColor() != null) ? m.getColor() : 0x808080;
                    return look.varyColor(base, rng);
                }
                return null;
            });
        }
    }

    /** Finds the first defined material color on a furniture piece. */
    private static Integer firstMaterialColor(HomePieceOfFurniture piece) {
        for (PieceMaterial pm : pieceMaterialsOf(piece, "look", false)) {
            if (pm.color != null) {
                return pm.color;
            }
        }
        return null;
    }

    /** Builds default look choices for the Random dialog based on colors and textures currently present. */
    public static List<SurroundRandomSettings.RandomLook.Choice> defaultLookChoices(
            List<HomePieceOfFurniture> pieces, boolean whole, Collection<String> materialKeys) {
        List<SurroundRandomSettings.RandomLook.Choice> out = new ArrayList<>();
        Set<Integer> seenColors = new LinkedHashSet<>();
        Map<String, HomeTexture> seenTextures = new LinkedHashMap<>();
        if (pieces != null) {
            for (HomePieceOfFurniture p : pieces) {
                if (whole && p.getTexture() != null) {
                    seenTextures.put(textureKey(p.getTexture()), p.getTexture());
                } else if (whole && p.getColor() != null) {
                    seenColors.add(p.getColor() & 0xFFFFFF);
                } else {
                    for (PieceMaterial pm : pieceMaterialsOf(p, "look", false)) {
                        if (!whole && materialKeys != null && !materialKeys.isEmpty() && !materialKeys.contains(pm.name)) {
                            continue;
                        }
                        if (pm.texture != null) {
                            seenTextures.put(textureKey(pm.texture), pm.texture);
                        } else if (pm.color != null) {
                            seenColors.add(pm.color & 0xFFFFFF);
                        }
                    }
                }
            }
        }
        for (Integer c : seenColors) {
            out.add(new SurroundRandomSettings.RandomLook.Choice(c));
        }
        for (HomeTexture t : seenTextures.values()) {
            out.add(new SurroundRandomSettings.RandomLook.Choice(t));
        }
        return out;
    }

    /** Generates a lookup key for a HomeTexture based on catalog ID and name. */
    private static String textureKey(HomeTexture t) {
        return (t.getCatalogId() != null ? t.getCatalogId() : "") + "|" + (t.getName() != null ? t.getName() : "");
    }

    /** Checks if any piece in the list supports horizontal rotation (pitch and roll). */
    public static boolean containsHorizontallyRotatable(List<HomePieceOfFurniture> pieces) {
        if (pieces == null || pieces.isEmpty()) {
            return true;
        }
        for (HomePieceOfFurniture p : pieces) {
            if (p != null && p.isHorizontallyRotatable()) {
                return true;
            }
        }
        return false;
    }

    /** Checks if any piece in the list is resizable in width, depth, or height. */
    public static boolean containsResizable(List<HomePieceOfFurniture> pieces) {
        if (pieces == null || pieces.isEmpty()) {
            return true;
        }
        for (HomePieceOfFurniture p : pieces) {
            if (p != null && p.isResizable()) {
                return true;
            }
        }
        return false;
    }

    /** Checks if the list contains any light fixture (HomeLight). */
    public static boolean containsLight(List<HomePieceOfFurniture> pieces) {
        if (pieces == null) {
            return false;
        }
        for (HomePieceOfFurniture p : pieces) {
            if (containsLight(p)) {
                return true;
            }
        }
        return false;
    }

    /** Checks if a piece or any child in its group is a HomeLight. */
    public static boolean containsLight(HomePieceOfFurniture piece) {
        if (piece instanceof HomeLight) {
            return true;
        }
        if (piece instanceof HomeFurnitureGroup) {
            for (HomePieceOfFurniture child : ((HomeFurnitureGroup) piece).getFurniture()) {
                if (containsLight(child)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Unwraps single furniture groups to their top-level constituent children for preview lists. */
    public static List<HomePieceOfFurniture> topPiecesOf(List<HomePieceOfFurniture> pieces) {
        if (pieces == null || pieces.isEmpty()) {
            return Collections.emptyList();
        }
        if (pieces.size() == 1 && pieces.get(0) instanceof HomeFurnitureGroup) {
            return new ArrayList<>(((HomeFurnitureGroup) pieces.get(0)).getFurniture());
        }
        return new ArrayList<>(pieces);
    }

    /** Formats a human-readable display string representing a material property value (e.g. hex color or texture name). */
    public static String formatMaterialValue(PieceMaterial m, String property) {
        if ("color".equals(property)) {
            return m.color != null ? String.format("#%06X", m.color & 0xFFFFFF) : "\u2014";
        } else if ("texture".equals(property)) {
            return m.texture != null ? m.texture.getName() : "\u2014";
        } else if ("look".equals(property)) {
            if (m.texture != null) {
                return m.texture.getName();
            }
            return m.color != null ? String.format("#%06X", m.color & 0xFFFFFF) : "\u2014";
        } else if ("shininess".equals(property)) {
            return m.shininess != null ? Math.round(m.shininess * 100f) + "%" : "\u2014";
        }
        return "";
    }

    /**
     * Builds an 18x18 pixel swatch Icon representing a material's color, texture, or shininess.
     *
     * @return swatch Icon, or null
     */
    public static Icon swatchIcon(PieceMaterial m, String property) {
        if ("color".equals(property) && m.color != null) {
            return new ColorIcon(new Color(m.color));
        }
        if (("texture".equals(property) || "look".equals(property)) && m.texture != null) {
            ImageIcon texIcon = loadTextureIcon(m.texture.getImage(), 18);
            if (texIcon != null) {
                return texIcon;
            }
        }
        if ("look".equals(property) && m.color != null) {
            return new ColorIcon(new Color(m.color));
        }
        if ("shininess".equals(property) && m.shininess != null) {
            int g = Math.min(255, Math.max(0, Math.round(80 + m.shininess * 175f)));
            return new ColorIcon(new Color(g, g, g));
        }
        return null;
    }

    /**
     * Loads a scaled thumbnail image from texture Content streams.
     *
     * @return ImageIcon, or null if load fails
     */
    public static ImageIcon loadTextureIcon(Content content, int size) {
        if (content == null) {
            return null;
        }
        try (InputStream is = content.openStream()) {
            Image img = ImageIO.read(is);
            if (img != null) {
                return new ImageIcon(img.getScaledInstance(size, size, Image.SCALE_SMOOTH));
            }
        } catch (IOException ignored) {
        }
        return null;
    }

    /** Simple 18x18 pixel solid color swatch Icon with a 1-pixel gray border. */
    public static final class ColorIcon implements Icon {
        private final Color color;

        public ColorIcon(Color color) {
            this.color = color;
        }

        @Override
        public int getIconWidth() {
            return 18;
        }

        @Override
        public int getIconHeight() {
            return 18;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            g.setColor(color);
            g.fillRect(x + 1, y + 1, 16, 16);
            g.setColor(Color.GRAY);
            g.drawRect(x + 1, y + 1, 15, 15);
        }
    }
}
