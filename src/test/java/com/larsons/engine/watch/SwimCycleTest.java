package com.larsons.engine.watch;

import com.larsons.engine.demo.WatchScene;
import com.larsons.engine.graphics.EyeCamera;
import com.larsons.engine.watch.render.Gait;
import com.larsons.engine.watch.render.Mesh;
import com.larsons.engine.watch.render.SwimStroke;
import com.larsons.engine.watch.render.WalkerModel;
import com.larsons.engine.watch.world.TerrainField;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The swim: that a swimmer floats at the right height, lies down as they set
 * off, stands back up when they stop, and never stops moving.
 *
 * <h2>What is actually being checked</h2>
 *
 * <p>A swimmer used to be drawn as a <em>standing walker</em> — upright, legs
 * striding at whatever speed they were making — so crossing a lake was
 * somebody marching along the bottom of it and a dive was the same figure
 * marching downwards. Everything wrong with that is measurable:
 *
 * <ul>
 *   <li>a body that does not lie down keeps its head a metre and a half over
 *       its feet whatever it is doing — so the head has to end up at the
 *       waterline when floating and the shoulders under it;</li>
 *   <li>a pose that switches rather than tips shows a cut — so the pose at a
 *       standstill has to <b>be</b> the standing figure, to the centimetre,
 *       and the way between them has to be continuous in speed;</li>
 *   <li>a stroke clocked on ground covered stops dead when somebody swims
 *       straight down — so the cycle has to be clocked on distance through the
 *       water, and it has to have a floor under it, because a swimmer who
 *       stops swimming sinks;</li>
 *   <li>and a piecewise stroke whose pieces meet at mismatched slopes ticks
 *       once a cycle for ever.</li>
 * </ul>
 */
@Timeout(120)
class SwimCycleTest {

    /** A swimmer floating at the surface has their feet exactly this far under. */
    private static final double FLOATING =
            TerrainField.WATER_LEVEL - WatchScene.FLOAT_DEPTH;

    // --- how the body lies ---------------------------------------------------------

    /** Going nowhere is upright; going somewhere is lying down. */
    @Test
    void aSwimmerLiesDownAsTheySetOffAndStandsUpWhenTheyStop() {
        assertEquals(Math.PI / 2, WalkerModel.swimPitch(0, 0, false), 1e-9,
                "somebody treading water is not upright");
        double moving = WalkerModel.swimPitch(2.4, 0, false);
        assertTrue(moving < 0.7 && moving > 0.2,
                "a swimmer at full speed is at " + moving + " radians, which is "
                        + "neither prone nor upright");
        // And every speed in between is between, without a step in it.
        double previous = Math.PI / 2;
        for (int i = 0; i <= 200; i++) {
            double pitch = WalkerModel.swimPitch(i / 50.0, 0, false);
            assertTrue(pitch <= previous + 1e-9, "the body sat back up at " + (i / 50.0));
            assertTrue(previous - pitch < 0.05, "the body snapped flat at " + (i / 50.0));
            previous = pitch;
        }
    }

    /** Under water the body lies along the way they are looking, and travelling. */
    @Test
    void aDiverPointsWhereTheyAreGoing() {
        for (int p = -6; p <= 6; p++) {
            double look = p / 6.0 * EyeCamera.MAX_PITCH;
            assertEquals(look, WalkerModel.swimPitch(9, look, true), 1e-9,
                    "a swimmer under way is not lying along their own course");
        }
        // At the surface they cannot: the body has to stay in the water.
        assertTrue(WalkerModel.swimPitch(9, -1.2, false) > 0,
                "a swimmer at the surface was pointed at the bottom of the lake");
    }

    /**
     * Floating still is the standing figure, to the centimetre.
     *
     * <p>The property that makes wading out of your depth one movement rather
     * than a cut between two models.
     */
    @Test
    void aSwimmerGoingNowhereIsTheStandingFigure() {
        Mesh standing = walker();
        Mesh floating = swimmer(0, WalkerModel.swimPitch(0, 0, false), 0, 0.3, true);
        // Within a few centimetres rather than exactly: the walk is hung from a
        // planted boot, which lifts it by the depth of a sole, and the stroke
        // has three centimetres of glide in it either way. Both are far below
        // what an eye reads as a cut.
        assertEquals(standing.maxZ(), floating.maxZ(), 0.08,
                "a swimmer treading water is a different height from a walker");
        // The feet get more room again, because a swimmer's toes point and a
        // walker's are flat on the ground — and they are under water anyway.
        assertEquals(standing.minZ(), floating.minZ(), 0.16,
                "a swimmer treading water has their feet somewhere else");
    }

