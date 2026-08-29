package com.larsons.engine.watch.render;

import com.larsons.engine.watch.Chart;
import com.larsons.engine.watch.world.TerrainField;
import com.larsons.engine.watch.world.WatchBiome;
import com.larsons.engine.watch.world.WatchMaterial;

import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * The picture on a map: a square of the world, seen from directly above.
 *
 * <h2>Painted from the seed, not stored on the map</h2>
 *
 * <p>Everything here is a pure function of {@code (seed, centre, radius)}. That
 * is the same observation {@link com.larsons.engine.watch.Shops} makes about
 * trading posts and {@code Boats} makes about boats, taken one step further:
 * because the ground is a function of the seed, so is a picture of it, so a map
 * on the wire is four numbers and a name and the picture is worked out at both
 * ends. A map's paper is never transmitted, never saved, and never goes stale.
 *
 * <p>It is also why the picture is <em>identical</em> for everybody — down to
 * the pixel, on a card or through Java2D — and why two maps of overlapping
 * country agree exactly where they overlap. A board leans on that: it draws its
 * maps side by side in world coordinates and the join is invisible because both
 * sides of it were painted from the same function of the same ground.
 *
 * <h2>Baked on a worker, like a chunk</h2>
 *
 * <p>{@link TerrainField#heightAt} is eight noise fields and a trail query, and
 * a map is tens of thousands of samples of it — a fifth of a second of work at
 * the sizes here. Doing that on the frame thread would drop a frame every time
 * a panel opened, so {@link #of} returns whatever is already painted and puts
 * anything else on a queue for the one daemon thread that paints them. The
 * panel draws "surveying" for a frame or two and then the map is there, which
 * is the same bargain {@code ChunkStreamer} makes with the ground itself.
 *
 * <p>{@link #bake} is the synchronous door, for tests and for anything that
 * genuinely has to have the picture before it can go on.
 */
public final class ChartImage {

    /** The smallest picture painted for a map, in pixels a side. */
    public static final int MIN_PIXELS = 128;

    /** …and the largest. */
    public static final int MAX_PIXELS = 256;

    /**
     * How many metres of world one pixel would ideally cover.
     *
     * <p>Eight is a little coarser than the terrain mesh's own two-metre step,
     * which is right: a map is a plan of a country and not a photograph of it,
     * and a pixel that resolves individual boulders would make a wood look like
     * noise. It is only a target — {@link #pixelsFor} clamps it at both ends, so
     * a small map is drawn finer than eight and a very large one coarser.
     */
    private static final double METRES_PER_PIXEL = 8;

    /**
     * How coarse the biome grid is, relative to the height grid.
     *
     * <p>A biome is hundreds of metres across and deciding one costs a second
     * climate evaluation, so it is read every eighth pixel and held. At the
     * scales here that is a sample every sixty metres or so, and a border that
     * steps by sixty metres on a map of two kilometres is a border drawn with a
     * thick pen — which is what a border on a map is.
     */
    private static final int BIOME_EVERY = 8;

    /** How many pictures are kept. */
    private static final int CACHE_LIMIT = 24;

    /** Where the light comes from on a map: over your left shoulder, as ever. */
    private static final double LIGHT_X = -0.55, LIGHT_Y = -0.55, LIGHT_Z = 0.63;

    /** How much of the shading is hillshade rather than flat material colour. */
    private static final double RELIEF = 0.45;

    private static final Map<String, BufferedImage> CACHE =
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override protected boolean removeEldestEntry(
                        Map.Entry<String, BufferedImage> eldest) {
                    return size() > CACHE_LIMIT;
                }
            };

    /** What is already on the queue, so a panel asking every frame queues once. */
    private static final Set<String> PENDING = ConcurrentHashMap.newKeySet();

    private static final LinkedBlockingQueue<Runnable> WORK = new LinkedBlockingQueue<>();

    private static Thread painter;

    private ChartImage() {}

    /** Forget every picture — what a texture-pack rescan or a test calls. */
    public static void invalidate() {
        synchronized (CACHE) {
            CACHE.clear();
        }
        PENDING.clear();
    }

    /** How large a picture a map of this size gets. */
    public static int pixelsFor(double radius) {
        int wanted = (int) Math.round(radius * 2 / METRES_PER_PIXEL);
        return Math.max(MIN_PIXELS, Math.min(MAX_PIXELS, wanted));
    }

    /**
     * The picture for a map, or {@code null} while it is still being painted.
     *
     * <p>Asking again next frame is the intended use: the call is a map lookup
     * once the answer exists, and queueing is guarded so a panel redrawing sixty
     * times a second queues the same picture once.
     */
    public static BufferedImage of(TerrainField field, Chart chart) {
        if (field == null || chart == null) return null;
        return of(field, chart.centreX(), chart.centreY(), chart.radius());
    }

    /** The picture for any square of the world, or {@code null} while it paints. */
    public static BufferedImage of(TerrainField field, double centreX, double centreY,
                                   double radius) {
        if (field == null) return null;
        int pixels = pixelsFor(radius);
        String key = key(field, centreX, centreY, radius, pixels);
        BufferedImage cached;
        synchronized (CACHE) {
            cached = CACHE.get(key);
        }
        if (cached != null) return cached;
        if (PENDING.add(key)) {
            start();
            WORK.add(() -> {
                // The removal is in a finally, so a map that throws while it is
                // being painted is a map that can be asked for again. Left in
                // the happy path, one failure would pin its key in PENDING for
                // the life of the process and that map would draw "surveying"
                // for ever.
                try {
                    BufferedImage painted = paint(field, centreX, centreY, radius, pixels);
                    synchronized (CACHE) {
                        CACHE.put(key, painted);
                    }
                } finally {
                    PENDING.remove(key);
                }
            });
        }
        return null;
    }

    /** Paint one now, on this thread, and remember it. */
    public static BufferedImage bake(TerrainField field, double centreX, double centreY,
                                     double radius) {
        int pixels = pixelsFor(radius);
        String key = key(field, centreX, centreY, radius, pixels);
        synchronized (CACHE) {
            BufferedImage cached = CACHE.get(key);
            if (cached != null) return cached;
        }
        BufferedImage painted = paint(field, centreX, centreY, radius, pixels);
        synchronized (CACHE) {
            CACHE.put(key, painted);
        }
        PENDING.remove(key);
        return painted;
    }

    /** Whether a picture is already in hand — for tests, and for nothing on screen. */
    public static boolean ready(TerrainField field, double centreX, double centreY,
                                double radius) {
        synchronized (CACHE) {
            return CACHE.containsKey(key(field, centreX, centreY, radius,
                    pixelsFor(radius)));
        }
    }

    private static String key(TerrainField field, double centreX, double centreY,
                              double radius, int pixels) {
        return field.seed() + ":" + centreX + ":" + centreY + ":" + radius + ":" + pixels;
    }

    /**
     * The one painter thread.
     *
     * <p>One, and a daemon. One because a map is not urgent and eight of them
     * competing for the same cores as the chunk workers would make the walk
     * stutter to fill in a panel nobody is looking at yet; a daemon because a
     * half-painted map must never be the reason a game will not quit.
     */
    private static synchronized void start() {
        if (painter != null && painter.isAlive()) return;
        painter = new Thread(() -> {
            while (true) {
                try {
                    WORK.take().run();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (RuntimeException e) {
                    // A map that will not paint is one blank panel, not a dead
                    // thread that silently stops painting every map after it.
                    System.err.println("watch: could not paint a map — " + e);
                }
            }
        }, "watch-chart-painter");
        painter.setDaemon(true);
        painter.start();
    }

    /**
     * Sample the ground and colour it in.
     *
     * <p>One extra row and column of heights are taken around the edge so every
     * pixel has the four neighbours the hillshade needs — the alternative is a
     * one-pixel border shaded differently from the rest, which on a map that
     * tiles with another map is a visible seam down the join.
     */
    private static BufferedImage paint(TerrainField field, double centreX, double centreY,
                                       double radius, int pixels) {
        double step = radius * 2 / pixels;
        double originX = centreX - radius + step / 2;
        double originY = centreY - radius + step / 2;

        // (pixels + 2)² heights, indexed with a one-cell border.
        int n = pixels + 2;
        double[] heights = new double[n * n];
        for (int iy = 0; iy < n; iy++) {
            double wy = originY + (iy - 1) * step;
            for (int ix = 0; ix < n; ix++) {
                heights[iy * n + ix] = field.heightAt(originX + (ix - 1) * step, wy);
            }
        }

        // The biome grid, coarse. Held between samples rather than looked up per
        // pixel: see BIOME_EVERY.
        int bn = pixels / BIOME_EVERY + 2;
        WatchBiome[] biomes = new WatchBiome[bn * bn];
        for (int by = 0; by < bn; by++) {
            for (int bx = 0; bx < bn; bx++) {
                biomes[by * bn + bx] = field.biomeAt(originX + bx * BIOME_EVERY * step,
                        originY + by * BIOME_EVERY * step);
            }
        }

        BufferedImage image = new BufferedImage(pixels, pixels,
                BufferedImage.TYPE_INT_RGB);
        for (int py = 0; py < pixels; py++) {
            for (int px = 0; px < pixels; px++) {
                int i = (py + 1) * n + (px + 1);
                double h = heights[i];
                double west = heights[i - 1], east = heights[i + 1];
                double north = heights[i - n], south = heights[i + n];
                double dx = (east - west) / (2 * step);
                double dy = (south - north) / (2 * step);

                int rgb;
                if (h < TerrainField.WATER_LEVEL) {
                    rgb = water(TerrainField.WATER_LEVEL - h);
                } else {
                    WatchBiome biome = biomes[Math.min(bn - 1, py / BIOME_EVERY) * bn
                            + Math.min(bn - 1, px / BIOME_EVERY)];
                    WatchMaterial surface = field.surfaceAt(originX + px * step,
                            originY + py * step, h, Math.hypot(dx, dy), biome);
                    rgb = shade(surface.rgb(), dx, dy);
                }
                image.setRGB(px, py, rgb);
            }
        }
        return image;
    }

    /**
     * A material's colour under the map's own light.
     *
     * <p>Flat colours rather than the terrain's textures, and a fixed light
     * rather than the sky's: a map is drawn on paper by somebody who was there
     * in the daylight, so it does not go dark at night, it does not go grey in
     * the rain, and it does not change when a texture pack does. What the
     * hillshade is for is the one thing a flat plan otherwise cannot say, which
     * is which way the ground falls.
     */
    private static int shade(int rgb, double dx, double dy) {
        // The surface normal of a heightfield is (−dz/dx, −dz/dy, 1), normalised.
        double nx = -dx, ny = -dy, nz = 1;
        double length = Math.sqrt(nx * nx + ny * ny + 1);
        double lambert = (nx * LIGHT_X + ny * LIGHT_Y + nz * LIGHT_Z) / length;
        double lit = 1 - RELIEF + RELIEF * Math.max(0, Math.min(1, lambert + 0.35));
        int r = clampByte(((rgb >> 16) & 0xFF) * lit);
        int g = clampByte(((rgb >> 8) & 0xFF) * lit);
        int b = clampByte((rgb & 0xFF) * lit);
        return (r << 16) | (g << 8) | b;
    }

    /** Water, darkening with depth — the one thing a plan can say about a lake. */
    private static int water(double depth) {
        double deep = Math.max(0, Math.min(1, depth / 12));
        int shallow = WatchMaterial.SHALLOWS.rgb();
        int open = WatchMaterial.WATER.rgb();
        int r = clampByte(mix((shallow >> 16) & 0xFF, (open >> 16) & 0xFF, deep));
        int g = clampByte(mix((shallow >> 8) & 0xFF, (open >> 8) & 0xFF, deep));
        int b = clampByte(mix(shallow & 0xFF, open & 0xFF, deep));
        return (r << 16) | (g << 8) | b;
    }

    private static double mix(double a, double b, double t) { return a + (b - a) * t; }

    private static int clampByte(double v) {
        return (int) Math.max(0, Math.min(255, Math.round(v)));
    }
}
