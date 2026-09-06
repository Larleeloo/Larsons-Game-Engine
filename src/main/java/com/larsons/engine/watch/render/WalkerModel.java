package com.larsons.engine.watch.render;

import com.larsons.engine.watch.Cosmetics;
import com.larsons.engine.watch.world.WatchMaterial;
import com.larsons.engine.watch.world.WatchMaterials;

import java.util.List;

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
 *   <li>for <b>other players</b>, whole, at whatever they are doing;</li>
 *   <li>in a <b>boat</b>, sitting down and {@linkplain #rower working the
 *       oars}, which is a different animal from a standing figure and is
 *       therefore a different method.</li>
 * </ul>
 *
 * <p>And all four of them can be <b>dressed</b>. Whatever a player bought off a
 * keeper's clothes rail is hung on this figure <em>after</em> it is drawn —
 * see {@link CosmeticModel}, and {@link com.larsons.engine.watch.Cosmetics} for
 * why that is the specification rather than a description. Nothing in the pose
 * below knows or cares: each method takes a list of keys it passes straight on,
 * and with the list empty every one of them emits the mesh it always did.
 *
 * <h2>The gait</h2>
 *
 * <p>Every limb <b>pivots</b>, about a hip or a shoulder, with a knee and an
 * elbow between the pivot and the end of it. That is what changed, and it is
 * the whole of why the walk stopped looking like a fault: the old version slid
 * upright boxes along the arc a thigh would have swept without ever turning
 * them, so at any part of the stride except the middle a leg was a rectangle
 * floating beside a boot, both drifting past a hip that neither was attached
 * to. Four struts and two angles per leg cost the same twenty-four triangles
 * and read as a person.
 *
 * <p><b>The hips are not given a height; they are solved for one.</b> A figure
 * whose legs swing about a fixed hip has feet that sink into the ground at the
 * middle of the stride and float above it at the ends, because a leg at an
 * angle does not reach as far down as a leg hanging straight. So the legs are
 * posed first, the lower foot is put on the ground, and the body is hung from
 * that — which produces the rise and fall of a real walk for free, in step with
 * the stride by construction, and scaled down to nothing as the walker slows
 * because the swing it comes from is.
 *
 * <p>Everything is emitted through {@link Shapes}, into the same
 * {@link Mesh.Builder} the animals and the world use, so a walker costs about
 * the same as a small deer and is drawn by exactly the same code on both
 * backends.
 */
public final class WalkerModel {

    /** How tall a standing walker is, in metres. */
    public static final double HEIGHT = 1.78;

    /** …and a crouched one. */
    public static final double CROUCH_HEIGHT = 1.18;

    /**
     * How far a thigh swings from vertical at a full walk, in radians.
     *
     * <p>Shorter than it looks: the hip rises and falls by the leg's length
     * times {@code 1 − cos} of this, so an ambitious stride is also a figure
     * pogoing up and down by a foot. Twenty-six degrees is a stride of about
     * eighty centimetres on a leg of eighty, which is a long walking step and a
     * short running one — and this game's "walk" is four and a half metres a
     * second, so it is being asked to cover both.
     */
    private static final double SWING = 0.45;

    /** The speed the swing is measured against, in metres per second. */
    private static final double SWING_REFERENCE = 4.4;

    /**
     * How much harder than a walk the limbs can be driven.
     *
     * <p>A sprint is {@code RUN_SPEED} — nearly twice the reference — and
     * without this it would be a walk cycle played faster, which is what a
     * cheap run animation looks like. A quarter more swing is enough that
     * somebody running past reads as running.
     */
    private static final double DRIVE_CAP = 1.25;

    /** How far a knee folds at the top of the swing, in radians. */
    private static final double KNEE_BEND = 0.85;

    /** How far below the ankle a boot's sole is, in metres. */
    private static final double BOOT_DROP = 0.015, BOOT_SOLE = 0.05;

    /** How far an elbow stays folded, and how much more on the forward swing. */
    private static final double ELBOW_REST = 0.22, ELBOW_BEND = 0.45;

    /** How fast a jump leaves the ground, for scaling the pose. See {@link Leap}. */
    private static final double LEAP_REFERENCE = 4.6;

    /** How far a thigh comes up at the top of a jump, and how far a knee folds. */
    private static final double TUCK_LEAN = 0.34, TUCK_KNEE = 1.20;

    /** …and how far they reach for the ground on the way down. */
    private static final double REACH_LEAN = 0.12, REACH_KNEE = 0.30;

    /** How far both knees fold to absorb a full-speed landing. */
    private static final double ABSORB_KNEE = 0.85;

    /**
     * Where the arms go at the top of a jump and at the bottom of a fall, in
     * radians from hanging straight down.
     *
     * <p>Up and forward on the way up, out and low on the way down. Larger than
     * anything the walk uses, deliberately: from behind, a jumping figure's
     * legs are under its own body and its arms are the only part with a
     * silhouette of their own.
     */
    private static final double AIR_ARM_RISE = 2.55, AIR_ARM_FALL = 1.15;

    /**
     * Where the neck is, as a share of the height.
     *
     * <p>Between the top of the chest box (0.86) and the underside of the head
     * (0.94 less its own half-width), which is the gap a collar goes in. Named
     * because {@link CosmeticModel} writes every neck piece against it: put a
     * scarf at the middle of the chest instead and it is a scarf inside
     * somebody, since the chest is 0.22 m deep and a collar is not.
     */
    private static final double NECK = 0.875;

    /**
     * What somebody with nothing on is wearing.
     *
     * <p>Named rather than written as an empty list at each call site, because
     * the whole promise of {@link CosmeticModel} is that this is the ordinary
     * case and that it costs nothing — and a promise is easier to keep when it
     * has a name to grep for.
     */
    public static final List<String> WEARING_NOTHING = List.of();

    private WalkerModel() {}

    /**
     * What a walker's legs are doing that is not walking.
     *
     * <p>Three numbers rather than a state, because the pose has to be
     * <b>continuous</b> in all of them: a figure that switches from a walk pose
     * to a jump pose on the frame its feet leave the ground shows a cut, and a
     * cut is the thing every part of this animation is written to avoid. Each
     * of these blends the walk out and the leap in.
     *
     * @param air    how far off the ground the pose is, {@code 0} standing to
     *               {@code 1} clear of it
     * @param climb  how fast they are rising, in metres per second; negative
     *               falling. What decides whether the legs tuck under or reach
     *               down, which is the whole difference between the top of a
     *               jump and the bottom of one
     * @param settle how much of a landing is still being absorbed, {@code 1} on
     *               touchdown down to {@code 0} standing
     */
    public record Leap(double air, double climb, double settle) {

        /** Both feet on the ground and nothing to absorb. */
        public static final Leap GROUNDED = new Leap(0, 0, 0);

