package com.larsons.engine.watch.render;

import com.larsons.engine.watch.Forage;
import com.larsons.engine.watch.light.LightKind;
import com.larsons.engine.watch.world.WatchMaterial;
import com.larsons.engine.watch.world.WatchMaterials;

import java.util.HashMap;
import java.util.Map;

/**
 * A solid for every single thing you can be carrying — <b>so that "an item" is
 * a thing in the world rather than a word in a list.</b>
 *
 * <h2>Why an item needs a model</h2>
 *
 * <p>Everything in this game that you can pick up existed only as a key in a
 * satchel and a line of text. That is enough for the bag and not enough for
 * anywhere else: a feeder with suet in it drew a generic red blob whatever it
 * had been filled with, a thing lying on the ground could not exist at all, and
 * the hands this class was written alongside had nothing to hold.
 *
 * <h2>Per item, not per kind</h2>
 *
 * <p>The first version of this file drew <em>one model per {@link Forage.Kind}</em>
 * and tinted it from the item's key: every berry was the same three spheres,
 * every seed the same pebble, every material the same stick. That was a
 * reasonable trade while the only place an item was drawn was on a feeder
 * twenty metres away — and it stopped being reasonable the moment items started
 * lying on the ground at your feet, being held in your hand, and appearing as
 * a picture beside their own row in the satchel. At those distances "a
 * differently coloured blob" is not a model, it is a placeholder.
 *
 * <p>So every key in {@link Forage} now has a shape of its own: an acorn has a
 * cap, a cone has scales, a beetle has legs, a bottle of nectar has a neck and
 * a stopper. They are still built out of {@link Shapes}' five primitives and
 * still cost a few dozen triangles apiece, because the budget has not changed —
 * what changed is that the triangles are spent on telling two things apart
 * rather than on drawing the same thing forty times.
 *
 * <p><b>Colour is a table now, not a hash.</b> The hash was the other half of
 * the placeholder: it guaranteed only that two keys differed, which is how a
 * blueberry came out plum-red and snowberry came out maroon. {@link #colourOf}
 * looks the item up first and falls back to the old band-and-hash for anything
 * it does not know, so an item added to {@link Forage} without a colour still
 * draws in a sensible one.
 *
 * <h2>The frame</h2>
 *
 * <p>Every model is built <b>standing on {@code z}</b> and reaching upward from
 * it, within about 0.2 m of {@code (x, y)} at {@code scale} 1. That is what lets
 * one call put a thing on the ground, on a feeder's tray, on a crop's seed head
 * and in a walker's hand without any of the four knowing what it is drawing.
 */
public final class ItemModel {

    private ItemModel() {}

    /**
     * Draw one item at a point, at a size.
     *
     * @param scale metres across, roughly — {@code 1} is a fist-sized thing
     * @param yaw   which way it is turned
     */
    public static void item(Mesh.Builder mesh, String key, double x, double y, double z,
                            double scale, double yaw) {
        Forage.Item item = Forage.byKey(key);
        Forage.Kind kind = item == null ? Forage.Kind.MATERIAL : item.kind();
        float[] uv = new float[4];
        int colour = colourOf(key, kind);

        switch (kind) {
            case BERRY -> fruit(mesh, key, x, y, z, scale, yaw, uv, colour);
            case SEED -> seed(mesh, key, x, y, z, scale, yaw, uv, colour);
            case FISH -> fish(mesh, key, x, y, z, scale, yaw, uv, colour);
            case CRITTER -> critter(mesh, key, x, y, z, scale, yaw, uv, colour);
            case MATERIAL -> material(mesh, key, x, y, z, scale, yaw, uv, colour);
            case PREPARED -> prepared(mesh, key, x, y, z, scale, yaw, uv, colour);
            case TOOL -> tool(mesh, key, x, y, z, scale, yaw, uv, colour);
        }
    }

    // --- berries and fruit ---------------------------------------------------------

    /**
     * Anything that grew on a bush or a tree.
     *
     * <p>Four shapes cover twenty-eight items, because that is genuinely how
     * many shapes there are: a cluster of small round berries, one big fruit on
     * a stalk, a pod that splits, and a mushroom. Which of the four a key gets
     * is the only thing that has to be decided here.
     */
    private static void fruit(Mesh.Builder mesh, String key, double x, double y, double z,
                              double scale, double yaw, float[] uv, int colour) {
        switch (key) {
            case "glow_spore", "star_spore" -> mushroom(mesh, x, y, z, scale, yaw, uv,
                    colour, key.equals("star_spore"));
            case "cocoa_pod", "kapok_pod", "baobab_fruit" ->
                    pod(mesh, x, y, z, scale, yaw, uv, colour);
            case "coconut" -> coconut(mesh, x, y, z, scale, yaw, uv, colour);
            case "prickly_pear", "cactus_fruit" ->
                    spinyFruit(mesh, x, y, z, scale, yaw, uv, colour);
            case "fig", "guava", "sun_pear", "amethyst_plum", "mangrove_apple",
                 "dewfruit", "nightbell" ->
                    drupe(mesh, x, y, z, scale, yaw, uv, colour, key.equals("sun_pear"));
            case "date", "moon_date" -> dates(mesh, x, y, z, scale, yaw, uv, colour);
            // Blackberry, salmonberry, thimbleberry: an aggregate of drupelets
            // rather than one skin, which is the whole visual difference
            // between a bramble and a blueberry.
            case "blackberry", "salmonberry", "thimbleberry", "cloudberry" ->
                    aggregate(mesh, x, y, z, scale, yaw, uv, colour);
            default -> berryCluster(mesh, x, y, z, scale, yaw, uv, colour);
        }
    }

    /** Three or four small round berries on a common stem, with one leaf. */
    private static void berryCluster(Mesh.Builder mesh, double x, double y, double z,
                                     double scale, double yaw, float[] uv, int colour) {
        WatchMaterials.uv(WatchMaterial.BARK, uv);
        Shapes.prism(mesh, x, y, z, z + 0.030 * scale, 0.006 * scale, 0.005 * scale,
                4, yaw, uv, 0x4A3A22, false);
        // The leaf goes on <em>under</em> the fruit, on the stem. Sitting level
        // with the berries it read as a cap and turned every hedgerow berry in
        // the game into a small mushroom.
        leaf(mesh, x, y, z + 0.024 * scale, scale * 0.75, yaw + 2.1, uv);
        WatchMaterials.uv(WatchMaterial.BERRY, uv);
        // Four, spread wide enough to stay four, and squat rather than round:
        // an octahedron comes to a point at the top, so a tight stack of them
        // fused into one spade-shaped lump instead of reading as a handful.
        for (int i = 0; i < 4; i++) {
            double a = yaw + i * Math.PI / 2 + 0.3;
            double r = 0.040 * scale;
            Shapes.blob(mesh, x + Math.cos(a) * r, y + Math.sin(a) * r,
                    z + (0.048 + 0.010 * (i % 2)) * scale,
                    0.030 * scale, 0.030 * scale, 0.023 * scale,
                    uv, shade(colour, 0.88 + 0.07 * i));
        }
    }

    /** A bramble: eight drupelets packed into a thimble, on a short stalk. */
    private static void aggregate(Mesh.Builder mesh, double x, double y, double z,
                                  double scale, double yaw, float[] uv, int colour) {
        WatchMaterials.uv(WatchMaterial.BARK, uv);
        Shapes.prism(mesh, x, y, z, z + 0.038 * scale, 0.006 * scale, 0.005 * scale,
                4, yaw, uv, 0x50402A, false);
        WatchMaterials.uv(WatchMaterial.BERRY, uv);
        for (int ring = 0; ring < 2; ring++) {
            int count = ring == 0 ? 5 : 3;
            double r = (ring == 0 ? 0.030 : 0.018) * scale;
            double lift = (ring == 0 ? 0.058 : 0.088) * scale;
            double size = (ring == 0 ? 0.021 : 0.018) * scale;
            for (int i = 0; i < count; i++) {
                double a = yaw + ring * 0.6 + i * Math.PI * 2 / count;
                Shapes.blob(mesh, x + Math.cos(a) * r, y + Math.sin(a) * r, z + lift,
                        size, size, size, uv, shade(colour, 0.88 + 0.09 * (i % 3)));
            }
        }
        Shapes.blob(mesh, x, y, z + 0.104 * scale, 0.018 * scale, 0.018 * scale,
                0.016 * scale, uv, shade(colour, 1.12));
    }

    /** One fruit with a skin, a stalk and a leaf — a fig, a plum, a pear. */
    private static void drupe(Mesh.Builder mesh, double x, double y, double z,
                              double scale, double yaw, float[] uv, int colour,
                              boolean tapered) {
        WatchMaterials.uv(WatchMaterial.LEAF, uv);
        double r = 0.055 * scale;
        double top = tapered ? r * 1.7 : r * 2.0;
        ball(mesh, x, y, z, r, top, yaw, uv, colour);
        if (tapered) {
            // A pear's neck: a second, smaller body stacked on the first.
            ball(mesh, x, y, z + top * 0.86, r * 0.60, r * 1.10, yaw, uv,
                    shade(colour, 1.06));
            top += r * 0.95;
        }
        WatchMaterials.uv(WatchMaterial.BARK, uv);
        Shapes.prism(mesh, x, y, z + top * 0.94, z + top + r * 0.5, 0.008 * scale,
                0.006 * scale, 4, yaw, uv, 0x4A3A22, true);
        leaf(mesh, x, y, z + top + r * 0.36, scale, yaw + 0.9, uv);
    }

