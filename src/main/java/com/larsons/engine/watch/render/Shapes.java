package com.larsons.engine.watch.render;

/**
 * The handful of solids everything in this world is built out of, emitted as
 * flat-shaded triangles.
 *
 * <p><b>A handful of primitives, and no more.</b> A trunk is a tapered prism, a
 * crown is an octahedron or a stack of cones, a boulder is a squashed
 * octahedron, an animal is a pile of boxes, a limb or an oar is a box between
 * two points, and a blade of grass is a tapered quad. Every one of them is a
 * few triangles with one colour each, which is what keeps a forest inside a
 * polygon budget a software rasteriser can still draw — and what makes the
 * whole world look like it was made by the same hand.
 *
 * <p>Each face is shaded here, against the same fixed key direction
 * {@link TerrainMesher} uses, so a tree and the ground it stands on catch the
 * light the same way. The renderer applies the sky's colour and the fog per
 * frame on top; see that class for why the sun's own direction is not in this
 * calculation.
 */
public final class Shapes {

    private static final double KEY_X = -0.40, KEY_Y = -0.33, KEY_Z = 0.85;
    /**
     * The share of a face's colour that does not depend on the light.
     *
     * <p>Higher than a physically-minded renderer would use, deliberately. The
     * key light is a single fixed direction, so a face turned away from it gets
     * nothing else — and in a game whose whole verb is <em>identify that
     * animal</em>, a bird's shaded flank being four tenths of its own colour
     * makes it unidentifiable. Outdoors, in daylight, the sky is a light source
     * too; this is that, as one number.
     */
    private static final double AMBIENT = 0.64;

    private Shapes() {}

    /**
     * One triangle, lit by its own normal.
     *
     * <p>Wind the vertices counter-clockwise seen from outside the solid: the
     * normal comes out of that winding, and a face wound the other way is lit as
     * though the sun were underneath it.
     */
    public static void face(Mesh.Builder mesh,
                            double ax, double ay, double az,
                            double bx, double by, double bz,
                            double cx, double cy, double cz,
                            float[] uv, int albedo) {
        double ux = bx - ax, uy = by - ay, uz = bz - az;
        double vx = cx - ax, vy = cy - ay, vz = cz - az;
        double nx = uy * vz - uz * vy;
        double ny = uz * vx - ux * vz;
        double nz = ux * vy - uy * vx;
        double length = Math.sqrt(nx * nx + ny * ny + nz * nz);
        double lit = AMBIENT;
        if (length > 1e-9) {
            double dot = (nx * KEY_X + ny * KEY_Y + nz * KEY_Z) / length;
            lit = AMBIENT + (1 - AMBIENT) * Math.max(0, dot);
        }
        int argb = TerrainMesher.shade(albedo, lit);
        float u = (uv[0] + uv[2]) / 2, v = (uv[1] + uv[3]) / 2;
        mesh.triangle((float) ax, (float) ay, (float) az,
                (float) bx, (float) by, (float) bz,
                (float) cx, (float) cy, (float) cz, u, v, argb);
    }

    /** A four-sided face, as two triangles. Same winding rule as {@link #face}. */
    public static void quad(Mesh.Builder mesh,
                            double ax, double ay, double az,
                            double bx, double by, double bz,
                            double cx, double cy, double cz,
                            double dx, double dy, double dz,
                            float[] uv, int albedo) {
        face(mesh, ax, ay, az, bx, by, bz, cx, cy, cz, uv, albedo);
        face(mesh, ax, ay, az, cx, cy, cz, dx, dy, dz, uv, albedo);
    }

    /**
     * A tapered prism standing on its end — a trunk, a stem, a cane, a leg.
     *
     * @param sides    how many faces round it; 5 is enough to read as round and
     *                 cheap enough to put a forest of them on screen
     * @param capTop   whether to close the top; a trunk that a crown sits on
     *                 does not need one, a cactus does
     */
    public static void prism(Mesh.Builder mesh, double x, double y,
                             double z0, double z1, double r0, double r1,
                             int sides, double yaw, float[] uv, int albedo,
                             boolean capTop) {
        double step = Math.PI * 2 / sides;
        for (int i = 0; i < sides; i++) {
            double a0 = yaw + i * step, a1 = yaw + (i + 1) * step;
            double c0 = Math.cos(a0), s0 = Math.sin(a0);
            double c1 = Math.cos(a1), s1 = Math.sin(a1);
            // Outward winding: bottom-near, bottom-far, top-far, top-near.
            quad(mesh,
                    x + c0 * r0, y + s0 * r0, z0,
                    x + c1 * r0, y + s1 * r0, z0,
                    x + c1 * r1, y + s1 * r1, z1,
                    x + c0 * r1, y + s0 * r1, z1, uv, albedo);
        }
        if (capTop && r1 > 0.001) {
            for (int i = 0; i < sides; i++) {
                double a0 = yaw + i * step, a1 = yaw + (i + 1) * step;
                face(mesh, x, y, z1,
                        x + Math.cos(a0) * r1, y + Math.sin(a0) * r1, z1,
                        x + Math.cos(a1) * r1, y + Math.sin(a1) * r1, z1, uv, albedo);
            }
        }
    }

