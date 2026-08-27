package com.larsons.engine.watch;

import com.larsons.engine.watch.life.AnimState;
import com.larsons.engine.watch.life.Animal;
import com.larsons.engine.watch.life.AnimalDef;
import com.larsons.engine.watch.life.AnimalRegistry;
import com.larsons.engine.watch.life.Diet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * That an animal keeps moving, and keeps to the half of the world it lives in.
 *
 * <h2>What these are actually testing</h2>
 *
 * <p>Two reported bugs, both of which look the same from outside — an animal
 * that stops being an animal — and which had different causes:
 *
 * <ul>
 *   <li><b>Stuck.</b> The decision loop's wander branch returned early if the
 *       target had not been reached, with no other way out, so anything that
 *       could not close the distance wandered at it for ever.</li>
 *   <li><b>Underwater.</b> A swimmer's altitude was applied as a height above
 *       the ground when it meant a depth below the surface, so a fish sat a
 *       quarter-metre inside whatever it was over — including a hillside it
 *       had wandered onto, because nothing stopped it.</li>
 * </ul>
 *
 * <p>So these tests drive animals for a long simulated time over a controlled
 * world and assert the two things that were false: that they get somewhere, and
 * that they are never inside the ground.
 */
@Timeout(180)
class AnimalMovementTest {

    /**
     * A world made of one straight shoreline: dry land to the west of
     * {@code x = 0}, deepening water to the east. Simple enough that "did it
     * stay in the water" is a question with an obvious right answer.
     */
    private static final class Shore implements Animal.Surroundings {

        private final WatchClock clock = WatchClock.at(0.5);

        @Override public double groundAt(double x, double y) {
            return x < 0 ? 4 : -Math.min(9, x * 0.4);
        }

        @Override public double waterDepthAt(double x, double y) {
            return Math.max(0, TerrainField.WATER_LEVEL_FOR_TEST - groundAt(x, y));
        }

        @Override
        public boolean waterNear(double x, double y, double radius, double minDepth,
                                 double[] out) {
            for (double r = 2; r <= radius; r += 2) {
                double px = x + r;
                if (waterDepthAt(px, y) >= minDepth) {
                    out[0] = px;
                    out[1] = y;
                    return true;
                }
            }
            return false;
        }

        @Override public WatchClock clock() { return clock; }

        @Override public double disturbanceAt(double x, double y) {
            return Double.MAX_VALUE;
        }

        @Override
        public boolean nearestLure(double x, double y, Diet diet, double[] out) {
            return false;
        }

        @Override public boolean playerPosition(String name, double[] out) {
            return false;
        }
    }

    /** The water level, restated here so the fake world agrees with the real one. */
    private static final class TerrainField {
        static final double WATER_LEVEL_FOR_TEST =
                com.larsons.engine.watch.world.TerrainField.WATER_LEVEL;
    }

    /** The first species that swims. */
    private static AnimalDef aFish() {
        for (AnimalDef def : AnimalRegistry.all()) {
            if (def.aquatic()) return def;
        }
        throw new AssertionError("the registry has no aquatic species");
    }

    /** The first species that neither swims nor flies. */
    private static AnimalDef aWalker() {
        for (AnimalDef def : AnimalRegistry.all()) {
            if (!def.aquatic() && !def.airborne() && def.speed() > 0.4) return def;
        }
        throw new AssertionError("the registry has no walking species");
    }

    private static void run(Animal animal, Animal.Surroundings around, double seconds) {
        for (double t = 0; t < seconds; t += 1.0 / 20) {
            animal.step(1.0 / 20, around);
        }
    }

    /**
     * A walker put down in a clearing covers ground.
     *
     * <p>The weakest possible statement of "does not get stuck", and the one
     * that failed before: an animal wandering at an unreachable target moved
     * for a few metres and then stopped for the rest of the session.
     */
    @Test
    void aWalkerKeepsMoving() {
        Shore world = new Shore();
        AnimalDef def = aWalker();
        // Well inland, so there is nothing but walkable ground in every
        // direction it could choose.
        Animal animal = new Animal(1, def, -400, 0, world.groundAt(-400, 0), 99L);

        double travelled = 0;
        double lastX = animal.x(), lastY = animal.y();
        for (int i = 0; i < 20 * 240; i++) {
            animal.step(1.0 / 20, world);
            travelled += Math.hypot(animal.x() - lastX, animal.y() - lastY);
            lastX = animal.x();
            lastY = animal.y();
        }
        // Four minutes at even a slow walk with any gaps at all is tens of
        // metres. A stuck animal produces single digits.
        assertTrue(travelled > 40,
                def.name() + " covered only " + Math.round(travelled)
                        + " m in four minutes");
    }

