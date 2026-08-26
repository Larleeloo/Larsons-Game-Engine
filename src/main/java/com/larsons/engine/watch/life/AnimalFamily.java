package com.larsons.engine.watch.life;

import java.util.List;

/**
 * The twenty-six body plans every animal in the world is built from.
 *
 * <p><b>A family is the half of a species that is shared.</b> It carries the
 * shape ({@link Build}), how the thing gets about ({@link Motion}), when it is
 * awake, what it eats, roughly how big it is, how close you can get before it
 * flushes, and which biomes it lives in. What it does not carry is a name, a
 * colour or a rarity — those come from the lineage and the epithet that
 * {@link AnimalRegistry} crosses it with, and that crossing is what turns
 * twenty-six rows into more than a thousand species.
 *
 * <p><b>Why the lineages are here.</b> A lineage ("Finch", "Goshawk") is what
 * makes a species sound like a real animal rather than like a generated one,
 * and it belongs to exactly one family — a finch is a songbird and nothing
 * else. Keeping the list on the family is what guarantees that every one of the
 * hundred and eighty-two lineage names in this file is used once, which is in
 * turn what guarantees that no two species in the game share a name. There is a
 * test for it.
 */
public enum AnimalFamily {

    // --- birds ------------------------------------------------------------------

    SONGBIRD("songbird", "Songbirds", Build.SMALL_BIRD, Motion.FLIT, Activity.DIURNAL,
            Diet.SEEDS, 0.13, 0.62, 0x8C6A3E, 0.30,
            biomes("deciduous_forest", "pine_forest", "autumn_birchwood", "bamboo_thicket",
                    "sunflower_prairie", "alpine_meadow", "amethyst_grove", "boreal_taiga"),
            lineages("Finch", "Thrush", "Warbler", "Sparrow", "Wren", "Tanager", "Bunting")),

    RAPTOR("raptor", "Raptors", Build.RAPTOR_BIRD, Motion.SOAR, Activity.DIURNAL,
            Diet.CARRION, 0.62, 0.90, 0x6B5A46, 0.04,
            biomes("mountains", "canyon_badlands", "savanna", "alpine_meadow",
                    "tundra_barrens", "pine_forest", "crystal_highlands"),
            lineages("Hawk", "Falcon", "Eagle", "Kite", "Harrier", "Buzzard", "Goshawk")),

    OWL("owl", "Owls", Build.RAPTOR_BIRD, Motion.FLY, Activity.NOCTURNAL,
            Diet.CARRION, 0.42, 0.72, 0x8A7A62, 0.10,
            biomes("deciduous_forest", "pine_forest", "boreal_taiga", "redwood_cathedral",
                    "mushroom_hollow", "autumn_birchwood", "desert"),
            lineages("Owlet", "Scops", "Barn Owl", "Eagle Owl", "Hawk Owl", "Pygmy Owl",
                    "Fish Owl")),

    WATERFOWL("waterfowl", "Waterfowl", Build.LARGE_BIRD, Motion.SWIM, Activity.DIURNAL,
            Diet.FOLIAGE, 0.48, 0.55, 0x4A5A6E, 0.22,
            biomes("wetland_marsh", "mangrove_coast", "tropics", "tundra_barrens",
                    "boreal_taiga", "redwood_cathedral"),
            lineages("Duck", "Goose", "Swan", "Teal", "Merganser", "Eider", "Pochard")),

    WADER("wader", "Waders", Build.WADING_BIRD, Motion.WALK, Activity.CREPUSCULAR,
            Diet.FISH, 0.86, 0.80, 0x9AA0A6, 0.06,
            biomes("wetland_marsh", "mangrove_coast", "tropics", "rainforest",
                    "savanna", "bamboo_thicket"),
            lineages("Heron", "Egret", "Stork", "Ibis", "Crane", "Spoonbill", "Bittern")),

    SHOREBIRD("shorebird", "Shorebirds", Build.WADING_BIRD, Motion.WALK, Activity.DIURNAL,
            Diet.INSECTS, 0.24, 0.70, 0xB8A88C, 0.14,
            biomes("tropics", "mangrove_coast", "wetland_marsh", "tundra_barrens",
                    "canyon_badlands"),
            lineages("Plover", "Sandpiper", "Curlew", "Godwit", "Turnstone", "Avocet",
                    "Oystercatcher")),

    HUMMINGBIRD("hummingbird", "Hummingbirds", Build.HOVERER, Motion.HOVER, Activity.DIURNAL,
            Diet.NECTAR, 0.08, 0.48, 0x2E8A6A, 0.34,
            biomes("rainforest", "tropics", "amethyst_grove", "sunflower_prairie",
                    "bamboo_thicket", "alpine_meadow"),
            lineages("Hermit", "Sylph", "Coquette", "Starthroat", "Woodstar", "Sunangel",
                    "Brilliant")),

