package com.larsons.engine.watch;

import com.larsons.engine.graphics.MeshPass;
import com.larsons.engine.watch.light.SkyLight;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The half of the world's light that is not a lamp.
 *
 * <p><b>Every one of these is a question about the picture, asked as
 * arithmetic.</b> {@link SkyLight} turns a clock, two colours and a forecast
 * into a {@link MeshPass.Sky}, and the GL backend then spends a shadow pass and
 * a fragment's worth of instructions on the answer. What can go wrong is
 * therefore not "it crashes" — it is "the sun is up at midnight", "a storm is
 * as bright as noon", "the fog never gets thick enough to notice", each of
 * which draws a perfectly good frame of the wrong world.
 *
 * <p>The GL half — that a canopy actually puts a shadow on the ground, that the
 * air actually carries a lamp — is {@code GlLightingTest}, which needs a
 * driver. This needs nothing, which is why it is where the numbers live.
 */
@Timeout(60)
class SkyLightTest {

    /** A summer-green biome: blue sky, pale haze. */
    private static final int SKY = 0x8FC0E8;
    private static final int FOG = 0xC8D4DC;

    private static MeshPass.Sky at(double timeOfDay) {
        return at(timeOfDay, 0, 0);
    }

    private static MeshPass.Sky at(double timeOfDay, double weather, double overcast) {
        return SkyLight.of(WatchClock.at(timeOfDay), SKY, FOG, weather, overcast,
                false, 14, 0);
    }

    private static float luma(float r, float g, float b) {
        return 0.2126f * r + 0.7152f * g + 0.0722f * b;
    }

    // --- the sun -------------------------------------------------------------------

    /**
     * <b>The sun is where the clock says it is, and only when it is up.</b>
     *
     * <p>The direction comes straight off {@link WatchClock#sunDirection} so
     * that the shadows a card casts fall the same way as the sun the painter
     * draws in the sky — two suns in one frame, pointing different ways, is the
     * kind of thing nobody spots for a week and then cannot unsee.
     */
    @Test
    void theSunRisesInTheEastAndSetsInTheWest() {
        MeshPass.Sky morning = at(0.30);   // 07:12
        MeshPass.Sky evening = at(0.75);   // 18:00
        assertTrue(morning.sunX() > 0.2, "the sun did not rise in the east");
        assertTrue(evening.sunX() < -0.2, "the sun did not set in the west");
        assertTrue(morning.sunZ() > 0 && evening.sunZ() > 0,
                "the sun is below the horizon in the middle of the morning");

        MeshPass.Sky noon = at(0.5);
        // Not straight up: this sun tracks across the southern sky, so even at
        // its highest it is leaning about twenty-six degrees south. That tilt
        // is the reason a north-facing slope is in shade all day, which is most
        // of what makes a hillside read as a hillside.
        assertTrue(noon.sunZ() > 0.85, "the sun is not high at noon");
        assertTrue(noon.sunY() < 0, "the sun is not in the southern sky");
        assertTrue(noon.sunUp(), "there is no sun at noon");
        assertTrue(noon.castsShadows(), "nothing casts a shadow at noon");
    }

    /**
     * Noon is a sun; midnight is not.
     *
     * <p>And the second half of that matters more than the first. A night that
     * still ran a shadow pass would cost a full second submission of every mesh
     * in view for a term a hundredth of the frame wide — see the note on
     * {@code shadow} in {@link SkyLight}.
     */
    @Test
    void midnightHasAMoonAndNoShadows() {
        MeshPass.Sky midnight = at(0.0);
        assertFalse(midnight.castsShadows(),
                "midnight is paying for a shadow map nobody can see");
        assertEquals(0, midnight.shadow(), 1e-6);

        // …but it is not flat. The moon is opposite the sun, and the whole
        // reason to keep a directional term at night is that you can still read
        // the shape of the ground by it.
        assertTrue(midnight.sunZ() > 0.5,
                "the moon is not up at midnight, so the night has no direction to it");
        assertTrue(luma(midnight.sunR(), midnight.sunG(), midnight.sunB()) > 0,
                "the night is lit by nothing directional at all");
        assertTrue(midnight.sunB() > midnight.sunR(),
                "moonlight is meant to be cold and came out warmer than the sun");
    }

