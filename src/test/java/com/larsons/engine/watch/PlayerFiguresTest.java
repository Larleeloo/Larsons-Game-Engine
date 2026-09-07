package com.larsons.engine.watch;

import com.larsons.engine.config.GameContext;
import com.larsons.engine.config.GameTypeStore;
import com.larsons.engine.input.InputManager;
import com.larsons.engine.scene.SceneManager;
import com.larsons.engine.demo.WatchLobbyScene;
import com.larsons.engine.demo.WatchScene;
import com.larsons.engine.ui.ConfigForm;
import com.larsons.engine.watch.life.AnimState;
import com.larsons.engine.watch.model.ModelRig;
import com.larsons.engine.watch.model.SceneModel;
import com.larsons.engine.watch.model.SceneModels;
import com.larsons.engine.watch.net.WatchClient;
import com.larsons.engine.watch.net.WatchServer;
import com.larsons.engine.watch.net.WatchSession;
import com.larsons.engine.watch.render.CosmeticModel;
import com.larsons.engine.watch.render.Mesh;
import com.larsons.engine.watch.render.WalkerModel;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.JPanel;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two people you can walk as, and the two wardrobes cut to them.
 *
 * <h2>What this file is guarding</h2>
 *
 * <p>A worn cosmetic is the one model in this game that is <b>never measured
 * and never rescaled</b>: the metre an artist put a hat at is the metre it is
 * worn at. That is the right call — it is what lets a cape hang off the
 * shoulders <em>and</em> reach the knee — and it has one consequence that
 * nothing else in the repository would notice going wrong: a wardrobe is cut
 * to a body, so thirty-six files are only correct <em>relative to two other
 * files</em>, and all thirty-eight of them are binaries that do not diff.
 *
 * <p>So the assertions here are mostly about fit. They draw the real figure
 * and the real garment out of {@code src/main/resources} and check that the
 * hat is at head height on the head it was cut for; that the two wardrobes are
 * genuinely two rather than a copy; that a piece stays inside the budget six
 * of them on eight people in one clearing implies. A number moved in
 * {@code tools/blender/figures.py} and re-exported for one figure and not the
 * other fails this rather than reaching a screenshot.
 *
 * <p>The rest is the choice itself: that it survives a save, rides everybody's
 * snapshot row, is offered before the walk starts and in the middle of one,
 * and costs nothing at all.
 *
 * <p><b>This is one of the few test classes that deliberately wants the
 * classpath.</b> Its subject is the art that ships, so it asks the loader for
 * exactly what a player would get — see {@link SceneModels.Sources}, and see
 * {@code CosmeticsTest}, which wants the opposite for the opposite reason.
 */
@Timeout(180)
class PlayerFiguresTest {

    /** How tall both figures stand, and what every window below is read against. */
    private static final double HEIGHT = WalkerModel.HEIGHT;

    private WatchServer server;
    private final List<WatchClient> clients = new ArrayList<>();

    @BeforeEach
    void theShippedArt() {
        // Explicit rather than assumed. Every other class that touches this
        // switch narrows it and puts it back, and one that forgot would leave
        // this file quietly measuring nothing at all.
        SceneModels.setDirectory(Path.of(SceneModels.DIRECTORY));
        SceneModels.setSources(SceneModels.Sources.FOLDER_AND_CLASSPATH);
    }

    @AfterEach
    void tearDown() {
        for (WatchClient client : clients) client.close();
        clients.clear();
        if (server != null) server.stop();
    }

    // --- the catalogue ------------------------------------------------------------------

    /** Two of them, each with a key, a name, a line and a file of its own. */
    @Test
    void everyFigureIsAWholeAnswer() {
        List<Figure> all = Figure.all();
        assertTrue(all.size() >= 2, "one figure is not a choice");
        Set<String> keys = new HashSet<>();
        Set<String> labels = new HashSet<>();
        for (Figure figure : all) {
            assertTrue(keys.add(figure.key()), "two figures answer to " + figure.key());
            assertTrue(labels.add(figure.label()), "two figures called " + figure.label());
            assertFalse(figure.note().isBlank(), figure.key() + " has nothing said about it");
            assertEquals(figure, Figure.byKey(figure.key()));
            assertEquals(figure, Figure.of(figure.key()));
            assertEquals("characters/" + figure.key(), figure.model());
            // The label, so a ConfigForm row reads as a person rather than as
            // an enum constant.
            assertEquals(figure.label(), figure.toString());
        }
        assertTrue(all.contains(Figure.DEFAULT), "the default is not one of them");
        assertNotEquals(Figure.DEFAULT, Figure.DEFAULT.next(),
                "next() must actually go somewhere");
        // Round, so a screen can offer this as one key rather than as a list.
        Figure at = Figure.DEFAULT;
        for (int i = 0; i < all.size(); i++) at = at.next();
        assertEquals(Figure.DEFAULT, at, "cycling the figures does not come back round");
    }

