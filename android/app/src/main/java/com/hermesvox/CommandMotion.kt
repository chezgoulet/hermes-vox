package com.hermesvox

/**
 * CommandMotion — how the being shows a command ran, from a closed grammar of six verbs (#227).
 *
 * One bespoke animation per command does not scale and rots as commands are added. Instead a
 * command declares its [CommandCatalog.Category] and inherits one of six motion verbs; success
 * and failure are a different *ending* on the same verb, never a different verb. A new command
 * needs no artist — it says what kind of thing it does.
 *
 * Each verb is drawn with a shape the being already has (a token of [AvatarView.SHAPE_TOKENS]),
 * held while the command runs. The endings: **done** opens into the bloom and returns; **failed**
 * stalls into the hourglass in the waiting colour, then returns. This is the app-local
 * ancestor of #233's scene vocabulary, which inherits it.
 *
 * Pure JVM, so the grammar is proven off-device.
 */
object CommandMotion {

    enum class Verb(val shape: String) {
        /** Look across something: inspecting state. */
        SWEEP("radar"),
        /** A beat at the centre: choosing among options. */
        PULSE("nucleus"),
        /** Draw inward: condensing what is there. */
        CONVERGE("infall"),
        /** Spin out and clear: starting over. */
        SCATTER("vortex"),
        /** Ripples outward: reaching for something far away. */
        RING("water"),
        /** Come to rest: informing, nothing changes. */
        SETTLE("aura"),
    }

    enum class Ending(val shape: String, val state: String, val ms: Long) {
        DONE("bloom", "settle", 900L),
        FAILED("hourglass", "waiting", 1400L),
    }

    /** How long a verb shows on its own when the command reports no outcome (a card command). */
    const val CARD_MS = 1_200L

    fun verbFor(c: CommandCatalog.Category): Verb = when (c) {
        CommandCatalog.Category.CLEAR -> Verb.SCATTER
        CommandCatalog.Category.CONDENSE -> Verb.CONVERGE
        CommandCatalog.Category.CONNECT -> Verb.RING
        CommandCatalog.Category.INSPECT -> Verb.SWEEP
        CommandCatalog.Category.CHOOSE -> Verb.PULSE
        CommandCatalog.Category.INFORM -> Verb.SETTLE
    }

    fun ending(ok: Boolean): Ending = if (ok) Ending.DONE else Ending.FAILED
}
