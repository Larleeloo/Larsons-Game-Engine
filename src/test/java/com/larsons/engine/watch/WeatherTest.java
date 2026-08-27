package com.larsons.engine.watch;

import com.larsons.engine.watch.world.WatchBiome;
import com.larsons.engine.watch.world.WatchBiomes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sky: that it changes, that it changes into things the biome could
 * plausibly produce, that the change is gradual, and that a party shares it.
 */
@Timeout(120)
class WeatherTest {

    private static WatchBiome biome(String key) {
        WatchBiome found = WatchBiomes.byKey(key);
        assertNotNull(found, "no biome " + key);
        return found;
    }

    /** Long enough that a spell must have ended, whatever it rolled. */
    private static void run(Weather weather, WatchBiome where, double seconds) {
        for (double t = 0; t < seconds; t += 0.5) {
            weather.tick(0.5, where, WatchClock.Phase.MORNING);
        }
    }

    @Test
    void startsClearAndChanges() {
        Weather weather = new Weather(12L);
        assertEquals(Weather.Condition.CLEAR, weather.condition(),
                "a new world should open on a clear sky");
        Set<Weather.Condition> seen = EnumSet.noneOf(Weather.Condition.class);
        WatchBiome where = biome("wetland_marsh");
        for (int i = 0; i < 60; i++) {
            weather.roll(where, WatchClock.Phase.MORNING);
            seen.add(weather.condition());
        }
        assertTrue(seen.size() >= 4,
                "sixty rolls in a marsh should produce several conditions, got " + seen);
    }

    /** A roll never lands on what is already up: a change has to change something. */
    @Test
    void neverRollsTheSameConditionTwice() {
        Weather weather = new Weather(3L);
        WatchBiome where = biome("desert");
        for (int i = 0; i < 200; i++) {
            Weather.Condition before = weather.condition();
            weather.roll(where, WatchClock.Phase.MIDDAY);
            assertNotEquals(before, weather.condition(),
                    "the weather changed to what it already was");
        }
    }

    /** A desert does not snow; a tundra can. */
    @Test
    void theBiomeDecidesWhatIsPossible() {
        WatchBiome desert = biome("desert");
        Weather dry = new Weather(7L);
        for (int i = 0; i < 400; i++) {
            dry.roll(desert, WatchClock.Phase.MIDDAY);
            assertNotEquals(Weather.Condition.SNOW, dry.condition(),
                    "it snowed in the desert");
        }

        WatchBiome cold = biome("tundra_barrens");
        Weather frozen = new Weather(7L);
        boolean snowed = false;
        for (int i = 0; i < 400 && !snowed; i++) {
            frozen.roll(cold, WatchClock.Phase.NIGHT);
            snowed = frozen.condition() == Weather.Condition.SNOW;
        }
        assertTrue(snowed, "four hundred rolls on the tundra and never any snow");
    }

    /**
     * Every number the simulation reads is interpolated across the transition,
     * so nothing steps between frames.
     */
    @Test
    void changesAreGradual() {
        Weather weather = new Weather(19L);
        WatchBiome where = biome("rainforest");
        // Roll until something with a real effect comes up.
        for (int i = 0; i < 200 && !weather.condition().precipitates(); i++) {
            weather.roll(where, WatchClock.Phase.MORNING);
        }
        assertTrue(weather.condition().precipitates(),
                "could not find a wet condition to test the transition with");

        assertEquals(0, weather.blend(), 1e-9, "a fresh spell starts at zero blend");
        double first = weather.intensity();
        weather.tick(Weather.TRANSITION * 0.5, where, WatchClock.Phase.MORNING);
        double middle = weather.intensity();
        weather.tick(Weather.TRANSITION * 0.6, where, WatchClock.Phase.MORNING);
        double settled = weather.intensity();

        assertTrue(middle > first, "the weather did not build");
        assertTrue(settled >= middle, "the weather went backwards");
        assertEquals(1, weather.blend(), 1e-9, "the transition never finished");
    }

    /** Fog lets you close; a storm does not. That is the mechanic. */
    @Test
    void conditionsChangeHowCloseYouCanGet() {
        assertTrue(Weather.Condition.FOG.flushScale() < 1,
                "fog should let you get closer");
        assertTrue(Weather.Condition.STORM.flushScale() > 1,
                "a storm should make everything jumpy");
        assertTrue(Weather.Condition.STORM.activity()
                        < Weather.Condition.CLEAR.activity(),
                "less should be out in a storm");
        assertTrue(Weather.Condition.DRIZZLE.activity()
                        > Weather.Condition.CLEAR.activity(),
                "a drizzle brings the ground feeders out");
        for (Weather.Condition condition : Weather.Condition.values()) {
            assertTrue(condition.visibility() > 0 && condition.visibility() <= 1,
                    condition + " has an impossible visibility");
        }
    }

    /** What goes on the wire comes back off it. */
    @Test
    void roundTripsThroughASnapshot() {
        Weather host = new Weather(5L);
        WatchBiome where = biome("boreal_taiga");
        run(host, where, 1200);

        Weather guest = new Weather(999L);
        guest.load(host.toMap());
        assertEquals(host.condition(), guest.condition());
        assertEquals(host.previous(), guest.previous());
        assertEquals(host.blend(), guest.blend(), 1e-6);
        assertEquals(host.visibility(), guest.visibility(), 1e-6);
    }

    /** An empty or missing sky leaves a client's weather alone. */
    @Test
    void toleratesAMissingSky() {
        Weather weather = new Weather(1L);
        weather.roll(biome("pine_forest"), WatchClock.Phase.DUSK);
        Weather.Condition before = weather.condition();
        weather.load(null);
        weather.load(new LinkedHashMap<>());
        assertEquals(before, weather.condition());
    }

    /** The particle count follows the viewport and stays sane at both ends. */
    @Test
    void particleCountScalesWithTheViewport() {
        Weather weather = new Weather(2L);
        for (int i = 0; i < 200
                && weather.condition() != Weather.Condition.RAIN; i++) {
            weather.roll(biome("rainforest"), WatchClock.Phase.MORNING);
        }
        weather.tick(Weather.TRANSITION * 2, biome("rainforest"),
                WatchClock.Phase.MORNING);
        int small = weather.particleCount(640, 360);
        int large = weather.particleCount(2560, 1440);
        assertTrue(small > 0, "rain with no drops in it");
        assertTrue(large > small, "a bigger window should get more rain");
        assertTrue(large < 4000, "a bigger window should not get unbounded rain");
    }

    /** The game ticks its own weather, and the biome underfoot steers it. */
    @Test
    void theGameOwnsOneSky() {
        WatchGame game = new WatchGame(WatchGame.Config.hosted("Sky", 4242L));
        game.join(1, "Larson");
        assertNotNull(game.weather());
        for (int i = 0; i < 4000; i++) game.tick(0.5);
        // Two thousand seconds is several spells; something must have happened.
        Map<String, Object> sky = game.weather().toMap();
        assertTrue(sky.containsKey("c"), "the snapshot carries no condition");
        assertFalse(String.valueOf(sky.get("c")).isBlank());
    }
}
