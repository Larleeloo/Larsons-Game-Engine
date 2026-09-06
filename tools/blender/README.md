# Making a cosmetic in Blender

A walkthrough for one piece, start to finish. It assumes you can drive Blender
and have never touched this game's importer.

The **contract** is §16 of
[`src/main/resources/watch/models/README.md`](../../src/main/resources/watch/models/README.md);
where this file and that one disagree, that one is right. This is the version
with the clicks in it.

---

## 0. The three facts that matter

Everything else is detail.

**1. Blender's coordinates are the game's coordinates.** A point you model at
Blender `(x, y, z)` arrives in the game at `(x, y, z)` on a walker standing at
the origin. There is no axis arithmetic for you to get wrong — the
Blender → glTF → importer chain composes to the identity, and a test pins it.

```
+Z  up
−Y  the way the figure faces and walks     (Blender's Front view, numpad 1)
+X  one side of the figure — and the one `_l` bones go on, see step 4
```

**2. You are making a garment, not a figure.** A cosmetic is authored *on* a
body: you build the reference walker, fit the piece to it, rig the piece to the
walker's bones, then delete the body and export the garment. That is what makes
a cape follow the shoulders it hangs off.

**3. It is not measured or rescaled.** Every other model in this game is
normalised to a height and stood on the floor. A cosmetic is not: the size you
model at and the height you put it at are both kept exactly. Model in metres, at
life size, where the thing actually sits.

---

## 1. Build the reference walker

From a clone of this repository:

```
Blender → Scripting tab → Open → tools/blender/cosmetic_reference.py → Run Script
```

or, to start from nothing:

```bash
blender --python tools/blender/cosmetic_reference.py
```

You get a collection called **REFERENCE** containing the walker as the boxes the
game actually draws, and an armature whose bones are already at the joints those
boxes pivot about. The boxes are throwaway — you delete them at step 6. The
armature ships with your piece if you rig to it.

If you would rather build it by hand, the landmark table is in §16. The script
exists because typing thirteen numbers is thirteen chances to mistype one, and a
reference figure that is two centimetres wrong is a wardrobe that is two
centimetres wrong for ever.

> The reference is the *box* walker, which is what the game draws today. If a
> modelled ranger or a different figure ever replaces it, the clothes still fit:
> they are rigged to the bones, and the bones are where they are.

---

## 2. Model the piece, in place

Turn on **Front orthographic** (numpad 1) and work against the body.

* A **hat** sits on the crown of the one the figure is already wearing — that is
  at Z 1.95, and anything on the head slot goes *over* it. Look at
  `REF_hat_brim` and `REF_hat_crown`: those are the boxes you are covering.
* A **cape** hangs off the shoulders (Z 1.45, ±0.20 across) and down the back.
  The chest's back face is at **Y +0.22**, so a panel goes at about **Y +0.25**
  — behind the body, not through it.
* **Boots** go round `REF_boot_l` / `REF_boot_r`; a **scarf** at the neck,
  Z 1.59; **mittens** over `REF_hand_l` / `REF_hand_r`, which are 0.11 m cubes.

Keep it in the house style: **flat-shaded, chunky, low-polygon**. Everything in
this world is a slightly-rounded box. No subdivision, no smooth shading, no
bevels with more than one segment — a subdivided sphere costs three hundred
triangles to look exactly like a faceted one from six metres away.

For the density of detail to aim at, read the boxed version of the piece you are
replacing: every one of the eighteen is written out box by box in
[`CosmeticModel.java`](../../src/main/java/com/larsons/engine/watch/render/CosmeticModel.java).
Take its colours from
[`Cosmetics.java`](../../src/main/java/com/larsons/engine/watch/Cosmetics.java).

**Budget: about 250 triangles.** Six pieces can be on one person and eight people
can be in one clearing, so a 900-triangle cape is 43,000 triangles of coat in a
wood.

---

## 3. Paint it with materials, not textures

**Textures are not read at all.** Every triangle in this game samples one tile of
the world atlas and takes its colour from the vertex.

Give the piece six or eight materials — `wool`, `trim`, `leather`, `brass` — and
assign faces to them. Each one: **Principled BSDF → Base Color**, as a flat
value, **not** a texture node.

