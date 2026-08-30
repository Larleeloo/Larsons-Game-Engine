package com.larsons.engine.watch.life;

import java.awt.Color;

/**
 * How hard a species is to find at all.
 *
 * <p>Rarity does two things and they are different: it decides <b>how often</b>
 * a species is chosen when the world populates a patch of ground, and it
 * decides <b>how far away</b> it flushes, because the rare things in a real
 * field guide are mostly rare because they see you first.
 *
 * <p>The tiers are deliberately steep at the top. A legendary is about one
 * animal in three hundred; the point of the last page of a guide is that it
 * takes a season.
 */
public enum Rarity {

    COMMON("Common", 1.00, 0.90, new Color(0xB8C2CE)),
    UNCOMMON("Uncommon", 0.46, 1.00, new Color(0x86C48A)),
    SCARCE("Scarce", 0.18, 1.12, new Color(0x86A8D8)),
    RARE("Rare", 0.055, 1.26, new Color(0xC08AD8)),
    LEGENDARY("Legendary", 0.012, 1.42, new Color(0xE8B860)),

    /**
     * The tier above the book — <b>the three mutants, and nothing else.</b>
     *
     * <p><b>Not the thing that makes them rare</b>, and the number is what it is
     * because of that. The obvious move is a vanishing frequency — a twentieth
     * of a legendary, say — and it was measured and it does not work: a biome
     * holds two to five hundred species whose weights sum to about a hundred and
     * fifty, so a frequency of 0.0006 comes out at <em>one pick in two hundred
     * thousand</em>. A player walking all night meets one about once every three
     * thousand hours, which is a feature that does not exist.
     *
     * <p>What actually keeps the wood safe is four filters stacked, and only one
     * of them is this number — see
     * {@link com.larsons.engine.watch.life.Mutants}: three biomes each out of
     * twenty, one hour band each with a hard zero outside it
     * ({@link AnimalDef#encounterWeight}), at most one alive anywhere, and a
     * ten-minute cooldown after each. Those are the rate limit. This is only
     * "when the world is willing, and you are on the right ground at the right
     * hour, how soon".
     *
     * <p>At 0.30 that comes to roughly one pick in five hundred, which is
     * something like half an hour of walking the right country at the right
     * hour, and rarer than the cooldown so that meetings are not metronomic.
     *
     * <p>The wariness scale is {@code 1} and means nothing: a mutant does not
     * flush, so there is no distance at which it decides you are a problem. It
     * has already decided.
     */
    MYTHIC("Mythic", 0.30, 1.00, new Color(0xD86A5E));

    private final String label;
    private final double frequency;
    private final double warinessScale;
    private final Color tint;

    Rarity(String label, double frequency, double warinessScale, Color tint) {
        this.label = label;
        this.frequency = frequency;
        this.warinessScale = warinessScale;
        this.tint = tint;
    }

    /** What the guide's page calls this. */
    public String label() { return label; }

    /** How often this tier is picked, relative to a common species. */
    public double frequency() { return frequency; }

    /** How much this tier multiplies its family's wariness. */
    public double warinessScale() { return warinessScale; }

    /** The colour the guide marks this tier in. */
    public Color tint() { return tint; }

    /**
     * How much a sighting of this tier is worth on the record.
     *
     * <p>{@link #MYTHIC} is worth five legendaries, and it is meant to be the
     * best afternoon's work in the game: writing one down means having stood
     * still, in the open, in front of something that was walking toward you.
     */
    public int points() {
        return switch (this) {
            case COMMON -> 1;
            case UNCOMMON -> 3;
            case SCARCE -> 8;
            case RARE -> 25;
            case LEGENDARY -> 100;
            case MYTHIC -> 500;
        };
    }

    /** The tier a saved name means, tolerating anything unknown. */
    public static Rarity of(String text, Rarity fallback) {
        if (text == null || text.isBlank()) return fallback;
        for (Rarity r : values()) {
            if (r.name().equalsIgnoreCase(text.trim())) return r;
        }
        return fallback;
    }
}
