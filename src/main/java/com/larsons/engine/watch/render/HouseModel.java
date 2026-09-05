package com.larsons.engine.watch.render;

import com.larsons.engine.watch.home.HouseKit;
import com.larsons.engine.watch.home.HousePart;
import com.larsons.engine.watch.home.Homestead;
import com.larsons.engine.watch.world.WatchMaterial;
import com.larsons.engine.watch.world.WatchMaterials;

import java.util.List;

/**
 * A bought house, as triangles.
 *
 * <h2>It draws the list it is given, and nothing else</h2>
 *
 * <p>This class has no idea what a lodge looks like. {@link HouseKit} decides
 * that, once, and hands over a list of boxes; the job here is to make each box
 * look like the thing it is. That division is the whole reason a house cannot
 * have a wall you can see and walk through — see {@link HousePart}, which is
 * where the argument is written down — and it is why adding a plan to
 * {@link com.larsons.engine.watch.home.HousePlan} needs no change in this file.
 *
 * <p>What it does add is the carpentry that a box cannot carry: a floor is sawn
 * into boards, a wall into weatherboarding, a roof into overlapping courses, a
 * ladder into stiles and rungs. Those are {@link HousePart.Shape}s rather than
 * roles, because they are drawing instructions and nothing else in the game
 * needs to know about them — a floor is a floor to the feet whether it is drawn
 * as one slab or nine boards.
 *
 * <h2>Why the boards are worth it</h2>
 *
 * <p>{@code ShopModel} made the same call and gave the reason: a floor with
 * lines in it is the difference between carpentry and a plinth. It costs a
 * factor of four or so in triangles — a mansion is about twenty thousand of
 * them with its boards sawn and its furniture in it, and about four and a half
 * with neither — which for a house that is meshed when it is bought and then
 * held is nothing. See {@code WatchScene.homeMesh}, which rebuilds only when
 * the homestead changes, and the {@code detail} flag below, which spends that
 * factor only on the houses near enough for it to buy anything.
 */
public final class HouseModel {

    /** How wide one sawn board is, in metres. */
    private static final double BOARD = 0.26;

    /** The most boards one part is cut into. */
    private static final int MAX_BOARDS = 14;

    /** How many courses a roof slope is laid in, near and far. */
    private static final int COURSES = 7, FAR_COURSES = 2;

    /** How far apart a ladder's rungs are, in metres. */
    private static final double RUNG_SPACING = 0.30;

    private HouseModel() {}

    /**
     * Draw one house.
     *
     * @param parts  what it is made of, from {@link Homestead#partsOf}
     * @param ox     the mesh's origin, which the caller subtracts for precision
     * @param detail whether to saw the boards, lay the courses and put the
     *               furniture in — dropped past a few tens of metres, where a
     *               mansion's twenty thousand triangles buy nothing. The same
     *               call {@code ShopModel} makes about a keeper's wares, and
     *               for the same reason
     */
    public static void house(Mesh.Builder mesh, Homestead.Home home,
                             List<HousePart> parts, double ox, double oy,
                             boolean detail) {
        Frame frame = Frame.at(home.x() - ox, home.y() - oy, home.z(), home.yaw());
        float[] uv = new float[4];
        for (HousePart part : parts) {
            if (!detail && part.role().detail()) continue;
            WatchMaterials.uv(part.material(), uv);
            int albedo = ShopModel.shade(WatchMaterials.shade(part.material()),
                    part.shade());
            switch (part.shape()) {
                case BOARDS -> {
                    if (detail) boards(mesh, frame, part, uv, albedo);
                    else box(mesh, frame, part.along(), part.across(), part.up(),
                            part.halfAlong(), part.halfAcross(), part.halfUp(),
                            uv, albedo);
                }
                case LADDER -> ladder(mesh, frame, part, uv, albedo);
                case PITCH_FRONT, PITCH_BACK, PITCH_RIGHT, PITCH_LEFT ->
                        courses(mesh, frame, part, uv, albedo,
                                detail ? COURSES : FAR_COURSES);
                default -> box(mesh, frame, part.along(), part.across(), part.up(),
                        part.halfAlong(), part.halfAcross(), part.halfUp(), uv, albedo);
            }
        }
        if (detail) door(mesh, frame, home, uv);
    }

