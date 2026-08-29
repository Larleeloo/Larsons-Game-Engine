package com.larsons.engine.watch.world;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Where the party has walked — <b>a path the players make, that the wood takes
 * back in ten minutes.</b>
 *
 * <p>{@link TrailNetwork} is the wood's own path system: generated from the
 * seed, the same for everybody, and there before anybody arrived. This is the
 * other kind of path, and the two are deliberately not the same mechanism. A
 * trodden track is a fact about <em>history</em> rather than about position, so
 * it cannot be a function of the seed, and it is the only thing in this world
 * that is not.
 *
 * <h2>What it is for</h2>
 *
 * <p>Walking somewhere in a wood with no edge and no map is walking somewhere
 * you cannot afterwards find again. A track that lasts
 * {@value #LIFETIME} seconds is exactly long enough to answer the three
 * questions a walk actually asks — <em>have I already swept this hollow</em>,
 * <em>which way did the others go</em>, and <em>how do I get back to the lake I
 * just left</em> — and short enough that an afternoon in one valley does not end
 * with the valley looking like a car park. It is not a minimap and it is not
 * breadcrumbs you place: it is what your own boots did, drawn on the ground
 * where you did it.
 *
 * <h2>Nothing about this travels</h2>
 *
 * <p>A track record is not in the snapshot and there is no message for one. It
 * does not have to be: every walker's position is <em>already</em> in every
 * snapshot, twenty times a second, for the whole party — so both ends watch the
 * same feet and each keeps its own copy of where they went. That is the same
 * bargain {@link com.larsons.engine.watch.Boats} and
 * {@code Shops} strike from the other direction; they derive a shared answer
 * from the seed, and this derives one from a stream both ends were being sent
 * anyway.
 *
 * <p>The cost of that bargain is honest and bounded: <b>somebody who joins
 * halfway through sees the last ten minutes fill in rather than arriving</b>,
 * because they were not being told where anybody was while they were in the
 * lobby. Ten minutes later every machine agrees again. Sending it instead would
 * mean a few thousand coordinates in a five-second world sync — for a
 * decoration — which is not a trade this game makes.
 *
 * <h2>What is stored</h2>
 *
 * <p>One chain of {@linkplain Print prints} per walker, oldest first. A print
 * is laid when its walker has moved {@value #STRIDE} metres from the last one,
 * so the record is proportional to <em>distance walked</em> and not to time
 * spent: standing still costs nothing at all, and the chain of somebody who
 * sprints for the whole ten minutes is bounded by
 * {@link #MAX_PRINTS_PER_WALKER}. Eight of those is under two megabytes, which
 * is the ceiling and not the usual case.
 *
 * <p>Two consecutive prints make a <em>segment</em> only when they are within
 * {@value #MAX_GAP} metres of each other. That single rule is what stops a line
 * being drawn across the world when somebody steps out of a boat on the far
 * shore, rejoins after a save, or is simply not walked for a moment because
 * their client had a long frame — none of which are footsteps, and all of which
 * would otherwise be a kilometre of trail nobody made.
 *
 * <h2>Threading</h2>
 *
 * <p>Not synchronised, and not meant to be. One of these belongs to a screen:
 * it is fed and read on the frame thread, by the scene that owns it, and it
 * decides nothing.
 */
public final class TrackField {

    /**
     * How long a track lasts, in seconds — <b>ten minutes, as asked.</b>
     *
     * <p>Against this game's clock that is ten minutes of the world as well,
     * because {@link com.larsons.engine.watch.WatchClock} is the wall clock: a
     * day here is a day. There is no scale factor to get wrong.
     */
    public static final double LIFETIME = 600;

    /** How far a walker moves before another print is laid, in metres. */
    public static final double STRIDE = 1.4;

    /** Half the width of a fresh track, in metres. */
    public static final double HALF_WIDTH = 0.55;

    /**
     * The share of a track's life it stays at full strength for, before it
     * begins to fade.
     *
     * <p>Not zero, and that is the whole shape of the thing. A track that
     * starts fading the instant it is laid is at four fifths of itself by the
     * time you have turned round to look at it, so the trail behind you is
     * always palest where you have most recently been — which is backwards, and
     * reads as a rendering fault rather than as weather. Holding for the first
     * third means the path you are on looks like a path, and it is the ten
     * minute mark that does the forgetting.
     */
    public static final double HOLD = 0.34;

    /**
     * The furthest two prints can be apart and still be joined by a track, in
     * metres.
     *
     * <p>Comfortably more than {@link #STRIDE}, because a print is laid on
     * whichever frame the walker happens to have crossed the stride on: a
     * client running at ten frames a second while somebody sprints at
     * {@link com.larsons.engine.watch.WatchPlayer#RUN_SPEED} metres a second
     * lays them nearly a metre apart, and a snapshot missed on the wire doubles
     * that. Comfortably less than the distance any of the ways of moving
     * without walking cover — a boat, a save reopened, a party member first
     * appearing in the snapshot — because every one of those has to read as a
     * break in the chain rather than as a stride.
     */
    public static final double MAX_GAP = 6.0;

    /**
     * The most prints one walker's chain holds.
     *
     * <p>{@link #STRIDE} times this is 5.7 km of trail, which is more than
     * {@link com.larsons.engine.watch.WatchPlayer#RUN_SPEED} covers in
     * {@link #LIFETIME}. So the cap is a guarantee about memory rather than a
     * limit anybody can walk into: it is what a chain fed by something that is
     * <em>not</em> a walker — a teleport loop, a test driving positions in at
     * frame rate — runs into instead of the heap.
     */
    public static final int MAX_PRINTS_PER_WALKER = 4096;

    /**
     * One footfall: where a walker was, and when.
     *
     * <p>The {@code at} is this field's own clock (see {@link #seconds()}) and
     * not a wall-clock stamp, so a track's age is unaffected by the machine's
     * time changing under it and a test can age one by a quarter of an hour
     * without waiting.
     */
    public record Print(double x, double y, double at) {}

    private final Map<Integer, ArrayDeque<Print>> walked = new LinkedHashMap<>();

    private double now;

    /**
     * Advance the field's clock and forget everything older than
     * {@link #LIFETIME}.
     *
     * <p>Swept from the front of each chain and stopping at the first print
     * that is still alive: a chain is in time order by construction, so the
     * dead are always a prefix of it and the sweep costs the number of prints
     * it actually removes rather than the number it keeps.
     */
    public void advance(double dt) {
        if (dt > 0) now += dt;
        double oldest = now - LIFETIME;
        Iterator<Map.Entry<Integer, ArrayDeque<Print>>> chains =
                walked.entrySet().iterator();
        while (chains.hasNext()) {
            Deque<Print> chain = chains.next().getValue();
            while (!chain.isEmpty() && chain.peekFirst().at() < oldest) {
                chain.removeFirst();
            }
            // A walker whose whole trail has gone stops being one of the things
            // this field knows about — including one who left the party ten
            // minutes ago, which is the only way an entry is ever removed.
            if (chain.isEmpty()) chains.remove();
        }
    }

    /**
     * Say that a walker is here.
     *
     * <p>Called once a frame per walker with wherever they are; it is this
     * method rather than the caller that decides whether that is a footstep.
     * The caller's job is only to not call it for the ways of being somewhere
     * that leave no track — in a boat, under the water, in the air.
     *
     * @return whether a print was actually laid
     */
    public boolean note(int walkerId, double x, double y) {
        ArrayDeque<Print> chain = walked.computeIfAbsent(walkerId,
                id -> new ArrayDeque<>());
        Print last = chain.peekLast();
        if (last != null) {
            double dx = x - last.x(), dy = y - last.y();
            if (dx * dx + dy * dy < STRIDE * STRIDE) return false;
        }
        chain.addLast(new Print(x, y, now));
        while (chain.size() > MAX_PRINTS_PER_WALKER) chain.removeFirst();
        return true;
    }

    /** Forget everything — what leaving a walk and starting another one does. */
    public void clear() {
        walked.clear();
        now = 0;
    }

    /** This field's own clock, in seconds since it was cleared. */
    public double seconds() { return now; }

    /** How many prints are being kept, across the whole party. */
    public int prints() {
        int total = 0;
        for (Deque<Print> chain : walked.values()) total += chain.size();
        return total;
    }

    /** How many walkers have a trail at all. */
    public int trailCount() { return walked.size(); }

    /**
     * Every walker's chain, oldest print first — what the mesher walks.
     *
     * <p>Copied out rather than handed over, because the caller iterates a
     * chain twice (once to work out which way it is heading at each print, once
     * to emit the strip between them) and wants to index it. The copy is a few
     * thousand references a handful of times a second; see
     * {@code WatchScene.trackMesh} for why it is not per frame.
     */
    public Collection<List<Print>> trails() {
        List<List<Print>> out = new ArrayList<>(walked.size());
        for (Deque<Print> chain : walked.values()) {
            if (chain.size() >= 2) out.add(List.copyOf(chain));
        }
        return out;
    }

    /**
     * Whether two consecutive prints are joined by a track rather than by a
     * hole in the record. See {@link #MAX_GAP}.
     */
    public static boolean joined(Print a, Print b) {
        double dx = b.x() - a.x(), dy = b.y() - a.y();
        return dx * dx + dy * dy <= MAX_GAP * MAX_GAP;
    }

    /**
     * How fresh a print laid at {@code at} is now: {@code 1} while it is within
     * {@link #HOLD} of its life, easing to {@code 0} at {@link #LIFETIME}.
     */
    public double freshnessAt(double at) {
        double age = now - at;
        if (age <= LIFETIME * HOLD) return 1;
        if (age >= LIFETIME) return 0;
        double t = (age - LIFETIME * HOLD) / (LIFETIME * (1 - HOLD));
        return 1 - t * t * (3 - 2 * t);
    }

    /** {@link #freshnessAt(double)} for a print. */
    public double freshnessOf(Print print) { return freshnessAt(print.at()); }

    /**
     * How wide a track of a given freshness still reads, as a share of
     * {@link #HALF_WIDTH}.
     *
     * <p>A track narrows as well as fading, because that is what happens to
     * one: the edges are the shallowest part of it and the grass there stands
     * back up first. Static, and used by both the mesher and
     * {@link #strengthAt}, so the number the game can be asked and the shape on
     * the ground are the same shape.
     */
    public static double widthOf(double freshness) {
        return 0.55 + 0.45 * freshness;
    }

    /**
     * How worn the ground is at a point — {@code 0} untrodden, {@code 1} a
     * fresh path.
     *
     * <p>Composited rather than maximised over the tracks that reach the point,
     * by the same rule the translucent quads on the ground blend by. So ground
     * crossed twice reads as more worn than ground crossed once, and the answer
     * this returns is the darkness you can actually see there.
     *
     * <p><b>A scan, and deliberately not indexed.</b> {@link TrailNetwork}
     * caches its cells because the terrain field asks it a question at every
     * vertex of every chunk; nothing asks this one at that rate. It is here for
     * the debug readout, for tests, and for whatever wants to know whether a
     * place has been walked — a few times a second over a few thousand prints,
     * which is arithmetic rather than a data structure.
     */
    public double strengthAt(double x, double y) {
        double worn = 0;
        for (Deque<Print> chain : walked.values()) {
            Print previous = null;
            for (Print print : chain) {
                if (previous != null && joined(previous, print)) {
                    double fresh = freshnessOf(print);
                    if (fresh > 0) {
                        double reach = HALF_WIDTH * widthOf(fresh);
                        double distance = distanceToSegment(x, y, previous, print);
                        if (distance < reach) {
                            worn += (1 - worn) * fresh * edgeFade(distance, reach);
                        }
                    }
                }
                previous = print;
            }
        }
        return worn > 1 ? 1 : worn;
    }

    /** Whether a point is on the trodden part of somebody's track. */
    public boolean trodden(double x, double y) { return strengthAt(x, y) > 0.05; }

    /**
     * How much of a track's strength survives at {@code distance} from its
     * centre line — full through the middle of it, smoothly out to nothing at
     * the edge, so a path has a margin rather than a kerb.
     */
    private static double edgeFade(double distance, double reach) {
        double core = reach * 0.55;
        if (distance <= core) return 1;
        double t = 1 - (distance - core) / (reach - core);
        return t * t * (3 - 2 * t);
    }

    /** The distance from a point to the segment between two prints. */
    private static double distanceToSegment(double x, double y, Print a, Print b) {
        double ex = b.x() - a.x(), ey = b.y() - a.y();
        double lengthSquared = ex * ex + ey * ey;
        double t = 0;
        if (lengthSquared > 1e-12) {
            t = ((x - a.x()) * ex + (y - a.y()) * ey) / lengthSquared;
            t = t < 0 ? 0 : Math.min(t, 1);
        }
        double dx = x - (a.x() + ex * t);
        double dy = y - (a.y() + ey * t);
        return Math.sqrt(dx * dx + dy * dy);
    }

    @Override public String toString() {
        return "tracks(" + walked.size() + " walkers, " + prints() + " prints, t="
                + Math.round(now) + "s)";
    }
}