Type the hex you want. **Colour space is handled for you** — glTF stores linear
and the importer converts back, so `A8482F` in the picker is `A8482F` in the
game. If you have compensated for this by hand before, don't: you will get it
twice.

Do not bake shading in. Lighting is added per triangle from its own normal, so a
face painted dark to fake a shadow is dark on the sunny side too.

---

## 4. Rig it

**Build the piece in separate, slightly overlapping parts, one per bone** — the
way a wooden artist's mannequin is built, not one continuous skin. The importer
assigns each *triangle* to the single bone with the most weight across its three
corners and moves it rigidly, so weight painting is wasted effort here.
Overlapping the parts at each joint is what stops daylight showing through when a
limb bends.

Then parent to the reference armature:

1. select your piece(s), then shift-select `REFERENCE_rig`;
2. **Ctrl+P → With Automatic Weights** (or **→ Bone**, having picked the bone in
   Pose mode). Either works: whatever the weights say, the dominant bone wins.

Which bone:

| Piece | Bone |
|---|---|
| hat, hood, veil, spectacles, goggles | `head` |
| scarf, collar, lanyard, cape, cloak, bedroll | `spine` |
| mittens, gloves | `hand_l` and `hand_r` — one part each |
| gaiters, waders | `foot_l` and `foot_r` — one part each |

**`_l` goes on `+X`.** Not because that is anybody's anatomy — the engine's own
two halves disagree about which side it calls right — but because it is what
keeps a modelled gaiter swinging with the boot inside it: the boxes swing the
`+X` leg on `sin(phase)` and the importer's fallback swings `left` on
`sin(phase)`. `CosmeticsTest.aModelledPieceWalksInStepWithTheBoxesUnderIt` pins
it. If a finished piece looks a beat out, swapping the two names is the whole of
the fix.

Anything you parent to `head` follows the head — which nods a little on the walk
and the idle, and takes whatever your own clip gives it.

> The **look-at** you may have read about in §10 of the models README is the
> keeper's and the ranger's, not a player's. A walker does not turn their head
> toward anybody, so neither does their hat. If that ever changes, a piece
> already rigged to `head` gets it for nothing.

A piece with **no armature at all still works**: each object becomes its own
bone, named after the object, and the same name table applies. So an object
called `cape_spine` is bound correctly with no rigging at all. That is the
one-minute version if you are just trying the pipeline out.

---

## 5. Animate it (optional, and the part worth doing)

Name your actions exactly:

| Action | Plays when |
|---|---|
| `idle` | standing still |
| `walk` | walking |
| `run` | sprinting |

Three, because three is what a person's legs do. **A clip is driven by the
wearer's own gait clock**, so a cloak's `walk` lands in step with the legs
underneath it by construction — you do not have to match a frame rate to
anything.

A piece with **no animation at all still moves**: it swings with the bone it is
rigged to, posed by the game's procedural humanoid table. Ship `walk` first if
you ship one.

**Bake to keyframes on the bones before exporting.** No IK, no constraints, no
shape keys — none of them are read. `LINEAR` and `STEP` interpolation are read
exactly; `CUBICSPLINE` loads but its tangents are dropped, and a spline through
hand-set keys overshoots, which bends a limb backwards.

---

## 6. Export

1. **Delete the reference body** — every object named `REF_*`. It is a stand-in
   for the player and must not ship inside a hat.

   **Keep `REFERENCE_rig` if you parented to it.** A skinned mesh needs its
   armature in the file or the skinning is lost, and the spare bones cost
   nothing: a bone with no geometry on it emits no triangles. Export the rig and
   your piece together.

   If you took the no-armature route (step 4's last paragraph, objects named for
   their bone), delete the whole REFERENCE collection — there is nothing to keep.

2. Select everything, **Object → Apply → All Transforms** (`Ctrl+A`). A mirrored
   object — one with a negative scale — exports inside-out, because a negative
   scale flips which way its triangles wind.

3. **Triangulate.** Add a Triangulate modifier, or tick *Triangulated Mesh* on
   export. Faces are otherwise fanned about their first corner, which is right
   for a convex polygon and wrong for a concave one.

