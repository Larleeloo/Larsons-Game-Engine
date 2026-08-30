# Field Guide — the mutants' voices

Fifteen sound files. Drop them in and the three things that hunt you stop being
silent.

This is the audio twin of `watch/models/README.md`, and it works the same way:
name a file correctly, put it in the right folder, and the game finds it. There
is nothing to register, nothing to rebuild, and no menu to visit.

---

## 1. Where the files go

Not here. **This folder is documentation**; the audio itself belongs in the
game's sound pack, which lives next to the runnable jar:

```
  <the game>/
  ├── LarsonsEngine.jar
  └── share/
      └── sounds/
          ├── soundpack.json     ← volume and pitch, per sound if you like
          ├── SOUND_KEYS.txt     ← every sound the game can make, generated
          └── watch/             ← you are here
              ├── wendigo_call.wav
              ├── wendigo_notice.wav
              └── …
```

Running from an IDE scaffolds `share/` for you with the folders and the key list
already written. `SOUND_KEYS.txt` lists these fifteen rows under **Field Guide
mutants**, so you never have to trust this document over the game.

**Formats:** `.wav`, `.mp3`, `.aif`, `.aiff`, `.au`. MP3 decodes through the
engine's own decoder, the rest through the JDK.

---

## 2. The fifteen names

| File | When it plays |
|---|---|
| `wendigo_call.wav` | one turns up in the world — **carries 900 m** |
| `wendigo_notice.wav` | it has seen somebody and started coming |
| `wendigo_step.wav` | a footfall, every 3.2 m it covers |
| `wendigo_strike.wav` | a blow landing |
| `wendigo_hurl.wav` | it throws a bone shard |
| `wendigo_impact.wav` | that shard arrives |
| `werewolf_call.wav` | …and the same five for the werewolf |
| `werewolf_notice.wav` | |
| `werewolf_step.wav` | |
| `werewolf_strike.wav` | |
| `mirewraith_call.wav` | …and for the mirewraith |
| `mirewraith_notice.wav` | |
| `mirewraith_step.wav` | |
| `mirewraith_strike.wav` | |

Only the wendigo throws anything, so only the wendigo has `hurl` and `impact`.
The key list will not ask you for `mirewraith_hurl`.

### One file is enough to start

A file named for the creature alone — `watch/wendigo.wav` — answers for **every
state it has not been given its own file for**. So one recording gives a wendigo
a voice, and you can replace its cry later without touching the rest. That is the
same progressive rule every mob sound in the engine follows.

Anything missing is **silence**. A pack can be one file or fifteen; nothing
breaks either way, and no state is a prerequisite for any other.

---

## 3. What each one is for

**`call` is the important one.** It is the only warning a player gets that the
wood has stopped being safe, and it deliberately carries **further than the
creature is ever drawn** — nine hundred metres, against a render distance of a
few hundred on a good card. Somebody who hears it and looks up at an empty
treeline has understood the situation exactly. Make it long, make it carry, and
do not make it pretty.

**`notice` is the turn.** It fires once, on the frame the creature stops
wandering and starts coming for somebody. Short and sharp — this is the sound a
player will learn to dread.

**`step` is the only one that repeats.** It plays once every 3.2 m of ground
covered, so it speeds up when the creature does — and these three run at a
sprinting player's pace, so it will be fast. Keep it short and quiet; it is
played at 70% and it will be played a lot.

**`strike` is a blow landing**, not a swing starting. The wind-up is visible in
the animation for about two seconds before this; that gap is what makes being
caught survivable, and this sound is the end of it.

**`hurl` and `impact`** are the wendigo throwing and the shard arriving. The
shard travels 24 m/s and lives up to four seconds, so there is real time between
the two — they should sound like a pair.

---

## 4. Everything else in this game is silent

On purpose. There are thirteen hundred species in the Field Guide and none of
them makes a sound: a wood full of generated bird calls is a wood where the
calls are wallpaper, and the whole proposition of the game is that you find
things by **looking**. Nothing is lost by that silence, because nothing you can
find can hurt you.

Three things can. And a creature that hunts you is exactly the case where sound
stops being decoration and becomes information — it is the only channel that
works when the thing is behind you, and behind you is where it is trying to be.

So please do not read this folder as the start of a full ambient mix for the
Field Guide. It is fifteen files, for three creatures, and the silence around
them is what makes them work.

---

## 5. How it is positioned

Every one of these is placed in the world and heard from where the player is
looking: panned by the component across their view, faded by distance, squared
so the near half of the range is barely touched. All of it goes through
`Sounds.playAt`, which is the engine's one distance-and-pan model — the same one
every mob and every meteor in the block world uses.

You do not need to do anything about this. Record the sound dry and mono; the
game does the rest. A stereo file will still play, but its own image will fight
the pan the game applies, so mono is better.

---

## 6. Volume and pitch

`soundpack.json` beside the folder takes a per-sound override:

```json
{
  "volume": 1.0,
  "pitch": 1.0,
  "pitchVariation": 0.08,
  "sounds": {
    "watch/wendigo/call": { "volume": 1.4 },
    "watch/mirewraith/step": { "volume": 0.6, "pitch": 0.85 }
  }
}
```

**Fresh pitch** is on by default: every one-shot plays a few percent off its
recorded pitch, drawn per playback, so a run of footfalls never sounds like a
stuck record. If you have recorded several variations yourself and want them
exactly as cut, turn `pitchVariation` down to `0`.
