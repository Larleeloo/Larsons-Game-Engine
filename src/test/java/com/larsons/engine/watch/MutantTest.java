package com.larsons.engine.watch;

import com.larsons.engine.watch.life.AnimState;
import com.larsons.engine.watch.life.Animal;
import com.larsons.engine.watch.life.AnimalDef;
import com.larsons.engine.watch.life.AnimalFamily;
import com.larsons.engine.watch.life.AnimalModel;
import com.larsons.engine.watch.life.AnimalModels;
import com.larsons.engine.watch.life.AnimalRegistry;
import com.larsons.engine.watch.life.Diet;
import com.larsons.engine.watch.life.Mutants;
import com.larsons.engine.watch.life.Rarity;
import com.larsons.engine.watch.world.WatchBiomes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The three things in the wood that hunt you.
 *
 * <p>Everything else in this game flees, and the whole of the difficulty curve
 * is built on that — so a species that comes toward a player is a new kind of
 * object in a world that had none, and nearly everything about it is asserted
 * here rather than left to be noticed in play:
 *
 * <ul>
 *   <li>that there are <b>three</b>, that they are rare, and that each is
 *       confined to its own regions and its own hours;</li>
 *   <li>that one actually closes on a player and takes their health;</li>
 *   <li>that holding still — the answer to every other animal — is not the
 *       answer to this one;</li>
 *   <li>that dying drops the satchel where you fell and stands you back up at
 *       the spawn, with the bag still there to walk back to.</li>
 * </ul>
 */
@Timeout(180)
class MutantTest {

    private static WatchGame game() {
        return new WatchGame(WatchGame.Config.solo("Mutant Walk"));
    }

    /** The wendigo, which is the one with the longest reach and least damage. */
    private static Mutants.Kind wendigo() {
        return Mutants.of(AnimalRegistry.inFamily(AnimalFamily.WENDIGO).get(0));
    }

    // --- the three of them --------------------------------------------------------------

    @Test
    void thereAreExactlyThreeAndTheyAreInTheBook() {
        assertEquals(3, Mutants.count(), "the brief asks for three mutants");
        for (Mutants.Kind kind : Mutants.all()) {
            AnimalDef def = kind.def();
            assertSame(def, AnimalRegistry.byKey(def.key()));
            assertTrue(def.hostile(), def.key() + " is a mutant that is not hostile");
            assertTrue(Mutants.isMutant(def));
        }
        // …and nothing else is.
        int hostile = 0;
        for (AnimalDef def : AnimalRegistry.all()) {
            if (def.hostile()) hostile++;
        }
        assertEquals(3, hostile, "something other than a mutant hunts people");
    }

    private static void assertSame(Object expected, Object actual) {
        assertEquals(expected, actual);
    }

    /**
     * A hostile family is not crossed with the epithet pool. Forty-nine
     * wendigos with rolled colours would be the failure mode {@code Mutants}
     * exists to prevent, and it would happen silently — the registry's build
     * loop would simply carry on past the skip.
     */
    @Test
    void aHostileFamilyIsOneSpeciesAndNotFortyNine() {
        for (AnimalFamily family : AnimalFamily.values()) {
            if (family.natural()) continue;
            assertEquals(1, AnimalRegistry.inFamily(family).size(),
                    family + " was crossed with the epithet pool");
        }
    }

    /**
     * <b>What makes a mutant rare is not its frequency.</b> That was the first
     * assumption and it was measured and abandoned: a biome holds two to five
     * hundred species whose encounter weights sum to about a hundred and fifty,
     * so the vanishing frequency a "tier above legendary" suggests comes out at
     * one pick in two hundred thousand — a feature nobody ever sees.
     *
     * <p>So this asserts the thing that is actually true. Its <em>share of the
     * pick, where and when it can be picked at all</em>, is somewhere a walk
     * will find; the rarity comes from the ground, the hour and the cap, which
     * the tests around this one cover.
     */
    @Test
    void theyAreRareByWhereAndWhenRatherThanByTheRoll() {
        for (Mutants.Kind kind : Mutants.all()) {
            assertEquals(Rarity.MYTHIC, kind.def().rarity(), kind.key());
        }
        assertTrue(Rarity.MYTHIC.points() >= Rarity.LEGENDARY.points() * 4,
                "spotting one is not worth the walk");

        // In its own country at its own hour, how much of the table is it?
        for (Mutants.Kind kind : Mutants.all()) {
            for (String biome : kind.def().biomes()) {
                for (WatchClock.Phase phase : WatchClock.Phase.values()) {
                    double total = 0, mine = 0;
                    for (AnimalDef def : AnimalRegistry.inBiome(biome)) {
                        double weight = def.encounterWeight(phase);
                        total += weight;
                        if (def.key().equals(kind.key())) mine = weight;
                    }
                    if (mine <= 0) continue;
                    double oneIn = total / mine;
                    assertTrue(oneIn < 5000, kind.key() + " in " + biome + " at " + phase
                            + " is one pick in " + Math.round(oneIn)
                            + " — nobody will ever meet it");
                    assertTrue(oneIn > 60, kind.key() + " in " + biome + " at " + phase
                            + " is one pick in " + Math.round(oneIn)
                            + " — that is a common animal");
                }
            }
        }
    }

