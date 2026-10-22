# The app skill: from the repo to the agent (#285)

`skills/hermes-vox-app/SKILL.md` is the app's verified self-description — what runs where, every
setting, every `event=` line, the failure modes the field has produced. It is how the agent can
answer "where is that setting?" without a tutorial. **Shipping it in the repo is not the same as
the agent having it**: the agent Vox talks to is configured on the gateway.

## Install it

From the phone: **Settings → Entity & Connection → Teach your agent this app → Ask the agent.**
The app sends one turn asking the agent to run the install itself and say whether it worked.

Or, on the machine running Hermes:

```
hermes skills install https://raw.githubusercontent.com/chezgoulet/hermes-vox/v<version>/skills/hermes-vox-app/SKILL.md --yes
```

For a named profile (a Vox profile whose address is `https://host/p/<name>`), the profile goes
first: `hermes -p <name> skills install … --yes`. The *Copy command* button fills it in.

Install the description of the build you run: a release's own tag (`v0.9.1`), or `testing` for a
nightly — the app's button picks it from its version. Not `main`: the skill reached `testing` after
0.9.0.1 was cut, so `main` has none until the next release (found on the device, 2026-10-22).

## Make sure it is on

An installed skill is enabled on every platform unless the owner turned it off. Vox reaches the
agent through the API server, whose platform name is `api_server`. If the agent does not use the
skill, check the gateway's config:

- `skills.disabled` must not list `hermes-vox-app`;
- `skills.platform_disabled.api_server` must not list it.

`hermes skills config` edits both interactively; `hermes skills list` shows it installed.

## Check it

Ask by voice: **"How do I change the voice in Hermes Vox?"** Answered from the skill, the agent
names *Settings → Voice* (Text-to-speech, Delivery, Speaker) and does not invent a menu.

## Status for 0.9.1

The install path, the in-app affordance and this note are done. **The acceptance — a fresh user
asking a settings question and the agent answering from the skill — has not been run**: it
needs the owner's gateway, which the build host cannot reach.
