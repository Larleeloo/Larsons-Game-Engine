package com.larsons.engine.watch.render;

import com.larsons.engine.graphics.EyeCamera;
import com.larsons.engine.graphics.Offscreen;
import com.larsons.engine.graphics.draw.DrawTarget;
import com.larsons.engine.watch.WatchClock;
import com.larsons.engine.watch.life.AnimState;
import com.larsons.engine.watch.life.AnimalDef;
import com.larsons.engine.watch.life.AnimalModels;

import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A species' picture in the book — <b>its actual model, not its skin sheet.</b>
 *
 * <p>The first version of the guide showed the 64×64 skin, which is what the
 * model is painted from and is not what the animal looks like: a grid of
 * coloured rectangles that tells a player nothing about whether the thing they
 * are looking for is a wader or a wren. So a page renders the model instead —
 * the same boxes, the same skin, the same renderer the world uses — three
 * quarters on, in its {@link AnimState#IDLE} pose, lit at noon.
 *
 * <p><b>Cached, because a book is a book.</b> Turning a page must not cost a
 * render; the portrait for a species is drawn once at a size and kept. The
 * cache is small and bounded — a player looks at a few dozen pages in a
 * session, not thirteen hundred.
 */
public final class AnimalPortrait {

    /** How many portraits are kept. */
    private static final int CACHE_LIMIT = 96;

    /** Three quarters on: enough of the side to read the shape, enough of the
     * front to read the face. */
    private static final double VIEW_YAW = Math.toRadians(38);

    private static final Map<String, BufferedImage> CACHE =
            new LinkedHashMap<>(32, 0.75f, true) {
                @Override protected boolean removeEldestEntry(
                        Map.Entry<String, BufferedImage> eldest) {
                    return size() > CACHE_LIMIT;
                }
            };

    private AnimalPortrait() {}

    /** Forget every portrait — what a texture pack rescan calls. */
    public static synchronized void invalidate() { CACHE.clear(); }

    /**
     * A square picture of a species, {@code size} pixels on a side.
     *
     * @param background the colour behind it, {@code 0xRRGGBB}
     */
    public static synchronized BufferedImage of(AnimalDef def, int size, int background) {
        int edge = Math.max(24, size);
        String key = def.key() + ":" + edge + ":" + background;
        BufferedImage cached = CACHE.get(key);
        if (cached != null) return cached;

        BufferedImage image;
        try (Offscreen bake = Offscreen.opaque(edge, edge, true)) {
            paint(bake.target(), def, edge, background);
            image = bake.image();
        }
        CACHE.put(key, image);
        return image;
    }

    /**
     * How large every species is drawn, in world metres, before the camera is
     * placed.
     *
     * <p><b>The model is scaled, not the camera moved.</b> The obvious way to
     * frame a subject is to stand closer to a small one — and it does not work
     * here, because {@link EyeCamera#NEAR} is 0.8 m and a bat is 18 cm across:
     * the camera would end up <em>inside</em> the near plane and every triangle
     * would be clipped away, which is exactly what the first version of this
     * did (a page with nothing on it). Scaling every animal to the same size
     * and standing at a fixed, sane distance frames all thirteen hundred of
     * them identically and cannot fall through the near plane.
     */
    private static final double FRAMED_EXTENT = 2.0;

    /**
     * How far back the camera stands, in {@link #FRAMED_EXTENT}s.
     *
     * <p><b>Brought in from 1.7, because the pages were mostly background.</b>
     * Measured across the book: a heron covered three per cent of its own
     * portrait, an elk five, a grizzly — the broadest thing in the game — eight.
     * Every one of them was a small figure in the middle of a large empty
     * square, and the framing had never been looked at because nothing in the
     * registry was thin enough to make it obvious.
     *
     * <p>The three mutants are. A gaunt biped is nearly all air: scaled so its
     * <em>height</em> fills the framed extent, a wendigo is a sliver two per
     * cent of the page wide, which is a blank page with a scratch on it. The
     * answer is not a special case for three species — it is that the subject
     * should fill its own portrait, which is now roughly twice the area it was
     * for all thirteen hundred.
     *
     * <p>The floor on this number is {@link EyeCamera#NEAR}: at 1.25 the nearest
     * corner of a framed subject is about 1.5 m from the eye and the near plane
     * is at 0.8, so there is still most of a metre of margin before the clipping
     * bug this class was written to avoid comes back.
     */
    private static final double FRAMED_DISTANCE = 1.25;

    private static void paint(DrawTarget target, AnimalDef def, int edge, int background) {
        AnimalModels.Loaded model = AnimalModels.of(def);
        Mesh measured = build(model, def, 1);
        if (measured.isEmpty()) {
            target.fillRect(0, 0, edge, edge, background);
            return;
        }
        double width = Math.max(measured.maxX() - measured.minX(),
                measured.maxY() - measured.minY());
        double height = measured.maxZ() - measured.minZ();
        double extent = Math.max(1e-4, Math.max(width, height));
        Mesh built = build(model, def, FRAMED_EXTENT / extent);

        double centreZ = (built.minZ() + built.maxZ()) / 2;
        double distance = FRAMED_EXTENT * FRAMED_DISTANCE;
        double lift = FRAMED_EXTENT * 0.30;

        // Stand somewhere on a circle about the origin and look back at it.
        // EyeCamera's forward is (sin yaw, −cos yaw), so facing the origin from
        // angle a means sin(yaw) = −cos(a) and cos(yaw) = sin(a).
        double angle = Math.PI * 0.62;
        EyeCamera eye = new EyeCamera(edge, edge);
        eye.place(Math.cos(angle) * distance, Math.sin(angle) * distance, centreZ + lift);
        eye.look(Math.atan2(-Math.cos(angle), Math.sin(angle)),
                -Math.atan2(lift, distance));

        WatchRenderer renderer = new WatchRenderer();
        renderer.setFogRange(distance * 40, distance * 90);
        // Noon, so a nocturnal species is not shown in the dark it lives in —
        // the page is for identifying it, not for atmosphere.
        renderer.begin(target, eye, edge, edge, WatchClock.at(0.5), background, background);
        renderer.submit(built);
        renderer.flush(target);
    }

    private static Mesh build(AnimalModels.Loaded model, AnimalDef def, double scale) {
        Mesh.Builder mesh = Mesh.builder(0, 0, 0, false, 1);
        model.draw(mesh, def, 0, 0, 0, VIEW_YAW, AnimState.IDLE, 0.2, scale);
        return mesh.build();
    }
}
