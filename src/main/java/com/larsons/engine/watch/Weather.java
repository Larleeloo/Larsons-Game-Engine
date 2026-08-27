package com.larsons.engine.watch;

import com.larsons.engine.watch.world.WatchBiome;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

/**
 * What the sky is doing — <b>and what that changes about the animals.</b>
 *
 * <h2>Weather as a mechanic, not a filter</h2>
 *
 * <p>A rain overlay is easy and worth nothing. What makes weather worth having
 * in a game about watching animals is that it moves the animals: a drizzle
 * brings worms up and thrushes down after them, a storm empties the sky of
 * everything that flies, fog is the only hour a shy species will let you within
 * twenty metres, and the clear hour after rain is the best watching of the day.
 * So every condition here carries three numbers the simulation reads —
 * {@link Condition#activity}, {@link Condition#flushScale} and
 * {@link Condition#visibility} — and the overlay is a consequence of the
 * weather rather than the whole of it.
 *
 * <h2>One sky, everywhere</h2>
 *
 * <p>The <b>server owns the weather</b> for the same reason it owns the clock:
 * a party standing in one clearing has to be standing in one rainstorm. It
 * rides along in the snapshot as two numbers — which condition, and how far
 * through it — and clients adopt them. Solo, the same object is ticked locally
 * and nothing else changes.
 *
 * <h2>Where it comes from</h2>
 *
 * <p>Deterministic given the seed and the hour, so two players who join the same
 * world at the same moment get the same forecast even before a snapshot
 * arrives, and so a fresh world is not permanently the one where it never
 * rains. The <em>biome</em> weights the roll — it does not snow in the tropics
 * and it rains a great deal in a rainforest — which means walking north is a
 * change in the weather as well as in the trees.
 */
public final class Weather {

    /** What the sky is doing. */
    public enum Condition {

        /** Nothing at all. The default, and the most common. */
        CLEAR("Clear", 1.00, 1.00, 1.00, 0),

        /** High cloud; a little flatter, a little bolder. */
        CLOUDY("Cloudy", 1.05, 0.95, 0.92, 0),

        /** Steady drizzle. Worms up, thrushes down, everything else damp. */
        DRIZZLE("Drizzle", 1.12, 0.88, 0.72, 220),

        /** Proper rain. Fliers sit it out; the ground feeders do well. */
        RAIN("Rain", 0.92, 0.80, 0.52, 520),

        /** Wind and water. Almost nothing is out, and the fishing is bad. */
        STORM("Storm", 0.55, 1.30, 0.34, 900),

        /** Snow. Quiet, bright, and the tracks are wonderful. */
        SNOW("Snow", 0.78, 0.86, 0.46, 420),

        /**
         * Fog. The condition the game is best in: everything is close, and
         * nothing can see you coming.
         */
        FOG("Fog", 0.96, 0.55, 0.28, 0),

        /** Dry wind. Fliers struggle, everything is jumpy. */
        WIND("Windy", 0.88, 1.18, 0.86, 0);

        private final String label;
        private final double activity;
        private final double flushScale;
        private final double visibility;
        private final int density;

        Condition(String label, double activity, double flushScale, double visibility,
                  int density) {
            this.label = label;
            this.activity = activity;
            this.flushScale = flushScale;
            this.visibility = visibility;
            this.density = density;
        }

        /** What the HUD calls this. */
        public String label() { return label; }

        /** How much more or less is out in it, as a multiplier on encounters. */
        public double activity() { return activity; }

        /**
         * How much further out an animal notices you, as a multiplier on its
         * flush distance. Below one is weather you can use.
         */
        public double flushScale() { return flushScale; }

        /** How far you can see in it, as a multiplier on the fog range. */
        public double visibility() { return visibility; }

        /** How many falling particles to draw, at a reference viewport. */
        public int density() { return density; }

        /** Whether anything falls out of the sky in it. */
        public boolean precipitates() { return density > 0; }

