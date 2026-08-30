# Field Guide — the animal-watching game

> **Status:** implemented. This document is the design record: what the game is,
> how it is put together, what it reuses from the engine, and where each piece
> of the brief landed in the source tree.

A fourth mini game, sitting beside the Auto Battler, Council of Six and
Evolution on the launch screen's corner strip. You and up to seven friends walk
an endless, procedurally generated wilderness, find animals, and write them into
a shared field guide. Nothing in it is a fight.

```
  Launch screen  →  [ Field Guide ]  →  watchlobby  →  watch
                                            ↘ watchguide (the book)
```

---

## 1. Why it is its own package, and what it borrows

The engine already has an infinite procedural world: `world/gen/WorldGenerator`
builds run-length-encoded **voxel columns**, `graphics/chunk` meshes them into
16³ sections, and `:gl` draws those sections with a depth buffer. That machinery
is excellent and this game does not use it, for one reason: the brief asks for
**low-to-mid-polygon terrain**, and a voxel column is neither. A hillside that
reads as a hillside rather than as a staircase is a continuous heightfield
meshed into triangles, and there is no setting on a block world that produces
one.

So the world is new (`watch/world`) and everything *around* it is borrowed:

| Borrowed | From | Used for |
|---|---|---|
| `EyeCamera` | `graphics` | projection, near-clipping, the frustum test |
| `PerlinNoise` | `level` | every noise field in the generator |
| `DrawTarget` | `graphics/draw` | the whole CPU render path — no `Graphics2D` anywhere |
| `Skins` / `TexturePack` | `graphics` | custom textures for terrain, flora and animals |
| `Menu` / `ConfigForm` / `MenuTheme` | `ui` | lobby and book screens |
| `Protocol.encode/decode` | `net` | the wire framing, exactly as the auto battler reuses it |
| `KeyBinds` / `GameAction` | `input` | rebindable controls, in their own category |
| `Json` | `util` | saves and the wire |
| `StandaloneGame` | `minigame` | the launch-screen button, its art and its texture key |

The core's **zero-runtime-dependency invariant is untouched**: everything here
is JDK-only, and the one GPU addition lives in `:gl` behind a core interface
that defaults to "unsupported".

---

## 2. The world

### 2.1 Terrain (`watch/world/TerrainField`, `WatchChunk`, `ChunkStreamer`)

A **pure function of (seed, x, y)**, like `WorldGenerator` and for the same
reason: any chunk can be built on any thread in any order, and rebuilt
identically after eviction. Nothing in a chunk depends on what has been
generated before it.

Per sample the field produces a `Ground` reading — height, biome, surface
material, water depth, trail strength — from a stack of noise fields:

1. **Climate** — slow temperature / humidity / weirdness fields.
2. **Continents** — a very slow field that drops land below the water line.
3. **Biome** — every biome scores itself against the climate; the winner
   supplies materials, and a weighted blend of **all twenty** supplies the
   height target and relief. Blending the numbers while picking the materials
   is what gives sharp biome borders over ground that never steps — the same
   trick `WorldGenerator` uses. *(Built as "the top few" and changed: the
   membership of a top-three set changes discontinuously as the climate moves,
   and that discontinuity was a twelve-metre cliff. Blending everything, with
   the weights cubed so distant biomes contribute nothing, is continuous
   everywhere.)*
4. **Relief** — ridged noise for spines, fbm for the lumps, warped by the
   biome's own `relief`.
5. **Rivers and lakes** — a lake field dimples the land; anything under the
   water table is flooded.
6. **Trails** — see below; a trail cuts the ground toward the level of the
   undisturbed terrain around it and replaces the surface material.

A **chunk** is `WatchChunk.SAMPLES` squared height samples (17×17 for a 32 m
tile sampled every 2 m — one extra row and column so neighbouring chunks share
an edge and the mesh has no seam), plus the flora, water and trail data for that tile. The
`ChunkStreamer` keeps a ring of them around every viewer on a small pool of
daemon workers (`watch-chunk-N`), evicts by distance, and never blocks a frame:
a frame draws what has arrived.

### 2.2 Biomes (`watch/world/WatchBiomes`) — twenty of them

The seven the brief named, and thirteen more:

| # | Key | Name | |
|---|---|---|---|
| 1 | `pine_forest` | Pine Forest | *asked for* |
| 2 | `deciduous_forest` | Deciduous Woods | *asked for* |
| 3 | `desert` | Dune Desert | *asked for* |
| 4 | `rainforest` | Rainforest | *asked for* |
| 5 | `tropics` | Palm Tropics | *asked for* |
| 6 | `mountains` | Mountain Ridges | *asked for* |
| 7 | `amethyst_grove` | Amethyst Grove | *asked for — the purple-leaf fantasy biome* |
| 8 | `autumn_birchwood` | Autumn Birchwood | |
| 9 | `boreal_taiga` | Boreal Taiga | |
| 10 | `alpine_meadow` | Alpine Meadow | |
| 11 | `savanna` | Acacia Savanna | |
| 12 | `wetland_marsh` | Reed Marsh | |
| 13 | `mangrove_coast` | Mangrove Coast | |
| 14 | `redwood_cathedral` | Redwood Cathedral | |
| 15 | `canyon_badlands` | Canyon Badlands | |
| 16 | `tundra_barrens` | Tundra Barrens | |
| 17 | `bamboo_thicket` | Bamboo Thicket | |
| 18 | `mushroom_hollow` | Mushroom Hollow | *fantasy* |
| 19 | `sunflower_prairie` | Sunflower Prairie | |
| 20 | `crystal_highlands` | Crystal Highlands | *fantasy* |

A biome is **data** (`WatchBiome`), following the precedent `Biome` set: climate
band, height target and relief, a five-colour palette, its water and sky tints,
which tree species grow in it and how densely, its grass length range, its
berry and seed tables, and the animal families that live there. Adding one is
adding a row.

### 2.3 Trails (`watch/world/TrailNetwork`)

Trails are generated the way the terrain is — as a pure function of position —
so two players a kilometre apart agree about them without exchanging a byte.

The world is cut into **trail cells** of 160 m. Each cell deterministically
places one *node*, and joins it to its eastern and southern neighbours by a
path that is a quadratic Bézier with a noise-displaced control point.
`strengthAt(x, y)` returns how close a point is to the nearest such path, and
the terrain field uses it to (a) pull the height toward the level of the
undisturbed terrain in a ring outside the cut, and (b) swap the surface
material for the biome's trail material. The result is a network of packed-earth paths that
wander over ridges and along valleys, join up, and continue forever.

### 2.4 Trees that grow (`watch/world/TreeSpecies`, `TreeInstance`, `Grove`)

Thirty-six tree species — twenty-four that grow wild and twelve that exist only
as the child of two others — each with **five growth stages** (`SEEDLING`,
`SAPLING`, `YOUNG`, `MATURE`, `ANCIENT`) that change trunk height, canopy
radius and the mesh that gets built. A tree carries:

* a **species**, and
* a **genome**: four traits (`vigour`, `canopy`, `hue`, `fruit`), each 0–255.

Wild trees are generated deterministically from their position, so an untouched
forest is identical for every player. A tree the players *plant* is a
`TreeInstance` in the game state, ticked with the world clock, advancing stages
over real hours.

**Cross-pollination** (`Grove.pollinate`) crosses two neighbouring mature trees:
the child takes each trait from one parent or the other with a small mutation,
and when two *different species* cross, the child's species is looked up in a
hybrid table — `pine × amethyst → frostglass pine`, and so on. Twelve hybrids
exist; six of them can only be produced by crossing, which is what makes a
cultivated grove worth keeping.

### 2.5 Grass (`watch/world/GrassField`)

Grass is placed by a blue-noise-ish hash per square metre and given a **length**
from a separate low-frequency field scaled into the biome's own
`[grassMin, grassMax]` band, so a meadow has long grass in the hollows and
cropped grass on the ridges. Each blade is meshed as a two-triangle tapered
quad; length drives height, biome drives colour, and a wind field shears the
tip. Grass beyond `GrassField.DETAIL_REACH` is not built at all.

### 2.6 The clock (`watch/WatchClock`)

**Synchronised to real life**, as asked. `WatchClock.fromSystem()` reads the
host's wall clock and maps local midnight → `0.0`, noon → `0.5`. Everything that
cares — sky colour, sun and moon position, fog, which animals are out — reads
this one number. In multiplayer the *server's* clock is authoritative and rides
along in every snapshot, so a session spread over three time zones shares one
sunset. A test asserts the mapping in both directions.

### 2.7 Trodden tracks (`watch/world/TrackField`, `watch/render/TrackMesher`)

The other kind of path, and deliberately not the same mechanism as §2.3. A
trail is a fact about *position* and is generated; a **track** is a fact about
*history* and cannot be. It is the only thing in this world that is neither
derived from the seed nor sent over the wire.

Walking lays a **print** every `STRIDE` (1.4 m) into a per-walker chain; two
consecutive prints make a *segment* only if they are within `MAX_GAP` (6 m) of
each other, which is the single rule that stops a line being drawn across a lake
somebody rowed. A segment holds full strength for the first third of its life
and then eases to nothing at **ten minutes**, so the path you are standing on
reads as a path and the wood takes it back within the hour. Because the record
is proportional to *distance walked* rather than to time spent, standing in a
clearing costs nothing at all.

**It is drawn on the ground and never cut into it.** The heightfield is what
eight machines agree about without exchanging a byte, and a decoration is not
allowed to be the thing that gives that up — nor to force a re-mesh of whatever
chunk somebody is standing in every few strides. So `TrackMesher` lays a
translucent sheet 9 cm over the ground in the biome's own trail material, meshed
as one bending strip per walker (shared cross-sections, so a turn bends rather
than leaving a wedge on the outside of it). Two walkers *do* overlap, and are
meant to: alpha composites, so ground two people crossed comes out darker than
ground one did, for free.

**Nothing about it travels.** Every walker's position is already in every
snapshot, twenty times a second, so both ends watch the same feet and each keeps
its own copy of where they went — the same bargain the boats and the shops
strike from the other direction. The cost is honest and bounded: somebody who
joins halfway through sees the last ten minutes fill in rather than arrive.

One renderer change came with it. A painter's algorithm has no way to say *this
lies on that* — a decal on the far half of a two-metre ground triangle sorts
behind the triangle's own midpoint and is painted over, so a trail across open
country comes out as dashes on the grid the ground happens to be meshed at.
`Mesh.sortBias()` pulls a mesh toward the eye **for the sort key only**; the
geometry, the fog and the culling all keep the true depth. A card needs none of
it, because a depth buffer compares fragments rather than centroids.

---

## 3. The animals

### 3.1 A thousand species (`watch/life/AnimalRegistry`)

Hand-writing a thousand rows is neither possible nor useful; generating them
randomly gives a thousand animals nobody can tell apart. So the registry is
**combinatorial and deterministic**:

```
  family  ×  lineage  ×  descriptor  →  species
  (24)       (~6 each)    (~9 each)
```

* a **family** (`AnimalFamily`) is a body plan — songbird, raptor, waterfowl,
  wader, owl, hummingbird, corvid, parrot, deer, canid, felid, rodent,
  mustelid, primate, bear, bovid, hare, reptile, amphibian, butterfly, beetle,
  fish, bat, fantasy — and carries the model, the gait, the animation set and
  the base behaviour;
