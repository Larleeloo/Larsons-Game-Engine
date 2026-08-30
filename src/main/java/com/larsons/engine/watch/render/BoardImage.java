package com.larsons.engine.watch.render;

import com.larsons.engine.graphics.Offscreen;
import com.larsons.engine.graphics.draw.DrawTarget;
import com.larsons.engine.watch.Cartography;
import com.larsons.engine.watch.Chart;
import com.larsons.engine.watch.world.TerrainField;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The face of a map board, as a grid of colours the world can be built out of.
 *
 * <h2>Why the board is drawn in the world at all</h2>
 *
 * <p>A board that only shows its maps when you press a key is a noticeboard
 * with the notice in a drawer. The whole reason a party builds one is so that
 * <b>anybody standing in front of it can see the map</b> — who has surveyed
 * what, where the camp is, what somebody circled in red — without stopping,
 * opening a screen, and closing it again. Opening it is for reading the small
 * print; standing there is for seeing the shape of the country.
 *
 * <p>So the combined map is baked into an image here and
 * {@code WatchScene.boardFaces} lays it on the timber as a grid of little flat
 * facets, one per cell, in the colour of the ground it stands for. That is the
 * same thing the terrain itself is — a low-poly world of flat-shaded facets —
 * so a map on a board looks like it belongs to this world rather than like a
 * photograph hung in it.
 *
 * <h2>Baked once, sampled as facets</h2>
 *
 * <p>The image is baked {@value #SUPERSAMPLE} times larger than the grid and
 * then box-filtered down. That is not a detail: a pen stroke is a line a couple
 * of pixels wide, and a line sampled at one point per facet either lands on a
 * facet or does not — a scribble comes out as a dotted rash. Averaged down, the
 * facets a line passes through are tinted toward its ink and the mark reads as
 * a mark.
 *
 * <p>Everything about the picture is a function of the world: the ground comes
 * from {@link ChartImage}, which paints it from the seed, and the icons and ink
 * come from the charts. So every player standing at one board sees the same
 * board, and nothing about it has to travel beyond the maps themselves.
 */
public final class BoardImage {

    /** How many image pixels are averaged into one facet, on a side. */
    public static final int SUPERSAMPLE = 6;

    /** The largest image baked, whatever grid is asked for. */
    private static final int MAX_PIXELS = 384;

    /** How many boards' faces are kept. */
    private static final int CACHE_LIMIT = 12;

    /** The paper a board's face is, where no map covers it. */
    private static final Color PAPER = new Color(214, 201, 170);

    /** The line drawn round each pinned map, so the join can be read. */
    private static final Color SHEET_EDGE = new Color(120, 102, 74, 110);

    /** One baked face: the grid, and the state it was baked from. */
    private record Face(int[] cells, int grid, String signature) {}

    private static final Map<Long, Face> CACHE = new LinkedHashMap<>(8, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Long, Face> eldest) {
            return size() > CACHE_LIMIT;
        }
    };

    private BoardImage() {}

    /** Forget every face — what a test calls. */
    public static synchronized void invalidate() { CACHE.clear(); }

    /**
     * The board's face as {@code grid × grid} packed {@code 0xRRGGBB}, row
     * major, north up — or {@code null} when there is nothing to show yet.
     *
     * <p>{@code null} means one of two things and the caller treats them alike:
     * nothing is pinned to this board, so it is bare timber; or the ground it
     * needs is still being painted on {@link ChartImage}'s worker, so it is bare
     * timber for another frame or two.
     *
     * <p>Safe to call every frame for every board in sight: a hit is a map
     * lookup and a string comparison, and the string is what makes a stroke of
     * a pen appear on the timber without anybody having to invalidate anything.
     */
    public static synchronized int[] cells(TerrainField field, Cartography maps,
                                           Cartography.Board board, int grid) {
        if (field == null || maps == null || board == null || grid < 2) return null;
        List<Chart> pinned = maps.pinnedTo(board.id());
        if (pinned.isEmpty()) return null;
        double[] bounds = maps.bounds(board.id());
        if (bounds == null) return null;

        String signature = signatureOf(pinned, grid);
        Face cached = CACHE.get(board.id());
        if (cached != null && cached.grid() == grid
                && cached.signature().equals(signature)) {
            return cached.cells();
        }

        // Largest first, so a detailed map of one corner lies on top of the
        // overview it belongs to — the same order the panel draws them in.
        List<Chart> sheets = new java.util.ArrayList<>(pinned);
        sheets.sort((a, b) -> Double.compare(b.radius(), a.radius()));

        // Every sheet's ground, gathered before anything is allocated. All or
        // nothing: a board with one of its maps missing would be a board with a
        // hole in it, and the cache is keyed on what is <em>pinned</em> rather
        // than on what happened to have been painted, so the hole would stick.
        // Doing it here rather than mid-bake is also what stops a board waiting
        // on ChartImage from allocating an offscreen image every frame.
        //
        // The loop finishes even once it knows the answer is no: asking is what
        // queues a map for painting, so stopping at the first one that is not
        // ready would put the board's maps on the worker one at a time, a few
        // frames apart, instead of all at once.
        BufferedImage[] ground = new BufferedImage[sheets.size()];
        boolean ready = true;
        for (int i = 0; i < sheets.size(); i++) {
            ground[i] = ChartImage.of(field, sheets.get(i));
            if (ground[i] == null) ready = false;
        }
        if (!ready) return null;

        int pixels = Math.min(MAX_PIXELS, grid * SUPERSAMPLE);
        double centreX = (bounds[0] + bounds[2]) / 2;
        double centreY = (bounds[1] + bounds[3]) / 2;
        // The square that contains the union, because a board is square and a
        // map drawn to fit a non-square hole is a map with its scale bar wrong.
        double span = Math.max(bounds[2] - bounds[0], bounds[3] - bounds[1]);
        MapInk.Frame frame = new MapInk.Frame(0, 0, pixels, centreX, centreY, span);

        BufferedImage baked;
        try (Offscreen bake = Offscreen.opaque(pixels, pixels, true)) {
            paint(bake.target(), sheets, ground, frame, pixels);
            baked = bake.image();
        }
        int[] cells = downsample(baked, grid);
        CACHE.put(board.id(), new Face(cells, grid, signature));
        return cells;
    }

    /** Draw the combined map: the ground, the joins, the icons and the ink. */
    private static void paint(DrawTarget target, List<Chart> sheets,
                              BufferedImage[] ground, MapInk.Frame frame, int pixels) {
        target.fillRect(0, 0, pixels, pixels, PAPER);
        for (int i = 0; i < sheets.size(); i++) {
            Chart chart = sheets.get(i);
            int left = frame.screenX(chart.minX());
            int top = frame.screenY(chart.minY());
            target.drawImage(ground[i], left, top, frame.screenX(chart.maxX()) - left,
                    frame.screenY(chart.maxY()) - top);
        }
        for (Chart chart : sheets) {
            int left = frame.screenX(chart.minX());
            int top = frame.screenY(chart.minY());
            target.drawRect(left, top, frame.screenX(chart.maxX()) - left,
                    frame.screenY(chart.maxY()) - top, SHEET_EDGE, SUPERSAMPLE);
        }

        // Icons and ink sized in <em>facets</em> rather than in pixels, because
        // facets are what a person standing at the board actually sees. An icon
        // is twelve pixels across at scale 1 and wants to be about two facets;
        // a stroke is two pixels wide and wants to be one, or it comes out as a
        // faint tint on a scattering of cells rather than as a line.
        double iconScale = SUPERSAMPLE * 2 / 12.0;
        double inkScale = SUPERSAMPLE / 2.0;
        for (Chart chart : sheets) {
            for (Chart.Landmark landmark : chart.landmarks()) {
                MapInk.icon(target, landmark.kind(), frame.screenX(landmark.x()),
                        frame.screenY(landmark.y()), iconScale);
            }
        }
        for (Chart chart : sheets) {
            for (Chart.Stroke stroke : chart.strokes()) {
                MapInk.stroke(target, frame, stroke, inkScale);
            }
            // A note is its dot without its words. Text baked at this size and
            // then averaged into facets is not small text, it is grey mush —
            // and reading it is what opening the map is for.
            for (Chart.Note note : chart.notes()) {
                MapInk.note(target, frame, note, iconScale, null);
            }
        }
    }

    /** Average the baked image down into one colour per facet. */
    private static int[] downsample(BufferedImage image, int grid) {
        int[] cells = new int[grid * grid];
        int pixels = image.getWidth();
        for (int cy = 0; cy < grid; cy++) {
            int y0 = cy * pixels / grid, y1 = Math.max(y0 + 1, (cy + 1) * pixels / grid);
            for (int cx = 0; cx < grid; cx++) {
                int x0 = cx * pixels / grid;
                int x1 = Math.max(x0 + 1, (cx + 1) * pixels / grid);
                long r = 0, g = 0, b = 0, n = 0;
                for (int y = y0; y < y1; y++) {
                    for (int x = x0; x < x1; x++) {
                        int argb = image.getRGB(x, y);
                        r += (argb >> 16) & 0xFF;
                        g += (argb >> 8) & 0xFF;
                        b += argb & 0xFF;
                        n++;
                    }
                }
                cells[cy * grid + cx] = (int) ((r / n) << 16 | (g / n) << 8 | (b / n));
            }
        }
        return cells;
    }

    /**
     * What this board's face depends on, as a string.
     *
     * <p>Cheap to build every frame and exact about what matters: which maps
     * are up, where they are, how big they are, and how many marks are on them.
     * A stroke of a pen changes the count, so the face is rebaked on the frame
     * the ink arrives — which is what makes drawing on a map something the rest
     * of the party watches happen on the board across the clearing.
     */
    private static String signatureOf(List<Chart> pinned, int grid) {
        StringBuilder sb = new StringBuilder().append(grid);
        for (Chart chart : pinned) {
            sb.append('|').append(chart.id()).append(',').append(chart.centreX())
                    .append(',').append(chart.centreY()).append(',').append(chart.radius())
                    .append(',').append(chart.marks());
        }
        return sb.toString();
    }
}
