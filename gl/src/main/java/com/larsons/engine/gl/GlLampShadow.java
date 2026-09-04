package com.larsons.engine.gl;

import com.larsons.engine.graphics.EyeCamera;
import com.larsons.engine.graphics.LightCull;
import com.larsons.engine.graphics.Mat4;
import com.larsons.engine.graphics.MeshPass;

import java.util.List;

import static org.lwjgl.opengl.GL33C.*;

/**
 * <b>What the trees put on the ground at night.</b> The same idea as
 * {@link GlShadowMap}, from a fire instead of the sun — and therefore in six
 * directions at once, because a flame does not have one.
 *
 * <h2>Why a campfire needs its own map</h2>
 *
 * <p>Before this, a wood at night had no shadows in it at all: the sun's map is
 * switched off after dark (see {@code SkyLight}, and it is right to be — the
 * moon's directional term is a twentieth of daylight and its shadows would be a
 * hundredth of the frame). But a fire two metres from a trunk is not a
 * twentieth of anything. It is the brightest thing for a hundred metres, and a
 * trunk standing in front of it should throw a black stripe across the whole
 * clearing. Without one, a camp at night reads as a wood that has been evenly
 * tinted orange, which is exactly the flat look the vertex bake was there to
 * avoid.
 *
 * <p>The long shadow is the whole point, and it is the thing a lamp gives that
 * the sun cannot. Sunlight is parallel, so a tree's shadow is the width of the
 * tree; a fire on the ground is a <em>point</em>, so the same tree's shadow
 * spreads as it goes and reaches the far side of the clearing. That divergence
 * is free here — it is what a perspective projection does — and it is most of
 * why firelight looks like firelight.
 *
 * <h2>One lamp, six faces, and then kept</h2>
 *
 * <p>A cube map, because a point light shines everywhere and a cube is the one
 * shape a GPU can look up by direction in a single fetch. Six faces of
 * {@link #DEFAULT_FACE}² is more depth than the sun's single map — which would
 * be ruinous if it were drawn every frame, so it is not:
 *
 * <ul>
 *   <li><b>One lamp gets it</b>, the one that matters most from where the
 *       camera is standing ({@link LightCull#airScore}, the same ranking the
 *       air budget uses), and only if the camera is inside
 *       {@link #REACH_MULTIPLE} of its own radius. A camp of six lanterns
 *       therefore costs one map, not six, and the five that do not have it are
 *       lit exactly as they were before.</li>
 *   <li><b>Only what is inside the lamp</b> casts into it. A campfire reaches
 *       twelve metres, which is one terrain chunk and its trees — a handful of
 *       meshes out of the several hundred in a frame.</li>
 *   <li><b>Each of those goes only into the faces it is actually in.</b> A
 *       ninety-degree frustum whose apex is the lamp has four planes through
 *       that apex, so the test is four dot products and it typically puts a
 *       mesh in one face rather than six.</li>
 *   <li><b>And then the map is kept.</b> A fire does not move and neither does
 *       a wood, so it is drawn on the frame you arrive and reused until one of
 *       the two changes. Nothing rebuilt per frame casts — the animals, the
 *       walkers, the player's own hands are all {@code casts(false)} already —
 *       so "the wood changed" means a chunk finished meshing, which is rare and
 *       is exactly when the map <em>should</em> be redrawn.</li>
 *   <li><b>It does not exist at all in daylight.</b> A fire at noon casts no
 *       shadow you could see, so there is nothing to draw; the strength ramps
 *       up through dusk rather than switching, so there is no frame where it
 *       appears. See {@code GlMeshPass.lampShadowStrength}.</li>
 * </ul>
 *
 * <p>What is <em>not</em> shadowed is the lit air — the broad glow a fire puts
 * into mist. Volumetric shadows mean marching the ray through this map rather
 * than fetching it once, which is a different order of cost, and the artefact
 * it leaves is subtle: a tree in front of a fire blocks the ground behind it
 * but not quite all of the haze above it. Against having no night shadows at
 * all, that is a trade worth making.
 *
 * <p>Every way this can fail to exist is survivable, exactly as the sun's map
 * is: no cube texture, no framebuffer, a driver that will not compile the depth
 * shader, or {@code -Dlarsons.render.gl.shadowmap=0} — the map reports itself
 * unavailable, one uniform switches the term off, and the night draws lit and
 * unshadowed as it did before.
 */
