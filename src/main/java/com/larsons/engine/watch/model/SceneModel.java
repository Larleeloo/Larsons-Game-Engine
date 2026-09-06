package com.larsons.engine.watch.model;

import com.larsons.engine.watch.life.AnimState;
import com.larsons.engine.watch.life.AnimalModel;
import com.larsons.engine.watch.render.Mesh;
import com.larsons.engine.watch.render.Shapes;

import java.util.ArrayList;
import java.util.EnumMap;
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
 * <p>A model is measured on import and scaled so that one chosen extent is
 * exactly {@code 1}, with its lowest point on the ground. Which extent is
 * {@link Normalise}: a creature is measured nose to tail because that is what
 * {@code AnimalDef.bodyLength} means, and a person is measured head to foot
 * because that is what a person's size is. The consequence is the one that
 * matters to whoever is modelling: <b>work at any size you like.</b>
 */
public final class SceneModel {

    /** Which extent becomes {@code 1} on import. */
    public enum Normalise {
        /** Longest horizontal extent — what a creature's body length means. */
        BODY_LENGTH,
        /** Floor to crown — what a person's height means. */
        HEIGHT,
        /** Nothing: the file's own units are taken to be metres. */
        METRES
    }

    /** One node, baked: where it rests, what moves it, and what it is called. */
    private record Bone(String name, int parent, double[] translation, double[] rotation,
                        double[] scale, AnimalModel.Joint joint,
                        double pivotX, double pivotY, double pivotZ) {}

    /** One clip, with its tracks indexed by the node they move. */
    private record Take(double length, List<RawModel.Track> tracks) {}

    private final String name;
    private final ModelRig.Kind kind;
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
        this.kind = kind;
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
    public static SceneModel bake(RawModel raw, ModelRig.Kind kind, Normalise normalise) {
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
        double unit = switch (normalise) {
            case BODY_LENGTH -> 1 / Math.max(1e-6, Math.max(spanF, spanR));
            case HEIGHT -> 1 / Math.max(1e-6, spanU);
            case METRES -> 1;
        };
        double floor = minU * unit;

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
        mesh(mesh, x, y, z, yaw, state, phase, scale, uv, 0);
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
        Take take = clips.get(state);
        double[][] globals = take == null ? rest : sample(take, phase);
        double cos = Math.cos(yaw), sin = Math.sin(yaw);
        double[] point = new double[3];
        double[] a = new double[3], b = new double[3], c = new double[3];

        for (RawModel.Piece piece : pieces) {
            Bone bone = bones[piece.node()];
            // A state the model animates is drawn from its own clip; one it
            // does not is drawn at rest and posed by the procedural table, per
            // joint. That is what makes a two-clip model worth committing.
            AnimalModel.Pose pose = take == null
                    ? ModelRig.poseOf(kind, state, bone.joint(), phase) : null;
            double[] global = globals[piece.node()];
            float[] positions = piece.positions();
            int[] colours = piece.colours();

            double aim = bone.joint() == AnimalModel.Joint.HEAD ? headTurn : 0;
            for (int t = 0; t < colours.length; t++) {
                int at = t * 9;
                corner(global, positions, at, bone, pose, aim, point, a);
                corner(global, positions, at + 3, bone, pose, aim, point, b);
                corner(global, positions, at + 6, bone, pose, aim, point, c);
                Shapes.face(mesh,
                        x + (a[0] * cos - a[1] * sin) * scale,
                        y + (a[0] * sin + a[1] * cos) * scale, z + a[2] * scale,
                        x + (b[0] * cos - b[1] * sin) * scale,
                        y + (b[0] * sin + b[1] * cos) * scale, z + b[2] * scale,
                        x + (c[0] * cos - c[1] * sin) * scale,
                        y + (c[0] * sin + c[1] * cos) * scale, z + c[2] * scale,
                        uv, colours[t]);
            }
        }
    }

    /**
     * One corner: through its bone, into this game's axes, then through the
     * fallback pose if there is one.
     */
    private void corner(double[] global, float[] positions, int at, Bone bone,
                        AnimalModel.Pose pose, double aim, double[] scratch, double[] out) {
        RawModel.transform(global, positions[at], positions[at + 1], positions[at + 2],
                scratch);
        double forward = scratch[2] * unit;
        double right = scratch[0] * unit;
        double up = scratch[1] * unit - floor;
        if (pose == null) {
            out[0] = forward;
            out[1] = right;
            out[2] = up;
        } else {
            // The same three hinges, in the same order, as AnimalModel.emitBox —
            // roll about forward, pitch about right, turn about up, all of it
            // about the bone's own rest pivot.
            double cp = Math.cos(pose.pitch()), sp = Math.sin(pose.pitch());
            double cr = Math.cos(pose.roll()), sr = Math.sin(pose.roll());
            double ct = Math.cos(pose.turn()), st = Math.sin(pose.turn());
            double px = forward - bone.pivotX();
            double py = (right - bone.pivotY()) * pose.spread();
            double pz = up - bone.pivotZ();
            double ry = py * cr - pz * sr;
            double rz = py * sr + pz * cr;
            double fx = px * cp + rz * sp;
            double fz = -px * sp + rz * cp;
            out[0] = bone.pivotX() + fx * ct - ry * st + pose.dx();
            out[1] = bone.pivotY() + fx * st + ry * ct + pose.dy();
            out[2] = bone.pivotZ() + fz + pose.dz();
        }
        if (aim == 0) return;
        // The head's own turn, last, about the same pivot: it has to compose
        // with whatever the clip already did to the neck rather than replace it.
        double ca = Math.cos(aim), sa = Math.sin(aim);
        double dx = out[0] - bone.pivotX(), dy = out[1] - bone.pivotY();
        out[0] = bone.pivotX() + dx * ca - dy * sa;
        out[1] = bone.pivotY() + dx * sa + dy * ca;
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