4. **File → Export → glTF 2.0 (.glb/.gltf)**

   | Setting | Value |
   |---|---|
   | Format | **glTF Binary (.glb)** |
   | Include → Limit to | **Selected Objects** (or select everything) |
   | Transform → **+Y Up** | **on** (the default) |
   | Data → Mesh → **Apply Modifiers** | on |
   | Data → **Materials** | Export |
   | Animation | **on**, if you made any |

5. Save it as:

   ```
   src/main/resources/watch/models/cosmetics/<piece key>.glb
   ```

   The eighteen keys:

   ```
   wool_mittens      knitted_beanie   canvas_gaiters   wool_scarf
   rolled_bedroll    wire_spectacles  feathered_band   glass_lanyard
   leather_gloves    straw_boater     snow_goggles     oilskin_hood
   river_waders      moth_veil        fur_collar       oilskin_cape
   antler_circlet    heron_cloak
   ```

---

## 7. Check it

```bash
./gradlew :test --tests '*ModelImportTest*' --tests '*CosmeticsTest*'
./gradlew run
```

In the game: walk to a trading post, press <kbd>E</kbd>, press <kbd>←</kbd> for
the clothes rail, buy the piece, then <kbd>F5</kbd> for third person.

**If it did not load**, one line went to stderr saying why and the boxes were
drawn instead:

```
watch: could not load model watch/models/cosmetics/heron_cloak.glb (no triangles in it) — keeping the fallback
```

The causes, in the order they actually happen:

| It looks like | It is |
|---|---|
| the boxes are still there, and stderr said nothing | the filename does not match a key, or the folder is not the one being read — a `watch/models` folder **next to the jar** wins over the classpath |
| the boxes are still there, and stderr said why | a `.gltf` whose `.bin` was not beside it, a truncated file, or nothing selected on export. Use `.glb` |
| it is lying at their feet | you modelled it at the world origin instead of **in place** on the reference figure. A cosmetic is never moved for you |
| it is inside-out | a mirrored object — a negative scale, transforms not applied (`Ctrl+A`) |
| it is worn back-to-front | you modelled it facing `+Y`. Blender's **Front** view, numpad 1, looks along `+Y` at a figure facing `−Y` |
| it is worn at right angles | you modelled it facing `+X` or `−X` |
| it is enormous or tiny | you modelled in centimetres. A cosmetic is **not** rescaled — metres |
| the colours are washed out, or too dark | you compensated for colour space by hand. Don't; type the hex you want |
| the walk is a beat out | swap `_l` and `_r`. See step 4 |
| it does not move at all | no clip and nothing rigged: an unparented object binds by its own *name*, so call it `cape_spine` rather than `Cube.001` |

---

## What a modelled piece deliberately does not do

Worth knowing before you spend an evening on a cape:

* **A swimmer and a rower keep the boxes.** Those poses are numbers no glTF clip
  knows — a spine laid along the way somebody is diving, a body folded onto a
  thwart — so a modelled piece falls back rather than standing bolt upright in
  the middle of a lake. Your hat changes shape when you dive.
* **Your own hands in first person keep the boxes too**, for the same reason: the
  view model is built in the camera's frame rather than the world's.
* **It is not recoloured.** The two pieces the game tints to the wearer's own
  coat — the oilskin hood and the cape — stop being tinted the moment they are
  modelled. If you want a cape that still reads as *that player's* across a
  valley, leave some of the coat showing.

---

## Committing it

A `.glb` is binary and will not diff, so the commit message is the only record of
what changed:

```bash
git add src/main/resources/watch/models/cosmetics/heron_cloak.glb
git commit -m "Heron cloak: modelled in Blender, walk clip"
git push -u origin <your-branch>
```

Once it is pushed, a cloud session can run the import tests against the real file
and report what the importer made of it — triangle count, which bones bound to
which joints, which clips it found, and whether anything was skipped.

**Blender itself cannot be driven from a cloud session** — the MCP server is a
socket on your machine and a container has no route to it. See the note at the
foot of [`BLENDER_BRIEF.md`](../../BLENDER_BRIEF.md).
