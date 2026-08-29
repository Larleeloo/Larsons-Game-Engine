package com.larsons.engine.watch.render;

import com.larsons.engine.watch.Boats;
import com.larsons.engine.watch.world.WatchMaterial;
import com.larsons.engine.watch.world.WatchMaterials;

/**
 * A clinker-built rowing boat, out of the same handful of solids everything
 * else in this world is made of.
 *
 * <p>Six planks a side, tapering to a point at the bow and a transom at the
 * stern, two thwarts across and a pair of oars. About ninety triangles, which
 * is a third of a deer and a hundredth of a chunk of ground — a lake with a
 * dozen boats on it costs nothing worth measuring.
 *
 * <p>Drawn sitting on the waterline rather than on the bed, because a boat
 * floats: the caller passes {@link Boats.Boat#z()}, which is the water level,
 * and everything here is measured from it.
 *
 * <h2>The oars</h2>
 *
 * <p>A moored boat has them <b>shipped</b> — stowed fore and aft along the
 * gunwale, which is where you leave a pair of oars and is what tells you at a
 * glance that nobody is in the boat. A boat somebody is rowing has them
 * <b>working</b>, swung about their locks by {@link RowStroke}: blades buried
 * and sweeping aft through the drive, lifted clear and swung forward again
 * through the recovery. They were shipped in both cases before, which is why a
 * rowed boat used to travel at nine and a half metres a second with its oars
 * lying tidily along the rail beside a rower thrashing their legs as though
 * running.
 *
 * <p>Everything about where an oar is at a given instant lives in
 * {@link #handle} and {@link #blade}, so the person swinging them can be put
 * exactly on the handles — see {@link WalkerModel#rower}.
 */
public final class BoatModel {

    private BoatModel() {}

    /** How much of the hull is below the waterline, in metres. */
    public static final double DRAUGHT = 0.22;

    /** How deep the hull is from keel to gunwale, in metres. */
    public static final double DEPTH = 0.46;

    /** Half the length overall, in metres. */
    public static final double HALF = 1.7;

    /** Half the beam, in metres. */
    public static final double BEAM = 0.62;

    /** How far the hull lifts and drops on the swell, in metres. */
    private static final double HEAVE = 0.035;

    /** How far aft of amidships a rower sits, in metres. */
    public static final double SEAT_ALONG = -HALF * 0.35;

    /**
     * How far forward of amidships the oarlocks are, in metres.
     *
     * <p>Just forward of the seat rather than amidships, and that is a
     * constraint rather than a taste: the handles have to end up inside a
     * seated person's arm span at every point of the stroke, and an oarlock
     * amidships puts them eighty centimetres in front of a rower sitting on the
     * aft thwart — further than an arm reaches, so the hands let go of the
     * handles at the finish of every stroke.
     */
    private static final double LOCK_ALONG = SEAT_ALONG + 0.175;

    /** How far out from the centreline they are. */
    private static final double LOCK_ACROSS = BEAM * 0.86;

    /** Oar inboard of the lock (the handle) and outboard of it (to the blade). */
    private static final double HANDLE_ARM = 0.46, BLADE_ARM = 1.30;

    /** How far either way an oar sweeps, in radians. */
    private static final double SWEEP = 0.62;

    /**
     * How far the blade sits below the lock when it is buried, in metres.
     *
     * <p>Deeper than the hull's own draught, which is the point: a blade that
     * only reached the waterline would be skimming the surface beside a boat
     * sitting deeper than it, and a stroke has to bite.
     */
    private static final double BURIED = 0.55;

    /** …and how much of that it gives back at the top of the recovery. */
    private static final double CLEARED = 0.46;

    /** Passed as the stroke to say "nobody is rowing": oars shipped. */
    public static final double SHIPPED = -1;

    /**
     * How far the hull is off its waterline this instant, in metres.
     *
     * <p>Public because a rower is not drawn by this class and has to ride the
     * same swell: a boat that bobs under a person who does not is two objects
     * sliding through each other, and it is the first thing anybody notices
     * about a boat with somebody in it.
     */
    public static double heave(double bob) {
        return Math.sin(RowStroke.wrap(bob) * Math.PI * 2) * HEAVE;
    }

    /** The height of the keel inside the boat — where a rower's feet go. */
    public static double floorZ(double waterZ, double bob) {
        return waterZ + heave(bob) - DRAUGHT + DEPTH * 0.10;
    }

    /** The height of the thwarts — where a rower sits. */
    public static double thwartZ(double waterZ, double bob) {
        return waterZ + heave(bob) - DRAUGHT + DEPTH * 0.86;
    }

