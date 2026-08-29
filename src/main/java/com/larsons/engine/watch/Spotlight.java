package com.larsons.engine.watch;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * "There! Look!" — <b>the gesture, as a game mechanic.</b>
 *
 * <p>The brief asks for one thing above all the others in the multiplayer half:
 * spot an animal, click it, and it lights up for everyone else for a moment.
 * That is this. It is the thing you cannot do by shouting a compass bearing at
 * somebody, and it is the whole reason the game is better with friends in it
 * than with friends in another window.
 *
 * <p><b>It is a server message, not a local effect.</b> A client that drew its
 * own outline would be pointing at an animal that, on somebody else's screen, is
 * three metres to the left — the positions are only as synchronised as the last
 * snapshot. So the client asks, the server confirms which animal was meant, and
 * every client is told the animal's id. Everybody then outlines the animal
 * <em>they</em> have, which is the one they can actually see.
 *
 * <p>It lasts {@value #SECONDS} seconds. Long enough to look up and find it,
 * short enough that a party cannot leave the whole wood outlined.
 *
 * @param points what the sighting was worth, and {@code 0} for one that was
 *               already on the open page. It rides here rather than in a
 *               message of its own because the host has already decided it and
 *               this message already names the species — so one integer beside
 *               it is the whole of what a client needs to keep the party's
 *               balance in step. See {@code FieldGuide.credit}.
 */
public record Spotlight(long animalId, String species, String finder, double x, double y,
                        double z, double remaining, boolean discovery, int points) {

    /** How long an outline stays up. */
    public static final double SECONDS = 4.0;

    /** A fresh spotlight on an animal. */
    public static Spotlight of(long animalId, String species, String finder,
                               double x, double y, double z, boolean discovery,
                               int points) {
        return new Spotlight(animalId, species, finder, x, y, z, SECONDS, discovery,
                points);
    }

    /** The same spotlight, {@code dt} seconds later. */
    public Spotlight aged(double dt) {
        return new Spotlight(animalId, species, finder, x, y, z,
                Math.max(0, remaining - dt), discovery, points);
    }

    /** Whether it is still up. */
    public boolean alive() { return remaining > 0; }

    /** How bright the outline is now — it fades over its last second. */
    public double intensity() {
        if (remaining <= 0) return 0;
        return remaining > 1 ? 1 : remaining;
    }

    /** What appears over the animal. */
    public String label() {
        String name = com.larsons.engine.watch.life.AnimalRegistry.byKey(species) != null
                ? com.larsons.engine.watch.life.AnimalRegistry.byKey(species).name()
                : species;
        // What it paid, when it paid anything. On a fresh page this is on every
        // sighting, which is exactly the feeling a stamped page is bought for:
        // a wood you have walked a hundred times is worth something again.
        String worth = points > 0 ? "  +" + points : "";
        if (discovery) return name + " — new!" + worth;
        String who = finder == null || finder.isBlank() ? name : name + " · " + finder;
        return who + worth;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("a", animalId);
        m.put("sp", species);
        if (finder != null) m.put("by", finder);
        m.put("x", x);
        m.put("y", y);
        m.put("z", z);
        if (discovery) m.put("new", true);
        if (points > 0) m.put("pts", points);
        return m;
    }

    public static Spotlight fromMap(Map<String, Object> m) {
        if (m == null) return null;
        return new Spotlight(WatchJson.big(m, "a", 0), WatchJson.str(m, "sp", ""),
                WatchJson.str(m, "by", null), WatchJson.num(m, "x", 0),
                WatchJson.num(m, "y", 0), WatchJson.num(m, "z", 0), SECONDS,
                WatchJson.bool(m, "new", false), WatchJson.integer(m, "pts", 0));
    }
}
