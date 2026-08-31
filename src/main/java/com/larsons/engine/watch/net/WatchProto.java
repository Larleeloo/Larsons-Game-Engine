package com.larsons.engine.watch.net;

import com.larsons.engine.watch.Lure;
import com.larsons.engine.watch.Spotlight;
import com.larsons.engine.watch.WatchGame;
import com.larsons.engine.watch.WatchPlayer;
import com.larsons.engine.watch.life.Animal;
import com.larsons.engine.watch.life.Hurl;

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
 *                      "players":[…],"animals":[…],"lures":[…],"hurls":[…],
 *                      "lights":[…]}
 *
 *   client → server   {"t":"spot","a":animalId}     (0 = whatever I am looking at)
 *   server → all      {"t":"seen","a":id,"sp":"songbird_finch_banded","by":"Kara",
 *                      "new":true,"x":..,"y":..,"z":..}
 *
 *   client → server   {"t":"pick"} {"t":"log"} {"t":"harvest"} {"t":"cross"}
 *   client → server   {"t":"lure","f":"suet_cake"} {"t":"refill","id":..}
 *   client → server   {"t":"lamp"}       (light, douse or fill what is in hand)
 *   client → server   {"t":"putlight"}   (set a light down, or build a fire)
 *   client → server   {"t":"plant","s":"acorn"}
 *   client → server   {"t":"build","p":"platform","r":2,"tree":true}
 *   client → server   {"t":"craft","o":"suet_cake","st":"FIRE"}
 *   client → server   {"t":"cast"} {"t":"strike"}
 *   client → server   {"t":"glass","m":8}            (1 = put it away)
 *   client → server   {"t":"debug","c":"7799"}       (the host's walk only)
 *   client → server   {"t":"summon","sp":"wendigo_wendigo_hollow"}   (debug only)
 *   client → server   {"t":"buy","s":shopId,"k":"plank"}   {"t":"stamp","s":shopId}
 *
 *   client → server   {"t":"chart","r":512}           (a map of what I can see)
 *   client → server   {"t":"rename","c":mapId,"n":"North Wood"}
 *   client → server   {"t":"mark","c":mapId,"i":1,"p":[x,y,x,y…]}   (a pen stroke)
 *   client → server   {"t":"note","c":mapId,"i":1,"x":..,"y":..,"s":"otters"}
 *   client → server   {"t":"erase","c":mapId,"s":markId}
 *   client → server   {"t":"pin","c":mapId,"b":boardId}   (0 = take it back)
 *
 *   client → server   {"t":"gather"}                  (take a dropped satchel)
 *
 *   server → client   {"t":"bag","items":{…}}          (private, after any change)
 *   server → all      {"t":"world","grove":{…},"crops":{…},"built":{…},"maps":{…},
 *                      "spills":{…}}
 *   server → all      {"t":"guide","entries":[…],"pets":[…],"earned":…,"tally":[…]}
 *   both              info / error / ping / pong
 * </pre>
 *
 * <p><b>Nothing about a trading post travels.</b> Where one stands, who keeps
 * it, what is on its shelves and what they charge are all functions of the seed
 * — see {@link com.larsons.engine.watch.Shops} — so a client works all of that
 * out for itself the way it works out the trails and the boats. The two verbs
 * above carry an <em>intention</em>, and the answer comes back as the two things
 * that genuinely changed: a satchel and a ledger.
 *
 * <p><b>And no map is ever a picture.</b> The same argument reaches further for
 * maps than for anything else here: a map's paper is a square of ground, the
 * ground is a function of the seed, so a map on the wire is a centre, a radius,
 * a name and whatever somebody drew on it — see
 * {@link com.larsons.engine.watch.Chart}. Both ends paint the identical picture
 * from the seed they already share, which is also why two players' copies of one
 * map agree pixel for pixel.
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
     *
     * <p><b>A respawn travels here too</b>, and as a counter rather than as a
     * message: {@code WatchPlayer.toSnapshot} puts {@code rs} on the row of
     * anybody who has been killed, and a client that sees its own number go up
     * teleports to the position in the same snapshot. See
     * {@link WatchPlayer#respawns()} for why that is better than telling them
     * once and hoping.
     */
    public static Map<String, Object> state(long tick, double timeOfDay,
                                            List<WatchPlayer> players,
                                            List<Animal> animals, List<Lure> lures,
                                            List<Hurl> hurls, List<Object> lights,
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

        // What is in the air. On the snapshot rather than on an event, because
        // a shard is a moving thing rather than a thing that happened: a client
        // told only "one was thrown, here, this fast" would have to simulate it,
        // and two clients simulating the same shard is two shards. Omitted
        // entirely when nothing is flying, which is nearly every snapshot in
        // nearly every walk.
        if (hurls != null && !hurls.isEmpty()) {
            List<Object> hurlRows = new ArrayList<>();
            for (Hurl hurl : hurls) hurlRows.add(hurl.toMap());
            m.put("hurls", hurlRows);
        }

        List<Object> lureRows = new ArrayList<>();
        for (Lure lure : lures) lureRows.add(lure.toMap());
        m.put("lures", lureRows);

        // The fires and lanterns, beside the feeders and for the feeders'
        // reason: they are world state that <em>changes on its own</em>. A
        // built piece never does, so it can ride the five-second world sync;
        // a fire goes out, and a party who watched their camp go dark five
        // seconds after it happened would learn to distrust the picture. A row
        // is six numbers and a key, and a well-lit camp has half a dozen.
        m.put("lights", lights == null ? new ArrayList<>() : lights);
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

    /**
     * A code somebody typed on the number keys.
     *
     * <p>The code travels rather than the conclusion: a client that sent
     * {@code {"t":"debug","on":true}} would be a client that decides whether it
     * is allowed unlimited items, which is not a thing a client decides. See
     * {@code WatchGame.debug}, which checks the digits <em>and</em> whose walk
     * it is.
     */
    public static Map<String, Object> debug(String code) {
        Map<String, Object> m = msg("debug");
        m.put("c", code);
        return m;
    }

    /**
     * Put an animal on the ground in front of me — <b>debug mode only.</b>
     *
     * <p>The species travels rather than "the next mutant", for
     * {@link #debug}'s reason turned around: which of the three a key cycles to
     * is a fact about the keyboard in front of one person and no business of the
     * host's, and a host that had to keep its own idea of where each client had
     * got to in the cycle would be keeping state for nothing. The client names
     * what it wants; the host decides whether that client may have it. See
     * {@code WatchGame.summon}, which refuses anybody not in debug mode.
     */
    public static Map<String, Object> summon(String species) {
        Map<String, Object> m = msg("summon");
        m.put("sp", species);
        return m;
    }

    /**
     * A spyglass going up or coming down.
     *
     * <p>Its own message rather than a field on {@code move}, because it
     * changes perhaps twice a minute and {@code move} goes out twenty times a
     * second. The host answers by refusing it outright when the sender has no
     * glass — see {@code WatchGame.glass}.
     *
     * @param power the magnification, or {@code 1} to put it away
     */
    public static Map<String, Object> glass(double power) {
        Map<String, Object> m = msg("glass");
        m.put("m", round(power));
        return m;
    }

    /**
     * Buy one line off a keeper's shelf.
     *
     * <p>The shop's id goes with it, and the host checks it against the post the
     * sender is <em>standing at</em> rather than trusting it — see
     * {@code WatchGame.buy}. It is here to disambiguate, not to address: without
     * it a client whose idea of which counter it was at differed from the
     * host's would buy the right thing at the wrong prices.
     */
    public static Map<String, Object> buy(long shopId, String item) {
        Map<String, Object> m = msg("buy");
        m.put("s", shopId);
        m.put("k", item);
        return m;
    }

    /** Ask the keeper to stamp a fresh page in the guide. */
    public static Map<String, Object> stamp(long shopId) {
        Map<String, Object> m = msg("stamp");
        m.put("s", shopId);
        return m;
    }

    // --- maps --------------------------------------------------------------------
    //
    // Six verbs, and between them they carry no pictures. A map's paper is a
    // function of the seed, which both ends already have, so what travels is
    // where a map is and what somebody wrote on it. See
    // com.larsons.engine.watch.Chart.

    /**
     * Draw a map here.
     *
     * <p><b>The reach is the one number a client is the authority on.</b> A map
     * spans the render distance of the machine that drew it, and how far that
     * machine can see is a fact about its graphics card that no host can
     * discover. So it is sent, and the host clamps it — see
     * {@code WatchGame.drawMap}, which rounds it up
     * {@code Chart.RADII} and refuses anybody not in debug mode outright.
     *
     * @param reach how far this machine can see, in metres
     */
    public static Map<String, Object> chart(double reach) {
        Map<String, Object> m = msg("chart");
        m.put("r", round(reach));
        return m;
    }

    /** Rename a map. */
    public static Map<String, Object> renameChart(long chartId, String name) {
        Map<String, Object> m = msg("rename");
        m.put("c", chartId);
        m.put("n", name);
        return m;
    }

    /**
     * One pen stroke, in world metres, as a flat {@code x, y, x, y…} array.
     *
     * <p>Flat for {@code Chart.Stroke.toMap}'s reason: a scribble is the only
     * thing about a map with more than a handful of numbers in it, and a
     * {@code {"x":…,"y":…}} per point triples what one costs for nothing
     * anybody reads. The whole stroke goes at once, when the pen comes up,
     * rather than a point per frame — a stroke is one thing a person did.
     */
    public static Map<String, Object> mark(long chartId, int ink, double[] xs,
                                           double[] ys) {
        Map<String, Object> m = msg("mark");
        m.put("c", chartId);
        m.put("i", ink);
        int n = Math.min(xs.length, ys.length);
        List<Object> flat = new ArrayList<>(n * 2);
        for (int i = 0; i < n; i++) {
            flat.add(round(xs[i]));
            flat.add(round(ys[i]));
        }
        m.put("p", flat);
        return m;
    }

    /** A few words written on a map at a point. */
    public static Map<String, Object> note(long chartId, int ink, double x, double y,
                                           String text) {
        Map<String, Object> m = msg("note");
        m.put("c", chartId);
        m.put("i", ink);
        m.put("x", round(x));
        m.put("y", round(y));
        m.put("s", text);
        return m;
    }

    /** Rub one mark off a map. Strokes and notes share an id space. */
    public static Map<String, Object> erase(long chartId, long markId) {
        Map<String, Object> m = msg("erase");
        m.put("c", chartId);
        m.put("s", markId);
        return m;
    }

    /**
     * Pin a map to the board in front of you, or pass {@code 0} to take it back.
     *
     * <p>The board's id rides along for the reason a shop's does: to
     * disambiguate between two boards, not to address one from across the
     * world. {@code WatchGame.pinMap} checks it against the board the sender is
     * actually standing at.
     */
    public static Map<String, Object> pin(long chartId, long boardId) {
        Map<String, Object> m = msg("pin");
        m.put("c", chartId);
        m.put("b", boardId);
        return m;
    }

    // --- pushed state ------------------------------------------------------------

    public static Map<String, Object> bag(Map<String, Object> items) {
        Map<String, Object> m = msg("bag");
        m.put("items", items);
        return m;
    }

    /**
     * The slow half of the world.
     *
     * @param taken the pieces of ground litter somebody has already picked up.
     *              Like the boats, everything else about the litter is a
     *              function of the seed and never travels; unlike the boats,
     *              what has been taken cannot be derived from anything, and a
     *              client that did not know would keep drawing a branch that is
     *              no longer there. See {@code WatchGame.takenLitter}.
     */
    public static Map<String, Object> world(Map<String, Object> grove,
                                            Map<String, Object> crops,
                                            Map<String, Object> built,
                                            Map<String, Object> maps,
                                            Map<String, Object> boats,
                                            Map<String, Object> spills,
                                            List<Long> taken) {
        Map<String, Object> m = msg("world");
        m.put("grove", grove);
        m.put("crops", crops);
        m.put("built", built);
        // The dropped satchels. On the slow channel with everything else the
        // host owns, and not on the snapshot: a heap appears when somebody dies
        // and disappears when somebody picks it up, which is a handful of times
        // a session — and the world sync goes out the instant either happens,
        // so it is never five seconds stale in practice. See
        // com.larsons.engine.watch.Spill.
        if (spills != null) m.put("spills", spills);
        // The maps ride with the buildings rather than on a channel of their
        // own: they change at the same rate and for the same reason — somebody
        // did something to the world — and a stroke of a pen is a few hundred
        // bytes beside a grove.
        if (maps != null) m.put("maps", maps);
        // Only the boats somebody has moved: every other boat in the world is a
        // function of the seed, which both ends already have.
        if (boats != null) m.put("boats", boats);
        if (taken != null && !taken.isEmpty()) m.put("taken", new ArrayList<Object>(taken));
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
