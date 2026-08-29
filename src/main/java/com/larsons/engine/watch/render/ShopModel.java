package com.larsons.engine.watch.render;

import com.larsons.engine.watch.Shops;
import com.larsons.engine.watch.Trading;
import com.larsons.engine.watch.world.WatchMaterial;
import com.larsons.engine.watch.world.WatchMaterials;

import java.util.List;

/**
 * The trading post, as a building.
 *
 * <h2>Why it is built out rather than blocked in</h2>
 *
 * <p>Everything else standing in this world is either something a player put
 * there one piece at a time ({@code build.BuildPiece}) or something the
 * generator scattered — and a shop is neither. It has to read as
 * <b>prebuilt</b>: as a thing somebody else made, on purpose, before you got
 * here, and made properly. A hut assembled from four of the player's own wall
 * pieces would read as a player's hut, which is exactly the wrong impression,
 * because the point of the post is that there is somebody in this wood who is
 * not you.
 *
 * <p>So it is a carpentry drawing rather than a box: stone footings, a raised
 * plank deck with a step up to it, corner posts carrying a plate beam, a pitched
 * roof on real rafters with the eaves oversailing the front, a back wall of
 * shelves, a counter across the open front, and a yard with a woodpile, crates,
 * a lantern post and a hitching rail. Around a hundred and twenty solids, which
 * is more than any other single object in the game and about a fifth of what one
 * chunk of hillside costs.
 *
 * <h2>The shelves are the real stock</h2>
 *
 * <p>The wares on the back shelves are the post's actual {@link Trading.Offer}s,
 * drawn through {@link ItemModel} — the same models the satchel, the ground and
 * a feeder's tray use. That is not decoration: a player who walks up can read
 * what a keeper sells off the shelf before opening anything, two posts a valley
 * apart visibly differ, and nothing here can drift out of step with the panel
 * because both are reading the same list.
 *
 * <h2>What moves</h2>
 *
 * <p>Two things, and both are on the world's own animation clock rather than a
 * frame count — see {@code WatchScene.animClock} for why that distinction cost a
 * whole round of fixes. The hanging sign swings, gently, and the lanterns' flames
 * flicker. Everything else is nailed down.
 */
public final class ShopModel {

    /**
     * How far the deck stands above the ground, in metres.
     *
     * <p>Public because the keeper has to stand on it, and the keeper is drawn
     * by a different class: a shop whose floor was 0.36 here and 0.35 wherever
     * the figure is placed is a keeper with their ankles through the boards.
     */
    public static final double DECK = 0.36;

    /** How thick the deck boards are. */
    private static final double DECK_THICK = 0.07;

    /**
     * How high the eaves are above the deck, and the ridge above the eaves.
     *
     * <p>The ridge is what gives the post a silhouette. A metre of rise over the
     * three and a half the front slope covers is sixteen degrees, which from
     * anywhere but directly in front reads as a flat lid on four posts; half as
     * much again is a roof.
     */
    private static final double EAVES = 2.42, RIDGE = 1.45;

    /** How far the roof oversails the counter, in metres. */
    private static final double OVERSAIL = 1.35;

    /** How fast the sign swings, in cycles a second, and how far in radians. */
    private static final double SIGN_RATE = 0.19, SIGN_SWING = 0.10;

    /** How fast a lantern flame gutters, in cycles a second. */
    private static final double FLAME_RATE = 2.7;

    private ShopModel() {}

    /**
     * The building's own frame of reference.
     *
     * <p>Every part below is placed in {@code (along, across, up)} — forward out
     * of the counter, right along the front, and up — and this turns that into
     * world metres. Written once because a hundred and twenty parts placed in
     * world coordinates would be a hundred and twenty chances to get the same
     * sine wrong, and because the whole post then turns as one when the siting
     * picks a different bearing.
     */
    private record Frame(double x, double y, double z, double yaw,
                         double fx, double fy, double sx, double sy) {

        static Frame at(double x, double y, double z, double yaw) {
            // The game's convention everywhere: forward is (sin, −cos) and the
            // walker's right is (cos, sin). Shapes.box's local +x is that right,
            // and its local +y is the opposite of forward — which is why `part`
            // passes the half-extents in the order it does.
            return new Frame(x, y, z, yaw, Math.sin(yaw), -Math.cos(yaw),
                    Math.cos(yaw), Math.sin(yaw));
        }

        double px(double along, double across) { return x + fx * along + sx * across; }

        double py(double along, double across) { return y + fy * along + sy * across; }
    }

