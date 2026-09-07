"""
Build the **wayfarer** — the second figure you can walk as — and export it.

    blender --background --python tools/blender/wayfarer.py

or, from Blender's Scripting tab, Open then Run Script, which leaves the
figure in the scene for you to look at and writes the file as well.

It makes one collection called WAYFARER holding fourteen mesh objects — one
per bone — an armature called `wayfarer_rig`, and five actions: `idle`,
`walk`, `run`, `swim` and `row`. It writes

    src/main/resources/watch/models/characters/wayfarer.glb

--- what this figure is ---------------------------------------------------

The other player. `ranger.py` builds the one this game has always drawn:
square-shouldered, campaign hat, a beard. This is somebody else doing the
same job in the same coat — narrower through the shoulder, a waist cut above
the belt rather than at it, more flare in the skirt, a soft felt hat, and a
plait over one shoulder instead.

**It is a different person, not a smaller one.** Scaling the walker down
produces a child, which is the usual way this goes wrong. Every difference
here is proportional and every one of them is a row in `figures.py`: the
shoulders come in 30 mm while the hips stay put, the ribcage narrows and the
waist rises, the legs take 25 mm off the torso, the head loses 25 mm across
and the neck gains 25 mm of length.

**Exactly 1.78 m to the crown**, like the walker, and that is load-bearing:
an imported character is normalised by its height and redrawn at
`WalkerModel.HEIGHT`, so a figure authored at 1.78 comes out at the metres it
was authored in — which is what lets `cosmetics.py` write a hat at 1.62 and
have it worn at 1.62.

--- the three facts that shape everything here ----------------------------

**Blender's coordinates are the game's coordinates.**

  +Z  up
  -Y  the way the wayfarer faces
  +X  the side `_l` bones go on, and the side the satchel is on

**Colour is materials, not textures.** Every triangle takes its colour from
its material's base colour. So the detail is carried by *how many* flat
colours there are and *which faces* get them, including per-face painting
where one box wants two — the underside of a brim, the hem of a coat.

**Rigid parts, not one skin.** The importer gives each triangle to the one
bone with the most weight across its corners and moves it rigidly. So this is
built the way a wooden mannequin is: separate pieces that overlap at the
joints, each wholly weighted to its own bone. There is nothing to weight-paint.

The contract is `src/main/resources/watch/models/README.md` §8-§15 and §17.
"""

import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import bpy                                                    # noqa: E402
from mathutils import Vector                                  # noqa: E402

import figures                                                # noqa: E402
import kit                                                    # noqa: E402
from kit import Part, mirrored, pose                          # noqa: E402

COLLECTION = "WAYFARER"
RIG = "wayfarer_rig"

F = figures.WAYFARER

#: Where the file goes, relative to the root of the repository.
OUTPUT = "src/main/resources/watch/models/characters/wayfarer.glb"

# --- the landmarks, unpacked ----------------------------------------------
#
# Every one of these is a row of `figures.WAYFARER`, named locally because
# half of them are used three times each and a hand-typed 1.19 in the wrong
# place is a shoulder that does not line up with its own sleeve. Read them
# there; this block only spells them.

SOLE = F["sole"]
ANKLE_Z = F["ankle_z"]
KNEE_Z = F["knee_z"]
HIP_Z = F["hip_z"]
WAIST_Z = F["waist_z"]
CHEST_Z = F["chest_z"]
SHOULDER_Z = F["shoulder_z"]
NECK_Z = F["neck_z"]
HEAD_Z = F["head_z"]
CROWN_Z = F["crown_z"]

HIP_X = F["hip_x"]
SHOULDER_X = F["shoulder_x"]
ELBOW_Z = F["elbow_z"]

HEAD_TOP = F["head_top"]
HEAD_X = F["head_half_x"]
HEAD_Y = F["head_half_y"]
FACE_Y = F["face_y"]
EYE_Z = F["eye_z"]

BRIM_Z = F["hat_brim_z"]
BRIM_R = F["hat_brim_r"]
CROWN_R = F["hat_crown_r"]

