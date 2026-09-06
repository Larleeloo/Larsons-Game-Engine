package com.larsons.engine.watch.render;

import com.larsons.engine.watch.Cosmetics;
import com.larsons.engine.watch.world.WatchMaterial;
import com.larsons.engine.watch.world.WatchMaterials;

import java.util.List;

/**
 * The clothes, drawn on top of the figure that is already there.
 *
 * <h2>Why this is not part of {@link WalkerModel}</h2>
 *
 * <p>A walker is one pose solved four ways — standing, jumping, rowing,
 * swimming — and every line of it is about where a joint ends up. A hat is
 * about none of that: it is a small pile of boxes that wants to know where the
 * top of a head is and nothing else. Kept in {@code WalkerModel} the two would
 * be interleaved, and the next person to retime the gait would be reading a
 * catalogue of hatbands while they did it.
 *
 * <p>So the walker solves its pose and then hands this class the <b>places</b>
 * it found — a {@link Fit} each for the head, the face, the neck, the back,
 * each hand and each boot. That split is what makes the same eighteen
 * descriptions work on a standing figure and on a swimmer whose spine is
 * pointing at the lake bed: nothing here knows which it is drawing on, because
 * a fit carries its own idea of up.
 *
 * <h2>Everything is measured in the part it hangs on</h2>
 *
 * <p>Every offset and every half-extent below is written in {@link Fit#size()}
 * — the half-width of the head, the hand, the boot it is worn on — and never in
 * metres. That is not tidiness: {@code WalkerModel.HEIGHT} is a constant today
 * and a crouching walker is already {@code CROUCH_HEIGHT}, so a mitten written
 * as "five and a half centimetres" is a mitten that comes off the hand the
 * first time anybody scales a figure. Written as {@code 1.28 × size} it stays
 * on.
 *
 * <h2>Two of them wear the wearer's colour</h2>
 *
 * <p>A hood and a cape are the only pieces big enough to hide a walker's
 * outline, and the outline is how a party tells each other apart at two hundred
 * metres ({@link WalkerModel#coatFor}). Those two are drawn in the coat colour
 * they are covering rather than in a colour of their own — see
 * {@link Cosmetics.Piece#tinted()} — so six people in matching oilskins are
 * still six people. Everything else is small enough to be its own colour.
 */
public final class CosmeticModel {

    private CosmeticModel() {}

    /**
     * A place on a body where something is worn.
     *
     * <p>An origin, a way up, and a size — plus the yaw the boxes are turned
     * about, which is the body's and not the fit's. That last one is the same
     * compromise {@code WalkerModel.head} already makes for a swimmer's hat: a
     * piece <b>stacks</b> along the part it is worn on, so a prone swimmer's
     * veil hangs down their face rather than off the side of it, while the
     * boxes themselves stay square to the world's vertical. Nothing in this
     * catalogue is long enough for the difference to read, and the alternative
     * is a full basis on every box in the file.
     *
     * @param upX  the way the part points; need not be a unit vector
     * @param yaw  which way the wearer faces, in radians
     * @param size the half-width of the part this hangs on, in metres — the
     *             unit every offset below is written in
     */
    public record Fit(double x, double y, double z,
                      double upX, double upY, double upZ,
                      double yaw, double size) {

        /** Straight up, which is where every part of a standing figure points. */
        public static Fit upright(double x, double y, double z, double yaw, double size) {
            return new Fit(x, y, z, 0, 0, 1, yaw, size);
        }

        public Fit {
            double length = Math.sqrt(upX * upX + upY * upY + upZ * upZ);
            if (length < 1e-9) {
                upX = 0;
                upY = 0;
                upZ = 1;
            } else {
                upX /= length;
                upY /= length;
                upZ /= length;
            }
        }

        /**
         * Which way the wearer is looking, as far as it is across the fit's own
         * up — a unit vector.
         *
         * <p>Projected rather than taken flat, because a swimmer lying on their
         * face has a spine pointing along the world's forward: unprojected, the
         * "front of the head" a pair of spectacles is put on would be somewhere
         * inside the skull. Falls back to the wearer's own right when the two
         * are parallel, which is a walker looking straight up or straight down
         * and cannot be told apart by any face they are wearing.
         */
        double[] forward() {
            double fx = Math.sin(yaw), fy = -Math.cos(yaw), fz = 0;
            double along = fx * upX + fy * upY + fz * upZ;
            double px = fx - upX * along, py = fy - upY * along, pz = fz - upZ * along;
            double length = Math.sqrt(px * px + py * py + pz * pz);
            if (length < 1e-6) {
                return new double[]{Math.cos(yaw), Math.sin(yaw), 0};
            }
            return new double[]{px / length, py / length, pz / length};
        }

        /**
         * The wearer's right: up × forward, which is right-handed with both.
         *
         * <p>Takes the forward it is crossed with rather than asking for it,
         * so that placing one box costs one {@link #forward} and not two. It is
         * a sine and a cosine either way, and this is called a few hundred
         * times a frame with a party in the wood.
         */
        double[] right(double[] forward) {
            return new double[]{upY * forward[2] - upZ * forward[1],
                    upZ * forward[0] - upX * forward[2],
                    upX * forward[1] - upY * forward[0]};
        }
    }

