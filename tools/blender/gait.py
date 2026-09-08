"""
The five clips a walker is ever drawn in, for any figure.

`idle`, `walk`, `run`, `swim` and `row` — and five rather than one or two
because a state with no clip is posed by a procedural table that works per
piece about each bone's own pivot rather than down the hierarchy. At an idle's
0.03 radians nobody can tell; at a run's 0.67 the hand rotates about the wrist
it is still standing at while the arm swings away from the shoulder, and the
two come apart by a third of a metre. Five clips means the fallback never runs
on a player figure at all.

**And now they animate the clothes as well.** A worn piece has no clip of its
own; the engine asks the body where its joints went and carries the garment
along with them (`SceneModel.wornAt`). So everything below is the motion of a
coat, a hat and a pair of boots as much as of the person inside them, which is
the argument for having it in one file rather than one per figure.

Angles are written in **world** axes and turned into each bone's own space by
`kit.pose`, so "swing the leg forward" is a number about global X here and not
a guess about which way a bone's roll happens to point.

    about +X, negative  ->  swings a limb forward, the way the figure faces
    about +Y, positive  ->  swings a limb toward -X
    about +Z, positive  ->  turn toward +X

**The first two are written for a limb, and a limb hangs below its pivot.**
Anything standing *above* its pivot — a chest on a waist, a head on a neck —
goes the other way, because the sign follows which side of the joint the
geometry is on and not which bone it is.
"""

import math

import kit
from kit import pose


def torso(rig, f, frame, pitch=0.0, roll=0.0, yaw=0.0, shift=(0, 0, 0),
          swing=0.0, knee=(0.0, 0.0)):
    """The spine, and the legs that have to undo it.

    **The one thing worth understanding about animating this rig.** The legs
    hang off `spine`, so every breath the chest takes lifts both boots off the
    ground with it and every lean drags them sideways. A 12 mm breath is a
    figure hovering 12 mm over the turf, which is the sort of thing nobody sees
    in Blender and everybody sees in a clearing at dusk. So the legs are given
    the inverse of whatever the spine just did, and the walk's own swing is
    added on top.

    `swing` is the hip swing, positive for the +X leg forward. `knee` is how far
    each knee folds — positive draws the heel up behind, which is the only way
    it bends.
    """
    pose(rig, "spine", frame, pitch=pitch, roll=roll, yaw=yaw, shift=shift)
    back = (-shift[0], -shift[1], -shift[2])
    for leg, shin, foot, side, bend in (("leg_l", "shin_l", "foot_l", 1, knee[0]),
                                        ("leg_r", "shin_r", "foot_r", -1, knee[1])):
        pose(rig, leg, frame, pitch=-pitch - side * swing, roll=-roll, yaw=-yaw,
             shift=back)
        pose(rig, shin, frame, pitch=bend)
        # The ankle undoes everything above it, which keeps the boot flat
        # through the whole stride. A boot that tips instead drives its heel
        # through the floor at the extremes.
        pose(rig, foot, frame, pitch=side * swing - bend)


def idle(rig, f, seconds=5.0):
    """Breathing, and a slow shift of weight from one foot to the other.

    Very small on purpose — a person standing still, not swaying. The two
    cycles are the same length so the whole thing loops on one period, and the
    first and last frames are identical so it loops clean.
    """
    frames = int(seconds * kit.FPS)
    kit.action(rig, "idle")
    kit.rest(rig)
    for i in range(frames + 1):
        frame = i + 1
        t = i / frames
        breath = math.sin(t * math.tau)
        sway = math.sin(t * math.tau)
        look = math.sin(t * math.tau + 1.1)
        pose(rig, "root", frame, shift=(sway * 0.007, 0, 0))
        torso(rig, f, frame, pitch=-0.013 * breath, roll=0.024 * sway,
              shift=(0, 0, 0.006 * breath))
        pose(rig, "head", frame, pitch=0.020 * breath, yaw=0.048 * look,
             roll=-0.014 * sway)
        pose(rig, "arm_l", frame, pitch=0.024 * breath, roll=0.016 * sway)
        pose(rig, "arm_r", frame, pitch=-0.024 * breath, roll=0.016 * sway)
        pose(rig, "hand_l", frame, pitch=0.032 * breath)
        pose(rig, "hand_r", frame, pitch=-0.032 * breath)


