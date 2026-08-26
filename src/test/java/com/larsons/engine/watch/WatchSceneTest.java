package com.larsons.engine.watch;

import com.larsons.engine.config.GameContext;
import com.larsons.engine.config.GameTypeStore;
import com.larsons.engine.demo.WatchGuideScene;
import com.larsons.engine.demo.WatchLobbyScene;
import com.larsons.engine.demo.WatchScene;
import com.larsons.engine.graphics.MiniGameSprites;
import com.larsons.engine.graphics.Offscreen;
import com.larsons.engine.graphics.TextureKeys;
import com.larsons.engine.graphics.draw.RecordingTarget;
import com.larsons.engine.input.GameAction;
import com.larsons.engine.input.InputManager;
import com.larsons.engine.minigame.StandaloneGame;
import com.larsons.engine.scene.SceneManager;
import com.larsons.engine.ui.MenuItem;
import com.larsons.engine.watch.life.AnimalDef;
import com.larsons.engine.watch.life.AnimalFamily;
import com.larsons.engine.watch.life.AnimalRegistry;
import com.larsons.engine.watch.world.WatchBiome;
import com.larsons.engine.watch.world.WatchBiomes;
import com.larsons.engine.watch.world.WatchMaterial;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The mini game as the rest of the engine sees it: a tile on the launch strip,
 * three scenes, a category of key binds, and a set of texture keys a pack can
 * override.
 *
 * <p>Nothing here opens a window. A scene is built over a scratch context,
 * registered with a bare {@link SceneManager}, ticked, and drawn to a
 * {@link RecordingTarget} — the same lifecycle the engine puts it through,
 * minus the frame.
 */
@Timeout(180)
class WatchSceneTest {

    private static final int WIDTH = 800, HEIGHT = 480;

    private static GameContext context(Path dir) {
        return new GameContext(null, new GameTypeStore(dir.toString()));
    }

    // --- the launch strip ------------------------------------------------------------

    @Test
    void theFieldGuideIsOnTheLaunchStripBesideTheOtherMiniGames() {
        StandaloneGame guide = null;
        for (StandaloneGame game : StandaloneGame.values()) {
            if ("field_guide".equals(game.key())) guide = game;
        }
        assertNotNull(guide, "the Field Guide is not on the launch strip at all");
        assertEquals(WatchLobbyScene.NAME, guide.scene(),
                "the tile does not open the walk's lobby");
        assertTrue(guide.title() != null && !guide.title().isBlank());
        assertTrue(guide.tagline() != null && !guide.tagline().isBlank(),
                "the tile says nothing about what it is");
        assertNotNull(guide.accent(), "the tile has no colour");

        // Beside, not instead of.
        Set<String> keys = new TreeSet<>();
        for (StandaloneGame game : StandaloneGame.values()) keys.add(game.key());
        assertTrue(keys.size() >= 4,
                "the strip lost a mini game when this one was added: " + keys);
        assertTrue(keys.contains("evolution"), "the evolution game is gone");
    }

    @Test
    void everyMiniGameOnTheStripHasAnIconAndTheyAreNotTheSameIcon() {
        List<int[]> pixels = new ArrayList<>();
        for (StandaloneGame game : StandaloneGame.values()) {
            BufferedImage icon = MiniGameSprites.generated(game,
                    MiniGameSprites.State.STATIC, 96);
            assertNotNull(icon, game + " has no icon");
            int[] sample = new int[64];
            int at = 0;
            for (int y = 4; y < icon.getHeight() && at < 64; y += 11) {
                for (int x = 4; x < icon.getWidth() && at < 64; x += 11) {
                    sample[at++] = icon.getRGB(x, y);
                }
            }
            pixels.add(sample);
        }
        for (int i = 0; i < pixels.size(); i++) {
            for (int j = i + 1; j < pixels.size(); j++) {
                assertFalse(java.util.Arrays.equals(pixels.get(i), pixels.get(j)),
                        StandaloneGame.values()[i] + " and " + StandaloneGame.values()[j]
                                + " have the same icon");
            }
        }
    }

    // --- the scenes ------------------------------------------------------------------

