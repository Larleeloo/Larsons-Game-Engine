package com.larsons.engine.watch.render;

import com.larsons.engine.watch.world.ChunkStreamer;
import com.larsons.engine.watch.world.TrackField;
import com.larsons.engine.watch.world.WatchBiome;
import com.larsons.engine.watch.world.WatchMaterial;
import com.larsons.engine.watch.world.WatchMaterials;

import java.util.List;

/**
 * A {@link TrackField}'s record of where the party walked, turned into the
 * triangles that draw it.
 *
 * <h2>A decal, not terrain</h2>
 *
 * <p><b>A trodden track does not change the ground, and it must not.</b> The
 * heightfield is a pure function of the seed and the coordinate — that is the
 * load-bearing assumption behind the whole world, the reason eight machines
 * agree about a hillside without exchanging a byte, and the reason a chunk can
 * be thrown away and rebuilt identically an hour later. Cutting a player's
 * footprints into it would mean giving that up, and it would mean re-meshing
 * whatever chunk anybody is standing in every few strides, which is the single
 * most expensive thing this game can be asked to do.
 *
 * <p>So a track is a <em>sheet laid over</em> the ground instead:
 * {@value #LIFT} metres above it, translucent, in the biome's own trail
 * material. The generated {@link com.larsons.engine.watch.world.TrailNetwork}
 * cuts the terrain; this only shades it. That is also the honest picture of
 * what the two things are — one is a path the wood has, the other is a mark you
 * left on it.
 *
 * <h2>One strip per walker, not one quad per print</h2>
 *
 * <p>Each walker's prints are meshed as a continuous strip whose cross-section
 * at every print is square to the <em>average</em> of the way the walker came
 * in and the way they went out. Quads built one per segment from their own
 * direction would be a chain of separate rectangles: they leave a wedge of
 * bare ground on the outside of every turn and overlap on the inside, and
 * because these are translucent, an overlap is a visibly darker patch. A shared
 * cross-section has neither — the strip bends.
 *
 * <p>Two different walkers <em>do</em> overlap, and are meant to: two
 * translucent passes over the same ground composite to something darker than
 * one, which is exactly what happens to ground two people have walked over. It
 * costs nothing to arrange because it is what alpha does.
 */
public final class TrackMesher {

    /**
     * How far above the ground the sheet floats, in metres.
     *
     * <p>Enough to clear two things, and the second is the one that sets it.
     * The first is depth precision on a card, which with a near plane as far
     * out as {@link com.larsons.engine.graphics.EyeCamera#NEAR} is millimetres
     * at any distance a track is drawn at, so this is never a tie. The second
     * is that the sheet's corners take a <em>bilinear</em> read of the
     * heightfield while the ground under them is drawn as flat triangles, and
     * the two disagree across a saddle-shaped quad by a quarter of however much
     * it is saddled — centimetres, on ordinary ground. The sheet has to clear
     * that, or it sinks into the hillside in patches.
     *
     * <p>It is small enough to be invisible: at eye height, nine centimetres of
     * float under something lying at your feet is a shadow's worth of gap.
     */
    public static final double LIFT = 0.09;

    /**
     * How far toward the eye a track is sorted on the painter path, in metres.
     *
     * <p>Half a ground quad and a little more — see {@link Mesh#sortBias()} for
     * what this buys and what it costs. It is in metres of depth rather than in
     * quads because the renderer sorts in metres; the number to beat is the
     * distance from a terrain triangle's <em>middle</em> to its far corner,
     * which on the two-metre grid the nearest ground is meshed at is 1.49 m in
     * the plane and rather less than that once projected onto the view.
     *
     * <p><b>The failure it prevents is not subtle.</b> Counting the pixels one
     * frame's trail actually reaches, over a walk through a bamboo thicket:
     *
     * <pre>
     *   bias    pixels of trail drawn
     *    0.0           27 742      broken into disconnected blocks
     *    0.8           48 219      whole, with notches in it
     *    1.6           53 674      whole
     * </pre>
     *
     * <p>Half the trail missing at zero, and missing in <em>rectangles</em>
     * aligned to the terrain grid, which is the shape that reads as a rendering
     * fault rather than as a faint path.
     */
    public static final double SORT_BIAS = 1.6;

