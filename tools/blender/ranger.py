"""
Build the forest ranger in Blender, ready to export as a character `.glb`.

Run it from Blender's Scripting tab (Open, then Run Script) or with

    blender --python tools/blender/ranger.py

It makes one collection called RANGER holding fourteen mesh objects — one
per bone — an armature called `ranger_rig`, and six actions: `idle`, `walk`,
`run`, `swim`, `row` and `alert`. Nothing is exported; see EXPORT at the foot
of this file for the settings that matter and for which name to save under.

**This figure is the player.** Filed as `characters/walker.glb` it replaces
the walker — you in third person, and everybody else in the clearing — which
is why it carries the clips it does and a pack on its back. The five besides
`alert` are every state a walker is ever drawn in, and that is the point: a
state with no clip is posed by a procedural table that works per piece about
each bone's own pivot, which comes apart at a run's angles and comes apart
completely at a swimmer's. The pack is there because the back is the side of
yourself you spend the game looking at.

Filed as `characters/ranger.glb` instead it is the figure outside the trading
post, which needs only `idle`. Do not file it as both, or every ranger in the
world is the player's twin.

The contract is `src/main/resources/watch/models/README.md` §8-§15, and the
ranger's own numbers are `BLENDER_BRIEF.md` §2. Where this file and those
disagree, those are right. The box model this replaces is
`src/main/java/com/larsons/engine/watch/render/RangerModel.java`, and it is
what the proportions and the palette below are taken from, landmark by
landmark, so that dropping the `.glb` in changes what the ranger looks like
and not how big they are or where they stand.

--- The three facts that shape everything here ----------------------------

**Blender's coordinates are the game's coordinates.**

  +Z  up
  -Y  the way the ranger faces
  +X  the side `_l` bones go on, and the side the satchel is on

**Colour is materials, not textures.** Every triangle takes its colour from
its material's base colour; a UV out of this file would point at whatever
happened to be next to that tile in the world atlas. So the detail here is
carried by *how many* flat colours there are and *which faces* get them —
twenty-one materials over eighty-odd boxes, including per-face painting
where a single box wants two colours (the underside of the hat brim, the
hem of the coat). Type the hex you want: the importer converts back out of
linear, so the hex in PALETTE is the hex the game draws.

**Rigid parts, not one skin.** The importer gives each *triangle* to the
single bone with the most weight across its corners and moves it rigidly.
So the ranger is built the way a wooden mannequin is: separate pieces that
overlap at the joints, each one wholly weighted to its own bone. There is
nothing here to weight-paint.
"""

import math

import bpy
import bmesh
from mathutils import Matrix, Quaternion, Vector

COLLECTION = "RANGER"
RIG = "ranger_rig"

# --- the palette -----------------------------------------------------------
#
# The twelve from the brief, as sRGB hex, exactly as RangerModel's own
# constants spell them. Everything else in DERIVED below is one of these
# scaled the way ShopModel.shade scales it, which is what the box model does
# for its own second and third tones — so a modelled ranger and a boxed one
# are painted out of the same tin.

PALETTE = {
    "coat":          0x3C5240,   # field coat, sleeves, hat crown
    "coat_dark":     0x2E4033,   # coat skirt, hat brim, pockets
    "coat_light":    0x4A6450,   # collar, cuffs, shoulder yokes
    "trouser":       0x6B6247,   # trousers
    "leather":       0x4A3626,   # boots, belt, satchel, binocular bodies
    "leather_light": 0x5C4433,   # boot cuffs, satchel flap
    "brass":         0xB8A050,   # buckle, badge, canteen fittings
    "glass":         0x243230,   # binocular lenses
    "skin":          0xC98F63,   # face, hands
    "hair":          0x4A3220,   # hair, brows, beard
    "trim":          0xA8442E,   # neckerchief, hatband, bedroll
    "eye":           0x241C18,   # eyes
}

# name -> (which palette colour, how much of it). ShopModel.shade, in Python.
DERIVED = {
    "coat_shadow":   ("coat", 0.70),        # brim underside, coat hem
    "trouser_shin":  ("trouser", 0.94),
    "trouser_knee":  ("trouser", 0.86),
    "leather_dark":  ("leather", 0.72),     # soles, toe caps
    "boot_mud":      ("leather", 0.86),     # the band above the sole
    "brass_dark":    ("brass", 0.70),       # canteen body, binocular bridge
    "skin_shadow":   ("skin", 0.94),        # jaw, ears, neck
    "skin_light":    ("skin", 1.04),        # nose
    "hair_dark":     ("hair", 0.90),        # brows
    "trim_dark":     ("trim", 0.80),        # bedroll, the hanging corner
}

# --- the landmarks ---------------------------------------------------------
#
# Metres, sole to crown, straight out of BLENDER_BRIEF.md's table. Named
# because half of them are used three times each and a hand-typed 1.18 in
# the wrong place is a shoulder that does not line up with its own sleeve.

SOLE = 0.00
ANKLE_Z = 0.07
KNEE_Z = 0.37
HIP_Z = 0.69
BELT_Z = 0.78
WAIST_Z = 0.95          # where the spine bone pivots
CHEST_Z = 1.03
GLASS_Z = 1.00          # binoculars, at rest on the chest
SHOULDER_Z = 1.18
NECK_Z = 1.27           # the head bone's pivot: the base of the neck
HEAD_Z = 1.45
BRIM_Z = 1.59
CROWN_Z = 1.78          # the top of the ranger

HIP_X = 0.105
SHOULDER_X = 0.205

ELBOW_Z = 0.90
WRIST = Vector((SHOULDER_X, -0.055, 0.645))   # the arm hangs a little forward

HEIGHT = CROWN_Z - SOLE                        # 1.78, and RangerModel.HEIGHT

# Where a rower's hips sit above the floorboards they brace their feet on.
# `BoatModel.DEPTH * 0.76` is the thwart above the floor, and a seated hip
# joint is about 110 mm above the plank — the same two numbers WalkerModel's
# boxed rower is built from, so the two figures sit at the same height in the
# same boat. See the `row` clip.
SEAT_Z = 0.46


# --- colour ----------------------------------------------------------------

def _linear(channel):
    """One sRGB byte as the linear value Blender's colour picker stores."""
    c = channel / 255.0
    return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4


def _shade(rgb, scale):
    """ShopModel.shade: a scale in sRGB space, clamped, byte by byte."""
    r = min(255, int(((rgb >> 16) & 0xFF) * scale))
    g = min(255, int(((rgb >> 8) & 0xFF) * scale))
    b = min(255, int((rgb & 0xFF) * scale))
    return (r << 16) | (g << 8) | b


