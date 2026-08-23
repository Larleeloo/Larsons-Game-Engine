package com.larsons.engine.graphics;

import com.larsons.engine.graphics.chunk.BlockAtlas;
import com.larsons.engine.graphics.chunk.SectionRenderList;

/**
 * A backend that can draw the world in three dimensions, with a depth buffer.
 *
 * <p><b>This is the seam the GPU terrain path arrives through</b>, and it is
 * narrow on purpose. Everything hard about drawing a voxel world — deciding
 * which sections exist, meshing them, culling them, ordering them — happens in
 * {@code com.larsons.engine.graphics.chunk} on the CPU, backend-agnostic and
 * testable without a graphics card. What is left for a backend is: take this
 * list of meshes and this matrix, and put the triangles on the screen with a
 * depth test. A renderer that cannot do that returns {@code null} from
 * {@link Renderer#terrainPass()} and the scene draws through
 * {@link SolidPainter} instead, which is the arrangement that keeps the
 * JDK-only build working.
 *
 * <p><b>Why it needs a depth buffer and the painter does not.</b> The painter
 * sorts every face of every frame because it has nothing else to sort with, and
 * that is precisely what stops its geometry from being cached: an order that
 * depends on where the camera is cannot be computed once. A depth test removes
 * the ordering requirement from opaque geometry entirely, which is what lets a
 * section's triangles go to the GPU once and stay there — and that is the whole
 * of why this path can hold a frame rate at a render distance the painter
 * cannot.
 */
public interface TerrainPass {

    /**
     * Hand over the block atlas. Called when it is built and whenever it
     * changes — an animated sheet, a texture pack reloaded — so the backend can
     * upload it. Cheap to call with an unchanged atlas: implementations compare
     * {@link BlockAtlas#revision()}.
     */
    void setAtlas(BlockAtlas atlas);

    /**
     * Draw a frame's worth of sections.
     *
     * <p>Opaque geometry first, near to far as the list already is, with depth
     * writes on; then anything see-through, far to near, with depth writes off
     * — the two render layers every voxel renderer ends up with, because a
     * depth test solves ordering for one of them and not the other.
     *
     * @param list      the sections to draw, nearest first
     * @param eye       where the camera is, for the model matrix of each section
     * @param fogArgb   the colour distance fades into, matching the painter's sky
     * @param fogStart  where fog begins, in world units
     * @param fogEnd    where it is total, in world units
     */
    void drawTerrain(SectionRenderList list, EyeCamera eye, int tileSize,
                     int fogArgb, double fogStart, double fogEnd);

    /**
     * What the last frame's terrain actually cost the graphics card, in
     * milliseconds, or {@code 0} when the backend cannot say.
     *
     * <p><b>The only honest measure of how much world a machine can draw, and
     * the reason it has to come from the backend.</b> Everything the CPU can
     * see about a draw call is how long it took to <em>queue</em> — a
     * {@code glDrawArrays} returns in microseconds whatever it costs to
     * execute — so a renderer deciding its own render distance from CPU
     * timings is reading an instrument that is wrong by two orders of magnitude
     * in the flattering direction. A ceiling picked by hand instead is wrong in
     * the other one: it is the same number on a laptop and on a card ten times
     * quicker, and the fast machine never finds out.
     *
     * <p>Measured, the radius grows until this says stop, which is a different
     * answer on every machine and the right one on each.
     */
    default double lastGpuMillis() { return 0; }

    /**
     * Whether this pass can actually draw.
     *
     * <p><b>The one question a caller must ask before standing the painter
     * down.</b> A scene that hands the blocks over stops drawing them itself, so
     * a pass that then declines — a shader the driver would not compile, a
     * texture that would not upload — takes the whole world with it rather than
     * degrading. There is no way to discover that from
     * {@link Renderer#terrainPass()}, which answers before a shader has ever
     * been asked to compile; this answers afterwards, and a backend that has
     * given up says so from then on.
     */
    default boolean available() { return true; }

    /**
     * Whether the depth buffer this pass wrote is available to whatever draws
     * next.
     *
     * <p>The billboards — actors, plants, the level's scenery — are drawn by
     * the 2D target after this, and they look right against the terrain only if
     * they are depth-tested against it. A backend that cannot offer that says
     * so, and the scene falls back to sorting them against the terrain itself.
     */
    default boolean sharesDepthWithSprites() { return false; }

    /** Release whatever the backend is holding — buffers, textures, programs. */
    void dispose();
}
