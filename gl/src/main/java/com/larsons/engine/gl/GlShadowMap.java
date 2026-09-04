package com.larsons.engine.gl;

import com.larsons.engine.graphics.EyeCamera;
import com.larsons.engine.graphics.Mat4;
import com.larsons.engine.graphics.MeshPass;

import static org.lwjgl.opengl.GL33C.*;

/**
 * <b>What the trees put on the ground.</b> The world again, from the sun, with
 * nothing recorded but how far away it was.
 *
 * <h2>Why a second pass and not something cheaper</h2>
 *
 * <p>Every other shading term in {@link GlTerrainProgram} is local: a fragment
 * knows its own normal, its own distance and where the lamps are, and can
 * answer from that alone. A shadow is the one question a fragment cannot answer
 * about itself, because the answer is about a <em>different</em> piece of
 * geometry — the branch four metres above it. There is no trick that gets round
 * that. Either the frame is drawn twice, or the wood has no shadows in it.
 *
 * <p>So it is drawn twice: once into a depth texture from the sun's point of
 * view, and once normally, with each fragment comparing its own distance from
 * the sun against the nearest thing the sun could see in that direction. Nearer
 * means something is in the way.
 *
 * <h2>What it costs, and what is done about it</h2>
 *
 * <ul>
 *   <li><b>The draw calls double</b>, for the opaque half only — water and
 *       grass sheets cast nothing. The buffers are the ones the main pass is
 *       about to use, uploaded once, so there is no geometry cost beyond the
 *       second submission.</li>
 *   <li><b>The pass does not happen at all</b> when there is no sun to cast:
 *       at night, under a storm, or under water. See
 *       {@link MeshPass.Sky#castsShadows}.</li>
 *   <li><b>The map covers a disc round the camera</b> rather than the whole
 *       view. Shadows are a near-field cue — a tree's shadow at a hundred and
 *       fifty metres is two pixels — and spending a fixed budget of texels on
 *       everything in sight is how a shadow map ends up with none of it sharp.
 *       The main shader fades the term out at the rim, so where the map ends is
 *       not a line ruled across the wood.</li>
 *   <li><b>Leaves cast leaf shadows.</b> The depth shader alpha-tests against
 *       the same atlas the world does, so a canopy throws dapple rather than
 *       the shadow of a solid box, which is most of the reason to have this in
 *       a wood at all.</li>
 * </ul>
 *
 * <h2>Peter-panning, acne, and the shimmer</h2>
 *
 * <p>The three ways a shadow map goes wrong, and where each is handled:
 *
 * <ol>
 *   <li><b>Acne</b> — a surface shadowing itself, because its own depth in the
 *       map is quantised. A polygon offset here and a slope-scaled bias in the
 *       fragment shader; the second is the load-bearing one, because the error
 *       grows with how obliquely the sun strikes the surface and a flat bias
 *       big enough for a grazing hillside detaches every shadow in the frame.</li>
 *   <li><b>Peter-panning</b> — the shadow separating from the foot of the thing
 *       casting it, which is what too much bias buys. Kept in check by having
 *       the bias in metres mean the same thing everywhere, which is what the
 *       orthographic (and therefore linear-depth) projection gives.</li>
 *   <li><b>The shimmer</b> — handled entirely in {@link Mat4#sunlight}, and the
 *       most important of the three: an unsnapped shadow map crawls as the
 *       player walks and looks far worse than no shadows at all.</li>
 * </ol>
 *
 * <p><b>Every one of the ways this can fail to exist is survivable.</b> No
 * framebuffer, no depth texture, a driver that will not compile the depth
 * shader: the map reports itself unavailable, the main shader's shadow term is
 * switched off with one uniform, and the world draws exactly as it did before —
 * lit, unshadowed, and at full speed.
 */
final class GlShadowMap implements AutoCloseable {

    /**
     * {@code -Dlarsons.render.gl.shadowmap=N} — the map's edge in texels, or
     * {@code 0} to turn shadows off entirely.
     *
     * <p>The same escape hatch {@link GlShaderChain#MAX_LIGHTS_PROPERTY} is:
     * a machine where this pass is the difference between playable and not
     * should be able to say so without a rebuild, and a machine where it is
     * free should be able to ask for more of it.
     */
    static final String SIZE_PROPERTY = "larsons.render.gl.shadowmap";

    /** Texels on a side, by default. */
    private static final int DEFAULT_SIZE = 2048;

