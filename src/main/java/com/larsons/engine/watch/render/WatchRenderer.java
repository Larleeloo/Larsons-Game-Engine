package com.larsons.engine.watch.render;

import com.larsons.engine.graphics.EyeCamera;
import com.larsons.engine.graphics.MeshPass;
import com.larsons.engine.graphics.TerrainPass;
import com.larsons.engine.graphics.draw.DrawTarget;
import com.larsons.engine.watch.WatchClock;
import com.larsons.engine.watch.world.WatchMaterials;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The world on screen, drawn through {@link DrawTarget} and nothing else.
 *
 * <h2>A painter's algorithm, because the JDK-only build is the one that must
 * work</h2>
 *
 * <p>Java2D has no depth buffer (invariant 4 of this engine says the plain jar
 * runs anywhere with a JRE, and a JRE has no GPU). So every triangle a frame
 * wants is transformed, projected, shaded, and dropped into one flat buffer
 * with its depth; the buffer is sorted far to near; and the whole lot is filled
 * in that order. An occluder drawn after what it occludes covers it, which is
 * the oldest trick in the book and the only one available here.
 *
 * <p><b>It also solves translucency for free</b>, which a depth buffer does
 * not. Water is just another triangle with an alpha in its colour: sorted into
 * the same order as everything else, it blends over what is behind it because
 * what is behind it was already drawn. The GPU path has to keep two lists and
 * sort one of them; this one does not.
 *
 * <h2>No allocation on a frame</h2>
 *
 * <p>Everything is flat primitive arrays that grow and are then reused: the
 * projected corners, the colours, the depths, and a {@code long[]} of
 * {@code (depth, index)} pairs that one {@link Arrays#sort(long[], int, int)}
 * puts in order. A frame with forty thousand triangles in it allocates nothing
 * and sorts once. That is the same arrangement
 * {@link com.larsons.engine.graphics.SolidPainter} arrived at, for the same
 * reason: a per-triangle object would cost more in garbage than in arithmetic.
 *
 * <h2>What is culled, and where</h2>
 *
 * <ol>
 *   <li><b>The mesh</b>, against the frustum, from its own bounding box — one
 *       test throws away a whole chunk that is behind the camera.</li>
 *   <li><b>The triangle</b>, if it is entirely behind the near plane.</li>
 *   <li><b>The triangle</b>, if it faces away: the sign of the projected area.
 *       Roughly two fifths of a terrain mesh, for one multiply each.</li>
 *   <li><b>The triangle</b>, if it lands entirely outside the viewport, which
 *       the rasteriser would otherwise clip for free but the sort would not.</li>
 * </ol>
 */
public final class WatchRenderer {

    /** The most vertices a clipped triangle can have. */
    private static final int MAX_CORNERS = 4;

    /** Depth quantisation for the sort key, in units per metre. */
    private static final int DEPTH_UNITS = 32;

    /** Bits of the sort key given to the triangle's index. */
    private static final int INDEX_BITS = 22;

    private static final long INDEX_MASK = (1L << INDEX_BITS) - 1;

    private int[] cornerX = new int[4096 * MAX_CORNERS];
    private int[] cornerY = new int[4096 * MAX_CORNERS];
    private byte[] corners = new byte[4096];
    private int[] colour = new int[4096];
    private long[] order = new long[4096];
    private int queued;

    private final double[] eyeVerts = new double[9];
    private final double[] scratch = new double[3];
    private final double[] clipped = new double[(3 + 1) * 3];
    private final double[] tint = new double[3];

    /** The backend's mesh pass this frame, or {@code null} for the painter path. */
    private MeshPass meshPass;
    private final List<MeshPass.Draw> gpuDraws = new ArrayList<>();
    private boolean gpu;

    private EyeCamera eye;
    private int viewWidth, viewHeight;
    private int fogRgb;
    private double fogStart = 60, fogEnd = 260;
    private int drawn, culled, submitted;

    /**
     * Start a frame: paint the sky, and empty the queue.
     *
     * @param clock what time it is, which decides the light and the sky
     * @param skyRgb the biome's own daytime sky
     * @param fogRgb the biome's own daytime haze
     */
    public void begin(DrawTarget target, EyeCamera eye, int viewWidth, int viewHeight,
                      WatchClock clock, int skyRgb, int fogRgb) {
        // Ask the target what it can do, every frame. A scene is handed a
        // target and nothing else — it may be a window, a recording or a golden
        // frame — and which of those it is decides whether there is a card
        // behind it. See DrawTarget.terrainPass.
        TerrainPass terrain = target.terrainPass();
        meshPass = terrain == null || !terrain.available() ? null : terrain.meshPass();
        gpu = meshPass != null;
        gpuDraws.clear();

        this.eye = eye;
        this.viewWidth = viewWidth;
        this.viewHeight = viewHeight;
        this.fogRgb = clock.fogColour(fogRgb);
        this.queued = 0;
        this.drawn = 0;
        this.culled = 0;
        this.submitted = 0;
        clock.lightTint(tint);
        sky(target, clock, clock.skyColour(skyRgb));
    }

    /** Whether this frame is being drawn by a graphics card. */
    public boolean acceleratedByGpu() { return gpu; }

    /**
     * What the card spent on the last frame's meshes, in milliseconds; {@code 0}
     * on the painter path or where the driver cannot say.
     */
    public double gpuMillis() { return meshPass == null ? 0 : meshPass.lastFrameMillis(); }

    /** Tell the backend it can release the buffers for these sources. */
    public void discard(List<Long> keys) {
        if (meshPass != null) meshPass.discard(keys);
    }

    /** How far away distance starts and finishes fading into the fog. */
    public void setFogRange(double start, double end) {
        this.fogStart = Math.max(1, start);
        this.fogEnd = Math.max(this.fogStart + 1, end);
    }

    public double fogEnd() { return fogEnd; }

    /** The colour the horizon is, this frame. */
    public int fogColour() { return fogRgb; }

    /** How many triangles the last {@link #flush} actually filled. */
    public int drawnTriangles() { return drawn; }

    /** How many were thrown away before they reached the queue. */
    public int culledTriangles() { return culled; }

    /** How many were offered by {@link #submit}. */
    public int submittedTriangles() { return submitted; }

    /**
     * Queue every triangle in a mesh that survives culling.
     *
     * <p>Does no drawing: nothing can be drawn until every mesh has been seen,
     * because the order is the whole algorithm.
     */
    public void submit(Mesh mesh) {
        submit(mesh, 0);
    }

    /**
     * Queue a mesh, naming what produced it.
     *
     * <p>The {@code key} is only used by the GPU path, where it is the identity
     * a buffer is cached under — a chunk's packed coordinates, an animal's id.
     * A caller with nothing stable to name (a mesh rebuilt every frame) passes
     * {@code 0} and gets the painter's behaviour on both paths.
     */
    public void submit(Mesh mesh, long key) {
        if (mesh == null || mesh.isEmpty() || eye == null) return;
        double ox = mesh.originX(), oy = mesh.originY(), oz = mesh.originZ();
        if (!eye.boxVisible(ox + mesh.minX(), oy + mesh.minY(), oz + mesh.minZ(),
                ox + mesh.maxX(), oy + mesh.maxY(), oz + mesh.maxZ())) {
            culled += mesh.triangleCount();
            return;
        }
        if (gpu) {
            // The card gets the whole mesh and does its own per-triangle work:
            // no projection, no clipping, no sort, no per-triangle shading on
            // this thread at all. That is the entire difference between the two
            // paths, and it is why the GPU one can hold a render distance the
            // painter cannot.
            gpuDraws.add(mesh.toDraw(key));
            submitted += mesh.triangleCount();
            return;
        }
        float[] verts = mesh.vertices();
        int[] colours = mesh.colours();
        int count = mesh.vertexCount();
        submitted += count / 3;
        for (int v = 0; v + 2 < count; v += 3) {
            triangle(verts, colours, v, ox, oy, oz);
        }
    }

    /** Project, cull, shade and queue one triangle. */
    private void triangle(float[] verts, int[] colours, int v,
                          double ox, double oy, double oz) {
        int at = v * Mesh.FLOATS_PER_VERTEX;
        double near = 0;
        for (int i = 0; i < 3; i++) {
            int o = at + i * Mesh.FLOATS_PER_VERTEX;
            eye.toEye(ox + verts[o], oy + verts[o + 1], oz + verts[o + 2], scratch);
            eyeVerts[i * 3] = scratch[0];
            eyeVerts[i * 3 + 1] = scratch[1];
            eyeVerts[i * 3 + 2] = scratch[2];
            near = Math.max(near, scratch[2]);
        }
        if (near <= EyeCamera.NEAR) {
            culled++;
            return;
        }

        int n = EyeCamera.clipNear(eyeVerts, 3, clipped);
        if (n < 3) {
            culled++;
            return;
        }

        ensure(n);
        int base = queued * MAX_CORNERS;
        double depthSum = 0;
        for (int i = 0; i < n; i++) {
            double right = clipped[i * 3], high = clipped[i * 3 + 1], depth = clipped[i * 3 + 2];
            cornerX[base + i] = (int) Math.round(eye.screenX(right, depth));
            cornerY[base + i] = (int) Math.round(eye.screenY(high, depth));
            depthSum += depth;
        }

        // Back-facing? Worth deriving rather than guessing, because guessing it
        // wrong draws the underside of the world and culls the ground you are
        // standing on — which is exactly what the first version of this did.
        //
        // Take the camera at the origin looking south with no pitch, and a
        // ground quad ahead of it. `toEye` gives right = −dx, high = dz,
        // depth = dy; `screenY` then flips high. Walking the quad's own winding
        // (north-west, north-east, south-east — counter-clockwise about a
        // normal that points up) puts those three corners on screen in an order
        // whose shoelace sum comes out <b>positive</b>. So a positive area is a
        // face turned toward the eye, and everything else is the back of one.
        long area = signedArea(base, n);
        if (area <= 0) {
            culled++;
            return;
        }

        if (offScreen(base, n)) {
            culled++;
            return;
        }

        double depth = depthSum / n;
        if (depth > fogEnd) {
            culled++;
            return;
        }

        corners[queued] = (byte) n;
        colour[queued] = fogged(colours[v], depth);
        long key = ((long) Math.min((1L << 40) - 1, (long) (depth * DEPTH_UNITS)) << INDEX_BITS)
                | (queued & INDEX_MASK);
        order[queued] = key;
        queued++;
    }

    private long signedArea(int base, int n) {
        long area = 0;
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            area += (long) cornerX[base + i] * cornerY[base + j]
                    - (long) cornerX[base + j] * cornerY[base + i];
        }
        return area;
    }

    private boolean offScreen(int base, int n) {
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        for (int i = 0; i < n; i++) {
            minX = Math.min(minX, cornerX[base + i]);
            maxX = Math.max(maxX, cornerX[base + i]);
            minY = Math.min(minY, cornerY[base + i]);
            maxY = Math.max(maxY, cornerY[base + i]);
        }
        return maxX < 0 || minX > viewWidth || maxY < 0 || minY > viewHeight;
    }

    /** The vertex colour with this hour's light and this frame's fog applied. */
    private int fogged(int argb, double depth) {
        int a = (argb >>> 24) & 0xFF;
        double r = ((argb >> 16) & 0xFF) * tint[0];
        double g = ((argb >> 8) & 0xFF) * tint[1];
        double b = (argb & 0xFF) * tint[2];
        double haze = depth <= fogStart ? 0
                : Math.min(1, (depth - fogStart) / (fogEnd - fogStart));
        if (haze > 0) {
            // Squared, so the near half of the view is barely touched and the
            // far edge goes all the way — a linear fade greys the middle
            // distance and makes the whole world look like weather.
            haze *= haze;
            r += (((fogRgb >> 16) & 0xFF) - r) * haze;
            g += (((fogRgb >> 8) & 0xFF) - g) * haze;
            b += ((fogRgb & 0xFF) - b) * haze;
        }
        return (a << 24) | (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
    }

    /**
     * Draw everything queued, far to near.
     *
     * <p>One sort of one {@code long[]}: the depth is in the high bits and the
     * triangle's index in the low, so sorting the keys sorts the triangles and
     * nothing is moved but a long. Walked backwards, because the array comes
     * out near-first and the painter needs the opposite.
     */
    public void flush(DrawTarget target) {
        if (gpu) {
            meshPass.setTexture(WatchMaterials.atlas(), WatchMaterials.revision());
            meshPass.draw(gpuDraws, eye, fogRgb, fogStart, fogEnd);
            drawn = submitted;
            gpuDraws.clear();
            return;
        }
        if (queued == 0) return;
        Arrays.sort(order, 0, queued);
        int[] xs = new int[MAX_CORNERS];
        int[] ys = new int[MAX_CORNERS];
        for (int i = queued - 1; i >= 0; i--) {
            int index = (int) (order[i] & INDEX_MASK);
            int n = corners[index];
            int base = index * MAX_CORNERS;
            System.arraycopy(cornerX, base, xs, 0, n);
            System.arraycopy(cornerY, base, ys, 0, n);
            target.fillPolygon(xs, ys, n, colour[index]);
            drawn++;
        }
        queued = 0;
    }

    /**
     * The sky: a vertical gradient from its own colour at the zenith to the
     * fog's at the horizon, and the sun or moon where the clock puts it.
     *
     * <p>Drawn as a gradient rather than a flat fill because the horizon has to
     * meet the fog exactly — the far edge of the ground fades to
     * {@link #fogColour()} and anything else there would leave a visible line
     * where the world ends.
     */
    private void sky(DrawTarget target, WatchClock clock, int zenith) {
        int horizonY = (int) Math.round(eye.horizonY());
        int top = Math.min(horizonY, 0);
        int height = Math.max(1, horizonY - top);
        target.fillLinearGradient(0, top, viewWidth, height,
                0, top, 0xFF000000 | zenith,
                0, top + height, 0xFF000000 | fogRgb);
        if (horizonY < viewHeight) {
            // Below the horizon: the haze the distant ground fades into, so a
            // gap between the last chunk and the edge of the world is the same
            // colour as the fog rather than a hole.
            target.fillRect(0, Math.max(0, horizonY), viewWidth,
                    viewHeight - Math.max(0, horizonY), 0xFF000000 | fogRgb);
        }
        celestialBody(target, clock);
    }

    /** The sun by day and the moon by night, projected onto the sky. */
    private void celestialBody(DrawTarget target, WatchClock clock) {
        double[] direction = new double[3];
        clock.sunDirection(direction);
        boolean night = clock.night();
        if (night) {
            // The moon is opposite the sun, which is why it is up at night.
            direction[0] = -direction[0];
            direction[1] = -direction[1];
            direction[2] = -direction[2];
        }
        if (direction[2] <= -0.05) return;
        double far = 400;
        double[] point = new double[3];
        if (!eye.project(eye.x() + direction[0] * far, eye.y() + direction[1] * far,
                eye.z() + direction[2] * far, point)) {
            return;
        }
        int radius = Math.max(6, viewHeight / 22);
        int argb = night ? 0xFFE8ECF4 : 0xFFFFF3C8;
        int glow = night ? 0x30A8C0E0 : 0x50FFE08A;
        target.fillOval((int) point[0] - radius * 3, (int) point[1] - radius * 3,
                radius * 6, radius * 6, new java.awt.Color(glow, true));
        target.fillOval((int) point[0] - radius, (int) point[1] - radius,
                radius * 2, radius * 2, new java.awt.Color(argb, true));
    }

    private void ensure(int cornersNeeded) {
        if (queued < corners.length) return;
        int wanted = corners.length * 2;
        cornerX = Arrays.copyOf(cornerX, wanted * MAX_CORNERS);
        cornerY = Arrays.copyOf(cornerY, wanted * MAX_CORNERS);
        corners = Arrays.copyOf(corners, wanted);
        colour = Arrays.copyOf(colour, wanted);
        order = Arrays.copyOf(order, wanted);
    }

    private static int clamp(double v) {
        return (int) Math.max(0, Math.min(255, Math.round(v)));
    }
}
