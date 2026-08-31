package com.larsons.engine.watch;

import com.larsons.engine.watch.build.Structure;
import com.larsons.engine.watch.life.Animal;
import com.larsons.engine.watch.life.AnimState;
import com.larsons.engine.watch.life.AnimalDef;
import com.larsons.engine.watch.life.AnimalRegistry;
import com.larsons.engine.watch.life.Hurl;
import com.larsons.engine.watch.light.Lights;
import com.larsons.engine.watch.world.Grove;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Everything the screen needs, and nothing that decides anything.
 *
 * <p><b>One seam, two sources.</b> Playing alone, the scene owns a
 * {@link WatchGame} and copies it into one of these every frame. Playing
 * online, the scene owns no game at all and a {@code WatchClient} fills one of
 * these from the host's snapshots. The renderer, the HUD and the field guide
 * screen read this and never ask which of the two they are looking at — which
 * is what stops the single-player and multiplayer paths drifting into two
 * subtly different games.
 *
 * <p>It is deliberately dumb: records, lists, and no methods that change
 * anything. Everything that <em>decides</em> is on the server side of the seam.
 */
public final class WatchView {

    /**
     * One person, as drawn.
     *
     * @param glass the magnification they have a spyglass up at, {@code 1} for
     *              none — so a party can see who is looking at something, and
     *              which way, without anybody having to say so
     * @param debug whether they are in {@link Debug} mode; on this player's own
     *              row it is what turns their satchel bottomless on this side
     *              of the wire
     * @param health how much of their bar is left, {@code 1} whole to {@code 0}
     *              down — drawn for everybody, because a party spread over a
     *              valley finds out that one of them has met something by
     *              seeing it
     * @param respawns how many times they have been killed. See
     *              {@link WatchPlayer#respawns()}: this is how a respawn
     *              reaches the screen that has to act on it
     * @param light the forage key of the light they are carrying lit, or
     *              {@code null} for empty hands. On everybody's row rather than
     *              only on your own, because a lantern moving along the far
     *              side of a valley is how a party keeps track of each other
     *              after dark — see
     *              {@link com.larsons.engine.watch.light.LightField}
     * @param lightHours how many real hours of burning are left in it. In
     *              hours rather than as a fraction so that this row carries no
     *              knowledge of the light catalogue: what a full lantern holds
     *              is {@code LightKind}'s business, and the screen has that
     *              class to hand
     */
    public record Walker(int id, String name, double x, double y, double z,
                         double yaw, double pitch, double stillness, boolean crouching,
                         boolean submerged, double breath, long boatId, double glass,
                         boolean debug, double health, int respawns, String light,
                         double lightHours) {

        /** Whether they have something lit in their hand. */
        public boolean carryingLight() { return light != null && !light.isBlank(); }

        /** Whether they are rowing rather than walking. */
        public boolean inBoat() { return boatId != 0; }

        /** Whether they have a glass to their eye. */
        public boolean glassing() { return glass > 1.02; }

        /** Whether they are hurt at all. */
        public boolean hurt() { return health < 0.999; }
    }

    /** One animal, as drawn. */
    public record Creature(long id, AnimalDef def, double x, double y, double z,
                           double yaw, AnimState state, double phase, double trust,
                           String owner) {

        /** Whether it belongs to somebody. */
        public boolean tame() { return owner != null && trust >= 1; }
    }

    private final List<Walker> walkers = new ArrayList<>();
    private final List<Creature> creatures = new ArrayList<>();
    private final List<Lure> lures = new ArrayList<>();

    /**
     * Everything in the air — bone shards a wendigo has thrown. See
     * {@link com.larsons.engine.watch.life.Hurl}.
     */
    private final List<Hurl> hurls = new ArrayList<>();
    private final List<Spotlight> spotlights = new ArrayList<>();
    private final List<String> log = new ArrayList<>();

    /**
     * The pieces of ground litter somebody has already picked up.
     *
     * <p>The one thing about what is lying on the floor that cannot be worked
     * out from the seed. See {@link WatchGame#takenLitter()} for why it travels
     * and the rest of the litter does not.
     */
    private final Set<Long> takenLitter = new HashSet<>();

    private int selfId;
    private long seed;
    private String worldName = "";
    private double timeOfDay;
    private final Satchel satchel = new Satchel();
    private final FieldGuide guide = new FieldGuide();
    private final Grove grove = new Grove();
    private final Cultivation crops = new Cultivation();
    private final Structure structure = new Structure();

