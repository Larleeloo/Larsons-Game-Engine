package com.larsons.engine.watch;

import com.larsons.engine.watch.world.TreeSpecies;
import com.larsons.engine.watch.world.WatchBiome;
import com.larsons.engine.watch.world.WatchBiomes;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The biome table: twenty places, each of which has to be somewhere a climate
 * can actually land and has to look like itself when you get there.
 *
 * <p>{@link TerrainFieldTest} asks whether the world contains them.
 * This asks whether the table is coherent on its own terms — which is the
 * cheaper question and the one that localises a fault when both fail.
 */
class WatchBiomesTest {

    @Test
    void thereAreTwentyOfThemAndTheirKeysAreUnique() {
        List<WatchBiome> all = WatchBiomes.all();
        assertEquals(20, all.size(), "the brief asks for the seven named biomes plus ten more");
        assertEquals(20, WatchBiomes.count());

        Set<String> keys = new TreeSet<>();
        Set<String> names = new TreeSet<>();
        for (WatchBiome biome : all) {
            assertTrue(keys.add(biome.key()), "duplicate key " + biome.key());
            assertTrue(names.add(biome.displayName()),
                    "duplicate name " + biome.displayName());
        }
    }

    /** The seven the brief named by hand, spelled as it spelled them. */
    @Test
    void theBiomesTheBriefAskedForByNameAreAllThere() {
        for (String key : List.of("pine_forest", "deciduous_forest", "desert", "rainforest",
                "tropics", "mountains", "amethyst_grove")) {
            assertNotNull(WatchBiomes.byKey(key), "no biome keyed " + key);
        }
        // "a fantasy biome with purple leaves"
        WatchBiome fantasy = WatchBiomes.byKey("amethyst_grove");
        assertTrue(fantasy.trees().contains(TreeSpecies.AMETHYST),
                "the fantasy biome has no purple-leaved tree in it");
        assertTrue(fantasy.strangeness() > WatchBiome.ORDINARY,
                "a fantasy biome that any ordinary climate can reach is not one");

        // "tropics (palm trees)"
        assertTrue(WatchBiomes.byKey("tropics").trees().stream()
                        .anyMatch(t -> t == TreeSpecies.PALM || t == TreeSpecies.COCONUT_PALM),
                "the tropics have no palms");
    }

    @Test
    void anUnknownKeyIsNullAndNeverAnException() {
        assertNull(WatchBiomes.byKey("atlantis"));
        assertNull(WatchBiomes.byKey(null));
        assertEquals(WatchBiomes.defaultBiome(), WatchBiomes.of("atlantis"),
                "of() falls back rather than failing, so a stale save still loads");
    }

    /**
     * Every biome is the best fit for <em>some</em> climate — its own, at
     * minimum. A biome that loses at its own coordinates is one the generator
     * can never choose, which is how seven of them went missing the first time.
     */
    @Test
    void everyBiomeWinsAtItsOwnClimate() {
        StringBuilder unreachable = new StringBuilder();
        for (WatchBiome biome : WatchBiomes.all()) {
            WatchBiome winner = WatchBiomes.bestFit(biome.temperature(), biome.humidity(),
                    biome.strangeness());
            if (winner != biome) {
                unreachable.append("\n  ").append(biome.key())
                        .append(" loses its own climate to ").append(winner.key());
            }
        }
        assertEquals("", unreachable.toString(),
                "these biomes can never be chosen:" + unreachable);
    }

    /** And the index form agrees with the object form, since the mesher uses it. */
    @Test
    void bestFitIndexAgreesWithBestFit() {
        for (int t = 0; t <= 100; t += 7) {
            for (int h = 0; h <= 100; h += 7) {
                for (int s : new int[]{0, 12, 50, 90}) {
                    int index = WatchBiomes.bestFitIndex(t, h, s);
                    assertEquals(WatchBiomes.bestFit(t, h, s), WatchBiomes.all().get(index),
                            "at " + t + "/" + h + "/" + s);
                }
            }
        }
    }

    /**
     * The fantasy biomes are rare because they need a climate axis nothing else
     * uses. If an ordinary temperate valley could be an amethyst grove, the
     * grove would stop being worth walking to.
     */
    @Test
    void strangeBiomesAreOutOfReachOfOrdinaryWeather() {
        for (WatchBiome biome : WatchBiomes.all()) {
            if (biome.strangeness() <= WatchBiome.ORDINARY) continue;
            WatchBiome atOrdinary = WatchBiomes.bestFit(biome.temperature(),
                    biome.humidity(), WatchBiome.ORDINARY);
            assertTrue(atOrdinary.strangeness() <= WatchBiome.ORDINARY,
                    biome.key() + " is reachable at ordinary strangeness");
        }
    }

    /** Anything the world draws has to be filled in, or a chunk meshes a null. */
    @Test
    void everyBiomeIsFullyFurnished() {
        for (WatchBiome biome : WatchBiomes.all()) {
            String at = biome.key();
            assertNotNull(biome.displayName(), at);
            assertTrue(biome.blurb() != null && !biome.blurb().isBlank(),
                    at + " has no blurb, and the guide prints one");
            assertNotNull(biome.surface(), at + " surface");
            assertNotNull(biome.surfaceAlt(), at + " alternate surface");
            assertNotNull(biome.cliff(), at + " cliff");
            assertNotNull(biome.shore(), at + " shore");
            assertNotNull(biome.trail(), at + " trail");
            assertTrue(biome.weight() > 0, at + " has no weight and can never win");
            assertTrue(biome.relief() >= 0, at + " has negative relief");
            assertTrue(biome.grassMin() <= biome.grassMax(),
                    at + " grass is longer at its shortest than at its longest");
            assertTrue(biome.grassMax() <= 2.5, at + " grass is taller than a player");
            assertTrue(biome.treeDensity() >= 0 && biome.treeDensity() <= 1, at + " trees");
        }
    }

    /**
     * Grass of varying lengths was asked for, and a table where every biome
     * says the same thing would satisfy the code and not the request.
     */
    @Test
    void grassLengthActuallyVariesBetweenBiomes() {
        Set<String> bands = new HashSet<>();
        double shortest = Double.MAX_VALUE, longest = 0;
        for (WatchBiome biome : WatchBiomes.all()) {
            bands.add(biome.grassMin() + ":" + biome.grassMax());
            shortest = Math.min(shortest, biome.grassMin());
            longest = Math.max(longest, biome.grassMax());
        }
        assertTrue(bands.size() >= 8,
                "only " + bands.size() + " distinct grass bands across twenty biomes");
        assertTrue(longest > shortest * 3,
                "the longest grass (" + longest + " m) is not meaningfully longer than the "
                        + "shortest (" + shortest + " m)");
    }

    /** A forest with no trees in its list would mesh an empty forest. */
    @Test
    void anyBiomeThatGrowsTreesNamesSome() {
        for (WatchBiome biome : WatchBiomes.all()) {
            if (biome.treeDensity() <= 0) continue;
            assertTrue(!biome.trees().isEmpty(),
                    biome.key() + " has a tree density and no species to grow");
            for (TreeSpecies species : biome.trees()) {
                assertTrue(!species.hybridOnly(), biome.key() + " grows " + species
                        + " wild, but that species only exists as a cross");
            }
        }
    }
}
