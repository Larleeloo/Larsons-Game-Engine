package com.larsons.engine.watch;

import java.util.List;

/**
 * Debug mode: <b>a code typed on the number keys, and everything is
 * unlimited.</b>
 *
 * <h2>Why a typed code and not a key bind or a menu item</h2>
 *
 * <p>A rebindable action would appear on the controls screen, which is a
 * screen for the game's own verbs; a menu item would sit on the pause screen
 * inviting a first-time player to press it and skip the whole game. A code is
 * the third thing, and it is the one every game has used for forty years: it
 * costs nothing to nobody who does not know it, and anybody who does know it
 * can have it in four keystrokes without leaving the walk.
 *
 * <p>It is {@value #CODE} — the port the walk hosts on, which is as good a
 * mnemonic as any and is written down in the README beside it. Typed anywhere
 * in the walk, panels included; typed again, it goes off. There is no
 * feedback while it is half typed, on purpose, and a very loud line when it
 * lands.
 *
 * <h2>What it grants, and why the list is short</h2>
 *
 * <p>{@link Power} is the whole of it, and it is a short list rather than twelve
 * rows because the first row is <b>structural</b>: debug mode does not hand out
 * a list of items, it makes the satchel {@linkplain Satchel#bottomless()
 * bottomless}. Almost every cost in this game — every recipe, every build piece,
 * a feeder's serving, a seed going into the ground, the rod, the spyglass — is
 * paid by asking a {@link Satchel} whether it {@code has} something and then
 * {@code take}-ing it. One lens over that one class covers all of them, and it
 * covers whatever is added next <em>without being edited</em>: an item added
 * to {@link Forage} is already unlimited, a recipe added to {@link Recipes} is
 * already affordable, a piece added to {@code BuildPiece} is already free.
 *
 * <p>"Almost" is {@link Power#POINTS}, and it is worth reading as the exception
 * that proves the rule. A trading post's prices are paid out of the
 * {@link FieldGuide}'s balance rather than out of a satchel, so the lens does
 * not reach them and no amount of cleverness would make it. Covering that cost
 * took exactly what the note below says it should: one row, and one
 * {@code if (player.debugging())}.
 *
 * <p>That is the answer to "make it grow as more features are added". A debug
 * mode built as a list of grants goes stale the week after it is written,
 * because the list is a copy of a registry that keeps moving. This one is a
 * lens over the registry itself.
 *
 * <p>When a future feature needs something debug mode cannot already give it —
 * a spawn, a teleport, a clock that can be wound forward — the shape is:
 * <b>add a row to {@link Power}, and one {@code if (player.debugging())} where
 * it acts</b>. The row is what puts it on the readout; the {@code if} is the
 * feature. Nothing else has to know.
 *
 * <h2>Whose world it is</h2>
 *
 * <p>The mode is per player and the <em>server</em> decides who may have it —
 * see {@code WatchGame.debug}. On your own walk, or on one you are hosting,
 * the code works. On somebody else's walk it is refused, because the field
 * guide is shared: a stranger with unlimited suet cake writes their way
 * through a book four other people are keeping.
 */
public final class Debug {

    /**
     * The code. Four digits, in order, on the number row or the keypad.
     *
     * <p>{@code 7799} is {@code WatchProto.DEFAULT_PORT} — written out rather
     * than derived from it, because a cheat code that changes when somebody
     * moves a port is a cheat code that stops working for reasons nobody can
     * see.
     */
    public static final String CODE = "7799";

    /** How long a half-typed code waits before it is forgotten, in seconds. */
    public static final double FORGET_SECONDS = 2.5;

    /**
     * What the mode grants.
     *
     * <p>Adding one is adding a row — see the class note for the other half of
     * that sentence, which is the {@code if} that reads it.
     */
    public enum Power {