    /** The smallest that is worth the pass at all. */
    private static final int MIN_SIZE = 512;

    /**
     * How far from the camera shadows are drawn, in metres.
     *
     * <p>A hundred metres of radius at two thousand texels is a shadow texel
     * roughly ten centimetres across, which is about the width of a branch —
     * the point at which more resolution stops being visible and more distance
     * starts costing sharpness everywhere.
     */
    private static final double REACH = 100;

    /**
     * …and how deep the box is along the sun's own axis.
     *
     * <p>Generous, because what has to fit is not the ground but everything
     * that can cast onto it: with the sun near the horizon a ridge two hundred
     * metres away throws across the whole map, and a box that clipped it would
     * make shadows disappear exactly at the hour they are longest.
     */
    private static final double DEPTH = 2 * REACH + 220;

    /** Discard below this in the depth pass, matching the opaque pass's cut. */
    private static final float ALPHA_CUT = 0.5f;

    /** Depth-buffer slope offset, in the driver's own units. */
    private static final float POLYGON_OFFSET_SLOPE = 2.4f;

    private static final float POLYGON_OFFSET_UNITS = 4.0f;

    /**
     * The shader's own bias, as a fraction of the box's depth.
     *
     * <p>In depth-buffer units rather than metres because that is what the
     * comparison is in; {@link #DEPTH} converts. A quarter of a metre flat,
     * and up to six times that where the sun rakes across a slope.
     */
    private static final double FLAT_BIAS_METRES = 0.16;

    private static final double SLOPE_BIAS_METRES = 0.42;

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

    private static final String FRAGMENT = """
            #version 330 core
            in vec2 vUV;
            uniform sampler2D uAtlas;
            uniform float uAlphaCut;
            void main() {
                // Nothing is written but depth — the framebuffer has no colour
                // attachment at all. The one thing this shader is for is the
                // discard: a leaf sheet is mostly gaps, and a canopy that cast
                // the shadow of its own bounding quad would put a black square
                // under every tree in the wood.
                if (texture(uAtlas, vUV).a < uAlphaCut) discard;
            }
            """;

    private final int size;

    private int fbo = -1;
    private int depthTexture = -1;
    private int program = -1;
    private int uLightMvp = -1;
    private int uAtlas = -1;
    private int uAlphaCut = -1;

    /** Set once anything refuses; from then on this is a no-op. */
    private boolean unavailable;

    private boolean built;

    /** The frame's matrix, and the scratch the per-mesh product lands in. */
    private Mat4 lightViewProjection;
    private double eyeX, eyeY, eyeZ;
    private final double[] inLightSpace = new double[4];

    /** Saved across the pass, because this borrows somebody else's context. */
    private int callersFbo;
    private final int[] callersViewport = new int[4];

    GlShadowMap() {
        this.size = requestedSize();
        if (size <= 0) unavailable = true;
    }

    private static int requestedSize() {
        String asked = System.getProperty(SIZE_PROPERTY);
        if (asked == null || asked.isBlank()) return DEFAULT_SIZE;
        try {
            int n = Integer.parseInt(asked.trim());
            return n <= 0 ? 0 : Math.max(MIN_SIZE, n);
        } catch (NumberFormatException e) {
            return DEFAULT_SIZE;
        }
    }

    /** Whether a map exists to sample; {@code false} switches the term off. */
    boolean available() {
        return !unavailable && built;
    }

    /** One texel of the map, in UV — what the shader's kernel steps by. */
    float texel() {
        return available() ? 1f / size : 0f;
    }

    /**
     * The flat part of the depth comparison's bias, in buffer units.
     *
     * <p>Metres divided by the box's depth, because that is exactly what the
     * shader's comparison is in: the projection maps the box across normalised
     * device coordinates and the viewport maps those onto {@code [0, 1]}, so
     * one unit of the depth the shader reads back <em>is</em> {@link #DEPTH}
     * metres. Being able to say that in one line is the reason the projection
     * is orthographic and the depth linear.
     */
    float flatBias() {
        return (float) (FLAT_BIAS_METRES / DEPTH);
    }

    /** …and the part that grows with how obliquely the sun strikes. */
    float slopeBias() {
        return (float) (SLOPE_BIAS_METRES / DEPTH);
    }

    int texture() {
        return depthTexture;
    }