* a **lineage** narrows it (`finch`, `thrush`, `warbler`…), setting size,
  colour family and diet;
* a **descriptor** is the epithet (`banded`, `emerald`, `dusk`…), which shifts
  the palette, the rarity and one behavioural trait.

Every combination produces a stable key (`songbird_finch_banded`), a stable
name (*Banded Finch*), a palette, a rarity, an activity window, a diet, a set
of biomes it lives in, and the lures it will come to. The build is deterministic
and the test suite asserts: **≥ 1000 species, unique keys, unique names, every
biome reachable, every rarity tier populated, and identical output across two
builds.**

### 3.2 Models (`watch/life/AnimalModel`) — Minecraft-style, textured

Placeholders as asked, and *real* placeholders: every species gets a boxy
3D model — cuboids with a per-face UV into a 64×64 procedurally painted skin —
built from its family's body plan and its own palette. A finch is a body box, a
head box, a beak wedge, two wings and two legs; a deer is a longer body, a neck,
a head, four legs and antlers if its lineage has them. They animate: each family
declares an `AnimalPose` for `IDLE`, `WALK`, `RUN`, `FLY`, `FORAGE`, `ALERT`,
`SLEEP` and `CALL`, and the pose is a per-part rotation curve driven by a phase
clock.

Textures are procedural but **overridable**: every part reads
`animal/<species>/skin` through `Skins`, so a texture pack replaces one
species' skin, and `animal/<family>/skin` replaces a whole family's.

### 3.3 Blockbench (`watch/life/Blockbench`, `resources/watch/models/README.md`)

The importer reads a real `.bbmodel` / Blockbench-exported `.json`: `resolution`,
`elements` (with `from`, `to`, `inflate`, `rotation`, `origin` and per-face
`uv`), `outliner` bone groups, the texture, and `animations` with
`bones/<bone>/{rotation,position,scale}` keyframe channels including the
`linear` / `catmullrom` interpolation modes. What comes out is the same
`AnimalModel` + `AnimalClip` types the procedural placeholders produce, so a
species with a Blockbench model is drawn by exactly the same code as one
without.

Drop a model at `resources/watch/models/<species>.bbmodel` (plus
`<species>.png` for its texture) and it replaces that species' placeholder at
load. The resource folder's README is the full instruction sheet: axis
conventions, the bone names the animation binder looks for, the clip names it
maps to which `AnimState`, and how to check your model loaded.

### 3.4 Being alive (`watch/life/Animal`)

A small AI: `WANDER → FEED → ALERT → FLEE`, plus `PERCH`/`ROOST` for fliers and
`SLEEP` outside the species' activity window. Animals notice players by distance
scaled by the player's **stillness** — walk at one and it flushes at forty
metres; crouch and wait and it will feed at five. Lures pull them in
(`Lure.attract`), and a species will only come to a lure it eats.

Some are **tameable** (`AnimalDef.tameable`, about one species in four and a
half — the share is per family, so a songbird is far likelier to come home with
you than a bear is).
Feeding a tameable animal its preferred food repeatedly raises `trust`; at full
trust it becomes a **pet**, follows its owner, and is saved with the profile.

---

## 4. Playing it

### 4.1 Spotting and the shared field guide

Click an animal you can see: `WatchGame.spot` traces the camera ray, finds it,
and

* writes a `Sighting` into your `FieldGuide` — first sighting of a species is
  a **discovery**, with the time, the biome and who found it;
* raises a **`Spotlight`** — the animal is outlined for *every* player for four
  seconds, with your name on it. This is the "point at the bird" gesture the
  brief asks for, and it is a server message so it is genuinely shared.

The guide is a book (`watchguide`) with a page per species: silhouette, name,
family, where and when you saw it, who else in your party has it, and the
lures that work.

### 4.2 Attracting things

Four routes, all of them real:

* **Berries** — `Forage.berriesAt` puts biome-appropriate berry bushes in the
  world; picking gives fruit, which is a lure and a cooking ingredient.
* **Seeds** — found in grass and cones; plant them (`Cultivation`) and they
  grow into feeders' worth of grain, or into trees.
* **Fishing** — a lake or river tile plus a rod gives the `Fishing` mini-loop
  (cast → wait a species-dependent time → strike inside a window). Fish are
  the lure every raptor, heron and kingfisher wants.
* **Cooking** — `Recipes` turns raw ingredients into ten prepared foods with
  much wider draw (suet cake, berry mash, nectar, grain loaf…).

A placed feeder (`Lure`) has a radius, a strength and a decay; `WatchGame`
resolves which species within its radius are drawn to it each tick and steers
them in.

> Foraging the *ground* — as opposed to a bush or a tree — was an invisible roll
> when this was written and is a thing lying there to be walked to now. See
> §7b, "Foraging happens to something you can see", and `watch/Litter`.

### 4.3 The spyglass (`watch/Spyglass`)

A draw tube with three stops — **×4, ×8, ×15** — held up on the right mouse
button, its stop changed with the wheel. It is the one craftable object that
changes what the world *is* rather than what comes to you.

**It is optics, not a crop.** The temptation is to draw the frame as usual and
scale the middle of it up; that is a magnifying glass held over a photograph,
and a chaffinch four hundred metres away stays the three grey pixels it was.
Instead the camera's own field of view is narrowed —

```
  fov(power) = 2·atan( tan(fov₀ / 2) / power )
```

— so the far hillside is re-projected at the size it now subtends and drawn with
the triangles it deserves at that size. `EyeCamera`'s floor moved from 20° to 2°
for this; twenty degrees is ×3.5, which would have silently clamped the top two
stops and made "×15" a lie.

**And the distance is actually built.** A raised glass sets a
`ChunkStreamer.Focus` — an origin, a heading, a reach and a magnification — and
chunks inside that cone are wanted even far outside the view radius, at a level
of detail chosen from their distance **divided by the magnification**. At ×8 a
chunk twenty out is meshed as though it were two and a half away: real trunks,
bushes, the lot. It is affordable because the same narrowing that asks for the
detail pays for it — a ×8 glass is a ten-degree frustum, so nearly everything in
the ordinary ring fails the frustum test and is never submitted. On the software
path the cone is capped shorter, for the same reason the ring is six chunks
there and sixteen on a card.

Measured on the **software** path (960×540, seed 20260828, standing in the
deciduous woods, cone capped at ten chunks there):

| | naked eye | ×15 glass |
|---|---|---|
| field of view | 70° | 5° |
| chunks in memory | 169 | 192 |
| chunks at full detail | 9 | 38 |
| furthest full-detail chunk | 45 m | **326 m** |
| triangles submitted to the frame | 34,992 | **11,152** |

Full detail reaches seven times further and the frame costs a third of the
triangles, because the narrowing that asks for the detail is the same
narrowing that culls everything either side of it.

Three more things follow, and all three are the difference between an
instrument and a prop:

* **There is something out there.** A third of a glassing player's share of the
  animal roster is spawned down the line they are looking, and nothing inside
  the cone is ever despawned. Without it the beautifully-drawn far shore would
  be empty, because the spawn ring is ninety-five metres and a ×15 spot is nine
  hundred.
* **The host decides the reach.** `WatchGame.glass` refuses a power to anybody
  without a spyglass in their satchel, and `lookingAt` takes its range and its
  tolerance from `Spyglass` — the reach grows with the power and the angular
  tolerance shrinks by it, so glassing is a longer *and* a more exact way of
  pointing. Being able to record a bird nine hundred metres away is the one
  thing in this game worth cheating for.
* **It shakes.** Fifteen magnifications multiply the tremor in your hands by
  fifteen too. The sway is scaled to the current field of view and damped by
  the game's existing `stillness` stat, so crouching and standing still is what
  steadies it — the same answer the whole game already has for getting near
  anything.

Making one is the deepest chain in the book, deliberately: quartz off bare rock
or crystal, sand off a dune or a beach, ground together into a **lens** at a
bench, two lenses plus a plank, rope and sap into the **tube**. What the ground
gives up is `Forage.underfoot`, which reads the *surface underfoot* and not only
the biome — so "go and find a beach" is a real instruction. Nobody has a glass
in their first ten minutes, and anybody who wants one has a reason to walk
somewhere they have not been.

The eyepiece on screen — the round mask, the brass ring, the half-degree
reticle — is drawn over a finished frame and is *not* the zoom. It would be just
as correct if it drew nothing, and the view would be just as magnified.

### 4.4 Debug mode (`watch/Debug`)

Type **7799** on the number keys anywhere in the walk and debug mode comes on;
type it again and it goes off. A typed code rather than a key bind (the
controls screen is for the game's verbs, and putting ten digits on it would put
the answer on it too) or a menu item (which invites a first-time player to press
it and skip the game).

`Debug.Power` is the whole of what it grants, and it is a short list rather than
twelve rows:

| | |
|---|---|
| **Unlimited items** | Every recipe, every build piece, every feeder, every seed, every tool. |
| **Unlimited points** | Anything on any keeper's shelf, at any price. *(Added in §7f — see below for why it had to be.)* |
| **Readout** | Position, chunk and LOD, biome and material underfoot, streaming, triangles, what is alive, the glass, the guide, the nearest trading post. |

**A short list because the first row is structural.** Debug mode does not hand
out a list of items — it makes the player's satchel `bottomless`, and *almost
every* cost in this game is a `has` and a `take`
against a `Satchel`. One lens over one class covers crafting, building,
feeders, planting, fishing and the spyglass, and it covers whatever is added
next **without being edited**: an item added to `Forage` is already unlimited, a
recipe added to `Recipes` already affordable, a piece added to `BuildPiece`
already free. A debug mode written as a list of grants is a copy of a registry
that keeps moving, and it is stale a week later.

Three properties worth keeping:

* **A lens, not a gift.** Nothing is added when it goes on and nothing taken
  when it comes off, so switching it off leaves the walk exactly as it was.
* **It reaches the screen.** The flag is on the player, rides in their own
  snapshot, and is copied onto the view's satchel on both the solo and the wire
  paths — so a client's cooking and build screens light up exactly when the
  host says they should rather than greying out things the host would allow. It
  survives a save, too.
* **The host's walk only.** `WatchGame.debug` refuses the code to anybody who
  is not the first walker on the walk, because the guide is shared.

The extension point, stated once so it does not have to be guessed: a future
power is **a row in `Debug.Power`** — which is what puts it on the readout —
**and one `if (player.debugging())` where it acts.**

**Unlimited points** is the first row that had to be added, and it cost exactly
that: one row and one `if`. It exists because §7f broke the invariant the first
row rests on — a trading post's prices are paid out of the `FieldGuide`'s
balance rather than out of a satchel, so the lens does not reach them and no
amount of cleverness would make it. Which is the honest version of "the list is
short": it is short because the structural row covers so much, not because
nothing will ever fall outside it.

### 4.5 Building (`watch/build`)

Ten piece types — post, beam, floor, wall, window wall, roof, ladder, door,
platform and rope bridge — each with a **foraged** recipe (fallen branches,
bark, reeds, stone, vine). Pieces snap to a metre grid; a piece placed against a
trunk anchors to it, which is how a treehouse gets built. Structures are shared:
placement goes through the server and every player sees it.

---

