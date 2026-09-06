# Making the forest ranger in Blender

This file is for a **local** Claude Code session — one running on your own
machine, with Blender open and a Blender MCP server attached. A cloud session
cannot reach Blender; see the note at the bottom for why.

There are three parts:

1. [Setting up](#1-setting-up) — twenty minutes, once.
2. [The brief](#2-the-brief) — the thing to paste into the local session.
3. [Committing it](#3-committing-it) — where the file goes and how to check it worked.

---

## 1. Setting up

**On your machine, not in a cloud session.**

```bash
# Claude Code, signed in to the same account
npm install -g @anthropic-ai/claude-code    # if you haven't got it

# A Blender MCP server. blender-mcp is the usual one; follow its README to
# install the companion Blender add-on and enable it in Blender's sidebar.
# Then, from a clone of this repository:
cd /path/to/Larsons-Game-Engine
claude mcp add blender -- uvx blender-mcp
```

Open Blender, enable the add-on's connection from its sidebar panel (`N` →
BlenderMCP → Connect), then start Claude Code in the repository and check the
tools are there with `/mcp`.

Work **inside a clone of this repo**. The local session can then read
`src/main/resources/watch/models/README.md`, which is the full specification —
the brief below is a summary of it plus the ranger's specific numbers, and if
the two ever disagree, the README is right.

---

## 2. The brief

Paste everything between the lines into the local Claude session.

---8<---

You have Blender available over MCP. Build a **forest ranger** character for the
Field Guide game in this repository, and export it as a `.glb`.

**Read `src/main/resources/watch/models/README.md` first**, especially §8–§15.
It is the contract this file has to satisfy. What follows is the ranger's own
specification; where it is silent, the README governs.

### The look

Chunky, cartoonish, low-polygon, flat-shaded. Think a wooden artist's mannequin
in a uniform: everything is a slightly-rounded box, nothing is smooth, every
surface is one flat colour. Big head, big hat, big boots, thick limbs — the
proportions are deliberately not realistic. There is an existing character in
this game, the trading-post keeper, in
`src/main/java/com/larsons/engine/watch/render/KeeperModel.java`; read it, and
match its density of detail. The ranger you are replacing is in
`RangerModel.java` in the same folder, written out box by box, and **it is the
thing to match** — read it before modelling anything.

The one rule that matters most: **this is not a realism exercise.** A ranger is
recognisable at two hundred metres from their silhouette — a wide flat hat brim
with a peaked crown, a bulky coat, binoculars on the chest — and everything else
is decoration on top of that.

### Size and proportions

Model with **+Z up**, facing **−Y** (Blender's Front view, numpad 1), centred on
the world origin, with the soles of the boots on `Z = 0`.

Total height **1.78 m**, sole to the crown of the hat. Every number below is in
metres, and they are the exact numbers the existing box model uses.

| Landmark | Z |
|---|---|
| sole of boot | 0.00 |
| ankle | 0.07 |
| top of boot cuff | 0.24 |
| knee | 0.37 |
| coat skirt centre | 0.60 |
| hip / belt | 0.69 / 0.78 |
| chest centre | 1.03 |
| binoculars at rest | 1.00 |
| shoulder | 1.18 |
| base of neck | 1.27 |
| head centre | 1.45 |
| hat brim | 1.59 |
| crown of hat | 1.78 |

Full sizes, not half-extents. **Width** is left-to-right, **depth** is
front-to-back.

| Part | Centre Z | Width | Depth | Height |
|---|---|---|---|---|
| head | 1.45 | 0.31 | 0.29 | 0.33 |
| jaw (under the head, narrower) | 1.32 | 0.26 | 0.25 | 0.10 |
| hat brim | 1.59 | 0.64 | 0.60 | 0.03 |
| hatband | 1.61 | 0.35 | 0.34 | 0.03 |
| hat crown (box under the peak) | 1.65 | 0.33 | 0.33 | 0.12 |
| collar | 1.25 | 0.25 | 0.23 | 0.06 |
| torso / coat | 1.03 | 0.33 | 0.29 | 0.46 |
| chest pockets (×2, ±0.075 across, 0.15 forward) | 1.06 | 0.11 | 0.04 | 0.11 |
| shoulder yokes (×2, ±0.15 across) | 1.17 | 0.15 | 0.26 | 0.06 |
| belt | 0.78 | 0.34 | 0.30 | 0.08 |
| coat skirt (below the belt) | 0.60 | 0.36 | 0.32 | 0.34 |
| satchel (right hip, +0.185 across) | 0.67 | 0.15 | 0.10 | 0.20 |
| boot | 0.04 | 0.18 | 0.27 | 0.08 |
| boot cuff | 0.16 | 0.18 | 0.20 | 0.17 |

- Shoulders are **±0.205** either side of centre; hips **±0.105**. The limbs are
  narrower apart than the coat is wide, which is what makes the coat read as
  worn over a body rather than as the body.
- Upper arm **0.28** long and **0.14** thick; forearm **0.26** long and **0.12**
  thick; hand a **0.11** cube.
- Thigh **0.33** long and **0.18** thick; shin **0.30** long and **0.15** thick.
- The hat's peak is a **four-sided cone** from Z 1.71 to Z 1.78, radius 0.16.
  This peak is the ranger's silhouette — the keeper's hat has a wider brim and
  no peak, and the two must be tellable apart from behind at distance.
- Binoculars sit **0.16 forward** of the chest centre at Z 1.00: two barrels
  **0.06 × 0.12 × 0.06** at ±0.036 across, a bridge between them, and lenses
  **0.02 × 0.05 × 0.05** on the front of each.

### Palette

These are exact sRGB hex values. Type them into Blender's colour picker as
Base Color on a Principled BSDF — flat values, **no texture nodes**. The
importer handles the colour-space conversion, so the hex you type is the hex
that appears in the game.

| Material | Hex | On |
|---|---|---|
| `coat` | `3C5240` | field coat, shoulder yokes, hat |
| `coat_dark` | `2E4033` | coat skirt, hat brim, under-surfaces |
| `coat_light` | `4A6450` | collar, cuffs, shoulder yokes |
| `trouser` | `6B6247` | trousers |
| `leather` | `4A3626` | boots, belt, satchel, binocular bodies |
| `leather_light` | `5C4433` | boot cuffs, satchel flap |
| `brass` | `B8A050` | belt buckle, hat badge, canteen |
| `glass` | `243230` | binocular lenses |
| `skin` | `C98F63` | face, hands |
| `hair` | `4A3220` | hair, brows, beard |
| `trim` | `A8442E` | neckerchief, hatband, bedroll |
| `eye` | `241C18` | eyes |

### What the ranger carries

All of these exist on the box model and should be on yours:

- a **campaign hat**: flat brim, hatband in `trim`, four-sided peaked crown, a
  small brass badge on the front of the crown;
- **binoculars** on a strap round the neck, resting on the chest at Z 1.00, with
  two barrels, a bridge between them, and dark lenses on the front;
- a **neckerchief** at the throat, with one corner hanging down the front;
- a **bedroll** rolled across the shoulders, in `trim`;
- a **belt** with a brass buckle, a **satchel** on the right hip and a
  **canteen** on the left;
- **patch pockets** on both sides of the chest;
- **boots** with a tall cuff over the trouser.

### Rigging

Build it in **separate, slightly overlapping pieces**, one per bone — not one
continuous skin. The importer assigns each triangle to a single bone, so weight
painting is wasted effort here; overlapping the pieces at the joints is what
stops daylight showing through when a limb bends.

Bones, named exactly like this (the names are the binding contract):

```
root
└── spine          → body
    ├── head       → head        (skull, face, hair, hat, neckerchief all parented here)
    ├── arm_l      → left arm
    │   └── hand_l
    ├── arm_r      → right arm
    │   └── hand_r
    ├── leg_l      → left leg
    │   └── foot_l
    └── leg_r      → right leg
        └── foot_r
```

Put the **neck bone at the base of the neck** (Z 1.27), not inside the skull —
the game turns the head to follow the player, about that bone, and a pivot in
the middle of the skull makes the head swivel like a turret.

The torso, belt, satchel, canteen, bedroll and coat skirt all go on `spine`.

### Animation

Optional, and worth doing in this order. Name the Blender actions exactly:

1. `idle` — 4–5 seconds, looping. Breathing, and a slow weight shift from one
   foot to the other. Very small: this is a person standing still, not swaying.
2. `walk` — about 1 second, looping. Opposite arm to opposite leg.
3. `alert` — a short look-up.

A model with **no animation at all is fine** and will still move — the game
falls back to a procedural breathing idle and a procedural walk. Ship `idle`
first and add the rest later; each one is an improvement, not a prerequisite.

Bake to keyframes on the bones before exporting. No IK, no constraints, no
shape keys — none of them are read.

### Budget

The box model you are replacing is **616 triangles**. Stay under **1200**.
Triangulate before export. No subdivision surfaces, no multi-segment bevels, no
smooth shading — everything here is flat-shaded, and a subdivided sphere costs
three hundred triangles to look exactly like a faceted one from six metres away.

### Export

1. `Ctrl+A → All Transforms` on every object. A mirrored (negatively scaled)
   object exports inside-out.
2. **File → Export → glTF 2.0 (.glb/.gltf)**
   - Format: **glTF Binary (.glb)**
   - Transform: **+Y Up** (the default)
   - Include: **Selected Objects** off, or everything selected
   - Data: **Apply Modifiers** on, **Animation** on if you made any
3. Save it to `src/main/resources/watch/models/characters/ranger.glb` in this
   repository.

### Checking it

From the repository root:

```bash
./gradlew :test --tests '*ModelImportTest*'
./gradlew run        # then walk to a trading post
```

The ranger stands about 4 m out in front of every trading post and off to one
side. If the file loaded, the figure there is yours; if it did not, the game
prints one line to stderr saying why and draws the boxes instead. The most
common causes, in the order they actually happen: nothing was selected on
export; the file went to the wrong folder; transforms were not applied.

---8<---

## 3. Committing it

The file goes at:

```
src/main/resources/watch/models/characters/ranger.glb
```

Commit it on a branch and push. A `.glb` is binary, so it will not diff — say in
the commit message what changed about the model, since that is the only record.

```bash
git add src/main/resources/watch/models/characters/ranger.glb
git commit -m "Ranger: modelled in Blender, idle and walk clips"
git push -u origin <your-branch>
```

Once it is pushed I can see it from a cloud session, run the import tests
against the real file, and tell you what the importer made of it — triangle
count, which bones bound to which joints, which clips it found, whether
anything was skipped.

**Other things you can drop in the same way**, all documented in the folder
README:

| File | Replaces |
|---|---|
| `characters/ranger.glb` | the ranger |
| `<species key>.glb` | one of the 1323 animals |
| `<family key>.glb` | all 49 animals of a family |
| `<anything>.obj` | any of the above, static, no animation |

---

## Why this has to run locally

A Blender MCP server is a process on your machine that talks to the Blender
add-on over a socket on `127.0.0.1`. A cloud session runs in a container in
Anthropic's infrastructure — its `localhost` is that container, which has no
Blender in it and no route to your machine. Its outbound network is HTTPS-only
through a policy proxy, so there is no way to reach a raw socket on your desktop
either.

You can bring this conversation to your machine rather than the other way
round — `claude --teleport <session-id>` pulls a cloud session, branch and all,
into your local terminal, where `localhost` means what you want it to mean.
