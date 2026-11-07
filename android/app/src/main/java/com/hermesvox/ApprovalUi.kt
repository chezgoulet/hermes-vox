package com.hermesvox

import com.hermesvox.core.ApprovalChoices

/**
 * #282 — one approval as the UI needs it. The controller owns the wire/gate model;
 * this is the small immutable snapshot the host renders, so a later event cannot
 * mutate the card under the user's finger.
 *
 * The phone's cards use the same model ([PhoneApproval.Card.prompt]) — every action at Ask, not
 * only a special one: the same voice allowlist answers them, and the only difference is the two
 * words on the buttons ([labels]), which are the owner's language rather than the gateway's choice
 * words.
 */
class ApprovalPrompt(
    val runId: String,
    val requestId: String,
    /** The action in user language (never a tool name). */
    val action: String,
    /** The one plain line: what is being approved and why. */
    val line: String,
    /** Exactly the choices the gateway offered, deny included. */
    val choices: List<String>,
    /** The window before the request is denied automatically. */
    val timeoutMs: Long,
    /** Optional button text per wire choice; falls back to [ApprovalChoices.label]. */
    val labels: Map<String, String> = emptyMap(),
) {
    /** The button text for [choice]. The wire word is unchanged; only the label differs. */
    fun label(choice: String): String = labels[choice] ?: ApprovalChoices.label(choice)
}
