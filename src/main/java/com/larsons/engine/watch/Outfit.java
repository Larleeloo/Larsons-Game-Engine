package com.larsons.engine.watch;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What one player owns to wear, and what they have on.
 *
 * <p><b>Two collections, and they are two on purpose.</b> A wardrobe is what
 * has been bought and is yours for ever; an outfit is which of it is on the
 * figure this minute. Keeping them separate is what makes taking a hat off
 * free — a game where undressing loses you the hat is a game nobody undresses
 * in — and it is also why a cosmetic is not a satchel item: a bag you can drop
 * ({@link Spill}) is the wrong place for the only thing in this world you
 * cannot get back by walking somewhere.
 *
 * <p><b>One piece to a {@link Cosmetics.Slot}.</b> {@link #wear} puts something
 * on and takes off whatever was in its slot in the same motion, so the renderer
 * never meets two hats and never has to decide between them. Everything else
 * here follows from that: the worn set is at most six keys long, its order is
 * the slot order, and it round-trips through a save and a snapshot as a list of
 * those keys and nothing else.
 *
 * <p>Owned <b>per player rather than per party</b>, unlike the points that buy
 * it. That asymmetry is deliberate and is the same one the satchel already
 * makes: the book is shared because finding a bird is a thing you do for
 * everybody, and a coat is not.
 */
public final class Outfit {

    /** Everything bought, in the order it was bought — which is a small history. */
    private final Set<String> owned = new LinkedHashSet<>();

    /** What is on, one per slot. */
    private final Map<Cosmetics.Slot, String> worn = new EnumMap<>(Cosmetics.Slot.class);

    /** Whether this player owns a piece. */
    public boolean owns(String key) { return key != null && owned.contains(key); }

    /**
     * Add a piece to the wardrobe.
     *
     * @return {@code false} if it is not a real piece or was already owned,
     *         which is what stops a second purchase of the same hat
     */
    public boolean acquire(String key) {
        if (!Cosmetics.isWorn(key) || owned.contains(key)) return false;
        owned.add(key);
        return true;
    }

    /** Everything owned, in the order it was bought. */
    public List<String> wardrobe() { return List.copyOf(owned); }

    /** How many pieces are owned. */
    public int pieces() { return owned.size(); }

    /**
     * Put something on, taking off whatever was in its slot.
     *
     * @return {@code false} for anything unknown or unowned — the one rule the
     *         host enforces on this class, and the reason a client cannot dress
     *         itself in a cloak it never bought
     */
    public boolean wear(String key) {
        Cosmetics.Piece piece = Cosmetics.byKey(key);
        if (piece == null || !owned.contains(key)) return false;
        worn.put(piece.slot(), key);
        return true;
    }

    /**
     * Take off whatever is in a slot.
     *
     * @return {@code true} if something came off
     */
    public boolean takeOff(Cosmetics.Slot slot) {
        return slot != null && worn.remove(slot) != null;
    }

    /**
     * Put a piece on if it is off and take it off if it is on.
     *
     * <p>One verb rather than two, because there is one gesture: a player
     * clicks the row. Two verbs would need the screen to know which one to send,
     * which means the screen and the host would each have an opinion about what
     * is currently worn and they would eventually disagree.
     *
     * @return what to tell the player, or {@code null} if nothing happened
     */
    public String toggle(String key) {
        Cosmetics.Piece piece = Cosmetics.byKey(key);
        if (piece == null || !owned.contains(key)) return null;
        if (key.equals(worn.get(piece.slot()))) {
            worn.remove(piece.slot());
            return "Took off the " + piece.name();
        }
        String was = worn.put(piece.slot(), key);
        Cosmetics.Piece off = Cosmetics.byKey(was);
        return off == null ? "Put on the " + piece.name()
                : "Put on the " + piece.name() + ", and the " + off.name() + " away";
    }

    /** What is worn in a slot, or {@code null}. */
    public String wornIn(Cosmetics.Slot slot) { return worn.get(slot); }

    /** Whether a particular piece is on. */
    public boolean wearing(String key) {
        Cosmetics.Piece piece = Cosmetics.byKey(key);
        return piece != null && key.equals(worn.get(piece.slot()));
    }

    /** Everything on, in slot order — what the renderer walks. */
    public List<String> wornKeys() {
        List<String> out = new ArrayList<>();
        for (Cosmetics.Slot slot : Cosmetics.Slot.values()) {
            String key = worn.get(slot);
            if (key != null) out.add(key);
        }
        return out;
    }

    /** Whether anything at all is on. */
    public boolean bare() { return worn.isEmpty(); }

    // --- the wire ---------------------------------------------------------------------

    /**
     * What is on, as one short string — {@code "knitted_beanie,wool_scarf"}.
     *
     * <p>This is the whole of what a walker's cosmetics cost on the wire, and
     * it rides on the player row of a snapshot twenty times a second, so it is
     * a string rather than an array of objects: at most six keys and no commas
     * inside any of them. What is <em>owned</em> deliberately does not go here.
     * Nobody else's screen has any use for the contents of your wardrobe, and
     * sending eight wardrobes to eight people every tick to draw six hats would
     * be the one extravagance in a protocol whose entire argument is that a
     * trading post costs nothing to send.
     */
    public String wornLine() { return String.join(",", wornKeys()); }

    /** Replace what is on from {@link #wornLine}, ignoring anything unknown. */
    public void loadWorn(String line) {
        worn.clear();
        if (line == null || line.isBlank()) return;
        for (String key : line.split(",")) {
            Cosmetics.Piece piece = Cosmetics.byKey(key.trim());
            // Straight into the map rather than through wear(): a client
            // drawing somebody else's hat has no copy of their wardrobe and
            // never will, so "do they own it" is a question only the host can
            // ask and has already asked.
            if (piece != null) worn.put(piece.slot(), piece.key());
        }
    }

    // --- persistence -------------------------------------------------------------------

    /** The wardrobe and the outfit, for a save or for this player's own screen. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("own", List.copyOf(owned));
        m.put("on", wornLine());
        return m;
    }

    /** Restore both from {@link #toMap}. */
    public void load(Map<String, Object> m) {
        owned.clear();
        worn.clear();
        if (m == null) return;
        for (String key : WatchJson.strings(m, "own")) {
            // Anything the catalogue no longer has is dropped rather than kept:
            // a piece deleted between one session and the next is not a piece,
            // and carrying its key for ever would eventually be a wardrobe full
            // of things that draw as nothing.
            if (Cosmetics.isWorn(key)) owned.add(key);
        }
        // Through wear() rather than into the map, so a save that says somebody
        // is wearing a coat they do not own puts nothing on. loadWorn is the
        // one that skips that check, and it can: it is describing a stranger.
        for (String key : WatchJson.str(m, "on", "").split(",")) wear(key.trim());
    }

    @Override public String toString() {
        return bare() ? "nothing on" : String.join(", ", wornKeys());
    }
}
