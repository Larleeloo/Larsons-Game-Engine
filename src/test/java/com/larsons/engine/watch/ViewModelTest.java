package com.larsons.engine.watch;

import com.larsons.engine.graphics.EyeCamera;
import com.larsons.engine.watch.render.Mesh;
import com.larsons.engine.watch.render.WalkerModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The first-person view model: that the hands are where hands go.
 *
 * <h2>Why this needs a test at all</h2>
 *
 * <p>Because the bug it exists to catch is invisible from the code. The
 * camera's up axis is a cross product of its forward and right axes, and the
 * two orders look equally plausible written down; with this engine's basis one
 * of them points straight <em>down</em> for every pitch a player can hold. Get
 * it backwards and the arms render forty centimetres above the eye line instead
 * of below it — which reads as somebody else's arms coming over your head, and
 * which nothing but looking at the screen or an assertion like this one will
 * tell you.
 */
@Timeout(60)
class ViewModelTest {

    /** The up axis, at a pitch and a yaw, as the model computes it. */
    private static double[] upAt(double yaw, double pitch) {
        EyeCamera eye = new EyeCamera(1280, 720);
        eye.place(0, 0, 0);
        eye.look(yaw, pitch);
        double[] up = new double[3];
        WalkerModel.cameraUp(eye.dirX(), eye.dirY(), eye.dirZ(),
                eye.rightX(), eye.rightY(), up);
        return up;
    }

    /** Up is up, at every yaw and every pitch a player can hold. */
    @Test
    void upPointsUp() {
        for (int y = 0; y < 16; y++) {
            double yaw = y * Math.PI / 8;
            for (int p = -8; p <= 8; p++) {
                double pitch = p / 8.0 * EyeCamera.MAX_PITCH;
                double[] up = upAt(yaw, pitch);
                assertTrue(up[2] > 0,
                        "up points down at yaw " + yaw + " pitch " + pitch
                                + " (z = " + up[2] + ")");
                double length = Math.sqrt(up[0] * up[0] + up[1] * up[1] + up[2] * up[2]);
                assertTrue(Math.abs(length - 1) < 1e-9, "up is not a unit vector");
            }
        }
    }

    /** …and it is perpendicular to both axes it was derived from. */
    @Test
    void upIsPerpendicularToTheOtherTwo() {
        EyeCamera eye = new EyeCamera(1280, 720);
        eye.place(0, 0, 0);
        eye.look(1.1, 0.42);
        double[] up = new double[3];
        WalkerModel.cameraUp(eye.dirX(), eye.dirY(), eye.dirZ(),
                eye.rightX(), eye.rightY(), up);
        double alongView = up[0] * eye.dirX() + up[1] * eye.dirY() + up[2] * eye.dirZ();
        double alongRight = up[0] * eye.rightX() + up[1] * eye.rightY();
        assertTrue(Math.abs(alongView) < 1e-9, "up leans along the view: " + alongView);
        assertTrue(Math.abs(alongRight) < 1e-9, "up leans sideways: " + alongRight);
    }

    /**
     * The hands come out below the eye and in front of it — which is the
     * assertion the sign error actually broke.
     */
    @Test
    void handsHangBelowTheEyeAndInFrontOfIt() {
        EyeCamera eye = new EyeCamera(1280, 720);
        eye.place(0, 0, 0);
        // Level, looking north, so "below" and "in front" are readable straight
        // off the axes rather than through a rotation.
        eye.look(0, 0);

        Mesh.Builder builder = Mesh.builder(0, 0, 0, false, 1);
        WalkerModel.hands(builder, 0, 0, 0, eye.dirX(), eye.dirY(), eye.dirZ(),
                eye.rightX(), eye.rightY(), 0, 0, 0, 0x4A6B33);
        Mesh mesh = builder.build();

        assertTrue(mesh.vertexCount() > 0, "the hands drew nothing");
        assertTrue(mesh.maxZ() < 0,
                "the hands are at or above the eye line (top at z = " + mesh.maxZ() + ")");
        assertTrue(mesh.minZ() > -1.2,
                "the hands are somewhere around the knees (bottom at z = "
                        + mesh.minZ() + ")");
        // Looking north is −y, and everything should be out that way and past
        // the near plane, or the painter clips the lot away.
        assertTrue(mesh.maxY() < -EyeCamera.NEAR,
                "the hands are inside the near plane (nearest y = " + mesh.maxY() + ")");
        // Two of them, one either side.
        assertTrue(mesh.minX() < -0.2 && mesh.maxX() > 0.2,
                "there is only one hand: x runs " + mesh.minX() + " to " + mesh.maxX());
    }

    /** A held item lands in the right hand rather than off in space. */
    @Test
    void aHeldItemIsInTheHand() {
        EyeCamera eye = new EyeCamera(1280, 720);
        eye.place(0, 0, 0);
        eye.look(0, 0);
        double[] up = new double[3];
        WalkerModel.cameraUp(eye.dirX(), eye.dirY(), eye.dirZ(),
                eye.rightX(), eye.rightY(), up);

        // The scene's own arithmetic for where the item goes.
        double forward = WalkerModel.HAND_FORWARD + 0.10;
        double out = WalkerModel.HAND_SIDE;
        double down = WalkerModel.HAND_DROP;
        double x = eye.dirX() * forward + eye.rightX() * out + up[0] * -down;
        double y = eye.dirY() * forward + eye.rightY() * out + up[1] * -down;
        double z = eye.dirZ() * forward + up[2] * -down;

        assertTrue(z < 0, "the held item floats above the eye (z = " + z + ")");
        assertTrue(x > 0, "the held item is in the left hand (x = " + x + ")");
        assertTrue(y < -EyeCamera.NEAR,
                "the held item is inside the near plane (y = " + y + ")");
    }
}
