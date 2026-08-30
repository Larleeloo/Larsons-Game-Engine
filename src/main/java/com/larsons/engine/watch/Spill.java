package com.larsons.engine.watch;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What somebody was carrying, lying where they fell.
 *
 * <h2>Why it is stored and {@link Litter} is not</h2>
 *
 * <p>The other thing on the floor of this world is generated: a piece of litter
 * is a function of the cell it is in, both ends of a connection work it out
 * independently, and the only fact that ever travels is which pieces somebody
 * has already taken. That arrangement is what makes an endless world's floor
 * affordable, and it is exactly the wrong arrangement here.
 *
 * <p>A spill is <b>a thing that happened</b>. There is no seed from which "Kara
 * died at the ford with eleven blackberries and a spyglass" can be derived, no
 * way for a client to work it out, and no way to rebuild it after a restart
 * unless it is written down. So a spill is real state: it is in the save, it
 * goes out on the world sync beside the grove and the boats, and the host is
 * the only thing that creates or removes one.
 *
 * <h2>What it is for</h2>
 *
 * <p>Dying has to cost something or the three {@link
 * com.larsons.engine.watch.life.Mutants} are a light show. It also must not cost
 * anything <em>permanent</em>, because this is a game about going for a walk and
 * picking things up, and a satchel deleted by a bad thirty seconds in the dark
 * is thirty minutes of somebody's afternoon deleted with it.
 *
 * <p>So death drops the bag rather than emptying it. Everything you were
 * carrying is in one heap at the place it happened; the walk back to it is the
 * actual penalty, and it is a penalty made of the thing the game is already
 * about — walking somewhere, in this case somewhere you know for a fact there is
 * something dangerous. Anybody can pick a heap up, which is the answer for a
 * party: a friend can fetch your satchel while you take the long way round.
 *
 * <p><b>Nothing decays.</b> A heap waits. A save reopened a week later still has
 * it, in the same place, with everything in it — losing a satchel to a timer
 * nobody was told about would be the worst possible version of this feature.
 */
public final class Spill {

    /** How far a player can be from a heap and still gather it, in metres. */
    public static final double REACH = 2.6;

    /**
     * How big a heap is drawn, in metres — what the reach highlight rings.
     *
     * <p>Wider than a piece of litter and narrower than a bush: it is a pile of
     * somebody's things, and it has to be findable in long grass at dusk without
     * looking like a haystack.
     */
    public static final double RADIUS = 0.55;

    /**
     * One heap.
     *
     * @param id    stable identity, so a client can draw it and a pickup can
     *              name it without ambiguity
     * @param owner whose satchel it was — printed on the highlight, because
     *              "Kara's satchel" is the thing a party needs to read off it
     * @param items what is in it, keyed exactly as a {@link Satchel} is
     * @param when  when it was dropped, in wall-clock millis, for the log line
     */
    public record Pile(long id, String owner, double x, double y, double z,
                       Map<String, Integer> items, long when) {

        public Pile {
            items = Map.copyOf(items);
        }

        /** How many things are in it, counting duplicates. */
        public int total() {
            int n = 0;
            for (int count : items.values()) n += count;
            return n;
        }

        /** Whether there is anything in it at all. */
        public boolean empty() { return items.isEmpty(); }

        /** What the highlight says. */
        public String label() {
            String whose = owner == null || owner.isBlank() ? "A dropped satchel"
                    : owner + "'s satchel";
            int total = total();
            return whose + "  ·  " + total + (total == 1 ? " thing" : " things");
        }

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            if (owner != null) m.put("by", owner);
            m.put("x", x);
            m.put("y", y);
            m.put("z", z);
            m.put("t", when);
            m.put("items", new LinkedHashMap<>(items));
            return m;
        }

