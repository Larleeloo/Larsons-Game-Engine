package com.larsons.engine.watch.world;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The kinds of tree that grow here — thirty-six of them, twelve of which
 * nothing in the world plants and only a gardener can produce.
 *
 * <p><b>A species is a shape and a pair of materials.</b> Everything the
 * mesher needs to build a tree is on this enum: which {@link Form} its crown
 * takes, how tall its trunk gets, how wide the canopy reaches, how many
 * canopy layers it stacks, what its wood and its leaves are made of, and
 * whether it fruits. What it does <em>not</em> carry is a size — that comes
 * from the {@link Stage} the individual tree has reached, which is what makes
 * a forest of one species read as a forest rather than as a stamp repeated.
 *
 * <p><b>The twelve hybrids.</b> {@link #hybridOnly()} species have no natural
 * range: no biome lists them and the wild generator never places one. The only
 * way to see one is to plant two different species close together, let both
 * reach {@link Stage#MATURE}, and cross them ({@code Grove.pollinate}); the
 * pair is looked up in {@link #hybrid}. Six of the twelve are the offspring of
 * another hybrid, so the last of them takes three generations of gardening —
 * which is the point of having them.
 */
public enum TreeSpecies {

    // --- conifers -------------------------------------------------------------

    PINE("pine", "Pine", Form.CONIFER, WatchMaterial.BARK, WatchMaterial.PINE_NEEDLE,
            18, 0.45, 3.4, 5, null, 26),
    SPRUCE("spruce", "Spruce", Form.CONIFER, WatchMaterial.DARK_BARK, WatchMaterial.PINE_NEEDLE,
            22, 0.5, 3.0, 6, null, 30),
    FIR("fir", "Fir", Form.CONIFER, WatchMaterial.DARK_BARK, WatchMaterial.PINE_NEEDLE,
            20, 0.45, 3.2, 5, null, 28),
    CEDAR("cedar", "Cedar", Form.CONIFER, WatchMaterial.BARK, WatchMaterial.LEAF,
            17, 0.55, 4.0, 4, null, 32),

    // --- broadleaves ----------------------------------------------------------

    OAK("oak", "Oak", Form.BROADLEAF, WatchMaterial.BARK, WatchMaterial.LEAF,
            14, 0.6, 5.0, 3, "acorn", 34),
    MAPLE("maple", "Maple", Form.BROADLEAF, WatchMaterial.BARK, WatchMaterial.AUTUMN_LEAF,
            13, 0.5, 4.6, 3, "samara", 30),
    BIRCH("birch", "Birch", Form.BROADLEAF, WatchMaterial.PALE_BARK, WatchMaterial.LEAF,
            12, 0.32, 3.2, 3, "catkin", 22),
    BEECH("beech", "Beech", Form.BROADLEAF, WatchMaterial.PALE_BARK, WatchMaterial.LEAF,
            15, 0.55, 5.2, 3, "beechnut", 33),
    ASPEN("aspen", "Aspen", Form.BROADLEAF, WatchMaterial.PALE_BARK, WatchMaterial.AUTUMN_LEAF,
            13, 0.3, 3.0, 3, "catkin", 20),
    WILLOW("willow", "Willow", Form.WEEPING, WatchMaterial.BARK, WatchMaterial.LEAF,
            11, 0.55, 5.4, 3, null, 24),

    // --- warm and tropical ----------------------------------------------------

    PALM("palm", "Fan Palm", Form.PALM, WatchMaterial.BARK, WatchMaterial.PALM_FROND,
            13, 0.3, 3.6, 1, "date", 26),
    COCONUT_PALM("coconut_palm", "Coconut Palm", Form.PALM, WatchMaterial.BARK,
            WatchMaterial.PALM_FROND, 16, 0.34, 4.0, 1, "coconut", 30),
    MAHOGANY("mahogany", "Mahogany", Form.EMERGENT, WatchMaterial.DARK_BARK, WatchMaterial.LEAF,
            26, 0.75, 6.5, 3, null, 44),
    KAPOK("kapok", "Kapok", Form.EMERGENT, WatchMaterial.PALE_BARK, WatchMaterial.LEAF,
            32, 0.9, 7.5, 2, "kapok_pod", 52),
    ACACIA("acacia", "Acacia", Form.UMBRELLA, WatchMaterial.BARK, WatchMaterial.LEAF,
            10, 0.45, 6.0, 2, null, 24),
    BAOBAB("baobab", "Baobab", Form.BOTTLE, WatchMaterial.PALE_BARK, WatchMaterial.LEAF,
            15, 1.6, 5.0, 2, "baobab_fruit", 60),
    MANGROVE("mangrove", "Mangrove", Form.STILT, WatchMaterial.DARK_BARK, WatchMaterial.LEAF,
            9, 0.4, 4.2, 3, null, 22),

    // --- specialists ----------------------------------------------------------

    REDWOOD("redwood", "Redwood", Form.COLUMN, WatchMaterial.BARK, WatchMaterial.PINE_NEEDLE,
            48, 1.5, 6.0, 4, null, 70),
    BAMBOO("bamboo", "Bamboo", Form.CANE, WatchMaterial.BAMBOO, WatchMaterial.BAMBOO,
            9, 0.12, 0.9, 4, null, 8),
    SAGUARO("saguaro", "Saguaro", Form.CACTUS, WatchMaterial.LUSH_GRASS, WatchMaterial.LUSH_GRASS,
            8, 0.42, 1.8, 2, "cactus_fruit", 48),
    JOSHUA("joshua", "Joshua Tree", Form.CACTUS, WatchMaterial.BARK, WatchMaterial.MOSS,
            8, 0.4, 2.6, 3, null, 40),

    // --- fantasy --------------------------------------------------------------

    AMETHYST("amethyst", "Amethyst Tree", Form.BROADLEAF, WatchMaterial.PALE_BARK,
            WatchMaterial.PURPLE_LEAF, 16, 0.5, 5.0, 4, "amethyst_plum", 36),
    GLOWCAP("glowcap", "Glowcap", Form.MUSHROOM, WatchMaterial.PALE_BARK, WatchMaterial.CAP,
            7, 0.5, 3.8, 1, "glow_spore", 14),
    CRYSTAL_PINE("crystal_pine", "Crystal Pine", Form.CRYSTAL, WatchMaterial.CRYSTAL,
            WatchMaterial.CRYSTAL, 15, 0.4, 3.0, 5, null, 42),

    // --- hybrids: none of these grows wild ------------------------------------

    SILVER_PINE("silver_pine", "Silver Pine", Form.CONIFER, WatchMaterial.PALE_BARK,
            WatchMaterial.PINE_NEEDLE, 20, 0.4, 3.4, 5, null, 28, true),
    AMETHYST_OAK("amethyst_oak", "Amethyst Oak", Form.BROADLEAF, WatchMaterial.BARK,
            WatchMaterial.PURPLE_LEAF, 17, 0.6, 5.4, 4, "amethyst_plum", 38, true),
    BLOOD_MAPLE("blood_maple", "Blood Maple", Form.BROADLEAF, WatchMaterial.DARK_BARK,
            WatchMaterial.BERRY, 14, 0.5, 4.8, 3, "samara", 32, true),
    SUNWILLOW("sunwillow", "Sunwillow", Form.WEEPING, WatchMaterial.PALE_BARK,
            WatchMaterial.PETAL, 12, 0.5, 5.6, 3, "sun_pear", 30, true),
    GLASS_FIR("glass_fir", "Glass Fir", Form.CRYSTAL, WatchMaterial.CRYSTAL,
            WatchMaterial.ICE, 19, 0.42, 3.1, 5, null, 44, true),
    EMBERWOOD("emberwood", "Emberwood", Form.COLUMN, WatchMaterial.DARK_BARK,
            WatchMaterial.AUTUMN_LEAF, 34, 1.2, 5.5, 4, null, 62, true),
    MOONPALM("moonpalm", "Moonpalm", Form.PALM, WatchMaterial.PALE_BARK,
            WatchMaterial.PURPLE_LEAF, 15, 0.32, 4.0, 1, "moon_date", 32, true),
    GHOST_BIRCH("ghost_birch", "Ghost Birch", Form.BROADLEAF, WatchMaterial.SNOW,
            WatchMaterial.ICE, 13, 0.3, 3.2, 3, "catkin", 24, true),
    IRON_BEECH("iron_beech", "Iron Beech", Form.BROADLEAF, WatchMaterial.DARK_ROCK,
            WatchMaterial.LEAF, 17, 0.7, 5.4, 3, "beechnut", 46, true),
    TIDECANE("tidecane", "Tidecane", Form.CANE, WatchMaterial.BAMBOO, WatchMaterial.SHALLOWS,
            11, 0.14, 1.0, 4, null, 12, true),
    STARCAP("starcap", "Starcap", Form.MUSHROOM, WatchMaterial.CRYSTAL, WatchMaterial.CRYSTAL,
            9, 0.5, 4.2, 1, "star_spore", 20, true),
    DAWN_CEDAR("dawn_cedar", "Dawn Cedar", Form.CONIFER, WatchMaterial.BARK,
            WatchMaterial.PETAL, 19, 0.55, 4.2, 4, "dawn_cone", 36, true);

    /** The crown shapes a tree can take; what the mesher switches on. */
    public enum Form {
        /** A stack of shrinking rings — every needle-leaf tree. */
        CONIFER,
        /** A ball of leaf clusters on a branching trunk. */
        BROADLEAF,
        /** A broadleaf whose outer clusters hang below the branch. */
        WEEPING,
        /** A bare stem with fronds radiating off the top. */
        PALM,
        /** A very tall bare trunk with a flat crown above the canopy. */
        EMERGENT,
        /** A short trunk with a wide, flat, layered top. */
        UMBRELLA,
        /** A swollen trunk with a sparse crown. */
        BOTTLE,
        /** Stilt roots below a low crown; grows in shallow water. */
        STILT,
        /** A straight column with a narrow crown very high up. */
        COLUMN,
        /** Jointed canes in a clump, no crown to speak of. */
        CANE,
        /** Ribbed columns with arms; no leaves at all. */
        CACTUS,
        /** A stalk and a cap. */
        MUSHROOM,
        /** Faceted shards instead of foliage. */
        CRYSTAL
    }

    /**
     * How far along a tree is. A stage scales the whole tree and decides how
     * much of its crown exists, so a seedling really is a twig with two leaves
     * on it and not a small copy of the mature tree.
     */
    public enum Stage {
        SEEDLING("Seedling", 0.06, 0),
        SAPLING("Sapling", 0.22, 1),
        YOUNG("Young", 0.55, 2),
        MATURE("Mature", 1.00, 3),
        ANCIENT("Ancient", 1.28, 4);

        private final String label;
        private final double scale;
        private final int crownLayers;

        Stage(String label, double scale, int crownLayers) {
            this.label = label;
            this.scale = scale;
            this.crownLayers = crownLayers;
        }

        /** What a player-facing panel calls this. */
        public String label() { return label; }

        /** How much of the species' full size a tree at this stage has. */
        public double scale() { return scale; }

        /**
         * How many of the species' canopy layers are built at this stage,
         * capped by the species' own count. Zero is a bare stem with a tuft.
         */
        public int crownLayers() { return crownLayers; }

        /** The next stage, or this one when there is nothing further. */
        public Stage next() {
            return this == ANCIENT ? this : values()[ordinal() + 1];
        }

        /** Whether a tree at this stage can be crossed with another. */
        public boolean canPollinate() { return ordinal() >= MATURE.ordinal(); }

        /** The stage a saved name means, tolerating anything unknown. */
        public static Stage of(String text, Stage fallback) {
            if (text == null || text.isBlank()) return fallback;
            for (Stage s : values()) {
                if (s.name().equalsIgnoreCase(text.trim())) return s;
            }
            return fallback;
        }
    }

    private final String key;
    private final String name;
    private final Form form;
    private final WatchMaterial wood;
    private final WatchMaterial foliage;
    private final double height;
    private final double trunkRadius;
    private final double canopyRadius;
    private final int canopyLayers;
    private final String fruit;
    private final double growthHours;
    private final boolean hybridOnly;

    TreeSpecies(String key, String name, Form form, WatchMaterial wood, WatchMaterial foliage,
                double height, double trunkRadius, double canopyRadius, int canopyLayers,
                String fruit, double growthHours) {
        this(key, name, form, wood, foliage, height, trunkRadius, canopyRadius, canopyLayers,
                fruit, growthHours, false);
    }

    TreeSpecies(String key, String name, Form form, WatchMaterial wood, WatchMaterial foliage,
                double height, double trunkRadius, double canopyRadius, int canopyLayers,
                String fruit, double growthHours, boolean hybridOnly) {
        this.key = key;
        this.name = name;
        this.form = form;
        this.wood = wood;
        this.foliage = foliage;
        this.height = height;
        this.trunkRadius = trunkRadius;
        this.canopyRadius = canopyRadius;
        this.canopyLayers = canopyLayers;
        this.fruit = fruit;
        this.growthHours = growthHours;
        this.hybridOnly = hybridOnly;
    }

    public String key() { return key; }

    public String displayName() { return name; }

    public Form form() { return form; }

    /** What the trunk and branches are made of. */
    public WatchMaterial wood() { return wood; }

    /** What the crown is made of. */
    public WatchMaterial foliage() { return foliage; }

    /** Trunk height in metres at {@link Stage#MATURE}. */
    public double height() { return height; }

    public double trunkRadius() { return trunkRadius; }

    public double canopyRadius() { return canopyRadius; }

    public int canopyLayers() { return canopyLayers; }

    /** The forage item this species drops, or {@code null} if it does not fruit. */
    public String fruit() { return fruit; }

    /** Real hours from one growth stage to the next, before vigour. */
    public double growthHours() { return growthHours; }

    /** Whether this species exists only as the result of a cross. */
    public boolean hybridOnly() { return hybridOnly; }

    /** Trunk height at a given stage, in metres. */
    public double heightAt(Stage stage) { return height * stage.scale(); }

    /** Crown radius at a given stage, in metres. */
    public double canopyAt(Stage stage) { return canopyRadius * stage.scale(); }

    // --- crossing ----------------------------------------------------------------

    /**
     * The pairs that produce something new. Symmetric — {@link #hybrid} sorts
     * the two parents before looking them up, so a cross is a cross whichever
     * tree the player clicked first.
     */
    private static final Map<String, TreeSpecies> CROSSES = new LinkedHashMap<>();

    private static void cross(TreeSpecies a, TreeSpecies b, TreeSpecies child) {
        CROSSES.put(pairKey(a, b), child);
    }

    static {
        // First generation: two wild species, and a gardener who noticed.
        cross(PINE, BIRCH, SILVER_PINE);
        cross(OAK, AMETHYST, AMETHYST_OAK);
        cross(MAPLE, AMETHYST, BLOOD_MAPLE);
        cross(WILLOW, ACACIA, SUNWILLOW);
        cross(FIR, CRYSTAL_PINE, GLASS_FIR);
        cross(REDWOOD, ACACIA, EMBERWOOD);
        // Second generation: every one of these takes a hybrid for a parent,
        // so it cannot be reached without first growing one of the six above
        // to maturity — three generations of gardening for the last of them.
        cross(PALM, AMETHYST_OAK, MOONPALM);
        cross(BIRCH, SILVER_PINE, GHOST_BIRCH);
        cross(BEECH, EMBERWOOD, IRON_BEECH);
        cross(MANGROVE, SUNWILLOW, TIDECANE);
        cross(GLOWCAP, GLASS_FIR, STARCAP);
        cross(CEDAR, BLOOD_MAPLE, DAWN_CEDAR);
    }

    private static String pairKey(TreeSpecies a, TreeSpecies b) {
        return a.ordinal() <= b.ordinal() ? a.key + "+" + b.key : b.key + "+" + a.key;
    }

    /**
     * What crossing {@code a} with {@code b} produces, or {@code null} when the
     * pair has no hybrid — in which case the cross yields one of the parents
     * (see {@code Grove.pollinate}) rather than failing.
     *
     * <p>Crossing a species with itself is not a cross: it returns
     * {@code null}, because a pine pollinated by a pine is a pine.
     */
    public static TreeSpecies hybrid(TreeSpecies a, TreeSpecies b) {
        if (a == null || b == null || a == b) return null;
        return CROSSES.get(pairKey(a, b));
    }

    /** Every cross that produces something, as {@code parent × parent → child}. */
    public static Map<String, TreeSpecies> crosses() {
        return Map.copyOf(CROSSES);
    }

    /** The species a saved key means, tolerating anything unknown. */
    public static TreeSpecies of(String text, TreeSpecies fallback) {
        if (text == null || text.isBlank()) return fallback;
        String want = text.trim().toLowerCase();
        for (TreeSpecies s : values()) {
            if (s.key.equals(want) || s.name().equalsIgnoreCase(want)) return s;
        }
        return fallback;
    }

    /** Every species that grows without a gardener. */
    public static List<TreeSpecies> wild() {
        List<TreeSpecies> out = new ArrayList<>();
        for (TreeSpecies s : values()) {
            if (!s.hybridOnly) out.add(s);
        }
        return out;
    }
}
