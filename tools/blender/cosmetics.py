"""
Build the whole wardrobe, fitted to each figure, and export all of it.

    blender --background --python tools/blender/cosmetics.py

    blender --background --python tools/blender/cosmetics.py -- wayfarer
    blender --background --python tools/blender/cosmetics.py -- walker wool_scarf

With no arguments it writes **thirty-six files** — the eighteen pieces of
`Cosmetics.java`, once for each figure of `figures.py`:

    src/main/resources/watch/models/cosmetics/walker/<key>.glb
    src/main/resources/watch/models/cosmetics/wayfarer/<key>.glb

Given a figure key it writes that figure's eighteen; given a figure and one
or more piece keys, only those.

--- why there are two of everything ---------------------------------------

**A cosmetic is the one model in this game that is never measured and never
rescaled.** Everything else — an animal, a ranger, a player — is normalised
by its height and redrawn at the size the thing actually is, so one file
serves a hummingbird and an elk. A worn piece is `AS_PLACED`: the metre an
artist puts a hat at is the metre it is worn at.

That is the right call, and it has one consequence: a wardrobe is fitted to
*a body*. A collar cut for a 0.33 m chest stands 40 mm off a 0.28 m one, and
a hat that covers a square crown 0.47 m across the corners swallows a round
one. So the moment there were two figures there were two wardrobes, and the
game looks for a piece under the wearer's own figure first —
`CosmeticModel.importedFor`.

Both are built from `figures.py` by the same code below. That is what keeps
thirty-six files honest: a scarf is not written at "1.27" for one figure and
"1.283" for the other, it is written at `f["collar_z"]` once.

--- the three rules a piece is built under --------------------------------

**1. Over the top, never instead of.** The figure underneath is still drawn,
whole. A hat has to *cover* the hat already on the head, which is what
`hat_cover_r` is for; a cape has to clear the pack, which is `pack_back_y`.
A piece that merely occupies the same space as the body reads as a garment
somebody is standing inside.

**2. Rigged, not placed.** Each piece is parented to a bone of a rig with
README §10's names, so a mitten follows a hand and a cape hangs off a spine
through the whole walk. The rig is exported with it. `_l` is `+X` — not
anatomy, but what keeps a modelled gaiter swinging with the boot inside it.

**3. No clips.** A piece with no animation still moves: it swings with the
bone it is rigged to, posed by the game's own procedural humanoid table, in
step with the legs by construction because it is driven by the wearer's gait
clock. Thirty-six authored walk cycles would be thirty-six chances for a
cloak to disagree with the coat under it, and the fallback does not have
that failure mode. See README §16.

--- the budget ------------------------------------------------------------

About 250 triangles a piece, and about 800 for a whole outfit. Six pieces can
be on one person and eight people can be in one clearing, so a 900-triangle
cape is 43,000 triangles of coat in a wood. `PlayerFiguresTest` holds every
file in this folder against both numbers.
"""

import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import bpy                                                    # noqa: E402
from mathutils import Vector                                  # noqa: E402

import figures                                                # noqa: E402
import kit                                                    # noqa: E402
from kit import Part                                          # noqa: E402

COLLECTION = "WARDROBE"

#: Where the files go, relative to the root of the repository.
OUTPUT = "src/main/resources/watch/models/cosmetics"


# --- the catalogue's own colours -------------------------------------------
#
# **Straight out of `Cosmetics.java`, key for key.** The two files have to
# agree or the picture in the shop is a different colour from the thing you
# bought, and `PlayerFiguresTest.theWardrobeIsPaintedOutOfTheCatalogue` reads
# both and says so.
#
# The two exceptions are the two pieces the *boxed* game tints to the
# wearer's own coat, which have `rgb == 0` in the catalogue as the sentinel
# for that. A modelled piece is never tinted — README §16 — so they need a
# colour of their own, and waxed oilskin is what they are: a green so dark it
# reads as black in a wood and as green in the sun.

OILSKIN = 0x2E3A34

