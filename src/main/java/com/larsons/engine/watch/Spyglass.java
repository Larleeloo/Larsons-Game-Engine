package com.larsons.engine.watch;

/**
 * A draw-tube spyglass: <b>a real change of focal length, not a picture blown
 * up.</b>
 *
 * <h2>Why this is optics and not a zoom effect</h2>
 *
 * <p>The cheap way to do a spyglass is to draw the frame as usual and then
 * scale the middle of it up. That is a magnifying glass held over a photograph:
 * every pixel gets bigger and not one of them gets more detailed, so a chaffinch
 * four hundred metres away is still the three grey pixels it was, only larger.
 * In a game whose entire verb is <em>look at that animal and work out what it
 * is</em>, that is worse than useless — it promises resolution and delivers
 * blur.
 *
 * <p>So this narrows the camera's own field of view instead. The world is
 * re-projected through a longer lens: the far hillside is rendered at the size
 * it now subtends, with the triangles it deserves at that size, and the
 * {@link com.larsons.engine.watch.world.ChunkStreamer} is told where the glass
 * is pointed so the ground down that line is built at full detail and out to
 * several times the ordinary view distance. Magnification, here, is exactly the
 * ratio of tangents:
 *
 * <pre>
 *   fov(power) = 2·atan( tan(fov₀ / 2) / power )
 * </pre>
 *
 * <p>which is the same formula a real objective and eyepiece obey, and it is
 * what makes "×8" mean eight times rather than "a bit closer".
 *
 * <h2>Three pulls of the tube</h2>
 *
 * <p>{@link #POWERS} — ×4, ×8, ×15 — because a draw tube has stops rather than
 * a continuous zoom ring, and because three powers are three genuinely
 * different jobs: ×4 to sweep a valley, ×8 to work out what is on the far
 * shore, ×15 to read the bars on its chest while it sits still.
 *
 * <h2>The tube travels; it does not teleport</h2>
 *
 * <p>Raising, lowering and changing power all move one number,
 * {@link #power()}, toward its target — and the easing happens in <em>log</em>
 * space, so the rate of change of magnification is constant. Linear easing
 * spends most of its time crawling through the last two powers and snaps
 * through the first ten, which reads as a lurch; in log space ×1→×15 and
 * ×15→×1 take the same time and both look like a tube being drawn out.
 *
 * <h2>And it shakes</h2>
 *
 * <p>Fifteen magnifications multiply the shake in your hands by fifteen too,
 * which is why nobody hand-holds a spotting scope. {@link #swayYaw()} and
 * {@link #swayPitch()} are that shake, in <em>angle</em>, scaled to the current
 * field of view — so it is roughly the same wander across the eyepiece at every
 * power, and it is the reason the game's existing
 * {@linkplain WatchPlayer#stillness() stillness} stat is what steadies it.
 * Stand still and crouch and the view settles; walk and glass at the same time
 * and you will not identify anything.
 *
 * <p>This object is the <em>client's</em> half: the scene owns one, it moves
 * the camera, and it decides nothing about the world. What the server is told
 * is a single number — {@link #power()} — through {@code WatchGame.glass}, and
 * the server uses it for the one thing it has to be authoritative about: how
 * far away a player can pick something out. See {@link #spotRange} and
 * {@link #tolerance}.
 */
public final class Spyglass {

    /** The satchel key of the instrument itself. */
    public static final String ITEM = "spyglass";

    /** The stops on the tube, in magnifications. */
    public static final double[] POWERS = {4, 8, 15};

    /** Naked eye. Not a stop — the tube closed. */
    public static final double NONE = 1;

    /**
     * How far the glass reaches per magnification, in metres.
     *
     * <p>Sixty a power: ×4 sees a quarter of a kilometre, ×15 the better part
     * of one. This one number decides three things that must agree — how far
     * the ground is streamed in full detail, how far the fog is pushed back,
     * and how far away an animal can be recorded — because a glass that draws
     * a hillside it will not let you identify anything on is a prop.
     */
    public static final double METRES_PER_POWER = 60;

    /**
     * How long the tube takes to travel, as an exponential time constant.
     *
     * <p>A little under half a second to settle, which is about how long it
     * takes to actually raise a glass to your eye.
     */
    private static final double TRAVEL_SECONDS = 0.12;

    /** Below this the tube counts as closed and the glass as down. */
    private static final double UP_THRESHOLD = 1.02;

    /** How much of the field of view the shake wanders across, at its worst. */
    private static final double SWAY_SHARE = 0.085;

    /** How much shake is left in the steadiest possible hands. */
    private static final double STEADIEST = 0.16;

    private int stop;
    private boolean raised;
    private double power = NONE;
    private double clock;
    private double unsteady = 1;

    // --- the optics, which anybody may ask about ---------------------------------

    /**
     * The vertical field of view a power looks through, in radians.
     *
     * @param fovAtRest the unaided field of view — the camera's own
     */
    public static double fovFor(double fovAtRest, double power) {
        double p = Math.max(NONE, power);
        return 2 * Math.atan(Math.tan(fovAtRest / 2) / p);
    }

    /** How far a power reaches, in metres. */
    public static double rangeFor(double power) {
        return Math.max(NONE, power) * METRES_PER_POWER;
    }

