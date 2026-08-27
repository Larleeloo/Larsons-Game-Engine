package com.larsons.engine.watch.render;

import com.larsons.engine.watch.world.Flora;
import com.larsons.engine.watch.world.GrassField;
import com.larsons.engine.watch.world.TreeInstance;
import com.larsons.engine.watch.world.TreeSpecies;
import com.larsons.engine.watch.world.WatchBiome;
import com.larsons.engine.watch.world.WatchChunk;
import com.larsons.engine.watch.world.WatchMaterial;
import com.larsons.engine.watch.world.WatchMaterials;

import java.util.List;

/**
 * Trees, bushes, boulders and grass, turned into triangles.
 *
 * <h2>What a tree costs</h2>
 *
 * <p>Between thirty and eighty triangles, depending on its {@link
 * TreeSpecies.Form} and how grown it is. That is the budget the whole look was
 * chosen around: a crown is two or three octahedra rather than a mesh, a trunk
 * is a five-sided prism rather than a cylinder, and a conifer is a stack of
 * six-sided cones. A forest of four hundred trees inside the view is therefore
 * something like twenty thousand triangles, which a GPU does not notice and a
 * software rasteriser can still be honest about.
 *
 * <h2>Growth is geometry, not a scale factor</h2>
 *
 * <p>A seedling is not a mature tree drawn small. {@link TreeSpecies.Stage}
 * gives both a size <em>and</em> a number of crown layers, so a seedling is a
 * stem with one tuft on it, a sapling has a real but sparse crown, and only a
 * mature tree has the full set. Watching something you planted fill out is the
 * entire reward for planting it, and a uniform scale would not give it.
 */
public final class FloraMesher {

    /** Sides on a trunk. Five reads as round and costs ten triangles. */
    private static final int TRUNK_SIDES = 5;

    /** Sides on a conifer's tier. */
    private static final int TIER_SIDES = 6;

    private FloraMesher() {}

    /**
     * Everything solid growing on a chunk: its trees, bushes and boulders, in
     * one mesh.
     *
     * <p>One mesh rather than one per plant, because a draw call per tree is
     * how a forest becomes four hundred draw calls — the same argument the
     * block renderer's section batching makes, at the scale this game works at.
     */
    public static Mesh flora(WatchChunk chunk, int lod) {
        double ox = chunk.originX(), oy = chunk.originY();
        Mesh.Builder mesh = Mesh.builder(ox, oy, 0, false, chunk.meshRevision());
        // Past the second level of detail a tree is a few pixels tall; it keeps
        // its trunk and its crown and loses the detail nobody can resolve.
        boolean detailed = lod <= 1;
        for (TreeInstance tree : chunk.trees()) {
            tree(mesh, tree, ox, oy, detailed);
        }
        if (lod <= 1) {
            for (Flora.Bush bush : chunk.bushes()) bush(mesh, bush, ox, oy);
        }
        for (Flora.Rock rock : chunk.rocks()) rock(mesh, rock, ox, oy);
        return mesh.build();
    }

