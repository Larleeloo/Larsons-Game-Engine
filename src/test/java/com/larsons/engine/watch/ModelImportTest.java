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
import com.larsons.engine.watch.render.Mesh;
import com.larsons.engine.watch.render.RangerModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    private static SceneModel bake(byte[] bytes, ModelRig.Kind kind,
                                   SceneModel.Normalise normalise) {
        RawModel raw = com.larsons.engine.watch.model.GltfReader.parse(bytes, "test", null);
        assertNotNull(raw, "the fixture did not parse");
        return SceneModel.bake(raw, kind, normalise);
    }

    /** What one draw of a model covers, so a test can measure it. */
    private static Mesh draw(SceneModel model, AnimState state, double phase,
                             double scale, double headTurn) {
        Mesh.Builder mesh = Mesh.builder(0, 0, 0, false, 1);
        float[] uv = {0, 0, 1, 1};
        model.mesh(mesh, 0, 0, 0, 0, state, phase, scale, uv, headTurn);
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
                SceneModel.Normalise.METRES);
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
                SceneModel.Normalise.HEIGHT);
        assertNotNull(model);
        assertEquals(1, model.height(), 1e-6, "height should normalise to one");

        // Drawn at 1.8 m, the thing should be 1.8 m tall with its feet at zero,
        // whatever size it was modelled at.
        Mesh drawn = draw(model, AnimState.STRIKE, 0, 1.8, 0);
        assertEquals(0, drawn.minZ(), 1e-5, "the model did not stand on the ground");
        assertEquals(1.8, drawn.maxZ(), 1e-5);
    }

    @Test
    void aCreatureIsNormalisedToItsBodyLength() {
        SceneModel model = bake(gltfBytes(), ModelRig.Kind.CREATURE,
                SceneModel.Normalise.BODY_LENGTH);
        assertNotNull(model);
        assertEquals(1, model.length(), 1e-6, "body length should normalise to one");
    }

    /** Modelled at ten times the size, it still comes out the size it is drawn at. */
    @Test
    void theSizeItWasModelledAtDoesNotMatter() {
        String scaled = gltf("data:application/octet-stream;base64,"
                + Base64.getEncoder().encodeToString(scaled(10)));
        SceneModel big = bake(scaled.getBytes(StandardCharsets.UTF_8),
                ModelRig.Kind.HUMANOID, SceneModel.Normalise.HEIGHT);
        SceneModel small = bake(gltfBytes(), ModelRig.Kind.HUMANOID,
                SceneModel.Normalise.HEIGHT);
        assertNotNull(big);
        assertNotNull(small);
        assertEquals(small.height(), big.height(), 1e-6);
        assertEquals(draw(small, AnimState.STRIKE, 0, 1.8, 0).maxZ(),
                draw(big, AnimState.STRIKE, 0, 1.8, 0).maxZ(), 1e-4);
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
                SceneModel.Normalise.METRES);
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
                SceneModel.Normalise.METRES);
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
                SceneModel.Normalise.METRES);
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
    }

    /** A bone that names nothing takes its parent's joint, so detail can nest. */
    @Test
    void anUnnamedBoneInheritsItsParent() {
        String nested = gltf("data:application/octet-stream;base64,"
                + Base64.getEncoder().encodeToString(buffer()))
                .replace("\"name\": \"head\"", "\"name\": \"tuft\"");
        SceneModel model = bake(nested.getBytes(StandardCharsets.UTF_8),
                ModelRig.Kind.HUMANOID, SceneModel.Normalise.METRES);
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

    /** Put the loaders back where the rest of the suite expects to find them. */
    private static void reset() {
        AnimalModels.setDirectory(Path.of(AnimalModels.DIRECTORY));
        SceneModels.setDirectory(Path.of(SceneModels.DIRECTORY));
    }
}
