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

    /**
     * What colour each piece is being drawn in, where the player has said.
     *
     * <p><b>Per player, not per piece.</b> Two people in the same coat are two
     * people who chose the same coat, and if one of them dyes theirs the other
     * one's does not change — so this cannot live on {@link Cosmetics.Piece},
     * which is a catalogue row and is shared by everybody in the world.
     *
     * <p>Only what has actually been changed. A piece with no entry is drawn in
     * the colours its artist gave it, which is what every piece starts as and
     * what most of them stay, so the common outfit costs nothing here and
     * nothing on the wire.
     */
    private final Map<String, Integer> dyed = new LinkedHashMap<>();

    /**
     * Give somebody the clothes they walk out in.
     *
     * <p>Called when a player joins and again after a save is read, because
     * both of those are moments where somebody might otherwise be standing in
     * a wood in their underwear. The kit is free and universal, so this is not
     * a grant so much as a statement of fact — {@link #acquire} refuses what is
     * already owned, so calling it twice costs nothing.
     *
     * <p>What it does <em>not</em> do is dress anybody. See {@link #dressIn}.
     */
    public void grantStandardKit() {
        for (Cosmetics.Piece piece : Cosmetics.standardKit()) acquire(piece.key());
    }

    /**
     * Put the standard kit on, in every slot that is still empty.
     *
     * <p><b>Empty slots only, and that is the whole subtlety.</b> This runs
     * after a save is read as well as on a fresh walker, and a walk saved in a
     * heron cloak has to come back in the heron cloak rather than in the coat
     * it started in. It also runs on a save written before the kit existed, in
     * which every slot but the ones they had bought is empty — which is
     * precisely the case that needs dressing.
     *
     * @param hair which hairstyle this figure starts in; see {@link Figure#hair}
     */
    public void dressIn(String hair) {
        for (Cosmetics.Piece piece : Cosmetics.standardKit()) {
            if (piece.slot() == Cosmetics.Slot.HAIR) continue;
            if (worn.get(piece.slot()) == null) wear(piece.key());
        }
        if (worn.get(Cosmetics.Slot.HAIR) == null) wear(hair);
    }

    /**
     * Whether the whole catalogue counts as owned — <b>debug mode's wardrobe.</b>
     *
     * <p><b>A lens over what is owned, not a gift of it</b>, which is exactly
     * what {@code Satchel.bottomless} is and is the shape {@link Debug} asks
     * every power to take. Nothing is added when it goes on and nothing is taken
     * away when it comes off: {@link #owned} is untouched underneath, so a walk
     * that spent an hour trying on a heron cloak and then left debug mode is
     * the walk it was, wearing whatever of its own it had on.
     *
     * <p>Being a lens rather than a list is also what makes it not go stale. A
     * piece added to {@link Cosmetics} next month is in this wardrobe the day it
     * is added, with nothing here or in {@code Debug} edited — the same promise
     * the bottomless satchel makes about {@link Forage}.
     */
    private boolean everything;

    /** Whether this player owns a piece. */
    public boolean owns(String key) {
        if (key == null) return false;
        return everything ? Cosmetics.isWorn(key) : owned.contains(key);
    }

    /** Whether the catalogue is open — see {@link #everything}. */
    public boolean openWardrobe() { return everything; }

    /** Open the whole catalogue, or shut it again. */
    public void setOpenWardrobe(boolean on) { this.everything = on; }

    // --- colour ---------------------------------------------------------------------

    /**
     * What colour a piece is drawn in for this player, or {@code 0} for the
     * colours its artist gave it.
     *
     * <p>Zero rather than {@code null} because it is read once per piece per
     * frame by the renderer, and because {@code 0} is unusable as a colour
     * anyway: black cloth in this world is {@code 0x1A1A1A} and a true
     * {@code 000000} has never been anything but a sentinel — see
     * {@code Cosmetics.Piece.tinted}, which already spends it.
     */
    public int colourOf(String key) {
        Integer chosen = key == null ? null : dyed.get(key);
        return chosen == null ? 0 : chosen;
    }

    /**
     * Dye a piece, or put it back to how it was made with {@code 0}.
     *
     * <p>Allowed on anything in the catalogue rather than only on what is
     * owned. That is deliberate and it is the opposite call from {@link #wear}:
     * wearing a cloak you have not bought is claiming something, and choosing
     * what colour you would dye it if you had one is not — so the wardrobe
     * screen can show a real preview of a coat on the rail instead of a swatch.
     *
     * @return whether anything changed
     */
    public boolean dye(String key, int rgb) {
        if (!Cosmetics.isWorn(key)) return false;
        Integer was = rgb == 0 ? dyed.remove(key) : dyed.put(key, rgb & 0xFFFFFF);
        return (was == null ? 0 : was) != (rgb & 0xFFFFFF);
    }

    /** Every piece this player has dyed, and to what. */
    public Map<String, Integer> colours() { return Map.copyOf(dyed); }

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

    /**
     * Whether a piece was really acquired, ignoring {@link #everything}.
     *
     * <p>The one question debug mode must not answer "yes" to on its own. A
     * keeper refusing a sale with "you already have the boater" has to mean the
     * boater is in the wardrobe rather than that the catalogue is open, or
     * {@code Debug.Power.POINTS} would quietly stop being able to buy a
     * cosmetic at all — which is the one thing that row exists to do.
     */
    public boolean bought(String key) { return key != null && owned.contains(key); }

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
        if (piece == null || !owns(key)) return false;
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
        if (piece == null || !owns(key)) return null;
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

    /**
     * What has been dyed, as one short string — {@code "field_coat:8A3B2E"}.
     *
     * <p>Beside {@link #wornLine} on the player row and for its reason: a coat
     * is dyed to be seen, so everybody's renderer needs it. Only what has
     * actually been changed goes out, so a party who have left their clothes
     * alone cost nothing at all — which is most parties, most of the time.
     *
     * <p>At most nine entries and no commas or colons inside a key, so it stays
     * a string rather than becoming an array of objects on a message that goes
     * out twenty times a second.
     */
    public String dyeLine() {
        if (dyed.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, Integer> e : dyed.entrySet()) {
            if (out.length() > 0) out.append(',');
            out.append(e.getKey()).append(':')
                    .append(String.format("%06X", e.getValue() & 0xFFFFFF));
        }
        return out.toString();
    }

    /** Replace the dyes from {@link #dyeLine}, ignoring anything unknown. */
    public void loadDyes(String line) {
        dyed.clear();
        if (line == null || line.isBlank()) return;
        for (String entry : line.split(",")) {
            int at = entry.indexOf(':');
            if (at <= 0) continue;
            String key = entry.substring(0, at).trim();
            if (!Cosmetics.isWorn(key)) continue;
            try {
                dyed.put(key, Integer.parseInt(entry.substring(at + 1).trim(), 16)
                        & 0xFFFFFF);
            } catch (NumberFormatException e) {
                // A colour that is not a colour is no colour, which draws the
                // piece as its artist made it. Nothing anybody sends here can
                // stop a walker being drawn.
            }
        }
    }

    // --- persistence -------------------------------------------------------------------

    /** The wardrobe and the outfit, for a save or for this player's own screen. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("own", List.copyOf(owned));
        m.put("on", wornLine());
        if (!dyed.isEmpty()) m.put("dye", dyeLine());
        return m;
    }

    /** Restore all three from {@link #toMap}. */
    public void load(Map<String, Object> m) {
        owned.clear();
        worn.clear();
        dyed.clear();
        if (m == null) return;
        loadDyes(WatchJson.str(m, "dye", ""));
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