    /**
     * Draw one trading post.
     *
     * @param ox    the mesh's origin, which the caller subtracts for precision
     * @param clock the world animation clock, in seconds
     * @param wares whether to put the stock on the shelves — dropped past a few
     *              tens of metres, where nine item models are nine hundred
     *              triangles nobody can see
     */
    public static void post(Mesh.Builder mesh, Shops.Shop shop, double ox, double oy,
                            double clock, boolean wares) {
        Frame f = Frame.at(shop.x() - ox, shop.y() - oy, shop.z(), shop.yaw());
        float[] timber = new float[4];
        float[] plank = new float[4];
        float[] thatch = new float[4];
        float[] stone = new float[4];
        WatchMaterials.uv(WatchMaterial.BARK, timber);
        WatchMaterials.uv(WatchMaterial.PLANK, plank);
        WatchMaterials.uv(WatchMaterial.THATCH, thatch);
        WatchMaterials.uv(WatchMaterial.STONE_BLOCK, stone);

        int beam = WatchMaterials.shade(WatchMaterial.DARK_BARK);
        int board = WatchMaterials.shade(WatchMaterial.PLANK);
        int shingle = WatchMaterials.shade(WatchMaterial.THATCH);
        int footing = WatchMaterials.shade(WatchMaterial.STONE_BLOCK);

        footings(mesh, f, stone, footing);
        deck(mesh, f, plank, board, timber, beam);
        frameAndWalls(mesh, f, plank, board, timber, beam);
        roof(mesh, f, thatch, shingle, timber, beam);
        counter(mesh, f, plank, board, timber, beam);
        shelves(mesh, f, shop.stock(), plank, board, wares);
        yard(mesh, f, plank, board, timber, beam, stone, footing, clock);
        sign(mesh, f, shop, timber, beam, plank, board, clock);
    }

    // --- the fabric ------------------------------------------------------------------

    /** Four dry-stone piers and a plinth, so the deck is not sitting in the mud. */
    private static void footings(Mesh.Builder mesh, Frame f, float[] uv, int colour) {
        for (int a = -1; a <= 1; a += 2) {
            for (int b = -1; b <= 1; b += 2) {
                double along = a * (Shops.HALF_DEPTH - 0.34);
                double across = b * (Shops.HALF_WIDTH - 0.34);
                // Two courses, the upper one set back — which is what makes a
                // stack of stones read as a pier somebody laid rather than a
                // block somebody dropped.
                part(mesh, f, along, across, DECK * 0.30, 0.30, 0.30, DECK * 0.30,
                        uv, colour);
                part(mesh, f, along, across, DECK * 0.78, 0.24, 0.24, DECK * 0.22,
                        uv, shade(colour, 1.07));
            }
        }
    }

    /** The deck: joists across, boards along, and a step up at the right-hand end. */
    private static void deck(Mesh.Builder mesh, Frame f, float[] plank, int board,
                             float[] timber, int beam) {
        for (int i = -1; i <= 1; i++) {
            part(mesh, f, i * (Shops.HALF_DEPTH - 0.25), 0, DECK - DECK_THICK - 0.05,
                    0.07, Shops.HALF_WIDTH, 0.05, timber, beam);
        }
        // Boards laid one at a time rather than one slab, because a floor with
        // lines in it is the difference between carpentry and a plinth.
        int boards = 9;
        double width = (Shops.HALF_WIDTH * 2 - 0.04) / boards;
        for (int i = 0; i < boards; i++) {
            double across = -Shops.HALF_WIDTH + width * (i + 0.5) + 0.02;
            part(mesh, f, 0, across, DECK - DECK_THICK / 2, Shops.HALF_DEPTH,
                    width / 2 - 0.012, DECK_THICK / 2, plank,
                    // Alternating weathering, so the grain reads at a distance.
                    shade(board, i % 2 == 0 ? 1.0 : 0.92));
        }
        // The step, at the open end where the counter does not block the way.
        for (int i = 0; i < 2; i++) {
            part(mesh, f, -Shops.HALF_DEPTH + 0.30, Shops.HALF_WIDTH + 0.28 + i * 0.30,
                    DECK * (0.62 - i * 0.30), 0.42, 0.17, DECK * 0.20, plank,
                    shade(board, 0.88));
        }
    }

