package com.larsons.engine.watch.world;

import java.awt.Color;

/**
 * What a patch of ground — or a trunk, a leaf, a plank — is made of.
 *
 * <p><b>A material is a colour, a surface, and a texture key.</b> The terrain
 * here is a triangle mesh rather than a grid of blocks, so there is no
 * {@code Block} to hang a texture off: a vertex carries a colour and a pair of
 * texture coordinates, and the coordinates point into an atlas with one tile
 * per member of this enum ({@link WatchMaterials#atlas()}). That is what makes
 * "low-poly terrain that can have custom textures" a single mechanism rather
 * than two — the same tile is sampled by the GPU path per fragment and averaged
 * by the CPU path per triangle, so a texture pack that replaces
 * {@code watch/terrain/grass} changes both.
 *
 * <p>The colours are the fallback and the CPU path's normal case: a flat-shaded
 * low-poly world reads by its palette, not by its texels, and a machine drawing
 * through Java2D should not be paying for a texture lookup it cannot afford.
 *
 * <h2>The three numbers after the colour</h2>
 *
 * <p>A colour on its own says what a surface <em>is</em> and nothing about how
 * it answers a light, which is why a world lit only by a diffuse term comes out
 * looking like painted card however good the palette is. Water and wet rock and
 * a beetle's shell are all "shiny" in ways a Lambert term cannot express at all:
 * they have a highlight, it moves with the eye rather than with the surface, and
 * it is the single strongest cue that a thing is made of something.
 *
 * <p>So every material also carries the standard metal/roughness pair, plus how
 * much relief its tile has:
 *
 * <ul>
 *   <li>{@link #roughness()} — how spread out that highlight is. Near zero is a
 *       mirror (still water, ice); one is chalk, and the highlight is so broad
 *       it is indistinguishable from the ambient. Everything in a wood is
 *       somewhere in the top half of this range, which is why the few things
 *       that are not — the lake, the frost, a wet stone — carry the whole
 *       scene.</li>
 *   <li>{@link #metalness()} — whether the surface tints its reflection with
 *       its own colour and has no diffuse at all (a metal), or reflects white
 *       and keeps its colour underneath (everything that grew). Almost
 *       everything here is the second; {@link #CRYSTAL} is what the channel is
 *       for.</li>
 *   <li>{@link #relief()} — how deep the bumps in its own tile are, which is
 *       what {@link WatchMaterials} turns into a normal map. Gravel is rubble
 *       and bark is grooves; still water has none at all.</li>
 * </ul>
 *
 * <p>These are <b>descriptions of a surface</b> rather than instructions to a
 * shader, on exactly the terms {@link com.larsons.engine.graphics.MeshPass.Sky}
 * describes an environment: a backend with a card spends a full specular lobe on
 * them per light per fragment, and the Java2D painter ignores them completely
 * and keeps the flat fill it can afford. Neither of them is wrong about the
 * world; one of them can afford more of it.
 *
 * @see WatchMaterials for the atlases, the pack lookup and the shading helpers
 */
public enum WatchMaterial {

    // --- ground ---------------------------------------------------------------
    //
    // key                  colour    rough  metal  relief

    GRASS("grass", 0x5C8A3C, 0.86, 0, 0.55),
    LUSH_GRASS("lush_grass", 0x3F7A2E, 0.82, 0, 0.62),
    DRY_GRASS("dry_grass", 0xA8974E, 0.88, 0, 0.50),
    TUNDRA_GRASS("tundra_grass", 0x77855E, 0.90, 0, 0.46),
    MOSS("moss", 0x4A6B33, 0.94, 0, 0.72),
    DIRT("dirt", 0x6B5236, 0.90, 0, 0.44),
    CLAY("clay", 0x9A6A48, 0.70, 0, 0.28),
    SAND("sand", 0xD9C68A, 0.80, 0, 0.34),
    RED_SAND("red_sand", 0xB4703F, 0.82, 0, 0.34),
    GRAVEL("gravel", 0x8A8578, 0.78, 0, 0.90),
    ROCK("rock", 0x7A7A78, 0.66, 0, 0.78),
    DARK_ROCK("dark_rock", 0x53514F, 0.60, 0, 0.84),
    /**
     * Snow, and the one loose material with a real highlight.
     *
     * <p>Fresh snow is a field of little mirrors, which is why a slope in low
     * sun glitters and why it is the material that most obviously reads as
     * <em>plastic</em> if it is given a matte roughness and nothing else.
     */
    SNOW("snow", 0xE8EDF2, 0.40, 0, 0.24),
    ICE("ice", 0xB8D8E6, 0.10, 0, 0.12),
    ASH("ash", 0x565049, 0.96, 0, 0.40),
    /**
     * Crystal — the one thing in this world that reflects with its own colour.
     *
     * <p>The metal channel exists for this. Everything else here grew or was
     * weathered and reflects the sky white, keeping its colour underneath; a
     * crystal face tints what it throws back, which is the difference between
     * a purple rock and a gem.
     */
    CRYSTAL("crystal", 0x9C86D8, 0.14, 0.55, 0.30),
    PEAT("peat", 0x4A3B2E, 0.88, 0, 0.56),