COLLAR_Z = F["collar_z"]
NECK_R = F["neck_r"]
CHEST_X = F["chest_half_x"]
CHEST_FRONT = F["chest_front_y"]
CHEST_BACK = F["chest_back_y"]
WAIST_X = F["waist_half_x"]
PACK_BACK = F["pack_back_y"]
HEM_Z = F["hem_z"]
SKIRT_X = F["skirt_half_x"]

HAND = Vector(F["hand"])
HAND_R = F["hand_half"]
BOOT = Vector(F["boot"])
BOOT_X = F["boot_half_x"]
BOOT_Y = F["boot_half_y"]
BOOT_TOP = F["boot_top"]
SHIN_X = F["shin_half_x"]

#: The wrist, which is the hand's own centre with the joint above it.
WRIST = Vector((SHOULDER_X, HAND.y + 0.004, HAND.z + 0.062))

#: The belt, which is not a bone — it is where the coat is cinched.
BELT_Z = 0.815

HEIGHT = CROWN_Z - SOLE

#: Where a rower's hips sit above the floorboards they brace their feet on.
#: `BoatModel.DEPTH * 0.76` is the thwart above the floor and a seated hip
#: joint is about 110 mm above the plank — the same two numbers the boxed
#: rower is built from, so both figures sit at the same height in one boat.
SEAT_Z = 0.46


# --- the wayfarer, bone by bone -------------------------------------------

def head_part():
    """The face, the hair, the plait, and the soft hat over all of it.

    Everything on the head goes on the head bone, plait included. The plait
    hangs forward over the right shoulder rather than down the back, for one
    reason that is entirely about this game: the back already has a pack on
    it, and a rope of hair down the middle of a bedroll is a smear rather
    than a plait. Over the front it has a coat to lie against and a
    silhouette of its own.
    """
    p = Part()
    # The neck. Longer than the walker's by 25 mm, which is most of what
    # reads as a different build from behind.
    p.prism((0, 0, 1.322), NECK_R, 0.115, "skin_shadow", sides=6, squash=0.95)

    p.box((0, 0, HEAD_Z), (HEAD_X * 2, HEAD_Y * 2, HEAD_TOP - 1.320), "skin")
    p.box((0, -0.008, 1.362), (0.235, 0.230, 0.084), "skin_shadow")     # jaw
    p.box((0, -0.148, EYE_Z - 0.020), (0.040, 0.044, 0.050), "skin_light")
    # Two brows and two eyes rather than one bar of each: a box the width of
    # a face reads as a scowl at every distance and two read as a face.
    mirrored(p, ("box", (0.062, -0.128, 1.526), (0.086, 0.028, 0.024), "hair_dark"))
    mirrored(p, ("box", (0.062, -0.138, EYE_Z), (0.034, 0.022, 0.032), "eye"))
    p.box((0, -0.136, 1.398), (0.058, 0.022, 0.020), "trim_dark")       # mouth

    # The hair. **Worn forward, and that is the whole of why it is worth
    # four boxes.** Hung at the back alone it is invisible from in front —
    # the brim shades the crown and a face is a bare box — so the sides come
    # down past the ear in front of it, which is what frames a face, and the
    # fringe sits on the brow just under the brim.
    p.box((0, -0.120, 1.556), (0.288, 0.078, 0.070), "hair")
    p.box((0, 0.130, 1.478), (0.300, 0.074, 0.246), "hair")
    mirrored(p, ("box", (0.138, -0.030, 1.448), (0.036, 0.200, 0.200), "hair"))
    p.box((0, 0.118, 1.344), (0.176, 0.078, 0.118), "hair")             # gathered

    # The plait, over the right shoulder and down the front of the coat.
    # Three struts of falling width and a tie, which is the fewest that
    # reads as plaited rather than as a rope.
    p.strut((0.072, 0.104, 1.336), (0.128, -0.024, 1.258), 0.072, 0.072, "hair")
    p.strut((0.128, -0.024, 1.258), (0.116, -0.118, 1.146), 0.062, 0.062, "hair_dark")
    p.strut((0.116, -0.118, 1.146), (0.101, -0.138, 1.058), 0.047, 0.047, "hair")
    p.box((0.100, -0.140, 1.046), (0.050, 0.050, 0.026), "trim")

    # The hat: a soft felt one, and the whole of why these two are tellable
    # apart from behind. Where the walker's is a flat brim and a four-sided
    # peak, this is a round brim with a roll at its edge and a bell crown.
    # The roll at the edge is a second, taller drum at the full radius with
    # the flat of the brim tucked inside it — two prisms, 56 triangles. A
    # band of eight little boxes round the same circle would read the same
    # and cost 96, which is a tenth of the whole figure for the edge of a hat.
    p.prism((0, 0, BRIM_Z), BRIM_R, 0.044, "coat_dark", squash=0.96)
    p.prism((0, 0, BRIM_Z + 0.002), BRIM_R - 0.020, 0.026, "coat_dark",
            squash=0.96, top_material="coat_shadow")
    p.prism((0, 0, 1.702), CROWN_R, 0.156, "coat", squash=0.96,
            top_radius=CROWN_R * 0.88)
    p.prism((0, 0, 1.640), CROWN_R + 0.008, 0.034, "trim", sides=6,
            squash=0.96)
    # One moulted primary in the band, swept back over the crown — a flat
    # strut rather than a box, because the whole of what makes it read as a
    # feather is that it leans.
    p.strut((0.098, -0.096, 1.646), (0.146, 0.128, 1.792), 0.052, 0.014,
            "coat_light")
    return p


