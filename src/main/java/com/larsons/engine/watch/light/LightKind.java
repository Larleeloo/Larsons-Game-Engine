package com.larsons.engine.watch.light;

import com.larsons.engine.watch.Forage;
import com.larsons.engine.watch.Satchel;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The four things in this game that burn — <b>a catalogue, not four classes.</b>
 *
 * <p>A light here is entirely described by numbers: what colour it burns, how
 * far it reaches, how much it wavers, how long it lasts and what feeds it.
 * Nothing about a campfire is <em>behaviour</em> that a lantern does not also
 * have, so the difference between them is a row rather than a subclass — the
 * same bargain {@link com.larsons.engine.watch.world.WatchBiome} and
 * {@link com.larsons.engine.watch.build.BuildPiece} strike, and for the same
 * reason: adding a fifth light should be adding a row.
 *
 * <p><b>The colours are the point of having four.</b> A camp lit by one warm
 * fire and one cold spore lantern reads as two different pools of light on the
 * same ground, and at a hundred metres in the dark you can tell which of your
 * party is carrying which. That only works because the numbers below are
 * genuinely different rather than four shades of orange:
 *
 * <table border="1">
 *   <caption>What each one is for</caption>
 *   <tr><th></th><th>Colour</th><th>Reach</th><th>Burns</th><th>Fed with</th></tr>
 *   <tr><td>Campfire</td><td>deep orange</td><td>12 m</td><td>4 h</td>
 *       <td>branches</td></tr>
 *   <tr><td>Lantern</td><td>warm yellow</td><td>9 m</td><td>9 h</td>
 *       <td>sap</td></tr>
 *   <tr><td>Torch</td><td>orange</td><td>6.5 m</td><td>1.2 h</td>
 *       <td>nothing — it burns out and is gone</td></tr>
 *   <tr><td>Spore lantern</td><td>cold green</td><td>7.5 m</td><td>forever</td>
 *       <td>nothing — spores do not go out</td></tr>
 * </table>
 *
 * <p><b>Three of the four can be carried and all four can be put down</b>, and
 * that is deliberate: the fire is the one you build to sit at, and the other
 * three are the ones you take with you and then leave behind to find your way
 * back by. See {@link Lights} for where they end up and
 * {@link LightField} for what they do to the picture.
 */
public enum LightKind {

    /**
     * A ring of stones and an armful of branches.
     *
     * <p>The brightest thing a party can make and the only one that is
     * <em>built</em> rather than carried: it costs materials on the spot, it
     * stays where it is put, and it goes out in four hours unless somebody
     * feeds it. That last part is the whole design of it — a camp is a place
     * people keep coming back to, and a fire nobody has to tend is a lamp post.
     */
    CAMPFIRE("campfire", "Campfire", null, 0xFF9A3C, 12.0, 1.15, 0.22, 4.0,
            "fallen_branch", 1.5, false, 0.45,
            "Stones and branches. The brightest thing you can make, and it "
                    + "wants feeding.",
            "fallen_branch", 3, "stone", 2),

    /**
     * An oil lantern: carried lit, or set down to mark somewhere.
     *
     * <p>The workhorse. Dimmer than the fire and steadier than the torch, and
     * the only one whose nine hours outlast an evening — which makes it the one
     * you actually walk with.
     */
    LANTERN("lantern", "Lantern", "lantern", 0xFFC46A, 9.0, 0.95, 0.06, 9.0,
            "sap", 3.0, true, 0.35,
            "Resin oil behind glass. Carry it lit, or set it down as a mark."),

    /**
     * A branch, some sap and a strip of bark.
     *
     * <p>Cheap, bright, brief, and <b>gone when it is out</b> — the one light
     * here that is spent rather than tended. What it is for is the walk home:
     * a torch is what you light when the sun goes down further from camp than
     * you meant to be.
     */
    TORCH("torch", "Torch", "torch", 0xFF8A38, 6.5, 0.85, 0.30, 1.2,
            null, 0, true, 0.70,
            "Bark, sap and a branch. Burns bright, burns out, and is gone."),

    /**
     * Glow spores in a jar — <b>the cold one, and the one that never goes
     * out.</b>
     *
     * <p>It is here because the world has a mushroom hollow in it and because
     * every other light in this list is a shade of fire. Green light on green
     * leaves does something to a wood at two in the morning that no amount of
     * orange does, and a light with no fuel at all is the one you hang over a
     * feeder and forget about.
     */
    SPORE_LANTERN("spore_lantern", "Spore Lantern", "spore_lantern", 0x7CF0C0, 7.5,
            0.70, 0.0, 0.0, null, 0, true, 0.40,
            "Glow spores in a jar. Cold, green, and it never goes out.");

    private final String key;
    private final String name;
    private final String item;
    private final int rgb;
    private final double radius;
    private final double intensity;
    private final double flicker;
    private final double burnHours;
    private final String fuel;
    private final double fuelHours;
    private final boolean carryable;
    private final double flameHeight;
    private final String note;
    private final Map<String, Integer> cost;

