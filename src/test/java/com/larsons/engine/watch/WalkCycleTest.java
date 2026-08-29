package com.larsons.engine.watch;

import com.larsons.engine.graphics.EyeCamera;
import com.larsons.engine.watch.render.BoatModel;
import com.larsons.engine.watch.render.Gait;
import com.larsons.engine.watch.render.Mesh;
import com.larsons.engine.watch.render.RowStroke;
import com.larsons.engine.watch.render.Shapes;
import com.larsons.engine.watch.render.WalkerModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The walk and the row: that they are smooth, that they are timed in seconds,
 * and that the parts of a figure stay attached to each other.
 *
 * <h2>Why any of this can be tested at all</h2>
 *
 * <p>"Choppy" sounds like something only an eye can judge, and most of it is
 * not. Every artefact this file is about is a number with a property:
 *
 * <ul>
 *   <li>a phase driven by the frame count rather than by elapsed time runs at
 *       different speeds for different frame rates — so the same second of
 *       walking, taken in one step or in a hundred, has to end at the same
 *       place in the cycle;</li>
 *   <li>a position taken straight from a twenty-hertz snapshot moves in hops —
 *       so the largest step a drawn walker takes in a frame has to be a small
 *       fraction of the distance between two snapshots;</li>
 *   <li>a piecewise animation whose pieces do not meet with matching slopes
 *       ticks once a cycle — so the stroke's curves have to be differentiable
 *       across their joins and across the wrap;</li>
 *   <li>a figure whose feet are not solved for slides along the ground or sinks
 *       into it — so the lower boot has to be on the ground at every point of
 *       the stride;</li>
 *   <li>and hands that are not put on the oars come off them — so the fists and
 *       the handles have to be in the same place.</li>
 * </ul>
 */
@Timeout(120)
class WalkCycleTest {

    // --- the clock ---------------------------------------------------------------------

    /**
     * The same second of walking, sampled at wildly different frame rates,
     * leaves the cycle in the same place.
     *
     * <p>This is the whole of the old bug: a phase advanced by
     * {@code frame * 0.02} runs at twice the speed on a hundred-and-twenty-hertz
     * screen as on a sixty, and lurches whenever the rate wobbles.
     */
    @Test
    void theWalkCycleIsTimedInSecondsAndNotInFrames() {
        double slow = walked(1.0, 12);
        double fast = walked(1.0, 400);
        assertEquals(slow, fast, 1e-9,
                "a second of walking covers a different part of the cycle at a "
                        + "different frame rate: " + slow + " vs " + fast);
    }

    /** How far through the cycle one second at walking pace gets, in turns. */
    private static double walked(double seconds, int steps) {
        double dt = seconds / steps;
        double phase = 0;
        for (int i = 0; i < steps; i++) {
            phase += Gait.cadence(WatchPlayer.WALK_SPEED) * dt;
        }
        return phase;
    }

    /** Standing still is standing still: no speed, no cadence, no cycle. */
    @Test
    void aWalkerWhoIsNotMovingDoesNotPaddleTheirLegs() {
        assertEquals(0, Gait.cadence(0), 1e-12);
        assertEquals(0, Gait.strokeRate(0), 1e-12);
    }

    /**
     * The cadence is inside the range a person's is, at every speed the game
     * can produce — and a sprint is not a walk played at four times the speed.
     */
    @Test
    void theCadenceStaysInsideARangeAnEyeCanRead() {
        assertTrue(Gait.cadence(WatchPlayer.CROUCH_SPEED) > 0.4,
                "a creeping walker's legs barely move");
        assertTrue(Gait.cadence(WatchPlayer.RUN_SPEED) < 2.4,
                "a sprint is a blur: " + Gait.cadence(WatchPlayer.RUN_SPEED)
                        + " cycles a second");
        assertTrue(Gait.cadence(WatchPlayer.RUN_SPEED)
                        > Gait.cadence(WatchPlayer.WALK_SPEED) * 1.15,
                "running and walking are the same cycle at the same rate");
        // Rowing covers far more ground per cycle than walking, so a boat at
        // full speed is not somebody flailing.
        assertTrue(Gait.strokeRate(Boats.ROW_SPEED) < 1.7,
                "the oars are going round like an egg whisk: "
                        + Gait.strokeRate(Boats.ROW_SPEED));
    }

