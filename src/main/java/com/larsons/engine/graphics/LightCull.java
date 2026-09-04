package com.larsons.engine.graphics;

/**
 * <b>Which lamps can touch which mesh</b> — the arithmetic that keeps a camp
 * from costing the whole screen.
 *
 * <h2>Why a frame's lights cannot simply all be sent</h2>
 *
 * <p>A fragment shader that walks a list walks all of it, for every fragment on
 * the screen. A campfire reaches twelve metres and the view reaches two hundred
 * and sixty, so on a frame with a camp in the corner of it the overwhelming
 * majority of fragments walk every lamp in the world, work out that each is far
 * too far away to matter, and throw the answer away. Measured, that is a little
 * over two milliseconds per lamp per frame at 720p, whatever the lamp is doing
 * — which is why a camp at night was the one place the frame rate fell over.
 *
 * <p>Branching out of the loop per fragment does not fix it and makes it worse:
 * it was tried, and it cost thirty-five percent more, because the branch stops
 * the compiler unrolling a loop whose body is a dozen instructions. The answer
 * has to be arrived at <em>before</em> the shader runs, per mesh, on the CPU,
 * exactly as {@code WatchRenderer} has always done it for the painter.
 *
 * <h2>Two questions, not one</h2>
 *
 * <p>A lamp affects a mesh in two quite different ways, and they cull
 * differently:
 *
 * <ol>
 *   <li><b>It lights the surface.</b> That depends on how far the lamp is from
 *       the mesh, and nothing else: a lamp whose sphere misses the mesh's box
 *       contributes exactly zero to every fragment of it. {@link #touchesBox}.</li>
 *   <li><b>It lights the air in front of the mesh.</b> That depends on how far
 *       the lamp is from the <em>view rays</em>, which is a different question
 *       with a different answer — a lantern at your feet lights the air in
 *       front of a hillside a hundred metres off, while contributing nothing at
 *       all to its surface. {@link #touchesWedge}.</li>
 * </ol>
 *
 * <p>The second is the looser test and contains the first, so a caller wanting
 * one list asks only {@code touchesWedge}. A caller that can afford two — the
 * GL backend orders its uniform array so the surface lights come first and runs
 * two loops — uses both, and a lamp you are standing on stops costing distant
 * ground its full price.
 *
 * <h2>Conservative, and deliberately so</h2>
 *
 * <p>Every test here may say yes when the true answer is no; none may say no
 * when the true answer is yes. A cull that is too generous costs a few
 * instructions; a cull that is too tight puts out a light somebody is looking
 * at, at a chunk boundary, as they walk. So the mesh is treated as its bounding
 * <em>sphere</em> rather than its box, and the wedge as a cone.
 */
public final class LightCull {

    private LightCull() {}

    /**
     * Whether a lamp's own sphere reaches a mesh at all.
     *
     * <p>Two spheres: the lamp's reach against the ball around the mesh. This
     * is what decides whether the lamp appears in the surface half of the
     * shader's loop.
     *
     * @param centreX the mesh's bounding centre, in world coordinates
     * @param radius  the radius of the ball around it
     */
    public static boolean touchesBox(MeshPass.Light light,
                                     double centreX, double centreY, double centreZ,
                                     double radius) {
        double dx = light.x() - centreX;
        double dy = light.y() - centreY;
        double dz = light.z() - centreZ;
        double reach = light.radius() + radius;
        return dx * dx + dy * dy + dz * dz <= reach * reach;
    }

    /**
     * How much air a lamp can light, from where the camera is standing.
     *
     * <p><b>For ranking, not for shading</b> — the shader works out the real
     * answer per fragment. This is only for deciding which lamps are worth
     * asking about at all, and it has to be the same shape as the thing it is
     * standing in for: a lamp's glow fills the view when you are inside it,
     * falls away as you step out, and is bounded by how far it reaches and how
     * hard it burns.
     *
     * <p>The same curve {@code LightField} ranks by when it caps a frame at
     * sixteen, for the same reason and deliberately so: a camp of forty
     * lanterns should lose the ones that do not matter, in the same order,
     * wherever the decision is taken.
     */
    public static double airScore(MeshPass.Light light,
                                  double eyeX, double eyeY, double eyeZ) {
        double dx = light.x() - eyeX, dy = light.y() - eyeY, dz = light.z() - eyeZ;
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double radius = Math.max(1e-4, light.radius());
        double outside = Math.max(0, distance - radius);
        return light.intensity() * radius / (1 + outside / radius);
    }

    /**
     * Whether a lamp can light the air anywhere between the eye and a mesh.
     *
     * <p><b>The wedge is the cone of view rays that end on the mesh</b>, and a
     * lamp matters to it if its sphere touches that cone anywhere along its
     * length. Two tests, both algebraic — no trigonometry, because this runs
     * once per mesh per lamp per frame and a frame holds a few hundred meshes:
     *
     * <ol>
     *   <li><b>Length.</b> Nothing past the far side of the mesh can scatter
     *       into a ray that stops there, so a lamp further from the eye than
     *       the mesh's far edge plus its own reach is out.</li>
     *   <li><b>Angle.</b> The mesh subtends a half-angle from the eye and so
     *       does the lamp; if the two directions differ by more than the sum,
     *       the lamp's sphere and the cone do not meet. Compared as cosines
     *       through the sum formula, so the whole thing is multiplies and one
     *       square root each.</li>
     * </ol>
     *
     * <p>Two cases skip straight to yes, and both are common rather than
     * pathological: <b>the eye inside the mesh's own ball</b>, where the cone
     * is every direction there is, and <b>the eye inside the lamp</b> — you are
     * standing at your own campfire — where every ray in the frame begins
     * inside the light and every one of them carries some of it.
     */
    public static boolean touchesWedge(MeshPass.Light light,
                                       double eyeX, double eyeY, double eyeZ,
                                       double centreX, double centreY, double centreZ,
                                       double radius) {
        double ux = centreX - eyeX, uy = centreY - eyeY, uz = centreZ - eyeZ;
        double toMesh = Math.sqrt(ux * ux + uy * uy + uz * uz);
        if (toMesh <= radius) return true;

        double vx = light.x() - eyeX, vy = light.y() - eyeY, vz = light.z() - eyeZ;
        double toLight = Math.sqrt(vx * vx + vy * vy + vz * vz);
        double reach = light.radius();
        if (toLight <= reach) return true;

        if (toLight > toMesh + radius + reach) return false;

        double sinMesh = radius / toMesh;
        double sinLight = reach / toLight;
        double cosMesh = Math.sqrt(Math.max(0, 1 - sinMesh * sinMesh));
        double cosLight = Math.sqrt(Math.max(0, 1 - sinLight * sinLight));
        // cos(a + b), where a and b are the two half-angles. Below −1 the pair
        // covers every direction and there is nothing left to exclude.
        double widest = cosMesh * cosLight - sinMesh * sinLight;
        if (widest <= -1) return true;
        double between = (ux * vx + uy * vy + uz * vz) / (toMesh * toLight);
        return between >= widest;
    }
}
