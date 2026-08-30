package com.larsons.engine.watch;

import com.larsons.engine.config.GameContext;
import com.larsons.engine.config.GameTypeStore;
import com.larsons.engine.demo.MapPanel;
import com.larsons.engine.demo.WatchLobbyScene;
import com.larsons.engine.demo.WatchScene;
import com.larsons.engine.graphics.draw.RecordingTarget;
import com.larsons.engine.input.InputManager;
import com.larsons.engine.scene.SceneManager;
import com.larsons.engine.watch.build.BuildPiece;
import com.larsons.engine.watch.net.WatchProto;
import com.larsons.engine.watch.net.WatchSession;
import com.larsons.engine.watch.render.BoardImage;
import com.larsons.engine.watch.render.ChartImage;
import com.larsons.engine.watch.render.MapInk;
import com.larsons.engine.watch.world.TerrainField;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Maps: the paper, the pen, the party on it, and the board they join up on.
 *
 * <p>Seven claims, and they are the seven things a map was asked to be.
 *
 * <ol>
 *   <li><b>It is as wide as you can see.</b> A map drawn on a machine with a
 *       long ring is a bigger map than one drawn on a short one, and wherever
 *       you were standing when you drew it, everything within your render
 *       distance is inside its square.</li>
 *   <li><b>It arrives finished.</b> The ground is painted from the seed and the
 *       icons are collected at the moment it is drawn — there is nothing to
 *       walk off and nothing to fill in.</li>
 *   <li><b>It has the shops on it</b>, and everything else a country has.</li>
 *   <li><b>You can write on it</b>, and what you wrote is what everybody else
 *       sees, survives a save, and can be rubbed out again.</li>
 *   <li><b>It is in your satchel</b> and it can be renamed there.</li>
 *   <li><b>It always shows everybody</b>, including the ones who have walked
 *       off it — pinned to the border, the way Minecraft does it.</li>
 *   <li><b>Maps combine.</b> Two maps of neighbouring country pinned to one
 *       board are one larger map, with nothing for the player to line up.</li>
 * </ol>
 *
 * <p>And an eighth, which is the one that will be deleted rather than kept:
 * every one of the verbs above is refused to anybody not in debug mode.
 */
@Timeout(300)
class MapsTest {

    private static final long SEED = 20260829L;

    private static final int WIDTH = 1000, HEIGHT = 680;

    /** A solo walk with debug mode already on, which is where maps live for now. */
    private static WatchGame walking() {
        WatchGame game = new WatchGame(WatchGame.Config.hosted("Cartography", SEED));
        WatchPlayer me = game.join(1, "Kara");
        assertNotNull(me);
        game.debug(1, Debug.CODE);
        assertTrue(me.debugging(), "the code did not take on this player's own walk");
        return game;
    }

    private static void stand(WatchGame game, int id, double x, double y) {
        game.move(id, x, y, game.field().heightAt(x, y), 0, 0, false, 0.05);
    }

    // --- 1. as wide as you can see ------------------------------------------------------

    /**
     * The map covers the render distance in every direction from where it was
     * drawn — which is the promise, and is not the same as "the map is the
     * render distance across".
     *
     * <p>The centre snaps to a grid so maps tile ({@link Chart#snap}), so the
     * player is not in the middle of their own map. The size is chosen to
     * survive that: sweeping a player across a whole grid cell and drawing a map
     * at every step, the worst case still has the full circle inside the square.
     */
    @Test
    void aMapSpansTheRenderDistanceFromWhereverItWasDrawn() {
        WatchGame game = walking();
        double reach = 320;
        double radius = Chart.radiusFor(reach);
        double worst = Double.MAX_VALUE;
        for (int i = 0; i < 24; i++) {
            // Right across one grid cell, diagonally, so both axes are swept.
            double at = i * radius / 23.0;
            stand(game, 1, at, at);
            Chart chart = game.drawMap(1, reach);
            assertNotNull(chart, "a walker in debug mode could not draw a map");
            assertTrue(chart.covers(at, at),
                    "the map does not contain the person who drew it");
            // How far the nearest edge is from where they stood.
            double margin = Math.min(
                    Math.min(at - chart.minX(), chart.maxX() - at),
                    Math.min(at - chart.minY(), chart.maxY() - at));
            worst = Math.min(worst, margin);
        }
        assertTrue(worst >= reach,
                "somewhere in the grid cell the map's nearest edge was only " + worst
                        + " m away, inside a render distance of " + reach);
    }

    /** A longer ring draws a bigger map; a shorter one draws a smaller. */
    @Test
    void theMapIsAsBigAsTheMachineDrawingIt() {
        WatchGame game = walking();
        Chart small = game.drawMap(1, 96);
        Chart large = game.drawMap(1, 640);
        assertNotNull(small);
        assertNotNull(large);
        assertTrue(large.radius() > small.radius(),
                "a machine that can see seven times as far drew the same map: "
                        + small.radius() + " vs " + large.radius());
        // …and both landed on the ladder, which is what makes them tile.
        assertTrue(onTheLadder(small.radius()), "small map is off the ladder");
        assertTrue(onTheLadder(large.radius()), "large map is off the ladder");
        assertTrue(large.radius() % small.radius() == 0,
                "two map sizes that are not whole multiples cannot tile: "
                        + small.radius() + ", " + large.radius());
    }

    private static boolean onTheLadder(double radius) {
        for (double step : Chart.RADII) {
            if (step == radius) return true;
        }
        return false;
    }