    /**
     * Every fire and lantern standing in the world.
     *
     * <p>Beside the buildings rather than beside the feeders, because that is
     * what they are: things the party put down that stay put. What is different
     * about them is that they burn out, which is why they ride the snapshot with
     * the feeders rather than the five-second world sync — see
     * {@link com.larsons.engine.watch.net.WatchProto#state}.
     */
    private final Lights lights = new Lights();

    /**
     * The party's maps and boards.
     *
     * <p>Here rather than beside the satchel even though a map lives in one,
     * because a map is a thing in the world that happens to be in somebody's
     * bag: the board across the valley has maps on it that are nobody's, and the
     * screen has to draw them. Which satchel a given map is in is a field on the
     * map — see {@link Chart#owner()}.
     */
    private final Cartography cartography = new Cartography();

    private Weather weather = new Weather(0);
    private Boats boats = new Boats(0);
    private Shops shops = new Shops(0);

    /**
     * Whether a game of tag is on, who is it, and what the party is being asked.
     *
     * <p>The one thing on this view that the screen reads back into how the local
     * player <em>moves</em>: {@code WatchScene.walk} takes its speed multiplier
     * off {@link Tag#speed} and refuses to move at all while
     * {@link Tag#frozen(int)}. That is a rule the client follows rather than one
     * it decides — the host enforces the freeze by refusing positions — but it
     * has to be here, because a client that walked on and was silently pulled
     * back twenty times a second would be a client that had lost control of its
     * own feet.
     */
    private final Tag tag = new Tag();

    /** What the party has put up for each other to find. */
    private final Bounty bounties = new Bounty();

    /**
     * The satchels lying where somebody died.
     *
     * <p>Beside the grove and the buildings rather than beside the litter,
     * because that is what it is: world state the host owns, sent whole on the
     * world sync. See {@link Spill}.
     */
    private final Spill spills = new Spill();

    /** How many lines of party chatter are kept. */
    private static final int LOG_LIMIT = 40;

    /** Which walker is this player. */
    public int selfId() { return selfId; }

    public void setSelfId(int id) { this.selfId = id; }

    /** The world's seed, so the client can generate the same terrain. */
    public long seed() { return seed; }

    /**
     * Take the world's seed, and rebuild everything derived from it.
     *
     * <p>The boats are a pure function of the seed, so a view told which world
     * it is looking at can work out where they all are without being sent one —
     * which is the whole reason they are generated rather than placed. Only the
     * handful somebody has rowed elsewhere arrive over the wire.
     *
     * <p>The trading posts are the same, and more completely so: not one byte
     * about a shop ever travels, because nothing about one can be changed. See
     * {@link Shops}.
     */
    public void setSeed(long seed) {
        if (this.seed != seed) {
            this.boats = new Boats(seed);
            this.weather = new Weather(seed);
            this.shops = new Shops(seed);
        }
        this.seed = seed;
    }

    /** What the sky is doing — the host's, when there is one. */
    public Weather weather() { return weather; }

    /** Where the boats are. */
    public Boats boats() { return boats; }

    /** Where the trading posts are, who keeps them, and what they sell. */
    public Shops shops() { return shops; }

    public String worldName() { return worldName; }

    public void setWorldName(String name) { this.worldName = name == null ? "" : name; }

    /** The host's time of day — the one everybody's sky is drawn from. */
    public double timeOfDay() { return timeOfDay; }

    public void setTimeOfDay(double timeOfDay) { this.timeOfDay = timeOfDay; }

    public List<Walker> walkers() { return walkers; }

    public List<Creature> creatures() { return creatures; }

    public List<Lure> lures() { return lures; }

    /** Everything in the air. */
    public List<Hurl> hurls() { return hurls; }

    public List<Spotlight> spotlights() { return spotlights; }

    /** This player's own satchel. */
    public Satchel satchel() { return satchel; }

    /** The party's shared book. */
    public FieldGuide guide() { return guide; }

    public Grove grove() { return grove; }

    public Cultivation crops() { return crops; }

    public Structure structure() { return structure; }

    /** Every fire and lantern the party has left standing. */
    public Lights lights() { return lights; }

    /** Every map anybody drew, and every board they went up on. */
    public Cartography maps() { return cartography; }

    /** Every satchel lying where somebody died. */
    public Spill spills() { return spills; }

    /** Whether a game of tag is on, and who is it. */
    public Tag tag() { return tag; }

    /** The Eye Spy board. */
    public Bounty bounties() { return bounties; }