final class GlLampShadow implements AutoCloseable {

    /**
     * {@code -Dlarsons.render.gl.lampshadow=N} — one face's edge in texels, or
     * {@code 0} to turn lamp shadows off and leave the sun's alone.
     *
     * <p>{@code -Dlarsons.render.gl.shadowmap=0} turns this off too: somebody
     * who has said "no shadow passes on this machine" has said it about this
     * one as well, and having to know about two properties to mean one thing is
     * how an escape hatch stops being one.
     */
    static final String SIZE_PROPERTY = "larsons.render.gl.lampshadow";

    /**
     * Texels on a face's side, by default.
     *
     * <p>Six of these is a million and a half depth texels, which is more than
     * the sun's whole map — affordable only because it is drawn on the frame
     * you sit down at the fire and then reused for as long as you are there.
     * What it buys is angular resolution: ninety degrees over five hundred
     * texels is a fifth of a degree, so a branch three metres from the flame
     * has an edge about a centimetre wide, which is where a shadow this close
     * to its caster needs to be.
     */
    private static final int DEFAULT_FACE = 512;

    /** The smallest face worth the pass at all. */
    private static final int MIN_FACE = 128;

    /**
     * The near plane, in metres — <b>and it wants to be small but not tiny.</b>
     *
     * <p>A lamp sits inside its own lantern and a few centimetres above the
     * ground, so anything much larger clips away the very geometry whose shadow
     * is longest. But the depth in a perspective map is a reciprocal, so
     * halving this halves the precision everywhere else; twelve centimetres is
     * inside the glass and still leaves the far end of a twelve-metre reach
     * with depth quanta well under a millimetre.
     */
    private static final double NEAR = 0.12;

    /**
     * How far outside its own radius a lamp may be and still get the map.
     *
     * <p>A shadow is a near-field cue and a lamp's is nearer than most: a fire
     * forty metres off is a dot, and the stripe its trees cast is a couple of
     * pixels wide. Three radii is well past where it stops being worth a pass
     * and near enough that walking toward a camp lights its shadows before you
     * can make them out.
     */
    private static final double REACH_MULTIPLE = 3;

    /**
     * How much better a rival lamp must score before it takes the map.
     *
     * <p><b>Sixty percent, because a fire flickers.</b> {@code LightField}
     * wobbles a flame's intensity by up to a fifth several times a second, and
     * the ranking is a function of that and of where the camera is standing —
     * so two lanterns either side of a table, or a fire and the lantern in your
     * hand, would otherwise trade the map back and forth every few frames, and
     * every trade is a full redraw of six faces. A margin comfortably wider
     * than the flicker makes the choice stable, and when it is wrong it is
     * wrong between two lamps that were nearly equal to begin with.
     *
     * <p>It also settles the case the request was about: sit down at a
     * campfire holding a lantern and the fire keeps the map, because the
     * lantern has to be more than half again as prominent to take it and a fire
     * is the brightest thing a party can make.
     */
    private static final double STICKY = 1.6;

    /** How far the chosen lamp may drift before its map has to be redrawn. */
    private static final double MOVE_TOLERANCE = 0.02;

    /**
     * …and how far its <em>reach</em> may drift, as a fraction.
     *
     * <p>A separate tolerance, and a much looser one, because a flame's radius
     * is not a measurement — it is {@code LightField}'s flicker, which moves it
     * by six percent on every single frame. Compared exactly, a campfire would
     * never once reuse its own map. Compared to a fifth, it reuses it for as
     * long as it is burning, and the far plane is set that much beyond the
     * radius so the map still covers the widest the flame ever gets.
     */
    private static final double REACH_TOLERANCE = 0.20;

    /** Depth-buffer slope offset, in the driver's own units. */
    private static final float POLYGON_OFFSET_SLOPE = 2.4f;

    private static final float POLYGON_OFFSET_UNITS = 4.0f;

