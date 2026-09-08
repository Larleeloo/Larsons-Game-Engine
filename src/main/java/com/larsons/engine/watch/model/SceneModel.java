package com.larsons.engine.watch.model;

import com.larsons.engine.watch.life.AnimState;
import com.larsons.engine.watch.life.AnimalModel;
import com.larsons.engine.watch.life.Blockbench;
import com.larsons.engine.watch.render.Mesh;
import com.larsons.engine.watch.render.Shapes;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * An imported model, ready to draw — <b>the other kind of geometry in this game.</b>
 *
 * <p>{@link AnimalModel} is a pile of boxes and it always will be; that is what
 * thirteen hundred procedural species are made of and what a {@code .bbmodel}
 * imports into. This is the free-form one: triangles out of a {@code .glb} or
 * an {@code .obj}, bound to a skeleton, posed by clips the artist authored or —
 * for any state they have not got to yet — by the same procedural table the
 * placeholders use.
 *
 * <p>Both end up in the same {@link Mesh} through the same {@link Shapes#face},
 * so a chunk holding an imported ranger and a generated wren cannot tell them
 * apart, and neither can the renderer, the fog, or the shadow pass.
 *
 * <h2>Where the axis swap happens</h2>
 *
 * <p><b>Here, once, on the finished vertex.</b> Node transforms, quaternions and
 * keyframes all stay in the file's own space — {@code +x} right, {@code +y} up,
 * {@code +z} front — right up until a corner is written into the mesh, at which
 * point it becomes this game's {@code +x} forward, {@code +y} right, {@code +z}
 * up. The swap is a cyclic permutation, so it preserves handedness and a file's
 * counter-clockwise winding is still counter-clockwise here; a model exported
 * with a mirrored (negatively scaled) object is the one case that comes out
 * inside-out, which is why the README says to apply your modifiers.
 *
 * <h2>Normalising</h2>
 *
 * <p>A model is measured on import and scaled so that its <b>height</b> is a
 * number the caller chose, with its lowest point on the ground. The consequence
 * that matters to whoever is modelling is: <b>work at any size you like.</b>
 *
 * <p><b>Height, and not the longest horizontal extent.</b> An earlier version
 * measured a creature nose to tail, on the reasoning that this is what
 * {@code AnimalDef.bodyLength} means. That is a fair description of a heron and
 * a nonsense for a wendigo, whose widest horizontal measurement is its antler
 * spread — the first real import to arrive came out ten metres tall against its
 * placeholder's seven, and two and a half times as wide. Height is the
 * measurement a quadruped and a biped both have, and matching the placeholder's
 * makes an import exactly the size of the thing it replaces. See
 * {@link AnimalModel#height}.
 */
public final class SceneModel {

    /**
     * What to add to a <b>person's</b> yaw before handing it to {@link #mesh}.
     *
     * <p><b>This game has two facing conventions and they are ninety degrees
     * apart.</b> An animal's boxes point along {@code +x} at a yaw of zero
     * ({@code AnimalModel}), and this class was written to match them — so a
     * file's front, which the README asks you to point down Blender's {@code −Y},
     * comes out along {@code +x} too and an imported wren faces the way its
     * placeholder did. A <em>person</em> is drawn the other way round:
     * {@code WalkerModel}, {@code KeeperModel} and {@code RangerModel} all take
     * forward as {@code (sin yaw, −cos yaw)}, which at zero is {@code −y}.
     *
     * <p>Nothing was wrong until a person was imported. Handed a walker's yaw
     * unturned, a modelled figure stands square to the one underneath it and a
     * cape hangs off somebody's left shoulder — which is the "walks sideways"
     * failure the README warns about, arriving from the engine rather than from
     * the export. So every caller drawing an imported <em>person</em> adds this,
     * and {@code ModelImportTest.anImportedPersonFacesTheWayTheBoxesFace} pins
     * it: the file's front lands exactly where the box model's forward is, at
     * three different yaws.
     *
     * <p>A creature adds nothing. Its two conventions already agree.
     */
    public static final double PERSON_TURN = -Math.PI / 2;

    /**
     * A whole-body tip, for a figure that is not standing up.
     *
     * <p><b>Why this is a parameter and not an animation</b> — the same
     * argument {@link #mesh}'s {@code headTurn} makes, one joint further out.
     * A swimmer's body angle is not a pose an artist can key: it runs
     * continuously from upright, treading water, through flat on the surface,
     * to head-down in a dive, and which of those it is depends on where the
     * player is looking. No clip knows that. So the {@code swim} clip is
     * authored **upright**, doing the arms and the legs of a breaststroke, and
     * the tip is applied here, afterwards, about a pivot up the body.
     *
     * <p>That is also what keeps a modelled swimmer continuous with a modelled
     * walker: at {@code pitch} zero this is the standing figure exactly, so
     * somebody wading out of their depth tips over into a swim rather than
     * cutting to a different model. {@code WalkerModel.swimmer} makes the same
     * promise about the boxes and for the same reason.
     *
     * @param pitch radians to tip forward — {@code 0} upright, {@code π/2}
     *              face-down and flat, negative for a head-up float
     * @param pivot how far up the model, as a share of its height, the tip
     *              turns about. The hips, for a swimmer: turned about the neck
     *              instead the same body floats with its whole chest in the air
     */
    public record Lean(double pitch, double pivot) {
        /** Standing up: what everything but a swimmer passes. */
        public static final Lean UPRIGHT = new Lean(0, 0);

        /** Whether this does nothing, which is the common case and a fast path. */
        public boolean none() { return pitch == 0; }
    }

    /**
     * How big a model should come out, and where its floor is.
     *
     * @param height   floor to crown, in whatever units the caller then draws
     *                 at — one body length for a creature, one person for a
     *                 person — or {@code 0} to keep the file's own units
     * @param grounded whether the model's lowest point is dropped to the
     *                 caller's {@code z}. True for anything that stands on the
     *                 ground, which is nearly everything; false for a thing
     *                 modelled <em>in place</em> against something else, where
     *                 the height the artist put it at is the answer rather than
     *                 an accident. See {@link #AS_PLACED}
     */
    public record Size(double height, boolean grounded) {

        /** The file's own units, taken to be metres, stood on the floor. */
        public static final Size AS_MODELLED = new Size(0, true);

        /**
         * The file's own units <em>and</em> the file's own height off the floor.
         *
         * <p>For something modelled <em>on</em> something else rather than
         * standing on the ground — a hat, a cape, a pair of boots, authored
         * where they sit on a reference figure at the origin. Grounding one of
         * those is exactly wrong: it takes a hat modelled at 1.8 m and puts it
         * on the floor, because the lowest point of a hat is the underside of
         * its brim.
         */
        public static final Size AS_PLACED = new Size(0, false);

        /** Scale so the model stands exactly this many units tall. */
        public static Size height(double units) {
            return new Size(Math.max(1e-6, units), true);
        }
    }

    /** One node, baked: where it rests, what moves it, and what it is called. */
    private record Bone(String name, int parent, double[] translation, double[] rotation,
                        double[] scale, AnimalModel.Joint joint,
                        double pivotX, double pivotY, double pivotZ) {}

    /** One clip, with its tracks indexed by the node they move. */
    private record Take(double length, List<RawModel.Track> tracks) {}

    /**
     * Where a figure's joints have got to at one moment — <b>what a garment worn
     * over it has to follow.</b>
     *
     * <h2>The bug this exists to fix</h2>
     *
     * <p>A worn piece and the body under it are two separate models with two
     * separate rigs, drawn by two separate calls. Left to itself each animates
     * on its own: the body plays the {@code walk} the artist authored — which
     * drops the root to put the lower boot on the floor, leans the chest, nods
     * the head — and the garment, having no clip of its own, is posed by
     * {@link ModelRig}'s procedural table instead. So the walker bobbed and the
     * hat did not. Standing still it was invisible; at a run the hat hung in the
     * air while the head dropped 50 mm out from under it, once a stride.
     *
     * <p>Giving every garment a copy of the body's clips would have worked and
     * is what the folder README used to suggest. It is also thirty-six copies of
     * one animation, several megabytes of keyframes, and thirty-six chances for
     * a cloak to disagree with the coat under it. This is the other answer: the
     * body is asked where its joints went, and the clothes are drawn through
     * that.
     *
     * <h2>What it is</h2>
     *
     * <p>One rigid transform per bone, <b>in world metres relative to the
     * figure's feet</b>, taking a point where that bone rests to where it is
     * now. Keyed by bone name first and by {@link AnimalModel.Joint} second,
     * because the two rigs share README §10's names — a mitten on {@code hand_l}
     * follows the wearer's {@code hand_l} exactly — and a garment bone named
     * something the body has not got still has a joint in common with one.
     *
     * <p><b>It composes rather than replaces.</b> A piece with a clip of its own
     * plays it and is then moved by this on top, which is the physically honest
     * order: a cloak's swing is motion relative to the shoulders it hangs from.
     * A piece with no clip is simply carried.
     */
    public static final class Worn {

        private final Map<String, double[]> byBone;
        private final Map<AnimalModel.Joint, double[]> byJoint;

        private Worn(Map<String, double[]> byBone,
                     Map<AnimalModel.Joint, double[]> byJoint) {
            this.byBone = byBone;
            this.byJoint = byJoint;
        }

        /**
         * The transform for a garment bone, or {@code null} to leave it where it
         * is.
         *
         * <p>By name, then by joint. The fallback matters more than it looks:
         * a cape rigged to one bone called {@code cape_spine} has no
         * counterpart on the body at all, and what it wants is the spine's.
         */
        double[] forBone(String name, AnimalModel.Joint joint) {
            double[] exact = byBone.get(Blockbench.normalise(name));
            return exact != null ? exact : byJoint.get(joint);
        }

        /** Whether anything here actually moves anything. */
        public boolean still() { return byBone.isEmpty() && byJoint.isEmpty(); }

        /** Apply one of {@link #forBone}'s transforms to a point, in place. */
        static void apply(double[] m, double[] p) {
            double x = p[0], y = p[1], z = p[2];
            p[0] = m[0] * x + m[1] * y + m[2] * z + m[3];
            p[1] = m[4] * x + m[5] * y + m[6] * z + m[7];
            p[2] = m[8] * x + m[9] * y + m[10] * z + m[11];
        }
    }

    private final String name;
    private final AnimalModel.PoseSource ownPoses;
    private final Bone[] bones;
    private final List<RawModel.Piece> pieces;
    private final Map<AnimState, Take> clips;
    private final double[][] rest;
    private final double unit;
    private final double floor;
    private final double height;
    private final double length;
    private final int triangles;

    private SceneModel(String name, ModelRig.Kind kind, Bone[] bones,
                       List<RawModel.Piece> pieces, Map<AnimState, Take> clips,
                       double[][] rest, double unit, double floor,
                       double height, double length, int triangles) {
        this.name = name;
        this.ownPoses = (state, joint, phase) -> ModelRig.poseOf(kind, state, joint, phase);
        this.bones = bones;
        this.pieces = pieces;
        this.clips = clips;
        this.rest = rest;
        this.unit = unit;
        this.floor = floor;
        this.height = height;
        this.length = length;
        this.triangles = triangles;
    }

    /**
     * Turn a parsed file into something drawable.
     *
     * @return the model, or {@code null} when there is nothing in it to draw —
     *         which every caller treats as "keep the fallback"
     */
    public static SceneModel bake(RawModel raw, ModelRig.Kind kind, Size size) {
        if (raw == null || raw.empty()) return null;
        double[][] rest = raw.restGlobals();
        List<RawModel.Node> nodes = raw.nodes();

        // Pass one: measure the rest pose, in this game's axes, so the file can
        // be scaled and stood on the ground.
        double minF = Double.MAX_VALUE, maxF = -Double.MAX_VALUE;
        double minR = Double.MAX_VALUE, maxR = -Double.MAX_VALUE;
        double minU = Double.MAX_VALUE, maxU = -Double.MAX_VALUE;
        double[] point = new double[3];
        for (RawModel.Piece piece : raw.pieces()) {
            if (piece.node() < 0 || piece.node() >= rest.length) continue;
            double[] global = rest[piece.node()];
            float[] positions = piece.positions();
            for (int i = 0; i + 2 < positions.length; i += 3) {
                RawModel.transform(global, positions[i], positions[i + 1],
                        positions[i + 2], point);
                // File (right, up, front) to game (forward, right, up).
                double forward = point[2], right = point[0], up = point[1];
                minF = Math.min(minF, forward); maxF = Math.max(maxF, forward);
                minR = Math.min(minR, right); maxR = Math.max(maxR, right);
                minU = Math.min(minU, up); maxU = Math.max(maxU, up);
            }
        }
        if (minU > maxU) return null;

        double spanF = maxF - minF, spanR = maxR - minR, spanU = maxU - minU;
        double unit = size.height() > 0 ? size.height() / Math.max(1e-6, spanU) : 1;
        // Where the model's own floor is, and therefore how much comes off
        // every vertex to stand it on the caller's z. Zero for something
        // modelled in place — see Size.AS_PLACED.
        double floor = size.grounded() ? minU * unit : 0;

        Bone[] bones = new Bone[nodes.size()];
        for (int i = 0; i < nodes.size(); i++) {
            RawModel.Node node = nodes.get(i);
            AnimalModel.Joint joint = ModelRig.jointOf(node.name(), kind);
            if (joint == null && node.parent() >= 0 && node.parent() < i) {
                // A bone that names no joint inherits its parent's, which is
                // what lets `brim` under `head` work without being called head.
                joint = bones[node.parent()].joint();
            }
            if (joint == null) joint = AnimalModel.Joint.BODY;
            double[] global = rest[i];
            bones[i] = new Bone(node.name(), node.parent(), node.translation(),
                    node.rotation(), node.scale(), joint,
                    global[11] * unit, global[3] * unit, global[7] * unit - floor);
        }

        Map<AnimState, Take> clips = new EnumMap<>(AnimState.class);
        for (RawModel.Clip clip : raw.clips()) {
            AnimState state = AnimState.forClip(clip.name());
            if (state == null || clips.containsKey(state)) continue;
            clips.put(state, new Take(clip.length(), clip.tracks()));
        }

        List<RawModel.Piece> pieces = new ArrayList<>();
        for (RawModel.Piece piece : raw.pieces()) {
            if (piece.node() >= 0 && piece.node() < bones.length) pieces.add(piece);
        }
        if (pieces.isEmpty()) return null;

        return new SceneModel(raw.name(), kind, bones, List.copyOf(pieces), clips, rest,
                unit, floor, spanU * unit, Math.max(spanF, spanR) * unit, raw.triangles());
    }

    /** What the file called itself. */
    public String name() { return name; }

    /** How tall the model is in its normalised space — {@code 1} for a person. */
    public double height() { return height; }

    /** Its longest horizontal extent, normalised — {@code 1} for a creature. */
    public double length() { return length; }

    /** How many triangles one of these costs a frame. */
    public int triangles() { return triangles; }

    /** Which states this model animates itself. Everything else falls back. */
    public Set<AnimState> states() { return clips.keySet(); }

    /** Whether the artist supplied a clip for a state. */
    public boolean animates(AnimState state) { return clips.containsKey(state); }

    /**
     * Write the model into a mesh.
     *
     * @param x     where it stands, relative to the mesh's origin; {@code z} is
     *              the ground under its feet
     * @param yaw   which way it faces, in radians
     * @param phase how far through the state's cycle, in turns
     * @param scale metres per normalised unit — a person's height, a creature's
     *              body length
     * @param uv    the atlas tile every triangle samples; see {@link RawModel}
     */
    public void mesh(Mesh.Builder mesh, double x, double y, double z, double yaw,
                     AnimState state, double phase, double scale, float[] uv) {
        mesh(mesh, x, y, z, yaw, state, phase, scale, uv, 0, null);
    }

    /**
     * {@link #mesh} with the head turned off the body's facing.
     *
     * <p><b>Why this is a parameter and not an animation.</b> A figure whose
     * head follows you across the front of a shop is a person and one facing
     * straight ahead is furniture — {@code KeeperModel} says so at length and it
     * is right. But where the head is pointing depends on where <em>you</em> are
     * standing, which no authored clip can know. So it is applied here, after
     * the clip, as a turn about the head bone's own pivot: an imported model
     * keeps the trick without its artist having to do anything, and without a
     * runtime rig that could point any bone anywhere.
     *
     * @param headTurn radians about the vertical, applied to every bone bound
     *                 to {@link AnimalModel.Joint#HEAD}. Clamp it to what a neck
     *                 does before calling.
     */
    public void mesh(Mesh.Builder mesh, double x, double y, double z, double yaw,
                     AnimState state, double phase, double scale, float[] uv,
                     double headTurn) {
        mesh(mesh, x, y, z, yaw, state, phase, scale, uv, headTurn, null);
    }

    /**
     * {@link #mesh} with the fallback animation supplied from outside.
     *
     * <p><b>Which table poses the states this model did not animate.</b> Left
     * to itself a creature falls back to the shared animal poses, which is
     * right for the thirteen hundred and wrong for the three mutants: that
     * table is a good <em>animal</em> walk, and running it on a ten-metre biped
     * produces a ten-metre biped going for a pleasant walk. {@code MutantGait}
     * exists because of that, and this is how an imported wendigo gets it —
     * a model that ships {@code walk} and {@code attack} still has eight states
     * to be posed in, and they should be posed as the thing it is.
     *
     * <p>Passed at draw time rather than baked in, so the geometry stays
     * cacheable on the file alone.
     *
     * @param fallback where a missing state's pose comes from, or {@code null}
     *                 for this model's own kind of rig
     */
    public void mesh(Mesh.Builder mesh, double x, double y, double z, double yaw,
                     AnimState state, double phase, double scale, float[] uv,
                     double headTurn, AnimalModel.PoseSource fallback) {
        mesh(mesh, x, y, z, yaw, state, phase, scale, uv, headTurn, fallback,
                Lean.UPRIGHT);
    }

    /**
     * {@link #mesh} with the whole body tipped over. See {@link Lean}.
     *
     * <p>Applied after the clip and after the head's own turn, so a swimmer
     * laid flat still has the breaststroke the clip gave them and still has a
     * head that looks where it is pointed.
     */
    public void mesh(Mesh.Builder mesh, double x, double y, double z, double yaw,
                     AnimState state, double phase, double scale, float[] uv,
                     double headTurn, AnimalModel.PoseSource fallback, Lean lean) {
        mesh(mesh, x, y, z, yaw, state, phase, scale, uv, headTurn, fallback, lean, null);
    }

    /**
     * {@link #mesh} drawn through the motion of the figure this is worn over.
     *
     * <p><b>For a garment, and it is what stops a hat hanging in the air while
     * the head under it bobs.</b> See {@link Worn}: the body is asked where its
     * joints went and every triangle here is carried along with the one it is
     * rigged to, on top of whatever this model's own clip did.
     *
     * @param worn the wearer's {@link #wornAt}, or {@code null} for a figure
     *             that is not being worn by anybody
     */
    public void mesh(Mesh.Builder mesh, double x, double y, double z, double yaw,
                     AnimState state, double phase, double scale, float[] uv,
                     double headTurn, AnimalModel.PoseSource fallback, Lean lean,
                     Worn worn) {
        AnimalModel.PoseSource poses = fallback == null ? ownPoses : fallback;
        Take take = clips.get(state);
        double[][] globals = take == null ? rest : sample(take, phase);
        double cos = Math.cos(yaw), sin = Math.sin(yaw);
        double[] point = new double[3];
        double[] a = new double[3], b = new double[3], c = new double[3];

        for (RawModel.Piece piece : pieces) {
            Bone bone = bones[piece.node()];
            double[] carry = worn == null ? null
                    : worn.forBone(bone.name(), bone.joint());
            // A state the model animates is drawn from its own clip; one it
            // does not is drawn at rest and posed by the procedural table, per
            // joint. That is what makes a two-clip model worth committing —
            // except on a garment being carried by a body, where the body's own
            // motion is the answer and the stand-in would fight it.
            AnimalModel.Pose pose = take == null && carry == null
                    ? poses.poseOf(state, bone.joint(), phase) : null;
            double[] global = globals[piece.node()];
            float[] positions = piece.positions();
            int[] colours = piece.colours();

            double aim = bone.joint() == AnimalModel.Joint.HEAD ? headTurn : 0;
            for (int t = 0; t < colours.length; t++) {
                int at = t * 9;
                corner(global, positions, at, bone, pose, aim, lean, point, a);
                corner(global, positions, at + 3, bone, pose, aim, lean, point, b);
                corner(global, positions, at + 6, bone, pose, aim, lean, point, c);
                metres(carry, a, scale);
                metres(carry, b, scale);
                metres(carry, c, scale);
                Shapes.face(mesh,
                        x + a[0] * cos - a[1] * sin, y + a[0] * sin + a[1] * cos, z + a[2],
                        x + b[0] * cos - b[1] * sin, y + b[0] * sin + b[1] * cos, z + b[2],
                        x + c[0] * cos - c[1] * sin, y + c[0] * sin + c[1] * cos, z + c[2],
                        uv, colours[t]);
            }
        }
    }

    /** Normalised units to metres above the figure's feet, and along with it. */
    private static void metres(double[] carry, double[] p, double scale) {
        p[0] *= scale;
        p[1] *= scale;
        p[2] *= scale;
        if (carry != null) Worn.apply(carry, p);
    }

    /**
     * One corner: through its bone, into this game's axes, then through the
     * fallback pose if there is one.
     */
    private void corner(double[] global, float[] positions, int at, Bone bone,
                        AnimalModel.Pose pose, double aim, Lean lean,
                        double[] scratch, double[] out) {
        RawModel.transform(global, positions[at], positions[at + 1], positions[at + 2],
                scratch);
        out[0] = scratch[2] * unit;
        out[1] = scratch[0] * unit;
        out[2] = scratch[1] * unit - floor;
        if (pose != null) posed(bone, pose, out);
        if (aim != 0) {
            // The head's own turn, about the same pivot: it has to compose with
            // whatever the clip already did to the neck rather than replace it.
            double ca = Math.cos(aim), sa = Math.sin(aim);
            double dx = out[0] - bone.pivotX(), dy = out[1] - bone.pivotY();
            out[0] = bone.pivotX() + dx * ca - dy * sa;
            out[1] = bone.pivotY() + dx * sa + dy * ca;
        }
        if (lean.none()) return;
        // …and the whole body last of all, about a pivot up the model rather
        // than about any one bone's, because this is the figure lying down and
        // not a joint bending. Forward and up only: a tip has no yaw in it, and
        // the yaw the caller wants is applied outside this method anyway.
        double cl = Math.cos(lean.pitch()), sl = Math.sin(lean.pitch());
        double df = out[0], du = out[2] - lean.pivot();
        out[0] = df * cl + du * sl;
        out[2] = lean.pivot() - df * sl + du * cl;
    }

    /**
     * One point through a procedural {@link AnimalModel.Pose}, in place.
     *
     * <p>The same three hinges, in the same order, as {@code AnimalModel.emitBox}
     * — roll about forward, pitch about right, turn about up, all of it about
     * the bone's own rest pivot. Extracted because {@link #wornAt} has to apply
     * exactly this and a second copy of it would drift.
     */
    private static void posed(Bone bone, AnimalModel.Pose pose, double[] p) {
        double cp = Math.cos(pose.pitch()), sp = Math.sin(pose.pitch());
        double cr = Math.cos(pose.roll()), sr = Math.sin(pose.roll());
        double ct = Math.cos(pose.turn()), st = Math.sin(pose.turn());
        double px = p[0] - bone.pivotX();
        double py = (p[1] - bone.pivotY()) * pose.spread();
        double pz = p[2] - bone.pivotZ();
        double ry = py * cr - pz * sr;
        double rz = py * sr + pz * cr;
        double fx = px * cp + rz * sp;
        double fz = -px * sp + rz * cp;
        p[0] = bone.pivotX() + fx * ct - ry * st + pose.dx();
        p[1] = bone.pivotY() + fx * st + ry * ct + pose.dy();
        p[2] = bone.pivotZ() + fz + pose.dz();
    }

    /**
     * Where this figure's joints are at a moment of a state, for a garment worn
     * over it to be drawn through. See {@link Worn}.
     *
     * <p>Built by measuring rather than by algebra: the map from a bone's rest
     * space into world metres is affine, so it is recovered by pushing the
     * origin and the three unit axes through the whole chain — the bone's own
     * posed transform, the file-to-game axis permutation, the normalising scale,
     * the floor offset — and reading off the columns. Four points a bone, once a
     * draw, and no chance of a sign convention being written down twice.
     *
     * <p><b>The lean goes in here rather than being applied to the garment
     * separately</b>, and that is not tidiness. A {@link Lean}'s pivot is a
     * share of the model's own height, and a worn piece is the one model in this
     * game that is not normalised — its units are metres. Two different meanings
     * for one number is how a swimmer's cloak ends up tipping about their
     * ankles. Folded in here, {@code Worn} is simply where the joints went, and
     * the garment needs no opinion about leaning at all.
     *
     * @param scale metres per normalised unit, exactly as {@link #mesh} takes it
     * @param lean  the tip the wearer is drawn under, or {@link Lean#UPRIGHT}
     */
    public Worn wornAt(AnimState state, double phase, double scale, Lean lean) {
        Take take = clips.get(state);
        double[][] globals = take == null ? rest : sample(take, phase);
        Map<String, double[]> byBone = new LinkedHashMap<>();
        Map<AnimalModel.Joint, double[]> byJoint = new EnumMap<>(AnimalModel.Joint.class);
        double[] probe = new double[3];
        double[] origin = new double[3];
        double[] axis = new double[3];

        for (int i = 0; i < bones.length; i++) {
            Bone bone = bones[i];
            // A state with no clip is posed by the procedural table, per joint,
            // and the clothes have to follow *that* rather than nothing — which
            // is what a half-finished body dropped into the folder gets.
            AnimalModel.Pose pose = take == null
                    ? ownPoses.poseOf(state, bone.joint(), phase) : null;
            double[] moved = RawModel.multiply(globals[i], RawModel.invert(rest[i]));
            place(moved, bone, pose, scale, lean, 0, 0, 0, origin);
            double[] m = new double[12];
            for (int a = 0; a < 3; a++) {
                place(moved, bone, pose, scale, lean,
                        a == 0 ? 1 : 0, a == 1 ? 1 : 0, a == 2 ? 1 : 0, probe);
                for (int r = 0; r < 3; r++) axis[r] = probe[r] - origin[r];
                m[a] = axis[0];
                m[4 + a] = axis[1];
                m[8 + a] = axis[2];
            }
            m[3] = origin[0];
            m[7] = origin[1];
            m[11] = origin[2];
            if (identity(m)) continue;
            byBone.put(Blockbench.normalise(bone.name()), m);
            // First bone of a joint wins, which is the one nearest the root —
            // the whole limb rather than a fingertip.
            byJoint.putIfAbsent(bone.joint(), m);
        }
        return new Worn(byBone, byJoint);
    }

    /**
     * Where a point that rests at world metres {@code (mx, my, mz)} on this
     * figure has been moved to by {@code moved}.
     *
     * <p>Out of world metres, back through the axis permutation and the
     * normalising scale into the file's own space, through the bone's
     * rest-relative motion, and out again — which is why it is written once here
     * and read four times a bone rather than being inlined.
     *
     * @param moved this bone's transform relative to its rest, in file space
     */
    private void place(double[] moved, Bone bone, AnimalModel.Pose pose, double scale,
                       Lean lean, double mx, double my, double mz, double[] out) {
        double forward = mx / scale, right = my / scale, up = mz / scale + floor;
        // Game (forward, right, up) back to file (right, up, front).
        double[] local = new double[3];
        RawModel.transform(moved, right / unit, up / unit, forward / unit, local);
        double[] point = {local[2] * unit, local[0] * unit, local[1] * unit - floor};
        // Exactly the order `corner` applies them in, and for the same reason:
        // two copies of this that disagree is a garment that follows a body
        // almost everywhere.
        if (pose != null) posed(bone, pose, point);
        if (!lean.none()) {
            double cl = Math.cos(lean.pitch()), sl = Math.sin(lean.pitch());
            double df = point[0], du = point[2] - lean.pivot();
            point[0] = df * cl + du * sl;
            point[2] = lean.pivot() - df * sl + du * cl;
        }
        out[0] = point[0] * scale;
        out[1] = point[1] * scale;
        out[2] = point[2] * scale;
    }

    /** Whether a rigid transform leaves everything exactly where it was. */
    private static boolean identity(double[] m) {
        for (int i = 0; i < 12; i++) {
            double want = i == 0 || i == 5 || i == 10 ? 1 : 0;
            if (Math.abs(m[i] - want) > 1e-9) return false;
        }
        return true;
    }

    /**
     * Every bone's transform at a moment in a clip.
     *
     * <p>Allocates a matrix per bone per draw, which is the one place in this
     * class that would matter if a hundred of these were on screen. There are
     * not: an imported model is a character or a prop, and the things there are
     * hundreds of are boxes and terrain. If that ever changes, this is the
     * method to cache per (clip, quantised phase).
     */
    private double[][] sample(Take take, double phase) {
        double time = (phase - Math.floor(phase)) * take.length();
        double[][] translation = new double[bones.length][];
        double[][] rotation = new double[bones.length][];
        double[][] scale = new double[bones.length][];
        for (RawModel.Track track : take.tracks()) {
            int node = track.node();
            if (node < 0 || node >= bones.length) continue;
            double[] value = valueAt(track, time);
            if (value == null) continue;
            switch (track.channel()) {
                case TRANSLATION -> translation[node] = value;
                case ROTATION -> rotation[node] = value;
                case SCALE -> scale[node] = value;
            }
        }
        double[][] globals = new double[bones.length][];
        for (int i = 0; i < bones.length; i++) {
            Bone bone = bones[i];
            double[] local = RawModel.compose(
                    translation[i] != null ? translation[i] : bone.translation(),
                    rotation[i] != null ? rotation[i] : bone.rotation(),
                    scale[i] != null ? scale[i] : bone.scale());
            globals[i] = bone.parent() >= 0 && bone.parent() < i
                    ? RawModel.multiply(globals[bone.parent()], local)
                    : local;
        }
        return globals;
    }

    /**
     * A track's value at a time.
     *
     * <p>Holds before the first key and after the last, which is what a clip
     * that does not start at zero should do. Rotations are interpolated as
     * quaternions the short way round — negating one end when the pair points
     * apart, without which a bone takes the long way round on any turn past a
     * half circle, which looks exactly like a limb snapping.
     */
    private static double[] valueAt(RawModel.Track track, double time) {
        double[] times = track.times();
        double[] values = track.values();
        int stride = track.channel() == RawModel.Channel.ROTATION ? 4 : 3;
        int keys = Math.min(times.length, values.length / stride);
        if (keys <= 0) return null;
        if (keys == 1 || time <= times[0]) return tuple(values, 0, stride);
        if (time >= times[keys - 1]) return tuple(values, keys - 1, stride);

        int i = 0;
        while (i + 1 < keys && times[i + 1] < time) i++;
        int j = Math.min(keys - 1, i + 1);
        if (track.interpolation() == RawModel.Interpolation.STEP) {
            return tuple(values, i, stride);
        }
        double span = Math.max(1e-9, times[j] - times[i]);
        double t = (time - times[i]) / span;
        double[] a = tuple(values, i, stride);
        double[] b = tuple(values, j, stride);
        if (stride == 4) {
            double dot = a[0] * b[0] + a[1] * b[1] + a[2] * b[2] + a[3] * b[3];
            double sign = dot < 0 ? -1 : 1;
            double[] out = new double[4];
            for (int k = 0; k < 4; k++) out[k] = a[k] + (b[k] * sign - a[k]) * t;
            return out;
        }
        double[] out = new double[stride];
        for (int k = 0; k < stride; k++) out[k] = a[k] + (b[k] - a[k]) * t;
        return out;
    }

    private static double[] tuple(double[] values, int key, int stride) {
        double[] out = new double[stride];
        System.arraycopy(values, key * stride, out, 0, stride);
        return out;
    }
}
