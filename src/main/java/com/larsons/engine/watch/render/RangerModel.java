package com.larsons.engine.watch.render;

import com.larsons.engine.watch.life.AnimState;
import com.larsons.engine.watch.model.ModelRig;
import com.larsons.engine.watch.model.SceneModel;
import com.larsons.engine.watch.model.SceneModels;
import com.larsons.engine.watch.world.WatchMaterial;
import com.larsons.engine.watch.world.WatchMaterials;

/**
 * The ranger outside the trading post — <b>and the first character you can
 * replace with a file.</b>
 *
 * <h2>What is here and why</h2>
 *
 * <p>Two things, and the second is the point of the first. The second is
 * {@link #ranger}, which looks for {@code watch/models/characters/ranger.glb}
 * and draws it if it is there. The first is everything below that: a ranger
 * built out of the same boxes the keeper is, so that the slot is occupied by
 * somebody before any art arrives, and so that an artist has something concrete
 * to match rather than a paragraph about style.
 *
 * <p>That is the pattern the whole game already runs on — see
 * {@code AnimalModel}, whose thirteen hundred procedural species are the
 * fallback that keeps everything drawable while the art arrives one file at a
 * time — applied to a person for the first time.
 *
 * <h2>Why a ranger, and why outside</h2>
 *
 * <p>The keeper stands behind a counter and sells you things. A ranger stands
 * in front of the post with a pair of binoculars round their neck and
 * <em>looks at the wood</em>, which is what the player is there to do. They are
 * placed out in the clearing rather than at the counter so that the two figures
 * read as two people at a distance: one framed by a building, one not.
 *
 * <p>Everything about them is derived from the post's own id, exactly as
 * {@code Shops.Keeper} is, so the ranger at a post is the same person next
 * week.
 *
 * <h2>They are alive without going anywhere</h2>
 *
 * <p>Same three-part rule as the keeper, with the third gesture changed to the
 * one that says what a ranger is for:
 *
 * <ul>
 *   <li><b>breathing</b> — the chest rises on a slow cycle;</li>
 *   <li><b>weight</b> — they shift from foot to foot every few seconds;</li>
 *   <li><b>the glass</b> — once every {@value #SCAN_PERIOD} seconds they raise
 *       the binoculars and sweep the treeline.</li>
 * </ul>
 *
 * <p>And they look at you, within the range a neck turns — including when they
 * are an imported model, because {@link SceneModel#mesh} takes the head's turn
 * as a parameter for exactly this.
 */
public final class RangerModel {

    /** The name an imported model must be filed under. */
    public static final String MODEL = "characters/ranger";

    /**
     * How tall a ranger of {@code build} 1 is, in metres — <b>crown of the hat
     * to the sole of the boot.</b>
     *
     * <p>That definition is doing real work. An imported model is normalised to
     * its own height and then drawn at this many metres, so if the boxes below
     * meant something else by "height" — the keeper's constant, for instance,
     * means the body and leaves the hat above it — then dropping in a
     * {@code .glb} would visibly resize the ranger. A replacement that is not
     * the same size as what it replaced is not a replacement.
     */
    public static final double HEIGHT = 1.78;

    /**
     * Where the crown of the hat lands, in the body scale the boxes are drawn
     * in: {@code 0.86} up to the neck, plus {@code 2.56} head units of face and
     * hat above it.
     *
     * <p>Written down rather than discovered because it is the number that ties
     * {@link #HEIGHT} to the geometry — change any of the three head or hat
     * offsets and this changes with them, and {@code ModelImportTest} is what
     * says so.
     */
    private static final double CROWN = 0.86 + 2.56 * 0.135;

    /**
     * Where they stand relative to the post's centre, in metres: this far along
     * the front of it, and this far out from it.
     *
     * <p>Both clear the building — {@code Shops.HALF_WIDTH} is 2.7 and
     * {@code HALF_DEPTH} is 2.0 — and the pair of them is 4.1 m from the
     * centre, comfortably inside {@code Shops.CLEARING} at 8.5, so a ranger
     * never ends up standing in a tree. Public because the scene does the
     * placing: one opinion about where they stand rather than two.
     */
    public static final double BESIDE = 2.9, OUT = 2.9;

    /** How long one breath takes, in seconds. */
    private static final double BREATH_PERIOD = 4.9;

    /** How often the weight shifts, in seconds. */
    private static final double SHIFT_PERIOD = 8.1;

