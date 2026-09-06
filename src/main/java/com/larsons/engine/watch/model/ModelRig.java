package com.larsons.engine.watch.model;

import com.larsons.engine.watch.life.AnimState;
import com.larsons.engine.watch.life.AnimalModel;
import com.larsons.engine.watch.life.Blockbench;

/**
 * What a bone's <b>name</b> means, and what a joint does when nothing animates it.
 *
 * <h2>Names are the whole contract</h2>
 *
 * <p>An imported model binds to this game through the names an artist gave the
 * bones and nothing else — no manifest, no sidecar, no naming a file after a
 * numbered slot. Call a bone {@code head} and it is the head; call it
 * {@code arm_l} and it is the left arm; call it {@code frobnicator} and it
 * inherits whatever its parent was, which is almost always right. That rule is
 * already how {@link Blockbench} works and this is deliberately the same rule,
 * extended to the two things a {@code .bbmodel} could not describe: a biped's
 * arms, and a skeleton whose bones came out of Blender rather than out of a box
 * editor.
 *
 * <h2>Two vocabularies</h2>
 *
 * <p>{@link Kind#CREATURE} is the animal one — four legs, a pair of wings, a
 * tail — and it is {@link Blockbench#jointOf} exactly, so a {@code .glb} wren
 * and a {@code .bbmodel} wren bind identically. {@link Kind#HUMANOID} is for
 * people: two arms, two legs, and no wings.
 *
 * <p><b>A biped's arms are {@code WING_L} and {@code WING_R}.</b> That reads
 * oddly and it is the right call: {@link AnimalModel.Joint} is the one joint
 * vocabulary the renderer, the picker and the field guide all speak, and adding
 * a parallel humanoid enum would mean every one of them growing a branch to ask
 * which kind of skeleton it was holding. {@code WING_*} is the upper limb pair.
 * The names an artist types are {@code arm}, {@code hand}, {@code shoulder} —
 * nobody has to know.
 */
public final class ModelRig {

    /** Which vocabulary a model's bone names are read in. */
    public enum Kind {
        /** Four legs, wings, a tail — {@link Blockbench}'s own names. */
        CREATURE,
        /** Two arms, two legs, a head. People. */
        HUMANOID
    }

    private ModelRig() {}

    /**
     * The joint a bone name means, or {@code null} when it names none — in
     * which case the caller gives it its parent's joint, and a root that
     * matches nothing is the body.
     */
    public static AnimalModel.Joint jointOf(String boneName, Kind kind) {
        if (boneName == null) return null;
        if (kind == Kind.CREATURE) return Blockbench.jointOf(boneName);

        String n = boneName.toLowerCase().replace('-', '_').replace(' ', '_').replace('.', '_');
        // Blender's mirror modifier and its rigify names both end a side with
        // .L/.R, which the dot-folding above has already turned into _l/_r.
        boolean left = n.contains("left") || n.endsWith("_l") || n.contains("_l_");
        boolean right = n.contains("right") || n.endsWith("_r") || n.contains("_r_");

        if (n.contains("arm") || n.contains("hand") || n.contains("shoulder")
                || n.contains("elbow") || n.contains("wrist") || n.contains("finger")
                || n.contains("clavicle")) {
            return right ? AnimalModel.Joint.WING_R : AnimalModel.Joint.WING_L;
        }
        if (n.contains("leg") || n.contains("foot") || n.contains("thigh")
                || n.contains("shin") || n.contains("knee") || n.contains("ankle")
                || n.contains("boot") || n.contains("toe")) {
            return right ? AnimalModel.Joint.LEG_FR : AnimalModel.Joint.LEG_FL;
        }
        if (n.contains("head") || n.contains("skull") || n.contains("neck")
                || n.contains("face") || n.contains("jaw") || n.contains("eye")
                || n.contains("hair") || n.contains("hat") || n.contains("brim")) {
            return AnimalModel.Joint.HEAD;
        }
        if (n.contains("ear")) return AnimalModel.Joint.EAR;
        if (n.contains("tail") || n.contains("pack") || n.contains("bedroll")) {
            return AnimalModel.Joint.TAIL;
        }
        if (n.contains("body") || n.contains("torso") || n.contains("chest")
                || n.contains("spine") || n.contains("hip") || n.contains("pelvis")
                || n.contains("root") || n.contains("coat") || n.contains("belt")) {
            return AnimalModel.Joint.BODY;
        }
        return null;
    }

