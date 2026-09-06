# Index Phone Fork — Persistent Context Pack

> This file is the authoritative context for the **Index Phone** customization of the Core Devices `mobileapp` repository.
> It is intended to be read by every coding agent before making changes to this fork.
>
> **Baseline:** Core Devices `mobileapp`, user fork based on upstream tag `1.11.0.2`.
> **Current validated state:** Android phone-mode work through Phase 2B, including the local-first bootstrap safety/retry correction and launch-time Koin repair.
> **Current branch used during development:** `index-phone-phase-1`.
> **Primary goal:** preserve the existing Index experience while replacing the physical Index 01 button/ring dependency with an Android-phone experience centered on the physical Volume Up button.

---

## 1. Mission and product requirements

Create a phone-only Android experience around the existing **Index 01** functionality in the Core Devices mobile app.

The requirements are:

1. Launch directly into the existing Index feed experience on Android.
2. Do not expose Pebble watch/device onboarding, device selection, login screens, generic bottom navigation, or unrelated Pebble/watch sections in the normal phone-mode user experience.
3. Preserve the existing Index feed, list/note model, recording/processing pipeline, transcription, agent behavior, Index actions, settings, integrations, and data model wherever possible.
4. Remove or bypass visible Pebble branding that belongs only to the generic shell/onboarding flow. Do not purge Pebble assets or delete generic upstream features globally.
5. Basic Index notes/lists must be **local-first**. A fresh install must provide the normal system lists locally without requiring Firebase authentication or a Firestore round trip.
6. Firebase/cloud functionality remains available for synchronization, backup, and cloud-dependent features. Do not fake authentication, introduce anonymous auth, or remove backend auth semantics without a separately approved investigation.
7. Phone-mode AI defaults must eventually be:
   - **Agent Model = Local LLM**
   - **Speech Engine = Local only**
   These defaults should be selected automatically for a new phone-mode user. Selecting **Local only** must use the existing mechanism to obtain/download the current/latest local speech-recognition/transcription model. Cloud options remain available.
8. Add an Android-only phone input layer that makes the physical **Volume Up** button emulate the existing Index 01 button gesture vocabulary.
9. The desired gesture vocabulary is the existing one:
   - Click
   - DoubleClick
   - TripleClick
   - Hold
   - ClickHold / double-click-and-hold
10. The Volume Up system must ultimately work, where Android and the target OEM/device permit it, when:
    - the Index app is foregrounded;
    - another app is foregrounded;
    - the home screen is foregrounded;
    - the phone is locked;
    - the screen is off.
11. The highest-priority hardware gesture is:

    `Volume Up hold -> record -> release -> stop -> existing Index local processing`

12. Do not duplicate or rewrite the existing Index recording/transcription/agent pipeline. Phone-captured audio must enter the existing pipeline at its supported boundary.
13. Existing configurable Index gesture routing should drive phone-button behavior whenever possible. Do not create a second hard-coded gesture destination system.
14. Keep fork-specific changes isolated and minimal so future upstream merges/rebases remain manageable.
15. Android is the target for fork-specific behavior. Do not change iOS behavior for Android-only requirements unless a concrete shared-code dependency makes it unavoidable and the change is explicitly approved.

---

## 2. Non-negotiable engineering principles

### 2.1 Thin customization layer

Treat this fork as:

`Core Devices upstream + a small, isolated Index Phone layer`

Do not turn the fork into a rewritten application.

### 2.2 Preserve upstream implementation

Prefer, in order:

1. Existing extension points and abstractions.
2. A small adapter/wrapper around existing code.
3. A small Android-specific class/package when an Android-only seam is needed.
4. A very small targeted edit to upstream/shared code only when no cleaner seam exists.

Avoid changing existing Index recording, transcription, agent, database, BLE, and synchronization internals unless a later investigation proves the change is required.

### 2.3 Surgical changes only

Before editing a file, determine whether the requirement can be satisfied by changing a routing/bootstrap decision or adding a small adapter at an existing boundary.

Do not perform opportunistic refactors, formatting sweeps, dependency upgrades, architecture migrations, or unrelated cleanup.

### 2.4 Evidence over agent summaries

An implementation is not considered correct because an agent says it is correct.

Evidence hierarchy:

1. Actual source code / Git diff.
2. Build and test output.
3. Physical-device validation when behavior is device-dependent.
4. Agent summary only as a convenience.

### 2.5 Small commits and checkpoints

Each future feature should be a separate commit or a very small series of single-purpose commits.

Because Phase 1, Phase 2A, and Phase 2B were developed before this workflow was stabilized, it is acceptable to create one clean validated checkpoint covering those already-tested changes rather than retroactively manufacturing perfect historical commits.

### 2.6 Context maintenance

`INDEX_PHONE_CONTEXT.md` records product requirements, durable architecture findings, validated fork deltas, known limitations, and the active roadmap.

Do not edit it during investigation or implementation. Update it in a separate documentation step **only after the implementation has been reviewed and validated**.

A coding executor must not rewrite or shorten historical architecture merely because it appears old. If a historical finding is superseded, mark it as superseded and explain why.

---

## 3. Authoritative workflow for every future phase

Every future phase must follow this sequence:

1. **Read context** — read this entire file and any root `AGENTS.md`.
2. **Inspect current repository state** — inspect relevant source, Git status, and existing tests before proposing changes.
3. **Define one narrow objective** — do not combine unrelated issues.
4. **Investigate only** — gather evidence and identify the smallest safe implementation path. No source edits.
5. **Report findings** — list relevant files, current behavior, proposed change, risks, tests, and any assumptions that still require proof.
6. **Approval** — wait for explicit approval to implement the proposed change.
7. **Implement only the approved scope** — no opportunistic refactors or adjacent fixes.
8. **Verify locally** — run the narrowest relevant tests/build, then `git diff --check`, `git status --short`, and inspect the actual diff.
9. **Physical-device validation** — required when Android runtime, permissions, background behavior, hardware buttons, audio, or UI insets are involved.
10. **Documentation** — only after validation, update this context file in a separate task.
11. **Review context diff** — make sure documentation describes evidence, not speculation.
12. **Commit/push** — commit the validated phase.
13. **Proceed to the next narrow objective.**