    /** How often they raise the glass, and for how long. */
    private static final double SCAN_PERIOD = 11.0, SCAN_LENGTH = 4.2;

    /** How far a neck turns, in radians. */
    private static final double NECK_LIMIT = 1.15;

    /** The service's own colours. A ranger is a uniform; only the person varies. */
    private static final int COAT = 0x3C5240;
    private static final int TROUSER = 0x6B6247;
    private static final int LEATHER = 0x4A3626;
    private static final int BRASS = 0xB8A050;
    private static final int GLASS = 0x243230;

    private static final String[] NAMES = {
            "Wren Aldercott", "Hollis Fen", "Marsh Ivey", "Bracken Coil", "Tamsin Rook",
            "Ozias Kell", "Linnet Hay", "Fennimore Ash", "Perrin Slate", "Yarrow Beck",
            "Corvin Mire", "Sable Thicket"
    };

    private static final int[] SKINS = {
            0xE0B48E, 0xC98F63, 0x9A6540, 0x6E4529, 0x4A2E1C, 0xF0CBA6
    };

    private static final int[] HAIRS = {
            0x2A2018, 0x4A3220, 0x6B4A2A, 0x8A6A3A, 0x9A9A96, 0x1C1814
    };

    private RangerModel() {}

    /**
     * A ranger.
     *
     * @param trim  their neckerchief and hatband, which is the one thing about
     *              the uniform that is theirs
     * @param build how tall they are, as a share of {@link #HEIGHT}
     */
    public record Ranger(String name, int trimRgb, int skinRgb, int hairRgb,
                         boolean beard, double build) {}

    /**
     * The ranger at a post.
     *
     * <p>Derived rather than stored, from the same id the keeper is, so this
     * costs nothing to keep and cannot drift out of step with the building.
     */
    public static Ranger of(long postId) {
        long h = mix(postId);
        return new Ranger(
                NAMES[(int) Math.floorMod(h, NAMES.length)],
                trim((int) Math.floorMod(h >> 7, 6)),
                SKINS[(int) Math.floorMod(h >> 13, SKINS.length)],
                HAIRS[(int) Math.floorMod(h >> 19, HAIRS.length)],
                Math.floorMod(h >> 23, 3) == 0,
                0.94 + Math.floorMod(h >> 29, 13) * 0.011);
    }

    /** Whether the ranger is being drawn from an imported file. */
    public static boolean imported() { return model() != null; }

    private static SceneModel model() {
        return SceneModels.of(MODEL, ModelRig.Kind.HUMANOID, SceneModel.Normalise.HEIGHT);
    }

    /**
     * Draw a ranger standing in the clearing.
     *
     * @param z       the ground under their feet, in world metres
     * @param yaw     the way they face with nobody about
     * @param lookYaw the way to whoever they are watching, in world radians
     * @param clock   the world animation clock, in seconds
     * @param phase   an offset into the idle cycles, so two rangers in a day's
     *                walk are not breathing in step
     */
    public static void ranger(Mesh.Builder mesh, Ranger ranger, double x, double y,
                              double z, double yaw, double lookYaw, double clock,
                              double phase) {
        double t = clock + phase;
        double turn = clamp(wrap(lookYaw - yaw), -NECK_LIMIT, NECK_LIMIT);
        double height = HEIGHT * ranger.build();

        SceneModel imported = model();
        if (imported != null) {
            float[] uv = new float[4];
            WatchMaterials.uv(WatchMaterial.PELT, uv);
            // Standing, with the head following. A model that supplies no idle
            // clip is posed by ModelRig's humanoid table instead, which is what
            // makes a model with no animations at all still worth committing.
            imported.mesh(mesh, x, y, z, yaw, AnimState.IDLE,
                    t * AnimState.IDLE.cyclesPerSecond(), height, uv, turn);
            return;
        }

        float[] uv = new float[4];
        WatchMaterials.uv(WatchMaterial.PLANK, uv);

        double breath = Math.sin(t * Math.PI * 2 / BREATH_PERIOD);
        double shift = Math.sin(t * Math.PI * 2 / SHIFT_PERIOD);
        double scan = pulse(t, SCAN_PERIOD, SCAN_LENGTH);

        double cos = Math.cos(yaw), sin = Math.sin(yaw);
        double fx = sin, fy = -cos;
        double sx = cos, sy = sin;

        // The proportions below are the keeper's, and in those the hat's crown
        // stands at CROWN rather than at one. Dividing it out here is what makes
        // HEIGHT mean how tall this person is for the boxes and for an imported
        // model alike.
        double body = height / CROWN;
        double hipZ = body * 0.47;
        int trim = ranger.trimRgb();

        legs(mesh, x, y, z, fx, fy, sx, sy, yaw, body, hipZ, shift, uv);
        torso(mesh, x, y, z, fx, fy, sx, sy, yaw, body, hipZ, breath, shift, uv, trim);
        // The glass rides on the chest until it is raised, and the arms follow
        // it rather than the other way about — a ranger lifts the binoculars and
        // their hands go with them, which is why one number drives both.
        arms(mesh, x, y, z, fx, fy, sx, sy, yaw, body, scan, shift, uv, ranger);
        glass(mesh, x, y, z, fx, fy, sx, sy, yaw, body, scan, uv);
        head(mesh, x, y, z + body * 0.86 + breath * body * 0.003, fx, fy, yaw + turn,
                body * 0.135, ranger, uv, trim);
    }

