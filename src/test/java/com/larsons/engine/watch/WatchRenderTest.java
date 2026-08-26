package com.larsons.engine.watch;

import com.larsons.engine.graphics.EyeCamera;
import com.larsons.engine.graphics.draw.DrawTarget;
import com.larsons.engine.graphics.draw.RecordingTarget;
import com.larsons.engine.watch.life.AnimState;
import com.larsons.engine.watch.life.AnimalDef;
import com.larsons.engine.watch.life.AnimalModel;
import com.larsons.engine.watch.life.AnimalModels;
import com.larsons.engine.watch.life.AnimalRegistry;
import com.larsons.engine.watch.render.AnimalPortrait;
import com.larsons.engine.watch.render.Mesh;
import com.larsons.engine.watch.render.Shapes;
import com.larsons.engine.watch.render.TerrainMesher;
import com.larsons.engine.watch.render.WatchRenderer;
import com.larsons.engine.watch.world.ChunkStreamer;
import com.larsons.engine.watch.world.TerrainField;
import com.larsons.engine.watch.world.WatchChunk;
import com.larsons.engine.watch.world.WatchMaterial;
import com.larsons.engine.watch.world.WatchMaterials;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.awt.image.BufferedImage;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The renderer: triangles in, a picture out, and the several ways that goes
 * wrong quietly.
 *
 * <p>Two of these tests exist because of bugs that shipped in a screenshot and
 * looked <em>almost</em> right. The winding sign was inverted, so the world drew
 * the undersides of things and culled the ground the player was standing on —
 * a scene that still looked like terrain. And the painter's order was
 * near-to-far in one branch, which is invisible until something translucent
 * crosses something else.
 */
@Timeout(180)
class WatchRenderTest {

    private static final int WIDTH = 320, HEIGHT = 200;

    private static EyeCamera camera() {
        EyeCamera eye = new EyeCamera(WIDTH, HEIGHT);
        eye.place(0, 0, 4);
        eye.look(0, -0.2);
        return eye;
    }

    private static DrawTarget target() {
        return new RecordingTarget(WIDTH, HEIGHT);
    }

    /**
     * A wall of ground in front of the camera, at {@code distance} metres.
     *
     * <p>{@link EyeCamera}'s forward at yaw zero is {@code (sin 0, -cos 0)} —
     * <b>−y</b> — so "in front" is negative y, and the wall is wound so its
     * outward normal points back at +y. Getting either of those backwards
     * produces a test that passes for the wrong reason: geometry behind the
     * camera and geometry facing away from it both draw nothing.
     */
    private static Mesh ground(double distance, int albedo) {
        double y = -Math.abs(distance);
        Mesh.Builder mesh = Mesh.builder(0, 0, 0, false, 1);
        float[] uv = new float[4];
        WatchMaterials.uv(WatchMaterial.GRASS, uv);
        Shapes.quad(mesh, 20, y, 0, -20, y, 0, -20, y, 12, 20, y, 12, uv, albedo);
        return mesh.build();
    }

    // --- drawing at all ------------------------------------------------------------------

    /**
     * A real chunk, streamed and meshed the way the game does it, drawn.
     *
     * <p>Through {@link ChunkStreamer#loadNow} rather than
     * {@link WatchChunk#generate}: generating a chunk fills in its heights and
     * its trees, and <em>meshing</em> it is a second step the streamer's workers
     * do at a level of detail that depends on how far away it is. A test that
     * only generated would submit an empty mesh and prove nothing.
     */
    @Test
    void aFrameOfTerrainDrawsTriangles() {
        try (ChunkStreamer streamer = new ChunkStreamer(1234L)) {
            streamer.loadNow(0, 0, 1);
            WatchChunk chunk = streamer.chunkAt(0, 0);
            assertNotNull(chunk, "the streamer produced no chunk under the player");
            assertTrue(chunk.meshed(), "the chunk was generated and never meshed");

            WatchRenderer renderer = new WatchRenderer();
            DrawTarget target = target();
            EyeCamera eye = new EyeCamera(WIDTH, HEIGHT);
            // Standing off the chunk's +y side, looking back along −y at it.
            eye.place(chunk.originX() + 16, chunk.originY() + 70, chunk.highest() + 12);
            eye.look(0, -0.25);

            renderer.begin(target, eye, WIDTH, HEIGHT, WatchClock.at(0.5), 0x87CEEB, 0xB0C4DE);
            renderer.submit(chunk.groundMesh());
            renderer.flush(target);

            assertTrue(renderer.submittedTriangles() > 100,
                    "a chunk of ground meshed to " + renderer.submittedTriangles()
                            + " triangles");
            assertTrue(renderer.drawnTriangles() > 0, "nothing was drawn");
        }
    }

