package com.larsons.engine.watch.life;

import com.larsons.engine.util.Json;
import com.larsons.engine.watch.WatchJson;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads a Blockbench model — <b>the path the real art arrives by.</b>
 *
 * <p>Every species in this game ships with a procedural placeholder
 * ({@link AnimalModel}). This class is how one of them is replaced: drop a
 * {@code .bbmodel} (or a Blockbench-exported {@code .json}) into
 * {@code resources/watch/models/} named after the species or its family, and it
 * is loaded in place of the placeholder — same renderer, same animation states,
 * same everything downstream. See that folder's {@code README.md} for the
 * conventions, which this class defines and that file explains.
 *
 * <h2>What is read</h2>
 *
 * <ul>
 *   <li><b>{@code resolution}</b> — the texture's size, so face UVs can be
 *       normalised.</li>
 *   <li><b>{@code elements}</b> — each cuboid's {@code from}/{@code to} corners
 *       in Blockbench's pixel space, its {@code inflate}, its {@code origin}
 *       (the pivot it rotates about) and its own {@code rotation}.</li>
 *   <li><b>{@code outliner}</b> — the bone tree. A bone's <b>name</b> is what
 *       binds its elements to one of this game's {@link AnimalModel.Joint}s
 *       ({@link #jointOf}), which is the one convention an artist has to
 *       follow.</li>
 *   <li><b>{@code animations}</b> — each clip's name is matched to an
 *       {@link AnimState} by {@link AnimState#forClip}, and its per-bone
 *       {@code rotation} and {@code position} channels are kept as keyframes
 *       with their interpolation mode.</li>
 *   <li><b>{@code textures}</b> — the first embedded texture's base64 data, so
 *       a model can carry its own skin.</li>
 * </ul>
 *
 * <h2>What is deliberately not read</h2>
 *
 * <p>Meshes (Blockbench's free-form polygon mode), {@code scale} channels,
 * molang expressions in keyframe data, and per-face texture assignment beyond
 * the first texture. This game draws boxes, and a model that is not boxes has
 * nowhere to go in a low-poly world built out of them. A file containing them
 * still loads — the parts it cannot use are skipped, not fatal.
 *
 * <h2>Units</h2>
 *
 * <p>Blockbench works in pixels where sixteen make a Minecraft block. A model
 * is normalised on import so that its longest horizontal extent is <b>one body
 * length</b>, exactly like the placeholders, which is what lets one file serve
 * a species whose real size the registry decides.
 */
public final class Blockbench {

    /** Blockbench pixels per Minecraft block. Only used for the default scale. */
    public static final double PIXELS_PER_BLOCK = 16;

    private Blockbench() {}

    /** One keyframe of one channel. */
    public record Keyframe(double time, double x, double y, double z, boolean smooth) {}

    /** One bone's animation on one channel within one clip. */
    public record Channel(AnimalModel.Joint joint, boolean rotation,
                          List<Keyframe> frames) {

        /**
         * The channel's value at a time, written into {@code out} as
         * {@code x, y, z}.
         *
         * <p>Linear between keyframes, or a Catmull-Rom pass through them when
         * the frame asks for it — the two modes Blockbench actually writes.
         * Before the first frame and after the last it holds, which is what a
         * non-looping clip should do at its ends.
         */
        public void sample(double time, double[] out) {
            if (frames.isEmpty()) {
                out[0] = out[1] = out[2] = 0;
                return;
            }
            if (time <= frames.get(0).time()) {
                write(frames.get(0), out);
                return;
            }
            Keyframe last = frames.get(frames.size() - 1);
            if (time >= last.time()) {
                write(last, out);
                return;
            }
            int i = 0;
            while (i + 1 < frames.size() && frames.get(i + 1).time() < time) i++;
            Keyframe a = frames.get(i);
            Keyframe b = frames.get(Math.min(frames.size() - 1, i + 1));
            double span = Math.max(1e-6, b.time() - a.time());
            double t = (time - a.time()) / span;
            if (b.smooth()) {
                // Smoothstep rather than a true four-point spline: it is the
                // same at the keyframes, has the same zero derivative there,
                // and cannot overshoot into a limb bending backwards — which a
                // real Catmull-Rom through hand-set keys frequently does.
                t = t * t * (3 - 2 * t);
            }
            out[0] = a.x() + (b.x() - a.x()) * t;
            out[1] = a.y() + (b.y() - a.y()) * t;
            out[2] = a.z() + (b.z() - a.z()) * t;
        }

        private static void write(Keyframe f, double[] out) {
            out[0] = f.x();
            out[1] = f.y();
            out[2] = f.z();
        }
    }

    /** One animation: a length, and the channels that play over it. */
    public record Clip(String name, double length, boolean loop, List<Channel> channels) {}

    /**
     * A loaded model: its boxes, its clips, and its own texture if it brought
     * one. Implements {@link AnimalModel.PoseSource}, so it can be handed
     * straight to {@link AnimalModel#mesh} in place of the procedural poses.
     */
    public static final class Model implements AnimalModel.PoseSource {

        private final AnimalModel geometry;
        private final Map<AnimState, Clip> clips;
        private final byte[] texture;
        private final String name;

        Model(String name, AnimalModel geometry, Map<AnimState, Clip> clips, byte[] texture) {
            this.name = name;
            this.geometry = geometry;
            this.clips = clips;
            this.texture = texture;
        }

        /** What the file called itself. */
        public String name() { return name; }

        /** The boxes, ready to draw. */
        public AnimalModel geometry() { return geometry; }

        /** Which animation states this model actually supplies. */
        public Map<AnimState, Clip> clips() { return clips; }

        /** Whether this model brought its own skin. */
        public boolean hasTexture() { return texture != null && texture.length > 0; }

        /** The embedded skin's PNG bytes, or {@code null}. */
        public byte[] texture() { return texture == null ? null : texture.clone(); }

        /**
         * The pose for a joint at a phase.
         *
         * <p><b>Falls back rather than failing.</b> A model with no clip for
         * this state is posed by the procedural animation instead, so an
         * artist who has done {@code idle} and {@code walk} gets a working
         * animal with seven states still moving, and finishing the set is an
         * improvement rather than a prerequisite.
         */
        @Override
        public AnimalModel.Pose poseOf(AnimState state, AnimalModel.Joint joint,
                                       double phase) {
            Clip clip = clips.get(state);
            if (clip == null) return AnimalModel.pose(state, joint, phase);
            double time = phase * clip.length();
            double[] rotation = new double[3];
            double[] position = new double[3];
            boolean found = false;
            for (Channel channel : clip.channels()) {
                if (channel.joint() != joint) continue;
                found = true;
                channel.sample(time, channel.rotation() ? rotation : position);
            }
            if (!found) return AnimalModel.Pose.REST;
            // Blockbench writes degrees, and its rotation channel is
            // (x = pitch about the model's left-right axis, y = turn about the
            // vertical, z = roll about the front-back axis) — the same three
            // hinges this game's Pose has, in the same order.
            //
            // Its *position* channel is in its own axes, which are not ours:
            // Blockbench has x to the right, y up and z toward the front, while
            // this game has x forward, y right and z up. So the three
            // components are re-ordered rather than copied. Getting this wrong
            // is a limb that slides sideways when the animation meant forward,
            // which is subtle enough to ship, so it is spelled out here.
            return AnimalModel.Pose.full(
                    Math.toRadians(rotation[0]), Math.toRadians(rotation[1]),
                    Math.toRadians(rotation[2]),
                    position[2] / PIXELS_PER_BLOCK, position[0] / PIXELS_PER_BLOCK,
                    position[1] / PIXELS_PER_BLOCK);
        }
    }

    // --- loading ---------------------------------------------------------------------

    /** Load a {@code .bbmodel} from disk. */
    public static Model load(Path file) throws IOException {
        return parse(Files.readString(file, StandardCharsets.UTF_8),
                file.getFileName().toString());
    }

    /** Load one from the classpath — where a bundled model lives. */
    public static Model loadResource(String resourcePath) throws IOException {
        try (InputStream in = Blockbench.class.getClassLoader()
                .getResourceAsStream(resourcePath)) {
            if (in == null) return null;
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8), resourcePath);
        }
    }

    /**
     * Parse a model from its JSON text.
     *
     * @return the model, or {@code null} when the text is not a Blockbench file
     *         — a malformed drop-in is a species that keeps its placeholder,
     *         not a game that will not start
     */
    public static Model parse(String json, String name) {
        Map<String, Object> root;
        try {
            Object parsed = Json.parse(json);
            if (!(parsed instanceof Map<?, ?>)) return null;
            root = Json.asObject(parsed);
        } catch (RuntimeException e) {
            return null;
        }
        List<Map<String, Object>> elements = WatchJson.objects(root, "elements");
        if (elements.isEmpty()) return null;

        Map<Integer, AnimalModel.Joint> elementJoint = new LinkedHashMap<>();
        Map<String, AnimalModel.Joint> boneJoint = new LinkedHashMap<>();
        readOutliner(WatchJson.list(root, "outliner"), elements, elementJoint, boneJoint,
                null);

        // Pass one: measure, so the model can be normalised to one body length.
        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
        double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        double minZ = Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        for (Map<String, Object> element : elements) {
            double[] from = triple(element, "from");
            double[] to = triple(element, "to");
            double inflate = WatchJson.num(element, "inflate", 0);
            minX = Math.min(minX, Math.min(from[0], to[0]) - inflate);
            maxX = Math.max(maxX, Math.max(from[0], to[0]) + inflate);
            minY = Math.min(minY, Math.min(from[1], to[1]) - inflate);
            maxY = Math.max(maxY, Math.max(from[1], to[1]) + inflate);
            minZ = Math.min(minZ, Math.min(from[2], to[2]) - inflate);
            maxZ = Math.max(maxZ, Math.max(from[2], to[2]) + inflate);
        }
        // Blockbench: x is left-right, y is up, z is front-back. This game:
        // x is forward, y is right, z is up. The longest horizontal extent
        // becomes one body length.
        double extent = Math.max(1e-6, Math.max(maxZ - minZ, maxX - minX));
        double scale = 1.0 / extent;
        double floor = minY;

        List<AnimalModel.Part> parts = new ArrayList<>();
        for (int i = 0; i < elements.size(); i++) {
            Map<String, Object> element = elements.get(i);
            double[] from = triple(element, "from");
            double[] to = triple(element, "to");
            double inflate = WatchJson.num(element, "inflate", 0);
            double[] origin = element.containsKey("origin")
                    ? triple(element, "origin")
                    : new double[]{(from[0] + to[0]) / 2, (from[1] + to[1]) / 2,
                            (from[2] + to[2]) / 2};

            double hx = (Math.abs(to[2] - from[2]) / 2 + inflate) * scale;
            double hy = (Math.abs(to[0] - from[0]) / 2 + inflate) * scale;
            double hz = (Math.abs(to[1] - from[1]) / 2 + inflate) * scale;
            double centreForward = ((from[2] + to[2]) / 2) * scale;
            double centreRight = ((from[0] + to[0]) / 2) * scale;
            double centreUp = ((from[1] + to[1]) / 2 - floor) * scale;
            double pivotForward = origin[2] * scale;
            double pivotRight = origin[0] * scale;
            double pivotUp = (origin[1] - floor) * scale;

            AnimalModel.Joint joint = elementJoint.getOrDefault(i, AnimalModel.Joint.BODY);
            parts.add(new AnimalModel.Part(joint,
                    pivotForward, pivotRight, pivotUp,
                    centreForward - pivotForward, centreRight - pivotRight,
                    centreUp - pivotUp,
                    hx, hy, hz, regionOf(joint)));
        }

        Map<AnimState, Clip> clips = readAnimations(root, boneJoint);
        byte[] texture = readTexture(root);
        double standHeight = (maxY - floor) * scale * 0.5;
        return new Model(name, AnimalModel.imported(parts, standHeight), clips, texture);
    }

    /**
     * Walk the bone tree, recording which joint each element belongs to.
     *
     * <p>Blockbench's outliner mixes two kinds of entry in one array: an
     * integer (or a uuid string) is an element, and an object is a bone with
     * children. A bone inherits its parent's joint unless its own name names a
     * different one, which is what lets {@code head → beak} work without the
     * artist having to name the beak.
     */
    private static void readOutliner(List<Object> nodes,
                                     List<Map<String, Object>> elements,
                                     Map<Integer, AnimalModel.Joint> elementJoint,
                                     Map<String, AnimalModel.Joint> boneJoint,
                                     AnimalModel.Joint inherited) {
        for (Object node : nodes) {
            if (node instanceof Map<?, ?>) {
                Map<String, Object> bone = Json.asObject(node);
                String name = WatchJson.str(bone, "name", "");
                AnimalModel.Joint joint = jointOf(name);
                if (joint == null) joint = inherited;
                if (joint == null) joint = AnimalModel.Joint.BODY;
                boneJoint.put(name.toLowerCase(), joint);
                readOutliner(WatchJson.list(bone, "children"), elements, elementJoint,
                        boneJoint, joint);
            } else {
                int index = indexOf(node, elements);
                if (index >= 0 && inherited != null) elementJoint.put(index, inherited);
            }
        }
    }

    /** Resolve an outliner leaf — an index, or a uuid to be looked up. */
    private static int indexOf(Object node, List<Map<String, Object>> elements) {
        if (node instanceof Number n) return n.intValue();
        if (node instanceof String uuid) {
            for (int i = 0; i < elements.size(); i++) {
                if (uuid.equals(WatchJson.str(elements.get(i), "uuid", null))) return i;
            }
        }
        return -1;
    }

    /**
     * The joint a bone name means.
     *
     * <p><b>This is the convention an artist has to follow</b>, and it is the
     * only one. Names are matched case-insensitively on substrings, so
     * {@code leftWing}, {@code wing_l} and {@code Wing Left} all work; a bone
     * whose name matches nothing inherits its parent's joint, and a top-level
     * bone that matches nothing is part of the body.
     */
    public static AnimalModel.Joint jointOf(String boneName) {
        if (boneName == null) return null;
        String n = boneName.toLowerCase().replace('-', '_').replace(' ', '_');
        boolean left = n.contains("left") || n.endsWith("_l") || n.contains("_l_");
        boolean right = n.contains("right") || n.endsWith("_r") || n.contains("_r_");
        boolean front = n.contains("front") || n.contains("fore");
        if (n.contains("wing") || n.contains("fin") || n.contains("flipper")) {
            return right ? AnimalModel.Joint.WING_R : AnimalModel.Joint.WING_L;
        }
        if (n.contains("leg") || n.contains("foot") || n.contains("paw")
                || n.contains("talon") || n.contains("claw")) {
            if (front) return right ? AnimalModel.Joint.LEG_FR : AnimalModel.Joint.LEG_FL;
            if (n.contains("back") || n.contains("hind") || n.contains("rear")) {
                return right ? AnimalModel.Joint.LEG_BR : AnimalModel.Joint.LEG_BL;
            }
            return right ? AnimalModel.Joint.LEG_FR : AnimalModel.Joint.LEG_FL;
        }
        if (n.contains("tail")) return AnimalModel.Joint.TAIL;
        if (n.contains("ear") || n.contains("antenna")) return AnimalModel.Joint.EAR;
        if (n.contains("horn") || n.contains("antler") || n.contains("crest")) {
            return AnimalModel.Joint.HORN;
        }
        if (n.contains("head") || n.contains("skull") || n.contains("beak")
                || n.contains("bill") || n.contains("snout") || n.contains("neck")
                || n.contains("eye") || n.contains("jaw")) {
            return AnimalModel.Joint.HEAD;
        }
        if (n.contains("body") || n.contains("torso") || n.contains("chest")
                || n.contains("root")) {
            return AnimalModel.Joint.BODY;
        }
        return null;
    }

    /** Which part of the skin a joint's boxes are painted from. */
    private static AnimalSkins.Region regionOf(AnimalModel.Joint joint) {
        return switch (joint) {
            case HEAD -> AnimalSkins.Region.HEAD;
            case WING_L, WING_R -> AnimalSkins.Region.WING;
            case LEG_FL, LEG_FR, LEG_BL, LEG_BR -> AnimalSkins.Region.LIMB;
            case TAIL -> AnimalSkins.Region.TAIL;
            case HORN -> AnimalSkins.Region.HARD;
            case EAR -> AnimalSkins.Region.HEAD;
            default -> AnimalSkins.Region.BODY;
        };
    }

    private static Map<AnimState, Clip> readAnimations(Map<String, Object> root,
                                                       Map<String, AnimalModel.Joint> bones) {
        Map<AnimState, Clip> clips = new EnumMap<>(AnimState.class);
        for (Map<String, Object> animation : WatchJson.objects(root, "animations")) {
            String name = WatchJson.str(animation, "name", "");
            AnimState state = AnimState.forClip(name);
            if (state == null || clips.containsKey(state)) continue;
            double length = WatchJson.num(animation, "length", 1);
            boolean loop = !"once".equalsIgnoreCase(WatchJson.str(animation, "loop", "loop"));

            List<Channel> channels = new ArrayList<>();
            Map<String, Object> animators = WatchJson.map(animation, "animators");
            for (Map.Entry<String, Object> entry : animators.entrySet()) {
                if (!(entry.getValue() instanceof Map<?, ?>)) continue;
                Map<String, Object> animator = Json.asObject(entry.getValue());
                String boneName = WatchJson.str(animator, "name", "");
                AnimalModel.Joint joint = jointOf(boneName);
                if (joint == null) joint = bones.get(boneName.toLowerCase());
                if (joint == null) continue;

                List<Keyframe> rotation = new ArrayList<>();
                List<Keyframe> position = new ArrayList<>();
                for (Map<String, Object> frame : WatchJson.objects(animator, "keyframes")) {
                    String channelName = WatchJson.str(frame, "channel", "");
                    List<Map<String, Object>> points =
                            WatchJson.objects(frame, "data_points");
                    if (points.isEmpty()) continue;
                    Map<String, Object> point = points.get(0);
                    boolean smooth = !"linear".equalsIgnoreCase(
                            WatchJson.str(frame, "interpolation", "linear"));
                    Keyframe key = new Keyframe(WatchJson.num(frame, "time", 0),
                            number(point.get("x")), number(point.get("y")),
                            number(point.get("z")), smooth);
                    if ("rotation".equalsIgnoreCase(channelName)) rotation.add(key);
                    else if ("position".equalsIgnoreCase(channelName)) position.add(key);
                }
                rotation.sort((a, b) -> Double.compare(a.time(), b.time()));
                position.sort((a, b) -> Double.compare(a.time(), b.time()));
                if (!rotation.isEmpty()) channels.add(new Channel(joint, true, rotation));
                if (!position.isEmpty()) channels.add(new Channel(joint, false, position));
            }
            if (!channels.isEmpty()) {
                clips.put(state, new Clip(name, Math.max(0.05, length), loop, channels));
            }
        }
        return clips;
    }

    /** The first embedded texture's bytes, or {@code null}. */
    private static byte[] readTexture(Map<String, Object> root) {
        for (Map<String, Object> texture : WatchJson.objects(root, "textures")) {
            String source = WatchJson.str(texture, "source", "");
            int comma = source.indexOf(',');
            if (!source.startsWith("data:") || comma < 0) continue;
            try {
                return Base64.getDecoder().decode(source.substring(comma + 1));
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        return null;
    }

    /**
     * A three-number array under a key.
     *
     * <p>Blockbench sometimes writes a coordinate as a string ("8" rather than
     * 8) after a hand edit, so numbers are parsed leniently rather than
     * required.
     */
    private static double[] triple(Map<String, Object> from, String key) {
        List<Object> list = WatchJson.list(from, key);
        double[] out = new double[3];
        for (int i = 0; i < 3 && i < list.size(); i++) {
            out[i] = number(list.get(i));
        }
        return out;
    }

    private static double number(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        if (o instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }
}
