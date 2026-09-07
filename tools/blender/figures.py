"""
The two people you can walk as, measured.

**Why there is a table rather than two scripts each with its own numbers.**
A cosmetic is the one model in this game that is never measured and never
rescaled: the height an artist puts a hat at is the height it is worn at. So
a wardrobe is only ever fitted to *a particular figure*, and the moment there
are two figures there are two wardrobes — thirty-six files whose every
number is read off a body. Written twice, they would be wrong within a month
and wrong in a binary that does not diff.

So every landmark either figure has is in here, once, and `wayfarer.py`
builds a body out of it while `cosmetics.py` builds clothes out of it. Both
of them read the same rows, which is the whole point: a collar sits at the
Z the collar bone is at because it is literally the same number.

--- the two of them -------------------------------------------------------

    walker      the figure this game has shipped since people had bodies.
                Square-shouldered, field coat, campaign hat, a pack on the
                back. Built by `ranger.py`, which is *not* driven from this
                table — see WALKER below for why, and for what keeps the two
                honest.

    wayfarer    the second figure. A slighter build: narrower shoulders, a
                waist, a longer coat with more flare in the skirt, a soft
                brimmed hat instead of the campaign one, and a plait rather
                than a beard. Built by `wayfarer.py` out of these rows.

Both stand **exactly 1.78 m** floor to crown, and that number is not a
coincidence or a style: an imported character is normalised by its height
and drawn at `WalkerModel.HEIGHT`, so a figure authored at 1.78 comes out at
the metres it was authored in and a figure authored at anything else does
not. Author at 1.78 and the landmarks below are the landmarks the game
draws — which is what lets a hat be written at 1.62 and arrive at 1.62.

--- everything is metres, floor up ----------------------------------------

    +Z  up, from the sole of the boot at 0
    -Y  the way they face
    +X  the side the `_l` bones are on
"""

# --- the walker ------------------------------------------------------------
#
# **These are read off `ranger.py`, not fed into it.** That script built
# `characters/walker.glb` before this table existed and it stays
# self-contained on purpose: it is the figure the game has always drawn, in a
# binary nobody can diff, and a refactor that changed one rounding in it would
# change what every player looks like without a single line of review
# noticing.
#
# The copy is kept honest from the outside instead —
# `PlayerFiguresTest.theWalkerTableAgreesWithTheScriptThatBuiltIt` reads both
# files and asserts row against row, so a number that moves in one and not the
# other fails a build rather than misfitting a wardrobe.

WALKER = {
    "key": "walker",
    "model": "characters/walker",

    # --- the skeleton, as `ranger.py` lays it out -------------------------
    "sole": 0.00,
    "ankle_z": 0.07,
    "knee_z": 0.37,
    "hip_z": 0.69,
    "waist_z": 0.95,
    "shoulder_z": 1.18,
    "neck_z": 1.27,
    "head_z": 1.45,
    "crown_z": 1.78,
    "hip_x": 0.105,
    "shoulder_x": 0.205,
    "elbow_z": 0.90,

    # --- what a cosmetic is fitted to -------------------------------------
    #
    # Every one of these is a face or a centre of a box in `ranger.py`, and
    # each is here because some piece of the wardrobe is written against it.
    # The names are what a garment would call them rather than what an
    # anatomist would.

    # The head, as a box: centre, and how far it reaches.
    "head_half_x": 0.155,          # skull 0.31 wide
    "head_half_y": 0.145,          # …and 0.29 deep
    "head_top": 1.615,             # the top of the skull, under the hat
    "face_y": -0.145,              # the front of the face
    "eye_z": 1.462,                # …and the height of the eyes in it

    # The hat already on them, which everything in the HEAD slot goes over.
    #
    # `hat_cover_r` is the one an artist actually needs: the radius a piece
    # has to reach to *hide* the crown underneath it. The walker's crown is
    # a square box 0.33 across, so covering it takes the radius of its
    # corners, 0.233, and not half of its side.
    "hat_brim_z": 1.590,
    "hat_brim_r": 0.320,           # half of a 0.64 brim: the widest thing
    "hat_crown_r": 0.165,
    "hat_cover_r": 0.238,
    "hat_top": 1.780,

    # The throat, and the chest under it.
    "collar_z": 1.270,
    "neck_r": 0.085,
    "chest_z": 1.030,
    "chest_half_x": 0.165,
    "chest_front_y": -0.145,
    "chest_back_y": 0.145,
    "waist_half_x": 0.170,         # the belt, 0.34 across

    # The back: a cape has to clear the pack, not the coat.
    "pack_back_y": 0.330,
    "hem_z": 0.445,                # the bottom of the coat skirt
    "skirt_half_x": 0.180,

    # A hand at rest, and a boot on the floor.
    "hand": (0.205, -0.062, 0.588),
    "hand_half": 0.055,
    "boot": (0.105, -0.030, 0.045),
    "boot_half_x": 0.090,
    "boot_half_y": 0.135,
    "boot_top": 0.245,             # the top of the boot cuff
    "shin_half_x": 0.075,
}


