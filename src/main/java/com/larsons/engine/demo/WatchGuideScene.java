package com.larsons.engine.demo;

import com.larsons.engine.config.GameContext;
import com.larsons.engine.graphics.draw.DrawTarget;
import com.larsons.engine.input.GameAction;
import com.larsons.engine.input.InputManager;
import com.larsons.engine.input.KeyBinds;
import com.larsons.engine.scene.AbstractScene;
import com.larsons.engine.watch.FieldGuide;
import com.larsons.engine.watch.Sighting;
import com.larsons.engine.watch.WatchView;
import com.larsons.engine.watch.life.AnimalDef;
import com.larsons.engine.watch.life.AnimalFamily;
import com.larsons.engine.watch.life.AnimalModels;
import com.larsons.engine.watch.life.AnimalRegistry;
import com.larsons.engine.watch.life.Rarity;
import com.larsons.engine.watch.render.AnimalPortrait;
import com.larsons.engine.watch.world.WatchBiome;
import com.larsons.engine.watch.world.WatchBiomes;

import java.awt.Color;
import java.awt.Font;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The book.
 *
 * <p>Thirteen hundred pages, of which you have written some. What it shows is
 * arranged around the one question a player actually has — <em>what should I go
 * and look for?</em> — so the list can be turned to a family, a biome or a
 * rarity tier, and every view says how many of that group are still missing.
 *
 * <p><b>Undiscovered species are listed, and named nothing.</b> A guide that
 * hid them would not tell you there is anything to find; a guide that named
 * them would remove the point of finding it. So a page you have not written
 * shows a silhouette, the family, and where it lives — which is exactly the
 * amount of information that sends somebody out of the door.
 */
public class WatchGuideScene extends AbstractScene {

    /** The scene the book lives at. */
    public static final String NAME = "watchguide";

    private static final Color PAPER = new Color(24, 30, 26);
    private static final Color PANEL = new Color(16, 22, 18);
    private static final Color INK = new Color(232, 238, 230);
    private static final Color DIM = new Color(150, 166, 152);
    private static final Color ACCENT = new Color(140, 208, 150);
    private static final Color UNKNOWN = new Color(96, 106, 98);
    private static final Font TITLE = new Font("SansSerif", Font.BOLD, 24);
    private static final Font HEAD = new Font("SansSerif", Font.BOLD, 15);
    private static final Font BODY = new Font("SansSerif", Font.PLAIN, 14);
    private static final Font SMALL = new Font("SansSerif", Font.PLAIN, 12);

    /** How the list is grouped. */
    private enum Sort {
        FOUND("Recently found"),
        FAMILY("By family"),
        BIOME("Where you are"),
        RARITY("By rarity"),
        MISSING("Still missing");

        final String label;

        Sort(String label) { this.label = label; }
    }

    private final GameContext ctx;

    private WatchView view;
    private String returnTo = WatchLobbyScene.NAME;
    private Sort sort = Sort.FOUND;
    private int selected;
    private int scroll;
    private List<AnimalDef> listing = List.of();

