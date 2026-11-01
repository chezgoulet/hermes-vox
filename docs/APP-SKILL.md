# The app skill: from the repo to your agent (#285)

`skills/hermes-vox-app/SKILL.md` is the app's verified self-description — what runs where,
every setting, every `event=` line, the failure modes the field has produced. It is how the
agent can answer "where is that setting?" without a tutorial. **Shipping it in the repo is not
the same as the agent having it**: the agent Vox talks to is configured on *your* gateway, and
a skill that is not installed there does nothing.

This note is in four parts: how a Hermes skill is enabled (read from the Hermes source, not
from memory), the shortest honest install, what was actually verified against a running agent,
and what to do when the agent's profile is not the one your Vox profile calls.

---

## 1. How a skill is enabled, in the Hermes source

Checked against the Hermes checkout at `0.22.5` (`bf0203a794`). Paths below are relative to
that checkout.

**Installed = a directory with a `SKILL.md` in the profile's skills root.** The skills root is
`<HERMES_HOME>/skills` (`hermes_constants.py:1278` `get_skills_dir`, with `get_hermes_home` at
`hermes_constants.py:188`), and `hermes skills install` writes the parsed bundle there
(`tools/skills_hub_install.py:158` `_resolve_lock_install_path`). Nothing else registers a
skill: no plugin, no database row, no index file to edit.

**A profile is a separate HERMES_HOME, therefore a separate skills root.** `-p work` (or
`--profile work`) resolves to `<root>/profiles/work` before anything else is imported
(`hermes_cli/profiles.py:2442` `resolve_profile_env`, path built at
`hermes_cli/profiles.py:2456` `root / "profiles" / canon`). So a skill installed for the
default profile is invisible to `work`, and the reverse.

**Enabled = installed AND not named in the config's disable lists.** `get_disabled_skill_names`
(`agent/skill_utils.py:278`) unions two keys from `config.yaml`:

```yaml
skills:
  disabled: [some-skill]              # off on every platform
  platform_disabled:
    api_server: [hermes-vox-app]      # off for the API server only
```

and the platform-scoped list is looked up by the turn's platform
(`agent/skill_utils.py:287` `platform_disabled`). `hermes-agent` is the one skill that cannot
be disabled (`ESSENTIAL_SKILLS`, `agent/skill_utils.py:272`). There is **no** "enabled" flag to
set: an installed skill is on by default, on every platform.

**The app's platform name is `api_server`.** Every turn the app sends enters the gateway as an
API-server turn — the adapter binds `platform="api_server"` into the session context before the
agent runs (`gateway/platforms/api_server.py:4079`), and `POST /v1/responses`
(`gateway/platforms/api_server.py:1675`) is the route Vox uses. Confirmed from the store of the
gateway used for the transcripts below: every session created by those requests has
`source = api_server` and a session key of the form `agent:main:api_server:dm:<uuid>`. So the
key that can switch the skill off for the app is `skills.platform_disabled.api_server`.

**How the agent "has" it at answer time.** Enabled skills are listed (name + description) in the
agent's system prompt by `_skills_prompt` (`agent/system_prompt.py:301`); the body is read on
demand with the `skill_view` tool. In the verified run below, the log line
`tool skill_view completed (0.05s, 78380 chars)` is the agent opening the skill it had just
seen in its index.

**Local vs gateway profiles — where the mechanism is the same and where it differs.**

- Same: the directory, the config keys and the on-demand read. A CLI turn (`hermes -p work`),
  a desktop turn and an API-server turn of the same profile all read the same `skills/` tree
  and the same `skills.disabled`.
- Differs: only the *platform* label a turn carries, and therefore which
  `platform_disabled.<platform>` list applies. CLI/TUI/desktop turns are not `api_server`
  (`gateway/session_context.py:200` `NON_MESSAGING_SESSION_SURFACES` lists `api_server`, `cli`,
  `desktop`, `tui` separately), so a skill you disabled for `api_server` still answers in a
  terminal on the same host. Disabling it in `skills.disabled` (the flat list) takes it out
  everywhere.