    /**
     * Aim the sun at the ground in front of the camera and open the pass.
     *
     * @return {@code false} when there is nothing to do — no sun, no map, or a
     *         driver that would not give us one — in which case nothing has
     *         been touched and {@link #end} must not be called
     */
    boolean begin(MeshPass.Sky sky, EyeCamera eye) {
        if (unavailable || sky == null || eye == null || !sky.castsShadows()) return false;
        if (!build()) return false;

        // Centred ahead of where the player is looking rather than on them:
        // half the map would otherwise be spent on the ground behind their
        // head. Only the horizontal part of the forward axis, so looking up at
        // the canopy does not throw the box into the sky.
        double ahead = REACH * 0.55;
        double centreX = eye.x() + eye.forwardX() * ahead;
        double centreY = eye.y() + eye.forwardY() * ahead;
        lightViewProjection = Mat4.sunlight(sky.sunX(), sky.sunY(), sky.sunZ(),
                centreX, centreY, eye.z(),
                eye.x(), eye.y(), eye.z(),
                REACH, DEPTH, size);
        eyeX = eye.x();
        eyeY = eye.y();
        eyeZ = eye.z();

        callersFbo = glGetInteger(GL_FRAMEBUFFER_BINDING);
        glGetIntegerv(GL_VIEWPORT, callersViewport);

        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glViewport(0, 0, size, size);
        glEnable(GL_DEPTH_TEST);
        glDepthFunc(GL_LEQUAL);
        glDepthMask(true);
        glClear(GL_DEPTH_BUFFER_BIT);
        glDisable(GL_BLEND);
        // No cull. Half of what casts here is a single-sided sheet — a leaf
        // spray, a grass blade, the flat of a fern — and culling either winding
        // deletes about half the canopy from the map, which reads as a wood
        // whose shadows have holes in it for no reason anybody can see.
        glDisable(GL_CULL_FACE);
        glEnable(GL_POLYGON_OFFSET_FILL);
        glPolygonOffset(POLYGON_OFFSET_SLOPE, POLYGON_OFFSET_UNITS);

        glUseProgram(program);
        glUniform1i(uAtlas, GlTerrainProgram.UNIT_ATLAS);
        glUniform1f(uAlphaCut, ALPHA_CUT);
        return true;
    }

    /**
     * How far outside the box a mesh's <em>origin</em> may be and still have
     * some of the mesh inside it.
     *
     * <p>A {@link MeshPass.Draw} carries an origin and no extent — the bounding
     * box it was culled against belongs to the mesher, on the other side of the
     * seam — so the test below has to be generous by however large a mesh can
     * be. A terrain chunk is thirty-two metres across and its trees put another
     * twenty on top of it, so fifty is safe and still throws away most of a
     * frame at any render distance worth having.
     */
    private static final double CASTER_MARGIN = 50;

    /**
     * Whether a mesh at this origin could put anything on the map.
     *
     * <p><b>Worth the test, because the alternative is drawing the whole render
     * distance twice.</b> The box is two hundred metres across and a card is
     * asked for five hundred; without this, every chunk on the horizon would be
     * submitted a second time to be clipped away by a projection it was never
     * inside. Measured across the sun's own axis rather than as a plain
     * distance, because along that axis the box is deliberately enormous — a
     * ridge two hundred metres up the hill still casts down onto the ground in
     * front of you.
     */
    boolean casts(double originX, double originY, double originZ) {
        if (lightViewProjection == null) return false;
        lightViewProjection.transform(originX - eyeX, originY - eyeY, originZ - eyeZ,
                inLightSpace);
        // Normalised device coordinates, where the box is the unit square: one
        // unit is REACH metres, so the margin converts the same way.
        double edge = 1 + CASTER_MARGIN / REACH;
        return Math.abs(inLightSpace[0]) <= edge && Math.abs(inLightSpace[1]) <= edge;
    }

    /**
     * The matrix a mesh at this origin casts through — <b>and the same one the
     * main pass must look it up with.</b>
     *
     * <p>Handed back rather than kept, so that the two passes provably use one
     * matrix per mesh: computing it twice from the same inputs would be correct
     * until the day one of the two callers rounded something, at which point
     * every shadow in the world would be a few centimetres out and nothing
     * would say so.
     */
    float[] matrixFor(double originX, double originY, double originZ) {
        if (lightViewProjection == null) return null;
        return lightViewProjection.times(Mat4.translation(
                originX - eyeX, originY - eyeY, originZ - eyeZ)).columnMajor();
    }