    /**
     * A key this build has never heard of is a walker, not a crash.
     *
     * <p>The one guarantee worth having about somebody joining from a version
     * with a third figure in it: they are still <em>drawn</em>. Everything else
     * about them arrives correctly and their body is the one this game drew
     * before there was a choice.
     */
    @Test
    void anUnknownFigureIsTheOneThisGameAlwaysDrew() {
        assertNull(Figure.byKey("mothman"));
        assertNull(Figure.byKey(null));
        assertEquals(Figure.DEFAULT, Figure.of("mothman"));
        assertEquals(Figure.DEFAULT, Figure.of(null));
        assertEquals(Figure.DEFAULT, Figure.of(""));
    }

    // --- the models that ship -----------------------------------------------------------

    /**
     * Both figures are modelled, both stand the same height, and both carry
     * every clip a walker is ever drawn in.
     *
     * <p><b>Five clips is not a nicety.</b> A state with no clip is posed by
     * {@code ModelRig}'s procedural table, which works per piece about each
     * bone's own pivot rather than down the hierarchy — invisible at an idle's
     * 0.03 radians and, at a run's 0.67, a hand left hovering at the wrist its
     * arm has swung away from. A walker is drawn in exactly these five states,
     * so five clips means the fallback never runs on either figure.
     */
    @Test
    void bothFiguresAreModelledAndCarryEveryClipAWalkerIsDrawnIn() {
        for (Figure figure : Figure.all()) {
            SceneModel model = SceneModels.of(figure.model(), ModelRig.Kind.HUMANOID,
                    SceneModel.Size.height(1));
            assertNotNull(model, figure.key() + " has no model in watch/models");
            assertTrue(WalkerModel.imported(figure),
                    figure.key() + " is not what the renderer would draw");
            for (AnimState state : List.of(AnimState.IDLE, AnimState.WALK, AnimState.RUN,
                    AnimState.SWIM, AnimState.ROW)) {
                assertTrue(model.animates(state),
                        figure.key() + " has no " + state.name().toLowerCase() + " clip");
            }

            Mesh standing = standing(figure);
            assertEquals(0, standing.minZ(), 0.02,
                    figure.key() + " does not stand on the ground");
            assertEquals(HEIGHT, standing.maxZ(), 0.02,
                    figure.key() + " is not " + HEIGHT + " m to the crown");
            // §14's ceiling for a character. Both figures pay the same, which
            // is what stops one of them being the one you pick to be seen from
            // further away.
            assertTrue(standing.triangleCount() <= 1200,
                    figure.key() + " is " + standing.triangleCount() + " triangles, over "
                            + "the 1200 a character is budgeted");
            assertTrue(standing.triangleCount() >= 400,
                    figure.key() + " is " + standing.triangleCount() + " triangles, which "
                            + "is a placeholder rather than a person");
        }
    }

    /**
     * The second figure is a different person, not a smaller one.
     *
     * <p><b>The failure this catches is the usual way a second figure goes
     * wrong.</b> Scaled down, the walker would be a child: same proportions,
     * less of them. So the two are held apart where a build differs — the
     * shoulders come in while the hips stay — and held together where a
     * <em>person</em> does not, which is the height the whole wardrobe is
     * hung from.
     */
    @Test
    void theSecondFigureIsADifferentPersonAndNotASmallerOne() {
        Mesh walker = standing(Figure.WALKER);
        Mesh wayfarer = standing(Figure.WAYFARER);

        assertEquals(walker.maxZ(), wayfarer.maxZ(), 0.02,
                "the two figures are different heights");
        // Across the shoulders, measured where the shoulders are rather than
        // over the whole figure — the widest thing on either of them is a hat.
        double walkerShoulder = widthAt(walker, 1.10, 1.25);
        double wayfarerShoulder = widthAt(wayfarer, 1.10, 1.25);
        assertTrue(wayfarerShoulder < walkerShoulder - 0.03,
                "the wayfarer's shoulders are " + wayfarerShoulder + " m against the "
                        + "walker's " + walkerShoulder + " — that is the same build");
        // …and not simply smaller: the hips stay where they are.
        double walkerHip = widthAt(walker, 0.50, 0.70);
        double wayfarerHip = widthAt(wayfarer, 0.50, 0.70);
        assertTrue(wayfarerHip > walkerHip - 0.05,
                "the wayfarer was scaled down rather than cut differently — hips "
                        + wayfarerHip + " against " + walkerHip);
    }

