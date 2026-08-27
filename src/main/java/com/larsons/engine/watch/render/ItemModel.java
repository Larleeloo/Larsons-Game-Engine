package com.larsons.engine.watch.render;

import com.larsons.engine.watch.Forage;
import com.larsons.engine.watch.world.WatchMaterial;
import com.larsons.engine.watch.world.WatchMaterials;

/**
 * A small solid for anything you can be carrying — <b>so that "an item" is a
 * thing in the world rather than a word in a list.</b>
 *
 * <h2>Why an item needs a model</h2>
 *
 * <p>Everything in this game that you can pick up existed only as a key in a
 * satchel and a line of text. That is enough for the bag and not enough for
 * anywhere else: a feeder with suet in it drew a generic red blob whatever it
 * had been filled with, a dropped item could not exist at all, and the hands
 * this class was written alongside had nothing to hold. One model per
 * {@link Forage.Kind}, tinted per item, fixes all three for a couple of dozen
 * triangles apiece.
 *
 * <p>The models are deliberately <em>kind</em>-shaped rather than item-shaped: a
 * blackberry and a lingonberry are both a small cluster of dark spheres and
 * pretending otherwise would be forty models nobody could tell apart at the
 * distance they are seen from. What distinguishes them is the tint, which comes
 * from the item's own key so it is stable and needs no table.
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
        int colour = tintOf(key, kind);

        switch (kind) {
            case BERRY -> {
                WatchMaterials.uv(WatchMaterial.BERRY, uv);
                // A little cluster, because one sphere reads as a ball bearing.
                for (int i = 0; i < 3; i++) {
                    double a = yaw + i * Math.PI * 2 / 3;
                    Shapes.blob(mesh, x + Math.cos(a) * 0.035 * scale,
                            y + Math.sin(a) * 0.035 * scale, z + 0.03 * scale,
                            0.035 * scale, 0.035 * scale, 0.035 * scale, uv, colour);
                }
            }
            case SEED -> {
                WatchMaterials.uv(WatchMaterial.DRY_GRASS, uv);
                Shapes.blob(mesh, x, y, z + 0.03 * scale, 0.05 * scale, 0.03 * scale,
                        0.028 * scale, uv, colour);
            }
            case FISH -> {
                WatchMaterials.uv(WatchMaterial.WATER, uv);
                Shapes.box(mesh, x, y, z + 0.05 * scale, 0.05 * scale, 0.15 * scale,
                        0.05 * scale, yaw, uv, colour);
                // A tail, which is the whole of what makes a box a fish.
                Shapes.box(mesh, x - Math.sin(yaw) * 0.17 * scale,
                        y + Math.cos(yaw) * 0.17 * scale, z + 0.05 * scale,
                        0.015 * scale, 0.05 * scale, 0.07 * scale, yaw, uv, colour);
            }
            case CRITTER -> {
                WatchMaterials.uv(WatchMaterial.DARK_BARK, uv);
                Shapes.blob(mesh, x, y, z + 0.02 * scale, 0.045 * scale, 0.028 * scale,
                        0.02 * scale, uv, colour);
            }
            case MATERIAL -> material(mesh, key, x, y, z, scale, yaw, uv, colour);
            case PREPARED -> {
                WatchMaterials.uv(WatchMaterial.THATCH, uv);
                Shapes.box(mesh, x, y, z + 0.04 * scale, 0.09 * scale, 0.09 * scale,
                        0.04 * scale, yaw, uv, colour);
            }
            case TOOL -> tool(mesh, key, x, y, z, scale, yaw, uv, colour);
        }
    }

    private static void material(Mesh.Builder mesh, String key, double x, double y,
                                 double z, double scale, double yaw, float[] uv,
                                 int colour) {
        switch (key) {
            case "stone", "clay_lump" -> {
                WatchMaterials.uv(WatchMaterial.ROCK, uv);
                Shapes.blob(mesh, x, y, z + 0.05 * scale, 0.08 * scale, 0.07 * scale,
                        0.05 * scale, uv, colour);
            }
            case "vine", "rope" -> {
                WatchMaterials.uv(WatchMaterial.ROPE, uv);
                Shapes.prism(mesh, x, y, z, z + 0.04 * scale, 0.07 * scale,
                        0.07 * scale, 6, yaw, uv, colour, true);
            }
            case "feather" -> {
                WatchMaterials.uv(WatchMaterial.PLANK, uv);
                Shapes.blade(mesh, x, y, z, 0.20 * scale, 0.05 * scale, yaw,
                        Math.sin(yaw) * 0.05 * scale, -Math.cos(yaw) * 0.05 * scale,
                        uv, colour);
            }
            case "reed_bundle", "thatch" -> {
                WatchMaterials.uv(WatchMaterial.THATCH, uv);
                for (int i = 0; i < 4; i++) {
                    double a = yaw + i * Math.PI / 4;
                    Shapes.blade(mesh, x + Math.cos(a) * 0.02 * scale,
                            y + Math.sin(a) * 0.02 * scale, z, 0.26 * scale,
                            0.03 * scale, a, 0, 0, uv, colour);
                }
            }
            default -> {
                // A branch, a plank, a strip of bark: a stick, lying down.
                WatchMaterials.uv(WatchMaterial.BARK, uv);
                Shapes.prism(mesh, x, y, z + 0.03 * scale, z + 0.05 * scale,
                        0.03 * scale, 0.03 * scale, 5, yaw, uv, colour, true);
                Shapes.box(mesh, x, y, z + 0.04 * scale, 0.03 * scale, 0.22 * scale,
                        0.03 * scale, yaw, uv, colour);
            }
        }
    }

    private static void tool(Mesh.Builder mesh, String key, double x, double y, double z,
                             double scale, double yaw, float[] uv, int colour) {
        switch (key) {
            case "rod" -> {
                WatchMaterials.uv(WatchMaterial.BARK, uv);
                Shapes.prism(mesh, x, y, z, z + 1.35 * scale, 0.022 * scale,
                        0.008 * scale, 5, yaw, uv, colour, true);
            }
            case "feeder" -> {
                WatchMaterials.uv(WatchMaterial.PLANK, uv);
                Shapes.box(mesh, x, y, z + 0.05 * scale, 0.13 * scale, 0.13 * scale,
                        0.05 * scale, yaw, uv, colour);
            }
            case "journal" -> {
                WatchMaterials.uv(WatchMaterial.PLANK, uv);
                Shapes.box(mesh, x, y, z + 0.02 * scale, 0.07 * scale, 0.10 * scale,
                        0.02 * scale, yaw, uv, colour);
            }
            default -> {
                WatchMaterials.uv(WatchMaterial.BARK, uv);
                Shapes.box(mesh, x, y, z + 0.03 * scale, 0.03 * scale, 0.12 * scale,
                        0.03 * scale, yaw, uv, colour);
                Shapes.box(mesh, x + Math.sin(yaw) * 0.13 * scale,
                        y - Math.cos(yaw) * 0.13 * scale, z + 0.03 * scale,
                        0.045 * scale, 0.05 * scale, 0.012 * scale, yaw, uv,
                        WatchMaterials.shade(WatchMaterial.ROCK));
            }
        }
    }

    /**
     * The colour an item is drawn in.
     *
     * <p>From its key's hash rather than from a table: forty berries would be
     * forty rows of a table nobody would ever check, and what actually matters
     * is that a blackberry is consistently darker than a salmonberry and that
     * neither of them is ever grey. So the kind picks the band — reds and
     * purples for berries, straws for seeds, silvers for fish — and the hash
     * picks a point in it.
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

    private static int lerp(int a, int b, double t) {
        int r = (int) (((a >> 16) & 0xFF) + (((b >> 16) & 0xFF) - ((a >> 16) & 0xFF)) * t);
        int g = (int) (((a >> 8) & 0xFF) + (((b >> 8) & 0xFF) - ((a >> 8) & 0xFF)) * t);
        int bl = (int) ((a & 0xFF) + ((b & 0xFF) - (a & 0xFF)) * t);
        return (r << 16) | (g << 8) | bl;
    }
}
