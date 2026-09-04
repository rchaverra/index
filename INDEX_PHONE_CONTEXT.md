# Index Phone Fork — Persistent Context Pack

> This file is the authoritative context for the **Index Phone** customization of the Core Devices `mobileapp` repository.
> It is intended to be read by every AI coding agent before making changes to this fork.
>
> **Baseline inspected:** user-provided `index-dev.zip`, described as an unmodified fork of `coredevices/mobileapp`.
> **Phase:** 0 — repository archaeology / architecture mapping.
> **Status:** No source files modified during Phase 0.

---

## 1. Mission

Create a phone-only Android experience around the existing **Index 01** functionality in the Core Devices mobile app.

The desired product behavior is:

1. Launch directly into the existing Index feed experience.
2. Do not expose Pebble watch/device onboarding, device selection, login screens, bottom navigation, or unrelated app sections in the normal user experience.
3. Preserve the existing Index feed, recording/processing pipeline, Index actions, settings, data model, integrations, and behavior wherever possible.
4. Remove/suppress visible Pebble branding that is part of the generic app shell.
5. Add an Android-only phone input layer that makes the physical **Volume Up** button emulate the existing Index 01 button gesture vocabulary.
6. The Volume Up gesture system must ultimately work from the Index app, other foreground apps, the home screen, the lock screen, and with the screen off, subject to what Android actually permits on the supported API levels and the device/OEM.
7. Do not duplicate or rewrite the existing Index recording/processing pipeline. Phone-captured audio must enter the existing pipeline at its supported boundary.
8. Keep fork-specific changes isolated and minimal so upstream `coredevices/mobileapp` updates can be merged/rebased with as little conflict and troubleshooting as possible.

---

## 2. Non-negotiable engineering principles

### 2.1 Thin customization layer

Treat this fork as:

`Core Devices upstream + a small, isolated Index Phone layer`

Do **not** turn the fork into a rewritten application.

### 2.2 Preserve upstream implementation

Prefer, in order:

1. Existing extension points / existing abstractions.
2. A small adapter/wrapper around existing code.
3. A new Android-specific class in a clearly isolated package/module location.
4. A very small targeted edit to upstream code only when there is no cleaner seam.

Avoid modifying existing Index recording, transcription, agent, database, or BLE implementations unless absolutely required.

### 2.3 Surgical changes only

Before editing a file, determine whether the change can be made by adding an adapter or changing a single routing/visibility decision elsewhere.

Do not perform opportunistic refactors, formatting sweeps, dependency upgrades, architecture migrations, or unrelated cleanup.

### 2.4 Small commits

Each feature phase should be a separate Git commit or a small series of single-purpose commits.

Suggested progression:

- `index-phone: phase 1 baseline`
- `index-phone: phase 2 direct index launch`
- `index-phone: phase 3 hide generic app chrome`
- `index-phone: phase 4 phone gesture engine`
- `index-phone: phase 5 global volume key capture`
- `index-phone: phase 6 volume hold recording`
- `index-phone: phase 7 full gesture routing`

Actual commit names may differ, but keep the separation.

### 2.5 Context Pack maintenance rule

Every coding phase must update this file with:

- phase completed
- files added/modified
- reason for every fork-specific modification
- relevant tests
- Android/platform assumptions
- known limitations
- upstream merge sensitivity

Do not allow the context pack to become a second implementation/specification that diverges from the code. It records intent, architecture, and deltas.

---

## 3. Baseline repository architecture found in Phase 0

Top-level project is Kotlin Multiplatform + Compose Multiplatform.

### Relevant modules

| Module | Role | Index Phone relevance |
|---|---|---|
| `composeApp` | Shared app shell, UI, navigation, Android/iOS platform entry support, Koin wiring | **High** — launch/navigation/Android entry points live here |
| `androidApp` | Android application wrapper: manifest, application ID, build/signing/R8 | **High** — Android manifest and final app packaging |
| `experimental` | Current Index 01 / Ring feature implementation | **Very high** — most Index behavior already lives here |
| `libindex` | Index hardware/device plumbing | **High** but should largely remain untouched for phone-only mode |
| `index-ai` | Index AI assistant/data-layer support | **High** but should be reused, not rewritten |
| `pebble` | Generic Pebble watch UI, navigation, device features | **Medium/high** for current shell; likely mostly hidden by phone mode |
| `util` | Shared utilities and permissions | **Medium** |
| `cactus`, `resampler`, `krisp-stubs` | Audio/ML support | **Reuse only through existing pipeline** |

