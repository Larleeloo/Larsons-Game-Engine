package com.larsons.engine.watch.home;

import com.larsons.engine.watch.home.HousePart.Role;
import com.larsons.engine.watch.home.HousePart.Shape;
import com.larsons.engine.watch.world.WatchMaterial;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The carpentry: a {@link HousePlan} turned into the boxes it is made of.
 *
 * <h2>Why a generator and not ten models</h2>
 *
 * <p>Ten hand-built houses would be ten files that share nothing, and the sixth
 * time somebody wanted a window an inch lower they would move it in one of them.
 * What is actually true of these houses is that they are the <em>same house</em>
 * at ten sizes and five levels of finish: a floor for every storey, four walls
 * round each one with openings cut in them, something over the top, a way up,
 * and the furniture the money bought. So that is what this is — one drawing,
 * parameterised by the four fields the plan carries, and the reason
 * {@link HousePlan}'s promise that "intricacy scales with price" is structural
 * rather than a marketing line: {@link HousePlan.Trim} is branched on in this
 * file and nowhere else, and each step up adds a <em>class</em> of part.
 *
 * <h2>Blocks</h2>
 *
 * <p>A house is one or more {@link Block}s — a rectangle of ground, a run of
 * storeys, and which of its four faces are outside walls. Everything below is
 * written against a block rather than against the house, which is what lets the
 * {@linkplain HousePlan.Trim#GRAND grandest} plan grow two wings and a tower out
 * of the same forty lines that put four walls round a lean-to. Blocks never
 * overlap: a wing is beside the hall and takes the hall's wall away where they
 * meet, and the tower starts at the storey the hall's walls stop at.
 *
 * <h2>What is not here</h2>
 *
 * <p>No randomness. A plan expands to the same house every time, which is why
 * the expansion can be {@linkplain #parts(HousePlan) cached once per plan} and
 * why two players looking at the same house see the same house without a byte
 * on the wire. The only per-house number is the {@code drop} — how far the
 * ground is below a treehouse's deck — and that only changes the length of the
 * ladder down to it.
 */
public final class HouseKit {

    /** How far the ground floor stands above the ground it sits on, in metres. */
    public static final double DECK = 0.34;

    /** How thick a floor is, in metres. */
    public static final double FLOOR_THICK = 0.16;

    /** How thick a wall is, in metres. */
    public static final double WALL_THICK = 0.12;

    /** A doorway: wide enough for two, tall enough for anybody. */
    public static final double DOOR_WIDTH = 1.16, DOOR_HEIGHT = 2.06;

    /** Where a window's sill and head sit above the floor, in metres. */
    public static final double SILL = 0.95, HEAD = 1.88;

    /** A watching slot, for the plans too rough to glaze. */
    private static final double SLIT_SILL = 1.06, SLIT_HEAD = 1.54;

    /** How high a railing is, and how thick. */
    public static final double RAIL_HEIGHT = 1.02;

    private static final double RAIL_THICK = 0.055;

    /** How wide a ladder is, and how far it stands off what it is fixed to. */
    public static final double LADDER_WIDTH = 0.62, LADDER_THICK = 0.09;

    /** How wide a staircase is, in metres. */
    private static final double STAIR_WIDTH = 0.98;

    /** How deep one tread is, and how tall one riser. */
    private static final double TREAD = 0.27, RISER = 0.20;

    /** Half a corner post. */
    private static final double POST = 0.11;

    /** How far the eaves oversail the walls, in metres. */
    private static final double EAVE = 0.36;

    /** How wide the hole in a floor is, where the way up comes through. */
    private static final double HATCH = 1.25;

    /**
     * How far a map board's face stands off the middle of its timber, and how
     * wide and tall the timber is.
     *
     * <p>Public because the maps are painted onto it by a different class —
     * {@code WatchScene.boardFaces} lays a mosaic of the country on each face —
     * and a board that was 0.06 thick here and 0.05 there is a map floating a
     * centimetre in front of the wood.
     */
    public static final double BOARD_STAND = 0.06, BOARD_HALF = 0.95;

    /** How high the middle of a map board hangs above the floor, in metres. */
    public static final double BOARD_HEIGHT = 1.35;

    /** The four faces of a block, as the wall builder numbers them. */
    private static final int FRONT = 0, BACK = 1, RIGHT = 2, LEFT = 3;

    /** How much headroom there is under a ceiling, in metres. */
    public static double ceiling() { return HousePlan.STOREY - FLOOR_THICK; }

    private HouseKit() {}

    /**
     * A rectangle of house.
     *
     * @param a0 the back edge, and {@code a1} the front, in the house's frame
     * @param c0 the left edge, and {@code c1} the right
     * @param from the storey its lowest floor is
     * @param sides which of {@link #FRONT}, {@link #BACK}, {@link #RIGHT} and
     *              {@link #LEFT} carry an outside wall; a face left out is where
     *              this block opens into another
     * @param door whether the front of its lowest storey has the front door in it
     */
    private record Block(double a0, double a1, double c0, double c1,
                         int from, int storeys, HousePlan.Roof roof,
                         boolean[] sides, boolean door) {

        double midAlong() { return (a0 + a1) / 2; }

        double midAcross() { return (c0 + c1) / 2; }

        double halfAlong() { return (a1 - a0) / 2; }

        double halfAcross() { return (c1 - c0) / 2; }

        /** The floor level of one of its storeys, measured from the house's base. */
        double floorOf(int storey) { return DECK + storey * HousePlan.STOREY; }

        /** Where its walls stop and its roof starts. */
        double eaves() { return floorOf(from + storeys) - FLOOR_THICK; }
    }

    /** An opening in a wall: a door, a window, a slot. */
    private record Gap(double centre, double half, double sill, double head,
                       boolean glazed, boolean shuttered) {}

    private static final Map<HousePlan, List<HousePart>> CACHE =
            Collections.synchronizedMap(new EnumMap<>(HousePlan.class));

    /**
     * Everything one plan is made of, in the house's own frame.
     *
     * <p>Cached, because it is the same list every time and because the walk
     * asks for it once per frame per house in sight and once per collision
     * query — see {@link Homestead}, which holds the world-frame answer on top
     * of this one.
     */
    public static List<HousePart> parts(HousePlan plan) {
        return CACHE.computeIfAbsent(plan, HouseKit::expand);
    }

    /**
     * Everything one <em>placed</em> house is made of.
     *
     * <p>The plan's parts, plus whatever gets the house down to the ground it
     * was put on. That is the only thing about a house that is not a function
     * of its plan, and it is two different things depending on where it stands:
     * a treehouse's ladder is as long as the tree is tall, and a house on a
     * hillside stands on piers as long as the hill falls away.
     *
     * @param drop how far the ground is below the house's base, in metres
     */
    public static List<HousePart> parts(HousePlan plan, double drop) {
        List<HousePart> fixed = parts(plan);
        if (drop <= (plan.tree() ? 0.4 : 0.25)) return fixed;
        List<HousePart> out = new ArrayList<>(fixed);
        if (plan.tree()) trunkLadder(out, plan, drop);
        else piers(out, plan, drop);
        return List.copyOf(out);
    }

    /**
     * What a house on a slope stands on, and how you get up to its door.
     *
     * <p>The floor is laid level with the <em>highest</em> ground under the
     * footprint — a floor below the hillside it stands on is a floor a walker's
     * boots come up through — so on any slope worth the name the downhill side
     * is in the air. This is what fills that: a post at each corner reaching the
     * ground, and the front steps carried on down beside them.
     *
     * <p>The steps run all the way to the deepest corner even though the ground
     * in front of the door is usually higher, and that costs nothing: a walker
     * stands on the highest thing under their boots, so the treads below the
     * hillside are simply never the answer. See {@link Homestead#standOn}.
     */
    private static void piers(List<HousePart> out, HousePlan plan, double drop) {
        double hA = plan.halfAlong(), hC = plan.halfAcross();
        double top = DECK - FLOOR_THICK;
        for (int a = -1; a <= 1; a += 2) {
            for (int c = -1; c <= 1; c += 2) {
                out.add(new HousePart(Role.POST, Shape.BOX, a * (hA - 0.26),
                        c * (hC - 0.26), (top - drop) / 2, 0.16, 0.16,
                        (top + drop) / 2, WatchMaterial.BARK, 0.96));
            }
        }
        // A brace across each end, which is what stops four posts being four
        // stilts, and a sill beam round the perimeter for them to carry.
        for (int c = -1; c <= 1; c += 2) {
            out.add(new HousePart(Role.TRIM, Shape.BOX, 0, c * (hC - 0.26),
                    top - drop * 0.55, hA - 0.26, 0.06, 0.06,
                    WatchMaterial.BARK, 0.9));
        }
        int treads = (int) Math.ceil(drop / 0.19);
        for (int i = 1; i <= treads; i++) {
            double z = -drop * i / treads;
            out.add(new HousePart(Role.STAIR, Shape.BOX,
                    hA + 0.24 * 2 + 0.24 * i, 0, z - 0.055,
                    0.12, DOOR_WIDTH / 2 + 0.28, 0.055, WatchMaterial.PLANK, 0.9));
        }
    }

    // --- the drawing ------------------------------------------------------------------

    private static List<HousePart> expand(HousePlan plan) {
        List<HousePart> out = new ArrayList<>();
        List<Block> blocks = blocks(plan);

        if (plan.tree()) bearers(out, plan);
        else footings(out, plan);

        for (Block block : blocks) shell(out, plan, block);

        // The way up, the way in, and what the money bought. All of them are
        // about the main block — the first one, which is the one with the front
        // door in it — because a wing you cannot get into from the hall is not a
        // wing, and a second staircase is a thing to trip over rather than a
        // feature.
        // A way up in every block that has anywhere to go, so a wing has its own
        // stair and the mansion's tower is somewhere you can actually stand.
        for (Block block : blocks) ways(out, plan, block);

        // The front steps, the balcony and the furniture belong to the block
        // with the front door in it — the first one, always.
        Block main = blocks.get(0);
        steps(out, plan, main);
        fittings(out, plan, main);
        return List.copyOf(out);
    }

    /**
     * How a plan is cut into blocks.
     *
     * <p>One, until the money runs to more than one. A {@link HousePlan.Trim#GRAND}
     * house is a hall down the middle with a lower wing either side of it and a
     * tower standing on the hall's roof, and the three of them together are the
     * whole of what "mansion" means here: not a bigger box, but a building with
     * a shape you can walk round.
     */
    private static List<Block> blocks(HousePlan plan) {
        double hA = plan.halfAlong(), hC = plan.halfAcross();
        int storeys = plan.storeys();
        if (!plan.trim().atLeast(HousePlan.Trim.GRAND)) {
            return List.of(new Block(-hA, hA, -hC, hC, 0, storeys, plan.roof(),
                    new boolean[]{true, true, true, true}, true));
        }
        double hallC = hC * 0.44;
        double wingA = hA * 0.68;
        List<Block> out = new ArrayList<>();
        // The hall. Its long sides are open where the wings meet it.
        out.add(new Block(-hA, hA, -hallC, hallC, 0, storeys, plan.roof(),
                new boolean[]{true, true, false, false}, true));
        // The wings, a storey lower, opening into the hall.
        out.add(new Block(-wingA, wingA, hallC, hC, 0, storeys - 1, HousePlan.Roof.GABLE,
                new boolean[]{true, true, true, false}, false));
        out.add(new Block(-wingA, wingA, -hC, -hallC, 0, storeys - 1, HousePlan.Roof.GABLE,
                new boolean[]{true, true, false, true}, false));
        // The tower, standing where the hall's walls stop. It is the one part of
        // any house on the list that is above the roofline, which is what makes
        // a mansion read as a mansion from the far side of a valley.
        out.add(new Block(-hA * 0.55, -hA * 0.02, -hallC * 0.62, hallC * 0.62,
                storeys, 1, HousePlan.Roof.HIP,
                new boolean[]{true, true, true, true}, false));
        return List.copyOf(out);
    }

    /** Dry-stone piers under a house that stands on the ground. */
    private static void footings(List<HousePart> out, HousePlan plan) {
        double hA = plan.halfAlong(), hC = plan.halfAcross();
        boolean plinth = plan.trim().atLeast(HousePlan.Trim.GRAND);
        if (plinth) {
            // The grandest houses get a continuous plinth rather than four
            // stones, because a mansion on stilts is a shed with ambitions.
            out.add(new HousePart(Role.TRIM, Shape.BOX, 0, 0, DECK * 0.45,
                    hA + 0.24, hC + 0.24, DECK * 0.45, WatchMaterial.STONE_BLOCK, 1.0));
            return;
        }
        for (int a = -1; a <= 1; a += 2) {
            for (int c = -1; c <= 1; c += 2) {
                out.add(new HousePart(Role.TRIM, Shape.BOX, a * (hA - 0.30),
                        c * (hC - 0.30), DECK * 0.42, 0.30, 0.30, DECK * 0.42,
                        WatchMaterial.STONE_BLOCK, 1.0));
            }
        }
    }

    /**
     * What a treehouse rests on.
     *
     * <p>Two bearers crossing under the deck and a collar round the trunk. It
     * is structural in the honest sense — it is what stops the house looking
     * like it is hovering next to a tree, which is what every treehouse in a
     * game that skips this step looks like.
     */
    private static void bearers(List<HousePart> out, HousePlan plan) {
        double hA = plan.halfAlong(), hC = plan.halfAcross();
        double z = DECK - FLOOR_THICK - 0.09;
        for (int c = -1; c <= 1; c += 2) {
            out.add(new HousePart(Role.TRIM, Shape.BOX, 0, c * hC * 0.62, z,
                    hA + 0.2, 0.09, 0.09, WatchMaterial.BARK, 1.0));
        }
        for (int a = -1; a <= 1; a += 2) {
            out.add(new HousePart(Role.TRIM, Shape.BOX, a * hA * 0.62, 0, z,
                    0.09, hC + 0.2, 0.09, WatchMaterial.BARK, 1.0));
        }
        // Braces up from under the deck to the corners, which is what actually
        // carries a cantilevered floor and is the shape everybody recognises.
        for (int a = -1; a <= 1; a += 2) {
            for (int c = -1; c <= 1; c += 2) {
                out.add(new HousePart(Role.TRIM, Shape.BOX, a * hA * 0.5, c * hC * 0.5,
                        z - 0.42, 0.07, 0.07, 0.42, WatchMaterial.BARK, 0.94));
            }
        }
    }

    // --- one block --------------------------------------------------------------------

    /** Floors, posts, walls and a roof, for one rectangle of house. */
    private static void shell(List<HousePart> out, HousePlan plan, Block block) {
        boolean open = block.roof() == HousePlan.Roof.OPEN;
        for (int s = 0; s < block.storeys(); s++) {
            int storey = block.from() + s;
            double floor = block.floorOf(storey);
            floor(out, plan, block, storey, floor);
            posts(out, block, floor, floor + HousePlan.STOREY);
            if (open) {
                // A platform is railed all the way round except where the
                // ladder arrives, which is the back. A rail across the head of
                // your own ladder is the sort of thing that only shows up once
                // somebody has paid for the thing and climbed it.
                boolean landing = plan.tree();
                rails(out, block.midAlong(), block.midAcross(), block.halfAlong(),
                        block.halfAcross(), floor, true, !landing, true, true);
                if (landing) backRailWithGap(out, block, floor);
                continue;
            }
            for (int side = FRONT; side <= LEFT; side++) {
                if (!block.sides()[side]) continue;
                wall(out, plan, block, side, storey, floor);
            }
        }
        roof(out, plan, block);
    }

    /**
     * One floor, with a hole in it where the way up comes through.
     *
     * <p>The hatch is not decoration. A staircase that arrived at a continuous
     * slab of boards would be a staircase you climb through the ceiling, and
     * the whole claim this feature makes is that the houses are functional:
     * what you can see you can walk on, and what you can walk up arrives
     * somewhere.
     */
    private static void floor(List<HousePart> out, HousePlan plan, Block block,
                              int storey, double top) {
        deck(out, block, top, storey > 0 ? wayUp(block) : null);
    }

    /**
     * A rectangle of floor over one block, with an optional hole in it.
     *
     * <p>Written as four bands round the hole rather than as four hand-placed
     * boxes, so that moving the stair moves the hole with it.
     */
    private static void deck(List<HousePart> out, Block block, double top, double[] hole) {
        double up = top - FLOOR_THICK / 2;
        if (hole == null) {
            slab(out, block.midAlong(), block.midAcross(), up,
                    block.halfAlong(), block.halfAcross());
            return;
        }
        double h0 = hole[0] - HATCH / 2, h1 = hole[0] + HATCH / 2;
        double k0 = hole[1] - HATCH / 2, k1 = hole[1] + HATCH / 2;
        band(out, block.a0(), h0, block.c0(), block.c1(), up);
        band(out, h1, block.a1(), block.c0(), block.c1(), up);
        band(out, h0, h1, block.c0(), k0, up);
        band(out, h0, h1, k1, block.c1(), up);
    }

    /** A rectangle of floor, if there is any of it left to lay. */
    private static void band(List<HousePart> out, double a0, double a1,
                             double c0, double c1, double up) {
        if (a1 - a0 < 0.05 || c1 - c0 < 0.05) return;
        slab(out, (a0 + a1) / 2, (c0 + c1) / 2, up, (a1 - a0) / 2, (c1 - c0) / 2);
    }

    private static void slab(List<HousePart> out, double along, double across, double up,
                             double halfAlong, double halfAcross) {
        out.add(new HousePart(Role.FLOOR, Shape.BOARDS, along, across, up,
                halfAlong, halfAcross, FLOOR_THICK / 2, WatchMaterial.PLANK, 1.0));
    }

    /** Corner posts, from one floor to the next. */
    private static void posts(List<HousePart> out, Block block, double from, double to) {
        double up = (from + to) / 2, half = (to - from) / 2;
        for (int a = -1; a <= 1; a += 2) {
            for (int c = -1; c <= 1; c += 2) {
                out.add(new HousePart(Role.POST, Shape.BOX,
                        block.midAlong() + a * (block.halfAlong() - POST),
                        block.midAcross() + c * (block.halfAcross() - POST),
                        up, POST, POST, half, WatchMaterial.BARK, 1.0));
            }
        }
    }

    // --- walls ------------------------------------------------------------------------

    /** One face of one storey, with whatever is cut out of it. */
    private static void wall(List<HousePart> out, HousePlan plan, Block block, int side,
                             int storey, double floor) {
        if (side == FRONT && storey == block.from() && openFront(plan)) return;
        double top = floor + ceiling();
        boolean along = side == FRONT || side == BACK;
        double lo = along ? block.c0() : block.a0();
        double hi = along ? block.c1() : block.a1();
        double fixed = switch (side) {
            case FRONT -> block.a1() - WALL_THICK / 2;
            case BACK -> block.a0() + WALL_THICK / 2;
            case RIGHT -> block.c1() - WALL_THICK / 2;
            default -> block.c0() + WALL_THICK / 2;
        };

        List<Gap> gaps = openings(plan, block, side, storey, lo, hi, floor);
        gaps.sort(java.util.Comparator.comparingDouble(Gap::centre));
        double cursor = lo;
        for (Gap gap : gaps) {
            double g0 = Math.max(lo, gap.centre() - gap.half());
            double g1 = Math.min(hi, gap.centre() + gap.half());
            if (g1 - g0 < 0.05) continue;
            pier(out, along, fixed, cursor, g0, floor, top);
            pier(out, along, fixed, g0, g1, floor, floor + gap.sill());
            pier(out, along, fixed, g0, g1, floor + gap.head(), top);
            if (gap.glazed()) {
                panel(out, Role.GLASS, Shape.BOX, along, fixed, g0, g1,
                        floor + gap.sill(), floor + gap.head(),
                        WatchMaterial.GLASSPANE, 1.0, WALL_THICK * 0.22);
            }
            if (gap.shuttered()) shutters(out, along, fixed, g0, g1, floor + gap.sill(),
                    floor + gap.head());
            cursor = g1;
        }
        pier(out, along, fixed, cursor, hi, floor, top);

        // A plate beam under the head of the wall — the line that makes a wall
        // of boards read as a frame. Proud of the boarding on both faces and
        // stopping level with its top, so the floor above hides its top face
        // rather than fighting it.
        panel(out, Role.WALL, Shape.BOX, along, fixed, lo, hi, top - 0.14, top,
                WatchMaterial.BARK, 1.0, WALL_THICK * 0.66);
    }

    /** A length of solid wall between two openings, if there is any. */
    private static void pier(List<HousePart> out, boolean along, double fixed,
                             double lo, double hi, double zLo, double zHi) {
        if (hi - lo < 0.05 || zHi - zLo < 0.05) return;
        panel(out, Role.WALL, Shape.BOARDS, along, fixed, lo, hi, zLo, zHi,
                WatchMaterial.PLANK, 1.0, WALL_THICK / 2);
    }

    /** One rectangle standing in a wall plane, whatever it is made of. */
    private static void panel(List<HousePart> out, Role role, Shape shape, boolean along,
                              double fixed, double lo, double hi, double zLo, double zHi,
                              WatchMaterial material, double shade, double halfThick) {
        if (hi - lo < 0.02 || zHi - zLo < 0.02) return;
        double centre = (lo + hi) / 2, half = (hi - lo) / 2;
        double up = (zLo + zHi) / 2, halfUp = (zHi - zLo) / 2;
        out.add(along
                ? new HousePart(role, shape, fixed, centre, up, halfThick, half, halfUp,
                        material, shade)
                : new HousePart(role, shape, centre, fixed, up, half, halfThick, halfUp,
                        material, shade));
    }

    /** A pair of shutters, thrown back against the wall either side of a hole. */
    private static void shutters(List<HousePart> out, boolean along, double fixed,
                                 double g0, double g1, double zLo, double zHi) {
        double width = (g1 - g0) * 0.42;
        for (int s = -1; s <= 1; s += 2) {
            double centre = s < 0 ? g0 - width / 2 : g1 + width / 2;
            panel(out, Role.TRIM, Shape.BOARDS, along, fixed + sign(fixed) * WALL_THICK,
                    centre - width / 2, centre + width / 2, zLo, zHi,
                    WatchMaterial.PLANK, 0.86, 0.025);
        }
    }

    private static double sign(double v) { return v >= 0 ? 1 : -1; }

    /**
     * What is cut out of one wall.
     *
     * <p>The front door, and then windows spread over whatever is left. How many
     * and how big is the price talking: a {@link HousePlan.Trim#ROUGH} house gets
     * one slot to watch through, a {@link HousePlan.Trim#PLAIN} one gets holes
     * with shutters, and everything above that gets glass.
     */
    private static List<Gap> openings(HousePlan plan, Block block, int side, int storey,
                                      double lo, double hi, double floor) {
        List<Gap> gaps = new ArrayList<>();
        HousePlan.Trim trim = plan.trim();
        double run = hi - lo, middle = (lo + hi) / 2;

        boolean groundFloor = storey == 0;
        if (side == FRONT && groundFloor && block.door()) {
            gaps.add(new Gap(middle, DOOR_WIDTH / 2, 0, DOOR_HEIGHT, false, false));
        }
        // A treehouse's back wall is where its ladder arrives, so it needs a way
        // in as much as the front does.
        if (side == BACK && groundFloor && plan.tree()) {
            gaps.add(new Gap(middle, DOOR_WIDTH / 2, 0, DOOR_HEIGHT, false, false));
        }
        // The balcony door, over the front door, on the houses that have one.
        if (side == FRONT && storey == 1 && balcony(plan)) {
            gaps.add(new Gap(middle, DOOR_WIDTH / 2, 0, DOOR_HEIGHT, false, false));
        }

        double sill = trim == HousePlan.Trim.ROUGH ? SLIT_SILL : SILL;
        double head = trim == HousePlan.Trim.ROUGH ? SLIT_HEAD : HEAD;
        double width = switch (trim) {
            case ROUGH -> 0.30;
            case PLAIN -> 0.72;
            case FITTED -> 0.90;
            case FINE -> 1.02;
            case GRAND -> 1.14;
        };
        boolean glazed = trim.atLeast(HousePlan.Trim.FITTED);
        boolean shuttered = trim == HousePlan.Trim.PLAIN;

        // One window per two and a half metres of wall, which is the spacing a
        // real elevation uses and which keeps a mansion from having six windows
        // where a cabin has one of the same size.
        int count = Math.max(0, (int) Math.floor(run / 2.5));
        if (count == 0 && run > 1.4 && trim.atLeast(HousePlan.Trim.PLAIN)) count = 1;
        boolean doorHere = !gaps.isEmpty();
        if (doorHere) count = Math.max(count - 1, run > 3.4 ? 2 : 0);
        for (int i = 0; i < count; i++) {
            double t = (i + 1.0) / (count + 1.0);
            double centre = lo + run * t;
            if (doorHere && Math.abs(centre - middle) < DOOR_WIDTH / 2 + width / 2 + 0.2) {
                // Shove it clear of the door rather than dropping it: a blank
                // wall either side of a door reads as an unfinished house.
                centre = middle + Math.signum(centre - middle + 1e-9)
                        * (DOOR_WIDTH / 2 + width / 2 + 0.34);
            }
            if (centre - width / 2 < lo + 0.34 || centre + width / 2 > hi - 0.34) continue;
            gaps.add(new Gap(centre, width / 2, sill, head, glazed, shuttered));
        }
        return gaps;
    }

    /** The one plan whose front is left out. A lean-to with a front wall is a shed. */
    private static boolean openFront(HousePlan plan) { return plan == HousePlan.LEAN_TO; }

    /** Whether a plan has a balcony over its door. */
    private static boolean balcony(HousePlan plan) {
        return plan.trim().atLeast(HousePlan.Trim.FINE) && plan.storeys() >= 2;
    }

    // --- roofs ------------------------------------------------------------------------

    /** Whatever goes over the top of one block. */
    private static void roof(List<HousePart> out, HousePlan plan, Block block) {
        double eaves = block.eaves();
        double hA = block.halfAlong() + EAVE, hC = block.halfAcross() + EAVE;
        double mA = block.midAlong(), mC = block.midAcross();
        WatchMaterial cover = plan.trim().atLeast(HousePlan.Trim.FITTED)
                ? WatchMaterial.DARK_BARK : WatchMaterial.THATCH;

        switch (block.roof()) {
            case OPEN -> { }

            case DECK -> {
                // A flat lid you can stand on, with a rail round it: the whole
                // point of the fort, and the only roof on the list that is a
                // floor as far as the collision is concerned.
                deck(out, block, eaves + FLOOR_THICK, wayUp(block));
                rails(out, mA, mC, block.halfAlong(), block.halfAcross(),
                        eaves + FLOOR_THICK, true, true, true, true);
            }

            case LEAN -> {
                double rise = Math.min(1.4, block.halfAlong() * 0.55);
                pitch(out, Shape.PITCH_BACK, mA, mC, hA, hC, eaves, eaves + rise, cover);
                gableEnd(out, block, eaves, eaves + rise, true);
            }

            case GABLE -> {
                // The ridge runs across the house, so the slopes fall to the
                // front and the back and the door is under the eaves. That is
                // the cottage everybody has a picture of. The rise is taken off
                // the shorter half-extent, which is the one the slope actually
                // runs over: a pitch measured against the other one comes out
                // vertical on a house that is much wider than it is deep.
                double rise = shorter(block) * 0.80;
                pitch(out, Shape.PITCH_FRONT, mA + hA / 2, mC, hA / 2, hC,
                        eaves, eaves + rise, cover);
                pitch(out, Shape.PITCH_BACK, mA - hA / 2, mC, hA / 2, hC,
                        eaves, eaves + rise, cover);
                ridge(out, mA, mC, hC, eaves + rise);
                gableEnd(out, block, eaves, eaves + rise, false);
            }

            case HIP -> {
                double rise = shorter(block) * 0.70;
                double ridgeHalf = block.halfAcross() * 0.34;
                pitch(out, Shape.PITCH_FRONT, mA + hA / 2, mC, hA / 2, hC,
                        eaves, eaves + rise, cover);
                pitch(out, Shape.PITCH_BACK, mA - hA / 2, mC, hA / 2, hC,
                        eaves, eaves + rise, cover);
                pitch(out, Shape.PITCH_RIGHT, mA, mC + hC / 2, hA, hC / 2,
                        eaves, eaves + rise, cover);
                pitch(out, Shape.PITCH_LEFT, mA, mC - hC / 2, hA, hC / 2,
                        eaves, eaves + rise, cover);
                ridge(out, mA, mC, ridgeHalf, eaves + rise);
            }
        }
    }

    /** The half-extent a roof's slope actually runs over. */
    private static double shorter(Block block) {
        return Math.min(block.halfAlong(), block.halfAcross());
    }

    private static void pitch(List<HousePart> out, Shape shape, double along, double across,
                              double halfAlong, double halfAcross, double eaves,
                              double ridge, WatchMaterial cover) {
        out.add(new HousePart(Role.ROOF, shape, along, across, (eaves + ridge) / 2,
                halfAlong, halfAcross, (ridge - eaves) / 2, cover, 1.0));
    }

    /** The beam along the top, so a roof has a line rather than a crease. */
    private static void ridge(List<HousePart> out, double along, double across,
                              double half, double z) {
        out.add(new HousePart(Role.ROOF, Shape.BOX, along, across, z + 0.06,
                0.09, half, 0.09, WatchMaterial.BARK, 1.0));
    }

    /**
     * The triangle of wall under a gable, stepped.
     *
     * <p>Boxes rather than a triangle because {@link HousePart} is boxes, and
     * six steps at this size is a straight edge from anywhere a player stands.
     */
    private static void gableEnd(List<HousePart> out, Block block, double eaves,
                                 double ridge, boolean lean) {
        int steps = 6;
        double rise = ridge - eaves;
        for (int side = 0; side < 2; side++) {
            boolean right = side == 1;
            double c = right ? block.c1() - WALL_THICK / 2 : block.c0() + WALL_THICK / 2;
            if (lean) {
                // A lean-to's end is a right-angled triangle rather than an
                // isosceles one: the roof is high at the front and low at the
                // back, so each band of wall runs from where the slope has
                // reached that height forward to the front wall.
                double span = block.a1() - block.a0();
                for (int i = 0; i < steps; i++) {
                    double t0 = i / (double) steps, t1 = (i + 1.0) / steps;
                    panel(out, Role.WALL, Shape.BOARDS, false, c,
                            block.a0() + span * t1, block.a1(),
                            eaves + rise * t0, eaves + rise * t1,
                            WatchMaterial.PLANK, 0.95, WALL_THICK / 2);
                }
                continue;
            }
            for (int i = 0; i < steps; i++) {
                double t0 = i / (double) steps, t1 = (i + 1.0) / steps;
                double half = block.halfAlong() * (1 - t0);
                double halfInner = block.halfAlong() * (1 - t1);
                if (half - halfInner < 0.01) continue;
                panel(out, Role.WALL, Shape.BOARDS, false, c,
                        block.midAlong() - half, block.midAlong() + half,
                        eaves + rise * t0, eaves + rise * t1,
                        WatchMaterial.PLANK, 0.95, WALL_THICK / 2);
            }
        }
    }

    // --- ways up ----------------------------------------------------------------------

    /**
     * Where a block's way up stands, as {@code along, across}.
     *
     * <p>One method, asked by three: the stair that climbs, the hole in the
     * floor it arrives through, and the ladder that stands in for it in a rough
     * house. A house with the hatch somewhere other than the top of the stair
     * is the single most obvious way this could go wrong, and it cannot,
     * because there is one number.
     */
    private static double[] wayUp(Block block) {
        double along = block.a0() + Math.min(1.7, block.halfAlong() * 0.9) + 0.2;
        double across = block.c0() + Math.min(HATCH / 2 + 0.24, block.halfAcross() * 0.9);
        return new double[]{along, across};
    }

    /**
     * How you get from one floor to the next.
     *
     * <p>A ladder while the house is rough and a proper staircase once it is
     * {@linkplain HousePlan.Trim#FITTED fitted} — which is the single clearest
     * thing the price ladder buys, because a staircase is a thing you walk up
     * without thinking and a ladder is a thing you stop and climb.
     */
    private static void ways(List<HousePart> out, HousePlan plan, Block block) {
        double[] at = wayUp(block);
        boolean stair = plan.trim().atLeast(HousePlan.Trim.FITTED);
        // A block that starts above the ground — the mansion's tower — is
        // reached from the storey below it, which belongs to another block. The
        // run stands at this block's own way up, which is inside both.
        int first = block.from() > 0 ? block.from() - 1 : block.from();
        for (int storey = first; storey + 1 < block.from() + block.storeys(); storey++) {
            double from = block.floorOf(storey), to = block.floorOf(storey + 1);
            if (stair) staircase(out, at[0], at[1], from, to);
            else ladder(out, at[0], at[1], from, to, true);
        }
        // The fort's roof deck: the way up carries on past the top floor, and
        // the deck has a hole in it to arrive through.
        if (block.roof() == HousePlan.Roof.DECK) {
            ladder(out, at[0], at[1], block.floorOf(block.from() + block.storeys() - 1),
                    block.eaves() + FLOOR_THICK, true);
        }
    }

    /**
     * A straight flight of treads.
     *
     * <p>Every tread is its own {@link Role#STAIR} box, so walking up one is
     * the ordinary business of standing on the highest thing under your boots
     * rather than a special case in the walk — see {@link Homestead#standOn}.
     * The rise is under a fifth of a metre, which is inside the step the walk
     * will take without a jump.
     */
    private static void staircase(List<HousePart> out, double along, double across,
                                  double from, double to) {
        int treads = Math.max(2, (int) Math.ceil((to - from) / RISER));
        double rise = (to - from) / treads;
        for (int i = 0; i < treads; i++) {
            double z = from + rise * (i + 1);
            out.add(new HousePart(Role.STAIR, Shape.BOX, along + TREAD * i, across,
                    z - 0.035, TREAD / 2, STAIR_WIDTH / 2, 0.035,
                    WatchMaterial.PLANK, i % 2 == 0 ? 1.0 : 0.93));
        }
        // The stringer under the treads, so a flight has a side to it.
        for (int s = -1; s <= 1; s += 2) {
            out.add(new HousePart(Role.TRIM, Shape.BOX,
                    along + TREAD * (treads - 1) / 2.0, across + s * STAIR_WIDTH / 2,
                    (from + to) / 2 - 0.12, TREAD * treads / 2.0, 0.05,
                    (to - from) / 2, WatchMaterial.PLANK, 0.88));
        }
    }

    /** One ladder, from one height to another. */
    private static void ladder(List<HousePart> out, double along, double across,
                               double from, double to, boolean inside) {
        if (to - from < 0.4) return;
        out.add(new HousePart(Role.LADDER, Shape.LADDER, along, across,
                (from + to) / 2, LADDER_THICK, LADDER_WIDTH / 2, (to - from) / 2,
                WatchMaterial.BARK, inside ? 1.0 : 0.95));
    }

    /**
     * The climb from the ground to a treehouse, and the landing it arrives at.
     *
     * <p>This is the promise in the brief — a treehouse comes with the ladder up
     * to the tree it is in — and the landing is why it is not just a ladder: a
     * ladder that ends in mid-air beside a wall is a ladder you fall off. The
     * back wall of a treehouse has a doorway in it for exactly this
     * ({@link #openings}), and the two are placed from the same numbers.
     */
    private static void trunkLadder(List<HousePart> out, HousePlan plan, double drop) {
        double back = -plan.halfAlong();
        // The landing: a metre of deck out of the back door, railed on three
        // sides so that only the ladder is a way off it.
        double landing = 0.44;
        slab(out, back - landing, 0, DECK - FLOOR_THICK / 2, landing, 0.78);
        rails(out, back - landing, 0, landing, 0.78, DECK, false, false, true, true);
        out.add(new HousePart(Role.TRIM, Shape.BOX, back - landing, 0,
                DECK - FLOOR_THICK - 0.4, 0.07, 0.07, 0.4, WatchMaterial.BARK, 0.94));
        ladder(out, back - landing * 2 + LADDER_THICK, 0, -drop, DECK - 0.02, false);
    }

    /** A railing round some or all of a rectangle. */
    private static void rails(List<HousePart> out, double along, double across,
                              double halfAlong, double halfAcross, double floor,
                              boolean front, boolean back, boolean right, boolean left) {
        double up = floor + RAIL_HEIGHT / 2;
        double half = RAIL_HEIGHT / 2;
        if (front) out.add(rail(along + halfAlong - RAIL_THICK, across, up,
                RAIL_THICK, halfAcross, half));
        if (back) out.add(rail(along - halfAlong + RAIL_THICK, across, up,
                RAIL_THICK, halfAcross, half));
        if (right) out.add(rail(along, across + halfAcross - RAIL_THICK, up,
                halfAlong, RAIL_THICK, half));
        if (left) out.add(rail(along, across - halfAcross + RAIL_THICK, up,
                halfAlong, RAIL_THICK, half));
    }

    /** The back rail of an open platform, in two pieces with the way in between. */
    private static void backRailWithGap(List<HousePart> out, Block block, double floor) {
        double along = block.a0() + RAIL_THICK;
        double up = floor + RAIL_HEIGHT / 2, half = RAIL_HEIGHT / 2;
        double gap = DOOR_WIDTH / 2;
        for (int s = -1; s <= 1; s += 2) {
            double lo = s < 0 ? block.c0() : block.midAcross() + gap;
            double hi = s < 0 ? block.midAcross() - gap : block.c1();
            if (hi - lo < 0.05) continue;
            out.add(rail(along, (lo + hi) / 2, up, RAIL_THICK, (hi - lo) / 2, half));
        }
    }

    private static HousePart rail(double along, double across, double up,
                                  double halfAlong, double halfAcross, double halfUp) {
        return new HousePart(Role.POST, Shape.BOX, along, across, up,
                halfAlong, halfAcross, halfUp, WatchMaterial.BARK, 1.0);
    }

    /**
     * The steps up to the front door, and the balcony over it.
     *
     * <p>Steps rather than a ramp, and {@link Role#STAIR} rather than
     * {@link Role#TRIM}, because {@link #DECK} is a third of a metre and a house
     * you have to jump into is a house nobody believes in.
     */
    private static void steps(List<HousePart> out, HousePlan plan, Block block) {
        if (plan.tree()) return;
        double front = block.a1();
        int count = 2;
        for (int i = 0; i < count; i++) {
            double top = DECK * (i + 1) / (double) count;
            out.add(new HousePart(Role.STAIR, Shape.BOX,
                    front + 0.24 * (count - i) - 0.12, block.midAcross(), top - 0.055,
                    0.12, DOOR_WIDTH / 2 + 0.28, 0.055, WatchMaterial.PLANK, 0.92));
        }
        if (!balcony(plan)) return;
        double floor = block.floorOf(1);
        double halfAlong = 0.62, halfAcross = DOOR_WIDTH / 2 + 0.75;
        slab(out, front + halfAlong, block.midAcross(), floor - FLOOR_THICK / 2,
                halfAlong, halfAcross);
        rails(out, front + halfAlong, block.midAcross(), halfAlong, halfAcross, floor,
                true, false, true, true);
        // Brackets under it, because a balcony hanging off nothing is the same
        // lie the treehouse's bearers are there to avoid.
        for (int s = -1; s <= 1; s += 2) {
            out.add(new HousePart(Role.TRIM, Shape.BOX, front + halfAlong * 0.6,
                    block.midAcross() + s * (halfAcross - 0.1), floor - 0.5,
                    halfAlong * 0.6, 0.06, 0.36, WatchMaterial.BARK, 0.92));
        }
    }

    // --- what is inside ----------------------------------------------------------------

    /**
     * The hearth, the furniture and the study wall.
     *
     * <p>Where the price ladder stops being about how much house there is and
     * starts being about whether it is a home: a fort is a box you stand in, a
     * cabin has a fire, and a lodge has a table, a bed, shelves and a wall to
     * pin maps to. Every one of them is a {@link Role#FITTING} — you can stand
     * on a table and you cannot get wedged behind a chair.
     */
    private static void fittings(List<HousePart> out, HousePlan plan, Block block) {
        HousePlan.Trim trim = plan.trim();
        if (!trim.atLeast(HousePlan.Trim.PLAIN)) return;
        double floor = block.floorOf(0);
        double back = block.a0(), right = block.c1(), left = block.c0();

        // The hearth, against the back wall, and the stack that carries the
        // smoke out through the roof once the house can afford a chimney.
        double hearthAcross = block.midAcross() + block.halfAcross() * 0.45;
        out.add(new HousePart(Role.WALL, Shape.BOX, back + 0.42, hearthAcross,
                floor + 0.42, 0.36, 0.62, 0.42, WatchMaterial.STONE_BLOCK, 1.0));
        out.add(new HousePart(Role.TRIM, Shape.BOX, back + 0.52, hearthAcross,
                floor + 0.16, 0.22, 0.44, 0.14, WatchMaterial.ASH, 1.0));
        if (trim.atLeast(HousePlan.Trim.FITTED)) {
            out.add(new HousePart(Role.WALL, Shape.BOX, back + 0.30, hearthAcross,
                    floor + (block.storeys() * HousePlan.STOREY + 1.4) / 2,
                    0.30, 0.50, (block.storeys() * HousePlan.STOREY + 1.4) / 2,
                    WatchMaterial.STONE_BLOCK, 0.94));
        }

        // A bench by the fire, from the first house that has a fire.
        out.add(new HousePart(Role.FITTING, Shape.BOX, back + 1.15,
                hearthAcross - 0.1, floor + 0.20, 0.22, 0.55, 0.05,
                WatchMaterial.PLANK, 0.9));

        if (!trim.atLeast(HousePlan.Trim.FITTED)) return;

        // A table in the middle of the room, and a bed against the far wall.
        double tableAlong = block.midAlong() + 0.2;
        double tableAcross = block.midAcross() - block.halfAcross() * 0.30;
        out.add(new HousePart(Role.FITTING, Shape.BOX, tableAlong, tableAcross,
                floor + 0.72, 0.52, 0.36, 0.04, WatchMaterial.PLANK, 1.0));
        for (int a = -1; a <= 1; a += 2) {
            for (int c = -1; c <= 1; c += 2) {
                out.add(new HousePart(Role.TRIM, Shape.BOX, tableAlong + a * 0.44,
                        tableAcross + c * 0.28, floor + 0.36, 0.05, 0.05, 0.36,
                        WatchMaterial.PLANK, 0.88));
            }
        }
        out.add(new HousePart(Role.FITTING, Shape.BOX, back + 1.0, left + 0.62,
                floor + 0.28, 0.95, 0.52, 0.16, WatchMaterial.PLANK, 0.94));
        out.add(new HousePart(Role.TRIM, Shape.BOX, back + 1.0, left + 0.62,
                floor + 0.50, 0.88, 0.46, 0.08, WatchMaterial.PELT, 1.0));

        // Shelves on the right-hand wall, which is what a room in this game is
        // actually for: somewhere to put what you found.
        for (int i = 0; i < 2; i++) {
            out.add(new HousePart(Role.FITTING, Shape.BOX, block.midAlong(),
                    right - 0.22, floor + 0.95 + i * 0.52, block.halfAlong() * 0.55,
                    0.18, 0.03, WatchMaterial.PLANK, 0.96));
        }

        if (!plan.board()) return;
        // The study wall: the map board, which is the one fitting that is more
        // than furniture. See HousePlan.board().
        out.add(new HousePart(Role.BOARD, Shape.BOARDS, back + WALL_THICK + BOARD_STAND,
                block.midAcross() - block.halfAcross() * 0.42, floor + BOARD_HEIGHT,
                BOARD_STAND, BOARD_HALF, BOARD_HALF, WatchMaterial.PLANK, 1.02));
    }

    /**
     * Where a house's map board hangs, in the house's frame — or {@code null}.
     *
     * <p>Asked by {@code WatchGame} when a house is bought, so that the
     * {@link com.larsons.engine.watch.Cartography.Board} it registers is at the
     * timber a player will walk up to rather than at the middle of the building.
     */
    public static HousePart boardOf(HousePlan plan) {
        if (!plan.board()) return null;
        for (HousePart part : parts(plan)) {
            if (part.role() == Role.BOARD) return part;
        }
        return null;
    }
}
