package com.larsons.engine.watch.world;

import com.larsons.engine.graphics.MeshPass;
import com.larsons.engine.graphics.Offscreen;
import com.larsons.engine.graphics.Skins;

import java.awt.image.BufferedImage;

/**
 * The two textures the whole world is drawn with, and the colours the CPU path
 * shades it by.
 *
 * <p><b>One atlas per channel, because the draw count is the wall.</b> A terrain
 * mesh that changed texture every time it changed material would be one draw
 * call per material per chunk; with every material in one image, a chunk is one
 * call and the material lives in the vertex's texture coordinates. That is the
 * same argument {@link com.larsons.engine.graphics.chunk.BlockAtlas} makes for
 * blocks, and this is the same shape of answer at a different scale — a few
 * dozen tiles rather than a few hundred.
 *
 * <h2>The colour atlas holds detail, not colour</h2>
 *
 * <p>{@link #atlas()} is a <b>detail</b> map: every tile is divided by its own
 * average, so mid-grey means "exactly this material's colour" and the tile
 * carries nothing but the variation around it. That is not a stylistic choice,
 * it is the only encoding under which the two backends draw the same world.
 *
 * <p>A card shades a fragment as {@code texture × vertexColour}, and every
 * mesher in {@code watch.render} bakes the material's <em>colour</em> into the
 * vertex — it has to, because the Java2D painter fills a flat polygon with that
 * colour and never looks at a texture at all. So an atlas holding the colour as
 * well meant the card multiplied the colour by itself: grass that the painter
 * drew at {@code 547E37} arrived on screen at {@code 1D400C}, a third as
 * bright and most of the way to black. Every surface in the game was affected
 * and the whole GL build read as muddy — which is the sort of bug that gets
 * described as "the lighting is too dark" and chased around a shader for a
 * week.
 *
 * <p>Dividing by the average fixes it in the one place where both paths can
 * agree: {@code detail × colour × light} is the painter's own answer with the
 * texture's variation on top, for a generated tile <em>and</em> for a texture
 * pack's, because {@link #shade} is the pack tile's average when a pack is
 * installed. See {@link MeshPass#DETAIL_GAIN} for the factor of two that lets a
 * texel be brighter than its tile's average in an eight-bit image.
 *
 * <h2>The surface atlas holds what the light does</h2>
 *
 * <p>{@link #surface()} is the same grid of tiles again, carrying the rest of
 * the material: a tangent-space normal in red and green, {@linkplain
 * WatchMaterial#roughness() roughness} in blue and {@linkplain
 * WatchMaterial#metalness() metalness} in alpha. Together with the detail map
 * that is a complete metal/roughness material, sampled per fragment by
 * {@code GlTerrainProgram}, which is what lets the lake hold a sun, the frost
 * glitter and a wet stone read as wet.
 *
 * <p><b>Both are derived from one height field per material</b>, and that is
 * what makes them look like a surface rather than like two lots of noise. The
 * bumps in the normal map are the same bumps the colour is shaded by and the
 * same ones the roughness varies with: a pit in the tile is dark, faces the way
 * the normal says, and is a little rougher than the crest beside it.
 *
 * <h2>Where a tile comes from</h2>
 *
 * <p>A texture pack's file for {@link WatchMaterial#textureKey()} if there is
 * one, else a generated tile — the material's colour with a grain over it whose
 * shape depends on what it is made of: rubble for gravel, grooves for bark,
 * ripples for sand, long swells for water. A pack may also supply
 * {@link WatchMaterial#normalKey()} and {@link WatchMaterial#surfaceKey()}; if
 * it does not, the normal is derived from whatever colour tile it did supply,
 * so a pack that replaces only the picture still gets relief that agrees with
 * the picture. The engine ships no image files (invariant 4) and this does not
 * change that.
 *
 * <p><b>{@link #shade} is the CPU path's whole use of all this.</b> Java2D
 * fills a polygon with a colour, not with a texture, and a per-pixel affine
 * texture map through {@code drawImage} would cost more than the entire rest of
 * the frame. So the CPU path asks for the material's <em>average</em> colour —
 * which is the material's own colour when no pack is installed, and the pack's
 * average when one is — and fills flat. A pack therefore recolours the Java2D
 * build and fully textures the GL one, from one set of files.
 */
