package com.larsons.engine.graphics.chunk;

import com.larsons.engine.graphics.Skins;
import com.larsons.engine.world.Block;
import com.larsons.engine.world.BlockRegistry;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Every block's sheet, packed into one image — <b>Minecraft's block atlas</b>,
 * and it exists for exactly the reason that one does.
 *
 * <p>A GPU draws fastest when it is left alone. Binding a different texture
 * between two quads ends the draw call they could have shared, so a terrain
 * pass that reached for each block's own sheet would issue a draw call per
 * block type per section — hundreds a frame, each one a handful of triangles.
 * With every sheet in one image the whole of the visible world is one bind and
 * two draws per section, and the sheet a quad wants is a pair of texture
 * coordinates rather than a state change.
 *
 * <p><b>A grid rather than a packer.</b> Block sheets are square and all much
 * the same size, so the shelf-packing that a sprite atlas needs buys nothing
 * here; a fixed {@value #TILE} pixel cell scales each sheet into place and the
 * lookup is arithmetic. Two cells per block — a top and a side — which is what
 * the engine's own texture resolution offers
 * ({@link Block#topTextureKey()}, {@link Block#sideTextureKey()}).
 *
 * <p><b>Animation is a re-upload of the tiles that move</b>, which is again
 * what Minecraft does: water and lava are several frames deep, so
 * {@link #advance} re-draws only those cells and says whether anything changed.
 * The backend re-uploads when it did. A still world never touches the texture
 * after the first frame.
 *
 * <p><b>Coordinates are inset by half a texel.</b> A quad's edge lands exactly
 * on a cell boundary, and a sample exactly on a boundary belongs to whichever
 * texel the interpolator's last bit chooses — which on a neighbouring cell is a
 * different block. Half a texel in is inside the tile by construction.
 */
public final class BlockAtlas {

    /** Pixels per side of one cell of the atlas. */
    public static final int TILE = 32;

    /** Which of a block's two sheets a quad wants. */
    public static final int FACE_TOP = 0;
    public static final int FACE_SIDE = 1;

    private final int columns;
    private final int rows;
    private final BufferedImage image;
    /** {@code (blockId << 1) | face} to the cell index that holds it. */
    private final Map<Integer, Integer> cells = new HashMap<>();
    /** The texture key each cell was drawn from, or {@code null} where blank. */
    private final String[] keyOf;
    /** Cells whose key has more than one frame, so they have to be redrawn. */
    private final int[] animated;
    /** Faces the pack really dressed, as against the ones on the white cell. */
    private final java.util.Set<Integer> sheeted = new java.util.HashSet<>();
    /** The key of the cell that stands in for "no sheet". */
    private static final String WHITE = "";
    private double drawnAt = -1;
    private int revision;

    private BlockAtlas(int columns, int rows, BufferedImage image, String[] keyOf,
                       int[] animated) {
        this.columns = columns;
        this.rows = rows;
        this.image = image;
        this.keyOf = keyOf;
        this.animated = animated;
    }

    /**
     * Pack every block in {@code blocks}.
     *
     * <p><b>Every block gets a cell, including the ones with no sheet.</b>
     * Those share a single white one, so a quad that has no texture still
     * samples the atlas and comes out as its own vertex colour — which means
     * the shader never has to ask whether a quad is textured, and a section of
     * mixed blocks is still one draw call. Minecraft's atlas is total for the
     * same reason.
     */
    public static BlockAtlas of(BlockRegistry blocks) {
        List<String> keys = new ArrayList<>();
        Map<Integer, Integer> wanted = new HashMap<>();
        java.util.Set<Integer> sheeted = new java.util.HashSet<>();
        // The white cell first, so it is always index 0 and a block with no
        // sheet needs no lookup to find it.
        keys.add(WHITE);
        for (Block b : blocks.all()) {
            String top = resolve(b.topTextureKey(), b.textureKey());
            String side = resolve(b.sideTextureKey(), b.textureKey());
            addCell(keys, wanted, b.id(), FACE_TOP, top);
            addCell(keys, wanted, b.id(), FACE_SIDE, side);
            if (top != null) sheeted.add((b.id() << 1) | FACE_TOP);
            if (side != null) sheeted.add((b.id() << 1) | FACE_SIDE);
        }
        int count = Math.max(1, keys.size());
        int columns = (int) Math.ceil(Math.sqrt(count));
        int rows = (count + columns - 1) / columns;
        BufferedImage image = new BufferedImage(columns * TILE, rows * TILE,
                BufferedImage.TYPE_INT_ARGB);
        String[] keyOf = keys.toArray(new String[0]);
        List<Integer> moving = new ArrayList<>();
        for (int i = 0; i < keyOf.length; i++) {
            if (!WHITE.equals(keyOf[i]) && Skins.animated(keyOf[i])) moving.add(i);
        }
        int[] animated = new int[moving.size()];
        for (int i = 0; i < animated.length; i++) animated[i] = moving.get(i);

        BlockAtlas atlas = new BlockAtlas(columns, rows, image, keyOf, animated);
        atlas.cells.putAll(wanted);
        atlas.sheeted.addAll(sheeted);
        atlas.redraw(0, true);
        return atlas;
    }

    private static String resolve(String faceKey, String flatKey) {
        if (faceKey != null && Skins.frame(faceKey, 0) != null) return faceKey;
        if (flatKey != null && Skins.frame(flatKey, 0) != null) return flatKey;
        return null;
    }

    private static void addCell(List<String> keys, Map<Integer, Integer> wanted,
                                int id, int face, String key) {
        if (key == null) {
            wanted.put((id << 1) | face, 0);   // the white cell
            return;
        }
        int at = keys.indexOf(key);
        if (at < 0) {
            at = keys.size();
            keys.add(key);
        }
        wanted.put((id << 1) | face, at);
    }

    /** The packed image, for a backend to upload. */
    public BufferedImage image() { return image; }

    /**
     * Bumped whenever the image changes, so a backend can notice without
     * comparing pixels.
     */
    public int revision() { return revision; }

    /**
     * Whether the texture pack actually supplied a sheet for this face, as
     * against the white cell that stands in for one.
     *
     * <p>What a mesher wants it for: a textured face is tinted white, because
     * the sheet already carries the block's colour and multiplying by it again
     * would darken every block twice. A sheetless face is tinted by the block.
     */
    public boolean hasSheet(int blockId, int face) {
        return sheeted.contains((blockId << 1) | face);
    }

    /**
     * The texture coordinates of one block's face, into {@code out} as
     * {@code {u0, v0, u1, v1}} — or all zeroes when there is no sheet, which a
     * mesher reads as "draw it in its own colour".
     */
    public void uv(int blockId, int face, float[] out) {
        Integer cell = cells.get((blockId << 1) | face);
        if (cell == null) {
            out[0] = out[1] = out[2] = out[3] = 0;
            return;
        }
        int cx = cell % columns, cy = cell / columns;
        float half = 0.5f / TILE;
        out[0] = (cx + half) / columns;
        out[1] = (cy + half) / rows;
        out[2] = (cx + 1 - half) / columns;
        out[3] = (cy + 1 - half) / rows;
    }

    /**
     * Redraw the cells that animate, at this point in their cycle.
     *
     * @return whether anything moved, and so whether the backend has to upload
     */
    public boolean advance(double animClock) {
        if (animated.length == 0) return false;
        // Bucketed to the frame rather than to the clock: a sheet with four
        // frames changes four times a cycle, and re-uploading a texture sixty
        // times a second to show four pictures is fifty-six uploads of the
        // picture that is already there.
        double bucket = Math.floor(animClock * 16);
        if (bucket == drawnAt) return false;
        drawnAt = bucket;
        redraw(animClock, false);
        return true;
    }

    private void redraw(double animClock, boolean all) {
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.setComposite(java.awt.AlphaComposite.Src);
            if (all) {
                for (int i = 0; i < keyOf.length; i++) draw(g, i, animClock);
            } else {
                for (int cell : animated) draw(g, cell, animClock);
            }
        } finally {
            g.dispose();
        }
        revision++;
    }

    private void draw(Graphics2D g, int cell, double animClock) {
        int cx = (cell % columns) * TILE, cy = (cell / columns) * TILE;
        if (WHITE.equals(keyOf[cell])) {
            g.setColor(java.awt.Color.WHITE);
            g.fillRect(cx, cy, TILE, TILE);
            return;
        }
        BufferedImage sheet = Skins.frame(keyOf[cell], animClock);
        if (sheet == null) return;
        g.drawImage(sheet, cx, cy, cx + TILE, cy + TILE,
                0, 0, sheet.getWidth(), sheet.getHeight(), null);
    }
}