    // --- everybody else, between two snapshots ------------------------------------------

    /**
     * A walker whose position arrives twenty times a second is drawn moving
     * every frame, in steps far smaller than the ones that arrive.
     */
    @Test
    void aRemoteWalkerGlidesBetweenSnapshotsInsteadOfHopping() {
        Gait gaits = new Gait();
        double frameDt = 1 / 60.0, snapshotDt = 1 / 20.0;
        double speed = WatchPlayer.WALK_SPEED;
        double sent = 0;
        // Settle first: the very first snapshot is placed rather than eased.
        gaits.follow(7, 0, 0, 0, 0, Gait.Cycle.STRIDE, false, frameDt);

        double previous = 0;
        double largest = 0;
        double snapshotStep = speed * snapshotDt;
        for (int frame = 1; frame <= 240; frame++) {
            // A new position every third frame, which is twenty a second.
            if (frame % 3 == 0) sent += snapshotStep;
            Gait.Step step = gaits.follow(7, sent, 0, 0, 0, Gait.Cycle.STRIDE, false, frameDt);
            if (frame > 60) {
                largest = Math.max(largest, Math.abs(step.x() - previous));
                assertTrue(step.x() >= previous - 1e-9,
                        "a walker going one way went backwards at frame " + frame);
            }
            previous = step.x();
        }
        assertTrue(largest < snapshotStep * 0.6,
                "the drawn walker is still hopping a snapshot at a time: "
                        + largest + " m in a frame against " + snapshotStep
                        + " m between snapshots");
        assertTrue(largest > 0,
                "the drawn walker is not moving between snapshots at all");
    }

    /** …and their legs run at the speed they are seen to be moving at. */
    @Test
    void theLegsAreDrivenByTheSpeedTheFigureIsActuallySeenToMoveAt() {
        Gait gaits = new Gait();
        double frameDt = 1 / 60.0;
        double speed = 3.0;
        double sent = 0;
        Gait.Step step = gaits.follow(3, 0, 0, 0, 0, Gait.Cycle.STRIDE, false, frameDt);
        for (int frame = 0; frame < 600; frame++) {
            sent += speed * frameDt;
            step = gaits.follow(3, sent, 0, 0, 0, Gait.Cycle.STRIDE, false, frameDt);
        }
        assertEquals(speed, step.speed(), 0.05,
                "the drawn speed does not settle on the real one");
    }

    /** A walker who is somewhere else entirely is placed there, not skated to it. */
    @Test
    void aTeleportIsPlacedRatherThanGlidedAcrossTheCounty() {
        Gait gaits = new Gait();
        gaits.follow(2, 0, 0, 0, 0, Gait.Cycle.STRIDE, false, 1 / 60.0);
        Gait.Step step = gaits.follow(2, 900, 400, 0, 0, Gait.Cycle.STRIDE, false, 1 / 60.0);
        assertEquals(900, step.x(), 1e-9);
        assertEquals(400, step.y(), 1e-9);
        assertEquals(0, step.speed(), 1e-9,
                "a teleport was mistaken for somebody moving at Mach 40");
    }

    /** A heading eases the short way round rather than unwinding a whole turn. */
    @Test
    void aTurnGoesTheShortWayRound() {
        double eased = Gait.turn(3.0, -3.0, 0.5);
        // The short way from 3.0 to −3.0 is 0.283 radians forward, through π.
        assertEquals(3.0 + (Math.PI * 2 - 6.0) / 2, eased, 1e-9,
                "the walker spun most of the way round to turn sixteen degrees");
    }

