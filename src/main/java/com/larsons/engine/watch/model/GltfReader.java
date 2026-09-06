package com.larsons.engine.watch.model;

import com.larsons.engine.util.Json;
import com.larsons.engine.watch.WatchJson;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads glTF 2.0 — <b>the path animation arrives by.</b>
 *
 * <p>{@code .glb} and {@code .gltf} both, with buffers embedded as a
 * {@code data:} URI, packed in a GLB's binary chunk, or sitting beside the file
 * as a {@code .bin}. It is the format Blender exports without a plugin, it
 * carries a skeleton and its clips in one file, and — the reason it is here
 * rather than FBX — it is JSON and a byte array, so this reads it with the
 * engine's own {@link Json} and nothing else. See {@code build.gradle.kts}: the
 * runtime has no dependencies and this did not change that.
 *
 * <h2>Rigid binding, deliberately</h2>
 *
 * <p>A skinned mesh gives every vertex up to four bones and a weight each, so
 * that a shoulder can bend smoothly. <b>This reader picks one bone per
 * triangle</b> — the one with the most weight across its three corners — and
 * moves the triangle with that bone alone.
 *
 * <p>That is not a shortcut around hard maths, it is the right answer for this
 * game twice over. The vertex format is
 * {@link com.larsons.engine.watch.render.Mesh}'s: three positions, two texture
 * floats and a packed colour, with nowhere to put four joint indices and four
 * weights, and widening it would cost every terrain chunk in the world memory
 * to carry fields only characters use. And the look is flat-shaded facets —
 * a smoothly deforming skin has nothing to deform <em>into</em> when each
 * triangle is one flat colour. Rigid parts are what this world already is.
 *
 * <p>What that means for an artist is in the folder README, and it is one
 * sentence: model in parts, not in one continuous skin.
 *
 * <h2>What is not read</h2>
 *
 * <p>Textures and images (see {@link RawModel} — colour comes from the
 * material's {@code baseColorFactor}), morph targets and their weight tracks,
 * cameras, lights, sparse accessors, and primitive modes other than triangles.
 * A file containing any of them still loads; those parts are skipped.
 */
public final class GltfReader {

    /** {@code 'glTF'} little-endian, the first four bytes of every GLB. */
    private static final int GLB_MAGIC = 0x46546C67;

    private static final int CHUNK_JSON = 0x4E4F534A;
    private static final int CHUNK_BIN = 0x004E4942;

    /** glTF's {@code mode} for a list of triangles. Nothing else is drawn. */
    private static final int MODE_TRIANGLES = 4;

    private GltfReader() {}

    /** Where an external buffer's bytes come from; {@code null} when there are none. */
    public interface Buffers {
        byte[] read(String uri);
    }

    /** Read a {@code .gltf} or {@code .glb} from disk, with its siblings. */
    public static RawModel load(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        Path folder = file.getParent();
        return parse(bytes, file.getFileName().toString(), uri -> {
            if (folder == null) return null;
            try {
                Path sibling = folder.resolve(decode(uri));
                return Files.isReadable(sibling) ? Files.readAllBytes(sibling) : null;
            } catch (IOException | RuntimeException e) {
                return null;
            }
        });
    }

    /** Read one from the classpath, resolving external buffers as sibling resources. */
    public static RawModel loadResource(String resourcePath) throws IOException {
        byte[] bytes = resource(resourcePath);
        if (bytes == null) return null;
        int slash = resourcePath.lastIndexOf('/');
        String folder = slash < 0 ? "" : resourcePath.substring(0, slash + 1);
        return parse(bytes, resourcePath, uri -> {
            try {
                return resource(folder + decode(uri));
            } catch (IOException | RuntimeException e) {
                return null;
            }
        });
    }

    /**
     * Parse a glTF document.
     *
     * @return the model, or {@code null} when the bytes are not glTF or hold no
     *         triangles — which leaves whatever asked for it with its fallback
     */
    public static RawModel parse(byte[] bytes, String name, Buffers external) {
        try {
            Document document = open(bytes);
            if (document == null) return null;
            RawModel built = new Reader(document, name, external).read();
            return built == null || built.empty() ? null : built;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** A glTF's JSON, and the GLB binary chunk when it came from one. */
    private record Document(Map<String, Object> json, byte[] binary) {}

    private static Document open(byte[] bytes) {
        if (bytes.length >= 12) {
            ByteBuffer head = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            if (head.getInt(0) == GLB_MAGIC) return glb(bytes);
        }
        Object parsed = Json.parse(new String(bytes, StandardCharsets.UTF_8));
        return parsed instanceof Map<?, ?>
                ? new Document(Json.asObject(parsed), null) : null;
    }

    /**
     * The chunks of a GLB.
     *
     * <p>Chunk lengths are read as unsigned and bounds-checked against what
     * actually arrived, so a truncated download is a model that does not load
     * rather than an exception out of the middle of a frame.
     */
    private static Document glb(byte[] bytes) {
        ByteBuffer in = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        in.position(12);
        Map<String, Object> json = null;
        byte[] binary = null;
        while (in.remaining() >= 8) {
            long length = in.getInt() & 0xFFFFFFFFL;
            int type = in.getInt();
            if (length > in.remaining()) break;
            byte[] chunk = new byte[(int) length];
            in.get(chunk);
            if (type == CHUNK_JSON && json == null) {
                Object parsed = Json.parse(new String(chunk, StandardCharsets.UTF_8));
                if (parsed instanceof Map<?, ?>) json = Json.asObject(parsed);
            } else if (type == CHUNK_BIN && binary == null) {
                binary = chunk;
            }
        }
        return json == null ? null : new Document(json, binary);
    }

    /** One parse, with the document's arrays to hand. */
    private static final class Reader {

        private final Map<String, Object> json;
        private final String name;
        private final Buffers external;
        private final byte[] glbBinary;

        private final List<Map<String, Object>> accessors;
        private final List<Map<String, Object>> views;
        private final List<Map<String, Object>> gltfNodes;
        private final List<Map<String, Object>> meshes;
        private final List<Map<String, Object>> materials;
        private final List<Map<String, Object>> skins;
        private final byte[][] buffers;

        /** glTF node index to the index the built model gave it. */
        private final Map<Integer, Integer> mapped = new HashMap<>();

        private final RawModel.Builder model;

        Reader(Document document, String name, Buffers external) {
            this.json = document.json();
            this.name = name;
            this.external = external;
            this.glbBinary = document.binary();
            this.accessors = WatchJson.objects(json, "accessors");
            this.views = WatchJson.objects(json, "bufferViews");
            this.gltfNodes = WatchJson.objects(json, "nodes");
            this.meshes = WatchJson.objects(json, "meshes");
            this.materials = WatchJson.objects(json, "materials");
            this.skins = WatchJson.objects(json, "skins");
            this.model = new RawModel.Builder(name);
            this.buffers = readBuffers();
        }

        RawModel read() {
            if (gltfNodes.isEmpty()) return null;
            hierarchy();
            geometry();
            animations();
            return model.build();
        }

        // --- the tree ------------------------------------------------------------

        /**
         * Flatten glTF's node array into a tree in parent-before-child order,
         * which is what {@link RawModel#restGlobals} relies on.
         *
         * <p>Walked from the scene's roots, then swept for anything the scene
         * left out — Blender writes every object into the scene, but a file
         * assembled by a script need not, and an orphaned bone that silently
         * vanished would be a limb that did not draw.
         */
        private void hierarchy() {
            Map<String, Object> scene = scene();
            boolean[] seen = new boolean[gltfNodes.size()];
            for (Object root : WatchJson.list(scene, "nodes")) {
                if (root instanceof Number n) walk(n.intValue(), -1, seen);
            }
            for (int i = 0; i < gltfNodes.size(); i++) {
                if (!seen[i]) walk(i, -1, seen);
            }
        }

        private Map<String, Object> scene() {
            List<Map<String, Object>> scenes = WatchJson.objects(json, "scenes");
            if (scenes.isEmpty()) return Map.of();
            int at = WatchJson.integer(json, "scene", 0);
            return at >= 0 && at < scenes.size() ? scenes.get(at) : scenes.get(0);
        }

        private void walk(int index, int parent, boolean[] seen) {
            if (index < 0 || index >= gltfNodes.size() || seen[index]) return;
            seen[index] = true;
            Map<String, Object> node = gltfNodes.get(index);
            int at = model.node(new RawModel.Node(
                    WatchJson.str(node, "name", "node" + index), parent,
                    translationOf(node), rotationOf(node), scaleOf(node)));
            mapped.put(index, at);
            for (Object child : WatchJson.list(node, "children")) {
                if (child instanceof Number n) walk(n.intValue(), at, seen);
            }
        }

        /**
         * A node's translation.
         *
         * <p>glTF lets a node carry either a TRS triple or one {@code matrix},
         * and Blender writes the matrix form for objects it could not express
         * as TRS. The matrix is <b>column-major</b>, so it is decomposed here
         * rather than transposed and used — the rest of this package thinks in
         * TRS because an animation track does.
         */
        private double[] translationOf(Map<String, Object> node) {
            double[] matrix = matrixOf(node);
            if (matrix != null) return new double[]{matrix[3], matrix[7], matrix[11]};
            return triple(node, "translation", 0, 0, 0);
        }

        private double[] scaleOf(Map<String, Object> node) {
            double[] m = matrixOf(node);
            if (m == null) return triple(node, "scale", 1, 1, 1);
            return new double[]{
                    length(m[0], m[4], m[8]),
                    length(m[1], m[5], m[9]),
                    length(m[2], m[6], m[10])};
        }

        private double[] rotationOf(Map<String, Object> node) {
            double[] m = matrixOf(node);
            if (m == null) {
                List<Object> list = WatchJson.list(node, "rotation");
                if (list.size() < 4) return new double[]{0, 0, 0, 1};
                return new double[]{number(list.get(0)), number(list.get(1)),
                        number(list.get(2)), number(list.get(3))};
            }
            double[] s = scaleOf(node);
            double sx = s[0] == 0 ? 1 : s[0], sy = s[1] == 0 ? 1 : s[1];
            double sz = s[2] == 0 ? 1 : s[2];
            // The rotation part, with the scale divided back out, into a
            // quaternion by Shepperd's method — branching on the largest
            // diagonal term, because the naive w-first form loses all its
            // precision on a half-turn, which is exactly the pose a mirrored
            // limb is in.
            double m00 = m[0] / sx, m01 = m[1] / sy, m02 = m[2] / sz;
            double m10 = m[4] / sx, m11 = m[5] / sy, m12 = m[6] / sz;
            double m20 = m[8] / sx, m21 = m[9] / sy, m22 = m[10] / sz;
            double trace = m00 + m11 + m22;
            double x, y, z, w;
            if (trace > 0) {
                double r = Math.sqrt(1 + trace) * 2;
                w = 0.25 * r;
                x = (m21 - m12) / r;
                y = (m02 - m20) / r;
                z = (m10 - m01) / r;
            } else if (m00 > m11 && m00 > m22) {
                double r = Math.sqrt(1 + m00 - m11 - m22) * 2;
                w = (m21 - m12) / r;
                x = 0.25 * r;
                y = (m01 + m10) / r;
                z = (m02 + m20) / r;
            } else if (m11 > m22) {
                double r = Math.sqrt(1 + m11 - m00 - m22) * 2;
                w = (m02 - m20) / r;
                x = (m01 + m10) / r;
                y = 0.25 * r;
                z = (m12 + m21) / r;
            } else {
                double r = Math.sqrt(1 + m22 - m00 - m11) * 2;
                w = (m10 - m01) / r;
                x = (m02 + m20) / r;
                y = (m12 + m21) / r;
                z = 0.25 * r;
            }
            return new double[]{x, y, z, w};
        }

        /** A node's {@code matrix}, transposed into row-major, or {@code null}. */
        private double[] matrixOf(Map<String, Object> node) {
            List<Object> list = WatchJson.list(node, "matrix");
            if (list.size() < 16) return null;
            double[] column = new double[16];
            for (int i = 0; i < 16; i++) column[i] = number(list.get(i));
            double[] row = new double[16];
            for (int c = 0; c < 4; c++) {
                for (int r = 0; r < 4; r++) row[r * 4 + c] = column[c * 4 + r];
            }
            return row;
        }

        // --- geometry ------------------------------------------------------------

        /** Every node that carries a mesh, turned into triangles bound to nodes. */
        private void geometry() {
            Map<Integer, Group> groups = new HashMap<>();
            for (int i = 0; i < gltfNodes.size(); i++) {
                Map<String, Object> node = gltfNodes.get(i);
                if (!node.containsKey("mesh")) continue;
                int meshIndex = WatchJson.integer(node, "mesh", -1);
                if (meshIndex < 0 || meshIndex >= meshes.size()) continue;
                Integer own = mapped.get(i);
                if (own == null) continue;
                Skin skin = skinOf(node);
                for (Map<String, Object> primitive
                        : WatchJson.objects(meshes.get(meshIndex), "primitives")) {
                    primitive(primitive, own, skin, groups);
                }
            }
            for (Map.Entry<Integer, Group> entry : groups.entrySet()) {
                model.piece(entry.getValue().piece(entry.getKey()));
            }
        }

        /**
         * A skin's joints, as model node indices, with each one's inverse bind
         * matrix.
         *
         * <p>The inverse bind is what takes a vertex from the mesh's own space
         * into the bone's, and it is read from the file rather than derived
         * from the rest pose, because the two are not always the same: a rig
         * posed when it was exported has a rest pose that is not its bind pose,
         * and deriving would bake that pose into the geometry twice.
         */
        private Skin skinOf(Map<String, Object> node) {
            int index = WatchJson.integer(node, "skin", -1);
            if (index < 0 || index >= skins.size()) return null;
            Map<String, Object> skin = skins.get(index);
            List<Object> joints = WatchJson.list(skin, "joints");
            if (joints.isEmpty()) return null;
            int[] nodes = new int[joints.size()];
            for (int i = 0; i < joints.size(); i++) {
                Integer at = joints.get(i) instanceof Number n ? mapped.get(n.intValue()) : null;
                nodes[i] = at == null ? -1 : at;
            }
            double[][] binds = new double[joints.size()][];
            double[] flat = accessor(WatchJson.integer(skin, "inverseBindMatrices", -1));
            for (int i = 0; i < joints.size(); i++) {
                if (flat != null && (i + 1) * 16 <= flat.length) {
                    double[] row = new double[16];
                    for (int c = 0; c < 4; c++) {
                        for (int r = 0; r < 4; r++) row[r * 4 + c] = flat[i * 16 + c * 4 + r];
                    }
                    binds[i] = row;
                } else {
                    binds[i] = RawModel.identity();
                }
            }
            return new Skin(nodes, binds);
        }

        private record Skin(int[] nodes, double[][] binds) {}

        private void primitive(Map<String, Object> primitive, int meshNode, Skin skin,
                               Map<Integer, Group> groups) {
            if (WatchJson.integer(primitive, "mode", MODE_TRIANGLES) != MODE_TRIANGLES) return;
            Map<String, Object> attributes = WatchJson.map(primitive, "attributes");
            double[] positions = accessor(WatchJson.integer(attributes, "POSITION", -1));
            if (positions == null || positions.length < 9) return;

            int[] indices = indices(WatchJson.integer(primitive, "indices", -1),
                    positions.length / 3);
            double[] joints = accessor(WatchJson.integer(attributes, "JOINTS_0", -1));
            double[] weights = accessor(WatchJson.integer(attributes, "WEIGHTS_0", -1));
            double[] tint = accessor(WatchJson.integer(attributes, "COLOR_0", -1));
            int tintStride = tint == null ? 0 : components(WatchJson.integer(
                    attributes, "COLOR_0", -1));
            double[] base = colourOf(WatchJson.integer(primitive, "material", -1));

            double[] point = new double[3];
            for (int t = 0; t + 2 < indices.length; t += 3) {
                int a = indices[t], b = indices[t + 1], c = indices[t + 2];
                if (!inRange(a, positions) || !inRange(b, positions)
                        || !inRange(c, positions)) {
                    continue;
                }
                int node = meshNode;
                double[] bind = null;
                if (skin != null && joints != null && weights != null) {
                    int dominant = dominant(joints, weights, a, b, c, skin.nodes().length);
                    if (dominant >= 0 && skin.nodes()[dominant] >= 0) {
                        node = skin.nodes()[dominant];
                        bind = skin.binds()[dominant];
                    }
                }
                int colour = shade(base, tint, tintStride, a, b, c);
                Group group = groups.computeIfAbsent(node, k -> new Group());
                group.begin();
                for (int corner : new int[]{a, b, c}) {
                    double x = positions[corner * 3];
                    double y = positions[corner * 3 + 1];
                    double z = positions[corner * 3 + 2];
                    if (bind != null) {
                        RawModel.transform(bind, x, y, z, point);
                        x = point[0]; y = point[1]; z = point[2];
                    }
                    group.corner(x, y, z);
                }
                group.end(colour);
            }
        }

        private static boolean inRange(int vertex, double[] positions) {
            return vertex >= 0 && (vertex + 1) * 3 <= positions.length;
        }

        /**
         * The joint a triangle belongs to: the one with the most weight summed
         * over its three corners.
         *
         * <p>Summing rather than taking any one corner's best is what keeps a
         * triangle that straddles a joint with the bone that owns most of it,
         * so a seam falls where the weights already said the limb divides.
         */
        private static int dominant(double[] joints, double[] weights, int a, int b, int c,
                                    int count) {
            double[] total = new double[count];
            for (int corner : new int[]{a, b, c}) {
                for (int i = 0; i < 4; i++) {
                    int at = corner * 4 + i;
                    if (at >= joints.length || at >= weights.length) break;
                    int joint = (int) Math.round(joints[at]);
                    if (joint >= 0 && joint < count) total[joint] += weights[at];
                }
            }
            int best = -1;
            double most = 0;
            for (int i = 0; i < count; i++) {
                if (total[i] > most) { most = total[i]; best = i; }
            }
            return best;
        }

        /**
         * The material's colour, tinted by any vertex colours the triangle
         * carries, converted out of linear once at the end.
         *
         * <p>The multiply happens in linear because that is the space both
         * numbers are in and the space a tint means something in; converting
         * either one first and multiplying sRGB by sRGB darkens everything
         * that is painted twice.
         */
        private static int shade(double[] base, double[] tint, int stride,
                                 int a, int b, int c) {
            double r = base[0], g = base[1], bl = base[2];
            if (tint != null && stride >= 3) {
                double tr = 0, tg = 0, tb = 0;
                int found = 0;
                for (int corner : new int[]{a, b, c}) {
                    int at = corner * stride;
                    if (at + 2 >= tint.length) continue;
                    tr += tint[at];
                    tg += tint[at + 1];
                    tb += tint[at + 2];
                    found++;
                }
                if (found > 0) {
                    r *= tr / found;
                    g *= tg / found;
                    bl *= tb / found;
                }
            }
            return ObjReader.packLinear(r, g, bl);
        }

        /**
         * A material's base colour as linear {@code r, g, b}.
         *
         * <p>A primitive naming no material at all gets the neutral grey the
         * OBJ reader uses, so an untextured test export is visible rather than
         * white-on-white; one that names a material but sets no
         * {@code baseColorFactor} gets glTF's own default, which is white.
         */
        private double[] colourOf(int index) {
            if (index < 0 || index >= materials.size()) {
                return new double[]{
                        ObjReader.linear((ObjReader.DEFAULT_COLOUR >> 16) & 0xFF),
                        ObjReader.linear((ObjReader.DEFAULT_COLOUR >> 8) & 0xFF),
                        ObjReader.linear(ObjReader.DEFAULT_COLOUR & 0xFF)};
            }
            Map<String, Object> pbr =
                    WatchJson.map(materials.get(index), "pbrMetallicRoughness");
            List<Object> factor = WatchJson.list(pbr, "baseColorFactor");
            if (factor.size() < 3) return new double[]{1, 1, 1};
            return new double[]{number(factor.get(0)), number(factor.get(1)),
                    number(factor.get(2))};
        }

        /** A primitive's index list, or {@code 0..count} when it is not indexed. */
        private int[] indices(int accessor, int count) {
            double[] read = accessor(accessor);
            if (read == null) {
                int[] sequence = new int[count];
                for (int i = 0; i < count; i++) sequence[i] = i;
                return sequence;
            }
            int[] out = new int[read.length];
            for (int i = 0; i < read.length; i++) out[i] = (int) Math.round(read[i]);
            return out;
        }

        // --- animation -----------------------------------------------------------

        private void animations() {
            for (Map<String, Object> animation : WatchJson.objects(json, "animations")) {
                List<Map<String, Object>> samplers =
                        WatchJson.objects(animation, "samplers");
                List<RawModel.Track> tracks = new ArrayList<>();
                double length = 0;
                for (Map<String, Object> channel : WatchJson.objects(animation, "channels")) {
                    int at = WatchJson.integer(channel, "sampler", -1);
                    if (at < 0 || at >= samplers.size()) continue;
                    Map<String, Object> target = WatchJson.map(channel, "target");
                    Integer node = mapped.get(WatchJson.integer(target, "node", -1));
                    if (node == null) continue;
                    RawModel.Channel path = switch (WatchJson.str(target, "path", "")) {
                        case "translation" -> RawModel.Channel.TRANSLATION;
                        case "rotation" -> RawModel.Channel.ROTATION;
                        case "scale" -> RawModel.Channel.SCALE;
                        // "weights" is morph targets, which this world has no
                        // shape for — see the class note.
                        default -> null;
                    };
                    if (path == null) continue;

                    Map<String, Object> sampler = samplers.get(at);
                    double[] times = accessor(WatchJson.integer(sampler, "input", -1));
                    double[] values = accessor(WatchJson.integer(sampler, "output", -1));
                    if (times == null || values == null || times.length == 0) continue;
                    RawModel.Interpolation interpolation = switch (
                            WatchJson.str(sampler, "interpolation", "LINEAR")) {
                        case "STEP" -> RawModel.Interpolation.STEP;
                        case "CUBICSPLINE" -> RawModel.Interpolation.CUBIC;
                        default -> RawModel.Interpolation.LINEAR;
                    };
                    int stride = path == RawModel.Channel.ROTATION ? 4 : 3;
                    values = flatten(values, times.length, stride, interpolation);
                    if (values == null) continue;
                    length = Math.max(length, times[times.length - 1]);
                    tracks.add(new RawModel.Track(node, path, interpolation, times, values));
                }
                if (!tracks.isEmpty()) {
                    model.clip(new RawModel.Clip(WatchJson.str(animation, "name", ""),
                            Math.max(0.05, length), true, tracks));
                }
            }
        }

        /**
         * A sampler's output, one tuple per keyframe.
         *
         * <p>A cubic-spline sampler writes three tuples per key — an in
         * tangent, the value, an out tangent — and only the middle one survives
         * here. See {@link RawModel.Interpolation#CUBIC}.
         */
        private static double[] flatten(double[] values, int keys, int stride,
                                        RawModel.Interpolation interpolation) {
            if (interpolation != RawModel.Interpolation.CUBIC) {
                return values.length >= keys * stride ? values : null;
            }
            if (values.length < keys * stride * 3) return null;
            double[] out = new double[keys * stride];
            for (int k = 0; k < keys; k++) {
                System.arraycopy(values, (k * 3 + 1) * stride, out, k * stride, stride);
            }
            return out;
        }

        // --- buffers and accessors -------------------------------------------------

        private byte[][] readBuffers() {
            List<Map<String, Object>> declared = WatchJson.objects(json, "buffers");
            byte[][] out = new byte[declared.size()][];
            for (int i = 0; i < declared.size(); i++) {
                String uri = WatchJson.str(declared.get(i), "uri", null);
                if (uri == null) {
                    // No URI means the GLB's own binary chunk, which is only
                    // ever buffer zero.
                    out[i] = i == 0 ? glbBinary : null;
                } else if (uri.startsWith("data:")) {
                    int comma = uri.indexOf(',');
                    try {
                        out[i] = comma < 0 ? null
                                : Base64.getDecoder().decode(uri.substring(comma + 1));
                    } catch (IllegalArgumentException e) {
                        out[i] = null;
                    }
                } else if (external != null) {
                    out[i] = external.read(uri);
                }
            }
            return out;
        }

        /** How many numbers one element of an accessor has. */
        private int components(int index) {
            if (index < 0 || index >= accessors.size()) return 0;
            return sizeOf(WatchJson.str(accessors.get(index), "type", "SCALAR"));
        }

        /**
         * An accessor's contents, flattened and un-normalised.
         *
         * <p>Interleaved buffer views are honoured through {@code byteStride},
         * which Blender writes for the vertex attributes it packs together.
         * Anything out of bounds — a stride that runs off the end, a view a
         * buffer never arrived for — comes back {@code null} rather than
         * throwing, so a half-downloaded model is a model that does not load.
         */
        private double[] accessor(int index) {
            if (index < 0 || index >= accessors.size()) return null;
            Map<String, Object> accessor = accessors.get(index);
            int count = WatchJson.integer(accessor, "count", 0);
            int size = sizeOf(WatchJson.str(accessor, "type", "SCALAR"));
            int component = WatchJson.integer(accessor, "componentType", 0);
            int width = widthOf(component);
            if (count <= 0 || size == 0 || width == 0) return null;

            int view = WatchJson.integer(accessor, "bufferView", -1);
            if (view < 0 || view >= views.size()) return new double[count * size];
            Map<String, Object> bufferView = views.get(view);
            int buffer = WatchJson.integer(bufferView, "buffer", -1);
            if (buffer < 0 || buffer >= buffers.length || buffers[buffer] == null) return null;
            byte[] bytes = buffers[buffer];

            int viewOffset = WatchJson.integer(bufferView, "byteOffset", 0);
            int stride = WatchJson.integer(bufferView, "byteStride", 0);
            if (stride <= 0) stride = size * width;
            int start = viewOffset + WatchJson.integer(accessor, "byteOffset", 0);
            if (start < 0 || start + (long) (count - 1) * stride + (long) size * width
                    > bytes.length) {
                return null;
            }

            boolean normalised = WatchJson.bool(accessor, "normalized", false);
            ByteBuffer in = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            double[] out = new double[count * size];
            for (int i = 0; i < count; i++) {
                int at = start + i * stride;
                for (int c = 0; c < size; c++) {
                    out[i * size + c] = value(in, at + c * width, component, normalised);
                }
            }
            return out;
        }

        private static double value(ByteBuffer in, int at, int component, boolean normalised) {
            return switch (component) {
                case 5120 -> normalised
                        ? Math.max(-1, in.get(at) / 127.0) : in.get(at);
                case 5121 -> normalised
                        ? (in.get(at) & 0xFF) / 255.0 : in.get(at) & 0xFF;
                case 5122 -> normalised
                        ? Math.max(-1, in.getShort(at) / 32767.0) : in.getShort(at);
                case 5123 -> normalised
                        ? (in.getShort(at) & 0xFFFF) / 65535.0 : in.getShort(at) & 0xFFFF;
                case 5125 -> in.getInt(at) & 0xFFFFFFFFL;
                case 5126 -> in.getFloat(at);
                default -> 0;
            };
        }

        private static int sizeOf(String type) {
            return switch (type) {
                case "SCALAR" -> 1;
                case "VEC2" -> 2;
                case "VEC3" -> 3;
                case "VEC4", "MAT2" -> 4;
                case "MAT3" -> 9;
                case "MAT4" -> 16;
                default -> 0;
            };
        }

        private static int widthOf(int componentType) {
            return switch (componentType) {
                case 5120, 5121 -> 1;
                case 5122, 5123 -> 2;
                case 5125, 5126 -> 4;
                default -> 0;
            };
        }

        private double[] triple(Map<String, Object> from, String key,
                                double x, double y, double z) {
            List<Object> list = WatchJson.list(from, key);
            if (list.size() < 3) return new double[]{x, y, z};
            return new double[]{number(list.get(0)), number(list.get(1)),
                    number(list.get(2))};
        }

        private static double length(double x, double y, double z) {
            return Math.sqrt(x * x + y * y + z * z);
        }
    }

    /** Triangles gathered for one node. */
    private static final class Group {
        private float[] positions = new float[9 * 64];
        private int[] colours = new int[64];
        private int count;
        private int corner;

        void begin() {
            if (count == colours.length) {
                positions = java.util.Arrays.copyOf(positions, positions.length * 2);
                colours = java.util.Arrays.copyOf(colours, colours.length * 2);
            }
            corner = 0;
        }

        void corner(double x, double y, double z) {
            int at = count * 9 + corner * 3;
            positions[at] = (float) x;
            positions[at + 1] = (float) y;
            positions[at + 2] = (float) z;
            corner++;
        }

        void end(int colour) {
            colours[count] = colour;
            count++;
        }

        RawModel.Piece piece(int node) {
            return new RawModel.Piece(node, java.util.Arrays.copyOf(positions, count * 9),
                    java.util.Arrays.copyOf(colours, count));
        }
    }

    // --- plumbing --------------------------------------------------------------------

    private static double number(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0;
    }

    /** Percent-decode a buffer URI, which is how a filename with a space arrives. */
    private static String decode(String uri) {
        if (uri.indexOf('%') < 0) return uri;
        StringBuilder out = new StringBuilder(uri.length());
        for (int i = 0; i < uri.length(); i++) {
            char c = uri.charAt(i);
            if (c == '%' && i + 2 < uri.length()) {
                try {
                    out.append((char) Integer.parseInt(uri.substring(i + 1, i + 3), 16));
                    i += 2;
                    continue;
                } catch (NumberFormatException e) {
                    // Not an escape after all; fall through and keep the '%'.
                }
            }
            out.append(c);
        }
        return out.toString();
    }

    private static byte[] resource(String path) throws IOException {
        try (var in = GltfReader.class.getClassLoader().getResourceAsStream(path)) {
            return in == null ? null : in.readAllBytes();
        }
    }
}
