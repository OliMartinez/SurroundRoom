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
import com.eteks.sweethome3d.model.HomePieceOfFurniture;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.media.j3d.Appearance;
import javax.media.j3d.BranchGroup;
import javax.media.j3d.Geometry;
import javax.media.j3d.GeometryArray;
import javax.media.j3d.GeometryStripArray;
import javax.media.j3d.Group;
import javax.media.j3d.IndexedGeometryArray;
import javax.media.j3d.IndexedGeometryStripArray;
import javax.media.j3d.IndexedQuadArray;
import javax.media.j3d.IndexedTriangleArray;
import javax.media.j3d.IndexedTriangleFanArray;
import javax.media.j3d.IndexedTriangleStripArray;
import javax.media.j3d.Link;
import javax.media.j3d.Node;
import javax.media.j3d.QuadArray;
import javax.media.j3d.Shape3D;
import javax.media.j3d.SharedGroup;
import javax.media.j3d.Transform3D;
import javax.media.j3d.TransformGroup;
import javax.media.j3d.TriangleArray;
import javax.media.j3d.TriangleFanArray;
import javax.media.j3d.TriangleStripArray;
import javax.vecmath.Point3f;

/**
 * Software-rendered 3D thumbnail showing one material in orange over a gray model using dual Z-buffers
 * (-28&deg; yaw, 22&deg; pitch, 35% X-ray blend through occluding parts, 2&times; supersampled downscale).
 */
final class MaterialPreview {

    private static final int HIGHLIGHT = 0xFF6A00;
    private static final int OTHER_PARTS = 0xD3D7DD;
    private static final double YAW = Math.toRadians(-28);
    private static final double PITCH = Math.toRadians(22);
    private static final double[] LIGHT = normalize(-0.45, 0.65, 0.62);
    private static final Map<Object, Mesh> MESHES = lruCache(12);
    private static final Map<List<Object>, BufferedImage> IMAGES = lruCache(400);

