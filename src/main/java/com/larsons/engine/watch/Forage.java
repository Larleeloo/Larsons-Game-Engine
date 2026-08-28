package com.larsons.engine.watch;

import com.larsons.engine.watch.life.Diet;
import com.larsons.engine.watch.world.WatchBiome;
import com.larsons.engine.watch.world.WatchMaterial;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything you can pick up, and what each of them is for.
 *
 * <p><b>One registry, because the four ways of getting food have to arrive
 * somewhere in common.</b> Berries come off bushes and trees, seeds out of
 * grass heads and cones, fish out of the water, and prepared food out of the
 * fire — and all four end up as a key in a satchel, on a feeder, and in a
 * species' {@link Diet}. Keeping them one kind of thing is what makes "this
 * bird will come to that" a lookup rather than four special cases.
 *
 * <p>An item's {@link Kind} decides where it comes from and what can be done
 * with it; its {@link Item#appeal()} is how strongly it draws anything that
 * eats it, which is what makes cooking worth doing — a suet cake is worth four
 * handfuls of seed and reaches three times as far.
 */
public final class Forage {

    /** Where an item comes from, and so how it is obtained. */
    public enum Kind {
        /** Off a bush or a fruiting tree. */
        BERRY("Berry"),
        /** Out of a seed head, a cone, or a picked-over feeder. */
        SEED("Seed"),
        /** Out of the water, with a rod. */
        FISH("Fish"),
        /** Turned up under logs and stones. */
        CRITTER("Invertebrate"),
        /** Gathered, and used for building rather than eating. */
        MATERIAL("Material"),
        /** Made at a fire out of two or three other things. */
        PREPARED("Prepared food"),
        /** A thing you use rather than consume. */
        TOOL("Tool");

        private final String label;

        Kind(String label) { this.label = label; }

        public String label() { return label; }

        /** Whether a feeder will accept this. */
        public boolean edible() {
            return this == BERRY || this == SEED || this == FISH || this == CRITTER
                    || this == PREPARED;
        }
    }

    /**
     * One kind of thing.
     *
     * @param key      stable identifier, as used by diets, saves and the wire
     * @param name     what a player sees
     * @param kind     where it comes from
     * @param appeal   how strongly it pulls anything that eats it, 0–2
     * @param reach    how far a feeder of it is noticed, in metres
     * @param note     one line for the satchel's tooltip
     */
    public record Item(String key, String name, Kind kind, double appeal, double reach,
                       String note) {

        /** Whether this can go on a feeder at all. */
        public boolean edible() { return kind.edible(); }

        /** Which diets will come to it. */
        public List<Diet> diets() {
            List<Diet> out = new ArrayList<>();
            for (Diet d : Diet.values()) {
                if (d.eats(key)) out.add(d);
            }
            return out;
        }
    }

    private static final Map<String, Item> ITEMS = build();

    private Forage() {}

    /** Every item there is, in a stable order. */
    public static List<Item> all() { return List.copyOf(ITEMS.values()); }

    /** The item with this key, or {@code null}. */
    public static Item byKey(String key) {
        // A null key is an unknown item, not an exception: these strings come
        // out of satchels loaded from disk and off the wire, and the map is
        // immutable, which means it throws on a null lookup rather than
        // missing it.
        return key == null ? null : ITEMS.get(key);
    }

    /** What a player sees an item called; the key itself when it is unknown. */
    public static String nameOf(String key) {
        Item item = byKey(key);
        return item != null ? item.name() : key;
    }

    /** Every item of a kind. */
    public static List<Item> ofKind(Kind kind) {
        List<Item> out = new ArrayList<>();
        for (Item item : ITEMS.values()) {
            if (item.kind() == kind) out.add(item);
        }
        return out;
    }

    /**
     * How strongly a food draws a species on a given diet, and from how far —
     * written into {@code out} as {@code appeal, reach}.
     *
     * <p>The item's own pull multiplied by where it sits on that diet's list of
     * favourites. Zero when the species does not eat it at all, which is what
     * keeps a feeder of fish from filling up with finches.
     */
    public static void draw(String key, Diet diet, double[] out) {
        Item item = ITEMS.get(key);
        if (item == null || !item.edible() || diet == null) {
            out[0] = 0;
            out[1] = 0;
            return;
        }
        double appetite = diet.appeal(key);
        out[0] = item.appeal() * appetite;
        out[1] = appetite > 0 ? item.reach() : 0;
    }

    /**
     * What a piece of ground gives up to somebody turning it over.
     *
     * <p>The <b>region</b> decides most of it — a wood has fallen branches in
     * it, a marsh has reeds — and the <b>surface actually underfoot</b> decides
     * the rest, which is what makes sand a beach and quartz a crag rather than
     * making both of them properties of a whole biome. Those two are here
     * because they are what a spyglass is made of, and a glass ought to be a
     * reason to walk somewhere.
     *
     * <p><b>Multiplicity is the weighting.</b> A key listed twice is twice as
     * likely to come up, because the caller picks one entry uniformly. That is
     * cruder than a table of probabilities and much easier to read: you can see
     * at a glance that a dune is mostly sand and occasionally quartz.
     *
     * <p>Lives here rather than in {@code WatchGame} so that "what can this
     * world actually give me" is a question with an answer that does not need a
     * running game — which is how a test can prove that everything the recipes
     * ask for is obtainable somewhere.
     *
     * @param surface what the generator says the ground is made of at the point
     * @return the candidates, never empty
     */
    public static List<String> underfoot(WatchBiome biome, WatchMaterial surface) {
        List<String> options = new ArrayList<>();
        if (biome != null) {
            if (!biome.trees().isEmpty()) {
                options.add("fallen_branch");
                options.add("bark_strip");
                options.add("sap");
            }
            if (biome.humidity() > 70) options.add("reed_bundle");
            if (biome.humidity() > 55) options.add("vine");
            if (biome.rockDensity() > 0.004) options.add("stone");
            if (biome.humidity() > 45) options.add("clay_lump");
        }

        if (surface != null) {
            switch (surface) {
                case SAND, RED_SAND, TRAIL_SAND -> {
                    // A beach or a dune: mostly sand, and the odd quartz pebble
                    // washed out of whatever the sand used to be.
                    options.add("sand");
                    options.add("sand");
                    options.add("quartz");
                }
                case ROCK, DARK_ROCK, GRAVEL, TRAIL_STONE -> {
                    options.add("stone");
                    options.add("quartz");
                }
                case CRYSTAL -> {
                    // The one place a lens is easy, and it is a fantasy biome
                    // half a world away from wherever anybody starts.
                    options.add("quartz");
                    options.add("quartz");
                }
                default -> { }
            }
        }

        options.add("clover");
        options.add("feather");
        return options;
    }

    private static Map<String, Item> build() {
        Map<String, Item> map = new LinkedHashMap<>();

        // --- berries and fruit ---------------------------------------------------
        berry(map, "blackberry", "Blackberry", "Hedgerows everywhere; stains everything.");
        berry(map, "blueberry", "Blueberry", "Low bushes on acid ground.");
        berry(map, "lingonberry", "Lingonberry", "Under the pines, all winter.");
        berry(map, "cloudberry", "Cloudberry", "Bog gold. Two weeks a year.");
        berry(map, "elderberry", "Elderberry", "Heavy black umbels; thrushes queue for them.");
        berry(map, "juniper", "Juniper Berry", "Resinous. Waxwings do not mind.");
        berry(map, "salmonberry", "Salmonberry", "Wet ground, bright orange.");
        berry(map, "thimbleberry", "Thimbleberry", "Falls apart if you look at it.");
        berry(map, "crowberry", "Crowberry", "Tundra crawler; almost no flavour.");
        berry(map, "snowberry", "Snowberry", "White, waxy, and best left to the birds.");
        berry(map, "prickly_pear", "Prickly Pear", "Worth the spines.");
        berry(map, "guava", "Guava", "The whole canopy can smell it.");
        berry(map, "fig", "Fig", "A fruiting fig is the busiest tree in the forest.");
        berry(map, "sea_grape", "Sea Grape", "Coastal, salt-tolerant, faintly sweet.");
        berry(map, "mangrove_apple", "Mangrove Apple", "Floats. That is how it gets about.");
        berry(map, "nightbell", "Nightbell", "Only opens after dark, and glows faintly.");
        berry(map, "dewfruit", "Dewfruit", "Cold to hold, whatever the weather.");
        berry(map, "amethyst_plum", "Amethyst Plum", "Off an amethyst tree. Tastes purple.");
        berry(map, "sun_pear", "Sun Pear", "Warm to the touch at noon.");
        berry(map, "date", "Date", "Off a fan palm; keeps for a season.");
        berry(map, "moon_date", "Moon Date", "Pale, and sweeter at night.");
        berry(map, "cactus_fruit", "Cactus Fruit", "Saguaro's, and hard-won.");
        berry(map, "baobab_fruit", "Baobab Fruit", "Chalky pods the size of your hand.");
        berry(map, "cocoa_pod", "Cocoa Pod", "Straight off the trunk.");
        berry(map, "coconut", "Coconut", "Heavy. Mind your head under the palms.");
        berry(map, "glow_spore", "Glow Spore", "Off a glowcap; lights a satchel.");
        berry(map, "star_spore", "Star Spore", "Off a starcap. Nobody knows why it twinkles.");
        berry(map, "kapok_pod", "Kapok Pod", "Bursts into floss when it dries.");

        // --- seeds ---------------------------------------------------------------
        seed(map, "grass_seed", "Grass Seed", "The staple. Plant it or scatter it.");
        seed(map, "sunflower_seed", "Sunflower Seed", "Fat, oily, and universally popular.");
        seed(map, "thistle_seed", "Thistle Seed", "Tiny. Finches specialise in it.");
        seed(map, "millet", "Millet", "What a ground feeder wants.");
        seed(map, "wild_rice", "Wild Rice", "Marsh grain; waterfowl come from a mile off.");
        seed(map, "lupine_seed", "Lupine Seed", "Alpine. Grows into a spire of flowers.");
        seed(map, "sedge_seed", "Sedge Seed", "Wet ground and cold ground both.");
        seed(map, "acorn", "Acorn", "Plant it and wait thirty years. Or don't.");
        seed(map, "beechnut", "Beechnut", "Mast years bring everything out of the wood.");
        seed(map, "pine_seed", "Pine Seed", "Out of a cone, with patience.");
        seed(map, "birch_seed", "Birch Seed", "Blows for miles.");
        seed(map, "redwood_cone", "Redwood Cone", "Absurdly small for the tree it makes.");
        seed(map, "palm_seed", "Palm Seed", "A coconut is one of these, mostly.");
        seed(map, "bamboo_seed", "Bamboo Seed", "Flowers once a lifetime.");
        seed(map, "cactus_seed", "Cactus Seed", "Needs a hot spot and no water.");
        seed(map, "kapok_seed", "Kapok Seed", "Comes wrapped in its own parachute.");
        seed(map, "amethyst_seed", "Amethyst Seed", "Faceted. Should not be a seed at all.");
        seed(map, "spore_pod", "Spore Pod", "Grows a glowcap, given damp and dark.");
        seed(map, "dawn_cone", "Dawn Cone", "Off a dawn cedar. Smells of nectar.");
        seed(map, "samara", "Samara", "The maple's helicopter.");
        seed(map, "catkin", "Catkin", "Birch and aspen both. Full of tiny seed.");

        // --- fish and invertebrates ----------------------------------------------
        item(map, "trout", "Trout", Kind.FISH, 1.4, 26, "Cold clean water.");
        item(map, "char", "Char", Kind.FISH, 1.4, 26, "Deeper and colder still.");
        item(map, "pike", "Pike", Kind.FISH, 1.5, 30, "A predator; something else's meal now.");
        item(map, "perch", "Perch", Kind.FISH, 1.3, 24, "Striped, obliging, everywhere.");
        item(map, "minnow_bait", "Minnow", Kind.FISH, 1.1, 22, "Bait, or a kingfisher's lunch.");
        item(map, "beetle", "Beetle", Kind.CRITTER, 0.9, 16, "Under a log, reliably.");
        item(map, "mealworms", "Mealworms", Kind.CRITTER, 1.2, 20, "Everything insectivorous, at once.");

        // --- materials -----------------------------------------------------------
        material(map, "fallen_branch", "Fallen Branch", "The frame of everything you build.");
        material(map, "bark_strip", "Bark Strip", "Roofing, and cordage at a pinch.");
        material(map, "reed_bundle", "Reed Bundle", "Thatch. Cut it in the marsh.");
        material(map, "stone", "Stone", "Footings, hearths, and a fish-smoking pit.");
        material(map, "vine", "Vine", "Lashing. A treehouse is mostly lashing.");
        material(map, "clay_lump", "Clay Lump", "Daub, and a passable oven.");
        material(map, "sap", "Sap", "Glue, and the base of every syrup.");
        material(map, "feather", "Feather", "Moulted, never plucked. Fletching and quills.");
        material(map, "plank", "Plank", "Split from a branch. Floors and walls.");
        material(map, "thatch", "Thatch", "Bound reeds. A roof for a season.");
        material(map, "rope", "Rope", "Twisted bark. Every lashing in a treehouse.");
        // The three the spyglass is made of. Sand and quartz are picked off the
        // ground like everything else here — but only where the ground has them,
        // which is what makes the glass a thing you travel for rather than a
        // thing you make in the first clearing. See `underfoot` above.
        material(map, "sand", "Sand", "Off a dune or a beach. Grinding paste, with water.");
        material(map, "quartz", "Clear Quartz", "Water-clear, out of rock. A lens is in there.");
        material(map, "lens", "Ground Lens", "Quartz, ground and polished until it gathers light.");

        // --- prepared ------------------------------------------------------------
        prepared(map, "suet_cake", "Suet Cake", 1.9, 42,
                "Fat, seed and berry pressed together. Nothing refuses it.");
        prepared(map, "berry_mash", "Berry Mash", 1.6, 34,
                "Three berries, crushed. Thrushes, waxwings, bears.");
        prepared(map, "nectar", "Nectar", 1.8, 30,
                "Sap and petals, warmed. Hummingbirds only, and all of them.");
        prepared(map, "sugar_water", "Sugar Water", 1.4, 26, "Cruder nectar. Still works.");
        prepared(map, "petal_syrup", "Petal Syrup", 1.7, 32,
                "Slower to make, and it keeps.");
        prepared(map, "grain_loaf", "Grain Loaf", 1.6, 36,
                "Millet and rice baked into a slab. Ground feeders swarm it.");
        prepared(map, "grub_tray", "Grub Tray", 1.7, 32,
                "Beetles and mealworms on bark. Unpleasant; effective.");
        prepared(map, "smoked_fish", "Smoked Fish", 1.8, 40,
                "Keeps for a week and can be smelled for most of one.");
        prepared(map, "moth_lamp", "Moth Lamp", 1.5, 28,
                "A lit lamp is a lure for what eats what it draws.");
        prepared(map, "hay_bundle", "Hay Bundle", 1.3, 30, "Cut grass, dried. Grazers.");
        prepared(map, "salt_lick", "Salt Lick", 1.6, 44,
                "Deer will cross a valley for this. Slowly.");
        prepared(map, "meat_scrap", "Meat Scrap", 1.5, 38, "For the ones with hooked bills.");
        prepared(map, "clover", "Clover", 1.1, 22, "Picked, not cooked. Hares and rabbits.");

        // --- tools ---------------------------------------------------------------
        item(map, "rod", "Fishing Rod", Kind.TOOL, 0, 0, "Branch, vine, and patience.");
        item(map, "trowel", "Trowel", Kind.TOOL, 0, 0, "For planting what you found.");
        item(map, "feeder", "Feeder", Kind.TOOL, 0, 0,
                "Put food in it, stand well back, and wait.");
        item(map, "journal", "Field Journal", Kind.TOOL, 0, 0,
                "Where the sightings go. You start with it.");
        item(map, Spyglass.ITEM, "Spyglass", Kind.TOOL, 0, 0,
                "Two lenses in a tube. Hold it up and the far shore comes to you.");

        return Map.copyOf(map);
    }

    private static void berry(Map<String, Item> map, String key, String name, String note) {
        item(map, key, name, Kind.BERRY, 1.0, 20, note);
    }

    private static void seed(Map<String, Item> map, String key, String name, String note) {
        item(map, key, name, Kind.SEED, 0.9, 18, note);
    }

    private static void material(Map<String, Item> map, String key, String name, String note) {
        item(map, key, name, Kind.MATERIAL, 0, 0, note);
    }

    private static void prepared(Map<String, Item> map, String key, String name,
                                 double appeal, double reach, String note) {
        item(map, key, name, Kind.PREPARED, appeal, reach, note);
    }

    private static void item(Map<String, Item> map, String key, String name, Kind kind,
                             double appeal, double reach, String note) {
        map.put(key, new Item(key, name, kind, appeal, reach, note));
    }
}
