package com.larsons.engine.gl;

import com.larsons.engine.graphics.EyeCamera;
import com.larsons.engine.graphics.MeshPass;

import java.util.List;
import java.util.Locale;

import static org.lwjgl.opengl.GL33C.*;

/**
 * The shader the world is drawn with — one matrix, one texture, and fog.
 *
 * <p><b>As small as a terrain shader can be, on purpose.</b> Everything a
 * fancier one would compute per fragment has already been computed and baked
 * into the vertex colour by {@code SectionMesher}: the face's shade, its
 * ambient occlusion, the block's own tint. That is not a shortcut, it is the
 * arrangement that lets the geometry be uploaded once and never touched again —
 * a lighting model evaluated at draw time is a lighting model that has to be
 * re-uploaded when anything moves.
 *
 * <p><b>The matrix is model-view-projection, per section.</b> Vertices arrive
 * relative to their section's own corner (see {@code SectionMesh}), so the
 * model matrix is a translation and the whole product is built on the CPU in
 * {@code double} and handed down as sixteen floats. A world sixty-five thousand
 * blocks across cannot be expressed in float at block precision, and this is
 * how every renderer of that size avoids having to.
 *
 * <p><b>Fog is linear in eye distance</b> and mixes toward the sky's own
 * horizon colour, which is what makes the edge of the render distance read as
 * haze rather than as the place the world stops. It matches
 * {@code SolidPainter}'s fog by construction: same start, same end, same
 * colour, so a player switching backends sees the same weather.
 *
 * <p><b>Alpha is tested, not just blended.</b> A leaf sheet is mostly nothing,
 * and a fragment that is nothing must not write depth — otherwise the gaps in a
 * canopy occlude whatever is behind them. Discarding below a threshold in the
 * opaque pass is what every voxel renderer does with cutout geometry.
 *
 * <h2>The one thing that cannot be baked</h2>
 *
 * <p>Everything above holds for the sun. It does not hold for a lantern
 * somebody is <em>carrying</em>: that light moves every frame, and a lighting
 * model baked into vertex colours would have to re-mesh a forest because a
 * walker took a step. So the shader grew a second half — a daylight multiplier
 * and up to {@link com.larsons.engine.graphics.MeshPass#MAX_LIGHTS} point
 * lights, as uniforms, applied per fragment. The geometry still never changes;
 * the light does.
 *
 * <p><b>And it needs a normal, which this vertex format does not have.</b>
 * Adding one would be four more bytes on every vertex of every chunk in memory,
 * to carry a number that is the same for all three vertices of a flat-shaded
 * triangle. The fragment shader derives it instead, from the screen-space
 * gradient of the eye-space position: {@code cross(dFdx(p), dFdy(p))} is
 * exactly the plane the triangle lies in, costs two instructions, and is free
 * of the one thing an interpolated normal would give — smoothing, which is
 * precisely what this world does not want.
 *
 * <p><b>Neutral unless told otherwise.</b> A GLSL uniform starts at zero, so a
 * daylight multiplier nobody set would draw a black world; {@link #use} resets
 * the lighting block to "full daylight, no lamps" every time the program is
 * bound. That is what lets {@link GlTerrainPass} — which knows nothing about
 * any of this and has its own lighting baked in by {@code SectionMesher} —
 * share the program and be entirely unaffected.
 */
final class GlTerrainProgram implements AutoCloseable {

    /** Vertex attribute slots, matched by {@link GlSectionArena}. */
    static final int ATTRIB_POSITION = 0;
    static final int ATTRIB_UV = 1;
    static final int ATTRIB_COLOR = 2;

    /** How many point lights the fragment shader will walk. */
    private static final int MAX_LIGHTS = MeshPass.MAX_LIGHTS;

    /**
     * How much of a light a surface facing away from it still gets.
     *
     * <p>The same constant the CPU painter uses, and it has to be: the two
     * paths draw the same world, and a wrap term that differed would be a
     * campfire lighting the underside of a branch on one machine and not on
     * another. Read off the seam rather than written out here, so there is one
     * number. See {@link com.larsons.engine.graphics.MeshPass#LIGHT_WRAP}.
     */
    private static final float WRAP = (float) MeshPass.LIGHT_WRAP;

    private static final String VERTEX = """
            #version 330 core
            layout(location = 0) in vec3 aPos;
            layout(location = 1) in vec2 aUV;
            layout(location = 2) in vec4 aColor;

            uniform mat4 uMvp;
            uniform mat4 uModelView;

            out vec2 vUV;
            out vec4 vColor;
            out vec3 vEye;

            void main() {
                gl_Position = uMvp * vec4(aPos, 1.0);
                vUV = aUV;
                vColor = aColor;
                // Where this vertex is in the camera's own frame. Its z is the
                // distance in front of the eye, which is what the fog reads —
                // the view matrix's third row is the camera's forward axis. The
                // other two components are what the lighting needs: a point
                // light arrives already in this frame, so a fragment can
                // subtract without a second matrix.
                vEye = (uModelView * vec4(aPos, 1.0)).xyz;
            }
            """;