    /** Corner posts, the plate beams they carry, and the walls between them. */
    private static void frameAndWalls(Mesh.Builder mesh, Frame f, float[] plank, int board,
                                      float[] timber, int beam) {
        double top = DECK + EAVES;
        for (int a = -1; a <= 1; a += 2) {
            for (int b = -1; b <= 1; b += 2) {
                part(mesh, f, a * (Shops.HALF_DEPTH - 0.12), b * (Shops.HALF_WIDTH - 0.12),
                        DECK + EAVES / 2, 0.10, 0.10, EAVES / 2, timber, beam);
            }
        }
        // Plate beams round the head of the posts: two along the sides, two
        // across the ends. The front one is what the counter's roof hangs from.
        for (int a = -1; a <= 1; a += 2) {
            part(mesh, f, a * (Shops.HALF_DEPTH - 0.12), 0, top, 0.10, Shops.HALF_WIDTH,
                    0.10, timber, beam);
        }
        for (int b = -1; b <= 1; b += 2) {
            part(mesh, f, 0, b * (Shops.HALF_WIDTH - 0.12), top, Shops.HALF_DEPTH, 0.10,
                    0.10, timber, beam);
        }

        // The back wall, boarded to the eaves — the shelves hang on it.
        int boards = 8;
        double width = (Shops.HALF_WIDTH * 2 - 0.30) / boards;
        for (int i = 0; i < boards; i++) {
            double across = -Shops.HALF_WIDTH + 0.15 + width * (i + 0.5);
            part(mesh, f, -Shops.HALF_DEPTH + 0.06, across, DECK + EAVES / 2, 0.045,
                    width / 2 - 0.01, EAVES / 2 - 0.02, plank,
                    shade(board, i % 3 == 0 ? 0.9 : 1.0));
        }
        // The side walls, boarded to shoulder height only: a counter shop is
        // open, and a fully enclosed one would hide the keeper and the shelves
        // from anybody standing in front of it.
        for (int b = -1; b <= 1; b += 2) {
            part(mesh, f, 0.05, b * (Shops.HALF_WIDTH - 0.06), DECK + 0.62,
                    Shops.HALF_DEPTH - 0.15, 0.04, 0.60, plank, shade(board, 0.94));
            // …and a brace across the open part of each side, which is what a
            // frame that is not going to rack looks like.
            Shapes.strut(mesh,
                    f.px(-Shops.HALF_DEPTH + 0.2, b * (Shops.HALF_WIDTH - 0.1)),
                    f.py(-Shops.HALF_DEPTH + 0.2, b * (Shops.HALF_WIDTH - 0.1)),
                    f.z() + DECK + 1.30,
                    f.px(Shops.HALF_DEPTH - 0.2, b * (Shops.HALF_WIDTH - 0.1)),
                    f.py(Shops.HALF_DEPTH - 0.2, b * (Shops.HALF_WIDTH - 0.1)),
                    f.z() + top - 0.12, 0.045, 0.045, timber, beam);
        }
    }