def spine_part():
    """The coat, the belt that cinches it, and everything hung off both.

    **The waist is the piece of geometry that does the work.** The walker's
    coat is one slab from collar to skirt with a belt drawn across it; this
    one narrows to 0.25 m at 0.96 and flares to 0.36 at the hem, so the
    silhouette has a shape in it before anything is worn over it at all.
    """
    p = Part()
    # Ribcage: narrow at the waist, wider at the chest.
    p.taper((0, 0, 0.900), (0, 0, 1.258),
            (WAIST_X * 2 + 0.010, 0.240), (CHEST_X * 2, CHEST_BACK * 2),
            "coat")
    p.box((0, 0, COLLAR_Z), (0.212, 0.196, 0.058), "coat_light")        # collar
    p.box((0, CHEST_FRONT - 0.004, 1.070), (0.052, 0.020, 0.360), "coat_dark")
    for z in (1.176, 1.032):
        p.box((0, CHEST_FRONT - 0.016, z), (0.026, 0.020, 0.026), "brass")

    # Shoulder yokes, a shade lighter — what reads as a uniform at any
    # distance you can still see a person at — and a pair of patch pockets.
    mirrored(p, ("box", (0.126, 0, 1.180), (0.128, 0.238, 0.056), "coat_light"))
    mirrored(p, ("box", (0.070, -0.126, 1.058), (0.098, 0.038, 0.100), "coat_dark"))
    mirrored(p, ("box", (0.070, -0.130, 1.114), (0.106, 0.042, 0.030), "coat_light"))

    # The belt, on the hips rather than at the waist: it is what says the
    # coat above it is cut in and the skirt below it is not.
    p.box((0, 0, BELT_Z), (0.288, 0.262, 0.062), "leather")
    p.box((0, -0.130, BELT_Z), (0.058, 0.032, 0.058), "brass")

    # The skirt: one tapered panel from the belt to the hem, which is what a
    # stack of boxes cannot do without a staircase down its side.
    p.taper((0, 0, HEM_Z), (0, 0, 0.848),
            (SKIRT_X * 2, 0.322), (0.262, 0.246), "coat_dark")
    p.box((0, 0, HEM_Z + 0.014), (SKIRT_X * 2 + 0.008, 0.330, 0.028),
          "coat_shadow")

    # A satchel on one hip and a canteen on the other, which is the pair of
    # asymmetries that stops a figure reading as a mannequin. Hung wide and
    # a little aft, clear of where the hands swing.
    p.box((0.196, 0.050, 0.690), (0.140, 0.096, 0.184), "leather")
    p.box((0.196, 0.050, 0.768), (0.150, 0.104, 0.056), "leather_light")
    p.prism((-0.196, 0.050, 0.716), 0.058, 0.120, "brass_dark", sides=6)
    p.box((-0.196, 0.050, 0.788), (0.042, 0.042, 0.034), "brass")

    # The field pack, and the bedroll lashed under it. **This is the side of
    # the figure the player actually looks at** — the camera is behind them
    # in third person — so it gets the same care the front does.
    p.box((0, 0.212, 1.000), (0.256, 0.176, 0.300), "leather")
    p.box((0, 0.212, 1.170), (0.266, 0.186, 0.056), "leather_light")
    mirrored(p, ("box", (0.076, PACK_BACK - 0.004, 1.140), (0.040, 0.020, 0.128),
                 "leather_dark"))
    p.strut((-0.196, 0.222, 0.856), (0.196, 0.222, 0.874), 0.092, 0.092,
            "trim_dark")
    mirrored(p, ("box", (0.126, 0.222, 0.865), (0.028, 0.106, 0.106), "leather"))
    # Straps over the shoulders and down the chest, which is what says the
    # pack is being carried rather than floating behind somebody.
    mirrored(p, ("strut", (0.086, 0.176, 1.212), (0.090, -0.126, 1.010),
                 0.042, 0.020, "leather"))

    # The binoculars, on a strap round the neck and resting on the chest.
    mirrored(p, ("box", (0.032, -0.150, 1.024), (0.054, 0.108, 0.054), "leather"))
    p.box((0, -0.150, 1.024), (0.040, 0.054, 0.040), "brass_dark")
    mirrored(p, ("box", (0.032, -0.212, 1.024), (0.046, 0.020, 0.046), "glass"))
    mirrored(p, ("strut", (0.112, 0.016, 1.212), (0.040, -0.136, 1.040),
                 0.020, 0.011, "leather"))
    return p