        /**
         * Whether any of it does anything.
         *
         * <p>Against a tolerance rather than against zero, because both numbers
         * are eased and an eased number approaches zero without ever arriving:
         * tested exactly, a walker who landed a minute ago is still officially
         * mid-jump by a millimetre for the rest of the session.
         */
        public boolean still() {
            return air < 1e-3 && settle < 1e-3;
        }
    }

    /**
     * One walker, standing, crouching, walking, running, jumping or swimming.
     *
     * <p>Not rowing: see {@link #rower}, which sits the same person down and
     * puts their hands on the oars. Passing a rower through here is what drew
     * somebody sprinting on the spot in a boat travelling at nine and a half
     * metres a second.
     *
     * @param mesh    where the triangles go
     * @param x       position relative to the mesh's origin
     * @param z       the ground under their feet, in world metres
     * @param yaw     which way they are facing, in radians
     * <p><b>Nothing is subtracted for swimming.</b> A walker's {@code z} is
     * where their feet are, and a diver's feet are already below the waterline
     * — the dive is in that number rather than on top of it, which is why this
     * takes no depth of its own. Handing one over as well drew a diver a second
     * dive-depth down, through the lake bed.
     *
     * @param phase   the gait clock, in turns — {@code 0}–{@code 1}
     * @param speed   how fast they are moving, in metres per second
     * @param leap    what the legs are doing besides walking; {@link Leap#GROUNDED}
     *                for somebody with both feet down
     * @param tint    a colour to shift the coat by, so a party is telling apart
     */
    public static void walker(Mesh.Builder mesh, double x, double y, double z,
                              double yaw, boolean crouching, double phase, double speed,
                              Leap leap, int tint) {
        walker(mesh, x, y, z, yaw, crouching, phase, speed, leap, tint, WEARING_NOTHING);
    }

