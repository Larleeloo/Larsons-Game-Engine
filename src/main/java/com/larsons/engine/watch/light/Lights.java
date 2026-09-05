package com.larsons.engine.watch.light;

import com.larsons.engine.watch.WatchJson;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every fire and every lantern the party has left standing.
 *
 * <p><b>A flat map, for {@link com.larsons.engine.watch.home.Homestead}'s
 * reason.</b> A world holds a handful of these — a camp has a fire and two or
 * three lanterns, and a party that has walked a long way has left a dozen more
 * behind them as waymarks. A spatial index for that is a structure to keep
 * correct in exchange for microseconds nobody was going to notice; what matters
 * is that one is small, cheap to send, and can be found by walking the lot.
 *
 * <p><b>The host owns all of it.</b> Placing, feeding and taking one back are
 * decisions, and decisions are the server's — see
 * {@link com.larsons.engine.watch.WatchGame}. This class does what it is told
 * and keeps the answer; it never adjudicates, exactly as {@code Homestead}
 * never consults {@code blocked} from {@code place}.
 */
public final class Lights {

    /**
     * How close together two of these may stand, in metres.
     *
     * <p>Small, because the point is not to ration light — it is to stop a
     * player who has held the key down putting nine campfires inside each
     * other, which is one bright white blob and nine lots of fuel gone. Two
     * lanterns a metre apart are a perfectly reasonable thing to want.
     */
    public static final double MIN_GAP = 0.8;

    private final Map<Long, PlacedLight> lights = new LinkedHashMap<>();
    private long nextId = 1;

    /** Put a full one down, and light it. */
    public PlacedLight place(LightKind kind, double x, double y, double z, double yaw,
                             String placedBy, long atMillis) {
        return place(kind, x, y, z, yaw, placedBy, atMillis,
                kind == null ? 0 : kind.burnHours(), true);
    }

    /**
     * Put one down with the oil it already had.
     *
     * <p>What setting down the lantern in your hand goes through, and the
     * reason the plain {@link #place} is not enough: a lamp that refilled
     * itself every time it touched the ground would make the fuel economy a
     * formality. The hours follow the object.
     */
    public PlacedLight place(LightKind kind, double x, double y, double z, double yaw,
                             String placedBy, long atMillis, double fuelHours,
                             boolean lit) {
        PlacedLight light = new PlacedLight(nextId++, kind, x, y, z, yaw, placedBy,
                atMillis, fuelHours, lit);
        lights.put(light.id(), light);
        return light;
    }

    /** Adopt one that arrived from a host or a save, keeping its id. */
    public void adopt(PlacedLight light) {
        if (light == null) return;
        lights.put(light.id(), light);
        nextId = Math.max(nextId, light.id() + 1);
    }

    /** Take one away. */
    public PlacedLight remove(long id) { return lights.remove(id); }

    public PlacedLight byId(long id) { return lights.get(id); }

    /** Everything standing, in the order it was put down. */
    public List<PlacedLight> all() { return List.copyOf(lights.values()); }

    public int size() { return lights.size(); }

    public boolean isEmpty() { return lights.isEmpty(); }

    /** How many of them are actually burning — what the debug readout counts. */
    public int burning() {
        int n = 0;
        for (PlacedLight light : lights.values()) {
            if (light.lit()) n++;
        }
        return n;
    }

    /** Everything within a radius of a point — what the renderer asks for. */
    public List<PlacedLight> near(double x, double y, double radius) {
        List<PlacedLight> out = new ArrayList<>();
        double r2 = radius * radius;
        for (PlacedLight light : lights.values()) {
            double dx = light.x() - x, dy = light.y() - y;
            if (dx * dx + dy * dy <= r2) out.add(light);
        }
        return out;
    }

    /** The nearest one within a radius, or {@code null} — what a reaching hand finds. */
    public PlacedLight nearest(double x, double y, double radius) {
        PlacedLight best = null;
        double bestDistance = radius * radius;
        for (PlacedLight light : lights.values()) {
            double dx = light.x() - x, dy = light.y() - y;
            double d = dx * dx + dy * dy;
            if (d <= bestDistance) {
                bestDistance = d;
                best = light;
            }
        }
        return best;
    }

    /** Whether something is already standing where one would go. */
    public boolean blocked(double x, double y) {
        for (PlacedLight light : lights.values()) {
            double dx = light.x() - x, dy = light.y() - y;
            if (dx * dx + dy * dy < MIN_GAP * MIN_GAP) return true;
        }
        return false;
    }

    /**
     * Let real hours pass over every one of them.
     *
     * <p>Anything that has burnt out and leaves nothing behind — a torch — is
     * dropped here rather than left as an invisible entry nobody can see or
     * pick up. See {@link LightKind#leavesEmbers}.
     *
     * @return the ones that went out on this step, for the host to mention
     */
    public List<PlacedLight> burn(double hours) {
        List<PlacedLight> died = new ArrayList<>();
        if (hours <= 0) return died;
        for (PlacedLight light : List.copyOf(lights.values())) {
            if (light.burn(hours)) died.add(light);
        }
        lights.values().removeIf(PlacedLight::spent);
        return died;
    }

    // --- persistence ----------------------------------------------------------------

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("next", nextId);
        m.put("lights", toRows());
        return m;
    }

    public void load(Map<String, Object> m) {
        lights.clear();
        nextId = Math.max(1, WatchJson.big(m, "next", 1));
        loadRows(WatchJson.objects(m, "lights"));
    }

    /** Every light as a row — what a snapshot sends. */
    public List<Object> toRows() {
        List<Object> rows = new ArrayList<>();
        for (PlacedLight light : lights.values()) rows.add(light.toMap());
        return rows;
    }

    /** Replace everything from a host's list. */
    public void loadRows(List<Map<String, Object>> rows) {
        lights.clear();
        for (Map<String, Object> row : rows) adopt(PlacedLight.fromMap(row));
    }
}
