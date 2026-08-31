# Field Guide — the animal-watching game

> **Status:** implemented. This document is the design record: what the game is,
> how it is put together, what it reuses from the engine, and where each piece
> of the brief landed in the source tree.

A fourth mini game, sitting beside the Auto Battler, Council of Six and
Evolution on the launch screen's corner strip. You and up to seven friends walk
an endless, procedurally generated wilderness, find animals, and write them into
a shared field guide. Nothing in it is a fight — there is nothing to fight with,
and the three things that hunt you (§7i) are something to get away from rather
than something to beat.

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
| **Maps** | Draw a map, mark it, pin it to a board. *(Added in §7h — a gate on an unpriced feature rather than an abundance.)* |
| **Summon mutants** | **K** puts a wendigo, a werewolf or a mirewraith on the ground twenty metres in front of you — any biome, any hour, however many. *(Added in §7i.)* |
| **Readout** | Position, chunk and LOD, biome and material underfoot, streaming, triangles, what is alive and what is hunting, the glass, the guide, the nearest trading post. |

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

**Summon mutants** is the third, and the class note had named it before it
existed — "a spawn" is its first example of a thing the satchel lens cannot
reach. It is a different shape again from the two before it. *Unlimited points*
covers a cost that escaped the satchel and *Maps* gates a feature that is not
priced yet; this one grants something **no player will ever have**. It is not a
verb waiting for a gate to lift, and that is why its key is the one thing in the
walk that is not on the controls screen — see §7i.

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
    void setLighting(List<Light> lights, float dayR, float dayG, float dayB);
    void draw(List<Mesh.Draw> draws, EyeCamera eye, int fogArgb,
              double fogStart, double fogEnd);
}
```

`setLighting` arrived with §7j and is the one thing here that is not baked into
the geometry: the hour's own multiplier, and up to `MAX_LIGHTS` point lights in
world coordinates. It has a do-nothing default, so a backend that ignores it —
and `GlTerrainPass`, which shares the shader — draws exactly what it drew
before.

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
| GPU-accelerated lighting; placeable and carried lights; glowing mutants | `watch/light/*`, `watch/render/LightModel`, `MeshPass.setLighting`, `gl/GlTerrainProgram`'s fragment lighting — see §7j |
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
| Tag: a voted-for round, 1.3× speed, a 30 s freeze, a water gun, a compass to the nearest player | `watch/Tag`, `WatchGame.suggestTag`/`voteTag`/`squirt`, `Hurl.squirted`, `WatchView.nearestOther` — see §7k |
| Eye Spy: a random 10–100 point bounty on an animal, once a day, for everybody but the poster | `watch/Bounty`, `WatchGame.postBounty`, `FieldGuide.reward` — see §7k |

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

## 7i. Three things that hunt you

Everything above this line is a game in which nothing can hurt you. Section 4
opens by saying so, and it was true: the whole difficulty curve was one
relationship — an animal decides how close it will let you get, you hold still,
it lets you closer. There was no failure state, and deliberately not.

This is the round that added one, and the design problem was not "how do we do
combat" (we do not — there is nothing to fight back with). It was: **how do you
put something dangerous in a game about walking around looking at birds without
turning it into a game about danger?**

The answer this round settles on is that the wood has to stay safe. Not *mostly*
safe — safe, for hours, in almost every direction, so that a player gets
comfortable, learns the biomes, forgets to look behind them, and is then very
occasionally not safe at all.

---

### The three (`watch/life/Mutants`)

| | Where | When | How it hunts |
|---|---|---|---|
| **Hollow Wendigo** — 5.6 m | boreal taiga, tundra barrens, crystal highlands | night | `STALK`: notices at 78 m, follows to 190, **6.6 m/s**, and **throws** — six blows to kill |
| **Moonfell Werewolf** — 4.4 m | deciduous woods, autumn birchwood, pine forest | dawn and dusk | `LUNGE`: **8.0 m/s held**, 11.1 in a 1.8 s burst — five blows |
| **Drowned Mirewraith** — 5.0 m | reed marsh, mangrove coast, mushroom hollow | night | `AMBUSH`: stands up at 22 m, then **8.0 m/s flat** — three blows |

**Two of them run exactly as fast as you can.** `WatchPlayer.RUN_SPEED` is
8.0 m/s and so is a mirewraith's pursuit and a werewolf's speed between bursts,
to two decimal places. A player at a flat sprint holds either of them exactly
level and loses ground on every werewolf burst, so a straight-line chase has no
winning pace: what saves you is a ridge, a lake or a stand of trees. The chase is
decided by the ground rather than by the legs, which is the right verb for a game
about walking around looking at things.

**So the blows are slow.** 2.4 s for the werewolf, 3.0 for the wendigo, 3.6 for
the mirewraith — the three slowest attacks in anything. That is one design and
neither half survives alone: something that can hold a sprinting player's pace
has to be survivable once it arrives, and what makes being caught survivable
rather than fatal is whole seconds between swings, with a wind-up you can see
from behind. Being caught is a problem, not a death.

The wendigo is the exception and pays for it. At 6.6 m/s it is the only one a
sprint outpaces — and the only one that does not need to catch you.

No two of them share a biome, so a region has at most one horror in it and it is
always the same one. Between them they haunt nine of the twenty biomes; the
other eleven have nothing.

**They are written out, and that is the whole argument of the file.** Every
other animal in this game is generated — twenty-seven families crossed with a
hundred and eighty-nine lineages and a pool of epithets, 1 323 species, and that
is the right way to fill a book nobody could write by hand. It is the wrong way
to make something frightening. A generated horror is a *category* of horror, and
forty-nine wendigos with rolled colours and rolled sizes is a weather condition
rather than an event. So `AnimalRegistry.build` skips the three
`hostile()` families and appends `Mutants.species()` instead.

They are ordinary `AnimalDef`s from that point on: the guide pages them, a
texture pack redresses them, the wire names them, `AnimalSkins` paints them. A
mutant is a page in the book like any other page, which is exactly what makes
walking up to one worth doing.

### Four filters, and only one of them is a dice roll

1. **Region** — three biomes each, above, and no two of them share one. Eleven
   of the twenty biomes have nothing in them at all.
2. **Hour** — and this one is a *hard* edge, which nothing else in the game has.
   `Activity.activityAt` never quite returns zero on purpose (a nocturnal
   warbler disturbed at noon does move, and a page nobody can finish is a bad
   page). `AnimalDef.encounterWeight` returns a flat zero for a hostile species
   outside its hours, so "the taiga is safe until dark" is true rather than
   nearly true, and a player can plan a day around it.
3. **The world's state**, in `WatchGame.populate`, which is not about odds at
   all: at most **one** alive anywhere, and a **ten-minute** cooldown after one
   is put down — so walking away from a wendigo cannot be answered by the next
   spawn tick handing you another, which would read as it teleporting.
4. **`Rarity.MYTHIC`**, last, because it is the one that matters least and the
   one that was got wrong.

### The frequency was the wrong lever, and measuring said so

The obvious reading of "a tier above legendary" is a vanishing frequency, and
0.0006 — a twentieth of a legendary — is what this was built with. Then it was
measured. A biome holds two to five hundred species whose encounter weights sum
to about a hundred and fifty, so that frequency comes out at **one pick in two
hundred thousand**: fifty simulated minutes of night walking across four seeds,
about 2 300 spawns with 44% of them on mutant ground, met nothing at all. On
that setting a player meets a mutant about once every three thousand hours,
which is a feature that does not exist.

It is at **0.30** now — roughly one pick in five hundred — and the tier's job is
correspondingly smaller than it looked: *given* that you are on one of three
biomes out of twenty, in the right hour band, with nothing else alive and the
cooldown spent, how soon. The rarity comes from the other three filters. Half an
hour of walking the right country at the right hour is about right, and it is
deliberately rarer than the cooldown so meetings are not metronomic.

Measured after the change, on fifty-minute night walks: one seed met two, two
met one, one met none; the day walks met nothing on any seed, which is the hard
hour edge doing its job.

### And the spawn ring had to come from the creature, not from a constant

A fixed 90–140 m ring is the obvious way to say "you should see it coming", and
it produced spawns that nothing came of: a wendigo notices at 78 m, so it
arrived **outside its own senses**, wandered, and was left behind by a walker at
4.4 m/s who never knew it was there. Two spawns in fifty minutes and not one
second of being hunted.

So `WatchGame.placeMutant` draws the distance from a band inside *that
creature's* notice range, floored at 45 m — near enough that it takes an
interest at once, far enough that a five-metre silhouette is seen first, and
about ten seconds at a walk in which to decide what to do. The number that is a
warning for one of the three is out of earshot for another, which is exactly
what a constant cannot express.

The ambusher is the exception and is placed the other way round: it is meant
*not* to notice you at spawn, so it goes 45–63 m ahead within a ±28° cone of
where its quarry is walking, and the encounter begins when they walk into it.
(That cone had `atan2`'s two arguments the wrong way round at first, which put
every ambusher exactly behind the person it was waiting for — a bug that
presents identically to "ambushers never trigger". `MutantTest` now walks a
night and asserts something finds somebody, which is the assertion neither of
these two worlds could pass.)

A mutant spawn also says one line into the log — the only spawn in the game that
announces itself — because a player looking the other way when a wendigo walks
out of the treeline otherwise gets no warning at all.

### The models are bipeds, and nothing else is

`AnimalModel` has five parameterised plans covering twenty-one builds: a heron
and a sparrow are the same eleven boxes with different numbers. That is how you
get a hundred animals that belong to one world, and it is exactly why the
mutants cannot be a setting on it — something that should look *wrong* in a
world cannot be drawn by the thing that world is drawn by.

So `wendigo()`, `werewolf()` and `mirewraith()` are written box by box, they
share no plan with anything, and each is the only species in its build. (They
were later rebuilt at four times the detail, and two of them lit from inside —
see *More intricate models* below.) Every
other model here stands a horizontal body on legs; these stand a torso on end
with arms hanging off it, at four to six metres, which reads as not-an-animal
from across a valley and before it has moved.

**The arms are the front legs.** An upright thing needs two limbs that swing
opposite the two it walks on, which is what `LEG_FL`/`LEG_FR` already are and
how `WALK` and `RUN` already pose them. So a mutant's arms swing when it walks
and pump when it runs with no new pose table at all. Using `WING_L`/`WING_R`
would be the obvious reading of "arms" and is wrong: the wing poses fold
themselves flat against the flank in half the states, which is right for a bird
standing about and is amputation for anything else.

One more thing came out of looking at them rather than at the code. The
mirewraith was first built as a barrel wider than the arms hanging beside it,
with near-black limbs (`detail` is the `LIMB` and `HARD` colour) on a dark green
body — and at any distance, at night, in the biomes it lives in, all four of its
arms vanished into its own trunk and the silhouette was a slab. A waist narrower
than the shoulders above it, and pale bloated limbs against the drowned green,
are what turn four dangling arms into something a player can see are four
dangling arms. Both are also what the creature is, which is usually how that
goes.

`AnimState` gained a tenth state, `STRIKE`, rather than the mutants special-casing
one: ten states are the same contract with Blockbench that nine were — a model
that supplies a `strike` clip gets it, one that does not falls back to the
procedural pose, and the three placeholders are posed by the same table as the
wrens.

### Health (`watch/WatchPlayer`)

A second resource, and the first that can end a walk. Ninety seconds from empty,
six seconds of delay after the last blow, and no medicine, no bandage, no
crafting tree ending in a poultice. What it measures is how many more seconds
you can afford to be in front of the thing.

It regenerates **without** being tied to stillness, which was the obvious
flourish and is the wrong one: holding still is what makes animals come to you,
and making it also the way to heal would turn the game's one voluntary patient
verb into a chore performed after every chase. You heal while walking home.

The bar goes out for the whole party, not only its owner: seeing that somebody
else is on a third is how eight people spread over a valley learn that one of
them has walked into something.

### Stillness does not work on them, and that is the point

`Animal.hunt` is a separate four-state loop reached by the first branch of
`step`, not a clause inside `decide`. A mutant has no flush distance, no lure it
comes to, no trust, and no ALERT — it has decided.

The load-bearing difference is one method. Every other question an animal asks
about people goes through `disturbanceAt`, which is not distance but *loudness*:
a settled player counts as three times further away than they are. That is the
approach mechanic, and it must not apply here — if it did, the way to be safe
from a wendigo would be to stand still in front of it, and the single thing this
game asks a player to do would become the thing that kills them. So
`nearestQuarry` is the plain distance. Nothing you do about your footsteps
changes the answer; where you are standing is the answer.

There is a test that stands a player perfectly still in a clearing whose
`disturbanceAt` returns "nobody, at any distance", and asserts that the wendigo
comes anyway.

### Dying drops the bag (`watch/Spill`)

Death has to cost something or the three of them are a light show. It must not
cost anything *permanent*, because this is a game about picking things up and a
satchel deleted by a bad thirty seconds in the dark is half an afternoon deleted
with it.

So it drops. Everything you were carrying is in one heap at the place it
happened, anybody can pick it up (a friend fetching your bag off the fen while
you take the long way round is the best thing this feature does), and nothing
decays — a save reopened a week later still has it. The penalty is the walk
back, and it is a penalty made of the thing the game is already about: walking
somewhere you know for a fact there is something dangerous.

`Spill` is **stored** state, unlike `Litter`, and the difference is worth being
explicit about. A piece of litter is a function of the cell it is in; both ends
of a connection derive it and only "who has taken what" travels. A spill is a
thing that *happened* — there is no seed from which "Kara died at the ford with
eleven blackberries" can be derived — so it is in the save and it rides the world
sync beside the grove and the boats.

A heap is **first** in `WatchGame.pickTarget`, above the bushes and the boats and
everything else the key can reach. It was fourth, and a test that walked somebody
back to their own satchel got a handful of fruit off the tree beside it — which
is the whole feature failing at the last step. It costs nothing to put first:
there is usually no spilled satchel anywhere in the world, and its reach is
2.6 m against the key's 4.5, so it only wins when you are standing on one.

There is no death screen and no timer. You stand up at the spawn point with a
long walk ahead of you, which is punishment enough.

### How a respawn reaches the client

The client is authoritative about where it is standing — it sends a position and
the host records it — so the host **cannot** move somebody by writing a position
into the snapshot: the next `move` would put the old one straight back. A "you
have been moved" message would work and would have to be acknowledged, resent
when lost and ignored when duplicated.

Instead `WatchPlayer` publishes a **respawn counter** in every snapshot beside
its position, and a client that sees its own number go up teleports to the
position in that same snapshot. Lost packets do not matter (the next snapshot
carries it), duplicates do not matter (the number is unchanged), and a client
that joins late is already in the right place. `WatchScene.syncVitals` is the
one frame that acts on it, and it also ends the boat, the glass, the panel and
the tracks — a walker at the spawn point still rowing a boat four hundred metres
away would be the obvious bug.

### Summoning one on purpose

Every filter in §"Four filters" is working correctly when it refuses to produce
a mutant, which leaves anybody testing the three of them with nothing to do but
walk a taiga at night and wait. So debug mode gained the row its own class note
had predicted years of features ago — "a spawn" is the first example it gives of
something the satchel lens cannot reach — and it cost what that note says it
should: one row in `Debug.Power`, and one `if (player.debugging())` in
`WatchGame.summon`.

**K, in debug mode, cycles the three.** Press it once for a wendigo, again for a
werewolf, again for a mirewraith, and round. One key rather than three because
two of three bindings would be wrong most of the time, and a cycle rather than a
random pick because the whole use of the thing is looking at the one you are
working on.

It lands twenty metres ahead, on the ground, facing you. Twenty is taken from
the creatures' own senses rather than chosen for the view: the shortest notice
range of the three is the mirewraith's twenty-two, so a summon is something that
*starts happening* rather than a statue to walk around.

**It asks none of the four filters** — not the region, not the hour, not the cap
of one alive, not the cooldown. That is the feature and not a shortcut: a tester
standing in a desert at noon needs all four out of the way at once, and each of
them refusing is each of them working. The verb summons any species in the
registry, because the code is identical either way and a restriction that exists
only to restrict is one more rule to explain; what makes it a *mutant* feature is
which keys the walk offers, which is the client's decision. A heron you can find
by walking to a marsh.

**The key is not on the controls screen, and `WATCH_MAP` is** — which looks
inconsistent and is the point. A map is a game verb behind a gate that will one
day lift, so it is listed where a player will find it the day it stops being
special. Summoning a wendigo is never going to be a player verb, and advertising
one that is always refused is the exact thing `Debug`'s class note says a menu
item would do wrong. So K is read raw off the keyboard, the way the code itself
is, and to anybody who has not typed 7799 it does nothing at all.

### The wendigo throws (`watch/life/Hurl`)

A creature that cannot catch you and cannot hurt you is scenery, and the wendigo
is deliberately the slow one. So it throws splinters of bone: 45 m of reach, one
every three and a half seconds, 24 m/s, under a quarter of real gravity.

The answer to it is a third verb rather than a third speed. Against a werewolf
you look for ground; against a mirewraith you look at the twenty-two metres you
have left; against a wendigo you look for something to put **between** you. The
shard buries itself in a rise exactly as you would hope, which is the cover
mechanic working rather than a bug — a fact that cost two tests before it was
appreciated, see below.

**The minimum range is the interesting half.** Inside six metres it does not
throw at all, which means *closing* on a wendigo turns its ranged attack off and
leaves you with the slowest melee in the game. The dangerous place is the middle
distance; both running away and running at it beat standing at forty metres in
the open.

It is not the engine's projectile system, on purpose. That one belongs to the
block world and knows about block collision, damage types, owners and item
drops; there is exactly one thing in the Field Guide that flies, and it is a
straight line with a lifetime.

**Two bugs, both of which read as "it works except at some distances".** Worth
recording because they are the same bug wearing different clothes — a continuous
model sampled discretely — and because the first fix for each looked right:

1. *The arc was approximated.* The aim was lifted by however far the shard would
   fall over a flight time of `range / speed`. That is wrong twice: lifting a
   fixed-magnitude velocity steals speed from the horizontal, so the shard takes
   longer than the estimate and falls further than the correction allowed. The
   error is small and not monotonic — measured, it hit at 12, 20, 40 and 44 m and
   missed cleanly at 30. It is now the exact launch angle, which is one square
   root, taking the flatter of the two solutions.
2. *The hit test sampled a point.* A shard covers 1.2 m per tick against a 1.1 m
   hit radius, so it stepped straight through people. It now measures the
   **segment it swept** this tick against the target, which is exact at any speed
   and makes the answer independent of the tick rate.

### More intricate models, and two of them are lit

The three were rebuilt from a few dozen boxes each to around a hundred: jointed
legs with knees and toes, segmented spines, ribcages, collarbones, fingers with
two joints apiece, hanging jaws with teeth in them, layered antlers, ears with
inner faces. `AnimalModel` gained two small helpers (`box` and `limb`) because a
twelve-argument constructor written out two hundred and forty times is a plan
nobody can safely change; `limb` also caps each segment, which is the difference
between a plank and something that tapers into a joint.

**The wendigo's chest is open and burning**, ribs standing in front of the fire
rather than hiding it, with dimmer shells above and below. **The werewolf has two
red eyes** — and they had to move: first built level with the snout at the width
of it, they were invisible from every angle a player will ever see the creature
from, because a muzzle is 4 cm of solid head in front and a skull 5 cm behind.
They sit on the brow now. **The mirewraith's lantern is green**, not red, so that
a party who has met all three can tell which pair of lights is in the treeline.

**How "glowing" is done, and what it is not.** The renderer multiplies every
vertex by the hour's light and the mesh format has no emissive channel; adding
one means a field on `Mesh`, a branch in the painter's shading and a change to
the card's shaders — a great deal of renderer for two eyes. So it glows the way a
flat-shaded world can: the brightest, most saturated colour on the creature, set
in a near-black socket, with dimmer shells around it. Both are multiplied by the
same light, so the *contrast* survives every hour of the day, and at night —
which is when these are out — ten-to-one against a dark body is the only thing on
them the eye finds.

That is still true of the *material*, and §7j is the other half of it: the glow
is a colour on a texture, and the light it casts on the trunks beside the
creature is a point light in the same colour. Neither needed an emissive vertex
channel.

Two new skin regions carry it, `GLOW` and `SHADOW`, and they **cost nothing**:
the eight original regions look like they tile the 64×64 sheet and do not quite,
leaving a 32×16 strip along the bottom right that nothing had ever painted or
read. Every one of the thirteen hundred existing species is unchanged to the
byte, and a pack author's sheet gains two blocks rather than losing any.

### And they do not walk like animals (`watch/life/MutantGait`)

`AnimalModel.pose` is a good walk cycle: legs exactly out of phase, both sides
the same, body rising on each footfall, head bobbing in time. That is what a deer
looks like and why a deer looks fine. Run it on a six-metre biped and you get a
six-metre biped going for a pleasant walk.

Everything unsettling about a gait is a broken symmetry, and each of these is a
specific one:

* **The legs are 0.43 of a turn apart, not 0.5.** One foot lands a fourteenth of
  a stride early, for ever, and the limp never resolves into a rhythm you can tap
  along to. It is the single most effective line in the file.
* **One side strides 22% further than the other**, held constant, so it walks in
  a very slight curve it is forever correcting.
* **The torso lags the legs by a quarter turn and counter-rotates**, which is
  what a body being *carried* by its legs looks like rather than one driving them.
* **The head runs at a third of the stride rate**, drifting, never landing on the
  beat — a head that nods in time reads as a horse.
* **The arms hang and overshoot** rather than pumping, because the creature is
  not using them for balance.

Idling is not standing still either: a slow breath, a head that keeps turning to
look at something that is not there, and a shudder that spikes once every few
cycles rather than a even sway. The strike is slow up and fast down, so the pose
says *now* before the damage does.

It reaches the renderer through `AnimalModel.PoseSource`, which is the seam an
imported Blockbench model already uses — so a mutant is just a species whose
poses come from somewhere else, and a hand-animated `.bbmodel` still overrides
it exactly as it overrides the geometry.

### The bug that deleted three torsos

Worth writing down at length, because it survived a full test suite, three
rounds of looking at renders, and a commit — and because the reason it survived
is more interesting than the bug.

`AnimalModel.Pose` has a four-argument shorthand, `(pitch, roll, lift, spread)`,
and the last number **multiplies the part's width**. It exists for exactly one
thing: folding a bird's wing flat against its flank, because no rotation folds a
plate.

Every `BODY` and `HEAD` pose in `MutantGait` was written with that shorthand,
passing a sway value — or a plain zero — into the slot where `spread` lives. So
on all three creatures the torso, ribcage, chest glow, shoulders, neck and skull
were multiplied to **zero width**: a flat plane, seen edge-on. The limbs used the
three-argument form, which leaves the spread at one, so what a player saw was a
pair of arms and a pair of legs walking around with a vertical line between them.
The models were right the whole time. The poses were deleting them.

**Why nobody spotted it.** Every render taken while building this was a
three-quarter view, and from three-quarters a collapsed torso reads as *gaunt* —
which is exactly what a wendigo is meant to look like. The red chest glow still
showed, as a red rectangle, and was noted approvingly. It took a front-on view to
make it obvious.

**Why the obvious test would not have caught it either.** The first version of
the regression test measured the creature's overall bounding box, and it stayed
green when the bug was deliberately reintroduced: arms hanging at the sides and a
metre of antler keep the box perfectly wide around a torso that is not there.
The test now pulls the `BODY` and `HEAD` parts into a model of their own and
measures *that*, where there is nothing to hide behind.

Two guards, at the cause and at the symptom:

* **`onlyAWingMayBeNarrowed`** walks every pose source in the game — the shared
  animal table included — and asserts that no joint but a wing is ever given a
  spread other than one. The shared table is one mistyped argument away from
  doing this to a heron.
* **`theirTorsoIsSolidInEveryState`** meshes each mutant's trunk alone and
  asserts it has real width and depth in every state and at every phase.

And the file itself no longer constructs a `Pose` directly: a body part goes
through `body(pitch, turn, roll, lift)` and a limb through `limb(pitch, roll,
lift)`, neither of which can reach the argument that did this.

### Making the shard obvious (`watch/render/Sparks`)

A bone shard crosses forty metres in under two seconds, and the first version of
it was two thin pale struts. At that speed that is a thing a player notices
*after* it has hit them, which makes the wendigo's whole attack feel like
unexplained damage rather than like something they could have stepped out of the
way of.

Three changes. The shard is twice the size and built as a shape rather than a
sliver — a bone shaft, a barbed head and a cross-piece, with a core in the
thrower's own fire colour, so what is coming at you is a metre of burning bone.
It lays a **trail of embers**, which is the part that actually does the work: a
trail is visible along the path the shard has *already* taken, so it says where
the thing came from as well as where it is. And it **bursts on impact**, because
a projectile that simply stops existing reads as a rendering fault rather than as
a thing that landed.

**Not the engine's particle system**, and this is not laziness. `graphics/Particles`
draws sprites in *screen space* for a side-scroller; a screen-space sprite has
nowhere to live in a first-person world built out of submitted triangles, and an
ember that does not sort against the terrain behind it is an ember that shines
through hillsides. So an ember here is what everything else here is: a very small
box, in the world, in the frame's moving mesh. It occludes correctly, it fogs
correctly, and it needed no renderer changes at all.

Two details worth keeping. The trail is emitted **along the segment the shard
covered this frame** rather than at the point it reached — at 24 m/s a shard
moves more than a metre between frames, so emitting at the point gives a dotted
line of clumps that flickers with the frame rate. (The same reasoning that made
the shard's own hit test measure a segment.) And embers **shrink** rather than
fading, because the mesh they go into is opaque and an alpha fade would mean a
second translucent mesh sorted against the first; a cube that shrinks to nothing
has the same silhouette at a distance for none of the cost.

Nothing about an ember travels. `Sparks` watches the same replicated shard
positions the renderer draws and derives the trail and the impact from them,
exactly as `WatchSounds` derives the noises.

### Fifteen sounds, and nothing else in the game makes any

The Field Guide has thirteen hundred species and no sound at all, on purpose: a
wood full of generated bird calls is a wood where the calls are wallpaper, and
the whole proposition is that you find things by looking. Nothing is lost by that
silence because nothing you can find can hurt you.

Three things can — and a creature that hunts you is exactly the case where sound
stops being decoration and becomes **information**, because it is the only
channel that works when the thing is behind you, which is where it is trying to
be. So these three have voices and the finches do not.

`call · notice · step · strike`, plus `hurl · impact` for the thrower. Drop
`watch/wendigo_call.wav` into the sound pack and it plays; a file named for the
creature alone answers for every state it has not been given one of; anything
missing is silence. They are listed in the generated `SOUND_KEYS.txt` under
**Field Guide mutants**, and `resources/watch/sounds/README.md` is the long form.

`call` is the important one and carries **900 m** — deliberately further than the
creature is ever *drawn*. Somebody who hears it and looks up at an empty treeline
has understood the situation exactly.

Two design notes. The sounds are **derived from replicated state rather than sent
as events** (`WatchSounds` watches the view for edges — a creature appearing, a
state changing, a shard leaving the air), so a sound can never disagree with what
is on screen and no message can be lost. And footfalls are laid **per metre
walked rather than per second**, the same rule the track system uses, so they
speed up when a mutant does with no extra state.

The engine's one distance-and-pan model (`Sounds.playAt`) takes a screen-space
offset because it was written for a side-scroller; `WatchSounds.at` rotates the
world delta into the listener's own frame and hands it over, so the Field Guide
fades and pans through exactly the same call as every mob in the block world.

### One thing that changed for everybody

`AnimalPortrait` framed every subject so that its largest extent filled a fixed
box, with the camera 1.7 of those boxes back. That had never been looked at
because nothing in the registry was thin: measured across the book, a heron
covered 3% of its own portrait, an elk 5%, a grizzly — the broadest thing in the
game — 8%. Every page was a small figure in a large empty square.

A gaunt biped made it obvious: a wendigo came out as a 2% sliver, which is a
blank page with a scratch on it. The fix is not a special case for three species
but the camera coming in to 1.25, so the subject fills its own portrait — about
twice the area, for all 1 326 of them. The near-plane clipping this class was
written to avoid is still a metre away at the closest corner.

---

## 7j. Firelight, and the shader that finally got some

> Asked for: lighting accelerated by the GPU — lanterns and campfires that can
> be placed, some lights that can be carried, and the mutant animals glowing.

The world had a day and a night and nothing in between you could carry. Every
colour in it is a baked albedo multiplied by the hour (`WatchClock.lightTint`),
and the whole of "night" was that multiplier reaching 0.26. Nothing in the game
could add to it.

### The bug this found on the way in

**The card was not applying the hour at all.** `WatchRenderer.fogged` multiplies
every vertex by `lightTint` on the painter path; `GlMeshPass` uploaded the same
vertices and drew them through a shader with no such uniform. A machine with a
driver therefore played the whole game at noon while a machine without one had a
night — a difference nobody had put a number on because both pictures look like
a wood.

It is one call now, `MeshPass.setLighting`, made from `WatchRenderer.flush`, and
the two paths shade the same world at the same hour. Everything below rides on
the same seam.

### Four things that burn (`watch/light/LightKind`)

A catalogue, not four classes — a light here is entirely described by numbers,
so the difference between a campfire and a lantern is a row:

| | Colour | Reach | Burns | Fed with | Carried |
|---|---|---|---|---|---|
| **Campfire** | deep orange | 12 m | 4 h | branches | no — built where it stands |
| **Lantern** | warm yellow | 9 m | 9 h | sap | yes |
| **Torch** | orange | 6.5 m | 1.2 h | nothing — it burns out and is gone | yes |
| **Spore Lantern** | cold green | 7.5 m | for ever | nothing | yes |

The colours are the point of having four. A camp lit by one warm fire and one
cold spore lantern reads as two pools of light on the same ground, and at a
hundred metres in the dark you can tell which of your party is carrying which.
The spore lantern is also the one thing in the game whose ingredient comes from
exactly one biome (`glow_spore`, the mushroom hollow): the only light that never
goes out should be the one you had to go somewhere for.

Fuel is measured in **real hours** off the wall clock, like the trees and the
crops and for the same reason — a fire lit before bed is out in the morning
whether or not anybody was logged in.

### Two keys, and what they mean

* **N** — light, douse or fill whatever is in your hand. One key, because "make
  it light" and "make it dark" are one intention with a state attached: burning
  goes out, out-with-oil comes back, empty-with-sap-in-the-bag is filled and
  lit, and empty hands take the longest-lasting light in the satchel.
* **H** — set it down (H for hearth). What is in your hand goes down first,
  keeping the oil it has left; with empty hands it builds a campfire out of
  three branches and two stones.
* **E** — the existing reach key, which now finds a fire as a `Pickable.FIRE`
  and tends it: an armful of branches goes on a guttering one, a cold one is
  lit, and a lantern with nothing wrong with it is picked back up — still
  burning, with the oil it had.

**Putting a lamp out keeps its oil**, which is the whole reason `WatchPlayer`
carries a separate `lampLit` flag rather than letting a null key mean "not
burning". A lamp that forgot its oil every time it was doused would make the
fuel economy a formality: putting one out and lighting it again is two
keypresses.

New walkers start with two torches, on the same argument as the starting seed
and berries: this clock is the real one, and somebody who begins a walk at nine
in the evening should not have to forage a wood they cannot see in first.

### The mutants light the ground now

§7i's note on the glow — "the mesh format has no emissive channel, so it glows
by contrast rather than by emitting" — is still exactly right about the
*material*. What was missing was the other half: a creature with a burning
ribcage should light the trunks beside it. Each of the three now carries a point
light in its own `AnimalSkins.Region.GLOW` colour at chest height, pulsing on a
slow two-second heartbeat rather than a flame's chatter.

Its reach is **6.2 m, deliberately short for how bright it is**. A wendigo lighting
the wood the way a campfire does would mean meeting one is well lit, which is
precisely the wrong feeling; six metres lights the ground it stands on and the
trunks beside it and nothing else. The thing you can see is the thing that is
already too close.

### What is burning, per frame (`watch/light/LightField`)

Nothing about a light travels. A fire's *existence* is world state the host owns
and replicates — where it is, whether it is lit, how much is left in it — and
every *consequence* of it is worked out on the machine that draws it, which is
the same bargain `Sparks` strikes for embers and `WatchSounds` for noises. It
buys a flicker that cannot arrive late and nothing on the wire when eight people
stand round one fire.

Five things are gathered: placed lights, everybody's carried lights (not only
your own — a lantern moving along the far side of a valley is how a party keeps
track of each other after dark), mutants, a wendigo's burning shards in flight,
and the one feeder bait that is itself a lamp (`moth_lamp`, whose own
description said so already).

**The bound is the design.** A fragment shader that walks a list walks all of
it, so the list is capped at `MeshPass.MAX_LIGHTS` = 16 and the cap is applied
here, ranked by how much lit ground each light can put in front of the camera —
full marks while the eye is inside a light's own sphere, falling away outside
it. A party that lights forty lanterns in one clearing gets the sixteen that
reach them and no frame-rate cliff, because the alternative is a game that gets
slower the more of it you build.

### The normal problem, and the two answers to it

Point lights need a surface normal and this vertex format has none — three
positions, two texture coordinates and a colour, chosen so a chunk can be
uploaded once and drawn for as long as it is in view. Adding one would be four
more bytes on every vertex in memory to carry a number that is the same for all
three vertices of a flat-shaded triangle.

Neither path pays for it:

* **The painter** has the normal for nothing. It walks triangles, and a flat-shaded
  mesh's three vertices *are* the face: one cross product per triangle, and only
  for triangles in a mesh that survived a per-mesh light cull.
* **The card** derives it per fragment from the screen-space gradient of the
  eye-space position — `normalize(cross(dFdx(vEye), dFdy(vEye)))` is exactly the
  plane the triangle lies in, costs two instructions, and is free of the one
  thing an interpolated normal would give: smoothing, which is precisely what
  this world does not want.

Both then turn the normal toward the eye, because grass, leaves and water are
single-sided sheets meant to be seen from either face — a lantern behind a blade
of grass has to light the side you are looking at.

The arithmetic after that is one function, `LightField.contribute`, written out
once in Java and once in GLSL:

```
  fall   = (1 − distance/radius)²        compact, so a light is either inside a
                                         fragment's reckoning or costs it nothing
  ndotl  = max(0, N · L)
  light += colour × intensity × fall × (WRAP + (1 − WRAP) × ndotl)
```

`WRAP` (0.32) lives on `MeshPass.LIGHT_WRAP` so both sides read one number. It
is why a fire lights the underside of a branch: pure Lambert on flat-shaded
low-poly geometry is black on everything not squarely facing the flame, and a
wood at night is mostly surfaces that are not. Inverse-square would be more
nearly physical and has no outer edge at all — which means every light in the
world contributes to every fragment, the thing a bounded list exists to prevent.

### Where the cost goes

| | Painter | Card |
|---|---|---|
| Lights per frame | ≤ 16, culled again per mesh | ≤ 16, in a uniform block |
| Normal | one cross product per triangle | `dFdx`/`dFdy`, per fragment |
| Meshes that pay anything | only those whose bounding box a light's sphere touches | all — it is two dozen instructions |
| Geometry re-uploaded when a light moves | none | none |

The per-mesh cull is what makes the painter afford this at all: a campfire
reaches twelve metres and a chunk is thirty-two across, so all but a handful of
the meshes in a frame are outside every light in the world. One box test each
throws them out and their forty thousand triangles never ask a lighting
question.

### The objects (`watch/render/LightModel`)

A pool of light with nothing in the middle of it reads as a rendering fault. So:
a ring of seven stones with three logs across it and a three-cone flame; a
lantern's base, four uprights, a cap and its bail — open-sided, because a
modelled pane would hide the only part of it that matters; a torch as a shaft
with a bound head. A campfire that has burnt out **chars rather than vanishes**,
because a four-hour fuel should be a chore and not a punishment: the logs go to
ash colour and the hearth stays lightable.

The flame is built on `WatchMaterial.PAPER` — the white, grainless tile the map
board needed — so that `texture × vertexColour` on a card and the vertex colour
alone in the painter come out as the same fire. It needs no emissive channel: a
flame sits at the exact centre of its own point light, so the lighting pass hands
it the full intensity of the thing it is the source of, and it reads at its own
colour at every hour of the day.

Campfires throw embers, through `Sparks` — the class that already existed for
burning bone shards — at fourteen a second, rising, capped at six fires' worth.
Sparks going up are what say the light is being *made* rather than emitted.

### What travels

Placed lights ride the **snapshot**, beside the feeders rather than on the
five-second world sync, and for the feeders' reason: they are world state that
changes on its own. A built piece never does; a fire goes out, and a party who
watched their camp go dark five seconds after it happened would learn to
distrust the picture. A row is six numbers and a key.

A carried light is one field on the player row (`lt`), and the hours left in it
a second (`lh`) — which is what draws the gauge above the stillness bar, and
which had to be sent in **hours rather than as a fraction** so that the row
carries no knowledge of the catalogue.

---

## 7k. Two games inside the game: tag, and Eye Spy

Two things a party can do to each other rather than to the wood. They are
grouped here because they share a problem the rest of this game does not have:
**every other verb in the Field Guide is something one person does, and both of
these are things one person does *to everybody else*.** Picking a berry costs
nobody anything. Starting a chase costs seven other people their afternoon.

That is why one of them has a vote in front of it and the other has a limit of
one a day. Neither restriction is about balance; both are about consent.

### Tag (`watch/Tag`)

A round has one person **it**, who moves at **1.3×**, **cannot move at all for
the first thirty seconds** after becoming it, and carries a **water gun** that
tags at range.

**The vote.** Pressing <kbd>T</kbd> opens a poll rather than starting a game.
It carries on a **strict majority of the party, not of the votes cast** — an
abstention is a no. That distinction is the whole rule: without it, two people
out of eight could start a chase while the other six were looking through
spyglasses. The poll closes early the moment its answer can no longer change
(everybody has answered, or enough have said yes, or enough have not), so a
party that is paying attention never waits out the half minute.

**The same key ends it.** <kbd>T</kbd> during a round suggests calling it off,
on identical terms. One verb, one mechanism, and no way for whoever is losing to
stop the game on their own. The round **keeps running while the party is being
asked** — a poll that paused it would hand whoever is it their freeze back — so
a round and a poll are two booleans on `Tag` rather than one state.

**Whoever asked is it.** Not a dice roll: it is what stops "let's play tag"
being a way of making somebody else run around for half an hour.

**The freeze is a refusal, not a request.** A client is the authority on where
it is standing, so the host cannot move somebody by writing a position into a
snapshot — the next `move` would put them back. What it *can* do is decline to
take one, and that is exactly what `WatchGame.move` does for thirty seconds:

```java
if (tag.frozen(id)) {
    player.moveTo(player.x(), player.y(), player.z(), yaw, pitch, crouching, dt);
    return;
}
```

Their head still turns. Being frozen is standing still and counting, not being
switched off, and watching everybody scatter is most of what those thirty
seconds are for.

The screen obeys the same rule from the other side (`WatchScene.walk` zeroes the
input while `Tag.speed` is nothing), and that is not the rule said twice: without
it a player would walk away from their own body and be silently pulled back
twenty times a second, which reads as the game having broken rather than as a
count of thirty.

**The water gun is a `Hurl`.** The one other thing in this world that flies is a
wendigo's bone shard, and a jet of water is the same problem — a thing let go at
a speed, in a direction, that arcs, that is checked against people every tick and
dies on the ground. So it is that class again rather than a second copy of it,
and the only difference is what happens on arrival: `flyHurls` reads the species
key and either wounds somebody or makes them it. A **contact** tag was never an
option: positions are only as synchronised as the last snapshot, so walking into
somebody would be a tag that landed on one screen and missed on another.

The one rule a jet needs that a shard does not is `Hurl.owner()`. It leaves half
a metre in front of the shooter's chest, which is well inside `HIT_RADIUS` of it
— without knowing whose it is, **every shot would tag the person who fired it on
the tick they fired it.**

The gun is a real item in a real satchel (`Forage`, `ItemModel`) so the hands
hold it and the party can see who has it, but whether a shot is allowed is asked
of the *round*, never of the bag: a gun dropped by dying must not be a way to
keep tagging people.

**The compass is not in this class, deliberately.** Every walker's position is
already in every snapshot, so the needle that points at the nearest one is
something each screen works out for itself (`WatchView.nearestOther`,
`WatchScene.drawQuarry`) — drawn on the compass strip, because it is a bearing
and that strip is where this game keeps bearings. A host answering it would be
answering a question twenty times a second that its client can answer for free,
and a needle a snapshot behind at a dead run points where somebody used to be.
It is shown **only to whoever is it**: given to everybody it would end every
round in about forty seconds, as the field simply spread out along the vectors
they were shown.

**It is weather.** Nothing about a round is saved. A poll that was open when
somebody closed the game is not a poll anybody is still thinking about, and a
walk reopened three days later with one player unable to move for half a minute
is a bug however faithfully it restores what was happening.

### Eye Spy (`watch/Bounty`)

One walker names an animal; the **world** prices it at somewhere between ten and
a hundred points; anybody but the person who asked can claim it by spotting one.

**The host rolls the number.** A reward somebody sets themselves is a reward they
set to a hundred every time, and a board of hundreds is a board with no news on
it. Only the species travels — `WatchProto.bounty` carries no price, because a
client that sent one would be a client awarding itself a hundred points a day.

**One a day, per walker,** on the real calendar (`Bounty.dayOf`), which is the
only clock this game has ever used. The point of the limit is that a bounty is a
thing you thought about.

**Whoever asked cannot answer**, which is the one rule the whole board rests on
and is a single `continue` in `Bounty.claim`. The points go into the **shared**
ledger, like every other point in this game — there is one book, one page and one
purse — and the finder's name goes on the posting rather than on the money.
`FieldGuide.reward` exists for this and only this: a claim cannot go through
`credit`, which is about a species and refuses anything already on the open page,
and a bounty on a wren you have logged forty times is still a bounty.

**A sighting is a sighting**, so both places one is written ask the board: a
spot, and a landed fish. Somebody who puts a hundred points on something that can
only be caught has asked for a fishing trip, which is a perfectly good thing to
ask for.

**The shortlist is a convenience, not a rule.** Thirteen hundred species is not a
menu, so the screen offers a dozen: the biome underfoot, with the ones already in
the book first, and never one the board is already carrying. Both ends compute it
from the registry, the shared book and where you are standing — three things a
client already has — so nothing about the list travels and the host validates
only what it has to: the species exists, the walker has not posted today, and the
board has no open bounty on it already. A bounty on something nobody has ever
seen is a perfectly good bounty and is arguably the best kind.

**It is kept**, unlike the tag round beside it, because a bounty is a promise: a
party that logs off with four open should log back on to four open, and to the
day limit remembering that it has been spent. They expire after twenty-four real
hours, so a board is a list of things worth doing this week rather than an
archive of every animal anybody ever wondered about.

### What travels

The **round** rides the snapshot, beside the shards and for their reason: a
freeze counting down and a poll running out change every tick, and a client told
only "a round started" would be running both clocks itself — which is two clocks
disagreeing about whether somebody may move yet. The field is left out entirely
while nothing is happening, which is every snapshot of nearly every walk, and it
is *replaced* rather than merged for `FieldGuide`'s tally's reason: a round that
has ended is an absence.

The **board** rides the five-second world sync with the grove and the buildings,
because it changes a handful of times a day rather than a handful of times a
second. What makes that bearable is `Bounty.version`: three things change the
board and they arrive by three different routes — a posting is a request, a claim
rides inside a sighting that already has a message of its own, and an expiry
happens on a tick with nothing behind it at all — so `WatchServer` watches one
counter instead of three verbs, exactly as `announceDeaths` watches the respawn
counter. `announceTag` does the same for the water gun changing hands, which
moves two satchels on a channel that otherwise only fires when a request has been
answered.

### The keys

| Key | What |
|---|---|
| <kbd>T</kbd> | Suggest a game · suggest calling one off · vote yes |
| <kbd>U</kbd> | Vote no |
| <kbd>Q</kbd> | Water gun (only while it, and only once thawed) |
| <kbd>J</kbd> | The Eye Spy board |

<kbd>T</kbd> does three things because they are one intention with a state
attached, in the same way <kbd>N</kbd> lights and douses one lamp. Both vote keys
are read **above the panel branch**, so a poll can be answered from the satchel
screen — a poll is open for half a minute and an abstention is a no, and somebody
who happened to be reading a recipe should not be voted against by their own
inventory. The card is drawn over the panels for the same reason, beside the
damage flash.

---

## 8. Tests

`src/test/java/com/larsons/engine/watch/`

* `TerrainFieldTest` — determinism, biome coverage, water below the line,
  trails that actually flatten, chunk edges that match their neighbours.
* `WatchBiomesTest` — twenty biomes, unique keys, the seven named ones present,
  every one reachable from some climate.
* `AnimalRegistryTest` — ≥ 1000 species, unique keys and names, deterministic
  rebuild, rarity and biome coverage, tameable subset non-empty. Its two
  per-family invariants ("at least twenty of each", "at least six colours") are
  scoped to the *generated* families: a hostile family holding twenty species
  would be the exact failure `Mutants` exists to prevent.
* `MutantTest` — the three of them, end to end. **That there are three**: hostile
  families are one species each rather than forty-nine, nothing else in the book
  hunts anybody, they are mythic and worth five legendaries. **That the wood is
  safe**: no two share a biome, most of the world has none, a mutant is offered
  at a flat zero outside its own hours while an ordinary species keeps its soft
  edge at every hour, at most one is alive across four thousand ticks of a
  four-player walk, and fewer than one animal in fifty a walking player meets is
  one. **That they differ**: three powers, three body plans, three colours,
  three damages, each between three and eight blows from a full bar, and each
  giving up further out than it notices. **That they hunt**: a wendigo sixty
  metres from a player who is standing perfectly still — in a world whose
  `disturbanceAt` says nobody is anywhere near — closes twenty metres in thirty
  seconds, lands blows, is drawn mid-swing, and gives up when the player is
  properly clear; a wren in the same clearing hunts nobody. **That health is a
  bar**: nothing heals inside the delay, it comes back slowly afterwards, and it
  fills. **That dying costs the walk back**: the satchel is dropped where they
  fell rather than deleted, they wake whole at the spawn point with the respawn
  counted once, gathering it two hundred metres away is refused and gathering it
  at the heap gives everything back, an empty bag leaves no heap, and a heap
  survives a save. **That a tester can get one on demand**: a summon is refused
  without the code and granted with it, an unknown species summons nothing, all
  three arrive at midday twenty metres out standing on the ground with the cap
  and the cooldown ignored, and what arrives is hunting rather than posing.
  `DebugModeTest` adds the other end of that, through the real scene: K does
  nothing before the code is typed, and three presses after it produce three
  different mutants — which is the only place the raw key binding and the cycle
  can be tested at all, since neither exists anywhere else.
  **That two of them hold a sprint**: the werewolf's between-burst speed and the
  mirewraith's flat pursuit are `WatchPlayer.RUN_SPEED` to a tenth of a metre a
  second, the wendigo's sits between a walk and a run, and all three swing no
  faster than every two seconds. **That the wendigo throws**: only it has an arm,
  its reach is far beyond its grasp, it hurts less at range than in it — and,
  driven directly over flat ground, a shard connects with a stationary target
  from every distance in its band. That last one is a *unit* test on `Hurl` and
  deliberately not a world one: over real terrain a shard buries itself in a rise
  and a thrower ten metres downhill falls short, both of which are the physics
  working, and a test that cannot tell those from a broken arc is a test that
  measures the landscape. The decision to throw is tested through the animal
  instead — at range it throws and cannot reach; inside its minimum it swings and
  never throws. **That they do not walk like animals**: the legs are not exactly
  opposed, the two sides do not stride equally far, a run is the walk wound up
  rather than a different creature, and every one of them poses measurably
  differently from the shared animal table it would otherwise fall back to.
  **That the glows read**: each is four times brighter than the socket beside it,
  bright enough to carry at night, and the two red pairs are red while the
  mirewraith's is not — plus that the two new skin regions took nothing from the
  eight old ones. **That they have voices**: every key the game plays is in the
  catalogue a creator reads, the catalogue lists nothing that will never play,
  only the thrower is asked for a throwing sound, and one file named for a
  creature covers every state it has not been given its own.
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
* `LightingTest` — the four layers of §7j. **The catalogue**: every light names
  items, fuel and costs that exist, and every carried one can actually be made.
  **The world state**: a fire burns down over real hours and leaves a lightable
  hearth, a torch is spent and gone, feeding relights and is capped at a full
  charge, two lights cannot stand inside each other, and every standing light
  survives a save and the wire with whatever is left in it. **The verbs**: one
  key lights and douses, dousing keeps the oil (the exploit that would otherwise
  make the fuel economy a formality), an empty lantern is filled from the
  satchel, empty hands build a fire out of branches and stones, what is in your
  hand goes down before a campfire does and keeps its oil, standing at a fire
  offers to tend it and E feeds it, taking a lit lantern back keeps it burning,
  dying drops the lamp with the bag, and a reopened walk finds the camp.
  **The picture**: everything burning reaches the frame and a cold hearth does
  not, a mutant lights the ground in its own glow colour at chest height, forty
  lanterns are capped at sixteen and the fire you are standing at is not the one
  dropped, the falloff is compact and the wrap share is the constant both
  backends read — and, through the painter end to end, a campfire measurably
  brightens and warms the ground in front of it while one four hundred metres
  away changes nothing. The last of those also pins the bug §7j opens with: at
  midnight the world is a quarter of its noon brightness, which is the number the
  card is now handed and was not before.
  `gl/…/GlLightingTest` is the other end of it, on a real driver and skipped
  where there is none: the world shader compiles with its lighting block, the
  driver keeps the uniforms (an array looked up as `uLightPos` rather than
  `uLightPos[0]` comes back −1 and every write to it is accepted and discarded),
  a full block of sixteen uploads cleanly, one too many is clamped rather than
  written past the array, and binding the program leaves the block neutral —
  which is what keeps `GlTerrainPass`, which shares the shader and knows nothing
  about any of this, drawing exactly what it drew before.
* `WatchSceneTest` — the mini game is on the launch strip, the scenes register,
  the lobby's menu offers what it should.
* `TagTest` — the four rules of §7k, in order. **The party decides**: a walk for
  one cannot have a round at all, suggesting it counts as wanting it, two votes
  out of four do not carry and three do, and a poll closes the moment its answer
  can no longer change rather than waiting out its clock. **The freeze is
  enforced, not requested**: a walker who has just become it sends a position
  forty metres away and the world keeps the one they were tagged at, their head
  still turns while it does, and both of those stop the moment the count runs
  out — after which they are measurably 1.3× and everybody else is not. **The
  water gun tags**: it is refused to everybody but whoever is it and to whoever is
  it while they are still counting, it will not fire twice without reloading, a
  jet fired at nobody cannot tag the person who fired it (which is the bug
  `Hurl.owner` exists for), and one that lands makes its target it, freezes them
  from the top, moves the gun into their satchel and credits the tag. **It is
  weather**: a save carries no round and no poll, and a client that stops being
  told about one forgets it rather than leaving somebody frozen for the rest of
  the walk. Then the same thing twice more from outside: two real clients on a
  socket voting themselves into a round and watching the gun appear in one
  satchel, and the walk itself — a scene, no window — holding a frozen walker
  still while <kbd>W</kbd> is held down, letting them run once the count is over,
  and answering a poll with <kbd>U</kbd> from a screen.
* `BountyTest` — the four rules of Eye Spy. **The world prices it**: five hundred
  rolls are inside the band, and genuinely vary, which is the one of the three
  that a fixed price list would also satisfy. **One a day**: the second posting of
  a day is refused with a reason a player can act on, another walker's allowance
  is their own, and tomorrow comes round. **Whoever asked cannot answer**: the
  poster spotting their own quarry takes nothing and leaves it up for somebody who
  can have it, and the next walker to see one is paid — through the real
  simulation, with the animal summoned rather than waited for, because a test
  that walked about hoping the right species turned up would fail by seed. **It is
  kept**: the board and the day limit both survive a save and the wire, an id from
  a loaded board is never reissued, and an unclaimed bounty comes down after a
  day. Plus what a screen offers — the shortlist is short, is the biome's own,
  puts what is in the book first, and never offers something the board is already
  carrying. Then the board through the walk: <kbd>J</kbd> opens it, Enter pins up
  what the cursor is on, and the species that goes up is the one that was shown.
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
