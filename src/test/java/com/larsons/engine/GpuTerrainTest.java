package com.larsons.engine;

import com.larsons.engine.config.GameProfile;
import com.larsons.engine.graphics.EyeCamera;
import com.larsons.engine.graphics.Frustum;
import com.larsons.engine.graphics.Mat4;
import com.larsons.engine.graphics.chunk.BlockAtlas;
import com.larsons.engine.graphics.chunk.DetailReach;
import com.larsons.engine.graphics.chunk.SectionMesh;
import com.larsons.engine.graphics.chunk.SectionMesher;
import com.larsons.engine.graphics.chunk.SectionRenderList;
import com.larsons.engine.graphics.chunk.SectionVisibility;
import com.larsons.engine.graphics.chunk.TerrainSections;
import com.larsons.engine.level.Level;
import com.larsons.engine.level.LevelFormat;
import com.larsons.engine.world.BlockRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The depth-buffered terrain path: everything about it that can be checked
 * without a graphics card, which is deliberately almost all of it.
 *
 * <p><b>The split is the design.</b> Deciding what the world looks like —
 * which sections exist, which faces of them are exposed, how occluded each
 * corner is, which sections the camera can see and in what order — is CPU work
 * in {@code com.larsons.engine.graphics.chunk}, and every line of it is
 * exercised here. What is left for the GL module is uploading arrays and
 * issuing draw calls. That is not how the code was split by accident: a
 * renderer whose correctness lives in a shader is a renderer nobody can test.
 */
@Timeout(120)
class GpuTerrainTest {

    private static final int TILE = 32;
    private static final int W = 1280, H = 720;

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    // --- the camera, twice ----------------------------------------------------------

    /**
     * <b>The matrix and the painter put a point on the same pixel.</b>
     *
     * <p>The most important test here, because it is the one thing that cannot
     * be seen by looking at either backend on its own. The GPU path projects
     * with a matrix and the Java2D path projects with arithmetic; a player who
     * switches between them — or an author checking a level in the editor's
     * preview and then playing it — must not see the picture move. Anything
     * that disagrees shows up here as pixels rather than as a shrug.
     */
    @Test
    void theMatrixAndThePainterAgreeAboutWhereAPointIs() {
        EyeCamera eye = new EyeCamera(W, H);
        eye.place(1000, 2000, 300);
        eye.look(0.7, -0.2);

        Mat4 viewProjection = Mat4.perspective(eye.fov(), W / (double) H,
                EyeCamera.NEAR, 10_000).times(Mat4.view(eye));
        double[] clip = new double[4];
        double[] seen = new double[3];

        for (double[] point : new double[][] {
                {1000, 2100, 300}, {1200, 2400, 420}, {700, 2600, 120}, {1030, 2050, 305}}) {
            eye.toEye(point[0], point[1], point[2], seen);
            if (seen[2] <= EyeCamera.NEAR) continue;
            double painterX = eye.screenX(seen[0], seen[2]);
            double painterY = eye.screenY(seen[1], seen[2]);

            viewProjection.transform(point[0], point[1], point[2], clip);
            assertTrue(clip[3] > 0, "a point in front of the eye has positive w");
            double matrixX = (clip[0] / clip[3] + 1) / 2 * W;
            double matrixY = (1 - clip[1] / clip[3]) / 2 * H;

            assertEquals(painterX, matrixX, 0.01,
                    "the two backends put this point on different columns of pixels");
            assertEquals(painterY, matrixY, 0.01,
                    "…and on different rows");
        }
    }

