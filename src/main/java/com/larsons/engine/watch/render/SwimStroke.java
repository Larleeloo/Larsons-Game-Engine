package com.larsons.engine.watch.render;

/**
 * One stroke of a swimmer, as five numbers — the counterpart of
 * {@link RowStroke}, and written the same way for the same reasons.
 *
 * <h2>Why breaststroke</h2>
 *
 * <p>Because this game's swimmer has to <b>breathe</b>. A player at the surface
 * is one whose head is out of the water and whose {@code breath} is coming
 * back; a player whose head is under is on a clock. A stroke that buries the
 * face and turns it to the side once a cycle says the opposite of what the
 * breath meter says. Breaststroke keeps the head up and the eyes forward, which
 * is also the pose a game about <em>looking at things</em> wants somebody to be
 * in while they cross a lake.
 *
 * <p>It reads better at distance too. Both arms do the same thing at the same
 * time, so the silhouette of a swimmer a hundred metres off is a wide sweep and
 * a narrow glide rather than two small limbs alternating — and at that range a
 * front crawl's arms are a couple of pixels flickering.
 *
 * <h2>The shape of a stroke</h2>
 *
 * <p>{@value #PULL} of the cycle is the <b>pull</b>: the hands sweep out from
 * in front, round, and back in to the chest, and the head lifts to breathe. The
 * rest is the <b>recovery</b>: the hands shoot forward again while the legs
 * draw up and snap out and together — pull, breathe, kick, glide, which is the
 * order the stroke is actually taught in and the reason a breaststroker moves
 * in surges rather than at a constant speed.
 *
 * <p><b>Every curve meets its neighbour with zero slope</b>, as in
 * {@link RowStroke}: a piecewise animation whose pieces have matching values
 * and mismatched velocities snaps at the join, once a stroke, for ever.
 * {@link #reach} is a half-cosine either way; {@link #spread},
 * {@link #kick} and {@link #breathe} are raised cosines over one half of the
 * cycle and flat zero over the other, so each leaves and rejoins the flat
 * without a corner.
 */
public final class SwimStroke {

    private SwimStroke() {}

    /** The share of a stroke the arms spend pulling. */
    public static final double PULL = 0.42;

    /**
     * Where the hands are along the body, {@code +1} extended in front at the
     * glide to {@code -1} tucked at the chest at the end of the pull.
     */
    public static double reach(double stroke) {
        double s = RowStroke.wrap(stroke);
        if (s < PULL) return Math.cos(Math.PI * s / PULL);
        return -Math.cos(Math.PI * (s - PULL) / (1 - PULL));
    }

    /**
     * How far the hands are out to the sides, {@code 0} together on the
     * centreline to {@code 1} at the widest point of the pull.
     *
     * <p>Zero through the whole recovery, because a breaststroker's hands come
     * forward <em>under the chest</em> rather than out round the sides: that
     * asymmetry — wide going back, narrow coming forward — is what makes the
     * stroke push water instead of stirring it, and it is what the eye reads as
     * swimming rather than as flapping.
     */
    public static double spread(double stroke) {
        double s = RowStroke.wrap(stroke);
        if (s >= PULL) return 0;
        return 0.5 - 0.5 * Math.cos(2 * Math.PI * s / PULL);
    }

    /**
     * How far the knees are drawn up, {@code 0} legs straight and trailing to
     * {@code 1} heels at the seat, ready to snap.
     *
     * <p>Flat at zero through the pull: the legs trail while the arms work, and
     * kick while the arms recover. Both at once is the commonest way to draw a
     * swimmer wrong, and it looks like somebody falling downstairs.
     */
    public static double kick(double stroke) {
        double s = RowStroke.wrap(stroke);
        if (s <= PULL) return 0;
        double r = (s - PULL) / (1 - PULL);
        return 0.5 - 0.5 * Math.cos(2 * Math.PI * r);
    }

    /**
     * How far the head is lifted to breathe, {@code 0} in the glide to
     * {@code 1} at the middle of the pull.
     *
     * <p>Small in metres and large in what it says: it is the one movement that
     * tells somebody watching from the bank that the swimmer is at the surface
     * rather than under it.
     */
    public static double breathe(double stroke) {
        double s = RowStroke.wrap(stroke);
        if (s >= PULL) return 0;
        return 0.5 - 0.5 * Math.cos(2 * Math.PI * s / PULL);
    }

    /**
     * How hard the swimmer is being driven forward over the cycle, {@code 0} in
     * the glide to {@code 1} at the middle of the leg kick.
     *
     * <p>Breaststroke's propulsion is nearly all in the legs and it arrives in
     * one shove, which is why the stroke surges. Used for a few centimetres of
     * gather and glide, and no more.
     */
    public static double surge(double stroke) {
        double s = RowStroke.wrap(stroke);
        if (s <= PULL) return 0;
        double r = (s - PULL) / (1 - PULL);
        return 0.5 - 0.5 * Math.cos(2 * Math.PI * r);
    }
}
