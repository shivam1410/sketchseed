# Sketch Seed

A 100-day drawing habit app for Android. One prompt a day, revealed only when you
open the app.

The app never asks *"what do you want to draw today?"* — deciding is the part that
kills the habit. It says *"here's today's sketch"* and gets out of the way.

## How the journey works

The day number comes from the **calendar**, not from your personal progress. Day N is
the Nth day after the pack's `startDate`, which is baked into `prompts.json`. So
everyone running the same build sees the same prompt on the same date — with no
server, no accounts, and no sync. Just arithmetic on a date.

Three ideas that look similar are deliberately kept apart:

| | What it means | What a missed day does |
|---|---|---|
| **Day 17 of 100** | What the calendar says today is | Nothing — it moves on regardless |
| **Completed** | How many you've actually finished | Nothing. Missed days stay open |
| **Streak** | Consecutive calendar days you drew | Resets to zero, permanently |

**Missed days stay open.** If you skip day 8, your streak dies — that's the point of a
streak. But day 8's prompt is still there, and you can go back and draw it whenever.
Finishing it does *not* resurrect the streak, because you genuinely didn't draw that
day; the completion is recorded against the day you actually drew it.

That also fixes joining late. Install on day 40 and days 1–39 are all sitting there
waiting, so you still get the beginner ramp instead of being dropped into
`Country Road` on your first evening.

**Nothing ahead is readable.** Days the calendar hasn't reached are locked and don't
even carry their prompt text in memory. The grid shows finished sketches, open days,
and padlocks — never a spoiler.

### Sharing

Hand the APK to a friend and you're on the same prompt every day automatically.
Streaks and completion counts stay personal to each phone; only the prompt schedule is
shared. Change `startDate` and you fork the schedule for everyone on that build.

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
absent on plenty of hardware, and AICore will happily sit on a model download
indefinitely. Every stage is therefore bounded by a timeout, the UI says whether it is
*downloading the model* or *running inference* rather than showing an unqualified
spinner, and every failure resolves to "no tip". It is off the critical path of
marking a day done, and it can be switched off entirely.

The base model lives in AICore and is shared system-wide, so this app does not pull
down its own copy of Gemini Nano.

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
maths, the reveal logic, and back-filling — are covered by fast JVM tests.

## Building

```bash
./gradlew assembleDebug
```

```bash
./gradlew testDebugUnitTest
```

Photo storage is covered by instrumented tests, because the bug they guard against
lived in `BitmapFactory`'s bounds-decoding contract, which JVM stubs don't reproduce.
These need a connected device or emulator:

```bash
./gradlew connectedDebugAndroidTest
```

Or open the project in Android Studio and run it.

## The prompts

100 prompts, 1–3 words each, ramping from `Apple` to `Self Portrait`:

- Days 1–30 — single objects with simple silhouettes
- Days 31–70 — structure, perspective, texture, animals
- Days 71–100 — scenes, interiors, faces

They live in `app/src/main/assets/prompts.json`, alongside the `startDate` that anchors
day 1 to the calendar. Editing the prompts will not rewrite your history: each
finished day snapshots the prompt text it was drawn from.