public final class WatchMaterials {

    /**
     * Edge of one tile in the atlas, in pixels.
     *
     * <p>Doubled when the surface atlas arrived. A tile is stretched across one
     * two-metre quad of ground, so this is the resolution the world has when
     * you are standing on it: thirty-two texels was six centimetres each, which
     * is fine for a colour that is mostly one colour and far too coarse for a
     * normal map, where it reads as facets. Sixty-four is three centimetres,
     * and the whole grid is still under a megabyte per atlas.
     */
    public static final int TILE = 64;

    /** Tiles across the atlas; enough for every material with room to grow. */
    private static final int COLUMNS = 8;

    /** …and down it. */
    private static final int ROWS =
            (WatchMaterial.values().length + COLUMNS - 1) / COLUMNS;

    /**
     * Texels of a tile kept off each edge when a UV is handed out, so a filter
     * at a tile's border cannot bleed its neighbour in.
     *
     * <p>Raised from half a texel to one and a half, because the surface atlas
     * is filtered <b>linearly</b> where the colour one is not. A normal map
     * sampled nearest is a field of flat facets — every bump in the game with a
     * staircase around it — and a linear sample half a texel from the edge is
     * half somebody else's tile. At one and a half texels the sample lands
     * exactly on the centre of the second texel in, which is inside the tile
     * for either filter and is the same answer for both.
     */
    private static final float INSET = 1.5f;

    /**
     * The lowest roughness any texel may have.
     *
     * <p>A perfectly smooth surface concentrates the sun into a highlight
     * smaller than a pixel, and what that looks like in motion is not a mirror
     * but a fragment that flickers on and off as you walk past it. Water is the
     * smoothest thing in the game and sits a little above this.
     */
    public static final float MIN_ROUGHNESS = 0.045f;

    /**
     * How far a texel's roughness moves with the relief under it.
     *
     * <p>Pits rougher than crests, which is what weathering does: the top of a
     * cobble is polished by everything that has walked on it and the gap beside
     * it is full of grit. It costs nothing — the height field is already there
     * — and it is most of why a rough material stops reading as one flat sheen.
     */
    private static final double ROUGHNESS_SPREAD = 0.26;

    /**
     * How steep the normal map's slopes are at full {@linkplain
     * WatchMaterial#relief() relief}.
     *
     * <p>An artistic scale rather than a derived one: the honest gradient of a
     * height field measured across a tile is enormous (a two-metre quad
     * carrying sixty-four texels of noise), and the number that matters is how
     * much the light is allowed to swing between one texel and the next. High
     * enough that gravel reads as gravel, low enough that the slopes are not
     * clipping at right angles across most of the tile — a normal map that
     * saturates is a normal map with flat facets in it, which is the thing it
     * was brought in to remove.
     */
    private static final double BUMP = 2.4;

    /** How much of a tile's colour swings with its own relief. */
    private static final double CONTRAST = 0.15;

    /** …and how much darker the bottom of a pit is than the top of a crest. */
    private static final double CAVITY = 0.16;

    /**
     * How far a tile's colour is allowed to drift in hue across its own face.
     *
     * <p><b>The difference between a texture and a wobbled brightness.</b> A
     * hillside of one green with noise in its value is one green; real grass is
     * yellow where it is dry and blue-green where it is deep, and drifting the
     * two ends apart by a tenth is enough for the eye to read it as a surface
     * made of things rather than a surface made of paint.
     */
    private static final double HUE_DRIFT = 0.13;

    /**
     * How much the generated tiles are saturated over the flat palette.
     *
     * <p>Small, and applied to the tile rather than to {@link WatchMaterial}'s
     * own numbers so that the palette stays the readable, slightly muted set
     * the guide's pages and the map are drawn from, while the ground you are
     * standing on has some blood in it. Both backends get it, because both
     * backends read {@link #shade}.
     */
    private static final double SATURATE = 1.16;