    /**
     * A round-ish solid: a drum with a cap on each end.
     *
     * <p>Cheaper than a sphere and, more to the point, <b>rounder than an
     * octahedron</b>. {@link Shapes#blob} comes to a point at the top and the
     * bottom, and a fruit built out of one — or out of two stacked, which was
     * the first attempt — has a silhouette that is a rhombus however it is
     * proportioned. Every apple-shaped thing in the catalogue read as a cut
     * gem. Six sides and three tiers is about forty triangles and reads as
     * fruit.
     *
     * @param z      where it stands
     * @param r      how wide it is at its middle
     * @param height how tall, base to crown
     */
    private static void ball(Mesh.Builder mesh, double x, double y, double z, double r,
                             double height, double yaw, float[] uv, int colour) {
        Shapes.prism(mesh, x, y, z, z + height * 0.30, r * 0.46, r, 6, yaw, uv,
                shade(colour, 0.9), false);
        Shapes.prism(mesh, x, y, z + height * 0.30, z + height * 0.66, r, r, 6, yaw,
                uv, colour, false);
        Shapes.prism(mesh, x, y, z + height * 0.66, z + height, r, r * 0.44, 6, yaw,
                uv, shade(colour, 1.08), true);
    }

    /** Two or three dates on a stem — small, oval, and always in a bunch. */
    private static void dates(Mesh.Builder mesh, double x, double y, double z,
                              double scale, double yaw, float[] uv, int colour) {
        WatchMaterials.uv(WatchMaterial.BARK, uv);
        Shapes.prism(mesh, x, y, z + 0.02 * scale, z + 0.10 * scale, 0.006 * scale,
                0.004 * scale, 4, yaw, uv, 0x5A4428, false);
        WatchMaterials.uv(WatchMaterial.LEAF, uv);
        for (int i = 0; i < 3; i++) {
            double a = yaw + i * Math.PI * 2 / 3;
            Shapes.blob(mesh, x + Math.cos(a) * 0.022 * scale,
                    y + Math.sin(a) * 0.022 * scale, z + (0.030 + i * 0.012) * scale,
                    0.022 * scale, 0.022 * scale, 0.034 * scale, uv,
                    shade(colour, 0.9 + 0.1 * i));
        }
    }

    /** A cactus fruit: a barrel with a crown of spines round its top. */
    private static void spinyFruit(Mesh.Builder mesh, double x, double y, double z,
                                   double scale, double yaw, float[] uv, int colour) {
        WatchMaterials.uv(WatchMaterial.LEAF, uv);
        Shapes.prism(mesh, x, y, z, z + 0.10 * scale, 0.040 * scale, 0.034 * scale,
                7, yaw, uv, colour, true);
        WatchMaterials.uv(WatchMaterial.DRY_GRASS, uv);
        for (int i = 0; i < 7; i++) {
            double a = yaw + i * Math.PI * 2 / 7;
            // Long enough to be seen against the fruit: a spine you cannot make
            // out is a prickly pear that is only a barrel.
            spike(mesh, x + Math.cos(a) * 0.034 * scale,
                    y + Math.sin(a) * 0.034 * scale, z + (0.030 + 0.030 * (i % 3)) * scale,
                    0.014 * scale, 0.012 * scale, a, 0.036 * scale, uv, 0xEDE4C8);
        }
    }

    /** A woody pod hanging off its own short stalk — cocoa, kapok, baobab. */
    private static void pod(Mesh.Builder mesh, double x, double y, double z,
                            double scale, double yaw, float[] uv, int colour) {
        WatchMaterials.uv(WatchMaterial.BARK, uv);
        Shapes.blob(mesh, x, y, z + 0.055 * scale, 0.040 * scale, 0.075 * scale,
                0.052 * scale, yaw, uv, colour);
        // The ribs, which are what makes a cocoa pod a cocoa pod. Shallow: cut
        // deeper they stop being ribs and the pod becomes a crate.
        for (int i = -1; i <= 1; i += 2) {
            Shapes.box(mesh, x + Math.cos(yaw) * 0.038 * scale * i,
                    y + Math.sin(yaw) * 0.038 * scale * i, z + 0.055 * scale,
                    0.005 * scale, 0.058 * scale, 0.030 * scale, yaw, uv,
                    shade(colour, 0.8));
        }
        Shapes.prism(mesh, x, y, z + 0.100 * scale, z + 0.132 * scale, 0.008 * scale,
                0.006 * scale, 4, yaw, uv, 0x5A4428, true);
    }

    /** A coconut: a husked sphere with three eyes and a tuft of fibre. */
    private static void coconut(Mesh.Builder mesh, double x, double y, double z,
                                double scale, double yaw, float[] uv, int colour) {
        WatchMaterials.uv(WatchMaterial.BARK, uv);
        double r = 0.072 * scale;
        ball(mesh, x, y, z, r, r * 1.9, yaw, uv, colour);
        for (int i = 0; i < 3; i++) {
            double a = yaw + i * Math.PI * 2 / 3;
            Shapes.blob(mesh, x + Math.cos(a) * r * 0.55, y + Math.sin(a) * r * 0.55,
                    z + r * 1.5, r * 0.16, r * 0.16, r * 0.12, uv, shade(colour, 0.55));
        }
        WatchMaterials.uv(WatchMaterial.THATCH, uv);
        for (int i = 0; i < 4; i++) {
            double a = yaw + 0.4 + i * Math.PI / 2;
            spike(mesh, x + Math.cos(a) * r * 0.4, y + Math.sin(a) * r * 0.4,
                    z + r * 1.8, 0.032 * scale, 0.010 * scale, a, 0.016 * scale, uv,
                    shade(colour, 1.25));
        }
    }

    /** A cap, a stalk, and a ring of gills under it. */
    private static void mushroom(Mesh.Builder mesh, double x, double y, double z,
                                 double scale, double yaw, float[] uv, int colour,
                                 boolean starred) {
        WatchMaterials.uv(WatchMaterial.PALE_BARK, uv);
        Shapes.prism(mesh, x, y, z, z + 0.085 * scale, 0.016 * scale, 0.012 * scale,
                6, yaw, uv, 0xD8D0BC, false);
        WatchMaterials.uv(WatchMaterial.CAP, uv);
        // The gills: a flat disc a shade darker, so the cap reads as having an
        // underside rather than as a cone balanced on a stick.
        Shapes.prism(mesh, x, y, z + 0.082 * scale, z + 0.090 * scale, 0.052 * scale,
                0.056 * scale, 8, yaw, uv, shade(colour, 0.6), false);
        Shapes.cone(mesh, x, y, z + 0.090 * scale, z + 0.140 * scale, 0.058 * scale,
                8, yaw, uv, colour);
        if (starred) {
            // The twinkle: five pale flecks round the cap, which is the only
            // thing that tells a starcap from a glowcap at any distance.
            for (int i = 0; i < 5; i++) {
                double a = yaw + i * Math.PI * 2 / 5;
                Shapes.blob(mesh, x + Math.cos(a) * 0.030 * scale,
                        y + Math.sin(a) * 0.030 * scale, z + 0.112 * scale,
                        0.008 * scale, 0.008 * scale, 0.006 * scale, uv, 0xFFFFFF);
            }
        }
    }

    /** One leaf, lying out from a point — the thing that says "this grew". */
    private static void leaf(Mesh.Builder mesh, double x, double y, double z,
                             double scale, double yaw, float[] uv) {
        WatchMaterials.uv(WatchMaterial.LEAF, uv);
        spike(mesh, x, y, z, 0.010 * scale, 0.030 * scale, yaw, 0.055 * scale, uv,
                0x3E7A34);
    }

    /**
     * A blade that leans out along {@code angle}, with its base laid
     * <em>across</em> that direction.
     *
     * <p>{@link Shapes#blade} spreads its base along {@code (cos yaw, sin yaw)}
     * and puts its tip wherever the lean says — so a caller who passes the same
     * direction for both gets three collinear points and draws nothing at all.
     * Every leaf, spine, reed and wing in this file wants the base across the
     * lean, so the quarter turn is expressed once, here.
     *
     * @param reach how far out the tip is, in metres
     */
    private static void spike(Mesh.Builder mesh, double x, double y, double z,
                              double height, double width, double angle, double reach,
                              float[] uv, int colour) {
        Shapes.blade(mesh, x, y, z, height, width, angle + Math.PI / 2,
                Math.cos(angle) * reach, Math.sin(angle) * reach, uv, colour);
    }

    // --- seeds ----------------------------------------------------------------------

    private static void seed(Mesh.Builder mesh, String key, double x, double y, double z,
                             double scale, double yaw, float[] uv, int colour) {
        switch (key) {
            case "acorn", "beechnut" -> acorn(mesh, x, y, z, scale, yaw, uv, colour);
            case "pine_seed", "redwood_cone", "dawn_cone", "spore_pod" ->
                    pineCone(mesh, x, y, z, scale, yaw, uv, colour);
            case "samara" -> samara(mesh, x, y, z, scale, yaw, uv, colour);
            case "catkin" -> catkin(mesh, x, y, z, scale, yaw, uv, colour);
            case "sunflower_seed", "cactus_seed", "lupine_seed", "palm_seed",
                 "bamboo_seed", "kapok_seed" ->
                    seedHusk(mesh, x, y, z, scale, yaw, uv, colour,
                            key.equals("sunflower_seed"));
            case "amethyst_seed" -> crystal(mesh, x, y, z, scale * 0.55, yaw, uv, colour);
            case "birch_seed" -> samara(mesh, x, y, z, scale * 0.7, yaw, uv, colour);
            // Grass seed, millet, thistle, wild rice, sedge: a seed head on a
            // stalk, which is what you actually strip between two fingers.
            default -> seedHead(mesh, x, y, z, scale, yaw, uv, colour);
        }
    }

