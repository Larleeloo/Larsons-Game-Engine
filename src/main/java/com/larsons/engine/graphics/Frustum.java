package com.larsons.engine.graphics;

/**
 * The six planes of what a camera can see, pulled straight out of its
 * view-projection matrix.
 *
 * <p><b>Out of the matrix rather than out of the camera's angles</b>, which is
 * the Gribb&ndash;Hartmann extraction every renderer uses and is not merely a
 * tidier way to get the same numbers. The GPU clips against the planes implied
 * by the matrix it was handed; a cull built from the camera's yaw and pitch is
 * a <em>second</em> derivation of those planes, and any disagreement between
 * the two shows up as a chunk that vanishes at the edge of the screen. Taking
 * both from one matrix makes the disagreement impossible rather than unlikely.
 *
 * <p>Six planes rather than {@link EyeCamera}'s four: the near and far planes
 * matter here because a section of world is a box with a top and a bottom and
 * the render distance is a real edge, where the Java2D sweep bounded distance
 * separately and only ever needed the sides.
 *
 * <p>Planes are stored as {@code (a, b, c, d)} with {@code a·x + b·y + c·z + d}
 * positive inside, normalised so that value is a distance — which is what lets
 * a caller ask how far outside something is rather than only whether it is.
 */
public final class Frustum {

    /** Left, right, bottom, top, near, far. */
    private static final int PLANES = 6;

    private final double[] planes = new double[PLANES * 4];

    private Frustum() {}

    /**
     * The frustum of a view-projection matrix.
     *
     * <p>Each plane is a sum or difference of two rows of the matrix, which is
     * the whole trick: a point is inside the left plane exactly when
     * {@code clip.x > -clip.w}, and {@code clip.x + clip.w} is a linear
     * function of the world point whose coefficients are row&nbsp;0 plus
     * row&nbsp;3.
     */
    public static Frustum of(Mat4 viewProjection) {
        Frustum f = new Frustum();
        double[] r = new double[4 * 4];
        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < 4; col++) r[row * 4 + col] = viewProjection.at(row, col);
        }
        f.set(0, r, 3, 0, 1);    // left:   row3 + row0
        f.set(1, r, 3, 0, -1);   // right:  row3 − row0
        f.set(2, r, 3, 1, 1);    // bottom: row3 + row1
        f.set(3, r, 3, 1, -1);   // top:    row3 − row1
        f.set(4, r, 3, 2, 1);    // near:   row3 + row2
        f.set(5, r, 3, 2, -1);   // far:    row3 − row2
        return f;
    }

    private void set(int plane, double[] r, int base, int other, double sign) {
        double a = r[base * 4] + sign * r[other * 4];
        double b = r[base * 4 + 1] + sign * r[other * 4 + 1];
        double c = r[base * 4 + 2] + sign * r[other * 4 + 2];
        double d = r[base * 4 + 3] + sign * r[other * 4 + 3];
        double length = Math.sqrt(a * a + b * b + c * c);
        if (length > 1e-12) {
            a /= length;
            b /= length;
            c /= length;
            d /= length;
        }
        planes[plane * 4] = a;
        planes[plane * 4 + 1] = b;
        planes[plane * 4 + 2] = c;
        planes[plane * 4 + 3] = d;
    }

    /**
     * Whether any of an axis-aligned box is inside.
     *
     * <p>The standard positive-vertex test: for each plane, take the corner of
     * the box furthest along that plane's normal, and if <em>that</em> corner
     * is behind the plane the whole box is. Eight corners are never tested,
     * which is what makes this cheap enough to ask of every section of the
     * world every frame.
     *
     * <p>It can say yes to a box that is outside — one straddling the corner of
     * two planes without touching the frustum — and that is the direction a
     * cull is allowed to be wrong in: an extra section drawn costs a draw call,
     * and a section wrongly dropped is a hole in the world.
     */
    public boolean boxVisible(double minX, double minY, double minZ,
                              double maxX, double maxY, double maxZ) {
        for (int p = 0; p < PLANES; p++) {
            double a = planes[p * 4], b = planes[p * 4 + 1], c = planes[p * 4 + 2];
            double d = planes[p * 4 + 3];
            double x = a >= 0 ? maxX : minX;
            double y = b >= 0 ? maxY : minY;
            double z = c >= 0 ? maxZ : minZ;
            if (a * x + b * y + c * z + d < 0) return false;
        }
        return true;
    }

    /** One plane, as {@code {a, b, c, d}} — for tests. */
    public double[] plane(int index) {
        double[] out = new double[4];
        System.arraycopy(planes, index * 4, out, 0, 4);
        return out;
    }
}
