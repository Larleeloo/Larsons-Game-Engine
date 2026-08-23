package com.larsons.engine.gl;

import com.larsons.engine.graphics.chunk.SectionBatch;
import com.larsons.engine.graphics.chunk.SectionMesh;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.opengl.GL33C.*;

/**
 * A block of {@value SectionBatch#SPAN}&sup3; sections sharing one buffer and
 * one position — <b>what makes a ninety-chunk view a few hundred draw calls
 * instead of fifty thousand.</b>
 *
 * <p>See {@link SectionBatch} for the argument and for the packing itself,
 * which lives in the core module because it is arithmetic and arithmetic can be
 * tested without a graphics card. What is here is the GL half: two layers,
 * because a section has two of them, and a {@code glMultiDrawArrays} per layer
 * submitting exactly the ranges belonging to the sections a frame can see.
 *
 * <h2>Rebuilt whole, and lazily</h2>
 *
 * <p>When one section in an arena is remeshed the arena is re-packed and
 * re-uploaded entirely, rather than that section's range being patched in
 * place. Patching is not possible in general — a rebuilt section is a different
 * number of vertices, so everything after it would have to move — and the
 * alternative, a free list with compaction, is a memory allocator inside a
 * renderer.
 *
 * <p>What makes whole-rebuild affordable is that it is <em>deferred</em>: a
 * section arriving marks its arena dirty, and the arena is rebuilt at most once
 * per frame however many of its sixty-four sections landed. During the seconds
 * a world is loading that is exactly the case, so the coalescing is worth more
 * than patching would have been.
 */
final class GlSectionArena implements AutoCloseable {

    /** One render layer's buffer, and where each section sits inside it. */
    private static final class Layer {
        int vao = -1;
        int vbo = -1;
        final int[] first = new int[SectionBatch.SLOTS];
        final int[] count = new int[SectionBatch.SLOTS];

        void close() {
            if (vao >= 0) glDeleteVertexArrays(vao);
            if (vbo >= 0) glDeleteBuffers(vbo);
            vao = vbo = -1;
        }
    }

    private final int arenaX, arenaY, arenaZ;
    private final Layer opaque = new Layer();
    private final Layer translucent = new Layer();

    /** What was uploaded, per slot, so a remesh is noticed and nothing else is. */
    private final SectionMesh[] uploaded = new SectionMesh[SectionBatch.SLOTS];

    private boolean dirty;

    /** The frame this was last drawn in, for eviction. */
    private int lastSeen;

    void seenAt(int frame) { lastSeen = frame; }

    int lastSeen() { return lastSeen; }

    GlSectionArena(int arenaX, int arenaY, int arenaZ) {
        this.arenaX = arenaX;
        this.arenaY = arenaY;
        this.arenaZ = arenaZ;
    }

    int arenaX() { return arenaX; }

    int arenaY() { return arenaY; }

    int arenaZ() { return arenaZ; }

    /**
     * Take note of what a section currently is. Marks the arena for rebuilding
     * if this is not what was uploaded for that slot.
     */
    void offer(int slot, SectionMesh mesh) {
        if (uploaded[slot] == mesh) return;
        uploaded[slot] = mesh;
        dirty = true;
    }

    boolean isDirty() { return dirty; }

    /**
     * Re-pack and re-upload both layers.
     *
     * @param batch a scratch packer, reused across arenas — it holds one
     *              arena's vertices for the moment it takes to hand them to the
     *              driver, and one of those is cheaper than one per arena
     */
    void rebuild(SectionBatch batch, int tileSize) {
        dirty = false;
        double span = SectionMesh.SIZE * (double) tileSize;
        pack(batch, span, true, opaque);
        pack(batch, span, false, translucent);
    }

