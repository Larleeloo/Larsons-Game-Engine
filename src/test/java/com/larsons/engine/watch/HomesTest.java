package com.larsons.engine.watch;

import com.larsons.engine.watch.home.HouseKit;
import com.larsons.engine.watch.home.HousePart;
import com.larsons.engine.watch.home.HousePlan;
import com.larsons.engine.watch.home.Homestead;
import com.larsons.engine.watch.net.WatchProto;
import com.larsons.engine.watch.world.TreeGenome;
import com.larsons.engine.watch.world.TreeInstance;
import com.larsons.engine.watch.world.TreeSpecies;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Houses: the catalogue, the carpentry, and the fact that you can walk into one.
 *
 * <p>Six claims, and they are the six things the feature was asked to be.
 *
 * <ol>
 *   <li><b>They are bought, not built.</b> A house costs points out of the
 *       shared book, it goes up complete, and it can be taken down again for
 *       half of what it cost.</li>
 *   <li><b>Bigger costs more.</b> Every step up the price ladder is more
 *       ground, more floors and more parts — structurally, not as a label.</li>
 *   <li><b>They are put down anywhere.</b> In front of the buyer, at any of
 *       eight turns, on any ground that will take one, and never inside
 *       somebody else's house.</li>
 *   <li><b>Walls are solid and floors carry you.</b> The same list of boxes
 *       the mesher draws is the list the walk collides with, so there is no
 *       such thing as a wall you can see and walk through.</li>
 *   <li><b>Treehouses come with the ladder up to the tree.</b> Every one of
 *       them, from the ground to the deck, without the player buying it.</li>
 *   <li><b>They survive the wire and the save.</b> A mansion is a plan key and
 *       eight numbers, and both ends build the same three hundred boxes from
 *       it.</li>
 * </ol>
 */
@Timeout(300)
class HomesTest {

    private static final long SEED = 20260905L;

    private static WatchGame game() {
        WatchGame game = new WatchGame(WatchGame.Config.hosted("Homes", SEED));
        game.join(1, "Kara");
        return game;
    }

    private static void stand(WatchGame game, int id, double x, double y) {
        game.move(id, x, y, game.field().heightAt(x, y), 0, 0, false, 0.05);
    }

    /**
     * Put a house up somewhere near, and hand it back.
     *
     * <p>A run of spots, because a house needs ground that does not fall away
     * under it — which is a rule this file tests elsewhere and does not want to
     * be at the mercy of everywhere else.
     */
    private static Homestead.Home house(WatchGame game, HousePlan plan) {
        game.guide().reward(plan.price());
        for (int i = 0; i < 24; i++) {
            stand(game, 1, i * 9.0, i * 5.0);
            Homestead.Outcome receipt = game.buyHome(1, plan, 0);
            if (receipt.done()) return receipt.home();
        }
        return null;
    }

    // --- 1. bought, not built -----------------------------------------------------------

    /**
     * The guide pays, and the house goes up complete.
     *
     * <p>Complete is the word doing the work: what comes back is one record,
     * and everything a player will walk around inside is derived from its plan.
     */
    @Test
    void aHouseIsBoughtWithPointsAndArrivesFinished() {
        WatchGame game = game();
        Homestead.Home home = house(game, HousePlan.CABIN);
        assertNotNull(home, "nowhere would take a cabin");
        assertEquals(1, game.homes().size());
        assertEquals(0, game.guide().points(), "the points were not handed over");

        List<HousePart> parts = game.homes().partsOf(home);
        assertFalse(parts.isEmpty(), "the cabin is made of nothing");
        Map<HousePart.Role, Integer> roles = tally(parts);
        // The three that make it a house rather than a hut: something to stand
        // on, something to keep the wind off, and a way in through it.
        assertTrue(roles.getOrDefault(HousePart.Role.FLOOR, 0) > 0, "no floor");
        assertTrue(roles.getOrDefault(HousePart.Role.WALL, 0) > 3, "no walls");
        assertTrue(roles.getOrDefault(HousePart.Role.ROOF, 0) > 0, "no roof");
        assertTrue(roles.getOrDefault(HousePart.Role.STAIR, 0) > 0,
                "no step up to the front door");
    }