    /**
     * <b>Which way a face pointing at you comes out wound — the whole of the
     * back-face cull, decided here rather than by a driver constant somebody
     * guessed at.</b>
     *
     * <p>{@code SectionMesher} winds every face counter-clockwise seen from
     * outside, which is the textbook front face and would reach the window
     * counter-clockwise through the textbook projection. This engine's is not
     * the textbook one: its eye space has <b>+Z forward</b>, because
     * {@link EyeCamera} does, and that single sign reverses the answer. So the
     * measurement is made here, on the real mesh through the real matrices, and
     * {@link Mat4#FRONT_FACES_WIND_CLOCKWISE} — which is what the GL backend
     * hands {@code glFrontFace} — has to agree with it.
     *
     * <p><b>Why this is worth a test rather than a comment.</b> Getting it
     * backwards does not draw a mirrored world or a black screen, either of
     * which would be obvious. It culls precisely the faces that were pointing at
     * the player and keeps the ones that were pointing away: a flat plain draws
     * nothing at all, and a lone block shows the two or three faces on its far
     * side. That reads as "the ground is transparent", which is a very long way
     * from "a cull constant is inverted".
     */
    @Test
    void aFacePointingAtTheEyeWindsTheWayTheCullIsSetFor() {
        Level lvl = bare();
        lvl.setTile(4, 4, 5, stone(lvl));
        float[] v = SectionMesher.build(lvl, atlas(), 0, 0, 0).opaqueVertices();
        assertTrue(v.length > 0, "the block has to have meshed for this to mean anything");

        double cx = 4.5 * TILE, cy = 4.5 * TILE, cz = 4.5 * TILE;
        EyeCamera eye = new EyeCamera(W, H);
        // Off one corner and above it, so some faces point this way and some
        // point away — a camera square onto one face would prove only half of it.
        eye.place(cx - 6 * TILE, cy - 5 * TILE, cz + 4 * TILE);
        aim(eye, cx, cy, cz);
        Mat4 viewProjection = Mat4.perspective(eye.fov(), W / (double) H,
                EyeCamera.NEAR, 10_000).times(Mat4.view(eye));

        int towardsClockwise = 0, towardsCounter = 0, awayClockwise = 0, awayCounter = 0;
        int stride = SectionMesh.FLOATS_PER_VERTEX;
        double[] clip = new double[4];
        for (int t = 0; t + 3 * stride <= v.length; t += 3 * stride) {
            double[][] p = new double[3][];
            double[][] ndc = new double[3][];
            boolean inFront = true;
            for (int i = 0; i < 3; i++) {
                int at = t + i * stride;
                p[i] = new double[] {v[at], v[at + 1], v[at + 2]};
                viewProjection.transform(p[i][0], p[i][1], p[i][2], clip);
                if (clip[3] <= 0) { inFront = false; break; }
                ndc[i] = new double[] {clip[0] / clip[3], clip[1] / clip[3]};
            }
            if (!inFront) continue;

            // The triangle's own normal, and whether the eye is on its outward
            // side. This is the ground truth the projection has to preserve.
            double[] n = cross(p[0], p[1], p[2]);
            boolean towards = n[0] * (eye.x() - p[0][0]) + n[1] * (eye.y() - p[0][1])
                    + n[2] * (eye.z() - p[0][2]) > 0;
            // Signed area in normalised device coordinates, where y is up —
            // exactly the quantity GL computes to decide which way a triangle
            // faces. Negative is clockwise.
            double area = (ndc[1][0] - ndc[0][0]) * (ndc[2][1] - ndc[0][1])
                    - (ndc[2][0] - ndc[0][0]) * (ndc[1][1] - ndc[0][1]);
            if (Math.abs(area) < 1e-12) continue;       // edge-on, decides nothing
            if (towards) {
                if (area < 0) towardsClockwise++; else towardsCounter++;
            } else {
                if (area < 0) awayClockwise++; else awayCounter++;
            }
        }

        assertTrue(towardsClockwise + towardsCounter > 0, "some face has to point at the eye");
        assertTrue(awayClockwise + awayCounter > 0, "and some has to point away");
        assertEquals(0, towardsCounter,
                "faces pointing at the eye must all wind the same way, and "
                        + towardsCounter + " of them went the other way");
        assertEquals(0, awayClockwise, "and so must the ones pointing away");
        assertEquals(Mat4.FRONT_FACES_WIND_CLOCKWISE, towardsClockwise > 0,
                "Mat4.FRONT_FACES_WIND_CLOCKWISE is what the GL backend passes to "
                        + "glFrontFace, and it now disagrees with the geometry: the "
                        + "cull would keep exactly the faces pointing away from the "
                        + "player");
    }

