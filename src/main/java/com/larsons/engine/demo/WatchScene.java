package com.larsons.engine.demo;

import com.larsons.engine.config.GameContext;
import com.larsons.engine.config.PlayerSettings;
import com.larsons.engine.graphics.EyeCamera;
import com.larsons.engine.graphics.MeshPass;
import com.larsons.engine.graphics.draw.DrawTarget;
import com.larsons.engine.input.GameAction;
import com.larsons.engine.input.InputManager;
import com.larsons.engine.input.KeyBinds;
import com.larsons.engine.input.Pointer;
import com.larsons.engine.scene.AbstractScene;
import com.larsons.engine.watch.Boats;
import com.larsons.engine.watch.Bounty;
import com.larsons.engine.watch.Cartography;
import com.larsons.engine.watch.Chart;
import com.larsons.engine.watch.Cosmetics;
import com.larsons.engine.watch.Cultivation;
import com.larsons.engine.watch.Debug;
import com.larsons.engine.watch.FieldGuide;
import com.larsons.engine.watch.Fishing;
import com.larsons.engine.watch.Forage;
import com.larsons.engine.watch.Litter;
import com.larsons.engine.watch.Lure;
import com.larsons.engine.watch.Recipes;
import com.larsons.engine.watch.Satchel;
import com.larsons.engine.watch.Shops;
import com.larsons.engine.watch.Spill;
import com.larsons.engine.watch.Spotlight;
import com.larsons.engine.watch.Spyglass;
import com.larsons.engine.watch.Tag;
import com.larsons.engine.watch.Trading;
import com.larsons.engine.watch.WatchClock;
import com.larsons.engine.watch.WatchGame;
import com.larsons.engine.watch.WatchPlayer;
import com.larsons.engine.watch.WatchSounds;
import com.larsons.engine.watch.WatchStore;
import com.larsons.engine.watch.WatchView;
import com.larsons.engine.watch.Weather;
import com.larsons.engine.watch.home.HouseKit;
import com.larsons.engine.watch.home.HousePlan;
import com.larsons.engine.watch.home.Homestead;
import com.larsons.engine.watch.life.AnimalDef;
import com.larsons.engine.watch.life.AnimalModels;
import com.larsons.engine.watch.life.AnimalRegistry;
import com.larsons.engine.watch.life.AnimalSkins;
import com.larsons.engine.watch.life.Hurl;
import com.larsons.engine.watch.life.Mutants;
import com.larsons.engine.watch.light.LightField;
import com.larsons.engine.watch.light.LightKind;
import com.larsons.engine.watch.light.PlacedLight;
import com.larsons.engine.watch.light.SkyLight;
import com.larsons.engine.watch.net.WatchSession;
import com.larsons.engine.watch.render.AnimalPortrait;
import com.larsons.engine.watch.render.BoardImage;
import com.larsons.engine.watch.render.BoatModel;
import com.larsons.engine.watch.render.ChartImage;
import com.larsons.engine.watch.render.FloraMesher;
import com.larsons.engine.watch.render.Gait;
import com.larsons.engine.watch.render.HouseModel;
import com.larsons.engine.watch.render.ItemModel;
import com.larsons.engine.watch.render.ItemPortrait;
import com.larsons.engine.watch.render.KeeperModel;
import com.larsons.engine.watch.render.LightModel;
import com.larsons.engine.watch.render.Mesh;
import com.larsons.engine.watch.render.RangerModel;
import com.larsons.engine.watch.render.RowStroke;
import com.larsons.engine.watch.render.Shapes;
import com.larsons.engine.watch.render.ShopModel;
import com.larsons.engine.watch.render.Sparks;
import com.larsons.engine.watch.render.TrackMesher;
import com.larsons.engine.watch.render.WalkerModel;
import com.larsons.engine.watch.render.WatchRenderer;
import com.larsons.engine.watch.world.ChunkStreamer;
import com.larsons.engine.watch.world.Flora;
import com.larsons.engine.watch.world.TerrainField;
import com.larsons.engine.watch.world.TrackField;
import com.larsons.engine.watch.world.TreeInstance;
import com.larsons.engine.watch.world.WatchBiome;
import com.larsons.engine.watch.world.WatchChunk;
import com.larsons.engine.watch.world.WatchMaterial;
import com.larsons.engine.watch.world.WatchMaterials;

