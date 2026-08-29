package com.larsons.engine.watch.render;

/**
 * One stroke of a pair of oars, as three numbers — <b>the single description of
 * rowing that the hull, the rower and the first-person hands all read.</b>
 *
 * <h2>Why it is a class and not three lines in the boat</h2>
 *
 * <p>Three separate things have to agree about where a stroke has got to: the
 * oars ({@link BoatModel}), the person swinging them
 * ({@link WalkerModel#rower}), and — when it is you doing the rowing — the two
 * hands drawn in front of the camera ({@link WalkerModel#rowingHands}). Written
 * out three times they drift, and the drift is visible: hands that let go of
 * the handles, a blade that catches half a beat before the arms move. Written
 * once, they cannot.
 *
 * <h2>The shape of a stroke</h2>
 *
 * <p>A stroke is a phase in turns. {@value #DRIVE} of it is the <b>drive</b> —
 * blade buried, boat accelerating — and the rest is the <b>recovery</b>, blade
 * out of the water and coming back for the next catch. Drive shorter than
 * recovery is what rowing looks like; equal halves read as somebody stirring
 * soup.
 *
 * <p><b>Every curve here meets its neighbour with zero slope.</b> That is the
 * whole of "smooth": a piecewise animation whose pieces have matching values
 * but mismatched velocities snaps at the join, once a stroke, for ever — which
 * is exactly the kind of tick the eye reads as a glitch rather than as a
 * rhythm. {@link #reach} is a half-cosine either way, so both ends of both
 * halves are stationary; {@link #lift} is a raised cosine over the recovery
 * only, so it leaves and rejoins the flat of the drive without a corner; and
 * {@link #surge} is a raised cosine over the drive for the same reason.
 *
 * <h2>Which way a forward-facing rower pulls</h2>
 *
 * <p>A rower in a real boat sits facing the stern, because a pull toward the
 * chest is stronger than a push away from it. This game moves a boat the way
 * its player is looking — that is what the movement keys mean everywhere else
 * in it — so the figure in the boat faces the way it is going and
 * <em>pushes</em>: hands out from the chest on the drive, back to it on the
 * recovery. It is a real technique (it is how anybody rows a dinghy through
 * moorings they would rather not hit), and it is the only version that agrees
 * with the direction the boat actually travels: the handles are inboard of the
 * oarlocks, so hands going forward swing the blades aft, and blades sweeping
 * aft are what push a boat ahead.
 */
public final class RowStroke {

    private RowStroke() {}

    /** The share of a stroke the blade spends in the water. */
    public static final double DRIVE = 0.42;

    /**
     * Where the hands are along the boat, {@code -1} drawn back to the chest at
     * the catch to {@code +1} pushed out at the finish.
     *
     * <p>Continuous and stationary at both ends of both halves, so the hands
     * come to rest at the catch and at the finish rather than reversing at
     * speed — which is both what a stroke does and what stops the join between
     * the halves from being visible.
     */
    public static double reach(double stroke) {
        double s = wrap(stroke);
        if (s < DRIVE) return -Math.cos(Math.PI * s / DRIVE);
        return Math.cos(Math.PI * (s - DRIVE) / (1 - DRIVE));
    }

    /**
     * How far the blade is clear of the water, {@code 0} buried through the
     * whole drive up to {@code 1} at the top of the recovery.
     *
     * <p>Flat at zero for the drive and a single raised cosine over the
     * recovery: the blade squares up and drops in as the catch comes round, and
     * lifts out again the moment the finish passes, with no step at either.
     */
    public static double lift(double stroke) {
        double s = wrap(stroke);
        if (s <= DRIVE) return 0;
        double r = (s - DRIVE) / (1 - DRIVE);
        return 0.5 - 0.5 * Math.cos(2 * Math.PI * r);
    }

    /**
     * How hard the boat is being driven forward, {@code 0} through the recovery
     * to {@code 1} at the middle of the drive.
     *
     * <p>What makes a rowed boat surge rather than glide: the hull noses down
     * and gathers way while the blades are in, and runs level while they are
     * not. Small — see {@link BoatModel} for the couple of centimetres it is
     * worth — but it is the difference between a boat being rowed and a boat
     * being dragged along on a string.
     */
    public static double surge(double stroke) {
        double s = wrap(stroke);
        if (s > DRIVE) return 0;
        return 0.5 - 0.5 * Math.cos(2 * Math.PI * s / DRIVE);
    }

    /** A phase folded into {@code [0,1)}, whatever the caller's clock has done. */
    public static double wrap(double phase) {
        if (!Double.isFinite(phase)) return 0;
        double s = phase - Math.floor(phase);
        // Math.floor on a value a hair below an integer can land on the integer
        // itself, which returns exactly 1 and puts callers one step outside the
        // half-open range they were promised.
        return s >= 1 ? 0 : s;
    }
}
