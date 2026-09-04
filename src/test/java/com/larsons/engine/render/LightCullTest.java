package com.larsons.engine.render;

import com.larsons.engine.graphics.LightCull;
import com.larsons.engine.graphics.MeshPass;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cull that decides which lamps a mesh is allowed to see.
 *
 * <p><b>Getting this wrong does not crash and does not look like a bug.</b> Too
 * tight and a campfire stops lighting the chunk next to it — a seam of darkness
 * along a boundary the player cannot see, moving with them as they walk, which
 * reads as "the lighting is glitchy" and takes an afternoon to trace back to an
 * inequality. So the properties below are asserted the way a conservative cull
 * has to be asserted: not "does it say yes to the right cases" but <b>"does it
 * ever say no when the answer is yes"</b>, sampled over a few thousand
 * arrangements rather than over the handful somebody thought of.
 */
@Timeout(60)
class LightCullTest {

    private static MeshPass.Light lamp(double x, double y, double z, double radius) {
        return MeshPass.Light.of(x, y, z, 0xFF9A3C, radius, 1.0);
    }

    // --- the surface test ------------------------------------------------------------

    /** A lamp reaches a mesh exactly when the two spheres meet. */
    @Test
    void aLampReachesWhatItsOwnSphereTouches() {
        assertTrue(LightCull.touchesBox(lamp(0, 0, 0, 12), 10, 0, 0, 2),
                "a twelve-metre lamp does not reach a mesh ten metres away");
        assertFalse(LightCull.touchesBox(lamp(0, 0, 0, 12), 40, 0, 0, 2),
                "a twelve-metre lamp reaches forty metres");
        // Exactly touching counts: the boundary belongs to the lit side, so
        // that a mesh sliding across it never flickers.
        assertTrue(LightCull.touchesBox(lamp(0, 0, 0, 12), 14, 0, 0, 2),
                "the two spheres touch and the lamp was culled anyway");
    }

    // --- the air test ----------------------------------------------------------------

    /**
     * <b>Anything that lights a surface also lights the air in front of it.</b>
     *
     * <p>The one relationship the two tests must have, because the GL backend
     * builds its uniform array on it: the surface-lit lamps go in first and the
     * air-only ones after, and a lamp that passed the first test but failed the
     * second would be counted in the surface half and dropped from the air
     * half, which is a lamp whose glow vanishes while its pool of light stays.
     */
    @Test
    void everyLampThatLightsAMeshAlsoLightsTheAirInFrontOfIt() {
        Random random = new Random(20260904L);
        int checked = 0;
        for (int i = 0; i < 20_000; i++) {
            double[] eye = point(random, 60);
            double[] centre = point(random, 60);
            double radius = 1 + random.nextDouble() * 30;
            MeshPass.Light light = lamp(centre[0] + spread(random, 40),
                    centre[1] + spread(random, 40), centre[2] + spread(random, 40),
                    1 + random.nextDouble() * 20);
            if (!LightCull.touchesBox(light, centre[0], centre[1], centre[2], radius)) {
                continue;
            }
            checked++;
            assertTrue(LightCull.touchesWedge(light, eye[0], eye[1], eye[2],
                            centre[0], centre[1], centre[2], radius),
                    "a lamp touching the mesh was said not to reach the air in "
                            + "front of it");
        }
        assertTrue(checked > 500, "only " + checked + " arrangements actually "
                + "touched, so this proved very little");
    }

    /**
     * <b>The cull never puts out a light that is really there.</b>
     *
     * <p>The property that matters, tested against the thing the shader
     * actually does: fire rays from the eye at points in the mesh's ball, walk
     * each one, and if any step of any ray falls inside the lamp then the
     * shader would have found light there and the cull must not have said no.
     * A conservative test is allowed to say yes when the answer is no; this is
     * the direction it is not allowed to be wrong in.
     */
    @Test
    void theCullNeverHidesALampAnyViewRayWouldHaveFound() {
        Random random = new Random(4711L);
        int found = 0;
        for (int trial = 0; trial < 4000; trial++) {
            double[] eye = point(random, 50);
            double[] centre = point(random, 80);
            double radius = 1 + random.nextDouble() * 25;
            MeshPass.Light light = lamp(centre[0] + spread(random, 60),
                    centre[1] + spread(random, 60), centre[2] + spread(random, 60),
                    1 + random.nextDouble() * 18);

            if (LightCull.touchesWedge(light, eye[0], eye[1], eye[2],
                    centre[0], centre[1], centre[2], radius)) {
                continue;
            }
            // The cull said no. Prove no ray disagrees.
            for (int ray = 0; ray < 40; ray++) {
                double[] target = onBall(random, centre, radius);
                for (int step = 0; step <= 32; step++) {
                    double t = step / 32.0;
                    double x = eye[0] + (target[0] - eye[0]) * t;
                    double y = eye[1] + (target[1] - eye[1]) * t;
                    double z = eye[2] + (target[2] - eye[2]) * t;
                    double dx = x - light.x(), dy = y - light.y(), dz = z - light.z();
                    boolean inside = dx * dx + dy * dy + dz * dz
                            < light.radius() * light.radius() * 0.98;
                    assertFalse(inside, "a view ray runs through a lamp the cull "
                            + "had already thrown away");
                }
            }
            found++;
        }
        assertTrue(found > 500, "only " + found + " arrangements were culled at all, "
                + "so this proved very little");
    }

