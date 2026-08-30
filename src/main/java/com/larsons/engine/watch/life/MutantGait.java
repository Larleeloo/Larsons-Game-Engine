package com.larsons.engine.watch.life;

import com.larsons.engine.watch.life.AnimalModel.Joint;
import com.larsons.engine.watch.life.AnimalModel.Pose;

/**
 * How the three mutants move — <b>and the whole point is that they do not move
 * like animals.</b>
 *
 * <h2>Why they cannot share the animal poses</h2>
 *
 * <p>{@link AnimalModel#pose} is a good walk cycle. Opposite legs swing out of
 * phase by exactly half a turn, the body rises on each footfall, the head bobs a
 * little, and it is <em>symmetrical</em> — which is what a deer looks like and
 * why a deer looks fine. Run that same cycle on a six-metre biped and you get a
 * six-metre biped going for a pleasant walk.
 *
 * <p>Everything that makes a gait unsettling is a broken symmetry, and each one
 * below is a specific one:
 *
 * <ul>
 *   <li><b>The legs are not out of phase by half.</b> They are out by
 *       {@link #LIMP} of a turn, so one leg is always a fraction late — which
 *       reads as a limp the eye cannot quite time, rather than as damage.</li>
 *   <li><b>The two sides are not the same length of stride.</b> One side swings
 *       further than the other, held constant per creature, so it walks in a
 *       very slight curve it is forever correcting.</li>
 *   <li><b>The torso counter-rotates against the legs and lags them.</b> A real
 *       spine leads; these follow, a quarter-turn behind, which is what a body
 *       being <em>carried</em> by its legs looks like rather than one driving
 *       them.</li>
 *   <li><b>The head is on a slower clock than the feet</b> — a third of the
 *       stride rate, drifting, never landing on the beat. A head that nods in
 *       time with the footfalls reads as a horse; one that does not reads as
 *       something that is not paying attention to its own walking.</li>
 *   <li><b>The arms hang and swing wide rather than pumping.</b> Dead weight on
 *       a hinge, overshooting at the ends of the swing, because the creature is
 *       not using them for balance.</li>
 * </ul>
 *
 * <p>None of these is expensive: a pose is three angles and a lift, and this
 * file is arithmetic on a phase. What they buy is the difference between a
 * monster and a large animal, and that is the whole of what the three of them
 * are for.
 *
 * <h2>How it is reached</h2>
 *
 * <p>{@link AnimalModel.PoseSource} is the seam an imported Blockbench model
 * already uses, so a mutant is simply a species whose poses come from somewhere
 * other than the shared table — the renderer, the field guide and the picking
 * ray never learn that anything is different. A mutant with a hand-made
 * {@code .bbmodel} still overrides this, exactly as it overrides the geometry.
 */
public final class MutantGait implements AnimalModel.PoseSource {

    /**
     * How far out of phase the two legs are, in turns.
     *
     * <p><b>Not 0.5</b>, which is what every animal in the game uses and what
     * walking actually is. At 0.5 the two feet are perfectly opposed and the
     * gait is even; at 0.43 one foot lands a fourteenth of a stride early, for
     * ever, and the limp never resolves into a rhythm you can tap along to.
     * That is the single most effective line in this file.
     */
    private static final double LIMP = 0.43;

    /** How much slower the head's clock runs than the feet's. */
    private static final double HEAD_DRIFT = 0.34;

    /** How much longer one side's stride is than the other's. */
    private static final double ASYMMETRY = 0.22;

    private final Mutants.Power power;

    /**
     * A per-creature offset, so the three of them are not in step with each
     * other when two are on screen — which happens exactly once, in debug mode,
     * and would look absurd.
     */
    private final double seed;

    private MutantGait(Mutants.Power power, double seed) {
        this.power = power;
        this.seed = seed;
    }

    /** The gait for one mutant. */
    public static MutantGait of(Mutants.Kind kind) {
        return new MutantGait(kind.power(),
                Math.abs(kind.key().hashCode() % 1000) / 1000.0);
    }

    @Override
    public Pose poseOf(AnimState state, Joint joint, double phase) {
        double t = phase + seed;
        return switch (state) {
            case WALK -> walk(joint, t, 1.0);
            // A run is the same broken gait, wound up: longer strides, a deeper
            // forward lean, and the arms coming up. Deliberately the same shape
            // rather than a new one — a creature whose walk and run are
            // unrelated reads as two creatures.
            case RUN -> walk(joint, t, 1.75);
            case STRIKE -> strike(joint, t);
            case IDLE, TAME -> idle(joint, t);
            case ALERT -> alert(joint, t);
            // Nothing here is ever asked for these: a mutant does not sleep
            // where a player can see it, does not forage, does not call, and
            // cannot fly. Falling back to the shared table is the right answer
            // for a state that should not happen, and is what an imported model
            // does for a clip it does not carry.
            default -> AnimalModel.pose(state, joint, phase);
        };
    }

