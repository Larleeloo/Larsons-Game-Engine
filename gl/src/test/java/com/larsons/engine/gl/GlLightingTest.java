package com.larsons.engine.gl;

import com.larsons.engine.graphics.EyeCamera;
import com.larsons.engine.graphics.Mat4;
import com.larsons.engine.graphics.MeshPass;
import com.larsons.engine.watch.render.Mesh;
import com.larsons.engine.watch.world.WatchMaterial;
import com.larsons.engine.watch.world.WatchMaterials;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.lwjgl.opengl.GL33C.GL_COLOR_BUFFER_BIT;
import static org.lwjgl.opengl.GL33C.GL_DEPTH_BUFFER_BIT;
import static org.lwjgl.opengl.GL33C.GL_NO_ERROR;
import static org.lwjgl.opengl.GL33C.glClear;
import static org.lwjgl.opengl.GL33C.glClearColor;
import static org.lwjgl.opengl.GL33C.glGetError;
import static org.lwjgl.opengl.GL33C.glViewport;

/**
 * The lighting half of the world shader, on a real driver.
 *
 * <p><b>Everything about this feature that a headless machine can check is
 * checked in the core</b> — see {@code LightingTest}, which exercises the
 * catalogue, the world state, the verbs and the painter's own arithmetic. What
 * it cannot check is the half that only exists as GLSL, and the ways that half
 * fails are quiet ones:
 *
 * <ul>
 *   <li>the shader does not compile — {@code dFdx} in the wrong stage, a
 *       dynamic loop bound the driver refuses, an array size substituted
 *       wrongly. {@link GlMeshPass} catches this at runtime and falls back to
 *       the painter with one line on stderr, which in a game means "the frame
 *       rate quietly halved";</li>
 *   <li>a uniform's location comes back {@code -1} because the name was spelled
 *       {@code uLightPos} rather than {@code uLightPos[0]}, and every write to
 *       it is accepted and discarded. The world draws perfectly and is lit by
 *       nothing;</li>
 *   <li>the wrong {@code glUniform} overload for the array's type, which is a
 *       {@code GL_INVALID_OPERATION} nobody is looking for.</li>
 * </ul>
 *
 * <p>Where no GL 3.3 context can be created — headless CI, a machine with no
 * driver — every test here skips rather than fails, on the same principle as
 * {@code GlShaderChainTest}: "could not ask" is not "the answer is no".
 */
@Timeout(120)
class GlLightingTest {

    private static GlContext context;
    private GlTerrainProgram program;

    @BeforeAll
    static void openContext() {
        context = GlContext.offscreen(0);
    }

    @BeforeEach
    void requireContext() {
        assumeTrue(context != null && context.available(),
                () -> "no GL 3.3 core context: "
                        + (context == null ? "not created" : context.whyUnavailable()));
        context.makeCurrent();
        program = new GlTerrainProgram();
        // Anything the context arrived with is not this test's.
        while (glGetError() != GL_NO_ERROR) { /* drain */ }
    }

    @AfterEach
    void closeProgram() {
        if (program != null) {
            program.close();
            program = null;
        }
    }

    @AfterAll
    static void closeContext() {
        if (context != null) context.close();
    }

    /** It compiles and links at all — which is the whole of the fallback risk. */
    @Test
    void theWorldShaderCompilesWithItsLighting() {
        assertEquals(GL_NO_ERROR, glGetError(), "linking left an error behind");
        assertTrue(program.lightingUniformsResolved(),
                "the driver did not keep the lighting uniforms — every light "
                        + "written to them would be silently discarded");
    }

    /** A frame's worth of lights goes up without upsetting the driver. */
    @Test
    void aFullSetOfLightsUploadsCleanly() {
        EyeCamera eye = new EyeCamera(320, 200);
        eye.place(0, 0, 2);
        eye.look(0, 0);

        List<MeshPass.Light> lights = new ArrayList<>();
        for (int i = 0; i < MeshPass.MAX_LIGHTS; i++) {
            lights.add(MeshPass.Light.of(i * 3, -4, 1, 0xFF9A3C, 12, 1.15));
        }
        program.use();
        program.setLighting(lights, eye, 0.26f, 0.26f, 0.30f);
        assertEquals(GL_NO_ERROR, glGetError(), "a full light block upset the driver");

        // …and the two edges of it: none at all, and one more than the array
        // holds. The second is the one worth having — a caller that sends
        // seventeen must be clamped here rather than writing past the array.
        program.setLighting(List.of(), eye, 1f, 1f, 1f);
        assertEquals(GL_NO_ERROR, glGetError(), "an empty light block upset the driver");

        lights.add(MeshPass.Light.of(99, 99, 1, 0xFFFFFF, 4, 1));
        program.setLighting(lights, eye, 1f, 1f, 1f);
        assertEquals(GL_NO_ERROR, glGetError(), "one light too many was written anyway");
    }