    /**
     * Draw whatever of {@code worn} belongs in {@code slot}, at {@code fit}.
     *
     * <p>Called once per slot by whoever is drawing the figure. A slot with
     * nothing in it costs one walk of a list at most six long and emits no
     * triangles — which is the whole of what "optional" means here: a walker
     * wearing nothing is exactly the walker this game drew before any of this
     * existed.
     *
     * @param worn what the wearer has on, as {@code Outfit.wornKeys}
     * @param coat the wearer's own coat colour, for the pieces that take it
     */
    public static void wear(Mesh.Builder mesh, List<String> worn, Cosmetics.Slot slot,
                            Fit fit, int coat) {
        if (worn == null || worn.isEmpty()) return;
        for (String key : worn) {
            Cosmetics.Piece piece = Cosmetics.byKey(key);
            if (piece == null || piece.slot() != slot) continue;
            draw(mesh, piece, fit, coat);
            // One piece to a slot — see Outfit.wear — so there is nothing after
            // this worth looking at.
            return;
        }
    }

    /**
     * One piece, standing on its own at the origin — what a portrait renders.
     *
     * <p>The same descriptions the figure wears, at the same scale relative to
     * the part they hang on, which is the point: a picture drawn from a second
     * description would eventually be a picture of a hat nobody owns.
     *
     * @param size what to treat the missing body part as being — pass the
     *             {@linkplain #portraitSize natural size} for its slot
     */
    public static void alone(Mesh.Builder mesh, String key, double x, double y, double z,
                             double yaw, double size, int coat) {
        Cosmetics.Piece piece = Cosmetics.byKey(key);
        if (piece == null) return;
        draw(mesh, piece, Fit.upright(x, y, z, yaw, size), coat);
    }

    /**
     * How big the body part under a slot is, in metres — the head's half-width,
     * the hand's, the boot's.
     *
     * <p>Taken from {@code WalkerModel}'s own boxes and public because a
     * portrait has no body to measure and would otherwise have to guess.
     */
    public static double portraitSize(Cosmetics.Slot slot) {
        return switch (slot) {
            case HANDS -> 0.055;
            case FEET -> 0.085;
            default -> 0.115;
        };
    }

    /**
     * How far in front of the neck the front of a chest is, in sizes.
     *
     * <p>{@code WalkerModel}'s chest is 0.22 m from its middle to its face and
     * a neck fit stands on that middle, so this is the number every scarf tail
     * and every lanyard in the file is written against. It is here rather than
     * in each of them because it is a fact about the walker, and the first
     * version of three of these pieces was drawn inside somebody's ribs for
     * want of it.
     */
    private static final double CHEST_OUT = 2.05;

