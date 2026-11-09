# Hermes Vox 0.9.1.1 — the profile fix

A patch on 0.9.1. Two profile defects are repaired: the list a gateway returns could be
discarded while the app blamed the host, and a profile's name could not be corrected once the
address under it changed.

## Discovery reads the answer it asked for

- The ask now states the block's schema inline, so an agent that was never taught the app's
  skill can still answer in the shape the client reads.
- The parser accepts what agents actually write: a leading bullet or the marker
  `hermes profile list` prints, `=` or `:`, wrapping quotes or backticks, a trailing comma,
  capitals, and names up to 64 characters — the host's own rule.
- A block containing only `default=<name>` is a one-profile list.
- A block with no keyed lines may name the profiles bare. Every name is still proved with
  `GET /v1/models` before it is saved, so a name the key cannot open is reported, not trusted.
- Nothing saved is deleted, repointed or re-keyed by a refresh.

## The failure says which failure it was

One line, and now one meaning each: no block, a block with no names, names that did not fit the
rule (with the count), or no answer in time. `event=profiles-ask` logs the counts, and when the
root rejected the active profile's key it records which base answered.

## The label can be corrected

- Long-press a row in the Profiles sheet to rename it. The name is a local label; the address
  carries the identity. The per-profile document moves with the name.
- After Settings → Entity saves a changed address, the app offers once to rename the profile to
  match it. *Keep name* leaves it alone, and nothing is renamed silently.
- The header follows a rename immediately.

## The key, when profiles do not share one

Hermes resolves the API key per profile. If the host root answers 401 or 404 to the active
profile's key, the ask is retried **once** against that profile's own path. A per-profile key
therefore no longer looks like a host that will not answer.

## Evidence

- Gate: `scripts/gate.sh` exit 0 — unit suite, 17-fixture stream parity, mTLS harness, live
  gateway suite (7), 440 app-skill claims (0 stale), 84 doc-truth invariants.
- CI: `android`, `repro`, `changes`, `CLA signature` green on every commit in this batch; the
  release build is dispatched from the tagged commit and verified from the published file.
- Device, on a Pixel 7 with the nightly built from `ef3e926`: two profiles discovered from a
  multiplexed gateway; long-press rename `voxtest` → `Vox Test`; switch to it; the
  address-change offer renamed the active profile and the header followed immediately. The
  throwaway profile on the host was removed afterwards.
- Not proven: a third-party gateway's exact malformed block (the parser is proven against
  synthetic replies and the shapes named in the issue), and a full voice call on the renamed
  profile.