    /**
     * The same walker, dressed.
     *
     * <p><b>Over the top, never instead of.</b> Every box below is drawn
     * exactly as it was before any of this existed and the clothes are hung on
     * the joints afterwards, which is why an empty {@code worn} produces the
     * identical mesh — {@code CosmeticsTest.anUndressedWalkerIsTheWalkerItAlwaysWas}
     * holds that to the triangle. See {@link com.larsons.engine.watch.Cosmetics}.
     *
     * @param worn what they have on, as {@code Outfit.wornKeys} — slot order,
     *             at most one piece per slot
     */
    public static void walker(Mesh.Builder mesh, double x, double y, double z,
                              double yaw, boolean crouching, double phase, double speed,
                              Leap leap, int tint, List<String> worn) {
        float[] uv = new float[4];
        WatchMaterials.uv(WatchMaterial.PLANK, uv);

        double height = crouching ? CROUCH_HEIGHT : HEIGHT;
        double base = z;
        int coat = tint;
        int skin = WatchMaterials.shade(WatchMaterial.CLAY);
        int boot = WatchMaterials.shade(WatchMaterial.DARK_BARK);

        // How hard the limbs are swinging: nothing standing still, everything at
        // a jog, and a quarter more at a sprint. The phase runs at whatever the
        // caller's clock says — see Gait.cadence, which is where it comes from.
        double drive = Math.min(DRIVE_CAP, Math.max(0, speed) / SWING_REFERENCE);
        double turn = RowStroke.wrap(phase) * Math.PI * 2;

        // Off the ground: how much of the pose is the jump's rather than the
        // walk's, and which half of the jump it is. `tuck` runs from 1 at the
        // top of the push-off to 0 at the fastest part of the fall, so the legs
        // come up under the body on the way up and reach for the ground on the
        // way down — which is what tells a player at a glance which way they
        // are going.
        double air = Math.max(0, Math.min(1, leap.air()));
        double settle = Math.max(0, Math.min(1, leap.settle()));
        double rise = Math.max(-1, Math.min(1, leap.climb() / LEAP_REFERENCE));
        double tuck = 0.5 + 0.5 * rise;

        double cos = Math.cos(yaw), sin = Math.sin(yaw);
        // The body's own axes: "forward" is where they are looking, "side" is
        // their right. Every offset below is written in those rather than in
        // world x and y, which is what makes the whole figure turn as one.
        double fx = sin, fy = -cos;
        double sx = cos, sy = sin;

        double hipZ = height * 0.47;
        double legLength = height * 0.45;
        double thigh = legLength * 0.52, shin = legLength * 0.48;
        // Where the lower ankle has to end up: high enough that the sole of the
        // boot on it is exactly on the ground. Taken from the boot rather than
        // from the leg, because the boot is the part that touches — measuring
        // it from the leg leaves whichever walker is being drawn floating or
        // buried by the depth of a sole, everywhere, for ever.
        double ankleRest = BOOT_DROP + BOOT_SOLE;

        // Pose both legs about a hip at the origin, then find how far the whole
        // body has to drop for the lower foot to be standing on the ground.
        double[] kneeAlong = new double[2], kneeUp = new double[2];
        double[] ankleAlong = new double[2], ankleUp = new double[2];
        double lowest = Double.MAX_VALUE;
        for (int i = 0; i < 2; i++) {
            double side = i == 0 ? -1 : 1;
            double angle = turn + (side > 0 ? 0 : Math.PI);
            double stride = Math.sin(angle) * SWING * drive;
            // The knee folds while the leg is swinging through, which is when
            // it is moving forward — squared so it eases in and out rather than
            // switching on at the crossing.
            double forward = Math.max(0, Math.cos(angle));
            double walkBend = KNEE_BEND * drive * forward * forward;

            // In the air both legs do the same thing, plus a quarter of the
            // stride they were mid-way through: exactly symmetric is a shop
            // dummy falling over, and a trailing leg is what a person jumping
            // actually has.
            double lean = mix(stride, mix(REACH_LEAN, TUCK_LEAN, tuck) + stride * 0.25, air);
            double bend = mix(walkBend, mix(REACH_KNEE, TUCK_KNEE, tuck), air);
            // …and on the way back up out of a landing, both knees fold to take
            // it. The feet are planted through all of this, so the fold is what
            // drops the hips — the dip comes out of the geometry rather than
            // being a separate number pushing the body down.
            bend += ABSORB_KNEE * settle;

            kneeAlong[i] = Math.sin(lean) * thigh;
            kneeUp[i] = hipZ - Math.cos(lean) * thigh;
            ankleAlong[i] = kneeAlong[i] + Math.sin(lean - bend) * shin;
            ankleUp[i] = kneeUp[i] - Math.cos(lean - bend) * shin;
            lowest = Math.min(lowest, ankleUp[i]);
        }
        // <b>Only while there is ground to stand on.</b> The plant is what puts
        // the lower boot on the floor; off the ground there is no floor, and
        // applying it would hang the whole body from whichever foot was lowest
        // and turn a tuck into a squat that never rises.
        double lift = (1 - air) * (ankleRest - lowest);

        // A walker leans into a run, into a jump, and into the landing. Small,
        // and derived from the same numbers, so each arrives and leaves with
        // the thing that caused it.
        double leanIn = 0.11 * drive + 0.16 * air * tuck + 0.22 * settle;

        for (int i = 0; i < 2; i++) {
            double side = i == 0 ? -1 : 1;
            double hx = x + sx * side * 0.09, hy = y + sy * side * 0.09;
            double kx = hx + fx * kneeAlong[i], ky = hy + fy * kneeAlong[i];
            double ax = hx + fx * ankleAlong[i], ay = hy + fy * ankleAlong[i];
            Shapes.strut(mesh, hx, hy, base + lift + hipZ, kx, ky, base + lift + kneeUp[i],
                    0.082, 0.082, sx, sy, 0, uv, coat);
            Shapes.strut(mesh, kx, ky, base + lift + kneeUp[i],
                    ax, ay, base + lift + ankleUp[i], 0.072, 0.072, sx, sy, 0, uv, coat);
            // A boot, so a leg reads as having an end to it. Kept level and
            // yawed with the body: a foot rolls through a step, but a box that
            // rolled with a boxy shin would only show the gap between them.
            Shapes.box(mesh, ax + fx * 0.03, ay + fy * 0.03,
                    base + lift + ankleUp[i] - BOOT_DROP, 0.085, 0.115, BOOT_SOLE,
                    yaw, uv, boot);
            // …and whatever is buckled over it. On the boot rather than on the
            // shin, so a gaiter follows the foot through the stride instead of
            // hanging off the leg above it.
            CosmeticModel.wear(mesh, worn, Cosmetics.Slot.FEET,
                    CosmeticModel.Fit.upright(ax + fx * 0.03, ay + fy * 0.03,
                            base + lift + ankleUp[i] - BOOT_DROP, yaw, 0.085), coat);
        }

        double shoulderZ = base + lift + height * 0.80;
        // Everything above the hips leans as one piece, by however far it is
        // above them — a chest that slid forward on its own and left the head
        // where it was would be a person folding in half.
        double lead = Math.sin(leanIn);
        double chestAlong = lead * (height * 0.68 - hipZ);
        double shoulderAlong = lead * (height * 0.80 - hipZ);
        double headAlong = lead * (height * 0.94 - hipZ);
        Shapes.box(mesh, x + fx * chestAlong, y + fy * chestAlong,
                base + lift + height * 0.68, 0.145, 0.22, height * 0.18, yaw, uv, coat);

        // Arms, opposite the legs on each side — which is what a person does.
        double armLength = height * 0.36;
        double upper = armLength * 0.52, fore = armLength * 0.48;
        for (int i = 0; i < 2; i++) {
            double side = i == 0 ? -1 : 1;
            double angle = turn + (side > 0 ? Math.PI : 0);
            double swung = Math.sin(angle) * SWING * 0.85 * drive;
            double forward = Math.max(0, Math.cos(angle));
            double walkBend = ELBOW_REST + ELBOW_BEND * drive * forward * forward;
            // Arms go up with the jump and come down and out with the fall, and
            // then forward for balance out of the landing. This is most of what
            // makes a jump read as a jump: the legs are under the body and
            // mostly hidden, and the arms are the part with a whole silhouette
            // to themselves.
            double lean = mix(swung, mix(AIR_ARM_FALL, AIR_ARM_RISE, tuck), air)
                    + settle * 0.55;
            // Nearly straight in the air. An elbow folds the forearm further
            // round the way the arm is already swung, so the fold that reads as
            // a bent arm hanging down wraps a raised one back over the head.
            double bend = mix(walkBend, 0.14, air);
            double ex = Math.sin(lean) * upper;
            double eu = -Math.cos(lean) * upper;
            double wx = ex + Math.sin(lean + bend) * fore;
            double wu = eu - Math.cos(lean + bend) * fore;

            double shX = x + sx * side * 0.20 + fx * shoulderAlong;
            double shY = y + sy * side * 0.20 + fy * shoulderAlong;
            Shapes.strut(mesh, shX, shY, shoulderZ,
                    shX + fx * ex, shY + fy * ex, shoulderZ + eu,
                    0.062, 0.062, sx, sy, 0, uv, coat);
            Shapes.strut(mesh, shX + fx * ex, shY + fy * ex, shoulderZ + eu,
                    shX + fx * wx, shY + fy * wx, shoulderZ + wu,
                    0.055, 0.055, sx, sy, 0, uv, coat);
            Shapes.box(mesh, shX + fx * wx, shY + fy * wx, shoulderZ + wu - 0.02,
                    0.055, 0.055, 0.055, yaw, uv, skin);
            CosmeticModel.wear(mesh, worn, Cosmetics.Slot.HANDS,
                    CosmeticModel.Fit.upright(shX + fx * wx, shY + fy * wx,
                            shoulderZ + wu - 0.02, yaw, 0.055), coat);
        }

        head(mesh, x + fx * headAlong, y + fy * headAlong, base + lift, height, yaw,
                uv, skin, worn, coat);
        // A pack, because everybody in this game is carrying a satchel.
        Shapes.box(mesh, x + fx * (chestAlong - 0.20), y + fy * (chestAlong - 0.20),
                base + lift + height * 0.70, 0.12, 0.09, 0.14, yaw, uv,
                WatchMaterials.shade(WatchMaterial.BARK));
        // What is over the pack, and what is round the throat. Both are hung
        // off the leaning upper body rather than off the world, so a cape
        // leaning into a sprint leans with the back it is buckled to.
        CosmeticModel.wear(mesh, worn, Cosmetics.Slot.BACK,
                CosmeticModel.Fit.upright(x + fx * (chestAlong - 0.20),
                        y + fy * (chestAlong - 0.20), base + lift + height * 0.70,
                        yaw, 0.115), coat);
        CosmeticModel.wear(mesh, worn, Cosmetics.Slot.NECK,
                CosmeticModel.Fit.upright(x + fx * shoulderAlong, y + fy * shoulderAlong,
                        base + lift + height * NECK, yaw, 0.115), coat);
    }

    /**
     * The head, and the hat brim that makes a walker readable at two hundred
     * metres — which is further than the name label is legible.
     *
     * <p>Upright, from the height of the feet under it.
     */
    private static void head(Mesh.Builder mesh, double x, double y, double base,
                             double height, double yaw, float[] uv, int skin,
                             List<String> worn, int coat) {
        head(mesh, x, y, base + height * 0.94, 0, 0, 1, height, yaw, uv, skin, worn, coat);
    }

