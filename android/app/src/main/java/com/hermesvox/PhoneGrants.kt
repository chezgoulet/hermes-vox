package com.hermesvox

/**
 * PhoneGrants — #429 step 1: the one place the phone's capability groups and their grants live.
 *
 * The tool schema, the Phone tools screen and the audit all read this object, so there is exactly
 * one answer to "what may the agent do on this phone". A second list would be a second truth, and
 * the first time the two disagreed the screen would promise something the schema did not offer.
 *
 * ## Three states, one meaning each
 *
 * | state | the schema | a call |
 * |---|---|---|
 * | **Off** | the group's actions are **absent** from the `enum` | `ok:false, reason:"not_granted"` |
 * | **Ask** | the actions are offered | `ok:false, reason:"approval-required"`, a card naming the action and its arguments, and nothing runs until the owner answers |
 * | **Allow** | the actions are offered | executed without a card |
 *
 * **Every group defaults to [State.OFF].** There is no allow-all; the closest shortcut,
 * [PhoneGrants.instantAll], touches Tier 0 and skips the groups a bulk tap must not decide.
 *
 * **The state is honoured as stored.** Every group offers all three states
 * ([statesFor]), and a stored value is the policy — there is no second opinion on read or write.
 * Allow on a consequential action is a real permission: the protection is the warning on the row
 * and the default being Off everywhere, not a hidden veto. An Ask is answered by the same card for
 * every action, in [PhoneEngine]'s one handshake.
 *
 * ## What the tier is for, now
 *
 * The tier no longer decides which states the owner may choose. It is the **description** the row
 * and the card use: what the consequence is, and whether an undo is offered. Instant actions are
 * reversible and register an undo; consequential ones do not. The tier still groups the rows in the
 * sheet, so a reader can see which actions are cheap to reverse and which are not.
 *
 * A group's `actions` are the ones the phone implements **today** and may advertise; `planned` names
 * the actions the group will hold when its capability lands, so a direct call today gets a governance
 * refusal that names the group instead of "unknown action" — and is never advertised, because a
 * promise in an `enum` is a capability the phone must be able to keep.
 *
 * Pure JVM, so [PhoneGrantsTest] proves the defaults and the uniform states without a device. The
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
     * agent do, the warnings where the consequence is not obvious, and its action names.
     *
     * [warning] is the standing line under the row — what this capability costs whoever chooses it.
     * [allowWarning] is the louder line shown only while the row is **Allow**, because that is the
     * state in which the consequence actually happens without a card. Neither is a veto: the owner's
     * choice is honoured, and the row says plainly what they chose.
     */
    data class Group(
        val id: String,
        val label: String,
        val tier: Int,
        /** One line, in the owner's words: what this lets the agent do. */
        val lets: String,
        /** Where the consequence is not obvious, the sentence that says it plainly. */
        val warning: String? = null,
        /** What Allow means for this group, shown only at Allow. Loud where it matters. */
        val allowWarning: String? = null,
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
            allowWarning = "Allow: Vox can read your clipboard without asking, including a " +
                "password or a code. It can also write to it without asking.",
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
            allowWarning = "Allow: Vox can read a file in the folder you granted, and offer it to " +
                "your agent, without asking. Reading inside that folder is no longer a decision.",
            actions = listOf("file_list", "file_read", "file_offer"),
        ),
        Group(
            id = "calendar",
            label = "Calendar",
            tier = PhoneTiers.REPORTED,
            lets = "Read today's events and create an event, which it can undo.",
            allowWarning = "Allow: Vox can read your calendar and create an event without asking. " +
                "An event it creates carries an undo.",
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
            allowWarning = "Allow: Vox can read the notifications on this phone without asking — " +
                "codes, messages and anything else on your lock screen.",
            actions = listOf("notifications", "notification_read"),
        ),
        Group(
            id = "messages",
            label = "Messages",
            tier = PhoneTiers.INSTANT,
            lets = "Write a text and hand it to your messaging app. You send it.",
            allowWarning = "Allow: Vox can write a text and open your messaging app without " +
                "asking you first. You still press send.",
            actions = listOf("message"),
        ),
        Group(
            id = "calls",
            label = "Calls",
            tier = PhoneTiers.INSTANT,
            lets = "Open the dialer with a number ready. You make the call.",
            allowWarning = "Allow: Vox can open the dialer with a number ready without asking " +
                "you first. You still press call.",
            actions = listOf("dial"),
        ),
        Group(
            id = "apps",
            label = "Apps",
            tier = PhoneTiers.INSTANT,
            lets = "Open an app or a link, or remove an app, which Android then confirms.",
            warning = "This group includes removing an app: consequential, and there is no undo.",
            allowWarning = "Allow: Vox can remove an app without asking you first. Android shows " +
                "its own confirmation, and a removed app cannot be put back by an undo.",
            actions = listOf("launch", "open", "uninstall"),
            planned = listOf("install"),
        ),
        Group(
            id = "contacts",
            label = "Contacts",
            tier = PhoneTiers.INSTANT,
            lets = "Look up a contact's number by name.",
            allowWarning = "Allow: Vox can read any contact's name and numbers without asking.",
            actions = listOf("contact"),
        ),
    )

    /** The state every group starts in. There is no other default. */
    val DEFAULT: State = State.OFF

    /** A read of the owner's policy. Implementations return the stored state, honoured as stored. */
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
        // are, while `uninstall` is consequential: a group grant covers removal now, but the card
        // and the warning still describe it as the one-way action it is.
        return PhoneTiers.of(a) ?: group.tier
    }

    /**
     * The states the owner may choose for [tier] — all three, for every tier. The word means the
     * same thing whatever the group: Off refuses, Ask raises a card, Allow runs. The tier is a
     * description of the consequence, never a reason to withhold a choice.
     */
    fun statesFor(tier: Int): List<State> = listOf(State.OFF, State.ASK, State.ALLOW)

    /** The live state of [group]'s grant, exactly as the owner stored it. */
    fun stateOf(source: Source, group: Group): State = source.state(group)

    /** The live state of [action]'s group, or null when it belongs to none. */
    fun stateOf(source: Source, action: String): State? = groupOf(action)?.let { stateOf(source, it) }

    /**
     * The actions the model may be offered right now: every implemented action whose group is not
     * Off, plus `undo` and `status`.
     *
     * An entry in this list is a promise the phone must be able to keep, and with one handshake for
     * every action ([PhoneEngine]) it can: at **Ask** the action is offered and a card answers it.
     *
     * `undo` and `status` belong to no group on purpose. `undo` only reverses what the agent itself
     * did (H4), and `status` only reports the mind's own view; neither acts on the phone, and a
     * phone whose grants are all Off must still be able to say so.
     */
    fun advertised(source: Source): List<String> {
        val out = ArrayList<String>()
        for (g in GROUPS) {
            if (stateOf(source, g) == State.OFF) continue
            out.addAll(g.actions)
        }
        out.addAll(ALWAYS)
        return out
    }

    /** The two actions that are not a capability: reversing one, and asking what is on. */
    val ALWAYS: List<String> = listOf("undo", "status")

    /**
     * "Allow all instant actions" — the one bulk shortcut the design permits. It touches Tier 0
     * only and never [BULK_EXEMPT]: one tap is not the place to grant the groups that read other
     * people's words, or the apps group whose Allow is also the uninstall permission. Not "Allow
     * all", and not "Allow all of the private ones" either.
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
     * words into the agent's conversation, and apps carries the uninstall permission. Each one
     * stays a decision of its own even if a shortcut is ever wired to the screen.
     */
    val BULK_EXEMPT: Set<String> = setOf("notifications", "contacts", "apps")

    /** The groups grouped by tier, in display order. */
    fun byTier(): List<Pair<Int, List<Group>>> =
        listOf(PhoneTiers.INSTANT, PhoneTiers.REPORTED, PhoneTiers.APPROVAL)
            .map { tier -> tier to GROUPS.filter { it.tier == tier } }

    /** The word the screen uses for a tier. It describes the consequence, not a permission. */
    fun tierLabel(tier: Int): String = when (tier) {
        PhoneTiers.INSTANT -> "Instant"
        PhoneTiers.REPORTED -> "Reported"
        else -> "Consequential"
    }

    /**
     * The one line that explains a tier above its rows. The tier says what a card will say and
     * whether an undo exists — it no longer decides which of the three states the owner may pick.
     */
    fun tierLine(tier: Int): String = when (tier) {
        PhoneTiers.INSTANT ->
            "Reversible and private. A card names the action and what it changes; an instant " +
                "action carries an undo, and its use is recorded in Recent actions."
        PhoneTiers.REPORTED ->
            "Changes something you can see. A card names the action and its arguments; the " +
                "result is recorded in Recent actions, and an undo is offered where the action " +
                "can be reversed."
        else ->
            "Consequential or private to read, with no undo. A card names the action and its " +
                "arguments, so an Allow here is a standing permission: there is no card and no " +
                "way back."
    }

    /** A short, non-secret summary for a log line: `timers-alarms=allow,dnd-volume=off`. */
    fun summary(source: Source): String =
        GROUPS.joinToString(",") { "${it.id}=${stateOf(source, it).id}" }
}