    private static final String FRAGMENT = """
            #version 330 core
            in vec2 vUV;
            in vec4 vColor;
            in vec3 vEye;

            uniform sampler2D uAtlas;
            uniform vec4 uFog;        // rgb = colour, a = 1 when fog is on
            uniform vec2 uFogRange;   // x = start, y = end
            uniform float uAlphaCut;  // discard below this; 0 in the blended pass

            uniform vec3 uDaylight;   // the hour, as a per-channel multiplier
            uniform int uLightCount;
            uniform vec4 uLightPos[MAX_LIGHTS];    // xyz eye-space, w = radius
            uniform vec4 uLightColour[MAX_LIGHTS]; // rgb, a = intensity

            out vec4 fragColor;

            void main() {
                // Every block has an atlas cell — a sheetless one shares a
                // white cell — so this is unconditional and a section of mixed
                // blocks is still one draw call. See BlockAtlas.
                vec4 colour = texture(uAtlas, vUV) * vColor;
                if (colour.a < uAlphaCut) discard;

                vec3 light = uDaylight;
                if (uLightCount > 0) {
                    // The face's own normal, out of the screen-space gradient
                    // of the eye-space position. This world is flat shaded, so
                    // the plane a fragment lies in *is* its triangle's plane
                    // and this is exact rather than an approximation — and it
                    // costs no vertex attribute, no re-mesh and no upload.
                    //
                    // Turned toward the eye afterwards, because grass, leaves
                    // and water are single-sided sheets meant to be seen from
                    // either face: a lantern behind a blade of grass has to
                    // light the side you are looking at.
                    vec3 n = normalize(cross(dFdx(vEye), dFdy(vEye)));
                    if (dot(n, vEye) > 0.0) n = -n;
                    for (int i = 0; i < uLightCount; i++) {
                        vec3 to = uLightPos[i].xyz - vEye;
                        float radius = max(uLightPos[i].w, 0.0001);
                        float distance = length(to);
                        // Compact falloff: a light is either inside a
                        // fragment's reckoning or costs it nothing. Squared, so
                        // the pool under a lantern has an edge that reads as
                        // light rather than as a circle drawn on the ground.
                        float fall = max(0.0, 1.0 - distance / radius);
                        fall *= fall;
                        float ndotl = max(0.0, dot(n, to / max(distance, 0.0001)));
                        light += uLightColour[i].rgb * (uLightColour[i].a * fall
                                * (WRAP + (1.0 - WRAP) * ndotl));
                    }
                }
                colour.rgb *= light;

                // Fog last: light multiplies and haze interpolates, so a lit
                // thing a long way off is still mostly haze. Applying them the
                // other way round would let a fire beside the camera brighten
                // the horizon.
                if (uFog.a > 0.0) {
                    float t = clamp((vEye.z - uFogRange.x)
                                    / max(1.0, uFogRange.y - uFogRange.x), 0.0, 1.0);
                    colour.rgb = mix(colour.rgb, uFog.rgb, t);
                }
                fragColor = colour;
            }
            """
            .replace("MAX_LIGHTS", String.valueOf(MAX_LIGHTS))
            .replace("WRAP", String.format(Locale.ROOT, "%.4f", WRAP));

    private final int program;
    private final int uMvp;
    private final int uModelView;
    private final int uAtlas;
    private final int uFog;
    private final int uFogRange;
    private final int uAlphaCut;
    private final int uDaylight;
    private final int uLightCount;
    private final int uLightPos;
    private final int uLightColour;

    /** Reused, so setting the lights on a frame allocates nothing. */
    private final float[] lightPos = new float[MAX_LIGHTS * 4];
    private final float[] lightColour = new float[MAX_LIGHTS * 4];
    private final double[] inEyeSpace = new double[3];

    GlTerrainProgram() {
        program = link(compile(GL_VERTEX_SHADER, VERTEX), compile(GL_FRAGMENT_SHADER, FRAGMENT));
        uMvp = glGetUniformLocation(program, "uMvp");
        uModelView = glGetUniformLocation(program, "uModelView");
        uAtlas = glGetUniformLocation(program, "uAtlas");
        uFog = glGetUniformLocation(program, "uFog");
        uFogRange = glGetUniformLocation(program, "uFogRange");
        uAlphaCut = glGetUniformLocation(program, "uAlphaCut");
        uDaylight = glGetUniformLocation(program, "uDaylight");
        uLightCount = glGetUniformLocation(program, "uLightCount");
        // An array's location is the location of its first element, and that is
        // the spelling a driver answers to. `uLightPos` on its own returns −1 on
        // some of them, which is a silent no-op rather than an error.
        uLightPos = glGetUniformLocation(program, "uLightPos[0]");
        uLightColour = glGetUniformLocation(program, "uLightColour[0]");
    }

