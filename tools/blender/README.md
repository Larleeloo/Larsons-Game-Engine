# Making a cosmetic in Blender

A walkthrough for one piece, start to finish. It assumes you can drive Blender
and have never touched this game's importer.

The **contract** is §16 of
[`src/main/resources/watch/models/README.md`](../../src/main/resources/watch/models/README.md);
where this file and that one disagree, that one is right. This is the version
with the clicks in it.

## What is already in this folder

Everything below is the hand-made route. Before you start it, know that the
whole wardrobe is already generated from a table, and that adding to it may be a
matter of editing one:

| Script | Builds |
|---|---|
| `bodies.py` | the **two player bodies** — a head, bare limbs, a vest and shorts. Writes both `characters/*.glb` |
| `cosmetics.py` | **all twenty-eight pieces, for both figures.** Writes fifty-six files |
| `figures.py` | the measurements those two are built from — every landmark, once |
| `gait.py` | the five clips a walker is drawn in, which now animate the clothes as well |
| `kit.py` | the shapes it is all built out of — boxes, drums, tapers, struts |
| `ranger.py` | the **ranger** outside the trading post, who is an NPC and keeps his clothes on |
| `cosmetic_reference.py` | the *boxed* reference figure, for the figure-agnostic folder |

```bash
blender --background --python tools/blender/bodies.py
blender --background --python tools/blender/cosmetics.py
blender --background --python tools/blender/cosmetics.py -- wayfarer heron_cloak
```

**A body is not an outfit.** The coat, the trousers, the boots, the pack, the hat
and the hair are worn pieces — `cosmetics.py` builds them — because a player can
take them off. What `bodies.py` makes is what is left underneath.

Redoing one piece by hand is completely legitimate — that is what the rest of
this file is for. Changing a *proportion* is not: put it in `figures.py` and
re-run, or the two figures and their two wardrobes will disagree.

---

## 0. The four facts that matter

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
body: you build the figure, fit the piece to it, rig the piece to that figure's
bones, then delete the body and export the garment. That is what makes a cape
follow the shoulders it hangs off.

**3. It is not measured or rescaled.** Every other model in this game is
normalised to a height and stood on the floor. A cosmetic is not: the size you
model at and the height you put it at are both kept exactly. Model in metres, at
life size, where the thing actually sits.

**4. Therefore a garment belongs to a body.** There are two figures you can walk
as, they are different shapes, and a piece is filed under the one it was cut
for:

```
src/main/resources/watch/models/cosmetics/walker/<key>.glb
src/main/resources/watch/models/cosmetics/wayfarer/<key>.glb
src/main/resources/watch/models/cosmetics/<key>.glb          ← fits anybody
```

The first two are used only while that figure's own body is the one being drawn.
The third is the folder that existed before there were two figures, is used
whatever is being drawn, and is cut to the **boxed** reference figure instead —
which is 1.95 m to the top of its hat where the modelled ones are 1.78. Getting
those two tables mixed up is the one mistake here that puts a hat 170 mm over
somebody's head, so decide which folder you are filling before you model
anything.

---

## 1. Build the figure you are cutting for

**For `cosmetics/walker/` or `cosmetics/wayfarer/`** — the modelled bodies:

```bash
blender --python tools/blender/bodies.py -- walker
blender --python tools/blender/bodies.py -- wayfarer
```

Either leaves a collection with the figure in it, rigged, standing on `Z = 0`
facing `−Y`, 1.78 m to the crown. That is the body you fit the garment to — and
it is a body, in a vest and shorts, so if the piece you are making is a coat you
are fitting it to skin rather than to other clothes. Add the standard kit as
well when that matters:

```bash
blender --python tools/blender/cosmetics.py -- walker field_coat field_trousers
```

**For `cosmetics/`** — the figure-agnostic folder:

```
Blender → Scripting tab → Open → tools/blender/cosmetic_reference.py → Run Script
```

