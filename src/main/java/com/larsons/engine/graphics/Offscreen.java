package com.larsons.engine.graphics;

import com.larsons.engine.graphics.draw.DrawTarget;
import com.larsons.engine.graphics.draw.Java2DTarget;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/**
 * A {@link DrawTarget} over an image you own — <b>baking, without reopening the
 * seam.</b>
 *
 * <p>There are two different things a class can want a graphics context for,
 * and the engine treats them differently on purpose:
 *
 * <ul>
 *   <li><b>Drawing a frame</b> goes through the {@code DrawTarget} the scene
 *       was handed, whatever backend is behind it. Nothing outside
 *       {@code graphics} may name a concrete one — see
 *       {@code SealedSeamTest}, which fails the build on the attempt.</li>
 *   <li><b>Baking</b> — painting a sprite sheet, an icon, a portrait, once, at
 *       load, into a {@link BufferedImage} the caller then hands back as a
 *       texture — is Java2D by definition, because the result is pixels rather
 *       than draw calls.</li>
 * </ul>
 *
 * <p>Before this existed a baker had two options: name {@code Graphics2D}
 * itself and take an excuse in the seam test, or not bake. This is the third:
 * ask for a surface, draw through the same verbs a frame uses, and get an image
 * back. It lives in {@code graphics} because that is the one package allowed to
 * know which backend it is, and it hands out a {@code DrawTarget} because that
 * is all a caller needs.
 *
 * <pre>
 *   try (Offscreen bake = Offscreen.image(64, 64, true)) {
 *       bake.target().fillRect(0, 0, 64, 64, Color.WHITE);
 *       return bake.image();
 *   }
 * </pre>
 *
 * <p><b>Close it.</b> A {@code Graphics2D} holds native resources until it is
 * disposed; the try-with-resources above is not decoration. The image stays
 * valid afterwards — it is the point.
 */
public final class Offscreen implements AutoCloseable {

    private final BufferedImage image;
    private final Graphics2D graphics;
    private final DrawTarget target;

    private Offscreen(BufferedImage image, Graphics2D graphics, DrawTarget target) {
        this.image = image;
        this.graphics = graphics;
        this.target = target;
    }

    /**
     * A new transparent image and a target over it.
     *
     * @param smooth whether to antialias — right for a portrait or an icon,
     *               wrong for pixel art, which wants its edges
     */
    public static Offscreen image(int width, int height, boolean smooth) {
        return over(new BufferedImage(Math.max(1, width), Math.max(1, height),
                BufferedImage.TYPE_INT_ARGB), smooth);
    }

    /** The same, opaque — for anything that fills its whole surface. */
    public static Offscreen opaque(int width, int height, boolean smooth) {
        return over(new BufferedImage(Math.max(1, width), Math.max(1, height),
                BufferedImage.TYPE_INT_RGB), smooth);
    }

    /** A target over an image that already exists. */
    public static Offscreen over(BufferedImage image, boolean smooth) {
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, smooth
                ? RenderingHints.VALUE_ANTIALIAS_ON : RenderingHints.VALUE_ANTIALIAS_OFF);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, smooth
                ? RenderingHints.VALUE_INTERPOLATION_BILINEAR
                : RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        return new Offscreen(image, g, new Java2DTarget(g, image.getWidth(),
                image.getHeight()));
    }

    /** Draw through this. */
    public DrawTarget target() { return target; }

    /** The image being drawn into; valid after {@link #close()}. */
    public BufferedImage image() { return image; }

    public int width() { return image.getWidth(); }

    public int height() { return image.getHeight(); }

    @Override
    public void close() {
        graphics.dispose();
    }
}
