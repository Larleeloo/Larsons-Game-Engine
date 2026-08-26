package com.larsons.engine.watch;

import com.larsons.engine.watch.world.Grove;
import com.larsons.engine.watch.world.TreeGenome;
import com.larsons.engine.watch.world.TreeInstance;
import com.larsons.engine.watch.world.TreeSpecies;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Trees that grow, and trees that cross — the two things the brief asked for
 * that make a walk you come back to different from the walk you left.
 *
 * <p>Growth is measured in <b>real hours</b>, not sessions, so a tree planted
 * on Tuesday is taller on Thursday whether or not the game was running. Crossing
 * is the long game: twelve of the thirty-six species exist only as the child of
 * two others, and six of those need a hybrid parent, so the last of them is
 * three generations from anything you can find growing wild.
 */
class GroveTest {

    private static Grove grove() { return new Grove(); }

    // --- growth --------------------------------------------------------------------

    @Test
    void aPlantedTreeStartsAsASeedling() {
        Grove grove = grove();
        TreeInstance tree = grove.plant(TreeSpecies.OAK, 10, 20, 3,
                TreeGenome.average(), "Kara");
        assertNotNull(tree);
        assertEquals(TreeSpecies.Stage.SEEDLING, tree.stage());
        assertEquals(0.0, tree.grownHours(), 1e-9);
        assertTrue(tree.cultivated(), "a planted tree is somebody's, not the world's");
        assertEquals("Kara", tree.plantedBy());
        assertSame(tree, grove.byId(tree.id()));
        assertEquals(1, grove.size());
    }

    @Test
    void aTreeAdvancesThroughEveryStageAndThenStops() {
        Grove grove = grove();
        TreeInstance tree = grove.plant(TreeSpecies.OAK, 0, 0, 0,
                TreeGenome.average(), "Kara");

        Set<TreeSpecies.Stage> reached = new HashSet<>();
        reached.add(tree.stage());
        for (int hour = 0; hour < 4000; hour++) {
            tree.advance(1);
            reached.add(tree.stage());
        }
        for (TreeSpecies.Stage stage : TreeSpecies.Stage.values()) {
            assertTrue(reached.contains(stage), "a tree never passed through " + stage);
        }
        assertEquals(TreeSpecies.Stage.ANCIENT, tree.stage(), "growth did not settle");

        double before = tree.height();
        tree.advance(100000);
        assertEquals(before, tree.height(), 1e-9,
                "an ancient tree is still growing — it will be a kilometre tall by August");
    }

    @Test
    void aTreeGetsTallerAndWiderAsItGrows() {
        Grove grove = grove();
        TreeInstance tree = grove.plant(TreeSpecies.REDWOOD, 0, 0, 0,
                TreeGenome.average(), null);
        double lastHeight = 0, lastCanopy = 0;
        for (TreeSpecies.Stage stage : TreeSpecies.Stage.values()) {
            while (tree.stage() != stage && tree.stage().ordinal() < stage.ordinal()) {
                tree.advance(4);
            }
            assertTrue(tree.height() >= lastHeight,
                    "a redwood shrank between " + lastHeight + " and " + stage);
            assertTrue(tree.canopy() >= lastCanopy, "its canopy shrank at " + stage);
            lastHeight = tree.height();
            lastCanopy = tree.canopy();
        }
        assertTrue(lastHeight > TreeSpecies.REDWOOD.height() * 0.8,
                "a fully grown redwood is only " + lastHeight + " m");
    }

    /**
     * Measured as hours-to-mature rather than as a stage after a fixed wait:
     * run both long enough and they are both ancient, which says nothing.
     */
    @Test
    void aVigorousGenomeGrowsFasterThanASluggishOne() {
        Grove grove = grove();
        TreeInstance quick = grove.plant(TreeSpecies.BIRCH, 0, 0, 0,
                new TreeGenome(255, 128, 128, 128), null);
        TreeInstance slow = grove.plant(TreeSpecies.BIRCH, 5, 0, 0,
                new TreeGenome(0, 128, 128, 128), null);

        assertTrue(hoursToMature(quick) < hoursToMature(slow),
                "vigour does nothing: both matured in " + hoursToMature(quick) + " hours");
    }

    private static int hoursToMature(TreeInstance tree) {
        int hours = 0;
        while (tree.stage().ordinal() < TreeSpecies.Stage.MATURE.ordinal() && hours < 100000) {
            tree.advance(1);
            hours++;
        }
        return hours;
    }