    /**
     * How opaque a fresh track is.
     *
     * <p><b>Slight, as asked, and the ceiling matters more than the curve.</b>
     * The brief is a pathway that reads as having been walked over, not a road
     * painted on the meadow — and because two passes over the same ground
     * composite, the honest way to make a much-used route look much-used is to
     * keep one pass faint and let the arithmetic do the rest. At this value a
     * single crossing is something you notice when you look for it and four
     * crossings are unmistakable, which is the right way round.
     */
    private static final double FRESH_ALPHA = 0.40;

    /** Below this alpha a segment is not worth a quad. */
    private static final int MIN_ALPHA = 6;

    private TrackMesher() {}

    /**
     * Build the sheet for every track within {@code radius} metres of
     * {@code (px, py)}.
     *
     * @param ground where the height and the biome under each corner come from
     *               — the streamer rather than the generator, so the sheet lies
     *               on the triangles that are actually drawn rather than on the
     *               exact surface they approximate
     * @param revision stamped into the mesh, so a backend re-uploads when this
     *                 is rebuilt and leaves the buffer alone when it is not
     */
    public static Mesh tracks(TrackField tracks, ChunkStreamer ground,
                              double px, double py, double radius, int revision) {
        // Snapped to the metre, for the same reason the dynamic mesh is: the
        // origin has to move so the floats stay precise a long way out, and a
        // backend caching by origin should re-upload on a step rather than on a
        // frame. See GlMeshPass.Buffer.originX.
        double ox = Math.floor(px), oy = Math.floor(py);
        Mesh.Builder mesh = Mesh.builder(ox, oy, 0, true, revision).sortBias(SORT_BIAS);
        if (tracks == null || ground == null) return mesh.build();
        float[] uv = new float[4];
        for (List<TrackField.Print> chain : tracks.trails()) {
            strip(mesh, tracks, ground, chain, px, py, radius * radius, ox, oy, uv);
        }
        return mesh.build();
    }

    /** One walker's trail, as a strip that bends through its own prints. */
    private static void strip(Mesh.Builder mesh, TrackField tracks, ChunkStreamer ground,
                              List<TrackField.Print> chain, double px, double py,
                              double reachSquared, double ox, double oy, float[] uv) {
        int n = chain.size();
        if (n < 2) return;
        double[] offX = new double[n], offY = new double[n];
        for (int i = 0; i < n; i++) crossSection(tracks, chain, i, offX, offY);

        for (int i = 0; i + 1 < n; i++) {
            TrackField.Print a = chain.get(i), b = chain.get(i + 1);
            if (!TrackField.joined(a, b)) continue;
            // Either end without a width is a print with no direction — see
            // crossSection — and a quad built on one is inside out rather than
            // narrow.
            if (offX[i] == 0 && offY[i] == 0) continue;
            if (offX[i + 1] == 0 && offY[i + 1] == 0) continue;
            double mx = (a.x() + b.x()) / 2, my = (a.y() + b.y()) / 2;
            double dx = mx - px, dy = my - py;
            if (dx * dx + dy * dy > reachSquared) continue;

            // The younger end dates the segment: it is the stride that laid it,
            // and dating it from the older end would make the trail behind a
            // walker who stopped for five minutes fade from the wrong place.
            double fresh = tracks.freshnessOf(b);
            int alpha = (int) Math.round(fresh * FRESH_ALPHA * 255);
            if (alpha < MIN_ALPHA) continue;

            WatchBiome biome = ground.biomeAt(mx, my);
            WatchMaterial material = biome.trail();
            WatchMaterials.uv(material, uv);
            int argb = (alpha << 24) | (WatchMaterials.shade(material) & 0xFFFFFF);

            // Wound the way every up-facing quad in this renderer is wound —
            // across the strip, then along it — so the face's normal points at
            // the sky and the painter's back-face test keeps it. See
            // TerrainMesher.emit for where the sign of that test comes from.
            quad(mesh, ground, ox, oy, uv, argb,
                    a.x() - offX[i], a.y() - offY[i],
                    b.x() - offX[i + 1], b.y() - offY[i + 1],
                    b.x() + offX[i + 1], b.y() + offY[i + 1],
                    a.x() + offX[i], a.y() + offY[i]);
        }
    }

