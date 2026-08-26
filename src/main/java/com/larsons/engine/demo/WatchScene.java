package com.larsons.engine.demo;

import com.larsons.engine.config.GameContext;
import com.larsons.engine.config.PlayerSettings;
import com.larsons.engine.graphics.EyeCamera;
import com.larsons.engine.graphics.draw.DrawTarget;
import com.larsons.engine.input.GameAction;
import com.larsons.engine.input.InputManager;
import com.larsons.engine.input.KeyBinds;
import com.larsons.engine.input.Pointer;
import com.larsons.engine.scene.AbstractScene;
import com.larsons.engine.watch.Cultivation;
import com.larsons.engine.watch.Fishing;
import com.larsons.engine.watch.Forage;
import com.larsons.engine.watch.Lure;
import com.larsons.engine.watch.Recipes;
import com.larsons.engine.watch.Satchel;
import com.larsons.engine.watch.Spotlight;
import com.larsons.engine.watch.WatchClock;
import com.larsons.engine.watch.WatchGame;
import com.larsons.engine.watch.WatchPlayer;
import com.larsons.engine.watch.WatchStore;
import com.larsons.engine.watch.WatchView;
import com.larsons.engine.watch.build.BuildPiece;
import com.larsons.engine.watch.build.Structure;
import com.larsons.engine.watch.life.AnimState;
import com.larsons.engine.watch.life.AnimalModel;
import com.larsons.engine.watch.life.AnimalModels;
import com.larsons.engine.watch.net.WatchSession;
import com.larsons.engine.watch.render.FloraMesher;
import com.larsons.engine.watch.render.Mesh;
import com.larsons.engine.watch.render.Shapes;
import com.larsons.engine.watch.render.WatchRenderer;
import com.larsons.engine.watch.world.ChunkStreamer;
import com.larsons.engine.watch.world.TerrainField;
import com.larsons.engine.watch.world.TreeInstance;
import com.larsons.engine.watch.world.WatchBiome;
import com.larsons.engine.watch.world.WatchChunk;
import com.larsons.engine.watch.world.WatchMaterial;
import com.larsons.engine.watch.world.WatchMaterials;

import java.awt.Color;
import java.awt.Font;
import java.util.ArrayList;
import java.util.List;

/**
 * The walk itself.
 *
 * <h2>What this scene is responsible for, and what it is not</h2>
 *
 * <p>It draws, it reads the keyboard, and it moves the local camera. It decides
 * <b>nothing</b> about the world: every verb — spotting, picking, planting,
 * building, casting — is a request handed to the {@link WatchSession}, which
 * either passes it to a locally-owned {@link WatchGame} or puts it on a socket.
 * The scene then draws whatever comes back in the {@link WatchView}. That split
 * is why single-player and multiplayer are the same game and not two, and it is
 * why nothing in this file can cheat.
 *
 * <h2>The frame</h2>
 *
 * <ol>
 *   <li>Read the mouse and the keys; move the local player over the
 *       heightfield.</li>
 *   <li>Tell the session where we are, and let it advance.</li>
 *   <li>Keep the chunks around us loaded ({@link ChunkStreamer}, on its own
 *       worker threads — nothing is generated on this thread).</li>
 *   <li>Build one small mesh of everything that moves — animals, the other
 *       walkers, feeders, crops, buildings — and hand it, plus every loaded
 *       chunk's four static meshes, to the {@link WatchRenderer}.</li>
 *   <li>Draw the HUD.</li>
 * </ol>
 *
 * <p><b>The renderer decides how.</b> On a machine with a GL backend the meshes
 * go to the card and are drawn with a depth buffer; on a plain JRE the same
 * meshes are projected, sorted and filled through Java2D. This scene submits
 * the same things either way and never asks which happened.
 */
public class WatchScene extends AbstractScene {

    /** The scene the game runs in. */
    public static final String NAME = "watch";

    // --- how it feels ---------------------------------------------------------------

    /** Radians of turn per pixel of mouse travel, before the player's own scale. */
    private static final double LOOK_STEP = 0.0032;

    /** How fast the camera settles onto the ground under it, per second. */
    private static final double STEP_SMOOTHING = 12;

    /** How far behind the player the third-person camera sits, in metres. */
    private static final double THIRD_PERSON_BACK = 4.2;

    /** How often the client tells the host where it is, in seconds. */
    private static final double MOVE_INTERVAL = 1 / 20.0;

    /** How often a solo walk is written to disk, in seconds. */
    private static final double AUTOSAVE_INTERVAL = 45;

    // --- palette --------------------------------------------------------------------

    private static final Color HUD_INK = new Color(238, 244, 236);
    private static final Color HUD_DIM = new Color(176, 192, 178);
    private static final Color HUD_PANEL = new Color(14, 22, 18, 232);
    private static final Color HUD_ACCENT = new Color(140, 208, 150);
    private static final Color HUD_WARN = new Color(240, 190, 110);
    private static final Font HUD_FONT = new Font("SansSerif", Font.PLAIN, 14);
    private static final Font HUD_BOLD = new Font("SansSerif", Font.BOLD, 15);
    private static final Font HUD_SMALL = new Font("SansSerif", Font.PLAIN, 12);
    private static final Font TITLE_FONT = new Font("SansSerif", Font.BOLD, 22);

    /** Which overlay is up, if any. */
    private enum Panel { NONE, SATCHEL, BUILD, PAUSED }

    private final GameContext ctx;

    private WatchSession session;
    private WatchStore store;
    private ChunkStreamer streamer;

    private final EyeCamera eye = new EyeCamera();
    private final WatchRenderer renderer = new WatchRenderer();
    private final WatchClock clock = WatchClock.fromSystem();

    private final int[] lookMotion = new int[2];
    private final double[] scratch3 = new double[3];