    /** A map is never larger than the guard, whatever a client claims it can see. */
    @Test
    void aClientCannotAskForAMapOfTheWholePlane() {
        WatchGame game = walking();
        Chart absurd = game.drawMap(1, 1e9);
        assertNotNull(absurd);
        assertEquals(Chart.MAX_RADIUS, absurd.radius(),
                "a client claiming a million-metre view got the map it asked for");
    }

    // --- 2. it arrives finished --------------------------------------------------------

    /**
     * The picture is a pure function of the seed, so two machines that have
     * never exchanged a byte paint the same paper — pixel for pixel.
     *
     * <p>This is what makes a map four numbers on the wire rather than an image,
     * and it is what makes the join between two maps on a board invisible.
     */
    @Test
    void thePaperIsPaintedFromTheSeedAndIsTheSameEverywhere() {
        ChartImage.invalidate();
        BufferedImage mine = ChartImage.bake(new TerrainField(SEED), 0, 0, 256);
        ChartImage.invalidate();
        BufferedImage theirs = ChartImage.bake(new TerrainField(SEED), 0, 0, 256);
        assertNotNull(mine);
        assertEquals(mine.getWidth(), theirs.getWidth());
        for (int y = 0; y < mine.getHeight(); y += 7) {
            for (int x = 0; x < mine.getWidth(); x += 7) {
                assertEquals(mine.getRGB(x, y), theirs.getRGB(x, y),
                        "two copies of the same world disagree about the ground at "
                                + x + ", " + y);
            }
        }

        // A different world is a different picture, which is the other half of
        // "painted from the seed" and the half a constant would also pass.
        ChartImage.invalidate();
        BufferedImage elsewhere = ChartImage.bake(new TerrainField(SEED + 1), 0, 0, 256);
        boolean anyDifference = false;
        for (int y = 0; y < mine.getHeight() && !anyDifference; y += 3) {
            for (int x = 0; x < mine.getWidth(); x += 3) {
                if (mine.getRGB(x, y) != elsewhere.getRGB(x, y)) {
                    anyDifference = true;
                    break;
                }
            }
        }
        assertTrue(anyDifference, "two different worlds paint the same map");
    }

    /** The paper has more than one colour on it — it is a country, not a wash. */
    @Test
    void thePaperIsGroundRatherThanAFlatColour() {
        ChartImage.invalidate();
        BufferedImage paper = ChartImage.bake(new TerrainField(SEED), 0, 0, 512);
        Set<Integer> colours = new HashSet<>();
        for (int y = 0; y < paper.getHeight(); y += 2) {
            for (int x = 0; x < paper.getWidth(); x += 2) colours.add(paper.getRGB(x, y));
        }
        assertTrue(colours.size() > 200,
                "the whole map is " + colours.size() + " colours — that is not terrain");
    }

    // --- 3. the icons ------------------------------------------------------------------

    /**
     * A map has the trading posts on it, and it has them whether or not anybody
     * has ever walked to one.
     *
     * <p>The point of a map is to tell you where to go. A map that only shows
     * the posts you have already found is a map that cannot.
     */
    @Test
    void aMapHasTheShopsOnItWhetherOrNotAnybodyHasFoundThem() {
        WatchGame game = walking();
        Shops.Shop shop = game.shops().nearest(game.field(), 0, 0, 3000);
        assertNotNull(shop, "the test world has no trading post within three kilometres");
        stand(game, 1, shop.x(), shop.y());
        Chart chart = game.drawMap(1, 512);
        assertNotNull(chart);
        List<Chart.Landmark> posts = chart.landmarks(Chart.Kind.SHOP);
        assertFalse(posts.isEmpty(), "a map drawn on a trading post has no shop on it");
        boolean found = false;
        for (Chart.Landmark landmark : posts) {
            if (Math.hypot(landmark.x() - shop.x(), landmark.y() - shop.y()) < 1) {
                found = true;
                assertEquals(shop.sign(), landmark.label());
            }
        }
        assertTrue(found, "the post the player is standing at is not the one on the map");
    }

    /** Everything else the party put in the world is on it too. */
    @Test
    void aMapHasTheOtherPointsOfInterestOnIt() {
        WatchGame game = walking();
        stand(game, 1, 0, 0);
        assertNotNull(game.placeLure(1, "suet_cake"), "could not put a feeder out");
        assertNotNull(game.build(1, BuildPiece.FLOOR, 0, false), "could not build");

        Chart chart = game.drawMap(1, 256);
        assertNotNull(chart);
        assertFalse(chart.landmarks(Chart.Kind.FEEDER).isEmpty(),
                "the feeder standing at the player's feet is not on their map");
        assertFalse(chart.landmarks(Chart.Kind.CAMP).isEmpty(),
                "the floor they just laid is not on their map");
    }

    /**
     * A camp of many pieces is one icon.
     *
     * <p>Not a nicety: without it a treehouse is forty overlapping tents and the
     * map is unreadable exactly where somebody has been busiest.
     */
    @Test
    void aCampOfManyPiecesIsOneIcon() {
        WatchGame game = walking();
        int built = 0;
        for (int i = 0; i < 6; i++) {
            stand(game, 1, i * 0.6, 0);
            if (game.build(1, BuildPiece.POST, 0, false) != null) built++;
        }
        assertTrue(built >= 4, "only " + built + " pieces went down; nothing to cluster");
        stand(game, 1, 0, 0);
        Chart chart = game.drawMap(1, 256);
        assertNotNull(chart);
        assertEquals(1, chart.landmarks(Chart.Kind.CAMP).size(),
                built + " pieces in one clearing drew "
                        + chart.landmarks(Chart.Kind.CAMP).size() + " camps");
    }

