package com.larsons.engine.watch.world;

import com.larsons.engine.level.PerlinNoise;

/**
 * Ground cover, and <b>how long it is</b>.
 *
 * <p>Grass that is one height everywhere is a texture. Grass with a length
 * that varies is terrain you can read: long in the hollows where the water
 * gathers, cropped on the ridge where the wind gets at it, and knee-high across
 * a prairie where nothing stops it. So a blade's length comes from a
 * low-frequency field of its own, mapped into the biome's
 * {@code [grassMin, grassMax]} band — which means the same field produces
 * ankle-high tundra and shoulder-high sunflower prairie without a second
 * mechanism.
 *
 * <p><b>Placement is a hash, not a list.</b> Every blade is decided by the
 * position it would grow at, so grass is generated, never stored, and two
 * players see the same tuft. The candidate positions are a {@value #SPACING}-
 * metre grid jittered inside its own cell, which is a cheap approximation of a
 * blue-noise distribution: no two blades in one cell, and no visible rows.
 *
 * <p><b>Only close up.</b> A blade is two triangles and there are hundreds of
 * thousands of them within sight; grass is built for the chunks nearest the
 * camera and for no others (see {@code ChunkStreamer.grassRadius}). At any
 * distance where the individual blade is smaller than a pixel it has been
 * replaced by the ground's own colour, which is what it would have averaged to.
 */
public final class GrassField {

    /** Metres between candidate blades. */
    public static final double SPACING = 1.1;

    /** How much of a blade's height the wind can shear its tip by. */
    private static final double WIND_SHEAR = 0.32;

    private final long seed;
    private final PerlinNoise lengthNoise;
    private final PerlinNoise windNoise;

    public GrassField(long seed) {
        this.seed = seed;
        this.lengthNoise = new PerlinNoise(seed + 601);
        this.windNoise = new PerlinNoise(seed + 607);
    }

    /**
     * Whether a blade grows at a candidate position.
     *
     * <p>Takes the slope because grass does not grow on a cliff, and the water
     * depth because it does not grow in a lake either — the marsh's reeds are a
     * biome with a very long grass band, not grass underwater.
     */
    public boolean grows(double x, double y, WatchBiome biome, double slope, double water) {
        if (water > 0.05 || slope > 0.9) return false;
        return unit(hash(x, y, 0x6B4A)) < biome.grassDensity();
    }

    /**
     * How long the blade at a position is, in metres — the biome's band,
     * indexed by a slow field so length varies over tens of metres rather than
     * per blade, plus a little per-blade jitter so a patch is not a lawn.
     */
    public double lengthAt(double x, double y, WatchBiome biome) {
        double field = (lengthNoise.fbm(x * 0.012, y * 0.012, 3, 0.5, 2) + 1) / 2;
        double jitter = unit(hash(x, y, 0x1B37)) * 0.25 - 0.125;
        double t = clamp01(field + jitter);
        return biome.grassMin() + (biome.grassMax() - biome.grassMin()) * t;
    }

    /**
     * How far the tip of a blade is pushed off vertical at a moment, written
     * into {@code out} as {@code dx, dy} in metres.
     *
     * <p>One field, sampled at the blade's own position and scrolled with the
     * clock, so a whole meadow leans together and then recovers together
     * instead of every blade waving on its own phase.
     */
    public void windAt(double x, double y, double seconds, double length, double[] out) {
        double phase = seconds * 0.55;
        double gust = windNoise.fbm(x * 0.03 + phase, y * 0.03, 2, 0.5, 2);
        double side = windNoise.fbm(y * 0.03 - phase, x * 0.03, 2, 0.5, 2);
        double lean = length * WIND_SHEAR * (0.55 + 0.45 * gust);
        out[0] = lean;
        out[1] = lean * side * 0.4;
    }

    /**
     * The jittered position of the candidate blade in cell {@code (cx, cy)},
     * written into {@code out} as {@code x, y} in world metres.
     */
    public void positionOf(int cx, int cy, double[] out) {
        long h = hash(cx * SPACING, cy * SPACING, 0x77C1);
        out[0] = (cx + 0.15 + 0.7 * unit(h)) * SPACING;
        out[1] = (cy + 0.15 + 0.7 * roll(h, 19)) * SPACING;
    }

    /** How much darker or lighter one blade is than its neighbours, ±1. */
    public double tintOf(double x, double y) {
        return unit(hash(x, y, 0x3F09)) * 2 - 1;
    }

    /**
     * A stable hash of a position, quantised to a sixty-fourth of a metre so
     * two callers who arrived at "the same place" by different arithmetic get
     * the same answer. Salted with the world seed, so two worlds do not share
     * their tufts.
     */
    private long hash(double x, double y, int salt) {
        long ix = Math.round(x * 64);
        long iy = Math.round(y * 64);
        long h = seed ^ (ix * 0x9E3779B97F4A7C15L) ^ (iy * 0xBF58476D1CE4E5B9L) ^ salt;
        h = (h ^ (h >>> 30)) * 0x94D049BB133111EBL;
        h = (h ^ (h >>> 27)) * 0xD6E8FEB86659FD93L;
        return h ^ (h >>> 31);
    }


    /**
     * An independent value in {@code [0, 1)} drawn from one hash — the
     * {@code stream}-th of them.
     *
     * <p><b>Not a shift of the hash, which is what this replaced and which
     * silently did not work.</b> {@link #unit} takes the top fifty-three bits,
     * so asking for {@code unit(h >>> 34)} leaves thirty bits above the point
     * and twenty-three zeroes below it: every such "roll" came out under
     * {@code 2}<sup>-23</sup>. Every density test in the generator was
     * therefore passing unconditionally — every candidate cell grew a tree, and
     * every species in the registry came out common and tameable. Re-mixing
     * costs three multiplies and cannot fail this way.
     */
    private static double roll(long h, int stream) {
        long x = h + stream * 0x9E3779B97F4A7C15L;
        x = (x ^ (x >>> 30)) * 0xBF58476D1CE4E5B9L;
        x = (x ^ (x >>> 27)) * 0x94D049BB133111EBL;
        x ^= x >>> 31;
        return (x >>> 11) * 0x1.0p-53;
    }

    private static double unit(long h) {
        return (h >>> 11) * 0x1.0p-53;
    }

    private static double clamp01(double v) {
        return v < 0 ? 0 : Math.min(v, 1);
    }
}
