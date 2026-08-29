package com.larsons.engine.watch.render;

/**
 * A piece of the world as triangles, ready to be handed to a GPU or walked by
 * the CPU painter.
 *
 * <p><b>The vertex format is deliberately the block renderer's.</b> Three
 * position floats, two texture floats and a packed {@code 0xAARRGGBB} per
 * vertex — the same layout
 * {@link com.larsons.engine.graphics.chunk.SectionMesh} uses, for the same
 * reasons and with the same consequences: a GL backend can upload one of these
 * with the shader and the buffer layout it already has, and anybody who has
 * read {@code graphics/chunk} already knows what is in here.
 *
 * <p>Positions are <b>relative to {@link #originX()}</b>. A world with no edge
 * puts a walker tens of thousands of metres from the origin sooner or later,
 * and {@code float} has twenty-four bits of mantissa — absolute positions out
 * there would quantise to something coarser than a footstep and the ground
 * would visibly shear. The translation stays in {@code double}, in the camera,
 * once per mesh per frame.
 *
 * <p>Triangles rather than quads, and no index buffer. Low-poly terrain shades
 * <em>flat</em> — one normal per triangle, not one per vertex — so the two
 * triangles of a quad do not share a colour and could not share a vertex
 * anyway. Indexing would save nothing and cost a lookup.
 */
public final class Mesh {

    /** Floats per vertex: {@code x, y, z, u, v}. */
    public static final int FLOATS_PER_VERTEX = 5;

    /** Vertices in a triangle. */
    public static final int VERTICES_PER_TRIANGLE = 3;

    private final float[] vertices;
    private final int[] colours;
    private final int vertexCount;
    private final double originX, originY, originZ;
    private final boolean translucent;
    private final float minX, minY, minZ, maxX, maxY, maxZ;
    private final int revision;
    private final double sortBias;

    private Mesh(Builder b) {
        this.vertexCount = b.count;
        this.vertices = java.util.Arrays.copyOf(b.vertices, b.count * FLOATS_PER_VERTEX);
        this.colours = java.util.Arrays.copyOf(b.colours, b.count);
        this.originX = b.originX;
        this.originY = b.originY;
        this.originZ = b.originZ;
        this.translucent = b.translucent;
        this.minX = b.minX; this.minY = b.minY; this.minZ = b.minZ;
        this.maxX = b.maxX; this.maxY = b.maxY; this.maxZ = b.maxZ;
        this.revision = b.revision;
        this.sortBias = b.sortBias;
    }

    /** A mesh with nothing in it, at an origin. Never uploaded, never drawn. */
    public static Mesh empty(double ox, double oy, double oz) {
        return new Builder(ox, oy, oz, false, 0).build();
    }

    /** Start building a mesh whose positions are relative to {@code (ox, oy, oz)}. */
    public static Builder builder(double ox, double oy, double oz, boolean translucent,
                                  int revision) {
        return new Builder(ox, oy, oz, translucent, revision);
    }

    /** {@link #FLOATS_PER_VERTEX} floats per vertex, {@link #vertexCount()} of them. */
    public float[] vertices() { return vertices; }

    /** One packed {@code 0xAARRGGBB} per vertex. */
    public int[] colours() { return colours; }

    public int vertexCount() { return vertexCount; }

    /** Triangles in this mesh. */
    public int triangleCount() { return vertexCount / VERTICES_PER_TRIANGLE; }

    public boolean isEmpty() { return vertexCount == 0; }

    public double originX() { return originX; }

    public double originY() { return originY; }

    public double originZ() { return originZ; }

    /** Whether this has to be drawn after everything opaque, back to front. */
    public boolean translucent() { return translucent; }

    /**
     * How far toward the eye this mesh's triangles are <em>sorted</em>, in
     * metres — <b>a decal bias, and it moves nothing.</b>
     *
     * <p>Only the painter path reads it, and only for the depth key it sorts
     * on: the geometry, the fog and the culling all use the true depth. It
     * exists because a painter's algorithm has no way to say "this lies on
     * that". A track quad a hand's breadth above the ground is inside a terrain
     * triangle two metres across, and the triangle is sorted by the depth of
     * its <em>middle</em> — so a decal on the far half of one sorts behind it
     * and is painted over, and a trail across open ground comes out as dashes
     * on the grid the ground happens to be meshed at.
     *
     * <p>The bias is therefore about half a ground quad, which is what it takes
     * to put a decal reliably in front of whatever it is lying on. The cost is
     * paid in the same currency: something standing on the ground <em>nearer</em>
     * than the decal by less than the bias — a blade of grass beside the path —
     * is painted under it rather than over it. At the alpha a track is drawn
     * with that is a faint tint on a few blades, which is a much smaller lie
     * than half the trail being missing.
     *
     * <p>A card needs none of this: the depth buffer compares fragments rather
     * than centroids, and {@code GlMeshPass} draws the translucent layer after
     * the opaque one with depth writes off. See {@code WatchRenderer.triangle}.
     */
    public double sortBias() { return sortBias; }

    /**
     * Which build of its source this mesh is. A backend that has uploaded a
     * mesh compares this rather than the arrays, so a chunk that has been
     * rebuilt is re-uploaded and one that has not is left alone.
     */
    public int revision() { return revision; }

