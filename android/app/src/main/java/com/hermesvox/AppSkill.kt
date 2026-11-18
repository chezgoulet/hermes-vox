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
 * ## The content is checked before it is installed
 *
 * A skill is standing instructions in every later session, and this one is fetched from a git
 * ref that moves: `testing` for a nightly, a tag for a release — and a tag can be rewritten.
 * Whoever can write that ref could reprogram the agent of everyone who taps the button, with
 * nothing on the phone able to notice.
 *
 * So [SKILL_SHA256] is the digest of this build's own `SKILL.md`, and [verifyCommand] checks
 * the fetched bytes against it *before* [command] installs anything. [AppSkillTest] recomputes
 * the digest from the repository file, so the constant cannot drift silently while a green
 * test run claims it is right.
 *
 * What this does not close: `hermes skills install` takes a URL and re-fetches, so a server
 * that answers the verification request with the real file and the install request with
 * another one is still believed. Closing that needs either a local-file install (the digest
 * verified copy, copied into the profile's skills directory — verified to load, but it is a
 * different install path and loses `hermes skills update` tracking) or a `--sha256` flag on
 * the install command. The moved-tag attack, which is the realistic one, is closed.
 *
 * Pure JVM, so the strings the app sends and shows are proven off-device.
 */
object AppSkill {

    const val NAME = "hermes-vox-app"

    private const val RAW = "https://raw.githubusercontent.com/chezgoulet/hermes-vox"
    private const val PATH = "skills/hermes-vox-app/SKILL.md"

    /** Where [verifyCommand] puts the fetched file, so [command] can be run straight after. */
    const val SCRATCH = "/tmp/vox-skill.md"

    /**
     * SHA-256 of `skills/hermes-vox-app/SKILL.md` at the commit this build was cut from.
     * Pinned by `AppSkillTest.thePinnedDigestIsTheSkillsOwn`, which hashes the repository file
     * and fails when it moves: a skill edit must update this constant in the same commit.
     */
    const val SKILL_SHA256 = "87596a8f2bdf920976453f30f56f8c3728700a2e884a1a2401c4c0418e117325"

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

    /**
     * Fetch the skill and check it is the file this build was cut with. Run before [command];
     * a non-zero exit means "do not install", and the turn says so.
     */
    fun verifyCommand(versionName: String): String =
        "curl -fsSL ${url(versionName)} -o $SCRATCH && " +
            "echo \"$SKILL_SHA256  $SCRATCH\" | sha256sum -c --quiet -"

    /**
     * The command to paste on the machine running Hermes. `--yes` because the install is
     * asked for by its owner; a named profile goes first (`hermes -p work skills …`).
     *
     * [profile] is only honoured when it is one safe path segment. It arrives from the
     * endpoint URL the owner typed (`…/p/<name>`) and is spliced into a shell command, so a
     * value with `;`, `$`, a space or a backtick is dropped rather than quoted — there is no
     * reason for a profile name to contain one, and a command line is not a place to find out.
     */
    fun command(versionName: String, profile: String = ""): String =
        (safeProfile(profile)?.let { "hermes -p $it" } ?: "hermes") +
            " skills install ${url(versionName)} --yes"

    /** The turn that asks the agent to install it itself: check first, then install, and say
     *  plainly whether it ran, so the outcome can be read on the phone. */
    fun installTurn(versionName: String, profile: String = ""): String =
        "Please install the Hermes Vox app skill so you can answer questions about the app I'm " +
            "talking to you through. Two steps, in this order, and stop if the first fails.\n\n" +
            "1. Check that the skill file is the one this build shipped with:\n" +
            "   ${verifyCommand(versionName)}\n" +
            "   If that exits non-zero, do not install anything: say that the skill's content " +
            "did not match the build that asked for it, and name the digest that was expected.\n\n" +
            "2. Install it:\n" +
            "   ${command(versionName, profile)}\n\n" +
            "Then tell me in one sentence whether it is installed and what step 1 printed."

    /**
     * The two lines a person pastes when they are doing it by hand: check, then install. The
     * same order the turn asks for, so neither surface is the sloppy one.
     */
    fun pasteScript(versionName: String, profile: String = ""): String =
        verifyCommand(versionName) + "\n" + command(versionName, profile)

    /** The question that proves it: answered from the skill, it names the Voice section. */
    const val CHECK_QUESTION = "How do I change the voice in Hermes Vox?"

    /** A profile name that may appear on a command line: one path segment, nothing else. */
    private val PROFILE = Regex("^[A-Za-z0-9._-]{1,64}$")

    private fun safeProfile(profile: String): String? =
        profile.trim().takeIf { PROFILE.matches(it) }

    /** The profile a base URL addresses (`https://host/p/<name>`), or "" for the default.
     *  A `…/p/` segment that is not a plain name is treated as no profile at all, so nothing
     *  from a pasted address can reach a shell. */
    fun profileOf(baseUrl: String): String =
        safeProfile(
            Regex("""/p/([^/]+)/*$""").find(baseUrl.trim())?.groupValues?.get(1).orEmpty()
        ).orEmpty()
}