    /** {@link Gait#at} answers with what the frame decided, once, for both passes. */
    @Test
    void theBoatAndItsRowerAreGivenTheSameAnswer() {
        Gait gaits = new Gait();
        gaits.follow(4, 10, 0, 0, 1, Gait.Cycle.STROKE, false, 1 / 60.0);
        Gait.Step first = gaits.follow(4, 12, 0, 0, 1, Gait.Cycle.STROKE, false, 1 / 60.0);
        Gait.Step again = gaits.at(4);
        assertEquals(first.x(), again.x(), 1e-12);
        assertEquals(first.phase(), again.phase(), 1e-12,
                "the boat would be drawn at a different point of the stroke "
                        + "from the person rowing it");
    }

    // --- the stroke --------------------------------------------------------------------

    /** Reach, lift and surge are all continuous — including across the wrap. */
    @Test
    void nothingInAStrokeJumps() {
        double step = 1e-4;
        double previousReach = RowStroke.reach(-step);
        double previousLift = RowStroke.lift(-step);
        double previousSurge = RowStroke.surge(-step);
        for (double s = 0; s <= 1.0001; s += step) {
            double reach = RowStroke.reach(s), lift = RowStroke.lift(s);
            double surge = RowStroke.surge(s);
            assertTrue(Math.abs(reach - previousReach) < 0.01,
                    "the hands jump at " + s);
            assertTrue(Math.abs(lift - previousLift) < 0.01,
                    "the blade jumps at " + s);
            assertTrue(Math.abs(surge - previousSurge) < 0.01,
                    "the boat jumps at " + s);
            previousReach = reach;
            previousLift = lift;
            previousSurge = surge;
        }
    }

    /**
     * …and so are their slopes, which is the harder half: a curve that meets
     * its neighbour at the right value but the wrong speed ticks once a stroke,
     * for ever, and reads as a fault rather than as a rhythm.
     */
    @Test
    void nothingInAStrokeChangesDirectionInstantly() {
        double step = 1e-4;
        for (double s = 0; s <= 1.0001; s += step) {
            assertSmooth("the hands", s, step, RowStroke::reach, 0.02);
            assertSmooth("the blade", s, step, RowStroke::lift, 0.02);
            assertSmooth("the surge", s, step, RowStroke::surge, 0.02);
        }
    }

    private interface Curve { double at(double phase); }

    /** That a curve's slope either side of a point differs by very little. */
    private static void assertSmooth(String what, double at, double step,
                                     Curve curve, double tolerance) {
        double before = (curve.at(at) - curve.at(at - step)) / step;
        double after = (curve.at(at + step) - curve.at(at)) / step;
        assertTrue(Math.abs(after - before) < tolerance / step * 0.01 + 0.05,
                what + " changes direction instantly at " + at
                        + " (slope " + before + " then " + after + ")");
    }

    /** The blade is in the water for the drive and out of it for the recovery. */
    @Test
    void theBladeIsInTheWaterExactlyWhileItIsBeingPulled() {
        for (double s = 0; s < RowStroke.DRIVE; s += 0.01) {
            assertEquals(0, RowStroke.lift(s), 1e-9,
                    "the blade is out of the water during the drive, at " + s);
        }
        assertTrue(RowStroke.lift(RowStroke.DRIVE + (1 - RowStroke.DRIVE) / 2) > 0.9,
                "the blade never clears the water on the recovery");
        // The drive is the shorter half — a stroke, not somebody stirring soup.
        assertTrue(RowStroke.DRIVE < 0.5, "the drive is longer than the recovery");
    }

    /** The hands are at one end of their travel at the catch and the other at the finish. */
    @Test
    void theHandsRunFromTheCatchToTheFinishAndBack() {
        assertEquals(-1, RowStroke.reach(0), 1e-9);
        assertEquals(1, RowStroke.reach(RowStroke.DRIVE), 1e-9);
        assertEquals(-1, RowStroke.reach(1), 1e-9);
    }

    // --- the figure --------------------------------------------------------------------