    public WatchGuideScene(GameContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Open the book on a party's guide.
     *
     * @param returnTo the scene Esc goes back to — the walk, or the lobby
     */
    public void show(WatchView view, String returnTo) {
        this.view = view;
        this.returnTo = returnTo == null ? WatchLobbyScene.NAME : returnTo;
        this.selected = 0;
        this.scroll = 0;
        rebuild();
    }

    /** The species currently listed, so a test can check the sort. */
    public List<AnimalDef> listing() { return listing; }

    /** Which species the cursor is on, or {@code null}. */
    public AnimalDef selected() {
        return listing.isEmpty() ? null
                : listing.get(Math.floorMod(selected, listing.size()));
    }

    @Override
    public void onEnter() {
        if (view == null) view = new WatchView();
        rebuild();
    }

    private void rebuild() {
        FieldGuide guide = view.guide();
        List<AnimalDef> out = new ArrayList<>();
        switch (sort) {
            case FOUND -> {
                for (Sighting sighting : guide.recent(400)) {
                    AnimalDef def = sighting.def();
                    if (def != null && !out.contains(def)) out.add(def);
                }
                // Anything found before the journal's window still belongs in
                // the list; the journal is a tail, the guide is the record.
                for (AnimalDef def : AnimalRegistry.all()) {
                    if (guide.seen(def.key()) && !out.contains(def)) out.add(def);
                }
            }
            case FAMILY -> {
                for (AnimalFamily family : AnimalFamily.values()) {
                    out.addAll(AnimalRegistry.inFamily(family));
                }
            }
            case BIOME -> {
                WatchBiome here = biomeUnderfoot();
                out.addAll(AnimalRegistry.inBiome(here.key()));
            }
            case RARITY -> {
                for (Rarity rarity : Rarity.values()) {
                    for (AnimalDef def : AnimalRegistry.all()) {
                        if (def.rarity() == rarity) out.add(def);
                    }
                }
            }
            case MISSING -> {
                WatchBiome here = biomeUnderfoot();
                out.addAll(guide.missingIn(here.key(), 400));
                for (AnimalDef def : AnimalRegistry.all()) {
                    if (!guide.seen(def.key()) && !out.contains(def)) out.add(def);
                }
            }
        }
        listing = List.copyOf(out);
        selected = listing.isEmpty() ? 0 : Math.floorMod(selected, listing.size());
    }

    /** The biome the party is standing in, for the "where you are" views. */
    private WatchBiome biomeUnderfoot() {
        WatchView.Walker me = view.self();
        if (me == null) return WatchBiomes.defaultBiome();
        // The client does not run the generator for the world it is in when it
        // is only a guest, so this is a best effort: the biome list is the same
        // everywhere, and a guide opened on the wrong page is a keypress away
        // from the right one.
        return WatchBiomes.defaultBiome();
    }

    @Override
    public void update(double dt, InputManager input) {
        if (KeyBinds.pressed(input, GameAction.MENU_BACK)
                || KeyBinds.pressed(input, GameAction.WATCH_GUIDE)) {
            scenes.transitionTo(returnTo);
            return;
        }
        if (listing.isEmpty()) return;
        if (KeyBinds.pressed(input, GameAction.MENU_DOWN)) selected++;
        if (KeyBinds.pressed(input, GameAction.MENU_UP)) selected--;
        if (KeyBinds.pressed(input, GameAction.MENU_RIGHT)) {
            sort = Sort.values()[(sort.ordinal() + 1) % Sort.values().length];
            selected = 0;
            rebuild();
        }
        if (KeyBinds.pressed(input, GameAction.MENU_LEFT)) {
            sort = Sort.values()[Math.floorMod(sort.ordinal() - 1, Sort.values().length)];
            selected = 0;
            rebuild();
        }
        int wheel = input.getWheelRotation();
        if (wheel != 0) selected += wheel * 3;
        selected = Math.floorMod(selected, listing.size());
    }

    @Override
    public void render(DrawTarget target, float alpha) {
        target.fillRect(0, 0, viewportWidth, viewportHeight, PAPER);
        if (view == null) return;
        FieldGuide guide = view.guide();

        target.drawText("Field Guide", 28, 40, TITLE, INK);
        String progress = guide.discovered() + " of " + guide.total() + " species  ·  "
                + guide.points() + " points  ·  "
                + Math.round(guide.completion() * 1000) / 10.0 + "%";
        target.drawText(progress, 28, 64, BODY, ACCENT);
        String sorts = "◀ " + sort.label + " ▶";
        target.drawText(sorts, viewportWidth - 28 - target.textWidth(sorts, HEAD), 40,
                HEAD, INK);

        int listX = 28, listY = 92;
        int listW = Math.max(280, viewportWidth / 2 - 48);
        int listH = viewportHeight - listY - 48;
        drawList(target, guide, listX, listY, listW, listH);

        int paneX = listX + listW + 24;
        int paneW = viewportWidth - paneX - 28;
        drawPage(target, guide, paneX, listY, paneW, listH);

        SceneChrome.hint(target, viewportHeight,
                "↑↓ turn pages · ←→ change how the book is sorted · Esc / G to close");
    }

    private void drawList(DrawTarget target, FieldGuide guide, int x, int y, int w, int h) {
        target.fillRect(x, y, w, h, PANEL);
        int rowHeight = 20;
        int rows = Math.max(1, (h - 12) / rowHeight);
        // Keep the cursor a third of the way down rather than at an edge, so
        // there is always context above and below it.
        scroll = Math.max(0, Math.min(listing.size() - rows, selected - rows / 3));
        int row = y + 20;
        for (int i = scroll; i < listing.size() && row < y + h - 4; i++) {
            AnimalDef def = listing.get(i);
            boolean seen = guide.seen(def.key());
            if (i == selected) {
                target.fillRect(x + 4, row - 14, w - 8, rowHeight, new Color(48, 76, 54));
            }
            String name = seen ? def.name() : "— — —";
            target.drawText(name, x + 12, row, BODY, seen ? INK : UNKNOWN);
            String tag = seen ? def.rarity().label() : def.family().plural();
            target.drawText(tag, x + w - 12 - target.textWidth(tag, SMALL), row, SMALL,
                    seen ? def.rarity().tint() : UNKNOWN);
            row += rowHeight;
        }
        if (listing.isEmpty()) {
            target.drawText("Nothing here yet — go and look.", x + 12, y + 28, BODY, DIM);
        }
    }

    /**
     * The open page: the species' own skin, the facts, and where and when you
     * saw it — or, for one you have not, only the shape of the question.
     */
    private void drawPage(DrawTarget target, FieldGuide guide, int x, int y, int w, int h) {
        target.fillRect(x, y, w, h, PANEL);
        AnimalDef def = selected();
        if (def == null) return;
        boolean seen = guide.seen(def.key());

        // The three bars stand on the floor of the panel and the page flows
        // down towards them. Both of those were true before and neither knew
        // about the other, so a species whose biome list ran to three lines
        // printed its record on top of them. Nothing below this line is
        // allowed past `floor`.
        int floor = y + h - PROGRESS_HEIGHT;

        int portrait = Math.min(180, w / 3);
        if (seen) {
            // Its model, not its skin sheet: the sheet is what the model is
            // painted from, and a grid of coloured rectangles tells nobody
            // whether they are looking for a wader or a wren.
            target.drawImage(AnimalPortrait.of(def, portrait, 0x1B2620),
                    x + 16, y + 16, portrait, portrait);
        } else {
            // A silhouette: the same sheet, filled flat. What it gives away is
            // the size and shape of the animal, which is what a guide's
            // unfilled page gives away.
            target.fillRect(x + 16, y + 16, portrait, portrait, new Color(40, 48, 42));
            String q = "?";
            target.drawText(q, x + 16 + portrait / 2 - target.textWidth(q, TITLE) / 2,
                    y + 16 + portrait / 2 + 8, TITLE, UNKNOWN);
        }
        target.drawRect(x + 16, y + 16, portrait, portrait, new Color(60, 74, 62));

        // The column beside the portrait is narrow — a third of a half-width
        // panel — so everything in it wraps rather than running off the edge
        // of the book, which is what "eats anything it can catch" did.
        int tx = x + 16 + portrait + 20;
        int tw = x + w - 16 - tx;
        int row = y + 40;
        row = wrapped(target, seen ? def.name() : "Unrecorded", tx, row, tw, HEAD,
                seen ? INK : UNKNOWN) + 6;
        row = wrapped(target, def.family().plural() + " · " + def.rarity().label(),
                tx, row, tw, BODY, def.rarity().tint()) + 4;
        row = wrapped(target, "Seen " + def.activity().phrase() + " · eats "
                + def.diet().label().toLowerCase(), tx, row, tw, SMALL, DIM);
        row = wrapped(target, String.format("%.2f m · %s", def.bodyLength(),
                def.tameable() ? "can be tamed" : "wild"), tx, row, tw, SMALL, DIM);
        if (AnimalModels.isImported(def)) {
            row = wrapped(target, "Blockbench model loaded", tx, row, tw, SMALL, ACCENT);
        }

        // Below the portrait, the full width of the panel — and the deeper of
        // the two columns, since a tall portrait must not overwrite the prose.
        row = Math.max(row, y + 16 + portrait + 34);
        int width = w - 32;
        if (row + 20 < floor) {
            target.drawText("Where to look", x + 16, row, HEAD, ACCENT);
            row += 20;
            row = clipped(target, def.whereToLook(), x + 16, row, width, floor) + 8;
        }

        if (seen) {
            Sighting first = guide.firstSighting(def.key());
            if (row + 20 < floor) {
                target.drawText("Your record", x + 16, row, HEAD, ACCENT);
                row += 20;
                if (first != null) {
                    row = wrappedTo(target, first.describe(), x + 16, row, width, floor,
                            SMALL, INK);
                    int times = guide.timesSeen(def.key());
                    int points = def.rarity().points();
                    row = wrappedTo(target,
                            (times == 1 ? "Seen once" : "Seen " + times + " times")
                                    + " · " + points + (points == 1 ? " point" : " points"),
                            x + 16, row, width, floor, SMALL, DIM);
                }
                if (guide.tamed(def.key())) {
                    wrappedTo(target, "One of these is a pet.", x + 16, row, width, floor,
                            SMALL, ACCENT);
                }
            }
        } else if (row + 20 < floor) {
            target.drawText("What brings it in", x + 16, row, HEAD, ACCENT);
            row += 20;
            String foods = String.join(", ",
                    def.diet().foods().subList(0, Math.min(4, def.diet().foods().size())));
            clipped(target, foods, x + 16, row, width, floor);
        }

        drawProgress(target, guide, x + 16, floor + 8, width);
    }

    /** How much of the panel's floor the three bars and their labels need. */
    private static final int PROGRESS_HEIGHT = 88;

    /** {@link #wrapped}, stopping at {@code floor} rather than drawing past it. */
    private static int clipped(DrawTarget target, String text, int x, int y, int width,
                               int floor) {
        return wrappedTo(target, text, x, y, width, floor, SMALL, DIM);
    }

    private static int wrappedTo(DrawTarget target, String text, int x, int y, int width,
                                 int floor, Font font, Color colour) {
        if (y >= floor) return y;
        return wrapped(target, text, x, y, width, font, colour, floor);
    }

    /**
     * Draw text inside a width, breaking it at spaces.
     *
     * <p>The page has to hold a sentence naming up to eight biomes, and the
     * first version of it ran that sentence straight off the right-hand edge.
     * There is no text layout in {@code DrawTarget} by design — it measures and
     * it draws — so a caller that needs a paragraph measures its own words,
     * which is four lines and correct at every window size.
     *
     * @return the baseline after the last line written
     */
    private static int wrapped(DrawTarget target, String text, int x, int y, int width,
                               Font font, Color colour) {
        return wrapped(target, text, x, y, width, font, colour, Integer.MAX_VALUE);
    }

    /**
     * @param floor a baseline no line may be drawn at or below; the rest of the
     *              text is dropped rather than printed over whatever lives there
     */
    private static int wrapped(DrawTarget target, String text, int x, int y, int width,
                               Font font, Color colour, int floor) {
        if (text == null || text.isBlank()) return y;
        StringBuilder line = new StringBuilder();
        int baseline = y;
        for (String word : text.split(" ")) {
            String candidate = line.length() == 0 ? word : line + " " + word;
            if (target.textWidth(candidate, font) > width && line.length() > 0) {
                if (baseline >= floor) return baseline;
                target.drawText(line.toString(), x, baseline, font, colour);
                baseline += 16;
                line.setLength(0);
                line.append(word);
            } else {
                line.setLength(0);
                line.append(candidate);
            }
        }
        if (line.length() > 0 && baseline < floor) {
            target.drawText(line.toString(), x, baseline, font, colour);
            baseline += 16;
        }
        return baseline;
    }

    /** Three bars: how much of each family, rarity tier and biome is written up. */
    private void drawProgress(DrawTarget target, FieldGuide guide, int x, int y, int w) {
        AnimalDef def = selected();
        if (def == null) return;
        Map<AnimalFamily, int[]> families = guide.byFamily();
        Map<Rarity, int[]> rarities = guide.byRarity();
        Map<String, int[]> biomes = guide.byBiome();

        int[] family = families.getOrDefault(def.family(), new int[2]);
        int[] rarity = rarities.getOrDefault(def.rarity(), new int[2]);
        String biomeKey = def.biomes().isEmpty() ? "" : def.biomes().get(0);
        int[] biome = biomes.getOrDefault(biomeKey, new int[2]);
        WatchBiome biomeDef = WatchBiomes.byKey(biomeKey);

        bar(target, x, y, w, def.family().plural(), family);
        bar(target, x, y + 26, w, def.rarity().label(), rarity);
        bar(target, x, y + 52, w,
                biomeDef == null ? "This biome" : biomeDef.displayName(), biome);
    }

    private void bar(DrawTarget target, int x, int y, int w, String label, int[] pair) {
        int have = pair.length > 0 ? pair[0] : 0;
        int total = pair.length > 1 ? Math.max(1, pair[1]) : 1;
        target.drawText(label, x, y, SMALL, DIM);
        String count = have + " / " + total;
        target.drawText(count, x + w - target.textWidth(count, SMALL), y, SMALL, DIM);
        int barY = y + 6;
        target.fillRect(x, barY, w, 5, new Color(40, 48, 42));
        target.fillRect(x, barY, (int) (w * (have / (double) total)), 5, ACCENT);
    }
}