    /**
     * The shader's bias, in metres, flat and slope-scaled.
     *
     * <p>In metres and not in buffer units, unlike the sun's, and that is the
     * one place a perspective shadow map is <em>easier</em> to reason about
     * than an orthographic one: the shader has to undo the reciprocal anyway to
     * get a comparable depth, so the natural place to apply the bias is before
     * it does — on the distance itself, where a centimetre is a centimetre
     * whether the fragment is under the flame or across the clearing.
     */
    private static final double FLAT_BIAS = 0.04;

    private static final double SLOPE_BIAS = 0.16;

    private final int face;

    private int fbo = -1;
    private int cube = -1;

    /** The shader — the same class the sun's map draws with; see there. */
    private final GlDepthProgram depth = new GlDepthProgram();

    private boolean cutouts;
    private boolean unavailable;
    private boolean built;

    /** Which of the frame's lamps has the map this frame, or {@code −1}. */
    private int lamp = -1;

    /** …and where it is, as {@link #aim} found it. */
    private double pendingX, pendingY, pendingZ, pendingRadius;

    /** Where it was, and how far it reached, when the map was drawn. */
    private double drawnX = Double.NaN, drawnY, drawnZ, drawnReach;

    /**
     * …and the far plane, which is that reach plus {@link #REACH_TOLERANCE}.
     *
     * <p>Deliberately wider than the lamp, so that a flame flickering up to its
     * widest is still inside a map drawn when it was at its narrowest. Nothing
     * is lost by the slack: depth beyond the reach is depth nothing is lit by.
     */
    private double drawnFar;
    private long drawnCasters = Long.MIN_VALUE;

    /**
     * The six faces' projections, column-major — <b>a function of the reach and
     * nothing else</b>, so they are rebuilt only when a different lamp with a
     * different radius takes the map. Where the lamp <em>is</em> comes in
     * through the per-mesh translation in {@link #matrixInto}.
     */
    private final float[][] faceColumns = new float[6][];
    private double faceFar = Double.NaN;

    /** Saved across the pass, because this borrows somebody else's context. */
    private int callersFbo;
    private final int[] callersViewport = new int[4];

    GlLampShadow() {
        this.face = requestedFace();
        if (face <= 0) unavailable = true;
    }

    private static int requestedFace() {
        if ("0".equals(trimmed(GlShadowMap.SIZE_PROPERTY))) return 0;
        String asked = trimmed(SIZE_PROPERTY);
        if (asked == null) return DEFAULT_FACE;
        try {
            int n = Integer.parseInt(asked);
            return n <= 0 ? 0 : Math.max(MIN_FACE, n);
        } catch (NumberFormatException e) {
            return DEFAULT_FACE;
        }
    }