    /** A stalk with a fat head of grain on it. */
    private static void seedHead(Mesh.Builder mesh, double x, double y, double z,
                                 double scale, double yaw, float[] uv, int colour) {
        WatchMaterials.uv(WatchMaterial.DRY_GRASS, uv);
        Shapes.prism(mesh, x, y, z, z + 0.075 * scale, 0.007 * scale, 0.006 * scale,
                4, yaw, uv, 0xA8B060, false);
        // Ten grains rather than seven, packed rather than strung out: the head
        // is the whole of what one of these is and it was a thread of beads.
        for (int i = 0; i < 10; i++) {
            double a = yaw + i * 1.9;
            double lift = 0.070 + (i / 2) * 0.020;
            Shapes.blob(mesh, x + Math.cos(a) * 0.020 * scale,
                    y + Math.sin(a) * 0.020 * scale, z + lift * scale,
                    0.017 * scale, 0.017 * scale, 0.021 * scale, a, uv,
                    shade(colour, 0.88 + 0.04 * (i % 4)));
        }
    }

    /** A single husked seed lying on its side, striped if it is a sunflower's. */
    private static void seedHusk(Mesh.Builder mesh, double x, double y, double z,
                                 double scale, double yaw, float[] uv, int colour,
                                 boolean striped) {
        WatchMaterials.uv(WatchMaterial.DRY_GRASS, uv);
        Shapes.blob(mesh, x, y, z + 0.016 * scale, 0.020 * scale, 0.038 * scale,
                0.016 * scale, yaw, uv, colour);
        if (striped) {
            for (int i = -1; i <= 1; i += 2) {
                Shapes.box(mesh, x + Math.cos(yaw) * 0.009 * scale * i,
                        y + Math.sin(yaw) * 0.009 * scale * i, z + 0.030 * scale,
                        0.004 * scale, 0.030 * scale, 0.003 * scale, yaw, uv, 0xE8DCC0);
            }
        }
    }

    /** A nut sitting in its cap. */
    private static void acorn(Mesh.Builder mesh, double x, double y, double z,
                              double scale, double yaw, float[] uv, int colour) {
        WatchMaterials.uv(WatchMaterial.BARK, uv);
        Shapes.blob(mesh, x, y, z + 0.038 * scale, 0.026 * scale, 0.026 * scale,
                0.036 * scale, uv, colour);
        Shapes.prism(mesh, x, y, z + 0.052 * scale, z + 0.074 * scale, 0.030 * scale,
                0.022 * scale, 6, yaw, uv, shade(colour, 0.62), true);
        Shapes.prism(mesh, x, y, z + 0.074 * scale, z + 0.094 * scale, 0.006 * scale,
                0.004 * scale, 4, yaw, uv, shade(colour, 0.5), true);
    }

    /** A cone: four rings of scales stepping in toward the tip. */
    private static void pineCone(Mesh.Builder mesh, double x, double y, double z,
                                 double scale, double yaw, float[] uv, int colour) {
        WatchMaterials.uv(WatchMaterial.BARK, uv);
        Shapes.prism(mesh, x, y, z, z + 0.11 * scale, 0.012 * scale, 0.006 * scale,
                4, yaw, uv, shade(colour, 0.7), false);
        // Sixteen scales as octahedra rather than as boxes: a box is twelve
        // triangles and an octahedron is eight, and at this size the difference
        // between them is nothing and the difference in the budget is a third.
        for (int tier = 0; tier < 4; tier++) {
            double lift = (0.018 + tier * 0.024) * scale;
            double r = (0.034 - tier * 0.007) * scale;
            for (int i = 0; i < 4; i++) {
                double a = yaw + tier * 0.62 + i * Math.PI / 2;
                Shapes.blob(mesh, x + Math.cos(a) * r, y + Math.sin(a) * r, z + lift,
                        0.016 * scale, 0.011 * scale, 0.009 * scale, a, uv,
                        shade(colour, 0.86 + 0.06 * (tier % 2)));
            }
        }
    }

    /** The maple's helicopter: a seed at one end and a papery wing off it. */
    private static void samara(Mesh.Builder mesh, double x, double y, double z,
                               double scale, double yaw, float[] uv, int colour) {
        WatchMaterials.uv(WatchMaterial.BARK, uv);
        Shapes.blob(mesh, x, y, z + 0.014 * scale, 0.017 * scale, 0.015 * scale,
                0.014 * scale, uv, shade(colour, 0.75));
        WatchMaterials.uv(WatchMaterial.PALE_BARK, uv);
        // Broad at the root and cocked upward: a wing 3 mm wide is a wire, and
        // a wing lying flat is invisible from anywhere but straight above.
        spike(mesh, x, y, z + 0.012 * scale, 0.024 * scale, 0.056 * scale, yaw,
                0.100 * scale, uv, colour);
        spike(mesh, x, y, z + 0.012 * scale, 0.014 * scale, 0.040 * scale, yaw + 0.35,
                0.086 * scale, uv, shade(colour, 0.9));
    }

    /** A drooping catkin — a furry cylinder, bent over at the tip. */
    private static void catkin(Mesh.Builder mesh, double x, double y, double z,
                               double scale, double yaw, float[] uv, int colour) {
        WatchMaterials.uv(WatchMaterial.DRY_GRASS, uv);
        double fx = Math.cos(yaw), fy = Math.sin(yaw);
        for (int i = 0; i < 5; i++) {
            double t = i / 4.0;
            Shapes.blob(mesh, x + fx * 0.030 * scale * t, y + fy * 0.030 * scale * t,
                    z + (0.075 - 0.062 * t * t) * scale,
                    0.013 * scale, 0.013 * scale, 0.018 * scale, uv,
                    shade(colour, 0.9 + 0.05 * (i % 3)));
        }
    }

    // --- fish and invertebrates -----------------------------------------------------

    /** A body, a tail, a dorsal fin and an eye — which is a fish, and a box is not. */
    private static void fish(Mesh.Builder mesh, String key, double x, double y, double z,
                             double scale, double yaw, float[] uv, int colour) {
        double length = key.equals("minnow_bait") ? 0.62 : 1.0;
        double fx = Math.sin(yaw), fy = -Math.cos(yaw);
        double rx = Math.cos(yaw), ry = Math.sin(yaw);
        WatchMaterials.uv(WatchMaterial.WATER, uv);
        // Deep-bodied and narrow, which is the shape of a fish seen from
        // anywhere but directly above. The first proportions here were long,
        // wide and shallow, and drew a plank with a fin on it.
        double half = 0.125 * scale * length;
        double belly = 0.058 * scale * length;
        double beam = 0.030 * scale * length;

        // The body, as three boxes tapering to a snout — <b>not an
        // octahedron.</b> A blob's plan view is a rhombus, and a satchel
        // portrait looks down at about twenty-five degrees, so every fish in
        // the game was a kite with a fin on it. Boxes are what every other
        // animal in this world is built from and their plan view is a fish.
        Shapes.box(mesh, x, y, z + belly, beam, half * 0.52, belly, yaw, uv, colour);
        Shapes.box(mesh, x + fx * half * 0.72, y + fy * half * 0.72, z + belly * 1.02,
                beam * 0.78, half * 0.24, belly * 0.86, yaw, uv, shade(colour, 0.94));
        Shapes.box(mesh, x + fx * half * 1.02, y + fy * half * 1.02, z + belly * 1.02,
                beam * 0.44, half * 0.12, belly * 0.52, yaw, uv, shade(colour, 0.88));
        // The stock the tail hangs off, narrowing behind the body.
        Shapes.box(mesh, x - fx * half * 0.74, y - fy * half * 0.74, z + belly * 1.02,
                beam * 0.46, half * 0.26, belly * 0.58, yaw, uv, shade(colour, 0.9));
        // A pale belly and a dark back, which is what a fish in the water is
        // counter-shaded for and what stops a landed one being one flat colour.
        Shapes.box(mesh, x, y, z + belly * 0.30, beam * 0.94, half * 0.50,
                belly * 0.30, yaw, uv, shade(colour, 1.45));
        Shapes.box(mesh, x, y, z + belly * 1.80, beam * 0.72, half * 0.48,
                belly * 0.22, yaw, uv, shade(colour, 0.66));
        // The tail: a fin standing on edge behind the body.
        Shapes.box(mesh, x - fx * half * 1.14, y - fy * half * 1.14, z + belly * 1.05,
                0.005 * scale, half * 0.26, belly * 0.95, yaw, uv, shade(colour, 0.76));
        // The dorsal fin, and a small pectoral on each flank.
        Shapes.blade(mesh, x, y, z + belly * 1.75, belly * 0.85, half * 0.72,
                yaw + Math.PI / 2, 0, 0, uv, shade(colour, 0.7));
        for (int side = -1; side <= 1; side += 2) {
            Shapes.box(mesh, x + fx * half * 0.22 + rx * beam * 1.05 * side,
                    y + fy * half * 0.22 + ry * beam * 1.05 * side,
                    z + belly * 0.72, beam * 0.42, half * 0.20, 0.004 * scale,
                    yaw, uv, shade(colour, 0.86));
        }
        // A pair of eyes, at the head end.
        for (int side = -1; side <= 1; side += 2) {
            Shapes.blob(mesh, x + fx * half * 0.70 + rx * beam * 0.62 * side,
                    y + fy * half * 0.70 + ry * beam * 0.62 * side,
                    z + belly * 1.35, 0.010 * scale, 0.010 * scale, 0.010 * scale, uv,
                    0x201C18);
        }
    }

