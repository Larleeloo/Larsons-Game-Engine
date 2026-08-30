package com.larsons.engine.watch;

import com.larsons.engine.audio.Sounds;
import com.larsons.engine.watch.life.Mutants;
import com.larsons.engine.watch.life.MutantVoice;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the walk can hear — <b>and it is only ever the three mutants.</b>
 *
 * <p>Client-side, and entirely so. Nothing here changes the world: it watches
 * the {@link WatchView} the host has already filled in and decides what that
 * means for the speakers. A sound that came over the wire as an event would be
 * one more message type and one more thing that can be lost; a sound derived
 * from state that is already replicated cannot disagree with what is on screen.
 *
 * <h2>Turning a world into two speakers</h2>
 *
 * <p>{@link Sounds#playAt} is the engine's one distance-and-pan model and it
 * takes a screen-space offset, because the game it was written for is a
 * side-scroller: {@code dx} is "how far to the right of the listener" and
 * {@code halfWidth} is half a viewport. A first-person walk has no such thing —
 * what it has is a position in the world and a direction the player is facing —
 * so {@link #at} rotates the world delta into the listener's own frame and hands
 * the result over. Everything in this game fades and pans through that one call,
 * exactly as every mob and meteor in the block world does.
 *
 * <h2>Why it remembers things</h2>
 *
 * <p>Three of the five voices are <em>edges</em> rather than states: a creature
 * arriving, a creature noticing you, a shard landing. The snapshot says what is
 * true now, not what changed, so this keeps the little that is needed to spot a
 * change — which mutants were alive last frame, which were hunting, which shards
 * were in the air — and nothing else.
 */
public final class WatchSounds {

    /**
     * How far a mutant's arrival cry carries, in metres.
     *
     * <p>Deliberately further than one is ever <em>drawn</em>: the render ring
     * on a card is around five hundred metres and on the painter far less, and
     * the whole job of this sound is to be the thing you hear when there is
     * nothing to see. A player who hears it and looks up at an empty treeline is
     * a player who has understood the situation exactly.
     */
    public static final double CALL_REACH = 900;

    /** How far the rest of it carries. */
    private static final double NEAR_REACH = 90;

    /**
     * The distance at which a sound is fully panned to one side, in metres.
     *
     * <p>{@link Sounds#playAt}'s {@code halfWidth}. Twenty metres, which is
     * about the distance at which one of these stops being a thing over there
     * and starts being a thing about to reach you — so the pan is hardest
     * exactly when knowing which side it is on matters most.
     */
    private static final double PAN_METRES = 20;

    /** How far one walks between footfalls, in metres. */
    private static final double STRIDE = 3.2;

    private final Set<Long> alive = new HashSet<>();
    private final Set<Long> hunting = new HashSet<>();
    private final Set<Long> striking = new HashSet<>();
    private final Set<Long> shards = new HashSet<>();

    /** Where each shard was last seen, and what threw it — see {@link #hurls}. */
    private final Map<Long, double[]> lastShard = new HashMap<>();

    private final Map<Long, String> shardSpecies = new HashMap<>();

    /** How far each creature has walked since its last footfall. */
    private final Map<Long, Double> strides = new HashMap<>();

    /** …and where each was last frame, to measure that by. */
    private final Map<Long, double[]> wasAt = new HashMap<>();

    /** Forget everything — what entering a walk does. */
    public void clear() {
        alive.clear();
        hunting.clear();
        striking.clear();
        shards.clear();
        strides.clear();
        wasAt.clear();
        lastShard.clear();
        shardSpecies.clear();
    }

    /**
     * Look at what is on screen and make whatever noise that implies.
     *
     * @param view  what the host says is out there
     * @param px    where the listener is
     * @param yaw   which way they are facing — the engine's convention, so
     *              forward is {@code (sin yaw, −cos yaw)}
     */
    public void update(WatchView view, double px, double py, double yaw) {
        if (view == null) return;
        Set<Long> seen = new HashSet<>();
        Set<Long> chasing = new HashSet<>();

        for (WatchView.Creature creature : view.creatures()) {
            Mutants.Kind kind = Mutants.of(creature.def());
            if (kind == null) continue;
            long id = creature.id();
            seen.add(id);

            // Arriving. The one sound that carries across the whole world, and
            // the only warning a player gets — see CALL_REACH.
            if (alive.add(id)) {
                at(MutantVoice.key(kind, MutantVoice.CALL), creature.x() - px,
                        creature.y() - py, yaw, CALL_REACH, 1.0);
            }

            // Having noticed somebody. Derived from the animation state rather
            // than from a flag: a mutant that is running is a mutant that has
            // decided, and the host already replicates that.
            boolean coming = creature.state() == com.larsons.engine.watch.life.AnimState.RUN;
            if (coming) chasing.add(id);
            if (coming && !hunting.contains(id)) {
                at(MutantVoice.key(kind, MutantVoice.NOTICE), creature.x() - px,
                        creature.y() - py, yaw, NEAR_REACH, 1.0);
            }

            // A swing landing. STRIKE is only ever entered by a blow, so the
            // edge into it is the blow.
            boolean swinging =
                    creature.state() == com.larsons.engine.watch.life.AnimState.STRIKE;
            if (swinging && !striking.contains(id)) {
                at(MutantVoice.key(kind, MutantVoice.STRIKE), creature.x() - px,
                        creature.y() - py, yaw, NEAR_REACH, 1.0);
            }
            if (swinging) striking.add(id);
            else striking.remove(id);

            footfalls(kind, creature, px, py, yaw);
        }

        // What was here and is not any more: it has been dropped, killed the
        // player, or simply walked out of the ring. Nothing is played for it —
        // a creature that leaves does so quietly, which is worse.
        alive.retainAll(seen);
        hunting.clear();
        hunting.addAll(chasing);
        striking.retainAll(seen);
        strides.keySet().retainAll(seen);
        wasAt.keySet().retainAll(seen);

        hurls(view, px, py, yaw);
    }

    /**
     * A footfall every {@link #STRIDE} metres of ground covered.
     *
     * <p><b>Per metre walked, not per second</b>, which is the same rule the
     * track system lays prints by and is right for the same reason: something
     * that is standing still is not walking, and something sprinting is not
     * taking the same number of steps as something ambling. It also means the
     * footfalls speed up when a mutant does, with no extra state.
     */
    private void footfalls(Mutants.Kind kind, WatchView.Creature creature,
                           double px, double py, double yaw) {
        long id = creature.id();
        double[] last = wasAt.get(id);
        wasAt.put(id, new double[]{creature.x(), creature.y()});
        if (last == null) return;
        double covered = Math.hypot(creature.x() - last[0], creature.y() - last[1]);
        // A snapshot's worth of teleport — a respawn, a fresh spawn reusing an
        // id — is not a stride. Anything past a couple of metres in one frame is
        // not something that walked there.
        if (covered > 2.5) return;
        double walked = strides.getOrDefault(id, 0.0) + covered;
        while (walked >= STRIDE) {
            walked -= STRIDE;
            at(MutantVoice.key(kind, MutantVoice.STEP), creature.x() - px,
                    creature.y() - py, yaw, NEAR_REACH, 0.7);
        }
        strides.put(id, walked);
    }

    /**
     * A shard leaving and a shard landing.
     *
     * <p>Both are edges on a set that the snapshot replaces wholesale, so
     * appearing in it is a throw and disappearing from it is an arrival. The
     * second is a small lie and a useful one: a shard also leaves the set by
     * timing out or hitting the ground, and those sound the same as a hit from
     * anywhere but underneath it.
     */
    private void hurls(WatchView view, double px, double py, double yaw) {
        Set<Long> flying = new HashSet<>();
        for (var hurl : view.hurls()) {
            flying.add(hurl.id());
            Mutants.Kind kind = Mutants.of(
                    com.larsons.engine.watch.life.AnimalRegistry.byKey(hurl.species()));
            if (kind == null || !shards.add(hurl.id())) continue;
            at(MutantVoice.key(kind, MutantVoice.HURL), hurl.x() - px, hurl.y() - py,
                    yaw, NEAR_REACH, 1.0);
        }
        for (Long gone : List.copyOf(shards)) {
            if (flying.contains(gone)) continue;
            // Where it was last seen is the best guess at where it stopped, and
            // the last snapshot that carried it is at most a tick old.
            double[] at = lastShard.get(gone);
            if (at != null) {
                Mutants.Kind kind = Mutants.of(com.larsons.engine.watch.life.AnimalRegistry
                        .byKey(shardSpecies.getOrDefault(gone, "")));
                if (kind != null) {
                    at(MutantVoice.key(kind, MutantVoice.IMPACT), at[0] - px,
                            at[1] - py, yaw, NEAR_REACH, 1.0);
                }
            }
            lastShard.remove(gone);
            shardSpecies.remove(gone);
        }
        shards.retainAll(flying);
        for (var hurl : view.hurls()) {
            lastShard.put(hurl.id(), new double[]{hurl.x(), hurl.y()});
            shardSpecies.put(hurl.id(), hurl.species());
        }
    }

    /**
     * Play one sound at a point in the world, heard from a listener facing a
     * direction.
     *
     * <p>The rotation is the whole of this method. The engine's forward vector
     * is {@code (sin yaw, −cos yaw)} and its right is that turned a quarter
     * clockwise, so the component of the offset along the right is what
     * {@link Sounds#playAt} wants for {@code dx} and the component along forward
     * is what it wants for {@code dy}. Get the two the wrong way round and a
     * creature directly behind the player is heard hard left.
     */
    private void at(String key, double dx, double dy, double yaw, double reach,
                    double volume) {
        double forwardX = Math.sin(yaw), forwardY = -Math.cos(yaw);
        double rightX = -forwardY, rightY = forwardX;
        double across = dx * rightX + dy * rightY;
        double along = dx * forwardX + dy * forwardY;
        double distance = Math.hypot(dx, dy);
        if (distance > reach) return;
        // Scaled so that a sound at the edge of its own reach is silent rather
        // than cut off: playAt's falloff is tuned for a viewport and these
        // reaches are hundreds of metres.
        double fade = 1 - distance / reach;
        Sounds.playAt(key, across, along, PAN_METRES, volume * fade * fade);
    }
}
