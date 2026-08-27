package com.larsons.engine.graphics;

/**
 * How much world this machine is willing to keep in memory — <b>one number,
 * derived from the heap, read by everything that caches ground.</b>
 *
 * <h2>Why a streaming world should hoard</h2>
 *
 * <p>A streaming world's default instinct is to throw away everything behind
 * you, because the machine it was designed on could not hold it. That instinct
 * is thirty years old and wrong on a machine with sixteen gigabytes in it: a
 * meshed chunk of low-poly heightfield is a few tens of kilobytes, a thousand of
 * them is a few tens of megabytes, and a thousand chunks is a kilometre in every
 * direction. Walking to the lake and back should not regenerate the path there
 * — it costs a few milliseconds of noise per chunk, on worker threads that are
 * then not building the ground in front of you, and it is the single most
 * common source of "why did it stutter just then" in a game of this shape.
 *
 * <p>So the rule here is: <b>keep what has been built until the heap says
 * otherwise.</b> The budget is a share of {@link Runtime#maxMemory()}, floored
 * so a small heap still keeps a useful ring and capped so a huge one does not
 * commit to a cache it will never fill.
 *
 * <h2>Why it lives in graphics and not in the field guide</h2>
 *
 * <p>Two callers, on either side of the module boundary: the chunk streamer in
 * {@code watch.world} sizes its retained cache from {@link #chunkCacheBudget},
 * and the GL backend sizes its buffer ceiling from {@link #gpuBufferBudget} so
 * the card is never the thing that forces a re-mesh of ground the CPU still
 * holds. The core cannot import {@code :gl} and {@code :gl} should not import
 * a mini game's internals, so the number they agree on lives here — JDK only,
 * like everything else in the core.
 */
public final class ChunkMemory {

    /**
     * The share of the heap a world cache may occupy.
     *
     * <p>An eighth. Deliberately modest: this is a cache of things that can be
     * rebuilt, so it must never be the reason something that cannot be rebuilt
     * fails to allocate. An eighth of a default 4 GB heap is 512 MB, which at
     * the sizes below is several thousand chunks — far more world than anybody
     * walks in a session.
     */
    private static final double HEAP_SHARE = 0.125;

    /** What one meshed chunk of low-poly heightfield weighs, in bytes. */
    private static final long BYTES_PER_CHUNK = 96 * 1024;

    /** Never keep fewer than this, however small the heap. */
    private static final int MIN_CHUNKS = 256;

    /** Never commit to more than this, however large it is. */
    private static final int MAX_CHUNKS = 12_288;

    private ChunkMemory() {}

    /** The heap this JVM may grow to, in bytes. */
    public static long maxHeapBytes() {
        long max = Runtime.getRuntime().maxMemory();
        // A JVM with no ceiling reports Long.MAX_VALUE. Treat that as generous
        // rather than as infinite, which would ask for a cache of two hundred
        // million chunks.
        return max == Long.MAX_VALUE ? 4L << 30 : max;
    }

    /**
     * How many meshed chunks may be held in memory at once, generated ground
     * that has been walked away from included.
     *
     * <p>Callers treat this as a soft ceiling on a least-recently-used cache:
     * over it, the chunk nobody has been near for longest is dropped, and
     * dropping it costs only the work to build it again if anybody goes back.
     */
    public static int chunkCacheBudget() {
        long bytes = (long) (maxHeapBytes() * HEAP_SHARE);
        long chunks = bytes / BYTES_PER_CHUNK;
        return (int) Math.max(MIN_CHUNKS, Math.min(MAX_CHUNKS, chunks));
    }

    /**
     * How many mesh buffers a graphics backend should be willing to keep.
     *
     * <p>Four per chunk — ground, water, flora, grass — plus headroom for
     * everything that is not ground. A backend below this is forcing the CPU to
     * re-mesh chunks it still has, which is the expensive half of the work.
     */
    public static int gpuBufferBudget() {
        return Math.min(65_536, chunkCacheBudget() * 4 + 1024);
    }

    /** A one-line description for a debug overlay or a log. */
    public static String describe() {
        return chunkCacheBudget() + " chunks cached (heap "
                + (maxHeapBytes() >> 20) + " MB)";
    }
}