    /**
     * The winding sign.
     *
     * <p>Front faces come out of the projection with <em>positive</em> shoelace
     * area, and the first cull tested the other sign — which drew the world
     * inside out and culled the ground under the player's feet. Asked directly:
     * a surface facing the camera is drawn, the same surface facing away is not.
     */
    @Test
    void aSurfaceFacingTheCameraIsDrawnAndOneFacingAwayIsNot() {
        float[] uv = new float[4];
        WatchMaterials.uv(WatchMaterial.GRASS, uv);

        // Ten metres in front of the camera, which looks toward −y.
        Mesh.Builder facing = Mesh.builder(0, 0, 0, false, 1);
        Shapes.face(facing, 4, -10, 0, -4, -10, 0, 0, -10, 6, uv, 0x66AA66);
        Mesh.Builder away = Mesh.builder(0, 0, 0, false, 1);
        Shapes.face(away, -4, -10, 0, 4, -10, 0, 0, -10, 6, uv, 0x66AA66);

        assertEquals(1, drawn(facing.build()), "a face pointed at the camera was culled");
        assertEquals(0, drawn(away.build()), "a face pointed away from the camera was drawn");
    }

    private static int drawn(Mesh mesh) {
        return drawn(mesh, camera());
    }

    private static int drawn(Mesh mesh, EyeCamera eye) {
        WatchRenderer renderer = new WatchRenderer();
        DrawTarget target = target();
        renderer.begin(target, eye, WIDTH, HEIGHT, WatchClock.at(0.5), 0x87CEEB, 0xB0C4DE);
        renderer.submit(mesh);
        renderer.flush(target);
        return renderer.drawnTriangles();
    }

    /**
     * A closed box shows the faces pointed at you and no others — one face
     * seen square on, three seen from a corner, never all six and never none.
     */
    @Test
    void aClosedSolidShowsOnlyTheFacesPointedAtYou() {
        float[] uv = new float[4];
        WatchMaterials.uv(WatchMaterial.BARK, uv);
        Mesh.Builder mesh = Mesh.builder(0, 0, 0, false, 1);
        Shapes.box(mesh, 0, -12, 4, 2, 2, 2, 0, uv, 0x8B5A2B);
        Mesh box = mesh.build();
        assertEquals(12, box.triangleCount(), "a box is six quads");

        assertEquals(2, drawn(box, camera()),
                "seen square on, a box should show exactly its front face");

        // From a corner, above: three faces, six triangles.
        EyeCamera corner = new EyeCamera(WIDTH, HEIGHT);
        corner.place(9, 0, 11);
        corner.look(Math.atan2(-9, 12), -Math.atan2(7, Math.hypot(9, 12)));
        assertEquals(6, drawn(box, corner),
                "seen from a corner and above, a box should show three of its faces");
    }

