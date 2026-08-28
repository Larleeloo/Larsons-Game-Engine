package com.larsons.engine.watch;

import com.larsons.engine.config.GameContext;
import com.larsons.engine.config.GameTypeStore;
import com.larsons.engine.demo.WatchLobbyScene;
import com.larsons.engine.demo.WatchScene;
import com.larsons.engine.graphics.EyeCamera;
import com.larsons.engine.graphics.draw.RecordingTarget;
import com.larsons.engine.input.InputManager;
import com.larsons.engine.scene.SceneManager;
import com.larsons.engine.watch.life.Animal;
import com.larsons.engine.watch.net.WatchSession;
import com.larsons.engine.watch.world.ChunkStreamer;
import com.larsons.engine.watch.world.TerrainField;
import com.larsons.engine.watch.world.WatchBiome;
import com.larsons.engine.watch.world.WatchBiomes;
import com.larsons.engine.watch.world.WatchChunk;
import com.larsons.engine.watch.world.WatchMaterial;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.JPanel;
import java.awt.event.KeyEvent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The spyglass: that it is optics rather than a crop, that the world it looks
 * at is actually built, and that anybody can make one.
 *
 * <p>The three claims this file exists to hold down, in order:
 *
 * <ol>
 *   <li><b>It magnifies.</b> A thing four hundred metres away projects ×8
 *       larger at ×8 — because the camera's focal length changed, not because
 *       a finished frame was scaled.</li>
 *   <li><b>There is something there to see.</b> The ground down the line of
 *       sight is streamed at full detail far outside the ordinary view radius,
 *       and the animals are not dropped out from under the person watching
 *       them.</li>
 *   <li><b>You can make one.</b> Every ingredient exists, is obtainable from
 *       ground this world actually generates, and the two-step chain runs
 *       through the game's own crafting.</li>
 * </ol>
 */
@Timeout(180)
class SpyglassTest {

    // --- 1. it is optics -----------------------------------------------------------

    /**
     * Magnification is the ratio of tangents, which is what makes "×8" a claim
     * rather than a label.
     */
    @Test
    void everyStopIsATrueMagnification() {
        double rest = EyeCamera.DEFAULT_FOV;
        for (double power : Spyglass.POWERS) {
            double fov = Spyglass.fovFor(rest, power);
            assertEquals(Math.tan(rest / 2) / power, Math.tan(fov / 2), 1e-9,
                    "×" + power + " is not " + power + " times anything");
            assertTrue(fov < rest, "×" + power + " did not narrow the view at all");
        }
        assertEquals(rest, Spyglass.fovFor(rest, Spyglass.NONE), 1e-9,
                "a closed tube changed the view");
    }

    /**
     * The camera can actually be set to it.
     *
     * <p>{@code EyeCamera} used to refuse anything under twenty degrees, which
     * is ×3.5 — so the top two stops would have been silently clamped and the
     * glass would have lied about its own power. This is that clamp, tested at
     * the number that matters.
     */
    @Test
    void theCameraTakesTheNarrowestStopWithoutClamping() {
        EyeCamera eye = new EyeCamera(1280, 720);
        double top = Spyglass.POWERS[Spyglass.POWERS.length - 1];
        double wanted = Spyglass.fovFor(EyeCamera.DEFAULT_FOV, top);
        eye.setFov(wanted);
        assertEquals(wanted, eye.fov(), 1e-9,
                "the camera clamped the top stop, so ×" + top + " is not ×" + top);
        assertTrue(wanted > EyeCamera.MIN_FOV,
                "the top stop is at the very floor of what the camera allows, "
                        + "which leaves no room for a longer glass ever");
    }

    /**
     * The point of all of it: a distant thing is drawn larger, in proportion.
     *
     * <p>A crop of a finished frame would give the same number and no more
     * pixels; this measures the projection itself, which is where the extra
     * detail comes from.
     */
    @Test
    void adistantThingProjectsLargerInProportionToThePower() {
        EyeCamera eye = new EyeCamera(1280, 720);
        double far = 400;
        eye.setFov(EyeCamera.DEFAULT_FOV);
        double unaided = eye.scaleAt(far);

        for (double power : Spyglass.POWERS) {
            eye.setFov(Spyglass.fovFor(EyeCamera.DEFAULT_FOV, power));
            double through = eye.scaleAt(far);
            assertEquals(power, through / unaided, 0.02,
                    "a bird at " + far + " m is not ×" + power + " bigger at ×" + power);
        }
    }