    /** …and comes down again, for half of it back. */
    @Test
    void aHouseCanBeTakenDownForHalfOfWhatItCost() {
        WatchGame game = game();
        Homestead.Home home = house(game, HousePlan.FORT);
        assertNotNull(home);
        assertEquals(0, game.guide().points());

        stand(game, 1, home.x() + 400, home.y());
        Homestead.Outcome away = game.packUp(1);
        assertFalse(away.done(), "a fort was packed up from four hundred metres away");
        assertNotNull(away.line(), "and it was refused without saying why");
        assertEquals(1, game.homes().size());

        stand(game, 1, home.x(), home.y());
        assertTrue(game.packUp(1).done(), "the fort would not come down");
        assertEquals(0, game.homes().size());
        assertEquals(HousePlan.FORT.price() / 2, game.guide().points(),
                "packing up did not put half the price back");
        assertEquals(0, game.guide().earned() - game.guide().spent()
                - HousePlan.FORT.price() / 2,
                "the refund was booked as animals seen rather than as money back");
    }

    // --- 2. bigger costs more ------------------------------------------------------------

    /**
     * The catalogue's whole claim, held to the wall.
     *
     * <p>Volume across the whole list, and part count within each family —
     * because a treehouse is dearer than a ground house of the same size for
     * the tree, and the honest promise is that money buys more <em>house</em>
     * rather than more of some abstract score.
     */
    @Test
    void payingMoreBuysMoreHouse() {
        HousePlan previous = null;
        for (HousePlan plan : HousePlan.all()) {
            if (previous != null) {
                assertTrue(plan.price() >= previous.price(),
                        "the catalogue is not in price order at " + plan);
                assertTrue(plan.volume() >= previous.volume(),
                        plan + " costs more than " + previous + " and is smaller");
            }
            previous = plan;
        }
        for (List<HousePlan> family : List.of(HousePlan.onGround(), HousePlan.inTrees())) {
            HousePlan last = null;
            for (HousePlan plan : family) {
                int parts = HouseKit.parts(plan).size();
                if (last != null) {
                    assertTrue(parts >= HouseKit.parts(last).size(),
                            plan + " costs more than " + last + " and is made of fewer "
                                    + "parts (" + parts + " against "
                                    + HouseKit.parts(last).size() + ")");
                    assertTrue(plan.storeys() >= last.storeys(),
                            plan + " has fewer floors than " + last);
                }
                last = plan;
            }
        }
    }

    /**
     * The cheapest is a fort and the dearest is a mansion, and the difference
     * shows in the parts rather than in the description.
     */
    @Test
    void theTopOfTheLadderIsAnotherKindOfBuildingEntirely() {
        List<HousePart> simple = HouseKit.parts(HousePlan.all().get(0));
        List<HousePart> grand = HouseKit.parts(HousePlan.MANSION);
        assertTrue(grand.size() > simple.size() * 5,
                "the dearest house is not meaningfully more intricate than the cheapest: "
                        + grand.size() + " parts against " + simple.size());

        Map<HousePart.Role, Integer> roles = tally(grand);
        // Things only the top of the ladder has: glazing, staircases, a study
        // wall and furniture.
        assertTrue(roles.getOrDefault(HousePart.Role.GLASS, 0) > 0, "no glazed windows");
        assertTrue(roles.getOrDefault(HousePart.Role.STAIR, 0) > 10, "no staircases");
        assertTrue(roles.getOrDefault(HousePart.Role.FITTING, 0) > 0, "no furniture");
        assertEquals(1, roles.getOrDefault(HousePart.Role.BOARD, 0),
                "the grandest house has no study to pin maps in");
        assertNotNull(HouseKit.boardOf(HousePlan.MANSION));
        assertNull(HouseKit.boardOf(HousePlan.all().get(0)),
                "the cheapest house came with a study");
    }

    // --- 3. put down anywhere ------------------------------------------------------------

