package com.larsons.engine.watch.render;

import com.larsons.engine.watch.Boats;
import com.larsons.engine.watch.world.WatchMaterial;
import com.larsons.engine.watch.world.WatchMaterials;

/**
 * A clinker-built rowing boat, out of the same handful of solids everything
 * else in this world is made of.
 *
 * <p>Six planks a side, tapering to a point at the bow and a transom at the
 * stern, two thwarts across and a pair of oars shipped along the gunwale. About
 * ninety triangles, which is a third of a deer and a hundredth of a chunk of
 * ground — a lake with a dozen boats on it costs nothing worth measuring.
 *
 * <p>Drawn sitting on the waterline rather than on the bed, because a boat
 * floats: the caller passes {@link Boats.Boat#z()}, which is the water level,
 * and everything here is measured from it.
 */
public final class BoatModel {

    private BoatModel() {}

    /** How much of the hull is below the waterline, in metres. */
    private static final double DRAUGHT = 0.22;

    /**
     * One boat, at a position relative to the mesh's origin.
     *
     * @param bob how far through the bobbing cycle, in turns; a moored boat
     *            that is perfectly still reads as a prop
     */
    public static void boat(Mesh.Builder mesh, double x, double y, double z, double yaw,
                            double bob) {
        float[] uv = new float[4];
        WatchMaterials.uv(WatchMaterial.PLANK, uv);
        int hull = WatchMaterials.shade(WatchMaterial.PLANK);
        int trim = WatchMaterials.shade(WatchMaterial.DARK_BARK);
        int oar = WatchMaterials.shade(WatchMaterial.BARK);

        double lift = Math.sin(bob * Math.PI * 2) * 0.035;
        double roll = Math.cos(bob * Math.PI * 2) * 0.02;
        double base = z + lift - DRAUGHT;

        double cos = Math.cos(yaw), sin = Math.sin(yaw);
        // Along the hull, and across it.
        double ax = sin, ay = -cos;
        double bx = cos, by = sin;

        double half = 1.7, beam = 0.62, depth = 0.46;

        // The hull, as five sections that narrow toward the bow. A single box
        // would be a crate; the taper is what reads as a boat from the shore.
        int sections = 5;
        for (int i = 0; i < sections; i++) {
            double t0 = i / (double) sections, t1 = (i + 1) / (double) sections;
            double mid = (t0 + t1) / 2;
            // 0 at the stern, 1 at the bow.
            double width = beam * (1 - Math.pow(mid, 2.4) * 0.82);
            double along = -half + (t0 + t1) / 2 * half * 2;
            double length = half * 2 / sections / 2;
            Shapes.box(mesh, x + ax * along, y + ay * along,
                    base + depth / 2 + roll * mid,
                    width, length, depth / 2, yaw, uv, hull);
        }

        // The gunwale, a dark strip down each side, and the transom.
        for (int side = -1; side <= 1; side += 2) {
            Shapes.box(mesh, x + bx * side * beam * 0.86, y + by * side * beam * 0.86,
                    base + depth, beam * 0.10, half * 0.9, 0.045, yaw, uv, trim);
        }
        Shapes.box(mesh, x - ax * half, y - ay * half, base + depth * 0.55,
                beam * 0.92, 0.06, depth * 0.5, yaw, uv, trim);

        // Two thwarts to sit on.
        for (int i = 0; i < 2; i++) {
            double along = -half * 0.35 + i * half * 0.7;
            Shapes.box(mesh, x + ax * along, y + ay * along, base + depth * 0.86,
                    beam * 0.82, 0.09, 0.035, yaw, uv, trim);
        }

        // Oars, shipped fore and aft along the gunwale.
        for (int side = -1; side <= 1; side += 2) {
            double ox = x + bx * side * beam * 0.72;
            double oy = y + by * side * beam * 0.72;
            Shapes.box(mesh, ox, oy, base + depth * 1.02, 0.028, half * 0.8, 0.028,
                    yaw, uv, oar);
            Shapes.box(mesh, ox + ax * half * 0.78, oy + ay * half * 0.78,
                    base + depth * 1.02, 0.055, 0.19, 0.014, yaw, uv, oar);
        }
    }
}
