package com.larsons.engine.watch;

import com.larsons.engine.config.GameContext;
import com.larsons.engine.config.GameTypeStore;
import com.larsons.engine.demo.WatchLobbyScene;
import com.larsons.engine.demo.WatchScene;
import com.larsons.engine.input.GameAction;
import com.larsons.engine.input.InputManager;
import com.larsons.engine.input.KeyBinds;
import com.larsons.engine.scene.SceneManager;
import com.larsons.engine.watch.net.WatchSession;
import com.larsons.engine.watch.render.Mesh;
import com.larsons.engine.watch.render.TrackMesher;
import com.larsons.engine.watch.world.ChunkStreamer;
import com.larsons.engine.watch.world.TerrainField;
import com.larsons.engine.watch.world.TrackField;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.JPanel;
import java.awt.event.KeyEvent;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The path players wear into the ground by walking on it, and the ten minutes
 * it lasts.
 *
 * <p>Three claims are load-bearing and the rest of this file is about them.
 *
 * <ul>
 *   <li><b>It is temporary.</b> Ten minutes is the whole brief; a track that
 *       outlived it would turn a wood into a map of itself.</li>
 *   <li><b>It is made by walking</b> — proportional to distance covered, not to
 *       time spent — so standing in a clearing for an hour costs nothing and
 *       leaves nothing.</li>
 *   <li><b>It is drawn on the ground and never cut into it.</b> The heightfield
 *       is the one thing every machine agrees about without being told, and a
 *       decoration is not allowed to be the thing that breaks that.</li>
 * </ul>
 */
@Timeout(180)
class TrackFieldTest {

    /**
     * Walk somebody from {@code (x, y)} along a unit direction, in short steps
     * — the way a client feeds this thing, one position per frame, so the
     * prints land where a stride would put them rather than where the test
     * would like them.
     */
    private static void walk(TrackField tracks, int id, double x, double y,
                             double dx, double dy, double metres) {
        double step = 0.2;
        for (double at = 0; at <= metres; at += step) {
            tracks.note(id, x + dx * at, y + dy * at);
        }
    }

    // --- what a stride is ---------------------------------------------------------------

    /** Standing still is not walking, however long it goes on for. */
    @Test
    void standingStillLeavesOnePrintAndNoMore() {
        TrackField tracks = new TrackField();
        for (int i = 0; i < 4000; i++) {
            tracks.note(1, 12.0, -3.0);
            tracks.advance(1 / 60.0);
        }
        assertEquals(1, tracks.prints(),
                "standing in one place wrote a print per frame");
        assertEquals(0, tracks.strengthAt(12, -3), 0.0,
                "a single print with nothing to join it to is drawing a track");
    }

    /** Prints are laid a stride apart, whatever rate the positions arrive at. */
    @Test
    void printsAreLaidOneStrideApart() {
        TrackField tracks = new TrackField();
        walk(tracks, 1, 0, 0, 1, 0, 60);
        int prints = tracks.prints();
        assertTrue(prints > 38 && prints < 48,
                "sixty metres of walking came out as " + prints + " prints");

        // And no two of them further apart than the frame that laid them could
        // account for, which is what the strip is drawn on the assumption of.
        double previous = Double.NaN;
        for (TrackField.Print print : tracks.trails().iterator().next()) {
            if (!Double.isNaN(previous)) {
                double gap = print.x() - previous;
                assertTrue(gap >= TrackField.STRIDE - 1e-9 && gap <= TrackField.STRIDE + 0.25,
                        "a stride of " + gap + " m");
            }
            previous = print.x();
        }
    }

    /** A track is under the line that was walked, and not beside it. */
    @Test
    void theTrackIsWhereTheWalkerWent() {
        TrackField tracks = new TrackField();
        walk(tracks, 1, 0, 0, 1, 0, 40);
        assertTrue(tracks.strengthAt(20, 0) > 0.95, "the middle of a fresh path is faint");
        assertTrue(tracks.trodden(20, 0.3), "a path a third of a metre wide is not one");
        assertEquals(0, tracks.strengthAt(20, 4), 0.0,
                "the ground four metres off the path is trodden");
        assertEquals(0, tracks.strengthAt(-30, 0), 0.0,
                "ground nobody has been near is trodden");
    }