Root `settings.gradle.kts` includes these modules directly.

---

## 4. Android baseline

From `gradle/libs.versions.toml`:

- compile SDK: **37**
- min SDK: **26**
- target SDK: **36**
- AGP: **9.3.1**
- Kotlin: **2.4.10**
- Java/JVM toolchain: **17**
- Compose Multiplatform: **1.11.1**
- AndroidX Activity Compose: **1.13.0**
- AndroidX Navigation: **2.9.2**
- AndroidX Lifecycle: **2.11.0**
- WorkManager: **2.11.2**
- Koin: **4.2.2**

Android application ID in `androidApp/build.gradle.kts`:

`coredevices.coreapp`

The Android shell declares `MainApplication` and `MainActivity` from `composeApp`.

---

## 5. Application startup / navigation map

### Android entry

`composeApp/src/androidMain/kotlin/coredevices/coreapp/MainActivity.kt`

Important behavior:

- Uses `ComponentActivity` + `setContent { App() }`.
- Injects the existing Pebble/Index-related delegates.
- Calls `pebbleDelegate.initPostPermissions()` during creation.
- Handles deep links.
- On resume, resumes Pebble app delegate and retries background service startup.

This is a likely Android hook for a future phone-mode bootstrap, but Phase 0 does **not** prescribe changing it yet.

### Application

`composeApp/src/androidMain/kotlin/coredevices/coreapp/MainApplication.kt`

Important behavior:

- Starts Koin modules including `experimentalModule`.
- Calls `experimentalDevices.appInit()`.
- Calls `experimentalDevices.init()` indirectly through existing application/delegate lifecycle wiring.
- Initializes notifications and other app-wide services.
- Starts/monitors existing Pebble background infrastructure.

A phone-only input service may eventually need Android lifecycle/bootstrap wiring here, but first investigate existing service/DI seams.

### Shared app shell

`composeApp/src/commonMain/kotlin/coredevices/coreapp/ui/App.kt`

Current start destination logic:

- if `SHOWN_ONBOARDING` is false → `CommonRoutes.OnboardingRoute`
- otherwise → `PebbleRoutes.WatchHomeRoute`

Therefore the generic onboarding/login path is selected by app-level state before the Index tab can be shown.

### Main navigation

`composeApp/src/commonMain/kotlin/coredevices/coreapp/ui/navigation/AppNavHost.kt`

Important findings:

- `experimentalDevices.addExperimentalRoutes(...)` registers Index/Ring detail routes.
- `addPebbleRoutes(...)` registers `PebbleRoutes.WatchHomeRoute` and passes the existing Index composable into the generic watch-home shell.
- `ExperimentalDevices.IndexScreen(...)` renders the existing Index feed.
- Ring/Index detail routes are deliberately classified as `RingRoute` and can remain inside the inner navigation stack.

This is a major architectural seam for phone mode: **the Index experience is already callable separately from most Index internals.**

---

## 6. Generic watch-home shell and bottom navigation

`pebble/src/commonMain/kotlin/coredevices/pebble/ui/WatchHomeScreen.kt`

`WatchHomeViewModel` chooses its initial tab with logic equivalent to:

- Index only when `enableIndex` is true **and** an Index ring is paired.
- otherwise Watch Faces if a fully connected watch exists.
- otherwise Watches.

The shell exposes tabs including:

- Watch Faces
- Index
- Watches
- Health
- Notifications
- Settings

The shell also owns the visible Material 3 bottom `NavigationBar`.

The Index tab currently suppresses the shell's **top** app bar because Index screens provide their own header, but the **bottom navigation remains owned by the generic shell**.

This suggests a safer phone-only approach is likely to bypass or specialize the shell rather than delete generic Pebble routes.

Do not assume that the existing `WatchHomeScreen` must be heavily modified; first test whether a phone-mode route can enter the existing Index screen more directly.

---

## 7. Existing Index feed entry point

