package com.larsons.engine.watch;

import com.larsons.engine.watch.world.Flora;
import com.larsons.engine.watch.world.TerrainField;
import com.larsons.engine.watch.world.TrailNetwork;
import com.larsons.engine.watch.world.WatchBiome;
import com.larsons.engine.watch.world.WatchBiomes;
import com.larsons.engine.watch.world.WatchChunk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The generator, which is the load-bearing assumption of the whole game.
 *
 * <p><b>Nothing about the world is sent over the network.</b> Eight players
 * agree about where every hill and every tree is because each of them computes
 * it from the seed and the coordinate, and for that to work the field has to be
 * a pure function — the same answer for the same input, on every machine, in
 * any order, from any thread. Most of what is here is that property, asked in
 * the several ways it could break.
 *
 * <p>The rest is habitability: a world of cliffs is one you cannot walk, and a
 * world of two biomes is one you have already seen.
 */
@Timeout(120)
class TerrainFieldTest {

    private static final long SEED = 0x5EEDL;

    /** A grid of sample points spanning several kilometres. */
    private static double[][] samples(int side, double step) {
        double[][] out = new double[side * side][2];
        int at = 0;
        for (int i = 0; i < side; i++) {
            for (int j = 0; j < side; j++) {
                out[at][0] = (i - side / 2.0) * step + 0.5;
                out[at][1] = (j - side / 2.0) * step + 0.5;
                at++;
            }
        }
        return out;
    }

    // --- determinism ---------------------------------------------------------------------

    @Test
    void twoFieldsOnOneSeedAgreeEverywhere() {
        TerrainField a = new TerrainField(SEED);
        TerrainField b = new TerrainField(SEED);
        for (double[] p : samples(24, 37)) {
            assertEquals(a.heightAt(p[0], p[1]), b.heightAt(p[0], p[1]), 0.0,
                    "height at " + p[0] + "," + p[1]);
            assertEquals(a.biomeAt(p[0], p[1]).key(), b.biomeAt(p[0], p[1]).key(),
                    "biome at " + p[0] + "," + p[1]);
        }
    }

    /**
     * Order independence, which is the property the chunk streamer needs and
     * plain repeatability does not prove: a field asked about a point after
     * wandering half the map must answer what a fresh field answers.
     */
    @Test
    void askingInADifferentOrderChangesNothing() {
        TerrainField fresh = new TerrainField(SEED);
        TerrainField wandered = new TerrainField(SEED);
        for (int i = 0; i < 2000; i++) wandered.heightAt(i * 13.7, -i * 9.1);

        for (double[] p : samples(10, 61)) {
            assertEquals(fresh.heightAt(p[0], p[1]), wandered.heightAt(p[0], p[1]), 0.0);
        }
    }

    @Test
    void differentSeedsMakeDifferentWorlds() {
        TerrainField a = new TerrainField(1L);
        TerrainField b = new TerrainField(2L);
        int same = 0;
        double[][] points = samples(16, 53);
        for (double[] p : points) {
            if (Math.abs(a.heightAt(p[0], p[1]) - b.heightAt(p[0], p[1])) < 0.01) same++;
        }
        assertTrue(same < points.length / 8,
                "two seeds produced the same height at " + same + " of " + points.length
                        + " points — the seed is not reaching the noise");
    }

