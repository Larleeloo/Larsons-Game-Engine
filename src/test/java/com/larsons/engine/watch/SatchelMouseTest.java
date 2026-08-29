package com.larsons.engine.watch;

import com.larsons.engine.config.GameContext;
import com.larsons.engine.config.GameTypeStore;
import com.larsons.engine.demo.WatchLobbyScene;
import com.larsons.engine.demo.WatchScene;
import com.larsons.engine.graphics.draw.RecordingTarget;
import com.larsons.engine.input.InputManager;
import com.larsons.engine.scene.SceneManager;
import com.larsons.engine.watch.net.WatchSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cooking screen can be worked entirely with the mouse.
 *
 * <p>It could not. It had a cursor, two columns and a scroll window, and the
 * only things that moved any of them were ↑, ↓, ← and → — in a game whose every
 * other verb is on the mouse, and on a list that runs to forty rows of
 * inventory beside twenty of recipes. This is the contract that replaced that:
 * hovering selects, clicking acts, the wheel scrolls the column it is over, and
 * there is a ✕. The keys still do everything they did, which the last test
 * here checks.
 *
 * <p>Nothing here opens a window: the scene is built over a scratch context,
 * ticked with a synthetic {@link InputManager}, and drawn to a
 * {@link RecordingTarget}.
 */
@Timeout(180)
class SatchelMouseTest {

    private static final int WIDTH = 900, HEIGHT = 620;

    /** A walk with a full satchel, standing still, with the satchel screen up. */
    private static final class Walk implements AutoCloseable {
        final WatchScene scene;
        final SceneManager scenes;
        final InputManager input = new InputManager();
        final WatchSession session;
        final WatchGame game;

        Walk(Path dir) {
            GameContext ctx = new GameContext(null, new GameTypeStore(dir.toString()));
            scene = new WatchScene(ctx);
            scenes = new SceneManager();
            scenes.setViewport(WIDTH, HEIGHT);
            scenes.register(WatchLobbyScene.NAME, new WatchLobbyScene(ctx,
                    new WatchStore(dir.resolve("w").toString())));
            scenes.register(WatchScene.NAME, scene);

            game = new WatchGame(new WatchGame.Config(97531L, "Mouse", 1));
            WatchPlayer me = game.join(1, "Kara");
            // Enough kinds that the carrying list has to scroll.
            int n = 0;
            for (Forage.Item item : Forage.all()) {
                if (n++ % 2 == 0) me.satchel().add(item.key(), 1 + n % 5);
            }
            session = WatchSession.solo(game);
            session.setSelfId(1);
            scene.adopt(session, new WatchStore(dir.resolve("w").toString()));
            scenes.setScene(WatchScene.NAME);
            tick(3);
        }

        void tick(int frames) {
            for (int i = 0; i < frames; i++) {
                input.newFrame();
                scenes.update(1.0 / 60, input);
            }
        }

        /** Open the satchel screen with the key the HUD says opens it. */
        void openSatchel() {
            input.newFrame();
            input.pressKey(KeyEvent.VK_TAB, 0);
            scenes.update(1.0 / 60, input);
            input.newFrame();
            input.releaseKey(KeyEvent.VK_TAB);
            scenes.update(1.0 / 60, input);
        }

        /** One frame with the pointer somewhere. */
        void hover(int x, int y) {
            input.newFrame();
            input.moveMouse(x, y);
            scenes.update(1.0 / 60, input);
        }

        /** One frame with the pointer somewhere and the button going down. */
        void click(int x, int y) {
            input.newFrame();
            input.moveMouse(x, y);
            input.pressMouse(MouseEvent.BUTTON1, 0);
            scenes.update(1.0 / 60, input);
            input.newFrame();
            input.releaseMouse(MouseEvent.BUTTON1);
            scenes.update(1.0 / 60, input);
        }

        /**
         * One frame with the wheel having turned.
         *
         * <p>The turn is latched <em>before</em> {@code newFrame}, because that
         * is the call that promotes accumulated events into the tick a scene
         * reads — which is also how the real window delivers it.
         */
        void wheel(int notches) {
            input.scroll(notches);
            input.newFrame();
            scenes.update(1.0 / 60, input);
        }

        @Override public void close() { session.close(); }
    }

    @Test
    void hoveringDownTheCarryingListWalksItsRowsInOrder(@TempDir Path dir) {
        try (Walk walk = new Walk(dir)) {
            walk.openSatchel();
            assertEquals("satchel", walk.scene.panelName());

            List<String> hovered = new ArrayList<>();
            // Down the middle of the left column, which is where the carrying
            // list is however wide the window happens to be.
            for (int y = 0; y < HEIGHT; y += 4) {
                walk.hover(WIDTH / 4, y);
                String at = walk.scene.panelCursor();
                if (at != null && (hovered.isEmpty()
                        || !at.equals(hovered.get(hovered.size() - 1)))) {
                    hovered.add(at);
                }
            }
            assertTrue(hovered.size() >= 6,
                    "sweeping the pointer down the list selected " + hovered.size()
                            + " different rows — hovering does not select");

            // And they came out in the satchel's own order, which is what says
            // the hit boxes line up with the rows that were drawn rather than
            // merely changing when the pointer moves.
            List<String> keys = walk.scene.view().satchel().keys();
            int last = -1;
            for (String key : hovered) {
                int at = keys.indexOf(key);
                assertTrue(at > last,
                        "hovering picked " + key + " out of order — the rows the panel"
                                + " draws are not the rows it hit-tests");
                last = at;
            }
        }
    }