## 5. Rendering

### 5.1 The mesh (`watch/render/Mesh`)

Deliberately **the same vertex format as `SectionMesh`** — `x, y, z, u, v` plus
a packed `0xAARRGGBB` per vertex, positions relative to the mesh's own origin.
That is not a coincidence: it means the GPU path can use the block renderer's
existing shader, its existing buffer layout and its existing fog, and it means
anyone who has read `graphics/chunk` already knows this format.

### 5.2 CPU path (`watch/render/WatchRenderer`)

The JDK-only build must work (invariant 4), and Java2D has no depth buffer, so
this is a painter's algorithm — the same choice `SolidPainter` made:

1. transform every vertex into the eye frame (`EyeCamera.toEye`);
2. drop back-facing triangles by the sign of the projected cross product;
3. `EyeCamera.clipNear` anything crossing the near plane;
4. shade flat: the triangle's normal against the sun direction the clock gives,
   plus the biome's ambient, plus distance fog;
5. sort far-to-near on the centroid depth and `fillPolygon` them.

Triangles are collected into a reusable buffer and sorted as packed `long`s
(depth in the high bits, index in the low) — one `Arrays.sort` on primitives per
frame, no allocation, the trick `SolidPainter` uses.

### 5.3 GPU path (`graphics/MeshPass`, `gl/GlMeshPass`)

A new, narrow core interface:

```java
public interface MeshPass {
    void setTexture(BufferedImage atlas, int revision);
    void draw(List<Mesh.Draw> draws, EyeCamera eye, int fogArgb,
              double fogStart, double fogEnd);
}
```

`TerrainPass.meshPass()` returns one (default `null`). `GlMeshPass` uploads each
mesh once into a VBO keyed by the mesh's identity and revision, re-uses it until
the mesh changes, and draws with a depth test, back-face culling, alpha testing
and the same fog curve as the CPU path — so the two look the same, which is what
makes it possible to develop on either. The core never imports LWJGL;
`ModuleBoundaryTest` and `verifyNoRuntimeDependencies` still hold.

### 5.4 Threads

| Pool | Threads | Work |
|---|---|---|
| `watch-chunk-N` | `min(6, cores − 1)` | terrain sampling, flora placement, meshing |
| `watch-server` / `watch-accept` | 2 | when hosting |
| the frame thread | 1 | culling, drawing, input |

Chunk builds are queued nearest-first and bounded per frame on upload, so a
teleport cannot spend a frame in `glBufferData`.

---

## 6. Online (`watch/net`)

The auto battler's shape exactly: newline-delimited compact JSON over TCP,
framed by `net/Protocol`, its own vocabulary and its own version, an
authoritative server owning all state on a tick thread, connections that only
decode onto a queue.

```
  client → server   {"t":"join","v":1,"name":"Larson"}
  server → client   {"t":"welcome","id":3,"seed":...,"tick":20,"world":{...}}
  server → all      {"t":"party","host":1,"players":[{id,name,x,y,z,yaw}...]}
  client → server   {"t":"move","x":..,"y":..,"z":..,"yaw":..,"pitch":..,"st":..}
  server → all      {"t":"state","time":0.42,"players":[...],"animals":[...]}
  client → server   {"t":"spot","a":animalId}
  server → all      {"t":"seen","a":id,"sp":"songbird_finch_banded","by":3}
  client → server   {"t":"lure","k":"suet","x":..,"y":..}   {"t":"build","p":"floor",...}
  client → server   {"t":"plant","s":"pine","x":..,"y":..}  {"t":"tame","a":id}
  server → all      {"t":"world", ...}    (lures, structures, plantings, pets)
```

**Eight players**, `WatchProto.MAX_PLAYERS`, port **7799** (the world server is
7777, the auto battler 7788, the deckbuilder 7790). Animals are simulated only
by the server and replicated in an interest radius around each player, so a
party spread across the map costs what it should.

---

## 7. Where the brief landed

| Asked for | Where |
|---|---|
| Infinite low-poly 3D terrain, custom textures | `watch/world/TerrainField`, `watch/render/TerrainMesher`, `WatchMaterials` (texture keys) |
| Online, up to 8 | `watch/net/*`, `WatchProto.MAX_PLAYERS = 8` |
| GPU accelerated, multithreaded | `gl/GlMeshPass`, `graphics/MeshPass`; `ChunkStreamer` worker pool |
| Trees that grow in stages | `TreeSpecies.Stage`, `TreeInstance.advance`, `Grove` |
| Grass of varying length | `GrassField`, `WatchBiome.grassMin/grassMax` |
| 3D animals from Blockbench + placeholders | `watch/life/Blockbench`, `AnimalModel`, `resources/watch/models/README.md` |
| Trails | `watch/world/TrailNetwork` |
| Temporary tracking trails that follow players | `watch/world/TrackField`, `watch/render/TrackMesher` |
| 17+ biomes, incl. the seven named | `watch/world/WatchBiomes` (20) |
| Luring: fishing, berries, seeds, cooking | `watch/Fishing`, `Forage`, `Cultivation`, `Recipes`, `Lure` |
| Tree cross-pollination | `Grove.pollinate`, `TreeSpecies.hybrid` |
| Building from foraged materials | `watch/build/BuildPiece`, `Structure` |
| Real-life day/night | `WatchClock.fromSystem` |
| 1000+ animals | `AnimalRegistry` (asserted in tests) |
| Pets | `AnimalDef.tameable`, `Animal.trust`, `FieldGuide.pets` |
| Click to highlight for everyone | `WatchGame.spot` → `Spotlight` → `WatchProto.seen` |

---

## 7a. The second round

> A later pass, from playing it. Eleven items; three of them were bugs, and
> the bugs are the ones worth reading about.

### The three that were wrong

**Built things jumped.** A mesh's vertices are measured from its own origin and
the origin is applied by the model-view matrix, so a cached GPU buffer is only
re-usable for a draw *at the origin it was filled at*. `GlMeshPass` compared the
revision and not the origin, and deferred an upload when the frame was over its
cap — and the dynamic mesh (animals, walkers, feeders, everything built) is
rebuilt each frame around the *player's* position. So a deferred frame drew last
frame's vertices at this frame's origin: the whole lot shifted by however far the
player had moved and snapped back on the next upload. Walls and platforms, which
do not move, were where it showed. Buffers now record their origin and a moved
one is never deferred; the dynamic mesh's origin is also snapped to the metre so
it changes on a step rather than on a frame.

**Level of detail never reached the card.** The meshers stamped
`chunk.revision()` — the chunk's *data* revision, fixed at generation — into
every mesh they built. That is exactly the number a backend compares to decide
whether to re-upload, so a chunk re-meshed at a finer LOD produced meshes the
backend had already seen and skipped. On the GPU path a chunk was therefore drawn
for ever at whatever detail it was first built at: walking toward a hillside never
sharpened it, and the swaying grass never swayed. There is now a separate
`WatchChunk.meshRevision`, bumped by `beginMesh()` before each re-mesh. The
painter walks the arrays directly and had been right the whole time, which is
why this survived so long on the machine it was written on.

**Animals got stuck, and swam through hills.** Two causes behind one symptom.
`Animal.decide`'s wander branch returned early whenever the target had not been
reached, with no other exit — so anything that could not close the distance
wandered at it for the rest of the session. And a swimmer's `altitude` was
applied as a height *above the ground* when it meant a depth *below the
surface*, which put a fish a quarter-metre inside whatever it was over,
including hillsides it had wandered onto because nothing stopped it. Journeys
now time out (`WANDER_TIMEOUT`) and stalls are noticed within a second and a
half; targets are chosen in the medium the animal lives in; steps that would
leave that medium are refused; anything *already* out of its medium may move
toward it, which is what stops the fix creating a new stuck case; and altitude
is clamped so nothing is ever inside the ground.

### The fourth, reported after the first three were fixed

**An animal would sprint away and then freeze in place, still playing its
running animation.** Three causes, all producing the same picture, because the
pose came from what the animal *intended* while the position came from what was
*possible*:

1. **Fleeing chose one escape and never another.** `enter(FLEE)` picked a point
   24–54 m off at the moment of the flush, and while a player stayed inside the
   flush distance `decide` returned early on every subsequent tick without
   reconsidering. The animal ran there, arrived, and stood at a dead run — and
   `move`'s "am I there yet" guard did nothing at all, not even count the
   standstill as a stall, so the give-up timer could not rescue it either.
   Measured on a fallow deer with a player five metres off: 53 m of running,
   then **114 seconds frozen**. This is also why animals never left the area —
   one escape is at most 54 m and the despawn radius is 170.
2. **The escape was chosen without a route.** The ring only tested whether the
   *destination* was habitable, so a deer's way out could be a good meadow on
   the far side of a lake: every step was then refused and it pressed into the
   bank until the timer fired, then picked another point across the same lake.
3. **A cooling-off animal kept the running pose.** Having got clear, it spent
   `ALERT_PATIENCE` (3.5 s) still in `FLEE` before settling.

Fixed respectively by `keepFleeing` (re-target on arrival or stall, direction
chosen as the calmest point on a sampled ring, so it is away from the players
by construction), `advance` (a blocked step is deflected up to 80° and the
animal commits to one side, so it runs *along* a shoreline; genuinely cornered
becomes `ALERT`), and stopping the run at arrival rather than at the end of the
cool-down.

And then the class of bug is closed rather than the instances: `poseFor` checks
the ground actually covered at the end of every tick, so an animal that did not
move is never drawn moving, whatever the cause. Measured after: **zero** frozen
frames across four worlds and twenty-seven minutes of simulation, and 26 of 26
animals now leave the area when walked at.

### The eight that were missing

| Asked for | Where |
|---|---|
| A player model, and hands in first person | `watch/render/WalkerModel` — one articulated figure with a gait, drawn for every player; `hands` builds the view model in the camera's basis |
| Items with models, highlighted when picked up | `watch/render/ItemModel`; `WatchGame.pickTarget` → the glow, ring and prompt in `WatchScene.drawReachHighlight`. Extended in the third round from one model per *kind* to one per *item*, and from a highlight to a highlight with something under it |
| Weather events | `watch/Weather` — eight conditions, server-owned like the clock, in the snapshot's `sky` field |
| Scroll through the entire inventory | `WatchScene.drawSatchel` — two scrolling columns with cursors, windows and bars |
| Walk the sea floor, find underwater animals | `WatchScene.swim` (Space rises, Shift sinks); `WatchPlayer.breath`; `WatchGame.populate` samples a wet ring and a wet species table for a submerged player |
| Findable boats | `watch/Boats` — generated on shorelines from the seed, like the trails; `watch/render/BoatModel` |
| Terrain rendering, and permanence on a big machine | `graphics/ChunkMemory`; `ChunkStreamer`'s retained LRU cache; `GlMeshPass` upload and buffer budgets |
| General UI quality of life | one key for whatever is in reach (`WatchGame.use`), a compass, a breath meter, a pickup flash, a weather line |

### Ground that has been built stays built

The streamer used to drop any chunk more than three outside the view radius, so
walking to the lake and back rebuilt the whole path there — a few milliseconds
of noise per chunk, on the very workers that should have been building the
ground *ahead* of the player, and pacing over one chunk boundary could
regenerate the same ground indefinitely. On a machine with sixteen gigabytes in
it, throwing that work away to save forty megabytes is the wrong trade by two
orders of magnitude.

