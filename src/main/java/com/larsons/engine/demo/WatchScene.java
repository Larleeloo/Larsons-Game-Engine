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
import com.larsons.engine.watch.Boats;
import com.larsons.engine.watch.Cultivation;
import com.larsons.engine.watch.Debug;
import com.larsons.engine.watch.Fishing;
import com.larsons.engine.watch.Forage;
import com.larsons.engine.watch.Litter;
import com.larsons.engine.watch.Lure;
import com.larsons.engine.watch.Recipes;
import com.larsons.engine.watch.Satchel;
import com.larsons.engine.watch.Spotlight;
import com.larsons.engine.watch.Spyglass;
import com.larsons.engine.watch.WatchClock;
import com.larsons.engine.watch.WatchGame;
import com.larsons.engine.watch.WatchPlayer;
import com.larsons.engine.watch.WatchStore;
import com.larsons.engine.watch.WatchView;
import com.larsons.engine.watch.Weather;
import com.larsons.engine.watch.build.BuildPiece;
import com.larsons.engine.watch.build.Structure;
import com.larsons.engine.watch.life.AnimalModels;
import com.larsons.engine.watch.net.WatchSession;
import com.larsons.engine.watch.render.BoatModel;
import com.larsons.engine.watch.render.FloraMesher;
import com.larsons.engine.watch.render.ItemModel;
import com.larsons.engine.watch.render.ItemPortrait;
import com.larsons.engine.watch.render.Mesh;
import com.larsons.engine.watch.render.Shapes;
import com.larsons.engine.watch.render.WalkerModel;
import com.larsons.engine.watch.render.WatchRenderer;
import com.larsons.engine.watch.world.ChunkStreamer;
import com.larsons.engine.watch.world.Flora;
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
import java.util.Map;

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

    /** How fast a swimmer rises or sinks, in metres per second. */
    private static final double DIVE_SPEED = 2.2;

    /** How fast a swimmer moves horizontally, in metres per second. */
    private static final double SWIM_SPEED = 2.4;

    /**
     * How far under the surface a floating swimmer's feet sit, in metres.
     *
     * <p>Chest-deep: {@link WalkerModel#HEIGHT} is 1.78 and the eye is at 1.68,
     * so a swimmer whose feet are 1.2 below the waterline has their head above
     * it, which is what "swimming" has to look like before diving means
     * anything.
     */
    private static final double FLOAT_DEPTH = 1.2;

    /** How far a player's eyes have to be under water to count as submerged. */
    private static final double SUBMERGED_MARGIN = 0.05;

    /** How much slower everything is under water. */
    private static final double UNDERWATER_DRAG = 0.55;

    /** How far the fog closes in under water, as a share of the surface range. */
    private static final double UNDERWATER_VISIBILITY = 0.16;

    /** How often the client tells the host where it is, in seconds. */
    private static final double MOVE_INTERVAL = 1 / 20.0;

    /** How often a solo walk is written to disk, in seconds. */
    private static final double AUTOSAVE_INTERVAL = 45;

    /**
     * How far off a thing lying on the ground is still drawn, in metres — on a
     * card, and on a machine drawing through Java2D.
     *
     * <p>These are ten-centimetre objects that cost around a hundred triangles
     * each: at eighty metres one is a single pixel and costs the same hundred
     * it costs at two. Forty metres is about fifty of them, which is five
     * thousand triangles — nothing on a card and a tenth of the painter's whole
     * frame, so the painter gets a shorter ring for the same reason its chunk
     * ring is six and not sixteen. See {@link #applyDistanceSettings}.
     */
    private static final double LITTER_RANGE_GPU = 40;

    private static final double LITTER_RANGE_PAINTER = 22;

    /** How far the player walks before the litter sweep is redone, in metres. */
    private static final double LITTER_RESTEP = 4;

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

    /**
     * The buffer keys for the two meshes that are not a chunk.
     *
     * <p><b>Out at the bottom of the range, and not {@code 1} and {@code 2}.</b>
     * A chunk's four meshes are keyed {@code chunkKey * 4 + n}, and
     * {@code WatchChunk.key(0, 0)} is zero — so the chunk the world origin
     * falls in owns keys 0 through 3, and the small numbers these two started
     * with collided with its flora and its grass. Two meshes sharing one buffer
     * re-upload over the top of each other every frame, which is the exact cost
     * the buffer cache exists to avoid, on the one chunk every player starts
     * standing in.
     */
    private static final long DYNAMIC_KEY = Long.MIN_VALUE;

    private static final long VIEW_MODEL_KEY = Long.MIN_VALUE + 1;

    /** Which overlay is up, if any. */
    private enum Panel { NONE, SATCHEL, BUILD, PAUSED }

    private final GameContext ctx;

    private WatchSession session;
    private WatchStore store;
    private ChunkStreamer streamer;

    /**
     * What is lying on the ground, and the ground it is lying on.
     *
     * <p>The scene's own copy, built from the same seed the streamer is: a
     * piece of litter is a function of its position, so the drawing side works
     * out where the pieces are for itself and the host is only ever asked which
     * of them have already been taken. That is the same arrangement the trees
     * and the boats use, and it is why a party of eight can walk through a wood
     * full of fallen branches without a single one of them going on the wire.
     */
    private Litter litter;

    private Flora.Ground litterGround;

    /** Whichever of the two litter ranges the backend turned out to afford. */
    private double litterRange = LITTER_RANGE_PAINTER;

    /**
     * The pieces near enough to draw, and where the player was when they were
     * last worked out.
     *
     * <p><b>Not recomputed per frame.</b> Sweeping forty metres of ground is a
     * few hundred cells, and each cell costs a height, a slope and a surface
     * off the generator — three or four thousand noise evaluations, which is a
     * whole millisecond of a painter's frame budget to answer a question whose
     * answer only changes when you walk. So it is redone every
     * {@link #LITTER_RESTEP} metres of travel, which at walking pace is about
     * twice a second, and the list is drawn from in between.
     */
    private final List<Litter.Piece> litterNearby = new ArrayList<>();

    private double litterFromX = Double.NaN, litterFromY;

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

    /**
     * The glass, and where it is actually pointing.
     *
     * <p>{@link #yaw} and {@link #pitch} are where the player has aimed;
     * {@code aimYaw} and {@code aimPitch} are that plus the tremor in their
     * hands, and they are what the camera looks along, what the crosshair
     * means, and what goes to the host. Keeping them apart is what lets
     * walking still be walking while the view wanders — see
     * {@link Spyglass#swayYaw}.
     */
    private final Spyglass glass = new Spyglass();

    private double aimYaw, aimPitch;

    /** The power last sent to the host, so a steady glass is not re-announced. */
    private double sentGlassPower = Spyglass.NONE;

    /**
     * The last few number keys, waiting to spell {@link Debug#CODE}.
     *
     * <p>Read straight off the keyboard rather than through {@link KeyBinds},
     * and that is the point: a cheat code is not a control. Putting ten digits
     * on the controls screen would be putting the answer on it too.
     */
    private final Debug.Pad pad = new Debug.Pad();

    /**
     * How far the player's feet are below the waterline, in metres.
     *
     * <p>Zero on land and at the surface; up to the depth of the water when
     * diving. This is the whole of the swimming state, and keeping it as a
     * depth rather than as an absolute height is what makes it survive walking
     * out of a lake onto a slope.
     */
    private double dive;

    /** Whether the eyes are under the surface this frame. */
    private boolean submerged;

    /** How much air is left locally, so the meter is smooth between snapshots. */
    private double breath = 1;

    /** The boat being rowed, or {@code 0}. */
    private long boatId;

    /** The gait clock, in turns — what drives the walk cycle and the head bob. */
    private double gait;

    /** How fast the local player moved on the last frame, in metres per second. */
    private double lastSpeed;

    /** How far through a reach-out gesture the hands are, {@code 0}–{@code 1}. */
    private double reach;

    private Panel panel = Panel.NONE;
    private int satchelIndex;
    private int satchelScroll;
    private int recipeIndex;
    private int recipeScroll;
    private int pieceIndex;
    private int pieceTurn;
    private boolean pieceInTree;

    /** Which of a panel's two columns the keys are driving. */
    private boolean recipeColumn;

    /**
     * Where the pointer was last frame, so a panel can tell hovering from
     * resting. See {@link #pointerMoved}.
     */
    private int pointerX = -1, pointerY = -1;

    /** Which scrollbar is being dragged: {@code 0} none, {@code 1} left, {@code 2} right. */
    private int dragBar;

    private double moveTimer;
    private double saveTimer;
    private int frame;
    private String prompt = "";
    private long lookingAtId;

    /** What is in reach, worked out every frame so it can be highlighted. */
    private WatchGame.Pickable inReach;

    /** How long ago something was picked up, for the flash on the HUD. */
    private double pickedFlash;
    private String pickedName = "";

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

        // Solo: join our own game. Joining is what resumes a save — a walker a
        // save left behind is woken by whoever arrives (WatchGame.wake), so
        // this both starts a fresh world and picks up an old one. Hosting or
        // joining, the server did it when the socket opened and tells us who we
        // are in the welcome.
        if (session.local() != null && session.local().players().isEmpty()) {
            WatchPlayer me = session.local().join(1, "Walker");
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
        // The same seed and the same generator the host used, so the branch
        // this side draws is the branch that side lets you pick up.
        litter = new Litter(seed, streamer.field());
        litterGround = Flora.ground(streamer.field());
        litterNearby.clear();
        litterFromX = Double.NaN;
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
        litterRange = gpu ? LITTER_RANGE_GPU : LITTER_RANGE_PAINTER;
        litterFromX = Double.NaN;
        streamer.setViewRadius(gpu ? radius : Math.min(6, radius));
        streamer.setDetailRadius(gpu ? 4 : 1);
        streamer.setGrassRadius(gpu ? 3 : 1);
        // The fog range is not set here: it is set every frame by
        // `applyVisibility`, which starts from this ring and brings it in for
        // the weather and the water. Setting it here as well would only mean a
        // frame of the wrong number after every settings change.
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

        // Before the panel branch, so the code can be typed anywhere in the
        // walk — including on the satchel screen, which is exactly where
        // somebody testing a recipe is standing when they want it.
        readCode(dt, input);

        if (panel != Panel.NONE) {
            Pointer.restore();
            // A panel is worked with the pointer, and the walk steers with the
            // pointer's *motion*. Without this the travel spent picking a
            // recipe piles up unread and is spent all at once on the frame the
            // panel closes, which throws the camera halfway round the world.
            input.discardMouseMotion();
            // A panel puts the glass away: you cannot read a recipe through a
            // telescope, and coming back to a screen still zoomed to ×15 with
            // no memory of having raised it is disorienting.
            glass.tick(dt, false, 1);
            applySway();
            announceGlass();
            aimGlass();
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
            satchelScroll = 0;
            recipeIndex = 0;
            recipeScroll = 0;
            dragBar = 0;
            // Opens on what you are carrying, not on what you could cook: the
            // question "what have I got" is asked ten times as often.
            recipeColumn = false;
            return;
        }
        if (KeyBinds.pressed(input, GameAction.WATCH_BUILD)) {
            panel = Panel.BUILD;
            return;
        }
        if (KeyBinds.pressed(input, GameAction.TOGGLE_VIEW)) thirdPerson = !thirdPerson;

        useGlass(dt, input);
        steerLook(input);
        walk(dt, input);
        // The reach gesture and the pickup flash decay on their own clock,
        // which is the frame's rather than the simulation's: they are things
        // the screen does, not things the world does.
        reach = Math.max(0, reach - dt * 3.2);
        pickedFlash = Math.max(0, pickedFlash - dt);
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
        noticePickups();

        // Where the glass is pointed decides which chunks this call asks for,
        // so it is set first.
        aimGlass();
        streamer.update(px, py, dt);
        sweepLitter();
        saveTimer += dt;
        if (saveTimer >= AUTOSAVE_INTERVAL) {
            saveTimer = 0;
            saveIfSolo();
        }
    }

    /**
     * Work out what is lying on the ground around us, when we have moved far
     * enough for the answer to have changed. See {@link #litterNearby}.
     */
    private void sweepLitter() {
        if (litter == null) return;
        if (!Double.isNaN(litterFromX)
                && Math.hypot(px - litterFromX, py - litterFromY) < LITTER_RESTEP) {
            return;
        }
        litterFromX = px;
        litterFromY = py;
        litterNearby.clear();
        // A margin of one restep, so a piece that comes into range between two
        // sweeps was already in the list rather than popping into existence.
        litterNearby.addAll(litter.near(litterGround, px, py,
                litterRange + LITTER_RESTEP));
    }

    /**
     * The nearest thing on the floor within arm's reach that nobody has taken.
     *
     * <p>What the highlight rings and what E picks up — worked out from the
     * same swept list the drawing uses, so the ring can never be round a piece
     * that is not being drawn.
     */
    private Litter.Piece litterInReach() {
        Litter.Piece best = null;
        double bestDistance = WatchGame.REACH * WatchGame.REACH;
        for (Litter.Piece piece : litterNearby) {
            if (view().litterTaken(piece.id())) continue;
            double dx = piece.x() - px, dy = piece.y() - py;
            double d = dx * dx + dy * dy;
            if (d < bestDistance) {
                bestDistance = d;
                best = piece;
            }
        }
        return best;
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
     *
     * <p>The one addition is {@link Spyglass#lookScale()}: turning is slowed by
     * the magnification, so a hand movement sweeps the same distance
     * <em>across the eyepiece</em> at every power. Without it a flick at ×15
     * throws the view fifteen screens and the glass is unusable at exactly the
     * power it exists for.
     */
    private void steerLook(InputManager input) {
        int w = Math.max(1, viewportWidth), h = Math.max(1, viewportHeight);
        Pointer.setVisible(false);
        Pointer.lockTo(input, w / 2, h / 2);
        input.consumeMouseMotion(lookMotion);
        double step = LOOK_STEP * PlayerSettings.active().lookSensitivity
                * glass.lookScale();
        double sign = PlayerSettings.active().invertLook ? 1 : -1;
        yaw += lookMotion[0] * step;
        pitch += lookMotion[1] * step * sign;
        pitch = Math.max(-EyeCamera.MAX_PITCH, Math.min(EyeCamera.MAX_PITCH, pitch));
        yaw = yaw % (Math.PI * 2);

        applySway();
    }

    /**
     * The aim: the heading plus whatever the hands are doing.
     *
     * <p>Worked out after the mouse has been read, so that the camera, the
     * crosshair and the host all use this frame's number rather than last
     * frame's. The tremor goes on the aim and <em>not</em> on the heading —
     * walking is still walking, and only the view wanders.
     */
    private void applySway() {
        aimYaw = yaw + glass.swayYaw(EyeCamera.DEFAULT_FOV);
        aimPitch = Math.max(-EyeCamera.MAX_PITCH, Math.min(EyeCamera.MAX_PITCH,
                pitch + glass.swayPitch(EyeCamera.DEFAULT_FOV)));
    }

    /**
     * Raise, lower and focus the glass, and tell the host when any of that
     * changed.
     *
     * <p><b>Held rather than toggled</b>, because that is what raising
     * something to your eye is. The wheel changes the stop while it is up —
     * three pulls of a draw tube, wrapping — which is the only control in this
     * game on the wheel and so cannot be mistaken for anything else.
     *
     * <p>Carrying one is checked here rather than in {@link Spyglass}: the
     * satchel belongs to the view, and the optic has no business reading it.
     * The host checks it again, because the host checks everything.
     */
    private void useGlass(double dt, InputManager input) {
        boolean carried = view().satchel().has(Spyglass.ITEM);
        boolean wanted = carried && KeyBinds.down(input, GameAction.WATCH_SPYGLASS);
        if (wanted) {
            // AWT's wheel is positive toward the user; scrolling away — the
            // gesture everybody makes for "closer" — pulls the tube out.
            int notches = input.getWheelRotation();
            if (notches != 0) glass.nudge(-notches);
        }
        glass.tick(dt, wanted, stillness());
        announceGlass();
    }

    /**
     * Tell the host the glass moved, if it did.
     *
     * <p>Only on a change, and only past a threshold, so a tube travelling
     * between two stops does not put forty messages on the wire — but every
     * change does have to go, because the host is what decides how far this
     * player can record something and what stays alive out there to be
     * recorded.
     */
    private void announceGlass() {
        double power = glass.power();
        if (Math.abs(power - sentGlassPower) <= 0.05
                && (power == Spyglass.NONE) == (sentGlassPower == Spyglass.NONE)) {
            return;
        }
        sentGlassPower = power;
        if (session.local() != null) {
            session.local().glass(session.selfId(), power);
        } else if (session.client() != null) {
            session.client().sendGlass(power);
        }
    }

    /**
     * Point the streamer's detail down the glass.
     *
     * <p>The cone is the camera's own frustum, widened a little so a chunk at
     * its edge is built before it swings into view, and its reach is what the
     * glass claims it can see — bounded by what the backend can hold. A card
     * can carry a kilometre of full-detail ground in a ten-degree wedge; the
     * painter cannot, and gets a shorter one for the same reason its ordinary
     * ring is six chunks and not sixteen.
     */
    private void aimGlass() {
        if (streamer == null) return;
        if (!glass.up()) {
            streamer.setFocus(null);
            return;
        }
        double fov = glass.fov(EyeCamera.DEFAULT_FOV);
        // The horizontal half angle, from the vertical one and the window's
        // shape, plus a margin for turning.
        double aspect = viewportHeight <= 0 ? 1.6 : viewportWidth / (double) viewportHeight;
        double half = Math.atan(Math.tan(fov / 2) * Math.max(1, aspect)) * 1.5 + 0.05;
        int reach = (int) Math.ceil(glass.range() / WatchChunk.SIZE);
        reach = Math.min(reach, renderer.acceleratedByGpu() ? 30 : 10);
        streamer.setFocus(ChunkStreamer.Focus.looking(px, py, aimYaw, reach, half,
                glass.power()));
    }

    /**
     * Watch the number keys for {@link Debug#CODE}.
     *
     * <p>Both the number row and the keypad, because a code you can only type
     * on one of them is a code that does not work on half the keyboards people
     * have. Nothing is drawn while it is half typed — a code with a progress
     * bar is not a code — and the answer, when it comes, comes from the host:
     * this only sends the digits.
     */
    private void readCode(double dt, InputManager input) {
        pad.tick(dt);
        for (int digit = 0; digit <= 9; digit++) {
            boolean pressed = input.isKeyJustPressed(java.awt.event.KeyEvent.VK_0 + digit)
                    || input.isKeyJustPressed(java.awt.event.KeyEvent.VK_NUMPAD0 + digit);
            if (!pressed || !pad.type(digit)) continue;
            if (session.local() != null) {
                // A solo game has no sink for its own chat, so the line and the
                // flash are raised here — and this is the one moment in the
                // game where a player has to be certain something happened.
                boolean on = session.local().debug(session.selfId(), Debug.CODE);
                picked(on ? "Debug mode ON — everything is unlimited"
                        : "Debug mode off");
            } else if (session.client() != null) {
                // The host answers with a line of its own, and may refuse: on
                // somebody else's walk the code does nothing. Saying anything
                // here would risk saying the opposite of what happened.
                session.client().sendDebug(Debug.CODE);
            }
        }
    }

    /** Whether this player is in debug mode, as the last snapshot has it. */
    private boolean debugging() {
        WatchView.Walker me = view() == null ? null : view().self();
        return me != null && me.debug();
    }

    /** How settled this player is — the host's number when there is one. */
    private double stillness() {
        WatchPlayer me = session.local() == null ? null
                : session.local().player(session.selfId());
        if (me != null) return me.stillness();
        WatchView.Walker self = view().self();
        return self != null ? self.stillness() : 1;
    }

    /**
     * Walk, swim, dive, or row.
     *
     * <p>No jumping and no fall damage: this is a game about looking at things,
     * and the movement it needs is the movement a person out for a walk has.
     * What it does have is <b>crouching</b>, because crouching is how you get
     * near a wary animal — see {@link WatchPlayer#stillness()} — and three
     * things that were promised and were not there:
     *
     * <ul>
     *   <li><b>Diving.</b> The old version pinned a swimmer to sixty
     *       centimetres under the surface and gave them no way down, so the sea
     *       floor and everything on it were places you could see and never
     *       reach. Now the crouch key sinks and the sprint key rises, and the
     *       floor of a lake is somewhere you walk about on.</li>
     *   <li><b>Rowing.</b> A boat is nine and a half metres a second across
     *       water that is otherwise two and a half.</li>
     *   <li><b>Breath.</b> Which runs out, and floats you up rather than
     *       killing you. See {@link WatchPlayer#breath()}.</li>
     * </ul>
     */
    private void walk(double dt, InputManager input) {
        double ground = streamer.groundAt(px, py);
        double surface = TerrainField.WATER_LEVEL;
        double depth = Math.max(0, surface - ground);
        boolean overWater = depth > 0.6;
        boolean rowing = boatId != 0 && overWater;

        // Crouch on land is a stance; in the water it is "go down". The key
        // does the thing the situation calls for rather than being two keys.
        if (KeyBinds.pressed(input, GameAction.JUMP) && !overWater && !rowing) {
            crouching = !crouching;
        }
        if (overWater) crouching = false;

        double forward = 0, strafe = 0;
        if (KeyBinds.down(input, GameAction.MOVE_UP)) forward += 1;
        if (KeyBinds.down(input, GameAction.MOVE_DOWN)) forward -= 1;
        if (KeyBinds.down(input, GameAction.MOVE_RIGHT)) strafe += 1;
        if (KeyBinds.down(input, GameAction.MOVE_LEFT)) strafe -= 1;

        boolean sprinting = KeyBinds.down(input, GameAction.SPRINT);
        // In the water, up is up and crouch is down — the convention every
        // game with swimming in it uses, and the opposite of what "jump" and
        // "sprint" mean on land. Sprint is free while swimming (there is no
        // sprinting in water) and crouch is the key a player's hand is already
        // reaching for when they want to go lower.
        boolean rising = KeyBinds.down(input, GameAction.JUMP);
        boolean sinking = KeyBinds.down(input, GameAction.SPRINT);

        double speed;
        if (rowing) {
            speed = Boats.ROW_SPEED;
        } else if (overWater) {
            speed = SWIM_SPEED * (submerged ? UNDERWATER_DRAG + 0.45 : 1);
        } else {
            speed = crouching ? WatchPlayer.CROUCH_SPEED
                    : sprinting && !crouching ? WatchPlayer.RUN_SPEED
                    : WatchPlayer.WALK_SPEED;
        }

        double startX = px, startY = py;
        double length = Math.hypot(forward, strafe);
        if (length > 0) {
            forward /= length;
            strafe /= length;
            // Forward is where the eye is looking, flattened onto the ground —
            // walking is not affected by looking up at a bird. Under water it
            // is not flattened: swimming where you are looking is the whole
            // point of being able to look down.
            double fx, fy;
            if (submerged) {
                double cp = Math.cos(pitch);
                fx = Math.sin(yaw) * cp;
                fy = -Math.cos(yaw) * cp;
                double flat = Math.hypot(fx, fy);
                if (flat > 1e-6) {
                    fx /= flat;
                    fy /= flat;
                }
                // Looking down and swimming forward should take you down.
                dive += Math.sin(-pitch) * forward * speed * dt;
            } else {
                fx = Math.sin(yaw);
                fy = -Math.cos(yaw);
            }
            double rx = Math.cos(yaw), ry = Math.sin(yaw);
            px += (fx * forward + rx * strafe) * speed * dt;
            py += (fy * forward + ry * strafe) * speed * dt;
        }

        // A boat cannot be rowed onto the grass. Refusing the step rather than
        // teleporting the player back is what keeps a boat nosed into a bank
        // rather than beached in a field.
        if (rowing && streamer.groundAt(px, py) > surface - 0.35) {
            px = startX;
            py = startY;
        }

        lastSpeed = Math.hypot(px - startX, py - startY) / Math.max(1e-6, dt);
        gait += lastSpeed * dt * 0.55;
        gait -= Math.floor(gait);

        if (rowing) {
            // Sitting in the boat: the body rides on the waterline whatever the
            // bed is doing under it.
            dive = 0;
            smoothedGround = surface - Boats.DECK;
            pz = smoothedGround;
            submerged = false;
            breath = Math.min(1, breath + dt * 4 / WatchPlayer.BREATH_SECONDS);
            return;
        }

        if (overWater) {
            swim(dt, depth, sinking, rising, ground, surface);
            return;
        }

        // Back on dry land: shed whatever depth was left, and follow the ground.
        dive = 0;
        submerged = false;
        breath = Math.min(1, breath + dt * 4 / WatchPlayer.BREATH_SECONDS);
        // Eased rather than snapped: on a two-metre grid the ground under a
        // walker changes by tens of centimetres a step, and a camera that
        // tracked it exactly would jolt with every one of them.
        smoothedGround += (ground - smoothedGround) * Math.min(1, dt * STEP_SMOOTHING);
        pz = smoothedGround;
    }

    /**
     * In the water: float, sink, rise, and run out of air.
     *
     * <p>{@link #dive} is how far below the waterline the feet are, so the
     * whole of swimming is a number between {@code FLOAT_DEPTH} — head out,
     * treading water — and the depth of the water, which is standing on the
     * bed. Every other part of the state falls out of that one number, which is
     * why walking out of a lake onto a slope needs no special case: the depth
     * simply stops applying.
     */
    private void swim(double dt, double depth, boolean sinking, boolean rising,
                      double ground, double surface) {
        double floor = Math.max(0, depth);
        if (sinking) dive += DIVE_SPEED * dt;
        if (rising) dive -= DIVE_SPEED * dt;
        // Out of air: come up, whatever the keys say. Not a death, and not a
        // fade to black — the game is about looking at things, and the worst
        // that should happen to somebody who looked too long is that they have
        // to surface and go back down.
        if (breath <= 0) dive -= DIVE_SPEED * 1.3 * dt;
        // Nobody sinks by standing still, and nobody floats up out of a dive
        // they meant: the drift toward the surface is gentle and only applies
        // when the player is not asking for anything.
        if (!sinking && !rising && breath > 0) {
            dive += (Math.min(FLOAT_DEPTH, floor) - dive) * Math.min(1, dt * 0.55);
        }
        dive = Math.max(0, Math.min(floor, dive));

        smoothedGround = surface - dive;
        // Standing on the bed rather than hovering above it: when the dive has
        // reached the floor, this is exactly `ground`.
        pz = Math.max(ground, smoothedGround);

        double eyeZ = pz + (crouching ? 1.10 : 1.68);
        submerged = eyeZ < surface - SUBMERGED_MARGIN;
        breath = submerged
                ? Math.max(0, breath - dt / WatchPlayer.BREATH_SECONDS)
                : Math.min(1, breath + dt * 4 / WatchPlayer.BREATH_SECONDS);
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
        // Cleared here and not only in reachPrompt: an animal under the
        // crosshair returns early, and without this the ring stayed pulsing
        // round a bush the player had walked away from ten seconds ago.
        inReach = null;
        // The aim, not the heading: what the crosshair covers is where the
        // glass is actually wandering. See `useGlass`.
        double cp = Math.cos(aimPitch);
        double dirX = Math.sin(aimYaw) * cp, dirY = -Math.cos(aimYaw) * cp;
        double dirZ = Math.sin(aimPitch);
        double eyeZ = pz + (crouching ? 1.10 : 1.68);
        double power = glass.power();
        double range = Spyglass.spotRange(power, WatchGame.SPOT_RANGE);
        double best = Double.MAX_VALUE;
        WatchView.Creature found = null;
        for (WatchView.Creature creature : view().creatures()) {
            double dx = creature.x() - px, dy = creature.y() - py;
            double dz = creature.z() + creature.def().bodyLength() * 0.5 - eyeZ;
            double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (distance > range || distance < 0.01) continue;
            double dot = (dx * dirX + dy * dirY + dz * dirZ) / distance;
            if (dot <= 0) continue;
            double angle = Math.acos(Math.min(1, dot));
            double tolerance = Spyglass.tolerance(creature.def().bodyLength(), distance,
                    power);
            if (angle > tolerance) continue;
            double score = angle * 1000 + distance;
            if (score < best) {
                best = score;
                found = creature;
            }
        }
        if (found == null) {
            // Nothing under the crosshair, but there may well be something at
            // your feet. The reach prompt is the quieter of the two and loses
            // to an animal, which is the right order for a game about animals.
            reachPrompt();
            return;
        }
        lookingAtId = found.id();
        boolean known = view().guide().seen(found.def().key());
        prompt = known
                ? found.def().name() + "  —  click to point it out"
                : "Something new  —  click to record it";
        // How far off it is, but only through the glass: unaided, everything is
        // within a hundred metres and the number is noise. Glassing, it is the
        // whole reason you raised it — "there is something four hundred metres
        // out on that spit" is what one walker says to another.
        if (glass.up()) {
            double dx = found.x() - px, dy = found.y() - py;
            prompt += "  ·  " + Math.round(Math.hypot(dx, dy)) + " m";
        }
    }

    /**
     * What is in reach, and what E would do to it.
     *
     * <p>Solo, the game itself answers, and its answer is the one E will act
     * on. Online there is nobody to ask at frame rate — the host adjudicates
     * the press when it arrives — so this walks the same candidates over what
     * the client already has: the chunks it generated from the shared seed, and
     * the grove, crops, feeders and boats the last world sync brought. It can
     * be wrong about a bush somebody else has just stripped, which shows as a
     * ring that turns out to be empty; that is a better failure than a game
     * where the highlight only exists in single player.
     */
    private void reachPrompt() {
        WatchGame local = session.local();
        inReach = local != null ? local.pickTarget(session.selfId()) : guessReach();
        if (inReach != null) prompt = inReach.prompt();
    }

    /** The client's own guess at what is in reach. See {@link #reachPrompt}. */
    private WatchGame.Pickable guessReach() {
        WatchView view = view();
        double reachLimit = WatchGame.REACH;

        WatchChunk chunk = streamer.chunkAt(px, py);
        if (chunk != null) {
            for (com.larsons.engine.watch.world.Flora.Bush bush : chunk.bushes()) {
                if (!bush.ripe()) continue;
                if (Math.hypot(bush.x() - px, bush.y() - py) > reachLimit) continue;
                return new WatchGame.Pickable(WatchGame.Pickable.Kind.BUSH,
                        bush.berry(), Forage.nameOf(bush.berry()), bush.x(), bush.y(),
                        bush.z() + bush.radius() * 0.8, bush.radius());
            }
        }
        for (TreeInstance tree : view.grove().near(px, py, reachLimit + 1.5)) {
            if (!tree.fruiting() || tree.species().fruit() == null) continue;
            return new WatchGame.Pickable(WatchGame.Pickable.Kind.TREE,
                    tree.species().fruit(), Forage.nameOf(tree.species().fruit()),
                    tree.x(), tree.y(), tree.z() + Math.max(1.4, tree.height() * 0.55),
                    0.9);
        }
        for (Cultivation.Crop crop : view.crops().near(px, py, reachLimit)) {
            if (!crop.ripe()) continue;
            return new WatchGame.Pickable(WatchGame.Pickable.Kind.CROP, crop.seed(),
                    Forage.nameOf(crop.seed()), crop.x(), crop.y(),
                    crop.z() + crop.height(), 0.4);
        }
        for (Lure lure : view.lures()) {
            if (Math.hypot(lure.x() - px, lure.y() - py) > reachLimit) continue;
            return new WatchGame.Pickable(WatchGame.Pickable.Kind.FEEDER, lure.food(),
                    Forage.nameOf(lure.food()) + " feeder", lure.x(), lure.y(),
                    lure.z() + 1.2, 0.35);
        }
        if (boatId == 0) {
            Boats.Boat boat = view.boats().nearest(streamer.field(), px, py,
                    Boats.BOARD_RANGE);
            if (boat != null) {
                return new WatchGame.Pickable(WatchGame.Pickable.Kind.BOAT, "boat",
                        "Rowing boat", boat.x(), boat.y(), boat.z() + 0.4, 1.6);
            }
        }
        Litter.Piece piece = litterInReach();
        if (piece != null) {
            return new WatchGame.Pickable(WatchGame.Pickable.Kind.GROUND, piece.key(),
                    Forage.nameOf(piece.key()), piece.x(), piece.y(),
                    piece.z() + 0.10, WatchGame.GROUND_HIGHLIGHT);
        }
        return null;
    }

    /** The verbs. Every one of them is a request; none of them changes anything here. */
    private void act(InputManager input) {
        if (KeyBinds.pressed(input, GameAction.WATCH_SPOT)) request("spot");
        // WATCH_PICK only, and deliberately not INTERACT as well. Both ship
        // bound to E, and while they sent different verbs — "pick" and
        // "harvest" — one press doing both was merely odd. Now that one verb
        // covers everything in reach, one press doing it twice is two berries
        // off one bush and two frames of it on the wire.
        if (KeyBinds.pressed(input, GameAction.WATCH_PICK)) request("use");
        if (KeyBinds.pressed(input, GameAction.WATCH_PLANT)) request("plant");
        if (KeyBinds.pressed(input, GameAction.WATCH_CROSS)) request("cross");
        if (KeyBinds.pressed(input, GameAction.WATCH_FEEDER)) request("lure");
        if (KeyBinds.pressed(input, GameAction.WATCH_ROD)) request("rod");
        if (KeyBinds.pressed(input, GameAction.WATCH_BOAT)) request("boat");
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
            case "use" -> {
                // The reach gesture plays whether or not anything came of it:
                // reaching out and finding nothing is information too.
                reach = 1;
                if (local != null) {
                    String line = local.use(me);
                    if (line != null) picked(line);
                } else {
                    session.client().sendAction("use");
                    // Take it off the ground now rather than in a round trip's
                    // time. The host still decides — the next world sync
                    // replaces the whole set — but a thing that stays lying
                    // there for a fifth of a second after you have picked it up
                    // reads as the pick having failed. See
                    // WatchView.noteLitterTaken.
                    if (inReach != null
                            && inReach.kind() == WatchGame.Pickable.Kind.GROUND) {
                        Litter.Piece piece = litterInReach();
                        if (piece != null) view().noteLitterTaken(piece.id());
                    }
                }
            }
            case "boat" -> {
                if (local != null) {
                    String line = local.useBoat(me);
                    if (line != null) {
                        say(line);
                        WatchPlayer player = local.player(me);
                        boatId = player == null ? 0 : player.boatId();
                    }
                } else {
                    session.client().sendAction("boat");
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

    /**
     * Something went in the satchel: say so, and flash it on screen.
     *
     * <p>The chat log at the bottom right is where every event in this game has
     * always gone, and it is the wrong place for the one event that happens
     * fifty times an hour and that the player is looking at the middle of the
     * screen for. A short flash under the crosshair costs one string and is
     * where the eye already is.
     */
    private void picked(String line) {
        say(line);
        flash(line);
    }

    /** Put a line under the crosshair for {@link #FLASH_SECONDS}. */
    private void flash(String line) {
        pickedName = line;
        pickedFlash = FLASH_SECONDS;
    }

    /** How long the pickup flash lasts, in seconds. */
    private static final double FLASH_SECONDS = 1.6;

    /** What was in the satchel last frame. See {@link #noticePickups}. */
    private Map<String, Integer> carried;

    /**
     * Flash anything that turned up in the satchel we did not put there.
     *
     * <p>Online, a pick is a request and what comes back is a satchel with one
     * more thing in it a few frames later — there is no local call to hang the
     * flash off. Watching the contents is the honest signal, and it catches
     * cases a local hook would miss anyway: a recipe finishing, a fish landing.
     *
     * <p><b>The whole map, not the total and the last key.</b> The satchel keeps
     * insertion order and a second handful of blackberries does not reorder
     * anything, so "the last key" is the last <em>new</em> kind, which after
     * five minutes is almost never what was just picked. Diffing the counts
     * names the right thing every time and costs a walk over a map with a few
     * dozen entries in it, once a frame.
     *
     * <p>Yields to a flash raised in the last fraction of a second, which is
     * the solo path having already said something more specific about the same
     * event.
     */
    private void noticePickups() {
        // Nothing is ever gained when everything is unlimited, and the frame
        // debug mode goes on would otherwise flash whichever item the diff
        // happened to reach first.
        if (view().satchel().bottomless()) {
            carried = null;
            return;
        }
        Map<String, Integer> now = view().satchel().contents();
        if (carried != null && pickedFlash < FLASH_SECONDS - 0.2) {
            String gained = null;
            int most = 0;
            for (Map.Entry<String, Integer> entry : now.entrySet()) {
                int grew = entry.getValue() - carried.getOrDefault(entry.getKey(), 0);
                if (grew > most) {
                    most = grew;
                    gained = entry.getKey();
                }
            }
            if (gained != null) {
                flash("+" + most + " " + Forage.nameOf(gained));
                flashedKey = gained;
            }
        }
        carried = now;
    }

    /**
     * The item the hand should be holding, or {@code null}.
     *
     * <p>Whatever the last flash named, resolved back to a key — so the thing
     * in your hand is the thing the screen just said you picked up, and the two
     * cannot disagree.
     */
    private String flashedKey;

    /**
     * Tell the host where we are and which way we are looking.
     *
     * <p><b>The aim goes on the wire, not the heading.</b> The host traces its
     * own ray to decide what a click hit, and the ray it should trace is the
     * one under the crosshair — which, with a glass up, includes the shake in
     * the player's hands. Sending the un-swayed heading would mean the label
     * said "Redpoll" and the click recorded whatever was a third of a degree
     * away, which is a different bird at four hundred metres.
     */
    private void sendMove() {
        if (session.online()) {
            session.client().sendMove(px, py, pz, aimYaw, aimPitch, crouching);
            // The host decides whether that position is under water and what
            // that does to the breath; adopt its answer rather than our own.
            WatchView.Walker me = view().self();
            if (me != null) {
                breath = me.breath();
                boatId = me.boatId();
            }
        } else if (session.local() != null) {
            session.local().move(session.selfId(), px, py, pz, aimYaw, aimPitch, crouching,
                    MOVE_INTERVAL);
            WatchPlayer me = session.local().player(session.selfId());
            if (me != null) boatId = me.boatId();
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

    /**
     * The satchel screen: two columns, both of which scroll, and both of which
     * the mouse drives.
     *
     * <p><b>The carrying list could not be scrolled at all.</b> It drew from the
     * top of the panel until it ran out of room and then stopped, so a satchel
     * with more than about twenty kinds in it — which is a satchel after an
     * hour — simply had a tail nobody could see or reach. Everything
     * <em>was</em> in there; the game just would not show it to you.
     *
     * <p>Then it grew a cursor, a window and a bar, and ←/→ to move between the
     * columns — and was still a screen you could only work with the arrow keys,
     * in a game whose every other verb is on the mouse. Forty rows of inventory
     * navigated one press at a time is a cooking screen nobody cooks on. So:
     *
     * <ul>
     *   <li>the pointer <b>hovers</b> a row to select it — but only when it has
     *       actually moved, so a mouse sitting still on the desk does not undo
     *       every arrow-key press;</li>
     *   <li>a <b>click</b> does what Enter does to that row: makes the recipe,
     *       plants the seed, puts the food out;</li>
     *   <li>the <b>wheel</b> scrolls whichever column it is over, independently
     *       of where the cursor is — which is what makes reading a long list
     *       different from walking it;</li>
     *   <li>the <b>bars</b> can be dragged, and the ✕ closes the panel.</li>
     * </ul>
     *
     * <p>None of that takes anything away from the keys. Both drive the same
     * two indices and the same two scroll offsets, and either can be left alone
     * for a whole session.
     */
    private void updateSatchel(InputManager input) {
        List<String> items = view().satchel().keys();
        List<Recipes.Recipe> recipes = Recipes.all();
        SatchelBox box = satchelBox();

        if (KeyBinds.pressed(input, GameAction.MENU_LEFT)) recipeColumn = false;
        if (KeyBinds.pressed(input, GameAction.MENU_RIGHT)) recipeColumn = true;

        int step = 0;
        if (KeyBinds.pressed(input, GameAction.MENU_DOWN)) step = 1;
        if (KeyBinds.pressed(input, GameAction.MENU_UP)) step = -1;
        if (step != 0) {
            if (recipeColumn) {
                recipeIndex = Math.floorMod(recipeIndex + step,
                        Math.max(1, recipes.size()));
                recipeScroll = clampScroll(recipeScroll, recipeIndex, recipes.size(),
                        box.rows());
            } else {
                satchelIndex = items.isEmpty() ? 0
                        : Math.floorMod(satchelIndex + step, items.size());
                satchelScroll = clampScroll(satchelScroll, satchelIndex, items.size(),
                        box.rows());
            }
        }

        // --- the mouse -------------------------------------------------------
        boolean moved = pointerMoved(input);
        int mx = pointerX, my = pointerY;
        int over = box.columnAt(mx, my);

        if (dragging(input, box, items.size(), recipes.size())) return;

        int wheel = input.getWheelRotation();
        if (wheel != 0) {
            int which = over >= 0 ? over : recipeColumn ? 1 : 0;
            if (which == 1) {
                recipeScroll = boundScroll(recipeScroll + wheel * WHEEL_ROWS,
                        recipes.size(), box.rows());
            } else {
                satchelScroll = boundScroll(satchelScroll + wheel * WHEEL_ROWS,
                        items.size(), box.rows());
            }
        }

        int hovered = -1;
        if (over >= 0) {
            int row = box.rowAt(my);
            int total = over == 1 ? recipes.size() : items.size();
            int scroll = over == 1 ? recipeScroll : satchelScroll;
            if (row >= 0 && scroll + row < total) hovered = scroll + row;
        }
        if (hovered >= 0 && moved) {
            recipeColumn = over == 1;
            if (recipeColumn) recipeIndex = hovered;
            else satchelIndex = hovered;
        }

        if (input.isMouseJustPressed()) {
            if (box.overClose(mx, my)) {
                panel = Panel.NONE;
                return;
            }
            if (hovered >= 0) {
                recipeColumn = over == 1;
                if (recipeColumn) {
                    recipeIndex = hovered;
                    craft(recipes.get(hovered));
                } else {
                    satchelIndex = hovered;
                    useFromSatchel(items.get(hovered));
                }
            }
        }

        if (KeyBinds.pressed(input, GameAction.MENU_SELECT)) {
            if (recipeColumn && !recipes.isEmpty()) {
                craft(recipes.get(Math.min(recipeIndex, recipes.size() - 1)));
            } else if (!items.isEmpty()) {
                useFromSatchel(items.get(Math.min(satchelIndex, items.size() - 1)));
            }
        }
        if (KeyBinds.pressed(input, GameAction.WATCH_SATCHEL)) panel = Panel.NONE;
    }

    /** How many rows one notch of the wheel moves a list. */
    private static final int WHEEL_ROWS = 3;

    /**
     * Whether the pointer has moved since the last frame — and remember where
     * it is either way.
     *
     * <p>The whole of what stops hovering from fighting the arrow keys. Without
     * it, a pointer resting anywhere over a list re-selects the row under it
     * every frame, so pressing ↓ moves the cursor for exactly one frame and it
     * springs back. The panels are the only place in this scene that reads the
     * pointer's <em>position</em> — the walk reads its motion — so the two
     * fields live here rather than beside the look controls.
     */
    private boolean pointerMoved(InputManager input) {
        int mx = input.getMouseX(), my = input.getMouseY();
        boolean moved = mx != pointerX || my != pointerY;
        pointerX = mx;
        pointerY = my;
        return moved;
    }

    /**
     * Carry on a scrollbar drag, or start one.
     *
     * @return whether a drag is in progress, in which case the rest of the
     *         panel's mouse handling is skipped — a hand dragging a bar past a
     *         row is not hovering that row, and letting go over one is not a
     *         click on it
     */
    private boolean dragging(InputManager input, SatchelBox box, int items,
                             int recipes) {
        if (dragBar == 0 && input.isMouseJustPressed()) {
            if (box.overBar(pointerX, pointerY, 0) && items > box.rows()) dragBar = 1;
            else if (box.overBar(pointerX, pointerY, 1) && recipes > box.rows()) {
                dragBar = 2;
            }
        }
        if (dragBar == 0) return false;
        if (!input.isMouseDown()) {
            dragBar = 0;
            return false;
        }
        int total = dragBar == 2 ? recipes : items;
        // Where the pointer sits down the track, as a share of it, turned into
        // a first visible row.
        double share = (pointerY - box.barTop()) / (double) Math.max(1, box.barHeight());
        int at = boundScroll((int) Math.round(share * (total - box.rows())), total,
                box.rows());
        if (dragBar == 2) recipeScroll = at;
        else satchelScroll = at;
        return true;
    }

    /** A scroll offset kept inside a list, ignoring where the cursor is. */
    private static int boundScroll(int scroll, int total, int rows) {
        return Math.max(0, Math.min(scroll, Math.max(0, total - rows)));
    }

    private void craft(Recipes.Recipe recipe) {
        if (session.local() != null) {
            say(session.local().craft(session.selfId(), recipe, recipe.station())
                    ? "Made " + recipe.name()
                    : "Not enough for that — " + recipe.costLine());
        } else {
            session.client().sendCraft(recipe.output(), recipe.station().name());
        }
    }

    /**
     * Do the obvious thing with the item under the cursor.
     *
     * <p>Food goes on a feeder, a seed goes in the ground, and everything else
     * says what it is for. Nothing here is a new verb — they are the ones F and
     * R already send — it is only that they can now be aimed at a particular
     * item instead of at whatever the satchel happened to list first.
     */
    private void useFromSatchel(String key) {
        Forage.Item item = Forage.byKey(key);
        if (item == null) return;
        WatchGame local = session.local();
        int me = session.selfId();
        if (item.kind() == Forage.Kind.SEED) {
            if (local != null) {
                String line = local.plant(me, key);
                say(line != null ? line : "You need a trowel and dry ground");
            } else {
                session.client().sendPlant(key);
            }
            return;
        }
        if (item.edible()) {
            if (local != null) {
                say(local.placeLure(me, key) != null
                        ? "Put out " + item.name()
                        : "You need a feeder, and dry ground to stand it on");
            } else {
                session.client().sendPlaceLure(key);
            }
            return;
        }
        say(item.name() + " — " + item.note());
    }

    /**
     * The build screen — driven by the same two hands as the satchel, and for
     * the same reason.
     *
     * <p>Ten pieces fit on one page, so there is nothing to scroll; what it
     * needed was a pointer that selects a row, a click that builds it, and a ✕.
     */
    private void updateBuild(InputManager input) {
        List<BuildPiece> pieces = BuildPiece.all();
        SatchelBox box = buildBox();
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

        boolean moved = pointerMoved(input);
        int row = box.columnAt(pointerX, pointerY) == 0 ? box.rowAt(pointerY) : -1;
        if (row >= 0 && row >= pieces.size()) row = -1;
        if (row >= 0 && moved) pieceIndex = row;

        boolean build = KeyBinds.pressed(input, GameAction.MENU_SELECT);
        if (input.isMouseJustPressed()) {
            if (box.overClose(pointerX, pointerY)) {
                panel = Panel.NONE;
                return;
            }
            if (row >= 0) {
                pieceIndex = row;
                build = true;
            }
        }
        if (build) {
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

    /**
     * Where the build screen's parts are — the satchel's box, one column wide.
     *
     * <p>The same record, because the two panels want the same three answers
     * ("which row is that", "is the pointer on the ✕", "where does the list
     * start") and a second nearly-identical layout type would be a second
     * chance to get one of them wrong.
     */
    private SatchelBox buildBox() {
        int w = Math.min(600, Math.max(320, viewportWidth - 80));
        int h = Math.min(460, Math.max(240, viewportHeight - 80));
        int x = (viewportWidth - w) / 2, y = (viewportHeight - h) / 2;
        int listTop = y + 62;
        return new SatchelBox(x, y, w, h, listTop, BuildPiece.all().size(), w - 40);
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
        Weather weather = view().weather();
        // The sky the world is drawn under, which is three things multiplied:
        // the biome's own colour, the hour, and the weather.
        int sky = weatherTint(biome.skyRgb(), weather);
        int fog = weatherTint(biome.fogRgb(), weather);
        if (submerged) {
            // Under water everything is the water's colour and nothing is far
            // away. That is not decoration: a sea floor drawn under a clear sky
            // at a two-hundred-metre draw distance does not read as being under
            // anything at all.
            sky = underwaterTint(biome);
            fog = sky;
        }
        renderer.begin(target, eye, viewportWidth, viewportHeight, clock, sky, fog);
        applyVisibility(weather);
        if (frame == 2) applyDistanceSettings();

        // <b>The moving meshes go first, and the order is not cosmetic.</b>
        // Opaque geometry is order-independent under a depth buffer, so this
        // costs nothing visually — but a backend bounds how many buffers it
        // will re-specify in one frame, and these two are re-specified every
        // frame by construction. Submitted after four hundred chunks, they are
        // the ones that lose the race into fresh terrain, and a frame with no
        // animals or no hands in it is far more noticeable than a frame with
        // one hillside at the wrong level of detail.
        renderer.submit(buildDynamicMesh(), DYNAMIC_KEY);
        // The view model is its own mesh with its own key, because it is the
        // one thing on screen that is measured from the camera rather than from
        // the world and so has to be rebuilt whenever the camera turns — which
        // is every frame, whether or not anything else moved.
        // Not while the glass is up. At ×8 the hands subtend twenty degrees of
        // a ten-degree view, so they are not "off to the side" — they are the
        // whole frame, in front of the thing being looked at. What a player
        // sees instead is the eyepiece, drawn over the finished world by
        // `drawEyepiece`, which is how a scope has always been done and is also
        // the honest picture: your hands are behind the glass, not in it.
        if (!thirdPerson && !glass.up()) renderer.submit(buildViewMesh(), VIEW_MODEL_KEY);

        for (WatchChunk chunk : streamer.chunks()) {
            long key = chunk.key();
            renderer.submit(chunk.groundMesh(), key * 4);
            renderer.submit(chunk.floraMesh(), key * 4 + 1);
            renderer.submit(chunk.grassMesh(), key * 4 + 2);
            renderer.submit(chunk.waterMesh(), key * 4 + 3);
        }
        renderer.flush(target);

        drawWeatherOverlay(target, weather);
        drawEyepiece(target);
        drawHud(target, biome, weather);
        switch (panel) {
            case SATCHEL -> drawSatchel(target);
            case BUILD -> drawBuild(target);
            case PAUSED -> drawPaused(target);
            case NONE -> { }
        }
    }

    /**
     * How far the fog lets you see this frame.
     *
     * <p>The streamer's ring decides the ceiling; the weather and the water
     * bring it in. Applied every frame rather than only when the setting
     * changes, because the weather is always halfway through changing.
     */
    private void applyVisibility(Weather weather) {
        double reach = streamer.viewRadius() * WatchChunk.SIZE;
        // A glass sees past the fog as well as past the ring — and it has to,
        // because the painter path throws away anything beyond the far plane
        // and a card fades it to the horizon's colour. Whatever ground the
        // focus cone actually asked for is what the fog is allowed to hide.
        ChunkStreamer.Focus focus = streamer.focus();
        if (focus != null) {
            reach = Math.max(reach, focus.radius() * (double) WatchChunk.SIZE);
        }
        double scale = weather.visibility();
        if (submerged) scale = Math.min(scale, UNDERWATER_VISIBILITY);
        // Haze thins as it is magnified out of the way: the far half of a ×15
        // view would otherwise be a flat wash of fog colour, which is exactly
        // the detail the glass was raised to see through.
        double lens = Math.min(3.0, glass.power() * 0.5 + 0.5);
        double end = reach * 1.02 * scale;
        // Pulled back toward the far plane, never past it: the horizon still
        // has to meet the sky in the same colour. See WatchRenderer.sky.
        double start = Math.min(end * 0.92, reach * 0.45 * scale * lens);
        renderer.setFogRange(start, end);
    }

    /** A biome colour, pushed toward the weather's own grey. */
    private static int weatherTint(int rgb, Weather weather) {
        double dim = 1 - (1 - weather.visibility()) * 0.45;
        int r = (int) (((rgb >> 16) & 0xFF) * dim);
        int g = (int) (((rgb >> 8) & 0xFF) * dim);
        int b = (int) ((rgb & 0xFF) * dim);
        // Weather greys the sky as well as darkening it: a storm is not a
        // night, it is a flat white-grey lid.
        int grey = (r + g + b) / 3;
        double flat = (1 - weather.visibility()) * 0.5;
        r = (int) (r + (grey - r) * flat);
        g = (int) (g + (grey - g) * flat);
        b = (int) (b + (grey - b) * flat);
        return (r << 16) | (g << 8) | b;
    }

    /**
     * The colour of being under water, blended toward this biome's own haze so
     * a tropical lagoon and a peat tarn are not the same green.
     */
    private static int underwaterTint(WatchBiome biome) {
        int water = WatchMaterials.shade(WatchMaterial.WATER);
        int haze = biome.fogRgb();
        int r = (int) ((((water >> 16) & 0xFF) * 0.72 + ((haze >> 16) & 0xFF) * 0.14));
        int g = (int) ((((water >> 8) & 0xFF) * 0.78 + ((haze >> 8) & 0xFF) * 0.14));
        int b = (int) (((water & 0xFF) * 0.88 + (haze & 0xFF) * 0.14));
        return (Math.min(255, r) << 16) | (Math.min(255, g) << 8) | Math.min(255, b);
    }

    /** How high the local player's eyes are above their feet, in metres. */
    private double eyeHeight() {
        return crouching ? 1.10 : 1.68;
    }

    private void placeCamera() {
        eye.setViewport(viewportWidth, viewportHeight);
        // The whole of the zoom: a shorter field of view through a longer lens.
        // Nothing else about the frame changes, which is why what comes back is
        // more detail rather than bigger pixels. See Spyglass.
        eye.setFov(glass.fov(EyeCamera.DEFAULT_FOV));
        double eyeHeight = eyeHeight();
        // A head bob, at a fifth of the amplitude a shooter would use. Enough
        // that walking feels like walking; little enough that nobody watching a
        // bird through it notices.
        if (!thirdPerson) {
            eyeHeight += Math.sin(gait * Math.PI * 4) * 0.028
                    * Math.min(1, lastSpeed / WatchPlayer.WALK_SPEED);
        }
        if (thirdPerson && !glass.up()) {
            // Behind and a little above, and pulled up out of the ground if the
            // slope behind is steeper than the camera arm.
            double bx = px - Math.sin(yaw) * THIRD_PERSON_BACK;
            double by = py + Math.cos(yaw) * THIRD_PERSON_BACK;
            double bz = Math.max(streamer.groundAt(bx, by) + 1.2,
                    pz + eyeHeight + 1.0);
            eye.place(bx, by, bz);
        } else {
            // A raised glass is at your eye whatever the view setting says:
            // looking through a telescope from four metres behind your own head
            // is not a thing, and the third-person camera's own body would be
            // in the middle of the eyepiece.
            eye.place(px, py, pz + eyeHeight);
        }
        eye.look(aimYaw, aimPitch);
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
        // Snapped to the metre rather than taken from the player's exact
        // position. The origin still moves — it has to, or the floats lose
        // precision a long way out — but it moves in metre steps, so a backend
        // caching by origin re-uploads on a step rather than on a frame. See
        // the note on GlMeshPass.Buffer.originX for what happens when a cached
        // buffer and a moved origin are allowed to disagree.
        double ox = Math.floor(px), oy = Math.floor(py);
        Mesh.Builder mesh = Mesh.builder(ox, oy, 0, false, frame);
        float[] uv = new float[4];

        for (WatchView.Creature creature : view.creatures()) {
            AnimalModels.Loaded model = AnimalModels.of(creature.def());
            model.geometry().mesh(mesh, creature.def(), creature.x() - ox,
                    creature.y() - oy, creature.z(), creature.yaw(),
                    creature.state(), creature.phase(), 1, model.poses());
        }

        // Every walker, including this one: in third person you are looking at
        // yourself, and in first person your own body is still what casts the
        // shadow, sits in the boat, and shows above the water.
        for (WatchView.Walker walker : view.walkers()) {
            if (walker.id() == view.selfId() && !thirdPerson) continue;
            drawWalker(mesh, walker, ox, oy);
        }
        if (thirdPerson && view.self() == null) {
            // Before the first snapshot there is no walker record for us, and
            // the third-person camera would be looking at nothing.
            drawSelf(mesh, ox, oy);
        }

        // The boats: every one within the view, wherever the seed put it or
        // whoever left it there.
        //
        // A boat somebody is <em>in</em> is drawn at that person rather than at
        // its mooring, because that is where it is. The store only learns where
        // a boat has got to when somebody steps out of it — which is the right
        // thing to persist and exactly the wrong thing to draw from, since a
        // rower would otherwise glide across the lake while their boat sat on
        // the beach they left.
        double boatReach = streamer.viewRadius() * WatchChunk.SIZE;
        for (Boats.Boat boat
                : view.boats().near(streamer.field(), px, py, boatReach)) {
            WatchView.Walker rower = rowerOf(view, boat.id());
            double bx = boat.x(), by = boat.y(), byaw = boat.yaw();
            if (rower != null) {
                bx = rower.x();
                by = rower.y();
                byaw = rower.yaw();
            }
            BoatModel.boat(mesh, bx - ox, by - oy, boat.z(), byaw,
                    (frame * 0.006 + boat.id() * 0.13) % 1);
        }
        // …and our own, which is in the party list but may not have reached the
        // view yet, and whose position we know better than the last snapshot.
        if (boatId != 0 && rowerOf(view, boatId) == null) {
            Boats.Boat mine = view.boats().byId(streamer.field(), boatId);
            if (mine != null) {
                BoatModel.boat(mesh, px - ox, py - oy, mine.z(), yaw,
                        (frame * 0.006 + boatId * 0.13) % 1);
            }
        }

        for (Lure lure : view.lures()) {
            WatchMaterials.uv(WatchMaterial.PLANK, uv);
            int post = WatchMaterials.shade(WatchMaterial.BARK);
            Shapes.prism(mesh, lure.x() - ox, lure.y() - oy, lure.z(), lure.z() + 1.1,
                    0.05, 0.05, 4, 0, uv, post, false);
            int tray = WatchMaterials.shade(lure.active()
                    ? WatchMaterial.PLANK : WatchMaterial.DARK_BARK);
            Shapes.box(mesh, lure.x() - ox, lure.y() - oy, lure.z() + 1.15,
                    0.26, 0.26, 0.05, 0, uv, tray);
            if (lure.active()) {
                // What is actually in it, rather than the same red blob however
                // it was filled. A feeder is the one thing in this game whose
                // contents somebody else needs to be able to read from twenty
                // metres away, because it decides what turns up at it.
                ItemModel.item(mesh, lure.food(), lure.x() - ox, lure.y() - oy,
                        lure.z() + 1.20, 1.1, frame * 0.004);
            }
        }

        // Everything lying on the floor near us. It goes in the moving mesh
        // rather than in a chunk's static flora, and that is not laziness: a
        // picked-up branch has to be gone <em>this frame</em>, and a chunk mesh
        // is rebuilt when the level of detail changes and not when somebody
        // stoops. The z comes off the streamer rather than off the piece so it
        // sits on the ground as drawn, which is a bilinear interpolation of the
        // heightfield and can be a few centimetres from the raw generator.
        for (Litter.Piece piece : litterNearby) {
            if (view.litterTaken(piece.id())) continue;
            double dx = piece.x() - px, dy = piece.y() - py;
            if (dx * dx + dy * dy > litterRange * litterRange) continue;
            ItemModel.item(mesh, piece.key(), piece.x() - ox, piece.y() - oy,
                    streamer.groundAt(piece.x(), piece.y()), piece.scale(),
                    piece.yaw());
        }

        for (Cultivation.Crop crop : view.crops().all()) {
            WatchMaterials.uv(WatchMaterial.LUSH_GRASS, uv);
            int green = WatchMaterials.shade(crop.ripe()
                    ? WatchMaterial.DRY_GRASS : WatchMaterial.LUSH_GRASS);
            for (int i = 0; i < 4; i++) {
                double a = i * Math.PI / 2 + 0.4;
                Shapes.blade(mesh, crop.x() - ox + Math.cos(a) * 0.12,
                        crop.y() - oy + Math.sin(a) * 0.12, crop.z(),
                        crop.height(), 0.07, a, 0, 0, uv, green);
            }
            // A ripe crop wears its own seed head, so "ready" is something you
            // can see across a clearing rather than something you walk up to.
            if (crop.ripe()) {
                ItemModel.item(mesh, crop.seed(), crop.x() - ox, crop.y() - oy,
                        crop.z() + crop.height(), 1.0, 0);
            }
        }

        for (TreeInstance tree : view.grove().all()) {
            FloraMesher.tree(mesh, tree, ox, oy, true);
        }

        for (Structure.Placement piece : view.structure().all()) {
            WatchMaterials.uv(piece.piece().material(), uv);
            Shapes.box(mesh, piece.x() - ox, piece.y() - oy, piece.z(),
                    piece.piece().sizeX() / 2, piece.piece().sizeY() / 2,
                    piece.piece().sizeZ() / 2, piece.yaw(), uv,
                    WatchMaterials.shade(piece.piece().material()));
        }

        return mesh.build();
    }

    /**
     * One player, as a walking figure.
     *
     * <p>Was three boxes and a hat brim; is now {@link WalkerModel}, which has
     * legs that swing and arms that swing against them. The gait phase is
     * derived from the position rather than sent: a walker's own hash gives
     * each person a different footfall, and the clock runs at the frame rate so
     * nobody's legs step at twenty hertz because that is the snapshot rate.
     */
    private void drawWalker(Mesh.Builder mesh, WatchView.Walker walker,
                            double ox, double oy) {
        double x = walker.x() - ox, y = walker.y() - oy;
        boolean me = walker.id() == view().selfId();
        double speed = me ? lastSpeed : 1 - walker.stillness();
        double phase = me ? gait : (frame * 0.02 + walker.id() * 0.37) % 1;
        // <b>Nothing is subtracted for swimming.</b> A walker's z is where their
        // feet are, and a diver's feet are already below the waterline — the
        // dive is in that number, not on top of it. Passing the dive depth here
        // as well drew a diver a second dive-depth down, through the lake bed,
        // and buried a remote one a metre and a half into it. The one genuine
        // offset is the boat, which lifts a rower onto the thwart.
        double sunk = walker.inBoat() ? -Boats.DECK * 0.4 : 0;
        WalkerModel.walker(mesh, x, y, walker.z(), walker.yaw(), walker.crouching(),
                phase, speed * WatchPlayer.WALK_SPEED,
                WalkerModel.coatFor(walker.id()), sunk);

        // Somebody with a glass up, seen from outside: a tube at their eye,
        // pointing where they are pointing. This is the only way in the game to
        // tell at a glance that a friend across the clearing has found
        // something and which way to look — the party's own gesture, before
        // anybody clicks anything.
        if (walker.glassing()) {
            double head = (walker.crouching() ? 1.10 : 1.68) + sunk;
            double lean = 0.18;
            ItemModel.item(mesh, Spyglass.ITEM,
                    x + Math.sin(walker.yaw()) * lean,
                    y - Math.cos(walker.yaw()) * lean,
                    walker.z() + head, 1.0, walker.yaw());
        }
    }

    /** Whoever is rowing a boat, or {@code null} if it is moored. */
    private WatchView.Walker rowerOf(WatchView view, long boat) {
        for (WatchView.Walker walker : view.walkers()) {
            if (walker.boatId() == boat) {
                // Our own position is a frame old in the view and current here;
                // prefer the live one, which is what stops the boat we are
                // rowing lagging a snapshot behind us.
                if (walker.id() == view.selfId()) {
                    return new WatchView.Walker(walker.id(), walker.name(), px, py, pz,
                            yaw, pitch, walker.stillness(), walker.crouching(),
                            walker.submerged(), walker.breath(), walker.boatId(),
                            walker.glass(), walker.debug());
                }
                return walker;
            }
        }
        return null;
    }

    /** This player, before the first snapshot has told us where we are. */
    private void drawSelf(Mesh.Builder mesh, double ox, double oy) {
        WalkerModel.walker(mesh, px - ox, py - oy, pz, yaw, crouching, gait,
                lastSpeed, WalkerModel.coatFor(session.selfId()),
                boatId != 0 ? -Boats.DECK * 0.4 : 0);
    }

    /**
     * The hands, and whatever is in them — <b>the only thing on screen built in
     * the camera's frame rather than the world's.</b>
     *
     * <p>Its own mesh with its own key, because its origin is the eye: it moves
     * and turns every frame whether or not the player does, and mixing it into
     * the dynamic mesh would mean rebuilding a clearing's worth of animals
     * every time somebody twitched the mouse.
     */
    private Mesh buildViewMesh() {
        Mesh.Builder mesh = Mesh.builder(eye.x(), eye.y(), eye.z(), false, frame);
        double sway = Math.min(1, lastSpeed / WatchPlayer.WALK_SPEED);
        // Underwater the hands sweep rather than swing, which is a slower clock
        // and a wider one; on land they follow the gait.
        double bob = submerged ? (gait * 0.6) % 1 : gait;
        WalkerModel.hands(mesh, 0, 0, 0, eye.dirX(), eye.dirY(), eye.dirZ(),
                eye.rightX(), eye.rightY(), bob, submerged ? 0.6 : sway,
                reach, WalkerModel.coatFor(session.selfId()));

        // What is being carried, in the right hand, when there is something
        // worth showing: a rod that is out, or the last thing picked up.
        String held = heldItem();
        if (held != null) {
            double forward = WalkerModel.HAND_FORWARD + reach * 0.42 + 0.10;
            double out = WalkerModel.HAND_SIDE;
            double down = WalkerModel.HAND_DROP - reach * 0.16;
            double[] up = new double[3];
            WalkerModel.cameraUp(eye.dirX(), eye.dirY(), eye.dirZ(),
                    eye.rightX(), eye.rightY(), up);
            double upX = up[0], upY = up[1], upZ = up[2];
            ItemModel.item(mesh, held,
                    eye.dirX() * forward + eye.rightX() * out + upX * -down,
                    eye.dirY() * forward + eye.rightY() * out + upY * -down,
                    eye.dirZ() * forward + upZ * -down,
                    1.0, Math.atan2(eye.dirX(), -eye.dirY()));
        }
        return mesh.build();
    }

    /**
     * What the right hand is holding, or {@code null} for an empty one.
     *
     * <p>A rod that is out beats everything, because a cast line is the one
     * piece of state in this game that a player has to be able to see they are
     * in. Otherwise it is whatever was picked up last, for as long as the
     * pickup flash lasts — which is the moment somebody actually wants to see
     * what they got.
     */
    private String heldItem() {
        WatchPlayer me = session.local() == null ? null
                : session.local().player(session.selfId());
        if (me != null && me.rod().active()) return "rod";
        if (pickedFlash <= 0 || flashedKey == null) return null;
        return view().satchel().has(flashedKey) ? flashedKey : null;
    }

    // --- the HUD ----------------------------------------------------------------------

    private void drawHud(DrawTarget target, WatchBiome biome, Weather weather) {
        WatchView view = view();
        int pad = 16;

        // Crosshair, and whatever is under it. The eyepiece draws its own,
        // finer, and two crosses on top of each other is one too many.
        if (panel == Panel.NONE && !glass.up()) {
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
        // The weather, on the same terms as the clock and for the same reason:
        // it decides what is out and how close it will let you get, so it has
        // to be readable at a glance rather than inferred from the sky.
        String sky = weather.describe();
        label(target, sky + weatherNote(weather), pad, pad + 56, HUD_SMALL,
                weather.visibility() < 0.6 ? HUD_WARN : HUD_INK);
        int points = view.guide().points();
        label(target, view.guide().discovered() + " / " + view.guide().total()
                        + " species · " + points + (points == 1 ? " pt" : " pts"),
                pad, pad + 74, HUD_SMALL, HUD_ACCENT);
        drawCompass(target, pad, pad + 96);

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
        String hint = boatId != 0 ? "Rowing — Y to step out"
                : crouching ? "Crouched — stay still and they will come back"
                : "Stillness";
        label(target, hint, viewportHeight > 0
                ? viewportWidth / 2 - target.textWidth(hint, HUD_SMALL) / 2 : 0,
                barY - 6, HUD_SMALL, HUD_DIM);

        // The breath, above the stillness and only when it matters. A bar that
        // is full and always on screen is a bar nobody reads; one that appears
        // the moment you put your head under is one nobody can miss.
        if (breath < 0.999) drawBreath(target, barX, barY - 24, barW, barH);

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
        drawReachHighlight(target);
        drawRod(target, me);
        drawPickedFlash(target);

        if (!prompt.isEmpty()) {
            label(target, prompt,
                    viewportWidth / 2 - target.textWidth(prompt, HUD_FONT) / 2,
                    viewportHeight / 2 + 42, HUD_FONT, HUD_INK);
        }
        if (panel == Panel.NONE) {
            String keys = "E use · F feeder · R plant · C cross · V rod · Y boat "
                    + "· B build · Tab satchel · G guide";
            if (view.satchel().has(Spyglass.ITEM)) {
                keys += " · Right-click glass";
            }
            label(target, keys, pad, viewportHeight - pad - 22, HUD_SMALL,
                    new Color(150, 168, 152));
        }
        drawGlassReadout(target);
        drawDebugReadout(target, biome);
    }

    /**
     * The debug readout: what the mode grants, and what the world is doing.
     *
     * <p><b>Down the left, under the compass</b>, because the top left is
     * already where this game puts "where and when" and this is more of that.
     * It is drawn on its own dark card rather than as shadowed text over the
     * world: it is nine lines of numbers and it has to be readable over a
     * sunlit lake, and unlike the rest of the HUD it is not something a player
     * is meant to be looking past.
     *
     * <p><b>This is the place a new debug feature announces itself.</b> A row
     * added to {@link Debug.Power} appears in the granted list without a line
     * here; a number worth watching goes in {@code lines} below. Both halves
     * are one edit each, which is the whole reason the readout exists rather
     * than a {@code System.out.println} somebody deletes afterwards.
     */
    private void drawDebugReadout(DrawTarget target, WatchBiome biome) {
        if (!debugging()) return;
        WatchView view = view();

        List<String> lines = new ArrayList<>();
        for (Debug.Power power : Debug.powers()) lines.add("· " + power.label());
        lines.add("");
        lines.add(String.format("at  %.1f, %.1f, %.1f", px, py, pz));
        WatchChunk here = streamer.chunkAt(px, py);
        lines.add("chunk  " + WatchChunk.chunkOf(px) + ", " + WatchChunk.chunkOf(py)
                + (here == null ? "  (not built)" : "  lod " + here.lod()));
        lines.add("ground  " + biome.displayName().toLowerCase()
                + (here == null ? ""
                        : " · " + here.surfaceAtWorld(px, py).name().toLowerCase()));
        ChunkStreamer.Focus focus = streamer.focus();
        lines.add("chunks  " + streamer.loadedCount() + " up · "
                + streamer.pending() + " queued · " + streamer.cachedCount() + " kept"
                + "   r" + streamer.viewRadius()
                + (focus == null ? "" : " +cone r" + focus.radius()));
        lines.add("frame  " + (renderer.acceleratedByGpu() ? "gpu" : "painter")
                + " · " + renderer.drawnTriangles() + " drawn · "
                + renderer.culledTriangles() + " culled");
        lines.add("alive  " + view.creatures().size() + " animals · "
                + view.lures().size() + " feeders · " + view.walkers().size() + " walking");
        lines.add("glass  " + (glass.up()
                ? "×" + Math.round(glass.power()) + " · " + Math.round(glass.range()) + " m"
                : "down"));
        lines.add("guide  " + view.guide().discovered() + " / " + view.guide().total()
                + " · " + view.guide().points() + " pts");

        int pad = 16;
        // Clear of the compass strip, which is the last thing the ordinary HUD
        // puts down the left.
        int top = pad + 128;
        int width = 268;
        int height = 28 + lines.size() * 15;
        target.fillRect(pad - 6, top - 16, width, height, new Color(10, 14, 20, 205));
        target.drawRect(pad - 6, top - 16, width, height, HUD_WARN);
        target.drawText("DEBUG  ·  code " + Debug.CODE + " again to stop",
                pad, top, HUD_SMALL, HUD_WARN);
        int row = top + 18;
        for (String line : lines) {
            if (!line.isEmpty()) {
                target.drawText(line, pad, row, HUD_SMALL,
                        line.startsWith("· ") ? HUD_ACCENT : HUD_DIM);
            }
            row += 15;
        }
    }

    /**
     * What the glass is doing: its power, its reach, and how steady it is.
     *
     * <p><b>Inside the bore</b>, near the bottom of it, where a scope puts its
     * own numbers — and where the walk's HUD is not. Below the tube it would
     * land on the satchel strip and the key hints, which is exactly where it
     * was first put and exactly why it moved: the two most-read lines on the
     * screen and the one line you raised the glass to read, all on top of each
     * other.
     *
     * <p>It answers the questions asked while looking through it — "am I at ×8
     * or ×15" and "is it settled enough to identify this". The steadiness bar
     * is the existing stillness stat seen from the other end, and putting it
     * here is what makes the connection between crouching and identifying a
     * distant bird something a player works out for themselves.
     */
    private void drawGlassReadout(DrawTarget target) {
        if (!glass.up() || panel != Panel.NONE) return;
        int cx = viewportWidth / 2, cy = viewportHeight / 2;
        int radius = boreRadius();
        int y = cy + (int) (radius * 0.62);

        String power = "×" + Math.round(glass.power())
                + "   ·   " + Math.round(glass.range()) + " m";
        label(target, power, cx - target.textWidth(power, HUD_BOLD) / 2, y,
                HUD_BOLD, HUD_ACCENT);

        double steady = glass.steadiness();
        int barW = 120, barH = 4;
        int barX = cx - barW / 2, barY = y + 10;
        target.fillRect(barX, barY, barW, barH, new Color(0, 0, 0, 140));
        target.fillRect(barX, barY, (int) (barW * steady), barH,
                steady > 0.6 ? HUD_ACCENT : HUD_WARN);
        if (steady < 0.6) {
            String hint = "Crouch and stand still to steady it";
            label(target, hint, cx - target.textWidth(hint, HUD_SMALL) / 2, barY + 18,
                    HUD_SMALL, HUD_DIM);
        }
    }

    /** How wide the tube's bore is on screen, in pixels. */
    private int boreRadius() {
        return (int) (Math.min(viewportWidth, viewportHeight)
                * (0.30 + 0.13 * glass.deployment()));
    }

    /** What the weather is doing to the watching, in a few words. */
    private static String weatherNote(Weather weather) {
        if (weather.flushScale() < 0.75) return "  ·  they will let you close";
        if (weather.flushScale() > 1.1) return "  ·  everything is jumpy";
        if (weather.activity() > 1.05) return "  ·  plenty about";
        if (weather.activity() < 0.7) return "  ·  little about";
        return "";
    }

    /**
     * A compass strip, because a world with no edge has no landmarks either.
     *
     * <p>Eight points across a fixed width, sliding under a fixed marker. This
     * is the cheapest possible orientation aid and the game badly wanted one:
     * "the lake is north of the camp" is the only way anybody can describe
     * where anything is, and before this there was no way to know which way
     * north was.
     */
    private void drawCompass(DrawTarget target, int x, int y) {
        int width = 190, height = 16;
        target.fillRect(x, y - height + 4, width, height, new Color(0, 0, 0, 90));
        String[] points = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
        for (int i = 0; i < points.length; i++) {
            // Where this point sits relative to the way we are looking, in
            // radians, wrapped into (−π, π].
            double bearing = i * Math.PI / 4;
            double delta = bearing - yaw;
            delta = Math.atan2(Math.sin(delta), Math.cos(delta));
            // Only the half-turn in front of us is on the strip.
            if (Math.abs(delta) > Math.PI / 2) continue;
            int at = (int) (x + width / 2.0 + delta / (Math.PI / 2) * (width / 2.0));
            boolean cardinal = i % 2 == 0;
            String text = points[i];
            label(target, text, at - target.textWidth(text, HUD_SMALL) / 2, y,
                    HUD_SMALL, cardinal ? HUD_INK : HUD_DIM);
        }
        target.fillRect(x + width / 2, y - height + 4, 1, height, HUD_ACCENT);
    }

    /** The breath meter — blue while there is air, amber while there is not. */
    private void drawBreath(DrawTarget target, int x, int y, int width, int height) {
        target.fillRect(x, y, width, height, new Color(0, 0, 0, 140));
        Color ink = breath > 0.3 ? new Color(120, 190, 235) : HUD_WARN;
        target.fillRect(x, y, (int) (width * breath), height, ink);
        String text = breath <= 0 ? "Out of air — surfacing" : "Breath";
        label(target, text, x + width / 2 - target.textWidth(text, HUD_SMALL) / 2,
                y - 5, HUD_SMALL, breath <= 0 ? HUD_WARN : HUD_DIM);
    }

    /**
     * A glow round whatever E would take, and a ring inside it.
     *
     * <p>The other half of {@link WatchGame#pickTarget}: the glow says
     * <em>which</em> thing, the prompt says what would happen to it. Drawn in
     * the same shape as a spotlight so the two read as one language, and dimmer
     * so an animal somebody has pointed at always wins the eye.
     *
     * <p><b>The halo is what makes it work on small things.</b> A ring on its
     * own was enough while everything that could be picked up was a bush or a
     * tree, which is to say a metre across and hard to miss. A quartz pebble in
     * the shingle is eight centimetres and the same colour as the shingle: an
     * outline round it is an outline round nothing anybody has spotted yet. Four
     * concentric ovals of decreasing alpha cost four draws and make the thing
     * itself light up, which is the difference between "there is a marker here"
     * and "that stone is worth picking up".
     */
    private void drawReachHighlight(DrawTarget target) {
        if (inReach == null || panel != Panel.NONE) return;
        double[] point = new double[3];
        if (!eye.project(inReach.x(), inReach.y(), inReach.z(), point)) return;
        // A floor in metres so a small thing still gets a ring, and a floor in
        // pixels so a distant one does not vanish. The metre floor was 0.25 —
        // wider than an acorn, a pebble or a seed head, so every one of them
        // was ringed as though it were a bush.
        int radius = (int) Math.max(10,
                eye.scaleAt(point[2]) * Math.max(0.08, inReach.radius()));
        int cx = (int) point[0], cy = (int) point[1];
        // A slow pulse, so it reads as live rather than as part of the scenery.
        double pulse = 0.5 + 0.5 * Math.sin(frame * 0.09);
        int alpha = (int) (110 + 60 * pulse);

        // The halo: filled discs from the outside in, each faint enough that a
        // dozen of them would still not hide what is underneath.
        for (int i = GLOW_RINGS; i >= 1; i--) {
            int r = (int) (radius * (1.15 + 0.42 * i / (double) GLOW_RINGS
                    + 0.06 * pulse));
            int wash = (int) (11 * (1 - (i - 1) / (double) GLOW_RINGS) + 4);
            target.fillOval(cx - r, cy - r, r * 2, r * 2,
                    new Color(226, 244, 186, wash));
        }
        target.drawOval(cx - radius, cy - radius, radius * 2, radius * 2,
                new Color(230, 240, 210, alpha), 2f);
        // Four corner ticks, which is what makes a circle read as a selection
        // rather than as a hoop somebody left in a tree.
        int tick = Math.max(4, radius / 3);
        Color ink = new Color(230, 240, 210, Math.min(255, alpha + 60));
        target.fillRect(cx - radius, cy - 1, tick, 2, ink);
        target.fillRect(cx + radius - tick, cy - 1, tick, 2, ink);
        target.fillRect(cx - 1, cy - radius, 2, tick, ink);
        target.fillRect(cx - 1, cy + radius - tick, 2, tick, ink);
    }

    /** How many washes make the halo under a highlighted thing. */
    private static final int GLOW_RINGS = 4;

    /** What just went in the satchel, under the crosshair, fading. */
    private void drawPickedFlash(DrawTarget target) {
        if (pickedFlash <= 0 || pickedName.isEmpty()) return;
        int alpha = (int) Math.min(255, 255 * Math.min(1, pickedFlash / 0.6));
        Color ink = new Color(HUD_ACCENT.getRed(), HUD_ACCENT.getGreen(),
                HUD_ACCENT.getBlue(), alpha);
        int rise = (int) ((1.6 - pickedFlash) * 14);
        label(target, pickedName,
                viewportWidth / 2 - target.textWidth(pickedName, HUD_BOLD) / 2,
                viewportHeight / 2 - 70 - rise, HUD_BOLD, ink);
    }

    /**
     * Rain, snow and fog, over the finished world.
     *
     * <p>Drawn as a 2D overlay rather than as geometry, and deliberately. A
     * hundred thousand raindrops as world triangles is a hundred thousand
     * triangles through the painter's sort on a machine that has no card, which
     * is the machine this build must work on. Streaks in screen space cost one
     * line each, look the same on both backends, and — because the seed is the
     * frame — never repeat a pattern.
     */
    private void drawWeatherOverlay(DrawTarget target, Weather weather) {
        if (submerged) {
            // Under water there is no weather, there is water: a wash of the
            // depth's own colour, heavier the deeper you are.
            int alpha = (int) Math.min(150, 40 + dive * 22);
            target.fillRect(0, 0, viewportWidth, viewportHeight,
                    new Color(18, 58, 78, alpha));
            return;
        }
        double intensity = weather.intensity();
        if (intensity <= 0.01) return;
        Weather.Condition condition = weather.condition();

        if (condition == Weather.Condition.FOG || weather.previous()
                == Weather.Condition.FOG) {
            double fogAmount = condition == Weather.Condition.FOG
                    ? weather.blend() : 1 - weather.blend();
            target.fillRect(0, 0, viewportWidth, viewportHeight,
                    new Color(206, 212, 216, (int) (110 * fogAmount)));
        }
        if (!condition.precipitates()) return;

        int count = weather.particleCount(viewportWidth, viewportHeight);
        if (count <= 0) return;
        boolean snow = condition.frozen();
        Color ink = snow ? new Color(238, 244, 250, 200)
                : new Color(178, 206, 226, 130);
        // A cheap deterministic scatter: one multiply per drop, seeded on the
        // frame so the field moves without anybody having to keep an array of
        // particles alive between frames.
        long hash = frame * 0x9E3779B97F4A7C15L;
        int fall = snow ? 3 : 26;
        int drift = (int) (Math.sin(frame * 0.03) * (snow ? 9 : 3));
        for (int i = 0; i < count; i++) {
            hash = hash * 6364136223846793005L + 1442695040888963407L;
            int dx = (int) Math.floorMod(hash >>> 17, Math.max(1, viewportWidth));
            int dy = (int) Math.floorMod(hash >>> 33, Math.max(1, viewportHeight));
            if (snow) {
                target.fillRect(dx, dy, 2, 2, ink);
            } else {
                target.drawLine(dx, dy, dx + drift, dy + fall, ink, 1f);
            }
        }
        if (condition == Weather.Condition.STORM && (frame / 3) % 47 == 0) {
            // Lightning: two frames of a pale wash, rare enough to startle.
            target.fillRect(0, 0, viewportWidth, viewportHeight,
                    new Color(255, 255, 245, 70));
        }
    }

    /**
     * What looking through a tube looks like.
     *
     * <p><b>Drawn as a mask over a finished frame, and it is not the zoom.</b>
     * The magnification already happened, in the projection, before a single
     * triangle was transformed — this is only the round hole you are seeing it
     * through. Anybody reading this file to find out how the spyglass works
     * should be reading {@link Spyglass} and {@link #placeCamera}; this method
     * would be just as correct if it drew nothing, and the view would be just
     * as magnified.
     *
     * <p>The hole is cut by filling the two rectangles either side of the
     * circle on each band of scanlines. Fifty bands is a hundred fills for a
     * shape no primitive in {@link DrawTarget} can express — cheaper than an
     * image mask, exact enough that nobody can see the steps at four pixels a
     * band, and it works identically on both backends because it is nothing but
     * rectangles.
     */
    private void drawEyepiece(DrawTarget target) {
        double open = glass.deployment();
        if (!glass.up() || open <= 0.01) return;

        int cx = viewportWidth / 2, cy = viewportHeight / 2;
        // The tube's own bore, which widens as the glass comes up to the eye —
        // so raising it reads as a tube arriving rather than as a hole opening.
        int radius = boreRadius();
        int alpha = (int) (245 * Math.min(1, open * 1.4));
        int surround = (alpha << 24);

        int band = 4;
        for (int y = 0; y < viewportHeight; y += band) {
            int dy = y + band / 2 - cy;
            int halfWidth = Math.abs(dy) >= radius ? 0
                    : (int) Math.sqrt((double) radius * radius - (double) dy * dy);
            int left = cx - halfWidth, right = cx + halfWidth;
            if (left > 0) target.fillRect(0, y, left, band, surround);
            if (right < viewportWidth) {
                target.fillRect(right, y, viewportWidth - right, band, surround);
            }
        }

        // The brass, and the soft edge of the field of view inside it. Three
        // rings rather than one: a single hard circle over a landscape reads as
        // a cut-out sticker.
        int inner = (int) (140 * open);
        target.drawOval(cx - radius, cy - radius, radius * 2, radius * 2,
                new Color(0, 0, 0, inner), 6f);
        target.drawOval(cx - radius, cy - radius, radius * 2, radius * 2,
                new Color(176, 138, 60, (int) (210 * open)), 2.5f);
        int flare = radius + 4;
        target.drawOval(cx - flare, cy - flare, flare * 2, flare * 2,
                new Color(232, 206, 150, (int) (70 * open)), 1.5f);

        drawReticle(target, cx, cy, radius, open);
    }

    /**
     * The scale in the eyepiece: a fine cross, and ticks that say how wide the
     * view is.
     *
     * <p>The ticks are a <b>real</b> scale — they are drawn a fixed number of
     * degrees apart, worked out from the field of view the camera is actually
     * using — so the gaps close up as the tube is drawn out, and the whole
     * reticle is a readout of the magnification rather than decoration. Half a
     * degree at ×15 is about eight metres at half a kilometre, which is the
     * kind of judgement this instrument exists to support.
     */
    private void drawReticle(DrawTarget target, int cx, int cy, int radius, double open) {
        Color ink = new Color(228, 236, 224, (int) (150 * open));
        int arm = radius / 5;
        target.drawLine(cx - arm, cy, cx - arm / 3, cy, ink, 1f);
        target.drawLine(cx + arm / 3, cy, cx + arm, cy, ink, 1f);
        target.drawLine(cx, cy - arm, cx, cy - arm / 3, ink, 1f);
        target.drawLine(cx, cy + arm / 3, cx, cy + arm, ink, 1f);

        double degrees = Math.toDegrees(glass.fov(EyeCamera.DEFAULT_FOV));
        if (degrees <= 0.01) return;
        // Pixels per half a degree, from the pixels the whole field of view is
        // worth. The camera measures its field vertically; so does this.
        double perTick = viewportHeight * 0.5 / degrees;
        if (perTick < 6) return;
        for (int i = 1; i * perTick < radius * 0.92; i++) {
            int at = (int) (i * perTick);
            int length = i % 2 == 0 ? 7 : 4;
            target.drawLine(cx + at, cy - length, cx + at, cy + length, ink, 1f);
            target.drawLine(cx - at, cy - length, cx - at, cy + length, ink, 1f);
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
            sb.append(satchel.countLabel(key)).append("× ").append(Forage.nameOf(key));
        }
        if (satchel.kinds() > 6) sb.append("   +").append(satchel.kinds() - 6).append(" more");
        // Half the screen, so a satchel full of long names does not run into
        // the party log along the other side.
        label(target, fitted(target, sb.toString(), viewportWidth / 2 - x),
                x, baseline, HUD_SMALL, HUD_DIM);
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

    /** Pixels between rows in a scrolling list — tall enough to hold a picture. */
    private static final int ROW_HEIGHT = 28;

    /** The picture beside a row, in pixels. */
    private static final int ICON = ROW_HEIGHT - 6;

    /** The picture in the footer, of whichever row the cursor is on. */
    private static final int DETAIL_ICON = 60;

    /** What a portrait is rendered over — the panel's own dark card. */
    private static final int ICON_BACKDROP = 0x121C17;

    /**
     * Where the satchel screen's parts are.
     *
     * <p>Worked out in one place because two methods need it and they must
     * agree exactly: {@link #drawSatchel} paints the rows and
     * {@link #updateSatchel} decides which one the pointer is over. A panel
     * whose hit boxes are computed separately from its drawing is a panel that
     * selects the row above the one you clicked on, for ever, on some window
     * sizes and not others.
     */
    private record SatchelBox(int x, int y, int w, int h, int listTop, int rows,
                              int colWidth) {

        int leftX() { return x + 20; }

        int rightX() { return x + w / 2 + 14; }

        /** The left edge of a column: {@code 0} carrying, {@code 1} recipes. */
        int columnX(int column) { return column == 1 ? rightX() : leftX(); }

        int barTop() { return listTop; }

        int barHeight() { return rows * ROW_HEIGHT; }

        int barX(int column) { return columnX(column) + colWidth - 5; }

        /** The top of a visible row's band. */
        int rowTop(int row) { return listTop + row * ROW_HEIGHT; }

        /** Which list a point is over: {@code 0} carrying, {@code 1} recipes, {@code −1} neither. */
        int columnAt(int mx, int my) {
            if (my < barTop() || my >= barTop() + barHeight()) return -1;
            if (mx >= leftX() - 8 && mx < leftX() + colWidth) return 0;
            if (mx >= rightX() - 8 && mx < rightX() + colWidth) return 1;
            return -1;
        }

        /** Which visible row a {@code y} lands on, or {@code −1}. */
        int rowAt(int my) {
            int row = (my - listTop) / ROW_HEIGHT;
            return my >= listTop && row < rows ? row : -1;
        }

        boolean overBar(int mx, int my, int column) {
            int bx = barX(column);
            return mx >= bx - 4 && mx <= bx + 7
                    && my >= barTop() && my < barTop() + barHeight();
        }

        boolean overClose(int mx, int my) {
            return mx >= x + w - 38 && mx <= x + w - 12
                    && my >= y + 12 && my <= y + 38;
        }
    }

    private SatchelBox satchelBox() {
        int w = Math.min(880, Math.max(320, viewportWidth - 80));
        int h = Math.min(560, Math.max(240, viewportHeight - 80));
        int x = (viewportWidth - w) / 2, y = (viewportHeight - h) / 2;
        int listTop = y + 82;
        int listBottom = y + h - DETAIL_ICON - 26;
        int rows = Math.max(1, (listBottom - listTop) / ROW_HEIGHT);
        return new SatchelBox(x, y, w, h, listTop, rows, w / 2 - 34);
    }

    private void drawSatchel(DrawTarget target) {
        SatchelBox box = satchelBox();
        int x = box.x(), y = box.y(), w = box.w(), h = box.h();
        target.fillRect(x, y, w, h, HUD_PANEL);
        target.drawRect(x, y, w, h, HUD_ACCENT);
        target.drawText("Satchel & Cooking", x + 20, y + 32, TITLE_FONT, HUD_INK);
        drawCloseButton(target, box);

        Satchel satchel = view().satchel();
        List<String> items = satchel.keys();
        List<Recipes.Recipe> recipes = Recipes.all();
        // Only bounded here, never pulled back to the cursor: the wheel and the
        // bar move the window on its own, and a draw that dragged it back to
        // wherever the cursor happened to be would undo the scroll every frame.
        satchelScroll = boundScroll(satchelScroll, items.size(), box.rows());
        recipeScroll = boundScroll(recipeScroll, recipes.size(), box.rows());

        // --- carrying ------------------------------------------------------
        int col = box.leftX();
        String carrying = satchel.bottomless()
                ? "Carrying  (everything — debug)"
                : "Carrying  (" + satchel.kinds() + " kinds, "
                        + satchel.total() + " things)";
        target.drawText(carrying, col, y + 68, HUD_BOLD,
                recipeColumn ? HUD_DIM : HUD_ACCENT);
        for (int i = 0; i < box.rows(); i++) {
            int index = satchelScroll + i;
            if (index >= items.size()) break;
            String key = items.get(index);
            Forage.Item item = Forage.byKey(key);
            int top = box.rowTop(i);
            int text = top + ROW_HEIGHT - 9;
            if (index == satchelIndex && !recipeColumn) {
                target.fillRect(col - 8, top, box.colWidth(), ROW_HEIGHT - 2,
                        new Color(60, 110, 70, 160));
            }
            // Its own model, not a coloured square: forty rows of "Berry" and
            // "Seed" tell you what category a thing is and nothing about what
            // it is. See ItemPortrait.
            target.drawImage(ItemPortrait.of(key, ICON, ICON_BACKDROP),
                    col, top + 3, ICON, ICON);
            target.drawText(satchel.countLabel(key) + "×", col + ICON + 8, text,
                    HUD_FONT, HUD_ACCENT);
            target.drawText(Forage.nameOf(key), col + ICON + 44, text, HUD_FONT, HUD_INK);
            if (item != null) {
                target.drawText(item.kind().label(), col + box.colWidth() - 96, text,
                        HUD_SMALL, HUD_DIM);
            }
        }
        if (items.isEmpty()) {
            target.drawText("Nothing yet — press E out there", col,
                    box.rowTop(0) + 18, HUD_SMALL, HUD_DIM);
        }
        scrollbar(target, box.barX(0), box.barTop(), box.barHeight(), satchelScroll,
                box.rows(), items.size());

        // --- recipes -------------------------------------------------------
        int rx = box.rightX();
        target.drawText("Recipes", rx, y + 68, HUD_BOLD,
                recipeColumn ? HUD_ACCENT : HUD_DIM);
        for (int i = 0; i < box.rows(); i++) {
            int index = recipeScroll + i;
            if (index >= recipes.size()) break;
            Recipes.Recipe recipe = recipes.get(index);
            boolean can = recipe.affordable(satchel);
            int top = box.rowTop(i);
            int text = top + ROW_HEIGHT - 9;
            if (index == recipeIndex && recipeColumn) {
                target.fillRect(rx - 8, top, box.colWidth(), ROW_HEIGHT - 2,
                        new Color(60, 110, 70, 160));
            }
            target.drawImage(ItemPortrait.of(recipe.output(), ICON, ICON_BACKDROP),
                    rx, top + 3, ICON, ICON);
            target.drawText(recipe.name(), rx + ICON + 8, text, HUD_FONT,
                    can ? HUD_INK : new Color(130, 140, 132));
            // Clipped to the column rather than drawn over the panel's own
            // edge and out into the world, which is what a four-ingredient
            // recipe did. Whichever row the cursor is on says it in full along
            // the footer, so nothing is actually hidden.
            int costX = rx + ICON + 152;
            target.drawText(fitted(target, recipe.costLine(), rx + box.colWidth() - 10 - costX),
                    costX, text, HUD_SMALL, can ? HUD_DIM : new Color(120, 110, 100));
        }
        scrollbar(target, box.barX(1), box.barTop(), box.barHeight(), recipeScroll,
                box.rows(), recipes.size());

        drawSatchelFooter(target, box, satchel, items, recipes);
        String keys = "Click to use · wheel to scroll · ↑↓←→ · Enter · Tab close";
        target.drawText(keys, x + w - target.textWidth(keys, HUD_SMALL) - 46, y + 32,
                HUD_SMALL, HUD_DIM);
    }

    /**
     * The footer: a big picture of whatever the cursor is on, and what it is
     * for.
     *
     * <p>The one place on the screen with room to draw an item properly, which
     * is what makes the row icons legible rather than decorative — the small
     * one says which row, and this says what the thing actually looks like.
     */
    private void drawSatchelFooter(DrawTarget target, SatchelBox box, Satchel satchel,
                                   List<String> items, List<Recipes.Recipe> recipes) {
        int x = box.x(), y = box.y(), w = box.w(), h = box.h();
        int top = y + h - DETAIL_ICON - 16;
        target.fillRect(x + 12, top - 8, w - 24, 1, new Color(90, 120, 96, 120));

        String key = null;
        String title = "";
        String note = "";
        if (recipeColumn && !recipes.isEmpty()) {
            Recipes.Recipe recipe = recipes.get(Math.min(recipeIndex, recipes.size() - 1));
            key = recipe.output();
            title = recipe.name() + "  ·  " + recipe.station().label()
                    + (recipe.affordable(satchel) ? "" : "  ·  short of ingredients");
            // The cost in full, because the list's copy of it may have been
            // cut to fit the column.
            note = recipe.costLine() + "  —  " + recipe.note();
        } else if (!items.isEmpty()) {
            key = items.get(Math.min(satchelIndex, items.size() - 1));
            Forage.Item item = Forage.byKey(key);
            title = Forage.nameOf(key)
                    + (item == null ? "" : "  ·  " + item.kind().label());
            note = item == null ? "" : item.note();
        }
        if (key == null) return;

        target.drawImage(ItemPortrait.of(key, DETAIL_ICON, ICON_BACKDROP),
                x + 20, top, DETAIL_ICON, DETAIL_ICON);
        target.drawRect(x + 20, top, DETAIL_ICON, DETAIL_ICON, new Color(70, 96, 76));
        int tx = x + 28 + DETAIL_ICON;
        int width = x + w - 20 - tx;
        target.drawText(fitted(target, title, width), tx, top + 20, HUD_BOLD, HUD_INK);
        target.drawText(fitted(target, note, width), tx, top + 42, HUD_SMALL, HUD_DIM);
    }

    /** The ✕ every panel that the mouse drives needs in its corner. */
    private void drawCloseButton(DrawTarget target, SatchelBox box) {
        boolean over = box.overClose(pointerX, pointerY);
        int bx = box.x() + box.w() - 38, by = box.y() + 12;
        target.fillRect(bx, by, 26, 26,
                over ? new Color(90, 40, 40, 200) : new Color(30, 44, 36, 160));
        target.drawRect(bx, by, 26, 26, over ? HUD_WARN : new Color(80, 104, 86));
        target.drawText("✕", bx + 8, by + 19, HUD_FONT, over ? HUD_WARN : HUD_DIM);
    }

    /**
     * A line cut to a width, with an ellipsis where it was cut.
     *
     * <p>Measured against the target's own font metrics rather than guessed
     * from a character count, because {@code HUD_SMALL} is proportional and
     * "1 × Sap, 1 × Stone" and "1 × Millet, 1 × Wild Rice" are the same number
     * of characters and nothing like the same number of pixels.
     */
    private static String fitted(DrawTarget target, String text, int width) {
        if (text == null || text.isEmpty() || width <= 0) return "";
        if (target.textWidth(text, HUD_SMALL) <= width) return text;
        int end = text.length();
        while (end > 1 && target.textWidth(text.substring(0, end) + "…", HUD_SMALL) > width) {
            end--;
        }
        return text.substring(0, end) + "…";
    }

    /**
     * Where a scrolling window should start so the cursor is inside it.
     *
     * <p>Two lines of margin at each end, so the list moves before the cursor
     * reaches the edge and the player can always see what is coming.
     */
    private static int clampScroll(int scroll, int cursor, int total, int rows) {
        if (total <= rows) return 0;
        int margin = Math.min(2, rows / 3);
        int at = Math.max(0, Math.min(scroll, total - rows));
        if (cursor - margin < at) at = Math.max(0, cursor - margin);
        if (cursor + margin >= at + rows) {
            at = Math.min(total - rows, cursor + margin - rows + 1);
        }
        return Math.max(0, Math.min(at, total - rows));
    }

    /** A thumb on the right of a list, drawn only when there is more than fits. */
    private void scrollbar(DrawTarget target, int x, int y, int height, int scroll,
                           int rows, int total) {
        if (total <= rows || height <= 0) return;
        target.fillRect(x, y, 3, height, new Color(255, 255, 255, 32));
        int thumb = Math.max(14, (int) (height * (rows / (double) total)));
        int at = (int) (y + (height - thumb) * (scroll / (double) (total - rows)));
        target.fillRect(x, at, 3, thumb, new Color(140, 208, 150, 160));
    }

    private void drawBuild(DrawTarget target) {
        SatchelBox box = buildBox();
        int x = box.x(), y = box.y(), w = box.w(), h = box.h();
        target.fillRect(x, y, w, h, HUD_PANEL);
        target.drawRect(x, y, w, h, HUD_ACCENT);
        target.drawText("Build", x + 20, y + 32, TITLE_FONT, HUD_INK);
        drawCloseButton(target, box);

        Satchel satchel = view().satchel();
        List<BuildPiece> pieces = BuildPiece.all();
        for (int i = 0; i < pieces.size(); i++) {
            BuildPiece piece = pieces.get(i);
            boolean can = piece.affordable(satchel);
            int top = box.rowTop(i);
            int text = top + ROW_HEIGHT - 9;
            if (i == pieceIndex) {
                target.fillRect(x + 14, top, w - 28, ROW_HEIGHT - 2,
                        new Color(60, 110, 70, 160));
            }
            target.drawText(piece.displayName(), x + 20, text, HUD_FONT,
                    can ? HUD_INK : new Color(130, 140, 132));
            target.drawText(piece.costLine(), x + 190, text, HUD_SMALL,
                    can ? HUD_DIM : new Color(120, 110, 100));
        }
        BuildPiece chosen = pieces.get(pieceIndex);
        target.drawText(fitted(target, chosen.note(), w - 40), x + 20, y + h - 44,
                HUD_SMALL, HUD_DIM);
        String facing = "Facing " + (pieceTurn * 45) + "°"
                + (pieceInTree ? " · fixed to the nearest trunk" : " · on the ground");
        target.drawText(facing, x + 20, y + h - 26, HUD_SMALL,
                pieceInTree && !chosen.anchors() ? HUD_WARN : HUD_ACCENT);
        String keys = "Click to build · X turn · ←→ ground/tree · B close";
        target.drawText(keys, x + w - target.textWidth(keys, HUD_SMALL) - 46, y + 32,
                HUD_SMALL, HUD_DIM);
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

    /** Which overlay is up, in lower case, or {@code "none"} — for tests. */
    public String panelName() { return panel.name().toLowerCase(java.util.Locale.ROOT); }

    /**
     * What the satchel screen's cursor is on — an item key in the carrying
     * column, a recipe's output in the cooking one, or {@code null} for an
     * empty list. For tests.
     */
    public String panelCursor() {
        if (recipeColumn) {
            List<Recipes.Recipe> recipes = Recipes.all();
            return recipes.isEmpty() ? null
                    : recipes.get(Math.min(recipeIndex, recipes.size() - 1)).output();
        }
        List<String> items = view().satchel().keys();
        return items.isEmpty() ? null
                : items.get(Math.min(satchelIndex, items.size() - 1));
    }

    /** How far down its list the column with the cursor is scrolled — for tests. */
    public int panelScroll() { return recipeColumn ? recipeScroll : satchelScroll; }

    /** What is in reach of the local player this frame, or {@code null} — for tests. */
    public WatchGame.Pickable inReach() { return inReach; }

    /** The camera, so a test can check where it ended up. */
    public EyeCamera camera() { return eye; }

    /** The renderer, so a test can read its counters. */
    public WatchRenderer renderer() { return renderer; }

    /** Where the local player is standing. */
    public double[] position() { return new double[]{px, py, pz}; }
}
