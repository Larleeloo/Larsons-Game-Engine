package com.larsons.engine.watch.net;

import com.larsons.engine.net.Protocol;
import com.larsons.engine.watch.Recipes;
import com.larsons.engine.watch.Spotlight;
import com.larsons.engine.watch.WatchGame;
import com.larsons.engine.watch.WatchJson;
import com.larsons.engine.watch.WatchPlayer;
import com.larsons.engine.watch.build.BuildPiece;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The authoritative host for a shared walk.
 *
 * <p>Built to the same plan as the engine's other servers
 * ({@link com.larsons.engine.net.GameServer},
 * {@code com.larsons.engine.autobattler.AutoServer}): bind a port, accept up to
 * {@link WatchProto#MAX_PLAYERS} connections, and keep <b>all</b> game state in
 * one {@link WatchGame} owned by the tick thread. Connections do two things and
 * no more — decode a line onto a queue, and write whatever has been queued for
 * them — so a player on a bad connection can slow down their own snapshots and
 * nobody else's simulation.
 *
 * <p><b>What actually goes over the wire, and how often.</b>
 *
 * <ul>
 *   <li><b>Snapshots</b>, {@value WatchProto#TICK_RATE} a second: the party's
 *       positions, every animal near anybody, and the feeders. This is the bulk
 *       of the traffic and it is why positions are rounded to the centimetre
 *       before they are printed.</li>
 *   <li><b>The world</b> — the grove, the crops, the buildings — every
 *       {@value WatchProto#WORLD_SYNC_TICKS} ticks, because a tree that grew a
 *       stage or a platform somebody nailed up is not urgent and there is no
 *       point sending an unchanged copy twenty times a second.</li>
 *   <li><b>Events</b>, when they happen: a sighting, a discovery, somebody
 *       joining, a tame animal changing hands.</li>
 *   <li><b>A satchel</b>, privately, to the one player whose satchel changed.</li>
 * </ul>
 */
public final class WatchServer implements WatchGame.Sink {

    /** How long a silent connection is given before it is dropped. */
    private static final long TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(20);

    /** How many messages may pile up for one slow client before it is dropped. */
    private static final int OUTBOX_CAPACITY = 512;

    private final WatchGame game;

    private ServerSocket serverSocket;
    private Thread acceptThread;
    private Thread tickThread;
    private volatile boolean running;
    private volatile long tick;

    private final CopyOnWriteArrayList<Connection> connections = new CopyOnWriteArrayList<>();
    private final ConcurrentLinkedQueue<Connection> pendingJoins = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Request> pendingRequests = new ConcurrentLinkedQueue<>();
    private final AtomicInteger nextPlayerId = new AtomicInteger(1);

    /**
     * The respawn count last seen for each player, so a death can be noticed.
     *
     * <p>Tick-thread state, like everything else here that is not a queue. See
     * {@link #announceDeaths}.
     */
    private final Map<Integer, Integer> respawnsSeen = new java.util.HashMap<>();

    private record Request(Connection conn, Map<String, Object> message) {}

    public WatchServer(WatchGame.Config config) {
        this.game = new WatchGame(config);
        this.game.setSink(this);
    }

    /** The world this server is hosting. Owned by the tick thread once started. */
    public WatchGame game() { return game; }

    /** Bind and start serving. Port {@code 0} picks a free one. */
    public synchronized void start(int port) throws IOException {
        if (running) throw new IllegalStateException("Server already running");
        serverSocket = new ServerSocket();
        serverSocket.bind(new InetSocketAddress(port));
        running = true;
        acceptThread = daemon("watch-accept", this::acceptLoop);
        tickThread = daemon("watch-tick", this::tickLoop);
        acceptThread.start();
        tickThread.start();
        log("Hosting \"" + game.config().worldName() + "\" on port " + port());
    }

    public int port() { return serverSocket == null ? -1 : serverSocket.getLocalPort(); }

    public boolean isRunning() { return running; }

    /** How many people are on the walk. */
    public int playerCount() {
        int n = 0;
        for (Connection c : connections) {
            if (c.playerId > 0 && !c.closed) n++;
        }
        return n;
    }

    public synchronized void stop() {
        if (!running) return;
        running = false;
        closeQuietly(serverSocket);
        for (Connection c : connections) c.markClosed();
        Connection pending;
        while ((pending = pendingJoins.poll()) != null) pending.markClosed();
        joinQuietly(acceptThread);
        joinQuietly(tickThread);
        log("Server stopped");
    }

    // --- Sink -----------------------------------------------------------------------

    @Override
    public void toPlayer(int playerId, Map<String, Object> message) {
        String line = Protocol.encode(message);
        for (Connection c : connections) {
            if (c.playerId == playerId && !c.closed) {
                c.send(line);
                return;
            }
        }
    }

    @Override
    public void toAll(Map<String, Object> message) {
        String line = Protocol.encode(message);
        for (Connection c : connections) {
            if (c.playerId > 0 && !c.closed) c.send(line);
        }
    }

    @Override
    public void info(String text) {
        toAll(WatchProto.info(text));
    }

    // --- accept ---------------------------------------------------------------------

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                socket.setTcpNoDelay(true);
                Connection conn = new Connection(socket);
                connections.add(conn);
                conn.start();
            } catch (IOException e) {
                if (running) log("accept failed: " + e.getMessage());
            }
        }
    }

    // --- tick -----------------------------------------------------------------------

    private void tickLoop() {
        final long step = 1_000_000_000L / WatchProto.TICK_RATE;
        long next = System.nanoTime();
        while (running) {
            long now = System.nanoTime();
            if (now < next) {
                parkUntil(next);
                continue;
            }
            next += step;
            // A host that was paused (a laptop lid) must not try to catch up by
            // running four hundred ticks at once.
            if (next < now - step * 10) next = now + step;

            try {
                admitJoins();
                drainRequests();
                dropSilent(now);
                game.tick(1.0 / WatchProto.TICK_RATE);
                announceDeaths();
                broadcastState();
                tick++;
            } catch (RuntimeException e) {
                // One bad tick must not take the world down with it: a party of
                // eight losing their afternoon to a null pointer in one
                // animal's AI is a worse outcome than a skipped frame.
                log("tick failed: " + e);
            }
        }
    }

    private void admitJoins() {
        Connection conn;
        while ((conn = pendingJoins.poll()) != null) {
            if (conn.closed) continue;
            if (playerCount() >= game.config().maxPlayers()) {
                conn.closeAfterFlush(Protocol.encode(WatchProto.error(
                        "This walk is full (" + game.config().maxPlayers() + " players)")));
                continue;
            }
            int id = nextPlayerId.getAndIncrement();
            conn.playerId = id;
            WatchPlayer player = game.join(id, conn.name);
            if (player == null) {
                // The game keeps the same cap and can refuse for reasons this
                // loop cannot see. Two checks that could disagree must not end
                // in a null dereference on the tick thread.
                conn.playerId = 0;
                conn.closeAfterFlush(Protocol.encode(WatchProto.error(
                        "This walk is full (" + game.config().maxPlayers() + " players)")));
                continue;
            }
            conn.send(Protocol.encode(WatchProto.welcome(id, game.config().seed(),
                    game.config().worldName(), WatchProto.TICK_RATE,
                    game.config().maxPlayers())));
            conn.send(Protocol.encode(WatchProto.bag(player.satchel().toMap())));
            conn.send(Protocol.encode(WatchProto.guide(game.guide().toMap())));
            sendWorld();
            toAll(WatchProto.party(hostId(), game.players()));
        }
    }

    /**
     * Push the consequences of anybody having been killed during that tick.
     *
     * <p>A death happens deep inside the simulation — an animal decides it is
     * close enough, the game takes the last of somebody's health — and two
     * things it changes are on channels that do not go out every tick: the
     * victim's satchel is now empty (a private {@code bag}) and their heap is
     * now on the ground (the world sync). Neither would otherwise reach anybody
     * for up to five seconds, which is five seconds of a client drawing a bag
     * its owner no longer has and no satchel where they fell.
     *
     * <p>Noticed rather than announced, by watching the respawn counter each
     * player already publishes. The alternative is a callback out of the game
     * into the server, and {@link WatchGame} deliberately reports what happened
     * through its {@link WatchGame.Sink} and its state rather than by knowing
     * who is listening.
     */
    private void announceDeaths() {
        boolean died = false;
        for (WatchPlayer player : game.players()) {
            int seen = respawnsSeen.getOrDefault(player.id(), -1);
            if (seen == player.respawns()) continue;
            respawnsSeen.put(player.id(), player.respawns());
            // A player joining for the first time arrives with whatever count
            // their save left them; that is not a death that happened here.
            if (seen < 0) continue;
            died = true;
            bagChanged(player.id(), null);
        }
        if (died) sendWorld();
    }

    /** The first player to have joined; whoever is "the host" for the lobby's list. */
    private int hostId() {
        for (WatchPlayer p : game.players()) return p.id();
        return 0;
    }

    private void dropSilent(long now) {
        for (Connection c : connections) {
            if (c.closed || now - c.lastHeard < TIMEOUT_NANOS) continue;
            c.markClosed();
        }
        connections.removeIf(c -> {
            if (!c.closed) return false;
            if (c.playerId > 0) {
                game.leave(c.playerId);
                c.playerId = 0;
                toAll(WatchProto.party(hostId(), game.players()));
            }
            return true;
        });
    }

    private void broadcastState() {
        toAll(WatchProto.state(tick, game.clock().timeOfDay(), game.players(),
                game.animals(), game.lures(), game.hurls(), game.weather().toMap()));
        if (tick % WatchProto.WORLD_SYNC_TICKS == 0) sendWorld();
    }

    private void sendWorld() {
        toAll(WatchProto.world(game.grove().toMap(), game.crops().toMap(),
                game.structure().toMap(), game.maps().toMap(), game.boats().toMap(),
                game.spills().toMap(), game.takenLitter()));
    }

    // --- requests -------------------------------------------------------------------

    private void drainRequests() {
        Request request;
        while ((request = pendingRequests.poll()) != null) {
            try {
                handle(request.conn(), request.message());
            } catch (RuntimeException e) {
                log("bad request from " + request.conn().name + ": " + e);
            }
        }
    }

    /**
     * One client request.
     *
     * <p>Everything a player can do goes through here, and everything is
     * checked against the server's own state — see {@link WatchGame}, which is
     * where all of the rules live. This method is only routing.
     */
    private void handle(Connection conn, Map<String, Object> message) {
        int id = conn.playerId;
        if (id <= 0) return;
        String type = WatchProto.type(message);
        switch (type) {
            case "move" -> game.move(id, WatchJson.num(message, "x", 0),
                    WatchJson.num(message, "y", 0), WatchJson.num(message, "z", 0),
                    WatchJson.num(message, "yaw", 0), WatchJson.num(message, "p", 0),
                    WatchJson.bool(message, "c", false), 1.0 / WatchProto.TICK_RATE);

            case "spot" -> {
                Spotlight light = game.spot(id, WatchJson.big(message, "a", 0));
                if (light != null) {
                    toAll(WatchProto.seen(light));
                    if (light.discovery()) announceEntry(light.species());
                }
            }

            case "pick" -> {
                WatchGame.Pickable target = game.pickTarget(id);
                String got = game.pick(id);
                if (got != null) {
                    bagChanged(id, "Picked " + nameOf(got));
                    // `pick` falls through to the floor when nothing is growing
                    // in reach, so a plain pick takes litter too — and every
                    // client is drawing that piece until it is told otherwise.
                    if (target == null
                            || target.kind() == WatchGame.Pickable.Kind.GROUND) {
                        sendWorld();
                    }
                }
            }

            case "use" -> {
                // Ask what is in reach before acting, so we know afterwards
                // whether the world changed or only the satchel. A world sync
                // is the whole grove, every crop, every built piece and every
                // moved boat to every client — worth sending when a crop has
                // been pulled or a boat taken, and absurd to send because
                // somebody picked a berry, which is most presses of this key.
                WatchGame.Pickable target = game.pickTarget(id);
                String line = game.use(id);
                if (line != null) {
                    bagChanged(id, line);
                    if (target != null && (target.kind() == WatchGame.Pickable.Kind.CROP
                            || target.kind() == WatchGame.Pickable.Kind.BOAT
                            // Something taken off the floor has to go out too:
                            // every client is drawing it, and none of them can
                            // work out on its own that it is gone.
                            || target.kind() == WatchGame.Pickable.Kind.GROUND
                            // …and a dropped satchel most of all: it is the
                            // largest thing on the ground and somebody may be
                            // walking across a valley to it.
                            || target.kind() == WatchGame.Pickable.Kind.SATCHEL)) {
                        sendWorld();
                    }
                }
            }

            case "log" -> {
                String got = game.turnOverLog(id);
                if (got != null) bagChanged(id, "Found " + nameOf(got));
            }

            case "gather" -> {
                // A whole dropped satchel, taken in one gesture. Both channels
                // have to go out: the taker's bag is fuller and the heap is
                // gone from everybody's ground.
                String line = game.gather(id);
                if (line != null) {
                    bagChanged(id, line);
                    sendWorld();
                }
            }

            case "lure" -> {
                if (game.placeLure(id, WatchJson.str(message, "f", "")) != null) {
                    bagChanged(id, null);
                }
            }

            case "refill" -> {
                if (game.refillLure(id, WatchJson.big(message, "id", 0))) {
                    bagChanged(id, "Topped up the feeder");
                }
            }

            case "takelure" -> {
                if (game.removeLure(id, WatchJson.big(message, "id", 0))) {
                    bagChanged(id, null);
                }
            }

            case "plant" -> {
                String line = game.plant(id, WatchJson.str(message, "s", ""));
                if (line != null) {
                    bagChanged(id, line);
                    sendWorld();
                }
            }

            case "cross" -> {
                var cross = game.pollinate(id);
                if (cross != null) {
                    game.plantCross(id, cross);
                    bagChanged(id, cross.describe());
                    sendWorld();
                }
            }

            case "harvest" -> {
                String got = game.harvest(id);
                if (got != null) {
                    bagChanged(id, "Harvested " + got);
                    sendWorld();
                }
            }

            case "build" -> {
                BuildPiece piece = BuildPiece.of(WatchJson.str(message, "p", ""));
                if (game.build(id, piece, WatchJson.integer(message, "r", 0),
                        WatchJson.bool(message, "tree", false)) != null) {
                    bagChanged(id, "Built a " + piece.displayName());
                    sendWorld();
                }
            }

            case "craft" -> {
                Recipes.Recipe recipe = Recipes.making(WatchJson.str(message, "o", ""));
                Recipes.Station station = stationOf(WatchJson.str(message, "st", "HANDS"));
                if (game.craft(id, recipe, station)) {
                    bagChanged(id, "Made " + recipe.name());
                }
            }

            case "boat" -> {
                String line = game.useBoat(id);
                if (line != null) {
                    conn.send(Protocol.encode(WatchProto.info(line)));
                    // Where a boat is left is world state, not a snapshot
                    // field: it is set once when somebody steps out and read
                    // for ever after by everyone who walks that shore.
                    sendWorld();
                }
            }

            case "glass" -> game.glass(id, WatchJson.num(message, "m", 1));

            case "debug" -> {
                // The satchel it reports afterwards is the same satchel; what
                // changed is what the client is allowed to think it can afford,
                // and that rides on the next snapshot. Sending the bag anyway
                // means the cooking screen redraws on the very next frame
                // rather than on the next thing that happens to be picked up.
                game.debug(id, WatchJson.str(message, "c", ""));
                bagChanged(id, null);
            }

            case "summon" -> {
                // Nothing is sent back and nothing needs to be: an animal is a
                // snapshot field, so a summoned one is on every client's screen
                // on the next tick like any other. The host refuses anybody not
                // in debug mode — see WatchGame.summon — and a refusal is
                // silence, which is the same answer every other debug-gated verb
                // gives a client that should not have asked.
                game.summon(id, WatchJson.str(message, "sp", ""));
            }

            case "cast" -> game.castRod(id);

            case "strike" -> {
                var fish = game.strike(id);
                if (fish != null) {
                    bagChanged(id, "Landed a " + fish.name());
                    announceEntry(fish.key());
                    // A fish is a sighting, so it may have paid — and unlike a
                    // spot there is no `seen` message carrying the award for a
                    // client to add up from. The ledger goes out instead.
                    sendLedger();
                }
            }

            case "buy" -> {
                String line = game.buy(id, WatchJson.big(message, "s", 0),
                        WatchJson.str(message, "k", ""));
                if (line != null) {
                    bagChanged(id, line);
                    // The balance is the party's, so everybody is told — a
                    // friend across the valley should see the points go down
                    // when somebody spends them out of the shared book.
                    sendLedger();
                }
            }

            case "stamp" -> {
                String line = game.stamp(id, WatchJson.big(message, "s", 0));
                if (line != null) {
                    conn.send(Protocol.encode(WatchProto.info(line)));
                    // The one message a client cannot derive: a page has been
                    // turned, and the tally that says what still scores is now
                    // empty. FieldGuide.load replaces rather than merges it for
                    // exactly this.
                    sendLedger();
                }
            }

            // --- maps ------------------------------------------------------
            //
            // Every one of these ends in sendWorld(), and none of them ends in
            // bagChanged(): a map is world state, not a possession, even while
            // it is in one player's satchel. The world sync is also what makes
            // the wait bearable — the tick's own sync is five seconds away, and
            // five seconds is a very long time to watch for a line you just
            // drew to appear.

            case "chart" -> {
                var chart = game.drawMap(id, WatchJson.num(message, "r", 0));
                if (chart != null) {
                    conn.send(Protocol.encode(WatchProto.info(
                            "Drew " + chart.name() + " — " + chart.describe())));
                    sendWorld();
                }
            }

            case "rename" -> {
                if (game.renameMap(id, WatchJson.big(message, "c", 0),
                        WatchJson.str(message, "n", ""))) {
                    sendWorld();
                }
            }

            case "mark" -> {
                List<Object> flat = WatchJson.list(message, "p");
                int points = flat.size() / 2;
                double[] xs = new double[points];
                double[] ys = new double[points];
                for (int i = 0; i < points; i++) {
                    xs[i] = flat.get(i * 2) instanceof Number a ? a.doubleValue() : 0;
                    ys[i] = flat.get(i * 2 + 1) instanceof Number b ? b.doubleValue() : 0;
                }
                if (game.markMap(id, WatchJson.big(message, "c", 0),
                        WatchJson.integer(message, "i", 0), xs, ys) != null) {
                    sendWorld();
                }
            }

            case "note" -> {
                if (game.noteMap(id, WatchJson.big(message, "c", 0),
                        WatchJson.integer(message, "i", 0),
                        WatchJson.num(message, "x", 0), WatchJson.num(message, "y", 0),
                        WatchJson.str(message, "s", "")) != null) {
                    sendWorld();
                }
            }

            case "erase" -> {
                if (game.eraseMark(id, WatchJson.big(message, "c", 0),
                        WatchJson.big(message, "s", 0))) {
                    sendWorld();
                }
            }

            case "pin" -> {
                if (game.pinMap(id, WatchJson.big(message, "c", 0),
                        WatchJson.big(message, "b", 0))) {
                    sendWorld();
                }
            }

            case "ping" -> conn.send(Protocol.encode(
                    WatchProto.pong(WatchJson.big(message, "p", 0))));

            default -> { }
        }
    }

    private static Recipes.Station stationOf(String name) {
        for (Recipes.Station station : Recipes.Station.values()) {
            if (station.name().equalsIgnoreCase(name)) return station;
        }
        return Recipes.Station.HANDS;
    }

    private static String nameOf(String key) {
        return com.larsons.engine.watch.Forage.nameOf(key);
    }

    /**
     * Tell everybody about one new entry in the book.
     *
     * <p>One entry, not the whole guide. {@link com.larsons.engine.watch.FieldGuide#load}
     * <em>merges</em> rather than replacing, so a message carrying a single
     * sighting fills that page in for every client — and a party three hundred
     * species into a walk is not re-sending three hundred rows every time
     * somebody finds a wren.
     */
    private void announceEntry(String species) {
        var sighting = game.guide().firstSighting(species);
        if (sighting == null) return;
        Map<String, Object> entries = new java.util.LinkedHashMap<>();
        entries.put("entries", java.util.List.of(sighting.toMap()));
        toAll(WatchProto.guide(entries));
    }

    /**
     * Tell everybody what the book's ledger now says.
     *
     * <p>Three numbers and the open page, and no entries at all — which is the
     * whole reason {@code FieldGuide.ledger} exists separately from
     * {@code toMap}. A party four hundred species into a walk should not be sent
     * four hundred rows to be told that somebody bought a plank.
     */
    private void sendLedger() {
        toAll(WatchProto.guide(game.guide().ledger()));
    }

    /** Push a player's satchel back to them, and optionally say what changed. */
    private void bagChanged(int id, String line) {
        WatchPlayer player = game.player(id);
        if (player == null) return;
        toPlayer(id, WatchProto.bag(player.satchel().toMap()));
        if (line != null) toPlayer(id, WatchProto.info(line));
    }

    // --- connections ----------------------------------------------------------------

    /**
     * One socket.
     *
     * <p>Two threads and a queue: the reader decodes lines and drops them on
     * the server's request queue, the writer takes whatever the tick thread
     * queued and writes it. Neither of them touches the game.
     */
    private final class Connection {
        private final Socket socket;
        private final LinkedBlockingQueue<String> outbox =
                new LinkedBlockingQueue<>(OUTBOX_CAPACITY);
        private volatile boolean closed;
        private volatile boolean flushThenClose;
        private volatile int playerId;
        private volatile long lastHeard = System.nanoTime();
        private volatile String name = "Walker";

        Connection(Socket socket) { this.socket = socket; }

        void start() {
            daemon("watch-read-" + socket.getPort(), this::readLoop).start();
            daemon("watch-write-" + socket.getPort(), this::writeLoop).start();
        }

        void send(String line) {
            if (closed) return;
            if (!outbox.offer(line)) {
                // A client that cannot keep up with twenty snapshots a second
                // is a client that is gone; dropping it is kinder than letting
                // the queue grow until the host runs out of memory.
                markClosed();
            }
        }

        void markClosed() {
            closed = true;
            outbox.clear();
            closeQuietly(socket);
        }

        /**
         * Say one last thing, then go.
         *
         * <p><b>Not {@code send} followed by {@code markClosed}</b>, which is
         * what this replaced and which never delivered anything: the writer
         * runs on its own thread, so closing immediately after queueing empties
         * the outbox before it has been looked at. A client refused for being
         * the ninth to arrive would simply see the socket drop, with no reason
         * given — which reads as "the game is broken", not "the walk is full".
         */
        void closeAfterFlush(String line) {
            send(line);
            flushThenClose = true;
        }

        private void readLoop() {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(
                    socket.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while (!closed && (line = in.readLine()) != null) {
                    lastHeard = System.nanoTime();
                    Map<String, Object> message = Protocol.decode(line);
                    if (message == null) continue;
                    if (playerId <= 0) {
                        if (!"join".equals(WatchProto.type(message))) continue;
                        if (WatchJson.integer(message, "v", 0) != WatchProto.VERSION) {
                            closeAfterFlush(Protocol.encode(WatchProto.error(
                                    "Different version of Field Guide — host is v"
                                            + WatchProto.VERSION)));
                            return;
                        }
                        name = WatchProto.sanitizeName(
                                WatchJson.str(message, "name", "Walker"));
                        pendingJoins.add(this);
                        continue;
                    }
                    pendingRequests.add(new Request(this, message));
                }
            } catch (IOException e) {
                // A closed socket is a player who left, which is not an error.
            } finally {
                markClosed();
            }
        }

        private void writeLoop() {
            try (BufferedWriter out = new BufferedWriter(new OutputStreamWriter(
                    socket.getOutputStream(), StandardCharsets.UTF_8))) {
                while (!closed) {
                    String line = outbox.poll(200, TimeUnit.MILLISECONDS);
                    if (line != null) {
                        out.write(line);
                        out.write('\n');
                        out.flush();
                    }
                    if (flushThenClose && outbox.isEmpty()) {
                        markClosed();
                        return;
                    }
                }
            } catch (IOException | InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                markClosed();
            }
        }
    }

    // --- plumbing -------------------------------------------------------------------

    private static Thread daemon(String name, Runnable body) {
        Thread thread = new Thread(body, name);
        thread.setDaemon(true);
        return thread;
    }

    private static void parkUntil(long deadlineNanos) {
        long remaining = deadlineNanos - System.nanoTime();
        if (remaining <= 0) return;
        try {
            Thread.sleep(remaining / 1_000_000, (int) (remaining % 1_000_000));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (IOException ignored) {
            // Closing a socket that is already gone is not news.
        }
    }

    private static void joinQuietly(Thread thread) {
        if (thread == null) return;
        try {
            thread.join(1500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void log(String text) {
        System.out.println("[watch] " + text);
    }
}