    @Test
    void allThreeScenesRegisterAndDrawWithoutAWindow(@TempDir Path dir) {
        GameContext ctx = context(dir);
        SceneManager scenes = new SceneManager();
        scenes.setViewport(WIDTH, HEIGHT);
        scenes.register(WatchLobbyScene.NAME,
                new WatchLobbyScene(ctx, new WatchStore(dir.resolve("walks").toString())));
        WatchGuideScene book = new WatchGuideScene(ctx);
        scenes.register(WatchGuideScene.NAME, book);
        scenes.register(WatchScene.NAME, new WatchScene(ctx));

        // A book with something written in it: an empty one is a real state and
        // three lines of "nothing here yet", which proves nothing about the page.
        WatchView view = new WatchView();
        AnimalDef found = AnimalRegistry.all().get(17);
        view.guide().record(new Sighting(found.key(), 0L, 0.4,
                WatchBiomes.defaultBiome().key(), "Kara", 0, 0, true));
        book.show(view, WatchLobbyScene.NAME);

        for (String name : List.of(WatchLobbyScene.NAME, WatchGuideScene.NAME)) {
            scenes.setScene(name);
            InputManager input = new InputManager();
            for (int i = 0; i < 3; i++) {
                input.newFrame();
                scenes.update(1.0 / 120, input);
            }
            RecordingTarget target = new RecordingTarget(WIDTH, HEIGHT);
            scenes.render(target, 0f);
            assertTrue(target.commands().size() > 8,
                    name + " drew almost nothing: " + target.commands().size() + " calls");
        }
    }

    @Test
    void theLobbyOffersAWalkAloneAndAWalkTogether(@TempDir Path dir) {
        WatchLobbyScene lobby = new WatchLobbyScene(context(dir),
                new WatchStore(dir.resolve("walks").toString()));
        lobby.onEnter();

        List<String> labels = new ArrayList<>();
        for (MenuItem item : lobby.menu().items()) labels.add(item.text());
        String all = String.join(" | ", labels);

        assertTrue(all.contains("New Walk"), "there is no way to start one: " + all);
        assertTrue(all.contains("Host"), "there is no way to host one: " + all);
        assertTrue(all.contains("Join"), "there is no way to join one: " + all);
        assertTrue(all.contains(String.valueOf(WatchGame.MAX_PLAYERS)),
                "the lobby never says how many people can come: " + all);
        assertTrue(all.contains("Key Binds") || all.contains("Controls"),
                "the controls cannot be reached from the lobby: " + all);
    }

    @Test
    void aSavedWalkIsOfferedBackWhenThereIsOne(@TempDir Path dir) {
        WatchStore store = new WatchStore(dir.resolve("walks").toString());
        WatchGame game = new WatchGame(new WatchGame.Config(99L, "Yesterday", 8));
        game.join(1, "Kara");
        store.save(game);

        WatchLobbyScene lobby = new WatchLobbyScene(context(dir), store);
        lobby.onEnter();

        boolean offered = false;
        for (MenuItem item : lobby.menu().items()) {
            if (item.text().contains("Yesterday")) offered = true;
        }
        assertTrue(offered, "a saved walk was not offered back on the lobby's first screen");
    }

    @Test
    void theGuideListsSpeciesAndTurnsToOne(@TempDir Path dir) {
        WatchGuideScene guide = new WatchGuideScene(context(dir));
        WatchView view = new WatchView();
        guide.show(view, WatchLobbyScene.NAME);
        guide.onEnter();

        // An empty guide sorts by "recently found" and has found nothing, which
        // is a real state and an empty list.
        assertNotNull(guide.listing());

        AnimalDef def = AnimalRegistry.all().get(5);
        view.guide().record(new Sighting(def.key(), 0L, 0.4,
                WatchBiomes.defaultBiome().key(), "Kara", 0, 0, true));
        guide.show(view, WatchLobbyScene.NAME);

        assertFalse(guide.listing().isEmpty(), "a species was recorded and the book is empty");
        assertEquals(def, guide.selected(), "the book did not turn to what was just found");
    }

    /**
     * The walk, handed a solo session the way the lobby hands it one, streams a
     * world in and draws it — no window, no socket, no card.
     */
    @Test
    void theWalkDrawsTheWorldItIsGiven(@TempDir Path dir) {
        GameContext ctx = context(dir);
        WatchScene walk = new WatchScene(ctx);
        SceneManager scenes = new SceneManager();
        scenes.setViewport(WIDTH, HEIGHT);
        scenes.register(WatchLobbyScene.NAME, new WatchLobbyScene(ctx,
                new WatchStore(dir.resolve("walks").toString())));
        scenes.register(WatchScene.NAME, walk);

        WatchGame game = new WatchGame(new WatchGame.Config(2468L, "Test Walk", 1));
        game.join(1, "Kara");
        com.larsons.engine.watch.net.WatchSession session =
                com.larsons.engine.watch.net.WatchSession.solo(game);
        session.setSelfId(1);
        walk.adopt(session, new WatchStore(dir.resolve("walks").toString()));

        scenes.setScene(WatchScene.NAME);
        InputManager input = new InputManager();
        // The world arrives on the streamer's own workers, so this waits for it
        // rather than assuming a fixed number of frames is enough — on a slow
        // machine it is not, and a flaky test is worse than no test.
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            input.newFrame();
            scenes.update(1.0 / 60, input);
            if (walk.streamer() != null && walk.streamer().loadedCount() > 0) break;
            Thread.yield();
        }
        for (int i = 0; i < 5; i++) {
            input.newFrame();
            scenes.update(1.0 / 60, input);
        }

