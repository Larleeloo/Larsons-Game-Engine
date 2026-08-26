package com.larsons.engine.watch;

import com.larsons.engine.watch.life.AnimalDef;
import com.larsons.engine.watch.life.AnimalRegistry;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One line in the journal: what was seen, where, when, and by whom.
 *
 * <p><b>The record is the game's currency.</b> Nothing here is scored, bought
 * or spent — a sighting is a fact about an afternoon, and the field guide is
 * the accumulation of them. The fields are chosen so the guide's page can say
 * something a player will recognise: not "Banded Finch ✓", but "Banded Finch,
 * Reed Marsh, 07:14, found by Kara".
 *
 * @param species    the species key
 * @param atMillis   wall-clock moment, so the guide can print a real time
 * @param timeOfDay  where in the day it was, for the "seen at dawn" line
 * @param biome      the biome key it was in
 * @param finder     the player who clicked on it
 * @param x          where, so the guide can point you back
 * @param y          likewise
 * @param first      whether this was the first time anybody in the party saw it
 */
public record Sighting(String species, long atMillis, double timeOfDay, String biome,
                       String finder, double x, double y, boolean first) {

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");

    /** The species, or {@code null} if the registry no longer has it. */
    public AnimalDef def() { return AnimalRegistry.byKey(species); }

    /** What the guide's entry reads. */
    public String describe() {
        AnimalDef def = def();
        String name = def != null ? def.name() : species;
        var biomeDef = com.larsons.engine.watch.world.WatchBiomes.byKey(biome);
        String place = biomeDef != null ? biomeDef.displayName() : biome;
        String when = LocalClock(atMillis);
        String who = finder == null || finder.isBlank() ? "" : ", found by " + finder;
        return name + " — " + place + ", " + when + who;
    }

    private static String LocalClock(long millis) {
        if (millis <= 0) return WatchClock.localTimeOf(0).format(CLOCK);
        return CLOCK.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()));
    }

    /** Which part of the day it was. */
    public WatchClock.Phase phase() { return WatchClock.phaseOf(timeOfDay); }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sp", species);
        m.put("t", atMillis);
        m.put("d", timeOfDay);
        m.put("b", biome);
        if (finder != null) m.put("by", finder);
        m.put("x", x);
        m.put("y", y);
        if (first) m.put("first", true);
        return m;
    }

    public static Sighting fromMap(Map<String, Object> m) {
        if (m == null) return null;
        String species = WatchJson.str(m, "sp", null);
        if (species == null) return null;
        return new Sighting(species, WatchJson.big(m, "t", 0), WatchJson.num(m, "d", 0),
                WatchJson.str(m, "b", ""), WatchJson.str(m, "by", null),
                WatchJson.num(m, "x", 0), WatchJson.num(m, "y", 0),
                WatchJson.bool(m, "first", false));
    }
}
