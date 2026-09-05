package com.larsons.engine.watch.light;

import com.larsons.engine.watch.WatchJson;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A light somebody put down, and how much of it is left.
 *
 * <p><b>Mutable, like {@link com.larsons.engine.watch.Lure} and for its
 * reason.</b> Everything else a party leaves in this world is a record — a
 * house, a planted tree, a pinned map — because nothing about it changes
 * once it is there. A fire changes continuously: it burns down, it goes out, it
 * is fed and it comes back. Making that a new object every tick would be a new
 * object every tick.
 *
 * <p>Fuel is measured in <b>real hours</b>, not ticks, for the same reason
 * growth is (see {@link com.larsons.engine.watch.WatchClock#realHoursBetween}):
 * a fire lit before bed should be out in the morning whether or not anybody was
 * logged in, and a lantern's nine hours should mean the nine hours the wall
 * clock says.
 */
public final class PlacedLight {

    private final long id;
    private final LightKind kind;
    private final double x, y, z;
    private final double yaw;
    private final String placedBy;
    private final long placedAtMillis;

    /** Hours of burning left in it; ignored entirely by an eternal kind. */
    private double fuelHours;

    private boolean lit;

    public PlacedLight(long id, LightKind kind, double x, double y, double z, double yaw,
                       String placedBy, long placedAtMillis) {
        this(id, kind, x, y, z, yaw, placedBy, placedAtMillis,
                kind == null ? 0 : kind.burnHours(), true);
    }

    public PlacedLight(long id, LightKind kind, double x, double y, double z, double yaw,
                       String placedBy, long placedAtMillis, double fuelHours,
                       boolean lit) {
        this.id = id;
        this.kind = kind == null ? LightKind.CAMPFIRE : kind;
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.placedBy = placedBy;
        this.placedAtMillis = placedAtMillis;
        this.fuelHours = Math.max(0, fuelHours);
        this.lit = lit && (this.kind.eternal() || this.fuelHours > 0);
    }

    public long id() { return id; }

    public LightKind kind() { return kind; }

    public double x() { return x; }

    public double y() { return y; }

    /** Where its foot stands — the ground, or the shelf it was left on. */
    public double z() { return z; }

    /** Which way it is turned, in radians; the fire does not care and the lantern does. */
    public double yaw() { return yaw; }

    /** Where the flame itself is, which is what the light comes from. */
    public double flameZ() { return z + kind.flameHeight(); }

    /** Who put it there. */
    public String placedBy() { return placedBy; }

    public long placedAtMillis() { return placedAtMillis; }

    /** Whether it is actually burning. */
    public boolean lit() { return lit; }

    /** Hours of burning left; {@link Double#POSITIVE_INFINITY} for an eternal one. */
    public double fuelHours() {
        return kind.eternal() ? Double.POSITIVE_INFINITY : fuelHours;
    }

    /**
     * How much of a full charge is left, {@code 0}–{@code 1}.
     *
     * <p>What the flame is scaled by as well as what the HUD draws: a fire on
     * its last quarter hour visibly sinks, which is the warning a party gets
     * before the dark comes back. See {@link #burnBrightness}.
     */
    public double fuelLeft() {
        if (kind.eternal()) return 1;
        return kind.burnHours() <= 0 ? 0 : Math.min(1, fuelHours / kind.burnHours());
    }

    /**
     * What it is burning at, {@code 0}–{@code 1} — <b>full until it is nearly
     * spent, then falling.</b>
     *
     * <p>A linear fade over four hours would mean a fire is half as bright an
     * hour after it is lit, which is not what a fire does and is not what
     * anybody wants from one. It burns at full until the last fifth and dies
     * over that.
     */
    public double burnBrightness() {
        if (!lit) return 0;
        double left = fuelLeft();
        return left >= LOW ? 1 : Math.max(0.18, left / LOW);
    }

    /** Where the burn starts visibly dropping, as a share of a full charge. */
    private static final double LOW = 0.2;

    /** Whether it is nearly out — what the prompt warns about. */
    public boolean guttering() { return lit && !kind.eternal() && fuelLeft() < LOW; }

    /**
     * Let real time pass.
     *
     * @return whether it went out on this step, so a caller can say so once
     */
    public boolean burn(double hours) {
        if (!lit || kind.eternal() || hours <= 0) return false;
        fuelHours = Math.max(0, fuelHours - hours);
        if (fuelHours > 0) return false;
        lit = false;
        return true;
    }

    /**
     * Feed it, and light it if it had gone out.
     *
     * <p>Capped at a full charge rather than accumulating: a fire somebody has
     * emptied a satchel of branches into is a fire, not a bonfire that burns
     * for a week. The cap is what keeps tending it a habit rather than a
     * one-off.
     *
     * @return whether the fuel was worth anything
     */
    public boolean feed(int units) {
        if (kind.eternal() || kind.fuel() == null || units <= 0) return false;
        if (fuelHours >= kind.burnHours() - 1e-6) return false;
        fuelHours = Math.min(kind.burnHours(), fuelHours + kind.fuelHours() * units);
        lit = true;
        return true;
    }

    /**
     * Light one that has fuel in it but is not burning.
     *
     * @return whether anything changed
     */
    public boolean relight() {
        if (lit || (!kind.eternal() && fuelHours <= 0)) return false;
        lit = true;
        return true;
    }

    /** Put it out without spending anything — what taking one back does first. */
    public void douse() { lit = false; }

    /** Whether nothing is left of it at all. See {@link LightKind#leavesEmbers}. */
    public boolean spent() {
        return !kind.eternal() && fuelHours <= 0 && !kind.leavesEmbers();
    }

    /** What the HUD says about it. */
    public String describe() {
        if (kind.eternal()) return kind.displayName();
        if (!lit) return kind.displayName() + " — out";
        if (guttering()) return kind.displayName() + " — burning low";
        return kind.displayName() + " — " + hoursLabel();
    }

    /** How long it has left, as a player reads it. */
    public String hoursLabel() {
        if (kind.eternal()) return "steady";
        int minutes = (int) Math.round(fuelHours * 60);
        if (minutes >= 90) return (Math.round(fuelHours * 10) / 10.0) + " h left";
        return Math.max(1, minutes) + " min left";
    }

    /** How far away one of these is worth drawing a highlight round. */
    public double reach() { return kind == LightKind.CAMPFIRE ? 0.9 : 0.35; }

    // --- persistence --------------------------------------------------------------

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("k", kind.key());
        m.put("x", x);
        m.put("y", y);
        m.put("z", z);
        if (yaw != 0) m.put("yaw", yaw);
        if (!kind.eternal()) m.put("f", Math.round(fuelHours * 1000) / 1000.0);
        m.put("on", lit);
        m.put("t", placedAtMillis);
        if (placedBy != null) m.put("by", placedBy);
        return m;
    }

    public static PlacedLight fromMap(Map<String, Object> m) {
        if (m == null) return null;
        LightKind kind = LightKind.of(WatchJson.str(m, "k", ""));
        if (kind == null) return null;
        return new PlacedLight(WatchJson.big(m, "id", 0), kind,
                WatchJson.num(m, "x", 0), WatchJson.num(m, "y", 0),
                WatchJson.num(m, "z", 0), WatchJson.num(m, "yaw", 0),
                WatchJson.str(m, "by", null), WatchJson.big(m, "t", 0),
                WatchJson.num(m, "f", kind.burnHours()),
                WatchJson.bool(m, "on", true));
    }

    @Override public String toString() {
        return describe() + " at (" + Math.round(x) + ", " + Math.round(y) + ")";
    }
}
