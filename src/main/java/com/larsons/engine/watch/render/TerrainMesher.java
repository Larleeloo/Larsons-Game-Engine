package com.larsons.engine.watch.render;

import com.larsons.engine.watch.world.TerrainField;
import com.larsons.engine.watch.world.WatchChunk;
import com.larsons.engine.watch.world.WatchMaterial;
import com.larsons.engine.watch.world.WatchMaterials;

/**
 * A chunk's heightfield, turned into the triangles that draw it.
 *
 * <h2>Flat shading is the whole look</h2>
 *
 * <p>Each quad of the grid becomes two triangles, and <b>each triangle gets one
 * colour, computed from its own normal</b>. That is what "low poly" means as a
 * rendering decision rather than a polygon count: a hillside is a mosaic of
 * facets that each catch the light differently, and the facets are what the eye
 * reads the shape from. Smooth (per-vertex) normals over the same geometry would
 * look like a low-resolution version of a realistic hill; flat normals look like
 * a deliberate one.
 *
 * <h2>Why the light is baked in and the time of day is not</h2>
 *
 * <p>The vertex format carries no normal — it is the block renderer's format,
 * three positions, two texture coordinates and a colour — so a shader could not
 * light these triangles even if one wanted to. The face's shade is therefore
 * multiplied into its colour here, against a <b>fixed key direction</b>, and
 * what the renderer applies per frame is the sky's own colour and the fog.
 *
 * <p>That is the same arrangement Minecraft uses (a face's brightness is baked;
 * the sky light is global) and it is what makes a day/night cycle affordable: a
 * sun that moved the baked shade would invalidate every mesh in the world every
 * few seconds. The cost is that a face pointing east is not lit differently at
 * dawn than at dusk, which nobody has ever noticed in a game that shades this
 * way.
 */
public final class TerrainMesher {

    /**
     * The direction the key light comes from — high, and off to the north-west,
     * so the two horizontal axes shade differently and a ridge running either
     * way reads as a ridge. Normalised.
     */
    private static final double KEY_X = -0.40, KEY_Y = -0.33, KEY_Z = 0.85;

    /** The share of a face's colour that does not depend on the light at all. */
    private static final double AMBIENT = 0.58;

    private TerrainMesher() {}

    /**
     * Build the ground mesh for a chunk at a level of detail.
     *
     * @param lod {@code 0} for every sample, up to {@link WatchChunk#MAX_LOD}
     */
    public static Mesh ground(WatchChunk chunk, int lod) {
        int stride = 1 << Math.max(0, Math.min(WatchChunk.MAX_LOD, lod));
        int n = WatchChunk.SAMPLES;
        double ox = chunk.originX(), oy = chunk.originY();
        Mesh.Builder mesh = Mesh.builder(ox, oy, 0, false, chunk.meshRevision());
        float[] uv = new float[4];

        for (int iy = 0; iy + stride < n; iy += stride) {
            for (int ix = 0; ix + stride < n; ix += stride) {
                int jx = ix + stride, jy = iy + stride;
                float ax = ix * WatchChunk.STEP, ay = iy * WatchChunk.STEP;
                float bx = jx * WatchChunk.STEP, by = jy * WatchChunk.STEP;
                float h00 = chunk.heightAt(ix, iy);
                float h10 = chunk.heightAt(jx, iy);
                float h11 = chunk.heightAt(jx, jy);
                float h01 = chunk.heightAt(ix, jy);

                // The material of the quad is the one at its own middle, so a
                // border between grass and rock lands on a triangle edge rather
                // than being averaged into a colour that is neither.
                WatchMaterial material = chunk.surfaceAt(
                        Math.min(n - 1, ix + stride / 2), Math.min(n - 1, iy + stride / 2));
                WatchMaterials.uv(material, uv);
                int albedo = WatchMaterials.shade(material);

                // Split along whichever diagonal is flatter. A saddle quad cut
                // the wrong way puts a fold across it that is not in the data —
                // and on a two-metre grid that fold is a metre-high wall.
                boolean mainDiagonal = Math.abs(h00 - h11) <= Math.abs(h10 - h01);
                if (mainDiagonal) {
                    emit(mesh, ax, ay, h00, bx, ay, h10, bx, by, h11, uv, albedo, 0);
                    emit(mesh, ax, ay, h00, bx, by, h11, ax, by, h01, uv, albedo, 1);
                } else {
                    emit(mesh, ax, ay, h00, bx, ay, h10, ax, by, h01, uv, albedo, 2);
                    emit(mesh, bx, ay, h10, bx, by, h11, ax, by, h01, uv, albedo, 3);
                }
            }
        }
        return mesh.build();
    }