    // --- the body --------------------------------------------------------------------

    /** Two legs in field trousers, and boots laced over them. */
    private static void legs(Mesh.Builder mesh, double x, double y, double z,
                             double fx, double fy, double sx, double sy, double yaw,
                             double height, double hipZ, double shift, float[] uv) {
        // <b>The leg is shorter than the keeper's, and that is the boot.</b>
        // A keeper's boot hangs about four centimetres below their ankle and
        // therefore four centimetres below the model's own base — invisible,
        // because a keeper stands on a plank deck and the deck hides it. A
        // ranger stands on the turf, where the same four centimetres is a
        // person buried to the laces. So the leg is cut by exactly the depth of
        // the boot beneath the ankle (0.045 of the height, below), which puts
        // the sole on the ground and leaves every other proportion alone.
        double legLength = height * 0.425;
        double thigh = legLength * 0.52, shin = legLength * 0.48;
        double bootHalf = height * 0.028, bootDrop = height * 0.017;
        for (int i = 0; i < 2; i++) {
            double side = i == 0 ? -1 : 1;
            double load = 0.5 + 0.5 * shift * side;
            double bend = 0.26 * (1 - load);
            double hx = x + sx * side * 0.105, hy = y + sy * side * 0.105;
            double kneeZ = z + hipZ - Math.cos(bend * 0.5) * thigh;
            double kx = hx + fx * Math.sin(bend * 0.5) * thigh;
            double ky = hy + fy * Math.sin(bend * 0.5) * thigh;
            double ankleZ = kneeZ - Math.cos(bend) * shin + 0.02 * (1 - load);
            double axp = kx - fx * Math.sin(bend) * shin * 0.4;
            double ayp = ky - fy * Math.sin(bend) * shin * 0.4;
            Shapes.strut(mesh, hx, hy, z + hipZ, kx, ky, kneeZ, 0.088, 0.088,
                    sx, sy, 0, uv, TROUSER);
            Shapes.strut(mesh, kx, ky, kneeZ, axp, ayp, ankleZ, 0.074, 0.074,
                    sx, sy, 0, uv, ShopModel.shade(TROUSER, 0.94));
            // A boot, and a tall cuff over the trouser — the detail that says
            // this person walks for a living. Both measured in heights rather
            // than metres, so a short ranger's soles are on the ground too.
            Shapes.box(mesh, axp + fx * 0.04, ayp + fy * 0.04, ankleZ - bootDrop,
                    0.09, 0.135, bootHalf, yaw, uv, LEATHER);
            Shapes.box(mesh, axp, ayp, ankleZ + height * 0.062, 0.088, 0.10,
                    height * 0.056, yaw, uv, ShopModel.shade(LEATHER, 1.14));
        }
    }