def _material(name, rgb):
    """A flat colour, as a Principled BSDF with nothing plugged into it.

    Base Color is set in linear because that is what Blender stores and what
    glTF writes; the importer converts back, so the byte in `rgb` is the byte
    the game draws. Looked up by node *type* rather than name, which is
    localised and has been renamed between Blender versions.
    """
    mat = bpy.data.materials.get(name)
    if mat is None:
        mat = bpy.data.materials.new(name)
    mat.use_nodes = True
    linear = (_linear((rgb >> 16) & 0xFF), _linear((rgb >> 8) & 0xFF),
              _linear(rgb & 0xFF))
    for node in mat.node_tree.nodes:
        if node.type == "BSDF_PRINCIPLED":
            node.inputs["Base Color"].default_value = linear + (1.0,)
            if "Roughness" in node.inputs:
                node.inputs["Roughness"].default_value = 1.0
            if "Metallic" in node.inputs:
                node.inputs["Metallic"].default_value = 0.0
            break
    mat.diffuse_color = linear + (1.0,)
    return mat


def materials():
    made = {name: _material(name, rgb) for name, rgb in PALETTE.items()}
    for name, (source, scale) in DERIVED.items():
        made[name] = _material(name, _shade(PALETTE[source], scale))
    return made


# --- geometry --------------------------------------------------------------
#
# A Part is a heap of world-space quads with a material name on each, which
# is all any of this is. They are gathered per bone and welded into one mesh
# per bone at the end, because one object per bone is one vertex group per
# bone and no weight painting at all.

# The order _box writes its faces in, so a part can name one of them.
FACES = ("-z", "+z", "-y", "+y", "+x", "-x")


BURIED = 1e-6


class Part:
    """Quads and their materials, in world coordinates."""

    def __init__(self):
        self.verts = []
        self.faces = []
        self.mats = []
        self.solids = []

    def add(self, verts, faces, mats):
        base = len(self.verts)
        self.verts.extend(verts)
        self.faces.extend(tuple(base + i for i in face) for face in faces)
        self.mats.extend(mats)

    def bury(self):
        """Drop the faces sealed inside another box of this same piece.

        **This is where the budget for the detail comes from.** The figure is
        built out of overlapping boxes — that is what stops daylight showing
        at the joints — and an overlap means faces that are inside solid
        wood: the top of the neck inside the skull, the base of the hat's
        peak inside its crown, the whole of the pyjama-striped nothing under
        the coat. Nobody will ever see one, and every one of them costs two
        triangles the software rasteriser has to walk.

        Only boxes in the *same* piece may seal a face, because a piece is
        the unit that moves rigidly. A face hidden inside the collar is not
        hidden at all once the head turns and takes the collar's neighbour
        with it.

        Strictly inside, so a face lying flush on another box's surface
        survives. Those are visible about half the time, and telling which
        half is a z-fighting argument rather than an arithmetic one.
        """
        faces, mats, used = [], [], set()
        for face, material in zip(self.faces, self.mats):
            points = [self.verts[i] for i in face]
            if any(all(lo[a] + BURIED <= p[a] <= hi[a] - BURIED
                       for p in points for a in (0, 1, 2))
                   for lo, hi in self.solids):
                continue
            faces.append(face)
            mats.append(material)
            used.update(face)
        # Re-index, or the orphaned corners ship as loose vertices.
        order = sorted(used)
        at = {v: i for i, v in enumerate(order)}
        self.verts = [self.verts[v] for v in order]
        self.faces = [tuple(at[i] for i in face) for face in faces]
        self.mats = mats
        return self

    def box(self, centre, size, material, faces=None, rotation=None):
        """A box, by its centre and its *full* size — width, depth, height.

        Full sizes rather than half-extents because BLENDER_BRIEF.md's table
        is written in full sizes, and converting them here would only be a
        chance to halve one of them twice.

        `faces` overrides the material on named faces: {"-z": "coat_shadow"}
        paints the underside alone, which is how one box carries two colours
        without costing a second box.
        """
        cx, cy, cz = centre
        hx, hy, hz = size[0] / 2, size[1] / 2, size[2] / 2
        corners = [Vector((sx * hx, sy * hy, sz * hz))
                   for sx, sy, sz in ((-1, -1, -1), (1, -1, -1), (1, 1, -1),
                                      (-1, 1, -1), (-1, -1, 1), (1, -1, 1),
                                      (1, 1, 1), (-1, 1, 1))]
        if rotation is not None:
            corners = [rotation @ v for v in corners]
        verts = [(cx + v.x, cy + v.y, cz + v.z) for v in corners]
        quads = [(0, 3, 2, 1), (4, 5, 6, 7), (0, 1, 5, 4),
                 (2, 3, 7, 6), (1, 2, 6, 5), (3, 0, 4, 7)]
        paint = [(faces or {}).get(side, material) for side in FACES]
        self.add(verts, quads, paint)
        if rotation is None:
            # An axis-aligned box can seal another box's faces. A turned one
            # is skipped rather than approximated: its bounding box is not
            # itself, and a face culled against the corner of a box that is
            # not there is a hole in the model.
            self.solids.append(((cx - hx, cy - hy, cz - hz),
                                (cx + hx, cy + hy, cz + hz)))

    def strut(self, start, end, width, depth, material):
        """A box laid between two points — a sleeve, a strap, a bedroll.

        Built axis-aligned and then turned onto the line, so that the object
        it lands in still has an identity transform and `Ctrl+A` has nothing
        left to do.
        """
        start, end = Vector(start), Vector(end)
        along = end - start
        length = along.length
        turn = Vector((0, 0, 1)).rotation_difference(along.normalized())
        self.box((start + end) / 2, (width, depth, length), material,
                 rotation=turn.to_matrix())

    def pyramid(self, centre_xy, base_z, base_size, apex_z, material):
        """Four sides to a point: the campaign hat's peak.

        The one shape here that is not a box, and the reason is the
        silhouette — the keeper's hat is a brim and stops, and these two have
        to be tellable apart from behind at two hundred metres.
        """
        cx, cy = centre_xy
        h = base_size / 2
        verts = [(cx - h, cy - h, base_z), (cx + h, cy - h, base_z),
                 (cx + h, cy + h, base_z), (cx - h, cy + h, base_z),
                 (cx, cy, apex_z)]
        faces = [(0, 3, 2, 1), (0, 1, 4), (1, 2, 4), (2, 3, 4), (3, 0, 4)]
        self.add(verts, faces, [material] * 5)


