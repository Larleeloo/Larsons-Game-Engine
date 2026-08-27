package com.larsons.engine.watch;

import com.larsons.engine.watch.life.Animal;
import com.larsons.engine.watch.world.TerrainField;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sea floor: that a player can be under water, that the game knows it, that
 * the breath runs out and comes back, and that there is something down there.
 */
@Timeout(180)
class DivingTest {

    /** Find somewhere in this world with real water over it. */
    private static double[] deepWater(TerrainField field) {
        for (double r = 40; r < 4000; r += 40) {
            for (int i = 0; i < 24; i++) {
                double a = i * Math.PI * 2 / 24;
                double x = Math.cos(a) * r, y = Math.sin(a) * r;
                double ground = field.heightAt(x, y);
                if (field.waterDepth(ground) >= 4) return new double[]{x, y, ground};
            }
        }
        return null;
    }

    /** Putting your head under is something the server works out, not something you say. */
    @Test
    void theServerDecidesWhetherYouAreUnderWater() {
        WatchGame game = new WatchGame(WatchGame.Config.hosted("Dive", 20250827L));
        WatchPlayer me = game.join(1, "Larson");
        assertNotNull(me);
        assertFalse(me.submerged(), "a fresh player starts under water");
        assertEquals(1, me.breath(), 1e-9);

        double[] deep = deepWater(game.field());
        assertNotNull(deep, "this world has no deep water anywhere near the origin");

        // Standing on the bed, which is several metres under the surface.
        game.move(1, deep[0], deep[1], deep[2], 0, 0, false, 0.05);
        assertTrue(game.player(1).submerged(),
                "standing on a lake bed did not count as being under water");
    }

    /** Air runs out down there, and comes back up here. */
    @Test
    void breathRunsOutAndComesBack() {
        WatchGame game = new WatchGame(WatchGame.Config.hosted("Air", 20250827L));
        game.join(1, "Larson");
        double[] deep = deepWater(game.field());
        assertNotNull(deep);

        // Sit on the bed for longer than a lungful.
        for (int i = 0; i < 20 * (int) WatchPlayer.BREATH_SECONDS + 40; i++) {
            game.move(1, deep[0], deep[1], deep[2], 0, 0, false, 1.0 / 20);
        }
        assertTrue(game.player(1).outOfBreath(),
                "a minute on the bed and still " + game.player(1).breath() + " air");

        // Back up to the surface. The recovery is four times as fast as the
        // spend, so a few seconds is plenty.
        double top = TerrainField.WATER_LEVEL + 1;
        for (int i = 0; i < 20 * 20; i++) {
            game.move(1, deep[0], deep[1], top, 0, 0, false, 1.0 / 20);
        }
        assertEquals(1, game.player(1).breath(), 1e-6,
                "the breath did not come back at the surface");
        assertFalse(game.player(1).submerged());
    }

    /** Diving is what puts fish in front of you. */
    @Test
    void thereIsSomethingDownThere() {
        WatchGame game = new WatchGame(WatchGame.Config.hosted("Reef", 20250827L));
        game.join(1, "Larson");
        double[] deep = deepWater(game.field());
        assertNotNull(deep);

        boolean sawOne = false;
        for (int i = 0; i < 20 * 200 && !sawOne; i++) {
            game.move(1, deep[0], deep[1], deep[2], 0, 0, false, 1.0 / 20);
            game.tick(1.0 / 20);
            for (Animal animal : game.animals()) {
                if (!animal.def().aquatic()) continue;
                double d = Math.hypot(animal.x() - deep[0], animal.y() - deep[1]);
                if (d < 120) sawOne = true;
            }
        }
        assertTrue(sawOne,
                "three minutes on a lake bed and not one aquatic animal turned up");
    }

    /** A snapshot carries the state a diver's screen needs. */
    @Test
    void divingStateReachesTheView() {
        WatchGame game = new WatchGame(WatchGame.Config.hosted("View", 20250827L));
        game.join(1, "Larson");
        double[] deep = deepWater(game.field());
        assertNotNull(deep);
        for (int i = 0; i < 200; i++) {
            game.move(1, deep[0], deep[1], deep[2], 0, 0, false, 1.0 / 20);
        }

        WatchView view = new WatchView();
        view.snapshot(game, 1);
        WatchView.Walker me = view.self();
        assertNotNull(me);
        assertTrue(me.submerged(), "the view does not know the player is under water");
        assertTrue(me.breath() < 1, "the view does not know the breath is being spent");

        // …and through the wire, which is a different path to the same fields.
        WatchView guest = new WatchView();
        guest.loadWalkers(java.util.List.of(game.player(1).toSnapshot()));
        WatchView.Walker replicated = guest.walkers().get(0);
        assertTrue(replicated.submerged());
        assertEquals(me.breath(), replicated.breath(), 1e-6);
    }
}
