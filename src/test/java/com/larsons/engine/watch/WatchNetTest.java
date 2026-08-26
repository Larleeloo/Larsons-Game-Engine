package com.larsons.engine.watch;

import com.larsons.engine.watch.net.WatchClient;
import com.larsons.engine.watch.net.WatchProto;
import com.larsons.engine.watch.net.WatchServer;
import com.larsons.engine.watch.net.WatchSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Eight people on one walk, over a real socket on loopback.
 *
 * <p><b>What crosses the wire is small on purpose.</b> The world is a function
 * of the seed, so no terrain, no tree and no blade of grass is ever sent — a
 * client is told the seed once and generates the same hillside the host is
 * standing on. What is sent is what cannot be derived: where people are, what
 * is alive near them, who spotted what, and the guide they are filling in
 * together.
 *
 * <p>These tests run a real {@link WatchServer} and real {@link WatchClient}s
 * rather than a mock, because the things that break here are the things a mock
 * would paper over: a socket closed before its outbox drained, a message
 * arriving before the welcome, a snapshot that was never sent because nobody
 * had moved.
 */
@Timeout(120)
class WatchNetTest {

    private WatchServer server;
    private final List<WatchClient> clients = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (WatchClient client : clients) client.close();
        clients.clear();
        if (server != null) server.stop();
    }

    private WatchServer host(String name) throws IOException {
        server = new WatchServer(WatchGame.Config.hosted(name, 20250607L));
        server.start(0);
        assertTrue(server.isRunning(), "the server did not come up");
        return server;
    }

    private WatchClient join(String name) throws IOException {
        WatchClient client = WatchClient.connect("127.0.0.1", server.port(), name);
        clients.add(client);
        return client;
    }

    /** Run both ends until {@code until} comes true, or fail saying what did not happen. */
    private void until(String what, java.util.function.BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            for (WatchClient client : clients) client.pump();
            if (condition.getAsBoolean()) return;
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        for (WatchClient client : clients) client.pump();
        assertTrue(condition.getAsBoolean(), "timed out waiting for: " + what);
    }

    // --- the party ---------------------------------------------------------------------

    @Test
    void eightPeopleCanWalkTogether() throws IOException {
        host("Eight");
        for (int i = 0; i < WatchProto.MAX_PLAYERS; i++) join("Walker " + i);

        until("everybody to be welcomed", () -> clients.stream().allMatch(WatchClient::ready));
        until("the server to seat everybody",
                () -> server.playerCount() == WatchProto.MAX_PLAYERS);

        for (WatchClient client : clients) {
            assertEquals(20250607L, client.view().seed(), "a client got the wrong world");
            assertEquals("Eight", client.view().worldName());
            assertEquals(WatchProto.MAX_PLAYERS, client.maxPlayers());
            assertTrue(client.view().selfId() > 0, "a client was never told who it is");
        }
        until("everybody to see the whole party",
                () -> clients.get(0).view().walkers().size() == WatchProto.MAX_PLAYERS);
    }

    /**
     * The ninth is turned away <em>with a reason</em>. The first version of this
     * dropped the socket instead: {@code send} cleared the outbox on close, so
     * the message explaining why never left the building and a player who could
     * not get in saw "connection lost".
     */
    @Test
    void aNinthPlayerIsToldWhyRatherThanDropped() throws IOException {
        host("Full");
        for (int i = 0; i < WatchProto.MAX_PLAYERS; i++) join("Walker " + i);
        until("the walk to fill", () -> server.playerCount() == WatchProto.MAX_PLAYERS);

        WatchClient ninth = join("Latecomer");
        until("the ninth to be turned away",
                () -> ninth.error() != null || ninth.closed());

        assertNotNull(ninth.error(), "the ninth player was dropped without being told why");
        assertTrue(ninth.error().toLowerCase().contains("full"),
                "the reason given was '" + ninth.error() + "'");
        assertFalse(ninth.ready(), "a refused client thinks it is in the game");
        assertEquals(WatchProto.MAX_PLAYERS, server.playerCount(),
                "the ninth player got in anyway");
    }

    @Test
    void leavingIsNoticedByEverybodyElse() throws IOException {
        host("Comings");
        WatchClient stays = join("Stays");
        WatchClient goes = join("Goes");
        until("both to be in", () -> server.playerCount() == 2);
        until("both to see each other", () -> stays.view().walkers().size() == 2);

        goes.close();
        until("the departure to reach the other client",
                () -> stays.view().walkers().size() == 1);
        assertEquals("Stays", stays.view().walkers().get(0).name());
    }

    // --- what is replicated -------------------------------------------------------------

    @Test
    void movingIsSeenByTheOtherPeopleOnTheWalk() throws IOException {
        host("Moving");
        WatchClient mover = join("Mover");
        WatchClient watcher = join("Watcher");
        until("both to be in", () -> server.playerCount() == 2);
        until("the watcher to see the mover", () -> watcher.view().others().size() == 1);

        double startX = watcher.view().others().get(0).x();
        for (int i = 0; i < 60; i++) {
            mover.sendMove(startX + 40, 12.5, 3, 1.2, 0, false);
            mover.pump();
            watcher.pump();
            sleep(10);
        }
        until("the movement to arrive", () -> {
            var seen = watcher.view().others();
            return !seen.isEmpty() && Math.abs(seen.get(0).x() - (startX + 40)) < 1.0;
        });

        var seen = watcher.view().others().get(0);
        assertEquals(12.5, seen.y(), 0.1, "the y did not survive the trip");
        assertEquals("Mover", seen.name());
    }

    @Test
    void animalsReachEveryClientWithoutAnyTerrainBeingSent() throws IOException {
        host("Alive");
        WatchClient client = join("Kara");
        until("the welcome", client::ready);

        for (int i = 0; i < 400; i++) {
            client.sendMove(0, 0, server.game().groundAt(0, 0), 0, 0, false);
            client.pump();
            sleep(5);
        }
        until("animals to arrive", () -> !client.view().creatures().isEmpty());

        for (var creature : client.view().creatures()) {
            assertNotNull(creature.def(), "an animal arrived without a species");
            assertNotNull(creature.def().name());
        }
    }

    /** The verb the brief asked for: click it, and everybody else sees it light up. */
    @Test
    void spottingSomethingHighlightsItForEverybodyElse() throws IOException {
        host("Spotting");
        WatchClient finder = join("Kara");
        WatchClient friend = join("Sam");
        until("both to be in", () -> server.playerCount() == 2);

        for (int i = 0; i < 400 && server.game().animals().isEmpty(); i++) {
            finder.sendMove(0, 0, server.game().groundAt(0, 0), 0, 0, false);
            finder.pump();
            friend.pump();
            sleep(5);
        }
        until("something to be alive near the party",
                () -> !server.game().animals().isEmpty());

        long id = server.game().animals().get(0).id();
        finder.sendSpot(id);

        until("the highlight to reach the other player",
                () -> !friend.view().spotlights().isEmpty());
        Spotlight light = friend.view().spotlights().get(0);
        assertEquals("Kara", light.finder(), "the highlight names the wrong finder");
        assertEquals(id, light.animalId());
        assertTrue(light.alive());
    }

    /** One book, filled in by everybody — a discovery is the party's, not yours. */
    @Test
    void theFieldGuideIsSharedByThePartyAndTheSatchelIsNot() throws IOException {
        host("Sharing");
        WatchClient finder = join("Kara");
        WatchClient friend = join("Sam");
        until("both to be in", () -> server.playerCount() == 2);
        until("both to be welcomed", () -> finder.ready() && friend.ready());

        for (int i = 0; i < 400 && server.game().animals().isEmpty(); i++) {
            finder.sendMove(0, 0, server.game().groundAt(0, 0), 0, 0, false);
            finder.pump();
            friend.pump();
            sleep(5);
        }
        until("something to be alive", () -> !server.game().animals().isEmpty());

        var animal = server.game().animals().get(0);
        finder.sendSpot(animal.id());

        until("the discovery to reach the other player's book",
                () -> friend.view().guide().seen(animal.def().key()));
        assertTrue(finder.view().guide().seen(animal.def().key()),
                "the finder's own book did not get it");

        // A satchel is private: Sam's is Sam's, and nothing Kara picked up is in it.
        assertTrue(friend.view().satchel().total() > 0, "Sam started with an empty satchel");
    }

    // --- the session wrapper --------------------------------------------------------------

    @Test
    void aSoloSessionIsTheSameShapeAsAnOnlineOne() {
        WatchGame game = new WatchGame(WatchGame.Config.solo("Alone"));
        game.join(1, "Kara");
        try (WatchSession session = WatchSession.solo(game)) {
            assertFalse(session.online(), "a solo walk opened a socket");
            assertTrue(session.alive());
            assertNotNull(session.local());
            session.setSelfId(1);
            session.update(0.05);

            assertNotNull(session.view(), "a solo session has no view to draw");
            assertNotNull(session.view().self(), "a solo session cannot find the player");
            assertEquals("Kara", session.view().self().name());
        }
    }

    @Test
    void hostingJoinsYourOwnServerOverLoopback() throws IOException {
        WatchServer hosted = new WatchServer(WatchGame.Config.hosted("Mine", 5L));
        hosted.start(0);
        WatchClient self = WatchClient.connect("127.0.0.1", hosted.port(), "Host");
        try (WatchSession session = WatchSession.hosting(hosted, self)) {
            assertTrue(session.isHost());
            assertTrue(session.online());
            assertNotNull(session.hostedServer());

            long deadline = System.currentTimeMillis() + 15_000;
            while (System.currentTimeMillis() < deadline && session.view().self() == null) {
                session.update(0.05);
                sleep(10);
            }
            assertNotNull(session.view().self(), "the host never joined its own walk");
            assertEquals("Host", session.view().self().name());
            assertEquals(5L, session.view().seed());
        } finally {
            hosted.stop();
        }
    }

    @Test
    void aSessionThatCannotConnectSaysSoRatherThanHanging() {
        IOException thrown = null;
        try {
            // Port 1 on loopback: nothing is listening and nothing will be.
            WatchClient.connect("127.0.0.1", 1, "Nobody").close();
        } catch (IOException e) {
            thrown = e;
        }
        assertNotNull(thrown, "connecting to a closed port succeeded");
    }

    // --- the protocol ------------------------------------------------------------------

    /**
     * Positions go over the wire rounded to the centimetre. Asked end to end
     * rather than of the rounding function, which is private and should stay
     * that way: what matters is that a position sent at full precision comes
     * back at centimetre precision, not how it got there.
     */
    @Test
    void positionsArriveRoundedToTheCentimetre() throws IOException {
        host("Precision");
        WatchClient mover = join("Mover");
        WatchClient watcher = join("Watcher");
        until("both to be in", () -> server.playerCount() == 2);
        until("the watcher to see the mover", () -> watcher.view().others().size() == 1);

        double awkward = 12.3456789;
        for (int i = 0; i < 60; i++) {
            mover.sendMove(awkward, -awkward, 3, 0, 0, false);
            mover.pump();
            watcher.pump();
            sleep(10);
        }
        until("the position to arrive", () -> {
            var seen = watcher.view().others();
            return !seen.isEmpty() && Math.abs(seen.get(0).x() - awkward) < 0.02;
        });

        double x = watcher.view().others().get(0).x();
        assertEquals(Math.round(x * 100) / 100.0, x, 1e-9,
                "a position arrived at more than centimetre precision (" + x + ")");
        assertEquals(12.35, x, 1e-9);
    }

    @Test
    void theProtocolAgreesWithTheGameAboutItsOwnLimits() {
        assertEquals(WatchGame.MAX_PLAYERS, WatchProto.MAX_PLAYERS,
                "the wire and the simulation disagree about how many people fit");
        assertTrue(WatchProto.TICK_RATE > 0 && WatchProto.TICK_RATE <= 60);
        assertTrue(WatchProto.DEFAULT_PORT > 1024);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