    // --- the wardrobes ------------------------------------------------------------------

    /**
     * Every piece in the catalogue is modelled for every figure.
     *
     * <p>The cheap failure this catches: somebody adds a row to
     * {@code Cosmetics.build}, runs {@code tools/blender/cosmetics.py} for one
     * figure and forgets the other, and half the party is in boxes.
     */
    @Test
    void everyPieceIsModelledForEveryFigure() {
        for (Figure figure : Figure.all()) {
            for (Cosmetics.Piece piece : Cosmetics.all()) {
                assertNotNull(CosmeticModel.importedFor(figure, piece.key()),
                        figure.key() + " has no modelled " + piece.key());
            }
            assertEquals(List.of(), CosmeticModel.boxesOnly(figure, everything()),
                    figure.key() + " is still wearing boxes somewhere");
        }
    }

    /**
     * Two wardrobes, and they are two rather than one copied.
     *
     * <p>If this fails the whole exercise was pointless: a piece that is
     * byte-identical between the figures is a piece that was cut to one of them
     * and posted to the other, which is precisely the thing
     * {@link CosmeticModel#importedFor} exists to avoid.
     */
    @Test
    void aWardrobeIsCutToItsOwnFigure() {
        int different = 0;
        for (Cosmetics.Piece piece : Cosmetics.all()) {
            Mesh one = worn(Figure.WALKER, piece.key());
            Mesh two = worn(Figure.WAYFARER, piece.key());
            if (!bounds(one).equals(bounds(two))) different++;
        }
        // Not all eighteen: a lanyard hangs off a neck and two necks are two
        // necks, but a pair of spectacles could legitimately come out the same
        // if the two faces ever agreed. Most of them is the assertion.
        assertTrue(different >= Cosmetics.all().size() - 2,
                only(different) + " of " + Cosmetics.all().size() + " pieces differ "
                        + "between the figures — the second wardrobe is a copy");
    }

    /**
     * <b>The one that matters.</b> Everything is worn where its slot says it is.
     *
     * <p>A cosmetic is {@code AS_PLACED}: never measured, never moved. So the
     * only thing standing between a modelled hat and a hat floating a foot over
     * somebody's head is that the artist typed the right number, and this is
     * where that is checked — against the figure the game actually draws, for
     * every piece and every figure, in the metres both are written in.
     *
     * <p>The windows are wide on purpose. This is not a test of taste; it is a
     * test that a wardrobe was cut to the figure it is filed under, which is
     * the mistake that actually happens: re-exported against the wrong body's
     * table, every piece lands 100–400 mm out at once. The one place a window
     * alone would not have caught it is the head — §16's reference figure is
     * 1.95 m to the top of its hat and these two are 1.78, so a hat cut to the
     * old table sits <em>above</em> the head rather than on it and any band
     * generous enough for a hood would let it through. That is what the extra
     * assertion about reaching down onto the crown is for.
     */
    @Test
    void everyPieceIsWornWhereItsSlotSaysItIs() {
        // Bottom and top of the band a slot's own geometry has to sit in, in
        // metres up a standing figure. Read off the figures themselves: the
        // head is at 1.45-1.47 and the hat over it reaches 1.78, a hand hangs
        // at 0.59-0.60, a boot cuff tops out at 0.25.
        Map<Cosmetics.Slot, double[]> window = Map.of(
                Cosmetics.Slot.HEAD, new double[]{1.45, 2.02},
                Cosmetics.Slot.FACE, new double[]{1.24, 1.66},
                Cosmetics.Slot.NECK, new double[]{0.92, 1.44},
                Cosmetics.Slot.BACK, new double[]{0.34, 1.34},
                Cosmetics.Slot.HANDS, new double[]{0.46, 0.74},
                Cosmetics.Slot.FEET, new double[]{0.00, 0.50});

        for (Figure figure : Figure.all()) {
            double crown = standing(figure).maxZ();
            for (Cosmetics.Piece piece : Cosmetics.all()) {
                Mesh mesh = worn(figure, piece.key());
                assertFalse(mesh.isEmpty(),
                        figure.key() + "/" + piece.key() + " drew nothing");
                String where = figure.key() + "/" + piece.key();
                double[] band = window.get(piece.slot());
                double middle = (mesh.minZ() + mesh.maxZ()) / 2;
                assertTrue(middle >= band[0] && middle <= band[1],
                        where + " sits at " + round(middle) + " m, which is not "
                                + "anywhere a " + piece.slot().label().toLowerCase()
                                + " piece belongs (" + band[0] + "-" + band[1] + ")");
                // Nothing under the floor and nothing over the hat.
                assertTrue(mesh.minZ() >= -0.03,
                        where + " reaches " + round(mesh.minZ()) + " m, under the ground");
                assertTrue(mesh.maxZ() <= crown + 0.30,
                        where + " reaches " + round(mesh.maxZ()) + " m, well over a head");
                // …and nothing wider than the widest thing on a walker, which
                // is a hat brim at 0.64.
                assertTrue(mesh.maxX() - mesh.minX() <= 0.90,
                        where + " is " + round(mesh.maxX() - mesh.minX()) + " m across");

                // **Worn on the wearer, not hovering over them.** A head piece
                // goes over a hat that is already there, so it has to come down
                // onto the crown; a back piece hangs off the shoulders, so its
                // top is at the yoke and not somewhere above the ears.
                if (piece.slot() == Cosmetics.Slot.HEAD) {
                    assertTrue(mesh.minZ() <= crown - 0.08,
                            where + " starts at " + round(mesh.minZ()) + " m on a figure "
                                    + round(crown) + " m tall — it is floating over the "
                                    + "head rather than being worn on it");
                }
                if (piece.slot() == Cosmetics.Slot.BACK) {
                    assertTrue(mesh.maxZ() <= crown - 0.42,
                            where + " reaches " + round(mesh.maxZ()) + " m, which is over "
                                    + "the shoulders it is supposed to hang from");
                }
            }
        }
    }

