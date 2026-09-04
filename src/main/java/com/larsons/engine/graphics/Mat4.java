package com.larsons.engine.graphics;

/**
 * A 4&times;4 matrix, in the layout OpenGL wants it: sixteen floats, column
 * major, so {@code m[column * 4 + row]}.
 *
 * <p><b>Why the engine grew one now.</b> Every projection in this engine until
 * now was arithmetic done on the CPU one vertex at a time
 * ({@link EyeCamera#screenX}), because the thing at the far end was a
 * {@code fillPolygon} and there was nowhere to put a matrix. A GPU terrain pass
 * is the opposite arrangement: the vertices go up once and the <em>matrix</em>
 * changes per frame, so the camera has to be expressible as one.
 *
 * <p><b>It has to agree with {@link EyeCamera} exactly.</b> Both backends draw
 * the same world through the same camera and a player switching between them
 * must not see the picture move. So the projection here is derived from that
 * class's own numbers rather than from the usual textbook
 * {@code gluPerspective}: its focal length is
 * {@code (height / 2) / tan(fov / 2)} and its eye space has <b>+Z pointing
 * forward</b>, where OpenGL's convention is &minus;Z. Both differences are
 * handled here, once, instead of being a sign error waiting in a shader.
 *
 * <p>Immutable, and every operation returns a new one. A frame builds two of
 * these; the allocation is not the thing worth optimising.
 */
public final class Mat4 {

    /**
     * <b>Geometry facing the eye comes out of {@link #perspective} wound
     * clockwise, and a back-face cull has to be told so.</b>
     *
     * <p>Wind a face counter-clockwise seen from outside — which is what every
     * mesher in this engine does, and what the textbook calls the front face —
     * and push it through the usual OpenGL projection, where the eye looks down
     * &minus;Z: it lands on the screen counter-clockwise, which is why
     * {@code GL_CCW} is the driver's default. This projection looks down
     * <b>+Z</b> instead, because {@link EyeCamera} does, and that one sign flips
     * the answer. A face whose outward normal points back at the eye has a
     * negative eye-space z-component of its normal here, and the perspective
     * divide carries that straight through to the window: the same triangle,
     * unchanged, comes out <em>clockwise</em>.
     *
     * <p>So a backend culling back faces against this matrix must set
     * {@code glFrontFace(GL_CW)}. Setting {@code GL_CCW} does not draw the world
     * mirrored or upside down — the image is right either way — it culls exactly
     * the faces that were pointing at you and keeps the ones that were pointing
     * away, so a flat plain shows nothing at all and a lone block shows the two
     * or three faces on its far side. That is a hard failure to read backwards
     * from, which is why the convention is stated here as a value, next to the
     * matrix that causes it, and pinned by {@code GpuTerrainTest}.
     */
    public static final boolean FRONT_FACES_WIND_CLOCKWISE = true;

    private final float[] m;

    private Mat4(float[] m) {
        this.m = m;
    }

    /** The matrix as OpenGL wants it — column major, sixteen floats. */
    public float[] columnMajor() {
        return m.clone();
    }

    /** One element, by row and column. */
    public double at(int row, int col) {
        return m[col * 4 + row];
    }

    /**
     * Wrap sixteen floats already in OpenGL's order. For a caller building a
     * translation or reading one back from a driver — every other way in goes
     * through the named constructors, which is where the conventions live.
     */
    public static Mat4 ofColumnMajor(float[] columnMajor) {
        if (columnMajor.length != 16) {
            throw new IllegalArgumentException("a 4x4 matrix is sixteen floats");
        }
        return new Mat4(columnMajor.clone());
    }

    /** A pure translation. */
    public static Mat4 translation(double x, double y, double z) {
        float[] out = new float[16];
        out[0] = out[5] = out[10] = out[15] = 1;
        out[12] = (float) x;
        out[13] = (float) y;
        out[14] = (float) z;
        return new Mat4(out);
    }

    /** The identity. */
    public static Mat4 identity() {
        float[] out = new float[16];
        out[0] = out[5] = out[10] = out[15] = 1;
        return new Mat4(out);
    }

