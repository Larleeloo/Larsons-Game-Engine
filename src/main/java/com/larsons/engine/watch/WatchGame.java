package com.larsons.engine.watch;

import com.larsons.engine.watch.build.BuildPiece;
import com.larsons.engine.watch.build.Structure;
import com.larsons.engine.watch.life.Animal;
import com.larsons.engine.watch.life.AnimalDef;
import com.larsons.engine.watch.life.AnimalRegistry;
import com.larsons.engine.watch.life.Diet;
import com.larsons.engine.watch.life.Mutants;
import com.larsons.engine.watch.world.Flora;
import com.larsons.engine.watch.world.Grove;
import com.larsons.engine.watch.world.TerrainField;
import com.larsons.engine.watch.world.TreeGenome;
import com.larsons.engine.watch.world.TreeInstance;
import com.larsons.engine.watch.world.TreeSpecies;
import com.larsons.engine.watch.world.WatchBiome;
import com.larsons.engine.watch.world.WatchChunk;
import com.larsons.engine.watch.world.WatchMaterial;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The whole game, as one object — <b>and the only place any of it happens.</b>
 *
 * <p>Solo, a scene owns one of these and drives it. Online, the <em>server</em>
 * owns one and clients own none: every action here is a request that arrives
 * over a socket and every result leaves as a message, which is the same
 * arrangement the engine's world server and its auto battler both use. The
 * consequences are the ones that matter for this game in particular:
 *
 * <ul>
 *   <li>a spotted animal is the <em>same</em> animal for everybody, because
 *       there is one list of them;</li>
 *   <li>a bird flushed by one player is flushed for the party, because there is
 *       one simulation of it;</li>
 *   <li>the field guide is shared, because there is one guide.</li>
 * </ul>
 *
 * <p><b>Animals exist near people and nowhere else.</b> A world with no edge
 * cannot hold a population, so animals are spawned into a ring around each
 * player, simulated while anybody is near, and dropped when everybody has
 * walked away. That is not a fidelity compromise — the species table is what
 * makes the world consistent, and a specific chaffinch is not a thing anybody
 * can tell apart from another one.
 *
 * <h2>Threading</h2>
 *
 * <p><b>One thread simulates; any thread may look.</b> Everything that changes
 * this object happens on the server's tick thread — the reader and writer
 * threads behind each connection only move bytes onto and off queues, and
 * {@link com.larsons.engine.watch.net.WatchServer} drains those queues from the
 * tick loop. But the game is <em>read</em> from elsewhere: a host autosaving
 * calls {@link #toMap()} on the frame thread while its own server is ticking,
 * and so does anything that asks it what is alive.
 *
 * <p>That was a crash, not a theoretical one — {@code List.copyOf} over a
 * {@link LinkedHashMap} whose size grew mid-copy throws
 * {@code ArrayIndexOutOfBoundsException} from
 * {@code LinkedHashMap.valuesToArray}, which is exactly what a snapshot taken
 * during a spawn did. So every public method that touches the party, the
 * animals, the feeders or the outlines is synchronised on this object. The lock
 * is held for a tick at twenty ticks a second; a reader waits microseconds and
 * gets a consistent answer instead of an exception.
 *
 * <p>The insertion-ordered maps stay: a concurrent map would fix the copy and
 * lose the order that decides who the party's host is.
 */
public final class WatchGame implements Animal.Surroundings {

    /** How a game tells the outside world what happened. */
    public interface Sink {
        /** Something one player should know about. */
        void toPlayer(int playerId, Map<String, Object> message);

        /** Something everybody should know about. */
        void toAll(Map<String, Object> message);

        /** A line for the party's chat log. */
        void info(String text);
    }

    /**
     * How many people can walk one world.
     *
     * <p>Eight, as asked. It lives here rather than in the protocol because it
     * is a property of the game — the party, the shared guide, the ring of
     * animals kept alive around them — and the protocol, the lobby and the
     * server all read it from the game rather than each keeping their own idea
     * of what the limit is.
     */
    public static final int MAX_PLAYERS = 8;

    /** The settings a world is created with. */
    public record Config(long seed, String worldName, int maxPlayers) {

        public Config {
            maxPlayers = Math.max(1, Math.min(MAX_PLAYERS, maxPlayers));
        }

        /** A solo world with a random seed. */
        public static Config solo(String worldName) {
            return new Config(new Random().nextLong(), worldName, 1);
        }

        /** A hosted world, open to the full party. */
        public static Config hosted(String worldName, long seed) {
            return new Config(seed, worldName, MAX_PLAYERS);
        }
    }

    /** Nearest an animal is spawned to a player, in metres. */
    private static final double SPAWN_NEAR = 22;

    /** Furthest, in metres. */
    private static final double SPAWN_FAR = 95;

    /** Beyond this from every player, an animal is forgotten. */
    private static final double DESPAWN = 170;

    /**
     * Nearest a <em>mutant</em> is ever put down, in metres.
     *
     * <p>{@link #SPAWN_NEAR} is twenty-two metres and would put a five-metre
     * wendigo inside the clearing you are standing in, arriving out of nothing —
     * which reads as a bug rather than as a horror, and takes away the seconds
     * of deciding what to do that the meeting is made of. Forty-five is about
     * ten of those seconds at a walk, and a thing three times a person's height
     * is unmistakable at it.
     *
     * <p>It is a <b>floor</b> rather than the distance itself, because a fixed
     * ring was measured and was wrong: put down at ninety to a hundred and forty
     * metres, a wendigo whose notice range is seventy-eight never noticed
     * anybody at all, and a walker who kept walking left it behind having never
     * known it was there. A spawn nothing comes of is not an encounter. See
     * {@link #placeMutant}.
     */
    private static final double MUTANT_SPAWN_FLOOR = 45;

    /**
     * How wide the cone an ambusher is placed in, in radians either side of
     * where its quarry is heading.
     *
     * <p>Twenty-eight degrees, which at the fifty-odd metres one is placed at
     * brings a walker within about twenty-five of it — just inside the range at
     * which it stands up. Wider and most of them are walked past unmet; narrower
     * and every one of them is dead ahead, which is a different and worse
     * problem.
     *
     * <p>An ambusher does not come to you — that is the whole of what
     * {@link Mutants.Power#AMBUSH} means — so a ring placement leaves it
     * standing in a fen behind you for ten minutes and then dropping out of the
     * world unmet. Putting it roughly where you are already going is not the
     * generator cheating; it is what the word means.
     */
    private static final double AMBUSH_CONE = Math.toRadians(28);

    /**
     * How many mutants may be alive at once, however many people are walking.
     *
     * <p>One. Two would be twice as frightening for about a minute and then
     * would be the population of the wood, and the entire design rests on the
     * wood being safe. See {@code Mutants} on the three filters this is the
     * fourth of.
     */
    private static final int MUTANT_CAP = 1;

    /**
     * How long after one is dropped before the world will offer another, in
     * seconds.
     *
     * <p>Ten minutes. Without it, walking away from a wendigo until it despawns
     * simply hands you a fresh one on the next spawn tick, which turns "I got
     * away" into "it teleported". Long enough to get somewhere else and calm
     * down; short enough that a night's walk can meet more than one.
     */
    private static final double MUTANT_COOLDOWN = 600;

    /** How many animals are kept alive per player. */
    private static final int PER_PLAYER = 26;

    /** …and in total, however many players there are. */
    private static final int TOTAL_CAP = 150;

    /**
     * What share of a glassing player's spawns go out down the glass.
     *
     * <p>A third: enough that a minute of sweeping a valley finds things in it,
     * little enough that the clearing you are standing in is still populated
     * when you put the glass down.
     */
    private static final double GLASS_SHARE = 0.34;

    /** How wide the cone spawns spread, as a share of how far down it they are. */
    private static final double GLASS_SPREAD = 0.35;

    /** How far a click can reach to spot something, in metres. */
    public static final double SPOT_RANGE = 130;

    /** How far a hand can reach to pick, plant or build, in metres. */
    public static final double REACH = 4.5;

    /**
     * How wide the ring round a thing on the ground is drawn, in metres.
     *
     * <p>Small, and smaller than the bush and tree it shares a highlight with:
     * a hoop three times the width of the acorn inside it points at a patch of
     * grass rather than at the acorn. The screen holds it to a floor in pixels
     * (see {@code WatchScene.drawReachHighlight}) so it does not shrink to
     * nothing at arm's length either.
     */
    public static final double GROUND_HIGHLIGHT = 0.10;

    private final Config config;
    private final TerrainField field;
    private final Flora flora;
    private final Litter litter;
    private final Flora.Ground ground;
    private final WatchClock clock;
    private final Random rng;

    /** How long the ground has to be left alone between handfuls, in millis. */
    private static final long GROUND_FORAGE_MILLIS = 1200;

    /** How long a picked bush or tree stays bare, in real hours. */
    private static final double REGROW_HOURS = 6;

    private final Map<Integer, WatchPlayer> players = new LinkedHashMap<>();

    /** What has been picked and when, so a bush is not an infinite supply. */
    private final Map<Long, Long> picked = new LinkedHashMap<>();

    /** When each player last took a handful off the ground. */
    private final Map<Integer, Long> foraged = new LinkedHashMap<>();

    /** Walkers a save left behind, waiting for whoever comes back. See {@link #wake}. */
    private final Map<Integer, WatchPlayer> resting = new LinkedHashMap<>();
    private final Map<Long, Animal> animals = new LinkedHashMap<>();
    private final Map<Long, Lure> lures = new LinkedHashMap<>();
    private final List<Spotlight> spotlights = new ArrayList<>();

    /**
     * Every satchel somebody has dropped by dying, still lying where it fell.
     *
     * <p>World state like the grove and the buildings — saved, synced, and
     * owned by the host — for the reason {@link Spill} sets out at length: it is
     * the one thing on the floor of this world that cannot be derived from the
     * seed, because it is a record of something that happened rather than a
     * fact about a place.
     */
    private final Spill spills = new Spill();

    private final FieldGuide guide = new FieldGuide();
    private final Grove grove = new Grove();
    private final Cultivation crops = new Cultivation();
    private final Structure structure = new Structure();

    /**
     * Every map the party has drawn, and every board they have hung one on.
     *
     * <p>World state like the grove and the buildings, and shared for the same
     * reason the book is: eight people walking one wood keep one set of maps
     * between them, so a map somebody drew of the far valley is a map anybody
     * can be handed. What is per-player is only which satchel a given map is
     * currently in — see {@link Chart#owner()}.
     */
    private final Cartography cartography = new Cartography();
    private final Weather weather;
    private final Boats boats;

    /**
     * The trading posts.
     *
     * <p>Held so that the rules can be checked against them — where a post is,
     * what it sells and for how much are all functions of the seed, so this
     * object holds no state and nothing about it is ever saved or sent. It
     * exists here for the same reason {@link Boats} does: a client asks its own
     * copy the same questions and gets the same answers, and the host is the one
     * that decides whether the money actually changed hands.
     */
    private final Shops shops;

    private Sink sink;
    private long nextAnimalId = 1;
    private long nextLureId = 1;
    private long lastRealMillis = System.currentTimeMillis();
    private double spawnTimer;

    /** How long until the world will offer another mutant. See {@link #MUTANT_COOLDOWN}. */
    private double mutantCooldown;

    public WatchGame(Config config) {
        this(config, null);
    }

    public WatchGame(Config config, Sink sink) {
        this.config = config;
        this.sink = sink;
        this.field = new TerrainField(config.seed());
        this.flora = new Flora(config.seed(), field);
        this.litter = new Litter(config.seed(), field);
        this.ground = Flora.ground(field);
        this.clock = WatchClock.fromSystem();
        this.rng = new Random(config.seed() ^ 0x5EED);
        this.weather = new Weather(config.seed());
        this.boats = new Boats(config.seed());
        this.shops = new Shops(config.seed());
    }

    /** Where messages go; may be replaced when a solo game becomes a hosted one. */
    public synchronized void setSink(Sink sink) { this.sink = sink; }

    public Config config() { return config; }

    /** The generator this world is built from. */
    public TerrainField field() { return field; }

    /** The flora scatterer, so a client can ask the same questions. */
    public Flora flora() { return flora; }

    /** What is lying on the ground, so a client can ask the same questions. */
    public Litter litter() { return litter; }

    /**
     * The generator seen as ground, which is what {@link Flora} and
     * {@link Litter} both want. Handed out rather than rebuilt by every caller
     * so that a host adjudicating a pick and a client drawing the same clearing
     * are sampling the identical thing.
     */
    public Flora.Ground ground() { return ground; }

    /** What time it is here — the host's clock, in a hosted game. */
    public WatchClock clock() { return clock; }

    /** The shared book. */
    public FieldGuide guide() { return guide; }

    /** Every tree anybody planted. */
    public Grove grove() { return grove; }

    /** Every crop anybody planted. */
    public Cultivation crops() { return crops; }

    /** Everything anybody built. */
    public Structure structure() { return structure; }

    /** Every map anybody drew, and every board they went up on. */
    public Cartography maps() { return cartography; }

    /** What the sky is doing, for everybody. */
    public Weather weather() { return weather; }

    /** The boats — where they were found, and where they have been left. */
    public Boats boats() { return boats; }

    /** The trading posts, so a client can ask the same questions. */
    public Shops shops() { return shops; }

    /** The party. */
    public synchronized List<WatchPlayer> players() { return List.copyOf(players.values()); }

    public synchronized WatchPlayer player(int id) { return players.get(id); }

    /** The player of a given name, or {@code null}. */
    public synchronized WatchPlayer playerNamed(String name) {
        for (WatchPlayer p : players.values()) {
            if (p.name().equals(name)) return p;
        }
        return null;
    }

    /** Every animal currently simulated. */
    public synchronized List<Animal> animals() { return List.copyOf(animals.values()); }

    public synchronized Animal animal(long id) { return animals.get(id); }

    /** Every feeder standing. */
    public synchronized List<Lure> lures() { return List.copyOf(lures.values()); }

    /** The outlines currently up. */
    public synchronized List<Spotlight> spotlights() { return List.copyOf(spotlights); }

    /** Every satchel anybody has dropped by dying. */
    public synchronized Spill spills() { return spills; }

    /**
     * Where a walk begins, and where it begins again.
     *
     * <p>The world origin. Everything about this game's geography is relative —
     * there is no edge, no map with a corner, and no landmark that is not
     * somewhere in particular — so the one fixed point is the one the first
     * player stood on. Dying puts you back here, which is also the only place
     * in an endless world that everybody can agree on the name of.
     */
    public double spawnX() { return 0; }

    public double spawnY() { return 0; }

    // --- the party -------------------------------------------------------------------

    /**
     * Somebody arrives, or {@code null} if the walk is full or they are already
     * on it.
     *
     * <p><b>The cap is enforced here and not only at the door.</b>
     * {@link WatchServer} turns a ninth connection away with a reason, which is
     * the right thing for a person to see — but it was the <em>only</em> thing
     * stopping a ninth player, so {@link Config#maxPlayers()} was a number the
     * simulation carried and never read. Anything that joins a game without
     * going through a socket (a save being reopened, a test, whatever comes
     * next) has to meet the same limit, and a rejoin on a live id has to be a
     * rejection rather than a second player quietly replacing the first.
     */
    public synchronized WatchPlayer join(int id, String name) {
        if (players.containsKey(id)) return null;
        if (players.size() >= Math.max(1, config.maxPlayers())) return null;

        WatchPlayer resumed = wake(id, name);
        if (resumed != null) {
            players.put(id, resumed);
            say(name + " picked up where they left off");
            return resumed;
        }

        double angle = rng.nextDouble() * Math.PI * 2;
        double radius = players.isEmpty() ? 0 : 4 + players.size() * 2.0;
        double x = Math.cos(angle) * radius;
        double y = Math.sin(angle) * radius;
        WatchPlayer player = new WatchPlayer(id, name, x, y, field.heightAt(x, y));
        // A journal, and enough to be going on with: nobody should have to
        // forage for twenty minutes before the game can start.
        player.satchel().add("journal", 1);
        player.satchel().add("grass_seed", 6);
        player.satchel().add("blackberry", 3);
        players.put(id, player);
        say(name + " joined the walk");
        return player;
    }

    /**
     * The walker a save left behind for whoever this is, or {@code null}.
     *
     * <p><b>A saved player is a place kept, not a body standing there.</b> The
     * first version of restoring a save put them straight into the party, and
     * that was wrong twice over: they occupied a seat and counted against the
     * cap while nobody was controlling them, and — because the server hands out
     * ids from one and a save's first player <em>is</em> id one — the host's own
     * connection collided with the ghost of itself and was turned away with
     * "this walk is full". Hosting a saved world simply did not work.
     *
     * <p>So they wait here instead, and are woken by whoever arrives: by name
     * if the name matches, and on a walk that only ever holds one person, by
     * being the only one there is. Waking keeps their position and their
     * satchel and takes the joiner's new id.
     */
    private WatchPlayer wake(int id, String name) {
        WatchPlayer found = null;
        for (WatchPlayer sleeper : resting.values()) {
            if (sleeper.name().equals(name)) {
                found = sleeper;
                break;
            }
        }
        if (found == null && config.maxPlayers() == 1 && resting.size() == 1) {
            found = resting.values().iterator().next();
        }
        if (found == null) return null;
        resting.remove(found.id());

        WatchPlayer player = new WatchPlayer(id, name, found.x(), found.y(), found.z());
        player.load(found.toMap());
        return player;
    }

    /** Somebody leaves. Their pets stay in the book; their feeders stay standing. */
    public synchronized void leave(int id) {
        WatchPlayer gone = players.remove(id);
        if (gone != null) say(gone.name() + " headed home");
    }

    /**
     * A movement update from a client.
     *
     * <p><b>Whether you are under water is derived here, not sent.</b> The
     * client says where it is; the server says what that means, from the same
     * heightfield everybody has. It is the same rule the speed derivation
     * follows and for the same reason — the breath meter is the only thing in
     * this game that runs out, so it should not be a number a client hands over.
     */
    public synchronized void move(int id, double x, double y, double z, double yaw, double pitch,
                     boolean crouching, double dt) {
        WatchPlayer player = players.get(id);
        if (player == null) return;
        player.moveTo(x, y, z, yaw, pitch, crouching, dt);
        player.setSubmerged(player.eyeZ() < TerrainField.WATER_LEVEL - 0.05, dt);
    }

    // --- spotting --------------------------------------------------------------------

    /**
     * How far a player can pick something out, in metres.
     *
     * <p>{@link #SPOT_RANGE} with the naked eye, and as far as the glass
     * reaches with one up — which is the whole point of carrying one, and the
     * reason the server rather than the client decides it. See
     * {@link Spyglass#spotRange}.
     */
    public synchronized double spotRange(int playerId) {
        WatchPlayer player = players.get(playerId);
        return player == null ? SPOT_RANGE
                : Spyglass.spotRange(player.glassPower(), SPOT_RANGE);
    }

    /**
     * Raise or lower a player's spyglass.
     *
     * <p><b>Checked here, because what it changes is what the server will let
     * you record.</b> A client claiming ×15 without a glass in its satchel
     * would be able to write down a bird nine hundred metres away, which is
     * the one thing in this game worth cheating for. So the power is only
     * taken if the player is actually carrying one.
     *
     * @param power the magnification, or {@code 1} to put it away
     * @return the power that was actually adopted
     */
    public synchronized double glass(int playerId, double power) {
        WatchPlayer player = players.get(playerId);
        if (player == null) return Spyglass.NONE;
        boolean carried = player.satchel().has(Spyglass.ITEM);
        player.setGlassPower(carried ? power : Spyglass.NONE);
        return player.glassPower();
    }

    // --- debug mode ------------------------------------------------------------------

    /**
     * Somebody typed a code. Turn debug mode on, or off, or refuse it.
     *
     * <p><b>The code is checked here and not on the client</b>, for the same
     * reason every other rule is: a client is a thing that asks. And the answer
     * depends on whose walk it is — on your own, or on one you are hosting, the
     * code works; on somebody else's it does not, because the field guide is
     * shared and a stranger with unlimited suet cake writes their way through a
     * book four other people are keeping. The host is the first walker on the
     * walk, which is the same rule the lobby's party list uses.
     *
     * <p>Toggles rather than sets, so the same four keys undo it.
     *
     * @param code what they typed
     * @return whether debug mode is now on for that player
     */
    public synchronized boolean debug(int playerId, String code) {
        WatchPlayer player = players.get(playerId);
        if (player == null || !Debug.isCode(code)) return false;
        if (!ownsThisWalk(playerId)) {
            say(player.name() + " tried a code — this is not their walk");
            return player.debugging();
        }
        player.setDebug(!player.debugging());
        say(player.name() + (player.debugging()
                ? " turned on debug mode — everything is unlimited"
                : " turned debug mode off"));
        return player.debugging();
    }

    /**
     * How far in front of a player a summoned animal is put down, in metres.
     *
     * <p>Twenty, and the number is chosen from the three mutants' own senses
     * rather than for the view: it is inside the notice range of all three — the
     * mirewraith's is the shortest at twenty-two — so a summon is a thing that
     * <em>starts happening</em> rather than a statue to walk around. Far enough
     * that a five-and-a-half-metre model is on screen whole at a normal field of
     * view, near enough that it is on you in four seconds.
     */
    private static final double SUMMON_AHEAD = 20;

    /**
     * Put an animal on the ground in front of a player — <b>debug mode only.</b>
     *
     * <p>The shape {@link Debug}'s class note prescribes for a power the satchel
     * lens cannot reach: a row in {@link Debug.Power} and one
     * {@code if (player.debugging())} where it acts. This is that {@code if}.
     *
     * <p><b>It asks none of the questions {@link #populate} asks</b>, and that
     * is the whole feature rather than a shortcut. A mutant is behind a region,
     * an hour, a cap of one alive and a ten-minute cooldown, and every one of
     * those is working correctly when it refuses — which leaves somebody testing
     * a wendigo's gait with no way to see one except to walk a taiga at night
     * and wait. So a summon skips the species table, the medium check, the cap
     * and the cooldown, and puts down exactly what it was asked for exactly
     * where it was asked for it.
     *
     * <p>Any species, not only the three. The code is the same either way and a
     * restriction that existed only to restrict would be one more rule to
     * explain; what makes this a mutant feature is which keys the walk offers,
     * and that is a decision for the client — see
     * {@code WatchScene.summonMutant}. A heron you can find by walking to a
     * marsh. A wendigo you cannot.
     *
     * @param speciesKey the key from {@link AnimalRegistry}
     * @return the animal, or {@code null} if the player may not, or there is no
     *         such species
     */
    public synchronized Animal summon(int playerId, String speciesKey) {
        WatchPlayer player = players.get(playerId);
        if (player == null || !player.debugging()) return null;
        AnimalDef def = AnimalRegistry.byKey(speciesKey);
        if (def == null) return null;

        // Straight out from the eye, on the flat: the engine's forward is
        // (sin yaw, −cos yaw), and a summon that appeared where the player was
        // *looking* would put a wendigo in a treetop whenever somebody had
        // glanced up.
        double x = player.x() + Math.sin(player.yaw()) * SUMMON_AHEAD;
        double y = player.y() - Math.cos(player.yaw()) * SUMMON_AHEAD;
        double ground = field.heightAt(x, y);
        double depth = field.waterDepth(ground);
        // The one thing a summon does still respect, because it is about where
        // the animal can be rather than about whether it is allowed to exist: a
        // swimmer goes under the surface and everything else stands on the bed
        // or the ground. Nothing is ever spawned inside the terrain.
        double z = def.aquatic()
                ? ground + Math.max(0, depth - Math.min(depth * 0.5, 0.6))
                : ground;

        long id = nextAnimalId++;
        Animal summoned = new Animal(id, def, x, y, z, config.seed() ^ id);
        animals.put(id, summoned);
        say(player.name() + " summoned a " + def.name());
        return summoned;
    }

    /**
     * Whether a player is the one whose walk this is.
     *
     * <p>Alone, there is only one of you. In a party it is whoever arrived
     * first, which is the host: {@code WatchServer.hostId} answers the same
     * question the same way, off the same insertion-ordered map, and this is
     * here rather than there because it is a rule about the game rather than
     * about the socket.
     */
    private boolean ownsThisWalk(int playerId) {
        for (WatchPlayer first : players.values()) return first.id() == playerId;
        return false;
    }

    /**
     * The animal a player is looking at, or {@code null}.
     *
     * <p>An angular test rather than a ray-box intersection: an animal is
     * "under the cursor" if the direction to it is within a few degrees of the
     * way the player is looking, and the tolerance widens with the animal's
     * size and narrows with distance. That is much more forgiving than a
     * geometric hit test, which is the right trade for a game where the target
     * is a sparrow forty metres away in a tree and the reward for hitting it is
     * a line in a book rather than damage.
     *
     * <p>Both numbers move when a glass goes up: the reach grows with the
     * magnification and the tolerance shrinks by it, so glassing is a longer
     * <em>and</em> a more exact way of pointing at something. See
     * {@link Spyglass#tolerance}.
     */
    public synchronized Animal lookingAt(int playerId) {
        WatchPlayer player = players.get(playerId);
        if (player == null) return null;
        double cp = Math.cos(player.pitch());
        double dirX = Math.sin(player.yaw()) * cp;
        double dirY = -Math.cos(player.yaw()) * cp;
        double dirZ = Math.sin(player.pitch());
        double eyeZ = player.eyeZ();
        double power = player.glassPower();
        double range = Spyglass.spotRange(power, SPOT_RANGE);

        Animal best = null;
        double bestScore = Double.MAX_VALUE;
        for (Animal animal : animals.values()) {
            double dx = animal.x() - player.x();
            double dy = animal.y() - player.y();
            double dz = animal.z() + animal.def().bodyLength() * 0.5 - eyeZ;
            double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (distance > range || distance < 0.01) continue;
            double dot = (dx * dirX + dy * dirY + dz * dirZ) / distance;
            if (dot <= 0) continue;
            double angle = Math.acos(Math.min(1, dot));
            // The tolerance an animal of this size subtends, with a floor so a
            // hummingbird at forty metres is not impossible to click.
            double tolerance = Spyglass.tolerance(animal.def().bodyLength(), distance,
                    power);
            if (angle > tolerance) continue;
            // Prefer the one nearest the centre of the view, then the nearest.
            double score = angle * 1000 + distance;
            if (score < bestScore) {
                bestScore = score;
                best = animal;
            }
        }
        return best;
    }

    /**
     * A player clicked on something.
     *
     * <p>The heart of the game: it writes the sighting, raises the shared
     * outline, and tells everybody. Returns the spotlight so a solo game can
     * use the same path as a hosted one.
     */
    public synchronized Spotlight spot(int playerId, long animalId) {
        WatchPlayer player = players.get(playerId);
        if (player == null) return null;
        Animal animal = animalId > 0 ? animals.get(animalId) : lookingAt(playerId);
        if (animal == null) return null;

        AnimalDef def = animal.def();
        WatchBiome biome = field.biomeAt(animal.x(), animal.y());
        Sighting sighting = new Sighting(def.key(), System.currentTimeMillis(),
                clock.timeOfDay(), biome.key(), player.name(), animal.x(), animal.y(),
                !guide.seen(def.key()));
        // Asked before recording, because recording is what spends it: this is
        // what the sighting is worth on the page that is currently open, which
        // is the rarity for anything not yet on it and nothing for anything
        // that is. See FieldGuide.
        int worth = guide.award(def.key());
        boolean discovery = guide.record(sighting);

        Spotlight light = Spotlight.of(animal.id(), def.key(), player.name(),
                animal.x(), animal.y(), animal.z(), discovery, worth);
        spotlights.add(light);
        if (discovery) {
            say(player.name() + " found a " + def.name() + " — new for the guide!");
        } else if (worth > 0) {
            say(player.name() + " logged a " + def.name() + " — " + worth
                    + (worth == 1 ? " point" : " points"));
        }
        return light;
    }

    /** Adopt a spotlight sent by the host — what a client does. */
    public synchronized void addSpotlight(Spotlight light) {
        if (light != null) spotlights.add(light);
    }

    // --- foraging --------------------------------------------------------------------

    /**
     * What the player is about to pick, if they press the key now.
     *
     * <p><b>Exists so that picking can be aimed at something.</b> {@link #pick}
     * has always known perfectly well what it was going to take — a ripe bush
     * first, then a fruiting tree, then a crop, then the ground — but it only
     * said so afterwards, in the past tense, in a line of chat. So the verb
     * with the most reach in the game was the one with the least feedback: you
     * pressed E somewhere near a bush and either something happened or it did
     * not, and there was no way to tell which it would be except by pressing.
     *
     * <p>This is the same walk over the same candidates, returning where the
     * thing is rather than taking it, so the screen can put a highlight round
     * it and a name under it. The two must agree, which is why the ordering
     * here is the ordering there and the reaches are the same constants.
     *
     * @return what is in reach, or {@code null} if there is nothing but ground
     */
    public synchronized Pickable pickTarget(int playerId) {
        WatchPlayer player = players.get(playerId);
        if (player == null) return null;
        double cp = Math.cos(player.pitch());
        double dirX = Math.sin(player.yaw()) * cp;
        double dirY = -Math.cos(player.yaw()) * cp;

        // <b>A dropped satchel wins over everything.</b> First in the list, and
        // it earned the place: a player who has walked four hundred metres back
        // to where they were killed, and presses the key standing over their own
        // bag, must not pick a blackberry. It was tried further down — above the
        // floor litter, below the things that grow — and a test walked somebody
        // back to a heap that happened to have a fruiting tree next to it and
        // got a handful of fruit.
        //
        // It costs nothing in the ordinary case, which is the argument for
        // putting it first rather than fourth: there is usually no spilled
        // satchel anywhere in the world, and {@link Spill#REACH} is 2.6 m
        // against this method's 4.5, so it only wins when somebody is standing
        // directly over one.
        Spill.Pile spilled = spills.nearest(player.x(), player.y(), Spill.REACH);
        if (spilled != null) {
            return new Pickable(Pickable.Kind.SATCHEL, String.valueOf(spilled.id()),
                    spilled.label(), spilled.x(), spilled.y(), spilled.z() + 0.25,
                    Spill.RADIUS);
        }

        Flora.Bush bush = flora.nearestBush(ground, player.x(), player.y(), REACH);
        if (bush != null && bush.ripe() && !bare(bushKey(bush))
                && inFront(player, bush.x(), bush.y(), dirX, dirY)) {
            return new Pickable(Pickable.Kind.BUSH, bush.berry(),
                    Forage.nameOf(bush.berry()), bush.x(), bush.y(),
                    bush.z() + bush.radius() * 0.8, bush.radius());
        }

        TreeInstance tree = nearestFruitingTree(player.x(), player.y(), REACH + 1.5);
        if (tree != null && tree.species().fruit() != null && !bare(tree.id())
                && inFront(player, tree.x(), tree.y(), dirX, dirY)) {
            return new Pickable(Pickable.Kind.TREE, tree.species().fruit(),
                    Forage.nameOf(tree.species().fruit()), tree.x(), tree.y(),
                    tree.z() + Math.max(1.4, tree.height() * 0.55), 0.9);
        }

        for (Cultivation.Crop crop : crops.near(player.x(), player.y(), REACH)) {
            if (!crop.ripe()) continue;
            return new Pickable(Pickable.Kind.CROP, crop.seed(),
                    Forage.nameOf(crop.seed()), crop.x(), crop.y(),
                    crop.z() + crop.height(), 0.4);
        }

        for (Lure lure : lures.values()) {
            double dx = lure.x() - player.x(), dy = lure.y() - player.y();
            if (dx * dx + dy * dy > REACH * REACH) continue;
            return new Pickable(Pickable.Kind.FEEDER, lure.food(),
                    Forage.nameOf(lure.food()) + " feeder", lure.x(), lure.y(),
                    lure.z() + 1.2, 0.35);
        }

        Boats.Boat boat = boats.nearest(field, player.x(), player.y(), Boats.BOARD_RANGE);
        if (boat != null && player.boatId() == 0) {
            return new Pickable(Pickable.Kind.BOAT, "boat", "Rowing boat",
                    boat.x(), boat.y(), boat.z() + 0.4, 1.6);
        }

        // A counter somebody is standing at. Above the litter for the same
        // reason the oars are: a pebble at your feet must not stop you talking
        // to the person in front of you.
        Shops.Shop shop = shops.atCounter(field, player.x(), player.y());
        if (shop != null) {
            return new Pickable(Pickable.Kind.SHOP, String.valueOf(shop.id()),
                    shop.title(), shop.counterX(), shop.counterY(),
                    shop.z() + Shops.COUNTER_TOP + 0.25, 0.5);
        }

        // A map board somebody built. Beside the counter in this list and for
        // the same reason: it is a thing you walk up to and read, and a pebble
        // at your feet must not be what the key takes instead.
        Cartography.Board board = cartography.boardAt(player.x(), player.y());
        if (board != null) {
            List<Chart> pinned = cartography.pinnedTo(board.id());
            return new Pickable(Pickable.Kind.BOARD, String.valueOf(board.id()),
                    pinned.isEmpty() ? "the empty map board"
                            : "the map board  ·  " + pinned.size()
                                    + (pinned.size() == 1 ? " map" : " maps"),
                    board.x(), board.y(), board.z() + 1.0, 1.5);
        }

        // Last, because everything above it is something somebody put there or
        // grew, and a stone on the shingle must not stop you taking the oars.
        Litter.Piece piece = nearestLitter(player.x(), player.y());
        if (piece != null) {
            return new Pickable(Pickable.Kind.GROUND, piece.key(),
                    Forage.nameOf(piece.key()), piece.x(), piece.y(),
                    piece.z() + 0.10, GROUND_HIGHLIGHT);
        }
        return null;
    }

    /**
     * The nearest thing lying on the ground that has not already been taken.
     *
     * <p>Walks outward rather than taking {@link Litter#nearest} at face value:
     * the nearest piece may be one somebody picked up an hour ago, and stopping
     * at it would make every piece behind it unreachable until it grew back.
     */
    private Litter.Piece nearestLitter(double x, double y) {
        Litter.Piece best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Litter.Piece piece : litter.near(ground, x, y, REACH)) {
            if (bare(piece.id())) continue;
            double dx = piece.x() - x, dy = piece.y() - y;
            double d = dx * dx + dy * dy;
            if (d < bestDistance) {
                bestDistance = d;
                best = piece;
            }
        }
        return best;
    }

    /**
     * Something in reach, and where it is on screen.
     *
     * @param kind   what it is, which decides which key takes it
     * @param key    the item it yields, or {@code "boat"}
     * @param name   what a player sees under the highlight
     * @param radius roughly how big it is, for sizing the ring
     */
    public record Pickable(Kind kind, String key, String name, double x, double y,
                           double z, double radius) {

        /** What sort of thing is in reach. */
        public enum Kind {
            /** A ripe berry bush. */
            BUSH("Pick"),
            /** A fruiting tree. */
            TREE("Pick"),
            /** A ripe crop. */
            CROP("Harvest"),
            /** A feeder somebody put out. */
            FEEDER("Top up"),
            /** A boat, drawn up on the shore. */
            BOAT("Take the oars"),
            /**
             * A trading post's counter. See {@link Shops}.
             *
             * <p>The one kind whose verb is not a thing the <em>host</em> does:
             * pressing the key opens a panel, and what the host is asked for
             * comes afterwards, one purchase at a time. It is in this list all
             * the same, because being in this list is what puts a ring round it
             * and a line under the crosshair, and a shop you cannot tell you are
             * standing at is a shop nobody finds twice.
             */
            SHOP("Trade at"),
            /**
             * A map board somebody built. See {@link Cartography}.
             *
             * <p>{@link #SHOP}'s twin, and here for {@link #SHOP}'s reason: the
             * verb is a screen rather than a message, and a board you cannot
             * tell you are standing at is a board nobody uses twice.
             */
            BOARD("Read"),
            /**
             * Somebody's satchel, dropped where they died. See {@link Spill}.
             *
             * <p>Its own kind rather than a {@link #GROUND} with a different
             * name, because what it does is different in the way that matters:
             * a piece of litter is one item and a heap is a whole bag, taken in
             * one gesture and gone afterwards.
             */
            SATCHEL("Gather"),
            /** Something lying on the ground. See {@link Litter}. */
            GROUND("Pick up");

            private final String verb;

            Kind(String verb) { this.verb = verb; }

            /** What the prompt says you would be doing. */
            public String verb() { return verb; }
        }

        /** The line the HUD puts under the highlight. */
        public String prompt() { return kind.verb() + " " + name; }
    }

    /**
     * Pick whatever is within reach.
     *
     * <p>One button, and it takes the most interesting thing nearby: a ripe
     * berry bush first, then a fruiting tree, then a seed head, then a
     * building material. Four separate keys for four kinds of picking would be
     * three keys too many for a game whose verb is "look".
     *
     * @return what went into the satchel, or {@code null} if there was nothing
     */
    public synchronized String pick(int playerId) {
        WatchPlayer player = players.get(playerId);
        if (player == null) return null;

        // Reaching happens in front of you, not around you. The first version
        // had no direction in it at all and no way to fail: a bush out of reach
        // fell through to a fruiting tree, that fell through to "a seed this
        // biome has", and that fell through to "some material, always" — so
        // holding E anywhere in the world produced an endless stream of things,
        // and none of it had anything to do with what you were looking at.
        double cp = Math.cos(player.pitch());
        double dirX = Math.sin(player.yaw()) * cp;
        double dirY = -Math.cos(player.yaw()) * cp;

        Flora.Bush bush = flora.nearestBush(ground, player.x(), player.y(), REACH);
        if (bush != null && bush.ripe()
                && inFront(player, bush.x(), bush.y(), dirX, dirY)) {
            // A picked bush is bare until it comes back. Without this you can
            // stand at one and empty it into your satchel for ever.
            if (bare(bushKey(bush))) return null;
            picked.put(bushKey(bush), System.currentTimeMillis());
            player.satchel().add(bush.berry(), 2 + rng.nextInt(3));
            return bush.berry();
        }

        TreeInstance tree = nearestFruitingTree(player.x(), player.y(), REACH + 1.5);
        if (tree != null && tree.species().fruit() != null
                && inFront(player, tree.x(), tree.y(), dirX, dirY)) {
            if (bare(tree.id())) return null;
            picked.put(tree.id(), System.currentTimeMillis());
            player.satchel().add(tree.species().fruit(), 1 + rng.nextInt(3));
            return tree.species().fruit();
        }

        // Nothing growing in reach. What is left is whatever is lying on the
        // floor — a particular thing, in a particular place, that you could see
        // before you pressed anything.
        //
        // This used to be a roll: no target, a cooldown, and a handful of
        // something out of `Forage.underfoot` announced from a patch of bare
        // grass. The table has not changed and neither have the odds; what
        // changed is that the roll happens when the world is generated rather
        // than when the key is pressed, so the thing you pick up is the thing
        // you walked to. See {@link Litter}.
        Litter.Piece piece = nearestLitter(player.x(), player.y());
        if (piece == null) return null;
        // Still a moment between handfuls: two pieces of litter a metre apart
        // are two presses, not one held key.
        long now = System.currentTimeMillis();
        Long last = foraged.get(playerId);
        if (last != null && now - last < GROUND_FORAGE_MILLIS) return null;
        foraged.put(playerId, now);

        picked.put(piece.id(), now);
        player.satchel().add(piece.key(), 1);
        return piece.key();
    }

    /**
     * The pieces of litter that have been taken and have not grown back.
     *
     * <p><b>The one thing about the ground that has to be said out loud.</b>
     * Where a piece of litter <em>is</em> and what it is are functions of the
     * seed, so a client works both out for itself and neither ever goes on the
     * wire. Whether somebody has already picked it up is not a function of
     * anything, and a client that did not know would keep drawing a branch
     * nobody can pick up — which is worse than the stale-bush case this game
     * already tolerates, because a bush stays a bush when it is bare and a
     * taken branch is simply not there.
     *
     * <p>Rides on the world sync rather than the snapshot: it changes when
     * somebody picks something up, which is a few times a minute, and the
     * snapshot goes out twenty times a second.
     */
    public synchronized List<Long> takenLitter() {
        // The keys first, because `bare` prunes the map it is reading.
        List<Long> keys = new ArrayList<>(picked.keySet());
        List<Long> out = new ArrayList<>();
        for (Long key : keys) {
            if (Litter.isLitter(key) && bare(key)) out.add(key);
        }
        return out;
    }

    /**
     * Whether something picked is still bare, and forget it once it is not —
     * so the map holds what has been picked recently rather than everything
     * that has ever been picked.
     */
    private boolean bare(long key) {
        Long when = picked.get(key);
        if (when == null) return false;
        if (WatchClock.realHoursBetween(when, System.currentTimeMillis()) >= REGROW_HOURS) {
            picked.remove(key);
            return false;
        }
        return true;
    }

    /** Whether a spot is in the half-plane the player is facing, and in reach. */
    private static boolean inFront(WatchPlayer player, double x, double y,
                                   double dirX, double dirY) {
        double dx = x - player.x(), dy = y - player.y();
        double distance = Math.hypot(dx, dy);
        if (distance > REACH || distance < 1e-6) return distance <= REACH;
        // Generous: a bush is wide and you are standing at it, so this only has
        // to rule out the one behind you.
        return (dx * dirX + dy * dirY) / distance > 0.25;
    }

    private static long bushKey(Flora.Bush bush) {
        return (long) Math.floor(bush.x() * 4) * 73_856_093L
                ^ (long) Math.floor(bush.y() * 4) * 19_349_663L;
    }

    /** The fruiting tree nearest a point — wild or planted. */
    private TreeInstance nearestFruitingTree(double x, double y, double radius) {
        TreeInstance best = null;
        double bestDistance = radius * radius;
        for (TreeInstance tree : grove.near(x, y, radius)) {
            if (!tree.fruiting()) continue;
            double dx = tree.x() - x, dy = tree.y() - y;
            double d = dx * dx + dy * dy;
            if (d < bestDistance) {
                bestDistance = d;
                best = tree;
            }
        }
        if (best != null) return best;
        TreeInstance wild = flora.nearestTree(ground, x, y, radius);
        return wild != null && wild.fruiting() ? wild : null;
    }

    /** What the generator says the surface is at a point. */
    public WatchMaterial surfaceUnderfoot(WatchBiome biome, double x, double y) {
        double height = field.heightAt(x, y);
        // The slope the material rule wants, read the way the mesher reads it:
        // a central difference over a couple of metres, which is the grid the
        // heightfield is sampled on.
        double step = WatchChunk.STEP;
        double dx = (field.heightAt(x + step, y) - field.heightAt(x - step, y)) / (2 * step);
        double dy = (field.heightAt(x, y + step) - field.heightAt(x, y - step)) / (2 * step);
        return field.surfaceAt(x, y, height, Math.hypot(dx, dy), biome);
    }

    /**
     * Do whatever the thing in reach wants doing.
     *
     * <p><b>One key, and the highlight says what it will do.</b> Picking a
     * bush, pulling a ripe crop, topping up a feeder and taking the oars were
     * four separate keys, three of which failed silently when you were not
     * standing at the right thing — and the player has no way of knowing which
     * of the four they are standing at except by trying all of them. Since
     * {@link #pickTarget} already works out exactly what is in reach so the
     * screen can draw a ring round it, the honest thing is to let that same
     * answer choose the verb.
     *
     * <p>The individual verbs stay: the wire has them, tests use them, and
     * nothing about routing through one key should make "harvest" stop being a
     * thing the server can be asked to do.
     *
     * @return a line for the HUD, or {@code null} if nothing happened
     */
    public synchronized String use(int playerId) {
        Pickable target = pickTarget(playerId);
        if (target == null) {
            String got = pick(playerId);
            return got == null ? null : "Picked " + Forage.nameOf(got);
        }
        return switch (target.kind()) {
            case BUSH, TREE -> {
                String got = pick(playerId);
                yield got == null ? null : "Picked " + Forage.nameOf(got);
            }
            case GROUND -> {
                String got = pick(playerId);
                yield got == null ? null : "Picked up " + Forage.nameOf(got);
            }
            case SATCHEL -> gather(playerId);
            case CROP -> {
                String got = harvest(playerId);
                yield got == null ? null : "Harvested " + got;
            }
            case FEEDER -> {
                Lure nearest = nearestLureTo(playerId);
                if (nearest == null) yield null;
                yield refillLure(playerId, nearest.id())
                        ? "Topped up the feeder"
                        : "Nothing left to put in it";
            }
            case BOAT -> useBoat(playerId);
            case SHOP -> {
                // Trading is a panel, and a panel is the client's business — see
                // the note on Kind.SHOP. What the host can usefully answer is
                // who is standing there, so a press that reaches it at all is
                // the keeper saying hello rather than silence.
                Shops.Shop shop = shopAt(playerId);
                yield shop == null ? null
                        : shop.keeper().name() + ": " + shop.keeper().greeting();
            }
            case BOARD -> {
                // Also a panel, for Kind.SHOP's reason. What the host can say is
                // what is on the board, which is what somebody walking up to one
                // wants to know before they open it.
                Cartography.Board board = boardAt(playerId);
                if (board == null) yield null;
                int pinned = cartography.pinnedTo(board.id()).size();
                yield pinned == 0 ? "An empty map board"
                        : pinned + (pinned == 1 ? " map on the board" : " maps on the board");
            }
        };
    }

    // --- trading ---------------------------------------------------------------------

    /**
     * The post whose counter a player is standing at, or {@code null}.
     *
     * <p>Every trading rule goes through this, and that is deliberate: a
     * purchase is only a purchase if the buyer is at the counter, and taking the
     * shop's id from the request without checking would let a client three
     * kilometres away buy a lens off a shop it had merely heard of. The id in
     * the message is a <em>disambiguator</em>, not an address.
     */
    public synchronized Shops.Shop shopAt(int playerId) {
        WatchPlayer player = players.get(playerId);
        return player == null ? null : shops.atCounter(field, player.x(), player.y());
    }

    /**
     * Buy something with points.
     *
     * <p><b>The guide pays, not the player</b>, because the guide is what
     * earned it. Eight people keeping one book keep one balance out of it, in a
     * game whose whole social mechanic is that a species one of them finds is
     * filled in for everybody — a per-player purse would be the one thing in
     * this game that was not shared, and it would make pointing a bird out to a
     * friend cost you something.
     *
     * @param shopId which post, as the client understood it; a request naming a
     *               post the player is not standing at is refused outright
     * @return a line for the HUD, or {@code null} when nothing was bought
     */
    public synchronized String buy(int playerId, long shopId, String item) {
        WatchPlayer player = players.get(playerId);
        Shops.Shop shop = shopAt(playerId);
        if (player == null || shop == null) return null;
        if (shopId != 0 && shop.id() != shopId) return null;
        Trading.Offer offer = shop.offer(item);
        if (offer == null) return null;
        // The one cost in this game that a bottomless satchel cannot reach,
        // because it is not paid out of a satchel. See Debug.Power.POINTS —
        // this `if` is the whole of that feature, exactly as Debug's class note
        // says a new power should be.
        if (!player.debugging() && !guide.spend(offer.price())) {
            return "Not enough points — " + offer.priceLine() + ", and the book has "
                    + guide.points();
        }
        player.satchel().add(offer.item(), offer.quantity());
        say(player.name() + " bought " + offer.label() + " at " + shop.sign());
        return "Bought " + offer.label() + " for " + offer.priceLine();
    }

    /**
     * Have a keeper stamp a fresh page: everything already seen counts again.
     *
     * <p>The other half of what a trading post is for, and the half the shelves
     * exist to give a reason to. See {@link FieldGuide#stamp} for why a keeper
     * will not stamp a blank one.
     *
     * @return a line for the HUD, or {@code null} when there is no keeper there
     */
    public synchronized String stamp(int playerId, long shopId) {
        WatchPlayer player = players.get(playerId);
        Shops.Shop shop = shopAt(playerId);
        if (player == null || shop == null) return null;
        if (shopId != 0 && shop.id() != shopId) return null;
        FieldGuide.Page page = guide.stamp(shop.keeper().name(),
                field.biomeAt(shop.x(), shop.y()).displayName(),
                System.currentTimeMillis());
        if (page == null) {
            return shop.keeper().name() + ": \"Nothing on it yet. Come back with a "
                    + "page worth stamping.\"";
        }
        say(shop.keeper().name() + " stamped " + player.name() + "'s guide — "
                + page.describe());
        return shop.keeper().stampLine();
    }

    // --- maps ------------------------------------------------------------------------
    //
    // Every verb below opens with the same `if (!player.debugging())`, and that
    // repetition is the feature rather than a smell: it is exactly the shape
    // Debug's class note prescribes for a power, and lifting the gate is
    // deleting one line from each of six methods. A single check somewhere
    // clever would be a check somebody has to find.

    /**
     * Draw a map of the country round a player.
     *
     * <p><b>The size comes from the machine, and the machine has to say so.</b>
     * A map spans the render distance of whoever drew it — that is the whole
     * promise — and how far a client can see is a property of its own graphics
     * card and its own detail setting, not of the world. The host has no way to
     * know it and no business guessing, so the reach travels with the request
     * and is clamped here: {@link Chart#radiusFor} rounds it up the ladder of
     * sizes, which both bounds it and makes it tile with everybody else's.
     *
     * <p>Everything on the paper is settled in this one call — see
     * {@link Survey}. Nothing about a map is ever filled in later.
     *
     * @param reach how far the drawing machine can see, in metres
     * @return the new map, or {@code null} if it was refused
     */
    public synchronized Chart drawMap(int playerId, double reach) {
        WatchPlayer player = players.get(playerId);
        if (player == null) return null;
        if (!player.debugging()) return null;
        // Where the paper will be, worked out before it exists, because the
        // survey has to be of the square the map will actually cover rather than
        // of the ground the player is standing on. Both this and
        // Cartography.draw go through Chart.radiusFor and Chart.snap, which is
        // the one place either answer is decided.
        double radius = Chart.radiusFor(reach);
        double cx = Chart.snap(player.x(), radius);
        double cy = Chart.snap(player.y(), radius);
        List<Chart.Landmark> icons = Survey.survey(field, shops, structure,
                List.copyOf(lures.values()), grove, boats, guide, cx, cy, radius);
        Chart chart = cartography.draw(Survey.nameFor(field, cx, cy), player.x(),
                player.y(), reach, player.name(), playerId, System.currentTimeMillis(),
                icons);
        if (chart == null) {
            say(player.name() + " has no room for another map");
            return null;
        }
        say(player.name() + " drew " + chart.name() + " — " + chart.describe());
        return chart;
    }

    /** Rename a map. Anybody may rename any map: the set of them is the party's. */
    public synchronized boolean renameMap(int playerId, long chartId, String name) {
        WatchPlayer player = players.get(playerId);
        if (player == null || !player.debugging()) return false;
        return cartography.rename(chartId, name);
    }

    /**
     * Lay a pen stroke on a map, in world metres.
     *
     * <p>The points arrive already in the world's own coordinates rather than
     * as a fraction of a panel: see {@link Chart}, which explains why ink is
     * kept that way. The host does not check that they land inside the map —
     * a line drawn off the edge is simply a line nobody can see, and refusing
     * it would mean adjudicating a scribble.
     */
    public synchronized Chart.Stroke markMap(int playerId, long chartId, int ink,
                                             double[] xs, double[] ys) {
        WatchPlayer player = players.get(playerId);
        if (player == null || !player.debugging()) return null;
        return cartography.mark(chartId, Chart.Ink.at(ink), player.name(), xs, ys);
    }

    /** Write a few words on a map. */
    public synchronized Chart.Note noteMap(int playerId, long chartId, int ink, double x,
                                           double y, String text) {
        WatchPlayer player = players.get(playerId);
        if (player == null || !player.debugging()) return null;
        return cartography.note(chartId, Chart.Ink.at(ink), player.name(), x, y, text);
    }

    /** Rub one mark off a map. */
    public synchronized boolean eraseMark(int playerId, long chartId, long markId) {
        WatchPlayer player = players.get(playerId);
        if (player == null || !player.debugging()) return false;
        return cartography.erase(chartId, markId);
    }

    /**
     * Pin a map to a board, or take one back off it.
     *
     * <p>The board has to be one the player is standing at, for the reason a
     * purchase has to be at a counter: the id in the message disambiguates
     * between boards, it does not address one across the world. Taking a map
     * <em>back</em> is checked the same way, against the board it is currently
     * on — reaching over a valley to unpin somebody's map is the same move as
     * reaching over one to pin your own.
     *
     * @param boardId the board to pin to, or {@code 0} to take the map back
     */
    public synchronized boolean pinMap(int playerId, long chartId, long boardId) {
        WatchPlayer player = players.get(playerId);
        if (player == null || !player.debugging()) return false;
        Chart chart = cartography.chart(chartId);
        if (chart == null) return false;
        Cartography.Board here = cartography.boardAt(player.x(), player.y());
        if (here == null) return false;
        long wanted = boardId == 0 ? chart.boardId() : boardId;
        if (wanted != here.id()) return false;
        boolean done = cartography.pin(chartId, boardId, playerId);
        if (done) {
            say(player.name() + (boardId == 0 ? " took " + chart.name() + " down"
                    : " pinned " + chart.name() + " up"));
        }
        return done;
    }

    /** The board a player is standing at, or {@code null}. */
    public synchronized Cartography.Board boardAt(int playerId) {
        WatchPlayer player = players.get(playerId);
        return player == null ? null : cartography.boardAt(player.x(), player.y());
    }

    private Lure nearestLureTo(int playerId) {
        WatchPlayer player = players.get(playerId);
        if (player == null) return null;
        Lure best = null;
        double bestDistance = REACH * REACH;
        for (Lure lure : lures.values()) {
            double dx = lure.x() - player.x(), dy = lure.y() - player.y();
            double d = dx * dx + dy * dy;
            if (d < bestDistance) {
                bestDistance = d;
                best = lure;
            }
        }
        return best;
    }

    /** Turn over a log: beetles and mealworms, which nothing insectivorous refuses. */
    public synchronized String turnOverLog(int playerId) {
        WatchPlayer player = players.get(playerId);
        if (player == null) return null;
        if (!player.satchel().has("fallen_branch")) return null;
        String found = rng.nextBoolean() ? "beetle" : "mealworms";
        player.satchel().add(found, 1 + rng.nextInt(3));
        return found;
    }

    // --- feeders ---------------------------------------------------------------------

    /**
     * Put a feeder down with something in it.
     *
     * @return the feeder, or {@code null} when the player has no feeder, no
     *         food, or is standing somewhere a feeder cannot go
     */
    public synchronized Lure placeLure(int playerId, String food) {
        WatchPlayer player = players.get(playerId);
        if (player == null) return null;
        Forage.Item item = Forage.byKey(food);
        if (item == null || !item.edible()) return null;
        if (!player.satchel().has("feeder") || !player.satchel().has(food)) return null;
        double z = field.heightAt(player.x(), player.y());
        if (field.waterDepth(z) > 0.2) return null;

        player.satchel().take("feeder", 1);
        player.satchel().take(food, 1);
        Lure lure = new Lure(nextLureId++, food, player.x(), player.y(), z,
                player.name(), System.currentTimeMillis());
        lures.put(lure.id(), lure);
        say(player.name() + " put out " + Forage.nameOf(food));
        return lure;
    }

    /** Top a feeder up from the satchel. */
    public synchronized boolean refillLure(int playerId, long lureId) {
        WatchPlayer player = players.get(playerId);
        Lure lure = lures.get(lureId);
        if (player == null || lure == null) return false;
        if (!player.satchel().take(lure.food(), 1)) return false;
        lure.refill();
        return true;
    }

    /** Take a feeder back. */
    public synchronized boolean removeLure(int playerId, long lureId) {
        WatchPlayer player = players.get(playerId);
        Lure lure = lures.remove(lureId);
        if (player == null || lure == null) return false;
        player.satchel().add("feeder", 1);
        return true;
    }

    /** Adopt a feeder sent by the host. */
    public synchronized void addLure(Lure lure) {
        if (lure != null) {
            lures.put(lure.id(), lure);
            nextLureId = Math.max(nextLureId, lure.id() + 1);
        }
    }

    // --- planting --------------------------------------------------------------------

    /**
     * Plant a seed where the player is standing.
     *
     * <p>A tree seed goes into the grove and grows over days; anything else
     * becomes a crop and ripens in hours. Both need a trowel, which is one
     * branch and one stone.
     *
     * @return a line for the HUD, or {@code null} when it could not be planted
     */
    public synchronized String plant(int playerId, String seed) {
        WatchPlayer player = players.get(playerId);
        if (player == null || !Cultivation.plantable(seed)) return null;
        if (!player.satchel().has("trowel") || !player.satchel().has(seed)) return null;
        double z = field.heightAt(player.x(), player.y());
        if (field.waterDepth(z) > 0) return null;

        player.satchel().take(seed, 1);
        TreeSpecies species = Cultivation.treeFor(seed);
        if (species != null) {
            TreeInstance tree = grove.plant(species, player.x(), player.y(), z,
                    TreeGenome.average(), player.name());
            return "Planted a " + tree.species().displayName();
        }
        crops.plant(seed, player.x(), player.y(), z, player.name());
        return "Planted " + Forage.nameOf(seed);
    }

    /**
     * Plant a crossed seed — what the breeding game produces.
     *
     * <p>Keeps the seed's own genome rather than starting from the average,
     * which is the whole point: a line improved over five generations has to
     * carry its parents' vigour into the ground with it.
     */
    public synchronized TreeInstance plantCross(int playerId, Grove.Cross cross) {
        WatchPlayer player = players.get(playerId);
        if (player == null || cross == null) return null;
        double z = field.heightAt(player.x(), player.y());
        return grove.plant(cross.species(), player.x(), player.y(), z, cross.genome(),
                player.name());
    }

    /** Cross the two planted trees nearest a player. */
    public synchronized Grove.Cross pollinate(int playerId) {
        WatchPlayer player = players.get(playerId);
        if (player == null) return null;
        List<TreeInstance> near = grove.near(player.x(), player.y(), Grove.POLLEN_REACH);
        near.removeIf(t -> !t.canPollinate());
        if (near.size() < 2) return null;
        near.sort(Comparator.comparingDouble(t -> {
            double dx = t.x() - player.x(), dy = t.y() - player.y();
            return dx * dx + dy * dy;
        }));
        Grove.Cross cross = grove.pollinate(near.get(0).id(), near.get(1).id(), rng);
        if (cross != null && cross.hybrid()) {
            say(player.name() + " crossed " + cross.parentA().displayName() + " with "
                    + cross.parentB().displayName() + " — a " + cross.species().displayName()
                    + "!");
        }
        return cross;
    }

    /** Pull up the ripe crop a player is standing over. */
    public synchronized String harvest(int playerId) {
        WatchPlayer player = players.get(playerId);
        if (player == null) return null;
        for (Cultivation.Crop crop : crops.near(player.x(), player.y(), REACH)) {
            if (!crop.ripe()) continue;
            String seed = crops.harvest(crop.id(), player.satchel());
            if (seed != null) return Forage.nameOf(seed);
        }
        return null;
    }

    // --- building --------------------------------------------------------------------

    /**
     * Put a piece down in front of the player.
     *
     * @param turn  which of the eight compass turns it takes
     * @param inTree whether to fix it to the nearest trunk instead of the ground
     */
    public synchronized Structure.Placement build(int playerId, BuildPiece piece, int turn,
                                     boolean inTree) {
        WatchPlayer player = players.get(playerId);
        if (player == null || piece == null) return null;
        // A piece the mode is still holding back — today only the map board.
        // Checked here rather than only in the build screen, because the screen
        // is a client and a client is a thing that asks.
        if (piece.debugOnly() && !player.debugging()) return null;
        if (!piece.affordable(player.satchel())) return null;

        // Two metres in front, so a piece appears where you are looking rather
        // than inside you.
        double x = player.x() + Math.sin(player.yaw()) * 2.0;
        double y = player.y() - Math.cos(player.yaw()) * 2.0;
        double z = field.heightAt(x, y) + piece.sizeZ() / 2;
        long treeId = 0;

        if (inTree && piece.anchors()) {
            TreeInstance tree = nearestAnchorTree(x, y);
            if (tree == null) return null;
            x = tree.x();
            y = tree.y();
            z = tree.z() + Math.max(2.2, tree.height() * 0.55);
            treeId = tree.id();
        }
        if (structure.blocked(piece, Structure.snap(x), Structure.snap(y),
                Structure.snap(z), turn)) {
            return null;
        }
        if (!piece.pay(player.satchel())) return null;
        Structure.Placement placement = structure.place(piece, x, y, z, turn, treeId,
                player.name(), System.currentTimeMillis());
        // A map board is timber and a place maps can be pinned; the timber is
        // the placement above and the place is this. Raised here rather than
        // lazily on first use, so a board that nobody has walked up to yet is
        // still a board in the save and on everybody else's copy of the world.
        if (piece == BuildPiece.MAP_BOARD) {
            cartography.raise(placement.id(), placement.x(), placement.y(),
                    placement.z(), placement.yaw(), player.name());
        }
        return placement;
    }

    /** The planted tree nearest a point that is big enough to hold a platform. */
    private TreeInstance nearestAnchorTree(double x, double y) {
        TreeInstance best = null;
        double bestDistance = 6 * 6;
        for (TreeInstance tree : grove.near(x, y, 6)) {
            if (tree.height() < 4) continue;
            double dx = tree.x() - x, dy = tree.y() - y;
            double d = dx * dx + dy * dy;
            if (d < bestDistance) {
                bestDistance = d;
                best = tree;
            }
        }
        if (best != null) return best;
        TreeInstance wild = flora.nearestTree(ground, x, y, 6);
        return wild != null && wild.height() >= 4 ? wild : null;
    }

    /** Cook, or make gear, out of a player's satchel. */
    public synchronized boolean craft(int playerId, Recipes.Recipe recipe, Recipes.Station station) {
        WatchPlayer player = players.get(playerId);
        if (player == null) return false;
        return Recipes.craft(recipe, player.satchel(), station);
    }

    // --- fishing ---------------------------------------------------------------------

    /** Cast into the water a player is looking at. */
    public synchronized boolean castRod(int playerId) {
        WatchPlayer player = players.get(playerId);
        if (player == null || !player.satchel().has("rod")) return false;
        // Straight out in front, ten metres; the water has to be there.
        double x = player.x() + Math.sin(player.yaw()) * 10;
        double y = player.y() - Math.cos(player.yaw()) * 10;
        double z = field.heightAt(x, y);
        if (field.waterDepth(z) < 0.5) return false;
        return player.rod().cast(x, y, TerrainField.WATER_LEVEL,
                field.biomeAt(x, y));
    }

    /**
     * Strike.
     *
     * <p>A landed fish is both an item and a <b>sighting</b>: it is a species in
     * the guide like anything else, and catching one is the only way most
     * people will ever see it.
     */
    public synchronized AnimalDef strike(int playerId) {
        WatchPlayer player = players.get(playerId);
        if (player == null) return null;
        AnimalDef fish = player.rod().strike();
        if (fish == null) return null;
        String item = Fishing.itemFor(fish);
        if (item != null) player.satchel().add(item, 1);
        WatchBiome biome = field.biomeAt(player.x(), player.y());
        int worth = guide.award(fish.key());
        boolean discovery = guide.record(new Sighting(fish.key(),
                System.currentTimeMillis(), clock.timeOfDay(), biome.key(),
                player.name(), player.x(), player.y(), !guide.seen(fish.key())));
        if (discovery) {
            say(player.name() + " landed a " + fish.name() + " — new for the guide!");
        } else if (worth > 0) {
            say(player.name() + " landed a " + fish.name() + " — " + worth
                    + (worth == 1 ? " point" : " points"));
        }
        return fish;
    }

    // --- boats -----------------------------------------------------------------------

    /**
     * Get into the boat a player is standing at, or out of the one they are in.
     *
     * <p>One verb, because there is never a moment when both would apply and a
     * second key for "get out" is a second key to remember. Getting out records
     * where the boat now is, which is what makes a boat rowed across a lake a
     * boat that is on the far side of the lake for everybody, for ever.
     *
     * @return a line for the HUD, or {@code null} when there was no boat
     */
    public synchronized String useBoat(int playerId) {
        WatchPlayer player = players.get(playerId);
        if (player == null) return null;

        if (player.boatId() != 0) {
            boats.moveTo(player.boatId(), player.x(), player.y(),
                    TerrainField.WATER_LEVEL, player.yaw());
            player.leaveBoat();
            return "Stepped out of the boat";
        }

        Boats.Boat boat = boats.nearest(field, player.x(), player.y(), Boats.BOARD_RANGE);
        if (boat == null) return null;
        player.boardBoat(boat.id());
        return "Took the oars";
    }

    /** Every boat near a point — what a client asks so it can draw them. */
    public List<Boats.Boat> boatsNear(double x, double y, double radius) {
        return boats.near(field, x, y, radius);
    }

    // --- the tick --------------------------------------------------------------------

    /**
     * Advance the world.
     *
     * <p>Two clocks run here and they are not the same. {@code dt} is the
     * simulation's — animals, feeders, the rod. Real elapsed <em>hours</em>,
     * measured off the wall clock, are what trees and crops grow by, so a
     * session resumed the next morning advances a night's worth of growth in
     * the first tick.
     */
    public synchronized void tick(double dt) {
        clock.tick(dt);
        // The weather is rolled for the biome somebody is standing in, so a
        // party that walks north gets a northern sky. Whose biome, with eight
        // players spread over a valley, is deliberately arbitrary: there is one
        // sky and somebody has to be under it first.
        WatchPlayer anyone = players.isEmpty() ? null : players.values().iterator().next();
        weather.tick(dt, anyone == null ? null : field.biomeAt(anyone.x(), anyone.y()),
                clock.phase());

        long now = System.currentTimeMillis();
        double realHours = WatchClock.realHoursBetween(lastRealMillis, now);
        lastRealMillis = now;
        if (realHours > 0) {
            for (TreeInstance grown : grove.advance(realHours)) {
                if (grown.stage() == TreeSpecies.Stage.MATURE) {
                    say("A " + grown.species().displayName() + " reached maturity");
                }
            }
            for (Cultivation.Crop ripe : crops.advance(realHours)) {
                say(Forage.nameOf(ripe.seed()) + " is ready to harvest");
            }
            for (Lure lure : lures.values()) lure.age(realHours);
        }

        for (WatchPlayer player : players.values()) {
            player.tick(dt);
            player.rod().tick(dt);
        }

        for (Animal animal : animals.values()) {
            animal.step(dt, this);
            if (animal.behaviour() == Animal.Behaviour.FEED) feedFrom(animal);
        }

        spotlights.replaceAll(light -> light.aged(dt));
        spotlights.removeIf(light -> !light.alive());
        lures.values().removeIf(Lure::spoiled);

        mutantCooldown = Math.max(0, mutantCooldown - dt);

        spawnTimer += dt;
        if (spawnTimer >= 0.5) {
            spawnTimer = 0;
            populate();
        }
    }

    /** An animal at a feeder takes a serving, and may become somebody's. */
    private void feedFrom(Animal animal) {
        Lure nearest = null;
        double bestDistance = 2.2 * 2.2;
        for (Lure lure : lures.values()) {
            if (!lure.tempts(animal.def())) continue;
            double dx = lure.x() - animal.x(), dy = lure.y() - animal.y();
            double d = dx * dx + dy * dy;
            if (d < bestDistance) {
                bestDistance = d;
                nearest = lure;
            }
        }
        if (nearest == null) return;
        // A serving is consumed rarely enough that a full feeder lasts a
        // session, and often enough that it does not last a week.
        if (rng.nextDouble() < 0.004) nearest.consume();
        if (animal.def().tameable() && animal.trust() >= 1 && animal.owner() == null) {
            animal.setOwner(nearest.placedBy());
            guide.tame(animal.def().key(), null, nearest.placedBy(),
                    System.currentTimeMillis());
            say(nearest.placedBy() + " has tamed a " + animal.def().name() + "!");
        }
    }

    /** Keep the ring around each player stocked, and drop what has been left behind. */
    private void populate() {
        animals.values().removeIf(animal -> {
            for (WatchPlayer player : players.values()) {
                double dx = animal.x() - player.x(), dy = animal.y() - player.y();
                if (dx * dx + dy * dy < DESPAWN * DESPAWN) return false;
                // …and a mutant is kept well past the ordinary ring. It is the
                // one thing here that follows rather than flees, and a wendigo
                // dropped because you got a hundred and seventy metres ahead of
                // it would make outrunning one trivial and would delete the
                // slow, awful business of it still being behind you.
                if (animal.hostile()
                        && dx * dx + dy * dy < animal.mutant().lose()
                                * animal.mutant().lose()) {
                    return false;
                }
                // …and nothing being watched through a glass is ever dropped
                // out from under the person watching it, however far away it
                // is. Without this the ring's edge is a wall a spyglass can
                // see straight over: the far hillside would be beautifully
                // drawn and completely empty.
                if (underGlass(player, animal.x(), animal.y())) return false;
            }
            return true;
        });

        // Fewer things are out in a storm and more in a drizzle, which is what
        // makes a change in the weather worth walking out into.
        int want = Math.min(TOTAL_CAP,
                (int) Math.round(players.size() * PER_PLAYER * weather.activity()));
        int tries = 0;
        while (animals.size() < want && tries++ < 24) {
            WatchPlayer host = pickPlayer();
            if (host == null) return;
            double angle = rng.nextDouble() * Math.PI * 2;
            double radius = SPAWN_NEAR + rng.nextDouble() * (SPAWN_FAR - SPAWN_NEAR);
            double x = host.x() + Math.cos(angle) * radius;
            double y = host.y() + Math.sin(angle) * radius;

            // A raised glass moves some of that player's share of the roster
            // out along the line they are looking down, so the far shore has
            // things on it to find. A share and not all of it: the wood you are
            // standing in should not empty out because you looked at a
            // mountain. See GLASS_SHARE.
            if (host.glassing() && rng.nextDouble() < GLASS_SHARE) {
                double reach = Spyglass.spotRange(host.glassPower(), SPOT_RANGE);
                double along = DESPAWN * 0.7 + rng.nextDouble() * (reach - DESPAWN * 0.7);
                double cp = Math.cos(host.pitch());
                double fx = Math.sin(host.yaw()) * cp, fy = -Math.cos(host.yaw()) * cp;
                double flat = Math.hypot(fx, fy);
                if (flat > 1e-6 && along > 0) {
                    fx /= flat;
                    fy /= flat;
                    // Scattered across the cone rather than piled on the exact
                    // sight line: the spread grows with distance, which is what
                    // the cone the glass is looking down actually does.
                    double spread = (rng.nextDouble() - 0.5) * along * GLASS_SPREAD;
                    x = host.x() + fx * along - fy * spread;
                    y = host.y() + fy * along + fx * spread;
                }
            }

            // <b>A diver gets a wet ring and a wet species table.</b> Without
            // this the sea floor is the emptiest place in the world, which is
            // the exact opposite of what it should be: the ring around a player
            // on a lake bed is still mostly the hillside above the waterline,
            // so almost every point sampled is dry and almost every species
            // offered is a land one — and both then fail the medium check
            // below. Two nudges fix it, and neither of them puts anything
            // anywhere it could not have been anyway.
            boolean wantWater = host.submerged();
            if (wantWater) {
                for (int attempt = 0; attempt < 6; attempt++) {
                    if (field.waterDepth(field.heightAt(x, y)) >= 1.0) break;
                    double a = rng.nextDouble() * Math.PI * 2;
                    double r = SPAWN_NEAR * 0.3 + rng.nextDouble() * SPAWN_NEAR;
                    x = host.x() + Math.cos(a) * r;
                    y = host.y() + Math.sin(a) * r;
                }
            }
            AnimalDef def = wantWater ? pickAquatic(x, y) : pickSpecies(x, y);
            if (def == null) continue;

            // The fourth filter on a mutant, and the only one that is not about
            // odds — see Mutants. The species table has already agreed that one
            // could be here (right biome, right hour, and it won the weighted
            // roll at a twentieth of a legendary's frequency); this decides
            // whether the world is in a state to hold one, and moves it out to
            // where it can be seen coming.
            if (def.hostile()) {
                if (!admitMutant()) continue;
                double[] at = placeMutant(Mutants.of(def), host);
                x = at[0];
                y = at[1];
                // The ring can land on the wrong side of a biome edge, and a
                // mutant is too expensive to waste on a failed check — it is one
                // animal in a night. So the species has to still belong where
                // the new point put it.
                if (!def.livesIn(field.biomeAt(x, y).key())) continue;
            }

            double z = field.heightAt(x, y);
            double depth = field.waterDepth(z);
            // A fish out of water, or a fox in a lake, is not a spawn — and the
            // thresholds are the ones the animal itself will enforce once it is
            // alive, so nothing is ever spawned somewhere it will immediately
            // have to escape from. See Animal.accept.
            if (!def.airborne()) {
                if (def.aquatic() ? depth < 1.0 : depth > 0.8) continue;
            }
            long id = nextAnimalId++;
            // Fish start at their swimming depth rather than on the bed, which
            // is where a spawn used to put them.
            double spawnZ = def.aquatic()
                    ? z + Math.max(0, depth - Math.min(depth * 0.5, 0.6)) : z;
            animals.put(id, new Animal(id, def, x, y, spawnZ, config.seed() ^ id));
            if (def.hostile()) {
                mutantCooldown = MUTANT_COOLDOWN;
                // Said out loud, and this is the only spawn in the game that
                // announces itself. A player who is looking the other way when
                // a wendigo walks out of the treeline gets no warning at all
                // otherwise, and "something is out there" is the sentence the
                // whole encounter is built to earn.
                say("Something large is moving out there…");
            }
        }
    }

    /**
     * Where to stand a mutant, as {@code x, y}.
     *
     * <p><b>Measured from its own eyes, not from a constant.</b> The band is
     * inside the range at which <em>this</em> mutant notices people and floored
     * at {@link #MUTANT_SPAWN_FLOOR}, which is the pair of conditions the
     * encounter needs: it takes an interest at once, and it is far enough off to
     * be seen first.
     *
     * <p>Both halves were measured wrong first. A fixed ninety-to-a-hundred-and-
     * forty ring put a wendigo whose notice is seventy-eight metres <em>outside
     * its own senses</em>: it turned up, stood about, and the walker who kept
     * walking left it behind having never known it was there. Fifty minutes of
     * night walking on four seeds produced two such spawns and not one second of
     * being hunted. Tying the band to the creature's own range instead of to a
     * constant is what fixes it, because the distance that is a warning for one
     * of the three is out of earshot for another.
     *
     * <p>The exception is the ambusher, which is meant <em>not</em> to notice
     * you at spawn: it is put in front of whoever it is waiting for, at a
     * distance well outside the range at which it stands up, and the encounter
     * begins when they walk into it. See {@link #AMBUSH_CONE}.
     */
    private double[] placeMutant(Mutants.Kind kind, WatchPlayer host) {
        double near = Math.max(MUTANT_SPAWN_FLOOR, kind.notice() * 0.55);
        double far = Math.max(near + 18, kind.notice() * 0.92);
        double away = near + rng.nextDouble() * (far - near);
        double bearing = rng.nextDouble() * Math.PI * 2;
        if (kind.power() == Mutants.Power.AMBUSH) {
            // Where they are looking, which in this game is where they are
            // going: the walk sends its aim with every move. The engine's
            // forward vector is (sin yaw, −cos yaw), so the bearing whose
            // (cos, sin) is that pair is atan2(−cos yaw, sin yaw) — and the two
            // arguments are worth spelling out, because getting them the wrong
            // way round places every ambusher behind its quarry, which is a bug
            // that looks exactly like "ambushers never trigger".
            double heading = Math.atan2(-Math.cos(host.yaw()), Math.sin(host.yaw()));
            bearing = heading + (rng.nextDouble() * 2 - 1) * AMBUSH_CONE;
        }
        return new double[]{host.x() + Math.cos(bearing) * away,
                host.y() + Math.sin(bearing) * away};
    }

    /**
     * Whether the world will take another mutant right now.
     *
     * <p>Two questions, and they are asking different things. The cap is about
     * the <em>wood</em>: one at a time, ever, because two is a population and a
     * population is not frightening. The cooldown is about the <em>player</em>:
     * having got away from one, they have ten minutes in which the next spawn
     * roll cannot simply hand them another, or walking away would never work.
     */
    private boolean admitMutant() {
        if (mutantCooldown > 0) return false;
        int alive = 0;
        for (Animal animal : animals.values()) {
            if (animal.hostile()) alive++;
        }
        return alive < MUTANT_CAP;
    }

    /**
     * Whether a point is inside the cone a player has a glass on.
     *
     * <p>Deliberately wider than the cone things are <em>spawned</em> into —
     * twice it, with a floor around the watcher — because this decides what is
     * <b>kept</b>, and a bird dropped because the watcher's hand moved is the
     * worst possible moment to drop one.
     */
    private boolean underGlass(WatchPlayer player, double x, double y) {
        if (!player.glassing()) return false;
        double dx = x - player.x(), dy = y - player.y();
        double distance = Math.hypot(dx, dy);
        double reach = Spyglass.spotRange(player.glassPower(), SPOT_RANGE);
        if (distance > reach * 1.15) return false;
        if (distance < 1e-6) return true;
        double cp = Math.cos(player.pitch());
        double fx = Math.sin(player.yaw()) * cp, fy = -Math.cos(player.yaw()) * cp;
        double flat = Math.hypot(fx, fy);
        if (flat < 1e-6) return true;
        double along = (dx * fx + dy * fy) / flat;
        if (along <= 0) return false;
        double across = Math.abs(dx * -fy + dy * fx) / flat;
        return across <= Math.max(DESPAWN * 0.5, along * GLASS_SPREAD);
    }

    private WatchPlayer pickPlayer() {
        if (players.isEmpty()) return null;
        List<WatchPlayer> all = new ArrayList<>(players.values());
        return all.get(rng.nextInt(all.size()));
    }

    /**
     * Which species turns up at a point: one that lives in that biome, weighted
     * by how rare it is and by whether it is awake at this hour.
     */
    public synchronized AnimalDef pickSpecies(double x, double y) {
        return weightedPick(AnimalRegistry.inBiome(field.biomeAt(x, y).key()));
    }

    /**
     * Which species turns up under water at a point.
     *
     * <p>The same weighting over a narrower table: the biome's own swimmers,
     * and the fliers that fish, so a dive is a heron overhead as well as a
     * shoal below. Falls back to the whole table when a biome has no swimmers
     * at all rather than spawning nothing — an animal that fails the depth
     * check costs one loop iteration, and a lake with nothing in it costs the
     * feature.
     */
    private AnimalDef pickAquatic(double x, double y) {
        List<AnimalDef> here = AnimalRegistry.inBiome(field.biomeAt(x, y).key());
        List<AnimalDef> swimmers = new ArrayList<>();
        for (AnimalDef def : here) {
            if (def.aquatic()) swimmers.add(def);
        }
        return weightedPick(swimmers.isEmpty() ? here : swimmers);
    }

    /** One of a list, weighted by rarity and by whether it is awake now. */
    private AnimalDef weightedPick(List<AnimalDef> candidates) {
        if (candidates.isEmpty()) return null;
        WatchClock.Phase phase = clock.phase();
        double total = 0;
        for (AnimalDef def : candidates) total += def.encounterWeight(phase);
        if (total <= 0) return null;
        double roll = rng.nextDouble() * total;
        for (AnimalDef def : candidates) {
            roll -= def.encounterWeight(phase);
            if (roll <= 0) return def;
        }
        return candidates.get(candidates.size() - 1);
    }

    // --- Animal.Surroundings ---------------------------------------------------------

    @Override public double groundAt(double x, double y) { return field.heightAt(x, y); }

    @Override
    public double waterDepthAt(double x, double y) {
        return field.waterDepth(field.heightAt(x, y));
    }

    /**
     * The nearest point with water over it, searched outward in rings.
     *
     * <p>Spiral rather than dense: a swimmer asking this is asking "which way
     * is the lake", and a dozen samples on each of a few rings answers that as
     * well as a grid would for a fraction of the noise. Nearest ring first, so
     * the first hit is the nearest shoreline rather than an arbitrary one.
     */
    @Override
    public boolean waterNear(double x, double y, double radius, double minDepth,
                             double[] out) {
        for (double r = 4; r <= radius; r += 4) {
            int samples = (int) Math.max(8, Math.round(r * 1.5));
            double spin = rng.nextDouble() * Math.PI * 2;
            for (int i = 0; i < samples; i++) {
                double a = spin + i * Math.PI * 2 / samples;
                double px = x + Math.cos(a) * r, py = y + Math.sin(a) * r;
                if (waterDepthAt(px, py) >= minDepth) {
                    out[0] = px;
                    out[1] = py;
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * How loud the nearest player is, at a point — <b>with the weather in it.</b>
     *
     * <p>The weather's effect on how close an animal will let you get is
     * applied here rather than to the animal's own flush distance, because it
     * is a property of the air between the two of you rather than of the
     * animal: fog and rain hide and deafen a walker, wind makes everything
     * jumpy. Dividing the apparent distance by the scale is the same
     * arithmetic as multiplying every species' flush distance by it, and it
     * happens in one place instead of a thousand.
     */
    @Override
    public synchronized double disturbanceAt(double x, double y) {
        double nearest = Double.MAX_VALUE;
        for (WatchPlayer player : players.values()) {
            nearest = Math.min(nearest, player.apparentDistanceTo(x, y));
        }
        if (nearest == Double.MAX_VALUE) return nearest;
        double scale = Math.max(0.2, weather.flushScale());
        return nearest / scale;
    }

    @Override
    public synchronized boolean nearestLure(double x, double y, Diet diet, double[] out) {
        double bestAppeal = 0;
        boolean found = false;
        double[] appeal = new double[2];
        for (Lure lure : lures.values()) {
            Forage.draw(lure.food(), diet, appeal);
            if (appeal[0] <= 0) continue;
            double dx = lure.x() - x, dy = lure.y() - y;
            double distance = Math.sqrt(dx * dx + dy * dy);
            if (distance > appeal[1]) continue;
            // Nearer and stronger both count; a great feeder across the valley
            // loses to a fair one in the next clearing.
            double pull = appeal[0] / (1 + distance * 0.05);
            if (pull > bestAppeal) {
                bestAppeal = pull;
                out[0] = lure.x();
                out[1] = lure.y();
                out[2] = appeal[0];
                found = true;
            }
        }
        return found;
    }

    @Override
    public synchronized boolean playerPosition(String name, double[] out) {
        WatchPlayer player = playerNamed(name);
        if (player == null) return false;
        out[0] = player.x();
        out[1] = player.y();
        out[2] = player.z();
        return true;
    }

    /**
     * The nearest player a mutant could come for.
     *
     * <p><b>Plain distance, and that is the whole difference from
     * {@link #disturbanceAt}.</b> Every other question an animal asks about
     * people is asked through the apparent distance, which stillness multiplies
     * — that is the approach mechanic, and it is what makes crouching worth the
     * speed it costs. It must not apply here. If it did, the way to be safe
     * from a wendigo would be to stand still in front of it, and the one thing
     * this game asks a player to do would become the thing that kills them.
     *
     * <p>So a mutant sees a person, not a disturbance. Nothing you do about your
     * footsteps changes the answer; where you are standing is the answer.
     */
    @Override
    public synchronized String nearestQuarry(double x, double y, double range) {
        WatchPlayer best = null;
        double bestDistance = range * range;
        for (WatchPlayer player : players.values()) {
            if (!player.alive()) continue;
            double dx = player.x() - x, dy = player.y() - y;
            double d = dx * dx + dy * dy;
            if (d < bestDistance) {
                bestDistance = d;
                best = player;
            }
        }
        return best == null ? null : best.name();
    }

    /**
     * A mutant lands a blow.
     *
     * <p>The animal decided it was close enough; everything that follows is the
     * world's business, and all of it is here: the wound, the line in the log,
     * and — when that was the last one they could take — the satchel on the
     * ground and the walk back from the spawn.
     */
    @Override
    public synchronized void wound(String name, double amount, Animal by) {
        WatchPlayer player = playerNamed(name);
        if (player == null || !player.alive() || amount <= 0) return;
        String attacker = by == null ? "Something" : by.def().name();
        if (!player.wound(amount)) {
            // Said once per blow rather than once per chase: the log is the
            // only place a player who is looking the other way finds out that
            // the thing behind them has caught up.
            say(attacker + " struck " + player.name());
            return;
        }
        kill(player, attacker);
    }

    /**
     * Somebody went down.
     *
     * <p>Three things happen and they are meant to be read as one sentence:
     * everything they were carrying goes on the ground <em>where they fell</em>,
     * they get up at the spawn, and the party is told. The satchel is dropped
     * rather than deleted because the penalty for dying should be the walk back
     * — see {@link Spill} — and dropped in a heap rather than scattered because
     * a heap is something you can find again at dusk.
     *
     * <p>There is no death screen and no timer. This is a game about walking
     * around looking at things; being made to sit and watch a countdown is the
     * one thing that would break that, and standing up at the spawn point with a
     * long walk ahead of you is punishment enough.
     */
    private void kill(WatchPlayer player, String attacker) {
        double fellX = player.x(), fellY = player.y();
        Spill.Pile dropped = spills.drop(player.name(), fellX, fellY,
                field.heightAt(fellX, fellY), player.satchel().contents());
        player.satchel().clear();
        // Anything hunting them stops: their quarry is not there any more. The
        // animals notice on their own next tick — `nearestQuarry` skips the
        // dead and their new position is a kilometre away — so nothing has to
        // be told.
        player.respawnAt(spawnX(), spawnY(), field.heightAt(spawnX(), spawnY()));
        if (dropped == null) {
            say(attacker + " killed " + player.name());
            return;
        }
        say(attacker + " killed " + player.name() + " — their satchel is where they fell ("
                + Math.round(fellX) + ", " + Math.round(fellY) + ")");
    }

    /**
     * Pick up a dropped satchel: all of it, into whoever is standing over it.
     *
     * <p>Anybody may take any heap, including somebody else's. In a party that
     * is the point — a friend fetching your bag off the fen while you take the
     * long way round is the best thing this feature does — and alone it makes
     * no difference at all.
     *
     * @return a line for the HUD, or {@code null} if there was nothing in reach
     */
    public synchronized String gather(int playerId) {
        WatchPlayer player = players.get(playerId);
        if (player == null) return null;
        Spill.Pile pile = spills.nearest(player.x(), player.y(), Spill.REACH);
        if (pile == null) return null;
        Map<String, Integer> items = spills.take(pile.id());
        if (items == null) return null;
        items.forEach((key, count) -> player.satchel().add(key, count));
        int total = pile.total();
        boolean own = player.name().equals(pile.owner());
        return (own ? "Picked your satchel back up" : "Picked up " + pile.label())
                + "  ·  " + total + (total == 1 ? " thing" : " things");
    }

    private void say(String text) {
        if (sink != null) sink.info(text);
    }

    // --- persistence ------------------------------------------------------------------

    /** Everything worth keeping, as JSON. Animals are not: they are weather. */
    public synchronized Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("seed", config.seed());
        m.put("world", config.worldName());
        m.put("saved", System.currentTimeMillis());
        m.put("guide", guide.toMap());
        m.put("grove", grove.toMap());
        m.put("crops", crops.toMap());
        m.put("built", structure.toMap());
        // The maps, which are the one piece of world state that is mostly
        // somebody's handwriting: where each one is and what has been drawn on
        // it. The paper itself is not here — see Chart — so a walk with fifty
        // maps in it costs a few kilobytes of save.
        m.put("maps", cartography.toMap());
        m.put("sky", weather.toMap());
        m.put("boats", boats.toMap());
        // Dropped satchels. The one thing on the floor of this world that is
        // not a function of the seed, and the one whose loss a player would
        // actually feel: reopening a walk to find the bag you died with gone is
        // the save file taking something away that the game deliberately did
        // not. See Spill.
        m.put("spills", spills.toMap());
        List<Object> lureRows = new ArrayList<>();
        for (Lure lure : lures.values()) lureRows.add(lure.toMap());
        m.put("lures", lureRows);
        // What has been picked and has not grown back. Worth keeping now that
        // some of it is visible: a stripped bush looked the same either way,
        // but reopening a walk to find every branch you cleared out of your
        // camp lying there again is the world contradicting itself.
        List<Object> pickedRows = new ArrayList<>();
        for (Map.Entry<Long, Long> entry : List.copyOf(picked.entrySet())) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("k", entry.getKey());
            row.put("t", entry.getValue());
            pickedRows.add(row);
        }
        m.put("picked", pickedRows);
        // Everybody on the walk, and everybody the last save was still holding a
        // place for — a friend who has not been back since should not lose
        // their satchel because you played on without them.
        List<Object> playerRows = new ArrayList<>();
        for (WatchPlayer player : players.values()) playerRows.add(player.toMap());
        for (WatchPlayer sleeper : resting.values()) playerRows.add(sleeper.toMap());
        m.put("players", playerRows);
        return m;
    }

    /** Put a saved world back. */
    public synchronized void load(Map<String, Object> m) {
        guide.load(WatchJson.map(m, "guide"));
        grove.load(WatchJson.map(m, "grove"));
        crops.load(WatchJson.map(m, "crops"));
        structure.load(WatchJson.map(m, "built"));
        cartography.load(WatchJson.map(m, "maps"));
        weather.load(WatchJson.map(m, "sky"));
        boats.load(WatchJson.map(m, "boats"));
        spills.load(WatchJson.map(m, "spills"));
        lures.clear();
        for (Map<String, Object> row : WatchJson.objects(m, "lures")) {
            addLure(Lure.fromMap(row));
        }
        picked.clear();
        for (Map<String, Object> row : WatchJson.objects(m, "picked")) {
            long key = WatchJson.big(row, "k", 0);
            long when = WatchJson.big(row, "t", 0);
            // Anything already grown back is dropped rather than loaded and
            // forgotten on first touch, so a long-running world's save does not
            // carry every berry ever picked in it.
            if (key != 0 && when > 0
                    && WatchClock.realHoursBetween(when, System.currentTimeMillis())
                            < REGROW_HOURS) {
                picked.put(key, when);
            }
        }
        // The party, which {@link #toMap} has always written and this has never
        // read. Reopening a walk therefore put you back at the world origin
        // with an empty satchel, however far you had walked and however much
        // you had picked up — the single most annoying possible bug in a game
        // about going for a walk and collecting things.
        resting.clear();
        for (Map<String, Object> row : WatchJson.objects(m, "players")) {
            WatchPlayer player = new WatchPlayer(WatchJson.integer(row, "id", 1),
                    WatchJson.str(row, "n", "Walker"), 0, 0, 0);
            player.load(row);
            resting.put(player.id(), player);
        }
        // Time passes while a save is on disk, and everything that grows should
        // know about it: this is what makes a tree planted last week a tree.
        long saved = WatchJson.big(m, "saved", System.currentTimeMillis());
        double hours = WatchClock.realHoursBetween(saved, System.currentTimeMillis());
        if (hours > 0) {
            grove.advance(hours);
            crops.advance(hours);
            for (Lure lure : lures.values()) lure.age(hours);
            lures.values().removeIf(Lure::spoiled);
        }
        lastRealMillis = System.currentTimeMillis();
    }
}