    private static String trimmed(String property) {
        String value = System.getProperty(property);
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** Which of the frame's lamps has the map, or {@code −1} for none. */
    int lamp() {
        return lamp;
    }

    int texture() {
        return cube;
    }

    /**
     * Pick the lamp whose shadow is worth a pass, and work out where it is.
     * Touches no GL state beyond building the map the first time.
     *
     * <p>Separate from {@link #openPass} for {@link GlShadowMap#aim}'s reason:
     * the caller has to know which lamp this is, and whether its map is already
     * the one on the card, before it decides to spend a pass.
     *
     * @param strength what the caller will actually shade with; {@code 0} in
     *                 daylight, and then there is nothing to draw
     * @return the index of the chosen lamp, or {@code −1} for none
     */
    int aim(List<MeshPass.Light> lights, EyeCamera eye, double strength,
            boolean atlasHasCutouts) {
        lamp = -1;
        if (unavailable || lights == null || lights.isEmpty() || eye == null) return -1;
        if (strength <= 0) return -1;

        int best = -1;
        double bestScore = 0;
        for (int i = 0; i < lights.size(); i++) {
            MeshPass.Light light = lights.get(i);
            double radius = light.radius();
            if (radius <= 0 || light.intensity() <= 0) continue;
            double dx = light.x() - eye.x(), dy = light.y() - eye.y();
            double dz = light.z() - eye.z();
            double reach = radius * REACH_MULTIPLE;
            if (dx * dx + dy * dy + dz * dz > reach * reach) continue;
            double score = LightCull.airScore(light, eye.x(), eye.y(), eye.z());
            // The lamp that already has the map keeps it through a near tie.
            if (isDrawnLamp(light)) score *= STICKY;
            if (score > bestScore) {
                bestScore = score;
                best = i;
            }
        }
        if (best < 0) return -1;
        if (!build()) return -1;

        cutouts = atlasHasCutouts;
        lamp = best;
        MeshPass.Light chosen = lights.get(best);
        pendingX = chosen.x();
        pendingY = chosen.y();
        pendingZ = chosen.z();
        pendingRadius = chosen.radius();
        return best;
    }

    private boolean isDrawnLamp(MeshPass.Light light) {
        return Math.abs(light.x() - drawnX) <= MOVE_TOLERANCE
                && Math.abs(light.y() - drawnY) <= MOVE_TOLERANCE
                && Math.abs(light.z() - drawnZ) <= MOVE_TOLERANCE
                && reaches(light.radius());
    }

    /** Whether the lamp the map was drawn from is not the one it wants now. */
    boolean lampMoved() {
        return Double.isNaN(drawnX) || !reaches(pendingRadius)
                || Math.abs(pendingX - drawnX) > MOVE_TOLERANCE
                || Math.abs(pendingY - drawnY) > MOVE_TOLERANCE
                || Math.abs(pendingZ - drawnZ) > MOVE_TOLERANCE;
    }

    /** Whether the map on the card still covers a lamp of this reach. */
    private boolean reaches(double radius) {
        return Math.abs(radius - drawnReach) <= REACH_TOLERANCE * drawnReach;
    }

    /**
     * Adopt this frame's lamp as the one both passes will use.
     *
     * <p>Before the casters are chosen, for {@link GlShadowMap#adoptBox}'s
     * reason: {@link #casts} answers "is this mesh inside the lamp", and
     * choosing against the old lamp while drawing through the new one loses
     * whatever the lamp has just moved onto.
     */
    void adoptLamp() {
        drawnX = pendingX;
        drawnY = pendingY;
        drawnZ = pendingZ;
        drawnReach = pendingRadius;
        drawnFar = pendingRadius * (1 + REACH_TOLERANCE);
        if (drawnFar != faceFar) {
            for (int f = 0; f < 6; f++) {
                faceColumns[f] = Mat4.cubeFace(f, NEAR, drawnFar).columnMajor();
            }
            faceFar = drawnFar;
        }
    }

    /** Whether what is standing round the lamp differs from what was drawn. */
    boolean castersChanged(long casters) {
        return drawnCasters != casters;
    }

    /** Whether a mesh's bounding ball reaches inside the lamp at all. */
    boolean casts(double centreX, double centreY, double centreZ, double radius) {
        double dx = centreX - drawnX, dy = centreY - drawnY, dz = centreZ - drawnZ;
        double reach = drawnFar + radius;
        return dx * dx + dy * dy + dz * dz <= reach * reach;
    }

    /**
     * …and whether it reaches into <em>this</em> face of the cube.
     *
     * <p>A ninety-degree frustum whose apex is the lamp has four side planes
     * and every one of them passes through that apex, so there is no offset to
     * carry: a ball is outside the face if it is more than its own radius
     * behind any of the four. The normals are {@code (forward ± right)} and
     * {@code (forward ± up)} over root two, which is where the constant comes
     * from — and taking the four in one loop with the radius scaled instead
     * saves normalising anything.
     *
     * <p><b>Worth it because six is the multiplier.</b> A chunk beside a fire
     * is in one face, sometimes two; without this it would be drawn into all
     * six and five of those would clip it away.
     */
    boolean faceCasts(int which, double centreX, double centreY, double centreZ,
                      double radius) {
        double cx = centreX - drawnX, cy = centreY - drawnY, cz = centreZ - drawnZ;
        double[] basis = FACE_AXES[which];
        double margin = radius * ROOT_TWO;
        for (int plane = 0; plane < 4; plane++) {
            // forward ± right, forward ± up — the four side planes, unnormalised,
            // which is why the radius is scaled by root two to match.
            double sign = (plane & 1) == 0 ? 1 : -1;
            int axis = plane < 2 ? 0 : 3;
            double nx = basis[6] + sign * basis[axis];
            double ny = basis[7] + sign * basis[axis + 1];
            double nz = basis[8] + sign * basis[axis + 2];
            if (cx * nx + cy * ny + cz * nz < -margin) return false;
        }
        return true;
    }

    private static final double ROOT_TWO = Math.sqrt(2);

    /**
     * The same six bases {@link Mat4#cubeFace} projects through, for the
     * culling above.
     *
     * <p>Duplicated rather than exported, and the duplication is checked:
     * {@code GlLightingTest} pushes a point through both and asserts that
     * anything this says is in a face lands inside that face's projection. The
     * alternative — a public accessor on {@code Mat4} handing out nine doubles
     * — would put a cube map's internals in the seam every backend shares.
     */
    private static final double[][] FACE_AXES = {
            {0, 0, -1,   0, -1, 0,    1, 0, 0},
            {0, 0, 1,    0, -1, 0,   -1, 0, 0},
            {1, 0, 0,    0, 0, 1,     0, 1, 0},
            {1, 0, 0,    0, 0, -1,    0, -1, 0},
            {1, 0, 0,    0, -1, 0,    0, 0, 1},
            {-1, 0, 0,   0, -1, 0,    0, 0, -1},
    };

    /**
     * The matrix a mesh at this origin casts into one face through.
     *
     * <p>The face's own projection with a translation on the right, which
     * leaves the first three columns alone — twelve multiply-adds and no
     * allocation, on a path that runs once per mesh per face.
     */
    void matrixInto(int which, double originX, double originY, double originZ,
                    float[] out) {
        float[] columns = faceColumns[which];
        if (columns == null) return;
        float tx = (float) (originX - drawnX);
        float ty = (float) (originY - drawnY);
        float tz = (float) (originZ - drawnZ);
        System.arraycopy(columns, 0, out, 0, 12);
        for (int row = 0; row < 4; row++) {
            out[12 + row] = columns[row] * tx + columns[4 + row] * ty
                    + columns[8 + row] * tz + columns[12 + row];
        }
    }

    /** Bind the cube and set the state all six faces are drawn with. */
    void openPass(long casters) {
        drawnCasters = casters;

        callersFbo = glGetInteger(GL_FRAMEBUFFER_BINDING);
        glGetIntegerv(GL_VIEWPORT, callersViewport);

        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glViewport(0, 0, face, face);
        glEnable(GL_DEPTH_TEST);
        glDepthFunc(GL_LEQUAL);
        glDepthMask(true);
        glDisable(GL_BLEND);
        // No cull, for two reasons that happen to agree. Half of what casts
        // here is a single-sided sheet, as in the sun's map — and a cube map's
        // face basis is left-handed besides, so every triangle in here reaches
        // the raster wound backwards from the way the rest of the engine winds
        // them. See Mat4's own note on that basis.
        glDisable(GL_CULL_FACE);
        glEnable(GL_POLYGON_OFFSET_FILL);
        glPolygonOffset(POLYGON_OFFSET_SLOPE, POLYGON_OFFSET_UNITS);

        depth.use(cutouts);
    }

    /** Point the framebuffer at one face and clear it. */
    void openFace(int which) {
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT,
                GL_TEXTURE_CUBE_MAP_POSITIVE_X + which, cube, 0);
        glClear(GL_DEPTH_BUFFER_BIT);
    }

