package com.larsons.engine.watch;

import com.larsons.engine.watch.world.TerrainField;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Boats: that there are some, that they are on shorelines, that everybody
 * finds the same ones, and that moving one is remembered.
 */
@Timeout(180)
class BoatsTest {

    private static final long SEED = 8675309L;

    /** A wide sweep, so a world with little coast in one corner still has some. */
    private static final double SWEEP = 3000;

    private static List<Boats.Boat> sweep(Boats boats, TerrainField field) {
        return boats.near(field, 0, 0, SWEEP);
    }

    @Test
    void aWorldHasBoatsInIt() {
        TerrainField field = new TerrainField(SEED);
        List<Boats.Boat> found = sweep(new Boats(SEED), field);
        assertTrue(found.size() >= 3,
                "a six-kilometre square of world should have a few boats, found "
                        + found.size());
    }

    /** Two players on the same seed find the same boats, without exchanging a byte. */
    @Test
    void everybodyFindsTheSameBoats() {
        TerrainField field = new TerrainField(SEED);
        List<Boats.Boat> mine = sweep(new Boats(SEED), field);
        List<Boats.Boat> yours = sweep(new Boats(SEED), field);
        assertEquals(mine.size(), yours.size());
        for (int i = 0; i < mine.size(); i++) {
            assertEquals(mine.get(i).id(), yours.get(i).id());
            assertEquals(mine.get(i).x(), yours.get(i).x(), 1e-9);
            assertEquals(mine.get(i).y(), yours.get(i).y(), 1e-9);
            assertEquals(mine.get(i).yaw(), yours.get(i).yaw(), 1e-9);
        }
    }

    /** A different seed is a different world, with the boats somewhere else. */
    @Test
    void aDifferentSeedIsADifferentShore() {
        TerrainField a = new TerrainField(SEED);
        TerrainField b = new TerrainField(SEED + 1);
        Set<Long> here = new HashSet<>();
        for (Boats.Boat boat : sweep(new Boats(SEED), a)) {
            here.add(Math.round(boat.x()) * 31 + Math.round(boat.y()));
        }
        int shared = 0;
        for (Boats.Boat boat : sweep(new Boats(SEED + 1), b)) {
            if (here.contains(Math.round(boat.x()) * 31 + Math.round(boat.y()))) shared++;
        }
        assertTrue(shared <= 1, "two worlds' boats landed in the same places");
    }

    /** Every boat is in water shallow enough to be pulled up in. */
    @Test
    void boatsAreOnShorelines() {
        TerrainField field = new TerrainField(SEED);
        List<Boats.Boat> found = sweep(new Boats(SEED), field);
        assertTrue(found.size() > 0, "no boats to check");
        for (Boats.Boat boat : found) {
            double depth = field.waterDepth(field.heightAt(boat.x(), boat.y()));
            assertTrue(depth > 0, boat + " is on dry land");
            assertTrue(depth <= 1.5, boat + " is moored in " + depth + " m of water");
        }
    }

    /** A boat left somewhere is found there, by anybody. */
    @Test
    void aMovedBoatStaysWhereItWasLeft() {
        TerrainField field = new TerrainField(SEED);
        Boats boats = new Boats(SEED);
        Boats.Boat first = sweep(boats, field).get(0);

        double newX = first.x() + 240, newY = first.y() - 180;
        boats.moveTo(first.id(), newX, newY, TerrainField.WATER_LEVEL, 1.25);
        assertEquals(1, boats.movedCount());

        Boats.Boat again = boats.nearest(field, newX, newY, 5);
        assertNotNull(again, "the boat was not where it was left");
        assertEquals(first.id(), again.id());
        assertEquals(newX, again.x(), 1e-9);
        assertTrue(again.moved());

        // …and it is not also still in its original cell.
        Boats.Boat ghost = boats.nearest(field, first.x(), first.y(), 2);
        assertNull(ghost, "the boat was in two places at once");
    }

    /** Moved boats round-trip through a save and a world sync. */
    @Test
    void movedBoatsRoundTrip() {
        TerrainField field = new TerrainField(SEED);
        Boats host = new Boats(SEED);
        List<Boats.Boat> found = sweep(host, field);
        host.moveTo(found.get(0).id(), 111, 222, 0, 0.5);
        host.moveTo(found.get(1).id(), -333, 444, 0, 2.5);

        Boats guest = new Boats(SEED);
        guest.load(host.toMap());
        assertEquals(2, guest.movedCount());
        Boats.Boat there = guest.nearest(field, 111, 222, 3);
        assertNotNull(there);
        assertEquals(found.get(0).id(), there.id());
        assertEquals(0.5, there.yaw(), 1e-9);
    }