    /** Raising, lowering and changing stops all travel rather than snapping. */
    @Test
    void theTubeTravelsAndSettlesOnItsStop() {
        Spyglass glass = new Spyglass();
        glass.setStop(2);
        assertFalse(glass.up(), "a glass nobody has raised is up");

        glass.tick(1 / 60.0, true, 1);
        assertTrue(glass.power() > Spyglass.NONE, "the tube did not begin to open");
        assertTrue(glass.power() < glass.stopPower(),
                "the tube snapped straight to its stop instead of travelling");

        for (int i = 0; i < 120; i++) glass.tick(1 / 60.0, true, 1);
        assertEquals(glass.stopPower(), glass.power(), 1e-9,
                "the tube never reached its stop");
        assertEquals(1, glass.deployment(), 1e-9);
        assertEquals(Spyglass.rangeFor(glass.stopPower()), glass.range(), 1e-9);

        for (int i = 0; i < 120; i++) glass.tick(1 / 60.0, false, 1);
        assertEquals(Spyglass.NONE, glass.power(), 1e-9, "the tube would not close");
        assertFalse(glass.up());
    }

    /**
     * Turning is slowed by the magnification, or the glass is unusable at
     * exactly the power it exists for.
     */
    @Test
    void lookingSlowsDownWithThePower() {
        Spyglass glass = new Spyglass();
        assertEquals(1, glass.lookScale(), 1e-9, "a closed tube slowed the mouse down");
        glass.setStop(1);
        for (int i = 0; i < 200; i++) glass.tick(1 / 60.0, true, 1);
        assertEquals(1 / glass.stopPower(), glass.lookScale(), 1e-6);
    }

    /**
     * It shakes, and standing still is what steadies it — which is how the
     * glass is wired into the one stat this game already has.
     */
    @Test
    void theViewWandersAndStillnessSettlesIt() {
        Spyglass jittery = new Spyglass();
        Spyglass steady = new Spyglass();
        jittery.setStop(2);
        steady.setStop(2);

        double worst = 0, best = 0;
        for (int i = 0; i < 400; i++) {
            jittery.tick(1 / 60.0, true, 0);
            steady.tick(1 / 60.0, true, 1);
            worst = Math.max(worst, Math.abs(jittery.swayYaw(EyeCamera.DEFAULT_FOV)));
            best = Math.max(best, Math.abs(steady.swayYaw(EyeCamera.DEFAULT_FOV)));
        }
        assertTrue(worst > 0, "the glass does not shake at all, at a dead run");
        assertTrue(best < worst * 0.5,
                "standing still barely steadied the glass: " + best + " vs " + worst);
        assertTrue(worst < Spyglass.fovFor(EyeCamera.DEFAULT_FOV, 15),
                "the shake is wider than the whole field of view");

        Spyglass closed = new Spyglass();
        closed.tick(1 / 60.0, false, 0);
        assertEquals(0, closed.swayYaw(EyeCamera.DEFAULT_FOV), 1e-12,
                "a glass in the satchel is shaking the naked eye");
    }

    /** Pointing gets more exact with the power, not merely longer-ranged. */
    @Test
    void aGlassPicksOneBirdOutOfTheFlock() {
        double loose = Spyglass.tolerance(0.14, 300, Spyglass.NONE);
        double tight = Spyglass.tolerance(0.14, 300, 15);
        assertEquals(loose / 15, tight, 1e-9);
        assertTrue(tight < loose, "the glass did not sharpen the aim");
    }

    // --- 2. the world it looks at ---------------------------------------------------