You get a collection called **REFERENCE** containing the walker as the *boxes*
the game draws when there is no character model at all, and an armature whose
bones are at the joints those boxes pivot about. The boxes are throwaway — you
delete them at step 6. The armature ships with your piece if you rig to it.

If you would rather build a figure by hand, both landmark tables are in §16. The
scripts exist because typing thirteen numbers is thirteen chances to mistype one,
and a reference figure that is two centimetres wrong is a wardrobe that is two
centimetres wrong for ever.

---

## 2. Model the piece, in place

Turn on **Front orthographic** (numpad 1) and work against the body. The numbers
below are the **modelled walker's** — for the wayfarer, and for the boxed
reference figure, read the other columns of §16's tables.

* A **hat** sits on the crown of the one the figure is already wearing — that is
  at Z 1.59 for the brim and 1.78 for the top of the crown, and anything on the
  head slot goes *over* it. A round piece needs a radius of **0.238** to cover
  the walker's square crown, because that is where its corners are.
* A **cape** hangs off the shoulders (Z 1.18, ±0.205 across) and down the back.
  The pack's back face is at **Y +0.33**, so a panel goes at about **Y +0.37** —
  behind the pack, not through it.
* **Boots** go round the feet at Z 0–0.25; a **scarf** at the neck, Z 1.27;
  **mittens** over the hands, which hang at (±0.205, −0.06, 0.59).

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

Then parent to the figure's armature — `REFERENCE_rig`, `ranger_rig` or
`wayfarer_rig`, whichever you built at step 1:

1. select your piece(s), then shift-select the rig;
2. **Ctrl+P → With Automatic Weights** (or **→ Bone**, having picked the bone in
   Pose mode). Either works: whatever the weights say, the dominant bone wins.

All three rigs carry the same fifteen bones under the same names, which is on
purpose: a garment rigged to one binds correctly against any of them, and only
its *placement* is figure-specific.

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

## 5. Animate it (you almost certainly should not)

**The body carries the clothes.** The figure plays its own clip, the engine asks
it where each joint went, and your garment is moved along with the bone it is
rigged to — so a hat bobs, a boot walks, a coat sits down in a boat, and you do
not have to key a single frame. Every one of the fifty-six pieces this game ships
has no animation in it at all.

If you do want secondary motion — a cloak with a swing of its own — name your
actions exactly:

| Action | Plays when |
|---|---|
| `idle` | standing still |
| `walk` | walking |
| `run` | sprinting |

**It composes with the body rather than replacing it.** Your clip runs and the
wearer's motion is applied on top, which means you author only the *extra*: a hem
lifting, a tail lagging. Animating the walk itself into a cloak gets you two
walks. And a clip is driven by the wearer's own gait clock, so what you make
lands in step with the legs by construction — there is no frame rate to match.

**Bake to keyframes on the bones before exporting.** No IK, no constraints, no
shape keys — none of them are read. `LINEAR` and `STEP` interpolation are read
exactly; `CUBICSPLINE` loads but its tangents are dropped, and a spline through
hand-set keys overshoots, which bends a limb backwards.

---

## 6. Export