- Differs, and this is the trap: **a different profile is a different directory.** Nothing is
  shared between profiles, including skills.

One rough edge worth knowing: `hermes skills config` (the interactive picker) deliberately omits
`api_server` from its platform list (`hermes_cli/skills_config.py:9`
`if k != "api_server"`), so that particular key cannot be set from the picker — edit
`config.yaml` or use `hermes config set`. The picker's `global` choice writes
`skills.disabled`, which does apply to the app.

---

## 2. Install it — the shortest honest path

On the machine running Hermes:

```
hermes skills install https://raw.githubusercontent.com/chezgoulet/hermes-vox/v<version>/skills/hermes-vox-app/SKILL.md --yes
```

- `--yes` skips the confirmation prompt (`hermes_cli/subcommands/skills.py:63`); without a TTY
  the install would otherwise wait forever.
- For a named profile the profile goes **first**, before the subcommand:
  `hermes -p work skills install … --yes`.
- Install the description of the build you run: a release's own tag (`v0.9.1`), or `testing`
  for a nightly — the app's button picks it from its version. Not `main`: the skill reached
  `testing` after 0.9.0.1 was cut, so `main` has none until the next release (found on the
  device, 2026-10-22).
- The installer scans the skills it fetches and quarantines on a bad verdict; the app skill
  comes back `SAFE` / `ALLOWED`. You do not need to pass `--force`.
- The bundle is `Files: SKILL.md` and nothing else. (Before this note the skill's pointer to its
  checker read as `scripts/<file>` inside the skill folder, so every install tried to fetch a
  support file that does not exist there and logged `referenced support file … skipping it`.)

**From the phone.** Vox cannot write to the gateway, and does not pretend to. Two surfaces ask
the agent to do it instead, both using one stored turn on the same `/v1/responses` path a
normal turn uses (`AppSkillInstall`, `android/app/src/main/java/com/hermesvox/AppSkillInstall.kt`):

- **First run, once:** after the connection probe succeeds — the first moment an agent exists to
  ask — the offer "Teach your agent this app?" with *Ask the agent* / *Copy command* / *Later*.
  The answer is remembered (`app_skill_offered`); it is an offer, not a nag, and it never
  installs anything on its own.
- **Any time:** **Settings → Entity & Connection → Teach your agent this app** — the same three
  choices, plus the command in a dialog.

*Copy command* fills in the build's own ref and, for a profile address
(`https://host/p/<name>`), the `-p <name>` that goes with it.

**Nothing else is required.** An installed skill is enabled on every platform (section 1); the
running gateway picked the new skill up without a restart in the verified run below.

If the agent still does not use it, check the two disable lists — and check you installed into
the profile that actually answers the app (section 4).

---

## 3. Verified: the same question, with and without the skill

Run on 2026-11-01 against a real Hermes `0.22.5` gateway, asking exactly what the app asks
(`AppSkill.CHECK_QUESTION`), over exactly the endpoint the app uses (`POST /v1/responses`,
bearer `API_SERVER_KEY`, model route `hermes-agent`, non-streaming):

```
curl -s http://127.0.0.1:18642/v1/responses \
  -H "Authorization: Bearer $API_SERVER_KEY" -H "Content-Type: application/json" \
  -d '{"model":"hermes-agent","input":[{"role":"user","content":"How do I change the voice in Hermes Vox?"}],"stream":false}'
```

The gateway was a throwaway profile (its own `HERMES_HOME`, one agent, api_server on a loopback
port), because the owner's gateway in this environment serves Telegram only and has no
`api_server` platform — see "What this did not cover" below. The skill was installed from the
repository URL with the command in section 2, into that same profile.

### Without the skill — guesswork about the wrong voice