    @Test
    void theGroveAdvancesEverythingAndReportsWhatChanged() {
        Grove grove = grove();
        for (int i = 0; i < 5; i++) {
            grove.plant(TreeSpecies.MAPLE, i * 4, 0, 0, TreeGenome.average(), "Kara");
        }
        List<TreeInstance> changed = grove.advance(400);
        assertEquals(5, changed.size(), "not every tree grew");
        for (TreeInstance tree : grove.all()) {
            assertNotEquals(TreeSpecies.Stage.SEEDLING, tree.stage());
        }
        assertTrue(grove.advance(0.0001).isEmpty(),
                "a moment's growth reported a stage change");
    }

    /** Only a grown tree makes pollen, or a seedling could father a forest. */
    @Test
    void onlyAMatureTreeCanPollinateOrFruit() {
        Grove grove = grove();
        TreeInstance tree = grove.plant(TreeSpecies.OAK, 0, 0, 0, TreeGenome.average(), null);
        assertTrue(!tree.canPollinate(), "a seedling is making pollen");
        assertTrue(!tree.fruiting(), "a seedling is fruiting");

        while (tree.stage().ordinal() < TreeSpecies.Stage.MATURE.ordinal()) tree.advance(8);
        assertTrue(tree.canPollinate(), "a mature oak cannot pollinate");
    }

    // --- crossing ------------------------------------------------------------------

    @Test
    void theCrossTableIsSymmetricAndNeverCrossesSomethingWithItself() {
        for (TreeSpecies a : TreeSpecies.values()) {
            assertNull(TreeSpecies.hybrid(a, a), a + " crossed with itself makes something");
            for (TreeSpecies b : TreeSpecies.values()) {
                assertEquals(TreeSpecies.hybrid(a, b), TreeSpecies.hybrid(b, a),
                        a + " x " + b + " depends on which one you call the mother");
            }
        }
    }

    @Test
    void everyHybridOnlySpeciesIsReachableFromSomeCross() {
        Map<String, TreeSpecies> crosses = TreeSpecies.crosses();
        Set<TreeSpecies> reachable = new HashSet<>(crosses.values());
        StringBuilder orphans = new StringBuilder();
        for (TreeSpecies species : TreeSpecies.values()) {
            if (species.hybridOnly() && !reachable.contains(species)) {
                orphans.append("\n  ").append(species);
            }
        }
        assertEquals("", orphans.toString(),
                "these species exist and nothing can produce them:" + orphans);
    }

    @Test
    void aHybridOnlySpeciesNeverGrowsWild() {
        for (TreeSpecies species : TreeSpecies.wild()) {
            assertTrue(!species.hybridOnly(),
                    species + " is listed wild and marked hybrid-only");
        }
        long hybrids = List.of(TreeSpecies.values()).stream()
                .filter(TreeSpecies::hybridOnly).count();
        assertTrue(hybrids >= 10, "only " + hybrids + " species are worth breeding for");
    }

    /**
     * The deepest crosses need a hybrid parent, which is what turns breeding
     * from a lookup into a project: you cannot reach a second-generation tree
     * without having grown a first-generation one to maturity first.
     */
    @Test
    void someCrossesNeedAHybridParent() {
        int secondGeneration = 0;
        for (Map.Entry<String, TreeSpecies> entry : TreeSpecies.crosses().entrySet()) {
            String[] parents = entry.getKey().split("\\+");
            if (parents.length != 2) continue;
            TreeSpecies a = TreeSpecies.of(parents[0], null);
            TreeSpecies b = TreeSpecies.of(parents[1], null);
            if (a != null && b != null && (a.hybridOnly() || b.hybridOnly())) {
                secondGeneration++;
            }
        }
        assertTrue(secondGeneration >= 4,
                "only " + secondGeneration + " crosses need a hybrid parent — every tree in "
                        + "the game is one afternoon away");
    }

    @Test
    void pollinatingTwoMatureTreesMakesACross() {
        Grove grove = grove();
        TreeInstance pine = mature(grove, TreeSpecies.PINE, 0, 0);
        TreeInstance amethyst = mature(grove, TreeSpecies.AMETHYST, 3, 0);

        Grove.Cross cross = grove.pollinate(pine.id(), amethyst.id(), new Random(4));
        assertNotNull(cross, "two mature trees in reach produced nothing");
        assertNotNull(cross.species());
        assertNotNull(cross.genome());
    }

