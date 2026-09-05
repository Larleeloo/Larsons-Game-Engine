package com.larsons.engine.watch;

import com.larsons.engine.graphics.MeshPass;
import com.larsons.engine.graphics.SkinDef;
import com.larsons.engine.graphics.Skins;
import com.larsons.engine.watch.world.WatchMaterial;
import com.larsons.engine.watch.world.WatchMaterials;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>The material atlases — what a surface is, as two images.</b>
 *
 * <p>{@code WatchRenderTest} checks that every material has a tile and that the
 * tiles do not overlap. This is about what is <em>in</em> them, and every
 * assertion here is one that failed silently before it existed:
 *
 * <ul>
 *   <li>the colour atlas holds <b>detail</b> rather than colour, so the card
 *       stops multiplying a material by itself. That bug drew the whole GL
 *       build at a third of the brightness the painter drew it at, for
 *       months, and it does not announce itself — it looks like "the lighting
 *       is a bit dark";</li>
 *   <li>the surface atlas holds a material rather than noise: roughness that
 *       is the material's, metalness that is exactly it, and normals that
 *       average out flat;</li>
 *   <li>the tiles <b>join up with themselves</b>. Every one of them is
 *       repeated every two metres across the ground, so a grain that does not
 *       wrap is a line ruled across the world at every quad edge — which is
 *       precisely what the stretched lattice under bark did before its two
 *       axes were given their own cell counts;</li>
 *   <li>and a texture pack goes through all of it unharmed.</li>
 * </ul>
 */
class MaterialsTest {

    private static final int TILE = WatchMaterials.TILE;

    @AfterEach
    void rebuild() {
        // Several of these install a pack sheet. Anything left behind would
        // reach every other test in the module through a static atlas.
        Skins.remove(WatchMaterial.GRASS.textureKey());
        Skins.clearCache();
        WatchMaterials.invalidate();
    }

    // --- the colour atlas is a detail map ------------------------------------------

    /**
     * <b>Every tile averages to mid-grey.</b>
     *
     * <p>The one invariant the whole encoding rests on, and the reason the two
     * backends agree about what colour anything is. The painter fills a
     * triangle with {@link WatchMaterials#shade}; the card multiplies that same
     * colour by this tile and by {@link MeshPass#DETAIL_GAIN}. Those two are
     * the same picture exactly when the tile's average is a half — and when it
     * is not, nothing fails, the world is simply drawn at the wrong exposure on
     * one backend and nobody can say why.
     */
    @Test
    void everyColourTileAveragesToTheMaterialItStandsFor() {
        BufferedImage atlas = WatchMaterials.atlas();
        for (WatchMaterial material : WatchMaterial.values()) {
            long[] sums = new long[3];
            forEachTexel(atlas, material, (argb, x, y) -> {
                sums[0] += (argb >> 16) & 0xFF;
                sums[1] += (argb >> 8) & 0xFF;
                sums[2] += argb & 0xFF;
            });
            long r = sums[0], g = sums[1], b = sums[2];
            int n = TILE * TILE;
            // 255 / DETAIL_GAIN — "this texel is exactly the material's own
            // colour", which is what the average of a detail tile has to be.
            double neutral = 255 / MeshPass.DETAIL_GAIN;
            for (long channel : new long[] {r / n, g / n, b / n}) {
                assertTrue(Math.abs(channel - neutral) <= 4,
                        material + "'s tile averages " + channel + " where a detail "
                                + "map has to average " + Math.round(neutral) + " — the "
                                + "card would draw it "
                                + Math.round(100 * channel / neutral) + "% of the "
                                + "colour the painter fills it with");
            }
        }
    }

