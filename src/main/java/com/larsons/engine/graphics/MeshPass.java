package com.larsons.engine.graphics;

import java.awt.image.BufferedImage;
import java.util.List;

/**
 * A backend that can draw <b>arbitrary triangle meshes</b> in three dimensions,
 * with a depth buffer.
 *
 * <h2>Why this exists beside {@link TerrainPass}</h2>
 *
 * <p>{@code TerrainPass} draws <em>a voxel world</em>: it takes a
 * {@link com.larsons.engine.graphics.chunk.SectionRenderList}, which is a list
 * of 16³ sections produced by a cave-aware graph walk, and it is exactly the
 * right shape for the game the engine was built around. It is the wrong shape
 * for a game whose ground is a continuous heightfield meshed into triangles —
 * there are no sections, no cave walk and no block atlas, and pretending
 * otherwise would mean inventing all three.
 *
 * <p>So this is the same idea one level more general: <em>here are some
 * triangles, here is a camera, put them on the screen with a depth test</em>.
 * Everything hard — generating the world, meshing it, deciding what is in view
 * — stays on the CPU in backend-neutral code that can be tested on a machine
 * with no graphics card, exactly as it does for the block path. What is left
 * for a backend is upload and draw.
 *
 * <p><b>The vertex format is deliberately the block renderer's</b>: three
 * position floats, two texture floats, one packed {@code 0xAARRGGBB} per
 * vertex, positions relative to the mesh's own origin. A GL backend can
 * therefore use the shader and the buffer layout it already has, and the two
 * paths cannot drift apart in how they fog, shade or alpha-test.
 *
 * <p>A renderer that cannot do this returns {@code null} from
 * {@link TerrainPass#meshPass()} and the caller draws through its own painter
 * instead — which is what keeps the JDK-only build working (invariant 4).
 */
public interface MeshPass {

    /**
     * One mesh to draw this frame.
     *
     * @param key         stable identity of the source — a chunk, an animal, a
     *                    structure. Buffers are cached under it, so a mesh that
     *                    has not changed is uploaded once and drawn thereafter
     * @param revision    which build of that source this is; a change is what
     *                    triggers a re-upload
     * @param vertices    five floats per vertex: {@code x, y, z, u, v}
     * @param colours     one packed {@code 0xAARRGGBB} per vertex
     * @param vertexCount how many vertices are actually used
     * @param originX     world position the vertices are relative to
     * @param translucent whether it must be drawn after everything opaque
     */
    record Draw(long key, int revision, float[] vertices, int[] colours, int vertexCount,
                double originX, double originY, double originZ, boolean translucent) {

        /** Triangles in this mesh. */
        public int triangleCount() { return vertexCount / 3; }

        public boolean isEmpty() { return vertexCount == 0; }
    }

    /**
     * Hand over the texture every mesh samples.
     *
     * <p>Cheap to call with an unchanged image: implementations compare
     * {@code revision} and do nothing when it matches, so a caller can pass its
     * atlas every frame without thinking about it.
     */
    void setTexture(BufferedImage atlas, int revision);

    /**
     * Draw a frame's worth of meshes.
     *
     * <p>Opaque first with depth writes on, in whatever order they arrive —
     * that is what the depth test is for. Then anything see-through, sorted far
     * to near with depth writes off, because blending is not commutative. The
     * implementation does the sorting; the caller does not have to.
     *
     * @param eye      where the camera is
     * @param fogArgb  the colour distance fades into, matching the sky
     * @param fogStart where fog begins, in world units
     * @param fogEnd   where it is total, in world units
     */
    void draw(List<Draw> draws, EyeCamera eye, int fogArgb, double fogStart, double fogEnd);

    /**
     * Forget the buffers cached under these keys.
     *
     * <p>Called when the caller drops a chunk. Video memory has no garbage
     * collector, and a player who walks a long way would otherwise leave a
     * buffer on the card for every piece of ground they have ever looked at.
     */
    void discard(List<Long> keys);

    /**
     * What the last frame's meshes cost the graphics card, in milliseconds, or
     * {@code 0} when the backend cannot say.
     *
     * <p>The only honest measure of how much world a machine can draw: frame
     * time includes everything else, and a CPU-bound frame hides a GPU that has
     * room to spare.
     */
    default double lastFrameMillis() { return 0; }

    /** How many meshes were uploaded on the last frame; for the debug overlay. */
    default int uploadsLastFrame() { return 0; }
}