        /** Whether this is snow rather than rain — the streaks are different. */
        public boolean frozen() { return this == SNOW; }

        /** Whether being out in it soaks you. */
        public boolean wet() { return this == DRIZZLE || this == RAIN || this == STORM; }

        /** The condition a saved or received name means; {@link #CLEAR} if unknown. */
        public static Condition of(String name) {
            if (name == null) return CLEAR;
            for (Condition c : values()) {
                if (c.name().equalsIgnoreCase(name)) return c;
            }
            return CLEAR;
        }
    }

    /** Shortest a spell of weather lasts, in seconds. */
    private static final double MIN_SPELL = 150;

    /** Longest, in seconds. */
    private static final double MAX_SPELL = 900;

    /**
     * How long a change takes, in seconds.
     *
     * <p>Weather that switches between frames is weather nobody believes. Every
     * number this class hands out is interpolated across this window, so rain
     * arrives as a thickening drizzle and fog lifts rather than vanishing.
     */
    public static final double TRANSITION = 26;

    private final Random rng;

    private Condition condition = Condition.CLEAR;
    private Condition previous = Condition.CLEAR;
    private double sinceChange;
    private double spell = MIN_SPELL;

    /** The biome the weather was last rolled for, so a walk north changes it. */
    private String biomeKey = "";

    public Weather(long seed) {
        this.rng = new Random(seed ^ 0x5EA50FL);
        this.spell = MIN_SPELL + rng.nextDouble() * (MAX_SPELL - MIN_SPELL);
    }

    /** What it is doing now. */
    public Condition condition() { return condition; }

    /** What it was doing before, which a transition is still showing some of. */
    public Condition previous() { return previous; }

    /** How far through the change to {@link #condition()} we are, {@code 0}–{@code 1}. */
    public double blend() {
        return Math.min(1, sinceChange / TRANSITION);
    }

    /** How long the current spell has been running, in seconds. */
    public double elapsed() { return sinceChange; }

    /**
     * How strongly the weather is showing, {@code 0}–{@code 1} — the number an
     * overlay scales its particle count by, and the one that makes rain arrive
     * rather than appear.
     */
    public double intensity() {
        double now = condition.precipitates() || condition == Condition.FOG ? 1 : 0;
        double was = previous.precipitates() || previous == Condition.FOG ? 1 : 0;
        return was + (now - was) * blend();
    }

    /** A blended multiplier for how much is out. */
    public double activity() {
        return mix(previous.activity(), condition.activity());
    }

    /** A blended multiplier on every animal's flush distance. */
    public double flushScale() {
        return mix(previous.flushScale(), condition.flushScale());
    }

    /** A blended multiplier on how far the fog lets you see. */
    public double visibility() {
        return mix(previous.visibility(), condition.visibility());
    }

    /** How many particles to draw for a viewport of this many pixels. */
    public int particleCount(int viewWidth, int viewHeight) {
        double share = (viewWidth * (double) viewHeight) / (1280.0 * 720.0);
        double density = mix(previous.density(), condition.density());
        return (int) Math.round(density * Math.max(0.35, Math.min(2.5, share)));
    }

    private double mix(double was, double now) {
        double t = blend();
        return was + (now - was) * t;
    }

    /**
     * Advance the weather, in the biome the party is standing in.
     *
     * <p>Called from the game's tick. The biome is passed rather than stored
     * because it is only ever read at the moment a new spell is rolled — the
     * weather does not change because you walked into a desert, but the
     * <em>next</em> weather is a desert's weather.
     */
    public void tick(double dt, WatchBiome biome, WatchClock.Phase phase) {
        if (dt <= 0) return;
        sinceChange += dt;
        if (biome != null) biomeKey = biome.key();
        if (sinceChange < spell) return;
        roll(biome, phase);
    }

