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

    /** The released skill: it describes the released app, so it is read from `main`. */
    const val URL = "https://raw.githubusercontent.com/chezgoulet/hermes-vox/main/skills/hermes-vox-app/SKILL.md"

    /** The command to paste on the machine running Hermes. `--yes` because the install is
     *  asked for by its owner; a named profile goes first (`hermes -p work skills …`). */
    fun command(profile: String = ""): String =
        (if (profile.isBlank()) "hermes" else "hermes -p ${profile.trim()}") + " skills install $URL --yes"

    /** The turn that asks the agent to install it itself. Plain about what it runs and asks for
     *  a one-line answer, so the outcome can be read on the phone. */
    fun installTurn(): String =
        "Please install the Hermes Vox app skill so you can answer questions about the app I'm " +
            "talking to you through. Run: ${command()} — then tell me in one sentence whether it is installed."

    /** The question that proves it: answered from the skill, it names the Voice section. */
    const val CHECK_QUESTION = "How do I change the voice in Hermes Vox?"

    /** The profile a base URL addresses (`https://host/p/<name>`), or "" for the default. */
    fun profileOf(baseUrl: String): String =
        Regex("""/p/([^/]+)/*$""").find(baseUrl.trim())?.groupValues?.get(1).orEmpty()
}