    PARROT("parrot", "Parrots", Build.HOOKED_BILL, Motion.FLY, Activity.DIURNAL,
            Diet.NUTS, 0.34, 0.52, 0x2E8A3C, 0.40,
            biomes("rainforest", "tropics", "mangrove_coast", "bamboo_thicket",
                    "amethyst_grove"),
            lineages("Macaw", "Parakeet", "Lorikeet", "Cockatoo", "Amazon", "Conure",
                    "Lovebird")),

    CORVID("corvid", "Corvids", Build.LARGE_BIRD, Motion.FLY, Activity.DIURNAL,
            Diet.OMNIVORE, 0.42, 0.44, 0x2A2A32, 0.44,
            biomes("deciduous_forest", "pine_forest", "mountains", "canyon_badlands",
                    "tundra_barrens", "savanna", "autumn_birchwood", "crystal_highlands"),
            lineages("Crow", "Raven", "Jay", "Magpie", "Jackdaw", "Rook", "Nutcracker")),

    WOODPECKER("woodpecker", "Bark Birds", Build.SMALL_BIRD, Motion.CLIMB, Activity.DIURNAL,
            Diet.INSECTS, 0.20, 0.60, 0x1F1F26, 0.20,
            biomes("deciduous_forest", "pine_forest", "redwood_cathedral", "boreal_taiga",
                    "autumn_birchwood", "bamboo_thicket", "mushroom_hollow"),
            lineages("Flicker", "Sapsucker", "Piculet", "Wryneck", "Woodpecker", "Nuthatch",
                    "Treecreeper")),

    GAMEBIRD("gamebird", "Ground Birds", Build.LARGE_BIRD, Motion.WALK, Activity.CREPUSCULAR,
            Diet.GRAIN, 0.38, 0.66, 0x7A6440, 0.26,
            biomes("sunflower_prairie", "savanna", "alpine_meadow", "tundra_barrens",
                    "boreal_taiga", "canyon_badlands", "desert"),
            lineages("Grouse", "Pheasant", "Quail", "Partridge", "Ptarmigan", "Francolin",
                    "Guineafowl")),

    SEABIRD("seabird", "Seabirds", Build.LARGE_BIRD, Motion.SOAR, Activity.DIURNAL,
            Diet.FISH, 0.44, 0.38, 0xE0E4EA, 0.18,
            biomes("tropics", "mangrove_coast", "wetland_marsh", "tundra_barrens",
                    "canyon_badlands"),
            lineages("Gull", "Tern", "Petrel", "Gannet", "Skua", "Puffin", "Albatross")),

    // --- mammals ----------------------------------------------------------------

    DEER("deer", "Deer", Build.DEER_LIKE, Motion.WALK, Activity.CREPUSCULAR,
            Diet.FOLIAGE, 1.35, 0.86, 0x8A6A46, 0.08,
            biomes("deciduous_forest", "pine_forest", "boreal_taiga", "autumn_birchwood",
                    "redwood_cathedral", "alpine_meadow", "tundra_barrens", "amethyst_grove"),
            lineages("Roe", "Fallow", "Sika", "Muntjac", "Elk", "Caribou", "Brocket")),

    CANID("canid", "Canids", Build.QUADRUPED, Motion.WALK, Activity.CREPUSCULAR,
            Diet.OMNIVORE, 0.92, 0.82, 0xA0663A, 0.10,
            biomes("boreal_taiga", "tundra_barrens", "savanna", "desert", "mountains",
                    "pine_forest", "canyon_badlands", "alpine_meadow"),
            lineages("Fox", "Wolf", "Jackal", "Coyote", "Dhole", "Fennec", "Maned Wolf")),

    FELID("felid", "Small Cats", Build.CAT_LIKE, Motion.WALK, Activity.NOCTURNAL,
            Diet.CARRION, 0.78, 0.92, 0xB89A66, 0.05,
            biomes("rainforest", "bamboo_thicket", "boreal_taiga", "mountains",
                    "savanna", "redwood_cathedral", "crystal_highlands"),
            lineages("Lynx", "Ocelot", "Serval", "Caracal", "Margay", "Jaguarundi",
                    "Wildcat")),

    RODENT("rodent", "Rodents", Build.SMALL_MAMMAL, Motion.HOP, Activity.DIURNAL,
            Diet.NUTS, 0.24, 0.58, 0x8A6E4A, 0.42,
            biomes("deciduous_forest", "pine_forest", "autumn_birchwood", "alpine_meadow",
                    "sunflower_prairie", "mountains", "bamboo_thicket", "mushroom_hollow"),
            lineages("Squirrel", "Chipmunk", "Vole", "Dormouse", "Marmot", "Gopher",
                    "Agouti")),