    /**
     * A swimmer at the surface has their head out of the water and their
     * shoulders under it — because of where {@code FLOAT_DEPTH} puts them.
     */
    @Test
    void aSurfaceSwimmerFloatsWithTheirHeadOutAndTheRestUnder() {
        double surface = TerrainField.WATER_LEVEL;
        for (int i = 0; i < 10; i++) {
            Mesh mesh = swimmer(FLOATING, WalkerModel.swimPitch(2.4, 0, false),
                    i / 10.0, 1, true);
            assertTrue(mesh.maxZ() > surface + 0.05,
                    "at stroke " + (i / 10.0) + " the swimmer's head is under water");
            assertTrue(mesh.maxZ() < surface + 0.45,
                    "the swimmer is riding " + (mesh.maxZ() - surface)
                            + " m out of the water");
            assertTrue(mesh.minZ() < surface - 0.4,
                    "the swimmer's legs are out of the water");
        }
    }

    /** …and one treading water is in it up to the chest, which is the same claim. */
    @Test
    void aSwimmerTreadingWaterIsInItToTheChest() {
        double surface = TerrainField.WATER_LEVEL;
        Mesh mesh = swimmer(FLOATING, WalkerModel.swimPitch(0, 0, false), 0.3, 0, true);
        double out = mesh.maxZ() - surface;
        assertTrue(out > 0.3 && out < 0.75,
                "a floating swimmer stands " + out + " m out of the water");
    }

    /** Swimming head-down puts the head below the feet, which is what a dive is. */
    @Test
    void aDiverGoesDownHeadFirst() {
        double[] eye = new double[2];
        double pitch = WalkerModel.swimPitch(9, -1.0, true);
        WalkerModel.swimEye(pitch, eye);
        assertTrue(eye[1] < 0.4,
                "a head-down diver's head is " + eye[1] + " m above their own feet");
    }

    /**
     * Wherever the pose puts the head, {@link WalkerModel#swimEye} says so —
     * which is what a raised spyglass is hung on.
     *
     * <p>Checked against the mesh's own vertices rather than against the model's
     * arithmetic, because the two are written in different places and the only
     * interesting question is whether they agree.
     */
    @Test
    void theEyeIsWhereTheHeadActuallyIs() {
        double[] eye = new double[2];
        for (int p = -4; p <= 4; p++) {
            double pitch = p / 4.0 * (Math.PI / 2);
            WalkerModel.swimEye(pitch, eye);
            Mesh mesh = swimmer(0, pitch, 0.7, 1, false);
            // Facing north (yaw 0), so "along" is −y. The bound is a head's own
            // half-diagonal and a little: a head is a 23-centimetre box, so its
            // nearest corner to its own centre is twenty of them away, and
            // anything genuinely misplaced is out by a third of a metre.
            assertTrue(nearest(mesh, 0, -eye[0], eye[1]) < 0.22,
                    "at a body pitch of " + pitch + " nothing in the swimmer is "
                            + "where their head is supposed to be");
        }
    }

    /** Every pose builds a real figure, at every angle and every point of a stroke. */
    @Test
    void everyPoseBuildsAFigure() {
        for (int p = -4; p <= 4; p++) {
            for (int i = 0; i < 8; i++) {
                Mesh mesh = swimmer(0, p / 4.0 * (Math.PI / 2), i / 8.0,
                        i / 8.0, i % 2 == 0);
                assertTrue(mesh.triangleCount() > 100,
                        "a swimmer came out with " + mesh.triangleCount() + " triangles");
                for (float v : mesh.vertices()) {
                    assertTrue(Float.isFinite(v), "a swimmer has a vertex at infinity");
                }
            }
        }
    }

    // --- the clock -----------------------------------------------------------------

    /**
     * A swimmer's stroke never stops, because a swimmer who stops stroking
     * sinks. Every other cycle in the game is still at a standstill.
     */
    @Test
    void aSwimmerNeverStopsMoving() {
        assertTrue(Gait.swimRate(0) > 0.15,
                "somebody treading water is frozen mid-stroke");
        assertEquals(0, Gait.cadence(0), 1e-12, "a standing walker is not still");
        assertTrue(Gait.swimRate(2.4) > Gait.swimRate(0) * 2,
                "swimming and floating are the same rate");
        assertTrue(Gait.swimRate(2.4) < 2.2,
                "a swimmer's arms are going round like a windmill: "
                        + Gait.swimRate(2.4));
    }