    /**
     * A field is read from six streaming threads at once and shared with the
     * simulation. If any of it were stateful, this is where it would show.
     */
    @Test
    void isSafeToReadFromManyThreadsAtOnce() throws InterruptedException {
        TerrainField field = new TerrainField(SEED);
        double[][] points = samples(12, 43);
        double[] expected = new double[points.length];
        for (int i = 0; i < points.length; i++) {
            expected[i] = field.heightAt(points[i][0], points[i][1]);
        }

        Thread[] threads = new Thread[6];
        boolean[] agreed = new boolean[threads.length];
        for (int t = 0; t < threads.length; t++) {
            int which = t;
            threads[t] = new Thread(() -> {
                boolean ok = true;
                for (int pass = 0; pass < 3 && ok; pass++) {
                    for (int i = 0; i < points.length; i++) {
                        if (field.heightAt(points[i][0], points[i][1]) != expected[i]) ok = false;
                    }
                }
                agreed[which] = ok;
            });
            threads[t].start();
        }
        for (Thread thread : threads) thread.join();
        for (int t = 0; t < threads.length; t++) {
            assertTrue(agreed[t], "thread " + t + " read a different world");
        }
    }

    // --- what the world is like ----------------------------------------------------------

    /**
     * Twenty biomes are twenty biomes only if a player can walk into them. This
     * is the test that caught the first fitting, where two biomes covered two
     * thirds of the map and seven never appeared at all.
     */
    @Test
    void everyBiomeIsSomewhereAndNoneSwallowsTheMap() {
        TerrainField field = new TerrainField(SEED);
        Map<String, Integer> seen = new HashMap<>();
        // Ten kilometres. A two-kilometre square is not a fair sample of a
        // world whose climate fields turn over every few hundred metres —
        // measured over one, the tundra genuinely was not there, and it is
        // six percent of the world.
        double[][] points = samples(150, 66);
        for (double[] p : points) {
            seen.merge(field.biomeAt(p[0], p[1]).key(), 1, Integer::sum);
        }

        StringBuilder missing = new StringBuilder();
        for (WatchBiome biome : WatchBiomes.all()) {
            if (!seen.containsKey(biome.key())) missing.append("\n  ").append(biome.key());
        }
        assertEquals("", missing.toString(),
                "these biomes exist in the table and nowhere in the world:" + missing);

        for (Map.Entry<String, Integer> entry : seen.entrySet()) {
            double share = entry.getValue() / (double) points.length;
            assertTrue(share < 0.25, entry.getKey() + " covers "
                    + Math.round(share * 100) + "% of the world on its own");
        }
    }

    /** Water is where the water level says it is, and there is some. */
    @Test
    void thereAreLakesAndTheyAreBelowTheWaterLine() {
        TerrainField field = new TerrainField(SEED);
        int wet = 0;
        double[][] points = samples(60, 31);
        for (double[] p : points) {
            double h = field.heightAt(p[0], p[1]);
            double depth = field.waterDepth(h);
            if (depth > 0) {
                wet++;
                assertTrue(h < TerrainField.WATER_LEVEL,
                        "water reported at height " + h + ", above the water line");
            } else {
                assertTrue(h >= TerrainField.WATER_LEVEL - 1e-9,
                        "dry land at height " + h + ", below the water line");
            }
        }
        double share = wet / (double) points.length;
        assertTrue(share > 0.03 && share < 0.45,
                "water covers " + Math.round(share * 100) + "% of the world — there has to "
                        + "be enough to fish in and enough left to walk on");
    }

