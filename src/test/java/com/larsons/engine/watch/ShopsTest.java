package com.larsons.engine.watch;

import com.larsons.engine.config.GameContext;
import com.larsons.engine.config.GameTypeStore;
import com.larsons.engine.demo.WatchLobbyScene;
import com.larsons.engine.demo.WatchScene;
import com.larsons.engine.graphics.draw.RecordingTarget;
import com.larsons.engine.input.InputManager;
import com.larsons.engine.scene.SceneManager;
import com.larsons.engine.watch.life.Animal;
import com.larsons.engine.watch.life.AnimalDef;
import com.larsons.engine.watch.life.AnimalRegistry;
import com.larsons.engine.watch.life.Rarity;
import com.larsons.engine.watch.net.WatchClient;
import com.larsons.engine.watch.net.WatchServer;
import com.larsons.engine.watch.net.WatchSession;
import com.larsons.engine.watch.render.KeeperModel;
import com.larsons.engine.watch.render.Mesh;
import com.larsons.engine.watch.render.ShopModel;
import com.larsons.engine.watch.world.Flora;
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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Trading posts, and the thing they are really for.
 *
 * <p>Three claims, in order, and the third is the one the whole feature exists
 * to make.
 *
 * <ol>
 *   <li><b>They are in the world.</b> Generated from the seed like the trails
 *       and the boats, standing beside a path on flat dry ground, far enough
 *       apart never to overlap, with nothing growing through them — and
 *       identical for two players who have never exchanged a byte.</li>
 *   <li><b>They trade.</b> Points buy materials, at the counter, from the shelf
 *       that post actually has, out of the shared book's balance — and the whole
 *       thing is refused to somebody standing in the next valley or short of the
 *       price, without taking anything.</li>
 *   <li><b>They turn the page.</b> A stamped page makes every species already in
 *       the guide worth its rarity again <em>without removing one of them from
 *       the guide</em>, which is the entire ask: the record is permanent and the
 *       scoring is not.</li>
 * </ol>
 */
@Timeout(180)
class ShopsTest {

    private static final long SEED = 20260828L;