    /** The two cases that skip straight to yes, named so they cannot rot. */
    @Test
    void standingInsideALampOrInsideTheMeshKeepsEverything() {
        // Standing at your own campfire: every ray in the frame begins inside
        // it, so every mesh in the frame carries some of its glow — including
        // the hillside a hundred metres behind you.
        assertTrue(LightCull.touchesWedge(lamp(0, 0, 0, 12), 1, 0, 0,
                        -100, 0, 0, 20),
                "a lamp the camera is standing inside was culled from a mesh "
                        + "behind it");
        // Inside the mesh's own ball there is no cone left to be outside of.
        assertTrue(LightCull.touchesWedge(lamp(0, 40, 0, 6), 0, 0, 0, 1, 1, 1, 30),
                "the camera is inside the mesh and a lamp was culled by angle");
    }

    /** …and the ones it really should throw away. */
    @Test
    void aLampNowhereNearTheViewIsThrownAway() {
        // Ninety degrees off the line of sight, and far from it.
        assertFalse(LightCull.touchesWedge(lamp(0, 60, 0, 8), 0, 0, 0, 60, 0, 0, 4),
                "a lamp at right angles to the whole view was kept");
        // Well behind the mesh: nothing past the far side can scatter into a
        // ray that stops there.
        assertFalse(LightCull.touchesWedge(lamp(200, 0, 0, 8), 0, 0, 0, 60, 0, 0, 4),
                "a lamp a hundred and forty metres behind the mesh was kept");
        // Behind the camera, and small.
        assertFalse(LightCull.touchesWedge(lamp(-40, 0, 0, 6), 0, 0, 0, 60, 0, 0, 4),
                "a lamp behind the camera was kept for a mesh in front of it");
    }

    // --- the ranking -----------------------------------------------------------------

    /**
     * The air budget spends itself on the lamps that fill the most of the view.
     *
     * <p>Only the ordering matters — the number is a ranking key and nothing
     * reads its magnitude — but the ordering has to match how much of the
     * screen each lamp's glow actually covers, or the budget drops the fire you
     * are standing at in favour of a lantern on the ridge.
     */
    @Test
    void theBudgetRanksTheLampsYouAreStandingIn() {
        MeshPass.Light here = lamp(0, 2, 0, 12);
        MeshPass.Light yonder = lamp(0, 90, 0, 12);
        assertTrue(LightCull.airScore(here, 0, 0, 0)
                        > LightCull.airScore(yonder, 0, 0, 0) * 4,
                "a lamp at your feet ranks no higher than one ninety metres off");

        // …and a brighter, wider one beats a dimmer one at the same distance.
        MeshPass.Light big = MeshPass.Light.of(0, 20, 0, 0xFFFFFF, 20, 1.2);
        MeshPass.Light small = MeshPass.Light.of(0, 20, 0, 0xFFFFFF, 5, 0.4);
        assertTrue(LightCull.airScore(big, 0, 0, 0) > LightCull.airScore(small, 0, 0, 0),
                "a bonfire ranks below a candle at the same range");
    }

    private static double[] point(Random random, double spread) {
        return new double[] {spread(random, spread), spread(random, spread),
                spread(random, spread)};
    }

    private static double spread(Random random, double by) {
        return (random.nextDouble() - 0.5) * 2 * by;
    }

    /** A point somewhere inside the ball, so rays sweep the whole of it. */
    private static double[] onBall(Random random, double[] centre, double radius) {
        double x, y, z;
        do {
            x = spread(random, 1);
            y = spread(random, 1);
            z = spread(random, 1);
        } while (x * x + y * y + z * z > 1);
        return new double[] {centre[0] + x * radius, centre[1] + y * radius,
                centre[2] + z * radius};
    }
}
