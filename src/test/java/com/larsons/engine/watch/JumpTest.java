package com.larsons.engine.watch;

import com.larsons.engine.config.GameContext;
import com.larsons.engine.config.GameTypeStore;
import com.larsons.engine.demo.WatchLobbyScene;
import com.larsons.engine.demo.WatchScene;
import com.larsons.engine.input.GameAction;
import com.larsons.engine.input.InputBinding;
import com.larsons.engine.input.InputManager;
import com.larsons.engine.input.KeyBinds;
import com.larsons.engine.scene.SceneManager;
import com.larsons.engine.watch.net.WatchSession;
import com.larsons.engine.watch.render.Gait;
import com.larsons.engine.watch.render.Mesh;
import com.larsons.engine.watch.render.WalkerModel;
import com.larsons.engine.watch.world.TerrainField;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.JPanel;
import java.awt.event.KeyEvent;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Jumping: the key that does it, the arc it takes, and the figure that has to
 * look like it is doing it.
 *
 * <h2>What was wrong before there was any</h2>
 *
 * <p>The walk had no jump at all, and worse, it had taken the jump key for
 * something else: {@code GameAction.JUMP} — Space, in this engine and every
 * other game — toggled a <em>crouch</em>. A player who pressed the one key that
 * means "jump" everywhere got a squat. Crouching now has a key of its own and
 * Space does what it says.
 *
 * <p>Most of this is driven through the real scene rather than through the
 * arithmetic underneath it, because the interesting claims are about what a
 * keypress does: that Space leaves the ground, that it comes back, that it
 * cannot be pressed twice for twice the height, and that the crouch key is not
 * it.
 */
@Timeout(180)
class JumpTest {

    // --- the keys ------------------------------------------------------------------

    /** Space jumps; something else crouches; nothing in movement shares a key. */
    @Test
    void theJumpKeyIsSpaceAndTheCrouchKeyIsNot() {
        KeyBinds binds = KeyBinds.defaults();
        assertEquals(InputBinding.key(KeyEvent.VK_SPACE), binds.binding(GameAction.JUMP, 0),
                "the jump key is not Space");
        InputBinding crouch = binds.binding(GameAction.CROUCH, 0);
        assertTrue(crouch.isBound(), "crouching has no key at all");
        assertFalse(crouch.equals(binds.binding(GameAction.JUMP, 0)),
                "crouching is still on the jump key");
        assertEquals(GameAction.Category.MOVEMENT, GameAction.CROUCH.category(),
                "crouching is not filed with the rest of movement");
        assertFalse(binds.hasConflict(GameAction.CROUCH),
                "the crouch key collides with something else that moves: "
                        + binds.conflicts(GameAction.CROUCH));
        assertFalse(binds.hasConflict(GameAction.JUMP),
                "the jump key collides with something else that moves: "
                        + binds.conflicts(GameAction.JUMP));
    }

    // --- the arc, through the scene --------------------------------------------------

    /** Space takes both feet off the ground and gravity brings them back. */
    @Test
    void pressingSpaceLeavesTheGroundAndComesBackToIt(@TempDir Path dir) {
        Walk walk = Walk.onDryLand(dir);
        double ground = walk.z();

        walk.press(KeyEvent.VK_SPACE);
        double highest = ground;
        for (int i = 0; i < 120; i++) {
            walk.step();
            highest = Math.max(highest, walk.z());
        }
        assertTrue(highest > ground + 0.5,
                "a jump got " + (highest - ground) + " m off the ground");
        assertTrue(highest < ground + 1.4,
                "a jump got " + (highest - ground) + " m off the ground, which is a "
                        + "game about looking at things doing parkour");

        // …and back down, within the airtime the constants imply, plus a margin.
        for (int i = 0; i < 120; i++) walk.step();
        assertEquals(ground, walk.z(), 0.02,
                "the jump never came down");
        walk.close();
    }