    /** Dawn and dusk are warm; noon is not. */
    @Test
    void theLowSunIsWarmAndTheHighSunIsNot() {
        MeshPass.Sky dawn = at(0.26);      // 06:14, just over the horizon
        MeshPass.Sky noon = at(0.5);
        double dawnWarmth = dawn.sunR() / Math.max(1e-6, dawn.sunB());
        double noonWarmth = noon.sunR() / Math.max(1e-6, noon.sunB());
        assertTrue(dawnWarmth > noonWarmth * 1.3,
                "the sun on the horizon is no warmer than the sun overhead: "
                        + dawnWarmth + " against " + noonWarmth);
    }

    /**
     * <b>A storm has no sun in it.</b>
     *
     * <p>Cloud is the difference between a flat lid of light and a world with a
     * direction to it, and it has to come off the same number the fog range
     * does — the weather is always halfway through changing, and a sun that
     * switched off while the haze thickened smoothly would be the one part of
     * a change nobody believes.
     */
    @Test
    void cloudTakesTheSunAndTheShadowsWithIt() {
        MeshPass.Sky clear = at(0.5, 0, 0);
        MeshPass.Sky storm = at(0.5, 0.66, SkyLight.overcastFrom(0.34));
        assertTrue(luma(storm.sunR(), storm.sunG(), storm.sunB())
                        < luma(clear.sunR(), clear.sunG(), clear.sunB()) * 0.5,
                "a storm at noon is as sunny as a clear noon");
        assertTrue(storm.shadow() < clear.shadow() * 0.5,
                "a storm is still casting hard shadows");
        assertEquals(0, SkyLight.overcastFrom(1.0), 1e-9,
                "perfect visibility somehow has cloud in it");
        assertEquals(1, SkyLight.overcastFrom(0.0), 1e-9,
                "nothing visible at all somehow has a clear sky");
    }

    // --- the ambient ---------------------------------------------------------------

    /**
     * <b>What faces up and what faces down do not get the same light.</b>
     *
     * <p>This pair is the cheapest thing in the whole feature and does the most
     * work: one {@code mix} in the shader, and it is the difference between
     * low-poly ground that reads as carved and low-poly ground that reads as a
     * flat sheet of green.
     */
    @Test
    void theSkyLightsWhatFacesUpAndTheGroundBouncesBackLess() {
        MeshPass.Sky noon = at(0.5);
        float up = luma(noon.skyR(), noon.skyG(), noon.skyB());
        float down = luma(noon.groundR(), noon.groundG(), noon.groundB());
        assertTrue(up > down * 1.25,
                "the hemisphere is nearly flat: " + up + " above, " + down + " below");
        assertTrue(down > 0.4,
                "the underside of everything is nearly black, which is not a bounce, "
                        + "it is a hole");

        // …and each end carries its own colour rather than a grey. A blue sky
        // has to leave a blue cast on what looks at it.
        assertTrue(noon.skyB() > noon.skyR(),
                "a blue sky is lighting upward faces warm");
    }

    /**
     * <b>The hour is not in here twice.</b>
     *
     * <p>{@code WatchClock.lightTint} is still the one number that says how
     * bright it is, and it reaches the card by its own road — the daylight
     * multiplier of {@link MeshPass#setLighting}, which the painter uses as
     * well. What this class produces is a <em>redistribution</em> of it, so the
     * two ambient ends have to come out as multipliers around one at any hour:
     * scale them by the daylight as well and midnight would be a quarter of a
     * quarter, which is black.
     *
     * <p>The one term that does carry the hour is the sun, because there is
     * nothing else for it to be carried by — and it duly all but disappears at
     * midnight, leaving the moon.
     */
    @Test
    void theHourIsCarriedByTheClockAndNotCountedAgainHere() {
        for (double hour : new double[] {0.0, 0.25, 0.5, 0.75}) {
            MeshPass.Sky sky = at(hour);
            float up = luma(sky.skyR(), sky.skyG(), sky.skyB());
            assertTrue(up > 0.6 && up < 1.4,
                    "the ambient at " + hour + " came out at " + up
                            + ", which is the hour being applied to it twice");
        }
        MeshPass.Sky noon = at(0.5);
        MeshPass.Sky midnight = at(0.0);
        assertTrue(luma(noon.sunR(), noon.sunG(), noon.sunB())
                        > luma(midnight.sunR(), midnight.sunG(), midnight.sunB()) * 4,
                "the moon is nearly as bright as the sun");
    }