    private static BufferedImage atlas;
    private static BufferedImage surface;
    private static int revision;
    private static int[] average;

    private WatchMaterials() {}

    /**
     * The colour atlas, built on first use — <b>a detail map, not an albedo
     * map.</b> See the class note.
     *
     * <p>Synchronised because chunk meshing runs on the streamer's workers and
     * every one of them asks for UVs; the build itself happens once.
     */
    public static synchronized BufferedImage atlas() {
        if (atlas == null) rebuild();
        return atlas;
    }

    /**
     * The surface atlas: {@code rg} a tangent-space normal, {@code b}
     * roughness, {@code a} metalness, tile for tile with {@link #atlas()} and
     * sampled by the same UVs.
     */
    public static synchronized BufferedImage surface() {
        if (surface == null) rebuild();
        return surface;
    }

    /**
     * How many times the atlases have been rebuilt. A renderer that has
     * uploaded them compares this rather than the images, so a texture pack
     * rescan re-uploads and an ordinary frame does not.
     */
    public static synchronized int revision() {
        if (atlas == null) rebuild();
        return revision;
    }

    /**
     * Throw the atlases away, so the next frame picks up a texture pack that
     * has just been installed or rescanned.
     */
    public static synchronized void invalidate() {
        atlas = null;
        surface = null;
        average = null;
    }

    /**
     * The flat colour {@code material} is drawn in — the average of its tile,
     * so a pack's grass shades the Java2D build too.
     *
     * <p>This is also the number the detail atlas is divided by, which is what
     * makes {@code detail × shade} come back to the tile the bake actually
     * painted. See the class note.
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
        float insetU = INSET / (TILE * COLUMNS);
        float insetV = INSET / (TILE * ROWS);
        out[0] = col / (float) COLUMNS + insetU;
        out[1] = row / (float) ROWS + insetV;
        out[2] = (col + 1) / (float) COLUMNS - insetU;
        out[3] = (row + 1) / (float) ROWS - insetV;
    }

    /** The centre of {@code material}'s tile — what an untextured face uses. */
    public static void centreUv(WatchMaterial material, float[] out) {
        float[] box = new float[4];
        uv(material, box);
        out[0] = (box[0] + box[2]) / 2;
        out[1] = (box[1] + box[3]) / 2;
    }

    // --- the bake -------------------------------------------------------------------

    private static void rebuild() {
        WatchMaterial[] all = WatchMaterial.values();
        BufferedImage colours = new BufferedImage(COLUMNS * TILE, ROWS * TILE,
                BufferedImage.TYPE_INT_ARGB);
        BufferedImage surfaces = new BufferedImage(COLUMNS * TILE, ROWS * TILE,
                BufferedImage.TYPE_INT_ARGB);
        int[] shades = new int[all.length];
        int[] tile = new int[TILE * TILE];
        float[] height = new float[TILE * TILE];

        for (int i = 0; i < all.length; i++) {
            WatchMaterial material = all[i];
            int ox = (i % COLUMNS) * TILE;
            int oy = (i / COLUMNS) * TILE;
            paintColour(material, tile, height);
            shades[i] = averageOf(tile);
            writeDetail(colours, ox, oy, tile, shades[i]);
            writeSurface(surfaces, ox, oy, material, height);
        }
        atlas = colours;
        surface = surfaces;
        average = shades;
        revision++;
    }