    /**
     * A map is a record of when it was drawn, not a live view.
     *
     * <p>The difference between a map and a minimap, and the reason there is any
     * point in drawing a second one of the same place.
     */
    @Test
    void aMapDoesNotFillInAfterwards() {
        WatchGame game = walking();
        stand(game, 1, 0, 0);
        Chart before = game.drawMap(1, 256);
        assertNotNull(before);
        int camps = before.landmarks(Chart.Kind.CAMP).size();

        assertNotNull(game.build(1, BuildPiece.FLOOR, 0, false));
        assertEquals(camps, before.landmarks(Chart.Kind.CAMP).size(),
                "a camp built afterwards appeared on a map already drawn");

        Chart after = game.drawMap(1, 256);
        assertNotNull(after);
        assertTrue(after.landmarks(Chart.Kind.CAMP).size() > camps,
                "a map drawn after the camp went up does not have it");
    }

    // --- 4. the pen --------------------------------------------------------------------

    /** A stroke goes on, comes back off, and is kept in world metres either way. */
    @Test
    void thePenMarksTheMapAndTheEraserUnmarksIt() {
        WatchGame game = walking();
        stand(game, 1, 0, 0);
        Chart chart = game.drawMap(1, 256);
        assertNotNull(chart);

        double[] xs = {10, 20, 30};
        double[] ys = {-10, -14, -18};
        Chart.Stroke stroke = game.markMap(1, chart.id(), 2, xs, ys);
        assertNotNull(stroke, "the pen drew nothing");
        assertEquals(1, chart.strokes().size());
        assertEquals(Chart.Ink.at(2), stroke.ink());
        assertEquals(10, stroke.xs()[0], 1e-9,
                "ink is not in world metres, so it cannot travel between maps");

        // A single point is not a line, and is refused rather than stored.
        assertNull(game.markMap(1, chart.id(), 0, new double[]{1}, new double[]{1}),
                "one point is a line");

        Chart.Note note = game.noteMap(1, chart.id(), 3, 15, -12, "otters at dusk");
        assertNotNull(note);
        assertEquals(1, chart.notes().size());
        assertEquals(2, chart.marks());

        // The eraser names one mark by id, and the id space covers both kinds.
        long near = chart.markNear(20, -14, 4);
        assertEquals(stroke.id(), near, "the eraser picked the wrong mark");
        assertTrue(game.eraseMark(1, chart.id(), near));
        assertEquals(0, chart.strokes().size());
        assertEquals(1, chart.marks(), "rubbing out the line took the note with it");
        assertTrue(game.eraseMark(1, chart.id(), note.id()));
        assertEquals(0, chart.marks());
    }

    /** A stroke is capped, so a very long scribble cannot be a very long message. */
    @Test
    void aStrokeIsBounded() {
        double[] xs = new double[Chart.MAX_STROKE_POINTS * 3];
        double[] ys = new double[xs.length];
        for (int i = 0; i < xs.length; i++) {
            xs[i] = i;
            ys[i] = i;
        }
        Chart.Stroke stroke = new Chart.Stroke(1, Chart.Ink.RED, "Kara", xs, ys);
        assertEquals(Chart.MAX_STROKE_POINTS, stroke.size());
    }

    /** What was written on a map is still on it after a save and a reload. */
    @Test
    void marksSurviveASave(@TempDir Path dir) {
        WatchGame game = walking();
        stand(game, 1, 0, 0);
        Chart chart = game.drawMap(1, 256);
        assertNotNull(chart);
        game.renameMap(1, chart.id(), "The Long Meadow");
        game.markMap(1, chart.id(), 1, new double[]{4, 8}, new double[]{4, 9});
        game.noteMap(1, chart.id(), 4, 6, 6, "ford");
        int icons = chart.landmarks().size();

        WatchStore store = new WatchStore(dir.resolve("worlds").toString());
        store.save(game);

        WatchGame reopened = new WatchGame(WatchGame.Config.hosted("Cartography", SEED));
        assertTrue(store.load(reopened), "the world did not save");
        Chart back = reopened.maps().chart(chart.id());
        assertNotNull(back, "the map did not survive the save");
        assertEquals("The Long Meadow", back.name());
        assertEquals(chart.centreX(), back.centreX(), 1e-6);
        assertEquals(chart.radius(), back.radius(), 1e-6);
        assertEquals(icons, back.landmarks().size(), "the icons did not come back");
        assertEquals(1, back.strokes().size());
        assertEquals(1, back.notes().size());
        assertEquals("ford", back.notes().get(0).text());
        assertEquals(4, back.strokes().get(0).xs()[0], 0.02,
                "the stroke came back somewhere else");
    }

    /** A map crosses the wire with its ink, and no picture. */
    @Test
    void aMapTravelsAsFourNumbersAndSomeInk() {
        WatchGame game = walking();
        stand(game, 1, 0, 0);
        Chart chart = game.drawMap(1, 256);
        assertNotNull(chart);
        game.markMap(1, chart.id(), 0, new double[]{1, 2}, new double[]{3, 4});

        Map<String, Object> message = WatchProto.world(game.grove().toMap(),
                game.crops().toMap(), game.structure().toMap(), game.maps().toMap(),
                game.boats().toMap(), game.takenLitter());

        WatchView view = new WatchView();
        view.maps().load(WatchJson.map(message, "maps"));
        Chart theirs = view.maps().chart(chart.id());
        assertNotNull(theirs, "the map did not cross the wire");
        assertEquals(chart.name(), theirs.name());
        assertEquals(chart.radius(), theirs.radius(), 1e-6);
        assertEquals(1, theirs.strokes().size(), "the ink did not cross the wire");
        assertEquals(chart.landmarks().size(), theirs.landmarks().size());
    }