    /**
     * The claim in the brief, as a measurement: with a glass on it, ground far
     * outside the ordinary view radius is built, <b>and built finely</b>.
     *
     * <p>Without the focus that ground does not exist at all; with it, the
     * chunks down the line are at the finest level of detail, which is the
     * difference between a distant hill that has trunks and bushes on it and
     * one that is four triangles and a green smear.
     */
    @Test
    void aRaisedGlassStreamsDistantGroundAtFullDetail() {
        TerrainField field = new TerrainField(20260828L);
        try (ChunkStreamer streamer = new ChunkStreamer(field)) {
            streamer.setViewRadius(4);
            streamer.setDetailRadius(2);

            // Due north — dirY is negative for a yaw of zero, as EyeCamera
            // means it, and the chunk twelve out that way is three times
            // further than anything the ring would build.
            int far = 12;
            streamer.setFocus(ChunkStreamer.Focus.looking(16, 16, 0, far + 2, 0.30, 8));
            streamer.loadNow(16, 16, 0);

            WatchChunk beyond = null;
            for (int tries = 0; tries < 40 && beyond == null; tries++) {
                streamer.update(16, 16, 1 / 60.0);
                settle(streamer);
                beyond = streamer.chunk(0, -far);
            }
            assertNotNull(beyond, "the glass is pointed at ground that was never built");
            assertEquals(0, beyond.lod(),
                    "the ground under the glass was built at level " + beyond.lod()
                            + " — a distant hill, drawn distant");
        }
    }

    /**
     * …and without a glass on it, that ground does not exist at all.
     *
     * <p>Which is the measurement that makes the previous test mean something:
     * the same streamer, the same seed, the same forty frames, and the chunk
     * twelve out is simply not there. What the glass does is not "sharpen
     * something that was already drawn".
     */
    @Test
    void theSameGroundIsNotBuiltWithoutTheGlass() {
        TerrainField field = new TerrainField(20260828L);
        try (ChunkStreamer streamer = new ChunkStreamer(field)) {
            streamer.setViewRadius(4);
            streamer.setDetailRadius(2);
            for (int i = 0; i < 40; i++) {
                streamer.update(16, 16, 1 / 60.0);
                settle(streamer);
            }
            assertNull(streamer.chunk(0, -12),
                    "ground twelve chunks out was built with no glass up, so the ring "
                            + "is already doing the glass's work and this proves nothing");
            // The near ring is very much there, so this is not a streamer that
            // failed to start.
            assertNotNull(streamer.chunk(0, -1), "nothing at all was streamed");
        }
    }

    /** A chunk the glass is looking at is not evicted out from under it. */
    @Test
    void groundUnderTheGlassSurvivesTheEviction() {
        TerrainField field = new TerrainField(77L);
        try (ChunkStreamer streamer = new ChunkStreamer(field)) {
            streamer.setViewRadius(3);
            streamer.setDetailRadius(1);
            streamer.setFocus(ChunkStreamer.Focus.looking(16, 16, 0, 14, 0.30, 8));

            WatchChunk beyond = null;
            for (int tries = 0; tries < 60 && beyond == null; tries++) {
                streamer.update(16, 16, 1 / 60.0);
                settle(streamer);
                beyond = streamer.chunk(0, -11);
            }
            assertNotNull(beyond, "nothing was built down the glass");

            for (int i = 0; i < 20; i++) streamer.update(16, 16, 1 / 60.0);
            assertNotNull(streamer.chunk(0, -11),
                    "the chunk being looked at was evicted while it was on screen");

            // Put the glass away and it goes, like any other ground walked away
            // from — into the cache, not into the bin.
            streamer.setFocus(null);
            streamer.update(16, 16, 1 / 60.0);
            assertNull(streamer.chunk(0, -11),
                    "distant ground is still held after the glass came down");
        }
    }

    /** The cone is a cone: what is behind you is not in it. */
    @Test
    void theConeOnlyHoldsWhatIsInFrontOfIt() {
        ChunkStreamer.Focus north = ChunkStreamer.Focus.looking(0, 0, 0, 20, 0.25, 8);
        assertTrue(north.holds(0, -10), "the chunk straight ahead is not in the cone");
        assertFalse(north.holds(0, 10), "the cone reaches out behind the player");
        assertFalse(north.holds(15, -10), "the cone is wider than it says it is");
        assertFalse(north.holds(0, -30), "the cone reaches past its own range");
        assertTrue(north.holds(0, 0), "the chunk under the player's feet is not in the cone");
    }

