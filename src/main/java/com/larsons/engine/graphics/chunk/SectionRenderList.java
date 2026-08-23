package com.larsons.engine.graphics.chunk;

import com.larsons.engine.graphics.Frustum;

import java.util.ArrayList;
import java.util.List;

/**
 * Which sections a frame draws, and in what order — <b>Minecraft Java's chunk
 * graph walk</b>.
 *
 * <p><b>A breadth-first search out from the camera, not a sweep of a disc.</b>
 * The world is treated as a graph whose nodes are sections and whose edges are
 * the faces between them, and a step is allowed only when three things hold:
 *
 * <ul>
 *   <li>the section you are leaving can <em>see</em> from the face you came in
 *       by to the face you are leaving by ({@link SectionVisibility}), which is
 *       what stops the walk at the far wall of a cave;</li>
 *   <li>the step does not double back — once the walk has gone east it never
 *       goes west again, which is what stops it wandering round behind the
 *       player through a corridor and drawing the world twice;</li>
 *   <li>the section arrived at is inside the frustum and inside the render
 *       distance.</li>
 * </ul>
 *
 * <p>Standing on a plain every section is open, nothing is culled that the
 * frustum would not have culled, and the walk costs a queue push per section.
 * Standing in a tunnel it draws the tunnel. That difference — an order of
 * magnitude, underground — is why the walk exists, and no frustum can find it,
 * because the sections behind the wall really are in front of the camera.
 *
 * <h2>What it is not</h2>
 *
 * <p><b>It is conservative, and knowingly so.</b> The connectivity is recorded
 * per <em>face</em>, not per point on a face, so a section split down the
 * middle by a wall reports that its east face can see its north face — which is
 * true of the half of the east face on the northern side and false of the
 * other. A walk that arrives through the southern half is therefore allowed to
 * leave northward, and a wall running clean across the world leaks around
 * itself one section at a time. Minecraft's has the same shape and the same
 * leak, for the same reason: the alternative is tracking where on a face a ray
 * entered, which is a portal renderer and costs more than it saves.
 *
 * <p>What it does catch is the case worth catching — <b>a section with no empty
 * cell in it connects nothing to anything</b> — so being inside a mountain, a
 * cave, a building or a mine draws what you are inside of and stops. A cull is
 * allowed to be too generous; the failure it must not have is dropping
 * something the player can see, and under-claiming can never do that.
 *
 * <p><b>Breadth-first is also the draw order.</b> It comes out sorted by steps
 * from the camera, which is near-to-far, which is what an opaque pass wants: a
 * near section drawn first fills the depth buffer and the fragments of
 * everything behind it are thrown away before they are shaded. The translucent
 * pass wants the reverse and gets it by walking the same list backwards.
 */
public final class SectionRenderList {

    /** Sections the walk may visit in one frame, whatever else is true. */
    private static final int MAX_VISITS = 300_000;

    /** One section to draw: where it is, and its mesh. */
    public record Visible(int sx, int sy, int sz, SectionMesh mesh) {}

    private final List<Visible> visible = new ArrayList<>();

    /**
     * <b>The walk allocates nothing, and at this scale that is most of its
     * cost.</b>
     *
     * <p>A ninety-chunk view puts tens of thousands of sections through this
     * loop every frame. The obvious spelling — a {@code HashSet<Long>} for what
     * has been seen and an {@code ArrayDeque<long[]>} for the queue — boxes a
     * {@code Long} and allocates a four-element array for every one of them,
     * which is a few million objects a second handed to the collector to prove
     * that a section was already visited. Measured at thirty-two chunks it was
     * <b>6.1 ms a frame</b>, worst case 14.5, against a whole frame budget of
     * 8.3 at 120 Hz.
     *
     * <p>So: an open-addressed set of {@code long} keys with linear probing, and
     * a ring of {@code int}s four to a node. Both are reused between frames and
     * both grow to whatever the largest frame needed and then stop. The
     * arithmetic is identical; the garbage is gone.
     */
    private long[] seenKeys = new long[1 << 14];
    private int seenCount;
    private int seenMask = seenKeys.length - 1;

    /** {@code sx, sy, sz, from} per node, head and tail chasing each other. */
    private int[] queue = new int[1 << 16];
    private int head, tail;

    /** The sections to draw, nearest first. */
    public List<Visible> visible() { return visible; }

    /**
     * How many sections the last walk stepped into, drawn or not.
     *
     * <p>The number that predicts what a walk costs, where {@link #visible} is
     * the number that predicts what the draw costs. They are very different: the
     * sky over a mountain range is thousands of sections that are stepped into,
     * found empty, and walked straight through.
     */
    public int visits() { return visits; }