    /**
     * {@link GlTerrainProgram#use} leaves the block neutral.
     *
     * <p>The reason this matters is {@link GlTerrainPass}, which shares this
     * program, knows nothing about any of this and has its own shading baked
     * into vertex colours by {@code SectionMesher}. A GLSL uniform starts at
     * zero, so a daylight multiplier nobody set is <em>black</em> — the voxel
     * world would have gone dark the moment this shader grew a lighting block.
     */
    @Test
    void bindingTheProgramResetsTheLightingToNeutral() {
        program.use();
        assertEquals(GL_NO_ERROR, glGetError(),
                "binding the program without ever setting a light upset the driver");
    }

    // --- the sky half of it ---------------------------------------------------------

    /** Every sky uniform survived the link, on the same principle as the lights. */
    @Test
    void theWorldShaderCompilesWithItsSky() {
        assertTrue(program.skyUniformsResolved(),
                "the driver did not keep the sky's uniforms — the world would be "
                        + "lit at the right time of day and by nothing directional, "
                        + "which looks exactly like the flat shading this replaced");
    }

    /** A whole sky goes up without upsetting the driver. */
    @Test
    void aWholeSkyUploadsCleanly() {
        EyeCamera eye = new EyeCamera(320, 200);
        eye.place(0, 0, 2);
        eye.look(0.4, -0.2);

        program.use();
        program.setSky(dawn(), eye, 1 / 2048f, 0.001f, 0.004f);
        assertEquals(GL_NO_ERROR, glGetError(), "a full sky upset the driver");

        // …and the two edges: nothing at all, which is what GlTerrainPass gets,
        // and a sky with no camera to put its sun into eye space.
        program.setSky(MeshPass.Sky.PLAIN, eye, 0f, 0f, 0f);
        assertEquals(GL_NO_ERROR, glGetError(), "a plain sky upset the driver");
        program.setSky(dawn(), null, 0f, 0f, 0f);
        assertEquals(GL_NO_ERROR, glGetError(), "a sky with no camera upset the driver");
    }

    // --- and what it actually draws ---------------------------------------------------

    /**
     * <b>A canopy puts a shadow on the ground under it.</b>
     *
     * <p>The whole of {@link GlShadowMap}, end to end and on a real driver: a
     * depth framebuffer, a second submission of the opaque meshes through the
     * sun's own matrix, and a fragment comparing itself against what came back.
     * Every step of it fails silently — an incomplete framebuffer, a matrix
     * with the sun's sign the wrong way round, a bias so large that nothing is
     * ever in shadow — and the result of any of them is a wood that draws
     * perfectly and has no shadows in it, which is indistinguishable from this
     * feature not being finished.
     *
     * <p>The control is the same frame with the shadow strength turned down to
     * nothing, which is also the frame every machine without a depth
     * framebuffer sees. Two things are asserted against it and the second is
     * the one that catches shadow acne: the ground <em>away</em> from the
     * canopy has to come back unchanged, because a surface the sun strikes
     * squarely must not shadow itself.
     */
    @Test
    void aCanopyPutsAShadowOnTheGroundUnderIt() {
        EyeCamera eye = clearing();
        int[] unshadowed = render(eye, sunny(0), List.of(), true);
        int[] shadowed = render(eye, sunny(0.75f), List.of(), true);
        assumeTrue(unshadowed != null && shadowed != null,
                "no offscreen surface to draw into");

        int under = luma(shadowed, eye, 0, 0) - luma(unshadowed, eye, 0, 0);
        assertTrue(under < -18, "the canopy cast no shadow: the ground under it came "
                + "back " + under + "/255 different, which nobody sees");

        int beside = luma(shadowed, eye, 9, 0) - luma(unshadowed, eye, 9, 0);
        assertTrue(Math.abs(beside) <= 6, "open ground five metres clear of the canopy "
                + "changed by " + beside + "/255 when the shadow map was switched on "
                + "— that is the ground shadowing itself, which is acne");
    }