    /**
     * How a joint sits when the model supplied no clip for a state.
     *
     * <p><b>This is what makes a half-finished model worth committing.</b> A
     * file with an {@code idle} and a {@code walk} still has eight other states
     * to be drawn in, and the alternative to a fallback is a ranger who stands
     * bolt upright the moment they start running. Same rule as
     * {@code Blockbench}'s, one level up: there it was per joint within a clip,
     * here it is per state within a model.
     */
    public static AnimalModel.Pose poseOf(Kind kind, AnimState state,
                                          AnimalModel.Joint joint, double phase) {
        return kind == Kind.CREATURE
                ? AnimalModel.pose(state, joint, phase)
                : humanoid(state, joint, phase);
    }

    /**
     * The stand-in animation for a person.
     *
     * <p>Not the animal table with different numbers: run that on a biped and
     * the arms fold like wings, because {@code WING_*}'s resting pose is folded
     * against a flank. This is the keeper's vocabulary instead — breathe, shift,
     * swing opposite arms and legs — which is the one this game already
     * establishes for people standing about.
     */
    private static AnimalModel.Pose humanoid(AnimState state, AnimalModel.Joint joint,
                                             double phase) {
        double wave = Math.sin(phase * Math.PI * 2);
        double counter = -wave;
        return switch (state) {
            case WALK, RUN -> {
                double reach = state == AnimState.RUN ? 0.95 : 0.55;
                yield switch (joint) {
                    // Opposite arm to leg, which is the one thing a walk cycle
                    // cannot get wrong and still look like walking.
                    case LEG_FL -> new AnimalModel.Pose(wave * reach, 0, 0);
                    case LEG_FR -> new AnimalModel.Pose(counter * reach, 0, 0);
                    case WING_L -> new AnimalModel.Pose(counter * reach * 0.7, 0, 0);
                    case WING_R -> new AnimalModel.Pose(wave * reach * 0.7, 0, 0);
                    case BODY -> new AnimalModel.Pose(0, wave * 0.05,
                            Math.abs(wave) * 0.015);
                    case HEAD -> new AnimalModel.Pose(wave * 0.04, 0, 0);
                    default -> AnimalModel.Pose.REST;
                };
            }
            case FORAGE -> switch (joint) {
                // Crouched over whatever they are looking at.
                case BODY -> AnimalModel.Pose.full(0.42, 0, 0, 0, 0, -0.06);
                case HEAD -> new AnimalModel.Pose(0.30 + wave * 0.05, 0, 0);
                case WING_L, WING_R -> new AnimalModel.Pose(0.55, 0, 0);
                default -> AnimalModel.Pose.REST;
            };
            case ALERT -> switch (joint) {
                case HEAD -> new AnimalModel.Pose(-0.16, 0, 0);
                default -> AnimalModel.Pose.REST;
            };
            case SLEEP -> switch (joint) {
                case HEAD -> new AnimalModel.Pose(0.34, 0, 0);
                case BODY -> AnimalModel.Pose.full(0.10, 0, 0, 0, 0, -0.04);
                default -> AnimalModel.Pose.REST;
            };
            case CALL -> switch (joint) {
                case HEAD -> new AnimalModel.Pose(-0.20 + wave * 0.06, 0, 0);
                case WING_R -> new AnimalModel.Pose(-0.9, 0, 0);
                default -> AnimalModel.Pose.REST;
            };
            case STRIKE -> switch (joint) {
                // One swing, forward and back, rather than a cycle either side
                // of rest — see AnimalModel's `swing`.
                case WING_R -> new AnimalModel.Pose(
                        -(0.5 - 0.5 * Math.cos(phase * Math.PI * 2)) * 2.1, 0, 0);
                case BODY -> new AnimalModel.Pose(0, 0.18 * wave, 0);
                default -> AnimalModel.Pose.REST;
            };
            // IDLE, TAME, FLY: a person standing, breathing. A slow rise in the
            // chest and the smallest sway, because absolute stillness is the
            // thing that reads as a statue.
            default -> switch (joint) {
                case BODY -> AnimalModel.Pose.full(0, 0, wave * 0.012, 0, 0, wave * 0.004);
                case HEAD -> new AnimalModel.Pose(wave * 0.03, 0, 0);
                case WING_L -> new AnimalModel.Pose(wave * 0.03, 0, 0);
                case WING_R -> new AnimalModel.Pose(counter * 0.03, 0, 0);
                default -> AnimalModel.Pose.REST;
            };
        };
    }
}
