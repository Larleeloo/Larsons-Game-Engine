package com.larsons.engine.watch;

import com.larsons.engine.watch.world.Flora;
import com.larsons.engine.watch.world.TerrainField;
import com.larsons.engine.watch.world.WatchBiome;
import com.larsons.engine.watch.world.WatchMaterial;

import java.util.ArrayList;
import java.util.List;

/**
 * What is lying about on the ground, waiting to be picked up.
 *
 * <h2>Why this exists</h2>
 *
 * <p>Foraging used to be invisible. {@code WatchGame.pick} would find nothing
 * in front of you, wait out a cooldown, roll against {@link Forage#underfoot}
 * and announce that you had picked up a fallen branch — from a patch of grass
 * with nothing on it. There was no branch. There was no way to see that one
 * clearing had branches in it and the next had none, no reason to walk toward
 * anything, and no way to tell a stretch of beach with quartz in it from one
 * without except by standing in both and pressing E for a minute.
 *
 * <p>So the table moved outdoors. Every candidate {@link #CELL}-metre cell
 * hashes out a position and, if the roll passes, decides what is lying there
 * from <em>the same table foraging always used</em> — the region's own
 * materials, the surface actually underfoot, and this biome's seeds. Nothing
 * about what the world can give you changed; what changed is that you can see
 * it, walk to it, and pick up the particular thing you were looking at.
 *
 * <h2>Generated, never stored</h2>
 *
 * <p>Exactly {@link Flora}'s arrangement, and for exactly its reasons: a world
 * with no edge cannot hold a list of everything on its floor, and two players
 * who walk to the same shingle bank a week apart have to find the same quartz.
 * So a piece is a function of its own cell, both ends of a connection work it
 * out independently, and the only thing that ever has to be <em>said</em> is
 * which pieces somebody has already taken.
 */
public final class Litter {

    /**
     * Metres between candidates.
     *
     * <p>Five, which is {@link Flora#TREE_CELL} — chosen for the same reason,
     * that one candidate per cell is a cheap stand-in for blue noise and no two
     * pieces can land on top of each other.
     */
    public static final double CELL = 5;

    /**
     * The share of cells that have something in them.
     *
     * <p>A quarter: about one thing every hundred square metres, so an
     * unhurried walk across a clearing passes half a dozen. Lower and foraging
     * is a worse deal than the invisible roll it replaced; higher and the floor
     * of the wood looks like a jumble sale.
     */
    private static final double DENSITY = 0.25;

    /** Above this slope nothing stays put — it has rolled to the bottom. */
    private static final double MAX_SLOPE = 0.62;

    /** How much of what is lying about is seed rather than material. */
    private static final double SEED_SHARE = 0.4;

    /**
     * One thing on the ground.
     *
     * @param id    stable identity, from the cell it was hashed out of — what a
     *              satchel-side "already taken" list is keyed on
     * @param key   the {@link Forage} item it yields
     * @param scale how big it is drawn, around 1
     */
    public record Piece(long id, String key, double x, double y, double z, double yaw,
                        double scale) {}

    private final long seed;
    private final TerrainField field;

    /** Where the trading posts are, for {@link Flora}'s reason and its comment. */
    private final Shops shops;

    public Litter(long seed, TerrainField field) {
        this.seed = seed;
        this.field = field;
        this.shops = new Shops(seed);
    }

    /**
     * What is lying in a cell, or {@code null} — the whole decision, in one
     * place, so that a host adjudicating a pick and a client drawing the ground
     * cannot disagree about what is on it.
     */
    public Piece at(int cx, int cy, Flora.Ground ground) {
        long h = hash(cx, cy);
        if (roll(h, 3) >= DENSITY) return null;

        double px = (cx + 0.12 + 0.76 * unit(h)) * CELL;
        double py = (cy + 0.12 + 0.76 * roll(h, 11)) * CELL;

        double z = ground.heightAt(px, py);
        // Nothing floats, and nothing sits on a cliff.
        if (field.waterDepth(z) > 0) return null;
        double slope = ground.slopeAt(px, py);
        if (slope > MAX_SLOPE) return null;
        // A trading post's yard is swept. Somebody lives here.
        if (shops.clearingAt(field, px, py)) return null;

        WatchBiome biome = ground.biomeAt(px, py);
        String key = keyFor(h, biome, px, py, z, slope);
        if (key == null) return null;
        return new Piece(idOf(cx, cy), key, px, py, z, roll(h, 23) * Math.PI * 2,
                0.9 + roll(h, 29) * 0.35);
    }