    @Test
    void everyTriangleIsEitherDrawnOrCulledAndNeverBoth() {
        float[] uv = new float[4];
        WatchMaterials.uv(WatchMaterial.BARK, uv);
        Mesh.Builder mesh = Mesh.builder(0, 0, 0, false, 1);
        Shapes.box(mesh, 0, -12, 4, 2, 2, 2, 0, uv, 0x8B5A2B);
        Mesh box = mesh.build();

        WatchRenderer renderer = new WatchRenderer();
        DrawTarget target = target();
        renderer.begin(target, camera(), WIDTH, HEIGHT, WatchClock.at(0.5), 0x87CEEB, 0xB0C4DE);
        renderer.submit(box);
        renderer.flush(target);

        assertEquals(box.triangleCount(),
                renderer.drawnTriangles() + renderer.culledTriangles(),
                "triangles went missing between submitting and drawing");
    }

    // --- what is behind you --------------------------------------------------------------

    @Test
    void nothingBehindTheCameraIsDrawn() {
        float[] uv = new float[4];
        WatchMaterials.uv(WatchMaterial.GRASS, uv);
        // The camera looks toward −y from the origin, so +y is behind it. Wound
        // to face the camera, so the only reason it can be missing is position.
        Mesh.Builder behind = Mesh.builder(0, 0, 0, false, 1);
        Shapes.quad(behind, -20, 40, 0, 20, 40, 0, 20, 40, 12, -20, 40, 12, uv, 0x66AA66);

        WatchRenderer renderer = new WatchRenderer();
        DrawTarget target = target();
        renderer.begin(target, camera(), WIDTH, HEIGHT, WatchClock.at(0.5), 0x87CEEB, 0xB0C4DE);
        renderer.submit(behind.build());
        renderer.flush(target);

        assertEquals(0, renderer.drawnTriangles(),
                "geometry behind the camera reached the target");
    }

    @Test
    void somethingBeyondTheFogIsNotDrawn() {
        WatchRenderer renderer = new WatchRenderer();
        renderer.setFogRange(20, 40);
        DrawTarget target = target();
        renderer.begin(target, camera(), WIDTH, HEIGHT, WatchClock.at(0.5), 0x87CEEB, 0xB0C4DE);
        renderer.submit(ground(500, 0x66AA66));
        renderer.flush(target);

        assertEquals(0, renderer.drawnTriangles(),
                "something five hundred metres past the fog was drawn anyway");
        assertEquals(40.0, renderer.fogEnd(), 1e-9);
    }

    // --- order ---------------------------------------------------------------------------

    /**
     * Far to near, because there is no depth buffer on this path.
     *
     * <p>Recorded rather than reasoned about: the target keeps the order the
     * calls arrived in, so the two grounds' colours say which was painted
     * first. Near-to-far is invisible on opaque geometry that never overlaps and
     * ruinous the moment anything does.
     */
    @Test
    void thePainterWorksFromTheBackForwards() {
        RecordingTarget target = new RecordingTarget(WIDTH, HEIGHT);
        WatchRenderer renderer = new WatchRenderer();
        renderer.setFogRange(400, 900);
        renderer.begin(target, camera(), WIDTH, HEIGHT, WatchClock.at(0.5), 0x87CEEB, 0xB0C4DE);
        // begin paints the sky, and the sky is itself a large blue rectangle —
        // left in, it is the first "blue" in the recording and the test reads
        // the order backwards. Only the two grounds are being ordered here.
        target.clearRecording();
        // Submitted near first, deliberately: the renderer has to reorder them.
        renderer.submit(ground(30, 0x0000FF));
        renderer.submit(ground(90, 0xFF0000));
        renderer.flush(target);

        assertEquals(4, renderer.drawnTriangles(), "both grounds should be visible");
        int firstRed = indexOfColour(target, 0xFF0000);
        int firstBlue = indexOfColour(target, 0x0000FF);
        assertTrue(firstRed >= 0 && firstBlue >= 0,
                "one of the two grounds never reached the target");
        assertTrue(firstRed < firstBlue,
                "the near ground (blue) was painted before the far one (red) — the painter "
                        + "is running front to back and near things will be overdrawn");
    }

