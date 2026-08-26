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
 * <p>The five tiers are deliberately steep at the top. A legendary is about one
 * animal in three hundred; the point of the last page of a guide is that it
 * takes a season.
 */
public enum Rarity {

    COMMON("Common", 1.00, 0.90, new Color(0xB8C2CE)),
    UNCOMMON("Uncommon", 0.46, 1.00, new Color(0x86C48A)),
    SCARCE("Scarce", 0.18, 1.12, new Color(0x86A8D8)),
    RARE("Rare", 0.055, 1.26, new Color(0xC08AD8)),
    LEGENDARY("Legendary", 0.012, 1.42, new Color(0xE8B860));

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

    /** How much a sighting of this tier is worth on the record. */
    public int points() {
        return switch (this) {
            case COMMON -> 1;
            case UNCOMMON -> 3;
            case SCARCE -> 8;
            case RARE -> 25;
            case LEGENDARY -> 100;
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