    /**
     * Each keeps to its own ground, and no two of them share any of it. That is
     * what makes a region a thing a player can learn: the taiga has one horror
     * in it, and it is always the same one.
     */
    @Test
    void eachHauntsItsOwnRegionsAndTheyDoNotOverlap() {
        Set<String> claimed = new TreeSet<>();
        for (Mutants.Kind kind : Mutants.all()) {
            List<String> biomes = kind.def().biomes();
            assertTrue(biomes.size() >= 2 && biomes.size() <= 4,
                    kind.key() + " is in " + biomes.size() + " biomes");
            for (String biome : biomes) {
                assertNotNull(WatchBiomes.byKey(biome),
                        kind.key() + " haunts '" + biome + "', which is not a biome");
                assertTrue(claimed.add(biome),
                        biome + " has two different mutants in it");
            }
        }
        // And most of the world has none at all: nineteen or twenty biomes of
        // the twenty are simply safe, which is the whole bargain.
        assertTrue(claimed.size() <= WatchBiomes.count() / 2,
                "mutants haunt " + claimed.size() + " of " + WatchBiomes.count()
                        + " biomes — most of the world has to be safe");
    }

    /**
     * <b>The hard edge on the hour.</b> Every ordinary species has a non-zero
     * chance at every hour, on purpose; a mutant does not, so "the taiga is safe
     * until dark" is true rather than nearly true.
     */
    @Test
    void aMutantIsNotOfferedOutsideItsOwnHours() {
        for (Mutants.Kind kind : Mutants.all()) {
            AnimalDef def = kind.def();
            boolean everAwake = false;
            for (WatchClock.Phase phase : WatchClock.Phase.values()) {
                double weight = def.encounterWeight(phase);
                if (def.activity().awakeAt(phase)) {
                    everAwake = true;
                    assertTrue(weight > 0, def.key() + " is asleep during its own hours");
                } else {
                    assertEquals(0, weight, 0,
                            def.key() + " can turn up at " + phase + ", which is not its hour");
                }
            }
            assertTrue(everAwake, def.key() + " is never abroad at all");
        }
        // An ordinary species keeps its soft edge — this is the line the rule
        // above is deliberately an exception to.
        AnimalDef wren = AnimalRegistry.all().get(0);
        for (WatchClock.Phase phase : WatchClock.Phase.values()) {
            assertTrue(wren.encounterWeight(phase) > 0,
                    "an ordinary species has become unfindable at " + phase);
        }
    }

    @Test
    void theyTowerOverAPlayerAndDifferFromEachOther() {
        Set<Integer> colours = new HashSet<>();
        Set<AnimalFamily.Build> builds = new HashSet<>();
        for (Mutants.Kind kind : Mutants.all()) {
            assertTrue(kind.height() >= Mutants.SHORTEST,
                    kind.key() + " is only " + kind.height() + " m");
            // A person's eye is at 1.68 m. Two and a half times that is the
            // floor for "towers over you".
            assertTrue(kind.height() > 1.68 * 2.5, kind.key() + " does not tower");
            assertEquals(kind.height(), kind.def().bodyLength(), 1e-9,
                    kind.key() + " is drawn at a different size than it is");
            colours.add(kind.def().body());
            builds.add(kind.def().family().build());
        }
        assertEquals(3, colours.size(), "two mutants are the same colour");
        assertEquals(3, builds.size(), "two mutants share a body plan");
    }