The local coding agent/CLI is an **investigation and implementation tool**, not the architectural decision-maker.

---

## 4. Repository baseline snapshot from Phase 0

The baseline project is Kotlin Multiplatform + Compose Multiplatform.

### Relevant modules

| Module | Role | Index Phone relevance |
|---|---|---|
| `composeApp` | Shared app shell, UI, navigation, Android/iOS platform entry support, Koin wiring | High — launch/navigation/Android entry points |
| `androidApp` | Android wrapper, manifest, application ID, packaging | High |
| `experimental` | Index 01 / Ring feature implementation | Very high |
| `libindex` | Index hardware/device plumbing | High but should mostly remain untouched for phone-only mode |
| `index-ai` | Index AI assistant/data-layer support | High; reuse rather than rewrite |
| `pebble` | Generic Pebble watch UI/navigation/device features | Mostly hidden/bypassed in phone mode |
| `util` | Shared utilities and permissions | Medium |
| `cactus`, `resampler`, `krisp-stubs` | Audio/ML support | Reuse through existing pipeline |

Root `settings.gradle.kts` includes these modules directly.

### Android baseline observed during setup

- compile SDK: 37
- min SDK: 26
- target SDK: 36
- AGP: 9.3.1
- Kotlin: 2.4.10
- Java/JVM toolchain: 17
- Compose Multiplatform: 1.11.1
- AndroidX Activity Compose: 1.13.0
- AndroidX Navigation: 2.9.2
- AndroidX Lifecycle: 2.11.0
- WorkManager: 2.11.2
- Koin: 4.2.2
- Android application ID: `coredevices.coreapp`

These values are a baseline snapshot, not a request to pin them forever. Re-check them after upstream updates.

---

## 5. Application startup and navigation architecture

### Android entry

`composeApp/src/androidMain/kotlin/coredevices/coreapp/MainActivity.kt`

Baseline findings:

- `ComponentActivity` + `setContent { App() }`
- initializes existing Pebble/Index-related delegates
- handles deep links
- participates in background/service lifecycle behavior

Potential future phone-input/bootstrap work may touch Android application/activity/service infrastructure, but do not assume `MainActivity` is the correct location until the relevant feasibility investigation is complete.

### Application

`composeApp/src/androidMain/kotlin/coredevices/coreapp/MainApplication.kt`

Baseline findings:

- starts Koin modules including `experimentalModule`
- calls `experimentalDevices.appInit()`
- initializes notifications and other app-wide/background infrastructure

### Shared app shell

`composeApp/src/commonMain/kotlin/coredevices/coreapp/ui/App.kt`

Baseline upstream behavior selected onboarding or the generic watch home. Phase 1 now overrides the Android root start destination for phone mode; see the validated delta ledger below.

### Main navigation

`composeApp/src/commonMain/kotlin/coredevices/coreapp/ui/navigation/AppNavHost.kt`

Important baseline finding:

`ExperimentalDevices.IndexScreen(...)` is callable separately from most generic Pebble/watch UI. This became the primary seam for direct Android Index launch.

---

## 6. Generic watch-home shell and Index feed entry

`pebble/src/commonMain/kotlin/coredevices/pebble/ui/WatchHomeScreen.kt`

The generic shell owns watch-oriented tabs and bottom navigation. The Index screen already owns its own Index header.

This led to the Phase 1 strategy: **bypass the generic shell on Android phone mode rather than delete or heavily rewrite it**.

Primary Index entry point:

`experimental/src/commonMain/kotlin/coredevices/ExperimentalDevices.kt`

`ExperimentalDevices.IndexScreen(...)` directly invokes the existing `IndexFeedScreen(...)` and provides Index-specific header actions.

This remains an important reuse point.

---

## 7. Existing Index feed and settings

Primary feed/UI files include:

- `experimental/src/commonMain/kotlin/coredevices/ring/ui/screens/home/IndexFeedScreen.kt`
- `experimental/src/commonMain/kotlin/coredevices/ring/ui/screens/home/IndexFeedComponents.kt`
- `experimental/src/commonMain/kotlin/coredevices/ring/ui/components/feed/*`
- `experimental/src/commonMain/kotlin/coredevices/ring/ui/components/chat/*`

Do not redesign the Index UI merely to make the fork look different. Preserve the existing Index experience unless a specific phone-only UI defect requires a targeted change.

Primary settings/gesture files include:

- `experimental/src/commonMain/kotlin/coredevices/ring/ui/screens/settings/IndexSettings.kt`
- `experimental/src/commonMain/kotlin/coredevices/ring/ui/screens/settings/IndexActionsSection.kt`
- `experimental/src/commonMain/kotlin/coredevices/ring/ui/screens/settings/ButtonSwitchboard.kt`
- `experimental/src/commonMain/kotlin/coredevices/ring/service/button/RingGestureRouting.kt`
- `experimental/src/commonMain/kotlin/coredevices/ring/service/button/GestureRoutingPreferences.kt`

### Existing gesture vocabulary

`RingGesture` defines:

- `Click`
- `DoubleClick`
- `TripleClick`
- `Hold`
- `ClickHold`

The UI labels correspond to:

- Click
- Double click
- Triple click
- Hold & Talk
- Double click & hold

### Existing destinations

`GestureDestination` includes existing routes such as:

- PlayPause
- NextTrack
- IndexAgent
- WebSearch
- McpSandbox
- WebhookOnly
- Nothing