    /** {@code (b − a) × (c − a)}, a triangle's own outward normal. */
    private static double[] cross(double[] a, double[] b, double[] c) {
        double ux = b[0] - a[0], uy = b[1] - a[1], uz = b[2] - a[2];
        double wx = c[0] - a[0], wy = c[1] - a[1], wz = c[2] - a[2];
        return new double[] {uy * wz - uz * wy, uz * wx - ux * wz, ux * wy - uy * wx};
    }

    /** Point the camera at a world position, whatever it is standing on. */
    private static void aim(EyeCamera eye, double tx, double ty, double tz) {
        double dx = tx - eye.x(), dy = ty - eye.y(), dz = tz - eye.z();
        eye.look(Math.atan2(dx, -dy), Math.atan2(dz, Math.hypot(dx, dy)));
    }

    /** Depth comes out inside the buffer's range, and grows with distance. */
    @Test
    void depthIsMonotonicAndInsideTheBuffer() {
        double near = EyeCamera.ndcDepth(EyeCamera.NEAR, 5000);
        double mid = EyeCamera.ndcDepth(500, 5000);
        double far = EyeCamera.ndcDepth(5000, 5000);
        assertEquals(-1, near, 1e-6, "the near plane is the front of the buffer");
        assertEquals(1, far, 1e-6, "and the far plane is the back of it");
        assertTrue(mid > near && mid < far, "and everything between is between");
    }

    /** The frustum keeps what is in front and drops what is behind or too far. */
    @Test
    void theFrustumKeepsWhatTheCameraCanSee() {
        EyeCamera eye = new EyeCamera(W, H);
        eye.place(0, 0, 0);
        eye.look(0, 0);   // looking along −y, which is this engine's north
        Frustum frustum = Frustum.of(Mat4.perspective(eye.fov(), W / (double) H,
                EyeCamera.NEAR, 1000).times(Mat4.view(eye)));

        assertTrue(frustum.boxVisible(-50, -500, -50, 50, -400, 50),
                "straight ahead is visible");
        assertFalse(frustum.boxVisible(-50, 400, -50, 50, 500, 50),
                "straight behind is not");
        assertFalse(frustum.boxVisible(-50, -5000, -50, 50, -4000, 50),
                "and past the far plane is not");
        assertFalse(frustum.boxVisible(4000, -500, -50, 4100, -400, 50),
                "nor is a long way off to the side");
    }

    // --- cave culling ---------------------------------------------------------------

    /** A section with nothing in it lets sight through in every direction. */
    @Test
    void anEmptySectionSeesEverything() {
        SectionVisibility open = SectionVisibility.of(new boolean[cells()]);
        for (int a = 0; a < SectionVisibility.FACES; a++) {
            for (int b = 0; b < SectionVisibility.FACES; b++) {
                assertTrue(open.canSee(a, b), "empty: " + a + " should see " + b);
            }
        }
    }

    /** A section packed solid stops sight dead — which is the whole saving. */
    @Test
    void aSolidSectionSeesNothing() {
        boolean[] solid = new boolean[cells()];
        java.util.Arrays.fill(solid, true);
        SectionVisibility packed = SectionVisibility.of(solid);
        for (int a = 0; a < SectionVisibility.FACES; a++) {
            for (int b = 0; b < SectionVisibility.FACES; b++) {
                assertFalse(packed.canSee(a, b), "solid: " + a + " must not see " + b);
            }
        }
    }

