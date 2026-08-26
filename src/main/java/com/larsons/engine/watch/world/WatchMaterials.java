package com.larsons.engine.watch.world;

import com.larsons.engine.graphics.Offscreen;
import com.larsons.engine.graphics.Skins;

import java.awt.image.BufferedImage;
import java.util.Random;

/**
 * The one texture the whole world is drawn with, and the colours the CPU path
 * shades it by.
 *
 * <p><b>One atlas, because the draw count is the wall.</b> A terrain mesh that
 * changed texture every time it changed material would be one draw call per
 * material per chunk; with every material in one image, a chunk is one call and
 * the material lives in the vertex's texture coordinates. That is the same
 * argument {@link com.larsons.engine.graphics.chunk.BlockAtlas} makes for
 * blocks, and this is the same shape of answer at a different scale — a few
 * dozen tiles rather than a few hundred.
 *
 * <p><b>Where a tile comes from.</b> A texture pack's file for
 * {@link WatchMaterial#textureKey()} if there is one, else a generated tile:
 * the material's colour with a low-amplitude value noise over it, which is what
 * keeps a hillside from reading as one flat wash under a light that grazes it.
 * The engine ships no image files (invariant 4) and this does not change that.
 *
 * <p><b>{@link #shade} is the CPU path's whole use of the atlas.</b> Java2D
 * fills a polygon with a colour, not with a texture, and a per-pixel affine
 * texture map through {@code drawImage} would cost more than the entire rest of
 * the frame. So the CPU path asks for the material's <em>average</em> colour —
 * which is the material's own colour when no pack is installed, and the pack's
 * average when one is — and fills flat. A pack therefore recolours the Java2D
 * build and fully textures the GL one, from one set of files.
 */
public final class WatchMaterials {

    /** Edge of one tile in the atlas, in pixels. */
    public static final int TILE = 32;

    /** Tiles across the atlas; enough for every material with room to grow. */
    private static final int COLUMNS = 8;

    /**
     * A hair of the tile kept off each edge when a UV is handed out, so a
     * bilinear filter at a tile's border cannot bleed its neighbour in. The
     * block atlas calls this the same thing and needs it for the same reason.
     */
    private static final float INSET = 0.5f / (TILE * COLUMNS);

    private static BufferedImage atlas;
    private static int revision;
    private static int[] average;

    private WatchMaterials() {}

    /**
     * The atlas, built on first use.
     *
     * <p>Synchronised because chunk meshing runs on the streamer's workers and
     * every one of them asks for UVs; the build itself happens once.
     */
    public static synchronized BufferedImage atlas() {
        if (atlas == null) rebuild();
        return atlas;
    }

    /**
     * How many times the atlas has been rebuilt. A renderer that has uploaded
     * it compares this rather than the image, so a texture pack rescan
     * re-uploads and an ordinary frame does not.
     */
    public static synchronized int revision() {
        if (atlas == null) rebuild();
        return revision;
    }

    /**
     * Throw the atlas away, so the next frame picks up a texture pack that has
     * just been installed or rescanned.
     */
    public static synchronized void invalidate() {
        atlas = null;
        average = null;
    }

    /**
     * The flat colour {@code material} is drawn in — the average of its atlas
     * tile, so a pack's grass shades the Java2D build too.
     *
     * @return packed {@code 0xRRGGBB}
     */
    public static synchronized int shade(WatchMaterial material) {
        if (average == null) rebuild();
        return average[material.ordinal()];
    }

    /**
     * The texture coordinates of {@code material}'s tile, written into
     * {@code out} as {@code u0, v0, u1, v1}.
     *
     * <p>Writes into a caller-owned array: a chunk mesh asks this once per
     * triangle strip and thousands of times per build, and the allocation would
     * cost more than the arithmetic.
     */
    public static void uv(WatchMaterial material, float[] out) {
        int index = material.ordinal();
        int col = index % COLUMNS;
        int row = index / COLUMNS;
        float span = 1f / COLUMNS;
        float rows = rowCount();
        out[0] = col * span + INSET;
        out[1] = row / rows + INSET;
        out[2] = (col + 1) * span - INSET;
        out[3] = (row + 1) / rows - INSET;
    }