def mirrored(part, spec):
    """Emit a spec at +X and again at -X.

    Everything two-of is written once, on the +X side — which is the `_l`
    side, the side the satchel is on, and the side the brief's numbers are
    quoted from.
    """
    for side in (1, -1):
        kind = spec[0]
        if kind == "box":
            _, centre, size, material = spec[:4]
            faces = spec[4] if len(spec) > 4 else None
            part.box((side * centre[0], centre[1], centre[2]), size, material,
                     faces=faces)
        elif kind == "strut":
            _, start, end, width, depth, material = spec
            part.strut((side * start[0], start[1], start[2]),
                       (side * end[0], end[1], end[2]), width, depth, material)


# --- the ranger, bone by bone ----------------------------------------------

def head_part():
    """The face, the hair on it, and the hat that is the whole silhouette.

    Everything on the head goes on the head bone, neckerchief included —
    that is what makes it all turn together when the ranger looks at you,
    which they do, up to about 66 degrees, for free.
    """
    p = Part()
    p.box((0, 0, 1.285), (0.15, 0.15, 0.10), "skin_shadow")            # neck
    p.box((0, 0, HEAD_Z), (0.31, 0.29, 0.33), "skin")                  # skull
    p.box((0, -0.012, 1.32), (0.26, 0.25, 0.10), "skin_shadow")        # jaw
    p.box((0, -0.158, 1.41), (0.05, 0.05, 0.055), "skin_light")        # nose
    mirrored(p, ("box", (0.157, 0.005, 1.44), (0.03, 0.05, 0.075), "skin_shadow"))
    # Two brows rather than one bar across: a single box the width of the
    # face reads as a scowl from every distance, and two read as a face.
    mirrored(p, ("box", (0.068, -0.138, 1.508), (0.095, 0.03, 0.028), "hair_dark"))
    mirrored(p, ("box", (0.068, -0.148, 1.462), (0.038, 0.02, 0.036), "eye"))

    p.box((0, -0.128, 1.552), (0.30, 0.06, 0.045), "hair")             # fringe
    # Wider than the skull it sits on, or the corners of the head show as
    # two strips of scalp from directly behind.
    p.box((0, 0.135, 1.46), (0.325, 0.055, 0.18), "hair")              # back
    mirrored(p, ("box", (0.152, 0.03, 1.42), (0.025, 0.10, 0.11), "hair"))
    p.box((0, 0.175, 1.375), (0.09, 0.06, 0.10), "hair_dark")          # tied back
    p.box((0, -0.105, 1.315), (0.19, 0.10, 0.08), "hair")              # beard
    p.box((0, -0.14, 1.365), (0.14, 0.06, 0.04), "hair")               # moustache

    # The neckerchief, worn high: a band round the throat that stands proud
    # of the jaw on every side, with one corner hanging down the front. It
    # sits *above* the collar, which is why the collar is at 1.235 and not
    # at the jawline — there is no room for both, and this is the one thing
    # on the uniform that is the ranger's own colour.
    p.box((0, -0.01, 1.29), (0.28, 0.26, 0.06), "trim")
    p.box((0, -0.125, 1.20), (0.09, 0.035, 0.12), "trim_dark")

    # The hat. A flat brim with a darker underside, the hatband in the
    # ranger's own colour, a box of a crown and a four-sided peak on top.
    p.box((0, 0, BRIM_Z), (0.64, 0.60, 0.03), "coat_dark",
          faces={"-z": "coat_shadow"})
    p.box((0, 0, 1.61), (0.35, 0.34, 0.03), "trim")
    p.box((0, 0, 1.65), (0.33, 0.33, 0.12), "coat")
    p.pyramid((0, 0), 1.705, 0.30, CROWN_Z, "coat")
    p.box((0, -0.168, 1.655), (0.05, 0.022, 0.05), "brass")            # badge
    return p


