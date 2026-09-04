package com.larsons.engine.gl;

import com.larsons.engine.graphics.EyeCamera;
import com.larsons.engine.graphics.LightCull;
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
 * <h2>…and a third, when there is a sun</h2>
 *
 * <p>Before either of those, the opaque half goes up once more through
 * {@link GlShadowMap}: the same buffers, the same frame, seen from the sun and
 * writing nothing but depth. That is the one thing a fragment cannot work out
 * about itself — whether something else is in the way — and there is no trick
 * that avoids drawing the world twice for it.
 *
 * <p><b>It is skipped whenever it would not be seen</b>, which is most of the
 * time: at night, under a storm, under water, and for every mesh outside the
 * two hundred metres of world the map covers. When it is skipped the frame is
 * the frame this class drew before the pass existed, to the instruction — one
 * uniform switches the lookup off in the shader, and nothing else changes.
 *
 * <p><b>The buffers are therefore resolved before either pass runs.</b> That is
 * the only structural change the shadow pass forced: uploads are bounded per
 * frame, and doing them inside the first pass would let the shadow map hold a
 * mesh the main pass had to skip — a tree casting a shadow it is not standing
 * in. See {@link #prepare}.
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

    /**
     * The sun and the air, held between {@link #setSky} and {@link #draw} for
     * the same reason the lights are.
     */
    private Sky sky = Sky.PLAIN;

    /**
     * The world again, from the sun — see {@link GlShadowMap}.
     *
     * <p>Owned here rather than by the target because it is a consequence of
     * <em>this</em> pass's geometry: what casts a shadow is exactly what this
     * class was handed, and nothing else in a frame knows the list.
     */
    private final GlShadowMap shadows = new GlShadowMap();

    /**
     * …and the world again from the brightest lamp, after dark — see
     * {@link GlLampShadow}.
     *
     * <p>The two are all but exclusive in practice: the sun's map is drawn
     * while there is a sun to cast and this one only once there is not, so a
     * frame pays for one shadow pass and not two. They are separate objects
     * rather than one because nothing else about them is the same shape — one
     * box against six, snapped world texels against a fixed reach, redrawn by
     * walking against redrawn by lighting a fire.
     */
    private final GlLampShadow lampShadows = new GlLampShadow();

    /** How much of the lamp's own light a shadow of it takes away. */
    private static final float LAMP_SHADOW = 0.88f;

    /**
     * How dark it has to be before a lamp's shadow is worth drawing.
     *
     * <p>Measured against the hour's own multiplier, which is what the shader
     * scales the whole ambient hemisphere by: {@code WatchClock.daylight} runs
     * from about a quarter at midnight to one at noon, so this ramp is dusk. A
     * fire at noon casts a shadow that is a percent of the frame and costs six
     * faces of depth to draw; the same fire at midnight is the only light there
     * is. Ramped rather than switched so there is no frame where the shadows of
     * a camp appear all at once.
     */
    private static final double LAMP_SHADOW_DARK = 0.34, LAMP_SHADOW_LIGHT = 0.62;

    /**
     * Draws that have a buffer on the card and can be issued — <b>resolved
     * once and then drawn twice.</b>
     *
     * <p>Before shadows there was one pass and the upload could happen inside
     * it. There are two now, and both of them need the same buffer for the same
     * mesh: uploading during the first would spend the frame's upload budget on
     * the shadow map and leave the main pass drawing a mesh the shadow pass
     * had and it did not. So the budget is spent once, up front, and both
     * passes walk the answer.
     */
    private final List<Ready> ready = new ArrayList<>();
    private int readyCount;
    private int opaqueReady;

    private int texture = -1;
    private int textureRevision = -1;

    /** Whether the last frame actually redrew either map; for the debug readout. */
    private boolean shadowsDrawn;
    private boolean lampShadowsDrawn;

    /**
     * How many of a frame's lamps are allowed to light the <em>air</em>.
     *
     * <p><b>The one term in the whole shader whose cost a cull cannot bound.</b>
     * A lamp lights the surface of the few meshes near it and nothing else, so
     * {@link LightCull#touchesBox} throws nearly all of that away — but a lamp
     * lights the air along every view ray that passes near it, and when you are
     * standing <em>inside</em> one, which is what standing at your own campfire
     * means, that is every ray in the frame. Measured at a little under two
     * milliseconds per lamp per frame at 720p, and no geometry test can remove
     * it, because the light really is there.
     *
     * <p>So it is rationed instead, by the same measure {@code LightField} uses
     * when it caps a frame at sixteen: how much lit air each lamp can put in
     * front of the camera. Six is a fire and five lanterns, which is a large
     * camp; what is dropped beyond that is the seventh-brightest glow in a pool
     * of six brighter ones, and the pop as one swaps for another is a few
     * percent of a soft gradient. Every one of them still lights the ground.
     */
    private static final int MAX_AIR_LIGHTS = 8;

    /**
     * Scratch for {@link #chooseLights}: which of the frame's lamps this mesh
     * wants, the ones lighting its surface first.
     */
    private final int[] lightOrder = new int[MAX_LIGHTS];
    private int surfaceLights, airLights;
    private long lightSubset;

    /**
     * Which frame lamp has the cube map, and how hard its shadow is shaded.
     *
     * <p>{@code −1} and {@code 0} in daylight and whenever no lamp is near
     * enough to be worth a pass, which switches the whole term off.
     */
    private int shadowingLamp = -1;
    private float lampShadow;

    /** Whether this mesh in particular is lit by that lamp; see {@link #issue}. */
    private boolean lampShadowed;

    /** Scratch for the lamp pass, so a frame of it allocates nothing. */
    private final float[] lampMvp = new float[16];
    private final float[] lampCurve = new float[4];

    /** Which lamps are in the frame's air budget; see {@link #MAX_AIR_LIGHTS}. */
    private final boolean[] litsAir = new boolean[MAX_LIGHTS];
    private final double[] airScores = new double[MAX_LIGHTS];

    /**
     * Whether anything in the atlas is see-through enough to be discarded.
     *
     * <p><b>Discovered rather than assumed, and it decides how expensive the
     * shadow pass is.</b> A depth-only pass that can {@code discard} cannot use
     * a card's early-depth rejection, which is the thing that makes a depth
     * pass two to four times cheaper than a shaded one — so the alpha test in
     * {@link GlShadowMap} is worth paying for only when some texel would
     * actually fail it. The Field Guide's atlas has no holes at all
     * ({@code WatchMaterials} paints every opaque material at full alpha), so
     * on that world this is always false and the shadow pass gets an empty
     * fragment shader; a texture pack that adds a cutout turns the test back on
     * by itself.
     */
    private boolean atlasHasCutouts;
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

    /**
     * One mesh, its buffer, and the matrix the sun sees it through.
     *
     * <p>Mutable and pooled rather than a record, because there is one of these
     * per mesh per frame and a frame holds several hundred meshes. See
     * {@link #ready}.
     */
    private static final class Ready {
        Draw draw;
        Buffer buffer;
        /**
         * How the sun sees this mesh, or {@code null} when nothing is casting
         * at all this frame.
         *
         * <p><b>Computed for every mesh, not only the ones that cast</b>, and
         * that is not an oversight. The main pass uses this matrix to look
         * <em>up</em> the shadow map, and a mesh outside the map has to be told
         * so by landing outside it — which only the real matrix does. Handing
         * that mesh an identity instead would put its own local coordinates
         * into the lookup, and a mesh whose vertices happen to be small numbers
         * (an animal, the hands) would sample the map at whatever that pointed
         * at and wear somebody else's shadow.
         */
        final float[] lightMvp = new float[16];
        /** Whether {@link #lightMvp} has been filled for this frame. */
        boolean lit;
        /** Whether it is near enough the box to put anything <em>into</em> it. */
        boolean casts;
        /** …and the same question about the lamp's cube. */
        boolean lampCasts;
        /** Its bounding ball in the world, which both of those are asked of. */
        double ballX, ballY, ballZ, ballRadius;
    }

    @Override
    public void setTexture(BufferedImage atlas, int revision) {
        if (atlas == null || revision == textureRevision) return;
        int w = atlas.getWidth(), h = atlas.getHeight();
        int[] argb = atlas.getRGB(0, 0, w, h, null, 0, w);
        ByteBuffer pixels = MemoryUtil.memAlloc(w * h * 4);
        boolean cutouts = false;
        int cut = Math.round(ALPHA_CUT * 255);
        try {
            for (int p : argb) {
                pixels.put((byte) ((p >> 16) & 0xFF));
                pixels.put((byte) ((p >> 8) & 0xFF));
                pixels.put((byte) (p & 0xFF));
                pixels.put((byte) ((p >>> 24) & 0xFF));
                // Free, because this loop is already walking every texel, and
                // it saves the shadow pass a fragment shader. See
                // atlasHasCutouts.
                if ((p >>> 24) < cut) cutouts = true;
            }
            atlasHasCutouts = cutouts;
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
    public void setSky(Sky frameSky) {
        sky = frameSky == null ? Sky.PLAIN : frameSky;
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
        // <b>And near to far for the opaque half — not for correctness, for
        // cost.</b> A depth test makes opaque geometry order-independent, which
        // is why this list arrived in whatever order the caller built it in;
        // but "the answer does not depend on the order" is not "the work does
        // not". Drawn far first, a hillside is shaded in full and then covered
        // by the tree in front of it, and everything that shader did — the
        // hemisphere, the shadow lookup, every lamp in the frame, the fog — is
        // thrown away. Drawn near first, the depth buffer already holds the
        // tree and the hillside behind it is rejected before it is shaded at
        // all.
        //
        // In a wood, which is nothing but things in front of other things, that
        // is most of the fragments in the frame. One sort of a few hundred
        // entries buys it.
        opaque.sort(Comparator.comparingDouble((Draw d) -> distanceSquared(d, eye)));

        // Everything the card can actually draw this frame, resolved before
        // either pass touches it. Opaque first so that when the upload budget
        // runs out it is the water that waits.
        boolean casting = shadows.aim(sky, eye, atlasHasCutouts);
        // Whether the sun's box has moved must be settled *before* the casters
        // are chosen, because which meshes are in the box is a question about
        // which box. See GlShadowMap.adoptBox.
        boolean redraw = casting && shadows.boxMoved();
        if (redraw) shadows.adoptBox();

        // …and the same three steps for the fire, which takes over from the sun
        // after dark. Its strength is what decides whether the pass happens at
        // all: nothing is drawn in daylight, because nothing would be seen.
        float lampStrength = lampShadowStrength();
        boolean lampCasting = lampShadows.aim(lights, eye, lampStrength,
                atlasHasCutouts) >= 0;
        boolean lampRedraw = lampCasting && lampShadows.lampMoved();
        if (lampRedraw) lampShadows.adoptLamp();

        readyCount = 0;
        for (Draw draw : opaque) prepare(draw, casting, lampCasting);
        opaqueReady = readyCount;
        for (Draw draw : translucent) prepare(draw, casting, lampCasting);

        // …and then whether what is standing in that box has changed. Standing
        // still in a wood, neither has, and the map already on the card is the
        // one this frame wants. See GlShadowMap.boxMoved.
        long casters = casting ? casterSignature() : 0;
        if (casting && !redraw && shadows.castersChanged(casters)) redraw = true;
        shadowsDrawn = redraw;
        if (redraw) {
            shadows.openPass(casters);
            try {
                glActiveTexture(GL_TEXTURE0 + GlTerrainProgram.UNIT_ATLAS);
                glBindTexture(GL_TEXTURE_2D, texture);
                // Only the opaque half, and only what asked to cast. A sheet of
                // water casting a shadow of itself onto the lake bed is not a
                // shadow, it is a lake that has gone dark.
                for (int i = 0; i < opaqueReady; i++) {
                    Ready row = ready.get(i);
                    if (row.casts) {
                        shadows.cast(row.buffer.vao, row.buffer.vertexCount, row.lightMvp);
                    }
                }
            } finally {
                // Whatever happened, the caller's framebuffer goes back.
                // Everything after this — including the outer method's own
                // state restoration — is written on the assumption that it is
                // drawing into the frame, and an exception that left the sun's
                // depth texture bound would send the rest of the frame into it.
                shadows.end();
            }
        }

        // The fire's six faces, on the same terms: only when what is standing
        // round it has changed, and only the meshes it reaches. A camp you have
        // sat down at pays for this once.
        long lampCasters = lampCasting ? lampCasterSignature() : 0;
        if (lampCasting && !lampRedraw && lampShadows.castersChanged(lampCasters)) {
            lampRedraw = true;
        }
        lampShadowsDrawn = lampRedraw;
        if (lampRedraw) {
            lampShadows.openPass(lampCasters);
            try {
                glActiveTexture(GL_TEXTURE0 + GlTerrainProgram.UNIT_ATLAS);
                glBindTexture(GL_TEXTURE_2D, texture);
                for (int side = 0; side < 6; side++) {
                    lampShadows.openFace(side);
                    for (int i = 0; i < opaqueReady; i++) {
                        Ready row = ready.get(i);
                        if (!row.lampCasts) continue;
                        // Six faces of a cube tile the whole sphere, so a mesh
                        // that is in the lamp's reach at all is in one of them
                        // — and usually only one. Without this test each of
                        // those meshes would be submitted six times to be
                        // clipped away five.
                        if (!lampShadows.faceCasts(side, row.ballX, row.ballY,
                                row.ballZ, row.ballRadius)) {
                            continue;
                        }
                        lampShadows.matrixInto(side, row.draw.originX(),
                                row.draw.originY(), row.draw.originZ(), lampMvp);
                        lampShadows.cast(row.buffer.vao, row.buffer.vertexCount, lampMvp);
                    }
                }
            } finally {
                lampShadows.end();
            }
        }

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
        rationAir(eye);
        // …and the sun, the weather and the grade, likewise once. The shadow
        // term switches itself off when there is no map to read: see
        // GlTerrainProgram.setSky.
        program.setSky(sky, eye, casting ? shadows.texel() : 0f,
                shadows.flatBias(), shadows.slopeBias());
        glActiveTexture(GL_TEXTURE0 + GlTerrainProgram.UNIT_SHADOW);
        // The depth map, or nothing at all. Never the atlas: that unit is a
        // `sampler2DShadow` now, and a colour texture bound to one is a
        // mismatched sampler — harmless while the shader does not read it, and
        // exactly the sort of thing a driver is entitled to complain about
        // whether or not it is read.
        glBindTexture(GL_TEXTURE_2D, casting ? shadows.texture() : 0);
        glActiveTexture(GL_TEXTURE0 + GlTerrainProgram.UNIT_LAMP_SHADOW);
        glBindTexture(GL_TEXTURE_CUBE_MAP, lampCasting ? lampShadows.texture() : 0);
        glActiveTexture(GL_TEXTURE0 + GlTerrainProgram.UNIT_ATLAS);
        glBindTexture(GL_TEXTURE_2D, texture);
        // The fire's own arithmetic, once for the frame; which meshes are
        // shadowed by it is settled per mesh, in issue().
        shadowingLamp = lampCasting ? lampShadows.lamp() : -1;
        lampShadow = lampCasting ? lampStrength : 0f;
        if (lampCasting) {
            lampShadows.depthCurveInto(lampCurve);
            program.setLampShadow(eye, lampCurve, lampShadows.slopeBias());
        }

        for (int i = 0; i < opaqueReady; i++) issue(ready.get(i), projection, eye);

        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glDepthMask(false);
        program.setAlphaCut(0f);
        for (int i = opaqueReady; i < readyCount; i++) issue(ready.get(i), projection, eye);

        evict();
    }

    /**
     * What is standing in the sun's box, as one number.
     *
     * <p>Combined commutatively — added rather than folded in sequence —
     * because the order meshes arrive in is the order of a hash map's values
     * and can change between frames without anything having moved. An
     * order-sensitive summary would report a change that was not one, which
     * costs a redraw rather than correctness, but would cost it constantly.
     *
     * <p>What has to be in it: which meshes cast, which build of each, and
     * where each one is. A chunk that finishes re-meshing changes its revision;
     * an animal that walks changes its origin; a tree that comes into range
     * changes the count. Any of those changes the map.
     */
    private long casterSignature() {
        long mixed = 0;
        for (int i = 0; i < opaqueReady; i++) {
            Ready row = ready.get(i);
            if (!row.casts) continue;
            long one = row.draw.key() * 0x9E3779B97F4A7C15L;
            one ^= (long) row.draw.revision() * 0xC2B2AE3D27D4EB4FL;
            one ^= Double.doubleToLongBits(row.draw.originX()) * 0x165667B19E3779F9L;
            one ^= Double.doubleToLongBits(row.draw.originY()) * 0x27D4EB2F165667C5L;
            one ^= Double.doubleToLongBits(row.draw.originZ()) * 0x85EBCA77C2B2AE63L;
            mixed += one ^ (one >>> 29);
        }
        return mixed;
    }

    /**
     * …and the same summary of what is standing round the lamp.
     *
     * <p>A separate number from {@link #casterSignature} because it is a
     * separate set: the sun's box is two hundred metres across and the lamp's
     * is twelve, so a tree finishing its mesh on the far side of the map
     * changes one and not the other, and sharing a signature would redraw six
     * faces of cube every time it did.
     */
    private long lampCasterSignature() {
        long mixed = 0;
        for (int i = 0; i < opaqueReady; i++) {
            Ready row = ready.get(i);
            if (!row.lampCasts) continue;
            long one = row.draw.key() * 0x9E3779B97F4A7C15L;
            one ^= (long) row.draw.revision() * 0xC2B2AE3D27D4EB4FL;
            one ^= Double.doubleToLongBits(row.draw.originX()) * 0x165667B19E3779F9L;
            one ^= Double.doubleToLongBits(row.draw.originY()) * 0x27D4EB2F165667C5L;
            one ^= Double.doubleToLongBits(row.draw.originZ()) * 0x85EBCA77C2B2AE63L;
            mixed += one ^ (one >>> 29);
        }
        return mixed;
    }

    /**
     * How visible a lamp's own shadow would be, this hour.
     *
     * <p>Measured against {@code uDaylight} — the hour as a per-channel
     * multiplier — because that is precisely what the shader scales the whole
     * ambient hemisphere by, and a lamp's shadow is only as visible as the lamp
     * is against everything else in the frame. Green-weighted, which is the eye
     * talking rather than the arithmetic: the night's tint is a cold blue and
     * averaging the three channels would call it brighter than it looks.
     */
    private float lampShadowStrength() {
        double luma = 0.2126 * dayR + 0.7152 * dayG + 0.0722 * dayB;
        double t = (LAMP_SHADOW_LIGHT - luma)
                / (LAMP_SHADOW_LIGHT - LAMP_SHADOW_DARK);
        t = t < 0 ? 0 : Math.min(t, 1);
        return (float) (LAMP_SHADOW * t * t * (3 - 2 * t));
    }

    /**
     * Get a mesh's buffer onto the card if it is not there, and note it for
     * both passes.
     *
     * <p>What used to be the front half of {@code issue}. The upload budget is
     * spent here and only here, which is what stops the shadow pass and the
     * main pass disagreeing about which meshes exist this frame.
     */
    private void prepare(Draw draw, boolean casting, boolean lampCasting) {
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
        if (buffer.vao < 0 || buffer.vertexCount == 0) return;
        if (readyCount == ready.size()) ready.add(new Ready());
        Ready row = ready.get(readyCount++);
        row.draw = draw;
        row.buffer = buffer;
        // One matrix per mesh, computed once and used by both passes — the
        // shadow map is written through it and then read back through it, and
        // deriving it twice is how the two come to differ by a rounding.
        row.lit = casting;
        if (casting) {
            // Into the row's own array rather than out of a fresh one: this
            // runs once per mesh per frame, and a frame holds several hundred
            // meshes.
            shadows.matrixInto(draw.originX(), draw.originY(), draw.originZ(),
                    row.lightMvp);
        }
        // Casting is the expensive half and is worth culling: at a
        // five-hundred-metre render distance most of a frame is nowhere near
        // the sun's two hundred metres of map, and submitting it would be
        // drawing the horizon twice to have it clipped away.
        row.casts = casting && draw.casts()
                && shadows.casts(draw.originX(), draw.originY(), draw.originZ());
        // The lamp's reach is twelve metres rather than two hundred, so its
        // cull is the mesh's real ball against a sphere rather than the sun's
        // generous margin — a campfire's casters are one chunk and its trees.
        Bounds bounds = draw.bounds();
        row.ballX = draw.originX() + bounds.centreX();
        row.ballY = draw.originY() + bounds.centreY();
        row.ballZ = draw.originZ() + bounds.centreZ();
        row.ballRadius = bounds.radius();
        row.lampCasts = lampCasting && draw.casts()
                && lampShadows.casts(row.ballX, row.ballY, row.ballZ, row.ballRadius);
    }

    private static double distanceSquared(Draw draw, EyeCamera eye) {
        double dx = draw.originX() - eye.x();
        double dy = draw.originY() - eye.y();
        double dz = draw.originZ() - eye.z();
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * Which of the frame's lamps are allowed to light the air this frame.
     *
     * <p>A selection sort over at most sixteen entries, once a frame — the list
     * is tiny and the alternative is sorting an index array, which allocates.
     * See {@link #MAX_AIR_LIGHTS} for why there is a budget at all and
     * {@link LightCull#airScore} for what it is spent on.
     */
    private void rationAir(EyeCamera eye) {
        int count = lights.size();
        for (int i = 0; i < count; i++) {
            airScores[i] = LightCull.airScore(lights.get(i), eye.x(), eye.y(), eye.z());
            litsAir[i] = false;
        }
        for (int taken = 0; taken < Math.min(MAX_AIR_LIGHTS, count); taken++) {
            int best = -1;
            for (int i = 0; i < count; i++) {
                if (!litsAir[i] && (best < 0 || airScores[i] > airScores[best])) best = i;
            }
            if (best < 0) break;
            litsAir[best] = true;
        }
    }

    /**
     * Which of the frame's lamps can touch this mesh, and how.
     *
     * <p>Two lists in one array, the surface-lit lamps first — see
     * {@link LightCull} for why the two questions have different answers and
     * {@code GlTerrainProgram}'s two loops for what is done with the split. The
     * subset is also summarised as a bitmask so that the long runs of meshes
     * wanting the same lamps, which is most of a frame and usually means none
     * at all, cost one uniform upload between them.
     */
    private void chooseLights(Ready row, EyeCamera eye) {
        surfaceLights = 0;
        airLights = 0;
        lightSubset = 0;
        lampShadowed = false;
        if (lights.isEmpty()) return;
        double centreX = row.ballX, centreY = row.ballY, centreZ = row.ballZ;
        double radius = row.ballRadius;

        long surfaceMask = 0, airMask = 0;
        // <b>The lamp with the cube map goes in first, and that is what makes
        // slot 0 mean something.</b> The shader takes its shadow back out of
        // slot 0 after the loop rather than testing an index inside it — see
        // the note there — which is only sound if this is the one place that
        // decides where it lands.
        if (shadowingLamp >= 0 && LightCull.touchesBox(lights.get(shadowingLamp),
                centreX, centreY, centreZ, radius)) {
            lightOrder[surfaceLights++] = shadowingLamp;
            surfaceMask |= 1L << shadowingLamp;
            lampShadowed = true;
        }
        // Two passes so that the surface-lit ones land first without a sort.
        for (int i = 0; i < lights.size(); i++) {
            if ((surfaceMask & (1L << i)) != 0) continue;
            if (LightCull.touchesBox(lights.get(i), centreX, centreY, centreZ, radius)) {
                lightOrder[surfaceLights++] = i;
                surfaceMask |= 1L << i;
            }
        }
        airLights = surfaceLights;
        for (int i = 0; i < lights.size(); i++) {
            if ((surfaceMask & (1L << i)) != 0) continue;
            if (litsAir[i] && LightCull.touchesWedge(lights.get(i),
                    eye.x(), eye.y(), eye.z(), centreX, centreY, centreZ, radius)) {
                lightOrder[airLights++] = i;
                airMask |= 1L << i;
            }
        }
        lightSubset = (surfaceMask << 32) | airMask;
    }

    /** Set the matrices for one prepared mesh and issue it. */
    private void issue(Ready row, Mat4 projection, EyeCamera eye) {
        Draw draw = row.draw;
        chooseLights(row, eye);
        program.setMeshLights(lightOrder, surfaceLights, airLights, lightSubset);
        program.setMeshLampShadow(lampShadowed ? lampShadow : 0f);

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
        program.setLightMatrix(row.lit ? row.lightMvp : null);
        // Where in the world this mesh is, for the two things a fragment cannot
        // work out from an eye-relative position: how high above the mist it
        // stands, and where it sits in the drift. Wrapped here, in double,
        // because a world coordinate out at the edge of this world does not fit
        // in a float at the precision a fog bank needs. See DRIFT_PERIOD.
        program.setMeshOrigin(wrapped(draw.originX()), wrapped(draw.originY()),
                draw.originZ());

        glBindVertexArray(row.buffer.vao);
        glDrawArrays(GL_TRIANGLES, 0, row.buffer.vertexCount);
    }

    /** A world coordinate folded into the drift's own period, never negative. */
    private static double wrapped(double v) {
        double period = GlTerrainProgram.DRIFT_PERIOD;
        double folded = v % period;
        return folded < 0 ? folded + period : folded;
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

    @Override
    public boolean redrewShadowsLastFrame() { return shadowsDrawn || lampShadowsDrawn; }

    /**
     * …and whether it was the fire's six faces in particular.
     *
     * <p>Not on the seam: the readout above wants "a shadow pass happened",
     * which is the frame's cost, while the tests want to know <em>which</em>,
     * because the two are cached against different things and a test that
     * could not tell them apart would pass on a lamp map redrawn every frame.
     */
    boolean redrewLampShadowsLastFrame() { return lampShadowsDrawn; }

    /** Release everything on the card. */
    void dispose() {
        for (Buffer buffer : buffers.values()) release(buffer);
        buffers.clear();
        ready.clear();
        readyCount = 0;
        opaqueReady = 0;
        shadows.close();
        lampShadows.close();
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