    /**
     * <b>A lantern lights the air between you and the hillside.</b>
     *
     * <p>Deliberately arranged so that nothing else can account for it: the
     * lamp is eleven metres from the patch of ground being measured and reaches
     * six, so its surface term is exactly zero there and the falloff, the wrap
     * and the normal are all out of the picture. What is left is the view ray,
     * which passes within half a metre of the flame. If that patch of ground
     * gets brighter when {@code scatter} is turned up, the only thing that can
     * have done it is the air.
     */
    @Test
    void theAirCarriesALanternsLightBackToTheEye() {
        EyeCamera eye = clearing();
        List<MeshPass.Light> lamp = List.of(
                MeshPass.Light.of(0, 3, 4, 0xFFC46A, 6, 1.0));

        int[] vacuum = render(eye, scattering(0), lamp, false);
        int[] misty = render(eye, scattering(0.55f), lamp, false);
        assumeTrue(vacuum != null && misty != null, "no offscreen surface to draw into");

        int glow = luma(misty, eye, 0, -8) - luma(vacuum, eye, 0, -8);
        assertTrue(glow > 12, "the air carried nothing: ground the lamp cannot reach "
                + "came back " + glow + "/255 brighter with the scattering on, and "
                + "the ray goes straight through the flame");
    }

    /**
     * <b>A shadow map is drawn once and then kept until something moves.</b>
     *
     * <p>The pass is the most expensive thing in a frame — a second submission
     * of every opaque mesh, rasterised into a map larger than the screen — and
     * a shadow map is a pure function of where its box is and what is standing
     * in it. Neither moves while the player is still, so a frame that redraws
     * it anyway is paying twice for the same depth values, and that was the
     * whole of the frame rate this feature cost when it first shipped.
     *
     * <p><b>The second assertion is the one that makes the first safe.</b>
     * Reusing the map means reusing the matrix it was drawn with, and the main
     * pass has to look it up through that same matrix even though the camera
     * has since moved; get that wrong and the shadows slide off their trees
     * while the frame rate looks wonderful. So the two frames are compared
     * pixel for pixel and must be identical.
     */
    @Test
    void aStillCameraDrawsTheShadowMapOnceAndThenKeepsIt() {
        EyeCamera eye = clearing();
        GlSurface surface = new GlSurface(0);
        GlMeshPass pass = new GlMeshPass(() -> null);
        try {
            surface.resize(WIDTH, HEIGHT);
            surface.bind();
            glViewport(0, 0, WIDTH, HEIGHT);
            pass.setTexture(WatchMaterials.atlas(), WatchMaterials.revision());
            pass.setLighting(List.of(), 0.5f, 0.5f, 0.5f);
            pass.setSky(sunny(0.75f));

            List<MeshPass.Draw> draws = List.of(ground().toDraw(1), canopy().toDraw(2));
            int[] first = frame(surface, pass, draws, eye);
            assertTrue(pass.redrewShadowsLastFrame(),
                    "the very first frame had no map and did not draw one");

            int[] second = frame(surface, pass, draws, eye);
            assertFalse(pass.redrewShadowsLastFrame(),
                    "a second frame from a camera that has not moved, of a wood that "
                            + "has not moved, drew the whole shadow map again");
            assertArrayEquals(first, second,
                    "the reused map did not draw the same picture — which means the "
                            + "matrix the main pass looks it up through is no longer "
                            + "the one it was drawn with");

            // …and it does not keep it for ever. Twenty metres is well past the
            // step the box is snapped to.
            EyeCamera moved = clearing();
            moved.place(eye.x() + 20, eye.y(), eye.z());
            frame(surface, pass, draws, moved);
            assertTrue(pass.redrewShadowsLastFrame(),
                    "the box did not follow the player twenty metres down the field");
        } finally {
            pass.dispose();
            surface.close();
            GlSurface.unbind();
        }
    }

