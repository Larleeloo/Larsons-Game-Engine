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
        glFrontFace(GL_CCW);
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

        glBindVertexArray(0);
        glDisable(GL_CULL_FACE);
        // Handed back to the 2D target with the depth buffer intact and depth
        // *writes* off: everything drawn after this — sprites, plants, the HUD —
        // is tested against the world it is standing in but cannot occlude it.
        // A painter that never calls pushDepth draws at the near plane and so
        // passes the test unconditionally, which is what a HUD wants and is why
        // this costs the rest of the engine nothing.
        glEnable(GL_DEPTH_TEST);
        glDepthFunc(GL_LEQUAL);
        glDepthMask(false);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        if (flat != null) flat.restoreDepth();
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
    }
}
