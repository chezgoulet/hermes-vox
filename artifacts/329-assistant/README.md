# #329 — Vox as the phone's digital assistant: device evidence

Device: **Pixel 7** (`2A091FDH200AJ1`, `panther`)
Android: **17** (`ro.build.version.sdk` 37)
Build: **`CP3A.261005.002.A1`**
APK: `hermes-vox-0.9.0.3.apk`, debug build of branch `feat/329-assistant-role` (`c697907` + this PR)

All captures are `adb exec-out screencap -p` from that handset. The emulator is not used
anywhere in this folder.

## What the app registers

```
$ adb shell cmd package query-activities --brief -a android.intent.action.ASSIST -c android.intent.category.DEFAULT
6 activities found:
  Activity #0:
    com.google.android.googlequicksearchbox/.GoogleAppImplicitActionAssistGatewayInternal
  Activity #1:
    com.anthropic.claude/.mainactivity.AssistantOverlayActivity
  Activity #2:
    com.hermesvox/.AssistActivity
  ...
```

`com.hermesvox/.AssistActivity` is the new entry point; handling `ACTION_ASSIST` is what makes
the app eligible for `android.app.role.ASSISTANT`.

## Screenshots

| # | File | What it shows |
|---|---|---|
| 01 | `01-settings-home-assistant-row.png` | Settings home: the new **Be the phone's assistant** row, subtitle read live from Android — "Off — another app is the phone's assistant" before the role is granted |
| 02 | `02-assistant-section.png` | The section: the state line, "Use Hermes Vox as the assistant" (`ASK ANDROID`), "Android's digital assistant settings" (`OPEN`), and the text that separates Android's role from the maker's side-button mapping |
| 03 | `03-role-request-outcome.png` | What the request row does on this build: Android refuses the in-app request (`E RequestRoleActivity: Role is not requestable: android.app.role.ASSISTANT` in logcat), so the app says so and hands over the system-settings intent instead of failing silently |
| 04 | `04-system-assistant-picker-vox.png` | Android's own **Default digital assistant app** screen: Hermes Vox is listed and selectable next to Google, Claude, Firefox and Home Assistant — the qualification proof, and the user path that works on this build |
| 05 | `05-android-consent-change-to-vox.png` | Android's consent: "Change your digital assistant app to Hermes Vox?" (Keep current / Change) — the role is only ever granted by this tap |
| 06 | `06-cold-assist-opens-call.png` | **Cold start**: app force-stopped, role held, `input keyevent KEYCODE_ASSIST` → the existing call screen opens, `LISTENING…`, timer running. Log: `event=assist route=start_call callLive=false onboarding=false` |
| 07 | `07-live-call-assist-focuses.png` | **Already in a call**: call live, app sent Home, `KEYCODE_ASSIST` → the same call screen returns with its timer still running (00:23, not 00:0x). Log: `event=assist route=focus_live_call callLive=true` — no second session |
| 08 | `08-role-removed-assist-does-not-open-vox.png` | **The negative**: role removed, `KEYCODE_ASSIST` must not open Vox (see the note below) |

## The role commands and their real output

```
$ adb shell cmd role get-role-holders android.app.role.ASSISTANT
com.google.android.googlequicksearchbox

$ adb shell cmd role add-role-holder android.app.role.ASSISTANT com.hermesvox
$ adb shell cmd role get-role-holders android.app.role.ASSISTANT
com.hermesvox

$ adb shell cmd role remove-role-holder android.app.role.ASSISTANT com.hermesvox
$ adb shell cmd role get-role-holders android.app.role.ASSISTANT
com.google.android.googlequicksearchbox
```

`cmd role add-role-holder/remove-role-holder` is the form this build accepts (`cmd role help`
lists exactly those; there is no `is-role-holdable`). The role was granted through Android's
own picker in 05 as well, so the app never takes it silently.

## Note on 08

The handset's secure keyguard re-engaged between 07 and the negative run (another agent session
shares this Pixel; `dumpsys trust` reports `strongAuthRequired=0x1`), and Android 17 refuses
`screencap` on a locked device, so the capture for the negative case was taken as the role
removal itself — `cmd role get-role-holders` returning
`com.google.android.googlequicksearchbox`, followed by `KEYCODE_ASSIST` with no
`event=assist` in logcat and Vox not resumed. The screenshot is re-taken on the next unlocked
device pass; the state it must show is the system's assistant, not Vox.