    /**
     * The same, at a given point and stacked along a given direction.
     *
     * <p>A hat sits <b>on</b> a head rather than above it in world terms, and
     * for everybody standing up those are the same sentence. For a swimmer they
     * are not: laid out along the world's vertical, the brim of a prone
     * swimmer's hat floats off the side of their head and follows them across
     * the lake like a small yellow raft.
     *
     * <p><b>Every pose's clothes go through here</b>, which is the point of
     * hanging the head and face slots on this method rather than on
     * {@link #walker}: a walker, a rower and a swimmer all finish by drawing
     * their head with this one call, so a hat bought at a counter is on the
     * figure whether it is standing on a trail, sitting in a boat or half under
     * a lake — and what keeps a swimmer's veil hanging down their face rather
     * than off the side of it is the {@code up} this already takes.
     *
     * <p>The rest of an outfit is on the {@linkplain #walker standing figure}
     * only, and that is a decision rather than an oversight: a scarf and a cape
     * are cut for a body that is upright, a rower's is folded onto a thwart and
     * a swimmer's is mostly under water, and hanging a full-length oilskin off
     * either would be three pieces of geometry through a hull.
     *
     * @param upX the way the top of the head points; a unit vector
     */
    private static void head(Mesh.Builder mesh, double cx, double cy, double cz,
                             double upX, double upY, double upZ,
                             double height, double yaw, float[] uv, int skin,
                             List<String> worn, int coat) {
        int hat = WatchMaterials.shade(WatchMaterial.DRY_GRASS);
        double brim = height * (1.02 - 0.94), crown = height * (1.05 - 0.94);
        Shapes.box(mesh, cx, cy, cz, 0.115, 0.115, 0.115, yaw, uv, skin);
        Shapes.box(mesh, cx + upX * brim, cy + upY * brim, cz + upZ * brim,
                0.27, 0.27, 0.022, yaw, uv, hat);
        Shapes.box(mesh, cx + upX * crown, cy + upY * crown, cz + upZ * crown,
                0.135, 0.135, 0.05, yaw, uv, hat);
        // On the face at the middle of the head, and on the head at the top of
        // the crown the figure already has — `top` is read off the box just
        // drawn rather than written out again, so a beanie sits on the hat
        // instead of inside it however the hat is later retimed.
        CosmeticModel.wear(mesh, worn, Cosmetics.Slot.FACE,
                new CosmeticModel.Fit(cx, cy, cz, upX, upY, upZ, yaw, 0.115), coat);
        double top = crown + 0.05;
        CosmeticModel.wear(mesh, worn, Cosmetics.Slot.HEAD,
                new CosmeticModel.Fit(cx + upX * top, cy + upY * top, cz + upZ * top,
                        upX, upY, upZ, yaw, 0.115), coat);
    }

    /**
     * The same person, sitting in a boat and working the oars.
     *
     * <p><b>Its own pose, because rowing is its own thing.</b> A rower put
     * through {@link #walker} is a standing figure with its legs driven by the
     * boat's speed — and a boat does {@code Boats.ROW_SPEED}, which is above a
     * sprint, so what a player used to see in third person was somebody
     * sprinting on the spot inside a hull, at five paces a second, with the
     * oars stowed along the rail beside them. Here the legs are folded onto the
     * floorboards where a seated person's legs go, and the hands are put
     * <em>on the handles</em> — {@link BoatModel#handle} is the one description
     * of where those are, so the grip cannot drift off the oar however the
     * stroke is retimed.
     *
     * @param waterZ the boat's waterline, before the swell — the same number
     *               handed to {@link BoatModel#boat}
     * @param bob    the hull's bobbing clock, so the rower rides the same swell
     * @param stroke how far through a stroke of the oars, in turns
     */
    public static void rower(Mesh.Builder mesh, double x, double y, double waterZ,
                             double yaw, double bob, double stroke, int tint) {
        rower(mesh, x, y, waterZ, yaw, bob, stroke, tint, WEARING_NOTHING);
    }

    /** The same rower, in whatever hat and spectacles they bought. See {@link #head}. */
    public static void rower(Mesh.Builder mesh, double x, double y, double waterZ,
                             double yaw, double bob, double stroke, int tint,
                             List<String> worn) {
        float[] uv = new float[4];
        WatchMaterials.uv(WatchMaterial.PLANK, uv);
        int coat = tint;
        int skin = WatchMaterials.shade(WatchMaterial.CLAY);
        int boot = WatchMaterials.shade(WatchMaterial.DARK_BARK);

        double cos = Math.cos(yaw), sin = Math.sin(yaw);
        double fx = sin, fy = -cos;
        double sx = cos, sy = sin;

        double seat = BoatModel.thwartZ(waterZ, bob);
        double floor = BoatModel.floorZ(waterZ, bob);
        double hipsZ = seat + 0.11;
        // Forward at the finish, upright at the catch: a push stroke leans into
        // the handles. Half a radian either way would be a rower falling over;
        // this is the swing you can see from the bank and no more.
        double swing = 0.5 + 0.5 * RowStroke.reach(stroke);
        double leanIn = 0.30 * swing - 0.06;
        double hipsAlong = BoatModel.SEAT_ALONG;

        // Legs: thighs forward along the thwart, shins down to the floorboards.
        for (int i = 0; i < 2; i++) {
            double side = i == 0 ? -1 : 1;
            double hx = x + sx * side * 0.11 + fx * hipsAlong;
            double hy = y + sy * side * 0.11 + fy * hipsAlong;
            double kneeAlong = hipsAlong + 0.44;
            double kx = x + sx * side * 0.13 + fx * kneeAlong;
            double ky = y + sy * side * 0.13 + fy * kneeAlong;
            double kneeZ = hipsZ + 0.03;
            double footAlong = kneeAlong + 0.13;
            double ax = x + sx * side * 0.13 + fx * footAlong;
            double ay = y + sy * side * 0.13 + fy * footAlong;
            double footZ = floor + 0.06;
            Shapes.strut(mesh, hx, hy, hipsZ, kx, ky, kneeZ,
                    0.082, 0.082, sx, sy, 0, uv, coat);
            Shapes.strut(mesh, kx, ky, kneeZ, ax, ay, footZ,
                    0.072, 0.072, sx, sy, 0, uv, coat);
            Shapes.box(mesh, ax + fx * 0.03, ay + fy * 0.03, footZ - 0.02,
                    0.085, 0.115, 0.05, yaw, uv, boot);
        }

        // Torso, from the hips up to a pair of shoulders that lean with the
        // stroke, and on past them to the neck — the arm hangs from a point
        // part way up the chest, not off the top of it, the same proportion a
        // standing walker's does. Drawn as a box rather than a strut because it
        // is wider than it is deep, and a strut's cross-section is squared to
        // the world rather than to the body it belongs to.
        double torso = 0.50, neck = torso + HEIGHT * 0.06;
        double shoulderAlong = hipsAlong + Math.sin(leanIn) * torso;
        double shoulderZ = hipsZ + Math.cos(leanIn) * torso;
        double neckAlong = hipsAlong + Math.sin(leanIn) * neck;
        double neckZ = hipsZ + Math.cos(leanIn) * neck;
        Shapes.box(mesh, x + fx * (hipsAlong + neckAlong) / 2,
                y + fy * (hipsAlong + neckAlong) / 2,
                (hipsZ + neckZ) / 2, 0.145, 0.20, (neckZ - hipsZ) / 2, yaw, uv, coat);

        // Arms, solved onto the handles rather than swung at them.
        double armLength = HEIGHT * 0.36;
        double upper = armLength * 0.52, fore = armLength * 0.48;
        double[] grip = new double[3];
        double[] elbow = new double[3];
        for (int side = -1; side <= 1; side += 2) {
            BoatModel.handle(stroke, side, grip);
            double gx = x + fx * grip[0] + sx * grip[1];
            double gy = y + fy * grip[0] + sy * grip[1];
            double gz = waterZ + BoatModel.heave(bob) + grip[2];
            double shX = x + fx * shoulderAlong + sx * side * 0.20;
            double shY = y + fy * shoulderAlong + sy * side * 0.20;
            // Elbows out and down, which is where a rower's go and which is the
            // one choice that never folds an arm through the chest.
            fold(shX, shY, shoulderZ, gx, gy, gz, upper, fore,
                    sx * side, sy * side, -1.4, elbow);
            Shapes.strut(mesh, shX, shY, shoulderZ, elbow[0], elbow[1], elbow[2],
                    0.062, 0.062, sx, sy, 0, uv, coat);
            Shapes.strut(mesh, elbow[0], elbow[1], elbow[2], gx, gy, gz,
                    0.055, 0.055, sx, sy, 0, uv, coat);
            Shapes.box(mesh, gx, gy, gz, 0.055, 0.055, 0.055, yaw, uv, skin);
        }

        // Sitting on the neck rather than a fixed distance over the seat, so a
        // rower leaning through the stroke takes their head with them. The
        // offset is the standing figure's own: 0.94 of the height for the head
        // against 0.86 for the top of the chest.
        head(mesh, x + fx * neckAlong, y + fy * neckAlong,
                neckZ - HEIGHT * 0.86, HEIGHT, yaw, uv, skin, worn, coat);
    }