def spine_part():
    """The coat, and everything hung off it.

    The coat is wider than the body inside it and the skirt is wider still,
    which is what makes it read as a coat worn over somebody rather than as
    the shape of them.
    """
    p = Part()
    p.box((0, 0, CHEST_Z), (0.33, 0.29, 0.46), "coat")
    p.box((0, 0, 1.235), (0.25, 0.23, 0.06), "coat_light")             # collar
    mirrored(p, ("box", (0.075, -0.118, 1.222), (0.08, 0.03, 0.05), "coat_light"))
    p.box((0, -0.148, 1.05), (0.06, 0.02, 0.40), "coat_dark")          # placket
    for z in (1.18, 1.05, 0.92):
        p.box((0, -0.162, z), (0.028, 0.02, 0.028), "brass")           # buttons

    # Patch pockets and their flaps, both sides of the chest — two boxes
    # each, and the coat stops being a slab.
    mirrored(p, ("box", (0.075, -0.147, 1.05), (0.11, 0.04, 0.11), "coat_dark"))
    mirrored(p, ("box", (0.075, -0.150, 1.113), (0.118, 0.045, 0.032), "coat_light"))
    # Shoulder yokes, a shade lighter: what reads as a uniform at any
    # distance you can still see a person at.
    mirrored(p, ("box", (0.15, 0, 1.17), (0.15, 0.26, 0.06), "coat_light"))

    p.box((0, 0, BELT_Z), (0.34, 0.30, 0.08), "leather")
    p.box((0, -0.152, BELT_Z), (0.07, 0.035, 0.07), "brass")           # buckle
    mirrored(p, ("box", (0.10, -0.152, BELT_Z), (0.03, 0.02, 0.085), "leather_dark"))
    # A sheath knife on the front of the belt, clear of where the hands hang.
    p.box((-0.115, -0.135, 0.700), (0.05, 0.09, 0.15), "leather_dark")
    p.box((-0.115, -0.135, 0.790), (0.035, 0.055, 0.06), "leather_light")
    p.box((0, 0, 0.60), (0.36, 0.32, 0.34), "coat_dark",
          faces={"-z": "coat_shadow"})                                 # skirt
    p.box((0, 0, 0.445), (0.365, 0.325, 0.03), "coat_shadow")          # hem
    p.box((0, 0.158, 0.55), (0.05, 0.02, 0.24), "coat_shadow")         # back vent

    # A satchel on one hip and a canteen on the other, which is the pair of
    # asymmetries that stop the figure reading as a mannequin.
    #
    # **Both are hung wider and further back than the brief's table puts
    # them**, at 0.215 across and 0.055 behind, and for two reasons that the
    # boxes never had to care about: a skirt 0.36 wide swallows anything at
    # 0.185, so the satchel was a dark shape on a dark coat rather than a
    # bag; and the hands hang at 0.15-0.26 across, which is exactly where
    # the bag was, so a swinging arm passed through it.
    p.box((0.215, 0.055, 0.67), (0.15, 0.10, 0.20), "leather")
    p.box((0.215, 0.055, 0.755), (0.16, 0.11, 0.06), "leather_light")
    p.box((0.215, 0.000, 0.715), (0.03, 0.022, 0.03), "brass")
    p.box((-0.215, 0.055, 0.70), (0.11, 0.075, 0.13), "brass_dark")
    p.box((-0.215, 0.055, 0.782), (0.045, 0.045, 0.035), "brass")

    # The field pack, and the bedroll lashed across the top of it.
    #
    # **This is the side of the figure the player actually looks at.** In
    # third person the camera is behind them, so the back is the view they
    # have of themselves for the whole game — and a coat and a bedroll was
    # the back of somebody standing about outside a shop, which is what this
    # model was for before it was the player.
    # Deep enough to reach the coat's back face at Y 0.145 and overlap it.
    # At 0.17 it stopped 15 mm short, and 15 mm of daylight between a pack
    # and the back carrying it reads, side on, as a bag floating behind
    # somebody.
    p.box((0, 0.235, 1.02), (0.30, 0.19, 0.32), "leather")
    p.box((0, 0.235, 1.205), (0.31, 0.20, 0.06), "leather_light")      # flap
    mirrored(p, ("box", (0.165, 0.245, 0.98), (0.06, 0.14, 0.18), "leather_light"))
    mirrored(p, ("box", (0.09, 0.335, 1.13), (0.035, 0.025, 0.035), "brass"))
    mirrored(p, ("box", (0.09, 0.335, 1.185), (0.045, 0.02, 0.125), "leather_dark"))
    # **The bedroll is lashed under the pack, not across the shoulders.** It
    # was on the shoulders while this was the ranger, and at that height it
    # sat at exactly the neckerchief's — two bands of the same trim colour
    # touching, which from behind is one red smear rather than a scarf and a
    # blanket. Under the pack it reads as itself, and the neck reads as a
    # neck.
    p.strut((-0.22, 0.245, 0.865), (0.22, 0.245, 0.885), 0.10, 0.10, "trim_dark")
    mirrored(p, ("box", (0.14, 0.245, 0.875), (0.03, 0.115, 0.115), "leather"))
    # Over the shoulders and down the chest, which is what says it is being
    # carried rather than floating behind them.
    mirrored(p, ("strut", (0.10, 0.20, 1.20), (0.105, -0.14, 1.00),
                 0.045, 0.022, "leather"))

    # The binoculars, resting on the chest on a strap round the neck.
    mirrored(p, ("box", (0.036, -0.17, GLASS_Z), (0.06, 0.12, 0.06), "leather"))
    p.box((0, -0.17, GLASS_Z), (0.045, 0.06, 0.045), "brass_dark")
    mirrored(p, ("box", (0.036, -0.238, GLASS_Z), (0.05, 0.02, 0.05), "glass"))
    mirrored(p, ("strut", (0.13, 0.02, 1.20), (0.045, -0.155, 1.045),
                 0.022, 0.012, "leather"))
    return p


def arm_part(side):
    """Upper arm and the elbow it bends at, with the service patch on it."""
    p = Part()
    x = side * SHOULDER_X
    p.box((x, 0, 1.04), (0.14, 0.14, 0.28), "coat")
    p.box((x, -0.012, 0.898), (0.148, 0.15, 0.075), "coat_dark")
    # A service patch on the upper sleeve, in the ranger's own trim — wider
    # than the sleeve it sits on, so it is a patch and not a stain.
    p.box((x, -0.02, 1.09), (0.15, 0.09, 0.055), "trim")
    return p


def forearm_part(side):
    """Forearm and rolled cuff, on a bone of their own.

    **The elbow used to be modelled in rather than rigged**, as a permanent
    forward set, because the ranger's contract had no bone between the
    shoulder and the wrist and a figure standing outside a shop does not
    need one. A breaststroke and a pull on a pair of oars both do: the arms
    sweep and fold, and an arm that cannot fold does the whole stroke as one
    rigid oar of its own.
    """
    p = Part()
    x = side * SHOULDER_X
    p.strut((x, -0.010, ELBOW_Z), (x, WRIST.y, WRIST.z), 0.12, 0.12, "coat")
    p.box((x, WRIST.y + 0.005, WRIST.z + 0.03), (0.13, 0.13, 0.05), "coat_light")
    return p


def hand_part(side):
    p = Part()
    x = side * SHOULDER_X
    p.box((x, -0.062, 0.588), (0.11, 0.11, 0.11), "skin")
    p.box((x - side * 0.062, -0.075, 0.60), (0.035, 0.04, 0.055), "skin")
    p.box((x, -0.062, 0.628), (0.115, 0.115, 0.035), "leather_dark")   # glove band
    return p


def leg_part(side):
    """Thigh, knee and the cargo pocket on it."""
    p = Part()
    x = side * HIP_X
    p.box((x, 0, 0.525), (0.18, 0.18, 0.33), "trouser")
    p.box((x, -0.006, KNEE_Z), (0.165, 0.175, 0.06), "trouser_knee")
    # A cargo pocket on the thigh, and a flap over it.
    p.box((x, -0.055, 0.50), (0.19, 0.09, 0.16), "trouser_knee")
    p.box((x, -0.058, 0.585), (0.195, 0.095, 0.035), "trouser")
    return p


def shin_part(side):
    """Below the knee.

    Split off for the two poses that fold a leg in half — a rower's, with
    their thighs along the thwart and their shins dropped to the
    floorboards, and a breaststroke's frog kick. A walk gets it for nothing:
    the swinging leg now folds at the knee instead of scything through as
    one rigid plank.
    """
    p = Part()
    x = side * HIP_X
    p.box((x, 0, 0.22), (0.15, 0.15, 0.30), "trouser_shin")
    return p