    /**
     * One material's colour tile and the height field under it.
     *
     * <p>Two answers from one call because the second is derived from the
     * first whenever a pack supplied the first: a pack that ships a photograph
     * of gravel and no normal map still gets relief, because the light in that
     * photograph <em>is</em> a height field to within a constant. Only a
     * material with no pack tile at all gets the generated field, and then the
     * colour is painted from the field rather than the other way round.
     */
    private static void paintColour(WatchMaterial material, int[] tile, float[] height) {
        BufferedImage packed = packTile(material.textureKey());
        if (packed == null) {
            generate(material, tile, height);
            return;
        }
        scaleInto(packed, tile);
        for (int i = 0; i < tile.length; i++) {
            int argb = tile[i];
            // Rec. 709 luma, because a height taken off a plain channel average
            // reads a red rock as deeper than a green one.
            height[i] = (float) ((0.2126 * ((argb >> 16) & 0xFF)
                    + 0.7152 * ((argb >> 8) & 0xFF)
                    + 0.0722 * (argb & 0xFF)) / 255.0);
        }
    }

    /** A pack's (or the player's) sheet for a key, if there is one. */
    private static BufferedImage packTile(String key) {
        try {
            return Skins.frame(key, 0);
        } catch (RuntimeException e) {
            return null; // a broken sheet is a missing sheet, not a crash
        }
    }

    /** Any image, resampled to one tile, as {@code 0xAARRGGBB}. */
    private static void scaleInto(BufferedImage source, int[] tile) {
        BufferedImage scratch = new BufferedImage(TILE, TILE,
                BufferedImage.TYPE_INT_ARGB);
        try (Offscreen bake = Offscreen.over(scratch, true)) {
            bake.target().drawImage(source, 0, 0, TILE, TILE);
        }
        scratch.getRGB(0, 0, TILE, TILE, tile, 0, TILE);
    }

    /**
     * The generated tile: the material's colour over its own grain.
     *
     * <p>The height field comes first and the colour is painted from it, which
     * is the whole reason the grain and the bumps agree. What varies per
     * material is the <em>shape</em> of that field — see {@link Grain} — and
     * how hard the colour is driven by it.
     */
    private static void generate(WatchMaterial material, int[] tile, float[] height) {
        Grain grain = grainOf(material);
        long seed = material.key().hashCode() * 0x9E3779B97F4A7C15L;
        float[] tones = new float[TILE * TILE];
        for (int y = 0; y < TILE; y++) {
            for (int x = 0; x < TILE; x++) {
                double u = (x + 0.5) / TILE, v = (y + 0.5) / TILE;
                height[y * TILE + x] = (float) clamp01(grain.at(u, v, seed));
                // A second, much broader field for the hue, so the colour
                // drifts in patches the size of a footprint while the relief is
                // per texel.
                tones[y * TILE + x] =
                        (float) Grain.smooth(u, v, 3, seed ^ 0x5DEECE66DL);
            }
        }
        centre(height);
        centre(tones);

        int base = saturated(material.rgb());
        // The two ends the tile drifts between. Warm is toward red and away
        // from blue, cool the other way — a rotation of the hue rather than a
        // change of brightness, which is what stops this reading as more noise.
        //
        // Off the grain's own contrast, so that a material with no grain has no
        // drift either: PAPER has to come out of here exactly white or a map
        // board is tinted on one backend and not the other. See WatchMaterial.
        double drift = HUE_DRIFT * grain.contrast();
        int warm = tinted(base, 1 + drift, 1, 1 - drift * 0.8);
        int cool = tinted(base, 1 - drift * 0.7, 1, 1 + drift);
        double contrast = CONTRAST * grain.contrast();
        double cavity = CAVITY * material.relief();

        for (int y = 0; y < TILE; y++) {
            for (int x = 0; x < TILE; x++) {
                int at = y * TILE + x;
                double h = height[at];
                int hue = mix(cool, warm, tones[at]);
                // The tile's own light: a crest catches more than the flat and
                // a pit keeps less of the sky, both off the one height field.
                // The cavity term is divided by its own average so that it
                // shades the tile without dimming the material — a texture that
                // changed what colour a thing is would put the two backends
                // back out of step, which is the whole thing this class is
                // arranged to avoid.
                double lit = (1 + contrast * (h - 0.5) * 2)
                        * (1 - cavity * (1 - h)) / (1 - cavity * 0.5);
                // Opaque, even for water: see writeDetail on where this world's
                // transparency actually lives.
                tile[at] = 0xFF000000
                        | (clamp(((hue >> 16) & 0xFF) * lit) << 16)
                        | (clamp(((hue >> 8) & 0xFF) * lit) << 8)
                        | clamp((hue & 0xFF) * lit);
            }
        }
    }

