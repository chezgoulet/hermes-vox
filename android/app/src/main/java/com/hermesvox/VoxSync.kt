package com.hermesvox

import com.hermesvox.core.HermesSession
import java.io.File

/**
 * VoxSync — the VOX.md workflow in one place, with the Presence gate FIRST.
 *
 * The owner's three rules, as code:
 *
 *  - **(a) Presence off means no workflow.** [plan] and both entry points of [obtain] /
 *    [refetch] return [Need.OFF] before a file is read, a request is made or a prompt can
 *    be shown. Presence off is not "quiet mode": the workflow does not exist.
 *  - **(b) First call per profile.** With Presence on, an active profile with no valid
 *    mirror gets one through the SAME path Settings uses ([ask] → [VoxSoul.resync]):
 *    the entity authors the document, the validator accepts or refuses it, and only an
 *    accepted document is written ([VoxMirror.write]). If the ask fails, [Attempt.ready]
 *    is false and [Attempt.reason] says why — the caller keeps Presence quiet instead of
 *    speaking a stale or empty soul.
 *  - **Verify before mirroring.** The validator is untouched: nothing here normalizes,
 *    trims, repairs by hand, or writes a document that [VoxSoul.resync] did not accept.
 *    The format contract, the no-memory rule and the Memory refusal are exactly as they
 *    were — this file only decides *which profile* a validated document belongs to.
 *
 * Pure JVM except [ask]'s HTTP turn, which is the client the app already uses.
 */
object VoxSync {

    /** What a caller must do to give Presence a document for the active profile. */
    enum class Need { OFF, READY, FETCH }

    /**
     * The gate + the first-call decision, pure.
     *
     * OFF wins over everything: with Presence off there is nothing to report about a
     * mirror, present or absent.
     */
    fun plan(presenceOn: Boolean, mirror: String?): Need = when {
        !presenceOn -> Need.OFF
        VoxSoul.mirrorStatus(mirror) is VoxSoul.Mirror.Present -> Need.READY
        else -> Need.FETCH
    }

    /** One sync attempt, in the words a caller can act on. */
    data class Attempt(
        val need: Need,
        val profile: String,
        val outcome: VoxSoul.Outcome? = null,
        val wrote: Boolean = false,
        /** The entity's raw reply (null when no turn completed) — for the honest dialog. */
        val reply: String? = null,
    ) {
        /** Presence may speak: a valid document is on disk for [profile]. */
        val ready: Boolean get() = need == Need.READY || wrote

        /** Why Presence must stay quiet (null when ready, or when Presence is off). */
        val reason: String? get() = when {
            ready || need == Need.OFF -> null
            outcome is VoxSoul.Outcome.Rejected -> outcome.reason
            outcome != null -> "the entity's VOX.md was valid but could not be written to this phone"
            else -> "the entity did not answer"
        }
    }

    /**
     * Ask the entity to author (or return) its voice-export. This IS the resync flow: one
     * stored turn on the live chain ([HermesSession.turnStored]), so a [retryNote] lands
     * with the entity already knowing the format it just got wrong.
     *
     * Blocking (up to the client's turn timeout) — call it off the UI thread.
     */
    fun ask(session: HermesSession, retryNote: String? = null, log: (String) -> Unit = {}): String? {
        val directive = if (retryNote != null) "$retryNote\n\n${VoxSoul.AUTHOR_DIRECTIVE}" else VoxSoul.AUTHOR_DIRECTIVE
        return try { session.turnStored(directive) } catch (e: Throwable) {
            log("event=vox-resync-failed err=${e.message?.take(120)}"); null
        }
    }

    /**
     * The first-call rule: give [profile] a document if, and only if, it has no valid one.
     * Blocking; call it off the UI thread.
     *
     * The legacy device-wide mirror is migrated first, so an install upgrading across this
     * change keeps the document it already had instead of being asked again.
     */
    fun obtain(
        root: File,
        profile: String,
        presenceOn: Boolean,
        retryNote: String? = null,
        log: (String) -> Unit = {},
        ask: (String?) -> String?,
    ): Attempt {
        if (!presenceOn) return Attempt(Need.OFF, profile)          // rule (a): nothing happens
        VoxMirror.migrate(root, profile)                             // one-way, idempotent
        if (plan(true, VoxMirror.read(root, profile)) == Need.READY) return Attempt(Need.READY, profile)
        return refetch(root, profile, true, retryNote, log, ask)
    }

    /**
     * Ask, validate, and mirror into [profile]'s slot — the Settings "Resync VOX.md" button
     * and [obtain] share this. A document that fails validation is never written; the
     * outcome carries the reason the UI shows verbatim. Blocking.
     */
    fun refetch(
        root: File,
        profile: String,
        presenceOn: Boolean,
        retryNote: String? = null,
        log: (String) -> Unit = {},
        ask: (String?) -> String?,
    ): Attempt {
        if (!presenceOn) return Attempt(Need.OFF, profile)          // rule (a): no request at all
        val reply = ask(retryNote)
        val outcome = VoxSoul.resync(reply)
        val document = when (outcome) {
            is VoxSoul.Outcome.Accepted -> outcome.document
            is VoxSoul.Outcome.Repaired -> outcome.document
            is VoxSoul.Outcome.Rejected -> null
        }
        val wrote = document != null && VoxMirror.write(root, profile, document)
        // The line the field greps for, with the profile it belongs to and a sample of what
        // the entity actually said (a failed sync must be diagnosable from the log alone).
        val evidence = reply?.trim()?.replace(Regex("\\s+"), " ")?.take(240)?.ifBlank { "(reply was blank)" } ?: "(no reply)"
        val verdict = when (outcome) {
            is VoxSoul.Outcome.Accepted -> "ok"
            is VoxSoul.Outcome.Repaired -> "ok repaired=${outcome.repairs.joinToString("; ")}"
            is VoxSoul.Outcome.Rejected -> "invalid: ${outcome.reason}"
        }
        log("event=vox-resync profile=$profile doc=${document?.length ?: 0} result=$verdict reply=$evidence")
        return Attempt(Need.FETCH, profile, outcome, wrote, reply)
    }
}