    // --- 5. in the satchel -------------------------------------------------------------

    /** A map is carried, and its name is the player's to change. */
    @Test
    void mapsAreCarriedAndCanBeRenamed() {
        WatchGame game = walking();
        stand(game, 1, 0, 0);
        Chart chart = game.drawMap(1, 256);
        assertNotNull(chart);
        assertEquals(List.of(chart), game.maps().carriedBy(1),
                "the map is not in the satchel of the person who drew it");

        String automatic = chart.name();
        assertFalse(automatic.isBlank(), "a fresh map has no name at all");
        assertTrue(game.renameMap(1, chart.id(), "Otter Reach"));
        assertEquals("Otter Reach", chart.name());
        assertNotEquals(automatic, chart.name());

        // Blank is refused: a map called nothing cannot be picked out of a list.
        assertFalse(game.renameMap(1, chart.id(), "   "));
        assertEquals("Otter Reach", chart.name());

        // And a name is bounded, like every other string that crosses the wire.
        game.renameMap(1, chart.id(), "x".repeat(400));
        assertTrue(chart.name().length() <= Chart.MAX_NAME_LENGTH);
    }

    // --- 6. everybody on it ------------------------------------------------------------

    /**
     * Every walker has a pin, and a walker off the paper is pinned to the
     * border rather than dropped.
     *
     * <p>The Minecraft rule, and the one that makes a map useful to a party
     * rather than to a person.
     */
    @Test
    void everyWalkerIsOnTheMapEvenTheOnesWhoWalkedOffIt(@TempDir Path dir) {
        try (Walk walk = new Walk(dir)) {
            Chart chart = walk.drawMap();
            assertNotNull(chart, "M did not draw a map");
            assertEquals("map", walk.scene.panelName(), "the map did not open");
            MapPanel panel = walk.scene.mapPanel();

            // Somebody standing in the middle of the map.
            int[] inside = panel.pinFor(walk.session.view(), chart.centreX(),
                    chart.centreY(), WIDTH, HEIGHT);
            assertEquals(0, inside[2], "a walker in the middle of the map is off it");

            // …and somebody a long way off the east edge.
            double away = chart.maxX() + chart.span() * 4;
            int[] off = panel.pinFor(walk.session.view(), away, chart.centreY(),
                    WIDTH, HEIGHT);
            assertEquals(1, off[2], "a walker four map-widths east is drawn as on it");
            double[] shown = panel.shown(walk.session.view(), WIDTH, HEIGHT);
            assertTrue(off[0] > inside[0],
                    "the pin for somebody to the east is not to the east");
            assertTrue(panel.pinFor(walk.session.view(), shown[2] + 1e6, chart.centreY(),
                    WIDTH, HEIGHT)[0] == off[0],
                    "a walker further away is pinned somewhere else than the border");
            assertEquals(inside[1], off[1], 2,
                    "a walker due east is pinned north or south of the map");
        }
    }

    /**
     * The frame both the panel and the board's face are drawn through.
     *
     * <p>One geometry, tested once. A map is drawn on a screen and on a piece of
     * timber and the two have to be the same map, so the thing that turns metres
     * into pixels lives in {@link MapInk} — and if it were wrong, every icon on
     * both would be in the wrong place together, which is exactly the sort of
     * bug that looks deliberate.
     */
    @Test
    void metresAndPixelsAgreeInBothDirections() {
        MapInk.Frame frame = new MapInk.Frame(20, 40, 300, 1000, -500, 600);
        // The middle of the paper is the middle of the country.
        assertEquals(1000, frame.worldX(20 + 150), 2);
        assertEquals(-500, frame.worldY(40 + 150), 2);
        // North is up, and north is −y.
        assertTrue(frame.screenY(-800) < frame.screenY(-200),
                "north is not up: a point further north drew further down");
        assertTrue(frame.screenX(1200) > frame.screenX(800),
                "east is not right");
        // …and a round trip lands back where it started, to the pixel.
        for (double at : new double[]{760, 900, 1000, 1180, 1290}) {
            assertEquals(at, frame.worldX(frame.screenX(at)), 3);
        }
        assertTrue(frame.holds(20, 40));
        assertFalse(frame.holds(19, 40));
        assertFalse(frame.holds(20 + 300, 40));
        assertEquals(0.5, frame.scale(), 1e-9);
    }

    // --- 7. the board ------------------------------------------------------------------