    /**
     * The tile, divided by its own average and halved — the encoding the whole
     * class note is about.
     *
     * <p>Halved because an eight-bit texture cannot hold a number above one and
     * a texel <em>above</em> its tile's average is the interesting half of a
     * detail map. Mid-grey is therefore "exactly the average", and the shader
     * multiplies by {@link MeshPass#DETAIL_GAIN} to put it back.
     *
     * <p><b>Alpha is passed through untouched</b>, and for the generated tiles
     * that means fully opaque even for water. Transparency in this world lives
     * on the vertex — {@code TerrainMesher.waterColour} decides how much you
     * can see through a lake by how deep it is — and a tile that dimmed it
     * again would be the same double-multiply this encoding exists to remove.
     * A pack's cutout is its own business and survives.
     */
    private static void writeDetail(BufferedImage image, int ox, int oy, int[] tile,
                                    int shade) {
        double gain = MeshPass.DETAIL_GAIN;
        double r = Math.max(1, (shade >> 16) & 0xFF) * gain;
        double g = Math.max(1, (shade >> 8) & 0xFF) * gain;
        double b = Math.max(1, shade & 0xFF) * gain;
        for (int y = 0; y < TILE; y++) {
            for (int x = 0; x < TILE; x++) {
                int argb = tile[y * TILE + x];
                int detail = (argb & 0xFF000000)
                        | (clamp(((argb >> 16) & 0xFF) * 255 / r) << 16)
                        | (clamp(((argb >> 8) & 0xFF) * 255 / g) << 8)
                        | clamp((argb & 0xFF) * 255 / b);
                image.setRGB(ox + x, oy + y, detail);
            }
        }
    }

    /**
     * The material's other half: which way each texel faces, how rough it is
     * and whether it is a metal.
     *
     * <p>The normal is a central difference of the height field, wrapped at the
     * tile's edges so the pattern joins up with itself, and scaled by the
     * material's own {@linkplain WatchMaterial#relief() relief}. Only two
     * channels of it are stored: a tangent-space normal is a unit vector, so
     * the third is {@code sqrt(1 − x² − y²)} and the card can have it for one
     * instruction rather than a third of the texture's bandwidth.
     */
    private static void writeSurface(BufferedImage image, int ox, int oy,
                                     WatchMaterial material, float[] height) {
        int[] normals = new int[TILE * TILE];
        BufferedImage packedNormal = packTile(material.normalKey());
        if (packedNormal != null) scaleInto(packedNormal, normals);

        int[] surfaces = new int[TILE * TILE];
        BufferedImage packedSurface = packTile(material.surfaceKey());
        if (packedSurface != null) scaleInto(packedSurface, surfaces);

        double slope = BUMP * material.relief();
        double rough = material.roughness();
        int metal = Math.round(material.metalness() * 255);

        for (int y = 0; y < TILE; y++) {
            for (int x = 0; x < TILE; x++) {
                int at = y * TILE + x;
                int nx, ny;
                if (packedNormal != null) {
                    nx = (normals[at] >> 16) & 0xFF;
                    ny = (normals[at] >> 8) & 0xFF;
                } else {
                    // −∂h/∂u and −∂h/∂v: the way a heightfield's surface tips.
                    double dx = height[wrap(x + 1) + y * TILE]
                            - height[wrap(x - 1) + y * TILE];
                    double dy = height[x + wrap(y + 1) * TILE]
                            - height[x + wrap(y - 1) * TILE];
                    nx = encode(-dx * slope);
                    ny = encode(-dy * slope);
                }
                int r, m;
                if (packedSurface != null) {
                    r = (surfaces[at] >> 16) & 0xFF;
                    m = (surfaces[at] >> 8) & 0xFF;
                } else {
                    // Rougher in the hollows than on the crests. See
                    // ROUGHNESS_SPREAD.
                    double varied = rough + ROUGHNESS_SPREAD * material.relief()
                            * (0.5 - height[at]);
                    r = (int) Math.round(255 * Math.max(MIN_ROUGHNESS,
                            Math.min(1, varied)));
                    m = metal;
                }
                image.setRGB(ox + x, oy + y, (m << 24) | (nx << 16) | (ny << 8) | r);
            }
        }
    }

