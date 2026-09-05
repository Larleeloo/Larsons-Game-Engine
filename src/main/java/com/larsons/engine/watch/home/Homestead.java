package com.larsons.engine.watch.home;

import com.larsons.engine.watch.WatchJson;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Every house the party has bought, and where each one stands.
 *
 * <h2>A flat list, and nine numbers a house</h2>
 *
 * <p>{@code Structure}'s argument, and it survives its author: a party owns a
 * handful of houses, a spatial index over a handful is a thing to keep correct
 * in exchange for microseconds nobody was going to notice, and what actually
 * matters is that a house is <b>cheap to send</b>. A {@link Home} is a plan key
 * and eight numbers, so a mansion crosses the wire in about eighty bytes and
 * arrives complete — the three hundred boxes it is made of are
 * {@linkplain HouseKit#parts(HousePlan) derived on both sides} from that key,
 * which is the whole reason houses are bought rather than built. The old
 * building system had to send every plank somebody nailed down.
 *
 * <h2>Collision lives here</h2>
 *
 * <p>This class is also the walk's answer to "what am I standing on" and "can I
 * go that way", because it is the only thing that knows where the timber is.
 * Three queries do all of it — {@link #standOn}, {@link #solidAt} and
 * {@link #climbAt} — and each of them works the same way: take the world point
 * into the house's own frame, where every part is an axis-aligned box, and
 * answer there. Turning the <em>question</em> rather than the three hundred
 * boxes is what lets a house stand at any of the eight compass turns without
 * the collision ever meeting a rotated box.
 *
 * <p>None of the three adjudicates anything. Like {@code Structure} before it,
 * {@link #place} is the step after the decision rather than the decision: on a
 * host it runs once {@code WatchGame.buyHome} has checked and paid, and on a
 * guest it runs to apply what the host already decided. A guest that
 * re-adjudicated would drop houses whenever its copy of the world was a tick
 * behind.
 */
public final class Homestead {

    /** Metres a house's position snaps to. */
    public static final double GRID = 0.5;

    /** How many turns a house can take: the eight compass points. */
    public static final int TURNS = 8;

    /**
     * One house, standing somewhere.
     *
     * @param z       the ground under a house that stands on the ground, or the
     *                deck height for one in a tree
     * @param drop    how far the ground is below {@code z}, which is how long
     *                the ladder down from a treehouse has to be
     * @param treeId  the tree it is fixed to, or {@code 0} for the ground
     */
    public record Home(long id, HousePlan plan, double x, double y, double z,
                       int turn, long treeId, double drop, String boughtBy,
                       long atMillis) {

        /** Which way the front door faces, in radians. */
        public double yaw() { return turn * Math.PI * 2 / TURNS; }

        /** Whether it is fixed in a tree rather than standing on the ground. */
        public boolean inTree() { return treeId != 0; }

        /**
         * How far from the middle nothing else may stand, in metres.
         *
         * <p>Wider than the plan's own circle on a slope: a house whose ground
         * falls away stands on piers with a flight of steps beside them, and the
         * steps reach further out the further it falls. A treehouse's climb is
         * vertical and adds nothing.
         */
        public double radius() {
            return plan.radius() + (inTree() ? 0 : Math.max(0, drop) * 1.3);
        }

        /** How far a point is from the middle of the house, in metres. */
        public double distanceTo(double px, double py) {
            return Math.hypot(x - px, y - py);
        }

        /** Whether somebody standing there is close enough to call it theirs. */
        public boolean inReach(double px, double py) {
            return distanceTo(px, py) <= radius();
        }

        /** A world point's distance out of the front door, in metres. */
        public double alongOf(double px, double py) {
            double yaw = yaw();
            return (px - x) * Math.sin(yaw) - (py - y) * Math.cos(yaw);
        }

        /** …and to the door's right. */
        public double acrossOf(double px, double py) {
            double yaw = yaw();
            return (px - x) * Math.cos(yaw) + (py - y) * Math.sin(yaw);
        }

        /** Where a point in the house's frame is in the world. */
        public double worldX(double along, double across) {
            double yaw = yaw();
            return x + Math.sin(yaw) * along + Math.cos(yaw) * across;
        }

        public double worldY(double along, double across) {
            double yaw = yaw();
            return y - Math.cos(yaw) * along + Math.sin(yaw) * across;
        }

        /** What it is worth to somebody taking it down again: half what it cost. */
        public int refund() { return Math.max(1, plan.price() / 2); }

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("h", plan.key());
            m.put("x", x);
            m.put("y", y);
            m.put("z", z);
            m.put("r", turn);
            if (treeId != 0) m.put("tree", treeId);
            if (drop > 0) m.put("d", drop);
            if (boughtBy != null) m.put("by", boughtBy);
            m.put("t", atMillis);
            return m;
        }

        static Home fromMap(Map<String, Object> m) {
            HousePlan plan = HousePlan.of(WatchJson.str(m, "h", ""));
            if (plan == null) return null;
            return new Home(WatchJson.big(m, "id", 0), plan,
                    WatchJson.num(m, "x", 0), WatchJson.num(m, "y", 0),
                    WatchJson.num(m, "z", 0), WatchJson.integer(m, "r", 0),
                    WatchJson.big(m, "tree", 0), WatchJson.num(m, "d", 0),
                    WatchJson.str(m, "by", null), WatchJson.big(m, "t", 0));
        }
    }

    /**
     * What a climb offers: the run of ladder a player is standing against.
     *
     * @param bottom the lowest rung's height, in world metres
     * @param top    where the ladder lets you off
     */
    public record Climb(double bottom, double top) {}

    /**
     * What came of asking to put a house up, or to take one down.
     *
     * <p>A house and a line, rather than a house or {@code null}, because there
     * are five different ways a purchase can be refused — no points, no tree, a
     * lake, a cliff, something already standing there — and a player who is
     * told none of them will try the same thing again in the same place. The
     * old build verb returned {@code null} and the screen guessed at "cannot
     * build there", which was right about a third of the time.
     *
     * <p>One type for both verbs, because the caller wants the same two things
     * of each: did the world change, and what does the player get told. That it
     * carries the line is what lets the refusal reach a <em>networked</em>
     * player, who has nobody else to hear it from.
     */
    public record Outcome(Home home, String line) {

        /** Whether the world actually changed. */
        public boolean done() { return home != null; }

        /** A refusal, with the reason a player is owed. */
        public static Outcome refused(String why) { return new Outcome(null, why); }
    }

    private final Map<Long, Home> homes = new LinkedHashMap<>();

    /**
     * One house's boxes, and what they were worked out for.
     *
     * <p>The plan and the drop are carried so the entry can be <b>checked</b>
     * rather than trusted. A house is immutable, so in practice they never
     * disagree — but the map outlives a {@link #load}, and a cache that assumed
     * an id still meant the same house would be a house drawn as somebody
     * else's the first time two worlds were opened in one session.
     */
    private record Cached(HousePlan plan, double drop, List<HousePart> parts) {}

    /**
     * Each house's boxes, worked out once.
     *
     * <p>Concurrent because the frame thread collides against them while the
     * drawing thread meshes them, and because a client replaces the whole
     * homestead every world sync while both are reading.
     */
    private final Map<Long, Cached> parts = new ConcurrentHashMap<>();

    private long nextId = 1;

    /** Put a house down, snapped to the grid. */
    public Home place(HousePlan plan, double x, double y, double z, int turn,
                      long treeId, double drop, String boughtBy, long atMillis) {
        Home home = new Home(nextId++, plan, snap(x), snap(y), z,
                Math.floorMod(turn, TURNS), treeId, Math.max(0, drop), boughtBy,
                atMillis);
        homes.put(home.id(), home);
        return home;
    }

    /** Add a house that arrived from a host or a save, keeping its id. */
    public void adopt(Home home) {
        if (home == null) return;
        homes.put(home.id(), home);
        nextId = Math.max(nextId, home.id() + 1);
    }

    /** Take one down. */
    public Home remove(long id) {
        parts.remove(id);
        return homes.remove(id);
    }

    /** Everything standing. */
    public List<Home> all() { return List.copyOf(homes.values()); }

    public int size() { return homes.size(); }

    public Home byId(long id) { return homes.get(id); }

    /** Everything within a radius of a point — what the mesher asks for. */
    public List<Home> near(double x, double y, double radius) {
        List<Home> out = new ArrayList<>();
        for (Home home : homes.values()) {
            double dx = home.x() - x, dy = home.y() - y;
            double reach = radius + home.radius();
            if (dx * dx + dy * dy <= reach * reach) out.add(home);
        }
        return out;
    }

    /** Everything fixed to one tree — so felling it takes the house too. */
    public List<Home> inTree(long treeId) {
        List<Home> out = new ArrayList<>();
        for (Home home : homes.values()) {
            if (home.treeId() == treeId) out.add(home);
        }
        return out;
    }

    /** The house whose footprint a point is nearest inside, or {@code null}. */
    public Home at(double x, double y) {
        Home best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Home home : homes.values()) {
            double d = home.distanceTo(x, y);
            if (d <= home.radius() && d < bestDistance) {
                bestDistance = d;
                best = home;
            }
        }
        return best;
    }

    /**
     * Whether a house would stand on top of one already there.
     *
     * <p>Two circles, which is exact enough and turn-independent — see
     * {@link HousePlan#radius()} for why a conservative circle is the right
     * trade here and a separating-axis test is not.
     */
    public boolean blocked(HousePlan plan, double x, double y) {
        double mine = plan.radius();
        for (Home home : homes.values()) {
            double dx = home.x() - x, dy = home.y() - y;
            double reach = mine + home.radius();
            if (dx * dx + dy * dy < reach * reach) return true;
        }
        return false;
    }

    /** A coordinate on the placement grid. */
    public static double snap(double v) {
        return Math.round(v / GRID) * GRID;
    }

    /**
     * One house's boxes, in the house's own frame.
     *
     * <p>Held rather than rebuilt, and it matters more than it looks: a solo
     * walk copies the whole homestead into its view every single frame (see
     * {@code WatchView.snapshot}), and the walk then asks this three times a
     * frame per house for the collision. Keyed by id and <em>checked</em>
     * against the plan and the drop, so a reload that brings the same houses
     * back keeps the same answer instead of allocating three hundred boxes a
     * house sixty times a second.
     */
    public List<HousePart> partsOf(Home home) {
        if (home == null) return List.of();
        Cached cached = parts.get(home.id());
        if (cached != null && cached.plan() == home.plan()
                && cached.drop() == home.drop()) {
            return cached.parts();
        }
        List<HousePart> built = HouseKit.parts(home.plan(), home.drop());
        parts.put(home.id(), new Cached(home.plan(), home.drop(), built));
        return built;
    }

    // --- what the walk asks ------------------------------------------------------------

    /**
     * How wide a walker is, in metres — the circle their shoulders sweep.
     *
     * <p>Deliberately slim. A doorway is {@value HouseKit#DOOR_WIDTH} metres
     * wide and a player who cannot walk through their own front door without
     * lining up on it would be the only thing anybody remembered about this
     * feature.
     */
    public static final double BODY_RADIUS = 0.32;

    /** How tall a walker is for the purpose of not walking into a lintel. */
    public static final double BODY_HEIGHT = 1.78;

    /**
     * The highest thing in a house that a walker at this point could be
     * standing on.
     *
     * <p>Everything about floors, stairs, decks and roof platforms comes out of
     * this one method, and it is deliberately the same rule for all of them:
     * <b>stand on the highest walkable top that is not above you</b>. A stair
     * tread is a floor twenty centimetres up, a landing is a floor three metres
     * up, and neither needs a case of its own.
     *
     * @param ceiling the highest surface that counts — a walker's feet plus the
     *                step they can take without jumping. Passing their eye
     *                height here is what would let somebody walk up a wall.
     * @param ground  what the answer is when no house is in the way
     */
    public double standOn(double x, double y, double ceiling, double ground) {
        double best = ground;
        for (Home home : near(x, y, 1.0)) {
            double along = home.alongOf(x, y), across = home.acrossOf(x, y);
            for (HousePart part : partsOf(home)) {
                if (!part.role().walkable()) continue;
                double top = home.z() + part.top();
                if (top <= best || top > ceiling) continue;
                if (Math.abs(along - part.along()) > part.halfAlong()) continue;
                if (Math.abs(across - part.across()) > part.halfAcross()) continue;
                best = top;
            }
        }
        return best;
    }

    /**
     * Whether a walker standing here would be inside somebody's wall.
     *
     * <p>Asked <em>after</em> a step has been taken and used to refuse it — see
     * {@code WatchScene.walk}, which tries the two axes separately so that
     * walking into a wall at an angle slides along it rather than stopping
     * dead.
     *
     * @param footZ where their boots are, and {@code headZ} the top of their head
     */
    public boolean solidAt(double x, double y, double footZ, double headZ) {
        for (Home home : near(x, y, BODY_RADIUS + 0.5)) {
            double along = home.alongOf(x, y), across = home.acrossOf(x, y);
            double foot = footZ - home.z(), head = headZ - home.z();
            for (HousePart part : partsOf(home)) {
                if (!part.role().solid()) continue;
                if (part.overlaps(along, across, foot, head, BODY_RADIUS)) return true;
            }
        }
        return false;
    }

    /**
     * The ladder a walker is standing against, or {@code null}.
     *
     * <p>A ladder is not solid, so walking into one puts you <em>on</em> it. What
     * comes back is the run it offers — where its lowest rung is and where it
     * lets you off — because the walk clamps the climb to that and because a
     * ladder you can ride past the top of is a ladder that puts you in the sky.
     */
    public Climb climbAt(double x, double y, double footZ, double headZ) {
        for (Home home : near(x, y, BODY_RADIUS + 0.5)) {
            double along = home.alongOf(x, y), across = home.acrossOf(x, y);
            double foot = footZ - home.z(), head = headZ - home.z();
            for (HousePart part : partsOf(home)) {
                if (!part.role().climbable()) continue;
                // A touch more reach than the body, because a ladder is a thing
                // you lean into and a rung you cannot quite grip is worse than
                // no rung.
                if (!part.overlaps(along, across, foot, head, BODY_RADIUS + 0.22)) {
                    continue;
                }
                return new Climb(home.z() + part.bottom(), home.z() + part.top());
            }
        }
        return null;
    }

    /**
     * Where a house's map board hangs, as {@code x, y, z, yaw} — or {@code null}
     * when the plan has no study.
     */
    public double[] boardOf(Home home) {
        if (home == null) return null;
        HousePart board = HouseKit.boardOf(home.plan());
        if (board == null) return null;
        return new double[]{
                home.worldX(board.along(), board.across()),
                home.worldY(board.along(), board.across()),
                home.z() + board.up(),
                home.yaw()
        };
    }

    // --- persistence ----------------------------------------------------------------

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("next", nextId);
        m.put("homes", toRows());
        return m;
    }

    public void load(Map<String, Object> m) {
        homes.clear();
        nextId = Math.max(1, WatchJson.big(m, "next", 1));
        for (Map<String, Object> row : WatchJson.objects(m, "homes")) {
            adopt(Home.fromMap(row));
        }
        parts.keySet().retainAll(homes.keySet());
    }

    /** Every house as a row — what a snapshot sends. */
    public List<Object> toRows() {
        List<Object> rows = new ArrayList<>();
        for (Home home : homes.values()) rows.add(home.toMap());
        return rows;
    }

    /** Replace everything from a host's list. */
    public void loadRows(List<Map<String, Object>> rows) {
        homes.clear();
        for (Map<String, Object> row : rows) adopt(Home.fromMap(row));
        parts.keySet().retainAll(homes.keySet());
    }
}