    /**
     * Animals exist out there to be looked at.
     *
     * <p>The ring is a hundred metres and a spot at ×15 is nine hundred, so
     * without this the beautifully-drawn far shore would be empty and the
     * glass would be scenery. A share of a glassing player's roster is spawned
     * down the line they are looking, and nothing being watched is dropped.
     */
    @Test
    void thingsAppearOutWhereTheGlassIsLooking() {
        WatchGame game = new WatchGame(new WatchGame.Config(4242L, "Glassing", 1));
        WatchPlayer me = game.join(1, "Kara");
        me.satchel().add(Spyglass.ITEM, 1);
        assertEquals(15, game.glass(1, 15), 1e-9, "the host refused a glass we are carrying");

        double furthest = 0;
        for (int i = 0; i < 900; i++) {
            game.move(1, me.x(), me.y(), me.z(), 0, 0, false, 1 / 20.0);
            game.tick(1 / 20.0);
            for (Animal animal : game.animals()) {
                furthest = Math.max(furthest,
                        Math.hypot(animal.x() - me.x(), animal.y() - me.y()));
            }
        }
        assertTrue(furthest > 250,
                "nothing ever appeared further out than " + Math.round(furthest)
                        + " m, so a ×15 glass has nothing to look at");
    }

    /**
     * …and one of them can be written down, which is the game's one verb.
     *
     * <p>The same animal, the same direction, with the glass up and with it
     * down: the host allows the far one only through the glass.
     */
    @Test
    void aFarAnimalCanBeSpottedThroughTheGlassAndNotWithout() {
        WatchGame game = new WatchGame(new WatchGame.Config(1234L, "Far Shore", 1));
        WatchPlayer me = game.join(1, "Kara");
        me.satchel().add(Spyglass.ITEM, 1);
        game.glass(1, 15);

        Animal far = null;
        for (int i = 0; i < 900 && far == null; i++) {
            game.move(1, me.x(), me.y(), me.z(), 0, 0, false, 1 / 20.0);
            game.tick(1 / 20.0);
            for (Animal animal : game.animals()) {
                double distance = Math.hypot(animal.x() - me.x(), animal.y() - me.y());
                if (distance > WatchGame.SPOT_RANGE + 60) far = animal;
            }
        }
        assertNotNull(far, "nothing ever got far enough away to need a glass");

        // Look straight at it, in the engine's own convention.
        double dx = far.x() - me.x(), dy = far.y() - me.y();
        double dz = far.z() + far.def().bodyLength() * 0.5 - me.eyeZ();
        double yaw = Math.atan2(dx, -dy);
        double pitch = Math.atan2(dz, Math.hypot(dx, dy));
        game.move(1, me.x(), me.y(), me.z(), yaw, pitch, false, 1 / 20.0);

        assertEquals(far, game.lookingAt(1),
                "a glass at ×15 could not pick out an animal "
                        + Math.round(Math.hypot(dx, dy)) + " m away");
        assertTrue(game.spotRange(1) > WatchGame.SPOT_RANGE * 4,
                "the host did not extend this player's reach");

        game.glass(1, Spyglass.NONE);
        assertNotEquals(far, game.lookingAt(1),
                "the same animal is visible to the naked eye, so the glass changed nothing");
        assertEquals(WatchGame.SPOT_RANGE, game.spotRange(1), 1e-9);
    }

    /** A glass nobody is carrying does not work, however loudly a client asks. */
    @Test
    void theHostRefusesAGlassNobodyHas() {
        WatchGame game = new WatchGame(new WatchGame.Config(9L, "Honest", 1));
        WatchPlayer me = game.join(1, "Kara");
        assertEquals(Spyglass.NONE, game.glass(1, 15), 1e-9,
                "a player with no spyglass was given a ×15 view of the world");
        assertEquals(WatchGame.SPOT_RANGE, game.spotRange(1), 1e-9);

        me.satchel().add(Spyglass.ITEM, 1);
        assertEquals(15, game.glass(1, 15), 1e-9);
        assertTrue(me.glassing());
    }

