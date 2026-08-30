package com.larsons.engine.watch.life;

/**
 * The animation states every animal in the game has.
 *
 * <p><b>This is the contract with Blockbench.</b> A placeholder model poses
 * these ten states procedurally; an imported {@code .bbmodel} supplies a clip
 * for each of them by name ({@link #clipNames()}), and anything it does not
 * supply falls back to the procedural pose rather than to nothing. So a model
 * with only {@code idle} and {@code walk} animations still works everywhere,
 * and finishing the set is an improvement rather than a prerequisite — which is
 * the property that lets art arrive one clip at a time.
 *
 * <p>See {@code resources/watch/models/README.md} for the naming and the
 * import steps.
 */
public enum AnimState {

    /** Standing, sitting, or floating; small idle movement only. */
    IDLE("idle", "idle", "stand"),

    /** Moving at a normal pace. */
    WALK("walk", "walk", "move", "swim"),

    /** Fleeing, or covering ground. */
    RUN("run", "run", "sprint", "flee"),

    /** Airborne under its own power. */
    FLY("fly", "fly", "flap", "glide"),

    /** Head down, eating — what a lure produces and what a photograph wants. */
    FORAGE("forage", "forage", "eat", "feed", "peck", "graze"),

    /** Head up, frozen, deciding whether you are a problem. */
    ALERT("alert", "alert", "look", "watch"),

    /** Asleep, outside its own hours. */
    SLEEP("sleep", "sleep", "rest", "roost"),

    /** Calling — the moment that gives away where it is. */
    CALL("call", "call", "sing", "display"),

    /** Being carried, ridden, or perched on a hand: a tame animal at home. */
    TAME("tame", "tame", "sit", "perch"),

    /**
     * Swinging at somebody — <b>the only state in this file that nothing in the
     * original game could reach.</b>
     *
     * <p>Added with the mutants ({@link Mutants}), and deliberately added
     * <em>here</em> rather than as a special case inside them. Nine states were
     * the contract with Blockbench and ten is the same contract: an imported
     * model that supplies a {@code strike} clip gets it, one that does not falls
     * back to the procedural pose, and the field guide's own placeholder wendigo
     * is posed by the same table as its wrens. A tenth animation is an
     * improvement, not a prerequisite, which was the property the other nine
     * were designed for.
     *
     * <p>Nothing but a hostile species ever enters it, so every one of the
     * thirteen hundred ordinary animals is unaffected by its existence.
     */
    STRIKE("strike", "strike", "attack", "bite", "lunge", "swipe");

    private final String key;
    private final String[] clipNames;

    AnimState(String key, String... clipNames) {
        this.key = key;
        this.clipNames = clipNames;
    }

    /** Stable identifier — what a save file and a texture key use. */
    public String key() { return key; }

    /**
     * The animation names an imported model may use for this state, most
     * preferred first. Matching is case-insensitive and ignores any
     * {@code animation.<model>.} prefix Blockbench writes.
     */
    public String[] clipNames() { return clipNames.clone(); }

    /** How fast this state's phase clock runs, in cycles per second. */
    public double cyclesPerSecond() {
        return switch (this) {
            case IDLE, TAME -> 0.45;
            case WALK -> 1.6;
            case RUN -> 3.1;
            case FLY -> 5.5;
            case FORAGE -> 1.1;
            case ALERT -> 0.3;
            case SLEEP -> 0.18;
            case CALL -> 2.2;
            // One swing per cycle, and the cycle is short: a blow you can see
            // coming and cannot quite get out of the way of. It is faster than
            // a run cycle on purpose — a mutant winding up is the moment the
            // player is meant to react to.
            case STRIKE -> 1.4;
        };
    }

    /** Whether an animal in this state is holding still enough to be studied. */
    public boolean settled() {
        return this == IDLE || this == FORAGE || this == SLEEP || this == CALL
                || this == TAME;
    }

    /** The state a saved name means, tolerating anything unknown. */
    public static AnimState of(String text, AnimState fallback) {
        if (text == null || text.isBlank()) return fallback;
        String want = text.trim().toLowerCase();
        for (AnimState s : values()) {
            if (s.key.equals(want) || s.name().equalsIgnoreCase(want)) return s;
        }
        return fallback;
    }

    /**
     * The state an imported clip name belongs to, or {@code null} when it
     * matches none of them. Tolerates Blockbench's
     * {@code animation.wren.walk_cycle} spelling.
     */
    public static AnimState forClip(String clipName) {
        if (clipName == null) return null;
        String name = clipName.toLowerCase();
        int dot = name.lastIndexOf('.');
        if (dot >= 0) name = name.substring(dot + 1);
        for (AnimState s : values()) {
            for (String candidate : s.clipNames) {
                if (name.equals(candidate) || name.startsWith(candidate + "_")
                        || name.endsWith("_" + candidate)) {
                    return s;
                }
            }
        }
        return null;
    }
}