A chunk walked away from now moves into a least-recently-used cache sized from
`Runtime.maxMemory()` (`ChunkMemory.chunkCacheBudget`, an eighth of the heap at
96 KB a chunk, floored at 256 and capped at 12,288). Walking back is a map
lookup. The cache is only ever an optimisation — a chunk is a pure function of
`(seed, x, y)`, so a miss is indistinguishable from a cache that was never there
— which is why a small heap can have a small one and nothing else has to know.
`GlMeshPass` sizes its buffer ceiling from the same number, so the card is never
what forces a re-mesh of ground the CPU still holds, and its per-frame upload
cap went from twelve to thirty-six: twelve was chosen when a view held a few
dozen chunks, and at the distances a card actually holds it turned "the ground
arrives a frame late" into "the ground arrives four seconds late".

### Weather is a mechanic, not a filter

A rain overlay is easy and worth nothing. Each of the eight conditions carries
three numbers the *simulation* reads — how much is out, how close it lets you
get, and how far you can see — so fog is the best watching in the game and a
storm is the worst, deliberately. The flush scale is applied once, in
`WatchGame.disturbanceAt`, because it is a property of the air between you and
the animal rather than of the animal; that is the same arithmetic as scaling a
thousand species' flush distances and it happens in one place. The biome weights
the roll, so walking north is a change in the weather as well as in the trees,
and every number is interpolated across a 26-second transition so rain arrives
as a thickening drizzle rather than appearing.

### Fish live everywhere now

`TerrainField` floods anything below the water line whatever biome it is in, so
a canyon has pools and a desert has an oasis. The `FISH` family named seven
biomes, which left thirteen worlds' worth of water with nothing living in it —
invisible until there was a reason to dive to the bottom of it, and then the
emptiest place in the game. Both swimming families now span the biomes that have
water in them, which is all of them. This does not make fish common everywhere:
`AnimalRegistry.biomesFor` gives each *species* a slice of its family's range, so
what widened is which fish you find where.

---

## 7b. The third round

> Four asks, from playing it again. They turn out to be one ask: **the things
> in this game were words rather than objects.**

### An item is a thing now, not a category

`ItemModel` drew **one model per `Forage.Kind`**, tinted from the item's key's
hash. That was a fair trade while the only place an item appeared was on a
feeder twenty metres away — and it stopped being fair the moment items started
lying at your feet, sitting in your hand and appearing as a picture beside their
own row. Forty berries drawn as the same three spheres in forty shades is a
placeholder, and the hash made it worse than that: it guarantees only that two
keys *differ*, which is how a blueberry came out plum-red and a snowberry came
out maroon.

Every key in `Forage` now has a solid of its own — an acorn in its cap, a cone
of stepped scales, a beetle with six legs, a bottle with a neck and a stopper, a
trowel with a blade — still built from `Shapes`' five primitives and still
inside a **150-triangle budget** per item, which `ItemModelTest` holds them to.
The colours are a table with the hash as a fallback, so an item added tomorrow
draws in a sensible colour without anybody coming back here.

Three things had to be fixed underneath it:

* **`Shapes.blob` could not face anywhere.** Every other primitive takes a yaw
  and the octahedron did not, so a fish that placed its head, tail and eyes
  along its facing direction and used a blob for the body in between got a body
  lying across all three: a snout coming out of a flank. There is now a yaw
  overload, turned to match `box`'s convention.
* **`Shapes.blade` spreads its base along the direction it is given and puts its
  tip wherever the lean says** — so a caller passing the same direction for both
  gets three collinear points and draws *nothing*. Every leaf, spine, reed and
  wing wants the base across the lean; `ItemModel.spike` expresses the quarter
  turn once.
* **A fish is not an octahedron.** A blob's plan view is a rhombus, and a
  satchel portrait looks down at about twenty-five degrees, so every fish was a
  kite with a fin on it. The body is boxes now, which is what every other animal
  in this world is made of.

### Foraging happens to something you can see (`watch/Litter`)

`WatchGame.pick` used to find nothing in front of you, wait out a cooldown, roll
against `Forage.underfoot` and announce that you had picked up a fallen branch —
from a patch of grass with nothing on it. There was no branch. There was no
reason to walk toward anything, and no way to tell a shingle bank with quartz in
it from one without except by standing on both and pressing <kbd>E</kbd> for a
minute.

The roll moved from the key press to the world generation. `Litter` is `Flora`'s
arrangement applied to the floor: each five-metre cell hashes out one candidate,
and what is lying there is chosen from **the same table foraging always used** —
the region's materials, the surface actually underfoot, and the biome's seeds.
Same odds, same catalogue; what changed is that you can see it, walk to it, and
pick up the particular thing you were looking at. About one thing per hundred
square metres, which `LitterTest` pins from both ends.

Generated, never stored, for `Flora`'s reasons — so the floor of a world costs
nothing on the wire and two players a week apart find the same stone. The one
thing that *cannot* be derived is whether somebody has already picked a piece
up, so that travels: `WatchGame.takenLitter` rides on the world sync, which is
where a pulled crop and a moved boat already ride. The scene takes a piece off
the ground the instant it asks for it rather than a round trip later, and the
next sync corrects it if the host disagreed.

It is drawn in the **moving** mesh rather than in a chunk's static flora, and
that is not laziness: a picked-up branch has to be gone this frame, and a chunk
is re-meshed when its level of detail changes and not when somebody stoops. The
sweep that finds what is nearby costs a few hundred generator samples, so it is
redone every four metres of travel rather than every frame, and its radius is 40
m on a card and 22 m through the painter — the same trade, for the same reason,
as the six-chunk view radius.

`picked` is also **saved** now. It never was, which was invisible while
everything it held was a bare bush; coming back to a camp you had cleared to
find every branch lying there again is the world contradicting itself.

### The highlight glows

A ring was enough while everything you could pick up was a bush or a tree — a
metre across and hard to miss. A quartz pebble in the shingle is eight
centimetres and the colour of the shingle, and an outline round it is an outline
round nothing anybody has spotted yet. Four filled discs of decreasing alpha
under the ring cost four draws and make the *thing* light up. The ring's
minimum size came down with it: the floor was 0.25 m, wider than the acorn
inside it.

### The cooking screen takes a mouse

Two scrolling columns, cursors, windows, bars — and the only things that moved
any of them were the four arrow keys, in a game whose every other verb is on the
mouse. Hovering a row now selects it, clicking does what <kbd>Enter</kbd> does,
the wheel scrolls whichever column the pointer is over (independently of the
cursor, because reading a list is not walking it), the bars drag, and there is a
✕. The build screen took the same treatment.

Two details carry the whole feature:

* **Hovering only counts when the pointer has moved.** A mouse resting on the
  desk otherwise re-selects the row under it every frame, so ↓ moves the cursor
  for one frame and it springs back.
* **One layout, computed once.** `WatchScene.SatchelBox` is what draws the rows
  *and* what hit-tests them. A panel whose hit boxes are worked out separately
  from its drawing is a panel that selects the row above the one you clicked, on
  some window sizes and not others.

And the walk discards the pointer travel spent in a panel, because the walk
steers on the pointer's *motion*: without that, the distance covered choosing a
recipe is banked and spent all at once on the frame the panel closes.

### And a picture in the satchel (`watch/render/ItemPortrait`)

`AnimalPortrait` for things rather than for creatures, and deliberately the same
shape of class — same offscreen render, same noon light, same bounded cache —
because the two answer the same question and a player should not be able to tell
they are two pieces of code. The model is built once at unit scale, its bounding
box read off the mesh, and rebuilt at whatever scale frames it, so a coconut and
a beetle are framed identically without anybody saying how big either is.

Two numbers were measured rather than chosen. The camera sits a third of the way
up rather than at the eleven degrees that framed an animal nicely: half this
catalogue is flat — a feather, a plank, a chip of bark — and at eleven degrees
they are seen edge-on and read as a scratch. And the view yaw is set against the
camera's bearing: at the angle it started at, every elongated model in the game
ran within twelve degrees of the line of sight, so a trout was a thumbnail of a
trout's nose.

The one item that does not fit is the fishing rod: 1.35 m long and 4 cm thick,
which framed whole in a 22-pixel row is a shaft a third of a pixel across. An
item taller than four times its width is framed on its width and anchored on its
foot, so what the row shows is a butt, a cork grip and a tapering shaft. Nothing
else in the catalogue reaches the cap.

---

## 7c. The fourth round — the walk and the row

> One ask: *the walking and rowing look choppy and a bit glitchy.* It turned
> out to be five separate faults that happened to land on the same two verbs,
> and only one of them was in an animation.

### The clock was the frame counter

Everything that animated on its own was phased off `frame`, the count of frames
*drawn*: a boat bobbed at `frame * 0.006`, another player's legs ran at
`frame * 0.02`, a feeder turned at `frame * 0.004`. A cycle counted in frames
runs at half speed on a sixty-hertz screen and at double on a
hundred-and-forty-four, changes rate whenever the view gets cheaper or dearer,
and hitches with every dropped frame — which is exactly the "choppy" that
cannot be pointed at, because each animation is perfectly smooth and it is the
clock beneath them that is not.

There is now one clock, `WatchScene.animClock`, in seconds, advanced by the
fixed simulation step and read at draw time with the frame's own `alpha` added
back (see `FrameCadence`, which exists to make that fraction available). Every
self-running cycle is a rate against it.

### Everybody else moved in fifty-millisecond hops