    /**
     * Two maps of neighbouring country, pinned to one board, are one larger map.
     *
     * <p>There is nothing for the player to line up: the board's paper is the
     * union of what is on it, every map is drawn where it is, and two maps a
     * span apart therefore meet along an edge. That is the whole of "combine
     * into a larger map".
     */
    @Test
    void twoMapsOnABoardAreOneLargerMap() {
        WatchGame game = walking();
        stand(game, 1, 0, 0);
        Chart west = game.drawMap(1, 128);
        assertNotNull(west);
        // One whole span east, which is the step that makes two maps meet.
        stand(game, 1, west.span(), 0);
        Chart east = game.drawMap(1, 128);
        assertNotNull(east);
        assertNotEquals(west.id(), east.id());
        assertEquals(west.maxX(), east.minX(), 1e-6,
                "two maps drawn a span apart do not meet: " + west.maxX() + " vs "
                        + east.minX());

        // Build the board, walk to it, and pin both.
        stand(game, 1, 0, 0);
        var placement = game.build(1, BuildPiece.MAP_BOARD, 0, false);
        assertNotNull(placement, "a walker in debug mode could not build a map board");
        Cartography.Board board = game.maps().boardAt(placement.x(), placement.y());
        assertNotNull(board, "building a board did not put one in the world");
        stand(game, 1, board.x(), board.y());
        assertNotNull(game.boardAt(1), "standing at the board is not standing at it");

        assertTrue(game.pinMap(1, west.id(), board.id()));
        double[] one = game.maps().bounds(board.id());
        assertNotNull(one);
        assertEquals(west.span(), one[2] - one[0], 1e-6);

        assertTrue(game.pinMap(1, east.id(), board.id()));
        double[] both = game.maps().bounds(board.id());
        assertNotNull(both);
        assertEquals(west.span() * 2, both[2] - both[0], 1e-6,
                "pinning a second map beside the first did not widen the board");
        assertEquals(west.span(), both[3] - both[1], 1e-6,
                "two maps side by side made the board taller as well as wider");
        assertEquals(2, game.maps().pinnedTo(board.id()).size());
        assertTrue(game.maps().carriedBy(1).isEmpty(),
                "a map on the board is still in the satchel as well");

        // …and taking one back shrinks it again, and puts the map in the bag.
        assertTrue(game.pinMap(1, east.id(), 0));
        assertEquals(west.span(), game.maps().bounds(board.id())[2]
                - game.maps().bounds(board.id())[0], 1e-6);
        assertEquals(List.of(east), game.maps().carriedBy(1));
    }

    /**
     * The board carries its maps on its face, so standing in front of one is
     * enough.
     *
     * <p>The claim this whole half of the feature exists to make: a board whose
     * maps only exist inside a screen is a noticeboard with the notice in a
     * drawer.
     */
    @Test
    void theBoardWearsItsMaps() {
        ChartImage.invalidate();
        BoardImage.invalidate();
        WatchGame game = walking();
        stand(game, 1, 0, 0);
        Chart chart = game.drawMap(1, 128);
        assertNotNull(chart);
        var placement = game.build(1, BuildPiece.MAP_BOARD, 0, false);
        assertNotNull(placement);
        Cartography.Board board = game.maps().boardAt(placement.x(), placement.y());
        assertNotNull(board);

        int grid = 24;
        assertNull(BoardImage.cells(game.field(), game.maps(), board, grid),
                "an empty board already has a map on it");

        stand(game, 1, board.x(), board.y());
        assertTrue(game.pinMap(1, chart.id(), board.id()));
        // The ground is painted on a worker; bake it here so the test is about
        // the board rather than about how fast that thread happens to be.
        ChartImage.bake(game.field(), chart.centreX(), chart.centreY(), chart.radius());

        int[] face = BoardImage.cells(game.field(), game.maps(), board, grid);
        assertNotNull(face, "a board with a map pinned to it has a blank face");
        assertEquals(grid * grid, face.length);
        Set<Integer> colours = new HashSet<>();
        for (int cell : face) colours.add(cell);
        assertTrue(colours.size() > 20,
                "the whole board's face is " + colours.size()
                        + " colours — that is not a map");

        // …and the face follows the ink. A pen stroke somebody draws is
        // something the rest of the party watches appear on the board.
        int[] before = face.clone();
        double across = chart.span() * 0.3;
        game.markMap(1, chart.id(), 1,
                new double[]{chart.centreX() - across, chart.centreX() + across},
                new double[]{chart.centreY(), chart.centreY()});
        int[] after = BoardImage.cells(game.field(), game.maps(), board, grid);
        assertNotNull(after);
        assertFalse(java.util.Arrays.equals(before, after),
                "drawing a line across the map changed nothing on the board");

        // Taking the map back leaves bare timber again.
        assertTrue(game.pinMap(1, chart.id(), 0));
        assertNull(BoardImage.cells(game.field(), game.maps(), board, grid),
                "a board with nothing on it still shows a map");
    }

    /** …and it is real geometry in the world, not something the panel draws. */
    @Test
    void theBoardsMapIsInTheWorld(@TempDir Path dir) {
        try (Walk walk = new Walk(dir)) {
            ChartImage.invalidate();
            BoardImage.invalidate();
            Chart chart = walk.drawMap();
            assertNotNull(chart);
            walk.press(KeyEvent.VK_ESCAPE);

            var placement = walk.game.build(1, BuildPiece.MAP_BOARD, 0, false);
            assertNotNull(placement);
            walk.tick(4);
            walk.draw();
            assertEquals(0, walk.scene.boardTriangles(),
                    "an empty board is already covered in map");

            Cartography.Board board = walk.game.maps().boardAt(placement.x(),
                    placement.y());
            assertNotNull(board);
            assertTrue(walk.game.pinMap(1, chart.id(), board.id()));
            ChartImage.bake(walk.scene.streamer().field(), chart.centreX(),
                    chart.centreY(), chart.radius());
            walk.tick(4);
            walk.draw();

            assertTrue(walk.scene.boardTriangles() > 0,
                    "the map on the board is not in the world at all");
            assertEquals("none", walk.scene.panelName(),
                    "the board's map only shows with a panel open");
        }
    }

