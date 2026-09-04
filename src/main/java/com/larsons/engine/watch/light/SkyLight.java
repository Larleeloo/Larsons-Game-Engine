package com.larsons.engine.watch.light;

import com.larsons.engine.graphics.MeshPass;
import com.larsons.engine.watch.WatchClock;

/**
 * <b>What the sky is doing to everything you can see.</b>
 *
 * <p>{@link LightField} gathers the things that are burning. This works out the
 * other half — the sun, the two-colour ambient it leaves behind, and the air
 * between the eye and the hillside — and hands it over as one
 * {@link MeshPass.Sky}.
 *
 * <h2>Why it is a separate class from the clock</h2>
 *
 * <p>{@link WatchClock} answers "what time is it" and, because the painter
 * needed exactly one number, also "how bright is that": {@link
 * WatchClock#lightTint} is a single per-channel multiplier applied to every
 * colour in the world regardless of which way the surface faces. That is a
 * complete lighting model for a renderer with no normals and no budget, and it
 * is <em>all</em> the world had.
 *
 * <p>A card has both. So the same hour is re-expressed here as a description of
 * an environment rather than a multiplier: the sun is in this direction and
 * this colour, the sky above is that colour and the ground bounces back this
 * much of it, the air is this thick and pools at this height. What a backend
 * does with that is its own business — the painter ignores it entirely and
 * keeps the flat multiplier, which is why nothing here can make the JDK-only
 * build slower or different.
 *
 * <h2>The numbers are chosen against a mesh that is already shaded</h2>
 *
 * <p>Every triangle in this world arrives with a shade baked into its vertex
 * colour by {@code TerrainMesher} — a fixed key light from the north-west, with
 * a generous ambient floor, which is what makes a ridge read as a ridge at a
 * distance no dynamic light could afford to reach. That bake cannot move (it
 * would mean re-meshing the world at sunrise) and it is not trying to: it is
 * <em>form</em>, the shape of the ground, and it stays.
 *
 * <p>What is added here is the <em>hour</em>: a real sun in the real place the
 * clock puts it, warm at the horizon and pale overhead, plus a sky that lights
 * what faces up and a ground bounce for what faces down. The two do overlap —
 * a north face is darkened twice at noon — and the amplitudes below are picked
 * so that the sum is brighter and more coloured than the old flat multiplier
 * rather than darker. Vibrancy was the point; a physically careful model that
 * left the wood dimmer than it found it would have missed it.
 */
public final class SkyLight {

    /**
     * The most of a surface's light that comes straight from the sun.
     *
     * <p>The rest is ambient. Push this up and the world goes contrasty and
     * hard, like a render with one lamp in it; leave it too low and a shadow
     * under a tree is a patch of ground four percent darker, which is a shadow
     * a screenshot can find and a player cannot. Over half, so that a hillside
     * turning away from the sun visibly turns away and the shadow of a canopy
     * reads as a shadow.
     */
    private static final double DIRECT = 0.55;

    /**
     * How much of the ambient is given up to pay for the sun.
     *
     * <p><b>Not all of it, deliberately.</b> Conserving light exactly — taking
     * out of the ambient everything the sun puts in — would mean this whole
     * class could only redistribute the brightness the flat multiplier already
     * had, and the world would come out looking the same overall. Giving up
     * slightly less than the sun adds is what makes a sunlit slope brighter
     * than it used to be while a shaded one is only a little darker.
     */
    private static final double AMBIENT_GIVE = 0.52;

    /** What a surface facing straight up gets from the sky, before its hue. */
    private static final double SKY_TOP = 1.12;

    /**
     * …and what one facing straight down gets back off the ground.
     *
     * <p>Not lower, and the reason is the mesher: {@code TerrainMesher} has
     * already darkened a downward face to its own ambient floor, so this is the
     * <em>second</em> thing dimming it. Four fifths here is a third off in
     * total, which is shape; the same number at half of that would be a wood
     * of silhouettes.
     */
    private static final double GROUND_BOUNCE = 0.80;

    /** How much of the sky's own colour reaches the ambient, {@code 0}–{@code 1}. */
    private static final double SKY_HUE = 0.34;

    /** …and of the haze's, for the bounce. Less: dirt is not a mirror. */
    private static final double GROUND_HUE = 0.22;

    /**
     * How much of the sun a shadowed surface loses at most.
     *
     * <p>Not all of it. A shadow with none of the sun in it is a hole, and the
     * sky is still overhead — what stands under a tree at noon is lit by a
     * bright blue hemisphere and nothing else, which is exactly the ambient
     * term this leaves alone.
     */
    private static final double SHADOW = 0.78;