    /**
     * <b>A tunnel connects its two ends and nothing else.</b> The case the whole
     * technique exists for: standing in a corridor, the sections through the
     * rock either side of it are in front of the camera and must not be drawn.
     */
    @Test
    void aTunnelConnectsOnlyItsOwnEnds() {
        int size = SectionMesh.SIZE;
        boolean[] solid = new boolean[cells()];
        java.util.Arrays.fill(solid, true);
        // One row of empty cells straight through, west to east.
        int y = size / 2, z = size / 2;
        for (int x = 0; x < size; x++) solid[(z * size + y) * size + x] = false;

        SectionVisibility tunnel = SectionVisibility.of(solid);
        assertTrue(tunnel.canSee(SectionVisibility.WEST, SectionVisibility.EAST),
                "you can see along a tunnel");
        assertTrue(tunnel.canSee(SectionVisibility.EAST, SectionVisibility.WEST),
                "and back along it");
        assertFalse(tunnel.canSee(SectionVisibility.WEST, SectionVisibility.UP),
                "but not out through the roof of one");
        assertFalse(tunnel.canSee(SectionVisibility.NORTH, SectionVisibility.SOUTH),
                "and not across the rock it is cut through");
    }

    private static int cells() {
        return SectionMesh.SIZE * SectionMesh.SIZE * SectionMesh.SIZE;
    }

    // --- meshing --------------------------------------------------------------------

    /** A block on its own has six faces; two triangles each is thirty-six vertices. */
    @Test
    void aLoneBlockIsSixFaces() {
        Level lvl = bare();
        lvl.setTile(4, 4, 5, stone(lvl));
        SectionMesh mesh = SectionMesher.build(lvl, atlas(), 0, 0, 0);
        assertEquals(6 * SectionMesh.VERTICES_PER_QUAD, mesh.opaqueVertexCount(),
                "six faces, two triangles each");
        assertEquals(0, mesh.translucentVertexCount(), "and none of it see-through");
    }

    /** A block with a block on top of it has five, and the buried one has none. */
    @Test
    void aBuriedBlockHasNoFaces() {
        Level lvl = bare();
        int stone = stone(lvl);
        // A 3×3×3 of stone: the middle block is buried, the rest are not.
        for (int c = 3; c <= 5; c++) {
            for (int r = 3; r <= 5; r++) {
                for (int l = 4; l <= 6; l++) lvl.setTile(c, r, l, stone);
            }
        }
        SectionMesh mesh = SectionMesher.build(lvl, atlas(), 0, 0, 0);
        int faces = mesh.opaqueVertexCount() / SectionMesh.VERTICES_PER_QUAD;
        assertEquals(6 * 9, faces,
                "a cube of twenty-seven blocks shows nine faces on each of six sides");
    }

    /** Water goes in the buffer that is drawn after everything opaque. */
    @Test
    void seeThroughBlocksGoInTheirOwnBuffer() {
        Level lvl = bare();
        lvl.setTile(4, 4, 5, lvl.blocks.get("water").id());
        SectionMesh mesh = SectionMesher.build(lvl, atlas(), 0, 0, 0);
        assertEquals(0, mesh.opaqueVertexCount(), "water is not opaque geometry");
        assertEquals(6 * SectionMesh.VERTICES_PER_QUAD, mesh.translucentVertexCount());
    }

    /**
     * <b>Ambient occlusion darkens the corner that is closed in.</b> The
     * signature of smooth lighting, and the thing that makes a voxel world read
     * as having corners rather than as a grid of flat squares.
     */
    @Test
    void ambientOcclusionDarkensAClosedCorner() {
        Level open = bare();
        int stoneId = stone(open);
        open.setTile(6, 6, 5, stoneId);
        int[] flat = SectionMesher.build(open, atlas(), 0, 0, 0).opaqueColours();
        // A block with nothing near it is lit by its faces alone: four
        // brightnesses, one per face direction, and every vertex of a face the
        // same. Anything more than four can only have come from occlusion.
        assertEquals(4, levels(flat),
                "a lone block has one brightness per face and no occlusion at all");

        Level tucked = bare();
        tucked.setTile(6, 6, 5, stoneId);
        // A block overhanging its eastern edge, a layer up: the two corners of
        // its top face on that side are closed in and have to come out darker.
        tucked.setTile(7, 6, 6, stoneId);
        int[] shaded = SectionMesher.build(tucked, atlas(), 0, 0, 0).opaqueColours();
        assertTrue(levels(shaded) > 4,
                "an overhang has to darken the vertices under it, and did not: "
                        + levels(shaded) + " brightnesses");
    }

