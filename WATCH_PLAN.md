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

### 4.3 Building (`watch/build`)

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

### The eight that were missing

| Asked for | Where |
|---|---|
| A player model, and hands in first person | `watch/render/WalkerModel` — one articulated figure with a gait, drawn for every player; `hands` builds the view model in the camera's basis |
| Items with models, highlighted when picked up | `watch/render/ItemModel`; `WatchGame.pickTarget` → the ring and prompt in `WatchScene.drawReachHighlight` |
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
  spread across the registry ever ends up inside the ground.
* `ChunkCacheTest` — ground walked away from is kept rather than rebuilt, the
  cache honours its ceiling, a zero budget still plays, and a re-mesh bumps the
  revision a backend keys its buffers on.
* `DivingTest` — the server decides who is under water, the breath runs out and
  comes back, an aquatic animal turns up for a diver, and the state reaches the
  view both by snapshot and over the wire.
* `WatchRenderTest` — a frame draws triangles, sorted far to near, and nothing
  behind the camera reaches the target.