    /** A board is read from in front of it, not from the next valley. */
    @Test
    void aMapIsPinnedAtTheBoardRatherThanFromAnywhere() {
        WatchGame game = walking();
        stand(game, 1, 0, 0);
        Chart chart = game.drawMap(1, 128);
        var placement = game.build(1, BuildPiece.MAP_BOARD, 0, false);
        assertNotNull(placement);
        Cartography.Board board = game.maps().boardAt(placement.x(), placement.y());
        assertNotNull(board);

        stand(game, 1, board.x() + 400, board.y());
        assertFalse(game.pinMap(1, chart.id(), board.id()),
                "a walker four hundred metres away pinned a map to the board");
        assertTrue(game.maps().pinnedTo(board.id()).isEmpty());

        stand(game, 1, board.x(), board.y());
        assertTrue(game.pinMap(1, chart.id(), board.id()));
    }

    /** Walking up to a board is something the walk can see. */
    @Test
    void aBoardIsSomethingYouCanWalkUpTo() {
        WatchGame game = walking();
        stand(game, 1, 0, 0);
        var placement = game.build(1, BuildPiece.MAP_BOARD, 0, false);
        assertNotNull(placement);
        stand(game, 1, placement.x(), placement.y());
        WatchGame.Pickable target = game.pickTarget(1);
        assertNotNull(target, "there is nothing in reach at a map board");
        assertEquals(WatchGame.Pickable.Kind.BOARD, target.kind());
        assertNotNull(game.use(1), "pressing the use key at a board says nothing");
    }

    // --- 8. and none of it, without the code -------------------------------------------

    /**
     * Every map verb is refused to a player who has not typed the code.
     *
     * <p>The claim {@link Debug.Power#MAPS} makes, checked one verb at a time —
     * because the gate is one line in each of them and one line is exactly what
     * gets left out.
     */
    @Test
    void everyMapVerbIsBehindDebugMode() {
        WatchGame game = new WatchGame(WatchGame.Config.hosted("Locked", SEED));
        game.join(1, "Kara");
        stand(game, 1, 0, 0);

        assertNull(game.drawMap(1, 256), "a player without the code drew a map");
        assertNull(game.build(1, BuildPiece.MAP_BOARD, 0, false),
                "a player without the code built a map board");

        // Then with the code, so we have something to be refused about.
        game.debug(1, Debug.CODE);
        Chart chart = game.drawMap(1, 256);
        assertNotNull(chart);
        var placement = game.build(1, BuildPiece.MAP_BOARD, 0, false);
        assertNotNull(placement);
        Cartography.Board board = game.maps().boardAt(placement.x(), placement.y());
        assertNotNull(board);
        stand(game, 1, board.x(), board.y());
        game.debug(1, Debug.CODE);
        assertFalse(game.player(1).debugging(), "the code did not toggle back off");

        assertNull(game.markMap(1, chart.id(), 0, new double[]{0, 1}, new double[]{0, 1}),
                "a player without the code wrote on a map");
        assertNull(game.noteMap(1, chart.id(), 0, 0, 0, "hello"),
                "a player without the code wrote a note on a map");
        assertFalse(game.renameMap(1, chart.id(), "Mine now"),
                "a player without the code renamed a map");
        assertFalse(game.eraseMark(1, chart.id(), 1),
                "a player without the code rubbed a map out");
        assertFalse(game.pinMap(1, chart.id(), board.id()),
                "a player without the code pinned a map to a board");
    }

    /** The build screen does not list the board to somebody who cannot build it. */
    @Test
    void theBuildScreenHidesWhatDebugModeIsHoldingBack() {
        assertFalse(BuildPiece.available(false).contains(BuildPiece.MAP_BOARD),
                "the map board is on the build menu for everybody");
        assertTrue(BuildPiece.available(true).contains(BuildPiece.MAP_BOARD));
        // Everything else is on both lists, so this is a gate rather than a
        // second menu that could drift from the first.
        for (BuildPiece piece : BuildPiece.all()) {
            if (piece.debugOnly()) continue;
            assertTrue(BuildPiece.available(false).contains(piece),
                    piece + " vanished from the ordinary build menu");
        }
    }

    /** The mode says out loud that it grants maps, on the readout it draws. */
    @Test
    void theReadoutSaysMapsAreOneOfThePowers() {
        assertTrue(Debug.powers().contains(Debug.Power.MAPS),
                "debug mode grants maps and does not say so");
        assertFalse(Debug.Power.MAPS.note().isBlank());
    }

    // --- the walk ----------------------------------------------------------------------

    /**
     * A walk driven through the real scene, so the panel, the keys and the
     * satchel rows are exercised rather than only the model underneath them.
     */
    private static final class Walk implements AutoCloseable {
        final WatchScene scene;
        final SceneManager scenes;
        final InputManager input = new InputManager();
        final WatchSession session;
        final WatchGame game;

        Walk(Path dir) {
            GameContext ctx = new GameContext(null, new GameTypeStore(dir.toString()));
            scene = new WatchScene(ctx);
            scenes = new SceneManager();
            scenes.setViewport(WIDTH, HEIGHT);
            scenes.register(WatchLobbyScene.NAME, new WatchLobbyScene(ctx,
                    new WatchStore(dir.resolve("w").toString())));
            scenes.register(WatchScene.NAME, scene);

            game = new WatchGame(new WatchGame.Config(SEED, "Paper", 1));
            game.join(1, "Kara");
            game.debug(1, Debug.CODE);
            session = WatchSession.solo(game);
            session.setSelfId(1);
            scene.adopt(session, new WatchStore(dir.resolve("w").toString()));
            scenes.setScene(WatchScene.NAME);
            tick(3);
        }

        void tick(int frames) {
            for (int i = 0; i < frames; i++) {
                input.newFrame();
                scenes.update(1.0 / 60, input);
            }
        }

