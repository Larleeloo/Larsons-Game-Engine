package com.larsons.engine.watch.render;

import com.larsons.engine.graphics.EyeCamera;
import com.larsons.engine.graphics.MeshPass;
import com.larsons.engine.graphics.TerrainPass;
import com.larsons.engine.graphics.draw.DrawTarget;
import com.larsons.engine.watch.WatchClock;
import com.larsons.engine.watch.light.LightField;
import com.larsons.engine.watch.light.SkyLight;
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
 * <h2>Two lights, one of them new</h2>
 *
 * <p>Every colour in this world is a baked albedo — the face's own shade, from
 * the mesher — multiplied by the hour. That multiplier is
 * {@link WatchClock#lightTint}, and applying it is what makes the same hillside
 * read differently at six in the morning and six at night.
 *
 * <p><b>The card was not applying it.</b> The painter multiplied every vertex
 * by the hour in {@link #fogged} and the GPU path multiplied by nothing at all,
 * because the shader had no uniform to multiply by — so a machine with a driver
 * played the whole game at noon while a machine without one had a night. That
 * is now one call, {@link MeshPass#setLighting}, made from {@link #flush}, and
 * the two paths shade the same world at the same hour.
 *
 * <p>The point lights ride the same seam. They are the one thing here that
 * cannot be baked — a carried lantern moves every frame, and re-meshing a
 * forest because somebody took a step is not a lighting model — so they arrive
 * per frame as {@link MeshPass.Light}s and are applied per fragment on a card
 * and per triangle here. See
 * {@link LightField} for what is burning and what each one does to a surface.
 *
 * <h2>…and a third, which only one backend answers</h2>
 *
 * <p>{@link #setAtmosphere} adds the rest of the environment — where the sun
 * is and what colour, what the sky and the ground bounce back, how thick the
 * air is and where the mist lies. It is a <em>description</em> rather than an
 * instruction, because the two backends have wildly different budgets: the card
 * spends a shadow map and a per-fragment hemisphere on it, and this painter
 * spends nothing at all and keeps the flat multiplier.
 *
 * <p><b>That divergence is deliberate and is the only one.</b> Both paths agree
 * on the hour, on the fog's colour and range, and on every lamp; where they
 * differ is in how richly the same described world is expressed. A player
 * switching backends sees the same time of day and the same camp, drawn once
 * with a sun in it and once without. The alternative — putting a directional
 * term in the painter as well — costs a normal for every triangle in the frame
 * rather than only the ones near a flame, on the thread that is also running
 * the game.
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

    /**
     * The point lights this frame, and which of them can touch the mesh being
     * submitted.
     *
     * <p><b>Culled per mesh, not per triangle</b>, which is what makes the
     * painter path afford this at all. A campfire's light reaches twelve metres
     * and a chunk is thirty-two across, so all but a handful of the meshes in a
     * frame are outside every light in the world — one box test each throws
     * them out, and their forty thousand triangles never ask a lighting
     * question. What is left is the few meshes actually near a flame, and those
     * pay for what they get.
     */
    private final List<MeshPass.Light> lights = new ArrayList<>();

    private final int[] meshLights = new int[MeshPass.MAX_LIGHTS];
    private int meshLightCount;
    private final double[] lit = new double[3];

    private EyeCamera eye;
    private int viewWidth, viewHeight;
    private int fogRgb;
    private double fogStart = 60, fogEnd = 260;
    private int drawn, culled, submitted;

    /**
     * The sun, the air and the grade — <b>the half of the light only a card is
     * asked for.</b>
     *
     * <p>{@link MeshPass.Sky#PLAIN} until somebody says otherwise, and that is
     * the whole of what keeps the painter path and every other caller of this
     * renderer (the portraits, the guide's little turntables) exactly as they
     * were. See {@link #setAtmosphere}.
     */
    private MeshPass.Sky sky = MeshPass.Sky.PLAIN;

    /** Kept from {@link #begin}, because {@link #setAtmosphere} rebuilds from them. */
    private WatchClock clock;
    private int skyRgb;

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
        this.lights.clear();
        this.clock = clock;
        this.skyRgb = clock.skyColour(skyRgb);
        // Plain until told: a caller that draws a world without describing the
        // weather over it gets the picture this renderer drew before any of the
        // sky existed, on both paths.
        this.sky = MeshPass.Sky.PLAIN;
        clock.lightTint(tint);
        sky(target, clock, this.skyRgb);
    }

    /**
     * The sun and the air the world is standing in — <b>the card's half of the
     * light.</b>
     *
     * <p>Called after {@link #begin}, from which it takes the hour and this
     * frame's two colours; what it adds is everything the clock cannot know,
     * which is the weather and where the ground is. The result is one
     * {@link MeshPass.Sky} handed to the backend in {@link #flush}, and a
     * backend that does nothing with it — every backend but the GL one — draws
     * exactly what it drew before.
     *
     * <p>Deliberately <em>not</em> applied to the painter's own arithmetic. The
     * painter shades a triangle at a time on the thread that is also running
     * the game, and a directional term costs it a normal for every triangle in
     * the frame rather than only the ones near a flame; the flat multiplier it
     * already has is the right answer at that budget. The two paths therefore
     * agree on the hour and diverge on how richly they express it, which is the
     * honest trade and is stated here so nobody has to discover it.
     *
     * @see SkyLight
     */
    public void setAtmosphere(double weather, double overcast, boolean submerged,
                              double floorZ, double seconds) {
        if (clock == null) return;
        this.sky = SkyLight.of(clock, skyRgb, fogRgb, weather, overcast, submerged,
                floorZ, seconds);
    }

    /** The sky the last {@link #setAtmosphere} worked out; for tests and the HUD. */
    public MeshPass.Sky atmosphere() { return sky; }

    /**
     * Whether the last frame rebuilt a shadow map or reused the one it had.
     *
     * <p>For the debug readout, and it is the number to watch when this feels
     * slow: the map is a function of where the sun's box is and what is
     * standing in it, and a player who is not walking has moved neither.
     */
    public boolean redrewShadows() {
        return meshPass != null && meshPass.redrewShadowsLastFrame();
    }

    /**
     * The lights this frame is lit by — <b>everything that is burning.</b>
     *
     * <p>Called after {@link #begin} and before anything is submitted, because
     * the painter path shades each triangle as it queues it and cannot be told
     * about a lantern afterwards. On a card it is a uniform block set once for
     * the whole frame; here it is a short array walked per triangle, against
     * lights already culled to the mesh being submitted.
     *
     * <p>Whatever is passed is copied rather than kept: the caller's list is
     * rebuilt every frame by {@link LightField}, and a renderer holding a
     * reference to somebody else's mutable list is a renderer that shades the
     * first half of a frame by one set of lights and the second half by
     * another.
     */
    public void setLights(List<MeshPass.Light> frameLights) {
        lights.clear();
        if (frameLights == null) return;
        for (MeshPass.Light light : frameLights) {
            if (lights.size() >= MeshPass.MAX_LIGHTS) break;
            if (light != null) lights.add(light);
        }
    }

    /** How many lights are burning in this frame; for the debug readout. */
    public int lightCount() { return lights.size(); }

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
        double bias = mesh.sortBias();
        submitted += count / 3;
        cullLights(mesh, ox, oy, oz);
        for (int v = 0; v + 2 < count; v += 3) {
            triangle(verts, colours, v, ox, oy, oz, bias);
        }
    }

    /**
     * Which of the frame's lights can reach this mesh at all.
     *
     * <p>A sphere against the mesh's own bounding box, which is the same test
     * every renderer uses to decide whether a light is worth a fragment's time
     * and is exact enough here for the same reason: a light that fails it
     * contributes nothing anywhere in the mesh, and one that passes may still
     * contribute nothing to most of its triangles — which the per-triangle
     * falloff then finds out cheaply.
     */
    private void cullLights(Mesh mesh, double ox, double oy, double oz) {
        meshLightCount = 0;
        if (lights.isEmpty()) return;
        double minX = ox + mesh.minX(), maxX = ox + mesh.maxX();
        double minY = oy + mesh.minY(), maxY = oy + mesh.maxY();
        double minZ = oz + mesh.minZ(), maxZ = oz + mesh.maxZ();
        for (int i = 0; i < lights.size(); i++) {
            MeshPass.Light light = lights.get(i);
            double dx = light.x() < minX ? minX - light.x()
                    : light.x() > maxX ? light.x() - maxX : 0;
            double dy = light.y() < minY ? minY - light.y()
                    : light.y() > maxY ? light.y() - maxY : 0;
            double dz = light.z() < minZ ? minZ - light.z()
                    : light.z() > maxZ ? light.z() - maxZ : 0;
            double radius = light.radius();
            if (dx * dx + dy * dy + dz * dz < radius * radius) {
                meshLights[meshLightCount++] = i;
            }
        }
    }

    /** Project, cull, shade and queue one triangle. */
    private void triangle(float[] verts, int[] colours, int v,
                          double ox, double oy, double oz, double bias) {
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
        colour[queued] = fogged(colours[v], depth, verts, at, ox, oy, oz);
        // The true depth decides the colour, the fog and the culling above; only
        // the order this is painted in is biased, and only for a mesh that asked
        // to be. See Mesh.sortBias.
        double sortDepth = bias <= 0 ? depth : Math.max(EyeCamera.NEAR, depth - bias);
        long key = ((long) Math.min((1L << 40) - 1, (long) (sortDepth * DEPTH_UNITS))
                << INDEX_BITS) | (queued & INDEX_MASK);
        order[queued] = key;
        queued++;
    }

    /**
     * What the lamps add to one triangle, into {@link #lit}.
     *
     * <p><b>Per triangle, from the face's own normal</b> — which this path has
     * for nothing, because a flat-shaded mesh's three vertices <em>are</em> the
     * face. The card cannot do it this way (a fragment has no idea which
     * triangle it came from) and does not need to: it recovers the same normal
     * from the depth gradient, per fragment, for two instructions. Same
     * geometry, two ways of asking for it, and
     * {@link LightField#contribute} is the one piece of arithmetic both of them
     * then run.
     *
     * <p>The normal is turned toward the eye rather than trusted. Grass blades,
     * leaves and water are single-sided sheets meant to be seen from either
     * face, and a lantern behind a blade of grass must light the side of it you
     * are looking at.
     */
    private void illuminate(float[] verts, int at, double ox, double oy, double oz) {
        int s = Mesh.FLOATS_PER_VERTEX;
        double ax = ox + verts[at], ay = oy + verts[at + 1], az = oz + verts[at + 2];
        double bx = ox + verts[at + s], by = oy + verts[at + s + 1];
        double bz = oz + verts[at + s + 2];
        double cx = ox + verts[at + 2 * s], cy = oy + verts[at + 2 * s + 1];
        double cz = oz + verts[at + 2 * s + 2];
        double px = (ax + bx + cx) / 3, py = (ay + by + cy) / 3, pz = (az + bz + cz) / 3;

        double ux = bx - ax, uy = by - ay, uz = bz - az;
        double vx = cx - ax, vy = cy - ay, vz = cz - az;
        double nx = uy * vz - uz * vy;
        double ny = uz * vx - ux * vz;
        double nz = ux * vy - uy * vx;
        double length = Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (length > 1e-9) {
            nx /= length;
            ny /= length;
            nz /= length;
            if (nx * (px - eye.x()) + ny * (py - eye.y()) + nz * (pz - eye.z()) > 0) {
                nx = -nx;
                ny = -ny;
                nz = -nz;
            }
        } else {
            nx = ny = nz = 0;
        }

        lit[0] = 0;
        lit[1] = 0;
        lit[2] = 0;
        for (int i = 0; i < meshLightCount; i++) {
            LightField.contribute(lights.get(meshLights[i]), px, py, pz,
                    nx, ny, nz, lit);
        }
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

    /**
     * The vertex colour with this hour's light, whatever lamps reach it, and
     * this frame's fog applied — <b>in that order, and the order matters.</b>
     *
     * <p>Light multiplies and fog interpolates: a lit thing a long way off is
     * still mostly haze, and a haze that had been applied first would be
     * <em>brightened</em> by a fire beside the camera. That is the same order
     * the shader uses, for the same reason.
     */
    private int fogged(int argb, double depth, float[] verts, int at,
                       double ox, double oy, double oz) {
        int a = (argb >>> 24) & 0xFF;
        double lightR = tint[0], lightG = tint[1], lightB = tint[2];
        if (meshLightCount > 0) {
            illuminate(verts, at, ox, oy, oz);
            lightR += lit[0];
            lightG += lit[1];
            lightB += lit[2];
        }
        double r = ((argb >> 16) & 0xFF) * lightR;
        double g = ((argb >> 8) & 0xFF) * lightG;
        double b = (argb & 0xFF) * lightB;
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
            // …and what the light does to it: the normals, the roughness and
            // the metalness, from the same bake and on the same tiles. Handing
            // this over is also what tells the backend that the atlas above is
            // a *detail* map rather than a colour one — see MeshPass.DETAIL_GAIN
            // for why those two facts arrive together and what the alternative
            // looked like.
            meshPass.setSurface(WatchMaterials.surface(), WatchMaterials.revision());
            // The hour and the lamps, as uniforms, once for the whole frame.
            // <b>This is where the two paths stop being the same program and
            // start being the same picture.</b> The painter has already shaded
            // every triangle it queued; the card has not shaded anything yet,
            // and is about to do it per fragment with a normal it works out
            // from the depth gradient. Same daylight, same falloff, same wrap
            // term — see MeshPass.setLighting.
            meshPass.setLighting(lights, (float) tint[0], (float) tint[1],
                    (float) tint[2]);
            // …and the sun, the air and the grade, which the painter has no
            // budget for and a card has nothing better to spend on. See
            // setAtmosphere for why only one of the two paths gets this.
            meshPass.setSky(sky);
            meshPass.draw(gpuDraws, eye, fogRgb, fogStart, fogEnd);
            drawn = submitted;
            gpuDraws.clear();
            return;
        }
        if (queued == 0) return;
        Arrays.sort(order, 0, queued);
        // Hard edges for the world. See the note on fillSealed.
        target.setSmoothing(false);
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
        target.setSmoothing(true);
        queued = 0;
    }

    /**
     * <b>Why the world is drawn with smoothing off.</b>
     *
     * <p>Two triangles that share a world edge project to the same screen edge,
     * and neither of them owns the pixels along it. An antialiased fill takes
     * a share of each boundary pixel and blends it against whatever is already
     * there rather than against its neighbour's share, so the pair leaves a
     * pale hairline; fifty thousand abutting terrain triangles draw that
     * hairline along every one of them and the ground ends up under a bright
     * lattice that crawls as you walk.
     *
     * <p>The first fix here was the one
     * {@link com.larsons.engine.graphics.SolidPainter} uses for blocks —
     * stroke each face in the colour it was just filled with, covering exactly
     * that half-pixel. It worked, and it cost <b>2.4 times the frame</b>:
     * stroking an antialiased polygon builds and rasterises an outline shape,
     * and doing it per triangle took a 122 ms frame to 288 ms. On a screen
     * already struggling that is not a trade worth making.
     *
     * <p>Turning smoothing off instead fixes the seam at its cause — a shared
     * edge becomes exact, so there is nothing to bleed through — and is
     * <em>faster</em> than the frame was before the seam was ever addressed.
     * A world of flat-shaded facets loses nothing by having hard edges; it is
     * what it looks like anyway. Smoothing goes back on before the HUD, which
     * very much does want it.
     */
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
