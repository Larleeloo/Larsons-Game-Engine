package com.larsons.engine.watch.life;

import com.larsons.engine.watch.render.Mesh;
import com.larsons.engine.watch.render.Shapes;
import com.larsons.engine.watch.world.WatchMaterial;
import com.larsons.engine.watch.world.WatchMaterials;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * A boxy, Minecraft-style animal — <b>the placeholder, and a real one.</b>
 *
 * <p>The brief asks for placeholders "until the full Blockbench animations can
 * be implemented", and for those placeholders to be full 3D models in a
 * Minecraft style, complete with textures. That is what these are: a pile of
 * rectangular boxes, each one hung off a named {@link Joint}, each one taking
 * its colour from a region of the species' own skin sheet
 * ({@link AnimalSkins}), and every one of them posed by the same nine
 * {@link AnimState}s a Blockbench model will be. A species with an imported
 * model is drawn by exactly the same code as one without — see
 * {@link Blockbench} — so the placeholders are not scaffolding to be torn out,
 * they are the fallback that keeps every one of the 1 300-odd species drawable
 * while the art arrives one file at a time.
 *
 * <h2>Five plans, twenty-one builds</h2>
 *
 * <p>Hand-placing boxes for twenty-one body plans is twenty-one chances to get
 * a leg in the wrong place. Instead there are five <em>generic</em> plans —
 * bird, quadruped, lizard, fish, insect — each parameterised by the
 * proportions that actually differ: how long the legs are, how much neck there
 * is, how far the wings reach, whether it has horns, ears, a hooked bill.
 * A heron and a sparrow are the same eleven boxes with different numbers, and
 * that is also true of a heron and a sparrow.
 *
 * <h2>Model space</h2>
 *
 * <p>{@code +x} is forward (the way it is facing), {@code +y} is its right,
 * {@code +z} is up, and the origin is on the ground under it. Everything is in
 * <b>fractions of body length</b>, so one plan serves a 7 cm hummingbird and a
 * 1.7 m bear, and {@link #mesh} scales at the end.
 */
public final class AnimalModel {

    /** The named hinges a pose can move. */
    public enum Joint {
        /** The trunk; the whole animal moves with it. */
        BODY,
        /** Head and anything on it. */
        HEAD,
        TAIL,
        WING_L, WING_R,
        /** Front legs — the forelimbs of a quadruped, a bird's single pair. */
        LEG_FL, LEG_FR,
        LEG_BL, LEG_BR,
        /** Ears, which flick; horns and antlers, which do not. */
        EAR,
        HORN,
        /** Anything a plan needs that is not one of the above. */
        EXTRA
    }

    /**
     * One box.
     *
     * @param joint  which hinge moves it
     * @param pivotX where that hinge is, in body lengths from the origin
     * @param cx     the box's centre, relative to the pivot
     * @param hx     the box's half-extents
     * @param region which part of the skin it is painted from
     */
    public record Part(Joint joint, double pivotX, double pivotY, double pivotZ,
                       double cx, double cy, double cz,
                       double hx, double hy, double hz,
                       AnimalSkins.Region region) {}

    private static final Map<AnimalFamily.Build, AnimalModel> PLANS =
            new EnumMap<>(AnimalFamily.Build.class);

    private final List<Part> parts;
    private final double standHeight;

    private AnimalModel(List<Part> parts, double standHeight) {
        this.parts = List.copyOf(parts);
        this.standHeight = standHeight;
    }

    /** The plan for a species — its family's build, cached. */
    public static synchronized AnimalModel of(AnimalDef def) {
        return of(def.family().build());
    }

    /** The plan for a build, cached. */
    public static synchronized AnimalModel of(AnimalFamily.Build build) {
        return PLANS.computeIfAbsent(build, AnimalModel::plan);
    }

    /**
     * A model assembled from somewhere else — what {@link Blockbench} produces.
     *
     * <p>Deliberately the same type as a placeholder rather than a sibling of
     * it: the renderer, the field guide, the picking ray and the tests all take
     * an {@code AnimalModel}, and none of them should have to ask where it came
     * from.
     */
    public static AnimalModel imported(List<Part> parts, double standHeight) {
        return new AnimalModel(parts, standHeight);
    }

    /** The boxes, in the order they are drawn. */
    public List<Part> parts() { return parts; }

    /** How far the animal's back is off the ground, in body lengths. */
    public double standHeight() { return standHeight; }

    /** How many boxes this plan has — what a test counts. */
    public int boxCount() { return parts.size(); }

    // --- posing ---------------------------------------------------------------------

    /**
     * How far each joint is turned in a state, at a phase.
     *
     * <p>{@code pitch} swings a part about the animal's own left-right axis —
     * a leg forward, a head down — and {@code roll} about its forward axis,
     * which is what a wing does. {@code lift} raises the whole part. Three
     * numbers per joint is not much, and it is enough: what reads as "walking"
     * at ten metres is opposite legs swinging out of phase, and what reads as
     * "flying" is wings going up and down.
     */
    public record Pose(double pitch, double turn, double roll,
                       double dx, double dy, double dz, double spread) {

        /**
         * The shorthand the procedural poses are written in: a pitch, a roll,
         * and a lift straight up.
         */
        public Pose(double pitch, double roll, double lift) {
            this(pitch, 0, roll, 0, 0, lift, 1);
        }

        /** …and the same with a fold, for wings. */
        public Pose(double pitch, double roll, double lift, double spread) {
            this(pitch, 0, roll, 0, 0, lift, spread);
        }

        /**
         * The full form, which is what an imported Blockbench clip produces:
         * three rotations and a translation, per bone, per frame.
         */
        public static Pose full(double pitch, double turn, double roll,
                                double dx, double dy, double dz) {
            return new Pose(pitch, turn, roll, dx, dy, dz, 1);
        }

        static final Pose REST = new Pose(0, 0, 0);

        /**
         * A folded wing.
         *
         * <p><b>Why a fourth number.</b> A wing is a wide, thin plate, and a
         * bird standing on the ground has it folded against its flank — but no
         * rotation folds a plate: turned about the body's axis it becomes a
         * plate hanging <em>down</em>, which is a bird with two paddles stuck
         * to it. The first version of this had exactly that, and every animal
         * in the game stood there with its wings out like a weathervane. So
         * folding scales the wing's reach instead, which is what folding is.
         */
        static Pose folded(double side) {
            return new Pose(0.10, side * 0.22, 0, 0.24);
        }

        /** The lift, kept under its old name for the procedural poses. */
        public double lift() { return dz; }
    }

    /**
     * Where a pose comes from.
     *
     * <p>The procedural placeholders answer with {@link #pose}; an imported
     * Blockbench model answers by sampling its own clip. Because they answer
     * the same question with the same type, {@link #mesh} does not know or care
     * which it is holding — which is what makes "the art arrived for this one
     * species" a data change rather than a code change.
     */
    public interface PoseSource {
        Pose poseOf(AnimState state, Joint joint, double phase);
    }

    /** The built-in procedural poses, as a {@link PoseSource}. */
    public static PoseSource procedural() { return AnimalModel::pose; }

    /**
     * The pose of one joint in one state at one phase.
     *
     * @param phase how far through the state's cycle, in turns
     */
    public static Pose pose(AnimState state, Joint joint, double phase) {
        double wave = Math.sin(phase * Math.PI * 2);
        double counter = Math.sin(phase * Math.PI * 2 + Math.PI);
        return switch (state) {
            case IDLE, TAME -> switch (joint) {
                case HEAD -> new Pose(wave * 0.06, 0, 0);
                case TAIL -> new Pose(counter * 0.08, 0, 0);
                case EAR -> new Pose(wave * 0.20, 0, 0);
                case WING_L -> Pose.folded(-1);
                case WING_R -> Pose.folded(1);
                default -> Pose.REST;
            };
            case WALK -> switch (joint) {
                case LEG_FL, LEG_BR -> new Pose(wave * 0.55, 0, 0);
                case LEG_FR, LEG_BL -> new Pose(counter * 0.55, 0, 0);
                case TAIL -> new Pose(wave * 0.14, 0, 0);
                case HEAD -> new Pose(wave * 0.05, 0, 0);
                case BODY -> new Pose(0, wave * 0.04, Math.abs(wave) * 0.012);
                case WING_L -> Pose.folded(-1);
                case WING_R -> Pose.folded(1);
                default -> Pose.REST;
            };
            case RUN -> switch (joint) {
                case LEG_FL, LEG_FR -> new Pose(wave * 0.95, 0, 0);
                case LEG_BL, LEG_BR -> new Pose(counter * 0.95, 0, 0);
                case TAIL -> new Pose(-0.25 + wave * 0.18, 0, 0);
                case BODY -> new Pose(wave * 0.10, 0, Math.max(0, wave) * 0.05);
                case WING_L -> new Pose(0.1, -0.3, 0, 0.45);
                case WING_R -> new Pose(0.1, 0.3, 0, 0.45);
                default -> Pose.REST;
            };
            case FLY -> switch (joint) {
                // The two wings mirror, which is why one takes the negative:
                // rolled the same way they would both point at the ground.
                case WING_L -> new Pose(0, wave * 1.05, 0);
                case WING_R -> new Pose(0, -wave * 1.05, 0);
                case LEG_FL, LEG_FR, LEG_BL, LEG_BR -> new Pose(-0.7, 0, 0);
                case TAIL -> new Pose(0.12, 0, 0);
                case BODY -> new Pose(-0.10, 0, wave * 0.03);
                default -> Pose.REST;
            };
            case FORAGE -> switch (joint) {
                case HEAD -> new Pose(0.85 + wave * 0.22, 0, -0.02);
                case TAIL -> new Pose(-0.2, 0, 0);
                case BODY -> new Pose(0.14, 0, -0.01);
                case WING_L -> Pose.folded(-1);
                case WING_R -> Pose.folded(1);
                default -> Pose.REST;
            };
            case ALERT -> switch (joint) {
                case HEAD -> new Pose(-0.34, wave * 0.10, 0.02);
                case EAR -> new Pose(-0.4, 0, 0);
                case TAIL -> new Pose(-0.3, 0, 0);
                case WING_L -> Pose.folded(-1);
                case WING_R -> Pose.folded(1);
                default -> Pose.REST;
            };
            case SLEEP -> switch (joint) {
                case HEAD -> new Pose(0.55, 0.9, -0.03);
                case LEG_FL, LEG_FR, LEG_BL, LEG_BR -> new Pose(1.35, 0, -0.02);
                case TAIL -> new Pose(0.3, 0, 0);
                case BODY -> new Pose(0, 0, -0.06 + wave * 0.004);
                case EAR -> new Pose(0.5, 0, 0);
                case WING_L -> new Pose(0.2, -0.2, 0, 0.20);
                case WING_R -> new Pose(0.2, 0.2, 0, 0.20);
                default -> Pose.REST;
            };
            case CALL -> switch (joint) {
                case HEAD -> new Pose(-0.45 - Math.abs(wave) * 0.18, 0, 0.02);
                case TAIL -> new Pose(-0.35, 0, 0);
                case WING_L -> new Pose(0, -Math.abs(wave) * 0.5, 0, 0.42);
                case WING_R -> new Pose(0, Math.abs(wave) * 0.5, 0, 0.42);
                default -> Pose.REST;
            };
        };
    }

    // --- meshing --------------------------------------------------------------------

    /**
     * Write this animal into a mesh.
     *
     * @param def     the species, for its colours
     * @param x       world position; {@code z} is the ground it stands on
     * @param yaw     which way it faces, radians
     * @param state   what it is doing
     * @param phase   how far through that state's cycle, in turns
     * @param scale   extra scale on top of the species' body length
     */
    public void mesh(Mesh.Builder mesh, AnimalDef def, double x, double y, double z,
                     double yaw, AnimState state, double phase, double scale) {
        mesh(mesh, def, x, y, z, yaw, state, phase, scale, procedural());
    }

    /** {@link #mesh} with the poses taken from somewhere else — an import. */
    public void mesh(Mesh.Builder mesh, AnimalDef def, double x, double y, double z,
                     double yaw, AnimState state, double phase, double scale,
                     PoseSource poses) {
        double length = def.bodyLength() * scale;
        double cos = Math.cos(yaw), sin = Math.sin(yaw);
        float[] uv = new float[4];
        // Every animal shares one tile of the world atlas; the colour, which is
        // what the flat-shaded look actually reads, comes from the skin.
        WatchMaterials.uv(WatchMaterial.BARK, uv);

        for (Part part : parts) {
            Pose pose = poses.poseOf(state, part.joint(), phase);
            int argb = AnimalSkins.regionColour(def, part.region());
            emitBox(mesh, part, pose, argb, uv, length, x, y, z, cos, sin);
        }
    }

    /**
     * One box, rotated about its joint's pivot and then about the animal's own
     * yaw, into world space.
     *
     * <p>Written out rather than composed from matrices because it is eight
     * corners and three sines, it runs once per box per animal per frame, and a
     * 4×4 pipeline here would be more code and more allocation for the same
     * eight points.
     */
    private void emitBox(Mesh.Builder mesh, Part part, Pose pose, int argb, float[] uv,
                         double length, double wx, double wy, double wz,
                         double cos, double sin) {
        double cp = Math.cos(pose.pitch()), sp = Math.sin(pose.pitch());
        double cr = Math.cos(pose.roll()), sr = Math.sin(pose.roll());
        double ct = Math.cos(pose.turn()), st = Math.sin(pose.turn());
        double spread = pose.spread();

        double[][] world = new double[8][3];
        int at = 0;
        for (int sz = -1; sz <= 1; sz += 2) {
            for (int sy = -1; sy <= 1; sy += 2) {
                for (int sx = -1; sx <= 1; sx += 2) {
                    // Corner in the part's own frame, relative to its pivot.
                    double px = part.cx() + sx * part.hx();
                    double py = (part.cy() + sy * part.hy()) * spread;
                    double pz = part.cz() + sz * part.hz();
                    // Roll about the forward axis, pitch about the right axis,
                    // then turn about the vertical — the order a limb hinges
                    // in, and the order Blockbench writes its rotations in.
                    double ry = py * cr - pz * sr;
                    double rz = py * sr + pz * cr;
                    double fx = px * cp + rz * sp;
                    double fz = -px * sp + rz * cp;
                    double tx = fx * ct - ry * st;
                    double ty = fx * st + ry * ct;
                    // Back into model space, then out to the world.
                    double mx = part.pivotX() + tx + pose.dx();
                    double my = part.pivotY() + ty + pose.dy();
                    double mz = part.pivotZ() + fz + pose.dz();
                    world[at][0] = wx + (mx * cos - my * sin) * length;
                    world[at][1] = wy + (mx * sin + my * cos) * length;
                    world[at][2] = wz + mz * length;
                    at++;
                }
            }
        }
        face(mesh, world, 4, 5, 7, 6, uv, argb); // top
        face(mesh, world, 0, 2, 3, 1, uv, argb); // bottom
        face(mesh, world, 0, 1, 5, 4, uv, argb); // left  (−y)
        face(mesh, world, 3, 2, 6, 7, uv, argb); // right (+y)
        face(mesh, world, 1, 3, 7, 5, uv, argb); // front (+x)
        face(mesh, world, 2, 0, 4, 6, uv, argb); // back  (−x)
    }

    private static void face(Mesh.Builder mesh, double[][] c, int a, int b, int d, int e,
                             float[] uv, int argb) {
        Shapes.quad(mesh, c[a][0], c[a][1], c[a][2], c[b][0], c[b][1], c[b][2],
                c[d][0], c[d][1], c[d][2], c[e][0], c[e][1], c[e][2], uv, argb);
    }

    // --- the five plans -------------------------------------------------------------

    private static AnimalModel plan(AnimalFamily.Build build) {
        return switch (build) {
            case SMALL_BIRD -> bird(0.22, 0.05, 0.50, 0.30, Bill.SHORT, false);
            case LARGE_BIRD -> bird(0.30, 0.16, 0.62, 0.32, Bill.SHORT, false);
            case WADING_BIRD -> bird(0.78, 0.44, 0.56, 0.18, Bill.LONG, false);
            case RAPTOR_BIRD -> bird(0.28, 0.10, 0.76, 0.34, Bill.HOOKED, false);
            case HOOKED_BILL -> bird(0.20, 0.08, 0.46, 0.58, Bill.HOOKED, false);
            case HOVERER -> bird(0.09, 0.04, 0.44, 0.22, Bill.NEEDLE, false);
            case BAT_LIKE -> bird(0.10, 0.04, 0.98, 0.12, Bill.SHORT, true);
            case QUADRUPED -> quadruped(0.42, 0.24, 0.44, 0.16, Head.PLAIN, true);
            case DEER_LIKE -> quadruped(0.58, 0.38, 0.12, 0.18, Head.ANTLERED, true);
            case CAT_LIKE -> quadruped(0.36, 0.16, 0.58, 0.10, Head.PLAIN, true);
            case SMALL_MAMMAL -> quadruped(0.20, 0.10, 0.62, 0.10, Head.PLAIN, true);
            case LONG_MAMMAL -> quadruped(0.18, 0.16, 0.48, 0.14, Head.PLAIN, true);
            case PRIMATE_LIKE -> quadruped(0.36, 0.16, 0.72, 0.08, Head.PLAIN, true);
            case BULKY -> quadruped(0.44, 0.18, 0.10, 0.16, Head.PLAIN, true);
            case HORNED -> quadruped(0.52, 0.30, 0.16, 0.16, Head.HORNED, true);
            case LAGOMORPH -> quadruped(0.26, 0.08, 0.10, 0.08, Head.LONG_EARED, true);
            case LIZARD -> lizard(0.12, 0.92, false);
            case AMPHIB -> lizard(0.17, 0.16, true);
            case FISH_LIKE -> fish();
            case WINGED_INSECT -> insect();
            case ETHEREAL -> sprite();
        };
    }

    /** What a bird has on the front of its face. */
    private enum Bill { SHORT, LONG, HOOKED, NEEDLE }

    /** What a quadruped has on top of its head. */
    private enum Head { PLAIN, HORNED, ANTLERED, LONG_EARED }

    /**
     * A bird: two legs, a body, a neck-and-head, a tail, and a pair of wings.
     *
     * @param legLen   leg length, in body lengths
     * @param neckLen  how far the head sits ahead of and above the body
     * @param wingSpan how far one wing reaches out from the flank
     * @param tailLen  tail length
     * @param bat      leathery wings, big ears and no tail to speak of
     */
    private static AnimalModel bird(double legLen, double neckLen, double wingSpan,
                                    double tailLen, Bill bill, boolean bat) {
        List<Part> parts = new ArrayList<>();
        double bodyZ = legLen + 0.16;
        double bodyHalfX = 0.28, bodyHalfY = 0.16, bodyHalfZ = 0.15;

        // Legs, hinged at the body.
        for (int side = -1; side <= 1; side += 2) {
            Joint joint = side < 0 ? Joint.LEG_FL : Joint.LEG_FR;
            parts.add(new Part(joint, 0.02, side * 0.08, bodyZ - bodyHalfZ,
                    0, 0, -legLen / 2, 0.028, 0.028, legLen / 2,
                    AnimalSkins.Region.LIMB));
            // A foot, so it does not end in a point.
            parts.add(new Part(joint, 0.02, side * 0.08, bodyZ - bodyHalfZ,
                    0.03, 0, -legLen, 0.055, 0.03, 0.018,
                    AnimalSkins.Region.LIMB));
        }

        parts.add(new Part(Joint.BODY, 0, 0, bodyZ, 0, 0, 0,
                bodyHalfX, bodyHalfY, bodyHalfZ, AnimalSkins.Region.BODY));
        parts.add(new Part(Joint.BODY, 0, 0, bodyZ, -0.02, 0, -bodyHalfZ * 0.7,
                bodyHalfX * 0.8, bodyHalfY * 0.85, bodyHalfZ * 0.4,
                AnimalSkins.Region.BELLY));

        double headX = bodyHalfX + neckLen * 0.6;
        double headZ = bodyZ + 0.10 + neckLen;
        if (neckLen > 0.12) {
            parts.add(new Part(Joint.HEAD, bodyHalfX * 0.6, 0, bodyZ + 0.06,
                    neckLen * 0.3, 0, neckLen * 0.5, 0.05, 0.05, neckLen * 0.55,
                    AnimalSkins.Region.BODY));
        }
        parts.add(new Part(Joint.HEAD, headX - 0.10, 0, headZ - 0.06,
                0.10, 0, 0.06, 0.11, 0.10, 0.10, AnimalSkins.Region.HEAD));
        parts.add(new Part(Joint.HEAD, headX - 0.10, 0, headZ - 0.06,
                0.19, 0, 0.09, 0.04, 0.055, 0.035, AnimalSkins.Region.EYE));

        double billLen = switch (bill) {
            case SHORT -> 0.09;
            case LONG -> 0.34;
            case HOOKED -> 0.11;
            case NEEDLE -> 0.42;
        };
        double billThick = bill == Bill.NEEDLE ? 0.012 : (bill == Bill.HOOKED ? 0.05 : 0.032);
        parts.add(new Part(Joint.HEAD, headX - 0.10, 0, headZ - 0.06,
                0.19 + billLen / 2, 0, 0.05, billLen / 2, billThick, billThick,
                AnimalSkins.Region.HARD));
        if (bill == Bill.HOOKED) {
            parts.add(new Part(Joint.HEAD, headX - 0.10, 0, headZ - 0.06,
                    0.19 + billLen, 0, 0.02, 0.03, 0.03, 0.04,
                    AnimalSkins.Region.HARD));
        }

        if (bat) {
            for (int side = -1; side <= 1; side += 2) {
                parts.add(new Part(Joint.EAR, headX - 0.10, side * 0.06, headZ + 0.02,
                        0, side * 0.03, 0.10, 0.03, 0.02, 0.10,
                        AnimalSkins.Region.HEAD));
            }
        }

        if (tailLen > 0.02) {
            parts.add(new Part(Joint.TAIL, -bodyHalfX, 0, bodyZ,
                    -tailLen / 2, 0, 0.02, tailLen / 2, 0.10, 0.022,
                    AnimalSkins.Region.TAIL));
        }

        for (int side = -1; side <= 1; side += 2) {
            Joint joint = side < 0 ? Joint.WING_L : Joint.WING_R;
            parts.add(new Part(joint, -0.02, side * bodyHalfY, bodyZ + 0.06,
                    -0.02, side * wingSpan / 2, 0,
                    bodyHalfX * 0.85, wingSpan / 2, bat ? 0.012 : 0.026,
                    AnimalSkins.Region.WING));
        }
        return new AnimalModel(parts, bodyZ);
    }

    /**
     * A quadruped: four legs, a body, a neck and head, a tail, and whatever the
     * head carries.
     */
    private static AnimalModel quadruped(double legLen, double neckLen, double tailLen,
                                         double snout, Head head, boolean tailUp) {
        List<Part> parts = new ArrayList<>();
        double bodyZ = legLen + 0.13;
        double bodyHalfX = 0.32, bodyHalfY = 0.15, bodyHalfZ = 0.13;

        Joint[] legs = {Joint.LEG_FL, Joint.LEG_FR, Joint.LEG_BL, Joint.LEG_BR};
        double[] legX = {bodyHalfX * 0.66, bodyHalfX * 0.66, -bodyHalfX * 0.66, -bodyHalfX * 0.66};
        double[] legY = {-bodyHalfY * 0.72, bodyHalfY * 0.72, -bodyHalfY * 0.72, bodyHalfY * 0.72};
        for (int i = 0; i < 4; i++) {
            parts.add(new Part(legs[i], legX[i], legY[i], bodyZ - bodyHalfZ,
                    0, 0, -legLen / 2, 0.042, 0.042, legLen / 2,
                    AnimalSkins.Region.LIMB));
            parts.add(new Part(legs[i], legX[i], legY[i], bodyZ - bodyHalfZ,
                    0.02, 0, -legLen, 0.055, 0.045, 0.022,
                    AnimalSkins.Region.LIMB));
        }

        parts.add(new Part(Joint.BODY, 0, 0, bodyZ, 0, 0, 0,
                bodyHalfX, bodyHalfY, bodyHalfZ, AnimalSkins.Region.BODY));
        parts.add(new Part(Joint.BODY, 0, 0, bodyZ, 0, 0, -bodyHalfZ * 0.75,
                bodyHalfX * 0.85, bodyHalfY * 0.85, bodyHalfZ * 0.35,
                AnimalSkins.Region.BELLY));

        double headX = bodyHalfX + neckLen * 0.7;
        double headZ = bodyZ + neckLen * 0.75;
        if (neckLen > 0.10) {
            parts.add(new Part(Joint.HEAD, bodyHalfX * 0.8, 0, bodyZ + bodyHalfZ * 0.4,
                    neckLen * 0.35, 0, neckLen * 0.38, 0.06, 0.06, neckLen * 0.5,
                    AnimalSkins.Region.BODY));
        }
        parts.add(new Part(Joint.HEAD, headX - 0.10, 0, headZ,
                0.10, 0, 0.02, 0.11, 0.09, 0.09, AnimalSkins.Region.HEAD));
        parts.add(new Part(Joint.HEAD, headX - 0.10, 0, headZ,
                0.20 + snout / 2, 0, -0.01, snout / 2 + 0.03, 0.055, 0.05,
                AnimalSkins.Region.HEAD));
        parts.add(new Part(Joint.HEAD, headX - 0.10, 0, headZ,
                0.16, 0, 0.07, 0.035, 0.075, 0.03, AnimalSkins.Region.EYE));

        switch (head) {
            case HORNED -> {
                for (int side = -1; side <= 1; side += 2) {
                    parts.add(new Part(Joint.HORN, headX - 0.10, side * 0.05, headZ + 0.08,
                            0.02, side * 0.03, 0.10, 0.028, 0.028, 0.12,
                            AnimalSkins.Region.HARD));
                    parts.add(new Part(Joint.HORN, headX - 0.10, side * 0.05, headZ + 0.08,
                            -0.06, side * 0.05, 0.20, 0.028, 0.028, 0.06,
                            AnimalSkins.Region.HARD));
                }
            }
            case ANTLERED -> {
                for (int side = -1; side <= 1; side += 2) {
                    parts.add(new Part(Joint.HORN, headX - 0.10, side * 0.05, headZ + 0.08,
                            0, side * 0.05, 0.13, 0.022, 0.022, 0.15,
                            AnimalSkins.Region.HARD));
                    parts.add(new Part(Joint.HORN, headX - 0.10, side * 0.05, headZ + 0.08,
                            0.07, side * 0.10, 0.22, 0.07, 0.018, 0.018,
                            AnimalSkins.Region.HARD));
                    parts.add(new Part(Joint.HORN, headX - 0.10, side * 0.05, headZ + 0.08,
                            -0.04, side * 0.11, 0.26, 0.05, 0.018, 0.018,
                            AnimalSkins.Region.HARD));
                }
            }
            case LONG_EARED -> {
                for (int side = -1; side <= 1; side += 2) {
                    parts.add(new Part(Joint.EAR, headX - 0.10, side * 0.05, headZ + 0.07,
                            -0.01, side * 0.02, 0.14, 0.026, 0.02, 0.15,
                            AnimalSkins.Region.HEAD));
                }
            }
            case PLAIN -> {
                for (int side = -1; side <= 1; side += 2) {
                    parts.add(new Part(Joint.EAR, headX - 0.10, side * 0.06, headZ + 0.07,
                            -0.01, side * 0.02, 0.05, 0.022, 0.018, 0.05,
                            AnimalSkins.Region.HEAD));
                }
            }
        }

        if (tailLen > 0.02) {
            double lift = tailUp ? 0.05 : 0;
            parts.add(new Part(Joint.TAIL, -bodyHalfX, 0, bodyZ + 0.04,
                    -tailLen / 2, 0, lift, tailLen / 2, 0.045, 0.045,
                    AnimalSkins.Region.TAIL));
        }
        return new AnimalModel(parts, bodyZ);
    }

    /** A lizard or a frog: low body, splayed legs, and a tail or not. */
    private static AnimalModel lizard(double legLen, double tailLen, boolean squat) {
        List<Part> parts = new ArrayList<>();
        double bodyZ = legLen + 0.06;
        double bodyHalfX = squat ? 0.26 : 0.30;
        double bodyHalfY = squat ? 0.19 : 0.13;
        double bodyHalfZ = squat ? 0.12 : 0.08;

        Joint[] legs = {Joint.LEG_FL, Joint.LEG_FR, Joint.LEG_BL, Joint.LEG_BR};
        double[] legX = {bodyHalfX * 0.6, bodyHalfX * 0.6, -bodyHalfX * 0.6, -bodyHalfX * 0.6};
        for (int i = 0; i < 4; i++) {
            double side = (i % 2 == 0) ? -1 : 1;
            // Splayed: the foot is outboard of the hip, which is the whole
            // difference between a lizard's stance and a dog's.
            parts.add(new Part(legs[i], legX[i], side * bodyHalfY * 0.8, bodyZ,
                    0, side * legLen * 0.5, -legLen * 0.5,
                    0.035, legLen * 0.55, 0.03, AnimalSkins.Region.LIMB));
        }

        parts.add(new Part(Joint.BODY, 0, 0, bodyZ, 0, 0, 0,
                bodyHalfX, bodyHalfY, bodyHalfZ, AnimalSkins.Region.BODY));
        parts.add(new Part(Joint.BODY, 0, 0, bodyZ, 0, 0, -bodyHalfZ * 0.7,
                bodyHalfX * 0.8, bodyHalfY * 0.8, bodyHalfZ * 0.3,
                AnimalSkins.Region.BELLY));
        parts.add(new Part(Joint.HEAD, bodyHalfX, 0, bodyZ + 0.01,
                0.11, 0, 0.01, 0.12, bodyHalfY * 0.75, bodyHalfZ * 0.85,
                AnimalSkins.Region.HEAD));
        parts.add(new Part(Joint.HEAD, bodyHalfX, 0, bodyZ + 0.01,
                0.10, 0, 0.06, 0.05, bodyHalfY * 0.8, 0.03,
                AnimalSkins.Region.EYE));
        if (tailLen > 0.02) {
            parts.add(new Part(Joint.TAIL, -bodyHalfX, 0, bodyZ,
                    -tailLen / 2, 0, 0, tailLen / 2, 0.04, 0.035,
                    AnimalSkins.Region.TAIL));
        }
        return new AnimalModel(parts, bodyZ);
    }

    /** A fish: a body, a tail fin, a dorsal, and a pair of pectorals. */
    private static AnimalModel fish() {
        List<Part> parts = new ArrayList<>();
        double bodyZ = 0;
        parts.add(new Part(Joint.BODY, 0, 0, bodyZ, 0.04, 0, 0,
                0.32, 0.09, 0.16, AnimalSkins.Region.BODY));
        parts.add(new Part(Joint.BODY, 0, 0, bodyZ, 0.04, 0, -0.11,
                0.26, 0.07, 0.05, AnimalSkins.Region.BELLY));
        parts.add(new Part(Joint.HEAD, 0.34, 0, bodyZ, 0.08, 0, 0,
                0.10, 0.075, 0.11, AnimalSkins.Region.HEAD));
        parts.add(new Part(Joint.HEAD, 0.34, 0, bodyZ, 0.10, 0, 0.05,
                0.04, 0.08, 0.03, AnimalSkins.Region.EYE));
        parts.add(new Part(Joint.TAIL, -0.28, 0, bodyZ, -0.12, 0, 0,
                0.13, 0.015, 0.17, AnimalSkins.Region.TAIL));
        parts.add(new Part(Joint.EXTRA, 0, 0, bodyZ, 0, 0, 0.20,
                0.16, 0.012, 0.07, AnimalSkins.Region.WING));
        for (int side = -1; side <= 1; side += 2) {
            Joint joint = side < 0 ? Joint.WING_L : Joint.WING_R;
            parts.add(new Part(joint, 0.18, side * 0.08, bodyZ,
                    -0.04, side * 0.07, -0.02, 0.07, 0.07, 0.012,
                    AnimalSkins.Region.WING));
        }
        return new AnimalModel(parts, 0);
    }

    /** A butterfly or moth: a thin body, four wings, and antennae. */
    private static AnimalModel insect() {
        List<Part> parts = new ArrayList<>();
        double bodyZ = 0.06;
        parts.add(new Part(Joint.BODY, 0, 0, bodyZ, 0, 0, 0,
                0.26, 0.035, 0.035, AnimalSkins.Region.BODY));
        parts.add(new Part(Joint.HEAD, 0.26, 0, bodyZ, 0.05, 0, 0.01,
                0.05, 0.045, 0.045, AnimalSkins.Region.HEAD));
        for (int side = -1; side <= 1; side += 2) {
            Joint joint = side < 0 ? Joint.WING_L : Joint.WING_R;
            // Fore and hind wings, so a butterfly reads as one rather than as
            // a paper aeroplane.
            parts.add(new Part(joint, 0.06, side * 0.03, bodyZ,
                    0.10, side * 0.30, 0.01, 0.20, 0.30, 0.006,
                    AnimalSkins.Region.WING));
            parts.add(new Part(joint, -0.10, side * 0.03, bodyZ,
                    -0.10, side * 0.20, -0.01, 0.13, 0.20, 0.006,
                    AnimalSkins.Region.WING));
            parts.add(new Part(Joint.EAR, 0.30, side * 0.02, bodyZ + 0.03,
                    0.06, side * 0.05, 0.06, 0.07, 0.008, 0.008,
                    AnimalSkins.Region.HARD));
        }
        return new AnimalModel(parts, bodyZ);
    }

    /** Whatever a sprite is: a core, a halo of shards, and no legs. */
    private static AnimalModel sprite() {
        List<Part> parts = new ArrayList<>();
        double bodyZ = 0.35;
        parts.add(new Part(Joint.BODY, 0, 0, bodyZ, 0, 0, 0,
                0.16, 0.16, 0.16, AnimalSkins.Region.BODY));
        parts.add(new Part(Joint.HEAD, 0, 0, bodyZ, 0.10, 0, 0.10,
                0.07, 0.07, 0.07, AnimalSkins.Region.EYE));
        for (int i = 0; i < 5; i++) {
            double a = i * Math.PI * 2 / 5;
            Joint joint = i % 2 == 0 ? Joint.WING_L : Joint.WING_R;
            parts.add(new Part(joint, 0, 0, bodyZ,
                    Math.cos(a) * 0.30, Math.sin(a) * 0.30, Math.sin(a * 2) * 0.12,
                    0.05, 0.05, 0.12, AnimalSkins.Region.WING));
        }
        return new AnimalModel(parts, bodyZ);
    }
}
