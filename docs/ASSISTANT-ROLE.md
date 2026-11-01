# Be the phone's assistant (#329)

Vox can be the app your phone opens when you invoke its assistant — on a Pixel, a long
press of the power button; on a Samsung, a long press of the side button **if** One UI is
wired that way. This document says exactly what the app does, what Android does, and what
neither of them can do for you.

## The one sentence

Android owns the **role**; the phone maker owns the **button**. Vox asks for the role when
you tap a Settings row; whether the physical key sends the assist gesture at all is decided
in the phone's own settings.

## What "supported digital assistant" means mechanically

Android's digital assistant is whatever app holds the `android.app.role.ASSISTANT` role
(`RoleManager.ROLE_ASSISTANT`, added in API 29). An app becomes *eligible* for that role by
declaring an activity that handles the assist intent and the default category:

```xml
<intent-filter>
    <action android:name="android.intent.action.ASSIST" />
    <category android:name="android.intent.category.DEFAULT" />
</intent-filter>
```

Sources checked for this (not memory):

* `android.app.role.RoleManager` — `ROLE_ASSISTANT` is `"android.app.role.ASSISTANT"`, API 29;
  the class docs say a role is a system privilege, that qualification means "defining certain
  components in its manifest", and that the holder is granted **only with user consent**
  through `createRequestRoleIntent()`.
* `android.content.Intent#ACTION_ASSIST` — `"android.intent.action.ASSIST"`, API 16,
  "Activity Action: Perform assist action."
* `android.service.voice.VoiceInteractionService` — the system launches the *current*
  assistant on the assistant gesture ("long-pressing the power button to activate the current
  VoiceInteractionService"), and its effects apply only to a VoiceInteractionService, not to
  "general Intent.ACTION_ASSIST intents from arbitrary applications".
* The app's own manifest (`android/app/src/main/AndroidManifest.xml`) — before #329 it had no
  `ACTION_ASSIST` handler and no role request; that is the gap the reporter hit.

Measured on the verification device (Android 17, build `CP3A.261005.002.A1`):

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

and Android's own picker (`Settings.ACTION_VOICE_INPUT_SETTINGS`) lists **Hermes Vox**
next to Google, Claude, Firefox, Home Assistant and None. That is the qualification proof:
the app is eligible; the role decides whether the gesture reaches it.

## What the app adds

1. **`AssistActivity`** — a translucent, no-history, exported bridge that handles
   `ACTION_ASSIST`. It starts the *existing* call screen (`MainActivity`) with
   `NEW_TASK | CLEAR_TOP | SINGLE_TOP` and `MainActivity.EXTRA_ASSIST`, then finishes. It is
   not a second UI, has no state, and starts no service.
2. **`AssistRoute`** — the pure rule that decides what the gesture does:

   | Situation | Result |
   |---|---|
   | a call is already running | `FOCUS_LIVE_CALL` — the screen comes forward, no second session |
   | set up and idle | `START_CALL` — the call screen opens ready to listen |
   | first run (or "Skip for now" with no endpoint) | `ONBOARD` — no call it cannot hold |

   It is a pure Kotlin object, unit-tested in `AssistRouteTest` without an engine or a device.
3. **Settings → Be the phone's assistant** — the only way the app ever asks. It shows the
   role's real state (read from Android every time, never cached), a row that opens Android's
   consent dialog, and a row that opens Android's own digital-assistant screen.

## What Android will and will not do

* **The consent dialog.** On the verification build, `createRequestRoleIntent()` returns
  immediately without showing a dialog; logcat says
  `E RequestRoleActivity: Role is not requestable: android.app.role.ASSISTANT`. The app
  therefore falls back to the screen that does work: **Settings → Apps → Default apps →
  Digital assistant app** (`Settings.ACTION_VOICE_INPUT_SETTINGS`), where Vox is listed and
  one tap plus a "Change your digital assistant app to Hermes Vox?" confirmation grants it.
  The app never grants the role itself.
* **No public way to drop a role.** There is no `relinquish` API, so "undo" is Android's own
  screen: pick another app (or None) there. That is what the Settings section's second row
  opens.
* **No public way to name the current holder.** Several apps can qualify; `resolveActivity`
  then answers with the platform resolver, so the app says "another app is the phone's
  assistant" rather than guessing. Android's picker is where the name is visible.

## What the phone maker owns (the honest limit)

The role decides *which app the assist gesture reaches*. It does not create the gesture.

* **Pixel / stock Android:** long press of the power button is Android's assistant gesture.
  Nothing else to set once Vox holds the role.
* **Samsung / One UI:** the side button's long press is a One UI setting of its own (the
  "Side button" / Bixby / power-menu configuration in Settings → Advanced features). If One UI
  sends that press to its own assistant or to the power menu, Vox is never invoked — even
  holding the role. Set the long press to the assistant there, then choose Vox here.
  The exact label varies by One UI version and carrier build; no app can bind a physical key.
* Some OEMs route the gesture only to an app that also implements a `VoiceInteractionService`
  (the full assistant API, with its own overlay). Vox deliberately does **not**: that would
  mean an always-listening service. It handles the assist intent, which is what the role
  requires, and opens its normal call screen.

## Verify it yourself

```sh
# is Vox the assistant?
adb shell cmd role get-role-holders android.app.role.ASSISTANT

# grant / take back from a shell (testing only; the app itself always asks in Android's UI)
adb shell cmd role add-role-holder android.app.role.ASSISTANT com.hermesvox
adb shell cmd role remove-role-holder android.app.role.ASSISTANT com.hermesvox

# the assist gesture, from a cold start or during a call
adb shell input keyevent KEYCODE_ASSIST
```

What to expect, measured on a Pixel 7 (Android 17, `CP3A.261005.002.A1`):

| State | `KEYCODE_ASSIST` |
|---|---|
| role held, app force-stopped | Vox opens on the call screen, `LISTENING…`, timer running; VoxLog `event=assist route=start_call` |
| role held, call already live, app backgrounded | the same call screen returns with its timer still running; `event=assist route=focus_live_call`, no second session |
| role removed | Vox is not opened at all (the other assistant / the system handles it) |

The screenshots are in `artifacts/329-assistant/`.

## What this does not do

* No foreground service and no always-on listener were added. The only service is the
  existing `VoiceService`, which runs **during a call**.
* Nothing runs at first launch, and nothing asks for the role unprompted.
* Vox does not listen from the lock screen beyond what the call screen itself does once
  opened; the role gives the gesture a destination, not a microphone.
* The call screen is not `showWhenLocked`, so on a phone with a secure lock the intent is
  delivered and the screen appears once the phone is unlocked. Drawing over the lock screen
  would mean holding an assistant overlay open without the owner's unlock — the thing the
  role does not require and this app does not do.