    /**
     * One tree, in a mesh whose origin is {@code (ox, oy)} — used both by the
     * chunk mesher above and by the game when a planted tree grows and only
     * that one tree has to be rebuilt.
     */
    public static void tree(Mesh.Builder mesh, TreeInstance tree, double ox, double oy,
                            boolean detailed) {
        TreeSpecies species = tree.species();
        double x = tree.x() - ox, y = tree.y() - oy, z = tree.z();
        double height = tree.height();
        double canopy = tree.canopy();
        double trunkR = species.trunkRadius() * tree.stage().scale();
        int layers = Math.min(species.canopyLayers(), tree.stage().crownLayers() + 1);
        double yaw = tree.yaw();

        float[] woodUv = new float[4];
        float[] leafUv = new float[4];
        WatchMaterials.uv(species.wood(), woodUv);
        WatchMaterials.uv(species.foliage(), leafUv);
        int wood = WatchMaterials.shade(species.wood());
        double[] tintScale = new double[3];
        tree.genome().tint(tintScale);
        int leaf = TerrainMesher.tint(WatchMaterials.shade(species.foliage()), tintScale, 1);

        int sides = detailed ? TRUNK_SIDES : 3;
        switch (species.form()) {
            case CONIFER -> {
                Shapes.prism(mesh, x, y, z, z + height, trunkR, trunkR * 0.35,
                        sides, yaw, woodUv, wood, false);
                for (int i = 0; i < Math.max(1, layers); i++) {
                    double t = i / (double) Math.max(1, layers);
                    double base = z + height * (0.25 + 0.62 * t);
                    double tierR = canopy * (1 - t * 0.72);
                    Shapes.cone(mesh, x, y, base, base + height * 0.34, tierR,
                            detailed ? TIER_SIDES : 4, yaw, leafUv, leaf);
                }
            }
            case BROADLEAF -> {
                Shapes.prism(mesh, x, y, z, z + height * 0.62, trunkR, trunkR * 0.55,
                        sides, yaw, woodUv, wood, false);
                crown(mesh, x, y, z + height * 0.72, canopy, canopy * 0.72, layers, yaw,
                        leafUv, leaf);
            }
            case WEEPING -> {
                Shapes.prism(mesh, x, y, z, z + height * 0.6, trunkR, trunkR * 0.5,
                        sides, yaw, woodUv, wood, false);
                crown(mesh, x, y, z + height * 0.7, canopy, canopy * 0.55, layers, yaw,
                        leafUv, leaf);
                // The curtain: clusters hung below the crown's own edge.
                for (int i = 0; i < Math.max(2, layers + 1); i++) {
                    double a = yaw + i * Math.PI * 2 / Math.max(2, layers + 1);
                    Shapes.blob(mesh, x + Math.cos(a) * canopy * 0.8,
                            y + Math.sin(a) * canopy * 0.8, z + height * 0.42,
                            canopy * 0.26, canopy * 0.26, height * 0.24, leafUv, leaf);
                }
            }
            case PALM -> {
                Shapes.prism(mesh, x, y, z, z + height, trunkR, trunkR * 0.7,
                        sides, yaw, woodUv, wood, false);
                // Fronds reach well past the crown and hang below the head, in
                // two rings at different heights. A palm read from a distance
                // is entirely its silhouette, and a ring of flat slabs at one
                // height reads as a parasol rather than as a palm.
                int fronds = detailed ? 8 : 5;
                for (int i = 0; i < fronds; i++) {
                    double a = yaw + i * Math.PI * 2 / fronds;
                    boolean droops = i % 2 == 1;
                    double reach = canopy * (droops ? 1.15 : 0.85);
                    double fx = x + Math.cos(a) * reach * 0.6;
                    double fy = y + Math.sin(a) * reach * 0.6;
                    double fz = z + height - (droops ? canopy * 0.55 : canopy * 0.12);
                    Shapes.blob(mesh, fx, fy, fz,
                            Math.abs(Math.cos(a)) * reach * 0.9 + canopy * 0.22,
                            Math.abs(Math.sin(a)) * reach * 0.9 + canopy * 0.22,
                            canopy * 0.14, leafUv, leaf);
                }
                // The head itself, so the fronds have something to come out of.
                Shapes.blob(mesh, x, y, z + height, canopy * 0.28, canopy * 0.28,
                        canopy * 0.2, leafUv, leaf);
            }
            case EMERGENT -> {
                Shapes.prism(mesh, x, y, z, z + height * 0.84, trunkR, trunkR * 0.42,
                        sides, yaw, woodUv, wood, false);
                crown(mesh, x, y, z + height * 0.94, canopy, canopy * 0.42, layers + 1, yaw,
                        leafUv, leaf);
            }
            case UMBRELLA -> {
                Shapes.prism(mesh, x, y, z, z + height * 0.7, trunkR, trunkR * 0.4,
                        sides, yaw, woodUv, wood, false);
                crown(mesh, x, y, z + height * 0.82, canopy, canopy * 0.3, layers + 1, yaw,
                        leafUv, leaf);
            }
            case BOTTLE -> {
                Shapes.prism(mesh, x, y, z, z + height * 0.66, trunkR, trunkR * 0.28,
                        detailed ? 7 : 4, yaw, woodUv, wood, false);
                crown(mesh, x, y, z + height * 0.78, canopy * 0.8, canopy * 0.34, layers, yaw,
                        leafUv, leaf);
            }
            case STILT -> {
                int legs = detailed ? 5 : 3;
                for (int i = 0; i < legs; i++) {
                    double a = yaw + i * Math.PI * 2 / legs;
                    Shapes.prism(mesh, x + Math.cos(a) * trunkR * 2.2,
                            y + Math.sin(a) * trunkR * 2.2, z - 0.6,
                            z + height * 0.3, trunkR * 0.34, trunkR * 0.22,
                            3, yaw, woodUv, wood, false);
                }
                Shapes.prism(mesh, x, y, z + height * 0.28, z + height * 0.66,
                        trunkR, trunkR * 0.6, sides, yaw, woodUv, wood, false);
                crown(mesh, x, y, z + height * 0.78, canopy, canopy * 0.5, layers, yaw,
                        leafUv, leaf);
            }
            case COLUMN -> {
                Shapes.prism(mesh, x, y, z, z + height * 0.9, trunkR, trunkR * 0.3,
                        detailed ? 7 : 4, yaw, woodUv, wood, false);
                for (int i = 0; i < Math.max(1, layers); i++) {
                    double t = i / (double) Math.max(1, layers);
                    double base = z + height * (0.6 + 0.32 * t);
                    Shapes.cone(mesh, x, y, base, base + height * 0.18,
                            canopy * (1 - t * 0.6), detailed ? TIER_SIDES : 4, yaw,
                            leafUv, leaf);
                }
            }
            case CANE -> {
                // A clump, not a stem: bamboo is read by the number of canes
                // and the leaves between them, and two bare poles read as a
                // fence post. The canes lean out from the clump's centre.
                int canes = detailed ? 8 : 4;
                for (int i = 0; i < canes; i++) {
                    double a = yaw + i * 2.399; // golden angle: no two line up
                    double spread = canopy * (0.35 + 0.65 * ((i * 7) % 5) / 5.0);
                    double cxx = x + Math.cos(a) * spread;
                    double cyy = y + Math.sin(a) * spread;
                    double ch = height * (0.62 + 0.38 * ((i * 37) % 11) / 11.0);
                    Shapes.prism(mesh, cxx, cyy, z, z + ch, trunkR, trunkR * 0.7,
                            4, a, woodUv, wood, true);
                    if (detailed) {
                        // Two tufts up the cane, not one on the top.
                        for (int t = 1; t <= 2; t++) {
                            double lz = z + ch * (0.55 + 0.35 * t / 2.0);
                            Shapes.blob(mesh, cxx + Math.cos(a) * canopy * 0.5,
                                    cyy + Math.sin(a) * canopy * 0.5, lz,
                                    canopy * 0.85, canopy * 0.85, ch * 0.06,
                                    leafUv, leaf);
                        }
                    }
                }
            }
            case CACTUS -> {
                Shapes.prism(mesh, x, y, z, z + height, trunkR, trunkR * 0.85,
                        detailed ? 8 : 5, yaw, leafUv, leaf, true);
                if (layers > 1) {
                    // An arm is an elbow: out from the trunk, then straight up.
                    // A bare vertical post beside the trunk is a fence, and the
                    // elbow is the whole reason a saguaro is recognisable.
                    for (int i = 0; i < 2; i++) {
                        double a = yaw + i * Math.PI + 0.2;
                        double reach = canopy * 1.1;
                        double armX = x + Math.cos(a) * reach;
                        double armY = y + Math.sin(a) * reach;
                        double elbow = z + height * (0.34 + 0.14 * i);
                        Shapes.box(mesh, (x + armX) / 2, (y + armY) / 2, elbow,
                                reach / 2 + trunkR * 0.4, trunkR * 0.45, trunkR * 0.45,
                                a, leafUv, leaf);
                        Shapes.prism(mesh, armX, armY, elbow,
                                z + height * (0.76 + 0.1 * i), trunkR * 0.5, trunkR * 0.42,
                                detailed ? 6 : 4, a, leafUv, leaf, true);
                    }
                }
            }
            case MUSHROOM -> {
                Shapes.prism(mesh, x, y, z, z + height * 0.72, trunkR, trunkR * 1.1,
                        detailed ? 7 : 4, yaw, woodUv, wood, false);
                Shapes.cone(mesh, x, y, z + height * 0.66, z + height * 1.05,
                        canopy, detailed ? 8 : 5, yaw, leafUv, leaf);
            }
            case CRYSTAL -> {
                for (int i = 0; i < Math.max(2, layers + 1); i++) {
                    double t = i / (double) Math.max(2, layers + 1);
                    double base = z + height * t * 0.75;
                    Shapes.cone(mesh, x + Math.cos(yaw + i) * trunkR * 0.6,
                            y + Math.sin(yaw + i) * trunkR * 0.6, base,
                            base + height * (0.45 - t * 0.2),
                            canopy * (0.6 - t * 0.3) + trunkR,
                            detailed ? 5 : 3, yaw + i, leafUv, leaf);
                }
            }
        }

        // Fruit, when the tree is carrying: a few berries at the crown's edge.
        if (detailed && tree.fruiting()) {
            float[] fruitUv = new float[4];
            WatchMaterials.uv(WatchMaterial.BERRY, fruitUv);
            int fruitColour = WatchMaterials.shade(WatchMaterial.BERRY);
            int count = 2 + (int) (tree.genome().fruitfulness() * 4);
            for (int i = 0; i < count; i++) {
                double a = yaw + i * Math.PI * 2 / count;
                Shapes.blob(mesh, x + Math.cos(a) * canopy * 0.72,
                        y + Math.sin(a) * canopy * 0.72,
                        z + height * 0.7, 0.16, 0.16, 0.16, fruitUv, fruitColour);
            }
        }
    }

