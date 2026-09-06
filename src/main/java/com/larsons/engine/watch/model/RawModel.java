package com.larsons.engine.watch.model;

import java.util.ArrayList;
import java.util.List;

/**
 * A model as its file said it, before this game has had an opinion about it.
 *
 * <p><b>One shape for three formats.</b> {@link ObjReader} and
 * {@link GltfReader} each know one file format and nothing about this game;
 * {@link SceneModel} knows this game and nothing about file formats. This is
 * what they hand across, and keeping it deliberately dumb — a node tree, some
 * triangles, some keyframes — is what stops a fourth format later meaning a
 * fourth renderer.
 *
 * <h2>Whose axes these are</h2>
 *
 * <p><b>The file's, not the game's.</b> Everything in here is in the source
 * file's own coordinate system: {@code +x} right, {@code +y} up, {@code +z}
 * toward the front of the model — glTF's convention, which is also what
 * Blender's OBJ and glTF exporters produce with their default
 * <em>-Z forward, Y up</em> settings, and (not by coincidence) the same
 * permutation {@link com.larsons.engine.watch.life.Blockbench} already
 * converts from.
 *
 * <p>The swap to this game's {@code +x} forward, {@code +y} right, {@code +z}
 * up happens exactly once, in {@link SceneModel#bake}, on the finished vertex.
 * Doing it here instead would mean converting rotations and interpolating
 * quaternions in a mirrored basis, which is a class of bug that shows up as
 * an arm that swings the wrong way only during the second half of a clip.
 *
 * <h2>Triangles carry colour, not texture</h2>
 *
 * <p>{@link Piece#colours()} is <b>one packed {@code 0xRRGGBB} per triangle</b>,
 * because that is what this world is: flat-shaded facets, each a single colour,
 * lit by {@link com.larsons.engine.watch.render.Shapes#face}. A file's own UVs
 * are read and thrown away — every mesh in this game samples one tile of the
 * world atlas, so a vertex UV out of a {@code .glb} would point at whatever
 * happened to be next to that tile. See the folder README for what this means
 * for an artist: paint with materials, not with an image.
 */
public final class RawModel {

    /**
     * One node of the file's tree — a bone, an empty, or an object.
     *
     * @param parent      index into the model's node list, or {@code -1} for a root
     * @param translation {@code x, y, z}
     * @param rotation    a quaternion, {@code x, y, z, w}, as glTF writes it
     * @param scale       {@code x, y, z}
     */
    public record Node(String name, int parent,
                       double[] translation, double[] rotation, double[] scale) {

        /** A node at rest, with no transform of its own. */
        public static Node identity(String name, int parent) {
            return new Node(name, parent, new double[]{0, 0, 0},
                    new double[]{0, 0, 0, 1}, new double[]{1, 1, 1});
        }
    }

    /** Which of a node's three transforms a track animates. */
    public enum Channel { TRANSLATION, ROTATION, SCALE }

    /**
     * How a track gets from one keyframe to the next.
     *
     * <p>{@link #CUBIC} is glTF's cubic spline, whose samples carry an in- and
     * out-tangent either side of each value. This game reads the values and
     * drops the tangents, degrading it to {@link #LINEAR} — for
     * {@code Blockbench}'s reason, spelled out there: a spline through hand-set
     * keys overshoots, and an overshooting limb bends backwards.
     */
    public enum Interpolation { LINEAR, STEP, CUBIC }

    /**
     * One node's animation on one channel.
     *
     * @param times  keyframe times in seconds, ascending
     * @param values {@code times.length} tuples — three long for a translation
     *               or a scale, four for a rotation
     */
    public record Track(int node, Channel channel, Interpolation interpolation,
                        double[] times, double[] values) {}

    /** One animation: a length in seconds, and the tracks that play over it. */
    public record Clip(String name, double length, boolean loop, List<Track> tracks) {}

    /**
     * Triangles that move with one node.
     *
     * <p>Vertices are in <b>that node's own space</b>, so drawing is
     * {@code global(node, time) × vertex} and nothing else. A skinned mesh is
     * folded into this shape by {@link GltfReader}, which picks each triangle's
     * dominant bone and pushes the vertices into it — see that class for why
     * rigid binding is the right answer in a world made of flat facets.
     *
     * @param positions {@code 9} floats per triangle
     * @param colours   one packed {@code 0xRRGGBB} per triangle
     */
    public record Piece(int node, float[] positions, int[] colours) {

        /** How many triangles are in here. */
        public int triangles() { return colours.length; }
    }

    private final String name;
    private final List<Node> nodes;
    private final List<Piece> pieces;
    private final List<Clip> clips;

    RawModel(String name, List<Node> nodes, List<Piece> pieces, List<Clip> clips) {
        this.name = name;
        this.nodes = List.copyOf(nodes);
        this.pieces = List.copyOf(pieces);
        this.clips = List.copyOf(clips);
    }

    /** What the file called itself, for the line printed when it will not load. */
    public String name() { return name; }

    public List<Node> nodes() { return nodes; }

    public List<Piece> pieces() { return pieces; }

    public List<Clip> clips() { return clips; }

    /** How many triangles the whole model is, which is what a budget counts. */
    public int triangles() {
        int n = 0;
        for (Piece piece : pieces) n += piece.triangles();
        return n;
    }

    /** Whether there is anything to draw. A model with nothing is not a model. */
    public boolean empty() { return triangles() == 0; }

