package com.larsons.engine.watch.world;

import com.larsons.engine.level.PerlinNoise;

/**
 * The shape of the world, as a pure function of {@code (seed, x, y)}.
 *
 * <p><b>Pure is the whole design.</b> Nothing here remembers anything, nothing
 * here depends on what has been generated before, and every method can be
 * called from any thread at any time. That is what lets
 * {@link ChunkStreamer} build eight chunks at once on eight workers in whatever
 * order they finish, throw them away when the party walks off, and rebuild them
 * byte-identical an hour later — and it is what lets two players a kilometre
 * apart agree about a hillside neither of them has told the other about.
 *
 * <h2>The pipeline, per sample</h2>
 *
 * <ol>
 *   <li><b>Warp</b> — the sample point is displaced by a slow noise field
 *       before anything else reads it, which is what turns the straight-ish
 *       borders a climate field produces into the wandering ones a map has.</li>
 *   <li><b>Climate</b> — temperature, humidity and an elevation
 *       <em>potential</em>. The potential rather than the finished height,
 *       because the height depends on the biome and the biome depends on the
 *       climate; feeding the answer back into the question makes the two
 *       oscillate and the borders shimmer.</li>
 *   <li><b>Biome</b> — every biome scores itself against that climate
 *       ({@link WatchBiome#fit}); the best fit supplies the materials, and the
 *       best <em>three</em>, weighted, supply the height target and the relief.
 *       Picking the materials while blending the numbers is what gives sharp
 *       biome borders over ground that never steps.</li>
 *   <li><b>Relief</b> — ridged noise for the spines of ranges, fractal noise
 *       for the lumps on them, scaled by the blended relief.</li>
 *   <li><b>Seas and lakes</b> — a very slow continent field drops whole
 *       regions below the water line; a faster lake field dimples the land.</li>
 *   <li><b>Trails</b> — {@link TrailNetwork} says how strongly a path runs
 *       here, and the ground is pulled toward its own local average by that
 *       much, which is what cuts a level path across a slope.</li>
 * </ol>
 *
 * <p>Heights are <b>metres</b>, and the water line is <b>zero</b>. A biome's
 * {@link WatchBiome#targetHeight()} is therefore read directly as "how far
 * above the sea this place sits", which is the only unit anybody wants to think
 * in while tuning one.
 */
public final class TerrainField {

    /** The height still water stands at, in metres. Everything else is relative to it. */
    public static final double WATER_LEVEL = 0;

    /** How deep the ocean floor is allowed to get, in metres below the line. */
    private static final double OCEAN_FLOOR = -26;

    /** Above this slope (metres of rise per metre across) soil does not hold. */
    private static final double CLIFF_SLOPE = 0.85;

    /**
     * How far out the samples that establish a path's level are taken, in
     * metres.
     *
     * <p><b>Measured, not guessed, and the first guess was wrong in the
     * interesting direction.</b> At 7 m the ring sat so close to the point it
     * was levelling that it read almost the same ground, the correction it
     * produced was almost nothing, and the tread came out <em>steeper</em> than
     * the open country beside it — 1.07× the mean grade, when the whole promise
     * of a path is that it is easier walking than the hillside.
     *
     * <p>The reach trades two things against each other. Wider reads a level
     * from further out, so the path is flatter along itself; but the ground it
     * is being pulled to is further from where it started, so the bank at the
     * edge of the cut is steeper. Across a sweep at 7, 9, 13, 18 and 26 m:
     *
     * <pre>
     *   reach   grade vs open   bank grade
     *      7        1.074          0.189
     *     13        1.016          0.212
     *     18        0.963          0.250
     *     26        0.880          0.334
     * </pre>
     *
     * <p>Eighteen is where the tread first becomes genuinely easier going than
     * the country around it while the bank is still a bank rather than a wall.
     *
     * <p>What this does <b>not</b> do is level a hillside, and it cannot: an
     * average taken symmetrically about a point is unchanged by a constant
     * gradient, so a path down a mountain still goes down the mountain. It
     * takes the bumps out from under your feet — a fifth less curvature on the
     * tread than off it — which is what a trail is.
     */
    private static final double SMOOTHING_REACH = 18;

    private final long seed;
    private final TrailNetwork trails;

