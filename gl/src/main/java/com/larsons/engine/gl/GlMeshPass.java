package com.larsons.engine.gl;

import com.larsons.engine.graphics.EyeCamera;
import com.larsons.engine.graphics.Mat4;
import com.larsons.engine.graphics.MeshPass;
import org.lwjgl.system.MemoryUtil;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.lwjgl.opengl.GL33C.*;

/**
 * Arbitrary triangle meshes, on the card — <b>the GPU half of the low-poly
 * world.</b>
 *
 * <p>{@link GlTerrainPass} draws a voxel world out of 16³ sections. This draws
 * whatever it is given: a chunk of heightfield, a forest, a herd of animals, a
 * treehouse. Everything expensive — generating, meshing, culling — has already
 * happened on the CPU in {@code com.larsons.engine.watch}, on worker threads,
 * in code with no GL in it at all. What is left here is the two things a
 * backend is actually for.
 *
 * <h2>Upload once, draw many</h2>
 *
 * <p>A mesh arrives with a {@code key} (which chunk, which animal) and a
 * {@code revision} (which build of it). A buffer is kept per key and re-uploaded
 * only when the revision changes, so a chunk that is not being re-meshed costs
 * one {@code glDrawArrays} a frame and nothing else. Uploads are bounded per
 * frame for the reason the block path bounds its own: walking into fresh
 * terrain dirties dozens at once, and a frame that re-specified every buffer
 * would be the stutter this whole arrangement exists to remove.
 *
 * <h2>Two passes, for the usual reason</h2>
 *
 * <p>Opaque first, depth writes on, in whatever order the caller sent — a depth
 * test makes opaque geometry order-independent, which is the whole point of
 * having one. Then anything see-through, sorted <em>far to near</em> with depth
 * writes off, because blending is not commutative and water seen through water
 * is still water.
 *
 * <h2>It borrows somebody else's context</h2>
 *
 * <p>Exactly like {@link GlTerrainPass}, this runs in the middle of a frame the
 * 2D target owns, so every piece of state it moves is state that target is
 * relying on. The vertex array binding, the bound texture and the program are
 * all saved and put back — see the note on {@code GlTerrainPass} for what
 * happens when they are not.
 */
final class GlMeshPass implements MeshPass {

    /**
     * Meshes re-uploaded in one frame; the rest arrive over the next few.
     *
     * <p>Raised from twelve. Twelve was chosen when a chunk's mesh was the only
     * thing being uploaded and a view held a few dozen of them; at the render
     * distances a card actually holds, walking into fresh terrain dirties far
     * more than twelve at once, and a cap that low turned "the ground arrives a
     * frame late" into "the ground arrives four seconds late" — the world
     * visibly assembling itself ahead of a walking player. An upload is a
     * {@code glBufferData} of a few tens of kilobytes; three dozen of them is
     * well inside a frame on any card that has a mesh pass at all.
     */
    private static final int MAX_UPLOADS_PER_FRAME = 36;

    /**
     * Buffers kept before the least recently drawn is dropped.
     *
     * <p>Video memory has no garbage collector, and a party that walks ten
     * kilometres would otherwise leave a buffer on the card for every piece of
     * ground they have looked at. But the ceiling should follow the machine:
     * {@link ChunkMemory} turns the heap the JVM was given into how much world
     * this process is willing to hold, and a buffer ceiling well above that
     * count means the card is never the thing that forces a re-mesh of ground
     * the CPU still has.
     */
    private static final int MAX_BUFFERS = Math.max(4096,
            com.larsons.engine.graphics.ChunkMemory.gpuBufferBudget());

    /** Bytes per vertex: three position floats, two texture floats, RGBA. */
    private static final int STRIDE = 5 * 4 + 4;

    /** Discard below this in the opaque pass, so cutouts write no depth. */
    private static final float ALPHA_CUT = 0.5f;

    private final java.util.function.Supplier<GlTarget> target;

    private final Map<Long, Buffer> buffers = new HashMap<>();
    private final List<Draw> opaque = new ArrayList<>();
    private final List<Draw> translucent = new ArrayList<>();

    private GlTerrainProgram program;

    /**
     * The lights the next frame is lit by, and the hour's own colour.
     *
     * <p>Held between {@link #setLighting} and {@link #draw} rather than passed
     * through {@code draw} because the two are set at different moments by the
     * caller and because a backend that ignores lighting entirely — which is
     * every backend but this one — should not have to have the parameter in its
     * signature. See {@link MeshPass#setLighting}.
     */
    private final List<Light> lights = new ArrayList<>();

    private float dayR = 1, dayG = 1, dayB = 1;

    private int texture = -1;
    private int textureRevision = -1;
    private boolean unavailable;
    private int frameStamp;
    private int uploadsThisFrame;