    /**
     * The pieces worn on a pair of limbs are worn on both of them.
     *
     * <p>Which is the other half of "is it in the right place": a mitten built
     * once and mirrored badly is a mitten on one hand and nothing on the other,
     * and the vertical window above cannot see that at all.
     */
    @Test
    void mittensAndBootsComeInPairs() {
        for (Figure figure : Figure.all()) {
            for (Cosmetics.Piece piece : Cosmetics.all()) {
                if (piece.slot() != Cosmetics.Slot.HANDS
                        && piece.slot() != Cosmetics.Slot.FEET) {
                    continue;
                }
                Mesh mesh = worn(figure, piece.key());
                String where = figure.key() + "/" + piece.key();
                assertTrue(mesh.minX() < -0.04, where + " has nothing on the -x side");
                assertTrue(mesh.maxX() > 0.04, where + " has nothing on the +x side");
                // …and a gap between them, or it is one slab across the middle.
                assertEquals(0, (mesh.minX() + mesh.maxX()) / 2, 0.04,
                        where + " is not centred on the figure");
            }
        }
    }

    /** What is worn on the back is worn on the back. */
    @Test
    void aCapeHangsBehindTheWalkerAndNotThroughThem() {
        for (Figure figure : Figure.all()) {
            for (Cosmetics.Piece piece : Cosmetics.inSlot(Cosmetics.Slot.BACK)) {
                Mesh mesh = worn(figure, piece.key());
                // Forward is -y, so a back piece has to reach well past the
                // middle the other way. The pack it goes over stands at +0.30.
                assertTrue(mesh.maxY() > 0.24,
                        figure.key() + "/" + piece.key() + " reaches only "
                                + round(mesh.maxY()) + " m behind the middle, which is "
                                + "in front of the pack it is worn over");
            }
        }
    }

    /**
     * A whole outfit stays inside what eight people in a clearing can afford.
     *
     * <p>Six pieces on one person and eight people in one wood is the number
     * this budget is written against: a 900-triangle cape is 43,000 triangles
     * of coat in a clearing, on a software rasteriser.
     */
    @Test
    void awardrobeStaysInsideItsBudget() {
        for (Figure figure : Figure.all()) {
            for (Cosmetics.Piece piece : Cosmetics.all()) {
                int count = worn(figure, piece.key()).triangleCount();
                assertTrue(count <= 250,
                        figure.key() + "/" + piece.key() + " is " + count
                                + " triangles, over the 250 a piece is budgeted");
                // …and more than the boxes it replaced, which is the whole
                // reason anybody modelled it.
                assertTrue(count >= 60,
                        figure.key() + "/" + piece.key() + " is " + count
                                + " triangles, which is no better than its boxes");
            }
            int outfit = 0;
            for (Cosmetics.Slot slot : Cosmetics.Slot.values()) {
                int dearest = 0;
                for (Cosmetics.Piece piece : Cosmetics.inSlot(slot)) {
                    dearest = Math.max(dearest, worn(figure, piece.key()).triangleCount());
                }
                outfit += dearest;
            }
            assertTrue(outfit <= 1400,
                    figure.key() + "'s dearest outfit is " + outfit + " triangles");
        }
    }