        /**
         * A bottomless satchel: every item, in any number, for ever.
         *
         * <p>The one that does the work. Crafting, building, feeders, planting,
         * fishing and the spyglass are all costs paid out of a satchel, so all
         * of them come free with this and none of them is named here.
         */
        ITEMS("Unlimited items",
                "Every recipe, every build piece, every feeder and every tool — "
                        + "anything paid for out of the satchel."),

        /**
         * A keeper's whole shelf, for nothing.
         *
         * <p><b>The first row that had to be added</b>, and the class note above
         * predicted its shape exactly: a row here and one
         * {@code if (player.debugging())} where it acts — in {@code WatchGame.buy}.
         * It exists because {@link Trading} broke the invariant the first row
         * rests on. Every cost in this game <em>was</em> a {@code has} and a
         * {@code take} against a {@link Satchel}; a purchase at a trading post is
         * paid out of the {@link FieldGuide}'s balance instead, so a bottomless
         * satchel does not reach it and never could.
         *
         * <p>It buys, rather than granting: the goods still go in the satchel and
         * the line still appears in the log, so a host testing what a shelf hands
         * over sees exactly what a player would. What it does not do is turn the
         * page — a stamp is a thing the guide records and debug mode is a lens,
         * not an edit.
         */
        POINTS("Unlimited points",
                "Anything on any keeper's shelf, at any price, without spending "
                        + "what the guide earned."),

        /**
         * The readout: what the world is doing, in numbers, over the top left.
         *
         * <p>The other half of what "debug mode" means. It is also where a new
         * power announces itself, which is why it is a power rather than a
         * setting.
         */
        READOUT("Readout",
                "Position, chunk, biome, streaming, triangles and the party, "
                        + "live on screen.");

        private final String label;
        private final String note;

        Power(String label, String note) {
            this.label = label;
            this.note = note;
        }

        /** What the readout calls it. */
        public String label() { return label; }

        /** One line saying what it does. */
        public String note() { return note; }
    }

    private static final List<Power> POWERS = List.of(Power.values());

    private Debug() {}

    /** Everything the mode grants, in the order the readout lists it. */
    public static List<Power> powers() { return POWERS; }

    /** Whether a string of digits is the code. */
    public static boolean isCode(String typed) { return CODE.equals(typed); }

    /**
     * The keypad: the last few digits somebody typed, and whether they spell
     * the code.
     *
     * <p>A rolling buffer rather than a match from the beginning, so
     * {@code 1-7-7-9-9} works as well as {@code 7-7-9-9} — a player who
     * fumbles the first key should not have to stop and start again, and
     * nothing else in this game is listening to the number keys for the buffer
     * to interfere with.
     *
     * <p>It forgets after {@link #FORGET_SECONDS} of no digits, which is what
     * stops a stray {@code 7} from an hour ago sitting at the front of the
     * buffer waiting to complete a code the player did not type.
     *
     * <p>Client side, and deliberately: the code is a way of <em>asking</em>.
     * What comes of the asking is the server's business.
     */
    public static final class Pad {

        private final StringBuilder typed = new StringBuilder();
        private double idle;

        /**
         * Somebody pressed a number key.
         *
         * @param digit {@code 0}–{@code 9}
         * @return {@code true} if that keystroke completed the code
         */
        public boolean type(int digit) {
            if (digit < 0 || digit > 9) return false;
            idle = 0;
            typed.append((char) ('0' + digit));
            while (typed.length() > CODE.length()) typed.deleteCharAt(0);
            if (!isCode(typed.toString())) return false;
            typed.setLength(0);
            return true;
        }

        /** Let a half-typed code expire. */
        public void tick(double dt) {
            if (typed.length() == 0) return;
            idle += dt;
            if (idle >= FORGET_SECONDS) typed.setLength(0);
        }

        /** How many digits are waiting — for tests, and for nothing on screen. */
        public int pending() { return typed.length(); }

        /** Forget whatever has been typed. */
        public void clear() { typed.setLength(0); }
    }
}
