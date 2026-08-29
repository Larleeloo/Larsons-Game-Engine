package com.larsons.engine.watch.render;

import com.larsons.engine.watch.Shops;
import com.larsons.engine.watch.life.AnimalDef;
import com.larsons.engine.watch.life.AnimalModels;
import com.larsons.engine.watch.life.AnimState;
import com.larsons.engine.watch.world.WatchMaterial;
import com.larsons.engine.watch.world.WatchMaterials;

/**
 * The person behind the counter.
 *
 * <h2>Why this is not a {@link WalkerModel}</h2>
 *
 * <p>Every other figure in this game is a walker — a coat, a head, four limbs
 * and a hat, tinted per player, and exactly right for somebody two hundred
 * metres away across a valley whom you will mostly see running. The keeper is
 * the one person in the world you stand a metre from and look at while you
 * decide what to buy, and at a metre a tinted walker is a mannequin.
 *
 * <p>So this is the same skeleton with about three times the parts on it: a
 * coat with a skirt and facings over a shirt, an apron, a belt with pouches
 * hung off it, a scarf, a face with a nose and a jaw, hair, an optional beard,
 * optional spectacles, one of three hats, and a pencil behind the ear.
 * Everything is chosen once from the post's own id ({@link Shops.Keeper}), so a
 * keeper looks the way they looked last week.
 *
 * <h2>They are alive without going anywhere</h2>
 *
 * <p>An NPC standing perfectly still is a statue, and a statue behind a counter
 * is worse than no NPC. Three things move, none of them a walk cycle:
 *
 * <ul>
 *   <li><b>breathing</b> — the chest and shoulders rise on a slow cycle;</li>
 *   <li><b>weight</b> — they shift from one foot to the other every few
 *       seconds, which tilts the hips and drops one shoulder;</li>
 *   <li><b>the ledger</b> — once every {@value #BUSY_PERIOD} seconds or so they
 *       lean in and write in it, which is the gesture that says what they are
 *       for.</li>
 * </ul>
 *
 * <p>And they <b>look at you</b>: the head turns toward whoever is nearest,
 * within the range a neck actually turns, easing rather than snapping. That one
 * thing does more work than the other three together — a figure whose eyes
 * follow you across the front of a shop is a person, and a figure facing
 * straight ahead while you walk round them is furniture.
 *
 * <p>All of it runs on the world's animation clock in seconds, never on a frame
 * count. See {@code WatchScene.animClock}.
 */
public final class KeeperModel {

    /** How tall a keeper of {@code build} 1 is, in metres. */
    public static final double HEIGHT = 1.74;

    /**
     * How far behind the counter a keeper stands, in metres.
     *
     * <p>Public, and read by the scene when it places the figure, because two
     * separate opinions about where a keeper stands is how the ledger they are
     * writing in ends up on the wrong side of the counter from their hand. The
     * counter is {@link Shops#COUNTER_OUT} in front of the building's centre and
     * the keeper is this far back from it.
     */
    public static final double BEHIND_COUNTER = 0.85;

    /** How long one breath takes, in seconds. */
    private static final double BREATH_PERIOD = 4.4;

    /** How often the weight shifts, in seconds. */
    private static final double SHIFT_PERIOD = 7.3;

    /** How often they turn to the ledger, and for how long. */
    private static final double BUSY_PERIOD = 13.0, BUSY_LENGTH = 3.4;

    /** How far a neck turns, in radians. */
    private static final double NECK_LIMIT = 1.15;

    /** How far along the counter the keeper's own animal sits, in metres. */
    private static final double COMPANION_ACROSS = 0.95;

    private KeeperModel() {}