def foot_part(side):
    """A boot, a sole, a toe cap, a tall cuff over the trouser — and mud.

    The mud is a band of its own material just above the sole rather than
    shading painted onto the boot, because a face painted dark to fake a
    shadow is dark on the sunny side too. This one is dirt, and dirt is dark
    on both sides.
    """
    p = Part()
    x = side * HIP_X
    p.box((x, -0.03, 0.045), (0.18, 0.27, 0.09), "leather")
    p.box((x, -0.03, 0.011), (0.19, 0.28, 0.022), "leather_dark")
    p.box((x, -0.03, 0.030), (0.185, 0.275, 0.026), "boot_mud")
    p.box((x, -0.135, 0.055), (0.17, 0.075, 0.07), "leather_dark")
    p.box((x, 0, 0.16), (0.18, 0.20, 0.17), "leather_light")
    p.box((x, -0.098, 0.16), (0.06, 0.022, 0.14), "leather_dark")      # laces
    p.box((x, 0.075, 0.018), (0.17, 0.09, 0.036), "leather_dark")      # heel
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
# The names are the entire binding contract — README §10. The neck bone is
# at the *base* of the neck rather than inside the skull, because the game
# turns the head about it and a pivot in the middle of a skull swivels like
# a turret.
#
# `_l` is +X. Not anatomy: it is what keeps a modelled gaiter swinging with
# the boot inside it, since the boxes swing the +X leg on sin(phase) and the
# importer's fallback swings `left` on sin(phase) too.

#
# **Four more bones than the ranger's contract asks for**, and every one of
# them still binds where §10 says: `forearm_*` matches on `arm`, `shin_*` on
# `shin`, and the `_l`/`_r` ending puts each on its own side. They are here
# because a rower folds at the knee and a breaststroke folds at both — a leg
# that cannot bend rows with its ankles on the thwart.
#
# The cost of an extra bone is the procedural fallback, which poses each
# piece about its own pivot rather than down the chain: in a state this file
# ships no clip for, a shin would rotate about the knee it is still standing
# at. That is why the clip list below covers every state a walker is drawn
# in. See `run` for the long version.

BONES = [
    # name,       head,                            tail,                             parent
    ("root",      (0, 0, 0.0),                     (0, 0, WAIST_Z),                  None),
    ("spine",     (0, 0, WAIST_Z),                 (0, 0, NECK_Z),                   "root"),
    ("head",      (0, 0, NECK_Z),                  (0, 0, 1.62),                     "spine"),
    ("arm_l",     (SHOULDER_X, 0, SHOULDER_Z),     (SHOULDER_X, -0.01, ELBOW_Z),     "spine"),
    ("forearm_l", (SHOULDER_X, -0.01, ELBOW_Z),    tuple(WRIST),                     "arm_l"),
    ("hand_l",    tuple(WRIST),                    (SHOULDER_X, -0.07, 0.53),        "forearm_l"),
    ("arm_r",     (-SHOULDER_X, 0, SHOULDER_Z),    (-SHOULDER_X, -0.01, ELBOW_Z),    "spine"),
    ("forearm_r", (-SHOULDER_X, -0.01, ELBOW_Z),   (-WRIST.x, WRIST.y, WRIST.z),     "arm_r"),
    ("hand_r",    (-WRIST.x, WRIST.y, WRIST.z),    (-SHOULDER_X, -0.07, 0.53),       "forearm_r"),
    ("leg_l",     (HIP_X, 0, HIP_Z),               (HIP_X, 0, KNEE_Z),               "spine"),
    ("shin_l",    (HIP_X, 0, KNEE_Z),              (HIP_X, 0, ANKLE_Z),              "leg_l"),
    ("foot_l",    (HIP_X, 0, ANKLE_Z),             (HIP_X, -0.16, 0.045),            "shin_l"),
    ("leg_r",     (-HIP_X, 0, HIP_Z),              (-HIP_X, 0, KNEE_Z),              "spine"),
    ("shin_r",    (-HIP_X, 0, KNEE_Z),             (-HIP_X, 0, ANKLE_Z),             "leg_r"),
    ("foot_r",    (-HIP_X, 0, ANKLE_Z),            (-HIP_X, -0.16, 0.045),           "shin_r"),
]


# --- building it -----------------------------------------------------------

def _fresh_collection(name):
    old = bpy.data.collections.get(name)
    if old:
        for obj in list(old.objects):
            bpy.data.objects.remove(obj, do_unlink=True)
        bpy.data.collections.remove(old)
    made = bpy.data.collections.new(name)
    bpy.context.scene.collection.children.link(made)
    return made


def _to_object_mode():
    obj = bpy.context.view_layer.objects.active
    if obj is not None and obj.mode != "OBJECT":
        bpy.ops.object.mode_set(mode="OBJECT")


def _mesh_object(collection, name, part, palette):
    """One bone's worth of boxes, welded into one triangulated mesh.

    Triangulated here rather than left to a modifier so that the file is
    triangulated however it is exported: a quad is otherwise fanned about
    its first corner, which is right for a convex face and wrong for the
    rest.
    """
    used = []
    for material in part.mats:
        if material not in used:
            used.append(material)
    slot = {name: i for i, name in enumerate(used)}

    mesh = bpy.data.meshes.new(name)
    mesh.from_pydata(part.verts, [], part.faces)
    mesh.update()
    for material in used:
        mesh.materials.append(palette[material])
    for polygon, material in zip(mesh.polygons, part.mats):
        polygon.material_index = slot[material]

    bm = bmesh.new()
    bm.from_mesh(mesh)
    bmesh.ops.triangulate(bm, faces=bm.faces[:])
    bm.to_mesh(mesh)
    bm.free()
    mesh.update()
    # Flat everywhere. A subdivided sphere costs three hundred triangles to
    # look exactly like a faceted one from six metres away.
    for polygon in mesh.polygons:
        polygon.use_smooth = False

    obj = bpy.data.objects.new(name, mesh)
    collection.objects.link(obj)
    return obj


def build_rig(collection):
    armature = bpy.data.armatures.new(RIG)
    rig = bpy.data.objects.new(RIG, armature)
    collection.objects.link(rig)

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
    return rig


def bind(obj, rig, bone):
    """Every vertex to one bone, at weight one.

    Which is all the rigging this renderer can use: it takes the dominant
    bone per triangle and moves it rigidly, so a gradient across a joint
    would be thrown away. The pieces overlap at the joints instead, which is
    what stops daylight showing through when a limb swings.
    """
    group = obj.vertex_groups.new(name=bone)
    group.add(range(len(obj.data.vertices)), 1.0, "REPLACE")
    modifier = obj.modifiers.new("Armature", "ARMATURE")
    modifier.object = rig
    obj.parent = rig
    obj.matrix_parent_inverse = rig.matrix_world.inverted()


