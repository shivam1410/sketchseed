# Sketch Seed

A 100-day drawing habit app for Android. One prompt a day, revealed only when you
open the app.

The app never asks *"what do you want to draw today?"* — deciding is the part that
kills the habit. It says *"here's today's sketch"* and gets out of the way.

<p align="center">
  <img src="docs/screenshots/01-today.png" width="30%" alt="Today: the day counter, one prompt, and its difficulty">
  <img src="docs/screenshots/02-journey.png" width="30%" alt="Journey: completed days, streak, best streak and progress, over a grid of 100 days">
  <img src="docs/screenshots/03-settings.png" width="30%" alt="Settings: Google Drive backup, plain zip export, and reminders">
</p>
<p align="center">
  <em>Today · Journey · Settings — shown on a fresh install, so the grid is still locked.</em>
</p>

## How the journey works

The day number comes from the **calendar**, not from your personal progress. Day N is
the Nth day after *your* day 1 — recorded the first time the app runs, so whenever you
start you start at `Apple`. Just arithmetic on a date: no server, no account, no sync.

Three ideas that look similar are deliberately kept apart:

| | What it means | What a missed day does |
|---|---|---|
| **Day 17 of 100** | What the calendar says today is | Nothing — it moves on regardless |
| **Completed** | How many you've actually finished | Nothing. Missed days stay open |
| **Streak** | Consecutive days you drew | One gap can be forgiven; two cannot |

**Missed days stay open.** If you skip day 8, day 8's prompt is still there and you can
go back and draw it whenever. Finishing it does *not* put a sketch on day 8's date,
because you genuinely didn't draw that day; the completion is recorded against the day
you actually drew it.

### The day starts at 6am, not midnight

Someone still awake at half past midnight thinking *"I should draw today's"* is, by any
human account, finishing yesterday. Midnight is a boundary the clock cares about and
nobody else does — and treating it as the cliff meant drawing at 00:14 served you
tomorrow's prompt and counted the evening you were sitting in as missed.

So a drawing day runs 6am to 6am. Only the journey uses this clock; reminders read the
wall clock, because a 9am nudge means 9am.

Records store the wall-clock time they were finished at, but the day a sketch counts for
is decided once, when it's written, and never re-derived. Moving the boundary must not
silently renumber history. Sketches finished before the app kept times have no time to
recover, so they read as midnight — an assumption, not a measurement.

### One missed day can be forgiven, but it has to be earned

A single gap is bridged only if every one of the two days behind it that *existed* was
drawn. The condition is the whole point: forgiving every isolated gap unconditionally
would let you draw on alternate days forever and watch a streak climb on half the effort,
which makes the number meaningless.

"That existed" is load-bearing. A gap on your second day has only one day behind it, and
holding out for two would make the rule unsatisfiable exactly when a new habit is most
likely to slip.

The forgiven day is bridged, never counted — a streak is always the number of days you
actually drew, so it can never exceed the sketches behind it. Two gaps in a row can never
both be forgiven, because the day behind the second one is itself missing.

Because a streak counts **days** and progress counts **sketches**, back-filling a day
alongside another one makes the two disagree: three sketches drawn across two days is a
streak of two. That reads like a bug, so when the numbers diverge the Journey screen says
which it is rather than leaving you to work it out.

That also fixes joining late. Install on day 40 and days 1–39 are all sitting there
waiting, so you still get the beginner ramp instead of being dropped into
`Country Road` on your first evening.

**Nothing ahead is readable.** Days the calendar hasn't reached are locked and don't
even carry their prompt text in memory. The grid shows finished sketches, open days,
and padlocks — never a spoiler.

### Starting, and sharing

Day 1 is the day you first open the app, stored per install. That is a deliberate
choice with a real cost, and it is worth being explicit about which way it cuts:

- **Someone installing on any random day still begins at `Apple`.** They get the
  beginner ramp instead of being dropped into `Country Road` on their first evening.
- **Two people who start on different days are on different prompts.** The *order* is
  shared; the schedule is not. Install together and you stay in step; install a month
  apart and you never will.

An earlier version anchored everyone to a fixed `startDate` in `prompts.json` so any
two installs matched by date. That is still the fallback if no personal date has been
recorded, but a late installer losing the whole beginner ramp was the worse trade.

