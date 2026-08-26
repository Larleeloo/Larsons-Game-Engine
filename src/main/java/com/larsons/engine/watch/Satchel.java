package com.larsons.engine.watch;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What one player is carrying.
 *
 * <p><b>A bag of counts, not a grid of slots.</b> The engine's world game has a
 * slotted inventory because it is a game about arranging things; this one is a
 * game about finding things, and a naturalist's satchel is a list of what is in
 * it. So there is no slot arithmetic, no stack limit, no dragging — you have
 * eleven blackberries or you do not.
 *
 * <p>Insertion order is kept, so the satchel reads as a record of what you
 * picked up in the order you picked it up, which is the closest thing to a
 * story an inventory has.
 */
public final class Satchel {

    private final Map<String, Integer> counts = new LinkedHashMap<>();

    /** How many of an item is being carried. */
    public int count(String key) { return counts.getOrDefault(key, 0); }

    /** Whether there is at least one. */
    public boolean has(String key) { return count(key) > 0; }

    /** Whether there are at least {@code n}. */
    public boolean has(String key, int n) { return count(key) >= n; }

    /** Put {@code n} of something in. Negative or zero adds nothing. */
    public void add(String key, int n) {
        if (key == null || n <= 0) return;
        counts.merge(key, n, Integer::sum);
    }

    /**
     * Take {@code n} of something out.
     *
     * @return {@code true} if there were that many; nothing is removed if not,
     *         so a recipe that cannot be afforded cannot half-consume its
     *         ingredients
     */
    public boolean take(String key, int n) {
        if (n <= 0) return true;
        int have = count(key);
        if (have < n) return false;
        if (have == n) counts.remove(key);
        else counts.put(key, have - n);
        return true;
    }

    /** Everything in it, in the order it went in. */
    public Map<String, Integer> contents() { return Map.copyOf(counts); }

    /** Every key, in the order it went in. */
    public List<String> keys() { return List.copyOf(counts.keySet()); }

    /** How many distinct kinds of thing are in it. */
    public int kinds() { return counts.size(); }

    /** How many things in total. */
    public int total() {
        int n = 0;
        for (int c : counts.values()) n += c;
        return n;
    }

    /** Everything of one kind — what the cooking screen lists. */
    public List<String> ofKind(Forage.Kind kind) {
        List<String> out = new ArrayList<>();
        for (String key : counts.keySet()) {
            Forage.Item item = Forage.byKey(key);
            if (item != null && item.kind() == kind) out.add(key);
        }
        return out;
    }

    /** Empty it. */
    public void clear() { counts.clear(); }

    // --- persistence --------------------------------------------------------------

    /** The satchel as a JSON object: one key per item. */
    public Map<String, Object> toMap() {
        return new LinkedHashMap<>(counts);
    }

    /** Replace the contents from a save or a snapshot. */
    public void load(Map<String, Object> m) {
        counts.clear();
        if (m == null) return;
        m.forEach((key, value) -> {
            if (value instanceof Number n && n.intValue() > 0) {
                counts.put(key, n.intValue());
            }
        });
    }

    @Override public String toString() {
        return counts.isEmpty() ? "empty satchel" : counts.toString();
    }
}