    /**
     * The whole thing, through the scene the player actually plays: hold the
     * key with a glass in the satchel and the camera narrows, the streamer is
     * pointed, and the host is told.
     *
     * <p>No window and no card — a {@link RecordingTarget}, which reports no
     * mesh pass, so this is the software path and the shorter cone that goes
     * with it. It still has to be a cone.
     */
    @Test
    void raisingTheGlassInTheWalkNarrowsTheViewAndPointsTheStreamer(@TempDir Path dir) {
        GameContext ctx = new GameContext(null, new GameTypeStore(dir.toString()));
        WatchStore store = new WatchStore(dir.resolve("walks").toString());
        WatchScene walk = new WatchScene(ctx);
        SceneManager scenes = new SceneManager();
        scenes.setViewport(800, 480);
        scenes.register(WatchLobbyScene.NAME, new WatchLobbyScene(ctx, store));
        scenes.register(WatchScene.NAME, walk);

        WatchGame game = new WatchGame(new WatchGame.Config(555L, "Glassing", 1));
        WatchPlayer me = game.join(1, "Kara");
        me.satchel().add(Spyglass.ITEM, 1);
        WatchSession session = WatchSession.solo(game);
        session.setSelfId(1);
        walk.adopt(session, store);
        scenes.setScene(WatchScene.NAME);

        InputManager input = new InputManager();
        for (int i = 0; i < 4; i++) {
            input.newFrame();
            scenes.update(1 / 60.0, input);
        }
        RecordingTarget target = new RecordingTarget(800, 480);
        scenes.render(target, 0f);
        double unaided = walk.camera().fov();
        assertEquals(EyeCamera.DEFAULT_FOV, unaided, 1e-9,
                "the walk does not start at the ordinary field of view");
        assertNull(walk.streamer().focus(), "the streamer is pointed with no glass up");

        // Hold it. The key goes down once and stays down, which is what a held
        // binding is; a second of frames is more than the tube needs.
        input.newFrame();
        input.keyPressed(new KeyEvent(new JPanel(), KeyEvent.KEY_PRESSED, 0, 0,
                KeyEvent.VK_Z, KeyEvent.CHAR_UNDEFINED));
        for (int i = 0; i < 60; i++) {
            scenes.update(1 / 60.0, input);
            input.newFrame();
        }
        // The camera is placed as a frame is drawn, so the fov is only true
        // after one — which is also the honest place to read it from.
        scenes.render(new RecordingTarget(800, 480), 0f);

        double through = walk.camera().fov();
        assertTrue(through < unaided * 0.5,
                "holding the glass barely changed the view: " + Math.toDegrees(through)
                        + "° against " + Math.toDegrees(unaided) + "°");
        assertEquals(Spyglass.fovFor(EyeCamera.DEFAULT_FOV, Spyglass.POWERS[0]), through,
                1e-6, "the camera is not at the first stop's field of view");
        assertTrue(me.glassing(), "the host was never told the glass went up");

        ChunkStreamer.Focus focus = walk.streamer().focus();
        assertNotNull(focus, "the streamer was never pointed down the glass");
        assertTrue(focus.radius() > walk.streamer().viewRadius(),
                "the cone reaches no further than the ordinary ring, so nothing new "
                        + "is being built");
        assertTrue(focus.magnification() > 1,
                "the cone was handed no magnification, so its ground stays coarse");

        // And it draws, eyepiece and all.
        RecordingTarget glassed = new RecordingTarget(800, 480);
        assertDoesNotThrow(() -> scenes.render(glassed, 0f), "drawing through the glass threw");
        assertTrue(glassed.commands().size() > target.commands().size(),
                "the eyepiece drew nothing over the world");

        // Let go and everything goes back.
        input.newFrame();
        input.keyReleased(new KeyEvent(new JPanel(), KeyEvent.KEY_RELEASED, 0, 0,
                KeyEvent.VK_Z, KeyEvent.CHAR_UNDEFINED));
        for (int i = 0; i < 60; i++) {
            scenes.update(1 / 60.0, input);
            input.newFrame();
        }
        scenes.render(new RecordingTarget(800, 480), 0f);
        assertEquals(unaided, walk.camera().fov(), 1e-9, "the view stayed zoomed in");
        assertNull(walk.streamer().focus(), "the streamer is still pointed down a closed tube");
        assertFalse(me.glassing(), "the host still thinks the glass is up");

        session.close();
    }

    /** …and a walker with no spyglass gets nothing at all for holding the key. */
    @Test
    void aWalkerWithNoGlassHoldsTheKeyAndNothingHappens(@TempDir Path dir) {
        GameContext ctx = new GameContext(null, new GameTypeStore(dir.toString()));
        WatchStore store = new WatchStore(dir.resolve("walks").toString());
        WatchScene walk = new WatchScene(ctx);
        SceneManager scenes = new SceneManager();
        scenes.setViewport(800, 480);
        scenes.register(WatchLobbyScene.NAME, new WatchLobbyScene(ctx, store));
        scenes.register(WatchScene.NAME, walk);

        WatchGame game = new WatchGame(new WatchGame.Config(556L, "Empty Handed", 1));
        WatchPlayer me = game.join(1, "Kara");
        WatchSession session = WatchSession.solo(game);
        session.setSelfId(1);
        walk.adopt(session, store);
        scenes.setScene(WatchScene.NAME);

        InputManager input = new InputManager();
        input.newFrame();
        input.keyPressed(new KeyEvent(new JPanel(), KeyEvent.KEY_PRESSED, 0, 0,
                KeyEvent.VK_Z, KeyEvent.CHAR_UNDEFINED));
        for (int i = 0; i < 60; i++) {
            scenes.update(1 / 60.0, input);
            input.newFrame();
        }
        scenes.render(new RecordingTarget(800, 480), 0f);
        assertEquals(EyeCamera.DEFAULT_FOV, walk.camera().fov(), 1e-9,
                "a walker with an empty satchel is looking through a telescope");
        assertFalse(me.glassing());
        assertNull(walk.streamer().focus());
        session.close();
    }