    // --- trails ---------------------------------------------------------------

    TRAIL_EARTH("trail_earth", 0x8A6E4B, 0.86, 0, 0.40),
    TRAIL_STONE("trail_stone", 0x9A968C, 0.64, 0, 0.56),
    TRAIL_SAND("trail_sand", 0xCBB479, 0.82, 0, 0.30),
    TRAIL_BOARDWALK("trail_boardwalk", 0x8A6B44, 0.72, 0, 0.62),

    // --- water ----------------------------------------------------------------

    /**
     * Open water: the smoothest thing in the game, and what most of this
     * exists for.
     *
     * <p>A lake with no specular term is a sheet of blue-grey card, whatever
     * you do to its colour. With one it carries the sky, the sun sits on it as
     * a glare that moves when you do, and its edge goes bright where the eye
     * grazes it — none of which is a texture, and all of which is one number
     * here and a Fresnel term on the card.
     */
    WATER("water", 0x2E6E8E, 0.055, 0, 0.10),
    SHALLOWS("shallows", 0x4C93A8, 0.10, 0, 0.16),

    // --- flora ----------------------------------------------------------------

    BARK("bark", 0x6B4E32, 0.85, 0, 0.92),
    PALE_BARK("pale_bark", 0xC9C3B4, 0.80, 0, 0.76),
    DARK_BARK("dark_bark", 0x453425, 0.86, 0, 0.96),
    /**
     * A leaf, which is waxed and shines — <b>not chalk.</b>
     *
     * <p>Foliage is the largest area of colour in this game and the easiest to
     * get wrong: at full roughness a canopy is a flat green mass, and the
     * moment it is allowed a broad highlight the top of a tree catches the sun
     * and the underside does not, which is what a wood looks like.
     */
    LEAF("leaf", 0x3E7A34, 0.54, 0, 0.36),
    PINE_NEEDLE("pine_needle", 0x2C5A3A, 0.50, 0, 0.30),
    AUTUMN_LEAF("autumn_leaf", 0xC07428, 0.58, 0, 0.36),
    PURPLE_LEAF("purple_leaf", 0x8A5AC0, 0.54, 0, 0.36),
    PALM_FROND("palm_frond", 0x4E8F45, 0.46, 0, 0.42),
    BAMBOO("bamboo", 0x8FA84C, 0.44, 0, 0.34),
    CAP("cap", 0xC05A55, 0.60, 0, 0.28),
    PETAL("petal", 0xE0C24C, 0.56, 0, 0.20),
    BERRY("berry", 0xB03448, 0.28, 0, 0.14),

    // --- living ---------------------------------------------------------------

    /**
     * Fur, feather, hide and wool — <b>the surface of everything alive.</b>
     *
     * <p>Every animal in the game is drawn from one tile of this atlas, because
     * what a flat-shaded animal reads by is the colour on its vertices (from its
     * skin sheet) and not its texels. That tile used to be {@link #BARK}'s,
     * chosen when a tile was a noise pattern and nothing else. It is not a noise
     * pattern any more: it says what the light does, and bark says "deeply
     * grooved, and dry" — which is a fair description of an oak and a poor one
     * of a wren.
     *
     * <p>So there is a material for the living things: a fine soft grain,
     * shallow relief, and enough of a sheen to catch the low sun the way a
     * feathered back does. It carries no colour of its own worth speaking of,
     * for the same reason {@link #PAPER} does not.
     */
    PELT("pelt", 0xB5A48E, 0.68, 0, 0.30),

    // --- built ----------------------------------------------------------------

    PLANK("plank", 0xA37C4C, 0.70, 0, 0.58),
    THATCH("thatch", 0xC0A45E, 0.88, 0, 0.84),
    ROPE("rope", 0xB9A276, 0.90, 0, 0.88),
    STONE_BLOCK("stone_block", 0x8C8880, 0.68, 0, 0.52),
    GLASSPANE("glasspane", 0xA9D6E0, 0.06, 0, 0.04),