    /** One mesh into whichever face is open. */
    void cast(int vao, int vertexCount, float[] lightMvp) {
        depth.cast(vao, vertexCount, lightMvp);
    }

    /** Close the pass and put the caller's framebuffer and viewport back. */
    void end() {
        glDisable(GL_POLYGON_OFFSET_FILL);
        glPolygonOffset(0, 0);
        glBindFramebuffer(GL_FRAMEBUFFER, callersFbo);
        glViewport(callersViewport[0], callersViewport[1],
                callersViewport[2], callersViewport[3]);
    }

    // --- what the shader needs to undo the projection --------------------------------

    /**
     * The two constants that turn a distance into the depth stored in this map.
     *
     * <p>A perspective projection writes {@code A + B / distance}, and the
     * fragment shader has to arrive at the same number from the direction it
     * already has rather than by rasterising anything. Handed over as numbers
     * rather than recomputed there so that the projection and its inverse
     * cannot drift apart — which they would, silently, as a shadow that is
     * biased by an amount that grows with distance.
     *
     * @return {@code {A, B, flat bias, near}}, the biases in metres
     */
    void depthCurveInto(float[] out) {
        double far = Math.max(drawnFar, NEAR * 2);
        double range = far - NEAR;
        out[0] = (float) ((far + NEAR) / range);
        out[1] = (float) (-2 * far * NEAR / range);
        out[2] = (float) FLAT_BIAS;
        out[3] = (float) NEAR;
    }

