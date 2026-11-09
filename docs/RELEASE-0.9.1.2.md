# Hermes Vox 0.9.1.2 — the profile fix, corrected

A patch on 0.9.1.1. Everything 0.9.1.1 repaired still stands; this corrects one rule it got
wrong and closes two small silences.

## A default line is only believed for the built-in default

0.9.1.1 accepted a block containing only `default=<name>` as a one-profile list, then proved it
with `GET /v1/models` at the host root. The root is the address of the built-in `default`
profile, and it answers 200 for any working gateway — so the probe could not refute a word the
agent made up. `default=none` produced a saved profile named `none`, pointing at the root, with
the user's key.

The rule was always narrower than that. **The host root is the built-in `default` profile, and
only that name.** Every other name is probed at `/p/<name>`; a name the gateway does not serve
fails its probe and is reported as unreachable, never saved. A one-profile host answering
`default=default` still works, and a gateway listing real named profiles is unaffected.

## Smaller, and honest

- A profile's VOX.md that could not be moved during a rename now says so, instead of reporting
  a missing document as if the profile had never been mirrored.
- Two sentences in the app's support skill described the old single-attempt ask; they now
  describe the shipped behaviour (the root, then once the profile's own path on 401/404).

## Evidence

- Gate: `scripts/gate.sh` exit 0 — unit suite, 17-fixture stream parity, mTLS harness, live
  gateway suite (7), 440 app-skill claims (0 stale), 84 doc-truth invariants.
- CI on each commit: `android`, `repro`, `changes`, `CLA signature` green.
- The release build is dispatched from the tagged commit, built twice, and the published file is
  read back and hashed.
- Not proven on a device: this patch changes which address a made-up default is probed at; the
  device pass for 0.9.1.1 covered discovery, rename and the address-change offer, and the
  behaviour they exercise is unchanged here.
