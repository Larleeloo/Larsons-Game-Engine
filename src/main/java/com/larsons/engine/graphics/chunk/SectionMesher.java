package com.larsons.engine.graphics.chunk;

import com.larsons.engine.level.Level;
import com.larsons.engine.world.Block;

import java.awt.Color;

/**
 * Turns one 16&sup3; section of world into triangles — <b>the chunk build</b>,
 * and the piece of the GPU path that Minecraft's shape is copied from most
 * closely.
 *
 * <h2>What it emits, and why in that shape</h2>
 *
 * <p><b>One quad per exposed face, not a merged mesh.</b> Greedy meshing turns
 * a wall into one big quad and would cut the triangle count several-fold, and
 * every voxel renderer that wants per-vertex lighting gives it up anyway —
 * because a merged quad has four corners where the ground under it has forty,
 * and ambient occlusion lives on the corners. Minecraft emits per-face quads
 * for the same reason. The far field is where merging belongs, and that is
 * where this engine does it ({@code WorldLod}).
 *
 * <p><b>Shading is baked into the vertex colour.</b> The classic four levels —
 * top brightest, north/south next, east/west darker, the underside darkest —
 * multiplied by that vertex's ambient occlusion. No lights, no normals, no
 * second pass: a cube reads as a cube because its faces differ, and a corner
 * reads as a corner because the vertices where three blocks meet are darker.
 *
 * <p><b>Ambient occlusion is Minecraft's "smooth lighting", including the flip.</b>
 * Each corner of a face looks at the three cells that touch it on the outward
 * side and counts how many are solid; two solids either side of a corner mean
 * the corner is fully occluded whatever the diagonal says. The quad is then
 * split into triangles along whichever diagonal keeps the two darkest corners
 * apart — without that, a face with occlusion on one corner shows a hard seam
 * across itself, which is the single most recognisable artefact of getting this
 * wrong.
 *
 * <h2>What it does not do</h2>
 *
 * <p>Plants, actors and the level's decor objects are not here. They are
 * billboards that turn with the camera, so they belong to the pass that knows
 * where the camera is rather than to geometry built once and kept. The solid
 * painter still draws them, over the top of this, into the same depth buffer.
 */
public final class SectionMesher {

    /** The four brightnesses, matching {@code SolidPainter}'s exactly. */
    private static final double SHADE_TOP = 1.0;
    private static final double SHADE_NORTH_SOUTH = 0.80;
    private static final double SHADE_EAST_WEST = 0.62;
    private static final double SHADE_BOTTOM = 0.42;

    /**
     * How dark a fully-occluded corner is, and how much light the three steps
     * up to an open one add.
     *
     * <p>Ambient occlusion is a lie about light and the only question is how
     * big a one. Under half is a black smear in every inside corner; over
     * four-fifths is a mesh that cost four brightness levels per vertex to look
     * flat. These are close to Minecraft's own and were chosen to read the same
     * against this engine's four face shades.
     */
    private static final double AO_DARKEST = 0.45;
    private static final double AO_STEP = (1.0 - AO_DARKEST) / 3;

    private SectionMesher() {}