def walk(rig, f, seconds=1.0, reach=0.40, fold=0.55, lean=0.028, name="walk"):
    """One stride, opposite arm to opposite leg.

    **The body drops to meet the legs.** A straight leg swung `reach` rad either
    way lifts its own boot `LEG * (1 - cos reach)` clear of the turf, so the
    figure would walk the cycle on stilts and land flat-footed in the middle of
    it. Dropping the root by exactly that much puts both soles back on the floor
    at every frame, and the rise and fall it produces on the way through is the
    bob a walk has anyway — the same bob a hat now has to keep up with.

    **The knee does the clearance**, and does it where a knee does: the swinging
    leg folds as it passes under the body and is straight again at both ends of
    the stride. That timing is `cos`, not `sin` — the quarter-cycle that catches
    people out — and it is also what keeps the drop above exact, because at full
    spread both knees are straight.
    """
    frames = int(seconds * kit.FPS)
    kit.action(rig, name)
    kit.rest(rig)
    for i in range(frames + 1):
        frame = i + 1
        t = i / frames
        wave = math.sin(t * math.tau)
        pass_by = math.cos(t * math.tau)
        stride = abs(wave)
        drop = (f["hip_z"] - f["ankle_z"]) * (1 - math.cos(reach * wave))
        pose(rig, "root", frame, shift=(0, 0, -drop))
        torso(rig, f, frame, pitch=lean, roll=0.028 * pass_by, yaw=0.058 * wave,
              swing=reach * wave,
              knee=(fold * max(0.0, pass_by), fold * max(0.0, -pass_by)))
        pose(rig, "head", frame, yaw=-0.042 * wave,
             pitch=0.020 * stride - lean * 0.6)
        pose(rig, "arm_l", frame, pitch=reach * 0.68 * wave)
        pose(rig, "arm_r", frame, pitch=-reach * 0.68 * wave)
        # The elbow folds on the forward swing and straightens on the back one,
        # which is the asymmetry that stops an arm reading as a pendulum.
        pose(rig, "forearm_l", frame, pitch=-0.24 - reach * 0.45 * min(0.0, wave))
        pose(rig, "forearm_r", frame, pitch=-0.24 + reach * 0.45 * max(0.0, wave))
        pose(rig, "hand_l", frame, pitch=reach * 0.25 * wave)
        pose(rig, "hand_r", frame, pitch=-reach * 0.25 * wave)


def run(rig, f):
    """The same cycle, driven harder. See the file note for why it is a clip."""
    walk(rig, f, seconds=0.7, reach=0.62, fold=0.95, lean=0.082, name="run")


def swim(rig, f, seconds=1.6):
    """Breaststroke — **authored standing up.**

    The clip that looks wrong in Blender and right in the game. A swimmer's body
    angle runs from upright, treading water, through flat on the surface, to
    head-down in a dive, and which of those it is depends on where the player is
    looking — so no keyframe can hold it. The engine tips the whole figure at
    draw time instead (`SceneModel.Lean`), about the hips, and this supplies
    only what the arms and legs do inside that tip.

    So read every pose below as if the figure were already face-down: arms
    overhead is the reach out in front, knees to the chest is the frog kick
    drawing up, and the head lifting is the breath.
    """
    frames = int(seconds * kit.FPS)
    kit.action(rig, "swim")
    kit.rest(rig)
    for i in range(frames + 1):
        frame = i + 1
        t = i / frames
        # 0 is the full reach, 1 is hands pulled back to the chest. Front-loaded
        # so the pull is quick and the glide is long — a breaststroke is mostly
        # waiting, which is what makes it read as swimming rather than flailing.
        pull = 0.5 - 0.5 * math.cos(min(1.0, t / 0.45) * math.tau) if t < 0.45 else 0.0
        # Legs a beat behind the arms: a breaststroke kicks as the arms recover,
        # which is the whole of why it moves anybody anywhere.
        kt = (t - 0.35) / 0.45
        kick = 0.5 - 0.5 * math.cos(min(1.0, max(0.0, kt)) * math.tau)

        pose(rig, "root", frame, shift=(0, 0, 0))
        # Every limb angle here is *local* — measured against the chest rather
        # than the world — because a swimmer's arms belong to their body and not
        # to the horizon. That is the opposite of `torso`'s rule, which exists to
        # keep boots planted on ground this figure is nowhere near.
        pose(rig, "spine", frame, pitch=0.05 - 0.10 * pull)
        for bone in ("shin_l", "shin_r"):
            pose(rig, bone, frame, pitch=1.75 * kick)
        pose(rig, "head", frame, pitch=0.30 - 0.62 * pull)
        for bone, side in (("arm_l", 1), ("arm_r", -1)):
            pose(rig, bone, frame, pitch=-2.55 + 1.40 * pull,
                 roll=-side * (0.10 + 0.55 * math.sin(math.pi * pull)))
        for bone in ("forearm_l", "forearm_r"):
            pose(rig, bone, frame, pitch=-0.10 - 1.30 * pull)
        for bone, side in (("hand_l", 1), ("hand_r", -1)):
            pose(rig, bone, frame, pitch=-0.25 * pull, roll=side * 0.30)
        for bone, side in (("leg_l", 1), ("leg_r", -1)):
            pose(rig, bone, frame, pitch=-0.95 * kick, roll=-side * 0.45 * kick)
        for bone in ("foot_l", "foot_r"):
            pose(rig, bone, frame, pitch=-0.35 * kick)