    // --- swimming ---------------------------------------------------------------------

    /**
     * How far a swimmer's body is tipped from horizontal, in radians —
     * {@code π/2} bolt upright, {@code 0} lying flat, negative going down.
     *
     * <p><b>Treading water and swimming are the same pose at two speeds.</b>
     * Somebody bobbing about at the surface is vertical; somebody actually
     * going somewhere is prone; and there is no third thing to model, because
     * the whole of the difference between them is how far this one angle has
     * tipped. Interpolating it also means walking into a lake, floating, and
     * setting off is one continuous movement rather than three poses cutting
     * between each other — a walker at the water's edge is this pose at
     * {@code π/2}, which is exactly the standing figure.
     *
     * <p>Under the surface it lies along the way they are looking, because that
     * is the way they are travelling — see {@code WatchScene.walk}, where a
     * submerged player's movement follows their pitch. At the surface it
     * settles at {@link #SURFACE_PITCH} instead: head and shoulders out of the
     * water, body trailing down behind.
     */
    public static double swimPitch(double speed, double lookPitch, boolean submerged) {
        double flat = submerged ? lookPitch : SURFACE_PITCH;
        return UPRIGHT + (flat - UPRIGHT) * swimDrive(speed);
    }

    /**
     * How hard somebody in the water is swimming, {@code 0} treading to
     * {@code 1} making way.
     *
     * <p>The same number {@link #swimPitch} tips the body by, because it is the
     * same distinction: a stroke that takes you somewhere and a scull that
     * holds you where you are differ in how far the limbs travel as much as in
     * how far the body has laid down. Without it, somebody floating still
     * pointed both arms at the sky once every three and a half seconds — the
     * glide of a stroke they were not swimming.
     */
    public static double swimDrive(double speed) {
        return Math.min(1, Math.max(0, speed) / SWIM_REFERENCE);
    }

    /** Upright: the pose a swimmer going nowhere holds, and the standing one. */
    private static final double UPRIGHT = Math.PI / 2;

    /**
     * How far a surface swimmer's body slopes down behind them, in radians.
     *
     * <p>Not flat, and the reason is the waterline. The game floats a swimmer
     * with their feet {@code WatchScene.FLOAT_DEPTH} under the surface and puts
     * their eye — and so this model's head — an eye-height above that, which is
     * about half a metre clear of the water. Laid out truly flat from there,
     * the whole body would be drawn <em>above</em> the surface, swimming
     * through the air. Sloped, the head and shoulders are out and everything
     * from the chest down is under, which is what a breaststroker looks like
     * and is what the depth the game already tracks actually describes.
     */
    private static final double SURFACE_PITCH = 0.52;

    /**
     * The speed at which a swimmer is fully prone, in metres per second.
     *
     * <p>About seven tenths of {@code WatchScene.SWIM_SPEED}, so anybody
     * swimming at any real pace is flat and only somebody nudging themselves
     * gently about stays upright.
     */
    private static final double SWIM_REFERENCE = 1.7;

    /**
     * One swimmer, doing breaststroke.
     *
     * <p><b>The same figure as {@link #walker}, hung from its neck and tipped
     * over.</b> Every joint is at the proportion of the height it is when
     * standing — the neck at {@code 0.86}, the hips at {@code 0.47}, the feet
     * at nothing — so at {@link #swimPitch} of a right angle this draws exactly
     * the standing pose, and a player wading out of their depth tips into a
     * swim rather than cutting to a different model. That continuity is the
     * whole reason the body is built about the neck rather than about the feet:
     * the feet are what the game tracks, but they are the end that has to move
     * when somebody lies down in the water, and the head is the end that must
     * not, because it is where the camera is and where the air is.
     *
     * <p>What used to happen instead: a swimmer was drawn as a
     * <em>standing walker</em>, upright, legs striding at whatever speed they
     * were making. Crossing a lake was somebody marching along the bottom of
     * it with their head in the air, and diving was the same figure marching
     * downwards.
     *
     * @param z         the swimmer's feet, as the game tracks them
     * @param bodyPitch from {@link #swimPitch}
     * @param drive     from {@link #swimDrive} — a scull at nothing, a full
     *                  stroke at one
     * @param phase     the stroke clock, in turns
     * @param surfaced  whether their head is out of the water, and so whether
     *                  they lift it to breathe once a stroke
     */
    public static void swimmer(Mesh.Builder mesh, double x, double y, double z,
                               double yaw, double bodyPitch, double drive, double phase,
                               boolean surfaced, int tint) {
        swimmer(mesh, x, y, z, yaw, bodyPitch, drive, phase, surfaced, tint,
                WEARING_NOTHING);
    }

