package com.larsons.engine.watch.world;

import java.util.Random;

/**
 * What one tree inherited — four traits, each a byte, and the whole of what
 * makes two pines of the same age different trees.
 *
 * <p><b>Four rather than forty.</b> A trait only earns its place if a player
 * can see it in the wood or use it in the garden, and these four can:
 *
 * <ul>
 *   <li>{@link #vigour()} — how fast it moves between growth stages. A vigorous
 *       tree reaches {@link TreeSpecies.Stage#MATURE} in about half the time a
 *       feeble one takes, which is the trait a gardener selects for first.</li>
 *   <li>{@link #canopy()} — how wide the crown spreads, ±35% of the species'
 *       own figure. A wide crown shades more ground, and shade is what several
 *       animal families are looking for.</li>
 *   <li>{@link #hue()} — where in the species' leaf range this tree sits. Two
 *       maples side by side are not the same orange.</li>
 *   <li>{@link #fruit()} — how much fruit it sets, which is what makes a
 *       cultivated tree worth cultivating: fruit is a lure, and a heavy-setting
 *       tree draws birds a barren one does not.</li>
 * </ul>
 *
 * <p><b>Crossing</b> ({@link #cross}) takes each trait from one parent or the
 * other rather than averaging them — averaging collapses a population to the
 * mean in three generations and there is nothing left to select. A small
 * mutation is added on top, so a line can drift past both its parents given
 * enough patience, which is the only reason to keep breeding.
 */
public final class TreeGenome {

    /** How far a single generation can move a trait, up or down. */
    private static final int MUTATION = 22;

    private final int vigour;
    private final int canopy;
    private final int hue;
    private final int fruit;

    public TreeGenome(int vigour, int canopy, int hue, int fruit) {
        this.vigour = clamp(vigour);
        this.canopy = clamp(canopy);
        this.hue = clamp(hue);
        this.fruit = clamp(fruit);
    }

    /**
     * The genome a wild tree at a position has — derived from the position
     * itself, so an untouched forest is the same forest for every player and
     * costs nothing to store.
     */
    public static TreeGenome wild(long positionHash) {
        // Wild trees cluster around the middle: a forest of extremes would
        // leave nothing for a gardener to achieve.
        return new TreeGenome(
                middling(positionHash),
                middling(positionHash >>> 13),
                (int) ((positionHash >>> 26) & 0xFF),
                middling(positionHash >>> 39));
    }

    /** A trait drawn toward 128 — the average of two uniform bytes. */
    private static int middling(long bits) {
        return (int) (((bits & 0xFF) + ((bits >>> 8) & 0xFF)) / 2);
    }

    /** The genome a garden seed of no particular parentage starts from. */
    public static TreeGenome average() { return new TreeGenome(128, 128, 128, 128); }

    public int vigour() { return vigour; }

    public int canopy() { return canopy; }

    public int hue() { return hue; }

    public int fruit() { return fruit; }

    /**
     * How much faster or slower than the species' own figure this tree grows —
     * {@code 0.6} at no vigour, {@code 1.0} in the middle, {@code 1.55} at
     * full.
     */
    public double growthRate() { return 0.6 + vigour / 255.0 * 0.95; }

    /** The multiplier on the species' canopy radius, {@code 0.65}–{@code 1.35}. */
    public double canopyScale() { return 0.65 + canopy / 255.0 * 0.7; }

    /** How much fruit this tree sets, {@code 0}–{@code 1}. */
    public double fruitfulness() { return fruit / 255.0; }

    /**
     * How far this tree's leaves are shifted from the species' colour, as a
     * multiplier per channel written into {@code out} as {@code r, g, b}.
     *
     * <p>Hue is turned into a warm/cool tilt rather than a rotation: a maple
     * whose leaves went blue would not read as a maple, and the point of the
     * trait is to tell two maples apart, not to repaint the species.
     */
    public void tint(double[] out) {
        double t = hue / 255.0 - 0.5;
        out[0] = 1 + t * 0.30;
        out[1] = 1 - Math.abs(t) * 0.10;
        out[2] = 1 - t * 0.26;
    }

    /**
     * Cross this genome with another. Each trait comes from one parent or the
     * other, then drifts by up to {@value #MUTATION}.
     */
    public TreeGenome cross(TreeGenome other, Random rng) {
        return new TreeGenome(
                mix(vigour, other.vigour, rng),
                mix(canopy, other.canopy, rng),
                mix(hue, other.hue, rng),
                mix(fruit, other.fruit, rng));
    }

    private static int mix(int a, int b, Random rng) {
        int inherited = rng.nextBoolean() ? a : b;
        return clamp(inherited + rng.nextInt(MUTATION * 2 + 1) - MUTATION);
    }

    /** The four traits packed into one int, for saves and the wire. */
    public int packed() {
        return (vigour << 24) | (canopy << 16) | (hue << 8) | fruit;
    }

    /** The inverse of {@link #packed()}. */
    public static TreeGenome unpack(int bits) {
        return new TreeGenome((bits >>> 24) & 0xFF, (bits >>> 16) & 0xFF,
                (bits >>> 8) & 0xFF, bits & 0xFF);
    }

    @Override public boolean equals(Object o) {
        return o instanceof TreeGenome g && g.packed() == packed();
    }

    @Override public int hashCode() { return packed(); }

    @Override public String toString() {
        return "vigour " + vigour + ", canopy " + canopy + ", hue " + hue + ", fruit " + fruit;
    }

    private static int clamp(int v) { return v < 0 ? 0 : Math.min(v, 255); }
}