    private void pack(SectionBatch batch, double span, boolean wantOpaque, Layer layer) {
        batch.reset();
        for (int slot = 0; slot < SectionBatch.SLOTS; slot++) {
            SectionMesh mesh = uploaded[slot];
            if (mesh == null) continue;
            float[] vertices = wantOpaque ? mesh.opaqueVertices() : mesh.translucentVertices();
            int[] colours = wantOpaque ? mesh.opaqueColours() : mesh.translucentColours();
            if (colours.length == 0) continue;
            // Where this section's corner sits relative to the arena's. The
            // coordinates are recovered from the slot rather than stored,
            // because the slot *is* those coordinates — and decoded by
            // SectionBatch's own inverses rather than spelled out again here,
            // so the two cannot drift apart.
            //
            // The world's axes: a section's x is its column, its y its row and
            // its z its height, which is why the height index supplies the last
            // of the three. Same mapping as the model matrix this replaced.
            batch.add(slot, vertices, colours,
                    (float) (SectionBatch.localColumn(slot) * span),
                    (float) (SectionBatch.localRow(slot) * span),
                    (float) (SectionBatch.localHeight(slot) * span));
        }
        for (int slot = 0; slot < SectionBatch.SLOTS; slot++) {
            layer.first[slot] = batch.firstOf(slot);
            layer.count[slot] = batch.countOf(slot);
        }
        if (batch.vertexCount() == 0) {
            // Nothing in this layer. Whatever buffer it had is kept — an arena
            // that empties is usually one about to fill again — and nothing is
            // drawn from it, because every count is now zero.
            return;
        }
        if (layer.vao < 0) layer.vao = glGenVertexArrays();
        if (layer.vbo < 0) layer.vbo = glGenBuffers();
        upload(layer, batch.vertices(), batch.colours(), batch.vertexCount());
    }

    private static void upload(Layer layer, float[] vertices, int[] colours, int count) {
        ByteBuffer data = MemoryUtil.memAlloc(count * SectionMesh.STRIDE_BYTES);
        int callersVao = glGetInteger(GL_VERTEX_ARRAY_BINDING);
        try {
            for (int i = 0; i < count; i++) {
                int at = i * SectionMesh.FLOATS_PER_VERTEX;
                data.putFloat(vertices[at]);
                data.putFloat(vertices[at + 1]);
                data.putFloat(vertices[at + 2]);
                data.putFloat(vertices[at + 3]);
                data.putFloat(vertices[at + 4]);
                int argb = colours[i];
                data.put((byte) ((argb >> 16) & 0xFF));
                data.put((byte) ((argb >> 8) & 0xFF));
                data.put((byte) (argb & 0xFF));
                data.put((byte) ((argb >>> 24) & 0xFF));
            }
            data.flip();
            glBindVertexArray(layer.vao);
            glBindBuffer(GL_ARRAY_BUFFER, layer.vbo);
            glBufferData(GL_ARRAY_BUFFER, data, GL_STATIC_DRAW);
            int stride = SectionMesh.STRIDE_BYTES;
            glEnableVertexAttribArray(GlTerrainProgram.ATTRIB_POSITION);
            glVertexAttribPointer(GlTerrainProgram.ATTRIB_POSITION, 3, GL_FLOAT, false, stride, 0);
            glEnableVertexAttribArray(GlTerrainProgram.ATTRIB_UV);
            glVertexAttribPointer(GlTerrainProgram.ATTRIB_UV, 2, GL_FLOAT, false, stride, 12);
            glEnableVertexAttribArray(GlTerrainProgram.ATTRIB_COLOR);
            glVertexAttribPointer(GlTerrainProgram.ATTRIB_COLOR, 4, GL_UNSIGNED_BYTE, true,
                    stride, 20);
        } finally {
            glBindVertexArray(callersVao);
            MemoryUtil.memFree(data);
        }
    }

    /**
     * Draw the visible slots of one layer in a single call.
     *
     * @param slots     which slots the frame can see
     * @param slotCount how many of {@code slots} are meaningful
     * @param firsts    scratch, holding at least {@link SectionBatch#SLOTS} ints
     * @return whether anything was drawn
     */
    boolean draw(boolean wantOpaque, int[] slots, int slotCount,
                 IntBuffer firsts, IntBuffer counts) {
        Layer layer = wantOpaque ? opaque : translucent;
        if (layer.vao < 0) return false;
        firsts.clear();
        counts.clear();
        for (int i = 0; i < slotCount; i++) {
            int slot = slots[i];
            if (layer.count[slot] == 0) continue;
            firsts.put(layer.first[slot]);
            counts.put(layer.count[slot]);
        }
        if (firsts.position() == 0) return false;
        firsts.flip();
        counts.flip();
        glBindVertexArray(layer.vao);
        glMultiDrawArrays(GL_TRIANGLES, firsts, counts);
        return true;
    }

    @Override
    public void close() {
        opaque.close();
        translucent.close();
    }
}
