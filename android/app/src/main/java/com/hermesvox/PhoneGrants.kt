package com.hermesvox

/**
 * PhoneGrants — #429 step 1: the one place the phone's capability groups and their grants live.
 *
 * The tool schema, the Phone tools screen and the audit all read this object, so there is exactly
 * one answer to "what may the agent do on this phone". A second list would be a second truth, and
 * the first time the two disagreed the screen would promise something the schema did not offer.
 *
 * ## Three states, per group
 *
 * | state | the schema | a call |
 * |---|---|---|
 * | **Off** | the group's actions are **absent** from the `enum` | `ok:false, reason:"not_granted"` |
 * | **Ask** (Tier 0) | the actions are **absent** from the `enum` (except one with its own card, `uninstall`) | `ok:false, reason:"approval-required"`, and the detail says no card exists for it yet |
 * | **Ask** (Tier 1) | the actions are offered | executed, and reported: a reported group is on when the owner turns it on |
 * | **Ask** (Tier 2) | the actions are offered | `ok:false, reason:"approval-required"` — every use is a card |
 * | **Allow** (Tier 0 only) | the actions are offered | executed, and reported |
 *
 * **Every group defaults to [GrantState.OFF].** There is no allow-all; the closest shortcut,
 * [PhoneGrants.instantAll], touches Tier 0 and skips the groups a bulk tap must not decide.
 *
 * **Allow is a Tier 0 word.** Tier 1 changes something the owner can see and Tier 2 leaves the
 * phone or reads what is private, so both stop at Ask; [effective] coerces a stored Allow back to
 * Ask rather than trusting the caller. That is why the setting is validated on read *and* write:
 * a stale preference is not a grant.
 *
 * **Ask is not one behaviour.** It is "the owner said yes, once, at the group" for a Tier 1 group —
 * which acts now and is reported, because there is nothing to wait for after the group was turned
 * on — and "every use waits for a card" for Tier 2. For **Tier 0** there is no card: its actions
 * are withheld from the enum and a call is refused with the truth ("there is no approval card for
 * it yet"), because an offer in an `enum` is a capability the phone must be able to keep.
 * [PhoneTool.parse] is where that line is drawn.
 *
 * ## Which tier a group is
 *
 * The tiers are [PhoneTiers]' existing table (D34), extended to the groups #429 names. A group's
 * `actions` are the ones the phone implements **today** and may advertise; `planned` names the
 * actions the group will hold when its capability lands, so a direct call today gets a governance
 * refusal that names the group instead of "unknown action" — and is never advertised, because a
 * promise in an `enum` is a capability the phone must be able to keep.
 *
 * Pure JVM, so [PhoneGrantsTest] proves the defaults and the tier rule without a device. The
 * Android side (reading and writing the owner's choices) is [PhoneGrantStore].
 */
object PhoneGrants {

    /** One group's grant. [OFF] is the only default. */
    enum class State(val id: String, val label: String) {
        OFF("off", "Off"),
        ASK("ask", "Ask"),
        ALLOW("allow", "Allow"),
        ;

        companion object {
            /** The stored form, forgiving about case and whitespace; null when it is not a state. */
            fun of(raw: String?): State? = values().firstOrNull { it.id == raw?.trim()?.lowercase() }
        }
    }

    /**
     * A capability group: what it is called, what tier it belongs to, one line of what it lets the
     * agent do, the warning where the consequence is not obvious, and its action names.
     *
     * [actions] are implemented and may be offered; [planned] are named only so a direct call is
     * refused as "not granted" rather than as "unknown".
     */
    data class Group(
        val id: String,
        val label: String,
        val tier: Int,
        /** One line, in the owner's words: what this lets the agent do. */
        val lets: String,
        /** Where the consequence is not obvious, the sentence that says it plainly. */
        val warning: String? = null,
        val actions: List<String> = emptyList(),
        val planned: List<String> = emptyList(),
    ) {
        /** True when the phone can perform at least one action of this group today. */
        val built: Boolean get() = actions.isNotEmpty()
    }

