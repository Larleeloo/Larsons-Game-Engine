package com.larsons.engine.watch.home;

import java.util.ArrayList;
import java.util.List;

/**
 * The houses a keeper will sell you, and what each one costs in points.
 *
 * <h2>Bought whole, not built up</h2>
 *
 * <p>This replaces building. The old {@code watch.build} package sold a player
 * ten boxes — a post, a beam, a floor, a wall — and asked them to make a house
 * out of them, and the honest thing to say about it is that nobody ever did:
 * what a party actually built was a floor with a roof on it, because a wall
 * that is one 2.6 m box cannot have a window in it, a door cannot line up with
 * anything, and a staircase is not expressible at all. A game about standing
 * still and looking at birds had acquired an afternoon of block-laying as its
 * price of admission, and the thing it charged that price for was worse than
 * the trading post the generator puts down for free.
 *
 * <p>So a home is now <b>a thing you buy, complete</b>, with its floors, its
 * walls, its window openings, its stairs, its ladders and its furniture already
 * in it — the {@link HouseKit} draws the whole carpentry from the plan — and
 * the only decisions left to the player are the two that were always the
 * interesting ones: <em>which</em> house, and <em>where</em>.
 *
 * <h2>Points, and what they buy</h2>
 *
 * <p>Priced in the same points a trading post charges, which come from looking
 * at animals ({@link com.larsons.engine.watch.FieldGuide}) and are the party's
 * rather than any one player's. Read the ladder against
 * {@link com.larsons.engine.watch.life.Rarity#points()} — a common bird is 1,
 * an uncommon 3, a legendary 100 — and it says this: a {@link #LEAN_TO} is an
 * hour's watching, a {@link #CABIN} is a good weekend, and the
 * {@link #MANSION} is the thing a party works toward for as long as they play.
 *
 * <p><b>Size and intricacy scale with the price, and they do it structurally.</b>
 * Every plan carries a footprint, a number of storeys, a roof and a level of
 * {@link Trim}, and {@link HouseKit} reads all four: more money buys more
 * ground, more floors above it, a roof with more sides to it, glazed windows
 * instead of holes, a staircase instead of a ladder, a chimney, a balcony,
 * furniture, and at the top a house that stops being one box and grows wings
 * and a tower. Nothing here is a cosmetic tier — a bigger number is more house
 * to walk around inside. {@code HomesTest} holds that promise to the wall: the
 * catalogue must be monotonic in price for volume <em>and</em> for the number of
 * parts the kit emits.
 *
 * <h2>The two families</h2>
 *
 * <p>Half the catalogue stands on the ground and half is {@linkplain #tree()
 * fixed in a tree}, and a treehouse always comes with the ladder that reaches
 * it — see {@link HouseKit}, which runs one from the ground to the deck as part
 * of the house rather than leaving a player to buy their way up. That was the
 * one thing the old build pieces got right and the one thing they made you do
 * yourself, three metres of ladder at a time.
 */
public enum HousePlan {

    // --- on the ground ----------------------------------------------------------------

    /** Two walls, a sloping roof and somewhere dry to sit. The cheapest roof. */
    LEAN_TO("lean_to", "Lean-To", 45, 1.5, 1.7, 1, Roof.LEAN, Trim.ROUGH, false,
            "Three sides and a slope. Somewhere dry to sit out a shower."),

    /** A box with a lookout deck on the lid, and a ladder up to it. */
    FORT("fort", "Timber Fort", 120, 1.9, 2.0, 1, Roof.DECK, Trim.ROUGH, false,
            "Four walls, a railed roof deck, and the ladder up to it."),

    /** One room, a hearth, and a proper gable. The first house that is a house. */
    CABIN("cabin", "Cabin", 300, 2.4, 3.0, 1, Roof.GABLE, Trim.PLAIN, false,
            "One room, a stone hearth and a pitched roof. Shutters on the windows."),

    /** Two floors, a staircase, glazed windows and a chimney. */
    LODGE("lodge", "Lodge", 760, 3.0, 3.7, 2, Roof.GABLE, Trim.FITTED, false,
            "Two floors and a stair between them. Glazed, furnished, and a chimney."),

    /** Three floors under a hipped roof, with a balcony over the door. */
    MANOR("manor", "Manor House", 1650, 3.8, 5.0, 3, Roof.HIP, Trim.FINE, false,
            "Three floors, a hipped roof, a balcony over the door and a study."),

    /** Wings, a tower, and more rooms than anybody needs. The top of the ladder. */
    MANSION("mansion", "Mansion", 3400, 4.6, 6.4, 3, Roof.HIP, Trim.GRAND, false,
            "A hall with two wings and a tower, over three floors. Absurd, and yours."),

