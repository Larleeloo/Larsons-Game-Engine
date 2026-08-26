package com.larsons.engine.watch.life;

import com.larsons.engine.watch.WatchClock;

/**
 * When a species is awake — <b>the reason the clock is the real one.</b>
 *
 * <p>A game whose day lasts twenty minutes can put every animal on screen
 * within one of them, and then the time of day is decoration. Here it is a
 * filter: something like a third of the guide is nocturnal, and the only way to
 * fill those pages is to go out after dark. "Come back at dawn" is an
 * instruction with teeth when dawn is dawn.
 */
public enum Activity {

    /** Out in daylight, asleep at night. */
    DIURNAL("Diurnal", "by day"),

    /** Out after dark. */
    NOCTURNAL("Nocturnal", "after dark"),

    /** The two half-lit hours, and not much else. */
    CREPUSCULAR("Crepuscular", "at dawn and dusk"),

    /** Out at any hour, in its own time. */
    CATHEMERAL("Cathemeral", "at any hour");

    private final String label;
    private final String phrase;

    Activity(String label, String phrase) {
        this.label = label;
        this.phrase = phrase;
    }

    /** What the field guide's page calls this. */
    public String label() { return label; }

    /** How the guide's sentence reads: "Seen <em>after dark</em>." */
    public String phrase() { return phrase; }

    /**
     * How likely a species of this habit is to be about at a given hour,
     * {@code 0} (asleep) to {@code 1} (its own hour).
     *
     * <p>Never quite zero for anything but a deep sleeper: a nocturnal animal
     * disturbed at noon does move, and a guide that made a species literally
     * unfindable outside four hours would be a guide nobody finishes.
     */
    public double activityAt(WatchClock.Phase phase) {
        return switch (this) {
            case DIURNAL -> switch (phase) {
                case MORNING, MIDDAY, AFTERNOON -> 1.0;
                case DAWN, DUSK -> 0.55;
                case NIGHT -> 0.08;
            };
            case NOCTURNAL -> switch (phase) {
                case NIGHT -> 1.0;
                case DAWN, DUSK -> 0.6;
                case MORNING, AFTERNOON -> 0.12;
                case MIDDAY -> 0.05;
            };
            case CREPUSCULAR -> switch (phase) {
                case DAWN, DUSK -> 1.0;
                case MORNING, AFTERNOON -> 0.42;
                case NIGHT -> 0.3;
                case MIDDAY -> 0.15;
            };
            case CATHEMERAL -> 0.72;
        };
    }

    /** Whether this species is properly out at this hour. */
    public boolean awakeAt(WatchClock.Phase phase) {
        return activityAt(phase) >= 0.5;
    }

    /** The habit a saved name means, tolerating anything unknown. */
    public static Activity of(String text, Activity fallback) {
        if (text == null || text.isBlank()) return fallback;
        for (Activity a : values()) {
            if (a.name().equalsIgnoreCase(text.trim())) return a;
        }
        return fallback;
    }
}