    /**
     * The thirteen groups, in the order the screen and the schema read them: instant first, then
     * reported, then approval. One list; no second copy.
     */
    val GROUPS: List<Group> = listOf(
        Group(
            id = "timers-alarms",
            label = "Timers and alarms",
            tier = PhoneTiers.INSTANT,
            lets = "Set, cancel and check countdown timers and alarms.",
            actions = listOf("timer", "alarm"),
        ),
        Group(
            id = "dnd-volume",
            label = "Do not disturb and volume",
            tier = PhoneTiers.INSTANT,
            lets = "Turn Do not disturb on or off, and change the media volume.",
            actions = listOf("dnd", "volume"),
        ),
        Group(
            id = "notes",
            label = "Reminders and notes",
            tier = PhoneTiers.REPORTED,
            lets = "Keep a short note, and report it back to you.",
            actions = listOf("note"),
        ),
        Group(
            id = "media",
            label = "Media",
            tier = PhoneTiers.INSTANT,
            lets = "Play, pause, skip, and ask what is playing.",
            actions = listOf("play", "pause", "skip", "now_playing"),
        ),
        Group(
            id = "device-state",
            label = "Device state",
            tier = PhoneTiers.INSTANT,
            lets = "Read battery, charging, connectivity, screen and storage state.",
            actions = listOf("state"),
        ),
        Group(
            id = "clipboard",
            label = "Clipboard",
            tier = PhoneTiers.INSTANT,
            lets = "Put text on the clipboard, and read what is there when the phone allows it.",
            warning = "Whatever you copy can be read by your agent while Vox is in front — " +
                "including a password or a code.",
            actions = listOf("clip", "clip_read"),
        ),
        Group(
            id = "files",
            label = "Files",
            tier = PhoneTiers.REPORTED,
            // The folder is the owner's grant, by its own name: Android 11+ will not grant the
            // Download root, so the folder really is a subfolder (`Download/Work`).
            lets = "List, read and offer files inside the folder you granted.",
            warning = "Your agent could list that folder and read a file in it when you approve it.",
            actions = listOf("file_list", "file_read", "file_offer"),
        ),
        Group(
            id = "calendar",
            label = "Calendar",
            tier = PhoneTiers.REPORTED,
            lets = "Read today's events and create an event, which it can undo.",
            actions = listOf("agenda", "event_create"),
        ),
        Group(
            id = "notifications",
            label = "Notifications",
            tier = PhoneTiers.INSTANT,
            lets = "Read the notifications currently on this phone while a call is open — " +
                "codes, messages and anything else on your lock screen go to your agent.",
            warning = "Your agent could read every notification on this phone. Codes, messages " +
                "and anything else on your lock screen go to your agent.",
            actions = listOf("notifications", "notification_read"),
        ),
        Group(
            id = "messages",
            label = "Messages",
            tier = PhoneTiers.INSTANT,
            lets = "Write a text and hand it to your messaging app. You send it.",
            actions = listOf("message"),
        ),
        Group(
            id = "calls",
            label = "Calls",
            tier = PhoneTiers.INSTANT,
            lets = "Open the dialer with a number ready. You make the call.",
            actions = listOf("dial"),
        ),
        Group(
            id = "apps",
            label = "Apps",
            tier = PhoneTiers.INSTANT,
            lets = "Open an app or a link, or remove an app. Removing always asks you first.",
            actions = listOf("launch", "open", "uninstall"),
            planned = listOf("install"),
        ),
        Group(
            id = "contacts",
            label = "Contacts",
            tier = PhoneTiers.INSTANT,
            lets = "Look up a contact's number by name.",
            actions = listOf("contact"),
        ),
    )

    /** The state every group starts in. There is no other default. */
    val DEFAULT: State = State.OFF

    /** A read of the owner's policy. Implementations return the stored state; [effective] applies. */
    fun interface Source {
        fun state(group: Group): State
    }

    /** Every group Off — the state of a phone that has never been asked. */
    val ALL_OFF: Source = Source { DEFAULT }

    /** The state of one action's group, or null when the action belongs to no group. */
    fun groupOf(action: String): Group? {
        val a = action.trim().lowercase()
        return GROUPS.firstOrNull { g -> a in g.actions || a in g.planned }
    }

    /**
     * The tier of [action], group first so planned names answer honestly too.
     *
     * A **planned** action keeps its own tier rather than inheriting the group's: the apps group
     * is instant because `launch`/`open` are, while `uninstall` is still an approval action. A
     * planned name is never advertised either way.
     */
    fun tierOf(action: String): Int? {
        val a = action.trim().lowercase()
        val group = groupOf(a) ?: return PhoneTiers.of(a)
        // An action's own tier wins inside a group. The apps group is instant because launch/open
        // are, while `uninstall` stays approval: a group grant is not a grant to remove anything.
        return PhoneTiers.of(a) ?: group.tier
    }

    /**
     * The states the owner may choose for [tier]. Allow exists for Tier 0 alone: Tier 1 is visible
     * but private enough to ask, and Tier 2 is never instant whatever the owner picks.
     */
    fun statesFor(tier: Int): List<State> =
        if (tier == PhoneTiers.INSTANT) listOf(State.OFF, State.ASK, State.ALLOW)
        else listOf(State.OFF, State.ASK)

    /** True when [state] is a legal choice for [tier]. */
    fun permits(tier: Int, state: State): Boolean = state in statesFor(tier)

