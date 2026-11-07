# The app skill: what it is, how it is installed, how it stays true

`skills/hermes-vox-app/SKILL.md` is the app's verified self-description — what Hermes Vox is,
what a person can do with it, what it refuses, where every setting lives, what every `event=` line
means, and how to check an answer. It is what lets the agent on *your* gateway answer "where is
that setting?" without a tutorial, and what keeps it from inventing a feature.

Shipping the file in this repository is not the same as the agent having it: the agent is
configured on your gateway, and a skill that is not installed there does nothing. This page is
the human side of that — the file's contract, the shortest honest install, the evidence that it
changes an answer, and what to do when the app moves.

**A claim is a file and a token, never a line number.** Every factual statement in the skill ends
with ``(path `token`)``. The token must occur exactly once in that file, and it is the whole
claim: a line number is a denormalised index into source that any edit above it moves, so none is
stored. Adding code above a cited token changes nothing. A token that has left its file means the
claim is stale and must be corrected against the code; a token that appears twice identifies
nothing and must quote the whole line. `scripts/check-skill-claims.py` enforces exactly that on
every gate run, and it is the only tool that does — there is nothing to re-anchor.

---

## 1. What the skill teaches

The file answers four questions in order — what the app is, what a person can do with it, what
it refuses, and how to check an answer — and carries the reference behind them in between:

| Section | The question it answers |
|---|---|
| 1. What this is | The app in four facts, plus a glossary: turn, profile, Presence, being, tailnet, MCP, session, command, approval |
| 2. What a person can do with it | Every capability, how it is reached, and what it needs — conversation, sessions, files, phone actions, approvals, profiles, models, diagnostics |
| 3. What it refuses | The absences stated as plainly as the capabilities: no cloud, no account, no listening wake word, no local mind, no unapproved phone action, no full duplex, no emotion, no session store |
| 4. The pieces and where they run | The eight legs of a call, from the microphone to the avatar, and which of them leave the phone |
| 5. The settings map | Every row a user can see, its pref key, its default, and what it changes — including the rows that have no screen |
| 6. The log vocabulary | Every `event=` line a support question turns on, grouped by subsystem, with the values that are bad and what they mean |
| 7. The model catalogue | Model id, size, whether it is required, its licence, and what cannot co-exist on a small phone |
| 8. Failure modes | Log signature → cause → what to do, for the failures the field has produced |
| 9. How to check | Which command proves which claim: the checker, the gate, the gateway's routes, the phone's diagnostics block |

Three things in it are contracts rather than prose:

- **The refusals** (section 3) exist so an agent does not promise what the app cannot do — a
  listening wake word, a cloud account, a second brain on the phone, a full-duplex call.
- **The profile ask.** The app's profile switcher asks the agent for the profiles it serves by
  sending one `[[vox:profiles]]` turn; the agent answers with **only** one fenced `vox-profiles`
  block. The settings map teaches that shape, because prose instead of a block is read as "this host did
  not answer", and a block whose names fail the charset is read as "no usable profiles". The ask
  is a question, never a request to change the host.
- **The diagnostic block** (`vox-diag`) is the one thing the skill points a support conversation
  at: build, preset, device, mind side, model, counters — no transcript, key, URL or hostname.

The file carries several hundred verified claims — the checker prints the exact count on every
run. Each one is a sentence an agent could otherwise get wrong, tied to the line of code that
makes it true.

---

## 2. How it is installed

On the machine running Hermes:

```
hermes skills install https://raw.githubusercontent.com/chezgoulet/hermes-vox/v<version>/skills/hermes-vox-app/SKILL.md --yes
```

- **Pick a ref that carries the file.** A release's own tag (`v0.9.1`), or `testing` for a
  nightly; the app's own button derives the ref from its version name. `main` lags `testing` and
  does not carry the skill, so a URL built on it 404s.
