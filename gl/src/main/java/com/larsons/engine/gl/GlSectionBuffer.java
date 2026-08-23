package com.larsons.engine.gl;

import com.larsons.engine.graphics.chunk.SectionMesh;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

import static org.lwjgl.opengl.GL33C.*;

/**
 * One section's triangles, living on the GPU.
 *
 * <p><b>Uploaded once, drawn every frame.</b> That sentence is the entire
 * reason the GPU path exists: the painter's algorithm has to sort geometry
 * against the camera and so cannot keep any, while a depth test removes the
 * ordering requirement and lets a section's vertices sit in video memory until
 * somebody mines a block. A frame then costs one {@code glDrawArrays} per
 * visible section rather than a projection per face.
 *
 * <p><b>A vertex array object each</b>, rather than one shared and re-pointed
 * per section. Re-specifying five attribute pointers per draw is more driver
 * work than binding a VAO, and a VAO is a few dozen bytes; at a thousand
 * sections that is a rounding error against the megabytes of vertices they
 * describe.
 *
 * <p>Two buffers, because a section has two render layers — see
 * {@link SectionMesh}. Either may be empty, and an empty one holds no GL object
 * at all.
 */
final class GlSectionBuffer implements AutoCloseable {

    private int opaqueVao = -1, opaqueVbo = -1, opaqueCount;
    private int clearVao = -1, clearVbo = -1, clearCount;

    /** Which build of the section this holds, so a rebuild is noticed. */
    private Object built;

    /** Whether this holds {@code mesh} already. */
    boolean holds(SectionMesh mesh) {
        return built == mesh;
    }

    int opaqueVertexCount() { return opaqueCount; }

    int translucentVertexCount() { return clearCount; }

    /**
     * Put a mesh on the GPU, replacing whatever was here.
     *
     * <p>The whole buffer is re-specified with {@code glBufferData} rather than
     * updated in place: a rebuilt section is a different number of vertices, so
     * there is nothing to update in place, and orphaning the old storage this
     * way is the documented signal to the driver that it may hand back fresh
     * memory instead of waiting for the frame still reading the old.
     */
    void upload(SectionMesh mesh) {
        opaqueCount = mesh.opaqueVertexCount();
        clearCount = mesh.translucentVertexCount();
        if (opaqueCount > 0) {
            if (opaqueVao < 0) {
                opaqueVao = glGenVertexArrays();
                opaqueVbo = glGenBuffers();
            }
            write(opaqueVao, opaqueVbo, mesh.opaqueVertices(), mesh.opaqueColours());
        }
        if (clearCount > 0) {
            if (clearVao < 0) {
                clearVao = glGenVertexArrays();
                clearVbo = glGenBuffers();
            }
            write(clearVao, clearVbo, mesh.translucentVertices(), mesh.translucentColours());
        }
        built = mesh;
    }

    private static void write(int vao, int vbo, float[] vertices, int[] colours) {
        int count = colours.length;
        // Whoever's vertex array was current stays current on the way out.
        // Binding zero instead would look harmless and is not: this engine keeps
        // its 2D attribute pointers in one long-lived VAO (GlContext), and a
        // core-profile draw with none bound draws nothing at all.
        int callersVao = glGetInteger(GL_VERTEX_ARRAY_BINDING);
        ByteBuffer data = MemoryUtil.memAlloc(count * SectionMesh.STRIDE_BYTES);
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
            glBindVertexArray(vao);
            glBindBuffer(GL_ARRAY_BUFFER, vbo);
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

    /** Draw the opaque half; returns whether anything was drawn. */
    boolean drawOpaque() {
        if (opaqueCount == 0) return false;
        glBindVertexArray(opaqueVao);
        glDrawArrays(GL_TRIANGLES, 0, opaqueCount);
        return true;
    }

    /** Draw the see-through half; returns whether anything was drawn. */
    boolean drawTranslucent() {
        if (clearCount == 0) return false;
        glBindVertexArray(clearVao);
        glDrawArrays(GL_TRIANGLES, 0, clearCount);
        return true;
    }

    @Override
    public void close() {
        if (opaqueVao >= 0) {
            glDeleteVertexArrays(opaqueVao);
            glDeleteBuffers(opaqueVbo);
            opaqueVao = opaqueVbo = -1;
        }
        if (clearVao >= 0) {
            glDeleteVertexArrays(clearVao);
            glDeleteBuffers(clearVbo);
            clearVao = clearVbo = -1;
        }
        opaqueCount = clearCount = 0;
        built = null;
    }
}