    // --- matrices --------------------------------------------------------------------
    //
    // Row-major 4×4 in a double[16], which is the layout the rest of this
    // package reads directly. Small and local on purpose: the graphics package
    // has its own Mat4 for the camera, in float and in its own conventions, and
    // sharing one across a renderer boundary to save forty lines would mean
    // every change to either having to be checked against both.

    /** A fresh identity matrix. */
    public static double[] identity() {
        return new double[]{1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
    }

    /** {@code a × b}, into a new matrix. */
    public static double[] multiply(double[] a, double[] b) {
        double[] out = new double[16];
        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < 4; col++) {
                double sum = 0;
                for (int k = 0; k < 4; k++) sum += a[row * 4 + k] * b[k * 4 + col];
                out[row * 4 + col] = sum;
            }
        }
        return out;
    }

    /** A node's own transform, as {@code translation × rotation × scale}. */
    public static double[] compose(double[] t, double[] r, double[] s) {
        double x = r[0], y = r[1], z = r[2], w = r[3];
        double n = Math.sqrt(x * x + y * y + z * z + w * w);
        if (n > 1e-12) { x /= n; y /= n; z /= n; w /= n; } else { x = y = z = 0; w = 1; }
        double xx = x * x, yy = y * y, zz = z * z;
        double xy = x * y, xz = x * z, yz = y * z;
        double wx = w * x, wy = w * y, wz = w * z;
        return new double[]{
                (1 - 2 * (yy + zz)) * s[0], (2 * (xy - wz)) * s[1], (2 * (xz + wy)) * s[2], t[0],
                (2 * (xy + wz)) * s[0], (1 - 2 * (xx + zz)) * s[1], (2 * (yz - wx)) * s[2], t[1],
                (2 * (xz - wy)) * s[0], (2 * (yz + wx)) * s[1], (1 - 2 * (xx + yy)) * s[2], t[2],
                0, 0, 0, 1};
    }

    /** {@code m × (x, y, z, 1)}, written into {@code out}. */
    public static void transform(double[] m, double x, double y, double z, double[] out) {
        out[0] = m[0] * x + m[1] * y + m[2] * z + m[3];
        out[1] = m[4] * x + m[5] * y + m[6] * z + m[7];
        out[2] = m[8] * x + m[9] * y + m[10] * z + m[11];
    }

    /**
     * The inverse of an affine matrix.
     *
     * <p>General enough for a bind pose that has been scaled non-uniformly,
     * which Blender does produce and which a transpose-and-negate shortcut
     * would silently get wrong. A singular matrix — a bone scaled to nothing —
     * comes back as the identity rather than as {@code NaN} spreading through
     * every vertex bound to it.
     */
    public static double[] invert(double[] m) {
        double a = m[0], b = m[1], c = m[2];
        double d = m[4], e = m[5], f = m[6];
        double g = m[8], h = m[9], i = m[10];
        double det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g);
        if (Math.abs(det) < 1e-12) return identity();
        double inv = 1 / det;
        double[] r = new double[16];
        r[0] = (e * i - f * h) * inv;
        r[1] = (c * h - b * i) * inv;
        r[2] = (b * f - c * e) * inv;
        r[4] = (f * g - d * i) * inv;
        r[5] = (a * i - c * g) * inv;
        r[6] = (c * d - a * f) * inv;
        r[8] = (d * h - e * g) * inv;
        r[9] = (b * g - a * h) * inv;
        r[10] = (a * e - b * d) * inv;
        double tx = m[3], ty = m[7], tz = m[11];
        r[3] = -(r[0] * tx + r[1] * ty + r[2] * tz);
        r[7] = -(r[4] * tx + r[5] * ty + r[6] * tz);
        r[11] = -(r[8] * tx + r[9] * ty + r[10] * tz);
        r[15] = 1;
        return r;
    }

    /**
     * Every node's transform in model space, at rest.
     *
     * <p>Parents are resolved before children, which the readers guarantee by
     * emitting nodes in tree order. A node whose parent index points forward or
     * at itself — a malformed file, or a cycle — is treated as a root rather
     * than looping for ever.
     */
    public double[][] restGlobals() {
        double[][] globals = new double[nodes.size()][];
        for (int i = 0; i < nodes.size(); i++) {
            Node node = nodes.get(i);
            double[] local = compose(node.translation(), node.rotation(), node.scale());
            globals[i] = node.parent() >= 0 && node.parent() < i
                    ? multiply(globals[node.parent()], local)
                    : local;
        }
        return globals;
    }

    /** A builder, because a reader discovers nodes and triangles as it goes. */
    public static final class Builder {
        private final String name;
        private final List<Node> nodes = new ArrayList<>();
        private final List<Piece> pieces = new ArrayList<>();
        private final List<Clip> clips = new ArrayList<>();

        public Builder(String name) { this.name = name; }

        /** Add a node, returning its index. */
        public int node(Node node) {
            nodes.add(node);
            return nodes.size() - 1;
        }

        public void piece(Piece piece) {
            if (piece.triangles() > 0) pieces.add(piece);
        }

        public void clip(Clip clip) {
            if (!clip.tracks().isEmpty()) clips.add(clip);
        }

        public List<Node> nodes() { return nodes; }

        public RawModel build() { return new RawModel(name, nodes, pieces, clips); }
    }
}
