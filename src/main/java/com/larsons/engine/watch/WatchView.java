package com.larsons.engine.watch;

import com.larsons.engine.watch.build.Structure;
import com.larsons.engine.watch.life.Animal;
import com.larsons.engine.watch.life.AnimState;
import com.larsons.engine.watch.life.AnimalDef;
import com.larsons.engine.watch.life.AnimalRegistry;
import com.larsons.engine.watch.world.Grove;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    /** One person, as drawn. */
    public record Walker(int id, String name, double x, double y, double z,
                         double yaw, double pitch, double stillness, boolean crouching) {}

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
    private final List<Spotlight> spotlights = new ArrayList<>();
    private final List<String> log = new ArrayList<>();

    private int selfId;
    private long seed;
    private String worldName = "";
    private double timeOfDay;
    private final Satchel satchel = new Satchel();
    private final FieldGuide guide = new FieldGuide();
    private final Grove grove = new Grove();
    private final Cultivation crops = new Cultivation();
    private final Structure structure = new Structure();

    /** How many lines of party chatter are kept. */
    private static final int LOG_LIMIT = 40;

    /** Which walker is this player. */
    public int selfId() { return selfId; }

    public void setSelfId(int id) { this.selfId = id; }

    /** The world's seed, so the client can generate the same terrain. */
    public long seed() { return seed; }

    public void setSeed(long seed) { this.seed = seed; }

    public String worldName() { return worldName; }

    public void setWorldName(String name) { this.worldName = name == null ? "" : name; }

    /** The host's time of day — the one everybody's sky is drawn from. */
    public double timeOfDay() { return timeOfDay; }

    public void setTimeOfDay(double timeOfDay) { this.timeOfDay = timeOfDay; }

    public List<Walker> walkers() { return walkers; }

    public List<Creature> creatures() { return creatures; }

    public List<Lure> lures() { return lures; }

    public List<Spotlight> spotlights() { return spotlights; }

    /** This player's own satchel. */
    public Satchel satchel() { return satchel; }

    /** The party's shared book. */
    public FieldGuide guide() { return guide; }

    public Grove grove() { return grove; }

    public Cultivation crops() { return crops; }

    public Structure structure() { return structure; }

    /** The last few things that happened. */
    public List<String> log() { return List.copyOf(log); }

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
                    player.crouching()));
        }
        creatures.clear();
        for (Animal animal : game.animals()) {
            creatures.add(new Creature(animal.id(), animal.def(), animal.x(), animal.y(),
                    animal.z(), animal.yaw(), animal.state(), animal.phase(),
                    animal.trust(), animal.owner()));
        }
        lures.clear();
        lures.addAll(game.lures());
        spotlights.clear();
        spotlights.addAll(game.spotlights());

        WatchPlayer me = game.player(selfId);
        if (me != null) {
            satchel.load(me.satchel().toMap());
        }
        guide.load(game.guide().toMap());
        grove.load(game.grove().toMap());
        crops.load(game.crops().toMap());
        structure.load(game.structure().toMap());
    }

    // --- filling from the wire ---------------------------------------------------------

    /** Replace the party from a snapshot's {@code players} array. */
    public void loadWalkers(List<Map<String, Object>> rows) {
        walkers.clear();
        for (Map<String, Object> row : rows) {
            walkers.add(new Walker(WatchJson.integer(row, "id", 0),
                    WatchJson.str(row, "n", "?"), WatchJson.num(row, "x", 0),
                    WatchJson.num(row, "y", 0), WatchJson.num(row, "z", 0),
                    WatchJson.num(row, "yaw", 0), WatchJson.num(row, "p", 0),
                    WatchJson.num(row, "st", 1), WatchJson.bool(row, "c", false)));
        }
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

    /** Replace the feeders from a snapshot's {@code lures} array. */
    public void loadLures(List<Map<String, Object>> rows) {
        lures.clear();
        for (Map<String, Object> row : rows) {
            Lure lure = Lure.fromMap(row);
            if (lure != null) lures.add(lure);
        }
    }
}
