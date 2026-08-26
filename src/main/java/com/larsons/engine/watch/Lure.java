package com.larsons.engine.watch;

import com.larsons.engine.watch.life.AnimalDef;
import com.larsons.engine.watch.life.Diet;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A feeder somebody put down, with something in it.
 *
 * <p><b>The join between foraging and watching.</b> Everything the food half of
 * the game produces ends up here: a key, a place, and a clock. What it does is
 * one method — {@link #appealTo} — which asks whether a species eats what is in
 * the feeder and, if so, how strongly and from how far. Everything else about
 * attracting animals falls out of that: a cooked food pulls harder and further
 * than a raw one because {@link Forage} says so, a seed feeder does not bring
 * herons because {@link Diet} says so, and a feeder that has been standing for
 * an hour has less in it because of the clock below.
 *
 * <p><b>They run out.</b> A feeder holds a number of servings and loses one
 * every time something feeds from it; an empty feeder still stands there, and
 * refilling it is a reason to walk back. Left alone entirely it also spoils, so
 * a camp cannot be set up once and then farmed forever.
 */
public final class Lure {

    /** Real hours before an untouched feeder's contents are no good. */
    public static final double SPOIL_HOURS = 8;

    /** How many servings a full feeder holds. */
    public static final int SERVINGS = 12;

    private final long id;
    private final String food;
    private final double x, y, z;
    private final String placedBy;
    private final long placedAtMillis;

    private int servings;
    private double hoursStanding;

    public Lure(long id, String food, double x, double y, double z, String placedBy,
                long placedAtMillis) {
        this(id, food, x, y, z, placedBy, placedAtMillis, SERVINGS, 0);
    }

    public Lure(long id, String food, double x, double y, double z, String placedBy,
                long placedAtMillis, int servings, double hoursStanding) {
        this.id = id;
        this.food = food;
        this.x = x;
        this.y = y;
        this.z = z;
        this.placedBy = placedBy;
        this.placedAtMillis = placedAtMillis;
        this.servings = servings;
        this.hoursStanding = hoursStanding;
    }

    public long id() { return id; }

    /** The forage key in it. */
    public String food() { return food; }

    public double x() { return x; }

    public double y() { return y; }

    public double z() { return z; }

    /** Who put it there — what the "someone is feeding" note names. */
    public String placedBy() { return placedBy; }

    public long placedAtMillis() { return placedAtMillis; }

    /** How many feeds are left in it. */
    public int servings() { return servings; }

    /** How long it has stood, in real hours. */
    public double hoursStanding() { return hoursStanding; }

    /** Whether there is anything left worth coming for. */
    public boolean active() { return servings > 0 && hoursStanding < SPOIL_HOURS; }

    /** Whether it has gone off rather than been eaten. */
    public boolean spoiled() { return hoursStanding >= SPOIL_HOURS; }

    /** Something fed here. */
    public void consume() {
        if (servings > 0) servings--;
    }

    /** Fill it up again — with the same food; a different food is a new feeder. */
    public void refill() {
        servings = SERVINGS;
        hoursStanding = 0;
    }

    /** Let real time pass. */
    public void age(double hours) {
        if (hours > 0) hoursStanding += hours;
    }

    /**
     * How strongly this feeder draws a species, and from how far — written into
     * {@code out} as {@code appeal, reach}.
     *
     * <p>Zero on both counts when the species does not eat what is in it, when
     * it is empty, or when it has spoiled — so a bird never sets off toward a
     * feeder it would refuse when it got there.
     *
     * <p>What is left tapers the pull: a nearly empty feeder is worth going to
     * but not worth crossing a valley for, which is what keeps a party moving
     * rather than camping on one spot.
     */
    public void appealTo(AnimalDef def, double[] out) {
        out[0] = 0;
        out[1] = 0;
        if (def == null || !active()) return;
        Forage.draw(food, def.diet(), out);
        if (out[0] <= 0) return;
        double left = servings / (double) SERVINGS;
        double freshness = 1 - hoursStanding / SPOIL_HOURS;
        double scale = (0.45 + 0.55 * left) * Math.max(0.2, freshness);
        out[0] *= scale;
        out[1] *= scale;
    }

    /** Whether a species would come to this at all, wherever it is. */
    public boolean tempts(AnimalDef def) {
        double[] out = new double[2];
        appealTo(def, out);
        return out[0] > 0;
    }

    /** Whether a species standing at a distance would come. */
    public boolean reaches(AnimalDef def, double distance) {
        double[] out = new double[2];
        appealTo(def, out);
        return out[0] > 0 && distance <= out[1];
    }

    /** What the HUD says about it. */
    public String describe() {
        if (spoiled()) return Forage.nameOf(food) + " — spoiled";
        if (servings <= 0) return Forage.nameOf(food) + " — empty";
        return Forage.nameOf(food) + " — " + servings + "/" + SERVINGS;
    }

    // --- persistence --------------------------------------------------------------

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("f", food);
        m.put("x", x);
        m.put("y", y);
        m.put("z", z);
        m.put("n", servings);
        m.put("h", hoursStanding);
        m.put("t", placedAtMillis);
        if (placedBy != null) m.put("by", placedBy);
        return m;
    }

    public static Lure fromMap(Map<String, Object> m) {
        if (m == null) return null;
        return new Lure(WatchJson.big(m, "id", 0), WatchJson.str(m, "f", "grass_seed"),
                WatchJson.num(m, "x", 0), WatchJson.num(m, "y", 0), WatchJson.num(m, "z", 0),
                WatchJson.str(m, "by", null), WatchJson.big(m, "t", 0),
                WatchJson.integer(m, "n", SERVINGS), WatchJson.num(m, "h", 0));
    }

    @Override public String toString() {
        return describe() + " at (" + Math.round(x) + ", " + Math.round(y) + ")";
    }
}