# --- animation -------------------------------------------------------------
#
# Written in *world* axes and converted into each bone's own space on the
# way in, so that "swing the leg forward" is a number about global X here
# and not a guess about which way a bone's roll happens to point.
#
#   about +X, negative  ->  swings a limb forward, the way the ranger faces
#   about +Y, positive  ->  swings a limb toward -X
#   about +Z, positive  ->  turn toward +X
#
# **The first two are written for a limb, and a limb hangs below its pivot.**
# Anything that stands *above* its pivot — the spine on the waist, the head
# on the neck — goes the other way, because the sign follows which side of
# the joint the geometry is on and not which bone it is. So a positive pitch
# leans a chest forward and swings a leg back, and both of those are the same
# rotation. It reads as a trap and it is one: the ranger ran for a while with
# a 0.085 rad backward lean that nobody could see and everybody could feel.
#
# No IK, no constraints, no shape keys: none of them are read, and the
# exporter bakes what is here to keyframes on the bones anyway.

FPS = 24


def _local_axis(rig, bone, axis):
    """A world axis, in the bone's own rest space."""
    rest = rig.data.bones[bone].matrix_local.to_3x3()
    return (rest.inverted() @ Vector(axis)).normalized()


def _pose(rig, bone, frame, pitch=0.0, roll=0.0, yaw=0.0, shift=None):
    """One bone at one frame: three world-axis turns and an offset."""
    pb = rig.pose.bones[bone]
    pb.rotation_mode = "QUATERNION"
    turn = Quaternion((1, 0, 0), 0)
    for axis, angle in (((1, 0, 0), pitch), ((0, 1, 0), roll), ((0, 0, 1), yaw)):
        if angle:
            turn = turn @ Quaternion(_local_axis(rig, bone, axis), angle)
    pb.rotation_quaternion = turn
    pb.keyframe_insert("rotation_quaternion", frame=frame)
    if shift is not None:
        rest = rig.data.bones[bone].matrix_local.to_3x3()
        pb.location = rest.inverted() @ Vector(shift)
        pb.keyframe_insert("location", frame=frame)


def _action(rig, name):
    """A named, empty action — and the last one of that name, gone.

    Running this script twice would otherwise leave `idle.001` beside
    `idle`, both with fake users, and both would be exported: the importer
    reads a clip's name as a `_`-separated prefix, so `idle_001` claims IDLE
    as loudly as `idle` does and which one wins is whichever it saw last.
    """
    for old in list(bpy.data.actions):
        if old.name.split(".")[0] == name:
            old.use_fake_user = False
            bpy.data.actions.remove(old)
    action = bpy.data.actions.new(name)
    action.use_fake_user = True
    if rig.animation_data is None:
        rig.animation_data_create()
    rig.animation_data.action = action
    return action


def _rest(rig):
    for pb in rig.pose.bones:
        pb.rotation_mode = "QUATERNION"
        pb.rotation_quaternion = Quaternion((1, 0, 0), 0)
        pb.location = Vector((0, 0, 0))


def _torso(rig, frame, pitch=0.0, roll=0.0, yaw=0.0, shift=(0, 0, 0),
           swing=0.0, knee=(0.0, 0.0)):
    """The spine, and the legs that have to undo it.

    **The one thing worth understanding about animating this rig.** The legs
    hang off `spine` — that is the bone tree the importer's contract asks
    for — so every breath the chest takes lifts both boots off the ground
    with it, and every lean drags them sideways. A 12 mm breath is a figure
    hovering 12 mm over the turf, which is exactly the sort of thing nobody
    sees in Blender and everybody sees in a clearing at dusk.

    So the legs are given the inverse of whatever the spine just did, and
    the walk's own swing is added on top of that. It is not an exact inverse
    — the two rotations are about different pivots, 260 mm apart — but at
    the angles here the error is under a millimetre, and
    `floor_over` in the checks below is what says so.

    `swing` is the hip swing, positive for the +X leg forward. `knee` is how
    far each knee folds — positive draws the heel up behind, which is the
    only way it bends.
    """
    _pose(rig, "spine", frame, pitch=pitch, roll=roll, yaw=yaw, shift=shift)
    back = (-shift[0], -shift[1], -shift[2])
    for leg, shin, foot, side, bend in (("leg_l", "shin_l", "foot_l", 1, knee[0]),
                                        ("leg_r", "shin_r", "foot_r", -1, knee[1])):
        _pose(rig, leg, frame, pitch=-pitch - side * swing, roll=-roll, yaw=-yaw,
              shift=back)
        _pose(rig, shin, frame, pitch=bend)
        # The ankle undoes everything above it, which keeps the boot flat
        # through the whole stride. A boot that tips instead drives its heel
        # through the floor at the extremes, and there is no knee left over
        # to absorb it — the knee is busy lifting the other foot.
        _pose(rig, foot, frame, pitch=side * swing - bend)


def idle(rig, seconds=5.0):
    """Breathing, and a slow shift of weight from one foot to the other.

    Very small on purpose — this is a person standing still, not swaying.
    The two cycles are the same length so the whole thing loops on one
    period, and the first and last frames are identical so it loops clean.
    """
    frames = int(seconds * FPS)
    _action(rig, "idle")
    _rest(rig)
    for f in range(frames + 1):
        frame = f + 1
        t = f / frames
        breath = math.sin(t * math.tau)
        sway = math.sin(t * math.tau)
        look = math.sin(t * math.tau + 1.1)
        _pose(rig, "root", frame, shift=(sway * 0.006, 0, 0))
        _torso(rig, frame, pitch=-0.012 * breath, roll=0.020 * sway,
               shift=(0, 0, 0.006 * breath))
        _pose(rig, "head", frame, pitch=0.018 * breath, yaw=0.045 * look,
              roll=-0.012 * sway)
        _pose(rig, "arm_l", frame, pitch=0.022 * breath, roll=0.014 * sway)
        _pose(rig, "arm_r", frame, pitch=-0.022 * breath, roll=0.014 * sway)
        _pose(rig, "hand_l", frame, pitch=0.030 * breath)
        _pose(rig, "hand_r", frame, pitch=-0.030 * breath)


