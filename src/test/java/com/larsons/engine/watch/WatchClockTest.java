package com.larsons.engine.watch;

import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The day, which this game takes from yours.
 *
 * <p>"Real-life synchronized day/night cycle" is a small sentence with an
 * awkward consequence: the light is a function of the wall clock, so it cannot
 * be goldened, cannot be replayed, and is different for every developer who
 * runs the suite. Everything here therefore tests the <em>mapping</em> — the
 * pure function from a moment to a time of day and from a time of day to a
 * light — and touches the system clock exactly once, to check that it is
 * wired up at all.
 */
class WatchClockTest {

    private static final ZoneId UTC = ZoneId.of("UTC");

    private static double at(int hour, int minute) {
        return WatchClock.timeOfDay(ZonedDateTime.of(2026, 6, 15, hour, minute, 0, 0, UTC));
    }

    // --- the mapping ------------------------------------------------------------------

    @Test
    void midnightIsZeroAndNoonIsHalf() {
        assertEquals(0.0, at(0, 0), 1e-9, "midnight");
        assertEquals(0.25, at(6, 0), 1e-9, "six in the morning");
        assertEquals(0.5, at(12, 0), 1e-9, "noon");
        assertEquals(0.75, at(18, 0), 1e-9, "six in the evening");
        assertEquals(0.5 + 1.0 / 48, at(12, 30), 1e-9, "half past noon");
    }

    @Test
    void theMappingGoesBothWays() {
        for (int hour = 0; hour < 24; hour++) {
            for (int minute : new int[]{0, 17, 45}) {
                double t = at(hour, minute);
                LocalTime back = WatchClock.localTimeOf(t);
                assertEquals(hour, back.getHour(), hour + ":" + minute);
                assertEquals(minute, back.getMinute(), hour + ":" + minute);
            }
        }
    }

    @Test
    void everyMomentOfTheDayIsInsideTheDay() {
        for (int hour = 0; hour < 24; hour++) {
            double t = at(hour, 0);
            assertTrue(t >= 0 && t < 1, hour + ":00 mapped to " + t);
        }
    }

    // --- the light ---------------------------------------------------------------------

    @Test
    void noonIsBrightAndMidnightIsNot() {
        assertTrue(WatchClock.daylightAt(0.5) > 0.9, "noon is dim");
        assertTrue(WatchClock.daylightAt(0.0) < 0.4, "midnight is bright");
        assertTrue(WatchClock.daylightAt(0.5) > WatchClock.daylightAt(0.25));
        assertTrue(WatchClock.daylightAt(0.25) > WatchClock.daylightAt(0.0));
    }

    /**
     * Night has a floor.
     *
     * <p>Not physics — playability. This is a game about identifying an animal
     * by looking at it, and a night rendered at true darkness is a game you
     * cannot play between dusk and dawn. Moonlight, as one number.
     */
    @Test
    void nightIsNeverPitchBlack() {
        for (double t = 0; t < 1; t += 0.01) {
            double light = WatchClock.daylightAt(t);
            assertTrue(light >= 0.25, "at " + t + " the world is at " + light + " brightness");
            assertTrue(light <= 1.0, "at " + t + " the light is " + light);
        }
    }

    /** The light has to move smoothly, or dusk is a switch rather than a sunset. */
    @Test
    void theLightNeverJumps() {
        double previous = WatchClock.daylightAt(0);
        double worst = 0;
        double worstAt = 0;
        for (double t = 0; t <= 1.0001; t += 0.001) {
            double light = WatchClock.daylightAt(t);
            double step = Math.abs(light - previous);
            if (step > worst) {
                worst = step;
                worstAt = t;
            }
            previous = light;
        }
        assertTrue(worst < 0.02, String.format(
                "the light jumps %.3f in a thousandth of a day at %.3f (%s)",
                worst, worstAt, WatchClock.localTimeOf(worstAt)));
    }

    /**
     * The phases have to line up with the light.
     *
     * <p>They did not, once: the boundaries were placed at the round hours and
     * the sun modelled its crossings elsewhere, so {@code DUSK} was reported
     * over ground already rendered at a fifth of daylight, and the HUD said
     * "dusk" while the screen said "night".
     */
    @Test
    void everyPhaseIsLitTheWayItsNameSuggests() {
        assertEquals(WatchClock.Phase.NIGHT, WatchClock.phaseOf(0.0));
        assertEquals(WatchClock.Phase.MIDDAY, WatchClock.phaseOf(0.5));

        double middayLight = 0, nightLight = 1, duskLight = 0, dawnLight = 0;
        for (double t = 0; t < 1; t += 0.002) {
            double light = WatchClock.daylightAt(t);
            switch (WatchClock.phaseOf(t)) {
                case MIDDAY -> middayLight = Math.max(middayLight, light);
                case NIGHT -> nightLight = Math.min(nightLight, light);
                case DUSK -> duskLight = Math.max(duskLight, light);
                case DAWN -> dawnLight = Math.max(dawnLight, light);
                default -> { }
            }
        }
        assertTrue(middayLight > 0.9, "midday tops out at " + middayLight);
        assertTrue(duskLight > 0.4, "dusk never gets above " + duskLight
                + " — it is being reported over ground that is already night");
        assertTrue(dawnLight > 0.3, "dawn never gets above " + dawnLight);
    }

