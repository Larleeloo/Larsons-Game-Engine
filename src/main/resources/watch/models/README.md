# Animal models — importing from Blockbench

Every one of the **1323** species in the Field Guide already has a model. They
are generated: a pile of boxes assembled from the species' build, painted from a
64×64 skin sheet drawn from its own three colours, animated by hand-written
procedural poses. They are placeholders, and they are meant to be replaced.

This folder is where the real art goes. **Drop a `.bbmodel` in here and it is
used instead**, with no code change, no rebuild, and no restart beyond the one
that reloads the folder. Nothing in here can stop the game starting: a file that
is missing, malformed, or built out of things this renderer cannot draw leaves
that species with its placeholder and prints one line to stderr.

You can do this one animal at a time, and one *animation* at a time. That is
deliberate — see [Partial models are fine](#partial-models-are-fine).

---

## 1. Where the file goes and what it is called

The loader looks for a species' model in this order, taking the first that
loads:

| Order | File | Covers |
|---|---|---|
| 1 | `watch/models/<species key>.bbmodel` | that one species |
| 2 | `watch/models/<family key>.bbmodel` | all 49 species of that family |
| 3 | *(none)* | the generated placeholder |

`.json` works as well as `.bbmodel` — Blockbench's **File → Export → Blockbench
Model** and its generic JSON export are the same format as far as this reader is
concerned.

**Species keys** are `<family>_<lineage>_<epithet>`, all lower case with
underscores:

```
songbird_finch_banded          Banded Finch
wader_stork_snow               Snow Stork
corvid_jackdaw_ivory           Ivory Jackdaw
primate_marmoset_bronze        Bronze Marmoset
bat_horseshoe_bat_striped      Striped Horseshoe Bat
```

Note the order: a species is displayed **epithet first** ("Snow Stork") and keyed
**lineage first** (`wader_stork_snow`), because the key sorts a family's
lineages together and the name reads like a name. To build one by hand: take the
family word, the lineage, and the epithet; lower-case each, turn spaces into
`_`, drop hyphens, and join with `_`. `AnimalRegistry.all()` lists every one of
them if you would rather read them off than spell them out.

**Family keys** are the 29 words below. A file named for one of these dresses
every species in that family, which is how you redress the whole game with
twenty-nine files rather than thirteen hundred:

```
songbird   raptor     owl        waterfowl  wader      shorebird
hummingbird parrot    corvid     woodpecker gamebird   seabird
deer       canid      felid      rodent     mustelid   primate
bear       bovid      hare       bat        reptile    amphibian
butterfly  fish       sprite
wendigo    werewolf   mirewraith
```

The last three are the mutants, and each is a family of exactly one species —
so for those three, the family file and the species file dress the same animal.
They are the only bipeds here, they stand between four and six metres, and they
are the only models that will ever play the `strike` animation (§4).

**Two places are searched, in this order:**

1. a `watch/models/` folder **next to the jar** — so a player can drop a model
   into an installed game without building anything;
2. `watch/models/` **on the classpath** — this folder, for models that ship with
   the game.

The first wins, so a local file overrides a bundled one.

---

## 2. Bone names — the one convention you have to follow

A box belongs to a joint because of **the name of the bone (group) it is
inside**. That is the entire binding contract. Names are matched
case-insensitively on substrings, after `-` and spaces are folded to `_`, so
`left_wing`, `leftWing`, `wing_l`, `Wing Left`, and `wing-left` all mean the
same thing.

| Joint | Bone name contains | Notes |
|---|---|---|
| `HEAD` | `head`, `skull`, `neck`, `beak`, `bill`, `snout`, `jaw`, `eye` | |
| `BODY` | `body`, `torso`, `chest`, `root` | also the default |
| `WING_L` / `WING_R` | `wing`, `fin`, `flipper` | side from `left`/`right`, `_l`/`_r` |
| `LEG_FL` / `LEG_FR` | `leg`, `foot`, `paw`, `talon`, `claw` + `front` or `fore` | |
| `LEG_BL` / `LEG_BR` | the same + `back`, `hind`, or `rear` | |
| `TAIL` | `tail` | |
| `EAR` | `ear`, `antenna` | |
| `HORN` | `horn`, `antler`, `crest` | |

Two rules make this less fussy than it looks:

- **A bone inherits its parent's joint.** A group called `beak` inside `head`
  does not need to be named for the head — and neither does a group called
  `feathers` or `tuft` or anything else that matched nothing. Nest the detail
  under the part it belongs to and it follows that part.
- **A top-level bone that matches nothing is body.** Getting it wrong is a box
  that does not move, not a box that vanishes.

A legless model is fine. A model with only a body is fine. Everything that is
absent is simply not animated.

### The skin a box is painted with

Each joint takes its colour from a region of the species' 64×64 skin sheet —
head boxes from the head region, wing boxes from the wing strip, and so on. If
your `.bbmodel` **embeds its own texture**, that texture is used instead, for
every species this file covers.

---

## 3. Axes, units and pivots

Blockbench and this game do not agree about which way is which. **The importer
converts for you** — this section is here so the result is not a surprise.

| | Blockbench | Field Guide |
|---|---|---|
| X | right | **forward** (the way it faces) |
| Y | up | right |
| Z | toward the viewer / front | **up** |

So: **model your animal facing +Z in Blockbench** (Blockbench's "front"), standing
on the ground plane, and it comes into the world facing the way it walks.

**Units.** Blockbench works in pixels, 16 to a Minecraft block. You do not have
to model at any particular size: on import the model is scaled so its **longest
horizontal extent becomes one body length**, and the registry then scales that
to the species' real size in metres — 0.09 m for a Bee Hummingbird, 2.4 m for a
Great Elk, from the same file. Model at whatever size is comfortable and let the
proportions carry the meaning.

**Ground level.** The lowest point of the model becomes the animal's feet. Model
standing on Y=0 if you like, or don't — the importer finds the floor either way.

**Pivots matter.** A box rotates about its element `origin`, which is what
Blockbench's pivot handle sets. A wing whose pivot is at its own centre will
windmill; put the pivot at the shoulder. This is the single most common reason
an imported model animates strangely.

---

## 4. Animations

Name a Blockbench animation after one of the ten states below and it is used
for that state. Matching ignores any `animation.<model>.` prefix Blockbench
writes, is case-insensitive, and accepts the name with a `_`-separated prefix or
suffix — so `walk`, `Walk`, `animation.wren.walk`, and `walk_cycle` all mean
`WALK`.

| State | Accepted clip names | When it plays |
|---|---|---|
| `IDLE` | `idle`, `stand` | standing, sitting, floating |
| `WALK` | `walk`, `move`, `swim` | moving at a normal pace |
| `RUN` | `run`, `sprint`, `flee` | fleeing, covering ground |
| `FLY` | `fly`, `flap`, `glide` | airborne |
| `FORAGE` | `forage`, `eat`, `feed`, `peck`, `graze` | head down at a lure or a berry |
| `ALERT` | `alert`, `look`, `watch` | frozen, deciding whether you are a problem |
| `SLEEP` | `sleep`, `rest`, `roost` | outside its own hours |
| `CALL` | `call`, `sing`, `display` | the moment that gives it away |
| `TAME` | `tame`, `sit`, `perch` | a pet, at home |
| `STRIKE` | `strike`, `attack`, `bite`, `lunge`, `swipe` | swinging at somebody — mutants only |

`STRIKE` is the odd one out: nothing but the three mutants (`wendigo`,
`werewolf`, `mirewraith`) ever enters it, so a clip for it on a wren is simply
never played. It is a full state all the same, with the same fallback rule as
the other nine, because the three of them are ordinary imported models in every
other respect.

**What is read from a clip:** its `length`, whether it loops, and per-bone
**`rotation`** and **`position`** keyframes with their interpolation mode
(`linear`, or smoothed). Rotation is in degrees, as Blockbench writes it.

**What is not read:** `scale` channels, molang expressions in keyframe values,
and sound/particle effect channels. A clip containing them still loads; those
channels are ignored.

Keyframes marked for smooth interpolation are eased with a smoothstep rather
than a true Catmull-Rom. It matches at the keys and has the same flat tangent
there, and unlike a real spline through hand-set keys it cannot overshoot into a
limb bending backwards.

### Partial models are fine

**A state with no clip falls back to the procedural animation**, per joint. A
model that supplies only `idle` and `walk` is a working animal with the other
eight states still moving; adding `fly` later improves it rather than completing
a prerequisite. Ship one clip at a time.

---

## 5. What this renderer cannot draw

This is a low-polygon world built out of boxes, and the importer reads boxes.

- **Blockbench's mesh mode** (free-form polygons) is skipped. A model that is
  entirely mesh has no elements and keeps its placeholder.
- **Per-face texture assignment** beyond the first texture is ignored.
- Element `rotation` and `inflate` **are** read.

If you want curves, get them the way the rest of this world does — several small
boxes, and let the flat shading do the work.

---

## 6. Checklist, and how to tell it worked

1. Model the animal facing **+Z**, on the ground plane.
2. Put every box inside a **named group**, per §2. Nest detail under the part it
   belongs to.
3. Set **pivots at the joints**, not at box centres.
4. Paint it, or leave it and let the species' generated skin colour it.
5. Add animations named per §4. `idle` and `walk` are enough to start.
6. **File → Export → Blockbench Model**, and save it into this folder as
   `<species key>.bbmodel` or `<family key>.bbmodel`.

**To check it loaded:** open the Field Guide (`G` on a walk, or from the lobby)
and turn to the species. A page whose model came from a file says

> **Blockbench model loaded**

under the animal's measurements. The portrait beside it is the real model, three
quarters on — if the shape on that page is your shape, it is in.

**If it did not load,** the reason was printed to stderr when the game tried:

```
watch: could not load model watch/models/songbird_finch_banded.bbmodel (no boxes in it) — keeping the placeholder
```

The usual causes, in the order they actually happen: the file is named for a
species key that does not exist (check the spelling against the guide), the
model is mesh rather than boxes, or the folder is not the one the game is
reading — remember the jar's neighbour wins over the classpath.

---

## 7. Skins, separately from models

You do not need a model to change how an animal looks. A texture pack supplying

- `watch/animal/<species key>` replaces one species' 64×64 skin, and
- `watch/animal/<family key>` replaces all 49 in a family,

which recolours both the world and the guide, since a box takes its colour from
the average of its region on that sheet. Terrain works the same way through
`watch/terrain/<material>`. The engine ships no image files; everything you see
before you add any is drawn at runtime.