def walk(rig, seconds=1.0, reach=0.40, fold=0.55, lean=0.030, name="walk"):
    """One stride, opposite arm to opposite leg.

    **The body drops to meet the legs.** A straight leg swung `reach` rad
    either way lifts its own boot `LEG * (1 - cos reach)` clear of the turf —
    49 mm at 0.40 rad — so the ranger would walk the cycle on stilts and land
    flat-footed in the middle of it. Dropping the root by exactly that much
    puts both soles back on the floor at every frame of the stride, and the
    rise and fall it produces on the way through is the bob a walk has
    anyway.

    **The knee does the clearance**, and does it where a knee does: the
    swinging leg folds as it passes under the body and is straight again at
    both ends of the stride. That timing is `cos`, not `sin` — the
    quarter-cycle that catches people out — and it is also what keeps the
    drop above exact, because at full spread, where the drop is doing its
    work, both knees are straight.
    """
    frames = int(seconds * FPS)
    _action(rig, name)
    _rest(rig)
    for f in range(frames + 1):
        frame = f + 1
        t = f / frames
        wave = math.sin(t * math.tau)
        pass_by = math.cos(t * math.tau)
        stride = abs(wave)
        drop = (HIP_Z - ANKLE_Z) * (1 - math.cos(reach * wave))
        _pose(rig, "root", frame, shift=(0, 0, -drop))
        _torso(rig, frame, pitch=lean, roll=0.030 * pass_by, yaw=0.055 * wave,
               swing=reach * wave,
               knee=(fold * max(0.0, pass_by), fold * max(0.0, -pass_by)))
        _pose(rig, "head", frame, yaw=-0.040 * wave, pitch=0.020 * stride - lean * 0.6)
        _pose(rig, "arm_l", frame, pitch=reach * 0.70 * wave)
        _pose(rig, "arm_r", frame, pitch=-reach * 0.70 * wave)
        # The elbow folds on the forward swing and straightens on the back
        # one, which is the asymmetry that stops an arm reading as a pendulum.
        _pose(rig, "forearm_l", frame, pitch=-0.22 - reach * 0.45 * min(0.0, wave))
        _pose(rig, "forearm_r", frame, pitch=-0.22 + reach * 0.45 * max(0.0, wave))
        _pose(rig, "hand_l", frame, pitch=reach * 0.25 * wave)
        _pose(rig, "hand_r", frame, pitch=-reach * 0.25 * wave)


def run(rig):
    """The same cycle, driven harder — and the reason it has to exist.

    **A state with no clip is not posed by the clip next door, it is posed by
    the procedural table**, and that table works per piece about each bone's
    own pivot rather than down the hierarchy. At an idle's 0.03 rad nobody
    can tell. At a run's 0.67 the hand rotates about the wrist it is still
    sitting at while the arm swings away from the shoulder, and the two part
    company by a third of a metre.

    So the three states the player is ever drawn in — `idle`, `walk`, `run` —
    all ship as clips, and the fallback never runs on this figure.
    """
    walk(rig, seconds=0.7, reach=0.62, fold=0.95, lean=0.085, name="run")


def swim(rig, seconds=1.6):
    """Breaststroke — <b>authored standing up.</b>

    This is the clip that looks wrong in Blender and right in the game. A
    swimmer's body angle runs continuously from upright, treading water,
    through flat on the surface, to head-down in a dive, and which of those
    it is depends on where the player is looking — so no keyframe can hold
    it. The engine tips the whole figure at draw time instead
    (`SceneModel.Lean`), about the hips, and this clip supplies only what the
    arms and the legs are doing inside that tip.

    So read every pose below as if the figure were already face-down: "arms
    overhead" is the reach out in front, "knees to the chest" is the frog
    kick drawing up, and the head lifting is the breath.

    One cycle is one stroke: catch, sweep, recover, glide. The glide is the
    long part — a breaststroke is mostly waiting, which is what makes it read
    as swimming rather than as flailing.
    """
    frames = int(seconds * FPS)
    _action(rig, "swim")
    _rest(rig)
    for f in range(frames + 1):
        frame = f + 1
        t = f / frames
        # Arms: 0 is the full reach, 1 is hands pulled back to the chest.
        # Front-loaded so the pull is quick and the glide is long.
        pull = 0.5 - 0.5 * math.cos(min(1.0, t / 0.45) * math.tau) if t < 0.45 else 0.0
        # Legs a beat behind the arms: a breaststroke kicks as the arms
        # recover, which is the whole of why it moves anybody anywhere.
        kt = (t - 0.35) / 0.45
        kick = 0.5 - 0.5 * math.cos(min(1.0, max(0.0, kt)) * math.tau)
        breath = pull

        _pose(rig, "root", frame, shift=(0, 0, 0))
        # A shallow undulation. Every limb angle below is *local* — measured
        # against the chest rather than against the world — because a
        # swimmer's arms belong to their body and not to the horizon. That is
        # the opposite of `_torso`'s rule, which exists to keep boots planted
        # on ground this figure is nowhere near, so it is not used here.
        _pose(rig, "spine", frame, pitch=0.05 - 0.10 * pull)
        for bone in ("shin_l", "shin_r"):
            _pose(rig, bone, frame, pitch=1.75 * kick)
        # Head down in the glide, up to breathe on the pull. Negative is up.
        _pose(rig, "head", frame, pitch=0.30 - 0.62 * breath)

        # The arms sweep from overhead to the chest. -2.55 rad is straight out
        # in front once the body is tipped; -1.15 is hands under the sternum.
        for bone, side in (("arm_l", 1), ("arm_r", -1)):
            _pose(rig, bone, frame, pitch=-2.55 + 1.40 * pull,
                  roll=-side * (0.10 + 0.55 * math.sin(math.pi * pull)))
        # Elbows straight at the reach, folded hard at the finish.
        for bone in ("forearm_l", "forearm_r"):
            _pose(rig, bone, frame, pitch=-0.10 - 1.30 * pull)
        for bone, side in (("hand_l", 1), ("hand_r", -1)):
            _pose(rig, bone, frame, pitch=-0.25 * pull, roll=side * 0.30)

        # The knees come up and out together — a frog kick, not a flutter —
        # and the hips open with them.
        for bone, side in (("leg_l", 1), ("leg_r", -1)):
            _pose(rig, bone, frame, pitch=-0.95 * kick, roll=-side * 0.45 * kick)
        for bone in ("foot_l", "foot_r"):
            _pose(rig, bone, frame, pitch=-0.35 * kick)


