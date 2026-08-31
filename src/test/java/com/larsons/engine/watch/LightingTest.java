package com.larsons.engine.watch;

import com.larsons.engine.graphics.EyeCamera;
import com.larsons.engine.graphics.MeshPass;
import com.larsons.engine.graphics.draw.RecordingTarget;
import com.larsons.engine.watch.life.Mutants;
import com.larsons.engine.watch.light.LightField;
import com.larsons.engine.watch.light.LightKind;
import com.larsons.engine.watch.light.Lights;
import com.larsons.engine.watch.light.PlacedLight;
import com.larsons.engine.watch.render.Mesh;
import com.larsons.engine.watch.render.Shapes;
import com.larsons.engine.watch.render.WatchRenderer;
import com.larsons.engine.watch.world.WatchMaterial;
import com.larsons.engine.watch.world.WatchMaterials;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fire, lamplight, and the things that glow in the dark.
 *
 * <p>Four layers, and the seams between them are what these check:
 *
 * <ol>
 *   <li>the <b>catalogue</b> ({@link LightKind}) — four rows of numbers, every
 *       one of which names things that have to exist elsewhere;</li>
 *   <li>the <b>world state</b> ({@link Lights}, {@link PlacedLight}) — what is
 *       standing where, how much is left in it, and what a save and the wire
 *       carry;</li>
 *   <li>the <b>verbs</b> ({@link WatchGame}) — lighting, dousing, filling,
 *       setting down, feeding and taking back;</li>
 *   <li>the <b>picture</b> ({@link LightField}, {@link WatchRenderer}) — which
 *       lights a frame carries and what they do to a surface.</li>
 * </ol>
 *
 * <p>The last of those is the one worth having: it is the only place the two
 * render paths can be compared without a graphics card, and the arithmetic they
 * share ({@link LightField#contribute}) is exactly what the fragment shader
 * runs. A test that a campfire brightens the ground it stands on is a test that
 * the whole chain — a placed light, a gathered frame, a culled mesh, a shaded
 * triangle — is wired up.
 */
@Timeout(180)
class LightingTest {

    private static final int WIDTH = 320, HEIGHT = 200;

    // --- the catalogue -------------------------------------------------------------

    /**
     * Every light is made of, fed by and packed into things that exist.
     *
     * <p>The catalogue is four rows of string keys, and a string key that names
     * nothing fails silently everywhere it is used: an uncraftable light, a
     * fire that cannot be fed, a cost nobody can pay.
     */
    @Test
    void everyLightNamesThingsThatExist() {
        assertFalse(LightKind.all().isEmpty());
        for (LightKind kind : LightKind.all()) {
            assertSame(kind, LightKind.of(kind.key()), kind + " does not round-trip");
            assertTrue(kind.radius() > 0, kind + " reaches nothing");
            assertTrue(kind.intensity() > 0, kind + " emits nothing");
            assertTrue(kind.note() != null && !kind.note().isBlank(),
                    kind + " has nothing to say about itself");

            if (kind.item() != null) {
                assertNotNull(Forage.byKey(kind.item()),
                        kind + " is carried as '" + kind.item() + "', which is not a thing");
                assertSame(kind, LightKind.ofItem(kind.item()));
                assertNotNull(Recipes.making(kind.item()),
                        kind + " cannot be made by anybody");
            } else {
                assertFalse(kind.cost().isEmpty(),
                        kind + " is neither carried nor built — nobody can ever have one");
            }
            for (String part : kind.cost().keySet()) {
                assertNotNull(Forage.byKey(part),
                        kind + " costs '" + part + "', which is not a thing");
            }
            if (kind.fuel() != null) {
                assertNotNull(Forage.byKey(kind.fuel()),
                        kind + " burns '" + kind.fuel() + "', which is not a thing");
                assertTrue(kind.fuelHours() > 0, kind + "'s fuel is worth nothing");
                assertFalse(kind.eternal(), kind + " has fuel and never runs out");
            }
        }
        assertNull(LightKind.CAMPFIRE.item(), "a campfire is built, not carried");
        assertTrue(LightKind.SPORE_LANTERN.eternal(), "spores go out");
        assertNull(LightKind.ofItem("blackberry"), "a blackberry is not a lamp");
        assertNull(LightKind.ofItem(null));
    }

    // --- what is standing where ------------------------------------------------------

    /** A fire burns down over real hours and then it is out — but still there. */
    @Test
    void aFireBurnsDownAndLeavesAHearth() {
        Lights lights = new Lights();
        PlacedLight fire = lights.place(LightKind.CAMPFIRE, 10, 20, 3, 0, "Kara", 0);
        assertTrue(fire.lit());
        assertEquals(1, fire.fuelLeft(), 1e-9);

        assertTrue(lights.burn(LightKind.CAMPFIRE.burnHours() * 0.5).isEmpty(),
                "half an evening in and it has gone out");
        assertTrue(fire.lit());
        assertTrue(fire.burnBrightness() > 0.9, "it should still be burning at full");

        List<PlacedLight> died = lights.burn(LightKind.CAMPFIRE.burnHours());
        assertEquals(List.of(fire), died, "nobody was told it went out");
        assertFalse(fire.lit());
        assertEquals(0, fire.burnBrightness(), 1e-9);
        assertEquals(1, lights.size(), "a cold hearth is still a hearth");
        assertSame(fire, lights.byId(fire.id()));
    }

    /** A torch is spent rather than tended: when it is out, it is gone. */
    @Test
    void aSpentTorchIsGone() {
        Lights lights = new Lights();
        PlacedLight torch = lights.place(LightKind.TORCH, 0, 0, 0, 0, "Kara", 0);
        lights.burn(LightKind.TORCH.burnHours() + 0.1);
        assertFalse(torch.lit());
        assertTrue(torch.spent());
        assertEquals(0, lights.size(), "the ash is still standing there");
    }

    /** Feeding relights, and cannot be stacked past a full charge. */
    @Test
    void feedingRelightsAFireAndIsCapped() {
        Lights lights = new Lights();
        PlacedLight fire = lights.place(LightKind.CAMPFIRE, 0, 0, 0, 0, "Kara", 0);
        lights.burn(LightKind.CAMPFIRE.burnHours() + 1);
        assertFalse(fire.lit());

        assertTrue(fire.feed(1), "a branch did nothing");
        assertTrue(fire.lit(), "feeding a cold fire did not light it");
        assertEquals(LightKind.CAMPFIRE.fuelHours(), fire.fuelHours(), 1e-9);

        for (int i = 0; i < 20; i++) fire.feed(1);
        assertEquals(LightKind.CAMPFIRE.burnHours(), fire.fuelHours(), 1e-9,
                "an armful of branches made a week-long bonfire");
        assertFalse(fire.feed(1), "a full fire took another branch anyway");
    }

    /** Two lights cannot stand inside one another. */
    @Test
    void lightsDoNotStackOnOneSpot() {
        Lights lights = new Lights();
        lights.place(LightKind.CAMPFIRE, 4, 4, 0, 0, "Kara", 0);
        assertTrue(lights.blocked(4, 4));
        assertTrue(lights.blocked(4 + Lights.MIN_GAP / 2, 4));
        assertFalse(lights.blocked(4 + Lights.MIN_GAP * 2, 4));
    }

    /** A save, and the wire, carry every light with whatever is left in it. */
    @Test
    void everyStandingLightSurvivesASaveAndTheWire() {
        Lights lights = new Lights();
        PlacedLight fire = lights.place(LightKind.CAMPFIRE, 12.5, -7.25, 3, 0, "Kara", 99);
        PlacedLight jar = lights.place(LightKind.SPORE_LANTERN, 3, 3, 1, 0.75, "Ben", 42);
        lights.burn(1.0);

        Lights loaded = new Lights();
        loaded.load(lights.toMap());
        assertEquals(2, loaded.size());
        PlacedLight backFire = loaded.byId(fire.id());
        assertNotNull(backFire);
        assertEquals(fire.fuelHours(), backFire.fuelHours(), 1e-3);
        assertEquals(LightKind.CAMPFIRE, backFire.kind());
        assertEquals(12.5, backFire.x(), 1e-9);
        assertEquals("Kara", backFire.placedBy());
        assertTrue(backFire.lit());
        assertTrue(loaded.byId(jar.id()).kind().eternal());

        // …and a light placed after loading does not reuse an id.
        PlacedLight another = loaded.place(LightKind.TORCH, 0, 0, 0, 0, "Ben", 0);
        assertSame(another, loaded.byId(another.id()));
        assertTrue(another.id() > jar.id(), "a loaded world handed out an id twice");

        Lights overWire = new Lights();
        overWire.loadRows(rowsOf(lights));
        assertEquals(2, overWire.size());
        assertEquals(fire.fuelHours(), overWire.byId(fire.id()).fuelHours(), 1e-3);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rowsOf(Lights lights) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object row : lights.toRows()) rows.add((Map<String, Object>) row);
        return rows;
    }

    // --- the verbs -------------------------------------------------------------------

    /**
     * A walk with one player standing somewhere a light can actually go.
     *
     * <p><b>A fixed seed, and then a check</b>, rather than
     * {@code Config.solo}, which rolls a new world every run. Where a player
     * spawns decides two things these tests depend on: whether the ground two
     * paces ahead is dry — a fire cannot be lit in a lake — and whether there
     * is a berry bush within arm's reach, which would win the reach prompt over
     * anything put down afterwards. Both are properties of the seed, so the
     * seed is chosen here rather than discovered as an intermittent failure.
     */
    private static WatchGame gameWithOnePlayer() {
        for (long seed : new long[]{31L, 7L, 5L, 11L, 101L, 2024L, 90210L}) {
            WatchGame game = new WatchGame(WatchGame.Config.hosted("Lamplight", seed));
            WatchPlayer me = game.join(1, "Kara");
            double x = me.x() + Math.sin(me.yaw()) * 2;
            double y = me.y() - Math.cos(me.yaw()) * 2;
            if (game.field().waterDepth(game.field().heightAt(x, y)) > 0) continue;
            if (game.pickTarget(1) != null) continue;
            return game;
        }
        throw new IllegalStateException("no seed put a walker on dry, empty ground");
    }

    /** One key lights it, and the same key puts it out. */
    @Test
    void oneKeyLightsAndDouses() {
        WatchGame game = gameWithOnePlayer();
        WatchPlayer me = game.player(1);
        me.satchel().clear();
        me.satchel().add("torch", 1);

        assertNotNull(game.tendLamp(1), "a torch in the bag would not light");
        assertEquals("torch", me.carriedLight());
        assertTrue(me.lampLit());
        assertEquals(LightKind.TORCH.burnHours(), me.lampFuel(), 1e-9);
        assertEquals(1, me.satchel().count("torch"),
                "lighting a torch should not consume it — carrying it is holding it");

        assertNotNull(game.tendLamp(1), "it would not go out");
        assertNull(me.carriedLight(), "it is still burning");
        assertEquals("torch", me.lamp(), "it left your hand as well as going out");
    }

    /**
     * Dousing keeps the oil.
     *
     * <p>The exploit this exists to stop: if putting a lamp out forgot how much
     * was left in it, then two keypresses would be a free refill and the whole
     * fuel economy would be a formality.
     */
    @Test
    void dousingKeepsWhatIsLeftInIt() {
        WatchGame game = gameWithOnePlayer();
        WatchPlayer me = game.player(1);
        me.satchel().clear();
        me.satchel().add("lantern", 1);
        game.tendLamp(1);
        me.burnLamp(4, false);
        double left = me.lampFuel();
        assertTrue(left > 0 && left < LightKind.LANTERN.burnHours());

        game.tendLamp(1);
        assertFalse(me.lampLit());
        game.tendLamp(1);
        assertTrue(me.lampLit());
        assertEquals(left, me.lampFuel(), 1e-9,
                "putting it out and lighting it again refilled it");
    }

    /** An empty lantern is filled from the satchel by the same key. */
    @Test
    void anEmptyLanternIsFilledBySap() {
        WatchGame game = gameWithOnePlayer();
        WatchPlayer me = game.player(1);
        me.satchel().clear();
        me.satchel().add("lantern", 1);
        game.tendLamp(1);
        me.burnLamp(LightKind.LANTERN.burnHours(), false);
        assertFalse(me.lampLit());
        assertEquals(0, me.lampFuel(), 1e-9);

        assertNotNull(game.tendLamp(1), "with no sap it should still say so");
        assertFalse(me.lampLit(), "an empty lantern lit itself out of nothing");

        me.satchel().add("sap", 1);
        assertNotNull(game.tendLamp(1));
        assertTrue(me.lampLit(), "sap in the bag did not reach the lantern");
        assertEquals(LightKind.LANTERN.burnHours(), me.lampFuel(), 1e-9);
        assertEquals(0, me.satchel().count("sap"), "the sap was not spent");
    }

    /** Empty hands and an armful of branches build a fire. */
    @Test
    void emptyHandsBuildAFire() {
        WatchGame game = gameWithOnePlayer();
        WatchPlayer me = game.player(1);
        me.satchel().clear();
        assertNull(game.setDownLight(1), "a fire out of nothing");

        LightKind.CAMPFIRE.cost().forEach((key, n) -> me.satchel().add(key, n));
        PlacedLight fire = game.setDownLight(1);
        assertNotNull(fire, "the makings of a fire would not make one");
        assertEquals(LightKind.CAMPFIRE, fire.kind());
        assertTrue(fire.lit());
        assertEquals(1, game.lights().size());
        for (String part : LightKind.CAMPFIRE.cost().keySet()) {
            assertEquals(0, me.satchel().count(part), part + " was not spent");
        }
    }

    /** What is in your hand goes down first, and keeps its oil. */
    @Test
    void settingDownALanternKeepsItsOil() {
        WatchGame game = gameWithOnePlayer();
        WatchPlayer me = game.player(1);
        me.satchel().clear();
        me.satchel().add("lantern", 1);
        // …and the makings of a fire, which must not win while a lamp is out.
        LightKind.CAMPFIRE.cost().forEach((key, n) -> me.satchel().add(key, n));
        game.tendLamp(1);
        me.burnLamp(2, false);
        double left = me.lampFuel();

        PlacedLight put = game.setDownLight(1);
        assertNotNull(put);
        assertEquals(LightKind.LANTERN, put.kind(),
                "it built a campfire while a lantern was in hand");
        assertEquals(left, put.fuelHours(), 1e-9, "the oil did not follow the lamp");
        assertTrue(put.lit());
        assertNull(me.lamp(), "the lamp is still in hand as well as on the ground");
        assertEquals(0, me.satchel().count("lantern"), "and still in the satchel");
    }

    /** Standing at a light is a "tend" prompt, and E does the tending. */
    @Test
    void standingAtAFireOffersToTendIt() {
        WatchGame game = gameWithOnePlayer();
        WatchPlayer me = game.player(1);
        me.satchel().clear();
        LightKind.CAMPFIRE.cost().forEach((key, n) -> me.satchel().add(key, n));
        PlacedLight fire = game.setDownLight(1);
        assertNotNull(fire);

        WatchGame.Pickable target = game.pickTarget(1);
        assertNotNull(target, "nothing in reach of a fire two metres away");
        assertEquals(WatchGame.Pickable.Kind.FIRE, target.kind());
        assertEquals("Tend", target.kind().verb());
        assertEquals(fire.flameZ(), target.z(), 1e-9);

        game.lights().burn(LightKind.CAMPFIRE.burnHours() * 0.9);
        double before = fire.fuelHours();
        assertTrue(fire.guttering(), "nine tenths of the way through and it is not low");
        me.satchel().add("fallen_branch", 1);
        String line = game.use(1);
        assertNotNull(line, "E at a guttering fire did nothing");
        assertEquals(before + LightKind.CAMPFIRE.fuelHours(), fire.fuelHours(), 1e-6,
                "the branch did not go on the fire");
        assertEquals(0, me.satchel().count("fallen_branch"));
    }

    /** Picking a lit lantern up puts it straight into an empty hand, still lit. */
    @Test
    void takingALanternBackKeepsItBurning() {
        WatchGame game = gameWithOnePlayer();
        WatchPlayer me = game.player(1);
        me.satchel().clear();
        me.satchel().add("lantern", 1);
        game.tendLamp(1);
        me.burnLamp(1.5, false);
        double left = me.lampFuel();
        game.setDownLight(1);

        String line = game.tendLight(1);
        assertNotNull(line);
        assertEquals(0, game.lights().size(), "it is still standing there");
        assertEquals(1, me.satchel().count("lantern"), "it did not go back in the bag");
        assertEquals("lantern", me.carriedLight(), "it did not come back lit");
        assertEquals(left, me.lampFuel(), 1e-3, "it refilled itself on the way up");
    }

    /** Dying drops the lamp with the bag it was in. */
    @Test
    void dyingPutsTheLampOut() {
        WatchGame game = gameWithOnePlayer();
        WatchPlayer me = game.player(1);
        me.satchel().add("torch", 1);
        game.tendLamp(1);
        assertNotNull(me.carriedLight());

        game.wound("Kara", 1.0, null);
        assertNull(me.carriedLight(), "they died holding a lit torch");
        assertNull(me.lamp());
        assertEquals(0, me.satchel().count("torch"));
    }

    /** A walk reopened finds the camp where it was left. */
    @Test
    void aSavedWalkKeepsItsFiresAndItsLamp() {
        WatchGame game = gameWithOnePlayer();
        WatchPlayer me = game.player(1);
        me.satchel().clear();
        me.satchel().add("lantern", 1);
        LightKind.CAMPFIRE.cost().forEach((key, n) -> me.satchel().add(key, n));
        game.tendLamp(1);
        PlacedLight fire = game.setDownLight(1);
        assertNotNull(fire);
        assertEquals(LightKind.LANTERN, fire.kind(), "the lamp went down first");

        Map<String, Object> saved = game.toMap();
        WatchGame reopened = new WatchGame(game.config());
        reopened.load(saved);
        assertEquals(1, reopened.lights().size(), "the camp is not there any more");
        assertEquals(LightKind.LANTERN, reopened.lights().all().get(0).kind());
    }

    // --- what the frame carries --------------------------------------------------------

    /** A view with one lit fire, one walker carrying a torch, and one mutant. */
    private static WatchView aLitWorld() {
        WatchView view = new WatchView();
        Lights lights = new Lights();
        lights.place(LightKind.CAMPFIRE, 0, 0, 0, 0, "Kara", 0);
        view.loadLights(rowsOf(lights));
        view.loadWalkers(List.of(walkerRow(1, "Kara", 2, 0, 0, "torch")));
        view.loadCreatures(List.of(creatureRow(Mutants.all().get(0).key(), 6, 0, 0)));
        return view;
    }

    private static Map<String, Object> walkerRow(int id, String name, double x, double y,
                                                 double z, String light) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("n", name);
        row.put("x", x);
        row.put("y", y);
        row.put("z", z);
        if (light != null) {
            row.put("lt", light);
            row.put("lh", 1.0);
        }
        return row;
    }

    private static Map<String, Object> creatureRow(String species, double x, double y,
                                                   double z) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", 7L);
        row.put("sp", species);
        row.put("x", x);
        row.put("y", y);
        row.put("z", z);
        return row;
    }

    /** Everything burning ends up in the frame's list. */
    @Test
    void everythingBurningReachesTheFrame() {
        LightField field = new LightField();
        field.gather(aLitWorld(), 0, 0, 1.7, 0);
        assertEquals(3, field.count(),
                "a fire, a carried torch and a mutant are three lights");
        for (MeshPass.Light light : field.lights()) {
            assertTrue(light.intensity() > 0);
            assertTrue(light.radius() > 0);
        }
    }

    /** A light nobody lit lights nothing. */
    @Test
    void aColdHearthLightsNothing() {
        WatchView view = new WatchView();
        Lights lights = new Lights();
        lights.place(LightKind.CAMPFIRE, 0, 0, 0, 0, "Kara", 0);
        lights.burn(LightKind.CAMPFIRE.burnHours() + 1);
        view.loadLights(rowsOf(lights));

        LightField field = new LightField();
        field.gather(view, 0, 0, 1.7, 0);
        assertEquals(0, field.count(), "an unlit fire is still lighting the wood");
    }

    /** A mutant carries its own glow colour into the frame. */
    @Test
    void aMutantLightsTheGroundItsOwnColour() {
        WatchView view = new WatchView();
        Mutants.Kind mutant = Mutants.all().get(0);
        view.loadCreatures(List.of(creatureRow(mutant.key(), 3, 0, 0)));

        LightField field = new LightField();
        field.gather(view, 0, 0, 1.7, 0);
        assertEquals(1, field.count(), "the thing in the treeline is not lit");
        MeshPass.Light light = field.lights().get(0);
        assertEquals(((mutant.glow() >> 16) & 0xFF) / 255f, light.r(), 1 / 255f,
                "it is not glowing its own colour");
        assertEquals(LightField.MUTANT_REACH, light.radius(), 1e-4);
        assertTrue(light.z() > 1, "the glow is at its feet rather than its chest");
    }

    /**
     * The list is bounded, and the cap takes the ones that do not matter.
     *
     * <p>The failure this exists to prevent is not a crash: it is a camp of
     * forty lanterns quietly costing every fragment on screen forty distance
     * calculations, or — with a fixed-size uniform array — the nearest fire
     * being the one that gets dropped.
     */
    @Test
    void tooManyLightsKeepTheOnesThatMatter() {
        WatchView view = new WatchView();
        Lights lights = new Lights();
        // One at the camera's feet, and forty strung out down the valley.
        lights.place(LightKind.CAMPFIRE, 0, 1, 0, 0, "Kara", 0);
        for (int i = 0; i < 40; i++) {
            lights.place(LightKind.LANTERN, 30 + i * 4, 0, 0, 0, "Ben", 0);
        }
        view.loadLights(rowsOf(lights));

        LightField field = new LightField();
        field.gather(view, 0, 0, 1.7, 0);
        assertEquals(MeshPass.MAX_LIGHTS, field.count(), "the cap is not being applied");
        boolean keptTheNearest = false;
        for (MeshPass.Light light : field.lights()) {
            if (Math.abs(light.y() - 1) < 0.01 && Math.abs(light.x()) < 0.01) {
                keptTheNearest = true;
            }
        }
        assertTrue(keptTheNearest, "the fire you are standing at was the one dropped");
    }

    // --- the arithmetic both backends run ----------------------------------------------

    /** A light reaches nothing at its own radius, and most at its middle. */
    @Test
    void theFalloffIsCompact() {
        MeshPass.Light lamp = MeshPass.Light.of(0, 0, 0, 0xFFFFFF, 10, 1);
        double[] out = new double[3];

        LightField.contribute(lamp, 0, 0, 10.01, 0, 0, 1, out);
        assertEquals(0, out[0], 1e-9, "it reaches past its own radius");

        LightField.contribute(lamp, 0, 0, 5, 0, 0, -1, out);
        double halfway = out[0];
        assertTrue(halfway > 0, "it reaches nothing halfway to its edge");

        out[0] = out[1] = out[2] = 0;
        LightField.contribute(lamp, 0, 0, 1, 0, 0, -1, out);
        assertTrue(out[0] > halfway * 2, "the falloff is not steep enough to read");
        assertTrue(out[0] <= 1.0001, "a surface at the flame is over-lit");
    }

    /**
     * A surface facing away still gets the wrap share, and no more.
     *
     * <p>Both halves matter. Without the wrap a wood at night is black on
     * everything not squarely facing the flame; with too much of it a fire is a
     * flat wash and the shapes go away.
     */
    @Test
    void aSurfaceFacingAwayGetsTheWrapShare() {
        MeshPass.Light lamp = MeshPass.Light.of(0, 0, 0, 0xFFFFFF, 10, 1);
        double[] facing = new double[3];
        double[] away = new double[3];
        LightField.contribute(lamp, 0, 0, 2, 0, 0, -1, facing);
        LightField.contribute(lamp, 0, 0, 2, 0, 0, 1, away);

        assertTrue(facing[0] > away[0], "orientation does not matter at all");
        assertEquals(LightField.WRAP, away[0] / facing[0], 1e-6,
                "the wrap share is not the constant both backends read");
        assertEquals(MeshPass.LIGHT_WRAP, LightField.WRAP, 1e-9,
                "the painter and the shader are reading different numbers");
    }

    /** A light's colour is the colour it adds. */
    @Test
    void aGreenLampAddsGreen() {
        MeshPass.Light jar = MeshPass.Light.of(0, 0, 0, 0x00FF00, 8, 1);
        double[] out = new double[3];
        LightField.contribute(jar, 0, 0, 1, 0, 0, -1, out);
        assertEquals(0, out[0], 1e-6);
        assertTrue(out[1] > 0.5);
        assertEquals(0, out[2], 1e-6);
    }

    // --- and what it does to a frame ------------------------------------------------------

    /** A wall of ground three metres in front of the camera, facing it. */
    private static Mesh wall(int albedo) {
        Mesh.Builder mesh = Mesh.builder(0, 0, 0, false, 1);
        float[] uv = new float[4];
        WatchMaterials.uv(WatchMaterial.GRASS, uv);
        Shapes.quad(mesh, 6, -3, 0, -6, -3, 0, -6, -3, 6, 6, -3, 6, uv, albedo);
        return mesh.build();
    }

    private static EyeCamera camera() {
        EyeCamera eye = new EyeCamera(WIDTH, HEIGHT);
        eye.place(0, 0, 2);
        eye.look(0, 0);
        return eye;
    }

    /** The brightest triangle the painter actually filled, as {@code 0xRRGGBB}. */
    private static int brightestFill(RecordingTarget target) {
        int best = 0;
        double bestSum = -1;
        for (RecordingTarget.Cmd.Shape shape
                : target.ofType(RecordingTarget.Cmd.Shape.class)) {
            if (!"fillPolygon".equals(shape.op())) continue;
            int argb = shape.argb();
            double sum = ((argb >> 16) & 0xFF) + ((argb >> 8) & 0xFF) + (argb & 0xFF);
            if (sum > bestSum) {
                bestSum = sum;
                best = argb & 0xFFFFFF;
            }
        }
        return best;
    }

    private static int drawWall(List<MeshPass.Light> lights) {
        RecordingTarget target = new RecordingTarget(WIDTH, HEIGHT);
        WatchRenderer renderer = new WatchRenderer();
        // Midnight, which is when any of this matters.
        renderer.begin(target, camera(), WIDTH, HEIGHT, WatchClock.at(0.0),
                0x101828, 0x101828);
        renderer.setFogRange(200, 400);
        renderer.setLights(lights);
        renderer.submit(wall(0x808080));
        renderer.flush(target);
        return brightestFill(target);
    }

    /**
     * A campfire brightens the ground it stands on — <b>through the whole
     * chain.</b>
     *
     * <p>The painter's own path, end to end: a light in the frame's list, culled
     * against a mesh's bounding box, evaluated against a triangle's own normal,
     * multiplied into a vertex colour and filled. Every one of those steps can
     * fail silently and leave a picture that still looks like a night-time wood.
     */
    @Test
    void aCampfireBrightensTheGroundInFrontOfIt() {
        int dark = drawWall(List.of());
        int lit = drawWall(List.of(MeshPass.Light.of(0, -2, 1, 0xFF9A3C, 12, 1.15)));

        assertTrue(red(lit) > red(dark), "the fire did not light the ground");
        assertTrue(red(lit) - red(dark) > 20,
                "the fire lit it by " + (red(lit) - red(dark)) + "/255, which nobody sees");
        assertTrue(red(lit) - blue(lit) > red(dark) - blue(dark),
                "an orange fire did not make the ground any warmer than it was");
    }

    /** A light on the far side of the world does not light what is in front of you. */
    @Test
    void aDistantLampChangesNothing() {
        int dark = drawWall(List.of());
        int far = drawWall(List.of(MeshPass.Light.of(400, 400, 1, 0xFF9A3C, 12, 1.15)));
        assertEquals(dark, far, "a fire four hundred metres away lit the ground");
    }

    /**
     * The hour still decides the base light — <b>on the painter and on a card
     * alike.</b>
     *
     * <p>Midnight is a quarter of noon, and until the lighting seam existed the
     * GPU path multiplied by nothing at all: a machine with a driver played the
     * whole game at noon. There is no context here to check the card, but the
     * number it is handed is the number this asserts, and it comes from one
     * place.
     */
    @Test
    void theHourStillDecidesTheBaseLight() {
        RecordingTarget noonTarget = new RecordingTarget(WIDTH, HEIGHT);
        WatchRenderer renderer = new WatchRenderer();
        renderer.begin(noonTarget, camera(), WIDTH, HEIGHT, WatchClock.at(0.5),
                0x8FC0E8, 0x8FC0E8);
        renderer.setFogRange(200, 400);
        renderer.submit(wall(0x808080));
        renderer.flush(noonTarget);

        int noon = brightestFill(noonTarget);
        int midnight = drawWall(List.of());
        assertTrue(green(noon) > green(midnight) * 2,
                "midnight and noon are the same brightness");
    }

    private static int red(int rgb) { return (rgb >> 16) & 0xFF; }

    private static int green(int rgb) { return (rgb >> 8) & 0xFF; }

    private static int blue(int rgb) { return rgb & 0xFF; }
}