    MUSTELID("mustelid", "Mustelids", Build.LONG_MAMMAL, Motion.WALK, Activity.NOCTURNAL,
            Diet.FISH, 0.56, 0.84, 0x6A4E33, 0.16,
            biomes("wetland_marsh", "boreal_taiga", "redwood_cathedral", "mountains",
                    "pine_forest", "mangrove_coast", "tundra_barrens"),
            lineages("Marten", "Stoat", "Otter", "Badger", "Weasel", "Mink", "Wolverine")),

    PRIMATE("primate", "Primates", Build.PRIMATE_LIKE, Motion.CLIMB, Activity.DIURNAL,
            Diet.BERRIES, 0.52, 0.60, 0x6A5238, 0.32,
            biomes("rainforest", "bamboo_thicket", "mangrove_coast", "tropics",
                    "amethyst_grove"),
            lineages("Tamarin", "Marmoset", "Langur", "Capuchin", "Macaque", "Gibbon",
                    "Colobus")),

    BEAR("bear", "Bears", Build.BULKY, Motion.WALK, Activity.CATHEMERAL,
            Diet.BERRIES, 1.70, 0.74, 0x4E3826, 0.03,
            biomes("boreal_taiga", "redwood_cathedral", "mountains", "bamboo_thicket",
                    "pine_forest", "tundra_barrens", "mushroom_hollow"),
            lineages("Black Bear", "Brown Bear", "Sun Bear", "Sloth Bear", "Moon Bear",
                    "Spectacled Bear", "Grizzly")),

    BOVID("bovid", "Bovids", Build.HORNED, Motion.WALK, Activity.DIURNAL,
            Diet.FOLIAGE, 1.20, 0.78, 0x9A8A6A, 0.09,
            biomes("mountains", "alpine_meadow", "canyon_badlands", "savanna", "desert",
                    "tundra_barrens", "crystal_highlands"),
            lineages("Ibex", "Chamois", "Gazelle", "Oryx", "Tahr", "Markhor", "Bighorn")),

    HARE("hare", "Hares & Kin", Build.LAGOMORPH, Motion.HOP, Activity.CREPUSCULAR,
            Diet.FOLIAGE, 0.36, 0.76, 0xA08A6A, 0.38,
            biomes("sunflower_prairie", "alpine_meadow", "tundra_barrens", "desert",
                    "savanna", "mountains", "canyon_badlands"),
            lineages("Hare", "Rabbit", "Pika", "Jackrabbit", "Cottontail", "Viscacha",
                    "Mara")),

    BAT("bat", "Bats", Build.BAT_LIKE, Motion.FLY, Activity.NOCTURNAL,
            Diet.INSECTS, 0.16, 0.50, 0x4A3A34, 0.20,
            biomes("mushroom_hollow", "rainforest", "canyon_badlands", "bamboo_thicket",
                    "deciduous_forest", "mangrove_coast", "crystal_highlands"),
            lineages("Pipistrelle", "Noctule", "Fruit Bat", "Horseshoe Bat", "Myotis",
                    "Free-tail", "Flying Fox")),

    // --- the smaller kingdoms ---------------------------------------------------

    REPTILE("reptile", "Reptiles", Build.LIZARD, Motion.WALK, Activity.DIURNAL,
            Diet.INSECTS, 0.30, 0.68, 0x6E7A46, 0.24,
            biomes("desert", "canyon_badlands", "savanna", "rainforest", "tropics",
                    "mangrove_coast", "bamboo_thicket"),
            lineages("Skink", "Gecko", "Iguana", "Monitor", "Anole", "Agama",
                    "Chameleon")),

    AMPHIBIAN("amphibian", "Amphibians", Build.AMPHIB, Motion.HOP, Activity.NOCTURNAL,
            Diet.INSECTS, 0.11, 0.54, 0x3E7A50, 0.36,
            biomes("wetland_marsh", "rainforest", "mushroom_hollow", "mangrove_coast",
                    "redwood_cathedral", "bamboo_thicket"),
            lineages("Tree Frog", "Newt", "Salamander", "Toad", "Chorus Frog", "Axolotl",
                    "Dart Frog")),

    BUTTERFLY("butterfly", "Butterflies", Build.WINGED_INSECT, Motion.FLIT, Activity.DIURNAL,
            Diet.NECTAR, 0.07, 0.40, 0xD8963E, 0.46,
            biomes("sunflower_prairie", "alpine_meadow", "rainforest", "amethyst_grove",
                    "bamboo_thicket", "deciduous_forest", "tropics"),
            lineages("Swallowtail", "Fritillary", "Admiral", "Skipper", "Hairstreak",
                    "Morpho", "Birdwing")),

