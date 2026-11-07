# Behaviour matrix, per release candidate (#208)

A table, not a session report: the failure this catches is a change that works on one device
and not the other. Each cell is **pass / fail + the log line that proves it**.

## How to record it

Per device (Android version in the header), per state (Presence off, Presence on):

| Case | How | The line that proves it |
|---|---|---|
| HTTPS gateway | place a call, one turn | `event=turn … outcome=stream-done` |
| cleartext refused | set an `http://` bare-IP address | `event=stream-turn-failed` / the pill "Can't reach the gateway" (skill §6.1) |
| wrong key | change one character of the key | the pill "key rejected"; `/v1/models` 401 |
| unreachable | airplane mode, place a call | the pill + `Reconnect failed` |
| barge-in | talk over a long reply | `event=barge-in` then `event=reply-end … reason=cancelled cause=barge-in` |
| greeting | Presence on, place a call | `event=er-glue source=greeting` (or its refusal, with the reason) |
| presence cadence | a long tool run | `event=er-presence line=…` at the density's interval |
| cues × ringer | normal / vibrate / silent / DND | `event=cue … action=… reason=…` |
| headset route | Bluetooth headset mid-call | `event=audio-route` / `event=comm-route` |

## 0.9.1

| Case | Pixel 7 · Realtime | Pixel 7 · Enhanced | Pixel 9 · Realtime | Pixel 9 · Enhanced |
|---|---|---|---|---|
| HTTPS gateway | pending | pending | pending | pending |
| cleartext refused | pending | pending | pending | pending |
| wrong key | pending | pending | pending | pending |
| unreachable | pending | pending | pending | pending |
| barge-in | pending | pending | pending | pending |
| greeting | — | pending | — | pending |
| presence cadence | — | pending | — | pending |
| cues × ringer | pending | pending | pending | pending |
| headset route | pending | pending | pending | pending |

**Status: not recorded** — the run needs the release candidate on both devices.