PAINT = {
    "wool_mittens":    (0xB4553F, 0xE0D2B8),
    "knitted_beanie":  (0x4A5A3C, 0xC9B98A),
    "canvas_gaiters":  (0x8A6A3A, 0x40382C),
    "wool_scarf":      (0xA8482F, 0xD9C68A),
    "rolled_bedroll":  (0xC9B98A, 0x6E4B2E),
    "wire_spectacles": (0xB0763A, 0xCFE4EA),
    "feathered_band":  (0x3E4A5C, 0xE4DCC4),
    "glass_lanyard":   (0x5C4A3A, 0xC08A3A),
    "leather_gloves":  (0x6E4B2E, 0x3A2C20),
    "straw_boater":    (0xD8C88A, 0x2F5A6B),
    "snow_goggles":    (0x3A3A3E, 0x8FA84C),
    "oilskin_hood":    (OILSKIN, 0x40514B),
    "river_waders":    (0x40514B, 0x2A2E28),
    "moth_veil":       (0xE4E0D4, 0x8A8070),
    "fur_collar":      (0x7A6248, 0x3A2E22),
    "oilskin_cape":    (OILSKIN, 0x8A6A3A),
    "antler_circlet":  (0x8A7A5C, 0xD8CDB4),
    "heron_cloak":     (0x6E7580, 0xBFC7B0),
}

#: The order a rail shows them: cheapest first, as the catalogue is built.
KEYS = tuple(PAINT)


def tin(key):
    """The five materials one piece is painted with.

    Five rather than two, because a piece with one colour and a band across
    it reads as a made thing and a piece with two tones of each reads as a
    made thing with a shape. `ShopModel.shade`, exactly as the boxes do it.

    **Named for the piece**, and that is not decoration. Blender looks a
    material up by name, so a wardrobe whose every piece calls its own colour
    `main` is a wardrobe of one colour — the last one built — with every mesh
    before it quietly repainted. It survives an export that writes each piece
    the moment it is made and shows up the first time anything looks at two
    of them at once, which is exactly the sort of bug that reaches a
    screenshot.
    """
    rgb, trim = PAINT[key]
    named = {
        "main": rgb,
        "main_dark": kit.shade(rgb, 0.80),
        "main_light": kit.shade(rgb, 1.14),
        "trim": trim,
        "trim_dark": kit.shade(trim, 0.78),
    }
    made = kit.palette({key + "_" + name: value for name, value in named.items()})
    return {name: made[key + "_" + name] for name in named}


# --- the pieces -------------------------------------------------------------
#
# Each returns {bone: Part} in world metres, on a figure standing at the
# origin facing -Y. Every number is read off `f`, which is a row of
# `figures.py`, so the same code fits both bodies.


def knitted_beanie(f):
    """Pulled down over the brim: a ribbed band, two knit courses and a bobble.

    Sized off `hat_cover_r` rather than off the head, because the head has a
    hat on it already and a beanie that fits the skull leaves a campaign hat
    sticking out of the top of it.
    """
    p = Part()
    r = f["hat_cover_r"] + 0.022
    low = f["hat_brim_z"] + 0.016
    top = f["hat_top"] + 0.030
    p.prism((0, 0, low + 0.030), r + 0.014, 0.060, "trim")           # band
    p.prism((0, 0, low + 0.088), r, 0.060, "main")                   # course
    p.prism((0, 0, low + 0.146), r - 0.008, 0.058, "main_light")     # course
    p.prism((0, 0, (low + 0.174 + top) / 2), r - 0.014,
            top - low - 0.174, "main", top_radius=r * 0.42)
    p.prism((0, 0, top + 0.030), r * 0.30, 0.060, "trim", sides=6)   # bobble
    return {"head": p}


def feathered_band(f):
    """A hatband with one moulted primary in it — quill, vane and all.

    The feather is what the piece is, so it gets four of its own shapes and
    the band gets two: a leaning quill with a vane either side of it reads as
    a feather where one flat strut reads as a stick.
    """
    p = Part()
    r = f["hat_cover_r"] + 0.010
    z = f["hat_brim_z"] + 0.055
    p.prism((0, 0, z), r, 0.052, "main")
    p.prism((0, 0, z + 0.040), r - 0.006, 0.026, "main_dark")
    p.box((0, -r * 0.96, z), (0.048, 0.026, 0.062), "trim_dark")     # keeper
    quill = (Vector((r * 0.62, -r * 0.52, z + 0.010)),
             Vector((r * 0.30, r * 0.86, f["hat_top"] + 0.090)))
    p.strut(quill[0], quill[1], 0.014, 0.014, "trim_dark")
    for t, width in ((0.30, 0.052), (0.58, 0.062), (0.84, 0.040)):
        at = quill[0].lerp(quill[1], t)
        p.plate((at.x, at.y, at.z), (width, 0.010, 0.090), "trim",
                tilt=0.5, turn=-0.6)
    return {"head": p}