    /** …and never wades out of its depth doing it. */
    @Test
    void aWalkerStaysOutOfDeepWater() {
        Shore world = new Shore();
        AnimalDef def = aWalker();
        // On the beach, where the temptation to wander east is real.
        Animal animal = new Animal(2, def, -3, 0, world.groundAt(-3, 0), 7L);
        for (int i = 0; i < 20 * 240; i++) {
            animal.step(1.0 / 20, world);
            assertTrue(world.waterDepthAt(animal.x(), animal.y()) <= 1.3,
                    def.name() + " walked into "
                            + world.waterDepthAt(animal.x(), animal.y()) + " m of water");
        }
    }

    /** A fish stays in the water, and never inside the bed. */
    @Test
    void aFishStaysInTheWaterAndAboveTheBed() {
        Shore world = new Shore();
        AnimalDef def = aFish();
        Animal animal = new Animal(3, def, 30, 0, 0, 11L);
        for (int i = 0; i < 20 * 240; i++) {
            animal.step(1.0 / 20, world);
            double bed = world.groundAt(animal.x(), animal.y());
            assertTrue(animal.z() >= bed - 1e-6,
                    def.name() + " is " + (bed - animal.z()) + " m inside the bed");
            assertTrue(world.waterDepthAt(animal.x(), animal.y()) > 0,
                    def.name() + " swam onto dry land");
        }
    }

    /**
     * A fish that somehow starts on the beach heads for the water rather than
     * being stuck for ever.
     *
     * <p>The escape clause in {@code Animal.accept}: an animal out of its
     * medium may take any step that gets it closer to that medium, or the fix
     * for one stuck case would create another.
     */
    @Test
    void aStrandedFishFindsTheWater() {
        Shore world = new Shore();
        AnimalDef def = aFish();
        Animal animal = new Animal(4, def, -6, 0, world.groundAt(-6, 0), 13L);
        run(animal, world, 240);
        assertTrue(world.waterDepthAt(animal.x(), animal.y()) > 0,
                def.name() + " is still stranded at x=" + animal.x());
    }

    /** Nothing ever ends up below the ground under it, in any medium. */
    @Test
    void nothingEverSinksIntoTheGround() {
        Shore world = new Shore();
        List<AnimalDef> all = AnimalRegistry.all();
        int checked = 0;
        // A spread across the registry rather than all thousand: this runs a
        // minute of simulation per animal.
        for (int i = 0; i < all.size(); i += 97) {
            AnimalDef def = all.get(i);
            double startX = def.aquatic() ? 40 : -40;
            Animal animal = new Animal(100 + i, def, startX, 0,
                    world.groundAt(startX, 0), i);
            for (int step = 0; step < 20 * 60; step++) {
                animal.step(1.0 / 20, world);
                double bed = world.groundAt(animal.x(), animal.y());
                assertTrue(animal.z() >= bed - 1e-6,
                        def.name() + " sank " + (bed - animal.z()) + " m into the ground");
            }
            checked++;
        }
        assertTrue(checked >= 5, "only checked " + checked + " species");
    }

    // --- fleeing ---------------------------------------------------------------------
    //
    // The reported symptom was precise and worth restating: an animal "runs
    // across the screen and then reaches some kind of invisible boundary and
    // freezes in place as they are still in the running animation". Three
    // separate causes produced it, and these tests pin all three plus the
    // invariant that would have made any of them invisible.

    /**
     * An animal that is chased does not stop.
     *
     * <p>Fleeing used to pick <em>one</em> escape at the moment of the flush
     * and never another: while a player stayed inside the flush distance the
     * decision loop returned early every tick, so the animal ran to that point,
     * arrived, and then played its running animation on the spot indefinitely.
     * Measured before the fix, on a fallow deer with a player five metres off:
     * fifty-three metres of running, then a hundred and fourteen seconds
     * frozen.
     */
    @Test
    void aChasedAnimalKeepsRunning() {
        WatchGame game = new WatchGame(WatchGame.Config.hosted("Chase", 4242L));
        game.join(1, "Larson");
        for (int i = 0; i < 20 * 30; i++) game.tick(1.0 / 20);

        Animal quarry = null;
        for (Animal animal : game.animals()) {
            if (!animal.def().airborne() && !animal.def().aquatic()) {
                quarry = animal;
                break;
            }
        }
        assertNotNull(quarry, "nothing on legs spawned to chase");

        double travelled = 0;
        double lastX = quarry.x(), lastY = quarry.y();
        for (int i = 0; i < 20 * 60; i++) {
            // Stand five metres off, wherever it goes. Nothing escapes this, so
            // nothing should ever stop running either.
            game.move(1, quarry.x() + 5, quarry.y(), 0, 0, 0, false, 1.0 / 20);
            game.tick(1.0 / 20);
            if (game.animal(quarry.id()) == null) break;
            travelled += Math.hypot(quarry.x() - lastX, quarry.y() - lastY);
            lastX = quarry.x();
            lastY = quarry.y();
        }
        // One flee target is 24–54 m. Anything in that range means it ran once
        // and then stood still, which is the bug.
        assertTrue(travelled > 120,
                quarry.def().name() + " covered only " + Math.round(travelled)
                        + " m in a minute of being followed — it stopped at its"
                        + " first escape instead of choosing another");
    }