    /** Field coat, belt, a satchel on one hip, and a rolled bedroll across the back. */
    private static void torso(Mesh.Builder mesh, double x, double y, double z,
                              double fx, double fy, double sx, double sy, double yaw,
                              double height, double hipZ, double breath, double shift,
                              float[] uv, int trim) {
        double chestZ = height * 0.70;
        double lift = breath * 0.008;

        Shapes.box(mesh, x, y, z + chestZ + lift, 0.165, 0.145, height * 0.155, yaw, uv,
                COAT);
        // Patch pockets, both sides of the chest. Two boxes, and the coat stops
        // being a slab.
        for (int b = -1; b <= 1; b += 2) {
            Shapes.box(mesh, x + fx * 0.15 + sx * b * 0.075, y + fy * 0.15 + sy * b * 0.075,
                    z + chestZ + 0.03, 0.055, 0.018, 0.055, yaw, uv,
                    ShopModel.shade(COAT, 0.88));
        }
        // Shoulder yokes, a shade lighter, which is what reads as a uniform
        // rather than a coat at any distance you can still see a person at.
        for (int b = -1; b <= 1; b += 2) {
            Shapes.box(mesh, x + sx * b * 0.15, y + sy * b * 0.15, z + height * 0.795 + lift,
                    0.075, 0.13, 0.028, yaw, uv, ShopModel.shade(COAT, 1.16));
        }
        Shapes.box(mesh, x, y, z + height * 0.845, 0.125, 0.115, 0.032, yaw, uv,
                ShopModel.shade(COAT, 1.12));

        // The belt, a brass buckle on it, and the satchel that hangs off it.
        double beltZ = hipZ + 0.085;
        Shapes.box(mesh, x, y, z + beltZ, 0.17, 0.15, 0.038, yaw, uv, LEATHER);
        Shapes.box(mesh, x + fx * 0.15, y + fy * 0.15, z + beltZ, 0.035, 0.02, 0.038,
                yaw, uv, BRASS);
        Shapes.box(mesh, x + sx * 0.185 - fx * 0.02, y + sy * 0.185 - fy * 0.02,
                z + beltZ - 0.11, 0.075, 0.05, 0.10, yaw, uv,
                ShopModel.shade(LEATHER, 1.1));
        Shapes.box(mesh, x + sx * 0.185 - fx * 0.02, y + sy * 0.185 - fy * 0.02,
                z + beltZ - 0.04, 0.078, 0.052, 0.03, yaw, uv,
                ShopModel.shade(LEATHER, 0.82));
        // A canteen on the other hip.
        Shapes.blob(mesh, x - sx * 0.185, y - sy * 0.185, z + beltZ - 0.08,
                0.055, 0.032, 0.065, yaw, uv, ShopModel.shade(BRASS, 0.7));

        // The coat's skirt below the belt, wider than the body.
        Shapes.box(mesh, x, y, z + hipZ - 0.09, 0.178, 0.158, 0.17, yaw, uv,
                ShopModel.shade(COAT, 0.9));

        // A bedroll across the shoulders, and the neckerchief under the collar —
        // the two things a ranger has that a shopkeeper does not.
        Shapes.strut(mesh, x - fx * 0.11 - sx * 0.19, y - fy * 0.11 - sy * 0.19,
                z + height * 0.80, x - fx * 0.11 + sx * 0.19, y - fy * 0.11 + sy * 0.19,
                z + height * 0.755, 0.052, 0.052, sx, sy, 0, uv,
                ShopModel.shade(trim, 0.8));
        Shapes.box(mesh, x + fx * 0.03, y + fy * 0.03, z + height * 0.855,
                0.11, 0.10, 0.042, yaw, uv, trim);
        // One corner of it hanging down the front, swinging with the weight.
        Shapes.strut(mesh, x + fx * 0.10, y + fy * 0.10, z + height * 0.845,
                x + fx * 0.11 + sx * shift * 0.025, y + fy * 0.11 + sy * shift * 0.025,
                z + height * 0.74, 0.038, 0.016, sx, sy, 0, uv,
                ShopModel.shade(trim, 0.9));
    }

