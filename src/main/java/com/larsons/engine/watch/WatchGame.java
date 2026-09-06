package com.larsons.engine.watch;

import com.larsons.engine.watch.home.HouseKit;
import com.larsons.engine.watch.home.HousePlan;
import com.larsons.engine.watch.home.Homestead;
import com.larsons.engine.watch.life.Animal;
import com.larsons.engine.watch.life.AnimalDef;
import com.larsons.engine.watch.life.AnimalRegistry;
import com.larsons.engine.watch.life.Diet;
import com.larsons.engine.watch.life.Hurl;
import com.larsons.engine.watch.life.Mutants;
import com.larsons.engine.watch.light.LightKind;
import com.larsons.engine.watch.light.Lights;
import com.larsons.engine.watch.light.PlacedLight;
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

    /**
     * Everything currently in the air — which in this game is only ever bone
     * shards a wendigo has thrown. See {@link Hurl}.
     *
     * <p>Beside the animals rather than beside the world state, and not saved,
     * for the animals' reason: a shard in flight when a walk closes is weather.
     */
    private final Map<Long, Hurl> hurls = new LinkedHashMap<>();

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

    /**
     * The party game, and the vote that starts one.
     *
     * <p>Not saved, unlike everything else on this list — see {@link Tag}, which
     * is weather in the same sense the animals are.
     */
    private final Tag tag = new Tag();

    /**
     * The Eye Spy board: what somebody has asked the rest of the party to find,
     * and what it pays.
     *
     * <p>Saved and synced with the world it belongs to, because a bounty is a
     * promise rather than a game in progress. See {@link Bounty}.
     */
    private final Bounty bounties = new Bounty();

    private final Grove grove = new Grove();
    private final Cultivation crops = new Cultivation();

    /**
     * Every house the party has bought.
     *
     * <p>Where {@code Structure} used to be. See {@link Homestead} and
     * {@link HousePlan} for why the building system became a catalogue: a house
     * is now one purchase and eight numbers rather than forty placements, which
     * is what makes a mansion cheap enough to send and possible to walk around
     * inside.
     */
    private final Homestead homes = new Homestead();

    /**
     * Every fire and lantern the party has put down.
     *
     * <p>World state like the buildings, and burning down like the feeders: the
     * two halves of it are that a light is a thing standing in a place, and
     * that it goes out. The host owns both — how much oil is left in a lantern
     * is exactly the sort of thing a client must not be allowed to decide, for
     * the reason everything else here is server-authoritative: a party that has
     * to walk back to camp before dark is a party playing the game, and one
     * whose lamps never run out is a party with the lights on.
     *
     * <p>What is <em>not</em> here is any of the light itself. A flame's
     * colour, its reach and its flicker are worked out on every machine that
     * draws one — see {@link com.larsons.engine.watch.light.LightField} — so
     * nothing about the lighting model travels and eight people round one fire
     * cost one row.
     */
    private final Lights lights = new Lights();

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
    private long nextHurlId = 1;
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

    /** Whether a game of tag is on, and who is it. */
    public Tag tag() { return tag; }

    /** What the party has put up for each other to find. */
    public Bounty bounties() { return bounties; }

    /** Every tree anybody planted. */
    public Grove grove() { return grove; }

    /** Every crop anybody planted. */
    public Cultivation crops() { return crops; }

    /** Every house anybody bought. */
    public Homestead homes() { return homes; }

    /** Every fire and lantern standing in the world. */
    public Lights lights() { return lights; }

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

    /** Everything in the air. */
    public synchronized List<Hurl> hurls() { return List.copyOf(hurls.values()); }

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
        // Two torches, for the same reason as the seed and the berries: this
        // clock is the real one, and somebody who starts a walk at nine in the
        // evening should not have to forage a wood they cannot see in before
        // the game can begin. Two is an evening, not a supply — they burn out,
        // and what replaces them is made rather than given.
        player.satchel().add("torch", 2);
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
        if (gone == null) return;
        say(gone.name() + " headed home");
        // A game of tag cannot outlive the person being chased or the person
        // doing the chasing. See Tag.left, which decides which of those this was.
        if (tag.left(id, players.size())) {
            takeGuns();
            say("The tag is off — " + gone.name() + " went home");
        }
    }

    /**
     * A movement update from a client.
     *
     * <p><b>Whether you are under water is derived here, not sent.</b> The
     * client says where it is; the server says what that means, from the same
     * heightfield everybody has. It is the same rule the speed derivation
     * follows and for the same reason — the breath meter is the only thing in
     * this game that runs out, so it should not be a number a client hands over.
     *
     * <p><b>A freeze is enforced here, and it is the only place it could be.</b>
     * A client is the authority on where it is standing, so the host cannot move
     * somebody by writing a position into a snapshot — the next {@code move}
     * would simply put them back. What it can do is refuse to <em>take</em> one,
     * which is this: whoever has just been tagged keeps the position they were
     * tagged at for {@link Tag#FREEZE_SECONDS} seconds however far their own
     * client thinks it has walked. Their head still turns, because being frozen
     * is standing still and counting rather than being switched off, and watching
     * everybody scatter is most of what those thirty seconds are for.
     */
    public synchronized void move(int id, double x, double y, double z, double yaw, double pitch,
                     boolean crouching, double dt) {
        WatchPlayer player = players.get(id);
        if (player == null) return;
        if (tag.frozen(id)) {
            player.moveTo(player.x(), player.y(), player.z(), yaw, pitch, crouching, dt);
            return;
        }
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
     * Wind the world's clock — <b>debug mode only.</b>
     *
     * <p>Not a private hour. {@link WatchClock} is one clock per world and the
     * host's is the one every client adopts, so this moves everybody's sky at
     * once; in a hosted walk only the host is ever granted debug mode, so
     * "everybody" is a party who can see who did it. See
     * {@link Debug.Power#CLOCK}.
     *
     * <p>Adopting a time is also what stops the clock following the wall clock
     * — that is {@link WatchClock#adopt}'s existing contract, written for a
     * guest taking its host's hour, and it is exactly what is wanted here:
     * a time somebody wound to should stay wound rather than springing back on
     * the next tick. {@link #followWallClock} puts it back.
     *
     * @return {@code true} if the clock moved
     */
    public synchronized boolean setTimeOfDay(int playerId, double timeOfDay) {
        WatchPlayer player = players.get(playerId);
        if (player == null || !player.debugging()) return false;
        clock.adopt(timeOfDay);
        return true;
    }

    /**
     * …and put it back on the real one. Debug mode only, for the same reason.
     *
     * @return {@code true} if the clock is now following the wall clock
     */
    public synchronized boolean followWallClock(int playerId) {
        WatchPlayer player = players.get(playerId);
        if (player == null || !player.debugging()) return false;
        clock.followWallClock();
        say(player.name() + " put the clock back to " + clock);
        return true;
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
        // …and whatever somebody had put up for it. Every sighting asks, because
        // the board is the one thing in this game that turns an animal nobody
        // would have looked twice at into the reason for an afternoon.
        claimBounty(def.key(), player);
        return light;
    }

    /** Adopt a spotlight sent by the host — what a client does. */
    public synchronized void addSpotlight(Spotlight light) {
        if (light != null) spotlights.add(light);
    }

    // --- tag -------------------------------------------------------------------------

    /**
     * Put a game of tag to the party — or, while one is on, put an end to it.
     *
     * <p>One verb for both, because they are the same question asked of the same
     * people under the same rules, and because a second key for "stop" would be a
     * key that did nothing for ninety-nine walks out of a hundred. Which of the
     * two it is depends only on whether a round is already running. See
     * {@link Tag#suggest}.
     *
     * @return a line for whoever asked, or {@code null} if there is no such player
     */
    public synchronized String suggestTag(int playerId) {
        WatchPlayer player = players.get(playerId);
        if (player == null) return null;
        if (players.size() < Tag.LEAST_PLAYERS) {
            return "Tag wants somebody to chase — this is a walk for one";
        }
        if (tag.polling()) return "There is already a vote open";
        if (!tag.suggest(playerId, player.name(), players.size())) return null;
        say(tag.question() + "  ·  " + (int) Tag.VOTE_SECONDS + "s to answer");
        return tag.question();
    }

    /**
     * One answer to the open poll.
     *
     * <p>The result is not decided here — see {@link Tag#tick}, which closes the
     * poll on the world's clock whether it was settled by the last vote or by
     * running out of time. Both paths through one piece of code is what stops a
     * walk where everybody answers at once behaving differently from a walk where
     * one person is making tea.
     */
    public synchronized boolean voteTag(int playerId, boolean yes) {
        WatchPlayer player = players.get(playerId);
        if (player == null || !tag.vote(playerId, yes)) return false;
        say(player.name() + (yes ? " is in" : " would rather not") + "  ·  " + tag.tally());
        return true;
    }

    /**
     * Pull the trigger.
     *
     * <p><b>Refused to anybody who is not it</b>, and to whoever is it while they
     * are still frozen — the gun is in the satchel and the satchel is not what
     * decides. A jet that has left is an ordinary thing in the air from there on;
     * what it does when it arrives is in {@link #flyHurls}.
     *
     * @return whether a jet left the barrel
     */
    public synchronized boolean squirt(int playerId) {
        WatchPlayer player = players.get(playerId);
        if (player == null || !tag.isIt(playerId)) return false;
        if (tag.frozen(playerId) || !tag.loaded(playerId)) return false;
        tag.fired(playerId);
        long id = nextHurlId++;
        double x = player.x() + Math.sin(player.yaw()) * Tag.BARREL_AHEAD;
        double y = player.y() - Math.cos(player.yaw()) * Tag.BARREL_AHEAD;
        hurls.put(id, Hurl.squirted(id, Tag.JET, player.name(), x, y,
                player.z() + Tag.BARREL_Z, player.yaw(), player.pitch(), Tag.JET_SPEED));
        return true;
    }

    /**
     * Somebody has been caught.
     *
     * <p>Three things and they are one sentence: it changes hands, the gun goes
     * with it, and the party is told. The gun moving is what makes the round
     * legible from outside — a walker across the clearing can see who is carrying
     * it — and it is why the item exists at all, given that {@link #squirt} asks
     * the round rather than the satchel whether a shot is allowed.
     */
    private void soak(WatchPlayer caught, String by) {
        WatchPlayer thrower = playerNamed(by);
        if (!tag.pass(caught.id(), caught.name(), by)) return;
        if (thrower != null) thrower.satchel().take(Tag.GUN, thrower.satchel().count(Tag.GUN));
        caught.satchel().add(Tag.GUN, 1);
        say((by == null ? "Somebody" : by) + " soaked " + caught.name()
                + " — they are it, and frozen for " + (int) Tag.FREEZE_SECONDS + " seconds");
    }

    /**
     * Take every water gun in the party back.
     *
     * <p>A sweep rather than a hand-back from whoever is it, because the round
     * can end while the gun is somewhere else: its owner may have been killed by
     * a wendigo and dropped the whole satchel, or left the walk carrying it. One
     * loop over eight bags costs nothing and cannot leave a toy behind.
     */
    private void takeGuns() {
        for (WatchPlayer player : players.values()) {
            player.satchel().take(Tag.GUN, player.satchel().count(Tag.GUN));
        }
    }

    /**
     * Hand the poll's answer, and the freeze's end, to the party.
     *
     * <p>Called from the tick with whatever {@link Tag#tick} decided. The round
     * itself has already started or ended by the time this runs — {@code Tag}
     * applies its own outcome — so this is only the announcements and the gun.
     */
    private void settleTag(Tag.Tick played) {
        switch (played.poll()) {
            case STARTED -> {
                WatchPlayer it = players.get(tag.itId());
                if (it != null) it.satchel().add(Tag.GUN, 1);
                say("Tag! " + tag.it() + " is it — frozen for "
                        + (int) Tag.FREEZE_SECONDS + " seconds, then a water gun and "
                        + Tag.IT_SPEED + "× pace");
            }
            case STOPPED -> {
                takeGuns();
                say("The tag is over" + scoreline());
            }
            case REFUSED -> say("The party said no");
            case NOTHING -> { }
        }
        // Said last, because a poll closing on the same tick a freeze ends is the
        // ordinary case rather than a corner of one: a call-off suggested the
        // instant somebody was tagged runs on that tag's own clock.
        if (played.thawed() && tag.running()) {
            say(tag.it() + " can move — look out");
        }
    }

    /** Who tagged whom, for the line that ends a round. */
    private String scoreline() {
        Map<String, Integer> board = tag.scoreboard();
        if (board.isEmpty()) return " — nobody was caught";
        StringBuilder sb = new StringBuilder(" — ");
        for (Map.Entry<String, Integer> entry : board.entrySet()) {
            if (sb.length() > 3) sb.append(", ");
            sb.append(entry.getKey()).append(' ').append(entry.getValue());
        }
        return sb.toString();
    }

    // --- the bounty board -------------------------------------------------------------

    /**
     * Pin up an Eye Spy bounty — <b>one per walker per day.</b>
     *
     * <p>The species travels and the price does not: a client naming what it
     * would like found is a request like every other one here, and a client
     * naming what that is worth would be a client with a hundred points a day to
     * award itself. The host rolls it, out of the world's own generator. See
     * {@link Bounty}.
     *
     * @return a line for whoever asked, refusal included, or {@code null} when
     *         there is no such player or no such species
     */
    public synchronized String postBounty(int playerId, String speciesKey) {
        WatchPlayer player = players.get(playerId);
        AnimalDef def = AnimalRegistry.byKey(speciesKey);
        if (player == null || def == null) return null;
        // A walk that can never hold anybody else is a walk where a bounty could
        // never be claimed — see Bounty, where the poster is the one walker who
        // may not answer their own question. Refused on the <em>cap</em> rather
        // than on how many are currently out, because a hosted walk somebody
        // happens to be alone on this afternoon is a walk with seven seats in it.
        if (config.maxPlayers() < Tag.LEAST_PLAYERS) {
            return "A bounty is for somebody else to find — this is a walk for one";
        }
        long now = System.currentTimeMillis();
        if (bounties.postedToday(player.name(), now)) {
            long hours = bounties.hoursUntilNextPosting(player.name(), now);
            return "One bounty a day — yours comes round again in " + hours
                    + (hours == 1 ? " hour" : " hours");
        }
        if (bounties.open(speciesKey)) {
            return "There is already a bounty on the " + def.name();
        }
        Bounty.Posting posting = bounties.post(speciesKey, Bounty.roll(rng),
                player.name(), now);
        if (posting == null) return null;
        say(player.name() + " wants a " + def.name() + " found — "
                + posting.points() + " points to whoever spots one");
        return "Pinned up — " + posting.describe();
    }

    /**
     * Settle whatever was on the board for a species somebody has just seen.
     *
     * <p>Called from both places a sighting is written — a spot and a landed fish
     * — because a bounty is about the species being <em>found</em> and the game
     * has two ways of finding one. It answers {@code null} for the ordinary case,
     * which is nearly every sighting of nearly every walk.
     */
    private Bounty.Posting claimBounty(String species, WatchPlayer finder) {
        Bounty.Posting claimed = bounties.claim(species, finder.name(),
                System.currentTimeMillis());
        if (claimed == null) return null;
        guide.reward(claimed.points());
        say(finder.name() + " claimed " + claimed.poster() + "'s bounty on the "
                + claimed.name() + " — " + claimed.points() + " points to the book");
        return claimed;
    }

    /**
     * What a screen offers somebody about to post a bounty, for where they are
     * standing.
     *
     * <p>Here as well as on {@link Bounty} so that a solo walk and a client both
     * ask the same object the same question about the same biome. The list is a
     * convenience rather than a rule — see {@link Bounty#choices}, which explains
     * why the host does not check a posting against it.
     */
    public synchronized List<AnimalDef> bountyChoices(int playerId) {
        WatchPlayer player = players.get(playerId);
        if (player == null) return List.of();
        WatchBiome biome = field.biomeAt(player.x(), player.y());
        return bounties.choices(guide, biome.key(), Bounty.CHOICES);
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

        // A fire or a lantern standing there. Above the oars and below the
        // feeders, on the same principle as both: it is a thing somebody put
        // here on purpose, and one that wants tending is the more urgent of the
        // two — a boat will still be on the beach in an hour and the fire will
        // not.
        PlacedLight burning = lights.nearest(player.x(), player.y(), REACH);
        if (burning != null) {
            return new Pickable(Pickable.Kind.FIRE, burning.kind().key(),
                    burning.describe(), burning.x(), burning.y(), burning.flameZ(),
                    burning.reach());
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
            /**
             * A fire or a lantern somebody put down. See
             * {@link com.larsons.engine.watch.light.Lights}.
             *
             * <p>"Tend" rather than "Take", because what the key does depends on
             * what the light needs: an armful of branches goes on a guttering
             * fire, a cold one is lit, and a lantern with nothing wrong with it
             * is picked up. One verb, three outcomes, and the prompt says which
             * by naming the light's own state — see
             * {@link com.larsons.engine.watch.light.PlacedLight#describe}.
             */
            FIRE("Tend"),
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
            case FIRE -> tendLight(playerId);
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
     * Buy something off the clothes rail.
     *
     * <p>{@link #buy}'s twin, and deliberately its own verb rather than a
     * branch inside it. What separates them is not the payment — both come out
     * of the guide, because there is one purse — but where the goods land: a
     * plank goes in a satchel as a count, and a coat goes in the buyer's
     * {@link Outfit} as a thing they now own. Folding the two together would
     * mean one method whose second half was an {@code if} about which of two
     * unrelated collections to touch.
     *
     * <p>Two refusals of its own, and neither takes anything:
     *
     * <ul>
     *   <li><b>this keeper does not have it.</b> A rail is a handful of the
     *       catalogue chosen by the post's own hash, and a client naming
     *       something off a different rail is a client that walked to the wrong
     *       shop;</li>
     *   <li><b>you already own it.</b> There is nothing to gain by a second
     *       one — a piece is not a count — so buying it twice would be a
     *       keeper taking your points for a hat you are already wearing.</li>
     * </ul>
     *
     * <p>Bought, and then <em>put on</em>, which is the one place this game
     * assumes what a player wanted. Somebody who has just spent two hundred
     * points on an antler circlet at a counter did not want to own it. It is
     * one click to take off again — see {@link #wear} — so the assumption
     * costs nothing when it is wrong.
     *
     * @param shopId which post, as the client understood it; a request naming a
     *               post the player is not standing at is refused outright
     * @return a line for the HUD, or {@code null} when nothing was bought
     */
    public synchronized String buyWorn(int playerId, long shopId, String key) {
        WatchPlayer player = players.get(playerId);
        Shops.Shop shop = shopAt(playerId);
        if (player == null || shop == null) return null;
        if (shopId != 0 && shop.id() != shopId) return null;
        Cosmetics.Piece piece = shop.worn(key);
        if (piece == null) return null;
        if (player.outfit().owns(key)) {
            return "You already have the " + piece.name() + ".";
        }
        // Debug mode buys off a rail for the same reason it buys off a shelf,
        // and through the same one line. See Debug.Power.POINTS.
        if (!player.debugging() && !guide.spend(piece.price())) {
            return "Not enough points — " + piece.priceLine() + ", and the book has "
                    + guide.points();
        }
        player.outfit().acquire(key);
        player.outfit().wear(key);
        say(player.name() + " bought the " + piece.name() + " at " + shop.sign());
        return "Bought the " + piece.name() + " for " + piece.priceLine();
    }

    /**
     * Put something on, or take it off again.
     *
     * <p><b>No counter, and no cost.</b> Every other verb on this page is
     * gated on standing at a shop because every other verb changes what the
     * party has; this one changes what one person looks like, out of things
     * they have already paid for. Gating it here would mean the rule could
     * never be relaxed without the host changing its mind about what a hat is.
     *
     * <p>The screen is a different question, and today's answer is narrower
     * than this: {@code WatchScene}'s only way to work a wardrobe is the shop
     * panel's clothes rail, so in practice a player changes at a counter. That
     * is a decision about where the rows are drawn and not about what is
     * allowed — a wardrobe on the satchel screen would need nothing here.
     *
     * <p>The one thing the host does insist on is ownership, and that is the
     * whole of why this is a host verb at all rather than a client toggle: what
     * somebody is wearing goes out on their snapshot row to everybody, so a
     * client that could dress itself could wear a cloak it never bought.
     *
     * @return a line for the HUD, or {@code null} when nothing changed
     */
    public synchronized String wear(int playerId, String key) {
        WatchPlayer player = players.get(playerId);
        return player == null ? null : player.outfit().toggle(key);
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
        List<Chart.Landmark> icons = Survey.survey(field, shops, homes,
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

    // --- fire and lamplight -----------------------------------------------------------

    /**
     * How far in front of somebody a light is set down, in metres.
     *
     * <p>Shorter than the stand-off a house is bought at, because a light is a
     * thing you put at your feet rather than a building you put up: a fire that
     * appeared two metres away would be a fire you have to walk to in order to
     * tend.
     */
    private static final double PLACE_AHEAD = 1.6;

    /** How deep water has to be before nothing can be stood in it. */
    private static final double DRY_ENOUGH = 0.2;

    /**
     * Light what is in the hand, put it out, or fill it — <b>one verb, because
     * a player pressing it always means the same thing.</b>
     *
     * <p>"Make it dark" and "make it light" are one intention with a state
     * attached, and a game that asked for two keys would be a game where you
     * have to remember which of them you last pressed. So: burning goes out;
     * out with oil in it comes back; empty with fuel in the satchel is filled
     * and lit; hands empty takes the best light in the satchel and lights that.
     *
     * <p>"Best" is <b>whichever burns longest</b>, with "for ever" at the top —
     * a jar of spores, then a lantern, then a torch. That is the right order
     * because it is the order of what each one costs to keep burning: nobody
     * should spend a torch while carrying a light that never goes out, and
     * a rule that read the enum's own order instead would depend on which way
     * round somebody happened to write the four rows.
     *
     * @return a line for the HUD, or {@code null} when there is nothing to burn
     */
    public synchronized String tendLamp(int playerId) {
        WatchPlayer player = players.get(playerId);
        if (player == null) return null;
        LightKind held = LightKind.ofItem(player.lamp());

        if (player.lampLit()) {
            player.douseLamp();
            return "Put the " + (held == null ? "light" : held.displayName()) + " out";
        }
        if (held != null) {
            if (player.relightLamp(held.eternal())) {
                return "Lit the " + held.displayName();
            }
            // In the hand and empty. Filling it is the same gesture, because
            // "my lantern has gone out" and "my lantern needs oil" are the same
            // problem from the player's side.
            if (held.fuel() != null && player.satchel().take(held.fuel(), 1)) {
                player.fillLamp(held.burnHours());
                return "Filled the " + held.displayName() + " with "
                        + Forage.nameOf(held.fuel());
            }
            if (held.fuel() != null) {
                return "The " + held.displayName() + " is empty — it needs "
                        + Forage.nameOf(held.fuel());
            }
        }
        LightKind take = bestCarryable(player);
        if (take == null) return null;
        player.carryLight(take.item(), take.burnHours());
        return "Lit the " + take.displayName();
    }

    /** The best thing in a satchel to be carrying lit. See {@link #tendLamp}. */
    private LightKind bestCarryable(WatchPlayer player) {
        LightKind best = null;
        for (LightKind kind : LightKind.all()) {
            if (!kind.carryable() || kind.item() == null) continue;
            if (!player.satchel().has(kind.item())) continue;
            if (best == null || lampRank(kind) > lampRank(best)) best = kind;
        }
        return best;
    }

    /** How long a light lasts, with "for ever" at the top. */
    private static double lampRank(LightKind kind) {
        return kind.eternal() ? Double.MAX_VALUE : kind.burnHours();
    }

    /**
     * Set a light down in front of the player: the one in hand, else a fire.
     *
     * <p><b>The order is the whole of the design.</b> A player with a lantern
     * out and a key that builds campfires would build a campfire, which is not
     * what anybody pressing "put this down" means. So: what is in your hand goes
     * down first, exactly as it was — lit if it was lit, with the oil it has
     * left; with empty hands you build a fire out of branches and stones; and
     * failing both you take a light out of the satchel and stand it up burning,
     * because a light nobody lit is a light nobody would notice they had put
     * down.
     *
     * @return the light now standing there, or {@code null} when there was
     *         nothing to put down or nowhere to put it
     */
    public synchronized PlacedLight setDownLight(int playerId) {
        WatchPlayer player = players.get(playerId);
        if (player == null) return null;
        double x = player.x() + Math.sin(player.yaw()) * PLACE_AHEAD;
        double y = player.y() - Math.cos(player.yaw()) * PLACE_AHEAD;
        double z = field.heightAt(x, y);
        // Not in a lake, and not on top of another one. Both refusals are
        // silent-ish — the caller says so — because the alternative is a fire
        // burning on the surface of a tarn.
        if (field.waterDepth(z) > DRY_ENOUGH) return null;
        if (lights.blocked(x, y)) return null;

        LightKind held = LightKind.ofItem(player.lamp());
        if (held != null && player.satchel().take(held.item(), 1)) {
            // Set down as it was: a lamp somebody had put out goes down out,
            // and one they were carrying lit stays lit. Anything else would be
            // the world disagreeing with the hand it came from.
            PlacedLight put = lights.place(held, x, y, z, player.yaw(), player.name(),
                    System.currentTimeMillis(), player.lampFuel(), player.lampLit());
            player.dropLamp();
            say(player.name() + " set down a " + held.displayName());
            return put;
        }
        if (LightKind.CAMPFIRE.pay(player.satchel())) {
            PlacedLight fire = lights.place(LightKind.CAMPFIRE, x, y, z, player.yaw(),
                    player.name(), System.currentTimeMillis());
            say(player.name() + " lit a campfire");
            return fire;
        }
        for (LightKind kind : LightKind.all()) {
            if (kind.item() == null || !player.satchel().has(kind.item())) continue;
            player.satchel().take(kind.item(), 1);
            PlacedLight put = lights.place(kind, x, y, z, player.yaw(), player.name(),
                    System.currentTimeMillis());
            say(player.name() + " set down a " + kind.displayName());
            return put;
        }
        return null;
    }

    /**
     * Feed, relight or pick up the light somebody is standing at.
     *
     * <p>What {@code E} does when the thing in reach is burning, and one method
     * rather than three keys for the same reason {@link #use} is one key for
     * eight kinds of picking: standing in front of a guttering fire with an
     * armful of branches, there is exactly one thing you want to happen.
     *
     * @return a line for the HUD, or {@code null} when there was nothing to do
     */
    public synchronized String tendLight(int playerId) {
        WatchPlayer player = players.get(playerId);
        if (player == null) return null;
        PlacedLight light = lights.nearest(player.x(), player.y(), REACH);
        if (light == null) return null;
        LightKind kind = light.kind();

        if (kind.fuel() != null && light.fuelLeft() < 1
                && player.satchel().has(kind.fuel())) {
            player.satchel().take(kind.fuel(), 1);
            light.feed(1);
            return "Fed the " + kind.displayName() + " — " + light.hoursLabel();
        }
        if (light.relight()) return "Lit the " + kind.displayName();
        if (kind.carryable()) {
            lights.remove(light.id());
            player.satchel().add(kind.item(), 1);
            // Straight into an empty hand, still burning, with the oil it had:
            // picking a lit lantern off the ground and having it go out would
            // be the world contradicting itself.
            if (player.lamp() == null) {
                player.carryLight(kind.item(),
                        kind.eternal() ? 0 : light.fuelHours());
                if (!light.lit()) player.douseLamp();
            }
            return "Took the " + kind.displayName();
        }
        return kind.fuel() == null ? null
                : "The " + kind.displayName() + " needs " + Forage.nameOf(kind.fuel());
    }

    /** The light somebody is standing at, or {@code null}. */
    public synchronized PlacedLight lightAt(int playerId) {
        WatchPlayer player = players.get(playerId);
        return player == null ? null
                : lights.nearest(player.x(), player.y(), REACH);
    }

    /** Adopt a light sent by the host. */
    public synchronized void addLight(PlacedLight light) {
        lights.adopt(light);
    }

    /**
     * Burn one player's lamp down, and take the ash away.
     *
     * <p>The one place a carried light can actually be <em>spent</em>: a torch
     * that has burnt out is not a torch you can relight, so the item leaves the
     * satchel with the flame. A lantern keeps its glass and waits for oil, which
     * is the difference {@link LightKind#leavesEmbers} names.
     */
    private void burnLamp(WatchPlayer player, double hours) {
        LightKind kind = LightKind.ofItem(player.lamp());
        if (kind == null) return;
        if (!player.burnLamp(hours, kind.eternal())) return;
        if (kind.leavesEmbers()) {
            say(player.name() + "'s " + kind.displayName().toLowerCase(
                    java.util.Locale.ROOT) + " has burnt out");
            return;
        }
        player.satchel().take(kind.item(), 1);
        player.dropLamp();
        say(player.name() + "'s " + kind.displayName().toLowerCase(java.util.Locale.ROOT)
                + " has burnt down to nothing");
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

    // --- houses ----------------------------------------------------------------------

    /**
     * How steep the ground under a house may be, as a fall across its own
     * footprint's diagonal, and the most that fall may ever be in metres.
     *
     * <p><b>Deliberately generous, because a house does not have to be level
     * with the ground — it has to stand on it.</b> A trading post is sited by a
     * much stricter rule ({@code Shops.MAX_SLOPE}) and can afford to be: the
     * generator tries two dozen spots and keeps the flattest. A player has
     * pressed a key and is looking at a hillside, and a game that answers "not
     * there" to most of a wood is a game whose house catalogue nobody uses. So
     * the ground is allowed to fall away by over half the footprint's diagonal
     * and the house is put up on piers that reach down to it — see
     * {@link HouseKit}, which draws those piers, and the front steps that come
     * down beside them.
     *
     * <p>The cap is what stops that becoming a tower. Past a couple of metres
     * of fall the thing under the floor is scaffolding rather than footings —
     * and, worse, a house you can walk <em>under</em> rather than into, since
     * its walls start at its own floor. A player who wants to live that far off
     * the ground should be buying a treehouse.
     */
    private static final double MAX_HOUSE_FALL = 0.55, MAX_HOUSE_PIERS = 2.2;

    /** How far above the water line a house has to stand, in metres. */
    private static final double DRY_MARGIN = 0.5;

    /**
     * What the ground at a site will take.
     *
     * @param top     the height the floor is laid at, which is the highest
     *                ground under the footprint — a floor below the hillside it
     *                stands on is a floor you cannot walk on
     * @param fall    how far the ground drops below that, which is how long the
     *                piers have to be
     * @param refusal why nothing can stand here, or {@code null}
     */
    private record Site(double top, double fall, String refusal) {}

    /**
     * Buy a house and stand it in front of the player.
     *
     * <p><b>The guide pays, exactly as it does at a counter.</b> See
     * {@link #buy} for the argument: there is one book, one page and one purse,
     * and a per-player wallet would be the one thing in this game that was not
     * shared. A house therefore belongs to the party, and anybody may
     * {@linkplain #packUp take one down} again.
     *
     * <p>The order of the checks matters and is deliberate: <b>site first, pay
     * second.</b> A player who is charged three thousand points and then told
     * there is a lake in the way has been robbed, and the refusal has to happen
     * before {@link FieldGuide#spend} is reached.
     *
     * @param turn   how far round from facing the buyer the house is turned,
     *               in eighths — see {@link #facingBuyer}
     * @return what happened, always with a line for the HUD — see
     *         {@link Homestead.Outcome}
     */
    public synchronized Homestead.Outcome buyHome(int playerId, HousePlan plan, int turn) {
        WatchPlayer player = players.get(playerId);
        if (player == null || plan == null) return Homestead.Outcome.refused(null);

        turn = facingBuyer(player.yaw(), turn);

        // Its own depth in front of the buyer, so a mansion appears in front of
        // them rather than around them.
        double x = Homestead.snap(player.x() + Math.sin(player.yaw()) * plan.standOff());
        double y = Homestead.snap(player.y() - Math.cos(player.yaw()) * plan.standOff());
        double z = field.heightAt(x, y);
        long treeId = 0;
        double drop = 0;

        if (plan.tree()) {
            TreeInstance tree = nearestAnchorTree(x, y);
            if (tree == null) {
                return Homestead.Outcome.refused(
                        "No tree big enough near here to hang a " + plan.displayName()
                                + " in");
            }
            x = Homestead.snap(tree.x());
            y = Homestead.snap(tree.y());
            // Just over half way up, which is where a crown starts and where a
            // deck is out of the wind and still under cover.
            z = tree.z() + Math.max(2.6, tree.height() * 0.52);
            drop = z - field.heightAt(x, y);
            treeId = tree.id();
        } else {
            Site site = siteFor(plan, x, y, turn);
            if (site.refusal() != null) return Homestead.Outcome.refused(site.refusal());
            z = site.top();
            drop = site.fall();
        }
        if (homes.blocked(plan, x, y)) {
            return Homestead.Outcome.refused("There is already a house standing there");
        }
        if (!player.debugging() && !guide.spend(plan.price())) {
            return Homestead.Outcome.refused("Not enough points — " + plan.priceLine()
                    + ", and the book has " + guide.points());
        }

        Homestead.Home home = homes.place(plan, x, y, z, turn, treeId, drop,
                player.name(), System.currentTimeMillis());
        raiseBoard(home);
        say(player.name() + " put up a " + plan.displayName());
        return new Homestead.Outcome(home,
                "Bought a " + plan.displayName() + " for " + plan.priceLine());
    }

    /**
     * Which compass turn a house takes, given the way its buyer is looking.
     *
     * <p><b>A house faces the person who bought it, and the turn is measured
     * from there.</b> That is the only arrangement that makes sense of a
     * catalogue: the house lands in front of you, so its front door has to be
     * the side you are looking at, or every purchase begins by walking round
     * the building to find the way in. The turn key then rotates it away from
     * that, an eighth at a time.
     *
     * <p>Resolved on the host rather than sent by the client, like every other
     * decision here: what travels is "three eighths round from facing me", and
     * what is stored is a compass bearing the mesher can draw.
     */
    public static int facingBuyer(double buyerYaw, int turn) {
        int eighths = (int) Math.round(buyerYaw / (Math.PI * 2 / Homestead.TURNS));
        // …plus a half turn, which is what "facing back at them" is.
        return Math.floorMod(eighths + Homestead.TURNS / 2 + turn, Homestead.TURNS);
    }

    /**
     * What the ground at a site will take.
     *
     * <p>Nine samples — four corners, four edges and the middle — which is
     * enough to catch the two things that actually happen: a footprint half in
     * a lake, and a footprint across a bank. A proper sweep would be twenty
     * heights off the generator on a verb a player presses once.
     */
    private Site siteFor(HousePlan plan, double x, double y, int turn) {
        // The footprint as the house will actually stand: forward is
        // (sin, −cos) and right is (cos, sin), the game's convention everywhere.
        double yaw = Math.floorMod(turn, Homestead.TURNS) * Math.PI * 2 / Homestead.TURNS;
        double sin = Math.sin(yaw), cos = Math.cos(yaw);
        double low = Double.MAX_VALUE, high = -Double.MAX_VALUE;
        double hA = plan.halfAlong(), hC = plan.halfAcross();
        for (int a = -1; a <= 1; a++) {
            for (int c = -1; c <= 1; c++) {
                double px = x + a * hA * sin + c * hC * cos;
                double py = y - a * hA * cos + c * hC * sin;
                double h = field.heightAt(px, py);
                if (field.waterDepth(h) > 0 || h < TerrainField.WATER_LEVEL + DRY_MARGIN) {
                    return new Site(0, 0,
                            "A " + plan.displayName() + " will not stand in water");
                }
                low = Math.min(low, h);
                high = Math.max(high, h);
            }
        }
        double fall = high - low;
        double allowed = Math.min(plan.diagonal() * MAX_HOUSE_FALL, MAX_HOUSE_PIERS);
        return fall > allowed
                ? new Site(high, fall,
                        "The ground here falls away too far for a " + plan.displayName())
                : new Site(high, fall, null);
    }

    /**
     * Take a house down and put half its price back in the book.
     *
     * <p>The other half of "place it anywhere", and the reason the catalogue can
     * be as expensive as it is: a player who has just spent three thousand
     * points putting a mansion somewhere they did not mean to has to be able to
     * undo it. Half back rather than all, because a decision that costs nothing
     * to reverse is not a decision.
     *
     * @return what happened, always with a line for the HUD — see
     *         {@link Homestead.Outcome}
     */
    public synchronized Homestead.Outcome packUp(int playerId) {
        WatchPlayer player = players.get(playerId);
        if (player == null) return Homestead.Outcome.refused(null);
        Homestead.Home home = homes.at(player.x(), player.y());
        if (home == null) {
            return Homestead.Outcome.refused("There is no house here to take down");
        }
        homes.remove(home.id());
        // The board goes with the timber it hung on, and the maps that were
        // pinned to it go back into the world rather than with it.
        cartography.removeBoard(home.id());
        int back = home.refund();
        guide.refund(back);
        say(player.name() + " packed up the " + home.plan().displayName());
        return new Homestead.Outcome(home, "Packed up the "
                + home.plan().displayName() + " — " + back
                + (back == 1 ? " point" : " points") + " back");
    }

    /**
     * Register the map board of a house that has one.
     *
     * <p>The last thing the building system did that nothing else could. A study
     * wall is timber, which the house already draws, <em>and</em> a place maps
     * can be pinned, which is this — and it is raised when the house is bought
     * rather than lazily on first use, so that a board nobody has walked up to
     * yet is still a board in the save and on everybody else's copy of the
     * world. See {@link HousePlan#board()}.
     */
    private void raiseBoard(Homestead.Home home) {
        double[] at = homes.boardOf(home);
        if (at == null) return;
        cartography.raise(home.id(), at[0], at[1], at[2], at[3], home.boughtBy());
    }

    /** The house a player is standing in or beside, or {@code null}. */
    public synchronized Homestead.Home homeAt(int playerId) {
        WatchPlayer player = players.get(playerId);
        return player == null ? null : homes.at(player.x(), player.y());
    }

    /** The planted tree nearest a point that is big enough to hold a house. */
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
        // A fish is a sighting, so it settles a bounty like any other. Somebody
        // who puts a hundred points on a species that can only be caught has
        // asked for a fishing trip, which is a perfectly good thing to ask for.
        claimBounty(fish.key(), player);
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
            // Everything burning burns down, on the wall clock rather than on
            // the tick, for the reason the trees grow on it: a fire lit before
            // bed should be out in the morning whether or not anybody was
            // logged in, and four hours has to mean four hours or it means
            // nothing.
            for (PlacedLight died : lights.burn(realHours)) {
                say("The " + died.kind().displayName().toLowerCase(java.util.Locale.ROOT)
                        + " has burnt out");
            }
            for (WatchPlayer player : players.values()) burnLamp(player, realHours);
        }

        for (WatchPlayer player : players.values()) {
            player.tick(dt);
            player.rod().tick(dt);
        }

        for (Animal animal : animals.values()) {
            animal.step(dt, this);
            if (animal.behaviour() == Animal.Behaviour.FEED) feedFrom(animal);
        }
        // After the animals, because a shard thrown this tick should not also
        // travel this tick: a projectile that appears already a metre out of the
        // thrower's hand reads as a teleport rather than as a throw.
        flyHurls(dt);

        spotlights.replaceAll(light -> light.aged(dt));
        spotlights.removeIf(light -> !light.alive());
        lures.values().removeIf(Lure::spoiled);

        // The party game: the poll's clock, the freeze, and whatever the party
        // decided. Tag applies its own outcome; what comes back is the news.
        Tag.Tick played = tag.tick(dt);
        if (!played.quiet()) settleTag(played);

        // …and anything nobody claimed in time. On the wall clock like the trees
        // and the fires, because a day has to mean a day: a bounty pinned up
        // before bed is stale in the morning whether or not anybody was logged in.
        if (realHours > 0) {
            for (Bounty.Posting lapsed : bounties.expire(now)) {
                say("Nobody found " + lapsed.poster() + "'s " + lapsed.name()
                        + " — the bounty has come down");
            }
        }

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
     * A wendigo lets one go.
     *
     * <p>The animal decided; the world builds the shard and owns it from here.
     * See {@link Hurl} for why this game has a projectile at all and why it is
     * not the block world's.
     */
    @Override
    public synchronized void hurlAt(Animal from, String at, Mutants.Ranged ranged) {
        WatchPlayer player = playerNamed(at);
        if (player == null || !player.alive() || ranged == null) return;
        long id = nextHurlId++;
        hurls.put(id, Hurl.thrown(id, from, player.x(), player.y(), player.z(), ranged));
    }

    /**
     * Advance everything in the air, and see what it hit.
     *
     * <p><b>Checked against every player rather than only the one it was aimed
     * at</b>, which is not a nicety: a party walking together would otherwise
     * watch shards pass through three people to reach the fourth, and the
     * obvious tactic against a wendigo would be to stand behind a friend
     * without it costing them anything.
     *
     * <p>Two things fly and the difference between them is only what happens on
     * arrival — a bone shard wounds, a jet of water makes somebody it. The one
     * extra rule the jet needs is that it cannot catch the person who fired it:
     * it leaves half a metre in front of their chest, which is well inside
     * {@link Hurl#HIT_RADIUS} of it. See {@link Tag}.
     */
    private void flyHurls(double dt) {
        if (hurls.isEmpty()) return;
        for (Hurl hurl : List.copyOf(hurls.values())) {
            if (!hurl.step(dt, field.heightAt(hurl.x(), hurl.y()))) continue;
            boolean jet = Tag.JET.equals(hurl.species());
            for (WatchPlayer player : players.values()) {
                if (!player.alive()) continue;
                if (jet && player.name().equals(hurl.owner())) continue;
                if (!hurl.hits(player.x(), player.y(), player.z())) continue;
                hurl.expire();
                if (jet) {
                    soak(player, hurl.owner());
                    break;
                }
                // Through the same door a swipe goes through, so a death by
                // shard drops the satchel and respawns exactly as a death by
                // claw does. The attacker's name comes off the species the
                // shard remembers, so the log line reads the same either way.
                AnimalDef thrower = AnimalRegistry.byKey(hurl.species());
                wound(player.name(), hurl.damage(),
                        thrower == null ? null : throwerOf(hurl));
                break;
            }
        }
        hurls.values().removeIf(Hurl::spent);
    }

    /**
     * A stand-in for the animal that threw a shard, for the sake of the log
     * line.
     *
     * <p>The real thrower may be dead, despawned or four hundred metres away by
     * the time its shard lands — a shard is not owned by the animal that let it
     * go. What {@link #wound} wants from it is a species name, and the shard
     * remembers that, so this hands back whichever live animal of that species
     * is nearest and {@code null} when there is none. {@code wound} says
     * "Something" for a null, which is the honest answer when the thing that
     * hurt you is no longer there.
     */
    private Animal throwerOf(Hurl hurl) {
        for (Animal animal : animals.values()) {
            if (animal.def().key().equals(hurl.species())) return animal;
        }
        return null;
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
        // The lamp goes with the bag, because it was in the bag. Dropping the
        // satchel and keeping the light burning would be the one thing that
        // survived dying, and it would be the thing that mattered most.
        player.dropLamp();
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
        m.put("homes", homes.toMap());
        // The maps, which are the one piece of world state that is mostly
        // somebody's handwriting: where each one is and what has been drawn on
        // it. The paper itself is not here — see Chart — so a walk with fifty
        // maps in it costs a few kilobytes of save.
        m.put("maps", cartography.toMap());
        m.put("sky", weather.toMap());
        m.put("boats", boats.toMap());
        // The Eye Spy board, which is the one thing here that is a promise: a
        // party that logs off with four bounties open should log back on to four
        // bounties open, and to the day-per-walker limit remembering that it has
        // already been spent. The tag round is deliberately not saved beside it —
        // see Tag.
        m.put("bounties", bounties.toMap());
        // Dropped satchels. The one thing on the floor of this world that is
        // not a function of the seed, and the one whose loss a player would
        // actually feel: reopening a walk to find the bag you died with gone is
        // the save file taking something away that the game deliberately did
        // not. See Spill.
        m.put("spills", spills.toMap());
        // The fires and lanterns, with however much is left in each. Saved with
        // the buildings rather than with the feeders because that is what they
        // are — a camp somebody made — and a walk reopened at a cold hearth
        // with the branches still beside it is the right way to come back to
        // one. See com.larsons.engine.watch.light.Lights.
        m.put("lights", lights.toMap());
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
        homes.load(WatchJson.map(m, "homes"));
        cartography.load(WatchJson.map(m, "maps"));
        weather.load(WatchJson.map(m, "sky"));
        boats.load(WatchJson.map(m, "boats"));
        bounties.load(WatchJson.map(m, "bounties"));
        spills.load(WatchJson.map(m, "spills"));
        lights.load(WatchJson.map(m, "lights"));
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
            // …and the fires burn down while the save sits on the disk, for the
            // same reason. A camp left lit a week ago is a cold hearth, not a
            // week-old fire.
            lights.burn(hours);
            // A bounty nobody claimed while the walk was closed has still gone
            // stale: a board reopened after a fortnight is a board with nothing
            // on it, not one holding a fortnight of unanswered questions.
            bounties.expire(System.currentTimeMillis());
        }
        lastRealMillis = System.currentTimeMillis();
    }
}