    @Test
    void aHouseGoesUpInFrontOfTheBuyerAtWhicheverTurnTheyChose() {
        int placed = 0;
        for (int turn = 0; turn < Homestead.TURNS; turn++) {
            WatchGame game = game();
            game.guide().reward(HousePlan.CABIN.price());
            stand(game, 1, 72, 40);
            Homestead.Outcome receipt = game.buyHome(1, HousePlan.CABIN, turn);
            if (!receipt.done()) continue;
            placed++;
            // The turn is measured from "facing the buyer", so turn 0 puts the
            // front door on the side the player is looking at. See
            // WatchGame.facingBuyer.
            assertEquals(WatchGame.facingBuyer(game.player(1).yaw(), turn),
                    receipt.home().turn(), "the house faces the wrong way");
            if (turn == 0) {
                // …which means the buyer is out in front of its own front door.
                assertTrue(receipt.home().alongOf(game.player(1).x(), game.player(1).y())
                                > HousePlan.CABIN.halfAlong(),
                        "the front door does not face the person who bought it");
            }
            // Snapped to the grid, so two houses laid side by side actually meet.
            assertEquals(receipt.home().x(), Homestead.snap(receipt.home().x()), 1e-9);
            assertEquals(receipt.home().y(), Homestead.snap(receipt.home().y()), 1e-9);
        }
        assertTrue(placed > 0, "not one of the eight turns put a cabin down");
    }

    /** Nothing goes up in a lake, and nothing goes up inside anything else. */
    @Test
    void aHouseIsRefusedWithAReasonRatherThanInSilence() {
        WatchGame game = game();
        Homestead.Home home = house(game, HousePlan.CABIN);
        assertNotNull(home);

        // Standing where the first one is, facing the same way: the second lands
        // on the first.
        stand(game, 1, home.x() - 6, home.y());
        game.guide().reward(HousePlan.CABIN.price());
        Homestead.Outcome clash = game.buyHome(1, HousePlan.CABIN, 0);
        assertFalse(clash.done());
        assertNotNull(clash.line(), "a refusal with nothing to say");
        assertEquals(HousePlan.CABIN.price(), game.guide().points(),
                "a refused house was charged for");

        // A plan that does not exist is refused without a line, because nothing
        // a player did caused it.
        Homestead.Outcome nothing = game.buyHome(1, null, 0);
        assertFalse(nothing.done());
        assertNull(nothing.line());
    }

    // --- 4. walls are solid, floors carry -------------------------------------------------

    /**
     * The claim that makes a house a place rather than a picture.
     *
     * <p>Asked of the model rather than of the screen: the same list the
     * renderer walks is the one these three queries walk, which is why they can
     * be checked without drawing anything.
     */
    @Test
    void wallsAreSolidAndFloorsCarryYouAndTheDoorwayIsNeither() {
        Homestead homes = new Homestead();
        Homestead.Home home = homes.place(HousePlan.CABIN, 0, 0, 10, 0, 0, 0, "Kara", 1);
        double hA = HousePlan.CABIN.halfAlong();

        // Standing in the middle of the room, on its floor rather than on the
        // hillside the ground pretends to be.
        assertEquals(10 + HouseKit.DECK, homes.standOn(0, 0, 12, 10), 1e-9,
                "the floor of the cabin is not what you stand on inside it");
        assertEquals(4.0, homes.standOn(80, 80, 12, 4.0), 1e-9,
                "a walker a long way from any house is not standing on the ground");
        // …and not on the floor above the one you are on.
        assertEquals(10, homes.standOn(0, 0, 10 + 0.1, 10), 1e-9,
                "a floor above a walker's head is what they are standing on");

        // The back wall stops you; the middle of the room does not.
        double wallAlong = -hA + HouseKit.WALL_THICK / 2;
        assertTrue(solid(homes, home, wallAlong, 0, 10.4),
                "the back wall of a cabin can be walked through");
        assertFalse(solid(homes, home, 0, 0, 10.4), "the middle of the room is solid");

        // The doorway is a hole in the front wall, dead centre, and wide enough
        // to walk through without lining up on it.
        double doorAlong = hA - HouseKit.WALL_THICK / 2;
        assertFalse(solid(homes, home, doorAlong, 0, 10.4),
                "the front door of a cabin is bricked up");
        double shoulder = HouseKit.DOOR_WIDTH / 2 - Homestead.BODY_RADIUS - 0.02;
        assertFalse(solid(homes, home, doorAlong, shoulder, 10.4),
                "a walker's shoulder catches the doorframe");
        assertFalse(solid(homes, home, doorAlong, -shoulder, 10.4),
                "a walker's other shoulder catches the doorframe");
        // …and the wall either side of it is not a hole.
        assertTrue(solid(homes, home, doorAlong, HousePlan.CABIN.halfAcross() - 0.4, 10.4),
                "the wall beside the front door can be walked through");
    }