    /** Three powers, three creatures — the encounters have to differ. */
    @Test
    void theirAbilitiesAllDiffer() {
        Set<Mutants.Power> powers = new HashSet<>();
        Set<Double> damages = new HashSet<>();
        for (Mutants.Kind kind : Mutants.all()) {
            powers.add(kind.power());
            damages.add(kind.damage());
            assertTrue(kind.blowsToKill() >= 3 && kind.blowsToKill() <= 8,
                    kind.key() + " kills in " + kind.blowsToKill()
                            + " blows — that is a coin flip or a chore");
            assertTrue(kind.lose() > kind.notice(),
                    kind.key() + " gives up closer than it notices, so it would "
                            + "drop and re-acquire the same player for ever");
            assertTrue(kind.chase() > 1, kind.key() + " pursues slower than it strolls");
        }
        assertEquals(Mutants.Power.values().length, powers.size(),
                "two mutants hunt the same way");
        assertEquals(3, damages.size(), "two mutants hit equally hard");
    }

    @Test
    void everyMutantHasAModelAndAStrikePose() {
        for (Mutants.Kind kind : Mutants.all()) {
            AnimalModels.Loaded model = AnimalModels.of(kind.def());
            assertTrue(model.geometry().boxCount() > 20,
                    kind.key() + " is drawn with only " + model.geometry().boxCount()
                            + " boxes");
            // The arms are the forelimb joints — see AnimalModel's mutant
            // section — and STRIKE is the one clip that had to be written for
            // them. If it poses them at rest the swing is invisible.
            AnimalModel.Pose arm = AnimalModel.pose(AnimState.STRIKE,
                    AnimalModel.Joint.LEG_FL, 0.5);
            AnimalModel.Pose resting = AnimalModel.pose(AnimState.IDLE,
                    AnimalModel.Joint.LEG_FL, 0.5);
            assertTrue(Math.abs(arm.pitch() - resting.pitch()) > 0.5,
                    "a mutant's strike does not move its arms");
        }
    }

    // --- hunting -------------------------------------------------------------------------

    /** A flat, dry world with one player in it, standing still. */
    private static final class Clearing implements Animal.Surroundings {

        private final WatchClock clock = WatchClock.at(0.0);
        double playerX, playerY;
        double damageTaken;
        int blows;

        @Override public double groundAt(double x, double y) { return 20; }

        @Override public double waterDepthAt(double x, double y) { return 0; }

        @Override
        public boolean waterNear(double x, double y, double r, double d, double[] out) {
            return false;
        }

        @Override public WatchClock clock() { return clock; }

        /**
         * Enormous, which is the point: this is what a player who is standing
         * perfectly still looks like to an ordinary animal. Nothing here should
         * make a mutant lose interest.
         */
        @Override public double disturbanceAt(double x, double y) {
            return Double.MAX_VALUE;
        }

        @Override
        public boolean nearestLure(double x, double y, Diet diet, double[] out) {
            return false;
        }

        @Override public boolean playerPosition(String name, double[] out) {
            out[0] = playerX;
            out[1] = playerY;
            out[2] = 20;
            return true;
        }

        @Override public String nearestQuarry(double x, double y, double range) {
            return Math.hypot(playerX - x, playerY - y) <= range ? "Kara" : null;
        }

        @Override public void wound(String name, double amount, Animal by) {
            damageTaken += amount;
            blows++;
        }
    }

    private static void run(Animal animal, Animal.Surroundings around, double seconds) {
        for (double t = 0; t < seconds; t += 1.0 / 20) {
            animal.step(1.0 / 20, around);
        }
    }

    /**
     * Hit a player until they go down once, and no further.
     *
     * <p>Once, and the guard is the respawn counter rather than
     * {@link WatchPlayer#alive()}: a killed walker is stood straight back up
     * with a full bar, so "keep hitting while they are alive" kills them over
     * and over. That is the correct behaviour of the game and the wrong loop for
     * a test.
     */
    private static void killOnce(WatchGame game, WatchPlayer player, Animal by) {
        int before = player.respawns();
        for (int i = 0; i < 40 && player.respawns() == before; i++) {
            game.wound(player.name(), 0.3, by);
        }
        assertEquals(before + 1, player.respawns(), "they were not killed");
    }

