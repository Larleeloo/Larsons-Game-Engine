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
 *
 * <h2>And then there are three that do none of this</h2>
 *
 * <p>{@link Mutants} adds three species that hunt people, and they run
 * {@link #hunt} instead of {@link #decide} — a different four-state machine
 * reached by the first branch of {@link #step}. Every sentence above is false of
 * them, which is the point of them: the mechanic this class is built around is
 * "hold still and it will let you closer", and the whole of what a mutant means
 * is that holding still does not work on it. Nothing in the ordinary loop was
 * softened to make room; the hostile branch is simply somewhere else.
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
        FOLLOW,
        /**
         * Coming for somebody. Only a {@link Mutants} species ever enters this.
         */
        HUNT,
        /** Close enough, and swinging. Likewise. */
        MAUL
    }

    /** What an animal needs to know about the world around it. */
    public interface Surroundings {

        /** The ground's height under a point, in metres. */
        double groundAt(double x, double y);

        /**
         * How deep the water over a point is, in metres; {@code 0} on dry land.
         *
         * <p>An animal has to be able to ask this or it cannot stay in the
         * medium it lives in, and staying in it is not a detail: a fish whose
         * wander target landed on a hillside swam up the hill and then sat
         * inside the ground, because its altitude is a quarter-metre
         * <em>below</em> whatever it is over. That is the "animals glitch
         * underwater and get stuck" bug, and the fix is this method plus
         * {@link #waterNear}.
         */
        double waterDepthAt(double x, double y);

        /**
         * A point within {@code radius} that has at least {@code minDepth} of
         * water over it, written into {@code out} as {@code x, y}.
         *
         * <p>What a swimmer that has wandered too near the shore steers back
         * to. Returns {@code false} when there is none, which for an animal
         * that has ended up somewhere impossible is the signal to stop trying
         * to swim and simply stay where the water is deepest nearby.
         */
        boolean waterNear(double x, double y, double radius, double minDepth, double[] out);

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

        /**
         * The nearest player a mutant could hunt, by name, or {@code null}.
         *
         * <p><b>Not {@link #disturbanceAt}, and the difference is the point.</b>
         * Disturbance is how <em>loud</em> a place is: a player who has stopped
         * moving counts as three times further away than they are, which is the
         * whole approach mechanic and is exactly the wrong question here.
         * Holding still is how you get close to a bird; it is not how you hide
         * from something that hunts. So this is the plain distance, and the
         * answer to a mutant is to be somewhere else rather than to be quiet.
         *
         * <p>Dead players are not offered. Neither is anybody further off than
         * {@code range}.
         *
         * <p><b>Defaulted</b>, along with {@link #wound}, and for a reason worth
         * writing down: this interface is implemented by {@code WatchGame} and
         * by test doubles, and only three of the thirteen hundred species in
         * this game will ever call either method. A test that stands a fish on
         * a shoreline should not have to answer questions about hunting people
         * to keep compiling, and the fish will never ask them.
         *
         * @return the player's name, for {@link #playerPosition} and
         *         {@link #wound}, or {@code null} when there is nobody
         */
        default String nearestQuarry(double x, double y, double range) {
            return null;
        }

        /**
         * Land a blow on a player.
         *
         * <p>The animal decides that it has hit somebody; the world decides
         * what that costs and what happens if it was the last one they could
         * take. That split is the same one the rest of this interface makes —
         * an {@link Animal} never reaches into the game, it asks the game
         * questions and tells it what it did.
         *
         * @param name   who was hit
         * @param amount a share of a full health bar
         * @param by     the animal that hit them, for the line in the log
         */
        default void wound(String name, double amount, Animal by) { }

        /**
         * Throw something at a player — the wendigo's ranged attack.
         *
         * <p>The same split as {@link #wound}: the animal decides that it is
         * loosing one, and the world builds it, tracks it and works out whether
         * it connects. An {@link Animal} owns no projectile and never sees one
         * again after this call.
         *
         * <p>Defaulted for {@link #nearestQuarry}'s reason, and more so: one of
         * thirteen hundred species ever calls it.
         *
         * @param at     who it is aimed at
         * @param ranged what the thrower's arm can do
         */
        default void hurlAt(Animal from, String at, Mutants.Ranged ranged) { }
    }

    /** How far an animal will travel to reach a lure. */
    private static final double LURE_REACH = 26;

    /** How long an alerted animal waits before deciding it is safe again. */
    private static final double ALERT_PATIENCE = 3.5;

    /** How much trust one uninterrupted feed is worth. */
    private static final double TRUST_PER_FEED = 0.09;

    /**
     * How long an animal will keep walking at one target before giving up on
     * it, in seconds.
     *
     * <p><b>The whole of the "animals get stuck" fix, in one number.</b> The
     * decision loop used to say: if you are wandering and are not yet at your
     * target, carry on — with no other way out of that branch. Anything that
     * could not close the distance therefore wandered for ever with no
     * behaviour change and no new target: a swimmer whose target was inland, a
     * walker whose target was in a lake, or simply one whose target was thirty
     * metres off and whose species walks at a fifth of a metre a second. From
     * outside it reads exactly as reported — an animal moving, apparently
     * purposefully, that never arrives anywhere and never does anything else.
     *
     * <p>Generous rather than tight: a real journey across a clearing is
     * seconds, and the point of this is to catch journeys that are not going to
     * end, not to interrupt ones that are.
     */
    private static final double WANDER_TIMEOUT = 14;

    /**
     * How little progress counts as none, in metres per second.
     *
     * <p>Checked against the animal's own speed rather than assumed: an animal
     * that is pressed against something it cannot pass covers no ground at all,
     * and noticing that in a second is better than noticing it in fourteen.
     */
    private static final double STALL_SPEED_FRACTION = 0.2;

    /** How long that has to hold before it counts as stuck. */
    private static final double STALL_PATIENCE = 1.5;

    /** The least water a swimmer will settle for, in metres. */
    private static final double SWIM_DEPTH = 0.6;

    /** How far a swimmer will look for water when it finds itself out of it. */
    private static final double SWIM_SEARCH = 30;

    /** Deeper than this and a walker will not go, in metres. */
    private static final double WADE_LIMIT = 1.1;

    /** Nearest a fleeing animal aims, in metres. */
    private static final double FLEE_NEAR = 24;

    /** …and furthest. */
    private static final double FLEE_FAR = 54;

    /**
     * How close to its escape an animal gets before choosing the next one, in
     * metres. See {@link #needsNewEscape}.
     */
    private static final double FLEE_RETARGET = 1.5;

    /** Directions considered when looking for a way out. */
    private static final int FLEE_SAMPLES = 8;

    /**
     * How far a blocked step may be turned aside, in radians, tried in order.
     *
     * <p>Straight on first, because that is what nearly every step in the world
     * is and it costs one sample. Then progressively sharper deflections, out
     * to eighty degrees — enough to follow a shoreline, and short of the
     * hundred and eighty that would have an animal turn round and call it
     * progress. See {@link #advance}.
     */
    private static final double[] DEFLECTIONS = {
        0, Math.toRadians(30), Math.toRadians(55), Math.toRadians(80)
    };

    /**
     * How long a {@link Mutants.Power#LUNGE} burst lasts, and how long the
     * breather between two of them is, in seconds.
     *
     * <p>The gap is what makes a werewolf survivable and the burst is what makes
     * it frightening. Both are on the long side of what the numbers alone would
     * suggest, because a player has to be able to <em>see</em> the rhythm: two
     * seconds of it closing and three of it merely following is a pattern
     * somebody works out on their first chase, and a pattern is the difference
     * between a mechanic and a dice roll.
     */
    private static final double BURST_SECONDS = 1.8;

    private static final double BURST_REST = 3.2;

    /**
     * What share of its burst speed a {@link Mutants.Power#LUNGE} hunter holds
     * between bursts.
     *
     * <p>Not a feel number: it is chosen so that the werewolf's <em>resting</em>
     * pursuit is {@link com.larsons.engine.watch.WatchPlayer#RUN_SPEED} exactly.
     * Its burst is 11.1 m/s and 0.72 of that is 8.0, which is a sprinting
     * player's pace to two decimal places — so a straight-line chase is a
     * stalemate that the bursts slowly win, and the way out is the ground rather
     * than the legs.
     *
     * <p>Kept here rather than as a second multiplier on {@code Kind} because
     * every {@code LUNGE} creature should have the same shape of rhythm; what
     * differs between two of them is how fast the burst is, which
     * {@link Mutants.Kind#chase()} already says.
     */
    private static final double LUNGE_REST = 0.72;

    /**
     * How close an {@link Mutants.Power#AMBUSH} hunter lets somebody get before
     * it stands up, as a share of its notice range.
     *
     * <p>Its notice range is already the shortest of the three, and this cuts it
     * further — to about twenty-two metres, which is the shortest reaction
     * window in the game. What that buys is the half-second between "that log is
     * the wrong shape" and it being upright, which is the entire personality of
     * the mirewraith.
     */
    private static final double AMBUSH_TRIGGER = 0.55;

    private final long id;
    private final AnimalDef def;

    /**
     * What this animal is, if it is one of the three that hunt people, and
     * {@code null} for the other thirteen hundred.
     *
     * <p>Resolved once in the constructor rather than per tick: it is a map
     * lookup on a species key, it never changes, and {@link #step} runs on
     * every animal alive twenty times a second.
     */
    private final Mutants.Kind mutant;

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

    /** How long it has been walking toward the current target. */
    private double travelTime;

    /** How long it has been failing to make progress toward it. */
    private double stallTime;

    /**
     * Which way it turns to get round something, {@code +1} or {@code −1}.
     *
     * <p>Held rather than chosen per step so an animal commits to one side of
     * an obstacle. Re-deciding every frame is how a thing ends up jittering on
     * the spot at the point of a headland, having gone left, then right, then
     * left again. See {@link #advance}.
     */
    private double slideSign = 1;

    /** Who this mutant is coming for, or {@code null}. */
    private String quarry;

    /** How long until it can swing again, in seconds. */
    private double strikeCooldown;

    /** …and until it can throw again. Its own clock — see {@link #hurl}. */
    private double hurlCooldown;

    /** Where a lunging hunter is in its burst-and-breather cycle, in seconds. */
    private double burstClock;

    /** Whether it is in the fast half of that cycle now. */
    private boolean bursting;

    public Animal(long id, AnimalDef def, double x, double y, double z, long seed) {
        this.id = id;
        this.def = def;
        this.mutant = Mutants.of(def);
        this.x = x;
        this.y = y;
        this.z = z;
        this.rng = new Random(seed);
        this.yaw = rng.nextDouble() * Math.PI * 2;
        // Half of them go round an obstacle one way and half the other, which
        // is what stops a herd flushed into the same headland forming a queue.
        this.slideSign = rng.nextBoolean() ? 1 : -1;
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

    /** Whether this is one of the three that hunt people. */
    public boolean hostile() { return mutant != null; }

    /** Which mutant it is, or {@code null}. */
    public Mutants.Kind mutant() { return mutant; }

    /** Who it is hunting, or {@code null} — for the HUD, and for a test. */
    public String quarry() { return quarry; }

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
        double[] scratch = new double[3];
        double wasX = x, wasY = y;

        // The three mutants run a different loop entirely, and it is the first
        // branch rather than a clause inside the ordinary one on purpose:
        // nothing below this line applies to something that hunts. It has no
        // flush distance, no lure it will come to, no hour it sleeps through
        // once it is awake, and no trust to gain. See Mutants.
        if (mutant != null) {
            hunt(dt, around, scratch);
        } else {
            double disturbance = around.disturbanceAt(x, y);
            boolean ownerNear = false;
            if (tame() && around.playerPosition(owner, scratch)) {
                double dx = scratch[0] - x, dy = scratch[1] - y;
                ownerNear = dx * dx + dy * dy < 900;
            }
            decide(dt, around, disturbance, ownerNear, scratch);
        }
        move(dt, around);
        poseFor(Math.hypot(x - wasX, y - wasY));

        phase += dt * state.cyclesPerSecond();
        phase -= Math.floor(phase);
    }

    // --- hunting ---------------------------------------------------------------------

    /**
     * One tick of a mutant, which is the only thing in this world that comes
     * <em>toward</em> a player.
     *
     * <p>The state machine is deliberately four states rather than the seven the
     * ordinary loop has, because a thing that hunts has far less to decide:
     *
     * <pre>
     *   SETTLED/WANDER  →  HUNT   somebody came inside the notice range
     *   HUNT            →  MAUL   they are within arm's reach
     *   MAUL            →  HUNT   they moved, or it swung and is winding up again
     *   HUNT            →  WANDER they got clear — see Kind.lose
     * </pre>
     *
     * <p><b>There is no ALERT and no FLEE.</b> Both are about deciding whether a
     * player is a problem, and these three have decided. What replaces the
     * decision is distance: everything a player can do about a mutant is a fact
     * about where they are standing, which is why the pursuit reads the plain
     * distance and not {@link Surroundings#disturbanceAt}. Holding still works
     * on a heron. It does not work on this.
     */
    private void hunt(double dt, Surroundings around, double[] scratch) {
        strikeCooldown = Math.max(0, strikeCooldown - dt);
        driveBurst(dt);

        // Keep the one it already has if that one is still within the range at
        // which it gives up; otherwise look for a new one. Sticking to a quarry
        // is what stops a mutant between two people oscillating on the spot,
        // and it is what makes "it is following Kara" a thing a party can say.
        if (quarry != null && !around.playerPosition(quarry, scratch)) quarry = null;
        if (quarry != null) {
            double dx = scratch[0] - x, dy = scratch[1] - y;
            if (Math.hypot(dx, dy) > mutant.lose()) quarry = null;
        }
        if (quarry == null) {
            quarry = around.nearestQuarry(x, y, noticeRange());
            if (quarry != null && !around.playerPosition(quarry, scratch)) quarry = null;
        }

        if (quarry == null) {
            // Nobody about. It walks its ground — the same wander every other
            // animal does, so a mutant nobody has met yet is something you can
            // watch moving through a wood from a long way off, which is the
            // whole of the encounter this design is built around.
            burstClock = 0;
            bursting = false;
            // An ambusher does not walk its ground: it stands where it is, and
            // "that log is the wrong shape" is the whole of the encounter. One
            // that wandered would be a five-metre silhouette moving through a
            // marsh, which is a thing you notice from four hundred metres away
            // and is the opposite of an ambush.
            if (mutant.power() == Mutants.Power.AMBUSH) {
                aimAt(x, y);
                enter(Behaviour.SETTLED);
                return;
            }
            // Carry on with the journey it is on, while that journey is still
            // going somewhere; choose another when it has arrived or stalled.
            if (behaviour == Behaviour.WANDER && dx2(targetX, targetY) > 1.2
                    && !givenUp()) {
                return;
            }
            pickWanderTarget(around);
            enter(Behaviour.WANDER);
            return;
        }

        double dx = scratch[0] - x, dy = scratch[1] - y;
        double distance = Math.hypot(dx, dy);
        // Face them whatever else is happening. A mutant that is winding up to
        // swing has to be pointed at the person it is about to hit, and a
        // fifteen-degree error there reads as a bug rather than as a miss.
        yaw += wrapAngle(Math.atan2(dy, dx) - yaw) * Math.min(1, dt * 4);

        if (distance <= mutant.reach()) {
            aimAt(x, y);
            enter(Behaviour.MAUL);
            if (strikeCooldown <= 0) {
                strikeCooldown = mutant.strikeSeconds();
                around.wound(quarry, mutant.damage(), this);
                // Restart the swing so the blow lands at the top of the arc
                // rather than wherever the shared phase clock happened to be.
                phase = 0;
            }
            return;
        }

        // Out of reach, and it may still be able to hurt them. It keeps walking
        // while it does: a thrower that stopped to throw would be a turret, and
        // the wendigo's whole character is that it does not stop.
        hurl(dt, around, distance);
        // Walk at where they are now rather than at where they were when the
        // last target was set: a pursuit that re-aims once a second is a thing
        // you can walk in a circle around.
        aimAt(scratch[0], scratch[1]);
        enter(Behaviour.HUNT);
    }

    /**
     * Throw something, if this one throws and the range is right.
     *
     * <p><b>A band rather than a ceiling.</b> The maximum is what you would
     * expect; the minimum is the interesting half. Inside
     * {@link Mutants.Ranged#minRange} it does not throw at all, which means
     * closing on a wendigo <em>turns its ranged attack off</em> and leaves you
     * with the slowest melee in the game. That is the shape of the fight: the
     * dangerous place is the middle distance, and both running away and running
     * at it are better than standing at forty metres in the open.
     *
     * <p>The cooldown is its own, not the melee one. A creature that had to
     * choose between swinging and throwing on one clock would spend the whole
     * approach doing neither.
     */
    private void hurl(double dt, Surroundings around, double distance) {
        Mutants.Ranged ranged = mutant.ranged();
        if (ranged == null) return;
        hurlCooldown = Math.max(0, hurlCooldown - dt);
        if (distance < ranged.minRange() || distance > ranged.range()) return;
        if (hurlCooldown > 0) return;
        hurlCooldown = ranged.everySeconds();
        around.hurlAt(this, quarry, ranged);
    }

    /**
     * How far off this one picks somebody up.
     *
     * <p>An {@link Mutants.Power#AMBUSH} hunter is a special case in the one
     * place it has to be: it has a short notice range to begin with, and it only
     * takes an interest inside a fraction of even that. Everything else notices
     * at its full range.
     */
    private double noticeRange() {
        if (mutant.power() != Mutants.Power.AMBUSH) return mutant.notice();
        return mutant.notice() * AMBUSH_TRIGGER;
    }

    /** Advance the burst-and-breather clock a {@link Mutants.Power#LUNGE} runs on. */
    private void driveBurst(double dt) {
        if (mutant.power() != Mutants.Power.LUNGE) return;
        burstClock += dt;
        double limit = bursting ? BURST_SECONDS : BURST_REST;
        if (burstClock >= limit) {
            burstClock = 0;
            bursting = !bursting;
        }
    }

    /** How fast it is going after somebody, in metres per second. */
    private double huntSpeed() {
        double chase = mutant.pursuitSpeed();
        return switch (mutant.power()) {
            // Between bursts it holds a sprinting player exactly level; during
            // one it is the fastest thing in the world. See LUNGE_REST — the
            // fraction is what makes the between-burst speed the design's
            // number rather than an incidental one.
            case LUNGE -> bursting ? chase : chase * LUNGE_REST;
            // Flat out, with no rhythm to read. Both of these hold a sprint.
            case AMBUSH, STALK -> chase;
        };
    }

    /** The square of the distance from here to a point. */
    private double dx2(double px, double py) {
        double dx = px - x, dy = py - y;
        return dx * dx + dy * dy;
    }

    /**
     * Make the animation agree with what the animal actually did.
     *
     * <p><b>The backstop, and the reason this is a rule rather than a series of
     * fixes.</b> Every freeze reported against this class looked the same from
     * outside — something sprinting on the spot — because the pose came from
     * the <em>intent</em> ({@code I am fleeing}) while the position came from
     * what was possible ({@code there is a lake in the way}). Whenever those
     * two disagreed, the disagreement was on screen.
     *
     * <p>The causes are fixed above — fleeing re-targets, steps go round
     * obstacles, arrivals stop the run — but the class of bug is worth closing
     * for good, because the next one will look identical and there is no reason
     * for it to be visible. So the last thing a tick does is check the ground
     * actually covered, and an animal that covered none is drawn standing.
     *
     * <p>The threshold is "did not move at all" rather than "moved slowly", so
     * a creeping animal still gets its walk cycle and nothing flickers.
     */
    private void poseFor(double covered) {
        AnimState intended = stateFor(behaviour);
        boolean moving = intended == AnimState.WALK || intended == AnimState.RUN;
        // A flier holding station is hovering, which FLY already reads as; only
        // things with feet look wrong standing still in a walk cycle.
        state = moving && covered < 1e-4 ? AnimState.ALERT : intended;
    }

    private void decide(double dt, Surroundings around, double disturbance,
                        boolean ownerNear, double[] scratch) {
        double flush = def.flushDistance();
        // A tame animal is not frightened of its own person. That is the whole
        // difference a pet makes, and it is one line.
        if (tame()) flush *= 0.15;

        if (disturbance < flush * 0.5) {
            flushed = true;
            alertTime = 0;
            // Being startled costs trust: a pet is made by patience and unmade
            // by charging at it.
            addTrust(-0.02);
            keepFleeing(around);
            return;
        }
        if (disturbance < flush) {
            // Still too close for comfort. An animal that is already running
            // keeps running — see keepFleeing for why that line is the whole
            // fix — and one that is not merely watches.
            if (behaviour == Behaviour.FLEE) keepFleeing(around);
            else enter(Behaviour.ALERT);
            alertTime = 0;
            return;
        }

        if (behaviour == Behaviour.FLEE || behaviour == Behaviour.ALERT) {
            // Far enough away to start calming down. An animal that has reached
            // its escape stops running <em>now</em> rather than at the end of
            // the cooling-off period: it has arrived, and standing on the spot
            // in a running animation for three and a half seconds is the last
            // of the freezes this class had. Standing alert is what an animal
            // that has just got clear actually does, and it is a pose the model
            // already has.
            if (behaviour == Behaviour.FLEE && needsNewEscape()) {
                enter(Behaviour.ALERT);
            }
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
            boolean arrived = dx * dx + dy * dy <= 1.2;
            // Arrived, or given up. Both end the journey; the second is what
            // stops an animal walking at an unreachable point for ever. See
            // WANDER_TIMEOUT.
            if (!arrived && !givenUp()) return;
            enter(Behaviour.SETTLED);
            return;
        }
        if (behaviour == Behaviour.SETTLED && stateTime > 2 + rng.nextDouble() * 5) {
            pickWanderTarget(around);
            enter(Behaviour.WANDER);
            return;
        }
        if (behaviour != Behaviour.SETTLED && behaviour != Behaviour.WANDER) {
            enter(Behaviour.SETTLED);
        }
    }

    /**
     * Whether the current journey has stopped being one.
     *
     * <p>Two ways: it has taken too long, or the animal is not covering ground.
     * Either one means the target is not somewhere this animal is going to get
     * to, and the answer to that is a different target rather than more of the
     * same one.
     */
    private boolean givenUp() {
        return travelTime > WANDER_TIMEOUT || stallTime > STALL_PATIENCE;
    }

    /**
     * Somewhere to go, that this animal can actually be when it gets there.
     *
     * <p>The old version picked a point in a ring and asked nothing about it,
     * which is how a trout ended up walking up a hillside and a fox ended up in
     * the middle of a lake. A few rejected candidates cost a handful of noise
     * samples once every several seconds per animal — nothing beside the
     * behaviour it buys.
     */
    private void pickWanderTarget(Surroundings around) {
        boolean swims = def.aquatic();
        for (int attempt = 0; attempt < 6; attempt++) {
            double reach = 6 + rng.nextDouble() * 22;
            double angle = rng.nextDouble() * Math.PI * 2;
            double tx = x + Math.cos(angle) * reach;
            double ty = y + Math.sin(angle) * reach;
            // A flier goes where it likes; it is over the ground, not on it.
            if (habitable(around, tx, ty)) {
                aimAt(tx, ty);
                return;
            }
        }
        // Nothing suitable in the ring. A swimmer looks further for water; a
        // walker that is somehow in a lake heads for the nearest shore. Failing
        // both, stay put — which is a decision, and one this animal will make
        // again in a few seconds.
        double[] out = new double[2];
        if (swims && around.waterNear(x, y, SWIM_SEARCH, SWIM_DEPTH, out)) {
            aimAt(out[0], out[1]);
            return;
        }
        aimAt(x, y);
    }

    /** Point at a target and start the clock on getting there. */
    private void aimAt(double tx, double ty) {
        targetX = tx;
        targetY = ty;
        travelTime = 0;
        stallTime = 0;
    }

    /**
     * Run, and <b>keep</b> running while there is something to run from.
     *
     * <p>This is the fix for an animal that sprints away, stops dead at nothing
     * at all, and then plays its running animation on the spot for as long as
     * you care to watch. The cause was that fleeing picked <em>one</em> escape
     * and never picked another: {@code enter} chose a point twenty-four to
     * fifty-four metres off at the moment of the flush, and while a player
     * stayed inside the flush distance {@link #decide} returned early on every
     * subsequent tick without ever reconsidering. So the animal ran to that
     * point, arrived — and {@link #move}'s "am I there yet" guard then did
     * nothing at all, not even count the standstill as a stall, so the
     * give-up timer could not rescue it either. The behaviour stayed
     * {@code FLEE}, the animation stayed {@code RUN}, and the animal stood
     * there running until the player walked away. Measured on a fallow deer
     * with a player five metres off: fifty-three metres of running, then a
     * hundred and fourteen seconds frozen.
     *
     * <p>It also fixes where the escape pointed. The old direction was
     * {@code yaw + π} — behind wherever the animal happened to be <em>facing</em>,
     * which after a turn or two has nothing to do with where the danger is. Now
     * the ring is sampled and the calmest point wins, which is away from the
     * player by construction, handles two players closing from both sides, and
     * needs no new question asked of the world.
     *
     * <p>And it only offers points the animal can actually occupy, so a deer no
     * longer flees into a lake and a fish no longer flees onto a beach. An
     * animal genuinely cornered — an island, a blind canyon — stops and watches
     * instead, which is both what a real one does and the honest animation for
     * standing still.
     */
    private void keepFleeing(Surroundings around) {
        if (behaviour == Behaviour.FLEE && !needsNewEscape()) return;
        if (findEscape(around)) enter(Behaviour.FLEE);
        else enter(Behaviour.ALERT);
    }

    /**
     * Whether the current escape has been used up.
     *
     * <p>Deliberately generous about "arrived": re-targeting a metre and a half
     * out means the next leg is chosen while the animal is still moving, so a
     * long flight is a curve rather than a series of stops and starts.
     */
    private boolean needsNewEscape() {
        double dx = targetX - x, dy = targetY - y;
        return dx * dx + dy * dy <= FLEE_RETARGET * FLEE_RETARGET || givenUp();
    }

    /**
     * Aim at the calmest reachable point on a ring, and say whether there was
     * one.
     *
     * <p>{@link Surroundings#disturbanceAt} is how loud a place is, so the
     * quietest sample is the best way out. Eight of them, spun by a random
     * offset so a herd does not leave along eight identical spokes.
     */
    private boolean findEscape(Surroundings around) {
        double reach = FLEE_NEAR + rng.nextDouble() * (FLEE_FAR - FLEE_NEAR);
        // Two rings: the far one first, and a short hop if the animal is boxed
        // in. Something with one metre of room should still take it.
        for (double scale : new double[]{1.0, 0.45}) {
            double bestCalm = -1;
            double bestX = 0, bestY = 0;
            boolean found = false;
            double spin = rng.nextDouble() * Math.PI * 2;
            for (int i = 0; i < FLEE_SAMPLES; i++) {
                double angle = spin + i * Math.PI * 2 / FLEE_SAMPLES;
                double tx = x + Math.cos(angle) * reach * scale;
                double ty = y + Math.sin(angle) * reach * scale;
                if (!habitable(around, tx, ty)) continue;
                double calm = around.disturbanceAt(tx, ty);
                if (calm > bestCalm) {
                    bestCalm = calm;
                    bestX = tx;
                    bestY = ty;
                    found = true;
                }
            }
            if (found) {
                aimAt(bestX, bestY);
                return true;
            }
        }
        return false;
    }

    /**
     * Whether this animal could stand, swim or perch at a point.
     *
     * <p>The medium test, in one place, so that choosing a target and taking a
     * step cannot disagree about what is passable — see {@link #accept}, which
     * is the same rule applied to a step rather than to a destination.
     */
    private boolean habitable(Surroundings around, double px, double py) {
        if (def.airborne()) return true;
        double depth = around.waterDepthAt(px, py);
        return def.aquatic() ? depth >= SWIM_DEPTH : depth <= wadeLimit();
    }

    /**
     * How deep this animal will go, in metres.
     *
     * <p>{@link #WADE_LIMIT} for everything that walks, and rather more for the
     * three that tower. A metre and a tenth of water is chest-deep on a fox and
     * over its head on a hare; it is somewhere below the knee of a five-metre
     * mirewraith, whose whole range is standing water and which would otherwise
     * spend its night refusing to enter the marsh it lives in.
     *
     * <p>It also makes the shoreline a real answer to a chase: a player who
     * swims out is safe from a werewolf at four metres of water and not at one,
     * which is a thing you can look at a lake and judge.
     */
    private double wadeLimit() {
        return mutant == null ? WADE_LIMIT
                : Math.max(WADE_LIMIT, def.bodyLength() * 0.45);
    }

    private void enter(Behaviour next) {
        if (behaviour == next) return;
        behaviour = next;
        stateTime = 0;
        state = stateFor(next);
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
            case HUNT -> AnimState.RUN;
            case MAUL -> AnimState.STRIKE;
        };
    }

    private void move(double dt, Surroundings around) {
        double speed = switch (behaviour) {
            case FLEE -> def.speed() * 1.8;
            case WANDER, FOLLOW -> def.speed();
            case FEED -> def.speed() * 0.3;
            // A mutant that has somebody. Everything about how fast that is
            // lives in one place, because the three of them differ in it more
            // than in anything else. See huntSpeed.
            case HUNT -> huntSpeed();
            default -> 0;
        };
        if (speed > 0) {
            travelTime += dt;
            double dx = targetX - x, dy = targetY - y;
            double distance = Math.sqrt(dx * dx + dy * dy);
            if (distance > 0.05) {
                double step = Math.min(distance, speed * dt);
                double moved = advance(around, Math.atan2(dy, dx), step);
                if (moved > 0) {
                    dx = targetX - x;
                    dy = targetY - y;
                    // Turn toward where it is going, rather than snapping: a
                    // bird that pivots instantly reads as a sprite, not an
                    // animal.
                    double want = Math.atan2(dy, dx);
                    yaw += wrapAngle(want - yaw) * Math.min(1, dt * 6);
                }
                // Whether the step was refused or simply too small to matter,
                // an animal that is not covering ground is one whose target is
                // not going to be reached. Time it, and let `decide` retarget.
                stallTime = moved < speed * dt * STALL_SPEED_FRACTION
                        ? stallTime + dt : 0;
            } else {
                // Standing on its own target while its behaviour still says it
                // is going somewhere. That is a stall like any other and has to
                // be counted like one: without this branch the give-up timer
                // never ran for an animal that had <em>arrived</em>, only for
                // one that was blocked, which is why a fleeing animal that
                // reached its escape stood there playing its running animation
                // rather than choosing another. See keepFleeing.
                stallTime += dt;
            }
        } else {
            travelTime = 0;
            stallTime = 0;
        }

        double ground = around.groundAt(x, y);
        double depth = around.waterDepthAt(x, y);
        double want = switch (behaviour) {
            case FLEE -> def.airborne() ? Math.max(def.perch(), 6) : 0;
            case FEED -> 0;
            case SLEEP -> def.airborne() ? def.perch() * 0.8 : 0;
            default -> def.airborne() ? def.perch() : 0;
        };
        if (def.aquatic()) {
            // <b>Measured down from the surface, not up from the bed.</b> The
            // old line took the perch — a small negative number — as an
            // altitude above the ground, so a fish over a bed four metres down
            // swam a quarter of a metre <em>into</em> that bed, and over dry
            // land it swam through the hillside. Depth from the surface is what
            // "a fish is under the water" actually means, and it degrades
            // correctly in shallows: over ten centimetres of water the fish is
            // at the bed, not below it.
            double surface = ground + depth;
            double below = Math.min(Math.abs(def.perch()) + 0.25, Math.max(0, depth - 0.15));
            want = surface - below - ground;
        }
        // Ease toward the height it wants rather than teleporting to it, so a
        // bird taking off climbs and a fish sinks.
        altitude += (want - altitude) * Math.min(1, dt * 1.6);
        // Nothing is ever inside the ground. The easing above is a smoothing of
        // where it wants to be, and a smoothing that passes through the terrain
        // on its way is still an animal in the terrain for those frames.
        if (altitude < 0) altitude = 0;
        z = ground + altitude;
    }

    /**
     * Take a step, <b>going round whatever is in the way rather than into it.</b>
     *
     * <p>Choosing a destination the animal could occupy is not the same as
     * choosing one it can walk to, and conflating them is the second half of
     * the freezing bug. A deer's escape can be a perfectly good meadow on the
     * far side of a lake: every candidate the ring offered passed the
     * habitability test, and the straight line to it crosses eight metres of
     * water, so every step was refused and the animal stood on the bank at a
     * dead run until the give-up timer noticed — then picked another point
     * across the same lake and did it again.
     *
     * <p>So a refused step is not the end of the move. The heading is deflected
     * by up to eighty degrees either way and the first direction that is open
     * wins, which is a run along the shoreline instead of a run into it. The
     * animal commits to one side ({@link #slideSign}) until that side is
     * blocked too, so it goes round an obstacle rather than jittering at the
     * point of it.
     *
     * <p>Cheap on the common path: the undeflected step is tried first and
     * costs one sample, which is what nearly every step in the world is.
     *
     * @return how far it actually got, in metres; {@code 0} if it is cornered
     */
    private double advance(Surroundings around, double heading, double step) {
        for (double deflection : DEFLECTIONS) {
            for (int side = 0; side < (deflection == 0 ? 1 : 2); side++) {
                // The committed side first, then the other one.
                double sign = side == 0 ? slideSign : -slideSign;
                double angle = heading + deflection * sign;
                double nx = x + Math.cos(angle) * step;
                double ny = y + Math.sin(angle) * step;
                if (!accept(around, nx, ny)) continue;
                x = nx;
                y = ny;
                if (deflection != 0) slideSign = sign;
                // A deflected step covers `step` of ground but less of the
                // distance to the target. Reporting the ground is what matters
                // here: the caller uses this to decide whether the animal is
                // stuck, and one running along a bank is not stuck.
                return step;
            }
        }
        return 0;
    }

    /**
     * Whether this animal can be at a point.
     *
     * <p>Fliers can be anywhere. A swimmer needs water and a walker needs to
     * not be out of its depth, and both refusals are what keep an animal in the
     * half of the world it belongs to — the containment that
     * {@link #pickWanderTarget} aims for and this enforces, including for the
     * targets {@code decide} sets without asking, like fleeing and following.
     *
     * <p><b>The escape clause matters as much as the rule.</b> An animal that
     * is <em>already</em> somewhere it should not be — a shore that dried out,
     * a spawn that landed badly, a river that moved — would otherwise have
     * every step refused and be stuck for good, which is the bug this was
     * written to fix rather than a new spelling of it. So when it is out of its
     * medium, any step that gets it closer to that medium is allowed.
     */
    private boolean accept(Surroundings around, double nx, double ny) {
        if (def.airborne()) return true;
        double here = around.waterDepthAt(x, y);
        double there = around.waterDepthAt(nx, ny);
        if (def.aquatic()) {
            boolean afloat = here >= SWIM_DEPTH * 0.5;
            return afloat ? there >= SWIM_DEPTH * 0.5 : there >= here;
        }
        double wade = wadeLimit();
        boolean footing = here <= wade;
        return footing ? there <= wade : there <= here;
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
