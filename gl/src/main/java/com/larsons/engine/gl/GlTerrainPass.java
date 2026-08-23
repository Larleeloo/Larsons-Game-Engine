package com.larsons.engine.gl;

import com.larsons.engine.graphics.EyeCamera;
import com.larsons.engine.graphics.Mat4;
import com.larsons.engine.graphics.TerrainPass;
import com.larsons.engine.graphics.chunk.BlockAtlas;
import com.larsons.engine.graphics.chunk.SectionMesh;
import com.larsons.engine.graphics.chunk.SectionRenderList;
import org.lwjgl.system.MemoryUtil;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.lwjgl.opengl.GL33C.*;

/**
 * The world, drawn in three dimensions with a depth buffer — <b>the pass this
 * engine did not have</b>.
 *
 * <h2>What a frame does</h2>
 *
 * <ol>
 *   <li>Clear the depth buffer. The colour buffer already holds the sky, which
 *       the 2D target painted; terrain is drawn over it and the sky shows
 *       wherever nothing was.</li>
 *   <li>Upload any section whose mesh has been rebuilt since it was last seen,
 *       bounded per frame so a teleport cannot spend a whole frame in
 *       {@code glBufferData}.</li>
 *   <li><b>Opaque pass</b>, near to far, depth test and depth write on, back
 *       faces culled, alpha tested so a leaf's holes do not write depth.</li>
 *   <li><b>Translucent pass</b>, far to near, depth test on and depth
 *       <em>write</em> off, blended. Water seen through water is still water.</li>
 * </ol>
 *
 * <h2>Why the two passes differ</h2>
 *
 * <p>A depth test makes opaque geometry order-independent, which is the whole
 * reason the meshes can be cached at all. It does nothing for blending, because
 * blending is not commutative — so the see-through half is still sorted, and it
 * is sorted by <em>section</em> rather than by face, which is a few hundred
 * comparisons instead of tens of thousands. Minecraft draws its render layers in
 * this order and for these reasons.
 *
 * <p><b>The depth buffer is left in place afterwards.</b> The sprites — actors,
 * plants, the level's scenery — are drawn by the 2D target after this, and
 * telling it about the depth buffer is what lets a character stand behind a
 * hill instead of on top of it. See {@link #sharesDepthWithSprites}.
 *
 * <h2>Everything else is put back exactly as it was found</h2>
 *
 * <p>This runs in the <em>middle</em> of somebody else's frame — after the sky
 * and before the sprites — so every piece of GL state it moves is state a
 * {@link GlTarget} is relying on, and three of them are not obvious:
 *
 * <ul>
 *   <li><b>The vertex array binding.</b> {@link GlContext} creates one VAO for
 *       the whole engine and {@code GlBatch} records its attribute pointers into
 *       it, once, at construction. A core-profile draw with no VAO bound is not
 *       a draw — it is {@code GL_INVALID_OPERATION} and nothing on screen — so
 *       leaving {@code glBindVertexArray(0)} behind here does not corrupt the
 *       sprites, it <b>deletes every one of them</b>, along with the plants, the
 *       HUD and the pause menu. Saved and restored, the way
 *       {@code GlShaderChain} already does it.</li>
 *   <li><b>The bound texture.</b> The atlas goes on unit 0, which is where the
 *       2D batch keeps its own page — and that batch caches the id it last
 *       bound, so the next sprite naming the same id would skip the rebind and
 *       sample the block atlas. Hence {@code GlTarget.restoreAfterTerrain}.</li>
 *   <li><b>The program.</b> Restored on <em>every</em> path out, including a
 *       throw, because a frame that carried on with the terrain shader bound
 *       would feed it 2D vertices through a 3D attribute layout.</li>
 * </ul>
 */
final class GlTerrainPass implements TerrainPass {

    /** Sections uploaded in one frame — the rest arrive over the next few. */
    private static final int MAX_UPLOADS_PER_FRAME = 16;

    /**
     * How opaque a fragment must be to survive the opaque pass.
     *
     * <p>Half, which is the usual cutout threshold: a leaf sheet's pixels are
     * either there or they are not, and a texel on the boundary belongs to
     * whichever side the sampler rounds to. Below this the fragment is
     * discarded and — the part that matters — writes no depth, so the sky shows
     * through the gaps in a canopy rather than the canopy occluding it.
     */
    private static final float ALPHA_CUT = 0.5f;

    /** Where the 2D target is, so its batch can be closed before GL state moves. */
    private final java.util.function.Supplier<GlTarget> target;

    GlTerrainPass(java.util.function.Supplier<GlTarget> target) {
        this.target = target;
    }

    private final Map<Long, GlSectionBuffer> buffers = new HashMap<>();
    private GlTerrainProgram program;
    private int atlasTexture = -1;
    private int atlasRevision = -1;
    private boolean unavailable;

    // --- what the card is actually spending -------------------------------------

