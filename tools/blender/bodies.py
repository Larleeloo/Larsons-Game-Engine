"""
Build the two player figures — **as bodies, not as outfits** — and export them.

    blender --background --python tools/blender/bodies.py
    blender --background --python tools/blender/bodies.py -- wayfarer

Writes

    src/main/resources/watch/models/characters/walker.glb
    src/main/resources/watch/models/characters/wayfarer.glb

--- what changed, and why it is the whole point --------------------------

These used to be finished people: a field coat, trousers, boots, a pack and a
hat, modelled into one mesh, with the wardrobe hung over the top. That makes
every garment on the rail a thing worn *over* clothes rather than instead of
them — a beanie pulled over a campaign hat, a cape over a pack — and it means
the one thing a player cannot do is take the coat off.

So the coat came off. What is left here is a **body**: a head with no hair on
it, bare arms and legs, and underwear. Everything that was clothing is now a
piece in `cosmetics.py`, owned from the start and worn by default, so it can
be taken off, swapped, and recoloured like anything else. Hair is a piece
too — see the `HAIRSTYLES` there.

**A body is not a nude.** It is a vest and a pair of shorts, which is the
level of detail this game draws at and the level of undress a player who takes
everything off should arrive at. Nothing here needs to be hidden by anything.

--- everything else is as it was -----------------------------------------

**Exactly 1.78 m to the crown.** Load-bearing rather than tidy: an imported
character is normalised by its height and redrawn at `WalkerModel.HEIGHT`, so
1.78 is the only figure that comes out at the metres it was authored in — and
a worn piece is never rescaled at all, so every landmark in `figures.py` is a
landmark the game draws.

**Blender's coordinates are the game's coordinates.** +Z up, −Y the way they
face, +X the side `_l` bones go on.

**Colour is materials, not textures**, and **rigid parts, not one skin**: the
importer gives each triangle to the one bone with the most weight across its
corners and moves it rigidly, so this is built the way a wooden mannequin is.

The contract is `src/main/resources/watch/models/README.md` §8-§15 and §17.
"""

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import bpy                                                    # noqa: E402
from mathutils import Vector                                  # noqa: E402

import figures                                                # noqa: E402
import gait                                                   # noqa: E402
import kit                                                    # noqa: E402
from kit import Part, mirrored                                # noqa: E402

#: Where the files go, relative to the root of the repository.
OUTPUT = "src/main/resources/watch/models/characters"


# --- the body, bone by bone ------------------------------------------------
#
# Every number is read off `f`, which is a row of `figures.py`, so one set of
# functions builds both people and the wardrobe is cut from the same table.

def head_part(f):
    """A face, and nothing on it that comes off.

    No hair: that is a piece now, and a scalp is what a bald head looks like.
    The walker keeps a beard because a beard is not a hat — it is part of who
    they are, it never comes off in this game, and putting it in the hairstyle
    would mean shaving somebody by changing their parting.
    """
    p = Part()
    hx, hy = f["head_half_x"], f["head_half_y"]
    top, mid = f["head_top"], f["head_z"]
    p.prism((0, 0, f["neck_z"] + 0.032), f["neck_r"], 0.115, "skin_shadow",
            sides=6, squash=0.95)
    p.box((0, 0, mid), (hx * 2, hy * 2, (top - mid) * 2), "skin")
    p.box((0, -0.008, mid - 0.104), (hx * 1.64, hy * 1.68, 0.086), "skin_shadow")
    p.box((0, f["face_y"] - 0.010, f["eye_z"] - 0.020),
          (0.041, 0.045, 0.049), "skin_light")                        # nose
    mirrored(p, ("box", (hx * 1.01, 0.004, mid), (0.026, 0.046, 0.066),
                 "skin_shadow"))                                      # ears
    # Two brows and two eyes rather than one bar of each: a box the width of a
    # face reads as a scowl at every distance and two read as a face.
    mirrored(p, ("box", (hx * 0.43, f["face_y"] + 0.012, f["eye_z"] + 0.043),
                 (hx * 0.60, 0.028, 0.024), "hair_dark"))
    mirrored(p, ("box", (hx * 0.43, f["face_y"] + 0.002, f["eye_z"]),
                 (0.034, 0.022, 0.032), "eye"))
    p.box((0, f["face_y"] + 0.004, mid - 0.070), (0.058, 0.022, 0.020),
          "trim_dark")                                                # mouth
    if f.get("beard"):
        p.box((0, f["face_y"] + 0.036, mid - 0.128), (hx * 1.22, 0.100, 0.080),
              "hair")
        p.box((0, f["face_y"] + 0.006, mid - 0.078), (0.140, 0.060, 0.040), "hair")
    return p