    /**
     * A pitched roof on rafters, oversailing the counter.
     *
     * <p>The ridge runs across the front rather than along it, so the slope
     * facing the road is the long one and the eaves come out over the counter —
     * which is both what a market stall does and the only arrangement in which
     * the keeper is standing in shade and the goods are out of the rain.
     */
    private static void roof(Mesh.Builder mesh, Frame f, float[] thatch, int shingle,
                             float[] timber, int beam) {
        double eaveZ = DECK + EAVES;
        double ridgeZ = eaveZ + RIDGE;
        double front = Shops.HALF_DEPTH + OVERSAIL;
        double back = -Shops.HALF_DEPTH - 0.35;
        double side = Shops.HALF_WIDTH + 0.30;
        double ridgeAlong = -Shops.HALF_DEPTH * 0.15;

        // The ridge beam, and the rafters hanging off it. Drawn before the
        // covering so that what shows under the eaves is timber.
        part(mesh, f, ridgeAlong, 0, ridgeZ + 0.04, 0.09, side, 0.09, timber, beam);
        for (int i = -3; i <= 3; i++) {
            double across = i * side / 3.2;
            Shapes.strut(mesh, f.px(ridgeAlong, across), f.py(ridgeAlong, across),
                    f.z() + ridgeZ, f.px(front, across), f.py(front, across),
                    f.z() + eaveZ - 0.06, 0.05, 0.05, timber, beam);
            Shapes.strut(mesh, f.px(ridgeAlong, across), f.py(ridgeAlong, across),
                    f.z() + ridgeZ, f.px(back, across), f.py(back, across),
                    f.z() + eaveZ - 0.06, 0.05, 0.05, timber, beam);
        }

        // The covering, as two slopes of overlapping courses. Courses rather
        // than one quad: a roof with lines across it reads as shingles from
        // thirty metres, and a flat plane reads as a lid.
        courses(mesh, f, thatch, shingle, ridgeAlong, ridgeZ, front, eaveZ, side, 7);
        courses(mesh, f, thatch, shingle, ridgeAlong, ridgeZ, back, eaveZ, side, 4);

        // A post at each oversailing corner, standing on the ground rather than
        // on the deck — the eaves reach past the deck, so there is nothing under
        // them to stand on — because eaves a metre and a third proud of the
        // frame are a cantilever holding themselves up by the grain.
        for (int b = -1; b <= 1; b += 2) {
            part(mesh, f, front - 0.22, b * (side - 0.24), eaveZ / 2, 0.075, 0.075,
                    eaveZ / 2, timber, shade(beam, 0.94));
        }
    }

    /** One slope, as overlapping courses from the ridge down to the eaves. */
    private static void courses(Mesh.Builder mesh, Frame f, float[] uv, int colour,
                                double ridgeAlong, double ridgeZ, double eaveAlong,
                                double eaveZ, double side, int count) {
        for (int i = 0; i < count; i++) {
            double t0 = i / (double) count, t1 = (i + 1.0) / count;
            // Each course laid a little proud of the one above it, which is what
            // an overlap is and what puts a shadow line between them.
            double a0 = ridgeAlong + (eaveAlong - ridgeAlong) * t0;
            double a1 = ridgeAlong + (eaveAlong - ridgeAlong) * t1;
            double z0 = ridgeZ + (eaveZ - ridgeZ) * t0;
            double z1 = ridgeZ + (eaveZ - ridgeZ) * t1;
            Shapes.strut(mesh, f.px(a0, -side), f.py(a0, -side), f.z() + z0 + 0.05,
                    f.px(a0, side), f.py(a0, side), f.z() + z0 + 0.05,
                    0.055, Math.hypot(a1 - a0, z1 - z0) * 0.62,
                    // Squared to the slope rather than to the world, so a course
                    // lies on the pitch instead of standing up out of it.
                    f.fx() * (a1 - a0), f.fy() * (a1 - a0), z1 - z0, uv,
                    shade(colour, i % 2 == 0 ? 1.0 : 0.9));
        }
    }