        assertNotNull(walk.session(), "the walk lost the session it was handed");
        assertNotNull(walk.view().self(), "the walk cannot find the player in it");
        assertNotNull(walk.streamer(), "no chunks are being streamed");

        RecordingTarget target = new RecordingTarget(WIDTH, HEIGHT);
        scenes.render(target, 0f);
        assertTrue(target.commands().size() > 20,
                "the walk drew " + target.commands().size() + " calls — there is no world");
        assertTrue(walk.renderer().submittedTriangles() > 50,
                "the walk submitted " + walk.renderer().submittedTriangles()
                        + " triangles — the world never streamed in");
        session.close();
    }

    // --- controls ---------------------------------------------------------------------

    @Test
    void theWalkHasItsOwnCategoryOfKeyBindsAndTheyDoNotClash() {
        List<GameAction> watch = new ArrayList<>();
        for (GameAction action : GameAction.values()) {
            if (action.category() == GameAction.Category.WATCH) watch.add(action);
        }
        assertTrue(watch.size() >= 8,
                "only " + watch.size() + " actions are bound for a whole game");

        assertEquals(watch, GameAction.in(GameAction.Category.WATCH),
                "the category listing disagrees with the actions' own category");

        // Two actions in the same category must not default to the same input,
        // or the walk starts with a conflict the player has to find and fix.
        Set<String> defaults = new TreeSet<>();
        for (GameAction action : watch) {
            assertTrue(action.label() != null && !action.label().isBlank(),
                    action + " has no label, so the key-bind screen cannot list it");
            assertFalse(action.defaults().isEmpty(), action + " is bound to nothing");
            for (var binding : action.defaults()) {
                assertTrue(binding.isBound(), action + " has an unbound default");
                assertTrue(defaults.add(binding.token()), action
                        + " defaults to " + binding.display()
                        + ", which another walk action already has");
            }
        }
    }

    /** The brief's verb: click on it. That has to be a mouse button, not a key. */
    @Test
    void spottingIsBoundToTheMouse() {
        boolean mouse = false;
        for (var binding : GameAction.WATCH_SPOT.defaults()) {
            if (binding.kind() == com.larsons.engine.input.InputBinding.Kind.MOUSE) mouse = true;
        }
        assertTrue(mouse, "spotting an animal is not bound to a mouse button, and the brief "
                + "asks for a click");
    }

    // --- what a texture pack can reach ---------------------------------------------------

    @Test
    void everyMaterialAndEveryFamilyIsInTheTextureCatalogue() {
        Set<String> known = new TreeSet<>();
        for (TextureKeys.Entry entry : TextureKeys.all()) known.add(entry.key());

        List<String> missing = new ArrayList<>();
        for (WatchMaterial material : WatchMaterial.values()) {
            if (!known.contains(material.textureKey())) missing.add(material.textureKey());
        }
        for (AnimalFamily family : AnimalFamily.values()) {
            if (!known.contains(family.textureKey())) missing.add(family.textureKey());
        }
        assertEquals(List.of(), missing,
                "a pack author cannot discover these keys: " + missing);
    }

    @Test
    void everyBiomeAndEveryFamilyCanBeNamedOnAPage() {
        for (WatchBiome biome : WatchBiomes.all()) {
            assertTrue(biome.displayName() != null && !biome.displayName().isBlank(),
                    biome.key() + " has no name to print");
        }
        for (AnimalFamily family : AnimalFamily.values()) {
            assertTrue(family.plural() != null && !family.plural().isBlank(),
                    family + " has no plural, and the guide groups by it");
        }
    }

    /** Three scenes, three names, and the launch tile opens one of them. */
    @Test
    void theThreeScenesHaveThreeDistinctNames() {
        Set<String> names = new TreeSet<>(
                List.of(WatchLobbyScene.NAME, WatchScene.NAME, WatchGuideScene.NAME));
        assertEquals(3, names.size(), "two of the three scenes share a name: " + names);

        StandaloneGame tile = null;
        for (StandaloneGame game : StandaloneGame.values()) {
            if ("field_guide".equals(game.key())) tile = game;
        }
        assertNotNull(tile);
        assertTrue(names.contains(tile.scene()),
                "the launch tile opens '" + tile.scene() + "', which the walk does not define");
    }
}