    /** The same swimmer, in whatever hat and spectacles they bought. See {@link #head}. */
    public static void swimmer(Mesh.Builder mesh, double x, double y, double z,
                               double yaw, double bodyPitch, double drive, double phase,
                               boolean surfaced, int tint, List<String> worn) {
        float[] uv = new float[4];
        WatchMaterials.uv(WatchMaterial.PLANK, uv);
        int coat = tint;
        int skin = WatchMaterials.shade(WatchMaterial.CLAY);
        int boot = WatchMaterials.shade(WatchMaterial.DARK_BARK);

        double stroke = RowStroke.wrap(phase);
        double effort = Math.min(1, Math.max(0, drive));
        double reach = SwimStroke.reach(stroke);
        double spread = SwimStroke.spread(stroke);
        // A scull still moves the legs, because a swimmer holding station is
        // holding it with their legs; it moves them a third as far.
        double kick = SwimStroke.kick(stroke) * (0.35 + 0.65 * effort);
        double lift = surfaced ? SwimStroke.breathe(stroke) * effort : 0;

        double cos = Math.cos(yaw), sin = Math.sin(yaw);
        double fx = sin, fy = -cos;
        double sx = cos, sy = sin;

        // The body's own three axes. `along` runs from the hips to the neck,
        // `belly` is the way the chest faces — forward when upright, down when
        // prone — and `across` is the shoulders. Every offset below is written
        // in these, which is what lets one set of numbers describe a swimmer at
        // any angle from vertical to head-down.
        double ca = Math.cos(bodyPitch), sa = Math.sin(bodyPitch);
        double alongF = ca, alongZ = sa;
        double bellyF = sa, bellyZ = -ca;

        double height = HEIGHT;
        double torso = height * (0.86 - 0.47);
        double legLength = height * 0.45;
        double thigh = legLength * 0.52, shin = legLength * 0.48;

        // A gather and a glide: breaststroke's propulsion arrives in one shove
        // from the legs, so the whole body eases forward through the kick and
        // coasts after it. Three centimetres, which is nothing to measure and
        // the difference between swimming and being towed.
        double glide = (SwimStroke.surge(stroke) - 0.5) * 0.06;

        // <b>The body turns about the hips</b>, which are left at the height a
        // standing figure's are. That is not an arbitrary pivot: the game
        // floats a swimmer with their feet {@code FLOAT_DEPTH} under the
        // surface, which is chest-deep for somebody upright, so a body laid
        // down about its hips puts the head at the waterline and everything
        // below the shoulders under it — without this model being told where
        // the water is. Turned about the neck instead, the same swimmer floats
        // with their whole chest in the air.
        double hipsF = alongF * glide;
        double hipsZ = z + height * 0.47 + alongZ * glide + lift * 0.07;

        double neckF = hipsF + alongF * torso;
        double neckZ = hipsZ + alongZ * torso;

        // Torso. A strut rather than a box, because it has to tip — and given
        // its roll outright, because a chest is wider than it is deep and a
        // strut left to choose its own reference flips it on the way through
        // vertical, which is exactly what a swimmer diving does.
        Shapes.strut(mesh, x + fx * hipsF, y + fy * hipsF, hipsZ,
                x + fx * neckF, y + fy * neckF, neckZ, 0.145, 0.22,
                fx * bellyF, fy * bellyF, bellyZ, uv, coat);

        // Legs, trailing behind and kicking. The angle is measured in the plane
        // of the body from "straight out behind" toward the belly, so the knees
        // draw up and forward the way a frog kick's do and the heels come up
        // behind on the way back.
        double knee = kick * 1.9;
        for (int i = 0; i < 2; i++) {
            double side = i == 0 ? -1 : 1;
            double out = side * (0.09 + kick * 0.26);
            double hx = x + fx * hipsF + sx * side * 0.09;
            double hy = y + fy * hipsF + sy * side * 0.09;
            double[] leg = new double[2];
            trail(alongF, alongZ, bellyF, bellyZ, kick * 0.85, thigh, leg);
            double kneeF = hipsF + leg[0], kneeZ = hipsZ + leg[1];
            double kx = x + fx * kneeF + sx * out;
            double ky = y + fy * kneeF + sy * out;
            trail(alongF, alongZ, bellyF, bellyZ, kick * 0.85 - knee, shin, leg);
            double footF = kneeF + leg[0], footZ = kneeZ + leg[1];
            double axf = x + fx * footF + sx * out * 0.8;
            double ayf = y + fy * footF + sy * out * 0.8;
            Shapes.strut(mesh, hx, hy, hipsZ, kx, ky, kneeZ,
                    0.082, 0.082, sx, sy, 0, uv, coat);
            Shapes.strut(mesh, kx, ky, kneeZ, axf, ayf, footZ,
                    0.072, 0.072, sx, sy, 0, uv, coat);
            // The boot goes on the end of the shin rather than lying flat: a
            // swimmer's foot points the way their leg does.
            Shapes.strut(mesh, axf, ayf, footZ,
                    axf + fx * leg[0] * 0.3, ayf + fy * leg[0] * 0.3, footZ + leg[1] * 0.3,
                    0.085, 0.05, fx * bellyF, fy * bellyF, bellyZ, uv, boot);
        }

        // Arms. The hands are placed in the body's own frame — forward along it
        // at the glide, out to the sides through the pull, in at the chest at
        // the catch — and the elbows are solved onto them, high and outboard,
        // which is where a breaststroker's go.
        double shoulderF = neckF - alongF * height * 0.06;
        double shoulderZ = neckZ - alongZ * height * 0.06;
        double armLength = height * 0.36;
        double upper = armLength * 0.52, fore = armLength * 0.48;
        double[] elbow = new double[3];
        for (int i = 0; i < 2; i++) {
            double side = i == 0 ? -1 : 1;
            // Sculling and stroking are the same three offsets at two sizes: a
            // scull keeps the hands low and in front of the chest — which on an
            // upright body is out at the waterline, where the hands of somebody
            // treading water are — and sweeps them a little, while a stroke
            // reaches past the head at the glide and pulls wide. Interpolated,
            // so setting off from a float is one movement.
            double ahead = mix(-0.22 + 0.12 * reach, 0.28 + 0.32 * reach, effort);
            double out = mix(0.18 + 0.18 * spread, 0.16 + 0.34 * spread, effort);
            double under = mix(0.30 + 0.08 * spread, 0.10 + 0.10 * spread, effort);

            double shX = x + fx * shoulderF + sx * side * 0.20;
            double shY = y + fy * shoulderF + sy * side * 0.20;
            double handF = shoulderF + alongF * ahead + bellyF * under;
            double handZ = shoulderZ + alongZ * ahead + bellyZ * under;
            double hx2 = x + fx * handF + sx * side * (0.20 + out);
            double hy2 = y + fy * handF + sy * side * (0.20 + out);

            fold(shX, shY, shoulderZ, hx2, hy2, handZ, upper, fore,
                    sx * side - fx * bellyF * 0.6, sy * side - fy * bellyF * 0.6,
                    -bellyZ * 0.6, elbow);
            Shapes.strut(mesh, shX, shY, shoulderZ, elbow[0], elbow[1], elbow[2],
                    0.062, 0.062, sx, sy, 0, uv, coat);
            Shapes.strut(mesh, elbow[0], elbow[1], elbow[2], hx2, hy2, handZ,
                    0.055, 0.055, sx, sy, 0, uv, coat);
            Shapes.box(mesh, hx2, hy2, handZ, 0.055, 0.055, 0.055, yaw, uv, skin);
        }

        // On the end of the spine, at the same proportion of the height it sits
        // at when standing — so a swimmer coming upright grows into the walker
        // rather than into something with its head on sideways. The hat is
        // stacked along the spine too; see the second `head`.
        double crown = height * (0.94 - 0.86);
        double headF = neckF + alongF * crown;
        head(mesh, x + fx * headF, y + fy * headF, neckZ + alongZ * crown,
                fx * alongF, fy * alongF, alongZ, height, yaw, uv, skin, worn, coat);
    }