    /**
     * <b>A lamp lights across the join between two meshes.</b>
     *
     * <p>Which lamps reach which mesh is decided per mesh on the CPU, because
     * a fragment shader that walks a list walks all of it and a camp was
     * costing the whole screen. The way that goes wrong is not a crash: it is a
     * lamp that stops at a chunk boundary, leaving a straight edge of darkness
     * across ground the player is walking over — a seam that moves with them
     * and reads as "the lighting is broken".
     *
     * <p>So: two pieces of ground meeting at a line, a lamp standing over one
     * of them and reaching well into the other, and the pool of light sampled
     * either side of the join. It has to cross.
     */
    @Test
    void aLampLightsAcrossTheJoinBetweenTwoMeshes() {
        EyeCamera eye = new EyeCamera(WIDTH, HEIGHT);
        eye.place(0, 14, 15);
        eye.look(0, -0.85);

        // The lamp stands over the western half and reaches nine metres into
        // the eastern one, so the eastern mesh is lit by a lamp that is not
        // standing on it — which is exactly the case a box test gets wrong if
        // it is written as "is the lamp inside this mesh".
        List<MeshPass.Light> lamp = List.of(
                MeshPass.Light.of(-3, 0, 2.0, 0xFFC46A, 12, 1.2));
        int[] pixels = render(eye, scattering(0), lamp, halves());
        assumeTrue(pixels != null, "no offscreen surface to draw into");

        int west = luma(pixels, eye, -0.8, 0);
        int east = luma(pixels, eye, 0.8, 0);
        assertTrue(Math.abs(west - east) <= 8,
                "the light stops at the join: " + west + "/255 on one side of it "
                        + "and " + east + " on the other");

        // …and it really is lit rather than uniformly dark, or the assertion
        // above would pass on a frame with no lamp in it at all.
        int far = luma(pixels, eye, 17, 0);
        assertTrue(east - far > 18,
                "the eastern half is no brighter under the lamp than it is "
                        + "seventeen metres away, so nothing was lit");
    }

    // --- and the fire, after the sun has gone down ------------------------------------

    /** Every lamp-cube uniform survived the link, on the same principle again. */
    @Test
    void theWorldShaderCompilesWithItsLampShadows() {
        assertTrue(program.lampShadowUniformsResolved(),
                "the driver did not keep the lamp cube's uniforms — a "
                        + "samplerCubeShadow left at zero samples the colour atlas, "
                        + "which is a mismatched sampler and never a shadow");
    }

    /**
     * <b>A post beside a fire throws a shadow, and the shadow spreads.</b>
     *
     * <p>The thing a lamp gives that the sun cannot. Sunlight is parallel, so a
     * trunk's shadow is the width of the trunk however far it runs; a fire is a
     * <em>point</em>, so the same trunk's shadow widens as it goes and reaches
     * the far side of the clearing. That divergence is what makes firelight
     * read as firelight, and it is what this asserts — not merely that
     * something got darker, but that the dark patch is wider three metres out
     * than it is at the post.
     *
     * <p>So: a lamp, a metre-wide post two metres in front of it, and four
     * patches of ground read off the frame with the cube map on and off. Two of
     * them are in line with the post and must go dark; the third is the same
     * lateral offset from the shadow's axis at two different distances, and it
     * has to be <em>lit</em> near the post and <em>dark</em> far from it. Only a
     * spreading shadow does that. The fourth is well clear and must not change
     * at all, which is what catches acne.
     */
    @Test
    void aPostBesideAFireThrowsAShadowThatSpreads() {
        EyeCamera eye = clearing();
        List<MeshPass.Draw> scene = List.of(ground().toDraw(1), post().toDraw(2));
        int[] unshadowed = renderNight(eye, camp(), scene, false);
        int[] shadowed = renderNight(eye, camp(), scene, true);
        assumeTrue(unshadowed != null && shadowed != null,
                "no offscreen surface to draw into");

        int behind = luma(shadowed, eye, 0, 0) - luma(unshadowed, eye, 0, 0);
        assertTrue(behind < -20, "the post cast no shadow at all: the ground directly "
                + "behind it came back " + behind + "/255 different");

        int along = luma(shadowed, eye, 0, -3) - luma(unshadowed, eye, 0, -3);
        assertTrue(along < -12, "the shadow stopped three metres from the post: "
                + along + "/255 there against " + behind + " at its foot");

        // The divergence, which is the whole point. Both of these are 1.1 m off
        // the axis; the post is only half a metre wide, so a parallel shadow
        // would leave them both lit and an equal-width one would leave them
        // both dark.
        int nearAxis = luma(shadowed, eye, 1.1, 1.0) - luma(unshadowed, eye, 1.1, 1.0);
        int farAxis = luma(shadowed, eye, 1.1, -3) - luma(unshadowed, eye, 1.1, -3);
        assertTrue(Math.abs(nearAxis) <= 6,
                "ground a metre off the axis and level with the post went "
                        + nearAxis + "/255 darker; the shadow is far too wide there "
                        + "to have come from a half-metre post");
        assertTrue(farAxis < -10,
                "the same metre off the axis, three metres further on, changed by "
                        + farAxis + "/255 — the shadow is not spreading, which is a "
                        + "point light being treated as a parallel one");

        // …and open ground the post cannot reach is untouched, which is the
        // assertion that catches a surface shadowing itself.
        int clear = luma(shadowed, eye, 6, 0) - luma(unshadowed, eye, 6, 0);
        assertTrue(Math.abs(clear) <= 6, "open ground six metres to the side changed by "
                + clear + "/255 when the lamp's cube was switched on — that is the "
                + "ground shadowing itself, which is acne");
    }