def straw_boater(f):
    """Flat brim, blue ribbon, and a bow on the back of it.

    **The brim goes just above the wearer's own**, half a centimetre proud
    and 35 mm wider, so what you see from anywhere above the horizon is the
    boater. Underneath it the campaign hat is still there, which is what a
    hat lining looks like anyway.
    """
    p = Part()
    brim_r = f["hat_brim_r"] + 0.035
    brim_z = f["hat_brim_z"] + 0.014
    crown_r = f["hat_cover_r"] + 0.014
    crown_top = f["hat_top"] + 0.026
    p.prism((0, 0, brim_z), brim_r, 0.024, "main",
            bottom_material="main_dark")
    p.prism((0, 0, brim_z + 0.014), brim_r - 0.030, 0.030, "main_light")
    p.prism((0, 0, (brim_z + crown_top) / 2), crown_r,
            crown_top - brim_z, "main")
    p.prism((0, 0, crown_top - 0.008), crown_r - 0.006, 0.020, "main_light")
    p.prism((0, 0, brim_z + 0.060), crown_r + 0.008, 0.048, "trim")  # ribbon
    p.box((0, crown_r + 0.020, brim_z + 0.062), (0.090, 0.036, 0.052), "trim")
    p.box((0, crown_r + 0.052, brim_z + 0.062), (0.048, 0.034, 0.030),
          "trim_dark")
    return {"head": p}


def oilskin_hood(f):
    """Waxed and up, with a peak over the brow and a gorget at the throat.

    One of the two pieces that changes a silhouette, which is why the boxed
    version is drawn in the wearer's own coat colour. A modelled one is not
    tinted — see OILSKIN — so it has to earn its outline instead: a shell
    that clears the hat, a peak that leans, and cheek panels that come
    forward past the jaw.
    """
    p = Part()
    r = f["hat_cover_r"] + 0.030
    brim = f["hat_brim_z"]
    top = f["hat_top"] + 0.045
    # **The shell starts above the brim, not at the neck.** Pulled down over
    # the whole head it has to be wider than the widest thing on the figure,
    # which is the hat brim — and a hood 0.68 m across is a barrel. Up over
    # the crown instead, and the brim it leaves showing is the hood's own
    # edge as far as anybody looking at it is concerned.
    p.prism((0, 0.014, brim + 0.090), r, 0.190, "main", squash=1.08)
    p.prism((0, 0.018, (brim + 0.180 + top) / 2), r - 0.012,
            top - brim - 0.180, "main", squash=1.08, top_radius=r * 0.58)
    # The peak. Tilted, because a peak that rain runs off is not flat, and a
    # flat one reads as a shelf over somebody's eyes.
    p.plate((0, f["face_y"] - 0.052, brim + 0.026),
            (r * 1.30, 0.115, 0.026), "main_dark", tilt=0.30)
    # Cheek panels down past the jaw, in front of the ear. Narrow: this is
    # the edge of a hood and not a pair of blinkers.
    kit.mirrored(p, ("plate", (f["head_half_x"] + 0.026, f["face_y"] + 0.085,
                               f["eye_z"] - 0.055),
                     (0.040, 0.185, 0.250), "main", 0.0, 0.24))
    # The gorget, over the collarbone, with the storm toggle on it.
    p.prism((0, 0.008, f["collar_z"] - 0.020), f["neck_r"] + 0.082, 0.080,
            "main_dark", squash=1.14)
    p.box((0, f["chest_front_y"] + 0.006, f["collar_z"] - 0.020),
          (0.050, 0.046, 0.054), "trim")
    return {"head": p}


