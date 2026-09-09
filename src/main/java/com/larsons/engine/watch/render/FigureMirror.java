package com.larsons.engine.watch.render;

import com.larsons.engine.graphics.EyeCamera;
import com.larsons.engine.graphics.Offscreen;
import com.larsons.engine.graphics.draw.DrawTarget;
import com.larsons.engine.watch.Figure;
import com.larsons.engine.watch.WatchClock;

import java.awt.image.BufferedImage;
import java.util.List;

/**
 * <b>You, dressed, turned to whatever angle you like — the looking-glass in the
 * wardrobe.</b>
 *
 * <p>The wardrobe screen is a list of what you own with a dot beside what is on,
 * and that is a manifest rather than a mirror. It is also the one screen in the
 * game where every decision is about how something <em>looks</em>: which of four
 * haircuts, what colour to dye a coat, whether the cape is worth what a keeper
 * is asking. Answering any of those from a row of text means putting the piece
 * on, closing the screen, swinging the third-person camera round yourself, and
 * opening the screen again to change your mind.
 *
 * <p>So the panel draws the actual figure, wearing the actual outfit, in the
 * actual dyes, and it turns. All the way round, because a cape, a pack and a
 * plait are all on the side of you that you cannot see and are most of what
 * there is to look at.
 *
 * <h2>It is the same figure the world draws</h2>
 *
 * <p>One call to {@link WalkerModel#walker}, with the wearer's own
 * {@link Figure}, the keys off their own {@code Outfit} and their own
 * {@link CosmeticModel.Dyes} — so this is a picture of the walker other people
 * can see rather than a second description of one. That matters more here than
 * on any other screen: the whole use of a mirror is that it does not lie, and a
 * preview assembled out of its own arithmetic would eventually be a preview of a
 * person nobody owns. {@link ItemPortrait} makes the same promise about a row in
 * a shop and for the same reason.
 *
 * <h2>Why it owns its image</h2>
 *
 * <p>Every other portrait in this game is cached: an item's picture, a species'
 * picture, drawn once and kept, because the subject cannot change. This one is
 * turning — it is a different picture every frame, and it is a different picture
 * again the moment somebody drags a colour slider — so there is nothing to cache
 * and the only thing worth saving is the allocation. Hence an instance with a
 * buffer rather than a static method with a map: the screen holds one of these,
 * it re-renders into the same {@code int[]} sixty times a second, and a menu
 * that is open for a minute allocates one image rather than three thousand.
 */
public final class FigureMirror {

    /**
     * How tall a frame the figure is drawn into, in metres from the floor.
     *
     * <p>Fixed rather than measured, which is the opposite of what
     * {@link ItemPortrait} does and is right for the opposite reason. A portrait
     * frames one still subject once and wants it to fill the picture; this one
     * turns, and a figure framed on its own bounding box <b>breathes</b> — an
     * antler rack is 0.69 m across the shoulders and 0.17 m front to back, so
     * measuring per frame would grow and shrink the whole person twice a
     * revolution. A fixed frame is a fixed person turning inside it.
     *
     * <p>2.15 m is the tallest thing anybody can be — the antler circlet tops
     * out at 1.87 — with a boot's worth of floor under it and a hand's breadth
     * of air over it, so that nothing anyone can put on is ever touching an
     * edge.
     */
    private static final double FRAME = 2.15;

    /**
     * The lens, in degrees, and how far back it stands.
     *
     * <p>A long lens on purpose. At the 70° the world is played through, a
     * figure close enough to fill a 300-pixel panel is close enough to be
     * distorted by it — boots the size of a chest, a head that shrinks as it
     * turns — and every one of those reads as the model being wrong rather than
     * the camera being close. Thirty degrees is a portrait lens, and the
     * distance falls out of it.
     */
    private static final double LENS = 30;

