# #327 — paste from the clipboard (captures)

## Status: emulator previews now, the Pixel 7 pass is pending

`emulator-preview/` holds the whole shot list, captured from the same debug APK on an
Android 15 emulator (`sdk_gphone64_x86_64`, AVD `ozy35`, 1080 × 2400). It proves the
states and both themes render, but **it is not the requested Pixel 7 evidence** and must
be replaced by it before the issue is closed.

The Pixel 7 (`2A091FDH200AJ1`) is on its secure lock screen and needs its owner's PIN:
after a reboot, a locked device will not start apps whose data is credential-encrypted,
so even the side-by-side debug build cannot be launched. The device pass is one unlock
and the commands below away.

The emulator is software-GL (`swiftshader`), so it occasionally drops the being from a
frame (`-button`) and its screencap can come back empty while it is mid-frame. Those are
emulator artifacts, not app states; the `-sent` shots show the being rendering normally.

## The shot list (what `emulator-preview/` contains, and what the device pass repeats)

| file | state |
|---|---|
| `327-paste-light-button.png` / `-dark-button.png` | the call screen: `PASTE` next to `/` |
| `327-paste-light-empty.png` / `-empty.png` | the clipboard is empty — the line says so, there is no SEND |
| `327-paste-light-url.png` / `-url.png` | a URL: one-line preview + `From the clipboard · 51 characters` |
| `327-paste-light-long.png` / `-long.png` | a 5,000-character stack trace: a short head, `…`, `5,000 characters` |
| `327-paste-light-sent.png` / `-sent.png` | after SEND, in conversation mode: `You: … · 51 characters pasted` |
| `327-paste-light-being-off-sound-off.png` | the URL preview with `presence_enabled=false`, `sound_cues=false` |

The sheet is a full-screen dialog on the app's own background (`@color/hv_bg`), so the
preview shots are the sheet itself. The call screen underneath shows only in `-button`
and `-sent`.

## Why the shot build has its own package

The phone's installed `com.hermesvox` is the release-signed nightly; Android refuses a
debug APK over it (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`). The capture uses the
side-by-side debug id `com.hermesvox.shot` (same source tree; only the application id
and the FileProvider authority differ), so `adb install -r` replaces the shot build and
the owner's app and data are untouched. Build it by temporarily changing
`applicationId "com.hermesvox"` → `"com.hermesvox.shot"` in
`android/app/build.gradle` and `com.hermesvox.fileprovider` →
`com.hermesvox.shot.fileprovider` in `AndroidManifest.xml`, running
`./gradlew assembleDebug`, then reverting both files. Nothing here was built with the
release key.

## Setting the clipboard and opening the sheet

No shell command can set the clipboard on Android 17, so the debug build carries four
harness extras (inert in release behind the existing `FLAG_DEBUGGABLE` guard, never
logged): `clipboard` (text), `clipboard_file` (a file's text — for a large multi-line
paste), `clipboard_empty`, and `open_paste`. They use the real `ClipboardManager`; the
PASTE button reads it back through the same `readClipboard()` a tap uses.

```sh
PKG=com.hermesvox.shot
KEY=$(cat ~/API_KEY.txt)          # the gateway's API_SERVER_KEY
URL=http://mikoa.leopard-vibes.ts.net:8642

# connect + name a model that answers (the profile route may be unavailable)
adb shell am start -n $PKG/com.hermesvox.MainActivity \
  --es url "$URL" --es key "$KEY" --es model deepseek-v4.1-flash
# the models gate is a first-run surface; "Not now" leaves the paste button usable
adb shell input tap 539 1569      # "Not now"

# empty clipboard
adb shell am start -n $PKG/com.hermesvox.MainActivity \
  --ez clipboard_empty true --ez open_paste true

# a URL (set + open in ONE intent: the read must happen while the app has clipboard focus)
adb shell am start -n $PKG/com.hermesvox.MainActivity \
  --es clipboard 'https://github.com/chezgoulet/hermes-vox/issues/327' --ez open_paste true

# 5,000 characters (the sample is a log; a shell argument would not survive it)
adb push /tmp/hermes-vox-paste-long.txt /data/local/tmp/hermes-vox-paste-long.txt
adb shell am start -n $PKG/com.hermesvox.MainActivity \
  --es clipboard_file /data/local/tmp/hermes-vox-paste-long.txt --ez open_paste true

# after SEND, the conversation transcript is the "sent result": set layout_mode=conversation
# in shared_prefs/hv.xml (run-as), relaunch, paste, tap SEND, wait for the turn.
```

Wait ~14 s after setting the clipboard before `adb exec-out screencap -p`: Android's own
clipboard preview overlay fades, and it is not part of the app. It also covers the footer
while it is up. Setting the clipboard and opening the sheet in one intent avoids a second
issue — a read from an unfocused app returns null, which would (correctly) show the empty
state.

## What the device pass must confirm

- The line matches the state: empty / count / capped with the dropped count.
- SEND is the only thing that starts a turn; back, ✕ and CANCEL send nothing.
- The sheet is legible in light and dark, with the being off and sound off, idle and in
  a live call (the sheet has no call dependency; `send()` reuses `liveController` when a
  call is live and creates one when idle).
