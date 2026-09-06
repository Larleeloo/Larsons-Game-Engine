package com.larsons.engine.watch;

import com.larsons.engine.config.GameContext;
import com.larsons.engine.config.GameTypeStore;
import com.larsons.engine.demo.WatchLobbyScene;
import com.larsons.engine.demo.WatchScene;
import com.larsons.engine.graphics.draw.RecordingTarget;
import com.larsons.engine.input.InputManager;
import com.larsons.engine.scene.SceneManager;
import com.larsons.engine.watch.life.AnimalDef;
import com.larsons.engine.watch.life.AnimalRegistry;
import com.larsons.engine.watch.net.WatchClient;
import com.larsons.engine.watch.net.WatchServer;
import com.larsons.engine.watch.net.WatchSession;
import com.larsons.engine.watch.render.CosmeticModel;
import com.larsons.engine.watch.render.ItemPortrait;
import com.larsons.engine.watch.render.Mesh;
import com.larsons.engine.watch.render.WalkerModel;
import com.larsons.engine.watch.world.TerrainField;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.JPanel;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Clothes off a trading post's rail, and the figure they go on.
 *
 * <p>Four claims, and the first is the one the whole feature has to keep or it
 * has no business existing.
 *
 * <ol>
 *   <li><b>They are optional.</b> A walker wearing nothing is the walker this
 *       game has always drawn, vertex for vertex; a walker wearing something is
 *       that walker with boxes added and none taken away; and nothing anybody
 *       wears touches a single number the walk reads.</li>
 *   <li><b>They are bought.</b> Off a keeper's own rail, at the counter, out of
 *       the shared book's balance — and refused to somebody in the next valley,
 *       somebody short of the price, and somebody who already owns the coat,
 *       without taking anything.</li>
 *   <li><b>They are kept, and they are seen.</b> A piece goes in its owner's
 *       wardrobe rather than in a bag, survives a save, and reaches everybody
 *       else's screen as what is <em>worn</em> — while what is merely owned
 *       stays private.</li>
 *   <li><b>One piece to a slot.</b> Which is the only rule in the model and
 *       the reason the renderer never has to decide between two hats.</li>
 * </ol>
 */
@Timeout(180)
class CosmeticsTest {

    private static final long SEED = 20260828L;