    private static void critter(Mesh.Builder mesh, String key, double x, double y,
                                double z, double scale, double yaw, float[] uv,
                                int colour) {
        if (key.equals("mealworms")) {
            grubs(mesh, x, y, z, scale, yaw, uv, colour);
        } else {
            beetle(mesh, x, y, z, scale, yaw, uv, colour);
        }
    }

    /** Abdomen, elytra, head, and six legs that stick out past the shell. */
    private static void beetle(Mesh.Builder mesh, double x, double y, double z,
                               double scale, double yaw, float[] uv, int colour) {
        double fx = Math.sin(yaw), fy = -Math.cos(yaw);
        WatchMaterials.uv(WatchMaterial.DARK_BARK, uv);
        Shapes.blob(mesh, x, y, z + 0.016 * scale, 0.030 * scale, 0.046 * scale,
                0.016 * scale, yaw, uv, colour);
        // The wing cases, split down the middle.
        for (int side = -1; side <= 1; side += 2) {
            Shapes.blob(mesh, x + Math.cos(yaw) * 0.013 * scale * side,
                    y + Math.sin(yaw) * 0.013 * scale * side, z + 0.024 * scale,
                    0.014 * scale, 0.040 * scale, 0.013 * scale, yaw, uv,
                    shade(colour, 1.35));
        }
        Shapes.blob(mesh, x + fx * 0.048 * scale, y + fy * 0.048 * scale,
                z + 0.014 * scale, 0.018 * scale, 0.016 * scale, 0.012 * scale, yaw,
                uv, shade(colour, 0.8));
        // Antennae and legs, as thin boxes: six of the latter, which is what
        // makes it an insect and not a bean.
        for (int i = 0; i < 3; i++) {
            double along = (0.026 - i * 0.026) * scale;
            for (int side = -1; side <= 1; side += 2) {
                Shapes.box(mesh, x + fx * along + Math.cos(yaw) * 0.036 * scale * side,
                        y + fy * along + Math.sin(yaw) * 0.036 * scale * side,
                        z + 0.008 * scale, 0.016 * scale, 0.004 * scale, 0.003 * scale,
                        yaw, uv, shade(colour, 0.7));
            }
        }
    }

    /** A handful of mealworms: five segmented curls in a heap. */
    private static void grubs(Mesh.Builder mesh, double x, double y, double z,
                              double scale, double yaw, float[] uv, int colour) {
        WatchMaterials.uv(WatchMaterial.DRY_GRASS, uv);
        for (int i = 0; i < 5; i++) {
            double a = yaw + i * 1.31;
            double bx = x + Math.cos(a) * 0.024 * scale;
            double by = y + Math.sin(a) * 0.024 * scale;
            double lift = z + (0.010 + 0.008 * (i % 2)) * scale;
            for (int seg = 0; seg < 3; seg++) {
                Shapes.blob(mesh, bx + Math.cos(a + 0.5) * 0.016 * scale * seg,
                        by + Math.sin(a + 0.5) * 0.016 * scale * seg, lift,
                        0.011 * scale, 0.011 * scale, 0.009 * scale, uv,
                        shade(colour, 0.92 + 0.06 * seg));
            }
        }
    }

    // --- materials ------------------------------------------------------------------

    private static void material(Mesh.Builder mesh, String key, double x, double y,
                                 double z, double scale, double yaw, float[] uv,
                                 int colour) {
        switch (key) {
            case "stone" -> stone(mesh, x, y, z, scale, yaw, uv, colour);
            case "clay_lump" -> {
                WatchMaterials.uv(WatchMaterial.CLAY, uv);
                Shapes.blob(mesh, x, y, z + 0.038 * scale, 0.062 * scale, 0.055 * scale,
                        0.038 * scale, uv, colour);
                Shapes.blob(mesh, x + 0.030 * scale, y - 0.022 * scale, z + 0.022 * scale,
                        0.030 * scale, 0.026 * scale, 0.022 * scale, uv,
                        shade(colour, 0.85));
            }
            case "vine" -> coil(mesh, x, y, z, scale, yaw, uv, colour, true);
            case "rope" -> coil(mesh, x, y, z, scale, yaw, uv, colour, false);
            case "feather" -> feather(mesh, x, y, z, scale, yaw, uv, colour);
            case "reed_bundle" -> reeds(mesh, x, y, z, scale, yaw, uv, colour);
            case "thatch" -> thatch(mesh, x, y, z, scale, yaw, uv, colour);
            case "plank" -> plank(mesh, x, y, z, scale, yaw, uv, colour);
            case "bark_strip" -> barkStrip(mesh, x, y, z, scale, yaw, uv, colour);
            case "sap" -> sap(mesh, x, y, z, scale, yaw, uv, colour);
            case "sand" -> sand(mesh, x, y, z, scale, yaw, uv, colour);
            case "quartz" -> crystal(mesh, x, y, z, scale, yaw, uv, colour);
            case "lens" -> lens(mesh, x, y, z, scale, yaw, uv, colour);
            default -> branch(mesh, x, y, z, scale, yaw, uv, colour);
        }
    }

    /** A stick, lying down, with one stub of a side branch. */
    private static void branch(Mesh.Builder mesh, double x, double y, double z,
                               double scale, double yaw, float[] uv, int colour) {
        double fx = Math.sin(yaw), fy = -Math.cos(yaw);
        WatchMaterials.uv(WatchMaterial.BARK, uv);
        // A prism laid along the facing axis is not something `prism` can do —
        // it stands things up — so the shaft is a long thin box, which reads
        // the same at this size and costs half the triangles.
        Shapes.box(mesh, x, y, z + 0.022 * scale, 0.020 * scale, 0.150 * scale,
                0.020 * scale, yaw, uv, colour);
        Shapes.box(mesh, x + fx * 0.075 * scale + Math.cos(yaw) * 0.045 * scale,
                y + fy * 0.075 * scale + Math.sin(yaw) * 0.045 * scale,
                z + 0.020 * scale, 0.045 * scale, 0.012 * scale, 0.012 * scale,
                yaw + 0.9, uv, shade(colour, 0.85));
        // The cut end, a shade paler, so it reads as broken off rather than grown.
        Shapes.box(mesh, x - fx * 0.150 * scale, y - fy * 0.150 * scale,
                z + 0.022 * scale, 0.019 * scale, 0.006 * scale, 0.019 * scale,
                yaw, uv, shade(colour, 1.45));
    }

    /** A curled strip of bark, outer side up. */
    private static void barkStrip(Mesh.Builder mesh, double x, double y, double z,
                                  double scale, double yaw, float[] uv, int colour) {
        WatchMaterials.uv(WatchMaterial.BARK, uv);
        for (int i = 0; i < 3; i++) {
            double lean = (i - 1) * 0.4;
            Shapes.box(mesh, x + Math.cos(yaw) * 0.022 * scale * (i - 1),
                    y + Math.sin(yaw) * 0.022 * scale * (i - 1),
                    z + (0.010 + 0.014 * (1 - Math.abs(i - 1))) * scale,
                    0.024 * scale, 0.100 * scale, 0.008 * scale, yaw + lean * 0.12, uv,
                    shade(colour, 0.9 + 0.1 * i));
        }
    }

    /** A split plank: flat, square-edged, with a visible grain line. */
    private static void plank(Mesh.Builder mesh, double x, double y, double z,
                              double scale, double yaw, float[] uv, int colour) {
        WatchMaterials.uv(WatchMaterial.PLANK, uv);
        Shapes.box(mesh, x, y, z + 0.014 * scale, 0.055 * scale, 0.165 * scale,
                0.014 * scale, yaw, uv, colour);
        Shapes.box(mesh, x, y, z + 0.029 * scale, 0.008 * scale, 0.150 * scale,
                0.002 * scale, yaw, uv, shade(colour, 0.82));
    }

    /** A tied bundle of cut reeds, standing on end. */
    private static void reeds(Mesh.Builder mesh, double x, double y, double z,
                              double scale, double yaw, float[] uv, int colour) {
        WatchMaterials.uv(WatchMaterial.THATCH, uv);
        for (int i = 0; i < 6; i++) {
            double a = yaw + i * Math.PI / 3;
            double r = 0.020 * scale;
            spike(mesh, x + Math.cos(a) * r, y + Math.sin(a) * r, z,
                    (0.24 + 0.03 * (i % 3)) * scale, 0.020 * scale, a, 0.030 * scale,
                    uv, shade(colour, 0.9 + 0.05 * (i % 3)));
        }
        WatchMaterials.uv(WatchMaterial.ROPE, uv);
        Shapes.prism(mesh, x, y, z + 0.085 * scale, z + 0.100 * scale, 0.030 * scale,
                0.030 * scale, 6, yaw, uv, 0xB9A276, false);
    }

    /** Bound thatch: a flat mat of reeds with two ties across it. */
    private static void thatch(Mesh.Builder mesh, double x, double y, double z,
                               double scale, double yaw, float[] uv, int colour) {
        WatchMaterials.uv(WatchMaterial.THATCH, uv);
        for (int i = 0; i < 5; i++) {
            Shapes.box(mesh, x + Math.cos(yaw) * (i - 2) * 0.024 * scale,
                    y + Math.sin(yaw) * (i - 2) * 0.024 * scale, z + 0.012 * scale,
                    0.012 * scale, 0.130 * scale, 0.012 * scale, yaw, uv,
                    shade(colour, 0.88 + 0.06 * (i % 3)));
        }
        WatchMaterials.uv(WatchMaterial.ROPE, uv);
        for (int i = -1; i <= 1; i += 2) {
            Shapes.box(mesh, x + Math.sin(yaw) * 0.060 * scale * i,
                    y - Math.cos(yaw) * 0.060 * scale * i, z + 0.026 * scale,
                    0.062 * scale, 0.007 * scale, 0.006 * scale, yaw, uv, 0xB9A276);
        }
    }