    /**
     * Paper: the face of a map board, and the one material here that carries no
     * colour, no relief and no shine of its own.
     *
     * <p><b>White, grainless and matte</b> — see
     * {@code WatchMaterials.paintTile}. Every other material in this enum is a
     * <em>surface</em>: its tile is what the thing looks like and a triangle of
     * it is drawn in the tile's average colour. A map board's face is not one
     * surface but a few thousand little ones, each carrying the colour of the
     * ground it stands for, so what its material has to supply is <em>nothing</em>.
     *
     * <p>That is a parity requirement rather than an aesthetic one. A card
     * shades a fragment as {@code texture × vertexColour} and the painter uses
     * the vertex colour alone, so any tile but a flat white one would give the
     * two backends two different maps — and a texture pack could quietly tint
     * every map in the game. A white tile makes the multiply the identity, and
     * both paths draw the colours {@code BoardImage} actually sampled. Full
     * roughness and no relief say the same thing about the other half: a board
     * has no highlight to catch and no bumps to catch it with.
     */
    PAPER("paper", 0xFFFFFF, 1.0, 0, 0);

    private final String key;
    private final int rgb;
    private final float roughness;
    private final float metalness;
    private final float relief;

    WatchMaterial(String key, int rgb, double roughness, double metalness, double relief) {
        this.key = key;
        this.rgb = rgb;
        this.roughness = (float) clamp01(roughness);
        this.metalness = (float) clamp01(metalness);
        this.relief = (float) clamp01(relief);
    }

    /** Stable identifier — what the texture key and any saved file use. */
    public String key() { return key; }

    /** The flat colour a triangle of this material is shaded from. */
    public int rgb() { return rgb; }

    /** {@link #rgb()} as an AWT colour. */
    public Color color() { return new Color(rgb); }

    /**
     * How spread out this material's highlight is, {@code 0} (a mirror) to
     * {@code 1} (chalk).
     *
     * <p>Never allowed to reach zero in the atlas: a perfectly smooth surface
     * concentrates the sun into a point smaller than a pixel, which on a card
     * is a fragment that flickers as you walk. See
     * {@link WatchMaterials#MIN_ROUGHNESS}.
     */
    public float roughness() { return roughness; }

    /**
     * Whether this reflects with its own colour and has no diffuse ({@code 1},
     * a metal) or reflects white over a coloured body ({@code 0}, everything
     * that grew).
     */
    public float metalness() { return metalness; }

    /** How deep the bumps in this material's own tile are, {@code 0}–{@code 1}. */
    public float relief() { return relief; }

    /**
     * The texture key a pack supplies a tile at — {@code watch/terrain/grass},
     * which {@link com.larsons.engine.graphics.TextureKeys} resolves to
     * {@code watch_terrain/grass.png} in a drop-in pack.
     */
    public String textureKey() { return "watch/terrain/" + key; }

    /**
     * …and where it supplies that tile's <b>normal map</b>, if it has one:
     * {@code watch_terrain/grass_normal.png}.
     *
     * <p>Optional, like every other file in a pack. A material with no normal
     * map keeps the one {@link WatchMaterials} derives from its own generated
     * relief, so a pack that replaces only the colour still gets bumps that
     * agree with the colour it replaced.
     */
    public String normalKey() { return textureKey() + "_normal"; }

    /**
     * …and its <b>surface map</b>: {@code watch_terrain/grass_surface.png},
     * red for roughness and green for metalness.
     *
     * <p>Two channels of one file rather than two files, because they are never
     * wanted separately and a creator who has drawn one has drawn the other.
     * Blue is unread and free for whatever a creator finds convenient to keep
     * there.
     */
    public String surfaceKey() { return textureKey() + "_surface"; }

    /** Whether this material is see-through, and so drawn after everything else. */
    public boolean translucent() {
        return this == WATER || this == SHALLOWS || this == GLASSPANE || this == ICE;
    }

    /** The material a saved name means, tolerating anything unknown. */
    public static WatchMaterial of(String text, WatchMaterial fallback) {
        if (text == null || text.isBlank()) return fallback;
        String want = text.trim().toLowerCase();
        for (WatchMaterial m : values()) {
            if (m.key.equals(want) || m.name().equalsIgnoreCase(want)) return m;
        }
        return fallback;
    }

    private static double clamp01(double v) {
        return v < 0 ? 0 : Math.min(v, 1);
    }
}
