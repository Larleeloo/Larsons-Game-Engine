package com.larsons.engine.watch;

import com.larsons.engine.watch.world.TerrainField;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Rowing boats, drawn up on shores, waiting to be found.
 *
 * <h2>Found, not crafted</h2>
 *
 * <p>A world this size has a great deal of water in it, and swimming across a
 * lake at a metre and a half a second is the least interesting thing the game
 * asks anybody to do. A boat is the fix, and the interesting version of that
 * fix is one you <b>come across</b>: an old hull pulled up on a gravel spit,
 * where somebody left it. So boats are generated the way the trails and the
 * trees are — as a pure function of position and seed — which means they are
 * genuinely findable (they are somewhere before anyone has been there), every
 * player in a party finds the same ones, and nothing has to be replicated for
 * two people to agree that there is a boat on that beach.
 *
 * <h2>How one is placed</h2>
 *
 * <p>The world is cut into {@value #CELL}-metre cells. A cell either has a boat
 * or does not — about one in {@value #ODDS_DENOMINATOR} does — and the one that
 * does puts it at a deterministic offset within itself. That offset is then
 * <em>only</em> a boat if the ground there is a shoreline: shallow water with
 * dry land within a few metres, which is where a boat would actually be. Most
 * candidate cells are inland and produce nothing, which is why the odds look
 * generous and the boats do not.
 *
 * <p>A boat that has been rowed somewhere else is a different thing — it is
 * state, and it goes in the save and over the wire like a feeder does. So
 * {@link Moved} overrides the generated position for a given cell, and a cell
 * with a moved boat does not also generate its original.
 */
public final class Boats {

    /** How large a cell is, in metres. */
    public static final double CELL = 190;

    /** One cell in this many carries a boat, before the shoreline test. */
    private static final int ODDS_DENOMINATOR = 3;

    /** How deep the water at a mooring is, at most, in metres. */
    private static final double MOORING_DEPTH = 1.4;

    /** How far a player can be from a boat and still board it, in metres. */
    public static final double BOARD_RANGE = 3.2;

    /** How fast a boat rows, in metres per second. */
    public static final double ROW_SPEED = 9.5;

    /** How high a boat's deck sits above the waterline, in metres. */
    public static final double DECK = 0.32;

    /**
     * One boat, wherever it currently is.
     *
     * @param id    the cell it was generated in, which is its identity for ever
     * @param x     where it is now
     * @param yaw   which way it is pointing, in radians
     * @param moved whether that is where it was generated or where somebody left it
     */
    public record Boat(long id, double x, double y, double z, double yaw, boolean moved) {

        /** Length overall, in metres. */
        public double length() { return 3.4; }

        /** Beam, in metres. */
        public double beam() { return 1.25; }

        /** How far a player is from it, in metres. */
        public double distanceTo(double px, double py) {
            return Math.hypot(x - px, y - py);
        }
    }

    /** A boat somebody has taken somewhere. */
    private record Moved(double x, double y, double z, double yaw) {}

    private final long seed;
    private final Map<Long, Moved> moved = new LinkedHashMap<>();

    public Boats(long seed) {
        this.seed = seed;
    }

    /**
     * A boat's identity, from the cell that generated it.
     *
     * <p>Salted, and the salt is load-bearing: {@code 0} is what
     * {@link com.larsons.engine.watch.WatchPlayer#boatId()} means by "not in a
     * boat", and the unsalted packing gives cell (0, 0) an id of exactly zero.
     * Players spawn at the world origin, so that was not a corner case — it was
     * the first boat most people would ever walk up to, and it could be boarded
     * and then never rowed or stepped out of, because every check for "am I in
     * a boat" said no. Salting moves the collision to a cell nobody is standing
     * in, and {@link #generatedIn} refuses that one cell outright so no boat
     * ever carries the sentinel.
     */
    private static final long ID_SALT = 0x5B0A7C0DEL;

    /** The identity of the boat cell (x, y) falls in. */
    public static long cellOf(double x, double y) {
        long cx = (long) Math.floor(x / CELL);
        long cy = (long) Math.floor(y / CELL);
        return idOf(cx, cy);
    }

    private static long idOf(long cx, long cy) {
        return ((cx << 32) ^ (cy & 0xFFFFFFFFL)) ^ ID_SALT;
    }

    /**
     * Every boat within {@code radius} of a point.
     *
     * <p>Two passes, and the second one is the interesting one. The first walks
     * the cells the radius touches and asks each for the boat the seed put
     * there, which is a couple of height samples per cell and nothing else —
     * the same shape of query {@link com.larsons.engine.watch.world.Flora}
     * answers for trees.
     *
     * <p>The second scans the boats somebody has <b>moved</b>, in full, because
     * a moved boat is no longer in the cell that generated it and cell-walking
     * cannot find it. That is the whole point of a boat: you row it across the
     * lake and it is on the far side of the lake, five cells from where it
     * started. The scan is over a map that holds one entry per boat anybody has
     * ever rowed — a handful in a long session — so a linear pass is both
     * cheaper than an index and impossible to get subtly wrong.
     */
    public List<Boat> near(TerrainField field, double x, double y, double radius) {
        List<Boat> out = new ArrayList<>();
        int reach = (int) Math.ceil(radius / CELL) + 1;
        long ccx = (long) Math.floor(x / CELL);
        long ccy = (long) Math.floor(y / CELL);
        double r2 = radius * radius;
        for (long cy = ccy - reach; cy <= ccy + reach; cy++) {
            for (long cx = ccx - reach; cx <= ccx + reach; cx++) {
                Boat boat = generatedIn(field, cx, cy);
                if (boat == null) continue;
                double dx = boat.x() - x, dy = boat.y() - y;
                if (dx * dx + dy * dy <= r2) out.add(boat);
            }
        }
        moved.forEach((id, at) -> {
            double dx = at.x() - x, dy = at.y() - y;
            if (dx * dx + dy * dy <= r2) {
                out.add(new Boat(id, at.x(), at.y(), at.z(), at.yaw(), true));
            }
        });
        return out;
    }

    /** The boat nearest a point within a radius, or {@code null}. */
    public Boat nearest(TerrainField field, double x, double y, double radius) {
        Boat best = null;
        double bestDistance = radius;
        for (Boat boat : near(field, x, y, radius)) {
            double d = boat.distanceTo(x, y);
            if (d <= bestDistance) {
                bestDistance = d;
                best = boat;
            }
        }
        return best;
    }

    /** The boat with an id, wherever it is, or {@code null} if there is none. */
    public Boat byId(TerrainField field, long id) {
        Moved elsewhere = moved.get(id);
        if (elsewhere != null) {
            return new Boat(id, elsewhere.x(), elsewhere.y(), elsewhere.z(),
                    elsewhere.yaw(), true);
        }
        long cell = id ^ ID_SALT;
        return generatedIn(field, cell >> 32, (int) (cell & 0xFFFFFFFFL));
    }

    /**
     * The boat the seed put in one cell, or {@code null}.
     *
     * <p>Deterministic and cheap: one hash decides whether the cell has a boat
     * at all, a second places it, and only then does the terrain get sampled.
     *
     * <p>Answers {@code null} for a cell whose boat has been moved away, so a
     * boat is never in two places at once. Where it went is {@link #near}'s
     * second pass.
     */
    private Boat generatedIn(TerrainField field, long cx, long cy) {
        long id = idOf(cx, cy);
        // The one cell whose id would be the "not in a boat" sentinel. See
        // ID_SALT: it is somewhere nobody will ever be, and one cell in a plane
        // with no edge having no boat is not a thing anybody can observe.
        if (id == 0) return null;
        if (moved.containsKey(id)) return null;
        Random rng = new Random(seed * 0x9E3779B97F4A7C15L ^ id * 0xC2B2AE3D27D4EB4FL);
        if (rng.nextInt(ODDS_DENOMINATOR) != 0) return null;

        double baseX = cx * CELL + rng.nextDouble() * CELL;
        double baseY = cy * CELL + rng.nextDouble() * CELL;
        // Walk a short way in a fixed direction looking for a shoreline. A boat
        // has to be at the edge of the water, and the odds of a random point in
        // a cell being one are poor; a dozen samples along a line through the
        // cell finds one whenever the cell has any coast in it at all.
        double angle = rng.nextDouble() * Math.PI * 2;
        double stepX = Math.cos(angle) * 9, stepY = Math.sin(angle) * 9;
        for (int i = 0; i < 12; i++) {
            double px = baseX + stepX * i, py = baseY + stepY * i;
            double depth = field.waterDepth(field.heightAt(px, py));
            if (depth <= 0.15 || depth > MOORING_DEPTH) continue;
            // Shallow water. Point it out to sea, which is whichever way gets
            // deeper — the natural way somebody would leave a boat they meant
            // to use again.
            double outX = px, outY = py;
            double bestDepth = depth;
            double facing = angle;
            for (int a = 0; a < 8; a++) {
                double look = a * Math.PI / 4;
                double sx = px + Math.cos(look) * 6, sy = py + Math.sin(look) * 6;
                double d = field.waterDepth(field.heightAt(sx, sy));
                if (d > bestDepth) {
                    bestDepth = d;
                    facing = look;
                }
            }
            double z = TerrainField.WATER_LEVEL;
            return new Boat(id, outX, outY, z, facing, false);
        }
        return null;
    }

    /** Say where a boat now is — what stepping out of one records. */
    public void moveTo(long id, double x, double y, double z, double yaw) {
        moved.put(id, new Moved(x, y, z, yaw));
    }

    /** Every boat that has been moved, so a save and a snapshot can carry them. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        List<Object> rows = new ArrayList<>();
        moved.forEach((id, at) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", id);
            row.put("x", at.x());
            row.put("y", at.y());
            row.put("z", at.z());
            row.put("yaw", at.yaw());
            rows.add(row);
        });
        m.put("moved", rows);
        return m;
    }

    /** Put the moved boats back. */
    public void load(Map<String, Object> m) {
        moved.clear();
        for (Map<String, Object> row : WatchJson.objects(m, "moved")) {
            moved.put(WatchJson.big(row, "id", 0),
                    new Moved(WatchJson.num(row, "x", 0), WatchJson.num(row, "y", 0),
                            WatchJson.num(row, "z", 0), WatchJson.num(row, "yaw", 0)));
        }
    }

    /** How many boats have been moved from where they were found. */
    public int movedCount() { return moved.size(); }
}