    /** The centre of {@code material}'s tile — what an untextured face uses. */
    public static void centreUv(WatchMaterial material, float[] out) {
        float[] box = new float[4];
        uv(material, box);
        out[0] = (box[0] + box[2]) / 2;
        out[1] = (box[1] + box[3]) / 2;
    }

    private static int rowCount() {
        return (WatchMaterial.values().length + COLUMNS - 1) / COLUMNS;
    }

    private static void rebuild() {
        WatchMaterial[] all = WatchMaterial.values();
        int rows = rowCount();
        BufferedImage image = new BufferedImage(COLUMNS * TILE, rows * TILE,
                BufferedImage.TYPE_INT_ARGB);
        int[] shades = new int[all.length];
        try (Offscreen bake = Offscreen.over(image, false)) {
            for (int i = 0; i < all.length; i++) {
                int x = (i % COLUMNS) * TILE;
                int y = (i / COLUMNS) * TILE;
                BufferedImage tile = packTile(all[i]);
                if (tile != null) {
                    bake.target().drawImage(tile, x, y, TILE, TILE);
                } else {
                    paintTile(image, x, y, all[i]);
                }
                shades[i] = averageOf(image, x, y);
            }
        }
        atlas = image;
        average = shades;
        revision++;
    }

    /** A pack's (or the player's) sheet for this material, if there is one. */
    private static BufferedImage packTile(WatchMaterial material) {
        try {
            return Skins.frame(material.textureKey(), 0);
        } catch (RuntimeException e) {
            return null; // a broken sheet is a missing sheet, not a crash
        }
    }

    /**
     * The generated tile: the material's colour, with value noise over it and a
     * touch more contrast on the hard materials than the soft ones.
     */
    private static void paintTile(BufferedImage image, int ox, int oy, WatchMaterial material) {
        int base = material.rgb();
        int br = (base >> 16) & 0xFF, bg = (base >> 8) & 0xFF, bb = base & 0xFF;
        // Seeded from the material's own name, so a tile is the same every run
        // and two materials never share a pattern.
        Random rng = new Random(material.key().hashCode() * 0x9E3779B97F4A7C15L);
        double grain = switch (material) {
            case ROCK, DARK_ROCK, GRAVEL, STONE_BLOCK, ASH -> 0.22;
            case SAND, RED_SAND, SNOW, TRAIL_SAND -> 0.10;
            case WATER, SHALLOWS, ICE, GLASSPANE, CRYSTAL -> 0.07;
            default -> 0.15;
        };
        int alpha = material.translucent() ? 200 : 255;
        for (int y = 0; y < TILE; y++) {
            for (int x = 0; x < TILE; x++) {
                double n = (rng.nextDouble() - 0.5) * 2 * grain;
                // A coarse second octave, so the grain has shapes in it rather
                // than being per-pixel confetti.
                if (((x / 4) + (y / 4)) % 3 == 0) n += grain * 0.35;
                int r = clamp(br * (1 + n));
                int gg = clamp(bg * (1 + n));
                int b = clamp(bb * (1 + n));
                image.setRGB(ox + x, oy + y, (alpha << 24) | (r << 16) | (gg << 8) | b);
            }
        }
    }

    private static int averageOf(BufferedImage image, int ox, int oy) {
        long r = 0, g = 0, b = 0;
        for (int y = 0; y < TILE; y++) {
            for (int x = 0; x < TILE; x++) {
                int argb = image.getRGB(ox + x, oy + y);
                r += (argb >> 16) & 0xFF;
                g += (argb >> 8) & 0xFF;
                b += argb & 0xFF;
            }
        }
        int n = TILE * TILE;
        return (int) ((r / n) << 16 | (g / n) << 8 | (b / n));
    }

    private static int clamp(double v) {
        return (int) Math.max(0, Math.min(255, Math.round(v)));
    }
}
