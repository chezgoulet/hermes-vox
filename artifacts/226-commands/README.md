# #226 — the commands screen, on a device

Real captures of the branch build: each PNG is the app's own frame buffer, taken with
`adb exec-out screencap` while `scripts/capture-commands.sh` drove the screen with
`input tap` / `input swipe`. Nothing here is a mock-up or a re-draw.

- **Device:** the `ozy35` AVD — Android 15, 1080 × 2400, 420 dpi, `swiftshader_indirect`.
  The owner's Pixel 7 was at the lock screen (PIN required after restart) and could not be
  unlocked for this pass, so the shots are from the emulator; the app, the build and the
  taps are the same.
- **Build:** debug APK of this branch (`feat/226-lane`), `versionName 0.9.0.3`,
  `applicationId com.hermesvox`. No gateway is required for any shot.
- **Ground:** the app's own `theme` pref — `dark` is `values-night` (OLED `#000000` page,
  `#0A0F16` rows), `light` is `values/` (`#F3F5F9` page, `#FFFFFF` rows), the authored
  light ground from #275.

## The shots

| file | ground | what it shows |
|---|---|---|
| `dark-all-groups.png` | dark | the whole sheet: header, search, and all four groups — Conversation, Connection, Session, Diagnostics |
| `dark-running.png` | dark | `/reconnect` **running…** on its own row |
| `dark-queued-and-still-working.png` | dark | `/clear` **queued** while `/reconnect` says **still working…** (past the 2 s threshold, with no number) |
| `dark-failed-and-done.png` | dark | `/reconnect` **failed** and `/clear` **done**, in the same frame |
| `light-sheet.png` | light | header, search, Conversation and Connection on the authored light page |
| `light-all-groups.png` | light | Session and Diagnostics, to the last row (`/help`) |
| `light-queued-and-still-working.png` | light | the same queued / still-working pair on the light ground |

**Not captured: light running, light failed, light done.** The emulator's system UI started
ANR-ing under swiftshader after the light pass, and every further attempt produced an ANR
dialog instead of the app. The four states are the same code path in both grounds — the
chip colours come from the same tokens, measured in `LightModeContrastTest` — but the
light-ground state shots are an honest gap, not an omission of the feature.

## How each state was produced

Not by a harness flag, and not by a timer in the app:

- **running** — `/reconnect` against `scripts/commands-slow-gateway.py` over `adb reverse`:
  a socket that accepts the connection and never answers, so the app's own
  `GatewayClient.TIMEOUT_MS` (20 s) is what decides when it ends.
- **queued** — `/clear` tapped while that `/reconnect` is still running. Inline commands
  run one at a time, so the second row says `queued` instead of being dropped.
- **still working…** — the same row three seconds in: past `Operations.LONG_MS`, the words
  change and no number appears.
- **failed** — the same `/reconnect` when its 20 s timeout expires.
- **done** — the queued `/clear`, which touches nothing on the gateway, runs next and
  finishes.

## Reproducing

```sh
adb install -r android/app/build/outputs/apk/debug/hermes-vox-0.9.0.3.apk
scripts/capture-commands.sh artifacts/226-commands com.hermesvox dark
scripts/capture-commands.sh artifacts/226-commands com.hermesvox light
```

The sheet is opened by the debug harness (`--ez sheet true`, inert in a release build);
tapping `/` opens the same screen for a person.
