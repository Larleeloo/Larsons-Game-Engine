package com.larsons.engine.watch;

import com.larsons.engine.watch.life.AnimState;
import com.larsons.engine.watch.life.AnimalDef;
import com.larsons.engine.watch.life.AnimalModel;
import com.larsons.engine.watch.life.AnimalModels;
import com.larsons.engine.watch.life.AnimalRegistry;
import com.larsons.engine.watch.model.ModelRig;
import com.larsons.engine.watch.model.ObjReader;
import com.larsons.engine.watch.model.RawModel;
import com.larsons.engine.watch.model.SceneModel;
import com.larsons.engine.watch.model.SceneModels;
import com.larsons.engine.watch.render.CosmeticModel;
import com.larsons.engine.watch.render.Mesh;
import com.larsons.engine.watch.render.RangerModel;
import com.larsons.engine.watch.render.WalkerModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The other import path — <b>what {@code BlockbenchTest} is, for triangles.</b>
 *
 * <p>{@code resources/watch/models/README.md} makes a second set of promises
 * now: export from Blender with the default settings and the model faces the
 * right way; call a bone {@code arm_l} and it is the left arm; model at any
 * size and a person comes out person-sized; ship a file with two clips in it and
 * the other eight states still move. Every one of those is a sentence somebody
 * will act on without reading the code, so every one of them is a test here.
 *
 * <p>The fixtures are built rather than checked in: a {@code .glb} in
 * {@code src/test/resources} is an opaque blob that nobody can review, and the
 * thing most worth reviewing about these is the exact bytes the reader is being
 * promised.
 */
class ModelImportTest {

    // --- fixtures ------------------------------------------------------------------

    /**
     * The binary half of the test model.
     *
     * <p>A body triangle a unit wide and two high, a head triangle above it, and
     * a two-key rotation track — laid out in that order because the byte offsets
     * in {@link #gltf} are written out by hand, which is the point: a reader
     * that quietly ignored {@code byteOffset} would pass a test whose fixture
     * had everything at zero.
     */
    private static byte[] buffer() {
        ByteBuffer b = ByteBuffer.allocate(112).order(ByteOrder.LITTLE_ENDIAN);
        // Body: (0,0,0), (1,0,0), (0,2,0) — one wide across, two high.
        b.putFloat(0).putFloat(0).putFloat(0);
        b.putFloat(1).putFloat(0).putFloat(0);
        b.putFloat(0).putFloat(2).putFloat(0);
        // Head, in the head node's own space; the node lifts it to y = 2.
        b.putFloat(0).putFloat(0).putFloat(0);
        b.putFloat(0.5f).putFloat(0).putFloat(0);
        b.putFloat(0).putFloat(0.5f).putFloat(0);
        // Keyframe times.
        b.putFloat(0).putFloat(1);
        // Rotations: none, then a quarter turn about x.
        b.putFloat(0).putFloat(0).putFloat(0).putFloat(1);
        float s = (float) Math.sin(Math.PI / 4), c = (float) Math.cos(Math.PI / 4);
        b.putFloat(s).putFloat(0).putFloat(0).putFloat(c);
        return b.array();
    }

    /** The JSON half, with the buffer either embedded or left for a GLB chunk. */
    private static String gltf(String bufferUri) {
        String uri = bufferUri == null ? "" : "\"uri\": \"" + bufferUri + "\", ";
        return """
        {
          "asset": {"version": "2.0"},
          "scene": 0,
          "scenes": [{"nodes": [0]}],
          "nodes": [
            {"name": "root", "children": [1]},
            {"name": "body", "mesh": 0, "children": [2]},
            {"name": "head", "translation": [0, 2, 0], "mesh": 1}
          ],
          "meshes": [
            {"primitives": [{"attributes": {"POSITION": 0}, "material": 0}]},
            {"primitives": [{"attributes": {"POSITION": 1}}]}
          ],
          "materials": [
            {"pbrMetallicRoughness": {"baseColorFactor": [0.2, 0.4, 0.6, 1]}}
          ],
          "accessors": [
            {"bufferView": 0, "componentType": 5126, "count": 3, "type": "VEC3"},
            {"bufferView": 1, "componentType": 5126, "count": 3, "type": "VEC3"},
            {"bufferView": 2, "componentType": 5126, "count": 2, "type": "SCALAR"},
            {"bufferView": 3, "componentType": 5126, "count": 2, "type": "VEC4"}
          ],
          "bufferViews": [
            {"buffer": 0, "byteOffset": 0,  "byteLength": 36},
            {"buffer": 0, "byteOffset": 36, "byteLength": 36},
            {"buffer": 0, "byteOffset": 72, "byteLength": 8},
            {"buffer": 0, "byteOffset": 80, "byteLength": 32}
          ],
          "buffers": [{%s"byteLength": 112}],
          "animations": [{
            "name": "walk",
            "channels": [{"sampler": 0, "target": {"node": 2, "path": "rotation"}}],
            "samplers": [{"input": 2, "output": 3, "interpolation": "LINEAR"}]
          }]
        }
        """.formatted(uri);
    }

    /** The whole model as one {@code .gltf} document, buffer and all. */
    private static byte[] gltfBytes() {
        String uri = "data:application/octet-stream;base64,"
                + Base64.getEncoder().encodeToString(buffer());
        return gltf(uri).getBytes(StandardCharsets.UTF_8);
    }

    /** The same model packed as a GLB: a JSON chunk, then a BIN chunk. */
    private static byte[] glbBytes() {
        byte[] json = pad(gltf(null).getBytes(StandardCharsets.UTF_8), (byte) ' ');
        byte[] binary = pad(buffer(), (byte) 0);
        int total = 12 + 8 + json.length + 8 + binary.length;
        ByteBuffer out = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
        out.putInt(0x46546C67);
        out.putInt(2);
        out.putInt(total);
        out.putInt(json.length);
        out.putInt(0x4E4F534A);
        out.put(json);
        out.putInt(binary.length);
        out.putInt(0x004E4942);
        out.put(binary);
        return out.array();
    }

