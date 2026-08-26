package com.larsons.engine.watch.world;

import com.larsons.engine.watch.WatchJson;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Every tree anybody planted, and the crossing bench.
 *
 * <p>Wild trees are not in here. They are a function of where they stand
 * ({@link Flora}) and there are millions of them; what this holds is the few
 * hundred a party has actually put in the ground, which is a number that fits
 * in a save file and has to, because a planted tree keeps growing while nobody
 * is looking.
 *
 * <h2>Growing</h2>
 *
 * <p>{@link #advance} takes <b>real hours</b> — the difference between the
 * world clock now and when the grove was last ticked. So a session that resumes
 * a day later advances every tree by a day in one call, which is the behaviour
 * a player expects from something they planted and went to bed.
 *
 * <h2>Crossing</h2>
 *
 * <p>{@link #pollinate} is the whole of the breeding game. Two trees, both
 * {@link TreeSpecies.Stage#MATURE} or better, within {@value #POLLEN_REACH}
 * metres of each other, produce a seed:
 *
 * <ul>
 *   <li>the seed's <b>genome</b> is the two parents crossed
 *       ({@link TreeGenome#cross}) — each trait from one parent or the other,
 *       plus a little drift, so a line can be improved past both of them;</li>
 *   <li>the seed's <b>species</b> is the parents' hybrid when the pair has one
 *       ({@link TreeSpecies#hybrid}), and otherwise one of the parents. Two
 *       pines make a pine — a better pine, if you chose your parents — and a
 *       pine and a birch make something that does not grow anywhere.</li>
 * </ul>
 */
public final class Grove {

    /** How close two trees have to be to cross, in metres. */
    public static final double POLLEN_REACH = 9;

    /** What a cross produces: a seed with a species and a parentage. */
    public record Cross(TreeSpecies species, TreeGenome genome, boolean hybrid,
                        TreeSpecies parentA, TreeSpecies parentB) {

        /** What the notification says when this lands in a player's satchel. */
        public String describe() {
            return hybrid
                    ? parentA.displayName() + " × " + parentB.displayName()
                            + " — a " + species.displayName() + " seed!"
                    : "A " + species.displayName() + " seed (" + genome + ")";
        }
    }

    private final Map<Long, TreeInstance> planted = new LinkedHashMap<>();
    private long nextId = 1;

    /** Put a tree in the ground. */
    public TreeInstance plant(TreeSpecies species, double x, double y, double z,
                              TreeGenome genome, String plantedBy) {
        TreeInstance tree = TreeInstance.planted(nextId++, species, x, y, z,
                genome == null ? TreeGenome.average() : genome, plantedBy);
        planted.put(tree.id(), tree);
        return tree;
    }

    /** The tree with this id, or {@code null}. */
    public TreeInstance byId(long id) { return planted.get(id); }

    /** Every planted tree, in the order they went in. */
    public List<TreeInstance> all() { return List.copyOf(planted.values()); }

    public int size() { return planted.size(); }

    /** Remove one — felled, or dug up and moved. */
    public TreeInstance remove(long id) { return planted.remove(id); }

    /** Every planted tree within {@code radius} metres of a point. */
    public List<TreeInstance> near(double x, double y, double radius) {
        List<TreeInstance> out = new ArrayList<>();
        double r2 = radius * radius;
        for (TreeInstance t : planted.values()) {
            double dx = t.x() - x, dy = t.y() - y;
            if (dx * dx + dy * dy <= r2) out.add(t);
        }
        return out;
    }

    /**
     * Age every tree by {@code hours} of real time.
     *
     * @return the trees that reached a new growth stage, for the notification
     *         a player gets when the thing they planted becomes a tree
     */
    public List<TreeInstance> advance(double hours) {
        List<TreeInstance> grew = new ArrayList<>();
        if (hours <= 0) return grew;
        for (TreeInstance t : planted.values()) {
            if (t.advance(hours)) grew.add(t);
        }
        return grew;
    }

    /**
     * Cross two planted trees.
     *
     * @return the seed, or {@code null} when the pair cannot be crossed — one
     *         of them is missing, one of them is not mature, or they are too
     *         far apart to have pollinated each other
     */
    public Cross pollinate(long aId, long bId, Random rng) {
        TreeInstance a = planted.get(aId);
        TreeInstance b = planted.get(bId);
        if (a == null || b == null || a == b) return null;
        if (!a.canPollinate() || !b.canPollinate()) return null;
        double dx = a.x() - b.x(), dy = a.y() - b.y();
        if (dx * dx + dy * dy > POLLEN_REACH * POLLEN_REACH) return null;
        return crossOf(a, b, rng);
    }

    /**
     * The seed two given trees would produce, without checking that they are
     * planted here — what a test drives directly and what
     * {@link #pollinate} calls once its checks have passed.
     */
    public static Cross crossOf(TreeInstance a, TreeInstance b, Random rng) {
        TreeGenome genome = a.genome().cross(b.genome(), rng);
        TreeSpecies hybrid = TreeSpecies.hybrid(a.species(), b.species());
        if (hybrid != null) {
            return new Cross(hybrid, genome, true, a.species(), b.species());
        }
        // No hybrid for this pair: the seed is one parent's species. Chosen
        // rather than always-the-first, so crossing an oak with a maple gives
        // oaks and maples in a garden rather than only ever oaks.
        TreeSpecies species = rng.nextBoolean() ? a.species() : b.species();
        return new Cross(species, genome, false, a.species(), b.species());
    }

    // --- persistence --------------------------------------------------------------

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        List<Object> trees = new ArrayList<>();
        for (TreeInstance t : planted.values()) trees.add(t.toMap());
        m.put("next", nextId);
        m.put("trees", trees);
        return m;
    }

    /** Replace the contents of this grove with what a save or a host sent. */
    public void load(Map<String, Object> m) {
        planted.clear();
        nextId = Math.max(1, WatchJson.big(m, "next", 1));
        for (Map<String, Object> row : WatchJson.objects(m, "trees")) {
            TreeInstance tree = TreeInstance.fromMap(row);
            if (tree != null && tree.id() != 0) {
                planted.put(tree.id(), tree);
                nextId = Math.max(nextId, tree.id() + 1);
            }
        }
    }
}