`experimental/src/commonMain/kotlin/coredevices/ExperimentalDevices.kt`

`ExperimentalDevices.IndexScreen(...)` already directly invokes:

`IndexFeedScreen(...)`

It also supplies the existing Index header actions, currently including:

- bug report action
- optional debug WAV import action
- Settings icon

The existing comments explicitly indicate that the Index screen already owns its inline header and that the generic `WatchHomeScreen` top bar is hidden while the Index tab is active.

The function is therefore a strong reuse point for phone mode.

Potential future phone-only header behavior should be implemented by changing the smallest possible chrome/action layer, not by rewriting `IndexFeedScreen`.

---

## 8. Existing Index Feed UI

Primary files:

- `experimental/src/commonMain/kotlin/coredevices/ring/ui/screens/home/IndexFeedScreen.kt`
- `experimental/src/commonMain/kotlin/coredevices/ring/ui/screens/home/IndexFeedComponents.kt`
- `experimental/src/commonMain/kotlin/coredevices/ring/ui/components/feed/*`
- `experimental/src/commonMain/kotlin/coredevices/ring/ui/components/chat/*`

The current header in `IndexFeedComponents.kt` displays the title **“Index”** and includes search plus trailing actions.

Do not rename or redesign the Index UI merely to match this context document. The user's target is to preserve the existing Index experience, not reskin it.

The current Index feed also contains its own recording/chat input path and feed/list UI.

---

## 9. Existing Index settings and gesture configuration

Primary files:

- `experimental/src/commonMain/kotlin/coredevices/ring/ui/screens/settings/IndexSettings.kt`
- `experimental/src/commonMain/kotlin/coredevices/ring/ui/screens/settings/IndexActionsSection.kt`
- `experimental/src/commonMain/kotlin/coredevices/ring/ui/screens/settings/ButtonSwitchboard.kt`
- `experimental/src/commonMain/kotlin/coredevices/ring/service/button/RingGestureRouting.kt`
- `experimental/src/commonMain/kotlin/coredevices/ring/service/button/GestureRoutingPreferences.kt`

### Existing gesture vocabulary

`RingGesture` currently defines:

- `Click`
- `DoubleClick`
- `TripleClick`
- `Hold`
- `ClickHold`

The existing labels rendered by `ButtonSwitchboard` are:

- Click
- Double click
- Triple click
- Hold & Talk
- Double click & hold

### Existing destinations

`GestureDestination` includes:

- `PlayPause`
- `NextTrack`
- `IndexAgent`
- `WebSearch`
- `McpSandbox`
- `WebhookOnly`
- `Nothing`

`GestureRoutingPreferences` persists per-gesture routing in app settings and validates that a destination is compatible with the gesture kind.

**This is extremely valuable for the phone implementation.** The phone Volume Up layer should feed the same gesture vocabulary and existing routing configuration whenever possible rather than create a second hard-coded mapping system.

---

## 10. Existing physical Index button event path

Relevant files:

- `experimental/src/commonMain/kotlin/coredevices/ring/service/IndexButtonSequenceRecorder.kt`
- `experimental/src/commonMain/kotlin/coredevices/ring/service/IndexButtonActionHandler.kt`
- `experimental/src/commonMain/kotlin/coredevices/ring/service/RingSync.kt`
- `experimental/src/commonMain/kotlin/coredevices/ring/service/button/RingGestureRouting.kt`
- `experimental/src/commonMain/kotlin/coredevices/ring/service/button/GestureRoutingPreferences.kt`

### Current ring-side sequence model

`IndexButtonSequenceRecorder` receives transfer status from the Index BLE/ring synchronization path and passes it through an existing debouncer.

The existing sequence is converted to:

`ButtonPress.Short` / `ButtonPress.Long`

and resolved using `RingGesture.forSequence(...)`.

`IndexButtonActionHandler` currently consumes these sequences for music-related actions.

Recording gestures travel through the recording-processing path, where the button sequence is preserved with the recording task and eventually used by `RecordingOperationFactory` to choose the operation/destination.

### Important design implication

The phone implementation should **not fake BLE transfer status just to reuse the ring synchronization class.** Instead, create a small phone-side gesture/input adapter at the correct abstraction boundary, then route the resulting gesture into the existing Index recording/action machinery.

