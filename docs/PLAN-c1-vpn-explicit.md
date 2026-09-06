# SPEC — C1: VPN-explicit onboarding + version bump (0.4.0 polish, batch 2)

## Why (directive 2026-09-06)
Vox speaks HTTP+bearer to a gateway address. Safe ONLY over a user-managed private
network. Today the README states it but the APP never tells the user. Christopher:
"must make it explicit that it is assumed this will be used over a VPN which the
user is responsible for setting up. We can recommend tailscale and nebula."

## D1 — Onboarding Network panel (OnboardingActivity.kt + layout activity_onboarding.xml)
- Between URL field and Connect/finish action: a short static panel (TextView block,
  no new interactive elements):
  "Network: Vox expects you to reach your Hermes gateway over a private network you
  control (Tailscale and Nebula are good options). Without one, your API key and
  audio travel over plain HTTP."
- Exact strings go in strings.xml (i18n-ready), not inline in Kotlin.
- Onboarding must NOT block on this (no checkbox, no 'I understand' — statement, not
  a gate). Keep the flow one screen; no scroll regressions (the layout is a ScrollView
  already).

## D2 — Settings → Entity one-liner (activity_settings.xml + SettingsActivity bind)
- Under the gateway URL row: small helper text, same substance, one sentence:
  "Private network (VPN/tunnel, e.g. Tailscale or Nebula) assumed — key + audio use plain HTTP otherwise."

## D3 — README polish
- README.md "Privacy" section: name the VPN expectation explicitly (it currently only
  says 'network access to your gateway' and 'plain HTTP'); mention Tailscale/Nebula as
  examples. Keep the 'never transmits audio anywhere except your gateway' claim intact.

## D4 — Version bump C0+C1 batch marker (NO release notes yet)
- versionCode 78, versionName "0.4.0-beta1". Release notes file lands with C2/C3 when
  the 0.4.0 batch actually releases. (Public consumers must never see the word baked.)
- Gate: gradle :app:testDebugUnitTest exit 0 (harness now at /home/c/gradle-8.12.1/bin/gradle).

## Verification (Torc)
- Grep strings.xml for both texts; read the onboarding layout diff (ScrollView intact,
  panel positioned between URL and Connect, not before the title).
- Independent gate on final tip with build/ purge + --rerun-tasks + XML mtime check.