    /** One mesh into the map. */
    void cast(int vao, int vertexCount, float[] lightMvp) {
        if (vao < 0 || vertexCount <= 0 || lightMvp == null) return;
        glUniformMatrix4fv(uLightMvp, false, lightMvp);
        glBindVertexArray(vao);
        glDrawArrays(GL_TRIANGLES, 0, vertexCount);
    }

    /** Close the pass and put the caller's framebuffer and viewport back. */
    void end() {
        glDisable(GL_POLYGON_OFFSET_FILL);
        glPolygonOffset(0, 0);
        glBindFramebuffer(GL_FRAMEBUFFER, callersFbo);
        glViewport(callersViewport[0], callersViewport[1],
                callersViewport[2], callersViewport[3]);
    }

    /** Bind the map where {@link GlTerrainProgram} expects to sample it. */
    void bind() {
        if (!available()) return;
        glActiveTexture(GL_TEXTURE0 + GlTerrainProgram.UNIT_SHADOW);
        glBindTexture(GL_TEXTURE_2D, depthTexture);
        glActiveTexture(GL_TEXTURE0 + GlTerrainProgram.UNIT_ATLAS);
    }

    /**
     * Make the texture, the framebuffer and the shader, once.
     *
     * <p>Everything here is checked rather than assumed, and any refusal is
     * final: a driver that will not give a depth-only framebuffer this frame
     * will not give one next frame either, and asking again every frame is how
     * a fallback becomes a stutter.
     */
    private boolean build() {
        if (built) return true;
        if (unavailable) return false;
        try {
            depthTexture = glGenTextures();
            int previousTexture = glGetInteger(GL_TEXTURE_BINDING_2D);
            glBindTexture(GL_TEXTURE_2D, depthTexture);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_DEPTH_COMPONENT24, size, size, 0,
                    GL_DEPTH_COMPONENT, GL_FLOAT, (java.nio.ByteBuffer) null);
            // Nearest, and the kernel in the shader does the softening. A linear
            // filter on a depth texture averages *distances* and then compares
            // once, which is not a soft shadow — it is a wrong one, along every
            // silhouette in the frame.
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_BORDER);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_BORDER);
            // Outside the map is "the sun saw nothing at all", which is lit.
            // The shader bounds-checks as well, but a border that defaulted to
            // black would make every edge case a shadow rather than a light.
            glTexParameterfv(GL_TEXTURE_2D, GL_TEXTURE_BORDER_COLOR,
                    new float[] {1f, 1f, 1f, 1f});
            glBindTexture(GL_TEXTURE_2D, previousTexture);

            int previousFbo = glGetInteger(GL_FRAMEBUFFER_BINDING);
            fbo = glGenFramebuffers();
            glBindFramebuffer(GL_FRAMEBUFFER, fbo);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D,
                    depthTexture, 0);
            // No colour, and saying so is not optional: a framebuffer with a
            // draw buffer it has no attachment for is incomplete on a
            // conformant driver and silently fine on a lax one.
            glDrawBuffer(GL_NONE);
            glReadBuffer(GL_NONE);
            int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
            glBindFramebuffer(GL_FRAMEBUFFER, previousFbo);
            if (status != GL_FRAMEBUFFER_COMPLETE) {
                throw new IllegalStateException("depth framebuffer incomplete: 0x"
                        + Integer.toHexString(status));
            }

            program = link(compile(GL_VERTEX_SHADER, VERTEX),
                    compile(GL_FRAGMENT_SHADER, FRAGMENT));
            uLightMvp = glGetUniformLocation(program, "uLightMvp");
            uAtlas = glGetUniformLocation(program, "uAtlas");
            uAlphaCut = glGetUniformLocation(program, "uAlphaCut");
            built = true;
            return true;
        } catch (RuntimeException e) {
            System.err.println("[gl] no shadow map on this driver, "
                    + "the world will be lit but unshadowed: " + e.getMessage());
            unavailable = true;
            release();
            return false;
        }
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

    private void release() {
        if (program >= 0) {
            glDeleteProgram(program);
            program = -1;
        }
        if (fbo >= 0) {
            glDeleteFramebuffers(fbo);
            fbo = -1;
        }
        if (depthTexture >= 0) {
            glDeleteTextures(depthTexture);
            depthTexture = -1;
        }
        built = false;
    }

    @Override
    public void close() {
        release();
    }
}