    /**
     * The house's own frame of reference: forward out of the front door, right
     * along the front, and up.
     *
     * <p>{@code ShopModel.Frame}'s twin, and deliberately not shared with it:
     * the two are four lines each, and a common base class for four lines would
     * be a package boundary to think about every time either building changes.
     */
    private record Frame(double x, double y, double z, double yaw,
                         double fx, double fy, double sx, double sy) {

        static Frame at(double x, double y, double z, double yaw) {
            return new Frame(x, y, z, yaw, Math.sin(yaw), -Math.cos(yaw),
                    Math.cos(yaw), Math.sin(yaw));
        }

        double px(double along, double across) { return x + fx * along + sx * across; }

        double py(double along, double across) { return y + fy * along + sy * across; }
    }

    private static void box(Mesh.Builder mesh, Frame f, double along, double across,
                            double up, double halfAlong, double halfAcross, double halfUp,
                            float[] uv, int albedo) {
        Shapes.box(mesh, f.px(along, across), f.py(along, across), f.z() + up,
                halfAcross, halfAlong, halfUp, f.yaw(), uv, albedo);
    }

    /**
     * A part sawn into boards.
     *
     * <p>Cut across whichever of its two broad faces is longer, so a floor comes
     * out as floorboards and a wall as weatherboarding without either being told
     * which it is. The thinnest axis is the thickness and is never cut, which is
     * what stops a plank being sliced into a stack of veneers.
     */
    private static void boards(Mesh.Builder mesh, Frame f, HousePart part,
                               float[] uv, int albedo) {
        double hA = part.halfAlong(), hC = part.halfAcross(), hU = part.halfUp();
        double thin = Math.min(hA, Math.min(hC, hU));
        // Which axis to saw along: the longer of the two that are not the
        // thickness.
        int axis;
        if (thin == hU) axis = hA >= hC ? 0 : 1;
        else if (thin == hA) axis = hC >= hU ? 1 : 2;
        else axis = hA >= hU ? 0 : 2;
        double half = axis == 0 ? hA : axis == 1 ? hC : hU;
        int count = Math.max(1, Math.min(MAX_BOARDS, (int) Math.round(half * 2 / BOARD)));
        double width = half * 2 / count;
        for (int i = 0; i < count; i++) {
            double offset = -half + width * (i + 0.5);
            // Alternating weathering, so the grain reads at a distance. The
            // same trick and the same reason as the trading post's deck.
            int shade = ShopModel.shade(albedo, i % 2 == 0 ? 1.0 : 0.93);
            // A hair narrower than its slot, which is what puts a shadow line
            // between one board and the next rather than a seam.
            double halfBoard = width / 2 - 0.008;
            if (halfBoard <= 0.001) halfBoard = width / 2;
            switch (axis) {
                case 0 -> box(mesh, f, part.along() + offset, part.across(), part.up(),
                        halfBoard, hC, hU, uv, shade);
                case 1 -> box(mesh, f, part.along(), part.across() + offset, part.up(),
                        hA, halfBoard, hU, uv, shade);
                default -> box(mesh, f, part.along(), part.across(), part.up() + offset,
                        hA, hC, halfBoard, uv, shade);
            }
        }
    }

    /**
     * A ladder: two stiles and a run of rungs.
     *
     * <p>Drawn rather than boxed because a ladder is the one part of a house
     * whose whole meaning is that you can see it is climbable. A solid slab
     * against a trunk reads as a buttress, and a player who cannot tell that a
     * treehouse has a way up will stand under it looking for one.
     */
    private static void ladder(Mesh.Builder mesh, Frame f, HousePart part,
                               float[] uv, int albedo) {
        double height = part.halfUp() * 2;
        double stile = part.halfAcross() - 0.045;
        for (int s = -1; s <= 1; s += 2) {
            box(mesh, f, part.along(), part.across() + s * stile, part.up(),
                    part.halfAlong() * 0.6, 0.045, part.halfUp(), uv, albedo);
        }
        int rungs = Math.max(2, (int) Math.round(height / RUNG_SPACING));
        double spacing = height / rungs;
        for (int i = 0; i < rungs; i++) {
            double z = part.bottom() + spacing * (i + 0.5);
            box(mesh, f, part.along() + part.halfAlong() * 0.35, part.across(), z,
                    part.halfAlong() * 0.35, stile, 0.032, uv,
                    ShopModel.shade(albedo, 0.92));
        }
    }

