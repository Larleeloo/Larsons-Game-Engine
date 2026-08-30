package com.larsons.engine.watch;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every map the party has drawn, and every board they have hung one on.
 *
 * <p><b>A flat store, for {@code Structure}'s reasons.</b> A party makes a few
 * dozen maps at the outside; an index over that is a thing to keep correct in
 * exchange for microseconds nobody was going to notice. What matters is that a
 * map is small enough to send whole — see {@link Chart}, which keeps where it
 * is and not what it looks like — so the entire cartography rides along in the
 * world sync beside the grove and the buildings, and a stroke somebody draws
 * appears on everybody's copy on the next one.
 *
 * <h2>Combining maps is laying them out, and nothing else</h2>
 *
 * <p>A {@linkplain Board map board} does not merge its maps into a new map.
 * It puts every map pinned to it where that map <em>is</em> — the whole board
 * is drawn in world coordinates, over the union of what has been pinned — and
 * the picture that comes out is continuous because the picture of a square of
 * ground is a function of the ground. Two maps of neighbouring country meet
 * along their shared edge with nothing to line up; two maps of the same country
 * at different sizes nest, because {@link Chart#RADII} is a ladder of doublings
 * and {@link Chart#snap} puts every centre on the grid its own size defines.
 *
 * <p>That is the whole of "combine into a larger map": pin a second map, and the
 * board is bigger. There is no join to make, no orientation to choose and no
 * order to get right — which is what makes it worth doing at a board instead of
 * flicking between two maps in a satchel.
 *
 * <h2>One counter for everything</h2>
 *
 * <p>Maps, boards, strokes and notes all take their ids from {@link #nextId},
 * so a mark's id is unique across the whole world and not merely within the map
 * it happens to be on. That costs nothing and it means the eraser can name what
 * it is rubbing out with one number, on a wire where the map it is on has
 * already been named.
 */
public final class Cartography {

    /** How close a player has to stand to a board to read it, in metres. */
    public static final double BOARD_RANGE = 3.4;

    /** How many maps the party may have at once. */
    public static final int MAX_CHARTS = 64;

    /**
     * One board, standing where somebody built it.
     *
     * <p>Twinned with a {@code Structure.Placement}: the placement is the
     * timber, which the mesher draws like any other built thing, and this is
     * what may be pinned to it. Two records rather than a field on the
     * placement, because a placement is a box in the world and knows nothing
     * about maps — and because the board is the thing a map points at, so it
     * has to have an id of its own that survives the timber being rebuilt.
     */
    public record Board(long id, long placementId, double x, double y, double z,
                        double yaw, String builtBy) {

        /** How far a player standing at a point is from it, in metres. */
        public double distanceTo(double px, double py) {
            return Math.hypot(x - px, y - py);
        }

        /** Whether somebody standing there could read it. */
        public boolean inReach(double px, double py) {
            return distanceTo(px, py) <= BOARD_RANGE;
        }

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("pl", placementId);
            m.put("x", x);
            m.put("y", y);
            m.put("z", z);
            m.put("yaw", yaw);
            if (builtBy != null) m.put("by", builtBy);
            return m;
        }

        static Board fromMap(Map<String, Object> m) {
            long id = WatchJson.big(m, "id", 0);
            if (id == 0) return null;
            return new Board(id, WatchJson.big(m, "pl", 0), WatchJson.num(m, "x", 0),
                    WatchJson.num(m, "y", 0), WatchJson.num(m, "z", 0),
                    WatchJson.num(m, "yaw", 0), WatchJson.str(m, "by", null));
        }
    }

    private final Map<Long, Chart> charts = new LinkedHashMap<>();
    private final Map<Long, Board> boards = new LinkedHashMap<>();
    private long nextId = 1;

    /**
     * Draw a map here.
     *
     * <p>The centre and the size are not the caller's to choose: the size comes
     * off the ladder for whatever the caller could see ({@link Chart#radiusFor})
     * and the centre is snapped to that size's grid ({@link Chart#snap}). A
     * caller that passed its own numbers straight through would be a caller
     * whose maps did not tile, which is the one thing this class is for.
     *
     * @param reach     how far the machine drawing it can see, in metres
     * @param landmarks what {@link Survey} found standing there, now frozen
     * @return the new map, or {@code null} if the party already has too many
     */
    public Chart draw(String name, double x, double y, double reach, String maker,
                      int owner, long atMillis, List<Chart.Landmark> landmarks) {
        if (charts.size() >= MAX_CHARTS) return null;
        double radius = Chart.radiusFor(reach);
        Chart chart = new Chart(nextId++, name, Chart.snap(x, radius),
                Chart.snap(y, radius), radius, maker, atMillis, landmarks);
        chart.setOwner(owner);
        charts.put(chart.id(), chart);
        return chart;
    }

    /** Add a map that arrived from a host or a save, keeping its id. */
    public void adopt(Chart chart) {
        if (chart == null) return;
        charts.put(chart.id(), chart);
        nextId = Math.max(nextId, chart.id() + 1);
    }

    /** Add a board that arrived from a host or a save, keeping its id. */
    public void adopt(Board board) {
        if (board == null) return;
        boards.put(board.id(), board);
        nextId = Math.max(nextId, board.id() + 1);
    }

    /** The map with this id, or {@code null}. */
    public Chart chart(long id) { return charts.get(id); }

    /** Every map, in the order they were drawn. */
    public List<Chart> charts() { return List.copyOf(charts.values()); }

    public int size() { return charts.size(); }

    /** The maps in one player's satchel, oldest first. */
    public List<Chart> carriedBy(int owner) {
        List<Chart> out = new ArrayList<>();
        for (Chart chart : charts.values()) {
            if (!chart.pinned() && chart.owner() == owner) out.add(chart);
        }
        return out;
    }

    /** The maps pinned to one board, in the order they went up. */
    public List<Chart> pinnedTo(long boardId) {
        List<Chart> out = new ArrayList<>();
        if (boardId == 0) return out;
        for (Chart chart : charts.values()) {
            if (chart.boardId() == boardId) out.add(chart);
        }
        return out;
    }

    /** Throw a map away. */
    public Chart remove(long id) { return charts.remove(id); }

    /** Rename one. */
    public boolean rename(long chartId, String name) {
        Chart chart = charts.get(chartId);
        if (chart == null || name == null || name.isBlank()) return false;
        chart.setName(name);
        return true;
    }

    /** Lay a pen stroke down on one, in world metres. */
    public Chart.Stroke mark(long chartId, Chart.Ink ink, String by, double[] xs,
                             double[] ys) {
        Chart chart = charts.get(chartId);
        if (chart == null) return null;
        Chart.Stroke stroke = new Chart.Stroke(nextId++, ink, by, xs, ys);
        if (!stroke.drawable()) return null;
        chart.add(stroke);
        return stroke;
    }

    /** Write a few words on one. */
    public Chart.Note note(long chartId, Chart.Ink ink, String by, double x, double y,
                           String text) {
        Chart chart = charts.get(chartId);
        if (chart == null || text == null || text.isBlank()) return null;
        Chart.Note note = new Chart.Note(nextId++, ink, by, x, y,
                Chart.trim(text, Chart.MAX_NOTE_LENGTH));
        chart.add(note);
        return note;
    }

    /** Rub one mark out. */
    public boolean erase(long chartId, long markId) {
        Chart chart = charts.get(chartId);
        return chart != null && chart.erase(markId);
    }

    /**
     * Pin a map to a board, or pass {@code boardId == 0} to take it back.
     *
     * <p>Taking one back puts it in {@code owner}'s satchel rather than back in
     * whoever's it was: a board is shared, so the person who unpins a map is the
     * person now carrying it, which is what happens when you take a page off a
     * wall.
     */
    public boolean pin(long chartId, long boardId, int owner) {
        Chart chart = charts.get(chartId);
        if (chart == null) return false;
        if (boardId != 0 && !boards.containsKey(boardId)) return false;
        chart.setBoardId(boardId);
        if (boardId == 0) chart.setOwner(owner);
        return true;
    }

    /** Put a board up, twinned with the piece of timber somebody built. */
    public Board raise(long placementId, double x, double y, double z, double yaw,
                       String builtBy) {
        Board board = new Board(nextId++, placementId, x, y, z, yaw, builtBy);
        boards.put(board.id(), board);
        return board;
    }

    /** The board with this id, or {@code null}. */
    public Board board(long id) { return boards.get(id); }

    /** Every board. */
    public List<Board> boards() { return List.copyOf(boards.values()); }

    /** The board a player is standing at, or {@code null}. */
    public Board boardAt(double x, double y) {
        Board best = null;
        double bestDistance = BOARD_RANGE;
        for (Board board : boards.values()) {
            double d = board.distanceTo(x, y);
            if (d <= bestDistance) {
                bestDistance = d;
                best = board;
            }
        }
        return best;
    }

    /** Take a board down along with the timber it was on. */
    public Board removeBoard(long placementId) {
        Board found = null;
        for (Board board : boards.values()) {
            if (board.placementId() == placementId) {
                found = board;
                break;
            }
        }
        if (found == null) return null;
        boards.remove(found.id());
        // The maps that were on it go back in the world rather than with it: a
        // board coming down is not a reason to lose a map, and an unowned map
        // is one anybody can pick up.
        for (Chart chart : charts.values()) {
            if (chart.boardId() == found.id()) chart.setBoardId(0);
        }
        return found;
    }

    /**
     * The country a board covers, as {@code minX, minY, maxX, maxY} — or
     * {@code null} when nothing is pinned to it.
     *
     * <p>The union of the pinned maps' own squares, which is the whole of how
     * maps combine. See the class note.
     */
    public double[] bounds(long boardId) {
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        boolean any = false;
        for (Chart chart : pinnedTo(boardId)) {
            any = true;
            minX = Math.min(minX, chart.minX());
            minY = Math.min(minY, chart.minY());
            maxX = Math.max(maxX, chart.maxX());
            maxY = Math.max(maxY, chart.maxY());
        }
        return any ? new double[]{minX, minY, maxX, maxY} : null;
    }

    // --- persistence ----------------------------------------------------------------

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("next", nextId);
        List<Object> chartRows = new ArrayList<>();
        for (Chart chart : charts.values()) chartRows.add(chart.toMap());
        m.put("charts", chartRows);
        List<Object> boardRows = new ArrayList<>();
        for (Board board : boards.values()) boardRows.add(board.toMap());
        m.put("boards", boardRows);
        return m;
    }

    /**
     * Replace everything from a save or a world sync.
     *
     * <p>Wholesale, like every other shared thing in this game: the lists are
     * small, a frame has to be right rather than clever, and a merge would have
     * to decide what to do about a map somebody else has just thrown away.
     */
    public void load(Map<String, Object> m) {
        charts.clear();
        boards.clear();
        nextId = Math.max(1, WatchJson.big(m, "next", 1));
        for (Map<String, Object> row : WatchJson.objects(m, "charts")) {
            adopt(Chart.fromMap(row));
        }
        for (Map<String, Object> row : WatchJson.objects(m, "boards")) {
            adopt(Board.fromMap(row));
        }
    }

    @Override public String toString() {
        return charts.size() + " maps, " + boards.size() + " boards";
    }
}