    /**
     * Build the section whose corner is at
     * {@code (sx*16, sz*16, sy*16 + 1)} in the level's own coordinates.
     *
     * <p>The height index is a <em>box</em> index rather than a layer: layer
     * {@code L} of a level occupies the box {@code L − 1}, because layer 0 is
     * the floor surface and has no thickness. A section's boxes are
     * {@code [sy*16, sy*16 + 16)}.
     *
     * <p>Safe to call from any thread that is not writing to the level, which
     * is what makes this a background job.
     */
    public static SectionMesh build(Level level, BlockAtlas atlas, int sx, int sy, int sz) {
        int size = SectionMesh.SIZE;
        int ts = Math.max(1, level.tileSize);
        int baseCol = sx * size, baseRow = sz * size, baseBox = sy * size;

        // A padded copy of the section and its shell, read once. Every question
        // below — is this face exposed, how occluded is this corner — is about
        // a cell and its twenty-six neighbours, and reading them from the level
        // one at a time would be twenty-seven run scans per cell instead of one.
        int pad = size + 2;
        int[] ids = new int[pad * pad * pad];
        boolean[] solid = new boolean[pad * pad * pad];
        boolean anything = false;
        for (int bz = -1; bz <= size; bz++) {
            int layer = baseBox + bz + 1;
            for (int by = -1; by <= size; by++) {
                int row = baseRow + by;
                for (int bx = -1; bx <= size; bx++) {
                    int col = baseCol + bx;
                    int id = layer < 0 ? 0 : level.tileAt(col, row, layer);
                    int at = index(bx + 1, by + 1, bz + 1, pad);
                    ids[at] = id;
                    boolean covers = id > 0 && level.blocks.covers(id);
                    solid[at] = covers;
                    if (id > 0 && bx >= 0 && bx < size && by >= 0 && by < size
                            && bz >= 0 && bz < size) {
                        anything = true;
                    }
                }
            }
        }

        boolean[] inner = new boolean[size * size * size];
        for (int z = 0; z < size; z++) {
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    inner[(z * size + y) * size + x] = solid[index(x + 1, y + 1, z + 1, pad)];
                }
            }
        }
        SectionVisibility visibility = SectionVisibility.of(inner);
        if (!anything) return SectionMesh.empty(sx, sy, sz);

        Buffer opaque = new Buffer();
        Buffer translucent = new Buffer();
        float[] uv = new float[4];

        for (int bz = 0; bz < size; bz++) {
            int layer = baseBox + bz + 1;
            for (int by = 0; by < size; by++) {
                for (int bx = 0; bx < size; bx++) {
                    int at = index(bx + 1, by + 1, bz + 1, pad);
                    int id = ids[at];
                    if (id <= 0) continue;
                    Block block = level.blocks.get(id);
                    // A plant is a stem and a billboard, not a cube; the
                    // painter draws it, over the top, into this depth buffer.
                    if (block != null && block.plant()) continue;
                    Color colour = level.colorFor(id);
                    if (colour == null) continue;
                    boolean clear = colour.getAlpha() < 255;
                    Buffer into = clear ? translucent : opaque;

                    double x0 = (baseCol + bx) * (double) ts, x1 = x0 + ts;
                    double y0 = (baseRow + by) * (double) ts, y1 = y0 + ts;
                    double z0 = (baseBox + bz) * (double) ts, z1 = z0 + ts;
                    // Relative to the section's corner: see SectionMesh on why
                    // the absolute position never reaches a float.
                    double ox = baseCol * (double) ts;
                    double oy = baseRow * (double) ts;
                    double oz = baseBox * (double) ts;
                    float fx0 = (float) (x0 - ox), fx1 = (float) (x1 - ox);
                    float fy0 = (float) (y0 - oy), fy1 = (float) (y1 - oy);
                    float fz0 = (float) (z0 - oz), fz1 = (float) (z1 - oz);

                    for (int face = 0; face < SectionVisibility.FACES; face++) {
                        if (!exposed(ids, solid, level, pad, bx, by, bz, face, id)) continue;
                        if (face == SectionVisibility.DOWN && layer <= 1) continue;
                        boolean top = face == SectionVisibility.UP
                                || face == SectionVisibility.DOWN;
                        int sheet = top ? BlockAtlas.FACE_TOP : BlockAtlas.FACE_SIDE;
                        atlas.uv(id, sheet, uv);
                        // A dressed face is tinted white, because its sheet
                        // already carries the block's colour and multiplying by
                        // it again would darken every textured block twice. Its
                        // alpha still comes from the block, so water stays
                        // water. An undressed face samples the atlas's white
                        // cell and is tinted by the block itself.
                        Color tintBase = atlas.hasSheet(id, sheet)
                                ? new Color(255, 255, 255, colour.getAlpha())
                                : colour;
                        quad(into, tintBase, shadeOf(face), uv,
                                fx0, fy0, fz0, fx1, fy1, fz1, face,
                                ao(solid, pad, bx, by, bz, face));
                    }
                }
            }
        }
        return new SectionMesh(sx, sy, sz,
                opaque.vertices(), opaque.colours(),
                translucent.vertices(), translucent.colours(), visibility);
    }

    private static int index(int x, int y, int z, int pad) {
        return (z * pad + y) * pad + x;
    }

    private static double shadeOf(int face) {
        return switch (face) {
            case SectionVisibility.UP -> SHADE_TOP;
            case SectionVisibility.DOWN -> SHADE_BOTTOM;
            case SectionVisibility.NORTH, SectionVisibility.SOUTH -> SHADE_NORTH_SOUTH;
            default -> SHADE_EAST_WEST;
        };
    }

    /**
     * Whether a face is worth emitting: its neighbour neither covers it nor is
     * the same block.
     *
     * <p>The same rule the CPU painter follows, which is what keeps the two
     * backends drawing the same world — a flower and a pane of glass leave the
     * face behind them visible, and water against water has no surface between
     * it however see-through it is.
     */
    private static boolean exposed(int[] ids, boolean[] solid, Level level, int pad,
                                   int bx, int by, int bz, int face, int selfId) {
        int nx = bx + 1 + SectionVisibility.step(face, 0);
        int ny = by + 1 + SectionVisibility.step(face, 1);
        int nz = bz + 1 + SectionVisibility.step(face, 2);
        int at = index(nx, ny, nz, pad);
        int id = ids[at];
        if (id == selfId && id > 0) return false;
        return !solid[at];
    }

    /**
     * The four corners' ambient occlusion, {@code 0..3} each, in the corner
     * order {@link #quad} lays a face out in.
     *
     * <p>For each corner, the three cells that touch it on the far side of the
     * face: the two edge neighbours and the diagonal. Two solid edges shut the
     * corner whatever the diagonal is doing, which is the case a plain count
     * would get wrong — it is a corner you genuinely cannot see light through.
     */
    private static int[] ao(boolean[] solid, int pad, int bx, int by, int bz, int face) {
        // The face's own outward step, and the two axes that run across it.
        int ox = SectionVisibility.step(face, 0);
        int oy = SectionVisibility.step(face, 1);
        int oz = SectionVisibility.step(face, 2);
        int[] u = new int[3], v = new int[3];
        across(face, u, v);
        int[] out = new int[4];
        // Corner order matches quad(): (−u,−v), (+u,−v), (+u,+v), (−u,+v).
        int[][] signs = {{-1, -1}, {1, -1}, {1, 1}, {-1, 1}};
        for (int c = 0; c < 4; c++) {
            int su = signs[c][0], sv = signs[c][1];
            boolean side1 = at(solid, pad, bx + ox + u[0] * su, by + oy + u[1] * su,
                    bz + oz + u[2] * su);
            boolean side2 = at(solid, pad, bx + ox + v[0] * sv, by + oy + v[1] * sv,
                    bz + oz + v[2] * sv);
            boolean corner = at(solid, pad, bx + ox + u[0] * su + v[0] * sv,
                    by + oy + u[1] * su + v[1] * sv, bz + oz + u[2] * su + v[2] * sv);
            out[c] = side1 && side2 ? 0 : 3 - ((side1 ? 1 : 0) + (side2 ? 1 : 0)
                    + (corner ? 1 : 0));
        }
        return out;
    }

    private static boolean at(boolean[] solid, int pad, int bx, int by, int bz) {
        int x = bx + 1, y = by + 1, z = bz + 1;
        if (x < 0 || y < 0 || z < 0 || x >= pad || y >= pad || z >= pad) return false;
        return solid[index(x, y, z, pad)];
    }

    /** The two axes that run across a face, as unit steps. */
    private static void across(int face, int[] u, int[] v) {
        switch (face) {
            case SectionVisibility.UP, SectionVisibility.DOWN -> {
                u[0] = 1; u[1] = 0; u[2] = 0;
                v[0] = 0; v[1] = 1; v[2] = 0;
            }
            case SectionVisibility.NORTH, SectionVisibility.SOUTH -> {
                u[0] = 1; u[1] = 0; u[2] = 0;
                v[0] = 0; v[1] = 0; v[2] = 1;
            }
            default -> {
                u[0] = 0; u[1] = 1; u[2] = 0;
                v[0] = 0; v[1] = 0; v[2] = 1;
            }
        }
    }

    /**
     * Append one face as two triangles, wound counter-clockwise seen from
     * outside so the GPU's back-face cull keeps it.
     *
     * <p>The diagonal is chosen by the ambient occlusion, which is the "flip"
     * every smooth-lit voxel renderer needs: split a quad the wrong way and the
     * interpolation across each triangle disagrees along the shared edge, and a
     * face with one dark corner grows a visible crease from that corner to the
     * opposite one.
     */
    private static void quad(Buffer into, Color colour, double shade, float[] uv,
                             float x0, float y0, float z0, float x1, float y1, float z1,
                             int face, int[] ao) {
        float[] px = new float[4], py = new float[4], pz = new float[4];
        switch (face) {
            case SectionVisibility.UP -> {
                set(px, py, pz, 0, x0, y0, z1); set(px, py, pz, 1, x1, y0, z1);
                set(px, py, pz, 2, x1, y1, z1); set(px, py, pz, 3, x0, y1, z1);
            }
            case SectionVisibility.DOWN -> {
                set(px, py, pz, 0, x0, y0, z0); set(px, py, pz, 1, x0, y1, z0);
                set(px, py, pz, 2, x1, y1, z0); set(px, py, pz, 3, x1, y0, z0);
            }
            case SectionVisibility.NORTH -> {
                set(px, py, pz, 0, x0, y0, z0); set(px, py, pz, 1, x1, y0, z0);
                set(px, py, pz, 2, x1, y0, z1); set(px, py, pz, 3, x0, y0, z1);
            }
            case SectionVisibility.SOUTH -> {
                set(px, py, pz, 0, x1, y1, z0); set(px, py, pz, 1, x0, y1, z0);
                set(px, py, pz, 2, x0, y1, z1); set(px, py, pz, 3, x1, y1, z1);
            }
            case SectionVisibility.EAST -> {
                set(px, py, pz, 0, x1, y0, z0); set(px, py, pz, 1, x1, y1, z0);
                set(px, py, pz, 2, x1, y1, z1); set(px, py, pz, 3, x1, y0, z1);
            }
            default -> {
                set(px, py, pz, 0, x0, y1, z0); set(px, py, pz, 1, x0, y0, z0);
                set(px, py, pz, 2, x0, y0, z1); set(px, py, pz, 3, x0, y1, z1);
            }
        }
        float[] cu = {uv[0], uv[2], uv[2], uv[0]};
        float[] cv = {uv[3], uv[3], uv[1], uv[1]};
        int[] argb = new int[4];
        for (int i = 0; i < 4; i++) {
            argb[i] = tint(colour, shade * (AO_DARKEST + AO_STEP * ao[i]));
        }
        // Split along whichever diagonal keeps the two darkest corners apart.
        boolean flip = ao[0] + ao[2] < ao[1] + ao[3];
        int[] order = flip ? new int[] {1, 2, 3, 1, 3, 0} : new int[] {0, 1, 2, 0, 2, 3};
        for (int i : order) into.add(px[i], py[i], pz[i], cu[i], cv[i], argb[i]);
    }

    private static void set(float[] px, float[] py, float[] pz, int at,
                            float x, float y, float z) {
        px[at] = x;
        py[at] = y;
        pz[at] = z;
    }

    private static int tint(Color colour, double by) {
        int r = clamp((int) Math.round(colour.getRed() * by));
        int g = clamp((int) Math.round(colour.getGreen() * by));
        int b = clamp((int) Math.round(colour.getBlue() * by));
        return (colour.getAlpha() << 24) | (r << 16) | (g << 8) | b;
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : Math.min(v, 255);
    }

    /** A growing pair of arrays: five floats and a colour per vertex. */
    private static final class Buffer {
        private float[] data = new float[SectionMesh.FLOATS_PER_VERTEX * 256];
        private int[] argb = new int[256];
        private int count;

        void add(float x, float y, float z, float u, float v, int colour) {
            if (count == argb.length) {
                data = java.util.Arrays.copyOf(data, data.length * 2);
                argb = java.util.Arrays.copyOf(argb, argb.length * 2);
            }
            int at = count * SectionMesh.FLOATS_PER_VERTEX;
            data[at] = x;
            data[at + 1] = y;
            data[at + 2] = z;
            data[at + 3] = u;
            data[at + 4] = v;
            argb[count] = colour;
            count++;
        }

        float[] vertices() {
            return java.util.Arrays.copyOf(data, count * SectionMesh.FLOATS_PER_VERTEX);
        }

        int[] colours() {
            return java.util.Arrays.copyOf(argb, count);
        }
    }
}
