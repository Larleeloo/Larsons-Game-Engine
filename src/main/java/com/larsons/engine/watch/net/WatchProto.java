package com.larsons.engine.watch.net;

import com.larsons.engine.watch.Lure;
import com.larsons.engine.watch.Spotlight;
import com.larsons.engine.watch.WatchGame;
import com.larsons.engine.watch.WatchPlayer;
import com.larsons.engine.watch.life.Animal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The wire protocol for walking a world together.
 *
 * <p>Newline-delimited compact JSON over TCP, framed by the engine's own
 * {@link com.larsons.engine.net.Protocol} — the same arrangement the world game
 * and the auto battler use, for the same reasons: nothing outside the JDK, and
 * a conversation you can read with {@code telnet}. The vocabulary and the
 * version are this game's own.
 *
 * <pre>
 *   client → server   {"t":"join","v":1,"name":"Larson"}
 *   server → client   {"t":"welcome","id":3,"seed":...,"world":"...","tick":20,"max":8}
 *   server → all      {"t":"party","host":1,"players":[{"id":1,"n":"Larson"}…]}
 *
 *   client → server   {"t":"move","x":..,"y":..,"z":..,"yaw":..,"p":..,"c":false}
 *   server → all      {"t":"state","tick":42,"time":0.61,
 *                      "players":[…],"animals":[…],"lures":[…]}
 *
 *   client → server   {"t":"spot","a":animalId}     (0 = whatever I am looking at)
 *   server → all      {"t":"seen","a":id,"sp":"songbird_finch_banded","by":"Kara",
 *                      "new":true,"x":..,"y":..,"z":..}
 *
 *   client → server   {"t":"pick"} {"t":"log"} {"t":"harvest"} {"t":"cross"}
 *   client → server   {"t":"lure","f":"suet_cake"} {"t":"refill","id":..}
 *   client → server   {"t":"plant","s":"acorn"}
 *   client → server   {"t":"build","p":"platform","r":2,"tree":true}
 *   client → server   {"t":"craft","o":"suet_cake","st":"FIRE"}
 *   client → server   {"t":"cast"} {"t":"strike"}
 *
 *   server → client   {"t":"bag","items":{…}}          (private, after any change)
 *   server → all      {"t":"world","grove":{…},"crops":{…},"built":{…}}
 *   server → all      {"t":"guide","entries":[…],"pets":[…]}
 *   both              info / error / ping / pong
 * </pre>
 *
 * <p><b>The server is authoritative about everything.</b> A client sends where
 * it thinks it is and what it would like to do; the server decides. That is
 * ordinary for a networked game and it matters more than usual here, because
 * the one thing a player could gain by lying — being able to approach a wary
 * animal — <em>is</em> the game. See {@link WatchPlayer#moveTo}, which derives
 * a player's speed from the positions they send rather than believing a flag.
 */
public final class WatchProto {

    /** Protocol version; bumped on incompatible changes. */
    public static final int VERSION = 1;

    /**
     * Default port. The world server is 7777, the auto battler 7788 and the
     * deckbuilder 7790; this is the next free one along.
     */
    public static final int DEFAULT_PORT = 7799;

    /** How many can walk one world — the game's own figure. */
    public static final int MAX_PLAYERS = WatchGame.MAX_PLAYERS;

    /** Ticks a second the server runs at. */
    public static final int TICK_RATE = 20;

    /** How often a full world sync goes out, in ticks. */
    public static final int WORLD_SYNC_TICKS = TICK_RATE * 5;

    private WatchProto() {}

    /** The message type, or {@code ""}. */
    public static String type(Map<String, Object> message) {
        return message != null && message.get("t") instanceof String s ? s : "";
    }

    // --- handshake ---------------------------------------------------------------

    public static Map<String, Object> join(String name) {
        Map<String, Object> m = msg("join");
        m.put("v", VERSION);
        m.put("name", name);
        return m;
    }

    public static Map<String, Object> welcome(int id, long seed, String world,
                                              int tickRate, int max) {
        Map<String, Object> m = msg("welcome");
        m.put("id", id);
        m.put("seed", seed);
        m.put("world", world);
        m.put("tick", tickRate);
        m.put("max", max);
        return m;
    }

    public static Map<String, Object> party(int hostId, List<WatchPlayer> players) {
        Map<String, Object> m = msg("party");
        m.put("host", hostId);
        List<Object> rows = new ArrayList<>();
        for (WatchPlayer p : players) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", p.id());
            row.put("n", p.name());
            rows.add(row);
        }
        m.put("players", rows);
        return m;
    }

    public static Map<String, Object> error(String reason) {
        Map<String, Object> m = msg("error");
        m.put("msg", reason);
        return m;
    }

    public static Map<String, Object> info(String text) {
        Map<String, Object> m = msg("info");
        m.put("msg", text);
        return m;
    }

    // --- movement and state ------------------------------------------------------

    public static Map<String, Object> move(double x, double y, double z, double yaw,
                                           double pitch, boolean crouching) {
        Map<String, Object> m = msg("move");
        m.put("x", round(x));
        m.put("y", round(y));
        m.put("z", round(z));
        m.put("yaw", round(yaw));
        m.put("p", round(pitch));
        if (crouching) m.put("c", true);
        return m;
    }

    /**
     * A snapshot.
     *
     * <p>Positions are rounded to the centimetre before they go out. A double
     * printed in full is about seventeen characters and there are eight of them
     * per animal; at a hundred and fifty animals and twenty ticks a second that
     * is the difference between a snapshot that fits comfortably and one that
     * does not.
     */
    public static Map<String, Object> state(long tick, double timeOfDay,
                                            List<WatchPlayer> players,
                                            List<Animal> animals, List<Lure> lures,
                                            Map<String, Object> sky) {
        Map<String, Object> m = msg("state");
        m.put("tick", tick);
        m.put("time", round(timeOfDay));
        // The weather rides along with the clock, and for the same reason: one
        // party, one sky. Four short fields per snapshot.
        if (sky != null) m.put("sky", sky);
        List<Object> playerRows = new ArrayList<>();
        for (WatchPlayer p : players) playerRows.add(p.toSnapshot());
        m.put("players", playerRows);

        List<Object> animalRows = new ArrayList<>();
        for (Animal a : animals) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", a.id());
            row.put("sp", a.def().key());
            row.put("x", round(a.x()));
            row.put("y", round(a.y()));
            row.put("z", round(a.z()));
            row.put("yaw", round(a.yaw()));
            row.put("s", a.state().key());
            row.put("ph", round(a.phase()));
            if (a.trust() > 0) row.put("tr", round(a.trust()));
            if (a.owner() != null) row.put("own", a.owner());
            animalRows.add(row);
        }
        m.put("animals", animalRows);

        List<Object> lureRows = new ArrayList<>();
        for (Lure lure : lures) lureRows.add(lure.toMap());
        m.put("lures", lureRows);
        return m;
    }

    // --- actions -----------------------------------------------------------------

    public static Map<String, Object> spot(long animalId) {
        Map<String, Object> m = msg("spot");
        m.put("a", animalId);
        return m;
    }

    public static Map<String, Object> seen(Spotlight light) {
        Map<String, Object> m = msg("seen");
        m.putAll(light.toMap());
        return m;
    }

    /** The no-argument actions: pick, log, harvest, cross, cast, strike. */
    public static Map<String, Object> action(String kind) {
        return msg(kind);
    }

    public static Map<String, Object> placeLure(String food) {
        Map<String, Object> m = msg("lure");
        m.put("f", food);
        return m;
    }

    public static Map<String, Object> refill(long lureId) {
        Map<String, Object> m = msg("refill");
        m.put("id", lureId);
        return m;
    }

    public static Map<String, Object> plant(String seed) {
        Map<String, Object> m = msg("plant");
        m.put("s", seed);
        return m;
    }

    public static Map<String, Object> build(String piece, int turn, boolean inTree) {
        Map<String, Object> m = msg("build");
        m.put("p", piece);
        m.put("r", turn);
        if (inTree) m.put("tree", true);
        return m;
    }

    public static Map<String, Object> craft(String output, String station) {
        Map<String, Object> m = msg("craft");
        m.put("o", output);
        m.put("st", station);
        return m;
    }

    // --- pushed state ------------------------------------------------------------

    public static Map<String, Object> bag(Map<String, Object> items) {
        Map<String, Object> m = msg("bag");
        m.put("items", items);
        return m;
    }

    public static Map<String, Object> world(Map<String, Object> grove,
                                            Map<String, Object> crops,
                                            Map<String, Object> built,
                                            Map<String, Object> boats) {
        Map<String, Object> m = msg("world");
        m.put("grove", grove);
        m.put("crops", crops);
        m.put("built", built);
        // Only the boats somebody has moved: every other boat in the world is a
        // function of the seed, which both ends already have.
        if (boats != null) m.put("boats", boats);
        return m;
    }

    public static Map<String, Object> guide(Map<String, Object> guide) {
        Map<String, Object> m = msg("guide");
        m.putAll(guide);
        return m;
    }

    // --- keepalive ---------------------------------------------------------------

    public static Map<String, Object> ping(long stamp) {
        Map<String, Object> m = msg("ping");
        m.put("p", stamp);
        return m;
    }

    public static Map<String, Object> pong(long stamp) {
        Map<String, Object> m = msg("pong");
        m.put("p", stamp);
        return m;
    }

    private static Map<String, Object> msg(String type) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("t", type);
        return m;
    }

    /** A coordinate, to the centimetre. See {@link #state}. */
    private static double round(double v) {
        return Math.round(v * 100) / 100.0;
    }

    /** A name, trimmed and capped, so a joining client cannot send a novel. */
    public static String sanitizeName(String name) {
        if (name == null || name.isBlank()) return "Walker";
        String trimmed = name.trim();
        return trimmed.length() > 24 ? trimmed.substring(0, 24) : trimmed;
    }
}