    /** Holding Space does not fly, and pressing it again in the air does nothing. */
    @Test
    void aJumpCannotBeStackedOnItself(@TempDir Path dir) {
        Walk walk = Walk.onDryLand(dir);
        double ground = walk.z();

        walk.hold(KeyEvent.VK_SPACE);
        double highest = ground;
        for (int i = 0; i < 90; i++) {
            // Pressed again on every frame it is in the air, which is what a
            // player leaning on the key does.
            walk.repress(KeyEvent.VK_SPACE);
            highest = Math.max(highest, walk.z());
        }
        assertTrue(highest < ground + 1.4,
                "leaning on the jump key flew " + (highest - ground) + " m up");
        walk.close();
    }

    /** The crouch key crouches, and the jump key does not. */
    @Test
    void spaceNoLongerCrouchesAndTheCrouchKeyDoes(@TempDir Path dir) {
        Walk walk = Walk.onDryLand(dir);
        assertFalse(walk.crouching(), "the walk opened crouched");

        walk.press(KeyEvent.VK_SPACE);
        for (int i = 0; i < 90; i++) walk.step();
        assertFalse(walk.crouching(), "the jump key still crouches");

        walk.press(KeyEvent.VK_CONTROL);
        for (int i = 0; i < 4; i++) walk.step();
        assertTrue(walk.crouching(), "the crouch key does not crouch");

        walk.press(KeyEvent.VK_CONTROL);
        for (int i = 0; i < 4; i++) walk.step();
        assertFalse(walk.crouching(), "the crouch key does not stand back up");
        walk.close();
    }

    /** Jumping out of a crouch stands up rather than jumping while squatting. */
    @Test
    void aJumpStandsOutOfACrouch(@TempDir Path dir) {
        Walk walk = Walk.onDryLand(dir);
        walk.press(KeyEvent.VK_CONTROL);
        for (int i = 0; i < 4; i++) walk.step();
        assertTrue(walk.crouching());

        walk.press(KeyEvent.VK_SPACE);
        for (int i = 0; i < 6; i++) walk.step();
        assertFalse(walk.crouching(), "the jump was taken from a squat");
        walk.close();
    }

    // --- the figure ------------------------------------------------------------------

    /** A grounded leap changes nothing: the walk is exactly the walk. */
    @Test
    void aWalkerOnTheGroundIsUnaffectedByTheJumpPose() {
        Mesh plain = figure(0.3, 4.4, WalkerModel.Leap.GROUNDED);
        Mesh same = figure(0.3, 4.4, new WalkerModel.Leap(0, 0, 0));
        assertEquals(plain.vertexCount(), same.vertexCount());
        for (int i = 0; i < plain.vertices().length; i++) {
            assertEquals(plain.vertices()[i], same.vertices()[i], 1e-9);
        }
        assertTrue(WalkerModel.Leap.GROUNDED.still());
        assertFalse(new WalkerModel.Leap(1, 0, 0).still());
    }

    /**
     * Off the ground the feet come up under the body; on the way down they
     * reach for it again.
     *
     * <p>The plant that puts a walker's lower boot on the floor is switched off
     * with the ground it needs — leave it on and a tuck becomes a squat, because
     * hanging the body from its own lowest foot is the same arithmetic either
     * way and only one of them is right when there is nothing to stand on.
     */
    @Test
    void theFeetTuckOnTheWayUpAndReachOnTheWayDown() {
        Mesh standing = figure(0, 0, WalkerModel.Leap.GROUNDED);
        Mesh rising = figure(0, 4.4, new WalkerModel.Leap(1, 4.6, 0));
        Mesh falling = figure(0, 4.4, new WalkerModel.Leap(1, -4.6, 0));

        assertEquals(0, standing.minZ(), 0.005, "a standing walker is not on the ground");
        assertTrue(rising.minZ() > 0.06,
                "the legs do not come up at the top of a jump: the boots are at "
                        + rising.minZ());
        assertTrue(falling.minZ() < rising.minZ() - 0.05,
                "the legs do not reach down on the way to the ground");
        // Both are still a person: the head goes up with the body, not down.
        assertTrue(rising.maxZ() > WalkerModel.HEIGHT * 0.9);
        assertTrue(falling.maxZ() > WalkerModel.HEIGHT * 0.9);
    }