`GestureRoutingPreferences` persists per-gesture routing and validates destination compatibility.

**Future phone Volume Up behavior should reuse this vocabulary and routing configuration instead of creating a parallel hard-coded mapping system.**

---

## 8. Existing physical Index button event path

Relevant baseline files include:

- `experimental/src/commonMain/kotlin/coredevices/ring/service/IndexButtonSequenceRecorder.kt`
- `experimental/src/commonMain/kotlin/coredevices/ring/service/IndexButtonActionHandler.kt`
- `experimental/src/commonMain/kotlin/coredevices/ring/service/RingSync.kt`
- `experimental/src/commonMain/kotlin/coredevices/ring/service/button/RingGestureRouting.kt`
- `experimental/src/commonMain/kotlin/coredevices/ring/service/button/GestureRoutingPreferences.kt`

The ring-side path resolves button activity to `ButtonPress.Short` / `ButtonPress.Long` and then to `RingGesture` semantics. Recording-related sequences eventually influence `RecordingOperationFactory`.

### Important design rule

Do **not** fake BLE/ring transfer status merely to make the phone pretend to be a physical Index device.

Prefer a small Android phone-side input adapter that reaches the appropriate existing gesture/action/recording abstraction boundary.

---

## 9. Existing recording pipeline — do not rewrite

The repository's own guidance explicitly says not to modify the Ring recording processing pipeline.

Relevant files include:

- `experimental/src/commonMain/kotlin/coredevices/ring/util/AudioRecorder.kt`
- `experimental/src/androidMain/kotlin/coredevices/ring/util/AudioRecorder.android.kt`
- `experimental/src/commonMain/kotlin/coredevices/ring/service/recordings/RecordingProcessingQueue.kt`
- `experimental/src/commonMain/kotlin/coredevices/ring/service/recordings/RecordingProcessor.kt`
- `experimental/src/commonMain/kotlin/coredevices/ring/service/recordings/button/RecordingOperationFactory.kt`
- `experimental/src/commonMain/kotlin/coredevices/ring/storage/RecordingStorage.kt`

The Android `AudioRecorder` already exists behind a multiplatform abstraction.

The local queue entry point accepts an optional button sequence:

`queueLocalAudioProcessing(fileId, buttonSequence)`

This is an important seam for preserving gesture identity while reusing existing processing.

---

## 10. Existing phone-side recording path in the UI

`experimental/src/commonMain/kotlin/coredevices/ring/ui/screens/home/FeedTabContents.kt`

The existing Index UI already has phone-side recording behavior corresponding to:

- start recording
- stop and process
- cancel recording

The recording is saved and handed to:

`recordingQueue.queueLocalAudioProcessing(fileId)`

This proves that phone audio capture is already compatible with the Index concept.

For global Volume Up recording, prefer a long-lived service/domain API that performs the equivalent recording/storage/queue handoff rather than calling a composable.

**A background service must not depend on Compose UI state as its source of truth.**

---

## 11. Android background/service infrastructure

Existing Android infrastructure includes examples such as:

- `composeApp/src/androidMain/kotlin/coredevices/coreapp/PebbleService.kt`
- `composeApp/src/androidMain/kotlin/coredevices/coreapp/PebbleBackgroundManager.kt`
- `composeApp/src/androidMain/kotlin/coredevices/coreapp/SyncWorker.kt`
- Index-related Android services in `experimental`, including `InferenceForegroundService.kt`

Existing manifests already declare a range of permissions and service capabilities, including audio, notifications, foreground service, alarm, boot, and hardware-related permissions.

Do not assume those existing declarations automatically solve global Volume Up capture. Hardware-key interception across other apps/lock/screen-off must be investigated separately against Android platform constraints and the actual target device/OEM.

---

## 12. Authentication and local-first data boundary

The required UX is no login/onboarding screen in normal phone mode.

That does **not** mean all backend authentication requirements disappear.

Keep these concepts separate:

1. The user does not see login during normal phone-mode launch.
2. Some cloud functionality may require a persisted/authenticated Firebase identity.
3. Fully anonymous cloud operation would be a deeper backend/product change.

Do not implement anonymous auth or fake credentials without a separate approved investigation.

### Local-first lists

Phase 2A established that the normal system lists can be initialized in the existing local Room-backed data model without waiting for Firebase/Firestore.

Do not create a second notes/list implementation.

Cloud sync should remain optional for local basic use and should reconcile through the existing sync model when authentication/network becomes available.

---

## 13. Branding boundary

Generic Pebble branding exists in resources/onboarding, including the Pebble logo used by the generic onboarding flow.

Phone mode should avoid the generic branded shell/onboarding where possible instead of deleting assets or upstream screens globally.

Do not perform a broad branding/resource purge.

---

## 14. Recommended architecture for the remaining phone work

This section preserves the useful Phase 0 architectural model but does **not** assign obsolete phase numbers.

### Layer A — Index Phone UI mode

Responsibilities:

- choose Index as the initial Android user-facing destination
- bypass generic onboarding/device selection in normal phone mode
- suppress generic bottom navigation and unrelated Pebble/watch surfaces
- suppress unwanted generic branding from the normal phone-mode flow
- retain existing Index feed/detail/settings implementation
- initialize phone-mode-specific defaults only where justified

Prefer explicit phone-mode routing/bootstrap decisions over deleting generic upstream functionality.

### Layer B — Android phone input adapter

Responsibilities:

- receive physical Volume Up events using the most appropriate Android mechanism
- recognize click/double/triple/hold/double-click-hold
- map phone events to existing `RingGesture` semantics where appropriate
- trigger existing Index action/recording behavior
- operate independently from Compose UI
- remain functional in background/locked/screen-off states where Android permits

Keep this layer Android-specific unless a shared abstraction is genuinely useful.