    /**
     * <b>A map board's face is exactly white on both backends.</b>
     *
     * <p>{@link WatchMaterial#PAPER} is the one material whose tile has to be
     * nothing at all: a board carries a few thousand colours of its own on its
     * vertices, and any grain, tint or relief here would be a texture the card
     * multiplied into a map that the painter drew plain. It is also the one
     * material where "no grain" has to survive every generated flourish added
     * since — the hue drift, the saturation lift, the cavity — which is why
     * this asserts the pixels rather than trusting the switch.
     */
    @Test
    void theMapBoardsMaterialIsFlatWhiteAndMatte() {
        assertEquals(0xFFFFFF, WatchMaterials.shade(WatchMaterial.PAPER),
                "paper is not white, so a map board is tinted");
        forEachTexel(WatchMaterials.atlas(), WatchMaterial.PAPER, (argb, x, y) -> {
            for (int shift : new int[] {16, 8, 0}) {
                int channel = (argb >> shift) & 0xFF;
                assertTrue(Math.abs(channel - 128) <= 1,
                        "paper's detail tile has a " + channel + " in it at "
                                + x + "," + y + " — a map board would come out "
                                + "textured on the card and flat on the painter");
            }
        });
        forEachTexel(WatchMaterials.surface(), WatchMaterial.PAPER, (argb, x, y) -> {
            assertTrue(Math.abs(((argb >> 16) & 0xFF) - 128) <= 1
                            && Math.abs(((argb >> 8) & 0xFF) - 128) <= 1,
                    "paper has relief in its normal map at " + x + "," + y);
            assertTrue((argb & 0xFF) >= 250, "paper is not matte");
            assertEquals(0, argb >>> 24, "paper is a metal");
        });
    }

    // --- the surface atlas is a material ------------------------------------------

    /**
     * The surface atlas is the colour atlas's own grid, tile for tile — which
     * is what lets one set of texture coordinates address both.
     */
    @Test
    void bothAtlasesAreTheSameShapeAndMoveTogether() {
        BufferedImage colour = WatchMaterials.atlas();
        BufferedImage surface = WatchMaterials.surface();
        assertEquals(colour.getWidth(), surface.getWidth());
        assertEquals(colour.getHeight(), surface.getHeight());

        int revision = WatchMaterials.revision();
        assertEquals(revision, WatchMaterials.revision(), "asking twice rebuilt them");
        WatchMaterials.invalidate();
        assertTrue(WatchMaterials.revision() > revision,
                "a rescan did not bump the revision, so a backend holding the old "
                        + "surface atlas beside a new colour one would never re-upload");
    }

    /**
     * <b>What is in the surface atlas is the material.</b>
     *
     * <p>Roughness averages to the material's own, metalness <em>is</em> it to
     * the texel, and the normals average out flat — the last of which is the
     * one worth having: a normal map whose mean is not flat tilts every surface
     * in the game a few degrees, which does not look like a bug in a texture,
     * it looks like the sun being in the wrong place.
     */
    @Test
    void theSurfaceAtlasCarriesEachMaterialsOwnRoughnessAndMetal() {
        for (WatchMaterial material : WatchMaterial.values()) {
            double[] sums = new double[3];
            int[] count = {0};
            forEachTexel(WatchMaterials.surface(), material, (argb, x, y) -> {
                sums[0] += ((argb >> 16) & 0xFF) - 127.5;   // normal x
                sums[1] += ((argb >> 8) & 0xFF) - 127.5;    // normal y
                sums[2] += (argb & 0xFF) / 255.0;           // roughness
                assertEquals(Math.round(material.metalness() * 255), argb >>> 24,
                        material + " has a texel of the wrong metal in it");
                count[0]++;
            });
            int n = count[0];
            assertEquals(TILE * TILE, n, material + " has a hole in its tile");
            assertTrue(Math.abs(sums[0] / n) < 2 && Math.abs(sums[1] / n) < 2,
                    material + "'s normal map is tilted on average ("
                            + Math.round(sums[0] / n) + ", " + Math.round(sums[1] / n)
                            + "/128), so a flat quad of it does not face the way it "
                            + "is pointing");
            assertTrue(Math.abs(sums[2] / n - material.roughness()) < 0.06,
                    material + " is baked at roughness " + (sums[2] / n)
                            + " and declares " + material.roughness());
        }
    }

