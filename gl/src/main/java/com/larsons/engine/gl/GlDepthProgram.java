package com.larsons.engine.gl;

import static org.lwjgl.opengl.GL33C.*;

/**
 * <b>The shader both shadow maps draw with.</b> A vertex position through a
 * matrix, and a fragment shader that does nothing.
 *
 * <p>There are two of these maps — {@link GlShadowMap} for the sun and
 * {@link GlLampShadow} for the brightest lamp — and they disagree about
 * everything except this. One is orthographic and the other a cube of
 * perspectives; one covers eighty metres and the other twelve; one is redrawn
 * when the player walks and the other when the fire is lit. But both want the
 * same thing out of a vertex, which is where it lands and nothing else, so the
 * shader is here and each map keeps its own framebuffer.
 *
 * <p>Each of them holds its <em>own</em> instance rather than sharing one, and
 * that is worth a sentence because sharing would be the obvious tidy-up. The
 * two maps fail independently: a driver can give a 2D depth framebuffer and
 * refuse a cube one, and either map is meant to turn itself off alone and leave
 * the other drawing. Sharing the program would tie those two lifetimes
 * together — and what is duplicated is two shaders of four lines each, compiled
 * once, the first time a sun or a fire actually casts something.
 *
 * <h2>Why the fragment shader is empty, and why there is a second one</h2>
 *
 * <p>A fragment shader that can {@code discard} makes a fragment's depth
 * unknowable until it has run, so a card cannot reject fragments against the
 * depth buffer <em>before</em> shading them. Every modern GPU rejects them at
 * two to four times the rate it shades them, and every one of them switches
 * that off for a shader containing the word {@code discard} — whether or not
 * any fragment ever takes it.
 *
 * <p>So the discard is worth having only where it is doing something. In this
 * game it usually is not: {@code WatchMaterials} paints every opaque material
 * at full alpha and only water below it, and water is translucent and does not
 * cast. But a texture pack with a leaf sheet in it — mostly gaps — would put a
 * black square under every tree in the wood without the test, so both exist and
 * {@code GlMeshPass} looks at the atlas as it uploads it and says which. A pack
 * that introduces a cutout gets the right answer without anybody remembering to
 * ask for it.
 */
final class GlDepthProgram implements AutoCloseable {

    /** Discard below this in the depth pass, matching the opaque pass's cut. */
    static final float ALPHA_CUT = 0.5f;

    /** The empty fragment shader — see the class note. */
    static final int PLAIN = 0;

    /** …and the one that tests the atlas's alpha. */
    static final int CUTOUT = 1;

    private static final String VERTEX = """
            #version 330 core
            layout(location = 0) in vec3 aPos;
            layout(location = 1) in vec2 aUV;
            uniform mat4 uLightMvp;
            out vec2 vUV;
            void main() {
                gl_Position = uLightMvp * vec4(aPos, 1.0);
                vUV = aUV;
            }
            """;

    private static final String PLAIN_FRAGMENT = """
            #version 330 core
            void main() { }
            """;

    private static final String CUTOUT_FRAGMENT = """
            #version 330 core
            in vec2 vUV;
            uniform sampler2D uAtlas;
            uniform float uAlphaCut;
            void main() {
                if (texture(uAtlas, vUV).a < uAlphaCut) discard;
            }
            """;

    private final int[] program = {-1, -1};
    private final int[] uLightMvp = {-1, -1};
    private final int[] uAtlas = {-1, -1};
    private final int[] uAlphaCut = {-1, -1};

    /** Which of the two the pass now open is using. */
    private int variant;

    private boolean built;

    /**
     * Compile and link both variants.
     *
     * @throws IllegalStateException if the driver refuses either, which the
     *         caller turns into "this map does not exist" rather than a crash
     */
    void build() {
        if (built) return;
        buildOne(PLAIN, PLAIN_FRAGMENT);
        buildOne(CUTOUT, CUTOUT_FRAGMENT);
        built = true;
    }

    /**
     * Bind the variant a pass wants and set what it needs.
     *
     * @param cutouts whether anything in the atlas has a hole in it
     */
    void use(boolean cutouts) {
        variant = cutouts ? CUTOUT : PLAIN;
        glUseProgram(program[variant]);
        if (variant == CUTOUT) {
            glUniform1i(uAtlas[variant], GlTerrainProgram.UNIT_ATLAS);
            glUniform1f(uAlphaCut[variant], ALPHA_CUT);
        }
    }

    /** One mesh into whichever map is bound. */
    void cast(int vao, int vertexCount, float[] lightMvp) {
        if (vao < 0 || vertexCount <= 0 || lightMvp == null) return;
        glUniformMatrix4fv(uLightMvp[variant], false, lightMvp);
        glBindVertexArray(vao);
        glDrawArrays(GL_TRIANGLES, 0, vertexCount);
    }

    private void buildOne(int which, String fragment) {
        program[which] = link(compile(GL_VERTEX_SHADER, VERTEX),
                compile(GL_FRAGMENT_SHADER, fragment));
        uLightMvp[which] = glGetUniformLocation(program[which], "uLightMvp");
        uAtlas[which] = glGetUniformLocation(program[which], "uAtlas");
        uAlphaCut[which] = glGetUniformLocation(program[which], "uAlphaCut");
    }

    private static int compile(int type, String source) {
        int shader = glCreateShader(type);
        glShaderSource(shader, source);
        glCompileShader(shader);
        if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE) {
            String log = glGetShaderInfoLog(shader);
            glDeleteShader(shader);
            throw new IllegalStateException("depth shader did not compile: " + log);
        }
        return shader;
    }

    private static int link(int vertex, int fragment) {
        int linked = glCreateProgram();
        glAttachShader(linked, vertex);
        glAttachShader(linked, fragment);
        glBindAttribLocation(linked, GlTerrainProgram.ATTRIB_POSITION, "aPos");
        glBindAttribLocation(linked, GlTerrainProgram.ATTRIB_UV, "aUV");
        glLinkProgram(linked);
        int status = glGetProgrami(linked, GL_LINK_STATUS);
        glDetachShader(linked, vertex);
        glDetachShader(linked, fragment);
        glDeleteShader(vertex);
        glDeleteShader(fragment);
        if (status == GL_FALSE) {
            String log = glGetProgramInfoLog(linked);
            glDeleteProgram(linked);
            throw new IllegalStateException("depth shader did not link: " + log);
        }
        return linked;
    }

    @Override
    public void close() {
        for (int i = 0; i < program.length; i++) {
            if (program[i] >= 0) glDeleteProgram(program[i]);
            program[i] = -1;
        }
        built = false;
    }
}