    /**
     * <b>The level's painted floor is geometry here too, and it has to be.</b>
     *
     * <p>Layer 0 is a lid at {@code z = 0} with no thickness rather than a cube,
     * so it falls outside the layer-1-and-up loop the rest of the mesher runs.
     * {@code SolidPainter} used to draw it and stands down the moment a
     * depth-buffered backend takes the blocks over — so if nothing picked it up
     * here, every column whose only content is the floor would be a hole through
     * the world, which on a level built by hand is the entire ground.
     */
    @Test
    void thePaintedFloorIsMeshedLikeAnythingElse() {
        int size = SectionMesh.SIZE;
        Level lvl = bare();
        lvl.fillFloor(lvl.blocks.get("stone_path").id());

        SectionMesh floor = SectionMesher.build(lvl, atlas(), 0, 0, 0);
        assertEquals(size * size * SectionMesh.VERTICES_PER_QUAD, floor.opaqueVertexCount(),
                "every cell of the section's floor is one upward quad");
        assertTrue(SectionMesher.build(lvl, atlas(), 0, 1, 0).isEmpty(),
                "and only the bottom section has a floor under it");

        // A block standing on the floor hides the tile it is standing on — the
        // same exposure rule as any other upward face — and contributes five of
        // its own, its underside being the floor's business rather than its.
        lvl.setTile(4, 4, 1, stone(lvl));
        SectionMesh stood = SectionMesher.build(lvl, atlas(), 0, 0, 0);
        assertEquals((size * size - 1 + 5) * SectionMesh.VERTICES_PER_QUAD,
                stood.opaqueVertexCount(),
                "the covered lid goes and the block's five exposed faces arrive");
    }

    /** A section of nothing meshes to nothing, and says so. */
    @Test
    void anEmptySectionMeshesToNothing() {
        SectionMesh mesh = SectionMesher.build(bare(), atlas(), 0, 0, 0);
        assertTrue(mesh.isEmpty());
        assertEquals(0, mesh.opaqueVertexCount());
    }

    /** Plants are not cubes and are left to the painter that can turn them. */
    @Test
    void plantsAreNotMeshed() {
        Level lvl = bare();
        lvl.setTile(4, 4, 5, lvl.blocks.get("flower_red").id());
        SectionMesh mesh = SectionMesher.build(lvl, atlas(), 0, 0, 0);
        assertTrue(mesh.isEmpty(), "a flower is a billboard, not geometry to keep");
    }

    /** How many distinct brightnesses a mesh's vertices carry. */
    private static int levels(int[] colours) {
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (int argb : colours) seen.add((argb >> 16) & 0xFF);
        return seen.size();
    }

    // --- the render walk ------------------------------------------------------------

    /**
     * On open ground the walk reaches what the frustum allows, and the list it
     * hands back is ordered near to far — which is what an opaque pass wants,
     * because a near section drawn first spares the shading of everything
     * behind it.
     */
    @Test
    void theWalkReachesOpenGroundNearestFirst() throws Exception {
        Level lvl = bare(160, 160);
        lvl.fillFloor(lvl.blocks.get("stone_path").id());
        for (int c = 0; c < 160; c++) {
            for (int r = 0; r < 160; r++) lvl.setTile(c, r, 1, stone(lvl));
        }
        TerrainSections sections = new TerrainSections(lvl, atlas());
        try {
            EyeCamera eye = new EyeCamera(W, H);
            eye.place(80 * TILE, 140 * TILE, 3 * TILE);
            eye.look(0, -0.1);
            SectionRenderList list = null;
            for (int i = 0; i < 300; i++) {
                list = walk(sections, eye, lvl, 60 * TILE);
                if (sections.buildingCount() == 0 && i > 20) break;
                Thread.sleep(10);
            }
            assertNotNull(list);
            assertFalse(list.visible().isEmpty(), "an open plain has sections to draw");

            double previous = -1;
            for (SectionRenderList.Visible v : list.visible()) {
                double dx = (v.sx() + 0.5) * SectionMesh.SIZE * TILE - eye.x();
                double dy = (v.sz() + 0.5) * SectionMesh.SIZE * TILE - eye.y();
                double distance = Math.sqrt(dx * dx + dy * dy);
                // Breadth-first is near-to-far in *steps*, which is near enough
                // to near-to-far in distance for the depth buffer's purposes;
                // what must not happen is the far half arriving first.
                if (previous > 0) {
                    assertTrue(distance > previous - SectionMesh.SIZE * TILE * 2,
                            "the walk went backwards: " + distance + " after " + previous);
                }
                previous = distance;
            }
        } finally {
            sections.close();
        }
    }