    /**
     * A material with relief in it has relief in it, and a smooth one has none
     * — the assertion that stops the whole normal map quietly becoming flat.
     */
    @Test
    void roughMaterialsHaveBumpsAndSmoothOnesDoNot() {
        double bark = reliefOf(WatchMaterial.BARK);
        double gravel = reliefOf(WatchMaterial.GRAVEL);
        double water = reliefOf(WatchMaterial.WATER);
        double paper = reliefOf(WatchMaterial.PAPER);
        assertTrue(bark > 8, "bark's normal map is nearly flat (" + bark + "/128)");
        assertTrue(gravel > 8, "gravel's normal map is nearly flat (" + gravel + ")");
        assertTrue(water < bark / 2,
                "still water is as bumpy as bark (" + water + " against " + bark + ")");
        // Half a byte, which is as flat as an eight-bit map gets: the exact
        // middle of 0–255 is 127.5 and the nearest byte to it is 128.
        assertTrue(paper <= 0.5, "paper has relief (" + paper + "/128)");
    }

    /** The mean absolute slope in a material's normal map, in bytes off flat. */
    private static double reliefOf(WatchMaterial material) {
        double[] sum = {0};
        int[] count = {0};
        forEachTexel(WatchMaterials.surface(), material, (argb, x, y) -> {
            sum[0] += Math.abs(((argb >> 16) & 0xFF) - 127.5)
                    + Math.abs(((argb >> 8) & 0xFF) - 127.5);
            count[0]++;
        });
        return sum[0] / (2 * count[0]);
    }

    // --- the tiles join up with themselves ----------------------------------------

    /**
     * <b>Every tile wraps.</b>
     *
     * <p>A tile is stretched across one two-metre quad of the heightfield and
     * then repeated across the whole world, so its left edge is always standing
     * next to its own right edge. A grain that does not wrap draws a line along
     * every quad boundary on the ground — a grid over the entire world, which
     * is far more obvious than any texture it was carrying.
     *
     * <p>Measured against the tile's own scale rather than against a constant:
     * opposite edges have to be more alike than two columns picked at random
     * out of the middle of it, which is a statement about the noise wrapping
     * and not about how strong the noise is. This is what caught bark, whose
     * lattice was stretched by scaling a coordinate — and so repeated at some
     * interval that had nothing to do with the tile's edge.
     */
    @Test
    void everyTileJoinsUpWithItself() {
        BufferedImage surface = WatchMaterials.surface();
        for (WatchMaterial material : WatchMaterial.values()) {
            if (material == WatchMaterial.PAPER) continue; // nothing to join
            int[][] tile = read(surface, material);
            double seam = (columnGap(tile, 0, TILE - 1) + rowGap(tile, 0, TILE - 1)) / 2;
            double inside = (columnGap(tile, TILE / 4, 3 * TILE / 4)
                    + rowGap(tile, TILE / 4, 3 * TILE / 4)) / 2;
            assertTrue(seam <= inside,
                    material + " does not tile: its opposite edges differ by " + seam
                            + " where two columns from the middle of it differ by "
                            + inside + ", so every quad edge in the world is a seam");
        }
    }

    private static double columnGap(int[][] tile, int a, int b) {
        double sum = 0;
        for (int y = 0; y < TILE; y++) sum += distance(tile[y][a], tile[y][b]);
        return sum / TILE;
    }

    private static double rowGap(int[][] tile, int a, int b) {
        double sum = 0;
        for (int x = 0; x < TILE; x++) sum += distance(tile[a][x], tile[b][x]);
        return sum / TILE;
    }

    private static double distance(int one, int two) {
        return Math.abs(((one >> 16) & 0xFF) - ((two >> 16) & 0xFF))
                + Math.abs(((one >> 8) & 0xFF) - ((two >> 8) & 0xFF));
    }

    // --- and a pack goes through all of it -----------------------------------------