    @Test
    void everyPhaseHappensAtSomePointInTheDay() {
        java.util.Set<WatchClock.Phase> seen = new java.util.HashSet<>();
        for (double t = 0; t < 1; t += 0.002) seen.add(WatchClock.phaseOf(t));
        for (WatchClock.Phase phase : WatchClock.Phase.values()) {
            assertTrue(seen.contains(phase), phase + " never happens");
        }
    }

    @Test
    void theSunIsUpInTheDayAndDownAtNight() {
        double[] direction = new double[3];
        WatchClock.at(0.5).sunDirection(direction);
        assertTrue(direction[2] > 0.5, "the sun is not overhead at noon");

        WatchClock.at(0.0).sunDirection(direction);
        assertTrue(direction[2] < 0, "the sun is above the horizon at midnight");

        assertTrue(WatchClock.at(0.5).sunAltitude() > WatchClock.at(0.3).sunAltitude());
        assertTrue(!WatchClock.at(0.5).night(), "noon is night");
        assertTrue(WatchClock.at(0.02).night(), "the small hours are not night");
    }

    @Test
    void theSkyAndTheFogFollowTheSun() {
        int blue = 0x6FA8DC;
        int day = WatchClock.at(0.5).skyColour(blue);
        int night = WatchClock.at(0.0).skyColour(blue);
        assertNotEquals(day, night, "the sky is the same colour at noon and at midnight");
        assertTrue(luma(day) > luma(night), "the night sky is brighter than the day sky");
        assertTrue(luma(WatchClock.at(0.5).fogColour(blue))
                > luma(WatchClock.at(0.0).fogColour(blue)), "night fog is brighter than day");
    }

    @Test
    void theLightTintWarmsAtTheEndsOfTheDay() {
        double[] noon = new double[3];
        double[] dusk = new double[3];
        WatchClock.at(0.5).lightTint(noon);
        WatchClock.at(0.77).lightTint(dusk);
        assertTrue(dusk[0] / dusk[2] > noon[0] / noon[2],
                "the evening light is no warmer than the midday light");
    }

    // --- being a clock ------------------------------------------------------------------

    /**
     * A clock started elsewhere does not read this machine's time — but it does
     * still run. That is what a guest needs: the host's time arrives every few
     * seconds, and the sun has to move between those rather than step.
     */
    @Test
    void aClockStartedElsewhereRunsWithoutReadingTheWallClock() {
        WatchClock clock = WatchClock.at(0.4);
        assertEquals(0.4, clock.timeOfDay(), 1e-9);
        assertTrue(!clock.followsWallClock());

        clock.tick(0);
        assertEquals(0.4, clock.timeOfDay(), 1e-9,
                "a clock that was not advanced read the machine's time anyway");

        clock.tick(3600);
        assertEquals(0.4 + 1.0 / 24, clock.timeOfDay(), 1e-9,
                "an hour of ticking did not advance the day by an hour");
    }

    @Test
    void aWallClockReadsTheWallClock() {
        WatchClock clock = WatchClock.fromSystem();
        assertTrue(clock.followsWallClock());
        double expected = WatchClock.timeOfDay(ZonedDateTime.now());
        // A minute of slack: this is the only test that races the real clock.
        assertEquals(expected, clock.timeOfDay(), 1.0 / (24 * 60),
                "the game's day is not the player's day");
    }

    @Test
    void adoptingATimeStopsFollowingTheWallClock() {
        WatchClock clock = WatchClock.fromSystem();
        clock.adopt(0.125);
        assertEquals(0.125, clock.timeOfDay(), 1e-9);
        assertTrue(!clock.followsWallClock(),
                "a guest that adopted the host's time is still reading its own");
        clock.followWallClock();
        assertTrue(clock.followsWallClock());
    }

    @Test
    void realHoursBetweenTwoMomentsIsTheHoursBetweenThem() {
        long start = 1_700_000_000_000L;
        assertEquals(0.0, WatchClock.realHoursBetween(start, start), 1e-9);
        assertEquals(1.0, WatchClock.realHoursBetween(start, start + 3_600_000L), 1e-9);
        assertEquals(48.0, WatchClock.realHoursBetween(start, start + 172_800_000L), 1e-9);
        assertTrue(WatchClock.realHoursBetween(start + 1000, start) <= 0,
                "time ran backwards and the trees grew anyway");
    }

    private static int luma(int rgb) {
        return (((rgb >> 16) & 0xFF) * 30 + ((rgb >> 8) & 0xFF) * 59 + (rgb & 0xFF) * 11) / 100;
    }
}