    /** Whether a walker standing at a point in the house's frame is in timber. */
    private static boolean solid(Homestead homes, Homestead.Home home,
                                 double along, double across, double footZ) {
        double x = home.worldX(along, across), y = home.worldY(along, across);
        return homes.solidAt(x, y, footZ, footZ + Homestead.BODY_HEIGHT);
    }

    /**
     * A staircase is a floor at twenty centimetres a time.
     *
     * <p>Which is the whole reason the walk needs no case for stairs: every
     * tread is somewhere to stand, and each is inside the step a walker takes
     * without jumping.
     */
    @Test
    void aStaircaseIsARunOfFloorsAWalkerCanStepUp() {
        List<HousePart> treads = new ArrayList<>();
        for (HousePart part : HouseKit.parts(HousePlan.LODGE)) {
            if (part.role() == HousePart.Role.STAIR) treads.add(part);
        }
        assertTrue(treads.size() > 8, "a two-floor lodge has no staircase in it");
        treads.sort(java.util.Comparator.comparingDouble(HousePart::top));
        for (int i = 1; i < treads.size(); i++) {
            double rise = treads.get(i).top() - treads.get(i - 1).top();
            assertTrue(rise < 0.62,
                    "a step of " + rise + " m is higher than a walker can step");
        }
        // The flight arrives at the floor above, give or take a tread.
        double top = treads.get(treads.size() - 1).top();
        assertTrue(Math.abs(top - (HouseKit.DECK + HousePlan.STOREY)) < 0.3,
                "the stairs of a lodge do not arrive at its first floor: " + top);
    }

    /**
     * Every floor above the ground has a hole in it where the way up arrives.
     *
     * <p>Not a nicety: a staircase that arrived at a continuous slab of boards
     * is a staircase you climb through the ceiling.
     */
    @Test
    void theFloorAboveAStairHasAHoleInIt() {
        for (HousePlan plan : HousePlan.all()) {
            if (plan.storeys() < 2) continue;
            double upstairs = HouseKit.DECK + HousePlan.STOREY;
            List<HousePart> slabs = new ArrayList<>();
            for (HousePart part : HouseKit.parts(plan)) {
                if (part.role() == HousePart.Role.FLOOR
                        && Math.abs(part.top() - upstairs) < 0.01) {
                    slabs.add(part);
                }
            }
            assertTrue(slabs.size() > 1,
                    plan + "'s first floor is one unbroken slab, so its stair goes "
                            + "through the ceiling");
        }
    }

    // --- 5. the ladder up the tree --------------------------------------------------------

    /**
     * The promise in the brief: a treehouse comes with the ladder up to the tree
     * it is in.
     *
     * <p>Every plan in the family, and the ladder has to actually reach — from
     * within a step of the ground to within a step of the deck.
     */
    @Test
    void everyTreehouseComesWithALadderThatReachesTheGround() {
        assertFalse(HousePlan.inTrees().isEmpty());
        for (HousePlan plan : HousePlan.inTrees()) {
            Homestead homes = new Homestead();
            double drop = 7.5;
            Homestead.Home home = homes.place(plan, 0, 0, 20, 0, 99, drop, "Kara", 1);
            HousePart ladder = null;
            for (HousePart part : homes.partsOf(home)) {
                if (part.role() == HousePart.Role.LADDER) ladder = part;
            }
            assertNotNull(ladder, plan + " is up a tree with no way up to it");
            assertTrue(ladder.bottom() <= -drop + 0.05,
                    plan + "'s ladder stops " + (ladder.bottom() + drop)
                            + " m short of the ground");
            assertTrue(ladder.top() >= HouseKit.DECK - 0.1,
                    plan + "'s ladder stops short of its own deck");

            // …and it is a ladder you can take hold of from the ground, and ride
            // to the deck.
            double x = home.worldX(ladder.along(), ladder.across());
            double y = home.worldY(ladder.along(), ladder.across());
            Homestead.Climb climb = homes.climbAt(x, y, 20 - drop, 20 - drop + 1.8);
            assertNotNull(climb, plan + "'s ladder cannot be taken hold of at its foot");
            assertEquals(20 - drop, climb.bottom(), 0.06);
            assertTrue(climb.top() >= 20 + HouseKit.DECK - 0.1);

            // Getting off at the top is standing on the landing, which is what
            // stops a ladder ending in mid-air.
            assertTrue(homes.standOn(x, y, climb.top() + 0.62, 20 - drop)
                            > 20 + HouseKit.DECK - 0.4,
                    plan + "'s ladder arrives nowhere");
        }
    }