Do not modify BLE/ring synchronization to pretend the phone is a physical Index unless later inspection proves that to be the cleanest extension point.

---

## 11. Existing recording pipeline — DO NOT REWRITE

`CLAUDE.md` in the baseline repository contains an explicit project rule:

> Do not modify the Ring recording processing pipeline.

It also specifies that new audio input sources should adapt audio to the pipeline's expected format and call the existing local-processing entry point.

Relevant files include:

- `experimental/src/commonMain/kotlin/coredevices/ring/util/AudioRecorder.kt`
- `experimental/src/androidMain/kotlin/coredevices/ring/util/AudioRecorder.android.kt`
- `experimental/src/commonMain/kotlin/coredevices/ring/service/recordings/RecordingProcessingQueue.kt`
- `experimental/src/commonMain/kotlin/coredevices/ring/service/recordings/RecordingProcessor.kt`
- `experimental/src/commonMain/kotlin/coredevices/ring/service/recordings/button/RecordingOperationFactory.kt`
- `experimental/src/commonMain/kotlin/coredevices/ring/storage/RecordingStorage.kt`

The Android `AudioRecorder` already exists behind a multiplatform abstraction.

The existing feed also has a phone-side microphone recording path in `FeedTabContents.kt`, which is useful evidence that **phone audio capture is already conceptually supported by Index**.

The target phone-button implementation should therefore reuse the existing recorder/storage/queue behavior instead of building another transcription/upload/agent path.

The existing local queue entry point accepts an optional `buttonSequence` (`queueLocalAudioProcessing(fileId, buttonSequence)`), which is especially important: the phone gesture adapter should be able to preserve the Index gesture identity all the way into `RecordingOperationFactory` without inventing a parallel routing system.

---

## 12. Existing phone-side recording path in the UI

`experimental/src/commonMain/kotlin/coredevices/ring/ui/screens/home/FeedTabContents.kt`

The current UI already has functions corresponding to:

- start recording
- stop and process
- cancel recording

The recording is saved and handed to:

`recordingQueue.queueLocalAudioProcessing(fileId)`

This is an important clue for future implementation.

The global Volume Up feature should ideally invoke a long-lived service/domain API that performs the equivalent of the existing recording flow rather than call a composable directly.

Do not make a background service depend on Compose state.

---

## 13. Android background/service infrastructure already present

Current Android infrastructure includes:

- `composeApp/src/androidMain/kotlin/coredevices/coreapp/PebbleService.kt`
- `composeApp/src/androidMain/kotlin/coredevices/coreapp/PebbleBackgroundManager.kt`
- `composeApp/src/androidMain/kotlin/coredevices/coreapp/SyncWorker.kt`
- Index-related Android services in `experimental`, including `InferenceForegroundService.kt`.

`androidApp/src/main/AndroidManifest.xml` already declares foreground services and app-level permissions.

`experimental/src/androidMain/AndroidManifest.xml` already declares Index-related permissions including:

- `RECORD_AUDIO`
- `POST_NOTIFICATIONS`
- Bluetooth permissions
- `FOREGROUND_SERVICE`
- `FOREGROUND_SERVICE_CONNECTED_DEVICE`
- alarm permissions
- boot-related permissions

A future global-button mechanism should reuse existing background/service infrastructure where practical and must obey current Android API constraints.

**Phase 0 conclusion:** the repository already has enough Android service infrastructure that a small phone-input service may be feasible without inventing an entirely new background architecture. Actual global Volume Up interception must be treated as a separate feasibility spike.

---

## 14. Authentication — critical constraint discovered in Phase 0

The requested UX is “no login page / do not make me log in.”

The source code shows that this must be interpreted carefully.

### Current UI

`composeApp/src/commonMain/kotlin/coredevices/coreapp/ui/screens/OnboardingScreen.kt` contains the generic onboarding flow, including device selection and a sign-in stage.

`composeApp/src/commonMain/kotlin/coredevices/coreapp/ui/screens/ringonboarding/SignInStep.kt` explicitly tells the user that Index 01 needs an account for backup and agent processing.

### Current backend behavior