#: Where a rower's hips sit above the floorboards they brace their feet on.
#: `BoatModel.DEPTH * 0.76` is the thwart above the floor and a seated hip joint
#: is about 110 mm above the plank — the same two numbers the boxed rower is
#: built from, so every figure sits at the same height in the same boat.
SEAT_Z = 0.46


def row(rig, f, seconds=2.2):
    """Sitting to a pair of oars.

    **The one clip measured against furniture rather than anatomy.** A rower is
    folded onto a thwart with their feet on the floorboards, and the gap between
    the two is the boat's. So the root drops to put the hips on the thwart, the
    thighs come forward far enough that a 300 mm shin can still reach the floor,
    and the feet land where the boards are.

    That geometry is why the legs are split at the knee at all: a leg rigged as
    one rigid bone cannot sit down.
    """
    frames = int(seconds * kit.FPS)
    # How far the hips sit below the bone the body swings about. **This is the
    # number that keeps a rower on their seat.** The spine pivots at the waist,
    # 270 mm above the hip joint, so leaning back swings the hips forward off
    # the thwart and takes the braced feet with them — 160 mm of boot skating
    # over the floorboards, once a stroke. The root undoes it.
    perch = f["waist_z"] - f["hip_z"]
    kit.action(rig, "row")
    kit.rest(rig)
    for i in range(frames + 1):
        frame = i + 1
        t = i / frames
        # 0 at the catch (arms out, body forward), 1 at the finish.
        drive = 0.5 - 0.5 * math.cos(t * math.tau)
        swing_back = 0.34 - 0.62 * drive
        pose(rig, "root", frame,
             shift=(0, -perch * math.sin(swing_back),
                    (SEAT_Z - f["waist_z"]) + perch * math.cos(swing_back)))
        pose(rig, "spine", frame, pitch=swing_back)
        # **The legs are furniture and the chest is not.** A fixed-seat boat
        # does not slide, so the thighs hold one angle *against the boat* for
        # the whole stroke while the body swings over them — which means every
        # angle here is a world angle with the spine's swing taken back out of
        # it, and the ankle with the knee's fold out of it as well.
        for bone in ("leg_l", "leg_r"):
            pose(rig, bone, frame, pitch=-1.13 - swing_back)
        for bone in ("shin_l", "shin_r"):
            pose(rig, bone, frame, pitch=0.64)
        for bone in ("foot_l", "foot_r"):
            pose(rig, bone, frame, pitch=0.49)
        # Arms: straight out at the catch, drawn to the ribs at the finish.
        # **The elbow has to come down, not just fold** — left pointing forward
        # while the forearm folds, the hand finishes beside the ear.
        for bone, side in (("arm_l", 1), ("arm_r", -1)):
            pose(rig, bone, frame, pitch=-1.45 + 1.60 * drive, roll=-side * 0.22)
        for bone in ("forearm_l", "forearm_r"):
            pose(rig, bone, frame, pitch=-0.05 - 1.50 * drive)
        for bone, side in (("hand_l", 1), ("hand_r", -1)):
            pose(rig, bone, frame, pitch=0.20, roll=side * 0.15)
        pose(rig, "head", frame, pitch=0.10 - 0.16 * drive)


def all_of_them(rig, f):
    """Every state a walker is drawn in, in the order they are listed."""
    idle(rig, f)
    walk(rig, f)
    run(rig, f)
    swim(rig, f)
    row(rig, f)