    /** A cone standing on its base — a conifer's tier, a cap, a beak. */
    public static void cone(Mesh.Builder mesh, double x, double y, double z0, double z1,
                            double radius, int sides, double yaw, float[] uv, int albedo) {
        double step = Math.PI * 2 / sides;
        for (int i = 0; i < sides; i++) {
            double a0 = yaw + i * step, a1 = yaw + (i + 1) * step;
            face(mesh,
                    x + Math.cos(a0) * radius, y + Math.sin(a0) * radius, z0,
                    x + Math.cos(a1) * radius, y + Math.sin(a1) * radius, z0,
                    x, y, z1, uv, albedo);
            // The underside, so a tier read from below is not a hole.
            face(mesh, x, y, z0,
                    x + Math.cos(a1) * radius, y + Math.sin(a1) * radius, z0,
                    x + Math.cos(a0) * radius, y + Math.sin(a0) * radius, z0, uv, albedo);
        }
    }

    /**
     * An octahedron — eight triangles, and the cheapest solid that reads as a
     * blob rather than as a box. Leaf clusters, boulders and berries are all
     * this, at different proportions.
     *
     * <p>Axis-aligned: {@code rx} lies along world east and {@code ry} along
     * world north. For anything whose length has to point <em>somewhere</em> —
     * a fish, a beetle, a seed pod — use {@link #blob(Mesh.Builder, double,
     * double, double, double, double, double, double, float[], int)} and give
     * it the yaw the rest of the model is built at.
     */
    public static void blob(Mesh.Builder mesh, double x, double y, double z,
                            double rx, double ry, double rz, float[] uv, int albedo) {
        blob(mesh, x, y, z, rx, ry, rz, 0, uv, albedo);
    }

    /**
     * An octahedron turned about the vertical, so its long axis can face
     * somewhere.
     *
     * <p><b>The half of {@link #blob} that was missing.</b> Every other solid
     * here takes a yaw and this one did not, so a model that placed a head, a
     * tail and a pair of eyes along its facing direction and then used a blob
     * for the body in between got a body lying across all three of them: a fish
     * whose snout came out of its flank. The turn matches {@link #box}'s —
     * local {@code +y} maps to {@code (−sin yaw, cos yaw)} — so a body and the
     * boxes bolted to it agree about which way is along.
     */
    public static void blob(Mesh.Builder mesh, double x, double y, double z,
                            double rx, double ry, double rz, double yaw,
                            float[] uv, int albedo) {
        double cos = Math.cos(yaw), sin = Math.sin(yaw);
        double[][] local = {{rx, 0}, {0, ry}, {-rx, 0}, {0, -ry}};
        double[][] equator = new double[4][3];
        for (int i = 0; i < 4; i++) {
            equator[i][0] = x + local[i][0] * cos - local[i][1] * sin;
            equator[i][1] = y + local[i][0] * sin + local[i][1] * cos;
            equator[i][2] = z;
        }
        double topZ = z + rz, bottomZ = z - rz;
        for (int i = 0; i < 4; i++) {
            double[] p = equator[i];
            double[] q = equator[(i + 1) % 4];
            face(mesh, p[0], p[1], p[2], q[0], q[1], q[2], x, y, topZ, uv, albedo);
            face(mesh, q[0], q[1], q[2], p[0], p[1], p[2], x, y, bottomZ, uv, albedo);
        }
    }

    /**
     * An axis-aligned box, given its centre and half-extents, turned about the
     * vertical by {@code yaw} — the whole of what an animal model is made of.
     */
    public static void box(Mesh.Builder mesh, double cx, double cy, double cz,
                           double hx, double hy, double hz, double yaw,
                           float[] uv, int albedo) {
        double cos = Math.cos(yaw), sin = Math.sin(yaw);
        // The box's own axes, as half-extent vectors: local +x turned by the
        // yaw, local +y turned with it, and up left alone. Right-handed, which
        // is what lets `solid` use one winding table for every box in the game.
        solid(mesh, cx, cy, cz,
                cos * hx, sin * hx, 0,
                -sin * hy, cos * hy, 0,
                0, 0, hz, uv, albedo);
    }

