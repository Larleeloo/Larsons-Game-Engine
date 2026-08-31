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
     * How many point lights a frame may carry.
     *
     * <p><b>A shared constant rather than two numbers that have to match.</b>
     * A backend sizes a uniform array with it and the caller sizes the list it
     * sends with it; the two disagreeing is a shader that silently drops the
     * last few lights of a camp, which is exactly the class of bug nobody finds
     * by looking. Sixteen is what a camp with a fire, four lanterns and a
     * mutant on the ridge actually needs, and it is small enough that a
     * fragment walking all of them is a handful of instructions.
     */
    int MAX_LIGHTS = 16;

    /**
     * How much of a light a surface facing away from it still receives.
     *
     * <p><b>On the seam rather than in either implementation, because both have
     * to use it.</b> A wrap term that differed between the two would be a
     * campfire that lights the underside of a branch on a machine with a driver
     * and not on one without, which is the class of difference nobody notices
     * until they are comparing screenshots.
     *
     * <p>Why there is a wrap term at all: pure Lambert on flat-shaded low-poly
     * geometry is black on everything not squarely facing the flame, and a wood
     * at night is mostly surfaces that are not. A third of the light reaching
     * the back of things is what makes a fire look like a fire in a clearing
     * rather than a spotlight.
     */
    double LIGHT_WRAP = 0.32;

    /**
     * One point light, in world coordinates.
     *
     * <p>Positions are {@code double} and absolute for the same reason a
     * {@link Draw}'s origin is: a world with no edge puts a campfire tens of
     * thousands of metres out, and the subtraction that makes that expressible
     * in {@code float} is the backend's to do, once, against the eye it is
     * already given.
     *
     * <p>{@code radius} is where the light reaches nothing at all, not a
     * half-life: the falloff is compact, so a light is either inside a
     * fragment's reckoning or costs it nothing. {@code intensity} is what it
     * contributes at the source, on the same scale as the daylight multiplier —
     * {@code 1} is "as bright as noon, right next to it".
     */
    record Light(double x, double y, double z, float r, float g, float b,
                 float radius, float intensity) {

        /** A light of a packed {@code 0xRRGGBB} colour. */
        public static Light of(double x, double y, double z, int rgb, double radius,
                               double intensity) {
            return new Light(x, y, z, ((rgb >> 16) & 0xFF) / 255f,
                    ((rgb >> 8) & 0xFF) / 255f, (rgb & 0xFF) / 255f,
                    (float) radius, (float) intensity);
        }
    }

    /**
     * The light the next {@link #draw} is lit by: the sky's own colour, and the
     * point lights burning in the world.
     *
     * <p><b>Why this is a call and not part of the vertex format.</b>
     * Everything a mesh knows about light is baked into its vertex colours when
     * it is meshed — which is what lets a chunk be uploaded once and drawn for
     * as long as it is in view — and a lantern somebody is <em>carrying</em>
     * moves every frame. Re-meshing a forest because a walker took a step is
     * not a lighting model, it is a stutter. So the static half stays baked and
     * the moving half arrives here, as uniforms, and is applied per fragment by
     * the card: the geometry never changes, and the light does.
     *
     * <p>The default does nothing, which is what keeps this optional. A backend
     * that has not implemented it draws exactly what it drew before, and a
     * caller that never calls it gets neutral daylight and no lamps.
     *
     * @param lights up to {@link #MAX_LIGHTS} of them; anything beyond is the
     *               caller's to have already thrown away, nearest and brightest
     *               first
     * @param dayR   the hour's own light, as a per-channel multiplier — the
     *               same three numbers {@code WatchClock.lightTint} hands the
     *               painter, so both paths shade the same world at the same
     *               hour
     */
    default void setLighting(List<Light> lights, float dayR, float dayG, float dayB) { }

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
