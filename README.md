# SketchSeed

A 100-day drawing habit app for Android. One prompt a day, revealed only when you
open the app.

The app never asks *"what do you want to draw today?"* — deciding is the part that
kills the habit. It says *"here's today's sketch"* and gets out of the way.

## How the journey works

Two counters that look similar are deliberately kept apart:

| | What it means | What a missed day does |
|---|---|---|
| **Day 17 of 100** | Your 17th finished sketch | Nothing. The journey waits. |
| **Streak** | Consecutive calendar days drawn | Resets to zero. |

So a bad week costs you the streak but never costs you a day of the hundred. You
still only get one prompt per calendar day — no bingeing to catch up.

**Nothing is readable ahead of time.** A prompt is revealed when it becomes today's
prompt, and the moment you mark it done the next one re-seals until tomorrow. The
journey grid shows finished days and locked squares, never a spoiler.

## What's in it

- **Today** — day counter, the prompt, difficulty, a reference search, mark-as-done,
  streak and progress.
- **Journey** — all 100 days as a grid of thumbnails. Tap any finished day to revisit
  the prompt and the sketch.
- **Day detail** — the full sketch, when you drew it, and the tip if you asked for one.
- **Settings** — backup, on-device AI, and reset.

## Reference search

Each prompt searches for `"<prompt> sketch"` rather than the bare noun, which returns
line drawings you can actually follow instead of photographs. Pinterest or Google
Images, your pick.

## On-device AI

Two ML Kit features, both running entirely on the phone. Nothing is uploaded.

**Document Scanner** (`play-services-mlkit-document-scanner`) handles sketch capture.
It finds the edges of the paper, corrects the perspective and flattens the shadow your
hand casts over the page, so a photo taken at an angle on a desk comes back looking
like a flatbed scan. Capture happens inside Play Services' own activity, so the app
never requests the `CAMERA` permission.

**Gemini Nano** (`genai-prompt`, ML Kit Prompt API) generates an optional one-line
drawing tip. This is a garnish, never a dependency: the API is beta, Gemini Nano is
absent on plenty of hardware, and every failure path resolves quietly to "no tip". It
is off the critical path of marking a day done, and it can be switched off entirely.

Prompt packs are hand-curated on purpose. A generated list would undermine the
difficulty ramp, which is the one thing a 100-day journey actually needs.

## Backup

Android Auto Backup, with **no account, no sign-in and no Drive API**. The platform
backs the app's data up to whichever Google account already backs up the device, and
restores it when you set up a new phone.

Backed up:

- journey progress and which prompt was drawn on which day
- completion dates and streak history
- sketch photos (optional — see below)

Auto Backup allows 25 MB per app, which a hundred full-resolution photos would blow
straight past. Two things keep it under:

1. Sketches are downscaled to 1600 px on the long edge and re-encoded as JPEG.
2. Settings has an **Include sketch photos** toggle. Because Auto Backup rules are
   static XML and cannot be flipped at runtime, the toggle physically moves the files
   between `filesDir/sketches` (backed up) and `noBackupFilesDir/sketches` (never
   backed up). Settings shows current usage and warns as you approach the limit.

## Tech

Single-module Kotlin app.

- Jetpack Compose + Material 3, dynamic colour where the device offers it
- DataStore + kotlinx.serialization for persistence — the whole journey is at most 100
  small records, so it is one JSON blob rather than a database. No Room, no schema
  migrations, no annotation processor.
- Coil 3 for image loading
- No authentication, no backend, no analytics

| | |
|---|---|
| minSdk | 26 (floor for the ML Kit Prompt API) |
| compileSdk / targetSdk | 36 |
| AGP / Gradle / JDK | 8.13.2 / 8.14.3 / 17 |

### Layout

```
domain/     pure Kotlin — streaks, journey rules, models (unit tested)
data/       DataStore repositories, prompt pack loading, photo storage
ai/         Gemini Nano tip generation
ui/         Compose screens, one package per screen
```

`domain/` has no Android dependencies, which is why the rules that matter — streak
maths and the reveal logic — are covered by fast JVM tests.

## Building

```bash
./gradlew assembleDebug
```

```bash
./gradlew testDebugUnitTest
```

Or open the project in Android Studio and run it.

## The prompts

100 prompts, 1–3 words each, ramping from `Apple` to `Self Portrait`:

- Days 1–30 — single objects with simple silhouettes
- Days 31–70 — structure, perspective, texture, animals
- Days 71–100 — scenes, interiors, faces

They live in `app/src/main/assets/prompts.json`. Editing that file will not rewrite
your history: each finished day snapshots the prompt text it was drawn from.
