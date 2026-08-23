package com.larsons.engine.graphics.chunk;

/**
 * Many sections' triangles concatenated into one buffer — <b>the arrangement
 * that turns fifty thousand draw calls into eight hundred.</b>
 *
 * <h2>Why the draw count is the wall</h2>
 *
 * <p>A section is drawn with its own {@code glDrawArrays} because it has its
 * own position, and a position is a uniform, and a uniform change ends a draw.
 * At twenty chunks that is a few thousand calls a frame and nobody notices; at
 * ninety it is around <b>fifty-two thousand</b>, and a driver spends longer
 * writing down that many commands than a card spends executing them. No amount
 * of meshing, culling or memory fixes it: the geometry is already minimal and
 * already cached, and the cost is in the asking.
 *
 * <h2>What this does instead</h2>
 *
 * <p>Sections are grouped into <b>arenas</b> of {@value #SPAN}&sup3; and the
 * whole arena shares one buffer and one position. A section's vertices are
 * written with its offset <em>within the arena</em> already added, so the
 * uniform is the arena's corner and is set once for all sixty-four of them.
 * Each section keeps its own range in the buffer — a first vertex and a count —
 * and a frame draws whichever ranges it can see with a single
 * {@code glMultiDrawArrays}.
 *
 * <p><b>Per-section culling survives intact</b>, which is the point of ranges
 * rather than one draw per arena: the frustum test and the cave walk still
 * decide section by section, and an arena with three visible sections submits
 * three ranges rather than sixty-four.
 *
 * <h2>Why the packing lives here and not in the GL module</h2>
 *
 * <p>Because it is arithmetic, and arithmetic can be tested on a machine with
 * no graphics card. Getting a first-vertex wrong by one produces a section
 * drawn with its neighbour's triangles — a hole in the world beside a smear of
 * misplaced ground — and that is a bug to catch in a unit test rather than by
 * looking at it. What is left for the backend is uploading these two arrays and
 * issuing the call.
 */
public final class SectionBatch {

    /** Sections along one edge of an arena; {@value} cubed is its capacity. */
    public static final int SPAN = 4;

    /** Sections in an arena. */
    public static final int SLOTS = SPAN * SPAN * SPAN;

    /** Which arena a section belongs to, packed as {@link TerrainSections#key}. */
    public static long arenaOf(int sx, int sy, int sz) {
        return TerrainSections.key(
                Math.floorDiv(sx, SPAN), Math.floorDiv(sy, SPAN), Math.floorDiv(sz, SPAN));
    }

    /** Where in its arena a section sits, {@code 0..}{@value #SLOTS}{@code -1}. */
    public static int slotOf(int sx, int sy, int sz) {
        int x = Math.floorMod(sx, SPAN);
        int y = Math.floorMod(sy, SPAN);
        int z = Math.floorMod(sz, SPAN);
        return (y * SPAN + z) * SPAN + x;
    }

    // The three inverses of slotOf. They live here rather than being written
    // out at the one place that needs them, because a slot decoded differently
    // from the way it was encoded puts a section's triangles at another
    // section's coordinates — geometry in the wrong place, drawn confidently,
    // with nothing to say which of the two spellings was wrong.

    /** The section's column offset within its arena. */
    public static int localColumn(int slot) { return slot % SPAN; }

    /** Its row offset. */
    public static int localRow(int slot) { return (slot / SPAN) % SPAN; }

    /** Its height offset. */
    public static int localHeight(int slot) { return slot / (SPAN * SPAN); }

    private float[] data = new float[SectionMesh.FLOATS_PER_VERTEX * 4096];
    private int[] argb = new int[4096];
    private int count;

    private final int[] first = new int[SLOTS];
    private final int[] length = new int[SLOTS];

    /** Empty it, keeping the arrays for the next arena. */
    public void reset() {
        count = 0;
        java.util.Arrays.fill(first, 0);
        java.util.Arrays.fill(length, 0);
    }

    /**
     * Append one section's vertices, shifted into the arena's frame.
     *
     * @param slot     where the section sits in the arena ({@link #slotOf})
     * @param vertices {@link SectionMesh#FLOATS_PER_VERTEX} floats each, relative
     *                 to the section's own corner
     * @param colours  one per vertex
     * @param dx       the section's corner relative to the arena's, in world units
     */
    public void add(int slot, float[] vertices, int[] colours, float dx, float dy, float dz) {
        int vertexCount = colours.length;
        if (vertexCount == 0) return;
        int stride = SectionMesh.FLOATS_PER_VERTEX;
        ensure(count + vertexCount);
        first[slot] = count;
        length[slot] = vertexCount;
        for (int i = 0; i < vertexCount; i++) {
            int from = i * stride;
            int to = (count + i) * stride;
            data[to] = vertices[from] + dx;
            data[to + 1] = vertices[from + 1] + dy;
            data[to + 2] = vertices[from + 2] + dz;
            data[to + 3] = vertices[from + 3];
            data[to + 4] = vertices[from + 4];
            argb[count + i] = colours[i];
        }
        count += vertexCount;
    }

    private void ensure(int vertices) {
        if (vertices <= argb.length) return;
        int want = Math.max(vertices, argb.length * 2);
        data = java.util.Arrays.copyOf(data, want * SectionMesh.FLOATS_PER_VERTEX);
        argb = java.util.Arrays.copyOf(argb, want);
    }

    /** Vertices packed so far; only the first {@link #vertexCount} are meaningful. */
    public float[] vertices() { return data; }

    /** Colours packed so far, one per vertex. */
    public int[] colours() { return argb; }

    /** How many vertices have been packed. */
    public int vertexCount() { return count; }

    /** Where a slot's vertices start, for {@code glMultiDrawArrays}. */
    public int firstOf(int slot) { return first[slot]; }

    /** How many vertices a slot contributed; {@code 0} when it contributed none. */
    public int countOf(int slot) { return length[slot]; }
}