    /** Force a new spell now — what a test does, and what a debug key would. */
    public void roll(WatchBiome biome, WatchClock.Phase phase) {
        Condition next = pick(biome, phase);
        // Never roll the same thing twice in a row: a "change" that changes
        // nothing spends a spell and reads as the weather being stuck.
        if (next == condition) next = next == Condition.CLEAR ? Condition.CLOUDY
                : Condition.CLEAR;
        previous = condition;
        condition = next;
        sinceChange = 0;
        spell = MIN_SPELL + rng.nextDouble() * (MAX_SPELL - MIN_SPELL);
    }

    /**
     * Which condition a biome rolls, weighted by its own climate.
     *
     * <p>Clear weather always has the largest share — this is a game about
     * going outside and looking at things, and a world that is under weather
     * more often than not is one where the mechanic is a tax. Everything else
     * is scaled by whether that biome could plausibly produce it.
     */
    private Condition pick(WatchBiome biome, WatchClock.Phase phase) {
        int temperature = biome == null ? 50 : biome.temperature();
        int humidity = biome == null ? 50 : biome.humidity();

        double[] weights = new double[Condition.values().length];
        weights[Condition.CLEAR.ordinal()] = 44;
        weights[Condition.CLOUDY.ordinal()] = 22 + humidity * 0.12;
        weights[Condition.DRIZZLE.ordinal()] = Math.max(0, humidity - 25) * 0.32;
        weights[Condition.RAIN.ordinal()] = Math.max(0, humidity - 40) * 0.34;
        weights[Condition.STORM.ordinal()] = Math.max(0, humidity - 55) * 0.14;
        // Snow needs the cold, and gets none of the rain's share when it is warm.
        weights[Condition.SNOW.ordinal()] = temperature < 34
                ? (34 - temperature) * 0.55 : 0;
        // Fog wants damp air and settles overnight and at dawn, which is also
        // when the best watching is — deliberately.
        double fogHour = switch (phase) {
            case NIGHT, DAWN -> 2.6;
            case MORNING -> 1.4;
            default -> 0.5;
        };
        weights[Condition.FOG.ordinal()] = Math.max(0, humidity - 30) * 0.16 * fogHour;
        weights[Condition.WIND.ordinal()] = 8 + Math.max(0, 45 - humidity) * 0.20;

        double total = 0;
        for (double w : weights) total += w;
        if (total <= 0) return Condition.CLEAR;
        double roll = rng.nextDouble() * total;
        for (int i = 0; i < weights.length; i++) {
            roll -= weights[i];
            if (roll <= 0) return Condition.values()[i];
        }
        return Condition.CLEAR;
    }

    /** One line for the HUD: what it is doing, and whether it is settling in. */
    public String describe() {
        if (blend() < 0.6 && previous != condition) {
            return previous.label() + " → " + condition.label();
        }
        return condition.label();
    }

    // --- the wire and the save ---------------------------------------------------

    /** The two numbers a snapshot carries. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("c", condition.name());
        m.put("p", previous.name());
        m.put("t", sinceChange);
        if (!biomeKey.isEmpty()) m.put("b", biomeKey);
        return m;
    }

    /** Adopt a host's sky. */
    public void load(Map<String, Object> m) {
        if (m == null || m.isEmpty()) return;
        // Read the fallback for `previous` before `condition` is overwritten.
        // Defaulting it to the *new* condition — which is what reading the
        // field after the assignment does — collapses the transition on any
        // payload that happens not to carry "p", so a client would step
        // straight into full rain rather than watching it arrive.
        String was = previous.name();
        condition = Condition.of(WatchJson.str(m, "c", condition.name()));
        previous = Condition.of(WatchJson.str(m, "p", was));
        sinceChange = WatchJson.num(m, "t", sinceChange);
        biomeKey = WatchJson.str(m, "b", biomeKey);
    }

    @Override public String toString() {
        return describe() + " (" + Math.round(sinceChange) + "s of "
                + Math.round(spell) + ")";
    }
}