    /**
     * How many of those had no mesh yet — <b>the measure of whether the world at
     * this radius has arrived.</b>
     *
     * <p>What a growing render distance has to wait for, and the only honest
     * signal for it. The count of sections <em>drawn</em> reads low both when
     * there is little to draw and when there is plenty but none of it is built,
     * and those two want opposite decisions: the first is an invitation to reach
     * further, the second is the worst possible moment to. Counting the misses
     * separates them. See {@code DetailReach}.
     */
    public int unbuilt() { return unbuilt; }

    private int visits;
    private int unbuilt;

    /**
     * Walk the graph.
     *
     * @param sections where meshes come from, and where missing ones are queued
     * @param frustum  the camera's six planes
     * @param tileSize world units per cell
     * @param eyeX     the camera, in world units
     * @param reach    how far to walk, in world units
     * @param minY     the lowest section index the world has (usually 0)
     * @param maxY     one past the highest
     */
    public void build(TerrainSections sections, Frustum frustum, int tileSize,
                      double eyeX, double eyeY, double eyeZ, double reach,
                      int minY, int maxY) {
        visible.clear();
        clearSeen();
        clearBandMemo();
        head = tail = 0;

        double span = SectionMesh.SIZE * (double) tileSize;
        int startX = (int) Math.floor(eyeX / span);
        int startZ = (int) Math.floor(eyeY / span);
        int startY = (int) Math.floor(eyeZ / span);
        startY = Math.max(minY, Math.min(maxY - 1, startY));
        sections.beginFrame(startX, startY, startZ);

        double reachSq = reach * reach;
        // The section the camera is in enters through no face at all, which is
        // what the −1 means: from inside, every face is a way out.
        push(startX, startY, startZ, -1);
        markSeen(TerrainSections.key(startX, startY, startZ));

        visits = 0;
        unbuilt = 0;
        while (head != tail && visits++ < MAX_VISITS) {
            int sx = queue[head], sy = queue[head + 1];
            int sz = queue[head + 2], from = queue[head + 3];
            head = (head + 4) & (queue.length - 1);

            SectionMesh mesh = sections.mesh(sx, sy, sz);
            SectionVisibility through = mesh != null
                    ? mesh.visibility()
                    // A section not meshed yet is treated as open. It has to be:
                    // assuming it solid would stop the walk at the edge of what
                    // has been built and the world would fill in one ring per
                    // frame, which is a visible crawl outward every time the
                    // player turns round.
                    : SectionVisibility.OPEN;
            if (mesh == null) unbuilt++;
            if (mesh != null && !mesh.isEmpty()) {
                visible.add(new Visible(sx, sy, sz, mesh));
            }

            for (int face = 0; face < SectionVisibility.FACES; face++) {
                if (!through.canSee(from, face)) continue;
                // No doubling back: a step out of the face the walk came in by
                // is a step toward the camera.
                if (from >= 0 && face == SectionVisibility.opposite(from)) continue;
                int nx = sx + SectionVisibility.step(face, 0);
                int nz = sz + SectionVisibility.step(face, 1);
                int ny = sy + SectionVisibility.step(face, 2);
                if (ny < minY || ny >= maxY) continue;
                // Not up through the sky. A column knows the band it actually
                // holds geometry in, and above a mountain that is a few sections
                // out of twenty — the rest is air the walk would step into, ask
                // about, and step out of. See TerrainSections.withinBand.
                if (!inBand(sections, nx, ny, nz)) continue;
                long key = TerrainSections.key(nx, ny, nz);
                if (!markSeen(key)) continue;

                double x0 = nx * span, y0 = nz * span, z0 = ny * span;
                // The <b>nearest</b> corner of the section, not its centre.
                // Measuring from the centre throws away a section whose near
                // half is well inside the reach, which leaves a ring half a
                // section wide that the detail pass has dropped and the
                // level-of-detail tree — which starts exactly at the reach —
                // has not yet picked up. That ring is bare sky, and it moves
                // with the camera, which is what a flashing horizon looks like.
                double cx = eyeX < x0 ? x0 - eyeX : Math.max(0, eyeX - (x0 + span));
                double cy = eyeY < y0 ? y0 - eyeY : Math.max(0, eyeY - (y0 + span));
                if (cx * cx + cy * cy > reachSq) continue;
                if (!frustum.boxVisible(x0, y0, z0, x0 + span, y0 + span, z0 + span)) continue;
                push(nx, ny, nz, SectionVisibility.opposite(face));
            }
        }
    }

    // --- the band memo ---------------------------------------------------------------

