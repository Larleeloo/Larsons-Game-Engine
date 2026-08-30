package com.larsons.engine.watch.render;

import com.larsons.engine.graphics.draw.DrawTarget;
import com.larsons.engine.watch.Chart;

import java.awt.Color;
import java.awt.Font;

/**
 * Everything drawn <em>on</em> a map: where a metre lands in pixels, the icons,
 * the pen and the notes.
 *
 * <h2>Why this is not in the panel</h2>
 *
 * <p>A map is drawn in two completely different places — on the screen, by
 * {@code demo.MapPanel}, and onto the face of a {@linkplain BoardImage map
 * board} standing in the world — and the two have to produce <b>the same
 * map</b>. Not a similar one: the board is what a party reads at a glance and
 * the panel is what they open to check, so an icon that means one thing on the
 * board and another on the screen is worse than no icon at all.
 *
 * <p>Nothing here knows which of the two it is drawing into. It is handed a
 * {@link DrawTarget} and a {@link Frame} and it draws; the panel's target is
 * the screen and the board's is an offscreen image that is then sampled into
 * facets.
 *
 * <h2>Everything scales</h2>
 *
 * <p>The board's image is baked several times larger than the grid of facets it
 * becomes, so that a hairline stroke survives being averaged down into cells
 * rather than disappearing. So every size here is a multiple of {@code scale}
 * rather than a pixel count: at {@code 1} an icon is the twelve pixels the
 * panel wants, at {@code 8} it is what a board's bake needs for the same icon
 * to still read after the downsample.
 */
public final class MapInk {

    /** The outline every icon is drawn with, so it reads on any ground. */
    public static final Color OUTLINE = new Color(38, 32, 24);

    /** The scrap of paper a note's text is written on. */
    private static final Color LABEL = new Color(232, 224, 202, 220);

    private MapInk() {}

    /**
     * Where a rectangle of the world sits in a rectangle of pixels.
     *
     * <p>The whole of a map's geometry, in one place, for
     * {@code WatchScene.SatchelBox}'s reason: the drawing and the hit testing
     * have to agree exactly, and geometry written twice is a panel that erases
     * the mark next to the one you clicked on.
     *
     * <p>North is up, and north is <b>−y</b>: the game's forward is
     * {@code (sin yaw, −cos yaw)}, so a walker facing yaw zero walks toward
     * smaller y and a map drawn north-up has −y at the top. Getting that
     * backwards would put every label on the wrong side of the world, so it is
     * decided here and nowhere else.
     */
    public record Frame(int x, int y, int size, double centreX, double centreY,
                        double span) {

        /** Pixels per metre. */
        public double scale() { return size / span; }

        public int screenX(double worldX) {
            return (int) Math.round(x + (worldX - centreX) / span * size + size / 2.0);
        }

        public int screenY(double worldY) {
            return (int) Math.round(y + (worldY - centreY) / span * size + size / 2.0);
        }

        public double worldX(int screenX) {
            return centreX + (screenX - x - size / 2.0) / size * span;
        }

        public double worldY(int screenY) {
            return centreY + (screenY - y - size / 2.0) / size * span;
        }

        /** Whether a point in pixels is on the paper. */
        public boolean holds(int mx, int my) {
            return mx >= x && mx < x + size && my >= y && my < y + size;
        }
    }

