package com.larsons.engine.watch.world;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The paths through the wood — <b>generated, endless, and agreed on by every
 * player without a byte crossing the wire.</b>
 *
 * <p>A trail cannot be a thing the server places, because the world has no
 * edge and a party can be a kilometre apart. So it is generated the way the
 * ground is: as a pure function of position and seed, which two machines
 * evaluate separately and get the same answer from.
 *
 * <h2>How the network is built</h2>
 *
 * <p>The plane is cut into {@value #CELL}-metre cells. Each cell hashes its own
 * coordinates into <b>one node</b> somewhere inside itself, and then considers
 * two edges: to the node in the cell to the east, and to the node in the cell to
 * the south. Each edge exists only if its own hash says so, which is what makes
 * the result a <em>network</em> — junctions where three or four edges meet, dead
 * ends where none of a node's neighbours accepted, and long runs where they all
 * did — rather than a grid of diagonals.
 *
 * <p>An edge is not a straight line. It is a quadratic Bézier whose control
 * point is the midpoint pushed sideways by a hashed amount, so a path leans
 * around where a hill would be and arrives at the next node from an angle. Two
 * edges meeting at a node therefore make a bend rather than a corner.
 *
 * <h2>What a query costs</h2>
 *
 * <p>{@link #strengthAt} looks at the 3×3 block of cells around the point, and
 * each cell's edges are <em>sampled once and cached</em> as a flat array of
 * points. So a query is a few hundred squared-distance comparisons against
 * cached floats and no allocation — which matters, because the terrain field
 * asks this question at every vertex of every chunk it builds.
 *
 * <p>The cache is unbounded in principle and bounded in practice by
 * {@link #MAX_CACHED_CELLS}: a cell is 160 m across, a render distance is a few
 * hundred metres, and a party of eight cannot keep more than a few thousand
 * cells warm between them. Past the cap it is cleared wholesale rather than
 * evicted one at a time, because the contents are cheap to rebuild and a
 * concurrent LRU is not.
 */
public final class TrailNetwork {

    /** How far apart trail nodes are, in metres. */
    public static final int CELL = 160;

    /** Half the width of the walked part of a path, in metres. */
    public static final double HALF_WIDTH = 1.9;

    /**
     * How far out the ground is still pulled toward the path's own level, in
     * metres. Wider than the path itself, so a trail cut across a slope has a
     * shoulder rather than a step down off the edge of it.
     */
    public static final double SHOULDER = 5.5;

    /** Points sampled along one edge; more is smoother and slower. */
    private static final int SAMPLES = 14;

    /** Past this many cached cells the cache is emptied and refilled. */
    private static final int MAX_CACHED_CELLS = 8192;

    private final long seed;
    private final Map<Long, float[]> cells = new ConcurrentHashMap<>();

    public TrailNetwork(long seed) {
        this.seed = seed;
    }

    /**
     * How strongly a trail runs through {@code (x, y)}.
     *
     * @return {@code 1} on the centre line, falling to {@code 0} at
     *         {@link #SHOULDER} metres from it
     */
    public double strengthAt(double x, double y) {
        double best = nearestSquared(x, y, SHOULDER);
        if (best >= SHOULDER * SHOULDER) return 0;
        double distance = Math.sqrt(best);
        if (distance <= HALF_WIDTH) return 1;
        // Smoothstep across the shoulder, so the surface material and the
        // flattening both fade out instead of ending at a line.
        double t = 1 - (distance - HALF_WIDTH) / (SHOULDER - HALF_WIDTH);
        return t * t * (3 - 2 * t);
    }

    /**
     * Whether {@code (x, y)} is on the walked part of a path — what the
     * surface material and the "you are on a trail" reading use.
     */
    public boolean onTrail(double x, double y) {
        return nearestSquared(x, y, HALF_WIDTH) < HALF_WIDTH * HALF_WIDTH;
    }

    /** How many cells are currently cached; for tests and the debug overlay. */
    public int cachedCells() { return cells.size(); }

    /** The squared distance to the nearest path point, capped at {@code reach}. */
    private double nearestSquared(double x, double y, double reach) {
        int cx = Math.floorDiv((int) Math.floor(x), CELL);
        int cy = Math.floorDiv((int) Math.floor(y), CELL);
        double best = reach * reach;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                float[] points = pointsOf(cx + dx, cy + dy);
                for (int i = 0; i < points.length; i += 2) {
                    double px = points[i] - x;
                    double py = points[i + 1] - y;
                    double d = px * px + py * py;
                    if (d < best) best = d;
                }
            }
        }
        return best;
    }

    /**
     * Every sampled path point belonging to a cell — its east edge and its
     * south edge, when those exist. Built once and cached.
     */
    private float[] pointsOf(int cx, int cy) {
        long key = ((long) cx << 32) ^ (cy & 0xFFFFFFFFL);
        float[] cached = cells.get(key);
        if (cached != null) return cached;
        if (cells.size() > MAX_CACHED_CELLS) cells.clear();

        boolean east = edgeExists(cx, cy, 0);
        boolean south = edgeExists(cx, cy, 1);
        int edges = (east ? 1 : 0) + (south ? 1 : 0);
        float[] points = new float[edges * SAMPLES * 2];
        int at = 0;
        double[] a = nodeOf(cx, cy);
        if (east) at = sampleEdge(points, at, a, nodeOf(cx + 1, cy), cx, cy, 0);
        if (south) sampleEdge(points, at, a, nodeOf(cx, cy + 1), cx, cy, 1);

        cells.put(key, points);
        return points;
    }

    /** Where a cell's single node sits, in world metres. */
    private double[] nodeOf(int cx, int cy) {
        long h = hash(cx, cy, 0x51ED);
        // A quarter-cell margin, so two neighbouring nodes cannot land on top
        // of each other and produce an edge of zero length.
        double fx = 0.25 + 0.5 * unit(h);
        double fy = 0.25 + 0.5 * roll(h, 21);
        return new double[]{(cx + fx) * CELL, (cy + fy) * CELL};
    }

    /**
     * Whether a cell's edge in {@code direction} (0 = east, 1 = south) is part
     * of the network. Around eleven in twenty, which is dense enough that a
     * walker keeps finding junctions and sparse enough that the network is not
     * a lattice.
     */
    private boolean edgeExists(int cx, int cy, int direction) {
        return unit(hash(cx, cy, 0x9E37 + direction)) < 0.55;
    }

    /** Write one edge's sampled points into {@code out}, returning the new end. */
    private int sampleEdge(float[] out, int at, double[] a, double[] b,
                           int cx, int cy, int direction) {
        double mx = (a[0] + b[0]) / 2;
        double my = (a[1] + b[1]) / 2;
        double ex = b[0] - a[0], ey = b[1] - a[1];
        double length = Math.max(1e-6, Math.sqrt(ex * ex + ey * ey));
        // Push the control point perpendicular to the edge. Signed, so a path
        // bows left as often as right, and scaled by the edge's own length so a
        // short edge does not loop around itself.
        double bow = (unit(hash(cx, cy, 0x2545 + direction)) - 0.5) * 0.7 * length;
        double px = -ey / length * bow;
        double py = ex / length * bow;
        double c0 = mx + px, c1 = my + py;
        for (int i = 0; i < SAMPLES; i++) {
            double t = i / (double) (SAMPLES - 1);
            double u = 1 - t;
            out[at++] = (float) (u * u * a[0] + 2 * u * t * c0 + t * t * b[0]);
            out[at++] = (float) (u * u * a[1] + 2 * u * t * c1 + t * t * b[1]);
        }
        return at;
    }

    private long hash(int cx, int cy, int salt) {
        long h = seed;
        h = h * 0x9E3779B97F4A7C15L + cx * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 29)) * 0x94D049BB133111EBL + cy * 0x2545F4914F6CDD1DL;
        h = (h ^ (h >>> 32)) * 0xD6E8FEB86659FD93L + salt;
        return h ^ (h >>> 31);
    }

    /** The low bits of a hash as a value in {@code [0, 1)}. */

    /**
     * An independent value in {@code [0, 1)} drawn from one hash — the
     * {@code stream}-th of them.
     *
     * <p><b>Not a shift of the hash, which is what this replaced and which
     * silently did not work.</b> {@link #unit} takes the top fifty-three bits,
     * so asking for {@code unit(h >>> 34)} leaves thirty bits above the point
     * and twenty-three zeroes below it: every such "roll" came out under
     * {@code 2}<sup>-23</sup>. Every density test in the generator was
     * therefore passing unconditionally — every candidate cell grew a tree, and
     * every species in the registry came out common and tameable. Re-mixing
     * costs three multiplies and cannot fail this way.
     */
    private static double roll(long h, int stream) {
        long x = h + stream * 0x9E3779B97F4A7C15L;
        x = (x ^ (x >>> 30)) * 0xBF58476D1CE4E5B9L;
        x = (x ^ (x >>> 27)) * 0x94D049BB133111EBL;
        x ^= x >>> 31;
        return (x >>> 11) * 0x1.0p-53;
    }

    private static double unit(long h) {
        return (h >>> 11) * 0x1.0p-53;
    }
}