    /**
     * The counter: a heavy top on two piers, with a shelf under it and the
     * keeper's own clutter on top.
     */
    private static void counter(Mesh.Builder mesh, Frame f, float[] plank, int board,
                                float[] timber, int beam) {
        double top = Shops.COUNTER_TOP;
        double out = Shops.COUNTER_OUT;
        part(mesh, f, out, 0, top, 0.34, Shops.HALF_WIDTH - 0.10, 0.055, plank,
                shade(board, 1.06));
        // A worn lip along the front, which is the part a thousand elbows have
        // been on.
        part(mesh, f, out + 0.36, 0, top - 0.02, 0.05, Shops.HALF_WIDTH - 0.10, 0.045,
                timber, shade(beam, 1.1));
        for (int b = -1; b <= 1; b += 2) {
            part(mesh, f, out, b * (Shops.HALF_WIDTH - 0.35), top / 2, 0.24, 0.16,
                    top / 2, plank, shade(board, 0.86));
        }
        // The under-shelf, where the sacks live.
        part(mesh, f, out, 0, top * 0.42, 0.26, Shops.HALF_WIDTH - 0.42, 0.03, plank,
                shade(board, 0.8));
        for (int i = -1; i <= 1; i++) {
            Shapes.blob(mesh, f.px(out, i * 0.9), f.py(out, i * 0.9),
                    f.z() + top * 0.42 + 0.20, 0.20, 0.16, 0.19, f.yaw(), plank,
                    shade(board, 0.74));
        }

        // The ledger, open, with an inkwell and a pencil beside it. This is the
        // book a page gets stamped in, and it is on the counter for that reason
        // rather than as dressing.
        part(mesh, f, out - 0.02, -0.55, top + 0.045, 0.17, 0.24, 0.02, plank, 0xE6DCC2);
        part(mesh, f, out - 0.02, -0.55, top + 0.068, 0.16, 0.02, 0.012, plank, 0x8A7A5A);
        part(mesh, f, out - 0.05, -0.20, top + 0.075, 0.045, 0.045, 0.05, plank, 0x1E2430);
        Shapes.strut(mesh, f.px(out + 0.10, -0.34), f.py(out + 0.10, -0.34), f.z() + top + 0.03,
                f.px(out + 0.16, -0.24), f.py(out + 0.16, -0.24), f.z() + top + 0.03,
                0.008, 0.008, timber, 0xC08A3A);

        // The balance the points are weighed on. Nobody in this game weighs
        // anything, and a trading post without a set of scales on it is not a
        // trading post.
        double bx = out - 0.02, by = 0.75;
        part(mesh, f, bx, by, top + 0.12, 0.05, 0.05, 0.12, timber, 0x9A8A6A);
        part(mesh, f, bx, by, top + 0.25, 0.02, 0.30, 0.018, timber, 0xB8A878);
        for (int b = -1; b <= 1; b += 2) {
            part(mesh, f, bx, by + b * 0.26, top + 0.20, 0.09, 0.09, 0.012, timber,
                    0xC8B888);
        }
    }

    /**
     * The back shelves, and the post's actual stock standing on them.
     *
     * <p>Three courses, filled left to right in the order the panel lists them,
     * so the shelf and the screen agree about what this keeper has.
     */
    private static void shelves(Mesh.Builder mesh, Frame f, List<Trading.Offer> stock,
                                float[] plank, int board, boolean wares) {
        double back = -Shops.HALF_DEPTH + 0.30;
        double[] heights = {DECK + 0.52, DECK + 1.06, DECK + 1.60};
        for (double height : heights) {
            part(mesh, f, back, 0, height, 0.22, Shops.HALF_WIDTH - 0.22, 0.03, plank,
                    shade(board, 0.9));
            // The bracket under each end, which is what a shelf hangs on.
            for (int b = -1; b <= 1; b += 2) {
                part(mesh, f, back - 0.10, b * (Shops.HALF_WIDTH - 0.34), height - 0.09,
                        0.10, 0.03, 0.07, plank, shade(board, 0.8));
            }
        }
        if (!wares) return;

        int perShelf = 3;
        for (int i = 0; i < stock.size() && i < heights.length * perShelf; i++) {
            int shelf = i / perShelf, slot = i % perShelf;
            double across = (slot - 1.0) * (Shops.HALF_WIDTH - 0.5);
            ItemModel.item(mesh, stock.get(i).item(), f.px(back, across),
                    f.py(back, across), f.z() + heights[shelf] + 0.03, 1.15,
                    f.yaw() + slot * 0.7);
        }
    }