    // --- in the trees -----------------------------------------------------------------

    /** A railed platform and the ladder to it. The cheapest way up a tree. */
    TREE_PERCH("tree_perch", "Tree Perch", 90, 1.6, 1.6, 1, Roof.OPEN, Trim.ROUGH, true,
            "A railed platform in the canopy, and the ladder up the trunk."),

    /** Walls and a watching slot, up where the birds are. */
    TREE_HIDE("tree_hide", "Tree Hide", 280, 2.0, 2.2, 1, Roof.LEAN, Trim.PLAIN, true,
            "A walled hide up the trunk, with a slot to watch through."),

    /** A room in a tree, with a deck round it and a real roof over it. */
    TREEHOUSE("treehouse", "Treehouse", 720, 2.6, 3.0, 1, Roof.GABLE, Trim.FITTED, true,
            "A furnished room in the canopy, a deck round it, and a gable roof."),

    /** Two floors in the canopy, joined by their own ladder. */
    CANOPY_HOUSE("canopy_house", "Canopy House", 1500, 3.1, 3.7, 2, Roof.GABLE, Trim.FINE,
            true, "Two floors in the crown, a balcony, and ladders the whole way up.");

    /**
     * What goes over the top, which is most of what a house looks like from
     * across a clearing.
     *
     * <p>Ordered by how much house it takes to carry one, which is the same
     * order as the price, and read by {@link HouseKit#parts} as an instruction
     * rather than as a label.
     */
    public enum Roof {
        /** Nothing. A platform is open to the sky. */
        OPEN,
        /** A single slope, falling to the back. */
        LEAN,
        /** A flat top you can stand on, with a rail round it. */
        DECK,
        /** Two slopes to a ridge, with gable ends. */
        GABLE,
        /** Four slopes to a short ridge. What a big house wears. */
        HIP
    }

    /**
     * How finished the inside is.
     *
     * <p>The one field that most deserves the word "intricacy": each step up
     * adds a class of part rather than making an existing one larger — shutters
     * and a hearth, then glazing and a stair and furniture and a study wall,
     * then a balcony, then wings and a tower. {@link HouseKit} branches on {@link #atLeast} in eight
     * places and nowhere else, so what a tier means is readable in one file.
     */
    public enum Trim {
        /** Bare timber, open holes for windows, a ladder to anything above. */
        ROUGH,
        /** Shutters, a door, a hearth, a bench. */
        PLAIN,
        /**
         * Glazed windows, a staircase, a table, a bed, shelves, a chimney, and
         * the study wall a map board hangs on.
         */
        FITTED,
        /** A balcony over the front door, reached through its own doorway. */
        FINE,
        /** Wings and a tower, and a stone plinth under all of it. */
        GRAND;

        /** Whether this is that tier or better. */
        public boolean atLeast(Trim other) { return ordinal() >= other.ordinal(); }
    }

    /** How tall one storey is, floor to floor, in metres. */
    public static final double STOREY = 2.75;

    private final String key;
    private final String name;
    private final int price;
    private final double halfAlong, halfAcross;
    private final int storeys;
    private final Roof roof;
    private final Trim trim;
    private final boolean tree;
    private final String note;

    HousePlan(String key, String name, int price, double halfAlong, double halfAcross,
              int storeys, Roof roof, Trim trim, boolean tree, String note) {
        this.key = key;
        this.name = name;
        this.price = price;
        this.halfAlong = halfAlong;
        this.halfAcross = halfAcross;
        this.storeys = storeys;
        this.roof = roof;
        this.trim = trim;
        this.tree = tree;
        this.note = note;
    }

    /** Stable identifier — what a save and the wire use. */
    public String key() { return key; }

    /** What a player sees it called. */
    public String displayName() { return name; }

    /** What it costs, in the guide's points. */
    public int price() { return price; }

    /** The price, spelled out for a row on a screen. */
    public String priceLine() { return price + (price == 1 ? " pt" : " pts"); }

    /** Half the footprint front-to-back, in metres. */
    public double halfAlong() { return halfAlong; }

    /** Half the footprint side-to-side, in metres. */
    public double halfAcross() { return halfAcross; }

    /** How many floors there are to stand on. */
    public int storeys() { return storeys; }

    /** What is over the top. */
    public Roof roof() { return roof; }

    /** How finished the inside is. */
    public Trim trim() { return trim; }

    /** Whether it is fixed in a tree rather than standing on the ground. */
    public boolean tree() { return tree; }