    /**
     * The ground is a surface, not a set of plates.
     *
     * <p>An earlier generator gated the lake basin on a hard threshold applied
     * to the very height it was modifying, which put eighteen-metre steps
     * across a two-metre quad — invisible in a screenshot, impassable in play.
     *
     * <p><b>Bounding the step does not catch that</b>, which is the lesson of
     * writing this test: a mountainside in {@code crystal_highlands} drops nine
     * metres over two, and it is a mountainside, not a bug. Steepness and
     * discontinuity are different properties and only one of them is wrong.
     *
     * <p>So this asks the question that separates them — <b>refinement</b>. On
     * a continuous surface, subdividing an interval divides the drop among the
     * pieces; at a discontinuity one piece keeps nearly all of it. Every one of
     * the worst steps in the world is subdivided here, and no eighth of an
     * interval may carry more than a third of its fall.
     */
    @Test
    void theGroundIsContinuousEvenWhereItIsSteep() {
        TerrainField field = new TerrainField(SEED);
        double step = WatchChunk.STEP;

        // Find the steepest steps in a few square kilometres.
        double[][] steepest = new double[12][3]; // fall, x, y
        double total = 0;
        int n = 0;
        for (int i = -70; i < 70; i++) {
            for (int j = -70; j < 70; j++) {
                double x = i * step, y = j * step;
                double h = field.heightAt(x, y);
                double fall = Math.abs(field.heightAt(x + step, y) - h);
                total += fall / step;
                n++;
                if (fall > steepest[0][0]) {
                    steepest[0] = new double[]{fall, x, y};
                    java.util.Arrays.sort(steepest, (a, b) -> Double.compare(a[0], b[0]));
                }
            }
        }

        double[] worst = steepest[steepest.length - 1];
        for (double[] probe : steepest) {
            if (probe[0] < 0.5) continue; // nothing to subdivide
            double x = probe[1], y = probe[2];
            double biggestPiece = 0;
            for (int k = 0; k < 8; k++) {
                double a = field.heightAt(x + k * step / 8, y);
                double b = field.heightAt(x + (k + 1) * step / 8, y);
                biggestPiece = Math.max(biggestPiece, Math.abs(b - a));
            }
            assertTrue(biggestPiece < probe[0] * 0.34 + 1e-6, String.format(
                    "the %.2f m fall at (%.0f, %.0f) happens almost entirely in one "
                            + "eighth of the interval (%.2f m of it) — that is a step in "
                            + "the surface, not a slope. Worst step in the sample was %.2f m.",
                    probe[0], x, y, biggestPiece, worst[0]));
        }

        assertTrue(total / n < 0.9, "the average slope is " + (total / n)
                + " — the whole world is a hillside");
    }

    /** And steep ground, while allowed, has to be the exception. */
    @Test
    void almostAllOfTheWorldIsWalkable() {
        TerrainField field = new TerrainField(SEED);
        double step = WatchChunk.STEP;
        int steep = 0, n = 0;
        for (int i = -70; i < 70; i++) {
            for (int j = -70; j < 70; j++) {
                double x = i * step, y = j * step;
                double h = field.heightAt(x, y);
                double slope = Math.max(Math.abs(field.heightAt(x + step, y) - h),
                        Math.abs(field.heightAt(x, y + step) - h)) / step;
                if (slope > 1.0) steep++;
                n++;
            }
        }
        double share = steep / (double) n;
        assertTrue(share < 0.12, "ground steeper than 45 degrees covers "
                + Math.round(share * 100) + "% of the world");
    }

    // --- trails --------------------------------------------------------------------------

