package com.larsons.engine.graphics.chunk;

/**
 * How far the world is drawn a block at a time — <b>found by measurement rather
 * than taken from a slider</b>.
 *
 * <h2>Why a slider cannot answer this</h2>
 *
 * <p>Per-block geometry costs the square of its radius, and it costs it twice:
 * in memory, because every section's triangles are kept so they need not be
 * rebuilt, and in draw calls, because each one is a separate piece of geometry
 * at a separate place. Measured on this engine's own terrain: thirty-two chunks
 * of full detail is <b>1.4 GB of mesh and 2.8 million quads a frame</b>. Ninety
 * chunks is that times eight — eleven gigabytes, and more draw calls than any
 * driver will issue in eight milliseconds.
 *
 * <p>So no machine draws ninety chunks a block at a time, and a renderer that
 * tries does not fail gracefully: it fills its cache, evicts the rim, rebuilds
 * it, evicts it again, and the horizon flashes while the frame rate collapses.
 * The world <em>is</em> drawn to ninety chunks — by {@code WorldLod}, whose cost
 * is a function of angle rather than distance, which is exactly the trade
 * Distant Horizons makes and the reason it can show a landscape Minecraft
 * cannot.
 *
 * <h2>What this does instead</h2>
 *
 * <p>It asks for a little, and grows while growing stays cheap. Every frame it
 * compares two measurements against two targets — how many sections the last
 * frame drew, and how long the last frame took — and moves the radius by one
 * and a half per cent. That is slow enough to be invisible (a hundred frames to
 * change by four fifths, under a second at 120 Hz) and fast enough to find the
 * machine's own answer while the player is still walking away from spawn.
 *
 * <p><b>Starting small rather than large is the half that matters while the
 * world is loading.</b> A radius that begins at what the slider asked for spends
 * the first several seconds meshing terrain the machine cannot sustain, which is
 * precisely when the player is least willing to lose frames. Beginning near and
 * growing outward means the world arrives around them at whatever rate the
 * machine can actually manage, and never faster.
 *
 * <p><b>And whatever it settles on, the level-of-detail tree starts exactly
 * there.</b> That is the other half of not flashing: the seam moves, slowly, but
 * there is never a gap at it, because both sides are told the same number.
 */
public final class DetailReach {

    /**
     * Sections a frame should draw, at most.
     *
     * <p>One draw call each, near enough — Minecraft at its own maximum render
     * distance is in this neighbourhood, which is not a coincidence: it is the
     * point where the driver's per-draw cost starts to be the frame. Machines
     * differ, so this is a ceiling rather than a target, and the frame-time rule
     * below is what actually finds a slower machine's number.
     */
    public static final int TARGET_SECTIONS = 4096;

    /**
     * Sections a frame's walk may step into.
     *
     * <p>A different limit from the one above and a necessary one: the walk goes
     * through the sky as well as the ground, and standing on a mountain looking
     * out there are several empty sections over every solid one. Measured at a
     * sixty-chunk radius the walk stepped into <b>94 000</b> sections to draw
     * four thousand, which is twenty milliseconds of doing nothing.
     */
    public static final int TARGET_VISITS = 24_000;

    /**
     * The frame this is trying to fit inside, in milliseconds.
     *
     * <p>120 frames a second is 8.3 ms for everything — the world, the sprites,
     * the interface, the simulation. Six leaves room for the rest of them, and
     * a frame consistently over it is the machine saying the radius is too big
     * whatever the section count thinks.
     */
    private static final double TARGET_FRAME_MS = 6.0;

    /** Below these fractions of the targets, growing is allowed. */
    private static final double COMFORTABLE = 0.85;

    /**
     * How much of a walk may still be waiting on a mesh and have the radius
     * grow anyway.
     *
     * <p>Not zero: the rim is always a frame or two behind, and a player walking
     * forward keeps it that way for as long as they walk. Two per cent is the
     * difference between "the edge is arriving" and "the middle has not turned
     * up yet".
     */
    private static final double UNBUILT_ALLOWED = 0.02;

    /** How much the radius moves in one frame. */
    private static final double STEP = 0.015;

    /** Where it starts, and the floor it never goes under, in sections. */
    private static final int START_SECTIONS = 6;
    private static final int FLOOR_SECTIONS = 4;

    private double reach = -1;

    /** The radius in world units, or {@code -1} before the first frame. */
    public double reach() { return reach; }

    /** Forget what was learnt — a new world, or a teleport across one. */
    public void reset() { reach = -1; }

    /**
     * Move the radius one frame's worth and return it.
     *
     * @param requested  what the player's slider asked for, in world units
     * @param affordable what the mesh cache can hold, in world units
     *                   ({@link TerrainSections#affordableReach})
     * @param drawn      sections the last frame drew
     * @param visits     sections the last frame's walk stepped into
     * @param unbuilt    how many of those had no mesh yet
     * @param frameMs    how long the last frame took, or {@code 0} if unknown
     * @param span       one section's width in world units, for the floor
     */
    public double update(double requested, double affordable, int drawn, int visits,
                         int unbuilt, double frameMs, double span) {
        double ceiling = Math.min(requested, affordable);
        double floor = Math.min(ceiling, FLOOR_SECTIONS * span);
        ceiling = Math.max(ceiling, floor);
        if (reach < 0) {
            reach = Math.min(ceiling, START_SECTIONS * span);
            return reach;
        }

        // Any measurement over its target shrinks the radius; growing needs all
        // of them comfortably under. Asymmetric on purpose — backing off a frame
        // the machine missed should not wait for the other signals to agree.
        boolean tooMuch = drawn > TARGET_SECTIONS
                || visits > TARGET_VISITS
                || (frameMs > 0 && frameMs > TARGET_FRAME_MS);

        // <b>And growing waits for the world it already asked for.</b> The
        // section count measures what has been <em>built</em>, not what has been
        // asked for, so while the meshers are behind it reads low for the one
        // reason that is not an invitation to ask for more. Left ungated the
        // radius runs away during exactly the seconds a player is least willing
        // to lose frames: measured, it reached fifty-eight chunks while four
        // hundred sections were drawn, and the walk spent twenty milliseconds a
        // frame stepping through ninety thousand sections with nothing in them
        // yet.
        //
        // The gate is the <em>miss rate</em> of the walk rather than the
        // meshers' queue length, because that is the question actually being
        // asked — is the world at this radius here — and it does not depend on
        // how many threads a machine has or how fast they are.
        boolean arrived = unbuilt <= visits * UNBUILT_ALLOWED;
        boolean roomToGrow = arrived
                && drawn < TARGET_SECTIONS * COMFORTABLE
                && visits < TARGET_VISITS * COMFORTABLE
                && (frameMs <= 0 || frameMs < TARGET_FRAME_MS * COMFORTABLE);

        if (tooMuch) reach *= 1 - STEP;
        else if (roomToGrow) reach *= 1 + STEP;

        reach = Math.max(floor, Math.min(ceiling, reach));
        return reach;
    }
}