    /**
     * One icon.
     *
     * <p>Drawn rather than lettered. A map with "S" on it for a shop is a map
     * with a legend you have to keep reading; a little house is a house. Each
     * shape is a handful of primitives, which is cheaper than an atlas and — the
     * real reason — cannot be broken by a texture pack that has never heard of
     * maps.
     *
     * @param scale {@code 1} for the panel's own size; larger for a bake that
     *              is going to be averaged down
     */
    public static void icon(DrawTarget target, Chart.Kind kind, int x, int y,
                            double scale) {
        Color colour = new Color(kind.rgb());
        float line = (float) Math.max(1, scale);
        switch (kind) {
            case SHOP -> {
                // A hut with a pitched roof.
                target.fillRect(x - s(4, scale), y - s(1, scale), s(9, scale),
                        s(6, scale), colour);
                target.fillPolygon(xs(x, scale, -6, 0, 7), ys(y, scale, -1, -7, -1),
                        3, colour);
                target.drawRect(x - s(4, scale), y - s(1, scale), s(9, scale),
                        s(6, scale), OUTLINE, line);
            }
            case CAMP -> {
                // A tent.
                int[] px = xs(x, scale, -6, 0, 6);
                int[] py = ys(y, scale, 5, -6, 5);
                target.fillPolygon(px, py, 3, colour);
                target.drawPolygon(px, py, 3, OUTLINE, line);
            }
            case FEEDER -> {
                // A post with a tray on it.
                target.fillRect(x - s(1, scale), y - s(2, scale), s(2, scale),
                        s(7, scale), OUTLINE);
                target.fillRect(x - s(5, scale), y - s(4, scale), s(11, scale),
                        s(3, scale), colour);
                target.drawRect(x - s(5, scale), y - s(4, scale), s(11, scale),
                        s(3, scale), OUTLINE, line);
            }
            case PLANTING -> {
                // A little tree.
                target.fillRect(x - s(1, scale), y, s(2, scale), s(5, scale),
                        new Color(90, 66, 44));
                target.fillOval(x - s(5, scale), y - s(7, scale), s(11, scale),
                        s(9, scale), colour);
                target.drawOval(x - s(5, scale), y - s(7, scale), s(11, scale),
                        s(9, scale), OUTLINE, line);
            }
            case BOAT -> {
                // A hull, seen from above.
                int[] px = xs(x, scale, -6, 0, 6, 0);
                int[] py = ys(y, scale, 0, -4, 0, 4);
                target.fillPolygon(px, py, 4, colour);
                target.drawPolygon(px, py, 4, OUTLINE, line);
            }
            case SIGHTING -> {
                // A ring with a dot in it: the mark somebody puts beside a bird.
                target.drawOval(x - s(5, scale), y - s(5, scale), s(10, scale),
                        s(10, scale), colour, line * 2);
                target.fillOval(x - s(2, scale), y - s(2, scale), s(4, scale),
                        s(4, scale), colour);
            }
            case SUMMIT -> {
                // A peak, with the shaded face every map draws on one.
                int[] px = xs(x, scale, -6, 0, 6);
                int[] py = ys(y, scale, 4, -6, 4);
                target.fillPolygon(px, py, 3, colour);
                target.fillPolygon(xs(x, scale, 0, 6, 2), ys(y, scale, -6, 4, 4), 3,
                        new Color(70, 66, 60));
                target.drawPolygon(px, py, 3, OUTLINE, line);
            }
        }
    }

    /** One pen stroke, in world metres, as a run of lines. */
    public static void stroke(DrawTarget target, Frame frame, Chart.Stroke stroke,
                              double scale) {
        Color colour = new Color(stroke.ink().rgb());
        double[] xs = stroke.xs(), ys = stroke.ys();
        float width = (float) Math.max(1, 2 * scale);
        for (int i = 1; i < xs.length; i++) {
            target.drawLine(frame.screenX(xs[i - 1]), frame.screenY(ys[i - 1]),
                    frame.screenX(xs[i]), frame.screenY(ys[i]), colour, width);
        }
    }

    /**
     * One note: a dot where it was written, and its words beside it.
     *
     * <p>A {@code null} font draws the dot alone, which is what a board wants.
     * Text baked at eight times its size and then averaged down into facets is
     * not small text, it is grey mush — so the board shows <em>that somebody
     * wrote here</em>, and reading it is what opening the map is for.
     */
    public static void note(DrawTarget target, Frame frame, Chart.Note note,
                            double scale, Font font) {
        int x = frame.screenX(note.x()), y = frame.screenY(note.y());
        Color colour = new Color(note.ink().rgb());
        target.fillOval(x - s(3, scale), y - s(3, scale), s(6, scale), s(6, scale),
                colour);
        if (font == null) return;
        int width = target.textWidth(note.text(), font);
        // On its own scrap of paper: a map has a picture under it, and dark
        // text straight onto dark wood reads as nothing.
        target.fillRect(x + 6, y - 11, width + 8, 15, LABEL);
        target.drawText(note.text(), x + 10, y + 1, font, colour);
    }

    private static int s(int pixels, double scale) {
        return (int) Math.max(1, Math.round(pixels * scale));
    }

    private static int[] xs(int x, double scale, int... offsets) {
        int[] out = new int[offsets.length];
        for (int i = 0; i < offsets.length; i++) {
            out[i] = x + (int) Math.round(offsets[i] * scale);
        }
        return out;
    }

    private static int[] ys(int y, double scale, int... offsets) {
        return xs(y, scale, offsets);
    }
}