    // --- ten minutes --------------------------------------------------------------------

    /** The whole of the brief: a track is gone ten minutes after it was made. */
    @Test
    void aTrackIsGoneAfterTenMinutes() {
        TrackField tracks = new TrackField();
        walk(tracks, 1, 0, 0, 1, 0, 40);
        assertTrue(tracks.strengthAt(20, 0) > 0.95);

        // Nine minutes in it is fading but still there — a path that vanished
        // early would be no use for finding your way back, which is what it is
        // for.
        tracks.advance(TrackField.LIFETIME * 0.9);
        double late = tracks.strengthAt(20, 0);
        assertTrue(late > 0, "the path was gone before its ten minutes were up");
        assertTrue(late < 0.5, "nine minutes of fading has not faded it");

        tracks.advance(TrackField.LIFETIME * 0.11);
        assertEquals(0, tracks.strengthAt(20, 0), 0.0, "the path outlived ten minutes");
        assertEquals(0, tracks.prints(), "the prints are still being kept");
        assertEquals(0, tracks.trailCount(), "the walker's empty trail is still held");
    }

    /** It holds at full strength first and fades afterwards, never the reverse. */
    @Test
    void freshnessHoldsAndThenFallsAndNeverRises() {
        TrackField tracks = new TrackField();
        tracks.advance(TrackField.LIFETIME * 2);
        double laid = tracks.seconds();
        assertEquals(1, tracks.freshnessAt(laid), 0.0, "a print is not fresh when laid");

        double previous = 1;
        boolean everFull = false;
        for (int i = 1; i <= 100; i++) {
            tracks.advance(TrackField.LIFETIME / 100.0);
            double fresh = tracks.freshnessAt(laid);
            assertTrue(fresh <= previous + 1e-9, "a track got fresher with age");
            if (i <= 30 && fresh >= 1) everFull = true;
            previous = fresh;
        }
        assertTrue(everFull, "a track starts fading the instant it is laid");
        assertTrue(previous < 1e-6, "a track is still readable after its lifetime");
    }

    // --- what is not a footstep ----------------------------------------------------------

    /**
     * Two positions a long way apart are a hole in the record, not a stride.
     *
     * <p>Which is what a boat crossing, a rejoin and a dropped snapshot all look
     * like from here — and drawing the line between them would put a kilometre
     * of path across a lake nobody swam.
     */
    @Test
    void aJumpDoesNotDrawALineAcrossTheWorld() {
        TrackField tracks = new TrackField();
        tracks.note(1, 0, 0);
        tracks.note(1, 900, 0);
        assertEquals(2, tracks.prints(), "the far position was not recorded at all");
        assertEquals(0, tracks.strengthAt(450, 0), 0.0,
                "there is a path across ground nobody walked");
        assertFalse(TrackField.joined(new TrackField.Print(0, 0, 0),
                        new TrackField.Print(900, 0, 0)),
                "a nine hundred metre gap counts as a stride");
    }

    /** Walking resumes cleanly on the far side of one. */
    @Test
    void walkingResumesAfterAJump() {
        TrackField tracks = new TrackField();
        walk(tracks, 1, 0, 0, 1, 0, 20);
        walk(tracks, 1, 900, 0, 1, 0, 20);
        assertTrue(tracks.strengthAt(10, 0) > 0.9, "the first stretch was lost");
        assertTrue(tracks.strengthAt(910, 0) > 0.9, "the second stretch was never drawn");
    }

    // --- more than one walker -------------------------------------------------------------