    /**
     * What a cell's roll turns into, given where it landed.
     *
     * <p>Seeds first, then the region-and-surface table — the same two rolls,
     * in the same order and at the same odds, that the blind ground forage used
     * to make. That is not a coincidence to be tidied away later: it is the
     * whole claim this class makes, that nothing about what the world yields
     * has changed and only its visibility has.
     */
    private String keyFor(long h, WatchBiome biome, double x, double y, double z,
                          double slope) {
        List<String> seeds = biome.seeds();
        if (!seeds.isEmpty() && roll(h, 41) < SEED_SHARE) {
            return seeds.get((int) ((h >>> 43) % seeds.size()));
        }
        WatchMaterial surface = field.surfaceAt(x, y, z, slope, biome);
        List<String> options = Forage.underfoot(biome, surface);
        if (options.isEmpty()) return null;
        return options.get((int) ((h >>> 37) % options.size()));
    }

    /** Everything lying within a radius of a point. */
    public List<Piece> near(Flora.Ground ground, double x, double y, double radius) {
        List<Piece> out = new ArrayList<>();
        double limit = radius * radius;
        for (int[] cell : cells(x, y, radius)) {
            Piece piece = at(cell[0], cell[1], ground);
            if (piece == null) continue;
            double dx = piece.x() - x, dy = piece.y() - y;
            if (dx * dx + dy * dy <= limit) out.add(piece);
        }
        return out;
    }

    /** The nearest piece to a point, within a radius, or {@code null}. */
    public Piece nearest(Flora.Ground ground, double x, double y, double radius) {
        Piece best = null;
        double bestDistance = radius * radius;
        for (int[] cell : cells(x, y, radius)) {
            Piece piece = at(cell[0], cell[1], ground);
            if (piece == null) continue;
            double dx = piece.x() - x, dy = piece.y() - y;
            double d = dx * dx + dy * dy;
            if (d < bestDistance) {
                bestDistance = d;
                best = piece;
            }
        }
        return best;
    }

    /**
     * The identity of the piece a cell would hold.
     *
     * <p>A cell pair packs into the low forty-two bits — twenty-one each, so a
     * world ten thousand kilometres across in either direction still has
     * distinct cells — and the rest is a fixed tag. The tag is what makes an
     * id from here recognisable in the {@code picked} map it shares with bush
     * and tree keys, and what keeps it away from {@code 0}, which that map
     * reads as "nothing".
     */
    public static long idOf(int cx, int cy) {
        return TAG | ((cx & CELL_MASK) << 21) | (cy & CELL_MASK);
    }

    /** Whether a {@code picked} key came from this class. */
    public static boolean isLitter(long id) {
        return (id & TAG_MASK) == TAG;
    }

    private static final long CELL_MASK = 0x1FFFFFL;

    private static final long TAG_MASK = ~((1L << 42) - 1);

    private static final long TAG = 0x4C49L << 42;

    /** The cells whose candidates could fall within a radius of a point. */
    private static List<int[]> cells(double x, double y, double radius) {
        List<int[]> out = new ArrayList<>();
        int firstX = (int) Math.floor((x - radius) / CELL);
        int lastX = (int) Math.floor((x + radius) / CELL);
        int firstY = (int) Math.floor((y - radius) / CELL);
        int lastY = (int) Math.floor((y + radius) / CELL);
        for (int cy = firstY; cy <= lastY; cy++) {
            for (int cx = firstX; cx <= lastX; cx++) out.add(new int[]{cx, cy});
        }
        return out;
    }

    private long hash(int cx, int cy) {
        long h = seed ^ (cx * 0x9E3779B97F4A7C15L) ^ (cy * 0xBF58476D1CE4E5B9L)
                ^ 0x1177L;
        h = (h ^ (h >>> 30)) * 0x94D049BB133111EBL;
        h = (h ^ (h >>> 27)) * 0xD6E8FEB86659FD93L;
        return h ^ (h >>> 31);
    }

    /**
     * An independent value in {@code [0, 1)} drawn from one hash — the
     * {@code stream}-th of them.
     *
     * <p>Re-mixed rather than shifted, for the reason {@link Flora} spells out
     * at length: {@code unit} consumes the top fifty-three bits, so a "roll"
     * that only shifts the hash comes out at effectively zero every time and
     * every density test in the generator silently passes.
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