    // --- 3. making one --------------------------------------------------------------

    /** The instrument, its lens and their materials are all real things. */
    @Test
    void everythingTheGlassIsMadeOfIsAThing() {
        for (String key : List.of(Spyglass.ITEM, "lens", "quartz", "sand")) {
            Forage.Item item = Forage.byKey(key);
            assertNotNull(item, "'" + key + "' is used by a recipe and is not an item");
            assertTrue(item.name() != null && !item.name().isBlank(),
                    key + " has no name to print in the satchel");
            assertTrue(item.note() != null && !item.note().isBlank(),
                    key + " has nothing to say about itself");
        }
        assertEquals(Forage.Kind.TOOL, Forage.byKey(Spyglass.ITEM).kind());
        assertFalse(Forage.byKey(Spyglass.ITEM).edible(),
                "the spyglass can be put on a feeder");
    }

    /** Every input of every recipe is an item — the glass's two included. */
    @Test
    void noRecipeAsksForSomethingThatDoesNotExist() {
        for (Recipes.Recipe recipe : Recipes.all()) {
            assertNotNull(Forage.byKey(recipe.output()),
                    recipe.output() + " is made and is not a thing");
            for (String input : recipe.inputs().keySet()) {
                assertNotNull(Forage.byKey(input),
                        recipe.output() + " is made of '" + input + "', which is not a thing");
            }
        }
    }

    /**
     * The chain runs, through the game's own crafting, out of nothing but
     * things picked off the ground.
     */
    @Test
    void aSpyglassCanBeMadeOutOfWhatTheGroundGivesYou() {
        WatchGame game = new WatchGame(WatchGame.Config.solo("Workshop"));
        WatchPlayer me = game.join(1, "Kara");
        Satchel bag = me.satchel();

        Recipes.Recipe lens = Recipes.making("lens");
        Recipes.Recipe glass = Recipes.making(Spyglass.ITEM);
        assertNotNull(lens, "there is no way to make a lens");
        assertNotNull(glass, "there is no way to make a spyglass");

        assertFalse(game.craft(1, glass, glass.station()),
                "a spyglass was made out of an empty satchel");

        // Raw materials only: two lenses' worth of quartz and sand, and the
        // tube's own timber and cordage. Everything here is a key the ground
        // hands out — see `theGroundActuallyHandsOutQuartzAndSand`.
        for (Map.Entry<String, Integer> need : lens.inputs().entrySet()) {
            bag.add(need.getKey(), need.getValue() * 2);
        }
        assertTrue(game.craft(1, lens, lens.station()), "the first lens would not grind");
        assertTrue(game.craft(1, lens, lens.station()), "the second lens would not grind");
        assertTrue(bag.has("lens", 2), "two grinds did not make two lenses");

        for (Map.Entry<String, Integer> need : glass.inputs().entrySet()) {
            if (need.getKey().equals("lens")) continue;
            bag.add(need.getKey(), need.getValue());
        }
        assertTrue(game.craft(1, glass, glass.station()), "the tube would not go together");
        assertTrue(bag.has(Spyglass.ITEM), "the spyglass is not in the satchel");
        assertFalse(bag.has("lens"), "the lenses were not used up");

        // …and it works the moment it is in the bag.
        assertEquals(8, game.glass(1, 8), 1e-9);
    }