    /**
     * A modelled piece really does replace its boxes on a modelled figure.
     *
     * <p>The two halves of the mechanism, held together: what
     * {@link CosmeticModel#overlay} draws is exactly what
     * {@link CosmeticModel#boxesOnly} took off the joints, so a dressed walker
     * is the figure plus the garment and never the figure plus both.
     */
    @Test
    void aDressedFigureIsTheFigurePlusTheGarmentAndNotBoth() {
        for (Figure figure : Figure.all()) {
            Mesh bare = standing(figure);
            Mesh dressed = dressed(figure, everything());
            assertTrue(dressed.triangleCount() > bare.triangleCount(),
                    figure.key() + " wearing everything drew no more than wearing nothing");
            int garments = 0;
            for (String key : everything()) garments += worn(figure, key).triangleCount();
            assertEquals(bare.triangleCount() + garments, dressed.triangleCount(),
                    figure.key() + " drew a garment twice, or drew boxes under a "
                            + "modelled piece");
        }
    }

    // --- the choice ---------------------------------------------------------------------

    /** Changing figure is free, instant, and touches nothing else about a walker. */
    @Test
    void changingFigureCostsNothingAndChangesNothingElse() {
        WatchGame game = new WatchGame(WatchGame.Config.hosted("Figures", 4242L));
        assertNotNull(game.join(1, "Kara"));
        WatchPlayer player = game.player(1);
        assertEquals(Figure.DEFAULT, player.figure(), "a fresh walker is not the default");

        Map<String, Object> before = player.toSnapshot();
        int points = game.guide().points();
        Figure other = Figure.DEFAULT.next();
        assertTrue(game.setFigure(1, other.key()), "the host would not change the figure");
        assertEquals(other, player.figure());
        assertEquals(points, game.guide().points(), "being somebody else cost points");

        Map<String, Object> after = player.toSnapshot();
        for (String key : before.keySet()) {
            assertEquals(before.get(key), after.get(key),
                    "changing figure changed \"" + key + "\" on the player");
        }
        Set<String> added = new HashSet<>(after.keySet());
        added.removeAll(before.keySet());
        assertEquals(Set.of("fg"), added, "a figure put " + added + " on a player row");

        // Nothing else answers, and nothing unknown is accepted.
        assertFalse(game.setFigure(1, other.key()), "setting the same figure said it changed");
        assertFalse(game.setFigure(1, "mothman"), "an unknown figure was accepted");
        assertEquals(other, player.figure());
        assertFalse(game.setFigure(99, Figure.DEFAULT.key()), "a player who is not there");
    }

    /**
     * Only the walkers who are somebody else cost anything on the wire.
     *
     * <p>The same bargain {@link Outfit#wornLine} makes: a party of walkers is
     * exactly the snapshot it always was, and the field appears only for
     * somebody who chose.
     */
    @Test
    void aPartyOfWalkersCostsNothingExtraOnTheWire() {
        WatchGame game = new WatchGame(WatchGame.Config.hosted("Figures", 91L));
        game.join(1, "Kara");
        assertFalse(game.player(1).toSnapshot().containsKey("fg"),
                "the default figure went out on the wire");
        game.setFigure(1, Figure.WAYFARER.key());
        assertEquals(Figure.WAYFARER.key(), game.player(1).toSnapshot().get("fg"));
    }

    /** A walk reopened is a walk in the same body. */
    @Test
    void aFigureSurvivesASave(@TempDir Path dir) {
        WatchGame game = new WatchGame(WatchGame.Config.hosted("Figures", 77L));
        game.join(1, "Kara");
        game.setFigure(1, Figure.WAYFARER.key());

        WatchStore store = new WatchStore(dir.resolve("walks").toString());
        store.save(game);
        WatchGame reopened = new WatchGame(WatchGame.Config.hosted("Figures", 77L));
        assertTrue(store.load(reopened), "the walk did not save");
        reopened.join(1, "Kara");
        assertEquals(Figure.WAYFARER, reopened.player(1).figure(),
                "a walk reopened put somebody else's body on");

        // …and a save from before there were two of them is a walker.
        WatchGame old = new WatchGame(WatchGame.Config.hosted("Figures", 77L));
        old.join(1, "Kara");
        old.player(1).load(new HashMap<>());
        assertEquals(Figure.DEFAULT, old.player(1).figure());
    }

