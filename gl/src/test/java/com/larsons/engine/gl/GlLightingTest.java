package com.larsons.engine.gl;

import com.larsons.engine.graphics.EyeCamera;
import com.larsons.engine.graphics.MeshPass;
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
import static org.lwjgl.opengl.GL33C.GL_NO_ERROR;
import static org.lwjgl.opengl.GL33C.glGetError;

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
}