    /**
     * The perspective divide, as {@link EyeCamera} performs it.
     *
     * <p>Given eye-space {@code (right, up, forward)} with forward positive,
     * this maps to clip space so that the result lands on exactly the pixel
     * {@code EyeCamera.screenX}/{@code screenY} would have put it on. The
     * horizontal scale is measured against the viewport <em>height</em> and
     * then divided by the aspect ratio, because that is where that class's
     * focal length comes from — a wider window shows more of the world rather
     * than the same world stretched.
     *
     * @param fov  vertical field of view, radians
     * @param near the near plane; anything closer cannot be projected
     * @param far  the far plane, which is the render distance plus a margin
     */
    public static Mat4 perspective(double fov, double aspect, double near, double far) {
        double tan = Math.tan(fov / 2);
        double fy = 1.0 / tan;
        double fx = fy / Math.max(1e-6, aspect);
        double range = far - near;
        float[] out = new float[16];
        out[0] = (float) fx;            // column 0, row 0
        out[5] = (float) fy;            // column 1, row 1
        out[10] = (float) ((far + near) / range);
        out[14] = (float) (-2 * far * near / range);
        // +Z forward rather than OpenGL's −Z: w takes z straight, unnegated.
        out[11] = 1;
        return new Mat4(out);
    }

    /**
     * The view matrix of an {@link EyeCamera}: its three axes as rows, and the
     * eye moved to the origin.
     *
     * <p>The axes are that class's own — right, up and forward as
     * {@code EyeCamera.toEye} uses them — so this is a rewriting of that method
     * rather than a second opinion about where the camera is looking.
     */
    public static Mat4 view(EyeCamera eye) {
        return view(eye, true);
    }

    /**
     * The same camera's axes with the eye left at the origin —
     * <b>for a renderer whose model matrices are already relative to it.</b>
     *
     * <p>A world with no edge cannot be expressed in {@code float} at
     * centimetre precision, so a renderer of one hands the card coordinates
     * measured from the camera rather than from the world origin: the model
     * matrix carries {@code (origin − eye)} in {@code double}, and everything
     * downstream stays small. That trick and {@link #view(EyeCamera)} do the
     * same subtraction, and a pass that uses both does it <b>twice</b> — which
     * is a camera that appears to hover at its own altitude above where it
     * really is, growing worse the higher the ground gets. It shipped, and on
     * ground twenty-eight metres up it drew the world two thousand pixels
     * below where the crosshair was pointing.
     *
     * <p>So: absolute model matrices take {@link #view(EyeCamera)},
     * eye-relative ones take this.
     */
    public static Mat4 viewRotation(EyeCamera eye) {
        return view(eye, false);
    }

    /**
     * The model-view of a mesh whose vertices are stored relative to
     * {@code (originX, originY, originZ)} — the whole eye-relative chain in one
     * call, so that no caller has to remember which of the two view matrices
     * goes with it.
     *
     * <p>It lives here rather than in the GL backend because that is the only
     * way it can be checked: a headless test can multiply matrices and compare
     * the result against {@link EyeCamera#project}, and cannot open a GL
     * context. The bug this exists to prevent — pairing an eye-relative model
     * with the eye-relative {@link #view(EyeCamera)} and subtracting the camera
     * twice — produced a picture that was perfectly self-consistent and drawn
     * from the wrong place, which no screenshot and no test of the backend
     * alone would have called wrong.
     */
    public static Mat4 eyeRelativeModelView(EyeCamera eye,
                                            double originX, double originY, double originZ) {
        return viewRotation(eye).times(translation(
                originX - eye.x(), originY - eye.y(), originZ - eye.z()));
    }