    /**
     * The state as it is actually honoured: an Allow stored against a Tier 1 or Tier 2 group is
     * read back as Ask. A grant that cannot be given is not a grant.
     */
    fun effective(group: Group, state: State): State =
        if (state == State.ALLOW && !permits(group.tier, state)) State.ASK else state

    /** The live state of [group]'s grant, normalised. */
    fun stateOf(source: Source, group: Group): State = effective(group, source.state(group))

    /** The live state of [action]'s group, or null when it belongs to none. */
    fun stateOf(source: Source, action: String): State? = groupOf(action)?.let { stateOf(source, it) }

    /**
     * The actions the model may be offered right now: every implemented action whose group is not
     * Off, plus `undo` and `status`.
     *
     * An entry in this list is a promise the phone must be able to keep, so a group at **Ask** is
     * only advertised where Ask is real: a Tier 1 group at Ask is on and runs, and an action with
     * its own card (`uninstall`) is honoured. A Tier 0 group at Ask has no card to wait for, so its
     * actions are withheld, and a direct call gets the truth from [PhoneTool.parse] rather than a
     * promise of an approval that will not come.
     *
     * `undo` and `status` belong to no group on purpose. `undo` only reverses what the agent itself
     * did (H4), and `status` only reports the mind's own view; neither acts on the phone, and a
     * phone whose grants are all Off must still be able to say so.
     */
    fun advertised(source: Source): List<String> {
        val out = ArrayList<String>()
        for (g in GROUPS) {
            val state = stateOf(source, g)
            if (state == State.OFF) continue
            for (a in g.actions) {
                if (state == State.ASK && !honoursAsk(g, a)) continue
                out.add(a)
            }
        }
        out.addAll(ALWAYS)
        return out
    }

    /** True when a call to [action] in [group] can succeed while the group is only at Ask. */
    private fun honoursAsk(group: Group, action: String): Boolean {
        val tier = tierOf(action) ?: group.tier
        // Reported groups run at Ask; an action with its own approval card is honoured too,
        // because the card is the thing the owner answers. That is `uninstall` and `file_offer`.
        return tier == PhoneTiers.REPORTED || PhoneApproval.handshaked(action)
    }

    /** The two actions that are not a capability: reversing one, and asking what is on. */
    val ALWAYS: List<String> = listOf("undo", "status")

    /**
     * "Allow all instant actions" — the one bulk shortcut the design permits. It touches Tier 0
     * only and never [BULK_EXEMPT]: one tap is not the place to grant the groups that read other
     * people's words, or the apps group whose Allow is the uninstall request path. Not "Allow all",
     * and not "Allow all of the private ones" either.
     */
    fun instantAll(states: Map<String, State>): Map<String, State> {
        val out = LinkedHashMap(states)
        for (g in GROUPS) {
            if (g.tier == PhoneTiers.INSTANT && g.id !in BULK_EXEMPT) out[g.id] = State.ALLOW
        }
        return out
    }

    /**
     * The Tier 0 groups a bulk tap must not decide: notifications and contacts put other people's
     * words into the agent's conversation, and apps carries the uninstall request path. Each one
     * stays a decision of its own even if a shortcut is ever wired to the screen.
     */
    val BULK_EXEMPT: Set<String> = setOf("notifications", "contacts", "apps")

    /** The groups grouped by tier, in display order. */
    fun byTier(): List<Pair<Int, List<Group>>> =
        listOf(PhoneTiers.INSTANT, PhoneTiers.REPORTED, PhoneTiers.APPROVAL)
            .map { tier -> tier to GROUPS.filter { it.tier == tier } }

    /** The word the screen uses for a tier. */
    fun tierLabel(tier: Int): String = when (tier) {
        PhoneTiers.INSTANT -> "Instant"
        PhoneTiers.REPORTED -> "Reported"
        else -> "Approval"
    }

    /** The one line that explains a tier's rules above its rows. */
    fun tierLine(tier: Int): String = when (tier) {
        PhoneTiers.INSTANT ->
            "Reversible and private. Off hides these actions from your agent; Allow lets them run " +
                "and be reported. Ask has no card to answer it yet, so a group at Ask is refused " +
                "and nothing runs until you choose Allow."
        PhoneTiers.REPORTED ->
            "Changes something you can see. Off, or Ask — and Ask means it runs, because a " +
                "reported group is one you have already turned on. Every use is recorded below."
        else ->
            "Consequential or private to read. Off or Ask only, and every use is a card of its own " +
                "even when the group is on."
    }

    /** A short, non-secret summary for a log line: `timers-alarms=allow,dnd-volume=off`. */
    fun summary(source: Source): String =
        GROUPS.joinToString(",") { "${it.id}=${stateOf(source, it).id}" }
}