    /**
     * Draw a keeper standing at their counter.
     *
     * @param x       where they stand, relative to the mesh's origin
     * @param z       the deck under their feet, in world metres
     * @param yaw     which way the counter faces — the way they face when there
     *                is nobody there
     * @param lookYaw the way to whoever they are looking at, in world radians;
     *                pass {@code yaw} for nobody
     * @param clock   the world animation clock, in seconds
     * @param phase   an offset into the idle cycles, so two keepers in a day's
     *                walk are not breathing in step
     */
    public static void keeper(Mesh.Builder mesh, Shops.Keeper keeper, double x, double y,
                              double z, double yaw, double lookYaw, double clock,
                              double phase) {
        float[] uv = new float[4];
        WatchMaterials.uv(WatchMaterial.PLANK, uv);

        double height = HEIGHT * keeper.build();
        double t = clock + phase;

        // The three idle cycles. `busy` is a smooth pulse rather than a switch:
        // a keeper who snapped between standing and writing would be the one
        // cut in a scene otherwise built entirely out of eased numbers.
        double breath = Math.sin(t * Math.PI * 2 / BREATH_PERIOD);
        double shift = Math.sin(t * Math.PI * 2 / SHIFT_PERIOD);
        double busy = pulse(t, BUSY_PERIOD, BUSY_LENGTH);

        double cos = Math.cos(yaw), sin = Math.sin(yaw);
        double fx = sin, fy = -cos;
        double sx = cos, sy = sin;

        int coat = keeper.coatRgb();
        int trim = keeper.trimRgb();
        int shirt = keeper.shirtRgb();
        int skin = keeper.skinRgb();
        int hair = keeper.hairRgb();
        int leather = 0x4A3626;

        // Leaning in over the counter is what "busy" is: the whole body above
        // the hips goes forward together, the way a person leaning on a counter
        // does, rather than the arm reaching out on its own.
        double lean = 0.22 * busy;
        double hipZ = height * 0.47;
        double lead = Math.sin(lean);

        legs(mesh, x, y, z, fx, fy, sx, sy, yaw, height, hipZ, shift, uv, coat, leather);
        torso(mesh, x, y, z, fx, fy, sx, sy, yaw, height, hipZ, lead, breath, shift, uv,
                coat, trim, shirt, leather);
        arms(mesh, x, y, z, fx, fy, sx, sy, yaw, height, lead, busy, shift, uv, coat, skin);

        // The head, on top of everything, turned toward whoever is there. The
        // turn is limited to what a neck does — past that a keeper would face
        // backwards over their own shoulder, which is not friendly, it is
        // alarming.
        double turn = clamp(wrap(lookYaw - yaw), -NECK_LIMIT, NECK_LIMIT);
        double headAlong = lead * (height * 0.94 - hipZ) + 0.03 * busy;
        head(mesh, x + fx * headAlong, y + fy * headAlong,
                z + height * 0.86 + breath * 0.006, yaw + turn, height * 0.14,
                busy, keeper, uv, skin, hair, coat, trim);

        companion(mesh, keeper, x, y, z, fx, fy, sx, sy, yaw, t);
    }

    // --- the body --------------------------------------------------------------------

    /**
     * Two legs, standing.
     *
     * <p>Straight, and the only thing that happens to them is the weight shift:
     * the loaded leg is the straight one and the other bends a little at the
     * knee and takes its heel off the deck. That is what standing about looks
     * like, and it is the difference between somebody at rest and somebody at
     * attention.
     */
    private static void legs(Mesh.Builder mesh, double x, double y, double z,
                             double fx, double fy, double sx, double sy, double yaw,
                             double height, double hipZ, double shift, float[] uv,
                             int coat, int boot) {
        double legLength = height * 0.45;
        double thigh = legLength * 0.52, shin = legLength * 0.48;
        for (int i = 0; i < 2; i++) {
            double side = i == 0 ? -1 : 1;
            // How much of the weight is on this leg: one at a time, easing over.
            double load = 0.5 + 0.5 * shift * side;
            double bend = 0.30 * (1 - load);
            double hx = x + sx * side * 0.10, hy = y + sy * side * 0.10;
            double kneeZ = z + hipZ - Math.cos(bend * 0.5) * thigh;
            double kx = hx + fx * Math.sin(bend * 0.5) * thigh;
            double ky = hy + fy * Math.sin(bend * 0.5) * thigh;
            double ankleZ = kneeZ - Math.cos(bend) * shin + 0.02 * (1 - load);
            double axp = kx - fx * Math.sin(bend) * shin * 0.4;
            double ayp = ky - fy * Math.sin(bend) * shin * 0.4;
            Shapes.strut(mesh, hx, hy, z + hipZ, kx, ky, kneeZ, 0.085, 0.085,
                    sx, sy, 0, uv, ShopModel.shade(coat, 0.8));
            Shapes.strut(mesh, kx, ky, kneeZ, axp, ayp, ankleZ, 0.072, 0.072,
                    sx, sy, 0, uv, ShopModel.shade(coat, 0.8));
            Shapes.box(mesh, axp + fx * 0.04, ayp + fy * 0.04, ankleZ - 0.03, 0.09, 0.13,
                    0.045, yaw, uv, boot);
            // A cuff over the top of the boot. Two boxes make a boot; three make
            // a boot somebody put on.
            Shapes.box(mesh, axp, ayp, ankleZ + 0.09, 0.085, 0.10, 0.06, yaw, uv,
                    ShopModel.shade(boot, 1.18));
        }
    }

