package com.larsons.engine.watch;

import com.larsons.engine.watch.life.AnimalDef;
import com.larsons.engine.watch.life.AnimalFamily;
import com.larsons.engine.watch.life.AnimalRegistry;
import com.larsons.engine.watch.world.WatchBiome;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The rod, and the four seconds that matter.
 *
 * <p><b>A lake is a source of the one lure nothing else provides.</b> Every
 * fish-eating family in the guide — herons, kingfishers, ospreys, otters, the
 * whole {@link com.larsons.engine.watch.life.Diet#FISH} column — comes to a
 * feeder with a fish on it and to nothing else. So fishing is not a side
 * activity; it is the only route to about a sixth of the book.
 *
 * <p>The loop is deliberately short and has one decision in it:
 *
 * <pre>
 *   cast  →  waiting (3–16 s)  →  BITE (a window of about a second)
 *              ↘ strike early: MISSED       ↘ strike in the window: LANDED
 *                                            ↘ miss the window: MISSED
 * </pre>
 *
 * <p>What is caught depends on where: the {@link WatchBiome} decides which of
 * the seven {@code FISH} lineages are present, and the same species table the
 * rest of the game uses supplies them — so a fish is a species with a name and
 * a page, and catching a new one is a sighting like any other.
 */
public final class Fishing {

    /** Where in the loop a rod is. */
    public enum Stage {
        /** Not fishing. */
        IDLE,
        /** In the water, nothing yet. */
        WAITING,
        /** Something is on. Strike now. */
        BITE,
        /** Landed; {@link #caught()} says what. */
        LANDED,
        /** Struck too early, or too late. */
        MISSED
    }

    /** The shortest and longest a fish takes to find the bait, in seconds. */
    private static final double MIN_WAIT = 3.0, MAX_WAIT = 16.0;

    /** How long the strike window is open, in seconds. */
    public static final double BITE_WINDOW = 1.05;

    /** How long a landed or missed result stays on screen before it clears. */
    private static final double RESULT_HOLD = 2.2;

    private final Random rng;

    private Stage stage = Stage.IDLE;
    private double timer;
    private double waitFor;
    private AnimalDef hooked;
    private AnimalDef caught;
    private double bobberX, bobberY, bobberZ;

    public Fishing(long seed) {
        this.rng = new Random(seed);
    }

    public Stage stage() { return stage; }

    /** Whether a cast is in the water. */
    public boolean active() { return stage == Stage.WAITING || stage == Stage.BITE; }

    /** What was landed, or {@code null}. */
    public AnimalDef caught() { return caught; }

    /** Where the float is sitting. */
    public double bobberX() { return bobberX; }

    public double bobberY() { return bobberY; }

    public double bobberZ() { return bobberZ; }

    /** How far through the bite window we are, {@code 0}–{@code 1}. */
    public double biteProgress() {
        return stage == Stage.BITE ? Math.min(1, timer / BITE_WINDOW) : 0;
    }

    /**
     * Cast into the water at a point.
     *
     * @param biome where the float lands, which decides what is down there
     * @return {@code false} if a cast is already in the water
     */
    public boolean cast(double x, double y, double z, WatchBiome biome) {
        if (active()) return false;
        bobberX = x;
        bobberY = y;
        bobberZ = z;
        caught = null;
        hooked = pick(biome);
        stage = Stage.WAITING;
        timer = 0;
        // A rarer fish keeps you waiting, which is most of what makes landing
        // one feel like anything.
        double patience = hooked == null ? 1 : 1 + (1 - hooked.rarity().frequency()) * 0.8;
        waitFor = (MIN_WAIT + rng.nextDouble() * (MAX_WAIT - MIN_WAIT)) * patience;
        return true;
    }

    /** Reel in without striking. */
    public void reelIn() {
        stage = Stage.IDLE;
        timer = 0;
        hooked = null;
    }

    /** Let time pass. */
    public void tick(double dt) {
        timer += dt;
        switch (stage) {
            case WAITING -> {
                if (timer >= waitFor) {
                    stage = hooked == null ? Stage.MISSED : Stage.BITE;
                    timer = 0;
                }
            }
            case BITE -> {
                if (timer >= BITE_WINDOW) {
                    stage = Stage.MISSED;
                    timer = 0;
                }
            }
            case LANDED, MISSED -> {
                if (timer >= RESULT_HOLD) {
                    stage = Stage.IDLE;
                    timer = 0;
                }
            }
            case IDLE -> { }
        }
    }

    /**
     * Strike.
     *
     * @return the species landed, or {@code null} for a miss — which includes
     *         striking before anything was there, the commonest mistake and the
     *         one the whole loop is built around
     */
    public AnimalDef strike() {
        if (stage == Stage.BITE) {
            caught = hooked;
            hooked = null;
            stage = Stage.LANDED;
            timer = 0;
            return caught;
        }
        if (stage == Stage.WAITING) {
            stage = Stage.MISSED;
            timer = 0;
            hooked = null;
        }
        return null;
    }

    /**
     * What the satchel gets for a landed fish.
     *
     * <p>The seven fish lineages map onto five forage keys; anything unusual
     * comes up as bait, which is still worth having because a minnow on a
     * feeder brings a kingfisher.
     */
    public static String itemFor(AnimalDef fish) {
        if (fish == null) return null;
        String lineage = fish.lineage().toLowerCase();
        return switch (lineage) {
            case "trout" -> "trout";
            case "char" -> "char";
            case "pike" -> "pike";
            case "perch" -> "perch";
            default -> "minnow_bait";
        };
    }

    /** Which fish species are in the water in a biome. */
    public static List<AnimalDef> waters(WatchBiome biome) {
        List<AnimalDef> out = new ArrayList<>();
        if (biome == null) return out;
        for (AnimalDef def : AnimalRegistry.inBiome(biome.key())) {
            if (def.family() == AnimalFamily.FISH) out.add(def);
        }
        return out;
    }

    /** Weighted by rarity: the common fish come up most of the time. */
    private AnimalDef pick(WatchBiome biome) {
        List<AnimalDef> here = waters(biome);
        if (here.isEmpty()) return null;
        double total = 0;
        for (AnimalDef def : here) total += def.rarity().frequency();
        double roll = rng.nextDouble() * total;
        for (AnimalDef def : here) {
            roll -= def.rarity().frequency();
            if (roll <= 0) return def;
        }
        return here.get(here.size() - 1);
    }

    /** What the HUD says right now. */
    public String hint() {
        return switch (stage) {
            case IDLE -> "";
            case WAITING -> "Waiting…";
            case BITE -> "Strike!";
            case LANDED -> caught == null ? "" : "Landed a " + caught.name() + "!";
            case MISSED -> "It got away.";
        };
    }
}