`experimental/src/commonMain/kotlin/coredevices/ring/service/indexfeed/IndexFeedSyncService.kt` observes Firebase auth state and gates Firestore synchronization on a non-null Firebase user.

`experimental/src/commonMain/kotlin/coredevices/ring/storage/RecordingStorage.kt` uses the authenticated Firebase user's UID for cloud storage paths.

Other Index/agent integrations likewise rely on authenticated backend access.

### Required interpretation

The first implementation goal should be:

**Remove/bypass the login/onboarding UI from the normal phone-mode launch path.**

Do **not** automatically remove authentication from the backend.

We need to distinguish between:

1. “The user never sees a login screen.”
2. “The app has a persisted/authenticated backend identity.”
3. “The app can operate completely anonymously with no Firebase account.”

Only #1 is currently clearly part of the UX requirement. #3 would be a deeper backend/product change and may not be possible without server-side support.

Do not implement anonymous auth or fake credentials unless explicitly investigated and justified in a later phase.

---

## 15. Current branding entry points found

Relevant generic branding resource:

- `composeApp/src/commonMain/composeResources/drawable/pebble-logo.png`

Generic onboarding uses the Pebble logo in:

`composeApp/src/commonMain/kotlin/coredevices/coreapp/ui/screens/OnboardingScreen.kt`

Android app-level launcher label is defined through resources referenced from the Android manifest.

The desired phone mode should hide/avoid the generic branded onboarding/splash experience rather than delete every Pebble asset from the repository.

Do not perform a broad asset purge.

---

## 16. Phase 0 recommended architecture

The baseline strongly suggests separating the future changes into two conceptual layers.

### Layer A — Index Phone UI mode

Responsibilities:

- select Index as the initial user-facing destination
- suppress generic onboarding/device-selection screens
- suppress generic bottom navigation
- suppress unrelated Pebble sections from normal navigation
- suppress unwanted branding
- retain existing Index feed/detail/settings implementation

Likely touch points:

- `composeApp/ui/App.kt`
- `composeApp/ui/navigation/AppNavHost.kt`
- possibly `pebble/ui/WatchHomeScreen.kt` only if there is no cleaner route
- `ExperimentalDevices.IndexScreen(...)` for Index-only header actions if necessary

Prefer an explicit phone-mode gate/route over deleting generic navigation code.

### Layer B — Android phone input adapter

Responsibilities:

- listen for physical Volume Up events by the most appropriate Android mechanism
- recognize click/double/triple/hold/double-click-hold
- expose a clean `IndexPhoneGesture`/equivalent abstraction
- map that abstraction to existing `RingGesture` semantics where appropriate
- trigger existing Index recording/action behavior
- operate independently from Compose UI
- survive background/locked/screen-off conditions where Android allows it

This layer should be Android-specific unless a shared abstraction is genuinely useful.

---

## 17. Proposed future package boundary

Do not blindly create these exact files. Use this as a conceptual boundary.

Suggested concept:

`coredevices.coreapp.indexphone` or an equivalent package appropriate to the repository.

Possible responsibilities/files:

- `IndexPhoneMode` — feature gate/configuration
- `IndexPhoneGesture` — phone-side gesture representation
- `IndexPhoneGestureDetector` — timing/state machine
- `IndexPhoneInputService` — Android background/hardware-input bridge
- `IndexPhoneActionBridge` — adapter into existing Index action/recording services

Use the minimum number of files necessary.

Avoid creating a new module unless the existing module boundaries make that clearly beneficial.

---

## 18. Phase plan after Phase 0

### Phase 1 — Fork scaffolding only

Goal:

- establish the fork-specific feature flag/context
- establish isolated package/files if needed
- add/update this context pack
- make no behavior change yet, or only a no-op gate

Acceptance:

- build still behaves like upstream
- no Index pipeline changes

### Phase 2 — Direct Index launch

Goal:

`App launch → existing Index feed`

No login/onboarding page should appear in phone mode.

Important:

- Do not delete onboarding implementation.
- Do not change backend authentication semantics.
- Ensure Index is enabled in the phone-only configuration without requiring a physical ring merely to choose the tab.

Acceptance:

- fresh install launches into Index UI
- app remains usable after process restart
- no onboarding/device-selection screen
- existing deep links still compile and generic routes remain in source