        void press(int key) {
            input.newFrame();
            input.pressKey(key, 0);
            scenes.update(1.0 / 60, input);
            input.newFrame();
            input.releaseKey(key);
            scenes.update(1.0 / 60, input);
        }

        void click(int x, int y) {
            input.newFrame();
            input.moveMouse(x, y);
            input.pressMouse(MouseEvent.BUTTON1, 0);
            scenes.update(1.0 / 60, input);
            input.newFrame();
            input.releaseMouse(MouseEvent.BUTTON1);
            scenes.update(1.0 / 60, input);
        }

        /** Press M, and hand back the map it drew. */
        Chart drawMap() {
            press(KeyEvent.VK_M);
            List<Chart> mine = game.maps().carriedBy(1);
            return mine.isEmpty() ? null : mine.get(mine.size() - 1);
        }

        void draw() {
            scenes.render(new RecordingTarget(WIDTH, HEIGHT), 1f);
        }

        /** Hold the button down and drag the pointer across the screen. */
        void drag(int fromX, int fromY, int toX, int toY) {
            input.newFrame();
            input.moveMouse(fromX, fromY);
            input.pressMouse(MouseEvent.BUTTON1, 0);
            scenes.update(1.0 / 60, input);
            for (int i = 1; i <= 12; i++) {
                input.newFrame();
                input.moveMouse(fromX + (toX - fromX) * i / 12,
                        fromY + (toY - fromY) * i / 12);
                scenes.update(1.0 / 60, input);
            }
            input.newFrame();
            input.releaseMouse(MouseEvent.BUTTON1);
            scenes.update(1.0 / 60, input);
        }

        /** Type a run of characters into whatever has the keyboard. */
        void type(String text) {
            input.newFrame();
            for (char c : text.toCharArray()) input.typeChar(c);
            scenes.update(1.0 / 60, input);
        }

        @Override public void close() { session.close(); }
    }

    /** M draws a map and opens it, and the panel draws without falling over. */
    @Test
    void theMapKeyDrawsOneAndOpensIt(@TempDir Path dir) {
        try (Walk walk = new Walk(dir)) {
            assertEquals("none", walk.scene.panelName());
            Chart chart = walk.drawMap();
            assertNotNull(chart, "M drew nothing");
            assertEquals("map", walk.scene.panelName());
            assertEquals(chart.id(), walk.scene.mapPanel().chartId());
            // …and it is still open several frames later. The panel closes
            // itself when what it is showing has gone, so "it opened" and "it
            // stayed open" are two different claims and only the second one
            // catches a panel opened on a view that has not been told about the
            // map yet.
            walk.tick(5);
            assertEquals("map", walk.scene.panelName(),
                    "the map screen closed itself a few frames after opening");
            // The map is as wide as this walk can actually see.
            assertTrue(chart.radius() >= walk.scene.mapReachMetres(),
                    "the map is narrower than the walk's own render distance");
            walk.draw();

            // …and M again puts it away.
            walk.press(KeyEvent.VK_M);
            assertEquals("none", walk.scene.panelName());
        }
    }

    /**
     * The pen: a drag across the paper is a stroke, and it lands where the hand
     * was.
     *
     * <p>Driven through the real scene rather than through
     * {@code WatchGame.markMap}, because the claim being made is about the
     * panel's arithmetic — a pen that draws two hundred metres from the pointer
     * is a pen nobody can mark a map with.
     */
    @Test
    void thePenDrawsWhereTheHandIs(@TempDir Path dir) {
        try (Walk walk = new Walk(dir)) {
            Chart chart = walk.drawMap();
            assertNotNull(chart);
            assertEquals("map", walk.scene.panelName());
            assertEquals(0, chart.strokes().size());
            walk.draw();

            // Down and to the right, across the middle of the paper — which is
            // south and east on a map with north up.
            walk.drag(220, 200, 380, 340);

            List<Chart.Stroke> ink = walk.game.maps().chart(chart.id()).strokes();
            assertEquals(1, ink.size(), "a drag across the paper drew " + ink.size()
                    + " strokes");
            Chart.Stroke stroke = ink.get(0);
            assertTrue(stroke.size() >= 3,
                    "the stroke kept only " + stroke.size() + " points of a long drag");
            int last = stroke.size() - 1;
            assertTrue(stroke.xs()[last] > stroke.xs()[0],
                    "dragging east drew a line running west");
            assertTrue(stroke.ys()[last] > stroke.ys()[0],
                    "dragging south drew a line running north");
            for (int i = 0; i < stroke.size(); i++) {
                assertTrue(chart.covers(stroke.xs()[i], stroke.ys()[i]),
                        "the pen put ink outside the map it was drawn on");
            }

            // The eraser (tool 3) takes it off again, at the point it was drawn.
            walk.press(KeyEvent.VK_3);
            walk.click(300, 270);
            assertTrue(walk.game.maps().chart(chart.id()).strokes().isEmpty(),
                    "the eraser did not rub out the line under the pointer");
        }
    }

    /** The note tool writes words on the map, which is the information half. */
    @Test
    void aNoteWritesWordsOnTheMap(@TempDir Path dir) {
        try (Walk walk = new Walk(dir)) {
            Chart chart = walk.drawMap();
            assertNotNull(chart);
            walk.press(KeyEvent.VK_2);
            assertEquals(MapPanel.Tool.NOTE, walk.scene.mapPanel().tool());
            walk.click(300, 300);
            assertTrue(walk.scene.mapPanel().typing(),
                    "clicking with the note tool did not open a field");
            walk.type("otters at dusk");
            walk.draw();
            walk.press(KeyEvent.VK_ENTER);

            List<Chart.Note> notes = walk.game.maps().chart(chart.id()).notes();
            assertEquals(1, notes.size());
            assertEquals("otters at dusk", notes.get(0).text());
            assertTrue(chart.covers(notes.get(0).x(), notes.get(0).y()),
                    "the note was written off the edge of the map");
            assertFalse(walk.scene.mapPanel().typing(), "the field did not close");
        }
    }