    private static void draw(Mesh.Builder mesh, Cosmetics.Piece piece, Fit fit, int coat) {
        float[] uv = new float[4];
        WatchMaterials.uv(WatchMaterial.PLANK, uv);
        // A tinted piece is drawn in the coat it is covering, darkened, so that
        // it still reads as a separate garment lying over one rather than as the
        // coat having grown. See the class note for why only two pieces are.
        int rgb = piece.tinted() ? ShopModel.shade(coat, 0.72) : piece.rgb();
        int trim = piece.trim();

        switch (piece.key()) {
            // --- head ------------------------------------------------------
            case "knitted_beanie" -> {
                box(mesh, fit, 0, 0, -0.55, 1.32, 1.32, 0.68, uv, rgb);
                box(mesh, fit, 0, 0, -1.10, 1.42, 1.42, 0.24, uv, trim);
                blob(mesh, fit, 0, 0, 0.42, 0.34, uv, ShopModel.shade(trim, 1.15));
            }
            case "feathered_band" -> {
                box(mesh, fit, 0, 0, -0.48, 1.30, 1.30, 0.22, uv, rgb);
                // One primary, stuck in at the side and swept back over the
                // crown — a flat strut rather than a box, because the whole of
                // what makes it read as a feather is that it leans.
                strut(mesh, fit, 1.05, -0.10, -0.35, 1.30, -1.45, 1.85,
                        0.17, 0.035, uv, trim);
            }
            case "straw_boater" -> {
                box(mesh, fit, 0, 0, -0.72, 2.55, 2.55, 0.16, uv, rgb);
                box(mesh, fit, 0, 0, 0.18, 1.26, 1.26, 0.78, uv, rgb);
                box(mesh, fit, 0, 0, -0.42, 1.32, 1.32, 0.22, uv, trim);
            }
            case "oilskin_hood" -> {
                // Up over the head rather than thrown back, which is the
                // version that changes a silhouette — and the reason this is
                // one of the two pieces drawn in the wearer's own colour.
                box(mesh, fit, 0, -0.15, -1.90, 1.58, 1.62, 1.62, uv, rgb);
                box(mesh, fit, 0, 1.70, -1.15, 1.42, 0.62, 0.15, uv, trim);
                box(mesh, fit, 0, -1.35, -2.90, 1.45, 0.45, 0.85, uv,
                        ShopModel.shade(rgb, 0.88));
            }
            case "antler_circlet" -> {
                box(mesh, fit, 0, 0, -0.52, 1.32, 1.32, 0.18, uv, trim);
                for (int side = -1; side <= 1; side += 2) {
                    // A beam up and out, a brow tine forward off it and a second
                    // tine back — three struts, which is the fewest that reads
                    // as an antler rather than as a stick.
                    strut(mesh, fit, side * 0.95, 0.05, -0.40,
                            side * 1.95, -0.25, 2.30, 0.13, 0.13, uv, rgb);
                    strut(mesh, fit, side * 1.45, -0.10, 1.05,
                            side * 2.35, 1.10, 1.75, 0.09, 0.09, uv, rgb);
                    strut(mesh, fit, side * 1.95, -0.25, 2.30,
                            side * 2.90, -1.05, 2.95, 0.09, 0.09, uv, rgb);
                }
            }

            // --- face ------------------------------------------------------
            case "wire_spectacles" -> {
                for (int side = -1; side <= 1; side += 2) {
                    box(mesh, fit, side * 0.50, 1.08, 0.10, 0.42, 0.07, 0.36, uv, trim);
                    box(mesh, fit, side * 1.02, 0.20, 0.10, 0.07, 0.90, 0.07, uv, rgb);
                }
                box(mesh, fit, 0, 1.08, 0.10, 0.16, 0.07, 0.08, uv, rgb);
            }
            case "snow_goggles" -> {
                box(mesh, fit, 0, 0.94, 0.18, 1.10, 0.22, 0.46, uv, rgb);
                box(mesh, fit, 0, 1.08, 0.18, 0.98, 0.14, 0.32, uv, trim);
                for (int side = -1; side <= 1; side += 2) {
                    box(mesh, fit, side * 1.02, 0.05, 0.18, 0.10, 1.05, 0.15, uv, rgb);
                }
            }
            case "moth_veil" -> {
                box(mesh, fit, 0, 0, 1.02, 1.24, 1.24, 0.10, uv, trim);
                box(mesh, fit, 0, 1.08, -0.40, 1.02, 0.06, 1.20, uv, rgb);
                for (int side = -1; side <= 1; side += 2) {
                    box(mesh, fit, side * 1.06, 0.10, -0.40, 0.06, 1.02, 1.20, uv, rgb);
                }
            }

            // --- neck ------------------------------------------------------
            // <b>Everything here clears CHEST_OUT.</b> The chest is 0.22 m
            // deep — nearly two sizes — so a scarf tail written at "just in
            // front of the neck" is a scarf tail inside the person wearing it.
            case "wool_scarf" -> {
                box(mesh, fit, 0, 0, 0, 1.55, 2.05, 0.40, uv, rgb);
                box(mesh, fit, 0, CHEST_OUT + 0.15, -0.30, 0.46, 0.34, 0.42, uv, trim);
                // One end left long, which is the only part of a scarf that
                // does anything at all once it is on somebody.
                box(mesh, fit, 0.62, CHEST_OUT + 0.05, -1.80, 0.38, 0.18, 1.55, uv, rgb);
            }
            case "glass_lanyard" -> {
                box(mesh, fit, 0, 0, 0.08, 1.42, 1.92, 0.18, uv, rgb);
                for (int side = -1; side <= 1; side += 2) {
                    box(mesh, fit, side * 0.50, CHEST_OUT, -1.70, 0.16, 0.10, 1.80,
                            uv, rgb);
                }
                blob(mesh, fit, 0, CHEST_OUT + 0.10, -3.45, 0.34, uv, trim);
            }
            case "fur_collar" -> {
                box(mesh, fit, 0, 0, 0.10, 1.90, 2.25, 0.62, uv, rgb);
                box(mesh, fit, 0, 0, -0.56, 1.66, 2.00, 0.22, uv, trim);
            }

            // --- back ------------------------------------------------------
            case "rolled_bedroll" -> {
                strut(mesh, fit, -1.10, 0, 1.38, 1.10, 0, 1.38, 0.42, 0.42, uv, rgb);
                for (int side = -1; side <= 1; side += 2) {
                    box(mesh, fit, side * 0.58, 0, 1.38, 0.10, 0.52, 0.50, uv, trim);
                }
            }
            case "oilskin_cape" -> {
                box(mesh, fit, 0, 0, 1.95, 2.05, 0.78, 0.30, uv, rgb);
                box(mesh, fit, 0, -0.32, -2.50, 2.20, 0.22, 3.55, uv,
                        ShopModel.shade(rgb, 0.92));
                box(mesh, fit, 0, -0.32, 1.30, 2.20, 0.22, 0.26, uv, trim);
            }
            case "heron_cloak" -> {
                box(mesh, fit, 0, -0.10, 1.95, 2.10, 0.82, 0.34, uv,
                        ShopModel.shade(rgb, 0.9));
                box(mesh, fit, 0, -0.32, -2.60, 2.35, 0.22, 3.60, uv, rgb);
                // Courses of feather down the panel — the same trick
                // {@code ShopModel} plays on a roof, and for its reason: lines
                // across a slab read as a made surface and a bare slab reads as
                // a board.
                for (int course = 0; course < 4; course++) {
                    box(mesh, fit, 0, -0.48, 0.30 - course * 1.65, 2.28, 0.14, 0.34, uv,
                            ShopModel.shade(trim, course % 2 == 0 ? 1.0 : 0.88));
                }
            }

            // --- hands -----------------------------------------------------
            case "wool_mittens" -> {
                box(mesh, fit, 0, 0, 0, 1.30, 1.30, 1.30, uv, rgb);
                box(mesh, fit, 0, 0, 1.38, 1.16, 1.16, 0.44, uv, trim);
            }
            case "leather_gloves" -> {
                box(mesh, fit, 0, 0, 0, 1.18, 1.18, 1.18, uv, rgb);
                box(mesh, fit, 0, 0, 1.16, 1.22, 1.22, 0.26, uv, trim);
            }

            // --- feet ------------------------------------------------------
            case "canvas_gaiters" -> {
                box(mesh, fit, 0, 0, 2.55, 0.98, 1.02, 2.30, uv, rgb);
                box(mesh, fit, 0, 0, 4.60, 1.06, 1.10, 0.24, uv, trim);
                box(mesh, fit, 0, 0, 0.45, 1.06, 1.10, 0.22, uv, trim);
            }
            case "river_waders" -> {
                box(mesh, fit, 0, 0, 0.10, 1.14, 1.10, 1.10, uv, rgb);
                box(mesh, fit, 0, 0, 2.70, 1.20, 1.26, 2.50, uv, rgb);
                box(mesh, fit, 0, 0, 5.05, 1.26, 1.32, 0.26, uv, trim);
            }

            // A key with no drawing is not drawn. Cosmetics.byKey has already
            // said it is a real piece, so this is the one case that matters:
            // somebody added a row to the catalogue and not a case here, and
            // CosmeticsTest.everyPieceInTheCatalogueDraws is what tells them.
            default -> { }
        }
    }