def arm_part(side):
    """Upper arm and the elbow it bends at, with a patch on the sleeve."""
    p = Part()
    x = side * SHOULDER_X
    p.box((x, 0, 1.052), (0.116, 0.116, 0.272), "coat")
    p.box((x, -0.010, 0.912), (0.124, 0.126, 0.070), "coat_dark")
    p.box((x, -0.018, 1.104), (0.126, 0.078, 0.050), "trim")
    return p


def forearm_part(side):
    """Forearm and turned-back cuff, on a bone of their own.

    Split from the upper arm because a breaststroke and a pull on a pair of
    oars both fold at the elbow, and an arm that cannot fold does the whole
    stroke as one rigid oar of its own.
    """
    p = Part()
    x = side * SHOULDER_X
    p.strut((x, -0.008, ELBOW_Z), (x, WRIST.y, WRIST.z), 0.100, 0.100, "coat")
    p.box((x, WRIST.y + 0.004, WRIST.z + 0.026), (0.110, 0.110, 0.046),
          "coat_light")
    return p


def hand_part(side):
    p = Part()
    x = side * SHOULDER_X
    p.box((x, HAND.y, HAND.z), (HAND_R * 2, HAND_R * 2, HAND_R * 2), "skin")
    p.box((x - side * 0.056, HAND.y - 0.012, HAND.z + 0.010),
          (0.030, 0.036, 0.048), "skin")                                # thumb
    p.box((x, HAND.y, HAND.z + 0.036), (0.102, 0.102, 0.030), "leather_dark")
    return p


def leg_part(side):
    """Thigh and knee. Mostly under the skirt, and all of it under it at rest."""
    p = Part()
    x = side * HIP_X
    p.box((x, 0, 0.556), (0.152, 0.152, 0.312), "trouser")
    p.box((x, -0.005, KNEE_Z), (0.140, 0.150, 0.056), "trouser_knee")
    return p


def shin_part(side):
    """Below the knee. Split off for the two poses that fold a leg in half —
    a rower with their shins dropped to the floorboards, and a frog kick."""
    p = Part()
    x = side * HIP_X
    p.box((x, 0, 0.238), (SHIN_X * 2, SHIN_X * 2, 0.300), "trouser_shin")
    return p


