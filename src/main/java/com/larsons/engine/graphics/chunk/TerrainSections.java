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
 * <p><b>What evicts one is distance, and what is counted is bytes.</b> Counting
 * sections is the mistake that looks reasonable: most of the sections inside a
 * long view are open sky or the inside of a mountain, both of which mesh to
 * nothing and cost nothing to keep — so a budget of four thousand
 * <em>sections</em> is spent almost entirely on emptiness and then evicts the
 * ground the player is looking at. Measured at sixteen chunks: 4 098 sections
 * held, 567 drawn, and the walk asking every frame for a rim that had just been
 * thrown away. That is what a flashing horizon is, and it does not get better
 * with distance — at thirty-two chunks the same 567 sections were drawn, because
 * the cache could not hold one section more.
 *
 * <p>So the budget is a number of bytes, sized from the heap the JVM was given,
 * and emptiness is free. What that buys is a cache that holds the whole of a
 * long view when the view fits and degrades honestly when it does not — see
 * {@link #affordableReach}, which is how the caller finds out where to stop
 * asking rather than discovering it as a hole.
 */
public final class TerrainSections {

    /**
     * The largest share of the heap the meshes may take.
     *
     * <p>A third, capped. Meshes are the one structure here that scales with the
     * square of the render distance, so they get the lion's share of what is
     * going spare — but a third leaves the world's own chunks, the atlas and
     * every other part of the game the room they had, and the cap keeps a
     * machine with a very large heap from filling it with terrain it will never
     * look at.
     */
    private static final double HEAP_SHARE = 1.0 / 3;
    private static final long MAX_BYTE_BUDGET = 1536L * 1024 * 1024;
    private static final long MIN_BYTE_BUDGET = 64L * 1024 * 1024;

    /**
     * Builds queued in one frame, and how many may be in flight at once.
     *
     * <p>The per-frame cap is what stops a teleport queueing the world in one
     * go; the in-flight cap is what actually paces the workers. The first is
     * generous now because the second exists: the old arrangement had only the
     * per-frame cap at thirty-two, which at sixty frames a second fills a
     * ninety-chunk view in about a minute — long enough that the player watches
     * it happen, which is the loading stutter rather than a fix for it.
     */
    private static final int MAX_QUEUED_PER_FRAME = 256;
    private static final int MAX_IN_FLIGHT = 512;

    /** How much of the budget is left standing after a trim; see {@link #trim}. */
    private static final double LOW_WATER = 0.85;

    /**
     * Map entries held, whatever they weigh.
     *
     * <p>Not a memory budget — that is {@link #byteBudget} — but a ceiling on
     * the map itself, because empty sections are kept for their visibility and a
     * player who walks a long way would otherwise accumulate every piece of sky
     * they have ever stood under. Sixty bytes an entry, so this is about twelve
     * megabytes of bookkeeping: far more than a ninety-chunk view needs, which
     * is the point of it being a safety net rather than a policy.
     */
    private static final int MAX_SECTIONS = 200_000;

    private final Level level;
    private final BlockAtlas atlas;
    private final long byteBudget;

    private final Map<Long, SectionMesh> meshes = new ConcurrentHashMap<>();
    private final java.util.Set<Long> building = ConcurrentHashMap.newKeySet();
    /** Sections whose mesh is stale, so a new one is wanted even though one exists. */
    private final java.util.Set<Long> dirty = ConcurrentHashMap.newKeySet();
    private ExecutorService workers;

    /** Bytes of mesh held, kept in step with {@link #meshes} rather than summed. */
    private final java.util.concurrent.atomic.AtomicLong bytes =
            new java.util.concurrent.atomic.AtomicLong();

    /**
     * Non-empty sections held, and their bytes — what {@link #affordableReach}
     * divides to find out how much ground a byte buys on <em>this</em> world.
     */
    private final AtomicInteger solidSections = new AtomicInteger();

    private volatile int centreX, centreY, centreZ;
    private int queuedThisFrame;
    private volatile boolean trimming;

    public TerrainSections(Level level, BlockAtlas atlas) {
        this(level, atlas, defaultByteBudget());
    }

    public TerrainSections(Level level, BlockAtlas atlas, long byteBudget) {
        this.level = level;
        this.atlas = atlas;
        this.byteBudget = Math.max(4L * 1024 * 1024, byteBudget);
    }

    static long defaultByteBudget() {
        long share = (long) (Runtime.getRuntime().maxMemory() * HEAP_SHARE);
        return Math.max(MIN_BYTE_BUDGET, Math.min(MAX_BYTE_BUDGET, share));
    }

    /** How many sections are meshed right now (diagnostics). */
    public int meshCount() { return meshes.size(); }

    /** How many bytes of mesh are held right now (diagnostics). */
    public long meshBytes() { return bytes.get(); }

    /** The ceiling {@link #meshBytes} is kept under. */
    public long byteBudget() { return byteBudget; }

    /** How many are being meshed right now (diagnostics). */
    public int buildingCount() { return building.size(); }

    /**
     * <b>How far this cache can actually keep the world, in world units.</b>
     *
     * <p>The number a caller needs and could not otherwise have. A view distance
     * is a request; what a machine can hold is a fact about its heap and about
     * how much geometry this particular terrain has per section — a plain and a
     * mountain range cost very different amounts for the same radius. So it is
     * measured rather than assumed: the bytes held divided by the non-empty
     * sections held gives what a section of <em>this</em> world weighs, the
     * budget divided by that gives how many fit, and the surface band being a
     * couple of sections deep turns that into a radius.
     *
     * <p><b>Answering honestly matters more than answering large.</b> A caller
     * that asks for more than this does not get more world, it gets the rim of
     * its view built and evicted and built again — which is a frame rate spent
     * on nothing and a horizon that flashes. Told where the detail really stops,
     * it can hand over to the level-of-detail tree exactly there, and the seam
     * is invisible.
     *
     * @param tileSize world units per cell
     * @return the radius the byte budget supports, or {@link Double#MAX_VALUE}
     *         while nothing is yet known about this world
     */
    public double affordableReach(int tileSize) {
        int solid = solidSections.get();
        long held = bytes.get();
        // Nothing built yet, or nothing in it: no evidence to be careful with.
        if (solid < MIN_SAMPLE || held <= 0) return Double.MAX_VALUE;
        double perSection = held / (double) solid;
        double affordable = byteBudget / perSection;
        // The surface is a band a couple of sections deep, not a single sheet:
        // a hillside puts three or four non-empty sections over one column, a
        // plain puts one. Measured the same way as the weight — non-empty
        // sections over the ground they cover — would need the walk's own
        // footprint, so this is the conservative constant instead.
        double columns = affordable / SURFACE_SECTIONS_PER_COLUMN;
        double radiusInSections = Math.sqrt(columns / Math.PI);
        return radiusInSections * SectionMesh.SIZE * (double) Math.max(1, tileSize);
    }

    /** Non-empty sections needed before {@link #affordableReach} trusts its average. */
    private static final int MIN_SAMPLE = 64;

    /** How deep the non-empty band over one column of world tends to be. */
    private static final double SURFACE_SECTIONS_PER_COLUMN = 2.5;

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
        // The empty check first, and it is not a micro-optimisation: this runs
        // tens of thousands of times a frame, `dirty` is empty except in the
        // frames just after a block was placed, and asking a ConcurrentHashMap
        // costs a boxed key and a hash either way. Half the lookups in the walk
        // are this one.
        if (mesh != null && (dirty.isEmpty() || !dirty.contains(key))) return mesh;
        queue(key, sx, sy, sz);
        return mesh;
    }

    private void queue(long key, int sx, int sy, int sz) {
        if (queuedThisFrame >= MAX_QUEUED_PER_FRAME) return;
        if (building.size() >= MAX_IN_FLIGHT) return;
        if (!building.add(key)) return;
        queuedThisFrame++;
        pool().execute(() -> {
            try {
                SectionMesh built = SectionMesher.build(level, atlas, sx, sy, sz);
                store(key, built);
                dirty.remove(key);
                if (bytes.get() > byteBudget || meshes.size() > MAX_SECTIONS) trim();
            } catch (RuntimeException e) {
                System.err.println("TerrainSections: section " + sx + "," + sy + "," + sz
                        + " failed to mesh: " + e);
            } finally {
                building.remove(key);
            }
        });
    }

    /** Put a mesh in, keeping the byte and non-empty tallies in step with it. */
    private void store(long key, SectionMesh built) {
        SectionMesh previous = meshes.put(key, built);
        long delta = built.byteCount() - (previous == null ? 0 : previous.byteCount());
        bytes.addAndGet(delta);
        int wasSolid = previous != null && !previous.isEmpty() ? 1 : 0;
        int isSolid = built.isEmpty() ? 0 : 1;
        if (isSolid != wasSolid) solidSections.addAndGet(isSolid - wasSolid);
    }

    /** Take one out, the same way. */
    private void forget(long key) {
        SectionMesh gone = meshes.remove(key);
        if (gone == null) return;
        bytes.addAndGet(-gone.byteCount());
        if (!gone.isEmpty()) solidSections.decrementAndGet();
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
        bytes.set(0);
        solidSections.set(0);
    }

    /** Let the meshing threads go. */
    public void close() {
        ExecutorService pool = workers;
        workers = null;
        if (pool != null) pool.shutdownNow();
        clear();
    }

    /**
     * Drop the furthest meshes until the budget has headroom again.
     *
     * <p><b>Down to a low-water mark, and only one thread at a time.</b> Both
     * halves are the fix for what this used to be: a full sort of every key, on
     * a worker thread, for <em>every build</em> once the budget was passed —
     * which past the budget is every build there is, several threads deep, each
     * one boxing four thousand longs to throw away one mesh. Trimming to
     * fifteen per cent under instead means one sort buys thousands of builds,
     * and the flag means the other workers get on with meshing rather than
     * queueing up to sort the same map.
     *
     * <p>Empty sections are never dropped: they weigh nothing, and their
     * visibility is what lets the walk see <em>through</em> them. Evicting the
     * sky to make room for the ground under it would stop the walk at the first
     * thing it threw away.
     */
    private void trim() {
        if (trimming) return;
        synchronized (this) {
            if (trimming) return;
            if (bytes.get() <= byteBudget && meshes.size() <= MAX_SECTIONS) return;
            trimming = true;
        }
        try {
            int cx = centreX, cy = centreY, cz = centreZ;
            long byteTarget = (long) (byteBudget * LOW_WATER);
            int countTarget = (int) (MAX_SECTIONS * LOW_WATER);
            List<Long> keys = new ArrayList<>(meshes.keySet());
            keys.sort(Comparator.comparingLong(k -> -distanceSq(k, cx, cy, cz)));
            for (Long key : keys) {
                boolean overBytes = bytes.get() > byteTarget;
                boolean overCount = meshes.size() > countTarget;
                if (!overBytes && !overCount) break;
                SectionMesh mesh = meshes.get(key);
                // An empty section weighs nothing and its visibility is what
                // lets the walk see *through* it, so bytes alone never justify
                // dropping one. Only the entry ceiling does — a player who has
                // walked a long way leaves a map full of remembered sky.
                if (mesh != null && mesh.isEmpty() && !overCount) continue;
                forget(key);
            }
        } finally {
            trimming = false;
        }
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
