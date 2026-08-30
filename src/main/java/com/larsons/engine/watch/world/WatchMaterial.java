package com.larsons.engine.watch.world;

import java.awt.Color;

/**
 * What a patch of ground — or a trunk, a leaf, a plank — is made of.
 *
 * <p><b>A material is a colour and a texture key, and nothing else.</b> The
 * terrain here is a triangle mesh rather than a grid of blocks, so there is no
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
 * @see WatchMaterials for the atlas, the pack lookup and the shading helpers
 */
public enum WatchMaterial {

    // --- ground ---------------------------------------------------------------

    GRASS("grass", 0x5C8A3C),
    LUSH_GRASS("lush_grass", 0x3F7A2E),
    DRY_GRASS("dry_grass", 0xA8974E),
    TUNDRA_GRASS("tundra_grass", 0x77855E),
    MOSS("moss", 0x4A6B33),
    DIRT("dirt", 0x6B5236),
    CLAY("clay", 0x9A6A48),
    SAND("sand", 0xD9C68A),
    RED_SAND("red_sand", 0xB4703F),
    GRAVEL("gravel", 0x8A8578),
    ROCK("rock", 0x7A7A78),
    DARK_ROCK("dark_rock", 0x53514F),
    SNOW("snow", 0xE8EDF2),
    ICE("ice", 0xB8D8E6),
    ASH("ash", 0x565049),
    CRYSTAL("crystal", 0x9C86D8),
    PEAT("peat", 0x4A3B2E),

    // --- trails ---------------------------------------------------------------

    TRAIL_EARTH("trail_earth", 0x8A6E4B),
    TRAIL_STONE("trail_stone", 0x9A968C),
    TRAIL_SAND("trail_sand", 0xCBB479),
    TRAIL_BOARDWALK("trail_boardwalk", 0x8A6B44),

    // --- water ----------------------------------------------------------------

    WATER("water", 0x2E6E8E),
    SHALLOWS("shallows", 0x4C93A8),

    // --- flora ----------------------------------------------------------------

    BARK("bark", 0x6B4E32),
    PALE_BARK("pale_bark", 0xC9C3B4),
    DARK_BARK("dark_bark", 0x453425),
    LEAF("leaf", 0x3E7A34),
    PINE_NEEDLE("pine_needle", 0x2C5A3A),
    AUTUMN_LEAF("autumn_leaf", 0xC07428),
    PURPLE_LEAF("purple_leaf", 0x8A5AC0),
    PALM_FROND("palm_frond", 0x4E8F45),
    BAMBOO("bamboo", 0x8FA84C),
    CAP("cap", 0xC05A55),
    PETAL("petal", 0xE0C24C),
    BERRY("berry", 0xB03448),

    // --- built ----------------------------------------------------------------

    PLANK("plank", 0xA37C4C),
    THATCH("thatch", 0xC0A45E),
    ROPE("rope", 0xB9A276),
    STONE_BLOCK("stone_block", 0x8C8880),
    GLASSPANE("glasspane", 0xA9D6E0),

    /**
     * Paper: the face of a map board, and the one material here that carries no
     * colour of its own.
     *
     * <p><b>White, and deliberately grainless</b> — see
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
     * both paths draw the colours {@code BoardImage} actually sampled.
     */
    PAPER("paper", 0xFFFFFF);

    private final String key;
    private final int rgb;

    WatchMaterial(String key, int rgb) {
        this.key = key;
        this.rgb = rgb;
    }

    /** Stable identifier — what the texture key and any saved file use. */
    public String key() { return key; }

    /** The flat colour a triangle of this material is shaded from. */
    public int rgb() { return rgb; }

    /** {@link #rgb()} as an AWT colour. */
    public Color color() { return new Color(rgb); }

    /**
     * The texture key a pack supplies a tile at — {@code watch/terrain/grass},
     * which {@link com.larsons.engine.graphics.TextureKeys} resolves to
     * {@code watch_terrain/grass.png} in a drop-in pack.
     */
    public String textureKey() { return "watch/terrain/" + key; }

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
}