    @Test
    void theWheelScrollsWhicheverColumnThePointerIsOver(@TempDir Path dir) {
        try (Walk walk = new Walk(dir)) {
            walk.openSatchel();
            walk.hover(WIDTH / 4, HEIGHT / 2);
            int before = walk.scene.panelScroll();
            String cursorBefore = walk.scene.panelCursor();

            walk.wheel(4);
            int after = walk.scene.panelScroll();
            assertTrue(after > before,
                    "the wheel did nothing to the carrying list: " + before + " → " + after);
            // Scrolling is reading, not walking: the cursor stays where it was.
            assertEquals(cursorBefore, walk.scene.panelCursor(),
                    "the wheel moved the cursor as well as the window");

            walk.wheel(-99);
            assertEquals(0, walk.scene.panelScroll(),
                    "scrolling back past the top left the window off the list");
        }
    }

    @Test
    void clickingARowDoesWhatEnterWouldHaveDone(@TempDir Path dir) {
        try (Walk walk = new Walk(dir)) {
            walk.openSatchel();
            // Into the recipe column, and onto whatever row is under the
            // pointer there.
            walk.hover(WIDTH * 3 / 4, HEIGHT / 2);
            String recipe = walk.scene.panelCursor();
            assertNotNull(recipe, "the pointer over the recipe column selected nothing");
            assertNotNull(Recipes.making(recipe),
                    "the cursor is on " + recipe + ", which is not a recipe — the"
                            + " pointer landed in the wrong column");

            int before = walk.scene.view().log().size();
            walk.click(WIDTH * 3 / 4, HEIGHT / 2);
            assertTrue(walk.scene.view().log().size() > before,
                    "clicking a recipe did nothing at all — not even 'not enough for that'");
        }
    }

    @Test
    void theCloseButtonClosesIt(@TempDir Path dir) {
        try (Walk walk = new Walk(dir)) {
            walk.openSatchel();
            assertEquals("satchel", walk.scene.panelName());
            // The ✕ sits in the panel's top right corner. The panel is centred
            // and sized as WatchScene.satchelBox says; this is that corner.
            int w = Math.min(880, Math.max(320, WIDTH - 80));
            int h = Math.min(560, Math.max(240, HEIGHT - 80));
            int x = (WIDTH - w) / 2, y = (HEIGHT - h) / 2;
            walk.click(x + w - 25, y + 25);
            assertEquals("none", walk.scene.panelName(),
                    "there is no way to shut the cooking screen with the mouse");
        }
    }

    /**
     * <b>The keys still do everything.</b> A mouse is an addition here, not a
     * replacement: somebody who never touches it must still be able to walk the
     * list, cross to the recipes and make one.
     */
    @Test
    void theArrowKeysStillDriveBothColumns(@TempDir Path dir) {
        try (Walk walk = new Walk(dir)) {
            walk.openSatchel();
            String first = walk.scene.panelCursor();
            press(walk, KeyEvent.VK_DOWN);
            assertNotEquals(first, walk.scene.panelCursor(), "↓ did not move the cursor");

            press(walk, KeyEvent.VK_RIGHT);
            String recipe = walk.scene.panelCursor();
            assertNotNull(Recipes.making(recipe), "→ did not cross to the recipes");

            press(walk, KeyEvent.VK_DOWN);
            assertNotEquals(recipe, walk.scene.panelCursor(),
                    "↓ does nothing in the recipe column");

            press(walk, KeyEvent.VK_LEFT);
            assertTrue(walk.scene.view().satchel().has(walk.scene.panelCursor()),
                    "← did not cross back to what we are carrying");

            press(walk, KeyEvent.VK_TAB);
            assertEquals("none", walk.scene.panelName(), "Tab no longer closes it");
        }
    }

    /**
     * Holding the pointer still over a row must not fight the arrow keys: a
     * mouse sitting on the desk re-selected the row under it every frame, so ↓
     * moved the cursor for one frame and it sprang back.
     */
    @Test
    void aRestingPointerDoesNotUndoTheArrowKeys(@TempDir Path dir) {
        try (Walk walk = new Walk(dir)) {
            walk.openSatchel();
            walk.hover(WIDTH / 4, HEIGHT / 2);
            String under = walk.scene.panelCursor();
            press(walk, KeyEvent.VK_DOWN);
            String moved = walk.scene.panelCursor();
            assertNotEquals(under, moved, "↓ did not move the cursor at all");
            // Several frames of the pointer not moving.
            walk.tick(5);
            assertEquals(moved, walk.scene.panelCursor(),
                    "the resting pointer dragged the cursor back to the row under it");
        }
    }

    private static void press(Walk walk, int key) {
        walk.input.newFrame();
        walk.input.pressKey(key, 0);
        walk.scenes.update(1.0 / 60, walk.input);
        walk.input.newFrame();
        walk.input.releaseKey(key);
        walk.scenes.update(1.0 / 60, walk.input);
    }
}