    private int timeQuery = -1;
    private boolean timing;
    private boolean timingUnavailable;
    private double gpuMillis;

    GlMeshPass(java.util.function.Supplier<GlTarget> target) {
        this.target = target;
    }

    /** One mesh's buffer on the card. */
    private static final class Buffer {
        int vao = -1;
        int vbo = -1;
        int revision = Integer.MIN_VALUE;
        int vertexCount;
        int lastSeen;
        /**
         * The origin the vertices in this buffer are measured from.
         *
         * <p>Load-bearing, and the cause of a bug that took a while to name.
         * Vertices are relative to their mesh's origin and the origin is applied
         * by the model-view matrix each frame, so a buffer is only re-usable for
         * a draw whose origin is the one it was filled at. The dynamic mesh —
         * animals, walkers, feeders, everything built — is rebuilt each frame
         * around the <em>player's</em> position, so its origin moves every frame
         * a player walks. Reusing a stale buffer for it therefore did not draw
         * one frame of slightly old positions; it drew the whole lot displaced
         * by however far the player had moved since. Walls and platforms, which
         * are otherwise perfectly still, visibly jumped a metre and back.
         */
        double originX = Double.NaN, originY = Double.NaN, originZ = Double.NaN;

        /** Whether this buffer's contents can be drawn at a draw's origin. */
        boolean matches(Draw draw) {
            return originX == draw.originX() && originY == draw.originY()
                    && originZ == draw.originZ();
        }
    }