    private final PerlinNoise warpA, warpB;
    private final PerlinNoise tempNoise, humidNoise, elevNoise, strangeNoise;
    private final PerlinNoise contNoise, ridgeNoise, detailNoise, lakeNoise;
    private final PerlinNoise patchNoise, cliffNoise;

    public TerrainField(long seed) {
        this.seed = seed;
        this.trails = new TrailNetwork(seed ^ 0x7A115L);
        this.warpA = new PerlinNoise(seed + 101);
        this.warpB = new PerlinNoise(seed + 103);
        this.tempNoise = new PerlinNoise(seed + 211);
        this.humidNoise = new PerlinNoise(seed + 223);
        this.elevNoise = new PerlinNoise(seed + 227);
        this.strangeNoise = new PerlinNoise(seed + 229);
        this.contNoise = new PerlinNoise(seed + 307);
        this.ridgeNoise = new PerlinNoise(seed + 311);
        this.detailNoise = new PerlinNoise(seed + 313);
        this.lakeNoise = new PerlinNoise(seed + 401);
        this.patchNoise = new PerlinNoise(seed + 409);
        this.cliffNoise = new PerlinNoise(seed + 419);
    }

    /** The seed this field was built from. */
    public long seed() { return seed; }

    /** The trail network laid over it. */
    public TrailNetwork trails() { return trails; }

    // --- climate ------------------------------------------------------------------

    /**
     * The climate at a point, written into {@code out} as
     * {@code temperature, humidity, strangeness} (all 0–100).
     *
     * <p>The third axis is what makes a rare biome possible: see
     * {@link WatchBiome#strangeness()}. Its field is squared-and-cubed rather
     * than merely stretched, so it is near zero across almost the whole world
     * and only occasionally — a few percent of it — climbs far enough for the
     * fantasy biomes to claim anything.
     *
     * <p>Writes into a caller-owned array of at least three: a chunk asks this
     * a thousand times and the allocation would cost more than the noise.
     */
    public void climateAt(double x, double y, double[] out) {
        double wx = x + warpA.fbm(x * 0.0009, y * 0.0009, 3, 0.5, 2) * 90;
        double wy = y + warpB.fbm(x * 0.0009, y * 0.0009, 3, 0.5, 2) * 90;
        double elevation = spread(elevNoise.fbm(wx * 0.00055, wy * 0.00055, 3, 0.5, 2));
        double temp = 50 + spread(tempNoise.fbm(wx * 0.00075, wy * 0.00075, 4, 0.5, 2)) * 52
                - Math.max(0, elevation) * 26;
        double humid = 50 + spread(humidNoise.fbm(wx * 0.00085, wy * 0.00085, 4, 0.5, 2)) * 52
                + Math.max(0, -elevation) * 10;
        double strange = WatchBiome.ORDINARY
                + Math.pow(Math.max(0, spread(strangeNoise.fbm(wx * 0.0011, wy * 0.0011,
                        3, 0.5, 2))), 3) * 130;
        out[0] = clamp(temp, 0, 100);
        out[1] = clamp(humid, 0, 100);
        out[2] = clamp(strange, 0, 100);
    }

    /**
     * Stretch a noise value out toward its extremes.
     *
     * <p><b>Without this the world is three biomes.</b> Fractal noise is a sum
     * of octaves and a sum of anything is bell-shaped: four octaves of Perlin
     * spend most of their time within a third of zero and almost never reach
     * ±1. Feeding that straight into a climate square puts nearly every point
     * near the middle of it, so the temperate biomes take the whole map and the
     * desert, the rainforest and the tundra are places that exist in the source
     * and not in the world. Measured before this was added: thirteen of the
     * twenty biomes ever appeared, and two of them covered two thirds of the
     * ground.
     *
     * <p>A {@code tanh} gain flattens the tails into a bounded range and a
     * signed power below one pushes the mass outward, which between them turn
     * that bell into something much closer to flat. The exponents were chosen
     * by sampling: at these values every biome appears and none of them takes
     * more than about a seventh of the map.
     */
    private static double spread(double noise) {
        double gained = Math.tanh(noise * 2.7);
        return Math.signum(gained) * Math.pow(Math.abs(gained), 0.62);
    }

    /** The biome at a point — the best fit for its climate. */
    public WatchBiome biomeAt(double x, double y) {
        double[] climate = new double[3];
        climateAt(x, y, climate);
        return WatchBiomes.bestFit(climate[0], climate[1], climate[2]);
    }