def antler_circlet(f):
    """Cast antler bound to a birch hoop: a beam, a brow tine and a crown tine.

    Three struts a side is the fewest that reads as an antler rather than as
    a stick, and the beams are `roll`s rather than boxes because an antler is
    round and a square one reads as a fence post.
    """
    p = Part()
    r = f["hat_cover_r"] + 0.012
    z = f["hat_brim_z"] + 0.062
    p.prism((0, 0, z), r, 0.036, "trim")
    p.prism((0, 0, z + 0.026), r - 0.004, 0.016, "trim_dark")
    for side in (1, -1):
        base = Vector((side * r * 0.80, 0.010, z + 0.020))
        mid = Vector((side * (r + 0.090), -0.030, f["hat_top"] + 0.075))
        tip = Vector((side * (r + 0.140), -0.115, f["hat_top"] + 0.185))
        p.roll(base, mid, 0.021, "main", sides=6)
        p.roll(mid, tip, 0.015, "main", sides=6)
        p.roll(mid, Vector((side * (r + 0.165), 0.105, f["hat_top"] + 0.130)),
               0.013, "main_light", sides=6)
        p.roll(base.lerp(mid, 0.45),
               Vector((side * (r + 0.075), -0.150, f["hat_top"] + 0.010)),
               0.012, "main_light", sides=6)
    return {"head": p}


def wire_spectacles(f):
    """Thin gold wire, two lenses and a pair of temples back to the ears.

    The lenses are drums rather than boxes — a round lens in a round rim is
    the whole of what says "spectacles" and not "goggles", which is the piece
    two rows down.
    """
    p = Part()
    y = f["face_y"] - 0.014
    z = f["eye_z"]
    x = f["head_half_x"] * 0.44
    for side in (1, -1):
        p.prism((side * x, y, z), 0.046, 0.010, "main", sides=8,
                turn=math.pi / 2)
        p.prism((side * x, y - 0.006, z), 0.038, 0.008, "trim", sides=8,
                turn=math.pi / 2)
        p.strut((side * (x + 0.042), y + 0.004, z),
                (side * (f["head_half_x"] + 0.008), 0.055, z + 0.012),
                0.008, 0.008, "main")
    p.box((0, y, z + 0.004), (x * 0.9, 0.010, 0.010), "main")        # bridge
    p.box((0, y + 0.020, z - 0.028), (0.030, 0.024, 0.010), "main")  # nose pad
    return {"head": p}


def snow_goggles(f):
    """Smoked glass on a strap, with a vent over each lens.

    For the glare off water as much as off snow, which is why the band round
    the head is leather rather than elastic and is drawn as three struts that
    actually reach the back of the skull.
    """
    p = Part()
    y = f["face_y"] - 0.020
    z = f["eye_z"] + 0.004
    x = f["head_half_x"] * 0.46
    p.box((0, y + 0.016, z), (f["head_half_x"] * 2 + 0.010, 0.062, 0.108),
          "main")
    for side in (1, -1):
        p.prism((side * x, y - 0.016, z), 0.050, 0.016, "main_dark", sides=8,
                turn=math.pi / 2)
        p.prism((side * x, y - 0.024, z), 0.041, 0.010, "trim", sides=8,
                turn=math.pi / 2)
        p.box((side * x, y - 0.006, z + 0.058), (0.058, 0.030, 0.016),
              "main_light")                                          # vent
        p.strut((side * (f["head_half_x"] + 0.004), y + 0.070, z),
                (side * (f["head_half_x"] * 0.55), f["head_half_y"] + 0.030, z),
                0.024, 0.014, "main_dark")
    p.box((0, f["head_half_y"] + 0.032, z), (f["head_half_x"], 0.024, 0.030),
          "main_dark")
    return {"head": p}


def moth_veil(f):
    """Fine net off the brim, weighted at the hem. Midges hate it and so will
    everyone.

    **Slats with daylight between them, not a shell.** Nothing in this game
    is transparent — every triangle is one flat colour — so a veil built as
    a closed cylinder round the head is a bucket, which is what the first
    version of this was. Eight narrow strips at a little under half the
    chord read as netting for the same reason a picket fence reads as a
    fence: what you see is as much gap as slat.
    """
    p = Part()
    r = f["hat_brim_r"] * 0.84
    top = f["hat_brim_z"] - 0.008
    drop = top - f["collar_z"] + 0.026
    p.prism((0, 0, top), r + 0.016, 0.018, "trim")
    for i in range(8):
        angle = math.tau * (i + 0.5) / 8
        out = r + 0.006
        p.plate((math.sin(angle) * out, math.cos(angle) * out, top - drop / 2),
                (2 * out * math.tan(math.pi / 8) * 0.44, 0.007, drop),
                "main", turn=-angle)
    p.ring((0, 0, top - drop + 0.010), r + 0.012, 0.016, 0.016, "trim_dark",
           sides=8)
    return {"head": p}


