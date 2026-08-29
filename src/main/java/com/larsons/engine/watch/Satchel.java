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

    /**
     * How many of everything a {@linkplain #bottomless() bottomless} satchel
     * reports having.
     *
     * <p>Four nines rather than {@code Integer.MAX_VALUE}: it is obviously not
     * a real number of blackberries, it fits in the satchel screen's column,
     * and nothing multiplies a count by anything, so there is no arithmetic to
     * overflow.
     */
    public static final int BOTTOMLESS = 9999;

    private final Map<String, Integer> counts = new LinkedHashMap<>();

    /**
     * Whether this satchel is a debug one: full of everything, for ever.
     *
     * <p><b>A lens over the contents, not a gift of them.</b> Nothing is added
     * when it goes on and nothing is taken away when it comes off — what is
     * actually in the bag is untouched underneath, so a walk that spent an
     * hour in debug mode and then left it is exactly the walk it was, plus
     * whatever was genuinely picked up along the way.
     */
    private boolean bottomless;

    /** How many of an item is being carried. */
    public int count(String key) {
        if (bottomless) return BOTTOMLESS;
        return counts.getOrDefault(key, 0);
    }

    /** Whether there is at least one. */
    public boolean has(String key) { return count(key) > 0; }

    /** Whether there are at least {@code n}. */
    public boolean has(String key, int n) { return count(key) >= n; }

    /**
     * Whether this satchel can never run out of anything.
     *
     * <p>See {@link Debug}: this one flag is the whole of debug mode's
     * unlimited items, because every cost in the game is a {@link #has} and a
     * {@link #take} against a satchel — including the costs of features that do
     * not exist yet.
     */
    public boolean bottomless() { return bottomless; }

    /**
     * Turn the lens on or off.
     *
     * <p>Set by {@code WatchPlayer.setDebug} on the server and mirrored onto
     * the view's copy from the snapshot, so the build and cooking screens on a
     * client light up exactly when the host says they should.
     */
    public void setBottomless(boolean bottomless) { this.bottomless = bottomless; }

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
        // Bottomless takes everything and loses nothing: the contents are left
        // exactly as they were, so the lens is reversible.
        if (bottomless) return true;
        int have = count(key);
        if (have < n) return false;
        if (have == n) counts.remove(key);
        else counts.put(key, have - n);
        return true;
    }

    /** Everything in it, in the order it went in. */
    public Map<String, Integer> contents() {
        if (!bottomless) return Map.copyOf(counts);
        Map<String, Integer> all = new LinkedHashMap<>();
        for (String key : listing()) all.put(key, BOTTOMLESS);
        return Map.copyOf(all);
    }

    /** Every key, in the order it went in. */
    public List<String> keys() { return List.copyOf(listing()); }

    /**
     * What a bottomless satchel lists: the whole catalogue.
     *
     * <p><b>The registry, not a stored list.</b> The listing is what the
     * satchel screen draws and what "put out the first food you have" reads, so
     * it has to be the real set of things — and taking it from {@link Forage}
     * rather than from a snapshot taken when the mode went on is what makes an
     * item added next month appear in a debug satchel without anybody
     * remembering to add it.
     *
     * <p>{@link #count} answers for <em>any</em> key while bottomless, catalogue
     * or not, so a cost in something not yet registered is still free. Only the
     * listing is limited to things that have a name to print.
     */
    private List<String> listing() {
        if (!bottomless) return new ArrayList<>(counts.keySet());
        List<String> all = new ArrayList<>();
        for (Forage.Item item : Forage.all()) all.add(item.key());
        for (String key : counts.keySet()) {
            if (!all.contains(key)) all.add(key);
        }
        return all;
    }

    /** How many distinct kinds of thing are in it. */
    public int kinds() { return bottomless ? listing().size() : counts.size(); }

    /** How many things in total. */
    public int total() {
        if (bottomless) return kinds() * BOTTOMLESS;
        int n = 0;
        for (int c : counts.values()) n += c;
        return n;
    }

    /** Everything of one kind — what the cooking screen lists. */
    public List<String> ofKind(Forage.Kind kind) {
        List<String> out = new ArrayList<>();
        for (String key : listing()) {
            Forage.Item item = Forage.byKey(key);
            if (item != null && item.kind() == kind) out.add(key);
        }
        return out;
    }

    /**
     * What the screen prints for a count: the number, or {@code ∞}.
     *
     * <p>Here rather than at the two places that draw it, so the satchel strip
     * and the satchel screen cannot disagree about what unlimited looks like.
     */
    public String countLabel(String key) {
        return bottomless ? "∞" : String.valueOf(count(key));
    }

    /** Empty it. */
    public void clear() { counts.clear(); }

    // --- persistence --------------------------------------------------------------

    /** The satchel as a JSON object: one key per item. */
    public Map<String, Object> toMap() {
        return new LinkedHashMap<>(counts);
    }

    /**
     * Replace the contents from a save or a snapshot.
     *
     * <p>The {@linkplain #bottomless() lens} is deliberately <b>not</b> touched:
     * it is a property of the player rather than of what they are carrying, it
     * arrives on a different message from a different place
     * ({@code WatchPlayer.debugging}), and clearing it here would switch debug
     * mode off on the client every time the host sent a bag.
     */
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
