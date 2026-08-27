package com.larsons.engine.watch;

import com.larsons.engine.watch.build.BuildPiece;
import com.larsons.engine.watch.build.Structure;
import com.larsons.engine.watch.life.Animal;
import com.larsons.engine.watch.life.AnimalDef;
import com.larsons.engine.watch.life.AnimalRegistry;
import com.larsons.engine.watch.life.Diet;
import com.larsons.engine.watch.world.Flora;
import com.larsons.engine.watch.world.Grove;
import com.larsons.engine.watch.world.TerrainField;
import com.larsons.engine.watch.world.TreeGenome;
import com.larsons.engine.watch.world.TreeInstance;
import com.larsons.engine.watch.world.TreeSpecies;
import com.larsons.engine.watch.world.WatchBiome;

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

    /** How many animals are kept alive per player. */
    private static final int PER_PLAYER = 26;

    /** …and in total, however many players there are. */
    private static final int TOTAL_CAP = 150;

    /** How far a click can reach to spot something, in metres. */
    public static final double SPOT_RANGE = 130;

    /** How far a hand can reach to pick, plant or build, in metres. */
    public static final double REACH = 4.5;

    private final Config config;
    private final TerrainField field;
    private final Flora flora;
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

    private final FieldGuide guide = new FieldGuide();
    private final Grove grove = new Grove();
    private final Cultivation crops = new Cultivation();
    private final Structure structure = new Structure();
    private final Weather weather;
    private final Boats boats;

    private Sink sink;
    private long nextAnimalId = 1;
    private long nextLureId = 1;
    private long lastRealMillis = System.currentTimeMillis();
    private double spawnTimer;

    public WatchGame(Config config) {
        this(config, null);
    }

    public WatchGame(Config config, Sink sink) {
        this.config = config;
        this.sink = sink;
        this.field = new TerrainField(config.seed());
        this.flora = new Flora(config.seed(), field);
        this.ground = Flora.ground(field);
        this.clock = WatchClock.fromSystem();
        this.rng = new Random(config.seed() ^ 0x5EED);
        this.weather = new Weather(config.seed());
        this.boats = new Boats(config.seed());
    }

    /** Where messages go; may be replaced when a solo game becomes a hosted one. */
    public synchronized void setSink(Sink sink) { this.sink = sink; }

    public Config config() { return config; }

    /** The generator this world is built from. */
    public TerrainField field() { return field; }

    /** The flora scatterer, so a client can ask the same questions. */
    public Flora flora() { return flora; }

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

    /** What the sky is doing, for everybody. */
    public Weather weather() { return weather; }

    /** The boats — where they were found, and where they have been left. */
    public Boats boats() { return boats; }

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
     * The animal a player is looking at, or {@code null}.
     *
     * <p>An angular test rather than a ray-box intersection: an animal is
     * "under the cursor" if the direction to it is within a few degrees of the
     * way the player is looking, and the tolerance widens with the animal's
     * size and narrows with distance. That is much more forgiving than a
     * geometric hit test, which is the right trade for a game where the target
     * is a sparrow forty metres away in a tree and the reward for hitting it is
     * a line in a book rather than damage.
     */
    public synchronized Animal lookingAt(int playerId) {
        WatchPlayer player = players.get(playerId);
        if (player == null) return null;
        double cp = Math.cos(player.pitch());
        double dirX = Math.sin(player.yaw()) * cp;
        double dirY = -Math.cos(player.yaw()) * cp;
        double dirZ = Math.sin(player.pitch());
        double eyeZ = player.eyeZ();

        Animal best = null;
        double bestScore = Double.MAX_VALUE;
        for (Animal animal : animals.values()) {
            double dx = animal.x() - player.x();
            double dy = animal.y() - player.y();
            double dz = animal.z() + animal.def().bodyLength() * 0.5 - eyeZ;
            double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (distance > SPOT_RANGE || distance < 0.01) continue;
            double dot = (dx * dirX + dy * dirY + dz * dirZ) / distance;
            if (dot <= 0) continue;
            double angle = Math.acos(Math.min(1, dot));
            // The tolerance an animal of this size subtends, with a floor so a
            // hummingbird at forty metres is not impossible to click.
            double tolerance = Math.max(0.022,
                    Math.atan2(animal.def().bodyLength() * 1.6, distance));
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
        boolean discovery = guide.record(sighting);

        Spotlight light = Spotlight.of(animal.id(), def.key(), player.name(),
                animal.x(), animal.y(), animal.z(), discovery);
        spotlights.add(light);
        if (discovery) {
            say(player.name() + " found a " + def.name() + " — new for the guide!");
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
        return null;
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
            BOAT("Take the oars");

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

        // Nothing in reach in front of you. Foraging the ground itself is
        // still a thing you can do — but it takes a moment and gives one thing,
        // rather than answering every press.
        long now = System.currentTimeMillis();
        Long last = foraged.get(playerId);
        if (last != null && now - last < GROUND_FORAGE_MILLIS) return null;
        foraged.put(playerId, now);

        WatchBiome biome = field.biomeAt(player.x(), player.y());
        if (!biome.seeds().isEmpty() && rng.nextDouble() < 0.55) {
            String seed = biome.seeds().get(rng.nextInt(biome.seeds().size()));
            player.satchel().add(seed, 1);
            return seed;
        }
        String material = materialUnderfoot(biome);
        player.satchel().add(material, 1);
        return material;
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

    /** What a biome's floor gives up when there is nothing better to pick. */
    private String materialUnderfoot(WatchBiome biome) {
        List<String> options = new ArrayList<>();
        if (!biome.trees().isEmpty()) {
            options.add("fallen_branch");
            options.add("bark_strip");
            options.add("sap");
        }
        if (biome.humidity() > 70) options.add("reed_bundle");
        if (biome.humidity() > 55) options.add("vine");
        if (biome.rockDensity() > 0.004) options.add("stone");
        if (biome.humidity() > 45) options.add("clay_lump");
        options.add("clover");
        options.add("feather");
        return options.get(rng.nextInt(options.size()));
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
        };
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
        return structure.place(piece, x, y, z, turn, treeId, player.name(),
                System.currentTimeMillis());
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
        boolean discovery = guide.record(new Sighting(fish.key(),
                System.currentTimeMillis(), clock.timeOfDay(), biome.key(),
                player.name(), player.x(), player.y(), !guide.seen(fish.key())));
        if (discovery) {
            say(player.name() + " landed a " + fish.name() + " — new for the guide!");
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
        }
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
        m.put("sky", weather.toMap());
        m.put("boats", boats.toMap());
        List<Object> lureRows = new ArrayList<>();
        for (Lure lure : lures.values()) lureRows.add(lure.toMap());
        m.put("lures", lureRows);
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
        weather.load(WatchJson.map(m, "sky"));
        boats.load(WatchJson.map(m, "boats"));
        lures.clear();
        for (Map<String, Object> row : WatchJson.objects(m, "lures")) {
            addLure(Lure.fromMap(row));
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