def wool_scarf(f):
    """Wound twice, with one end left long — and that end is the whole point.

    Everything here clears the front of the chest. A scarf tail written at
    "just in front of the neck" is a scarf tail inside the person wearing it:
    the chest reaches `chest_front_y`, which is 145 mm from the middle on the
    walker.
    """
    p = Part()
    r = f["neck_r"] + 0.052
    z = f["collar_z"]
    front = f["chest_front_y"] - 0.030
    p.prism((0, 0, z + 0.030), r, 0.070, "main", squash=1.05)
    p.prism((0, 0, z - 0.038), r + 0.012, 0.070, "main_dark", squash=1.05)
    p.prism((0, 0, z + 0.070), r - 0.010, 0.028, "trim")             # edge
    p.box((0.020, front + 0.010, z - 0.048), (0.086, 0.062, 0.070), "trim")
    # The long end, down the chest, with a fringe on it.
    p.plate((0.052, front - 0.006, z - 0.190), (0.096, 0.036, 0.230), "main",
            tilt=-0.06)
    p.plate((0.058, front - 0.020, z - 0.330), (0.090, 0.032, 0.090),
            "main_dark", tilt=-0.14)
    for i in (-1, 0, 1):
        p.box((0.058 + i * 0.028, front - 0.026, z - 0.396),
              (0.018, 0.026, 0.048), "trim")
    return {"spine": p}


def glass_lanyard(f):
    """Two braided straps and a brass ring, for a glass you keep dropping.

    The ring is a real hoop — eight panels with a hole in the middle — which
    is the one place in this wardrobe worth spending a hollow shape on,
    because a disc reads as a medal and the piece is not one.
    """
    p = Part()
    z = f["collar_z"] - 0.010
    front = f["chest_front_y"] - 0.016
    low = z - 0.230
    for side in (1, -1):
        top = Vector((side * (f["neck_r"] + 0.030), 0.020, z + 0.028))
        mid = Vector((side * 0.062, front + 0.008, low + 0.070))
        p.strut(top, mid, 0.026, 0.014, "main")
        p.box((side * 0.062, front + 0.006, low + 0.130),
              (0.032, 0.020, 0.026), "main_dark")                    # keeper
        p.box((side * 0.062, front + 0.006, low + 0.038),
              (0.032, 0.020, 0.026), "main_dark")
    p.box((0, front + 0.006, low + 0.010), (0.128, 0.022, 0.026), "main")
    p.ring((0, front - 0.006, low - 0.052), 0.046, 0.018, 0.020, "trim",
           sides=8)
    p.prism((0, front - 0.010, low - 0.052), 0.030, 0.014, "trim_dark",
            sides=8, turn=math.pi / 2)
    return {"spine": p}


def fur_collar(f):
    """Deep, dark, and shed rather than taken.

    **Fur is the one thing here that has to be irregular**, so this is the
    piece built out of a hollow ring of eight panels instead of a drum: each
    one is a different height and stands a different distance out, and the
    unevenness is the whole of what stops it reading as a rubber tyre.
    """
    p = Part()
    r = f["neck_r"] + 0.060
    z = f["collar_z"] - 0.004
    p.prism((0, 0, z), r - 0.016, 0.110, "trim_dark", squash=1.08)
    for i in range(8):
        angle = math.tau * (i + 0.5) / 8
        wobble = 0.016 * math.sin(i * 2.1) + 0.010 * math.cos(i * 1.3)
        out = r + wobble
        p.plate((math.sin(angle) * out, math.cos(angle) * out * 1.10,
                 z + wobble * 0.9),
                (2 * out * math.tan(math.pi / 8) * 1.12, 0.070,
                 0.130 + wobble * 1.8),
                "main" if i % 2 == 0 else "main_dark", turn=-angle)
    p.prism((0, 0, z - 0.062), r - 0.004, 0.030, "trim", squash=1.10)
    return {"spine": p}