    /**
     * The still water over a chunk: one flat quad per grid square that has any
     * water in it, at the water line.
     *
     * <p>Drawn as its own translucent mesh rather than as part of the ground,
     * because you can see through it and blending is not commutative — the same
     * split into render layers the block renderer makes, for the same reason.
     */
    public static Mesh water(WatchChunk chunk, int lod) {
        if (!chunk.anyWater()) return Mesh.empty(chunk.originX(), chunk.originY(), 0);
        int stride = 1 << Math.max(0, Math.min(WatchChunk.MAX_LOD, lod));
        int n = WatchChunk.SAMPLES;
        double ox = chunk.originX(), oy = chunk.originY();
        Mesh.Builder mesh = Mesh.builder(ox, oy, 0, true, chunk.meshRevision());
        float[] uv = new float[4];
        WatchMaterials.uv(WatchMaterial.WATER, uv);
        float surface = (float) TerrainField.WATER_LEVEL;

        for (int iy = 0; iy + stride < n; iy += stride) {
            for (int ix = 0; ix + stride < n; ix += stride) {
                int jx = ix + stride, jy = iy + stride;
                boolean wet = chunk.waterAt(ix, iy) > 0 || chunk.waterAt(jx, iy) > 0
                        || chunk.waterAt(jx, jy) > 0 || chunk.waterAt(ix, jy) > 0;
                if (!wet) continue;
                // Deep water is darker and less transparent than the shallows,
                // which is the whole of how a lake reads as having a shape.
                double depth = (chunk.waterAt(ix, iy) + chunk.waterAt(jx, jy)) / 2;
                int argb = waterColour(depth);
                float ax = ix * WatchChunk.STEP, ay = iy * WatchChunk.STEP;
                float bx = jx * WatchChunk.STEP, by = jy * WatchChunk.STEP;
                mesh.quad(ax, ay, surface, bx, ay, surface, bx, by, surface,
                        ax, by, surface, uv, argb);
            }
        }
        return mesh.build();
    }

    /** A face's colour: the material's own, dimmed by how the key light hits it. */
    private static void emit(Mesh.Builder mesh,
                             float ax, float ay, float az,
                             float bx, float by, float bz,
                             float cx, float cy, float cz,
                             float[] uv, int albedo, int corner) {
        double ux = bx - ax, uy = by - ay, uz = bz - az;
        double vx = cx - ax, vy = cy - ay, vz = cz - az;
        double nx = uy * vz - uz * vy;
        double ny = uz * vx - ux * vz;
        double nz = ux * vy - uy * vx;
        double length = Math.sqrt(nx * nx + ny * ny + nz * nz);
        double lit = AMBIENT;
        if (length > 1e-9) {
            // The winding is chosen so the normal points up out of the ground;
            // taking the absolute value would light an overhang from below.
            double dot = (nx * KEY_X + ny * KEY_Y + nz * KEY_Z) / length;
            lit = AMBIENT + (1 - AMBIENT) * Math.max(0, dot);
        }
        int argb = shade(albedo, lit);
        // Each corner takes the tile corner nearest it, so the texture is
        // stretched across the quad rather than repeated on each half of it.
        float[] c = cornerUv(uv, corner);
        mesh.triangle(ax, ay, az, c[0], c[1],
                bx, by, bz, c[2], c[3],
                cx, cy, cz, c[4], c[5], argb);
    }

    /**
     * The three texture coordinates of one of the four triangles a quad can be
     * cut into, in the order {@link #emit} writes its vertices.
     */
    private static float[] cornerUv(float[] uv, int which) {
        float u0 = uv[0], v0 = uv[1], u1 = uv[2], v1 = uv[3];
        return switch (which) {
            case 0 -> new float[]{u0, v0, u1, v0, u1, v1};
            case 1 -> new float[]{u0, v0, u1, v1, u0, v1};
            case 2 -> new float[]{u0, v0, u1, v0, u0, v1};
            default -> new float[]{u1, v0, u1, v1, u0, v1};
        };
    }

    /** Water's colour and opacity at a depth: pale and clear, to dark and not. */
    private static int waterColour(double depth) {
        double t = Math.min(1, depth / 9);
        int shallow = WatchMaterials.shade(WatchMaterial.SHALLOWS);
        int deep = WatchMaterials.shade(WatchMaterial.WATER);
        int r = (int) (((shallow >> 16) & 0xFF) * (1 - t) + ((deep >> 16) & 0xFF) * t);
        int g = (int) (((shallow >> 8) & 0xFF) * (1 - t) + ((deep >> 8) & 0xFF) * t);
        int b = (int) ((shallow & 0xFF) * (1 - t) + (deep & 0xFF) * t);
        int alpha = (int) (128 + 92 * t);
        return (alpha << 24) | (r << 16) | (g << 8) | b;
    }

    /** {@code 0xRRGGBB} scaled by a factor, opaque. */
    public static int shade(int rgb, double factor) {
        int r = clamp(((rgb >> 16) & 0xFF) * factor);
        int g = clamp(((rgb >> 8) & 0xFF) * factor);
        int b = clamp((rgb & 0xFF) * factor);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /** {@code 0xRRGGBB} scaled per channel — what a genome's leaf tint does. */
    public static int tint(int rgb, double[] rgbScale, double factor) {
        int r = clamp(((rgb >> 16) & 0xFF) * rgbScale[0] * factor);
        int g = clamp(((rgb >> 8) & 0xFF) * rgbScale[1] * factor);
        int b = clamp((rgb & 0xFF) * rgbScale[2] * factor);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static int clamp(double v) {
        return (int) Math.max(0, Math.min(255, Math.round(v)));
    }
}