### Phase 3 — Hide generic app chrome

Hide from the normal phone-mode UX:

- bottom Pebble navigation
- Watches
- Watch Faces
- Notifications
- Health
- generic Settings entry
- device-selection screens
- unrelated onboarding affordances
- unwanted Pebble branding

Preserve the existing Index settings/details functionality that is useful to Index.

Acceptance:

- user sees Index as the primary/only product surface
- Index detail pages still work
- Index settings remain reachable by the existing Index header/settings affordance if desired

### Phase 4 — Gesture state machine in isolation

Implement and unit test the timing/state-machine logic without global Android capture.

Inputs:

- down
- up
- elapsed time

Outputs:

- Click
- DoubleClick
- TripleClick
- Hold
- ClickHold

Do not connect to Android hardware yet.

Acceptance:

- deterministic unit tests cover the gesture matrix and timing boundaries
- no Compose dependency
- no audio dependency

### Phase 5 — Android Volume Up capture feasibility + implementation

Investigate what Android permits for the target SDK/API levels and actual device before committing to a mechanism.

Requirements to prove individually:

- Index app foreground
- another app foreground
- home screen
- screen locked
- screen off
- phone in normal audio conditions

Do not assume a normal Activity key listener is sufficient.

If the required global interception cannot be reliably achieved on the target Android/device, document the exact limitation rather than silently implementing something that only works in the foreground.

### Phase 6 — Long-press recording bridge

First implement the most important gesture:

`Volume Up hold → record → release → stop → existing Index local processing`

Do not alter the existing transcription/agent/queue architecture.

Acceptance:

- works when app is foreground
- works in background
- works while locked/screen off if the Phase 5 mechanism supports it
- resulting recording appears in the existing feed
- existing processing/transcription/agent behavior is unchanged

### Phase 7 — Full gesture routing

Connect:

- 1× → existing Click semantics
- 2× → existing DoubleClick semantics
- 3× → existing TripleClick semantics
- hold → existing Hold semantics
- 2× + hold → existing ClickHold semantics

Prefer using `GestureRoutingPreferences` and `RingGesture` rather than duplicating destination settings.

Acceptance:

- changes made in existing Index gesture settings affect the phone button
- no second conflicting settings system unless there is no clean alternative

### Phase 8 — Upstream merge rehearsal

Take a clean upstream update and rehearse merge/rebase.

Measure:

- number of conflicts
- fork-specific files touched by upstream
- whether any customization patch can be moved to fewer files
- test/build regressions

Then update this context pack with the observed merge hotspots.

---

## 19. Acceptance-test matrix

| Scenario | Required outcome |
|---|---|
| Fresh install | Starts directly in Index phone mode |
| App relaunch | Returns to Index phone mode |
| No ring paired | Index UI still available in phone mode |
| No onboarding completion flag | Onboarding still bypassed in phone mode |
| Login UI | Never shown during normal phone-mode launch |
| Index feed | Existing feed renders unchanged |
| Index detail | Existing detail navigation works |
| Index settings | Existing useful Index settings remain accessible |
| Bottom nav | Not visible in phone mode |
| Pebble device pages | Not visible in normal phone mode |
| Volume 1× | Existing Click semantics |
| Volume 2× | Existing DoubleClick semantics |
| Volume 3× | Existing TripleClick semantics |
| Volume hold | Existing Hold semantics / recording path |
| Volume 2×+hold | Existing ClickHold semantics |
| Index app foreground | Gesture works |
| Other app foreground | Gesture works, subject to Android capability |
| Home screen | Gesture works, subject to Android capability |
| Lock screen | Gesture works, subject to Android capability |
| Screen off | Gesture works, subject to Android capability |
| Recording result | Uses existing Index processing pipeline |
| Upstream update | Fork-specific changes remain localized |

---

## 20. Files identified as likely future touch points

These are **candidates, not a permission to modify them all**.

### High priority

