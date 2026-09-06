package com.larsons.engine.watch;

import com.larsons.engine.watch.world.WatchBiome;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Things to wear, and where on a walker each of them goes.
 *
 * <p><b>Over the top of the character, never instead of it.</b> Everybody in
 * this game is the same figure — {@link com.larsons.engine.watch.render.WalkerModel}'s
 * coat, head, four limbs, hat, boots and satchel, tinted by player id so a
 * party spread across a valley can tell each other apart. Nothing here replaces
 * any of that. A piece is a small pile of extra boxes hung on a part of the
 * body that is already there, which has three consequences worth stating:
 *
 * <ul>
 *   <li>a walker wearing nothing is <em>exactly</em> the walker this game has
 *       always drawn, to the triangle. Cosmetics are optional in the strong
 *       sense: switched off, they cost nothing and change nothing;</li>
 *   <li>the coat tint still reads, because the tint is on the figure underneath
 *       and most pieces are small. The two that are not — a hood and a cape,
 *       which do cover a person's outline — are {@linkplain Piece#tinted()
 *       drawn in the wearer's own colour} instead, so a party in matching
 *       cloaks is still a party of six different people;</li>
 *   <li>and nothing about a piece touches the walk. There is no stat on this
 *       page and there is not going to be one. Waders do not make you faster
 *       in water and a fur collar does not keep the cold out, because the
 *       moment one of them did, "optional" would be a lie.</li>
 * </ul>
 *
 * <h2>Slots</h2>
 *
 * <p>Six of them, one piece worn in each, and they are six because that is how
 * many places on the standing figure have room for something without two pieces
 * fighting over the same boxes. See {@link Slot}.
 *
 * <h2>Prices</h2>
 *
 * <p>The same points a shelf is priced in, read against
 * {@link com.larsons.engine.watch.life.Rarity#points()}: a common bird is 1 and
 * a legendary is 100. So mittens are an afternoon, a boater is a good morning's
 * walking, and the heron cloak is a thing somebody wears because they went and
 * found something. The ladder is deliberately wide — the cheapest piece is 16
 * and the dearest is 340 — because the whole point of a cosmetic is that it is
 * a thing to want rather than a thing to need, and a want with no expensive end
 * to it is a shopping list.
 *
 * <p><b>Paid out of the guide's balance, like everything else over a counter.</b>
 * One book, one page, one purse — see {@link WatchGame#buyWorn}. What a piece is
 * <em>not</em> is a satchel item: it never goes in a bag, so it is never
 * dropped on death, never cooked with, and never handed to anybody. It goes in
 * an {@link Outfit}, which belongs to the person rather than to the party.
 */
public final class Cosmetics {

    /**
     * Where on a walker a piece hangs.
     *
     * <p>One piece to a slot, so a hood and a boater cannot occupy the same
     * head. Putting something on takes off whatever was already in its slot,
     * which is the only rule in this class and the whole of why the renderer
     * never has to resolve two things overlapping.
     */
    public enum Slot {
        /** On the crown of the hat the figure is already wearing. */
        HEAD("Head", "over the hat"),
        /** On the front of the face, under whatever is on the head. */
        FACE("Face", "on the face"),
        /** Round the neck and over the shoulders. */
        NECK("Neck", "at the throat"),
        /** Over the satchel, down the back. */
        BACK("Back", "over the pack"),
        /** Over both hands. */
        HANDS("Hands", "on the hands"),
        /** Over both boots. */
        FEET("Feet", "over the boots");

        private final String label;
        private final String where;

        Slot(String label, String where) {
            this.label = label;
            this.where = where;
        }

        /** What the screen calls this slot. */
        public String label() { return label; }

        /** Where a piece in it sits, for the line under a row. */
        public String where() { return where; }
    }

    /**
     * One thing to wear.
     *
     * @param key    stable identifier, as used by saves, the wire and the
     *               renderer's own switch
     * @param name   what a player sees
     * @param slot   where it goes
     * @param price  what it costs in points, after the post's markup
     * @param rgb    the main colour it is drawn in
     * @param trim   the second colour — a band, a buckle, a lining. Every piece
     *               has one, because a single-coloured pile of boxes reads as a
     *               lump and a band across it reads as a made thing. Exactly
     *               {@code KeeperModel}'s hatband argument
     * @param tinted whether {@code rgb} is ignored in favour of the wearer's own
     *               coat colour. Only for the two pieces big enough to hide a
     *               walker's outline; see the class note
     * @param note   one line for the row under it
     */
    public record Piece(String key, String name, Slot slot, int price, int rgb, int trim,
                        boolean tinted, String note) {

        /** What the shop row prints on the right, while it is still for sale. */
        public String priceLine() { return price + (price == 1 ? " pt" : " pts"); }

        /** The same piece at a keeper's own prices. */
        Piece at(double markup) {
            return new Piece(key, name, slot, Math.max(1, (int) Math.round(price * markup)),
                    rgb, trim, tinted, note);
        }
    }

    /**
     * The catalogue, at list price and in the order a rail shows it — cheapest
     * first, which is also the order {@link #rail} biases its picking toward.
     *
     * <p>Insertion-ordered for {@link Forage}'s reason: this list is drawn on a
     * screen, and a map whose iteration order is salted per JVM run would
     * reshuffle every keeper's rail between sessions.
     */
    private static final Map<String, Piece> CATALOGUE = build();

    private static final List<Piece> ALL = List.copyOf(CATALOGUE.values());

    /** How many pieces one keeper hangs on their rail. */
    private static final int RAIL_MIN = 3, RAIL_MAX = 5;

    private Cosmetics() {}

    /** Everything anybody could wear, at list price, cheapest first. */
    public static List<Piece> all() { return ALL; }

    /** The piece with this key, or {@code null}. */
    public static Piece byKey(String key) {
        return key == null ? null : CATALOGUE.get(key);
    }

    /** Whether this key is something to wear at all. */
    public static boolean isWorn(String key) { return byKey(key) != null; }

    /** The slot a key belongs in, or {@code null} for anything that is not worn. */
    public static Slot slotOf(String key) {
        Piece piece = byKey(key);
        return piece == null ? null : piece.slot();
    }

    /** Everything that goes in one slot. */
    public static List<Piece> inSlot(Slot slot) {
        List<Piece> out = new ArrayList<>();
        for (Piece piece : ALL) {
            if (piece.slot() == slot) out.add(piece);
        }
        return out;
    }

    /**
     * One keeper's rail: which of the catalogue they happen to have hanging, at
     * their own prices.
     *
     * <p><b>A handful, not the catalogue</b> — {@link Trading#shelf}'s argument,
     * and the same code shape, because it is the same problem: a post that sold
     * every hat in the world would make the second post you found pointless, and
     * finding another one is what putting them in the wild is for.
     *
     * <p>Biased toward the cheap end and toward what the country round the post
     * would actually be selling. Nobody is guaranteed anything: a rail with no
     * hat on it is a rail with no hat on it, and the walk to the next post is
     * the game.
     */
    public static List<Piece> rail(WatchBiome biome, double markup, Random rng) {
        List<String> pool = new ArrayList<>();
        // What this country wears, first in the pool and therefore usually
        // picked — a marsh post has the waders and the high cold post has the
        // fur, which is most of what makes a rail feel placed rather than
        // rolled.
        for (String key : localTo(biome)) {
            if (CATALOGUE.containsKey(key) && !pool.contains(key)) pool.add(key);
        }
        for (Piece piece : ALL) {
            if (!pool.contains(piece.key())) pool.add(piece.key());
        }
        List<Piece> chosen = new ArrayList<>();
        int want = RAIL_MIN + rng.nextInt(RAIL_MAX - RAIL_MIN + 1);
        while (chosen.size() < want && !pool.isEmpty()) {
            chosen.add(CATALOGUE.get(pool.remove(pick(rng, pool.size()))).at(markup));
        }
        return List.copyOf(chosen);
    }

    /**
     * How strongly a rail leans on the front of its pool — the local and the
     * cheap — where {@code 1} would be a flat draw.
     *
     * <p>{@link Trading#shelf} picks off the front <em>third</em> and stops
     * there, which is right for a shelf because a shelf is short and every line
     * on it is something somebody needs. It would be wrong here: the whole
     * argument for an expensive end to this catalogue is that the heron cloak is
     * a thing to go looking for, and a rule that can never reach the back of the
     * pool is a rule that puts it in the game and in nobody's shop. A curve
     * instead of a cut-off, so the dear things are rare rather than impossible —
     * about one rail in ten has the last piece on it.
     */
    private static final double LOCAL_BIAS = 2.5;

    /** An index into a pool of {@code size}, biased hard toward its front. */
    private static int pick(Random rng, int size) {
        return Math.min(size - 1, (int) (size * Math.pow(rng.nextDouble(), LOCAL_BIAS)));
    }

    /** What the country round a post would be selling to walk out in. */
    private static List<String> localTo(WatchBiome biome) {
        List<String> out = new ArrayList<>();
        if (biome == null) return out;
        if (biome.temperature() < 40) {
            out.add("wool_mittens");
            out.add("knitted_beanie");
            out.add("fur_collar");
        }
        if (biome.humidity() > 65) {
            out.add("river_waders");
            out.add("oilskin_hood");
            out.add("oilskin_cape");
        }
        if (biome.temperature() > 65) out.add("straw_boater");
        if (!biome.trees().isEmpty()) out.add("feathered_band");
        if (biome.rockDensity() > 0.004) out.add("canvas_gaiters");
        // The strange places sell the strange things, and an antler circlet off
        // a shelf in the crystal highlands is the reward for having got there.
        if (biome.strangeness() > WatchBiome.ORDINARY) {
            out.add("antler_circlet");
            out.add("heron_cloak");
            out.add("moth_veil");
        }
        return out;
    }

    private static Map<String, Piece> build() {
        Map<String, Piece> map = new LinkedHashMap<>();

        // --- what somebody would put on first ---------------------------------
        piece(map, "wool_mittens", "Wool Mittens", Slot.HANDS, 16, 0xB4553F, 0xE0D2B8,
                "Knitted on somebody's porch. One size, and it is not yours.");
        piece(map, "knitted_beanie", "Knitted Beanie", Slot.HEAD, 18, 0x4A5A3C, 0xC9B98A,
                "Pulled down over the brim. Warm, and nobody has ever looked good in one.");
        piece(map, "canvas_gaiters", "Canvas Gaiters", Slot.FEET, 20, 0x8A6A3A, 0x40382C,
                "Buckled up the shin. Keeps the burrs out, mostly.");
        piece(map, "wool_scarf", "Wool Scarf", Slot.NECK, 22, 0xA8482F, 0xD9C68A,
                "Wound twice, with one end left long. That end is the whole point.");
        piece(map, "rolled_bedroll", "Rolled Bedroll", Slot.BACK, 26, 0xC9B98A, 0x6E4B2E,
                "Strapped across the satchel. It says you meant to be out this long.");
        piece(map, "wire_spectacles", "Wire Spectacles", Slot.FACE, 28, 0xB0763A, 0xCFE4EA,
                "Thin gold wire. The far shore is no clearer and you look like you know things.");

        // --- worked, and dearer for it ----------------------------------------
        piece(map, "feathered_band", "Feathered Band", Slot.HEAD, 34, 0x3E4A5C, 0xE4DCC4,
                "A hatband with one moulted primary in it. Not one you plucked.");
        piece(map, "glass_lanyard", "Glass Lanyard", Slot.NECK, 38, 0x5C4A3A, 0xC08A3A,
                "Two braided straps and a brass ring, for a glass you keep dropping.");
        piece(map, "leather_gloves", "Leather Gloves", Slot.HANDS, 42, 0x6E4B2E, 0x3A2C20,
                "Cut close, stitched at the seam. Rope will not take your palms off now.");
        piece(map, "straw_boater", "Straw Boater", Slot.HEAD, 48, 0xD8C88A, 0x2F5A6B,
                "Flat brim, blue ribbon. Absurd in a wood and worn in one anyway.");
        piece(map, "snow_goggles", "Snow Goggles", Slot.FACE, 62, 0x3A3A3E, 0x8FA84C,
                "Smoked glass on a strap. For the glare off water as much as off snow.");
        piece(map, "oilskin_hood", "Oilskin Hood", Slot.HEAD, 74, 0x000000, 0x40514B,
                "Waxed and up. Rain runs off the peak instead of down your neck.");
        piece(map, "river_waders", "River Waders", Slot.FEET, 78, 0x40514B, 0x2A2E28,
                "To the knee, rubberised, and they squeak. Every fisher owns a pair.");

        // --- the far end, which is the point of the ladder ---------------------
        piece(map, "moth_veil", "Moth Veil", Slot.FACE, 96, 0xE4E0D4, 0x8A8070,
                "Fine net off the brim. Midges hate it and so will everyone.");
        piece(map, "fur_collar", "Fur Collar", Slot.NECK, 130, 0x7A6248, 0x3A2E22,
                "Deep, dark and shed rather than taken. Ask the keeper; they will say so.");
        piece(map, "oilskin_cape", "Oilskin Cape", Slot.BACK, 155, 0x000000, 0x8A6A3A,
                "A yoke and a long panel to the knee. The whole wood runs off it.");
        piece(map, "antler_circlet", "Antler Circlet", Slot.HEAD, 195, 0x8A7A5C, 0xD8CDB4,
                "Cast antler, bound to a birch hoop. Found on the ground, like everything here.");
        piece(map, "heron_cloak", "Heron Cloak", Slot.BACK, 340, 0x6E7580, 0xBFC7B0,
                "Courses of grey feather over a long panel. Somebody spent a winter on it.");

        // Unmodifiable rather than Map.copyOf: the order is part of what this
        // returns. See ALL, and the note on Forage.ALL for the shuffled-satchel
        // bug that is the reason this is written down twice.
        return Collections.unmodifiableMap(map);
    }

    private static void piece(Map<String, Piece> map, String key, String name, Slot slot,
                              int price, int rgb, int trim, String note) {
        // A piece with no colour of its own is a piece drawn in the wearer's:
        // the two big ones, and the sentinel is what keeps that fact in the one
        // table rather than in a list of keys somewhere else. See Piece.tinted.
        map.put(key, new Piece(key, name, slot, price, rgb, trim, rgb == 0, note));
    }
}