    private static <K, V> Map<K, V> lruCache(final int maxEntries) {
        return new LinkedHashMap<K, V>(16, 0.75f, true) {
            private static final long serialVersionUID = 1L;

            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > maxEntries;
            }
        };
    }

    private MaterialPreview() {
    }

    static synchronized BufferedImage highlighted(HomePieceOfFurniture piece, String materialName, int size) {
        List<Object> key = Arrays.<Object>asList("H", MaterialUtils.modelKey(piece), materialName, size,
                Arrays.deepToString(piece.getModelRotation()), piece.isModelMirrored(),
                piece.getWidth(), piece.getDepth(), piece.getHeight());
        if (IMAGES.containsKey(key)) {
            return IMAGES.get(key);
        }
        Mesh mesh = mesh(piece);
        BufferedImage image = mesh != null ? draw(mesh, piece, materialName, size) : null;
        IMAGES.put(key, image);
        return image;
    }

    private static final class Mesh {
        float[] xyz = new float[9 * 256];
        String[] materials = new String[256];
        int count;

        void add(float[] corners, String material) {
            if (count == materials.length) {
                xyz = Arrays.copyOf(xyz, xyz.length * 2);
                materials = Arrays.copyOf(materials, materials.length * 2);
            }
            System.arraycopy(corners, 0, xyz, count * 9, 9);
            materials[count++] = material;
        }
    }

    private static Mesh mesh(HomePieceOfFurniture piece) {
        Content model = piece.getModel();
        if (model == null) {
            return null;
        }
        Object key = MaterialUtils.modelKey(piece);
        if (MESHES.containsKey(key)) {
            return MESHES.get(key);
        }
        Mesh mesh = null;
        try {
            BranchGroup root = ModelManager.getInstance().loadModel(model);
            if (root != null) {
                mesh = new Mesh();
                collect(root, new Transform3D(), mesh);
                if (mesh.count == 0) {
                    mesh = null;
                }
            }
        } catch (IOException | RuntimeException | LinkageError ex) {
            System.err.println("SurroundRoomPlugin: can't draw the model of " + piece.getName() + ": " + ex);
            mesh = null;
        }
        MESHES.put(key, mesh);
        return mesh;
    }

    private static void collect(Node node, Transform3D parentTransform, Mesh mesh) {
        if (node instanceof Group) {
            Transform3D transform = parentTransform;
            if (node instanceof TransformGroup) {
                Transform3D nodeTransform = new Transform3D();
                ((TransformGroup) node).getTransform(nodeTransform);
                transform = new Transform3D(parentTransform);
                transform.mul(nodeTransform);
            }
            Group group = (Group) node;
            for (int i = 0; i < group.numChildren(); i++) {
                collect(group.getChild(i), transform, mesh);
            }
        } else if (node instanceof Link) {
            SharedGroup sharedGroup = ((Link) node).getSharedGroup();
            if (sharedGroup != null) {
                collect(sharedGroup, parentTransform, mesh);
            }
        } else if (node instanceof Shape3D) {
            Shape3D shape = (Shape3D) node;
            Appearance appearance = shape.getAppearance();
            String material = appearance != null ? appearance.getName() : null;
            for (int i = 0; i < shape.numGeometries(); i++) {
                Geometry geometry = shape.getGeometry(i);
                if (geometry instanceof GeometryArray) {
                    addGeometry((GeometryArray) geometry, parentTransform, material, mesh);
                }
            }
        }
    }

    private static void addGeometry(GeometryArray geometry, Transform3D transform, String material, Mesh mesh) {
        int format = geometry.getVertexFormat();
        int vertexCount = geometry.getVertexCount();
        if (vertexCount <= 0 || (format & GeometryArray.USE_NIO_BUFFER) != 0) {
            return;
        }
        float[] coords = new float[vertexCount * 3];
        if ((format & GeometryArray.BY_REFERENCE) != 0) {
            if ((format & GeometryArray.INTERLEAVED) != 0) {
                // In Java3D interleaved format, XYZ coordinates occupy the last 3 floats of each vertex stride
                float[] data = geometry.getInterleavedVertices();
                if (data == null || data.length / vertexCount < 3) {
                    return;
                }
                int stride = data.length / vertexCount;
                for (int i = 0; i < vertexCount; i++) {
                    System.arraycopy(data, i * stride + stride - 3, coords, i * 3, 3);
                }
            } else {
                float[] floatCoords = geometry.getCoordRefFloat();
                double[] doubleCoords = floatCoords == null ? geometry.getCoordRefDouble() : null;
                if (floatCoords == null && doubleCoords == null) {
                    return;
                }
                for (int i = 0; i < coords.length; i++) {
                    coords[i] = floatCoords != null
                            ? (i < floatCoords.length ? floatCoords[i] : 0)
                            : (i < doubleCoords.length ? (float) doubleCoords[i] : 0);
                }
            }
        } else {
            geometry.getCoordinates(0, coords);
        }

        Point3f point = new Point3f();
        for (int i = 0; i < vertexCount; i++) {
            point.set(coords[i * 3], coords[i * 3 + 1], coords[i * 3 + 2]);
            transform.transform(point);
            coords[i * 3] = point.x;
            coords[i * 3 + 1] = point.y;
            coords[i * 3 + 2] = point.z;
        }

        int[] coordIndices = null;
        int cornerCount;
        if (geometry instanceof IndexedGeometryArray) {
            IndexedGeometryArray indexed = (IndexedGeometryArray) geometry;
            cornerCount = indexed.getIndexCount();
            coordIndices = new int[cornerCount];
            indexed.getCoordinateIndices(0, coordIndices);
        } else {
            cornerCount = geometry.getValidVertexCount();
        }

        Triangles triangles = new Triangles(coords, coordIndices, material, mesh);
        if (geometry instanceof TriangleArray || geometry instanceof IndexedTriangleArray) {
            for (int k = 0; k + 2 < cornerCount; k += 3) {
                triangles.add(k, k + 1, k + 2);
            }
        } else if (geometry instanceof QuadArray || geometry instanceof IndexedQuadArray) {
            for (int k = 0; k + 3 < cornerCount; k += 4) {
                triangles.add(k, k + 1, k + 2);
                triangles.add(k, k + 2, k + 3);
            }
        } else if (geometry instanceof IndexedGeometryStripArray || geometry instanceof GeometryStripArray) {
            boolean indexed = geometry instanceof IndexedGeometryStripArray;
            int[] stripCounts = new int[indexed ? ((IndexedGeometryStripArray) geometry).getNumStrips()
                    : ((GeometryStripArray) geometry).getNumStrips()];
            if (indexed) {
                ((IndexedGeometryStripArray) geometry).getStripIndexCounts(stripCounts);
            } else {
                ((GeometryStripArray) geometry).getStripVertexCounts(stripCounts);
            }
            boolean fan = geometry instanceof TriangleFanArray || geometry instanceof IndexedTriangleFanArray;
            boolean strip = geometry instanceof TriangleStripArray || geometry instanceof IndexedTriangleStripArray;
            int start = 0;
            for (int stripCount : stripCounts) {
                for (int j = 0; j + 2 < stripCount && start + j + 2 < cornerCount; j++) {
                    if (fan) {
                        triangles.add(start, start + j + 1, start + j + 2);
                    } else if (strip) {
                        if (j % 2 == 0) {
                            triangles.add(start + j, start + j + 1, start + j + 2);
                        } else {
                            triangles.add(start + j + 1, start + j, start + j + 2);
                        }
                    }
                }
                start += stripCount;
            }
        }
    }

    private static final class Triangles {
        private final float[] coords;
        private final int[] coordIndices;
        private final String material;
        private final Mesh mesh;
        private final float[] corners = new float[9];

        Triangles(float[] coords, int[] coordIndices, String material, Mesh mesh) {
            this.coords = coords;
            this.coordIndices = coordIndices;
            this.material = material;
            this.mesh = mesh;
        }

        void add(int k0, int k1, int k2) {
            int[] corner = {k0, k1, k2};
            for (int c = 0; c < 3; c++) {
                int vertex = coordIndices != null ? coordIndices[corner[c]] : corner[c];
                if (vertex < 0 || vertex * 3 + 2 >= coords.length) {
                    return;
                }
                System.arraycopy(coords, vertex * 3, corners, c * 3, 3);
            }
            mesh.add(corners, material);
        }
    }

    private static BufferedImage draw(Mesh mesh, HomePieceOfFurniture piece, String materialName, int size) {
        boolean wholePiece = MaterialUtils.WHOLE_PIECE.equals(materialName);
        boolean[] inPart = new boolean[mesh.count];
        boolean anyInPart = false;
        for (int i = 0; i < mesh.count; i++) {
            inPart[i] = wholePiece || materialName != null && materialName.equals(mesh.materials[i]);
            anyInPart |= inPart[i];
        }
        if (!anyInPart) {
            return null;
        }

        float[] points = transformPoints(mesh, piece);
        double cosYaw = Math.cos(YAW), sinYaw = Math.sin(YAW);
        double cosPitch = Math.cos(PITCH), sinPitch = Math.sin(PITCH);
        float[] view = new float[points.length];
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (int i = 0; i < mesh.count * 3; i++) {
            double x = points[i * 3], y = points[i * 3 + 1], z = points[i * 3 + 2];
            double x1 = x * cosYaw + z * sinYaw;
            double z1 = -x * sinYaw + z * cosYaw;
            float vx = (float) x1;
            float vy = (float) (y * cosPitch - z1 * sinPitch);
            view[i * 3] = vx;
            view[i * 3 + 1] = vy;
            view[i * 3 + 2] = (float) (y * sinPitch + z1 * cosPitch);
            minX = Math.min(minX, vx); maxX = Math.max(maxX, vx);
            minY = Math.min(minY, vy); maxY = Math.max(maxY, vy);
        }

        int renderSize = size * 2;
        float extent = Math.max(maxX - minX, maxY - minY);
        float pixelsPerCm = extent > 0 ? (renderSize - 4) / extent : 1;
        float offsetX = renderSize / 2f - (minX + maxX) / 2 * pixelsPerCm;
        float offsetY = renderSize / 2f + (minY + maxY) / 2 * pixelsPerCm;

        int[] pixels = new int[renderSize * renderSize], otherPixels = new int[renderSize * renderSize];
        float[] depth = new float[pixels.length], otherDepth = new float[pixels.length];
        Arrays.fill(depth, -Float.MAX_VALUE);
        Arrays.fill(otherDepth, -Float.MAX_VALUE);

        float[] sx = new float[3], sy = new float[3], sz = new float[3];
        for (int t = 0; t < mesh.count; t++) {
            for (int c = 0; c < 3; c++) {
                int i = (t * 3 + c) * 3;
                sx[c] = offsetX + view[i] * pixelsPerCm;
                sy[c] = offsetY - view[i + 1] * pixelsPerCm;
                sz[c] = view[i + 2];
            }
            double[] normal = faceNormal(view, t * 9);
            double light = 0.42 + 0.58 * Math.abs(normal[0] * LIGHT[0] + normal[1] * LIGHT[1] + normal[2] * LIGHT[2]);
            boolean other = !inPart[t];
            fillTriangle(other ? otherPixels : pixels, other ? otherDepth : depth, renderSize, sx, sy, sz,
                    other ? OTHER_PARTS : HIGHLIGHT, light);
        }

        for (int i = 0; i < pixels.length; i++) {
            boolean part = depth[i] > -Float.MAX_VALUE, other = otherDepth[i] > -Float.MAX_VALUE;
            if (part && other && otherDepth[i] > depth[i]) {
                pixels[i] = blend(otherPixels[i], pixels[i], 0.35); // X-ray reveal through occluding parts
            } else if (!part && other) {
                pixels[i] = otherPixels[i];
            }
        }
        return downscale(pixels, renderSize, size);
    }

    private static float[] transformPoints(Mesh mesh, HomePieceOfFurniture piece) {
        float[][] rotation = piece.getModelRotation();
        float[] points = new float[mesh.count * 9];
        float[] min = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
        float[] max = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (int i = 0; i < mesh.count * 3; i++) {
            float x = mesh.xyz[i * 3], y = mesh.xyz[i * 3 + 1], z = mesh.xyz[i * 3 + 2];
            for (int axis = 0; axis < 3; axis++) {
                float v = rotation != null
                        ? rotation[axis][0] * x + rotation[axis][1] * y + rotation[axis][2] * z
                        : axis == 0 ? x : axis == 1 ? y : z;
                points[i * 3 + axis] = v;
                min[axis] = Math.min(min[axis], v);
                max[axis] = Math.max(max[axis], v);
            }
        }
        float[] scale = {
            scaleOf(piece.getWidth(), max[0] - min[0]),
            scaleOf(piece.getHeight(), max[1] - min[1]),
            scaleOf(piece.getDepth(), max[2] - min[2])
        };
        for (int i = 0; i < mesh.count * 3; i++) {
            for (int axis = 0; axis < 3; axis++) {
                points[i * 3 + axis] = (points[i * 3 + axis] - (min[axis] + max[axis]) / 2) * scale[axis];
            }
            if (piece.isModelMirrored()) {
                points[i * 3] = -points[i * 3];
            }
        }
        return points;
    }

    private static double[] faceNormal(float[] a, int base) {
        return normalize(
                (a[base + 4] - a[base + 1]) * (a[base + 8] - a[base + 2])
                - (a[base + 5] - a[base + 2]) * (a[base + 7] - a[base + 1]),
                (a[base + 5] - a[base + 2]) * (a[base + 6] - a[base])
                - (a[base + 3] - a[base]) * (a[base + 8] - a[base + 2]),
                (a[base + 3] - a[base]) * (a[base + 7] - a[base + 1])
                - (a[base + 4] - a[base + 1]) * (a[base + 6] - a[base]));
    }

    private static BufferedImage downscale(int[] pixels, int renderSize, int size) {
        BufferedImage large = new BufferedImage(renderSize, renderSize, BufferedImage.TYPE_INT_ARGB);
        large.setRGB(0, 0, renderSize, renderSize, pixels, 0, renderSize);
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(large, 0, 0, size, size, null);
        g.dispose();
        return image;
    }

    private static float scaleOf(float pieceSize, float modelSize) {
        return modelSize > 1E-6f && pieceSize > 0 ? pieceSize / modelSize : 1;
    }

    private static void fillTriangle(int[] pixels, float[] depth, int size, float[] x, float[] y, float[] z,
            int color, double light) {
        float area = (x[1] - x[0]) * (y[2] - y[0]) - (x[2] - x[0]) * (y[1] - y[0]);
        if (Math.abs(area) < 1E-6f) {
            return;
        }
        int minX = Math.max(0, (int) Math.floor(Math.min(x[0], Math.min(x[1], x[2]))));
        int maxX = Math.min(size - 1, (int) Math.ceil(Math.max(x[0], Math.max(x[1], x[2]))));
        int minY = Math.max(0, (int) Math.floor(Math.min(y[0], Math.min(y[1], y[2]))));
        int maxY = Math.min(size - 1, (int) Math.ceil(Math.max(y[0], Math.max(y[1], y[2]))));
        int shaded = shade(color, light);
        for (int py = minY; py <= maxY; py++) {
            float cy = py + 0.5f;
            for (int px = minX; px <= maxX; px++) {
                float cx = px + 0.5f;
                float w0 = ((x[2] - x[1]) * (cy - y[1]) - (y[2] - y[1]) * (cx - x[1])) / area;
                float w1 = ((x[0] - x[2]) * (cy - y[2]) - (y[0] - y[2]) * (cx - x[2])) / area;
                float w2 = 1 - w0 - w1;
                if (w0 < 0 || w1 < 0 || w2 < 0) {
                    continue;
                }
                float pz = w0 * z[0] + w1 * z[1] + w2 * z[2];
                int index = py * size + px;
                if (pz > depth[index]) {
                    depth[index] = pz;
                    pixels[index] = shaded;
                }
            }
        }
    }

    private static int blend(int front, int back, double alpha) {
        int r = (int) (((front >> 16) & 0xFF) * alpha + ((back >> 16) & 0xFF) * (1 - alpha));
        int g = (int) (((front >> 8) & 0xFF) * alpha + ((back >> 8) & 0xFF) * (1 - alpha));
        int b = (int) ((front & 0xFF) * alpha + (back & 0xFF) * (1 - alpha));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static int shade(int rgb, double light) {
        int r = (int) Math.min(255, ((rgb >> 16) & 0xFF) * light);
        int g = (int) Math.min(255, ((rgb >> 8) & 0xFF) * light);
        int b = (int) Math.min(255, (rgb & 0xFF) * light);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static double[] normalize(double x, double y, double z) {
        double length = Math.sqrt(x * x + y * y + z * z);
        return length > 0 ? new double[]{x / length, y / length, z / length} : new double[]{0, 0, 1};
    }
}