Positions arrive at `WatchProto.TICK_RATE`, twenty a second. Drawn straight
from the last one, another player advances in forty-centimetre jumps five times
a second, for ever. `watch/render/Gait` eases a drawn position toward the last
one that arrived — smoothing rather than extrapolating, because the lag it
costs (a third of a metre, less than the snapshot's own age) is invisible and
the correction that guessing costs is not. A walker further than six metres
from where they were has teleported and is placed rather than skated.

**And your own body was on the same twenty-hertz path.** In third person the
walker drawn for you came from the view — that is, from the last position sent
to the host — while the camera following it moved every step, so the two
disagreed five times a second for as long as you walked. Your own row is now
taken from the live position, which is where the boat under you was already
being taken from.

### The gait was a wheel driven by a step function

The phase advanced at `speed * 0.55` cycles a metre and the swing was scaled by
the raw speed measured over one step. That figure goes from nothing to full
walking pace in a single step when a key goes down, and drops to zero for one
step whenever a move is refused — rowing into a bank does it every step — so
the limbs snapped between standing still and full swing several times a second.
The speed the animation reads is now eased over about a tenth of a second, and
the cadence comes from `Gait.cadence`, which lengthens the stride as the speed
rises the way a person does: a sprint is no longer a walk cycle played at four
times the rate.

### The legs never turned

`WalkerModel`'s limbs were upright boxes *slid* along the arc a thigh sweeps,
never rotated, so at any part of the stride but the middle a leg was a rectangle
floating beside a boot with a hip somewhere above neither. `Shapes.strut` — a
box between two arbitrary points, the sixth primitive — is what was missing;
legs and arms are now a thigh and a shin, an upper arm and a forearm, pivoting
about a hip and a shoulder with a knee that folds through the swing.

The hips are no longer given a height, either: the legs are posed first, the
lower boot is put on the ground, and the body is hung from that. A leg at an
angle does not reach as far down as one hanging straight, so a figure swung
about a fixed hip sinks into the ground at mid-stride and floats at the ends of
it — and solving it the other way round produces the rise and fall of a real
walk for nothing, in step with the stride by construction, fading to zero as the
walker slows because the swing it comes from does.

### Nobody was rowing

The largest fault, and not an animation at all: **there was no rowing.** A
player in a boat was drawn as a standing figure whose legs were driven by the
boat's speed, and `Boats.ROW_SPEED` is faster than a sprint — so what third
person showed was somebody sprinting on the spot inside a hull, at five paces a
second, with the oars stowed tidily along the rail beside them. In first person
the same speed drove the head bob and the hand sway, which shook the camera.

Now:

* `watch/render/RowStroke` is one description of a stroke — where the hands
  are, how far the blade is out of the water, how hard the boat is being driven
  — as three curves that meet with matching slopes, so nothing ticks once a
  stroke at the joins.
* `BoatModel` swings the oars about their locks from it, blades buried through
  the drive and lifted clear through the recovery, and noses the hull down on
  the drive. A boat nobody is in keeps its oars shipped, which is still how you
  tell one is free.
* `WalkerModel.rower` sits the figure on the thwart with its legs on the
  floorboards and solves its arms onto the handles — `BoatModel.handle` is the
  single source of where those are, so the grip cannot drift off the oar.
* In first person both hands work **together**, out on the drive and back on
  the recovery, on the same clock as the oars swinging in the water in front of
  you — rather than swinging against each other at a walk driven by nine and a
  half metres a second.

They were built on the real handles first, in world space, and that had to be
undone: `EyeCamera.NEAR` is eight tenths of a metre and the handles a seated
rower actually holds are about that far from the eye, so the elbows landed
*inside* the near plane and the painter path sliced the arms in half. What
carries a stroke from inside it is the rhythm rather than the millimetres, so
the view model keeps the camera's basis — the same reason `HAND_FORWARD` is
derived from the near plane rather than chosen.

Because the rower faces the way the boat is going — this game moves you where
you look, everywhere — the stroke is a push rather than a pull. That is a real
technique, and it is the only version that agrees with the direction of travel:
the handles are inboard of the locks, so hands going forward swing the blades
aft, and blades sweeping aft are what push a boat along.

---

## 7d. …and the swim

> The third of the three ways to get about, and it had the same fault the row
> did, for the same reason: **there was no swimming.**

A player in the water was drawn as a standing walker, upright, legs striding at
whatever speed they were making. Crossing a lake was somebody marching along
the bottom of it with their head in the air; a dive was the same figure
marching downwards; and the first-person hands were the walking ones on a
slower clock, so what you saw from inside was a person striding along in front
of your face while your body swam.

### One angle, and everything else follows

A swimmer is `WalkerModel.swimmer`: **the same figure as the walker, hung from
its hips and tipped over**. Every joint stays at the proportion of the height
it occupies when standing — the hips at 0.47, the neck at 0.86, the head at
0.94 — so `swimPitch` of a right angle draws the standing pose exactly, and
somebody wading out of their depth *tips* into a swim rather than cutting to a
different model. Treading water and swimming are not two poses; they are one
pose at two speeds, interpolated by `swimDrive`.

The hips are the pivot, and that is load-bearing rather than arbitrary. The
game floats a swimmer with their feet `FLOAT_DEPTH` under the surface, which is
chest-deep for somebody upright — so a body laid down about its hips puts the
head at the waterline and everything below the shoulders under it, without the
model being told where the water is. Turned about the neck instead (which was
tried first, and is in the history) the same swimmer floats with their whole
chest in the air, swimming through the sky.

Under water the body lies along the way they are looking, because under water
that is the way they are travelling — `WatchScene.walk` already steers a
submerged player by their pitch. At the surface it settles thirty degrees off
horizontal instead: head and shoulders out, body trailing down behind, which is
both what a breaststroker looks like and the only arrangement that keeps the
body in the water given where the eye is.

### Breaststroke, and why

`watch/render/SwimStroke` is one stroke as five curves, written like
`RowStroke` and meeting at their joins with matching slopes. It is breaststroke
because this game's swimmer has to **breathe**: a player at the surface is one
whose head is out and whose air is coming back, and a stroke that buries the
face and turns it aside once a cycle contradicts the breath meter. It also
reads at distance — both arms doing the same thing is a wide sweep and a narrow
glide, where a front crawl at a hundred metres is two pixels flickering.

Arms pull while the legs trail; legs kick while the arms recover; the head
lifts to breathe on the pull and only when the head is actually out of the
water. Both halves at once is the commonest way to draw a swimmer wrong and it
looks like somebody falling downstairs.

### Three things underneath it

* **The cycle is clocked on distance through the water, not ground covered.**
  A diver going straight down covers no ground at all, and clocked on ground
  would hang motionless all the way to the bottom. `Gait.Cycle.SWIM` measures
  in three dimensions; the other two stay on the flat, so a walker downhill is
  not sprinting.
* **It never stops.** Every other cycle in the game is still at a standstill,
  because a walker who stops walking stands there. A swimmer who stops swimming
  sinks, so `Gait.swimRate` has a floor under it and somebody treading water
  sculls, gently, about a stroke every three and a half seconds.
* **Who is swimming is worked out from the ground, not from the wire.** A
  walker is swimming when their feet are off the bed in water deep enough to be
  out of their depth — which is the distinction the game already makes, and the
  only one that is right at both ends: wading in the shallows is walking, and
  the moment the bed drops away it is not. The client generates the same terrain
  the host does, so it can see the bed under anybody in the party without a byte
  being sent about it.

Two smaller things had to be fixed to draw it. `Shapes.strut` now takes the
direction its cross-section is squared to, because a chest is wider than it is
deep and a strut left to choose its own reference flips it on the way through
vertical — which is exactly what a swimmer diving does. And a hat sits *on* a
head rather than above it in world terms; for everybody standing up those are
the same sentence, and for a prone swimmer the brim floated off the side of
their head and followed them across the lake like a small yellow raft.

---

## 7e. Jumping, and the key it was hiding under

> Asked for outright: a jump on Space, animated, with crouching moved off it.
> The second half turns out to be why the first half was missing.

### The key

`GameAction.JUMP` is Space, in this engine and in every game that has ever had
one — and the Field Guide read its **crouch** off it. A player who pressed the
one key that means "jump" everywhere got a squat, and there was no way to bind
a jump because the action that meant jumping was already spoken for. Crouching
now has `GameAction.CROUCH` of its own, on Control, which was free across the
whole enum. Both keys now do what their labels say, and the controls menu picks
the new one up for free because it is built from the enum.

It also fixes a comment that had been wrong since it was written. Swimming down
was on **Sprint**, under a note explaining that crouch "is the key a player's
hand is already reaching for when they want to go lower" — which it could not
be, because crouch *was* jump and jump was already how you swim up. Sinking is
now on the crouch key and Sprint is free.

### The arc

`JUMP_SPEED` and `GRAVITY` are chosen from the other end: eighty centimetres is
a boulder or a fallen trunk, which is what there is to get on top of in this
world, and two thirds of a second in the air is long enough to read as a jump
and short enough not to interrupt a walk. There is no air control and no fall
damage — this is still a game about looking at things.

Walking off a ledge is deliberately *not* a fall: the ground under a walker is
followed by an eased height (`STEP_SMOOTHING`), which is what stops a
two-metre heightfield grid from jolting the camera at every sample, and a
threshold that turned a steep slope into free-fall would fight it. You go
airborne by jumping, and a jump off a cliff falls the whole way down.

### The pose

`WalkerModel.Leap` is three numbers — how far off the ground, how fast rising,
how much of a landing is still being absorbed — and every one of them *blends*
rather than switches. Legs tuck under on the way up and reach for the ground on
the way down; arms go up with the push and come down and out with the fall; and
the landing folds both knees while the feet stay planted, so the dip comes out
of the same geometry that plants a walker's boots rather than out of a separate
number pushing the body down. The camera dips with it.

The plant itself is switched off with the ground it needs: hanging the body
from its own lowest foot is right when there is a floor and turns a tuck into a
squat when there is not.

Nothing about jumping goes on the wire. A remote jump arrives as a `z` that
went up and came down, and the ground under it is something every client
generates for itself — so `Gait` derives the whole pose, landing included, from
the position alone, the same way it derives swimming.

### One latent bug, found by a test

`JumpTest` sweeps the pose across a whole leap and asserts that no single step
moves the figure much further than its neighbours — a discontinuity is not a
large step, it is a step far larger than the ones either side. It found one, and
not in the jump: **every limb in the game flipped its cross-section** as it
swung through vertical, because `Shapes.strut` chooses its own reference axis
and swaps it near the pole. Square limbs made it invisible, so it had been there
through the walk, the row and the swim. All of them now square their limbs to
the body's own across axis, which a limb swinging fore-and-aft can never be
parallel to.

---

## 7f. Trading posts, and the page they turn

> Asked for: shops in the wild that trade in points and reset the seen-animals
> list; points spendable on materials; an intricate prebuilt structure with a
> detailed NPC keeping it; and the guide to keep every animal you have ever
> seen while paying you for them again. The last clause is the whole feature —
> the rest is the shop that delivers it.

### The record and the page were one thing, and had to be two

`FieldGuide.points()` was a **function of the record**: the sum of every entry's
rarity, recomputed on every call. That is the natural thing to write and it has
two consequences that only show up the moment anything can be bought.

A total derived from a list **cannot be spent**. Spending it would mean deleting
entries, and a guide that deletes entries is not a guide — the first sentence of
that class has always been that nothing is ever removed from it. And a species
already in the book is worth nothing for ever, so the four hundredth hour of a
walk is one where almost everything on the ground is worth looking at for its own
sake and for nothing else. The completeness of the record was what made the game
stop paying.

So the book keeps two things where it kept one:

| | |
|---|---|
| the **record** | `FieldGuide.first` — every species ever seen, permanent, and what every page of the book is drawn from |
| the **page** | `FieldGuide.tally` — the species already *scored* since the last time a page was turned, which is what decides whether a sighting earns anything |

A keeper at a trading post **stamps a new page**: the tally is emptied, the old
page is closed and filed in `volumes()` with its date, its count and the keeper's
name on it, and every animal in the world is worth its rarity again — while the
record of having seen them is untouched. Points are a **balance** now,
`earned − spent`, because a shop takes them.

Three small consequences worth stating, because each of them is where the feature
would otherwise have been invisible:

* **The crosshair has three states, not two.** "Something new", "worth 8 points",
  and "click to point it out". Without the middle one a stamped page is a number
  on a panel that nothing in the world reflects.
* **A `Spotlight` carries what it paid.** One integer beside a species name the
  message already contained, which is the whole of what a client needs to keep
  the party's balance in step — instead of the tally coming back down the wire on
  every sighting.
* **A keeper will not stamp a blank page.** That one rule is the economy's floor.
  Without it, walking up to a post with an animal in front of you is spot, stamp,
  spot, stamp, for as long as you can be bothered to press two keys. With it, a
  stamp is only worth asking for once you have filled a page — so the loop is
  "go and find things, then come back", which is the loop the game already is.

### The posts are generated, not placed (`watch/Shops`)

The boats' argument, and the same answer. A shop a player builds is a menu; a
shop a *server* places cannot exist in a world with no edge. So a trading post is
a pure function of position and seed, which makes it genuinely findable, identical
for every player, and free on the wire — **nothing about a post ever travels**,
because nothing about one can be changed. The two verbs on the wire carry an
intention (`buy`, `stamp`) and what comes back is the two things that actually
changed: a satchel and a ledger.

The plane is cut into 430 m cells; each offers a post, and that offer only becomes
one where the ground will take it. A post wants what a post has always wanted:

* **a road** — the siting probe walks a line across the cell until it stands on a
  `TrailNetwork` path, because a shop nobody walks past is a shop nobody finds;
* **flat, dry ground** — a rigid box of timber cannot be pitched on a cliff;
* **to be beside the path rather than across it** — set 5.6 m off the centre line
  on whichever side is clearer and turned to face back at the trail, which is why
  you come over a rise and see the sign side-on with the counter facing the road.

Measured over twelve worlds: **one post per 1.31 km²**, 526 m on average from
spawn to the nearest, and the closest two anywhere 177 m apart. That last number
is `CELL_INSET` doing its job — the probe walks far enough to leave its own cell,
so a post is confined to the middle of it and two neighbours can never be built
through each other.

**Nothing wild grows in the yard.** `Flora` and `Litter` both ask
`Shops.clearingAt` before they put anything down — the same rule they already
apply to trails, with a bigger footprint, because somebody cleared this ground
before they built on it. That query runs once per candidate plant, hundreds of
thousands of times as a wood is meshed, so the generated post for a cell is cached
the way `TrailNetwork` caches its edges. It costs **80 ns warm**, and it checks
*one* cell rather than the ring of nine: the inset guarantees no clearing can
reach over a boundary, so the ring would be nine times the work and would generate
eight cells' worth of siting probes the first time a worker touched new ground.

### What points buy (`watch/Trading`)

Materials, mostly, and that is the design: points come from looking at animals and
what they buy is what you would otherwise have had to walk for, so watching and
gathering are two currencies for one economy. An afternoon spent creeping up on
waders pays for the planks a hide is built out of, and a hide is what gets you
nearer the next wader.

Nothing here is a shortcut *past* the game. A post sells the raw material and
never the thing made from it: quartz, sand and a ground lens are on the shelf and
a **spyglass is not**, so the deepest crafting chain in the book is still the only
way anybody gets a glass. `ShopsTest` asserts that, over every shelf in eight
worlds.

Each keeper carries six to nine lines out of the catalogue, chosen by the post's
own hash and weighted by the country it stands in — a post on a shingle spit has
sand and quartz, one in a rainforest has vine, one in the crystal highlands has a
lens — with a markup of its own on top. Three staples are always there
(branches, rope, a feeder), because somebody who walked two kilometres should
never arrive to find nothing they can use.

### The building (`watch/render/ShopModel`)

Everything else standing in this world is either something a player placed one
piece at a time or something the generator scattered, and a shop is neither. It
has to read as **prebuilt** — as a thing somebody else made, on purpose, before
you got here, and made properly. A hut assembled from four of the player's own
wall pieces would read as a player's hut, which is exactly the wrong impression:
the point of the post is that there is somebody in this wood who is not you.

So it is a carpentry drawing rather than a box: stone footings, a raised deck of
individually laid boards with a step up to it, corner posts carrying plate beams,
diagonal braces, a pitched roof on real rafters laid in overlapping courses with
the eaves oversailing the counter, a boarded back wall of shelves, a counter with
a ledger, an inkwell, a set of scales and a lantern on it, and a yard with a
woodpile, crates, a barrel, a hitching rail and a lantern post. Around 1,670
triangles — more than any other single object in the game and about a fifth of one
chunk of hillside.

**The wares on the shelves are the post's actual stock**, drawn through
`ItemModel` — the same models the satchel, the ground and a feeder's tray use.
That is not decoration: a player can read what a keeper sells off the shelf before
opening anything, two posts a valley apart visibly differ, and the shelf and the
panel cannot drift apart because both read one list.

It is drawn in the **moving** mesh rather than in a chunk's static flora, which is
`Litter`'s reasoning turned the other way up: a chunk is re-meshed when its level
of detail changes and not when a sign swings, and splitting one building between
two meshes to save a few hundred triangles would let the shed and the person in it
disagree about where they were. Four ranges rather than one, because a post is not
one object — the shed carries 220 m, the keeper 90, the wares 34, and the keeper
stops turning to look at you at 22, which is about where a person stops noticing.

### The keeper (`watch/render/KeeperModel`)

Every other figure in this game is a `WalkerModel`: a coat, a head, four limbs and
a hat, tinted per player, and exactly right for somebody two hundred metres away
whom you will mostly see running. The keeper is the one person in the world you
stand a metre from and *look at* while you decide what to buy, and at a metre a
tinted walker is a mannequin.

So it is the same skeleton with about three times the parts on it — a coat with a
skirt and facings over a shirt, an apron, a belt with pouches, a scarf whose end
swings with the weight shift, a face with a jaw and a nose, hair, an optional
beard, optional spectacles with arms back to the ear, one of three hats, and a
pencil behind the ear — and every one of those is chosen once from the post's own
id, so a keeper looks the way they looked last week. So does their name, their
trade, what they say when you walk up, and the small tame animal from their own
biome sitting on the counter beside them, drawn through `AnimalModels` because it
*is* one.

**They are alive without going anywhere.** Breathing, a weight shift every seven
seconds, and once every thirteen a lean in to write in the ledger — a smooth pulse
rather than a switch, for the reason every other cycle in this game is eased. And
they **look at you**: the head turns toward whoever is nearest, limited to what a
neck actually does. That one thing does more work than the other three together.

### Debug mode had to gain a row, and that is the interesting part

`Debug`'s whole design is that **every cost in this game is a `has` and a `take`
against a `Satchel`**, so one lens over that one class covers the costs that do
not exist yet. A trading post's prices are the first cost that breaks it: they
come out of the guide's balance. So `Debug.Power.POINTS` exists, and adding it
cost exactly what §4.4 said a new power should — one row in the enum, which is
what puts it on the readout, and one `if (player.debugging())` in
`WatchGame.buy`, which is the feature. It buys rather than granting: the goods
still go in the satchel and the line still appears in the log, so a host
checking what a shelf hands over sees what a player would.

What it deliberately does **not** do is stamp a page. Debug mode is a lens over
what a player can afford; turning the page is a thing the guide *records*, and a
lens does not write.

### Three bugs, and the one that was already there

* **The keeper's animal was placed from the wrong origin.** The counter is
  measured from the building's centre and from the ground; the keeper stands
  behind it and on the deck. Using the counter's own two numbers as offsets from
  the *keeper* put the jackdaw a metre and a half out over the yard at a height
  nothing was standing at. `BEHIND_COUNTER` is one number now, read by the scene
  that places the figure and by the model that puts the ledger and the bird on
  the counter. A test measured it: the keeper came out 2.67 m wide.
* **The roof was a lid.** A metre of rise over the three and a half the front
  slope covers is sixteen degrees, which from anywhere but directly in front
  reads as a flat plane on four posts.
* **`aim` resolved what was in reach only when nothing was under the crosshair.**
  That was fine while the reach was a berry bush and the priority was about which
  *label* to print — and it was quietly false about everything else, because
  `inReach` is also what E acts on. Standing at a trading post with a chaffinch in
  view, the crosshair said "Banded Finch" and the reach key did nothing at all.
  What is in reach is now always worked out and only the line of text gives way.

---

## 7g. Tracks you leave behind

> Asked for: temporary tracking trails that follow players — a slight pathway
> created by players walking over terrain, lasting ten minutes.

### Why this could not be the trail system

`TrailNetwork` already draws paths through the wood, and reusing it was the
first idea and the wrong one. Everything that makes it work is that it is a
**pure function of position and seed**: two players a kilometre apart agree
about a path neither has mentioned, a chunk can be thrown away and rebuilt
byte-identical an hour later, and the terrain field can afford to ask it a
question at every vertex it generates. None of that survives contact with a
path that depends on *where somebody has been*.

So this is a second mechanism that shares nothing with the first but the word.
`TrailNetwork` cuts the ground; `TrackField` only records feet, and
`TrackMesher` only shades it.

### The ground does not move

The tempting implementation is to press the terrain down where somebody walked.
It fails twice over, and both are structural rather than a matter of tuning:

* **It is what makes the world shared.** `TerrainField.heightAt` has no memory
  by construction — that is the sentence the whole generator is written under.
  A height that depended on history would have to travel, and a world with no
  edge cannot send its own shape.
* **It costs the frame it would take.** A chunk is 289 height samples, its
  materials, its slopes, its flora and its grass. Re-generating whichever one a
  player is standing in every 1.4 m of walking is the most expensive thing this
  game can be asked to do, and a party of eight would be asking for eight of
  them at once.

A decal has neither problem, and it is also the more honest picture: what a
walker leaves on a hillside after ten minutes is a mark on it, not a trench.

### The three numbers

| | |
|---|---|
| **ten minutes** (`LIFETIME`) | the ask. Against this game's clock it is ten minutes of the world as well, because `WatchClock` *is* the wall clock — there is no scale factor to get wrong |
| **a third of it at full** (`HOLD`) | a track that starts fading the instant it is laid is palest where you have most recently been, which is backwards, and reads as a rendering fault rather than as weather |
| **40 % alpha** (`FRESH_ALPHA`) | "slight", as asked. Because two passes composite, the way to make a much-used route look much-used is to keep one pass faint and let the arithmetic do the rest: one crossing is something you notice when you look for it, four are unmistakable |

### Three ways of being somewhere that leave nothing

Refused by the scene rather than by the field, because they are facts about how
somebody is moving rather than about the record: **a boat** (oars do not tread),
**the water** (a footprint under a lake is not a thing), and **the air** (a jump
marks where it lands, not what it passed over). Everything else is a stride, and
the field decides which strides are far enough apart to be prints.

The fourth case is not refused anywhere and does not need to be: two positions
more than `MAX_GAP` apart are a *hole* in the record rather than a stride, so a
boat crossing, a save reopened, a party member first appearing in the snapshot
and a client that dropped a frame all read the same way — as a break in the
chain, not as a kilometre of trail nobody made.

### The painter needed something new, and it is one line

Everything else in this game sorts by depth and comes out right. A decal does
not: it is *inside* the triangle it is drawn on, and the painter sorts triangles
by their middles. A track on the near half of a ground quad is drawn after it
and shows; a track on the far half is drawn before it and does not — so half the
trail is missing, in stripes, on the grid the ground happens to be meshed at.
Counting the pixels one frame's trail actually reaches, over a walk through a
bamboo thicket: **27 742** at no bias and broken into disconnected blocks,
48 219 at 0.8 m and whole with notches in it, **53 674** at 1.6 m and whole.

`Mesh.sortBias()` is the fix and is deliberately as small as it can be: a
number of metres that the painter subtracts **from the sort key alone**. The
colour, the fog and the culling all keep the true depth, and the GPU path
ignores it entirely because a depth buffer already compares the right thing. The
cost is stated where the field is: something standing on the same ground
*nearer* than the decal by less than the bias — a blade of grass beside the path
— is painted under it rather than over it. At 40 % alpha that is a faint tint on
a few blades, against half the trail being invisible.

### Rebuilt four times a second, not sixty

The sheet is thousands of quads whose corners each cost a bilinear read of the
heightfield, and almost nothing about it changes between two frames: it fades
over ten minutes. So it is rebuilt when the player has walked `TRACK_RESTEP`
metres or `TRACK_REFRESH` seconds have passed, and the *same mesh object* is
handed back in between — which is what makes a card re-upload the buffer four
times a second rather than sixty, since `Mesh.revision()` is what it keys that
decision on. It is the same arrangement, and the same reasoning, as the litter
sweep beside it.

---

## 7h. Maps, and the board they join on

> Asked for: maps a player can mark with a pen, spanning the render distance
> from where they were drawn, with icons for shops and other points of
> interest, filled in on creation, kept in the inventory, opened by clicking,
> renameable at will, always showing every player's position relative to the
> map even from off its bounds — and a map board that can be built to combine
> maps and display them for everyone. Debug mode only, for now.

### A map is four numbers, and no picture

The one decision the whole feature rests on. The ground here is a pure function
of `(seed, x, y)`, so a picture of a square of it is a pure function of the same
three numbers — which means a map does not need to carry its own paper. A
`Chart` is a centre, a radius, a name, the icons that were standing when it was
drawn, and whatever somebody has since written on it; `ChartImage` paints the
rest from the seed on whichever machine is looking.

That is the argument `Shops` makes about trading posts, taken one step further,
and it pays three times over:

* **On the wire and on disk.** A walk with fifty maps in it costs a few
  kilobytes of save and rides in the world sync beside the grove.
* **Between players.** Two copies of one map agree *pixel for pixel*, because
  both were painted by the same function of the same ground rather than
  transmitted.
* **On a board.** Two maps of neighbouring country meet with no seam, for the
  same reason — there is no join to blend, only two windows onto one function.

The cost is a fifth of a second of noise per map, which is why `ChartImage`
bakes on a daemon worker and the panel draws "Surveying…" for a frame or two.
That is the bargain `ChunkStreamer` already makes with the ground itself.

### The size is the machine's to know, and the grid is not

"Span the render distance" is a fact about the *client*: how far a machine can
see is its graphics card and its detail slider, and `applyDistanceSettings` is
where that number is decided. No host can discover it, so it travels with the
request and `WatchGame.drawMap` clamps it.

Clamps it, specifically, up a **ladder of doublings** (`Chart.RADII`), and then
snaps the centre to the grid that size defines (`Chart.snap`). Both exist for
the board:

* maps whose spans are the same or a whole multiple of each other can tile;
* centres on a common grid make them actually meet, rather than land a hand's
  width apart, which is the one arrangement that looks like a mistake.

Snapping moves the centre by up to half a radius, so `radiusFor` asks for
**twice** the reach before rounding up. The arithmetic is worth stating because
it is the whole of the promise: the nearest edge is never closer than
`radius − radius/2 = radius/2`, and with `radius ≥ 2 × reach` that half-radius
is itself at least the reach. Everything the machine currently has loaded is
inside the square, in every direction, from wherever the key was pressed — and
`MapsTest` sweeps a player right across a grid cell to say so rather than
checking the middle and hoping.

### Filled in means finished, not revealed

There is no fog to walk off, and that is a design decision rather than an
omission. Fog of war belongs to a game where the map is the reward for
exploring; this is a game where exploring is the reward for exploring, and what
a map is *for* here is finding your way back.

So `Survey` runs once, at the moment the key is pressed, over everything the
world already has — the generated trading posts (on the map whether or not
anybody has found one, because a map that only shows the posts you have already
walked to cannot tell you where to walk), the buildings clustered so a treehouse
is one icon, the feeders, the plantings, the boats, the first sighting of each
species, and the local maxima of a coarse height grid for the high ground. Then
it is frozen.

Which makes a map **age**: the post is on it for ever, the feeder is on it long
after it rotted, and next week's camp is not on it at all. That is the
difference between a map and a minimap, and it is the only reason to draw a
second map of the same place.

### Ink is in metres

Every stroke and every note is stored in world coordinates rather than as a
fraction of the paper. It costs nothing — the panel converts once either way —
and it is what lets a board draw six maps' worth of annotation in one space
without transforming anything by whichever map it came from. A stroke is also
sent whole, when the pen comes up, rather than a point per frame: a stroke is
*one thing a person did*, so it is one thing to rub out and one thing to
attribute.

### Combining maps is laying them out

A board does not merge its maps into a new map, and there is nothing for the
player to line up. Its paper is the union of the squares pinned to it, every map
is drawn where it *is*, and the picture that comes out is continuous. Pin a
second map and the board is bigger. That is the entire interaction — no join,
no orientation, no order — and it works because none of the maps was ever drawn
relative to itself.

`MAP_BOARD` is the eleventh `BuildPiece` and the first that is more than its
box: building one registers a `Cartography.Board` twinned with the placement, so
a board is a *place that holds something* rather than a wall the maps happen to
be near.

### The board wears its maps

A board whose maps only exist inside a screen is a noticeboard with the notice
in a drawer. The whole reason a party builds one is that **anybody standing in
front of it can see the map** — so `BoardImage` bakes the combined map and
`WatchScene.boardFaces` lays it on the timber as a grid of flat facets, one per
cell, in the colour of the ground it stands for. That is what the terrain
already is, so a map on a board reads as part of this world rather than as a
photograph hung in it. Opening the panel is for the small print.

Three things make it work and each is a decision:

* **Baked six times over, then averaged down.** A pen stroke is a line two
  pixels wide, and a line sampled at one point per facet either lands on a facet
  or does not — a scribble comes out as a dotted rash. Box-filtered, the facets
  a line crosses are tinted toward its ink and the mark reads as a mark. Icons
  and strokes are therefore sized in *facets* rather than pixels: an icon wants
  about two, a stroke wants one.
* **A signature, not an invalidation.** The cache key is which maps are pinned,
  where they are, how big they are and how many marks they carry — built fresh
  every frame and compared. So a stroke somebody draws appears on the timber on
  the frame the ink arrives, with nothing anywhere having to remember to tell
  the board.
* **`WatchMaterial.PAPER`, which is white and grainless.** A card shades a
  fragment as `texture × vertexColour` and the painter uses the vertex colour
  alone. Every other material here is a *surface* whose tile is what the thing
  looks like; a board's face is a few thousand little surfaces each carrying its
  own colour, so what its material has to supply is nothing. Any tile but a flat
  white one would give the two backends two different maps and let a texture
  pack quietly tint every map in the game.

`Shapes.mosaic` is the primitive, and it is lit **once** rather than per facet:
every triangle on a face shares one normal, and a cross product and a square
root a thousand times a frame per board for an answer that cannot change is
exactly the kind of cost that is free until it is not.

Everything on the board is drawn through `MapInk`, which is also what the panel
draws through — the same icons, the same pen, the same world-to-pixel frame.
That is not tidiness: the board is what a party reads at a glance and the panel
is what they open to check, so an icon meaning one thing on the timber and
another on the screen would be worse than no icon.

Seeing a board is **not** behind `Debug.Power.MAPS`, and that is not an
oversight. The mode withholds the making of maps while their price is undecided;
a board is a thing a host has already built and pinned, standing in the world,
and a board only half the party can see is not a board.

### Off the map is still on the map

Every walker gets a pin turned to their heading; a walker outside the paper is
clamped to the border, drawn as a smaller arrow pointing after them, and
labelled with how far away they are. The Minecraft rule, and the one that makes
a map useful to a party rather than to a person — a map whose markers vanish the
moment somebody walks off it cannot answer the only question a party asks it.

### Why it is a debug power, and what happens when it stops being one

`Debug.Power.MAPS` is the second row the mode has had to grow, and it is a
different shape from the first. `POINTS` exists because a cost escaped the
satchel; this exists because a *decision* has not been made — what a map should
cost a player who is not in debug mode. Rather than guess at a price and ship
it, every map verb opens with the same `if (player.debugging())`, which is
exactly the shape `Debug`'s class note prescribes. Lifting the gate is deleting
one line from each of six methods and adding a cost; the row is the only one of
the four that would *disappear* rather than become free.

One consequence worth writing down: the code is four digits read anywhere in the
walk, and a map's name is text with digits in it. `WatchScene.typingText()` is
what stops renaming a map to "7799 steps to the ford" from turning the whole
feature off mid-keystroke.

---

## 8. Tests

`src/test/java/com/larsons/engine/watch/`

* `TerrainFieldTest` — determinism, biome coverage, water below the line,
  trails that actually flatten, chunk edges that match their neighbours.
* `WatchBiomesTest` — twenty biomes, unique keys, the seven named ones present,
  every one reachable from some climate.
* `AnimalRegistryTest` — ≥ 1000 species, unique keys and names, deterministic
  rebuild, rarity and biome coverage, tameable subset non-empty.
* `AnimalModelTest` — every species builds a model with boxes and a skin; every
  animation state resolves to a pose.
* `BlockbenchTest` — a small `.bbmodel` parses to the right boxes, bones and
  clips, including rotations and keyframe interpolation.
* `GroveTest` — growth stages advance with time; cross-pollination inherits
  traits and produces hybrids.
* `WatchGameTest` — spotting discovers, re-spotting does not; lures attract only
  species that eat them; fishing, cooking, planting and building round-trip.
* `WatchNetTest` — an eight-player session over loopback: join, move, spot,
  spotlight broadcast, build, disconnect; the ninth is refused.
* `WatchClockTest` — the real-clock mapping, both directions.
* `WatchSceneTest` — the mini game is on the launch strip, the scenes register,
  the lobby's menu offers what it should.
* `WeatherTest` — it changes, it never changes into what is already up, a
  desert does not snow and a tundra does, the transition is gradual, and it
  round-trips through a snapshot.
* `BoatsTest` — a world has boats, they are on shorelines, two players on one
  seed find the same ones, and a boat rowed across a lake is on the far side of
  the lake for everybody afterwards.
* `AnimalMovementTest` — over a controlled shoreline: a walker covers ground
  for four minutes and never wades out of its depth, a fish never leaves the
  water or enters the bed, a stranded fish finds its way back, and nothing in a
  spread across the registry ever ends up inside the ground. Then, over the
  real game: a chased animal keeps running rather than stopping at its first
  escape, **nothing is ever drawn walking or running while standing still**
  across a whole population and a player walking a circuit through it, and a
  flushed animal leaves the area. The first two fail on the code as it stood
  before the freeze was fixed, with the numbers quoted above.
* `ChunkCacheTest` — ground walked away from is kept rather than rebuilt, the
  cache honours its ceiling, a zero budget still plays, and a re-mesh bumps the
  revision a backend keys its buffers on.
* `DivingTest` — the server decides who is under water, the breath runs out and
  comes back, an aquatic animal turns up for a diver, and the state reaches the
  view both by snapshot and over the wire.
* `WatchRenderTest` — a frame draws triangles, sorted far to near, and nothing
  behind the camera reaches the target.
* `SpyglassTest` — three claims, in order. It is **optics**: each stop's field
  of view is the true ratio of tangents, the camera takes the narrowest one
  without clamping, and a thing at four hundred metres projects exactly ×N
  larger at ×N — measured on the projection, which is where the extra detail
  comes from, and not on a scaled frame, which would give the same number and
  no more pixels. There is **something to see**: ground twelve chunks out is
  built, and built at the finest level of detail, with a glass on it and does
  not exist at all without one; a chunk being looked at survives eviction and
  is released when the glass comes down; animals turn up past two hundred and
  fifty metres for a glassing player and one of them can be recorded through
  the glass and not without. And you can **make one**: every ingredient is a
  real item, the ground this world generates actually hands out quartz and
  sand, and the two-step chain runs through the game's own crafting out of
  nothing but raw materials. Then the whole thing through the scene a player
  plays — hold the key and the camera narrows, the streamer is pointed and the
  host is told; let go and all three go back; hold it with an empty satchel and
  nothing happens at all.
* `ItemModelTest` — every key in `Forage` builds geometry, no two items of one
  kind are the same model (which is what stops the per-kind placeholder quietly
  coming back), nothing is over the triangle budget, every model stands on the
  point it is given and is about the size of a hand, a blueberry is blue and a
  snowberry is white, an unknown key still draws something, and every item has a
  picture that is neither blank nor identical to another item's.
* `LitterTest` — the floor is a function of the seed and nothing else, two
  different worlds have different floors, everything lying about is a real item,
  there is enough of it to be worth walking for and not so much that the wood is
  a jumble sale, nothing floats, ids are stable and distinct and recognisable —
  and then through the game: what goes in the satchel is the piece you were
  standing over, it is gone afterwards, pressing <kbd>E</kbd> over bare ground
  gives nothing, and two people cannot pick up the same branch.
* `SatchelMouseTest` — the cooking screen through a synthetic `InputManager`:
  sweeping the pointer down a column walks its rows *in the list's own order*
  (which is what says the hit boxes are the rows that were drawn), the wheel
  scrolls the column it is over without moving the cursor and stops at the top,
  clicking a recipe does what Enter does, the ✕ closes it, the arrow keys still
  drive both columns, and a resting pointer does not undo them.
* `DebugModeTest` — the code (it lands, it toggles, a stray keypress in front
  of it is harmless, a half-typed one is forgotten, and random digits do not
  find it), what it grants, and who may have it: a guest on somebody else's
  walk is refused and the host is not. The test that carries the design is
  `everythingInTheGameIsFreeIncludingWhatIsAddedNext`, which walks
  `Recipes.all()` and `BuildPiece.all()` rather than naming anything — so a
  recipe added tomorrow is in the test tomorrow without the test being edited,
  which is the same property the feature has. Then that the flag reaches the
  screen on both the solo and the wire paths, that it survives a save, and that
  typing the code into a running `WatchScene` turns it on, draws the readout,
  and turns it off again.
* `WalkCycleTest` — that "choppy" is a set of numbers with properties, and each
  of them holds. A second of walking sampled at twelve frames and at four
  hundred leaves the cycle in the same place (which is what says the clock is
  seconds and not frames); a walker fed twenty-hertz positions is drawn moving
  every frame in steps under a fraction of the ones that arrive, never
  backwards, and their legs settle on the speed they are seen to move at; a
  teleport is placed rather than skated to and a heading turns the short way
  round; every curve in a stroke is continuous *and* so is its slope, across
  the joins and across the wrap; the blade is in the water for exactly the
  drive; the lower boot is on the ground at all sixty-four points of the
  stride, a standing walker is identical at every phase, and the body's rise
  and fall is real but under twenty centimetres; the rower's fists are within
  nine centimetres of the handles at every point of the stroke — checked
  against the mesh's own vertices, because the hands and the oars are drawn by
  two different classes and the only interesting question is whether the
  triangles agree — and the rower rides the hull's swell exactly. Then the
  primitive underneath it all: a strut is a closed box with every face wound
  outward, at three orientations including the degenerate vertical one, and a
  strut of no length is skipped rather than emitted with no normals.
* `SwimCycleTest` — the same treatment for the swim. That the body lies down as
  a swimmer sets off and stands back up when they stop, continuously in speed
  and with no step in it anywhere; that a diver lies along their own course and
  a surface swimmer never can, because the body has to stay in the water; that
  floating still **is** the standing figure, within the depth of a boot sole,
  which is what makes wading out of your depth one movement rather than a cut;
  that a swimmer at the surface has their head out of the water and their legs
  under it at every point of a stroke, and one treading water is in it to the
  chest — both of which follow from `WatchScene.FLOAT_DEPTH`, which the test
  reads rather than restates; that `swimEye` agrees with where the mesh
  actually put the head, at every body angle; that the stroke never stops,
  unlike every other cycle in the game, and that a diver going straight down is
  clocked as swimming while a walker downhill is not; that the arms and the
  legs take turns and neither jumps nor changes direction instantly; and that
  the first-person hands sweep together and stay clear of the near plane.
* `JumpTest` — mostly through the real scene, because the interesting claims
  are about what a keypress does: Space is the jump key and something else is
  the crouch key, neither collides with anything that moves, pressing Space
  leaves the ground and gravity brings it back, leaning on the key does not
  fly, Space no longer crouches and the crouch key does, and a jump taken from
  a crouch stands up first. Then the pose: a grounded leap is the walk to the
  last decimal, the feet tuck on the way up and reach on the way down, a
  landing is absorbed by the knees rather than by moving the floor, and no part
  of a leap has a cliff in it — measured against the sweep's own average step,
  since parts of a jumping figure honestly travel a long way and only a step
  far larger than its neighbours is a cut. Finally that a jump nobody sent is
  rebuilt from the position alone, landing and all. The seed is *searched* for
  rather than chosen: the first player joins at the world origin and a third of
  this world is under water, so a fixed seed is a coin toss over whether the
  test is about jumping or about swimming.
* `ShopsTest` — three claims, in order, and the third is the one the whole
  feature exists to make. **They are in the world**: a walk finds posts, every
  one of them stands beside a trail on flat dry ground, no two are within a
  clearing of each other, nothing wild grows in a yard, and two players on one
  seed find the same posts with the same keepers and the same shelves down to
  the price of rope. **They trade**: points buy materials at the counter off the
  shelf that post actually has, out of the shared book's balance — and the whole
  thing is refused, without taking anything, to somebody in the next valley,
  somebody short of the price, somebody naming a post they are not standing at,
  and somebody asking for a thing this keeper does not carry. **They turn the
  page**: a species scores once, scores nothing the second time, and scores
  again after a stamp — *and it never leaves the guide*, which is the assertion
  the ask comes down to. Then a keeper who will not stamp a blank page, a closed
  page kept as a numbered volume, the ledger and the pages through a save, and a
  single-entry announce that leaves the ledger exactly as it found it. Then the
  building and the person in it: the post is a building rather than a shed and
  fits in its own clearing, its shelves carry its own stock and drop it at
  distance, the sign swings and nothing else on it moves, the keeper is
  person-sized with their boots on the deck, every hat in the table is worn by
  somebody, their own animal is on the counter rather than out over the yard
  (which is the bug that measured 2.67 m wide), their head turns toward you and
  no further than a neck goes, and their idle has no cut in it across thirty
  seconds sampled nine hundred times. Finally through the real `WatchScene`,
  because the shop is the first thing in this game whose verb is a *screen*
  rather than a message: the highlight names the post, E opens it, a click buys,
  and E closes it again without reopening on the way out. And over a socket,
  where the point is what does *not* travel — no message names a shop's
  position, its keeper or its shelf, and a buy and a stamp still move both
  players' satchels and both players' books.
* `TrackFieldTest` — three claims, in order. **It is made by walking**: standing
  in one place for a minute writes one print and draws nothing, sixty metres of
  walking comes out as prints a stride apart, and the path is under the line
  that was walked and not beside it. **It is temporary**: a fresh track is
  strong, a nine-minute-old one is faint and still there, and at ten minutes
  both the path and the prints behind it are gone — with the fade asserted to
  hold at full first and to fall monotonically afterwards. **It is drawn on the
  ground**: every corner of the meshed sheet sits `TrackMesher.LIFT` above the
  ground under *that corner*, which is both what makes a path follow a hillside
  and the proof the mesh's origin is being subtracted the way the renderer adds
  it back; every triangle faces the sky, the mesh is translucent, and it carries
  the sort bias without which the painter buries it. Then the cases that are not
  footsteps — a nine-hundred-metre jump draws nothing between its ends and
  walking resumes cleanly on the far side — the cap on one walker's record, and
  ground two people crossed reading as more worn than ground one did. Finally
  through the real `WatchScene`, because every remaining way this breaks is a
  wiring fault: hold the forward key and the ground you started on is trodden
  behind you, and start another walk and it is not.
* `MapsTest` — seven claims, and an eighth that will be deleted rather than
  kept. **It is as wide as you can see**: a player is swept right across a
  snapping grid cell drawing a map at every step, and at the worst position the
  nearest edge is still a full render distance away — checking the middle would
  have proved nothing, since the middle is the one place the snap cannot hurt.
  Then that a longer ring draws a bigger map, that both land on the ladder and
  are whole multiples of each other, and that a client claiming a
  million-metre view gets the clamp. **It arrives finished**: two `TerrainField`s
  built from one seed paint the identical paper and two different seeds do not,
  the paper is terrain rather than a wash, and a camp built *after* a map was
  drawn is on the next map and not on that one. **It has the country on it**:
  the post underfoot with its own sign, the feeder and the floor just laid, and
  six pieces in one clearing drawn as one camp rather than six. **You can write
  on it**: a stroke goes on in world metres, one point is refused as not being a
  line, the eraser names one mark by id out of a space shared with the notes,
  and all of it survives a save and crosses the wire with the map. **It is in
  the satchel**: carried by whoever drew it, renamed, refused a blank name, and
  cut to length. **It shows everybody**: a walker in the middle is on it, one
  four map-widths east is pinned to the border rather than dropped, and pinning
  is to the east edge rather than to a corner. **Maps combine**: two drawn a
  span apart meet exactly, pinning the second widens the board by a whole map
  and does not make it taller, taking one back shrinks it and returns the map to
  the satchel, and a walker four hundred metres away cannot pin anything.
  **The board wears them**: an empty board's face is bare timber, a pinned map
  gives it a face of many colours rather than a wash, a line drawn across that
  map changes what the timber shows, and taking the map back leaves bare timber
  again — plus, through the real scene, that the map on a board is *geometry in
  the world* with no panel open, which is the whole claim. And the frame both
  the board and the panel are drawn through, once: middle to middle, north up,
  east right, and a metre→pixel→metre round trip that lands where it started.
  Finally through the real `WatchScene`, because most of this feature is a
  panel: M draws a map, opens it *and it is still open five frames later* —
  which is the assertion that catches a panel opened on a view that has not been
  told about the map yet — the satchel lists it above the items and opens it,
  F2 renames it, a drag across the paper draws a line that runs the way the hand
  did and lands inside the map, the eraser takes it off again, the note tool
  writes words, E at a board opens it, and clicking down the board's own list
  puts a map up. And the eighth: every one of the six verbs is refused to a
  player who has not typed the code, the build menu does not list the board to
  them, and the readout says maps are one of the powers.
