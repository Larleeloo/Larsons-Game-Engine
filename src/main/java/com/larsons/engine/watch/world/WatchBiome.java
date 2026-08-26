package com.larsons.engine.watch.world;

import java.util.ArrayList;
import java.util.List;

/**
 * One biome: the recipe a stretch of this world is built from.
 *
 * <p>Biomes are <b>data</b>, the way {@link com.larsons.engine.world.gen.Biome}
 * is data for the block world. A biome answers four questions the generator
 * asks in order:
 *
 * <ol>
 *   <li><b>Where does it belong?</b> — {@link #temperature()} and
 *       {@link #humidity()} are the climate it claims and {@link #weight()} is
 *       how strongly it claims it.</li>
 *   <li><b>What shape is the ground?</b> — {@link #targetHeight()}, the
 *       elevation it aims for in metres above the water line, and
 *       {@link #relief()}, how far it wanders from that.</li>
 *   <li><b>What is it made of?</b> — {@link #surface()} over
 *       {@link #surfaceAlt()} on the flats, {@link #cliff()} where the ground
 *       is too steep to hold soil, {@link #shore()} at the water's edge, and
 *       {@link #trail()} wherever a path runs through it.</li>
 *   <li><b>What grows on it?</b> — trees, grass of a length band of its own,
 *       bushes, and the berries and seeds a forager finds.</li>
 * </ol>
 *
 * <p>Which <em>animals</em> live here is deliberately not on this class. A
 * biome would then have to name species, and species are generated in their
 * thousands from families that already know where they live
 * ({@code AnimalFamily.biomes}) — so the dependency runs one way, from the
 * animals to the map, and a biome added here needs no edit over there.
 *
 * <p>Instances are immutable and built through {@link #named}, which reads as a
 * recipe at the call site:
 *
 * <pre>
 *   WatchBiome.named("pine_forest", "Pine Forest")
 *           .climate(30, 58).ground(34, 14).weight(9)
 *           .materials(GRASS, MOSS, ROCK, GRAVEL, TRAIL_EARTH)
 *           .sky(0x8FB4D8, 0xB9CBDC)
 *           .trees(0.46, PINE, SPRUCE, FIR)
 *           .grass(0.70, 0.20, 0.55)
 *           .forage(0.22, List.of("lingonberry"), List.of("pine_seed"))
 *           .build();
 * </pre>
 */
public final class WatchBiome {

    private final String key;
    private final String name;
    private final String blurb;
    private final int temperature;
    private final int humidity;
    private final int strangeness;
    private final int weight;
    private final double targetHeight;
    private final double relief;
    private final WatchMaterial surface;
    private final WatchMaterial surfaceAlt;
    private final WatchMaterial cliff;
    private final WatchMaterial shore;
    private final WatchMaterial trail;
    private final int skyRgb;
    private final int fogRgb;
    private final double treeDensity;
    private final List<TreeSpecies> trees;
    private final double grassDensity;
    private final double grassMin;
    private final double grassMax;
    private final double bushDensity;
    private final List<String> berries;
    private final List<String> seeds;
    private final double rockDensity;

    private WatchBiome(Builder b) {
        this.key = b.key;
        this.name = b.name;
        this.blurb = b.blurb;
        this.temperature = b.temperature;
        this.humidity = b.humidity;
        this.strangeness = b.strangeness;
        this.weight = b.weight;
        this.targetHeight = b.targetHeight;
        this.relief = b.relief;
        this.surface = b.surface;
        this.surfaceAlt = b.surfaceAlt;
        this.cliff = b.cliff;
        this.shore = b.shore;
        this.trail = b.trail;
        this.skyRgb = b.skyRgb;
        this.fogRgb = b.fogRgb;
        this.treeDensity = b.treeDensity;
        this.trees = List.copyOf(b.trees);
        this.grassDensity = b.grassDensity;
        this.grassMin = b.grassMin;
        this.grassMax = b.grassMax;
        this.bushDensity = b.bushDensity;
        this.berries = List.copyOf(b.berries);
        this.seeds = List.copyOf(b.seeds);
        this.rockDensity = b.rockDensity;
    }

    /** Start a biome recipe. */
    public static Builder named(String key, String name) {
        return new Builder(key, name);
    }

    /** Stable identifier — what a save file and the wire use. */
    public String key() { return key; }

    /** What a player sees this place called. */
    public String displayName() { return name; }

    /** One line for the field guide's page about this place. */
    public String blurb() { return blurb; }

    /** The climate band this biome claims, 0 (frozen) – 100 (scorching). */
    public int temperature() { return temperature; }

    /** The moisture band it claims, 0 (arid) – 100 (soaking). */
    public int humidity() { return humidity; }

    /**
     * The third climate axis: how <em>strange</em> a place has to be for this
     * biome to claim it, 0 (ordinary) – 100 (frankly impossible).
     *
     * <p><b>This exists because a rare biome cannot be made rare with a low
     * weight.</b> The first version tried that, and the amethyst grove — sitting
     * at the same temperature and humidity as the deciduous woods with a third
     * of its weight — lost every single square metre of the map to it and never
     * appeared anywhere at all. Rarity is not a smaller share of a contested
     * niche; it is a niche of your own that the world rarely produces. So the
     * three fantasy biomes sit at the far end of an axis whose field spends
     * almost all its time near zero, and win outright on the few percent of the
     * world where it does not.
     */
    public int strangeness() { return strangeness; }

    /** How strongly it claims that band, relative to every other biome. */
    public int weight() { return weight; }

    /** The elevation its ground aims for, in metres above the water line. */
    public double targetHeight() { return targetHeight; }

    /** How far the ground wanders above and below that, in metres. */
    public double relief() { return relief; }

