package com.larsons.engine.watch.render;

import com.larsons.engine.watch.world.WatchMaterial;
import com.larsons.engine.watch.world.WatchMaterials;

/**
 * A person, as boxes — <b>the same figure whether you are looking at yourself
 * or at somebody else.</b>
 *
 * <h2>Why the player has a body at all</h2>
 *
 * <p>The first version of this game drew every walker <em>except</em> the one
 * holding the mouse: three boxes for each of your friends and nothing at all
 * for you. That is a defensible economy in a game where the camera never leaves
 * your eyes, and it stops being one the moment there is a third-person key, a
 * boat to sit in, or water to be waist-deep in. It is also, quietly, the reason
 * a first-person game feels like a floating camera rather than a person: no
 * hands.
 *
 * <p>So there is one model, built here, and it is drawn for everybody:
 *
 * <ul>
 *   <li>in <b>third person</b>, whole, from behind;</li>
 *   <li>in <b>first person</b>, as {@linkplain #hands the two arms}, positioned
 *       from the camera's own basis rather than from the world, so they hang in
 *       front of the view the way a held object does;</li>
 *   <li>for <b>other players</b>, whole, at whatever they are doing.</li>
 * </ul>
 *
 * <h2>The gait</h2>
 *
 * <p>Legs and arms swing against one phase clock, opposite sides out of step,
 * with the amplitude scaled by how fast the walker is actually moving — so a
 * player standing still stands still, one creeping barely moves, and one at a
 * run swings properly. That is four lines of trigonometry and it is the whole
 * difference between a person and a bollard with a hat on.
 *
 * <p>Everything is emitted through {@link Shapes#box}, into the same
 * {@link Mesh.Builder} the animals and the world use, so a walker costs the
 * same as a small deer and is drawn by exactly the same code on both backends.
 */
public final class WalkerModel {

    /** How tall a standing walker is, in metres. */
    public static final double HEIGHT = 1.78;

    /** …and a crouched one. */
    public static final double CROUCH_HEIGHT = 1.18;

    /** How far the limbs swing at a full walk, in radians. */
    private static final double SWING = 0.72;

    /** The speed the swing is measured against, in metres per second. */
    private static final double SWING_REFERENCE = 4.4;

    private WalkerModel() {}

    /**
     * One walker, standing, crouching, swimming or rowing.
     *
     * @param mesh    where the triangles go
     * @param x       position relative to the mesh's origin
     * @param z       the ground under their feet, in world metres
     * @param yaw     which way they are facing, in radians
     * @param phase   the gait clock, in turns — {@code 0}–{@code 1}
     * @param speed   how fast they are moving, in metres per second
     * @param tint    a colour to shift the coat by, so a party is telling apart
     * @param sunk    how far the body is below its feet's height — what wading
     *                and swimming push it down by, in metres
     */
    public static void walker(Mesh.Builder mesh, double x, double y, double z,
                              double yaw, boolean crouching, double phase, double speed,
                              int tint, double sunk) {
        float[] uv = new float[4];
        WatchMaterials.uv(WatchMaterial.PLANK, uv);

        double height = crouching ? CROUCH_HEIGHT : HEIGHT;
        double base = z - sunk;
        int coat = tint;
        int skin = WatchMaterials.shade(WatchMaterial.CLAY);
        int boot = WatchMaterials.shade(WatchMaterial.DARK_BARK);
        int hat = WatchMaterials.shade(WatchMaterial.DRY_GRASS);

        // How hard the limbs are swinging: nothing standing still, everything at
        // a jog, and the phase runs at whatever the caller's clock says.
        double drive = Math.min(1, speed / SWING_REFERENCE);
        double swing = Math.sin(phase * Math.PI * 2) * SWING * drive;

        double cos = Math.cos(yaw), sin = Math.sin(yaw);
        // The body's own axes: "forward" is where they are looking, "side" is
        // their right. Every offset below is written in those rather than in
        // world x and y, which is what makes the whole figure turn as one.
        double fx = sin, fy = -cos;
        double sx = cos, sy = sin;

        double hipZ = base + height * 0.47;
        double legLength = height * 0.45;

        // Legs, swinging opposite one another about the hip.
        for (int side = -1; side <= 1; side += 2) {
            double lean = side > 0 ? swing : -swing;
            double footForward = Math.sin(lean) * legLength * 0.5;
            double drop = Math.cos(lean) * legLength * 0.5;
            double lx = x + sx * side * 0.09 + fx * footForward;
            double ly = y + sy * side * 0.09 + fy * footForward;
            Shapes.box(mesh, lx, ly, hipZ - drop, 0.075, 0.085, legLength * 0.5,
                    yaw, uv, coat);
            // A boot, so a leg reads as having an end to it.
            double bootForward = Math.sin(lean) * legLength;
            Shapes.box(mesh, x + sx * side * 0.09 + fx * bootForward,
                    y + sy * side * 0.09 + fy * bootForward,
                    hipZ - Math.cos(lean) * legLength + 0.04,
                    0.085, 0.115, 0.05, yaw, uv, boot);
        }

        // Torso.
        Shapes.box(mesh, x, y, base + height * 0.68, 0.145, 0.22, height * 0.18,
                yaw, uv, coat);

        // Arms, opposite the legs on each side — which is what a person does.
        double shoulderZ = base + height * 0.80;
        double armLength = height * 0.36;
        for (int side = -1; side <= 1; side += 2) {
            double lean = side > 0 ? -swing : swing;
            double handForward = Math.sin(lean) * armLength * 0.5;
            double drop = Math.cos(lean) * armLength * 0.5;
            Shapes.box(mesh, x + sx * side * 0.20 + fx * handForward,
                    y + sy * side * 0.20 + fy * handForward,
                    shoulderZ - drop, 0.06, 0.07, armLength * 0.5, yaw, uv, coat);
            Shapes.box(mesh, x + sx * side * 0.20 + fx * Math.sin(lean) * armLength,
                    y + sy * side * 0.20 + fy * Math.sin(lean) * armLength,
                    shoulderZ - Math.cos(lean) * armLength + 0.04,
                    0.055, 0.055, 0.055, yaw, uv, skin);
        }

        // Head, and the hat brim that makes a walker readable at two hundred
        // metres, which is further than the name label is legible.
        Shapes.box(mesh, x, y, base + height * 0.94, 0.115, 0.115, 0.115, yaw, uv, skin);
        Shapes.box(mesh, x, y, base + height * 1.02, 0.27, 0.27, 0.022, yaw, uv, hat);
        Shapes.box(mesh, x, y, base + height * 1.05, 0.135, 0.135, 0.05, yaw, uv, hat);
        // A pack, because everybody in this game is carrying a satchel.
        Shapes.box(mesh, x - fx * 0.20, y - fy * 0.20, base + height * 0.70,
                0.12, 0.09, 0.14, yaw, uv, WatchMaterials.shade(WatchMaterial.BARK));
    }

