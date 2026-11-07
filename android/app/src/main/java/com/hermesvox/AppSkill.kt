package com.hermesvox

/**
 * AppSkill — getting the app's own description onto the agent it talks to (#285).
 *
 * The repo ships `skills/hermes-vox-app/SKILL.md`: what the app runs, where every setting is,
 * what each log line means. A skill in the repo does nothing for the agent until it is
 * installed on the gateway; the app cannot install it (skills are the gateway's — D25). What
 * the app owns is the shortest path: one tap asks the agent to install it itself, or the exact
 * command to paste. Installed skills are on for every platform unless the owner turned them off
 * (`skills.disabled`, `skills.platform_disabled.api_server` in the gateway's config).
 *
 * Pure JVM, so the strings the app sends and shows are proven off-device.
 */
object AppSkill {

    const val NAME = "hermes-vox-app"

    private const val RAW = "https://raw.githubusercontent.com/chezgoulet/hermes-vox"
    private const val PATH = "skills/hermes-vox-app/SKILL.md"

    /**
     * The skill that describes **this build**: a release reads it from its own tag, a nightly
     * from `testing`. Not `main`: on the Pixel the agent found `main` 404 — the skill arrived
     * on `testing` after 0.9.0.1 was cut, so `main` has none until the next release, and a
     * release's skill must describe that release, not whatever `main` holds later.
     */
    fun url(versionName: String): String {
        val v = versionName.trim()
        val ref = if (v.isEmpty() || v.contains("nightly") || v.contains("-")) "testing" else "v$v"
        return "$RAW/$ref/$PATH"
    }

    /** The command to paste on the machine running Hermes. `--yes` because the install is
     *  asked for by its owner; a named profile goes first (`hermes -p work skills …`). */
    fun command(versionName: String, profile: String = ""): String =
        (if (profile.isBlank()) "hermes" else "hermes -p ${profile.trim()}") + " skills install ${url(versionName)} --yes"

    /** The turn that asks the agent to install it itself. Plain about what it runs and asks for
     *  a one-line answer, so the outcome can be read on the phone. */
    fun installTurn(versionName: String, profile: String = ""): String =
        "Please install the Hermes Vox app skill so you can answer questions about the app I'm " +
            "talking to you through. Run: ${command(versionName, profile)} — then tell me in one sentence whether it is installed."

    /** The question that proves it: answered from the skill, it names the Voice section. */
    const val CHECK_QUESTION = "How do I change the voice in Hermes Vox?"

    /** The profile a base URL addresses (`https://host/p/<name>`), or "" for the default. */
    fun profileOf(baseUrl: String): String =
        Regex("""/p/([^/]+)/*$""").find(baseUrl.trim())?.groupValues?.get(1).orEmpty()
}