    /**
     * The lower foot is on the ground at every point of the stride.
     *
     * <p>The property the solved hip height exists for. Legs swung about a
     * fixed hip put both feet in the air at the middle of the stride and both
     * through the ground at the ends of it — a figure that hovers and then
     * wades, twice a step.
     */
    @Test
    void theLowerBootIsOnTheGroundThroughoutTheStride() {
        for (int i = 0; i < 64; i++) {
            double phase = i / 64.0;
            Mesh mesh = walkerAt(phase, WatchPlayer.WALK_SPEED);
            assertEquals(0, mesh.minZ(), 0.02,
                    "at phase " + phase + " the lower foot is " + mesh.minZ()
                            + " m off the ground");
        }
    }

    /** A walker standing still is completely still — the same figure at every phase. */
    @Test
    void aWalkerStandingStillDoesNotTwitch() {
        Mesh first = walkerAt(0, 0);
        for (int i = 1; i < 8; i++) {
            Mesh other = walkerAt(i / 8.0, 0);
            assertEquals(first.vertexCount(), other.vertexCount());
            for (int v = 0; v < first.vertices().length; v++) {
                assertEquals(first.vertices()[v], other.vertices()[v], 1e-6,
                        "a standing walker moved between phase 0 and " + (i / 8.0));
            }
        }
    }

    /** The body rises and falls with the stride, and not by a comical amount. */
    @Test
    void theHipsRiseAndFallWithTheStrideAndNoFurther() {
        double lowest = Double.MAX_VALUE, highest = -Double.MAX_VALUE;
        for (int i = 0; i < 64; i++) {
            Mesh mesh = walkerAt(i / 64.0, WatchPlayer.WALK_SPEED);
            lowest = Math.min(lowest, mesh.maxZ());
            highest = Math.max(highest, mesh.maxZ());
        }
        double bob = highest - lowest;
        assertTrue(bob > 0.01, "the figure does not rise and fall at all");
        assertTrue(bob < 0.20, "the figure is pogoing: " + bob + " m");
    }

    /** Nobody is left standing under the ground, or floating over it. */
    @Test
    void aWalkerStandsOnTheGroundTheyAreGiven() {
        Mesh mesh = walkerAt(0, 0);
        assertEquals(0, mesh.minZ(), 0.005,
                "a standing walker's boots are not on the ground");
        // The top of the mesh is the crown of the hat rather than the head, so
        // it is a little over the documented height and must not be far over.
        assertTrue(mesh.maxZ() > WalkerModel.HEIGHT * 0.98
                        && mesh.maxZ() < WalkerModel.HEIGHT * 1.15,
                "a standing walker is " + mesh.maxZ() + " m to the top of the hat");
    }

    private static Mesh walkerAt(double phase, double speed) {
        Mesh.Builder mesh = Mesh.builder(0, 0, 0, false, 1);
        WalkerModel.walker(mesh, 0, 0, 0, 0.7, false, phase, speed,
                WalkerModel.Leap.GROUNDED, 0x4A6B33);
        return mesh.build();
    }

    // --- the rower ---------------------------------------------------------------------

    /**
     * The rower's fists are on the handles, at every point of the stroke.
     *
     * <p>Checked by finding the mesh's own extremes rather than by asking the
     * model where it thinks it put them: the hands and the oars are drawn by
     * two different classes, and the only interesting question is whether the
     * triangles agree.
     */
    @Test
    void theRowersHandsAreOnTheOars() {
        double[] grip = new double[3];
        for (int i = 0; i < 32; i++) {
            double stroke = i / 32.0;
            Mesh mesh = rowerAt(stroke);
            for (int side = -1; side <= 1; side += 2) {
                BoatModel.handle(stroke, side, grip);
                // The boat is drawn at the origin facing north (yaw 0), so the
                // boat's own axes are the world's: along is −y, across is +x.
                double hx = grip[1], hy = -grip[0], hz = grip[2];
                assertTrue(nearestVertex(mesh, hx, hy, hz) < 0.09,
                        "at stroke " + stroke + " the " + (side < 0 ? "port" : "starboard")
                                + " fist is nowhere near its handle");
            }
        }
    }

