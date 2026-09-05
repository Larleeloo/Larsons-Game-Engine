package com.larsons.engine.watch;

import com.larsons.engine.watch.home.HousePlan;
import com.larsons.engine.watch.home.Homestead;
import com.larsons.engine.watch.life.Animal;
import com.larsons.engine.watch.life.AnimalDef;
import com.larsons.engine.watch.life.Diet;
import com.larsons.engine.watch.world.TreeSpecies;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The simulation, run headlessly: the whole loop of walking somewhere, seeing
 * something, writing it down, and setting out food so that it comes back.
 *
 * <p>The game is authoritative on one object — this one — and every client is a
 * view of it, so everything a player can do is a method here and everything
 * here is what a test can drive. There is no scene, no window and no socket in
 * this file.
 */
@Timeout(180)
class WatchGameTest {

    /** A walk on your own, which is a walk with room for exactly one. */
    private static WatchGame game() {
        return new WatchGame(WatchGame.Config.solo("Test Walk"));
    }

    /** A walk with room for a party — the same simulation, a different cap. */
    private static WatchGame party() {
        return new WatchGame(WatchGame.Config.hosted("Test Party", 31L));
    }

    /** Join, then settle: stillness is what makes an animal let you close. */
    private static WatchPlayer settled(WatchGame game, int id, String name) {
        WatchPlayer player = game.join(id, name);
        for (int i = 0; i < 200; i++) {
            game.move(id, player.x(), player.y(), player.z(), 0, 0, false, 1.0 / 20);
            game.tick(1.0 / 20);
        }
        return player;
    }