    /**
     * Shift a field so its own average is a half.
     *
     * <p><b>A grain is relief, and must not also be a brightness.</b> The
     * shapes in {@link Grain} do not all average to the same thing — a cellular
     * field is mostly stone with a few cracks in it and sits well above a half,
     * and a lattice with three cells across it can land anywhere — so a tile
     * painted straight off one came out a different colour from the material it
     * was supposed to be, and the palette drifted with it: gravel a quarter
     * brighter, and every near-grey warmed, than the colour the guide's own
     * page draws them in.
     */
    private static void centre(float[] field) {
        double sum = 0;
        for (float v : field) sum += v;
        double lift = 0.5 - sum / field.length;
        if (Math.abs(lift) < 1e-4) return;
        for (int i = 0; i < field.length; i++) {
            field[i] = (float) clamp01(field[i] + lift);
        }
    }

    /** A signed slope, clamped, as an unsigned byte around {@code 128}. */
    private static int encode(double slope) {
        double clamped = Math.max(-1, Math.min(1, slope));
        return (int) Math.round(127.5 + 127.5 * clamped);
    }

    /** An index folded back into the tile, so the grain joins up with itself. */
    private static int wrap(int i) {
        return i < 0 ? i + TILE : i >= TILE ? i - TILE : i;
    }

    private static int averageOf(int[] tile) {
        long r = 0, g = 0, b = 0;
        for (int argb : tile) {
            r += (argb >> 16) & 0xFF;
            g += (argb >> 8) & 0xFF;
            b += argb & 0xFF;
        }
        int n = tile.length;
        return (int) ((r / n) << 16 | (g / n) << 8 | (b / n));
    }

    /** A colour pushed away from its own grey, keeping its brightness. */
    private static int saturated(int rgb) {
        double r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        double grey = 0.2126 * r + 0.7152 * g + 0.0722 * b;
        return (clamp(grey + (r - grey) * SATURATE) << 16)
                | (clamp(grey + (g - grey) * SATURATE) << 8)
                | clamp(grey + (b - grey) * SATURATE);
    }

    /** A colour with its three channels scaled. */
    private static int tinted(int rgb, double r, double g, double b) {
        return (clamp(((rgb >> 16) & 0xFF) * r) << 16)
                | (clamp(((rgb >> 8) & 0xFF) * g) << 8)
                | clamp((rgb & 0xFF) * b);
    }

    private static int mix(int a, int b, double t) {
        return (clamp(((a >> 16) & 0xFF) + (((b >> 16) & 0xFF) - ((a >> 16) & 0xFF)) * t) << 16)
                | (clamp(((a >> 8) & 0xFF) + (((b >> 8) & 0xFF) - ((a >> 8) & 0xFF)) * t) << 8)
                | clamp((a & 0xFF) + ((b & 0xFF) - (a & 0xFF)) * t);
    }

    private static int clamp(double v) {
        return (int) Math.max(0, Math.min(255, Math.round(v)));
    }

    private static double clamp01(double v) {
        return v < 0 ? 0 : Math.min(v, 1);
    }

    /**
     * What a material's grain is <em>shaped</em> like.
     *
     * <p>One noise function over every surface in the game would make gravel
     * and bark and open water look like three tints of the same fabric. These
     * are five shapes, each a few lines of arithmetic over the same tileable
     * value noise, and picking the right one per material is most of the
     * difference between a texture and a dither.
     *
     * <p>Every one of them is <b>periodic in the tile</b> — the lattice
     * coordinates are taken modulo the number of cells across — so a tile can
     * be repeated without a seam, which is what the ground does every two
     * metres.
     */
    private enum Grain {