> To change the voice in Hermes Vox, edit your `~/.hermes/config.yaml` file:
>
> **For TTS (Text-to-Speech - text → voice):**
> ```yaml
> tts:
>   provider: "elevenlabs"  # or "edge" (default/free), "openai", "gemini", "neutts", "piper", etc.
>   elevenlabs:
>     voice_id: "YOUR_VOICE_ID"  # e.g., "pNInz6obpgDQGcFmaJgB" for Adam
>     model_id: "eleven_multilingual_v2"
> ```
> Or for Edge TTS:
> ```yaml
> tts:
>   provider: "edge"
>   edge:
>     voice: "en-US-AriaNeural"  # 322 voices, 74 languages
> ```
>
> **For STT (Voice-to-Text - voice → text):**
> ```yaml
> stt:
>   provider: "local"  # or "groq", "openai", "xai", "elevenlabs"
>   local:
>     model: "medium"  # tiny, base, small, medium, large-v3, turbo
> ```
>
> **To apply:** Run `hermes reset` (new session) or restart Hermes Desktop.
>
> **Get ElevenLabs voices:** Set `ELEVENLABS_API_KEY` in `~/.hermes/.env` and use `/voice find_voices` in chat to browse.

Every word of that is about the *gateway's* TTS, and none of it is in the app: there is no
`elevenlabs` provider in Vox, no `tts:` section in any Vox screen, and `/voice find_voices` is a
chat command the app has no surface for. It is the shape of answer the issue is about.

### With the skill installed — the app's own settings

> Hermes Vox is the Android voice client. Voice is set in the app's Settings → Voice section:
>
> - **Synthesis register** (`voice`): Natural / Bright / Deep — controls voice tone
> - **TTS speaker** (`tts_speaker_<model>`): Engine's built-in voice (e.g., Supertonic F2, Piper 0)
> - **Speak responses** (`speak_responses`): On/Off — whether replies are voiced at all
> - **Narration voice** (`er_presence_voice`): silent / sounds / spoken
>
> To switch voices: open the app → Settings → Voice → select your preferred speaker from the dropdown.
>
> If you meant the **Hermes gateway agent** (not Vox app), use slash commands:
> - `/voice on` — voice-to-voice
> - `/voice tts` — always voice
> - `/voice off` — silent

Four real rows from the skill's settings map (section 3, *Voice*), the right section name, and
the right distinction between the app's voice and the gateway's — the last paragraph is the
skill's own warning that the two are different things.

Re-run against the branch head this note ships with (byte-identical to the file in the PR, same
gateway), the answer took the same route without the field names:

> To change the voice in Hermes Vox:
>
> **In the app (Settings → Voice):**
> - **Voice provider**: Choose between **Supertonic** (10 voices) or **Piper** (different voices)
> - **Voice selection**: Pick your preferred voice within the selected provider
>
> **Behind the scenes:**
> - The app uses on-device STT (Silero VAD + Whisper/Parakeet) and TTS (Supertonic or Piper) via sherpa-onnx
> - Models (≈290 MB total) download automatically from Settings → Voice models
> - Three required models: Silero VAD, Supertonic, and Whisper base.en
>
> **Gateway configuration** (for your Hermes Agent):
> In your `config.yaml` under the `voice.tts` section, you can also specify alternative TTS providers like ElevenLabs, OpenAI, or xAI — but these don't appear in the Vox app UI. The app only controls Supertonic/Piper voices.

Wording varies run to run (the model is a local 4B); the route does not. Both runs without the
skill named the gateway's `config.yaml`; both runs with it named Settings → Voice and nothing
else. The second run also logged `tool skill_view completed (0.06s, 79298 chars)` — the agent
opening the skill it had seen in its prompt index.

### And the platform key is real: installed but disabled for `api_server`

The same profile, the skill still installed, with one key added to its `config.yaml`:

```yaml
skills:
  platform_disabled:
    api_server: [hermes-vox-app]
```

After a restart, the same question again:

> To change the voice in Hermes, set the TTS provider in `~/.hermes/config.yaml`:
>
> ```yaml
> tts:
>   provider: elevenlabs  # options: edge, elevenlabs, openai, minimax, mistral, gemini, piper, neutts, kittentts, deepinfra, xai
> ```
>
> Then run `hermes config apply` to apply changes, or restart the desktop app. For ElevenLabs, add your key via `hermes config set env.ELEVENLABS_API_KEY "your-key"`.
>
> Voice commands: `/voice on`, `/voice tts`, `/voice off`

Back to guesswork, with the skill still on disk: `platform_disabled.api_server` is the key that
matters for the app, not `skills.disabled` alone.

**What this did not cover.** The gateway here is a local profile, not the owner's — this
environment's Hermes serves Telegram and has no `api_server` platform, and I did not restart a
gateway that is serving someone's conversations to enable one. The mechanism, the platform
label and the disable lists are the same code the owner's gateway runs (`0.22.5`,
`bf0203a794`); the transcripts above are that code answering. The one thing that stays
unverified-here is a *phone* round trip over a tailnet/tunnel, which changes nothing about
skills.

---

## 4. When the agent's profile is not the one Vox calls

Three different "profiles" get mixed up here. Keep them apart:

1. **A Vox profile** (Saved connections in the app) is one base URL + key + model. A profile
   addressed as `https://host/p/<work>` reaches Hermes profile `work`, because the API server
   routes the `/p/<name>/` prefix to that profile
   (`gateway/platforms/api_server.py:1575` `_resolve_request_profile`; multiplexing is
   `gateway.multiplex_profiles`, `gateway/config.py:619`).
2. **A Hermes profile** (`hermes -p work`) is a separate HERMES_HOME — its own `skills/`, its own
   `config.yaml`, its own memory.
3. **The agent that answers** is whichever Hermes profile the request landed in. That is the one
   whose skills tree has to hold the app skill.

So:

- If the Vox profile's endpoint is `https://host/p/work`, install with the profile:
  `hermes -p work skills install … --yes`. The app's *Copy command* already does this for a
  `/p/<name>` address.
- If the Vox profile's endpoint is the bare host, the request lands in the **default** profile —
  install without `-p`.
- If the gateway answers `/p/<name>` with 404 `Unknown or unconfigured profile`, that profile is
  not served on that gateway (multiplexing off, or the profile was never created). The app's
  Saved connections report this as "Not served — this gateway has no such profile".
- If the agent answers about the *gateway's* voice instead of the app's, that is not a broken
  skill — it is an agent that does not have it. Ask it: "do you have a skill named
  `hermes-vox-app`?" A "no" means the install went to another profile, or another machine.

On the gateway host, the checks that settle it:

```bash
hermes -p <profile> skills list                    # installed? (and the name)
hermes -p <profile> config get skills.disabled     # off everywhere?
hermes -p <profile> config get skills.platform_disabled   # off for api_server?
hermes -p <profile> config path                    # the config.yaml it reads
ls "$(dirname "$(hermes -p <profile> config path)")/skills/hermes-vox-app"
```

(Without `-p`, these answer for the default profile. `config path` prints that profile's
`config.yaml`, so the skills directory is its sibling.)

`hermes skills list` reporting the skill as installed while the agent still ignores it is
almost always one of two things: a disable list (section 1), or the wrong profile.

---

## Status

- Install path, the in-app affordance, the first-run offer and this note: done.
- Acceptance — a settings question answered from the skill — **verified on 2026-11-01** against
  a real `0.22.5` gateway over `/v1/responses`, including the negative case and the
  `platform_disabled.api_server` case (section 3).
- Not verified here: a phone round trip, and the owner's own gateway profile (which has no
  api_server platform in this environment).