    /** The satchel lists maps above the items, and opens one when it is clicked. */
    @Test
    void theSatchelCarriesMapsAndOpensThemWhenClicked(@TempDir Path dir) {
        try (Walk walk = new Walk(dir)) {
            Chart chart = walk.drawMap();
            assertNotNull(chart);
            walk.press(KeyEvent.VK_ESCAPE);
            assertEquals("none", walk.scene.panelName());

            walk.press(KeyEvent.VK_TAB);
            assertEquals("satchel", walk.scene.panelName());
            walk.draw();
            assertEquals("map:" + chart.id(), walk.scene.panelCursor(),
                    "the satchel does not open on the map at the top of the list");

            // Enter on that row opens the map, which is what "click on it to
            // view it" means from the keyboard.
            walk.press(KeyEvent.VK_ENTER);
            assertEquals("map", walk.scene.panelName());
            assertEquals(chart.id(), walk.scene.mapPanel().chartId());
        }
    }

    /**
     * The board, worked the way a player works it: walk up to it, press the use
     * key, and click a map in your satchel to put it up.
     *
     * <p>The click is swept down the strip rather than aimed at a row this test
     * has computed for itself, because a test that reproduces the panel's
     * layout arithmetic is a test that agrees with the bug.
     */
    @Test
    void aBoardIsOpenedAndPinnedToFromTheWalk(@TempDir Path dir) {
        try (Walk walk = new Walk(dir)) {
            Chart chart = walk.drawMap();
            assertNotNull(chart);
            walk.press(KeyEvent.VK_ESCAPE);

            var placement = walk.game.build(1, BuildPiece.MAP_BOARD, 0, false);
            assertNotNull(placement, "the walk could not build a map board");
            walk.tick(4);
            Cartography.Board board = walk.game.maps().boardAt(placement.x(),
                    placement.y());
            assertNotNull(board);

            assertNotNull(walk.scene.inReach(), "nothing is in reach at the board");
            assertEquals(WatchGame.Pickable.Kind.BOARD, walk.scene.inReach().kind(),
                    "the walk does not see the board it is standing at");
            walk.press(KeyEvent.VK_E);
            assertEquals("map", walk.scene.panelName(), "E at a board opened nothing");
            assertEquals(board.id(), walk.scene.mapPanel().boardId());
            assertEquals(0, walk.scene.mapPanel().chartId(),
                    "the board opened as a single map");

            // Down the strip, clicking, until the map goes up. The pin rows are
            // laid out while drawing, so a frame has to be drawn first.
            for (int y = 470; y < 580 && walk.game.maps().pinnedTo(board.id()).isEmpty();
                    y += 6) {
                walk.draw();
                walk.click(820, y);
            }
            assertEquals(1, walk.game.maps().pinnedTo(board.id()).size(),
                    "clicking down the board's list never pinned the map");
            assertTrue(walk.game.maps().carriedBy(1).isEmpty(),
                    "the map is on the board and in the satchel at once");
        }
    }

    /** F2 in the satchel renames the map under the cursor. */
    @Test
    void aMapIsRenamedInTheSatchel(@TempDir Path dir) {
        try (Walk walk = new Walk(dir)) {
            Chart chart = walk.drawMap();
            assertNotNull(chart);
            walk.press(KeyEvent.VK_ESCAPE);
            walk.press(KeyEvent.VK_TAB);
            assertEquals("satchel", walk.scene.panelName());

            walk.press(KeyEvent.VK_F2);
            // Typed characters arrive the way a window delivers them.
            walk.input.newFrame();
            for (char c : "Otter Reach".toCharArray()) walk.input.typeChar(c);
            walk.scenes.update(1.0 / 60, walk.input);
            walk.draw();
            walk.press(KeyEvent.VK_ENTER);

            assertEquals("Otter Reach", walk.game.maps().chart(chart.id()).name());
            assertEquals("satchel", walk.scene.panelName(),
                    "committing a name closed the satchel");
        }
    }

    /**
     * Typing a name does not toggle debug mode off underneath the player.
     *
     * <p>The code is four digits read anywhere in the walk, and a map's name is
     * text with digits in it. Without the guard, renaming a map to something
     * with {@value Debug#CODE} in it takes the whole feature away mid-keystroke.
     */
    @Test
    void typingANameDoesNotTypeTheCode(@TempDir Path dir) {
        try (Walk walk = new Walk(dir)) {
            assertNotNull(walk.drawMap());
            walk.press(KeyEvent.VK_ESCAPE);
            walk.press(KeyEvent.VK_TAB);
            walk.press(KeyEvent.VK_F2);
            for (char c : Debug.CODE.toCharArray()) {
                walk.input.newFrame();
                walk.input.pressKey(KeyEvent.VK_0 + (c - '0'), 0);
                walk.input.typeChar(c);
                walk.scenes.update(1.0 / 60, walk.input);
                walk.input.newFrame();
                walk.input.releaseKey(KeyEvent.VK_0 + (c - '0'));
                walk.scenes.update(1.0 / 60, walk.input);
            }
            assertTrue(walk.game.player(1).debugging(),
                    "typing a name with the code in it turned debug mode off");
        }
    }
}
