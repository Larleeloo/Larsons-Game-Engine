package com.larsons.engine.watch;

import com.larsons.engine.config.GameContext;
import com.larsons.engine.config.GameTypeStore;
import com.larsons.engine.demo.WatchLobbyScene;
import com.larsons.engine.demo.WatchScene;
import com.larsons.engine.graphics.draw.RecordingTarget;
import com.larsons.engine.input.InputManager;
import com.larsons.engine.scene.SceneManager;
import com.larsons.engine.watch.life.AnimalRegistry;
import com.larsons.engine.watch.life.Hurl;
import com.larsons.engine.watch.net.WatchClient;
import com.larsons.engine.watch.net.WatchServer;
import com.larsons.engine.watch.net.WatchSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.JPanel;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A game of tag, and the four rules that make it one.
 *
 * <ol>
 *   <li><b>The party decides.</b> A suggestion is a poll, an abstention is a no,
 *       and a walk for one cannot have a round at all — because the whole point
 *       of the vote is that a chase takes everybody's afternoon whether or not
 *       they wanted one.</li>
 *   <li><b>The freeze is enforced, not requested.</b> Thirty seconds in which the
 *       host refuses to take a new position from whoever has just become it, so
 *       a client that walked anyway would be walking on its own.</li>
 *   <li><b>The water gun tags, and only for whoever is it.</b> A jet cannot catch
 *       the person who fired it, and pulling the trigger while not it does
 *       nothing at all.</li>
 *   <li><b>It is weather.</b> Saved worlds do not carry a round; a walk reopened
 *       has nobody frozen in it.</li>
 * </ol>
 */
@Timeout(180)
class TagTest {

