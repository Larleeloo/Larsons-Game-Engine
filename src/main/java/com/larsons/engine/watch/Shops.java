package com.larsons.engine.watch;

import com.larsons.engine.watch.life.AnimalDef;
import com.larsons.engine.watch.life.AnimalRegistry;
import com.larsons.engine.watch.world.TerrainField;
import com.larsons.engine.watch.world.WatchBiome;
import com.larsons.engine.watch.world.WatchChunk;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Trading posts, standing beside the trails, waiting to be come across.
 *
 * <h2>Found, not built</h2>
 *
 * <p>The same argument the {@link Boats} made, and the same answer. A shop that
 * a player builds is a menu; a shop that a <em>server</em> places cannot exist
 * in a world with no edge, where a party can be a kilometre apart and neither
 * half has heard of the other's ground. So a trading post is generated exactly
 * the way the terrain, the trails and the boats are — as a pure function of
 * position and seed — which makes it genuinely findable (it was there before
 * anybody walked that way), identical for every player, and free on the wire.
 *
 * <p>The consequence worth stating out loud: <b>nothing about a post is state.</b>
 * Where it is, who keeps it, and what is on its shelves are all derived, so no
 * message ever mentions a shop and no save ever holds one. The only things that
 * change hands are the points and the goods, and both of those already live on
 * the guide and in a satchel.
 *
 * <h2>Where one goes</h2>
 *
 * <p>The plane is cut into {@value #CELL}-metre cells; about one in
 * {@value #ODDS_DENOMINATOR} offers a post, and that offer only becomes a post
 * where the ground will take one. A trading post wants what a trading post has
 * always wanted:
 *
 * <ul>
 *   <li><b>a road</b> — {@link com.larsons.engine.watch.world.TrailNetwork} is
 *       already the thing that decides where people walk, and a shop somewhere
 *       nobody walks past is a shop nobody finds. So the siting probe walks a
 *       line across the cell until it stands on a path;</li>
 *   <li><b>flat, dry ground</b> — the building is a rigid box of timber and
 *       cannot be pitched on a cliff or in a lake;</li>
 *   <li><b>and to be beside the path rather than across it</b>, which is the
 *       last step: the post is set {@value #VERGE} m off the centre line, on
 *       whichever side is clearer, and turned to face back at the trail. That is
 *       why you come over a rise and see the sign side-on with the counter
 *       facing the road.</li>
 * </ul>
 *
 * <p>Most candidate cells produce nothing, which is why the odds look generous
 * and the posts do not: expect one every kilometre or so of walking, and to find
 * them by following a trail rather than by wandering.
 *
 * <h2>The clearing</h2>
 *
 * <p>{@link #clearingAt} is asked by {@link com.larsons.engine.watch.world.Flora}
 * and {@link Litter} before they put anything down, and it is the reason a post
 * does not have an oak growing through its roof. Flora already refuses to grow
 * on a trail for exactly this kind of reason; this is the same rule with a
 * bigger footprint, and it lives here because the footprint belongs to the
 * building.
 *
 * <p>That query runs once per candidate plant — hundreds of thousands of times
 * as a wood is meshed — so the generated post for a cell is <b>cached</b>, the
 * way {@code TrailNetwork} caches its sampled edges and for the same reason: the
 * siting probe is a couple of dozen height samples and must not be paid twice.
 */
public final class Shops {

    /** How large a cell is, in metres. */
    public static final double CELL = 430;

    /** One cell in this many offers a post, before the ground has its say. */
    private static final int ODDS_DENOMINATOR = 1;

    /**
     * How far inside its own cell a post has to end up, in metres.
     *
     * <p>The siting probe walks a line up to {@value #PROBES}×{@value #PROBE_STEP}
     * metres long, which can carry it well out of the cell that started it — and
     * two neighbouring cells whose probes both wandered to the same boundary
     * would put two trading posts through each other. A cell keeps its post to
     * its own middle instead, which makes the nearest possible pair twice this
     * apart and costs nothing but a few candidate sites near the edges.
     *
     * <p>This is {@link com.larsons.engine.watch.world.Flora}'s "one candidate
     * per cell, jittered inside it" with a wider margin, for the same reason:
     * the margin is what turns "one per cell" into a minimum separation.
     */
    private static final double CELL_INSET = 26;

    /** How many steps the siting probe takes across a cell, and how long each is. */
    private static final int PROBES = 26;

    private static final double PROBE_STEP = 11;

    /** How strongly a trail has to run through a spot for a post to stand at it. */
    private static final double TRAIL_MIN = 0.55;

    /** How far off the centre line the building is set, in metres. */
    public static final double VERGE = 5.6;

    /** The steepest ground a post will stand on, as a gradient. */
    private static final double MAX_SLOPE = 0.22;

    /** How far above the water line the ground has to be, in metres. */
    private static final double DRY_MARGIN = 0.7;

    /** The building's footprint, in metres — half-extents about its centre. */
    public static final double HALF_WIDTH = 2.7, HALF_DEPTH = 2.0;

    /** How far in front of the centre the counter stands, in metres. */
    public static final double COUNTER_OUT = 2.35;

    /**
     * How high the counter top is above the ground, in metres.
     *
     * <p>Chest-high to a customer standing on the ground and waist-high to a
     * keeper standing on the deck behind it, which is what a market counter is
     * and is the only height at which both people look right at once. The
     * building's deck is {@code ShopModel.DECK} off the ground, so this number
     * and that one are chosen together.
     */
    public static final double COUNTER_TOP = 1.24;

    /** How close a player has to be to the counter to trade, in metres. */
    public static final double COUNTER_RANGE = 4.0;

    /**
     * How far from the centre nothing wild grows, in metres.
     *
     * <p>Wider than the building, because a post is a building <em>and</em> its
     * yard: the crates, the woodpile, the hitching rail and the space to stand
     * in front of the counter. Narrow enough that the wood still closes in
     * around it, which is what makes coming across one feel like coming across
     * one.
     */
    public static final double CLEARING = 8.5;

    /** Past this many cached cells the cache is emptied and refilled. */
    private static final int MAX_CACHED_CELLS = 4096;

    /**
     * Salted for {@link Boats#cellOf}'s reason: an unsalted packing gives cell
     * (0, 0) an id of exactly zero, and zero is what everything in this game
     * means by "no such thing". Players start at the world origin, so that would
     * not have been a corner case.
     */
    private static final long ID_SALT = 0x5405A1E5L;

    /** What a keeper wears on their head. Three, and they read at a distance. */
    public enum Hat {
        /** A wide felt brim. The pedlar's hat. */
        BROAD_BRIM,
        /** A knitted cap, pulled down. */
        WOOL_CAP,
        /** A hood, thrown back off the shoulders. */
        HOOD
    }

    /**
     * The person behind the counter.
     *
     * <p><b>A character rather than a vending machine.</b> Everything here is
     * derived from the post's own id, so a keeper is as permanent as the
     * building — walk back a week later and it is the same person, in the same
     * coat, with the same jackdaw on the counter, saying the same thing when you
     * walk up. That permanence is most of what makes them a person: a randomised
     * shopkeeper is furniture.
     *
     * @param name       what they are called
     * @param trade      their epithet, which is what the sign calls them
     * @param greeting   what they say when you come to the counter
     * @param stampLine  what they say when they stamp a fresh page
     * @param companion  the species sitting on the counter beside them, or
     *                   {@code null} — a keeper's own tame animal, taken from
     *                   the country their post stands in
     * @param build      how tall they are, as a share of {@code WalkerModel.HEIGHT}
     */
    public record Keeper(String name, String trade, String greeting, String stampLine,
                         int coatRgb, int trimRgb, int shirtRgb, int skinRgb, int hairRgb,
                         Hat hat, boolean beard, boolean spectacles, String companion,
                         double build) {

        /** Name and epithet, which is how the sign and the panel head them. */
        public String fullName() { return name + ", " + trade; }

        /** The animal on the counter, or {@code null}. */
        public AnimalDef companionDef() {
            return companion == null ? null : AnimalRegistry.byKey(companion);
        }
    }

    /**
     * One trading post.
     *
     * @param id     the cell it was generated in, which is its identity for ever
     * @param x      where the middle of the building is
     * @param yaw    which way the counter faces, in radians
     * @param markup what this keeper adds to the list price
     * @param stock  what is on the shelves. Not a quantity: see the class note —
     *               a post does not run out, because running out would be state
     */
    public record Shop(long id, String sign, double x, double y, double z, double yaw,
                       Keeper keeper, double markup, List<Trading.Offer> stock,
                       String biome) {

        public Shop {
            stock = List.copyOf(stock);
        }

        /** Where the counter is — the point "in reach" is measured to. */
        public double counterX() { return x + Math.sin(yaw) * COUNTER_OUT; }

        public double counterY() { return y - Math.cos(yaw) * COUNTER_OUT; }

        /** How far a player standing at a point is from the counter, in metres. */
        public double distanceTo(double px, double py) {
            return Math.hypot(counterX() - px, counterY() - py);
        }

        /** Whether somebody standing there could hand something over. */
        public boolean inReach(double px, double py) {
            return distanceTo(px, py) <= COUNTER_RANGE;
        }

        /** The line on this shelf for an item, or {@code null} if it is not sold here. */
        public Trading.Offer offer(String item) {
            for (Trading.Offer offer : stock) {
                if (offer.item().equals(item)) return offer;
            }
            return null;
        }

        /** What the sign over the door says, in full. */
        public String title() { return sign + "  ·  " + keeper.fullName(); }
    }

    private final long seed;

    /**
     * The generated post for a cell, or an empty optional for a cell with none.
     *
     * <p>Concurrent because chunk workers ask it while the frame thread does —
     * {@code Flora.scatter} runs on the pool and {@code WatchGame.pickTarget}
     * runs on the tick thread, and both go through {@link #clearingAt}.
     */
    private final Map<Long, Optional<Shop>> cells = new ConcurrentHashMap<>();

    public Shops(long seed) {
        this.seed = seed;
    }

    private static long idOf(long cx, long cy) {
        return ((cx << 32) ^ (cy & 0xFFFFFFFFL)) ^ ID_SALT;
    }

    /** Every post within {@code radius} of a point. */
    public List<Shop> near(TerrainField field, double x, double y, double radius) {
        List<Shop> out = new ArrayList<>();
        int reach = (int) Math.ceil(radius / CELL) + 1;
        long ccx = (long) Math.floor(x / CELL);
        long ccy = (long) Math.floor(y / CELL);
        double r2 = radius * radius;
        for (long cy = ccy - reach; cy <= ccy + reach; cy++) {
            for (long cx = ccx - reach; cx <= ccx + reach; cx++) {
                Shop shop = in(field, cx, cy);
                if (shop == null) continue;
                double dx = shop.x() - x, dy = shop.y() - y;
                if (dx * dx + dy * dy <= r2) out.add(shop);
            }
        }
        return out;
    }

    /** The post nearest a point within a radius, or {@code null}. */
    public Shop nearest(TerrainField field, double x, double y, double radius) {
        Shop best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Shop shop : near(field, x, y, radius)) {
            double d = Math.hypot(shop.x() - x, shop.y() - y);
            if (d < bestDistance) {
                bestDistance = d;
                best = shop;
            }
        }
        return best;
    }

    /** The post whose counter a player is standing at, or {@code null}. */
    public Shop atCounter(TerrainField field, double x, double y) {
        Shop shop = nearest(field, x, y, CELL);
        return shop != null && shop.inReach(x, y) ? shop : null;
    }

    /**
     * Whether a point is inside a post's yard, and so no place for a tree.
     *
     * <p>Asked by {@link com.larsons.engine.watch.world.Flora} and {@link Litter}
     * once per candidate. Everything expensive about answering it is behind the
     * cache; on a miss it costs one siting probe for the cell, and on a hit it is
     * a map lookup and a distance.
     */
    public boolean clearingAt(TerrainField field, double x, double y) {
        // <b>One cell, not the nine around it.</b> {@link #CELL_INSET} keeps a
        // post at least 26 m inside its own cell and a clearing is 8.5 m across,
        // so no clearing can reach over a boundary and a neighbour can never be
        // the answer. Checking the ring anyway would be nine times the work and
        // — worse — would generate eight cells' worth of siting probes the first
        // time a chunk worker touched a new region.
        Shop shop = in(field, (long) Math.floor(x / CELL), (long) Math.floor(y / CELL));
        if (shop == null) return false;
        double dx = shop.x() - x, dy = shop.y() - y;
        return dx * dx + dy * dy < CLEARING * CLEARING;
    }

    /** How many cells are currently remembered — for tests. */
    public int cachedCells() { return cells.size(); }

    /** The post a cell holds, generating it once and remembering the answer. */
    private Shop in(TerrainField field, long cx, long cy) {
        long id = idOf(cx, cy);
        Optional<Shop> known = cells.get(id);
        if (known != null) return known.orElse(null);
        Shop built = generate(field, cx, cy, id);
        // Cleared wholesale rather than evicted one at a time: the contents are
        // cheap to rebuild, a party cannot keep more than a few thousand cells
        // warm between them, and a concurrent LRU is a thing to get wrong.
        if (cells.size() > MAX_CACHED_CELLS) cells.clear();
        cells.put(id, Optional.ofNullable(built));
        return built;
    }

    /**
     * The post the seed puts in one cell, or {@code null}.
     *
     * <p>Deterministic and self-contained: one hash decides whether the cell
     * offers a post at all, and only then is the terrain touched.
     */
    private Shop generate(TerrainField field, long cx, long cy, long id) {
        // The one cell whose id would be the "no such shop" sentinel. See
        // ID_SALT.
        if (id == 0) return null;
        Random rng = new Random(seed * 0x9E3779B97F4A7C15L ^ id * 0xD6E8FEB86659FD93L);
        if (rng.nextInt(ODDS_DENOMINATOR) != 0) return null;

        double baseX = cx * CELL + rng.nextDouble() * CELL;
        double baseY = cy * CELL + rng.nextDouble() * CELL;
        double angle = rng.nextDouble() * Math.PI * 2;
        double stepX = Math.cos(angle) * PROBE_STEP, stepY = Math.sin(angle) * PROBE_STEP;

        for (int i = 0; i < PROBES; i++) {
            double px = baseX + stepX * i, py = baseY + stepY * i;
            if (field.trailAt(px, py) < TRAIL_MIN) continue;
            double[] site = beside(field, px, py);
            if (site == null) continue;
            if (!inCell(site[0], cx) || !inCell(site[1], cy)) continue;
            return furnish(field, id, site[0], site[1], site[2], site[3], rng);
        }
        return null;
    }

    /** Whether a coordinate is inside its cell's inset middle. See {@link #CELL_INSET}. */
    private static boolean inCell(double v, long cell) {
        double low = cell * CELL + CELL_INSET;
        return v >= low && v < low + CELL - CELL_INSET * 2;
    }

    /**
     * A spot beside the path at {@code (px, py)} that will take a building, as
     * {@code x, y, z, yaw} — or {@code null} if neither side of it will.
     *
     * <p>Eight bearings tried in turn, keeping the one that is furthest off the
     * trail: a post set on the path itself would be a post you walk through, and
     * the whole reason it is here is that people walk past. The yaw is the
     * bearing back toward the path, so the counter faces the road.
     */
    private double[] beside(TerrainField field, double px, double py) {
        double bestTrail = Double.MAX_VALUE;
        double[] best = null;
        for (int a = 0; a < 8; a++) {
            double look = a * Math.PI / 4;
            double sx = px + Math.cos(look) * VERGE, sy = py + Math.sin(look) * VERGE;
            double z = field.heightAt(sx, sy);
            if (field.waterDepth(z) > 0) continue;
            if (z < TerrainField.WATER_LEVEL + DRY_MARGIN) continue;
            if (slopeAt(field, sx, sy) > MAX_SLOPE) continue;
            double trail = field.trailAt(sx, sy);
            if (trail >= bestTrail) continue;
            bestTrail = trail;
            // The game's own convention: forward is (sin yaw, −cos yaw). Facing
            // back at the path from a bearing of `look` is therefore this.
            double yaw = Math.atan2(-Math.cos(look), -Math.sin(look));
            best = new double[]{sx, sy, z, yaw};
        }
        return best;
    }

    /** The slope at a point, read the way the mesher reads it. */
    private static double slopeAt(TerrainField field, double x, double y) {
        double step = WatchChunk.STEP;
        double dx = (field.heightAt(x + step, y) - field.heightAt(x - step, y)) / (2 * step);
        double dy = (field.heightAt(x, y + step) - field.heightAt(x, y - step)) / (2 * step);
        return Math.hypot(dx, dy);
    }

    /** Give a sited post its sign, its keeper and its shelves. */
    private Shop furnish(TerrainField field, long id, double x, double y, double z,
                         double yaw, Random rng) {
        WatchBiome biome = field.biomeAt(x, y);
        double markup = Trading.markupFor(rng);
        Keeper keeper = keeperFor(biome, rng);
        String sign = pick(rng, SIGN_FIRST) + " " + pick(rng, SIGN_SECOND);
        return new Shop(id, sign, x, y, z, yaw, keeper, markup,
                Trading.shelf(biome, markup, rng), biome.key());
    }

    // --- the people ------------------------------------------------------------------

    private static final String[] GIVEN = {
        "Maro", "Ilsa", "Tobin", "Wren", "Cassa", "Orrin", "Bel", "Hadric", "Nesta",
        "Pell", "Sorrel", "Vane", "Ottoline", "Corb", "Maddy", "Ashan", "Rook",
        "Linnet", "Garrow", "Thessa", "Fen", "Odd", "Perrin", "Halla"
    };

    private static final String[] FAMILY = {
        "Ash", "Bracken", "Coldwater", "Dunn", "Elmscroft", "Fallow", "Greyhalt",
        "Hollybrook", "Ilex", "Jarrow", "Kettle", "Larkspur", "Millrace", "Norn",
        "Overhill", "Quill", "Reedy", "Stellik", "Thornwood", "Umber", "Vetch",
        "Waverly", "Yarrow", "Ziller"
    };

    private static final String[] TRADE = {
        "the pedlar", "the fletcher", "the chandler", "the ropemaker", "the tinker",
        "the wainwright", "the cooper", "the herbalist", "the glazier", "the drover",
        "the ferryman's cousin", "who is not from here", "the trail-warden",
        "who keeps the ledger", "the marsh-wife", "the packman"
    };

    private static final String[] GREETING = {
        "Sit down, sit down. What have you been looking at?",
        "You have the walk of somebody with a full page.",
        "Mind the crate. Everything on the shelf is honest.",
        "There is tea if you want it. There is also rope.",
        "I heard you coming a quarter mile back. So did the birds.",
        "Bring me a page worth stamping and we will talk about the lens.",
        "Good light today. Bad light for prices.",
        "I do not ask what you saw. The ledger does.",
        "Everything here came past on somebody's back. Mine, mostly.",
        "You look like you have been sleeping under a platform again."
    };

    private static final String[] STAMP_LINE = {
        "There. Fresh page. Go and fill it.",
        "Signed and dated. The wood has forgotten you already.",
        "A clean page is worth more than a full one. Off you go.",
        "Stamped. Everything out there is new again, if you squint.",
        "Volume closed. I have seen worse handwriting."
    };

    private static final String[] SIGN_FIRST = {
        "The Long", "The Quiet", "The Crooked", "The Last", "The Second", "The Bent",
        "The Old", "The Far", "The Wet", "The Winter"
    };

    private static final String[] SIGN_SECOND = {
        "Rest", "Whistle", "Ledger", "Lantern", "Crossing", "Counter", "Shelf",
        "Mile", "Halt", "Bargain"
    };

    /** Coats, in the dyes somebody a long way from anywhere would actually have. */
    private static final int[] COATS = {
        0x6E4B2E, 0x4A5A3C, 0x7A3B34, 0x3E4A5C, 0x5C4A6B, 0x8A6A3A, 0x40514B, 0x6B5B3F
    };

    private static final int[] TRIMS = {
        0xC9B98A, 0xA8482F, 0x2F5A6B, 0xD9C68A, 0x8FA84C, 0xB0763A
    };

    private static final int[] SHIRTS = {
        0xD8CDB4, 0xBFC7B0, 0xE0D2B8, 0xAEB8C4
    };

    private static final int[] SKINS = {
        0xE8C29A, 0xC98F63, 0x8A5A3B, 0x5E3A24, 0xF0D6BA, 0xA06D45
    };

    private static final int[] HAIRS = {
        0x2A211C, 0x4A3524, 0x6E5334, 0x9A8156, 0xB8B0A4, 0x7A3A22
    };

    /**
     * The keeper of one post.
     *
     * <p>Drawn from the same {@link Random} as the siting, so a keeper is as
     * much a function of the seed as the ground they stand on — and so the whole
     * character costs one hash rather than a table somebody has to maintain.
     */
    private static Keeper keeperFor(WatchBiome biome, Random rng) {
        String name = pick(rng, GIVEN) + " " + pick(rng, FAMILY);
        // Somebody has to be keeping a shop in the middle of a wood, and the
        // reason they give is half of who they are.
        String trade = pick(rng, TRADE);
        Hat hat = Hat.values()[rng.nextInt(Hat.values().length)];
        boolean beard = rng.nextDouble() < 0.45;
        boolean spectacles = rng.nextDouble() < 0.4;
        double build = 0.92 + rng.nextDouble() * 0.16;
        return new Keeper(name, trade, pick(rng, GREETING), pick(rng, STAMP_LINE),
                pick(rng, COATS), pick(rng, TRIMS), pick(rng, SHIRTS), pick(rng, SKINS),
                pick(rng, HAIRS), hat, beard, spectacles, companionFor(biome, rng), build);
    }

    /**
     * The keeper's own tame animal, sitting on the counter.
     *
     * <p>A small one, from the country the post stands in, and tameable —
     * because it is the same relationship the player's own pets are, arrived at
     * the same way, and a keeper with a bear on the counter is a different game.
     * Two keepers in three have one; the third is on their own.
     */
    private static String companionFor(WatchBiome biome, Random rng) {
        if (rng.nextDouble() > 0.66) return null;
        List<AnimalDef> here = AnimalRegistry.inBiome(biome.key());
        List<AnimalDef> small = new ArrayList<>();
        for (AnimalDef def : here) {
            if (def.tameable() && !def.aquatic() && def.bodyLength() < 0.45) small.add(def);
        }
        if (small.isEmpty()) return null;
        return small.get(rng.nextInt(small.size())).key();
    }

    private static String pick(Random rng, String[] options) {
        return options[rng.nextInt(options.length)];
    }

    private static int pick(Random rng, int[] options) {
        return options[rng.nextInt(options.length)];
    }
}