    /** One line for the catalogue. */
    public String note() { return note; }

    /**
     * How much house there is, in cubic metres of storey.
     *
     * <p>Footprint times storeys, and the reason it exists is the promise the
     * class note makes: a test can hold the catalogue to "dearer is bigger"
     * with one number rather than by eyeballing ten rows of constants.
     */
    public double volume() {
        return halfAlong * 2 * halfAcross * 2 * storeys * STOREY;
    }

    /** The footprint's diagonal, in metres — what a slope is measured against. */
    public double diagonal() {
        return Math.hypot(halfAlong * 2, halfAcross * 2);
    }

    /**
     * The circle the footprint sits inside, in metres.
     *
     * <p>Circumscribed rather than exact, and the margin is deliberate: this is
     * what {@link Homestead#blocked} keeps two houses apart by, and a
     * conservative circle means the check is one distance and one comparison
     * whatever turn either house is standing at. A pair of mansions that could
     * have been squeezed a metre closer is not a bug anybody will report; two
     * that overlap is a wall you can walk through.
     */
    public double radius() {
        return Math.hypot(halfAlong, halfAcross) + 1.2;
    }

    /**
     * How far in front of a buyer the middle of the house lands, in metres.
     *
     * <p>Its own depth and then some, so that a mansion appears <em>in front
     * of</em> the person who bought it rather than around them. The old build
     * verb used a flat two metres, which was fine for a 2.6 m floor panel and
     * would put a player inside the hall of anything on this list.
     */
    public double standOff() { return halfAlong + 3.0; }

    /** How high the roof reaches above the floor of the ground storey, in metres. */
    public double height() {
        double walls = storeys * STOREY;
        return walls + switch (roof) {
            case OPEN -> 1.1;
            case DECK -> 1.1;
            case LEAN -> 0.9;
            case GABLE -> halfAcross * 0.72;
            case HIP -> halfAcross * 0.66 + (trim.atLeast(Trim.GRAND) ? STOREY : 0);
        };
    }

    /**
     * Whether the house has a map board on a wall inside it.
     *
     * <p>The last thing {@code BuildPiece} could do that nothing else could:
     * the map board was the one built piece that was more than its box, because
     * building one registers a {@link com.larsons.engine.watch.Cartography.Board}
     * that maps can be pinned to. Now it is a <em>fitting</em> — a house with a
     * study has a board on the study wall — which is where a board that eight
     * people stand in front of belonged all along, and which means the maps
     * feature survived the building system being deleted out from under it.
     *
     * <p>It arrives with the furniture, at {@link Trim#FITTED} — the tier where
     * a house stops being shelter and starts being somewhere you keep things.
     *
     * <p>It is not itself behind {@link com.larsons.engine.watch.Debug.Power#MAPS}:
     * every map <em>verb</em> still is, so a player without the code owns a
     * handsome empty board and can do nothing with it, exactly as they own a
     * bed they cannot sleep in.
     */
    public boolean board() { return trim.atLeast(Trim.FITTED); }

    /** Every plan, in the order the catalogue lists them: cheapest first. */
    public static List<HousePlan> all() { return ALL; }

    /** The ones that stand on the ground. */
    public static List<HousePlan> onGround() { return GROUND; }

    /** The ones that go up a tree. */
    public static List<HousePlan> inTrees() { return TREES; }

    /** The plan a saved key means, or {@code null}. */
    public static HousePlan of(String key) {
        for (HousePlan plan : values()) {
            if (plan.key.equals(key)) return plan;
        }
        return null;
    }

    /**
     * Sorted by price, which is the order a catalogue is read in and — because
     * the class note's promise is that the price <em>is</em> the size — the
     * order the houses get bigger in. Ground homes and treehouses are
     * deliberately interleaved rather than kept in two blocks: the row above a
     * treehouse should be what the same money buys on the floor of the wood.
     */
    private static final List<HousePlan> ALL = sortedByPrice();

    private static final List<HousePlan> GROUND = filter(false);

    private static final List<HousePlan> TREES = filter(true);

    private static List<HousePlan> sortedByPrice() {
        List<HousePlan> out = new ArrayList<>(List.of(values()));
        out.sort(java.util.Comparator.comparingInt(HousePlan::price));
        return List.copyOf(out);
    }

    private static List<HousePlan> filter(boolean tree) {
        List<HousePlan> out = new ArrayList<>();
        for (HousePlan plan : ALL) {
            if (plan.tree == tree) out.add(plan);
        }
        return List.copyOf(out);
    }
}