    /** A landing dips the body and keeps the feet on the ground. */
    @Test
    void aLandingIsAbsorbedByTheKneesRatherThanByTheFloor() {
        Mesh standing = figure(0, 0, WalkerModel.Leap.GROUNDED);
        Mesh landing = figure(0, 0, new WalkerModel.Leap(0, 0, 1));
        assertEquals(0, landing.minZ(), 0.02,
                "the landing crouch pushed the feet through the ground");
        assertTrue(landing.maxZ() < standing.maxZ() - 0.12,
                "a landing does not dip at all: " + landing.maxZ()
                        + " against a standing " + standing.maxZ());
        assertTrue(landing.maxZ() > standing.maxZ() - 0.6,
                "a landing folds the walker in half");
    }

    /** Nothing in the pose jumps as the leap comes and goes. */
    @Test
    void thePoseIsContinuousInEveryPartOfALeap() {
        // Off the ground, on the way up: `air` sweeping in from a standing pose.
        assertContinuous("leaving the ground",
                t -> new WalkerModel.Leap(t, 4.6, 0));
        // Through the top of the arc: the climb passing from up to down.
        assertContinuous("the top of the arc",
                t -> new WalkerModel.Leap(1, 4.6 - 9.2 * t, 0));
        // Standing back up out of a landing.
        assertContinuous("standing up out of a landing",
                t -> new WalkerModel.Leap(0, 0, 1 - t));
    }

    private interface Sweep { WalkerModel.Leap at(double t); }

    /**
     * That a figure swept over a parameter has no <b>cliff</b> in it.
     *
     * <p>Against the sweep's own average step rather than against a fixed
     * distance, and that distinction is the whole test: parts of a jumping
     * figure genuinely travel a long way — a hand goes from hanging by a hip to
     * straight overhead — so a fixed bound either fails on honest motion or is
     * loose enough to miss a cut. A discontinuity is not a large step, it is a
     * step *far larger than its neighbours*, so that is what is measured.
     */
    private static void assertContinuous(String what, Sweep sweep) {
        int steps = 400;
        double total = 0, worst = 0;
        double worstAt = 0;
        Mesh previous = figure(0.3, 4.4, sweep.at(0));
        for (int i = 1; i <= steps; i++) {
            double t = i / (double) steps;
            Mesh next = figure(0.3, 4.4, sweep.at(t));
            assertEquals(previous.vertexCount(), next.vertexCount());
            float[] a = previous.vertices(), b = next.vertices();
            double moved = 0;
            for (int v = 0; v < a.length; v++) moved = Math.max(moved, Math.abs(a[v] - b[v]));
            total += moved;
            if (moved > worst) {
                worst = moved;
                worstAt = t;
            }
            previous = next;
        }
        double mean = total / steps;
        assertTrue(mean > 1e-6, what + " does not move the figure at all");
        assertTrue(worst < mean * 8,
                what + " has a cliff in it at " + worstAt + ": one step moves "
                        + worst + " m against an average of " + mean);
    }

    private static Mesh figure(double phase, double speed, WalkerModel.Leap leap) {
        Mesh.Builder mesh = Mesh.builder(0, 0, 0, false, 1);
        WalkerModel.walker(mesh, 0, 0, 0, 0.7, false, phase, speed, leap, 0x4A6B33);
        return mesh.build();
    }

    // --- somebody else's jump ----------------------------------------------------------

    /**
     * A jump nobody sent still reads as one.
     *
     * <p>Nothing about being off the ground goes over the wire: it arrives as a
     * {@code z} that went up and came down, and the ground under it is
     * something every client generates for itself. {@link Gait} turns that back
     * into a pose, landing included.
     */
    @Test
    void aRemoteJumpIsRebuiltFromThePositionAlone() {
        Gait gaits = new Gait();
        double dt = 1 / 60.0;
        double z = 0, climb = 4.6;
        gaits.follow(9, 0, 0, 0, 0, Gait.Cycle.STRIDE, false, dt);

        Gait.Step step = null;
        double highestAir = 0;
        boolean landed = false;
        for (int frame = 0; frame < 120 && !landed; frame++) {
            climb -= 13.5 * dt;
            z += climb * dt;
            boolean off = z > 0.02;
            if (!off) z = 0;
            step = gaits.follow(9, 0, 0, z, 0, Gait.Cycle.STRIDE, off, dt);
            highestAir = Math.max(highestAir, step.leap().air());
            if (!off && frame > 10) landed = true;
        }
        assertTrue(landed, "the jump never came back down");
        assertTrue(highestAir > 0.9,
                "the pose never got off the ground: " + highestAir);
        assertNotNull(step);
        assertTrue(step.leap().settle() > 0.2,
                "the landing was not absorbed: " + step.leap().settle());

        // …and it stands back up on its own.
        for (int frame = 0; frame < 90; frame++) {
            step = gaits.follow(9, 0, 0, 0, 0, Gait.Cycle.STRIDE, false, dt);
        }
        assertTrue(step.leap().still(),
                "the walker never stood back up out of the landing");
    }