    /**
     * <b>A sealed room draws the room.</b>
     *
     * <p>The case the cave cull exists for, and the one no frustum can find:
     * standing in a chamber cut out of solid rock, the world outside it is
     * squarely in front of the camera and none of it can be seen. The sections
     * around the room have no empty cell in them at all, so they connect
     * nothing to anything and the walk stops at the walls.
     */
    @Test
    void aSealedRoomDrawsOnlyTheRoom() throws Exception {
        Level rock = solidWorld(true);
        Level plain = solidWorld(false);
        TerrainSections roomSections = new TerrainSections(rock, atlas());
        TerrainSections plainSections = new TerrainSections(plain, atlas());
        try {
            EyeCamera eye = new EyeCamera(W, H);
            eye.place(80 * TILE, 80 * TILE, 3 * TILE);
            eye.look(0, 0);
            int room = settled(roomSections, eye, rock);
            int open = settled(plainSections, eye, plain);
            assertTrue(room > 0, "the room itself is drawn");
            assertTrue(room * 4 < open,
                    "sealed in rock the walk has to stop at the walls: " + room
                            + " sections against " + open + " on the open plain");
        } finally {
            roomSections.close();
            plainSections.close();
        }
    }

    /**
     * Either solid rock with a room cut in it, or the same ground with open sky
     * over it — the two ends of what the walk can be asked to do.
     */
    private static Level solidWorld(boolean sealed) {
        Level lvl = bare(160, 160);
        int stone = stone(lvl);
        lvl.fillFloor(lvl.blocks.get("stone_path").id());
        if (!sealed) {
            for (int c = 0; c < 160; c++) {
                for (int r = 0; r < 160; r++) lvl.setTile(c, r, 1, stone);
            }
            return lvl;
        }
        for (int c = 0; c < 160; c++) {
            for (int r = 0; r < 160; r++) {
                for (int l = 1; l <= SectionMesh.SIZE; l++) lvl.setTile(c, r, l, stone);
            }
        }
        // A chamber around the camera, well inside one section.
        for (int c = 76; c <= 84; c++) {
            for (int r = 76; r <= 84; r++) {
                for (int l = 2; l <= 6; l++) lvl.setTile(c, r, l, 0);
            }
        }
        return lvl;
    }

    /** Walk until the meshes have arrived from the workers, then count. */
    private static int settled(TerrainSections sections, EyeCamera eye, Level lvl)
            throws InterruptedException {
        int count = 0;
        for (int i = 0; i < 400; i++) {
            count = walk(sections, eye, lvl, 90 * TILE, 1).visible().size();
            if (sections.buildingCount() == 0 && i > 30) break;
            Thread.sleep(10);
        }
        return count;
    }

    private static SectionRenderList walk(TerrainSections sections, EyeCamera eye,
                                          Level lvl, double reach) {
        return walk(sections, eye, lvl, reach,
                (lvl.layerCount() + SectionMesh.SIZE - 1) / SectionMesh.SIZE);
    }

    private static SectionRenderList walk(TerrainSections sections, EyeCamera eye,
                                          Level lvl, double reach, int tall) {
        Mat4 projection = Mat4.perspective(eye.fov(), W / (double) H, EyeCamera.NEAR, 20_000);
        Frustum frustum = Frustum.of(projection.times(Mat4.view(eye)));
        SectionRenderList list = new SectionRenderList();
        list.build(sections, frustum, TILE, eye.x(), eye.y(), eye.z(), reach, 0, tall);
        return list;
    }