    /** The first shape whose colour is close to {@code rgb}, allowing for shading. */
    private static int indexOfColour(RecordingTarget target, int rgb) {
        var shapes = target.ofType(RecordingTarget.Cmd.Shape.class);
        for (int i = 0; i < shapes.size(); i++) {
            int colour = shapes.get(i).argb();
            int r = (colour >> 16) & 0xFF, g = (colour >> 8) & 0xFF, b = colour & 0xFF;
            int wr = (rgb >> 16) & 0xFF, wg = (rgb >> 8) & 0xFF, wb = rgb & 0xFF;
            // Shading and fog scale a channel; the dominant one still dominates.
            boolean matches = (wr == 0 || (r > g && r > b))
                    && (wb == 0 || (b > r && b > g))
                    && (wg == 0 || (g > r && g > b));
            if (matches) return i;
        }
        return -1;
    }

    /**
     * The world is drawn hard-edged, and smoothing is handed back afterwards.
     *
     * <p>Both halves matter. Antialiasing a field of abutting triangles leaves
     * a pale hairline along every shared edge — a lattice over the ground that
     * crawls as you walk — and costs most of the frame doing it: the same
     * frame measured 288 ms smooth (with the stroke that was covering the
     * seam) against 35 ms hard-edged. Leaving smoothing off afterwards would
     * hand the HUD jagged text.
     */
    @Test
    void theWorldPassTurnsSmoothingOffAndPutsItBack() {
        RecordingTarget target = new RecordingTarget(WIDTH, HEIGHT);
        WatchRenderer renderer = new WatchRenderer();
        renderer.begin(target, camera(), WIDTH, HEIGHT, WatchClock.at(0.5),
                0x87CEEB, 0xB0C4DE);
        renderer.submit(ground(30, 0x66AA66));
        renderer.flush(target);
        assertTrue(renderer.drawnTriangles() > 0, "nothing was drawn, so nothing was proved");

        // Every polygon of the world has to fall inside an "off" window.
        boolean smoothing = true;
        int filled = 0;
        for (RecordingTarget.Cmd cmd : target.commands()) {
            if (cmd instanceof RecordingTarget.Cmd.Hint hint) {
                smoothing = hint.on();
            } else if (cmd instanceof RecordingTarget.Cmd.Shape shape
                    && "fillPolygon".equals(shape.op())) {
                assertFalse(smoothing,
                        "a world polygon was filled with smoothing on — that is the seam");
                filled++;
            }
        }
        assertTrue(filled > 0, "no polygons reached the target");
        assertTrue(smoothing, "smoothing was left off, so the HUD comes out jagged");
    }

    // --- meshes ----------------------------------------------------------------------------

    @Test
    void anEmptyMeshDrawsNothingAndDoesNotThrow() {
        WatchRenderer renderer = new WatchRenderer();
        DrawTarget target = target();
        renderer.begin(target, camera(), WIDTH, HEIGHT, WatchClock.at(0.5), 0x87CEEB, 0xB0C4DE);
        renderer.submit(Mesh.empty(0, 0, 0));
        renderer.flush(target);
        assertEquals(0, renderer.drawnTriangles());
    }

    @Test
    void aMeshKnowsItsOwnBoundsAndSize() {
        float[] uv = new float[4];
        WatchMaterials.uv(WatchMaterial.BARK, uv);
        Mesh.Builder builder = Mesh.builder(0, 0, 0, false, 1);
        Shapes.box(builder, 5, 6, 7, 1, 2, 3, 0, uv, 0x8B5A2B);
        Mesh mesh = builder.build();

        assertEquals(4.0, mesh.minX(), 1e-4);
        assertEquals(6.0, mesh.maxX(), 1e-4);
        assertEquals(4.0, mesh.minY(), 1e-4);
        assertEquals(8.0, mesh.maxY(), 1e-4);
        assertEquals(4.0, mesh.minZ(), 1e-4);
        assertEquals(10.0, mesh.maxZ(), 1e-4);
        assertEquals(mesh.triangleCount() * Mesh.VERTICES_PER_TRIANGLE, mesh.vertexCount());
        assertTrue(mesh.byteCount() > 0);
        assertFalse(mesh.isEmpty());
    }

