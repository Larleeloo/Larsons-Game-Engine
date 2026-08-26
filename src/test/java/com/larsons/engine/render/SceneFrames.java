package com.larsons.engine.render;

import com.larsons.engine.config.GameContext;
import com.larsons.engine.config.GameProfile;
import com.larsons.engine.config.GameTypeStore;
import com.larsons.engine.demo.AutoBattlerGuideScene;
import com.larsons.engine.demo.AutoBattlerLobbyScene;
import com.larsons.engine.demo.BoardCustomizeScene;
import com.larsons.engine.demo.CreativeScene;
import com.larsons.engine.demo.EvolutionScene;
import com.larsons.engine.demo.PlayScene;
import com.larsons.engine.demo.DeckLobbyScene;
import com.larsons.engine.demo.EvolutionCatalogScene;
import com.larsons.engine.demo.EvolutionLobbyScene;
import com.larsons.engine.demo.GameTypeEditorScene;
import com.larsons.engine.demo.KeyBindsScene;
import com.larsons.engine.demo.LevelSelectScene;
import com.larsons.engine.demo.MainMenuScene;
import com.larsons.engine.demo.MultiplayerScene;
import com.larsons.engine.demo.NewLevelScene;
import com.larsons.engine.demo.SaveSelectScene;
import com.larsons.engine.demo.SkinEditorScene;
import com.larsons.engine.demo.StartupScene;
import com.larsons.engine.demo.WatchGuideScene;
import com.larsons.engine.demo.WatchLobbyScene;
import com.larsons.engine.evolution.EvolutionGame;
import com.larsons.engine.evolution.EvolutionStore;
import com.larsons.engine.evolution.Nucleotide;
import com.larsons.engine.graphics.SkinStore;
import com.larsons.engine.input.InputManager;
import com.larsons.engine.input.KeyBindStore;
import com.larsons.engine.render.GoldenFrames.Frame;
import com.larsons.engine.scene.Scene;
import com.larsons.engine.scene.SceneManager;
import com.larsons.engine.watch.Sighting;
import com.larsons.engine.watch.WatchStore;
import com.larsons.engine.watch.WatchView;
import com.larsons.engine.watch.world.WatchBiomes;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * The same instrument as {@link GoldenFrames}, pointed at whole scenes instead
 * of individual painters.
 *
 * <p><b>Why B3 needs its own catalogue.</b> B0's frames golden the shared
 * painters, which is what B2 changed. B3 changes the eighteen scenes that
 * <em>call</em> those painters — around two thousand drawing statements that
 * were reaching past the seam through the static unwrap B4 has since deleted.
 * None of
 * that code appears in any B0 frame, so B0 would have stayed green through a
 * port that moved every HUD element in the game by a pixel. A port claiming to
 * be a pure translation has to be checked against the thing it translated, and
 * that means rendering the scenes themselves.
 *
 * <p><b>What a scene frame is.</b> A scene, built from fixed inputs, registered
 * with a {@link SceneManager} at a fixed viewport, advanced a fixed number of
 * fixed-length ticks with no input, and rendered once. That is the whole
 * lifecycle the engine puts a scene through, minus the window.
 *
 * <p><b>Determinism is the hard part, and it decides what is in here.</b>
 * Everything on disk is redirected to a scratch directory built by
 * {@link #store()} and friends, so the pictures do not depend on what the
 * developer happens to have saved. Scenes that remain non-deterministic after
 * that — because they seed a simulation from the wall clock, or spawn from an
 * unseeded RNG — are listed in {@link #NOT_GOLDENABLE} with the reason, rather
 * than being goldened at a tolerance that would hide a real regression.
 * {@code SceneFramesTest} asserts that list is exhaustive: every scene in the
 * demo package is either goldened here or named there, so a nineteenth scene
 * cannot be added without a decision being made about it.
 */
public final class SceneFrames {

    private SceneFrames() {}

    /** Where scene references live, beside B0's. */
    public static final String RESOURCE_DIR = "src/test/resources/golden";

    /** Fixed viewport for every scene frame: 4:3, big enough for a full HUD. */
    public static final int WIDTH = 800;
    public static final int HEIGHT = 480;

    /** Seed for anything a scene would otherwise roll, shared with {@link GoldenFrames}. */
    private static final long SEED = GoldenFrames.SEED;

    /** The fixed tick every scene is advanced by, matching the engine's sim rate. */
    private static final double DT = 1.0 / 120.0;

    /**
     * Scenes deliberately left out of the catalogue, and why.
     *
     * <p>Shorter than it first looked. Four scenes were listed here on the
     * assumption that they roll from an unseeded RNG — PlayScene's mob spawns,
     * EvolutionScene's population, AutoBattlerScene's shop, DeckGameScene's
     * shuffle. Measuring instead of assuming showed PlayScene is already
     * deterministic from a fixed level, and EvolutionScene needed one seeded
     * game handed to the public {@code adopt} it already had. The two that
     * remain are excluded for a different reason than the one guessed, which
     * is the whole argument for checking.
     */
    public static final List<String> NOT_GOLDENABLE = List.of(
            // Both draw nothing at all until a network client hands them a
            // table: their render methods return immediately on a null client,
            // so a golden of either would be a golden of the backdrop. The
            // suite already drives both against a real loopback server
            // (AutoBattlerSceneTest, DeckGameTest); that is a live server with
            // live timing, which is the one thing a fixed picture cannot be.
            "AutoBattlerScene",
            "DeckGameScene",
            // The walk is lit by the player's own wall clock — that is the
            // feature, not an oversight — so its sky, its fog and every face in
            // it are a function of what time it is when the test runs. On top
            // of that its terrain arrives from a pool of streaming threads, so
            // how much world exists after two fixed ticks depends on how fast
            // the machine is. Neither is fixable without goldening something
            // that is not the scene. WatchSceneTest drives it instead, and
            // asserts on what it built rather than on how it looked.
            "WatchScene");

    // --- the catalogue ---------------------------------------------------------

    /**
     * Every goldenable scene, ordered smallest-first — the order B3 ports them,
     * so a failure list reads as "the port stopped being a translation here".
     */
    public static List<Frame> all() {
        List<Frame> frames = new ArrayList<>();

        frames.add(scene("scene-startup", ctx -> new StartupScene(ctx)));
        frames.add(scene("scene-multiplayer", ctx -> new MultiplayerScene(ctx, "")));
        frames.add(scene("scene-level-select", ctx -> new LevelSelectScene(ctx)));
        frames.add(scene("scene-key-binds",
                ctx -> new KeyBindsScene(new KeyBindStore(scratch("keybinds").toString()))));
        frames.add(scene("scene-game-type-editor", ctx -> new GameTypeEditorScene(ctx)));
        frames.add(scene("scene-new-level", ctx -> new NewLevelScene(ctx)));
        frames.add(scene("scene-main-menu", ctx -> new MainMenuScene(ctx)));
        frames.add(scene("scene-save-select", ctx -> new SaveSelectScene(ctx)));
        frames.add(scene("scene-evolution-lobby",
                ctx -> new EvolutionLobbyScene(ctx, evolutionStore())));
        frames.add(scene("scene-board-customize", ctx -> new BoardCustomizeScene(ctx)));
        frames.add(scene("scene-skin-editor",
                ctx -> new SkinEditorScene(ctx, new SkinStore(scratch("skins").toString()))));
        frames.add(scene("scene-auto-battler-lobby", ctx -> new AutoBattlerLobbyScene(ctx)));
        frames.add(scene("scene-deck-lobby", ctx -> new DeckLobbyScene(ctx)));
        frames.add(scene("scene-evolution-catalog",
                ctx -> new EvolutionCatalogScene(ctx, evolutionStore())));
        frames.add(scene("scene-auto-battler-guide", ctx -> new AutoBattlerGuideScene(ctx)));
        frames.add(scene("scene-watch-lobby",
                ctx -> new WatchLobbyScene(ctx, new WatchStore(scratch("watch").toString()))));
        frames.add(scene("scene-watch-guide", ctx -> {
            WatchGuideScene guide = new WatchGuideScene(ctx);
            guide.show(writtenGuide(), WatchLobbyScene.NAME);
            return guide;
        }));
        frames.add(scene("scene-creative", ctx -> new CreativeScene(ctx)));
        frames.add(scene("scene-play", ctx -> new PlayScene(ctx, SAMPLE_LEVEL)));
        frames.add(scene("scene-evolution", ctx -> {
            EvolutionScene scene = new EvolutionScene(ctx, evolutionStore());
            // Its onEnter rolls a game off an unseeded Random; adopt a seeded
            // one first so the dish, the organism and the orbs are the same
            // every run. Without this the frame differs from itself by 2.72.
            scene.adopt(EvolutionGame.newGame(Nucleotide.G, SEED));
            return scene;
        }));

        return frames;
    }

    /**
     * All of them, painters and scenes, which is what the comparison and the
     * draw-call table both want.
     */
    public static List<Frame> allFrames() {
        List<Frame> frames = new ArrayList<>(GoldenFrames.all());
        frames.addAll(all());
        return frames;
    }

    // --- building one ----------------------------------------------------------

    /** A scene frame at the standard viewport, advanced two ticks before drawing. */
    private static Frame scene(String name, Function<GameContext, Scene> factory) {
        return scene(name, factory, 2);
    }

    /**
     * Advancing before drawing is not optional: several scenes build their menu
     * or form in {@code onEnter} and then settle it over the first ticks — a
     * {@code ContainerPanel} at zero openness draws nothing at all, and B0 hit
     * exactly that. Two ticks is past every such easing and short of any
     * animation that would make the picture a function of how many frames were
     * run.
     */
    private static Frame scene(String name, Function<GameContext, Scene> factory, int ticks) {
        return new Frame(name, WIDTH, HEIGHT, target -> {
            GameContext ctx = context();
            Scene scene = factory.apply(ctx);

            SceneManager scenes = new SceneManager();
            scenes.setViewport(WIDTH, HEIGHT);
            scenes.register(name, scene);
            scenes.setScene(name);

            InputManager input = new InputManager();
            for (int i = 0; i < ticks; i++) {
                input.newFrame();
                scenes.update(DT, input);
            }
            scenes.render(target, 0f);
        });
    }

    // --- fixed inputs ----------------------------------------------------------

    /**
     * A context over a scratch game-type directory holding two fixed profiles.
     *
     * <p>Empty would be simpler and would also golden the empty-list branch of
     * every menu that lists game types, which is not the branch players see.
     * Two entries exercise the list and its selection highlight, and being
     * written here rather than read from {@code src/main/resources} means the
     * picture does not change when someone saves a game type locally.
     */
    private static GameContext context() {
        GameContext ctx = new GameContext(null, store());
        GameProfile profile = new GameProfile("Frostmarch");
        profile.perspective = com.larsons.engine.graphics.Perspective.SIDE_SCROLL;
        ctx.setProfile(profile);
        return ctx;
    }

    private static GameTypeStore store() {
        Path dir = scratch("gametypes");
        GameTypeStore store = new GameTypeStore(dir.toString());
        if (!Files.exists(dir.resolve(store.fileFor("Frostmarch").getFileName()))) {
            store.save(new GameProfile("Frostmarch"));
            GameProfile second = new GameProfile("Sunken Halls");
            second.perspective = com.larsons.engine.graphics.Perspective.THREE_D;
            store.save(second);
        }
        return store;
    }

    /** The level the engine ships, so the frame does not depend on a saved one. */
    private static final String SAMPLE_LEVEL = "src/main/resources/levels/sample_level.json";

    private static EvolutionStore evolutionStore() {
        return new EvolutionStore(scratch("evolution").toString());
    }

    /**
     * A field guide with a few pages written in it, for the book's frame.
     *
     * <p>An empty guide would golden the "nothing here yet" branch, which is
     * the screen a player sees for about ninety seconds and never again; a
     * written one goldens the list, the portrait, the record and the three
     * progress bars, which is the screen the feature actually is.
     *
     * <p><b>Every sighting is stamped zero on purpose.</b> {@code Sighting}
     * prints its moment in the machine's own time zone, so a real timestamp
     * would make this picture a function of where the developer lives — a red
     * test in Berlin for a change made in California. Zero takes the branch
     * that formats a fixed clock instead, which is the same drawing code
     * against an input that does not move.
     */
    private static WatchView writtenGuide() {
        WatchView view = new WatchView();
        String biome = WatchBiomes.defaultBiome().key();
        List<com.larsons.engine.watch.life.AnimalDef> all =
                com.larsons.engine.watch.life.AnimalRegistry.all();
        // Spread across the registry rather than taking the first six, so the
        // page shows more than one family and more than one rarity tier.
        for (int i = 0; i < 6; i++) {
            var def = all.get(i * 137 % all.size());
            view.guide().record(new Sighting(def.key(), 0L, 0.35, biome, "Kara",
                    12.5 * i, -8.25 * i, true));
        }
        view.guide().tame(all.get(0).key(), "Pip", "Kara", 0L);
        return view;
    }

    /**
     * A directory under {@code build/} for a store to read and write, created
     * once per run. Not {@code java.io.tmpdir}: a golden that depends on a path
     * length depends on the username, and this way the inputs are inspectable
     * after a failure instead of swept away.
     */
    private static Path scratch(String name) {
        Path dir = Path.of("build", "golden-scenes", name);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return dir;
    }

}