    /** A rower sits in the boat rather than standing through the bottom of it. */
    @Test
    void theRowerSitsInTheHullRatherThanStandingInIt() {
        Mesh mesh = rowerAt(0.2);
        assertTrue(mesh.minZ() > BoatModel.floorZ(0, 0) - 0.06,
                "the rower's feet are through the floorboards: " + mesh.minZ());
        assertTrue(mesh.maxZ() < WalkerModel.HEIGHT * 0.92,
                "the rower is standing up: their head is at " + mesh.maxZ());
        assertTrue(mesh.maxZ() > BoatModel.thwartZ(0, 0) + 0.6,
                "the rower has no head above the gunwale at all");
    }

    /** The oars are shipped when nobody is rowing, and working when somebody is. */
    @Test
    void aMooredBoatKeepsItsOarsOutOfTheWater() {
        Mesh moored = boatAt(BoatModel.SHIPPED);
        Mesh rowed = boatAt(RowStroke.DRIVE / 2);
        assertTrue(moored.minZ() > -BoatModel.DRAUGHT - 0.01,
                "a moored boat has an oar dragging in the water");
        assertTrue(rowed.minZ() < moored.minZ() - 0.05,
                "a boat being rowed has both blades out of the water");
    }

    /** The blade goes into the water on the drive and comes out on the recovery. */
    @Test
    void theBladesEnterAndLeaveTheWaterWithTheStroke() {
        double[] blade = new double[3];
        BoatModel.blade(RowStroke.DRIVE / 2, 1, blade);
        assertTrue(blade[2] < 0, "the blade does not reach the water on the drive");
        BoatModel.blade(RowStroke.DRIVE + (1 - RowStroke.DRIVE) / 2, 1, blade);
        assertTrue(blade[2] > 0, "the blade drags through the water on the recovery");
    }

    /** The rower rides the same swell the hull does. */
    @Test
    void theRowerAndTheHullBobTogether() {
        Mesh flat = rowerAt(0.2);
        Mesh.Builder lifted = Mesh.builder(0, 0, 0, false, 1);
        WalkerModel.rower(lifted, 0, 0, 0, 0, 0.25, 0.2, 0x4A6B33);
        Mesh up = lifted.build();
        double moved = up.minZ() - flat.minZ();
        assertEquals(BoatModel.heave(0.25) - BoatModel.heave(0), moved, 1e-5,
                "the rower does not ride the swell the boat is on");
    }

    /**
     * The first-person hands clear the near plane at every point of a stroke.
     *
     * <p>The constraint that decided how they are built. {@link EyeCamera#NEAR}
     * is eight tenths of a metre and the painter path throws away anything
     * nearer, so hands on the real handles — which is where they were built
     * first — have their elbows inside it and are drawn with their back halves
     * missing. Every vertex, over a whole stroke, has to be past it.
     */
    @Test
    void theRowingHandsClearTheNearPlaneAtEveryPointOfTheStroke() {
        EyeCamera eye = new EyeCamera(1280, 720);
        eye.place(0, 0, 0);
        for (int p = -2; p <= 2; p++) {
            eye.look(1.1, p / 2.0 * EyeCamera.MAX_PITCH);
            for (int i = 0; i < 12; i++) {
                Mesh.Builder builder = Mesh.builder(0, 0, 0, false, 1);
                WalkerModel.rowingHands(builder, 0, 0, 0, eye.dirX(), eye.dirY(),
                        eye.dirZ(), eye.rightX(), eye.rightY(), i / 12.0, 0x4A6B33);
                Mesh mesh = builder.build();
                float[] v = mesh.vertices();
                for (int n = 0; n < mesh.vertexCount(); n++) {
                    int at = n * Mesh.FLOATS_PER_VERTEX;
                    // How far along the view direction the vertex is: what the
                    // near plane actually measures.
                    double along = v[at] * eye.dirX() + v[at + 1] * eye.dirY()
                            + v[at + 2] * eye.dirZ();
                    assertTrue(along > EyeCamera.NEAR,
                            "a rowing hand is " + along + " m from the eye at stroke "
                                    + (i / 12.0) + ", inside the near plane");
                }
            }
        }
    }