    /** The stroke is clocked on distance through the water, not ground covered. */
    @Test
    void aDiverGoingStraightDownIsStillSwimming() {
        Gait gaits = new Gait();
        double dt = 1 / 60.0;
        gaits.follow(5, 0, 0, 0, 0, Gait.Cycle.SWIM, dt);
        double sank = 0;
        Gait.Step step = null;
        // Straight down, at swimming speed, with no ground covered at all.
        for (int frame = 0; frame < 300; frame++) {
            sank -= 2.0 * dt;
            step = gaits.follow(5, 0, 0, sank, 0, Gait.Cycle.SWIM, dt);
        }
        assertTrue(step.speed() > 1.5,
                "a diver descending at two metres a second reads as moving at "
                        + step.speed());
        assertTrue(Gait.swimRate(step.speed()) > Gait.swimRate(0) * 1.5,
                "the stroke did not speed up for a diver going straight down");
    }

    /** A walker's cadence, by contrast, is not sped up by the ground falling away. */
    @Test
    void aWalkerDownhillIsClockedOnTheGroundTheyCover() {
        Gait gaits = new Gait();
        double dt = 1 / 60.0;
        gaits.follow(6, 0, 0, 0, 0, Gait.Cycle.STRIDE, dt);
        double along = 0, down = 0;
        Gait.Step step = null;
        for (int frame = 0; frame < 300; frame++) {
            along += 2.0 * dt;
            down -= 2.0 * dt;
            step = gaits.follow(6, along, 0, down, 0, Gait.Cycle.STRIDE, dt);
        }
        assertEquals(2.0, step.speed(), 0.08,
                "a walker on a slope is being clocked on the slope rather than "
                        + "on the ground they are covering");
    }

    // --- the stroke ----------------------------------------------------------------

    /** Nothing in a stroke jumps, and nothing in it changes direction instantly. */
    @Test
    void nothingInAStrokeSnaps() {
        double step = 1e-4;
        for (double s = 0; s <= 1.0001; s += step) {
            assertSmooth("the hands' reach", s, step, SwimStroke::reach);
            assertSmooth("the hands' sweep", s, step, SwimStroke::spread);
            assertSmooth("the kick", s, step, SwimStroke::kick);
            assertSmooth("the breath", s, step, SwimStroke::breathe);
            assertSmooth("the surge", s, step, SwimStroke::surge);
        }
    }

    private interface Curve { double at(double phase); }

    private static void assertSmooth(String what, double at, double step, Curve curve) {
        double here = curve.at(at), before = curve.at(at - step);
        assertTrue(Math.abs(here - before) < 0.01, what + " jumps at " + at);
        double slopeBefore = (here - before) / step;
        double slopeAfter = (curve.at(at + step) - here) / step;
        assertTrue(Math.abs(slopeAfter - slopeBefore) < 0.05,
                what + " changes direction instantly at " + at
                        + " (slope " + slopeBefore + " then " + slopeAfter + ")");
    }

    /** The arms pull while the legs trail, and the legs kick while the arms recover. */
    @Test
    void theArmsAndTheLegsTakeTurns() {
        for (double s = 0; s < SwimStroke.PULL; s += 0.01) {
            assertEquals(0, SwimStroke.kick(s), 1e-9,
                    "the legs are kicking during the arm pull, at " + s);
        }
        for (double s = SwimStroke.PULL; s < 1; s += 0.01) {
            assertEquals(0, SwimStroke.spread(s), 1e-9,
                    "the hands are still sweeping wide during the recovery, at " + s);
        }
        assertTrue(SwimStroke.spread(SwimStroke.PULL / 2) > 0.9,
                "the hands never sweep out at all");
        assertTrue(SwimStroke.kick(SwimStroke.PULL + (1 - SwimStroke.PULL) / 2) > 0.9,
                "the legs never draw up at all");
    }

    /** The hands run from in front at the glide to the chest at the end of the pull. */
    @Test
    void theHandsRunFromTheGlideToTheChestAndBack() {
        assertEquals(1, SwimStroke.reach(0), 1e-9);
        assertEquals(-1, SwimStroke.reach(SwimStroke.PULL), 1e-9);
        assertEquals(1, SwimStroke.reach(1), 1e-9);
    }