    /** A hank of vine or rope, coiled flat — the vine keeps its leaves. */
    private static void coil(Mesh.Builder mesh, double x, double y, double z,
                             double scale, double yaw, float[] uv, int colour,
                             boolean leafy) {
        WatchMaterials.uv(leafy ? WatchMaterial.LEAF : WatchMaterial.ROPE, uv);
        for (int turn = 0; turn < 2; turn++) {
            double r = (0.052 - turn * 0.016) * scale;
            for (int i = 0; i < 8; i++) {
                double a = yaw + turn * 0.4 + i * Math.PI / 4;
                Shapes.blob(mesh, x + Math.cos(a) * r, y + Math.sin(a) * r,
                        z + (0.012 + turn * 0.018) * scale,
                        0.013 * scale, 0.026 * scale, 0.011 * scale, a + Math.PI / 2,
                        uv, shade(colour, 0.9 + 0.08 * (i % 2)));
            }
        }
        if (leafy) {
            for (int i = 0; i < 3; i++) {
                leaf(mesh, x + Math.cos(yaw + i * 2.1) * 0.045 * scale,
                        y + Math.sin(yaw + i * 2.1) * 0.045 * scale, z + 0.026 * scale,
                        scale * 0.8, yaw + i * 2.1, uv);
            }
        }
    }

    /** A moulted feather: a shaft with a vane down each side of it. */
    private static void feather(Mesh.Builder mesh, double x, double y, double z,
                                double scale, double yaw, float[] uv, int colour) {
        WatchMaterials.uv(WatchMaterial.PALE_BARK, uv);
        Shapes.box(mesh, x, y, z + 0.006 * scale, 0.004 * scale, 0.110 * scale,
                0.004 * scale, yaw, uv, shade(colour, 0.8));
        // A vane down each side of the shaft: the base runs along the shaft and
        // the tip stands out from it, so the two together read as a feather
        // rather than as two leaves.
        for (int side = -1; side <= 1; side += 2) {
            Shapes.blade(mesh, x, y, z + 0.008 * scale, 0.014 * scale, 0.200 * scale,
                    yaw + Math.PI / 2, Math.cos(yaw) * 0.062 * scale * side,
                    Math.sin(yaw) * 0.062 * scale * side, uv,
                    shade(colour, side < 0 ? 0.88 : 1.0));
        }
        // The bare quill at the base, which is the half you hold.
        Shapes.box(mesh, x - Math.sin(yaw) * 0.105 * scale,
                y + Math.cos(yaw) * 0.105 * scale, z + 0.006 * scale,
                0.005 * scale, 0.028 * scale, 0.005 * scale, yaw, uv,
                shade(colour, 1.15));
    }

    /** A boulder chip: a squashed octahedron with a smaller one beside it. */
    private static void stone(Mesh.Builder mesh, double x, double y, double z,
                              double scale, double yaw, float[] uv, int colour) {
        WatchMaterials.uv(WatchMaterial.ROCK, uv);
        Shapes.blob(mesh, x, y, z + 0.042 * scale, 0.070 * scale, 0.060 * scale,
                0.042 * scale, yaw, uv, colour);
        Shapes.blob(mesh, x + Math.cos(yaw) * 0.058 * scale,
                y + Math.sin(yaw) * 0.058 * scale, z + 0.022 * scale,
                0.032 * scale, 0.030 * scale, 0.022 * scale, uv, shade(colour, 0.82));
    }

    /** A little heap of sand, with the grain the atlas gives it. */
    private static void sand(Mesh.Builder mesh, double x, double y, double z,
                             double scale, double yaw, float[] uv, int colour) {
        WatchMaterials.uv(WatchMaterial.SAND, uv);
        Shapes.cone(mesh, x, y, z, z + 0.048 * scale, 0.080 * scale, 8, yaw, uv, colour);
        Shapes.cone(mesh, x + Math.cos(yaw) * 0.055 * scale,
                y + Math.sin(yaw) * 0.055 * scale, z, z + 0.024 * scale,
                0.044 * scale, 6, yaw, uv, shade(colour, 0.9));
    }

    /** A quartz point: a hexagonal shaft with a pyramid on top. */
    private static void crystal(Mesh.Builder mesh, double x, double y, double z,
                                double scale, double yaw, float[] uv, int colour) {
        WatchMaterials.uv(WatchMaterial.CRYSTAL, uv);
        Shapes.prism(mesh, x, y, z, z + 0.085 * scale, 0.032 * scale, 0.030 * scale,
                6, yaw, uv, colour, false);
        Shapes.cone(mesh, x, y, z + 0.085 * scale, z + 0.135 * scale, 0.030 * scale,
                6, yaw, uv, shade(colour, 1.15));
        // A second, shorter point growing off the side, because quartz does
        // that and because a single spike reads as a traffic cone.
        double a = yaw + 1.1;
        Shapes.prism(mesh, x + Math.cos(a) * 0.036 * scale,
                y + Math.sin(a) * 0.036 * scale, z, z + 0.048 * scale,
                0.018 * scale, 0.016 * scale, 6, a, uv, shade(colour, 0.9), false);
        Shapes.cone(mesh, x + Math.cos(a) * 0.036 * scale,
                y + Math.sin(a) * 0.036 * scale, z + 0.048 * scale, z + 0.076 * scale,
                0.016 * scale, 6, a, uv, shade(colour, 1.2));
    }

    /** A ground lens: a disc, thicker in the middle, in a rim. */
    private static void lens(Mesh.Builder mesh, double x, double y, double z,
                             double scale, double yaw, float[] uv, int colour) {
        // Two shallow cones back to back, which is what a ground lens is: the
        // lower one is built bottom-up like every other prism here, widening to
        // the rim rather than narrowing from it.
        WatchMaterials.uv(WatchMaterial.ICE, uv);
        Shapes.prism(mesh, x, y, z, z + 0.010 * scale, 0.030 * scale, 0.052 * scale,
                10, yaw, uv, shade(colour, 0.85), false);
        Shapes.prism(mesh, x, y, z + 0.010 * scale, z + 0.022 * scale, 0.052 * scale,
                0.030 * scale, 10, yaw, uv, colour, true);
        WatchMaterials.uv(WatchMaterial.PLANK, uv);
        Shapes.prism(mesh, x, y, z + 0.004 * scale, z + 0.016 * scale, 0.058 * scale,
                0.058 * scale, 10, yaw, uv, 0xB08A3C, false);
    }

    /** A blob of sap: a bead on a chip of bark. */
    private static void sap(Mesh.Builder mesh, double x, double y, double z,
                            double scale, double yaw, float[] uv, int colour) {
        WatchMaterials.uv(WatchMaterial.BARK, uv);
        Shapes.box(mesh, x, y, z + 0.006 * scale, 0.048 * scale, 0.062 * scale,
                0.006 * scale, yaw, uv, 0x6B4E32);
        WatchMaterials.uv(WatchMaterial.PETAL, uv);
        Shapes.blob(mesh, x, y, z + 0.030 * scale, 0.036 * scale, 0.036 * scale,
                0.024 * scale, uv, colour);
        Shapes.blob(mesh, x + Math.cos(yaw) * 0.032 * scale,
                y + Math.sin(yaw) * 0.032 * scale, z + 0.018 * scale,
                0.018 * scale, 0.018 * scale, 0.012 * scale, uv, shade(colour, 1.1));
    }

    // --- prepared food ---------------------------------------------------------------

    private static void prepared(Mesh.Builder mesh, String key, double x, double y,
                                 double z, double scale, double yaw, float[] uv,
                                 int colour) {
        switch (key) {
            case "nectar", "sugar_water", "petal_syrup" ->
                    bottle(mesh, x, y, z, scale, yaw, uv, colour);
            case "berry_mash" -> bowl(mesh, x, y, z, scale, yaw, uv, colour);
            case "grain_loaf" -> loaf(mesh, x, y, z, scale, yaw, uv, colour);
            case "grub_tray" -> tray(mesh, x, y, z, scale, yaw, uv, colour);
            case "smoked_fish" -> smokedFish(mesh, x, y, z, scale, yaw, uv, colour);
            case "moth_lamp" -> lamp(mesh, x, y, z, scale, yaw, uv, colour);
            case "hay_bundle" -> hay(mesh, x, y, z, scale, yaw, uv, colour);
            case "salt_lick" -> saltLick(mesh, x, y, z, scale, yaw, uv, colour);
            case "meat_scrap" -> meat(mesh, x, y, z, scale, yaw, uv, colour);
            case "clover" -> clover(mesh, x, y, z, scale, yaw, uv, colour);
            // Suet: a pressed cake with the seed still showing in it.
            default -> suetCake(mesh, x, y, z, scale, yaw, uv, colour);
        }
    }

    /** A pressed cake, with the seed and berry that went into it still visible. */
    private static void suetCake(Mesh.Builder mesh, double x, double y, double z,
                                 double scale, double yaw, float[] uv, int colour) {
        WatchMaterials.uv(WatchMaterial.THATCH, uv);
        Shapes.box(mesh, x, y, z + 0.030 * scale, 0.085 * scale, 0.085 * scale,
                0.030 * scale, yaw, uv, colour);
        WatchMaterials.uv(WatchMaterial.BERRY, uv);
        for (int i = 0; i < 5; i++) {
            double a = yaw + i * 1.26;
            Shapes.blob(mesh, x + Math.cos(a) * 0.045 * scale,
                    y + Math.sin(a) * 0.045 * scale, z + 0.060 * scale,
                    0.012 * scale, 0.012 * scale, 0.008 * scale, uv,
                    i % 2 == 0 ? 0x8A1C34 : 0x6A5432);
        }
    }

