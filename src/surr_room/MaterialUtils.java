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
import com.eteks.sweethome3d.model.TextureImage;
import com.eteks.sweethome3d.tools.URLContent;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Image;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
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
import java.util.function.Predicate;
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
 * Extracts and caches named model materials, applies colors/textures/shininess and random/gradual
 * properties to pieces or targeted materials, anchors depth changes to walls, and builds UI swatch icons.
 */
public final class MaterialUtils {

    static final String WHOLE_PIECE = "[piece]";

    private MaterialUtils() {
    }

    static Object modelKey(HomePieceOfFurniture piece) {
        Content model = piece.getModel();
        if (model instanceof URLContent) {
            return ((URLContent) model).getURL().toString();
        }
        return model != null ? model : ("NAME:" + piece.getName());
    }

    public static final class PieceMaterial {
        public final String name, key;
        public final Integer color;
        public final HomeTexture texture;
        public final Float shininess;
        private final HomePieceOfFurniture piece;

        public PieceMaterial(String name, String key, Integer color, HomeTexture texture, Float shininess,
                             HomePieceOfFurniture piece) {
            this.name = name;
            this.key = key != null ? key : name;
            this.color = color;
            this.texture = texture;
            this.shininess = shininess;
            this.piece = piece;
        }

        public Image partPreview(int size) {
            return piece != null ? MaterialPreview.highlighted(piece, name, size) : null;
        }
    }

    public static List<PieceMaterial> pieceMaterialsOf(HomePieceOfFurniture piece, String property, boolean forOrigin) {
        Map<String, PieceMaterial> map = new LinkedHashMap<>();
        collectPieceMaterials(piece, property, forOrigin, map);
        return new ArrayList<>(map.values());
    }

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
        Map<String, HomeMaterial> explicit = explicitMaterialsMap(piece);
        Map<String, PieceMaterial> defaults = loadModelMaterials(piece);
        Set<String> allNames = new LinkedHashSet<>(defaults.keySet());
        allNames.addAll(explicit.keySet());

        Integer pieceColor = piece.getColor();
        HomeTexture pieceTex = piece.getTexture();
        Float pieceShine = piece.getShininess();