    // --- how far detail reaches -----------------------------------------------------

    /**
     * <b>The radius holds still while the world it already asked for is still
     * arriving.</b>
     *
     * <p>The regression this exists for, and it cost a frame rate rather than a
     * picture. The count of sections <em>drawn</em> reads low both when there is
     * little to draw and when there is plenty of it unbuilt — and left to that
     * signal alone the radius grew straight through a loading world: measured,
     * fifty-eight chunks reached while four hundred sections were drawn, the
     * walk stepping through ninety thousand empty sections at twenty
     * milliseconds a frame. Which is to say it spent the most frames exactly
     * when the player could least spare them.
     */
    @Test
    void theDetailRadiusWaitsForTheWorldItAlreadyAskedFor() {
        DetailReach reach = new DetailReach();
        double span = SectionMesh.SIZE * (double) TILE;
        double asked = 90 * span;
        double start = reach.update(asked, Double.MAX_VALUE, 0, 0, 0, 0, span);

        // Nothing drawn, nothing costly — but nine tenths of the walk is still
        // waiting on a mesh. That is not an invitation to reach further.
        double held = start;
        for (int i = 0; i < 500; i++) {
            held = reach.update(asked, Double.MAX_VALUE, 40, 4000, 3600, 0, span);
        }
        assertEquals(start, held, 1e-9,
                "the radius grew into a world that had not been built yet");

        // The same frame, with the world arrived: now it may grow.
        double grown = held;
        for (int i = 0; i < 200; i++) {
            grown = reach.update(asked, Double.MAX_VALUE, 40, 4000, 0, 0, span);
        }
        assertTrue(grown > held * 2, "once the world is there it has to reach further: "
                + grown + " from " + held);
    }

    /**
     * <b>A machine that is hitting its frame rate must be allowed to grow.</b>
     *
     * <p>The bug this pins shipped, and it looked like a broken setting rather
     * than a governor: every detail distance drew the same six or seven chunks,
     * however long you waited. The radius was being steered on <em>whole-frame</em>
     * time against a six-millisecond budget — and a frame running at exactly the
     * 120 Hz it was aiming for takes 8.3 ms, so hitting the target read as
     * missing it, and the radius sat at its opening value forever.
     *
     * <p>The reason it cannot be repaired by choosing a larger number is the
     * point of the test: under vsync the frame time is <em>pinned</em> to the
     * refresh interval whatever the load, so an idle 120 Hz frame and a
     * struggling one are the same measurement. What is steered on now is the
     * walk's own cost, which nothing pins.
     */
    @Test
    void aFrameRateItIsAlreadyHittingDoesNotStopTheRadiusGrowing() {
        DetailReach reach = new DetailReach();
        double span = SectionMesh.SIZE * (double) TILE;
        double asked = 40 * span;
        double at = reach.update(asked, Double.MAX_VALUE, 0, 0, 0, 0, span);
        double start = at;
        // A healthy frame: the walk costs a fraction of a millisecond, the world
        // is built, and there is little on screen. Nothing here is a reason to
        // stop — whatever the wall clock between frames happens to say.
        for (int i = 0; i < 400; i++) {
            at = reach.update(asked, Double.MAX_VALUE, 200, 900, 0, 0.4, span);
        }
        assertEquals(asked, at, 1e-6,
                "a comfortable frame has to reach what was asked for, and stopped at "
                        + at / span + " chunks from " + start / span);
    }

    /** Any of the three costs over its target pulls the radius back in. */
    @Test
    void theDetailRadiusBacksOffWhateverIsCostingTooMuch() {
        double span = SectionMesh.SIZE * (double) TILE;
        double asked = 90 * span;
        // Too many sections drawn; too much walked; too long a frame. Each on
        // its own, with everything else comfortable and the world fully built.
        for (int which = 0; which < 3; which++) {
            DetailReach reach = new DetailReach();
            double start = reach.update(asked, Double.MAX_VALUE, 0, 0, 0, 0, span);
            double at = start;
            for (int i = 0; i < 100; i++) {
                at = reach.update(asked, Double.MAX_VALUE,
                        which == 0 ? DetailReach.TARGET_SECTIONS * 2 : 10,
                        which == 1 ? DetailReach.TARGET_VISITS * 2 : 10,
                        0, which == 2 ? 40 : 0, span);
            }
            assertTrue(at < start, "signal " + which + " did not pull the radius in: "
                    + at + " from " + start);
            assertTrue(at >= span, "…nor may it collapse to nothing: " + at / span);
        }
    }