def row(rig, seconds=2.2):
    """Sitting to a pair of oars.

    <b>The one clip in this file measured against furniture.</b> A rower is
    folded onto a thwart with their feet on the floorboards, and the gap
    between those two is the boat's: `BoatModel.DEPTH * 0.76`, about 350 mm,
    with the hips a further 110 mm above the seat. So the root drops by
    {@code HIP_Z - 0.46} to put the hips on the thwart, the thighs come
    forward far enough that a 300 mm shin can still reach the floor, and the
    feet land 60 mm up where the floorboards are.

    That geometry is why the legs had to be split at the knee. At 65 degrees
    the thigh leaves the knee 265 mm above the floorboards, which a shin can
    just reach down; any flatter and the feet hang in the bilge.

    One cycle is one stroke: catch at the front, drive, finish leaning back,
    recover forward.
    """
    frames = int(seconds * FPS)
    # How far the hips sit below the bone the body swings about. **This is
    # the number that keeps a rower on their seat.** The spine pivots at the
    # waist, 260 mm above the hip joint, so leaning back swings the hips
    # forward off the thwart and takes the legs — and the braced feet — with
    # them: 160 mm of foot skating over the floorboards, once a stroke. The
    # root undoes it, which pins the hips and lets the chest do the swinging
    # a rower's chest actually does.
    perch = WAIST_Z - HIP_Z
    _action(rig, "row")
    _rest(rig)
    for f in range(frames + 1):
        frame = f + 1
        t = f / frames
        # 0 at the catch (arms out, body forward), 1 at the finish.
        drive = 0.5 - 0.5 * math.cos(t * math.tau)

        # Forward at the catch, leaning back at the finish. A push stroke
        # leans into the handles; half a radian either way would be somebody
        # falling out of the boat.
        swing_back = 0.34 - 0.62 * drive
        _pose(rig, "root", frame,
              shift=(0, -perch * math.sin(swing_back),
                     (SEAT_Z - WAIST_Z) + perch * math.cos(swing_back)))
        _pose(rig, "spine", frame, pitch=swing_back)
        # **The legs are furniture and the chest is not.** A fixed-seat boat
        # does not slide, so the thighs hold one angle *against the boat* for
        # the whole stroke while the body swings over them — which means
        # every angle here is a world angle with the spine's swing taken back
        # out of it, and the ankle with the knee's fold out of it as well.
        # Get that wrong and the feet paddle in the bilge in time with the
        # stroke.
        for bone in ("leg_l", "leg_r"):
            _pose(rig, bone, frame, pitch=-1.13 - swing_back)
        for bone in ("shin_l", "shin_r"):
            _pose(rig, bone, frame, pitch=0.64)
        for bone in ("foot_l", "foot_r"):
            _pose(rig, bone, frame, pitch=0.49)

        # Arms: straight out at the catch, drawn to the ribs at the finish.
        #
        # **The elbow has to come down, not just fold.** Left pointing forward
        # while the forearm folds, the hand finishes up beside the ear —
        # the arm curls rather than pulls, because a hand cannot get behind a
        # shoulder the upper arm is still holding out in front of it. So the
        # upper arm swings back through vertical over the stroke and the
        # forearm folds against it, which puts the hands at the chest.
        for bone, side in (("arm_l", 1), ("arm_r", -1)):
            _pose(rig, bone, frame, pitch=-1.45 + 1.60 * drive,
                  roll=-side * 0.22)
        for bone in ("forearm_l", "forearm_r"):
            _pose(rig, bone, frame, pitch=-0.05 - 1.50 * drive)
        for bone, side in (("hand_l", 1), ("hand_r", -1)):
            _pose(rig, bone, frame, pitch=0.20, roll=side * 0.15)
        _pose(rig, "head", frame, pitch=0.10 - 0.16 * drive)


def alert(rig, seconds=1.75):
    """A look up, and back. Head first, chest after it, hands with it.

    Held rather than snapped: the eye finds a discontinuity in a short clip
    instantly, so the rise and the fall are both eased and the pose sits
    still in the middle of them.
    """
    frames = int(seconds * FPS)
    _action(rig, "alert")
    _rest(rig)
    for f in range(frames + 1):
        frame = f + 1
        t = f / frames
        # Up over the first fifth, held to two thirds, down over the rest.
        if t < 0.20:
            rise = 0.5 - 0.5 * math.cos(t / 0.20 * math.pi)
        elif t < 0.66:
            rise = 1.0
        else:
            rise = 0.5 + 0.5 * math.cos((t - 0.66) / 0.34 * math.pi)
        _pose(rig, "root", frame)
        _torso(rig, frame, pitch=-0.055 * rise, shift=(0, 0, 0.012 * rise))
        _pose(rig, "head", frame, pitch=-0.240 * rise, yaw=0.070 * rise)
        _pose(rig, "arm_l", frame, pitch=-0.070 * rise)
        _pose(rig, "arm_r", frame, pitch=-0.070 * rise)
        _pose(rig, "hand_l", frame, pitch=-0.120 * rise)
        _pose(rig, "hand_r", frame, pitch=-0.120 * rise)


# --- the whole thing -------------------------------------------------------

def build():
    _to_object_mode()
    collection = _fresh_collection(COLLECTION)
    bpy.context.scene.render.fps = FPS
    palette = materials()

    rig = build_rig(collection)
    triangles = 0
    for bone, part in parts().items():
        obj = _mesh_object(collection, "ranger_" + bone, part, palette)
        bind(obj, rig, bone)
        triangles += len(obj.data.polygons)

    idle(rig)
    walk(rig)
    run(rig)
    swim(rig)
    row(rig)
    alert(rig)
    rig.animation_data.action = bpy.data.actions["idle"]
    bpy.context.scene.frame_set(1)

    print("RANGER built: %d triangles, %d bones, %d materials, clips %s"
          % (triangles, len(BONES), len(palette),
             ", ".join(a.name for a in bpy.data.actions if a.use_fake_user)))
    return rig


# --- EXPORT ----------------------------------------------------------------
#
# File -> Export -> glTF 2.0 (.glb/.gltf)
#
#   Format                        glTF Binary (.glb)
#   Include -> Limit to           off (or select the RANGER collection)
#   Transform -> +Y Up            on, which is the default
#   Data -> Mesh -> Apply Modifiers   on
#   Data -> Material              Export
#   Animation                     on
#
# Save it as
#
#   src/main/resources/watch/models/characters/walker.glb   <- the player
#   src/main/resources/watch/models/characters/ranger.glb   <- the NPC
#
# One or the other, not both.
#
# There is nothing to apply first: every object here is built in world
# coordinates with an identity transform, so `Ctrl+A -> All Transforms` has
# no work to do and nothing is mirrored, which is the usual way a figure
# arrives inside-out.

if __name__ == "__main__":
    build()