    /** Run the world until something is close enough to spot, or give up. */
    private static Animal nearest(WatchGame game, int id) {
        WatchPlayer me = game.player(id);
        for (int i = 0; i < 600 && game.animals().isEmpty(); i++) game.tick(0.05);
        Animal best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Animal animal : game.animals()) {
            double d = Math.hypot(animal.x() - me.x(), animal.y() - me.y());
            if (d < bestDistance) {
                bestDistance = d;
                best = animal;
            }
        }
        return best;
    }

    // --- joining and leaving ------------------------------------------------------------

    @Test
    void aWalkHoldsEightPeopleAndNotANinth() {
        WatchGame game = new WatchGame(WatchGame.Config.hosted("Party", 7L));
        for (int i = 1; i <= WatchGame.MAX_PLAYERS; i++) {
            assertNotNull(game.join(i, "Walker " + i), "player " + i + " could not join");
        }
        assertEquals(WatchGame.MAX_PLAYERS, game.players().size());
        assertNull(game.join(99, "Ninth"), "a ninth player joined an eight-player walk");
    }

    @Test
    void leavingFreesTheSpot() {
        WatchGame game = party();
        for (int i = 1; i <= WatchGame.MAX_PLAYERS; i++) game.join(i, "Walker " + i);
        game.leave(3);
        assertEquals(WatchGame.MAX_PLAYERS - 1, game.players().size());
        assertNull(game.player(3));
        assertNotNull(game.join(3, "Back Again"));
    }

    @Test
    void aPlayerStartsOnTheGroundAndNotInIt() {
        WatchGame game = game();
        WatchPlayer player = game.join(1, "Kara");
        assertEquals(game.groundAt(player.x(), player.y()), player.z(), 0.5,
                "the player spawned off the ground");
        assertTrue(player.eyeZ() > player.z(), "the player's eyes are in their feet");
    }

    // --- stillness ----------------------------------------------------------------------

    /**
     * The core verb of the game is <em>hold still</em>, and this is where that
     * lives: an animal judges a player by an apparent distance that stillness
     * multiplies. Running at a bird is the same as being three times closer.
     */
    @Test
    void standingStillMakesYouSeemFurtherAway() {
        WatchGame game = game();
        WatchPlayer player = game.join(1, "Kara");
        double x = player.x(), y = player.y(), z = player.z();

        double running = 0;
        for (int i = 0; i < 40; i++) {
            game.move(1, x + i * 0.4, y, z, 0, 0, false, 0.05);
            game.tick(0.05);
        }
        running = player.apparentDistanceTo(x + 100, y);

        for (int i = 0; i < 400; i++) {
            game.move(1, player.x(), player.y(), player.z(), 0, 0, false, 0.05);
            game.tick(0.05);
        }
        double still = player.apparentDistanceTo(x + 100, y);

        assertTrue(player.stillness() > 0.9, "standing still for 20 s did not settle");
        assertTrue(still > running * 1.5, "holding still (" + still
                + ") is no better than walking at it (" + running + ")");
    }

    @Test
    void crouchingIsStillerThanStanding() {
        WatchGame game = party();
        WatchPlayer standing = settled(game, 1, "Standing");
        WatchPlayer crouching = game.join(2, "Crouching");
        for (int i = 0; i < 400; i++) {
            game.move(2, crouching.x(), crouching.y(), crouching.z(), 0, 0, true, 0.05);
            game.tick(0.05);
        }
        assertTrue(crouching.crouching());
        assertTrue(crouching.apparentDistanceTo(crouching.x() + 100, crouching.y())
                        > standing.apparentDistanceTo(standing.x() + 100, standing.y()),
                "crouching buys nothing");
    }

    // --- spotting -----------------------------------------------------------------------

    @Test
    void spottingSomethingNewWritesItInTheGuide() {
        WatchGame game = game();
        settled(game, 1, "Kara");
        Animal animal = nearest(game, 1);
        assertNotNull(animal, "no animals spawned around a settled player");

        assertEquals(0, game.guide().discovered());
        Spotlight first = game.spot(1, animal.id());
        assertNotNull(first, "spotting an animal did nothing");
        assertTrue(first.discovery(), "the first of a species is not a discovery");
        assertEquals(1, game.guide().discovered());
        assertTrue(game.guide().seen(animal.def().key()));
        assertEquals("Kara", game.guide().firstSighting(animal.def().key()).finder());
    }

    /** Spotting the same species again counts, but is not a new page. */
    @Test
    void spottingTheSameSpeciesTwiceIsNotADiscoveryTwice() {
        WatchGame game = game();
        settled(game, 1, "Kara");
        Animal animal = nearest(game, 1);
        assertNotNull(animal);
        String species = animal.def().key();

        game.spot(1, animal.id());
        int discovered = game.guide().discovered();

        Spotlight again = game.spot(1, animal.id());
        assertNotNull(again, "spotting it a second time did nothing at all");
        assertFalse(again.discovery(), "the second sighting claims to be a discovery");
        assertEquals(discovered, game.guide().discovered());
        assertTrue(game.guide().timesSeen(species) >= 2, "the second sighting was not counted");
    }

    @Test
    void aSpotlightIsPutUpForEveryoneAndFadesOnItsOwn() {
        WatchGame game = party();
        settled(game, 1, "Kara");
        settled(game, 2, "Sam");
        Animal animal = nearest(game, 1);
        assertNotNull(animal);

        Spotlight light = game.spot(1, animal.id());
        assertNotNull(light);
        assertEquals(1, game.spotlights().size(),
                "the highlight is not in the shared list, so nobody else sees it");
        assertEquals("Kara", light.finder());

        for (double t = 0; t < Spotlight.SECONDS + 1; t += 0.1) game.tick(0.1);
        assertTrue(game.spotlights().isEmpty(),
                "the highlight never went out — it was meant to be momentary");
    }

    @Test
    void spottingSomethingThatIsNotThereIsHarmless() {
        WatchGame game = game();
        settled(game, 1, "Kara");
        assertNull(game.spot(1, 999_999L));
        assertNull(game.spot(404, 1L), "an unknown player spotted something");
    }

    // --- animals ------------------------------------------------------------------------

    @Test
    void animalsSpawnAroundAPlayerAndAreBoundedInNumber() {
        WatchGame game = game();
        settled(game, 1, "Kara");
        for (int i = 0; i < 2000; i++) game.tick(0.05);

        List<Animal> animals = game.animals();
        assertTrue(!animals.isEmpty(), "nothing lives in this world");
        assertTrue(animals.size() < 400, animals.size() + " animals around one player");

        WatchPlayer me = game.player(1);
        for (Animal animal : animals) {
            double distance = Math.hypot(animal.x() - me.x(), animal.y() - me.y());
            assertTrue(distance < 400, "an animal is " + Math.round(distance)
                    + " m away and still being simulated");
        }
    }

    /**
     * What lives around you is drawn from where you are, not from the whole
     * book.
     *
     * <p>Asked as locality rather than as "every animal is standing in a biome
     * it lives in", which is not true and should not be: an animal spawns
     * somewhere it belongs and then <em>wanders</em>, and a fox that walks out
     * of the woods into the meadow next door has not done anything wrong. The
     * spawn itself is pinned by
     * {@link #aSpeciesPickedForASpotLivesInThatSpot()}; this is the property
     * that would break if the spawner ever reached past the neighbourhood.
     */
    @Test
    void whatLivesAroundYouIsDrawnFromWhereYouAre() {
        WatchGame game = game();
        settled(game, 1, "Kara");
        for (int i = 0; i < 1200; i++) game.tick(0.05);

        Set<String> species = new HashSet<>();
        for (Animal animal : game.animals()) {
            assertNotNull(com.larsons.engine.watch.life.AnimalRegistry.byKey(
                            animal.def().key()),
                    animal.def().key() + " is alive and not in the registry");
            species.add(animal.def().key());
        }
        assertTrue(!species.isEmpty(), "nothing is alive anywhere near the player");
        assertTrue(species.size() < com.larsons.engine.watch.life.AnimalRegistry.count() / 8,
                species.size() + " different species around one player out of "
                        + com.larsons.engine.watch.life.AnimalRegistry.count()
                        + " — the spawner is drawing from the whole book, so where you walk "
                        + "does not decide what you see");
    }

    /**
     * The species that turns up at a spot is rolled, not derived — spawns are
     * the host's decision and reach every client in a snapshot, so they do not
     * have to agree without being told. What does have to hold is that the roll
     * respects the place.
     */
    @Test
    void aSpeciesPickedForASpotLivesInThatSpot() {
        WatchGame game = game();
        int picked = 0;
        for (int i = 0; i < 200; i++) {
            double x = i * 31.5, y = -i * 17.25;
            AnimalDef def = game.pickSpecies(x, y);
            if (def == null) continue;
            picked++;
            String biome = game.field().biomeAt(x, y).key();
            assertTrue(def.livesIn(biome),
                    def.key() + " was spawned into " + biome + ", where it does not live");
        }
        assertTrue(picked > 100, "only " + picked + " of 200 places had anything living there");
    }

    // --- foraging, lures and cooking -----------------------------------------------------

    /**
     * <b>E is not a slot machine.</b>
     *
     * <p>It had no target and no way to fail: a bush out of reach fell through
     * to a fruiting tree, that to "a seed this biome has", and that to "some
     * material, always" — so holding E anywhere produced an endless stream of
     * things with no relation to what you were looking at.
     */
    @Test
    void pickingHasToHaveSomethingToPickAndDoesNotRepeatForEver() {
        WatchGame game = game();
        WatchPlayer me = settled(game, 1, "Kara");
        int before = me.satchel().total();

        // Twenty presses in one second. Foraging the ground is real, but it is
        // one handful at a time, not one per frame.
        int got = 0;
        for (int i = 0; i < 20; i++) {
            if (game.pick(1) != null) got++;
            game.tick(0.05);
        }
        assertTrue(got <= 3, "twenty presses in a second yielded " + got
                + " separate things — E is still an infinite supply");
        assertTrue(me.satchel().total() > before || got == 0,
                "picking reported something and put nothing in the satchel");
    }

    @Test
    void aBushIsBareAfterYouHavePickedIt() {
        WatchGame game = game();
        WatchPlayer me = settled(game, 1, "Kara");

        // Stand at a bush and face it, then strip it.
        var bush = game.flora().nearestBush(
                com.larsons.engine.watch.world.Flora.ground(game.field()),
                me.x(), me.y(), 400);
        assumeBush(bush);
        double face = Math.atan2(bush.x() - me.x(), -(bush.y() - me.y()));
        game.move(1, bush.x() - 1.0, bush.y(), game.groundAt(bush.x() - 1.0, bush.y()),
                face, 0, false, 1.0 / 60);

        String first = null;
        for (int i = 0; i < 40 && first == null; i++) {
            first = game.pick(1);
            game.tick(1.0);
        }
        if (first == null) return; // nothing pickable here; the test above covers the cap
        int berries = me.satchel().count(first);
        for (int i = 0; i < 10; i++) {
            game.pick(1);
            game.tick(1.0);
        }
        assertTrue(me.satchel().count(first) <= berries + 12,
                "one bush yielded " + (me.satchel().count(first) - berries)
                        + " more handfuls after being picked clean");
    }

    private static void assumeBush(com.larsons.engine.watch.world.Flora.Bush bush) {
        org.junit.jupiter.api.Assumptions.assumeTrue(bush != null,
                "no bush anywhere near the spawn in this world");
    }

    /** A feeder takes two things: the feeder itself, and something to put in it. */
    @Test
    void aLureNeedsAFeederAndFoodAndThenStandsInTheWorld() {
        WatchGame game = game();
        WatchPlayer me = settled(game, 1, "Kara");

        assertNull(game.placeLure(1, "sunflower_seed"),
                "a feeder was made out of thin air");

        me.satchel().add("sunflower_seed", 3);
        assertNull(game.placeLure(1, "sunflower_seed"),
                "seed went out on the ground with nothing to put it in");

        me.satchel().add("feeder", 1);
        Lure lure = game.placeLure(1, "sunflower_seed");
        assertNotNull(lure, "a feeder could not be placed with seed and a feeder in hand");
        assertEquals(1, game.lures().size());
        assertEquals("Kara", lure.placedBy());
        assertEquals(2, me.satchel().count("sunflower_seed"), "the seed was not paid for");
        assertEquals(0, me.satchel().count("feeder"), "the feeder was not paid for");
        assertTrue(lure.active());
    }

    @Test
    void aLureTemptsOnlyWhatEatsWhatIsInIt() {
        Lure seeds = new Lure(1, "sunflower_seed", 0, 0, 0, "Kara", 0);
        Lure fish = new Lure(2, "smoked_fish", 0, 0, 0, "Kara", 0);

        int tempted = 0, ignored = 0;
        for (AnimalDef def : com.larsons.engine.watch.life.AnimalRegistry.all()) {
            boolean eatsSeed = def.diet().eats("sunflower_seed");
            assertEquals(eatsSeed, seeds.tempts(def),
                    def.key() + " (" + def.diet() + ") disagrees about sunflower seed");
            if (eatsSeed) tempted++; else ignored++;
        }
        assertTrue(tempted > 50, "only " + tempted + " species will come to seed");
        assertTrue(ignored > 50, "seed tempts almost everything — diet means nothing");

        assertTrue(fish.tempts(pick(Diet.FISH)), "a fish-eater ignores smoked fish");
        assertFalse(fish.tempts(pick(Diet.NECTAR)), "a hummingbird wants smoked fish");
    }

    @Test
    void aLureRunsOutAndThenSpoils() {
        Lure lure = new Lure(1, "berry_handful", 0, 0, 0, "Kara", 0);
        for (int i = 0; i < Lure.SERVINGS; i++) {
            assertTrue(lure.active(), "the feeder emptied early, at serving " + i);
            lure.consume();
        }
        assertFalse(lure.active(), "the feeder never empties");

        lure.refill();
        assertTrue(lure.active());
        lure.age(Lure.SPOIL_HOURS + 1);
        assertTrue(lure.spoiled(), "food left out for a day is still good");
        assertFalse(lure.active());
    }

    @Test
    void cookingTurnsWhatYouFoundIntoSomethingBetter() {
        WatchGame game = game();
        WatchPlayer me = settled(game, 1, "Kara");

        Recipes.Recipe recipe = Recipes.at(Recipes.Station.FIRE).get(0);
        assertFalse(game.craft(1, recipe, Recipes.Station.FIRE),
                "something was cooked out of nothing");

        // A player joins with a few berries already, so this counts the change
        // rather than the total.
        java.util.Map<String, Integer> before = new java.util.HashMap<>();
        for (var input : recipe.inputs().entrySet()) {
            before.put(input.getKey(), me.satchel().count(input.getKey()));
            me.satchel().add(input.getKey(), input.getValue());
        }
        assertTrue(game.craft(1, recipe, Recipes.Station.FIRE), "the recipe would not cook");
        assertTrue(me.satchel().has(recipe.output()), "the food was not put in the satchel");
        for (var input : recipe.inputs().entrySet()) {
            assertEquals(before.get(input.getKey()), me.satchel().count(input.getKey()),
                    input.getKey() + " was not used up");
        }
    }

    @Test
    void aRecipeCannotBeMadeAtTheWrongStation() {
        WatchGame game = game();
        WatchPlayer me = settled(game, 1, "Kara");
        Recipes.Recipe fireOnly = Recipes.at(Recipes.Station.FIRE).get(0);
        for (var input : fireOnly.inputs().entrySet()) {
            me.satchel().add(input.getKey(), input.getValue() * 2);
        }
        assertFalse(game.craft(1, fireOnly, Recipes.Station.HANDS),
                "a fire recipe was cooked in bare hands");
        assertTrue(game.craft(1, fireOnly, Recipes.Station.FIRE));
    }

    /**
     * Cooking has to be worth doing — for most things, and not for everything.
     *
     * <p>A kingfisher would rather have a live trout than a smoked one, and a
     * thrush would rather have a blackberry off the bush than mashed. Those are
     * the right answers, so this counts diets rather than demanding that every
     * prepared food beat its own ingredients: the fire has to earn its place,
     * without pretending that a jar of something is always better than what was
     * growing there.
     */
    @Test
    void cookingIsWorthDoingForMostAppetites() {
        double[] out = new double[2];
        int cookingWins = 0;
        StringBuilder table = new StringBuilder();
        for (Diet diet : Diet.values()) {
            double bestRaw = 0, bestPrepared = 0;
            for (Forage.Item item : Forage.all()) {
                Forage.draw(item.key(), diet, out);
                if (item.kind() == Forage.Kind.PREPARED) {
                    bestPrepared = Math.max(bestPrepared, out[0]);
                } else {
                    bestRaw = Math.max(bestRaw, out[0]);
                }
            }
            if (bestPrepared > bestRaw) cookingWins++;
            table.append(String.format("%n  %-9s raw %.2f prepared %.2f", diet, bestRaw,
                    bestPrepared));
        }
        assertTrue(cookingWins > Diet.values().length / 2,
                "cooking is only worth doing for " + cookingWins + " of " + Diet.values().length
                        + " appetites, so the fire is scenery:" + table);
    }

    // --- fishing -------------------------------------------------------------------------

    @Test
    void aRodOnlyCastsIntoWater() {
        WatchGame game = game();
        WatchPlayer me = settled(game, 1, "Kara");
        me.satchel().add("rod", 1);

        // Standing on dry land, wherever the spawn happened to be.
        boolean dry = game.groundAt(me.x(), me.y()) > 1.5;
        if (dry) {
            assertFalse(game.castRod(1), "the line went into a hillside");
        }
    }

    @Test
    void fishingRunsThroughItsStagesAndLandsSomething() {
        Fishing rod = new Fishing(11L);
        assertEquals(Fishing.Stage.IDLE, rod.stage());

        var lake = com.larsons.engine.watch.world.WatchBiomes.byKey("wetland_marsh");
        assertTrue(rod.cast(0, 0, -1, lake), "the rod would not cast into a marsh");
        assertEquals(Fishing.Stage.WAITING, rod.stage());

        boolean bit = false;
        for (int i = 0; i < 4000 && !bit; i++) {
            rod.tick(0.05);
            bit = rod.stage() == Fishing.Stage.BITE;
        }
        assertTrue(bit, "nothing bit in three minutes of fishing");
        assertTrue(rod.biteProgress() >= 0 && rod.biteProgress() <= 1);

        AnimalDef caught = rod.strike();
        assertNotNull(caught, "striking on a bite caught nothing");
        assertEquals(Fishing.Stage.LANDED, rod.stage());
        assertNotNull(Fishing.itemFor(caught), "the catch is not an item you can keep");
    }

    @Test
    void strikingWithNoBiteMissesAndStrikingWithNoLineDoesNothing() {
        Fishing rod = new Fishing(3L);
        assertNull(rod.strike(), "a rod that was never cast caught a fish");

        var lake = com.larsons.engine.watch.world.WatchBiomes.byKey("wetland_marsh");
        rod.cast(0, 0, -1, lake);
        assertNull(rod.strike(), "striking before the bite caught something");
        assertEquals(Fishing.Stage.MISSED, rod.stage());
    }

    @Test
    void aBiteDoesNotWaitForYouForEver() {
        Fishing rod = new Fishing(5L);
        var lake = com.larsons.engine.watch.world.WatchBiomes.byKey("wetland_marsh");
        rod.cast(0, 0, -1, lake);
        while (rod.stage() != Fishing.Stage.BITE) rod.tick(0.05);

        for (double t = 0; t <= Fishing.BITE_WINDOW + 0.5; t += 0.05) rod.tick(0.05);
        assertNotEqualsStage(Fishing.Stage.BITE, rod.stage());
    }

    private static void assertNotEqualsStage(Fishing.Stage unwanted, Fishing.Stage actual) {
        assertTrue(unwanted != actual, "the fish waited past the bite window");
    }

    // --- cultivating ----------------------------------------------------------------------

    @Test
    void plantingASeedPutsACropInTheGroundAndTakesTheSeed() {
        WatchGame game = game();
        WatchPlayer me = settled(game, 1, "Kara");

        assertNull(game.plant(1, "sunflower_seed"), "a crop grew from an empty satchel");
        me.satchel().add("sunflower_seed", 2);
        assertNull(game.plant(1, "sunflower_seed"), "a seed was planted with bare hands");

        me.satchel().add("trowel", 1);
        String planted = game.plant(1, "sunflower_seed");
        assertNotNull(planted, "the seed would not go in the ground");
        assertEquals(1, game.crops().size());
        assertEquals(1, me.satchel().count("sunflower_seed"));
        assertEquals(1, me.satchel().count("trowel"), "the trowel was used up");
    }

    @Test
    void aCropGrowsAndThenCanBeHarvested() {
        Cultivation crops = new Cultivation();
        Satchel satchel = new Satchel();
        Cultivation.Crop crop = crops.plant("sunflower_seed", 0, 0, 0, "Kara");
        assertNotNull(crop);

        assertNull(crops.harvest(crop.id(), satchel), "a seedling was harvested");
        crops.advance(Cultivation.GROW_HOURS + 1);

        String yield = crops.harvest(crop.id(), satchel);
        assertNotNull(yield, "a grown crop yielded nothing");
        assertTrue(satchel.total() >= Cultivation.YIELD, "the harvest was not put away");
        assertEquals(0, crops.size(), "the crop is still standing after being harvested");
    }

    @Test
    void aTreeSeedGrowsATreeAndNotACrop() {
        for (TreeSpecies species : TreeSpecies.wild()) {
            String seed = Cultivation.seedFor(species);
            if (seed == null) continue;
            assertEquals(species, Cultivation.treeFor(seed),
                    seed + " does not grow back into the tree it came from");
            assertTrue(Cultivation.plantable(seed), seed + " cannot be planted");
        }
        assertNull(Cultivation.treeFor("smoked_fish"), "a smoked fish grew a tree");
        assertNull(Cultivation.treeFor(null));
    }

    // --- houses ---------------------------------------------------------------------------

    /**
     * Stand somewhere a house will go up.
     *
     * <p>A few spots tried rather than one, because a house needs ground that
     * does not fall away under it and which square metre of a generated world
     * has that is not what these tests are about — see {@code WatchGame.siteFor}.
     * The player is left standing wherever it worked.
     */
    private static boolean findSpot(WatchGame game, int id, HousePlan plan) {
        WatchPlayer me = game.player(id);
        for (int i = 0; i < 24; i++) {
            double x = me.x() + i * 9.0, y = me.y() + i * 5.0;
            game.move(id, x, y, game.field().heightAt(x, y), 0, 0, false, 0.05);
            // Asked without buying: a dry run against the same siting the
            // purchase uses, paid for out of nothing because the book is empty.
            int before = game.guide().points();
            game.guide().reward(plan.price());
            Homestead.Outcome trial = game.buyHome(id, plan, 0);
            if (trial.done()) {
                game.homes().remove(trial.home().id());
                game.guide().refund(plan.price());
                game.guide().spend(game.guide().points() - before);
                return true;
            }
            game.guide().spend(plan.price());
        }
        return false;
    }

    @Test
    void aHouseCostsPointsAndThenStands() {
        WatchGame game = game();
        WatchPlayer me = settled(game, 1, "Kara");
        assertTrue(findSpot(game, 1, HousePlan.CABIN), "nowhere would take a cabin");
        assertEquals(0, game.guide().points(), "the search left points behind");

        Homestead.Outcome broke = game.buyHome(1, HousePlan.CABIN, 0);
        assertFalse(broke.done(), "a cabin was bought with an empty book");
        assertTrue(broke.line().contains("Not enough"),
                "the refusal did not say what was wrong: " + broke.line());
        assertEquals(0, game.homes().size());

        game.guide().reward(HousePlan.CABIN.price());
        Homestead.Outcome bought = game.buyHome(1, HousePlan.CABIN, 0);
        assertTrue(bought.done(), "the cabin would not go up: " + bought.line());
        assertEquals(1, game.homes().size());
        assertEquals(0, game.guide().points(), "the points were not handed over");
        assertEquals("Kara", bought.home().boughtBy());
        // In front of the buyer rather than on top of them.
        assertTrue(bought.home().distanceTo(me.x(), me.y()) > HousePlan.CABIN.halfAlong(),
                "the house landed on the player who bought it");

        // …and taken down again, for half of it back.
        game.move(1, bought.home().x(), bought.home().y(), bought.home().z(),
                0, 0, false, 0.05);
        assertTrue(game.packUp(1).done(), "the cabin would not come down again");
        assertEquals(0, game.homes().size());
        assertEquals(HousePlan.CABIN.price() / 2, game.guide().points(),
                "taking a house down did not put half of it back");
    }

    /**
     * {@code place} is the step after the decision, not the decision — a guest
     * applying what the host already ruled on must not rule again. So the
     * refusal is asked for where it is made.
     */
    @Test
    void twoHousesCannotStandInTheSameSpace() {
        Homestead homes = new Homestead();
        assertNotNull(homes.place(HousePlan.CABIN, 0, 0, 0, 0, 0, 0, "Kara", 0));
        assertTrue(homes.blocked(HousePlan.CABIN, 0, 0),
                "a second cabin fits inside the first");
        assertFalse(homes.blocked(HousePlan.CABIN, 60, 60));

        WatchGame game = game();
        settled(game, 1, "Kara");
        assertTrue(findSpot(game, 1, HousePlan.CABIN), "nowhere would take a cabin");
        game.guide().reward(HousePlan.CABIN.price() * 2);
        assertTrue(game.buyHome(1, HousePlan.CABIN, 0).done());
        Homestead.Outcome second = game.buyHome(1, HousePlan.CABIN, 0);
        assertFalse(second.done(), "a second cabin was put up inside the first");
        assertTrue(second.line().contains("already"), second.line());
        assertEquals(1, game.homes().size());
        assertEquals(HousePlan.CABIN.price(), game.guide().points(),
                "the refused house was charged for anyway");
    }

    /**
     * The catalogue's whole claim: pay more, get more house.
     *
     * <p>Checked as a ladder rather than against named numbers, so that a plan
     * added tomorrow between two existing ones has to keep the promise too.
     */
    @Test
    void everyHouseCostsSomethingAndBiggerHousesCostMore() {
        assertFalse(HousePlan.all().isEmpty());
        HousePlan previous = null;
        for (HousePlan plan : HousePlan.all()) {
            assertTrue(plan.price() > 0, plan + " is free");
            assertTrue(plan.storeys() >= 1, plan + " has no floor to stand on");
            assertTrue(plan.halfAlong() > 0 && plan.halfAcross() > 0,
                    plan + " has no footprint");
            assertFalse(plan.note().isBlank(), plan + " cannot say what it is");
            assertEquals(plan, HousePlan.of(plan.key()), plan + " does not survive its key");
            if (previous != null) {
                assertTrue(plan.price() >= previous.price(),
                        "the catalogue is not in price order at " + plan);
                assertTrue(plan.volume() >= previous.volume(),
                        plan + " costs more than " + previous + " and is smaller");
            }
            previous = plan;
        }
        assertNull(HousePlan.of("a_house_nobody_wrote"));
    }

    @Test
    void thereAreHousesForTheGroundAndHousesForTheTrees() {
        assertFalse(HousePlan.onGround().isEmpty(), "nothing can be put on the ground");
        assertFalse(HousePlan.inTrees().isEmpty(),
                "nothing goes up a tree, so there are no treehouses");
        assertEquals(HousePlan.all().size(),
                HousePlan.onGround().size() + HousePlan.inTrees().size(),
                "a plan is on neither list, or on both");
    }

    // --- saving ------------------------------------------------------------------------

    @Test
    void aWalkSurvivesBeingSavedAndReopened(@TempDir Path dir) {
        WatchGame game = new WatchGame(new WatchGame.Config(4242L, "Morning Walk", 8));
        WatchPlayer me = settled(game, 1, "Kara");
        me.satchel().add("acorn", 5);
        Animal animal = nearest(game, 1);
        if (animal != null) game.spot(1, animal.id());
        game.grove().plant(TreeSpecies.OAK, me.x() + 2, me.y(), me.z(),
                com.larsons.engine.watch.world.TreeGenome.average(), "Kara");

        WatchStore store = new WatchStore(dir.toString());
        store.save(game);
        assertTrue(store.exists("Morning Walk"));
        assertTrue(store.list().contains("Morning Walk"));
        assertEquals(4242L, store.seedOf("Morning Walk"), "the seed did not survive");

        WatchGame reopened = new WatchGame(new WatchGame.Config(4242L, "Morning Walk", 8));
        assertTrue(store.load(reopened), "the saved walk would not load");
        assertEquals(game.guide().discovered(), reopened.guide().discovered());
        assertEquals(1, reopened.grove().size(), "the planted oak was not saved");
        assertNotNull(store.describe("Morning Walk"));
    }

    /**
     * A walk you come back to puts you where you left off, carrying what you
     * had picked up.
     *
     * <p>{@code toMap} had always written the party and {@code load} had never
     * read it, so reopening a walk put you back at the world origin with an
     * empty satchel however far you had walked — in a game whose whole content
     * is walking somewhere and collecting things.
     */
    @Test
    void reopeningAWalkPutsYouBackWhereYouWereWithWhatYouHad(@TempDir Path dir) {
        WatchGame game = new WatchGame(new WatchGame.Config(31L, "Yesterday", 1));
        WatchPlayer me = game.join(1, "Kara");
        double x = 640, y = -480;
        me.moveTo(x, y, game.groundAt(x, y), 1.25, -0.1, false, 1.0 / 60);
        me.satchel().add("acorn", 7);
        me.satchel().add("smoked_fish", 2);

        WatchStore store = new WatchStore(dir.toString());
        store.save(game);

        WatchGame reopened = new WatchGame(new WatchGame.Config(31L, "Yesterday", 1));
        assertTrue(store.load(reopened));
        // A saved walker rests until somebody arrives to be them; joining is
        // what resumes one. See aSavedWalkerWaitsRatherThanOccupyingASeat.
        WatchPlayer back = reopened.join(1, "Kara");
        assertNotNull(back, "the walk came back with nobody able to join it");
        assertEquals(1, reopened.players().size());
        assertEquals("Kara", back.name());
        assertEquals(x, back.x(), 0.01, "you were put back at the wrong place");
        assertEquals(y, back.y(), 0.01, "you were put back at the wrong place");
        assertEquals(7, back.satchel().count("acorn"), "the satchel was emptied");
        assertEquals(2, back.satchel().count("smoked_fish"), "the satchel was emptied");
    }

    /**
     * A saved walker is a place kept, not a body standing in the way.
     *
     * <p>Restoring them straight into the party broke hosting outright: they
     * held a seat nobody was controlling, and — because the server hands out
     * ids from one, and a save's first player <em>is</em> id one — the host's
     * own connection collided with the ghost of itself and was turned away
     * with "this walk is full". Loading a hosted world put you back in the
     * lobby.
     */
    @Test
    void aSavedWalkerWaitsRatherThanOccupyingASeat(@TempDir Path dir) {
        WatchGame first = new WatchGame(new WatchGame.Config(5L, "Shared", 1));
        WatchPlayer me = first.join(1, "Larson");
        me.moveTo(120, -80, first.groundAt(120, -80), 0, 0, false, 1.0 / 60);
        me.satchel().add("acorn", 9);
        WatchStore store = new WatchStore(dir.toString());
        store.save(first);

        // Hosted, as the lobby does it: load the save, then let people connect.
        WatchGame hosted = new WatchGame(WatchGame.Config.hosted("Shared", 5L));
        assertTrue(store.load(hosted));
        assertEquals(0, hosted.players().size(),
                "the save's walker was seated before anybody connected");

        // The very id the server hands out first, which used to collide.
        WatchPlayer back = hosted.join(1, "Larson");
        assertNotNull(back, "the host could not join their own saved walk");
        assertEquals(120, back.x(), 0.01, "the host did not resume where they were");
        assertEquals(9, back.satchel().count("acorn"), "the host lost their satchel");

        WatchPlayer friend = hosted.join(2, "Sam");
        assertNotNull(friend, "a friend could not join");
        assertEquals(2, hosted.players().size());
    }

    /** Somebody who has not been back keeps their place across another save. */
    @Test
    void aWalkerWhoHasNotBeenBackIsNotForgotten(@TempDir Path dir) {
        WatchGame party = new WatchGame(WatchGame.Config.hosted("Party", 5L));
        party.join(1, "Larson").satchel().add("acorn", 4);
        party.join(2, "Sam").satchel().add("trout", 2);
        WatchStore store = new WatchStore(dir.toString());
        store.save(party);

        // Larson plays on alone and saves again.
        WatchGame alone = new WatchGame(WatchGame.Config.hosted("Party", 5L));
        store.load(alone);
        alone.join(1, "Larson");
        store.save(alone);

        // Sam comes back to what they left.
        WatchGame later = new WatchGame(WatchGame.Config.hosted("Party", 5L));
        store.load(later);
        WatchPlayer sam = later.join(1, "Sam");
        assertEquals(2, sam.satchel().count("trout"),
                "Sam's satchel was dropped because they missed a session");
    }

    /** …and a solo walk resumes its one walker whatever they were called. */
    @Test
    void aSoloWalkResumesItsOnlyWalkerByBeingTheOnlyOne(@TempDir Path dir) {
        WatchGame first = new WatchGame(new WatchGame.Config(5L, "Alone", 1));
        first.join(1, "Kara").satchel().add("acorn", 3);
        WatchStore store = new WatchStore(dir.toString());
        store.save(first);

        WatchGame again = new WatchGame(new WatchGame.Config(5L, "Alone", 1));
        store.load(again);
        // The scene joins as "Walker"; there is only one walker resting, so it
        // is unambiguously them.
        WatchPlayer me = again.join(1, "Walker");
        assertEquals(3, me.satchel().count("acorn"),
                "a solo walk did not resume its only walker");
    }

    @Test
    void aWalkThatWasNeverSavedDoesNotLoad(@TempDir Path dir) {
        WatchStore store = new WatchStore(dir.toString());
        assertFalse(store.exists("Nowhere"));
        assertTrue(store.list().isEmpty());
        assertFalse(store.load(new WatchGame(new WatchGame.Config(1L, "Nowhere", 8))));
        assertNull(store.describe("Nowhere"));
    }

    /** Trees keep growing while nobody is playing — that is the point of real hours. */
    @Test
    void treesGrowWhileTheWalkIsPutAway() {
        WatchGame game = new WatchGame(new WatchGame.Config(7L, "Away", 8));
        var tree = game.grove().plant(TreeSpecies.OAK, 0, 0, 0,
                com.larsons.engine.watch.world.TreeGenome.average(), "Kara");
        assertEquals(TreeSpecies.Stage.SEEDLING, tree.stage());

        var saved = game.toMap();
        saved.put("saved", System.currentTimeMillis() - 400L * 3_600_000L);

        WatchGame reopened = new WatchGame(new WatchGame.Config(7L, "Away", 8));
        reopened.load(saved);
        var back = reopened.grove().all().get(0);
        assertTrue(back.stage().ordinal() > TreeSpecies.Stage.SEEDLING.ordinal(),
                "four hundred hours passed and the oak is still a seedling");
    }

    private static AnimalDef pick(Diet diet) {
        for (AnimalDef def : com.larsons.engine.watch.life.AnimalRegistry.all()) {
            if (def.diet() == diet) return def;
        }
        throw new AssertionError("no species eats " + diet);
    }
}