        /** Fine speckle over a broad swell: soil, grass, moss, leaves. */
        SPECKLE(1.0) {
            @Override double at(double u, double v, long seed) {
                return 0.55 * smooth(u, v, 16, seed)
                        + 0.30 * smooth(u, v, 8, seed ^ 0x51ED270B)
                        + 0.15 * smooth(u, v, 4, seed ^ 0x2545F491);
            }
        },

        /**
         * Stones packed against each other: gravel, rock, the trodden path.
         *
         * <p>A cellular field rather than a sum of noises, because rubble is
         * not noisy, it is <em>made of things</em> — and the thing the eye
         * reads is the crack between two of them, which is exactly what the
         * distance to the second-nearest scattered point gives.
         */
        RUBBLE(0.85) {
            @Override double at(double u, double v, long seed) {
                double stones = cells(u, v, 7, seed);
                return 0.78 * stones + 0.22 * smooth(u, v, 16, seed ^ 0x9E3779B9L);
            }
        },

        /**
         * Grooves running one way: bark, planks, thatch, rope.
         *
         * <p>A lattice stretched eight to one and then folded about its middle,
         * which turns a blur into ridges. A trunk is the one thing in a wood
         * whose texture has a direction, and it is the direction it grew in.
         */
        STRIA(1.05) {
            @Override double at(double u, double v, long seed) {
                double ridged = 1 - Math.abs(2 * smooth(u, v, 24, 3, seed) - 1);
                return 0.62 * ridged + 0.26 * smooth(u, v, 12, 2, seed ^ 0x7FEB352DL)
                        + 0.12 * smooth(u, v, 20, 20, seed ^ 0x846CA68BL);
            }
        },

        /** Wind ripples with grit in them: sand, snow, a sandy trail. */
        RIPPLE(0.85) {
            @Override double at(double u, double v, long seed) {
                double wave = 0.5 + 0.5 * Math.sin(2 * Math.PI * (3 * v + u
                        + 0.35 * smooth(u, v, 4, seed)));
                return 0.45 * wave + 0.35 * smooth(u, v, 20, seed ^ 0x165667B1L)
                        + 0.20 * smooth(u, v, 6, seed ^ 0x27D4EB2FL);
            }
        },

        /** Long, low swells: water, ice, glass, crystal, a petal. */
        SWELL(0.55) {
            @Override double at(double u, double v, long seed) {
                return 0.70 * smooth(u, v, 3, seed)
                        + 0.30 * smooth(u, v, 6, seed ^ 0x85EBCA6BL);
            }
        },

        /**
         * Nothing at all — {@link WatchMaterial#PAPER}, and the reason the
         * enum has a member that does no work.
         *
         * <p>A map board's face carries a few thousand little colours of its
         * own on the vertices, so its tile has to be exactly flat: any grain
         * here would be a texture pack quietly tinting every map in the game.
         */
        FLAT(0) {
            @Override double at(double u, double v, long seed) { return 0.5; }
        };

        private final double contrast;

        Grain(double contrast) {
            this.contrast = contrast;
        }

        /** How hard this grain drives the tile's colour, relative to the rest. */
        double contrast() { return contrast; }

        /** The field at a point in the tile, {@code 0}–{@code 1}. */
        abstract double at(double u, double v, long seed);

        /** Tileable value noise on a square lattice of {@code cells}. */
        static double smooth(double u, double v, int cells, long seed) {
            return smooth(u, v, cells, cells, seed);
        }