def spine_part(f):
    """The torso, in a vest.

    The vest is the underwear and the whole reason this file exists: a player
    who takes off everything they own is wearing this, and it has to be a
    finished thing rather than a gap. Two straps, a body and a hem — four
    boxes, which is what everything else in this world costs.
    """
    p = Part()
    chest_x, back = f["chest_half_x"], f["chest_back_y"]
    waist_x = f["waist_half_x"]
    top = f["neck_z"] - 0.030
    p.taper((0, 0, f["hip_z"] + 0.010), (0, 0, top),
            (waist_x * 2, back * 1.86), (chest_x * 1.90, back * 1.92), "skin")
    # The vest, over the chest and down past the waist.
    p.taper((0, 0, f["hip_z"] + 0.040), (0, 0, top - 0.060),
            (waist_x * 2.06, back * 1.92), (chest_x * 1.96, back * 1.98), "linen")
    p.box((0, 0, top - 0.066), (chest_x * 1.98, back * 2.00, 0.030), "linen_dark")
    mirrored(p, ("box", (chest_x * 0.72, 0, top - 0.008),
                 (chest_x * 0.42, back * 1.60, 0.052), "linen"))       # straps
    p.box((0, 0, f["hip_z"] + 0.030), (waist_x * 2.10, back * 1.96, 0.034),
          "linen_dark")                                                # hem
    # The shorts, over the hips, which the trousers go over in turn.
    p.taper((0, 0, f["hip_z"] - 0.090), (0, 0, f["hip_z"] + 0.052),
            (f["hip_x"] * 3.50, back * 1.90), (waist_x * 2.14, back * 1.98),
            "linen_dark")
    p.box((0, 0, f["hip_z"] + 0.046), (waist_x * 2.18, back * 2.02, 0.028),
          "linen")                                                     # waistband
    return p


def arm_part(f, side):
    p = Part()
    x = side * f["shoulder_x"]
    top = f["shoulder_z"] + 0.030
    # `taper` takes the *centre* of each end, not a corner. Offsetting by half
    # a width here is how an arm ends up hanging beside a shoulder rather than
    # in it, which is exactly what the first version of this did.
    p.taper((x, 0, f["elbow_z"] - 0.020), (x, 0, top),
            (0.096, 0.096), (0.110, 0.110), "skin")
    p.prism((x, 0, top - 0.012), 0.062, 0.048, "skin_shadow", sides=6)
    return p


def forearm_part(f, side):
    """Split from the upper arm because a breaststroke and a pull on a pair of
    oars both fold at the elbow, and an arm that cannot fold does the whole
    stroke as one rigid oar of its own."""
    p = Part()
    x = side * f["shoulder_x"]
    wrist = wrist_of(f)
    p.strut((x, -0.008, f["elbow_z"]), (x, wrist.y, wrist.z), 0.086, 0.086, "skin")
    return p


def hand_part(f, side):
    p = Part()
    x = side * f["shoulder_x"]
    hx, hy, hz = f["hand"]
    r = f["hand_half"]
    p.box((x, hy, hz), (r * 2, r * 2, r * 2), "skin")
    p.box((x - side * r * 1.14, hy - 0.012, hz + r * 0.30),
          (r * 0.62, r * 0.90, r * 1.10), "skin")                      # thumb
    return p


def leg_part(f, side):
    p = Part()
    x = side * f["hip_x"]
    top = f["hip_z"] + 0.010
    p.taper((x, 0, f["knee_z"]), (x, 0, top),
            (0.124, 0.124), (0.144, 0.144), "skin")
    p.box((x, -0.005, f["knee_z"]), (0.126, 0.134, 0.052), "skin_shadow")
    return p


def shin_part(f, side):
    """Below the knee. Split off for the two poses that fold a leg in half."""
    p = Part()
    x = side * f["hip_x"]
    p.taper((x, 0, f["ankle_z"]), (x, 0, f["knee_z"]),
            (0.096, 0.096), (0.116, 0.116), "skin")
    return p


def foot_part(f, side):
    p = Part()
    x = side * f["hip_x"]
    bx, by, bz = f["boot"]
    p.box((x, by + 0.014, 0.032), (f["boot_half_x"] * 1.70,
                                   f["boot_half_y"] * 1.66, 0.064), "skin")
    p.box((x, by - 0.058, 0.022), (f["boot_half_x"] * 1.50, 0.070, 0.044),
          "skin_shadow")                                               # toes
    p.box((x, by + 0.062, 0.026), (f["boot_half_x"] * 1.44, 0.062, 0.052),
          "skin_shadow")                                               # heel
    return p


def wrist_of(f):
    """The wrist, which is the hand's own centre with the joint above it."""
    hx, hy, hz = f["hand"]
    return Vector((f["shoulder_x"], hy + 0.004, hz + 0.062))


