package com.larsons.engine.watch;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One person in the party.
 *
 * <h2>Stillness is a stat</h2>
 *
 * <p>The only number here that is not bookkeeping is {@link #stillness()}, and
 * it is the closest thing this game has to a character sheet. It rises while a
 * player holds position and collapses the moment they move; every animal reads
 * it through {@code WatchGame.disturbanceAt}, which scales their distance by
 * it. Stand still for ten seconds and the wood forgets you are there; take
 * three steps and it remembers.
 *
 * <p>That is deliberately the <em>only</em> way to get close to a wary species.
 * There is no camouflage to craft, no skill to level, and no upgrade that
 * widens the radius. There is standing still, and there is food.
 */
public final class WatchPlayer {

    /** How long it takes, in seconds, to go from moving to fully settled. */
    public static final double SETTLE_SECONDS = 9;

    /** How fast stillness is lost while moving, relative to how fast it is gained. */
    private static final double UNSETTLE_RATE = 5;

    /** Metres per second at a walk. */
    public static final double WALK_SPEED = 4.4;

    /** …at a jog, which costs every scrap of stillness. */
    public static final double RUN_SPEED = 8.0;

    /** …and crouched, which barely costs any. */
    public static final double CROUCH_SPEED = 1.5;

    private final int id;
    private final String name;

    /** How long a lungful lasts, in seconds. */
    public static final double BREATH_SECONDS = 42;

    /** How fast a breath comes back at the surface, relative to how fast it goes. */
    private static final double BREATH_RECOVERY = 4;

    private double x, y, z;
    private double yaw, pitch;
    private double stillness = 1;
    private boolean crouching;
    private double lastSpeed;

    /** How much air is left, {@code 0}–{@code 1}. */
    private double breath = 1;

    /** Whether the player's head is under the water this tick. */
    private boolean submerged;

    /** The boat being rowed, or {@code 0}. */
    private long boatId;

    private final Satchel satchel = new Satchel();
    private final Fishing rod;

    public WatchPlayer(int id, String name, double x, double y, double z) {
        this.id = id;
        this.name = name;
        this.x = x;
        this.y = y;
        this.z = z;
        this.rod = new Fishing(name.hashCode() * 31L + id);
    }

    public int id() { return id; }

    public String name() { return name; }

    public double x() { return x; }

    public double y() { return y; }

    /** The ground the player is standing on, in metres. */
    public double z() { return z; }

    /** Eye height above that. */
    public double eyeZ() { return z + (crouching ? 1.10 : 1.68); }

    public double yaw() { return yaw; }

    public double pitch() { return pitch; }

    public boolean crouching() { return crouching; }

    /** How settled the player is, {@code 0} (crashing about) – {@code 1} (still). */
    public double stillness() { return stillness; }

    /**
     * How much air is left, {@code 1} full to {@code 0} out.
     *
     * <p>The only resource in this game, and it is deliberately not a health
     * bar: running out surfaces you, it does not kill you. The sea floor is
     * somewhere to look at things, and a game about looking at things should
     * not punish you for looking too long — it should just make you come up for
     * air, which is what a person diving on a reef does anyway.
     */
    public double breath() { return breath; }

    /** Whether their head is under water. */
    public boolean submerged() { return submerged; }

    /** Whether the breath has run out and they are being floated up. */
    public boolean outOfBreath() { return breath <= 0; }

    /** Which boat they are in, or {@code 0}. */
    public long boatId() { return boatId; }

    /** Whether they are rowing rather than walking. */
    public boolean inBoat() { return boatId != 0; }

    /** Take the oars of a boat. */
    public void boardBoat(long id) { this.boatId = id; }

    /** Step out of whatever they were in. */
    public void leaveBoat() { this.boatId = 0; }

    /** Say whether the head is under, and spend or recover the breath. */
    public void setSubmerged(boolean under, double dt) {
        this.submerged = under;
        if (dt <= 0) return;
        if (under) {
            breath = Math.max(0, breath - dt / BREATH_SECONDS);
        } else {
            breath = Math.min(1, breath + dt * BREATH_RECOVERY / BREATH_SECONDS);
        }
    }

    /** What they are carrying. */
    public Satchel satchel() { return satchel; }

    /** Their rod, and whatever it is doing. */
    public Fishing rod() { return rod; }

    /** The speed of their last move, in metres per second. */
    public double speed() { return lastSpeed; }

    /**
     * Move to a position sent by the client, and work out what that did to
     * their stillness.
     *
     * <p>The server derives the speed from the positions rather than trusting a
     * "am I running" flag, so a client cannot claim to be creeping while
     * covering ten metres a second. It is also the only anti-cheat this game
     * needs: the worst a liar can achieve is being able to approach birds,
     * which is the whole game, so it is worth being right about.
     */
    public void moveTo(double x, double y, double z, double yaw, double pitch,
                       boolean crouching, double dt) {
        double dx = x - this.x, dy = y - this.y, dz = z - this.z;
        double moved = Math.sqrt(dx * dx + dy * dy + dz * dz);
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;
        this.crouching = crouching;
        if (dt <= 0) return;
        lastSpeed = moved / dt;
        updateStillness(dt);
    }

    /** Advance stillness for a tick in which no new position arrived. */
    public void tick(double dt) {
        lastSpeed *= Math.max(0, 1 - dt * 4);
        updateStillness(dt);
    }

    private void updateStillness(double dt) {
        // Crouching costs a fifth of what walking does, which is what makes
        // crouching worth the speed it costs.
        double effort = lastSpeed / (crouching ? CROUCH_SPEED : WALK_SPEED);
        if (effort < 0.06) {
            stillness = Math.min(1, stillness + dt / SETTLE_SECONDS);
        } else {
            double cost = effort * (crouching ? 0.2 : 1) * UNSETTLE_RATE / SETTLE_SECONDS;
            stillness = Math.max(0, stillness - dt * cost);
        }
    }

    /**
     * How far away this player <em>seems</em> to something at a point.
     *
     * <p>Not the plain distance: a settled player is treated as much further
     * away than they are, and one running is treated as much closer. This is
     * the number the whole approach mechanic is expressed in, and it lives here
     * rather than in the animal because it is a property of the player.
     */
    public double apparentDistanceTo(double px, double py) {
        double dx = px - x, dy = py - y;
        double distance = Math.sqrt(dx * dx + dy * dy);
        // 0.55× at a dead run, 3.2× standing still and crouched.
        double factor = 0.55 + stillness * (crouching ? 2.65 : 1.75);
        return distance * factor;
    }

    // --- persistence ----------------------------------------------------------------

    /** The fields that go in a snapshot, for everybody else to draw. */
    public Map<String, Object> toSnapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("n", name);
        m.put("x", x);
        m.put("y", y);
        m.put("z", z);
        m.put("yaw", yaw);
        m.put("p", pitch);
        m.put("st", stillness);
        if (crouching) m.put("c", true);
        if (submerged) m.put("uw", true);
        if (breath < 1) m.put("air", breath);
        if (boatId != 0) m.put("boat", boatId);
        return m;
    }

    /** The fields that go in a save — everything of this player's own. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = toSnapshot();
        m.put("bag", satchel.toMap());
        return m;
    }

    /** Restore from a save. */
    public void load(Map<String, Object> m) {
        x = WatchJson.num(m, "x", x);
        y = WatchJson.num(m, "y", y);
        z = WatchJson.num(m, "z", z);
        yaw = WatchJson.num(m, "yaw", yaw);
        pitch = WatchJson.num(m, "p", pitch);
        crouching = WatchJson.bool(m, "c", false);
        submerged = WatchJson.bool(m, "uw", false);
        breath = WatchJson.num(m, "air", 1);
        boatId = WatchJson.big(m, "boat", 0);
        satchel.load(WatchJson.map(m, "bag"));
    }

    @Override public String toString() {
        return name + " at (" + Math.round(x) + ", " + Math.round(y) + ")";
    }
}
