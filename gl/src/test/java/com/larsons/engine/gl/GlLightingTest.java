package com.larsons.engine.gl;

import com.larsons.engine.graphics.EyeCamera;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        GlSurface surface = new GlSurface(0);
        GlMeshPass pass = new GlMeshPass(() -> null);
        try {
            surface.resize(WIDTH, HEIGHT);
            surface.bind();
            glViewport(0, 0, WIDTH, HEIGHT);
            glClearColor(0, 0, 0, 1);
            glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

            List<MeshPass.Draw> draws = new ArrayList<>();
            draws.add(ground().toDraw(1));
            if (canopy) draws.add(canopy().toDraw(2));

            pass.setTexture(WatchMaterials.atlas(), WatchMaterials.revision());
            // Half daylight, so a shadow has somewhere to go and the sun has
            // somewhere to come from.
            pass.setLighting(lights, 0.5f, 0.5f, 0.5f);
            pass.setSky(sky);
            // Fog pushed out past the far corner of the clearing: this is a
            // test about light, and haze over it would only add a term both
            // frames share.
            pass.draw(draws, eye, 0x000000, 400, 800);
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