    /**
     * How far, and which way, the strip is offset from the walker's line at one
     * print — the perpendicular of the average of the directions in and out,
     * scaled by how wide a track of that age still is.
     *
     * <p>Averaging the two <em>unit</em> directions rather than the two
     * displacements is what makes a long stride and a short one meeting at a
     * corner bend it by the same amount; the alternative lets the longer of the
     * two decide where the corner points.
     *
     * <p>A true mitre would also lengthen the offset by the secant of the
     * half-angle, which is what stops a corner narrowing. It is left out
     * because of what a stride is: {@link TrackField#STRIDE} metres, and nobody
     * turns ninety degrees inside one, so the ordinary bend is a few degrees
     * and a couple of centimetres of narrowing. A genuine hairpin does pinch —
     * and a track that is slightly narrower where somebody turned on it reads
     * as a corner that has been walked round, which is what it is.
     */
    private static void crossSection(TrackField tracks, List<TrackField.Print> chain,
                                     int i, double[] offX, double[] offY) {
        TrackField.Print here = chain.get(i);
        double ux = 0, uy = 0;
        if (i > 0) {
            TrackField.Print before = chain.get(i - 1);
            if (TrackField.joined(before, here)) {
                double dx = here.x() - before.x(), dy = here.y() - before.y();
                double length = Math.sqrt(dx * dx + dy * dy);
                if (length > 1e-9) {
                    ux += dx / length;
                    uy += dy / length;
                }
            }
        }
        if (i + 1 < chain.size()) {
            TrackField.Print after = chain.get(i + 1);
            if (TrackField.joined(here, after)) {
                double dx = after.x() - here.x(), dy = after.y() - here.y();
                double length = Math.sqrt(dx * dx + dy * dy);
                if (length > 1e-9) {
                    ux += dx / length;
                    uy += dy / length;
                }
            }
        }
        double length = Math.sqrt(ux * ux + uy * uy);
        if (length < 1e-9) {
            // A print with no direction — an isolated one, or the exact
            // doubling back of a walker who turned round on the spot. It
            // contributes no width and the segments either side of it are
            // skipped rather than drawn inside out.
            offX[i] = 0;
            offY[i] = 0;
            return;
        }
        double fresh = tracks.freshnessOf(here);
        double half = TrackField.HALF_WIDTH * TrackField.widthOf(fresh);
        offX[i] = -uy / length * half;
        offY[i] = ux / length * half;
    }

    /** One quad of the sheet, each corner sitting on its own patch of ground. */
    private static void quad(Mesh.Builder mesh, ChunkStreamer ground, double ox, double oy,
                             float[] uv, int argb,
                             double ax, double ay, double bx, double by,
                             double cx, double cy, double dx, double dy) {
        mesh.quad((float) (ax - ox), (float) (ay - oy), lift(ground, ax, ay),
                (float) (bx - ox), (float) (by - oy), lift(ground, bx, by),
                (float) (cx - ox), (float) (cy - oy), lift(ground, cx, cy),
                (float) (dx - ox), (float) (dy - oy), lift(ground, dx, dy),
                uv, argb);
    }

    private static float lift(ChunkStreamer ground, double x, double y) {
        return (float) (ground.groundAt(x, y) + LIFT);
    }
}
