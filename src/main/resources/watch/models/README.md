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
twelve animation states, and falling back to the same procedural animation for a
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
case-insensitively on substrings, after `-`, `.`, spaces **and camel-case humps**
are all folded to `_` — so `left_wing`, `leftWing`, `wing_l`, `wing.L`,
`Wing Left` and `wing-left` all mean the same thing.

| Joint | Bone name contains | Notes |
|---|---|---|
| `HEAD` | `head`, `skull`, `neck`, `beak`, `bill`, `snout`, `muzzle`, `jaw`, `eye` | |
| `BODY` | `body`, `torso`, `chest`, `spine`, `hip`, `pelvis`, `abdomen`, `root` | also the default |
| `WING_L` / `WING_R` | `wing`, `fin`, `flipper`; also `arm`, `hand`, `clavicle`, `shoulder`, `elbow`, `wrist` | the upper limb pair — a biped's arms live here |
| `LEG_FL` / `LEG_FR` | `leg`, `foot`, `paw`, `talon`, `thigh`, `shin`, `knee`, `ankle`, `toe`, `hoof`, `hock` + `front` or `fore` | |
| `LEG_BL` / `LEG_BR` | the same + `back`, `hind`, or `rear` | |
| `TAIL` | `tail` | |
| `EAR` | `ear`, `antenna` | `ear` must be a whole word — see below |
| `HORN` | `horn`, `antler`, `crest` | |

Two words behave slightly differently from the rest, and both for the same
reason — a short substring catches too much:

- **`ear` is matched as a whole word**, delimited by `_` or an end. Without
  that, `forearm` is an ear (and so is `rear`, and so is `bear`, which is one of
  this game's own family keys). `left_ear`, `earL` and `ear_r` all still work.
- **`claw` matches nothing at all.** A claw is on whatever limb it hangs off —
  a talon is a foot, a wendigo's claws are on its hands — and it is always
  parented under that limb, so the inheritance rule below gets it right where a
  keyword cannot.

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
to model at any particular size: on import the model is scaled so it stands
**exactly as tall as the placeholder it replaces**, and the registry then scales
that to the species' real size in metres — 0.09 m for a Bee Hummingbird, 2.4 m
for a Great Elk, from the same file. Model at whatever size is comfortable and
let the proportions carry the meaning.

**Ground level.** The lowest point of the model becomes the animal's feet. Model
standing on Y=0 if you like, or don't — the importer finds the floor either way.

**Pivots matter.** A box rotates about its element `origin`, which is what
Blockbench's pivot handle sets. A wing whose pivot is at its own centre will
windmill; put the pivot at the shoulder. This is the single most common reason
an imported model animates strangely.

---

## 4. Animations

Name a Blockbench animation after one of the twelve states below and it is used
for that state. Matching ignores any `animation.<model>.` prefix Blockbench
writes, is case-insensitive, and accepts the name with a `_`-separated prefix or
suffix — so `walk`, `Walk`, `animation.wren.walk`, and `walk_cycle` all mean
`WALK`.

| State | Accepted clip names | When it plays |
|---|---|---|
| `IDLE` | `idle`, `stand` | standing, sitting, floating |
| `WALK` | `walk`, `move` | moving at a normal pace |
| `RUN` | `run`, `sprint`, `flee` | fleeing, covering ground |
| `FLY` | `fly`, `flap`, `glide` | airborne |
| `FORAGE` | `forage`, `eat`, `feed`, `peck`, `graze` | head down at a lure or a berry |
| `ALERT` | `alert`, `look`, `watch` | frozen, deciding whether you are a problem |
| `SLEEP` | `sleep`, `rest`, `roost` | outside its own hours |
| `CALL` | `call`, `sing`, `display` | the moment that gives it away |
| `TAME` | `tame`, `sit`, `perch` | a pet, at home |
| `STRIKE` | `strike`, `attack`, `bite`, `lunge`, `swipe` | swinging at somebody — mutants only |
| `SWIM` | `swim`, `stroke`, `paddle` | in the water — **the player only**, see §17 |
| `ROW` | `row`, `oar` | sitting to a pair of oars — **the player only**, see §17 |

`STRIKE` is the odd one out among the animals: nothing but the three mutants
(`wendigo`, `werewolf`, `mirewraith`) ever enters it, so a clip for it on a
wren is simply never played. It is a full state all the same, with the same
fallback rule as the rest, because the three of them are ordinary imported
models in every other respect.

`SWIM` and `ROW` are the player's, and no animal enters either. **`swim` used
to be an alias on `WALK`** — fair enough while swimming was something only an
otter did, and wrong the moment a person had both a walk cycle and a
breaststroke, because one alias cannot name two clips. No model in this
repository shipped a `swim` clip when it moved, so nothing that was working
stopped.

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
| `characters/walker.glb` | **the player** — you, and everybody else walking about. See §17 |
| `cosmetics/<piece key>.glb` | **one thing to wear** — a hat, a cape, a pair of boots. See §16 |

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
and scaled by its **height**, floor to crown, and the game then draws it at the
size the thing actually is:

- **a creature** comes out exactly as tall as the placeholder it replaces, so
  one file serves a 0.09 m hummingbird and a 2.4 m elk;
- **a character** comes out its own height in metres — 1.78 m for the ranger.

Height, and not the longest horizontal extent. Measuring nose to tail is a fair
description of a heron and a nonsense for anything that stands up: the first
wendigo to arrive was normalised on its antler spread and came out ten metres
tall against a seven-metre placeholder, and two and a half times as wide.

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
an animal, open the Field Guide (`G`) and turn to the species. For a cosmetic,
buy it off the rail at any post and look at yourself in third person (`F5`).

**If it did not load,** the reason was printed to stderr when the game tried:

```
watch: could not load model watch/models/characters/ranger.glb (no triangles in it) — keeping the fallback
```

The usual causes, in the order they actually happen: the file was exported with
no mesh selected; it is a `.gltf` whose `.bin` was not committed alongside it;
the name does not match §8; or the folder is not the one the game is reading —
remember the jar's neighbour wins over the classpath.

---

## 16. Cosmetics — clothes for the player

Everything the player can buy off a trading post's clothes rail is a pile of
boxes today, exactly like the animals and the ranger, and exactly as replaceable.
Drop a file in and it is worn instead:

```
watch/models/cosmetics/<piece key>.glb
```

The keys are the ones in `Cosmetics.java`, and there are eighteen of them:

```
wool_mittens      knitted_beanie   canvas_gaiters   wool_scarf
rolled_bedroll    wire_spectacles  feathered_band   glass_lanyard
leather_gloves    straw_boater     snow_goggles     oilskin_hood
river_waders      moth_veil        fur_collar       oilskin_cape
antler_circlet    heron_cloak
```

One file replaces one piece. Everything else on the rail keeps its boxes, so you
can do these one at a time — and the game will happily draw a modelled hat over
a boxed scarf.

**There is a walkthrough with the clicks in it**, plus a Blender script that
builds the reference figure and its armature for you, in `tools/blender/` at the
root of this repository. This section is the contract; that is the tutorial.

### A cosmetic is a rigged figure, not a prop

**This is the difference from everything else in this folder, and the only thing
here worth reading twice.** A ranger is a person. An animal is an animal. A
cosmetic is *the clothes off a person with the person deleted* — so you model it
the way you would model a coat: on a body, in place, rigged to that body's
skeleton.

1. **Stand a reference figure at the origin** — feet on `Z = 0`, facing **−Y**
   (Blender's Front view), 1.95 m to the top of its hat. Run
   `tools/blender/cosmetic_reference.py` and you have one, armature included;
   the table below is what it builds, landmark by landmark. §9's axes apply unchanged, and they are what
   tells a cape from a bib: **−Y is the front**, so a cape goes at **+Y**, behind
   the chest's back face (0.22 m from its middle).
2. **Model your piece where it sits on them.** A hat goes at head height. A cape
   hangs off the shoulders and down the back. Boots go round the ankles.
3. **Rig it to bones with the §10 names** — `head`, `spine`, `arm_l`, `arm_r`,
   `leg_l`, `leg_r`. A cape is on `spine`; mittens are on `hand_l` and `hand_r`;
   a hat is on `head`.
4. **Delete the reference figure** and export just your piece.

That is what makes a piece follow the joint it is worn on, and it is why a cape
can hang off the shoulders *and* reach the knees — a single anchor point could
not describe that.

### Where the reference figure's parts are

These are the walker's own numbers (`WalkerModel`), in metres, for a standing
figure with their feet on `Z = 0` and their arms at rest.
`CosmeticsTest.theReferenceFigureIsTheOneThisFolderDescribes` holds every row of
this table against the real mesh, so it cannot drift away from the game without
a test going red.

| Landmark | Z |
|---|---|
| sole of boot | 0.00 |
| boot centre | 0.05 |
| knee | 0.45 |
| hand centre (arms at rest) | 0.80 |
| hip | 0.87 |
| chest centre | 1.24 |
| pack (satchel) centre | 1.28 |
| shoulder | 1.45 |
| neck / collar | 1.59 |
| head centre | 1.70 |
| top of the head | 1.82 |
| brim of the default hat | 1.85 |
| **top of the default hat** | **1.95** |

**`WalkerModel.HEIGHT` is 1.78 and the figure is 1.95 m tall.** Those are not in
conflict: 1.78 is the nominal height every proportion above is a fraction of, and
the hat stands above it. Build against 1.95 — it is what you would measure.

- Shoulders are **±0.20** either side of centre, hips **±0.09**, boots **±0.09**.
- The head is a **0.23 m cube** and a hand is an **0.11 m cube**.
- The default hat's brim is **0.54 m across** — wider than the shoulders, and the
  widest thing on the figure.
- The chest is **0.29 wide and 0.44 deep**. Deeper than it looks, which is why a
  scarf tail written "just in front of the neck" ends up inside somebody.

Model at **1.0 = 1 metre**. Unlike everything else in this folder a cosmetic is
**not measured and rescaled**: the size you model at and the height you put it at
are both answers rather than accidents, so a hat modelled at 1.85 m arrives at
1.85 m. (It does scale with the wearer — a crouching walker's cape crouches — but
not with your file.)

### Animation

**This is the part worth doing.** Name your Blender actions after the states in
§4 and they play when the wearer does that thing:

| Action | Plays when |
|---|---|
| `idle` | standing still |
| `walk` | walking |
| `run` | sprinting |

Three, because three is what a person's legs do. Everything else falls back to
the procedural humanoid pose, so a cloak with **no animation at all still moves**
— it swings with the spine it is rigged to. Ship `walk` first if you ship one:
that is the state a cloak most wants an authored clip for.

The clip is driven by the **wearer's gait clock**, so a cloak's `walk` is in step
with the legs underneath it by construction. Everything in §13 applies unchanged:
bake to keyframes on the bones, no IK, no shape keys, `LINEAR` or `STEP`.

### Budget, and the two limits

| | Triangles |
|---|---|
| A boxed piece | 8–60 |
| A sensible ceiling for one piece | ~250 |
| …for a whole wardrobe on one walker | ~800 |

Six of these can be on one person at once and eight people can be in one wood, so
a 900-triangle cape is 43,000 triangles of coat in a clearing. Keep them small.

Three things a modelled piece does **not** do, all on purpose:

- **A swimmer and a rower keep the boxes.** Those two are posed by numbers no
  glTF clip knows — a spine laid along the way somebody is diving, a body folded
  onto a thwart — so a modelled piece falls back rather than standing bolt
  upright in the middle of a lake. Your hat changes shape when you dive. That is
  the honest version of the alternatives.
- **Your own hands in first person keep the boxes too**, for the same reason: the
  view model is built in the camera's frame rather than the world's.
- **It is not recoloured.** An animal's boxes are painted from its species' skin
  sheet so that one file can dress forty-nine; a cosmetic is one thing and wears
  the colours you gave its materials. The two pieces the game tints to the
  wearer's own coat — the oilskin hood and the cape — stop being tinted the
  moment they are modelled, so if you want a cape that still reads as *that
  player's* across a valley, leave some of the coat showing.

### Checklist

1. Reference figure at the origin, facing **−Y**, feet on `Z = 0`, hat at 1.95.
2. Model the piece **in place** on it, in metres.
3. Rig to §10 bone names. Cape → `spine`; hat → `head`; mittens →
   `hand_l`/`hand_r`.
4. Materials, not textures (§12). Flat colours.
5. `Ctrl+A → All Transforms`. Triangulate.
6. Animate `walk` if you animate anything.
7. Export **glTF Binary (.glb)**, *+Y Up*, *Apply Modifiers*, *Animation* on.
8. Save to `watch/models/cosmetics/<piece key>.glb`.

---

## 17. The player

`characters/walker.glb` replaces the figure in §5's boxes with your own — in
third person, for every other player in the party, and for anybody you pass in
a clearing. It is authored exactly like the ranger in §15: **+Z up, facing −Y,
feet on `Z = 0`**, materials rather than textures, separate overlapping pieces
one per bone, §10's bone names, under about 1200 triangles.

Two things are its own.

### Ship five clips, not one

`idle`, `walk`, `run`, `swim` and `row` — every state a walker is ever drawn
in. Everywhere else in this folder a partial model is fine and the procedural
table poses the rest; here it is fine only up to a point, and that point is
the size of the angle.

The fallback poses each **piece** about its own bone's pivot rather than
composing down the hierarchy. At an idle's 0.03 radians nothing shows. At a
run's 0.67 the hand rotates about the wrist it is still standing at while the
arm swings away from the shoulder, and the two come apart by a third of a
metre. Those three states are the only ones a walker is ever drawn in, so
three clips means the fallback never runs on this figure at all.

The `walk`, `run`, `swim` and `row` clips ride the **gait clock**, so the feet
land with the ground going past rather than with a frame rate — the same clock
a cosmetic's `walk` is driven by, which is what keeps a modelled cloak swinging
in step with the legs under it. `idle` runs on the world clock.

### Swimming: author it standing up

**`swim` is the one clip that looks wrong in Blender and right in the game.**
A swimmer's body angle runs continuously from upright, treading water, through
flat on the surface, to head-down in a dive, and which of those it is depends
on where the player is looking. No keyframe can hold that, so the clip supplies
only the stroke — the arms sweeping, the knees drawing up — with the figure
standing upright, and the engine tips the whole body at draw time about the
hips (`SceneModel.Lean`, at 0.47 of the height).

Read every pose in it as though the figure were already face-down: arms
overhead is the reach out in front, knees to the chest is the frog kick drawing
up, and the head tipping back is the breath.

At a tip of nothing this is the standing figure exactly, which is the point:
somebody wading out of their depth tips over into a swim rather than cutting to
a different model.

### Rowing: author it against the boat

`row` is the one clip measured against furniture rather than anatomy. The
figure is drawn from the **floorboards** — `BoatModel.floorZ` — and folded onto
a thwart `DEPTH * 0.76` above them, about 350 mm, with the hip joint a further
110 mm up. Keep the hips over the model's own origin and reach the feet forward
from there; the engine seats the whole figure with one offset along the boat.

Two things that bite:

- **A leg needs a knee.** At 65° the thigh leaves the knee 265 mm above the
  floorboards, which a 300 mm shin can just reach down. Any flatter and the
  feet hang in the bilge. A leg rigged as one rigid bone cannot sit down.
- **Pin the hips.** If the spine bone pivots at the waist rather than at the
  hips — and it should, for everything else — then leaning back swings the hips
  forward off the seat and takes the braced feet with them. Undo it on the root
  or the boots skate over the boards, 160 mm a stroke.

### What stays boxes

- **Your own hands in first person**, which are built in the camera's frame.
- **A jump**, which is drawn in whichever locomotion clip the walker's speed
  says, because there is no airborne state to name one with.
- **A crouch** is the model scaled to `CROUCH_HEIGHT`, which is what happens to
  the boxes too: a smaller person rather than a folded one.
- **A swimmer's effort.** The boxes scull at a third of a stroke when somebody
  is holding station and swim a full one when they are going somewhere; a clip
  is a clip, so a modelled swimmer always swims. Worth knowing, not worth a
  second clip.

### Cosmetics are fitted to the boxes, not to your model

Everything on the rail was authored against the reference figure in §16 —
shoulders at Z 1.45, head at 1.70, hat brim at 1.85 — and a worn piece is
`AS_PLACED`: never measured, never rescaled. A model with different landmarks
wears them at the box walker's heights, not at its own. Either match those
landmarks or re-author the eighteen pieces to yours.

### Testing against it

`SceneModels.setSources` is how a test says which figure it means. A test of
the boxes takes `NONE`; a test with a `.glb` fixture of its own takes
`FOLDER_ONLY`, so the classpath does not hand it this file as well. Without
that, committing a walker silently changes what half the walker tests are
measuring.

---
