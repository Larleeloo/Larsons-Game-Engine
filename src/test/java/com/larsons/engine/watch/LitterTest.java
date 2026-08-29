package com.larsons.engine.watch;

import com.larsons.engine.watch.world.Flora;
import com.larsons.engine.watch.world.TerrainField;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Foraging happens to something you can see.
 *
 * <p>It used to happen to nothing at all: press E anywhere with no bush in
 * front of you, wait out a cooldown, and a fallen branch appeared in the
 * satchel out of an empty patch of grass. {@link Litter} moved that roll from
 * the key press to the world generation, and these are the two halves of that
 * claim: that the same world yields the same things wherever it is worked out,
 * and that what goes in the satchel is the particular thing you were standing
 * over.
 */
@Timeout(180)
class LitterTest {

    private static final long SEED = 4242L;

    private static Litter litter(long seed) {
        return new Litter(seed, new TerrainField(seed));
    }

    private static Flora.Ground ground(long seed) {
        return Flora.ground(new TerrainField(seed));
    }

    /**
     * Two walkers who arrive a week apart find the same stone on the same bank.
     *
     * <p>The whole reason litter is generated rather than stored: it is a
     * function of its own cell, so a host and every client work it out
     * independently and nothing about the floor of the world ever goes on the
     * wire. If this drifts, a client draws a branch the host will not let you
     * pick up.
     */
    @Test
    void theGroundIsAFunctionOfTheSeedAndNothingElse() {
        List<Litter.Piece> first = litter(SEED).near(ground(SEED), 0, 0, 60);
        List<Litter.Piece> again = litter(SEED).near(ground(SEED), 0, 0, 60);
        assertEquals(first.size(), again.size(), "the same world gave a different floor");
        for (int i = 0; i < first.size(); i++) {
            assertEquals(first.get(i), again.get(i),
                    "piece " + i + " moved or changed between two readings");
        }
        assertFalse(first.isEmpty(), "a sixty-metre sweep found nothing to pick up");

        Set<String> other = new TreeSet<>();
        for (Litter.Piece piece : litter(SEED + 1).near(ground(SEED + 1), 0, 0, 60)) {
            other.add(piece.key() + "@" + Math.round(piece.x()) + "," + Math.round(piece.y()));
        }
        Set<String> mine = new TreeSet<>();
        for (Litter.Piece piece : first) {
            mine.add(piece.key() + "@" + Math.round(piece.x()) + "," + Math.round(piece.y()));
        }
        assertFalse(mine.equals(other), "two different worlds have identical floors");
    }

    /** Everything lying about is something the satchel and the recipes know. */
    @Test
    void everythingOnTheGroundIsARealItem() {
        Set<String> unknown = new TreeSet<>();
        Set<String> kinds = new TreeSet<>();
        for (int ring = 0; ring < 6; ring++) {
            double at = ring * 400;
            for (Litter.Piece piece : litter(SEED).near(ground(SEED), at, -at, 90)) {
                Forage.Item item = Forage.byKey(piece.key());
                if (item == null) unknown.add(piece.key());
                else kinds.add(item.kind().name());
                assertTrue(piece.scale() > 0.5 && piece.scale() < 2,
                        piece.key() + " is drawn at " + piece.scale() + " times life size");
            }
        }
        assertTrue(unknown.isEmpty(), "the ground is scattered with unknown items: " + unknown);
        assertTrue(kinds.contains("MATERIAL"),
                "nothing you can build with is ever lying about: " + kinds);
    }

    /**
     * Enough to be worth walking for, and not so much that the wood is a
     * jumble sale.
     *
     * <p>Foraging used to be unlimited — one handful per second, for ever,
     * anywhere. What replaced it has to be generous enough that a walk still
     * fills a satchel.
     */
    @Test
    void thereIsEnoughOnTheGroundToBeWorthLookingAt() {
        TerrainField field = new TerrainField(SEED);
        Flora.Ground ground = Flora.ground(field);
        Litter litter = new Litter(SEED, field);
        int found = 0;
        int places = 0;
        // Measured on dry land only. Most of a world this size is water, and
        // averaging a lake in would be measuring the sea floor.
        for (int i = 0; i < 60 && places < 12; i++) {
            double x = 300 + i * 137, y = (i % 7) * 190 - 400;
            if (field.waterDepth(field.heightAt(x, y)) > 0) continue;
            found += litter.near(ground, x, y, 50).size();
            places++;
        }
        assertTrue(places >= 8, "could not find eight dry places to look at");
        double perSweep = found / (double) places;
        // A fifty-metre sweep is 7,850 square metres.
        assertTrue(perSweep > 8,
                "only " + perSweep + " things within fifty metres — foraging is a worse"
                        + " deal than the invisible roll it replaced");
        assertTrue(perSweep < 140,
                perSweep + " things within fifty metres — the floor is a jumble sale");
    }

