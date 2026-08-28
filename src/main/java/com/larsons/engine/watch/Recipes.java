package com.larsons.engine.watch;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cooking, and the rest of what a fire and a pair of hands can make.
 *
 * <p><b>Why cooking exists in a game about looking at birds.</b> Raw food
 * works: scatter seed and something will come. But a handful of seed draws one
 * family from twenty metres, and a suet cake draws four from forty — so the
 * difference between a good morning and a great one is what you did the
 * evening before. That is the whole design of this system, and it is why every
 * prepared food in {@link Forage} has a higher {@code appeal} and a longer
 * {@code reach} than any of its ingredients.
 *
 * <p>Recipes are also how the building materials become building
 * <em>pieces</em>: a plank is a branch and a stone knife's worth of patience,
 * and the same table handles both, because "two of these and one of those
 * makes one of this" is one mechanism.
 */
public final class Recipes {

    /** Where a recipe can be made. */
    public enum Station {
        /** Anywhere, with your hands. */
        HANDS("Hands"),
        /** At a fire — which needs branches and a stone ring. */
        FIRE("Campfire"),
        /** At a bench, which a camp has once it has a roof. */
        BENCH("Workbench");

        private final String label;

        Station(String label) { this.label = label; }

        public String label() { return label; }
    }

    /**
     * One recipe.
     *
     * @param output    what it makes
     * @param count     how many
     * @param station   where it can be made
     * @param inputs    what it costs, as key → count
     * @param note      one line, for the cooking screen
     */
    public record Recipe(String output, int count, Station station,
                         Map<String, Integer> inputs, String note) {

        /** What a player sees this recipe called. */
        public String name() { return Forage.nameOf(output); }

        /** Whether a satchel can afford it. */
        public boolean affordable(Satchel satchel) {
            for (Map.Entry<String, Integer> need : inputs.entrySet()) {
                if (!satchel.has(need.getKey(), need.getValue())) return false;
            }
            return true;
        }

        /** The cost, spelled out: "2 × Fallen Branch, 1 × Vine". */
        public String costLine() {
            StringBuilder sb = new StringBuilder();
            inputs.forEach((key, n) -> {
                if (sb.length() > 0) sb.append(", ");
                sb.append(n).append(" × ").append(Forage.nameOf(key));
            });
            return sb.toString();
        }
    }

    private static final List<Recipe> ALL = build();

    private Recipes() {}

    /** Every recipe, in the order the cooking screen lists them. */
    public static List<Recipe> all() { return ALL; }

    /** The recipes that can be made at a station. */
    public static List<Recipe> at(Station station) {
        List<Recipe> out = new ArrayList<>();
        for (Recipe r : ALL) {
            if (r.station() == station) out.add(r);
        }
        return out;
    }

    /** The recipes a satchel can currently afford at a station. */
    public static List<Recipe> affordable(Satchel satchel, Station station) {
        List<Recipe> out = new ArrayList<>();
        for (Recipe r : at(station)) {
            if (r.affordable(satchel)) out.add(r);
        }
        return out;
    }

    /** The recipe that makes an item, or {@code null}. */
    public static Recipe making(String output) {
        for (Recipe r : ALL) {
            if (r.output().equals(output)) return r;
        }
        return null;
    }

    /**
     * Make one batch of a recipe out of a satchel.
     *
     * <p>All or nothing: the ingredients are checked before any of them are
     * spent, so a failed craft leaves the satchel exactly as it was.
     *
     * @return {@code true} if it was made
     */
    public static boolean craft(Recipe recipe, Satchel satchel, Station station) {
        if (recipe == null || satchel == null) return false;
        if (recipe.station() != station) return false;
        if (!recipe.affordable(satchel)) return false;
        recipe.inputs().forEach(satchel::take);
        satchel.add(recipe.output(), recipe.count());
        return true;
    }