    public WatchMaterial surface() { return surface; }

    /** The second ground material, patched into the first by noise. */
    public WatchMaterial surfaceAlt() { return surfaceAlt; }

    /** What shows through where the slope is too steep to hold soil. */
    public WatchMaterial cliff() { return cliff; }

    /** What the ground turns to at the water's edge. */
    public WatchMaterial shore() { return shore; }

    /** What a trail through this biome is surfaced with. */
    public WatchMaterial trail() { return trail; }

    /** The sky's colour overhead at noon, {@code 0xRRGGBB}. */
    public int skyRgb() { return skyRgb; }

    /** The colour distance fades into here, {@code 0xRRGGBB}. */
    public int fogRgb() { return fogRgb; }

    /** Trees per square metre, before the generator's own clumping. */
    public double treeDensity() { return treeDensity; }

    /** The species that grow here, in the order they are drawn from. */
    public List<TreeSpecies> trees() { return trees; }

    /** How much of the ground carries grass at all, 0–1. */
    public double grassDensity() { return grassDensity; }

    /** The shortest a blade gets here, in metres. */
    public double grassMin() { return grassMin; }

    /** The longest a blade gets here, in metres. */
    public double grassMax() { return grassMax; }

    /** Berry bushes and shrubs per square metre. */
    public double bushDensity() { return bushDensity; }

    /** The forage keys a berry bush here can carry. */
    public List<String> berries() { return berries; }

    /** The forage keys a seed head here can carry. */
    public List<String> seeds() { return seeds; }

    /** Loose boulders and outcrops per square metre. */
    public double rockDensity() { return rockDensity; }

    /** How much strangeness the ordinary world has. */
    public static final int ORDINARY = 12;

    /** {@link #fit(double, double, double)} at an ordinary place. */
    public double fit(double temp, double humid) {
        return fit(temp, humid, ORDINARY);
    }

    /**
     * How well this biome fits a climate, 0 (wrong place) upward.
     *
     * <p>A squared-distance falloff scaled by the biome's weight: a biome is
     * strongest at the centre of its band and fades from it, so borders are a
     * blend rather than a line, and the {@link #weight()} decides which of two
     * overlapping biomes wins the middle ground.
     */
    public double fit(double temp, double humid, double strange) {
        double dt = (temp - temperature) / 50.0;
        double dh = (humid - humidity) / 50.0;
        double ds = (strange - strangeness) / 50.0;
        double distance = dt * dt + dh * dh + ds * ds;
        return weight / (0.25 + distance * 4);
    }

    @Override public String toString() { return name; }

    /** The mutable half of a biome recipe; see {@link WatchBiome#named}. */
    public static final class Builder {
        private final String key;
        private final String name;
        private String blurb = "";
        private int temperature = 50;
        private int humidity = 50;
        private int strangeness = ORDINARY;
        private int weight = 10;
        private double targetHeight = 20;
        private double relief = 10;
        private WatchMaterial surface = WatchMaterial.GRASS;
        private WatchMaterial surfaceAlt = WatchMaterial.DIRT;
        private WatchMaterial cliff = WatchMaterial.ROCK;
        private WatchMaterial shore = WatchMaterial.SAND;
        private WatchMaterial trail = WatchMaterial.TRAIL_EARTH;
        private int skyRgb = 0x8FB6DE;
        private int fogRgb = 0xBFD2E2;
        private double treeDensity;
        private final List<TreeSpecies> trees = new ArrayList<>();
        private double grassDensity = 0.5;
        private double grassMin = 0.15;
        private double grassMax = 0.5;
        private double bushDensity;
        private final List<String> berries = new ArrayList<>();
        private final List<String> seeds = new ArrayList<>();
        private double rockDensity = 0.002;

        private Builder(String key, String name) {
            this.key = key;
            this.name = name;
        }

        public Builder blurb(String text) { this.blurb = text; return this; }

        public Builder climate(int temperature, int humidity) {
            this.temperature = temperature;
            this.humidity = humidity;
            return this;
        }

        public Builder weight(int weight) { this.weight = weight; return this; }

        /**
         * Put this biome on the far end of the strangeness axis — what the
         * three fantasy biomes do, and the only way to make a biome rare
         * without making it impossible. See {@link WatchBiome#strangeness()}.
         */
        public Builder strange(int strangeness) {
            this.strangeness = strangeness;
            return this;
        }

        public Builder ground(double targetHeight, double relief) {
            this.targetHeight = targetHeight;
            this.relief = relief;
            return this;
        }

        public Builder materials(WatchMaterial surface, WatchMaterial surfaceAlt,
                                 WatchMaterial cliff, WatchMaterial shore,
                                 WatchMaterial trail) {
            this.surface = surface;
            this.surfaceAlt = surfaceAlt;
            this.cliff = cliff;
            this.shore = shore;
            this.trail = trail;
            return this;
        }

        public Builder sky(int skyRgb, int fogRgb) {
            this.skyRgb = skyRgb;
            this.fogRgb = fogRgb;
            return this;
        }

        public Builder trees(double density, TreeSpecies... species) {
            this.treeDensity = density;
            for (TreeSpecies s : species) trees.add(s);
            return this;
        }

        public Builder grass(double density, double min, double max) {
            this.grassDensity = density;
            this.grassMin = min;
            this.grassMax = max;
            return this;
        }

        public Builder forage(double bushDensity, List<String> berries, List<String> seeds) {
            this.bushDensity = bushDensity;
            this.berries.addAll(berries);
            this.seeds.addAll(seeds);
            return this;
        }

        public Builder rocks(double density) { this.rockDensity = density; return this; }

        public WatchBiome build() { return new WatchBiome(this); }
    }
}
