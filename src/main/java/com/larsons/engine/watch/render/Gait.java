package com.larsons.engine.watch.render;

import java.util.HashMap;
import java.util.Map;

/**
 * How fast a walk cycle runs, and where everybody in the party has got to
 * between two snapshots — <b>the two halves of why the other people in this
 * world used to move like a flip-book.</b>
 *
 * <h2>The problem this exists to solve</h2>
 *
 * <p>Positions arrive at {@code WatchProto.TICK_RATE} — twenty a second, and
 * twenty a second is a fifth of the rate a walking game is drawn at. Drawn
 * straight from the last snapshot, another player advances in fifty-millisecond
 * hops: at a jog that is a forty-centimetre jump, five times a second, for ever.
 * Nothing about the network is wrong when that happens; what is missing is the
 * step between the two things that are known, and putting it back is this
 * class's first job.
 *
 * <p>The second is the gait clock itself. The old one was
 * {@code frame * 0.02}: a phase counted in <em>frames drawn</em> rather than in
 * seconds elapsed, which makes the same walk run at half speed on a
 * sixty-hertz screen and at double on a hundred-and-forty-four-hertz one, and
 * makes it stutter whenever the frame rate does. It also ran whether or not the
 * walker was going anywhere, so a standing figure paddled its legs on the spot.
 * A phase advanced by {@link #cadence} against real seconds and real ground
 * speed does neither.
 *
 * <h2>Smoothing, not extrapolating</h2>
 *
 * <p>A drawn position eases toward the last one that arrived, at
 * {@value #CATCH_UP} per second, rather than guessing where the walker will be
 * next. Easing costs a fixed distance of lag — speed over the rate, so about
 * thirty centimetres at a walk — and guessing costs a correction every time
 * somebody stops or turns, which is the artefact this class is here to remove.
 * In a game whose whole verb is looking at things, a third of a metre of lag on
 * somebody else's avatar is not a thing anybody can see; a snap when they stop
 * is.
 *
 * <p>Ground speed is measured from the <b>drawn</b> motion rather than from the
 * snapshots, which is what keeps the legs in step with the body: the figure is
 * moving at the speed it appears to be moving at, by construction, including
 * while it is still catching up to a position it has already been told about.
 *
 * <p>A walker who has genuinely teleported — a spawn, a fresh join, a world
 * reload — is more than {@value #SNAP} metres from where they were, which is
 * further than anybody can travel between two snapshots. That is placed rather
 * than eased, because easing it draws somebody skating across a continent.
 */
public final class Gait {

    /** How fast a drawn position closes on the last one that arrived, per second. */
    private static final double CATCH_UP = 14;

    /** Beyond this many metres in one snapshot, a walker teleported. */
    private static final double SNAP = 6;

    /** How fast the speed the legs are driven from settles, per second. */
    private static final double SPEED_SETTLE = 9;

    /**
     * The stride a walker takes at a standstill, in metres, and how much longer
     * it gets per metre a second of speed.
     *
     * <p>Both, rather than one fixed stride, because a fixed stride ties the
     * cadence to the speed alone and a run then becomes a blur: at eight metres
     * a second and a two-metre stride the legs cross four times a second, which
     * is neither what running looks like nor something a frame at sixty hertz
     * can show without aliasing. People lengthen their stride as they speed up;
     * doing the same here keeps the cadence inside a range the eye can read.
     */
    private static final double STRIDE = 1.35, STRIDE_PER_SPEED = 0.35;

    /** The same two numbers for a stroke of the oars, which covers more ground. */
    private static final double STROKE = 3.5, STROKE_PER_SPEED = 0.35;

    /**
     * Where one walker is being drawn, and how far through their cycle they are.
     *
     * @param speed how fast the drawn figure is moving, in metres per second —
     *              what the limb swing is scaled by
     * @param phase the walk cycle or the rowing stroke, in turns
     */
    public record Step(double x, double y, double z, double yaw,
                       double speed, double phase) {}

    private static final class Track {
        double x, y, z, yaw;
        double speed;
        double phase;
        boolean placed;
        Step step = new Step(0, 0, 0, 0, 0, 0);
    }

    /**
     * One entry per player in the party, which {@code WatchProto} caps at eight.
     * Nothing sweeps it: a walker who leaves and comes back keeps their id, and
     * comes back somewhere {@link #SNAP} handles.
     */
    private final Map<Integer, Track> tracks = new HashMap<>();

    /**
     * Take one walker's latest known position and give back where to draw them.
     *
     * <p>Call it once per walker per frame, in the order the frame draws them.
     * {@link #at} then answers with the same {@link Step} for anything else in
     * that frame that has to agree with it — the boat under a rower, most of
     * all, which is drawn in a later pass and would otherwise slide about
     * underneath its own rower.
     *
     * @param inBoat whether the cycle being clocked is a stroke of the oars
     *               rather than a stride
     */
    public Step follow(int id, double x, double y, double z, double yaw,
                       boolean inBoat, double dt) {
        Track track = tracks.computeIfAbsent(id, key -> new Track());
        double step = Math.max(0, Math.min(0.25, dt));
        if (!track.placed || Math.hypot(x - track.x, y - track.y) > SNAP) {
            track.placed = true;
            track.x = x;
            track.y = y;
            track.z = z;
            track.yaw = yaw;
            track.speed = 0;
        } else {
            double fromX = track.x, fromY = track.y;
            // Exponential rather than linear: the fraction closed in a frame
            // has to come from the frame's own length, or the smoothing runs at
            // the frame rate and is back to being the bug it fixes.
            double close = 1 - Math.exp(-step * CATCH_UP);
            track.x += (x - track.x) * close;
            track.y += (y - track.y) * close;
            track.z += (z - track.z) * close;
            track.yaw = turn(track.yaw, yaw, close);
            double moved = Math.hypot(track.x - fromX, track.y - fromY);
            double speed = step > 1e-6 ? moved / step : 0;
            track.speed += (speed - track.speed) * Math.min(1, step * SPEED_SETTLE);
        }
        double rate = inBoat ? strokeRate(track.speed) : cadence(track.speed);
        track.phase = RowStroke.wrap(track.phase + rate * step);
        track.step = new Step(track.x, track.y, track.z, track.yaw,
                track.speed, track.phase);
        return track.step;
    }

    /**
     * Where a walker was last drawn, or {@code null} if this frame has not
     * placed them yet.
     */
    public Step at(int id) {
        Track track = tracks.get(id);
        return track == null || !track.placed ? null : track.step;
    }

    /** Walk cycles a second at a ground speed, in metres per second. */
    public static double cadence(double speed) {
        double v = Math.max(0, speed);
        return v / (STRIDE + STRIDE_PER_SPEED * v);
    }

    /** Strokes of the oars a second at a rowing speed. */
    public static double strokeRate(double speed) {
        double v = Math.max(0, speed);
        return v / (STROKE + STROKE_PER_SPEED * v);
    }

    /**
     * A heading eased toward another the short way round.
     *
     * <p>Plain interpolation between {@code 3.1} and {@code -3.1} radians takes
     * the walker the long way — a whole turn on the spot for two degrees of
     * actual change — which is the one artefact a smoothed heading can add that
     * an unsmoothed one does not have.
     */
    public static double turn(double from, double to, double howFar) {
        double delta = to - from;
        delta -= Math.floor(delta / (Math.PI * 2) + 0.5) * Math.PI * 2;
        return from + delta * howFar;
    }
}