    /**
     * Whether the driver kept every uniform the lighting block needs.
     *
     * <p>For {@code GlLightingTest}, and worth the method: a uniform array's
     * location is looked up by the spelling {@code uLightPos[0]}, and getting
     * that wrong returns −1, which {@code glUniform4fv} accepts in silence. The
     * failure mode is a world that draws perfectly and is never lit by
     * anything — exactly the kind of thing that looks like "the lights are too
     * dim" for an afternoon.
     */
    boolean lightingUniformsResolved() {
        return uDaylight >= 0 && uLightCount >= 0 && uLightPos >= 0
                && uLightColour >= 0;
    }

    void use() {
        glUseProgram(program);
        glUniform1i(uAtlas, 0);
        // Neutral until somebody says otherwise. A GLSL uniform starts at zero,
        // so a caller that does not light its world — GlTerrainPass, whose
        // shading is baked in by SectionMesher — would otherwise draw it black.
        glUniform3f(uDaylight, 1f, 1f, 1f);
        glUniform1i(uLightCount, 0);
    }

    /**
     * The hour and the lamps, for the frame about to be drawn.
     *
     * <p>Light positions arrive in <b>world</b> coordinates and are put into eye
     * space here, in {@code double}, by the same camera transform every vertex
     * goes through: {@code EyeCamera.toEye} is a rewriting of the view matrix's
     * rows and the two cannot drift. That is why the shader can subtract a
     * light from {@code vEye} directly — both are in the camera's frame, both
     * are small numbers near the origin, and the world's absolute coordinates
     * (which are tens of thousands of metres and do not fit in a float at
     * centimetre precision) never reach the card at all.
     */
    void setLighting(List<MeshPass.Light> lights, EyeCamera eye,
                     float dayR, float dayG, float dayB) {
        glUniform3f(uDaylight, dayR, dayG, dayB);
        int count = lights == null ? 0 : Math.min(lights.size(), MAX_LIGHTS);
        glUniform1i(uLightCount, count);
        if (count == 0 || eye == null) return;
        for (int i = 0; i < count; i++) {
            MeshPass.Light light = lights.get(i);
            eye.toEye(light.x(), light.y(), light.z(), inEyeSpace);
            lightPos[i * 4] = (float) inEyeSpace[0];
            lightPos[i * 4 + 1] = (float) inEyeSpace[1];
            lightPos[i * 4 + 2] = (float) inEyeSpace[2];
            lightPos[i * 4 + 3] = light.radius();
            lightColour[i * 4] = light.r();
            lightColour[i * 4 + 1] = light.g();
            lightColour[i * 4 + 2] = light.b();
            lightColour[i * 4 + 3] = light.intensity();
        }
        // The whole array goes up rather than the used prefix, so this costs no
        // allocation on a frame: sixty-four floats is nothing to upload, and
        // the entries past uLightCount are never read because the shader's loop
        // is bounded by it.
        glUniform4fv(uLightPos, lightPos);
        glUniform4fv(uLightColour, lightColour);
    }

    void setMatrices(float[] mvp, float[] modelView) {
        glUniformMatrix4fv(uMvp, false, mvp);
        glUniformMatrix4fv(uModelView, false, modelView);
    }

    void setFog(int argb, double start, double end, boolean on) {
        glUniform4f(uFog, ((argb >> 16) & 0xFF) / 255f, ((argb >> 8) & 0xFF) / 255f,
                (argb & 0xFF) / 255f, on ? 1f : 0f);
        glUniform2f(uFogRange, (float) start, (float) end);
    }

    void setAlphaCut(float cut) {
        glUniform1f(uAlphaCut, cut);
    }


    private static int compile(int type, String source) {
        int shader = glCreateShader(type);
        glShaderSource(shader, source);
        glCompileShader(shader);
        if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE) {
            String log = glGetShaderInfoLog(shader);
            glDeleteShader(shader);
            throw new IllegalStateException("terrain shader did not compile: " + log);
        }
        return shader;
    }

    private static int link(int vertex, int fragment) {
        int program = glCreateProgram();
        glAttachShader(program, vertex);
        glAttachShader(program, fragment);
        glBindAttribLocation(program, ATTRIB_POSITION, "aPos");
        glBindAttribLocation(program, ATTRIB_UV, "aUV");
        glBindAttribLocation(program, ATTRIB_COLOR, "aColor");
        glLinkProgram(program);
        int status = glGetProgrami(program, GL_LINK_STATUS);
        glDetachShader(program, vertex);
        glDetachShader(program, fragment);
        glDeleteShader(vertex);
        glDeleteShader(fragment);
        if (status == GL_FALSE) {
            String log = glGetProgramInfoLog(program);
            glDeleteProgram(program);
            throw new IllegalStateException("terrain shader did not link: " + log);
        }
        return program;
    }

    @Override
    public void close() {
        glDeleteProgram(program);
    }
}