    /**
     * Nothing is ever drawn walking or running while standing still.
     *
     * <p>The invariant, checked over a whole population and a player walking a
     * circuit through it — which is the situation that produced the report,
     * because a moving player counts as much closer and keeps everything
     * inside its flush distance. This is deliberately a statement about the
     * <em>animation</em> rather than about any one cause: the pose comes from
     * what an animal intends and the position from what is possible, and any
     * future disagreement between the two would look exactly like this bug.
     */
    @Test
    void nothingRunsOnTheSpot() {
        for (long seed : new long[]{4242L, 7L, 999L}) {
            WatchGame game = new WatchGame(WatchGame.Config.hosted("Still", seed));
            game.join(1, "Larson");
            Map<Long, double[]> was = new HashMap<>();
            Map<Long, Integer> frozen = new HashMap<>();

            for (int i = 0; i < 20 * 150; i++) {
                double t = i / 20.0;
                double px = Math.cos(t * 0.15) * 60, py = Math.sin(t * 0.15) * 60;
                game.move(1, px, py, game.field().heightAt(px, py), 0, 0, false, 1.0 / 20);
                game.tick(1.0 / 20);

                for (Animal animal : game.animals()) {
                    AnimState state = animal.state();
                    boolean afoot = state == AnimState.WALK || state == AnimState.RUN;
                    double[] before = was.get(animal.id());
                    if (before != null && afoot) {
                        boolean still = Math.hypot(animal.x() - before[0],
                                animal.y() - before[1]) < 1e-4;
                        int streak = still ? frozen.getOrDefault(animal.id(), 0) + 1 : 0;
                        frozen.put(animal.id(), streak);
                        assertTrue(streak < 10,
                                animal.def().name() + " (" + animal.behaviour() + ", "
                                        + state + ") has been " + state
                                        + " without moving for " + streak / 20.0
                                        + " s on seed " + seed);
                    }
                    was.put(animal.id(), new double[]{animal.x(), animal.y()});
                }
            }
        }
    }

    /**
     * A flushed animal actually leaves.
     *
     * <p>The other half of the report — "they should probably be simulated to
     * exit the vicinity of the player at some point". They could not, because
     * one flee target is at most fifty-four metres and the despawn radius is a
     * hundred and seventy, so an animal that never chose a second target could
     * never reach it.
     */
    @Test
    void aFlushedAnimalLeavesTheVicinity() {
        WatchGame game = new WatchGame(WatchGame.Config.hosted("Leave", 4242L));
        game.join(1, "Larson");
        for (int i = 0; i < 20 * 30; i++) game.tick(1.0 / 20);

        Set<Long> present = new HashSet<>();
        for (Animal animal : game.animals()) present.add(animal.id());
        assertTrue(present.size() > 5, "not enough spawned to be worth measuring");

        // A steady walk in one direction, straight through the middle of them.
        for (int i = 0; i < 20 * 240; i++) {
            double px = i / 20.0 * 1.2;
            game.move(1, px, 0, game.field().heightAt(px, 0), 0, 0, false, 1.0 / 20);
            game.tick(1.0 / 20);
        }

        int gone = 0;
        for (long id : present) {
            if (game.animal(id) == null) gone++;
        }
        assertTrue(gone > present.size() / 2,
                "only " + gone + " of " + present.size()
                        + " animals left the area in four minutes of being walked at");
    }

    /** The real game keeps its animals out of the ground too, over a long run. */
    @Test
    void theRealWorldKeepsItsAnimalsAboveGround() {
        WatchGame game = new WatchGame(WatchGame.Config.hosted("Deep", 4711L));
        WatchPlayer me = game.join(1, "Larson");
        assertNotNull(me);
        for (int i = 0; i < 20 * 180; i++) {
            game.tick(1.0 / 20);
            if (i % 40 != 0) continue;
            for (Animal animal : game.animals()) {
                double bed = game.field().heightAt(animal.x(), animal.y());
                assertTrue(animal.z() >= bed - 1e-6,
                        animal + " is " + (bed - animal.z()) + " m under the ground");
            }
        }
        assertTrue(game.animals().size() > 0, "nothing ever spawned");
    }
}