    /**
     * The whole thing over a socket: somebody changes who they are and the
     * friend across the valley sees it.
     *
     * <p>A host verb, for {@link WatchGame#wear}'s reason and not a weaker one:
     * which body somebody is goes out on their row to everybody, so a client
     * that decided for itself would eventually disagree with what the rest of
     * the valley could see.
     */
    @Test
    void changingFigureReachesEverybodyElsesScreen() throws IOException {
        server = new WatchServer(WatchGame.Config.hosted("Figures", 8181L));
        server.start(0);
        WatchClient me = connect("Kara");
        WatchClient friend = connect("Sam");
        until("both to be welcomed", () -> me.ready() && friend.ready());
        until("the host to seat them", () -> server.playerCount() == 2);

        int id = me.view().selfId();
        assertEquals(Figure.DEFAULT, figureOf(friend, id));

        me.sendFigure(Figure.WAYFARER.key());
        until("the friend to see the new figure",
                () -> figureOf(friend, id) == Figure.WAYFARER);
        until("and this client to see it on its own row",
                () -> figureOf(me, id) == Figure.WAYFARER);

        // A key the host does not know leaves them exactly as they were.
        me.sendFigure("mothman");
        me.sendFigure(Figure.WAYFARER.key());
        until("the party to settle", () -> figureOf(friend, id) == Figure.WAYFARER);
        assertEquals(Figure.WAYFARER,
                server.game().player(id).figure(), "an unknown key reached the host's copy");
    }

    // --- the two screens that offer it ----------------------------------------------------

    /**
     * The pause screen changes who you are, and does it through the host.
     *
     * <p>Left or right, because the pause screen is a panel and panels in this
     * scene read left and right — see {@code WatchScene.updatePaused} for why
     * that is better than a binding nineteen screens out of twenty would
     * ignore.
     */
    @Test
    void thePauseScreenChangesWhoYouAre(@TempDir Path dir) {
        try (Walk walk = new Walk(dir)) {
            assertEquals(Figure.DEFAULT, walk.game.player(1).figure());
            walk.press(KeyEvent.VK_ESCAPE);
            assertEquals("paused", walk.walk.panelName(), "Esc did not pause the walk");

            walk.press(KeyEvent.VK_RIGHT);
            assertEquals(Figure.DEFAULT.next(), walk.game.player(1).figure(),
                    "the pause screen did not change the figure");
            walk.press(KeyEvent.VK_LEFT);
            assertEquals(Figure.DEFAULT, walk.game.player(1).figure(),
                    "it would not go back round");
            // Still paused: this is a setting on the screen, not a way off it.
            assertEquals("paused", walk.walk.panelName());
        }
    }

    /**
     * The lobby offers the choice before the walk starts, on every form that
     * starts one.
     *
     * <p>Deciding who you are after setting off is the wrong order, and a game
     * that only let you change it from a pause menu would be telling you the
     * question was an afterthought.
     */
    @Test
    void theLobbyOffersTheChoiceBeforeYouSetOff(@TempDir Path dir) {
        GameContext ctx = new GameContext(null, new GameTypeStore(dir.toString()));
        WatchStore store = new WatchStore(dir.resolve("walks").toString());
        SceneManager scenes = new SceneManager();
        scenes.setViewport(800, 480);
        WatchLobbyScene lobby = new WatchLobbyScene(ctx, store);
        scenes.register(WatchLobbyScene.NAME, lobby);
        scenes.setScene(WatchLobbyScene.NAME);

        for (String entry : List.of("New Walk", "Host a Walk", "Join a Walk")) {
            lobby.onEnter();
            assertTrue(open(lobby, entry), "the lobby has no \"" + entry + "\"");
            ConfigForm form = lobby.form();
            assertNotNull(form, entry + " opened no form");
            assertTrue(labels(form).contains("Walk as"),
                    entry + " does not ask which figure to walk as — it offers "
                            + labels(form));
        }
    }

    // --- the drift the art cannot notice --------------------------------------------------

    /**
     * The wardrobe is painted out of the catalogue.
     *
     * <p><b>A test on two files that have no other reason to agree.</b>
     * {@code Cosmetics.java} says a wool scarf is {@code A8482F} with a
     * {@code D9C68A} band and the shop draws its row from that; the Blender
     * script that cuts the geometry has its own copy, because Blender cannot
     * read Java. A number that moves in one is a coat whose picture in the shop
     * is a different colour from the coat on the buyer, and nothing else in
     * this repository would ever say so.
     */
    @Test
    void theWardrobeIsPaintedOutOfTheCatalogue() throws IOException {
        String script = Files.readString(Path.of("tools/blender/cosmetics.py"));
        Map<String, int[]> paint = new HashMap<>();
        Matcher m = Pattern.compile(
                "\"([a-z_]+)\":\\s*\\((0x[0-9A-Fa-f]{6}|OILSKIN),\\s*0x([0-9A-Fa-f]{6})\\)")
                .matcher(script);
        while (m.find()) {
            paint.put(m.group(1), new int[]{
                    m.group(2).equals("OILSKIN") ? 0 : Integer.parseInt(m.group(2).substring(2), 16),
                    Integer.parseInt(m.group(3), 16)});
        }
        assertEquals(Cosmetics.all().size(), paint.size(),
                "the Blender script paints " + paint.size() + " pieces and the catalogue "
                        + "has " + Cosmetics.all().size());
        for (Cosmetics.Piece piece : Cosmetics.all()) {
            int[] said = paint.get(piece.key());
            assertNotNull(said, "the Blender script has no colours for " + piece.key());
            assertEquals(piece.trim(), said[1], piece.key() + "'s trim");
            if (piece.tinted()) {
                // The sentinel: the boxes draw these two in the wearer's own
                // coat and a modelled piece is never tinted, so the script
                // gives them a colour of their own and says so by name.
                assertEquals(0, said[0], piece.key() + " is tinted and the script "
                        + "should paint it OILSKIN rather than a literal");
            } else {
                assertEquals(piece.rgb(), said[0], piece.key() + "'s main colour");
            }
        }
    }