    /** Shirt, coat, coat skirt, facings, apron, belt and pouches, and a scarf. */
    private static void torso(Mesh.Builder mesh, double x, double y, double z,
                              double fx, double fy, double sx, double sy, double yaw,
                              double height, double hipZ, double lead, double breath,
                              double shift, float[] uv, int coat, int trim, int shirt,
                              int leather) {
        double chestZ = height * 0.70;
        double chestAlong = lead * (chestZ - hipZ);
        double cx = x + fx * chestAlong, cy = y + fy * chestAlong;

        // The shirt first, and slightly narrower, so what shows between the
        // coat's facings is a shirt rather than a gap.
        Shapes.box(mesh, cx, cy, z + chestZ + breath * 0.008, 0.15, 0.14,
                height * 0.16, yaw, uv, shirt);
        // The coat body over it, wider and deeper, so it reads as worn over.
        Shapes.box(mesh, cx - fx * 0.015, cy - fy * 0.015, z + chestZ + breath * 0.008,
                0.175, 0.155, height * 0.155, yaw, uv, coat);
        // Facings down the front, in the lining colour.
        for (int b = -1; b <= 1; b += 2) {
            Shapes.box(mesh, cx + fx * 0.14 + sx * b * 0.075,
                    cy + fy * 0.14 + sy * b * 0.075, z + chestZ + 0.02,
                    0.045, 0.02, height * 0.14, yaw, uv, trim);
        }
        // The collar.
        Shapes.box(mesh, cx, cy, z + height * 0.845, 0.13, 0.115, 0.035, yaw, uv,
                ShopModel.shade(coat, 1.15));

        // The belt, and the two pouches every pedlar in the world has on it.
        double beltZ = hipZ + 0.09;
        double beltAlong = lead * (beltZ - hipZ);
        Shapes.box(mesh, x + fx * beltAlong, y + fy * beltAlong, z + beltZ,
                0.175, 0.15, 0.035, yaw, uv, leather);
        for (int b = -1; b <= 1; b += 2) {
            Shapes.box(mesh, x + fx * beltAlong + sx * b * 0.15 + fx * 0.03,
                    y + fy * beltAlong + sy * b * 0.15 + fy * 0.03, z + beltZ - 0.07,
                    0.055, 0.045, 0.07, yaw, uv, ShopModel.shade(leather, b > 0 ? 1.2 : 0.9));
        }
        // The coat's skirt, below the belt and wider than the body — which is
        // what makes the silhouette a coat rather than a jerkin.
        Shapes.box(mesh, x + fx * beltAlong * 0.6, y + fy * beltAlong * 0.6,
                z + hipZ - 0.10, 0.185, 0.165, 0.19, yaw, uv,
                ShopModel.shade(coat, 0.92));
        // The apron over the front of it, hanging straight whatever the coat does.
        Shapes.box(mesh, x + fx * 0.17, y + fy * 0.17, z + hipZ + 0.02,
                0.145, 0.02, 0.30, yaw, uv, ShopModel.shade(trim, 0.86));

        // A scarf, wound at the neck with one end over a shoulder. The end
        // swings with the weight shift, which is the only cloth in this game
        // that does anything at all.
        double neckZ = height * 0.855;
        Shapes.box(mesh, cx, cy, z + neckZ, 0.115, 0.10, 0.045, yaw, uv, trim);
        Shapes.strut(mesh, cx + sx * 0.06, cy + sy * 0.06, z + neckZ,
                cx + sx * (0.09 + shift * 0.03) + fx * 0.06,
                cy + sy * (0.09 + shift * 0.03) + fy * 0.06, z + neckZ - 0.28,
                0.035, 0.02, sx, sy, 0, uv, ShopModel.shade(trim, 0.94));
    }

