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
whole, and a piece that merely occupies the same space as the body reads as a
garment somebody is standing inside. A drum on the head has to clear the
corners of the box it is worn on (`crown_r`) and something on the back has to
stand off the back (`back_y`) — those two are where nearly every misfit in
this file has come from, and they are functions rather than numbers so that
there is one place to be wrong.

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
    # --- the standard kit -------------------------------------------------
    #
    # **These are the clothes that used to be modelled into the body.** Every
    # player owns them and wears them from the start, none of them is on any
    # rail, and all of them come off — which is the whole reason they are
    # pieces rather than geometry. See `Cosmetics.Piece.kit`.
    "field_coat":      (0x3C5240, 0x4A6450),
    "field_trousers":  (0x6B6247, 0x5E563F),
    "walking_boots":   (0x4A3626, 0x5C4433),
    "field_pack":      (0x4A3626, 0xB8A050),
    "walking_hat":     (0x2E4033, 0xA8442E),
    "neckerchief":     (0xA8442E, 0x8A3626),

    # --- hair -------------------------------------------------------------
    #
    # A hairstyle is a worn piece like any other: it is on the head bone, it
    # comes off, and its colour is yours to set. The body underneath is bald,
    # which is a haircut somebody may want anyway.
    "cropped_hair":    (0x4A3220, 0x3A2718),
    "swept_hair":      (0x4A3220, 0x5C4028),
    "long_plait":      (0x6B3A22, 0x54301C),
    "topknot":         (0x3A2A1E, 0x50382A),

    # --- the rail ---------------------------------------------------------
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


# --- fitting to a head, now that there is not a hat on it ------------------
#
# **Everything in the HEAD slot used to be cut to go *over* the figure's own
# hat**, which was modelled into the body and could not come off. It comes off
# now — it is `walking_hat` — so one hat is worn at a time and a piece on the
# head is cut to the head. These are what a hat's crown has to be to fit one,
# with the clearance a knitted thing needs and no more.

#: How far a hairstyle stands off the skull, per side.
#
#: `_scalp` adds its `thickness` to the cap's *full* width, so half of it is
#: what the hair actually gains on each side, and the odd millimetre on top is
#: so that a hat sits on hair rather than exactly in it.
HAIR_PROUD = 0.013


def wrist_of(f):
    """The wrist, which is the hand's own centre with the joint above it."""
    return (f["shoulder_x"], f["hand"][1] + 0.004, f["hand"][2] + 0.062)


def head_pad(f):
    """How far a box worn on the head has to stand off the skull, per side.

    A hat crown built as a *box* has the easy job: a box that is this much
    bigger than the head's box contains it, corners and all, and no arithmetic
    is needed. `crown_r` is the same question asked of a drum, which is the
    hard version.
    """
    return HAIR_PROUD + 0.004


def crown_r(f):
    """The radius a drum worn on the head has to sit at to cover one.

    **A head in this game is a box and most hats here are drums**, so the
    number is the box's own *corner* — `hypot` of its two half-widths — opened
    out twice: once for the hair under the hat, and once for the little a
    regular octagon loses between a vertex and the middle of an edge, which is
    where a corner of the skull meets it.

    This used to be 1.14 times the half-*width*, which is 30 mm inside that
    corner and 45 mm inside the hair on it. Every drum in this file therefore
    passed *through* the four corners of the head it was worn on: a beanie had
    a triangle of scalp at each corner, a hood had a face coming out of the
    front of it, and a hatband was invisible because the whole ring of it was
    inside the skull. It is the one measurement the whole HEAD slot is built
    on, so it is also the one that was wrong eight times over.

    The price is that a round hat on a square head is wide — the corners of a
    0.31 m head are 0.42 m apart, so nothing round covers one and stays
    narrow. That is a fact about the head rather than about the hat.
    """
    corner = math.hypot(f["head_half_x"], f["head_half_y"]) + HAIR_PROUD
    return corner / math.cos(math.pi / 8)


def brow_z(f):
    """Where a band, a brim or the edge of a cap crosses the forehead."""
    return f["eye_z"] + 0.062


def knitted_beanie(f):
    """Pulled down to the brow: a ribbed band, two knit courses and a bobble."""
    p = Part()
    r = crown_r(f)
    low = brow_z(f) - 0.030
    top = f["head_top"] + 0.036
    p.prism((0, 0, low + 0.032), r + 0.012, 0.064, "trim")           # band
    p.prism((0, 0, low + 0.094), r, 0.062, "main")                   # course
    p.prism((0, 0, low + 0.150), r - 0.008, 0.052, "main_light")     # course
    p.prism((0, 0, (low + 0.176 + top) / 2), r - 0.014,
            max(0.02, top - low - 0.176), "main", top_radius=r * 0.44)
    p.prism((0, 0, top + 0.032), r * 0.30, 0.062, "trim", sides=6)   # bobble
    return {"head": p}


