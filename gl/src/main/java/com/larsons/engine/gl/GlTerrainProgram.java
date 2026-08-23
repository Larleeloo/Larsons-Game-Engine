package com.larsons.engine.gl;

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
 */
final class GlTerrainProgram implements AutoCloseable {

    /** Vertex attribute slots, matched by {@link GlSectionArena}. */
    static final int ATTRIB_POSITION = 0;
    static final int ATTRIB_UV = 1;
    static final int ATTRIB_COLOR = 2;

    private static final String VERTEX = """
            #version 330 core
            layout(location = 0) in vec3 aPos;
            layout(location = 1) in vec2 aUV;
            layout(location = 2) in vec4 aColor;

            uniform mat4 uMvp;
            uniform mat4 uModelView;

            out vec2 vUV;
            out vec4 vColor;
            out float vDepth;

            void main() {
                gl_Position = uMvp * vec4(aPos, 1.0);
                vUV = aUV;
                vColor = aColor;
                // Distance from the eye, for fog. The view matrix's third row
                // is the camera's forward axis, so the transformed z is exactly
                // how far in front of the eye this vertex is.
                vDepth = (uModelView * vec4(aPos, 1.0)).z;
            }
            """;

    private static final String FRAGMENT = """
            #version 330 core
            in vec2 vUV;
            in vec4 vColor;
            in float vDepth;

            uniform sampler2D uAtlas;
            uniform vec4 uFog;        // rgb = colour, a = 1 when fog is on
            uniform vec2 uFogRange;   // x = start, y = end
            uniform float uAlphaCut;  // discard below this; 0 in the blended pass

            out vec4 fragColor;

            void main() {
                // Every block has an atlas cell — a sheetless one shares a
                // white cell — so this is unconditional and a section of mixed
                // blocks is still one draw call. See BlockAtlas.
                vec4 colour = texture(uAtlas, vUV) * vColor;
                if (colour.a < uAlphaCut) discard;
                if (uFog.a > 0.0) {
                    float t = clamp((vDepth - uFogRange.x)
                                    / max(1.0, uFogRange.y - uFogRange.x), 0.0, 1.0);
                    colour.rgb = mix(colour.rgb, uFog.rgb, t);
                }
                fragColor = colour;
            }
            """;

    private final int program;
    private final int uMvp;
    private final int uModelView;
    private final int uAtlas;
    private final int uFog;
    private final int uFogRange;
    private final int uAlphaCut;

    GlTerrainProgram() {
        program = link(compile(GL_VERTEX_SHADER, VERTEX), compile(GL_FRAGMENT_SHADER, FRAGMENT));
        uMvp = glGetUniformLocation(program, "uMvp");
        uModelView = glGetUniformLocation(program, "uModelView");
        uAtlas = glGetUniformLocation(program, "uAtlas");
        uFog = glGetUniformLocation(program, "uFog");
        uFogRange = glGetUniformLocation(program, "uFogRange");
        uAlphaCut = glGetUniformLocation(program, "uAlphaCut");
    }

    void use() {
        glUseProgram(program);
        glUniform1i(uAtlas, 0);
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