    /**
     * A {@code GL_TIME_ELAPSED} query around the terrain draw, read a frame
     * later — the same arrangement, and for the same reason, as
     * {@code GlShaderChain.Timers}.
     *
     * <p>Reading a query result blocks until the GPU has reached it, so asking
     * in the frame that issued it would stall the pipeline to measure it — the
     * measurement would cost more than the thing measured and would change it.
     * Asked at the start of the next frame instead, by which time the answer is
     * sitting there. One query in flight at a time: this is a single span, and
     * a frame whose answer is not ready simply keeps the last one.
     */
    private int timeQuery = -1;
    private boolean timing;
    private boolean timingUnavailable;

    /**
     * Smoothed over about a dozen frames.
     *
     * <p>A single expensive frame is a shader being compiled or a chunk of
     * uploads landing, and pulling the render distance in for one of those
     * would be visible where the cause was not. What the radius should follow
     * is the level the card is holding, not its worst instant.
     */
    private double gpuMillis;

    @Override
    public void setAtlas(BlockAtlas atlas) {
        if (atlas == null || atlas.revision() == atlasRevision) return;
        BufferedImage image = atlas.image();
        int w = image.getWidth(), h = image.getHeight();
        int[] argb = image.getRGB(0, 0, w, h, null, 0, w);
        ByteBuffer pixels = MemoryUtil.memAlloc(w * h * 4);
        try {
            for (int p : argb) {
                pixels.put((byte) ((p >> 16) & 0xFF));
                pixels.put((byte) ((p >> 8) & 0xFF));
                pixels.put((byte) (p & 0xFF));
                pixels.put((byte) ((p >>> 24) & 0xFF));
            }
            pixels.flip();
            if (atlasTexture < 0) atlasTexture = glGenTextures();
            glBindTexture(GL_TEXTURE_2D, atlasTexture);
            glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
            // Nearest, like every other texture in this engine: the art is
            // pixels and the Java2D backend samples it with
            // VALUE_INTERPOLATION_NEAREST_NEIGHBOR, so anything else here would
            // be the two backends disagreeing about what a block looks like.
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
            atlasRevision = atlas.revision();
        } finally {
            MemoryUtil.memFree(pixels);
        }
    }

    @Override
    public void drawTerrain(SectionRenderList list, EyeCamera eye, int tileSize,
                            int fogArgb, double fogStart, double fogEnd) {
        if (unavailable || atlasTexture < 0) return;
        List<SectionRenderList.Visible> visible = list.visible();
        if (visible.isEmpty()) return;
        // The sky is already in the colour buffer, drawn by the 2D target and
        // possibly still sitting in its batch. Issue it before the depth test
        // and the back-face cull go on, or it would be drawn under them.
        GlTarget flat = target.get();
        if (flat != null) flat.flushBatch();
        if (program == null) {
            try {
                program = new GlTerrainProgram();
            } catch (RuntimeException e) {
                // A driver that will not compile this will not compile it next
                // frame either; say so once and let the scene fall back.
                System.err.println("[gl] terrain shader unavailable, "
                        + "falling back to the painter: " + e.getMessage());
                unavailable = true;
                return;
            }
        }

        // The engine's one VAO, whose attribute pointers the 2D batch lives in.
        // Read rather than assumed: this class does not own it and is not the
        // only thing that borrows it.
        int callersVao = glGetInteger(GL_VERTEX_ARRAY_BINDING);
        collectGpuTime();
        beginGpuTime();
        try {
            drawSections(visible, eye, tileSize, fogArgb, fogStart, fogEnd);
        } finally {
            endGpuTime();
            glBindVertexArray(callersVao);
            glDisable(GL_CULL_FACE);
            // Handed back to the 2D target with the depth buffer intact and
            // depth *writes* off: everything drawn after this — sprites,
            // plants, the HUD — is tested against the world it is standing in
            // but cannot occlude it. A painter that never calls pushDepth draws
            // at the near plane and so passes the test unconditionally, which is
            // what a HUD wants and is why this costs the rest of the engine
            // nothing.
            glEnable(GL_DEPTH_TEST);
            glDepthFunc(GL_LEQUAL);
            glDepthMask(false);
            glEnable(GL_BLEND);
            glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA,
                    GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
            if (flat != null) flat.restoreAfterTerrain();
        }
    }

    @Override
    public double lastGpuMillis() { return gpuMillis; }