    /**
     * One roof slope, as overlapping courses from the ridge down to the eaves.
     *
     * <p>Courses rather than one slab, for the trading post's reason: a roof
     * with lines across it reads as shingles from thirty metres, and a flat
     * plane reads as a lid. Each course is a {@link Shapes#strut} squared to the
     * slope rather than to the world, so the covering lies on the pitch instead
     * of stepping down it.
     */
    private static void courses(Mesh.Builder mesh, Frame f, HousePart part,
                                float[] uv, int albedo, int count) {
        boolean alongFall = part.shape() == HousePart.Shape.PITCH_FRONT
                || part.shape() == HousePart.Shape.PITCH_BACK;
        // Where the covering starts high and where it finishes low, in the
        // house's frame. The shape names the direction of the fall.
        double highAlong, highAcross, lowAlong, lowAcross;
        double spread = alongFall ? part.halfAcross() : part.halfAlong();
        switch (part.shape()) {
            case PITCH_FRONT -> {
                highAlong = part.along() - part.halfAlong();
                lowAlong = part.along() + part.halfAlong();
                highAcross = lowAcross = part.across();
            }
            case PITCH_BACK -> {
                highAlong = part.along() + part.halfAlong();
                lowAlong = part.along() - part.halfAlong();
                highAcross = lowAcross = part.across();
            }
            case PITCH_RIGHT -> {
                highAcross = part.across() - part.halfAcross();
                lowAcross = part.across() + part.halfAcross();
                highAlong = lowAlong = part.along();
            }
            default -> {
                highAcross = part.across() + part.halfAcross();
                lowAcross = part.across() - part.halfAcross();
                highAlong = lowAlong = part.along();
            }
        }
        double ridge = part.top(), eave = part.bottom();

        for (int i = 0; i < count; i++) {
            double t0 = i / (double) count, t1 = (i + 1.0) / count;
            double a0 = highAlong + (lowAlong - highAlong) * t0;
            double a1 = highAlong + (lowAlong - highAlong) * t1;
            double c0 = highAcross + (lowAcross - highAcross) * t0;
            double c1 = highAcross + (lowAcross - highAcross) * t1;
            double z0 = ridge + (eave - ridge) * t0;
            double z1 = ridge + (eave - ridge) * t1;
            // The course itself runs across the fall, from one end of the slope
            // to the other; its depth is measured down the slope, so that each
            // one laps the one above it.
            double lapA = a1 - a0, lapC = c1 - c0, lapZ = z1 - z0;
            double thick = Math.sqrt(lapA * lapA + lapC * lapC + lapZ * lapZ) * 0.62;
            double sa = alongFall ? a0 : a0 - spread;
            double ea = alongFall ? a0 : a0 + spread;
            double sc = alongFall ? c0 - spread : c0;
            double ec = alongFall ? c0 + spread : c0;
            Shapes.strut(mesh,
                    f.px(sa, sc), f.py(sa, sc), f.z() + z0 + 0.04,
                    f.px(ea, ec), f.py(ea, ec), f.z() + z0 + 0.04,
                    0.055, thick,
                    // Squared to the slope rather than to the world, so a course
                    // lies flat on the pitch instead of standing up off it.
                    f.fx() * lapA + f.sx() * lapC, f.fy() * lapA + f.sy() * lapC, lapZ,
                    uv, ShopModel.shade(albedo, i % 2 == 0 ? 1.0 : 0.94));
        }
    }

    /**
     * The front door, standing open.
     *
     * <p>The one thing drawn from the house rather than from a part, and the
     * only piece of a house with no box behind it — because a door that
     * collided would be a door that had to swing, and a door standing ajar says
     * "come in" without anybody having to press anything. Left out of a
     * {@linkplain com.larsons.engine.watch.home.HousePlan#LEAN_TO lean-to},
     * which has no front wall to hang it on.
     */
    private static void door(Mesh.Builder mesh, Frame f, Homestead.Home home,
                             float[] uv) {
        if (home.plan() == com.larsons.engine.watch.home.HousePlan.LEAN_TO) return;
        if (home.plan().roof() == com.larsons.engine.watch.home.HousePlan.Roof.OPEN) return;
        WatchMaterials.uv(WatchMaterial.PLANK, uv);
        int albedo = ShopModel.shade(WatchMaterials.shade(WatchMaterial.PLANK), 0.82);
        double front = home.plan().halfAlong();
        double half = HouseKit.DOOR_WIDTH / 2;
        // Hinged at one side and swung inward about forty degrees, which is
        // just enough that the doorway reads as a doorway from side-on.
        double swing = 0.7;
        double hinge = -half;
        double leafA = front - Math.sin(swing) * half;
        double leafC = hinge + Math.cos(swing) * half;
        Shapes.strut(mesh,
                f.px(front, hinge), f.py(front, hinge),
                f.z() + HouseKit.DECK + HouseKit.DOOR_HEIGHT / 2,
                f.px(leafA, leafC), f.py(leafA, leafC),
                f.z() + HouseKit.DECK + HouseKit.DOOR_HEIGHT / 2,
                HouseKit.DOOR_HEIGHT / 2, 0.03, 0, 0, 1, uv, albedo);
    }
}
