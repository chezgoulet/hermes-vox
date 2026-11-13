# Behaviour matrix — filled rows (#208)

One device per row (D29 ruled one; a second device adds a column, not a table). The shape is
`docs/templates/RELEASE-CUT.md` §2a: **connect** — mode × endpoint state — and **behaviour** on
the working endpoint. Every cell is the log line that proves it, or `not run — <why>`.

A cell is not a tick. `verdict=unreachable` is a *pass* for the unreachable column, because the
column asks "does the app say so, and say why"; a column that produces no line is the failure.

## Row 1 — 2026-11-12, Pixel 7, `testing` `7d59e35` (amended 2026-11-13 by the owner's handset)

Device: Pixel 7 (`2A091FDH200AJ1`), Android as the device reports it, build
`0.9.1.4-nightly-7d59e35`. Endpoint under test: `http://mikoa.leopard-vibes.ts.net:8642`,
profile «Torc». Instrument: Settings → Entity & Connection → **Test entity connection** (`RUN`),
which writes `conn-test: verdict=… ping=… stream=…` — one line, naming the cause of a failure.

**Connect**

| Mode | HTTPS gateway | cleartext refused | wrong key | unreachable |
|---|---|---|---|---|
| Realtime | not run — the bench endpoint is cleartext on the tailnet; the working state is proved by `call-ready phase=green` below | `conn-test: verdict=unreachable live=unknown ping=0(IOException: Cleartext HTTP traffic to 100.64.0.1 not permitted)` | **pass**, owner's handset, 2026-11-13: an invalid key was refused and named as a key failure, not as a network failure; the real key restored and the test returned `ok` | `conn-test: verdict=unreachable live=unknown ping=0(UnknownHostException: Unable to resolve host "nothing.leopard-vibes.ts.net")` |
| Enhanced Realtime | not run this pass | not run this pass | not run this pass | not run this pass |

Working endpoint, after restoring the address:
`conn-test: verdict=ok live=unknown ping=200` and `event=call-ready phase=green ready=mind,hearing,voice pending=- failed=- legs=3 waitedMs=2053`.

**Behaviour**

| Behaviour | The line that proves it | Result |
|---|---|---|
| barge-in | `outcome=barge-in` | **pass**, owner's handset, 2026-11-13 (walkthrough `vox-sprint1-test-20261113-002842`): speaking over the reply cut it and the new question was answered without repeating what had been heard |
| greeting | — | not run — needs a voice |
| Presence cadence | `event=er-soul` | not run — Presence requires the gate and the 2.6 GB model on the device |
| cues, across the ringer matrix | — | not run — needs ringer-state changes and a voice |
| headset route | — | not run — needs hardware |

## What this row established beyond its cells

**"Cleartext refused" is a domain allowlist, not a blanket rule.** `http://mikoa.leopard-vibes.ts.net:8642`
works; `http://100.64.0.1:9` is refused by the platform with
`Cleartext HTTP traffic to 100.64.0.1 not permitted`. The app's own note under the field says the
private network (VPN/tunnel) case is assumed, and the network security config allows cleartext for
that domain — so the matrix column means "an address outside the allowlist is refused", and the
refusal arrives as `verdict=unreachable` with the platform's reason attached. Anyone reading a field
log should not read that IOException as "the host is down".

**The two failure columns are distinguishable in one field.** `IOException: Cleartext … not permitted`
versus `UnknownHostException: Unable to resolve host …` name different causes, and both sit behind
`verdict=unreachable`. If the app ever collapses them, this row is the regression witness.

**The rename prompt is a real state.** Changing the address raises *"This profile is still called
«Torc». Rename it to match the new address?"* — the matrix pass must answer `KEEP NAME` to preserve
the profile identity the switcher shows, or the row silently renames the device's profile.

## What is owed

- **wrong key** — one pass, owner's hand, because it needs the key that is already stored.
- **HTTPS gateway** and **Enhanced Realtime** — a second endpoint that speaks TLS.
- **behaviour** — one sitting with a voice, which is the same sitting that gives paths 4, 5 and 8.
