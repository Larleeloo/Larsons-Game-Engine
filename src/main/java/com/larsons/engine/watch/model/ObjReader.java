package com.larsons.engine.watch.model;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads Wavefront {@code .obj} — <b>the format every tool on earth can write.</b>
 *
 * <p>It is text, it has no version, and it is four keywords long. That is the
 * whole argument for it: a prop modelled in anything at all can be dropped into
 * this game without a converter, and the file can be read in a diff. What it
 * cannot carry is animation, so a character wants {@link GltfReader} instead —
 * see the folder README.
 *
 * <h2>What is read</h2>
 *
 * <ul>
 *   <li>{@code v} — vertices. {@code w} on the end is ignored.</li>
 *   <li>{@code f} — faces, of any length, fanned into triangles. Indices may be
 *       negative, which counts back from the end as the format says.</li>
 *   <li>{@code o} / {@code g} — objects and groups, each becoming a node. Their
 *       <b>names bind them to joints</b> exactly as a glTF bone's does, so an
 *       OBJ whose groups are called {@code head} and {@code leg_l} is posed by
 *       the procedural animation rather than standing still.</li>
 *   <li>{@code usemtl} and {@code mtllib} — the material's {@code Kd}, which is
 *       where a triangle's colour comes from.</li>
 * </ul>
 *
 * <h2>What is not</h2>
 *
 * <p>Normals ({@code vn}) — every facet here is lit by its own winding, so a
 * file's normals could only disagree with what is drawn. Texture coordinates
 * ({@code vt}) — see {@link RawModel} for why a UV has nowhere to go in a world
 * that atlases everything. Smoothing groups, curves, and free-form surfaces.
 * None of these stop a file loading.
 */
public final class ObjReader {

    /** What a triangle is painted when its file names no material. */
    static final int DEFAULT_COLOUR = 0xB4B0A6;

    private ObjReader() {}

    /** Read an {@code .obj} from disk, resolving any {@code mtllib} beside it. */
    public static RawModel load(Path file) throws IOException {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        Path folder = file.getParent();
        return parse(text, file.getFileName().toString(),
                name -> readSibling(folder, name));
    }

    /**
     * Read one from the classpath.
     *
     * <p>A {@code mtllib} is resolved as a sibling resource, so a bundled prop
     * can ship its own colours next to it.
     */
    public static RawModel loadResource(String resourcePath) throws IOException {
        byte[] bytes = resource(resourcePath);
        if (bytes == null) return null;
        int slash = resourcePath.lastIndexOf('/');
        String folder = slash < 0 ? "" : resourcePath.substring(0, slash + 1);
        return parse(new String(bytes, StandardCharsets.UTF_8), resourcePath, name -> {
            try {
                byte[] mtl = resource(folder + name);
                return mtl == null ? null : new String(mtl, StandardCharsets.UTF_8);
            } catch (IOException | RuntimeException e) {
                return null;
            }
        });
    }

    /** Where a {@code mtllib} line's text comes from; {@code null} when there is none. */
    public interface Materials {
        String read(String name);
    }

    /**
     * Parse OBJ text.
     *
     * @return the model, or {@code null} when there are no triangles in it — a
     *         file that is only vertices, or only comments, is not a model
     */
    public static RawModel parse(String text, String name, Materials materials) {
        RawModel.Builder model = new RawModel.Builder(name);
        int root = model.node(RawModel.Node.identity("root", -1));

        List<double[]> vertices = new ArrayList<>();
        Map<String, Integer> palette = new HashMap<>();
        // Triangles accumulate per group, because a Piece is per node and a
        // file is free to come back to a group it had left.
        Map<Integer, Group> groups = new HashMap<>();
        Group current = groups.computeIfAbsent(root, k -> new Group());
        int colour = DEFAULT_COLOUR;

        for (String raw : text.split("\\R")) {
            String line = strip(raw);
            if (line.isEmpty()) continue;
            String[] word = line.split("\\s+");
            switch (word[0]) {
                case "v" -> {
                    if (word.length >= 4) {
                        vertices.add(new double[]{number(word[1]), number(word[2]),
                                number(word[3])});
                    }
                }
                case "o", "g" -> {
                    String groupName = word.length > 1
                            ? line.substring(word[0].length()).trim() : "group";
                    int node = model.node(RawModel.Node.identity(groupName, root));
                    current = groups.computeIfAbsent(node, k -> new Group());
                }
                case "mtllib" -> {
                    if (word.length > 1 && materials != null) {
                        String mtl = materials.read(line.substring(word[0].length()).trim());
                        if (mtl != null) readMaterials(mtl, palette);
                    }
                }
                case "usemtl" -> {
                    String material = word.length > 1
                            ? line.substring(word[0].length()).trim() : "";
                    colour = palette.getOrDefault(material, DEFAULT_COLOUR);
                }
                case "f" -> face(word, vertices, current, colour);
                default -> { /* vn, vt, s, and anything else: not ours. */ }
            }
        }

        for (Map.Entry<Integer, Group> entry : groups.entrySet()) {
            model.piece(entry.getValue().piece(entry.getKey()));
        }
        RawModel built = model.build();
        return built.empty() ? null : built;
    }

