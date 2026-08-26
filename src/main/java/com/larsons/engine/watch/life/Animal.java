package com.larsons.engine.watch.life;

import com.larsons.engine.watch.WatchClock;

import java.util.Random;

/**
 * One animal, alive, somewhere in the world.
 *
 * <h2>The behaviour is the game</h2>
 *
 * <p>In a game about <em>watching</em> animals, what an animal does when it
 * notices you is not flavour — it is the whole of the difficulty curve. So the
 * AI here is small and every part of it is about that one relationship:
 *
 * <pre>
 *   SETTLED  →  ALERT   a player got inside the flush distance
 *   ALERT    →  FLEE    they kept coming, or moved fast
 *   ALERT    →  SETTLED they stopped, and stayed stopped
 *   any      →  FEED    a lure it eats is in range and nobody is too close
 *   any      →  SLEEP   it is the wrong hour for this species
 * </pre>
 *
 * <p><b>Stillness is the mechanic.</b> {@link Surroundings#disturbanceAt} hands
 * back not how far the nearest player is but how <em>loud</em> they are: a
 * sprinting player is worth several times their distance, a player who has
 * stopped moving is worth a fraction of it. So the way to get close to a rare
 * bird is to stop walking, which is also the way to get close to a rare bird.
 *
 * <p><b>Taming is the same relationship, run long.</b> A tameable species that
 * feeds at your lure while you stay still gains {@link #trust()}; at full trust
 * it follows the player who fed it and stops flushing from them at all. That is
 * the pet system, and it deliberately has no button.
 */
public final class Animal {

    /** What the animal is doing, as far as the simulation is concerned. */
    public enum Behaviour {
        /** Standing about, or drifting from perch to perch. */
        SETTLED,
        /** Going somewhere in particular. */
        WANDER,
        /** Eating at a lure or a natural food source. */
        FEED,
        /** Has noticed something and is deciding. */
        ALERT,
        /** Leaving, fast. */
        FLEE,
        /** Out of hours. */
        SLEEP,
        /** A tame animal, keeping up with its owner. */
        FOLLOW
    }

    /** What an animal needs to know about the world around it. */
    public interface Surroundings {

        /** The ground's height under a point, in metres. */
        double groundAt(double x, double y);

        /** What time it is. */
        WatchClock clock();

        /**
         * How disturbed a point is, in metres of "effective distance" to the
         * nearest player — smaller is more alarming.
         *
         * <p>Not the plain distance: a player who is moving counts as much
         * closer than they are, and one who has stopped counts as much further.
         * Returns {@link Double#MAX_VALUE} when nobody is near.
         */
        double disturbanceAt(double x, double y);

        /**
         * The position of the nearest lure this animal would eat, written into
         * {@code out} as {@code x, y, appeal}, or {@code false} when there is
         * none in range.
         */
        boolean nearestLure(double x, double y, Diet diet, double[] out);

        /** Where a named player is, written into {@code out}; {@code false} if gone. */
        boolean playerPosition(String name, double[] out);
    }

    /** How far an animal will travel to reach a lure. */
    private static final double LURE_REACH = 26;

    /** How long an alerted animal waits before deciding it is safe again. */
    private static final double ALERT_PATIENCE = 3.5;

    /** How much trust one uninterrupted feed is worth. */
    private static final double TRUST_PER_FEED = 0.09;

    private final long id;
    private final AnimalDef def;
    private final Random rng;

    private double x, y, z, yaw;
    private double targetX, targetY;
    private double altitude;
    private Behaviour behaviour = Behaviour.SETTLED;
    private AnimState state = AnimState.IDLE;
    private double phase;
    private double stateTime;
    private double alertTime;
    private double trust;
    private String owner;
    private boolean flushed;

    public Animal(long id, AnimalDef def, double x, double y, double z, long seed) {
        this.id = id;
        this.def = def;
        this.x = x;
        this.y = y;
        this.z = z;
        this.rng = new Random(seed);
        this.yaw = rng.nextDouble() * Math.PI * 2;
        this.targetX = x;
        this.targetY = y;
        this.altitude = Math.max(0, def.perch());
    }

    public long id() { return id; }