    /** Between two numbers, by a fraction already known to be in range. */
    private static double mix(double from, double to, double howFar) {
        return from + (to - from) * howFar;
    }

    /**
     * A point {@code length} along the body from a joint, at {@code angle}
     * radians round from "straight out behind" toward the belly.
     *
     * <p>{@code out} comes back as an offset {@code {forward, up}} in the same
     * two axes the caller's body is written in.
     */
    private static void trail(double alongF, double alongZ, double bellyF, double bellyZ,
                              double angle, double length, double[] out) {
        double c = Math.cos(angle) * length, s = Math.sin(angle) * length;
        out[0] = -alongF * c + bellyF * s;
        out[1] = -alongZ * c + bellyZ * s;
    }

    /**
     * Where a swimmer's head is, as {@code {forward, up}} from their feet — so
     * a raised glass, or anything else that belongs at somebody's eye, can find
     * it without rebuilding the pose.
     *
     * <p>The stroke's own few centimetres of glide and breath are left out: a
     * spyglass that bobbed with them would be describing the stroke rather than
     * where its owner is looking.
     */
    public static void swimEye(double bodyPitch, double[] out) {
        double spine = HEIGHT * (0.94 - 0.47);
        out[0] = Math.cos(bodyPitch) * spine;
        out[1] = HEIGHT * 0.47 + Math.sin(bodyPitch) * spine;
    }