    /** A bark bowl with crushed fruit in it. */
    private static void bowl(Mesh.Builder mesh, double x, double y, double z,
                             double scale, double yaw, float[] uv, int colour) {
        WatchMaterials.uv(WatchMaterial.BARK, uv);
        Shapes.prism(mesh, x, y, z, z + 0.042 * scale, 0.048 * scale, 0.072 * scale,
                8, yaw, uv, 0x6B4E32, false);
        WatchMaterials.uv(WatchMaterial.BERRY, uv);
        Shapes.prism(mesh, x, y, z + 0.030 * scale, z + 0.040 * scale, 0.062 * scale,
                0.066 * scale, 8, yaw, uv, colour, true);
    }

    /** A stoppered bottle of something sweet. */
    private static void bottle(Mesh.Builder mesh, double x, double y, double z,
                               double scale, double yaw, float[] uv, int colour) {
        WatchMaterials.uv(WatchMaterial.GLASSPANE, uv);
        Shapes.prism(mesh, x, y, z, z + 0.090 * scale, 0.040 * scale, 0.036 * scale,
                8, yaw, uv, colour, false);
        Shapes.prism(mesh, x, y, z + 0.090 * scale, z + 0.120 * scale, 0.036 * scale,
                0.016 * scale, 8, yaw, uv, shade(colour, 1.12), false);
        WatchMaterials.uv(WatchMaterial.BARK, uv);
        Shapes.prism(mesh, x, y, z + 0.120 * scale, z + 0.142 * scale, 0.018 * scale,
                0.018 * scale, 6, yaw, uv, 0x8A6A44, true);
    }

    /** A baked slab with a scored top. */
    private static void loaf(Mesh.Builder mesh, double x, double y, double z,
                             double scale, double yaw, float[] uv, int colour) {
        WatchMaterials.uv(WatchMaterial.THATCH, uv);
        Shapes.blob(mesh, x, y, z + 0.038 * scale, 0.062 * scale, 0.095 * scale,
                0.040 * scale, yaw, uv, colour);
        for (int i = -1; i <= 1; i++) {
            Shapes.box(mesh, x + Math.sin(yaw) * i * 0.040 * scale,
                    y - Math.cos(yaw) * i * 0.040 * scale, z + 0.070 * scale,
                    0.032 * scale, 0.005 * scale, 0.004 * scale, yaw, uv,
                    shade(colour, 0.7));
        }
    }

    /** A slab of bark with grubs laid out on it. */
    private static void tray(Mesh.Builder mesh, double x, double y, double z,
                             double scale, double yaw, float[] uv, int colour) {
        WatchMaterials.uv(WatchMaterial.BARK, uv);
        Shapes.box(mesh, x, y, z + 0.008 * scale, 0.075 * scale, 0.095 * scale,
                0.008 * scale, yaw, uv, colour);
        grubs(mesh, x, y, z + 0.016 * scale, scale * 1.1, yaw, uv, 0xC8A870);
    }

    /** A split fish over a smoking rack. */
    private static void smokedFish(Mesh.Builder mesh, double x, double y, double z,
                                   double scale, double yaw, float[] uv, int colour) {
        WatchMaterials.uv(WatchMaterial.BARK, uv);
        for (int i = -1; i <= 1; i += 2) {
            Shapes.box(mesh, x + Math.cos(yaw) * 0.048 * scale * i,
                    y + Math.sin(yaw) * 0.048 * scale * i, z + 0.008 * scale,
                    0.006 * scale, 0.110 * scale, 0.008 * scale, yaw, uv, 0x5A4428);
        }
        WatchMaterials.uv(WatchMaterial.CLAY, uv);
        Shapes.blob(mesh, x, y, z + 0.030 * scale, 0.038 * scale, 0.100 * scale,
                0.018 * scale, yaw, uv, colour);
        // The score marks a smoked fillet carries.
        for (int i = -1; i <= 1; i++) {
            Shapes.box(mesh, x + Math.sin(yaw) * i * 0.038 * scale,
                    y - Math.cos(yaw) * i * 0.038 * scale, z + 0.048 * scale,
                    0.026 * scale, 0.004 * scale, 0.003 * scale, yaw, uv,
                    shade(colour, 0.68));
        }
    }

    /** A lamp: a base, a chimney, and a flame in it. */
    private static void lamp(Mesh.Builder mesh, double x, double y, double z,
                             double scale, double yaw, float[] uv, int colour) {
        WatchMaterials.uv(WatchMaterial.ROCK, uv);
        Shapes.prism(mesh, x, y, z, z + 0.026 * scale, 0.052 * scale, 0.038 * scale,
                8, yaw, uv, 0x6A665E, false);
        WatchMaterials.uv(WatchMaterial.GLASSPANE, uv);
        Shapes.prism(mesh, x, y, z + 0.026 * scale, z + 0.108 * scale, 0.038 * scale,
                0.034 * scale, 8, yaw, uv, 0xA9D6E0, false);
        WatchMaterials.uv(WatchMaterial.PETAL, uv);
        Shapes.cone(mesh, x, y, z + 0.040 * scale, z + 0.086 * scale, 0.018 * scale,
                6, yaw, uv, colour);
        WatchMaterials.uv(WatchMaterial.ROPE, uv);
        Shapes.prism(mesh, x, y, z + 0.108 * scale, z + 0.128 * scale, 0.030 * scale,
                0.012 * scale, 6, yaw, uv, 0x8A7250, true);
    }

    /** A tied bale of dried grass. */
    private static void hay(Mesh.Builder mesh, double x, double y, double z,
                            double scale, double yaw, float[] uv, int colour) {
        WatchMaterials.uv(WatchMaterial.DRY_GRASS, uv);
        Shapes.box(mesh, x, y, z + 0.045 * scale, 0.060 * scale, 0.085 * scale,
                0.045 * scale, yaw, uv, colour);
        for (int i = 0; i < 6; i++) {
            double a = yaw + i * 1.05;
            spike(mesh, x + Math.cos(a) * 0.050 * scale,
                    y + Math.sin(a) * 0.050 * scale, z + 0.060 * scale,
                    0.040 * scale, 0.008 * scale, a, 0.020 * scale, uv,
                    shade(colour, 1.1));
        }
        WatchMaterials.uv(WatchMaterial.ROPE, uv);
        Shapes.box(mesh, x, y, z + 0.045 * scale, 0.064 * scale, 0.008 * scale,
                0.047 * scale, yaw, uv, 0xB9A276);
    }

    /** A block of salt, licked hollow on top. */
    private static void saltLick(Mesh.Builder mesh, double x, double y, double z,
                                 double scale, double yaw, float[] uv, int colour) {
        WatchMaterials.uv(WatchMaterial.SNOW, uv);
        Shapes.box(mesh, x, y, z + 0.040 * scale, 0.070 * scale, 0.070 * scale,
                0.040 * scale, yaw, uv, colour);
        // Worn down in the middle, which is what a lick that has been licked
        // looks like and the only thing distinguishing it from a block of snow.
        Shapes.prism(mesh, x, y, z + 0.080 * scale, z + 0.094 * scale, 0.050 * scale,
                0.034 * scale, 8, yaw, uv, shade(colour, 0.86), true);
    }

    /** A cut of meat on a chip of bark. */
    private static void meat(Mesh.Builder mesh, double x, double y, double z,
                             double scale, double yaw, float[] uv, int colour) {
        WatchMaterials.uv(WatchMaterial.BARK, uv);
        Shapes.box(mesh, x, y, z + 0.006 * scale, 0.058 * scale, 0.070 * scale,
                0.006 * scale, yaw, uv, 0x6B4E32);
        WatchMaterials.uv(WatchMaterial.CLAY, uv);
        Shapes.blob(mesh, x, y, z + 0.026 * scale, 0.044 * scale, 0.056 * scale,
                0.020 * scale, yaw, uv, colour);
        Shapes.blob(mesh, x + Math.cos(yaw) * 0.030 * scale,
                y + Math.sin(yaw) * 0.030 * scale, z + 0.020 * scale,
                0.024 * scale, 0.026 * scale, 0.014 * scale, uv, shade(colour, 1.2));
    }

    /** A sprig of clover: three leaflets on a stem. */
    private static void clover(Mesh.Builder mesh, double x, double y, double z,
                               double scale, double yaw, float[] uv, int colour) {
        WatchMaterials.uv(WatchMaterial.LUSH_GRASS, uv);
        Shapes.prism(mesh, x, y, z, z + 0.070 * scale, 0.006 * scale, 0.005 * scale,
                4, yaw, uv, shade(colour, 0.85), false);
        for (int i = 0; i < 3; i++) {
            double a = yaw + i * Math.PI * 2 / 3;
            Shapes.blob(mesh, x + Math.cos(a) * 0.026 * scale,
                    y + Math.sin(a) * 0.026 * scale, z + 0.076 * scale,
                    0.024 * scale, 0.024 * scale, 0.005 * scale, uv,
                    shade(colour, 0.92 + 0.08 * i));
        }
    }

    // --- tools -----------------------------------------------------------------------