    /**
     * <b>A fire you have sat down at is drawn once.</b>
     *
     * <p>Six faces is more depth than the sun's whole map, and it is only
     * affordable because a fire does not move and neither does a wood. The
     * cache is against the lamp's own position and what is standing round it,
     * both of which hold still while the player watches the flames — so the
     * second frame must reuse it, and must draw exactly the same picture doing
     * so.
     */
    @Test
    void aFireThatHasNotMovedDrawsItsCubeOnceAndThenKeepsIt() {
        EyeCamera eye = clearing();
        GlSurface surface = new GlSurface(0);
        GlMeshPass pass = new GlMeshPass(() -> null);
        try {
            surface.resize(WIDTH, HEIGHT);
            surface.bind();
            glViewport(0, 0, WIDTH, HEIGHT);
            pass.setTexture(WatchMaterials.atlas(), WatchMaterials.revision());
            pass.setLighting(camp(), NIGHT, NIGHT, NIGHT_BLUE);
            pass.setSky(scattering(0));

            List<MeshPass.Draw> scene = List.of(ground().toDraw(1), post().toDraw(2));
            int[] first = frame(surface, pass, scene, eye);
            assumeTrue(pass.redrewLampShadowsLastFrame(),
                    "this driver gave us no lamp cube to test the caching of");

            int[] second = frame(surface, pass, scene, eye);
            assertFalse(pass.redrewLampShadowsLastFrame(),
                    "a second frame of a fire that has not moved, in a wood that has "
                            + "not moved, drew all six faces of the cube again");
            assertArrayEquals(first, second,
                    "the reused cube did not draw the same picture, so what the main "
                            + "pass looks up is no longer what was written");
        } finally {
            pass.dispose();
            surface.close();
            GlSurface.unbind();
        }
    }

    /**
     * <b>…and a flame flickering does not count as the fire moving.</b>
     *
     * <p>The cache above is worth nothing without this, and it is the kind of
     * thing that is invisible until somebody profiles a camp.
     * {@code LightField} wobbles a flame's radius by six percent on every
     * single frame — that is what makes firelight look alive — so a map cached
     * against the radius <em>exactly</em> is a map that is never once reused,
     * six faces of depth every frame, and the frame rate at a camp back where
     * it was before any of this was optimised. So the reach is compared to a
     * fifth and the far plane is set that much beyond it.
     */
    @Test
    void aFlameFlickeringDoesNotCountAsTheFireMoving() {
        EyeCamera eye = clearing();
        GlSurface surface = new GlSurface(0);
        GlMeshPass pass = new GlMeshPass(() -> null);
        try {
            surface.resize(WIDTH, HEIGHT);
            surface.bind();
            glViewport(0, 0, WIDTH, HEIGHT);
            pass.setTexture(WatchMaterials.atlas(), WatchMaterials.revision());
            pass.setSky(scattering(0));
            List<MeshPass.Draw> scene = List.of(ground().toDraw(1), post().toDraw(2));

            int redraws = 0;
            for (int frame = 0; frame < 12; frame++) {
                // The same wobble LightField applies, and the same shape: the
                // radius between ninety-four percent and all of it, the
                // intensity swinging further still.
                double wobble = 0.5 + 0.5 * Math.sin(frame * 1.7);
                pass.setLighting(List.of(MeshPass.Light.of(0, 5, 0.7, 0xFFC46A,
                                LAMP_REACH * (0.94 + 0.06 * wobble), 4 * (0.8 + 0.2 * wobble))),
                        NIGHT, NIGHT, NIGHT_BLUE);
                frame(surface, pass, scene, eye);
                if (pass.redrewLampShadowsLastFrame()) redraws++;
            }
            assumeTrue(redraws > 0, "this driver gave us no lamp cube at all");
            assertEquals(1, redraws,
                    "a fire that only flickered redrew its cube " + redraws + " times "
                            + "in twelve frames; the cache is comparing the radius "
                            + "exactly and a flame's radius is never twice the same");
        } finally {
            pass.dispose();
            surface.close();
            GlSurface.unbind();
        }
    }