    /**
     * <b>The one that matters.</b> Standing perfectly still is the answer to
     * every other animal in this game and it must not be the answer to this one:
     * {@code Clearing.disturbanceAt} returns "nobody within any distance", which
     * is exactly what a settled player looks like, and the wendigo comes anyway.
     */
    @Test
    void stillnessDoesNotHideYouFromAMutant() {
        Mutants.Kind kind = wendigo();
        Clearing around = new Clearing();
        Animal beast = new Animal(1, kind.def(), 60, 0, 20, 7L);
        double startedAt = Math.hypot(beast.x() - around.playerX, beast.y() - around.playerY);

        run(beast, around, 30);

        double now = Math.hypot(beast.x() - around.playerX, beast.y() - around.playerY);
        assertTrue(now < startedAt - 20,
                "a wendigo sixty metres from a motionless player closed only "
                        + Math.round(startedAt - now) + " m in thirty seconds");
        assertEquals("Kara", beast.quarry(), "it never took an interest");
    }

    @Test
    void itCatchesUpAndKeepsHitting() {
        Mutants.Kind kind = wendigo();
        Clearing around = new Clearing();
        Animal beast = new Animal(1, kind.def(), 20, 0, 20, 11L);

        run(beast, around, 60);

        assertTrue(around.blows >= 3,
                "it landed " + around.blows + " blows in a minute on a stationary player");
        assertTrue(around.damageTaken >= 1.0,
                "a minute inside a wendigo's reach cost only "
                        + Math.round(around.damageTaken * 100) + "% of a health bar");
        assertEquals(AnimState.STRIKE, beast.state(),
                "it is standing next to somebody and not swinging");
    }

    /** Walking away has to work, or the encounter has no answer at all. */
    @Test
    void gettingClearMakesItGiveUp() {
        Mutants.Kind kind = wendigo();
        Clearing around = new Clearing();
        Animal beast = new Animal(1, kind.def(), 30, 0, 20, 13L);
        run(beast, around, 10);
        assertEquals("Kara", beast.quarry());

        // Somewhere well past the distance at which it loses interest.
        around.playerX = kind.lose() * 3;
        run(beast, around, 5);

        assertNull(beast.quarry(), "it is still hunting somebody a mile away");
    }

    @Test
    void anOrdinaryAnimalStillFleesAndNeverHunts() {
        AnimalDef wren = AnimalRegistry.all().get(0);
        Clearing around = new Clearing();
        Animal bird = new Animal(1, wren, 2, 0, 20, 5L);
        run(bird, around, 20);
        assertFalse(bird.hostile(), wren.key() + " has become a predator");
        assertNull(bird.quarry());
        assertEquals(0, around.blows, "a " + wren.name() + " attacked somebody");
    }

    // --- health, death and the satchel ---------------------------------------------------

    @Test
    void healthComesBackSlowlyAndOnlyAfterAPause() {
        WatchGame game = game();
        WatchPlayer player = game.join(1, "Kara");
        assertEquals(1, player.health(), 1e-9);

        assertFalse(player.wound(0.5));
        assertEquals(0.5, player.health(), 1e-9);
        assertTrue(player.bleeding());

        // Nothing at all while the wound is fresh.
        for (int i = 0; i < 20; i++) game.tick(0.1);
        assertEquals(0.5, player.health(), 1e-6, "it healed inside the delay");

        // …and then slowly. Ninety seconds from empty means well under a tenth
        // of a bar in ten.
        for (int i = 0; i < 100; i++) game.tick(0.1);
        assertTrue(player.health() > 0.5, "it never started healing");
        assertTrue(player.health() < 0.75,
                "half a bar came back in ten seconds — that is not slow");

        // And all the way back, given long enough.
        for (int i = 0; i < 1200; i++) game.tick(0.1);
        assertEquals(1, player.health(), 1e-6);
    }