    /** Both hands row together; they do not swing against each other. */
    @Test
    void theRowingHandsMoveTogetherRatherThanAgainstEachOther() {
        EyeCamera eye = new EyeCamera(1280, 720);
        eye.place(0, 0, 0);
        eye.look(0, 0);
        for (int i = 0; i < 16; i++) {
            Mesh.Builder builder = Mesh.builder(0, 0, 0, false, 1);
            WalkerModel.rowingHands(builder, 0, 0, 0, eye.dirX(), eye.dirY(),
                    eye.dirZ(), eye.rightX(), eye.rightY(), i / 16.0, 0x4A6B33);
            Mesh mesh = builder.build();
            // Looking along the world's −y, so "forward" is −y and the two
            // hands differ only in x. Their reach has to match.
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

    private static Mesh rowerAt(double stroke) {
        Mesh.Builder mesh = Mesh.builder(0, 0, 0, false, 1);
        WalkerModel.rower(mesh, 0, 0, 0, 0, 0, stroke, 0x4A6B33);
        return mesh.build();
    }

    private static Mesh boatAt(double stroke) {
        Mesh.Builder mesh = Mesh.builder(0, 0, 0, false, 1);
        BoatModel.boat(mesh, 0, 0, 0, 0, 0, stroke);
        return mesh.build();
    }

    /** How far the nearest vertex in a mesh is from a point. */
    private static double nearestVertex(Mesh mesh, double x, double y, double z) {
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

    // --- the primitive the limbs are made of ---------------------------------------------

    /**
     * A strut is a closed solid wound outward.
     *
     * <p>The one way this primitive can be wrong that looks fine in a wireframe
     * and catastrophic in a lit frame: every face is shaded by its own normal,
     * so a solid wound inside out is lit as though the sun were inside it.
     */
    @Test
    void aStrutIsWoundOutward() {
        double[][] ends = {
            {0, 0, 0, 0, 0, 1},          // straight up, the degenerate basis
            {0, 0, 0, 1, 0, 0},          // along the world east
            {0, 0, 0, 0.4, -0.7, 0.35},  // a limb at an angle to everything
        };
        for (double[] end : ends) {
            Mesh.Builder builder = Mesh.builder(0, 0, 0, false, 1);
            Shapes.strut(builder, end[0], end[1], end[2], end[3], end[4], end[5],
                    0.08, 0.06, new float[4], 0x808080);
            Mesh mesh = builder.build();
            assertEquals(12, mesh.triangleCount(), "a strut is not a closed box");
            double cx = (end[0] + end[3]) / 2, cy = (end[1] + end[4]) / 2;
            double cz = (end[2] + end[5]) / 2;
            float[] v = mesh.vertices();
            for (int t = 0; t < mesh.triangleCount(); t++) {
                int a = t * 3 * Mesh.FLOATS_PER_VERTEX;
                int b = a + Mesh.FLOATS_PER_VERTEX, c = b + Mesh.FLOATS_PER_VERTEX;
                double ux = v[b] - v[a], uy = v[b + 1] - v[a + 1], uz = v[b + 2] - v[a + 2];
                double wx = v[c] - v[a], wy = v[c + 1] - v[a + 1], wz = v[c + 2] - v[a + 2];
                double nx = uy * wz - uz * wy;
                double ny = uz * wx - ux * wz;
                double nz = ux * wy - uy * wx;
                // From the centre of the solid out to the face.
                double ox = v[a] - cx, oy = v[a + 1] - cy, oz = v[a + 2] - cz;
                assertTrue(nx * ox + ny * oy + nz * oz > 0,
                        "face " + t + " of a strut is wound inward");
            }
        }
    }

    /** A strut with no length is skipped rather than emitted with no normals. */
    @Test
    void aStrutOfNoLengthIsNotDrawn() {
        Mesh.Builder builder = Mesh.builder(0, 0, 0, false, 1);
        Shapes.strut(builder, 1, 1, 1, 1, 1, 1, 0.05, 0.05, new float[4], 0x808080);
        assertEquals(0, builder.triangleCount());
    }
}
