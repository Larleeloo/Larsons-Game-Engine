package com.larsons.engine.graphics.chunk;

import com.larsons.engine.level.Level;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Every section of world that has been meshed, and the machinery that keeps
 * that true — <b>Minecraft's chunk-render manager</b>.
 *
 * <p><b>Nothing is meshed on the frame that wants it.</b> A section build is
 * five thousand cell reads and a few thousand vertices; doing it while a frame
 * is waiting is exactly the stutter a chunk-mesh renderer exists to remove. So
 * a frame asks for what it can see, anything missing is queued nearest-first on
 * a small pool of workers, and the frame draws what has arrived. What has not
 * is a section of world that appears a frame or two later, at the edge of the
 * view, which is what every game of this shape does.
 *
 * <p><b>What invalidates a mesh</b> is a write to the level. A block placed at
 * a section's edge changes the faces of the section next door, so an edit
 * dirties the section it landed in and any neighbour it touches — the same
 * six-cell argument the column mesh makes one dimension down.
 *
 * <p><b>What evicts one</b> is distance. Meshes are the memory cost of a long
 * render distance — a section of surface terrain is a few thousand vertices, a
 * hundred kilobytes with its colours — so the furthest are dropped once the
 * budget is passed and rebuilt if the player turns round. They are a function
 * of the world, so dropping one loses nothing but the work.
 */
public final class TerrainSections {

    /** Sections held before the furthest are dropped. */
    private static final int DEFAULT_BUDGET = 4096;

    /** Builds queued in one frame, so a teleport does not queue the world. */
    private static final int MAX_QUEUED_PER_FRAME = 32;

    private final Level level;
    private final BlockAtlas atlas;
    private final int budget;

    private final Map<Long, SectionMesh> meshes = new ConcurrentHashMap<>();
    private final java.util.Set<Long> building = ConcurrentHashMap.newKeySet();
    /** Sections whose mesh is stale, so a new one is wanted even though one exists. */
    private final java.util.Set<Long> dirty = ConcurrentHashMap.newKeySet();
    private ExecutorService workers;

    private volatile int centreX, centreY, centreZ;
    private int queuedThisFrame;

    public TerrainSections(Level level, BlockAtlas atlas) {
        this(level, atlas, DEFAULT_BUDGET);
    }

    public TerrainSections(Level level, BlockAtlas atlas, int budget) {
        this.level = level;
        this.atlas = atlas;
        this.budget = Math.max(64, budget);
    }

    /** How many sections are meshed right now (diagnostics). */
    public int meshCount() { return meshes.size(); }

    /** How many are being meshed right now (diagnostics). */
    public int buildingCount() { return building.size(); }

    /** Start a frame: where the camera is, and a fresh build budget. */
    public void beginFrame(int sectionX, int sectionY, int sectionZ) {
        this.centreX = sectionX;
        this.centreY = sectionY;
        this.centreZ = sectionZ;
        this.queuedThisFrame = 0;
    }

    /**
     * The mesh of one section, queueing a build when there is not one.
     *
     * <p>Returns the stale mesh while a rebuild is in flight rather than
     * nothing: an edit moves one block and the section is a thousand faces of
     * landscape, so last build's answer is a better picture than a hole for the
     * frame or two it takes.
     */
    public SectionMesh mesh(int sx, int sy, int sz) {
        long key = key(sx, sy, sz);
        SectionMesh mesh = meshes.get(key);
        if (mesh != null && !dirty.contains(key)) return mesh;
        queue(key, sx, sy, sz);
        return mesh;
    }

    private void queue(long key, int sx, int sy, int sz) {
        if (queuedThisFrame >= MAX_QUEUED_PER_FRAME) return;
        if (!building.add(key)) return;
        queuedThisFrame++;
        pool().execute(() -> {
            try {
                SectionMesh built = SectionMesher.build(level, atlas, sx, sy, sz);
                meshes.put(key, built);
                dirty.remove(key);
                if (meshes.size() > budget) trim();
            } catch (RuntimeException e) {
                System.err.println("TerrainSections: section " + sx + "," + sy + "," + sz
                        + " failed to mesh: " + e);
            } finally {
                building.remove(key);
            }
        });
    }

    /**
     * A block changed at these level coordinates: forget the section it landed
     * in, and any neighbour whose faces it can have changed.
     */
    public void invalidate(int col, int row, int layer) {
        int box = layer - 1;
        int sx = Math.floorDiv(col, SectionMesh.SIZE);
        int sz = Math.floorDiv(row, SectionMesh.SIZE);
        int sy = Math.floorDiv(box, SectionMesh.SIZE);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    // Only the neighbours the cell actually touches: a block in
                    // the middle of a section cannot change the section next
                    // door, and dirtying twenty-seven of them for every block
                    // placed is twenty-six rebuilds nobody asked for.
                    if (dx != 0 && Math.floorMod(col, SectionMesh.SIZE)
                            != (dx < 0 ? 0 : SectionMesh.SIZE - 1)) {
                        continue;
                    }
                    if (dz != 0 && Math.floorMod(row, SectionMesh.SIZE)
                            != (dz < 0 ? 0 : SectionMesh.SIZE - 1)) {
                        continue;
                    }
                    if (dy != 0 && Math.floorMod(box, SectionMesh.SIZE)
                            != (dy < 0 ? 0 : SectionMesh.SIZE - 1)) {
                        continue;
                    }
                    dirty.add(key(sx + dx, sy + dy, sz + dz));
                }
            }
        }
    }

    /** Forget everything — the level's terrain was replaced under us. */
    public void clear() {
        meshes.clear();
        dirty.clear();
    }

    /** Let the meshing threads go. */
    public void close() {
        ExecutorService pool = workers;
        workers = null;
        if (pool != null) pool.shutdownNow();
        clear();
    }

    private void trim() {
        int cx = centreX, cy = centreY, cz = centreZ;
        List<Long> keys = new ArrayList<>(meshes.keySet());
        keys.sort(Comparator.comparingLong(k -> -distanceSq(k, cx, cy, cz)));
        int drop = Math.min(keys.size(), meshes.size() - budget);
        for (int i = 0; i < drop; i++) meshes.remove(keys.get(i));
    }

    private synchronized ExecutorService pool() {
        if (workers == null) {
            AtomicInteger n = new AtomicInteger();
            ThreadFactory factory = r -> {
                Thread t = new Thread(r, "section-mesher-" + n.incrementAndGet());
                t.setDaemon(true);
                // Below the game loop: a section arriving a frame later is
                // invisible, a dropped frame is not.
                t.setPriority(Thread.MIN_PRIORITY);
                return t;
            };
            workers = Executors.newFixedThreadPool(
                    Math.max(1, Runtime.getRuntime().availableProcessors() / 2), factory);
        }
        return workers;
    }

    /** {@code sx}, {@code sy}, {@code sz} in one long. */
    public static long key(int sx, int sy, int sz) {
        return ((long) (sx & 0x1FFFFF) << 42) | ((long) (sy & 0x1FFFFF) << 21) | (sz & 0x1FFFFF);
    }

    private static int signed(long packed) {
        int v = (int) (packed & 0x1FFFFF);
        return v >= 0x100000 ? v - 0x200000 : v;
    }

    private static long distanceSq(long key, int cx, int cy, int cz) {
        long x = signed(key >>> 42) - cx;
        long y = signed(key >>> 21) - cy;
        long z = signed(key) - cz;
        return x * x + y * y + z * z;
    }
}