    // --- height -------------------------------------------------------------------

    /**
     * The ground's height at a point, in metres above the water line.
     *
     * <p>This is the expensive one — eight noise fields and a trail query — and
     * it is what everything else in the game is measured against. Chunk
     * generation calls it once per vertex on a worker thread; the physics calls
     * it a handful of times per player per tick.
     */
    public double heightAt(double x, double y) {
        double base = baseHeightAt(x, y);
        double trail = trails.strengthAt(x, y);
        if (trail <= 0) return base;
        // A path is cut level: the ground is pulled toward the level of the
        // undisturbed terrain in a ring around it, by however strongly the
        // trail runs here.
        //
        // Eight samples on a ring rather than four on the axes. Four is a
        // cross, and a cross has a direction — a path crossing a ridge at
        // forty-five degrees was levelled along two axes that both ran along
        // the ridge, and stepped. Eight is close enough to a disc that the
        // answer does not depend on which way the ridge happens to lie.
        double diagonal = SMOOTHING_REACH * 0.70710678;
        double average = (baseHeightAt(x - SMOOTHING_REACH, y)
                + baseHeightAt(x + SMOOTHING_REACH, y)
                + baseHeightAt(x, y - SMOOTHING_REACH)
                + baseHeightAt(x, y + SMOOTHING_REACH)
                + baseHeightAt(x - diagonal, y - diagonal)
                + baseHeightAt(x + diagonal, y - diagonal)
                + baseHeightAt(x - diagonal, y + diagonal)
                + baseHeightAt(x + diagonal, y + diagonal)) / 8;
        return base + (average - base) * trail * 0.85;
    }

    /** The height before any trail is cut into it. */
    private double baseHeightAt(double x, double y) {
        double wx = x + warpA.fbm(x * 0.0009, y * 0.0009, 3, 0.5, 2) * 90;
        double wy = y + warpB.fbm(x * 0.0009, y * 0.0009, 3, 0.5, 2) * 90;

        double[] climate = new double[3];
        climateAt(x, y, climate);
        double[] shape = new double[2];
        blendedShape(climate[0], climate[1], climate[2], shape);
        double target = shape[0];
        double relief = shape[1];

        // Ridged noise: |fbm| inverted and squared, which puts a sharp crest
        // where the field crosses zero. This is what makes a range read as a
        // range rather than as a row of hills.
        double ridged = 1 - Math.abs(ridgeNoise.fbm(wx * 0.0022, wy * 0.0022, 4, 0.5, 2));
        ridged = ridged * ridged * 2 - 1;
        double detail = detailNoise.fbm(wx * 0.0085, wy * 0.0085, 4, 0.5, 2);
        double height = target + relief * (ridged * 0.62 + detail * 0.38);

        // Continents. A very slow field, stretched by the same curve the
        // climate is, so it reaches its extremes and there is real ocean rather
        // than a world of shallow puddles. The push is smooth, so a coastline
        // has a beach on it instead of a step.
        double continent = spread(contNoise.fbm(wx * 0.00028, wy * 0.00028, 3, 0.5, 2));
        if (continent < 0.12) {
            double under = (0.12 - continent) / 1.12;
            height -= Math.pow(under, 1.6) * 120;
        }

        // Lakes: a dimple where the lake field peaks, and only in a basin — a
        // lake part-way up a mountain is a hole in it.
        //
        // <b>Every gate here is a ramp and not an `if`.</b> The first version
        // wrote this as `if (height < target + relief * 0.5)`, which is a
        // condition on the very quantity being modified: two samples two metres
        // apart could land either side of it and differ by the whole depth of
        // the lake. That is what a cliff is, and the world was full of them —
        // measured at up to eighteen metres of step across a two-metre quad,
        // which at eye level is a wall you cannot see over standing in ground
        // that is otherwise a gentle slope.
        double lake = spread(lakeNoise.fbm(wx * 0.0035, wy * 0.0035, 3, 0.5, 2));
        if (lake > 0.62) {
            double dip = (lake - 0.62) / 0.38;
            double inBasin = ramp(height, target + relief * 0.95, target + relief * 0.15);
            double aboveWater = ramp(height, WATER_LEVEL - 3, WATER_LEVEL + 5);
            height -= dip * dip * 26 * inBasin * aboveWater;
        }

        return Math.max(OCEAN_FLOOR, height);
    }