**Resetting re-anchors day 1 to today**, which is what makes it a genuine restart
rather than a wipe that leaves you stranded on day 40 with 39 days to catch up.

## What's in it

- **Today** — the day counter, the prompt, its difficulty, a reference search and
  mark-as-done. Nothing else: no streak, no progress bar, no numbers. Those live one
  tap away, because the point of this screen is that you look at it and start drawing.
- **Journey** — all 100 days as a grid of thumbnails, with every number: completed,
  current streak, best streak, and the progress bar. Tap any finished day to revisit it.
- **Day detail** — the full sketch, when you drew it, and anything extra you drew that
  day.
- **Settings** — reminders, Drive backup, export and import, the update check, and reset.

## Reference search

Each prompt searches for `"<prompt> sketch"` rather than the bare noun, which returns
line drawings you can actually follow instead of photographs. Pinterest or Google
Images, your pick.

## Capturing a sketch

Camera or gallery, chosen per sketch. Photography is delegated to whatever camera app
you already have via `ACTION_IMAGE_CAPTURE`, and the gallery route uses the system
photo picker.

Neither needs a permission — and the camera one specifically **because the app
declares no `CAMERA` permission**. The platform only starts requiring it for this
intent once an app declares it, so not declaring it is what keeps the flow
permission-free. The camera writes into a cache directory exposed through a
`FileProvider`; `PhotoStore` then downscales it into place and the temp file is
disposable.

An earlier version used the ML Kit document scanner for its auto-crop and de-skew.
That is gone: it framed sketching as document scanning, and it needed Play Services.

**No AI.** An earlier version generated a one-line drawing tip with Gemini Nano, on
the device. It is gone. It was a garnish on a screen whose entire premise is having
nothing to decide, it was absent on most hardware, and keeping it meant carrying a beta
dependency and a model-download state machine to sometimes say something a beginner's
book says better. Prompt packs stay hand-curated for the same reason a generated list
would undermine the difficulty ramp, which is the one thing a 100-day journey needs.

## Backup

A zip of the journey and every sketch, kept in a hidden folder in your own Google
Drive, uploaded shortly after you finish a day.

Android Auto Backup is deliberately **off** (`allowBackup="false"`). It looked like the
cheaper option — no account, no code — but it caps at 25 MB and silently drops
everything past it, it resets whenever the signing key changes, and the user can
neither trigger it nor see whether it worked.

Backed up: journey progress, which prompt was drawn on which day, completion dates,
streak history, and every sketch photo — including the extras.

Two things make it recoverable rather than merely present:

1. **One generation is kept.** A new backup renames the current one to
   `sketchseed-backup-previous.zip` before uploading. A backup that only ever holds
   the newest state is only as trustworthy as the state that produced it — anything
   that empties the journey locally would, one automatic run later, empty the backup.
2. **Restore replaces, and adopts before it deletes.** Merging two journeys would
   produce a state neither the user nor the streak maths could explain, so restore
   overwrites wholesale behind a confirmation. New sketches are copied into place
   before any old ones are removed, so an interruption leaves you with both copies
   rather than neither.

The Drive copy is hidden in `appDataFolder`, which means you cannot open or move it
yourself. **Export a copy** in Settings writes the identical zip to ordinary storage —
no account, no Drive scope, restorable into any build.