    /** GLB chunks are four-byte aligned, and a reader may rely on it. */
    private static byte[] pad(byte[] bytes, byte with) {
        int over = bytes.length % 4;
        if (over == 0) return bytes;
        byte[] out = java.util.Arrays.copyOf(bytes, bytes.length + (4 - over));
        java.util.Arrays.fill(out, bytes.length, out.length, with);
        return out;
    }

    private static SceneModel bake(byte[] bytes, ModelRig.Kind kind, SceneModel.Size size) {
        RawModel raw = com.larsons.engine.watch.model.GltfReader.parse(bytes, "test", null);
        assertNotNull(raw, "the fixture did not parse");
        return SceneModel.bake(raw, kind, size);
    }

    /** What one draw of a model covers, so a test can measure it. */
    private static Mesh draw(SceneModel model, AnimState state, double phase,
                             double scale, double headTurn) {
        Mesh.Builder mesh = Mesh.builder(0, 0, 0, false, 1);
        float[] uv = {0, 0, 1, 1};
        model.mesh(mesh, 0, 0, 0, 0, state, phase, scale, uv, headTurn);
        return mesh.build();
    }

    /**
     * The model at rest — no clip, and every joint told to stay where it is.
     *
     * <p>What a test measuring a <em>size</em> wants. Drawing in a state the
     * fixture does not animate poses it procedurally, and a breathing model is
     * not the height of a standing one.
     */
    private static Mesh atRest(SceneModel model, double scale) {
        Mesh.Builder mesh = Mesh.builder(0, 0, 0, false, 1);
        model.mesh(mesh, 0, 0, 0, 0, AnimState.RUN, 0, scale, new float[]{0, 0, 1, 1}, 0,
                (state, joint, phase) -> AnimalModel.Pose.REST);
        return mesh.build();
    }

    // --- OBJ -----------------------------------------------------------------------

    private static final String CUBE_OBJ = """
        mtllib cube.mtl
        o head
        v 0 0 0
        v 1 0 0
        v 1 1 0
        v 0 1 0
        usemtl bark
        f 1 2 3
        f 1 3 4
        # a face written with negative indices, as a tool that streams them does
        f -4 -3 -2
        """;

    private static final String CUBE_MTL = """
        newmtl bark
        Kd 0.2 0.4 0.6
        """;

    @Test
    void objFacesBecomeTrianglesWithTheirMaterialColour() {
        RawModel model = ObjReader.parse(CUBE_OBJ, "cube.obj", name -> CUBE_MTL);
        assertNotNull(model);
        assertEquals(3, model.triangles(), "two faces and one negative-indexed one");
        for (RawModel.Piece piece : model.pieces()) {
            for (int colour : piece.colours()) {
                // Kd 0.2/0.4/0.6 is linear, and comes out as the sRGB the rest
                // of this game's colours are written in. See ObjReader.packLinear.
                assertEquals(0x7CAACB, colour, "the material's Kd did not reach the triangle");
            }
        }
    }

    @Test
    void objGroupsAreJointsLikeAnyOtherBone() {
        RawModel model = ObjReader.parse(CUBE_OBJ, "cube.obj", null);
        assertNotNull(model);
        // The `o head` line made a node, and that node is bound to the head.
        boolean found = false;
        for (RawModel.Node node : model.nodes()) {
            if ("head".equals(node.name())) {
                assertEquals(AnimalModel.Joint.HEAD,
                        ModelRig.jointOf(node.name(), ModelRig.Kind.HUMANOID));
                found = true;
            }
        }
        assertTrue(found, "the object name did not become a node");
    }

    @Test
    void anObjWithNoFacesIsNotAModel() {
        assertNull(ObjReader.parse("v 0 0 0\nv 1 0 0\n", "empty.obj", null),
                "a file with no faces should keep the fallback, not draw nothing");
    }

    // --- glTF ----------------------------------------------------------------------

    @Test
    void aGltfLoadsItsNodesMeshesAndClip() {
        RawModel raw = com.larsons.engine.watch.model.GltfReader.parse(gltfBytes(),
                "test.gltf", null);
        assertNotNull(raw);
        assertEquals(3, raw.nodes().size());
        assertEquals(2, raw.triangles(), "one triangle on the body, one on the head");
        assertEquals(1, raw.clips().size());
        assertEquals("walk", raw.clips().get(0).name());
    }

    @Test
    void aGlbCarriesTheSameModelAsItsGltf() {
        RawModel packed = com.larsons.engine.watch.model.GltfReader.parse(glbBytes(),
                "test.glb", null);
        RawModel loose = com.larsons.engine.watch.model.GltfReader.parse(gltfBytes(),
                "test.gltf", null);
        assertNotNull(packed, "the GLB container did not parse");
        assertNotNull(loose);
        assertEquals(loose.triangles(), packed.triangles());
        assertEquals(loose.nodes().size(), packed.nodes().size());
    }

    @Test
    void theMaterialsBaseColourPaintsTheTriangle() {
        RawModel raw = com.larsons.engine.watch.model.GltfReader.parse(gltfBytes(),
                "test.gltf", null);
        assertNotNull(raw);
        boolean painted = false;
        for (RawModel.Piece piece : raw.pieces()) {
            for (int colour : piece.colours()) {
                if (colour == 0x7CAACB) painted = true;
            }
        }
        assertTrue(painted, "baseColorFactor did not reach a triangle");
    }