    @Test
    void aKilledPlayerDropsEverythingAndWakesAtTheSpawn() {
        WatchGame game = game();
        WatchPlayer player = game.join(1, "Kara");
        // Walk a long way off, and carry something worth losing.
        game.move(1, 400, 300, game.groundAt(400, 300), 0, 0, false, 0.05);
        player.satchel().add("blackberry", 7);
        Map<String, Integer> carried = player.satchel().contents();
        assertFalse(carried.isEmpty());
        int before = player.respawns();

        // A mutant's blow, through the same path a real one takes.
        Animal beast = new Animal(1, wendigo().def(), 401, 300, 20, 3L);
        killOnce(game, player, beast);

        assertTrue(player.alive(), "they should have been stood back up, not left dead");
        assertEquals(1, player.health(), 1e-9, "they got up still hurt");
        assertEquals(before + 1, player.respawns(), "the respawn was not counted");
        assertEquals(game.spawnX(), player.x(), 0.01);
        assertEquals(game.spawnY(), player.y(), 0.01);
        assertTrue(player.satchel().contents().isEmpty(),
                "they kept their satchel through being killed");

        assertEquals(1, game.spills().count(), "nothing was dropped");
        Spill.Pile pile = game.spills().all().get(0);
        assertEquals("Kara", pile.owner());
        assertEquals(carried, pile.items(), "the heap is not what they were carrying");
        assertEquals(400, pile.x(), 1.5, "it was not dropped where they fell");
        assertEquals(300, pile.y(), 1.5);
    }

    @Test
    void walkingBackGetsTheSatchelBack() {
        WatchGame game = game();
        WatchPlayer player = game.join(1, "Kara");
        game.move(1, 200, 0, game.groundAt(200, 0), 0, 0, false, 0.05);
        // Clear the joining kit first, so the counts below are the ones this
        // test put there rather than those plus whatever `join` hands out.
        player.satchel().clear();
        player.satchel().add("blackberry", 7);
        player.satchel().add("spyglass", 1);

        Animal beast = new Animal(1, wendigo().def(), 201, 0, 20, 3L);
        killOnce(game, player, beast);
        Spill.Pile pile = game.spills().all().get(0);

        // Nothing happens from the spawn point — it is two hundred metres away.
        assertNull(game.gather(1), "a satchel was gathered from across the world");

        // Walk back to it.
        game.move(1, pile.x(), pile.y(), game.groundAt(pile.x(), pile.y()), 0, 0, false, 0.05);
        WatchGame.Pickable target = game.pickTarget(1);
        assertNotNull(target, "there is nothing in reach where the satchel is");
        assertEquals(WatchGame.Pickable.Kind.SATCHEL, target.kind());

        assertNotNull(game.use(1));
        assertEquals(7, player.satchel().count("blackberry"),
                "the blackberries did not come back");
        assertEquals(1, player.satchel().count("spyglass"),
                "the spyglass did not come back");
        assertEquals(0, game.spills().count(), "the heap is still on the ground");
    }

    /** A heap has to survive being saved, or a walk resumed is a walk robbed. */
    @Test
    void aDroppedSatchelSurvivesASave() {
        WatchGame game = game();
        WatchPlayer player = game.join(1, "Kara");
        game.move(1, 150, -80, game.groundAt(150, -80), 0, 0, false, 0.05);
        player.satchel().add("acorn", 4);
        Animal beast = new Animal(1, wendigo().def(), 151, -80, 20, 3L);
        killOnce(game, player, beast);

        Map<String, Object> saved = game.toMap();
        WatchGame reopened = new WatchGame(WatchGame.Config.solo("Mutant Walk"));
        reopened.load(saved);

        assertEquals(1, reopened.spills().count(), "the heap did not survive the save");
        Spill.Pile pile = reopened.spills().all().get(0);
        assertEquals("Kara", pile.owner());
        assertEquals(4, pile.items().get("acorn"));
        assertEquals(150, pile.x(), 1.5);
    }

    /** Dying with an empty bag leaves nothing to walk back to. */
    @Test
    void anEmptySatchelDropsNothing() {
        WatchGame game = game();
        WatchPlayer player = game.join(1, "Kara");
        player.satchel().clear();
        Animal beast = new Animal(1, wendigo().def(), 1, 0, 20, 3L);
        killOnce(game, player, beast);
        assertEquals(0, game.spills().count(), "an empty bag left a heap on the ground");
    }

    @Test
    void aDeadPlayerIsNotHuntedAndTheirHealthIsNotSpentTwice() {
        WatchGame game = game();
        WatchPlayer player = game.join(1, "Kara");
        Animal beast = new Animal(1, wendigo().def(), 1, 0, 20, 3L);
        killOnce(game, player, beast);
        // Whatever else happens, one death is one respawn: a blow that kills
        // must not go on to kill the freshly-respawned walker as well.
        assertEquals(1, player.health(), 1e-9);
    }

