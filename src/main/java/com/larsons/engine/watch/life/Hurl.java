package com.larsons.engine.watch.life;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A shard of bone the wendigo has thrown at somebody — or a jet of water out of
 * a water gun, which is the same problem with a different consequence.
 *
 * <h2>Why there is a projectile in a game about watching birds</h2>
 *
 * <p>The three mutants now hold a sprinting player's pace — see
 * {@link Mutants} — and two of them do it flat out. That leaves the wendigo,
 * which is the slowest of the three on purpose, needing a reason to be
 * frightening at a distance a sprint can open up. A creature that cannot catch
 * you and cannot hurt you is scenery.
 *
 * <p>So it throws. And the answer to it is different in kind from the answer to
 * the other two: against a werewolf you look for ground, against a mirewraith
 * you look for the twenty-two metres you have left, and against a wendigo you
 * look for something to put <em>between</em> you — which is a third verb rather
 * than a third speed.
 *
 * <h2>Why it is not a general projectile system</h2>
 *
 * <p>The engine has one ({@code world/ProjectileRegistry}) and this is
 * deliberately not it. That system belongs to the block world: it knows about
 * block collision, damage types, owners, item drops and a registry of kinds.
 * There is exactly one thing in the Field Guide that flies, thrown by one
 * creature at one target, and it is a straight line with a lifetime. Reaching
 * into another game's system for that would drag a registry and a collision
 * model into a package that has neither.
 *
 * <h2>What it is</h2>
 *
 * <p>Position, velocity, a lifetime and who is meant to catch it. It travels in
 * a straight line under a little gravity, it is checked against its target every
 * tick, and it dies on a hit, on the ground, or on running out of time.
 * <b>Server-side only</b>, like {@link Animal}: clients are handed positions in
 * snapshots and never step one, which is what keeps a party's view of an
 * incoming shard identical rather than merely similar.
 *
 * <p>It is not saved. A shard in the air when a walk closes is weather, exactly
 * as the animals are.
 *
 * <h2>The second thing that flies</h2>
 *
 * <p>A game of tag ({@code com.larsons.engine.watch.Tag}) tags at range, with a
 * jet of water, and that is this class again rather than a second copy of it:
 * a thing let go at a speed, in a direction, that arcs, that is checked against
 * people every tick and dies on the ground. What differs is only what happens on
 * a hit, and that is the world's business rather than the projectile's — see
 * {@code WatchGame.flyHurls}, which reads {@link #species()} and either wounds
 * somebody or makes them it.
 *
 * <p>The one thing the jet needs that a shard does not is {@link #owner()}: it
 * leaves from half a metre in front of the person who fired it, which is well
 * inside {@link #HIT_RADIUS} of their own chest, so without knowing whose it is
 * every shot would tag the shooter on the tick they fired it.
 */
public final class Hurl {

    /**
     * How much a shard drops, in metres per second per second.
     *
     * <p>A quarter of real gravity. Enough that a long throw visibly arcs and
     * that a player who breaks the line of sight low is missed; not so much that
     * aiming becomes a mortar problem the wendigo has no way to solve.
     */
    public static final double GRAVITY = 2.6;

    /** How long one lives before it gives up, in seconds. */
    public static final double LIFETIME = 4.0;

    /**
     * How near the target's centre counts as a hit, in metres.
     *
     * <p>Generous, and it is the number that decides whether the ranged attack
     * is a threat or a formality. A person is about half a metre across; 1.1 m
     * means a shard that passes close enough to be frightening also connects,
     * which is the honest trade for a creature that cannot lead its target.
     */
    public static final double HIT_RADIUS = 1.1;

    /** How far above the ground the target's middle is, in metres. */
    public static final double CHEST_HEIGHT = 0.95;

    private final long id;
    private final String species;
    private final String target;

    /**
     * The walker who fired it, or {@code null} when something threw it.
     *
     * <p>Set by {@link #squirted} and by nothing else, and read for exactly one
     * purpose: to be skipped when a flying thing is checked against the people
     * it might have hit.
     */
    private String owner;

    private double x, y, z;

    /**
     * Where it was at the start of this tick.
     *
     * <p><b>The whole of why a fast projectile hits anything.</b> A shard
     * travels {@link Mutants.Ranged#speed()} metres a second and the world steps
     * twenty times a second, so at twenty-four metres a second it moves 1.2 m
     * between two samples — further than {@link #HIT_RADIUS}. Testing the
     * sampled point against the target therefore steps <em>through</em> people:
     * measured against a stationary player it connected at eight, twelve,
     * sixteen, twenty, thirty and forty-four metres and passed cleanly through
     * at twenty-four, twenty-eight and forty, which reads in play as an attack
     * that works except at distances nobody can name.
     *
     * <p>So {@link #hits} measures the <em>segment</em> it swept this tick
     * rather than the point it landed on, which is exact for any speed and makes
     * the answer independent of the tick rate.
     */
    private double lastX, lastY, lastZ;

    private double vx, vy, vz;
    private double damage;
    private double age;
    private boolean spent;

    /** The direction it is travelling, for drawing it pointed the right way. */
    private double yaw, pitch;

    public Hurl(long id, String species, String target, double x, double y, double z,
                double vx, double vy, double vz, double damage) {
        this.id = id;
        this.species = species;
        this.target = target;
        this.x = x;
        this.y = y;
        this.z = z;
        this.vx = vx;
        this.vy = vy;
        this.vz = vz;
        this.lastX = x;
        this.lastY = y;
        this.lastZ = z;
        this.damage = damage;
        aim();
    }

    /**
     * Throw one from an animal at a point, leading nothing.
     *
     * <p><b>It aims where the player is, not where they are going.</b> A shard
     * that led its target would be unavoidable at these speeds, and a wendigo
     * that never missed would make cover pointless — the whole answer to this
     * creature is to not be standing in the line any more by the time the shard
     * arrives. Missing is the mechanic.
     */
    public static Hurl thrown(long id, Animal from, double atX, double atY, double atZ,
                              Mutants.Ranged ranged) {
        // Out of the chest rather than the feet, and a little forward, so it
        // does not appear inside the thrower's own ribs.
        double height = from.def().bodyLength() * 0.62;
        double ox = from.x() + Math.sin(from.yaw()) * 0.5;
        double oy = from.y() - Math.cos(from.yaw()) * 0.5;
        double oz = from.z() + height;

        double dx = atX - ox, dy = atY - oy, dz = atZ + CHEST_HEIGHT - oz;
        double flat = Math.hypot(dx, dy);
        double speed = Math.max(1e-6, ranged.speed());

        // <b>Solved rather than approximated.</b> The first version lifted the
        // aim by however far the shard would fall over a flight time of
        // range/speed, which is wrong twice: adding vertical lift to a
        // fixed-magnitude velocity steals it from the horizontal, so the shard
        // takes longer to arrive than the estimate assumed and falls further
        // than the correction allowed for. The error is small and it is not
        // monotonic — measured against a stationary target it hit at twelve,
        // twenty, forty and forty-four metres and missed cleanly at thirty,
        // which is the worst possible shape for a bug: an attack that works
        // except at one distance nobody can name.
        //
        // The exact launch angle for a fixed speed over a known displacement is
        // one square root. The flatter of the two solutions is the one to take —
        // it arrives sooner and reads as a thrown thing rather than a lobbed
        // one — and a negative discriminant means the target is out of range for
        // this arm, where aiming straight at it and falling short is the honest
        // answer.
        double vx, vy, vz;
        double g = GRAVITY;
        double vv = speed * speed;
        double disc = vv * vv - g * (g * flat * flat + 2 * dz * vv);
        if (flat > 1e-6 && disc >= 0) {
            double tan = (vv - Math.sqrt(disc)) / (g * flat);
            double angle = Math.atan(tan);
            double horizontal = speed * Math.cos(angle);
            vx = dx / flat * horizontal;
            vy = dy / flat * horizontal;
            vz = speed * Math.sin(angle);
        } else {
            double range = Math.max(1e-6, Math.hypot(flat, dz));
            vx = dx / range * speed;
            vy = dy / range * speed;
            vz = dz / range * speed;
        }
        return new Hurl(id, from.def().key(), null, ox, oy, oz, vx, vy, vz,
                ranged.damage());
    }

    /**
     * Fire one along a bearing, from a person rather than at one.
     *
     * <p>The other half of {@link #thrown}, and the difference is the whole
     * point: a shard is <em>aimed</em>, so its launch angle is solved backwards
     * from where its target is standing, and a jet is <em>pointed</em>, so it
     * simply goes where the barrel goes. A player who misses has missed by
     * aiming badly, which is what a water gun is for.
     *
     * @param by    who fired it — see {@link #owner()}
     * @param yaw   the direction, in the engine's own convention
     * @param pitch how far above the flat, in radians
     * @param speed how fast it leaves the barrel, in metres a second
     */
    public static Hurl squirted(long id, String species, String by, double x, double y,
                                double z, double yaw, double pitch, double speed) {
        double cos = Math.cos(pitch);
        Hurl jet = new Hurl(id, species, null, x, y, z, Math.sin(yaw) * cos * speed,
                -Math.cos(yaw) * cos * speed, Math.sin(pitch) * speed, 0);
        jet.owner = by;
        return jet;
    }

    /** Which shard this is. */
    public long id() { return id; }

    /** The species that threw it, so a client knows what to draw. */
    public String species() { return species; }

    /** Who it was thrown at, or {@code null} — unused today, kept for the log. */
    public String target() { return target; }

    /** The walker who fired it, or {@code null} when something threw it. */
    public String owner() { return owner; }

    public double x() { return x; }

    public double y() { return y; }

    public double z() { return z; }

    /** Which way it is pointing, for the model. */
    public double yaw() { return yaw; }

    /** …and how steeply, likewise. */
    public double pitch() { return pitch; }

    /** What it does on a hit, as a share of a full health bar. */
    public double damage() { return damage; }

    /** How long it has been in the air. */
    public double age() { return age; }

    /** Whether it is finished — hit something, landed, or timed out. */
    public boolean spent() { return spent; }

    /** Put it out, without it having hit anything. */
    public void expire() { spent = true; }

    /** Place one from a snapshot — what a client does. */
    public void place(double x, double y, double z, double yaw, double pitch) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.lastX = x;
        this.lastY = y;
        this.lastZ = z;
        this.yaw = yaw;
        this.pitch = pitch;
    }

    /**
     * Advance one shard.
     *
     * @param groundZ the height of the ground under it, in metres
     * @return {@code true} while it is still flying
     */
    public boolean step(double dt, double groundZ) {
        if (spent) return false;
        age += dt;
        lastX = x;
        lastY = y;
        lastZ = z;
        vz -= GRAVITY * dt;
        x += vx * dt;
        y += vy * dt;
        z += vz * dt;
        aim();
        if (age >= LIFETIME || z <= groundZ) {
            spent = true;
            return false;
        }
        return true;
    }

    /**
     * Whether the ground it covered this tick passed within
     * {@link #HIT_RADIUS} of a person standing at a point.
     *
     * <p>The <em>segment</em> from where it was to where it is, not the point it
     * ended on — see {@link #lastX} for the bug that distinction fixes. The
     * arithmetic is the usual closest-point-on-a-segment: project the target
     * onto the swept line, clamp to the ends, and measure.
     */
    public boolean hits(double px, double py, double pz) {
        double tx = px, ty = py, tz = pz + CHEST_HEIGHT;
        double sx = x - lastX, sy = y - lastY, sz = z - lastZ;
        double lengthSq = sx * sx + sy * sy + sz * sz;
        double t = 0;
        if (lengthSq > 1e-12) {
            t = ((tx - lastX) * sx + (ty - lastY) * sy + (tz - lastZ) * sz) / lengthSq;
            t = Math.max(0, Math.min(1, t));
        }
        double dx = lastX + sx * t - tx;
        double dy = lastY + sy * t - ty;
        double dz = lastZ + sz * t - tz;
        return dx * dx + dy * dy + dz * dz <= HIT_RADIUS * HIT_RADIUS;
    }

    private void aim() {
        double flat = Math.hypot(vx, vy);
        yaw = Math.atan2(vx, -vy);
        pitch = Math.atan2(vz, flat);
    }

    /** The fields that go in a snapshot. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("sp", species);
        m.put("x", round(x));
        m.put("y", round(y));
        m.put("z", round(z));
        m.put("yaw", round(yaw));
        m.put("p", round(pitch));
        return m;
    }

    /**
     * Rebuild one from a snapshot row, for a client to draw.
     *
     * <p>Velocity is not sent and is not wanted: a client never steps one — the
     * host does that and sends where it got to, twenty times a second, which at
     * a shard's speed is every metre and a bit.
     */
    public static Hurl fromMap(Map<String, Object> m) {
        if (m == null) return null;
        long id = num(m, "id");
        if (id == 0) return null;
        Hurl hurl = new Hurl(id, str(m, "sp"), null, dbl(m, "x"), dbl(m, "y"),
                dbl(m, "z"), 0, 0, 0, 0);
        hurl.place(dbl(m, "x"), dbl(m, "y"), dbl(m, "z"), dbl(m, "yaw"), dbl(m, "p"));
        return hurl;
    }

    private static double round(double v) { return Math.round(v * 100) / 100.0; }

    private static double dbl(Map<String, Object> m, String key) {
        return m.get(key) instanceof Number n ? n.doubleValue() : 0;
    }

    private static long num(Map<String, Object> m, String key) {
        return m.get(key) instanceof Number n ? n.longValue() : 0;
    }

    private static String str(Map<String, Object> m, String key) {
        return m.get(key) instanceof String s ? s : "";
    }

    @Override public String toString() {
        return "shard #" + id + " at (" + Math.round(x) + ", " + Math.round(y) + ")";
    }
}