    /**
     * <b>The face cull and the face projection agree about what is in a face.</b>
     *
     * <p>{@link GlLampShadow} decides which of the six faces a mesh is drawn
     * into with four dot products, and {@link Mat4#cubeFace} decides where it
     * lands once it is. Those are two spellings of one cube, in two classes, and
     * the way they come apart is not a crash — it is a mesh culled out of the
     * face it was actually in, which is a shadow with a quadrant missing that
     * only appears when something stands in exactly the wrong place.
     *
     * <p>So every direction is pushed through both. The cull is allowed to be
     * <em>generous</em> — it works on a ball and may keep a mesh a projection
     * then clips away — but never mean: anything the projection puts inside a
     * face, the cull must have kept.
     */
    @Test
    void theFaceCullKeepsEverythingTheFaceProjectionDraws() {
        GlLampShadow cube = new GlLampShadow();
        try {
            EyeCamera eye = clearing();
            List<MeshPass.Light> lamp = List.of(
                    MeshPass.Light.of(0, 0, 0, 0xFFC46A, LAMP_REACH, 2));
            assumeTrue(cube.aim(lamp, eye, 1, false) == 0,
                    "this driver would not give us a lamp cube");
            cube.adoptLamp();

            int kept = 0;
            for (int face = 0; face < 6; face++) {
                Mat4 projection = Mat4.cubeFace(face, 0.12, LAMP_REACH);
                for (double x = -4; x <= 4; x += 1) {
                    for (double y = -4; y <= 4; y += 1) {
                        for (double z = -4; z <= 4; z += 1) {
                            if (x == 0 && y == 0 && z == 0) continue;
                            double[] at = new double[4];
                            projection.transform(x, y, z, at);
                            boolean drawn = at[3] > 0
                                    && Math.abs(at[0]) <= at[3]
                                    && Math.abs(at[1]) <= at[3];
                            boolean culled = cube.faceCasts(face, x, y, z, 0);
                            if (culled) kept++;
                            assertTrue(!drawn || culled,
                                    "(" + x + ", " + y + ", " + z + ") lands inside "
                                            + "face " + face + " and the cull threw it "
                                            + "away, so that face is missing geometry "
                                            + "that belongs in it");
                        }
                    }
                }
            }
            // The cull must actually be doing something, or the assertion above
            // passes on a method that returns true.
            assertTrue(kept < 6 * 728 / 2,
                    "the face cull kept " + kept + " of " + (6 * 728) + " points, "
                            + "which is not a cull");
        } finally {
            cube.close();
        }
    }

    /** How far the test's fire reaches, in metres. */
    private static final double LAMP_REACH = 14;

    /** …and the hour it burns in: dark enough that its shadow is worth drawing. */
    private static final float NIGHT = 0.25f, NIGHT_BLUE = 0.28f;

    /** One fire, standing low, off to the north of the clearing. */
    private static List<MeshPass.Light> camp() {
        return List.of(MeshPass.Light.of(0, 5, 0.7, 0xFFC46A, LAMP_REACH, 4));
    }

    /**
     * A half-metre post two metres south of it, taller than the flame.
     *
     * <p>Taller on purpose: a post the light can see over would put a bright
     * band beyond its own shadow and the far sample would measure the band
     * rather than the spread. Short enough that the camera, which is eight
     * metres up and looking down, sees over it to every patch of ground being
     * read.
     */
    private static Mesh post() {
        Mesh.Builder mesh = Mesh.builder(0, 2, 0, false, 1);
        float[] uv = new float[4];
        WatchMaterials.uv(WatchMaterial.DIRT, uv);
        mesh.quad(0.5f, 0, 0, -0.5f, 0, 0, -0.5f, 0, 1.0f, 0.5f, 0, 1.0f,
                uv, 0xFF6B5A44);
        return mesh.build();
    }