    /**
     * The height target and relief for a climate, as a weighted blend over
     * every biome, written into {@code out}.
     *
     * <p><b>Every biome, with the weights sharpened</b> — not the best three.
     * Taking a fixed number of winners is the obvious thing and it does not
     * work: the moment the third-placed biome and the fourth-placed one swap,
     * the blend jumps by whatever those two biomes disagree about, and since
     * that swap happens along a line through the world, the world gets a step
     * along that line. Cubing each biome's fit gives the same effect the
     * top-three was reaching for — the nearest biome dominates and a distant
     * one contributes almost nothing — while staying continuous everywhere,
     * because nothing is ever dropped from the sum.
     */
    private void blendedShape(double temp, double humid, double strange, double[] out) {
        double total = 0, height = 0, relief = 0;
        for (WatchBiome b : WatchBiomes.all()) {
            double fit = b.fit(temp, humid, strange);
            double weight = fit * fit * fit;
            total += weight;
            height += b.targetHeight() * weight;
            relief += b.relief() * weight;
        }
        if (total <= 0) {
            WatchBiome fallback = WatchBiomes.defaultBiome();
            out[0] = fallback.targetHeight();
            out[1] = fallback.relief();
            return;
        }
        out[0] = height / total;
        out[1] = relief / total;
    }

    /**
     * A smooth 0→1 ramp as {@code v} moves from {@code from} to {@code to} —
     * the shape every gate in the generator has to have. A hard {@code if} on a
     * continuous quantity is how a heightfield gets cliffs in it that nothing
     * in the design put there.
     */
    private static double ramp(double v, double from, double to) {
        if (from == to) return v >= to ? 1 : 0;
        double t = (v - from) / (to - from);
        t = t < 0 ? 0 : Math.min(t, 1);
        return t * t * (3 - 2 * t);
    }

    // --- surface ------------------------------------------------------------------

    /**
     * What the ground is made of at a point.
     *
     * <p>Takes the height and the slope rather than computing them, because the
     * caller that wants this — the chunk mesher — has a whole heightfield in
     * hand and can read a slope off it for the price of two subtractions.
     * Working them out again here would triple the cost of a chunk.
     *
     * @param slope metres of rise per metre across, as read off the heightfield
     */
    public WatchMaterial surfaceAt(double x, double y, double height, double slope,
                                   WatchBiome biome) {
        if (height < WATER_LEVEL) {
            // Under water: the shore material in the shallows, silt below it.
            return height > WATER_LEVEL - 2.5 ? biome.shore()
                    : (height > WATER_LEVEL - 9 ? WatchMaterial.GRAVEL : WatchMaterial.CLAY);
        }
        if (height < WATER_LEVEL + 1.2) return biome.shore();

        double trail = trails.strengthAt(x, y);
        // Only where the path is actually walked, not out across its shoulder —
        // the shoulder is for the ground's shape, not for its colour.
        if (trail > 0.55 && slope < CLIFF_SLOPE) return biome.trail();

        // A steep face sheds soil. The threshold is jittered by a noise field
        // so the line between grass and rock is ragged rather than a contour.
        double cliffLine = CLIFF_SLOPE
                + cliffNoise.noise(x * 0.06, y * 0.06) * 0.22;
        if (slope > cliffLine) return biome.cliff();

        // Snow caps: only where the biome is cold to begin with, so a tropical
        // peak stays bare rock.
        if (biome.temperature() < 40 && height > biome.targetHeight() + biome.relief() * 0.75) {
            return WatchMaterial.SNOW;
        }

        double patch = patchNoise.fbm(x * 0.028, y * 0.028, 2, 0.5, 2);
        return patch > 0.18 ? biome.surfaceAlt() : biome.surface();
    }

    /**
     * How deep the water is over a point, in metres; {@code 0} on dry land.
     */
    public double waterDepth(double height) {
        return Math.max(0, WATER_LEVEL - height);
    }

    /** How strongly a trail runs at a point; {@code 0} to {@code 1}. */
    public double trailAt(double x, double y) {
        return trails.strengthAt(x, y);
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : Math.min(v, hi);
    }
}