    /**
     * A one-frame, direct-mapped memo of {@link TerrainSections#withinBand}.
     *
     * <p><b>Because the saving and the cost were the same size without it.</b>
     * The band is asked once per <em>step</em> — six per section — and the
     * answer lives in a {@code ConcurrentHashMap} keyed by a boxed
     * {@code Long}, so skipping the sky cost a hash and an allocation per step
     * to save a hash and an allocation per section. Measured, the walk got
     * slower: 5 664 visits at 2.28 ms became 3 467 visits at 6.20.
     *
     * <p>A column is asked about several times in quick succession — once per
     * vertical neighbour, and again from each of its horizontal ones — and a
     * breadth-first walk works through columns in a run, so a small
     * direct-mapped table hits nearly always and costs one multiply and one
     * array read when it does. Collisions simply re-ask; there is nothing to
     * evict and nothing to be correct about beyond the answer itself.
     *
     * <p>Cleared each frame, so a band that widened while the meshers caught up
     * is picked up on the next one rather than being remembered wrongly for the
     * rest of the session.
     */
    private static final int BAND_MEMO = 1 << 12;
    private final long[] bandKey = new long[BAND_MEMO];
    private final int[] bandLow = new int[BAND_MEMO];
    private final int[] bandHigh = new int[BAND_MEMO];

    private boolean inBand(TerrainSections sections, int sx, int sy, int sz) {
        // +1 so that column (0,0) — a real column — is not the empty marker.
        long key = (((long) sx << 32) ^ (sz & 0xFFFFFFFFL)) + 1;
        int at = (int) (mix(key) & (BAND_MEMO - 1));
        if (bandKey[at] != key) {
            bandKey[at] = key;
            long band = sections.band(sx, sz);
            bandLow[at] = (int) (band >> 32);
            bandHigh[at] = (int) band;
        }
        return sy >= bandLow[at] && sy <= bandHigh[at];
    }

    private void clearBandMemo() {
        java.util.Arrays.fill(bandKey, 0L);
    }

    // --- the two primitive collections ----------------------------------------------

    private void push(int sx, int sy, int sz, int from) {
        queue[tail] = sx;
        queue[tail + 1] = sy;
        queue[tail + 2] = sz;
        queue[tail + 3] = from;
        tail = (tail + 4) & (queue.length - 1);
        if (tail == head) growQueue();
    }

    /** Unwrap into a buffer twice the size; the ring is full at this point. */
    private void growQueue() {
        int[] bigger = new int[queue.length * 2];
        // head == tail means full, so the whole array is live, starting at head.
        for (int i = 0; i < queue.length; i++) {
            bigger[i] = queue[(head + i) & (queue.length - 1)];
        }
        head = 0;
        tail = queue.length;
        queue = bigger;
    }

    /**
     * Record a section as visited; {@code false} if it already was.
     *
     * <p>Open addressing with linear probing over a power-of-two table. Zero is
     * the empty slot and is also a legal key — the section at the world's
     * origin — so that one is tracked in a flag of its own rather than costing
     * every other lookup a comparison.
     */
    private boolean markSeen(long key) {
        if (key == 0) {
            if (seenZero) return false;
            seenZero = true;
            seenCount++;
            return true;
        }
        int at = (int) (mix(key) & seenMask);
        while (true) {
            long there = seenKeys[at];
            if (there == 0) {
                seenKeys[at] = key;
                if (++seenCount * 2 > seenKeys.length) growSeen();
                return true;
            }
            if (there == key) return false;
            at = (at + 1) & seenMask;
        }
    }

    private boolean seenZero;

    private void clearSeen() {
        // Only worth wiping what was written: a frame that visited four hundred
        // sections should not touch a table sized for a hundred thousand.
        if (seenCount > 0) java.util.Arrays.fill(seenKeys, 0L);
        seenCount = 0;
        seenZero = false;
    }

    private void growSeen() {
        long[] bigger = new long[seenKeys.length * 2];
        int mask = bigger.length - 1;
        for (long key : seenKeys) {
            if (key == 0) continue;
            int at = (int) (mix(key) & mask);
            while (bigger[at] != 0) at = (at + 1) & mask;
            bigger[at] = key;
        }
        seenKeys = bigger;
        seenMask = mask;
    }

    /**
     * A spreading hash. The section keys are three coordinates packed into
     * fixed bit ranges, so their low bits alone are one axis — a table indexed
     * by those would put a whole column of world in one probe chain.
     */
    private static long mix(long key) {
        long h = key * 0x9E3779B97F4A7C15L;
        h ^= h >>> 32;
        return h;
    }
}