    @Test
    void aChildInheritsFromBothParents() {
        Grove grove = grove();
        TreeInstance a = grove.plant(TreeSpecies.PINE, 0, 0, 0,
                new TreeGenome(240, 240, 240, 240), null);
        TreeInstance b = grove.plant(TreeSpecies.AMETHYST, 2, 0, 0,
                new TreeGenome(16, 16, 16, 16), null);
        while (!a.canPollinate() || !b.canPollinate()) {
            a.advance(8);
            b.advance(8);
        }

        Grove.Cross cross = Grove.crossOf(a, b, new Random(9));
        assertNotNull(cross);
        TreeGenome child = cross.genome();
        // Between the two, in every trait: a child of 240 and 16 cannot be 250.
        assertTrue(child.vigour() >= 0 && child.vigour() <= 255);
        assertTrue(child.vigour() > 16 - 40 && child.vigour() < 240 + 40,
                "vigour " + child.vigour() + " is outside what either parent could give");
        assertTrue(child.hue() > 16 - 40 && child.hue() < 240 + 40,
                "hue " + child.hue() + " came from neither parent");
    }

    @Test
    void aTreeWillNotPollinateAcrossTheWholeMap() {
        Grove grove = grove();
        TreeInstance here = mature(grove, TreeSpecies.PINE, 0, 0);
        TreeInstance far = mature(grove, TreeSpecies.AMETHYST, Grove.POLLEN_REACH * 4, 0);

        assertNull(grove.pollinate(here.id(), far.id(), new Random(1)),
                "pollen crossed " + (Grove.POLLEN_REACH * 4) + " metres of open ground");
    }

    @Test
    void aSeedlingHasNoPollenToGive() {
        Grove grove = grove();
        TreeInstance grown = mature(grove, TreeSpecies.PINE, 0, 0);
        TreeInstance sapling = grove.plant(TreeSpecies.AMETHYST, 2, 0, 0,
                TreeGenome.average(), null);
        assertNull(grove.pollinate(grown.id(), sapling.id(), new Random(1)),
                "a seedling fathered a hybrid");
    }

    // --- persistence ----------------------------------------------------------------

    @Test
    void aGroveSurvivesBeingSavedAndLoaded() {
        Grove grove = grove();
        TreeInstance planted = grove.plant(TreeSpecies.MAPLE, 12.5, -7.25, 3.5,
                new TreeGenome(11, 22, 33, 44), "Kara");
        planted.advance(300);

        Grove loaded = grove();
        loaded.load(grove.toMap());

        assertEquals(grove.size(), loaded.size());
        TreeInstance back = loaded.byId(planted.id());
        assertNotNull(back, "the tree's id did not survive the round trip");
        assertEquals(planted.species(), back.species());
        assertEquals(planted.stage(), back.stage());
        assertEquals(planted.x(), back.x(), 1e-6);
        assertEquals(planted.y(), back.y(), 1e-6);
        assertEquals(planted.grownHours(), back.grownHours(), 1e-6);
        assertEquals(planted.genome(), back.genome(), "the genome was lost");
        assertEquals("Kara", back.plantedBy());
    }

    @Test
    void aGenomePacksIntoAnIntAndBackOut() {
        for (int i = 0; i < 256; i += 7) {
            TreeGenome genome = new TreeGenome(i, 255 - i, (i * 3) % 256, (i * 7) % 256);
            assertEquals(genome, TreeGenome.unpack(genome.packed()), "at " + i);
        }
    }

    @Test
    void treesNearAPointAreTheOnesNearThatPoint() {
        Grove grove = grove();
        grove.plant(TreeSpecies.OAK, 0, 0, 0, TreeGenome.average(), null);
        grove.plant(TreeSpecies.OAK, 5, 0, 0, TreeGenome.average(), null);
        grove.plant(TreeSpecies.OAK, 500, 0, 0, TreeGenome.average(), null);

        assertEquals(2, grove.near(0, 0, 10).size());
        assertEquals(3, grove.near(0, 0, 1000).size());
        assertEquals(0, grove.near(-900, 0, 10).size());
    }

    @Test
    void removingATreeRemovesIt() {
        Grove grove = grove();
        TreeInstance tree = grove.plant(TreeSpecies.OAK, 0, 0, 0, TreeGenome.average(), null);
        assertSame(tree, grove.remove(tree.id()));
        assertEquals(0, grove.size());
        assertNull(grove.byId(tree.id()));
        assertNull(grove.remove(tree.id()), "removing it twice is not an error");
    }

    private static TreeInstance mature(Grove grove, TreeSpecies species, double x, double y) {
        TreeInstance tree = grove.plant(species, x, y, 0, TreeGenome.average(), null);
        while (!tree.canPollinate()) tree.advance(12);
        return tree;
    }
}