    /**
     * The promise an artist will hold this to without ever reading the code:
     * the hex they type into Blender's colour picker is the colour that turns
     * up in the game, next to the boxes that use the same hex directly.
     */
    @Test
    void aColourTypedIntoBlenderSurvivesTheRoundTrip() {
        // Blender turns the ranger coat's 0x3C5240 into these linear floats.
        double r = 60 / 255.0, g = 82 / 255.0, b = 64 / 255.0;
        String linear = "[%s, %s, %s, 1]".formatted(toLinear(r), toLinear(g), toLinear(b));
        byte[] bytes = gltf("data:application/octet-stream;base64,"
                + Base64.getEncoder().encodeToString(buffer()))
                .replace("[0.2, 0.4, 0.6, 1]", linear)
                .getBytes(StandardCharsets.UTF_8);

        RawModel raw = com.larsons.engine.watch.model.GltfReader.parse(bytes, "coat", null);
        assertNotNull(raw);
        boolean matched = false;
        for (RawModel.Piece piece : raw.pieces()) {
            for (int colour : piece.colours()) {
                if (colour == 0x3C5240) matched = true;
            }
        }
        assertTrue(matched, "a coat painted 0x3C5240 in Blender did not come back 0x3C5240");
    }

    private static double toLinear(double srgb) {
        return srgb <= 0.04045 ? srgb / 12.92 : Math.pow((srgb + 0.055) / 1.055, 2.4);
    }

    @Test
    void aTruncatedFileKeepsTheFallbackRatherThanThrowing() {
        byte[] whole = glbBytes();
        byte[] half = java.util.Arrays.copyOf(whole, whole.length / 2);
        assertNull(com.larsons.engine.watch.model.GltfReader.parse(half, "cut.glb", null));
        assertNull(com.larsons.engine.watch.model.GltfReader.parse(
                "not json at all".getBytes(StandardCharsets.UTF_8), "junk.glb", null));
    }

    // --- axes and size -------------------------------------------------------------

    /**
     * The README's central promise, and the one that is invisible when it
     * breaks: a file's up is this game's up, and a file's front is this game's
     * forward.
     */
    @Test
    void theFilesAxesBecomeThisGamesAxes() {
        SceneModel model = bake(gltfBytes(), ModelRig.Kind.HUMANOID,
                SceneModel.Size.AS_MODELLED);
        assertNotNull(model);
        Mesh drawn = draw(model, AnimState.STRIKE, 0, 1, 0);

        // The fixture is one unit along the file's +x and 2.5 up its +y, with
        // no depth at all along its +z. In this game that is: nothing forward,
        // one to the right, 2.5 up.
        assertEquals(0, drawn.maxX() - drawn.minX(), 1e-5, "the file had no depth");
        assertEquals(1, drawn.maxY() - drawn.minY(), 1e-5, "the file's right is ours");
        assertEquals(2.5, drawn.maxZ() - drawn.minZ(), 1e-5, "the file's up is ours");
    }

    @Test
    void aPersonIsNormalisedToTheirHeightAndStandsOnTheGround() {
        SceneModel model = bake(gltfBytes(), ModelRig.Kind.HUMANOID,
                SceneModel.Size.height(1));
        assertNotNull(model);
        assertEquals(1, model.height(), 1e-6, "height should normalise to one");

        // Drawn at 1.8 m, the thing should be 1.8 m tall with its feet at zero,
        // whatever size it was modelled at.
        Mesh drawn = atRest(model, 1.8);
        assertEquals(0, drawn.minZ(), 1e-5, "the model did not stand on the ground");
        assertEquals(1.8, drawn.maxZ(), 1e-5);
    }

    /**
     * The rule that replaced "longest horizontal extent becomes one body
     * length", which made the first real import — a wendigo — ten metres tall
     * against a seven-metre placeholder. See {@link AnimalModel#height}.
     */
    @Test
    void aCreatureComesOutTheHeightOfItsPlaceholder() {
        AnimalDef def = AnimalRegistry.all().get(11);
        double want = AnimalModel.of(def).height();
        assertTrue(want > 0, "the placeholder should have a height to match");

        SceneModel model = bake(gltfBytes(), ModelRig.Kind.CREATURE,
                SceneModel.Size.height(want));
        assertNotNull(model);
        assertEquals(want, model.height(), 1e-6);

        // Drawn at the species' body length, it stands as tall as the boxes do.
        Mesh drawn = atRest(model, def.bodyLength());
        assertEquals(0, drawn.minZ(), 1e-5, "an animal stands on the ground");
        assertEquals(want * def.bodyLength(), drawn.maxZ(), 1e-4);
    }

    /** Modelled at ten times the size, it still comes out the size it is drawn at. */
    @Test
    void theSizeItWasModelledAtDoesNotMatter() {
        String scaled = gltf("data:application/octet-stream;base64,"
                + Base64.getEncoder().encodeToString(scaled(10)));
        SceneModel big = bake(scaled.getBytes(StandardCharsets.UTF_8),
                ModelRig.Kind.HUMANOID, SceneModel.Size.height(1));
        SceneModel small = bake(gltfBytes(), ModelRig.Kind.HUMANOID,
                SceneModel.Size.height(1));
        assertNotNull(big);
        assertNotNull(small);
        assertEquals(small.height(), big.height(), 1e-6);
        assertEquals(atRest(small, 1.8).maxZ(), atRest(big, 1.8).maxZ(), 1e-4);
    }