def rolled_bedroll(f):
    """Strapped across the satchel: a roll, its blanket edge, and three straps.

    It says you meant to be out this long, which is the only job it has, so
    it is built as a real cylinder lying across the back rather than as a box
    somebody will read as a plank.
    """
    p = Part()
    y = f["pack_back_y"] + 0.052
    z = f["chest_z"] - 0.190
    reach = f["chest_half_x"] + 0.075
    p.roll((-reach, y, z), (reach, y, z + 0.010), 0.062, "main", sides=8,
           end_material="main_dark")
    p.roll((-reach * 0.99, y - 0.030, z + 0.004),
           (reach * 0.99, y - 0.030, z + 0.014), 0.030, "main_light", sides=6)
    for at in (-0.62, 0.0, 0.62):
        x = reach * at
        p.strut((x, y - 0.070, z + 0.006), (x, y + 0.072, z + 0.006),
                0.030, 0.030, "trim")
        p.box((x, y - 0.070, z + 0.006), (0.036, 0.026, 0.036), "trim_dark")
    for side in (1, -1):
        p.box((side * (reach + 0.014), y, z + 0.006),
              (0.030, 0.112, 0.112), "trim_dark")
    return {"spine": p}


def _cape(f, panel_material, yoke_material, hem_z):
    """The shape both big back pieces are: a yoke over the shoulders and a
    panel that stands off the back and widens toward the hem.

    Written once because a cape and a cloak differ in what is *on* them and
    not in how they hang, and two copies of this arithmetic would drift.
    """
    p = Part()
    y = f["pack_back_y"] + 0.036
    shoulder = f["shoulder_z"]
    across = f["shoulder_x"] + 0.055
    p.taper((0, y + 0.030, hem_z), (0, y, shoulder + 0.020),
            (across * 2.42, 0.036), (across * 1.90, 0.040), panel_material)
    # A yoke over both shoulders, and the two panels that fall from it in
    # front of the arms — which is what makes it a cape and not a curtain.
    p.box((0, (y + f["chest_front_y"]) / 2, shoulder + 0.046),
          (across * 2.02, y - f["chest_front_y"] + 0.030, 0.062),
          yoke_material)
    for side in (1, -1):
        p.plate((side * (across + 0.010), 0.010, shoulder - 0.130),
                (0.040, 0.270, 0.300), panel_material, turn=side * 0.10)
        # …and a narrow lapel down the front of each shoulder. **Narrow**:
        # the first version of this was a panel across half the chest and
        # the pair of them read as a sandwich board rather than as a cape
        # somebody had thrown on.
        p.plate((side * across * 0.86, f["chest_front_y"] - 0.014,
                 shoulder - 0.150), (across * 0.46, 0.032, 0.320),
                panel_material, tilt=0.04)
    return p, y, across


def oilskin_cape(f):
    """A yoke and a long panel to the knee. The whole wood runs off it."""
    p, y, across = _cape(f, "main", "main_dark", f["knee_z"] + 0.020)
    shoulder = f["shoulder_z"]
    p.box((0, y - 0.006, shoulder + 0.086),
          (across * 1.50, 0.070, 0.060), "trim")                     # collar
    p.box((0, (y + f["chest_front_y"]) / 2 - 0.040, shoulder + 0.010),
          (0.060, 0.040, 0.070), "trim_dark")                        # clasp
    p.box((0, y + 0.034, f["knee_z"] + 0.036),
          (across * 2.40, 0.044, 0.038), "trim_dark")                # hem band
    for side in (1, -1):
        p.strut((side * across * 0.55, y + 0.020, shoulder - 0.060),
                (side * across * 0.86, y + 0.026, f["chest_z"] - 0.160),
                0.026, 0.026, "trim_dark")
    return {"spine": p}


def heron_cloak(f):
    """Courses of grey feather over a long panel. Somebody spent a winter on it.

    The courses are the piece — lines across a slab read as a made surface
    and a bare slab reads as a board, which is the same trick `ShopModel`
    plays on a roof. Five of them, alternating shade, because four read as
    stripes and six cost more than the cloak is worth.
    """
    p, y, across = _cape(f, "main", "main_dark", f["hem_z"] - 0.020)
    shoulder = f["shoulder_z"]
    top = shoulder - 0.010
    bottom = f["hem_z"] + 0.020
    for course in range(5):
        t = course / 4.0
        z = top + (bottom - top) * t
        p.plate((0, y + 0.026 + 0.012 * t, z),
                (across * (1.94 + 0.46 * t), 0.030, 0.052),
                "trim" if course % 2 == 0 else "trim_dark", tilt=-0.10)
    for side in (1, -1):
        p.plate((side * (across + 0.014), 0.024, shoulder + 0.010),
                (0.046, 0.220, 0.070), "trim", turn=side * 0.10)
    p.box((0, (y + f["chest_front_y"]) / 2 - 0.044, shoulder + 0.014),
          (0.076, 0.040, 0.052), "trim")                             # clasp
    return {"spine": p}


