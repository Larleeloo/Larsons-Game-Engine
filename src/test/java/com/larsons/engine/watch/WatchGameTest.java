package com.larsons.engine.watch;

import com.larsons.engine.watch.build.BuildPiece;
import com.larsons.engine.watch.build.Structure;
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

    // --- building -------------------------------------------------------------------------

    @Test
    void buildingCostsWhatYouForagedAndThenStands() {
        WatchGame game = game();
        WatchPlayer me = settled(game, 1, "Kara");

        assertNull(game.build(1, BuildPiece.FLOOR, 0, false),
                "a floor was built out of nothing");

        for (var cost : BuildPiece.FLOOR.cost().entrySet()) {
            me.satchel().add(cost.getKey(), cost.getValue());
        }
        Structure.Placement placed = game.build(1, BuildPiece.FLOOR, 0, false);
        assertNotNull(placed, "the floor would not go down");
        assertEquals(1, game.structure().size());
        for (var cost : BuildPiece.FLOOR.cost().entrySet()) {
            assertEquals(0, me.satchel().count(cost.getKey()), "the materials were not paid");
        }
    }

    /**
     * {@code place} is the step after the decision, not the decision — a guest
     * applying what the host already ruled on must not rule again. So the
     * refusal is asked for where it is made.
     */
    @Test
    void twoPiecesCannotBeBuiltInTheSameSpace() {
        Structure structure = new Structure();
        assertNotNull(structure.place(BuildPiece.FLOOR, 0, 0, 0, 0, 0, "Kara", 0));
        assertTrue(structure.blocked(BuildPiece.FLOOR, 0, 0, 0, 0),
                "a second floor fits inside the first");
        assertFalse(structure.blocked(BuildPiece.FLOOR, 20, 20, 0, 0));

        WatchGame game = game();
        WatchPlayer me = settled(game, 1, "Kara");
        for (int i = 0; i < 2; i++) {
            for (var cost : BuildPiece.FLOOR.cost().entrySet()) {
                me.satchel().add(cost.getKey(), cost.getValue());
            }
        }
        assertNotNull(game.build(1, BuildPiece.FLOOR, 0, false));
        assertNull(game.build(1, BuildPiece.FLOOR, 0, false),
                "a second floor was built inside the first");
        assertEquals(1, game.structure().size());
    }

    @Test
    void everyPieceHasACostAndSomethingToBuildItOutOf() {
        for (BuildPiece piece : BuildPiece.all()) {
            assertTrue(!piece.cost().isEmpty(), piece + " is free");
            assertNotNull(piece.material(), piece.toString());
            assertTrue(piece.sizeX() > 0 && piece.sizeY() > 0 && piece.sizeZ() > 0,
                    piece + " has no size");
            assertTrue(piece.costLine() != null && !piece.costLine().isBlank(),
                    piece + " cannot say what it costs");
            for (String item : piece.cost().keySet()) {
                assertNotNull(Forage.byKey(item),
                        piece + " is built out of '" + item + "', which is not a thing");
            }
        }
    }

    @Test
    void aTreeHouseAnchorsToATreeAndNotToTheAir() {
        long anchoredPieces = BuildPiece.all().stream().filter(BuildPiece::anchors).count();
        assertTrue(anchoredPieces > 0,
                "nothing can be fixed to a tree, so there are no tree houses");
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
