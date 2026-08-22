package com.larsons.engine.graphics.chunk;

/**
 * One section of world, as vertices ready to be handed to a GPU.
 *
 * <p><b>The unit the whole GPU path is built out of.</b> Minecraft divides the
 * world into 16&times;16&times;16 sections, meshes each one when it changes,
 * and then spends every frame until the next change simply drawing the result.
 * That is the arrangement here: this is what a build produces, the backend
 * uploads it once, and a frame issues two draw calls against it.
 *
 * <p><b>Two buffers, because the depth buffer solves one problem and not the
 * other.</b> Opaque geometry can be drawn in any order at all once there is a
 * depth test — that is the whole point of having one — so the opaque buffer is
 * drawn with depth writes on and no sorting beyond section order. Anything you
 * can see <em>through</em> still has to be composited back to front, because
 * blending is not commutative, so water and glass go in a second buffer drawn
 * after every opaque section with depth writes off. Minecraft splits its
 * terrain into exactly these render layers and for exactly this reason.
 *
 * <p><b>The vertex format</b> is position, texture coordinate and a packed
 * colour: three floats, two floats, four bytes, {@value #STRIDE_BYTES} bytes in
 * all. The colour carries the face's shade and its per-vertex ambient occlusion
 * multiplied together, which is what makes a cube read as a cube and a corner
 * read as a corner without a single light calculation at draw time.
 *
 * <p>Positions are <b>relative to the section's own corner</b>. A world is
 * sixty-five thousand blocks across and {@code float} has twenty-four bits of
 * mantissa; absolute positions out at the edge would quantise to something
 * coarser than a block face, and the geometry would visibly shear. The
 * translation goes in the matrix, in {@code double}, once per section per
 * frame.
 */
public final class SectionMesh {

    /** Cells per side of a section — Minecraft's, and for the same reasons. */
    public static final int SIZE = 16;

    /** Floats per vertex: {@code x, y, z, u, v}. */
    public static final int FLOATS_PER_VERTEX = 5;

    /** Bytes per vertex: five floats and a packed RGBA. */
    public static final int STRIDE_BYTES = FLOATS_PER_VERTEX * 4 + 4;

    /** Vertices in one quad, as two triangles. */
    public static final int VERTICES_PER_QUAD = 6;

    private final int sectionX, sectionY, sectionZ;
    private final float[] opaque;
    private final int[] opaqueColour;
    private final float[] translucent;
    private final int[] translucentColour;
    private final SectionVisibility visibility;

    SectionMesh(int sectionX, int sectionY, int sectionZ,
                float[] opaque, int[] opaqueColour,
                float[] translucent, int[] translucentColour,
                SectionVisibility visibility) {
        this.sectionX = sectionX;
        this.sectionY = sectionY;
        this.sectionZ = sectionZ;
        this.opaque = opaque;
        this.opaqueColour = opaqueColour;
        this.translucent = translucent;
        this.translucentColour = translucentColour;
        this.visibility = visibility;
    }

    /** An empty section: nothing to draw, and nothing that blocks sight. */
    public static SectionMesh empty(int sx, int sy, int sz) {
        return new SectionMesh(sx, sy, sz, new float[0], new int[0], new float[0], new int[0],
                SectionVisibility.OPEN);
    }

    public int sectionX() { return sectionX; }

    public int sectionY() { return sectionY; }

    public int sectionZ() { return sectionZ; }

    /** Opaque vertices, {@link #FLOATS_PER_VERTEX} floats each. */
    public float[] opaqueVertices() { return opaque; }

    /** One packed {@code 0xAARRGGBB} per opaque vertex. */
    public int[] opaqueColours() { return opaqueColour; }

    /** Vertices you can see through, drawn after everything opaque. */
    public float[] translucentVertices() { return translucent; }

    /** One packed {@code 0xAARRGGBB} per translucent vertex. */
    public int[] translucentColours() { return translucentColour; }

    public int opaqueVertexCount() { return opaqueColour.length; }

    public int translucentVertexCount() { return translucentColour.length; }

    /** Whether there is anything at all in this section to draw. */
    public boolean isEmpty() {
        return opaqueColour.length == 0 && translucentColour.length == 0;
    }

    /** Which faces of this section can see which — see {@link SectionVisibility}. */
    public SectionVisibility visibility() { return visibility; }
}
