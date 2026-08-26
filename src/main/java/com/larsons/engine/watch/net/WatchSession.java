package com.larsons.engine.watch.net;

import com.larsons.engine.watch.WatchGame;
import com.larsons.engine.watch.WatchView;

/**
 * An active walk, carried from the lobby into the game.
 *
 * <p>Three shapes, one type — which is the point. The game scene is handed one
 * of these and asks it two questions: what should I draw
 * ({@link #view()}) and where do I send what the player did
 * ({@link #client()} / {@link #local()}). It never asks which of the three it
 * is holding.
 *
 * <ul>
 *   <li><b>Solo</b> — a {@link WatchGame} in this process and no sockets at
 *       all. {@link #local()} is it; {@link #client()} is {@code null}.</li>
 *   <li><b>Hosting</b> — a {@link WatchServer} in this process <em>and</em> a
 *       {@link WatchClient} connected to it over the loopback. The host plays
 *       through exactly the same client code every other player does, which is
 *       the arrangement that stops "it works for the host" from ever being a
 *       sentence anybody has to say.</li>
 *   <li><b>Joining</b> — a client and nothing else.</li>
 * </ul>
 *
 * <p>This mirrors {@code AutoSession} and {@code NetSession} in the rest of the
 * engine, and closing it does what closing those does: drop the connection, and
 * shut the integrated server down when there is one.
 */
public final class WatchSession implements AutoCloseable {

    private final WatchGame local;
    private final WatchClient client;
    private final WatchServer hostedServer;
    private final WatchView soloView;

    private WatchSession(WatchGame local, WatchClient client, WatchServer hostedServer) {
        this.local = local;
        this.client = client;
        this.hostedServer = hostedServer;
        this.soloView = local != null ? new WatchView() : null;
    }

    /** A walk on your own. */
    public static WatchSession solo(WatchGame game) {
        return new WatchSession(game, null, null);
    }

    /** A walk you are hosting, joined over the loopback like everybody else. */
    public static WatchSession hosting(WatchServer server, WatchClient client) {
        return new WatchSession(null, client, server);
    }

    /** Somebody else's walk. */
    public static WatchSession joining(WatchClient client) {
        return new WatchSession(null, client, null);
    }

    /** The locally simulated game, or {@code null} when playing online. */
    public WatchGame local() { return local; }

    /** The connection, or {@code null} when playing alone. */
    public WatchClient client() { return client; }

    /** The server this session is hosting, or {@code null}. */
    public WatchServer hostedServer() { return hostedServer; }

    /** Whether this player is the host others connect to. */
    public boolean isHost() { return hostedServer != null; }

    /** Whether anything is going over a socket. */
    public boolean online() { return client != null; }

    /** Whether the session is still usable. */
    public boolean alive() {
        return client == null || !client.closed();
    }

    /** Why it stopped being usable, or {@code null}. */
    public String error() { return client == null ? null : client.error(); }

    /**
     * What the scene draws.
     *
     * <p>Solo, the local game is copied into a view; online, the client's view
     * is already up to date from the last snapshot. Either way the caller gets
     * the same type and reads it the same way.
     */
    public WatchView view() {
        if (client != null) return client.view();
        return soloView;
    }

    /** The id of the player this session belongs to. */
    public int selfId() {
        return client != null ? client.view().selfId() : soloView.selfId();
    }

    /**
     * Advance whatever needs advancing, and refresh the view.
     *
     * @param dt seconds since the last frame
     */
    public void update(double dt) {
        if (client != null) {
            client.pump();
            client.view().ageSpotlights(dt);
            return;
        }
        if (local != null) {
            local.tick(dt);
            soloView.snapshot(local, soloView.selfId());
        }
    }

    /** Which player the solo view belongs to; set once, after joining. */
    public void setSelfId(int id) {
        if (soloView != null) soloView.setSelfId(id);
    }

    @Override
    public void close() {
        if (client != null) client.close();
        if (hostedServer != null) hostedServer.stop();
    }
}