    /**
     * The two arms, seen from inside your own head.
     *
     * <p>Placed from the camera's basis, not the world's: the caller hands over
     * the eye position and the three axes it is looking along, and the arms are
     * built at fixed offsets in that frame. So they follow the view exactly,
     * which is what a first-person model has to do, and they are ordinary world
     * triangles once they are built, which means they are depth-tested, fogged
     * and shaded by whichever backend is running without a special case
     * anywhere.
     *
     * <p><b>They live outside the near plane on purpose.</b> {@code EyeCamera}
     * clips at {@value com.larsons.engine.graphics.EyeCamera#NEAR} metres and
     * anything nearer is thrown away, so arms at arm's length would simply not
     * be drawn on the painter path. These sit at {@link #HAND_FORWARD} metres,
     * which is past the near plane and still close enough to read as your own
     * hands rather than as somebody standing in front of you.
     *
     * @param bob    how far through the head-bob cycle, in turns
     * @param sway   how strongly to bob, {@code 0} still to {@code 1} running
     * @param reach  how far through a reaching gesture, {@code 0}–{@code 1} —
     *               what picking something plays
     */
    public static void hands(Mesh.Builder mesh, double eyeX, double eyeY, double eyeZ,
                             double dirX, double dirY, double dirZ,
                             double rightX, double rightY, double bob, double sway,
                             double reach, int sleeve) {
        float[] uv = new float[4];
        WatchMaterials.uv(WatchMaterial.PLANK, uv);
        int skin = WatchMaterials.shade(WatchMaterial.CLAY);

        // Up, from the two axes we were given. The camera's right is horizontal
        // by construction, so up is right × direction.
        double upX = rightY * dirZ;
        double upY = -rightX * dirZ;
        double upZ = rightX * dirY - rightY * dirX;
        double length = Math.sqrt(upX * upX + upY * upY + upZ * upZ);
        if (length > 1e-9) {
            upX /= length;
            upY /= length;
            upZ /= length;
        }

        double yaw = Math.atan2(dirX, -dirY);
        double bobUp = Math.sin(bob * Math.PI * 2) * 0.035 * sway;
        double bobSide = Math.sin(bob * Math.PI) * 0.028 * sway;

        for (int side = -1; side <= 1; side += 2) {
            // The right hand reaches; the left stays where it is. One hand
            // moving is what a person picking a berry looks like.
            double forward = HAND_FORWARD + (side > 0 ? reach * 0.42 : 0);
            double out = HAND_SIDE * side + bobSide * side;
            double down = HAND_DROP - bobUp - (side > 0 ? reach * 0.16 : 0);

            double cx = eyeX + dirX * forward + rightX * out + upX * -down;
            double cy = eyeY + dirY * forward + rightY * out + upY * -down;
            double cz = eyeZ + dirZ * forward + upZ * -down;

            // A forearm along the view direction, and a fist on the end of it.
            Shapes.box(mesh, cx - dirX * 0.16, cy - dirY * 0.16, cz - dirZ * 0.16,
                    0.055, 0.16, 0.055, yaw, uv, sleeve);
            Shapes.box(mesh, cx, cy, cz, 0.062, 0.062, 0.062, yaw, uv, skin);
        }
    }

    /** How far in front of the eye the fists sit, in metres. See {@link #hands}. */
    public static final double HAND_FORWARD = 1.05;

    /** How far to either side. */
    public static final double HAND_SIDE = 0.42;

    /** How far below the eye line. */
    public static final double HAND_DROP = 0.42;

    /**
     * A coat colour for a walker, from their id.
     *
     * <p>Eight players in one wood all wearing the same green is eight people
     * nobody can tell apart at range, and the name labels only carry so far.
     * Six hues, evenly spaced, deterministic in the id so the same person is
     * the same colour to everybody.
     */
    public static int coatFor(int playerId) {
        int[] coats = {
            0x4A6B33, // moss
            0x2F5C7A, // slate blue
            0x7A4630, // rust
            0x5C4A7A, // heather
            0x6B6330, // ochre
            0x2F6B5C, // teal
        };
        return coats[Math.floorMod(playerId, coats.length)];
    }
}
