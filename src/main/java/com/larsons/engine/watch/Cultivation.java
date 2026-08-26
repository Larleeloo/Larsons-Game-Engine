package com.larsons.engine.watch;

import com.larsons.engine.watch.world.TreeSpecies;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Seeds you found, put in the ground, and came back to.
 *
 * <p>Two kinds of thing are planted and they are handled differently on
 * purpose:
 *
 * <ul>
 *   <li>a <b>tree seed</b> ({@link #treeFor}) becomes a
 *       {@link com.larsons.engine.watch.world.TreeInstance} in the party's
 *       {@link com.larsons.engine.watch.world.Grove}, where it grows through
 *       five stages over real days and can eventually be crossed with its
 *       neighbours — the breeding game;</li>
 *   <li>anything else becomes a <b>crop</b>, held here, which ripens in real
 *       hours and is then harvested for several times the seed that went in —
 *       the food supply.</li>
 * </ul>
 *
 * <p><b>Both run on the wall clock</b>, like everything else that grows in this
 * game: a patch of millet planted at dusk is ready in the morning whether or
 * not anybody was logged in, which is the entire reason to plant it rather than
 * keep walking.
 */
public final class Cultivation {

    /** How far along a crop is. */
    public enum Stage {
        SPROUT("Sprouting", 0.0),
        GROWING("Growing", 0.35),
        RIPE("Ripe", 1.0);

        private final String label;
        private final double at;

        Stage(String label, double at) {
            this.label = label;
            this.at = at;
        }

        public String label() { return label; }

        /** How far through its growth this stage begins. */
        public double at() { return at; }
    }

    /** Real hours from planting to ripe, for an ordinary crop. */
    public static final double GROW_HOURS = 5;

    /** How many of the seed a ripe crop yields. */
    public static final int YIELD = 4;

    /** One planted crop. */
    public static final class Crop {
        private final long id;
        private final String seed;
        private final double x, y, z;
        private final String plantedBy;
        private double hours;

        Crop(long id, String seed, double x, double y, double z, String plantedBy,
             double hours) {
            this.id = id;
            this.seed = seed;
            this.x = x;
            this.y = y;
            this.z = z;
            this.plantedBy = plantedBy;
            this.hours = hours;
        }

        public long id() { return id; }

        public String seed() { return seed; }

        public double x() { return x; }

        public double y() { return y; }

        public double z() { return z; }

        public String plantedBy() { return plantedBy; }

        /** Real hours since it went in. */
        public double hours() { return hours; }

        /** How far through its growth it is, {@code 0}–{@code 1}. */
        public double progress() { return Math.min(1, hours / GROW_HOURS); }

        public Stage stage() {
            double p = progress();
            if (p >= Stage.RIPE.at()) return Stage.RIPE;
            if (p >= Stage.GROWING.at()) return Stage.GROWING;
            return Stage.SPROUT;
        }

        public boolean ripe() { return stage() == Stage.RIPE; }

        /** How tall it stands right now, in metres — what the mesher draws. */
        public double height() { return 0.12 + progress() * 0.75; }

        void age(double addHours) {
            if (addHours > 0) hours += addHours;
        }

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("s", seed);
            m.put("x", x);
            m.put("y", y);
            m.put("z", z);
            m.put("h", hours);
            if (plantedBy != null) m.put("by", plantedBy);
            return m;
        }

        static Crop fromMap(Map<String, Object> m) {
            return new Crop(WatchJson.big(m, "id", 0), WatchJson.str(m, "s", "grass_seed"),
                    WatchJson.num(m, "x", 0), WatchJson.num(m, "y", 0),
                    WatchJson.num(m, "z", 0), WatchJson.str(m, "by", null),
                    WatchJson.num(m, "h", 0));
        }

        @Override public String toString() {
            return stage().label() + " " + Forage.nameOf(seed);
        }
    }

    /** Which seed grows which tree; anything not here is a crop. */
    private static final Map<String, TreeSpecies> TREE_SEEDS = treeSeeds();

    private final Map<Long, Crop> crops = new LinkedHashMap<>();
    private long nextId = 1;

    /** The tree a seed grows into, or {@code null} when it is a crop. */
    public static TreeSpecies treeFor(String seedKey) {
        return TREE_SEEDS.get(seedKey);
    }

    /** The seed a tree species drops, or {@code null}. */
    public static String seedFor(TreeSpecies species) {
        for (Map.Entry<String, TreeSpecies> e : TREE_SEEDS.entrySet()) {
            if (e.getValue() == species) return e.getKey();
        }
        return null;
    }

    /** Whether a forage key can be planted at all. */
    public static boolean plantable(String key) {
        Forage.Item item = Forage.byKey(key);
        return item != null && item.kind() == Forage.Kind.SEED;
    }

    /** Plant a crop. Tree seeds do not come here — see {@link #treeFor}. */
    public Crop plant(String seed, double x, double y, double z, String plantedBy) {
        Crop crop = new Crop(nextId++, seed, x, y, z, plantedBy, 0);
        crops.put(crop.id(), crop);
        return crop;
    }

    public Crop byId(long id) { return crops.get(id); }

    public List<Crop> all() { return List.copyOf(crops.values()); }

    public int size() { return crops.size(); }

    /** Every crop within a radius of a point. */
    public List<Crop> near(double x, double y, double radius) {
        List<Crop> out = new ArrayList<>();
        double r2 = radius * radius;
        for (Crop crop : crops.values()) {
            double dx = crop.x() - x, dy = crop.y() - y;
            if (dx * dx + dy * dy <= r2) out.add(crop);
        }
        return out;
    }

    /**
     * Age every crop by real hours.
     *
     * @return the crops that became ripe in this call, for the notification
     */
    public List<Crop> advance(double hours) {
        List<Crop> ripened = new ArrayList<>();
        if (hours <= 0) return ripened;
        for (Crop crop : crops.values()) {
            boolean was = crop.ripe();
            crop.age(hours);
            if (!was && crop.ripe()) ripened.add(crop);
        }
        return ripened;
    }

    /**
     * Pull a ripe crop up.
     *
     * @return what went into the satchel, or {@code null} when it was not ready
     */
    public String harvest(long id, Satchel into) {
        Crop crop = crops.get(id);
        if (crop == null || !crop.ripe()) return null;
        crops.remove(id);
        if (into != null) into.add(crop.seed(), YIELD);
        return crop.seed();
    }

    // --- persistence --------------------------------------------------------------

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        List<Object> rows = new ArrayList<>();
        for (Crop crop : crops.values()) rows.add(crop.toMap());
        m.put("next", nextId);
        m.put("crops", rows);
        return m;
    }

    public void load(Map<String, Object> m) {
        crops.clear();
        nextId = Math.max(1, WatchJson.big(m, "next", 1));
        for (Map<String, Object> row : WatchJson.objects(m, "crops")) {
            Crop crop = Crop.fromMap(row);
            crops.put(crop.id(), crop);
            nextId = Math.max(nextId, crop.id() + 1);
        }
    }

    private static Map<String, TreeSpecies> treeSeeds() {
        Map<String, TreeSpecies> map = new LinkedHashMap<>();
        map.put("acorn", TreeSpecies.OAK);
        map.put("beechnut", TreeSpecies.BEECH);
        map.put("pine_seed", TreeSpecies.PINE);
        map.put("birch_seed", TreeSpecies.BIRCH);
        map.put("redwood_cone", TreeSpecies.REDWOOD);
        map.put("palm_seed", TreeSpecies.PALM);
        map.put("bamboo_seed", TreeSpecies.BAMBOO);
        map.put("cactus_seed", TreeSpecies.SAGUARO);
        map.put("kapok_seed", TreeSpecies.KAPOK);
        map.put("amethyst_seed", TreeSpecies.AMETHYST);
        map.put("spore_pod", TreeSpecies.GLOWCAP);
        map.put("samara", TreeSpecies.MAPLE);
        map.put("catkin", TreeSpecies.ASPEN);
        map.put("dawn_cone", TreeSpecies.CEDAR);
        return Map.copyOf(map);
    }
}
