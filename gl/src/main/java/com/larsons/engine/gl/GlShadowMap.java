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
 * <p>The honest answer to "what does this cost" is: <b>a second frame</b>, and
 * the first version of it halved the frame rate. Six things bring it down, in
 * rough order of what they were worth:
 *
 * <ul>
 *   <li><b>The map is drawn once and then kept.</b> It is a pure function of
 *       where its box is and what is standing in it, and a player who is not
 *       walking has moved neither — so a still camera pays for the pass once
 *       and reuses it. See {@link #boxMoved} and {@link #castersChanged}; the
 *       box is snapped to {@link #SNAP_TEXELS} of world and the sun to a fifth
 *       of a degree precisely so that "unmoved" is the common answer.</li>
 *   <li><b>The box does not follow the view.</b> Centred on the player rather
 *       than pushed down the view axis, so turning to follow a bird — which is
 *       what this game <em>is</em> — never rebuilds anything.</li>
 *   <li><b>The depth pass has no fragment shader worth the name.</b> An empty
 *       one, so a card can reject fragments against depth before shading them
 *       at two to four times the rate. See {@link #PLAIN_FRAGMENT} for why the
 *       alpha test that used to be there was costing that for nothing.</li>
 *   <li><b>A quarter of the pixels.</b> A 1024² map over eighty metres is a
 *       sixteen-centimetre texel, under the width of the four-tap hardware
 *       kernel that filters it; the 2048² it started at was rasterising four
 *       and a half times a 720p frame every frame.</li>
 *   <li><b>Grass does not cast</b>, and neither does anything rebuilt per
 *       frame. See {@code Mesh.casts}: a quarter of the triangles and a third
 *       of the draw calls, for shadows narrower than a texel.</li>
 *   <li><b>The pass does not happen at all</b> when there is no sun to cast:
 *       at night, under a storm, or under water. See
 *       {@link MeshPass.Sky#castsShadows}.</li>
 * </ul>
 *
 * <p><b>The map covers a disc round the camera</b> rather than the whole view.
 * Shadows are a near-field cue — a tree's shadow at a hundred and fifty metres
 * is two pixels — and spending a fixed budget of texels on everything in sight
 * is how a shadow map ends up with none of it sharp. The main shader fades the
 * term out at the rim, so where the map ends is not a line ruled across the
 * wood.
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

    /**
     * Texels on a side, by default.
     *
     * <p><b>Down from two thousand, and the arithmetic is the argument.</b> A
     * 2048² map is four million depth fragments rasterised every frame — four
     * and a half times a 720p frame, for a term that is one multiply in the
     * shading. At 1024 over the reach below it is a million, which is about
     * what the screen itself costs, and the texel goes from ten centimetres to
     * sixteen: less than the width of the four-tap hardware kernel that filters
     * it, so what is lost is a sharpness the blur was removing anyway.
     */
    private static final int DEFAULT_SIZE = 1024;

    /** The smallest that is worth the pass at all. */
    private static final int MIN_SIZE = 512;

    /**
     * How far from the camera shadows are drawn, in metres.
     *
     * <p>Pulled in with the resolution, and by less: the two together put the
     * texel at sixteen centimetres rather than ten, which is still under the
     * width of a branch. Shadows are a near-field cue — a tree's shadow at
     * eighty metres is a few pixels and the rim fade is already taking it — so
     * this is the cheaper half of the trade to make.
     */
    private static final double REACH = 80;

    /**
     * …and how deep the box is along the sun's own axis.
     *
     * <p>Generous, because what has to fit is not the ground but everything
     * that can cast onto it: with the sun near the horizon a ridge two hundred
     * metres away throws across the whole map, and a box that clipped it would
     * make shadows disappear exactly at the hour they are longest.
     */
    private static final double DEPTH = 2 * REACH + 220;

    /**
     * How many texels the box's centre moves in, when it moves at all.
     *
     * <p><b>The snapping is what stops the shadows crawling; this is what stops
     * them being redrawn.</b> Quantising the box to whole texels already means
     * it holds still until the player has walked a texel's width — sixteen
     * centimetres, which at walking pace is about a twentieth of a second, so
     * the map is rebuilt on nearly every frame and {@link #alreadyDrawn} almost
     * never fires. Quantising to sixteen texels instead means it holds still
     * for two and a half metres, which is most of a second: the same map serves
     * forty frames of walking instead of three.
     *
     * <p>It costs nothing but lag in where the box is centred, bounded by those
     * two and a half metres against a reach of eighty. Sixteen texels is still
     * a whole number of texels, so the grid the snapping exists to align stays
     * exactly as aligned as it was.
     */
    private static final int SNAP_TEXELS = 16;

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

    /**
     * The depth pass's fragment shader when nothing in the atlas has a hole in
     * it — <b>and it is empty, which is the entire point.</b>
     *
     * <p>A fragment shader that can {@code discard} makes a fragment's depth
     * unknowable until it has run, so a card cannot reject fragments against
     * the depth buffer <em>before</em> shading them. Every modern GPU rejects
     * them at two to four times the rate it shades them, and every one of them
     * switches that off for a shader containing the word {@code discard} —
     * whether or not any fragment ever takes it.
     *
     * <p>So the discard is worth having only where it is doing something. In
     * this game it never was: {@code WatchMaterials} paints every opaque
     * material at full alpha and only water below it, and water is translucent
     * and does not cast. The alpha test was costing a texture fetch and the
     * whole early-depth path on every shadow fragment in order to discard
     * nothing at all.
     */
    private static final String PLAIN_FRAGMENT = """
            #version 330 core
            void main() { }
            """;

    /**
     * …and the one for an atlas that <em>does</em> have holes in it.
     *
     * <p>Still needed, and chosen automatically: a leaf sheet is mostly gaps,
     * and a canopy that cast the shadow of its own bounding quad would put a
     * black square under every tree in the wood. {@code GlMeshPass} looks at
     * the atlas as it uploads it and says which of these two to use, so a
     * texture pack that introduces a cutout gets the right answer without
     * anybody remembering to ask for it.
     */
    private static final String CUTOUT_FRAGMENT = """
            #version 330 core
            in vec2 vUV;
            uniform sampler2D uAtlas;
            uniform float uAlphaCut;
            void main() {
                if (texture(uAtlas, vUV).a < uAlphaCut) discard;
            }
            """;

    private final int size;

    private int fbo = -1;
    private int depthTexture = -1;

    /** The two depth programs; see {@link #PLAIN_FRAGMENT}. */
    private final int[] program = {-1, -1};
    private final int[] uLightMvp = {-1, -1};
    private final int[] uAtlas = {-1, -1};
    private final int[] uAlphaCut = {-1, -1};

    /** Which of the two the pass now open is using. */
    private int variant;

    private static final int PLAIN = 0;
    private static final int CUTOUT = 1;

    /** Set once anything refuses; from then on this is a no-op. */
    private boolean unavailable;

    private boolean built;

    /** The frame's matrix, and the scratch the per-mesh product lands in. */
    private Mat4 lightViewProjection;
    private double eyeX, eyeY, eyeZ;
    private final double[] inLightSpace = new double[4];

    /**
     * …and the same matrix as sixteen floats, kept for the frame.
     *
     * <p>{@link #matrixInto} multiplies out of this rather than through
     * {@link Mat4}, because a frame asks for one product per mesh and several
     * hundred meshes: going through the immutable class costs four arrays and
     * four objects each time, which is a couple of thousand allocations a frame
     * for arithmetic that is twelve multiply-adds.
     */
    private float[] lightColumns;

    /**
     * This frame's candidate, before anything has decided to draw it.
     *
     * <p>Held separately from the committed matrix above because the whole
     * question {@link #alreadyDrawn} answers is whether to adopt it — and if
     * the answer is no, the committed one has to survive untouched, camera and
     * all.
     */
    private Mat4 pending;
    private final double[] pendingCentre = new double[3];
    private final double[] pendingSun = new double[3];
    private double pendingEyeX, pendingEyeY, pendingEyeZ;

    /** …and where the box and the sun were when the map was last drawn. */
    private final double[] drawnCentre = {Double.NaN, Double.NaN, Double.NaN};
    private final double[] drawnSun = {Double.NaN, Double.NaN, Double.NaN};
    private long drawnCasters = Long.MIN_VALUE;

    /** Saved across the pass, because this borrows somebody else's context. */
    private int callersFbo;
    private final int[] callersViewport = new int[4];

    GlShadowMap() {
        this.size = requestedSize();
        if (size <= 0) unavailable = true;
    }

    /**
     * How finely the sun's direction is rounded before the box is built from
     * it: one part in {@link #SUN_STEPS} of a unit vector.
     */
    private static final double SUN_STEPS = 512;

    private static double quantise(double component) {
        return Math.rint(component * SUN_STEPS) / SUN_STEPS;
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
     * Work out where the sun's box goes this frame. Touches no GL state.
     *
     * <p>Separate from {@link #openPass} because the caller has to be able to
     * ask "would this frame's map be the one already on the card?" before
     * deciding to spend a pass drawing it, and that question is about the
     * matrix this computes.
     *
     * @return {@code false} when there is nothing to do — no sun, no map, or a
     *         driver that would not give us one
     */
    boolean aim(MeshPass.Sky sky, EyeCamera eye, boolean atlasHasCutouts) {
        if (unavailable || sky == null || eye == null || !sky.castsShadows()) return false;
        if (!build()) return false;
        variant = atlasHasCutouts ? CUTOUT : PLAIN;

        // <b>Centred on the player, not on what they are looking at.</b> An
        // earlier version pushed the box half its reach down the view axis, so
        // that none of it was spent behind the player's head — which is the
        // textbook arrangement and is wrong for this game. Turning to follow a
        // bird would drag the box across the wood and rebuild it, and turning
        // to follow a bird is what a player does all afternoon. Centred here,
        // the box depends on where you stand and not at all on where you look:
        // it costs the far half of the forward reach, which the rim fade was
        // taking anyway, and it buys a map that survives every glance.
        //
        // The sun is quantised for the same reason and it matters more. This
        // clock is the wall clock, so the sun moves a fifteen-thousandth of a
        // degree per frame — never enough to see, always enough to make an
        // exact comparison say the box has moved. Rounded to a fifth of a
        // degree it stands still for about a minute at a time, and a fifth of a
        // degree moves the shadow of a thirty-metre tree by ten centimetres,
        // which is less than one texel of the map it is drawn in.
        pendingSun[0] = quantise(sky.sunX());
        pendingSun[1] = quantise(sky.sunY());
        pendingSun[2] = quantise(sky.sunZ());
        pending = Mat4.sunlight(pendingSun[0], pendingSun[1], pendingSun[2],
                eye.x(), eye.y(), eye.z(),
                eye.x(), eye.y(), eye.z(),
                REACH, DEPTH, size / SNAP_TEXELS, pendingCentre);
        pendingEyeX = eye.x();
        pendingEyeY = eye.y();
        pendingEyeZ = eye.z();
        return true;
    }

    /**
     * <b>Whether the map on the card is already the map this frame wants.</b>
     *
     * <p>A shadow map is a function of two things: where its box is, and what
     * is standing in it. The box is snapped to whole texels and therefore does
     * not move at all until the player has walked a texel's width, and the wood
     * does not move at all. So a player standing still — watching a bird, with
     * the guide open, lining up a shot through the glass — is asking for the
     * same four million depth values that are already there, every frame.
     *
     * <p>Both halves are compared exactly rather than approximately: the
     * matrix, because a box half a texel out would put every shadow half a
     * texel out, and the casters, because a tree that finished meshing has to
     * appear. {@code casters} is the caller's summary of what it is about to
     * submit — see {@code GlMeshPass}.
     *
     * @return {@code true} when the pass can be skipped entirely
     */
    boolean boxMoved() {
        if (lightColumns == null) return true;
        for (int i = 0; i < 3; i++) {
            if (drawnCentre[i] != pendingCentre[i] || drawnSun[i] != pendingSun[i]) {
                return true;
            }
        }
        return false;
    }

    /**
     * Adopt this frame's box as the one both passes will use.
     *
     * <p>Called <b>before</b> the casters are chosen, and that order is not an
     * optimisation. {@link #casts} answers "is this mesh in the box", and the
     * pass then draws whatever it says through {@link #matrixInto}: choose the
     * casters against the old box and draw them through the new matrix and the
     * map is missing whatever the box has just moved onto, for one frame, every
     * time the player walks two and a half metres. That is a shadow that
     * flickers off as you approach it.
     */
    void adoptBox() {
        lightViewProjection = pending;
        lightColumns = pending.columnMajor();
        eyeX = pendingEyeX;
        eyeY = pendingEyeY;
        eyeZ = pendingEyeZ;
        System.arraycopy(pendingCentre, 0, drawnCentre, 0, 3);
        System.arraycopy(pendingSun, 0, drawnSun, 0, 3);
    }

    /** Whether what is standing in the box differs from what was drawn into it. */
    boolean castersChanged(long casters) {
        return drawnCasters != casters;
    }

    /** Bind the map and clear it, ready for {@link #cast}. */
    void openPass(long casters) {
        drawnCasters = casters;

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

        glUseProgram(program[variant]);
        if (variant == CUTOUT) {
            glUniform1i(uAtlas[variant], GlTerrainProgram.UNIT_ATLAS);
            glUniform1f(uAlphaCut[variant], ALPHA_CUT);
        }
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
    void matrixInto(double originX, double originY, double originZ, float[] out) {
        if (lightColumns == null) return;
        float tx = (float) (originX - eyeX);
        float ty = (float) (originY - eyeY);
        float tz = (float) (originZ - eyeZ);
        // Multiplying by a translation leaves the first three columns alone and
        // moves the fourth, so this is twelve multiply-adds rather than
        // sixty-four — and, more to the point, no allocation at all on a path
        // that runs once per mesh per frame.
        System.arraycopy(lightColumns, 0, out, 0, 12);
        for (int row = 0; row < 4; row++) {
            out[12 + row] = lightColumns[row] * tx + lightColumns[4 + row] * ty
                    + lightColumns[8 + row] * tz + lightColumns[12 + row];
        }
    }

    /** One mesh into the map. */
    void cast(int vao, int vertexCount, float[] lightMvp) {
        if (vao < 0 || vertexCount <= 0 || lightMvp == null) return;
        glUniformMatrix4fv(uLightMvp[variant], false, lightMvp);
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
            // <b>Compared by the texture unit, not by the shader.</b> With a
            // comparison mode set, a fetch through a `sampler2DShadow` returns
            // the *fraction* of its four neighbouring texels that pass the test
            // rather than a depth — so `GL_LINEAR` here is two-by-two
            // percentage-closer filtering done in fixed function, for the price
            // of one fetch.
            //
            // That is the whole reason the filter is linear now. Filtering a
            // depth texture and *then* comparing averages distances, which is
            // not a soft shadow but a wrong one along every silhouette;
            // comparing and then filtering is the soft shadow, and it is the
            // only one of the two the hardware will do for free. Four of these
            // taps give what sixteen hand-written ones used to.
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_COMPARE_MODE,
                    GL_COMPARE_REF_TO_TEXTURE);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_COMPARE_FUNC, GL_LEQUAL);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
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

            buildProgram(PLAIN, PLAIN_FRAGMENT);
            buildProgram(CUTOUT, CUTOUT_FRAGMENT);
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

    private void buildProgram(int which, String fragment) {
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

    private void release() {
        for (int i = 0; i < program.length; i++) {
            if (program[i] >= 0) glDeleteProgram(program[i]);
            program[i] = -1;
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
        lightViewProjection = null;
        lightColumns = null;
        drawnCentre[0] = Double.NaN;
        drawnSun[0] = Double.NaN;
        drawnCasters = Long.MIN_VALUE;
    }

    @Override
    public void close() {
        release();
    }
}