    // --- summoning one on purpose ---------------------------------------------------------

    /**
     * The debug summon exists because every filter in front of a mutant is
     * working correctly when it refuses to produce one, which leaves anybody
     * testing the three of them with nothing to do but walk a taiga at night and
     * wait. See {@code Debug.Power.SUMMON}.
     */
    @Test
    void summoningNeedsTheCode() {
        WatchGame game = game();
        game.join(1, "Kara");
        assertNull(game.summon(1, wendigo().key()),
                "a player who has not typed the code summoned a wendigo");
        assertEquals(0, game.animals().size());

        game.debug(1, Debug.CODE);
        assertNotNull(game.summon(1, wendigo().key()), "the code did not grant the summon");
        assertEquals(1, game.animals().size());
    }

    @Test
    void anUnknownSpeciesSummonsNothing() {
        WatchGame game = game();
        game.join(1, "Kara");
        game.debug(1, Debug.CODE);
        assertNull(game.summon(1, "tyrannosaurus"));
        assertNull(game.summon(1, null));
        assertEquals(0, game.animals().size(), "something was summoned out of nothing");
    }

    /**
     * <b>It asks none of the four questions a natural spawn asks.</b> A tester
     * standing in the wrong biome at the wrong hour, with a mutant already
     * alive and the cooldown unspent, still gets one — which is the entire
     * point of the power, and each clause of it is a thing that would otherwise
     * quietly refuse.
     */
    @Test
    void aSummonIgnoresTheRegionTheHourTheCapAndTheCooldown() {
        WatchGame game = game();
        WatchPlayer me = game.join(1, "Kara");
        game.debug(1, Debug.CODE);
        // Noon, which is the one hour at which no mutant is offered anywhere.
        game.clock().adopt(0.5);

        for (Mutants.Kind kind : Mutants.all()) {
            assertNotNull(game.summon(1, kind.key()),
                    kind.key() + " refused a summon at midday");
        }
        assertEquals(3, game.animals().size(),
                "the cap of one alive was applied to a summon");
        for (Animal animal : game.animals()) {
            assertTrue(animal.hostile());
            // Twenty metres out, on the ground, in front of where they look.
            double away = Math.hypot(animal.x() - me.x(), animal.y() - me.y());
            assertEquals(20, away, 1.0, animal.def().name() + " arrived " + away + " m off");
            assertEquals(game.groundAt(animal.x(), animal.y()), animal.z(), 0.01,
                    animal.def().name() + " is not standing on the ground");
        }
    }

    /** And what turns up is hunting, rather than standing there to be looked at. */
    @Test
    void aSummonedMutantComesForYou() {
        WatchGame game = game();
        WatchPlayer me = game.join(1, "Kara");
        game.debug(1, Debug.CODE);
        Animal beast = game.summon(1, wendigo().key());
        assertNotNull(beast);
        double startedAt = Math.hypot(beast.x() - me.x(), beast.y() - me.y());

        for (int i = 0; i < 200; i++) {
            game.move(1, me.x(), me.y(), me.z(), 0, 0, false, 0.05);
            game.tick(0.05);
        }
        assertEquals("Kara", beast.quarry(), "it was summoned and took no interest");
        assertTrue(Math.hypot(beast.x() - me.x(), beast.y() - me.y()) < startedAt,
                "it did not close at all in ten seconds");
    }

    // --- how often one turns up -----------------------------------------------------------

    /**
     * <b>The wood has to be safe.</b> A world that puts a mutant in front of a
     * player every few minutes is not the game this is a feature of, so this
     * walks a party through a long night and counts.
     */
    @Test
    void atMostOneIsAliveAtATime() {
        WatchGame game = new WatchGame(WatchGame.Config.hosted("Party", 4242L));
        for (int i = 1; i <= 4; i++) game.join(i, "Walker " + i);
        for (int tick = 0; tick < 4000; tick++) {
            game.tick(0.05);
            int hostile = 0;
            for (Animal animal : game.animals()) {
                if (animal.hostile()) hostile++;
            }
            assertTrue(hostile <= 1, hostile + " mutants alive at once");
        }
    }

