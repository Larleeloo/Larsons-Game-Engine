package com.larsons.engine.demo;

import com.larsons.engine.config.GameContext;
import com.larsons.engine.graphics.draw.DrawTarget;
import com.larsons.engine.input.GameAction;
import com.larsons.engine.input.InputManager;
import com.larsons.engine.input.KeyBinds;
import com.larsons.engine.net.Lan;
import com.larsons.engine.scene.AbstractScene;
import com.larsons.engine.ui.ConfigForm;
import com.larsons.engine.ui.Menu;
import com.larsons.engine.ui.MenuTheme;
import com.larsons.engine.watch.FieldGuide;
import com.larsons.engine.watch.WatchGame;
import com.larsons.engine.watch.WatchStore;
import com.larsons.engine.watch.life.AnimalRegistry;
import com.larsons.engine.watch.net.WatchClient;
import com.larsons.engine.watch.net.WatchProto;
import com.larsons.engine.watch.net.WatchServer;
import com.larsons.engine.watch.net.WatchSession;
import com.larsons.engine.watch.world.WatchBiomes;

import java.awt.Color;
import java.awt.Font;
import java.io.IOException;
import java.util.List;
import java.util.Random;

/**
 * The front door to the Field Guide: start a walk, carry one on, or join
 * somebody else's.
 *
 * <p>Three ways in, and they are deliberately the same three the rest of the
 * engine offers — walk alone, host for your friends, or type in an address. A
 * hosted walk runs a {@link WatchServer} in this process and then <em>joins it
 * over the loopback like everybody else</em>, so there is no separate "host"
 * code path that could work when the guest path does not.
 *
 * <p>The seed is on the screen because it is worth sharing: two people who type
 * in the same number walk the same world, down to the crooked pine.
 */
public class WatchLobbyScene extends AbstractScene {

    /** The scene this lobby lives at. */
    public static final String NAME = "watchlobby";

    private static final Color BG = new Color(12, 18, 15);
    private static final Color TEXT_DIM = new Color(140, 158, 146);
    private static final Color ERROR = new Color(232, 140, 120);
    private static final Font NOTE = new Font("SansSerif", Font.PLAIN, 13);

    private final GameContext ctx;
    private final WatchStore store;

    private Menu menu;
    private ConfigForm form;
    private Screen screen = Screen.MENU;
    private String status = "";
    private boolean statusIsError;

    // What the forms are editing.
    private String playerName = "Walker";
    private String worldName = "Morning Walk";
    private String seedText = "";
    private String hostAddress = "localhost";
    private int port = WatchProto.DEFAULT_PORT;

    private enum Screen { MENU, NEW_WALK, HOST, JOIN }

    public WatchLobbyScene(GameContext ctx) {
        this(ctx, new WatchStore());
    }

    public WatchLobbyScene(GameContext ctx, WatchStore store) {
        this.ctx = ctx;
        this.store = store;
    }

    @Override
    public void onEnter() {
        screen = Screen.MENU;
        status = "";
        statusIsError = false;
        ctx.lighting().setDarkness(0);
        ctx.applyLiveSettings();
        buildMenu();
    }

    /** Whatever menu is on screen, so the scene can be walked in tests. */
    public Menu menu() {
        if (menu == null) buildMenu();
        return menu;
    }

    /** The form on screen, or {@code null} when a menu is. */
    public ConfigForm form() { return screen == Screen.MENU ? null : form; }

    private void buildMenu() {
        List<String> saved = store.list();
        menu = new Menu("Field Guide")
                .subtitle("Walk a world with your friends and write down what you find")
                .theme(MenuTheme.dark());

        if (!saved.isEmpty()) {
            String recent = saved.get(0);
            String about = store.describe(recent);
            menu.add("Continue — " + recent + (about == null ? "" : "  (" + about + ")"),
                    () -> continueWalk(recent));
        }
        menu.add("New Walk", () -> openNewWalk(false));
        menu.add("Host a Walk (up to " + WatchGame.MAX_PLAYERS + ")", () -> openNewWalk(true));
        menu.add("Join a Walk", this::openJoin);
        if (saved.size() > 1) {
            menu.add("Saved Walks (" + saved.size() + ")", this::openSavedList);
        }
        menu.add("Controls (Key Binds)",
                () -> KeyBindsScene.openFor(scenes, NAME, GameAction.Category.WATCH));
        menu.add("Back to Game Types", () -> scenes.transitionTo("startup"));
    }

    private void openSavedList() {
        Menu list = new Menu("Saved Walks")
                .subtitle("Trees keep growing while you are away")
                .theme(MenuTheme.dark());
        for (String name : store.list()) {
            String about = store.describe(name);
            list.add(name + (about == null ? "" : "  (" + about + ")"),
                    () -> continueWalk(name));
        }
        list.add("Back", this::buildMenu);
        menu = list;
    }

    // --- new / host -------------------------------------------------------------------

    private void openNewWalk(boolean hosting) {
        screen = hosting ? Screen.HOST : Screen.NEW_WALK;
        status = "";
        statusIsError = false;
        form = new ConfigForm(hosting ? "Host a Walk" : "New Walk")
                .theme(MenuTheme.dark());
        form.addText("Your name", () -> playerName, v -> playerName = v, 24);
        form.addText("World name", () -> worldName, v -> worldName = v, 28);
        form.addText("Seed (blank for a new one)", () -> seedText, v -> seedText = v, 20);
        if (hosting) {
            form.addInt("Port", () -> port, v -> port = v, 1024, 65535, 1);
            form.addNote(() -> "Your friends connect to " + Lan.siteLocalAddress()
                    + ":" + port);
            form.addNote("Everyone who types in the same seed walks the same world.");
        } else {
            form.addNote("A walk on your own. You can host this world later from here.");
        }
        form.addNote(WatchBiomes.count() + " biomes · " + AnimalRegistry.count()
                + " species to find · the sun follows your own clock");
        form.addAction(hosting ? "Open the walk" : "Set off", () -> start(hosting));
        form.addAction("Cancel", this::backToMenu);
    }

