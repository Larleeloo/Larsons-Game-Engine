"""
Build the Field Guide's reference walker in Blender, to fit clothes to.

Run it from Blender's Scripting tab (Open, then Run Script) or with

    blender --python tools/blender/cosmetic_reference.py

It makes two things in a collection called REFERENCE:

  * the walker, as the boxes the game actually draws — a body to fit a
    garment against and then delete;
  * an armature with the bone names the importer binds, already at the
    joints those boxes pivot about.

Nothing here is exported. See `src/main/resources/watch/models/README.md`
§16 for the contract, and `tools/blender/README.md` for the walkthrough.

--- The one fact that makes this easy -------------------------------------

**Blender's coordinates are the game's coordinates.** A walker standing at
the world origin facing along the game's forward has every part of itself at
exactly the numbers below, and a point you model at Blender (x, y, z) arrives
in the game at (x, y, z). That is not a coincidence to be relied on blindly —
it is the Blender -> glTF -> importer chain composing to the identity, and
`CosmeticsTest.theBlenderOriginIsTheGamesOrigin` pins it — but it does mean
there is no axis arithmetic for you to get wrong.

  +Z  up
  -Y  the way the figure faces and walks
  +X  one side of the figure — and the one `_l` bones go on. See BONES below;
      it is about staying in step with the legs, not about anatomy.
"""

import bpy
from mathutils import Vector

# --- the figure ------------------------------------------------------------
#
# Metres, from WalkerModel's own proportions for a standing 1.78 m walker with
# their feet on Z = 0 and their arms at rest. Every one of these is asserted
# against the real mesh by CosmeticsTest.theReferenceFigureIsTheOneThisFolder-
# Describes, so if you change one here, change it there.

HEIGHT = 1.78          # WalkerModel.HEIGHT — the nominal height
HAT_TOP = 1.95         # …and what you would actually measure, hat included

SOLE = 0.00
BOOT_Z = 0.05
KNEE_Z = 0.45
HAND_Z = 0.80
HIP_Z = 0.87
CHEST_Z = 1.24
PACK_Z = 1.28
SHOULDER_Z = 1.45
NECK_Z = 1.59
HEAD_Z = 1.70
BRIM_Z = 1.85

HIP_X = 0.09           # legs, either side of centre
SHOULDER_X = 0.20      # arms
HAND_Y = -0.067        # the hands hang a little forward of the shoulder

# Half-extents, so a "size" below is half the box. The game is written in
# these and converting them here would only be a chance to halve one twice.
PARTS = [
    # name,            centre (x, y, z),                half extents
    ("boot_l",         ( HIP_X, -0.03, BOOT_Z),         (0.085, 0.115, 0.050)),
    ("boot_r",         (-HIP_X, -0.03, BOOT_Z),         (0.085, 0.115, 0.050)),
    ("shin_l",         ( HIP_X, 0.0, (KNEE_Z + 0.065) / 2), (0.072, 0.072, (KNEE_Z - 0.065) / 2)),
    ("shin_r",         (-HIP_X, 0.0, (KNEE_Z + 0.065) / 2), (0.072, 0.072, (KNEE_Z - 0.065) / 2)),
    ("thigh_l",        ( HIP_X, 0.0, (HIP_Z + KNEE_Z) / 2), (0.082, 0.082, (HIP_Z - KNEE_Z) / 2)),
    ("thigh_r",        (-HIP_X, 0.0, (HIP_Z + KNEE_Z) / 2), (0.082, 0.082, (HIP_Z - KNEE_Z) / 2)),
    ("chest",          (0.0, 0.0, CHEST_Z),             (0.145, 0.220, 0.320)),
    ("pack",           (0.0, 0.20, PACK_Z),             (0.120, 0.090, 0.140)),
    ("upperarm_l",     ( SHOULDER_X, 0.0, (SHOULDER_Z + 1.12) / 2), (0.062, 0.062, (SHOULDER_Z - 1.12) / 2)),
    ("upperarm_r",     (-SHOULDER_X, 0.0, (SHOULDER_Z + 1.12) / 2), (0.062, 0.062, (SHOULDER_Z - 1.12) / 2)),
    ("forearm_l",      ( SHOULDER_X, HAND_Y / 2, (1.12 + HAND_Z) / 2), (0.055, 0.055, (1.12 - HAND_Z) / 2)),
    ("forearm_r",      (-SHOULDER_X, HAND_Y / 2, (1.12 + HAND_Z) / 2), (0.055, 0.055, (1.12 - HAND_Z) / 2)),
    ("hand_l",         ( SHOULDER_X, HAND_Y, HAND_Z),   (0.055, 0.055, 0.055)),
    ("hand_r",         (-SHOULDER_X, HAND_Y, HAND_Z),   (0.055, 0.055, 0.055)),
    ("head",           (0.0, 0.0, HEAD_Z),              (0.115, 0.115, 0.115)),
    ("hat_brim",       (0.0, 0.0, BRIM_Z),              (0.270, 0.270, 0.022)),
    ("hat_crown",      (0.0, 0.0, 1.899),               (0.135, 0.135, 0.050)),
]