    /**
     * It is not free, and it is not the first thing anybody makes.
     *
     * <p>A glass that cost two branches would be in every satchel in the first
     * clearing, and "the far shore" would stop being somewhere you walk to.
     */
    @Test
    void theGlassIsTheDeepestThingInTheBook() {
        Recipes.Recipe glass = Recipes.making(Spyglass.ITEM);
        assertEquals(Recipes.Station.BENCH, glass.station(),
                "a spyglass can be assembled in bare hands in a field");
        assertTrue(glass.inputs().size() >= 3, "the tube costs almost nothing");

        // At least one input is itself something you have to make.
        boolean deep = false;
        for (String input : glass.inputs().keySet()) {
            if (Recipes.making(input) != null) deep = true;
        }
        assertTrue(deep, "every ingredient can be picked up, so there is no chain at all");
        assertTrue(glass.costLine().contains("Lens") || glass.costLine().contains("lens"),
                "the cost line does not mention the lenses: " + glass.costLine());
    }

    /**
     * The ground this world generates actually hands out quartz and sand.
     *
     * <p>Two halves, and both are needed: the <em>table</em> has to offer them
     * on rock and on sand, and the <em>world</em> has to have rock and sand in
     * it somewhere a walker could stand. A recipe whose materials are only
     * theoretically obtainable is not a craftable item.
     */
    @Test
    void theGroundActuallyHandsOutQuartzAndSand() {
        Set<String> fromSand = new TreeSet<>(
                Forage.underfoot(WatchBiomes.defaultBiome(), WatchMaterial.SAND));
        Set<String> fromRock = new TreeSet<>(
                Forage.underfoot(WatchBiomes.defaultBiome(), WatchMaterial.ROCK));
        assertTrue(fromSand.contains("sand"), "a dune gives up no sand: " + fromSand);
        assertTrue(fromSand.contains("quartz"), "a dune gives up no quartz: " + fromSand);
        assertTrue(fromRock.contains("quartz"), "bare rock gives up no quartz: " + fromRock);
        assertFalse(Forage.underfoot(WatchBiomes.defaultBiome(), WatchMaterial.LUSH_GRASS)
                .contains("sand"), "a meadow is handing out beach sand");

        // And the world has both kinds of ground in it, within a walk of the
        // origin — sampled off the generator rather than assumed.
        TerrainField field = new TerrainField(20260828L);
        WatchGame game = new WatchGame(new WatchGame.Config(20260828L, "Survey", 1));
        boolean sandy = false, rocky = false;
        for (int i = 0; i < 4000 && !(sandy && rocky); i++) {
            // A coarse spiral out to a couple of kilometres.
            double angle = i * 0.7;
            double radius = i * 0.6;
            double x = Math.cos(angle) * radius, y = Math.sin(angle) * radius;
            WatchBiome biome = field.biomeAt(x, y);
            List<String> yield = Forage.underfoot(biome,
                    game.surfaceUnderfoot(biome, x, y));
            if (yield.contains("sand")) sandy = true;
            if (yield.contains("quartz")) rocky = true;
        }
        assertTrue(rocky, "there is no ground within two kilometres that yields quartz");
        assertTrue(sandy, "there is no ground within two kilometres that yields sand");
    }

    /** Whatever a player picks off the ground is something that ground has. */
    @Test
    void pickingTakesWhatIsUnderfootAndNothingElse() {
        WatchGame game = new WatchGame(new WatchGame.Config(31337L, "Beachcomb", 8));
        List<String> taken = new ArrayList<>();
        for (int id = 1; id <= WatchGame.MAX_PLAYERS; id++) {
            WatchPlayer player = game.join(id, "Walker " + id);
            String got = game.pick(id);
            if (got != null) taken.add(got);

            WatchBiome biome = game.field().biomeAt(player.x(), player.y());
            List<String> possible = new ArrayList<>(Forage.underfoot(biome,
                    game.surfaceUnderfoot(biome, player.x(), player.y())));
            possible.addAll(biome.seeds());
            possible.addAll(biome.berries());
            if (got != null) {
                assertTrue(possible.contains(got),
                        "picked '" + got + "' off ground that does not have it: " + possible);
            }
        }
        assertFalse(taken.isEmpty(), "eight people foraged and nobody found anything");
    }

    // --- helpers ---------------------------------------------------------------------

    /** Wait for the streamer's workers to finish whatever they were queued. */
    private static void settle(ChunkStreamer streamer) {
        long deadline = System.currentTimeMillis() + 20_000;
        while (streamer.pending() > 0 && System.currentTimeMillis() < deadline) {
            Thread.yield();
        }
    }
}