- `composeApp/src/commonMain/kotlin/coredevices/coreapp/ui/App.kt`
- `composeApp/src/commonMain/kotlin/coredevices/coreapp/ui/navigation/AppNavHost.kt`
- `composeApp/src/androidMain/kotlin/coredevices/coreapp/MainActivity.kt`
- `composeApp/src/androidMain/kotlin/coredevices/coreapp/MainApplication.kt`
- `androidApp/src/main/AndroidManifest.xml`
- `experimental/src/commonMain/kotlin/coredevices/ExperimentalDevices.kt`
- `experimental/src/commonMain/kotlin/coredevices/ring/ui/screens/home/IndexFeedScreen.kt`
- `experimental/src/commonMain/kotlin/coredevices/ring/ui/screens/home/IndexFeedComponents.kt`
- `experimental/src/commonMain/kotlin/coredevices/ring/service/button/RingGestureRouting.kt`
- `experimental/src/commonMain/kotlin/coredevices/ring/service/button/GestureRoutingPreferences.kt`
- `experimental/src/commonMain/kotlin/coredevices/ring/service/recordings/RecordingProcessingQueue.kt`
- `experimental/src/commonMain/kotlin/coredevices/ring/util/AudioRecorder.android.kt`

### Potentially avoid modifying

- `experimental/src/commonMain/kotlin/coredevices/ring/service/RingSync.kt`
- `experimental/src/commonMain/kotlin/coredevices/ring/service/IndexButtonSequenceRecorder.kt`
- `libindex/*`
- BLE/Haversine code
- Index AI internals
- recording preprocessing/transcription/agent implementations

These should remain untouched unless a later phase proves otherwise.

---

## 21. Explicit “do not do this” list for AI coding agents

Do not:

- rewrite the Index feed
- replace the Index recording processor
- replace the Index transcription/agent pipeline
- fake a physical ring by changing BLE transfer code unless specifically proven necessary
- delete generic onboarding/watch/device code
- delete Pebble resources globally
- refactor navigation unrelated to phone mode
- introduce a new framework for state management
- add broad dependencies without necessity
- create a new Gradle module without necessity
- change the project's Kotlin/Compose/Android versions
- change upstream behavior for iOS unless explicitly required
- modify the existing Index data schema just to support the phone button
- hard-code gesture destinations when the existing gesture-routing configuration can be reused
- make a composable the source of truth for global/background input
- claim screen-off/lock-screen support is complete without testing it on the actual target Android device

---

## 22. Current Phase 0 conclusions

1. **Index is already fairly well isolated.** The `experimental` module contains the Index/Ring feature implementation, while `composeApp` owns the top-level application shell and navigation.
2. **`ExperimentalDevices.IndexScreen()` is a strong reuse point.** It already invokes the Index feed directly.
3. **The generic `WatchHomeScreen` is the main source of unwanted phone UX chrome.** Its tab selection and bottom navigation are separate from the actual Index feed.
4. **The existing Index gesture model is reusable.** `RingGesture` and `GestureRoutingPreferences` already represent the exact five gesture patterns we want.
5. **The existing recording path already supports local phone audio.** The feed has a local recording path that ultimately calls `queueLocalAudioProcessing(fileId)`.
6. **The existing project explicitly says not to modify the Ring recording-processing pipeline.** This should remain a hard rule.
7. **Global Volume Up interception is the main technical unknown.** It needs its own Android feasibility/implementation phase rather than being mixed into navigation changes.
8. **Authentication is a UX/backend boundary.** The login screen can be bypassed visually, but the current Index cloud-sync/storage/agent stack expects an authenticated Firebase user. Do not remove or fake that dependency without separate investigation.
9. **The fork should be organized so the majority of modifications live in a small phone-mode layer plus a few routing/bootstrap touch points.**

---

## 23. Phase 0 “do not modify” status

Phase 0 made **no source-code modifications**.

The only artifact produced by this phase is this context pack.

---

## 24. Instruction for the next AI agent

Before making any change:

1. Read this entire file.
2. Inspect the exact current source files named in the relevant phase.
3. Identify the smallest viable change set.
4. State which files you intend to modify and why.
5. Do not edit files outside that set unless the build/test exposes a concrete dependency that requires it.
6. Keep the change isolated to the current phase.
7. Add or update tests for non-trivial logic.
8. Run the narrowest relevant build/test commands.
9. Update this context pack before finishing the phase.

**Current task:** Phase 0 is complete. Do not implement Phase 1+ unless explicitly requested.
