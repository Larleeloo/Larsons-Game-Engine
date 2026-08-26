package com.larsons.engine.watch.net;

import com.larsons.engine.net.Protocol;
import com.larsons.engine.watch.Spotlight;
import com.larsons.engine.watch.WatchJson;
import com.larsons.engine.watch.WatchView;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * One player's end of a shared walk.
 *
 * <p><b>It decides nothing.</b> Everything a client does is a request — "I am
 * here now", "I clicked on that", "I would like to plant this" — and everything
 * it knows arrives in a snapshot. What it maintains is a {@link WatchView},
 * which is exactly what the scene draws, and which the solo path fills from a
 * locally-owned game instead. That seam is the whole reason single-player and
 * multiplayer are the same game rather than two.
 *
 * <p><b>Nothing here blocks the frame thread.</b> A reader thread parses lines
 * onto a queue; {@link #pump()} drains that queue on the frame thread and
 * applies it to the view. So a stalled host makes the world stop moving, which
 * is honest, rather than making the window stop responding, which is not.
 */
public final class WatchClient implements AutoCloseable {

    /** How long to wait for the socket to open. */
    private static final int CONNECT_TIMEOUT_MS = 6000;

    /** How many unread messages may pile up before the oldest are dropped. */
    private static final int INBOX_LIMIT = 4096;

    private final Socket socket;
    private final LinkedBlockingQueue<String> outbox = new LinkedBlockingQueue<>(1024);
    private final ConcurrentLinkedQueue<Map<String, Object>> inbox =
            new ConcurrentLinkedQueue<>();
    private final WatchView view = new WatchView();

    private volatile boolean closed;
    private volatile String error;
    private volatile boolean welcomed;
    private volatile int tickRate = WatchProto.TICK_RATE;
    private volatile int maxPlayers = WatchProto.MAX_PLAYERS;
    private volatile long lastPingSent;
    private volatile long roundTripMillis;

    private WatchClient(Socket socket) {
        this.socket = socket;
    }

    /**
     * Dial a host and say hello.
     *
     * @throws IOException when the host cannot be reached at all; a host that
     *                     refuses the join (wrong version, full) answers with
     *                     an {@code error} message instead, which shows up in
     *                     {@link #error()}
     */
    public static WatchClient connect(String host, int port, String name)
            throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
        socket.setTcpNoDelay(true);
        WatchClient client = new WatchClient(socket);
        client.start();
        client.send(WatchProto.join(name));
        return client;
    }

    private void start() {
        daemon("watch-client-read", this::readLoop).start();
        daemon("watch-client-write", this::writeLoop).start();
    }

    /** What the scene draws. */
    public WatchView view() { return view; }

    /** Whether the handshake has completed. */
    public boolean ready() { return welcomed; }

    /** Whether the connection has gone. */
    public boolean closed() { return closed; }

    /** The reason the host gave for refusing or dropping us, or {@code null}. */
    public String error() { return error; }

    /** The host's tick rate, from the welcome. */
    public int tickRate() { return tickRate; }

    /** How many the host will let in. */
    public int maxPlayers() { return maxPlayers; }

    /** The last measured round trip, in milliseconds. */
    public long ping() { return roundTripMillis; }

    // --- sending --------------------------------------------------------------------

    /** Queue a message for the host. */
    public void send(Map<String, Object> message) {
        if (closed || message == null) return;
        if (!outbox.offer(Protocol.encode(message))) close();
    }

    /** Tell the host where we are. Sent once a frame. */
    public void sendMove(double x, double y, double z, double yaw, double pitch,
                         boolean crouching) {
        send(WatchProto.move(x, y, z, yaw, pitch, crouching));
    }

    /** "There!" — {@code 0} means whatever the host thinks we are looking at. */
    public void sendSpot(long animalId) { send(WatchProto.spot(animalId)); }

    /** One of the no-argument verbs: pick, log, harvest, cross, cast, strike. */
    public void sendAction(String kind) { send(WatchProto.action(kind)); }

    public void sendPlaceLure(String food) { send(WatchProto.placeLure(food)); }

    public void sendRefill(long lureId) { send(WatchProto.refill(lureId)); }

    public void sendPlant(String seed) { send(WatchProto.plant(seed)); }

    public void sendBuild(String piece, int turn, boolean inTree) {
        send(WatchProto.build(piece, turn, inTree));
    }

    public void sendCraft(String output, String station) {
        send(WatchProto.craft(output, station));
    }

    // --- receiving ------------------------------------------------------------------

    /**
     * Apply everything that has arrived since the last frame.
     *
     * <p>Called once a frame from the scene, on the frame thread, which is what
     * makes the view safe to read without a lock: only this method writes it.
     *
     * @return how many messages were applied
     */
    public int pump() {
        int applied = 0;
        Map<String, Object> message;
        while ((message = inbox.poll()) != null) {
            apply(message);
            applied++;
        }
        // One ping a second, so the HUD has something honest to show and a
        // silently dead connection is noticed rather than assumed alive.
        long now = System.currentTimeMillis();
        if (welcomed && now - lastPingSent > 1000) {
            lastPingSent = now;
            send(WatchProto.ping(now));
        }
        return applied;
    }

    private void apply(Map<String, Object> message) {
        switch (WatchProto.type(message)) {
            case "welcome" -> {
                view.setSelfId(WatchJson.integer(message, "id", 0));
                view.setSeed(WatchJson.big(message, "seed", 0));
                view.setWorldName(WatchJson.str(message, "world", "Field Guide"));
                tickRate = Math.max(1, WatchJson.integer(message, "tick", tickRate));
                maxPlayers = WatchJson.integer(message, "max", maxPlayers);
                welcomed = true;
            }
            case "state" -> {
                view.setTimeOfDay(WatchJson.num(message, "time", view.timeOfDay()));
                view.loadWalkers(WatchJson.objects(message, "players"));
                view.loadCreatures(WatchJson.objects(message, "animals"));
                view.loadLures(WatchJson.objects(message, "lures"));
            }
            case "party" -> {
                // The party list is a superset of the snapshot's while somebody
                // is still loading; the snapshot is what gets drawn, so this is
                // only worth a line in the log.
                int count = WatchJson.objects(message, "players").size();
                view.say(count + (count == 1 ? " walker" : " walkers") + " out");
            }
            case "seen" -> {
                Spotlight light = Spotlight.fromMap(message);
                if (light != null) {
                    view.spotlights().add(light);
                    view.say(light.label());
                }
            }
            case "bag" -> view.satchel().load(WatchJson.map(message, "items"));
            case "guide" -> view.guide().load(message);
            case "world" -> {
                view.grove().load(WatchJson.map(message, "grove"));
                view.crops().load(WatchJson.map(message, "crops"));
                view.structure().load(WatchJson.map(message, "built"));
            }
            case "info" -> view.say(WatchJson.str(message, "msg", ""));
            case "error" -> {
                error = WatchJson.str(message, "msg", "The host refused the connection");
                view.say(error);
                close();
            }
            case "pong" -> roundTripMillis =
                    System.currentTimeMillis() - WatchJson.big(message, "p", 0);
            default -> { }
        }
    }

    private void readLoop() {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(
                socket.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while (!closed && (line = in.readLine()) != null) {
                Map<String, Object> message =
                        Protocol.decode(line, Protocol.MAX_SERVER_LINE_LENGTH);
                if (message == null) continue;
                // A frame that has not run for a while must not be able to make
                // this queue unbounded: the oldest snapshot is the one least
                // worth keeping.
                while (inbox.size() > INBOX_LIMIT) inbox.poll();
                inbox.add(message);
            }
        } catch (IOException e) {
            if (!closed && error == null) error = "Lost the connection to the host";
        } finally {
            closed = true;
        }
    }

    private void writeLoop() {
        try (BufferedWriter out = new BufferedWriter(new OutputStreamWriter(
                socket.getOutputStream(), StandardCharsets.UTF_8))) {
            while (!closed) {
                String line = outbox.poll(200, TimeUnit.MILLISECONDS);
                if (line == null) continue;
                out.write(line);
                out.write('\n');
                out.flush();
            }
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            closed = true;
        }
    }

    @Override
    public void close() {
        closed = true;
        outbox.clear();
        try {
            socket.close();
        } catch (IOException ignored) {
            // Closing a socket that is already gone is not news.
        }
    }

    private static Thread daemon(String name, Runnable body) {
        Thread thread = new Thread(body, name);
        thread.setDaemon(true);
        return thread;
    }
}