        /**
         * Tileable value noise: a lattice of random values, smoothstepped
         * between, wrapping at {@code cellsU} across and {@code cellsV} down.
         *
         * <p><b>Two counts rather than a scale on the coordinate</b>, and the
         * difference is whether the tile joins up with itself. Stretching a
         * square lattice by multiplying {@code u} leaves the pattern repeating
         * at some interval that is nothing to do with the tile's edge, so bark
         * came out with a visible line down every trunk; asking for a lattice
         * that is twenty-four wide and three deep stretches the same noise and
         * still lands on a whole cell at the seam.
         */
        static double smooth(double u, double v, int cellsU, int cellsV, long seed) {
            double x = u * cellsU, y = v * cellsV;
            int x0 = (int) Math.floor(x), y0 = (int) Math.floor(y);
            double fx = x - x0, fy = y - y0;
            double sx = fx * fx * (3 - 2 * fx), sy = fy * fy * (3 - 2 * fy);
            double a = lattice(x0, y0, cellsU, cellsV, seed);
            double b = lattice(x0 + 1, y0, cellsU, cellsV, seed);
            double c = lattice(x0, y0 + 1, cellsU, cellsV, seed);
            double d = lattice(x0 + 1, y0 + 1, cellsU, cellsV, seed);
            double top = a + (b - a) * sx;
            return top + ((c + (d - c) * sx) - top) * sy;
        }

        /**
         * Stones with cracks between them: how much further the
         * second-nearest scattered point is than the nearest.
         *
         * <p><b>The difference of the two distances, not the first on its
         * own.</b> The nearest distance alone is a field of dark spots — it is
         * zero <em>at</em> each seed and largest between them, which paints
         * rubble as polka dots with pale mortar, exactly backwards. The
         * difference is zero on the line equidistant from two seeds, which is
         * the crack between two stones, and rises to the middle of each, which
         * is the stone. One more comparison in the same loop.
         */
        static double cells(double u, double v, int cells, long seed) {
            double x = u * cells, y = v * cells;
            int x0 = (int) Math.floor(x), y0 = (int) Math.floor(y);
            double nearest = 8, second = 8;
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    int cx = x0 + dx, cy = y0 + dy;
                    double px = cx + lattice(cx, cy, cells, cells, seed);
                    double py = cy + lattice(cx, cy, cells, cells, seed ^ 0xD1B54A32L);
                    double ex = px - x, ey = py - y;
                    double d = Math.sqrt(ex * ex + ey * ey);
                    if (d < nearest) {
                        second = nearest;
                        nearest = d;
                    } else if (d < second) {
                        second = d;
                    }
                }
            }
            return clamp01((second - nearest) * 1.6);
        }

        /** One lattice value in {@code [0, 1)}, folded into the tile. */
        private static double lattice(int x, int y, int cellsU, int cellsV, long seed) {
            long h = seed
                    ^ (Math.floorMod(x, cellsU) * 0x9E3779B97F4A7C15L)
                    ^ (Math.floorMod(y, cellsV) * 0xC2B2AE3D27D4EB4FL);
            h ^= h >>> 29;
            h *= 0xBF58476D1CE4E5B9L;
            h ^= h >>> 32;
            h *= 0x94D049BB133111EBL;
            h ^= h >>> 31;
            return (h >>> 11) * 0x1.0p-53;
        }
    }

    /** Which grain a material is made of. */
    private static Grain grainOf(WatchMaterial material) {
        return switch (material) {
            case GRAVEL, ROCK, DARK_ROCK, STONE_BLOCK, TRAIL_STONE -> Grain.RUBBLE;
            case BARK, PALE_BARK, DARK_BARK, PLANK, THATCH, ROPE, BAMBOO,
                 TRAIL_BOARDWALK -> Grain.STRIA;
            case SAND, RED_SAND, TRAIL_SAND, SNOW -> Grain.RIPPLE;
            case WATER, SHALLOWS, ICE, GLASSPANE, CRYSTAL, PETAL, BERRY, CAP -> Grain.SWELL;
            // Fine and soft, and quieter than the ground: a bird is a few
            // centimetres of tile and its colour is on its vertices.
            case PELT -> Grain.SPECKLE;
            case PAPER -> Grain.FLAT;
            default -> Grain.SPECKLE;
        };
    }
}