    /**
     * The walker's row in the measurement table is the script that built him.
     *
     * <p>{@code figures.py} carries a copy of the walker's landmarks so that
     * {@code cosmetics.py} can cut clothes to them, and {@code ranger.py} — the
     * script that actually built {@code characters/walker.glb} — deliberately
     * stays self-contained, because it is the figure this game has always drawn
     * and a refactor that changed one rounding in it would change what every
     * player looks like. A copy needs a test or it is a lie waiting to happen.
     */
    @Test
    void theWalkerTableAgreesWithTheScriptThatBuiltIt() throws IOException {
        Map<String, Double> said = pythonConstants(
                Files.readString(Path.of("tools/blender/ranger.py")));
        Map<String, Double> table = pythonTable(
                Files.readString(Path.of("tools/blender/figures.py")), "WALKER");
        assertFalse(table.isEmpty(), "figures.py has no WALKER table in it");

        for (Map.Entry<String, String> row : Map.of(
                "sole", "SOLE", "ankle_z", "ANKLE_Z", "knee_z", "KNEE_Z",
                "hip_z", "HIP_Z", "waist_z", "WAIST_Z", "shoulder_z", "SHOULDER_Z",
                "neck_z", "NECK_Z", "head_z", "HEAD_Z", "crown_z", "CROWN_Z",
                "hip_x", "HIP_X").entrySet()) {
            assertEquals(said.get(row.getValue()), table.get(row.getKey()), 1e-9,
                    "figures.py's WALKER." + row.getKey() + " is not ranger.py's "
                            + row.getValue());
        }
        assertEquals(said.get("SHOULDER_X"), table.get("shoulder_x"), 1e-9, "shoulder_x");
        assertEquals(said.get("ELBOW_Z"), table.get("elbow_z"), 1e-9, "elbow_z");
        assertEquals(said.get("BRIM_Z"), table.get("hat_brim_z"), 1e-9, "hat_brim_z");
        // …and the height both figures have to be, or a wardrobe authored in
        // metres arrives at some other number of them. See figures.py.
        assertEquals(HEIGHT, table.get("crown_z"), 1e-9,
                "the walker is not WalkerModel.HEIGHT to the crown");
        Map<String, Double> other = pythonTable(
                Files.readString(Path.of("tools/blender/figures.py")), "WAYFARER");
        assertEquals(HEIGHT, other.get("crown_z"), 1e-9,
                "the wayfarer is not WalkerModel.HEIGHT to the crown");
    }

    // --- the plumbing -------------------------------------------------------------------

    /** One figure, standing still, wearing nothing. */
    private static Mesh standing(Figure figure) {
        return dressed(figure, WalkerModel.WEARING_NOTHING);
    }

    private static Mesh dressed(Figure figure, List<String> worn) {
        Mesh.Builder mesh = Mesh.builder(0, 0, 0, false, 1);
        WalkerModel.walker(mesh, figure, 0, 0, 0, 0, false, 0, 0,
                WalkerModel.Leap.GROUNDED, 0x4A6B33, worn, 0);
        return mesh.build();
    }

    /** One garment, on its own, at the feet of a standing figure. */
    private static Mesh worn(Figure figure, String key) {
        Mesh.Builder mesh = Mesh.builder(0, 0, 0, false, 1);
        CosmeticModel.overlay(mesh, figure, List.of(key), 0, 0, 0, 0, HEIGHT, 0, 0,
                new float[]{0, 0, 1, 1});
        return mesh.build();
    }

    /** One of everything, which is at most one piece a slot. */
    private static List<String> everything() {
        List<String> out = new ArrayList<>();
        for (Cosmetics.Slot slot : Cosmetics.Slot.values()) {
            out.add(Cosmetics.inSlot(slot).get(0).key());
        }
        return out;
    }

