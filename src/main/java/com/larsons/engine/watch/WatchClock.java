package com.larsons.engine.watch;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * What time it is — <b>the real time, where you are.</b>
 *
 * <p>Every other clock in this engine counts ticks. This one reads the wall
 * clock: local midnight is {@code 0.0}, noon is {@code 0.5}, and a session
 * started at six in the evening starts at dusk. That is the point of it. A
 * game about animals is a game about <em>when</em> animals are out, and a day
 * that is twenty minutes long turns "come back at dawn" into "wait a bit",
 * which is not the same instruction at all.
 *
 * <p><b>Online, the host's clock wins.</b> The server puts its own time of day
 * in every snapshot and clients adopt it, so a party spread across three time
 * zones shares one sunset — which is the only arrangement in which "meet me at
 * the lake at dusk" means anything. Solo, the clock is your own.
 *
 * <p>The mapping is a pure function ({@link #timeOfDay(ZonedDateTime)}) so it
 * can be tested without waiting twelve hours, and the object holds a small
 * offset so a save can be resumed at the time it was left if a host ever wants
 * that. Growth — trees, crops — is measured in {@link #realHoursBetween} rather
 * than in ticks, so a sapling planted before bed is measurably taller in the
 * morning whether or not anybody was logged in.
 */
public final class WatchClock {

    /**
     * Where in the day each phase begins, as a fraction of it.
     *
     * <p><b>Set against the sun, not by eye.</b> {@link #sunAltitude()} models
     * an equinox: the sun crosses the horizon at 06:00 and again at 18:00. The
     * phase boundaries have to straddle those two moments or the game says
     * "dusk" while the sun has been down for three quarters of an hour — which
     * is what the first set of numbers did, and it made the dusk hour render
     * at a fifth of daylight.
     */
    private static final double DAWN_START = 0.19;    // 04:34
    private static final double MORNING_START = 0.30; // 07:12
    private static final double NOON_START = 0.44;    // 10:34
    private static final double AFTERNOON_START = 0.57; // 13:41
    private static final double DUSK_START = 0.71;    // 17:02
    private static final double NIGHT_START = 0.83;   // 19:55

    /** The parts of the day an animal's activity window is written in. */
    public enum Phase {
        NIGHT("Night"),
        DAWN("Dawn"),
        MORNING("Morning"),
        MIDDAY("Midday"),
        AFTERNOON("Afternoon"),
        DUSK("Dusk");

        private final String label;

        Phase(String label) { this.label = label; }

        /** What a player-facing panel calls this. */
        public String label() { return label; }

        /** Whether this is one of the two half-lit hours. */
        public boolean crepuscular() { return this == DAWN || this == DUSK; }
    }

    private final ZoneId zone;
    private double timeOfDay;
    private boolean followsWallClock;

    private WatchClock(ZoneId zone, double timeOfDay, boolean followsWallClock) {
        this.zone = zone;
        this.timeOfDay = wrap(timeOfDay);
        this.followsWallClock = followsWallClock;
    }

    /** A clock synchronised to this machine's local time. */
    public static WatchClock fromSystem() {
        ZoneId zone = ZoneId.systemDefault();
        return new WatchClock(zone, timeOfDay(ZonedDateTime.now(zone)), true);
    }

    /**
     * A clock started at a time of day rather than at this machine's — for
     * tests, and for a client following a host.
     *
     * <p>Started, not frozen: it does not read the wall clock, but
     * {@link #tick} still advances it. That is what a guest needs — the host's
     * time arrives every few seconds and the sun has to keep moving between
     * those, not step. A test that wants a still picture simply does not tick
     * it.
     */
    public static WatchClock at(double timeOfDay) {
        return new WatchClock(ZoneId.systemDefault(), timeOfDay, false);
    }

    /**
     * The time of day a wall-clock moment maps to: {@code 0} at midnight,
     * {@code 0.5} at noon, monotonic in between.
     *
     * <p>Pure, and public, because it is the one piece of this that has to be
     * checked rather than watched. Sub-second resolution is deliberately kept —
     * the sun visibly moves during a long session.
     */
    public static double timeOfDay(ZonedDateTime moment) {
        LocalTime local = moment.toLocalTime();
        double seconds = local.toSecondOfDay() + local.getNano() / 1_000_000_000.0;
        return seconds / 86400.0;
    }

    /**
     * The inverse: what local time a time of day is, for the HUD's clock.
     *
     * <p><b>Rounded, not floored.</b> A time of day is a division by 86400 and
     * the multiplication back does not always land on the integer it came from:
     * 07:17 leaves here as 0.3034722…, comes back as 26219.999999999996, and
     * flooring that printed <em>07:16</em> on the HUD and in every sighting the
     * guide recorded. Half a second of rounding costs nothing and is right.
     */
    public static LocalTime localTimeOf(double timeOfDay) {
        double seconds = wrap(timeOfDay) * 86400;
        return LocalTime.ofSecondOfDay(Math.floorMod(Math.round(seconds), 86400L));
    }

    /** Real hours between two wall-clock stamps; what growth is measured in. */
    public static double realHoursBetween(long fromEpochMillis, long toEpochMillis) {
        if (toEpochMillis <= fromEpochMillis) return 0;
        return (toEpochMillis - fromEpochMillis) / 3_600_000.0;
    }

    /** Where in the day we are: {@code 0} midnight, {@code 0.5} noon. */
    public double timeOfDay() { return timeOfDay; }

    /** Whether this clock is still following the machine's own time. */
    public boolean followsWallClock() { return followsWallClock; }

    /**
     * Adopt a time of day sent by the host, and stop following the local wall
     * clock. Every client in a session does this on every snapshot.
     */
    public void adopt(double hostTimeOfDay) {
        this.timeOfDay = wrap(hostTimeOfDay);
        this.followsWallClock = false;
    }

    /** Go back to following this machine's clock — what leaving a session does. */
    public void followWallClock() {
        this.followsWallClock = true;
        this.timeOfDay = timeOfDay(ZonedDateTime.now(zone));
    }

    /**
     * Advance by {@code seconds} of real time.
     *
     * <p>A clock following the wall clock re-reads it rather than accumulating,
     * because accumulating drifts and because a laptop that was asleep for two
     * hours should wake up two hours later in the game as well.
     */
    public void tick(double seconds) {
        if (followsWallClock) {
            timeOfDay = timeOfDay(ZonedDateTime.now(zone));
        } else if (seconds > 0) {
            timeOfDay = wrap(timeOfDay + seconds / 86400.0);
        }
    }

    /** Which part of the day it is. */
    public Phase phase() { return phaseOf(timeOfDay); }

    /** Which part of the day a time of day falls in. */
    public static Phase phaseOf(double t) {
        double d = wrap(t);
        if (d < DAWN_START) return Phase.NIGHT;
        if (d < MORNING_START) return Phase.DAWN;
        if (d < NOON_START) return Phase.MORNING;
        if (d < AFTERNOON_START) return Phase.MIDDAY;
        if (d < DUSK_START) return Phase.AFTERNOON;
        if (d < NIGHT_START) return Phase.DUSK;
        return Phase.NIGHT;
    }

    /** Whether it is dark enough for the nocturnal half of the guide to be out. */
    public boolean night() { return phase() == Phase.NIGHT; }

    /**
     * How much daylight there is, {@code 0.26} at the dead of night to
     * {@code 1} at noon — what every colour in the world is multiplied by.
     *
     * <p>Driven by the sun's own altitude and smoothed, so the change from
     * night to day happens across the dawn rather than at a moment. The
     * <b>floor matters as much as the curve</b>: a game where you cannot see
     * anything is not night, it is a black screen, and a game about finding
     * animals has nocturnal ones in it that somebody has to be able to look at.
     *
     * <p>The band is deliberately generous around the horizon crossing —
     * sunset itself still reads at about two thirds of noon — because the half
     * hour either side of it is when most of the interesting animals are out,
     * and a curve that dropped it to a fifth (as the first one did) made the
     * best hour of the day the one you could not see in.
     */
    public double daylight() { return daylightAt(timeOfDay); }

    /** {@link #daylight()} at an arbitrary time of day. */
    public static double daylightAt(double t) {
        double altitude = Math.sin((wrap(t) - 0.25) * Math.PI * 2);
        double lit = (altitude + 0.30) / 0.55;
        lit = lit < 0 ? 0 : Math.min(lit, 1);
        return 0.26 + 0.74 * (lit * lit * (3 - 2 * lit));
    }

    /**
     * How high the sun stands, in radians: negative at night, {@code π/2}
     * straight overhead. What the shadows and the sky gradient are built from.
     */
    public double sunAltitude() {
        return Math.asin(Math.max(-1, Math.min(1, Math.sin((timeOfDay - 0.25) * Math.PI * 2))));
    }

    /**
     * The direction the sun is in, written into {@code out} as a unit
     * {@code x, y, z}. It rises in the east and sets in the west, tracking
     * across the southern sky.
     */
    public void sunDirection(double[] out) {
        double hour = (timeOfDay - 0.25) * Math.PI * 2;
        double altitude = Math.sin(hour);
        double east = Math.cos(hour);
        double length = Math.sqrt(east * east + 0.25 + altitude * altitude);
        out[0] = east / length;
        out[1] = -0.5 / length;
        out[2] = altitude / length;
    }

    /**
     * The colour of the light at this hour, as a per-channel multiplier written
     * into {@code out} as {@code r, g, b}.
     *
     * <p>Warm and low at dawn and dusk, neutral at midday, cold and dim at
     * night. This is what makes the same hillside read differently at six in
     * the morning and six in the evening, given that the shade baked into its
     * triangles cannot change.
     */
    public void lightTint(double[] out) {
        double day = daylight();
        Phase phase = phase();
        double warmth = switch (phase) {
            case DAWN -> 0.9;
            case DUSK -> 1.0;
            case MORNING, AFTERNOON -> 0.35;
            case MIDDAY -> 0.0;
            case NIGHT -> -0.7;
        };
        out[0] = day * (1 + warmth * 0.22);
        out[1] = day * (1 + warmth * 0.02);
        out[2] = day * (1 - warmth * 0.20);
    }

    /** A colour with this hour's light applied to it, for the CPU painter. */
    public int applyLight(int argb, double[] tint) {
        int a = (argb >>> 24) & 0xFF;
        int r = clamp(((argb >> 16) & 0xFF) * tint[0]);
        int g = clamp(((argb >> 8) & 0xFF) * tint[1]);
        int b = clamp((argb & 0xFF) * tint[2]);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /**
     * The sky's colour now, given the biome's own daytime sky.
     *
     * <p>Blended toward a warm band at dawn and dusk and toward a near-black
     * blue at night, so the horizon says what time it is from any biome.
     */
    public int skyColour(int biomeSkyRgb) {
        double day = daylight();
        Phase phase = phase();
        int target = switch (phase) {
            case DAWN -> 0xE8A46A;
            case DUSK -> 0xE07C52;
            case NIGHT -> 0x0B1024;
            default -> biomeSkyRgb;
        };
        double mix = switch (phase) {
            case DAWN, DUSK -> 0.55;
            case NIGHT -> 0.86;
            default -> 0;
        };
        int blended = blend(biomeSkyRgb, target, mix);
        return scale(blended, 0.30 + 0.70 * day);
    }

    /** The colour distance fades into now. */
    public int fogColour(int biomeFogRgb) {
        double day = daylight();
        Phase phase = phase();
        int target = switch (phase) {
            case DAWN -> 0xD9B08A;
            case DUSK -> 0xC98F70;
            case NIGHT -> 0x131A30;
            default -> biomeFogRgb;
        };
        double mix = switch (phase) {
            case DAWN, DUSK -> 0.5;
            case NIGHT -> 0.8;
            default -> 0;
        };
        return scale(blend(biomeFogRgb, target, mix), 0.34 + 0.66 * day);
    }

    private static int blend(int a, int b, double t) {
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        int r = (int) (ar + (br - ar) * t);
        int g = (int) (ag + (bg - ag) * t);
        int bl = (int) (ab + (bb - ab) * t);
        return (r << 16) | (g << 8) | bl;
    }

    private static int scale(int rgb, double by) {
        return (clamp(((rgb >> 16) & 0xFF) * by) << 16)
                | (clamp(((rgb >> 8) & 0xFF) * by) << 8)
                | clamp((rgb & 0xFF) * by);
    }

    private static int clamp(double v) {
        return (int) Math.max(0, Math.min(255, Math.round(v)));
    }

    private static double wrap(double t) {
        double d = t - Math.floor(t);
        return d < 0 ? d + 1 : d;
    }

    @Override public String toString() {
        return localTimeOf(timeOfDay).withNano(0) + " (" + phase().label() + ")";
    }
}