        for (String name : allNames) {
            PieceMaterial def = defaults.get(name);
            HomeMaterial exp = explicit.get(name);
            String key = exp != null && exp.getKey() != null ? exp.getKey() : (def != null ? def.key : name);
            Integer color = exp != null && exp.getColor() != null ? exp.getColor()
                    : (pieceColor != null ? pieceColor : (def != null ? def.color : null));
            HomeTexture tex = exp != null && exp.getTexture() != null ? exp.getTexture()
                    : (pieceTex != null ? pieceTex : (def != null ? def.texture : null));
            if (exp != null && exp.getColor() != null && exp.getTexture() == null) {
                tex = null;
            }
            Float shine = exp != null && exp.getShininess() != null ? exp.getShininess()
                    : (pieceShine != null ? pieceShine : (def != null ? def.shininess : null));

            if (forOrigin && pickProp(property, color, tex, shine) == null) {
                continue;
            }
            if (!out.containsKey(name)) {
                out.put(name, new PieceMaterial(name, key, color, tex, shine, piece));
            }
        }
    }

    private static final Map<Object, Map<String, PieceMaterial>> MODEL_MATERIALS = new LinkedHashMap<Object, Map<String, PieceMaterial>>(16, 0.75f, true) {
        private static final long serialVersionUID = 1L;

        @Override
        protected boolean removeEldestEntry(Map.Entry<Object, Map<String, PieceMaterial>> eldest) {
            return size() > 64;
        }
    };

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
                return new LinkedHashMap<>(); // Omit failed loads from cache so later attempts can retry
            }
            MODEL_MATERIALS.put(key, cached);
        }
        return cached;
    }

    private static Map<String, PieceMaterial> readModelMaterials(Content model) {
        final Map<String, PieceMaterial> result = new LinkedHashMap<>();
        final boolean[] failed = {false};
        try {
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
        }
    }

    private static void extractFromNode(Node node, Map<String, PieceMaterial> out) {
        if (node instanceof Group) {
            Group g = (Group) node;
            for (int i = 0, n = g.numChildren(); i < n; i++) {
                extractFromNode(g.getChild(i), out);
            }
        } else if (node instanceof Link) {
            // Shared shapes across branches (common in DAE and 3DS models)
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
            out.put(name, new PieceMaterial(name, name, color, extractDefaultTexture(app, name), shininess, null));
        }
    }

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
            } else if (userData instanceof URL) {
                content = new URLContent((URL) userData);
            } else if (userData instanceof String) {
                try {
                    content = new URLContent(new URL((String) userData));
                } catch (Exception ignored) {
                }
            }
            if (content == null) {
                return null;
            }
            final Content imgContent = content;
            final float w = Math.max(1f, tex.getWidth()), h = Math.max(1f, tex.getHeight());
            return new HomeTexture(new TextureImage() {
                private static final long serialVersionUID = 1L;
                @Override public String getName() { return name; }
                @Override public String getCreator() { return null; }
                @Override public Content getImage() { return imgContent; }
                @Override public float getWidth() { return w; }
                @Override public float getHeight() { return h; }
            });
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean isLookProp(String property) {
        return "color".equals(property) || "texture".equals(property) || "look".equals(property);
    }

    private static Object pickProp(String property, Integer color, HomeTexture tex, Float shine) {
        if ("color".equals(property)) {
            return color;
        }
        if ("texture".equals(property)) {
            return tex;
        }
        if ("look".equals(property)) {
            return tex != null ? tex : color;
        }
        if ("shininess".equals(property)) {
            return shine;
        }
        return null;
    }

    public static Object valueOf(HomePieceOfFurniture piece, String property, boolean whole, Collection<String> materialKeys) {
        if (piece == null) {
            return null;
        }
        if (whole) {
            Object v = pickProp(property, piece.getColor(), piece.getTexture(), piece.getShininess());
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
            Object mv = materialField(byName.get(key), property);
            if (mv != null) {
                return mv;
            }
        }
        return null;
    }

    public static Object materialField(PieceMaterial m, String property) {
        return m == null ? null : pickProp(property, m.color, m.texture, m.shininess);
    }

    public static Object materialField(HomeMaterial m, String property) {
        return m == null ? null : pickProp(property, m.getColor(), m.getTexture(), m.getShininess());
    }

    public static void setPieceValue(HomePieceOfFurniture piece, String property, Object value) {
        if (value == null) {
            return;
        }
        if (piece instanceof HomeFurnitureGroup) {
            for (HomePieceOfFurniture child : ((HomeFurnitureGroup) piece).getFurniture()) {
                setPieceValue(child, property, value);
            }
        }
        if (isLookProp(property)) {
            if (value instanceof HomeTexture && !"color".equals(property)) {
                piece.setColor(null);
                piece.setTexture((HomeTexture) value);
                clearMaterialOverride(piece, property);
            } else if (value instanceof Integer && !"texture".equals(property)) {
                piece.setTexture(null);
                piece.setColor((Integer) value);
                clearMaterialOverride(piece, property);
            }
        } else if ("shininess".equals(property) && value instanceof Float) {
            piece.setShininess((Float) value);
            clearMaterialOverride(piece, property);
        }
    }

    private static void clearMaterialOverride(HomePieceOfFurniture piece, String property) {
        HomeMaterial[] mats = piece.getModelMaterials();
        if (mats == null || mats.length == 0) {
            return;
        }
        boolean clearLook = isLookProp(property), clearShine = "shininess".equals(property);
        HomeMaterial[] updated = new HomeMaterial[mats.length];
        for (int i = 0; i < mats.length; i++) {
            HomeMaterial m = mats[i];
            if (m != null) {
                updated[i] = new HomeMaterial(m.getName(), m.getKey(),
                        clearLook ? null : m.getColor(),
                        clearLook ? null : m.getTexture(),
                        clearShine ? null : m.getShininess());
            }
        }
        piece.setModelMaterials(updated);
    }

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
        Map<String, HomeMaterial> explicit = explicitMaterialsMap(piece);
        boolean setsLook = isLookProp(property), setsShine = "shininess".equals(property);
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
                    if (setsLook && val instanceof HomeTexture && !"color".equals(property)) {
                        tex = (HomeTexture) val;
                        color = null;
                    } else if (setsLook && val instanceof Integer && !"texture".equals(property)) {
                        color = (Integer) val;
                        tex = null;
                    } else if (setsShine && val instanceof Float) {
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
                updated.add(new HomeMaterial(pm.name, pm.key, setsLook ? wholeColor : null,
                        setsLook ? wholeTex : null, setsShine ? wholeShine : null));
            }
        }
        piece.setModelMaterials(updated.toArray(new HomeMaterial[0]));
    }

    public static void applyRandomProperties(HomePieceOfFurniture piece, SurroundRandomSettings rnd, Random rng) {
        if (piece == null || rnd == null || !rnd.hasAny()) {
            return;
        }
        if (rnd.randomObjectSubset && rnd.objectProbability < 100.0
                && (rnd.objectProbability <= 0.0 || rng.nextDouble() * 100.0 >= rnd.objectProbability)) {
            return;
        }
        applyProperties(piece, rnd, rnd::isActive, (property, current) -> rnd.draw(property, current, rng),
                () -> applyRandomLook(piece, rnd, rng));
        if (rnd.mirror && rng.nextDouble() * 100.0 < rnd.mirrorProbability) {
            piece.setModelMirrored(!piece.isModelMirrored());
        }
    }

    public static void applyGradualProperties(HomePieceOfFurniture piece, SurroundRandomSettings grad, double t, int itemIndex) {
        if (piece == null || grad == null || !grad.hasAnyGradual()) {
            return;
        }
        applyProperties(piece, grad, property -> Boolean.TRUE.equals(grad.enabled.get(property)),
                (property, current) -> grad.evalGradual(property, current, t, itemIndex),
                () -> applyGradualLook(piece, grad, t));
    }

    private interface NextValue {
        double of(String property, double current);
    }

    private static void applyProperties(HomePieceOfFurniture piece, SurroundRandomSettings settings,
            Predicate<String> on, NextValue next, Runnable look) {
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

    private static float percent(double value) {
        return (float) (clamp(value, 0.0, 100.0) / 100.0);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static final Map<HomePieceOfFurniture, double[]> PIECE_OUTWARD_NORMALS = new IdentityHashMap<>();

    public static void registerOutwardNormal(HomePieceOfFurniture piece, double nx, double ny) {
        if (piece != null) {
            PIECE_OUTWARD_NORMALS.put(piece, new double[]{nx, ny});
        }
    }

    public static void copyOutwardNormal(HomePieceOfFurniture source, HomePieceOfFurniture target) {
        if (source != null && target != null) {
            double[] n = PIECE_OUTWARD_NORMALS.get(source);
            if (n != null) {
                PIECE_OUTWARD_NORMALS.put(target, n);
            }
        }
    }

    public static void clearOutwardNormals() {
        PIECE_OUTWARD_NORMALS.clear();
    }

    /** Resizes depth while shifting the center along the wall outward normal so the back stays anchored to the side. */
    private static void applyAnchoredDepthChange(HomePieceOfFurniture piece, double oldDepth, double newDepth) {
        if (Math.abs(newDepth - oldDepth) < 1e-4) {
            return;
        }
        double scaleAcross = newDepth / Math.max(0.5, oldDepth);
        double[] wallNormal = PIECE_OUTWARD_NORMALS.get(piece);
        double nx, ny, delta;
        if (wallNormal != null) {
            nx = wallNormal[0];
            ny = wallNormal[1];
            double relAngle = piece.getAngle() - Math.atan2(nx, -ny);
            double c = Math.abs(Math.cos(relAngle)), s = Math.abs(Math.sin(relAngle));
            double oldAcross = piece.getWidth() * s + piece.getDepth() * c;
            if (s > 0.707) { // Rotated ~90 deg in plan: width spans across the wall
                piece.setWidth((float) Math.max(0.5, piece.getWidth() * scaleAcross));
            } else {
                piece.setDepth((float) Math.max(0.5, piece.getDepth() * scaleAcross));
            }
            delta = (piece.getWidth() * s + piece.getDepth() * c - oldAcross) / 2.0;
        } else {
            delta = (newDepth - oldDepth) / 2.0;
            double a = piece.getAngle();
            nx = Math.sin(a);
            ny = -Math.cos(a);
            piece.setDepth((float) newDepth);
        }
        piece.setX((float) (piece.getX() + nx * delta));
        piece.setY((float) (piece.getY() + ny * delta));
    }

    private static Map<String, HomeMaterial> explicitMaterialsMap(HomePieceOfFurniture piece) {
        Map<String, HomeMaterial> explicit = new LinkedHashMap<>();
        HomeMaterial[] mats = piece.getModelMaterials();
        if (mats != null) {
            for (HomeMaterial m : mats) {
                if (m != null && m.getName() != null) {
                    explicit.put(m.getName(), m);
                }
            }
        }
        return explicit;
    }

    private static int lerpChannel(int c0, int c1, int shift, double t) {
        int a = (c0 >> shift) & 0xFF, b = (c1 >> shift) & 0xFF;
        return Math.max(0, Math.min(255, (int) Math.round(a + (b - a) * t)));
    }

    private static void applyGradualLook(HomePieceOfFurniture piece, SurroundRandomSettings grad, double t) {
        double effT = grad.gradualPattern == SurroundRandomSettings.PATTERN_PING_PONG
                ? (t <= 0.5 ? t * 2.0 : 2.0 * (1.0 - t)) : clamp(t, 0.0, 1.0);
        int c0 = grad.look.startColor != null ? grad.look.startColor : 0x3A7BD5;
        int c1 = grad.look.endColor != null ? grad.look.endColor : 0xE67E22;
        int rgb = (lerpChannel(c0, c1, 16, effT) << 16) | (lerpChannel(c0, c1, 8, effT) << 8) | lerpChannel(c0, c1, 0, effT);
        if (grad.isWholeTarget("look")) {
            setPieceValue(piece, "look", rgb);
        } else {
            applyToMaterials(piece, "look", grad.allSelectedMaterials("look"), m -> rgb);
        }
    }

    private static Object resolveRandomLook(SurroundRandomSettings.RandomLook look,
            boolean hasTexture, Integer baseColor, Random rng) {
        SurroundRandomSettings.RandomLook.Choice picked = look.drawChoice(rng);
        if (picked != null) {
            if (picked.texture != null) {
                return picked.texture;
            }
            if (picked.color != null) {
                return (look.varyComponents && picked.vary) ? look.varyColor(picked.color, rng) : picked.color;
            }
        }
        return (look.varyComponents && !hasTexture) ? look.varyColor(baseColor != null ? baseColor : 0x808080, rng) : null;
    }

    private static void applyRandomLook(HomePieceOfFurniture piece, SurroundRandomSettings rnd, Random rng) {
        SurroundRandomSettings.RandomLook look = rnd.look;
        if (rnd.isWholeTarget("look")) {
            Integer base = piece.getColor() != null ? piece.getColor() : firstMaterialColor(piece);
            Object val = resolveRandomLook(look, piece.getColor() == null && piece.getTexture() != null, base, rng);
            if (val != null) {
                setPieceValue(piece, "look", val);
            }
        } else {
            applyToMaterials(piece, "look", rnd.allSelectedMaterials("look"),
                    m -> resolveRandomLook(look, m != null && m.getTexture() != null, m != null ? m.getColor() : null, rng));
        }
    }

    private static Integer firstMaterialColor(HomePieceOfFurniture piece) {
        for (PieceMaterial pm : pieceMaterialsOf(piece, "look", false)) {
            if (pm.color != null) {
                return pm.color;
            }
        }
        return null;
    }

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

    private static String textureKey(HomeTexture t) {
        return (t.getCatalogId() != null ? t.getCatalogId() : "") + "|" + (t.getName() != null ? t.getName() : "");
    }

    private static boolean anyPieceMatches(List<HomePieceOfFurniture> pieces, Predicate<HomePieceOfFurniture> test) {
        if (pieces == null || pieces.isEmpty()) {
            return true;
        }
        for (HomePieceOfFurniture p : pieces) {
            if (p != null && test.test(p)) {
                return true;
            }
        }
        return false;
    }

    public static boolean containsHorizontallyRotatable(List<HomePieceOfFurniture> pieces) {
        return anyPieceMatches(pieces, HomePieceOfFurniture::isHorizontallyRotatable);
    }

    public static boolean containsResizable(List<HomePieceOfFurniture> pieces) {
        return anyPieceMatches(pieces, HomePieceOfFurniture::isResizable);
    }

    public static boolean containsLight(List<HomePieceOfFurniture> pieces) {
        if (pieces != null) {
            for (HomePieceOfFurniture p : pieces) {
                if (containsLight(p)) {
                    return true;
                }
            }
        }
        return false;
    }

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

    public static List<HomePieceOfFurniture> topPiecesOf(List<HomePieceOfFurniture> pieces) {
        if (pieces == null || pieces.isEmpty()) {
            return Collections.emptyList();
        }
        if (pieces.size() == 1 && pieces.get(0) instanceof HomeFurnitureGroup) {
            return new ArrayList<>(((HomeFurnitureGroup) pieces.get(0)).getFurniture());
        }
        return new ArrayList<>(pieces);
    }

    public static String formatMaterialValue(PieceMaterial m, String property) {
        if ("color".equals(property)) {
            return m.color != null ? String.format("#%06X", m.color & 0xFFFFFF) : "\u2014";
        }
        if ("texture".equals(property)) {
            return m.texture != null ? m.texture.getName() : "\u2014";
        }
        if ("look".equals(property)) {
            return m.texture != null ? m.texture.getName()
                    : (m.color != null ? String.format("#%06X", m.color & 0xFFFFFF) : "\u2014");
        }
        if ("shininess".equals(property)) {
            return m.shininess != null ? Math.round(m.shininess * 100f) + "%" : "\u2014";
        }
        return "";
    }

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

    public static final class ColorIcon implements Icon {
        private final Color color;

        public ColorIcon(Color color) {
            this.color = color;
        }

        @Override public int getIconWidth() { return 18; }
        @Override public int getIconHeight() { return 18; }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            g.setColor(color);
            g.fillRect(x + 1, y + 1, 16, 16);
            g.setColor(Color.GRAY);
            g.drawRect(x + 1, y + 1, 15, 15);
        }
    }
}
