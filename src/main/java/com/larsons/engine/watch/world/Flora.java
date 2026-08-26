package com.larsons.engine.watch.world;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything that grows without anybody planting it — <b>decided by where it
 * would grow, never stored.</b>
 *
 * <p>A forest is a few million trees. Storing them is out of the question and
 * sending them over the wire is worse, so a wild tree is a function of its own
 * position: the chunk that contains it works out that it is there, what species
 * it is, how old it is and which way it faces, and throws all of that away again
 * when the chunk is evicted. Two players who walk to the same clearing a week
 * apart find the same crooked pine.
 *
 * <h2>How a candidate becomes a plant</h2>
 *
 * <p>The plane is cut into cells — five metres for trees, six for bushes, nine
 * for boulders — and each cell hashes out <b>one</b> jittered candidate
 * position inside itself. That is a cheap stand-in for a blue-noise
 * distribution: no two trees can land on top of each other, and because the
 * position is jittered inside its cell the result has no visible rows in it.
 * The candidate then has to pass:
 *
 * <ul>
 *   <li>the biome's own density — a roll against
 *       {@link WatchBiome#treeDensity()} and friends;</li>
 *   <li>the ground — nothing roots on a cliff, and nothing but a
 *       {@link TreeSpecies.Form#STILT} mangrove roots in water;</li>
 *   <li>the trail — a path that grew a tree through the middle of it would
 *       not be a path.</li>
 * </ul>
 *
 * <p>Cells are visited with a one-cell margin around the chunk, because a tree
 * whose trunk is in the next chunk still has a crown that reaches into this
 * one; the ones that fall outside the chunk are discarded after the check,
 * which keeps every chunk's list to what is actually standing in it.
 */
public final class Flora {

    /** Metres between tree candidates. */
    public static final double TREE_CELL = 5;

    /** Metres between bush candidates. */
    public static final double BUSH_CELL = 6;

    /** Metres between boulder candidates. */
    public static final double ROCK_CELL = 9;

    /** Above this slope nothing roots. */
    private static final double MAX_SLOPE = 0.72;

    /**
     * How much a biome's {@code rockDensity} — which reads naturally as
     * "boulders per square metre" while tuning — is multiplied by to become a
     * probability per {@value #ROCK_CELL}-metre cell.
     */
    private static final double ROCK_SCALE = 25;

    /** A berry bush or shrub. */
    public record Bush(double x, double y, double z, String berry, double radius,
                       boolean ripe, WatchMaterial foliage) {}

    /** A boulder or rock outcrop. */
    public record Rock(double x, double y, double z, double radius, double yaw,
                       WatchMaterial material) {}

    private final long seed;
    private final TerrainField field;

    public Flora(long seed, TerrainField field) {
        this.seed = seed;
        this.field = field;
    }

    /**
     * Where the ground is, what biome it belongs to, and how steep it is.
     *
     * <p><b>Two callers ask the same questions from different places.</b> A
     * chunk being meshed has a whole heightfield in hand and reads the answers
     * off it; a server working out what a player just picked has no chunks at
     * all and asks the generator directly. If those two disagreed about where a
     * bush is, a player would pick a berry off a bush nobody else can see — so
     * the <em>decision</em> lives in one place here and only the source of the
     * three inputs differs.
     */
    public interface Ground {
        double heightAt(double x, double y);

        WatchBiome biomeAt(double x, double y);

        double slopeAt(double x, double y);
    }

    /** A {@link Ground} backed by the generator itself — what a server uses. */
    public static Ground ground(TerrainField field) {
        return new Ground() {
            @Override public double heightAt(double x, double y) {
                return field.heightAt(x, y);
            }

            @Override public WatchBiome biomeAt(double x, double y) {
                return field.biomeAt(x, y);
            }

            @Override public double slopeAt(double x, double y) {
                // Central differences over the same two metres a chunk's
                // heightfield is sampled at, so the two agree.
                double step = WatchChunk.STEP;
                double dx = (field.heightAt(x + step, y) - field.heightAt(x - step, y))
                        / (2 * step);
                double dy = (field.heightAt(x, y + step) - field.heightAt(x, y - step))
                        / (2 * step);
                return Math.sqrt(dx * dx + dy * dy);
            }
        };
    }

    /**
     * Fill a freshly generated chunk with its wild plants and rocks. Runs on
     * the chunk's own worker, before the chunk is published.
     */
    public void scatter(WatchChunk chunk) {
        double x0 = chunk.originX(), y0 = chunk.originY();
        double x1 = x0 + WatchChunk.SIZE, y1 = y0 + WatchChunk.SIZE;
        for (int[] cell : cells(x0 - TREE_CELL, y0 - TREE_CELL, x1 + TREE_CELL,
                y1 + TREE_CELL, TREE_CELL)) {
            TreeInstance tree = treeAt(cell[0], cell[1], chunk);
            if (tree != null && inside(x0, y0, x1, y1, tree.x(), tree.y())) {
                chunk.addTree(tree);
            }
        }
        for (int[] cell : cells(x0, y0, x1, y1, BUSH_CELL)) {
            Bush bush = bushAt(cell[0], cell[1], chunk);
            if (bush != null && inside(x0, y0, x1, y1, bush.x(), bush.y())) {
                chunk.addBush(bush);
            }
        }
        for (int[] cell : cells(x0, y0, x1, y1, ROCK_CELL)) {
            Rock rock = rockAt(cell[0], cell[1], chunk);
            if (rock != null && inside(x0, y0, x1, y1, rock.x(), rock.y())) {
                chunk.addRock(rock);
            }
        }
    }

    /** The cells whose candidates could fall in a rectangle. */
    private static List<int[]> cells(double x0, double y0, double x1, double y1,
                                     double size) {
        List<int[]> out = new ArrayList<>();
        int firstX = (int) Math.floor(x0 / size), lastX = (int) Math.floor(x1 / size);
        int firstY = (int) Math.floor(y0 / size), lastY = (int) Math.floor(y1 / size);
        for (int cy = firstY; cy <= lastY; cy++) {
            for (int cx = firstX; cx <= lastX; cx++) out.add(new int[]{cx, cy});
        }
        return out;
    }

    private static boolean inside(double x0, double y0, double x1, double y1,
                                  double x, double y) {
        return x >= x0 && x < x1 && y >= y0 && y < y1;
    }

    /** The tree in a cell, or {@code null} — the whole decision, in one place. */
    public TreeInstance treeAt(int cx, int cy, Ground ground) {
        long h = hash(cx, cy, 0x7EE5);
        double px = (cx + 0.1 + 0.8 * unit(h)) * TREE_CELL;
        double py = (cy + 0.1 + 0.8 * roll(h, 17)) * TREE_CELL;

        WatchBiome biome = ground.biomeAt(px, py);
        List<TreeSpecies> options = biome.trees();
        if (options.isEmpty()) return null;
        if (roll(h, 34) >= Math.min(1, biome.treeDensity())) return null;

        double z = ground.heightAt(px, py);
        double water = field.waterDepth(z);
        TreeSpecies species = options.get((int) ((h >>> 47) % options.size()));
        boolean stilt = species.form() == TreeSpecies.Form.STILT;
        if (water > 0 && !(stilt && water < 1.6)) return null;
        if (ground.slopeAt(px, py) > MAX_SLOPE) return null;
        // A trail with a tree in the middle of it is not a trail.
        if (field.trailAt(px, py) > 0.35) return null;
        return TreeInstance.wild(species, px, py, z, h);
    }

    /** The bush in a cell, or {@code null}. */
    public Bush bushAt(int cx, int cy, Ground ground) {
        long h = hash(cx, cy, 0x3B05);
        double px = (cx + 0.15 + 0.7 * unit(h)) * BUSH_CELL;
        double py = (cy + 0.15 + 0.7 * roll(h, 15)) * BUSH_CELL;

        WatchBiome biome = ground.biomeAt(px, py);
        List<String> berries = biome.berries();
        if (berries.isEmpty()) return null;
        if (roll(h, 31) >= biome.bushDensity()) return null;

        double z = ground.heightAt(px, py);
        if (field.waterDepth(z) > 0) return null;
        if (ground.slopeAt(px, py) > MAX_SLOPE) return null;
        if (field.trailAt(px, py) > 0.5) return null;

        String berry = berries.get((int) ((h >>> 45) % berries.size()));
        // Two bushes in three are carrying; the rest are picked over or out of
        // season, which is what makes finding one worth anything.
        boolean ripe = roll(h, 52) < 0.66;
        double radius = 0.6 + roll(h, 24) * 0.7;
        return new Bush(px, py, z, berry, radius, ripe,
                biome.trees().isEmpty() ? WatchMaterial.LEAF
                        : biome.trees().get(0).foliage());
    }

    /** The boulder in a cell, or {@code null}. */
    public Rock rockAt(int cx, int cy, Ground ground) {
        long h = hash(cx, cy, 0x1C0B);
        double px = (cx + 0.1 + 0.8 * unit(h)) * ROCK_CELL;
        double py = (cy + 0.1 + 0.8 * roll(h, 13)) * ROCK_CELL;

        WatchBiome biome = ground.biomeAt(px, py);
        if (roll(h, 29) >= Math.min(1, biome.rockDensity() * ROCK_SCALE)) return null;

        double z = ground.heightAt(px, py);
        if (field.waterDepth(z) > 1.2) return null;
        double radius = 0.5 + roll(h, 41) * 1.9;
        double yaw = roll(h, 20) * Math.PI * 2;
        return new Rock(px, py, z, radius, yaw, biome.cliff());
    }

    /**
     * The nearest bush to a point, within a radius, or {@code null}.
     *
     * <p>What "pick a berry" actually asks. Searches the cells that could hold
     * one rather than a list, because there is no list — see the class note.
     */
    public Bush nearestBush(Ground ground, double x, double y, double radius) {
        Bush best = null;
        double bestDistance = radius * radius;
        for (int[] cell : cells(x - radius, y - radius, x + radius, y + radius, BUSH_CELL)) {
            Bush bush = bushAt(cell[0], cell[1], ground);
            if (bush == null) continue;
            double dx = bush.x() - x, dy = bush.y() - y;
            double d = dx * dx + dy * dy;
            if (d < bestDistance) {
                bestDistance = d;
                best = bush;
            }
        }
        return best;
    }

    /** The nearest tree to a point, within a radius, or {@code null}. */
    public TreeInstance nearestTree(Ground ground, double x, double y, double radius) {
        TreeInstance best = null;
        double bestDistance = radius * radius;
        for (int[] cell : cells(x - radius, y - radius, x + radius, y + radius, TREE_CELL)) {
            TreeInstance tree = treeAt(cell[0], cell[1], ground);
            if (tree == null) continue;
            double dx = tree.x() - x, dy = tree.y() - y;
            double d = dx * dx + dy * dy;
            if (d < bestDistance) {
                bestDistance = d;
                best = tree;
            }
        }
        return best;
    }

    /** The nearest boulder to a point, within a radius, or {@code null}. */
    public Rock nearestRock(Ground ground, double x, double y, double radius) {
        Rock best = null;
        double bestDistance = radius * radius;
        for (int[] cell : cells(x - radius, y - radius, x + radius, y + radius, ROCK_CELL)) {
            Rock rock = rockAt(cell[0], cell[1], ground);
            if (rock == null) continue;
            double dx = rock.x() - x, dy = rock.y() - y;
            double d = dx * dx + dy * dy;
            if (d < bestDistance) {
                bestDistance = d;
                best = rock;
            }
        }
        return best;
    }

    private long hash(int cx, int cy, int salt) {
        long h = seed ^ (cx * 0x9E3779B97F4A7C15L) ^ (cy * 0xBF58476D1CE4E5B9L) ^ salt;
        h = (h ^ (h >>> 30)) * 0x94D049BB133111EBL;
        h = (h ^ (h >>> 27)) * 0xD6E8FEB86659FD93L;
        return h ^ (h >>> 31);
    }


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