    /** A treehouse goes up a tree, and refuses to go up when there is not one. */
    @Test
    void aTreehouseNeedsATree() {
        // Somewhere with nothing big enough within reach, hunted for rather
        // than assumed: a wood is mostly trees, and which square metre of one
        // has none within six metres is the generator's business.
        WatchGame bare = game();
        String refusal = null;
        for (int i = 0; i < 80 && refusal == null; i++) {
            stand(bare, 1, i * 37.0, -i * 23.0);
            bare.guide().reward(HousePlan.TREE_PERCH.price());
            Homestead.Outcome outcome = bare.buyHome(1, HousePlan.TREE_PERCH, 0);
            if (outcome.done()) bare.homes().remove(outcome.home().id());
            else if (outcome.line().contains("tree")) refusal = outcome.line();
        }
        assertNotNull(refusal,
                "a perch went up in eighty places without ever wanting a tree");

        // …and with an oak of our own grown in front of us, it goes up in that.
        WatchGame planted = game();
        stand(planted, 1, 0, 0);
        WatchPlayer me = planted.player(1);
        double tx = me.x(), ty = me.y() - HousePlan.TREE_PERCH.standOff();
        TreeInstance tree = planted.grove().plant(TreeSpecies.OAK, tx, ty,
                planted.field().heightAt(tx, ty), TreeGenome.average(), "Kara");
        assertNotNull(tree);
        for (int i = 0; i < 4000 && tree.height() < 6; i++) planted.grove().advance(24);
        assertTrue(tree.height() >= 4,
                "a planted oak never grew tall enough to hold a perch: " + tree.height());

        planted.guide().reward(HousePlan.TREE_PERCH.price());
        Homestead.Outcome up = planted.buyHome(1, HousePlan.TREE_PERCH, 0);
        assertTrue(up.done(), "a perch would not go up a planted oak: " + up.line());
        assertTrue(up.home().inTree(), "the perch is not fixed to anything");
        assertEquals(tree.id(), up.home().treeId());
        assertTrue(up.home().drop() > 1,
                "the perch is at ground level, so its ladder has nothing to climb");
        assertEquals(0, planted.guide().points(), "the perch was not paid for");
    }

    // --- 6. the wire and the save ---------------------------------------------------------

    /**
     * A mansion crosses the wire as a key and eight numbers, and the far end
     * builds the same house out of it.
     *
     * <p>This is the whole reason houses are bought rather than built: the old
     * building system had to send every plank somebody nailed down.
     */
    @Test
    void aHouseCrossesTheWireAsAKeyAndComesBackWhole() {
        Homestead here = new Homestead();
        Homestead.Home mansion = here.place(HousePlan.MANSION, 12, -8, 30, 3, 0, 1.4,
                "Kara", 1234);
        here.place(HousePlan.TREEHOUSE, 90, 90, 44, 1, 77, 11.5, "Sam", 4321);

        Map<String, Object> message = WatchProto.world(Map.of(), Map.of(),
                here.toMap(), Map.of(), Map.of(), Map.of(), Map.of(), List.of());
        Homestead there = new Homestead();
        there.load(WatchJson.map(message, "homes"));

        assertEquals(2, there.size(), "the houses did not cross the wire");
        Homestead.Home theirs = there.byId(mansion.id());
        assertNotNull(theirs);
        assertEquals(HousePlan.MANSION, theirs.plan());
        assertEquals(12, theirs.x(), 1e-9);
        assertEquals(-8, theirs.y(), 1e-9);
        assertEquals(30, theirs.z(), 1e-9);
        assertEquals(3, theirs.turn());
        assertEquals(1.4, theirs.drop(), 1e-9);
        assertEquals("Kara", theirs.boughtBy());
        assertEquals(here.partsOf(mansion).size(), there.partsOf(theirs).size(),
                "the two ends built different mansions out of the same key");

        Homestead.Home tree = there.byId(mansion.id() + 1);
        assertNotNull(tree);
        assertTrue(tree.inTree() && tree.treeId() == 77,
                "the treehouse forgot which tree it was in");
        assertEquals(1, there.inTree(77).size());
    }