    /** Boarding and stepping out, through the game the server owns. */
    @Test
    void boardingAndLeavingThroughTheGame() {
        WatchGame game = new WatchGame(WatchGame.Config.hosted("Boats", SEED));
        WatchPlayer player = game.join(1, "Larson");
        assertNotNull(player);

        // Nothing to board where we joined.
        assertEquals(0, player.boatId());

        Boats.Boat boat = game.boats().nearest(game.field(), 0, 0, SWEEP);
        assertNotNull(boat, "no boat anywhere in this world");

        // Walk to it — teleporting is what a test is allowed to do.
        game.move(1, boat.x(), boat.y(), boat.z(), 0, 0, false, 0.05);
        assertNotNull(game.useBoat(1), "could not board a boat we are standing at");
        assertEquals(boat.id(), game.player(1).boatId());

        // Row somewhere, and step out.
        game.move(1, boat.x() + 60, boat.y() + 40, boat.z(), 0, 0, false, 0.05);
        assertNotNull(game.useBoat(1));
        assertEquals(0, game.player(1).boatId());

        Boats.Boat moored = game.boats().nearest(game.field(),
                boat.x() + 60, boat.y() + 40, 4);
        assertNotNull(moored, "the boat did not come with us");
        assertEquals(boat.id(), moored.id());
    }

    /**
     * No boat ever carries the id that means "not in a boat".
     *
     * <p>Zero is {@link WatchPlayer#boatId()}'s sentinel and the unsalted cell
     * packing gave cell (0, 0) exactly that. Since everybody spawns at the
     * world origin, the first boat most people would ever walk up to could be
     * boarded and then never rowed or stepped out of, because every test for
     * "am I in a boat" said no.
     */
    @Test
    void noBoatCarriesTheNotInABoatSentinel() {
        TerrainField field = new TerrainField(SEED);
        Boats boats = new Boats(SEED);
        int checked = 0;
        for (Boats.Boat boat : sweep(boats, field)) {
            assertNotEquals(0, boat.id(), boat + " has the sentinel id");
            checked++;
        }
        // …and specifically the cell the world origin falls in, over several
        // worlds, since only some seeds put a boat there at all.
        for (long seed = 0; seed < 40; seed++) {
            Boats world = new Boats(seed);
            TerrainField terrain = new TerrainField(seed);
            for (Boats.Boat boat : world.near(terrain, 0, 0, Boats.CELL)) {
                assertNotEquals(0, boat.id(),
                        "seed " + seed + ": " + boat + " at the origin has the sentinel id");
                checked++;
            }
        }
        assertTrue(checked > 0, "no boats were checked at all");
    }

    /** An id round-trips back to the boat it names. */
    @Test
    void anIdFindsItsOwnBoat() {
        TerrainField field = new TerrainField(SEED);
        Boats boats = new Boats(SEED);
        for (Boats.Boat boat : sweep(boats, field)) {
            Boats.Boat again = boats.byId(field, boat.id());
            assertNotNull(again, "id " + boat.id() + " found nothing");
            assertEquals(boat.x(), again.x(), 1e-9);
            assertEquals(boat.y(), again.y(), 1e-9);
        }
        // …including after it has been moved.
        Boats.Boat first = sweep(boats, field).get(0);
        boats.moveTo(first.id(), 900, -700, 0, 0.75);
        Boats.Boat moved = boats.byId(field, first.id());
        assertNotNull(moved);
        assertEquals(900, moved.x(), 1e-9);
        assertTrue(moved.moved());
    }

    /** Nothing to board is not an error, it is nothing. */
    @Test
    void boardingNothingDoesNothing() {
        WatchGame game = new WatchGame(WatchGame.Config.solo("Empty"));
        game.join(1, "Larson");
        // A long way from any cell that could hold one.
        Boats.Boat any = game.boats().nearest(game.field(), 0, 0, Boats.BOARD_RANGE);
        if (any == null) {
            assertNull(game.useBoat(1));
        }
        assertNull(game.useBoat(999), "an absent player boarded a boat");
    }
}