    /** How wide a figure is between two heights, in metres. */
    private static double widthAt(Mesh mesh, double low, double high) {
        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
        float[] v = mesh.vertices();
        for (int i = 0; i < mesh.vertexCount(); i++) {
            double x = v[i * Mesh.FLOATS_PER_VERTEX];
            double z = v[i * Mesh.FLOATS_PER_VERTEX + 2];
            if (z < low || z > high) continue;
            min = Math.min(min, x);
            max = Math.max(max, x);
        }
        assertTrue(max > min, "nothing at all between " + low + " and " + high);
        return max - min;
    }

    private static String bounds(Mesh mesh) {
        return round(mesh.minX()) + "," + round(mesh.minY()) + "," + round(mesh.minZ())
                + ";" + round(mesh.maxX()) + "," + round(mesh.maxY()) + ","
                + round(mesh.maxZ()) + ";" + mesh.triangleCount();
    }

    private static double round(double v) { return Math.round(v * 1000) / 1000.0; }

    private static String only(int n) { return n == 0 ? "none" : Integer.toString(n); }

    /** What one client thinks another player's body is. */
    private static Figure figureOf(WatchClient client, int id) {
        for (WatchView.Walker walker : client.view().walkers()) {
            if (walker.id() == id) return walker.figure();
        }
        return null;
    }

    /** Every {@code NAME = number} at the top level of a Python file. */
    private static Map<String, Double> pythonConstants(String text) {
        Map<String, Double> out = new HashMap<>();
        Matcher m = Pattern.compile(
                "(?m)^([A-Z][A-Z0-9_]*) = (-?\\d+(?:\\.\\d+)?)\\s*(?:#.*)?$").matcher(text);
        while (m.find()) out.put(m.group(1), Double.parseDouble(m.group(2)));
        return out;
    }

    /** Every {@code "key": number} inside one top-level {@code NAME = { … }}. */
    private static Map<String, Double> pythonTable(String text, String name) {
        Matcher block = Pattern.compile("(?ms)^" + name + " = \\{(.*?)^\\}").matcher(text);
        Map<String, Double> out = new HashMap<>();
        if (!block.find()) return out;
        Matcher row = Pattern.compile("\"([a-z_]+)\":\\s*(-?\\d+(?:\\.\\d+)?)")
                .matcher(block.group(1));
        while (row.find()) out.put(row.group(1), Double.parseDouble(row.group(2)));
        return out;
    }

    /** Click a menu entry whose label starts with this, and say whether it was there. */
    private static boolean open(WatchLobbyScene lobby, String entry) {
        for (var item : lobby.menu().items()) {
            if (item.text().startsWith(entry)) {
                item.activate();
                return true;
            }
        }
        return false;
    }

    private static Set<String> labels(ConfigForm form) {
        Set<String> out = new HashSet<>();
        for (ConfigForm.Option option : form.options()) out.add(option.label());
        return out;
    }

    private WatchClient connect(String name) throws IOException {
        WatchClient client = WatchClient.connect("127.0.0.1", server.port(), name);
        clients.add(client);
        return client;
    }

    private void until(String what, java.util.function.BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 15_000;
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

    /** A running solo walk, so the pause screen can be pressed for real. */
    private static final class Walk implements AutoCloseable {

        private final SceneManager scenes = new SceneManager();
        private final InputManager input = new InputManager();
        private final WatchSession session;
        private final WatchGame game;
        private final WatchScene walk;

        Walk(Path dir) {
            GameContext ctx = new GameContext(null, new GameTypeStore(dir.toString()));
            WatchStore store = new WatchStore(dir.resolve("walks").toString());
            walk = new WatchScene(ctx);
            scenes.setViewport(800, 480);
            scenes.register(WatchScene.NAME, walk);
            game = new WatchGame(new WatchGame.Config(31L, "Figures", 1));
            game.join(1, "Kara");
            session = WatchSession.solo(game);
            session.setSelfId(1);
            walk.adopt(session, store);
            scenes.setScene(WatchScene.NAME);
            for (int i = 0; i < 6; i++) step();
        }

        void step() {
            input.newFrame();
            scenes.update(1 / 120.0, input);
        }

        void press(int key) {
            input.keyPressed(new KeyEvent(new JPanel(), KeyEvent.KEY_PRESSED, 0, 0, key,
                    KeyEvent.CHAR_UNDEFINED));
            step();
            input.keyReleased(new KeyEvent(new JPanel(), KeyEvent.KEY_RELEASED, 0, 0, key,
                    KeyEvent.CHAR_UNDEFINED));
            step();
        }

        @Override public void close() {
            walk.adopt(null, null);
            session.close();
        }
    }
}