    /** Nothing is left floating in a lake or balanced on a cliff. */
    @Test
    void nothingLiesInTheWaterOrOnACliff() {
        TerrainField field = new TerrainField(SEED);
        Flora.Ground ground = Flora.ground(field);
        Litter litter = new Litter(SEED, field);
        List<String> wet = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            double at = i * 700;
            for (Litter.Piece piece : litter.near(ground, at, -at * 0.6, 80)) {
                if (field.waterDepth(piece.z()) > 0) wet.add(piece.key() + " at " + piece.z());
            }
        }
        assertTrue(wet.isEmpty(), "these are floating: " + wet);
    }

    /** A cell's identity is stable, distinct, and recognisable as this class's. */
    @Test
    void everyPieceHasItsOwnIdAndSaysWhereItCameFrom() {
        Set<Long> seen = new HashSet<>();
        for (int cx = -600; cx <= 600; cx += 37) {
            for (int cy = -600; cy <= 600; cy += 41) {
                long id = Litter.idOf(cx, cy);
                assertTrue(Litter.isLitter(id),
                        "the id for (" + cx + ", " + cy + ") is not recognisable");
                assertTrue(seen.add(id),
                        "two cells share an id — (" + cx + ", " + cy + ")");
                assertEquals(id, Litter.idOf(cx, cy), "an id is not stable");
            }
        }
        // …and a key from somewhere else is not mistaken for one of ours. A
        // bush key is a mix of two hashed coordinates, so this is what stops a
        // picked bush hiding a piece of litter half a world away.
        assertFalse(Litter.isLitter(0), "zero reads as a piece of litter");
        assertFalse(Litter.isLitter(1), "a tree id reads as a piece of litter");
    }

    // --- and the game around it ---------------------------------------------------

    /**
     * Picking up is aimed: the thing that goes in the satchel is the thing the
     * highlight was round, and it is not there afterwards.
     */
    @Test
    void whatYouPickUpIsTheThingYouWereStandingOver() {
        WatchGame game = new WatchGame(new WatchGame.Config(SEED, "Litter", 1));
        WatchPlayer me = game.join(1, "Kara");

        Litter.Piece piece = firstDryPiece(game);
        assertNotNull(piece, "nowhere near the origin has anything lying on it");
        stand(game, piece.x() - 0.6, piece.y());

        WatchGame.Pickable target = game.pickTarget(1);
        assertNotNull(target, "standing on a branch and nothing is in reach");
        assertEquals(WatchGame.Pickable.Kind.GROUND, target.kind(),
                "something else got in the way: " + target);
        assertEquals(piece.key(), target.key());
        assertEquals("Pick up " + Forage.nameOf(piece.key()), target.prompt());

        int before = me.satchel().count(piece.key());
        assertEquals(piece.key(), game.pick(1), "the pick took something else");
        assertEquals(before + 1, me.satchel().count(piece.key()));
        assertTrue(game.takenLitter().contains(piece.id()),
                "the host did not record the piece as gone");

        // And it is not there any more, however long you stand on it.
        for (int i = 0; i < 6; i++) {
            game.tick(1.0);
            String got = game.pick(1);
            assertTrue(got == null || !got.equals(piece.key()),
                    "the same piece of litter came up twice");
        }
    }

    /**
     * <b>E is not a slot machine, and now it has a target.</b> Standing in the
     * middle of nothing and holding the key used to produce an endless stream
     * of materials; it should now produce nothing at all.
     */
    @Test
    void pressingPickWithNothingUnderfootGivesNothing() {
        WatchGame game = new WatchGame(new WatchGame.Config(SEED, "Litter", 1));
        game.join(1, "Kara");
        Litter litter = new Litter(SEED, game.field());

        // Find somewhere with nothing lying within reach, and stand there.
        double[] bare = null;
        for (int i = 0; i < 400 && bare == null; i++) {
            double x = i * 3.1, y = -i * 2.7;
            if (litter.near(game.ground(), x, y, WatchGame.REACH + 0.5).isEmpty()) {
                bare = new double[]{x, y};
            }
        }
        assertNotNull(bare, "every square metre of the world has something on it");
        stand(game, bare[0], bare[1]);

        int got = 0;
        for (int i = 0; i < 20; i++) {
            if (game.pick(1) != null) got++;
            game.tick(0.5);
        }
        // A bush or a fruiting tree may still be in reach of wherever that
        // landed; what must not happen is a handful every press.
        assertTrue(got <= 4, "twenty presses over bare ground yielded " + got + " things");
    }

    /** The first piece of litter near the origin that a player can actually stand at. */
    private static Litter.Piece firstDryPiece(WatchGame game) {
        Litter litter = new Litter(game.config().seed(), game.field());
        for (int ring = 0; ring < 10; ring++) {
            for (Litter.Piece piece
                    : litter.near(game.ground(), ring * 40, 0, 40)) {
                // Not one with a bush or a fruiting tree over it: those come
                // first in `pickTarget` and would answer instead.
                if (game.flora().nearestBush(game.ground(), piece.x(), piece.y(),
                        WatchGame.REACH + 2) != null) {
                    continue;
                }
                if (game.flora().nearestTree(game.ground(), piece.x(), piece.y(),
                        WatchGame.REACH + 3) != null) {
                    continue;
                }
                return piece;
            }
        }
        return null;
    }

    /** Put the player at a point, facing east, and let them settle. */
    private static void stand(WatchGame game, double x, double y) {
        for (int i = 0; i < 4; i++) {
            game.move(1, x, y, game.groundAt(x, y), Math.PI / 2, 0, false, 1.0 / 20);
            game.tick(0.05);
        }
    }

    /** A satchel that never runs out still cannot conjure a piece off the floor twice. */
    @Test
    void aTakenPieceStaysTakenForEverybody() {
        WatchGame game = new WatchGame(new WatchGame.Config(SEED, "Litter", 1));
        game.join(1, "Kara");
        game.join(2, "Ben");
        Litter.Piece piece = firstDryPiece(game);
        assertNotNull(piece);
        stand(game, piece.x() - 0.6, piece.y());
        for (int i = 0; i < 4; i++) {
            game.move(2, piece.x() + 0.6, piece.y(), game.groundAt(piece.x() + 0.6,
                    piece.y()), -Math.PI / 2, 0, false, 1.0 / 20);
            game.tick(0.05);
        }
        assertEquals(piece.key(), game.pick(1));
        game.tick(2.0);
        WatchGame.Pickable forBen = game.pickTarget(2);
        assertTrue(forBen == null || forBen.kind() != WatchGame.Pickable.Kind.GROUND
                        || !forBen.key().equals(piece.key())
                        || Math.hypot(forBen.x() - piece.x(), forBen.y() - piece.y()) > 0.01,
                "the second walker was offered the piece the first one took");
        assertNull(nullIfNotThatPiece(game.pick(2), piece.key()),
                "two people picked up the same branch");
    }

    private static String nullIfNotThatPiece(String got, String key) {
        return key.equals(got) ? got : null;
    }

    /**
     * The one fact about the floor that travels, travels.
     *
     * <p>Everything else about a piece of litter is a function of the seed and
     * is worked out at both ends; whether somebody has already picked it up
     * cannot be, so it rides on the world sync beside the grove, the crops and
     * the moved boats. Checked through the message rather than through a socket:
     * what matters is that the host writes it and a view reads it back.
     */
    @Test
    void whatHasBeenPickedUpTravelsWithTheRestOfTheSlowWorld() {
        WatchGame game = new WatchGame(new WatchGame.Config(SEED, "Litter", 1));
        game.join(1, "Kara");
        Litter.Piece piece = firstDryPiece(game);
        assertNotNull(piece);
        stand(game, piece.x() - 0.6, piece.y());
        assertEquals(piece.key(), game.pick(1));

        var message = com.larsons.engine.watch.net.WatchProto.world(
                game.grove().toMap(), game.crops().toMap(), game.structure().toMap(),
                game.boats().toMap(), game.takenLitter());

        WatchView view = new WatchView();
        assertFalse(view.litterTaken(piece.id()),
                "a fresh view thinks something has already been picked up");
        view.loadTakenLitter(WatchJson.list(message, "taken"));
        assertTrue(view.litterTaken(piece.id()),
                "the piece the host says is gone is still on every client's ground");

        // A later sync with nothing taken puts it back, which is what makes the
        // walk's own optimistic guess safe to make.
        view.loadTakenLitter(List.of());
        assertFalse(view.litterTaken(piece.id()), "the set is added to rather than replaced");
    }
}