    /**
     * A box between two points — <b>a limb, an oar, a rafter: anything whose
     * length points somewhere other than straight up.</b>
     *
     * <p>{@link #box} turns about the vertical only, which is all a standing
     * animal needs and is exactly what a swinging one does not have. A leg
     * built out of yawed boxes cannot rotate about the hip, so the old walk
     * cycle slid an upright box forward and back along the arc a thigh would
     * have swept and left the boot to catch up on its own: at any part of the
     * stride but the middle, the figure read as a stack of parts drifting past
     * each other rather than as a person walking. This is the primitive that
     * fixes that, and the one an oar needs for the same reason — an oar spends
     * its whole stroke at an angle to all three axes.
     *
     * <p>The cross-section is squared to the shallowest world axis, so a limb
     * is not twisted about its own length by the choice of basis: {@code up}
     * unless the strut is within a few degrees of vertical, in which case
     * world north, which no limb is ever parallel to for long enough to see the
     * changeover.
     *
     * @param halfWidth half the cross-section across the strut
     * @param halfThick half the cross-section the other way
     */
    public static void strut(Mesh.Builder mesh,
                             double x0, double y0, double z0,
                             double x1, double y1, double z1,
                             double halfWidth, double halfThick,
                             float[] uv, int albedo) {
        double dx = x1 - x0, dy = y1 - y0, dz = z1 - z0;
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        // A strut of no length is a degenerate solid with no normals: skip it
        // rather than emitting twelve triangles the shader cannot light.
        if (length < 1e-7) return;
        dx /= length;
        dy /= length;
        dz /= length;

        // A reference the strut is not parallel to; see the note above.
        double rx = 0, ry = 0, rz = 1;
        if (Math.abs(dz) > 0.995) {
            rx = 0;
            ry = 1;
            rz = 0;
        }
        // side = along × reference, and up = along × side. In that order the
        // triple (side, up, along) is right-handed, which is what `solid`'s
        // winding assumes.
        double sx = dy * rz - dz * ry;
        double sy = dz * rx - dx * rz;
        double sz = dx * ry - dy * rx;
        double slen = Math.sqrt(sx * sx + sy * sy + sz * sz);
        if (slen < 1e-9) return;
        sx /= slen;
        sy /= slen;
        sz /= slen;
        double ux = dy * sz - dz * sy;
        double uy = dz * sx - dx * sz;
        double uz = dx * sy - dy * sx;

        double half = length / 2;
        solid(mesh, (x0 + x1) / 2, (y0 + y1) / 2, (z0 + z1) / 2,
                sx * halfWidth, sy * halfWidth, sz * halfWidth,
                ux * halfThick, uy * halfThick, uz * halfThick,
                dx * half, dy * half, dz * half, uv, albedo);
    }

    /**
     * Six quads about a centre, from three half-axis vectors — what both
     * {@link #box} and {@link #strut} are.
     *
     * <p>The three axes must be right-handed ({@code a × b = c} in direction),
     * or every face comes out wound inward and the whole solid is lit as though
     * the sun were inside it.
     */
    private static void solid(Mesh.Builder mesh, double cx, double cy, double cz,
                              double ax, double ay, double az,
                              double bx, double by, double bz,
                              double dx, double dy, double dz,
                              float[] uv, int albedo) {
        double[][] corner = new double[8][3];
        int at = 0;
        for (int sc = -1; sc <= 1; sc += 2) {
            for (int sb = -1; sb <= 1; sb += 2) {
                for (int sa = -1; sa <= 1; sa += 2) {
                    corner[at][0] = cx + sa * ax + sb * bx + sc * dx;
                    corner[at][1] = cy + sa * ay + sb * by + sc * dy;
                    corner[at][2] = cz + sa * az + sb * bz + sc * dz;
                    at++;
                }
            }
        }
        // Indices into the corner table: (c, b, a) in that nesting order.
        // 0:(-,-,-) 1:(-,-,+) 2:(-,+,-) 3:(-,+,+) 4:(+,-,-) 5:(+,-,+) 6:(+,+,-) 7:(+,+,+)
        quadOf(mesh, corner, 4, 5, 7, 6, uv, albedo); // +c
        quadOf(mesh, corner, 0, 2, 3, 1, uv, albedo); // −c
        quadOf(mesh, corner, 0, 1, 5, 4, uv, albedo); // −b
        quadOf(mesh, corner, 3, 2, 6, 7, uv, albedo); // +b
        quadOf(mesh, corner, 1, 3, 7, 5, uv, albedo); // +a
        quadOf(mesh, corner, 2, 0, 4, 6, uv, albedo); // −a
    }

    private static void quadOf(Mesh.Builder mesh, double[][] c, int a, int b, int d, int e,
                               float[] uv, int albedo) {
        quad(mesh, c[a][0], c[a][1], c[a][2], c[b][0], c[b][1], c[b][2],
                c[d][0], c[d][1], c[d][2], c[e][0], c[e][1], c[e][2], uv, albedo);
    }

    /**
     * A blade of grass: a tapered quad from a base of width {@code width} to a
     * tip displaced by {@code (leanX, leanY)}, drawn on both sides so it is
     * visible from anywhere.
     */
    public static void blade(Mesh.Builder mesh, double x, double y, double z,
                             double height, double width, double yaw,
                             double leanX, double leanY, float[] uv, int albedo) {
        double hx = Math.cos(yaw) * width / 2, hy = Math.sin(yaw) * width / 2;
        double tipX = x + leanX, tipY = y + leanY, tipZ = z + height;
        // Front and back, so the blade does not vanish when seen from behind.
        face(mesh, x - hx, y - hy, z, x + hx, y + hy, z, tipX, tipY, tipZ, uv, albedo);
        face(mesh, x + hx, y + hy, z, x - hx, y - hy, z, tipX, tipY, tipZ, uv, albedo);
    }
}