    /** Only a swimmer at the surface lifts their head to breathe. */
    @Test
    void onlyASurfaceSwimmerBreathes() {
        double top = SwimStroke.PULL / 2;
        Mesh up = swimmer(0, 0.5, top, 1, true);
        Mesh under = swimmer(0, 0.5, top, 1, false);
        assertTrue(up.maxZ() > under.maxZ() + 0.01,
                "a swimmer at the surface does not lift their head to breathe");
    }

    // --- the view model ------------------------------------------------------------

    /** The first-person hands clear the near plane at every point of a stroke. */
    @Test
    void theSwimmingHandsClearTheNearPlane() {
        EyeCamera eye = new EyeCamera(1280, 720);
        eye.place(0, 0, 0);
        for (int p = -2; p <= 2; p++) {
            eye.look(2.0, p / 2.0 * EyeCamera.MAX_PITCH);
            for (int i = 0; i < 12; i++) {
                Mesh.Builder builder = Mesh.builder(0, 0, 0, false, 1);
                WalkerModel.swimmingHands(builder, 0, 0, 0, eye.dirX(), eye.dirY(),
                        eye.dirZ(), eye.rightX(), eye.rightY(), i / 12.0, 0x4A6B33);
                Mesh mesh = builder.build();
                float[] v = mesh.vertices();
                for (int n = 0; n < mesh.vertexCount(); n++) {
                    int at = n * Mesh.FLOATS_PER_VERTEX;
                    double along = v[at] * eye.dirX() + v[at + 1] * eye.dirY()
                            + v[at + 2] * eye.dirZ();
                    assertTrue(along > EyeCamera.NEAR,
                            "a swimming hand is " + along + " m from the eye at stroke "
                                    + (i / 12.0) + ", inside the near plane");
                }
            }
        }
    }

    /** Both hands sweep together, which is the difference between this and a walk. */
    @Test
    void theSwimmingHandsMoveTogether() {
        EyeCamera eye = new EyeCamera(1280, 720);
        eye.place(0, 0, 0);
        eye.look(0, 0);
        for (int i = 0; i < 16; i++) {
            Mesh.Builder builder = Mesh.builder(0, 0, 0, false, 1);
            WalkerModel.swimmingHands(builder, 0, 0, 0, eye.dirX(), eye.dirY(),
                    eye.dirZ(), eye.rightX(), eye.rightY(), i / 16.0, 0x4A6B33);
            Mesh mesh = builder.build();
            double left = Double.MAX_VALUE, right = Double.MAX_VALUE;
            float[] v = mesh.vertices();
            for (int n = 0; n < mesh.vertexCount(); n++) {
                int at = n * Mesh.FLOATS_PER_VERTEX;
                if (v[at] < 0) left = Math.min(left, v[at + 1]);
                else right = Math.min(right, v[at + 1]);
            }
            assertEquals(left, right, 1e-5,
                    "one hand is further out than the other at stroke " + (i / 16.0));
        }
    }

    // --- scaffolding ----------------------------------------------------------------

    private static Mesh swimmer(double z, double bodyPitch, double phase, double drive,
                                boolean surfaced) {
        Mesh.Builder mesh = Mesh.builder(0, 0, 0, false, 1);
        WalkerModel.swimmer(mesh, 0, 0, z, 0, bodyPitch, drive, phase, surfaced,
                0x4A6B33);
        return mesh.build();
    }

    private static Mesh walker() {
        Mesh.Builder mesh = Mesh.builder(0, 0, 0, false, 1);
        WalkerModel.walker(mesh, 0, 0, 0, 0, false, 0, 0, 0x4A6B33);
        return mesh.build();
    }

    /** How far the nearest vertex in a mesh is from a point. */
    private static double nearest(Mesh mesh, double x, double y, double z) {
        float[] v = mesh.vertices();
        double best = Double.MAX_VALUE;
        for (int i = 0; i < mesh.vertexCount(); i++) {
            int at = i * Mesh.FLOATS_PER_VERTEX;
            best = Math.min(best, Math.sqrt(
                    (v[at] - x) * (v[at] - x)
                            + (v[at + 1] - y) * (v[at + 1] - y)
                            + (v[at + 2] - z) * (v[at + 2] - z)));
        }
        return best;
    }
}