    private static void tool(Mesh.Builder mesh, String key, double x, double y, double z,
                             double scale, double yaw, float[] uv, int colour) {
        switch (key) {
            case "rod" -> rod(mesh, x, y, z, scale, yaw, uv, colour);
            case "feeder" -> feeder(mesh, x, y, z, scale, yaw, uv, colour);
            case "journal" -> journal(mesh, x, y, z, scale, yaw, uv, colour);
            case "spyglass" -> spyglass(mesh, x, y, z, scale, yaw, uv, colour);
            // The three things you can carry that burn, drawn <b>unlit</b> here
            // and by the same code that draws the standing ones. An item in a
            // satchel row, lying on the ground or being held out at a pickup is
            // not alight — what is alight is
            // {@link com.larsons.engine.watch.light.PlacedLight}, drawn by
            // {@link LightModel#light}, and the one in your hand while you carry
            // it lit, drawn by the view model. Two shapes for one object would
            // be two shapes to keep in step.
            case "lantern" -> LightModel.lantern(mesh, x, y, z, yaw, LightKind.LANTERN,
                    0, 0, scale * 0.75);
            case "spore_lantern" -> LightModel.lantern(mesh, x, y, z, yaw,
                    LightKind.SPORE_LANTERN,
                    // A jar of spores has nothing to put out: it is the one
                    // light that looks the same in a satchel as on a post.
                    1, 0, scale * 0.75);
            case "torch" -> LightModel.torch(mesh, x, y, z, yaw, 0, 0, scale * 0.42);
            case com.larsons.engine.watch.Tag.GUN ->
                    waterGun(mesh, x, y, z, scale, yaw, uv, colour);
            default -> trowel(mesh, x, y, z, scale, yaw, uv, colour);
        }
    }

    /**
     * A water gun: a tank, a barrel out of the front of it, and a grip.
     *
     * <p>Built along the yaw rather than up the z, unlike every other tool here,
     * because it is the one item whose <em>direction</em> is what a player reads:
     * somebody across a clearing has to be able to tell which way it is pointed,
     * and a shape drawn standing up says nothing about that.
     */
    private static void waterGun(Mesh.Builder mesh, double x, double y, double z,
                                 double scale, double yaw, float[] uv, int colour) {
        double fx = Math.sin(yaw), fy = -Math.cos(yaw);
        WatchMaterials.uv(WatchMaterial.CLAY, uv);
        // The tank, which is most of it.
        Shapes.box(mesh, x - fx * 0.030 * scale, y - fy * 0.030 * scale,
                z + 0.070 * scale, 0.040 * scale, 0.075 * scale, 0.032 * scale,
                yaw, uv, colour);
        // The water in it: a paler band along the top, so the thing reads as a
        // vessel rather than a block.
        Shapes.box(mesh, x - fx * 0.030 * scale, y - fy * 0.030 * scale,
                z + 0.104 * scale, 0.030 * scale, 0.062 * scale, 0.010 * scale,
                yaw, uv, lerp(colour, 0xE8F6FF, 0.7));
        // The barrel, and a nozzle on the end of it.
        Shapes.strut(mesh, x + fx * 0.050 * scale, y + fy * 0.050 * scale,
                z + 0.072 * scale, x + fx * 0.146 * scale, y + fy * 0.146 * scale,
                z + 0.072 * scale, 0.013 * scale, 0.013 * scale, uv,
                shade(colour, 0.8));
        Shapes.blob(mesh, x + fx * 0.152 * scale, y + fy * 0.152 * scale,
                z + 0.072 * scale, 0.015 * scale, 0.015 * scale, 0.015 * scale,
                uv, 0xF2C23A);
        // The grip, under the back of the tank.
        Shapes.box(mesh, x - fx * 0.052 * scale, y - fy * 0.052 * scale,
                z + 0.026 * scale, 0.020 * scale, 0.016 * scale, 0.030 * scale,
                yaw, uv, shade(colour, 0.7));
    }

    /** A rod: a tapered pole, a grip, and a line off the tip. */
    private static void rod(Mesh.Builder mesh, double x, double y, double z,
                            double scale, double yaw, float[] uv, int colour) {
        WatchMaterials.uv(WatchMaterial.BARK, uv);
        Shapes.prism(mesh, x, y, z, z + 1.35 * scale, 0.022 * scale, 0.006 * scale,
                5, yaw, uv, colour, true);
        WatchMaterials.uv(WatchMaterial.ROPE, uv);
        Shapes.prism(mesh, x, y, z + 0.14 * scale, z + 0.34 * scale, 0.026 * scale,
                0.026 * scale, 6, yaw, uv, 0xB9A276, false);
        // The line, as a hair-thin box hanging off the tip: without it a rod is
        // a stick, and a stick is what everything else in the satchel is.
        Shapes.box(mesh, x + Math.sin(yaw) * 0.05 * scale,
                y - Math.cos(yaw) * 0.05 * scale, z + 1.15 * scale,
                0.002 * scale, 0.002 * scale, 0.20 * scale, yaw, uv, 0xE0DCD0);
    }

    /** A trowel: a blade, a shoulder and a handle. */
    private static void trowel(Mesh.Builder mesh, double x, double y, double z,
                               double scale, double yaw, float[] uv, int colour) {
        double fx = Math.sin(yaw), fy = -Math.cos(yaw);
        WatchMaterials.uv(WatchMaterial.BARK, uv);
        // A handle you can see: the first one was a 2 cm pin on the end of a
        // wire, which at any size a satchel row draws is nothing at all.
        Shapes.prism(mesh, x - fx * 0.115 * scale, y - fy * 0.115 * scale,
                z + 0.016 * scale, z + 0.058 * scale, 0.032 * scale, 0.026 * scale,
                6, yaw, uv, colour, true);
        WatchMaterials.uv(WatchMaterial.ROCK, uv);
        Shapes.box(mesh, x - fx * 0.045 * scale, y - fy * 0.045 * scale,
                z + 0.020 * scale, 0.010 * scale, 0.045 * scale, 0.010 * scale,
                yaw, uv, 0x8A8578);
        // The blade: a flat pan with a point on it, tipped a little out of the
        // ground so it catches the light rather than vanishing edge-on.
        Shapes.box(mesh, x + fx * 0.030 * scale, y + fy * 0.030 * scale,
                z + 0.014 * scale, 0.042 * scale, 0.058 * scale, 0.008 * scale,
                yaw, uv, colour);
        spike(mesh, x + fx * 0.086 * scale, y + fy * 0.086 * scale, z + 0.014 * scale,
                0.008 * scale, 0.084 * scale, yaw - Math.PI / 2, 0.070 * scale, uv,
                shade(colour, 1.08));
    }

    /** A feeder: a tray on a post, with a peaked roof over it. */
    private static void feeder(Mesh.Builder mesh, double x, double y, double z,
                               double scale, double yaw, float[] uv, int colour) {
        WatchMaterials.uv(WatchMaterial.PLANK, uv);
        Shapes.box(mesh, x, y, z + 0.030 * scale, 0.090 * scale, 0.090 * scale,
                0.014 * scale, yaw, uv, colour);
        for (int side = -1; side <= 1; side += 2) {
            Shapes.box(mesh, x + Math.cos(yaw) * 0.086 * scale * side,
                    y + Math.sin(yaw) * 0.086 * scale * side, z + 0.050 * scale,
                    0.008 * scale, 0.090 * scale, 0.024 * scale, yaw, uv,
                    shade(colour, 0.86));
        }
        for (int i = 0; i < 4; i++) {
            double a = yaw + Math.PI / 4 + i * Math.PI / 2;
            Shapes.box(mesh, x + Math.cos(a) * 0.078 * scale,
                    y + Math.sin(a) * 0.078 * scale, z + 0.090 * scale,
                    0.006 * scale, 0.006 * scale, 0.048 * scale, yaw, uv,
                    shade(colour, 0.78));
        }
        WatchMaterials.uv(WatchMaterial.THATCH, uv);
        Shapes.cone(mesh, x, y, z + 0.138 * scale, z + 0.190 * scale, 0.105 * scale,
                4, yaw + Math.PI / 4, uv, 0xC0A45E);
    }

    /** The journal: a bound book with a ribbon in it. */
    private static void journal(Mesh.Builder mesh, double x, double y, double z,
                                double scale, double yaw, float[] uv, int colour) {
        WatchMaterials.uv(WatchMaterial.BARK, uv);
        Shapes.box(mesh, x, y, z + 0.014 * scale, 0.062 * scale, 0.085 * scale,
                0.014 * scale, yaw, uv, colour);
        WatchMaterials.uv(WatchMaterial.PALE_BARK, uv);
        Shapes.box(mesh, x + Math.cos(yaw) * 0.006 * scale,
                y + Math.sin(yaw) * 0.006 * scale, z + 0.015 * scale,
                0.058 * scale, 0.078 * scale, 0.013 * scale, yaw, uv, 0xE4DCC8);
        WatchMaterials.uv(WatchMaterial.BERRY, uv);
        Shapes.box(mesh, x, y, z + 0.029 * scale, 0.010 * scale, 0.070 * scale,
                0.002 * scale, yaw, uv, 0xA03448);
    }