    /** Take last frame's answer if the card has finished with it. */
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
            // A context without timer queries is a context that draws fine and
            // cannot say what it cost. The radius then steers on the signals
            // that remain, which is what a backend with no timing at all does.
            timingUnavailable = true;
            timing = false;
        }
    }

    private void endGpuTime() {
        if (!timing) return;
        timing = false;
        glEndQuery(GL_TIME_ELAPSED);
    }

    /** The two passes themselves; {@link #drawTerrain} owns the state around them. */
    private void drawSections(List<SectionRenderList.Visible> visible, EyeCamera eye,
                              int tileSize, int fogArgb, double fogStart, double fogEnd) {
        int uploads = 0;
        for (SectionRenderList.Visible v : visible) {
            long key = com.larsons.engine.graphics.chunk.TerrainSections
                    .key(v.sx(), v.sy(), v.sz());
            GlSectionBuffer buffer = buffers.get(key);
            if (buffer == null) {
                if (uploads >= MAX_UPLOADS_PER_FRAME) continue;
                buffer = new GlSectionBuffer();
                buffers.put(key, buffer);
            }
            if (!buffer.holds(v.mesh())) {
                if (uploads++ >= MAX_UPLOADS_PER_FRAME) continue;
                buffer.upload(v.mesh());
            }
        }

        Mat4 projection = Mat4.perspective(eye.fov(),
                eye.viewportWidth() / (double) eye.viewportHeight(),
                EyeCamera.NEAR, Math.max(EyeCamera.NEAR * 2, fogEnd * 1.5));
        Mat4 view = Mat4.view(eye);

        glEnable(GL_DEPTH_TEST);
        glDepthFunc(GL_LEQUAL);
        glDepthMask(true);
        glClear(GL_DEPTH_BUFFER_BIT);
        glEnable(GL_CULL_FACE);
        glCullFace(GL_BACK);
        // Clockwise, and the sign is load-bearing: this engine's eye space has
        // +Z forward, so a face wound counter-clockwise seen from outside — as
        // SectionMesher winds every one of them — reaches the window clockwise.
        // GL_CCW here keeps precisely the faces pointing away from the player.
        // See Mat4.FRONT_FACES_WIND_CLOCKWISE.
        glFrontFace(com.larsons.engine.graphics.Mat4.FRONT_FACES_WIND_CLOCKWISE
                ? GL_CW : GL_CCW);
        glDisable(GL_BLEND);

        program.use();
        program.setFog(fogArgb, fogStart, fogEnd, true);
        program.setAlphaCut(ALPHA_CUT);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, atlasTexture);

        double span = SectionMesh.SIZE * (double) tileSize;
        // Opaque, near to far: a near section drawn first fills the depth
        // buffer and everything behind it is thrown away before it is shaded.
        for (SectionRenderList.Visible v : visible) {
            GlSectionBuffer buffer = buffers.get(
                    com.larsons.engine.graphics.chunk.TerrainSections
                            .key(v.sx(), v.sy(), v.sz()));
            if (buffer == null || buffer.opaqueVertexCount() == 0) continue;
            bindSection(program, projection, view, v, span);
            buffer.drawOpaque();
        }

        // See-through, far to near, and no depth writes: blending is not
        // commutative, so this half is still ordered — by section rather than
        // by face, which is a few hundred comparisons instead of thousands.
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glDepthMask(false);
        program.setAlphaCut(0f);
        for (int i = visible.size() - 1; i >= 0; i--) {
            SectionRenderList.Visible v = visible.get(i);
            GlSectionBuffer buffer = buffers.get(
                    com.larsons.engine.graphics.chunk.TerrainSections
                            .key(v.sx(), v.sy(), v.sz()));
            if (buffer == null || buffer.translucentVertexCount() == 0) continue;
            bindSection(program, projection, view, v, span);
            buffer.drawTranslucent();
        }
    }

    /**
     * Point the shader at one section: its own translation folded into the view
     * and projection, so its vertices stay small floats near the origin.
     */
    private static void bindSection(GlTerrainProgram program, Mat4 projection, Mat4 view,
                                    SectionRenderList.Visible v, double span) {
        Mat4 model = Mat4.translation(v.sx() * span, v.sz() * span, v.sy() * span);
        Mat4 modelView = view.times(model);
        program.setMatrices(projection.times(modelView).columnMajor(),
                modelView.columnMajor());
    }


    /**
     * Until a shader fails to compile, at which point this says no for the rest
     * of the process and the scene goes back to the painter with its blocks.
     */
    @Override
    public boolean available() { return !unavailable; }

    /**
     * Yes: the depth buffer written here is the one the 2D target draws into
     * afterwards, so a sprite behind a hill is behind it.
     */
    @Override
    public boolean sharesDepthWithSprites() { return true; }

    @Override
    public void dispose() {
        for (GlSectionBuffer buffer : buffers.values()) buffer.close();
        buffers.clear();
        if (program != null) {
            program.close();
            program = null;
        }
        if (atlasTexture >= 0) {
            glDeleteTextures(atlasTexture);
            atlasTexture = -1;
            atlasRevision = -1;
        }
        if (timeQuery >= 0) {
            if (timing) {
                glEndQuery(GL_TIME_ELAPSED);
                timing = false;
            }
            glDeleteQueries(timeQuery);
            timeQuery = -1;
        }
    }
}