    /**
     * The nearest other walker to a point, or {@code null} — <b>the compass
     * needle, and the whole of it.</b>
     *
     * <p>Nothing about this travels and nothing needs to: every walker's position
     * is already in every snapshot, so the machine that has to draw the needle is
     * the machine that already knows where everybody is. A host that answered
     * this question would be a host answering a question twenty times a second
     * that its client can answer for free — and a needle that lagged a snapshot
     * behind at a dead run is a needle pointing where somebody used to be.
     */
    public Walker nearestOther(int selfId, double x, double y) {
        Walker best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Walker walker : walkers) {
            if (walker.id() == selfId) continue;
            double dx = walker.x() - x, dy = walker.y() - y;
            double d = dx * dx + dy * dy;
            if (d < bestDistance) {
                bestDistance = d;
                best = walker;
            }
        }
        return best;
    }

    /** The last few things that happened. */
    public List<String> log() { return List.copyOf(log); }

    /** Whether a piece of ground litter has already been picked up. */
    public boolean litterTaken(long id) { return takenLitter.contains(id); }

    /**
     * Remember a piece as taken without waiting to be told.
     *
     * <p>What the walk calls the instant it asks the host to pick something up.
     * The host is still the only thing that decides whether the pick succeeded
     * — but the answer takes a round trip, and a piece of litter that stays on
     * the ground for two hundred milliseconds after you have taken it is two
     * hundred milliseconds of the world disagreeing with you. The next world
     * sync replaces this set wholesale, so a refused pick corrects itself.
     */
    public void noteLitterTaken(long id) { takenLitter.add(id); }

    /** This player, or {@code null} before the first snapshot. */
    public Walker self() {
        for (Walker walker : walkers) {
            if (walker.id() == selfId) return walker;
        }
        return null;
    }

    /** Everybody else. */
    public List<Walker> others() {
        List<Walker> out = new ArrayList<>();
        for (Walker walker : walkers) {
            if (walker.id() != selfId) out.add(walker);
        }
        return out;
    }

    /** The creature with this id, or {@code null}. */
    public Creature creature(long id) {
        for (Creature creature : creatures) {
            if (creature.id() == id) return creature;
        }
        return null;
    }

    /** Add a line to the party log. */
    public void say(String text) {
        if (text == null || text.isBlank()) return;
        log.add(text);
        while (log.size() > LOG_LIMIT) log.remove(0);
    }

    /** Let the outlines fade — the one thing a view does on its own. */
    public void ageSpotlights(double dt) {
        spotlights.replaceAll(light -> light.aged(dt));
        spotlights.removeIf(light -> !light.alive());
    }

    /**
     * Copy a locally-simulated game into this view — the solo path.
     *
     * <p>Clears and refills rather than diffing: the lists are a few dozen
     * entries and a frame has to be right rather than clever.
     */
    public void snapshot(WatchGame game, int selfId) {
        this.selfId = selfId;
        this.seed = game.config().seed();
        this.worldName = game.config().worldName();
        this.timeOfDay = game.clock().timeOfDay();

        walkers.clear();
        for (WatchPlayer player : game.players()) {
            walkers.add(new Walker(player.id(), player.name(), player.x(), player.y(),
                    player.z(), player.yaw(), player.pitch(), player.stillness(),
                    player.crouching(), player.submerged(), player.breath(),
                    player.boatId(), player.glassPower(), player.debugging(),
                    player.health(), player.respawns(), player.carriedLight(),
                    player.lampFuel()));
        }
        creatures.clear();
        for (Animal animal : game.animals()) {
            creatures.add(new Creature(animal.id(), animal.def(), animal.x(), animal.y(),
                    animal.z(), animal.yaw(), animal.state(), animal.phase(),
                    animal.trust(), animal.owner()));
        }
        lures.clear();
        lures.addAll(game.lures());
        lights.load(game.lights().toMap());
        hurls.clear();
        hurls.addAll(game.hurls());
        spotlights.clear();
        spotlights.addAll(game.spotlights());
        weather = game.weather();
        boats = game.boats();
        shops = game.shops();

        WatchPlayer me = game.player(selfId);
        if (me != null) {
            satchel.load(me.satchel().toMap());
            // The lens is not in the contents — see Satchel.load — so it is
            // copied across here, on the same line of thinking that copies
            // everything else the screen needs from the thing that owns it.
            satchel.setBottomless(me.debugging());
        }
        guide.load(game.guide().toMap());
        grove.load(game.grove().toMap());
        crops.load(game.crops().toMap());
        structure.load(game.structure().toMap());
        cartography.load(game.maps().toMap());
        spills.load(game.spills().toMap());
        // The two party games. Copied across on the same line of thinking as
        // everything else here — the screen reads this and never the game — even
        // though a solo walk can have neither a poll nor a round in it: the
        // alternative is a scene that asks the local game one question and the
        // view another, which is exactly the drift this class exists to prevent.
        tag.load(game.tag().toMap());
        bounties.load(game.bounties().toMap());
        takenLitter.clear();
        takenLitter.addAll(game.takenLitter());
    }

    // --- filling from the wire ---------------------------------------------------------

    /**
     * Replace the party from a snapshot's {@code players} array.
     *
     * <p>Ends by taking this player's own debug flag off their row and putting
     * it on the satchel, which is the online half of what {@link #snapshot}
     * does on the last line of the solo one: the flag arrives with the party
     * and the contents arrive in a {@code bag}, and the screen needs both to
     * agree before it can grey a recipe out.
     */
    public void loadWalkers(List<Map<String, Object>> rows) {
        walkers.clear();
        for (Map<String, Object> row : rows) {
            walkers.add(new Walker(WatchJson.integer(row, "id", 0),
                    WatchJson.str(row, "n", "?"), WatchJson.num(row, "x", 0),
                    WatchJson.num(row, "y", 0), WatchJson.num(row, "z", 0),
                    WatchJson.num(row, "yaw", 0), WatchJson.num(row, "p", 0),
                    WatchJson.num(row, "st", 1), WatchJson.bool(row, "c", false),
                    WatchJson.bool(row, "uw", false), WatchJson.num(row, "air", 1),
                    WatchJson.big(row, "boat", 0), WatchJson.num(row, "gl", 1),
                    WatchJson.bool(row, "dbg", false), WatchJson.num(row, "hp", 1),
                    WatchJson.integer(row, "rs", 0), WatchJson.str(row, "lt", null),
                    WatchJson.num(row, "lh", 0)));
        }
        Walker me = self();
        satchel.setBottomless(me != null && me.debug());
    }

    /** Replace the animals from a snapshot's {@code animals} array. */
    public void loadCreatures(List<Map<String, Object>> rows) {
        creatures.clear();
        for (Map<String, Object> row : rows) {
            AnimalDef def = AnimalRegistry.byKey(WatchJson.str(row, "sp", ""));
            if (def == null) continue;
            creatures.add(new Creature(WatchJson.big(row, "id", 0), def,
                    WatchJson.num(row, "x", 0), WatchJson.num(row, "y", 0),
                    WatchJson.num(row, "z", 0), WatchJson.num(row, "yaw", 0),
                    AnimState.of(WatchJson.str(row, "s", "idle"), AnimState.IDLE),
                    WatchJson.num(row, "ph", 0), WatchJson.num(row, "tr", 0),
                    WatchJson.str(row, "own", null)));
        }
    }

    /** Replace the taken-litter set from a world sync's {@code taken} array. */
    public void loadTakenLitter(List<Object> ids) {
        takenLitter.clear();
        for (Object id : ids) {
            if (id instanceof Number n) takenLitter.add(n.longValue());
        }
    }

    /**
     * Replace what is in the air from a snapshot's {@code hurls} array.
     *
     * <p>Cleared even when the array is absent, which is what makes a shard that
     * has landed disappear: the field is omitted from a snapshot with nothing
     * flying, and a client that only replaced on presence would keep drawing the
     * last shard of the last fight for ever.
     */
    public void loadHurls(List<Map<String, Object>> rows) {
        hurls.clear();
        for (Map<String, Object> row : rows) {
            Hurl hurl = Hurl.fromMap(row);
            if (hurl != null) hurls.add(hurl);
        }
    }

    /** Replace the feeders from a snapshot's {@code lures} array. */
    public void loadLures(List<Map<String, Object>> rows) {
        lures.clear();
        for (Map<String, Object> row : rows) {
            Lure lure = Lure.fromMap(row);
            if (lure != null) lures.add(lure);
        }
    }

    /**
     * Replace the fires and lanterns from a snapshot's {@code lights} array.
     *
     * <p>Replaced wholesale even when the array is empty, for the reason
     * {@link #loadHurls} is: a client that only replaced on presence would keep
     * drawing — and keep lighting the wood with — a fire that has burnt out.
     */
    public void loadLights(List<Map<String, Object>> rows) {
        lights.loadRows(rows);
    }
}