    private WatchServer server;
    private final List<WatchClient> clients = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (WatchClient client : clients) client.close();
        clients.clear();
        if (server != null) server.stop();
    }

    // --- where they are ----------------------------------------------------------------

    /** The world has posts in it, at a density somebody could actually walk to. */
    @Test
    void aWorldHasTradingPostsInIt() {
        TerrainField field = new TerrainField(SEED);
        List<Shops.Shop> found = new Shops(SEED).near(field, 0, 0, 2000);
        assertFalse(found.isEmpty(), "a whole world with no trading post in it");
        // One per one-and-a-bit square kilometres, measured: enough that
        // following a trail finds one, few enough that finding one is an event.
        assertTrue(found.size() >= 4 && found.size() <= 40,
                "posts within 2 km: " + found.size());
    }

    /**
     * Every post is beside a path, on dry, gentle ground.
     *
     * <p>All three matter for different reasons: a shop nobody walks past is a
     * shop nobody finds, a shop in a lake is absurd, and a shop on a cliff has
     * its deck a metre in the air at one corner.
     */
    @Test
    void everyPostStandsBesideATrailOnGroundThatWillTakeOne() {
        for (long seed = 1; seed <= 8; seed++) {
            TerrainField field = new TerrainField(seed);
            Flora.Ground ground = Flora.ground(field);
            for (Shops.Shop shop : new Shops(seed).near(field, 0, 0, 1500)) {
                String where = "seed " + seed + " " + shop.sign();
                assertEquals(0, field.waterDepth(shop.z()), 1e-9, where + " is in water");
                assertTrue(shop.z() > TerrainField.WATER_LEVEL, where + " is at the tide line");
                assertTrue(ground.slopeAt(shop.x(), shop.y()) <= 0.25,
                        where + " is pitched on a slope");
                assertTrue(trailNear(field, shop.x(), shop.y(), 9) > 0.5,
                        where + " is nowhere near a path");
            }
        }
    }

    /** The strongest a trail runs anywhere within a radius of a point. */
    private static double trailNear(TerrainField field, double x, double y, double radius) {
        double best = 0;
        for (int a = 0; a < 24; a++) {
            double angle = a * Math.PI / 12;
            for (double r = 1; r <= radius; r++) {
                best = Math.max(best, field.trailAt(x + Math.cos(angle) * r,
                        y + Math.sin(angle) * r));
            }
        }
        return best;
    }

    /**
     * Two posts never stand in each other.
     *
     * <p>The siting probe walks a line long enough to leave the cell it started
     * in, so without {@code CELL_INSET} two neighbouring cells could both site a
     * post at the boundary between them and put two buildings through each
     * other. This is the test that says the inset is doing its job.
     */
    @Test
    void twoPostsAreNeverOnTopOfEachOther() {
        for (long seed = 1; seed <= 8; seed++) {
            List<Shops.Shop> found =
                    new Shops(seed).near(new TerrainField(seed), 0, 0, 2000);
            for (Shops.Shop a : found) {
                for (Shops.Shop b : found) {
                    if (a.id() == b.id()) continue;
                    double apart = Math.hypot(a.x() - b.x(), a.y() - b.y());
                    assertTrue(apart > Shops.CLEARING * 2,
                            "seed " + seed + ": two posts " + Math.round(apart) + " m apart");
                }
            }
        }
    }

    /** A post is a function of the seed, so two machines agree without talking. */
    @Test
    void twoPlayersOnOneSeedFindTheSamePostsAndTheSameKeepers() {
        List<Shops.Shop> mine = new Shops(SEED).near(new TerrainField(SEED), 0, 0, 1200);
        List<Shops.Shop> yours = new Shops(SEED).near(new TerrainField(SEED), 0, 0, 1200);
        assertEquals(mine.size(), yours.size(), "two clients count different posts");
        assertFalse(mine.isEmpty());
        for (int i = 0; i < mine.size(); i++) {
            Shops.Shop a = mine.get(i), b = yours.get(i);
            assertEquals(a.id(), b.id());
            assertEquals(a.x(), b.x(), 1e-9);
            assertEquals(a.y(), b.y(), 1e-9);
            assertEquals(a.yaw(), b.yaw(), 1e-9);
            assertEquals(a.sign(), b.sign());
            assertEquals(a.keeper(), b.keeper(), "the same post has two different keepers");
            assertEquals(a.stock(), b.stock(), "the same post has two different shelves");
        }
    }

    /** …and a different world is a different set of posts. */
    @Test
    void adifferentSeedIsADifferentSetOfPosts() {
        List<Shops.Shop> a = new Shops(SEED).near(new TerrainField(SEED), 0, 0, 2000);
        List<Shops.Shop> b = new Shops(SEED + 1)
                .near(new TerrainField(SEED + 1), 0, 0, 2000);
        Set<String> here = new HashSet<>();
        for (Shops.Shop shop : a) here.add(Math.round(shop.x()) + "/" + Math.round(shop.y()));
        int shared = 0;
        for (Shops.Shop shop : b) {
            if (here.contains(Math.round(shop.x()) + "/" + Math.round(shop.y()))) shared++;
        }
        assertEquals(0, shared, "two worlds put a post in the same place");
    }

    /**
     * Nothing wild grows in the yard.
     *
     * <p>Trees, bushes and boulders all go through {@code Flora}, and the litter
     * on the floor goes through {@code Litter}; all four ask
     * {@code Shops.clearingAt} before they put anything down. Without it a post
     * has an oak through its roof, which is the only failure of this feature a
     * player would notice from across a valley.
     */
    @Test
    void nothingGrowsThroughATradingPost() {
        for (long seed = 1; seed <= 6; seed++) {
            TerrainField field = new TerrainField(seed);
            Flora flora = new Flora(seed, field);
            Litter litter = new Litter(seed, field);
            Flora.Ground ground = Flora.ground(field);
            for (Shops.Shop shop : new Shops(seed).near(field, 0, 0, 1500)) {
                double reach = Shops.CLEARING - 0.1;
                assertNull(flora.nearestTree(ground, shop.x(), shop.y(), reach),
                        "a tree is standing in " + shop.sign());
                assertNull(flora.nearestBush(ground, shop.x(), shop.y(), reach),
                        "a bush is standing in " + shop.sign());
                assertNull(flora.nearestRock(ground, shop.x(), shop.y(), reach),
                        "a boulder is standing in " + shop.sign());
                assertNull(litter.nearest(ground, shop.x(), shop.y(), reach),
                        "somebody's yard is full of fallen branches");
            }
        }
    }

    /** The clearing is local: a post does not sweep the whole wood. */
    @Test
    void theClearingIsOnlyTheYard() {
        TerrainField field = new TerrainField(SEED);
        Shops shops = new Shops(SEED);
        Shops.Shop shop = shops.nearest(field, 0, 0, 3000);
        assertNotNull(shop, "no post to measure");
        assertTrue(shops.clearingAt(field, shop.x(), shop.y()), "a post is not its own yard");
        assertFalse(shops.clearingAt(field, shop.x() + Shops.CLEARING + 1, shop.y()),
                "the clearing reaches past the yard");
    }

    // --- who keeps them ----------------------------------------------------------------

    /**
     * Keepers are people, not a repeated prop.
     *
     * <p>The claim being tested is variety with permanence: a walk finds several
     * different people, and the same post finds the same one every time. Both
     * halves are what makes a keeper a character rather than a shop front.
     */
    @Test
    void eachPostHasItsOwnKeeper() {
        List<Shops.Shop> found = new Shops(SEED).near(new TerrainField(SEED), 0, 0, 2500);
        assertTrue(found.size() >= 4, "not enough posts to say anything about their keepers");
        Set<String> names = new HashSet<>();
        Set<String> signs = new HashSet<>();
        for (Shops.Shop shop : found) {
            Shops.Keeper keeper = shop.keeper();
            assertNotNull(keeper.name());
            assertFalse(keeper.name().isBlank());
            assertFalse(keeper.greeting().isBlank(), keeper.name() + " has nothing to say");
            assertFalse(keeper.stampLine().isBlank());
            assertNotNull(keeper.hat());
            assertTrue(keeper.build() > 0.8 && keeper.build() < 1.15,
                    keeper.name() + " is the wrong size for a person");
            // A companion is optional; one that exists has to be a real species,
            // and a small one — a keeper with a bear on the counter is a
            // different game.
            if (keeper.companion() != null) {
                AnimalDef pet = keeper.companionDef();
                assertNotNull(pet, keeper.name() + " has an animal nobody has heard of");
                assertTrue(pet.tameable(), "a keeper has tamed something untameable");
                assertTrue(pet.bodyLength() < 0.45, "that is not a counter animal");
            }
            names.add(keeper.name());
            signs.add(shop.sign());
        }
        assertTrue(names.size() > found.size() / 2,
                "the same keeper is running most of the world's shops");
        assertTrue(signs.size() > found.size() / 2, "every shop has the same name");
    }

    // --- what they sell ----------------------------------------------------------------

    /**
     * Every shelf is stocked, with real things, at prices somebody could pay.
     *
     * <p>And the rule that keeps the rest of the game intact: <b>no post sells a
     * spyglass</b>. Quartz and sand and a ground lens are materials and are for
     * sale; the glass itself is the deepest crafting chain in the book and the
     * one thing worth travelling for, and a shop that sold one would delete it.
     */
    @Test
    void everyShelfIsStockedWithRealThingsAtRealPrices() {
        for (long seed = 1; seed <= 8; seed++) {
            for (Shops.Shop shop : new Shops(seed).near(new TerrainField(seed), 0, 0, 1500)) {
                assertFalse(shop.stock().isEmpty(), shop.sign() + " sells nothing");
                Set<String> seen = new HashSet<>();
                for (Trading.Offer offer : shop.stock()) {
                    assertNotNull(Forage.byKey(offer.item()),
                            shop.sign() + " sells " + offer.item() + ", which does not exist");
                    assertTrue(offer.price() > 0, offer.item() + " is free");
                    assertTrue(offer.quantity() > 0, offer.item() + " comes in a bundle of none");
                    assertTrue(seen.add(offer.item()),
                            shop.sign() + " lists " + offer.item() + " twice");
                    assertFalse(Spyglass.ITEM.equals(offer.item()),
                            shop.sign() + " is selling the one thing worth travelling for");
                }
                // The staples, because somebody who walked two kilometres should
                // never arrive to find nothing they can use.
                assertNotNull(shop.offer("fallen_branch"), shop.sign() + " has no branches");
                assertNotNull(shop.offer("rope"), shop.sign() + " has no rope");
                assertNotNull(shop.offer("feeder"), shop.sign() + " has no feeders");
            }
        }
    }

    /** Two keepers are not the same shop twice. */
    @Test
    void twoPostsChargeDifferentPricesAndCarryDifferentThings() {
        List<Shops.Shop> found = new Shops(SEED).near(new TerrainField(SEED), 0, 0, 2500);
        assertTrue(found.size() >= 4);
        Set<Integer> ropePrices = new HashSet<>();
        Set<String> shelves = new HashSet<>();
        for (Shops.Shop shop : found) {
            ropePrices.add(shop.offer("rope").price());
            StringBuilder shelf = new StringBuilder();
            for (Trading.Offer offer : shop.stock()) shelf.append(offer.item()).append(',');
            shelves.add(shelf.toString());
        }
        assertTrue(ropePrices.size() > 1, "every keeper in the world charges the same for rope");
        assertTrue(shelves.size() > 1, "every keeper in the world carries the same shelf");
    }

    // --- trading ------------------------------------------------------------------------

    /** A world, a walker, and the walker standing at the nearest post's counter. */
    private static WatchGame atACounter(long seed, int id, String name, int[] outShop) {
        WatchGame game = new WatchGame(WatchGame.Config.hosted("Trading", seed));
        WatchPlayer player = game.join(id, name);
        assertNotNull(player);
        Shops.Shop shop = game.shops().nearest(game.field(), 0, 0, 4000);
        assertNotNull(shop, "the test world has no trading post within four kilometres");
        stand(game, id, shop.counterX(), shop.counterY());
        assertNotNull(game.shopAt(id), "standing at the counter is not standing at the counter");
        if (outShop != null) outShop[0] = 1;
        return game;
    }

    /** Put a player at a point, the way a client's movement messages would. */
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

    @Test
    void pointsBuyMaterialsAtTheCounter() {
        WatchGame game = atACounter(SEED, 1, "Kara", null);
        Shops.Shop shop = game.shopAt(1);
        Trading.Offer offer = shop.offer("fallen_branch");
        earn(game, 1, offer.price() + 10);

        int before = game.guide().points();
        int carried = game.player(1).satchel().count("fallen_branch");
        assertNotNull(game.buy(1, shop.id(), "fallen_branch"), "the purchase did nothing");

        assertEquals(carried + offer.quantity(),
                game.player(1).satchel().count("fallen_branch"),
                "the goods never arrived");
        assertEquals(before - offer.price(), game.guide().points(),
                "the points did not change hands");
    }

    /**
     * A purchase nobody can afford takes nothing.
     *
     * <p>All or nothing, like every other cost in this game — a player who
     * cannot pay must not end up out of pocket <em>and</em> empty-handed.
     */
    @Test
    void aPurchaseNobodyCanAffordCostsNothing() {
        WatchGame game = atACounter(SEED, 1, "Kara", null);
        Shops.Shop shop = game.shopAt(1);
        Trading.Offer offer = dearest(shop);
        earn(game, 1, Math.max(1, offer.price() - 1));
        // Spend down to under the price, so the refusal is about the balance and
        // not about an empty book.
        while (game.guide().points() >= offer.price()) game.guide().spend(1);

        int before = game.guide().points();
        int carried = game.player(1).satchel().count(offer.item());
        String line = game.buy(1, shop.id(), offer.item());

        assertNotNull(line, "a refusal should still say something");
        assertTrue(line.toLowerCase().contains("not enough"), line);
        assertEquals(before, game.guide().points(), "a refused purchase took the points");
        assertEquals(carried, game.player(1).satchel().count(offer.item()),
                "a refused purchase handed the goods over anyway");
    }

    private static Trading.Offer dearest(Shops.Shop shop) {
        Trading.Offer best = shop.stock().get(0);
        for (Trading.Offer offer : shop.stock()) {
            if (offer.price() > best.price()) best = offer;
        }
        return best;
    }

    /**
     * You have to be there.
     *
     * <p>The id in the request is a disambiguator, not an address: a client that
     * could name a post it had merely heard of could buy a lens from the far
     * side of the world, and the whole reason posts are in the wild is that
     * walking to one is the cost.
     */
    @Test
    void nobodyBuysAnythingFromTheNextValley() {
        WatchGame game = atACounter(SEED, 1, "Kara", null);
        Shops.Shop shop = game.shopAt(1);
        earn(game, 1, 400);
        int before = game.guide().points();

        stand(game, 1, shop.x() + 400, shop.y() + 400);
        assertNull(game.shopAt(1), "the counter followed the player");
        assertNull(game.buy(1, shop.id(), "fallen_branch"),
                "a player four hundred metres away bought something");
        assertNull(game.stamp(1, shop.id()), "a keeper stamped a page for somebody not there");
        assertEquals(before, game.guide().points());
    }

    /** Naming the wrong post at the right counter is refused rather than redirected. */
    @Test
    void aRequestNamingADifferentPostIsRefused() {
        WatchGame game = atACounter(SEED, 1, "Kara", null);
        earn(game, 1, 200);
        int before = game.guide().points();
        assertNull(game.buy(1, 0x5EEDL, "fallen_branch"),
                "a request naming a post that is not here was honoured");
        assertEquals(before, game.guide().points());
    }

    /** A keeper will not sell what they do not have. */
    @Test
    void aKeeperOnlySellsWhatIsOnTheirOwnShelf() {
        WatchGame game = atACounter(SEED, 1, "Kara", null);
        Shops.Shop shop = game.shopAt(1);
        earn(game, 1, 500);
        String missing = null;
        for (Trading.Offer offer : Trading.catalogue()) {
            if (shop.offer(offer.item()) == null) {
                missing = offer.item();
                break;
            }
        }
        assertNotNull(missing, "this post carries the entire catalogue, which it should not");
        assertNull(game.buy(1, shop.id(), missing),
                "a keeper sold something that was not on the shelf");
        assertNull(game.buy(1, shop.id(), "not_a_thing"));
    }

    /**
     * A trading post is free in debug mode, and the page still is not.
     *
     * <p>{@code Debug}'s whole design is that every cost in this game is a
     * {@code has} and a {@code take} against a {@link Satchel}, so one lens over
     * that one class covers the costs that do not exist yet. A shop's prices are
     * the first cost that breaks it — they come out of the guide's balance —
     * which is why {@code Debug.Power.POINTS} exists and why this test does.
     *
     * <p>And the other half: a stamp is <em>not</em> a debug power. Debug mode
     * is a lens over what a player can afford, and turning the page is a thing
     * the guide records rather than a thing anybody pays for.
     */
    @Test
    void debugModeBuysAnythingOnTheShelfAndStillWillNotStampABlankPage() {
        WatchGame game = atACounter(SEED, 1, "Kara", null);
        Shops.Shop shop = game.shopAt(1);
        assertTrue(game.debug(1, Debug.CODE), "the host could not use their own code");
        assertEquals(0, game.guide().points(), "this walk has earned nothing yet");

        for (Trading.Offer offer : shop.stock()) {
            assertNotNull(game.buy(1, shop.id(), offer.item()),
                    offer.item() + " could not be bought with unlimited points");
            assertTrue(game.player(1).satchel().has(offer.item()),
                    offer.item() + " was paid for and never handed over");
        }
        assertEquals(0, game.guide().spent(),
                "debug mode spent the book's points rather than standing in for them");

        String line = game.stamp(1, shop.id());
        assertTrue(line != null && line.toLowerCase().contains("nothing on it"),
                "debug mode turned a blank page: " + line);
        assertEquals(1, game.guide().volume());
    }

    // --- the page ------------------------------------------------------------------------

    /**
     * <b>The whole feature, in one test.</b>
     *
     * <p>A species scores once. Seeing it again scores nothing. A keeper stamps
     * a fresh page and it scores again — and it never left the guide.
     */
    @Test
    void aStampedPageMakesEverythingWorthPointsAgainWithoutLosingTheRecord() {
        WatchGame game = atACounter(SEED, 1, "Kara", null);
        Shops.Shop shop = game.shopAt(1);
        FieldGuide guide = game.guide();

        AnimalDef def = AnimalRegistry.all().get(0);
        int worth = def.rarity().points();
        WatchPlayer me = game.player(1);
        Sighting sighting = new Sighting(def.key(), System.currentTimeMillis(), 0.5,
                "pine_forest", "Kara", me.x(), me.y(), true);

        assertEquals(worth, guide.award(def.key()), "an unseen species is worth nothing");
        guide.record(sighting);
        assertEquals(worth, guide.points());
        assertEquals(0, guide.award(def.key()), "the same species paid twice on one page");

        guide.record(sighting);
        assertEquals(worth, guide.points(), "seeing it again paid again");
        assertEquals(1, guide.discovered());
        assertEquals(1, guide.volume(), "no page has been turned yet");

        assertNotNull(game.stamp(1, shop.id()), "the keeper would not stamp a full page");

        assertTrue(guide.seen(def.key()),
                "a stamped page took the species out of the guide — the one thing it must never do");
        assertEquals(1, guide.discovered(), "the record shrank");
        assertEquals(2, guide.volume(), "the volume did not turn over");
        assertEquals(1, guide.volumes().size());
        assertEquals(worth, guide.volumes().get(0).points(), "the closed page lost its score");
        assertEquals(0, guide.tallied(), "the new page is not blank");
        assertEquals(worth, guide.award(def.key()),
                "after a fresh page the species is still worth nothing, which is the bug");

        guide.record(sighting);
        assertEquals(worth * 2, guide.points(), "it did not pay the second time round");
        assertEquals(1, guide.discovered(), "scoring it again added a second entry");
    }

    /** A keeper will not stamp a blank page. */
    @Test
    void aKeeperWillNotStampABlankPage() {
        WatchGame game = atACounter(SEED, 1, "Kara", null);
        Shops.Shop shop = game.shopAt(1);
        assertEquals(0, game.guide().tallied());

        String line = game.stamp(1, shop.id());
        assertNotNull(line, "the keeper said nothing at all");
        assertTrue(line.toLowerCase().contains("nothing on it"), line);
        assertEquals(1, game.guide().volume(), "a blank page was turned anyway");

        // …and this is what stops the loop being spot, stamp, spot, stamp on one
        // animal standing in front of the counter. Filling the page is the cost
        // of turning it.
        earn(game, 1, 1);
        assertNotNull(game.stamp(1, shop.id()));
        assertEquals(2, game.guide().volume());

        String again = game.stamp(1, shop.id());
        assertTrue(again != null && again.toLowerCase().contains("nothing on it"),
                "the keeper stamped two pages running: " + again);
        assertEquals(2, game.guide().volume(), "a page was turned twice on one visit");
    }

    /** Spotting through the real game pays, and says what it paid. */
    @Test
    void spottingSomethingAlreadyInTheBookPaysAgainAfterAStamp() {
        WatchGame game = new WatchGame(WatchGame.Config.hosted("Trading", SEED));
        WatchPlayer player = game.join(1, "Kara");
        for (int i = 0; i < 200; i++) {
            game.move(1, player.x(), player.y(), player.z(), 0, 0, false, 0.05);
            game.tick(0.05);
        }
        Animal animal = null;
        for (int i = 0; i < 600 && game.animals().isEmpty(); i++) game.tick(0.05);
        for (Animal candidate : game.animals()) animal = candidate;
        assertNotNull(animal, "nothing turned up to look at");

        Spotlight first = game.spot(1, animal.id());
        assertNotNull(first);
        assertTrue(first.points() > 0, "the first sighting of a species paid nothing");
        assertEquals(first.points(), animal.def().rarity().points());

        Spotlight second = game.spot(1, animal.id());
        assertNotNull(second);
        assertEquals(0, second.points(), "the same species paid twice on one page");

        game.guide().stamp("Test", "here", System.currentTimeMillis());
        Spotlight third = game.spot(1, animal.id());
        assertNotNull(third);
        assertEquals(animal.def().rarity().points(), third.points(),
                "a fresh page did not make an old friend worth anything");
        assertFalse(third.discovery(), "the third sighting claims to be a discovery");
    }

    /** A closed page keeps its own record, which is what makes turning one bearable. */
    @Test
    void aClosedPageIsKeptAsAVolume() {
        FieldGuide guide = new FieldGuide();
        AnimalDef common = defOf(Rarity.COMMON);
        AnimalDef rare = defOf(Rarity.RARE);
        guide.record(sightingOf(common));
        guide.record(sightingOf(rare));

        FieldGuide.Page page = guide.stamp("Ilsa Waverly", "Pine Forest", 1234L);
        assertNotNull(page);
        assertEquals(1, page.index());
        assertEquals(2, page.species());
        assertEquals(common.rarity().points() + rare.rarity().points(), page.points());
        assertEquals("Ilsa Waverly", page.keeper());
        assertEquals("Pine Forest", page.place());
        assertTrue(page.describe().contains("Vol. 1"), page.describe());
    }

    private static AnimalDef defOf(Rarity rarity) {
        for (AnimalDef def : AnimalRegistry.all()) {
            if (def.rarity() == rarity) return def;
        }
        throw new IllegalStateException("no " + rarity + " species in the registry");
    }

    private static Sighting sightingOf(AnimalDef def) {
        return new Sighting(def.key(), System.currentTimeMillis(), 0.5, "pine_forest",
                "Kara", 0, 0, true);
    }

    // --- persistence ----------------------------------------------------------------------

    /** The ledger, the open page and the closed volumes all survive a save. */
    @Test
    void theLedgerAndThePagesSurviveASave() {
        FieldGuide guide = new FieldGuide();
        guide.record(sightingOf(defOf(Rarity.COMMON)));
        guide.record(sightingOf(defOf(Rarity.SCARCE)));
        guide.stamp("Ilsa", "Pine Forest", 500L);
        guide.record(sightingOf(defOf(Rarity.RARE)));
        assertTrue(guide.spend(3), "could not spend what was earned");

        FieldGuide reopened = new FieldGuide();
        reopened.load(guide.toMap());

        assertEquals(guide.points(), reopened.points(), "the balance did not survive");
        assertEquals(guide.earned(), reopened.earned());
        assertEquals(guide.spent(), reopened.spent());
        assertEquals(guide.discovered(), reopened.discovered());
        assertEquals(guide.tallied(), reopened.tallied(), "the open page did not survive");
        assertEquals(guide.page(), reopened.page());
        assertEquals(guide.volume(), reopened.volume());
        assertEquals(guide.volumes().size(), reopened.volumes().size());
        assertEquals(guide.volumes().get(0).points(), reopened.volumes().get(0).points());
    }

    /**
     * A message carrying one entry leaves the ledger alone.
     *
     * <p>The single-sighting announce is how a party's book is kept in step, and
     * it deliberately carries no ledger at all. A {@code load} that treated an
     * absent field as a reason to recompute would credit the whole record again
     * every time anybody found a wren.
     */
    @Test
    void aSingleEntryMessageDoesNotDisturbTheLedger() {
        FieldGuide guide = new FieldGuide();
        guide.record(sightingOf(defOf(Rarity.RARE)));
        int points = guide.points();
        int tallied = guide.tallied();

        Map<String, Object> announce = Map.of("entries",
                List.of(sightingOf(defOf(Rarity.COMMON)).toMap()));
        guide.load(announce);

        assertEquals(points, guide.points(), "one entry moved the balance");
        assertEquals(tallied, guide.tallied(), "one entry moved the open page");
        assertEquals(2, guide.discovered(), "the entry itself was not filed");
    }

    // --- online -----------------------------------------------------------------------------

    /**
     * The whole thing over a socket: a client buys, and both the satchel and the
     * shared balance come back.
     *
     * <p>And what does <em>not</em> travel is the point: no message names a
     * shop's position, its keeper or its shelf, because the client generated all
     * three from the seed. See {@code WatchProto}.
     */
    @Test
    void aClientBuysOverTheWireAndTheWholePartySeesTheBalanceChange() throws IOException {
        server = new WatchServer(WatchGame.Config.hosted("Trading", SEED));
        server.start(0);
        WatchClient buyer = connect("Kara");
        WatchClient friend = connect("Sam");
        until("both to be welcomed", () -> buyer.ready() && friend.ready());
        until("the host to seat them", () -> server.playerCount() == 2);

        WatchGame game = server.game();
        Shops.Shop shop = game.shops().nearest(game.field(), 0, 0, 4000);
        assertNotNull(shop);
        earn(game, buyer.view().selfId(), 300);
        // Walk there, the way a client says where it is.
        buyer.sendMove(shop.counterX(), shop.counterY(),
                game.field().heightAt(shop.counterX(), shop.counterY()), 0, 0, false);
        until("the host to put the buyer at the counter",
                () -> game.shopAt(buyer.view().selfId()) != null);

        Trading.Offer offer = shop.offer("fallen_branch");
        int before = game.guide().points();
        buyer.sendBuy(shop.id(), "fallen_branch");

        until("the goods to arrive",
                () -> buyer.view().satchel().count("fallen_branch") >= offer.quantity());
        until("the buyer's book to show the cost",
                () -> buyer.view().guide().points() == before - offer.price());
        until("the friend's book to show it too",
                () -> friend.view().guide().points() == before - offer.price());
        assertEquals(before - offer.price(), game.guide().points());
    }

    /** …and a stamp, which is the one thing a client cannot work out for itself. */
    @Test
    void aStampOverTheWireEmptiesEveryonesPage() throws IOException {
        server = new WatchServer(WatchGame.Config.hosted("Trading", SEED));
        server.start(0);
        WatchClient walker = connect("Kara");
        WatchClient friend = connect("Sam");
        until("both to be welcomed", () -> walker.ready() && friend.ready());
        until("the host to seat them", () -> server.playerCount() == 2);

        WatchGame game = server.game();
        Shops.Shop shop = game.shops().nearest(game.field(), 0, 0, 4000);
        assertNotNull(shop);
        earn(game, walker.view().selfId(), 60);
        int tallied = game.guide().tallied();
        assertTrue(tallied > 0);

        walker.sendMove(shop.counterX(), shop.counterY(),
                game.field().heightAt(shop.counterX(), shop.counterY()), 0, 0, false);
        until("the host to put them at the counter",
                () -> game.shopAt(walker.view().selfId()) != null);

        // Buy something first, which is what pushes the ledger — and with it the
        // page as it stands — out to everybody. Without a populated page on the
        // clients there would be nothing for the stamp to be seen emptying, and
        // the test would pass on a client that had never heard of any of this.
        walker.sendBuy(shop.id(), "fallen_branch");
        until("both books to show the party's page",
                () -> walker.view().guide().tallied() == tallied
                        && friend.view().guide().tallied() == tallied);

        walker.sendStamp(shop.id());
        until("the host to turn the page", () -> game.guide().volume() == 2);
        until("the walker's page to empty", () -> walker.view().guide().tallied() == 0);
        until("the friend's page to empty too",
                () -> friend.view().guide().tallied() == 0);
        until("the balances to agree with the host's",
                () -> walker.view().guide().points() == game.guide().points()
                        && friend.view().guide().points() == game.guide().points());
    }

    // --- the building and the person in it ---------------------------------------------------

    private static Mesh built(Shops.Shop shop, double clock, boolean wares) {
        Mesh.Builder mesh = Mesh.builder(0, 0, 0, false, 1);
        ShopModel.post(mesh, shop, 0, 0, clock, wares);
        return mesh.build();
    }

    private static Mesh person(Shops.Keeper keeper, double yaw, double look, double clock) {
        Mesh.Builder mesh = Mesh.builder(0, 0, 0, false, 1);
        KeeperModel.keeper(mesh, keeper, 0, 0, 0, yaw, look, clock, 0);
        return mesh.build();
    }

    private static Shops.Shop somePost() {
        Shops.Shop shop = new Shops(SEED).nearest(new TerrainField(SEED), 0, 0, 4000);
        assertNotNull(shop, "no post to draw");
        return shop;
    }

    /**
     * The post is a building rather than a box, and it stays inside its own
     * yard.
     *
     * <p>The triangle count is the interesting number: it has to be large enough
     * that the thing reads as carpentry — the brief asked for an intricate
     * prebuilt structure — and small enough that it is not a chunk of hillside
     * arriving in the mesh that is rebuilt every frame.
     */
    @Test
    void aPostIsABuildingAndFitsInItsOwnClearing() {
        Shops.Shop shop = somePost();
        Mesh mesh = built(shop, 0, true);
        assertFalse(mesh.isEmpty(), "the trading post drew nothing at all");
        assertTrue(mesh.triangleCount() > 400,
                "a shed, not a building: " + mesh.triangleCount() + " triangles");
        assertTrue(mesh.triangleCount() < 4000,
                "far too much geometry for a thing rebuilt every frame: "
                        + mesh.triangleCount());

        // Everything inside the yard nothing grows in, and nothing underground.
        double reach = Shops.CLEARING;
        assertTrue(mesh.minX() > shop.x() - reach && mesh.maxX() < shop.x() + reach,
                "the post is wider than the clearing it stands in");
        assertTrue(mesh.minY() > shop.y() - reach && mesh.maxY() < shop.y() + reach);
        assertTrue(mesh.minZ() >= shop.z() - 0.01, "part of the post is under the ground");
        assertTrue(mesh.maxZ() < shop.z() + 5, "the roof is in the clouds");
    }

    /** The wares on the shelves are this post's actual stock, and can be dropped. */
    @Test
    void theShelvesCarryTheStockAndTheStockIsWhatIsDroppedAtDistance() {
        Shops.Shop shop = somePost();
        Mesh near = built(shop, 0, true);
        Mesh far = built(shop, 0, false);
        assertTrue(near.triangleCount() > far.triangleCount(),
                "the shelves are empty either way, so the stock is not being drawn");
        // The building itself is unchanged; only the goods went.
        assertEquals(far.minZ(), near.minZ(), 0.001);
    }

    /** The sign swings, and nothing else moves. */
    @Test
    void theSignSwingsAndTheRestOfThePostStandsStill() {
        Shops.Shop shop = somePost();
        Mesh still = built(shop, 0, false);
        Mesh later = built(shop, 1.7, false);
        assertEquals(still.triangleCount(), later.triangleCount(),
                "the post gained or lost geometry between two frames");
        assertFalse(java.util.Arrays.equals(still.vertices(), later.vertices()),
                "nothing on the whole building moves — the sign is nailed to the bracket");
        // …and the movement is small: a sign swinging, not a building rocking.
        float drift = 0;
        for (int i = 0; i < still.vertices().length; i++) {
            drift = Math.max(drift, Math.abs(still.vertices()[i] - later.vertices()[i]));
        }
        assertTrue(drift < 0.25, "something on the post moved " + drift + " m in a second");
    }

    /** A keeper who has nobody on the counter, so a measurement is of them alone. */
    private static Shops.Keeper keeperAlone() {
        for (long seed = 1; seed <= 20; seed++) {
            for (Shops.Shop shop : new Shops(seed).near(new TerrainField(seed), 0, 0, 1500)) {
                if (shop.keeper().companion() == null) return shop.keeper();
            }
        }
        throw new IllegalStateException("every keeper in twenty worlds has an animal");
    }

    /**
     * The keeper is a person: person-sized, standing on the deck, and made of
     * enough parts to be worth standing in front of.
     */
    @Test
    void theKeeperIsAPersonRatherThanAMannequin() {
        Shops.Keeper keeper = keeperAlone();
        Mesh mesh = person(keeper, 0, 0, 0);
        assertFalse(mesh.isEmpty(), "the keeper drew nothing");
        assertTrue(mesh.triangleCount() > 300,
                "a keeper you stand a metre from should have more than "
                        + mesh.triangleCount() + " triangles on them");
        double height = mesh.maxZ() - mesh.minZ();
        assertTrue(height > 1.5 && height < 2.1, "the keeper is " + height + " m tall");
        assertTrue(mesh.minZ() > -0.06 && mesh.minZ() < 0.06,
                "the keeper's boots are " + mesh.minZ() + " m off the floor");
        double width = Math.max(mesh.maxX() - mesh.minX(), mesh.maxY() - mesh.minY());
        assertTrue(width < 1.2, "the keeper is " + width + " m across");
    }

    /** Every kind of keeper the generator can produce actually draws. */
    @Test
    void everyKeeperInTheWorldDraws() {
        Set<Shops.Hat> hats = new HashSet<>();
        int drawn = 0;
        for (long seed = 1; seed <= 10; seed++) {
            for (Shops.Shop shop : new Shops(seed).near(new TerrainField(seed), 0, 0, 1500)) {
                Mesh mesh = person(shop.keeper(), shop.yaw(), shop.yaw() + 0.4, 3.3);
                assertFalse(mesh.isEmpty(), shop.keeper().name() + " drew nothing");
                hats.add(shop.keeper().hat());
                drawn++;
            }
        }
        assertTrue(drawn > 20, "only " + drawn + " keepers to check");
        assertEquals(Shops.Hat.values().length, hats.size(),
                "some hat in the table is never worn by anybody: " + hats);
    }

    /**
     * A keeper's own animal sits on the counter, and not in mid-air in front of
     * it.
     *
     * <p>Two constants have to agree for this: the keeper stands
     * {@code BEHIND_COUNTER} back from the counter, and the counter is measured
     * from the ground while the keeper stands on the deck. The first version of
     * this measured both from the wrong origin and put the jackdaw a metre and a
     * half out over the yard, at a height nothing was standing at.
     */
    @Test
    void theKeepersOwnAnimalSitsOnTheCounter() {
        Shops.Keeper withPet = null;
        for (long seed = 1; seed <= 20 && withPet == null; seed++) {
            for (Shops.Shop shop : new Shops(seed).near(new TerrainField(seed), 0, 0, 1500)) {
                if (shop.keeper().companion() != null) {
                    withPet = shop.keeper();
                    break;
                }
            }
        }
        assertNotNull(withPet, "no keeper in twenty worlds keeps anything");

        Mesh alone = person(new Shops.Keeper(withPet.name(), withPet.trade(),
                withPet.greeting(), withPet.stampLine(), withPet.coatRgb(),
                withPet.trimRgb(), withPet.shirtRgb(), withPet.skinRgb(),
                withPet.hairRgb(), withPet.hat(), withPet.beard(), withPet.spectacles(),
                null, withPet.build()), 0, 0, 0);
        Mesh together = person(withPet, 0, 0, 0);
        assertTrue(together.triangleCount() > alone.triangleCount(),
                "the keeper's animal is not being drawn at all");

        // The counter top, in the keeper's own frame: the counter is measured
        // from the ground and the keeper is standing on the deck.
        double top = Shops.COUNTER_TOP - ShopModel.DECK;
        assertTrue(together.maxZ() >= alone.maxZ() - 0.01,
                "the animal is drawn above the keeper's head");
        // Everything the pet added is within arm's reach and on the counter.
        assertTrue(together.maxY() <= alone.maxY() + 0.01,
                "the animal is sitting behind the keeper");
        assertTrue(together.minY() >= -KeeperModel.BEHIND_COUNTER - 0.6,
                "the animal is out over the yard rather than on the counter: "
                        + together.minY());
        assertTrue(top > 0.5 && top < 1.1,
                "the counter is at " + top + " m to a keeper standing on the deck");
    }

    /** They look at you, and turning to do it moves the head and not the boots. */
    @Test
    void theKeeperTurnsTheirHeadTowardWhoeverIsThere() {
        Shops.Keeper keeper = keeperAlone();
        Mesh ahead = person(keeper, 0, 0, 0);
        Mesh aside = person(keeper, 0, 1.0, 0);
        assertFalse(java.util.Arrays.equals(ahead.vertices(), aside.vertices()),
                "the keeper stares straight ahead whoever walks up");
        // The feet are where they were: a neck turned, not a body.
        assertEquals(ahead.minZ(), aside.minZ(), 0.02, "the keeper turned their boots");

        // And the turn is limited to what a neck does — somebody standing behind
        // them does not get looked at over their own shoulder.
        Mesh behind = person(keeper, 0, Math.PI, 0);
        Mesh limit = person(keeper, 0, 1.15, 0);
        float apart = 0;
        for (int i = 0; i < behind.vertices().length; i++) {
            apart = Math.max(apart, Math.abs(behind.vertices()[i] - limit.vertices()[i]));
        }
        assertTrue(apart < 0.02, "the keeper's head turns further than a neck does");
    }

    /**
     * The idle animation never jumps.
     *
     * <p>The same claim {@code WalkCycleTest} makes about the walk, for the same
     * reason and with the same method: a discontinuity is not a large step, it
     * is a step far larger than the ones either side of it. The keeper leans in
     * to write in the ledger every thirteen seconds or so, and an idle gesture
     * that snapped on would read as a dropped frame.
     */
    @Test
    void theKeeperNeverJumpsBetweenOneFrameAndTheNext() {
        Shops.Keeper keeper = keeperAlone();
        int samples = 900;
        double span = 30.0;
        float[] previous = null;
        double biggest = 0, total = 0;
        int steps = 0;
        for (int i = 0; i <= samples; i++) {
            float[] now = person(keeper, 0, 0, i * span / samples).vertices();
            if (previous != null) {
                double step = 0;
                for (int v = 0; v < now.length; v++) {
                    step = Math.max(step, Math.abs(now[v] - previous[v]));
                }
                biggest = Math.max(biggest, step);
                total += step;
                steps++;
            }
            previous = now;
        }
        double mean = total / steps;
        assertTrue(biggest < Math.max(0.01, mean * 12),
                "the keeper's idle has a cut in it: one frame moved " + biggest
                        + " m against a mean of " + mean);
    }

    // --- through the screen a player actually uses ------------------------------------------

    /**
     * Standing at a counter, in the real scene: the highlight names the post,
     * <kbd>E</kbd> opens the panel, and a click on the shelf buys.
     *
     * <p>This is the one test that goes through {@code WatchScene}, and it is
     * here because the shop is the first thing in this game whose verb is a
     * <em>screen</em> rather than a message: the reach key opens a panel instead
     * of sending "use", and nothing below the scene can tell whether that
     * branch is wired up.
     */
    @Test
    void walkingUpToACounterOpensTheShopAndBuyingWorks(@TempDir Path dir) {
        try (Walk walk = Walk.atACounter(dir)) {
            WatchGame.Pickable target = walk.walk.inReach();
            assertNotNull(target, "standing at a counter, nothing is in reach");
            assertEquals(WatchGame.Pickable.Kind.SHOP, target.kind(),
                    "what is in reach at a trading post is a " + target.kind());
            assertTrue(target.prompt().startsWith("Trade at"), target.prompt());

            assertEquals("none", walk.walk.panelName());
            walk.press(KeyEvent.VK_E);
            assertEquals("shop", walk.walk.panelName(),
                    "the reach key at a counter did not open the shop");

            Shops.Shop shop = walk.game.shopAt(1);
            assertNotNull(shop);
            Trading.Offer offer = shop.stock().get(0);
            assertEquals(offer.item(), walk.walk.panelCursor(),
                    "the panel opened on something that is not the first thing on the shelf");

            earn(walk.game, 1, offer.price() + 5);
            int before = walk.game.guide().points();
            int carried = walk.game.player(1).satchel().count(offer.item());
            walk.press(KeyEvent.VK_ENTER);

            assertEquals(carried + offer.quantity(),
                    walk.game.player(1).satchel().count(offer.item()),
                    "buying from the panel handed nothing over");
            assertEquals(before - offer.price(), walk.game.guide().points());

            // …and the panel draws, over a world with a shop and a keeper in it.
            RecordingTarget frame = new RecordingTarget(800, 480);
            walk.scenes.render(frame, 0f);
            assertFalse(frame.commands().isEmpty(), "the shop screen drew nothing");
        }
    }

    /**
     * The reach key closes the shop as well as opening it, and does not reopen
     * it on the way out.
     *
     * <p>Worth its own test because <kbd>E</kbd> is doing two jobs on one key:
     * {@code act} opens the panel when there is no panel, and {@code updateShop}
     * closes it when there is. A press that reached both would open and close on
     * the same frame and the shop would appear to be broken.
     */
    @Test
    void theReachKeyClosesTheShopAgain(@TempDir Path dir) {
        try (Walk walk = Walk.atACounter(dir)) {
            walk.press(KeyEvent.VK_E);
            assertEquals("shop", walk.walk.panelName());
            walk.press(KeyEvent.VK_E);
            assertEquals("none", walk.walk.panelName(), "the shop would not close again");
            walk.press(KeyEvent.VK_E);
            assertEquals("shop", walk.walk.panelName(), "and would not reopen");
            walk.press(KeyEvent.VK_ESCAPE);
            assertEquals("none", walk.walk.panelName(), "Escape did not close the shop");
        }
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
            // Put the walker at the counter before the scene opens: the scene
            // takes its own position from the view on the way in, which is how
            // resuming a save works and therefore the honest way to start a
            // test anywhere but the world origin.
            stand(game, 1, shop.counterX(), shop.counterY());
            session = WatchSession.solo(game);
            session.setSelfId(1);
            walk.adopt(session, store);
            scenes.setScene(WatchScene.NAME);
            for (int i = 0; i < 6; i++) step();
        }

        /**
         * A world with a trading post near enough to the origin to open on.
         *
         * <p>Searched for rather than fixed, the way {@code JumpTest} searches
         * for dry land: which seed puts a post within a short walk is a property
         * of the generator, and a hard-coded one would quietly stop being about
         * shops the first time the terrain changed.
         */
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