def parts(f):
    """Every piece, filed under the bone that carries it, sealed faces gone."""
    made = {
        "spine": spine_part(f),
        "head": head_part(f),
        "arm_l": arm_part(f, 1), "arm_r": arm_part(f, -1),
        "forearm_l": forearm_part(f, 1), "forearm_r": forearm_part(f, -1),
        "hand_l": hand_part(f, 1), "hand_r": hand_part(f, -1),
        "leg_l": leg_part(f, 1), "leg_r": leg_part(f, -1),
        "shin_l": shin_part(f, 1), "shin_r": shin_part(f, -1),
        "foot_l": foot_part(f, 1), "foot_r": foot_part(f, -1),
    }
    return {bone: part.bury() for bone, part in made.items()}


# --- the skeleton ----------------------------------------------------------
#
# The names are the entire binding contract — README §10 — and they are the
# same fifteen on every figure and on the wardrobe rig, which is what lets a
# garment be carried by the body wearing it: `SceneModel.Worn` matches them up
# by name.
#
# The neck bone is at the *base* of the neck rather than inside the skull,
# because the game turns a head about it and a pivot in the middle of a skull
# swivels like a turret. `_l` is +X — not anatomy, but what keeps a modelled
# gaiter swinging with the boot inside it.

def bones_for(f):
    hip_x, shoulder_x = f["hip_x"], f["shoulder_x"]
    hy, hz = f["hand"][1], f["hand"][2]
    wrist = tuple(wrist_of(f))
    by, bz = f["boot"][1], f["boot"][2]
    return [
        ("root",      (0, 0, 0.0),                      (0, 0, f["waist_z"]),           None),
        ("spine",     (0, 0, f["waist_z"]),             (0, 0, f["neck_z"]),            "root"),
        ("head",      (0, 0, f["neck_z"]),              (0, 0, f["head_top"]),          "spine"),
        ("arm_l",     (shoulder_x, 0, f["shoulder_z"]), (shoulder_x, -0.01, f["elbow_z"]), "spine"),
        ("forearm_l", (shoulder_x, -0.01, f["elbow_z"]), wrist,                         "arm_l"),
        ("hand_l",    wrist,                            (shoulder_x, hy - 0.01, hz - 0.055), "forearm_l"),
        ("arm_r",     (-shoulder_x, 0, f["shoulder_z"]), (-shoulder_x, -0.01, f["elbow_z"]), "spine"),
        ("forearm_r", (-shoulder_x, -0.01, f["elbow_z"]), (-wrist[0], wrist[1], wrist[2]), "arm_r"),
        ("hand_r",    (-wrist[0], wrist[1], wrist[2]),  (-shoulder_x, hy - 0.01, hz - 0.055), "forearm_r"),
        ("leg_l",     (hip_x, 0, f["hip_z"]),           (hip_x, 0, f["knee_z"]),        "spine"),
        ("shin_l",    (hip_x, 0, f["knee_z"]),          (hip_x, 0, f["ankle_z"]),       "leg_l"),
        ("foot_l",    (hip_x, 0, f["ankle_z"]),         (hip_x, by - 0.12, bz),         "shin_l"),
        ("leg_r",     (-hip_x, 0, f["hip_z"]),          (-hip_x, 0, f["knee_z"]),       "spine"),
        ("shin_r",    (-hip_x, 0, f["knee_z"]),         (-hip_x, 0, f["ankle_z"]),      "leg_r"),
        ("foot_r",    (-hip_x, 0, f["ankle_z"]),        (-hip_x, by - 0.12, bz),        "shin_r"),
    ]


# --- building it -----------------------------------------------------------

def build(figure_key, write=True, root=None):
    f = figures.figure(figure_key)
    kit.to_object_mode()
    collection = kit.fresh_collection(figure_key.upper())
    bpy.context.scene.render.fps = kit.FPS
    tin = kit.palette(figures.colours(figure_key), figures.DERIVED)

    rig = kit.build_rig(collection, "%s_rig" % figure_key, bones_for(f))
    made = [rig]
    triangles = 0
    for bone, part in parts(f).items():
        obj = kit.mesh_object(collection, "%s_%s" % (figure_key, bone), part, tin)
        kit.bind(obj, rig, bone)
        made.append(obj)
        triangles += len(obj.data.polygons)

    gait.all_of_them(rig, f)
    rig.animation_data.action = bpy.data.actions["idle"]
    bpy.context.scene.frame_set(1)

    print("BODY %s: %d triangles, %d bones, clips %s"
          % (figure_key, triangles, len(bones_for(f)),
             ", ".join(a.name for a in bpy.data.actions if a.use_fake_user)))
    if write:
        if root is None:
            root = os.path.abspath(os.path.join(
                os.path.dirname(os.path.abspath(__file__)), "..", ".."))
        out = os.path.join(root, OUTPUT, figure_key + ".glb")
        kit.export(out, made)
        print("BODY %s written: %s (%d bytes)"
              % (figure_key, out, os.path.getsize(out)))
    return rig, made


def main():
    argv = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
    keys = argv if argv else [figure["key"] for figure in figures.ALL]
    for key in keys:
        build(key)


if __name__ == "__main__":
    main()