    /**
     * One face, fanned into triangles about its first corner.
     *
     * <p>A fan is right for a convex polygon and wrong for a concave one, which
     * is the trade every OBJ reader makes: the alternative is an ear-clipping
     * triangulator for a case that a modelling tool asked to export triangles
     * never produces. See the README's budget note — exporting triangulated is
     * the recommendation anyway.
     */
    private static void face(String[] word, List<double[]> vertices, Group into,
                             int colour) {
        int corners = word.length - 1;
        if (corners < 3) return;
        double[][] point = new double[corners][];
        for (int i = 0; i < corners; i++) {
            int index = index(word[i + 1], vertices.size());
            if (index < 0) return;
            point[i] = vertices.get(index);
        }
        for (int i = 1; i + 1 < corners; i++) {
            into.triangle(point[0], point[i], point[i + 1], colour);
        }
    }

    /** The vertex a {@code v/vt/vn} corner names, zero-based, or {@code -1}. */
    private static int index(String corner, int count) {
        int slash = corner.indexOf('/');
        String first = slash < 0 ? corner : corner.substring(0, slash);
        int at;
        try {
            at = Integer.parseInt(first.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
        // OBJ is one-based, and a negative index counts back from the most
        // recent vertex — which is how a file written in pieces refers to its
        // own last few without knowing where it is.
        int resolved = at > 0 ? at - 1 : count + at;
        return resolved >= 0 && resolved < count ? resolved : -1;
    }

    /** Every {@code newmtl}'s diffuse colour, packed. */
    private static void readMaterials(String text, Map<String, Integer> into) {
        String current = null;
        for (String raw : text.split("\\R")) {
            String line = strip(raw);
            if (line.isEmpty()) continue;
            String[] word = line.split("\\s+");
            if ("newmtl".equals(word[0]) && word.length > 1) {
                current = line.substring(word[0].length()).trim();
                into.putIfAbsent(current, DEFAULT_COLOUR);
            } else if ("Kd".equals(word[0]) && word.length >= 4 && current != null) {
                into.put(current,
                        packLinear(number(word[1]), number(word[2]), number(word[3])));
            }
        }
    }

    /** Triangles gathered for one node, grown as they arrive. */
    private static final class Group {
        private float[] positions = new float[9 * 64];
        private int[] colours = new int[64];
        private int count;

        void triangle(double[] a, double[] b, double[] c, int colour) {
            if (count == colours.length) {
                positions = java.util.Arrays.copyOf(positions, positions.length * 2);
                colours = java.util.Arrays.copyOf(colours, colours.length * 2);
            }
            int at = count * 9;
            write(positions, at, a);
            write(positions, at + 3, b);
            write(positions, at + 6, c);
            colours[count] = colour;
            count++;
        }

        private static void write(float[] into, int at, double[] point) {
            into[at] = (float) point[0];
            into[at + 1] = (float) point[1];
            into[at + 2] = (float) point[2];
        }

        RawModel.Piece piece(int node) {
            return new RawModel.Piece(node, java.util.Arrays.copyOf(positions, count * 9),
                    java.util.Arrays.copyOf(colours, count));
        }
    }

    // --- plumbing --------------------------------------------------------------------

    private static String strip(String line) {
        int hash = line.indexOf('#');
        return (hash < 0 ? line : line.substring(0, hash)).trim();
    }

    private static double number(String text) {
        try {
            return Double.parseDouble(text.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * A <b>linear</b> {@code 0..1} colour to a packed sRGB {@code 0xRRGGBB}.
     *
     * <p><b>This conversion is not optional, and leaving it out is invisible in
     * a unit test and obvious in the game.</b> glTF states that
     * {@code baseColorFactor} and {@code COLOR_0} are linear, and Blender writes
     * both that way: type {@code 3C5240} into its colour picker and what reaches
     * the file is {@code 0.045}, not {@code 0.235}. Every colour this game holds
     * — {@code KeeperModel}'s coats, the terrain materials, a species' skin —
     * is an sRGB value used as-is. Packing the file's number straight through
     * would land an imported model four stops under everything standing next to
     * it, which reads as "the new model is broken" rather than as a colour
     * space mistake.
     *
     * <p>OBJ's {@code Kd} has no declared colour space. It is treated as linear
     * too, because the tool that writes the ones this folder will see is
     * Blender, and Blender writes the same linear value there.
     */
    static int packLinear(double r, double g, double b) {
        return (srgb(r) << 16) | (srgb(g) << 8) | srgb(b);
    }

    private static int srgb(double v) {
        double c = Math.max(0, Math.min(1, v));
        double s = c <= 0.0031308 ? c * 12.92 : 1.055 * Math.pow(c, 1 / 2.4) - 0.055;
        return Math.max(0, Math.min(255, (int) Math.round(s * 255)));
    }

    /** The linear value a packed sRGB channel came from — {@link #packLinear} back. */
    static double linear(int channel) {
        double c = channel / 255.0;
        return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    private static String readSibling(Path folder, String name) {
        if (folder == null) return null;
        try {
            Path file = folder.resolve(name);
            return Files.isReadable(file)
                    ? Files.readString(file, StandardCharsets.UTF_8) : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static byte[] resource(String path) throws IOException {
        try (var in = ObjReader.class.getClassLoader().getResourceAsStream(path)) {
            return in == null ? null : in.readAllBytes();
        }
    }
}