import java.awt.Color;
import java.awt.Font;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
     * How fast a walker leaves the ground, in metres per second.
     *
     * <p>With {@link #GRAVITY}, a jump peaks at {@code v²/2g} — about eighty
     * centimetres — and is in the air for {@code 2v/g}, about two thirds of a
     * second. Both numbers are chosen from the other end: eighty centimetres is
     * a boulder or a fallen trunk, which is what there is to get on top of in
     * this world, and two thirds of a second is long enough to read as a jump
     * and short enough not to interrupt a walk.
     */
    private static final double JUMP_SPEED = 4.6;

    /** …and what pulls them back, in metres per second per second. */
    private static final double GRAVITY = 13.5;

    /** How far the eye dips at the bottom of a full landing, in metres. */
    private static final double LANDING_DIP = 0.20;

    /**
     * How far under the surface a floating swimmer's feet sit, in metres.
     *
     * <p>Chest-deep: {@link WalkerModel#HEIGHT} is 1.78 and the eye is at 1.68,
     * so a swimmer whose feet are 1.2 below the waterline has their head above
     * it, which is what "swimming" has to look like before diving means
     * anything.
     *
     * <p>Public because {@link WalkerModel#swimmer} depends on it without being
     * told it: a swimmer's body is laid down about the hips, which this number
     * puts at the right depth for the head to end up at the waterline and the
     * shoulders under it. Changing it moves how high a swimmer floats, and
     * {@code SwimCycleTest} is what says so out loud.
     */
    public static final double FLOAT_DEPTH = 1.2;

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

    /**
     * How far off the party's own trodden tracks are still drawn, in metres —
     * on a card, and on a machine drawing through Java2D.
     *
     * <p>A track is two triangles per stride and the party lays a stride every
     * {@link TrackField#STRIDE} metres, so what this range costs is not a
     * property of the world but of how much walking has been done inside it.
     * The pathological case is a party that has spent the last ten minutes
     * circling one clearing, which at eight walkers is a few thousand quads —
     * trivial on a card and most of a painter's frame, so the painter is given
     * a ring it cannot be surprised inside.
     *
     * <p>The painter's ring is also inside the one chunk it meshes at full
     * detail, which is not a coincidence: a decal is sorted in front of the
     * ground it lies on by {@link TrackMesher#SORT_BIAS}, and that number was
     * chosen against a two-metre ground quad. Out where the ground is meshed
     * coarser the bias would be too small to work, so the painter simply does
     * not draw tracks out there.
     */
    private static final double TRACK_RANGE_GPU = 120;

    private static final double TRACK_RANGE_PAINTER = 30;

    /**
     * How far the player walks, and how long they stand still, before the track
     * sheet is rebuilt — metres, and seconds.
     *
     * <p><b>Not per frame</b>, on the same reasoning as the litter sweep. The
     * sheet is thousands of quads whose corners each cost a bilinear read of
     * the heightfield, and almost nothing about it changes between two frames a
     * sixtieth of a second apart: a track fades over ten minutes, so four
     * rebuilds a second is two and a half thousand steps of a fade nobody can
     * see one step of. It also means a card re-uploads the buffer four times a
     * second rather than sixty — see {@link Mesh#revision()}, which is what
     * decides that.
     */
    private static final double TRACK_RESTEP = 3;

    private static final double TRACK_REFRESH = 0.25;

    /**
     * How far off a trading post is drawn at all, and the three ranges inside
     * that at which it gains its detail, in metres.
     *
     * <p>Four numbers rather than one, because a post is not one object: it is a
     * building worth about twelve hundred triangles, nine item models on its
     * shelves worth about nine hundred more, and a person worth five hundred.
     * The building is what you want to see from across a valley — that is the
     * whole point of the sign — and the wares on the shelf are unreadable at
     * thirty metres whatever they cost. So the shed carries a long way, the
     * shelf and the keeper do not, and the keeper stops turning to look at you
     * at about the distance a person would stop noticing.
     */
    private static final double SHOP_RANGE = 220;

    private static final double KEEPER_RANGE = 90;

    private static final double WARES_RANGE = 34;

    private static final double NOTICE_RANGE = 22;

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

    private static final long TRACKS_KEY = Long.MIN_VALUE + 2;

    private static final long HOMES_KEY = Long.MIN_VALUE + 3;

    /** Which overlay is up, if any. */
    private enum Panel { NONE, SATCHEL, HOMES, SHOP, MAP, BOUNTY, PAUSED }

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

    /**
     * The last ten minutes of where the party put its feet.
     *
     * <p><b>The screen's, not the host's.</b> Nothing about a trodden track is
     * on the wire and nothing needs to be: every walker's position is already
     * in every snapshot, so this side watches the same feet the host does and
     * reaches the same trail without being told it. See {@link TrackField},
     * which is where that bargain and what it costs are written down.
     */
    private final TrackField tracks = new TrackField();

    /**
     * That record as triangles, and the state that decides when to rebuild it.
     *
     * <p>Held between rebuilds rather than rebuilt per frame, and the <em>same
     * object</em> is resubmitted: a backend caches by {@link Mesh#revision()}
     * and by origin, so handing it back an unchanged mesh is what makes this
     * cost one upload every {@link #TRACK_REFRESH} seconds instead of one a
     * frame. See {@link #trackMesh()}.
     */
    private Mesh trackMesh = Mesh.empty(0, 0, 0);

    private int trackRevision;

    private double trackFromX = Double.NaN, trackFromY, trackAge;

    /**
     * The party's houses as triangles, and what decides when to rebuild them.
     *
     * <p><b>Its own mesh, not the dynamic one</b>, and the numbers say why: a
     * mansion is about four hundred boxes before the boards are sawn, and the
     * dynamic mesh is rebuilt from scratch on every single frame. Houses do not
     * move. They change when somebody buys one, sells one, or when a world sync
     * brings news of somebody else's — a handful of times a session — so this
     * follows {@link #trackMesh}'s pattern exactly: held between rebuilds, the
     * same object resubmitted, and a backend caching by {@link Mesh#revision()}
     * paying one upload for each of those handful rather than sixty a second.
     */
    private Mesh homeMesh = Mesh.empty(0, 0, 0);

    private int homeRevision;

    /** What the homestead looked like when the mesh was last built. */
    private long homeStamp = Long.MIN_VALUE;

    private double homeFromX = Double.NaN, homeFromY;

    /** Whichever of the two track ranges the backend turned out to afford. */
    private double trackRange = TRACK_RANGE_PAINTER;

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
     * What the walk can hear.
     *
     * <p>Driven from the view every frame rather than from events: see
     * {@link WatchSounds}, which derives every noise the three mutants make from
     * replicated state so that a sound can never disagree with what is drawn.
     */
    private final WatchSounds noises = new WatchSounds();

    /**
     * The embers off a thrown shard.
     *
     * <p>Driven from the view like the noises are, and for the same reason —
     * see {@link Sparks}, which derives a trail and an impact burst from
     * replicated positions rather than from anything on the wire.
     */
    private final Sparks sparks = new Sparks();

    /**
     * Everything burning, this frame.
     *
     * <p>Derived from the view like the embers and the noises, and for the
     * third time for the same reason: a fire's <em>existence</em> is replicated
     * and everything a fire <em>does</em> is worked out here. See
     * {@link LightField}, and {@link WatchRenderer#setLights} for where it goes.
     */
    private final LightField lights = new LightField();

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

    /** Whether both feet are off the ground, and how fast they are rising. */
    private boolean airborne;

    private double climb;

    /**
     * …and how much of that the <em>pose</em> has caught up with, eased.
     *
     * <p>{@link #airborne} is a fact and switches in a frame; a figure that
     * switched with it would cut from a walk to a jump on the frame its feet
     * left, which is the one thing every part of this animation is written to
     * avoid. A tenth of a second of blend is what makes a take-off a take-off.
     * {@code Gait} does exactly the same for everybody else, from the same
     * constant, so the party's jumps all read alike.
     */
    private double airPose;

    /**
     * How much of a landing is still being absorbed, {@code 1} on touchdown
     * down to {@code 0} standing.
     *
     * <p>A jump that ends the instant the feet touch is a figure that snaps
     * from mid-air to standing in one frame, which reads as a dropped frame
     * rather than as a landing. This is the third of a second afterwards that
     * makes it a landing — the knees take the drop and give it back.
     */
    private double settle;

    /** How much air is left locally, so the meter is smooth between snapshots. */
    private double breath = 1;

    /**
     * How much health is left, eased toward whatever the host last said.
     *
     * <p>Eased for {@link #breath}'s reason and one of its own: a bar that
     * stepped down by a fifth on the frame a snapshot arrived would read as a
     * rendering glitch, and a bar that slides down over a third of a second
     * reads as being hit. The number the game acts on is always the host's; this
     * is only what is drawn.
     */
    private double health = 1;

    /**
     * How much of the red is still on the screen, {@code 1} on the blow down to
     * {@code 0}.
     *
     * <p>The one piece of feedback in this game that is not a line of text, and
     * the only one that had to be: a player being hit from behind by something
     * they have not seen needs to know it in the frame it happens, and the log
     * in the bottom right is the last place their eye is.
     */
    private double hurtFlash;

    /** How long a blow's flash lasts, in seconds. */
    private static final double HURT_FLASH_SECONDS = 0.55;

    /**
     * How many of a dropped satchel's items are drawn around it.
     *
     * <p>Six. A satchel can hold forty kinds of thing and drawing forty of them
     * would be a bonfire rather than a bag; six is enough to tell one heap from
     * another across a clearing, which is all the picture has to do — the
     * highlight under the crosshair says whose it is and how much is in it.
     */
    private static final int SPILL_ITEMS = 6;

    /**
     * Half the length of a thrown bone shard, in metres. See {@code Hurl}.
     *
     * <p>Half a metre, up from a third: the thing travels at twenty-four metres
     * a second and it has to be legible in the tenth of a second it is anywhere
     * near you.
     */
    private static final double SHARD_LENGTH = 0.5;

    /** …and half the span of the barb across it. */
    private static final double SHARD_BARB = 0.22;

    /**
     * The respawn count this screen has already acted on.
     *
     * <p>See {@link WatchPlayer#respawns()}: the host cannot move a client by
     * writing a position into the snapshot, because the client is the authority
     * on where it is standing and would simply send the old position back. So a
     * death arrives as this number going up, and the frame that notices
     * teleports to the position in that same snapshot.
     */
    private int respawnsSeen = -1;

    /** How long the "you died" notice stays up, in seconds. */
    private static final double DEATH_NOTICE_SECONDS = 5;

    /** How much of that is left. */
    private double deathNotice;

    /** The boat being rowed, or {@code 0}. */
    private long boatId;

    /** The gait clock, in turns — what drives the walk cycle and the head bob. */
    private double gait;

    /**
     * The rowing clock, in turns — one turn is one stroke of the oars.
     *
     * <p>Its own clock rather than the gait's, because it is its own cycle: a
     * stroke covers five or six metres and a stride covers two, so driving both
     * off one phase makes whichever is not being used run at the wrong rate.
     */
    private double rowPhase;

    /**
     * …and the swimming one, for the same reason again — a swimming stroke
     * covers a metre and a bit, and unlike either of the others it never stops.
     * See {@link Gait#swimRate}.
     */
    private double swimPhase;

    /**
     * A clock in seconds that only ever goes forward — what everything that
     * animates on its own is timed against.
     *
     * <p><b>Not the frame count, which is what these used to be.</b> A boat
     * bobbing at {@code frame * 0.006} bobs half as fast on a sixty-hertz
     * screen as on a hundred-and-twenty, speeds up when the view is cheap,
     * slows down when a storm rolls in, and stutters with every hitch — which
     * is precisely the "choppy" nobody can point at, because the animation
     * itself is smooth and it is the clock underneath that is not. Advanced by
     * the fixed simulation step, so it is the same on every machine.
     */
    private double animClock;

    /** How long the last simulation step was, so the draw can put back {@code alpha}. */
    private double lastStep = 1 / 120.0;

    /** {@link #animClock} as of this frame's draw, including {@code alpha}. */
    private double drawClock;

    /** How long ago the last frame was drawn, in seconds; {@code -1} before the first. */
    private double drawnAt = -1;

    /**
     * Seconds between this draw and the last one.
     *
     * <p>The smoothing of everybody else's position runs on this rather than on
     * the simulation step, because it is a property of the picture rather than
     * of the world: a walker has to be eased between snapshots once per frame
     * drawn, however many or few of them a machine manages.
     */
    private double frameSeconds;

    /** How fast the local player moved on the last frame, in metres per second. */
    private double lastSpeed;

    /**
     * …and that speed with the frame-to-frame jitter taken out, which is what
     * the animation is driven from.
     *
     * <p>The raw figure is a position difference over a step, so it goes from
     * nothing to full walking pace in one step when a key goes down, drops to
     * zero for a single step whenever a move is refused (rowing into a bank
     * does exactly that), and jumps about while the ground under a walker
     * changes. Driven straight, the limbs snap between standing still and full
     * swing several times a second. Eased over about a tenth of a second, they
     * set off and settle.
     */
    private double animSpeed;

    /** How fast {@link #animSpeed} settles onto the real speed, per second. */
    private static final double SPEED_SETTLE = 9;

    /** Where the other walkers are being drawn, between two snapshots. */
    private final Gait gaits = new Gait();

    /** …and the answer for everybody this frame, self included. See {@link #posePlayers}. */
    private final Map<Integer, Gait.Step> posed = new LinkedHashMap<>();

    /** How fast a hull bobs on the swell, in cycles a second. */
    private static final double BOAT_BOB = 0.36;

    /** How fast a full feeder turns what is on it, in radians a second. */
    private static final double LURE_SPIN = 0.24;

    /** How far through a reach-out gesture the hands are, {@code 0}–{@code 1}. */
    private double reach;

    private Panel panel = Panel.NONE;
    private int satchelIndex;
    private int satchelScroll;
    private int recipeIndex;
    private int recipeScroll;
    /** Which row of the house catalogue is picked, and which way it will face. */
    private int homeIndex;

    private int homeTurn;

    /**
     * The post the shop panel is open on, and where the cursor is on its shelf.
     *
     * <p>The shop itself is not kept — it is a function of the seed and of where
     * we are standing, so it is looked up again whenever it is needed rather
     * than held and allowed to go stale while the player walks away from the
     * counter with the panel up. Only the id is remembered, so a request names
     * the post the panel was opened on.
     */
    private long shopId;

    private int shopIndex;

    /**
     * Which of a post's two lists the shop panel is showing: the shelf of
     * materials, or the clothes rail.
     *
     * <p>Two lists in one column rather than a third column, and that is a
     * decision about the panel rather than about the clothes. The right-hand
     * half of this screen is the keeper and the stamp, which is the half a
     * player has to be <em>told</em> about — see {@link #drawShop} — and
     * squeezing it to make room for hats would trade the explanation for the
     * shopping. Two headings over one list costs a keypress and keeps the
     * screen the shape it was.
     */
    private boolean shopRail;

    /**
     * Where the cursor is on the Eye Spy board's list of things to ask for.
     *
     * <p>The list itself is not kept, for the shop's reason turned up a notch: it
     * is a function of where the player is standing and of what is already on the
     * board, and both of those change under an open panel — somebody else can pin
     * up the very species this cursor is sitting on. So it is rebuilt from
     * {@link Bounty#choices} whenever it is needed, and only the row survives
     * between frames.
     */
    private int bountyIndex;

    private int bountyScroll;

    /** The last thing the board said back — a posting, or why it was refused. */
    private String bountyLine = "";

    /**
     * The map screen, which draws both a single map and a board.
     *
     * <p>Its own class rather than another six hundred lines here, and it earns
     * that: it has a coordinate system of its own (metres, north up), a tool in
     * hand, and a picture that arrives a frame or two late. See
     * {@link MapPanel}. What stays on this side is what stays on this side for
     * every other panel — which key opens it, and where its requests go.
     */
    private final MapPanel mapPanel = new MapPanel();

    /**
     * Where the map screen's requests go, on whichever path this session is.
     *
     * <p>One object rather than four lambdas built per frame, and it is the same
     * two-branch shape as every other verb in this scene: solo it goes straight
     * into the local game, online it goes on the wire. See {@link #request}.
     */
    private final MapPanel.Sink mapSink = new MapPanel.Sink() {

        @Override public void mark(long chartId, int ink, double[] xs, double[] ys) {
            if (session.local() != null) {
                session.local().markMap(session.selfId(), chartId, ink, xs, ys);
            } else {
                session.client().sendMark(chartId, ink, xs, ys);
            }
        }

        @Override public void note(long chartId, int ink, double x, double y, String text) {
            if (session.local() != null) {
                session.local().noteMap(session.selfId(), chartId, ink, x, y, text);
            } else {
                session.client().sendNote(chartId, ink, x, y, text);
            }
        }

        @Override public void erase(long chartId, long markId) {
            if (session.local() != null) {
                session.local().eraseMark(session.selfId(), chartId, markId);
            } else {
                session.client().sendErase(chartId, markId);
            }
        }

        @Override public void pin(long chartId, long boardId) {
            if (session.local() != null) {
                session.local().pinMap(session.selfId(), chartId, boardId);
            } else {
                session.client().sendPin(chartId, boardId);
            }
        }
    };

    /**
     * The highest map id we had when we last asked the host for one, or
     * {@code 0} when we are not waiting.
     *
     * <p>Online a map is a request like any other: nothing exists until the host
     * says so, and it arrives in the next world sync rather than as an answer.
     * Opening the panel optimistically would be opening it on a map the host may
     * have refused; not opening it at all would mean pressing M and watching
     * nothing happen. So the id we would have beaten is remembered, and the
     * first map above it that turns up in our own satchel is the one we asked
     * for.
     */
    private long awaitingMapAfter;

    /** How long we wait for a map before deciding the host refused it, in seconds. */
    private static final double MAP_WAIT_SECONDS = 6;

    private double awaitingMapFor;

    /** Which map row of the satchel is being renamed, or {@code 0}. */
    private long renamingId;

    /** What has been typed into the rename field. */
    private final StringBuilder renameText = new StringBuilder();

    /** The last thing the keeper said, and how long it stays on the panel. */
    private String keeperLine = "";

    private double keeperFor;

    /** How long a keeper's line stays up, in seconds. */
    private static final double KEEPER_SECONDS = 6;

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

    /** Where the party has walked in the last ten minutes. */
    public TrackField tracks() { return tracks; }

    /** How many embers are alive — for the debug readout and for a test. */
    public int sparkCount() { return sparks.count(); }

    @Override
    public void onEnter() {
        panel = Panel.NONE;
        prompt = "";
        frame = 0;
        // Every clock the animation runs on, back to a standstill: a walk
        // re-entered is a walk begun, and a gait left halfway through a stride
        // by the last one would open on a figure mid-step.
        gait = 0;
        rowPhase = 0;
        swimPhase = 0;
        animSpeed = 0;
        lastSpeed = 0;
        airborne = false;
        climb = 0;
        airPose = 0;
        settle = 0;
        drawnAt = -1;
        // The bar starts whole and the respawn counter starts unread, so the
        // first snapshot adopts whatever the save says without teleporting
        // anybody. See syncVitals.
        health = 1;
        hurtFlash = 0;
        deathNotice = 0;
        respawnsSeen = -1;
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
        // …and with nothing having been heard yet, so the mutant already out
        // there when a save is reopened cries once on the frame it is first
        // seen rather than being silently present.
        noises.clear();
        sparks.clear();
        // A walk starts with untrodden ground. Tracks are the one thing here
        // that is neither generated from the seed nor sent by the host, so
        // there is nowhere for the last world's to have come from and nowhere
        // for them to go — they are cleared with the screen that owns them.
        tracks.clear();
        trackMesh = Mesh.empty(0, 0, 0);
        trackFromX = Double.NaN;
        trackAge = TRACK_REFRESH;
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
        trackRange = gpu ? TRACK_RANGE_GPU : TRACK_RANGE_PAINTER;
        trackFromX = Double.NaN;
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

        // Before the panel branch, and before the early return under it: the
        // world does not stop bobbing because somebody opened their satchel,
        // and a clock that stopped with the panel would jump the moment it
        // closed.
        animClock += dt;
        lastStep = dt;
        // Here for the same reason, and it is the half of tracks that has to be:
        // the wood keeps taking a path back while somebody reads a recipe, so a
        // satchel left open for a quarter of an hour is a satchel that closes on
        // ground with nothing on it. Laying prints is in the walking half below,
        // because nobody walks anywhere with a panel up.
        tracks.advance(dt);
        trackAge += dt;

        // Before the panel branch, so the code can be typed anywhere in the
        // walk — including on the satchel screen, which is exactly where
        // somebody testing a recipe is standing when they want it.
        //
        // …but not while a field has the keyboard. A note on a map or a map's
        // new name is text, and text has digits in it: without this, writing
        // "7799 steps to the ford" on a map turns debug mode off underneath the
        // player, which is the one thing that would take the map screen away
        // while they were using it.
        if (!typingText()) {
            readCode(dt, input);
            // Anywhere in the walk, panels included, for the code's own reason:
            // somebody testing a mutant against the satchel screen is exactly
            // the person who wants one. And behind `debugging()`, so K is an
            // ordinary unbound key to everybody else.
            if (debugging() && input.isKeyJustPressed(java.awt.event.KeyEvent.VK_K)) {
                summonMutant();
            }
            windClock(dt, input);
            // Here rather than in `act`, which is below the panel branch, so
            // that a poll can be answered from the satchel screen. A poll is
            // open for half a minute and an abstention is a no — somebody who
            // happened to be reading a recipe when the party was asked should
            // not be voted against by their own inventory.
            answerPoll(input);
        }

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
            driveGait(dt, 0, cycleNow());
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
            // Being mauled does not stop for a satchel screen. See syncVitals.
            syncVitals(dt);
            // Nor does being heard. A player who opened their satchel while
            // something was coming should still hear it arrive.
            noises.update(view(), px, py, aimYaw);
            sparks.follow(view(), dt);
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
        if (KeyBinds.pressed(input, GameAction.WATCH_HOMES)) {
            panel = Panel.HOMES;
            homeIndex = 0;
            return;
        }
        if (KeyBinds.pressed(input, GameAction.WATCH_MAP)) {
            drawMap();
            return;
        }
        if (KeyBinds.pressed(input, GameAction.WATCH_BOUNTY)) {
            openBounties();
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
        syncVitals(dt);
        // After the sync, because the sync is what a snapshot arrives in — and
        // the ear is at the camera, so it hears from where the player is looking
        // rather than from where their feet point.
        noises.update(view(), px, py, aimYaw);
        sparks.follow(view(), dt);
        noticePickups();
        // After the sync, because the sync is what a map arrives in.
        awaitMap(dt);

        // Where the glass is pointed decides which chunks this call asks for,
        // so it is set first.
        aimGlass();
        streamer.update(px, py, dt);
        sweepLitter();
        treadTracks();
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
     * Put the party's feet on the ground — one print per walker per frame, and
     * the field decides which of them are strides.
     *
     * <p><b>Everybody, and not only this player.</b> A trail somebody else left
     * is worth more than your own: it is how a party that split up at the lake
     * finds each other again, and it is the answer to "which way did they go"
     * without anybody having to type it. The positions are already in the
     * snapshot, so drawing them costs nothing but this loop.
     *
     * <p>Three ways of being somewhere leave nothing behind, and all three are
     * refused here rather than in {@link TrackField}: a boat, because oars do
     * not tread; the water, because a footprint under a lake is not a thing;
     * and the air, because a jump marks where it lands and not where it passed
     * over. This player's own state is read from the walk rather than from the
     * last snapshot of it, for the same reason their body is drawn from it —
     * their own feet are the ones that must not lag.
     */
    private void treadTracks() {
        WatchView view = view();
        if (view == null || streamer == null) return;
        WatchView.Walker self = view.self();
        for (WatchView.Walker walker : view.walkers()) {
            // Everybody but us, and — until the welcome has arrived and we know
            // which of these rows is ours — everybody including us, from the
            // snapshot. Which is right: before we know who we are, our own feet
            // are just another walker's, and doing it twice is what would leave
            // two trails down one path.
            if (self != null && walker.id() == self.id()) continue;
            if (walker.inBoat() || walker.submerged()) continue;
            tread(walker.id(), walker.x(), walker.y());
        }
        if (self != null && !self.inBoat() && !airborne && dive <= SUBMERGED_MARGIN) {
            tread(self.id(), px, py);
        }
    }

    /** One walker, if the ground under them is ground rather than lake. */
    private void tread(int walkerId, double x, double y) {
        if (streamer.groundAt(x, y) <= TerrainField.WATER_LEVEL) return;
        tracks.note(walkerId, x, y);
    }

    /**
     * The party's tracks as triangles, rebuilt when they have gone stale.
     *
     * <p>Stale means one of two things, and both of them matter: the player has
     * walked {@link #TRACK_RESTEP} metres, so there is ground in range that was
     * not in range before — or {@link #TRACK_REFRESH} seconds have passed
     * standing still, which is what advances the fade and adds the strides just
     * taken. Between those the previous mesh is handed back unchanged, which is
     * what a backend needs to see to leave its buffer alone.
     */
    private Mesh trackMesh() {
        boolean moved = Double.isNaN(trackFromX)
                || Math.hypot(px - trackFromX, py - trackFromY) >= TRACK_RESTEP;
        if (!moved && trackAge < TRACK_REFRESH) return trackMesh;
        trackFromX = px;
        trackFromY = py;
        trackAge = 0;
        trackMesh = TrackMesher.tracks(tracks, streamer, px, py, trackRange,
                ++trackRevision);
        return trackMesh;
    }

    /** How far off a house is still drawn, in metres. */
    private static final double HOUSE_RANGE = 320;

    /** How far the player walks before the houses are re-meshed, in metres. */
    private static final double HOUSE_RESTEP = 48;

    /**
     * How far off a house is still built out of boards rather than slabs, in
     * metres.
     *
     * <p>A mansion is twenty thousand triangles with its floorboards sawn, its
     * roof laid in courses and its furniture in it, and about a quarter of that
     * without. None of the difference is visible from the far side of a valley,
     * and the difference between one and eight mansions in a mesh is whether
     * the rebuild is felt. Generous enough that you are inside the detail long
     * before you can read anything.
     */
    private static final double HOUSE_DETAIL = 70;

    /**
     * Every house in sight, meshed when the party's houses change and not
     * before.
     *
     * <p>The trigger is a <b>stamp</b> rather than a dirty flag, because the
     * homestead this reads is a copy — the client replaces the whole thing on
     * every world sync, and a flag set on the original would never reach it.
     * Summing the ids catches a house bought, a house taken down, and a whole
     * homestead swapped for somebody else's, which is every way this list can
     * change.
     */
    private Mesh homeMesh() {
        Homestead homes = view().homes();
        long stamp = homes.size() * 1_000_003L;
        for (Homestead.Home home : homes.all()) stamp += home.id() * 31 + home.turn();
        boolean moved = Double.isNaN(homeFromX)
                || Math.hypot(px - homeFromX, py - homeFromY) >= HOUSE_RESTEP;
        if (!moved && stamp == homeStamp) return homeMesh;
        homeStamp = stamp;
        homeFromX = px;
        homeFromY = py;
        double ox = Math.floor(px), oy = Math.floor(py);
        Mesh.Builder mesh = Mesh.builder(ox, oy, 0, false, ++homeRevision);
        for (Homestead.Home home : homes.near(px, py, HOUSE_RANGE)) {
            HouseModel.house(mesh, home, homes.partsOf(home), ox, oy,
                    home.distanceTo(px, py) < HOUSE_DETAIL);
        }
        homeMesh = mesh.build();
        return homeMesh;
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

    /**
     * Take the time of day from the world, so a party shares one sunset —
     * <b>and so a walk on your own shares one with itself.</b>
     *
     * <p>This screen keeps a {@link WatchClock} of its own because that is what
     * everything drawn is drawn through: the sun's angle, the sky, the fog, the
     * shadows, the line on the HUD. The world keeps another one, in
     * {@code WatchGame}, and that is the one that decides which animals are out
     * and what hour a sighting is stamped with. There have to be two — the
     * world's may be on a machine in another country — but there must only ever
     * be <em>one answer</em>, and the world's is it.
     *
     * <p><b>This used to ask the world only when online.</b> Solo, both clocks
     * were started from the same wall clock and agreed for ever, so the
     * distinction cost nothing and read as a small saving. It stopped being
     * free the moment anything could <em>move</em> the world's clock: winding it
     * with {@link #windClock} moved the hour the animals kept and the guide
     * recorded, and left the screen drawing the real afternoon it had always
     * drawn. Two clocks that agree by coincidence are one clock with a bug in
     * it waiting for a reason.
     *
     * <p>The fallback is not "follow the wall clock" but {@code tick(0)}, which
     * re-reads it only if this clock is still following it — a guest whose
     * connection has gone quiet keeps the last hour it was told rather than
     * snapping back to its own afternoon.
     */
    private void syncClock() {
        WatchView view = view();
        // Solo there is always a world to ask; online the view is empty until
        // the first snapshot lands, and its hour is zero until then.
        boolean worldKnows = session.local() != null
                || (view != null && view.timeOfDay() > 0);
        if (worldKnows && view != null) {
            clock.adopt(view.timeOfDay());
        } else {
            clock.tick(0);
        }
    }

    /**
     * Take the host's word for how hurt we are, and act on a death.
     *
     * <p>Two things happen here and they are both consequences of the same rule:
     * <b>the host owns the health bar and this screen owns the position.</b>
     *
     * <p>The bar is simply read and eased toward — nothing on this side may
     * decide it, because what spends it is a mutant's blow and a mutant lives
     * on the host. A drop since the last frame is a blow that landed, which is
     * what puts the red on the screen; the flash comes from noticing the fall
     * rather than from a message, so it works identically alone and online with
     * no verb of its own.
     *
     * <p>The position is the other half. The host <em>cannot</em> move us by
     * writing a position into the snapshot — {@link #sendMove} would put the old
     * one straight back — so a respawn arrives as a counter, and this is the
     * frame that acts on it. See {@link WatchPlayer#respawns()}.
     *
     * <p>Called from both branches of {@link #update}, panel open or not: a
     * player reading a recipe while something walks up behind them still gets
     * hit, still dies, and still has to be standing at the spawn afterwards.
     */
    private void syncVitals(double dt) {
        hurtFlash = Math.max(0, hurtFlash - dt / HURT_FLASH_SECONDS);
        deathNotice = Math.max(0, deathNotice - dt);
        WatchView view = view();
        if (view == null) return;
        WatchView.Walker me = view.self();
        if (me == null) return;

        if (me.health() < health - 0.004) hurtFlash = 1;
        // Toward rather than to. A tenth of a second of slide is the difference
        // between "the bar moved" and "the bar glitched"; a respawn's jump back
        // to full is not worth easing, so it is taken whole.
        health = me.health() > health
                ? me.health()
                : health + (me.health() - health) * Math.min(1, dt * 9);

        if (respawnsSeen < 0) {
            // The first snapshot of a session: adopt whatever the count is
            // without teleporting anybody. A save that remembers three deaths
            // is not a fourth one happening now.
            respawnsSeen = me.respawns();
            return;
        }
        if (me.respawns() == respawnsSeen) return;
        respawnsSeen = me.respawns();
        respawnTo(me);
    }

    /**
     * Stand back up at the spawn, wherever the host says that is.
     *
     * <p>Everything the walk was in the middle of has to end with it: a glass at
     * the eye, a boat under you, a panel open, a jump half-finished. Leaving any
     * of them would put the player at the spawn point still rowing a boat that
     * is four hundred metres away.
     */
    private void respawnTo(WatchView.Walker me) {
        px = me.x();
        py = me.y();
        pz = streamer != null ? streamer.groundAt(px, py) : me.z();
        smoothedGround = pz;
        dive = 0;
        climb = 0;
        airborne = false;
        settle = 0;
        boatId = 0;
        breath = 1;
        health = 1;
        hurtFlash = 0;
        deathNotice = DEATH_NOTICE_SECONDS;
        glass.tick(0, false, 1);
        panel = Panel.NONE;
        // The ground under the spawn may not be built — it can be a kilometre
        // from wherever the walk had got to — and a frame drawn before it is
        // there is a frame of hanging in the air over an empty world.
        if (streamer != null) streamer.loadNow(px, py, 1);
        // Tracks are a record of where this walker has been, and they must not
        // draw a line from the fen to the spawn point.
        tracks.clear();
        trackMesh = Mesh.empty(0, 0, 0);
        trackFromX = Double.NaN;
        say("You were killed — your satchel is where you fell");
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

    /**
     * K, in debug mode: put the next of the three mutants in front of us.
     *
     * <p><b>A raw key rather than a {@link GameAction}</b>, and the difference
     * from {@code WATCH_MAP} is the whole argument. A map is a game verb behind
     * a gate that will one day lift, so it is on the controls screen where a
     * player will find it the day it stops being special. Summoning a wendigo is
     * never going to be a player verb: putting "Summon Mutant" on the controls
     * screen would advertise a thing that is always refused, which is exactly
     * what {@link Debug}'s class note says a menu item would do wrong. So it is
     * read here, off the keyboard, the way the code itself is — and to anybody
     * who has not typed {@link Debug#CODE}, K does nothing at all.
     *
     * <p><b>One key, and it cycles.</b> Three keys for three creatures would be
     * three bindings to remember and two of them wrong most of the time; a key
     * that summons a <em>random</em> one is a key you press five times to see
     * the one you are working on. Round the three in order, and the log line
     * says which arrived.
     *
     * <p>Which one is next is kept on this side rather than sent, because it is
     * a fact about the keyboard in front of one person — see
     * {@link com.larsons.engine.watch.net.WatchProto#summon}.
     */
    private void summonMutant() {
        List<Mutants.Kind> kinds = Mutants.all();
        Mutants.Kind kind = kinds.get(Math.floorMod(summonNext++, kinds.size()));
        String key = kind.key();
        if (session.local() != null) {
            // Solo there is no sink for the host's own chat, so the line is
            // raised here — the same arrangement readCode uses, and for the same
            // reason: a debug verb that appears to do nothing is worse than none.
            if (session.local().summon(session.selfId(), key) != null) {
                picked("Summoned a " + kind.def().name());
            }
        } else if (session.client() != null) {
            // The host answers by putting it in the next snapshot, or by
            // refusing in silence. Saying anything here would risk saying the
            // opposite of what happened, which is readCode's rule too.
            session.client().sendSummon(key);
        }
    }

    /**
     * Which of the three the next press of K produces. See {@link #summonMutant}.
     */
    private int summonNext;

    /** How fast the clock scrubs while a key is held, in days per second. */
    private static final double SCRUB_RATE = 3 / 24.0;

    /**
     * How often a held scrub tells the host, in seconds.
     *
     * <p>Sixty messages a second to move a sky is absurd, and unnecessary: the
     * message carries an <em>absolute</em> hour rather than a step, so the
     * hour is right after the next one whatever happened to the ones before.
     * A dozen a second is smooth enough that the sun does not visibly step.
     */
    private static final double SCRUB_INTERVAL = 0.08;

    private double scrubbing = -1;
    private double scrubSent;

    /**
     * <kbd>,</kbd> and <kbd>.</kbd>, in debug mode: scrub the time of day.
     * <kbd>/</kbd> puts it back on the real clock.
     *
     * <p><b>Held rather than tapped</b>, and that is the whole of why this is
     * worth having over a key that jumps an hour. What a tester is checking is
     * not "what does six o'clock look like" but "does the light do anything
     * ugly on the way there" — the sun crossing the horizon, the fog lifting,
     * the fires becoming worth lighting, a tree's shadow swinging round and
     * stretching out. All of that is a <em>motion</em>, and three hours a
     * second is fast enough to see it and slow enough to stop on the frame you
     * wanted.
     *
     * <p>Raw keys rather than {@link com.larsons.engine.input.GameAction}s, for
     * {@link #summonMutant}'s reason: winding the clock is never going to be a
     * player verb, and a controls screen listing one that is always refused is
     * exactly what {@link Debug}'s class note says a menu item would do wrong.
     * The three sit together under one hand on any keyboard, which is as much
     * discoverability as a debug key needs given the readout names them.
     *
     * <p>Local as well as sent. Solo there is no host to answer and the game
     * object is right here; hosting, the same call is the authoritative one. A
     * guest sends and waits for the snapshot, which is the only arrangement in
     * which the party's sky and this player's agree.
     */
    private void windClock(double dt, InputManager input) {
        if (!debugging() || session == null) return;
        if (input.isKeyJustPressed(java.awt.event.KeyEvent.VK_SLASH)) {
            scrubbing = -1;
            if (session.local() != null) {
                if (session.local().followWallClock(session.selfId())) {
                    picked("Clock back to " + WatchClock.localTimeOf(
                            view().timeOfDay()).withNano(0));
                }
            } else if (session.client() != null) {
                session.client().sendClock(0, true);
            }
            return;
        }
        int way = 0;
        if (input.isKeyDown(java.awt.event.KeyEvent.VK_PERIOD)) way++;
        if (input.isKeyDown(java.awt.event.KeyEvent.VK_COMMA)) way--;
        if (way == 0) {
            scrubbing = -1;
            return;
        }
        // Started from wherever the world actually is, so that letting go and
        // pressing again picks up from what is on screen rather than from
        // whatever this side last asked for.
        if (scrubbing < 0) {
            scrubbing = view().timeOfDay();
            scrubSent = -1;
        }
        scrubbing += way * SCRUB_RATE * dt;
        scrubbing -= Math.floor(scrubbing);
        if (session.local() != null) {
            // Solo, every frame: the game object is right here, there is no
            // wire to spare, and the whole reason this key is held rather than
            // tapped is to watch the light *move*. Rate-limited to a dozen a
            // second the sun visibly steps, which is the one thing a tool for
            // looking at smoothness must not do.
            session.local().setTimeOfDay(session.selfId(), scrubbing);
            return;
        }
        if (session.client() == null) return;
        // Online it is a message, so it is worth rationing — and the ration is
        // free of consequence because the message carries an *absolute* hour
        // rather than a step: the hour is right after the next one whatever
        // happened to the ones in between.
        //
        // Against the simulation clock rather than the drawing one: this runs
        // in `update`, where the drawing clock is a frame stale and — on a
        // machine that never draws, which is what a test is — never moves at
        // all.
        if (scrubSent >= 0 && animClock - scrubSent < SCRUB_INTERVAL) return;
        scrubSent = animClock;
        session.client().sendClock(scrubbing, false);
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
     * Walk, jump, swim, dive, or row.
     *
     * <p>No fall damage: this is a game about looking at things, and nothing in
     * it should end with a player dead at the bottom of a slope they wanted a
     * better view from. There <em>is</em> jumping — see {@link #JUMP_SPEED} —
     * and there is <b>crouching</b>, because crouching is how you get near a
     * wary animal (see {@link WatchPlayer#stillness()}), and three things that
     * were promised and were not there:
     *
     * <ul>
     *   <li><b>Diving.</b> The old version pinned a swimmer to sixty
     *       centimetres under the surface and gave them no way down, so the sea
     *       floor and everything on it were places you could see and never
     *       reach. Now the crouch key sinks and the jump key rises, and the
     *       floor of a lake is somewhere you walk about on.</li>
     *   <li><b>Rowing.</b> A boat is nine and a half metres a second across
     *       water that is otherwise two and a half.</li>
     *   <li><b>Breath.</b> Which runs out, and floats you up rather than
     *       killing you. See {@link WatchPlayer#breath()}.</li>
     * </ul>
     *
     * <p>And a fourth, which arrived with the houses: <b>timber is solid.</b>
     * Three lines of this method go through {@link Homestead} — a wall refuses
     * a step, a floor is what you are standing on, and a ladder is what the two
     * vertical keys mean while you are holding one — and between them they are
     * the whole of why a bought house is a place rather than a picture. Each
     * one is deliberately a <em>rule</em> rather than a case: a stair tread is
     * a floor, a roof deck is a floor, a balcony is a floor, and none of the
     * three has a line of its own.
     */
    private void walk(double dt, InputManager input) {
        // Frozen: nothing below this line happens at all, which is the shortest
        // and the only honest way to write it.
        //
        // <b>The host enforces the freeze and this obeys it</b>, which is not the
        // same rule twice. The host refuses to take a position from somebody who
        // is counting; if this method ran anyway they would walk away from their
        // own body and be silently pulled back twenty times a second, which reads
        // as the game having broken rather than as a count of thirty. And it is
        // an early return rather than a zeroed input because the vertical half of
        // walking does not go through the input at all: a swimmer drifts toward
        // the surface on their own, and a swimmer out of air is floated up
        // whatever the keys say — both of which would be a screen disagreeing
        // with the world for half a minute. See Tag.
        if (view().tag().frozen(session.selfId())) {
            driveGait(dt, 0, cycleNow());
            settle = Math.max(0, settle - dt / Gait.SETTLE_SECONDS);
            return;
        }

        double ground = streamer.groundAt(px, py);
        double surface = TerrainField.WATER_LEVEL;
        double depth = Math.max(0, surface - ground);
        boolean overWater = depth > 0.6;
        boolean rowing = boatId != 0 && overWater;

        // The ladder we are holding, if we are holding one. Worked out before
        // anything else, because it changes what three keys mean: up and down
        // climb rather than jump and crouch, and there is no crouching on a
        // ladder.
        Homestead homes = view().homes();
        Homestead.Climb rung = overWater || rowing ? null
                : homes.climbAt(px, py, pz, pz + Homestead.BODY_HEIGHT);

        // A stance, and its own key. It used to be read off JUMP, so the one
        // key in every 3D game that means "jump" made you squat instead; now
        // Space does what it says and Control does the crouching.
        if (KeyBinds.pressed(input, GameAction.CROUCH) && !overWater && !rowing
                && rung == null) {
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
        // game with swimming in it uses. Now that crouching has a key of its
        // own, sinking is on it, which is what the comment here always claimed
        // and could not do while the crouch was on the jump key: it is the key
        // a player's hand is already reaching for when they want to go lower.
        boolean rising = KeyBinds.down(input, GameAction.JUMP);
        boolean sinking = KeyBinds.down(input, GameAction.CROUCH);
        // Off the ground, on the key that has meant this in every other scene
        // in this engine since there were scenes. Refused in a boat and in the
        // water, where the same key is already how you go up.
        boolean leaping = KeyBinds.pressed(input, GameAction.JUMP)
                && !overWater && !rowing && !airborne;

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
        // Being it, in the one place it changes anything about walking: 1.3×
        // everywhere — rowing and swimming included, because a chase that can be
        // won by getting into a boat is not a chase. The other half of the same
        // number, the freeze, has already returned above. See Tag.speed.
        speed *= view().tag().speed(session.selfId());

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
        // …and a wall cannot be walked through. Refusing the step for the same
        // reason, and one axis at a time so that walking into a wall at an
        // angle slides along it — see slideOffWalls.
        if (!rowing) slideOffWalls(homes, startX, startY);

        double startZ = pz;
        // Still in the air over the water is still in the air: a jump that
        // carries somebody off a bank falls until it reaches the surface and
        // starts swimming there. Handing them to `swim` the moment the ground
        // beneath them went deep would drop them onto the waterline from
        // whatever height they were at, in one frame.
        boolean overhead = airborne && pz > surface;
        if (rung != null) {
            climbLadder(dt, homes, rung, ground, rising, sinking);
        } else if (rowing) {
            // Sitting in the boat: the body rides on the waterline whatever the
            // bed is doing under it.
            land(surface - Boats.DECK);
            dive = 0;
            submerged = false;
            breath = Math.min(1, breath + dt * 4 / WatchPlayer.BREATH_SECONDS);
        } else if (overWater && !overhead) {
            land(pz);
            swim(dt, depth, sinking, rising, ground, surface);
        } else {
            // Back on dry land: shed whatever depth was left, and follow the ground.
            dive = 0;
            submerged = false;
            breath = Math.min(1, breath + dt * 4 / WatchPlayer.BREATH_SECONDS);
            if (leaping) {
                airborne = true;
                climb = JUMP_SPEED;
                crouching = false;
            }
            // What is actually under our boots: the ground, or the highest
            // floor, tread or deck of anybody's house that is not above us.
            double floor = homes.standOn(px, py, pz + STEP_UP, ground);
            if (airborne) {
                // Over water, the water is the floor: a jump that ends in a lake
                // ends at the surface, and the step after that is a swim.
                fly(dt, overWater ? surface : floor, overWater);
            } else if (pz - floor > FALL_STEP && pz - ground > FALL_STEP) {
                // Walked off the edge of something. Both halves of that test
                // matter: the first says the floor has dropped away, and the
                // second says we were up on somebody's carpentry rather than on
                // a hillside — a walker coming down a steep bank must still
                // <em>walk</em> down it, which is what the easing below is for.
                airborne = true;
                climb = 0;
                fly(dt, floor, false);
            } else {
                // Eased rather than snapped: on a two-metre grid the ground under
                // a walker changes by tens of centimetres a step, and a camera
                // that tracked it exactly would jolt with every one of them.
                smoothedGround += (floor - smoothedGround)
                        * Math.min(1, dt * STEP_SMOOTHING);
                pz = smoothedGround;
            }
        }
        // The landing settles on its own clock whatever happened above, so a
        // player who jumps into a lake or steps into a boat mid-crouch is not
        // left holding the dip for ever.
        settle = Math.max(0, settle - dt / Gait.SETTLE_SECONDS);

        // Last, because which cycle this step belongs to depends on where the
        // step left them — a stride into deep water is the step that becomes a
        // swim — and because a swimmer's speed has to count the depth they
        // covered as well as the ground. Somebody diving straight down covers
        // no ground at all, and clocked on ground alone would hang motionless
        // all the way to the bottom.
        Gait.Cycle cycle = cycleNow();
        double over = Math.hypot(px - startX, py - startY);
        double covered = cycle == Gait.Cycle.SWIM
                ? Math.hypot(over, pz - startZ) : over;
        driveGait(dt, covered / Math.max(1e-6, dt), cycle);
    }

    /**
     * How high a step a walker takes without jumping, in metres.
     *
     * <p>What decides whether a thing in a house is somewhere you walk onto or
     * somewhere you climb. A stair riser is 0.20 and the step up to a front
     * door is 0.17, so both are walked; a table top is 0.72 and a rail is 1.02,
     * so neither is. That is the whole rule, and it is one number rather than a
     * flag on each of them.
     */
    private static final double STEP_UP = 0.62;

    /**
     * How far the floor has to fall away before a walker is falling, in metres.
     *
     * <p>Comfortably more than {@link #STEP_UP} so that walking <em>down</em> a
     * staircase is walking rather than a series of small falls.
     */
    private static final double FALL_STEP = 0.85;

    /** How fast a ladder is climbed, in metres per second. */
    private static final double CLIMB_SPEED = 2.6;

    /**
     * How far off the boots the wall test starts, in metres.
     *
     * <p>A hair, so that the very bottom edge of a wall — which is level with
     * the floor it stands on — cannot catch a walker whose feet are being eased
     * onto that floor a centimetre at a time.
     */
    private static final double WALL_TOE = 0.10;

    /**
     * Undo a step that walked into somebody's wall.
     *
     * <p>One axis at a time, and it has to be: refusing both would stop a
     * player dead the moment they brushed a doorframe at an angle, which is the
     * single most common way anybody meets a wall in a game. Trying each axis on
     * its own means a step into a wall becomes a step <em>along</em> it, and
     * getting through a {@value HouseKit#DOOR_WIDTH}-metre doorway does not
     * require lining up on it first.
     */
    private void slideOffWalls(Homestead homes, double startX, double startY) {
        double foot = pz + WALL_TOE;
        double head = pz + Homestead.BODY_HEIGHT;
        if (!homes.solidAt(px, py, foot, head)) return;
        if (!homes.solidAt(px, startY, foot, head)) {
            py = startY;
            return;
        }
        if (!homes.solidAt(startX, py, foot, head)) {
            px = startX;
            return;
        }
        px = startX;
        py = startY;
    }

    /**
     * One step of a climb.
     *
     * <p>The whole of how anybody gets into a treehouse, and it is deliberately
     * the two keys that already mean up and down rather than a third one to
     * remember. Gravity is off while a hand is on a rung, the climb is clamped
     * to the ladder's own ends so nobody rides one into the sky, and a floor
     * that has come within reach wins — which is what makes arriving at the top
     * of a ladder feel like arriving rather than like stopping.
     */
    private void climbLadder(double dt, Homestead homes, Homestead.Climb rung,
                             double ground, boolean rising, boolean sinking) {
        airborne = false;
        climb = 0;
        dive = 0;
        submerged = false;
        crouching = false;
        breath = Math.min(1, breath + dt * 4 / WatchPlayer.BREATH_SECONDS);
        if (rising) pz += CLIMB_SPEED * dt;
        if (sinking) pz -= CLIMB_SPEED * dt;
        pz = Math.max(rung.bottom(), Math.min(rung.top(), pz));
        double floor = homes.standOn(px, py, pz + STEP_UP, ground);
        if (floor > pz) pz = floor;
        // Hard, not eased: a rung is where your foot is, and a camera drifting
        // up to it a tenth of a second late is a camera that is seasick.
        smoothedGround = pz;
    }

    /**
     * One step of a jump: gravity, and the ground when it arrives.
     *
     * <p>The whole of being off the ground. There is no air control worth the
     * name — a jump goes where it was aimed, and the horizontal step above
     * already happened at walking speed, which is the ordinary compromise and
     * the one that stops a player steering themselves onto a ledge they could
     * not have walked to.
     *
     * <p>The landing is a <em>hard</em> set of the eased ground rather than a
     * blend into it. {@link #smoothedGround} exists to take the steps out of a
     * heightfield, and a landing is not a step: the foot arrives where it
     * arrives, and easing it would float the player down the last few
     * centimetres after they had visibly touched.
     */
    private void fly(double dt, double floor, boolean water) {
        climb -= GRAVITY * dt;
        pz += climb * dt;
        if (pz <= floor) {
            // How hard, before the speed is thrown away: a hop off a kerb and a
            // drop off a bluff should not land the same way. Water absorbs its
            // own landings — a splash is not a pair of knees — so a jump into a
            // lake arrives with nothing to stand up out of.
            settle = water ? 0 : Math.min(1, -climb / Gait.LANDING_REFERENCE);
            land(floor);
        } else {
            smoothedGround = pz;
        }
    }

    /** Put both feet down at a height, wherever they were. */
    private void land(double at) {
        airborne = false;
        climb = 0;
        pz = at;
        smoothedGround = at;
    }

    /**
     * Advance the local player's animation clocks by one step.
     *
     * <p>Three clocks, and only the one being used advances: a walker's legs
     * must not be halfway through a stride when they step out of a boat, an oar
     * must not have run on while its rower was ashore, and a swimmer coming out
     * of a lake must not walk off mid-stroke.
     *
     * <p>Called from the panel branch as well, at a speed of nothing, so that
     * opening the satchel at a run winds the gait down over its usual tenth of
     * a second rather than freezing it mid-stride until the panel closes. A
     * swimmer's does not wind down, because it does not have a bottom to wind
     * down to — see {@link Gait#swimRate}.
     */
    private void driveGait(double dt, double speed, Gait.Cycle cycle) {
        lastSpeed = speed;
        animSpeed += (speed - animSpeed) * Math.min(1, dt * SPEED_SETTLE);
        airPose += ((airborne ? 1 : 0) - airPose) * Math.min(1, dt * Gait.AIR_SETTLE);
        switch (cycle) {
            case STROKE ->
                    rowPhase = RowStroke.wrap(rowPhase + Gait.strokeRate(animSpeed) * dt);
            case SWIM ->
                    swimPhase = RowStroke.wrap(swimPhase + Gait.swimRate(animSpeed) * dt);
            case STRIDE -> gait = RowStroke.wrap(gait + Gait.cadence(animSpeed) * dt);
        }
    }

    /**
     * Which cycle the local player is running this step.
     *
     * <p>Swimming is <b>feet off the bottom</b> rather than "in the water",
     * which is the distinction the game already makes and the only one that
     * gives the right answer at both ends: somebody wading in the shallows has
     * their feet on the bed and is walking, and the moment the bed drops away
     * from under them they are swimming. {@link #swim} arranges exactly that —
     * it clamps {@link #dive} to the depth of the water, so a player in the
     * shallows is standing on the bed and one out of their depth is not — which
     * is why {@link #afloat} can read it back off the position alone, and read
     * it off everybody else's the same way.
     */
    private Gait.Cycle cycleNow() {
        if (boatId != 0) return Gait.Cycle.STROKE;
        // Airborne beats afloat: a jump that carries somebody out over a lake
        // is a jump until they are in it, and the leap pose is a stride pose
        // with its legs elsewhere.
        if (airborne) return Gait.Cycle.STRIDE;
        return afloat(px, py, pz) ? Gait.Cycle.SWIM : Gait.Cycle.STRIDE;
    }

    /**
     * Whether somebody at this position is off the bottom in water deep enough
     * to swim in.
     *
     * <p>Answered from the terrain rather than from anything on the wire: the
     * client generates the same ground the host does, so it can see the bed
     * under anybody in the party without a byte being sent about it.
     */
    private boolean afloat(double x, double y, double z) {
        if (streamer == null) return false;
        double ground = streamer.groundAt(x, y);
        if (TerrainField.WATER_LEVEL - ground <= WADING) return false;
        return z > ground + FOOTING;
    }

    /** How deep the water has to be before anybody could be out of their depth. */
    private static final double WADING = 0.6;

    /** How far off the bed a swimmer's feet are before they stop being a walker. */
    private static final double FOOTING = 0.25;

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
        // <b>What is in reach is worked out whether or not there is a bird in
        // front of you.</b> It used to be worked out only in the branch below
        // where nothing was under the crosshair, on the reasoning that an animal
        // is the more interesting of the two things to name — which is true of
        // the <em>prompt</em> and was quietly false of everything else, because
        // {@code inReach} is also what E acts on. Standing at a trading post
        // with a chaffinch in view, the crosshair said "Banded Finch" and the
        // reach key silently did nothing at all. So the answer is always
        // computed and only the line of text gives way.
        inReach = null;
        reachPrompt();
        String reaching = prompt;
        prompt = "";
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
            prompt = reaching;
            return;
        }
        lookingAtId = found.id();
        FieldGuide guide = view().guide();
        // Three states, not two, and the middle one is the whole reason a page
        // gets stamped: something you have seen before but have not seen *since*
        // is worth its rarity again, and the crosshair is where a player finds
        // that out. Without this the feature is a number on a panel.
        int worth = guide.award(found.def().key());
        prompt = !guide.seen(found.def().key())
                ? "Something new  —  click to record it"
                : worth > 0
                        ? found.def().name() + "  —  worth " + worth
                                + (worth == 1 ? " point" : " points")
                        : found.def().name() + "  —  click to point it out";
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

        // First, exactly as on the host — see WatchGame.pickTarget, where the
        // argument for the order is set out. The two walks have to agree or the
        // ring says "blackberry" and the key gathers a satchel.
        Spill.Pile pile = view.spills().nearest(px, py, Spill.REACH);
        if (pile != null) {
            return new WatchGame.Pickable(WatchGame.Pickable.Kind.SATCHEL,
                    String.valueOf(pile.id()), pile.label(), pile.x(), pile.y(),
                    pile.z() + 0.25, Spill.RADIUS);
        }

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
        // A fire or a lantern. In the same place in this list as it is in the
        // host's, because the two have to agree about what the key would take:
        // a highlight that names one thing and a press that does another is
        // worse than no highlight at all.
        PlacedLight burning = view.lights().nearest(px, py, reachLimit);
        if (burning != null) {
            return new WatchGame.Pickable(WatchGame.Pickable.Kind.FIRE,
                    burning.kind().key(), burning.describe(), burning.x(), burning.y(),
                    burning.flameZ(), burning.reach());
        }
        if (boatId == 0) {
            Boats.Boat boat = view.boats().nearest(streamer.field(), px, py,
                    Boats.BOARD_RANGE);
            if (boat != null) {
                return new WatchGame.Pickable(WatchGame.Pickable.Kind.BOAT, "boat",
                        "Rowing boat", boat.x(), boat.y(), boat.z() + 0.4, 1.6);
            }
        }
        Shops.Shop shop = shopInReach();
        if (shop != null) {
            return new WatchGame.Pickable(WatchGame.Pickable.Kind.SHOP,
                    String.valueOf(shop.id()), shop.title(), shop.counterX(),
                    shop.counterY(), shop.z() + Shops.COUNTER_TOP + 0.25, 0.5);
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
        if (KeyBinds.pressed(input, GameAction.WATCH_LIGHT)) request("lamp");
        if (KeyBinds.pressed(input, GameAction.WATCH_CAMPFIRE)) request("putlight");
        // The tag keys are not here: they are read above the panel branch, so a
        // poll can be answered from a screen. See answerPoll. The trigger is,
        // because nobody fires a water gun out of their satchel.
        if (KeyBinds.pressed(input, GameAction.WATCH_SQUIRT)) request("squirt");
    }

    /**
     * T and U: the two keys the party games are worked with.
     *
     * <p>T does three things and they are one intention with a state attached,
     * in the same way N lights and douses one lamp — suggest a game, suggest an
     * end to one, or say yes to whichever is being asked. U is the no, and it
     * does nothing at all when nothing is being asked.
     */
    private void answerPoll(InputManager input) {
        if (session == null || view() == null) return;
        boolean asked = view().tag().polling();
        if (KeyBinds.pressed(input, GameAction.WATCH_TAG)) {
            request(asked ? "yes" : "tag");
        }
        if (asked && KeyBinds.pressed(input, GameAction.WATCH_TAG_NO)) request("no");
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
                // A counter is the one thing in reach whose verb is a screen
                // rather than a message. Handled here rather than in the host's
                // `use` because trading is a conversation — you look at what is
                // on the shelf, then decide — and a key that bought whatever was
                // nearest would be a key nobody dared press. Everything the
                // panel then does *is* a request, on the same two paths as every
                // other verb.
                if (inReach != null && inReach.kind() == WatchGame.Pickable.Kind.SHOP) {
                    openShop();
                    return;
                }
                // A map board, for the same reason: reading one is a screen,
                // and everything the screen then does is a request like any
                // other.
                if (inReach != null && inReach.kind() == WatchGame.Pickable.Kind.BOARD) {
                    openBoard();
                    return;
                }
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
            // Light, douse or fill what is in the hand. Nothing is sent but the
            // intention: the host owns whether there is anything to light and
            // how much oil is in it, and the answer arrives on everybody's next
            // snapshot as a flame on this player's row.
            case "lamp" -> {
                if (local != null) {
                    String line = local.tendLamp(me);
                    say(line != null ? line : "Nothing to light — make a torch first");
                } else {
                    session.client().sendAction("lamp");
                }
            }
            // Set a light down, or build a fire out of what is in the satchel.
            case "putlight" -> {
                if (local != null) {
                    if (local.setDownLight(me) == null) {
                        say(LightKind.CAMPFIRE.affordable(view().satchel())
                                ? "Nowhere to put it just here"
                                : "Needs " + LightKind.CAMPFIRE.costLine()
                                        + " — or a lantern to set down");
                    }
                } else {
                    session.client().sendAction("putlight");
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
            // The party games. Nothing is drawn optimistically on any of the
            // four: a poll, a freeze and whoever is it all ride the snapshot, so
            // the answer is on this screen within a twentieth of a second, and a
            // client that guessed would be a client that briefly showed a round
            // the host had refused to start.
            case "tag" -> {
                if (local != null) {
                    String line = local.suggestTag(me);
                    say(line != null ? line : "Nobody to play with");
                } else {
                    session.client().sendTag();
                }
            }
            case "yes", "no" -> {
                boolean yes = verb.equals("yes");
                if (local != null) local.voteTag(me, yes);
                else session.client().sendVote(yes);
            }
            case "squirt" -> {
                if (!view().tag().isIt(me)) {
                    say("The water gun is whoever is it's");
                } else if (view().tag().frozen(me)) {
                    say("Still counting — "
                            + (int) Math.ceil(view().tag().freeze()) + "s");
                } else if (local != null) {
                    local.squirt(me);
                } else {
                    session.client().sendSquirt();
                }
            }
            case "bounty" -> {
                String species = bountyCursor();
                if (species == null) {
                    bountySays("Nothing here worth asking for");
                } else if (local != null) {
                    String line = local.postBounty(me, species);
                    bountySays(line == null ? "No such animal" : line);
                } else {
                    session.client().sendBounty(species);
                    // Nothing is assumed, exactly as at a shop counter: the host
                    // rolls the price and may refuse the posting outright, and a
                    // number drawn optimistically would be a number it never
                    // agreed to.
                    bountySays("…");
                }
            }
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
        // Escape belongs to whatever has the keyboard: a half-typed note or a
        // half-typed name is cancelled by it, and only then does it close the
        // screen. Without this, pressing Escape to abandon a note you had begun
        // would abandon the map as well.
        boolean back = KeyBinds.pressed(input, GameAction.MENU_BACK)
                || KeyBinds.pressed(input, GameAction.PAUSE);
        if (back && !typingText()) {
            panel = Panel.NONE;
            mapPanel.close();
            return;
        }
        keeperFor = Math.max(0, keeperFor - dt);
        switch (panel) {
            case SATCHEL -> updateSatchel(input);
            case HOMES -> updateHomes(input);
            case SHOP -> updateShop(input);
            case MAP -> updateMap(input);
            case BOUNTY -> updateBounty(input);
            case PAUSED -> updatePaused(input);
            case NONE -> { }
        }
    }

    /** Whether a text field somewhere has the keyboard. */
    private boolean typingText() {
        return renamingId != 0 || mapPanel.typing();
    }

    // --- maps ------------------------------------------------------------------------

    /**
     * How far this machine can see, in metres — which is how wide a map it can
     * draw.
     *
     * <p>The streamer's ring, in chunks, times the size of one. Read here rather
     * than sent as a setting because it is what the ring <em>is</em> right now:
     * {@link #applyDistanceSettings} sizes it from whether a card turned up and
     * from the player's own detail slider, so this is the only honest answer and
     * it changes when either of those does.
     */
    private double mapReach() {
        return streamer == null ? 0 : streamer.viewRadius() * (double) WatchChunk.SIZE;
    }

    /** Draw a map of everything in view, and open it. */
    private void drawMap() {
        if (!debugging()) {
            say("Maps are still behind debug mode — type " + Debug.CODE);
            return;
        }
        WatchGame local = session.local();
        if (local != null) {
            Chart chart = local.drawMap(session.selfId(), mapReach());
            if (chart == null) {
                say("No room for another map");
                return;
            }
            // Copy the game into the view before opening on it. Everything the
            // panel draws comes from the view, and the view is refreshed at the
            // <em>end</em> of a frame — so a panel opened on a map made halfway
            // through this one would spend its first frame looking at a world
            // that has never heard of it, and close itself.
            session.update(0);
            picked("Drew " + chart.name());
            openChart(chart.id());
            return;
        }
        // Online the map comes back with the next world sync, which the host
        // sends the instant it makes one. Nothing is opened optimistically: a
        // panel showing a map the host may have refused is a panel that would
        // have to close itself under the player's hand. See awaitingMapAfter,
        // which is what opens it when it actually arrives.
        awaitingMapAfter = highestMapId();
        awaitingMapFor = MAP_WAIT_SECONDS;
        session.client().sendChart(mapReach());
        say("Drawing a map…");
    }

    /** The newest map id anybody in this world has, or {@code 0}. */
    private long highestMapId() {
        long highest = 0;
        for (Chart chart : view().maps().charts()) {
            highest = Math.max(highest, chart.id());
        }
        return highest;
    }

    /** Open the map we asked the host for, the frame it turns up. */
    private void awaitMap(double dt) {
        if (awaitingMapAfter == 0) return;
        awaitingMapFor -= dt;
        for (Chart chart : view().maps().carriedBy(session.selfId())) {
            if (chart.id() <= awaitingMapAfter) continue;
            awaitingMapAfter = 0;
            picked("Drew " + chart.name());
            openChart(chart.id());
            return;
        }
        if (awaitingMapFor <= 0) {
            awaitingMapAfter = 0;
            say("The host did not draw a map");
        }
    }

    /** Open one map. */
    private void openChart(long chartId) {
        mapPanel.openChart(chartId);
        panel = Panel.MAP;
    }

    /** Walk up to a board and read what is on it. */
    private void openBoard() {
        Cartography.Board board = view().maps().boardAt(px, py);
        if (board == null) return;
        mapPanel.openBoard(board.id());
        panel = Panel.MAP;
    }

    /**
     * The map screen.
     *
     * <p>Almost all of it is {@link MapPanel}'s: what stays here is the two
     * things a panel in this scene owes the scene, which are closing on its own
     * key and letting the strip see a click before the paper does.
     */
    private void updateMap(InputManager input) {
        if (!mapPanel.open()) {
            panel = Panel.NONE;
            return;
        }
        pointerMoved(input);
        if (input.isMouseJustPressed()
                && mapPanel.clickStrip(pointerX, pointerY, viewportWidth, viewportHeight,
                        mapSink)) {
            return;
        }
        mapPanel.update(input, view(), streamer == null ? null : streamer.field(),
                viewportWidth, viewportHeight, mapSink);
        if (!mapPanel.typing() && KeyBinds.pressed(input, GameAction.WATCH_MAP)) {
            mapPanel.close();
            panel = Panel.NONE;
        }
    }

    // --- trading ----------------------------------------------------------------------

    /**
     * The post whose counter we are standing at, or {@code null}.
     *
     * <p>Worked out on this side from the seed, exactly as the boats and the
     * litter are, and for the same reason: a shop is a pure function of the
     * world and asking the host where one is would be asking a question this
     * machine can already answer. What the host is for is deciding whether the
     * points were actually there. See {@link Shops}.
     */
    private Shops.Shop shopInReach() {
        if (streamer == null) return null;
        return view().shops().atCounter(streamer.field(), px, py);
    }

    /** Walk up to a counter. */
    private void openShop() {
        Shops.Shop shop = shopInReach();
        if (shop == null) return;
        panel = Panel.SHOP;
        shopId = shop.id();
        shopIndex = 0;
        // Always on the shelf. What a player walked to a post for is nine times
        // in ten a plank, and a screen that opened on whichever list was up last
        // time would be a screen that sometimes opens on the wrong one.
        shopRail = false;
        dragBar = 0;
        keeperLine = shop.keeper().greeting();
        keeperFor = KEEPER_SECONDS;
    }

    /**
     * The shop screen: a shelf to buy from, and a keeper to have a page stamped
     * by.
     *
     * <p>Driven by the same two hands as the satchel and the house screens, off
     * the same {@link SatchelBox}, because a third way of working a list would
     * be a third chance to select the row above the one that was clicked.
     *
     * <p>The panel closes itself when the counter is no longer there, and that
     * is a guard rather than a gesture: <b>a panel holds the walk still</b> —
     * {@code update} returns before {@link #walk} while one is up — so nobody
     * can currently stroll away from an open shop. What it defends against is
     * everything else that could take the post away underneath the screen, and
     * the reason it matters is that every button on this panel is refused by the
     * host unless the buyer is standing at the counter. A panel that outlived
     * its shop would be a screen whose every button silently did nothing.
     */
    private void updateShop(InputManager input) {
        Shops.Shop shop = shopInReach();
        if (shop == null || shop.id() != shopId) {
            panel = Panel.NONE;
            return;
        }
        List<Trading.Offer> stock = shop.stock();
        List<Cosmetics.Piece> rail = shop.rail();
        SatchelBox box = shopBox();

        // Which list is up, before anything else reads a row off it: the two
        // headings are buttons and the two menu keys are the same button, and
        // a swap has to land before the cursor is clamped to the new length.
        boolean swap = KeyBinds.pressed(input, GameAction.MENU_LEFT)
                || KeyBinds.pressed(input, GameAction.MENU_RIGHT);
        if (input.isMouseJustPressed()) {
            // A heading picks its own list outright rather than toggling:
            // clicking the one already up must do nothing, not swap away from it.
            if (overTab(box, false) && shopRail) swap = true;
            if (overTab(box, true) && !shopRail) swap = true;
        }
        if (swap) {
            shopRail = !shopRail;
            shopIndex = 0;
            return;
        }

        int rows = shopRail ? rail.size() : stock.size();
        int step = 0;
        if (KeyBinds.pressed(input, GameAction.MENU_DOWN)) step = 1;
        if (KeyBinds.pressed(input, GameAction.MENU_UP)) step = -1;
        if (step != 0 && rows > 0) {
            shopIndex = Math.floorMod(shopIndex + step, rows);
        }

        boolean moved = pointerMoved(input);
        int row = box.columnAt(pointerX, pointerY) == 0 ? box.rowAt(pointerY) : -1;
        if (row >= rows) row = -1;
        if (row >= 0 && moved) shopIndex = row;

        boolean take = KeyBinds.pressed(input, GameAction.MENU_SELECT);
        if (input.isMouseJustPressed()) {
            if (box.overClose(pointerX, pointerY)) {
                panel = Panel.NONE;
                return;
            }
            if (overStamp(box)) {
                stampPage(shop);
                return;
            }
            if (row >= 0) {
                shopIndex = row;
                take = true;
            }
        }
        // The other key the walk uses for "do the thing in front of you", which
        // here is the only thing on the panel that is not on the list.
        if (KeyBinds.pressed(input, GameAction.WATCH_CROSS)) {
            stampPage(shop);
            return;
        }
        if (take && rows > 0) {
            int at = Math.min(shopIndex, rows - 1);
            if (shopRail) {
                tryOn(shop, rail.get(at));
            } else {
                buy(shop, stock.get(at));
            }
        }
        if (KeyBinds.pressed(input, GameAction.WATCH_PICK)) panel = Panel.NONE;
    }

    /**
     * A row on the rail, clicked.
     *
     * <p><b>One gesture for three things</b> — buy it, put it on, take it off —
     * because from the player's side there is only one: they clicked the coat.
     * Which of the three it means is decided by what they already own, and it
     * is decided <em>here</em> rather than by the host so that the second click
     * on a piece bought a moment ago does not have to wait for a wardrobe to
     * come back over the wire before it knows it is a "wear" and not a second
     * purchase. Getting it wrong costs nothing: the host checks both again, and
     * refuses a purchase of something already owned.
     */
    private void tryOn(Shops.Shop shop, Cosmetics.Piece piece) {
        if (!view().outfit().owns(piece.key())) {
            buyWorn(shop, piece);
            return;
        }
        WatchGame local = session.local();
        if (local != null) {
            keeperSays(local.wear(session.selfId(), piece.key()));
        } else {
            session.client().sendWear(piece.key());
        }
    }

    /** Hand over the points for one thing off the rail. */
    private void buyWorn(Shops.Shop shop, Cosmetics.Piece piece) {
        WatchGame local = session.local();
        if (local != null) {
            String line = local.buyWorn(session.selfId(), shop.id(), piece.key());
            keeperSays(line != null ? line
                    : "Not enough points for the " + piece.name());
        } else {
            session.client().sendBuyWorn(shop.id(), piece.key());
            // Nothing assumed, for buy()'s reason: the wardrobe and the ledger
            // both come back on the host's own message, and a coat drawn
            // optimistically and then refused would be a coat that flickered.
            keeperSays("…");
        }
    }

    /** Hand over the points for one line, on whichever path this session is. */
    private void buy(Shops.Shop shop, Trading.Offer offer) {
        WatchGame local = session.local();
        if (local != null) {
            String line = local.buy(session.selfId(), shop.id(), offer.item());
            keeperSays(line != null ? line
                    : "Not enough points for " + offer.label());
        } else {
            session.client().sendBuy(shop.id(), offer.item());
            // Nothing is assumed here, unlike a picked-up branch: the satchel
            // and the ledger both come back from the host within a frame or
            // two, and a purchase drawn optimistically and then refused would
            // show the player points they never had.
            keeperSays("…");
        }
    }

    /** Ask for a fresh page. */
    private void stampPage(Shops.Shop shop) {
        WatchGame local = session.local();
        if (local != null) {
            String line = local.stamp(session.selfId(), shop.id());
            keeperSays(line == null ? "" : line);
        } else {
            session.client().sendStamp(shop.id());
            keeperSays("…");
        }
    }

    private void keeperSays(String line) {
        if (line == null || line.isBlank()) return;
        keeperLine = line;
        keeperFor = KEEPER_SECONDS;
        say(line);
    }

    // --- the Eye Spy board -------------------------------------------------------------

    /**
     * What this walker could ask the party to find, from where they are standing.
     *
     * <p>Worked out on this side, from the shared book and the biome underfoot —
     * exactly as the shops and the boats are, and for the same reason: it is a
     * pure function of things this machine already has, and asking the host would
     * be asking a question it could only answer with the same arithmetic. See
     * {@link Bounty#choices}, which is the function both ends call.
     */
    private List<AnimalDef> bountyChoices() {
        if (streamer == null) return List.of();
        return view().bounties().choices(view().guide(),
                streamer.biomeAt(px, py).key(), Bounty.CHOICES);
    }

    /** The species the board's cursor is on, or {@code null} for an empty list. */
    private String bountyCursor() {
        List<AnimalDef> choices = bountyChoices();
        if (choices.isEmpty()) return null;
        return choices.get(Math.min(bountyIndex, choices.size() - 1)).key();
    }

    private void openBounties() {
        panel = Panel.BOUNTY;
        bountyIndex = 0;
        bountyScroll = 0;
        dragBar = 0;
        bountyLine = "";
    }

    /**
     * The board: a list of things to ask for on the left, and what is already
     * pinned up on the right.
     *
     * <p>Driven by the same two hands as the shop and the satchel, off the same
     * {@link SatchelBox}, for the reason the shop gives: a third way of working a
     * list is a third chance to select the row above the one that was clicked.
     */
    private void updateBounty(InputManager input) {
        List<AnimalDef> choices = bountyChoices();
        SatchelBox box = satchelBox();

        int step = 0;
        if (KeyBinds.pressed(input, GameAction.MENU_DOWN)) step = 1;
        if (KeyBinds.pressed(input, GameAction.MENU_UP)) step = -1;
        if (step != 0 && !choices.isEmpty()) {
            bountyIndex = Math.floorMod(bountyIndex + step, choices.size());
        }

        int wheel = input.getWheelRotation();
        if (wheel != 0) {
            bountyScroll = boundScroll(bountyScroll + wheel * WHEEL_ROWS,
                    choices.size(), box.rows());
        }

        boolean moved = pointerMoved(input);
        int row = box.columnAt(pointerX, pointerY) == 0 ? box.rowAt(pointerY) : -1;
        int index = row < 0 ? -1 : bountyScroll + row;
        if (index >= choices.size()) index = -1;
        if (index >= 0 && moved) bountyIndex = index;

        boolean post = KeyBinds.pressed(input, GameAction.MENU_SELECT);
        if (input.isMouseJustPressed()) {
            if (box.overClose(pointerX, pointerY)) {
                panel = Panel.NONE;
                return;
            }
            if (index >= 0) {
                bountyIndex = index;
                post = true;
            }
        }
        if (post && !choices.isEmpty()) request("bounty");
        if (KeyBinds.pressed(input, GameAction.WATCH_BOUNTY)) panel = Panel.NONE;
        if (KeyBinds.pressed(input, GameAction.WATCH_PICK)) panel = Panel.NONE;
    }

    /** What the board said back, on the panel and in the log. */
    private void bountySays(String line) {
        if (line == null || line.isBlank()) return;
        bountyLine = line;
        say(line);
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
        // A name being typed owns the keyboard until it is finished — nothing
        // below this line should read a key while it is.
        if (renamingId != 0) {
            typeName(input);
            return;
        }

        List<Chart> maps = carriedMaps();
        List<String> items = view().satchel().keys();
        int carrying = maps.size() + items.size();
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
                satchelIndex = carrying == 0 ? 0
                        : Math.floorMod(satchelIndex + step, carrying);
                satchelScroll = clampScroll(satchelScroll, satchelIndex, carrying,
                        box.rows());
            }
        }

        // --- the mouse -------------------------------------------------------
        boolean moved = pointerMoved(input);
        int mx = pointerX, my = pointerY;
        int over = box.columnAt(mx, my);

        if (dragging(input, box, carrying, recipes.size())) return;

        int wheel = input.getWheelRotation();
        if (wheel != 0) {
            int which = over >= 0 ? over : recipeColumn ? 1 : 0;
            if (which == 1) {
                recipeScroll = boundScroll(recipeScroll + wheel * WHEEL_ROWS,
                        recipes.size(), box.rows());
            } else {
                satchelScroll = boundScroll(satchelScroll + wheel * WHEEL_ROWS,
                        carrying, box.rows());
            }
        }

        int hovered = -1;
        if (over >= 0) {
            int row = box.rowAt(my);
            int total = over == 1 ? recipes.size() : carrying;
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
                    useCarried(maps, items, hovered);
                    return;
                }
            }
        }

        if (KeyBinds.pressed(input, GameAction.MENU_SELECT)) {
            if (recipeColumn && !recipes.isEmpty()) {
                craft(recipes.get(Math.min(recipeIndex, recipes.size() - 1)));
            } else if (carrying > 0) {
                useCarried(maps, items, Math.min(satchelIndex, carrying - 1));
                return;
            }
        }
        // F2, which is what renames a thing in a list everywhere else. The map
        // screen deliberately has no rename on it: a name is a property of the
        // map as an object, so it is changed where the object is kept.
        if (!recipeColumn && input.isKeyJustPressed(java.awt.event.KeyEvent.VK_F2)
                && satchelIndex < maps.size()) {
            beginRename(maps.get(satchelIndex));
            return;
        }
        if (KeyBinds.pressed(input, GameAction.WATCH_SATCHEL)) panel = Panel.NONE;
    }

    /**
     * The maps in this player's satchel — empty unless they are in debug mode.
     *
     * <p>The gate is here as well as on the host, and not because the host
     * needs help: a client that drew rows for maps it may not have would be a
     * client whose inventory changed shape the moment somebody else typed a
     * code. See {@link Debug.Power#MAPS}.
     */
    private List<Chart> carriedMaps() {
        if (!debugging()) return List.of();
        return view().maps().carriedBy(session.selfId());
    }

    /**
     * Do the obvious thing with the row under the cursor — where the row may be
     * a map.
     *
     * <p>Maps sit at the top of the carrying column rather than in a column of
     * their own, and the reason is that they belong there: a map is a thing in
     * your satchel, and a third column would say it was a different sort of
     * possession. It also keeps the panel's arithmetic to one index over one
     * list, which is the arithmetic {@code SatchelBox} exists to protect.
     */
    private void useCarried(List<Chart> maps, List<String> items, int index) {
        if (index < maps.size()) {
            openChart(maps.get(index).id());
            return;
        }
        useFromSatchel(items.get(index - maps.size()));
    }

    /**
     * Start renaming a map: the field opens on the name it already has, with
     * that name <em>selected</em>.
     *
     * <p>Selected in the sense every rename in every file manager means: the
     * first character typed replaces the whole of it, and a backspace instead
     * puts the caret at the end and edits it. That is the behaviour of the only
     * gesture this is — renaming a thing in a list — and without it the field
     * opens on "Deciduous Woods 0E 0N" and the only way to get rid of that is
     * twenty-one presses of backspace.
     */
    private void beginRename(Chart chart) {
        renamingId = chart.id();
        renameText.setLength(0);
        renameText.append(chart.name());
        renameSelected = true;
    }

    /** Whether the name in the field is still the old one, waiting to be typed over. */
    private boolean renameSelected;

    /**
     * A map's name being typed.
     *
     * <p>Enter commits it, Escape drops it, and a blank name is refused rather
     * than accepted — a map called nothing cannot be picked out of a list, and
     * "" is what you get by holding backspace while you think.
     */
    private void typeName(InputManager input) {
        if (input.isKeyJustPressed(java.awt.event.KeyEvent.VK_ESCAPE)) {
            renamingId = 0;
            return;
        }
        if (input.isKeyJustPressed(java.awt.event.KeyEvent.VK_BACK_SPACE)) {
            renameSelected = false;
            if (renameText.length() > 0) renameText.deleteCharAt(renameText.length() - 1);
        }
        for (char c : input.consumeTypedChars().toCharArray()) {
            if (c < ' ') continue;
            if (renameSelected) {
                renameText.setLength(0);
                renameSelected = false;
            }
            if (renameText.length() >= Chart.MAX_NAME_LENGTH) break;
            renameText.append(c);
        }
        if (!input.isKeyJustPressed(java.awt.event.KeyEvent.VK_ENTER)) return;
        String name = renameText.toString().trim();
        if (!name.isEmpty()) {
            if (session.local() != null) {
                session.local().renameMap(session.selfId(), renamingId, name);
            } else {
                session.client().sendRenameChart(renamingId, name);
            }
        }
        renamingId = 0;
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
     * The house catalogue — driven by the same two hands as the satchel, and
     * for the same reason.
     *
     * <p>Ten houses fit on one page, so there is nothing to scroll; what it
     * needed was a pointer that picks a row, a click that buys it, a key to
     * turn it, and a ✕. Where the house goes is not on this screen at all: it
     * lands in front of you, which is the whole of "place it anywhere" and is
     * one fewer thing to explain than a ghost preview nobody asked for.
     */
    private void updateHomes(InputManager input) {
        List<HousePlan> plans = HousePlan.all();
        SatchelBox box = homesBox();
        if (KeyBinds.pressed(input, GameAction.MENU_DOWN)) homeIndex++;
        if (KeyBinds.pressed(input, GameAction.MENU_UP)) homeIndex--;
        homeIndex = Math.floorMod(homeIndex, plans.size());
        if (KeyBinds.pressed(input, GameAction.WATCH_TURN_HOME)) {
            homeTurn = (homeTurn + 1) % Homestead.TURNS;
        }
        // Left or right takes the house you are standing in back down again,
        // which is the one verb that needs no row of its own: there is only
        // ever the one house under your feet.
        if (KeyBinds.pressed(input, GameAction.MENU_RIGHT)
                || KeyBinds.pressed(input, GameAction.MENU_LEFT)) {
            packUp();
            return;
        }

        boolean moved = pointerMoved(input);
        int row = box.columnAt(pointerX, pointerY) == 0 ? box.rowAt(pointerY) : -1;
        if (row >= 0 && row >= plans.size()) row = -1;
        if (row >= 0 && moved) homeIndex = row;

        boolean buy = KeyBinds.pressed(input, GameAction.MENU_SELECT);
        if (input.isMouseJustPressed()) {
            if (box.overClose(pointerX, pointerY)) {
                panel = Panel.NONE;
                return;
            }
            if (overPackUp(box)) {
                packUp();
                return;
            }
            if (row >= 0) {
                homeIndex = row;
                buy = true;
            }
        }
        if (buy) {
            HousePlan plan = plans.get(homeIndex);
            if (session.local() != null) {
                // Solo, the refusal comes back with the reason attached. Online
                // it arrives as an info line a moment later — see
                // WatchServer's "home" case, which sends one either way.
                say(session.local().buyHome(session.selfId(), plan, homeTurn).line());
            } else {
                session.client().sendHome(plan.key(), homeTurn);
            }
        }
        if (KeyBinds.pressed(input, GameAction.WATCH_HOMES)) panel = Panel.NONE;
    }

    /** Take down the house we are standing in, wherever the request has to go. */
    private void packUp() {
        if (session.local() != null) {
            say(session.local().packUp(session.selfId()).line());
        } else {
            session.client().sendPackUp();
        }
    }

    /**
     * Where the catalogue's parts are — the satchel's box, one column wide.
     *
     * <p>The same record, because the two panels want the same three answers
     * ("which row is that", "is the pointer on the ✕", "where does the list
     * start") and a second nearly-identical layout type would be a second
     * chance to get one of them wrong.
     */
    private SatchelBox homesBox() {
        int w = Math.min(660, Math.max(340, viewportWidth - 80));
        int h = Math.min(500, Math.max(260, viewportHeight - 80));
        int x = (viewportWidth - w) / 2, y = (viewportHeight - h) / 2;
        int listTop = y + 68;
        return new SatchelBox(x, y, w, h, listTop, HousePlan.all().size(), w - 40);
    }

    /** The "take it down again" button: {@code x, y, w, h}. */
    private int[] packUpButton(SatchelBox box) {
        return new int[]{box.x() + box.w() - 158, box.y() + box.h() - 42, 138, 28};
    }

    private boolean overPackUp(SatchelBox box) {
        int[] b = packUpButton(box);
        return pointerX >= b[0] && pointerX < b[0] + b[2]
                && pointerY >= b[1] && pointerY < b[1] + b[3];
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
        // Where the animation clock has got to at the moment of drawing, which
        // is a fraction of a step past the last one the simulation ran. That
        // fraction is exactly what `alpha` is for: without it a frame drawn
        // between two steps shows the clock the earlier one left behind, and a
        // display running faster than the simulation draws the same instant
        // twice and then skips one. See FrameCadence.
        double now = animClock + alpha * lastStep;
        frameSeconds = drawnAt < 0 ? 0 : Math.max(0, now - drawnAt);
        drawnAt = now;
        drawClock = now;
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
        // Everything burning, worked out once and handed over before anything
        // is submitted: the painter shades each triangle as it queues it and
        // cannot be told about a lantern afterwards. On a card this is a
        // uniform block set once for the whole frame.
        lights.gather(view(), eye.x(), eye.y(), eye.z(), drawClock);
        renderer.setLights(lights.lights());
        applyVisibility(weather);
        applyAtmosphere(weather);
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
        // The party's own tracks, laid over whatever ground the chunks just put
        // down — and drawn after it on both paths, by the sort bias on the
        // painter and by the translucent layer on a card. Neither of those is
        // about the order they are submitted in: a decal that sorts under the
        // thing it is a decal on is not drawn at all, and half of one is worse
        // than none. See TrackMesher.SORT_BIAS for what that costs.
        renderer.submit(trackMesh(), TRACKS_KEY);
        renderer.submit(homeMesh(), HOMES_KEY);
        renderer.flush(target);

        drawWeatherOverlay(target, weather);
        drawEyepiece(target);
        drawHud(target, biome, weather);
        switch (panel) {
            case SATCHEL -> drawSatchel(target);
            case HOMES -> drawHomes(target);
            case SHOP -> drawShop(target);
            case MAP -> mapPanel.draw(target, view(), streamer.field(), viewportWidth,
                    viewportHeight);
            case BOUNTY -> drawBounty(target);
            case PAUSED -> drawPaused(target);
            case NONE -> { }
        }
        // The last two, and deliberately over the panels as well.
        //
        // Being hit while reading a recipe is still being hit, and a satchel
        // screen must not be the thing that hides the only warning this game
        // gives. The poll card is there for the same reason turned around: T and
        // U are read above the panel branch precisely so a screen cannot vote
        // against you by being open, and a card nobody can see would make that
        // pointless.
        drawTag(target);
        drawHurt(target);
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

    /**
     * The sun and the air this frame is standing in.
     *
     * <p>Everything here is already on screen somewhere: the weather decides
     * how far you can see, and it decides in exactly the same breath how thick
     * the air between you and the treeline looks and whether there is a sun
     * behind it. {@code Weather.visibility} is the one number both are taken
     * from, because it is the one the weather already interpolates across a
     * change — deriving the haze from anything else would give a world whose
     * fog thickened smoothly and whose sun switched off.
     *
     * <p>Only the GL backend does anything with this; see
     * {@link WatchRenderer#setAtmosphere}.
     */
    private void applyAtmosphere(Weather weather) {
        double visibility = submerged
                ? Math.min(weather.visibility(), UNDERWATER_VISIBILITY)
                : weather.visibility();
        double haze = Math.max(0, 1 - visibility);
        renderer.setAtmosphere(haze, SkyLight.overcastFrom(visibility), submerged,
                // Where the mist lies: the ground under the player's own feet,
                // which is the only height in view this scene actually knows
                // without asking the streamer for a hillside it has not meshed.
                streamer.groundAt(px, py), drawClock);
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
        //
        // Twice a stride, because both feet land in one — and driven by the
        // eased speed, so it fades in and out over a tenth of a second instead
        // of switching on and off with the movement key. Only on foot: an
        // oarsman's head does not bounce and a swimmer's does not either, and
        // at nine and a half metres a second this term used to shake the camera
        // hard enough to be the first thing anybody said about rowing.
        if (!thirdPerson && !airborne && cycleNow() == Gait.Cycle.STRIDE) {
            eyeHeight += Math.sin(gait * Math.PI * 4) * 0.028
                    * Math.min(1, animSpeed / WatchPlayer.WALK_SPEED);
        }
        // The landing, in the camera as well as in the body: the knees take the
        // drop, so the eye goes with them and comes back up over the same third
        // of a second. Without it a jump ends with the view stopping dead,
        // which is what falling on concrete looks like rather than landing.
        eyeHeight -= settle * LANDING_DIP;
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
        // <b>Not a shadow caster</b>, and that is a framerate decision rather
        // than an artistic one. This mesh is rebuilt from scratch every frame —
        // that is what "dynamic" means — so a backend caching a shadow map
        // against what is standing in it would find this changed on every
        // single frame and rebuild the map for the whole wood, throwing away
        // the one saving that makes the pass affordable while walking. What is
        // lost is a shadow under a moving animal, at a texel size where it
        // would have crawled anyway; what is kept is the trees'. See
        // Mesh.casts.
        Mesh.Builder mesh = Mesh.builder(ox, oy, 0, false, frame).casts(false);
        float[] uv = new float[4];

        for (WatchView.Creature creature : view.creatures()) {
            AnimalModels.Loaded model = AnimalModels.of(creature.def());
            model.draw(mesh, creature.def(), creature.x() - ox, creature.y() - oy,
                    creature.z(), creature.yaw(), creature.state(), creature.phase(), 1);
        }

        // Where everybody is this frame, worked out before anything is drawn:
        // a rower and the boat under them are drawn in two different passes and
        // have to be given the same answer, and the easing that produces it may
        // only be advanced once a frame.
        posePlayers(view);

        // Every walker, including this one: in third person you are looking at
        // yourself, and in first person your own body is still what casts the
        // shadow, sits in the boat, and shows above the water.
        double[] hand = new double[3];
        for (WatchView.Walker walker : view.walkers()) {
            if (walker.id() == view.selfId() && !thirdPerson) continue;
            drawWalker(mesh, walker, posed.get(walker.id()), ox, oy);
            // …and whatever they have lit, in their hand. Everybody's, because
            // the whole point of a lantern in a party is that the other seven
            // can see it moving. Your own is drawn by the view model instead,
            // in the camera's frame, where the hands holding it are.
            if (!walker.carryingLight()) continue;
            LightField.handHold(walker, hand);
            drawLampInHand(mesh, LightKind.ofItem(walker.light()), hand[0] - ox,
                    hand[1] - oy, hand[2], walker.yaw(), 1);
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
            Gait.Step rower = rowerOf(view, boat.id());
            double bx = boat.x(), by = boat.y(), byaw = boat.yaw();
            // A boat with nobody in it keeps its oars shipped along the rail;
            // one being rowed swings them, at whatever point of the stroke its
            // rower has reached. Same stroke, same clock, one description.
            double swing = BoatModel.SHIPPED;
            if (rower != null) {
                bx = rower.x();
                by = rower.y();
                byaw = rower.yaw();
                swing = rower.phase();
            }
            BoatModel.boat(mesh, bx - ox, by - oy, boat.z(), byaw,
                    bobOf(boat.id()), swing);
        }
        // …and our own, which is in the party list but may not have reached the
        // view yet, and whose position we know better than the last snapshot.
        if (boatId != 0 && rowerOf(view, boatId) == null) {
            Boats.Boat mine = view.boats().byId(streamer.field(), boatId);
            if (mine != null) {
                BoatModel.boat(mesh, px - ox, py - oy, mine.z(), yaw,
                        bobOf(boatId), rowPhase);
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
                        lure.z() + 1.20, 1.1, drawClock * LURE_SPIN);
            }
        }

        // Anything a wendigo has in the air, and the embers behind it.
        //
        // <b>Built to be seen coming.</b> The first version was two thin pale
        // struts, and at twenty-four metres a second that is a thing a player
        // notices *after* it has hit them — which makes the whole ranged attack
        // feel like unexplained damage rather than like something they could
        // have stepped out of the way of. So it is now a metre of burning bone:
        // a bone shaft, a barbed head, and a core in the thrower's own fire
        // colour, with a trail of embers laid along the ground it has already
        // covered. The trail is what actually does the work — it is visible
        // along the path *behind* the shard, so it says where the thing came
        // from as well as where it is.
        for (Hurl hurl : view.hurls()) {
            // The other thing that flies, and it is drawn as what it is: three
            // beads of water tapering back along the way it came, rather than a
            // shaft. See com.larsons.engine.watch.Tag.
            if (Tag.JET.equals(hurl.species())) {
                double cos = Math.cos(hurl.pitch());
                double jx = Math.sin(hurl.yaw()) * cos;
                double jy = -Math.cos(hurl.yaw()) * cos;
                double jz = Math.sin(hurl.pitch());
                WatchMaterials.uv(WatchMaterial.WATER, uv);
                for (int bead = 0; bead < 3; bead++) {
                    double back = bead * 0.34;
                    double size = 0.075 - bead * 0.018;
                    Shapes.blob(mesh, hurl.x() - ox - jx * back, hurl.y() - oy - jy * back,
                            hurl.z() - jz * back, size, size, size, uv,
                            bead == 0 ? 0xBDE8FF : 0x86C8EE);
                }
                continue;
            }
            AnimalDef thrower = AnimalRegistry.byKey(hurl.species());
            int bone = thrower == null ? 0xD8D2C0
                    : AnimalSkins.regionColour(thrower, AnimalSkins.Region.HARD);
            int fire = thrower == null ? 0xFF9A40
                    : AnimalSkins.regionColour(thrower, AnimalSkins.Region.GLOW);
            WatchMaterials.uv(WatchMaterial.BARK, uv);
            double cos = Math.cos(hurl.pitch());
            double fx = Math.sin(hurl.yaw()) * cos;
            double fy = -Math.cos(hurl.yaw()) * cos;
            double fz = Math.sin(hurl.pitch());
            double hx = hurl.x() - ox, hy = hurl.y() - oy, hz = hurl.z();
            // The shaft.
            Shapes.strut(mesh, hx - fx * SHARD_LENGTH, hy - fy * SHARD_LENGTH,
                    hz - fz * SHARD_LENGTH, hx + fx * SHARD_LENGTH,
                    hy + fy * SHARD_LENGTH, hz + fz * SHARD_LENGTH,
                    0.075, 0.075, uv, bone);
            // The fire down the middle of it, standing a little proud so it is
            // the part that catches the eye.
            Shapes.strut(mesh, hx - fx * SHARD_LENGTH * 0.55,
                    hy - fy * SHARD_LENGTH * 0.55, hz - fz * SHARD_LENGTH * 0.55,
                    hx + fx * SHARD_LENGTH * 0.75, hy + fy * SHARD_LENGTH * 0.75,
                    hz + fz * SHARD_LENGTH * 0.75, 0.095, 0.095, uv, fire);
            // A barbed head, and a cross-piece: a shape rather than a streak.
            Shapes.strut(mesh, hx + fx * SHARD_LENGTH * 0.6,
                    hy + fy * SHARD_LENGTH * 0.6, hz + fz * SHARD_LENGTH * 0.6,
                    hx + fx * SHARD_LENGTH * 1.25, hy + fy * SHARD_LENGTH * 1.25,
                    hz + fz * SHARD_LENGTH * 1.25, 0.045, 0.045, uv, bone);
            Shapes.strut(mesh, hx - fy * SHARD_BARB, hy + fx * SHARD_BARB, hz,
                    hx + fy * SHARD_BARB, hy - fx * SHARD_BARB, hz,
                    0.048, 0.048, uv, bone);
            Shapes.strut(mesh, hx, hy, hz - SHARD_BARB, hx, hy, hz + SHARD_BARB,
                    0.042, 0.042, uv, bone);
        }
        sparks.mesh(mesh, ox, oy);

        // Every satchel somebody has dropped by dying, within sight. In the
        // moving mesh for the litter's reason below, turned up a notch: a heap
        // appears the instant somebody is killed and is gone the instant
        // somebody gathers it, and neither event has anything to do with a
        // chunk's level of detail changing.
        //
        // Drawn as the things that are actually in it, in a ring, with a sack
        // in the middle: an anonymous crate would be findable and would tell
        // you nothing, and being able to see your own spyglass lying on the fen
        // from thirty metres away is the whole reason to walk back for it.
        for (Spill.Pile pile : view.spills().near(px, py, litterRange + LITTER_RESTEP)) {
            double sz = streamer.groundAt(pile.x(), pile.y());
            WatchMaterials.uv(WatchMaterial.PLANK, uv);
            Shapes.box(mesh, pile.x() - ox, pile.y() - oy, sz + 0.16, 0.24, 0.24, 0.16,
                    drawClock * 0.12, uv, WatchMaterials.shade(WatchMaterial.DARK_BARK));
            int shown = 0;
            for (String key : pile.items().keySet()) {
                if (shown >= SPILL_ITEMS) break;
                double a = shown * Math.PI * 2 / SPILL_ITEMS + drawClock * 0.25;
                ItemModel.item(mesh, key, pile.x() - ox + Math.cos(a) * 0.34,
                        pile.y() - oy + Math.sin(a) * 0.34, sz + 0.05, 1.0, a);
                shown++;
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

        // The trading posts within sight, and whoever is keeping them.
        //
        // In the <b>moving</b> mesh rather than in a chunk's static flora, for
        // {@link Litter}'s reason turned the other way up: a chunk is re-meshed
        // when its level of detail changes and not when a sign swings or a
        // keeper breathes, and splitting one building between a static mesh and
        // a moving one to save a few hundred triangles would mean the shed and
        // the person in it could disagree about where they were. There is at
        // most one post in a view — Shops.CELL is 540 m — so the cost is a
        // building, once, and only when there is one to draw.
        for (Shops.Shop shop : view.shops().near(streamer.field(), px, py, SHOP_RANGE)) {
            double away = Math.hypot(shop.x() - px, shop.y() - py);
            ShopModel.post(mesh, shop, ox, oy, drawClock, away < WARES_RANGE);
            if (away > KEEPER_RANGE) continue;
            // Behind the counter, facing out of it — and turning to look at
            // whoever is nearest. That the head follows you is worth more than
            // everything else in this block put together. How far back they
            // stand is the model's own number, because the model puts their
            // ledger and their jackdaw on the counter from it.
            double back = Shops.COUNTER_OUT - KeeperModel.BEHIND_COUNTER;
            double kx = shop.x() + Math.sin(shop.yaw()) * back;
            double ky = shop.y() - Math.cos(shop.yaw()) * back;
            double look = Math.atan2(px - kx, -(py - ky));
            KeeperModel.keeper(mesh, shop.keeper(), kx - ox, ky - oy,
                    shop.z() + ShopModel.DECK, shop.yaw(),
                    away < NOTICE_RANGE ? look : shop.yaw(), drawClock,
                    Math.floorMod(shop.id(), 97) * 0.11);

            // The ranger, out in front of the post and off to one side, facing
            // the wood rather than the counter. Two figures at one building
            // read as two people only if they are not both framed by it, which
            // is what puts this one out in the clearing — and it is the only
            // character in the game that can be replaced wholesale by a file
            // dropped in the models folder. See RangerModel.
            double rx = shop.x() + Math.sin(shop.yaw()) * RangerModel.OUT
                    + Math.cos(shop.yaw()) * RangerModel.BESIDE;
            double ry = shop.y() - Math.cos(shop.yaw()) * RangerModel.OUT
                    + Math.sin(shop.yaw()) * RangerModel.BESIDE;
            // On the ground the chunk actually shows, for Litter's reason: the
            // post's own z is the generator's, and a ranger a few centimetres
            // into the turf is more noticeable than a dropped acorn is.
            double rangerLook = Math.atan2(px - rx, -(py - ry));
            RangerModel.ranger(mesh, RangerModel.of(shop.id()), rx - ox, ry - oy,
                    streamer.groundAt(rx, ry), shop.yaw(),
                    away < NOTICE_RANGE ? rangerLook : shop.yaw(), drawClock,
                    Math.floorMod(shop.id(), 89) * 0.13);
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

        // Every fire and lantern standing in the world.
        //
        // In the <b>moving</b> mesh, for the reason the litter and the shops
        // are: a chunk is re-meshed when its level of detail changes and not
        // when somebody lights a fire, and a flame has to sway on the frame
        // clock rather than on whenever the ground it stands on was last built.
        // A camp is a handful of these, so the cost is a handful of cones.
        for (PlacedLight standing : view.lights().all()) {
            double dx = standing.x() - px, dy = standing.y() - py;
            if (dx * dx + dy * dy > LIGHT_RANGE * LIGHT_RANGE) continue;
            LightModel.light(mesh, standing, ox, oy, drawClock);
        }

        boardFaces(mesh, view, ox, oy, uv);

        return mesh.build();
    }

    /**
     * How far off a fire or a lantern is still built as a solid, in metres.
     *
     * <p>Generous, and it can afford to be: a light is a few dozen triangles
     * and there are never many. It also has to be — the whole point of a
     * lantern hung at a camp is that you can see it from across the valley on
     * the way back, and a light whose <em>glow</em> reaches further than its
     * model would be a pool of light with a hole in the middle.
     */
    private static final double LIGHT_RANGE = 220;

    /**
     * A lit light in somebody's hand.
     *
     * <p>One method rather than the same two-branch switch written twice,
     * because the view model draws exactly this in the camera's frame while the
     * moving mesh draws it in the world's.
     *
     * <p>Dropped below the point it hangs from rather than stood on it: both
     * models are built standing on their own feet, a lantern swings from its
     * bail and a torch is gripped halfway up, so each has to be lowered by
     * about the height of the part being held.
     */
    private void drawLampInHand(Mesh.Builder mesh, LightKind kind, double x, double y,
                                double z, double yaw, double burn) {
        if (kind == null) return;
        if (kind == LightKind.TORCH) {
            LightModel.torch(mesh, x, y, z - 0.16, yaw, burn, drawClock, 0.42);
        } else {
            LightModel.lantern(mesh, x, y, z - 0.22, yaw, kind, burn, drawClock, 0.75);
        }
    }

    // --- map boards ------------------------------------------------------------------

    /**
     * How many facets a board's map is drawn as, on a side — on a card, and on
     * a machine drawing through Java2D.
     *
     * <p>A board is {@value #BOARD_FACE} metres across, so thirty facets is
     * about six centimetres each: at the two or three metres somebody stands to
     * read one that is bigger than a letter on this page, and the country on it
     * reads at a glance. The painter gets fourteen for the reason it gets a
     * shorter litter ring and a shorter track ring — the count is what it costs,
     * and 2 × 14² quads is a tenth of what 2 × 30² is.
     */
    private static final int BOARD_GRID_GPU = 30;

    private static final int BOARD_GRID_PAINTER = 14;

    /** How far off a board's map is still drawn, in metres. */
    private static final double BOARD_RANGE_GPU = 34;

    private static final double BOARD_RANGE_PAINTER = 18;

    /**
     * How many boards are drawn at once.
     *
     * <p>A cap rather than a ring, because the ring is already short and the
     * pathological case is not a distance: it is a party that has built a wall
     * of boards in one clearing, which would otherwise be a frame of nothing
     * but map. Nearest first, so the cap takes the ones nobody is reading.
     */
    private static final int MAX_BOARDS = 4;

    /** How wide the map on a board's face is, in metres — square, inside the timber. */
    private static final double BOARD_FACE = 1.72;

    /** How far the map stands off the timber, in metres, so it cannot z-fight it. */
    private static final double BOARD_LIFT = 0.012;

    /**
     * The maps on every board in sight, laid on the timber as flat facets.
     *
     * <p><b>So that standing in front of a board is enough.</b> The panel is
     * for reading the small print — a note's words, a map's name, which sheet
     * is which — and everything else about a board is something a party should
     * get by walking past it: how much country has been surveyed, where the
     * camp and the posts are, what somebody has circled. A board whose maps
     * only exist inside a screen is a noticeboard with the notice in a drawer.
     *
     * <p>Both large faces, and that is deliberate rather than thorough: a board
     * is built facing whichever way its builder happened to be looking, and a
     * party walks round it. One face would mean half of them reading the back
     * of it.
     *
     * <p><b>Not gated on {@link #debugging()}</b>, and that is not an oversight.
     * {@link Debug.Power#MAPS} withholds the making of maps while their price is
     * undecided; it does not withhold <em>seeing</em> one. A board is a thing
     * standing in the world that a host has already built and pinned, and a
     * board only half the party can see is not a board.
     */
    private void boardFaces(Mesh.Builder mesh, WatchView view, double ox, double oy,
                            float[] uv) {
        boardTriangles = 0;
        if (streamer == null) return;
        List<Cartography.Board> boards = view.maps().boards();
        if (boards.isEmpty()) return;
        boolean gpu = renderer.acceleratedByGpu();
        int grid = gpu ? BOARD_GRID_GPU : BOARD_GRID_PAINTER;
        double range = gpu ? BOARD_RANGE_GPU : BOARD_RANGE_PAINTER;

        List<Cartography.Board> near = new ArrayList<>();
        for (Cartography.Board board : boards) {
            if (board.distanceTo(px, py) <= range) near.add(board);
        }
        near.sort((a, b) -> Double.compare(a.distanceTo(px, py), b.distanceTo(px, py)));

        WatchMaterials.uv(WatchMaterial.PAPER, uv);
        int drawn = 0;
        for (Cartography.Board board : near) {
            if (drawn >= MAX_BOARDS) break;
            int[] cells = BoardImage.cells(streamer.field(), view.maps(), board, grid);
            // Nothing pinned, or the ground is still being painted on
            // ChartImage's worker. Either way the board is bare timber for now,
            // which is exactly what an empty board should look like.
            if (cells == null) continue;
            drawn++;

            // The piece's own axes: +a along its width, +b through its
            // thickness, up left alone — the basis Shapes.box turns it on.
            double cos = Math.cos(board.yaw()), sin = Math.sin(board.yaw());
            double ax = cos, ay = sin;
            double bx = -sin, by = cos;
            double half = BOARD_FACE / 2;
            double stand = HouseKit.BOARD_STAND + BOARD_LIFT;
            double cx = board.x() - ox, cy = board.y() - oy, cz = board.z();

            // The −b face, seen from the −b side: its right is +a, so the
            // winding a→b→d→e in Shapes.mosaic gives a normal of a × up = −b.
            Shapes.mosaic(mesh, cx - bx * stand, cy - by * stand, cz,
                    ax * half, ay * half, 0, 0, 0, half, cells, grid, uv);
            // …and the +b face, which is the same picture mirrored, because a
            // viewer on that side has their right along −a.
            Shapes.mosaic(mesh, cx + bx * stand, cy + by * stand, cz,
                    -ax * half, -ay * half, 0, 0, 0, half, cells, grid, uv);
            boardTriangles += grid * grid * 4;
        }
    }

    /**
     * How many triangles the map boards put in the world last frame.
     *
     * <p>For tests, and for nothing on screen: "the board shows its maps" is
     * otherwise a claim about pixels, and this is the same claim about
     * geometry.
     */
    private int boardTriangles;

    /**
     * Work out where every walker is to be drawn this frame, and how far
     * through their cycle.
     *
     * <p><b>Once a frame, before anything is drawn, for everybody — including
     * the walker holding the mouse.</b> Two passes need the answer (the party,
     * and the boats a rower may be sitting in), the easing behind it may only
     * be advanced once per frame, and a rower drawn from one answer in a boat
     * drawn from another is a person sliding about on their own thwart.
     *
     * <p>This player is not eased and is not taken from the view at all. Their
     * own position is simulated here, every step, and the row in the view is
     * whatever was last sent to the host — which is twenty times a second. Read
     * from there, your own body in third person stepped along at the send rate
     * while the camera following it moved smoothly, so the two disagreed five
     * times a second for as long as you walked.
     */
    private void posePlayers(WatchView view) {
        posed.clear();
        for (WatchView.Walker walker : view.walkers()) {
            if (walker.id() == view.selfId()) {
                posed.put(walker.id(), new Gait.Step(px, py, pz, yaw, animSpeed,
                        phaseFor(cycleNow()), leap()));
            } else {
                posed.put(walker.id(), gaits.follow(walker.id(), walker.x(), walker.y(),
                        walker.z(), walker.yaw(), cycleOf(walker), aloft(walker),
                        frameSeconds));
            }
        }
    }

    /**
     * This player's own jump, as the model wants it.
     *
     * <p>Not eased and not derived: the physics that produced it is right here,
     * so the pose can be told outright rather than inferred from a position the
     * way everybody else's is.
     */
    private WalkerModel.Leap leap() {
        WalkerModel.Leap now = new WalkerModel.Leap(airPose, climb, settle);
        return now.still() ? WalkerModel.Leap.GROUNDED : now;
    }

    /**
     * Whether somebody else is off the ground.
     *
     * <p>From the terrain, like {@link #afloat} and for the same reason: a jump
     * reaches this client as a {@code z} that went up and came down, and the
     * ground under it is something the client generates for itself. Nothing
     * about jumping goes on the wire.
     *
     * <p>The margin is generous because the number it is compared against is
     * not: a walker's drawn height is eased toward the last one that arrived,
     * and the ground under them is a bilinear sample of a heightfield they may
     * be walking across at four metres a second. Half a metre is above every
     * disagreement those two can produce and below the top of any jump.
     */
    private boolean aloft(WatchView.Walker walker) {
        if (walker.inBoat() || streamer == null) return false;
        double ground = streamer.groundAt(walker.x(), walker.y());
        if (TerrainField.WATER_LEVEL - ground > WADING) return false;
        return walker.z() > ground + AIRBORNE_CLEARANCE;
    }

    /** How far above the ground somebody else has to be to be drawn jumping. */
    private static final double AIRBORNE_CLEARANCE = 0.5;

    /** This player's own clock for a cycle. See {@link #driveGait}. */
    private double phaseFor(Gait.Cycle cycle) {
        return switch (cycle) {
            case STROKE -> rowPhase;
            case SWIM -> swimPhase;
            case STRIDE -> gait;
        };
    }

    /**
     * Which cycle somebody else is running, from where they are.
     *
     * <p>Nothing about it is sent. A walker's position and the ground under it
     * are enough to say whether they are on their feet, in a boat or out of
     * their depth, and all three are things this client already has — so a
     * swimmer looks like a swimmer to everybody watching without the snapshot
     * growing by a byte.
     */
    private Gait.Cycle cycleOf(WatchView.Walker walker) {
        if (walker.inBoat()) return Gait.Cycle.STROKE;
        if (aloft(walker)) return Gait.Cycle.STRIDE;
        return afloat(walker.x(), walker.y(), walker.z())
                ? Gait.Cycle.SWIM : Gait.Cycle.STRIDE;
    }

    /**
     * One player, as a walking figure — or, in a boat, as a seated one working
     * a pair of oars.
     *
     * <p>Was three boxes and a hat brim; is now {@link WalkerModel}, whose
     * limbs pivot about a hip and a shoulder. The phase and the speed come from
     * {@link Gait} rather than from the wire: nobody's cadence is sent, it is
     * derived from how fast they are actually seen to be moving, so their legs
     * are in step with their body by construction and nobody's stride runs at
     * the frame rate or at the snapshot rate.
     */
    private void drawWalker(Mesh.Builder mesh, WatchView.Walker walker, Gait.Step step,
                            double ox, double oy) {
        if (step == null) return;
        double x = step.x() - ox, y = step.y() - oy;
        int coat = WalkerModel.coatFor(walker.id());
        // Whatever they bought at a counter, over the top of the figure — see
        // com.larsons.engine.watch.Cosmetics. Off their snapshot row rather than
        // out of any local state, so somebody who put a hat on two valleys away
        // is wearing it here on the next tick.
        List<String> worn = walker.wornKeys();

        // Where the eye of the figure just drawn is, so a raised glass can be
        // put at it. A rower's is over the thwart they are sitting on, which is
        // aft of the boat's own centre and lower than a standing head; a
        // swimmer's is on the end of a spine that may be pointing anywhere.
        double eyeAlong = 0.18, eyeZ;
        switch (cycleOf(walker)) {
            case STROKE -> {
                // The hull's own waterline, recovered from the rower rather
                // than looked up: a player in a boat stands on its deck, which
                // is exactly Boats.DECK below the water they are floating on.
                // That keeps the rower on the same swell as the boat without
                // either of them having to find the other.
                double waterZ = step.z() + Boats.DECK;
                double bob = bobOf(walker.boatId());
                WalkerModel.rower(mesh, x, y, waterZ, step.yaw(), bob, step.phase(),
                        coat, worn);
                eyeAlong += BoatModel.SEAT_ALONG;
                eyeZ = BoatModel.thwartZ(waterZ, bob) + WalkerModel.ROWER_EYE;
            }
            case SWIM -> {
                double bodyPitch = WalkerModel.swimPitch(step.speed(), walker.pitch(),
                        walker.submerged());
                WalkerModel.swimmer(mesh, x, y, step.z(), step.yaw(), bodyPitch,
                        WalkerModel.swimDrive(step.speed()), step.phase(),
                        !walker.submerged(), coat, worn);
                double[] eye = new double[2];
                WalkerModel.swimEye(bodyPitch, eye);
                eyeAlong += eye[0];
                eyeZ = step.z() + eye[1];
            }
            default -> {
                WalkerModel.walker(mesh, x, y, step.z(), step.yaw(), walker.crouching(),
                        step.phase(), step.speed(), step.leap(), coat, worn);
                eyeZ = step.z() + (walker.crouching() ? 1.10 : 1.68);
            }
        }

        // Somebody with a glass up, seen from outside: a tube at their eye,
        // pointing where they are pointing. This is the only way in the game to
        // tell at a glance that a friend across the clearing has found
        // something and which way to look — the party's own gesture, before
        // anybody clicks anything.
        if (walker.glassing()) {
            ItemModel.item(mesh, Spyglass.ITEM,
                    x + Math.sin(step.yaw()) * eyeAlong,
                    y - Math.cos(step.yaw()) * eyeAlong,
                    eyeZ, 1.0, step.yaw());
        }
    }

    /** Where whoever is rowing a boat is being drawn, or {@code null} if it is moored. */
    private Gait.Step rowerOf(WatchView view, long boat) {
        for (WatchView.Walker walker : view.walkers()) {
            if (walker.boatId() == boat) return posed.get(walker.id());
        }
        return null;
    }

    /** How far through its bobbing cycle a hull is, in turns. */
    private double bobOf(long boat) {
        return RowStroke.wrap(drawClock * BOAT_BOB + boat * 0.13);
    }

    /** This player, before the first snapshot has told us where we are. */
    private void drawSelf(Mesh.Builder mesh, double ox, double oy) {
        int coat = WalkerModel.coatFor(session.selfId());
        // Out of our own wardrobe rather than off a row, because there is no
        // row yet — that is what this method is for.
        List<String> worn = view().outfit().wornKeys();
        switch (cycleNow()) {
            case STROKE -> WalkerModel.rower(mesh, px - ox, py - oy, pz + Boats.DECK,
                    yaw, bobOf(boatId), rowPhase, coat, worn);
            case SWIM -> WalkerModel.swimmer(mesh, px - ox, py - oy, pz, yaw,
                    WalkerModel.swimPitch(animSpeed, pitch, submerged),
                    WalkerModel.swimDrive(animSpeed), swimPhase, !submerged, coat, worn);
            case STRIDE -> WalkerModel.walker(mesh, px - ox, py - oy, pz, yaw,
                    crouching, gait, animSpeed, leap(), coat, worn);
        }
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
        // Your own hands, held a foot from the camera and rebuilt every frame.
        // They cast nothing, for buildDynamicMesh's reason and one of their
        // own: a hand's shadow would be thrown across the whole clearing by a
        // low sun, from a piece of geometry that is not really in the world.
        Mesh.Builder mesh = Mesh.builder(eye.x(), eye.y(), eye.z(), false, frame)
                .casts(false);
        int sleeve = WalkerModel.coatFor(session.selfId());
        // What is being carried, in the right hand, when there is something
        // worth showing: a rod that is out, or the last thing picked up.
        String held = heldItem();
        Gait.Cycle cycle = cycleNow();
        // Both hands, working together, whenever they are both busy with the
        // same thing — and nothing in a hand to show instead.
        if (held == null && cycle != Gait.Cycle.STRIDE) {
            if (cycle == Gait.Cycle.STROKE) {
                // Rowing: on the stroke's own clock — the one the oars in the
                // water in front of you are swinging on — rather than swinging
                // against each other at a walk driven by nine and a half metres
                // a second, which is what a boat used to look like from inside.
                WalkerModel.rowingHands(mesh, 0, 0, 0, eye.dirX(), eye.dirY(),
                        eye.dirZ(), eye.rightX(), eye.rightY(), rowPhase, sleeve);
            } else {
                // Swimming: the same breaststroke the body behind the camera is
                // doing. It used to be the walking view model at a slower
                // clock, which is a person striding along in front of your face
                // while you cross a lake.
                WalkerModel.swimmingHands(mesh, 0, 0, 0, eye.dirX(), eye.dirY(),
                        eye.dirZ(), eye.rightX(), eye.rightY(), swimPhase, sleeve);
            }
            return mesh.build();
        }
        double sway = Math.min(1, animSpeed / WatchPlayer.WALK_SPEED);
        // Treading water with something in your hand: the hands still sweep
        // rather than swing, but slowly and without going anywhere.
        double bob = cycle == Gait.Cycle.SWIM ? swimPhase : gait;
        WalkerModel.hands(mesh, 0, 0, 0, eye.dirX(), eye.dirY(), eye.dirZ(),
                eye.rightX(), eye.rightY(), bob,
                cycle == Gait.Cycle.SWIM ? 0.6 : sway, reach, sleeve);

        if (held != null) {
            double forward = WalkerModel.HAND_FORWARD + reach * 0.42 + 0.10;
            double out = WalkerModel.HAND_SIDE;
            double down = WalkerModel.HAND_DROP - reach * 0.16;
            double[] up = new double[3];
            WalkerModel.cameraUp(eye.dirX(), eye.dirY(), eye.dirZ(),
                    eye.rightX(), eye.rightY(), up);
            double upX = up[0], upY = up[1], upZ = up[2];
            double hx = eye.dirX() * forward + eye.rightX() * out + upX * -down;
            double hy = eye.dirY() * forward + eye.rightY() * out + upY * -down;
            double hz = eye.dirZ() * forward + upZ * -down;
            double turn = Math.atan2(eye.dirX(), -eye.dirY());
            // A lit lamp is drawn burning; everything else, the same lamp
            // included, is drawn by the item model, which draws it cold. Which
            // of the two this is comes off the same flag the light does, so the
            // flame in your hand and the light on the ground cannot disagree.
            LightKind lamp = carryingLamp();
            if (lamp != null && lamp.item() != null && lamp.item().equals(held)) {
                drawLampInHand(mesh, lamp, hx, hy, hz, turn, 1);
            } else {
                ItemModel.item(mesh, held, hx, hy, hz, 1.0, turn);
            }
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
        // The water gun beats even a cast line, because being it beats
        // everything: there is no moment during a round when what is in your
        // hand is a question, and a player who has to look at their hands to
        // find out whether they can shoot has been told the wrong thing.
        if (view().tag().isIt(session.selfId())) return Tag.GUN;
        if (me != null && me.rod().active()) return "rod";
        // A lit lamp beats the pickup flash and loses to a cast line, which is
        // the order of how much each of the three is a thing you are *doing*:
        // a rod is out for a minute, a lantern is out for the evening, and a
        // flash is a second of "look what I found".
        if (pickedFlash <= 0 || flashedKey == null) {
            LightKind lamp = carryingLamp();
            return lamp == null ? null : lamp.item();
        }
        return view().satchel().has(flashedKey) ? flashedKey : null;
    }

    /**
     * The kind of light this player is carrying <em>lit</em>, or {@code null}.
     *
     * <p>Off the view's own walker row rather than off the local game, so it is
     * one answer online and off: the host decides whether there is anything
     * burning, and the row it puts in the snapshot is what the hands, the light
     * and the HUD all read.
     */
    private LightKind carryingLamp() {
        WatchView.Walker me = view().self();
        return me == null ? null : LightKind.ofItem(me.light());
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
        // Which volume is open and how full it is. Two numbers, and they are the
        // ones that answer "why is this wren suddenly worth something": a page
        // that has just been stamped is an empty one, and an empty page is a
        // wood full of animals that all count again.
        label(target, "Vol. " + view.guide().volume() + " · "
                        + view.guide().tallied() + " on this page",
                pad, pad + 90, HUD_SMALL, HUD_DIM);
        drawCompass(target, pad, pad + 112);
        // The needle, on the compass rather than beside it: whoever is it is
        // given a bearing to the nearest walker, which is the one thing a chase
        // in an endless wood needs and cannot get any other way.
        drawQuarry(target, pad, pad + 112);

        // Top right: the party.
        int right = viewportWidth - pad;
        int row = pad + 18;
        Tag tag = view.tag();
        for (WatchView.Walker walker : view.walkers()) {
            String label = walker.name()
                    + (walker.id() == view.selfId() ? " (you)" : "")
                    // Who is it, beside their name and nowhere else: a party
                    // spread over a valley has to be able to check at a glance,
                    // and the answer changes every couple of minutes.
                    + (tag.isIt(walker.id()) ? "  · IT" : "");
            // Somebody else's health, as a fraction beside their name and only
            // while they are hurt. A party spread over a valley finds out that
            // one of them has walked into something this way and no other, and
            // "Kara is on a third" is the sentence that turns three people
            // watching birds into three people going to help.
            if (walker.hurt()) {
                label += "  " + Math.max(1, (int) Math.round(walker.health() * 100)) + "%";
            }
            label(target, label, right - target.textWidth(label, HUD_FONT), row,
                    HUD_FONT, walker.hurt() ? HUD_WARN
                            : walker.id() == view.selfId() ? HUD_ACCENT : HUD_DIM);
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

        // The health, above both — and by the same argument, only once
        // something has taken some. This is the one bar in the game that can
        // run out for good, so it is drawn wider and brighter than the other
        // two the moment it is not full, and it stays up the whole time it is
        // refilling: the ninety seconds of walking home is exactly when a
        // player wants to know how far along it is. See WatchPlayer.health.
        if (health < 0.999) drawHealth(target, barY - (breath < 0.999 ? 48 : 24));

        // What is burning in your hand, and how long it has left.
        //
        // <b>Only while something is lit</b>, on the same argument as the
        // breath bar and the health bar: a readout that is always on screen is
        // a readout nobody reads. A lantern is a thing with an hour left in it
        // and no other way of finding that out — the flame does sink as it goes
        // (see PlacedLight.burnBrightness), which is the warning you get if you
        // are looking at it, and this is the one you get if you are not.
        drawLampGauge(target, barX, barY - lampGaugeLift(), barW);

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
                    + "· B houses · Tab satchel · G guide · J eye spy";
            if (view.satchel().has(Spyglass.ITEM)) {
                keys += " · Right-click glass";
            }
            // Only with somebody to play with, because it is the one verb on
            // this line the host refuses outright on a walk for one — and a hint
            // for a key that cannot work is worse than no hint. Q joins it only
            // while there is actually a gun in your hand.
            if (view.walkers().size() >= Tag.LEAST_PLAYERS) {
                keys += tag.isIt(view.selfId()) ? " · Q squirt · T call off" : " · T tag";
            }
            // Only once the mode is on, because it is the only verb on this
            // line that would otherwise refuse — and a hint for a key that does
            // nothing is worse than no hint. See Debug.Power.MAPS.
            if (debugging()) keys += " · M map · K summon · ,. clock · / real";
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
        // What is burning, and how much of it the frame is actually carrying.
        // The second number is the one worth watching: it is capped at
        // MeshPass.MAX_LIGHTS, and a camp where it is pinned there is a camp
        // where somebody's lantern is being silently dropped.
        LightKind lamp = carryingLamp();
        lines.add("light  " + renderer.lightCount() + " of " + MeshPass.MAX_LIGHTS
                + " lit · "
                + view.lights().burning() + "/" + view.lights().size() + " placed · "
                + (lamp == null ? "hands empty" : "carrying " + lamp.displayName()));
        // …and the sky the card is drawing under, which is otherwise invisible
        // to everything but a screenshot. The shadow figure is the one to watch
        // while tuning: it is what turns the sun's second pass on, so a zero
        // here with a sun in the frame is a wood that has quietly stopped
        // costing anything and quietly stopped having shadows in it.
        //
        // A zero after dark is expected and says nothing about whether there
        // are shadows: that is when the brightest fire takes over the pass
        // instead, and "map" below reports either of them. See GlLampShadow.
        MeshPass.Sky sky = renderer.atmosphere();
        lines.add("sky    sun " + Math.round(Math.toDegrees(Math.asin(
                Math.max(-1, Math.min(1, sky.sunZ()))))) + "° · shadow "
                + String.format(java.util.Locale.ROOT, "%.2f", sky.shadow())
                + " · haze " + String.format(java.util.Locale.ROOT, "%.4f", sky.haze())
                + "/m · air " + String.format(java.util.Locale.ROOT, "%.2f",
                        sky.scatter())
                + (renderer.acceleratedByGpu()
                        ? " · map " + (renderer.redrewShadows() ? "redrawn" : "kept")
                        : " (painter: none of it)"));
        // What is hunting, and what is lying on the ground because it caught
        // somebody. Both are rare enough that these two numbers are zero nearly
        // always, and are exactly what somebody testing the mutants wants when
        // they are not. See Mutants.
        WatchView.Creature hunting = null;
        for (WatchView.Creature creature : view.creatures()) {
            if (creature.def().hostile()) hunting = creature;
        }
        lines.add("hunted  " + (hunting == null ? "nothing about"
                : hunting.def().name() + " · "
                        + Math.round(Math.hypot(hunting.x() - px, hunting.y() - py))
                        + " m · " + hunting.state().key())
                + " · " + view.spills().count() + " dropped");
        lines.add("tracks  " + tracks.prints() + " prints · " + tracks.trailCount()
                + " trails · " + trackMesh.triangleCount() + " tri · "
                + Math.round(tracks.strengthAt(px, py) * 100) + "% underfoot");
        lines.add("glass  " + (glass.up()
                ? "×" + Math.round(glass.power()) + " · " + Math.round(glass.range()) + " m"
                : "down"));
        lines.add("guide  " + view.guide().discovered() + " / " + view.guide().total()
                + " · " + view.guide().points() + " pts · vol " + view.guide().volume()
                + " (" + view.guide().tallied() + " scored)");
        // The two party games, on one line each. Both are idle nearly always,
        // which is exactly why they are worth a line: the interesting states are
        // the ones nobody can reproduce on demand.
        Tag tag = view.tag();
        lines.add("tag  " + tag.describe().toLowerCase(java.util.Locale.ROOT)
                + (tag.polling() ? "  ·  " + tag.tally() : "")
                + (tag.running() ? "  ·  ×" + tag.speed(view.selfId()) : ""));
        lines.add("eye spy  " + view.bounties().openCount() + " open · "
                + view.bounties().settled().size() + " claimed");
        // Where the nearest trading post is, which is the one thing in this
        // world that is worth walking a long way to and cannot be seen from
        // anywhere near it.
        Shops.Shop post = view.shops().nearest(streamer.field(), px, py, Shops.CELL * 2);
        lines.add("post  " + (post == null ? "none within " + Math.round(Shops.CELL * 2) + " m"
                : Math.round(Math.hypot(post.x() - px, post.y() - py)) + " m · "
                        + post.sign() + " · " + post.stock().size() + " lines"));

        int pad = 16;
        // Clear of the compass strip, which is the last thing the ordinary HUD
        // puts down the left.
        int top = pad + 144;
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

    /**
     * The needle whoever is it is given: which way the nearest walker is, and
     * how far.
     *
     * <p><b>Only while it, and that is the whole balance of the feature.</b> A
     * bearing to the nearest person, given to everybody, would end every round in
     * about forty seconds — the field would simply spread out along the vectors
     * they were shown. Given to one player it is the answer to the only question
     * a chase in a wood with no edges and no landmarks can ask, which is "which
     * way did they go".
     *
     * <p>Drawn on the compass strip rather than beside it, because it is a
     * bearing and that strip is where this game keeps bearings. It is one more
     * mark sliding under the same fixed marker, in a colour nothing else on the
     * HUD uses.
     */
    private void drawQuarry(DrawTarget target, int x, int y) {
        Tag tag = view().tag();
        if (!tag.isIt(session.selfId())) return;
        WatchView.Walker quarry = view().nearestOther(session.selfId(), px, py);
        if (quarry == null) return;

        int width = 190;
        double bearing = Math.atan2(quarry.x() - px, -(quarry.y() - py));
        double delta = bearing - yaw;
        delta = Math.atan2(Math.sin(delta), Math.cos(delta));
        double metres = Math.hypot(quarry.x() - px, quarry.y() - py);

        // Behind you, the needle pins to whichever edge it went off — an arrow
        // that simply vanished when somebody ran round you would be a compass
        // that stops working at the exact moment it is being read.
        boolean ahead = Math.abs(delta) <= Math.PI / 2;
        double clamped = Math.max(-Math.PI / 2, Math.min(Math.PI / 2, delta));
        int at = (int) (x + width / 2.0 + clamped / (Math.PI / 2) * (width / 2.0));
        target.fillRect(at - 1, y - 20, 3, 10, QUARRY_INK);

        String line = quarry.name() + "  " + Math.round(metres) + " m"
                + (ahead ? "" : delta > 0 ? "  →" : "  ←");
        label(target, line, at - target.textWidth(line, HUD_SMALL) / 2, y - 24,
                HUD_SMALL, QUARRY_INK);
    }

    /**
     * The poll card, and the banner under it.
     *
     * <p><b>Top centre, over the world, and never a panel.</b> Everything else in
     * this game that asks a question stops the walk while it is asking — see
     * {@link #updatePanel}, which returns before {@link #walk}. This one cannot:
     * the question a running round asks is "shall we stop", and a screen that
     * froze eight people mid-chase for half a minute to ask it would hand whoever
     * is it their thirty seconds back. So it is two lines and two keys, in the one
     * part of the screen this HUD otherwise leaves empty.
     */
    private void drawTag(DrawTarget target) {
        Tag tag = view().tag();
        if (tag.idle()) return;
        int cx = viewportWidth / 2;
        int top = 26;

        if (tag.polling()) {
            String question = tag.question();
            String keys = tag.voted(session.selfId())
                    ? "answered · T yes · U no · " + (int) Math.ceil(tag.voteRemaining()) + "s"
                    : "T — yes · U — no · " + (int) Math.ceil(tag.voteRemaining()) + "s";
            int width = Math.max(target.textWidth(question, HUD_BOLD),
                    target.textWidth(keys, HUD_SMALL)) + 40;
            target.fillRect(cx - width / 2, top - 4, width, 60, HUD_PANEL);
            target.drawRect(cx - width / 2, top - 4, width, 60, HUD_ACCENT);
            label(target, question, cx - target.textWidth(question, HUD_BOLD) / 2,
                    top + 18, HUD_BOLD, HUD_INK);
            label(target, keys, cx - target.textWidth(keys, HUD_SMALL) / 2, top + 36,
                    HUD_SMALL, HUD_ACCENT);
            label(target, tag.tally(), cx - target.textWidth(tag.tally(), HUD_SMALL) / 2,
                    top + 50, HUD_SMALL, HUD_DIM);
            top += 72;
        }

        if (!tag.running()) return;
        boolean mine = tag.isIt(session.selfId());
        String banner = mine
                ? tag.freeze() > 0
                        ? "YOU ARE IT — counting " + (int) Math.ceil(tag.freeze())
                        : "YOU ARE IT — Q to squirt"
                : tag.it() + " is it"
                        + (tag.freeze() > 0
                                ? " — frozen for " + (int) Math.ceil(tag.freeze()) : "");
        label(target, banner, cx - target.textWidth(banner, HUD_BOLD) / 2, top + 16,
                HUD_BOLD, mine ? QUARRY_INK : HUD_WARN);

        // The count, as a bar rather than only as a number: thirty seconds is a
        // long time to watch a digit, and everybody else on the walk is reading
        // the same bar to work out how long they have to get somewhere.
        if (tag.freeze() > 0) {
            int barW = 190, barH = 5;
            int barX = cx - barW / 2, barY = top + 24;
            double left = tag.freeze() / Tag.FREEZE_SECONDS;
            target.fillRect(barX, barY, barW, barH, new Color(0, 0, 0, 150));
            target.fillRect(barX, barY, (int) (barW * left), barH, QUARRY_INK);
        }
    }

    /** The one colour on this HUD that means "the game of tag", and nothing else. */
    private static final Color QUARRY_INK = new Color(120, 190, 240);

    /**
     * The health bar, and the state of the wound under it.
     *
     * <p>Wider and taller than the stillness and breath bars, and its own
     * colour, because the three of them live in one column at the bottom of the
     * screen and a player glancing down at a dead run has to be able to tell
     * which is which without reading a word. Green while there is plenty, amber
     * on the way down, red at the end.
     *
     * <p>The line under it is the one number that is not on the bar: whether it
     * is coming back yet. {@link WatchPlayer#HEAL_DELAY} seconds after the last
     * blow it starts refilling, and until then a player standing in a hollow
     * counting to six wants to be told that is what they are doing.
     */
    private void drawHealth(DrawTarget target, int y) {
        int width = 190, height = 9;
        int x = viewportWidth / 2 - width / 2;
        target.fillRect(x - 1, y - 1, width + 2, height + 2, new Color(0, 0, 0, 170));
        Color ink = health > 0.6 ? new Color(126, 200, 122)
                : health > 0.3 ? HUD_WARN : new Color(224, 96, 84);
        target.fillRect(x, y, (int) (width * Math.max(0, health)), height, ink);
        // A tick at each quarter, so "two more of those" is something a player
        // can read off the bar rather than having to feel out.
        for (int i = 1; i < 4; i++) {
            target.fillRect(x + width * i / 4, y, 1, height, new Color(0, 0, 0, 120));
        }
        WatchView.Walker me = view() == null ? null : view().self();
        boolean mending = me != null && me.health() < 1 && health > 0;
        String text = mending ? "Health  ·  mending" : "Health";
        label(target, text, x + width / 2 - target.textWidth(text, HUD_SMALL) / 2,
                y - 5, HUD_SMALL, health > 0.3 ? HUD_DIM : HUD_WARN);
    }

    /**
     * Red at the edges when something hits you, and a line across the middle
     * when it kills you.
     *
     * <p>Every other thing that happens in this game is reported in the log in
     * the bottom right, which is the correct place for it: nothing there is
     * urgent. A blow from behind is the exception and the only one — a player
     * looking at a bird has no way of knowing they are being hit unless the
     * screen tells them where they are already looking.
     *
     * <p>A vignette rather than a full wash, so it never hides the thing that is
     * about to hit you again.
     */
    private void drawHurt(DrawTarget target) {
        if (hurtFlash > 0) {
            double strength = hurtFlash * hurtFlash;
            int bands = 7;
            int thickness = Math.max(6, Math.min(viewportWidth, viewportHeight) / 22);
            for (int i = 0; i < bands; i++) {
                int alpha = (int) (strength * 46 * (1 - i / (double) bands));
                if (alpha <= 0) continue;
                Color wash = new Color(180, 24, 20, alpha);
                int inset = i * thickness / bands;
                int band = Math.max(1, thickness / bands);
                target.fillRect(0, inset, viewportWidth, band, wash);
                target.fillRect(0, viewportHeight - inset - band, viewportWidth, band,
                        wash);
                target.fillRect(inset, 0, band, viewportHeight, wash);
                target.fillRect(viewportWidth - inset - band, 0, band, viewportHeight,
                        wash);
            }
        }
        if (deathNotice <= 0) return;
        // And the notice, which says the two things a player needs after dying
        // and cannot work out for themselves: where they are now, and that
        // their things are still somewhere.
        String head = "You were killed";
        String body = "Your satchel is lying where you fell — it will wait for you";
        int cy = viewportHeight / 2 - 70;
        int width = Math.max(target.textWidth(head, TITLE_FONT),
                target.textWidth(body, HUD_FONT)) + 44;
        int x = viewportWidth / 2 - width / 2;
        // Fading out over its last second, so it leaves rather than vanishing.
        int alpha = (int) (Math.min(1, deathNotice) * 220);
        target.fillRect(x, cy - 30, width, 66, new Color(28, 10, 10, alpha));
        target.drawRect(x, cy - 30, width, 66, new Color(200, 90, 80, alpha));
        label(target, head, viewportWidth / 2 - target.textWidth(head, TITLE_FONT) / 2,
                cy - 4, TITLE_FONT, new Color(238, 176, 168));
        label(target, body, viewportWidth / 2 - target.textWidth(body, HUD_FONT) / 2,
                cy + 22, HUD_FONT, HUD_DIM);
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

    /** How far above the stillness bar the lamp gauge sits, past the other two. */
    private int lampGaugeLift() {
        int lift = 24;
        if (breath < 0.999) lift += 24;
        if (health < 0.999) lift += 24;
        return lift;
    }

    /**
     * What is burning in your hand, and how much of it is left.
     *
     * <p>Drawn in the light's own colour rather than in the HUD's, which is
     * the whole of why it is worth a bar: three of the four lights in this game
     * are a different colour and the bar says which one you are carrying
     * without reading the label. An eternal light has nothing to run out, so it
     * gets a full bar and the word rather than a countdown to a number that
     * never falls.
     */
    private void drawLampGauge(DrawTarget target, int x, int y, int width) {
        LightKind lamp = carryingLamp();
        if (lamp == null) return;
        WatchView.Walker me = view().self();
        double left = lamp.eternal() || me == null ? 1
                : Math.max(0, Math.min(1, me.lightHours() / lamp.burnHours()));
        int height = 6;
        target.fillRect(x, y, width, height, new Color(0, 0, 0, 140));
        int rgb = lamp.rgb();
        target.fillRect(x, y, (int) (width * left), height,
                new Color((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF));
        String text = lamp.eternal() ? lamp.displayName()
                : left <= 0 ? lamp.displayName() + " — out"
                : left < 0.2 ? lamp.displayName() + " — burning low"
                : lamp.displayName();
        label(target, text, x + width / 2 - target.textWidth(text, HUD_SMALL) / 2,
                y - 5, HUD_SMALL, left < 0.2 && !lamp.eternal() ? HUD_WARN : HUD_DIM);
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
            // A flat wash over the finished frame, and on the painter path it
            // is the whole of the fog: that backend's haze is a distance fade
            // and nothing else, so without this a fog bank ten metres away is
            // as clear as a clear day.
            //
            // <b>On a card it is a quarter of that</b>, because the world
            // underneath already has real fog in it — an exponential with a
            // height to it, drifting, and lit by whatever lamps are standing in
            // it. Laying an opaque grey rectangle over that would flatten the
            // one thing it is for. What is left is the part a depth-aware fog
            // genuinely cannot do, which is the haze on the inside of your own
            // eye. See SkyLight.
            double wash = renderer.acceleratedByGpu() ? 28 : 110;
            target.fillRect(0, 0, viewportWidth, viewportHeight,
                    new Color(206, 212, 216, (int) (wash * fogAmount)));
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
        List<Chart> maps = carriedMaps();
        List<String> items = satchel.keys();
        int carried = maps.size() + items.size();
        List<Recipes.Recipe> recipes = Recipes.all();
        // Only bounded here, never pulled back to the cursor: the wheel and the
        // bar move the window on its own, and a draw that dragged it back to
        // wherever the cursor happened to be would undo the scroll every frame.
        satchelScroll = boundScroll(satchelScroll, carried, box.rows());
        recipeScroll = boundScroll(recipeScroll, recipes.size(), box.rows());

        // --- carrying ------------------------------------------------------
        int col = box.leftX();
        String carrying = satchel.bottomless()
                ? "Carrying  (everything — debug)"
                : "Carrying  (" + satchel.kinds() + " kinds, "
                        + satchel.total() + " things)";
        if (!maps.isEmpty()) {
            carrying = maps.size() + (maps.size() == 1 ? " map · " : " maps · ") + carrying;
        }
        target.drawText(carrying, col, y + 68, HUD_BOLD,
                recipeColumn ? HUD_DIM : HUD_ACCENT);
        for (int i = 0; i < box.rows(); i++) {
            int index = satchelScroll + i;
            if (index >= carried) break;
            int top = box.rowTop(i);
            int text = top + ROW_HEIGHT - 9;
            if (index == satchelIndex && !recipeColumn) {
                target.fillRect(col - 8, top, box.colWidth(), ROW_HEIGHT - 2,
                        new Color(60, 110, 70, 160));
            }
            if (index < maps.size()) {
                drawMapRow(target, box, maps.get(index), col, top, text);
                continue;
            }
            String key = items.get(index - maps.size());
            Forage.Item item = Forage.byKey(key);
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
        if (carried == 0) {
            target.drawText("Nothing yet — press E out there", col,
                    box.rowTop(0) + 18, HUD_SMALL, HUD_DIM);
        }
        scrollbar(target, box.barX(0), box.barTop(), box.barHeight(), satchelScroll,
                box.rows(), carried);

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
     * One map's row in the satchel.
     *
     * <p><b>The icon is the map itself.</b> Every other row in this list draws
     * the thing it is a row for ({@link ItemPortrait}), and a map's picture is
     * already a square of paper painted from the seed — so the honest icon for
     * one is a thumbnail of it, and eight maps in a satchel are told apart by
     * looking rather than by reading eight names.
     *
     * <p>It may not be painted yet ({@link ChartImage} bakes on a worker), in
     * which case the row draws an empty sheet for a frame or two. That is the
     * same bargain the world itself makes while chunks arrive.
     */
    private void drawMapRow(DrawTarget target, SatchelBox box, Chart chart, int col,
                            int top, int text) {
        BufferedImage paper = streamer == null ? null
                : ChartImage.of(streamer.field(), chart);
        if (paper != null) {
            target.drawImage(paper, col, top + 3, ICON, ICON);
        } else {
            target.fillRect(col, top + 3, ICON, ICON, new Color(198, 183, 152));
        }
        target.drawRect(col, top + 3, ICON, ICON, new Color(120, 102, 74));
        boolean renaming = renamingId == chart.id();
        String name = renaming ? renameText + "▏" : chart.name();
        target.drawText("MAP", col + ICON + 8, text, HUD_SMALL, HUD_WARN);
        target.drawText(fitted(target, name, box.colWidth() - ICON - 130),
                col + ICON + 44, text, HUD_FONT, renaming ? HUD_WARN : HUD_INK);
        target.drawText(renaming ? "Enter" : Math.round(chart.span()) + " m",
                col + box.colWidth() - 96, text, HUD_SMALL, HUD_DIM);
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
        List<Chart> maps = carriedMaps();
        if (!recipeColumn && satchelIndex < maps.size()) {
            // A map's own footer, drawn here rather than through the item path
            // because a map is not a Forage key and has no portrait: the big
            // picture is the map, and the line under it says how to work it.
            Chart chart = maps.get(satchelIndex);
            BufferedImage paper = streamer == null ? null
                    : ChartImage.of(streamer.field(), chart);
            if (paper != null) {
                target.drawImage(paper, x + 20, top, DETAIL_ICON, DETAIL_ICON);
            } else {
                target.fillRect(x + 20, top, DETAIL_ICON, DETAIL_ICON,
                        new Color(198, 183, 152));
            }
            target.drawRect(x + 20, top, DETAIL_ICON, DETAIL_ICON, new Color(120, 102, 74));
            int mapX = x + 28 + DETAIL_ICON;
            int mapWidth = x + w - 20 - mapX;
            target.drawText(fitted(target, chart.name(), mapWidth), mapX, top + 20,
                    HUD_BOLD, HUD_INK);
            target.drawText(fitted(target, chart.describe(), mapWidth), mapX, top + 40,
                    HUD_SMALL, HUD_DIM);
            String hint = renamingId == chart.id()
                    ? (renameSelected
                            ? "Type to replace the name, or backspace to edit it"
                            : "Enter keeps the new name, Esc leaves it alone")
                    : "Enter or click opens it  ·  F2 renames it  ·  "
                            + chart.marks() + " marks on it";
            target.drawText(fitted(target, hint, mapWidth), mapX, top + 58, HUD_SMALL,
                    renamingId == chart.id() ? HUD_WARN : HUD_ACCENT);
            return;
        }
        int itemIndex = satchelIndex - maps.size();
        if (recipeColumn && !recipes.isEmpty()) {
            Recipes.Recipe recipe = recipes.get(Math.min(recipeIndex, recipes.size() - 1));
            key = recipe.output();
            title = recipe.name() + "  ·  " + recipe.station().label()
                    + (recipe.affordable(satchel) ? "" : "  ·  short of ingredients");
            // The cost in full, because the list's copy of it may have been
            // cut to fit the column.
            note = recipe.costLine() + "  —  " + recipe.note();
        } else if (!items.isEmpty()) {
            key = items.get(Math.max(0, Math.min(itemIndex, items.size() - 1)));
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

    /**
     * The house catalogue.
     *
     * <p>A shop screen more than a build screen, and it reads like one: what it
     * is called, what it costs, what it is, and the balance the book has to pay
     * with. The row is greyed when the guide cannot afford it rather than being
     * hidden, because the top of this list is meant to be something a party can
     * see and cannot yet have.
     */
    private void drawHomes(DrawTarget target) {
        SatchelBox box = homesBox();
        int x = box.x(), y = box.y(), w = box.w(), h = box.h();
        target.fillRect(x, y, w, h, HUD_PANEL);
        target.drawRect(x, y, w, h, HUD_ACCENT);
        target.drawText("Houses", x + 20, y + 32, TITLE_FONT, HUD_INK);
        drawCloseButton(target, box);

        FieldGuide guide = view().guide();
        int purse = guide.points();
        String balance = purse + (purse == 1 ? " point" : " points") + " to spend";
        target.drawText(balance, x + w - target.textWidth(balance, HUD_BOLD) - 46, y + 54,
                HUD_BOLD, HUD_ACCENT);

        List<HousePlan> plans = HousePlan.all();
        homeIndex = Math.floorMod(homeIndex, plans.size());
        for (int i = 0; i < plans.size(); i++) {
            HousePlan plan = plans.get(i);
            boolean can = guide.affords(plan.price()) || debugging();
            int top = box.rowTop(i);
            int text = top + ROW_HEIGHT - 9;
            if (i == homeIndex) {
                target.fillRect(x + 14, top, w - 28, ROW_HEIGHT - 2,
                        new Color(60, 110, 70, 160));
            }
            target.drawText(plan.displayName(), x + 20, text, HUD_FONT,
                    can ? HUD_INK : new Color(130, 140, 132));
            String shape = plan.storeys() + (plan.storeys() == 1 ? " floor" : " floors")
                    + (plan.tree() ? " · up a tree" : "");
            target.drawText(shape, x + 190, text, HUD_SMALL,
                    can ? HUD_DIM : new Color(120, 110, 100));
            String price = plan.priceLine();
            target.drawText(price, x + w - target.textWidth(price, HUD_SMALL) - 30, text,
                    HUD_SMALL, can ? HUD_ACCENT : new Color(150, 110, 100));
        }
        // Both lines are cut to leave the button its corner: the panel is as
        // narrow as 340 on a small window, and a note running under a button is
        // a note nobody can read and a button nobody can see.
        HousePlan chosen = plans.get(homeIndex);
        int room = w - 200;
        target.drawText(fitted(target, chosen.note(), room), x + 20, y + h - 46,
                HUD_SMALL, HUD_DIM);
        String facing = (homeTurn == 0 ? "Front door facing you"
                : "Turned " + (homeTurn * 45) + "° from facing you")
                + (chosen.tree() ? " · up the nearest big tree" : " · in front of you");
        target.drawText(fitted(target, facing, room), x + 20, y + h - 26,
                HUD_SMALL, HUD_ACCENT);

        int[] pack = packUpButton(box);
        boolean over = overPackUp(box);
        target.fillRect(pack[0], pack[1], pack[2], pack[3],
                over ? new Color(90, 60, 55, 200) : new Color(50, 60, 54, 170));
        target.drawRect(pack[0], pack[1], pack[2], pack[3], HUD_ACCENT);
        String label = "Take this one down";
        target.drawText(label, pack[0] + (pack[2] - target.textWidth(label, HUD_SMALL)) / 2,
                pack[1] + 19, HUD_SMALL, HUD_INK);

        String keys = "Click to buy · X turn · ←→ take down · B close";
        target.drawText(keys, x + w - target.textWidth(keys, HUD_SMALL) - 46, y + 32,
                HUD_SMALL, HUD_DIM);
    }

    /**
     * Where the shop screen's parts are.
     *
     * <p>{@link SatchelBox} again, with the shelf in its left column and the
     * keeper in the space its right one would occupy — so the row the pointer
     * is over is decided by the same arithmetic that decides it on the two
     * screens either side of this one.
     */
    private SatchelBox shopBox() {
        int w = Math.min(860, Math.max(360, viewportWidth - 80));
        int h = Math.min(540, Math.max(280, viewportHeight - 80));
        int x = (viewportWidth - w) / 2, y = (viewportHeight - h) / 2;
        int listTop = y + 92;
        int rows = Math.max(1, (y + h - 30 - listTop) / ROW_HEIGHT);
        return new SatchelBox(x, y, w, h, listTop, rows, w / 2 - 34);
    }

    /** The "ask for a fresh page" button: {@code x, y, w, h}. */
    private int[] stampButton(SatchelBox box) {
        return new int[]{box.rightX(), box.y() + box.h() - 104, box.colWidth(), 44};
    }

    private boolean overStamp(SatchelBox box) {
        return over(stampButton(box));
    }

    /**
     * One of the two headings over the left-hand list: {@code x, y, w, h}.
     *
     * <p>Fixed widths rather than measured ones, and this is the one place in
     * the panel where that is right: a hit box is worked out by
     * {@link #updateShop}, which has no {@link DrawTarget} to measure text with,
     * and a heading whose clickable area came from a different arithmetic than
     * its drawing is the exact bug {@link SatchelBox} exists to prevent. So both
     * halves read these numbers.
     */
    private int[] tabButton(SatchelBox box, boolean rail) {
        int top = box.y() + 62;
        return rail ? new int[]{box.leftX() + 118, top, 96, 26}
                : new int[]{box.leftX() - 8, top, 118, 26};
    }

    private boolean overTab(SatchelBox box, boolean rail) {
        return over(tabButton(box, rail));
    }

    /** One of the two headings, lit when its list is the one showing. */
    private void drawTab(DrawTarget target, SatchelBox box, boolean rail, String label) {
        int[] b = tabButton(box, rail);
        boolean on = rail == shopRail;
        if (on) {
            target.fillRect(b[0], b[1], b[2], b[3], new Color(44, 78, 54, 170));
        }
        target.drawText(label, b[0] + 8, b[1] + 18, HUD_BOLD,
                on ? HUD_ACCENT : new Color(120, 132, 122));
    }

    /** Whether the pointer is inside an {@code x, y, w, h}. */
    private boolean over(int[] b) {
        return pointerX >= b[0] && pointerX < b[0] + b[2]
                && pointerY >= b[1] && pointerY < b[1] + b[3];
    }

    /**
     * The shop screen.
     *
     * <p>Two halves, and they are the two things a trading post is for: a shelf
     * of materials on the left, and on the right the keeper, the balance, and
     * the offer to stamp a fresh page. The right half is deliberately the larger
     * piece of furniture even though it is one button, because turning the page
     * is the part a player has to be told about — buying things off a list needs
     * no explanation and a mechanic that hands you back a thousand animals does.
     *
     * <p>The keeper's {@linkplain com.larsons.engine.watch.Cosmetics clothes
     * rail} is a second heading over that same left-hand list rather than a
     * third column, for the reason {@link #shopRail} gives: the explanation is
     * worth more panel than the shopping is. A row on it says what clicking it
     * would do — a price, or {@code Owned}, or {@code Worn} — because it is the
     * one list in the game where the same click means three different things.
     */
    private void drawShop(DrawTarget target) {
        Shops.Shop shop = shopInReach();
        if (shop == null) return;
        SatchelBox box = shopBox();
        int x = box.x(), y = box.y(), w = box.w(), h = box.h();
        target.fillRect(x, y, w, h, HUD_PANEL);
        target.drawRect(x, y, w, h, HUD_ACCENT);
        target.drawText(shop.sign(), x + 20, y + 32, TITLE_FONT, HUD_INK);
        target.drawText(shop.keeper().fullName(), x + 20, y + 54, HUD_SMALL, HUD_DIM);
        drawCloseButton(target, box);

        FieldGuide guide = view().guide();
        int purse = guide.points();
        String balance = purse + (purse == 1 ? " point" : " points") + " to spend";
        target.drawText(balance, x + w - target.textWidth(balance, HUD_BOLD) - 46, y + 54,
                HUD_BOLD, HUD_ACCENT);

        // --- the shelf, or the rail -----------------------------------------
        List<Trading.Offer> stock = shop.stock();
        List<Cosmetics.Piece> rail = shop.rail();
        drawTab(target, box, false, "On the shelf");
        drawTab(target, box, true, "On the rail");
        int shown = shopRail ? rail.size() : stock.size();
        for (int i = 0; i < shown && i < box.rows(); i++) {
            int top = box.rowTop(i);
            int text = top + ROW_HEIGHT - 9;
            if (i == shopIndex) {
                target.fillRect(box.leftX() - 8, top, box.colWidth(), ROW_HEIGHT - 2,
                        new Color(60, 110, 70, 160));
            }
            // Everything the two lists share: a picture, a name on the left and
            // a short right-hand answer to "what would clicking this do".
            String key = shopRail ? rail.get(i).key() : stock.get(i).item();
            String name = shopRail ? rail.get(i).name() : stock.get(i).label();
            int price = shopRail ? rail.get(i).price() : stock.get(i).price();
            // A piece already owned costs nothing and says so, which is also
            // how a player finds out that clicking it is now a "wear".
            boolean owned = shopRail && view().outfit().owns(key);
            boolean can = owned || guide.affords(price);
            String right = !owned ? (shopRail ? rail.get(i).priceLine()
                            : stock.get(i).priceLine())
                    : view().outfit().wearing(key) ? "Worn" : "Owned";
            // The same picture the satchel draws, because it is the same thing:
            // a player should recognise on the shelf what they will be carrying
            // — and on the rail what they will be wearing. See ItemPortrait.
            target.drawImage(ItemPortrait.of(key, ICON, ICON_BACKDROP),
                    box.leftX(), top + 3, ICON, ICON);
            target.drawText(name, box.leftX() + ICON + 10, text, HUD_FONT,
                    can ? HUD_INK : new Color(130, 140, 132));
            target.drawText(right,
                    box.leftX() + box.colWidth() - target.textWidth(right, HUD_FONT) - 12,
                    text, HUD_FONT, owned ? HUD_DIM : can ? HUD_ACCENT : HUD_WARN);
        }
        if (shopRail && rail.isEmpty()) {
            target.drawText("Nothing hanging up today.", box.leftX(),
                    box.listTop() + 18, HUD_SMALL, HUD_DIM);
        }

        // --- the keeper -----------------------------------------------------
        int rx = box.rightX();
        int width = box.colWidth();
        target.drawText("The counter", rx, y + 80, HUD_BOLD, HUD_ACCENT);
        int line = box.listTop() + 14;
        String said = keeperFor > 0 ? keeperLine : shop.keeper().greeting();
        for (String part : wrapped(target, "“" + said + "”", width)) {
            target.drawText(part, rx, line, HUD_FONT, HUD_INK);
            line += 20;
        }
        line += 12;
        String note = null;
        String under = null;
        if (shopRail && !rail.isEmpty()) {
            Cosmetics.Piece chosen = rail.get(Math.min(shopIndex, rail.size() - 1));
            note = chosen.note();
            // What it is and where it goes, which is the one thing a picture of
            // a hat in a twenty-two pixel row cannot say.
            under = chosen.slot().label() + " — " + chosen.slot().where()
                    + (view().outfit().owns(chosen.key())
                            ? "  ·  click to wear or take off" : "");
        } else if (!shopRail && !stock.isEmpty()) {
            note = stock.get(Math.min(shopIndex, stock.size() - 1)).note();
        }
        if (note != null) {
            target.drawText(fitted(target, note, width), rx, line, HUD_SMALL, HUD_DIM);
        }
        if (under != null) {
            target.drawText(fitted(target, under, width), rx, line + 20, HUD_SMALL,
                    HUD_ACCENT);
        }

        // The page, which is the other half of what this counter does.
        int tallied = guide.tallied();
        int pageTop = y + h - 176;
        target.drawText("Volume " + guide.volume(), rx, pageTop, HUD_BOLD, HUD_INK);
        target.drawText(tallied + " species on this page", rx, pageTop + 20, HUD_SMALL,
                HUD_DIM);
        int keeps = view().guide().discovered();
        target.drawText(keeps + " stay in the book whatever you do", rx, pageTop + 56,
                HUD_SMALL, HUD_DIM);
        target.drawText(tallied == 0
                        ? "Nothing to stamp yet — go and find something"
                        : "A fresh page makes every one of them worth points again",
                rx, pageTop + 38, HUD_SMALL, tallied == 0 ? HUD_WARN : HUD_ACCENT);

        int[] button = stampButton(box);
        boolean over = overStamp(box);
        boolean can = tallied > 0;
        target.fillRect(button[0], button[1], button[2], button[3],
                !can ? new Color(30, 40, 34, 180)
                        : over ? new Color(70, 130, 84, 220) : new Color(44, 78, 54, 200));
        target.drawRect(button[0], button[1], button[2], button[3],
                can ? HUD_ACCENT : new Color(80, 92, 82));
        String stamp = "Stamp a fresh page";
        target.drawText(stamp,
                button[0] + (button[2] - target.textWidth(stamp, HUD_BOLD)) / 2,
                button[1] + 28, HUD_BOLD, can ? HUD_INK : new Color(120, 132, 122));

        String keys = "Click to buy · ↑↓ · ←→ shelf/rail · Enter · C stamp · E close";
        target.drawText(keys, x + w - target.textWidth(keys, HUD_SMALL) - 46, y + 32,
                HUD_SMALL, HUD_DIM);
    }

    /**
     * The Eye Spy board.
     *
     * <p>Two columns and they answer two different questions. On the left, what
     * you could ask for: the species of the ground under your feet, the ones
     * already in the book first because a bounty on something somebody can
     * describe is a bounty somebody can go and look for. On the right, what is
     * already pinned up and what has been claimed — which is the half a player
     * actually opens this panel for, because it is the party's list of things
     * worth doing this week.
     */
    private void drawBounty(DrawTarget target) {
        SatchelBox box = satchelBox();
        int x = box.x(), y = box.y(), w = box.w(), h = box.h();
        target.fillRect(x, y, w, h, HUD_PANEL);
        target.drawRect(x, y, w, h, HUD_ACCENT);
        target.drawText("Eye Spy", x + 20, y + 32, TITLE_FONT, HUD_INK);
        WatchBiome here = streamer == null ? null : streamer.biomeAt(px, py);
        target.drawText("What lives in " + (here == null ? "here" : here.displayName())
                        + ", and what the party has asked for",
                x + 20, y + 54, HUD_SMALL, HUD_DIM);
        drawCloseButton(target, box);

        Bounty board = view().bounties();
        String me = myName();
        boolean spent = board.postedToday(me, System.currentTimeMillis());
        String allowance = spent
                ? "Your bounty is spent — one a day"
                : "One bounty to pin up today";
        target.drawText(allowance,
                x + w - target.textWidth(allowance, HUD_BOLD) - 46, y + 54, HUD_BOLD,
                spent ? HUD_WARN : HUD_ACCENT);

        // --- what you could ask for -----------------------------------------
        List<AnimalDef> choices = bountyChoices();
        bountyScroll = boundScroll(bountyScroll, choices.size(), box.rows());
        target.drawText("Ask the party to find", box.leftX(), y + 80, HUD_BOLD,
                HUD_ACCENT);
        for (int i = 0; i < box.rows(); i++) {
            int index = bountyScroll + i;
            if (index >= choices.size()) break;
            AnimalDef def = choices.get(index);
            int top = box.rowTop(i);
            int text = top + ROW_HEIGHT - 9;
            if (index == bountyIndex) {
                target.fillRect(box.leftX() - 8, top, box.colWidth(), ROW_HEIGHT - 2,
                        new Color(60, 110, 70, 160));
            }
            target.drawImage(AnimalPortrait.of(def, ICON, ICON_BACKDROP),
                    box.leftX(), top + 3, ICON, ICON);
            boolean known = view().guide().seen(def.key());
            target.drawText(def.name(), box.leftX() + ICON + 10, text, HUD_FONT,
                    spent ? new Color(130, 140, 132) : HUD_INK);
            // Whether it is in the book at all, which is the one thing that
            // changes what kind of bounty this would be: a reminder, or a hunt.
            String note = known ? "in the book" : "never seen";
            target.drawText(note,
                    box.leftX() + box.colWidth() - target.textWidth(note, HUD_SMALL) - 12,
                    text, HUD_SMALL, known ? HUD_DIM : HUD_WARN);
        }
        if (choices.isEmpty()) {
            target.drawText("Nothing here that is not already asked for",
                    box.leftX(), box.listTop() + 20, HUD_FONT, HUD_DIM);
        }
        scrollbar(target, box.barX(0), box.barTop(), box.barHeight(), bountyScroll,
                box.rows(), choices.size());

        // --- what is already up ---------------------------------------------
        int rx = box.rightX();
        int width = box.colWidth();
        List<Bounty.Posting> open = board.open();
        target.drawText("On the board  (" + open.size() + ")", rx, y + 80, HUD_BOLD,
                HUD_ACCENT);
        int line = box.listTop() + 14;
        long now = System.currentTimeMillis();
        for (Bounty.Posting posting : open) {
            if (line > y + h - 150) break;
            boolean mine = me.equals(posting.poster());
            target.drawText(fitted(target, posting.describe(), width), rx, line,
                    HUD_FONT, mine ? HUD_DIM : HUD_INK);
            // Whose it is decides whether it is worth anything to you, so the
            // one rule this board has is on the row rather than in a footnote.
            target.drawText(mine ? "yours — you cannot claim it"
                            : Math.round(posting.hoursLeft(now)) + "h left",
                    rx + 12, line + 15, HUD_SMALL, mine ? HUD_WARN : HUD_DIM);
            line += 38;
        }
        if (open.isEmpty()) {
            target.drawText("Nothing pinned up", rx, line, HUD_FONT, HUD_DIM);
            line += 24;
        }

        List<Bounty.Posting> settled = board.settled();
        if (!settled.isEmpty() && line < y + h - 130) {
            target.drawText("Claimed", rx, line + 14, HUD_BOLD, HUD_DIM);
            line += 34;
            for (Bounty.Posting posting : settled) {
                if (line > y + h - 96) break;
                target.drawText(fitted(target, posting.describe(), width), rx, line,
                        HUD_SMALL, HUD_DIM);
                line += 18;
            }
        }

        // --- what happened last -----------------------------------------------
        int foot = y + h - 74;
        AnimalDef chosen = choices.isEmpty() ? null
                : choices.get(Math.min(bountyIndex, choices.size() - 1));
        if (chosen != null) {
            target.drawText(fitted(target, chosen.name() + "  ·  " + chosen.blurb(),
                            w - 60),
                    box.leftX(), foot, HUD_SMALL, HUD_DIM);
            target.drawText("Enter — pin it up. The world decides what it pays: "
                            + Bounty.LEAST + "–" + Bounty.MOST + " points.",
                    box.leftX(), foot + 20, HUD_SMALL,
                    spent ? new Color(130, 140, 132) : HUD_ACCENT);
        }
        if (!bountyLine.isEmpty()) {
            target.drawText(fitted(target, bountyLine, w - 60), box.leftX(), foot + 42,
                    HUD_FONT, HUD_INK);
        }

        String keys = "Click to pin · ↑↓ · Enter · J close";
        target.drawText(keys, x + w - target.textWidth(keys, HUD_SMALL) - 46, y + 32,
                HUD_SMALL, HUD_DIM);
    }

    /** What this walker is called, for the rules that are about a name. */
    private String myName() {
        WatchView.Walker me = view().self();
        return me == null ? "" : me.name();
    }

    /** A line broken onto as many rows as it needs, at the panel's own width. */
    private static List<String> wrapped(DrawTarget target, String text, int width) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isBlank() || width <= 0) return out;
        StringBuilder row = new StringBuilder();
        for (String word : text.split(" ")) {
            String candidate = row.length() == 0 ? word : row + " " + word;
            if (target.textWidth(candidate, HUD_FONT) > width && row.length() > 0) {
                out.add(row.toString());
                row = new StringBuilder(word);
            } else {
                row = new StringBuilder(candidate);
            }
        }
        if (row.length() > 0) out.add(row.toString());
        return out;
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
        // The Eye Spy board's rows are species rather than items, so its cursor
        // answers a species key. A test that could not tell the two apart would
        // pass while the board was offering fishing rods.
        if (panel == Panel.BOUNTY) return bountyCursor();
        if (panel == Panel.SHOP) {
            Shops.Shop shop = shopInReach();
            if (shop == null) return null;
            // Whichever list is up. Both answer a key out of their own
            // catalogue, and the two catalogues share no keys, so a test can
            // tell a hat from a plank without being told which tab it is on.
            List<?> rows = shopRail ? shop.rail() : shop.stock();
            if (rows.isEmpty()) return null;
            Object row = rows.get(Math.min(shopIndex, rows.size() - 1));
            return row instanceof Cosmetics.Piece piece ? piece.key()
                    : ((Trading.Offer) row).item();
        }
        if (recipeColumn) {
            List<Recipes.Recipe> recipes = Recipes.all();
            return recipes.isEmpty() ? null
                    : recipes.get(Math.min(recipeIndex, recipes.size() - 1)).output();
        }
        // Maps sit above the items in the carrying column, so the cursor's row
        // may be one — named "map:<id>" rather than by an item key, because a
        // map is not one and a test that could not tell them apart would pass
        // while the rows were in the wrong order.
        List<Chart> maps = carriedMaps();
        if (satchelIndex < maps.size()) return "map:" + maps.get(satchelIndex).id();
        List<String> items = view().satchel().keys();
        if (items.isEmpty()) return null;
        int at = Math.max(0, Math.min(satchelIndex - maps.size(), items.size() - 1));
        return items.get(at);
    }

    /** How far down its list the column with the cursor is scrolled — for tests. */
    public int panelScroll() { return recipeColumn ? recipeScroll : satchelScroll; }

    /** The map screen, so a test can ask what it is showing — for tests. */
    public MapPanel mapPanel() { return mapPanel; }

    /** How many triangles the map boards put in the world last frame — for tests. */
    public int boardTriangles() { return boardTriangles; }

    /** How far this walk can see, which is how wide a map it draws — for tests. */
    public double mapReachMetres() { return mapReach(); }

    /** What is in reach of the local player this frame, or {@code null} — for tests. */
    public WatchGame.Pickable inReach() { return inReach; }

    /** How fast this walk is moving the local player, as a multiple — for tests. */
    public double paceMultiplier() { return view().tag().speed(session.selfId()); }

    /** The camera, so a test can check where it ended up. */
    public EyeCamera camera() { return eye; }

    /** The renderer, so a test can read its counters. */
    public WatchRenderer renderer() { return renderer; }

    /** Where the local player is standing. */
    public double[] position() { return new double[]{px, py, pz}; }

    /**
     * The hour this screen is <b>drawing</b>, which is not the same question as
     * what hour the world keeps — for tests.
     *
     * <p>Worth exposing because the difference between those two is exactly
     * where the clock went wrong once: the world's hour is what animals and the
     * guide go by, this one is what the sun, the sky, the fog and the shadows
     * go by, and a test that only checks the first will pass on a screen still
     * drawing the real afternoon. See {@link #syncClock}.
     */
    public double drawnTimeOfDay() { return clock.timeOfDay(); }
}