    /** A house is in the save, and the save is what a walk is resumed from. */
    @Test
    void aHouseSurvivesBeingSavedAndReopened() {
        WatchGame game = game();
        Homestead.Home home = house(game, HousePlan.LODGE);
        assertNotNull(home, "nowhere would take a lodge");

        WatchGame reopened = new WatchGame(WatchGame.Config.hosted("Homes", SEED));
        reopened.load(game.toMap());
        assertEquals(1, reopened.homes().size(), "the lodge did not survive the save");
        Homestead.Home back = reopened.homes().byId(home.id());
        assertNotNull(back);
        assertEquals(home.plan(), back.plan());
        assertEquals(home.x(), back.x(), 1e-9);
        assertEquals(home.turn(), back.turn());
        // And the study wall came back with it, which is what a map is pinned
        // to. The board is twinned with the house by its id, so a house that
        // came back without its board would be a board nobody could find.
        Cartography.Board board = null;
        for (Cartography.Board candidate : reopened.maps().boards()) {
            if (candidate.placementId() == home.id()) board = candidate;
        }
        assertNotNull(board, "the lodge's map board did not survive the save");
        assertTrue(board.distanceTo(back.x(), back.y()) < back.plan().radius(),
                "the board came back somewhere other than in the house it hangs in");
    }

    // --- and through the walk itself -----------------------------------------------------

    /**
     * The whole thing, driven through the real screen.
     *
     * <p>Everything above is asked of the model. This is asked of the walk: a
     * house is bought, the walker holds W, and the two claims that make a house
     * a place either hold or they do not. They are checked <em>every frame</em>
     * rather than at the end, because "ended up outside the wall" is also what a
     * walker who tunnelled clean through and out the far side looks like.
     */
    @Test
    void aWalkerWalksInThroughTheDoorAndNotThroughTheWall(@TempDir Path dir) {
        // Facing the front: the house is bought straight ahead, so W walks at
        // its own front door, up its step and onto its floor.
        try (Walk walk = new Walk(dir)) {
            Homestead.Home home = walk.buy(HousePlan.CABIN, 0);
            assertNotNull(home, "nowhere the walk could reach would take a cabin");
            walk.holdForward(150);
            assertFalse(walk.everInsideTimber, "the walk walked into its own wall");
            assertTrue(walk.game.homes().standOn(walk.px(), walk.py(),
                            walk.pz() + 0.62, -1000) > home.z() + HouseKit.DECK - 0.2,
                    "the walker did not end up standing on the cabin's floor");
        }

        // The same house turned back to front, so W walks at a blank wall
        // instead. It has to stop.
        try (Walk walk = new Walk(dir)) {
            Homestead.Home home = walk.buy(HousePlan.CABIN, Homestead.TURNS / 2);
            assertNotNull(home);
            walk.holdForward(150);
            assertFalse(walk.everInsideTimber, "the walk walked into its own wall");
            // Stopped outside it, and stopped <em>at</em> it: a walker who is
            // still six metres off has proved nothing about the wall.
            double along = home.alongOf(walk.px(), walk.py());
            double face = -home.plan().halfAlong();
            assertTrue(along < face, "the walker went through the back wall: "
                    + (along - face) + " m past it");
            assertTrue(along > face - Homestead.BODY_RADIUS - 0.5,
                    "the walker never reached the back wall to be stopped by it: "
                            + (face - along) + " m short");
        }
    }