    /**
     * <b>A texture pack's tile survives the encoding.</b>
     *
     * <p>The detail encoding is the one thing in this class a pack could break,
     * because a pack's tile is divided by <em>its own</em> average rather than
     * by the material's: get that backwards and a pack recolours the Java2D
     * build and leaves the GL one drawing the original palette, or worse, both
     * at once. So a sheet is installed, and both halves are checked — the flat
     * colour the painter fills with becomes the pack's, and the tile the card
     * samples still averages to neutral.
     *
     * <p>The normal map is checked too, and it is the part a creator gets for
     * free: a pack that ships a picture and no normal map has one derived from
     * the picture's own light, so dropping in a photograph of gravel gets
     * bumps that agree with the gravel in it.
     */
    @Test
    void aTexturePacksTileIsRenormalisedRatherThanIgnored() throws Exception {
        Path sheet = Files.createTempFile("watch-grass", ".png");
        BufferedImage art = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 32; y++) {
            for (int x = 0; x < 32; x++) {
                // A strong check pattern in an unmistakable purple, so neither
                // its colour nor its relief can be confused with the generated
                // tile it replaces.
                boolean pale = ((x / 4) + (y / 4)) % 2 == 0;
                art.setRGB(x, y, pale ? 0xFFB070E0 : 0xFF4A1C6A);
            }
        }
        ImageIO.write(art, "png", new File(sheet.toString()));
        try {
            Skins.put(new SkinDef(WatchMaterial.GRASS.textureKey(), sheet.toString(),
                    32, 32, 1, 0));
            Skins.clearCache();
            WatchMaterials.invalidate();

            int shade = WatchMaterials.shade(WatchMaterial.GRASS);
            int red = (shade >> 16) & 0xFF, green = (shade >> 8) & 0xFF;
            int blue = shade & 0xFF;
            assertTrue(blue > green + 30 && red > green + 20,
                    "the painter did not pick up the pack: grass is still "
                            + Integer.toHexString(shade));

            long[] sums = new long[3];
            int[] count = {0};
            forEachTexel(WatchMaterials.atlas(), WatchMaterial.GRASS, (argb, x, y) -> {
                sums[0] += (argb >> 16) & 0xFF;
                sums[1] += (argb >> 8) & 0xFF;
                sums[2] += argb & 0xFF;
                count[0]++;
            });
            for (long channel : sums) {
                assertTrue(Math.abs(channel / (double) count[0] - 127.5) <= 4,
                        "a packed tile averages " + channel / count[0]
                                + " rather than neutral, so the card would draw the "
                                + "pack's own colour on top of the pack's own colour");
            }
            assertTrue(reliefOf(WatchMaterial.GRASS) > 4,
                    "a pack that supplied no normal map got no relief at all, "
                            + "though the picture it did supply has edges in it");
        } finally {
            Files.deleteIfExists(sheet);
        }
    }

    // --- helpers -------------------------------------------------------------------

    private interface Texel {
        void at(int argb, int x, int y);
    }

    /** Walk one material's tile in either atlas, however the grid is laid out. */
    private static void forEachTexel(BufferedImage atlas, WatchMaterial material,
                                     Texel body) {
        int[][] tile = read(atlas, material);
        for (int y = 0; y < TILE; y++) {
            for (int x = 0; x < TILE; x++) body.at(tile[y][x], x, y);
        }
    }

    /**
     * One material's tile, read back through its own texture coordinates.
     *
     * <p>Through the UVs rather than by recomputing the grid, so that this
     * cannot agree with a broken layout: if {@code uv} and the bake ever
     * disagree about where a tile is, every assertion in this class is reading
     * somebody else's material and most of them would still pass.
     */
    private static int[][] read(BufferedImage atlas, WatchMaterial material) {
        float[] uv = new float[4];
        WatchMaterials.uv(material, uv);
        // The UVs are inset into the tile by a texel or two, so the corner is
        // found by snapping down to the grid rather than by knowing the inset —
        // which is the point of reading through the UVs at all.
        int ox = (int) Math.floor(uv[0] * atlas.getWidth() / TILE) * TILE;
        int oy = (int) Math.floor(uv[1] * atlas.getHeight() / TILE) * TILE;
        int[][] tile = new int[TILE][TILE];
        for (int y = 0; y < TILE; y++) {
            for (int x = 0; x < TILE; x++) tile[y][x] = atlas.getRGB(ox + x, oy + y);
        }
        return tile;
    }
}