    /** Ground two people crossed reads as more worn than ground one did. */
    @Test
    void groundCrossedTwiceIsMoreWornThanGroundCrossedOnce() {
        TrackField once = new TrackField();
        walk(once, 1, 0, 0, 1, 0, 40);
        TrackField twice = new TrackField();
        walk(twice, 1, 0, 0, 1, 0, 40);
        walk(twice, 2, 0, 0, 1, 0, 40);

        // Fresh, both are as dark as a track gets and there is nothing to
        // compare; the difference is what compositing does on the way down.
        once.advance(TrackField.LIFETIME * 0.85);
        twice.advance(TrackField.LIFETIME * 0.85);
        assertTrue(twice.strengthAt(20, 0) > once.strengthAt(20, 0) + 0.05,
                "a second walker over the same ground changed nothing: "
                        + once.strengthAt(20, 0) + " vs " + twice.strengthAt(20, 0));
        assertEquals(2, twice.trailCount(), "two walkers did not make two trails");
    }

    /** One walker's chain is capped, so nothing anybody does grows without end. */
    @Test
    void oneWalkersRecordIsBounded() {
        TrackField tracks = new TrackField();
        for (int i = 0; i < TrackField.MAX_PRINTS_PER_WALKER * 2; i++) {
            tracks.note(1, i * 2.0, 0);
        }
        assertEquals(TrackField.MAX_PRINTS_PER_WALKER, tracks.prints(),
                "the cap on one walker's prints is not holding");
    }

    /** Leaving a walk leaves nothing behind for the next one. */
    @Test
    void clearingForgetsEverything() {
        TrackField tracks = new TrackField();
        walk(tracks, 1, 0, 0, 1, 0, 40);
        tracks.advance(30);
        tracks.clear();
        assertEquals(0, tracks.prints());
        assertEquals(0, tracks.seconds(), 0.0);
        assertEquals(0, tracks.strengthAt(20, 0), 0.0);
    }

    // --- the sheet that draws it ------------------------------------------------------------

    /**
     * A walked line is meshed as a strip that lies on the ground, faces the sky
     * and is see-through.
     *
     * <p>The height assertion is the one that matters: every corner of the sheet
     * sits {@link TrackMesher#LIFT} metres above the ground <em>under that
     * corner</em>, which is both what makes a path follow a hillside and the
     * proof that the mesh's origin is being subtracted the way the renderer will
     * add it back.
     */
    @Test
    void aWalkedLineIsMeshedAsAStripLyingOnTheGround() {
        try (ChunkStreamer streamer = new ChunkStreamer(new TerrainField(21L), 512)) {
            streamer.setViewRadius(2);
            streamer.setDetailRadius(1);
            streamer.setGrassRadius(0);
            streamer.loadNow(0, 0, 2);

            TrackField tracks = new TrackField();
            walk(tracks, 1, 0, 0, 1, 0, 40);
            Mesh mesh = TrackMesher.tracks(tracks, streamer, 0, 0, 60, 1);

            assertTrue(mesh.triangleCount() >= 40,
                    "forty metres of walking meshed as " + mesh.triangleCount()
                            + " triangles");
            assertTrue(mesh.translucent(), "a track is drawn as if it were opaque");
            assertTrue(mesh.sortBias() > 0,
                    "the decal has no sort bias, so the painter will bury it");

            float[] vertices = mesh.vertices();
            for (int v = 0; v < mesh.vertexCount(); v++) {
                int at = v * Mesh.FLOATS_PER_VERTEX;
                double wx = mesh.originX() + vertices[at];
                double wy = mesh.originY() + vertices[at + 1];
                assertEquals(streamer.groundAt(wx, wy) + TrackMesher.LIFT,
                        vertices[at + 2], 1e-3,
                        "a corner of the sheet is not sitting on the ground");
            }

            // Wound the way the ground is: counter-clockwise about a normal
            // that points up, or the painter's back-face test throws it away.
            for (int v = 0; v + 2 < mesh.vertexCount(); v += 3) {
                int a = v * Mesh.FLOATS_PER_VERTEX;
                int b = (v + 1) * Mesh.FLOATS_PER_VERTEX;
                int c = (v + 2) * Mesh.FLOATS_PER_VERTEX;
                double ux = vertices[b] - vertices[a], uy = vertices[b + 1] - vertices[a + 1];
                double vx = vertices[c] - vertices[a], vy = vertices[c + 1] - vertices[a + 1];
                assertTrue(ux * vy - uy * vx > 0, "a track triangle faces the ground");
            }
        }
    }