> **Signing and Drive are linked.** Authorization is granted to an OAuth client bound
> to the applicationId *and* the signing certificate. Re-sign the app with a different
> key and every Drive call fails until that certificate's SHA-1 is registered in the
> Google Cloud project. This is why the release build is signed with the debug
> certificate — see [Building](#building).

## Reminders

Three nudges a day, on by default, each at a time you set — 9am, 6pm and 10pm to
start with. Three rather than one because a single notification is easy to swipe away
and forget, and easy to miss entirely if it lands mid-meeting.

**Drawing today silences the rest of the day.** That is decided when each reminder
fires, not by cancelling anything when you finish, so it holds however the day played
out — app killed, day restored from a backup, clock rolled past midnight. The worker
asks the same question every time: is there something to draw right now?

All three slots share one notification, so a later nudge replaces an earlier unread one
instead of leaving three stacked for the same undrawn day. That only works on a
high-importance channel: at default importance the replacement made a sound but never
surfaced, which is indistinguishable from no reminder at all. Android locks a channel's
importance once created, so the channel id carries a version — raising it in place would
have reached nobody who already had the app.

WorkManager rather than exact alarms. A drawing reminder is fine arriving a few minutes
late, and this survives reboots and Doze without the exact-alarm permission Android now
guards closely. Denying the notification permission costs only the reminders; Settings
says so plainly rather than showing a toggle that looks on while nothing arrives.

**A nudge more than two hours from its slot is dropped.** Inexactness is the price of
not using exact alarms, but Doze, battery saver or an app upgrade can hold a job for
much longer — and a held job runs at the next opportunity, which is usually the moment
you open the app. Without a check on how late it is, opening the app hands you a
reminder for a slot long gone, still captioned "morning" at teatime. Saying nothing is
better than saying the wrong thing.

Reminder delays are elapsed durations, so moving timezone leaves a pending one counted
out at the old offset. A receiver on `TIMEZONE_CHANGED` and `TIME_SET` re-anchors them —
neither needs a permission. Not `BOOT_COMPLETED`, which does: WorkManager restores its
own jobs across a reboot. A DST rollover inside one zone broadcasts neither, so that
still lags until the next launch.

Each slot is re-enqueued rather than updated in place. WorkManager fires periodic work
at `last_enqueue_time + initialDelay`, and updating preserves the original
`last_enqueue_time` — so a correctly computed delay was being measured from whenever the
slot was first created, and every reminder fired early by exactly that gap. Hours, on an
install of any age. Re-enqueueing resets the anchor, which is the only thing that makes
the time you picked the time you get.

## Finding out there is a new version

This app is sideloaded, not installed from Play, so nothing tells you a new build
exists. Every release so far relied on you going and looking, which means the restore
fix in 1.2.2 — the one that stops a hand-edited backup deleting your sketches — only
reached people who happened to check.

**Settings asks GitHub, and nothing else does.** Opening Settings triggers one
unauthenticated GET against the public releases feed, at most twice a day; tapping the
row asks immediately. Nothing about you or the phone is sent, there is no account, and
no other screen mentions updates. The Today screen is the one place the app has to stay
out of the way, and a version number is not a reason to draw.

Twice a day rather than every visit because the check rides on a screen you can open
repeatedly in a minute, and the feed allows sixty calls an hour per address before it
starts refusing. The timestamp is written even when a check *fails*, since a refusal is
exactly when a retry loop would do the most damage.

**A version it cannot read is not an update.** Tags are typed by hand, and this project
has published both `v1.1` and `v1.1.1`, so the comparison pads the shorter side with
zeros and compares components as numbers — `1.10` is after `1.9`, which sorting text
gets backwards. A tag that will not parse, a draft, a pre-release, or a release with no
APK attached all resolve to "say nothing". Offering to replace a working app is a claim
worth being sure of.

### Downloading and installing it

Three separate things stand between a published APK and a replaced app, and only one of
them is this app's own work:

1. **Where it came from.** An asset URL that is not on a GitHub host is refused, so the
   address being fetched is never simply whatever a field in the reply said.
2. **What arrived.** The bytes are checked against the SHA-256 GitHub publishes before
   anything is handed on. A truncated or altered download is thrown away rather than
   shown to you as something to approve. Nothing to compare against is treated as a
   failed check, not a passed one — that inversion is how verification becomes decor.
3. **What it may replace.** Android refuses to install an APK signed with a different
   certificate over an installed app. That is the real guarantee, it belongs to the
   platform rather than to this code, and nothing here can weaken it.

The install itself is always yours. The app opens the system installer and Android asks;
it cannot install anything silently, and `REQUEST_INSTALL_PACKAGES` only buys the right
to *ask*. That permission is granted by you in system settings and never by a dialog, so
the app checks at the moment you tap Install and sends you there if it is missing —
asking earlier would be a question with no context attached.

**Play Protect gets a say too, and it is worth expecting.** On a phone signed in to a
Google account, confirming the install is usually followed by *"App scan recommended —
Play Protect hasn't seen this app before"*. That is Google reacting to a sideloaded APK
with few installs, not to anything about this one, and it appears however the APK
arrived. Either choice gets you there: **Scan app** sends it to Google and continues, or
**More details → Install without scanning** skips it. Nothing in the app can pre-empt
this, and nothing should — it is the platform telling you it does not recognise what you
are installing, which is exactly true.

The download lands in the cache directory, which is cleared at the start of every
attempt: there is no resume story here, and a cache quietly filling with old APKs is the
kind of failure nobody notices. It is written under a temporary name and renamed only
once verified, so an interrupted transfer can never be mistaken for a finished file.

## Extra sketches

Some prompts take a minute. A finished day can hold any number of **extra sketches**,
each with a title and an optional photo, from either the home screen or day detail.

They are deliberately inert: extras never count toward the hundred, never move the
streak, and never unlock the next day. Otherwise a fast prompt becomes a way to buy
progress, and one-a-day stops meaning anything. Unit tests pin that down.

## Tech

Single-module Kotlin app.

- Jetpack Compose + Material 3, dynamic colour where the device offers it
- DataStore + kotlinx.serialization for persistence — the whole journey is at most 100
  small records, so it is one JSON blob rather than a database. No Room, no schema
  migrations, no annotation processor.
- Coil 3 for image loading
- No authentication, no backend, no analytics. The only servers the app ever talks to
  are your own Google Drive, and GitHub's public releases feed when Settings is open.

| | |
|---|---|
| minSdk | 26 (`java.time` without desugaring, per-app install permission) |
| compileSdk / targetSdk | 36 |
| AGP / Gradle / JDK | 8.13.2 / 8.14.3 / 17 |

### Layout

```
domain/     pure Kotlin — streaks, journey rules, models (unit tested)
data/       DataStore repositories, prompt pack loading, photo storage
backup/     Drive client, archive format, restore
notify/     reminder scheduling and the slots
update/     release check, version comparison, APK verification (unit tested)
ui/         Compose screens, one package per screen
```

`domain/` has no Android dependencies, which is why the rules that matter — streak
maths, the reveal logic, and back-filling — are covered by fast JVM tests. The same
applies to the parts of `update/` that decide anything: version comparison, reading the
releases feed, the check interval, and the digest check are all plain Kotlin, and the
feed parser is pinned against a reply GitHub really sent so an upstream rename shows up
as a failing test rather than as a check that silently never finds anything.

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

### A shareable APK

```bash
./gradlew assembleRelease
```

Produces a minified ~2.3 MB APK at `app/build/outputs/apk/release/app-release.apk`,
installable by anyone who sideloads it.

It is signed with the **debug** certificate on purpose. Drive authorization is bound to
the applicationId plus a signing certificate, and the debug one is what is registered;
signing with a fresh release key authorises fine and then fails every Drive call. To
move to a real release key, generate a keystore, register its SHA-1 in the Google Cloud
project alongside the existing one, then point `signingConfigs` at it. Play will not
accept a debug-signed APK, so that step is required before publishing.

## The prompts

100 days ramping from `Apple` to `Self Portrait`. Each day is a short subject — short
because it doubles as the image-search term.

The sequence is ordered by **skill, not by subject**, which is the order the teaching
material converges on: line and shape, then proportion, then perspective, then value.

| Days | Practising |
|---|---|
| 1–14 | Line and shape confidence |
| 15–26 | Proportion and measuring |
| 27–40 | Form and ellipses |
| 41–54 | Perspective |
| 55–66 | Value and shading |
| 67–76 | Texture |
| 77–87 | Organic form and gesture |
| 88–96 | Faces |
| 97–100 | Composition |

Difficulty climbs continuously rather than in three flat blocks, and individual days
are rated on their own merits — `Bicycle Wheel` at day 49 is HARD while sitting inside
the perspective run, because a wheel is an ellipse problem most beginners lose an hour
to. Faces get ten days of runway before the finale instead of arriving cold.

They live in `app/src/main/assets/prompts.json`, alongside a `startDate` that only
applies before first launch records your own — see
[Starting, and sharing](#starting-and-sharing). Editing the prompts will not rewrite
your history: each finished day snapshots the prompt text it was drawn from.
