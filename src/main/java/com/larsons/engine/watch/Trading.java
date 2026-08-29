package com.larsons.engine.watch;

import com.larsons.engine.watch.world.WatchBiome;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * What a trading post will part with, and what it costs in points.
 *
 * <p><b>Materials, mostly, and that is the whole design.</b> Points come from
 * looking at animals; the things they buy are the things you would otherwise
 * have had to walk for. That makes watching and gathering two currencies for
 * one economy rather than two unrelated chores — an afternoon spent creeping up
 * on waders pays for the planks a hide is built out of, and a hide is what gets
 * you nearer the next wader.
 *
 * <p>Nothing here is a <em>shortcut past</em> the rest of the game. A trading
 * post sells the raw material and never the thing made from it: quartz and sand
 * are on the shelf, a {@linkplain Spyglass spyglass} is not, so the two-step
 * grind at a bench that {@code SpyglassTest} is written around is still the only
 * way anybody gets a glass. The one place that rule bends is the tools — a
 * feeder, a trowel, a rod — which are cheap to make and infuriating to be
 * without, and which a pedlar would obviously be carrying.
 *
 * <h2>Prices</h2>
 *
 * <p>Read against {@link com.larsons.engine.watch.life.Rarity#points()}: a
 * common bird is 1, an uncommon 3, a legendary 100. So a fallen branch is two
 * birds, a plank is a good half hour, and a ground lens is a legendary or a very
 * thorough morning. The expensive end is deliberately reachable only by somebody
 * who has filled a page and had it stamped, which is what the stamp is for.
 *
 * <p>Each post applies its own {@linkplain Shops.Shop#markup() markup} on top,
 * so two keepers a valley apart are not the same shop twice and "what does the
 * one by the lake want for rope" is a thing worth knowing.
 */
public final class Trading {

    /**
     * One line on a shelf: a quantity of something, at a price.
     *
     * @param item     the {@link Forage} key handed over
     * @param quantity how many of them one purchase is
     * @param price    what it costs in points, after the post's markup
     */
    public record Offer(String item, int quantity, int price, String note) {

        /** What a player sees on the left of the row. */
        public String label() {
            return quantity == 1 ? Forage.nameOf(item)
                    : quantity + " × " + Forage.nameOf(item);
        }

        /** …and on the right. */
        public String priceLine() { return price + (price == 1 ? " pt" : " pts"); }

        /** The same offer at a keeper's own prices. */
        Offer at(double markup) {
            return new Offer(item, quantity, Math.max(1, (int) Math.round(price * markup)),
                    note);
        }

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("k", item);
            m.put("n", quantity);
            m.put("p", price);
            return m;
        }
    }

    /**
     * The catalogue, at list price and in the order a shelf shows it.
     *
     * <p>Insertion-ordered for {@link Forage}'s reason and with its consequence
     * in mind: this list is drawn on a screen, and a map whose iteration order
     * is salted per JVM run would reshuffle every keeper's shelf between
     * sessions.
     */
    private static final Map<String, Offer> CATALOGUE = build();

    private static final List<Offer> ALL = List.copyOf(CATALOGUE.values());

    /** How many lines one keeper carries. */
    private static final int SHELF_MIN = 6, SHELF_MAX = 9;

    /** The range a keeper's prices are scaled by. */
    private static final double MARKUP_LOW = 0.80, MARKUP_HIGH = 1.30;

    private Trading() {}

    /** Everything any post could sell, at list price. */
    public static List<Offer> catalogue() { return ALL; }

    /** The list-price offer for an item, or {@code null} if none is sold. */
    public static Offer listed(String item) {
        return item == null ? null : CATALOGUE.get(item);
    }

    /** Whether points can buy this at all. */
    public static boolean sold(String item) { return listed(item) != null; }

    /** The markup a post with this hash charges. */
    public static double markupFor(Random rng) {
        return MARKUP_LOW + rng.nextDouble() * (MARKUP_HIGH - MARKUP_LOW);
    }

    /**
     * One keeper's shelf: which of the catalogue they happen to be carrying,
     * at their own prices.
     *
     * <p><b>A subset, chosen by the post's own hash and weighted by where it
     * stands.</b> A shop that sold the whole catalogue would make the second one
     * you found pointless, and the point of putting them in the wild is that
     * finding another is worth something. Weighted by the biome because a post
     * on a shingle spit obviously has sand and quartz and a post in a
     * rainforest obviously has vine — which is also the honest version of "go
     * somewhere else if you want a lens".
     *
     * <p>The staples are always present. Somebody who walked two kilometres to
     * a trading post should never arrive to find it sells nothing they can use.
     */
    public static List<Offer> shelf(WatchBiome biome, double markup, Random rng) {
        List<Offer> chosen = new ArrayList<>();
        List<String> taken = new ArrayList<>();
        for (String key : STAPLES) {
            Offer offer = CATALOGUE.get(key);
            if (offer != null && taken.add(key)) chosen.add(offer.at(markup));
        }
        // What the country round the post produces, offered first among the
        // rest — a keeper trades what comes past their door.
        List<String> pool = new ArrayList<>();
        for (String key : localTo(biome)) {
            if (CATALOGUE.containsKey(key) && !taken.contains(key)) pool.add(key);
        }
        for (Offer offer : ALL) {
            if (!taken.contains(offer.item()) && !pool.contains(offer.item())) {
                pool.add(offer.item());
            }
        }
        int want = SHELF_MIN + rng.nextInt(SHELF_MAX - SHELF_MIN + 1);
        while (chosen.size() < want && !pool.isEmpty()) {
            // Biased toward the front of the pool, which is where the local
            // goods are: `nextInt` over a third of it, so a post's own country
            // usually wins and occasionally does not.
            int at = rng.nextInt(Math.max(1, Math.min(pool.size(), 1 + pool.size() / 3)));
            String key = pool.remove(at);
            taken.add(key);
            chosen.add(CATALOGUE.get(key).at(markup));
        }
        return List.copyOf(chosen);
    }

    /**
     * The lines every post carries.
     *
     * <p>Branches and rope, because they are what every recipe in the book
     * starts from, and a feeder, because a feeder is what turns points back into
     * animals and therefore back into points.
     */
    private static final List<String> STAPLES =
            List.of("fallen_branch", "rope", "feeder");

    /** What the country round a post is likely to be trading. */
    private static List<String> localTo(WatchBiome biome) {
        List<String> out = new ArrayList<>();
        if (biome == null) return out;
        if (!biome.trees().isEmpty()) {
            out.add("plank");
            out.add("bark_strip");
            out.add("sap");
        }
        if (biome.humidity() > 70) {
            out.add("reed_bundle");
            out.add("thatch");
        }
        if (biome.humidity() > 55) out.add("vine");
        if (biome.humidity() > 45) out.add("clay_lump");
        if (biome.rockDensity() > 0.004) {
            out.add("stone");
            out.add("quartz");
        }
        if (biome.humidity() < 40) out.add("sand");
        // The strange places trade in strange things, and a lens off a shelf in
        // the crystal highlands is the reward for having got there.
        if (biome.strangeness() > WatchBiome.ORDINARY) out.add("lens");
        return out;
    }

    private static Map<String, Offer> build() {
        Map<String, Offer> map = new LinkedHashMap<>();

        // --- raw materials, which is what the ask was about --------------------
        offer(map, "fallen_branch", 4, 6, "Cut, dried and stacked. Saves an hour.");
        offer(map, "bark_strip", 4, 6, "Peeled in sheets. Roofing, and cordage.");
        offer(map, "reed_bundle", 4, 8, "Cut in the marsh and dried on the rack.");
        offer(map, "stone", 4, 8, "Sorted; none of it will split on you.");
        offer(map, "vine", 4, 9, "Coiled. Every lashing in a treehouse.");
        offer(map, "clay_lump", 3, 9, "Wedged and wrapped. Ready for the wheel.");
        offer(map, "sap", 3, 11, "In a stoppered jar, which is the hard part.");
        offer(map, "feather", 5, 5, "Moulted, sorted by size. Fletching and quills.");

        // --- worked materials, dearer because somebody worked them ------------
        offer(map, "plank", 3, 16, "Split and planed. A floor is four of these.");
        offer(map, "thatch", 3, 16, "Bound in courses. A roof for a season.");
        offer(map, "rope", 2, 15, "Three-strand bark. Better than yours.");

        // --- the spyglass chain, raw only -------------------------------------
        offer(map, "sand", 4, 12, "Washed and graded. Grinding paste, with water.");
        offer(map, "quartz", 2, 30, "Water-clear. There is a lens in each of these.");
        offer(map, "lens", 1, 90, "Already ground. The two evenings you get back.");

        // --- tools ------------------------------------------------------------
        offer(map, "feeder", 1, 28, "Hung, filled and standing. Put it out and wait.");
        offer(map, "trowel", 1, 24, "Blade, tang and a handle that will not turn.");
        offer(map, "rod", 1, 34, "Whipped and varnished. It will outlast you.");

        // --- something to put on the feeder -----------------------------------
        offer(map, "suet_cake", 2, 26, "Pressed this morning. Nothing refuses it.");
        offer(map, "grain_loaf", 2, 20, "Baked in a slab. Ground feeders swarm it.");
        offer(map, "salt_lick", 1, 24, "Deer will cross a valley for it. Slowly.");
        offer(map, "smoked_fish", 2, 22, "Smells of the smoke-hut for a week.");
        offer(map, "mealworms", 6, 14, "In a tin with holes in the lid.");

        return java.util.Collections.unmodifiableMap(map);
    }

    private static void offer(Map<String, Offer> map, String item, int quantity, int price,
                              String note) {
        map.put(item, new Offer(item, quantity, price, note));
    }
}
