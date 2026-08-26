package com.larsons.engine.watch.world;

import com.larsons.engine.watch.WatchJson;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One tree, standing somewhere, at some point in its life.
 *
 * <p><b>The same type for a wild tree and a planted one</b>, because the mesher
 * should not have to care and the difference is one field. A wild tree is
 * derived from its position every time the chunk holding it is built, has no
 * {@link #id()}, and never changes; a planted one lives in the game state, has
 * an id the server hands out, accumulates {@link #grownHours()} as real time
 * passes, and moves up through {@link TreeSpecies.Stage} as it does.
 *
 * <p><b>Growth is in real hours, not in ticks.</b> The world's clock is the
 * wall clock ({@code WatchClock}), so a tree planted before bed is measurably
 * taller in the morning whether or not anybody was logged in. That is the whole
 * appeal of planting one, and it only works if the elapsed time is read from a
 * timestamp rather than counted by a running simulation.
 */
public final class TreeInstance {

    private final long id;
    private final TreeSpecies species;
    private final double x, y, z;
    private final double yaw;
    private final TreeGenome genome;
    private final String plantedBy;

    private TreeSpecies.Stage stage;
    private double grownHours;

    public TreeInstance(long id, TreeSpecies species, double x, double y, double z,
                        double yaw, TreeGenome genome, TreeSpecies.Stage stage,
                        double grownHours, String plantedBy) {
        this.id = id;
        this.species = species;
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.genome = genome;
        this.stage = stage;
        this.grownHours = grownHours;
        this.plantedBy = plantedBy;
    }

    /**
     * A wild tree: no id, no owner, and a stage decided by its own position so
     * a forest has seedlings and ancients in it without anybody planting one.
     */
    public static TreeInstance wild(TreeSpecies species, double x, double y, double z,
                                    long positionHash) {
        double roll = ((positionHash >>> 11) * 0x1.0p-53);
        TreeSpecies.Stage stage;
        if (roll < 0.06) stage = TreeSpecies.Stage.SEEDLING;
        else if (roll < 0.18) stage = TreeSpecies.Stage.SAPLING;
        else if (roll < 0.38) stage = TreeSpecies.Stage.YOUNG;
        else if (roll < 0.92) stage = TreeSpecies.Stage.MATURE;
        else stage = TreeSpecies.Stage.ANCIENT;
        double yaw = ((positionHash >>> 5) & 0x3FF) / 1024.0 * Math.PI * 2;
        return new TreeInstance(0, species, x, y, z, yaw,
                TreeGenome.wild(positionHash), stage, 0, null);
    }

    /** A tree just put in the ground by a player. */
    public static TreeInstance planted(long id, TreeSpecies species, double x, double y,
                                       double z, TreeGenome genome, String plantedBy) {
        double yaw = (Double.hashCode(x * 31 + y) & 0x3FF) / 1024.0 * Math.PI * 2;
        return new TreeInstance(id, species, x, y, z, yaw, genome,
                TreeSpecies.Stage.SEEDLING, 0, plantedBy);
    }

    /** Server-assigned id, or {@code 0} for a wild tree nobody planted. */
    public long id() { return id; }

    public TreeSpecies species() { return species; }

    public double x() { return x; }

    public double y() { return y; }

    /** The height of the ground the trunk stands on, in metres. */
    public double z() { return z; }

    /** Which way it faces, so two trees of a species are not stamped copies. */
    public double yaw() { return yaw; }

    public TreeGenome genome() { return genome; }

    public TreeSpecies.Stage stage() { return stage; }

    /** Real hours this tree has spent in its current stage. */
    public double grownHours() { return grownHours; }

    /** Who planted it, or {@code null} when it grew on its own. */
    public String plantedBy() { return plantedBy; }

    /** Whether this tree was planted rather than generated. */
    public boolean cultivated() { return id != 0; }

    /** Trunk height right now, in metres. */
    public double height() { return species.heightAt(stage); }

    /** Crown radius right now, in metres, after the genome's own spread. */
    public double canopy() { return species.canopyAt(stage) * genome.canopyScale(); }

    /** Whether this tree is old enough to be crossed with another. */
    public boolean canPollinate() { return stage.canPollinate(); }

    /** Whether it currently carries fruit worth picking. */
    public boolean fruiting() {
        return species.fruit() != null && stage.canPollinate()
                && genome.fruitfulness() > 0.25;
    }

    /**
     * Age this tree by {@code hours} of real time, advancing it through as many
     * stages as that covers.
     *
     * @return {@code true} if it reached a new stage
     */
    public boolean advance(double hours) {
        if (hours <= 0 || stage == TreeSpecies.Stage.ANCIENT) return false;
        grownHours += hours;
        boolean grew = false;
        while (stage != TreeSpecies.Stage.ANCIENT) {
            double needed = species.growthHours() / genome.growthRate();
            if (grownHours < needed) break;
            grownHours -= needed;
            stage = stage.next();
            grew = true;
        }
        return grew;
    }

    /** How far through its current stage this tree is, {@code 0}–{@code 1}. */
    public double stageProgress() {
        if (stage == TreeSpecies.Stage.ANCIENT) return 1;
        double needed = species.growthHours() / genome.growthRate();
        return needed <= 0 ? 1 : Math.min(1, grownHours / needed);
    }

    // --- persistence --------------------------------------------------------------

    /** This tree as a JSON object — the shape the save and the wire both use. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("sp", species.key());
        m.put("x", x);
        m.put("y", y);
        m.put("z", z);
        m.put("g", genome.packed());
        m.put("st", stage.name());
        m.put("h", grownHours);
        if (plantedBy != null) m.put("by", plantedBy);
        return m;
    }

    /** The inverse of {@link #toMap()}, tolerating anything missing. */
    public static TreeInstance fromMap(Map<String, Object> m) {
        if (m == null) return null;
        TreeSpecies species = TreeSpecies.of(WatchJson.str(m, "sp", ""), TreeSpecies.OAK);
        double x = WatchJson.num(m, "x", 0);
        double y = WatchJson.num(m, "y", 0);
        double z = WatchJson.num(m, "z", 0);
        long id = WatchJson.big(m, "id", 0);
        TreeGenome genome = TreeGenome.unpack(
                WatchJson.integer(m, "g", TreeGenome.average().packed()));
        TreeSpecies.Stage stage = TreeSpecies.Stage.of(WatchJson.str(m, "st", ""),
                TreeSpecies.Stage.SEEDLING);
        double hours = WatchJson.num(m, "h", 0);
        String by = m.get("by") instanceof String s ? s : null;
        double yaw = (Double.hashCode(x * 31 + y) & 0x3FF) / 1024.0 * Math.PI * 2;
        return new TreeInstance(id, species, x, y, z, yaw, genome, stage, hours, by);
    }

    @Override public String toString() {
        return stage.label() + " " + species.displayName()
                + " at (" + Math.round(x) + ", " + Math.round(y) + ")";
    }
}
