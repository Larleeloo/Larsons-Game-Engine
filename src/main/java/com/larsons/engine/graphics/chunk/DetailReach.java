package com.larsons.engine.graphics.chunk;

/**
 * How far the world is drawn a block at a time — <b>found by measurement rather
 * than taken from a slider</b>.
 *
 * <h2>Why a slider cannot answer this</h2>
 *
 * <p>Per-block geometry costs the square of its radius, and it costs it in
 * three separate currencies: memory, because every section's triangles are kept
 * so they need not be rebuilt; processor, because a frame has to decide which
 * of them are visible; and card, because it has to shade them. Measured on this
 * engine's own terrain, a chunk of radius is a few tens of megabytes and a few
 * tens of thousands of quads, and both scale with the area.
 *
 * <p><b>Which of the three runs out first is a fact about the machine, not about
 * the engine</b>, and it moves: a workstation with sixty-four gigabytes and a
 * recent card runs out of none of them where a laptop runs out of all three.
 * That is why none of the limits here is a chosen number any more. The heap
 * decides the memory ({@code TerrainSections.affordableReach}), a timer query
 * decides the card ({@code TerrainPass.lastGpuMillis}), and the walk times
 * itself.
 *
 * <p>Past whatever that comes to, the world is still drawn — by
 * {@code WorldLod}, whose cost is a function of angle rather than distance,
 * which is exactly the trade Distant Horizons makes and the reason it can show
 * a landscape Minecraft cannot. The seam between them is wherever this class
 * settles, and both sides are told the same number.
 *
 * <h2>What this does instead</h2>
 *
 * <p>It asks for a little, and grows while growing stays cheap. Every frame it
 * compares what the last one cost against what a frame can afford, and moves
 * the radius by one and a half per cent. That is slow enough to be invisible (a
 * hundred frames to change by four fifths, under a second at 120 Hz) and fast
 * enough to find the machine's own answer while the player is still walking
 * away from spawn.
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
     * Sections a frame may draw — <b>a backstop, not the answer</b>.
     *
     * <p>It used to be four thousand and it used to be what actually decided
     * the render distance, which was the wrong shape of rule twice over. A
     * number chosen by hand is the same number on a laptop and on a card ten
     * times quicker, so the quick machine never finds out what it could have
     * had — measured, it stopped at twenty-three chunks on a card that had
     * plenty left. And it is a proxy for the cost rather than the cost: what a
     * section costs to draw depends on how much is in it, which is a property of
     * the terrain and not of the count.
     *
     * <p>So the cost is measured now — {@link #TARGET_GPU_MS} on the card,
     * {@link #TARGET_CPU_MS} on the processor — and this is left far enough
     * above any sane answer that it only catches a runaway. Higher again since
     * sections stopped being draw calls: sixty-four of them share one buffer and
     * one {@code glMultiDrawArrays} now, so the count of them says even less
     * about what a frame costs than it did.
     */
    public static final int TARGET_SECTIONS = 120_000;

    /**
     * What the world may cost the graphics card, in milliseconds a frame.
     *
     * <p>Five of a 120 Hz frame's 8.3, which leaves the sprites, the interface
     * and the driver the rest. <b>This is the number that decides the render
     * distance on a machine that is not short of memory</b>, and it is the only
     * one of the governor's signals that measures the thing actually being
     * bought — see {@code TerrainPass.lastGpuMillis} for why the CPU cannot
     * answer this and why a constant should not try.
     */
    private static final double TARGET_GPU_MS = 5.0;

    /**
     * Sections a frame's walk may step into.
     *
     * <p>A different limit from the one above and a necessary one: the walk goes
     * through the sky as well as the ground, and standing on a mountain looking
     * out there are several empty sections over every solid one. Measured at a
     * sixty-chunk radius the walk stepped into <b>94 000</b> sections to draw
     * four thousand, which is twenty milliseconds of doing nothing.
     */
    public static final int TARGET_VISITS = 150_000;

    /**
     * What the section walk itself may cost, in milliseconds.
     *
     * <p><b>The walk's own time, and never the frame's.</b> That distinction
     * shipped wrong once and was worth a bug report: this used to compare the
     * <em>whole frame</em> against a six-millisecond budget, reasoning that 120
     * frames a second is 8.3 ms and the terrain should have most of it. But a
     * frame running at exactly 120 Hz <em>takes</em> 8.3 ms, which is over six —
     * so a game hitting its target read as a machine in distress, the radius
     * refused to grow past its opening value, and every detail-distance setting
     * produced the same six or seven chunks however long you waited.
     *
     * <p>And it is not a matter of picking a bigger number. Under vsync or any
     * frame cap the frame time is <b>pinned to the refresh interval whatever the
     * load is</b>: an idle 120 Hz frame and a struggling one both read 8.3 ms, an
     * idle 60 Hz frame reads 16.7. Whole-frame time carries no information about
     * headroom at all, and there is no threshold that fixes that.
     *
     * <p>The walk's own time does carry it — nothing pins it, it scales with the
     * radius, and it is the thing this class is actually steering. Two
     * milliseconds is a quarter of a 120 Hz frame spent deciding what to draw,
     * which is more than it should ever need: measured at thirty-two chunks it
     * costs 0.41.
     */
    private static final double TARGET_CPU_MS = 3.0;

    /** Below these fractions of the targets, growing is allowed. */
    private static final double COMFORTABLE = 0.85;

    /**
     * How much of a walk may still be waiting on a mesh and have the radius
     * grow anyway.
     *
     * <p>Not zero: the rim is always a frame or two behind, and a player walking
     * forward keeps it that way for as long as they walk. A tenth is the
     * difference between "the edge is arriving" and "the middle has not turned
     * up yet" — the runaway this guards against was nine tenths unbuilt, not one
     * tenth, and {@link #TARGET_VISITS} is a hard brake underneath it either
     * way. Stricter than this and the radius grows only on the occasional frame
     * where the rim happens to have caught up, which turns a second of expansion
     * into a minute of it.
     */
    private static final double UNBUILT_ALLOWED = 0.10;

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
     * @param cpuMs      what the terrain cost the processor, or {@code 0} if
     *                   unknown. <b>The terrain's own time — never the whole
     *                   frame's</b>, which under vsync says nothing; see
     *                   {@link #TARGET_CPU_MS}
     * @param gpuMs      what it cost the graphics card, or {@code 0} if the
     *                   backend cannot say ({@code TerrainPass.lastGpuMillis})
     * @param span       one section's width in world units, for the floor
     */
    public double update(double requested, double affordable, int drawn, int visits,
                         int unbuilt, double cpuMs, double gpuMs, double span) {
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
                || (cpuMs > 0 && cpuMs > TARGET_CPU_MS)
                || (gpuMs > 0 && gpuMs > TARGET_GPU_MS);

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
                && (cpuMs <= 0 || cpuMs < TARGET_CPU_MS * COMFORTABLE)
                && (gpuMs <= 0 || gpuMs < TARGET_GPU_MS * COMFORTABLE);

        if (tooMuch) reach *= 1 - STEP;
        else if (roomToGrow) reach *= 1 + STEP;

        reach = Math.max(floor, Math.min(ceiling, reach));
        return reach;
    }
}