    /**
     * How far away a player looking through this can pick something out.
     *
     * <p>The naked-eye range is the floor: a glass never makes you worse at
     * seeing what is in front of you.
     */
    public static double spotRange(double power, double unaidedRange) {
        return Math.max(unaidedRange, rangeFor(power));
    }

    /**
     * How far off the centre of the view an animal may be and still count as
     * being under the crosshair, in radians.
     *
     * <p>The unaided rule — an angular tolerance that widens with the animal's
     * size, with a floor so a hummingbird at forty metres is not impossible to
     * click — <b>divided by the power</b>. That division is the whole reason a
     * glass is worth raising for something close, too: the same tolerance
     * covers a fifteenth of the angle, so at ×15 you are picking one bird out
     * of a flock rather than clicking at the flock.
     *
     * <p>Shared by the server, which adjudicates the click, and the client,
     * which has to name what is under the crosshair at frame rate. Two copies
     * of this rule that disagreed would be a game where the label says
     * "Redpoll" and the click records nothing.
     */
    public static double tolerance(double bodyLength, double distance, double power) {
        double unaided = Math.max(0.022, Math.atan2(bodyLength * 1.6, distance));
        return unaided / Math.max(NONE, power);
    }

    // --- the tube ------------------------------------------------------------------

    /** Which stop the tube is set to, {@code 0}–{@code POWERS.length − 1}. */
    public int stop() { return stop; }

    /** The magnification that stop is worth, whether or not the glass is up. */
    public double stopPower() { return POWERS[stop]; }

    /** Move to another stop; wraps, so one key can cycle them. */
    public void setStop(int index) {
        stop = Math.floorMod(index, POWERS.length);
    }

    /** Pull the tube out a stop, or push it in one. */
    public void nudge(int steps) {
        if (steps != 0) setStop(stop + steps);
    }

    /** Whether the player is asking to look through it. */
    public boolean raised() { return raised; }

    /** Where the tube is now — {@code 1} closed, up to {@link #stopPower()}. */
    public double power() { return power; }

    /** Whether the glass is doing anything at all this frame. */
    public boolean up() { return power > UP_THRESHOLD; }

    /**
     * How far out the tube is, {@code 0} closed to {@code 1} at its stop — for
     * fading the eyepiece in rather than snapping it on.
     */
    public double deployment() {
        double target = stopPower();
        if (target <= NONE) return 0;
        return Math.max(0, Math.min(1, (power - NONE) / (target - NONE)));
    }

    /** How far this glass reaches at its current extension, in metres. */
    public double range() { return rangeFor(power); }

    /** The field of view it is looking through, given the unaided one. */
    public double fov(double fovAtRest) { return fovFor(fovAtRest, power); }

    /**
     * How much slower the mouse should turn the head while glassing.
     *
     * <p>The ratio of the tangents, which is the same thing as one over the
     * magnification: without it a hand movement that swept a quarter of an
     * unaided screen sweeps four screens at ×15, and the glass is unusable at
     * exactly the power it exists for.
     */
    public double lookScale() { return NONE / Math.max(NONE, power); }

    /**
     * Advance the tube.
     *
     * @param wanted    whether the player is holding the key <em>and</em> has
     *                  one in the satchel — a glass nobody owns never opens
     * @param steadiness how settled the player is, {@code 0}–{@code 1}; the
     *                   game's own stillness stat
     */
    public void tick(double dt, boolean wanted, double steadiness) {
        this.raised = wanted;
        double target = wanted ? stopPower() : NONE;
        if (dt > 0) {
            // In log space: see the class note. `1 − e^(−dt/τ)` is the same
            // frame-rate-independent ease the rest of the game smooths with.
            double k = 1 - Math.exp(-dt / TRAVEL_SECONDS);
            double now = Math.log(Math.max(NONE, power));
            power = Math.exp(now + (Math.log(target) - now) * k);
        }
        if (Math.abs(power - target) < 0.02) power = target;
        power = Math.max(NONE, power);

        clock += dt;
        double settled = Math.max(0, Math.min(1, steadiness));
        unsteady = STEADIEST + (1 - STEADIEST) * (1 - settled);
    }

    /**
     * The shake, across the eyepiece, in radians of yaw.
     *
     * <p>Two sines at unrelated frequencies rather than one: a single sine is a
     * metronome and reads as an animation, and the pair never repeats inside a
     * session, which is what a hand does.
     */
    public double swayYaw(double fovAtRest) {
        return amplitude(fovAtRest)
                * (Math.sin(clock * 1.7) * 0.6 + Math.sin(clock * 0.53 + 1.3) * 0.4);
    }

    /** The same, in radians of pitch. */
    public double swayPitch(double fovAtRest) {
        return amplitude(fovAtRest)
                * (Math.sin(clock * 1.31 + 0.7) * 0.6 + Math.sin(clock * 0.41) * 0.4);
    }

    /** How steady the view is now, {@code 0} wandering – {@code 1} rock solid. */
    public double steadiness() { return 1 - unsteady; }

    private double amplitude(double fovAtRest) {
        if (!up()) return 0;
        return fov(fovAtRest) * SWAY_SHARE * unsteady;
    }
}