    /**
     * <b>The sun adds more than the ambient gives up.</b>
     *
     * <p>The one number in this class that is a deliberate departure from
     * physics rather than an approximation of it, and the reason the whole
     * thing was written: a model that conserved light exactly could only
     * redistribute the flat multiplier's brightness, and the world would come
     * out looking the same overall. What is wanted is a sunlit slope that is
     * brighter than it used to be.
     */
    @Test
    void aSlopeFacingTheSunIsBrighterThanTheFlatMultiplierWas() {
        WatchClock clock = WatchClock.at(0.5);
        double[] flat = new double[3];
        clock.lightTint(flat);
        MeshPass.Sky noon = at(0.5);

        // A face square onto the sun, lit by the sky above and the sun itself.
        double facing = flat[1] * noon.skyG() + noon.sunG();
        assertTrue(facing > flat[1] * 1.10,
                "ground in full sun is no brighter than it was under the flat "
                        + "multiplier: " + facing + " against " + flat[1]);

        // …and one turned away is dimmer, but nowhere near black: the mesher has
        // already shaded it once, and darkening it twice is how a wood becomes
        // a silhouette.
        double away = flat[1] * noon.groundG();
        assertTrue(away < flat[1] && away > flat[1] * 0.5,
                "an unlit underside came out at " + away + " against " + flat[1]);
    }

    // --- the air -------------------------------------------------------------------

    /** Clear weather is clear; thick weather is not. */
    @Test
    void weatherIsWhatMakesTheAirThick() {
        assertEquals(0, at(0.5, 0, 0).haze(), 1e-9, "a clear noon has haze in it");
        MeshPass.Sky fog = at(0.5, 0.72, SkyLight.overcastFrom(0.28));
        assertTrue(fog.haze() > 0.004,
                "fog at noon is thinner than a hundred and fifty metres of e-folding, "
                        + "which nobody would call fog");
        assertTrue(fog.hazeDepth() > at(0.5, 0, 0).hazeDepth(),
                "the mist got no deeper when the fog rolled in");
    }

    /**
     * Mist lies where the ground is, and a ridge stands out of it.
     *
     * <p>The floor is passed in rather than assumed, because the only height
     * the scene reliably knows is the one under the player's own feet — and a
     * fog bank pinned to sea level in a world with hills is a fog bank you walk
     * above and never see.
     */
    @Test
    void theMistPoolsJustAboveTheGroundItWasGiven() {
        MeshPass.Sky low = SkyLight.of(WatchClock.at(0.5), SKY, FOG, 0.7, 0.4,
                false, 12, 0);
        MeshPass.Sky high = SkyLight.of(WatchClock.at(0.5), SKY, FOG, 0.7, 0.4,
                false, 260, 0);
        assertTrue(low.hazeFloor() > 12 && low.hazeFloor() < 14,
                "the mist is not lying on the ground it was told about");
        assertEquals(248, high.hazeFloor() - low.hazeFloor(), 1e-6,
                "the mist did not follow the ground up the hill");
    }

    /** There is mist at dawn whether or not the forecast asked for any. */
    @Test
    void thereIsAlwaysSomethingLyingInTheValleyAtDawn() {
        MeshPass.Sky dawn = at(0.24);
        MeshPass.Sky afternoon = at(0.62);
        assertEquals(WatchClock.Phase.DAWN, WatchClock.phaseOf(0.24), "wrong hour");
        assertTrue(dawn.haze() > 0,
                "a clear dawn has nothing at all lying in the valley");
        assertEquals(0, afternoon.haze(), 1e-9,
                "a clear afternoon has mist in it");
        assertTrue(dawn.hazeDepth() > afternoon.hazeDepth() * 1.5,
                "a clear dawn has no more lying in the valley than a clear afternoon");
        // …and it is a band near the ground rather than a wash over everything:
        // thin enough that a canopy stands out of it.
        assertTrue(dawn.hazeDepth() < 6,
                "the dawn mist is deep enough to swallow the trees as well");
    }