    /** The yard: crates, a barrel, a woodpile, a hitching rail and a lantern. */
    private static void yard(Mesh.Builder mesh, Frame f, float[] plank, int board,
                             float[] timber, int beam, float[] stone, int footing,
                             double clock) {
        // Crates, stacked, at the closed end of the counter.
        part(mesh, f, Shops.HALF_DEPTH + 0.55, -Shops.HALF_WIDTH - 0.75, 0.28, 0.28, 0.28,
                0.28, plank, shade(board, 0.92));
        part(mesh, f, Shops.HALF_DEPTH + 0.62, -Shops.HALF_WIDTH - 0.70, 0.79, 0.23, 0.23,
                0.23, plank, shade(board, 1.02));
        part(mesh, f, Shops.HALF_DEPTH + 0.55, -Shops.HALF_WIDTH - 0.75, 0.57, 0.30, 0.30,
                0.025, timber, shade(beam, 1.0));

        // A barrel, which is a prism and not a box, because a barrel that is a
        // box is a crate.
        Shapes.prism(mesh, f.px(-Shops.HALF_DEPTH - 0.85, Shops.HALF_WIDTH + 0.55),
                f.py(-Shops.HALF_DEPTH - 0.85, Shops.HALF_WIDTH + 0.55), f.z(),
                f.z() + 0.78, 0.30, 0.27, 7, f.yaw(), plank, shade(board, 0.88), true);
        for (int i = 0; i < 2; i++) {
            Shapes.prism(mesh, f.px(-Shops.HALF_DEPTH - 0.85, Shops.HALF_WIDTH + 0.55),
                    f.py(-Shops.HALF_DEPTH - 0.85, Shops.HALF_WIDTH + 0.55),
                    f.z() + 0.18 + i * 0.40, f.z() + 0.24 + i * 0.40, 0.31, 0.31, 7,
                    f.yaw(), timber, shade(beam, 1.05), false);
        }

        // The woodpile, along the back — split logs, ends out.
        for (int row = 0; row < 3; row++) {
            for (int i = 0; i < 5; i++) {
                double across = -1.0 + i * 0.24 + (row % 2) * 0.11;
                Shapes.prism(mesh,
                        f.px(-Shops.HALF_DEPTH - 1.15, across),
                        f.py(-Shops.HALF_DEPTH - 1.15, across),
                        f.z() + row * 0.20 + 0.02, f.z() + row * 0.20 + 0.20,
                        0.105, 0.10, 5, f.yaw() + i, timber,
                        shade(beam, 0.9 + (i % 3) * 0.07), true);
            }
        }

        // The hitching rail, out in front where the road is.
        for (int b = -1; b <= 1; b += 2) {
            part(mesh, f, Shops.HALF_DEPTH + 2.5, b * 1.5, 0.52, 0.07, 0.07, 0.52,
                    timber, beam);
        }
        part(mesh, f, Shops.HALF_DEPTH + 2.5, 0, 0.98, 0.055, 1.55, 0.055, timber,
                shade(beam, 1.06));

        // The lantern post, and the light in it. A post in a wood at dusk is
        // findable because of this.
        double lx = Shops.HALF_DEPTH + 1.9, ly = Shops.HALF_WIDTH + 0.9;
        part(mesh, f, lx, ly, 1.05, 0.06, 0.06, 1.05, timber, beam);
        part(mesh, f, lx - 0.18, ly, 2.06, 0.20, 0.05, 0.05, timber, beam);
        lantern(mesh, f, lx - 0.34, ly, 1.86, clock);
        lantern(mesh, f, Shops.COUNTER_OUT - 0.05, -Shops.HALF_WIDTH + 0.55,
                Shops.COUNTER_TOP + 0.19, clock + 0.4);
    }

    /** One lantern: a case, a cap, and a flame that is never quite the same size. */
    private static void lantern(Mesh.Builder mesh, Frame f, double along, double across,
                                double up, double clock) {
        float[] glass = new float[4];
        float[] timber = new float[4];
        WatchMaterials.uv(WatchMaterial.GLASSPANE, glass);
        WatchMaterials.uv(WatchMaterial.BARK, timber);
        part(mesh, f, along, across, up, 0.075, 0.075, 0.10, glass, 0x6A5C3A);
        part(mesh, f, along, across, up + 0.13, 0.09, 0.09, 0.03, timber, 0x3A3128);
        part(mesh, f, along, across, up - 0.12, 0.085, 0.085, 0.022, timber, 0x3A3128);
        // The flame. Its own small cycle, off the world clock like everything
        // else that animates on its own here.
        double flicker = 0.7 + 0.3 * Math.sin(clock * FLAME_RATE * Math.PI * 2)
                * Math.sin(clock * FLAME_RATE * 1.7);
        Shapes.blob(mesh, f.px(along, across), f.py(along, across), f.z() + up,
                0.03, 0.03, 0.055 * flicker, f.yaw(), glass, 0xFFD07A);
    }