    /**
     * Two arms.
     *
     * <p>Hanging, thumbs in the belt, until the glass comes up — at which point
     * both elbows fold and both hands arrive at the face together, because that
     * is the one gesture where a person's arms do the same thing.
     */
    private static void arms(Mesh.Builder mesh, double x, double y, double z,
                             double fx, double fy, double sx, double sy, double yaw,
                             double height, double scan, double shift, float[] uv,
                             Ranger ranger) {
        double shoulderZ = z + height * 0.80;
        double armLength = height * 0.36;
        double upper = armLength * 0.52, fore = armLength * 0.48;

        for (int i = 0; i < 2; i++) {
            double side = i == 0 ? -1 : 1;
            double lean = 0.12 + 0.035 * shift * side + scan * 0.62;
            double bend = 0.30 + scan * 1.62;

            double ex = Math.sin(lean) * upper;
            double eu = -Math.cos(lean) * upper;
            double wx = ex + Math.sin(lean + bend) * fore;
            double wu = eu - Math.cos(lean + bend) * fore;

            double shX = x + sx * side * 0.205, shY = y + sy * side * 0.205;
            double drop = -0.012 * shift * side;
            Shapes.strut(mesh, shX, shY, shoulderZ + drop,
                    shX + fx * ex, shY + fy * ex, shoulderZ + drop + eu,
                    0.070, 0.070, sx, sy, 0, uv, COAT);
            Shapes.strut(mesh, shX + fx * ex, shY + fy * ex, shoulderZ + drop + eu,
                    shX + fx * wx, shY + fy * wx, shoulderZ + drop + wu,
                    0.058, 0.058, sx, sy, 0, uv, ShopModel.shade(COAT, 0.94));
            // A rolled cuff, then the hand.
            Shapes.box(mesh, shX + fx * wx * 0.92, shY + fy * wx * 0.92,
                    shoulderZ + drop + wu * 0.92, 0.064, 0.064, 0.038, yaw, uv,
                    ShopModel.shade(COAT, 1.2));
            Shapes.box(mesh, shX + fx * wx, shY + fy * wx, shoulderZ + drop + wu - 0.02,
                    0.054, 0.054, 0.058, yaw, uv, ranger.skinRgb());
        }
    }

    /**
     * The binoculars, and the strap they hang from.
     *
     * <p>They ride on the chest and rise to the eyes, which is the whole gesture
     * — and they are drawn here rather than in {@link #arms} so that the strap
     * can slacken as they come up instead of stretching.
     */
    private static void glass(Mesh.Builder mesh, double x, double y, double z,
                              double fx, double fy, double sx, double sy, double yaw,
                              double height, double scan, float[] uv) {
        double restZ = z + height * 0.68;
        double eyeZ = z + height * 0.885;
        double gz = restZ + (eyeZ - restZ) * scan;
        double out = 0.16 + scan * 0.055;

        for (int b = -1; b <= 1; b += 2) {
            Shapes.box(mesh, x + fx * out + sx * b * 0.036, y + fy * out + sy * b * 0.036,
                    gz, 0.030, 0.058, 0.030, yaw, uv, ShopModel.shade(LEATHER, 0.7));
            // The objective lens on the front of each barrel.
            Shapes.box(mesh, x + fx * (out + 0.055) + sx * b * 0.036,
                    y + fy * (out + 0.055) + sy * b * 0.036, gz,
                    0.012, 0.026, 0.026, yaw, uv, GLASS);
        }
        // The bridge between the barrels.
        Shapes.box(mesh, x + fx * out, y + fy * out, gz, 0.024, 0.020, 0.020, yaw, uv,
                ShopModel.shade(BRASS, 0.8));
        // The strap, from each shoulder to the barrels, slackening as they rise.
        for (int b = -1; b <= 1; b += 2) {
            Shapes.strut(mesh, x + sx * b * 0.155, y + sy * b * 0.155, z + height * 0.815,
                    x + fx * out + sx * b * 0.036, y + fy * out + sy * b * 0.036,
                    gz + 0.02, 0.014, 0.008, sx, sy, 0, uv,
                    ShopModel.shade(LEATHER, 0.86));
        }
    }