    /** …and the slope-scaled half of the bias, in metres. */
    float slopeBias() {
        return (float) SLOPE_BIAS;
    }

    /**
     * Make the cube, the framebuffer and the shader, once.
     *
     * <p>Checked rather than assumed and any refusal is final, for
     * {@link GlShadowMap#build}'s reason: a driver that will not give a
     * depth-only cube this frame will not give one next frame either.
     */
    private boolean build() {
        if (built) return true;
        if (unavailable) return false;
        try {
            cube = glGenTextures();
            int previousTexture = glGetInteger(GL_TEXTURE_BINDING_CUBE_MAP);
            glBindTexture(GL_TEXTURE_CUBE_MAP, cube);
            for (int f = 0; f < 6; f++) {
                glTexImage2D(GL_TEXTURE_CUBE_MAP_POSITIVE_X + f, 0, GL_DEPTH_COMPONENT24,
                        face, face, 0, GL_DEPTH_COMPONENT, GL_FLOAT,
                        (java.nio.ByteBuffer) null);
            }
            // Compared by the texture unit, exactly as the sun's map is: with a
            // comparison mode set, a fetch through a `samplerCubeShadow` returns
            // the fraction of its neighbouring texels that pass rather than a
            // depth, so GL_LINEAR here is percentage-closer filtering in fixed
            // function for the price of one fetch. Filtering depth and *then*
            // comparing averages distances, which is a wrong answer along every
            // silhouette rather than a soft one.
            glTexParameteri(GL_TEXTURE_CUBE_MAP, GL_TEXTURE_COMPARE_MODE,
                    GL_COMPARE_REF_TO_TEXTURE);
            glTexParameteri(GL_TEXTURE_CUBE_MAP, GL_TEXTURE_COMPARE_FUNC, GL_LEQUAL);
            glTexParameteri(GL_TEXTURE_CUBE_MAP, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_CUBE_MAP, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            // Clamped on all three axes. A direction cannot leave a cube, so
            // this only matters at the seams — where a filter kernel that
            // wrapped round to the opposite face would put a stripe of the wrong
            // shadow down four edges of the world.
            glTexParameteri(GL_TEXTURE_CUBE_MAP, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_CUBE_MAP, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_CUBE_MAP, GL_TEXTURE_WRAP_R, GL_CLAMP_TO_EDGE);
            glBindTexture(GL_TEXTURE_CUBE_MAP, previousTexture);

            int previousFbo = glGetInteger(GL_FRAMEBUFFER_BINDING);
            fbo = glGenFramebuffers();
            glBindFramebuffer(GL_FRAMEBUFFER, fbo);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT,
                    GL_TEXTURE_CUBE_MAP_POSITIVE_X, cube, 0);
            glDrawBuffer(GL_NONE);
            glReadBuffer(GL_NONE);
            int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
            glBindFramebuffer(GL_FRAMEBUFFER, previousFbo);
            if (status != GL_FRAMEBUFFER_COMPLETE) {
                throw new IllegalStateException("lamp depth framebuffer incomplete: 0x"
                        + Integer.toHexString(status));
            }

            depth.build();
            built = true;
            return true;
        } catch (RuntimeException e) {
            System.err.println("[gl] no lamp shadows on this driver, "
                    + "a fire will light the wood but not shadow it: " + e.getMessage());
            unavailable = true;
            release();
            return false;
        }
    }

    private void release() {
        depth.close();
        if (fbo >= 0) {
            glDeleteFramebuffers(fbo);
            fbo = -1;
        }
        if (cube >= 0) {
            glDeleteTextures(cube);
            cube = -1;
        }
        built = false;
        lamp = -1;
        drawnX = Double.NaN;
        drawnReach = 0;
        drawnCasters = Long.MIN_VALUE;
        faceFar = Double.NaN;
    }

    @Override
    public void close() {
        release();
    }
}