# --- the wayfarer ----------------------------------------------------------
#
# The second figure, and `wayfarer.py` is built out of exactly these rows —
# so unlike the block above, this one is the source rather than a copy of it.
#
# **What makes it a different person and not a smaller one.** Scaling the
# walker down would have produced a child, which is the usual failure here.
# The differences are proportional instead: the shoulders come in by 30 mm
# while the hips stay where they are, the ribcage narrows and the waist is
# cut above it rather than at the belt, the legs take 25 mm off the torso,
# and the head loses 25 mm across while the neck gains 25 mm of length. The
# silhouette that comes out of that reads at two hundred metres, which is the
# distance this game asks a figure to read at.

WAYFARER = {
    "key": "wayfarer",
    "model": "characters/wayfarer",

    # --- the skeleton ------------------------------------------------------
    "sole": 0.00,
    "ankle_z": 0.065,
    "knee_z": 0.395,               # longer in the leg than the walker
    "hip_z": 0.715,
    "waist_z": 0.985,              # …and cut higher, which is the whole shape
    "shoulder_z": 1.190,
    "neck_z": 1.290,
    "head_z": 1.470,
    "crown_z": 1.780,
    "hip_x": 0.100,
    "shoulder_x": 0.175,           # 30 mm in from the walker's
    "elbow_z": 0.915,

    # --- what a cosmetic is fitted to -------------------------------------
    "head_half_x": 0.143,
    "head_half_y": 0.137,
    "head_top": 1.620,
    "face_y": -0.137,
    "eye_z": 1.483,

    # A soft felt hat: a rolled brim and a round crown, where the walker has
    # a flat brim and a peak. Narrower than his, so a hat bought off a rail
    # is cut to it rather than hung over it.
    "hat_brim_z": 1.612,
    "hat_brim_r": 0.275,
    "hat_crown_r": 0.152,
    "hat_cover_r": 0.162,          # a round crown, so barely more than itself
    "hat_top": 1.780,

    "collar_z": 1.283,
    "neck_r": 0.073,
    "chest_z": 1.048,
    "chest_half_x": 0.142,
    "chest_front_y": -0.130,
    "chest_back_y": 0.130,
    "waist_half_x": 0.126,         # nipped, and 44 mm narrower than his belt

    "pack_back_y": 0.300,
    "hem_z": 0.400,                # a longer coat, and more flare in it
    "skirt_half_x": 0.178,

    "hand": (0.175, -0.058, 0.600),
    "hand_half": 0.049,
    "boot": (0.100, -0.028, 0.042),
    "boot_half_x": 0.082,
    "boot_half_y": 0.125,
    "boot_top": 0.255,             # a taller boot, which is most of the leg
    "shin_half_x": 0.066,
}


#: Both of them, in the order a menu offers them.
ALL = (WALKER, WAYFARER)

#: …by the key the game files them under.
BY_KEY = {figure["key"]: figure for figure in ALL}


def figure(key):
    """One of them by key, or a KeyError naming what there is."""
    if key not in BY_KEY:
        raise KeyError("no figure %r — there is %s"
                       % (key, " and ".join(sorted(BY_KEY))))
    return BY_KEY[key]


# --- the palette -----------------------------------------------------------
#
# **One tin, two figures.** They wear the same field coat in the same green,
# because they are two people doing the same job and the wardrobe on the rail
# is cut for both of them: a second palette would make every cosmetic's trim
# read as belonging to one of them. What differs is hair, and the one colour
# each of them takes as their own — the walker's brick neckerchief against
# the wayfarer's heather, which is the pair you can actually tell apart in a
# wood at dusk.

COAT = {
    "coat":          0x3C5240,
    "coat_dark":     0x2E4033,
    "coat_light":    0x4A6450,
    "trouser":       0x6B6247,
    "leather":       0x4A3626,
    "leather_light": 0x5C4433,
    "brass":         0xB8A050,
    "glass":         0x243230,
    "eye":           0x241C18,
}

#: What each figure adds to it, and what they call their own.
WALKER_SKIN = {"skin": 0xC98F63, "hair": 0x4A3220, "trim": 0xA8442E}
WAYFARER_SKIN = {"skin": 0xD9A277, "hair": 0x6B3A22, "trim": 0x8C4A6B}

#: name -> (which colour, how much of it). `ShopModel.shade`, in Python.
DERIVED = {
    "coat_shadow":   ("coat", 0.70),
    "trouser_shin":  ("trouser", 0.94),
    "trouser_knee":  ("trouser", 0.86),
    "leather_dark":  ("leather", 0.72),
    "boot_mud":      ("leather", 0.86),
    "brass_dark":    ("brass", 0.70),
    "skin_shadow":   ("skin", 0.94),
    "skin_light":    ("skin", 1.04),
    "hair_dark":     ("hair", 0.90),
    "hair_light":    ("hair", 1.12),
    "trim_dark":     ("trim", 0.80),
    "trim_light":    ("trim", 1.15),
}


def colours(figure_key):
    """The whole tin for one figure: {name: rgb}."""
    made = dict(COAT)
    made.update(WALKER_SKIN if figure_key == "walker" else WAYFARER_SKIN)
    return made
