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
        double yaw = eye.yaw(), pitch = eye.pitch();
        double cy = Math.cos(yaw), sy = Math.sin(yaw);
        double cp = Math.cos(pitch), sp = Math.sin(pitch);
        double rx = cy, ry = sy, rz = 0;
        double ux = -sy * sp, uy = cy * sp, uz = cp;
        double fx = sy * cp, fy = -cy * cp, fz = sp;
        double ex = eye.x(), ey = eye.y(), ez = eye.z();
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