    /**
     * The sign: a bracket off the front corner post and a board hanging from it
     * on two rings, swinging.
     *
     * <p>The one part of the building that is not still, and the reason a post
     * catches the eye from across a clearing.
     */
    private static void sign(Mesh.Builder mesh, Frame f, Shops.Shop shop, float[] timber,
                             int beam, float[] plank, int board, double clock) {
        double along = Shops.HALF_DEPTH - 0.05;
        double across = Shops.HALF_WIDTH + 0.10;
        double head = DECK + EAVES - 0.18;
        // The bracket, out and a little up, with a diagonal stay under it.
        part(mesh, f, along, across + 0.42, head, 0.05, 0.44, 0.05, timber, beam);
        Shapes.strut(mesh, f.px(along, across), f.py(along, across), f.z() + head - 0.55,
                f.px(along, across + 0.72), f.py(along, across + 0.72), f.z() + head - 0.05,
                0.035, 0.035, timber, shade(beam, 0.94));

        double swing = Math.sin(clock * SIGN_RATE * Math.PI * 2) * SIGN_SWING
                + Math.sin(clock * SIGN_RATE * 2.7) * SIGN_SWING * 0.3;
        // The board hangs from the bracket's end and rocks about it, so the top
        // edge stays put and the bottom travels — which is what a hanging thing
        // does and what a board rotated about its own middle does not.
        double pivotZ = f.z() + head - 0.10;
        double hang = 0.62;
        double drop = Math.cos(swing) * hang, sway = Math.sin(swing) * hang;
        double bx = f.px(along, across + 0.72 + sway), by = f.py(along, across + 0.72 + sway);
        for (int b = -1; b <= 1; b += 2) {
            Shapes.strut(mesh, f.px(along, across + 0.72 + b * 0.28),
                    f.py(along, across + 0.72 + b * 0.28), pivotZ,
                    bx + f.sx() * b * 0.28, by + f.sy() * b * 0.28, pivotZ - drop * 0.35,
                    0.016, 0.016, timber, shade(beam, 1.1));
        }
        // Deliberately large — this is what a player sees first from across a
        // clearing, and a sign small enough to be in proportion is a sign nobody
        // notices. Real ones are oversized for the same reason.
        Shapes.box(mesh, bx, by, pivotZ - drop * 0.82, 0.42, 0.035, 0.30, f.yaw(), plank,
                shade(board, 1.08));
        // A painted panel on both faces of it, which is as much of a sign as
        // this renderer can write. What it says is on the HUD when you walk up.
        for (int side = -1; side <= 1; side += 2) {
            Shapes.box(mesh, bx + f.fx() * side * 0.045, by + f.fy() * side * 0.045,
                    pivotZ - drop * 0.82, 0.34, 0.012, 0.22, f.yaw(), plank,
                    tintFor(shop));
        }
    }

    /**
     * The colour of a post's sign board, from its own id.
     *
     * <p>Two posts in a day's walk should not be the same shop twice, and the
     * cheapest true difference between them is the paint.
     */
    private static int tintFor(Shops.Shop shop) {
        int[] paints = {0x2F5A6B, 0xA8482F, 0x4A6B33, 0x7A4A8A, 0xB08030, 0x35505C};
        return paints[(int) Math.floorMod(shop.id() >>> 7, paints.length)];
    }

    // --- plumbing --------------------------------------------------------------------

    /**
     * One box, placed in the building's own frame.
     *
     * <p>{@code Shapes.box}'s local {@code +x} is the frame's {@code across} and
     * its local {@code +y} is the opposite of {@code along}, so the half-extents
     * go in as (across, along, up). Stated once here rather than at each of the
     * hundred and twenty call sites.
     */
    private static void part(Mesh.Builder mesh, Frame f, double along, double across,
                             double up, double halfAlong, double halfAcross, double halfUp,
                             float[] uv, int colour) {
        Shapes.box(mesh, f.px(along, across), f.py(along, across), f.z() + up,
                halfAcross, halfAlong, halfUp, f.yaw(), uv, colour);
    }

    /** A colour, lightened or darkened — how one timber is told from the next. */
    static int shade(int rgb, double scale) {
        int r = Math.min(255, (int) (((rgb >> 16) & 0xFF) * scale));
        int g = Math.min(255, (int) (((rgb >> 8) & 0xFF) * scale));
        int b = Math.min(255, (int) ((rgb & 0xFF) * scale));
        return (r << 16) | (g << 8) | b;
    }
}
