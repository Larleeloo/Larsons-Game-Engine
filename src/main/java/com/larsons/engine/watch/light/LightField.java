package com.larsons.engine.watch.light;

import com.larsons.engine.graphics.MeshPass;
import com.larsons.engine.watch.Lure;
import com.larsons.engine.watch.WatchView;
import com.larsons.engine.watch.life.AnimalSkins;
import com.larsons.engine.watch.life.Hurl;
import com.larsons.engine.watch.life.Mutants;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Everything that is glowing this frame, and what it does to a surface.
 *
 * <h2>Why the lights are gathered rather than sent</h2>
 *
 * <p>Nothing here travels. A fire's <em>existence</em> is world state the host
 * owns and replicates, and so is whether it is lit — but a light is not a fire,
 * it is a consequence of one, and every consequence in this game is worked out
 * on the machine that draws it. That is the same bargain
 * {@link com.larsons.engine.watch.render.Sparks} strikes for embers and
 * {@code WatchSounds} strikes for noises, and it buys the same two things: a
 * flicker that cannot arrive late, and nothing on the wire when eight people
 * stand round a campfire.
 *
 * <h2>Five things burn</h2>
 *
 * <ol>
 *   <li><b>Placed lights</b> — the fires and lanterns of {@link Lights}.</li>
 *   <li><b>Carried lights</b> — everybody's, not only your own. A party spread
 *       over a valley can see each other's lanterns move, which is the whole
 *       reason to carry one lit rather than to light it when you arrive.</li>
 *   <li><b>Mutants</b> — see {@link #MUTANT_REACH}. The three things that hunt
 *       you have burning eyes and a lit ribcage, and until now that was a
 *       texture: the creature glowed and the ground under it did not.</li>
 *   <li><b>Thrown bone shards</b> — a wendigo's, which are already on fire and
 *       already trailing embers.</li>
 *   <li><b>Moth lamps</b> — the one feeder bait that is itself a lamp. Its own
 *       description says so; it may as well be true.</li>
 * </ol>
 *
 * <h2>The bound is the design</h2>
 *
 * <p>A fragment shader that walks a list walks all of it, so the list is capped
 * at {@link MeshPass#MAX_LIGHTS} and the cap is applied <em>here</em>, ranked by
 * how much each light can possibly matter from where the camera is standing.
 * A party that lights forty lanterns in one clearing gets the sixteen that
 * reach them and no frame-rate cliff — which is the behaviour to have, because
 * the alternative is a game that gets slower the more of it you build.
 */
public final class LightField {

    /**
     * Wrap term: how much of a light a surface gets when it faces away.
     *
     * <p>{@link MeshPass#LIGHT_WRAP}, because the card's shader has to use the
     * same number and the seam is the one place both sides can read it from.
     */
    public static final double WRAP = MeshPass.LIGHT_WRAP;

    /**
     * How far past a light's own radius it is still kept in the frame's list.
     *
     * <p>Zero would be correct and looks wrong. A light beyond this is
     * genuinely contributing nothing to anything on screen — the falloff is
     * compact — but a light <em>just</em> outside somebody's view still lights
     * ground they are about to walk into, and dropping it at the boundary makes
     * the pool of light under a lantern appear as the camera turns. Kept
     * generously and ranked by influence, so the cap takes the ones that do not
     * matter rather than an arbitrary radius doing it.
     */
    private static final double KEEP_BEYOND = 220;

    /** How high above their feet somebody's carried light hangs, in metres. */
    private static final double HAND_HEIGHT = 1.12;

    /** …and crouched, because a light held at a crouch is held at a crouch. */
    private static final double CROUCH_HAND_HEIGHT = 0.72;

    /** How far in front of somebody the light they carry actually hangs. */
    private static final double HAND_FORWARD = 0.34;

    /**
     * How far a mutant's own glow reaches, in metres.
     *
     * <p><b>Deliberately short for how bright it is.</b> A wendigo is five and a
     * half metres of bone with a burning chest, and if that lit the wood the way
     * a campfire does then meeting one would be well-lit — which is precisely
     * the wrong feeling. Six metres means it lights the ground it is standing on
     * and the trunks beside it and nothing else: the thing you can see is the
     * thing that is already too close.
     */
    public static final double MUTANT_REACH = 6.2;

    /** How bright it burns, against a lantern's {@code 0.95}. */
    private static final double MUTANT_INTENSITY = 0.85;

    /** How high up its body the glow sits, as a share of its height. */
    private static final double MUTANT_CHEST = 0.62;

    /** A thrown shard's fire, which is small, bright and moving. */
    private static final double SHARD_REACH = 4.5;

    private static final double SHARD_INTENSITY = 0.7;

    /** A moth lamp on a feeder: pale, small, and not really lighting anything. */
    private static final double LAMP_REACH = 5.0;

    private static final double LAMP_INTENSITY = 0.45;

    private static final int LAMP_RGB = 0xE8E4C0;

    /** The forage key of the one bait that is a lamp. */
    private static final String MOTH_LAMP = "moth_lamp";

    private final List<MeshPass.Light> lights = new ArrayList<>();
    private final List<Scored> scratch = new ArrayList<>();

    /** Where a walker's hand is, reused so a frame's gather allocates less. */
    private final double[] hand = new double[3];

    /** A light and how much it can matter from where the camera is. */
    private record Scored(MeshPass.Light light, double score) { }

    /** What is burning this frame, nearest and brightest first. */
    public List<MeshPass.Light> lights() { return lights; }

    public int count() { return lights.size(); }

    public boolean isEmpty() { return lights.isEmpty(); }

    /** Forget everything — what leaving a walk does. */
    public void clear() {
        lights.clear();
        scratch.clear();
    }

    /**
     * Work out every light in the world this frame.
     *
     * @param view    what the screen has been given
     * @param eyeX    where the camera is, for ranking and culling
     * @param seconds the drawing clock, which is what the flicker runs on — a
     *                real number of seconds that does not reset, so a fire
     *                flickers at the same rate however fast the frames come
     */
    public void gather(WatchView view, double eyeX, double eyeY, double eyeZ,
                       double seconds) {
        lights.clear();
        scratch.clear();
        if (view == null) return;

        for (PlacedLight placed : view.lights().all()) {
            if (!placed.lit()) continue;
            LightKind kind = placed.kind();
            double wobble = flicker(placed.id(), seconds, kind.flicker());
            offer(placed.x(), placed.y(), placed.flameZ(), kind.rgb(),
                    kind.radius() * (0.94 + 0.06 * wobble),
                    kind.intensity() * placed.burnBrightness() * wobble,
                    eyeX, eyeY, eyeZ);
        }

        for (WatchView.Walker walker : view.walkers()) {
            LightKind carried = LightKind.ofItem(walker.light());
            if (carried == null) continue;
            handHold(walker, hand);
            double wobble = flicker(walker.id() * 977L, seconds, carried.flicker());
            offer(hand[0], hand[1], hand[2], carried.rgb(), carried.radius(),
                    carried.intensity() * wobble, eyeX, eyeY, eyeZ);
        }

        for (WatchView.Creature creature : view.creatures()) {
            Mutants.Kind mutant = Mutants.of(creature.def());
            if (mutant == null) continue;
            // Its own glow colour, off the same skin region the model paints
            // its ribcage and its eyes with — so the light on the ground is the
            // colour of the thing casting it rather than a number chosen twice.
            int rgb = mutant.glow() != 0 ? mutant.glow()
                    : AnimalSkins.regionColour(creature.def(), AnimalSkins.Region.GLOW);
            // A slow, deep pulse rather than a flame's chatter: this is a
            // heartbeat, and it is the difference between a creature that is
            // lit and a creature that is alive.
            double pulse = 0.78 + 0.22 * Math.sin(seconds * 2.1 + creature.id() * 0.37);
            offer(creature.x(), creature.y(),
                    creature.z() + mutant.height() * MUTANT_CHEST, rgb,
                    MUTANT_REACH, MUTANT_INTENSITY * pulse, eyeX, eyeY, eyeZ);
        }

        for (Hurl hurl : view.hurls()) {
            Mutants.Kind thrower = Mutants.of(hurl.species());
            int rgb = thrower != null && thrower.glow() != 0 ? thrower.glow() : 0xFF7A30;
            offer(hurl.x(), hurl.y(), hurl.z(), rgb, SHARD_REACH,
                    SHARD_INTENSITY * flicker(hurl.id(), seconds, 0.35),
                    eyeX, eyeY, eyeZ);
        }

        for (Lure lure : view.lures()) {
            if (!MOTH_LAMP.equals(lure.food()) || !lure.active()) continue;
            offer(lure.x(), lure.y(), lure.z() + 1.25, LAMP_RGB, LAMP_REACH,
                    LAMP_INTENSITY * flicker(lure.id(), seconds, 0.12),
                    eyeX, eyeY, eyeZ);
        }

        if (scratch.size() > MeshPass.MAX_LIGHTS) {
            scratch.sort(Comparator.comparingDouble(s -> -s.score()));
            scratch.subList(MeshPass.MAX_LIGHTS, scratch.size()).clear();
        }
        for (Scored scored : scratch) lights.add(scored.light());
    }

    /**
     * Where somebody's carried light hangs, into {@code out} as {@code x, y, z}.
     *
     * <p><b>Here rather than in the renderer, because two things need it and
     * they must agree.</b> The scene draws a lantern model at a walker's hand
     * and this class puts a point light there; if the two used their own
     * numbers, the flame and the light it casts would be a hand's breadth
     * apart, which is visible the moment somebody crouches.
     */
    public static void handHold(WatchView.Walker walker, double[] out) {
        double height = walker.crouching() ? CROUCH_HAND_HEIGHT : HAND_HEIGHT;
        out[0] = walker.x() + Math.sin(walker.yaw()) * HAND_FORWARD;
        out[1] = walker.y() - Math.cos(walker.yaw()) * HAND_FORWARD;
        out[2] = walker.z() + height;
    }

    /** Add one light by hand — for a test, and for anything one-off. */
    public void add(MeshPass.Light light) {
        if (light != null && lights.size() < MeshPass.MAX_LIGHTS) lights.add(light);
    }

    /** Consider a light, dropping it if it cannot reach the camera's country. */
    private void offer(double x, double y, double z, int rgb, double radius,
                       double intensity, double eyeX, double eyeY, double eyeZ) {
        if (intensity <= 0.01 || radius <= 0.01) return;
        double dx = x - eyeX, dy = y - eyeY, dz = z - eyeZ;
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (distance > radius + KEEP_BEYOND) return;
        scratch.add(new Scored(MeshPass.Light.of(x, y, z, rgb, radius, intensity),
                score(distance, radius, intensity)));
    }

    /**
     * How much a light can matter, from a camera this far from it.
     *
     * <p>Not its brightness at the eye — a fire you are standing beside and a
     * fire twenty metres off both light a great deal of what is on screen, and
     * ranking by "how lit is the camera" would drop the second for a torch
     * somebody is holding behind you. What this measures is how much lit ground
     * the light can put in front of somebody standing there: full marks while
     * the eye is inside the light's own sphere, falling away outside it.
     */
    private static double score(double distance, double radius, double intensity) {
        double outside = Math.max(0, distance - radius);
        return intensity * radius / (1 + outside / Math.max(1, radius));
    }

    /**
     * A flame's wobble at a moment, as a multiplier around {@code 1}.
     *
     * <p>Three sines at incommensurable rates, offset by the light's own id, so
     * that two fires side by side never flicker together — which is what makes
     * a row of them read as several fires rather than as one dimmer switch.
     * A kind with no flicker at all gets a slow breath instead of nothing: a
     * jar of spores is not a fluorescent tube, and a light that is perfectly
     * constant reads as painted on.
     */
    public static double flicker(long id, double seconds, double amount) {
        double offset = (id % 97) * 0.618;
        if (amount <= 0) return 1 + 0.05 * Math.sin(seconds * 1.3 + offset);
        double a = Math.sin(seconds * 11.3 + offset);
        double b = Math.sin(seconds * 6.1 + offset * 1.9);
        double c = Math.sin(seconds * 23.7 + offset * 3.3);
        return 1 + amount * 0.5 * (a * 0.5 + b * 0.3 + c * 0.2);
    }

    /**
     * What one light adds to a surface, added into {@code out} as
     * {@code r, g, b} multipliers.
     *
     * <p><b>This is the CPU half of the shader</b>, and it is written out here
     * rather than inlined into the painter so that the two paths can be
     * compared by a test rather than by eye. The card computes exactly this per
     * fragment, off a normal it derives from the depth gradient; the painter
     * computes it per triangle, off the normal it already has from the cross
     * product. Same falloff, same wrap, same arithmetic.
     *
     * <p>The falloff is {@code (1 − d/r)²}: <b>compact</b>, so a light is either
     * within a surface's reckoning or costs it nothing, and quadratic, so the
     * pool under a lantern has an edge that reads as light rather than as a
     * circle drawn on the ground. Inverse-square would be more nearly physical
     * and has no outer edge at all, which means every light in the world
     * contributes to every fragment — the thing a bounded list exists to
     * prevent.
     *
     * <p>The wrap term is why a fire lights the underside of a branch. Pure
     * Lambert on flat-shaded low-poly geometry is very dark on anything not
     * squarely facing the flame, and a wood at night is mostly surfaces that
     * are not.
     *
     * @param nx the surface's unit normal; pass {@code 0,0,0} for something
     *           with no orientation worth speaking of, which gets the wrap
     *           term alone
     */
    public static void contribute(MeshPass.Light light, double x, double y, double z,
                                  double nx, double ny, double nz, double[] out) {
        double dx = light.x() - x, dy = light.y() - y, dz = light.z() - z;
        double d2 = dx * dx + dy * dy + dz * dz;
        double radius = light.radius();
        if (d2 >= radius * radius) return;
        double distance = Math.sqrt(d2);
        double fall = 1 - distance / radius;
        fall *= fall;
        double lambert = 1;
        if (nx != 0 || ny != 0 || nz != 0) {
            double inverse = distance > 1e-6 ? 1 / distance : 0;
            double ndotl = (dx * nx + dy * ny + dz * nz) * inverse;
            lambert = WRAP + (1 - WRAP) * Math.max(0, ndotl);
        }
        double strength = fall * light.intensity() * lambert;
        out[0] += light.r() * strength;
        out[1] += light.g() * strength;
        out[2] += light.b() * strength;
    }
}
