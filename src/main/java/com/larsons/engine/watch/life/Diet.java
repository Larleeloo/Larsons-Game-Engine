package com.larsons.engine.watch.life;

import java.util.List;

/**
 * What a species eats, and therefore <b>what will bring it to you</b>.
 *
 * <p>This is the join between the animals and the whole foraging half of the
 * game. A feeder is a pile of something; a species comes to it only if that
 * something is on this list. So "which food attracts what" is not a table
 * somebody has to maintain beside the species list — it falls out of the
 * species' own diet, and adding a food is adding a row here.
 *
 * <p>The forage keys named below are the ones {@code Forage} and
 * {@code Recipes} produce. A key that no longer exists simply never matches,
 * which is the right failure: a lure that attracts nothing, rather than a crash.
 */
public enum Diet {

    SEEDS("Seeds", "grass_seed", "sunflower_seed", "millet", "thistle_seed", "birch_seed",
            "sedge_seed", "lupine_seed", "grain_loaf"),

    NUTS("Nuts", "acorn", "beechnut", "pine_seed", "redwood_cone", "cactus_seed",
            "kapok_seed", "suet_cake"),

    BERRIES("Berries", "blackberry", "blueberry", "lingonberry", "cloudberry", "elderberry",
            "juniper", "salmonberry", "thimbleberry", "crowberry", "snowberry",
            "prickly_pear", "guava", "fig", "sea_grape", "mangrove_apple", "nightbell",
            "dewfruit", "berry_mash", "amethyst_plum", "sun_pear"),

    NECTAR("Nectar", "nectar", "sugar_water", "petal_syrup", "cocoa_pod", "dawn_cone",
            "moon_date", "date"),

    INSECTS("Insects", "grub_tray", "mealworms", "beetle", "moth_lamp", "suet_cake"),

    FISH("Fish", "minnow_bait", "trout", "char", "perch", "pike", "smoked_fish"),

    FOLIAGE("Foliage", "clover", "wild_rice", "bamboo_seed", "palm_seed", "hay_bundle",
            "salt_lick"),

    GRAIN("Grain", "millet", "wild_rice", "grain_loaf", "grass_seed", "hay_bundle"),

    CARRION("Meat", "meat_scrap", "smoked_fish", "trout", "pike", "grub_tray"),

    OMNIVORE("Anything", "blackberry", "acorn", "grain_loaf", "meat_scrap", "suet_cake",
            "berry_mash", "grub_tray", "smoked_fish");

    private final String label;
    private final List<String> foods;

    Diet(String label, String... foods) {
        this.label = label;
        this.foods = List.of(foods);
    }

    /** What the field guide's page calls this. */
    public String label() { return label; }

    /** The forage keys a species on this diet will come to. */
    public List<String> foods() { return foods; }

    /** Whether a food will draw a species on this diet. */
    public boolean eats(String forageKey) {
        return forageKey != null && foods.contains(forageKey);
    }

    /**
     * How strongly a food draws a species on this diet, {@code 0} for something
     * it does not eat.
     *
     * <p>The first food on a diet's list is its favourite and pulls hardest;
     * later ones taper. That gives the cooking half of the game something to
     * be for — a prepared food sits early on several lists at once, so a suet
     * cake brings three families to one feeder where a handful of seed brings
     * one.
     */
    public double appeal(String forageKey) {
        int at = foods.indexOf(forageKey);
        if (at < 0) return 0;
        return 1.0 - 0.5 * at / Math.max(1, foods.size() - 1);
    }

    /** The diet a saved name means, tolerating anything unknown. */
    public static Diet of(String text, Diet fallback) {
        if (text == null || text.isBlank()) return fallback;
        for (Diet d : values()) {
            if (d.name().equalsIgnoreCase(text.trim())) return d;
        }
        return fallback;
    }
}
