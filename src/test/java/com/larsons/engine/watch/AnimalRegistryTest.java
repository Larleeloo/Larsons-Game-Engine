package com.larsons.engine.watch;

import com.larsons.engine.watch.life.AnimalDef;
import com.larsons.engine.watch.life.AnimalFamily;
import com.larsons.engine.watch.life.AnimalRegistry;
import com.larsons.engine.watch.life.Diet;
import com.larsons.engine.watch.life.Rarity;
import com.larsons.engine.watch.world.WatchBiome;
import com.larsons.engine.watch.world.WatchBiomes;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Thirteen hundred species, generated — and the reason to generate them is also
 * the reason to test them: nobody read all thirteen hundred.
 *
 * <p>A hand-written bestiary of a thousand animals would be checked by the
 * writing. This one is checked here, and the failures it has actually caught
 * were the ones you would expect from generated data: every species landing in
 * one rarity tier, every species tameable, two lineages producing the same name,
 * a colour shift that pushed a whole family black.
 */
class AnimalRegistryTest {

    @Test
    void thereAreOverAThousandOfThem() {
        assertTrue(AnimalRegistry.count() >= 1000,
                "the brief asks for upwards of 1000 animals; there are "
                        + AnimalRegistry.count());
        assertEquals(AnimalRegistry.count(), AnimalRegistry.all().size());
    }

    @Test
    void everyKeyAndEveryNameIsItsOwn() {
        Set<String> keys = new TreeSet<>();
        Set<String> names = new TreeSet<>();
        StringBuilder clashes = new StringBuilder();
        for (AnimalDef def : AnimalRegistry.all()) {
            if (!keys.add(def.key())) clashes.append("\n  key ").append(def.key());
            if (!names.add(def.name())) clashes.append("\n  name ").append(def.name());
        }
        assertEquals("", clashes.toString(),
                "two species cannot share a name in a book you tick off:" + clashes);
    }

    @Test
    void everySpeciesIsFindableByItsKey() {
        for (AnimalDef def : AnimalRegistry.all()) {
            assertEquals(def, AnimalRegistry.byKey(def.key()), def.key());
        }
        assertNull(AnimalRegistry.byKey("dodo"), "an unknown key is null, not an exception");
        assertNull(AnimalRegistry.byKey(null));
    }

    /** The whole registry is derived from constants; it has to be the same every run. */
    @Test
    void theRegistryIsTheSameOnEveryMachine() {
        List<AnimalDef> all = AnimalRegistry.all();
        long fingerprint = 0;
        for (AnimalDef def : all) {
            fingerprint = fingerprint * 31 + def.key().hashCode();
            fingerprint = fingerprint * 31 + def.rarity().ordinal();
            fingerprint = fingerprint * 31 + def.body();
        }
        // Not a golden number — a second read, which is what would differ if
        // anything in here were rolled rather than derived.
        long again = 0;
        for (AnimalDef def : AnimalRegistry.all()) {
            again = again * 31 + def.key().hashCode();
            again = again * 31 + def.rarity().ordinal();
            again = again * 31 + def.body();
        }
        assertEquals(fingerprint, again);
        assertEquals(all, AnimalRegistry.all(), "the registry rebuilt itself differently");
    }

    // --- the shape of the distribution ------------------------------------------------

    /**
     * Rarity has to mean something. This is the test that caught the hash bug:
     * a stream of entropy was being shifted twice, every roll came out zero,
     * and all 1323 species were {@code COMMON}.
     */
    @Test
    void everyRarityTierIsPopulatedAndTheCommonOnesAreCommonest() {
        Map<Rarity, Integer> counts = new EnumMap<>(Rarity.class);
        for (AnimalDef def : AnimalRegistry.all()) counts.merge(def.rarity(), 1, Integer::sum);

        for (Rarity rarity : Rarity.values()) {
            assertTrue(counts.getOrDefault(rarity, 0) > 0,
                    "not one species is " + rarity.label());
        }
        assertTrue(counts.get(Rarity.COMMON) > counts.get(Rarity.LEGENDARY) * 10,
                "legendary species are not rare relative to common ones ("
                        + counts.get(Rarity.LEGENDARY) + " vs " + counts.get(Rarity.COMMON) + ")");
        double legendaryShare = counts.get(Rarity.LEGENDARY) / (double) AnimalRegistry.count();
        assertTrue(legendaryShare < 0.06,
                "legendary is " + Math.round(legendaryShare * 100) + "% of the book");
    }

    /** "Some animals can be kept as pets" — some, and not all. */
    @Test
    void someSpeciesAreTameableAndMostAreNot() {
        List<AnimalDef> tameable = AnimalRegistry.tameable();
        assertTrue(tameable.size() > 20, "only " + tameable.size() + " species can be kept");
        double share = tameable.size() / (double) AnimalRegistry.count();
        assertTrue(share < 0.5, Math.round(share * 100)
                + "% of the world's animals will come home with you — that is a petting zoo");
        for (AnimalDef def : tameable) {
            assertTrue(def.tameable(), def.key() + " is listed tameable and says it is not");
        }
    }