def feathered_band(f):
    """A hatband with one moulted primary in it — quill, vane and all.

    The feather is what the piece is, so it gets four of its own shapes and
    the band gets two: a leaning quill with a vane either side of it reads as
    a feather where one flat strut reads as a stick.
    """
    p = Part()
    r = crown_r(f) + 0.004
    z = brow_z(f) + 0.010
    p.prism((0, 0, z), r, 0.052, "main")
    p.prism((0, 0, z + 0.040), r - 0.006, 0.026, "main_dark")
    p.box((0, -r * 0.96, z), (0.048, 0.026, 0.062), "trim_dark")     # keeper
    quill = (Vector((r * 0.62, -r * 0.52, z + 0.010)),
             Vector((r * 0.30, r * 0.86, f["head_top"] + 0.150)))
    p.strut(quill[0], quill[1], 0.014, 0.014, "trim_dark")
    for t, width in ((0.30, 0.052), (0.58, 0.062), (0.84, 0.040)):
        at = quill[0].lerp(quill[1], t)
        p.plate((at.x, at.y, at.z), (width, 0.010, 0.090), "trim",
                tilt=0.5, turn=-0.6)
    return {"head": p}


def straw_boater(f):
    """Flat brim, blue ribbon, and a bow on the back of it.

    Absurd in a wood and worn in one anyway. A boater's crown is straight-sided
    and flat-topped and barely wider than the head, which is exactly what this
    game's shapes are good at.
    """
    p = Part()
    r = crown_r(f) + 0.008
    # A boater's brim is a hand's breadth of straw all the way round, and the
    # crown it is round has to clear a square head — so the brim is measured
    # off the crown rather than off the figure's own hat, which is a different
    # hat and a narrower one.
    brim_r = r + 0.085
    brim_z = brow_z(f) + 0.006
    top = f["head_top"] + 0.030
    p.prism((0, 0, brim_z), brim_r, 0.024, "main", bottom_material="main_dark")
    p.prism((0, 0, brim_z + 0.014), brim_r - 0.030, 0.030, "main_light")
    p.prism((0, 0, (brim_z + top) / 2), r, top - brim_z, "main")
    p.prism((0, 0, top - 0.008), r - 0.006, 0.020, "main_light")
    p.prism((0, 0, brim_z + 0.052), r + 0.008, 0.048, "trim")        # ribbon
    p.box((0, r + 0.020, brim_z + 0.054), (0.090, 0.036, 0.052), "trim")
    p.box((0, r + 0.052, brim_z + 0.054), (0.048, 0.034, 0.030), "trim_dark")
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
    r = crown_r(f) + 0.026
    brow = brow_z(f)
    top = f["head_top"] + 0.050
    # A shell over the whole skull, coming down to the brow at the front and
    # past the nape at the back, with the gorget picking up at the throat.
    p.prism((0, 0.014, brow + 0.030), r, 0.150, "main", squash=1.10)
    p.prism((0, 0.018, (brow + 0.105 + top) / 2), r - 0.012,
            max(0.02, top - brow - 0.105), "main", squash=1.10,
            top_radius=r * 0.58)
    # The peak. Tilted, because a peak that rain runs off is not flat, and a
    # flat one reads as a shelf over somebody's eyes.
    p.plate((0, f["face_y"] - 0.048, brow - 0.014),
            (r * 1.34, 0.110, 0.026), "main_dark", tilt=0.30)
    # Cheek panels down past the jaw, in front of the ear. Narrow: this is
    # the edge of a hood and not a pair of blinkers.
    kit.mirrored(p, ("plate", (f["head_half_x"] + 0.024, f["face_y"] + 0.085,
                               f["eye_z"] - 0.062),
                     (0.038, 0.180, 0.240), "main", 0.0, 0.24))
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
    r = crown_r(f) + 0.010
    z = brow_z(f) + 0.014
    top = f["head_top"]
    p.prism((0, 0, z), r, 0.036, "trim")
    p.prism((0, 0, z + 0.026), r - 0.004, 0.016, "trim_dark")
    # **The hoop is measured off the crown and the antlers off the head.** They
    # used to be written as so much further out than the hoop, which meant that
    # widening the hoop to stop it sitting inside the skull widened the rack
    # with it — a spread this piece never asked for and one that would have
    # taken it through the 0.90 m a worn piece is allowed to be.
    out = f["head_half_x"]
    for side in (1, -1):
        base = Vector((side * r * 0.80, 0.010, z + 0.020))
        mid = Vector((side * (out + 0.115), -0.030, top + 0.130))
        tip = Vector((side * (out + 0.165), -0.115, top + 0.240))
        p.roll(base, mid, 0.021, "main", sides=6)
        p.roll(mid, tip, 0.015, "main", sides=6)
        p.roll(mid, Vector((side * (out + 0.190), 0.105, top + 0.185)),
               0.013, "main_light", sides=6)
        p.roll(base.lerp(mid, 0.45),
               Vector((side * (out + 0.100), -0.150, top + 0.065)),
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
    version of this was. Eight narrow strips read as netting for the same
    reason a picket fence reads as a fence: what you see is more gap than
    slat. At the 0.44 of the chord they started at they were not narrow
    enough to do that and the piece read as a birdcage; at 0.26 the head
    inside is plainly a head.
    """
    p = Part()
    # A brim of its own, because there is no longer a hat to hang off: the
    # figure's own went into the wardrobe and one hat is worn at a time. Off
    # the crown rather than off the head's half-width — a veil that hangs
    # inside the corners of the skull hangs through the face.
    r = crown_r(f) + 0.030
    top = brow_z(f) + 0.030
    drop = top - f["collar_z"] + 0.026
    p.prism((0, 0, top), r + 0.016, 0.018, "trim")
    p.prism((0, 0, top - 0.026), crown_r(f), 0.052, "trim_dark")
    for i in range(8):
        angle = math.tau * (i + 0.5) / 8
        out = r + 0.006
        p.plate((math.sin(angle) * out, math.cos(angle) * out, top - drop / 2),
                (2 * out * math.tan(math.pi / 8) * 0.26, 0.007, drop),
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
    # **Round the outside of the shoulders, not threaded through them.** A
    # neck is 85 mm across and a chest is 165, so a ring built at "the neck
    # and a bit" — which is what this was — is a ring *inside* the body: the
    # panels at the sides were in the chest and the ones at the front and back
    # were in the jaw, and all a player ever saw of a 130-point cosmetic was
    # whichever corner happened to miss. There is no room for a deep fur
    # collar between a chin at 1.285 and shoulders at 1.24, so it does what a
    # real one does and sits on the outside of both.
    r = max(f["neck_r"] + 0.060, f["chest_half_x"] + 0.032)
    z = f["collar_z"] - 0.020
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
    """Strapped across the small of the back: a roll, its blanket edge, and
    three straps.

    It says you meant to be out this long, which is the only job it has, so
    it is built as a real cylinder lying across the back rather than as a box
    somebody will read as a plank.

    **Across the back rather than across the satchel**, which is what it used
    to be written against and is the one thing a walker wearing this is not
    also wearing — see `back_y`. Lower, too: a roll strapped straight onto
    somebody sits at the small of the back where a belt can take its weight,
    not between the shoulder blades where a pack would have held it.
    """
    p = Part()
    y = back_y(f, 0.088)
    z = f["chest_z"] - 0.240
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


def back_y(f, clear):
    """How far behind the middle something hung on the back sits.

    **Off the back, not off the pack.** Everything in the BACK slot used to be
    written against `pack_back_y`, which is where the satchel reaches — 330 mm
    behind the middle of a chest that is 145 mm deep. That was right while the
    pack was part of the body and every cape in the game was worn over one. The
    pack is a BACK piece itself now, and one piece is worn to a slot, so a cape
    was clearing a satchel that by definition was not there: 185 mm of daylight
    between a cloak and the shoulders it is supposed to hang from.
    """
    return f["chest_back_y"] + clear


def _cape(f, panel_material, yoke_material, hem_z):
    """The shape both big back pieces are: a yoke over the shoulders and a
    panel that stands off the back and widens toward the hem.

    Written once because a cape and a cloak differ in what is *on* them and
    not in how they hang, and two copies of this arithmetic would drift.
    """
    p = Part()
    # A hand's breadth off the back, which is what a cape hanging from a yoke
    # does and is as much as it can stand off before it reads as a signboard.
    y = back_y(f, 0.062)
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


def _legs(f, boot, shaft):
    """A boot on the foot bones and a shaft up the shin, on the shin bones.

    **Anything that reaches the knee has to bend at the ankle.** A gaiter and
    a pair of waders were both built by `_feet` — every triangle of them
    parented to `foot_l` and `foot_r` — so 300 mm of canvas swung about the
    ankle with the boot. Standing still nobody could see it; at a walk the
    tops of both waders scythed forward and back through the shins inside
    them, once a stride, because a foot rolls through 40° in a step and a shin
    does not.

    Splitting them is a change of *which bone carries which box* and nothing
    else: both parts are still authored in world metres on a figure standing
    at the origin, so the two halves meet exactly where they met before.
    """
    made = {}
    for foot, shin, side in (("foot_l", "shin_l", 1), ("foot_r", "shin_r", -1)):
        low, high = Part(), Part()
        boot(low, side)
        shaft(high, side)
        made[foot] = low
        made[shin] = high
    return made


#: How much of a foot's fore-and-aft depth a shin has.
#
#: A boot is long because a foot is; a shin is nearly round. A shaft tapered
#: from a boot's depth to a boot's depth is a slab of canvas the length of a
#: foot standing on edge all the way to the knee, which is what a pair of
#: waders looked like from any angle but dead ahead.
SHIN_OF_BOOT = 0.86

#: The widest a thing worn on one foot may be, as a share of the gap between
#: the two of them.
#
#: Feet are 210 mm apart on the walker and a boot is 185 mm across, so there
#: is 25 mm of daylight between them and that gap is the entire reason a pair
#: of legs reads as two legs. A gaiter at 208 and a wader at 212 closed it: two
#: of them met at the centre line and what a player saw below the knee was one
#: dark slab with a notch in it.
FOOT_SPAN = 0.90


def canvas_gaiters(f):
    """Buckled up the shin. Keeps the burrs out, mostly."""
    bx, by, bz = f["boot"]
    wide, deep = f["boot_half_x"], f["boot_half_y"]
    ankle = f["boot_top"] - 0.070
    top = f["boot_top"] + 0.145
    shin = deep * SHIN_OF_BOOT
    half = bx * FOOT_SPAN

    def boot(p, side):
        x = side * bx
        p.prism((x, by - 0.004, ankle + 0.012), min(wide + 0.020, half), 0.030,
                "trim", sides=6, squash=1.44)
        p.strut((x - (wide + 0.010), by + 0.006, bz - 0.006),
                (x + (wide + 0.010), by + 0.006, bz - 0.006),
                0.024, 0.024, "trim_dark")                           # instep

    def shaft(p, side):
        x = side * bx
        p.taper((x, by - 0.006, ankle), (x, by * 0.30, top),
                (min(wide + 0.014, half) * 2, deep * 1.86),
                ((wide - 0.008) * 2, shin * 1.44), "main")
        p.prism((x, by * 0.30, top - 0.014), wide + 0.008, 0.036, "trim",
                sides=6, squash=1.30)
        for at in (0.24, 0.56, 0.86):
            z = ankle + (top - ankle) * at
            p.box((x + side * (wide - 0.006), by * 0.40 - 0.014, z),
                  (0.020, 0.030, 0.026), "trim_dark")                # hooks
    return _legs(f, boot, shaft)


def river_waders(f):
    """To the knee, rubberised, and they squeak. Every fisher owns a pair."""
    bx, by, bz = f["boot"]
    wide, deep = f["boot_half_x"], f["boot_half_y"]
    top = f["knee_z"] + 0.030
    shin = deep * SHIN_OF_BOOT
    half = bx * FOOT_SPAN

    def boot(p, side):
        x = side * bx
        p.box((x, by, bz + 0.006), (min(wide + 0.016, half) * 2, deep * 2.10,
                                    0.100), "main")
        p.box((x, by - 0.010, bz - 0.030), (min(wide + 0.020, half) * 2,
                                            deep * 2.16, 0.028), "trim_dark")

    def shaft(p, side):
        x = side * bx
        p.taper((x, by * 0.60, 0.090), (x, 0.004, top),
                (min(wide + 0.016, half) * 2, deep * 1.94),
                ((wide - 0.004) * 2, shin * 1.42), "main")
        p.prism((x, 0.004, top - 0.020), wide + 0.014, 0.046, "main_light",
                sides=6, squash=1.22)                                # turned top
        p.prism((x, 0.004, top - 0.058), wide + 0.006, 0.026, "trim",
                sides=6, squash=1.24)
        p.box((x + side * (wide - 0.002), -0.004, top - 0.090),
              (0.022, 0.034, 0.070), "trim_dark")                    # buckle
    return _legs(f, boot, shaft)


# --- the standard kit -------------------------------------------------------
#
# **What used to be the figure.** These six were modelled into the body until
# the day somebody wanted the coat off, and they are ordinary worn pieces now:
# owned from the start, worn by default, free, on no rail, and every one of
# them removable and recolourable like anything else.
#
# They are also the reason the two figures still read as two people while
# wearing the same catalogue. One key, two files: `walker/walking_hat.glb` is a
# flat-brimmed campaign hat with a four-sided peak and
# `wayfarer/walking_hat.glb` is a soft felt one with a rolled brim and a bell
# crown, because a wardrobe cut per figure can afford to be.

def field_coat(f):
    """The green field coat, and the belt that cinches it.

    A collar, a placket with buttons down it, patch pockets, shoulder yokes a
    shade lighter, and a skirt that tapers out to the hem — which is what a
    stack of boxes cannot do without a staircase down its side.
    """
    p = Part()
    chest_x, back = f["chest_half_x"], f["chest_back_y"]
    waist_x, skirt_x = f["waist_half_x"], f["skirt_half_x"]
    front = f["chest_front_y"]
    collar = f["collar_z"]
    belt = f["hip_z"] + 0.100
    # **Deep enough to be over the vest rather than in it.** `bodies.py` builds
    # the vest at `back * 1.98` and this used to be `back * 2.02` — under three
    # millimetres of cloth between them, which is less than the gap between two
    # sheets of paper at this scale and much less than a painter's algorithm
    # can be relied on to tell apart. What showed for it was two pale wedges of
    # underwear through the chest of a waxed coat.
    p.taper((0, 0, belt - 0.030), (0, 0, collar - 0.014),
            (waist_x * 2.10, back * 2.10), (chest_x * 2.08, back * 2.16), "main")
    p.box((0, 0, collar), (chest_x * 1.50, back * 1.48, 0.058), "main_light")
    kit.mirrored(p, ("box", (chest_x * 0.44, front - 0.014, collar - 0.022),
                     (chest_x * 0.50, 0.030, 0.052), "main_light"))
    p.box((0, front - 0.020, (belt + collar) / 2),
          (0.052, 0.022, collar - belt - 0.030), "main_dark")         # placket
    for at in (0.24, 0.62):
        p.box((0, front - 0.032, belt + (collar - belt) * at),
              (0.026, 0.020, 0.026), "trim")                          # buttons
    kit.mirrored(p, ("box", (chest_x * 0.90, 0, f["shoulder_z"] - 0.010),
                     (chest_x * 0.90, back * 2.24, 0.058), "main_light"))
    kit.mirrored(p, ("box", (chest_x * 0.52, front - 0.014, belt + 0.120),
                     (chest_x * 0.68, 0.038, 0.100), "main_dark"))    # pockets
    kit.mirrored(p, ("box", (chest_x * 0.52, front - 0.018, belt + 0.176),
                     (chest_x * 0.74, 0.042, 0.030), "main_light"))
    # The belt and the skirt clear the shorts' waistband, which `bodies.py`
    # puts at `back * 2.02` — the same three millimetres the coat body had.
    p.box((0, 0, belt), (waist_x * 2.16, back * 2.22, 0.062), "trim_dark")
    p.box((0, front - 0.024, belt), (0.058, 0.032, 0.058), "trim")    # buckle
    p.taper((0, 0, f["hem_z"]), (0, 0, belt - 0.010),
            (skirt_x * 2, back * 2.36), (waist_x * 2.14, back * 2.16), "main_dark")
    p.box((0, 0, f["hem_z"] + 0.014), (skirt_x * 2 + 0.008, back * 2.42, 0.028),
          "main_dark")

    # **Sleeves, on the arm bones and not on the spine.** A coat modelled as a
    # body and a pair of front panels is a waistcoat: the bare arm shows
    # straight through it, which is what the first version of this looked like.
    # An upper sleeve and a cuff on each arm, each on its own bone, so the coat
    # bends at the elbow with the person inside it.
    made = {"spine": p}
    wrist = wrist_of(f)
    for arm, fore, side in (("arm_l", "forearm_l", 1), ("arm_r", "forearm_r", -1)):
        x = side * f["shoulder_x"]
        upper = Part()
        upper.taper((x, 0, f["elbow_z"] - 0.024), (x, 0, f["shoulder_z"] + 0.040),
                    (0.126, 0.126), (0.150, 0.150), "main")
        upper.box((x, -0.012, f["elbow_z"] - 0.002), (0.134, 0.138, 0.062),
                  "main_dark")                                        # elbow
        upper.box((x, -0.022, f["shoulder_z"] - 0.086), (0.140, 0.092, 0.050),
                  "trim")                                             # patch
        made[arm] = upper
        cuff = Part()
        cuff.strut((x, -0.008, f["elbow_z"]), (x, wrist[1], wrist[2] + 0.026),
                   0.112, 0.112, "main")
        cuff.box((x, wrist[1] + 0.004, wrist[2] + 0.036), (0.122, 0.122, 0.046),
                 "main_light")
        made[fore] = cuff
    return made


def field_trousers(f):
    """Trousers, with a cargo pocket on the thigh and a turn-up at the ankle.

    One leg on each leg bone rather than both on the spine, so they bend at the
    knee with the person in them.
    """
    made = {}
    for bone, side in (("leg_l", 1), ("leg_r", -1)):
        p = Part()
        x = side * f["hip_x"]
        top = f["hip_z"] + 0.020
        p.taper((x, 0, f["knee_z"] - 0.010), (x, 0, top),
                (0.152, 0.152), (0.176, 0.176), "main")
        p.box((x, -0.005, f["knee_z"]), (0.148, 0.156, 0.054), "main_dark")
        p.box((x, -0.062, f["knee_z"] + 0.130), (0.150, 0.070, 0.130), "main_dark")
        p.box((x, -0.066, f["knee_z"] + 0.204), (0.156, 0.076, 0.032), "trim")
        made[bone] = p
    for bone, side in (("shin_l", 1), ("shin_r", -1)):
        p = Part()
        x = side * f["hip_x"]
        p.taper((x, 0, f["boot_top"] - 0.040), (x, 0, f["knee_z"] + 0.006),
                (0.128, 0.128), (0.146, 0.146), "main")
        p.box((x, 0, f["boot_top"] - 0.032), (0.134, 0.134, 0.030), "trim")
        made[bone] = p
    return made


def walking_boots(f):
    """A sole, a toe cap, a tall shaft, laces up the front — and mud.

    The mud is a band of its own colour just above the sole rather than shading
    painted on, because a face painted dark to fake a shadow is dark on the
    sunny side too. This one is dirt, and dirt is dark on both sides.
    """
    wide, deep = f["boot_half_x"], f["boot_half_y"]
    bz = f["boot"][2]
    top = f["boot_top"]

    def build(p, side):
        x = side * f["boot"][0]
        y = f["boot"][1]
        p.box((x, y, bz + 0.006), (wide * 2.06, deep * 2.06, 0.088), "main")
        p.box((x, y, 0.012), (wide * 2.16, deep * 2.16, 0.024), "main_dark")
        p.box((x, y, 0.032), (wide * 2.10, deep * 2.10, 0.026), "trim_dark")
        p.box((x, y - deep * 0.78, 0.056), (wide * 1.76, 0.072, 0.070), "main_dark")
        p.taper((x, 0, 0.088), (x, -0.004, top),
                (wide * 2.10, deep * 2.20), (wide * 1.90, deep * 1.96), "trim")
        p.box((x, -deep * 0.70, 0.168), (0.052, 0.026, 0.132), "main_dark")
        p.box((x, -0.004, top - 0.016), (wide * 2.00, deep * 2.04, 0.032), "main")
        return p
    return _feet(f, build)


def field_pack(f):
    """A pack on the back, a bedroll lashed under it, and the straps that say
    it is being carried rather than floating behind somebody.

    **This is the side of the figure a player actually looks at** — the camera
    is behind them in third person — so it gets the same care the front does.
    """
    p = Part()
    chest_x, back = f["chest_half_x"], f["chest_back_y"]
    mid = f["chest_z"] - 0.020
    # From the back of the chest to `pack_back_y`, which is what that row of
    # the table means and is now the only thing that reads it: the capes and
    # the bedroll used to be cut against it too, and were cut against a satchel
    # they can never be worn with. See `back_y`.
    deep = f["pack_back_y"] - back
    y = back + deep / 2
    p.box((0, y, mid), (chest_x * 1.76, deep, 0.300), "main")
    p.box((0, y, mid + 0.170), (chest_x * 1.84, 0.186, 0.056), "main_light")
    kit.mirrored(p, ("box", (chest_x * 0.96, y + 0.008, mid - 0.040),
                     (0.052, 0.130, 0.168), "main_light"))
    kit.mirrored(p, ("box", (chest_x * 0.52, y + 0.084, mid + 0.140),
                     (0.040, 0.020, 0.124), "main_dark"))
    kit.mirrored(p, ("box", (chest_x * 0.52, y + 0.084, mid + 0.058),
                     (0.032, 0.024, 0.032), "trim"))
    p.roll((-(chest_x + 0.070), y + 0.010, mid - 0.150),
           (chest_x + 0.070, y + 0.010, mid - 0.144), 0.058, "trim_dark",
           end_material="main_dark")
    kit.mirrored(p, ("strut", (chest_x * 0.60, y - 0.038, f["shoulder_z"] + 0.020),
                     (chest_x * 0.62, f["chest_front_y"] - 0.010, mid - 0.020),
                     0.042, 0.020, "main"))
    return {"spine": p}


def walking_hat(f):
    """The hat a figure walks out in — and the one piece cut differently enough
    per figure that they read as two people from behind at two hundred metres.

    A campaign hat for the walker: a flat brim with a darker underside, a band,
    a box of a crown and a four-sided peak on top. A soft felt one for the
    wayfarer: a rolled brim and a bell crown. `figures.py` says which by
    carrying a `beard`, which is a proxy nobody should have to defend — so it
    says so explicitly instead, with `peaked`.
    """
    p = Part()
    brim_r, brim_z = f["hat_brim_r"], f["hat_brim_z"]
    top = f["hat_top"]
    if f.get("peaked"):
        # A box crown, so it is measured against the box of a head rather than
        # against a radius: this much proud of the skull on every side is this
        # much proud at the corners too, which is the one shape that gets that
        # for nothing. `hat_crown_r` used to be a row of the table and was a
        # radius pretending to be a half-width.
        hx = f["head_half_x"] + head_pad(f)
        hy = f["head_half_y"] + head_pad(f)
        p.box((0, 0, brim_z), (brim_r * 2, brim_r * 1.88, 0.030), "main",
              faces={"-z": "main_dark"})
        p.box((0, 0, brim_z + 0.020), (hx * 2.12, hy * 2.12, 0.030), "trim")
        p.box((0, 0, brim_z + 0.074), (hx * 2, hy * 2, 0.108), "main")
        p.pyramid((0, 0), brim_z + 0.128, hx * 1.82, top, "main")
        p.box((0, -hy * 1.02, brim_z + 0.062), (0.050, 0.022, 0.050), "trim_dark")
    else:
        # …and a drum crown, which is the hard version — see `crown_r`.
        r = crown_r(f)
        p.prism((0, 0, brim_z), brim_r, 0.044, "main", squash=0.96)
        p.prism((0, 0, brim_z + 0.002), brim_r - 0.020, 0.026, "main",
                squash=0.96, bottom_material="main_dark")
        p.prism((0, 0, (brim_z + top) / 2 + 0.014), r, top - brim_z - 0.028,
                "main", squash=0.96, top_radius=r * 0.72)
        p.prism((0, 0, brim_z + 0.030), r + 0.008, 0.034, "trim", sides=6,
                squash=0.96)
        p.strut((r * 0.64, -r * 0.62, brim_z + 0.036),
                (r * 0.96, r * 0.84, top + 0.014), 0.052, 0.014, "main_light")
    return {"head": p}


def neckerchief(f):
    """Worn high, standing proud of the jaw, with one corner down the front.

    The one thing on the standard kit that is the figure's own colour rather
    than the coat's — which is what makes two people in the same uniform
    tellable apart at the distance a coat stops being a coat.
    """
    p = Part()
    r = f["neck_r"] + 0.038
    z = f["collar_z"] + 0.020
    p.prism((0, 0, z), r, 0.062, "main", squash=1.06)
    p.prism((0, 0, z + 0.040), r - 0.008, 0.024, "trim", squash=1.06)
    p.plate((0, f["chest_front_y"] + 0.010, z - 0.086),
            (0.092, 0.030, 0.120), "main_dark", tilt=-0.10)
    return {"head": p}


# --- hair -------------------------------------------------------------------
#
# **A hairstyle is a worn piece.** The body is bald — see `bodies.py` — so this
# is where hair lives, on the head bone, in the HAIR slot, off the same rail
# rules as everything else except that nobody has to buy it: hair is not loot.
#
# Four of them, cut to each head. Both figures can wear any of them; which one
# they start in is `Figure`'s business and not this file's.

def _scalp(p, f, thickness=0.022):
    """The cap every hairstyle starts from: the skull, a shade proud of it."""
    hx, hy = f["head_half_x"], f["head_half_y"]
    top, mid = f["head_top"], f["head_z"]
    p.box((0, 0.004, mid + (top - mid) * 0.52),
          (hx * 2 + thickness, hy * 2 + thickness, (top - mid) * 1.20), "main")
    p.box((0, hy + thickness * 0.4, mid), (hx * 2 - 0.010, thickness * 1.6,
                                           (top - mid) * 1.30), "main_dark")


def cropped_hair(f):
    """Short back and sides. Nothing to catch on a branch."""
    p = Part()
    _scalp(p, f, 0.016)
    hx, hy = f["head_half_x"], f["head_half_y"]
    p.box((0, f["face_y"] + 0.008, f["eye_z"] + 0.086),
          (hx * 2 + 0.014, 0.040, 0.036), "main_dark")                # fringe
    kit.mirrored(p, ("box", (hx + 0.006, 0.006, f["eye_z"] + 0.052),
                     (0.018, hy * 1.70, 0.090), "main"))              # sides
    return {"head": p}


def swept_hair(f):
    """A side sweep and a fringe on the brow. The walker's own."""
    p = Part()
    _scalp(p, f)
    hx, hy = f["head_half_x"], f["head_half_y"]
    p.box((0, f["face_y"] + 0.004, f["eye_z"] + 0.082),
          (hx * 2 + 0.018, 0.052, 0.056), "main")
    p.plate((hx * 0.30, f["face_y"] + 0.010, f["eye_z"] + 0.100),
            (hx * 1.30, 0.048, 0.048), "main_light", turn=0.22)
    kit.mirrored(p, ("box", (hx + 0.010, 0.010, f["eye_z"] + 0.030),
                     (0.024, hy * 1.86, 0.130), "main"))
    p.box((0, hy + 0.030, f["head_z"] - 0.052), (hx * 1.80, 0.058, 0.128), "main")
    return {"head": p}


def long_plait(f):
    """Long, gathered at the nape and plaited forward over one shoulder.

    Forward rather than down the back for one reason that is entirely about
    this game: the back has a pack on it, and a rope of hair down the middle of
    a bedroll is a smear rather than a plait. Over the front it has a coat to
    lie against and a silhouette of its own.
    """
    p = Part()
    _scalp(p, f)
    hx, hy = f["head_half_x"], f["head_half_y"]
    p.box((0, f["face_y"] + 0.010, f["eye_z"] + 0.076),
          (hx * 2 + 0.020, 0.062, 0.070), "main")                     # fringe
    p.box((0, hy + 0.038, f["head_z"] - 0.010), (hx * 2.04, 0.074, 0.240), "main")
    kit.mirrored(p, ("box", (hx + 0.012, -0.030, f["head_z"] - 0.026),
                     (0.036, hy * 2.30, 0.200), "main"))
    p.box((0, hy + 0.026, f["head_z"] - 0.132), (hx * 1.20, 0.078, 0.116), "main")
    # Three strands of falling width and a tie, which is the fewest that reads
    # as plaited rather than as a rope.
    p.strut((hx * 0.50, hy * 0.76, f["head_z"] - 0.128),
            (hx * 0.86, f["face_y"] * 0.20, f["collar_z"] - 0.030),
            0.070, 0.070, "main")
    p.strut((hx * 0.86, f["face_y"] * 0.20, f["collar_z"] - 0.030),
            (hx * 0.78, f["chest_front_y"] + 0.014, f["collar_z"] - 0.140),
            0.060, 0.060, "main_dark")
    p.strut((hx * 0.78, f["chest_front_y"] + 0.014, f["collar_z"] - 0.140),
            (hx * 0.68, f["chest_front_y"] - 0.006, f["collar_z"] - 0.228),
            0.046, 0.046, "main")
    p.box((hx * 0.67, f["chest_front_y"] - 0.008, f["collar_z"] - 0.240),
          (0.050, 0.050, 0.026), "trim")
    return {"head": p}


def topknot(f):
    """Gathered up and out of the way, with a pin through it."""
    p = Part()
    _scalp(p, f)
    hx, hy = f["head_half_x"], f["head_half_y"]
    top = f["head_top"]
    p.box((0, f["face_y"] + 0.014, f["eye_z"] + 0.084),
          (hx * 1.70, 0.044, 0.040), "main_dark")
    p.prism((0, 0.026, top + 0.052), hx * 0.62, 0.088, "main", sides=6)
    p.prism((0, 0.026, top + 0.104), hx * 0.44, 0.036, "main_light", sides=6)
    p.roll((-hx * 0.72, 0.026, top + 0.062), (hx * 0.72, 0.026, top + 0.044),
           0.011, "trim", sides=6)                                    # the pin
    return {"head": p}


#: Which function builds which key, in the catalogue's own order.
BUILDERS = {
    "field_coat": field_coat,
    "field_trousers": field_trousers,
    "walking_boots": walking_boots,
    "field_pack": field_pack,
    "walking_hat": walking_hat,
    "neckerchief": neckerchief,
    "cropped_hair": cropped_hair,
    "swept_hair": swept_hair,
    "long_plait": long_plait,
    "topknot": topknot,
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