    public AnimalDef def() { return def; }

    public double x() { return x; }

    public double y() { return y; }

    /** How high off the ground it is now, in metres — its feet, not its back. */
    public double z() { return z; }

    public double yaw() { return yaw; }

    /** Which animation state it should be drawn in. */
    public AnimState state() { return state; }

    /** How far through that state's cycle, in turns. */
    public double phase() { return phase; }

    public Behaviour behaviour() { return behaviour; }

    /** How tame it is, {@code 0}–{@code 1}. Only ever above zero if tameable. */
    public double trust() { return trust; }

    /** Whether it has become somebody's pet. */
    public boolean tame() { return trust >= 1 && owner != null; }

    /** Who it follows, or {@code null}. */
    public String owner() { return owner; }

    /** Whether it has been startled since it was last calm — for the HUD. */
    public boolean flushed() { return flushed; }

    /** Put it somewhere — what a client does when a snapshot arrives. */
    public void place(double x, double y, double z, double yaw) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
    }

    /** Adopt a replicated state — likewise. */
    public void adopt(AnimState state, double phase, double trust, String owner) {
        this.state = state;
        this.phase = phase;
        this.trust = trust;
        this.owner = owner;
    }

    /** Make it somebody's, when its trust has reached the top. */
    public void setOwner(String name) { this.owner = name; }

    /** Nudge its trust — what feeding does, and what being frightened undoes. */
    public void addTrust(double amount) {
        if (!def.tameable()) return;
        trust = Math.max(0, Math.min(1, trust + amount));
    }

    // --- the tick --------------------------------------------------------------------

    /**
     * Advance one animal by {@code dt} seconds.
     *
     * <p>Server-side only: clients are handed positions and states in snapshots
     * and never run this, which is what keeps a party's view of a flushing bird
     * identical rather than merely similar.
     */
    public void step(double dt, Surroundings around) {
        stateTime += dt;
        double disturbance = around.disturbanceAt(x, y);
        boolean ownerNear = false;
        double[] scratch = new double[3];

        if (tame() && around.playerPosition(owner, scratch)) {
            double dx = scratch[0] - x, dy = scratch[1] - y;
            ownerNear = dx * dx + dy * dy < 900;
        }

        decide(dt, around, disturbance, ownerNear, scratch);
        move(dt, around);

        phase += dt * state.cyclesPerSecond();
        phase -= Math.floor(phase);
    }

    private void decide(double dt, Surroundings around, double disturbance,
                        boolean ownerNear, double[] scratch) {
        double flush = def.flushDistance();
        // A tame animal is not frightened of its own person. That is the whole
        // difference a pet makes, and it is one line.
        if (tame()) flush *= 0.15;

        if (disturbance < flush * 0.5) {
            enter(Behaviour.FLEE);
            flushed = true;
            alertTime = 0;
            // Being startled costs trust: a pet is made by patience and unmade
            // by charging at it.
            addTrust(-0.02);
            return;
        }
        if (disturbance < flush) {
            if (behaviour != Behaviour.FLEE) enter(Behaviour.ALERT);
            alertTime = 0;
            return;
        }

        if (behaviour == Behaviour.FLEE || behaviour == Behaviour.ALERT) {
            alertTime += dt;
            if (alertTime < ALERT_PATIENCE) return;
            flushed = false;
        }

        if (ownerNear && tame()) {
            enter(Behaviour.FOLLOW);
            around.playerPosition(owner, scratch);
            targetX = scratch[0] + rng.nextDouble() * 3 - 1.5;
            targetY = scratch[1] + rng.nextDouble() * 3 - 1.5;
            return;
        }

        // A lure it eats, within reach, wins over everything else it might do.
        if (around.nearestLure(x, y, def.diet(), scratch)) {
            double dx = scratch[0] - x, dy = scratch[1] - y;
            double distance = Math.sqrt(dx * dx + dy * dy);
            if (distance < LURE_REACH) {
                targetX = scratch[0];
                targetY = scratch[1];
                if (distance < 1.6) {
                    enter(Behaviour.FEED);
                    // Fed, undisturbed, by somebody standing still: this is how
                    // a wild animal becomes a pet, and there is no other way.
                    addTrust(TRUST_PER_FEED * dt);
                    return;
                }
                enter(Behaviour.WANDER);
                return;
            }
        }

        WatchClock.Phase hour = around.clock().phase();
        if (!def.activity().awakeAt(hour) && def.activity().activityAt(hour) < 0.2) {
            enter(Behaviour.SLEEP);
            return;
        }

        if (behaviour == Behaviour.WANDER) {
            double dx = targetX - x, dy = targetY - y;
            if (dx * dx + dy * dy > 1.2) return;
            enter(Behaviour.SETTLED);
            return;
        }
        if (behaviour == Behaviour.SETTLED && stateTime > 2 + rng.nextDouble() * 5) {
            pickWanderTarget();
            enter(Behaviour.WANDER);
            return;
        }
        if (behaviour != Behaviour.SETTLED && behaviour != Behaviour.WANDER) {
            enter(Behaviour.SETTLED);
        }
    }

    private void pickWanderTarget() {
        double reach = 6 + rng.nextDouble() * 22;
        double angle = rng.nextDouble() * Math.PI * 2;
        targetX = x + Math.cos(angle) * reach;
        targetY = y + Math.sin(angle) * reach;
    }

    private void enter(Behaviour next) {
        if (behaviour == next) return;
        behaviour = next;
        stateTime = 0;
        state = stateFor(next);
        if (next == Behaviour.FLEE) {
            // Straight away from whatever it is, as far as it can be bothered.
            double away = yaw + Math.PI + (rng.nextDouble() - 0.5);
            double reach = 24 + rng.nextDouble() * 30;
            targetX = x + Math.cos(away) * reach;
            targetY = y + Math.sin(away) * reach;
        }
    }

    /** Which animation a behaviour is drawn as, given how this family moves. */
    private AnimState stateFor(Behaviour next) {
        boolean flies = def.airborne();
        return switch (next) {
            case SETTLED -> tame() ? AnimState.TAME : AnimState.IDLE;
            case WANDER, FOLLOW -> flies ? AnimState.FLY : AnimState.WALK;
            case FEED -> AnimState.FORAGE;
            case ALERT -> AnimState.ALERT;
            case FLEE -> flies ? AnimState.FLY : AnimState.RUN;
            case SLEEP -> AnimState.SLEEP;
        };
    }

    private void move(double dt, Surroundings around) {
        double speed = switch (behaviour) {
            case FLEE -> def.speed() * 1.8;
            case WANDER, FOLLOW -> def.speed();
            case FEED -> def.speed() * 0.3;
            default -> 0;
        };
        if (speed > 0) {
            double dx = targetX - x, dy = targetY - y;
            double distance = Math.sqrt(dx * dx + dy * dy);
            if (distance > 0.05) {
                double step = Math.min(distance, speed * dt);
                x += dx / distance * step;
                y += dy / distance * step;
                // Turn toward where it is going, rather than snapping: a bird
                // that pivots instantly reads as a sprite, not an animal.
                double want = Math.atan2(dy, dx);
                yaw += wrapAngle(want - yaw) * Math.min(1, dt * 6);
            }
        }

        double ground = around.groundAt(x, y);
        double want = switch (behaviour) {
            case FLEE -> def.airborne() ? Math.max(def.perch(), 6) : 0;
            case FEED -> 0;
            case SLEEP -> def.airborne() ? def.perch() * 0.8 : 0;
            default -> def.airborne() ? def.perch() : 0;
        };
        if (def.aquatic()) want = Math.min(-0.25, def.perch());
        // Ease toward the height it wants rather than teleporting to it, so a
        // bird taking off climbs and a fish sinks.
        altitude += (want - altitude) * Math.min(1, dt * 1.6);
        z = ground + altitude;
    }

    private static double wrapAngle(double radians) {
        double a = radians;
        while (a > Math.PI) a -= Math.PI * 2;
        while (a < -Math.PI) a += Math.PI * 2;
        return a;
    }

    @Override public String toString() {
        return def.name() + " #" + id + " " + behaviour
                + " at (" + Math.round(x) + ", " + Math.round(y) + ")";
    }
}