    // --- scaffolding --------------------------------------------------------------------

    /** A running walk on ground somebody can jump on, and the keys to drive it. */
    private static final class Walk {

        private final SceneManager scenes = new SceneManager();
        private final InputManager input = new InputManager();
        private final WatchSession session;
        private final WatchGame game;
        private final WatchScene walk;

        private Walk(GameContext ctx, WatchStore store, long seed) {
            walk = new WatchScene(ctx);
            scenes.setViewport(800, 480);
            scenes.register(WatchLobbyScene.NAME, new WatchLobbyScene(ctx, store));
            scenes.register(WatchScene.NAME, walk);
            game = new WatchGame(new WatchGame.Config(seed, "Jump", 1));
            game.join(1, "Kara");
            session = WatchSession.solo(game);
            session.setSelfId(1);
            walk.adopt(session, store);
            scenes.setScene(WatchScene.NAME);
            for (int i = 0; i < 4; i++) step();
        }

        /**
         * A walk whose first player spawns on dry land.
         *
         * <p>The first player joins at the world origin exactly, and a third of
         * this world is under water, so a fixed seed is a coin toss over whether
         * the test is about jumping or about swimming. The seed is searched for
         * rather than chosen, which also means it cannot quietly stop being
         * dry land if the generator changes.
         */
        static Walk onDryLand(Path dir) {
            GameContext ctx = new GameContext(null, new GameTypeStore(dir.toString()));
            WatchStore store = new WatchStore(dir.resolve("walks").toString());
            for (long seed = 1; seed < 400; seed++) {
                double ground = new TerrainField(seed).heightAt(0, 0);
                if (ground > TerrainField.WATER_LEVEL + 2) return new Walk(ctx, store, seed);
            }
            throw new IllegalStateException("no seed in four hundred puts the origin ashore");
        }

        void step() {
            input.newFrame();
            scenes.update(1 / 120.0, input);
        }

        /** Press a key, let one frame see it, and release it. */
        void press(int key) {
            hold(key);
            step();
            release(key);
            step();
        }

        void hold(int key) {
            input.keyPressed(new KeyEvent(new JPanel(), KeyEvent.KEY_PRESSED, 0, 0, key,
                    KeyEvent.CHAR_UNDEFINED));
        }

        void release(int key) {
            input.keyReleased(new KeyEvent(new JPanel(), KeyEvent.KEY_RELEASED, 0, 0, key,
                    KeyEvent.CHAR_UNDEFINED));
        }

        /** Release and press again, so the rising edge arrives once more. */
        void repress(int key) {
            release(key);
            step();
            hold(key);
            step();
        }

        double z() {
            WatchPlayer me = game.player(1);
            return me == null ? 0 : me.z();
        }

        boolean crouching() {
            WatchPlayer me = game.player(1);
            return me != null && me.crouching();
        }

        /**
         * Give the scene back everything it took.
         *
         * <p>Through {@code adopt}, which is the method that hands a scene a
         * session and therefore the one that closes the last one — and with it
         * the {@link com.larsons.engine.watch.world.ChunkStreamer} and its pool
         * of worker threads. Closing only the session leaves those threads
         * generating terrain for a world nobody is standing in, for the rest of
         * the run: four of them, in this file alone, were enough to push a
         * wall-clock test elsewhere in the suite over its threshold.
         */
        void close() {
            walk.adopt(null, null);
            session.close();
        }
    }
}