    private double px, py, pz;
    private double yaw, pitch;
    private double smoothedGround;
    private boolean crouching;
    private boolean thirdPerson;

    private Panel panel = Panel.NONE;
    private int satchelIndex;
    private int recipeIndex;
    private int pieceIndex;
    private int pieceTurn;
    private boolean pieceInTree;

    private double moveTimer;
    private double saveTimer;
    private int frame;
    private String prompt = "";
    private long lookingAtId;

    public WatchScene(GameContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Take over a session handed across from the lobby.
     *
     * <p>Called before the transition, so {@link #onEnter} finds a world to
     * stand in rather than having to invent one.
     */
    public void adopt(WatchSession session, WatchStore store) {
        closeSession();
        this.session = session;
        this.store = store;
    }

    /** The session being played, for tests and the pause screen. */
    public WatchSession session() { return session; }

    /** What is on screen this frame. */
    public WatchView view() { return session == null ? null : session.view(); }

    /** The streamer keeping the world loaded, or {@code null} before entry. */
    public ChunkStreamer streamer() { return streamer; }

    @Override
    public void onEnter() {
        panel = Panel.NONE;
        prompt = "";
        frame = 0;
        if (session == null) return;

        // Solo: be the player the save restored, or join a new one if this is a
        // fresh world. Hosting or joining, the server did that when the socket
        // opened and tells us who we are in the welcome.
        //
        // Adopting rather than always joining is what makes "Continue" resume:
        // a reopened walk already has you in it, and joining a second player
        // beside you left the camera at the origin while the walker it was
        // supposed to be stood wherever you had left them.
        if (session.local() != null) {
            WatchPlayer me = session.local().players().isEmpty()
                    ? session.local().join(1, "Walker")
                    : session.local().players().get(0);
            if (me != null) {
                session.setSelfId(me.id());
                px = me.x();
                py = me.y();
            }
        }
        session.update(0);

        long seed = session.view().seed();
        if (session.local() != null) seed = session.local().config().seed();
        streamer = new ChunkStreamer(seed);
        applyDistanceSettings();

        WatchView.Walker me = session.view().self();
        if (me != null) {
            px = me.x();
            py = me.y();
        }
        pz = streamer.groundAt(px, py);
        smoothedGround = pz;
        // Build the ground under our feet before the first frame. Otherwise the
        // walk opens on a camera standing at the right height over a world that
        // has not arrived yet — which reads exactly like being a giant hanging
        // in the air while the landscape assembles itself underneath.
        streamer.loadNow(px, py, 1);
        ctx.lighting().setDarkness(0);
        ctx.applyLiveSettings();
    }

    @Override
    public void onExit() {
        Pointer.restore();
        saveIfSolo();
    }

    /**
     * How far the world is generated, and how much of it is drawn finely.
     *
     * <p><b>Set from what the machine turns out to have.</b> The painter can
     * hold a few tens of thousands of triangles; a card can hold a hundred
     * times that. Rather than shipping one number that is wrong on both, the
     * renderer is asked after its first frame whether it found a mesh pass, and
     * the ring is sized accordingly. The player's own detail setting scales
     * whichever answer comes back.
     */
    private void applyDistanceSettings() {
        if (streamer == null) return;
        double scale = Math.max(0.5, Math.min(2.0,
                PlayerSettings.active().detailDistance / 24.0));
        boolean gpu = renderer.acceleratedByGpu();
        // The player's detail setting may shorten the software path's view and
        // may not lengthen it. Measured at 1280x720: six chunks is 45 ms a
        // frame and twelve is 79, and a walking game at twelve frames a second
        // is one where the world visibly assembles itself every time you turn
        // round. A card has no such trouble and gets the full range.
        int radius = (int) Math.round((gpu ? 16 : 6) * scale);
        streamer.setViewRadius(gpu ? radius : Math.min(6, radius));
        streamer.setDetailRadius(gpu ? 4 : 1);
        streamer.setGrassRadius(gpu ? 3 : 1);
        renderer.setFogRange(streamer.viewRadius() * WatchChunk.SIZE * 0.45,
                streamer.viewRadius() * WatchChunk.SIZE * 1.02);
    }

    // --- the tick --------------------------------------------------------------------

    @Override
    public void update(double dt, InputManager input) {
        if (session == null) {
            scenes.transitionTo(WatchLobbyScene.NAME);
            return;
        }
        if (!session.alive()) {
            // The host went away. Say so on the way out rather than freezing on
            // the last snapshot we happened to receive.
            leave();
            return;
        }

        if (panel != Panel.NONE) {
            Pointer.restore();
            updatePanel(dt, input);
            // The pause screen is where "Leave Walk" lives, and leaving closes
            // the session out from under the two calls below. This is the line
            // whose absence made L crash the game.
            if (session == null) return;
            session.update(dt);
            syncClock();
            return;
        }

        if (KeyBinds.pressed(input, GameAction.PAUSE)) {
            panel = Panel.PAUSED;
            return;
        }
        if (KeyBinds.pressed(input, GameAction.WATCH_GUIDE)) {
            openGuide();
            return;
        }
        if (KeyBinds.pressed(input, GameAction.WATCH_SATCHEL)) {
            panel = Panel.SATCHEL;
            satchelIndex = 0;
            recipeIndex = 0;
            return;
        }
        if (KeyBinds.pressed(input, GameAction.WATCH_BUILD)) {
            panel = Panel.BUILD;
            return;
        }
        if (KeyBinds.pressed(input, GameAction.TOGGLE_VIEW)) thirdPerson = !thirdPerson;

        steerLook(input);
        walk(dt, input);
        aim();
        act(input);
        // `act` can end the walk: L leaves, and leaving closes the session and
        // the streamer out from under the rest of this method. Without this
        // line every one of the calls below dereferences a null, which is
        // exactly what pressing L did.
        if (session == null || streamer == null) return;

        moveTimer += dt;
        if (moveTimer >= MOVE_INTERVAL) {
            moveTimer = 0;
            sendMove();
        }
        session.update(dt);
        syncClock();

        streamer.update(px, py, dt);
        saveTimer += dt;
        if (saveTimer >= AUTOSAVE_INTERVAL) {
            saveTimer = 0;
            saveIfSolo();
        }
    }

    /** Adopt the host's time of day, so a party shares one sunset. */
    private void syncClock() {
        WatchView view = view();
        if (session.online() && view != null && view.timeOfDay() > 0) {
            clock.adopt(view.timeOfDay());
        } else {
            clock.tick(0);
        }
    }

    /**
     * Mouse look, exactly as the world game does it: hide the pointer, pin it
     * to the middle of the window, and read the hand's own travel rather than
     * where the arrow ended up. See {@code PlayScene.steerLook} for why.
     */
    private void steerLook(InputManager input) {
        int w = Math.max(1, viewportWidth), h = Math.max(1, viewportHeight);
        Pointer.setVisible(false);
        Pointer.lockTo(input, w / 2, h / 2);
        input.consumeMouseMotion(lookMotion);
        double step = LOOK_STEP * PlayerSettings.active().lookSensitivity;
        double sign = PlayerSettings.active().invertLook ? 1 : -1;
        yaw += lookMotion[0] * step;
        pitch += lookMotion[1] * step * sign;
        pitch = Math.max(-EyeCamera.MAX_PITCH, Math.min(EyeCamera.MAX_PITCH, pitch));
        yaw = yaw % (Math.PI * 2);
    }

    /**
     * Walk.
     *
     * <p>No jumping and no fall damage: this is a game about looking at things,
     * and the movement it needs is the movement a person out for a walk has.
     * What it does have is <b>crouching</b>, because crouching is how you get
     * near a wary animal — see {@link WatchPlayer#stillness()} — and swimming,
     * because the lakes are where the fish are.
     */
    private void walk(double dt, InputManager input) {
        if (KeyBinds.pressed(input, GameAction.JUMP)) crouching = !crouching;

        double forward = 0, strafe = 0;
        if (KeyBinds.down(input, GameAction.MOVE_UP)) forward += 1;
        if (KeyBinds.down(input, GameAction.MOVE_DOWN)) forward -= 1;
        if (KeyBinds.down(input, GameAction.MOVE_RIGHT)) strafe += 1;
        if (KeyBinds.down(input, GameAction.MOVE_LEFT)) strafe -= 1;

        boolean running = KeyBinds.down(input, GameAction.SPRINT) && !crouching;
        double speed = crouching ? WatchPlayer.CROUCH_SPEED
                : running ? WatchPlayer.RUN_SPEED : WatchPlayer.WALK_SPEED;

        double length = Math.hypot(forward, strafe);
        if (length > 0) {
            forward /= length;
            strafe /= length;
            // Forward is where the eye is looking, flattened onto the ground —
            // walking is not affected by looking up at a bird.
            double fx = Math.sin(yaw), fy = -Math.cos(yaw);
            double rx = Math.cos(yaw), ry = Math.sin(yaw);
            px += (fx * forward + rx * strafe) * speed * dt;
            py += (fy * forward + ry * strafe) * speed * dt;
        }

        double ground = streamer.groundAt(px, py);
        boolean wading = ground < TerrainField.WATER_LEVEL;
        double target = wading ? TerrainField.WATER_LEVEL - 0.6 : ground;
        // Eased rather than snapped: on a two-metre grid the ground under a
        // walker changes by tens of centimetres a step, and a camera that
        // tracked it exactly would jolt with every one of them.
        smoothedGround += (target - smoothedGround) * Math.min(1, dt * STEP_SMOOTHING);
        pz = smoothedGround;
    }

    /**
     * What is under the crosshair.
     *
     * <p>Worked out locally <b>only to say so on screen</b>. The click itself
     * sends {@code 0} — "whatever you think I am looking at" — and the server
     * traces its own ray, because the client's idea of where an animal is is a
     * snapshot old and the server's is authoritative. Telling the player what
     * they are about to point at, on the other hand, has to happen at frame
     * rate or it is useless.
     *
     * <p>An angular test with a tolerance that widens with the animal's size,
     * matching {@code WatchGame.lookingAt} closely enough that the two agree in
     * every case a player would notice.
     */
    private void aim() {
        lookingAtId = 0;
        prompt = "";
        double cp = Math.cos(pitch);
        double dirX = Math.sin(yaw) * cp, dirY = -Math.cos(yaw) * cp, dirZ = Math.sin(pitch);
        double eyeZ = pz + (crouching ? 1.10 : 1.68);
        double best = Double.MAX_VALUE;
        WatchView.Creature found = null;
        for (WatchView.Creature creature : view().creatures()) {
            double dx = creature.x() - px, dy = creature.y() - py;
            double dz = creature.z() + creature.def().bodyLength() * 0.5 - eyeZ;
            double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (distance > WatchGame.SPOT_RANGE || distance < 0.01) continue;
            double dot = (dx * dirX + dy * dirY + dz * dirZ) / distance;
            if (dot <= 0) continue;
            double angle = Math.acos(Math.min(1, dot));
            double tolerance = Math.max(0.022,
                    Math.atan2(creature.def().bodyLength() * 1.6, distance));
            if (angle > tolerance) continue;
            double score = angle * 1000 + distance;
            if (score < best) {
                best = score;
                found = creature;
            }
        }
        if (found == null) return;
        lookingAtId = found.id();
        boolean known = view().guide().seen(found.def().key());
        prompt = known
                ? found.def().name() + "  —  click to point it out"
                : "Something new  —  click to record it";
    }

    /** The verbs. Every one of them is a request; none of them changes anything here. */
    private void act(InputManager input) {
        if (KeyBinds.pressed(input, GameAction.WATCH_SPOT)) request("spot");
        if (KeyBinds.pressed(input, GameAction.WATCH_PICK)) request("pick");
        if (KeyBinds.pressed(input, GameAction.INTERACT)) request("harvest");
        if (KeyBinds.pressed(input, GameAction.WATCH_PLANT)) request("plant");
        if (KeyBinds.pressed(input, GameAction.WATCH_CROSS)) request("cross");
        if (KeyBinds.pressed(input, GameAction.WATCH_FEEDER)) request("lure");
        if (KeyBinds.pressed(input, GameAction.WATCH_ROD)) request("rod");
    }

    /**
     * Send one verb.
     *
     * <p>Solo it goes straight into the local game; online it goes on the wire.
     * The two branches are next to each other on purpose — they are the same
     * list of verbs, and a verb that exists in one and not the other would be a
     * feature that works alone and not with friends.
     */
    private void request(String verb) {
        WatchGame local = session.local();
        int me = session.selfId();
        switch (verb) {
            case "spot" -> {
                if (local != null) local.spot(me, 0);
                else session.client().sendSpot(lookingAtId);
            }
            case "pick" -> {
                if (local != null) {
                    String got = local.pick(me);
                    if (got != null) say("Picked " + Forage.nameOf(got));
                } else {
                    session.client().sendAction("pick");
                }
            }
            case "harvest" -> {
                if (local != null) {
                    String got = local.harvest(me);
                    if (got != null) say("Harvested " + got);
                } else {
                    session.client().sendAction("harvest");
                }
            }
            case "plant" -> {
                String seed = firstOfKind(Forage.Kind.SEED);
                if (seed == null) {
                    say("Nothing to plant — pick some seed first");
                } else if (local != null) {
                    String line = local.plant(me, seed);
                    say(line != null ? line : "You need a trowel and dry ground");
                } else {
                    session.client().sendPlant(seed);
                }
            }
            case "cross" -> {
                if (local != null) {
                    var cross = local.pollinate(me);
                    if (cross == null) {
                        say("Needs two different mature trees, close together");
                    } else {
                        local.plantCross(me, cross);
                        say(cross.describe());
                    }
                } else {
                    session.client().sendAction("cross");
                }
            }
            case "lure" -> {
                String food = firstEdible();
                if (food == null) {
                    say("Nothing to put out — forage, or cook something");
                } else if (local != null) {
                    if (local.placeLure(me, food) == null) {
                        say("You need a feeder, and dry ground to stand it on");
                    }
                } else {
                    session.client().sendPlaceLure(food);
                }
            }
            case "rod" -> castOrStrike(local, me);
            default -> { }
        }
    }

    /** One key for the rod: cast when it is out of the water, strike when it is in. */
    private void castOrStrike(WatchGame local, int me) {
        if (local != null) {
            WatchPlayer player = local.player(me);
            if (player == null) return;
            if (player.rod().active()) {
                var fish = local.strike(me);
                say(fish != null ? "Landed a " + fish.name() : player.rod().hint());
            } else if (!local.castRod(me)) {
                say(player.satchel().has("rod")
                        ? "Face the water and try again"
                        : "You need a rod — two branches and a vine");
            }
            return;
        }
        session.client().sendAction("cast");
        session.client().sendAction("strike");
    }

    private String firstOfKind(Forage.Kind kind) {
        Satchel satchel = view().satchel();
        for (String key : satchel.keys()) {
            Forage.Item item = Forage.byKey(key);
            if (item != null && item.kind() == kind) return key;
        }
        return null;
    }

    private String firstEdible() {
        Satchel satchel = view().satchel();
        // Prepared food first: it is what a player made on purpose, and a
        // feeder should not quietly take their last berry instead.
        for (String key : satchel.keys()) {
            Forage.Item item = Forage.byKey(key);
            if (item != null && item.kind() == Forage.Kind.PREPARED) return key;
        }
        for (String key : satchel.keys()) {
            Forage.Item item = Forage.byKey(key);
            if (item != null && item.edible()) return key;
        }
        return null;
    }

    private void say(String line) {
        view().say(line);
    }

    private void sendMove() {
        if (session.online()) {
            session.client().sendMove(px, py, pz, yaw, pitch, crouching);
        } else if (session.local() != null) {
            session.local().move(session.selfId(), px, py, pz, yaw, pitch, crouching,
                    MOVE_INTERVAL);
        }
    }

    // --- panels ----------------------------------------------------------------------

    private void updatePanel(double dt, InputManager input) {
        if (KeyBinds.pressed(input, GameAction.MENU_BACK)
                || KeyBinds.pressed(input, GameAction.PAUSE)) {
            panel = Panel.NONE;
            return;
        }
        switch (panel) {
            case SATCHEL -> updateSatchel(input);
            case BUILD -> updateBuild(input);
            case PAUSED -> updatePaused(input);
            case NONE -> { }
        }
    }

    private void updateSatchel(InputManager input) {
        List<Recipes.Recipe> recipes = Recipes.all();
        if (KeyBinds.pressed(input, GameAction.MENU_DOWN)) recipeIndex++;
        if (KeyBinds.pressed(input, GameAction.MENU_UP)) recipeIndex--;
        recipeIndex = Math.floorMod(recipeIndex, Math.max(1, recipes.size()));
        if (KeyBinds.pressed(input, GameAction.MENU_SELECT) && !recipes.isEmpty()) {
            Recipes.Recipe recipe = recipes.get(recipeIndex);
            if (session.local() != null) {
                say(session.local().craft(session.selfId(), recipe, recipe.station())
                        ? "Made " + recipe.name()
                        : "Not enough for that — " + recipe.costLine());
            } else {
                session.client().sendCraft(recipe.output(), recipe.station().name());
            }
        }
        if (KeyBinds.pressed(input, GameAction.WATCH_SATCHEL)) panel = Panel.NONE;
    }

    private void updateBuild(InputManager input) {
        List<BuildPiece> pieces = BuildPiece.all();
        if (KeyBinds.pressed(input, GameAction.MENU_DOWN)) pieceIndex++;
        if (KeyBinds.pressed(input, GameAction.MENU_UP)) pieceIndex--;
        pieceIndex = Math.floorMod(pieceIndex, pieces.size());
        if (KeyBinds.pressed(input, GameAction.WATCH_TURN_PIECE)) {
            pieceTurn = (pieceTurn + 1) % Structure.TURNS;
        }
        if (KeyBinds.pressed(input, GameAction.MENU_RIGHT)
                || KeyBinds.pressed(input, GameAction.MENU_LEFT)) {
            pieceInTree = !pieceInTree;
        }
        if (KeyBinds.pressed(input, GameAction.MENU_SELECT)) {
            BuildPiece piece = pieces.get(pieceIndex);
            if (session.local() != null) {
                say(session.local().build(session.selfId(), piece, pieceTurn, pieceInTree)
                        != null
                        ? "Built a " + piece.displayName()
                        : "Cannot build there — " + piece.costLine());
            } else {
                session.client().sendBuild(piece.key(), pieceTurn, pieceInTree);
            }
        }
        if (KeyBinds.pressed(input, GameAction.WATCH_BUILD)) panel = Panel.NONE;
    }

    private void updatePaused(InputManager input) {
        if (KeyBinds.pressed(input, GameAction.WATCH_LEAVE)) leave();
    }

    private void openGuide() {
        if (scenes.get(WatchGuideScene.NAME) instanceof WatchGuideScene guide) {
            guide.show(view(), NAME);
            scenes.transitionTo(WatchGuideScene.NAME);
        }
    }

    private void leave() {
        saveIfSolo();
        closeSession();
        scenes.transitionTo(WatchLobbyScene.NAME);
    }

    private void saveIfSolo() {
        // Only the machine that owns the world writes it. A client saving what
        // it happened to have received would produce a world with one player's
        // satchel in it and somebody else's trees.
        if (store == null || session == null) return;
        WatchGame owned = session.local() != null ? session.local()
                : session.isHost() ? session.hostedServer().game() : null;
        if (owned == null) return;
        try {
            store.save(owned);
        } catch (RuntimeException e) {
            System.err.println("watch: could not save — " + e.getMessage());
        }
    }

    private void closeSession() {
        if (session != null) session.close();
        if (streamer != null) streamer.close();
        session = null;
        streamer = null;
    }

    // --- drawing ----------------------------------------------------------------------

    @Override
    public void render(DrawTarget target, float alpha) {
        if (session == null || streamer == null) {
            target.fillRect(0, 0, viewportWidth, viewportHeight, SceneChrome.BACKDROP);
            return;
        }
        frame++;
        placeCamera();

        WatchBiome biome = streamer.biomeAt(px, py);
        renderer.begin(target, eye, viewportWidth, viewportHeight, clock,
                biome.skyRgb(), biome.fogRgb());
        if (frame == 2) applyDistanceSettings();

        for (WatchChunk chunk : streamer.chunks()) {
            long key = chunk.key();
            renderer.submit(chunk.groundMesh(), key * 4);
            renderer.submit(chunk.floraMesh(), key * 4 + 1);
            renderer.submit(chunk.grassMesh(), key * 4 + 2);
            renderer.submit(chunk.waterMesh(), key * 4 + 3);
        }
        renderer.submit(buildDynamicMesh(), 1);
        renderer.flush(target);

        drawHud(target, biome);
        switch (panel) {
            case SATCHEL -> drawSatchel(target);
            case BUILD -> drawBuild(target);
            case PAUSED -> drawPaused(target);
            case NONE -> { }
        }
    }

    private void placeCamera() {
        eye.setViewport(viewportWidth, viewportHeight);
        double eyeHeight = crouching ? 1.10 : 1.68;
        if (thirdPerson) {
            // Behind and a little above, and pulled up out of the ground if the
            // slope behind is steeper than the camera arm.
            double bx = px - Math.sin(yaw) * THIRD_PERSON_BACK;
            double by = py + Math.cos(yaw) * THIRD_PERSON_BACK;
            double bz = Math.max(streamer.groundAt(bx, by) + 1.2,
                    pz + eyeHeight + 1.0);
            eye.place(bx, by, bz);
        } else {
            eye.place(px, py, pz + eyeHeight);
        }
        eye.look(yaw, pitch);
    }

    /**
     * Everything that moves, in one mesh, rebuilt every frame.
     *
     * <p>One mesh rather than one per animal: a draw call per creature is how a
     * clearing with thirty birds in it becomes thirty draw calls, and the whole
     * lot together is a few thousand triangles — less than one chunk of ground.
     * It is rebuilt from scratch each frame because everything in it has moved,
     * which is also why it carries the frame number as its revision.
     */
    private Mesh buildDynamicMesh() {
        WatchView view = view();
        Mesh.Builder mesh = Mesh.builder(px, py, 0, false, frame);
        float[] uv = new float[4];

        for (WatchView.Creature creature : view.creatures()) {
            AnimalModels.Loaded model = AnimalModels.of(creature.def());
            model.geometry().mesh(mesh, creature.def(), creature.x() - px,
                    creature.y() - py, creature.z(), creature.yaw(),
                    creature.state(), creature.phase(), 1, model.poses());
        }

        // The other walkers: a simple figure, because what matters about them
        // is where they are and which way they are looking.
        for (WatchView.Walker walker : view.others()) {
            drawWalker(mesh, walker, uv);
        }

        for (Lure lure : view.lures()) {
            WatchMaterials.uv(WatchMaterial.PLANK, uv);
            int post = WatchMaterials.shade(WatchMaterial.BARK);
            Shapes.prism(mesh, lure.x() - px, lure.y() - py, lure.z(), lure.z() + 1.1,
                    0.05, 0.05, 4, 0, uv, post, false);
            int tray = WatchMaterials.shade(lure.active()
                    ? WatchMaterial.PLANK : WatchMaterial.DARK_BARK);
            Shapes.box(mesh, lure.x() - px, lure.y() - py, lure.z() + 1.15,
                    0.26, 0.26, 0.05, 0, uv, tray);
            if (lure.active()) {
                WatchMaterials.uv(WatchMaterial.BERRY, uv);
                Shapes.blob(mesh, lure.x() - px, lure.y() - py, lure.z() + 1.24,
                        0.14, 0.14, 0.06, uv, WatchMaterials.shade(WatchMaterial.BERRY));
            }
        }

        for (Cultivation.Crop crop : view.crops().all()) {
            WatchMaterials.uv(WatchMaterial.LUSH_GRASS, uv);
            int green = WatchMaterials.shade(crop.ripe()
                    ? WatchMaterial.DRY_GRASS : WatchMaterial.LUSH_GRASS);
            for (int i = 0; i < 4; i++) {
                double a = i * Math.PI / 2 + 0.4;
                Shapes.blade(mesh, crop.x() - px + Math.cos(a) * 0.12,
                        crop.y() - py + Math.sin(a) * 0.12, crop.z(),
                        crop.height(), 0.07, a, 0, 0, uv, green);
            }
        }

        for (TreeInstance tree : view.grove().all()) {
            FloraMesher.tree(mesh, tree, px, py, true);
        }

        for (Structure.Placement piece : view.structure().all()) {
            WatchMaterials.uv(piece.piece().material(), uv);
            Shapes.box(mesh, piece.x() - px, piece.y() - py, piece.z(),
                    piece.piece().sizeX() / 2, piece.piece().sizeY() / 2,
                    piece.piece().sizeZ() / 2, piece.yaw(), uv,
                    WatchMaterials.shade(piece.piece().material()));
        }

        return mesh.build();
    }

    /** Another player: a body, a head, and a hat brim so they read at a distance. */
    private void drawWalker(Mesh.Builder mesh, WatchView.Walker walker, float[] uv) {
        double x = walker.x() - px, y = walker.y() - py;
        double height = walker.crouching() ? 1.15 : 1.75;
        WatchMaterials.uv(WatchMaterial.PLANK, uv);
        int coat = WatchMaterials.shade(WatchMaterial.MOSS);
        int skin = WatchMaterials.shade(WatchMaterial.CLAY);
        int hat = WatchMaterials.shade(WatchMaterial.DRY_GRASS);
        Shapes.box(mesh, x, y, walker.z() + height * 0.42, 0.16, 0.24, height * 0.34,
                walker.yaw(), uv, coat);
        Shapes.box(mesh, x, y, walker.z() + height * 0.86, 0.13, 0.13, 0.13,
                walker.yaw(), uv, skin);
        Shapes.box(mesh, x, y, walker.z() + height * 0.99, 0.30, 0.30, 0.02,
                walker.yaw(), uv, hat);
        for (int side = -1; side <= 1; side += 2) {
            Shapes.box(mesh, x - Math.sin(walker.yaw()) * 0.02 + Math.cos(walker.yaw())
                            * side * 0.09,
                    y + Math.sin(walker.yaw()) * side * 0.09, walker.z() + height * 0.14,
                    0.06, 0.06, height * 0.16, walker.yaw(), uv, coat);
        }
    }

    // --- the HUD ----------------------------------------------------------------------

    private void drawHud(DrawTarget target, WatchBiome biome) {
        WatchView view = view();
        int pad = 16;

        // Crosshair, and whatever is under it.
        if (panel == Panel.NONE) {
            int cx = viewportWidth / 2, cy = viewportHeight / 2;
            target.fillRect(cx - 6, cy - 1, 12, 2, new Color(255, 255, 255, 120));
            target.fillRect(cx - 1, cy - 6, 2, 12, new Color(255, 255, 255, 120));
        }

        // Top left: where and when.
        String place = biome.displayName();
        String time = WatchClock.localTimeOf(clock.timeOfDay()).withSecond(0).withNano(0)
                + " · " + clock.phase().label();
        label(target, place, pad, pad + 18, HUD_BOLD, HUD_INK);
        // Full ink, not dim: the clock is what tells you which animals are out,
        // and it spends its life over a sky that is a different colour every
        // hour of the day.
        label(target, time, pad, pad + 38, HUD_FONT, HUD_INK);
        int points = view.guide().points();
        label(target, view.guide().discovered() + " / " + view.guide().total()
                        + " species · " + points + (points == 1 ? " pt" : " pts"),
                pad, pad + 56, HUD_SMALL, HUD_ACCENT);

        // Top right: the party.
        int right = viewportWidth - pad;
        int row = pad + 18;
        for (WatchView.Walker walker : view.walkers()) {
            String label = walker.name()
                    + (walker.id() == view.selfId() ? " (you)" : "");
            label(target, label, right - target.textWidth(label, HUD_FONT), row,
                    HUD_FONT, walker.id() == view.selfId() ? HUD_ACCENT : HUD_DIM);
            row += 18;
        }

        // Stillness, which is the stat that decides whether anything lets you
        // near it. Drawn as a bar because a number would be read once and
        // ignored, and this has to be glanceable while creeping.
        WatchPlayer me = session.local() == null ? null
                : session.local().player(session.selfId());
        double stillness = me != null ? me.stillness()
                : view.self() != null ? view.self().stillness() : 1;
        int barW = 150, barH = 6;
        int barX = viewportWidth / 2 - barW / 2, barY = viewportHeight - 54;
        target.fillRect(barX, barY, barW, barH, new Color(0, 0, 0, 140));
        target.fillRect(barX, barY, (int) (barW * stillness), barH,
                stillness > 0.7 ? HUD_ACCENT : HUD_WARN);
        String hint = crouching ? "Crouched — stay still and they will come back"
                : "Stillness";
        label(target, hint, viewportHeight > 0
                ? viewportWidth / 2 - target.textWidth(hint, HUD_SMALL) / 2 : 0,
                barY - 6, HUD_SMALL, HUD_DIM);

        // Bottom left: the satchel, briefly.
        drawSatchelStrip(target, view.satchel(), pad, viewportHeight - pad);

        // Bottom right: the last few things that happened.
        int logY = viewportHeight - pad - 4;
        List<String> log = view.log();
        for (int i = log.size() - 1; i >= 0 && i > log.size() - 6; i--) {
            String line = log.get(i);
            label(target, line, right - target.textWidth(line, HUD_SMALL), logY,
                    HUD_SMALL, HUD_DIM);
            logY -= 16;
        }

        drawSpotlights(target, view);
        drawRod(target, me);

        if (!prompt.isEmpty()) {
            label(target, prompt,
                    viewportWidth / 2 - target.textWidth(prompt, HUD_FONT) / 2,
                    viewportHeight / 2 + 42, HUD_FONT, HUD_INK);
        }
        if (panel == Panel.NONE) {
            String keys = "E pick · F feeder · R plant · C cross · V rod · B build "
                    + "· Tab satchel · G guide";
            label(target, keys, pad, viewportHeight - pad - 22, HUD_SMALL,
                    new Color(150, 168, 152));
        }
    }

    /**
     * HUD text drawn <b>over the world</b>, with a shadow under it.
     *
     * <p>Everything in this HUD sits on whatever the player happens to be
     * looking at, and what they are looking at is not a colour this scene
     * chooses — it is a sky that runs from near-white at noon to near-black at
     * midnight, and a hillside that can be any of twenty biomes' greens, sands
     * and snows. A single ink colour cannot be legible against all of that:
     * measured on a pale dawn sky, the clock line and the guide's progress were
     * both washed out to the point of being unreadable, which is a bad way to
     * lose the one line that says what time the animals think it is.
     *
     * <p>A one-pixel shadow costs a second string and fixes every case, because
     * a light glyph with a dark edge reads on anything. The panels do not use
     * this: they lay their own dark card down first and have a background they
     * control.
     */
    private static void label(DrawTarget target, String text, int x, int y, Font font,
                              Color colour) {
        target.drawText(text, x + 1, y + 1, font, SHADOW);
        target.drawText(text, x, y, font, colour);
    }

    /** Under every line of HUD text; dark and soft rather than a hard outline. */
    private static final Color SHADOW = new Color(0, 0, 0, 150);

    private void drawSatchelStrip(DrawTarget target, Satchel satchel, int x, int baseline) {
        if (satchel.kinds() == 0) return;
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (String key : satchel.keys()) {
            if (shown++ >= 6) break;
            if (sb.length() > 0) sb.append("   ");
            sb.append(satchel.count(key)).append("× ").append(Forage.nameOf(key));
        }
        if (satchel.kinds() > 6) sb.append("   +").append(satchel.kinds() - 6).append(" more");
        label(target, sb.toString(), x, baseline, HUD_SMALL, HUD_DIM);
    }

    /**
     * The shared outline: a ring on the animal somebody pointed at, with their
     * name on it.
     *
     * <p>Projected each frame from the animal's <em>current</em> position when
     * it is still in view, and from where it was when the call went out when it
     * is not — a bird that flushed the instant it was spotted should still be
     * pointed at, because the point of the gesture is telling somebody where to
     * look.
     */
    private void drawSpotlights(DrawTarget target, WatchView view) {
        double[] point = new double[3];
        for (Spotlight light : view.spotlights()) {
            WatchView.Creature creature = view.creature(light.animalId());
            double x = creature != null ? creature.x() : light.x();
            double y = creature != null ? creature.y() : light.y();
            double z = creature != null ? creature.z() : light.z();
            double size = creature != null ? creature.def().bodyLength() : 0.4;
            if (!eye.project(x, y, z + size * 0.6, point)) continue;
            double depth = point[2];
            int radius = (int) Math.max(14, eye.scaleAt(depth) * size * 1.6);
            int alpha = (int) (200 * light.intensity());
            Color ring = light.discovery()
                    ? new Color(255, 226, 130, alpha)
                    : new Color(150, 230, 170, alpha);
            target.drawOval((int) point[0] - radius, (int) point[1] - radius,
                    radius * 2, radius * 2, ring, 2.5f);
            String label = light.label();
            label(target, label, (int) point[0] - target.textWidth(label, HUD_SMALL) / 2,
                    (int) point[1] - radius - 8, HUD_SMALL, ring);
        }
    }

    private void drawRod(DrawTarget target, WatchPlayer me) {
        if (me == null || me.rod().stage() == Fishing.Stage.IDLE) return;
        String hint = me.rod().hint();
        if (hint.isEmpty()) return;
        Color colour = me.rod().stage() == Fishing.Stage.BITE ? HUD_WARN : HUD_INK;
        label(target, hint,
                viewportWidth / 2 - target.textWidth(hint, HUD_BOLD) / 2,
                viewportHeight / 2 - 40, HUD_BOLD, colour);
    }

    // --- overlays ----------------------------------------------------------------------

    private void drawSatchel(DrawTarget target) {
        int w = Math.min(760, viewportWidth - 80);
        int h = Math.min(460, viewportHeight - 80);
        int x = (viewportWidth - w) / 2, y = (viewportHeight - h) / 2;
        target.fillRect(x, y, w, h, HUD_PANEL);
        target.drawRect(x, y, w, h, HUD_ACCENT);
        target.drawText("Satchel & Cooking", x + 20, y + 32, TITLE_FONT, HUD_INK);

        Satchel satchel = view().satchel();
        int col = x + 20, row = y + 62;
        target.drawText("Carrying", col, row, HUD_BOLD, HUD_ACCENT);
        row += 22;
        for (String key : satchel.keys()) {
            if (row > y + h - 30) break;
            Forage.Item item = Forage.byKey(key);
            String line = satchel.count(key) + "×  " + Forage.nameOf(key);
            target.drawText(line, col, row, HUD_FONT, HUD_INK);
            if (item != null) {
                target.drawText(item.kind().label(), col + 210, row, HUD_SMALL, HUD_DIM);
            }
            row += 18;
        }

        int rx = x + w / 2 + 10;
        row = y + 62;
        target.drawText("Recipes", rx, row, HUD_BOLD, HUD_ACCENT);
        row += 22;
        List<Recipes.Recipe> recipes = Recipes.all();
        int first = Math.max(0, Math.min(recipeIndex - 6, recipes.size() - 14));
        for (int i = first; i < recipes.size() && row < y + h - 30; i++) {
            Recipes.Recipe recipe = recipes.get(i);
            boolean can = recipe.affordable(satchel);
            boolean here = i == recipeIndex;
            if (here) {
                target.fillRect(rx - 6, row - 13, w / 2 - 24, 18,
                        new Color(60, 110, 70, 160));
            }
            target.drawText(recipe.name(), rx, row, HUD_FONT,
                    can ? HUD_INK : new Color(130, 140, 132));
            target.drawText(recipe.costLine(), rx + 160, row, HUD_SMALL,
                    can ? HUD_DIM : new Color(120, 110, 100));
            row += 18;
        }
        if (!recipes.isEmpty()) {
            Recipes.Recipe recipe = recipes.get(recipeIndex);
            target.drawText(recipe.station().label() + " · " + recipe.note(),
                    x + 20, y + h - 16, HUD_SMALL, HUD_DIM);
        }
        target.drawText("↑↓ choose · Enter make · Tab or Esc close",
                x + w - 300, y + 32, HUD_SMALL, HUD_DIM);
    }

    private void drawBuild(DrawTarget target) {
        int w = Math.min(560, viewportWidth - 80);
        int h = Math.min(420, viewportHeight - 80);
        int x = (viewportWidth - w) / 2, y = (viewportHeight - h) / 2;
        target.fillRect(x, y, w, h, HUD_PANEL);
        target.drawRect(x, y, w, h, HUD_ACCENT);
        target.drawText("Build", x + 20, y + 32, TITLE_FONT, HUD_INK);

        Satchel satchel = view().satchel();
        int row = y + 62;
        List<BuildPiece> pieces = BuildPiece.all();
        for (int i = 0; i < pieces.size(); i++) {
            BuildPiece piece = pieces.get(i);
            boolean can = piece.affordable(satchel);
            if (i == pieceIndex) {
                target.fillRect(x + 14, row - 13, w - 28, 18, new Color(60, 110, 70, 160));
            }
            target.drawText(piece.displayName(), x + 20, row, HUD_FONT,
                    can ? HUD_INK : new Color(130, 140, 132));
            target.drawText(piece.costLine(), x + 190, row, HUD_SMALL,
                    can ? HUD_DIM : new Color(120, 110, 100));
            row += 20;
        }
        BuildPiece chosen = pieces.get(pieceIndex);
        target.drawText(chosen.note(), x + 20, y + h - 44, HUD_SMALL, HUD_DIM);
        String facing = "Facing " + (pieceTurn * 45) + "°"
                + (pieceInTree ? " · fixed to the nearest trunk" : " · on the ground");
        target.drawText(facing, x + 20, y + h - 26, HUD_SMALL,
                pieceInTree && !chosen.anchors() ? HUD_WARN : HUD_ACCENT);
        target.drawText("↑↓ choose · X turn · ←→ ground/tree · Enter build · B close",
                x + w - 400, y + 32, HUD_SMALL, HUD_DIM);
    }

    private void drawPaused(DrawTarget target) {
        int w = Math.min(460, viewportWidth - 80);
        int h = 220;
        int x = (viewportWidth - w) / 2, y = (viewportHeight - h) / 2;
        target.fillRect(0, 0, viewportWidth, viewportHeight, new Color(0, 0, 0, 120));
        target.fillRect(x, y, w, h, HUD_PANEL);
        target.drawRect(x, y, w, h, HUD_ACCENT);
        target.drawText("Paused", x + 20, y + 34, TITLE_FONT, HUD_INK);
        WatchView view = view();
        target.drawText(view.worldName() + " · seed " + view.seed(),
                x + 20, y + 62, HUD_SMALL, HUD_DIM);
        target.drawText(view.guide().discovered() + " of " + view.guide().total()
                        + " species found", x + 20, y + 86, HUD_FONT, HUD_ACCENT);
        target.drawText(session.online()
                        ? (session.isHost() ? "Hosting · " : "Joined · ")
                                + view.walkers().size() + " walking"
                        : "Walking alone",
                x + 20, y + 110, HUD_FONT, HUD_DIM);
        target.drawText("Esc — carry on", x + 20, y + 148, HUD_FONT, HUD_INK);
        target.drawText("G — field guide", x + 20, y + 170, HUD_FONT, HUD_INK);
        target.drawText("L — leave the walk", x + 20, y + 192, HUD_FONT, HUD_WARN);
    }

    /** Whatever the local player is looking at, for tests and the debug overlay. */
    public long lookingAtId() { return lookingAtId; }

    /** The camera, so a test can check where it ended up. */
    public EyeCamera camera() { return eye; }

    /** The renderer, so a test can read its counters. */
    public WatchRenderer renderer() { return renderer; }

    /** Where the local player is standing. */
    public double[] position() { return new double[]{px, py, pz}; }
}
