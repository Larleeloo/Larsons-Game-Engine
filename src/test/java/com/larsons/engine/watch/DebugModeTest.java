package com.larsons.engine.watch;

import com.larsons.engine.config.GameContext;
import com.larsons.engine.config.GameTypeStore;
import com.larsons.engine.demo.WatchLobbyScene;
import com.larsons.engine.demo.WatchScene;
import com.larsons.engine.graphics.draw.RecordingTarget;
import com.larsons.engine.input.InputManager;
import com.larsons.engine.scene.SceneManager;
import com.larsons.engine.watch.build.BuildPiece;
import com.larsons.engine.watch.life.Animal;
import com.larsons.engine.watch.net.WatchSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.JPanel;
import java.awt.event.KeyEvent;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Debug mode: the code, what it grants, and — the point of the whole design —
 * that what it grants is not a list anybody has to keep up to date.
 *
 * <p>The claim under test in {@link #everythingInTheGameIsFreeIncludingWhatIsAddedNext}
 * is the one that matters: the mode is a lens over the registries rather than a
 * table of grants, so a recipe, a build piece or an item added next month is
 * covered by it without this file or that one being edited.
 */
@Timeout(180)
class DebugModeTest {

    private static WatchGame solo() {
        return new WatchGame(WatchGame.Config.solo("Debug Walk"));
    }

    // --- the code --------------------------------------------------------------------

    @Test
    void theCodeIsDigitsAndNothingElse() {
        assertTrue(Debug.CODE.matches("\\d{3,}"),
                "the code must be a number somebody can type on the number keys: "
                        + Debug.CODE);
        assertTrue(Debug.isCode(Debug.CODE));
        assertFalse(Debug.isCode(""), "an empty string is the code");
        assertFalse(Debug.isCode(Debug.CODE + "0"), "anything ending in the code is the code");
    }

    @Test
    void thePadSpellsTheCodeAndOnlyTheCode() {
        Debug.Pad pad = new Debug.Pad();
        boolean landed = false;
        for (char c : Debug.CODE.toCharArray()) landed = pad.type(c - '0');
        assertTrue(landed, "typing the code did not land it");
        assertEquals(0, pad.pending(), "the pad kept the code after using it");

        // …and again, so one code can turn it on and off.
        for (char c : Debug.CODE.toCharArray()) landed = pad.type(c - '0');
        assertTrue(landed, "the code only works once");
    }

    @Test
    void aFumbledFirstKeyDoesNotRuinTheCode() {
        Debug.Pad pad = new Debug.Pad();
        // A rolling buffer: junk in front of the code is junk, not a failure.
        pad.type(1);
        pad.type(2);
        boolean landed = false;
        for (char c : Debug.CODE.toCharArray()) landed = pad.type(c - '0');
        assertTrue(landed, "a stray keypress before the code broke it");
    }

    @Test
    void aHalfTypedCodeIsForgotten() {
        Debug.Pad pad = new Debug.Pad();
        pad.type(Debug.CODE.charAt(0) - '0');
        assertEquals(1, pad.pending());
        pad.tick(Debug.FORGET_SECONDS + 0.1);
        assertEquals(0, pad.pending(),
                "a half-typed code waits for ever, so an old keypress can complete "
                        + "a code nobody typed");

        // The rest of the code, arriving late, does not land on its own.
        boolean landed = false;
        for (int i = 1; i < Debug.CODE.length(); i++) {
            landed = pad.type(Debug.CODE.charAt(i) - '0');
        }
        assertFalse(landed, "a forgotten code completed itself anyway");
    }

    @Test
    void nonDigitsAreNotDigits() {
        Debug.Pad pad = new Debug.Pad();
        assertFalse(pad.type(-1));
        assertFalse(pad.type(10));
        assertEquals(0, pad.pending(), "something that is not a digit went into the pad");
    }

    // --- what it grants ---------------------------------------------------------------

    @Test
    void theCodeTurnsItOnAndTheCodeTurnsItOff() {
        WatchGame game = solo();
        WatchPlayer me = game.join(1, "Kara");
        assertFalse(me.debugging(), "a walk starts in debug mode");

        assertTrue(game.debug(1, Debug.CODE), "the code did nothing");
        assertTrue(me.debugging());
        assertTrue(me.satchel().bottomless(), "the satchel is not bottomless");

        assertFalse(game.debug(1, Debug.CODE), "the code would not turn it off again");
        assertFalse(me.debugging());
        assertFalse(me.satchel().bottomless());
    }

    @Test
    void theWrongCodeDoesNothing() {
        WatchGame game = solo();
        WatchPlayer me = game.join(1, "Kara");
        assertFalse(game.debug(1, "0000"));
        assertFalse(game.debug(1, ""));
        assertFalse(game.debug(1, null));
        assertFalse(me.debugging(), "the wrong code let somebody in");
    }

    /**
     * The claim the design is for: not a list of grants, a lens over the
     * registries.
     *
     * <p>So this walks {@link Recipes#all()} and {@code BuildPiece.all()} rather
     * than naming anything — every recipe there is, and every piece, is
     * affordable and actually makeable. A recipe added tomorrow is in this test
     * tomorrow, without the test being edited, which is the same property the
     * feature has.
     */
    @Test
    void everythingInTheGameIsFreeIncludingWhatIsAddedNext() {
        WatchGame game = solo();
        WatchPlayer me = game.join(1, "Kara");
        game.debug(1, Debug.CODE);
        Satchel bag = me.satchel();

        assertFalse(Recipes.all().isEmpty());
        for (Recipes.Recipe recipe : Recipes.all()) {
            assertTrue(recipe.affordable(bag),
                    recipe.output() + " cannot be afforded with everything unlimited");
            assertTrue(game.craft(1, recipe, recipe.station()),
                    recipe.output() + " would not be made");
        }

        assertFalse(BuildPiece.all().isEmpty());
        for (BuildPiece piece : BuildPiece.all()) {
            assertTrue(piece.affordable(bag),
                    piece + " cannot be afforded with everything unlimited");
            assertTrue(piece.pay(bag), piece + " would not take payment");
        }

        // Every item there is, however many of it you like — including one that
        // no registry has heard of, which is what keeps a cost added later from
        // being the one thing the mode cannot pay.
        for (Forage.Item item : Forage.all()) {
            assertTrue(bag.has(item.key(), 999), item.key() + " ran out");
        }
        assertTrue(bag.has("something_invented_next_year", 5),
                "a cost in something not yet registered would not be free");
    }

    /** The tools the rest of the game gates on are simply there. */
    @Test
    void theToolsAreInTheBagAndTheVerbsThatNeedThemWork() {
        WatchGame game = solo();
        WatchPlayer me = game.join(1, "Kara");
        game.debug(1, Debug.CODE);

        assertTrue(me.satchel().has("rod"));
        assertTrue(me.satchel().has("trowel"));
        assertTrue(me.satchel().has("feeder"));
        // The spyglass, which was added after this mode was designed and needed
        // no line of it: it is an item, so it is unlimited.
        assertEquals(8, game.glass(1, 8), 1e-9,
                "the host refused a spyglass a debug satchel is carrying");

        // A feeder, put out with prepared food, from an empty start.
        assertNotNull(game.placeLure(1, "suet_cake"),
                "a feeder could not be put out with everything unlimited");
        assertNotNull(game.placeLure(1, "suet_cake"),
                "the second feeder cost something the first one used up");
    }

    /** …and the satchel screen has something to list. */
    @Test
    void aBottomlessSatchelListsTheWholeCatalogue() {
        Satchel bag = new Satchel();
        assertTrue(bag.keys().isEmpty());
        bag.setBottomless(true);

        assertEquals(Forage.all().size(), bag.keys().size(),
                "a debug satchel does not list every item there is");
        assertEquals("∞", bag.countLabel("blackberry"), "the screen would print 9999");
        assertFalse(bag.ofKind(Forage.Kind.PREPARED).isEmpty(),
                "'put out the first food you have' has nothing to find");
        assertFalse(bag.ofKind(Forage.Kind.SEED).isEmpty(),
                "'plant the first seed you have' has nothing to find");
    }

    /**
     * A lens, not a gift: what is really in the bag survives being seen through
     * it, so turning the mode off leaves the walk exactly as it was.
     */
    @Test
    void theModeIsReversible() {
        WatchGame game = solo();
        WatchPlayer me = game.join(1, "Kara");
        Satchel bag = me.satchel();
        Map<String, Integer> before = bag.contents();
        assertFalse(before.isEmpty(), "a joiner starts with nothing, so this proves nothing");

        game.debug(1, Debug.CODE);
        for (Recipes.Recipe recipe : Recipes.at(Recipes.Station.HANDS)) {
            game.craft(1, recipe, Recipes.Station.HANDS);
        }
        game.debug(1, Debug.CODE);

        for (Map.Entry<String, Integer> had : before.entrySet()) {
            assertTrue(bag.count(had.getKey()) >= had.getValue(),
                    had.getKey() + " was spent while it was supposed to be free");
        }
        assertFalse(bag.has("quartz"),
                "debug mode left real quartz in the bag after it was switched off");
    }

    // --- whose walk it is --------------------------------------------------------------

    @Test
    void aGuestOnSomebodyElsesWalkIsRefused() {
        WatchGame party = new WatchGame(WatchGame.Config.hosted("Shared", 5L));
        WatchPlayer host = party.join(1, "Kara");
        WatchPlayer guest = party.join(2, "Sam");

        assertFalse(party.debug(2, Debug.CODE),
                "a guest turned on unlimited items in somebody else's shared guide");
        assertFalse(guest.debugging());
        assertFalse(guest.satchel().bottomless());

        assertTrue(party.debug(1, Debug.CODE), "the host could not use their own code");
        assertTrue(host.debugging());
        assertFalse(guest.debugging(), "the host's code turned it on for the guest too");
    }

    // --- it reaches the screen -----------------------------------------------------------

    @Test
    void theFlagRidesTheSnapshotSoTheScreenAgrees() {
        WatchGame game = solo();
        WatchPlayer me = game.join(1, "Kara");
        game.debug(1, Debug.CODE);

        WatchView view = new WatchView();
        view.snapshot(game, 1);
        assertNotNull(view.self());
        assertTrue(view.self().debug(), "the snapshot does not say the player is in debug");
        assertTrue(view.satchel().bottomless(),
                "the screen's own satchel is not bottomless, so every recipe would "
                        + "be greyed out while the host allowed it");

        // …and the wire path, which is a different set of lines entirely.
        WatchView wired = new WatchView();
        wired.setSelfId(1);
        wired.loadWalkers(List.of(me.toSnapshot()));
        assertTrue(wired.satchel().bottomless(),
                "a client's satchel does not pick the flag up off the party snapshot");

        game.debug(1, Debug.CODE);
        wired.loadWalkers(List.of(me.toSnapshot()));
        assertFalse(wired.satchel().bottomless(), "switching it off does not reach a client");
    }

    @Test
    void aDebugWalkIsStillADebugWalkWhenItIsReopened(@TempDir Path dir) {
        WatchStore store = new WatchStore(dir.toString());
        WatchGame game = new WatchGame(new WatchGame.Config(8L, "Kept", 1));
        game.join(1, "Kara");
        game.debug(1, Debug.CODE);
        store.save(game);

        WatchGame reopened = new WatchGame(new WatchGame.Config(8L, "Kept", 1));
        assertTrue(store.load(reopened), "the walk would not reopen");
        WatchPlayer woken = reopened.join(1, "Kara");
        assertNotNull(woken, "the saved walker did not come back");
        assertTrue(woken.debugging(), "debug mode was lost across a save");
        assertTrue(woken.satchel().bottomless(),
                "the flag came back and the satchel's lens did not");
    }

    /** Typing the code in the walk itself turns it on, through the real scene. */
    @Test
    void typingTheCodeInTheWalkTurnsItOn(@TempDir Path dir) {
        GameContext ctx = new GameContext(null, new GameTypeStore(dir.toString()));
        WatchStore store = new WatchStore(dir.resolve("walks").toString());
        WatchScene walk = new WatchScene(ctx);
        SceneManager scenes = new SceneManager();
        scenes.setViewport(800, 480);
        scenes.register(WatchLobbyScene.NAME, new WatchLobbyScene(ctx, store));
        scenes.register(WatchScene.NAME, walk);

        WatchGame game = new WatchGame(new WatchGame.Config(77L, "Typed", 1));
        WatchPlayer me = game.join(1, "Kara");
        WatchSession session = WatchSession.solo(game);
        session.setSelfId(1);
        walk.adopt(session, store);
        scenes.setScene(WatchScene.NAME);

        InputManager input = new InputManager();
        for (int i = 0; i < 3; i++) {
            input.newFrame();
            scenes.update(1 / 60.0, input);
        }
        RecordingTarget plain = new RecordingTarget(800, 480);
        scenes.render(plain, 0f);
        assertFalse(me.debugging());

        typeCode(scenes, input);
        assertTrue(me.debugging(), "typing the code in the walk did nothing");

        // The readout is drawn, which is how a player knows.
        input.newFrame();
        scenes.update(1 / 60.0, input);
        RecordingTarget debugged = new RecordingTarget(800, 480);
        scenes.render(debugged, 0f);
        assertTrue(debugged.commands().size() > plain.commands().size(),
                "debug mode drew no readout: " + debugged.commands().size()
                        + " calls against " + plain.commands().size());

        // And typing it again puts it away.
        typeCode(scenes, input);
        assertFalse(me.debugging(), "the code would not switch it off from the walk");
        session.close();
    }

    /**
     * K summons a mutant in the walk, and only once the code has been typed.
     *
     * <p>Through the real scene because that is where the whole of this power
     * lives on the client: K is a <em>raw</em> key rather than a
     * {@link com.larsons.engine.input.GameAction}, deliberately — see
     * {@code WatchScene.summonMutant} — so nothing about it appears in the
     * bindings, and a test that called {@code WatchGame.summon} directly would
     * be testing everything except the part that could be wrong.
     */
    @Test
    void kSummonsTheThreeMutantsInTurnAndOnlyInDebugMode(@TempDir Path dir) {
        GameContext ctx = new GameContext(null, new GameTypeStore(dir.toString()));
        WatchStore store = new WatchStore(dir.resolve("walks").toString());
        WatchScene walk = new WatchScene(ctx);
        SceneManager scenes = new SceneManager();
        scenes.setViewport(800, 480);
        scenes.register(WatchLobbyScene.NAME, new WatchLobbyScene(ctx, store));
        scenes.register(WatchScene.NAME, walk);

        WatchGame game = new WatchGame(new WatchGame.Config(1234L, "Summoned", 1));
        WatchPlayer me = game.join(1, "Kara");
        WatchSession session = WatchSession.solo(game);
        session.setSelfId(1);
        walk.adopt(session, store);
        scenes.setScene(WatchScene.NAME);

        InputManager input = new InputManager();
        for (int i = 0; i < 3; i++) {
            input.newFrame();
            scenes.update(1 / 60.0, input);
        }
        // Before the code, K is an unbound key like any other.
        pressKey(scenes, input, KeyEvent.VK_K);
        assertEquals(0, hostileCount(game),
                "K summoned something without the code being typed");

        typeCode(scenes, input);
        assertTrue(me.debugging());

        // Three presses, three creatures, and each of the three is a different
        // one — the cycle is what makes one key enough for all of them.
        Set<String> summoned = new java.util.TreeSet<>();
        for (int i = 0; i < 3; i++) {
            pressKey(scenes, input, KeyEvent.VK_K);
            for (Animal animal : game.animals()) {
                if (animal.hostile()) summoned.add(animal.def().key());
            }
        }
        assertEquals(3, summoned.size(),
                "three presses of K produced " + summoned.size() + " different mutants: "
                        + summoned);
        assertEquals(3, hostileCount(game), "the summons did not all survive");
        session.close();
    }

    private static int hostileCount(WatchGame game) {
        int n = 0;
        for (Animal animal : game.animals()) {
            if (animal.hostile()) n++;
        }
        return n;
    }

    /** One key, pressed and released, with a frame in between. See {@link #typeCode}. */
    private static void pressKey(SceneManager scenes, InputManager input, int key) {
        input.keyPressed(new KeyEvent(new JPanel(), KeyEvent.KEY_PRESSED, 0, 0, key,
                KeyEvent.CHAR_UNDEFINED));
        input.newFrame();
        scenes.update(1 / 60.0, input);
        input.keyReleased(new KeyEvent(new JPanel(), KeyEvent.KEY_RELEASED, 0, 0, key,
                KeyEvent.CHAR_UNDEFINED));
    }

    /**
     * Type the code into a running scene, one key at a time.
     *
     * <p>Pressed, promoted, read, released — in that order, and the release
     * matters: {@code InputManager} records only the rising edge of a key, so
     * a code with two of the same digit next to it needs the key to come back
     * up in between. Which is what a keyboard does, and what this has to
     * imitate to be testing the real thing.
     */
    private static void typeCode(SceneManager scenes, InputManager input) {
        for (char c : Debug.CODE.toCharArray()) {
            int key = KeyEvent.VK_0 + (c - '0');
            input.keyPressed(new KeyEvent(new JPanel(), KeyEvent.KEY_PRESSED, 0, 0, key, c));
            input.newFrame();
            scenes.update(1 / 60.0, input);
            input.keyReleased(new KeyEvent(new JPanel(), KeyEvent.KEY_RELEASED, 0, 0, key, c));
        }
    }

    /** Walking about without typing it never turns it on by accident. */
    @Test
    void theCodeIsNotTypedByAccident() {
        Debug.Pad pad = new Debug.Pad();
        java.util.Random rng = new java.util.Random(99);
        int landings = 0;
        for (int i = 0; i < 5000; i++) {
            // Digits at random, with the pad forgetting between bursts the way
            // it would while somebody is playing.
            if (rng.nextInt(9) == 0) pad.tick(Debug.FORGET_SECONDS);
            if (pad.type(rng.nextInt(10))) landings++;
        }
        assertNotEquals(0, Debug.CODE.length());
        assertTrue(landings <= 5000 / Math.pow(10, Debug.CODE.length() - 1),
                "the code lands far too often on random digits: " + landings);
    }
}