    /** A crown of {@code layers} overlapping clusters around a point. */
    private static void crown(Mesh.Builder mesh, double x, double y, double z,
                              double radius, double thickness, int layers, double yaw,
                              float[] uv, int argb) {
        int clusters = Math.max(1, layers);
        Shapes.blob(mesh, x, y, z, radius * 0.78, radius * 0.78, thickness, uv, argb);
        for (int i = 1; i < clusters; i++) {
            double a = yaw + i * 2.399; // the golden angle: no two clusters line up
            double r = radius * 0.55;
            Shapes.blob(mesh, x + Math.cos(a) * r, y + Math.sin(a) * r,
                    z - thickness * 0.25 + (i % 2) * thickness * 0.4,
                    radius * 0.52, radius * 0.52, thickness * 0.72, uv, argb);
        }
    }

    private static void bush(Mesh.Builder mesh, Flora.Bush bush, double ox, double oy) {
        float[] uv = new float[4];
        WatchMaterials.uv(bush.foliage(), uv);
        int leaf = WatchMaterials.shade(bush.foliage());
        double x = bush.x() - ox, y = bush.y() - oy, z = bush.z();
        double r = bush.radius();
        Shapes.blob(mesh, x, y, z + r * 0.7, r, r, r * 0.8, uv, leaf);
        Shapes.blob(mesh, x + r * 0.5, y - r * 0.35, z + r * 0.5,
                r * 0.6, r * 0.6, r * 0.5, uv, leaf);
        if (bush.ripe()) {
            float[] berryUv = new float[4];
            WatchMaterials.uv(WatchMaterial.BERRY, berryUv);
            int berry = WatchMaterials.shade(WatchMaterial.BERRY);
            for (int i = 0; i < 4; i++) {
                double a = i * Math.PI / 2 + 0.4;
                Shapes.blob(mesh, x + Math.cos(a) * r * 0.8, y + Math.sin(a) * r * 0.8,
                        z + r * 0.9, 0.11, 0.11, 0.11, berryUv, berry);
            }
        }
    }