    /**
     * The walk, and the run, which is the walk with the numbers turned up.
     *
     * @param reach how far everything swings, {@code 1} walking
     */
    private Pose walk(Joint joint, double t, double reach) {
        double left = wave(t);
        // The other leg, late — see LIMP.
        double right = wave(t + LIMP);
        // The torso's own clock, a quarter turn behind the feet.
        double body = wave(t - 0.25);
        double head = wave(t * HEAD_DRIFT);
        // One side strides further than the other, for ever.
        double longSide = 1 + ASYMMETRY;
        double shortSide = 1 - ASYMMETRY;

        return switch (joint) {
            // Legs. The hind pair walk; note that a run does not simply scale
            // the lift, because a foot that leaves the ground by a metre reads
            // as a hop rather than as a stride.
            case LEG_BL -> new Pose(left * 0.52 * reach * longSide, 0,
                    Math.max(0, left) * 0.02 * reach);
            case LEG_BR -> new Pose(right * 0.52 * reach * shortSide, 0,
                    Math.max(0, right) * 0.02 * reach);
            // Arms: dead weight, out of phase with the leg on their own side,
            // and swinging wider than a walking figure's would.
            case LEG_FL -> new Pose(right * 0.44 * reach * shortSide,
                    -0.06 - Math.abs(right) * 0.10, -0.012 * reach);
            case LEG_FR -> new Pose(left * 0.44 * reach * longSide,
                    0.06 + Math.abs(left) * 0.10, -0.012 * reach);
            // The trunk: a forward lean that deepens with the pace, a roll that
            // lags the feet, and a sway that does not line up with either.
            case BODY -> new Pose(-0.06 * reach + body * 0.05,
                    wave(t - 0.25 + 0.5) * 0.03, body * 0.055 * reach,
                    body * 0.09);
            // The head, on its own slow clock, lolling.
            case HEAD -> new Pose(0.10 + head * 0.13, head * 0.16,
                    head * 0.07, 0);
            case TAIL -> new Pose(-0.18 + body * 0.20, body * 0.12, 0);
            case EAR -> new Pose(-0.14 + head * 0.22, 0, 0);
            case HORN -> new Pose(head * 0.04, head * 0.05, 0);
            default -> Pose.REST;
        };
    }

    /**
     * Standing about, which for these three is not standing still.
     *
     * <p>A very slow breath, a head that keeps turning to look at something
     * that is not there, and — the detail that does the work — a body that
     * <em>shudders</em> once every few cycles rather than swaying evenly.
     */
    private Pose idle(Joint joint, double t) {
        double breath = wave(t * 0.5);
        double look = wave(t * 0.19);
        // A twitch: near zero most of the cycle, and a sharp spike in it.
        double twitch = Math.pow(Math.max(0, wave(t * 0.37)), 12);
        return switch (joint) {
            case BODY -> new Pose(breath * 0.02 + twitch * 0.06, 0,
                    breath * 0.012, 0);
            case HEAD -> new Pose(0.06 + look * 0.20 - twitch * 0.24,
                    look * 0.42, 0, 0);
            case LEG_FL -> new Pose(0.05 + breath * 0.05, -0.08, 0);
            case LEG_FR -> new Pose(0.05 - breath * 0.05, 0.08, 0);
            case TAIL -> new Pose(-0.10 + breath * 0.10, 0, 0);
            case EAR -> new Pose(-0.10 + twitch * 0.5, 0, 0);
            default -> Pose.REST;
        };
    }

    /**
     * Having noticed somebody — the pose between standing and coming.
     *
     * <p>Head up and level, weight forward, arms drawn back. It is the one
     * moment in an encounter where the creature is still, and it is meant to be
     * the moment a player decides what to do.
     */
    private Pose alert(Joint joint, double t) {
        double tremor = wave(t * 2.6);
        return switch (joint) {
            case BODY -> new Pose(-0.16, tremor * 0.02, 0.02, 0);
            case HEAD -> new Pose(-0.22 + tremor * 0.03, tremor * 0.05, 0.02, 0);
            case LEG_FL -> new Pose(-0.34, -0.16, 0);
            case LEG_FR -> new Pose(-0.34, 0.16, 0);
            case TAIL -> new Pose(-0.42, 0, 0);
            case EAR -> new Pose(-0.44, 0, 0);
            default -> Pose.REST;
        };
    }

    /**
     * The blow — <b>and it is slow on purpose.</b>
     *
     * <p>These three now hold a sprinting player's pace, which only works as a
     * design if being caught is survivable; what makes it survivable is seconds
     * between blows, and what makes those seconds <em>readable</em> is a wind-up
     * a player can see from behind. So the arms go up over the first two thirds
     * of the cycle and come down over the last third, rather than sweeping
     * evenly: the pose says "now" before the damage does.
     *
     * <p>An {@link Mutants.Power#AMBUSH} striker swings with both arms at once
     * and the others alternate, which is the one place the three differ here —
     * the mirewraith has four arms and no reason to be tidy about them.
     */
    private Pose strike(Joint joint, double t) {
        double turn = t - Math.floor(t);
        // Slow up, fast down: a raised cosine over the first two thirds, then a
        // drop. `swing` is 0 at rest, 1 at the top, and back through 0.
        double raise = turn < 0.66 ? 0.5 - 0.5 * Math.cos(turn / 0.66 * Math.PI)
                : 1 - (turn - 0.66) / 0.34;
        boolean together = power == Mutants.Power.AMBUSH;
        double other = together ? raise
                : Math.max(0, 0.5 - 0.5 * Math.cos((turn + 0.5) % 1 / 0.66 * Math.PI));
        return switch (joint) {
            case LEG_FL -> new Pose(-1.5 * raise + 0.4, -0.30 - raise * 0.35, 0);
            case LEG_FR -> new Pose(-1.5 * other + 0.4, 0.30 + other * 0.35, 0);
            // Braced: the legs plant and the body drives through the swing.
            case LEG_BL -> new Pose(0.26, 0, 0);
            case LEG_BR -> new Pose(-0.26, 0, 0);
            case BODY -> new Pose(-0.22 + raise * 0.40, 0, -raise * 0.05, 0);
            case HEAD -> new Pose(-0.44 + raise * 0.62, 0, 0, 0);
            case TAIL -> new Pose(-0.36, 0, 0);
            case EAR -> new Pose(-0.5, 0, 0);
            case HORN -> new Pose(0.06, 0, 0);
            default -> Pose.REST;
        };
    }

    private static double wave(double turns) {
        return Math.sin(turns * Math.PI * 2);
    }
}
