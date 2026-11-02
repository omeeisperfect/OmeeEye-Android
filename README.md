# OmeeEye Android — BASE

Personal Android automation app. No cloud, no account, no sync, no ads,
no paywall, no script limits. Everything is stored on the device.

This repository holds the **base** only. Later options are added on top of
it; they are listed at the bottom so nobody mistakes their absence for an
oversight.

Package: `com.nimu.omeeeye` (default — changeable in `app/build.gradle.kts`).
Language: Kotlin. minSdk 24, targetSdk 34. No root required.

> **Standing rule — pick-only locations.** Anywhere the app needs a
> location, the ONLY option is "Pick on screen". There is deliberately no
> typed coordinate field (no X/Y input) anywhere in the app. Points come
> from tapping the screen (`PickPointActivity`) or from recorded touches
> (`RecordActivity`). The only text inputs in the base are the workflow
> name, the workflow search box, and the Type-text step's content — none
> of those are locations.

## What the base contains

1. **Gesture engine** (`engine/`)
   - `AutomationService` — an `AccessibilityService` that performs taps,
     swipes, key presses (Back / Home / Recents), clipboard-paste typing,
     and app launches via `dispatchGesture` / global actions.
   - `WorkflowRunner` — runs a workflow on a worker thread: steps in order,
     per-step wait time, cycle interval between cycles, pause (holds between
     steps and during sleeps), and stop (terminal — no resume after stop).
     Sub-tasks run inline with a depth guard (max 5) and cycle detection.
     Every run is recorded to local run history.
2. **Home** (`ui/MainActivity`) — reference-style layout: title, search
   (filters by workflow name), top 3-dot menu (Sort, History, Settings,
   Trash), card list of saved workflows (name, cycle interval, cycles,
   per-card 3-dot menu, play button), bottom CREATE TASK (outline) and
   CLOSE (filled) buttons. No bottom tab bar in base.
3. **Task editor** (`ui/TaskEditorActivity`) — numbered steps with a
   per-step icon, copy / drag-reorder (handle only) / delete / play-one-step
   controls, "Wait: N ms" under each step, and ADD / CANCEL / SAVE.
   CANCEL and back press discard without saving.
4. **Add-step menu** (`ui/AddStepBottomSheet`) — grouped bottom-sheet menu,
   each row an icon + bold title + grey description, CLOSE button.
   Groups in base: **Basic** (Tap, Swipe, Type text, Wait, Record gesture,
   Navigation keys, Launch app) and **Sub-task** (Add sub-task). The menu
   is data-driven, so later groups append without rewriting it.
5. **Run / stop + floating overlays** (`ui/FloatingService`)
   - Idle bar (bound to one workflow): configure, play, target picker
     (opens the editor straight into point picking), close, expand.
   - Running panel: stop, pause, timer, step count, Back / Home / Recents,
     more. Stopping ends the run fully.
6. **Local storage only** (`data/`) — workflows in `workflows.json`,
   run history in `run_history.json`, both in internal storage. Trash is
   soft delete with restore / delete-forever.
7. **Settings / History / Trash screens** — permission status (accessibility,
   display-over-other-apps, battery unrestricted) with buttons that open the
   matching system Settings screen; run history list; Trash list.

## What is deliberately NOT in the base

These are later options, not missing pieces. Do not treat their absence
as a bug:

- Triggers (time, SMS, notification, shake, battery, …)
- Hide mode, unhide triggers, lock-to-stop, lock-screen execution
- Code library / Type code step
- Excel row typing, data-driven repeat
- Button / image / color recognition
- Subscriptions, paywall, ads, accounts, cloud, sync

## Permissions the user grants (all manual, in system Settings)

- **Accessibility service** — required for taps, swipes, keys, typing.
- **Display over other apps** — required for the floating bars.
- **Battery unrestricted** (optional, recommended) — keeps a run alive in
  the background.

## Building the APK

Local Android builds are **not** possible in the environment where this
base was written (no Java/Gradle there), so the build runs in CI:

- Push to GitHub → `.github/workflows/android.yml` runs
  `gradle :app:assembleDebug` (JDK 17, Android SDK 34, Gradle 8.7) and
  uploads `app-debug.apk` as an artifact.
- Download the artifact from the workflow run and install it on the phone.

To build on a machine with the Android SDK installed:

```sh
gradle :app:assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

(`gradle/wrapper/gradle-wrapper.properties` pins Gradle 8.7; generate a
wrapper locally with `gradle wrapper --gradle-version 8.7` if you want
`./gradlew`.)

## Status — read this before calling it done

**Not Tested.** No Android build, unit test, or device run has ever
executed for this base: the writing environment has no Java, no Gradle,
and no Android SDK, and no APK exists yet. What was verified instead:

- Every `R.layout` / `R.id` / `R.string` / `R.color` / `@style` /
  `@drawable` reference resolves to a real file or value.
- All layout/manifest XML parses; all 16 Kotlin files have balanced
  braces and parentheses.
- No coordinate text field exists anywhere (only name search, workflow
  name, and type-text content inputs).
- No later-option feature, secret, key, or token exists in the source.

Verification still required, in this order:

1. Create the public GitHub repo and push this tree (not done — no repo
   exists yet, and nothing was pushed from here).
2. CI builds the debug APK (first real compile of this code).
3. Install on the user's phone and test: enable the three permissions,
   create a workflow, pick a point on screen, run it from the card and
   from the floating bar, pause/stop, record a gesture, Trash/restore.

## File map

```
app/src/main/java/com/nimu/omeeeye/
  model/Workflow.kt            Workflow / Step / Point types
  data/WorkflowStore.kt        JSON workflow storage + step serialisation
  data/RunHistoryStore.kt      local run history
  engine/AutomationService.kt  AccessibilityService gestures
  engine/WorkflowRunner.kt     run loop: cycles, pause, stop, sub-tasks
  ui/MainActivity.kt           Home
  ui/WorkflowAdapter.kt        workflow cards
  ui/TaskEditorActivity.kt     task editor + step editors
  ui/StepAdapter.kt            step rows
  ui/AddStepBottomSheet.kt     grouped add-step menu
  ui/PickPointActivity.kt      tap-to-pick a point (only coordinate source)
  ui/RecordActivity.kt         record taps/swipes into steps
  ui/FloatingService.kt        idle bar + running panel overlays
  ui/SettingsActivity.kt       permission status
  ui/HistoryActivity.kt        run history list
  ui/TrashActivity.kt          Trash list
```