    /** The sheet fades with the record it is built from, and then is not built. */
    @Test
    void theSheetFadesAndThenStopsBeingBuilt() {
        try (ChunkStreamer streamer = new ChunkStreamer(new TerrainField(21L), 512)) {
            streamer.setViewRadius(2);
            streamer.setDetailRadius(1);
            streamer.setGrassRadius(0);
            streamer.loadNow(0, 0, 2);

            TrackField tracks = new TrackField();
            walk(tracks, 1, 0, 0, 1, 0, 40);
            int fresh = maxAlpha(TrackMesher.tracks(tracks, streamer, 0, 0, 60, 1));
            assertTrue(fresh > 0, "a fresh track is drawn at no opacity at all");
            assertTrue(fresh < 160, "a track is drawn as a road rather than a path");

            tracks.advance(TrackField.LIFETIME * 0.9);
            int faded = maxAlpha(TrackMesher.tracks(tracks, streamer, 0, 0, 60, 2));
            assertTrue(faded < fresh, "nine minutes did not fade the drawn track");

            tracks.advance(TrackField.LIFETIME * 0.2);
            assertEquals(0, TrackMesher.tracks(tracks, streamer, 0, 0, 60, 3).triangleCount(),
                    "an expired track is still being meshed");
        }
    }

    /** Nothing walked, nothing drawn — and no crash when there is no ground yet. */
    @Test
    void anUntroddenWorldMeshesToNothing() {
        try (ChunkStreamer streamer = new ChunkStreamer(new TerrainField(21L), 512)) {
            TrackField tracks = new TrackField();
            assertEquals(0, TrackMesher.tracks(tracks, streamer, 0, 0, 60, 1).triangleCount());
            tracks.note(1, 0, 0);
            assertEquals(0, TrackMesher.tracks(tracks, streamer, 0, 0, 60, 2).triangleCount(),
                    "one print with nothing to join it to was meshed");
        }
    }

    private static int maxAlpha(Mesh mesh) {
        int most = 0;
        for (int argb : mesh.colours()) most = Math.max(most, (argb >>> 24) & 0xFF);
        return most;
    }

    // --- through the walk itself -------------------------------------------------------------

    /**
     * The end of it: hold the forward key in a real scene, and the ground
     * behind the player is trodden.
     *
     * <p>Driven through the scene rather than through the field because every
     * interesting way this can be broken is a wiring fault — the field never
     * fed, fed from the wrong position, fed while the world is paused — and none
     * of those is visible from underneath.
     */
    @Test
    void walkingInTheWorldTreadsAPathBehindYou(@TempDir Path dir) {
        Walk walk = Walk.onDryLand(dir);
        try {
            double startX = walk.x(), startY = walk.y();
            walk.walkForward(600);

            TrackField tracks = walk.scene().tracks();
            double moved = Math.hypot(walk.x() - startX, walk.y() - startY);
            assertTrue(moved > TrackField.STRIDE * 2,
                    "the player did not actually go anywhere: " + moved + " m");
            assertTrue(tracks.prints() > 1,
                    "walking " + Math.round(moved) + " m laid " + tracks.prints()
                            + " prints");
            assertTrue(tracks.trodden(startX, startY),
                    "the ground the player started on is untrodden, " + Math.round(moved)
                            + " m after they left it");
        } finally {
            walk.close();
        }
    }