    private static List<Recipe> build() {
        List<Recipe> out = new ArrayList<>();

        // --- food, at a fire -------------------------------------------------------
        add(out, "suet_cake", 2, Station.FIRE,
                "The best all-round lure there is, and it takes three kinds of foraging.",
                "meat_scrap", 1, "sunflower_seed", 3, "blackberry", 2);
        add(out, "berry_mash", 3, Station.HANDS,
                "No fire needed. Any three berries will do; these are the common ones.",
                "blackberry", 2, "elderberry", 2);
        add(out, "nectar", 2, Station.FIRE,
                "Warmed sap and petals. Hummingbirds and nothing else.",
                "sap", 1, "petal_syrup", 1);
        add(out, "sugar_water", 3, Station.HANDS,
                "Crude, quick, and half as good as the real thing.",
                "sap", 2);
        add(out, "petal_syrup", 2, Station.FIRE,
                "Slow to reduce, and it keeps all season.",
                "sap", 2, "clover", 2);
        add(out, "grain_loaf", 2, Station.FIRE,
                "A slab of baked grain. Ground feeders arrive in numbers.",
                "millet", 3, "wild_rice", 2);
        add(out, "grub_tray", 2, Station.HANDS,
                "Turn over three logs and put what you find on a piece of bark.",
                "beetle", 2, "mealworms", 2, "bark_strip", 1);
        add(out, "smoked_fish", 2, Station.FIRE,
                "One fish, one fire, one afternoon. Raptors notice from a long way off.",
                "trout", 1, "fallen_branch", 2);
        add(out, "moth_lamp", 1, Station.BENCH,
                "A lit lamp draws moths, and moths draw everything that eats moths.",
                "sap", 1, "stone", 1, "glow_spore", 1);
        add(out, "hay_bundle", 2, Station.HANDS,
                "Cut grass, dried on a rack. Deer, hares, anything that grazes.",
                "clover", 3, "vine", 1);
        add(out, "salt_lick", 1, Station.BENCH,
                "Takes a week to be found and then it is found every day.",
                "stone", 3, "clay_lump", 1);

        // --- gear ------------------------------------------------------------------
        add(out, "rod", 1, Station.HANDS,
                "A branch, a vine, and a bent pin's worth of patience.",
                "fallen_branch", 2, "vine", 2);
        add(out, "feeder", 1, Station.HANDS,
                "Somewhere to put the food where it can be seen from the air.",
                "fallen_branch", 1, "bark_strip", 2, "vine", 1);
        add(out, "trowel", 1, Station.HANDS,
                "For putting seeds where you want them.",
                "fallen_branch", 1, "stone", 1);

        // --- the spyglass, in two steps --------------------------------------------
        //
        // Deliberately the longest chain in the game, because it is the only
        // thing you can make that changes what the world *is* rather than what
        // comes to you: a walk with a glass in the satchel has a far shore in
        // it. Two steps, two kinds of ground and a bench —
        //
        //   quartz (bare rock or crystal) + sand (a dune or a beach)
        //       → a lens, ground at a bench
        //   two lenses + a plank + rope + sap
        //       → the tube
        //
        // — so nobody has one in their first ten minutes, and anybody who wants
        // one has a reason to walk somewhere they have not been. Every input is
        // something the ground already gives up: see Forage.underfoot for which
        // ground gives up which, and Forage itself for what they are.
        add(out, "lens", 1, Station.BENCH,
                "Quartz against wet sand, for an afternoon. Two of these make a glass.",
                "quartz", 1, "sand", 2);
        add(out, Spyglass.ITEM, 1, Station.BENCH,
                "Objective, eyepiece, and a tube to hold them the right distance apart.",
                "lens", 2, "plank", 1, "rope", 1, "sap", 1);

        // --- building materials -----------------------------------------------------
        add(out, "plank", 4, Station.BENCH,
                "Split from a branch. The floor of everything.",
                "fallen_branch", 1);
        add(out, "thatch", 3, Station.HANDS,
                "Reeds, bound. A roof that lasts a season.",
                "reed_bundle", 2, "vine", 1);
        add(out, "rope", 3, Station.HANDS,
                "Twisted bark. Every lashing in a treehouse is this.",
                "bark_strip", 2, "vine", 1);
        return List.copyOf(out);
    }

    private static void add(List<Recipe> out, String output, int count, Station station,
                            String note, Object... pairs) {
        Map<String, Integer> inputs = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            inputs.put((String) pairs[i], (Integer) pairs[i + 1]);
        }
        out.add(new Recipe(output, count, station, Map.copyOf(inputs), note));
    }
}