    /**
     * Two arms, hanging — until they are writing.
     *
     * <p>The right arm goes down onto the counter and the left stays where it
     * is, because a person writing does not move both. The elbow folds through
     * the reach rather than the arm swinging as one piece from the shoulder,
     * which is the fault {@code WalkerModel}'s legs had before {@code strut}
     * existed.
     */
    private static void arms(Mesh.Builder mesh, double x, double y, double z,
                             double fx, double fy, double sx, double sy, double yaw,
                             double height, double lead, double busy, double shift,
                             float[] uv, int coat, int skin) {
        double shoulderZ = z + height * 0.80;
        double armLength = height * 0.36;
        double upper = armLength * 0.52, fore = armLength * 0.48;
        double hipZ = height * 0.47;
        double shoulderAlong = lead * (height * 0.80 - hipZ);

        for (int i = 0; i < 2; i++) {
            double side = i == 0 ? -1 : 1;
            // Hanging, with the shoulder on the unloaded side a little lower.
            double lean = 0.10 + 0.04 * shift * side;
            double bend = 0.28;
            // The writing hand: the right, forward and down onto the ledger.
            if (side > 0) {
                lean += busy * 0.95;
                bend += busy * 0.55;
            } else {
                // …and the left takes the weight on the counter beside it.
                lean += busy * 0.55;
                bend += busy * 0.25;
            }
            double ex = Math.sin(lean) * upper;
            double eu = -Math.cos(lean) * upper;
            double wx = ex + Math.sin(lean + bend) * fore;
            double wu = eu - Math.cos(lean + bend) * fore;

            double shX = x + sx * side * 0.21 + fx * shoulderAlong;
            double shY = y + sy * side * 0.21 + fy * shoulderAlong;
            double drop = -0.012 * shift * side;
            Shapes.strut(mesh, shX, shY, shoulderZ + drop,
                    shX + fx * ex, shY + fy * ex, shoulderZ + drop + eu,
                    0.068, 0.068, sx, sy, 0, uv, coat);
            // A turned-back cuff at the wrist, in the coat's own colour
            // lightened — the smallest part on the model and the one that makes
            // the hand look attached rather than floating.
            Shapes.strut(mesh, shX + fx * ex, shY + fy * ex, shoulderZ + drop + eu,
                    shX + fx * wx, shY + fy * wx, shoulderZ + drop + wu,
                    0.056, 0.056, sx, sy, 0, uv, coat);
            Shapes.box(mesh, shX + fx * wx * 0.93, shY + fy * wx * 0.93,
                    shoulderZ + drop + wu * 0.93, 0.062, 0.062, 0.035, yaw, uv,
                    ShopModel.shade(coat, 1.2));
            Shapes.box(mesh, shX + fx * wx, shY + fy * wx, shoulderZ + drop + wu - 0.02,
                    0.052, 0.052, 0.055, yaw, uv, skin);
        }
    }

