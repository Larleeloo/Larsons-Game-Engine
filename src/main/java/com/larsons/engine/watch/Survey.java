package com.larsons.engine.watch;

import com.larsons.engine.watch.home.Homestead;
import com.larsons.engine.watch.world.Grove;
import com.larsons.engine.watch.world.TerrainField;
import com.larsons.engine.watch.world.TreeInstance;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What goes on a map the moment it is drawn.
 *
 * <h2>Filled in, not revealed</h2>
 *
 * <p><b>A map in this game arrives finished.</b> There is no fog to walk off
 * and no corner that fills in later: the ground is a pure function of the seed,
 * so the picture is complete the instant the paper exists, and the icons are
 * whatever was standing there when somebody surveyed it. Fog of war would be a
 * mechanic borrowed from a different kind of game — one where the map is the
 * reward for exploring — and this is a game where exploring is the reward for
 * exploring. What a map is for here is finding your way back.
 *
 * <p>The consequence is that a map <em>ages</em>. The keeper's post is on it for
 * ever, because a trading post cannot move; the feeder you had out that morning
 * is on it long after it rotted, and the camp you built the following week is
 * not on it at all. That is the difference between a map and a minimap, and it
 * is the reason there is any point in drawing a second map of the same country.
 *
 * <h2>Where each icon comes from</h2>
 *
 * <p>Every pass below reads something the world already has. Nothing is
 * invented to give the map something to draw, and nothing is stored to make the
 * survey cheaper — a survey runs once, at the moment a key is pressed, and is
 * then frozen into the {@link Chart}.
 *
 * <ul>
 *   <li>{@link Chart.Kind#SHOP} — {@link Shops}, which generates posts from the
 *       seed, so they are on the map whether or not anybody has found them.
 *       <b>That is deliberate</b>: a map whose trading posts appear only once
 *       you have walked to them is a map that cannot tell you where to walk.</li>
 *   <li>{@link Chart.Kind#CAMP} — {@link Homestead}: one icon per house, named
 *       for what it is. It needed clustering when a camp was forty separate
 *       built pieces; a house is one purchase standing in one place, so the
 *       icon can say "Mansion" instead of "forty pieces".</li>
 *   <li>{@link Chart.Kind#FEEDER} — the feeders standing.</li>
 *   <li>{@link Chart.Kind#PLANTING} — {@link Grove}, likewise clustered: an
 *       orchard is a place, and its individual trees are not.</li>
 *   <li>{@link Chart.Kind#BOAT} — {@link Boats}, which are generated the same
 *       way the posts are, plus wherever anybody has left one.</li>
 *   <li>{@link Chart.Kind#SIGHTING} — the first sighting of each species in the
 *       {@link FieldGuide}, which is the one thing on the map that is about the
 *       game rather than about the country.</li>
 *   <li>{@link Chart.Kind#SUMMIT} — read off the heightfield: the local maxima
 *       of a coarse grid. The only pass that costs anything, and it is
 *       {@value #SUMMIT_GRID}² height samples once.</li>
 * </ul>
 */
public final class Survey {

    /** How many icons of any one kind a map will carry. */
    private static final int PER_KIND = 12;

    /** How close two planted trees have to be to count as one orchard, in metres. */
    private static final double ORCHARD_CLUSTER = 22;

    /** How coarse the grid the high ground is read off is. */
    private static final int SUMMIT_GRID = 17;

    /** How far above the map's own mean height a point has to be to be a summit. */
    private static final double SUMMIT_MARGIN = 18;

    private Survey() {}

    /**
     * Everything worth an icon inside a square of the world.
     *
     * <p>Takes each source rather than a {@code WatchGame}, so the survey can be
     * run against a world assembled in a test without one — and so that reading
     * this method tells you exactly which parts of the world a map depends on.
     *
     * @param radius half the square's width, in metres
     */
    public static List<Chart.Landmark> survey(TerrainField field, Shops shops,
                                              Homestead homes,
                                              Collection<Lure> lures, Grove grove,
                                              Boats boats, FieldGuide guide,
                                              double centreX, double centreY,
                                              double radius) {
        List<Chart.Landmark> out = new ArrayList<>();
        // The circle that contains the square, so nothing in a corner is missed
        // by a source that only answers radial queries.
        double reach = radius * Math.sqrt(2) + 1;

        if (shops != null && field != null) {
            int taken = 0;
            for (Shops.Shop shop : shops.near(field, centreX, centreY, reach)) {
                if (taken >= PER_KIND) break;
                if (!inside(shop.x(), shop.y(), centreX, centreY, radius)) continue;
                out.add(new Chart.Landmark(Chart.Kind.SHOP, shop.x(), shop.y(),
                        shop.sign()));
                taken++;
            }
        }

        if (homes != null) {
            int taken = 0;
            for (Homestead.Home home : homes.near(centreX, centreY, reach)) {
                if (taken >= PER_KIND) break;
                if (!inside(home.x(), home.y(), centreX, centreY, radius)) continue;
                out.add(new Chart.Landmark(Chart.Kind.CAMP, home.x(), home.y(),
                        home.plan().displayName()));
                taken++;
            }
        }

        if (lures != null) {
            int taken = 0;
            for (Lure lure : lures) {
                if (taken >= PER_KIND) break;
                if (!inside(lure.x(), lure.y(), centreX, centreY, radius)) continue;
                out.add(new Chart.Landmark(Chart.Kind.FEEDER, lure.x(), lure.y(),
                        Forage.nameOf(lure.food()) + " feeder"));
                taken++;
            }
        }

        if (grove != null) {
            List<double[]> orchards = cluster(points(grove.near(centreX, centreY, reach)),
                    ORCHARD_CLUSTER);
            int taken = 0;
            for (double[] orchard : orchards) {
                if (taken >= PER_KIND) break;
                if (!inside(orchard[0], orchard[1], centreX, centreY, radius)) continue;
                int trees = (int) orchard[2];
                out.add(new Chart.Landmark(Chart.Kind.PLANTING, orchard[0], orchard[1],
                        trees == 1 ? "A planted tree" : trees + " planted trees"));
                taken++;
            }
        }

        if (boats != null && field != null) {
            int taken = 0;
            for (Boats.Boat boat : boats.near(field, centreX, centreY, reach)) {
                if (taken >= PER_KIND) break;
                if (!inside(boat.x(), boat.y(), centreX, centreY, radius)) continue;
                out.add(new Chart.Landmark(Chart.Kind.BOAT, boat.x(), boat.y(),
                        "Rowing boat"));
                taken++;
            }
        }

        if (guide != null) {
            int taken = 0;
            for (Sighting sighting : guide.journal()) {
                if (taken >= PER_KIND) break;
                if (!sighting.first()) continue;
                if (!inside(sighting.x(), sighting.y(), centreX, centreY, radius)) continue;
                var def = sighting.def();
                out.add(new Chart.Landmark(Chart.Kind.SIGHTING, sighting.x(),
                        sighting.y(), def != null ? def.name() : sighting.species()));
                taken++;
            }
        }

        if (field != null) out.addAll(summits(field, centreX, centreY, radius));
        return out;
    }

    /**
     * A short name for a fresh map: the country it is of, and where that is.
     *
     * <p>Better than "Map 3" and better than blank, and it is the string the
     * rename field opens on — a player who wants their own name for it types
     * over something that already says which map this is, which is the whole
     * reason a default matters.
     */
    public static String nameFor(TerrainField field, double x, double y) {
        String place = field == null ? "Unknown Country"
                : field.biomeAt(x, y).displayName();
        return Chart.trim(place + " " + bearing(x, y), Chart.MAX_NAME_LENGTH);
    }

    /**
     * Where a point is, in the form a map's title block would put it.
     *
     * <p>North is −y, which is the convention the whole game turns in: forward
     * is {@code (sin yaw, −cos yaw)}, so a walker facing yaw zero is walking
     * toward smaller y, and a map drawn with north up therefore has −y at the
     * top. Getting this backwards would put every label on the wrong side of
     * the world, so it is written down once, here, and the panel takes its
     * orientation from the same sentence.
     */
    public static String bearing(double x, double y) {
        long east = Math.round(x);
        long north = Math.round(-y);
        return Math.abs(east) + (east < 0 ? "W " : "E ")
                + Math.abs(north) + (north < 0 ? "S" : "N");
    }

    /**
     * The high ground inside the square.
     *
     * <p>Local maxima of a coarse grid: a point is a summit if it is higher
     * than all eight of its neighbours on that grid and stands
     * {@value #SUMMIT_MARGIN} metres clear of the square's own mean. The second
     * condition is what keeps a map of a flat marsh from being sprinkled with
     * "high ground" icons on the tallest hummocks — high is relative to the
     * country it is in, and the country is what the map is of.
     */
    private static List<Chart.Landmark> summits(TerrainField field, double centreX,
                                                double centreY, double radius) {
        double step = radius * 2 / (SUMMIT_GRID - 1.0);
        double[][] h = new double[SUMMIT_GRID][SUMMIT_GRID];
        double sum = 0;
        for (int iy = 0; iy < SUMMIT_GRID; iy++) {
            for (int ix = 0; ix < SUMMIT_GRID; ix++) {
                double wx = centreX - radius + ix * step;
                double wy = centreY - radius + iy * step;
                h[iy][ix] = field.heightAt(wx, wy);
                sum += h[iy][ix];
            }
        }
        double mean = sum / (SUMMIT_GRID * SUMMIT_GRID);

        List<Chart.Landmark> out = new ArrayList<>();
        List<double[]> found = new ArrayList<>();
        for (int iy = 1; iy < SUMMIT_GRID - 1; iy++) {
            for (int ix = 1; ix < SUMMIT_GRID - 1; ix++) {
                double here = h[iy][ix];
                if (here < mean + SUMMIT_MARGIN) continue;
                boolean top = true;
                for (int dy = -1; dy <= 1 && top; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        if ((dx != 0 || dy != 0) && h[iy + dy][ix + dx] >= here) {
                            top = false;
                            break;
                        }
                    }
                }
                if (!top) continue;
                found.add(new double[]{centreX - radius + ix * step,
                        centreY - radius + iy * step, here});
            }
        }
        // Tallest first, so a map of a range shows its peaks rather than the
        // first few shoulders the scan happened to walk over.
        found.sort((a, b) -> Double.compare(b[2], a[2]));
        for (double[] peak : found) {
            if (out.size() >= PER_KIND) break;
            out.add(new Chart.Landmark(Chart.Kind.SUMMIT, peak[0], peak[1],
                    Math.round(peak[2]) + " m"));
        }
        return out;
    }

    private static boolean inside(double x, double y, double centreX, double centreY,
                                  double radius) {
        return Math.abs(x - centreX) <= radius && Math.abs(y - centreY) <= radius;
    }

    private static List<double[]> points(List<TreeInstance> trees) {
        List<double[]> out = new ArrayList<>();
        for (TreeInstance tree : trees) out.add(new double[]{tree.x(), tree.y()});
        return out;
    }

    /**
     * Group points that are near each other, returning
     * {@code x, y, count} per group.
     *
     * <p>Single-pass and greedy: each point joins the first group whose centre
     * it is within {@code within} of, and starts a new one otherwise. That is
     * not the best clustering anybody has ever written and it does not need to
     * be — the input is a few dozen boxes on a hillside, and the question being
     * answered is "how many icons should this be", where the difference between
     * a good answer and a perfect one is invisible on a map.
     */
    private static List<double[]> cluster(List<double[]> points, double within) {
        List<double[]> groups = new ArrayList<>();
        // Sums rather than running centres, so a group's centre is the mean of
        // its members and not a walk that drifts toward whatever came last.
        Map<Integer, double[]> sums = new LinkedHashMap<>();
        for (double[] point : points) {
            int found = -1;
            for (int i = 0; i < groups.size(); i++) {
                double[] group = groups.get(i);
                if (Math.hypot(group[0] - point[0], group[1] - point[1]) <= within) {
                    found = i;
                    break;
                }
            }
            if (found < 0) {
                groups.add(new double[]{point[0], point[1], 1});
                sums.put(groups.size() - 1, new double[]{point[0], point[1]});
                continue;
            }
            double[] sum = sums.get(found);
            sum[0] += point[0];
            sum[1] += point[1];
            double[] group = groups.get(found);
            group[2]++;
            group[0] = sum[0] / group[2];
            group[1] = sum[1] / group[2];
        }
        return groups;
    }
}
