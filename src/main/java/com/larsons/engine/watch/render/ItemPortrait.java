package com.larsons.engine.watch.render;

import com.larsons.engine.graphics.EyeCamera;
import com.larsons.engine.graphics.Offscreen;
import com.larsons.engine.graphics.draw.DrawTarget;
import com.larsons.engine.watch.Cosmetics;
import com.larsons.engine.watch.WatchClock;

import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An item's picture in the satchel — <b>its actual model, three-quarters on.</b>
 *
 * <p>The satchel screen was forty rows of text: a count, a name, and a
 * category. That is a list of what you have and not a picture of it, and it
 * made the one screen in the game where a player decides what to cook the one
 * screen with nothing to look at. Every one of those keys already has a solid
 * ({@link ItemModel}) standing on a feeder or lying in the grass; drawing the
 * same solid beside its own row costs one render per item per size and turns
 * the list into an inventory.
 *
 * <p>This is {@link AnimalPortrait} for things rather than for creatures, and
 * deliberately the same shape of class — same offscreen render, same fixed
 * noon light, same bounded cache — because the two answer the same question
 * and a player should not be able to tell that they are two pieces of code.
 *
 * <p><b>Framed by measurement, not by a table.</b> A coconut is 15 cm across
 * and a fishing rod is 1.35 m; drawn at one scale the rod is a hairline and the
 * coconut fills the frame. So the model is built once at unit scale, its own
 * bounding box read off the mesh, and then rebuilt at whatever scale makes its
 * longest side {@link #FRAMED_EXTENT} — which frames every item in the game
 * identically without anybody having to say how big any of them is.
 */
public final class ItemPortrait {

    /** How many pictures are kept. */
    private static final int CACHE_LIMIT = 128;

    /**
     * How large every item is drawn, in world metres, before the camera is
     * placed.
     *
     * <p>The model is scaled and the camera left where it is, for the reason
     * {@link AnimalPortrait#of} spells out: {@link EyeCamera#NEAR} is 0.8 m and
     * a beetle is 8 cm long, so framing by walking closer puts the camera
     * inside the near plane and clips the whole subject away.
     */
    private static final double FRAMED_EXTENT = 2.0;

    /** How many times its own width an item may be tall before it is cropped. */
    private static final double ASPECT_CAP = 4.0;

    /**
     * Which way the item is turned for its picture.
     *
     * <p><b>Measured against the camera, not picked to look tidy.</b> Every
     * elongated model here — a plank, a branch, a fish, a loaf — runs along the
     * axis its yaw names, and the camera below stands at a fixed bearing. At
     * the 34° this started at, that axis came out within twelve degrees of the
     * line of sight: the whole catalogue of long things was drawn end-on, so a
     * trout was a thumbnail of a trout's nose. Turned to here it sits about
     * seventy degrees off the view, which is the three-quarter angle the
     * pictures were supposed to be at all along.
     */
    private static final double VIEW_YAW = Math.toRadians(-48);

    private static final Map<String, BufferedImage> CACHE =
            new LinkedHashMap<>(32, 0.75f, true) {
                @Override protected boolean removeEldestEntry(
                        Map.Entry<String, BufferedImage> eldest) {
                    return size() > CACHE_LIMIT;
                }
            };

    private ItemPortrait() {}

    /** Forget every picture — what a texture pack rescan calls. */
    public static synchronized void invalidate() { CACHE.clear(); }

    /**
     * A square picture of an item, {@code size} pixels on a side.
     *
     * @param key        a {@link com.larsons.engine.watch.Forage} key, or a
     *                   {@link Cosmetics} one — see {@link #build}
     * @param background the colour behind it, {@code 0xRRGGBB}
     */
    public static synchronized BufferedImage of(String key, int size, int background) {
        int edge = Math.max(16, size);
        String cacheKey = key + ":" + edge + ":" + background;
        BufferedImage cached = CACHE.get(cacheKey);
        if (cached != null) return cached;

        BufferedImage image;
        try (Offscreen bake = Offscreen.opaque(edge, edge, true)) {
            paint(bake.target(), key, edge, background);
            image = bake.image();
        }
        CACHE.put(cacheKey, image);
        return image;
    }

    private static void paint(DrawTarget target, String key, int edge, int background) {
        Mesh measured = build(key, 0, 0, 0, 1);
        if (measured.isEmpty()) {
            target.fillRect(0, 0, edge, edge, background);
            return;
        }
        double width = Math.max(1e-4, Math.max(measured.maxX() - measured.minX(),
                measured.maxY() - measured.minY()));
        double height = measured.maxZ() - measured.minZ();
        // Framed on the longest side — except for something far taller than it
        // is wide, which is framed on {@link #ASPECT_CAP} times its width and
        // allowed to run off the top. A fishing rod is 1.35 m long and 4 cm
        // thick: framed whole in a twenty-two pixel row it is a shaft a third
        // of a pixel across, which is to say an empty box. Framed on the bottom
        // third it is a butt, a cork grip and a tapering shaft, which is what a
        // rod looks like. Nothing else in the catalogue reaches the cap.
        double extent = Math.max(width, Math.min(height, width * ASPECT_CAP));
        double scale = FRAMED_EXTENT / extent;

        // Where the model's own middle sits, at unit scale and from a base of
        // zero. Every offset in ItemModel is proportional to the scale, so the
        // middle at `scale` is this times `scale` — which means standing the
        // model on the negative of that centres it on the origin exactly.
        double cx = (measured.minX() + measured.maxX()) / 2;
        double cy = (measured.minY() + measured.maxY()) / 2;
        // A capped item is anchored on its foot rather than its middle: the
        // middle of a rod is bare shaft.
        double cz = height > extent ? measured.minZ() + extent / 2
                : (measured.minZ() + measured.maxZ()) / 2;
        Mesh built = build(key, -cx * scale, -cy * scale, -cz * scale, scale);

        // Well above the horizon, and higher than a species portrait's camera.
        // Half the things in this catalogue are flat — a feather, a plank, a
        // chip of bark, a trowel — and at the eleven degrees that framed an
        // animal nicely they are seen almost edge-on and read as a scratch. A
        // third of the way up looks down into them without turning the tall
        // ones (a bottle, a mushroom) into plan views.
        double distance = FRAMED_EXTENT * 1.55;
        double lift = FRAMED_EXTENT * 0.78;

        // Stand somewhere on a circle about the origin and look back at it.
        // EyeCamera's forward is (sin yaw, −cos yaw), so facing the origin from
        // angle a means sin(yaw) = −cos(a) and cos(yaw) = sin(a).
        double angle = Math.PI * 0.62;
        EyeCamera eye = new EyeCamera(edge, edge);
        eye.place(Math.cos(angle) * distance, Math.sin(angle) * distance, lift);
        eye.look(Math.atan2(-Math.cos(angle), Math.sin(angle)),
                -Math.atan2(lift, distance));

        WatchRenderer renderer = new WatchRenderer();
        renderer.setFogRange(distance * 40, distance * 90);
        // Noon, so a nightbell is not shown in the dark it opens in — the row
        // is for recognising it, not for atmosphere.
        renderer.begin(target, eye, edge, edge, WatchClock.at(0.5), background,
                background);
        renderer.submit(built);
        renderer.flush(target);
    }

    /**
     * The solid for a key — an item's, or a piece of {@link Cosmetics}'.
     *
     * <p><b>One entry point for both</b>, because every screen that draws a row
     * wants a picture of whatever is on it and none of them should have to know
     * which catalogue the key came out of. A cosmetic has no {@link ItemModel}
     * and never will: it is described where it is worn, by
     * {@link CosmeticModel}, and {@code alone} is that same description with the
     * body left out. So a hat in a shop row is drawn by the code that puts it on
     * a head, which is the same promise the item rows already make.
     *
     * <p>Sized off the body part it hangs on rather than in metres — a mitten
     * measured against a hand and a boater against a head — so the framing above
     * gets an object of about the right proportions to look at and every piece
     * arrives in its row at the same apparent size as everything else.
     */
    private static Mesh build(String key, double x, double y, double z, double scale) {
        Mesh.Builder mesh = Mesh.builder(0, 0, 0, false, 1);
        Cosmetics.Piece piece = Cosmetics.byKey(key);
        if (piece != null) {
            // A fixed coat, so the two pieces drawn in the wearer's colour have
            // one to be drawn in. Whose does not matter and must not: a picture
            // that changed with the viewer would be a picture cached per player.
            CosmeticModel.alone(mesh, key, x, y, z, VIEW_YAW,
                    CosmeticModel.portraitSize(piece.slot()) * scale,
                    WalkerModel.coatFor(0));
        } else {
            ItemModel.item(mesh, key, x, y, z, scale, VIEW_YAW);
        }
        return mesh.build();
    }
}