    /**
     * A walk that is left takes its tracks with it.
     *
     * <p>Not "nothing is recorded" — the first frame of the new walk puts the
     * player's own feet down again, which is the whole point of it. What must
     * be gone is the <em>path</em>: the ground the last walk wore in is ground
     * this one has never been on.
     */
    @Test
    void anotherWalkStartsOnUntroddenGround(@TempDir Path dir) {
        Walk walk = Walk.onDryLand(dir);
        try {
            double startX = walk.x(), startY = walk.y();
            walk.walkForward(300);
            assertTrue(walk.scene().tracks().trodden(startX, startY),
                    "nothing was trodden at all");

            walk.reenter();
            TrackField tracks = walk.scene().tracks();
            assertFalse(tracks.trodden(startX, startY),
                    "the last walk's path came into the next one");
            assertTrue(tracks.prints() <= 1,
                    "the last walk's prints came with it: " + tracks.prints());
        } finally {
            walk.close();
        }
    }

    /**
     * A walk, driven the way the engine drives one — the same harness the jump
     * and swim tests use, reduced to what this file asks of it.
     */
    private static final class Walk {

        private final SceneManager scenes = new SceneManager();
        private final InputManager input = new InputManager();
        private final WatchSession session;
        private final WatchGame game;
        private final WatchScene walk;
        private final WatchStore store;

        private Walk(GameContext ctx, WatchStore store, long seed) {
            this.store = store;
            walk = new WatchScene(ctx);
            scenes.setViewport(800, 480);
            scenes.register(WatchLobbyScene.NAME, new WatchLobbyScene(ctx, store));
            scenes.register(WatchScene.NAME, walk);
            game = new WatchGame(new WatchGame.Config(seed, "Tracks", 1));
            game.join(1, "Kara");
            session = WatchSession.solo(game);
            session.setSelfId(1);
            walk.adopt(session, store);
            scenes.setScene(WatchScene.NAME);
            for (int i = 0; i < 4; i++) step();
        }

        /** A walk whose first player spawns on dry land. See {@code JumpTest}. */
        static Walk onDryLand(Path dir) {
            GameContext ctx = new GameContext(null, new GameTypeStore(dir.toString()));
            WatchStore store = new WatchStore(dir.resolve("walks").toString());
            for (long seed = 1; seed < 400; seed++) {
                double ground = new TerrainField(seed).heightAt(0, 0);
                if (ground > TerrainField.WATER_LEVEL + 2) return new Walk(ctx, store, seed);
            }
            throw new IllegalStateException("no seed in four hundred puts the origin ashore");
        }

        WatchScene scene() { return walk; }

        void step() {
            input.newFrame();
            scenes.update(1 / 120.0, input);
        }

        /** Hold the forward key down for a number of frames. */
        void walkForward(int frames) {
            int key = KeyBinds.active().binding(GameAction.MOVE_UP, 0).code();
            input.keyPressed(new KeyEvent(new JPanel(), KeyEvent.KEY_PRESSED, 0, 0, key,
                    KeyEvent.CHAR_UNDEFINED));
            for (int i = 0; i < frames; i++) step();
            input.keyReleased(new KeyEvent(new JPanel(), KeyEvent.KEY_RELEASED, 0, 0, key,
                    KeyEvent.CHAR_UNDEFINED));
            step();
        }

        /**
         * Leave and come back, which is what starting a second walk does.
         *
         * <p>Through {@code adopt} before the scene change, because it is
         * {@code adopt} that closes the streamer: re-entering without it would
         * leave the last one's worker threads generating terrain for a world
         * nobody is standing in.
         */
        void reenter() {
            walk.adopt(session, store);
            scenes.setScene(WatchScene.NAME);
            for (int i = 0; i < 4; i++) step();
        }

        double x() {
            WatchPlayer me = game.player(1);
            return me == null ? 0 : me.x();
        }

        double y() {
            WatchPlayer me = game.player(1);
            return me == null ? 0 : me.y();
        }

        /** Give the scene back its streamer's worker threads. See {@code JumpTest}. */
        void close() {
            walk.adopt(null, null);
            session.close();
        }
    }
}