    private WatchServer server;
    private final List<WatchClient> clients = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (WatchClient client : clients) client.close();
        clients.clear();
        if (server != null) server.stop();
    }

    /** A walk with room for a party. */
    private static WatchGame party() {
        return new WatchGame(WatchGame.Config.hosted("Tag", 90210L));
    }

    /**
     * Stand somebody at a point, over and over, so the host has taken it.
     *
     * <p>Both walkers are put at the <em>same</em> height, above whatever the
     * ground under them is doing. A jet dies on the terrain, and a test whose
     * outcome depended on the slope between two points of a procedural hillside
     * would pass or fail by seed.
     */
    private static void stand(WatchGame game, int id, double x, double y, double z,
                              double yaw) {
        for (int i = 0; i < 3; i++) {
            game.move(id, x, y, z, yaw, 0, false, 1.0 / 20);
        }
    }

    /** The height both walkers are put at: clear of the ground under either of them. */
    private static double level(WatchGame game, double ax, double ay, double bx,
                                double by) {
        return Math.max(game.field().heightAt(ax, ay), game.field().heightAt(bx, by)) + 0.5;
    }

    /** Run the world for a stretch of seconds. */
    private static void run(WatchGame game, double seconds) {
        for (double t = 0; t < seconds; t += 0.05) game.tick(0.05);
    }

    /**
     * A party of two with a round on: Larson at the origin, Kara {@code apart}
     * metres straight in front of him, and both at the same height.
     *
     * <p>Yaw zero is {@code (sin 0, −cos 0)}, which is straight down {@code −y} —
     * the engine's own convention, and the direction a jet fired from here goes.
     */
    private static WatchGame roundOn(WatchGame game, double apart) {
        game.join(1, "Larson");
        game.join(2, "Kara");
        double z = level(game, 0, 0, 0, -apart);
        stand(game, 1, 0, 0, z, 0);
        stand(game, 2, 0, -apart, z, 0);
        game.suggestTag(1);
        game.voteTag(2, true);
        game.tick(0.05);
        return game;
    }

    // --- the vote ----------------------------------------------------------------------

    @Test
    void aWalkForOneCannotStartAGameOfTag() {
        WatchGame game = new WatchGame(WatchGame.Config.solo("Alone"));
        game.join(1, "Larson");

        String said = game.suggestTag(1);
        assertNotNull(said, "the game said nothing at all");
        assertFalse(game.tag().polling(), "a poll opened on a walk with one person on it");
        assertFalse(game.tag().running(), "a round started with nobody to chase");
    }

    @Test
    void suggestingItOpensAPollAndCountsAsAYes() {
        WatchGame game = party();
        game.join(1, "Larson");
        game.join(2, "Kara");

        game.suggestTag(1);
        assertTrue(game.tag().polling(), "no poll opened");
        assertTrue(game.tag().toStart(), "the poll is asking the wrong question");
        assertEquals(1, game.tag().yes(), "whoever asked was not counted as wanting it");
        assertEquals(2, game.tag().electorate(), "the poll was put to the wrong party");
        assertTrue(game.tag().question().contains("Larson"),
                "the card does not say who asked: " + game.tag().question());
    }

    /**
     * An abstention is a no, and that is the rule the whole vote rests on: a
     * majority of the <em>party</em>, not of the votes cast, or two people out of
     * eight could start a chase while the other six were looking through
     * spyglasses.
     */
    @Test
    void aPollNeedsAMajorityOfEverybodyOnTheWalk() {
        WatchGame game = party();
        for (int i = 1; i <= 4; i++) game.join(i, "Walker " + i);

        game.suggestTag(1);
        game.voteTag(2, true);
        // Two for, two who never answered.
        assertFalse(game.tag().carries(), "two out of four carried the vote");
        run(game, Tag.VOTE_SECONDS + 1);
        assertFalse(game.tag().running(), "a round started on a minority");
        assertFalse(game.tag().polling(), "the poll never closed");

        // Three out of four does.
        game.suggestTag(1);
        game.voteTag(2, true);
        game.voteTag(3, true);
        assertTrue(game.tag().carries(), "three out of four did not carry the vote");
        game.tick(0.05);
        assertTrue(game.tag().running(), "the round did not start");
    }

    @Test
    void aPollClosesEarlyOnceEverybodyHasAnswered() {
        WatchGame game = party();
        game.join(1, "Larson");
        game.join(2, "Kara");

        game.suggestTag(1);
        game.voteTag(2, false);
        assertTrue(game.tag().settled(), "the poll did not notice everybody had answered");
        game.tick(0.05);
        assertFalse(game.tag().polling(), "the poll waited out its clock anyway");
        assertFalse(game.tag().running(), "a refused game started");
    }

    // --- the round ---------------------------------------------------------------------

    @Test
    void whoeverAskedIsItAndIsHandedTheWaterGun() {
        WatchGame game = roundOn(party(), 5);

        assertTrue(game.tag().running(), "the round did not start");
        assertEquals(1, game.tag().itId(), "the wrong walker is it");
        assertEquals("Larson", game.tag().it());
        assertTrue(game.player(1).satchel().has(Tag.GUN), "nobody was handed a water gun");
        assertFalse(game.player(2).satchel().has(Tag.GUN),
                "somebody who is not it has a water gun");
    }

    /**
     * The freeze is a refusal, not a request.
     *
     * <p>A client is the authority on where it is standing, so the only way a
     * host can hold somebody still is to decline to write down where they say
     * they are. This is that: thirty seconds of a walker sending positions and
     * the world keeping the one they were tagged at.
     */
    @Test
    void whoeverIsItCannotMoveForThirtySeconds() {
        WatchGame game = roundOn(party(), 5);
        double startX = game.player(1).x(), startY = game.player(1).y();

        assertTrue(game.tag().frozen(1), "whoever is it was not frozen");
        assertEquals(0.0, game.tag().speed(1), 1e-9, "a frozen walker may still move");
        stand(game, 1, startX + 40, startY + 40, game.player(1).z(), 0);
        assertEquals(startX, game.player(1).x(), 1e-6, "a frozen walker walked away");
        assertEquals(startY, game.player(1).y(), 1e-6, "a frozen walker walked away");

        // …and their head still turns, because being frozen is standing still
        // and counting rather than being switched off.
        game.move(1, startX, startY, game.player(1).z(), 1.2, 0.3, false, 1.0 / 20);
        assertEquals(1.2, game.player(1).yaw(), 1e-6, "a frozen walker cannot look around");

        run(game, Tag.FREEZE_SECONDS + 1);
        assertFalse(game.tag().frozen(1), "the freeze never ended");
        assertEquals(Tag.IT_SPEED, game.tag().speed(1), 1e-9,
                "whoever is it is not faster than everybody else");
        assertEquals(1.0, game.tag().speed(2), 1e-9, "somebody who is not it was sped up");

        stand(game, 1, startX + 3, startY, game.player(1).z(), 0);
        assertEquals(startX + 3, game.player(1).x(), 1e-6,
                "a thawed walker still cannot move");
    }

    // --- the water gun -----------------------------------------------------------------

    @Test
    void theWaterGunIsRefusedToEverybodyButWhoeverIsIt() {
        // Four hundred metres apart, so that firing the thing does not end the
        // round this test is trying to fire it during.
        WatchGame game = roundOn(party(), 400);
        run(game, Tag.FREEZE_SECONDS + 1);

        assertFalse(game.squirt(2), "somebody who is not it fired the water gun");
        assertTrue(game.squirt(1), "whoever is it could not fire");
        assertFalse(game.squirt(1), "the gun fired twice with no reload");
        run(game, Tag.RELOAD_SECONDS + 0.2);
        assertTrue(game.squirt(1), "the gun never reloaded");
    }

    @Test
    void aFrozenWalkerCannotFireEither() {
        WatchGame game = roundOn(party(), 5);
        assertTrue(game.tag().frozen(1));
        assertFalse(game.squirt(1), "a frozen walker fired the water gun");
    }

    /**
     * A jet leaves half a metre in front of the shooter's chest, which is well
     * inside the radius it is checked against — so without knowing whose it is,
     * every shot would tag the person who fired it on the tick they fired it.
     */
    @Test
    void aJetCannotTagThePersonWhoFiredIt() {
        // Kara is a long way off, so the only person the jet could catch is the
        // one who let it go.
        WatchGame game = roundOn(party(), 400);
        run(game, Tag.FREEZE_SECONDS + 1);

        assertTrue(game.squirt(1));
        run(game, Hurl.LIFETIME + 1);
        assertEquals(1, game.tag().itId(), "the shooter tagged themselves");
        assertTrue(game.hurls().isEmpty(), "the jet never went out");
    }

    @Test
    void aJetMakesWhoeverItCatchesItAndTheGunGoesWithIt() {
        WatchGame game = roundOn(party(), 5);
        run(game, Tag.FREEZE_SECONDS + 1);
        assertFalse(game.tag().frozen(1));

        assertTrue(game.squirt(1), "the gun did not fire");
        run(game, 0.6);

        assertEquals(2, game.tag().itId(), "the jet did not tag anybody");
        assertEquals("Kara", game.tag().it());
        assertTrue(game.tag().frozen(2), "the newly tagged walker is not frozen");
        assertEquals(Tag.FREEZE_SECONDS, game.tag().freeze(), 1.0,
                "the freeze did not start from the top");
        assertTrue(game.player(2).satchel().has(Tag.GUN),
                "the water gun did not change hands");
        assertFalse(game.player(1).satchel().has(Tag.GUN),
                "the walker who was it kept the gun");
        assertEquals(1, game.tag().tagsBy("Larson"), "nobody was credited with the tag");
    }

    // --- calling it off ----------------------------------------------------------------

    @Test
    void theSameKeyCallsItOffAndTheGunComesBack() {
        WatchGame game = roundOn(party(), 5);
        run(game, 1);

        game.suggestTag(1);
        assertTrue(game.tag().polling(), "no poll opened during a round");
        assertFalse(game.tag().toStart(), "the poll is asking to start a round that is on");
        // …and the round keeps running while the party is being asked, or
        // suggesting an end would hand whoever is it their freeze back.
        assertTrue(game.tag().running(), "the round paused for the vote");
        assertTrue(game.tag().frozen(1), "the freeze stopped counting during the vote");

        game.voteTag(2, true);
        game.tick(0.05);
        assertFalse(game.tag().running(), "the round did not end");
        assertFalse(game.player(1).satchel().has(Tag.GUN), "the water gun was left out");
    }

    @Test
    void aRoundEndsWhenWhoeverIsItGoesHome() {
        WatchGame game = roundOn(party(), 5);
        game.leave(1);
        assertFalse(game.tag().running(), "the chase outlived the person doing the chasing");
        assertEquals(0, game.tag().itId());
    }

    @Test
    void aRoundEndsWhenThereIsNobodyLeftToChase() {
        WatchGame game = roundOn(party(), 5);
        game.leave(2);
        assertFalse(game.tag().running(), "a round carried on with one person in it");
    }

    // --- it is weather -----------------------------------------------------------------

    @Test
    void aSavedWalkCarriesNoRound() {
        WatchGame game = roundOn(party(), 5);
        assertTrue(game.tag().running());
        Map<String, Object> saved = game.toMap();

        WatchGame reopened = party();
        reopened.load(saved);
        assertFalse(reopened.tag().running(),
                "a save put somebody back in a game of tag they left days ago");
        assertFalse(reopened.tag().polling(), "a save reopened a closed poll");
        assertFalse(saved.containsKey("tag"), "the round was written to disk at all");
    }

    // --- the wire ----------------------------------------------------------------------

    @Test
    void aRoundSurvivesTheWireExactly() {
        WatchGame game = roundOn(party(), 5);
        Tag copy = new Tag();
        copy.load(game.tag().toMap());

        assertTrue(copy.running());
        assertEquals(game.tag().itId(), copy.itId());
        assertEquals(game.tag().it(), copy.it());
        assertEquals(game.tag().freeze(), copy.freeze(), 0.11);
        assertTrue(copy.frozen(1), "the copy does not know who cannot move");
        // Nothing, because they are still counting — which is the number the
        // screen multiplies its own walking speed by. See Tag.speed.
        assertEquals(0.0, copy.speed(1), 1e-9,
                "a client would walk somebody the host is holding still");

        run(game, Tag.FREEZE_SECONDS + 1);
        copy.load(game.tag().toMap());
        assertEquals(Tag.IT_SPEED, copy.speed(1), 1e-9,
                "a client would walk whoever is it at everybody else's pace");
    }

    /**
     * Nothing at all while nothing is happening.
     *
     * <p>A snapshot goes out twenty times a second to eight people, and a walk
     * with no game of tag in it is nearly every walk. The field is left out
     * entirely rather than sent empty.
     */
    @Test
    void anIdleWalkSendsNothingAboutTag() {
        WatchGame game = party();
        game.join(1, "Larson");
        assertTrue(game.tag().idle(), "a fresh walk thinks a game is on");
        assertTrue(game.tag().toMap().isEmpty(), "an idle round still costs a field");
    }

    /**
     * A client that is told about a round and then not told about it again must
     * forget: the field is absent once the round is over, and a view that only
     * replaced on presence would leave one player frozen for the rest of the walk.
     */
    @Test
    void aClientForgetsARoundWhenTheFieldStopsArriving() {
        Tag mine = new Tag();
        mine.begin(3, "Kara");
        assertTrue(mine.frozen(3));

        mine.load(Map.of());
        assertFalse(mine.running(), "the client kept a round that had ended");
        assertFalse(mine.frozen(3), "somebody is still frozen after the round ended");
        assertEquals(1.0, mine.speed(3), 1e-9);
    }

    // --- over a socket -----------------------------------------------------------------

    /**
     * The whole thing end to end: two real clients, a real poll, a real freeze,
     * and a water gun that appears in one satchel and leaves another.
     */
    @Test
    void twoPeopleOnASocketCanVoteThemselvesIntoAGameOfTag() throws IOException {
        server = new WatchServer(WatchGame.Config.hosted("Chase", 4242L));
        server.start(0);
        WatchClient one = join("Larson");
        WatchClient two = join("Kara");
        until("both to be welcomed", () -> one.ready() && two.ready()
                && server.playerCount() == 2);

        one.sendTag();
        until("the poll to reach both clients",
                () -> one.view().tag().polling() && two.view().tag().polling());
        assertTrue(two.view().tag().question().contains("Larson"),
                "the card does not say who asked");

        two.sendVote(true);
        until("the round to start on both clients",
                () -> one.view().tag().running() && two.view().tag().running());

        assertEquals(one.view().selfId(), one.view().tag().itId(),
                "the walker who asked is not the one who is it");
        until("the water gun to reach the satchel of whoever is it",
                () -> one.view().satchel().has(Tag.GUN));
        assertFalse(two.view().satchel().has(Tag.GUN),
                "a walker who is not it was handed a water gun");
        assertTrue(one.view().tag().frozen(one.view().selfId()),
                "whoever is it is not frozen on their own screen");
        assertTrue(two.view().tag().frozen(one.view().selfId()),
                "the rest of the party cannot see who is frozen");
    }

    // --- the walk itself ---------------------------------------------------------------

    /**
     * The client half of the freeze, and the client half of the speed.
     *
     * <p>The host enforces the first by refusing positions, which
     * {@link #whoeverIsItCannotMoveForThirtySeconds} covers. This is the other
     * side of the same rule: the walk must not <em>try</em>. A screen that walked
     * on and was silently pulled back twenty times a second would read as the
     * game having broken rather than as being frozen — and once the count is
     * over, the same screen has to be the thing that moves faster, because the
     * host derives a player's speed from the positions they send rather than
     * setting it.
     */
    @Test
    void theWalkHoldsAFrozenWalkerStillAndThenLetsThemRun(@TempDir Path dir) {
        WatchGame game = roundOn(party(), 400);
        try (Walk walk = new Walk(dir, game)) {
            assertEquals(0.0, walk.scene.paceMultiplier(), 1e-9,
                    "the walk would move somebody the host is holding still");
            double[] from = walk.scene.position();
            walk.hold(KeyEvent.VK_W);
            for (int i = 0; i < 60; i++) walk.step();
            walk.release(KeyEvent.VK_W);
            assertEquals(from[0], walk.scene.position()[0], 1e-6,
                    "a frozen walker's own screen walked them away");
            assertEquals(from[1], walk.scene.position()[1], 1e-6,
                    "a frozen walker's own screen walked them away");

            // The count is burnt on the world's clock rather than by running a
            // thousand frames: what is being tested is what the screen does with
            // the answer, not how long the answer takes to arrive.
            run(game, Tag.FREEZE_SECONDS + 1);
            walk.step();
            assertEquals(Tag.IT_SPEED, walk.scene.paceMultiplier(), 1e-9,
                    "the walk is not moving whoever is it any faster");

            walk.hold(KeyEvent.VK_W);
            for (int i = 0; i < 60; i++) walk.step();
            walk.release(KeyEvent.VK_W);
            assertTrue(Math.hypot(walk.scene.position()[0] - from[0],
                            walk.scene.position()[1] - from[1]) > 0.5,
                    "a thawed walker still cannot move");
        }
    }

    /** The card, the banner and the needle all draw, with a round on. */
    @Test
    void theWalkDrawsWhatIsGoingOn(@TempDir Path dir) {
        WatchGame game = party();
        game.join(1, "Larson");
        game.join(2, "Kara");
        game.suggestTag(1);
        try (Walk walk = new Walk(dir, game)) {
            assertTrue(walk.scene.view().tag().polling(), "the poll did not reach the walk");
            RecordingTarget asking = new RecordingTarget(640, 400);
            walk.scenes.render(asking, 0f);
            assertTrue(asking.commands().size() > 20, "the walk drew almost nothing");

            // U is a no, and it is read above the panel branch — so it works
            // from a screen, which is exactly where somebody might be.
            walk.press(KeyEvent.VK_U);
            walk.step();
            assertFalse(game.tag().polling(), "the poll survived a settling vote");
            assertFalse(game.tag().running(), "a refused round started anyway");

            // …and again, this time voting it through, so the banner and the
            // needle are drawn as well.
            walk.press(KeyEvent.VK_T);
            game.voteTag(2, true);
            walk.step();
            assertTrue(game.tag().running(), "the walk could not start a round");
            RecordingTarget chasing = new RecordingTarget(640, 400);
            walk.scenes.render(chasing, 0f);
            assertTrue(chasing.commands().size() > 20, "the walk drew almost nothing");
        }
    }

    /** J opens the Eye Spy board, and Enter pins up whatever the cursor is on. */
    @Test
    void theEyeSpyBoardOpensAndPinsSomethingUp(@TempDir Path dir) {
        WatchGame game = party();
        game.join(1, "Larson");
        try (Walk walk = new Walk(dir, game)) {
            walk.press(KeyEvent.VK_J);
            assertEquals("bounty", walk.scene.panelName(), "J did not open the board");
            String quarry = walk.scene.panelCursor();
            assertNotNull(quarry, "the board is offering nothing to ask for");
            assertNotNull(AnimalRegistry.byKey(quarry),
                    "the board offered something that is not an animal: " + quarry);

            RecordingTarget target = new RecordingTarget(640, 400);
            walk.scenes.render(target, 0f);
            assertTrue(target.commands().size() > 20, "the board drew almost nothing");

            walk.press(KeyEvent.VK_ENTER);
            assertEquals(1, game.bounties().openCount(), "nothing was pinned up");
            assertEquals(quarry, game.bounties().open().get(0).species(),
                    "the wrong animal was asked for");
            assertEquals("Larson", game.bounties().open().get(0).poster());
        }
    }

    /**
     * A walk driven the way the engine drives it: a scene over a solo session,
     * ticked and drawn with no window and no socket.
     */
    private static final class Walk implements AutoCloseable {

        final SceneManager scenes = new SceneManager();
        final WatchScene scene;
        private final WatchSession session;
        private final InputManager input = new InputManager();

        Walk(Path dir, WatchGame game) {
            GameContext ctx = new GameContext(null, new GameTypeStore(dir.toString()));
            WatchStore store = new WatchStore(dir.resolve("walks").toString());
            scene = new WatchScene(ctx);
            scenes.setViewport(640, 400);
            scenes.register(WatchLobbyScene.NAME, new WatchLobbyScene(ctx, store));
            scenes.register(WatchScene.NAME, scene);
            session = WatchSession.solo(game);
            session.setSelfId(1);
            scene.adopt(session, store);
            scenes.setScene(WatchScene.NAME);
            for (int i = 0; i < 6; i++) step();
        }

        void step() {
            input.newFrame();
            scenes.update(1 / 120.0, input);
        }

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

        @Override public void close() {
            scene.adopt(null, null);
            session.close();
        }
    }

    private WatchClient join(String name) throws IOException {
        WatchClient client = WatchClient.connect("127.0.0.1", server.port(), name);
        clients.add(client);
        return client;
    }

    private void until(String what, java.util.function.BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            for (WatchClient client : clients) client.pump();
            if (condition.getAsBoolean()) return;
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        for (WatchClient client : clients) client.pump();
        assertTrue(condition.getAsBoolean(), "timed out waiting for: " + what);
    }
}