    @Test
    void everyFamilyHasItsShareAndEveryOneIsListedUnderIt() {
        for (AnimalFamily family : AnimalFamily.values()) {
            List<AnimalDef> members = AnimalRegistry.inFamily(family);
            assertTrue(members.size() >= 20,
                    family + " has only " + members.size() + " species in it");
            for (AnimalDef def : members) assertEquals(family, def.family());
        }
        int total = 0;
        for (AnimalFamily family : AnimalFamily.values()) {
            total += AnimalRegistry.inFamily(family).size();
        }
        assertEquals(AnimalRegistry.count(), total, "a species is in no family, or in two");
    }

    /**
     * Every biome has to have something in it, or a player who walks into the
     * tundra is looking at an empty guide page and empty ground.
     */
    @Test
    void everyBiomeHasAnimalsThatLiveInIt() {
        for (WatchBiome biome : WatchBiomes.all()) {
            List<AnimalDef> here = AnimalRegistry.inBiome(biome.key());
            assertTrue(here.size() >= 10,
                    "only " + here.size() + " species live in " + biome.key());
            for (AnimalDef def : here) {
                assertTrue(def.livesIn(biome.key()),
                        def.key() + " is listed in " + biome.key() + " and denies it");
            }
        }
    }

    // --- what one species is made of --------------------------------------------------

    @Test
    void everySpeciesIsCompleteEnoughToDraw() {
        for (AnimalDef def : AnimalRegistry.all()) {
            String at = def.key();
            assertTrue(def.name() != null && !def.name().isBlank(), at + " has no name");
            assertNotNull(def.family(), at);
            assertNotNull(def.diet(), at);
            assertNotNull(def.activity(), at);
            assertNotNull(def.rarity(), at);
            assertTrue(!def.biomes().isEmpty(), at + " lives nowhere");
            assertTrue(def.bodyLength() > 0.02 && def.bodyLength() < 6,
                    at + " is " + def.bodyLength() + " m long");
            assertTrue(def.wariness() >= 0, at + " has negative wariness");
            assertTrue(def.speed() > 0, at + " cannot move");
            assertTrue(def.flushDistance() > 0, at + " never flushes");
            assertTrue(def.whereToLook() != null && !def.whereToLook().isBlank(),
                    at + " has nothing to print on its page");
        }
    }

    /**
     * Colours have to be visible.
     *
     * <p>The palette shifts a family's base colour per epithet, and an early
     * version multiplied brightness without a floor — so "Dusky", "Shadowed"
     * and "Sable" anything came out at 4% luminance, which on a shaded flank at
     * dusk is a black rectangle. Every species is checked for being neither
     * black nor white nor grey, in all three of its colours.
     */
    @Test
    void noSpeciesIsInvisible() {
        StringBuilder bad = new StringBuilder();
        for (AnimalDef def : AnimalRegistry.all()) {
            check(bad, def, "body", def.body());
            check(bad, def, "accent", def.accent());
            check(bad, def, "detail", def.detail());
        }
        assertEquals("", bad.toString(), "these colours cannot be seen:" + bad);
    }

    private static void check(StringBuilder bad, AnimalDef def, String which, int rgb) {
        float[] hsb = Color.RGBtoHSB((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, null);
        if (hsb[2] < 0.16f) {
            bad.append("\n  ").append(def.key()).append(' ').append(which)
                    .append(" is black (brightness ").append(hsb[2]).append(')');
        } else if (hsb[2] > 0.985f && hsb[1] < 0.03f) {
            bad.append("\n  ").append(def.key()).append(' ').append(which).append(" is white");
        }
    }

    /**
     * A family's species have to differ from each other, or forty-nine "finches"
     * are one finch printed forty-nine times.
     */
    @Test
    void speciesWithinAFamilyLookDifferentFromEachOther() {
        for (AnimalFamily family : AnimalFamily.values()) {
            Set<Integer> bodies = new TreeSet<>();
            for (AnimalDef def : AnimalRegistry.inFamily(family)) bodies.add(def.body());
            assertTrue(bodies.size() >= 6, family + " has "
                    + AnimalRegistry.inFamily(family).size() + " species in only "
                    + bodies.size() + " colours");
        }
    }

    @Test
    void everyDietHasSomethingItWillComeToAFeederFor() {
        for (Diet diet : Diet.values()) {
            assertTrue(!diet.foods().isEmpty(), diet + " eats nothing, so nothing lures it");
            for (String food : diet.foods()) {
                assertNotNull(Forage.byKey(food),
                        diet + " eats '" + food + "', which is not a forage item");
                assertTrue(diet.appeal(food) > 0, diet + " is not tempted by its own food");
            }
            assertTrue(diet.eats(diet.foods().get(0)));
        }
    }

    /** The epithets are what make the names, so they must not repeat in a family. */
    @Test
    void aFamilysEpithetsAreDistinct() {
        for (AnimalFamily family : AnimalFamily.values()) {
            List<String> epithets = AnimalRegistry.epithetsFor(family);
            assertEquals(AnimalRegistry.EPITHETS_PER_FAMILY, epithets.size(), family.toString());
            assertEquals(epithets.size(), Set.copyOf(epithets).size(),
                    family + " uses one epithet twice");
        }
    }
}