### Conceptual package boundary

A small isolated package such as `coredevices.coreapp.indexphone` (or a repository-appropriate equivalent) may be useful.

Conceptual responsibilities could include:

- phone-mode gate/configuration
- phone gesture representation
- gesture timing/state machine
- Android hardware-input/background bridge
- adapter into existing Index action/recording services

These are **roles, not required class names**. Do not blindly create `IndexPhoneMode`, `IndexPhoneGesture`, `IndexPhoneGestureDetector`, `IndexPhoneInputService`, or `IndexPhoneActionBridge` just because they are mentioned here.

Use the minimum number of files necessary. Do not create a new Gradle module unless the existing module boundaries clearly require it.

---

## 15. Validated fork delta ledger

### Phase 1 — Direct Android Index launch

**Status: COMPLETED AND PHYSICALLY VALIDATED**

Files modified:

- `composeApp/src/commonMain/kotlin/coredevices/coreapp/ui/App.kt`
- `composeApp/src/commonMain/kotlin/coredevices/coreapp/ui/navigation/AppNavHost.kt`

Validated behavior:

- Android fresh install launches directly into the existing Index Feed.
- Generic onboarding/welcome/device-selection screens do not appear.
- The generic Pebble watch-home shell/bottom navigation is bypassed for the Android start destination.
- No recording/transcription/agent implementation was rewritten.

Implementation intent:

- override Android root `startDestination` to the existing Index route
- register the Index route at the root nav-host level so the existing Index screen can be shown without the generic watch-home shell

### Phase 2A — Local-first default Index lists

**Status: COMPLETED AND PHYSICALLY VALIDATED**

Files modified:

- `experimental/src/commonMain/kotlin/coredevices/ExperimentalDevices.kt`
- `experimental/src/commonMain/kotlin/coredevices/ring/service/indexfeed/DefaultListsBootstrap.kt`

Validated behavior on fresh install without a Firebase session/network dependency:

- Note to Self appears locally.
- Shopping List appears locally.
- Reminders appears locally.
- Notes `+` appears naturally through the existing reactive UI.
- User-created lists can be created locally.
- Basic local notes/lists work without Firebase authentication.
- Firebase remains optional for cloud synchronization/backup.
- No parallel notes/list system was introduced.

Implementation intent:

- initialize missing system lists locally through the existing repositories/data model
- use stable IDs
- use low/epoch local timestamps so later cloud data can win during normal reconciliation rather than overwriting newer cloud content
- retain existing cloud sync rather than replacing it

### Android bootstrap safety and launch repair

**Status: IMPLEMENTED AND PHYSICALLY VALIDATED WHERE DEVICE TESTING WAS POSSIBLE**

The Android default-list bootstrap now:

- inserts the three system rows locally through Room insert-if-missing/`IGNORE` semantics without waiting for authentication, Firestore, or cloud initialization;
- gates cloud bootstrap on both an authenticated account and `backupEnabled`;
- uses account-scoped transactional create-if-absent operations and conditional Todos migration;
- excludes pristine Android placeholders from unconditional remote batch writes and reuses conditional creation;
- retries deferred cloud bootstrap when the existing `PhoneNetworkMonitor` changes from unavailable to available, deduplicates identical connectivity states, and cancels obsolete work when backup is disabled or the account changes;
- preserves the pre-Phase-2A iOS bootstrap behavior.

The first repaired build crashed during application startup because `PhoneNetworkMonitor` was not registered in the application Koin graph. The existing monitor was registered in the isolated libpebble3 graph only. Registering it in the Android and iOS platform ring modules fixed construction of `ExperimentalDevices`. The rebuilt APK was installed with app data preserved; an explicit launch returned successfully, `MainActivity` remained resumed, and startup logcat contained no fatal or Koin error.

Focused policy tests and a real Room device test cover repeated/concurrent insertion, preservation of customized/deleted/locked rows, deferred and cancelled cloud work, connectivity retry, conditional migration, placeholder upload boundaries, and iOS isolation. The policy tests do not claim Firestore transaction semantics; those remain emulator/project dependent.

### Phase 2B — Android phone-mode Index activation and required permissions

**Status: COMPLETED AND PHYSICALLY VALIDATED**

Files modified:

- `experimental/src/commonMain/kotlin/coredevices/ExperimentalDevices.kt`
- `experimental/src/commonMain/kotlin/coredevices/ring/ui/screens/home/IndexFeedScreen.kt`

Implementation intent:

- ensure `enableIndex = true` on Android startup because device-selection onboarding is bypassed
- reuse the existing `PermissionRequester` infrastructure
- observe `missingPermissions`
- proactively request only the phone-mode subset:
  - `Permission.PostNotifications`
  - `Permission.SetAlarms`
- do not proactively request Bluetooth or Location for this phone-only flow

Physical-device validation:

- notification permission prompt appears automatically on fresh install
- microphone permission appears on-demand when recording
- Shopping List voice requests route correctly
- reminder voice requests route correctly
- Obsidian note sending remains functional
- no clearly identifiable automatic "Alarms & reminders" special-access setup prompt was observed during the fresh-install test, although reminder requests worked after Phase 2B
- direct Index Feed launch succeeded;
- all three default lists were available offline;
- a custom list survived an app restart;
- no duplicate lists or subsequent startup crash were observed;
- the local model downloaded successfully, and manually selected Local LLM/Local-only settings processed recordings routed to Reminders, Notes to self, and Shopping;
- the app remained usable once connectivity was available for the model download.

Preservation:

- no changes to recording pipeline
- no changes to transcription pipeline
- no changes to reminder internals
- no changes to Firebase sync/auth semantics
- no changes to Obsidian integration

---

## 16. Documented but not yet implemented phone-mode AI defaults

The desired defaults for a new Android phone-mode user are:

- **Agent Model: Local LLM**
- **Speech Engine: Local only**

These must eventually be applied automatically without requiring the user to open Settings.

Selecting Local only must invoke/reuse the existing mechanism for obtaining the current/latest local speech recognition/transcription model.

Cloud choices remain available.

This section is a product requirement, not evidence that the defaults have already been implemented.

---

## 17. Known current issues

These are observed defects/requirements, not diagnoses unless explicitly stated.

### 17.1 Named user-created note/list voice routing

**Status: IMPLEMENTED AND PHYSICALLY VALIDATED, WITH ONE LOCAL-MODEL ARTIFACT**

The existing `create_list_item` tool now preserves custom destination names and resolves the
destination against the original transcript when the local model supplies a conflicting hint.
Explicit local list titles win, reminder wording resolves to Reminders, shopping/grocery wording
resolves to Shopping, and ambiguous requests retain the model-provided hint. The recording,
transcription, and agent message-construction pipeline remains unchanged.

Physical validation on the Pixel Fold routed these recordings correctly:

- `Add something to my Today note. Today is not raining.` -> **Today note**
- `Add bread to my shopping list.` -> **Shopping list**
- `Remind me to call the bank.` -> **Reminders**
- `The weather is nice today.` -> **Notes to self**

The Today-note recording also produced a second item, `tomato`, despite that word not appearing in
the transcript. Two successful action chips were shown for the recording, indicating an additional
local-model tool call rather than a destination-resolution or transcription failure. Treat this as
an unresolved local-model multiple-tool-call artifact. Do not modify the protected AI pipeline as
part of destination routing.

### 17.2 Alarm/device-association dependency

Observed alarm failure:

`Failed to set alarm: Background alarms require a device association, check the Devices tab to fix.`

Observed during testing:

- no automatic device-association setup/prompt appeared;
- the failure remains unchanged and is not validated as fixed. The current Android `set_alarm` path checks for a CompanionDeviceManager association before launching `AlarmClock.ACTION_SET_ALARM`; a phone-only replacement remains unimplemented.

Do not assume the correct fix is to pair/fake a Pebble or Ring. Investigate the actual Android reminder/alarm integration and association check first.

### 17.3 Android navigation-bar insets

The Index feed root previously handled status-bar and keyboard insets but omitted the bottom
navigation-bar inset, so the compose bar overlapped Android's three-button controls. Applying the
standard `navigationBarsPadding()` modifier to that root keeps the bottom input above the native
controls without a fixed device-specific distance. The rebuilt APK was physically verified on the
folded Pixel Fold in three-button navigation mode on 2026-09-06; the overlap is resolved.

---

## 18. Current roadmap — optimized for outcome and technical risk

The requirement set has not changed. The ordering below is intended to reduce rework and expose the largest platform risk early.

### Milestone A — Clean validated checkpoint

Before new feature work:

- establish this canonical context document
- establish root `AGENTS.md`
- review the current repository/diff
- ensure `google-services.json` and generated `.cxx/` content are not committed
- create a clean Git checkpoint for the validated Phase 1 + 2A + 2B phone-mode work, Android bootstrap safety/retry, Koin registration repair, focused tests, root `AGENTS.md`, and this context update;
- leave local `androidApp/src/google-services.json` and generated `cactus-native/.cxx/` content untracked.

### Milestone B — Read-only Volume Up feasibility spike

**Investigation only first. No implementation.**

Determine what the current Android target/device can support for Volume Up capture in each state:

- Index app foreground
- another app foreground
- home screen
- lock screen
- screen off
- normal audio/volume conditions

Do not assume an Activity key listener is sufficient.

Investigate relevant Android mechanisms and their user-visible/security/accessibility/device-association implications before selecting one.

If a required state cannot be implemented reliably under Android/OEM constraints, document the exact limitation rather than claiming success.

#### Pixel Fold event-delivery result — 2026-09-05

An event-only Android accessibility-service probe was built and installed without connecting it to recording, gesture routing, transcription, or the agent. The service requested filtered key events, logged only physical Volume Up down/up timing and phone state, and returned `false` so Android retained normal volume handling.

Physical testing used a Google Pixel Fold on SDK 37 in the folded/closed posture with the usable outer display. The inner-display posture was intentionally excluded because that display is broken and is not part of the intended use of this test phone.

| Tested state | Physical Volume Up down/up delivered to probe |
|---|---|
| Index foreground, screen on and unlocked | Yes |
| Home screen, Index backgrounded | Yes |
| Android Settings foreground, Index backgrounded | Yes |
| Awake lock screen | Yes |
| Screen on with active media playback | Yes |
| Screen off/dozing with media idle | No |
| Screen off/dozing with active media playback | No |

The passing cases delivered one down event and one up event. A held press did not generate repeat events on this device, but the shared `downTime` and the elapsed time between down and up were sufficient to distinguish a short press from a hold. The screen-off failures were observed while the accessibility service remained enabled; active media did not restore delivery, the display remained dozing, and playback continued.

This proves that the public accessibility key-filter path can support the required gesture input while the tested Pixel Fold is awake, including when locked or another app is foregrounded. It does not support the requirement while that device's screen is fully off. An activity listener remains insufficient for global behavior. A media-session volume provider does not expose the complete physical down/release stream required for `hold -> record -> release -> stop` and would alter normal media-volume ownership, so it is not an equivalent replacement.

The Motorola Razr+ 2024 remains an intended target, but no physical result should be claimed until that device is available. OEM behavior may differ. The product requirement remains unchanged; implementation should explicitly target the device states proven feasible and retain a documented screen-off limitation where Android/OEM input policy prevents delivery.

### Milestone C — Stabilize remaining Index phone behavior

Keep each issue separate:

1. local-model duplicate or hallucinated tool-call artifact observed after named-list routing
2. alarm/device-association dependency
3. Android navigation insets
4. phone-mode Local LLM / Local-only speech defaults

The order among these can be adjusted based on investigation cost, but do not combine their implementations.

### Milestone D — Gesture timing/state machine

Implement/test gesture recognition independently from global Android hardware capture where practical.

Inputs conceptually include:

- down
- up
- elapsed time

Outputs:

- Click
- DoubleClick
- TripleClick
- Hold
- ClickHold

Acceptance:

- deterministic tests cover timing/gesture boundaries
- no Compose dependency
- no recording/audio dependency in the pure gesture detector

#### Folded Pixel Fold gesture result — 2026-09-05

The event-only Android probe now feeds a pure, common-code timing detector that emits the existing `RingGesture` vocabulary plus a hold-release signal for future recording stop behavior. Default timing is a 600 ms hold threshold and a 600 ms inter-click boundary. The Android adapter gives a pending click timer 200 ms of delivery allowance while continuing to classify the sequence from physical key-event timestamps; this prevents accessibility delivery latency from finalizing a click before an already-occurred next press reaches the service.

Focused Android host tests cover Click, DoubleClick, TripleClick, Hold, ClickHold, exact timing boundaries, hold release, repeated down events, sequence separation, and an up event without a preceding down. The full Android debug build passes.

Physical logging on the folded Pixel Fold validated:

- one short press -> `Click`
- two rapid short presses -> `DoubleClick`
- three rapid short presses -> `TripleClick`
- one held press -> `Hold`, followed by matching hold release
- one short press followed by a held second press -> `ClickHold`, followed by matching hold release

The physical ClickHold validation used a 125 ms gap between the first release and second down. Earlier attempts with 544 ms and 848 ms gaps correctly demonstrated the configured boundary behavior while tuning the adapter against accessibility delivery delay. Gesture output remains log-only at this milestone. It does not invoke recording, transcription, the agent, list routing, or gesture destinations, and the accessibility service continues returning `false` so Android retains normal volume handling.

### Milestone E — Android Volume Up capture layer

After feasibility is established, implement the smallest supported Android capture mechanism.

Validate each required device state individually rather than extrapolating from foreground success.

### Milestone F — Hold-to-record bridge

Implement the most important gesture first:

`Volume Up hold -> record -> release -> stop -> existing Index local processing`

Acceptance:

- resulting recording uses existing Index storage/queue/processing
- existing transcription/agent behavior remains unchanged
- works in every device state proven feasible by the capture milestone

#### Folded Pixel Fold hold-to-record result — 2026-09-05

The Android adapter now arms a dedicated microphone foreground service from the resumed activity
after Record Audio permission has been granted. The accessibility service sends only an existing
recording-gesture start/release pair to that already-running service. Capture uses the existing
`AudioRecorder`, writes through `RecordingStorage`, and calls
`RecordingProcessingQueue.queueLocalAudioProcessing` with the recognized gesture's existing button
sequence (`long` for Hold, `short long` for ClickHold). No
recording preprocessing, transcription, agent, tool, or list-routing implementation was changed.

Physical tests on the folded Pixel Fold validated the complete path from both the Home screen and
the awake locked screen. In each case Hold was recognized at 600 ms, recording began while Index
was not foregrounded, release stopped and queued the file, local transcription and the existing
agent ran, and the processing task completed successfully. The lock-screen capture contained 4.608
seconds of audio and used the configured Local-only speech engine. A persistent "Index phone
controls ready" notification indicates that the microphone service is armed.

After the initial recording validation, the accessibility adapter was changed to consume the full
Volume Up down/up stream whenever the recording service is armed. This prevents media volume from
increasing during a recording and keeps the stream well-formed as required by Android. If phone
controls are not armed, the event is passed through and Volume Up behaves normally. The
already-observed screen-off limitation remains: when the Pixel Fold is
fully off/dozing, Android does not deliver the Volume Up events to this public accessibility path,
so recording cannot begin in that state. After process death or reinstall, the user must open Index
once to re-arm the microphone service; reinstall may also require Accessibility to be enabled again.

Android requires the long-lived microphone foreground service to supply a notification. It is kept
silent and low priority, but cannot be removed in app code without giving up reliable recording from
Home and the awake lock screen. On Android 13+, the user may block this notification channel to hide
it from the drawer, while Android can still show Index in the system Active apps surface.

While unauthenticated, the unchanged downstream pipeline logs failed recording-persistence and
Firestore-upload attempts after local capture. Those errors did not block local transcription,
agent execution, or successful task completion in either physical test. They remain a separate
local-first cleanup concern outside this bridge milestone.

### Milestone G — Full existing gesture routing

Connect:

- 1x -> existing Click semantics
- 2x -> existing DoubleClick semantics
- 3x -> existing TripleClick semantics
- hold -> existing Hold semantics
- 2x + hold -> existing ClickHold semantics

Prefer `GestureRoutingPreferences` and `RingGesture` rather than creating a second settings/routing system.

Changes made in the existing Index gesture settings should affect the phone button where the existing model supports it.

#### Folded Pixel Fold full-routing result — 2026-09-06

The Android accessibility adapter now connects all five recognized gestures without introducing a
second routing system. Click, DoubleClick, and TripleClick read `GestureRoutingPreferences` when the
gesture is executed and dispatch the existing PlayPause, NextTrack, or Nothing behavior. Hold and
ClickHold attach their existing button sequences to the phone recording, after which the unchanged
`RecordingOperationFactory` resolves IndexAgent, WebSearch, McpSandbox, WebhookOnly, or Nothing from
the same preferences used by Index hardware. No gesture destination is hard-coded in the phone
adapter.