        static Pile fromMap(Map<String, Object> m) {
            if (m == null) return null;
            long id = WatchJson.big(m, "id", 0);
            if (id == 0) return null;
            Map<String, Integer> items = new LinkedHashMap<>();
            WatchJson.map(m, "items").forEach((key, value) -> {
                if (value instanceof Number n && n.intValue() > 0) {
                    items.put(key, n.intValue());
                }
            });
            return new Pile(id, WatchJson.str(m, "by", null), WatchJson.num(m, "x", 0),
                    WatchJson.num(m, "y", 0), WatchJson.num(m, "z", 0), items,
                    WatchJson.big(m, "t", 0));
        }
    }

    private final Map<Long, Pile> piles = new LinkedHashMap<>();

    private long nextId = 1;

    /** Every heap on the ground, in the order it was dropped. */
    public List<Pile> all() { return List.copyOf(piles.values()); }

    /** How many heaps there are. */
    public int count() { return piles.size(); }

    /** The heap with this id, or {@code null}. */
    public Pile byId(long id) { return piles.get(id); }

    /**
     * Drop a satchel's worth of things at a point.
     *
     * <p>An empty satchel drops nothing and returns {@code null}: a heap with
     * nothing in it is a thing to walk back to for no reason, and somebody
     * killed on their way out of camp with an empty bag should simply get up
     * and carry on.
     */
    public Pile drop(String owner, double x, double y, double z,
                     Map<String, Integer> items) {
        if (items == null || items.isEmpty()) return null;
        Map<String, Integer> kept = new LinkedHashMap<>();
        items.forEach((key, count) -> {
            if (key != null && count != null && count > 0) kept.put(key, count);
        });
        if (kept.isEmpty()) return null;
        Pile pile = new Pile(nextId++, owner, x, y, z, kept,
                System.currentTimeMillis());
        piles.put(pile.id(), pile);
        return pile;
    }

    /** Adopt a heap from a save or from the host. */
    public void add(Pile pile) {
        if (pile == null) return;
        piles.put(pile.id(), pile);
        nextId = Math.max(nextId, pile.id() + 1);
    }

    /** The nearest heap to a point within a radius, or {@code null}. */
    public Pile nearest(double x, double y, double radius) {
        Pile best = null;
        double bestDistance = radius * radius;
        for (Pile pile : piles.values()) {
            double dx = pile.x() - x, dy = pile.y() - y;
            double d = dx * dx + dy * dy;
            if (d < bestDistance) {
                bestDistance = d;
                best = pile;
            }
        }
        return best;
    }

    /** Every heap within a radius of a point — what a client draws. */
    public List<Pile> near(double x, double y, double radius) {
        List<Pile> out = new ArrayList<>();
        double limit = radius * radius;
        for (Pile pile : piles.values()) {
            double dx = pile.x() - x, dy = pile.y() - y;
            if (dx * dx + dy * dy <= limit) out.add(pile);
        }
        return out;
    }

    /**
     * Take a heap off the ground.
     *
     * <p>All of it, in one gesture, and then it is gone. Picking a satchel up
     * item by item would be a worse version of the satchel screen and would
     * leave a rump of things nobody wanted lying in the wood for ever.
     *
     * @return what was in it, or {@code null} if there was no such heap
     */
    public Map<String, Integer> take(long id) {
        Pile pile = piles.remove(id);
        return pile == null ? null : pile.items();
    }

    /** Forget every heap — what loading a different world does. */
    public void clear() {
        piles.clear();
        nextId = 1;
    }

    // --- persistence ------------------------------------------------------------------

    /** Every heap, as JSON. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        List<Object> rows = new ArrayList<>();
        for (Pile pile : piles.values()) rows.add(pile.toMap());
        m.put("piles", rows);
        m.put("next", nextId);
        return m;
    }

    /** Put the heaps back. */
    public void load(Map<String, Object> m) {
        clear();
        if (m == null) return;
        for (Map<String, Object> row : WatchJson.objects(m, "piles")) {
            add(Pile.fromMap(row));
        }
        nextId = Math.max(nextId, WatchJson.big(m, "next", nextId));
    }

    @Override public String toString() {
        return piles.size() + " dropped satchels";
    }
}