    LightKind(String key, String name, String item, int rgb, double radius,
              double intensity, double flicker, double burnHours, String fuel,
              double fuelHours, boolean carryable, double flameHeight, String note,
              Object... pairs) {
        this.key = key;
        this.name = name;
        this.item = item;
        this.rgb = rgb;
        this.radius = radius;
        this.intensity = intensity;
        this.flicker = flicker;
        this.burnHours = burnHours;
        this.fuel = fuel;
        this.fuelHours = fuelHours;
        this.carryable = carryable;
        this.flameHeight = flameHeight;
        this.note = note;
        Map<String, Integer> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put((String) pairs[i], (Integer) pairs[i + 1]);
        }
        this.cost = Map.copyOf(map);
    }

    /** Stable identifier — what a save and the wire carry. */
    public String key() { return key; }

    /** What a player sees it called. */
    public String displayName() { return name; }

    /**
     * The satchel key one of these is, or {@code null} for the fire.
     *
     * <p>A campfire is not a thing you carry; it is a thing you make out of
     * things you carry, which is why this is nullable rather than a
     * {@code "campfire"} item nobody could ever hold. {@link #cost()} is what
     * it takes instead.
     */
    public String item() { return item; }

    /** What it burns, {@code 0xRRGGBB}. */
    public int rgb() { return rgb; }

    /** How far its light reaches before it reaches nothing, in metres. */
    public double radius() { return radius; }

    /** How bright it is at the flame, on daylight's own scale. */
    public double intensity() { return intensity; }

    /** How much it wavers, {@code 0} steady to {@code 1} guttering. */
    public double flicker() { return flicker; }

    /** How many real hours a full one burns, or {@code 0} for never out. */
    public double burnHours() { return burnHours; }

    /** Whether it ever needs anything at all. */
    public boolean eternal() { return burnHours <= 0; }

    /** What feeds it, or {@code null} for the two that cannot be fed. */
    public String fuel() { return fuel; }

    /** How many hours one unit of fuel adds. */
    public double fuelHours() { return fuelHours; }

    /** Whether one can be carried lit. */
    public boolean carryable() { return carryable; }

    /**
     * How high above its foot the flame sits, in metres.
     *
     * <p>Where the light is <em>emitted from</em>, which is not where the
     * object is: a fire lights the ground from just above it and a lantern
     * lights it from the top of its post. Getting this wrong is the difference
     * between a pool of light under a lamp and a lamp standing in the dark.
     */
    public double flameHeight() { return flameHeight; }

    /** One line for the satchel and the prompt. */
    public String note() { return note; }

    /** What building one costs, as forage key → count; empty for the carried ones. */
    public Map<String, Integer> cost() { return cost; }

    /**
     * Whether a spent one is left standing.
     *
     * <p>A fire that has burnt down is a cold ring of stones you can relight;
     * a lantern out of oil is a lantern waiting for oil. A torch is neither —
     * it is ash — so it is the one thing here that removes itself. See
     * {@link Lights#burn}.
     */
    public boolean leavesEmbers() { return this != TORCH; }

    /** The cost spelled out: "3 × Fallen Branch, 2 × Stone". */
    public String costLine() {
        StringBuilder sb = new StringBuilder();
        cost.forEach((key, n) -> {
            if (sb.length() > 0) sb.append(", ");
            sb.append(n).append(" × ").append(Forage.nameOf(key));
        });
        return sb.toString();
    }

    /** Whether a satchel holds the makings of one. */
    public boolean affordable(Satchel satchel) {
        if (satchel == null) return false;
        if (item != null) return satchel.has(item);
        for (Map.Entry<String, Integer> need : cost.entrySet()) {
            if (!satchel.has(need.getKey(), need.getValue())) return false;
        }
        return true;
    }

    /**
     * Take what one costs out of a satchel.
     *
     * <p>All or nothing, like every other cost in this game: half a campfire's
     * worth of branches spent and no campfire is the kind of thing a player
     * remembers.
     */
    public boolean pay(Satchel satchel) {
        if (!affordable(satchel)) return false;
        if (item != null) return satchel.take(item, 1);
        cost.forEach(satchel::take);
        return true;
    }

    /** Every kind, in the order they are declared. */
    public static List<LightKind> all() { return List.of(values()); }

    /** The kind a saved key means, or {@code null}. */
    public static LightKind of(String key) {
        for (LightKind kind : values()) {
            if (kind.key.equals(key)) return kind;
        }
        return null;
    }

    /**
     * The kind a satchel item is, or {@code null} for anything that does not
     * burn.
     *
     * <p>What turns "I am holding a torch" into a light without anybody having
     * to keep a second table in step with this one.
     */
    public static LightKind ofItem(String itemKey) {
        if (itemKey == null) return null;
        for (LightKind kind : values()) {
            if (itemKey.equals(kind.item)) return kind;
        }
        return null;
    }
}