def _hands(f, build):
    """One shape, once per hand, on the two hand bones.

    `build` is handed the part and the side, and works in `+X` coordinates
    with the sign already applied — which is the only way to write a mitten
    once and have the thumb come out on the inside of both of them.
    """
    made = {}
    for bone, side in (("hand_l", 1), ("hand_r", -1)):
        p = Part()
        build(p, side)
        made[bone] = p
    return made


def wool_mittens(f):
    """Knitted on somebody's porch. One size, and it is not yours."""
    hx, hy, hz = f["hand"]
    r = f["hand_half"]

    def build(p, side):
        x = side * hx
        p.box((x, hy, hz - 0.004), (r * 2.42, r * 2.34, r * 2.20), "main")
        p.prism((x, hy, hz + r * 1.16), r * 1.12, r * 0.62, "main",
                sides=6, top_radius=r * 0.78)                        # rounded end
        p.box((x - side * r * 1.26, hy - 0.012, hz + r * 0.30),
              (r * 0.80, r * 1.10, r * 1.30), "main")                # thumb
        p.prism((x, hy, hz + r * 1.62), r * 1.26, r * 0.70, "trim", sides=6)
        p.box((x, hy, hz - r * 0.24), (r * 2.46, r * 2.38, r * 0.34),
              "main_light")                                          # knit course
    return _hands(f, build)


def leather_gloves(f):
    """Cut close, stitched at the seam. Rope will not take your palms off now."""
    hx, hy, hz = f["hand"]
    r = f["hand_half"]

    def build(p, side):
        x = side * hx
        p.box((x, hy, hz), (r * 2.20, r * 2.14, r * 1.90), "main")
        for i, at in enumerate((-0.52, 0.0, 0.52)):
            p.box((x + side * at * r * 0.74, hy - r * 0.30, hz - r * 1.10),
                  (r * 0.60, r * 1.36, r * 0.86),
                  "main" if i % 2 == 0 else "main_dark")             # fingers
        p.box((x - side * r * 1.14, hy - 0.010, hz + r * 0.24),
              (r * 0.66, r * 0.96, r * 1.10), "main_dark")           # thumb
        p.prism((x, hy, hz + r * 1.28), r * 1.22, r * 0.60, "trim", sides=6)
        p.box((x, hy - r * 0.92, hz + r * 1.28), (r * 1.20, r * 0.36, r * 0.42),
              "trim_dark")                                           # tab
    return _hands(f, build)


def _feet(f, build):
    """One shape, once per boot, on the two foot bones."""
    made = {}
    for bone, side in (("foot_l", 1), ("foot_r", -1)):
        p = Part()
        build(p, side)
        made[bone] = p
    return made


def canvas_gaiters(f):
    """Buckled up the shin. Keeps the burrs out, mostly."""
    bx, by, bz = f["boot"]
    wide, deep = f["boot_half_x"], f["boot_half_y"]
    top = f["boot_top"] + 0.145

    def build(p, side):
        x = side * bx
        p.taper((x, by - 0.006, f["boot_top"] - 0.070),
                (x, by + 0.004, top),
                ((wide + 0.014) * 2, deep * 1.86),
                ((wide - 0.008) * 2, deep * 1.44), "main")
        p.prism((x, by + 0.004, top - 0.014), wide + 0.008, 0.036, "trim",
                sides=6, squash=1.40)
        p.prism((x, by - 0.004, f["boot_top"] - 0.058), wide + 0.020, 0.030,
                "trim", sides=6, squash=1.44)
        for at in (0.24, 0.56, 0.86):
            z = f["boot_top"] - 0.070 + (top - f["boot_top"] + 0.070) * at
            p.box((x + side * (wide - 0.006), by - 0.020, z),
                  (0.020, 0.030, 0.026), "trim_dark")                # hooks
        p.strut((x - (wide + 0.010), by + 0.006, bz - 0.006),
                (x + (wide + 0.010), by + 0.006, bz - 0.006),
                0.024, 0.024, "trim_dark")                           # instep
    return _feet(f, build)