    /** Weather haze at its thickest, as extinction per metre. */
    private static final double HAZE_MAX = 0.011;

    /** How deep the ground mist lies, in metres, at its thickest. */
    private static final double MIST_DEPTH = 9.0;

    /** …and how far above the ground it starts to thin. */
    private static final double MIST_LIFT = 0.6;

    /**
     * Mist that is there at dawn whatever the forecast says.
     *
     * <p>Not a cheat — it is the hour the game is best in, the hour the weather
     * table already weights fog toward, and a valley with nothing lying in it
     * at five in the morning is the one thing that would make all this look
     * like a filter.
     */
    private static final double DAWN_MIST = 0.30;

    /** The sun's colour overhead: pale, barely warm. */
    private static final int SUN_HIGH = 0xFFF6DC;

    /** …and on the horizon, which is where all the good light is. */
    private static final int SUN_LOW = 0xFF8A3C;

    /** The moon's, which is the same light twice reflected and much colder. */
    private static final int MOON_RGB = 0xBFD2F0;

    /**
     * How much of the night's light the moon accounts for.
     *
     * <p>A share of a small total rather than a small share of a large one —
     * see the note where it is used. Half, because the point of having a
     * directional term at night at all is that you can read the shape of the
     * ground by it.
     */
    private static final double MOON_SHARE = 0.55;

    /** How high the sun has to climb before it is fully itself, as a sine. */
    private static final double SUN_RISE = 0.22;

    /** Under water: no sun, thick water, and everything close. */
    private static final double UNDERWATER_HAZE = 0.040;

    /**
     * How far above the lake bed the "mist" reaches when you are in it.
     *
     * <p>Water is not mist and does not lie in a band: it fills the whole
     * column, from the bed to the surface, at one density. Putting the floor
     * far above anything the player can reach is how the height term is turned
     * off without a second branch in the shader — every fragment is then below
     * it, and below the floor the density is flat.
     */
    private static final double UNDERWATER_COLUMN = 512;

    private SkyLight() {}

    /**
     * The sky, for one frame.
     *
     * @param clock     what time it is
     * @param skyRgb    the sky's colour this frame — the biome's own, already
     *                  put through the hour and the weather by the caller
     * @param fogRgb    the horizon's, likewise; the ground bounce is taken from
     *                  it because the haze at the bottom of the view <em>is</em>
     *                  roughly the colour of the ground going away
     * @param weather   how thick the weather is, {@code 0} clear to {@code 1}
     * @param overcast  how much cloud is between the sun and the world; a storm
     *                  is a flat lid and has no direction to its light at all
     * @param submerged whether the camera is under water, which is its own
     *                  weather and beats every other
     * @param floorZ    the ground's height under the camera, in world units —
     *                  where mist pools
     * @param seconds   the drawing clock, for anything that drifts
     */
    public static MeshPass.Sky of(WatchClock clock, int skyRgb, int fogRgb,
                                  double weather, double overcast, boolean submerged,
                                  double floorZ, double seconds) {
        double[] direction = new double[3];
        clock.sunDirection(direction);
        // The moon takes the sun's place at night, opposite it in the sky and
        // cold — a night with a directional light in it is a night you can read
        // the shape of the ground by, which is the difference between "dark"
        // and "unlit".
        boolean night = clock.night();
        if (night) {
            direction[0] = -direction[0];
            direction[1] = -direction[1];
            direction[2] = -direction[2];
        }

        double day = clock.daylight();
        double[] tint = new double[3];
        clock.lightTint(tint);

        double clear = 1 - clamp01(overcast);
        double weatherAmount = clamp01(weather);
        // How much of a sun there is: it climbs out of the horizon over the
        // first dozen degrees, so dawn is a long warm ramp rather than a light
        // switch, and cloud takes most of what is left.
        double above = Math.max(0, direction[2]);
        double up = smoothstep(0, SUN_RISE, above);
        double strength = up * (0.28 + 0.72 * clear);

        // The colour of it: orange on the horizon, near-white overhead, and the
        // moon's own pale blue rather than either.
        double low = 1 - smoothstep(0.02, 0.45, above);
        int hue = night ? MOON_RGB : blend(SUN_HIGH, SUN_LOW, low);

        // <b>How much of the hour's light arrives from one direction</b> — a
        // share of it, not an amount, and that distinction is the whole of why
        // the night is not black. Moonlight is a twentieth of daylight in life
        // and would be invisible here, so the moon is given a real share of a
        // much smaller total; taking `AMBIENT_GIVE` out of the ambient against
        // the sun's share while handing back the moon's would darken every
        // night by a fifth to pay for a light nobody can see.
        double share = strength * DIRECT * (night ? MOON_SHARE : 1);
        double directLight = submerged ? 0 : day * share;

        float[] sun = {
                (float) (((hue >> 16) & 0xFF) / 255.0 * directLight),
                (float) (((hue >> 8) & 0xFF) / 255.0 * directLight),
                (float) ((hue & 0xFF) / 255.0 * directLight),
        };

        // What is left over, spread across the hemisphere. The two ends are the
        // sky's own colour above and the haze's below, each pulled most of the
        // way back to neutral: this is a tilt in the light, not a coat of paint.
        float[] above3 = hueOf(skyRgb, SKY_HUE, SKY_TOP);
        float[] below3 = hueOf(fogRgb, GROUND_HUE, GROUND_BOUNCE);

        double give = 1 - AMBIENT_GIVE * (submerged ? 0 : share);

        double mist = Math.max(weatherAmount,
                clock.phase() == WatchClock.Phase.DAWN ? DAWN_MIST : 0);
        // Off the mist rather than off the forecast, so that the dawn bonus is
        // a bank of mist and not merely a deeper band of nothing.
        double haze = submerged ? UNDERWATER_HAZE : HAZE_MAX * mist;
        // Air carries a lamp best when there is something in it to carry with,
        // and matters most when the lamp is the only light there is.
        double scatter = submerged ? 0.20
                : (0.11 + 0.42 * mist) * (1.20 - 0.80 * day);
        // A storm should look like a storm: drab is the point of it, so the
        // grade backs off exactly as far as the weather comes on.
        double vibrance = 0.46 * (1 - 0.45 * weatherAmount);

        return new MeshPass.Sky(
                direction[0], direction[1], direction[2],
                sun[0], sun[1], sun[2],
                (float) (above3[0] * give), (float) (above3[1] * give),
                (float) (above3[2] * give),
                (float) (below3[0] * give), (float) (below3[1] * give),
                (float) (below3[2] * give),
                // No moon shadows, and not because they would look wrong: at a
                // twentieth of daylight the term they would remove is a
                // hundredth of the frame, and it would cost a whole extra pass
                // over every mesh in view for half of every day. The moon still
                // gets its directional term, which is the half of this you can
                // actually see at night.
                (float) (submerged || night ? 0 : SHADOW * strength),
                (float) haze,
                submerged ? floorZ + UNDERWATER_COLUMN : floorZ + MIST_LIFT,
                (float) (submerged ? UNDERWATER_COLUMN
                        : MIST_DEPTH * Math.max(0.2, mist)),
                (float) Math.max(0, scatter),
                (float) Math.max(0, vibrance),
                (float) seconds);
    }