def foot_part(side):
    """A tall boot, a sole, a toe cap, laces up the front — and mud.

    The mud is a band of its own material just above the sole rather than
    shading painted on, because a face painted dark to fake a shadow is dark
    on the sunny side too. This one is dirt, and dirt is dark on both sides.
    """
    p = Part()
    x = side * HIP_X
    p.box((x, BOOT.y, BOOT.z), (BOOT_X * 2, BOOT_Y * 2, 0.084), "leather")
    p.box((x, BOOT.y, 0.010), (BOOT_X * 2 + 0.010, BOOT_Y * 2 + 0.010, 0.020),
          "leather_dark")
    p.box((x, BOOT.y, 0.028), (BOOT_X * 2 + 0.005, BOOT_Y * 2 + 0.005, 0.024),
          "boot_mud")
    p.box((x, BOOT.y - 0.098, 0.052), (0.150, 0.070, 0.066), "leather_dark")
    # The shaft: taller than the walker's and tapered, which is the last of
    # the four things that tell the two figures apart at a distance.
    p.taper((x, 0, 0.084), (x, -0.004, BOOT_TOP),
            (0.172, 0.190), (0.156, 0.176), "leather_light")
    p.box((x, -0.090, 0.166), (0.052, 0.024, 0.130), "leather_dark")    # laces
    p.box((x, -0.004, BOOT_TOP - 0.016), (0.164, 0.184, 0.032), "leather")
    return p


def parts():
    """Every piece, filed under the bone that carries it, sealed faces gone."""
    made = {
        "spine": spine_part(),
        "head": head_part(),
        "arm_l": arm_part(1), "arm_r": arm_part(-1),
        "forearm_l": forearm_part(1), "forearm_r": forearm_part(-1),
        "hand_l": hand_part(1), "hand_r": hand_part(-1),
        "leg_l": leg_part(1), "leg_r": leg_part(-1),
        "shin_l": shin_part(1), "shin_r": shin_part(-1),
        "foot_l": foot_part(1), "foot_r": foot_part(-1),
    }
    return {bone: part.bury() for bone, part in made.items()}


# --- the skeleton ----------------------------------------------------------
#
# The names are the entire binding contract — README §10. The neck bone is at
# the *base* of the neck rather than inside the skull, because the game turns
# a head about it and a pivot in the middle of a skull swivels like a turret.
#
# `_l` is +X. Not anatomy: it is what keeps a modelled gaiter swinging with
# the boot inside it, since the boxes swing the +X leg on sin(phase) and the
# importer's fallback swings `left` on sin(phase) too.
#
# The same fifteen bones the walker has, in the same tree, so that one
# wardrobe rigs to either figure without a name changing.

BONES = [
    # name,       head,                            tail,                            parent
    ("root",      (0, 0, 0.0),                     (0, 0, WAIST_Z),                 None),
    ("spine",     (0, 0, WAIST_Z),                 (0, 0, NECK_Z),                  "root"),
    ("head",      (0, 0, NECK_Z),                  (0, 0, 1.640),                   "spine"),
    ("arm_l",     (SHOULDER_X, 0, SHOULDER_Z),     (SHOULDER_X, -0.01, ELBOW_Z),    "spine"),
    ("forearm_l", (SHOULDER_X, -0.01, ELBOW_Z),    tuple(WRIST),                    "arm_l"),
    ("hand_l",    tuple(WRIST),                    (SHOULDER_X, -0.066, 0.545),     "forearm_l"),
    ("arm_r",     (-SHOULDER_X, 0, SHOULDER_Z),    (-SHOULDER_X, -0.01, ELBOW_Z),   "spine"),
    ("forearm_r", (-SHOULDER_X, -0.01, ELBOW_Z),   (-WRIST.x, WRIST.y, WRIST.z),    "arm_r"),
    ("hand_r",    (-WRIST.x, WRIST.y, WRIST.z),    (-SHOULDER_X, -0.066, 0.545),    "forearm_r"),
    ("leg_l",     (HIP_X, 0, HIP_Z),               (HIP_X, 0, KNEE_Z),              "spine"),
    ("shin_l",    (HIP_X, 0, KNEE_Z),              (HIP_X, 0, ANKLE_Z),             "leg_l"),
    ("foot_l",    (HIP_X, 0, ANKLE_Z),             (HIP_X, -0.15, 0.042),           "shin_l"),
    ("leg_r",     (-HIP_X, 0, HIP_Z),              (-HIP_X, 0, KNEE_Z),             "spine"),
    ("shin_r",    (-HIP_X, 0, KNEE_Z),             (-HIP_X, 0, ANKLE_Z),            "leg_r"),
    ("foot_r",    (-HIP_X, 0, ANKLE_Z),            (-HIP_X, -0.15, 0.042),          "shin_r"),
]