- `--yes` skips the confirmation prompt (`hermes_cli/subcommands/skills.py:63`); without a TTY the
  install would otherwise wait forever.
- **A profile goes first, before the subcommand:** `hermes -p work skills install … --yes`.
- The installer scans what it fetches and quarantines on a bad verdict; this skill comes back
  `SAFE` / `ALLOWED`, so `--force` is not needed.
- The bundle is `Files: SKILL.md` and nothing else. That is deliberate: the checker lives in
  `scripts/`, outside the skill folder, because a support file inside it would be fetched on
  install and drift. The skill's text says where the checker is instead of shipping a copy.

**From the phone.** Vox cannot write to the gateway and does not pretend to. Two surfaces ask the
agent to do the install instead, both as one stored turn through the active profile
(`AppSkillInstall`, `android/app/src/main/java/com/hermesvox/AppSkillInstall.kt`):

- **First run, once:** after the connection probe succeeds — the first moment an agent exists to
  ask — the offer "Teach your agent this app?" with *Ask the agent* / *Copy command* / *Later*.
  The answer is remembered (`app_skill_offered`); it is an offer, never an automatic install.
- **Any time:** **Settings → Entity & Connection → Teach your agent this app** — the same three
  choices, plus the command in a dialog.

*Copy command* fills in the build's own ref and, for a profile address
(`https://host/p/<name>`), the `-p <name>` that goes with it.

**Nothing else is required.** An installed skill is enabled on every platform; the disable lists
in section 3 are the only switches, and a running gateway picks a new skill up without a restart.

---

## 3. The mechanism inside Hermes

Read from the Hermes 0.22.5 checkout (`bf0203a794`); paths below are relative to it.

**Installed = a directory with a `SKILL.md` in the profile's skills root.** The skills root is
`<HERMES_HOME>/skills` (`hermes_constants.py:1278` `get_skills_dir`, with `get_hermes_home` at
`hermes_constants.py:188`), and `hermes skills install` writes the parsed bundle there
(`tools/skills_hub_install.py:158` `_resolve_lock_install_path`). Nothing else registers a skill:
no plugin, no database row, no index file to edit.

**A profile is a separate HERMES_HOME, therefore a separate skills root.** `-p work` resolves to
`<root>/profiles/work` before anything else is imported (`hermes_cli/profiles.py:2442`
`resolve_profile_env`, path built at `hermes_cli/profiles.py:2456`). A skill installed for the
default profile is invisible to `work`, and the reverse.

**Enabled = installed AND not named in a disable list.** `get_disabled_skill_names`
(`agent/skill_utils.py:278`) unions two keys from `config.yaml`:

```yaml
skills:
  disabled: [some-skill]              # off on every platform
  platform_disabled:
    api_server: [hermes-vox-app]      # off for the API server only
```

`hermes-agent` is the one skill that cannot be disabled (`ESSENTIAL_SKILLS`,
`agent/skill_utils.py:272`). There is no "enabled" flag to set: an installed skill is on by
default, on every platform.

**The app's platform name is `api_server`.** Every turn from Vox enters the gateway as an
API-server turn — the adapter binds `platform="api_server"` into the session context before the
agent runs (`gateway/platforms/api_server.py:4079`). Turns ride `POST /v1/runs` by default, with
`POST /v1/responses` as the one-shot fallback when the admission 404s; both are API-server turns.
So the key that switches the skill off for the app is `skills.platform_disabled.api_server`.

**How the agent "has" it at answer time.** Enabled skills are listed (name + description) in the
agent's system prompt by `_skills_prompt` (`agent/system_prompt.py:301`); the body is read on
demand with the `skill_view` tool. In the verified run below, the log line
`tool skill_view completed (0.05s, 78380 chars)` is the agent opening the skill it had just seen
in its index.

