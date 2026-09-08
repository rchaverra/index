# Index Phone Mode — Getting Started

Index Phone Mode is an Android-focused fork of the Core Devices mobile app. It keeps the existing
Index feed, notes, lists, recordings, local transcription, and agent actions, while replacing the
Index 01 ring button with the phone's Volume Up button.

## First setup

1. Install **Index** and open it once.
2. Allow microphone access when requested.
3. In Android **Accessibility** settings, enable **Index Volume Up**.
4. Let Index download its local speech and agent models if they are not already installed.
5. In Index settings, choose what each gesture should do.

Index defaults new phone-mode users to Local LLM and Local-only speech. Your later settings choices
remain yours to change. Cloud-dependent actions, including Web Search, still need a compatible
signed-in account.

## Using Volume Up

Hold Volume Up, speak, then release it. The recording enters the same Index processing path used by
the original app: audio is stored locally, transcribed, and routed to the selected action or list.

The configurable gesture choices are:

- Click: Nothing, Play/Pause, Next track, Previous track, or Increase volume. Increase volume
  raises media volume one standard Android step each time.
- Double click and Triple click: Nothing, Play/Pause, Next track, or Previous track.
- Hold & Talk and Double click & hold: Index agent, Web Search, webhook, MCP sandbox when configured,
  or Nothing.

Volume Up remains normal when phone controls are not armed. While a recording is armed, Index
consumes the Volume Up stream so playback volume does not change mid-recording.

## Supported phone states

Volume Up recording has been physically verified on a folded Pixel Fold and folded Motorola Razr+
2024 while the outer display is awake, from Index, another app, Home, and the awake lock screen.

The public Android accessibility mechanism did not receive Volume Up events after either tested
device entered screen-off/doze. Index cannot begin a recording in that state. This is an Android
platform limitation of the tested public path, not a setting that can be enabled in the app.

### Optional screen-off headset control

For a screen-off fallback, enable **Screen-off headset recording** in Index settings, then reopen
Index once. Hold the play/pause button on a wired or Bluetooth headset to use the configured
recording gesture. This option is disabled by default because it reserves that headset control for
Index while the phone controls are armed. It is included in the personal-use build but still awaits
physical testing on the Motorola Razr+ 2024.

Android also requires the ready-to-record microphone service to show a quiet foreground-service
notification. The notification is silent and minimized, but Android may still list it in the
notification shade or Active apps panel.

## What this fork changes

The upstream app is designed around Pebble watches and Index 01 rings. Index Phone Mode changes only
the Android entry and input layers needed for a phone-first experience:

- launches directly into Index instead of generic watch onboarding;
- creates Notes to self, Shopping list, and Reminders locally for a new install;
- uses the independent Android application ID `com.ricardochaverra.index`, allowing it to coexist
  with the upstream app;
- adds the Android Volume Up accessibility adapter while reusing the original configurable gesture
  model and recording pipeline;
- removes ring-only cues from the Android phone-mode UI.

The fork intentionally does not replace the Index data model, transcription engine, agent pipeline,
or cloud account rules. That keeps behavior familiar and reduces the work needed to bring future
upstream updates into this branch.

## Obsidian notes

When **Obsidian** is selected under **Index Agent → Where notes save**, notes are written to the
vault folder you selected. The default main-note file is **`Index App.md`**. Android can revoke a
folder grant; if Index says the selected destination did not create a note, reopen Obsidian
configuration in Index settings, select the vault folder again, and save.

## Known limitations

- Screen-off Volume Up recording is not supported on the tested Pixel Fold or Motorola Razr+ 2024.
- Screen-off headset recording is implemented but not yet physically validated on the Razr+ 2024.
- Local models can occasionally generate an extra or incorrect action. Index records each action in
  the feed so it can be reviewed. If this happens, note the spoken phrase and visible actions before
  reporting it.
- Firebase sync and cloud bootstrap need a compatible signed-in account and have not been validated
  on this fork.
- A debug/sideloaded build may not satisfy banking-app integrity checks. Release signing and trusted
  distribution are separate deployment work.