    /** What the player asked for is a ceiling, never a floor. */
    @Test
    void theDetailRadiusNeverExceedsWhatWasAskedOrWhatFits() {
        DetailReach reach = new DetailReach();
        double span = SectionMesh.SIZE * (double) TILE;
        double at = 0;
        for (int i = 0; i < 2000; i++) {
            at = reach.update(8 * span, 5 * span, 1, 1, 0, 0.1, span);
        }
        assertTrue(at <= 5 * span + 1e-6,
                "the cache said five sections' worth and it reached " + at / span);
    }

    /**
     * <b>Emptiness is free, so the budget is bytes.</b> A section of open sky
     * meshes to nothing and is worth keeping — the walk needs its visibility to
     * see through it — so counting sections spends a cache on air.
     */
    @Test
    void anEmptySectionCostsTheCacheNothing() {
        Level lvl = bare();
        assertEquals(0, SectionMesher.build(lvl, atlas(), 0, 0, 0).byteCount(),
                "nothing in it, nothing to hold");
        lvl.setTile(4, 4, 5, stone(lvl));
        assertTrue(SectionMesher.build(lvl, atlas(), 0, 0, 0).byteCount() > 0,
                "and a block in it weighs something");
    }

    // --- the atlas ------------------------------------------------------------------

    /** Every block has a cell, so the shader never has to ask whether it does. */
    @Test
    void everyBlockHasACell() {
        BlockAtlas atlas = atlas();
        float[] uv = new float[4];
        for (com.larsons.engine.world.Block b : BlockRegistry.standard().all()) {
            for (int face : new int[] {BlockAtlas.FACE_TOP, BlockAtlas.FACE_SIDE}) {
                atlas.uv(b.id(), face, uv);
                assertTrue(uv[2] > uv[0] && uv[3] > uv[1],
                        b.key() + " has no cell on face " + face);
                assertTrue(uv[0] >= 0 && uv[2] <= 1 && uv[1] >= 0 && uv[3] <= 1,
                        b.key() + "'s cell is off the atlas");
            }
        }
        assertNotNull(atlas.image());
    }

    // --- invalidation ---------------------------------------------------------------

    /**
     * A block placed inside a section dirties that one; a block placed on its
     * edge dirties the neighbour too, because a face is a fact about two cells.
     */
    @Test
    void anEditDirtiesTheSectionsItCanHaveChanged() {
        Level lvl = bare(64, 64);
        TerrainSections sections = new TerrainSections(lvl, atlas());
        try {
            // Nothing throws, and the neighbour rule is exercised from both
            // the middle of a section and its very edge.
            sections.invalidate(8, 8, 5);
            sections.invalidate(0, 0, 1);
            sections.invalidate(SectionMesh.SIZE - 1, SectionMesh.SIZE - 1,
                    SectionMesh.SIZE);
        } finally {
            sections.close();
        }
    }

    // --- helpers --------------------------------------------------------------------

    private static BlockAtlas atlas() {
        return BlockAtlas.of(BlockRegistry.standard());
    }

    private static Level bare() {
        return bare(32, 32);
    }

    private static Level bare(int w, int h) {
        Level lvl = Level.empty("gpu-test", w, h, TILE);
        lvl.setFormat(LevelFormat.THREE_D);
        GameProfile settings = new GameProfile("gpu-test");
        settings.verticality = true;
        lvl.settings = settings;
        return lvl;
    }

    private static int stone(Level lvl) {
        return lvl.blocks.get("stone").id();
    }
}
