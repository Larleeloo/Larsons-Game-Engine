package com.larsons.engine.watch.world;

import com.larsons.engine.watch.render.Mesh;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One tile of the world: a square of ground, everything growing on it, and the
 * triangles that draw it.
 *
 * <h2>What is in one</h2>
 *
 * <p>{@value #SIZE} metres square, sampled every {@value #STEP} metres — so
 * {@value #SAMPLES} readings along each edge, <b>including both edges</b>. The
 * duplicated row and column are the point: a chunk's east edge is sampled at
 * exactly the coordinates its neighbour's west edge is, so the two meshes share
 * their vertices' positions to the last bit and there is no seam between them.
 * A generator that sampled 32 columns per 32-metre chunk would leave a hairline
 * crack down every chunk border, visible as a flicker of sky, and it is not
 * fixable afterwards.
 *
 * <h2>Levels of detail</h2>
 *
 * <p>The heightfield is always sampled at {@value #STEP} metres; what a
 * {@linkplain #lod() level of detail} changes is the <em>stride</em> the mesher
 * walks it with. LOD 0 uses every sample, LOD 1 every second, LOD 2 every
 * fourth, LOD 3 every eighth — 512, 128, 32 and 8 triangles respectively. The
 * data is the same in all four cases, so a chunk that moves closer is re-meshed
 * without being re-generated, which is the expensive half.
 *
 * <h2>Threading</h2>
 *
 * <p>A chunk is built on a worker, finished, and only then published to the
 * frame thread ({@link ChunkStreamer}); nothing mutates it afterwards except
 * {@link #setMeshes}, which the same worker calls before publication. The
 * fields the frame thread reads are final or written once, which is what makes
 * the handover safe without a lock on the drawing path.
 */
public final class WatchChunk implements Flora.Ground {

    /** A chunk's edge, in metres. */
    public static final int SIZE = 32;

    /** Metres between height samples — the finest the ground is ever drawn. */
    public static final int STEP = 2;

    /** Height samples along one edge, both edges included. */
    public static final int SAMPLES = SIZE / STEP + 1;

    /** The coarsest level of detail; {@code 1 << MAX_LOD} is its stride. */
    public static final int MAX_LOD = 3;

    private final int cx, cy;
    private final long revision;
    private final float[] heights;
    private final byte[] surfaces;
    private final byte[] biomes;
    private final float[] water;
    private final WatchBiome dominant;
    private final List<TreeInstance> trees;
    private final List<Flora.Bush> bushes;
    private final List<Flora.Rock> rocks;
    private final boolean anyWater;
    private final float lowest, highest;

    private int lod = MAX_LOD;
    private Mesh ground = Mesh.empty(0, 0, 0);
    private Mesh surf = Mesh.empty(0, 0, 0);
    private Mesh flora = Mesh.empty(0, 0, 0);
    private Mesh grass = Mesh.empty(0, 0, 0);

    private WatchChunk(int cx, int cy, long revision, float[] heights, byte[] surfaces,
                       byte[] biomes, float[] water, WatchBiome dominant,
                       List<TreeInstance> trees, List<Flora.Bush> bushes,
                       List<Flora.Rock> rocks, boolean anyWater,
                       float lowest, float highest) {
        this.cx = cx;
        this.cy = cy;
        this.revision = revision;
        this.heights = heights;
        this.surfaces = surfaces;
        this.biomes = biomes;
        this.water = water;
        this.dominant = dominant;
        this.trees = trees;
        this.bushes = bushes;
        this.rocks = rocks;
        this.anyWater = anyWater;
        this.lowest = lowest;
        this.highest = highest;
    }

    /**
     * Generate the chunk at {@code (cx, cy)} — the whole of the expensive part,
     * and the part that runs on a worker.
     *
     * @param revision a counter the meshes carry, so a backend can tell an
     *                 uploaded mesh from a rebuilt one
     */
    public static WatchChunk generate(TerrainField field, Flora flora, int cx, int cy,
                                      long revision) {
        int n = SAMPLES;
        float[] heights = new float[n * n];
        byte[] surfaces = new byte[n * n];
        byte[] biomes = new byte[n * n];
        float[] water = new float[n * n];
        double[] climate = new double[3];
        List<WatchBiome> all = WatchBiomes.all();
        int[] tally = new int[all.size()];
        float lowest = Float.MAX_VALUE, highest = -Float.MAX_VALUE;
        boolean anyWater = false;

        double ox = cx * (double) SIZE;
        double oy = cy * (double) SIZE;

        // Pass one: heights and biomes. The surface needs a slope, and a slope
        // needs its neighbours, so materials wait for pass two.
        for (int iy = 0; iy < n; iy++) {
            for (int ix = 0; ix < n; ix++) {
                double wx = ox + ix * STEP;
                double wy = oy + iy * STEP;
                float h = (float) field.heightAt(wx, wy);
                int at = iy * n + ix;
                heights[at] = h;
                field.climateAt(wx, wy, climate);
                int index = WatchBiomes.bestFitIndex(climate[0], climate[1], climate[2]);
                biomes[at] = (byte) index;
                tally[index]++;
                float depth = (float) field.waterDepth(h);
                water[at] = depth;
                if (depth > 0) anyWater = true;
                if (h < lowest) lowest = h;
                if (h > highest) highest = h;
            }
        }

        // Pass two: materials, now that a slope can be read off the field.
        for (int iy = 0; iy < n; iy++) {
            for (int ix = 0; ix < n; ix++) {
                int at = iy * n + ix;
                double wx = ox + ix * STEP;
                double wy = oy + iy * STEP;
                double slope = slopeOf(heights, ix, iy);
                WatchBiome biome = all.get(biomes[at] & 0xFF);
                surfaces[at] = (byte) field.surfaceAt(wx, wy, heights[at], slope, biome)
                        .ordinal();
            }
        }

        WatchBiome dominant = all.get(argmax(tally));

        WatchChunk chunk = new WatchChunk(cx, cy, revision, heights, surfaces, biomes,
                water, dominant, new ArrayList<>(), new ArrayList<>(), new ArrayList<>(),
                anyWater, lowest, highest);
        if (flora != null) flora.scatter(chunk);
        return chunk;
    }

    /** The slope at a sample, as metres of rise per metre across. */
    private static double slopeOf(float[] heights, int ix, int iy) {
        int n = SAMPLES;
        int west = Math.max(0, ix - 1), east = Math.min(n - 1, ix + 1);
        int north = Math.max(0, iy - 1), south = Math.min(n - 1, iy + 1);
        double dx = (heights[iy * n + east] - heights[iy * n + west])
                / ((east - west) * (double) STEP);
        double dy = (heights[south * n + ix] - heights[north * n + ix])
                / ((south - north) * (double) STEP);
        return Math.sqrt(dx * dx + dy * dy);
    }

    private static int argmax(int[] counts) {
        int best = 0;
        for (int i = 1; i < counts.length; i++) {
            if (counts[i] > counts[best]) best = i;
        }
        return best;
    }

    // --- where it is ---------------------------------------------------------------

    public int chunkX() { return cx; }

    public int chunkY() { return cy; }

    /** The west edge of this chunk, in world metres. */
    public double originX() { return cx * (double) SIZE; }

    /** The north edge of this chunk, in world metres. */
    public double originY() { return cy * (double) SIZE; }

    /** Which build of the world this is; carried into the meshes. */
    public long revision() { return revision; }

    /** The lowest ground in this chunk, for the frustum box. */
    public float lowest() { return lowest; }

    /** The highest — plus whatever grows on it; see {@link #ceiling()}. */
    public float highest() { return highest; }

    /**
     * How high anything in this chunk reaches, trees included. What a culling
     * box has to use: a redwood is fifty metres taller than the ground it
     * stands on, and a box drawn to the ground's height culls the tree the
     * moment the camera looks up.
     */
    public float ceiling() {
        float top = highest;
        for (TreeInstance t : trees) {
            top = Math.max(top, (float) (t.z() + t.height() * 1.4));
        }
        return top;
    }

    /** The chunk key {@code (cx, cy)} packs into. */
    public long key() { return key(cx, cy); }

    /** How a chunk coordinate pair is packed for the streamer's maps. */
    public static long key(int cx, int cy) {
        return ((long) cx << 32) ^ (cy & 0xFFFFFFFFL);
    }

    /** The chunk coordinate a world metre falls in. */
    public static int chunkOf(double world) {
        return Math.floorDiv((int) Math.floor(world), SIZE);
    }

    // --- what is in it -------------------------------------------------------------

    /** The height sample at a grid position. */
    public float heightAt(int ix, int iy) { return heights[iy * SAMPLES + ix]; }

    /** The whole heightfield, {@link #SAMPLES}² of it, row-major. */
    public float[] heights() { return heights; }

    /** The material of the ground at a grid position. */
    public WatchMaterial surfaceAt(int ix, int iy) {
        return WatchMaterial.values()[surfaces[iy * SAMPLES + ix] & 0xFF];
    }

    /** The biome at a grid position. */
    public WatchBiome biomeAt(int ix, int iy) {
        return WatchBiomes.all().get(biomes[iy * SAMPLES + ix] & 0xFF);
    }

    /** How deep the water is over a grid position; {@code 0} on dry land. */
    public float waterAt(int ix, int iy) { return water[iy * SAMPLES + ix]; }

    /** Whether any of this chunk is under water. */
    public boolean anyWater() { return anyWater; }

    /** The biome most of this chunk belongs to — what the sky and fog read. */
    public WatchBiome dominantBiome() { return dominant; }

    /**
     * The ground's height at a world position inside this chunk, interpolated
     * between the four samples around it.
     *
     * <p>What everything that walks on the ground stands on. Bilinear rather
     * than nearest, because a player climbing a hill on a two-metre grid would
     * otherwise go up it in two-metre steps.
     */
    public double groundAt(double worldX, double worldY) {
        double lx = (worldX - originX()) / STEP;
        double ly = (worldY - originY()) / STEP;
        int ix = (int) Math.floor(lx), iy = (int) Math.floor(ly);
        ix = Math.max(0, Math.min(SAMPLES - 2, ix));
        iy = Math.max(0, Math.min(SAMPLES - 2, iy));
        double fx = Math.max(0, Math.min(1, lx - ix));
        double fy = Math.max(0, Math.min(1, ly - iy));
        double h00 = heightAt(ix, iy), h10 = heightAt(ix + 1, iy);
        double h01 = heightAt(ix, iy + 1), h11 = heightAt(ix + 1, iy + 1);
        double top = h00 + (h10 - h00) * fx;
        double bottom = h01 + (h11 - h01) * fx;
        return top + (bottom - top) * fy;
    }

    /**
     * The ground's height at a world position — {@link Flora.Ground}'s name for
     * {@link #groundAt}, so a chunk can be handed to the flora scatterer
     * directly.
     */
    @Override public double heightAt(double worldX, double worldY) {
        return groundAt(worldX, worldY);
    }

    /** {@link Flora.Ground}'s name for {@link #biomeAtWorld}. */
    @Override public WatchBiome biomeAt(double worldX, double worldY) {
        return biomeAtWorld(worldX, worldY);
    }

    /** {@link Flora.Ground}'s name for {@link #slopeAtWorld}. */
    @Override public double slopeAt(double worldX, double worldY) {
        return slopeAtWorld(worldX, worldY);
    }

    /** The biome at a world position inside this chunk. */
    public WatchBiome biomeAtWorld(double worldX, double worldY) {
        int ix = clampIndex((worldX - originX()) / STEP);
        int iy = clampIndex((worldY - originY()) / STEP);
        return biomeAt(ix, iy);
    }

    /** The material at a world position inside this chunk. */
    public WatchMaterial surfaceAtWorld(double worldX, double worldY) {
        int ix = clampIndex((worldX - originX()) / STEP);
        int iy = clampIndex((worldY - originY()) / STEP);
        return surfaceAt(ix, iy);
    }

    /** The slope at a world position inside this chunk. */
    public double slopeAtWorld(double worldX, double worldY) {
        int ix = clampIndex((worldX - originX()) / STEP);
        int iy = clampIndex((worldY - originY()) / STEP);
        return slopeOf(heights, ix, iy);
    }

    private static int clampIndex(double local) {
        return Math.max(0, Math.min(SAMPLES - 1, (int) Math.round(local)));
    }

    /** The wild trees standing in this chunk. */
    public List<TreeInstance> trees() { return Collections.unmodifiableList(trees); }

    /** The berry bushes and shrubs in it. */
    public List<Flora.Bush> bushes() { return Collections.unmodifiableList(bushes); }

    /** The boulders and outcrops in it. */
    public List<Flora.Rock> rocks() { return Collections.unmodifiableList(rocks); }

    /** Called by {@link Flora} during generation, before the chunk is published. */
    void addTree(TreeInstance tree) { trees.add(tree); }

    void addBush(Flora.Bush bush) { bushes.add(bush); }

    void addRock(Flora.Rock rock) { rocks.add(rock); }

    // --- meshes ---------------------------------------------------------------------

    /** How finely this chunk is currently meshed; {@code 0} is finest. */
    public int lod() { return lod; }

    /** The ground. */
    public Mesh groundMesh() { return ground; }

    /** Still water, drawn after everything opaque. */
    public Mesh waterMesh() { return surf; }

    /** Trunks, crowns, bushes and boulders. */
    public Mesh floraMesh() { return flora; }

    /** Individual blades; only ever built at LOD 0. */
    public Mesh grassMesh() { return grass; }

    /** Whether this chunk has been meshed at all yet. */
    public boolean meshed() { return !ground.isEmpty() || !flora.isEmpty(); }

    /** Hand over a freshly built set of meshes. Called on the building worker. */
    public void setMeshes(int lod, Mesh ground, Mesh water, Mesh flora, Mesh grass) {
        this.lod = lod;
        this.ground = ground;
        this.surf = water;
        this.flora = flora;
        this.grass = grass;
    }

    /** Roughly what this chunk weighs on the heap, in bytes. */
    public int byteCount() {
        return heights.length * 4 + surfaces.length + biomes.length + water.length * 4
                + ground.byteCount() + surf.byteCount() + flora.byteCount()
                + grass.byteCount();
    }

    @Override public String toString() {
        return "chunk(" + cx + ", " + cy + ") " + dominant.displayName()
                + " lod " + lod + ", " + trees.size() + " trees";
    }
}