    /**
     * A draw-tube spyglass: three barrels, two collars and a lens.
     *
     * <p>Built along the facing axis rather than standing up like the rod,
     * because the one thing that has to read from across a clearing is
     * <em>which way somebody is pointing it</em> — a party watching a walker
     * raise a glass wants to know where to look, and a foreshortened tube
     * pointing at you is exactly the shape that says "at you".
     *
     * <p>The barrels step down toward the eye, which is what a drawn tube does
     * and what makes twenty triangles read as an instrument rather than as a
     * stick. The far end gets a pale disc: the objective, which catches the
     * light and is the only part anybody can see at range.
     */
    private static void spyglass(Mesh.Builder mesh, double x, double y, double z,
                                 double scale, double yaw, float[] uv, int colour) {
        double fx = Math.sin(yaw), fy = -Math.cos(yaw);
        WatchMaterials.uv(WatchMaterial.PLANK, uv);
        int brass = 0xB08A3C;
        int leather = 0x5A3F28;

        // eyepiece, barrel, objective — narrow to wide, back to front.
        barrel(mesh, x, y, z, fx, fy, -0.11, 0.05, 0.020, scale, yaw, uv, leather);
        barrel(mesh, x, y, z, fx, fy, 0.00, 0.06, 0.026, scale, yaw, uv, brass);
        barrel(mesh, x, y, z, fx, fy, 0.12, 0.06, 0.032, scale, yaw, uv, brass);
        // the collars where one tube slides into the next
        barrel(mesh, x, y, z, fx, fy, -0.06, 0.010, 0.030, scale, yaw, uv, leather);
        barrel(mesh, x, y, z, fx, fy, 0.06, 0.010, 0.036, scale, yaw, uv, leather);
        // the glass itself, at the front
        WatchMaterials.uv(WatchMaterial.ICE, uv);
        barrel(mesh, x, y, z, fx, fy, 0.178, 0.006, 0.030, scale, yaw, uv,
                WatchMaterials.shade(WatchMaterial.ICE));
        // Keep the tint the rest of the tool code computed doing something, so
        // two spyglasses in one clearing are not identically shiny.
        barrel(mesh, x, y, z, fx, fy, -0.155, 0.006, 0.018, scale, yaw, uv, colour);
    }

    /** One length of tube, {@code along} metres up the facing axis. */
    private static void barrel(Mesh.Builder mesh, double x, double y, double z,
                               double fx, double fy, double along, double halfLength,
                               double radius, double scale, double yaw, float[] uv,
                               int colour) {
        Shapes.box(mesh, x + fx * along * scale, y + fy * along * scale,
                z + 0.03 * scale, radius * scale, halfLength * scale, radius * scale,
                yaw, uv, colour);
    }

    // --- colour ----------------------------------------------------------------------

    /**
     * The colour an item is drawn in.
     *
     * <p>A table first — a blueberry is blue, a snowberry is white, a pike is
     * weed-green — and {@link #tintOf}'s band-and-hash for anything the table
     * has not heard of, so a key added to {@link Forage} still draws in a
     * plausible colour without anybody having to come back here.
     */
    public static int colourOf(String key, Forage.Kind kind) {
        Integer known = key == null ? null : PALETTE.get(key);
        return known != null ? known : tintOf(key, kind);
    }

    /**
     * A colour from the item's key alone: the kind picks a band — reds and
     * purples for berries, straws for seeds, silvers for fish — and the hash
     * picks a point in it.
     *
     * <p>What {@link #colourOf} falls back to. On its own it guarantees only
     * that two items differ, which is why it is no longer the whole answer.
     */
    public static int tintOf(String key, Forage.Kind kind) {
        int hash = key == null ? 0 : key.hashCode();
        double t = ((hash >>> 8) & 0xFF) / 255.0;
        return switch (kind) {
            case BERRY -> lerp(0x8A1C34, 0x6A2C86, t);
            case SEED -> lerp(0xB89A52, 0x8A7A3C, t);
            case FISH -> lerp(0x8FA6B4, 0x5C7A8A, t);
            case CRITTER -> lerp(0x4A3A2A, 0x6B5A38, t);
            case MATERIAL -> lerp(0x7A6244, 0x9A8A6A, t);
            case PREPARED -> lerp(0xC0A45E, 0xA8703C, t);
            case TOOL -> lerp(0x8A7250, 0xA39070, t);
        };
    }

    /** What each item is actually the colour of. See {@link #colourOf}. */
    private static final Map<String, Integer> PALETTE = palette();

    private static Map<String, Integer> palette() {
        Map<String, Integer> m = new HashMap<>();
        // berries and fruit
        m.put("blackberry", 0x241A2C);
        m.put("blueberry", 0x3C5688);
        m.put("lingonberry", 0xB4243A);
        m.put("cloudberry", 0xE0A038);
        m.put("elderberry", 0x2A1C38);
        m.put("juniper", 0x56688C);
        m.put("salmonberry", 0xE0762C);
        m.put("thimbleberry", 0xC8384A);
        m.put("crowberry", 0x241F2C);
        m.put("snowberry", 0xE8E4E0);
        m.put("prickly_pear", 0xB0325E);
        m.put("guava", 0xC8C060);
        m.put("fig", 0x6A3A60);
        m.put("sea_grape", 0x8A3A56);
        m.put("mangrove_apple", 0x9AA858);
        m.put("nightbell", 0x6A4AA8);
        m.put("dewfruit", 0xAEDCE8);
        m.put("amethyst_plum", 0x7A4AB0);
        m.put("sun_pear", 0xE0C050);
        m.put("date", 0x7A4A28);
        m.put("moon_date", 0xC4B4A0);
        m.put("cactus_fruit", 0xD04858);
        m.put("baobab_fruit", 0xB8A888);
        m.put("cocoa_pod", 0xB05828);
        m.put("coconut", 0x8A6A44);
        m.put("glow_spore", 0x9CE0C8);
        m.put("star_spore", 0xC4BCF0);
        m.put("kapok_pod", 0xA8906A);
        // seeds
        m.put("grass_seed", 0xC8B878);
        m.put("sunflower_seed", 0x3A3228);
        m.put("thistle_seed", 0x8A7A60);
        m.put("millet", 0xD8C878);
        m.put("wild_rice", 0x53472F);
        m.put("lupine_seed", 0x9A8A5A);
        m.put("sedge_seed", 0xA89858);
        m.put("acorn", 0x8A6034);
        m.put("beechnut", 0x7A5430);
        m.put("pine_seed", 0x6A5238);
        m.put("birch_seed", 0xC0A870);
        m.put("redwood_cone", 0x7A4E30);
        m.put("palm_seed", 0x9A7A50);
        m.put("bamboo_seed", 0xB8C070);
        m.put("cactus_seed", 0x504028);
        m.put("kapok_seed", 0xC8BCA0);
        m.put("amethyst_seed", 0x9A70D0);
        m.put("spore_pod", 0x7A9A80);
        m.put("dawn_cone", 0x8A6A48);
        m.put("samara", 0xC8B070);
        m.put("catkin", 0xB8A878);
        // fish and invertebrates
        m.put("trout", 0x7E8C76);
        m.put("char", 0x9A6A5A);
        m.put("pike", 0x6A7A4A);
        m.put("perch", 0x7A8A46);
        m.put("minnow_bait", 0xB0BCC4);
        m.put("beetle", 0x2E2820);
        m.put("mealworms", 0xC8A870);
        // materials
        m.put("fallen_branch", 0x6B4E32);
        m.put("bark_strip", 0x7A5C3C);
        m.put("reed_bundle", 0xC0A45E);
        m.put("stone", 0x7A7A78);
        m.put("vine", 0x4A6B33);
        m.put("clay_lump", 0x9A6A48);
        m.put("sap", 0xD8A038);
        m.put("feather", 0xD8D4CC);
        m.put("plank", 0xA37C4C);
        m.put("thatch", 0xC0A45E);
        m.put("rope", 0xB9A276);
        m.put("sand", 0xD9C68A);
        m.put("quartz", 0xD4E4EC);
        m.put("lens", 0xB8D8E6);
        // prepared
        m.put("suet_cake", 0xD8C89A);
        m.put("berry_mash", 0x8A2A50);
        m.put("nectar", 0xE0A840);
        m.put("sugar_water", 0xD8D0B0);
        m.put("petal_syrup", 0xC85A70);
        m.put("grain_loaf", 0xC09858);
        m.put("grub_tray", 0x8A6A44);
        m.put("smoked_fish", 0x9A6038);
        m.put("moth_lamp", 0xE8D070);
        m.put("hay_bundle", 0xC8B060);
        m.put("salt_lick", 0xE8E4E0);
        m.put("meat_scrap", 0xA84838);
        m.put("clover", 0x4A8A38);
        // tools
        m.put("rod", 0x6B4E32);
        m.put("trowel", 0x9A968C);
        m.put("feeder", 0xA37C4C);
        m.put("journal", 0x6A4A34);
        m.put("spyglass", 0xB08A3C);
        // The one thing in this wood that is not made of wood, bark or bone, and
        // it is meant to look it: the tag round is a game inside the game, and a
        // bright plastic toy is the honest picture of that.
        m.put(com.larsons.engine.watch.Tag.GUN, 0x3AA8D8);
        // The lights. Their models build their own colours out of
        // LightKind.rgb(), so these are only what a satchel row's rarity dot
        // and any future flat draw would use — the colour of the object, not
        // the colour of the flame.
        m.put("lantern", 0xC8A24C);
        m.put("torch", 0x8A6A3C);
        m.put("spore_lantern", 0x7CF0C0);
        return Map.copyOf(m);
    }

    /** One colour, brightened or darkened by a factor, clamped per channel. */
    private static int shade(int rgb, double factor) {
        int r = Math.min(255, (int) (((rgb >> 16) & 0xFF) * factor));
        int g = Math.min(255, (int) (((rgb >> 8) & 0xFF) * factor));
        int b = Math.min(255, (int) ((rgb & 0xFF) * factor));
        return (r << 16) | (g << 8) | b;
    }

    private static int lerp(int a, int b, double t) {
        int r = (int) (((a >> 16) & 0xFF) + (((b >> 16) & 0xFF) - ((a >> 16) & 0xFF)) * t);
        int g = (int) (((a >> 8) & 0xFF) + (((b >> 8) & 0xFF) - ((a >> 8) & 0xFF)) * t);
        int bl = (int) ((a & 0xFF) + ((b & 0xFF) - (a & 0xFF)) * t);
        return (r << 16) | (g << 8) | bl;
    }
}