    /**
     * <b>Lamps light the air most at night and in fog.</b>
     *
     * <p>Which is the whole of "a campfire casts broad rays across a clearing":
     * the surface term makes a pool of light on the ground, and this is what
     * makes the space above the pool glow.
     */
    @Test
    void theAirCarriesALampBestAtNightAndInFog() {
        double noon = at(0.5).scatter();
        double night = at(0.0).scatter();
        double foggyNight = at(0.0, 0.72, 0.9).scatter();
        assertTrue(night > noon * 1.5,
                "a lamp glows as much through midday air as through midnight air");
        assertTrue(foggyNight > night * 1.5,
                "fog made no difference to what the air carries");
        assertTrue(noon >= 0, "a negative amount of light in the air");
    }

    /** A storm is drab on purpose, and a clear day is not. */
    @Test
    void theGradeBacksOffExactlyAsFarAsTheWeatherComesOn() {
        assertTrue(at(0.5, 0, 0).vibrance() > at(0.5, 1, 1).vibrance(),
                "a storm is graded as vividly as a clear afternoon");
        assertTrue(at(0.5, 1, 1).vibrance() > 0,
                "a storm has had the colour taken out of it entirely");
    }

    // --- and the promise the whole seam rests on ------------------------------------

    /**
     * {@link MeshPass.Sky#PLAIN} is the identity of every term it names.
     *
     * <p><b>Load-bearing, and not obviously so.</b> One shader draws both this
     * world and the voxel one, and the voxel one's shading is baked into its
     * vertex colours. Every field here is therefore the value that makes its
     * own term vanish: equal ambients, so their mix is exactly one; no sun; no
     * shadow; no weather; no grade. Anything else and the block game would have
     * changed colour the moment the Field Guide grew a sky.
     */
    @Test
    void aPlainSkyChangesNothingAtAll() {
        MeshPass.Sky plain = MeshPass.Sky.PLAIN;
        assertEquals(plain.skyR(), plain.groundR(), 0,
                "the two ambients differ, so a plain sky shades by which way a "
                        + "face is turned");
        assertEquals(plain.skyG(), plain.groundG(), 0);
        assertEquals(plain.skyB(), plain.groundB(), 0);
        assertEquals(1, plain.skyR(), 0, "a plain sky is not a multiplier of one");
        assertEquals(0, plain.sunR() + plain.sunG() + plain.sunB(), 0);
        assertEquals(0, plain.shadow(), 0);
        assertEquals(0, plain.haze(), 0);
        assertEquals(0, plain.scatter(), 0);
        assertEquals(0, plain.vibrance(), 0);
        assertFalse(plain.sunUp(), "a plain sky has a sun in it");
        assertFalse(plain.castsShadows(), "a plain sky casts shadows");
    }

    /** Nothing here is ever a NaN, whatever it is asked. */
    @Test
    void everyHourOfEveryForecastIsANumber() {
        for (int hour = 0; hour < 48; hour++) {
            for (double weather : new double[] {0, 0.5, 1, -1, 2}) {
                MeshPass.Sky sky = SkyLight.of(WatchClock.at(hour / 48.0), SKY, FOG,
                        weather, weather, hour % 7 == 0, hour * 3.0, hour);
                assertTrue(finite(sky), "half past " + hour + " in weather " + weather
                        + " produced " + sky);
            }
        }
        // …including a biome whose sky and haze are pure black, which is what
        // the underwater tint approaches at midnight.
        assertTrue(finite(SkyLight.of(WatchClock.at(0.0), 0, 0, 1, 1, true, 0, 0)),
                "a black sky divided by its own brightness");
    }

    private static boolean finite(MeshPass.Sky sky) {
        return Double.isFinite(sky.sunX()) && Double.isFinite(sky.sunY())
                && Double.isFinite(sky.sunZ())
                && Float.isFinite(sky.sunR()) && Float.isFinite(sky.sunG())
                && Float.isFinite(sky.sunB())
                && Float.isFinite(sky.skyR()) && Float.isFinite(sky.skyG())
                && Float.isFinite(sky.skyB())
                && Float.isFinite(sky.groundR()) && Float.isFinite(sky.groundG())
                && Float.isFinite(sky.groundB())
                && Float.isFinite(sky.shadow()) && Float.isFinite(sky.haze())
                && Double.isFinite(sky.hazeFloor()) && Float.isFinite(sky.hazeDepth())
                && Float.isFinite(sky.scatter()) && Float.isFinite(sky.vibrance())
                && Float.isFinite(sky.seconds());
    }
}