**Local vs gateway profiles.** Same directory, same config keys, same on-demand read. What
differs is the *platform* label a turn carries, and therefore which `platform_disabled.<platform>`
list applies: CLI, TUI and desktop turns are not `api_server`
(`gateway/session_context.py:200` `NON_MESSAGING_SESSION_SURFACES`), so a skill disabled for
`api_server` still answers in a terminal on the same host. Disabling it in `skills.disabled`
takes it out everywhere.

One rough edge worth knowing: `hermes skills config` deliberately omits `api_server` from its
platform list (`hermes_cli/skills_config.py:9` `if k != "api_server"`), so that key cannot be set
from the picker — edit `config.yaml` or use `hermes config set`. The picker's `global` choice
writes `skills.disabled`, which does apply to the app.

---

## 4. How it stays true

Four habits, in the order they matter:

1. **The checker runs on every gate.** `bash scripts/gate.sh` (or `make test`) runs
   `python3 scripts/check-skill-claims.py` before it builds. Green means every token is present
   exactly once; the build cannot pass on a stale claim. `--verbose` prints each claim as it
   verifies.
2. **A failure is a statement about the code, not about the checker.** `stale claim: token not in
   this file` means read the file: if the behaviour changed, rewrite the sentence; if only the
   wording changed, quote the new line. `ambiguous — quote the whole line` means the token names
   two places; quote enough of one line. Neither outcome is fixed by a line number, and neither is
   fixed by deleting the sentence — the claims are the document's only tie to the binary.
3. **The code is the authority.** The checker proves an anchor exists, not that the sentence
   around it is true. When the two disagree, the code wins: correct the sentence, and if the code
   itself is the thing that is wrong, say so and fix it there. A rewrite that finds a false claim
   records the code that contradicts it rather than quietly dropping the claim.
4. **Add a row when the app grows.** A new setting, a new `event=` line, a new model or a new
   failure belongs in the section it teaches; the file's shape — frontmatter, a self-contained
   trigger sentence, the nine sections, a minimum claim count, and the leak guard for build-host
   paths and e-mail addresses — is checked as well, so a hollowed-out file fails even if the
   remaining tokens verify.

The rule this page exists to enforce: **the skill describes the app as it is**, in the present
tense. No version archaeology ("this replaced…"), no ticket number as the subject, no sentence
that needs a release note to parse. When the app changes, the sentence changes with it.

---

## 5. The evidence that it changes an answer

Run against a real Hermes gateway, asking exactly what the app asks (`AppSkill.CHECK_QUESTION`)
over the API server's turn route, with a throwaway profile (its own `HERMES_HOME`, one agent,
api_server on a loopback port).

**Without the skill**, asked how to change the voice, the agent described the *gateway's* TTS:
`~/.hermes/config.yaml`, an `elevenlabs` provider, `/voice find_voices`. Every word of that is
about a surface Vox does not have.

**With the skill installed**, the same question named the app's own rows: Settings → Voice, the
synthesis register (`voice`), the speaker (`tts_speaker_<model>`), `speak_responses`, the
narration voice — and drew the line between the app's voice and the gateway's.

**With the skill installed but disabled for `api_server`**, the answer went back to
`config.yaml`. That is the negative case that proves the platform key, not just the install.

Wording varies run to run; the route does not. The agent's log shows it opening the skill
(`tool skill_view completed`), which is what "the agent has it" looks like from the gateway's
side.

---

## 6. When the agent's profile is not the one Vox calls

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
- If the endpoint is the bare host, the request lands in the **default** profile — install
  without `-p`.
- If the gateway answers `/p/<name>` with 404 `Unknown or unconfigured profile`, that profile is
  not served on that gateway (multiplexing off, or the profile was never created). Saved
  connections report this as "Not served — this gateway has no such profile".
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

Without `-p`, these answer for the default profile. `config path` prints that profile's
`config.yaml`, so the skills directory is its sibling.

`hermes skills list` reporting the skill as installed while the agent still ignores it is almost
always one of two things: a disable list (section 3), or the wrong profile.