    /**
     * One frame of the same clearing at night, with the lamp's cube map on or
     * off.
     *
     * <p>Switched by the property rather than by changing the scene, so that
     * the two frames differ by the shadow lookup and by nothing else at all —
     * same geometry, same lamp, same hour. {@link GlLampShadow} reads it when
     * it is constructed, and a pass is constructed per frame here.
     */
    private int[] renderNight(EyeCamera eye, List<MeshPass.Light> lights,
                              List<MeshPass.Draw> scene, boolean lampShadows) {
        String before = System.getProperty(GlLampShadow.SIZE_PROPERTY);
        if (!lampShadows) System.setProperty(GlLampShadow.SIZE_PROPERTY, "0");
        GlSurface surface = new GlSurface(0);
        GlMeshPass pass = new GlMeshPass(() -> null);
        try {
            surface.resize(WIDTH, HEIGHT);
            surface.bind();
            glViewport(0, 0, WIDTH, HEIGHT);
            glClearColor(0, 0, 0, 1);
            glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
            pass.setTexture(WatchMaterials.atlas(), WatchMaterials.revision());
            pass.setLighting(lights, NIGHT, NIGHT, NIGHT_BLUE);
            pass.setSky(scattering(0));
            pass.draw(scene, eye, 0x000000, 400, 800);
            return surface.readPixels();
        } catch (RuntimeException e) {
            return null;
        } finally {
            pass.dispose();
            surface.close();
            GlSurface.unbind();
            if (before == null) System.clearProperty(GlLampShadow.SIZE_PROPERTY);
            else System.setProperty(GlLampShadow.SIZE_PROPERTY, before);
        }
    }

    /**
     * Two pieces of ground meeting along {@code x = 0}, as two separate meshes
     * with separate origins — which is what makes them cull separately.
     */
    private static List<MeshPass.Draw> halves() {
        return List.of(halfGround(-10, 1), halfGround(10, 2));
    }

    private static MeshPass.Draw halfGround(double originX, long key) {
        Mesh.Builder mesh = Mesh.builder(originX, 0, 0, false, 1);
        float[] uv = new float[4];
        WatchMaterials.uv(WatchMaterial.GRASS, uv);
        mesh.quad(-10, -10, 0, 10, -10, 0, 10, 10, 0, -10, 10, 0, uv, 0xFF9AA88A);
        return mesh.build().toDraw(key);
    }

    /** One frame into a bound surface, read back. */
    private static int[] frame(GlSurface surface, GlMeshPass pass,
                               List<MeshPass.Draw> draws, EyeCamera eye) {
        glClearColor(0, 0, 0, 1);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        pass.draw(draws, eye, 0x000000, 400, 800);
        return surface.readPixels();
    }

    // --- the fixtures --------------------------------------------------------------

    private static final int WIDTH = 192;
    private static final int HEIGHT = 144;

    /** Above a clearing, looking down into it from the north. */
    private static EyeCamera clearing() {
        EyeCamera eye = new EyeCamera(WIDTH, HEIGHT);
        eye.place(0, 12, 8);
        eye.look(0, -0.52);
        return eye;
    }

    /** A sun most of the way up, and this much shadow under it. */
    private static MeshPass.Sky sunny(float shadow) {
        return sky(0.15, 0.10, 0.98, 0.55f, shadow, 0);
    }

    /** Midnight, and this much of a lamp's light carried by the air. */
    private static MeshPass.Sky scattering(float scatter) {
        return sky(0, 0, 1, 0f, 0f, scatter);
    }

    private static MeshPass.Sky dawn() {
        return sky(0.94, -0.2, 0.28, 0.4f, 0.6f, 0.3f);
    }