    /**
     * A parallel projection, in the same conventions as {@link #perspective}:
     * <b>+Z forward</b>, and a box rather than a frustum.
     *
     * <p>What a shadow map is drawn through. The sun is far enough away that
     * its rays are parallel, so the depth buffer taken from it has no
     * perspective in it at all — and a projection with no divide is also the
     * one whose depth is linear, which is what makes a bias in metres mean the
     * same thing at both ends of the box.
     *
     * @param halfWidth  half the box, across
     * @param halfHeight half the box, up
     * @param near       the near face, in front of the light
     * @param far        the far face; everything between the two casts
     */
    public static Mat4 orthographic(double halfWidth, double halfHeight,
                                    double near, double far) {
        double range = far - near;
        float[] out = new float[16];
        out[0] = (float) (1 / Math.max(1e-9, halfWidth));
        out[5] = (float) (1 / Math.max(1e-9, halfHeight));
        out[10] = (float) (2 / range);
        out[14] = (float) (-(far + near) / range);
        out[15] = 1;
        return new Mat4(out);
    }

    /**
     * The rotation half of a view matrix aimed along a direction — the eye
     * still at the origin, +Z pointing where {@code (dx, dy, dz)} points.
     *
     * <p>Right-handed and wound the same way {@link #view(EyeCamera)} is:
     * {@code right × up = forward}. A caller composing this with a translation
     * therefore gets a camera the rest of the engine's conventions apply to,
     * back-face winding included.
     *
     * <p>The reference "up" is the world's, except where the direction is
     * within a few degrees of vertical — a sun directly overhead — where it
     * swings to +Y rather than producing a degenerate basis. Which one it picks
     * does not matter: rolling a parallel projection about its own axis draws
     * the same shadows.
     */
    public static Mat4 lookAlong(double dx, double dy, double dz) {
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length < 1e-12) return identity();
        double fx = dx / length, fy = dy / length, fz = dz / length;
        double ux = 0, uy = 0, uz = 1;
        if (Math.abs(fz) > 0.995) {
            uy = 1;
            uz = 0;
        }
        // right = up × forward, then a true up from the pair, so the three are
        // orthonormal even where the reference was not perpendicular.
        double rx = uy * fz - uz * fy;
        double ry = uz * fx - ux * fz;
        double rz = ux * fy - uy * fx;
        double rl = Math.sqrt(rx * rx + ry * ry + rz * rz);
        rx /= rl; ry /= rl; rz /= rl;
        ux = fy * rz - fz * ry;
        uy = fz * rx - fx * rz;
        uz = fx * ry - fy * rx;
        float[] out = new float[16];
        out[0] = (float) rx; out[4] = (float) ry; out[8] = (float) rz;
        out[1] = (float) ux; out[5] = (float) uy; out[9] = (float) uz;
        out[2] = (float) fx; out[6] = (float) fy; out[10] = (float) fz;
        out[15] = 1;
        return new Mat4(out);
    }

    /**
     * The whole matrix a sun casts its shadows through: a box of world, seen
     * from the sun, mapped into clip space.
     *
     * <p><b>Here rather than in the GL backend for {@link #eyeRelativeModelView}'s
     * reason</b> — a headless test can multiply matrices and check that the
     * ground in front of the camera lands inside the box while the ground
     * behind the player does not, and it cannot open a GL context. Every way
     * this can be wrong (the sun's sign, a basis that is left-handed, a depth
     * range that clips the canopy off the top of the map) produces a picture
     * that is merely <em>unshadowed</em> rather than obviously broken, which is
     * the class of thing nobody finds by looking.
     *
     * <p><b>The snapping is not a refinement, it is the whole difference
     * between shadows and a shimmer.</b> A shadow map is a grid, and a grid
     * whose origin slides continuously as the player walks re-decides which
     * texel every leaf edge falls in on every frame — so the edge of every
     * shadow in the wood crawls and sparkles, and it is far more distracting
     * than having no shadows at all. Quantising the box's centre to whole
     * texels means the grid moves in whole texels too: the pattern is the same
     * pattern, one texel over.
     *
     * @param sunX   the direction the sun is <em>in</em>, the way a clock's
     *               {@code sunDirection} gives it; the light travels the other
     *               way, and the sign is handled here rather than by callers
     * <p><b>The snapping is in absolute world light-space, and the frame the
     * result consumes is not.</b> Those two facts have to be separated or the
     * snapping does nothing. A renderer of an unbounded world hands the card
     * positions measured from the camera, and the camera moves by fractions of
     * a texel every frame; quantising a centre that is <em>already</em> measured
     * from it quantises the wrong quantity and the grid slides anyway. So the
     * centre is snapped where it is stationary — against the world — and the
     * camera is subtracted afterwards, in {@code double}, exactly.
     *
     * @param centreX where the box is centred, in <b>world</b> coordinates
     * @param fromX  the origin the geometry this matrix will be applied to is
     *               measured from, also in world coordinates: the camera, for
     *               an eye-relative renderer, and the origin for one whose
     *               vertices are absolute
     * @param radius half the box, across and up: how far shadows reach
     * @param depth  how deep the box is along the sun's own axis; everything
     *               within it can cast, and anything above the top of it casts
     *               nothing
     * @param texels the shadow map's resolution, for the snapping; {@code 0}
     *               or less leaves the box exactly where it was asked for
     */
    public static Mat4 sunlight(double sunX, double sunY, double sunZ,
                                double centreX, double centreY, double centreZ,
                                double fromX, double fromY, double fromZ,
                                double radius, double depth, int texels) {
        Mat4 rotation = lookAlong(-sunX, -sunY, -sunZ);
        double[] centre = new double[4];
        rotation.transform(centreX, centreY, centreZ, centre);
        if (texels > 0) {
            double texel = 2 * radius / texels;
            centre[0] = Math.floor(centre[0] / texel) * texel;
            centre[1] = Math.floor(centre[1] / texel) * texel;
        }
        double[] from = new double[4];
        rotation.transform(fromX, fromY, fromZ, from);
        // Slid back along the sun's axis by half the depth, so the box straddles
        // the centre rather than starting at it: a tree is as likely to be
        // between the sun and the ground as behind it.
        Mat4 recentre = translation(from[0] - centre[0], from[1] - centre[1],
                from[2] - centre[2] + depth / 2);
        return orthographic(radius, radius, 0, depth).times(recentre).times(rotation);
    }

    private static Mat4 view(EyeCamera eye, boolean translate) {
        double yaw = eye.yaw(), pitch = eye.pitch();
        double cy = Math.cos(yaw), sy = Math.sin(yaw);
        double cp = Math.cos(pitch), sp = Math.sin(pitch);
        double rx = cy, ry = sy, rz = 0;
        double ux = -sy * sp, uy = cy * sp, uz = cp;
        double fx = sy * cp, fy = -cy * cp, fz = sp;
        double ex = translate ? eye.x() : 0;
        double ey = translate ? eye.y() : 0;
        double ez = translate ? eye.z() : 0;
        float[] out = new float[16];
        // Column major: out[col * 4 + row].
        out[0] = (float) rx; out[4] = (float) ry; out[8] = (float) rz;
        out[1] = (float) ux; out[5] = (float) uy; out[9] = (float) uz;
        out[2] = (float) fx; out[6] = (float) fy; out[10] = (float) fz;
        out[12] = (float) -(rx * ex + ry * ey + rz * ez);
        out[13] = (float) -(ux * ex + uy * ey + uz * ez);
        out[14] = (float) -(fx * ex + fy * ey + fz * ez);
        out[15] = 1;
        return new Mat4(out);
    }

    /** {@code this × other}, the usual way round: apply {@code other} first. */
    public Mat4 times(Mat4 other) {
        float[] out = new float[16];
        for (int col = 0; col < 4; col++) {
            for (int row = 0; row < 4; row++) {
                float sum = 0;
                for (int k = 0; k < 4; k++) {
                    sum += m[k * 4 + row] * other.m[col * 4 + k];
                }
                out[col * 4 + row] = sum;
            }
        }
        return new Mat4(out);
    }

    /**
     * Project a world point, into {@code out} as {@code {x, y, z, w}} in clip
     * space. For tests and for anything that wants to check the two backends
     * agree about where a vertex lands.
     */
    public void transform(double x, double y, double z, double[] out) {
        for (int row = 0; row < 4; row++) {
            out[row] = m[row] * x + m[4 + row] * y + m[8 + row] * z + m[12 + row];
        }
    }
}