    private void openJoin() {
        screen = Screen.JOIN;
        status = "";
        statusIsError = false;
        form = new ConfigForm("Join a Walk").theme(MenuTheme.dark());
        form.addText("Your name", () -> playerName, v -> playerName = v, 24);
        form.addText("Host address", () -> hostAddress, v -> hostAddress = v, 40);
        form.addInt("Port", () -> port, v -> port = v, 1024, 65535, 1);
        form.addNote("The host's world, the host's clock, and the host's field guide.");
        form.addAction("Join", this::join);
        form.addAction("Cancel", this::backToMenu);
    }

    private void backToMenu() {
        screen = Screen.MENU;
        buildMenu();
    }

    // --- starting ---------------------------------------------------------------------

    private void start(boolean hosting) {
        long seed = parseSeed(seedText);
        WatchGame.Config config = hosting
                ? WatchGame.Config.hosted(worldName, seed)
                : new WatchGame.Config(seed, worldName, 1);
        if (hosting) {
            hostAndJoin(config);
        } else {
            WatchGame game = new WatchGame(config);
            store.load(game);
            handOff(WatchSession.solo(game));
        }
    }

    private void continueWalk(String name) {
        long seed = store.seedOf(name);
        WatchGame game = new WatchGame(new WatchGame.Config(
                seed != 0 ? seed : new Random().nextLong(), name, 1));
        store.load(game);
        worldName = name;
        handOff(WatchSession.solo(game));
    }

    /**
     * Host, then join over the loopback.
     *
     * <p>The host plays through the same {@link WatchClient} everybody else
     * does. That costs a socket and a little encoding, and it buys the thing
     * worth having: there is no privileged path, so "it works for the host"
     * cannot be a bug report.
     */
    private void hostAndJoin(WatchGame.Config config) {
        WatchServer server = new WatchServer(config);
        try {
            store.load(server.game());
            server.start(port);
        } catch (IOException e) {
            fail("Could not open port " + port + ": " + e.getMessage());
            return;
        }
        try {
            WatchClient client = WatchClient.connect("127.0.0.1", server.port(),
                    playerName);
            handOff(WatchSession.hosting(server, client));
        } catch (IOException e) {
            server.stop();
            fail("Hosted, but could not join: " + e.getMessage());
        }
    }

    private void join() {
        try {
            WatchClient client = WatchClient.connect(hostAddress.trim(), port,
                    playerName);
            handOff(WatchSession.joining(client));
        } catch (IOException e) {
            fail("Could not reach " + hostAddress.trim() + ":" + port
                    + " — " + e.getMessage());
        }
    }

    private void handOff(WatchSession session) {
        if (scenes.get(WatchScene.NAME) instanceof WatchScene scene) {
            scene.adopt(session, store);
            scenes.transitionTo(WatchScene.NAME);
        } else {
            session.close();
            fail("The Field Guide scene is not registered");
        }
    }

    private void fail(String reason) {
        status = reason;
        statusIsError = true;
        backToMenu();
    }

    /**
     * The seed a player typed.
     *
     * <p>A number is that number; anything else is hashed, so "oak valley" is a
     * seed too and typing the same words gets the same world. Blank is a new
     * one — which is the only case where two people typing nothing get
     * different places, and it is the case where they meant to.
     */
    static long parseSeed(String text) {
        if (text == null || text.isBlank()) return new Random().nextLong();
        String trimmed = text.trim();
        try {
            return Long.parseLong(trimmed);
        } catch (NumberFormatException e) {
            long h = 0xCBF29CE484222325L;
            for (int i = 0; i < trimmed.length(); i++) {
                h ^= trimmed.charAt(i);
                h *= 0x100000001B3L;
            }
            return h;
        }
    }

    @Override
    public void update(double dt, InputManager input) {
        if (screen != Screen.MENU) {
            if (KeyBinds.pressed(input, GameAction.MENU_BACK)) {
                backToMenu();
                return;
            }
            form.update(dt, input);
            return;
        }
        if (KeyBinds.pressed(input, GameAction.MENU_BACK)) {
            scenes.transitionTo("startup");
            return;
        }
        menu.update(dt, input);
    }

    @Override
    public void render(DrawTarget target, float alpha) {
        target.fillRect(0, 0, viewportWidth, viewportHeight, BG);
        if (screen != Screen.MENU) {
            form.render(target, viewportWidth, viewportHeight);
            SceneChrome.hint(target, viewportHeight,
                    "Arrow keys to move, type to edit, Enter to confirm · Esc to go back");
            return;
        }
        menu.render(target, viewportWidth, viewportHeight);
        if (!status.isEmpty()) {
            SceneChrome.status(target, viewportHeight, status,
                    statusIsError ? ERROR : SceneChrome.OK);
        }
        String note = "Walks are saved under " + store.directory()
                + " · animal models go in watch/models/";
        target.drawText(note, 24, viewportHeight - 24, NOTE, TEXT_DIM);
    }

    /** How complete the most recent walk's guide is; for the launch screen. */
    public String progressLine() {
        List<String> saved = store.list();
        if (saved.isEmpty()) return AnimalRegistry.count() + " species to find";
        WatchGame game = new WatchGame(new WatchGame.Config(0, saved.get(0), 1));
        store.load(game);
        FieldGuide guide = game.guide();
        return guide.discovered() + " of " + guide.total() + " species found";
    }
}