    /**
     * How high the camera looks, as a share of the frame.
     *
     * <p>A little under half, which puts the floor a boot's depth inside the
     * bottom edge: three of the nine slots are worn below the knee and the
     * first version of this framed the feet off the bottom of the picture.
     */
    private static final double EYE_SHARE = 0.47;

    private BufferedImage image;

    /**
     * One frame of the mirror: this figure, in these clothes, at this angle.
     *
     * <p>The returned image is <b>the mirror's own and is overwritten by the
     * next call</b> — draw it and forget it. That is the whole point of the
     * class and the one thing a caller has to know about it.
     *
     * @param yaw        which way the figure is turned, in radians; {@code 0}
     *                   faces the viewer, and turning it is the 360
     * @param clock      the world animation clock, in seconds, so an imported
     *                   figure plays its own idle rather than standing at a
     *                   frozen frame of one
     * @param coat       the wearer's own tint, as {@code WalkerModel.coatFor}
     * @param background what shows through where the figure is not, as
     *                   {@code 0xRRGGBB} — the panel's own colour, so the
     *                   picture sits in the panel rather than in a box
     */
    public BufferedImage of(int width, int height, Figure figure, List<String> worn,
                            CosmeticModel.Dyes dyes, int coat, double yaw, double clock,
                            int background) {
        int w = Math.max(16, width), h = Math.max(16, height);
        if (image == null || image.getWidth() != w || image.getHeight() != h) {
            image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        }
        try (Offscreen bake = Offscreen.over(image, true)) {
            paint(bake.target(), w, h, figure, worn, dyes, coat, yaw, clock, background);
        }
        return image;
    }

    private static void paint(DrawTarget target, int w, int h, Figure figure,
                              List<String> worn, CosmeticModel.Dyes dyes, int coat,
                              double yaw, double clock, int background) {
        Mesh.Builder mesh = Mesh.builder(0, 0, 0, false, 1);
        // Standing still, at the origin, on the ground, wearing what they have
        // on. `speed` is zero and `leap` is grounded, so this is the idle the
        // world draws somebody standing in a clearing in.
        WalkerModel.walker(mesh, figure, 0, 0, 0, yaw, false, 0, 0,
                WalkerModel.Leap.GROUNDED, coat, worn, clock, dyes);

        // The camera stands off far enough that a FRAME-tall subject fills the
        // shorter of the panel's two dimensions, and looks level at EYE_SHARE up
        // it. Level rather than tilted: a tilt is a foreshortening that changes
        // as the figure turns, and this picture's whole job is to be the same
        // picture from every side.
        double lens = Math.toRadians(LENS);
        double fit = FRAME / 2 * Math.max(1, (double) w / h);
        double distance = Math.max(EyeCamera.NEAR * 1.5, fit / Math.tan(lens / 2));
        double lift = FRAME * EYE_SHARE;

        EyeCamera eye = new EyeCamera(w, h);
        eye.setFov(lens);
        // On the −y side looking back along +y, which is the side a walker at a
        // yaw of zero has their face on — {@code WalkerModel}'s forward is
        // {@code (sin yaw, −cos yaw)}, so a yaw of zero faces −y. An
        // {@link EyeCamera}'s forward is the same expression, so looking the
        // other way along it is a yaw of π. That is what makes {@code yaw == 0}
        // mean "looking at you" here, which is the one thing a caller turning
        // this needs to be able to assume.
        eye.place(0, -distance, lift);
        eye.look(Math.PI, 0);

        WatchRenderer renderer = new WatchRenderer();
        // Well past the subject, so nothing in the mirror is hazed: the fog in
        // this game is distance in a wood, and there is no wood in here.
        renderer.setFogRange(distance * 40, distance * 90);
        // Noon, like every other picture drawn out of the world for a menu — a
        // wardrobe you can only see the colours of by day would be a wardrobe
        // you cannot use at night.
        renderer.begin(target, eye, w, h, WatchClock.at(0.5), background, background);
        renderer.submit(mesh.build());
        renderer.flush(target);
    }
}