    /**
     * Where the elbow (or knee) goes, given both ends of a two-bone limb.
     *
     * <p>The classic two-bone solve: the joint sits on the circle where the two
     * bones' spheres meet, and {@code bend} picks which point on that circle —
     * the direction the limb folds toward, with whatever part of it lies along
     * the shoulder-to-hand line discarded. An arm asked to reach further than
     * it is long straightens rather than tearing: the term under the root goes
     * negative, is clamped to zero, and the joint lands on the line between the
     * two ends.
     */
    static void fold(double sx, double sy, double sz, double hx, double hy, double hz,
                     double upper, double fore,
                     double bendX, double bendY, double bendZ, double[] out) {
        double dx = hx - sx, dy = hy - sy, dz = hz - sz;
        double span = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (span < 1e-6) {
            out[0] = sx;
            out[1] = sy;
            out[2] = sz - upper;
            return;
        }
        dx /= span;
        dy /= span;
        dz /= span;
        double along = (span * span + upper * upper - fore * fore) / (2 * span);
        along = Math.max(-upper, Math.min(upper, along));
        double out2 = Math.sqrt(Math.max(0, upper * upper - along * along));

        // The bend direction, with its component along the limb removed.
        double dot = bendX * dx + bendY * dy + bendZ * dz;
        double bx = bendX - dx * dot, by = bendY - dy * dot, bz = bendZ - dz * dot;
        double length = Math.sqrt(bx * bx + by * by + bz * bz);
        if (length < 1e-6) {
            // The bend was parallel to the limb and says nothing; anything
            // perpendicular will do, and down is the least surprising.
            bx = -dz * dx;
            by = -dz * dy;
            bz = 1 - dz * dz;
            length = Math.sqrt(bx * bx + by * by + bz * bz);
            if (length < 1e-6) {
                bx = 1;
                by = 0;
                bz = 0;
                length = 1;
            }
        }
        out[0] = sx + dx * along + bx / length * out2;
        out[1] = sy + dy * along + by / length * out2;
        out[2] = sz + dz * along + bz / length * out2;
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

        double[] up = new double[3];
        cameraUp(dirX, dirY, dirZ, rightX, rightY, up);
        double upX = up[0], upY = up[1], upZ = up[2];

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
            // Its centre is half its own length behind the fist, so the elbow
            // lands at exactly HAND_FORWARD − FOREARM. See HAND_FORWARD.
            double half = FOREARM / 2;
            Shapes.box(mesh, cx - dirX * half, cy - dirY * half, cz - dirZ * half,
                    0.055, half, 0.055, yaw, uv, sleeve);
            Shapes.box(mesh, cx, cy, cz, 0.062, 0.062, 0.062, yaw, uv, skin);
        }
    }

    /**
     * Your own two arms working a pair of oars, seen from inside your own head.
     *
     * <p>In the camera's frame, like {@link #hands} and for the same
     * unavoidable reason: {@link com.larsons.engine.graphics.EyeCamera#NEAR} is
     * eight tenths of a metre, and the oar handles a seated rower is actually
     * holding are about that far from a standing player's eye — so arms built
     * on the real handles have their elbows inside the near plane and are
     * sliced in half, or thrown away entirely, on the painter path. What sells
     * a stroke from inside it is the <b>rhythm</b> rather than the millimetres:
     * both hands together, out on the drive and back on the recovery, on the
     * same {@link RowStroke} clock as the oars swinging in the water in front
     * of you.
     *
     * <p>Both hands move as one, which is the difference between rowing and
     * walking: {@link #hands} swings them against each other and reaches with
     * one, and doing that in a boat is what made rowing look like sprinting.
     */
    public static void rowingHands(Mesh.Builder mesh, double eyeX, double eyeY, double eyeZ,
                                   double dirX, double dirY, double dirZ,
                                   double rightX, double rightY, double stroke, int sleeve) {
        float[] uv = new float[4];
        WatchMaterials.uv(WatchMaterial.PLANK, uv);
        int skin = WatchMaterials.shade(WatchMaterial.CLAY);

        double[] up = new double[3];
        cameraUp(dirX, dirY, dirZ, rightX, rightY, up);
        double upX = up[0], upY = up[1], upZ = up[2];
        double yaw = Math.atan2(dirX, -dirY);

        double reach = RowStroke.reach(stroke);
        double lift = RowStroke.lift(stroke);
        for (int side = -1; side <= 1; side += 2) {
            // Away on the drive and back on the recovery; and down a little as
            // the blade comes up, because the handle is the other end of the
            // same lever.
            //
            // The travel is added <b>forward of</b> {@link #HAND_FORWARD}
            // rather than spread either side of it: that distance is the near
            // plane plus an arm plus thirteen centimetres, and thirteen
            // centimetres is not enough to take a stroke's worth of drawing
            // back out of. Centred, the catch put both elbows through the near
            // plane and the painter drew the arms with their back halves cut
            // off, once a stroke.
            double forward = HAND_FORWARD + (reach + 1) * ROW_PUSH;
            double out = (HAND_SIDE + 0.05) * side;
            double down = HAND_DROP + lift * 0.07;

            double cx = eyeX + dirX * forward + rightX * out + upX * -down;
            double cy = eyeY + dirY * forward + rightY * out + upY * -down;
            double cz = eyeZ + dirZ * forward + upZ * -down;

            double half = FOREARM / 2;
            Shapes.box(mesh, cx - dirX * half, cy - dirY * half, cz - dirZ * half,
                    0.055, half, 0.055, yaw, uv, sleeve);
            Shapes.box(mesh, cx, cy, cz, 0.062, 0.062, 0.062, yaw, uv, skin);
        }
    }

    /**
     * Your own two arms swimming, seen from inside your own head.
     *
     * <p>The same stroke as {@link #swimmer}, in the camera's frame for the
     * same reason {@link #rowingHands} is: {@code EyeCamera.NEAR} is eight
     * tenths of a metre, and a swimmer's hands at the catch are nowhere near
     * that far from their face.
     *
     * <p>What it replaces is the walking view model at a slower clock — which
     * is to say a person striding along in front of the camera while the body
     * behind it swam. The travel is added forward of {@link #HAND_FORWARD}
     * rather than spread either side of it, so the catch cannot put an elbow
     * through the near plane; and both hands sweep out and back together,
     * because that is what makes it read as a stroke rather than as a walk.
     */
    public static void swimmingHands(Mesh.Builder mesh, double eyeX, double eyeY,
                                     double eyeZ, double dirX, double dirY, double dirZ,
                                     double rightX, double rightY, double stroke,
                                     int sleeve) {
        float[] uv = new float[4];
        WatchMaterials.uv(WatchMaterial.PLANK, uv);
        int skin = WatchMaterials.shade(WatchMaterial.CLAY);

        double[] up = new double[3];
        cameraUp(dirX, dirY, dirZ, rightX, rightY, up);
        double upX = up[0], upY = up[1], upZ = up[2];
        double yaw = Math.atan2(dirX, -dirY);

        double reach = SwimStroke.reach(stroke);
        double spread = SwimStroke.spread(stroke);
        for (int side = -1; side <= 1; side += 2) {
            double forward = HAND_FORWARD + (reach + 1) * SWIM_PUSH;
            double out = (HAND_SIDE + spread * 0.55) * side;
            // Deeper as they sweep wide, which is where the water is pushed
            // from, and back up under the chest as they come forward.
            double down = HAND_DROP + spread * 0.16;

            double cx = eyeX + dirX * forward + rightX * out + upX * -down;
            double cy = eyeY + dirY * forward + rightY * out + upY * -down;
            double cz = eyeZ + dirZ * forward + upZ * -down;

            double half = FOREARM / 2;
            Shapes.box(mesh, cx - dirX * half, cy - dirY * half, cz - dirZ * half,
                    0.055, half, 0.055, yaw, uv, sleeve);
            Shapes.box(mesh, cx, cy, cz, 0.062, 0.062, 0.062, yaw, uv, skin);
        }
    }

    /** How far the hands travel along the boat over one stroke, either way. */
    private static final double ROW_PUSH = 0.22;

    /** …and along the body over one swimming stroke. */
    private static final double SWIM_PUSH = 0.26;

    /**
     * How high a seated rower's eye is above the thwart, in metres — where the
     * glass goes when somebody in a boat raises one.
     */
    public static final double ROWER_EYE = 0.82;

    /**
     * The camera's up axis, from its forward and right axes.
     *
     * <p><b>{@code direction × right}, and the order is the whole of it.</b>
     * The other order looks equally plausible and points straight down: with
     * this engine's basis ({@code right = (cosYaw, sinYaw, 0)},
     * {@code dir = (sinYaw·cosPitch, −cosYaw·cosPitch, sinPitch)}),
     * {@code right × dir} has a z of {@code −cosPitch} — negative for every
     * pitch a player can hold. Getting it backwards put the first-person hands
     * forty centimetres <em>above</em> the eye line, which reads as somebody
     * else's arms coming over your head rather than as your own.
     *
     * <p>Public and shared because the held item is placed in the same frame
     * and has to agree with the hand it is in.
     */
    public static void cameraUp(double dirX, double dirY, double dirZ,
                                double rightX, double rightY, double[] out) {
        // right has no z component, which cancels three of the six terms.
        double x = -dirZ * rightY;
        double y = dirZ * rightX;
        double z = dirX * rightY - dirY * rightX;
        double length = Math.sqrt(x * x + y * y + z * z);
        if (length < 1e-9) {
            out[0] = 0;
            out[1] = 0;
            out[2] = 1;
            return;
        }
        out[0] = x / length;
        out[1] = y / length;
        out[2] = z / length;
    }

    /** How far back from the fist the forearm reaches, in metres. */
    private static final double FOREARM = 0.32;

    /**
     * How far in front of the eye the fists sit, in metres.
     *
     * <p><b>Derived from the near plane, not chosen.</b> The camera clips at
     * {@link com.larsons.engine.graphics.EyeCamera#NEAR} and the painter path
     * throws away anything nearer, so a view model has to clear it — and the
     * part that has to clear it is the <em>elbow</em>, which is
     * {@link #FOREARM} behind the fist, not the fist itself. Picking the fist's
     * distance by eye and forgetting the forearm put the near end of both arms
     * seven centimetres inside the plane, so the arms were drawn with their
     * back halves sliced off. Writing it as the near plane plus the arm plus a
     * margin means it cannot drift out of agreement again if either changes.
     */
    public static final double HAND_FORWARD =
            com.larsons.engine.graphics.EyeCamera.NEAR + FOREARM + 0.13;

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