    @Test
    void aFaceTurnedTowardTheLightIsBrighterThanOneTurnedAway() {
        float[] uv = new float[4];
        WatchMaterials.uv(WatchMaterial.GRASS, uv);

        Mesh.Builder up = Mesh.builder(0, 0, 0, false, 1);
        Shapes.face(up, 0, 0, 0, 2, 0, 0, 0, 2, 0, uv, 0x808080);
        Mesh.Builder down = Mesh.builder(0, 0, 0, false, 1);
        Shapes.face(down, 0, 0, 0, 0, 2, 0, 2, 0, 0, uv, 0x808080);

        assertTrue(brightest(up.build()) > brightest(down.build()),
                "a face looking at the sky is no brighter than one looking at the ground");
    }

    private static int brightest(Mesh mesh) {
        int best = 0;
        for (int colour : mesh.colours()) {
            int luma = ((colour >> 16) & 0xFF) + ((colour >> 8) & 0xFF) + (colour & 0xFF);
            best = Math.max(best, luma);
        }
        return best;
    }

    /**
     * Shading darkens and lightens without wrapping. An unclamped multiply is
     * the classic way a bright surface turns black at the top of the curve.
     */
    @Test
    void shadingStaysInsideTheColourSpace() {
        for (int channel = 0; channel <= 0xFF; channel += 17) {
            int albedo = (channel << 16) | (channel << 8) | channel;
            for (double lit = 0; lit <= 3.0; lit += 0.1) {
                int shaded = TerrainMesher.shade(albedo, lit);
                for (int shift : new int[]{16, 8, 0}) {
                    int value = (shaded >> shift) & 0xFF;
                    assertTrue(value >= 0 && value <= 0xFF,
                            "channel " + value + " from " + channel + " at " + lit);
                }
                if (lit < 1.0 && channel > 0) {
                    assertTrue(((shaded >> 16) & 0xFF) <= channel + 1,
                            "shading by " + lit + " made " + channel + " brighter");
                }
            }
        }
    }

    // --- the atlas ------------------------------------------------------------------------

    @Test
    void everyMaterialHasItsOwnTileAndItsOwnColour() {
        BufferedImage atlas = WatchMaterials.atlas();
        assertNotNull(atlas);

        Set<String> boxes = new HashSet<>();
        Set<Integer> shades = new HashSet<>();
        float[] uv = new float[4];
        for (WatchMaterial material : WatchMaterial.values()) {
            WatchMaterials.uv(material, uv);
            assertTrue(uv[0] >= 0 && uv[2] <= 1 && uv[1] >= 0 && uv[3] <= 1,
                    material + " maps outside the atlas");
            assertTrue(uv[0] < uv[2] && uv[1] < uv[3], material + " has an inside-out tile");
            assertTrue(boxes.add(uv[0] + ":" + uv[1]),
                    material + " shares a tile with another material");
            shades.add(WatchMaterials.shade(material));
        }
        assertTrue(shades.size() > WatchMaterial.values().length / 2,
                "only " + shades.size() + " distinct colours across "
                        + WatchMaterial.values().length + " materials");
    }

    @Test
    void theAtlasIsBuiltOnceAndItsRevisionMovesOnlyWhenItIsRebuilt() {
        int revision = WatchMaterials.revision();
        assertEquals(revision, WatchMaterials.revision(), "asking twice rebuilt the atlas");
        WatchMaterials.invalidate();
        assertTrue(WatchMaterials.revision() > revision,
                "a rescan did not bump the revision, so nothing would re-upload");
    }

    // --- portraits ---------------------------------------------------------------------