    @Override
    public void setTexture(BufferedImage atlas, int revision) {
        if (atlas == null || revision == textureRevision) return;
        int w = atlas.getWidth(), h = atlas.getHeight();
        int[] argb = atlas.getRGB(0, 0, w, h, null, 0, w);
        ByteBuffer pixels = MemoryUtil.memAlloc(w * h * 4);
        try {
            for (int p : argb) {
                pixels.put((byte) ((p >> 16) & 0xFF));
                pixels.put((byte) ((p >> 8) & 0xFF));
                pixels.put((byte) (p & 0xFF));
                pixels.put((byte) ((p >>> 24) & 0xFF));
            }
            pixels.flip();
            if (texture < 0) texture = glGenTextures();
            int previous = glGetInteger(GL_TEXTURE_BINDING_2D);
            glBindTexture(GL_TEXTURE_2D, texture);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA,
                    GL_UNSIGNED_BYTE, pixels);
            // Nearest both ways. The atlas is a grid of small tiles with no
            // gutters, so a linear filter at a tile's edge samples the tile
            // next door — grass with a seam of stone through it — and mipmaps
            // do the same thing an order of magnitude worse.
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
            glBindTexture(GL_TEXTURE_2D, previous);
            textureRevision = revision;
        } finally {
            MemoryUtil.memFree(pixels);
        }
    }

    @Override
    public void setLighting(List<Light> frameLights, float r, float g, float b) {
        lights.clear();
        if (frameLights != null) {
            for (Light light : frameLights) {
                if (lights.size() >= MAX_LIGHTS) break;
                if (light != null) lights.add(light);
            }
        }
        dayR = r;
        dayG = g;
        dayB = b;
    }

    @Override
    public void draw(List<Draw> draws, EyeCamera eye, int fogArgb, double fogStart,
                     double fogEnd) {
        if (unavailable || draws == null || draws.isEmpty() || eye == null) return;

        GlTarget flat = target.get();
        if (flat != null) flat.flushBatch();
        if (program == null) {
            try {
                program = new GlTerrainProgram();
            } catch (RuntimeException e) {
                // A driver that will not compile this will not compile it next
                // frame either; say so once and let the scene fall back to its
                // own painter.
                System.err.println("[gl] mesh shader unavailable, "
                        + "falling back to the painter: " + e.getMessage());
                unavailable = true;
                return;
            }
        }

        int callersVao = glGetInteger(GL_VERTEX_ARRAY_BINDING);
        collectGpuTime();
        beginGpuTime();
        try {
            drawMeshes(draws, eye, fogArgb, fogStart, fogEnd);
        } finally {
            endGpuTime();
            glBindVertexArray(callersVao);
            glDisable(GL_CULL_FACE);
            // Handed back with the depth buffer intact and depth writes off,
            // so the HUD and any sprites drawn after this are tested against
            // the world but cannot occlude it — the same contract the block
            // pass leaves behind.
            glEnable(GL_DEPTH_TEST);
            glDepthFunc(GL_LEQUAL);
            glDepthMask(false);
            glEnable(GL_BLEND);
            glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA,
                    GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
            if (flat != null) flat.restoreAfterTerrain();
        }
    }

    private void drawMeshes(List<Draw> draws, EyeCamera eye, int fogArgb,
                            double fogStart, double fogEnd) {
        frameStamp++;
        uploadsThisFrame = 0;
        opaque.clear();
        translucent.clear();
        for (Draw draw : draws) {
            if (draw == null || draw.isEmpty()) continue;
            (draw.translucent() ? translucent : opaque).add(draw);
        }
        // Far to near for the see-through half, by the mesh's own origin. Per
        // mesh rather than per triangle: a chunk is thirty-two metres across
        // and the eye is rarely inside two sheets of water at once, which is
        // the same trade the block path makes at the arena.
        translucent.sort(Comparator.comparingDouble(
                (Draw d) -> -distanceSquared(d, eye)));

        Mat4 projection = Mat4.perspective(eye.fov(),
                eye.viewportWidth() / (double) eye.viewportHeight(),
                EyeCamera.NEAR, Math.max(EyeCamera.NEAR * 2, fogEnd * 1.5));

        glEnable(GL_DEPTH_TEST);
        glDepthFunc(GL_LEQUAL);
        glDepthMask(true);
        glClear(GL_DEPTH_BUFFER_BIT);
        glEnable(GL_CULL_FACE);
        glCullFace(GL_BACK);
        // Clockwise, and the sign is load-bearing: this engine's eye space has
        // +Z forward, so a face wound counter-clockwise seen from outside — as
        // every mesher in `watch.render` winds them — reaches the window
        // clockwise. See Mat4.FRONT_FACES_WIND_CLOCKWISE.
        glFrontFace(Mat4.FRONT_FACES_WIND_CLOCKWISE ? GL_CW : GL_CCW);
        glDisable(GL_BLEND);

        program.use();
        program.setFog(fogArgb, fogStart, fogEnd, true);
        program.setAlphaCut(ALPHA_CUT);
        // Once for the frame, for both passes: the hour, and every lamp in the
        // world. `use` has just reset these to neutral, so a caller that never
        // calls setLighting draws exactly what it drew before this existed.
        program.setLighting(lights, eye, dayR, dayG, dayB);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, texture);

        for (Draw draw : opaque) issue(draw, projection, eye);

        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glDepthMask(false);
        program.setAlphaCut(0f);
        for (Draw draw : translucent) issue(draw, projection, eye);

        evict();
    }

    private static double distanceSquared(Draw draw, EyeCamera eye) {
        double dx = draw.originX() - eye.x();
        double dy = draw.originY() - eye.y();
        double dz = draw.originZ() - eye.z();
        return dx * dx + dy * dy + dz * dz;
    }

    /** Upload if needed, set the matrices, and draw one mesh. */
    private void issue(Draw draw, Mat4 projection, EyeCamera eye) {
        Buffer buffer = buffers.computeIfAbsent(draw.key(), k -> new Buffer());
        buffer.lastSeen = frameStamp;
        boolean stale = buffer.revision != draw.revision();
        boolean moved = !buffer.matches(draw);
        if (stale || moved) {
            if (uploadsThisFrame < MAX_UPLOADS_PER_FRAME) {
                upload(buffer, draw);
                uploadsThisFrame++;
            } else if (moved || buffer.vertexCount == 0) {
                // Over budget, and what is on the card cannot stand in for what
                // was asked for. A mesh with no buffer yet simply waits, as a
                // chunk that has not finished meshing does — and a mesh whose
                // origin has moved waits too, because its cached vertices mean
                // something else at the new origin. Drawing them there is worse
                // than not drawing at all: that is what made walls jump.
                //
                // The one thing that may be deferred is a stale mesh at an
                // unchanged origin — a chunk being re-meshed at a finer level
                // of detail, where last frame's triangles are in exactly the
                // right place and merely coarser, and one frame of them at the
                // edge of the view is invisible.
                return;
            }
        }
        drawBuffer(buffer, draw, projection, eye);
    }

    /** Set the matrices for one buffer at a draw's origin and issue it. */
    private void drawBuffer(Buffer buffer, Draw draw, Mat4 projection, EyeCamera eye) {
        if (buffer.vao < 0 || buffer.vertexCount == 0) return;

        // Vertices reach the card measured from the mesh's own origin, and the
        // origin reaches it measured from the eye, in double: a world with no
        // edge cannot be expressed in float at centimetre precision, and this
        // is how every renderer of that size avoids having to.
        //
        // Composed by Mat4 rather than here, because the pairing is the trap.
        // This once multiplied an eye-relative model by the eye-relative
        // Mat4.view and subtracted the camera twice — a self-consistent picture
        // drawn from twice the altitude, while every ray the game traced came
        // from the real camera.
        Mat4 modelView = Mat4.eyeRelativeModelView(eye,
                draw.originX(), draw.originY(), draw.originZ());
        program.setMatrices(projection.times(modelView).columnMajor(),
                modelView.columnMajor());

        glBindVertexArray(buffer.vao);
        glDrawArrays(GL_TRIANGLES, 0, buffer.vertexCount);
    }

    private void upload(Buffer buffer, Draw draw) {
        int count = draw.vertexCount();
        if (buffer.vao < 0) buffer.vao = glGenVertexArrays();
        if (buffer.vbo < 0) buffer.vbo = glGenBuffers();
        ByteBuffer data = MemoryUtil.memAlloc(count * STRIDE);
        int callersVao = glGetInteger(GL_VERTEX_ARRAY_BINDING);
        try {
            float[] vertices = draw.vertices();
            int[] colours = draw.colours();
            for (int i = 0; i < count; i++) {
                int at = i * 5;
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
            glBindVertexArray(buffer.vao);
            glBindBuffer(GL_ARRAY_BUFFER, buffer.vbo);
            glBufferData(GL_ARRAY_BUFFER, data, GL_STATIC_DRAW);
            glEnableVertexAttribArray(GlTerrainProgram.ATTRIB_POSITION);
            glVertexAttribPointer(GlTerrainProgram.ATTRIB_POSITION, 3, GL_FLOAT, false,
                    STRIDE, 0);
            glEnableVertexAttribArray(GlTerrainProgram.ATTRIB_UV);
            glVertexAttribPointer(GlTerrainProgram.ATTRIB_UV, 2, GL_FLOAT, false,
                    STRIDE, 12);
            glEnableVertexAttribArray(GlTerrainProgram.ATTRIB_COLOR);
            glVertexAttribPointer(GlTerrainProgram.ATTRIB_COLOR, 4, GL_UNSIGNED_BYTE, true,
                    STRIDE, 20);
            buffer.revision = draw.revision();
            buffer.vertexCount = count;
            buffer.originX = draw.originX();
            buffer.originY = draw.originY();
            buffer.originZ = draw.originZ();
        } finally {
            glBindVertexArray(callersVao);
            MemoryUtil.memFree(data);
        }
    }

    @Override
    public void discard(List<Long> keys) {
        if (keys == null) return;
        for (Long key : keys) {
            Buffer buffer = buffers.remove(key);
            if (buffer != null) release(buffer);
        }
    }

    /** Drop the buffers nothing has drawn for a while. */
    private void evict() {
        if (buffers.size() <= MAX_BUFFERS) return;
        buffers.entrySet().removeIf(entry -> {
            if (frameStamp - entry.getValue().lastSeen < 240) return false;
            release(entry.getValue());
            return true;
        });
    }

    private static void release(Buffer buffer) {
        if (buffer.vbo >= 0) glDeleteBuffers(buffer.vbo);
        if (buffer.vao >= 0) glDeleteVertexArrays(buffer.vao);
        buffer.vbo = -1;
        buffer.vao = -1;
        buffer.vertexCount = 0;
    }

    @Override
    public double lastFrameMillis() { return gpuMillis; }

    @Override
    public int uploadsLastFrame() { return uploadsThisFrame; }

    /** Release everything on the card. */
    void dispose() {
        for (Buffer buffer : buffers.values()) release(buffer);
        buffers.clear();
        if (texture >= 0) {
            glDeleteTextures(texture);
            texture = -1;
            textureRevision = -1;
        }
        if (program != null) {
            program.close();
            program = null;
        }
        if (timeQuery >= 0) {
            glDeleteQueries(timeQuery);
            timeQuery = -1;
        }
    }

    // --- what the card is actually spending -------------------------------------

    private void collectGpuTime() {
        if (timingUnavailable || timeQuery < 0 || timing) return;
        if (glGetQueryObjecti(timeQuery, GL_QUERY_RESULT_AVAILABLE) != GL_TRUE) return;
        double ms = glGetQueryObjecti64(timeQuery, GL_QUERY_RESULT) / 1e6;
        gpuMillis = gpuMillis <= 0 ? ms : gpuMillis + (ms - gpuMillis) * 0.08;
    }

    private void beginGpuTime() {
        if (timingUnavailable) return;
        try {
            if (timeQuery < 0) timeQuery = glGenQueries();
            glBeginQuery(GL_TIME_ELAPSED, timeQuery);
            timing = true;
        } catch (RuntimeException e) {
            // A context without timer queries draws fine and cannot say what it
            // cost; the caller then steers its render distance on the signals
            // that remain.
            timingUnavailable = true;
            timing = false;
        }
    }

    private void endGpuTime() {
        if (!timing) return;
        timing = false;
        glEndQuery(GL_TIME_ELAPSED);
    }
}
