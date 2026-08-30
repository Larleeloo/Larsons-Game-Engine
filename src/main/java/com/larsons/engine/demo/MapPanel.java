package com.larsons.engine.demo;

import com.larsons.engine.graphics.draw.DrawTarget;
import com.larsons.engine.input.InputManager;
import com.larsons.engine.ui.UiText;
import com.larsons.engine.watch.Cartography;
import com.larsons.engine.watch.Chart;
import com.larsons.engine.watch.WatchView;
import com.larsons.engine.watch.render.ChartImage;
import com.larsons.engine.watch.render.MapInk;
import com.larsons.engine.watch.world.TerrainField;

import java.awt.Color;
import java.awt.Font;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * The screen a map is read and written on — and, with more than one map, the
 * screen a board is read on.
 *
 * <h2>One panel, two things to look at</h2>
 *
 * <p>A map and a board are the same picture with a different set of maps in it:
 * a map is one, a board is however many are pinned to it. Everything else — the
 * pen, the notes, the icons, the walkers, the scale — is identical, so this is
 * one class with a {@linkplain #openBoard board} door and a
 * {@linkplain #openChart map} door rather than two panels that would drift
 * apart the first time somebody fixed the pen on one of them.
 *
 * <p>The consequence worth stating: <b>everything is drawn in world
 * coordinates.</b> The frame is a rectangle of the world mapped onto a
 * rectangle of the screen, and every map, icon, stroke and walker is put where
 * its metres say. That is what makes a board a larger map rather than a
 * collage: two maps of neighbouring country land side by side with no join to
 * compute, because neither of them was ever drawn relative to itself.
 *
 * <h2>Its own palette, on purpose</h2>
 *
 * <p>The rest of the game's panels are the dark green of a satchel screen. This
 * one is paper. A map is the one object in the game a player made rather than
 * found, and it should not look like the rest of the interface — it should look
 * like the thing it is.
 */
public final class MapPanel {

    // --- paper ------------------------------------------------------------------------

    private static final Color BACKDROP = new Color(8, 12, 10, 200);
    private static final Color PAPER = new Color(226, 214, 186);
    private static final Color PAPER_EDGE = new Color(120, 102, 74);
    private static final Color PAPER_DEEP = new Color(198, 183, 152);
    private static final Color INK = new Color(38, 32, 24);
    private static final Color INK_DIM = new Color(96, 85, 66);
    private static final Color UNSURVEYED = new Color(206, 192, 160);
    private static final Color TILE_LINE = new Color(120, 102, 74, 70);
    private static final Color SELF_PIN = new Color(232, 240, 236);
    private static final Color OTHER_PIN = new Color(84, 132, 196);

    private static final Font TITLE = new Font("SansSerif", Font.BOLD, 20);
    private static final Font BODY = new Font("SansSerif", Font.PLAIN, 14);
    private static final Font BOLD = new Font("SansSerif", Font.BOLD, 13);
    private static final Font SMALL = new Font("SansSerif", Font.PLAIN, 12);

    /** How wide the strip down the right-hand side is, in pixels. */
    private static final int STRIP = 214;

    /** How far in from the viewport edge the panel sits. */
    private static final int INSET = 40;

    /** How far apart two points of one stroke have to be to be worth keeping, in pixels. */
    private static final double STROKE_STEP = 3;

    /** How near the pointer a mark has to be for the eraser to take it, in pixels. */
    private static final double ERASER_PIXELS = 12;

    /** How far in or out the wheel takes the view per notch. */
    private static final double ZOOM_STEP = 1.25;

    private static final double MIN_ZOOM = 1, MAX_ZOOM = 12;

    /** How large a walker's pin is drawn, in pixels. */
    private static final int PIN = 11;

    /** What the panel asks the world to do. Every one of these is a request. */
    public interface Sink {

        /** Lay a pen stroke on a map, in world metres. */
        void mark(long chartId, int ink, double[] xs, double[] ys);

        /** Write a few words on a map. */
        void note(long chartId, int ink, double x, double y, String text);

        /** Rub one mark off a map. */
        void erase(long chartId, long markId);

        /** Pin a map to the open board, or pass {@code 0} to take it back. */
        void pin(long chartId, long boardId);
    }

    /** What the pointer does on the paper. */
    public enum Tool {
        PEN("Pen", "Drag to draw"),
        NOTE("Note", "Click, type, Enter"),
        ERASER("Eraser", "Click a mark to rub it out"),
        HAND("Hand", "Drag to move the paper");

        private final String label;
        private final String hint;

        Tool(String label, String hint) {
            this.label = label;
            this.hint = hint;
        }

        public String label() { return label; }

        public String hint() { return hint; }
    }

    private long chartId;
    private long boardId;
    private boolean open;

    private Tool tool = Tool.PEN;
    private int ink;

    private double zoom = 1;
    private double panX, panY;

    /** The stroke being drawn, in world metres, while the button is down. */
    private final List<double[]> pending = new ArrayList<>();

    private int lastPointerX = -1, lastPointerY = -1;
    private boolean drawing;
    private boolean panning;

    /** Where a pan drag was last frame. */
    private int dragFromX, dragFromY;

    /** Where a note is being typed, and what has been typed. {@code null} for none. */
    private double[] noteAt;
    private final StringBuilder noteText = new StringBuilder();

    /** Open on one map. */
    public void openChart(long chartId) {
        this.chartId = chartId;
        this.boardId = 0;
        reset();
    }

    /** Open on a board, and everything pinned to it. */
    public void openBoard(long boardId) {
        this.boardId = boardId;
        this.chartId = 0;
        reset();
    }

    private void reset() {
        open = true;
        zoom = 1;
        panX = 0;
        panY = 0;
        pending.clear();
        drawing = false;
        panning = false;
        noteAt = null;
        noteText.setLength(0);
    }

    /** Whether the panel is up. */
    public boolean open() { return open; }

    /** Which board it is showing, or {@code 0} for a single map. */
    public long boardId() { return boardId; }

    /** Which map it is showing, or {@code 0} for a board. */
    public long chartId() { return chartId; }

    /** Whether a note is half typed, so the walk knows not to read the keyboard. */
    public boolean typing() { return noteAt != null; }

    public void close() {
        open = false;
        pending.clear();
        noteAt = null;
    }

    /**
     * Which map the pen writes on.
     *
     * <p>On a board there are several, and the answer is <b>whichever one is
     * under the pointer</b> — which is the only answer that does not need a
     * selection step. Draw across the join between two maps and the stroke is
     * kept by the one the pen started on, so a line drawn over the seam stays
     * one line rather than being cut in half. It travels with that map: unpin it
     * and your annotation goes with the paper it was drawn on, which is what
     * happens when you take a page off a wall.
     */
    private Chart penTarget(WatchView view, double worldX, double worldY) {
        if (boardId == 0) return view.maps().chart(chartId);
        Chart best = null;
        for (Chart chart : view.maps().pinnedTo(boardId)) {
            if (!chart.covers(worldX, worldY)) continue;
            // The smallest map covering the point, so a detailed map laid over
            // an overview takes the ink rather than the sheet underneath it.
            if (best == null || chart.radius() < best.radius()) best = chart;
        }
        return best;
    }

    /** Every map this panel is showing, in the order they should be drawn. */
    private List<Chart> sheets(WatchView view) {
        if (boardId != 0) {
            // Largest first, so a detailed map of one corner lies on top of the
            // overview it belongs to rather than under it.
            List<Chart> pinned = new ArrayList<>(view.maps().pinnedTo(boardId));
            pinned.sort((a, b) -> Double.compare(b.radius(), a.radius()));
            return pinned;
        }
        Chart chart = view.maps().chart(chartId);
        return chart == null ? List.of() : List.of(chart);
    }

    // --- the tick ---------------------------------------------------------------------

    /**
     * Read the pointer and the keyboard.
     *
     * <p>Closes itself when whatever it is showing has gone — a map somebody
     * else unpinned, a board that is no longer there — for
     * {@code WatchScene.updateShop}'s reason: a panel that outlived its subject
     * would be a screen whose every button silently did nothing.
     */
    public void update(InputManager input, WatchView view, TerrainField field,
                       int viewportWidth, int viewportHeight, Sink sink) {
        if (!open) return;
        List<Chart> sheets = sheets(view);
        if (boardId == 0 && sheets.isEmpty()) {
            close();
            return;
        }
        MapInk.Frame frame = frame(view, viewportWidth, viewportHeight);

        if (noteAt != null) {
            typeNote(input, view, sink);
            return;
        }

        int mx = input.getMouseX(), my = input.getMouseY();
        boolean moved = mx != lastPointerX || my != lastPointerY;
        lastPointerX = mx;
        lastPointerY = my;

        for (Tool option : Tool.values()) {
            if (input.isKeyJustPressed(KeyEvent.VK_1 + option.ordinal())) tool = option;
        }

        int wheel = input.getWheelRotation();
        if (wheel != 0 && frame.holds(mx, my)) {
            // Zoom about the pointer rather than about the middle, so the thing
            // you are looking at is the thing that stays put. The pan is
            // adjusted by however far the point under the cursor would have
            // moved, which is the whole of it.
            double beforeX = frame.worldX(mx), beforeY = frame.worldY(my);
            zoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM,
                    zoom * Math.pow(ZOOM_STEP, -wheel)));
            MapInk.Frame after = frame(view, viewportWidth, viewportHeight);
            panX += beforeX - after.worldX(mx);
            panY += beforeY - after.worldY(my);
            frame = frame(view, viewportWidth, viewportHeight);
        }

        // The right button pans whatever the tool is: a hand you have to select
        // is a hand nobody selects, and there is nothing else the right button
        // does on a panel.
        boolean wantsPan = input.isRightMouseDown()
                || (tool == Tool.HAND && input.isMouseDown());
        if (wantsPan && (panning || frame.holds(mx, my))) {
            if (panning && moved) {
                panX -= (mx - dragFromX) / frame.scale();
                panY -= (my - dragFromY) / frame.scale();
            }
            panning = true;
            dragFromX = mx;
            dragFromY = my;
            return;
        }
        panning = false;

        if (tool == Tool.PEN) {
            pen(input, view, frame, mx, my, sink);
            return;
        }
        if (!input.isMouseJustPressed() || !frame.holds(mx, my)) return;
        double worldX = frame.worldX(mx), worldY = frame.worldY(my);
        Chart target = penTarget(view, worldX, worldY);
        if (target == null) return;
        if (tool == Tool.ERASER) {
            long mark = target.markNear(worldX, worldY, ERASER_PIXELS / frame.scale());
            if (mark != 0) sink.erase(target.id(), mark);
            return;
        }
        if (tool == Tool.NOTE) {
            noteAt = new double[]{worldX, worldY, target.id()};
            noteText.setLength(0);
        }
    }

    /**
     * The pen: collect points while the button is down, send the stroke when it
     * comes up.
     *
     * <p>One message per stroke rather than one per point, and the reason is
     * not only the wire: a stroke is <b>one thing a person did</b>, so it is one
     * thing to undo, one thing to rub out, and one thing to attribute. Points
     * closer together than {@link #STROKE_STEP} pixels are dropped, which turns
     * a hand resting still on the button into nothing rather than into two
     * hundred identical points.
     */
    private void pen(InputManager input, WatchView view, MapInk.Frame frame, int mx, int my,
                     Sink sink) {
        if (input.isMouseDown()) {
            if (!drawing) {
                if (!frame.holds(mx, my)) return;
                drawing = true;
                pending.clear();
            }
            double worldX = frame.worldX(mx), worldY = frame.worldY(my);
            if (pending.isEmpty()) {
                pending.add(new double[]{worldX, worldY});
                return;
            }
            double[] last = pending.get(pending.size() - 1);
            double step = STROKE_STEP / frame.scale();
            if (Math.hypot(worldX - last[0], worldY - last[1]) >= step
                    && pending.size() < Chart.MAX_STROKE_POINTS) {
                pending.add(new double[]{worldX, worldY});
            }
            return;
        }
        if (!drawing) return;
        drawing = false;
        if (pending.size() >= 2) {
            Chart target = penTarget(view, pending.get(0)[0], pending.get(0)[1]);
            if (target != null) {
                double[] xs = new double[pending.size()];
                double[] ys = new double[pending.size()];
                for (int i = 0; i < pending.size(); i++) {
                    xs[i] = pending.get(i)[0];
                    ys[i] = pending.get(i)[1];
                }
                sink.mark(target.id(), ink, xs, ys);
            }
        }
        pending.clear();
    }

    /** A note being typed: characters in, Enter commits, Escape drops it. */
    private void typeNote(InputManager input, WatchView view, Sink sink) {
        if (input.isKeyJustPressed(KeyEvent.VK_ESCAPE)) {
            noteAt = null;
            noteText.setLength(0);
            return;
        }
        if (input.isKeyJustPressed(KeyEvent.VK_BACK_SPACE) && noteText.length() > 0) {
            noteText.deleteCharAt(noteText.length() - 1);
        }
        String typed = input.consumeTypedChars();
        for (char c : typed.toCharArray()) {
            if (noteText.length() >= Chart.MAX_NOTE_LENGTH) break;
            if (c >= ' ') noteText.append(c);
        }
        if (input.isKeyJustPressed(KeyEvent.VK_ENTER)) {
            if (!noteText.toString().isBlank()) {
                sink.note((long) noteAt[2], ink, noteAt[0], noteAt[1],
                        noteText.toString().trim());
            }
            noteAt = null;
            noteText.setLength(0);
        }
    }

    /** Set the tool — what the buttons down the strip do. */
    public void setTool(Tool tool) { this.tool = tool; }

    /** Set the ink. */
    public void setInk(int ink) { this.ink = Math.floorMod(ink, Chart.Ink.values().length); }

    /** Which tool is in hand. */
    public Tool tool() { return tool; }

    /** Which ink is in the pen. */
    public int ink() { return ink; }

    // --- the picture ------------------------------------------------------------------

    /**
     * The rectangle of world the paper is showing.
     *
     * <p>For a single map it is that map's own square; for a board it is the
     * union of everything pinned to it — see {@link Cartography#bounds}, and
     * the class note on why a union is all "combining maps" has to mean. The
     * square is then divided by the zoom and shifted by the pan.
     */
    private MapInk.Frame frame(WatchView view, int viewportWidth, int viewportHeight) {
        int size = paperSize(viewportWidth, viewportHeight);
        int x = INSET + 16;
        int y = (viewportHeight - size) / 2;
        double centreX = 0, centreY = 0, span = Chart.MIN_RADIUS * 2;
        if (boardId != 0) {
            double[] bounds = view.maps().bounds(boardId);
            if (bounds != null) {
                centreX = (bounds[0] + bounds[2]) / 2;
                centreY = (bounds[1] + bounds[3]) / 2;
                span = Math.max(bounds[2] - bounds[0], bounds[3] - bounds[1]);
            } else {
                Cartography.Board board = view.maps().board(boardId);
                if (board != null) {
                    centreX = board.x();
                    centreY = board.y();
                }
            }
        } else {
            Chart chart = view.maps().chart(chartId);
            if (chart != null) {
                centreX = chart.centreX();
                centreY = chart.centreY();
                span = chart.span();
            }
        }
        return new MapInk.Frame(x, y, size, centreX + panX, centreY + panY, span / zoom);
    }

    private int paperSize(int viewportWidth, int viewportHeight) {
        int wide = viewportWidth - INSET * 2 - STRIP - 32;
        int tall = viewportHeight - INSET * 2;
        return Math.max(160, Math.min(wide, tall));
    }

    /** Draw the panel. */
    public void draw(DrawTarget target, WatchView view, TerrainField field,
                     int viewportWidth, int viewportHeight) {
        if (!open) return;
        target.fillRect(0, 0, viewportWidth, viewportHeight, BACKDROP);

        MapInk.Frame frame = frame(view, viewportWidth, viewportHeight);
        int panelX = INSET, panelY = INSET;
        int panelW = viewportWidth - INSET * 2;
        int panelH = viewportHeight - INSET * 2;
        target.fillRect(panelX, panelY, panelW, panelH, PAPER);
        target.drawRect(panelX, panelY, panelW, panelH, PAPER_EDGE, 2f);

        drawPaper(target, view, field, frame);
        drawStrip(target, view, panelX + panelW - STRIP - 12, panelY + 12, panelH - 24);
    }

    /** The map itself: ground, borders, icons, ink and everybody's position. */
    private void drawPaper(DrawTarget target, WatchView view, TerrainField field,
                           MapInk.Frame frame) {
        target.fillRect(frame.x(), frame.y(), frame.size(), frame.size(), UNSURVEYED);
        target.pushClip(frame.x(), frame.y(), frame.size(), frame.size());

        List<Chart> sheets = sheets(view);
        boolean waiting = false;
        for (Chart chart : sheets) {
            int left = frame.screenX(chart.minX());
            int top = frame.screenY(chart.minY());
            int right = frame.screenX(chart.maxX());
            int bottom = frame.screenY(chart.maxY());
            BufferedImage paper = ChartImage.of(field, chart);
            if (paper == null) {
                waiting = true;
                target.fillRect(left, top, right - left, bottom - top, PAPER_DEEP);
            } else {
                target.drawImage(paper, left, top, right - left, bottom - top);
            }
        }
        // The sheet borders, over the pictures, so a board shows how its maps
        // lock together — which is the one thing a seamless join hides.
        for (Chart chart : sheets) {
            int left = frame.screenX(chart.minX());
            int top = frame.screenY(chart.minY());
            target.drawRect(left, top, frame.screenX(chart.maxX()) - left,
                    frame.screenY(chart.maxY()) - top, TILE_LINE, 1f);
        }

        // Icons, then ink, through the same code the board's own face is baked
        // with — see MapInk. A player who reads a board and then opens the map
        // is looking at one map twice, not at two drawings of it.
        for (Chart chart : sheets) {
            for (Chart.Landmark landmark : chart.landmarks()) {
                MapInk.icon(target, landmark.kind(), frame.screenX(landmark.x()),
                        frame.screenY(landmark.y()), 1);
            }
        }
        for (Chart chart : sheets) {
            for (Chart.Stroke stroke : chart.strokes()) {
                MapInk.stroke(target, frame, stroke, 1);
            }
            for (Chart.Note note : chart.notes()) MapInk.note(target, frame, note, 1, SMALL);
        }
        // The stroke under the hand, which is not on any map yet.
        if (drawing && pending.size() >= 2) {
            drawPending(target, frame);
        }

        drawWalkers(target, view, frame);
        target.popClip();

        target.drawRect(frame.x(), frame.y(), frame.size(), frame.size(), PAPER_EDGE, 2f);
        drawScaleBar(target, frame);
        if (waiting) {
            String surveying = "Surveying…";
            target.drawText(surveying,
                    frame.x() + frame.size() / 2 - target.textWidth(surveying, BODY) / 2,
                    frame.y() + frame.size() / 2, BODY, INK);
        }
        if (noteAt != null) drawNoteEditor(target, frame);
    }

    private void drawPending(DrawTarget target, MapInk.Frame frame) {
        Color colour = new Color(Chart.Ink.at(ink).rgb());
        for (int i = 1; i < pending.size(); i++) {
            double[] a = pending.get(i - 1), b = pending.get(i);
            target.drawLine(frame.screenX(a[0]), frame.screenY(a[1]),
                    frame.screenX(b[0]), frame.screenY(b[1]), colour, 2f);
        }
    }

    private void drawNoteEditor(DrawTarget target, MapInk.Frame frame) {
        int x = frame.screenX(noteAt[0]), y = frame.screenY(noteAt[1]);
        String shown = noteText + "▏";
        int width = Math.max(80, target.textWidth(shown, SMALL) + 10);
        target.fillRect(x + 6, y - 12, width, 18, new Color(246, 240, 220));
        target.drawRect(x + 6, y - 12, width, 18, new Color(Chart.Ink.at(ink).rgb()));
        target.drawText(shown, x + 10, y + 1, SMALL, new Color(Chart.Ink.at(ink).rgb()));
    }

    /**
     * Everybody's position, and — the part that matters — everybody who is off
     * the paper.
     *
     * <p><b>A walker off the edge is pinned to the edge, not dropped.</b> A map
     * whose markers vanish the moment somebody walks off it is a map that
     * cannot answer the one question a party actually asks it, which is "where
     * is everyone". So a position outside the frame is clamped to the border and
     * drawn as a smaller pin with no heading — because at that point the honest
     * information is a direction, not a place — and the number beside it is how
     * far away they are.
     */
    private void drawWalkers(DrawTarget target, WatchView view, MapInk.Frame frame) {
        for (WatchView.Walker walker : view.walkers()) {
            boolean self = walker.id() == view.selfId();
            int[] pin = pinAt(frame, walker.x(), walker.y());
            int cx = pin[0], cy = pin[1];
            boolean off = pin[2] != 0;
            Color colour = self ? SELF_PIN : OTHER_PIN;
            if (off) {
                // A triangle pointing the way they are, rather than the way they
                // face: which direction to walk is the only thing this pin can
                // still say.
                double angle = Math.atan2(frame.screenY(walker.y()) - cy,
                        frame.screenX(walker.x()) - cx);
                arrow(target, cx, cy, angle, PIN * 0.7, colour);
            } else {
                // The game's forward is (sin yaw, −cos yaw) and screen y grows
                // downward with world y, so a walker's heading on the paper is
                // their yaw measured from north — which is this.
                arrow(target, cx, cy, walker.yaw() - Math.PI / 2, PIN, colour);
            }
            String label = walker.name() + (off ? "  " + far(view, walker) : "");
            target.drawText(label, cx + PIN + 2, cy + 4, SMALL,
                    self ? INK : new Color(40, 60, 96));
        }
    }

    /**
     * Where a world point's pin lands on the paper, as
     * {@code x, y, offTheEdge}.
     *
     * <p>Clamped to the border rather than allowed off it, which is the whole
     * of the "even if they are off the map" rule and the reason this is a method
     * rather than four lines inside the drawing loop: it is a claim a test can
     * make, and it is the same arithmetic the drawing uses.
     */
    private static int[] pinAt(MapInk.Frame frame, double worldX, double worldY) {
        int x = frame.screenX(worldX);
        int y = frame.screenY(worldY);
        boolean off = !frame.holds(x, y);
        int cx = Math.max(frame.x() + PIN, Math.min(frame.x() + frame.size() - PIN, x));
        int cy = Math.max(frame.y() + PIN, Math.min(frame.y() + frame.size() - PIN, y));
        return new int[]{cx, cy, off ? 1 : 0};
    }

    /**
     * Where a world point's pin would land, for a caller outside this class —
     * for tests, and for nothing on screen.
     */
    public int[] pinFor(WatchView view, double worldX, double worldY, int viewportWidth,
                        int viewportHeight) {
        return pinAt(frame(view, viewportWidth, viewportHeight), worldX, worldY);
    }

    /** The square of world the paper is showing, as {@code minX, minY, maxX, maxY}. */
    public double[] shown(WatchView view, int viewportWidth, int viewportHeight) {
        MapInk.Frame frame = frame(view, viewportWidth, viewportHeight);
        return new double[]{frame.worldX(frame.x()), frame.worldY(frame.y()),
                frame.worldX(frame.x() + frame.size()),
                frame.worldY(frame.y() + frame.size())};
    }

    /** How far away a walker off the paper is, in words. */
    private String far(WatchView view, WatchView.Walker walker) {
        WatchView.Walker me = view.self();
        if (me == null) return "";
        long metres = Math.round(Math.hypot(walker.x() - me.x(), walker.y() - me.y()));
        return metres >= 1000 ? String.format("%.1f km", metres / 1000.0) : metres + " m";
    }

    /** A little filled triangle pointing along {@code angle}. */
    private static void arrow(DrawTarget target, int x, int y, double angle, double size,
                              Color colour) {
        int[] xs = new int[3];
        int[] ys = new int[3];
        for (int i = 0; i < 3; i++) {
            // Nose, then two tails at ±140°, which is a dart rather than a
            // wedge and reads at eleven pixels.
            double a = angle + (i == 0 ? 0 : i == 1 ? Math.toRadians(140)
                    : Math.toRadians(-140));
            double r = i == 0 ? size : size * 0.85;
            xs[i] = (int) Math.round(x + Math.cos(a) * r);
            ys[i] = (int) Math.round(y + Math.sin(a) * r);
        }
        target.fillPolygon(xs, ys, 3, colour);
        target.drawPolygon(xs, ys, 3, INK, 1f);
    }

    /**
     * The scale bar.
     *
     * <p>A round number of metres rather than a round number of pixels, chosen
     * off the 1–2–5 ladder every printed map uses, so the bar says "200 m" and
     * not "173 m".
     */
    private void drawScaleBar(DrawTarget target, MapInk.Frame frame) {
        double metresWanted = frame.span() / 4;
        double magnitude = Math.pow(10, Math.floor(Math.log10(Math.max(1, metresWanted))));
        double lead = metresWanted / magnitude;
        double round = (lead >= 5 ? 5 : lead >= 2 ? 2 : 1) * magnitude;
        int pixels = (int) Math.round(round * frame.scale());
        int x = frame.x() + 12, y = frame.y() + frame.size() - 16;
        target.fillRect(x, y, pixels, 4, INK);
        target.fillRect(x, y - 3, 2, 10, INK);
        target.fillRect(x + pixels - 2, y - 3, 2, 10, INK);
        String label = round >= 1000 ? String.format("%.0f km", round / 1000)
                : Math.round(round) + " m";
        target.drawText(label, x + pixels + 6, y + 6, SMALL, INK);
    }

    // --- the strip --------------------------------------------------------------------

    /** Where each row of the strip starts, so drawing and hit testing agree. */
    private static final int TOOL_ROW = 92;
    private static final int TOOL_HEIGHT = 26;
    private static final int INK_ROW = TOOL_ROW + Tool.values().length * TOOL_HEIGHT + 26;
    private static final int LEGEND_ROW = INK_ROW + 44;
    private static final int PIN_ROW = LEGEND_ROW + Chart.Kind.values().length * 20 + 30;

    /** One clickable row on the board's pin list. */
    private record PinRow(long chartId, long toBoard, int x, int y, int w, int h) {

        boolean holds(int mx, int my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    /**
     * The pin list's rows, worked out while drawing and read when clicked.
     *
     * <p>The one place this panel settles its geometry in that order rather than
     * up front, and it has to: how many rows fit depends on how tall the panel
     * is and how many maps there are, so working it out twice is exactly the
     * mistake {@code WatchScene.SatchelBox} exists to prevent.
     */
    private final List<PinRow> pinRows = new ArrayList<>();

    private void drawStrip(DrawTarget target, WatchView view, int x, int y, int height) {
        target.fillRect(x, y, STRIP, height, PAPER_DEEP);
        target.drawRect(x, y, STRIP, height, PAPER_EDGE, 1f);

        String title;
        String subtitle;
        if (boardId != 0) {
            List<Chart> pinned = view.maps().pinnedTo(boardId);
            title = "Map Board";
            subtitle = pinned.isEmpty()
                    ? "Nothing pinned yet — put a map up and the paper fills in."
                    : pinned.size() + (pinned.size() == 1 ? " map" : " maps")
                            + " joined into one.";
        } else {
            Chart chart = view.maps().chart(chartId);
            title = chart == null ? "Map" : chart.name();
            subtitle = chart == null ? "" : chart.describe();
        }
        target.drawText(UiText.fit(target, TITLE, title, STRIP - 20), x + 10, y + 28,
                TITLE, INK);
        wrapped(target, subtitle, x + 10, y + 48, STRIP - 20, 2, INK_DIM);

        target.drawText("Pen", x + 10, y + TOOL_ROW - 8, BOLD, INK);
        for (Tool option : Tool.values()) {
            int row = y + TOOL_ROW + option.ordinal() * TOOL_HEIGHT;
            boolean on = option == tool;
            target.fillRect(x + 10, row, STRIP - 20, 22,
                    on ? new Color(180, 164, 128) : new Color(214, 200, 168));
            target.drawRect(x + 10, row, STRIP - 20, 22, PAPER_EDGE, 1f);
            target.drawText((option.ordinal() + 1) + "  " + option.label(), x + 18,
                    row + 16, BODY, INK);
        }
        target.drawText(tool.hint(), x + 10,
                y + TOOL_ROW + Tool.values().length * TOOL_HEIGHT + 14, SMALL, INK_DIM);

        int inkRow = y + INK_ROW;
        Chart.Ink[] inks = Chart.Ink.values();
        for (int i = 0; i < inks.length; i++) {
            int sx = x + 10 + i * 32;
            target.fillRect(sx, inkRow, 26, 22, new Color(inks[i].rgb()));
            target.drawRect(sx, inkRow, 26, 22, i == ink ? INK : PAPER_EDGE,
                    i == ink ? 2f : 1f);
        }

        int row = y + LEGEND_ROW;
        target.drawText("Key", x + 10, row, BOLD, INK);
        for (Chart.Kind kind : Chart.Kind.values()) {
            row += 20;
            MapInk.icon(target, kind, x + 20, row - 4, 1);
            target.drawText(kind.label(), x + 36, row, SMALL, INK_DIM);
        }

        pinRows.clear();
        if (boardId != 0) drawPinList(target, view, x, y + PIN_ROW, y + height - 56);
        wrapped(target, "Wheel zooms · right-drag moves the paper · Esc closes",
                x + 10, y + height - 42, STRIP - 20, 3, INK_DIM);
    }

    /**
     * The board's own list: what is pinned, and what is in your satchel waiting
     * to be.
     *
     * <p>This is the whole of "combine maps into a larger one" as a player meets
     * it — a map goes up, and the paper on the left is bigger. There is no join
     * to make, no orientation to choose and no order to get right, because the
     * maps were never drawn relative to themselves. See the class note.
     */
    private void drawPinList(DrawTarget target, WatchView view, int x, int y, int bottom) {
        int row = y;
        target.drawText("On the board  (click to take)", x + 10, row, BOLD, INK);
        for (Chart chart : view.maps().pinnedTo(boardId)) {
            row += 19;
            if (row > bottom) return;
            target.drawText("−  " + UiText.fit(target, SMALL, chart.name(), STRIP - 40),
                    x + 12, row, SMALL, INK);
            pinRows.add(new PinRow(chart.id(), 0, x + 10, row - 13, STRIP - 20, 18));
        }
        row += 28;
        if (row > bottom) return;
        target.drawText("In your satchel  (click to pin)", x + 10, row, BOLD, INK);
        for (Chart chart : view.maps().carriedBy(view.selfId())) {
            row += 19;
            if (row > bottom) return;
            target.drawText("+  " + UiText.fit(target, SMALL, chart.name(), STRIP - 40),
                    x + 12, row, SMALL, INK);
            pinRows.add(new PinRow(chart.id(), boardId, x + 10, row - 13, STRIP - 20, 18));
        }
    }

    /**
     * Whether a click landed on the strip, acting on it if it did.
     *
     * <p>The scene offers every click here first, so the strip has right of
     * refusal and the paper — which is everything else — never has to know the
     * strip is there. A click anywhere on the strip is swallowed even when it
     * hits no control, because a pen stroke that starts on the toolbar is not a
     * pen stroke anybody meant to draw.
     */
    public boolean clickStrip(int mx, int my, int viewportWidth, int viewportHeight,
                              Sink sink) {
        if (!open) return false;
        int x = INSET + (viewportWidth - INSET * 2) - STRIP - 12;
        int y = INSET + 12;
        int height = viewportHeight - INSET * 2 - 24;
        if (mx < x || mx > x + STRIP || my < y || my > y + height) return false;
        for (Tool option : Tool.values()) {
            int row = y + TOOL_ROW + option.ordinal() * TOOL_HEIGHT;
            if (my >= row && my < row + 22) {
                tool = option;
                return true;
            }
        }
        int inkRow = y + INK_ROW;
        if (my >= inkRow && my < inkRow + 22) {
            int index = (mx - x - 10) / 32;
            if (index >= 0 && index < Chart.Ink.values().length) ink = index;
            return true;
        }
        for (PinRow pinRow : pinRows) {
            if (pinRow.holds(mx, my)) {
                sink.pin(pinRow.chartId(), pinRow.toBoard());
                return true;
            }
        }
        return true;
    }

    /** A block of text, wrapped and laid down line by line. */
    private static void wrapped(DrawTarget target, String text, int x, int y, int width,
                                int lines, Color colour) {
        List<String> wrapped = UiText.wrap(target, SMALL, text, width, lines);
        for (int i = 0; i < wrapped.size(); i++) {
            target.drawText(wrapped.get(i), x, y + i * 14, SMALL, colour);
        }
    }
}