    /**
     * The tread is easier walking than the country it crosses.
     *
     * <p><b>Measured on the tread, with a stencil that fits on it.</b> Getting
     * this wrong is instructive: sampling two metres either side of a path
     * whose walked part is under four metres wide puts the stencil on the bank
     * of the cut, and then the harder the trail works the <em>rougher</em> it
     * measures — which is what the first version of this test reported, about a
     * generator that was in fact working. Both the sample and the control are
     * taken well inside their own kind of ground here.
     *
     * <p>Two properties, and they are not the same one. <b>Curvature</b> is the
     * bumps under your feet, and the cut takes those out. <b>Grade</b> is how
     * hard the walk is, and a symmetric average cannot flatten a constant
     * slope, so the improvement there is real but slight: a path down a
     * mountain still goes down the mountain.
     */
    @Test
    void theTreadIsSmootherAndGentlerThanTheCountryItCrosses() {
        TerrainField field = new TerrainField(SEED);
        assertNotNull(field.trails());
        double d = 0.8; // inside TrailNetwork.HALF_WIDTH, so the stencil stays on the tread

        double treadCurve = 0, treadGrade = 0, openCurve = 0, openGrade = 0;
        int tread = 0, open = 0;
        for (int i = -400; i < 400; i++) {
            for (int j = -400; j < 400; j++) {
                double x = i * 2.3, y = j * 2.3;
                boolean onTread = allSame(field, x, y, d, 1.0);
                boolean inOpen = allSame(field, x, y, d, 0.0);
                if (!onTread && !inOpen) continue;

                double h = field.heightAt(x, y);
                double curve = Math.max(
                        Math.abs(field.heightAt(x - d, y) - 2 * h + field.heightAt(x + d, y)),
                        Math.abs(field.heightAt(x, y - d) - 2 * h + field.heightAt(x, y + d)));
                double grade = Math.max(Math.abs(field.heightAt(x + d, y) - h),
                        Math.abs(field.heightAt(x, y + d) - h)) / d;
                if (onTread) {
                    tread++;
                    treadCurve += curve;
                    treadGrade += grade;
                } else {
                    open++;
                    openCurve += curve;
                    openGrade += grade;
                }
            }
        }

        assertTrue(tread > 300, "only " + tread + " samples landed squarely on a tread — "
                + "the network is too sparse to find on foot");
        assertTrue(treadCurve / tread < openCurve / open * 0.9, String.format(
                "the tread is no smoother than the open country (curvature %.5f vs %.5f) "
                        + "— the path is not being cut", treadCurve / tread, openCurve / open));
        assertTrue(treadGrade / tread < openGrade / open, String.format(
                "the tread is steeper going than the open country (grade %.4f vs %.4f)",
                treadGrade / tread, openGrade / open));
    }

    /** Whether a point and its four neighbours are all equally on or off the path. */
    private static boolean allSame(TerrainField field, double x, double y, double d,
                                   double want) {
        return field.trailAt(x, y) == want && field.trailAt(x - d, y) == want
                && field.trailAt(x + d, y) == want && field.trailAt(x, y - d) == want
                && field.trailAt(x, y + d) == want;
    }

    @Test
    void trailStrengthIsBoundedAndDeterministic() {
        TrailNetwork a = new TrailNetwork(SEED);
        TrailNetwork b = new TrailNetwork(SEED);
        int walked = 0;
        for (int i = 0; i < 4000; i++) {
            double x = i * 7.3 - 14000, y = -i * 4.1 + 900;
            double s = a.strengthAt(x, y);
            assertTrue(s >= 0 && s <= 1, "trail strength " + s + " is outside 0..1");
            assertEquals(s, b.strengthAt(x, y), 0.0, "two networks on one seed disagree");
            // onTrail is the walked tread; strengthAt reaches out over the
            // shoulder, where the ground is shaped but the path is not walked.
            // So the tread implies full strength, and not the other way round.
            if (a.onTrail(x, y)) {
                walked++;
                assertEquals(1.0, s, 0.0, "a point on the tread is not at full strength");
            }
        }
        assertTrue(walked > 0, "4000 samples across 30 km found no path at all");
    }

    // --- chunks --------------------------------------------------------------------------

    /**
     * Two chunks meeting at an edge must agree about that edge, or the world has
     * seams in it that daylight comes through. They share the sample, which is
     * why {@code SAMPLES} is {@code SIZE / STEP + 1} rather than {@code SIZE /
     * STEP}.
     */
    @Test
    void neighbouringChunksShareTheirEdge() {
        TerrainField field = new TerrainField(SEED);
        Flora flora = new Flora(SEED, field);
        WatchChunk left = WatchChunk.generate(field, flora, 0, 0, SEED);
        WatchChunk right = WatchChunk.generate(field, flora, 1, 0, SEED);
        WatchChunk below = WatchChunk.generate(field, flora, 0, 1, SEED);

        int last = WatchChunk.SAMPLES - 1;
        for (int i = 0; i < WatchChunk.SAMPLES; i++) {
            assertEquals(left.heightAt(last, i), right.heightAt(0, i), 0.0,
                    "east edge sample " + i + " does not match the neighbour's west edge");
            assertEquals(left.heightAt(i, last), below.heightAt(i, 0), 0.0,
                    "south edge sample " + i + " does not match the neighbour's north edge");
        }
    }