# --- animation -------------------------------------------------------------
#
# Five clips, because five is every state a walker is ever drawn in and a
# state with no clip is posed by a procedural table that works per piece
# about each bone's own pivot rather than down the hierarchy. At an idle's
# 0.03 rad nobody can tell; at a run's 0.67 the hand rotates about the wrist
# it is still standing at while the arm swings away from the shoulder, and
# the two come apart by a third of a metre.
#
# Angles are world-axis and converted into each bone's own space by
# `kit.pose`, so "swing the leg forward" is a number about global X here.

def _torso(rig, frame, pitch=0.0, roll=0.0, yaw=0.0, shift=(0, 0, 0),
           swing=0.0, knee=(0.0, 0.0)):
    """The spine, and the legs that have to undo it.

    **The one thing worth understanding about animating this rig.** The legs
    hang off `spine`, so every breath the chest takes lifts both boots off
    the ground with it and every lean drags them sideways. A 12 mm breath is
    a figure hovering 12 mm over the turf, which is the sort of thing nobody
    sees in Blender and everybody sees in a clearing at dusk. So the legs are
    given the inverse of whatever the spine just did, and the walk's own
    swing is added on top.

    `swing` is the hip swing, positive for the +X leg forward. `knee` is how
    far each knee folds — positive draws the heel up behind, which is the
    only way it bends.
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


def idle(rig, seconds=5.0):
    """Breathing, and a slow shift of weight from one foot to the other.

    Very small on purpose — a person standing still, not swaying. The two
    cycles are the same length so the whole thing loops on one period, and
    the first and last frames are identical so it loops clean.
    """
    frames = int(seconds * kit.FPS)
    kit.action(rig, "idle")
    kit.rest(rig)
    for f in range(frames + 1):
        frame = f + 1
        t = f / frames
        breath = math.sin(t * math.tau)
        sway = math.sin(t * math.tau)
        look = math.sin(t * math.tau + 1.1)
        pose(rig, "root", frame, shift=(sway * 0.007, 0, 0))
        _torso(rig, frame, pitch=-0.013 * breath, roll=0.024 * sway,
               shift=(0, 0, 0.006 * breath))
        pose(rig, "head", frame, pitch=0.020 * breath, yaw=0.048 * look,
             roll=-0.014 * sway)
        pose(rig, "arm_l", frame, pitch=0.024 * breath, roll=0.016 * sway)
        pose(rig, "arm_r", frame, pitch=-0.024 * breath, roll=0.016 * sway)
        pose(rig, "hand_l", frame, pitch=0.032 * breath)
        pose(rig, "hand_r", frame, pitch=-0.032 * breath)


def walk(rig, seconds=1.0, reach=0.40, fold=0.55, lean=0.028, name="walk"):
    """One stride, opposite arm to opposite leg.

    **The body drops to meet the legs.** A straight leg swung `reach` rad
    either way lifts its own boot `LEG * (1 - cos reach)` clear of the turf,
    so the figure would walk the cycle on stilts and land flat-footed in the
    middle of it. Dropping the root by exactly that much puts both soles back
    on the floor at every frame, and the rise and fall it produces on the way
    through is the bob a walk has anyway.

    **The knee does the clearance**, and does it where a knee does: the
    swinging leg folds as it passes under the body and is straight again at
    both ends of the stride. That timing is `cos`, not `sin` — the
    quarter-cycle that catches people out — and it is also what keeps the
    drop above exact, because at full spread both knees are straight.
    """
    frames = int(seconds * kit.FPS)
    kit.action(rig, name)
    kit.rest(rig)
    for f in range(frames + 1):
        frame = f + 1
        t = f / frames
        wave = math.sin(t * math.tau)
        pass_by = math.cos(t * math.tau)
        stride = abs(wave)
        drop = (HIP_Z - ANKLE_Z) * (1 - math.cos(reach * wave))
        pose(rig, "root", frame, shift=(0, 0, -drop))
        _torso(rig, frame, pitch=lean, roll=0.028 * pass_by, yaw=0.058 * wave,
               swing=reach * wave,
               knee=(fold * max(0.0, pass_by), fold * max(0.0, -pass_by)))
        pose(rig, "head", frame, yaw=-0.042 * wave,
             pitch=0.020 * stride - lean * 0.6)
        pose(rig, "arm_l", frame, pitch=reach * 0.68 * wave)
        pose(rig, "arm_r", frame, pitch=-reach * 0.68 * wave)
        # The elbow folds on the forward swing and straightens on the back
        # one, which is the asymmetry that stops an arm reading as a pendulum.
        pose(rig, "forearm_l", frame, pitch=-0.24 - reach * 0.45 * min(0.0, wave))
        pose(rig, "forearm_r", frame, pitch=-0.24 + reach * 0.45 * max(0.0, wave))
        pose(rig, "hand_l", frame, pitch=reach * 0.25 * wave)
        pose(rig, "hand_r", frame, pitch=-reach * 0.25 * wave)


def run(rig):
    """The same cycle, driven harder. See the note above BONES for why this
    has to exist as a clip rather than falling back."""
    walk(rig, seconds=0.7, reach=0.62, fold=0.95, lean=0.082, name="run")


def swim(rig, seconds=1.6):
    """Breaststroke — **authored standing up.**

    The clip that looks wrong in Blender and right in the game. A swimmer's
    body angle runs continuously from upright, treading water, through flat
    on the surface, to head-down in a dive, and which of those it is depends
    on where the player is looking — so no keyframe can hold it. The engine
    tips the whole figure at draw time instead (`SceneModel.Lean`), about the
    hips, and this supplies only what the arms and legs do inside that tip.

    So read every pose below as if the figure were already face-down: arms
    overhead is the reach out in front, knees to the chest is the frog kick
    drawing up, and the head lifting is the breath.
    """
    frames = int(seconds * kit.FPS)
    kit.action(rig, "swim")
    kit.rest(rig)
    for f in range(frames + 1):
        frame = f + 1
        t = f / frames
        # 0 is the full reach, 1 is hands pulled back to the chest.
        # Front-loaded, so the pull is quick and the glide is long — a
        # breaststroke is mostly waiting, which is what makes it read as
        # swimming rather than as flailing.
        pull = 0.5 - 0.5 * math.cos(min(1.0, t / 0.45) * math.tau) if t < 0.45 else 0.0
        # Legs a beat behind the arms: a breaststroke kicks as the arms
        # recover, which is the whole of why it moves anybody anywhere.
        kt = (t - 0.35) / 0.45
        kick = 0.5 - 0.5 * math.cos(min(1.0, max(0.0, kt)) * math.tau)

        pose(rig, "root", frame, shift=(0, 0, 0))
        # Every limb angle here is *local* — measured against the chest
        # rather than against the world — because a swimmer's arms belong to
        # their body and not to the horizon. That is the opposite of
        # `_torso`'s rule, which exists to keep boots planted on ground this
        # figure is nowhere near, so it is not used here.
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


def row(rig, seconds=2.2):
    """Sitting to a pair of oars.

    **The one clip measured against furniture rather than anatomy.** A rower
    is folded onto a thwart with their feet on the floorboards, and the gap
    between the two is the boat's: `BoatModel.DEPTH * 0.76`, about 350 mm,
    with the hips a further 110 mm above the seat. So the root drops to put
    the hips on the thwart, the thighs come forward far enough that a 300 mm
    shin can still reach the floor, and the feet land where the boards are.

    That geometry is why the legs are split at the knee at all: a leg rigged
    as one rigid bone cannot sit down.
    """
    frames = int(seconds * kit.FPS)
    # How far the hips sit below the bone the body swings about. **This is
    # the number that keeps a rower on their seat.** The spine pivots at the
    # waist, 270 mm above the hip joint, so leaning back swings the hips
    # forward off the thwart and takes the braced feet with them — 160 mm of
    # boot skating over the floorboards, once a stroke. The root undoes it.
    perch = WAIST_Z - HIP_Z
    kit.action(rig, "row")
    kit.rest(rig)
    for f in range(frames + 1):
        frame = f + 1
        t = f / frames
        # 0 at the catch (arms out, body forward), 1 at the finish.
        drive = 0.5 - 0.5 * math.cos(t * math.tau)
        swing_back = 0.34 - 0.62 * drive
        pose(rig, "root", frame,
             shift=(0, -perch * math.sin(swing_back),
                    (SEAT_Z - WAIST_Z) + perch * math.cos(swing_back)))
        pose(rig, "spine", frame, pitch=swing_back)
        # **The legs are furniture and the chest is not.** A fixed-seat boat
        # does not slide, so the thighs hold one angle *against the boat* for
        # the whole stroke while the body swings over them — which means
        # every angle here is a world angle with the spine's swing taken back
        # out of it, and the ankle with the knee's fold out of it as well.
        for bone in ("leg_l", "leg_r"):
            pose(rig, bone, frame, pitch=-1.13 - swing_back)
        for bone in ("shin_l", "shin_r"):
            pose(rig, bone, frame, pitch=0.64)
        for bone in ("foot_l", "foot_r"):
            pose(rig, bone, frame, pitch=0.49)
        # Arms: straight out at the catch, drawn to the ribs at the finish.
        # **The elbow has to come down, not just fold** — left pointing
        # forward while the forearm folds, the hand finishes beside the ear.
        for bone, side in (("arm_l", 1), ("arm_r", -1)):
            pose(rig, bone, frame, pitch=-1.45 + 1.60 * drive, roll=-side * 0.22)
        for bone in ("forearm_l", "forearm_r"):
            pose(rig, bone, frame, pitch=-0.05 - 1.50 * drive)
        for bone, side in (("hand_l", 1), ("hand_r", -1)):
            pose(rig, bone, frame, pitch=0.20, roll=side * 0.15)
        pose(rig, "head", frame, pitch=0.10 - 0.16 * drive)


# --- the whole thing -------------------------------------------------------

def build():
    kit.to_object_mode()
    collection = kit.fresh_collection(COLLECTION)
    bpy.context.scene.render.fps = kit.FPS
    tin = kit.palette(figures.colours(F["key"]), figures.DERIVED)

    rig = kit.build_rig(collection, RIG, BONES)
    made = [rig]
    triangles = 0
    for bone, part in parts().items():
        obj = kit.mesh_object(collection, "wayfarer_" + bone, part, tin)
        kit.bind(obj, rig, bone)
        made.append(obj)
        triangles += len(obj.data.polygons)

    idle(rig)
    walk(rig)
    run(rig)
    swim(rig)
    row(rig)
    rig.animation_data.action = bpy.data.actions["idle"]
    bpy.context.scene.frame_set(1)

    print("WAYFARER built: %d triangles, %d bones, %d materials, clips %s"
          % (triangles, len(BONES), len(tin),
             ", ".join(a.name for a in bpy.data.actions if a.use_fake_user)))
    return rig, made


def main():
    rig, made = build()
    root = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                        "..", ".."))
    out = os.path.join(root, OUTPUT)
    kit.export(out, made)
    print("WAYFARER written: %s (%d bytes)" % (out, os.path.getsize(out)))


if __name__ == "__main__":
    main()
