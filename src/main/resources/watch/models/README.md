# Models — what goes in this folder

Everything you can see in this game is generated. The **1323** species each have
a model assembled out of boxes from their build, painted from a 64×64 skin sheet
drawn from their own three colours, animated by hand-written procedural poses;
the people are the same idea with more parts on them. They are placeholders, and
they are meant to be replaced.

This folder is where the real art goes. **Drop a file in here and it is used
instead**, with no code change, no rebuild, and no restart beyond the one that
reloads the folder. Nothing in here can stop the game starting: a file that is
missing, malformed, or built out of things this renderer cannot draw leaves that
subject with its placeholder and prints one line to stderr.

You can do this one model at a time, and one *animation* at a time. That is
deliberate — see [Partial models are fine](#partial-models-are-fine).

## Two routes in

| | **Part one: boxes** | **Part two: meshes** |
|---|---|---|
| Format | `.bbmodel`, `.json` | `.glb`, `.gltf`, `.obj` |
| Tool | Blockbench | Blender, or anything |
| Geometry | axis-aligned cuboids | any triangles |
| Painted by | the species' generated skin | the file's own materials |
| Animation | Blockbench clips | glTF clips (`.obj` is static) |
| Good for | animals, in bulk | characters, props, one-offs |

Both end up in the same mesh, lit by the same flat shading, posed by the same
ten animation states, and falling back to the same procedural animation for a
state you have not got to. Neither is the "real" one.

**Which to use.** If you are dressing forty-nine species of songbird from one
file and want each of them to keep its own colours, you want boxes — a skin
sheet is the only thing that can recolour one model 49 ways. If you are making
*one* thing that needs a shape boxes cannot make, you want a mesh.

Part one is §1–§7 below. Part two starts at [§8](#8-meshes-gltf-and-obj).

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

---

# Part two — meshes

## 8. Meshes: glTF and OBJ

Everything above describes boxes. This part describes the other route: free-form
triangles out of a `.glb`, `.gltf` or `.obj`, which is how you get a shape that
is not a pile of cuboids, and how you animate something with a real skeleton.

**The formats, in the order they are tried:**

| Extension | Carries | Use it for |
|---|---|---|
| `.glb` | geometry, skeleton, animation, materials — all in one file | **anything animated.** Start here. |
| `.gltf` | the same, but split across a `.gltf` + `.bin` (+ nothing else useful) | only if your tool will not write `.glb` |
| `.obj` | geometry and material colours. **No animation.** | static props, and quick tests |

Prefer `.glb`. A `.gltf` that has lost the `.bin` next to it is the single most
common way an imported model arrives broken, and this is a folder people commit
to.

### Where the file goes

The same two places, in the same order, as everything else: a `watch/models/`
folder **next to the jar** first, then `watch/models/` **on the classpath**.

| Name | Covers |
|---|---|
| `<species key>.glb` | that one species (see §1 for keys) |
| `<family key>.glb` | all 49 species of that family |
| `characters/ranger.glb` | **the forest ranger** who stands outside every trading post |

A `.bbmodel` under the same name **wins** over a `.glb`. That is on purpose:
adding a mesh beside an existing box model should be a deliberate act — delete
the old one — rather than an accident of which extension sorted first.

---

## 9. Axes, units and where the floor is

**This is the same convention Blockbench uses**, and it is the one you get from
Blender's exporters without changing anything.

| | Blender (viewport) | glTF / OBJ file | Field Guide |
|---|---|---|---|
| X | right | right | **forward** |
| Y | into the screen | **up** | right |
| Z | up | toward you (front) | **up** |

In practice this is two rules:

1. **Model with +Z up** — Blender's default, do nothing.
2. **Face the character down −Y** — that is Blender's *Front* orthographic view
   (numpad 1), so your character is looking at you when you press it.

Export with the default settings (**+Y up** for glTF, **−Z forward / Y up** for
OBJ) and it lands facing the way it walks. Getting this wrong gives you a
model that walks sideways, which is obvious the moment you look at it.

**Units.** Model at whatever size is comfortable. On import the model is measured
and scaled so that one extent is exactly 1, and the game then draws it at the
size the thing actually is:

- **a creature** is scaled by its **longest horizontal extent** — because that
  is what `bodyLength` means, so one file serves a 0.09 m hummingbird and a
  2.4 m elk;
- **a character** is scaled by its **height**, crown to sole, because that is
  what a person's size is.

**The floor.** The model's lowest point becomes ground level. Stand it on Z=0 in
Blender if you like, or don't — the importer finds the floor either way.

**Apply your modifiers, and apply scale.** A mirrored object — one with a
negative scale — comes out inside-out, because a negative scale flips which way
its triangles wind. `Ctrl+A → All Transforms` before exporting.

---

## 10. Bone names, for meshes

Same rule as §2, and for the same reason: **the name of the bone is the entire
binding contract.** Matched case-insensitively on substrings, with `-`, `.` and
spaces folded to `_`, so `arm_l`, `armL`, `Left Arm`, `upper_arm.L` and
`arm-left` all mean the same thing. A bone that matches nothing **inherits its
parent's** joint, and a root that matches nothing is the body.

Creatures use the table in §2 unchanged. **People use this one:**

| Joint | Bone name contains |
|---|---|
| head | `head`, `skull`, `neck`, `face`, `jaw`, `eye`, `hair`, `hat`, `brim` |
| body | `body`, `torso`, `chest`, `spine`, `hip`, `pelvis`, `root`, `coat`, `belt` |
| left / right arm | `arm`, `hand`, `shoulder`, `elbow`, `wrist`, `finger`, `clavicle` |
| left / right leg | `leg`, `foot`, `thigh`, `shin`, `knee`, `ankle`, `boot`, `toe` |
| ears | `ear` |
| back | `tail`, `pack`, `bedroll` |

Side comes from `left`/`right`, or a `_l`/`_r` ending — which is what Blender's
mirror modifier and Rigify both produce, since `.L` and `.R` fold to those.

**Anything on the head should be parented to the head bone**, including the hat
and the hair. That is what makes it turn when the head turns.

### The head follows you

A character's head turns toward whoever is nearest, up to about 66°, on top of
whatever the animation is doing. You get this for free: parent it to a bone
whose name says `head` and it happens. It is the single thing that most makes a
figure read as a person rather than as furniture, so it is worth getting the
neck bone in the right place — at the base of the neck, not in the middle of the
skull.

---

## 11. Rigging: model in parts, not in one skin

**This is the one thing about this renderer that will surprise you.**

A normal game character is one continuous skin, and each vertex is blended
between up to four bones so the shoulder bends smoothly. This game does not do
that. Each **triangle** is assigned to the single bone with the most weight
across its three corners, and moves with that bone rigidly.

That is not a limitation being apologised for, it is the world: every surface
here is one flat colour, so there is nothing for a smooth deformation to deform
*into*. Rigid parts are what this game already looks like.

**What it means for you:** build the model out of separate pieces — an upper
arm, a forearm, a hand — the way a wooden artist's mannequin is built, rather
than sculpting one mesh and weight-painting it. Then:

- You can skip weight painting entirely. Parent each piece to its bone with
  **Ctrl+P → Bone**, or **Ctrl+P → With Automatic Weights** and it will still
  work, because whatever the weights say, the dominant bone wins.
- Overlap the pieces slightly at each joint so no gap opens when it bends. A
  cylinder that ends flush at the elbow will show daylight through it.
- A model with **no armature at all** is fine. Each object becomes its own
  bone, named after the object, and the joint table above still applies.

---

## 12. Colour: materials, not textures

**Textures are not read.** Every triangle in this game samples one tile of the
world atlas and takes its colour from the vertex — so a UV out of your file
would point at whatever happened to be next to that tile.

Colour comes from:

1. the material's **base colour** (Principled BSDF → Base Color, as a flat
   value, not a texture node); multiplied by
2. the mesh's **vertex colours** (`COLOR_0`), if it has any, averaged over each
   triangle.

So: **paint with materials.** Give the model eight or ten materials — coat,
trim, skin, hair, leather, brass — and assign faces to them. That is how every
model already in this game is coloured, and it is why the whole world looks like
it was made by the same hand.

**Colour space is handled for you.** glTF stores colour as linear, which is what
Blender writes: type `3C5240` into the colour picker and the number in the file
is `0.045`, not `0.235`. The importer converts back, so **the hex you type is
the hex you get**, and it will match a colour written into the game's own source
exactly. (If you have compensated for this by hand in the past, don't — you will
get it twice.)

Lighting is added on top per triangle, from its own normal, against a fixed key
direction. You do not need to bake any shading in, and you should not: a face
painted dark to fake a shadow will be dark on the side the sun is on too.

---

## 13. Animation, for meshes

Name a glTF action after one of the ten states in §4 and it plays for that
state. The same matching rules apply: case-insensitive, and a `_`-separated
prefix or suffix is allowed, so `walk`, `Walk`, `walk_cycle` and
`ranger_walk` all mean `WALK`.

**What is read:** the clip's length, and per-bone **translation**, **rotation**
and **scale** tracks, with `LINEAR` and `STEP` interpolation. `CUBICSPLINE`
loads, but its tangents are dropped and it is played as linear — a spline
through hand-set keys overshoots, and an overshooting limb bends backwards.

**What is not read:** morph targets (shape keys) and their weight tracks,
constraints and IK — **bake your animation to keyframes on the bones before
exporting**, which is what glTF export does by default anyway — cameras, lights,
and drivers.

**Partial models are fine, again.** A state with no clip is animated by the
procedural fallback instead: for a person that is a breathing, weight-shifting
idle and an opposite-arm-and-leg walk. Ship `idle` and `walk` and the other
eight states still move. A model with *no* animation at all is a working
character, not a statue.

---

## 14. Budget

This world is drawn by a software rasteriser as well as by a GPU, and triangles
are what it spends its time on.

| | Triangles |
|---|---|
| A generated animal | 60–200 |
| The keeper, the most detailed figure in the game | ~700 |
| **The procedural ranger you are replacing** | **616** |
| A sensible ceiling for a character | ~1200 |
| A sensible ceiling for a prop | ~400 |

**Export triangulated.** Add a Triangulate modifier or tick *Triangulated Mesh*
on export. Faces are otherwise fanned about their first corner, which is right
for a convex polygon and wrong for a concave one.

No subdivision, no bevels with more than one segment, no smooth shading — it is
all flat, and a subdivided sphere costs three hundred triangles to look exactly
like a faceted one from six metres away.

---

## 15. Checklist for a mesh

1. Model with **+Z up**, facing **−Y** (Blender's Front view), over the origin.
2. Build it in **separate overlapping pieces**, one per bone.
3. Name the bones (or the objects) per §10. Parent hats and hair to the head.
4. Paint it with **materials**, not textures. Eight or ten flat colours.
5. **Apply all transforms.** `Ctrl+A → All Transforms`.
6. **Triangulate**, and check the count against §14.
7. Animate if you want to; name actions per §4. `idle` and `walk` are plenty.
8. **File → Export → glTF 2.0**, format **glTF Binary (.glb)**, with
   *+Y Up*, *Apply Modifiers* and *Include → Animation* on.
9. Save it into this folder under the name from §8.

**To check it loaded:** the ranger is at any trading post — walk up to one. For
an animal, open the Field Guide (`G`) and turn to the species.

**If it did not load,** the reason was printed to stderr when the game tried:

```
watch: could not load model watch/models/characters/ranger.glb (no triangles in it) — keeping the fallback
```

The usual causes, in the order they actually happen: the file was exported with
no mesh selected; it is a `.gltf` whose `.bin` was not committed alongside it;
the name does not match §8; or the folder is not the one the game is reading —
remember the jar's neighbour wins over the classpath.