    @Test
    void aChunkAgreesWithTheFieldItCameFrom() {
        TerrainField field = new TerrainField(SEED);
        Flora flora = new Flora(SEED, field);
        WatchChunk chunk = WatchChunk.generate(field, flora, 3, -2, SEED);

        for (int i = 0; i < WatchChunk.SAMPLES; i += 4) {
            for (int j = 0; j < WatchChunk.SAMPLES; j += 4) {
                double x = chunk.originX() + i * WatchChunk.STEP;
                double y = chunk.originY() + j * WatchChunk.STEP;
                assertEquals(field.heightAt(x, y), chunk.heightAt(i, j), 1e-4,
                        "chunk sample " + i + "," + j + " is not the field's height");
            }
        }
    }

    @Test
    void aChunkIsTheSameChunkEveryTimeItIsGenerated() {
        TerrainField field = new TerrainField(SEED);
        Flora flora = new Flora(SEED, field);
        WatchChunk first = WatchChunk.generate(field, flora, -5, 7, SEED);
        WatchChunk again = WatchChunk.generate(field, flora, -5, 7, SEED);

        assertEquals(first.trees().size(), again.trees().size(), "tree count");
        assertEquals(first.bushes().size(), again.bushes().size(), "bush count");
        assertEquals(first.rocks().size(), again.rocks().size(), "rock count");
        for (int i = 0; i < first.trees().size(); i++) {
            assertEquals(first.trees().get(i).species(), again.trees().get(i).species());
            assertEquals(first.trees().get(i).x(), again.trees().get(i).x(), 0.0);
        }
    }

    /**
     * Trees have to be spread over the map rather than everywhere or nowhere.
     * The bug this guards against was arithmetic, not aesthetic: the density
     * roll shifted an already-shifted hash and always compared zero against a
     * positive threshold, so every candidate cell grew a tree.
     */
    @Test
    void treesGrowInSomeCellsAndNotOthers() {
        TerrainField field = new TerrainField(SEED);
        Flora flora = new Flora(SEED, field);
        Flora.Ground ground = Flora.ground(field);

        int grew = 0, cells = 0;
        for (int cx = -40; cx < 40; cx++) {
            for (int cy = -40; cy < 40; cy++) {
                cells++;
                if (flora.treeAt(cx, cy, ground) != null) grew++;
            }
        }
        assertTrue(grew > cells / 50, "only " + grew + " of " + cells
                + " cells grew a tree — the world is bald");
        assertTrue(grew < cells * 0.9, grew + " of " + cells
                + " cells grew a tree — every density roll is passing");
    }

    @Test
    void aTreeIsAlwaysStandingOnTheGround() {
        TerrainField field = new TerrainField(SEED);
        Flora flora = new Flora(SEED, field);
        Flora.Ground ground = Flora.ground(field);
        Set<String> species = new HashSet<>();
        int checked = 0;
        for (int cx = -30; cx < 30 && checked < 200; cx++) {
            for (int cy = -30; cy < 30 && checked < 200; cy++) {
                var tree = flora.treeAt(cx, cy, ground);
                if (tree == null) continue;
                checked++;
                species.add(tree.species().key());
                assertEquals(field.heightAt(tree.x(), tree.y()), tree.z(), 0.25,
                        tree.species() + " is floating or buried");
                assertTrue(tree.z() >= TerrainField.WATER_LEVEL - 0.5,
                        tree.species() + " is growing underwater");
            }
        }
        assertTrue(checked > 20, "not enough trees to judge");
        assertTrue(species.size() > 3,
                "only " + species.size() + " tree species in a 60x60 cell area");
    }
}
