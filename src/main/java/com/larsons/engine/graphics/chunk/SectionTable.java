package com.larsons.engine.graphics.chunk;

/**
 * A {@code long}-keyed table of section meshes, touched by one thread only —
 * <b>the structure the render walk reads tens of thousands of times a frame.</b>
 *
 * <h2>Why not a ConcurrentHashMap</h2>
 *
 * <p>It was one, and at a long render distance it was most of what a frame
 * cost. Three reasons, and none of them is the map being badly written:
 *
 * <ul>
 *   <li><b>The key is a {@code long} and the map wants an object.</b> Every
 *       lookup boxes one — an allocation per visited section, tens of
 *       thousands a frame, purely to ask a question about three small
 *       integers.</li>
 *   <li><b>The answer is one reference and the road to it is four.</b> Table,
 *       bin, node, key: four dependent loads, each a probable cache miss on a
 *       map of a hundred thousand entries. Two parallel arrays are one load
 *       each, next to each other.</li>
 *   <li><b>Nothing here is actually concurrent.</b> The walk reads; the
 *       meshers write. Making the <em>map</em> handle that costs every read a
 *       volatile, when the writes can simply be handed over instead.</li>
 * </ul>
 *
 * <p>So: open addressing with linear probing over parallel arrays, and the
 * hand-over is a queue rather than a lock. Meshers push finished sections onto
 * {@link TerrainSections}'s arrival queue and the frame drains it — which
 * means <b>this class never sees a second thread</b> and needs no
 * synchronisation at all, not even a volatile. That is not a shortcut around
 * the concurrency; it is the concurrency moved to the one place that can be
 * both correct and cheap.
 *
 * <p>Measured on the walk at twenty-two chunks: 345 ns per visited section
 * before, and the map was the bulk of it.
 *
 * <h2>Deletion</h2>
 *
 * <p>Removal in an open-addressed table cannot simply blank a slot — anything
 * that probed <em>past</em> that slot becomes unreachable. This uses backward
 * shift deletion rather than tombstones: the run after the hole is walked and
 * each entry moved back if it can be, which keeps the table free of gravestones
 * however many times the cache is trimmed. Trimming happens in thousands at a
 * time, so tombstones would otherwise accumulate until every lookup walked
 * them.
 */
final class SectionTable {

    /** Load factor, as a shift: grow when count exceeds half the capacity. */
    private static final int START = 1 << 12;

    private long[] keys;
    private SectionMesh[] values;
    private int mask;
    private int count;

    SectionTable() {
        keys = new long[START];
        values = new SectionMesh[START];
        mask = START - 1;
    }

    int size() { return count; }

    /**
     * The mesh for {@code key}, or {@code null}.
     *
     * <p>Key {@code 0} is legal — it is the section at the world's origin — so
     * an occupied slot is one with a non-null <em>value</em> rather than a
     * non-zero key. That costs nothing: the value has to be read anyway.
     */
    SectionMesh get(long key) {
        int at = (int) (mix(key) & mask);
        while (true) {
            SectionMesh there = values[at];
            if (there == null) return null;
            if (keys[at] == key) return there;
            at = (at + 1) & mask;
        }
    }

    /** Put a mesh in, returning whatever was there before. */
    SectionMesh put(long key, SectionMesh mesh) {
        int at = (int) (mix(key) & mask);
        while (true) {
            SectionMesh there = values[at];
            if (there == null) {
                keys[at] = key;
                values[at] = mesh;
                if (++count * 2 > keys.length) grow();
                return null;
            }
            if (keys[at] == key) {
                values[at] = mesh;
                return there;
            }
            at = (at + 1) & mask;
        }
    }

    /** Take one out, returning it, and close the run behind it. */
    SectionMesh remove(long key) {
        int at = (int) (mix(key) & mask);
        while (true) {
            SectionMesh there = values[at];
            if (there == null) return null;
            if (keys[at] == key) {
                values[at] = null;
                count--;
                closeRun(at);
                return there;
            }
            at = (at + 1) & mask;
        }
    }

    /**
     * Shift the run after a hole back over it, so nothing that probed past the
     * removed slot is stranded behind the gap it left.
     */
    private void closeRun(int hole) {
        int at = (hole + 1) & mask;
        while (values[at] != null) {
            int home = (int) (mix(keys[at]) & mask);
            // Movable when the hole lies between the entry's home and where it
            // actually sits — walking the wrap-around the same way the probe
            // does, so an entry that crossed the end of the array is judged by
            // the same rule as one that did not.
            boolean movable = (at > hole)
                    ? (home <= hole || home > at)
                    : (home <= hole && home > at);
            if (movable) {
                keys[hole] = keys[at];
                values[hole] = values[at];
                values[at] = null;
                hole = at;
            }
            at = (at + 1) & mask;
        }
    }

    /** Every key currently held, for the trim's distance sort. */
    long[] keys() {
        long[] out = new long[count];
        int n = 0;
        for (int i = 0; i < keys.length && n < out.length; i++) {
            if (values[i] != null) out[n++] = keys[i];
        }
        return out;
    }

    void clear() {
        java.util.Arrays.fill(values, null);
        count = 0;
    }

    private void grow() {
        long[] oldKeys = keys;
        SectionMesh[] oldValues = values;
        keys = new long[oldKeys.length * 2];
        values = new SectionMesh[oldValues.length * 2];
        mask = keys.length - 1;
        for (int i = 0; i < oldValues.length; i++) {
            if (oldValues[i] == null) continue;
            int at = (int) (mix(oldKeys[i]) & mask);
            while (values[at] != null) at = (at + 1) & mask;
            keys[at] = oldKeys[i];
            values[at] = oldValues[i];
        }
    }

    /**
     * A spreading hash. The keys pack three coordinates into fixed bit ranges,
     * so their low bits alone are one axis — a table indexed by those would put
     * a whole column of world into one probe chain.
     */
    private static long mix(long key) {
        long h = key * 0x9E3779B97F4A7C15L;
        h ^= h >>> 32;
        return h;
    }
}