    FISH("fish", "Freshwater Fish", Build.FISH_LIKE, Motion.SWIM, Activity.CATHEMERAL,
            Diet.INSECTS, 0.32, 0.66, 0x6A7A8A, 0.12,
            biomes("wetland_marsh", "mangrove_coast", "tropics", "boreal_taiga",
                    "redwood_cathedral", "alpine_meadow", "mountains"),
            lineages("Trout", "Char", "Pike", "Perch", "Carp", "Minnow", "Grayling")),

    SPRITE("sprite", "Sprites", Build.ETHEREAL, Motion.HOVER, Activity.NOCTURNAL,
            Diet.NECTAR, 0.22, 0.88, 0xA88CE0, 0.28,
            biomes("amethyst_grove", "mushroom_hollow", "crystal_highlands"),
            lineages("Wisp", "Glimmerling", "Moonhare", "Crystal Stag", "Spore Drake",
                    "Lantern Moth", "Dusk Sprite"));

    /** The shape a family is drawn as; see {@link AnimalModel}. */
    public enum Build {
        SMALL_BIRD, LARGE_BIRD, WADING_BIRD, RAPTOR_BIRD, HOOKED_BILL, HOVERER,
        QUADRUPED, DEER_LIKE, CAT_LIKE, SMALL_MAMMAL, LONG_MAMMAL, PRIMATE_LIKE,
        BULKY, HORNED, LAGOMORPH, BAT_LIKE, LIZARD, AMPHIB, WINGED_INSECT,
        FISH_LIKE, ETHEREAL
    }

    /** How a family gets about, which decides its gait and where it can be. */
    public enum Motion {
        /** Walks and runs on the ground. */
        WALK,
        /** Walks, but moves in hops. */
        HOP,
        /** Flies between perches, in short bursts. */
        FLIT,
        /** Flies properly, and lands where it means to. */
        FLY,
        /** Circles on a thermal and rarely lands at all. */
        SOAR,
        /** Holds a position in the air. */
        HOVER,
        /** Climbs trunks and branches. */
        CLIMB,
        /** Lives in the water. */
        SWIM;

        /** Whether this motion leaves the ground. */
        public boolean airborne() {
            return this == FLIT || this == FLY || this == SOAR || this == HOVER;
        }
    }

    private final String key;
    private final String plural;
    private final Build build;
    private final Motion motion;
    private final Activity activity;
    private final Diet diet;
    private final double bodyLength;
    private final double wariness;
    private final int baseColour;
    private final double tameShare;
    private final List<String> biomes;
    private final List<String> lineages;

    AnimalFamily(String key, String plural, Build build, Motion motion, Activity activity,
                 Diet diet, double bodyLength, double wariness, int baseColour,
                 double tameShare, List<String> biomes, List<String> lineages) {
        this.key = key;
        this.plural = plural;
        this.build = build;
        this.motion = motion;
        this.activity = activity;
        this.diet = diet;
        this.bodyLength = bodyLength;
        this.wariness = wariness;
        this.baseColour = baseColour;
        this.tameShare = tameShare;
        this.biomes = biomes;
        this.lineages = lineages;
    }

    private static List<String> biomes(String... keys) { return List.of(keys); }

    private static List<String> lineages(String... names) { return List.of(names); }

    /** Stable identifier — the first segment of every species key in this family. */
    public String key() { return key; }

    /** What the field guide's contents page calls this group. */
    public String plural() { return plural; }

    public Build build() { return build; }

    public Motion motion() { return motion; }

    /** When this family is normally awake, before an epithet shifts it. */
    public Activity activity() { return activity; }

    /** What it normally eats, before an epithet shifts it. */
    public Diet diet() { return diet; }

    /** Nose to tail, in metres, before a lineage and an epithet scale it. */
    public double bodyLength() { return bodyLength; }

    /**
     * How hard this family is to approach, 0 (indifferent) – 1 (gone before you
     * saw it). A raptor at 0.90 flushes at forty metres; a butterfly at 0.40
     * will let you stand next to it.
     */
    public double wariness() { return wariness; }

    /** The colour the palette of every species in this family is built from. */
    public int baseColour() { return baseColour; }

    /** What share of this family's species can be tamed. */
    public double tameShare() { return tameShare; }

    /** The biome keys this family lives in. */
    public List<String> biomes() { return biomes; }

    /** The seven lineage names crossed with epithets to make this family's species. */
    public List<String> lineages() { return lineages; }

    /** The texture key a pack reskins this whole family under. */
    public String textureKey() { return "watch/animal/" + key; }

    /** The family a saved key means, or {@code null}. */
    public static AnimalFamily of(String key) {
        for (AnimalFamily f : values()) {
            if (f.key.equals(key)) return f;
        }
        return null;
    }
}