    /**
     * A sky, with the grade off.
     *
     * <p>{@code vibrance} is zero throughout this class on purpose: it is a
     * non-linear curve over the finished colour, and a test that subtracts two
     * frames wants the difference it is measuring to be the difference it
     * caused.
     */
    private static MeshPass.Sky sky(double sunX, double sunY, double sunZ,
                                    float sun, float shadow, float scatter) {
        return new MeshPass.Sky(sunX, sunY, sunZ,
                sun, sun * 0.96f, sun * 0.86f,
                1.1f, 1.1f, 1.15f,
                0.7f, 0.7f, 0.72f,
                shadow, 0, 0, 4, scatter, 0, 12.5f);
    }

    /**
     * One frame of a clearing, as {@code 0xAARRGGBB}, or {@code null} where the
     * driver would not give us somewhere to draw it.
     */
    private int[] render(EyeCamera eye, MeshPass.Sky sky, List<MeshPass.Light> lights,
                         boolean canopy) {
        List<MeshPass.Draw> scene = canopy
                ? List.of(ground().toDraw(1), canopy().toDraw(2))
                : List.of(ground().toDraw(1));
        return render(eye, sky, lights, scene);
    }

    private int[] render(EyeCamera eye, MeshPass.Sky sky, List<MeshPass.Light> lights,
                         List<MeshPass.Draw> scene) {
        GlSurface surface = new GlSurface(0);
        GlMeshPass pass = new GlMeshPass(() -> null);
        try {
            surface.resize(WIDTH, HEIGHT);
            surface.bind();
            glViewport(0, 0, WIDTH, HEIGHT);
            glClearColor(0, 0, 0, 1);
            glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

            pass.setTexture(WatchMaterials.atlas(), WatchMaterials.revision());
            // Half daylight, so a shadow has somewhere to go and the sun has
            // somewhere to come from.
            pass.setLighting(lights, 0.5f, 0.5f, 0.5f);
            pass.setSky(sky);
            // Fog pushed out past the far corner of the clearing: this is a
            // test about light, and haze over it would only add a term both
            // frames share.
            pass.draw(scene, eye, 0x000000, 400, 800);
            return surface.readPixels();
        } catch (RuntimeException e) {
            return null;
        } finally {
            pass.dispose();
            surface.close();
            GlSurface.unbind();
        }
    }

    /** Thirty-two metres of flat ground, wound to face the sky. */
    private static Mesh ground() {
        Mesh.Builder mesh = Mesh.builder(0, 0, 0, false, 1);
        float[] uv = new float[4];
        WatchMaterials.uv(WatchMaterial.GRASS, uv);
        mesh.quad(-16, -16, 0, 16, -16, 0, 16, 16, 0, -16, 16, 0, uv, 0xFF9AA88A);
        return mesh.build();
    }

    /** …and eight metres of leaf five metres above the middle of it. */
    private static Mesh canopy() {
        Mesh.Builder mesh = Mesh.builder(0, 0, 0, false, 1);
        float[] uv = new float[4];
        WatchMaterials.uv(WatchMaterial.GRASS, uv);
        mesh.quad(-4, -4, 5, 4, -4, 5, 4, 4, 5, -4, 4, 5, uv, 0xFF3E6B32);
        return mesh.build();
    }

    /**
     * How bright the frame is where a patch of ground lands on it.
     *
     * <p>Averaged over a small window rather than read off one pixel: the
     * ground samples a real atlas cell with a pattern in it, and a single pixel
     * would be measuring the texture as much as the light. The two frames being
     * compared sample it at the same place either way, but a window makes the
     * numbers stable enough to put a threshold on.
     */
    private static int luma(int[] pixels, EyeCamera eye, double worldX, double worldY) {
        double[] at = new double[3];
        assertTrue(eye.project(worldX, worldY, 0, at),
                "(" + worldX + ", " + worldY + ") is behind the camera");
        int cx = (int) Math.round(at[0]);
        int cy = (int) Math.round(at[1]);
        long sum = 0;
        int n = 0;
        for (int y = cy - 2; y <= cy + 2; y++) {
            for (int x = cx - 2; x <= cx + 2; x++) {
                if (x < 0 || y < 0 || x >= WIDTH || y >= HEIGHT) continue;
                int argb = pixels[y * WIDTH + x];
                sum += (((argb >> 16) & 0xFF) * 54 + ((argb >> 8) & 0xFF) * 183
                        + (argb & 0xFF) * 19) >> 8;
                n++;
            }
        }
        assertTrue(n > 0, "(" + worldX + ", " + worldY + ") is off the edge of the frame");
        return (int) (sum / n);
    }
}