    /**
     * How much cloud a condition puts between the sun and the world.
     *
     * <p>A number rather than a switch on {@code Weather.Condition}, because
     * the weather is always halfway through changing and this has to be
     * interpolable — see {@code Weather.visibility}, which is the same idea and
     * is what this is derived from. Anything you cannot see far in, you cannot
     * see the sun through either.
     */
    public static double overcastFrom(double visibility) {
        return clamp01((1 - clamp01(visibility)) * 1.25);
    }

    /**
     * A colour as a per-channel multiplier around {@code 1}, pulled
     * {@code strength} of the way from neutral toward its own hue and then
     * scaled.
     *
     * <p>Normalising by the colour's own mean rather than by 255 is what makes
     * this a <em>hue</em> and not a brightness: a midnight sky and a noon sky
     * differ hugely in how much light they give and that is already carried by
     * the hour, so taking their brightness again here would darken the night
     * twice.
     */
    private static float[] hueOf(int rgb, double strength, double scale) {
        double r = ((rgb >> 16) & 0xFF), g = ((rgb >> 8) & 0xFF), b = (rgb & 0xFF);
        double mean = (r + g + b) / 3;
        if (mean < 1) return new float[] {(float) scale, (float) scale, (float) scale};
        return new float[] {
                (float) (scale * (1 + strength * (r / mean - 1))),
                (float) (scale * (1 + strength * (g / mean - 1))),
                (float) (scale * (1 + strength * (b / mean - 1))),
        };
    }

    private static int blend(int a, int b, double t) {
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        int r = (int) Math.round(ar + (br - ar) * t);
        int g = (int) Math.round(ag + (bg - ag) * t);
        int l = (int) Math.round(ab + (bb - ab) * t);
        return (r << 16) | (g << 8) | l;
    }

    private static double smoothstep(double edge0, double edge1, double x) {
        double t = clamp01((x - edge0) / Math.max(1e-9, edge1 - edge0));
        return t * t * (3 - 2 * t);
    }

    private static double clamp01(double v) {
        return v < 0 ? 0 : Math.min(v, 1);
    }
}