    public float minX() { return minX; }

    public float minY() { return minY; }

    public float minZ() { return minZ; }

    public float maxX() { return maxX; }

    public float maxY() { return maxY; }

    public float maxZ() { return maxZ; }

    /** Roughly what this weighs on the heap, in bytes. */
    public int byteCount() { return vertices.length * 4 + colours.length * 4; }

    /**
     * This mesh as something a GPU backend can draw.
     *
     * <p>A conversion with no copying in it: the arrays go across as they are,
     * because the vertex format was chosen to be the one the backend already
     * uploads. The {@code key} is the caller's stable identity for whatever
     * produced this mesh — a chunk's coordinates, an animal's id — and is what
     * lets the backend keep one buffer per source and re-upload only when
     * {@link #revision()} changes.
     */
    public com.larsons.engine.graphics.MeshPass.Draw toDraw(long key) {
        return new com.larsons.engine.graphics.MeshPass.Draw(key, revision, vertices,
                colours, vertexCount, originX, originY, originZ, translucent);
    }

    /**
     * Accumulates triangles into growable arrays, then freezes them.
     *
     * <p>Not thread-safe and not meant to be: one of these is built by one
     * worker, handed over finished, and never touched again.
     */
    public static final class Builder {
        private float[] vertices = new float[FLOATS_PER_VERTEX * 512];
        private int[] colours = new int[512];
        private int count;
        private final double originX, originY, originZ;
        private final boolean translucent;
        private final int revision;
        private double sortBias;
        private float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        private float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;

        private Builder(double ox, double oy, double oz, boolean translucent, int revision) {
            this.originX = ox;
            this.originY = oy;
            this.originZ = oz;
            this.translucent = translucent;
            this.revision = revision;
        }

        /** Sort this mesh's triangles as if they were nearer. See {@link #sortBias()}. */
        public Builder sortBias(double metres) {
            this.sortBias = Math.max(0, metres);
            return this;
        }

        /** How many triangles have been added so far. */
        public int triangleCount() { return count / VERTICES_PER_TRIANGLE; }

        /**
         * One triangle, wound counter-clockwise seen from the side it faces.
         *
         * <p>All three vertices take the same colour, because this mesh is flat
         * shaded: the caller has already worked out the face's normal and what
         * the light does to it, and asking for three copies of one number is
         * cheaper than asking the renderer to interpolate one.
         */
        public Builder triangle(float ax, float ay, float az,
                                float bx, float by, float bz,
                                float cx, float cy, float cz,
                                float u, float v, int argb) {
            ensure(3);
            vertex(ax, ay, az, u, v, argb);
            vertex(bx, by, bz, u, v, argb);
            vertex(cx, cy, cz, u, v, argb);
            return this;
        }

        /**
         * One triangle with its own texture coordinate per vertex — what the
         * terrain uses, so a tile is stretched across the quad rather than
         * repeated flat across each half of it.
         */
        public Builder triangle(float ax, float ay, float az, float au, float av,
                                float bx, float by, float bz, float bu, float bv,
                                float cx, float cy, float cz, float cu, float cv,
                                int argb) {
            ensure(3);
            vertex(ax, ay, az, au, av, argb);
            vertex(bx, by, bz, bu, bv, argb);
            vertex(cx, cy, cz, cu, cv, argb);
            return this;
        }

        /**
         * A quad as two triangles, wound {@code a → b → c → d} around its
         * edge. The corners take the four corners of the material's tile.
         *
         * @param uv {@code u0, v0, u1, v1} — see {@link
         *           com.larsons.engine.watch.world.WatchMaterials#uv}
         */
        public Builder quad(float ax, float ay, float az,
                            float bx, float by, float bz,
                            float cx, float cy, float cz,
                            float dx, float dy, float dz,
                            float[] uv, int argb) {
            triangle(ax, ay, az, uv[0], uv[3],
                    bx, by, bz, uv[2], uv[3],
                    cx, cy, cz, uv[2], uv[1], argb);
            triangle(ax, ay, az, uv[0], uv[3],
                    cx, cy, cz, uv[2], uv[1],
                    dx, dy, dz, uv[0], uv[1], argb);
            return this;
        }

        private void vertex(float x, float y, float z, float u, float v, int argb) {
            int at = count * FLOATS_PER_VERTEX;
            vertices[at] = x;
            vertices[at + 1] = y;
            vertices[at + 2] = z;
            vertices[at + 3] = u;
            vertices[at + 4] = v;
            colours[count] = argb;
            count++;
            if (x < minX) minX = x;
            if (y < minY) minY = y;
            if (z < minZ) minZ = z;
            if (x > maxX) maxX = x;
            if (y > maxY) maxY = y;
            if (z > maxZ) maxZ = z;
        }

        private void ensure(int more) {
            if (count + more <= colours.length) return;
            int wanted = Math.max(colours.length * 2, count + more);
            vertices = java.util.Arrays.copyOf(vertices, wanted * FLOATS_PER_VERTEX);
            colours = java.util.Arrays.copyOf(colours, wanted);
        }

        /** Freeze what has been added into an immutable mesh. */
        public Mesh build() {
            if (count == 0) {
                minX = minY = minZ = 0;
                maxX = maxY = maxZ = 0;
            }
            return new Mesh(this);
        }
    }
}
