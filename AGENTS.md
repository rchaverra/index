# Index Phone — Coding Agent Instructions

Read `INDEX_PHONE_CONTEXT.md` completely before investigating or changing this repository.

## Role

You are a local investigation/implementation executor. You are not the architectural decision-maker.

The user's product requirements and the durable project context live in `INDEX_PHONE_CONTEXT.md`.

## Mandatory workflow

For every new feature or defect:

1. Inspect the current implementation and Git state.
2. Work on one narrow objective only.
3. Investigate first without editing source.
4. Report evidence, relevant files, smallest proposed change, risks, and verification plan.
5. Wait for explicit approval before implementation.
6. Implement only the approved scope.
7. Run the narrowest relevant tests/build.
8. Run and inspect:
   - `git status --short`
   - `git diff --check`
   - `git diff --stat`
   - the actual relevant `git diff`
9. Do not claim device-dependent behavior is validated until the user tests it on the physical Android device.
10. Do not edit `INDEX_PHONE_CONTEXT.md` during investigation or implementation. Context updates happen only in a separate explicit documentation task after validation.
11. Do not commit or push unless the user explicitly asks.

## Engineering constraints

Preserve the existing Index architecture wherever possible.

Do not:

- rewrite the Index feed
- replace or fork the Index recording/transcription/agent pipeline
- create a second notes/list data model
- fake a physical Ring through BLE unless a reviewed investigation proves that is the correct seam
- hard-code phone gesture destinations when existing `RingGesture` / `GestureRoutingPreferences` can be reused
- make Compose UI state the source of truth for global/background hardware input
- restore generic onboarding merely to acquire permissions
- proactively request Bluetooth or Location for phone mode without explicit approval
- modify iOS for Android-only work unless unavoidable and approved
- perform unrelated refactors or formatting sweeps
- upgrade dependencies/toolchains as part of unrelated feature work
- add a Gradle module or broad dependency without explicit justification and approval
- edit generated build output
- commit `androidApp/src/google-services.json`
- commit `cactus-native/.cxx/`

## Recording rule

Treat the existing Index/Ring recording processing pipeline as protected.

New phone audio/input should adapt into the existing recorder/storage/queue/operation boundaries rather than create a parallel transcription or agent path.

## Android Volume Up work

Before implementing global Volume Up capture, prove feasibility separately for:

- Index app foreground
- another app foreground
- home screen
- lock screen
- screen off

Do not infer background or screen-off support from foreground success.

For gesture recognition, preserve the existing semantics:

- Click
- DoubleClick
- TripleClick
- Hold
- ClickHold

The priority recording behavior is:

`Volume Up hold -> record -> release -> stop -> existing Index local processing`

## Verification defaults

For Android source changes, the established build command is:

`./gradlew :androidApp:assembleDebug --no-daemon`

Use narrower unit tests first when they exist, then the Android assemble task when appropriate.

Before reporting completion, show the actual diff summary and identify every modified/untracked file.

## Local-only files

The local development copy of:

`androidApp/src/google-services.json`

must remain untracked and must not be committed.

Generated:

`cactus-native/.cxx/`

must also remain untracked and must not be committed.