    /**
     * A page's picture is the model, and the first version of it was blank: the
     * camera was moved close to frame a small species and ended up inside
     * {@link EyeCamera#NEAR}, clipping every triangle away. Scaling the model
     * instead cannot do that, and this is what says so — across the size range,
     * from a bee hummingbird to an elk.
     */
    @Test
    void everyPortraitHasSomethingInIt() {
        AnimalDef smallest = null, largest = null;
        for (AnimalDef def : AnimalRegistry.all()) {
            if (smallest == null || def.bodyLength() < smallest.bodyLength()) smallest = def;
            if (largest == null || def.bodyLength() > largest.bodyLength()) largest = def;
        }

        for (AnimalDef def : new AnimalDef[]{smallest, largest,
                AnimalRegistry.all().get(0), AnimalRegistry.all().get(700)}) {
            BufferedImage portrait = AnimalPortrait.of(def, 96, 0x1B2620);
            assertNotNull(portrait, def.key());
            assertEquals(96, portrait.getWidth());

            int painted = 0;
            for (int y = 0; y < 96; y++) {
                for (int x = 0; x < 96; x++) {
                    if ((portrait.getRGB(x, y) & 0xFFFFFF) != 0x1B2620) painted++;
                }
            }
            double share = painted / (96.0 * 96.0);
            assertTrue(share > 0.02, def.key() + " (" + def.bodyLength() + " m) fills "
                    + Math.round(share * 100) + "% of its portrait — the page is blank");
            assertTrue(share < 0.98, def.key() + " fills its whole portrait");
        }
    }

    @Test
    void aPortraitIsCachedRatherThanRedrawn() {
        AnimalDef def = AnimalRegistry.all().get(42);
        BufferedImage first = AnimalPortrait.of(def, 64, 0x000000);
        assertTrue(first == AnimalPortrait.of(def, 64, 0x000000),
                "turning back to a page redrew the animal");
        AnimalPortrait.invalidate();
        assertFalse(first == AnimalPortrait.of(def, 64, 0x000000),
                "a texture pack rescan did not clear the portraits");
    }

    // --- animals in the world ---------------------------------------------------------

    @Test
    void anAnimalMeshedIntoTheWorldIsDrawn() {
        AnimalDef def = AnimalRegistry.all().get(11);
        AnimalModels.Loaded model = AnimalModels.of(def);
        Mesh.Builder builder = Mesh.builder(0, 0, 0, false, 1);
        model.geometry().mesh(builder, def, 0, -12, 0, 0, AnimState.IDLE, 0, 4,
                AnimalModel.procedural());

        WatchRenderer renderer = new WatchRenderer();
        DrawTarget target = target();
        renderer.begin(target, camera(), WIDTH, HEIGHT, WatchClock.at(0.5), 0x87CEEB, 0xB0C4DE);
        renderer.submit(builder.build());
        renderer.flush(target);

        assertTrue(renderer.drawnTriangles() > 6,
                def.key() + " drew " + renderer.drawnTriangles() + " triangles in front of "
                        + "the camera");
    }

    @Test
    void nightIsDarkerThanNoonForTheSameGeometry() {
        assertTrue(litness(0.5) > litness(0.0),
                "the same hillside is no brighter at noon than at midnight");
    }

    private static int litness(double timeOfDay) {
        RecordingTarget target = new RecordingTarget(WIDTH, HEIGHT);
        WatchRenderer renderer = new WatchRenderer();
        renderer.begin(target, camera(), WIDTH, HEIGHT, WatchClock.at(timeOfDay),
                0x87CEEB, 0xB0C4DE);
        renderer.submit(ground(30, 0x66AA66));
        renderer.flush(target);

        int best = 0;
        for (var shape : target.ofType(RecordingTarget.Cmd.Shape.class)) {
            int colour = shape.argb();
            best = Math.max(best, ((colour >> 16) & 0xFF) + ((colour >> 8) & 0xFF)
                    + (colour & 0xFF));
        }
        return best;
    }
}
