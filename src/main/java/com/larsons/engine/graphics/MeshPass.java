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
     * <b>The air and the sun</b> — everything about a frame's light that is not
     * a lamp.
     *
     * <p>{@link #setLighting} carries the two things a picture cannot be drawn
     * without: how bright the hour is, and what is burning. This carries the
     * things that make it look like somewhere. They are separate calls because
     * they are separately optional: a backend that implements neither draws
     * what it always drew, and a backend that implements only the first gets a
     * correct, flat world at the right time of day.
     *
     * <p><b>Every field is a description of the world rather than an
     * instruction to a shader.</b> "The sun is over there and this colour",
     * not "multiply by 0.7" — because the two backends have wildly different
     * budgets and the one with a graphics card should be allowed to spend it.
     * The painter answers this with a flat multiplier it already had; the card
     * answers it with a per-fragment hemisphere, a shadow map and lit air. Both
     * are drawing the same described world.
     *
     * @param sunX      the direction the sun is <em>in</em>, as a unit vector in
     *                  world coordinates; its {@code z} is negative when the
     *                  sun is down
     * @param sunR      the sun's own colour, already scaled by how much of it
     *                  there is — {@code 0,0,0} at night and under a storm
     * @param skyR      what a surface facing straight up receives from the sky,
     *                  as a multiplier around {@code 1}
     * @param groundR   …and what one facing straight down receives back off the
     *                  ground. The pair is a two-colour ambient: it costs one
     *                  {@code mix} and it is the difference between a low-poly
     *                  world that reads as carved and one that reads as flat
     * @param shadow    how much of the sun a shadowed surface loses, {@code 0}
     *                  to {@code 1}. A backend with nowhere to put a shadow map
     *                  ignores it; {@code 0} means "do not spend the pass"
     * @param haze      weather's own thickness, as extinction per metre, on top
     *                  of whatever {@link #draw}'s fog range already does. This
     *                  is the number that makes fog <em>weather</em> rather
     *                  than a shorter view distance
     * @param hazeFloor the world height the ground fog pools at — mist sits in
     *                  the valleys and a ridge stands out of it, which is the
     *                  whole of why fog is worth having in a world with hills
     * @param hazeDepth how many metres above {@code hazeFloor} that mist takes
     *                  to thin out
     * @param scatter   how much of a lamp's light the air itself carries back
     *                  to the eye. Zero is a vacuum, where a campfire lights
     *                  only what it touches; anything above it is the broad
     *                  cone of glow you actually see round a fire at night
     * @param vibrance  one knob for the grade: {@code 0} leaves the colour
     *                  exactly as it was computed, and upwards of that lifts
     *                  saturation and rolls the highlights off so a lamp's own
     *                  pool keeps its colour instead of clipping to white
     * @param seconds   the drawing clock, for anything that drifts
     */
    record Sky(double sunX, double sunY, double sunZ,
               float sunR, float sunG, float sunB,
               float skyR, float skyG, float skyB,
               float groundR, float groundG, float groundB,
               float shadow, float haze, double hazeFloor, float hazeDepth,
               float scatter, float vibrance, float seconds) {

        /**
         * No sun, no weather, no grade — <b>the world exactly as it was drawn
         * before any of this existed.</b>
         *
         * <p>Load-bearing rather than a convenience: a backend shares one
         * shader between this path and the voxel world, whose shading is baked
         * into its vertex colours and must not be touched. Everything here is
         * therefore the identity of its own term, and a caller that never
         * mentions the sky gets it.
         */
        public static final Sky PLAIN = new Sky(0, 0, 1, 0, 0, 0, 1, 1, 1, 1, 1, 1,
                0, 0, 0, 1, 0, 0, 0);

        /** Whether there is any sun to cast anything. */
        public boolean sunUp() {
            return sunZ > 0 && (sunR > 0 || sunG > 0 || sunB > 0);
        }

        /** Whether a shadow map would earn its pass this frame. */
        public boolean castsShadows() {
            return shadow > 0.01f && sunUp();
        }
    }

    /**
     * Describe the sky the next {@link #draw} happens under.
     *
     * <p>Does nothing by default, which is what keeps it optional — see
     * {@link Sky#PLAIN} for what a caller that never calls this gets.
     */
    default void setSky(Sky sky) { }

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