    /**
     * A box at an offset in the fit's own frame.
     *
     * @param across how far to the wearer's right, in sizes
     * @param ahead  how far in front of them
     * @param along  how far up the part it is worn on
     * @param hx     half-extent across the wearer, in sizes; {@code hy} fore and
     *               aft, {@code hz} vertical
     */
    private static void box(Mesh.Builder mesh, Fit fit, double across, double ahead,
                            double along, double hx, double hy, double hz,
                            float[] uv, int rgb) {
        double[] p = at(fit, across, ahead, along);
        double s = fit.size();
        Shapes.box(mesh, p[0], p[1], p[2], hx * s, hy * s, hz * s, fit.yaw(), uv, rgb);
    }

    /** A strut between two offsets in the fit's own frame. */
    private static void strut(Mesh.Builder mesh, Fit fit,
                              double across0, double ahead0, double along0,
                              double across1, double ahead1, double along1,
                              double halfWidth, double halfThick, float[] uv, int rgb) {
        double[] a = at(fit, across0, ahead0, along0);
        double[] b = at(fit, across1, ahead1, along1);
        double s = fit.size();
        Shapes.strut(mesh, a[0], a[1], a[2], b[0], b[1], b[2],
                halfWidth * s, halfThick * s, uv, rgb);
    }

    /** A rounded lump at an offset — a bobble, a brass ring. */
    private static void blob(Mesh.Builder mesh, Fit fit, double across, double ahead,
                             double along, double radius, float[] uv, int rgb) {
        double[] p = at(fit, across, ahead, along);
        double s = fit.size();
        Shapes.blob(mesh, p[0], p[1], p[2], radius * s, radius * s, radius * s,
                fit.yaw(), uv, rgb);
    }

    /** An offset in sizes, turned into a point in the world. */
    private static double[] at(Fit fit, double across, double ahead, double along) {
        double[] f = fit.forward();
        double[] r = fit.right(f);
        double s = fit.size();
        return new double[]{
            fit.x() + r[0] * across * s + f[0] * ahead * s + fit.upX() * along * s,
            fit.y() + r[1] * across * s + f[1] * ahead * s + fit.upY() * along * s,
            fit.z() + r[2] * across * s + f[2] * ahead * s + fit.upZ() * along * s
        };
    }
}