    /**
     * The face, and the campaign hat on it.
     *
     * <p>The hat is the ranger's silhouette. A keeper's broad brim is wider and
     * ends there; this one is a little narrower and carries a <b>peaked crown</b>
     * on top, which is a shape nothing else in this world has. That is what lets
     * you tell a ranger from a keeper at two hundred metres without seeing
     * either face — and it is why the brim is not simply the keeper's, which
     * would have made two hats that read the same from everywhere but close up.
     */
    private static void head(Mesh.Builder mesh, double x, double y, double z,
                             double bodyFx, double bodyFy, double yaw, double size,
                             Ranger ranger, float[] uv, int trim) {
        double cos = Math.cos(yaw), sin = Math.sin(yaw);
        double fx = sin, fy = -cos;
        double sx = cos, sy = sin;
        int skin = ranger.skinRgb();
        int hair = ranger.hairRgb();

        // <b>Not lifted by the gesture.</b> An earlier version raised the head
        // while the glass came up, which read nicely and quietly made the model
        // taller than HEIGHT — and HEIGHT is what an imported ranger is scaled
        // to, so the two would have been different sizes at exactly the moment
        // you were looking at them. The gesture is carried by the arms and the
        // binoculars instead, which is where it reads from anyway.
        double z0 = z + size * 0.9;
        double face = size * 0.78;

        Shapes.box(mesh, x, y, z0, face, face * 0.92, size * 0.82, yaw, uv, skin);
        Shapes.box(mesh, x + fx * 0.012, y + fy * 0.012, z0 - size * 0.66,
                face * 0.82, face * 0.80, size * 0.24, yaw, uv,
                ShopModel.shade(skin, 0.96));
        for (int b = -1; b <= 1; b += 2) {
            Shapes.box(mesh, x + sx * b * face * 0.95, y + sy * b * face * 0.95, z0,
                    0.018, 0.032, 0.045, yaw, uv, ShopModel.shade(skin, 0.94));
        }
        Shapes.box(mesh, x + fx * face, y + fy * face, z0 - size * 0.10,
                0.03, 0.035, 0.045, yaw, uv, ShopModel.shade(skin, 1.04));
        Shapes.box(mesh, x + fx * face * 0.92, y + fy * face * 0.92, z0 + size * 0.28,
                face * 0.72, 0.02, 0.022, yaw, uv, ShopModel.shade(hair, 0.9));
        for (int b = -1; b <= 1; b += 2) {
            Shapes.box(mesh, x + fx * face * 0.95 + sx * b * 0.05,
                    y + fy * face * 0.95 + sy * b * 0.05, z0 + size * 0.12,
                    0.022, 0.014, 0.022, yaw, uv, 0x241C18);
        }
        if (ranger.beard()) {
            Shapes.box(mesh, x + fx * face * 0.62, y + fy * face * 0.62, z0 - size * 0.62,
                    face * 0.78, face * 0.5, size * 0.34, yaw, uv, hair);
        }
        Shapes.box(mesh, x - fx * 0.02, y - fy * 0.02, z0 + size * 0.42,
                face * 1.04, face * 0.98, size * 0.30, yaw, uv, hair);

        // The hat: a flat brim, a hatband in the ranger's own colour, and a
        // four-sided peaked crown made of a box under a squat cone.
        double brimZ = z0 + size * 0.72;
        Shapes.box(mesh, x, y, brimZ, size * 1.6, size * 1.5, 0.016, yaw, uv,
                ShopModel.shade(COAT, 0.7));
        Shapes.box(mesh, x, y, brimZ + size * 0.30, size * 0.84, size * 0.82,
                size * 0.30, yaw, uv, ShopModel.shade(COAT, 0.8));
        Shapes.box(mesh, x, y, brimZ + size * 0.11, size * 0.88, size * 0.86,
                size * 0.085, yaw, uv, trim);
        Shapes.cone(mesh, x, y, brimZ + size * 0.58, brimZ + size * 0.94,
                size * 0.78, 4, yaw, uv, ShopModel.shade(COAT, 0.86));
        // A badge on the front of the crown. Small, brass, and the one thing on
        // the model that says whose ranger this is.
        Shapes.box(mesh, x + fx * size * 0.80, y + fy * size * 0.80,
                brimZ + size * 0.34, 0.016, 0.030, 0.030, yaw, uv, BRASS);
    }

    // --- plumbing --------------------------------------------------------------------

    /**
     * A smooth pulse, up for {@code length} out of every {@code period} seconds.
     *
     * <p>Continuous in value and slope at both joins, for {@code KeeperModel}'s
     * reason: the eye finds a discontinuity in an idle animation instantly.
     */
    private static double pulse(double t, double period, double length) {
        double at = t - Math.floor(t / period) * period;
        if (at > length) return 0;
        return 0.5 - 0.5 * Math.cos(at / length * Math.PI * 2);
    }

    /** The neckerchief colours, which are the only thing a ranger picks. */
    private static int trim(int which) {
        return switch (which) {
            case 0 -> 0xA8442E;
            case 1 -> 0xC08A3A;
            case 2 -> 0x4E6E86;
            case 3 -> 0x8A5A7A;
            case 4 -> 0xD8CBA6;
            default -> 0x6E8A4A;
        };
    }

    /** A cheap avalanche, so neighbouring post ids do not make similar people. */
    private static long mix(long id) {
        long h = id * 0x9E3779B97F4A7C15L;
        h ^= h >>> 31;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 29;
        return h & Long.MAX_VALUE;
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