1. **Delete the body** — every object named `REF_*`, `ranger_*` or `wayfarer_*`.
   It is a stand-in for the player and must not ship inside a hat.

   **Keep the rig if you parented to it.** A skinned mesh needs its armature in
   the file or the skinning is lost, and the spare bones cost nothing: a bone
   with no geometry on it emits no triangles. Export the rig and your piece
   together.

   If you took the no-armature route (step 4's last paragraph, objects named for
   their bone), delete the whole collection — there is nothing to keep.

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
   src/main/resources/watch/models/cosmetics/<figure>/<piece key>.glb
   ```

   `<figure>` is `walker` or `wayfarer` — whichever body you fitted it to. Drop
   the folder only if you cut it to the boxed reference figure and mean it to be
   worn by anybody; see fact 4.

   The twenty-eight keys — eighteen off the rail, six of standard kit and four
   of hair:

   ```
   wool_mittens      knitted_beanie   canvas_gaiters   wool_scarf
   rolled_bedroll    wire_spectacles  feathered_band   glass_lanyard
   leather_gloves    straw_boater     snow_goggles     oilskin_hood
   river_waders      moth_veil        fur_collar       oilskin_cape
   antler_circlet    heron_cloak

   field_coat        field_trousers   walking_boots
   field_pack        walking_hat      neckerchief

   swept_hair        long_plait       cropped_hair     topknot
   ```

---

## 7. Check it

```bash
./gradlew :test --tests '*PlayerFiguresTest*' --tests '*CosmeticsTest*' --tests '*ModelImportTest*'
./gradlew run
```

`PlayerFiguresTest` is the one that will tell you whether it fits: it draws every
piece on every figure out of `src/main/resources` and checks each is worn where
its slot says, on both bodies, inside the triangle budget.

In the game: press <kbd>Esc</kbd> and <kbd>←</kbd>/<kbd>→</kbd> to be the figure
you cut it for, then <kbd>Enter</kbd> for the wardrobe and click the piece to put
it on. (One of the eighteen has to be bought first: walk to a trading post, press
<kbd>E</kbd>, then <kbd>←</kbd> for the clothes rail.)

Then <kbd>F5</kbd> for third person and, standing still, **hold the middle mouse
button** (or <kbd>O</kbd>) **to swing the camera round yourself** — up, down and
all the way about. That is the only way to look at the front of anything, and it
is what you want the moment you are checking a fit rather than playing. It is on
the controls screen as "Look Around Yourself" if you would rather move it.

**If it did not load**, one line went to stderr saying why and whatever was there
before was drawn instead:

```
watch: could not load model watch/models/cosmetics/walker/heron_cloak.glb (no triangles in it) — keeping the fallback
```

The causes, in the order they actually happen:

| It looks like | It is |
|---|---|
| the old piece is still there, and stderr said nothing | the filename does not match a key, the figure folder is spelled wrong, or the folder is not the one being read — a `watch/models` folder **next to the jar** wins over the classpath |
| the old piece is still there, and stderr said why | a `.gltf` whose `.bin` was not beside it, a truncated file, or nothing selected on export. Use `.glb` |
| it is lying at their feet | you modelled it at the world origin instead of **in place** on the figure. A cosmetic is never moved for you |
| it is 170 mm too high, or 250 mm off at the hands | you fitted it to the **boxed** reference figure and filed it under a figure folder, or the other way about. See fact 4 |
| it fits one figure and not the other | that is the system working. Cut a second one and file it under the other key |
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

* **Your own hands in first person keep the boxes**, and they are the only thing
  that does now: the view model is built in the camera's frame rather than the
  world's, so there is nothing there for a world-space garment to be carried by.
  A swimmer and a rower used to keep them too and no longer do.
* **The player picks its colour.** The base colour of your piece and its own
  shades are moved by three sliders on the wardrobe screen; the trim, the
  buckles and the lenses are not. So give the base colour to most of the piece,
  and make the trim a colour of its own rather than the main at some fraction —
  a trim that is exactly `main × 0.5` is a trim that gets dyed with it.
* **It is not recoloured *per wearer*.** The two pieces the game tints to the
  wearer's own coat — the oilskin hood and the cape — stop being tinted the
  moment they are
  modelled. If you want a cape that still reads as *that player's* across a
  valley, leave some of the coat showing.

---

## Committing it

A `.glb` is binary and will not diff, so the commit message is the only record of
what changed:

```bash
git add src/main/resources/watch/models/cosmetics/walker/heron_cloak.glb
git commit -m "Heron cloak, walker: modelled in Blender, walk clip"
git push -u origin <your-branch>
```

Once it is pushed, a cloud session can run the import tests against the real file
and report what the importer made of it — triangle count, which bones bound to
which joints, which clips it found, and whether anything was skipped.

**Blender itself cannot be driven from a cloud session** — the MCP server is a
socket on your machine and a container has no route to it. See the note at the
foot of [`BLENDER_BRIEF.md`](../../BLENDER_BRIEF.md).