    private static void rock(Mesh.Builder mesh, Flora.Rock rock, double ox, double oy) {
        float[] uv = new float[4];
        WatchMaterials.uv(rock.material(), uv);
        int argb = WatchMaterials.shade(rock.material());
        double r = rock.radius();
        // Sunk a little, so a boulder sits in the ground rather than on it.
        Shapes.blob(mesh, rock.x() - ox, rock.y() - oy, rock.z() + r * 0.45,
                r, r * 0.82, r * 0.7, uv, argb);
    }

    /**
     * The grass on a chunk.
     *
     * <p><b>Built only for the chunks nearest the camera</b> — see
     * {@link GrassField}. The lean is baked in from the wind field at the moment
     * of building, and the streamer rebuilds these few meshes on a slow cadence,
     * which is how a meadow moves without costing anything per frame.
     *
     * @param seconds where the wind field is sampled; advancing it and
     *                rebuilding is what makes the grass sway
     */
    public static Mesh grass(WatchChunk chunk, GrassField field, double seconds) {
        double ox = chunk.originX(), oy = chunk.originY();
        Mesh.Builder mesh = Mesh.builder(ox, oy, 0, false, chunk.meshRevision());
        float[] uv = new float[4];
        double[] wind = new double[2];
        double[] position = new double[2];

        int firstX = (int) Math.floor(ox / GrassField.SPACING);
        int firstY = (int) Math.floor(oy / GrassField.SPACING);
        int lastX = (int) Math.ceil((ox + WatchChunk.SIZE) / GrassField.SPACING);
        int lastY = (int) Math.ceil((oy + WatchChunk.SIZE) / GrassField.SPACING);

        for (int cy = firstY; cy < lastY; cy++) {
            for (int cx = firstX; cx < lastX; cx++) {
                field.positionOf(cx, cy, position);
                double wx = position[0], wy = position[1];
                if (wx < ox || wx >= ox + WatchChunk.SIZE
                        || wy < oy || wy >= oy + WatchChunk.SIZE) continue;

                WatchBiome biome = chunk.biomeAtWorld(wx, wy);
                double slope = chunk.slopeAtWorld(wx, wy);
                double z = chunk.groundAt(wx, wy);
                double water = Math.max(0, -z);
                if (!field.grows(wx, wy, biome, slope, water)) continue;

                double length = field.lengthAt(wx, wy, biome);
                field.windAt(wx, wy, seconds, length, wind);
                WatchMaterial material = chunk.surfaceAtWorld(wx, wy);
                WatchMaterials.uv(material, uv);
                // Grass takes its colour from the ground it grows out of,
                // lifted a little and jittered per blade, so a meadow is not a
                // single flat green and a tundra's is not the same green at all.
                double tint = 1.12 + field.tintOf(wx, wy) * 0.16;
                int argb = TerrainMesher.shade(WatchMaterials.shade(material), tint);
                double yaw = (wx * 12.9898 + wy * 78.233) % Math.PI;
                Shapes.blade(mesh, wx - ox, wy - oy, z, length,
                        Math.max(0.05, length * 0.16), yaw, wind[0], wind[1], uv, argb);
            }
        }
        return mesh.build();
    }

    /** Everything a chunk's flora would cost, for the streamer's memory budget. */
    public static int estimateTriangles(List<TreeInstance> trees) {
        return trees.size() * 48;
    }
}
