package com.larsons.engine.watch.render;

import com.larsons.engine.watch.WatchView;
import com.larsons.engine.watch.life.AnimalRegistry;
import com.larsons.engine.watch.life.AnimalSkins;
import com.larsons.engine.watch.life.Hurl;
import com.larsons.engine.watch.life.Mutants;
import com.larsons.engine.watch.world.WatchMaterial;
import com.larsons.engine.watch.world.WatchMaterials;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Embers — <b>the only particles in this game, and there is only one thing that
 * throws them.</b>
 *
 * <h2>Why this is not the engine's particle system</h2>
 *
 * <p>{@code graphics/Particles} is a good particle system for the game it was
 * written for: it draws sprites in <em>screen space</em> for a side-scroller,
 * with styles, textures and a 2D velocity. The Field Guide draws a first-person
 * world by handing triangles to {@link WatchRenderer}, and a screen-space sprite
 * has nowhere to live in it — an ember that does not sort against the terrain
 * behind it is an ember that shines through hillsides.
 *
 * <p>So an ember here is what everything else here is: a very small box, in the
 * world, in the frame's moving mesh. It costs six quads, it occludes correctly,
 * it fogs correctly, and it needed no renderer changes at all.
 *
 * <h2>What it is for</h2>
 *
 * <p>A bone shard crosses forty metres in under two seconds. At that speed a
 * plain sliver is a thing a player sees <em>after</em> it has hit them, which
 * makes the wendigo's whole attack feel like unexplained damage. What fixes that
 * is not a bigger shard — it is a <b>trail</b>, because a trail is visible along
 * the path the shard has already taken and tells you where it came from as well
 * as where it is. And a burst on impact, because a projectile that simply stops
 * existing reads as a rendering fault rather than as a thing that landed.
 *
 * <h2>Client-side, and derived rather than told</h2>
 *
 * <p>Nothing about an ember travels. This watches the same replicated shard
 * positions the renderer draws — see {@link #follow} — and works out the trail
 * and the impact from them, exactly as {@code WatchSounds} works out the noises.
 * A particle that arrived as a message would be one more thing that can be lost;
 * one derived from the shard's own position cannot disagree with where the shard
 * is.
 */
public final class Sparks {

    /**
     * How many embers exist at once, at most.
     *
     * <p>Six hundred is about four shards' worth of trail plus a burst, and it
     * is a ceiling rather than a target: the oldest are dropped first, so a
     * pathological case degrades into a shorter trail rather than into a slow
     * frame. At six quads each that is a few thousand triangles, against the
     * tens of thousands a single chunk of ground costs.
     */
    private static final int LIMIT = 600;

    /** How many embers a shard sheds per second of flight. */
    private static final double TRAIL_RATE = 90;

    /** …and how many it throws off when it lands. */
    private static final int BURST = 40;

    /** How long a trail ember lasts, in seconds. */
    private static final double TRAIL_LIFE = 0.55;

    /** …and an impact one, which lingers. */
    private static final double BURST_LIFE = 1.1;

    /** How fast an ember falls, in metres per second per second. */
    private static final double GRAVITY = 3.4;

    /** How big one starts, in metres. */
    private static final double SIZE = 0.075;

    /**
     * One ember.
     *
     * <p>A record would be tidier and is the wrong shape: these are stepped
     * every frame and there are hundreds, so they are mutable and reused rather
     * than reallocated.
     */
    private static final class Ember {
        double x, y, z, vx, vy, vz, age, life, size;
        int colour;
    }

    private final List<Ember> embers = new ArrayList<>();

    /** Where each shard was last frame, to lay a trail between. */
    private final Map<Long, double[]> was = new HashMap<>();

    /** …and what threw it, so an impact burst is the right colour. */
    private final Map<Long, String> species = new HashMap<>();

    /**
     * Deterministic, and seeded once.
     *
     * <p>Embers are pure decoration and never leave this machine, so there is
     * nothing to keep in step with anybody — but a fixed seed makes a recorded
     * frame reproducible, which is what lets a golden test look at one.
     */
    private final Random rng = new Random(0x5A5B);

    /** How much of an ember is owed but not yet emitted, per shard. */
    private final Map<Long, Double> owed = new HashMap<>();

    /** Forget everything — what entering a walk does. */
    public void clear() {
        embers.clear();
        was.clear();
        species.clear();
        owed.clear();
    }

    /** How many are alive; for the debug readout and a test. */
    public int count() { return embers.size(); }

    /**
     * Lay trails behind everything in the air, and burst whatever has landed.
     *
     * <p>The trail is emitted <em>along the segment the shard covered this
     * frame</em> rather than at the point it reached. At twenty-four metres a
     * second a shard moves more than a metre between frames, so emitting at the
     * point gives a dotted line of clumps that flickers with the frame rate;
     * spreading the same embers along the segment gives a continuous streak at
     * any frame rate, which is the same reasoning that made the shard's own hit
     * test measure a segment.
     */
    public void follow(WatchView view, double dt) {
        if (view == null) return;
        Set<Long> flying = new HashSet<>();
        for (Hurl hurl : view.hurls()) {
            long id = hurl.id();
            flying.add(id);
            species.put(id, hurl.species());
            int hot = glowOf(hurl.species());
            double[] from = was.get(id);
            was.put(id, new double[]{hurl.x(), hurl.y(), hurl.z()});
            if (from == null) continue;

            double owe = owed.getOrDefault(id, 0.0) + TRAIL_RATE * dt;
            int many = (int) owe;
            owed.put(id, owe - many);
            for (int i = 0; i < many; i++) {
                double t = (i + 0.5) / Math.max(1, many);
                spark(from[0] + (hurl.x() - from[0]) * t,
                        from[1] + (hurl.y() - from[1]) * t,
                        from[2] + (hurl.z() - from[2]) * t,
                        0.6, TRAIL_LIFE, SIZE, hot);
            }
        }

        // Anything that was in the air and is not any more has arrived
        // somewhere — a person, the ground, or the end of its life. All three
        // look the same from here and all three deserve a burst.
        for (Long gone : List.copyOf(was.keySet())) {
            if (flying.contains(gone)) continue;
            double[] at = was.remove(gone);
            String threw = species.remove(gone);
            owed.remove(gone);
            if (at == null) continue;
            int hot = glowOf(threw);
            for (int i = 0; i < BURST; i++) {
                spark(at[0], at[1], at[2], 4.5, BURST_LIFE, SIZE * 1.3, hot);
            }
        }

        advance(dt);
    }

    /** Move and age everything, and drop what has burned out. */
    private void advance(double dt) {
        for (int i = embers.size() - 1; i >= 0; i--) {
            Ember ember = embers.get(i);
            ember.age += dt;
            if (ember.age >= ember.life) {
                embers.remove(i);
                continue;
            }
            ember.vz -= GRAVITY * dt;
            ember.x += ember.vx * dt;
            ember.y += ember.vy * dt;
            ember.z += ember.vz * dt;
            // Air: they slow quickly, which is what makes a streak hang where it
            // was laid rather than following the shard along.
            double drag = Math.max(0, 1 - dt * 3.2);
            ember.vx *= drag;
            ember.vy *= drag;
        }
    }

    private void spark(double x, double y, double z, double spread, double life,
                       double size, int colour) {
        Ember ember = embers.size() >= LIMIT ? embers.remove(0) : new Ember();
        ember.x = x + (rng.nextDouble() - 0.5) * 0.14;
        ember.y = y + (rng.nextDouble() - 0.5) * 0.14;
        ember.z = z + (rng.nextDouble() - 0.5) * 0.14;
        ember.vx = (rng.nextDouble() - 0.5) * spread;
        ember.vy = (rng.nextDouble() - 0.5) * spread;
        // Biased upward, because an ember rises before it falls and a cloud that
        // only falls reads as gravel.
        ember.vz = (rng.nextDouble() - 0.2) * spread;
        ember.age = 0;
        ember.life = life * (0.6 + rng.nextDouble() * 0.6);
        ember.size = size * (0.6 + rng.nextDouble() * 0.8);
        ember.colour = colour;
        embers.add(ember);
    }

    /**
     * Write every ember into a frame's moving mesh.
     *
     * <p>They <b>shrink</b> as they age rather than fading, and that is a
     * constraint rather than a preference: the mesh they go into is opaque, and
     * an alpha fade would mean a second, translucent mesh sorted against the
     * first. A cube that shrinks to nothing is the same silhouette a fading one
     * has at a distance, for none of the cost.
     */
    public void mesh(Mesh.Builder mesh, double ox, double oy) {
        if (embers.isEmpty()) return;
        float[] uv = new float[4];
        WatchMaterials.uv(WatchMaterial.BARK, uv);
        for (Ember ember : embers) {
            double left = 1 - ember.age / ember.life;
            double half = ember.size * left * left;
            if (half < 0.004) continue;
            Shapes.box(mesh, ember.x - ox, ember.y - oy, ember.z, half, half, half,
                    0, uv, ember.colour);
        }
    }

    /**
     * What colour a given species' embers are.
     *
     * <p>Its {@link AnimalSkins.Region#GLOW}, so the sparks off a wendigo's
     * shard are the same fire as the one in its chest. That is not a flourish:
     * it is the only thing that says the shard came from <em>that</em> creature
     * rather than from somewhere else in the dark.
     */
    private static int glowOf(String speciesKey) {
        var def = AnimalRegistry.byKey(speciesKey);
        if (def == null) return 0xFF9A40;
        Mutants.Kind kind = Mutants.of(def);
        return kind == null ? AnimalSkins.regionColour(def, AnimalSkins.Region.HARD)
                : kind.glow();
    }
}