    /**
     * A drive of the real screen, cut down to what these two tests need.
     *
     * <p>{@code MapsTest} has its own; the two are deliberately not shared,
     * because a harness that has to serve every test in the package grows a
     * method for each of them and stops being readable by either.
     */
    private static final class Walk implements AutoCloseable {
        final com.larsons.engine.demo.WatchScene scene;
        final com.larsons.engine.scene.SceneManager scenes;
        final com.larsons.engine.input.InputManager input =
                new com.larsons.engine.input.InputManager();
        final com.larsons.engine.watch.net.WatchSession session;
        final WatchGame game;

        /** Whether the walker was ever standing inside solid timber. */
        boolean everInsideTimber;

        Walk(Path dir) {
            com.larsons.engine.config.GameContext ctx =
                    new com.larsons.engine.config.GameContext(null,
                            new com.larsons.engine.config.GameTypeStore(dir.toString()));
            scene = new com.larsons.engine.demo.WatchScene(ctx);
            scenes = new com.larsons.engine.scene.SceneManager();
            scenes.setViewport(960, 640);
            scenes.register(com.larsons.engine.demo.WatchLobbyScene.NAME,
                    new com.larsons.engine.demo.WatchLobbyScene(ctx,
                            new WatchStore(dir.resolve("w").toString())));
            scenes.register(com.larsons.engine.demo.WatchScene.NAME, scene);

            game = new WatchGame(new WatchGame.Config(SEED, "Walking Home", 1));
            game.join(1, "Kara");
            session = com.larsons.engine.watch.net.WatchSession.solo(game);
            session.setSelfId(1);
            scene.adopt(session, new WatchStore(dir.resolve("w").toString()));
            scenes.setScene(com.larsons.engine.demo.WatchScene.NAME);
            tick(4);
        }

        double px() { return game.player(1).x(); }

        double py() { return game.player(1).y(); }

        double pz() { return game.player(1).z(); }

        void tick(int frames) {
            for (int i = 0; i < frames; i++) {
                input.newFrame();
                scenes.update(1.0 / 60, input);
            }
        }

        /**
         * Move the walker about until somewhere will take a house, and buy one.
         *
         * <p>The walker is moved by the one mechanism the screen already has for
         * being moved by the host — a respawn — and the house lands in front of
         * whichever way they happen to be facing, which is what makes holding W
         * afterwards walk straight at it.
         */
        Homestead.Home buy(HousePlan plan, int turn) {
            game.guide().reward(plan.price() * 40);
            for (int i = 0; i < 40; i++) {
                double x = i * 11.0, y = i * 7.0;
                game.player(1).respawnAt(x, y, game.field().heightAt(x, y));
                tick(6);
                Homestead.Outcome receipt = game.buyHome(1, plan, turn);
                if (!receipt.done()) continue;
                // Level ground, specifically: a house whose site falls away
                // stands on piers, and walking at a house on piers is walking
                // <em>under</em> it. That is correct behaviour and it is not
                // what these two tests are about.
                if (receipt.home().drop() < 0.4) return receipt.home();
                game.homes().remove(receipt.home().id());
            }
            return null;
        }

        /** Hold W, watching every frame for a walker who is inside the timber. */
        void holdForward(int frames) {
            Homestead homes = game.homes();
            for (int i = 0; i < frames; i++) {
                input.newFrame();
                input.pressKey(java.awt.event.KeyEvent.VK_W, 0);
                scenes.update(1.0 / 60, input);
                if (homes.solidAt(px(), py(), pz() + 0.12,
                        pz() + Homestead.BODY_HEIGHT)) {
                    everInsideTimber = true;
                }
            }
            input.newFrame();
            input.releaseKey(java.awt.event.KeyEvent.VK_W);
            scenes.update(1.0 / 60, input);
        }

        @Override public void close() { session.close(); }
    }

    private static Map<HousePart.Role, Integer> tally(List<HousePart> parts) {
        Map<HousePart.Role, Integer> out = new EnumMap<>(HousePart.Role.class);
        for (HousePart part : parts) out.merge(part.role(), 1, Integer::sum);
        return out;
    }
}
