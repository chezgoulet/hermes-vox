package com.hermesvox

/**
 * #282 — one approval as the UI needs it. The controller owns the wire/gate model;
 * this is the small immutable snapshot the host renders, so a later event cannot
 * mutate the card under the user's finger.
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
)
