# Hermes Vox 0.4.0 — release notes

## Security (C0 — no baked gateway key)

- **Hermes Vox never ships with a key. You enter your own.** The build-time gateway
  key injection is gone. The ONLY key sources are onboarding and Settings → Entity
  re-entry, both user-entered and stored via SecureStore (encrypted at rest).
  There is no default, no fallback to a baked value, and no read site that pulls a
  compiled-in key. A missing key reaches a clear user-facing
  "enter your gateway API key in Settings" state instead of failing silently.
- A release-build guard fails the build if `HERMES_VOX_API_KEY` is set in the
  environment or a `buildConfigField` key injection is re-added to the gradle
  sources — the baked-key regression can never ship again.