Physical testing while another app was foregrounded validated Click -> Nothing, DoubleClick ->
PlayPause, and TripleClick -> NextTrack. Hold produced a successful `long` local processing task and
added the spoken item to Shopping. ClickHold produced `short long`. With ClickHold configured for
WebSearch, its first task reached the existing search route and stopped with the expected signed-out
"Login required for cloud processing" error: current WebSearch is `SearchAgentNenya`, deliberately
online-only and authenticated. After changing ClickHold to IndexAgent in Index settings, the next
ClickHold recording used local transcription and the normal local agent path, proving that the phone
gesture follows the stored setting without a rebuild.

The reminder regression reported during this milestone was also physically rechecked. Although the
local model requested `list_name: shopping` for "A reminder: I need to pick up my niece tomorrow,"
the existing request-aware list resolver selected `list_todos`; the item was stored in Reminders.
The local speech model transcribed "niece" as "knees," which is a separate transcription-quality
issue rather than a destination-routing failure.

### Milestone H — Upstream merge rehearsal

Rehearse a clean merge/rebase from current upstream and measure:

- conflicts
- fork-specific files touched by upstream
- whether fork patches can be localized further
- test/build regressions

Update this context with observed merge hotspots.

#### Rehearsal result against upstream `f853c91b` — 2026-09-06

Remote references were refreshed before testing. Upstream had advanced by 10 commits from the
fork's previous `4addc6d9` base, while the phone branch had four commits beyond that common base.
The upstream and fork file-change sets did not overlap in this update. `git merge-tree` produced a
clean merged tree with zero textual conflicts. A detached temporary worktree then merged
`upstream/master` into `b58aa308` with `--no-commit`; the automatic merge was clean and the exact
combined tree passed `:androidApp:assembleDebug`. The temporary checkout was removed afterward and
the real `index-phone-phase-1` branch remained unchanged.

This update therefore has low immediate merge risk. The highest future sensitivity remains in the
fork's existing shared Index files (`ExperimentalDevices`, list tools/repositories, navigation, and
Index feed UI), but none was touched by these 10 upstream commits. Most Volume Up behavior remains
localized in new Android adapter files. Applying this upstream revision is a separate mutation from
the completed rehearsal and requires its own checkpoint decision.

---

## 19. Acceptance-test matrix

| Scenario | Required outcome |
|---|---|
| Fresh install | Starts directly in Index phone mode |
| App relaunch | Returns to Index phone mode |
| No ring paired | Index UI remains available in phone mode |
| No onboarding completion flag | Generic onboarding remains bypassed in phone mode |
| Login UI | Not shown during normal phone-mode launch |
| Note to Self | Available locally on fresh install |
| Shopping List | Available locally on fresh install |
| Reminders | Available locally on fresh install |
| User-created list | Can be created locally |
| Index feed | Existing feed behavior preserved |
| Index details/settings | Existing useful Index navigation remains accessible |
| Generic Pebble bottom nav | Not visible in normal phone mode |
| Pebble device pages | Not visible in normal phone mode |
| Agent Model default | Local LLM — one-time Android phone-mode default physically verified; later user changes remain preserved |
| Speech Engine default | Local only — one-time Android phone-mode default physically verified; later user changes remain preserved |
| Local models absent | Speech model downloads automatically, followed by the local agent model; both were physically verified present on the folded Pixel Fold |
| Volume 1x | Click recognition and configured Nothing destination physically validated |
| Volume 2x | DoubleClick recognition and configured PlayPause destination physically validated |
| Volume 3x | TripleClick recognition and configured NextTrack destination physically validated |
| Volume hold | Hold-to-record connected to existing local processing and physically validated |
| Volume 2x+hold | ClickHold recording and stored-route selection physically validated; WebSearch requires authenticated cloud |
| Index app foreground | Event delivery and gesture recognition proven on folded Pixel Fold |
| Other app foreground | All five configured gesture paths physically validated on folded Pixel Fold |
| Home screen | Hold-to-record and local processing physically validated on folded Pixel Fold |
| Lock screen | Hold-to-record and local processing physically validated while awake and locked |
| Screen off | Event delivery failed on folded Pixel Fold, both media-idle and media-playing |
| Hold recording result | Existing Index processing pipeline used successfully without pipeline changes |
| Existing gesture settings | Drive phone-button destination behavior where supported |
| Android 3-button navigation | Bottom Index input remains fully above the native controls; physically validated |
| Upstream update | Fork-specific changes remain localized |

Automatic Android phone-mode defaults select Local LLM and Local-only speech once, preserve later Settings choices, and sequentially request the speech and local-agent models through the existing Android download manager when absent. Focused host tests and the Android debug build pass; both required models were physically verified on the folded Pixel Fold after automatic scheduling. The Android app is labeled **Index** and uses the independent install ID `com.ricardochaverra.index`, while the internal Kotlin namespace remains stable to reduce upstream conflicts. App-level launcher assets replace the upstream icon without changing shared resources, and the blank Android splash follows the system light/dark appearance. The settings FAQ opens this fork branch's README. Authenticated Firebase synchronization, cloud bootstrap, Firestore transaction behavior, the duplicate local-model tool-call artifact, and alarm behavior remain unvalidated. Volume Up event delivery, gesture recognition, Hold recording through existing local processing, and Android three-button navigation inset handling are physically validated for the documented awake folded Pixel Fold states. The Motorola Razr+ 2024 remains unvalidated.

---

## 20. Likely future touch points — candidates, not permission

High-interest files include:

- `composeApp/src/commonMain/kotlin/coredevices/coreapp/ui/App.kt`
- `composeApp/src/commonMain/kotlin/coredevices/coreapp/ui/navigation/AppNavHost.kt`
- `composeApp/src/androidMain/kotlin/coredevices/coreapp/MainActivity.kt`
- `composeApp/src/androidMain/kotlin/coredevices/coreapp/MainApplication.kt`
- `androidApp/src/main/AndroidManifest.xml`
- `experimental/src/commonMain/kotlin/coredevices/ExperimentalDevices.kt`
- `experimental/src/commonMain/kotlin/coredevices/ring/ui/screens/home/IndexFeedScreen.kt`
- `experimental/src/commonMain/kotlin/coredevices/ring/ui/screens/home/IndexFeedComponents.kt`
- `experimental/src/commonMain/kotlin/coredevices/ring/ui/screens/home/FeedTabContents.kt`
- `experimental/src/commonMain/kotlin/coredevices/ring/service/button/RingGestureRouting.kt`
- `experimental/src/commonMain/kotlin/coredevices/ring/service/button/GestureRoutingPreferences.kt`
- `experimental/src/commonMain/kotlin/coredevices/ring/service/recordings/RecordingProcessingQueue.kt`
- `experimental/src/commonMain/kotlin/coredevices/ring/service/recordings/button/RecordingOperationFactory.kt`
- `experimental/src/commonMain/kotlin/coredevices/ring/util/AudioRecorder.android.kt`

Potentially avoid modifying unless investigation proves necessary:

- `experimental/src/commonMain/kotlin/coredevices/ring/service/RingSync.kt`
- `experimental/src/commonMain/kotlin/coredevices/ring/service/IndexButtonSequenceRecorder.kt`
- `libindex/*`
- BLE transport/synchronization internals
- Index AI internals unrelated to a proven routing defect
- recording preprocessing/transcription/agent internals

---

## 21. Explicit do-not-do list

Do not:

- rewrite the Index feed
- replace the Index recording processor
- replace the Index transcription/agent pipeline
- fake a physical ring by altering BLE transfer behavior without explicit evidence that this is the correct seam
- delete generic onboarding/watch/device code
- delete Pebble resources globally
- restore generic onboarding just to obtain permissions
- auto-request Bluetooth or Location merely because upstream Index hardware uses them
- refactor navigation unrelated to the current narrow objective
- introduce a new state-management framework
- add broad dependencies without necessity
- create a new Gradle module without necessity
- upgrade Kotlin/Compose/Android versions as part of unrelated feature work
- change upstream iOS behavior for Android-only requirements without approval
- create a new notes/list database or schema merely for phone mode
- hard-code gesture destinations when existing routing can be reused
- make a composable the source of truth for global/background hardware input
- claim background/lock/screen-off support without physical-device testing
- treat an agent summary as proof of the actual diff
- update this context during implementation
- claim a feature is fixed merely because code compiles
- commit local `androidApp/src/google-services.json`
- commit generated `cactus-native/.cxx/` content

---

## 22. Local build/environment notes established during setup

Known development environment facts from the validated work:

- macOS development machine
- Android Studio used as the IDE
- Java 17
- Xcode/CocoaPods were installed because the KMP project configures iOS targets even though the fork's feature work is Android-focused
- `./gradlew :androidApp:assembleDebug --no-daemon` has completed successfully after local setup
- the focused bootstrap tests and Android device-test compilation completed successfully during the bootstrap correction;
- the rebuilt debug APK was installed on the connected phone with existing app data preserved and launched successfully; no fatal/Koin startup error was observed.
- local Android build needs `androidApp/src/google-services.json`; a dummy file was copied from the repository's provided dummy config for local development
- that copied `google-services.json` must remain untracked/uncommitted
- generated `cactus-native/.cxx/` must remain untracked/uncommitted

If a new machine or upstream revision changes build requirements, update this section only after verification.

---

## 23. Current status and next executor task

Current validated implementation:

- Phase 1: direct Android Index launch — complete
- Phase 2A: local-first default Index lists — complete
- Phase 2B: Android Index activation and phone-required permission flow — complete
- Android local-first bootstrap safety/retry and Koin registration repair — complete for the tested paths
- named-list voice routing — physically validated for a custom list and the three default destinations
- Volume Up event-only feasibility — complete for the folded Pixel Fold; awake states pass and screen-off states fail
- Volume Up pure gesture recognition — host-tested and physically validated for all five existing gesture types on the awake folded Pixel Fold
- Volume Up Hold recording bridge — physically validated through existing local processing from Home and the awake locked screen
- full existing gesture routing — physically validated on the awake folded Pixel Fold; stored settings change phone behavior without rebuilding
- Android 3-button navigation inset correction — physically validated on the folded Pixel Fold
- upstream merge rehearsal through `f853c91b` — zero conflicts and combined Android debug build passed

Current known defects/requirements:

- local-model duplicate or hallucinated tool-call artifact after otherwise-correct named-list routing
- alarm/device-association dependency
- Local LLM / Local-only speech defaults and sequential automatic speech/agent model downloads are implemented, host-tested, and physically verified on the folded Pixel Fold
- WebSearch requires an authenticated cloud account; signed-out routing correctly surfaces login-required
- recording cloud uploads are gated on both backup enablement and authentication; signed-out local processing remains local
- screen-off Volume Up events are not delivered by the tested public accessibility path on the folded Pixel Fold
- Motorola Razr+ 2024 Volume Up behavior remains physically untested until that device is available
- authenticated Firebase synchronization and cloud bootstrap remain physically/emulator unverified
- Banco General still rejected the current debug/sideloaded build with Index Accessibility disabled; release signing, Developer Options/debugging, and trusted distribution remain deferred compatibility variables

### Next recommended action

Before changing additional product behavior:

1. Complete the banking-app compatibility comparison with Index Accessibility disabled, then with Developer Options/debugging disabled if needed.
2. Preserve the documented Pixel screen-off limitation and validate the Motorola Razr+ 2024 when available.
3. Keep the remaining issues separate: duplicate local-model tool-call artifact, the phone-only alarm path, and release signing/distribution.

The product requirements in Section 1 remain unchanged unless the user explicitly changes them.