    /**
     * The face, and what is on it.
     *
     * <p>Drawn about a point at the neck and turned by the neck's own yaw rather
     * than the body's, so a keeper can watch a customer walk along the front of
     * the counter without their shoulders following.
     */
    private static void head(Mesh.Builder mesh, double x, double y, double z, double yaw,
                            double size, double busy, Shops.Keeper keeper, float[] uv,
                            int skin, int hair, int coat, int trim) {
        double cos = Math.cos(yaw), sin = Math.sin(yaw);
        double fx = sin, fy = -cos;
        double sx = cos, sy = sin;
        // Reading tips the head down, which is most of what makes the gesture
        // legible from in front.
        double z0 = z + size * 0.9 - busy * 0.035;
        double face = size * 0.78;

        Shapes.box(mesh, x, y, z0, face, face * 0.92, size * 0.82, yaw, uv, skin);
        // A jaw, narrower and lower — a head that is one cube is a head that is
        // a cube.
        Shapes.box(mesh, x + fx * 0.012, y + fy * 0.012, z0 - size * 0.66,
                face * 0.82, face * 0.80, size * 0.24, yaw, uv,
                ShopModel.shade(skin, 0.96));
        // Ears.
        for (int b = -1; b <= 1; b += 2) {
            Shapes.box(mesh, x + sx * b * face * 0.95, y + sy * b * face * 0.95, z0,
                    0.018, 0.032, 0.045, yaw, uv, ShopModel.shade(skin, 0.94));
        }
        // A nose, and a brow over it.
        Shapes.box(mesh, x + fx * face * 1.0, y + fy * face * 1.0, z0 - size * 0.10,
                0.03, 0.035, 0.045, yaw, uv, ShopModel.shade(skin, 1.04));
        Shapes.box(mesh, x + fx * face * 0.92, y + fy * face * 0.92, z0 + size * 0.28,
                face * 0.72, 0.02, 0.022, yaw, uv, ShopModel.shade(hair, 0.9));
        // Eyes.
        for (int b = -1; b <= 1; b += 2) {
            Shapes.box(mesh, x + fx * face * 0.95 + sx * b * 0.05,
                    y + fy * face * 0.95 + sy * b * 0.05, z0 + size * 0.12,
                    0.022, 0.014, 0.022, yaw, uv, 0x241C18);
        }
        if (keeper.spectacles()) {
            for (int b = -1; b <= 1; b += 2) {
                Shapes.box(mesh, x + fx * face * 1.02 + sx * b * 0.052,
                        y + fy * face * 1.02 + sy * b * 0.052, z0 + size * 0.12,
                        0.042, 0.008, 0.042, yaw, uv, 0xB8A050);
            }
            Shapes.box(mesh, x + fx * face * 1.02, y + fy * face * 1.02, z0 + size * 0.12,
                    0.015, 0.008, 0.008, yaw, uv, 0xB8A050);
            // The arm of the spectacles, back to the ear.
            for (int b = -1; b <= 1; b += 2) {
                Shapes.strut(mesh, x + fx * face * 1.0 + sx * b * 0.09,
                        y + fy * face * 1.0 + sy * b * 0.09, z0 + size * 0.12,
                        x + sx * b * face * 0.95, y + sy * b * face * 0.95,
                        z0 + size * 0.14, 0.006, 0.006, uv, 0xB8A050);
            }
        }
        if (keeper.beard()) {
            Shapes.box(mesh, x + fx * face * 0.62, y + fy * face * 0.62,
                    z0 - size * 0.62, face * 0.78, face * 0.5, size * 0.34, yaw, uv,
                    hair);
        }
        // Hair, as a cap round the back and sides, so a hat sits on hair rather
        // than on a bare skull.
        Shapes.box(mesh, x - fx * 0.02, y - fy * 0.02, z0 + size * 0.42,
                face * 1.04, face * 0.98, size * 0.30, yaw, uv, hair);
        hat(mesh, keeper.hat(), x, y, z0 + size * 0.72, yaw, size, uv, coat, trim, hair);

        // A pencil behind the ear, on the side the writing hand can reach.
        Shapes.strut(mesh, x + sx * face * 0.98 + fx * 0.02,
                y + sy * face * 0.98 + fy * 0.02, z0 + size * 0.06,
                x + sx * face * 0.98 - fx * 0.10,
                y + sy * face * 0.98 - fy * 0.10, z0 + size * 0.20,
                0.008, 0.008, uv, 0xC08A3A);
    }