    /** The fixture's buffer with every position multiplied — the node lift too. */
    private static byte[] scaled(float by) {
        byte[] bytes = buffer();
        ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < 18; i++) b.putFloat(i * 4, b.getFloat(i * 4) * by);
        return bytes;
    }

    // --- animation -----------------------------------------------------------------

    @Test
    void anAuthoredClipPlaysForItsState() {
        SceneModel model = bake(gltfBytes(), ModelRig.Kind.HUMANOID,
                SceneModel.Size.AS_MODELLED);
        assertNotNull(model);
        assertTrue(model.animates(AnimState.WALK), "the clip named walk was not bound");
        assertFalse(model.animates(AnimState.RUN));

        // The head bone turns a quarter circle over the clip, so the extent of
        // the model at the end of it is not the extent at the start.
        Mesh start = draw(model, AnimState.WALK, 0, 1, 0);
        Mesh end = draw(model, AnimState.WALK, 0.999, 1, 0);
        assertTrue(Math.abs(start.maxZ() - end.maxZ()) > 0.1,
                "the walk clip did not move anything");
    }

    /**
     * The promise that makes a half-finished model worth committing: a state
     * the artist has not animated is still animated.
     */
    @Test
    void aStateWithNoClipFallsBackToTheProceduralPose() {
        SceneModel model = bake(gltfBytes(), ModelRig.Kind.HUMANOID,
                SceneModel.Size.AS_MODELLED);
        assertNotNull(model);
        assertFalse(model.animates(AnimState.RUN), "the fixture animates only walk");

        Mesh early = draw(model, AnimState.RUN, 0.1, 1, 0);
        Mesh late = draw(model, AnimState.RUN, 0.6, 1, 0);
        assertTrue(different(early, late),
                "a state with no clip should still move, not freeze");
    }

    @Test
    void theHeadTurnsTowardWhoeverIsThere() {
        SceneModel model = bake(gltfBytes(), ModelRig.Kind.HUMANOID,
                SceneModel.Size.AS_MODELLED);
        assertNotNull(model);
        Mesh ahead = draw(model, AnimState.WALK, 0, 1, 0);
        Mesh turned = draw(model, AnimState.WALK, 0, 1, 1.0);
        assertTrue(different(ahead, turned), "the head turn changed nothing");
    }

    private static boolean different(Mesh a, Mesh b) {
        return Math.abs(a.maxX() - b.maxX()) > 1e-4 || Math.abs(a.maxY() - b.maxY()) > 1e-4
                || Math.abs(a.maxZ() - b.maxZ()) > 1e-4 || Math.abs(a.minX() - b.minX()) > 1e-4
                || Math.abs(a.minY() - b.minY()) > 1e-4 || Math.abs(a.minZ() - b.minZ()) > 1e-4;
    }

    // --- bone names ----------------------------------------------------------------

    @Test
    void humanoidBoneNamesBindToJoints() {
        assertEquals(AnimalModel.Joint.WING_L,
                ModelRig.jointOf("arm_l", ModelRig.Kind.HUMANOID));
        assertEquals(AnimalModel.Joint.WING_R,
                ModelRig.jointOf("upper_arm.R", ModelRig.Kind.HUMANOID));
        assertEquals(AnimalModel.Joint.WING_R,
                ModelRig.jointOf("Right Hand", ModelRig.Kind.HUMANOID));
        assertEquals(AnimalModel.Joint.LEG_FL,
                ModelRig.jointOf("thigh_l", ModelRig.Kind.HUMANOID));
        assertEquals(AnimalModel.Joint.HEAD, ModelRig.jointOf("neck", ModelRig.Kind.HUMANOID));
        assertEquals(AnimalModel.Joint.HEAD, ModelRig.jointOf("hat_brim",
                ModelRig.Kind.HUMANOID));
        assertEquals(AnimalModel.Joint.BODY, ModelRig.jointOf("spine_02",
                ModelRig.Kind.HUMANOID));
        assertNull(ModelRig.jointOf("frobnicator", ModelRig.Kind.HUMANOID),
                "an unknown name should inherit its parent, not guess");
    }

    /** A creature file binds by the same names a {@code .bbmodel} always did. */
    @Test
    void creatureBoneNamesAreBlockbenchsOwn() {
        assertEquals(AnimalModel.Joint.WING_R,
                ModelRig.jointOf("wing_r", ModelRig.Kind.CREATURE));
        assertEquals(AnimalModel.Joint.TAIL, ModelRig.jointOf("tail", ModelRig.Kind.CREATURE));
        assertEquals(AnimalModel.Joint.LEG_BL,
                ModelRig.jointOf("hind_leg_left", ModelRig.Kind.CREATURE));
        assertEquals(AnimalModel.Joint.EAR, ModelRig.jointOf("left_ear", ModelRig.Kind.CREATURE));
        assertEquals(AnimalModel.Joint.EAR, ModelRig.jointOf("earL", ModelRig.Kind.CREATURE));
    }

    /**
     * The bone names the real wendigo export uses — a standard Blender rig,
     * every one of which the matcher used to get wrong.
     */
    @Test
    void aBlenderRigsBoneNamesBindCorrectly() {
        ModelRig.Kind c = ModelRig.Kind.CREATURE;
        // `forearm` contains `ear`, and used to become one — so both of a
        // wendigo's forearms flicked like ears in every unanimated state.
        assertEquals(AnimalModel.Joint.WING_L, ModelRig.jointOf("forearm.L", c));
        assertEquals(AnimalModel.Joint.WING_R, ModelRig.jointOf("forearm.R", c));
        assertEquals(AnimalModel.Joint.WING_L, ModelRig.jointOf("upperarm.L", c));
        assertEquals(AnimalModel.Joint.WING_R, ModelRig.jointOf("hand.R", c));
        assertEquals(AnimalModel.Joint.WING_L, ModelRig.jointOf("clavicle.L", c));
        // Blender's .L/.R must pick a side; both used to fall to the left.
        assertEquals(AnimalModel.Joint.LEG_FL, ModelRig.jointOf("thigh.L", c));
        assertEquals(AnimalModel.Joint.LEG_FR, ModelRig.jointOf("thigh.R", c));
        assertEquals(AnimalModel.Joint.LEG_FR, ModelRig.jointOf("shin.R", c));
        assertEquals(AnimalModel.Joint.LEG_FR, ModelRig.jointOf("toe.R", c));
        // Spine, hips and muzzle matched nothing at all.
        assertEquals(AnimalModel.Joint.BODY, ModelRig.jointOf("spine_01", c));
        assertEquals(AnimalModel.Joint.BODY, ModelRig.jointOf("hips", c));
        assertEquals(AnimalModel.Joint.HEAD, ModelRig.jointOf("muzzle", c));
        assertEquals(AnimalModel.Joint.HEAD, ModelRig.jointOf("neck_02", c));
        assertEquals(AnimalModel.Joint.HORN, ModelRig.jointOf("antler.R", c));
    }

    /**
     * A claw is on whatever limb it hangs off, so it names no joint of its own
     * and takes its parent's. Matching `claw` as a leg put both of a wendigo's
     * hands on its front left foot.
     */
    @Test
    void clawsInheritTheLimbTheyHangOff() {
        assertNull(ModelRig.jointOf("claw_0.L", ModelRig.Kind.CREATURE),
                "a claw should inherit, not guess");
        // A bird's talon is still a foot, because a talon only ever is one.
        assertEquals(AnimalModel.Joint.LEG_FR,
                ModelRig.jointOf("talon_r", ModelRig.Kind.CREATURE));
    }

    /** `bear` is one of this game's own family keys, and is not an ear. */
    @Test
    void aFamilyNamedBearIsNotAnEar() {
        assertEquals(AnimalModel.Joint.BODY,
                bindingOf("bear", ModelRig.Kind.CREATURE));
    }

    private static AnimalModel.Joint bindingOf(String bone, ModelRig.Kind kind) {
        AnimalModel.Joint j = ModelRig.jointOf(bone, kind);
        return j == null ? AnimalModel.Joint.BODY : j;
    }

    /**
     * The states a model does not animate are posed by the table the caller
     * chooses — {@code MutantGait} for a six-metre biped, the shared animal
     * poses for a wren. Without this an imported wendigo idles like a wren.
     */
    @Test
    void theFallbackAnimationCanBeSuppliedByTheCaller() {
        SceneModel model = bake(gltfBytes(), ModelRig.Kind.CREATURE,
                SceneModel.Size.height(1));
        assertNotNull(model);
        assertFalse(model.animates(AnimState.RUN), "the fixture animates only walk");

        Mesh ownTable = draw(model, AnimState.RUN, 0.3, 1, 0);
        Mesh.Builder b = Mesh.builder(0, 0, 0, false, 1);
        model.mesh(b, 0, 0, 0, 0, AnimState.RUN, 0.3, 1, new float[]{0, 0, 1, 1}, 0,
                (state, joint, phase) -> AnimalModel.Pose.full(0.9, 0, 0, 0, 0, 0));
        assertTrue(different(ownTable, b.build()),
                "a fallback passed in should replace the model's own");
    }

    /** And a mutant's really is the mutant one, all the way through the loader. */
    @Test
    void anImportedMutantKeepsTheMutantGait(@TempDir Path dir) throws IOException {
        AnimalDef mutant = null;
        for (AnimalDef d : AnimalRegistry.all()) {
            if (d.family().key().equals("wendigo")) { mutant = d; break; }
        }
        assertNotNull(mutant, "the wendigo should be in the registry");
        Files.write(dir.resolve(mutant.family().key() + ".glb"), glbBytes());

        AnimalModels.setDirectory(dir);
        try {
            AnimalModels.Loaded loaded = AnimalModels.of(mutant);
            assertTrue(loaded.freeform(), "the .glb should have been picked up");
            AnimalModel.Pose gait = loaded.poses().poseOf(AnimState.IDLE,
                    AnimalModel.Joint.BODY, 0.3);
            AnimalModel.Pose generic = AnimalModel.pose(AnimState.IDLE,
                    AnimalModel.Joint.BODY, 0.3);
            assertNotEquals(generic, gait,
                    "an imported mutant should still be posed by MutantGait");
        } finally {
            reset();
        }
    }

    /** A bone that names nothing takes its parent's joint, so detail can nest. */
    @Test
    void anUnnamedBoneInheritsItsParent() {
        String nested = gltf("data:application/octet-stream;base64,"
                + Base64.getEncoder().encodeToString(buffer()))
                .replace("\"name\": \"head\"", "\"name\": \"tuft\"");
        SceneModel model = bake(nested.getBytes(StandardCharsets.UTF_8),
                ModelRig.Kind.HUMANOID, SceneModel.Size.AS_MODELLED);
        assertNotNull(model, "a model whose bones name nothing should still load");
    }

    // --- the folder ----------------------------------------------------------------

    @Test
    void aGlbInTheModelsFolderReplacesASpeciesPlaceholder(@TempDir Path dir)
            throws IOException {
        AnimalDef def = AnimalRegistry.all().get(5);
        Files.write(dir.resolve(def.key() + ".glb"), glbBytes());

        AnimalModels.setDirectory(dir);
        try {
            AnimalModels.Loaded loaded = AnimalModels.of(def);
            assertTrue(loaded.imported(), "the file in the folder was not picked up");
            assertTrue(loaded.freeform(), "a .glb should import as triangles");

            Mesh.Builder mesh = Mesh.builder(0, 0, 0, false, 1);
            loaded.draw(mesh, def, 0, 0, 0, 0, AnimState.IDLE, 0.2, 1);
            assertEquals(2, mesh.build().triangleCount(),
                    "draw() should use the imported mesh, not the placeholder's boxes");
        } finally {
            reset();
        }
    }

    @Test
    void aMalformedFileLeavesTheSpeciesWithItsPlaceholder(@TempDir Path dir)
            throws IOException {
        AnimalDef def = AnimalRegistry.all().get(6);
        Files.writeString(dir.resolve(def.key() + ".gltf"), "{ not glTF at all }",
                StandardCharsets.UTF_8);

        AnimalModels.setDirectory(dir);
        try {
            AnimalModels.Loaded loaded = AnimalModels.of(def);
            assertFalse(loaded.imported(), "a broken file must not replace anything");
            assertFalse(loaded.freeform());
            assertTrue(loaded.geometry().boxCount() > 0, "the placeholder is still there");
        } finally {
            reset();
        }
    }

    // --- the ranger ----------------------------------------------------------------

    @Test
    void theRangerIsDrawnBeforeAnyArtArrives() {
        reset();
        RangerModel.Ranger who = RangerModel.of(1234);
        Mesh.Builder mesh = Mesh.builder(0, 0, 0, false, 1);
        RangerModel.ranger(mesh, who, 0, 0, 0, 0.4, 0.9, 3.0, 0);
        Mesh drawn = mesh.build();
        assertTrue(drawn.triangleCount() > 200,
                "the procedural ranger should be a real model, not a stub");
        assertEquals(0, drawn.minZ(), 0.005, "a ranger stands on the ground");
        // HEIGHT means the crown of the hat, which is what an imported model is
        // scaled to. If these two ever disagree, dropping in a .glb silently
        // resizes the ranger.
        assertEquals(RangerModel.HEIGHT * who.build(), drawn.maxZ(), 0.01,
                "the boxes should be exactly HEIGHT tall");
    }

    /** Two posts, two people — the property that makes them characters. */
    @Test
    void aPostsRangerIsTheSamePersonEveryTime() {
        assertEquals(RangerModel.of(90210), RangerModel.of(90210));
        long differing = 0;
        for (long id = 1; id < 40; id++) {
            if (!RangerModel.of(id).equals(RangerModel.of(id + 1))) differing++;
        }
        assertTrue(differing > 30, "neighbouring posts got near-identical rangers");
    }

    @Test
    void aFileInTheCharactersFolderReplacesTheRanger(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("characters"));
        Files.write(dir.resolve("characters/ranger.glb"), glbBytes());

        SceneModels.setDirectory(dir);
        try {
            assertTrue(RangerModel.imported(), "the dropped-in ranger was not found");
            Mesh.Builder mesh = Mesh.builder(0, 0, 0, false, 1);
            RangerModel.ranger(mesh, RangerModel.of(7), 0, 0, 0, 0, 0, 1.0, 0);
            Mesh drawn = mesh.build();
            assertEquals(2, drawn.triangleCount(),
                    "the imported model should be drawn instead of the boxes");
            // Not exactly zero: the fixture supplies no idle clip, so the
            // humanoid fallback is breathing, and every triangle in the fixture
            // is bound to the body it lifts. A real model's feet are on LEG_*
            // bones, which breathing does not touch.
            assertEquals(0, drawn.minZ(), 0.01, "an imported ranger stands on the ground");
            assertEquals(RangerModel.HEIGHT * RangerModel.of(7).build(), drawn.maxZ(), 0.01,
                    "an imported ranger comes out person-sized whatever it was modelled at");
        } finally {
            reset();
        }
    }

    // --- the player, in and out of the water ------------------------------------------

    /** One swimmer, from the file rather than from the boxes. */
    private static Mesh swimming(double bodyPitch) {
        Mesh.Builder mesh = Mesh.builder(0, 0, 0, false, 1);
        WalkerModel.swimmer(mesh, 0, 0, 0, 0, bodyPitch, 1, 0, true, 0x4A6B33, List.of());
        return mesh.build();
    }

    private static Mesh standing() {
        Mesh.Builder mesh = Mesh.builder(0, 0, 0, false, 1);
        WalkerModel.walker(mesh, 0, 0, 0, 0, false, 0, 0,
                WalkerModel.Leap.GROUNDED, 0x4A6B33, List.of(), 0);
        return mesh.build();
    }

    /**
     * A swimmer and a rower are drawn from the file too.
     *
     * <p>They were the documented edge of this for as long as those two poses
     * were numbers no clip knew — a spine laid along a dive, a body folded onto
     * a thwart. Both are clips now, so a walker who wades into a lake stays the
     * figure they were on the bank.
     */
    @Test
    void aModelledWalkerSwimsAndRowsFromTheFileToo(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("characters"));
        Files.write(dir.resolve("characters/walker.glb"), glbBytes());

        SceneModels.setDirectory(dir);
        try {
            assertTrue(WalkerModel.imported(), "the dropped-in walker was not found");
            assertEquals(2, swimming(0).triangleCount(),
                    "a swimmer fell back to the boxes");
            Mesh.Builder mesh = Mesh.builder(0, 0, 0, false, 1);
            WalkerModel.rower(mesh, 0, 0, 0, 0, 0, 0.25, 0x4A6B33, List.of());
            assertEquals(2, mesh.build().triangleCount(),
                    "a rower fell back to the boxes");
        } finally {
            reset();
        }
    }

    /**
     * <b>An upright swimmer is the standing figure, to the vertex.</b>
     *
     * <p>Which is what makes wading out of your depth a tip rather than a cut
     * to a different model, and it is the one assertion that pins
     * {@link SceneModel.Lean}'s sign <em>and</em> its pivot at once: get either
     * wrong and a swimmer treading water is standing somewhere else.
     */
    @Test
    void anUprightSwimmerIsTheStandingFigure(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("characters"));
        Files.write(dir.resolve("characters/walker.glb"), glbBytes());

        SceneModels.setDirectory(dir);
        try {
            Mesh standing = standing();
            Mesh treading = swimming(Math.PI / 2);
            assertEquals(standing.vertexCount(), treading.vertexCount());
            float[] a = standing.vertices(), b = treading.vertices();
            for (int i = 0; i < a.length; i++) {
                assertEquals(a[i], b[i], 1e-4f,
                        "a swimmer holding station drifted off the standing figure");
            }
        } finally {
            reset();
        }
    }

    /** …and laying one down puts their head in front of them, not above them. */
    @Test
    void aProneSwimmerIsLaidOutForward(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("characters"));
        Files.write(dir.resolve("characters/walker.glb"), glbBytes());

        SceneModels.setDirectory(dir);
        try {
            Mesh upright = swimming(Math.PI / 2);
            Mesh prone = swimming(0);
            assertTrue(prone.maxZ() < upright.maxZ() - 0.5,
                    "a prone swimmer is still standing up");
            assertTrue(prone.minY() < upright.minY() - 0.2,
                    "a prone swimmer was laid out backwards, or not at all");
        } finally {
            reset();
        }
    }

    /**
     * {@code swim} names its own state, and {@code walk} no longer answers to
     * it.
     *
     * <p>It was an alias on {@code WALK} while swimming was something only an
     * animal did. A person has both a walk cycle and a breaststroke, and one
     * alias cannot name two clips.
     */
    @Test
    void swimAndRowAreStatesOfTheirOwn() {
        assertEquals(AnimState.SWIM, AnimState.forClip("swim"));
        assertEquals(AnimState.SWIM, AnimState.forClip("ranger_swim"));
        assertEquals(AnimState.ROW, AnimState.forClip("row"));
        assertEquals(AnimState.WALK, AnimState.forClip("walk"));
        assertEquals(AnimState.WALK, AnimState.forClip("walk_cycle"));
        assertNotEquals(AnimState.WALK, AnimState.forClip("swim"));
    }

    // --- cosmetics ------------------------------------------------------------------

    /**
     * A modelled cosmetic replaces its boxes, and does it <b>in place</b>.
     *
     * <p>The second half is the one that would be missed. Everything else this
     * importer loads stands on the ground, so it is measured and dropped to it;
     * a hat is authored on a reference figure at head height and the height it
     * was authored at <em>is</em> the answer. Grounded, this fixture's triangles
     * would sit at zero instead of at the two units up the file puts them.
     */
    @Test
    void aFileInTheCosmeticsFolderReplacesThePiece(@TempDir Path dir) throws IOException {
        String key = Cosmetics.all().get(0).key();
        Files.createDirectories(dir.resolve("cosmetics"));
        Files.write(dir.resolve("cosmetics/" + key + ".glb"), glbBytes());

        onlyTheFolder(dir);
        try {
            assertTrue(CosmeticModel.modelled(key), "the dropped-in piece was not found");
            assertEquals(List.of(), CosmeticModel.boxesOnly(List.of(key)),
                    "a modelled piece should be taken off the list the joints draw");

            Mesh.Builder mesh = Mesh.builder(0, 0, 0, false, 1);
            CosmeticModel.overlay(mesh, List.of(key), 0, 0, 0, 0,
                    WalkerModel.HEIGHT, 0, 0, new float[]{0, 0, 1, 1});
            Mesh drawn = mesh.build();
            assertEquals(2, drawn.triangleCount(), "the imported piece was not drawn");
            // The fixture's head triangle is two units up its own +y, and its
            // body triangle two high from zero. Nothing was dropped to the floor
            // and nothing was rescaled: this is AS_PLACED doing its one job.
            assertEquals(2.5, drawn.maxZ(), 0.02,
                    "a worn model was moved off the height it was modelled at");
        } finally {
            reset();
        }
    }

    /**
     * A piece nobody has modelled is untouched, and a broken file is the same as
     * no file.
     */
    @Test
    void anUnmodelledOrMalformedPieceKeepsItsBoxes(@TempDir Path dir) throws IOException {
        String key = Cosmetics.all().get(1).key();
        Files.createDirectories(dir.resolve("cosmetics"));
        Files.writeString(dir.resolve("cosmetics/" + key + ".gltf"), "{ not glTF }",
                StandardCharsets.UTF_8);

        onlyTheFolder(dir);
        try {
            assertFalse(CosmeticModel.modelled(key), "a broken file replaced something");
            assertEquals(List.of(key), CosmeticModel.boxesOnly(List.of(key)));
            Mesh.Builder mesh = Mesh.builder(0, 0, 0, false, 1);
            CosmeticModel.overlay(mesh, List.of(key), 0, 0, 0, 0,
                    WalkerModel.HEIGHT, 0, 0, new float[]{0, 0, 1, 1});
            assertTrue(mesh.build().isEmpty(), "a broken file drew something");
        } finally {
            reset();
        }
    }

    /**
     * The clip a walker asks for is the one their legs are doing.
     *
     * <p>The fixture animates {@code walk} and nothing else, so a walking figure
     * is posed by the file and a standing one by the humanoid fallback — and the
     * two have to differ, or the speed is not reaching the model at all.
     */
    @Test
    void aWornModelPlaysItsOwnClipAtTheSpeedTheWalkerIsGoing(@TempDir Path dir)
            throws IOException {
        String key = Cosmetics.all().get(2).key();
        Files.createDirectories(dir.resolve("cosmetics"));
        Files.write(dir.resolve("cosmetics/" + key + ".glb"), glbBytes());

        onlyTheFolder(dir);
        try {
            SceneModel model = CosmeticModel.importedFor(key);
            assertNotNull(model);
            assertTrue(model.animates(AnimState.WALK), "the fixture lost its walk clip");
            assertFalse(model.animates(AnimState.IDLE),
                    "the fixture was supposed to leave idle to the fallback");

            // Half way through the clip is a quarter turn about the head bone;
            // standing still is the fallback's small breath. Different meshes,
            // which is the whole assertion.
            assertNotEquals(bounds(worn(key, 0, 0.5)), bounds(worn(key, 5.0, 0.5)),
                    "the walker's speed did not pick the clip");
        } finally {
            reset();
        }
    }

    /**
     * An imported person faces the way the boxes under them face.
     *
     * <p><b>The bug this pins was in the engine and not in anybody's export.</b>
     * An animal's boxes point along {@code +x} at a yaw of zero and this
     * importer matches them; a walker, a keeper and a ranger all point along
     * {@code −y}. So a file's front — the {@code −Y} Blender is asked to face —
     * came out ninety degrees round from the figure it was replacing, and a
     * modelled cape hung off somebody's left shoulder. {@link SceneModel#PERSON_TURN}
     * is the correction and this is what says it is still applied.
     */
    @Test
    void anImportedPersonFacesTheWayTheBoxesFace() {
        // A speck a unit along the file's front — the direction the README tells
        // a modeller to point a character's nose — and another along its right.
        SceneModel front = objModel(speck(0, 0, 1));
        SceneModel side = objModel(speck(1, 0, 0));

        for (double yaw : new double[]{0, Math.PI / 2, 2.4}) {
            double[] nose = middle(person(front, yaw));
            double[] flank = middle(person(side, yaw));
            // Where the box models put forward and right at the same yaw —
            // WalkerModel, KeeperModel and RangerModel all use these two lines.
            assertEquals(Math.sin(yaw), nose[0], 0.01,
                    "an imported person's front is not the boxes' forward at " + yaw);
            assertEquals(-Math.cos(yaw), nose[1], 0.01,
                    "an imported person's front is not the boxes' forward at " + yaw);
            assertEquals(Math.cos(yaw), flank[0], 0.01,
                    "an imported person's right is not the boxes' right at " + yaw);
            assertEquals(Math.sin(yaw), flank[1], 0.01,
                    "an imported person's right is not the boxes' right at " + yaw);
        }
    }

    /** A triangle small enough that where it is, is where its corners are. */
    private static String speck(double x, double y, double z) {
        return "g spine\n"
                + "v " + x + " " + y + " " + z + "\n"
                + "v " + (x + 0.002) + " " + y + " " + z + "\n"
                + "v " + x + " " + (y + 0.002) + " " + z + "\n"
                + "f 1 2 3\n";
    }

    private static double[] middle(Mesh mesh) {
        return new double[]{(mesh.minX() + mesh.maxX()) / 2,
                (mesh.minY() + mesh.maxY()) / 2};
    }

    /** …and a creature adds nothing, because its two conventions already agree. */
    @Test
    void anImportedCreatureIsLeftAloneBecauseItAlreadyAgreed() {
        SceneModel front = objModel(speck(0, 0, 1));
        double[] nose = middle(draw(front, AnimState.RUN, 0, 1, 0));
        // AnimalModel's own forward at a yaw of zero, which is the one this
        // importer was written against and must go on agreeing with.
        assertEquals(1, nose[0], 0.01, "a creature's front moved");
        assertEquals(0, nose[1], 0.01, "a creature's front moved");
    }

    private static SceneModel objModel(String obj) {
        RawModel raw = ObjReader.parse(obj, "probe", null);
        assertNotNull(raw, "the probe did not parse");
        SceneModel model = SceneModel.bake(raw, ModelRig.Kind.HUMANOID,
                SceneModel.Size.AS_PLACED);
        assertNotNull(model);
        return model;
    }

    /** One person, drawn at rest so no procedural pose moves the probe. */
    private static Mesh person(SceneModel model, double yaw) {
        Mesh.Builder mesh = Mesh.builder(0, 0, 0, false, 1);
        model.mesh(mesh, 0, 0, 0, yaw + SceneModel.PERSON_TURN, AnimState.RUN, 0, 1,
                new float[]{0, 0, 1, 1}, 0, (s, j, p) -> AnimalModel.Pose.REST);
        return mesh.build();
    }

    /** One overlay, drawn at a walker's feet and scaled with them. */
    private static Mesh worn(String key, double speed, double phase) {
        Mesh.Builder mesh = Mesh.builder(0, 0, 0, false, 1);
        CosmeticModel.overlay(mesh, List.of(key), 0, 0, 0, 0, WalkerModel.HEIGHT,
                speed, phase, new float[]{0, 0, 1, 1});
        return mesh.build();
    }

    private static String bounds(Mesh mesh) {
        return mesh.minX() + "," + mesh.minY() + "," + mesh.minZ() + ";"
                + mesh.maxX() + "," + mesh.maxY() + "," + mesh.maxZ();
    }

    /**
     * Point the loader at a fixture folder and at <b>nothing else</b>.
     *
     * <p>{@link SceneModels#setDirectory} alone moves the folder searched
     * <em>first</em> and leaves the classpath searched after it, which is
     * exactly right for the game and wrong for a test with one file in it: this
     * repository ships a wardrobe under {@code cosmetics/<figure>/}, so a test
     * that writes one {@code wool_mittens.glb} to a temp folder and asks
     * whether the piece is modelled is answered by the real one. Every test
     * here that owns its own fixture wants only its own fixture.
     */
    private static void onlyTheFolder(Path dir) {
        SceneModels.setDirectory(dir);
        SceneModels.setSources(SceneModels.Sources.FOLDER_ONLY);
    }

    /** Put the loaders back where the rest of the suite expects to find them. */
    private static void reset() {
        AnimalModels.setDirectory(Path.of(AnimalModels.DIRECTORY));
        SceneModels.setDirectory(Path.of(SceneModels.DIRECTORY));
        SceneModels.setSources(SceneModels.Sources.FOLDER_AND_CLASSPATH);
    }
}