    private WatchServer server;
    private final List<WatchClient> clients = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (WatchClient client : clients) client.close();
        clients.clear();
        if (server != null) server.stop();
    }

    // --- the catalogue -------------------------------------------------------------------

    /** Something in every slot, at prices somebody could read as a ladder. */
    @Test
    void thereIsSomethingToWearOnEveryPartOfAWalker() {
        List<Cosmetics.Piece> all = Cosmetics.all();
        assertTrue(all.size() >= 12, "a wardrobe of " + all.size() + " is a token gesture");

        Set<String> keys = new HashSet<>();
        Set<String> names = new HashSet<>();
        for (Cosmetics.Piece piece : all) {
            assertTrue(keys.add(piece.key()), "two pieces answer to " + piece.key());
            assertTrue(names.add(piece.name()), "two pieces called " + piece.name());
            assertNotNull(piece.slot(), piece.key() + " goes nowhere");
            assertTrue(piece.price() > 0, piece.key() + " is free");
            assertFalse(piece.note().isBlank(), piece.key() + " has nothing said about it");
            assertEquals(piece, Cosmetics.byKey(piece.key()));
            assertEquals(piece.slot(), Cosmetics.slotOf(piece.key()));
            assertTrue(Cosmetics.isWorn(piece.key()));
        }
        for (Cosmetics.Slot slot : Cosmetics.Slot.values()) {
            assertFalse(Cosmetics.inSlot(slot).isEmpty(),
                    "nothing at all to put on your " + slot.label().toLowerCase());
        }
        // Read against Rarity.points(): a common bird is 1 and a legendary 100,
        // so the cheap end has to be an afternoon and the dear end a thing to
        // work toward. A flat catalogue is a shopping list, not a want.
        int cheapest = Integer.MAX_VALUE, dearest = 0;
        for (Cosmetics.Piece piece : all) {
            cheapest = Math.min(cheapest, piece.price());
            dearest = Math.max(dearest, piece.price());
        }
        assertTrue(cheapest <= 20, "the cheapest thing to wear costs " + cheapest);
        assertTrue(dearest >= 150, "nothing in the wardrobe is worth walking for");
    }

    /** Nothing in the catalogue shares a key with anything in a satchel. */
    @Test
    void aPieceIsNeverAlsoAnItem() {
        for (Cosmetics.Piece piece : Cosmetics.all()) {
            assertNull(Forage.byKey(piece.key()),
                    piece.key() + " is both a thing to wear and a thing to carry, so a "
                            + "satchel and a wardrobe would fight over it");
            assertFalse(Trading.sold(piece.key()),
                    piece.key() + " is on a shelf as well as on a rail");
        }
    }

    /**
     * Every piece in the catalogue actually draws something.
     *
     * <p>The one failure this whole file exists to catch cheaply: somebody adds
     * a row to {@code Cosmetics.build} and not a case to
     * {@code CosmeticModel.draw}, and the shop sells a coat that is invisible on
     * the buyer and blank in its own picture.
     */
    @Test
    void everyPieceInTheCatalogueDraws() {
        for (Cosmetics.Piece piece : Cosmetics.all()) {
            Mesh.Builder builder = Mesh.builder(0, 0, 0, false, 1);
            CosmeticModel.alone(builder, piece.key(), 0, 0, 0, 0.4,
                    CosmeticModel.portraitSize(piece.slot()), 0x4A6B33);
            Mesh mesh = builder.build();
            assertFalse(mesh.isEmpty(), piece.key() + " draws nothing at all");
            // More than one box, because a single box is what a forgotten piece
            // that fell through to some generic case would look like.
            assertTrue(mesh.triangleCount() >= 24,
                    piece.key() + " is " + mesh.triangleCount() + " triangles, which is "
                            + "one box — every piece has a band, a buckle or a lining");
            double size = CosmeticModel.portraitSize(piece.slot());
            // …and it is roughly the size of the body part it hangs on rather
            // than of the world: a hat measured in metres is a hat that comes
            // off the head the first time anybody scales a figure.
            assertTrue(mesh.maxX() - mesh.minX() < size * 24
                            && mesh.maxZ() - mesh.minZ() < size * 24,
                    piece.key() + " is " + (mesh.maxZ() - mesh.minZ()) + " m tall");
        }
    }

    /** And has a picture, through the same door every item's row goes through. */
    @Test
    void everyPieceHasItsOwnPortrait() {
        for (Cosmetics.Piece piece : Cosmetics.all()) {
            assertNotNull(ItemPortrait.of(piece.key(), 24, 0x101410),
                    piece.key() + " has no picture for its row");
        }
    }

    // --- over the top of the character ------------------------------------------------------

    /**
     * A walker wearing nothing is the walker this game has always drawn.
     *
     * <p>Vertex for vertex and colour for colour, which is the strongest form
     * of "optional" there is: with the wardrobe empty this feature is not a
     * cheaper version of the old figure, it <em>is</em> the old figure.
     */
    @Test
    void anUndressedWalkerIsTheWalkerItAlwaysWas() {
        Mesh before = walker(WalkerModel.WEARING_NOTHING);
        Mesh after = walker(List.of());
        assertEquals(before.vertexCount(), after.vertexCount());
        assertArrayEquals(before.vertices(), after.vertices(),
                "an empty outfit moved a vertex of the figure underneath");
        assertArrayEquals(before.colours(), after.colours(),
                "an empty outfit repainted the figure underneath");
    }

    /**
     * Everything worn is added; nothing underneath is taken away.
     *
     * <p>Checked by looking for every one of the bare figure's vertices in the
     * dressed one, rather than by comparing counts: the clothes are emitted
     * beside the body part they hang on, so a dressed mesh is the bare one
     * <em>interleaved</em> and a prefix comparison would fail on a hat. What
     * matters is that nothing went missing — a hat that swapped the head out or
     * a gaiter that moved a boot fails here whatever the totals say.
     */
    @Test
    void wearingSomethingAddsToTheFigureAndReplacesNoneOfIt() {
        Mesh bare = walker(WalkerModel.WEARING_NOTHING);
        for (Cosmetics.Piece piece : Cosmetics.all()) {
            Mesh dressed = walker(List.of(piece.key()));
            assertTrue(dressed.vertexCount() > bare.vertexCount(),
                    "wearing the " + piece.name() + " drew nothing on the figure");
            assertTrue(containsAllTriangles(dressed, bare),
                    "wearing the " + piece.name() + " took part of the walker away");
        }
    }

    /** A whole outfit is all six at once, and still only adds. */
    @Test
    void aWalkerCanWearOneOfEverythingAtOnce() {
        Outfit outfit = new Outfit();
        List<String> want = new ArrayList<>();
        for (Cosmetics.Slot slot : Cosmetics.Slot.values()) {
            String key = Cosmetics.inSlot(slot).get(0).key();
            outfit.acquire(key);
            assertTrue(outfit.wear(key));
            want.add(key);
        }
        assertEquals(want, outfit.wornKeys(), "a full outfit is one piece per slot");
        Mesh bare = walker(WalkerModel.WEARING_NOTHING);
        Mesh dressed = walker(outfit.wornKeys());
        assertTrue(containsAllTriangles(dressed, bare),
                "a fully dressed walker is missing part of the figure underneath");
        assertTrue(dressed.triangleCount() > bare.triangleCount() + 6 * 8,
                "six pieces added " + (dressed.triangleCount() - bare.triangleCount())
                        + " triangles between them");
    }

    /**
     * A hat is on a swimmer's head and a rower's, and it is on the right way
     * round.
     *
     * <p>Everything about a swimmer's pose is written along a spine that may be
     * pointing anywhere, and the bug this guards is a specific one the hat brim
     * already had once: a piece stacked along the world's vertical instead of
     * along the body floats off the side of a prone swimmer's head and follows
     * them across the lake.
     */
    @Test
    void aHatStaysOnAHeadWhicheverWayTheBodyIsPointing() {
        List<String> hat = List.of("straw_boater");
        for (double pitch : new double[]{Math.PI / 2, 0.5, 0, -0.6}) {
            Mesh bare = swimmer(pitch, WalkerModel.WEARING_NOTHING);
            Mesh worn = swimmer(pitch, hat);
            assertTrue(worn.triangleCount() > bare.triangleCount(),
                    "a swimmer at pitch " + pitch + " is not wearing their hat");
            // The whole added mesh has to sit within a head's reach of where the
            // bare mesh already goes. A brim laid out along the wrong axis puts
            // it a third of a metre outside that.
            double reach = 0.45;
            assertTrue(worn.minX() > bare.minX() - reach && worn.maxX() < bare.maxX() + reach
                            && worn.minY() > bare.minY() - reach
                            && worn.maxY() < bare.maxY() + reach
                            && worn.minZ() > bare.minZ() - reach
                            && worn.maxZ() < bare.maxZ() + reach,
                    "at pitch " + pitch + " the boater is off the side of the head");
        }
        Mesh bareRower = rower(WalkerModel.WEARING_NOTHING);
        assertTrue(rower(hat).triangleCount() > bareRower.triangleCount(),
                "a rower left their hat on the bank");
    }

    /** The two big pieces are drawn in the wearer's own colour, so a party still reads. */
    @Test
    void theOnlyPiecesThatHideAWalkerAreDrawnInTheirOwnColour() {
        List<String> tinted = new ArrayList<>();
        for (Cosmetics.Piece piece : Cosmetics.all()) {
            if (piece.tinted()) tinted.add(piece.key());
        }
        assertFalse(tinted.isEmpty(), "nothing takes the wearer's colour");
        for (String key : tinted) {
            Mesh one = walker(List.of(key), 0x4A6B33);
            Mesh two = walker(List.of(key), 0x7A4630);
            assertEquals(one.vertexCount(), two.vertexCount());
            assertArrayEquals(one.vertices(), two.vertices(),
                    "two coats moved the " + key);
            assertNotEquals(addedColours(key, 0x4A6B33), addedColours(key, 0x7A4630),
                    key + " is the same colour on everybody, so a party in them is "
                            + "six people nobody can tell apart");
        }
        // …and everything else is its own colour whoever is wearing it, which is
        // what makes a shop row's picture true.
        for (Cosmetics.Piece piece : Cosmetics.all()) {
            if (piece.tinted()) continue;
            assertEquals(addedColours(piece.key(), 0x4A6B33),
                    addedColours(piece.key(), 0x7A4630),
                    piece.key() + " changes colour with the coat under it");
        }
    }

    // --- one piece to a slot ------------------------------------------------------------------

    @Test
    void puttingOnASecondHatTakesTheFirstOneOff() {
        List<Cosmetics.Piece> hats = Cosmetics.inSlot(Cosmetics.Slot.HEAD);
        assertTrue(hats.size() >= 2, "one hat is not enough to test this with");
        Outfit outfit = new Outfit();
        outfit.acquire(hats.get(0).key());
        outfit.acquire(hats.get(1).key());
        outfit.wear(hats.get(0).key());
        outfit.wear(hats.get(1).key());

        assertEquals(List.of(hats.get(1).key()), outfit.wornKeys());
        assertEquals(hats.get(1).key(), outfit.wornIn(Cosmetics.Slot.HEAD));
        assertTrue(outfit.wearing(hats.get(1).key()));
        assertFalse(outfit.wearing(hats.get(0).key()));
        // Both are still owned: swapping a hat is not losing one.
        assertTrue(outfit.owns(hats.get(0).key()));
        assertEquals(2, outfit.pieces());

        assertTrue(outfit.takeOff(Cosmetics.Slot.HEAD));
        assertNull(outfit.wornIn(Cosmetics.Slot.HEAD));
        assertFalse(outfit.takeOff(Cosmetics.Slot.HEAD), "a bare head came off twice");
        assertEquals(2, outfit.pieces(), "taking a hat off lost both of them");
    }

    @Test
    void nobodyWearsWhatTheyDoNotOwn() {
        Outfit outfit = new Outfit();
        String key = Cosmetics.all().get(0).key();
        assertFalse(outfit.wear(key), "an unowned piece went on anyway");
        assertNull(outfit.toggle(key));
        assertTrue(outfit.bare());

        assertTrue(outfit.acquire(key));
        assertFalse(outfit.acquire(key), "the same piece was bought twice");
        assertNotNull(outfit.toggle(key));
        assertTrue(outfit.wearing(key));
        assertNotNull(outfit.toggle(key));
        assertFalse(outfit.wearing(key), "the second click did not take it off");
        assertTrue(outfit.owns(key), "taking a coat off lost it");
    }

    @Test
    void anOutfitSurvivesASaveAndOnlyWhatIsWornGoesOnTheWire() {
        Outfit outfit = new Outfit();
        outfit.acquire("wool_scarf");
        outfit.acquire("straw_boater");
        outfit.acquire("river_waders");
        outfit.wear("wool_scarf");
        outfit.wear("straw_boater");

        Outfit reopened = new Outfit();
        reopened.load(outfit.toMap());
        assertEquals(outfit.wardrobe(), reopened.wardrobe(), "the wardrobe was not saved");
        assertEquals(outfit.wornKeys(), reopened.wornKeys(), "the outfit was not saved");

        // The wire carries what is on and nothing else — the waders are owned
        // and off, and nobody else has any business knowing about them.
        Outfit seen = new Outfit();
        seen.loadWorn(outfit.wornLine());
        assertEquals(outfit.wornKeys(), seen.wornKeys());
        assertEquals(0, seen.pieces(), "somebody else's wardrobe travelled");
        assertFalse(outfit.wornLine().contains("river_waders"));

        // And junk off a save or a stale client is dropped rather than kept.
        Outfit stale = new Outfit();
        stale.loadWorn("wool_scarf,a_hat_that_was_deleted");
        assertEquals(List.of("wool_scarf"), stale.wornKeys());
    }

    // --- what a post has hanging up ------------------------------------------------------------

    @Test
    void everyPostHasARailAndNoTwoPostsHaveTheSameOne() {
        TerrainField field = new TerrainField(SEED);
        List<Shops.Shop> found = new Shops(SEED).near(field, 0, 0, 2000);
        assertFalse(found.isEmpty(), "no trading post to look at");

        Set<List<String>> rails = new HashSet<>();
        for (Shops.Shop shop : found) {
            List<String> keys = new ArrayList<>();
            for (Cosmetics.Piece piece : shop.rail()) {
                assertNotNull(Cosmetics.byKey(piece.key()),
                        shop.sign() + " is selling something that does not exist");
                assertFalse(keys.contains(piece.key()),
                        shop.sign() + " has two of the same coat on one rail");
                keys.add(piece.key());
                // At the keeper's own prices, like everything else they carry.
                Cosmetics.Piece listed = Cosmetics.byKey(piece.key());
                assertEquals(Math.max(1, (int) Math.round(listed.price() * shop.markup())),
                        piece.price(), piece.key() + " is not at " + shop.sign()
                                + "'s prices");
                assertEquals(listed.slot(), piece.slot());
                assertEquals(listed.name(), piece.name());
            }
            assertFalse(keys.isEmpty(), shop.sign() + " has an empty rail");
            assertTrue(keys.size() <= 6,
                    shop.sign() + " carries " + keys.size() + " — a post that sold the "
                            + "catalogue would make the next one pointless");
            rails.add(keys);
        }
        assertTrue(rails.size() > 1,
                "every post in two kilometres has the identical rail, so walking to "
                        + "another one buys nothing new");
    }

    @Test
    void twoPlayersOnOneSeedSeeTheSameRails() {
        TerrainField field = new TerrainField(SEED);
        Shops mine = new Shops(SEED);
        Shops theirs = new Shops(SEED);
        List<Shops.Shop> here = mine.near(field, 0, 0, 1400);
        assertFalse(here.isEmpty());
        for (Shops.Shop shop : here) {
            Shops.Shop same = theirs.nearest(field, shop.x(), shop.y(), 20);
            assertNotNull(same, "the same seed lost a post");
            assertEquals(shop.rail(), same.rail(),
                    "two clients on one seed disagree about what " + shop.sign()
                            + " has hanging up");
        }
    }

    /** A rail is drawn from the whole catalogue over enough posts, not from a corner of it. */
    @Test
    void theWholeCatalogueTurnsUpSomewhere() {
        Set<String> seen = new HashSet<>();
        Random rng = new Random(SEED);
        for (int i = 0; i < 400; i++) {
            for (Cosmetics.Piece piece : Cosmetics.rail(null, 1.0, rng)) {
                seen.add(piece.key());
            }
        }
        for (Cosmetics.Piece piece : Cosmetics.all()) {
            assertTrue(seen.contains(piece.key()),
                    piece.key() + " is in the catalogue and on nobody's rail");
        }
    }

    // --- buying it ------------------------------------------------------------------------------

    @Test
    void pointsBuyAHatAtTheCounterAndItGoesStraightOn() {
        WatchGame game = atACounter(SEED, 1, "Kara");
        Shops.Shop shop = game.shopAt(1);
        Cosmetics.Piece piece = shop.rail().get(0);
        earn(game, 1, piece.price() + 10);

        int before = game.guide().points();
        assertNotNull(game.buyWorn(1, shop.id(), piece.key()), "the purchase did nothing");

        Outfit outfit = game.player(1).outfit();
        assertTrue(outfit.owns(piece.key()), "the goods never arrived");
        assertTrue(outfit.wearing(piece.key()),
                "somebody who just spent points on a coat at a counter wants it on");
        assertEquals(before - piece.price(), game.guide().points(),
                "the points did not change hands");
        // Not in the satchel, which is the whole of why this is its own verb.
        assertEquals(0, game.player(1).satchel().count(piece.key()));
    }

    @Test
    void aSecondPurchaseOfTheSameCoatIsRefusedAndCostsNothing() {
        WatchGame game = atACounter(SEED, 1, "Kara");
        Shops.Shop shop = game.shopAt(1);
        Cosmetics.Piece piece = shop.rail().get(0);
        earn(game, 1, piece.price() * 3 + 20);
        game.buyWorn(1, shop.id(), piece.key());

        int before = game.guide().points();
        String line = game.buyWorn(1, shop.id(), piece.key());
        assertNotNull(line, "a refusal should still say something");
        assertTrue(line.toLowerCase().contains("already"), line);
        assertEquals(before, game.guide().points(), "a second hat was paid for");
        assertEquals(1, game.player(1).outfit().pieces());
    }

    @Test
    void aCoatNobodyCanAffordCostsNothing() {
        WatchGame game = atACounter(SEED, 1, "Kara");
        Shops.Shop shop = game.shopAt(1);
        Cosmetics.Piece piece = dearest(shop);
        earn(game, 1, Math.max(1, piece.price() - 1));
        while (game.guide().points() >= piece.price()) game.guide().spend(1);

        int before = game.guide().points();
        String line = game.buyWorn(1, shop.id(), piece.key());
        assertNotNull(line);
        assertTrue(line.toLowerCase().contains("not enough"), line);
        assertEquals(before, game.guide().points(), "a refused purchase took the points");
        assertFalse(game.player(1).outfit().owns(piece.key()),
                "a refused purchase handed the coat over anyway");
    }

    @Test
    void nobodyBuysACoatFromTheNextValleyOrOffSomebodyElsesRail() {
        WatchGame game = atACounter(SEED, 1, "Kara");
        Shops.Shop shop = game.shopAt(1);
        earn(game, 1, 900);
        Cosmetics.Piece piece = shop.rail().get(0);

        // Something the catalogue has and this keeper does not.
        String elsewhere = null;
        for (Cosmetics.Piece other : Cosmetics.all()) {
            if (shop.worn(other.key()) == null) elsewhere = other.key();
        }
        assertNotNull(elsewhere, "this post carries the whole catalogue");
        assertNull(game.buyWorn(1, shop.id(), elsewhere),
                "a keeper sold something off somebody else's rail");
        assertNull(game.buyWorn(1, shop.id() + 1, piece.key()),
                "a request naming a different post was honoured");

        int before = game.guide().points();
        stand(game, 1, shop.x() + 400, shop.y() + 400);
        assertNull(game.buyWorn(1, shop.id(), piece.key()),
                "somebody four hundred metres away bought a coat");
        assertEquals(before, game.guide().points());
        assertEquals(0, game.player(1).outfit().pieces());
    }

    @Test
    void debugModeDressesForFreeAndStillOnlyFromThisRail() {
        WatchGame game = atACounter(SEED, 1, "Kara");
        game.player(1).setDebug(true);
        Shops.Shop shop = game.shopAt(1);
        assertEquals(0, game.guide().points(), "the test world started with a balance");

        for (Cosmetics.Piece piece : shop.rail()) {
            assertNotNull(game.buyWorn(1, shop.id(), piece.key()),
                    "debug mode could not afford the " + piece.name());
        }
        assertEquals(shop.rail().size(), game.player(1).outfit().pieces());
        assertEquals(0, game.guide().points(), "debug mode spent points it did not have");
    }

    /**
     * Putting something on is free and needs no counter.
     *
     * <p>A rule about the verb rather than about the screen: today the shop's
     * clothes rail is the only place a player can work a wardrobe from, and
     * this is the assertion that says a second screen anywhere else would need
     * no host change to work. See {@link WatchGame#wear}.
     */
    @Test
    void dressingCostsNothingAndHappensAnywhere() {
        WatchGame game = atACounter(SEED, 1, "Kara");
        Shops.Shop shop = game.shopAt(1);
        Cosmetics.Piece piece = shop.rail().get(0);
        earn(game, 1, piece.price() + 10);
        game.buyWorn(1, shop.id(), piece.key());

        stand(game, 1, shop.x() + 2000, shop.y() + 2000);
        assertNull(game.shopAt(1), "the walker is still at a counter");
        int before = game.guide().points();
        assertNotNull(game.wear(1, piece.key()), "a hat would not come off in the wood");
        assertFalse(game.player(1).outfit().wearing(piece.key()));
        assertNotNull(game.wear(1, piece.key()));
        assertTrue(game.player(1).outfit().wearing(piece.key()), "and would not go on again");
        assertEquals(before, game.guide().points(), "getting dressed cost points");

        // And a piece nobody owns still cannot be put on, wherever they stand.
        String unowned = null;
        for (Cosmetics.Piece other : Cosmetics.all()) {
            if (!other.key().equals(piece.key())) unowned = other.key();
        }
        assertNull(game.wear(1, unowned), "a client dressed itself in something it never bought");
    }

    /** Nothing about being dressed changes a single thing the walk reads. */
    @Test
    void clothesAreCosmeticAndTouchNothingElse() {
        WatchGame game = atACounter(SEED, 1, "Kara");
        WatchPlayer player = game.player(1);
        Map<String, Object> before = player.toSnapshot();

        Shops.Shop shop = game.shopAt(1);
        earn(game, 1, 4000);
        for (Cosmetics.Piece piece : shop.rail()) game.buyWorn(1, shop.id(), piece.key());
        assertFalse(player.outfit().bare(), "nothing went on");

        Map<String, Object> after = player.toSnapshot();
        for (String key : before.keySet()) {
            assertEquals(before.get(key), after.get(key),
                    "getting dressed changed \"" + key + "\" on the player");
        }
        // The one field a full outfit is allowed to add.
        Set<String> added = new HashSet<>(after.keySet());
        added.removeAll(before.keySet());
        assertEquals(Set.of("w"), added,
                "an outfit put " + added + " on a player row");
        assertEquals(1, player.health(), 1e-9);
    }

    // --- keeping it -----------------------------------------------------------------------------

    @Test
    void theWardrobeAndTheOutfitSurviveASave(@TempDir Path dir) {
        WatchGame game = atACounter(SEED, 1, "Kara");
        Shops.Shop shop = game.shopAt(1);
        earn(game, 1, 4000);
        List<String> bought = new ArrayList<>();
        for (Cosmetics.Piece piece : shop.rail()) {
            game.buyWorn(1, shop.id(), piece.key());
            bought.add(piece.key());
        }
        // Take one off, so the save has to remember both lists rather than one.
        game.wear(1, bought.get(0));
        List<String> worn = game.player(1).outfit().wornKeys();

        WatchStore store = new WatchStore(dir.resolve("walks").toString());
        store.save(game);
        WatchGame reopened = new WatchGame(WatchGame.Config.hosted("Trading", SEED));
        assertTrue(store.load(reopened), "the walk did not save");
        reopened.join(1, "Kara");

        Outfit outfit = reopened.player(1).outfit();
        assertEquals(bought.size(), outfit.pieces(), "the wardrobe did not come back");
        for (String key : bought) assertTrue(outfit.owns(key), key + " was lost");
        assertEquals(worn, outfit.wornKeys(), "a walk reopened put a different coat on");
    }

    // --- online -----------------------------------------------------------------------------------

    /**
     * The whole thing over a socket: a client buys a coat, and the friend on the
     * far side of the valley sees it on them.
     *
     * <p>What does <em>not</em> travel is half the point. Nothing names the
     * rail — the client generated it from the seed, like the shelf — and nothing
     * tells the friend what is in the buyer's wardrobe. See {@code WatchProto}.
     */
    @Test
    void aClientBuysACoatAndEverybodySeesItOnThem() throws IOException {
        server = new WatchServer(WatchGame.Config.hosted("Trading", SEED));
        server.start(0);
        WatchClient buyer = connect("Kara");
        WatchClient friend = connect("Sam");
        until("both to be welcomed", () -> buyer.ready() && friend.ready());
        until("the host to seat them", () -> server.playerCount() == 2);

        WatchGame game = server.game();
        Shops.Shop shop = game.shops().nearest(game.field(), 0, 0, 4000);
        assertNotNull(shop);
        earn(game, buyer.view().selfId(), 900);
        buyer.sendMove(shop.counterX(), shop.counterY(),
                game.field().heightAt(shop.counterX(), shop.counterY()), 0, 0, false);
        until("the host to put the buyer at the counter",
                () -> game.shopAt(buyer.view().selfId()) != null);

        Cosmetics.Piece piece = shop.rail().get(0);
        int before = game.guide().points();
        buyer.sendBuyWorn(shop.id(), piece.key());

        until("the coat to arrive in the buyer's own wardrobe",
                () -> buyer.view().outfit().owns(piece.key()));
        until("the book to show the cost",
                () -> buyer.view().guide().points() == before - piece.price());
        int me = buyer.view().selfId();
        until("the friend to see it on them",
                () -> wornBy(friend, me).contains(piece.key()));
        // …and to know no more than that.
        assertEquals(0, friend.view().outfit().pieces(),
                "somebody else's wardrobe arrived on the friend's screen");

        buyer.sendWear(piece.key());
        until("taking it off to reach the friend",
                () -> !wornBy(friend, me).contains(piece.key()));
        assertTrue(buyer.view().outfit().owns(piece.key()),
                "taking a coat off over the wire lost it");
        assertEquals(before - piece.price(), game.guide().points(),
                "getting undressed cost points");
    }

    // --- through the screen a player actually uses ---------------------------------------------

    /**
     * Standing at a counter, in the real scene: the rail is a heading away, a
     * click buys the coat, and a second click takes it off again.
     */
    @Test
    void theShopScreenSellsClothesAndPutsThemOn(@TempDir Path dir) {
        try (Walk walk = Walk.atACounter(dir)) {
            walk.press(KeyEvent.VK_E);
            assertEquals("shop", walk.walk.panelName());
            Shops.Shop shop = walk.game.shopAt(1);
            assertNotNull(shop);
            assertEquals(shop.stock().get(0).item(), walk.walk.panelCursor(),
                    "the shop did not open on the shelf");

            walk.press(KeyEvent.VK_LEFT);
            Cosmetics.Piece piece = shop.rail().get(0);
            assertEquals(piece.key(), walk.walk.panelCursor(),
                    "the arrow key did not turn the panel to the clothes rail");

            earn(walk.game, 1, piece.price() + 20);
            int before = walk.game.guide().points();
            walk.press(KeyEvent.VK_ENTER);
            Outfit outfit = walk.game.player(1).outfit();
            assertTrue(outfit.owns(piece.key()), "the panel bought nothing");
            assertTrue(outfit.wearing(piece.key()), "and did not put it on");
            assertEquals(before - piece.price(), walk.game.guide().points());

            // The same row again is a "take it off" rather than a second sale.
            walk.press(KeyEvent.VK_ENTER);
            assertFalse(outfit.wearing(piece.key()), "the second click did not undress");
            assertTrue(outfit.owns(piece.key()));
            assertEquals(before - piece.price(), walk.game.guide().points(),
                    "clicking an owned coat charged for it again");

            walk.press(KeyEvent.VK_RIGHT);
            assertEquals(shop.stock().get(0).item(), walk.walk.panelCursor(),
                    "the panel would not go back to the shelf");

            RecordingTarget frame = new RecordingTarget(800, 480);
            walk.scenes.render(frame, 0f);
            assertFalse(frame.commands().isEmpty(), "the shop screen drew nothing");
        }
    }

    // --- the plumbing --------------------------------------------------------------------------

    private static Mesh walker(List<String> worn) { return walker(worn, 0x4A6B33); }

    private static Mesh walker(List<String> worn, int coat) {
        Mesh.Builder mesh = Mesh.builder(0, 0, 0, false, 1);
        WalkerModel.walker(mesh, 0, 0, 0, 0.7, false, 0.25, 3.0,
                WalkerModel.Leap.GROUNDED, coat, worn);
        return mesh.build();
    }

    private static Mesh swimmer(double pitch, List<String> worn) {
        Mesh.Builder mesh = Mesh.builder(0, 0, 0, false, 1);
        WalkerModel.swimmer(mesh, 0, 0, 0, 0.7, pitch, 1, 0.3, true, 0x4A6B33, worn);
        return mesh.build();
    }

    private static Mesh rower(List<String> worn) {
        Mesh.Builder mesh = Mesh.builder(0, 0, 0, false, 1);
        WalkerModel.rower(mesh, 0, 0, 0, 0.7, 0.25, 0.2, 0x4A6B33, worn);
        return mesh.build();
    }

    /** Every colour in a mesh. */
    private static Set<Integer> colourOf(Mesh mesh) {
        Set<Integer> out = new HashSet<>();
        for (int i = 0; i < mesh.vertexCount(); i++) out.add(mesh.colours()[i]);
        return out;
    }

    /**
     * The colours one piece puts on a walker that were not already on them.
     *
     * <p>The difference rather than the whole set, because the whole set
     * contains the coat — so two walkers in different coats would differ
     * whatever they were wearing, and the question here is only about the
     * garment.
     */
    private static Set<Integer> addedColours(String key, int coat) {
        Set<Integer> dressed = colourOf(walker(List.of(key), coat));
        dressed.removeAll(colourOf(walker(WalkerModel.WEARING_NOTHING, coat)));
        return dressed;
    }

    /**
     * Whether every triangle of {@code part} is somewhere in {@code whole}.
     *
     * <p>By position rather than by index, because the clothes are emitted
     * between the body's own parts — a hat goes on with the head — so a dressed
     * mesh is the bare one interleaved rather than the bare one followed by
     * something. What matters is that none of it went missing.
     */
    private static boolean containsAllTriangles(Mesh whole, Mesh part) {
        Set<String> have = new HashSet<>();
        for (int i = 0; i < whole.vertexCount(); i++) have.add(vertex(whole, i));
        for (int i = 0; i < part.vertexCount(); i++) {
            if (!have.contains(vertex(part, i))) return false;
        }
        return true;
    }

    private static String vertex(Mesh mesh, int i) {
        int at = i * Mesh.FLOATS_PER_VERTEX;
        return mesh.vertices()[at] + "," + mesh.vertices()[at + 1] + ","
                + mesh.vertices()[at + 2] + ":" + mesh.colours()[i];
    }

    /** What one client thinks another player has on. */
    private static List<String> wornBy(WatchClient client, int id) {
        for (WatchView.Walker walker : client.view().walkers()) {
            if (walker.id() == id) return walker.wornKeys();
        }
        return List.of();
    }

    /** The dearest thing on a rail, which is the one nobody can afford. */
    private static Cosmetics.Piece dearest(Shops.Shop shop) {
        Cosmetics.Piece best = shop.rail().get(0);
        for (Cosmetics.Piece piece : shop.rail()) {
            if (piece.price() > best.price()) best = piece;
        }
        return best;
    }

    /** A world, a walker, and the walker standing at the nearest post's counter. */
    private static WatchGame atACounter(long seed, int id, String name) {
        WatchGame game = new WatchGame(WatchGame.Config.hosted("Trading", seed));
        assertNotNull(game.join(id, name));
        Shops.Shop shop = game.shops().nearest(game.field(), 0, 0, 4000);
        assertNotNull(shop, "the test world has no trading post within four kilometres");
        stand(game, id, shop.counterX(), shop.counterY());
        assertNotNull(game.shopAt(id), "standing at the counter is not standing at it");
        return game;
    }

    private static void stand(WatchGame game, int id, double x, double y) {
        game.move(id, x, y, game.field().heightAt(x, y), 0, 0, false, 0.05);
    }

    /** Hand the guide some points to spend, the honest way: by seeing things. */
    private static int earn(WatchGame game, int id, int wanted) {
        List<AnimalDef> defs = AnimalRegistry.all();
        int at = 0;
        while (game.guide().points() < wanted && at < defs.size()) {
            AnimalDef def = defs.get(at++);
            WatchPlayer me = game.player(id);
            game.guide().record(new Sighting(def.key(), System.currentTimeMillis(), 0.5,
                    "pine_forest", me.name(), me.x(), me.y(), true));
        }
        return game.guide().points();
    }

    /** A running walk standing at the nearest trading post's counter. */
    private static final class Walk implements AutoCloseable {

        private final SceneManager scenes = new SceneManager();
        private final InputManager input = new InputManager();
        private final WatchSession session;
        private final WatchGame game;
        private final WatchScene walk;

        private Walk(GameContext ctx, WatchStore store, long seed, Shops.Shop shop) {
            walk = new WatchScene(ctx);
            scenes.setViewport(800, 480);
            scenes.register(WatchLobbyScene.NAME, new WatchLobbyScene(ctx, store));
            scenes.register(WatchScene.NAME, walk);
            game = new WatchGame(new WatchGame.Config(seed, "Trading", 1));
            game.join(1, "Kara");
            stand(game, 1, shop.counterX(), shop.counterY());
            session = WatchSession.solo(game);
            session.setSelfId(1);
            walk.adopt(session, store);
            scenes.setScene(WatchScene.NAME);
            for (int i = 0; i < 6; i++) step();
        }

        static Walk atACounter(Path dir) {
            GameContext ctx = new GameContext(null, new GameTypeStore(dir.toString()));
            WatchStore store = new WatchStore(dir.resolve("walks").toString());
            for (long seed = 1; seed < 60; seed++) {
                TerrainField field = new TerrainField(seed);
                Shops.Shop shop = new Shops(seed).nearest(field, 0, 0, 900);
                if (shop != null) return new Walk(ctx, store, seed, shop);
            }
            throw new IllegalStateException("no seed in sixty puts a post within 900 m");
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
            walk.adopt(null, null);
            session.close();
        }
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
}