def river_waders(f):
    """To the knee, rubberised, and they squeak. Every fisher owns a pair."""
    bx, by, bz = f["boot"]
    wide, deep = f["boot_half_x"], f["boot_half_y"]
    top = f["knee_z"] + 0.030

    def build(p, side):
        x = side * bx
        p.box((x, by, bz + 0.006), ((wide + 0.016) * 2, deep * 2.10, 0.100),
              "main")
        p.box((x, by - 0.010, bz - 0.030), ((wide + 0.020) * 2, deep * 2.16,
                                            0.028), "trim_dark")     # sole
        p.taper((x, by, 0.090), (x, 0.004, top),
                ((wide + 0.016) * 2, deep * 1.94),
                ((wide - 0.004) * 2, deep * 1.42), "main")
        p.prism((x, 0.004, top - 0.020), wide + 0.014, 0.046, "main_light",
                sides=6, squash=1.36)                                # turned top
        p.prism((x, 0.004, top - 0.058), wide + 0.006, 0.026, "trim",
                sides=6, squash=1.38)
        p.box((x + side * (wide - 0.002), -0.004, top - 0.090),
              (0.022, 0.034, 0.070), "trim_dark")                    # buckle
    return _feet(f, build)


#: Which function builds which key, in the catalogue's own order.
BUILDERS = {
    "wool_mittens": wool_mittens,
    "knitted_beanie": knitted_beanie,
    "canvas_gaiters": canvas_gaiters,
    "wool_scarf": wool_scarf,
    "rolled_bedroll": rolled_bedroll,
    "wire_spectacles": wire_spectacles,
    "feathered_band": feathered_band,
    "glass_lanyard": glass_lanyard,
    "leather_gloves": leather_gloves,
    "straw_boater": straw_boater,
    "snow_goggles": snow_goggles,
    "oilskin_hood": oilskin_hood,
    "river_waders": river_waders,
    "moth_veil": moth_veil,
    "fur_collar": fur_collar,
    "oilskin_cape": oilskin_cape,
    "antler_circlet": antler_circlet,
    "heron_cloak": heron_cloak,
}


# --- the rig a piece is hung on --------------------------------------------
#
# README §10's names, and the same tree both figures have. Only the bones a
# piece actually uses carry geometry; the rest cost a node apiece in the file
# and nothing at all at draw time, and having them all means one rig serves
# every piece in the wardrobe.

def bones_for(f):
    hip_x, shoulder_x = f["hip_x"], f["shoulder_x"]
    hx, hy, hz = f["hand"]
    bx, by, bz = f["boot"]
    wrist = (hx, hy + 0.004, hz + 0.062)
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


# --- building and writing them ---------------------------------------------

def build_piece(collection, rig, figure, key):
    """One piece's objects, rigged, ready to export. Returns them and a count."""
    parts = BUILDERS[key](figure)
    paint = tin(key)
    objects = []
    triangles = 0
    for bone, part in parts.items():
        part.bury()
        if part.empty():
            continue
        obj = kit.mesh_object(collection, "%s_%s" % (key, bone), part, paint)
        kit.bind(obj, rig, bone)
        objects.append(obj)
        triangles += len(obj.data.polygons)
    return objects, triangles


def build(figure_key, keys=KEYS, write=True, root=None):
    """Every named piece for one figure, built and (by default) written."""
    figure = figures.figure(figure_key)
    kit.to_object_mode()
    collection = kit.fresh_collection(COLLECTION + "_" + figure_key.upper())
    rig = kit.build_rig(collection, "%s_wardrobe_rig" % figure_key,
                        bones_for(figure))

    if root is None:
        root = os.path.abspath(os.path.join(
            os.path.dirname(os.path.abspath(__file__)), "..", ".."))

    counts = {}
    for key in keys:
        objects, triangles = build_piece(collection, rig, figure, key)
        counts[key] = triangles
        if not objects:
            print("WARDROBE %s/%s built nothing" % (figure_key, key))
            continue
        if write:
            out = os.path.join(root, OUTPUT, figure_key, key + ".glb")
            kit.export(out, [rig] + objects)
        print("WARDROBE %s/%-16s %4d triangles" % (figure_key, key, triangles))
    print("WARDROBE %s: %d pieces, %d triangles, dearest %d"
          % (figure_key, len(counts), sum(counts.values()),
             max(counts.values()) if counts else 0))
    return counts


def main():
    argv = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
    if not argv:
        for figure in figures.ALL:
            build(figure["key"])
        return
    figure_key, keys = argv[0], tuple(argv[1:]) or KEYS
    build(figure_key, keys)


if __name__ == "__main__":
    main()