    /** One of three hats, each with a silhouette of its own from behind. */
    private static void hat(Mesh.Builder mesh, Shops.Hat style, double x, double y,
                            double z, double yaw, double size, float[] uv, int coat,
                            int trim, int hair) {
        switch (style) {
            case BROAD_BRIM -> {
                Shapes.box(mesh, x, y, z, size * 1.85, size * 1.7, 0.018, yaw, uv,
                        ShopModel.shade(coat, 0.78));
                Shapes.box(mesh, x, y, z + size * 0.42, size * 0.82, size * 0.80,
                        size * 0.40, yaw, uv, ShopModel.shade(coat, 0.86));
                // The hatband, which is the whole reason a hat reads as a hat
                // and not as two stacked boxes.
                Shapes.box(mesh, x, y, z + size * 0.14, size * 0.86, size * 0.84,
                        size * 0.09, yaw, uv, trim);
            }
            case WOOL_CAP -> {
                Shapes.box(mesh, x, y, z - size * 0.08, size * 0.92, size * 0.90,
                        size * 0.28, yaw, uv, trim);
                Shapes.box(mesh, x, y, z + size * 0.22, size * 0.80, size * 0.78,
                        size * 0.22, yaw, uv, ShopModel.shade(trim, 0.88));
                Shapes.blob(mesh, x, y, z + size * 0.52, size * 0.24, size * 0.24,
                        size * 0.24, yaw, uv, ShopModel.shade(trim, 1.2));
            }
            case HOOD -> {
                // Thrown back off the head and lying on the shoulders, which is
                // the version that leaves the face visible.
                Shapes.box(mesh, x - Math.sin(yaw) * size * 0.55,
                        y + Math.cos(yaw) * size * 0.55, z - size * 0.85,
                        size * 1.0, size * 0.55, size * 0.55, yaw, uv,
                        ShopModel.shade(coat, 0.82));
                Shapes.box(mesh, x, y, z - size * 0.55, size * 1.05, size * 1.0,
                        size * 0.12, yaw, uv, ShopModel.shade(coat, 0.9));
            }
        }
    }

    /**
     * The keeper's own animal, sitting on the counter beside them.
     *
     * <p>Drawn through the same {@link AnimalModels} every wild creature is, at
     * the same scale, because it <em>is</em> one — a species out of the country
     * the post stands in, tamed the way a player's own pets are tamed. Nothing
     * about it is a special case except that it is not going anywhere.
     */
    private static void companion(Mesh.Builder mesh, Shops.Keeper keeper, double x,
                                  double y, double z, double fx, double fy, double sx,
                                  double sy, double yaw, double t) {
        AnimalDef def = keeper.companionDef();
        if (def == null) return;
        // On the counter, which is BEHIND_COUNTER in front of the keeper's own
        // feet and — because the keeper is standing on the deck and the counter
        // is measured from the ground — this far above them. Both are derived
        // rather than chosen, so moving the counter moves the jackdaw with it.
        double cx = x + fx * BEHIND_COUNTER + sx * COMPANION_ACROSS;
        double cy = y + fy * BEHIND_COUNTER + sy * COMPANION_ACROSS;
        double top = z + Shops.COUNTER_TOP - ShopModel.DECK + 0.04;
        AnimalModels.Loaded model = AnimalModels.of(def);
        // Idling on the spot, and turned a little off the keeper's own facing so
        // the pair of them do not look like a matched pair of statues. The phase
        // is the world clock, so it preens and settles like anything else alive.
        model.geometry().mesh(mesh, def, cx, cy, top, yaw - 0.5, AnimState.IDLE,
                t * 0.35, 1, model.poses());
    }

    // --- plumbing --------------------------------------------------------------------

    /**
     * A smooth pulse that is up for {@code length} out of every {@code period}
     * seconds, with a raised-cosine edge at each end.
     *
     * <p>Continuous in value <em>and</em> in slope at both joins, for the reason
     * every other cycle in this game is: the eye finds a discontinuity in an
     * idle animation instantly, and a keeper who snapped into writing would look
     * like a dropped frame.
     */
    private static double pulse(double t, double period, double length) {
        double at = t - Math.floor(t / period) * period;
        if (at > length) return 0;
        return 0.5 - 0.5 * Math.cos(at / length * Math.PI * 2);
    }

    private static double wrap(double angle) {
        double a = angle;
        while (a > Math.PI) a -= Math.PI * 2;
        while (a < -Math.PI) a += Math.PI * 2;
        return a;
    }

    private static double clamp(double v, double low, double high) {
        return v < low ? low : v > high ? high : v;
    }
}
