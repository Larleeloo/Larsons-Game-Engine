package com.larsons.engine.gl;

import com.larsons.engine.graphics.EyeCamera;
import com.larsons.engine.graphics.Mat4;
import com.larsons.engine.graphics.TerrainPass;
import com.larsons.engine.graphics.chunk.BlockAtlas;
import com.larsons.engine.graphics.chunk.SectionBatch;
import com.larsons.engine.graphics.chunk.SectionMesh;
import com.larsons.engine.graphics.chunk.SectionRenderList;
import org.lwjgl.system.MemoryUtil;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
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

    /**
     * Arenas re-uploaded in one frame — the rest arrive over the next few.
     *
     * <p>An arena is up to sixty-four sections, so this is a few megabytes of
     * {@code glBufferData} a frame at worst. Bounded for the reason the
     * per-section upload was: the first seconds of a world dirty everything at
     * once, and a frame that re-specified every buffer would be the stutter this
     * whole path exists to remove.
     */
    private static final int MAX_REBUILDS_PER_FRAME = 8;

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

    /** Every arena that has ever been drawn, by its packed section coordinates. */
    private final Map<Long, GlSectionArena> arenas = new HashMap<>();

    /** The scratch packer every arena rebuild borrows; see {@link SectionBatch}. */
    private final SectionBatch batch = new SectionBatch();

    // This frame's arenas, in the order the walk first reached them, and which
    // of each one's slots are visible. Reused between frames: a frame at ninety
    // chunks touches several hundred arenas and allocating that list again every
    // frame is the kind of garbage this path spent a commit removing.
    private GlSectionArena[] order = new GlSectionArena[256];
    private int[][] slots = newSlotRows(256);
    private int[] slotCount = new int[256];
    private int arenaCount;
    private final Map<Long, Integer> seen = new HashMap<>();

    /** Where {@code glMultiDrawArrays} reads its ranges from. */
    private IntBuffer firstScratch = MemoryUtil.memAllocInt(SectionBatch.SLOTS);
    private IntBuffer countScratch = MemoryUtil.memAllocInt(SectionBatch.SLOTS);

    /**
     * Arenas kept before the least recently seen are dropped.
     *
     * <p>A ninety-chunk view is on the order of two thousand of them, so this is
     * several times what any frame needs — it exists because a player who walks
     * a long way would otherwise leave a buffer on the card for every arena they
     * have ever looked at, and video memory has no garbage collector.
     */
    private static final int MAX_ARENAS = 8192;

    /** Counted up once per frame, so an arena knows when it was last wanted. */
    private int frameStamp;

    /**
     * The arena the last section belonged to.
     *
     * <p>The walk hands sections over in breadth-first order, which is
     * spatially coherent, so most of them land in the arena the one before did.
     * Worth a field because the alternative is a boxed map lookup per visible
     * section — tens of thousands a frame, which is the cost this path has
     * twice now had to go and remove from somewhere else.
     */
    private long lastArenaKey;
    private int lastArenaAt = -1;

    private static int[][] newSlotRows(int rows) {
        int[][] made = new int[rows][];
        for (int i = 0; i < rows; i++) made[i] = new int[SectionBatch.SLOTS];
        return made;
    }
    private GlTerrainProgram program;

    /** The general mesh pass, for worlds that are not made of blocks. */
    private GlMeshPass meshes;

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

    /**
     * The general triangle-mesh pass, built on first use.
     *
     * <p>Lazily, because most games in this engine are made of blocks and never
     * ask: a renderer that is only ever handed sections should not be paying
     * for a second shader program it will not use.
     */
    @Override
    public com.larsons.engine.graphics.MeshPass meshPass() {
        if (meshes == null) meshes = new GlMeshPass(target);
        return meshes;
    }

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
        groupByArena(visible);
        refreshArenas(tileSize);

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
        // Opaque, near to far — which the arena order already is, because the
        // walk hands its sections over near-first and an arena is first seen
        // when its nearest section is. A near arena drawn first fills the depth
        // buffer and everything behind it is thrown away before it is shaded.
        for (int i = 0; i < arenaCount; i++) {
            drawArena(i, projection, view, span, true);
        }

        // See-through, far to near, and no depth writes: blending is not
        // commutative, so this half is still ordered. By arena rather than by
        // section now, which is coarser than it was — sixty-four sections that
        // share a buffer are drawn in whatever order they sit in it. Two panes
        // of glass in one arena can therefore blend in the wrong order; that is
        // the price of the draw count, and it is paid where it shows least,
        // because an arena is sixty-four blocks across and the eye is rarely
        // inside two of its translucent faces at once.
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glDepthMask(false);
        program.setAlphaCut(0f);
        for (int i = arenaCount - 1; i >= 0; i--) {
            drawArena(i, projection, view, span, false);
        }
    }

    /**
     * Sort this frame's sections into their arenas, keeping the walk's order.
     *
     * <p>Nearest-first is a property of the list handed in, and it survives:
     * an arena takes its place the first time one of its sections is seen, so
     * the arenas come out ordered by their nearest visible member.
     */
    private void groupByArena(List<SectionRenderList.Visible> visible) {
        arenaCount = 0;
        seen.clear();
        lastArenaAt = -1;
        frameStamp++;
        for (SectionRenderList.Visible v : visible) {
            long key = SectionBatch.arenaOf(v.sx(), v.sy(), v.sz());
            int at;
            if (lastArenaAt >= 0 && key == lastArenaKey) {
                at = lastArenaAt;
            } else {
                Integer found = seen.get(key);
                if (found != null) {
                    at = found;
                } else {
                    if (arenaCount == order.length) grow();
                    at = arenaCount++;
                    seen.put(key, at);
                    GlSectionArena arena = arenas.get(key);
                    if (arena == null) {
                        arena = new GlSectionArena(
                                Math.floorDiv(v.sx(), SectionBatch.SPAN),
                                Math.floorDiv(v.sy(), SectionBatch.SPAN),
                                Math.floorDiv(v.sz(), SectionBatch.SPAN));
                        arenas.put(key, arena);
                    }
                    order[at] = arena;
                    slotCount[at] = 0;
                }
                lastArenaKey = key;
                lastArenaAt = at;
            }
            order[at].seenAt(frameStamp);
            int slot = SectionBatch.slotOf(v.sx(), v.sy(), v.sz());
            order[at].offer(slot, v.mesh());
            // A slot can only be offered once a frame — the walk marks each
            // section seen and visits it once — so this is a straight append.
            // Guarded anyway, because the alternative to a dropped section is
            // writing past the end of the row mid-frame.
            if (slotCount[at] < SectionBatch.SLOTS) slots[at][slotCount[at]++] = slot;
        }
        if (arenas.size() > MAX_ARENAS) evictArenas();
    }

    /**
     * Drop the arenas nothing has looked at for longest.
     *
     * <p>Video memory has no collector, so an arena's buffers live until
     * something deletes them. A player who walks across a world would otherwise
     * accumulate one for every arena they have ever seen.
     */
    private void evictArenas() {
        int keepAfter = frameStamp - EVICT_AFTER_FRAMES;
        arenas.values().removeIf(arena -> {
            if (arena.lastSeen() > keepAfter) return false;
            arena.close();
            return true;
        });
    }

    /** How stale an arena must be before it is dropped; see {@link #evictArenas}. */
    private static final int EVICT_AFTER_FRAMES = 600;

    /**
     * Re-upload the arenas whose contents changed, a few per frame.
     *
     * <p>Bounded for the reason the per-section upload was: a teleport, or the
     * first seconds of a world, dirties everything at once, and a frame that
     * re-specified every buffer would be the stutter this whole path exists to
     * remove. What is not rebuilt this frame is rebuilt next; until then the
     * arena draws what it last held, which is the world a moment ago rather
     * than a hole.
     */
    private void refreshArenas(int tileSize) {
        int rebuilt = 0;
        for (int i = 0; i < arenaCount && rebuilt < MAX_REBUILDS_PER_FRAME; i++) {
            if (!order[i].isDirty()) continue;
            order[i].rebuild(batch, tileSize);
            rebuilt++;
        }
    }

    private void drawArena(int at, Mat4 projection, Mat4 view, double span, boolean opaque) {
        if (slotCount[at] == 0) return;
        GlSectionArena arena = order[at];
        // One position for sixty-four sections: the arena's own corner, with
        // each section's offset inside it already folded into its vertices.
        Mat4 model = Mat4.translation(
                arena.arenaX() * SectionBatch.SPAN * span,
                arena.arenaZ() * SectionBatch.SPAN * span,
                arena.arenaY() * SectionBatch.SPAN * span);
        Mat4 modelView = view.times(model);
        program.setMatrices(projection.times(modelView).columnMajor(), modelView.columnMajor());
        arena.draw(opaque, slots[at], slotCount[at], firstScratch, countScratch);
    }

    private void grow() {
        int want = order.length * 2;
        order = java.util.Arrays.copyOf(order, want);
        slotCount = java.util.Arrays.copyOf(slotCount, want);
        int[][] bigger = new int[want][];
        System.arraycopy(slots, 0, bigger, 0, slots.length);
        for (int i = slots.length; i < want; i++) bigger[i] = new int[SectionBatch.SLOTS];
        slots = bigger;
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
        if (meshes != null) {
            meshes.dispose();
            meshes = null;
        }
        for (GlSectionArena arena : arenas.values()) arena.close();
        arenas.clear();
        arenaCount = 0;
        seen.clear();
        if (program != null) {
            program.close();
            program = null;
        }
        if (atlasTexture >= 0) {
            glDeleteTextures(atlasTexture);
            atlasTexture = -1;
            atlasRevision = -1;
        }
        if (firstScratch != null) {
            MemoryUtil.memFree(firstScratch);
            firstScratch = null;
        }
        if (countScratch != null) {
            MemoryUtil.memFree(countScratch);
            countScratch = null;
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