    /**
     * Where one oar's handle is, in the boat's own frame.
     *
     * <p>{@code out} comes back as {@code {along, across, up}} — along the hull
     * toward the bow, across it to starboard, and up from the waterline before
     * the heave. {@code side} is {@code -1} for the port oar and {@code +1} for
     * the starboard one.
     *
     * <p>The handle is <b>inboard</b> of the lock and the blade is outboard, so
     * they swing opposite ways about it: that is why hands pushed toward the
     * bow put the blades astern, and why a forward-facing rower pushes rather
     * than pulls. See {@link RowStroke}.
     */
    public static void handle(double stroke, int side, double[] out) {
        double sweep = SWEEP * RowStroke.reach(stroke);
        double rise = (BURIED - CLEARED * RowStroke.lift(stroke)) * HANDLE_ARM / BLADE_ARM;
        out[0] = LOCK_ALONG + Math.sin(sweep) * HANDLE_ARM;
        out[1] = side * (LOCK_ACROSS - Math.cos(sweep) * HANDLE_ARM);
        out[2] = DEPTH - DRAUGHT + rise;
    }

    /** Where the same oar's blade is, in the same frame. */
    public static void blade(double stroke, int side, double[] out) {
        double sweep = SWEEP * RowStroke.reach(stroke);
        double drop = BURIED - CLEARED * RowStroke.lift(stroke);
        out[0] = LOCK_ALONG - Math.sin(sweep) * BLADE_ARM;
        out[1] = side * (LOCK_ACROSS + Math.cos(sweep) * BLADE_ARM);
        out[2] = DEPTH - DRAUGHT - drop;
    }

    /** A boat nobody is in: oars shipped along the gunwale. */
    public static void boat(Mesh.Builder mesh, double x, double y, double z, double yaw,
                            double bob) {
        boat(mesh, x, y, z, yaw, bob, SHIPPED);
    }

    /**
     * One boat, at a position relative to the mesh's origin.
     *
     * @param bob    how far through the bobbing cycle, in turns; a moored boat
     *               that is perfectly still reads as a prop
     * @param stroke how far through a stroke of the oars, in turns, or
     *               {@link #SHIPPED} for a boat nobody is rowing
     */
    public static void boat(Mesh.Builder mesh, double x, double y, double z, double yaw,
                            double bob, double stroke) {
        float[] uv = new float[4];
        WatchMaterials.uv(WatchMaterial.PLANK, uv);
        int hull = WatchMaterials.shade(WatchMaterial.PLANK);
        int trim = WatchMaterials.shade(WatchMaterial.DARK_BARK);
        int oar = WatchMaterials.shade(WatchMaterial.BARK);

        boolean rowing = stroke >= 0;
        double base = z + heave(bob) - DRAUGHT;
        // A moored hull rocks; a rowed one noses down on the drive and runs
        // level on the recovery, which is the surge you feel in a boat and the
        // reason a rowed boat does not look like a boat being towed. Both are
        // the same thing to the geometry below — a rise applied along the hull
        // — so they are one number.
        double tilt = rowing
                ? -RowStroke.surge(stroke) * 0.05
                : Math.cos(RowStroke.wrap(bob) * Math.PI * 2) * 0.02;

        double cos = Math.cos(yaw), sin = Math.sin(yaw);
        // Along the hull, and across it.
        double ax = sin, ay = -cos;
        double bx = cos, by = sin;

        double half = HALF, beam = BEAM, depth = DEPTH;

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
                    base + depth / 2 + tilt * mid,
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

        if (!rowing) {
            // Shipped: stowed fore and aft along each gunwale, out of the way.
            for (int side = -1; side <= 1; side += 2) {
                double ox = x + bx * side * beam * 0.72;
                double oy = y + by * side * beam * 0.72;
                Shapes.box(mesh, ox, oy, base + depth * 1.02, 0.028, half * 0.8, 0.028,
                        yaw, uv, oar);
                Shapes.box(mesh, ox + ax * half * 0.78, oy + ay * half * 0.78,
                        base + depth * 1.02, 0.055, 0.19, 0.014, yaw, uv, oar);
            }
            return;
        }

        // Working: the loom runs from the handle, through the lock, to the
        // blade, as one strut — so an oar bends nowhere and pivots exactly
        // where the lock is.
        double[] grip = new double[3], tip = new double[3];
        for (int side = -1; side <= 1; side += 2) {
            handle(stroke, side, grip);
            blade(stroke, side, tip);
            double hx = x + ax * grip[0] + bx * grip[1];
            double hy = y + ay * grip[0] + by * grip[1];
            double hz = z + heave(bob) + grip[2];
            double tx = x + ax * tip[0] + bx * tip[1];
            double ty = y + ay * tip[0] + by * tip[1];
            double tz = z + heave(bob) + tip[2];
            Shapes.strut(mesh, hx, hy, hz, tx, ty, tz, 0.028, 0.028, uv, oar);

            // The blade itself, on the outboard end and square to the loom: a
            // flat paddle rather than the end of a broom handle.
            double bladeX = tx - (tx - hx) * 0.11;
            double bladeY = ty - (ty - hy) * 0.11;
            double bladeZ = tz - (tz - hz) * 0.11;
            Shapes.strut(mesh, bladeX, bladeY, bladeZ, tx, ty, tz,
                    0.012, 0.085, uv, oar);
        }
    }
}