# --- the bones -------------------------------------------------------------
#
# The names are the whole binding contract — see README §10. A bone that
# matches nothing inherits its parent, so extra bones under these are free.
#
# **Why `_l` is +X.** It is not about anatomy, it is about staying in step: a
# walker's boxes swing the +X leg on sin(phase) and the importer's fallback
# swings LEG_FL on sin(phase) too. Put `_l` on +X and a modelled piece walks
# with the legs underneath it. If a finished piece looks a beat out, swap the
# two names — that is the whole of the fix.
BONES = [
    # name,      head (x, y, z),                    tail,                              parent
    ("root",     (0.0, 0.0, 0.0),                   (0.0, 0.0, HIP_Z),                 None),
    ("spine",    (0.0, 0.0, HIP_Z),                 (0.0, 0.0, SHOULDER_Z),            "root"),
    ("head",     (0.0, 0.0, NECK_Z),                (0.0, 0.0, HAT_TOP),               "spine"),
    ("arm_l",    ( SHOULDER_X, 0.0, SHOULDER_Z),    ( SHOULDER_X, 0.0, 1.12),          "spine"),
    ("arm_r",    (-SHOULDER_X, 0.0, SHOULDER_Z),    (-SHOULDER_X, 0.0, 1.12),          "spine"),
    ("hand_l",   ( SHOULDER_X, HAND_Y, 1.12),       ( SHOULDER_X, HAND_Y, HAND_Z),     "arm_l"),
    ("hand_r",   (-SHOULDER_X, HAND_Y, 1.12),       (-SHOULDER_X, HAND_Y, HAND_Z),     "arm_r"),
    ("leg_l",    ( HIP_X, 0.0, HIP_Z),              ( HIP_X, 0.0, KNEE_Z),             "root"),
    ("leg_r",    (-HIP_X, 0.0, HIP_Z),              (-HIP_X, 0.0, KNEE_Z),             "root"),
    ("foot_l",   ( HIP_X, 0.0, KNEE_Z),             ( HIP_X, -0.03, BOOT_Z),           "leg_l"),
    ("foot_r",   (-HIP_X, 0.0, KNEE_Z),             (-HIP_X, -0.03, BOOT_Z),           "leg_r"),
]

COLLECTION = "REFERENCE"


def _fresh_collection(name):
    old = bpy.data.collections.get(name)
    if old:
        for obj in list(old.objects):
            bpy.data.objects.remove(obj, do_unlink=True)
        bpy.data.collections.remove(old)
    made = bpy.data.collections.new(name)
    bpy.context.scene.collection.children.link(made)
    return made


def _material(name, rgb):
    """A flat colour. Looked up by node *type*, because the node's name is
    localised and has been renamed between Blender versions."""
    mat = bpy.data.materials.get(name)
    if mat is None:
        mat = bpy.data.materials.new(name)
    mat.use_nodes = True
    for node in mat.node_tree.nodes:
        if node.type == "BSDF_PRINCIPLED" and "Base Color" in node.inputs:
            node.inputs["Base Color"].default_value = (rgb[0], rgb[1], rgb[2], 1.0)
            break
    mat.diffuse_color = (rgb[0], rgb[1], rgb[2], 1.0)
    return mat


def _box(collection, name, centre, half, material):
    cx, cy, cz = centre
    hx, hy, hz = half
    verts = [(cx + sx * hx, cy + sy * hy, cz + sz * hz)
             for sx, sy, sz in ((-1, -1, -1), (1, -1, -1), (1, 1, -1), (-1, 1, -1),
                                (-1, -1, 1), (1, -1, 1), (1, 1, 1), (-1, 1, 1))]
    faces = [(0, 3, 2, 1), (4, 5, 6, 7), (0, 1, 5, 4),
             (2, 3, 7, 6), (1, 2, 6, 5), (3, 0, 4, 7)]
    mesh = bpy.data.meshes.new(name)
    mesh.from_pydata(verts, [], faces)
    mesh.update()
    mesh.materials.append(material)
    obj = bpy.data.objects.new(name, mesh)
    collection.objects.link(obj)
    return obj


def _to_object_mode():
    """Operators below need Object mode and will not survive Edit mode."""
    obj = bpy.context.view_layer.objects.active
    if obj is not None and obj.mode != "OBJECT":
        bpy.ops.object.mode_set(mode="OBJECT")


def build():
    _to_object_mode()
    collection = _fresh_collection(COLLECTION)

    body = _material("REFERENCE_body", (0.10, 0.16, 0.08))
    skin = _material("REFERENCE_skin", (0.55, 0.32, 0.18))
    hat = _material("REFERENCE_hat", (0.42, 0.38, 0.16))
    for name, centre, half in PARTS:
        if name in ("head", "hand_l", "hand_r"):
            paint = skin
        elif name.startswith("hat"):
            paint = hat
        else:
            paint = body
        obj = _box(collection, "REF_" + name, centre, half, paint)
        obj.display_type = "SOLID"

    armature = bpy.data.armatures.new("REFERENCE_rig")
    rig = bpy.data.objects.new("REFERENCE_rig", armature)
    collection.objects.link(rig)

    # Edit mode is the only place edit_bones exists. The rig has to be both
    # active *and* selected for the operator to take, which is the one thing
    # that bites when this is run from a fresh scene.
    for other in bpy.context.view_layer.objects:
        other.select_set(False)
    bpy.context.view_layer.objects.active = rig
    rig.select_set(True)
    bpy.ops.object.mode_set(mode="EDIT")
    try:
        made = {}
        for name, head, tail, parent in BONES:
            bone = armature.edit_bones.new(name)
            bone.head = Vector(head)
            bone.tail = Vector(tail)
            bone.use_connect = False
            if parent:
                bone.parent = made[parent]
            made[name] = bone
    finally:
        bpy.ops.object.mode_set(mode="OBJECT")
    rig.show_in_front = True

    print("REFERENCE built: %d boxes, %d bones. "
          "Model your piece in place, parent it to a bone, then delete the "
          "REFERENCE collection before exporting."
          % (len(PARTS), len(BONES)))
    return rig


if __name__ == "__main__":
    build()