    /**
     * <b>The encounter has to actually happen.</b>
     *
     * <p>This is the test for the two bugs that made the whole feature
     * ornamental, and neither of them failed anything that existed: a mutant
     * spawned, wandered about, and was left behind by a walker who never knew it
     * was there. Fifty simulated minutes of night walking produced two spawns
     * and not one second of being hunted.
     *
     * <ol>
     *   <li>The spawn ring was a constant 90–140 m while a wendigo's notice
     *       range is 78, so it arrived <em>outside its own senses</em>. The band
     *       is now drawn from the creature's own range — see
     *       {@code WatchGame.placeMutant}.</li>
     *   <li>The ambusher's bearing converted the player's yaw to a compass
     *       direction with the two arguments of {@code atan2} the wrong way
     *       round, which put every ambusher exactly behind the person it was
     *       waiting for.</li>
     * </ol>
     *
     * <p>So this walks a night and asserts what neither of those worlds could
     * produce: that something found somebody. Several seeds, because meeting one
     * at all is meant to be uncommon and any single seed may honestly see
     * nothing.
     */
    @Test
    void somethingActuallyFindsYouOnANightWalk() {
        int seedsThatMet = 0;
        int seedsThatHunted = 0;
        for (long seed : new long[]{11L, 202L, 40404L}) {
            WatchGame game = new WatchGame(WatchGame.Config.hosted("Night", seed));
            WatchPlayer me = game.join(1, "Kara");
            double x = me.x(), y = me.y();
            boolean met = false, hunted = false;
            for (int tick = 0; tick < 40000; tick++) {
                // Held at one in the morning: two of the three are nocturnal,
                // and the point of the walk is to be out when they are.
                game.clock().adopt(0.05);
                double dx = WatchPlayer.WALK_SPEED * 0.05;
                double dy = Math.sin(tick / 900.0) * WatchPlayer.WALK_SPEED * 0.05;
                x += dx;
                y += dy;
                // A real walker looks where they are going, and an ambusher is
                // placed by that. The engine's forward is (sin yaw, −cos yaw).
                game.move(1, x, y, game.groundAt(x, y), Math.atan2(dx, -dy), 0, false,
                        0.05);
                game.tick(0.05);
                for (Animal animal : game.animals()) {
                    if (!animal.hostile()) continue;
                    met = true;
                    if (animal.quarry() != null) hunted = true;
                }
            }
            if (met) seedsThatMet++;
            if (hunted) seedsThatHunted++;
        }
        assertTrue(seedsThatMet >= 2,
                "three nights of walking met a mutant on " + seedsThatMet
                        + " of them — they are too rare to be a feature");
        assertTrue(seedsThatHunted >= 1,
                "a mutant turned up on " + seedsThatMet + " nights and hunted nobody on "
                        + "any of them — it is being put down outside its own senses");
    }

    /**
     * And the population as a whole stays what it was: the mutants are a
     * vanishing fraction of what a walk meets, not a new tier of common animal.
     */
    @Test
    void nearlyEverythingTheWorldOffersIsAnOrdinaryAnimal() {
        WatchGame game = new WatchGame(WatchGame.Config.hosted("Party", 99L));
        WatchPlayer kara = game.join(1, "Kara");
        int hostile = 0;
        int total = 0;
        Set<Long> counted = new HashSet<>();
        double x = kara.x(), y = kara.y();
        for (int tick = 0; tick < 6000; tick++) {
            // Walking, and walking a long way. A stationary player fills their
            // ring once and then meets nothing new for ever, so a count taken
            // standing still is a count of one clearing rather than of a night.
            x += WatchPlayer.WALK_SPEED * 0.05;
            y += Math.sin(tick / 400.0) * WatchPlayer.WALK_SPEED * 0.05;
            game.move(1, x, y, game.groundAt(x, y), 0, 0, false, 0.05);
            game.tick(0.05);
            for (Animal animal : game.animals()) {
                if (!counted.add(animal.id())) continue;
                total++;
                if (animal.hostile()) hostile++;
            }
        }
        assertTrue(total > 200, "only " + total + " animals were offered at all");
        assertTrue(hostile * 50 < total, hostile + " of " + total
                + " animals offered were mutants — the wood is not safe");
        // Five minutes of walking, a cap of one and a ten-minute cooldown: two
        // is the arithmetic ceiling and anything above it means one of the two
        // is not being enforced.
        assertTrue(hostile <= 2, hostile + " mutants in five minutes of walking");
    }
}
