package com.hermesvox

import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * PhoneToolsSheet — the owner's side of the phone-as-MCP-server (#421 steps 3–5).
 *
 * **The setup path is a turn, not a pasted block.** **Set up with my agent** composes the prompt in
 * [PhoneSetup], creates the credential if there is not one, and sends that prompt as an ordinary
 * stored turn through the app's session. The turn and the agent's reply are shown here, so a change
 * to the agent's own configuration is legible.
 *
 * **The turn is sent from the connection in force.** The session is resolved at the press
 * ([PhoneSetupStore.session]), from the active profile's url, key and scope, and rebuilt when the
 * one the app holds was built for a different connection (#430). Holding a client object in this
 * sheet would re-create that bug: a session keeps the address it was built with.
 *
 * **A failure says what happened.** [PhoneToolsView.setupHeadline] shows the record's own line — no
 * connection configured, key rejected, the agent did not answer, or the turn failed with the
 * gateway's reason — so a local misconfiguration never reads as a network outage.
 *
 * **The three setup states lead the sheet, in their own words.** *Not set up* — [PhoneToolsView
 * .NOT_SET_UP_HEADING], with the prompt and its copy action beside it and the restart named after
 * it — is drawn first, because enabling the set starts this phone's listener and mints its
 * credential but cannot add the entry to the agent's configuration on the host. *Asked, waiting for
 * your agent* follows a handover with no handshake yet. *Connected* is earned by the handshake and
 * is the only state that may say the word. None of the three shares another's wording.
 *
 * **Connected is earned by the handshake.** The headline stays *asked — waiting for your agent*
 * until the phone's own [PhoneMcpServer] has seen an authenticated MCP `initialize`; only then is
 * the state [PhoneSetup.State.CONNECTED]. The poll reads that handshake on its next tick, so a
 * gateway that restarts and connects clears the waiting state with no re-entry from the owner. The
 * agent's reply is displayed beneath every state, because when it fails, its own words are the next
 * clue.
 *
 * **Failure states each name a next step**, including a retry and a token rotation. The manual
 * config block still exists — behind a disclosure, labelled fallback, never the default path — for
 * an owner whose agent cannot edit its own configuration.
 *
 * **Permissions are shown, not assumed.** Do Not Disturb and notification access are special
 * accesses, so the sheet shows whether each is granted and opens the right system screen; an action
 * that needs one still refuses honestly in [PhoneEngine] — Do Not Disturb with `reason:"permission"`,
 * `now_playing` with `known:false` and the platform's own limit.
 *
 * **The sheet is one column, and it scrolls to its end.** Set up, the capability groups, Recent
 * actions and This phone are all in it; the column is exactly as tall as what it holds and the
 * viewport is what the header leaves, so nothing below the fold is ever clipped without a scroll
 * range (#451).
 *
 * **A tick redraws only what changed.** The poll is there so the clock can move waiting → timed out
 * and the "last request" line can age — not so the sheet can be rebuilt every second. Rebuilding it
 * put the scroll back at the top and dropped a drag in flight, which is what made the sheet read as
 * unscrollable on a device. [Drawn] is everything the sheet shows, and [repaints] is the rule.
 */
class PhoneToolsSheet(
    private val act: AppCompatActivity,
    private val firstRun: Boolean = false,
    private val onDismiss: () -> Unit = {},
) {
    private var dialog: Dialog? = null
    private var open = false
    private var notice: String? = null
    private var poll: Runnable? = null
    private val main = Handler(Looper.getMainLooper())

    /** The column the sheet scrolls, so a repaint can put the owner back where they were. */
    private var scroll: ScrollView? = null

    /** What the sheet is drawing right now; a tick that would draw the same thing does nothing. */
    private var shown: Drawn? = null

    private fun dp(v: Int) = (v * act.resources.displayMetrics.density).toInt()
    private fun color(id: Int) = ContextCompat.getColor(act, id)

    fun show() {
        open = true
        current = this
        paint()
        schedulePoll()
    }

    /** Re-read the live state (a call started, DND access came back from system settings). */
    fun refresh() {
        if (open) paint()
    }

    /**
     * The column the sheet scrolls. Read by [PhoneToolsSheetLayoutTest], which measures the sheet on
     * a device: that its content is taller than the viewport, that the last section is reachable,
     * and that a repaint leaves the owner where they were.
     */
    internal val scroller: ScrollView? get() = scroll

    fun close() {
        dialog?.dismiss()
    }

    private fun paint() {
        if (!open) return
        val d = dialog ?: VoxSheet.dialog(act).also { created ->
            dialog = created
            created.setOnDismissListener {
                open = false
                dialog = null
                scroll = null
                shown = null
                poll?.let { main.removeCallbacks(it) }
                poll = null
                if (current === this) current = null
                onDismiss()
            }
        }
        // A repaint rebuilds the column, so the offset is carried across it: the owner's place in
        // a long sheet is theirs, and a state that lands while they are reading must not yank them
        // back to the top (#451).
        val at = if (d.isShowing) scroll?.scrollY ?: 0 else 0
        val next = snapshot(System.currentTimeMillis())
        d.setContentView(build(next))
        shown = next
        if (at > 0) scroll?.post { scroll?.scrollTo(0, at) }
        d.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        if (d.isShowing) return
        d.show()
    }

    /**
     * What the sheet draws, as data — the whole of it, read once.
     *
     * [PhoneMcp.Status] and [PhoneSetup.Record] are data classes, and the two lines that age are
     * rendered here, so two reads a second apart are equal unless something the owner can see has
     * actually changed. That is what makes the poll safe: see [repaints].
     */
    internal data class Drawn(
        val status: PhoneMcp.Status,
        val state: PhoneSetup.State,
        val record: PhoneSetup.Record,
        val lastRequest: String,
        val handshake: String,
    )

    private fun snapshot(now: Long): Drawn =
        drawn(PhoneMcp.status(act), PhoneSetupStore.read(act), PhoneMcp.tokenTag(act), now)

    private fun schedulePoll() {
        if (poll != null) return
        val tick = object : Runnable {
            override fun run() {
                if (!open) return
                // #451: a tick repaints only when what the sheet would draw has changed.
                // This used to repaint on the clock — every second for as long as a setup was
                // WAITING or TIMED_OUT — and each repaint replaced the content view: the scroll
                // jumped to the top and a drag in flight was dropped, so nothing below the fold
                // could be reached at all.
                if (repaints(shown, snapshot(System.currentTimeMillis()))) paint()
                main.postDelayed(this, 1_000L)
            }
        }
        poll = tick
        main.postDelayed(tick, 1_000L)
    }

    private fun build(drawn: Drawn): View {
        val root = VoxSheet.root(act)
        VoxSheet.header(root, "PHONE TOOLS", "Close phone tools", onClose = { dialog?.dismiss() })

        // #451: the scroll takes the height the header leaves, and the column is exactly as tall as
        // what it holds. That pair is the scroll range: a weighted viewport over a wrapping column.
        // `isFillViewport` stretched a short column to the viewport, which makes the range and the
        // content disagree — so it is not set, and the column here is never short. Focusable, so a
        // keyboard or a switch device can rest on the column and arrow through it; the platform's
        // own initial focus (the close mark) is left alone.
        val scroll = ScrollView(act).apply { isFocusable = true }
        this.scroll = scroll
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(col, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val status = drawn.status

        // #421: the sequence the owner is actually in, first. Turning the set on starts this
        // phone's listener and mints its credential; it does not tell the agent the phone exists,
        // and Hermes reads MCP configuration at startup. So the setup state leads the sheet —
        // not below a list of grants the agent cannot use yet.
        if (status.allowed) setupSection(col, drawn, hostOrPlaceholder(status))

        body(
            col,
            if (firstRun)
                "Choose what your agent may do on this phone. Every capability group starts Off, " +
                    "and an Off group is absent from its tool list — it cannot even try."
            else
                "Choose what your agent may do on this phone. Only the groups you grant appear in " +
                    "its tool list; turning one off takes effect on the next call.",
        )
        body(
            col,
            "The listener is open only while a call is. Hanging up closes it; there is no " +
                "listening socket the rest of the time.",
            dim = true,
        )

        // #429 step 1: the same registry the schema is built from. The screen cannot promise a
        // capability the tool does not offer, because both read PhoneGrants.
        val states = PhoneGrantStore.states(act)
        col.addView(VoxSheet.subhead(act, "Capabilities"))
        body(
            col,
            "Off — the agent cannot use it. Ask — the agent asks you first, by tap or by voice. " +
                "Allow — the agent acts without asking. The three words mean the same thing for " +
                "every group, and every group starts Off. " +
                "${PhoneToolsView.grantSummary(states)}.",
        )
        for ((tier, groups) in PhoneGrants.byTier()) {
            col.addView(VoxSheet.subhead(act, PhoneGrants.tierLabel(tier)))
            body(col, PhoneGrants.tierLine(tier), dim = true)
            for (g in groups) grantRow(col, g, states[g.id] ?: PhoneGrants.DEFAULT)
        }

        // #429 step 1: what the agent actually did, refusals included. This is the record the
        // owner checks the agent's claims against.
        col.addView(VoxSheet.subhead(act, "Recent actions"))
        val audit = PhoneAuditStore.entries(act)
        if (audit.isEmpty()) {
            body(
                col,
                "Nothing yet. Every call your agent makes — including a refusal — appears here " +
                    "with its time, arguments and outcome.",
                dim = true,
            )
        } else {
            body(
                col,
                "Newest first. Arguments are summarised, never copied: a note's text is shown as " +
                    "its length. Tap an action that carries an undo to reverse it.",
                dim = true,
            )
            for (entry in audit.asReversed().take(AUDIT_ROWS)) auditRow(col, entry)
        }

        col.addView(VoxSheet.subhead(act, "This phone"))
        row(col, "Reachability", PhoneToolsView.reachability(status.tailnet, status.allowed))
        row(col, "Server", PhoneToolsView.server(status.tailnet, status.listening, status.url, status.allowed))
        row(col, "Do Not Disturb access", PhoneToolsView.dnd(status.dndGranted))
        if (!status.dndGranted && status.allowed) {
            action(
                col,
                "OPEN DND ACCESS",
                primary = false,
                description = "Open the system screen where you grant Do Not Disturb access",
            ) {
                openDndSettings()
            }
        }
        row(col, "Now playing access", PhoneToolsView.nowPlaying(status.mediaListenerGranted))
        if (states["media"] != null && states["media"] != PhoneGrants.State.OFF &&
            !status.mediaListenerGranted
        ) {
            action(
                col,
                "LET VOX SEE WHAT IS PLAYING",
                primary = false,
                description = "Open the system screen where you grant notification access",
            ) {
                openListenerSettings()
            }
        }
        row(col, "Notification access", PhoneToolsView.notifications(status.notificationListenerGranted))
        if (states["notifications"] != null && states["notifications"] != PhoneGrants.State.OFF &&
            !status.notificationListenerGranted
        ) {
            action(
                col,
                "OPEN NOTIFICATION ACCESS",
                primary = false,
                description = "Open the system screen where you grant notification-listener access",
            ) {
                openListenerSettings()
            }
        }
        row(col, "Contacts permission", PhoneToolsView.contacts(status.contactsGranted, status.contactsMissing))
        if (!status.contactsGranted) {
            action(
                col,
                "GRANT CONTACTS ACCESS",
                primary = false,
                description = "Ask Android for read access to your contacts",
            ) {
                requestContacts()
            }
        }
        row(col, "Volume permission", PhoneToolsView.volume(status.volumeGranted))
        row(col, "Files folder", PhoneToolsView.files(status.folders))
        if (status.folders.isEmpty()) {
            action(
                col,
                "PICK A FOLDER",
                primary = false,
                description = "Open the system folder picker and grant your agent one folder",
            ) {
                pickFolder()
            }
        } else {
            action(
                col,
                "PICK ANOTHER FOLDER",
                primary = false,
                description = "Open the system folder picker and grant your agent another folder",
            ) {
                pickFolder()
            }
        }
        row(col, "Calendar permission", PhoneToolsView.calendar(status.calendarGranted, status.calendarMissing))
        if (!status.calendarGranted) {
            action(
                col,
                "GRANT CALENDAR ACCESS",
                primary = false,
                description = "Ask Android for read and write access to the calendar",
            ) {
                requestCalendar()
            }
        }
        row(col, "Last request", drawn.lastRequest)
        row(col, "Token", PhoneToolsView.token(status.token, status.allowed))

        if (!status.allowed) {
            body(
                col,
                "Phone tools is off, so there is no token, no listener and no config to copy. " +
                    "Select Phone tools in Alpha features at the top of Settings.",
                dim = true,
            )
            return root
        }

        col.addView(VoxSheet.subhead(act, "Manual fallback"))
        body(
            col,
            "Only for an agent that cannot edit its own configuration. The setup turn above is the " +
                "normal path; this block is the fallback.",
            dim = true,
        )
        disclosure(
            col,
            if (manualOpen) "HIDE THE MANUAL CONFIG BLOCK" else "SHOW THE MANUAL CONFIG BLOCK",
            "Show the fallback config block and its copy button",
        ) {
            manualOpen = !manualOpen
            if (manualOpen) PhoneMcp.token(act)
            paint()
        }
        if (manualOpen) {
            val host = hostOrPlaceholder(status)
            val token = PhoneMcp.token(act)
            if (status.tailnet.isNullOrBlank()) {
                body(
                    col,
                    "Tailscale is not connected, so the address below is a placeholder. Connect it " +
                        "and open this sheet again for the real one.",
                    dim = true,
                )
            }
            val block = PhoneMcpConfig.snippet(host, token)
            body(col, "Add this block to the gateway's ~/.hermes/config.yaml:", dim = true)
            configView(col, block)
            action(col, "COPY CONFIG", primary = false, description = "Copy the fallback gateway config") {
                copy(block)
            }
            action(col, "ROTATE TOKEN", primary = false, description = "Replace the token and invalidate the old one") {
                rotateToken()
            }
        }

        body(
            col,
            "The token is never logged. Rotating it is the only way to revoke the old one; the " +
                "gateway keeps failing authentication until its config is updated. The listener " +
                "needs no foreground service: it lives exactly as long as the call that opened it.",
            dim = true,
        )

        return root
    }

    private var manualOpen = false

    /**
     * The setup state, drawn first: what stage the handover is at, and — while no handshake has been
     * observed — the prompt itself, its copy action, and the sentence that names the restart.
     *
     * **Why it leads the sheet.** Enabling Phone tools starts this phone's listener and mints its
     * credential. It does not tell the agent that the phone exists: that is one entry in the agent's
     * own configuration on the host, and Hermes reads that configuration at startup. An owner who
     * enabled the set and asked his agent about the phone got an honest "I have no such ability" —
     * every part of that was correct, and the sheet never showed him the step between. So the
     * state comes before the capability list it depends on.
     *
     * **Why the prompt is not a footnote.** It is the thing to hand over. It is shown in full, with
     * **Copy setup prompt**, because the agent cannot know about the phone until someone gives it
     * this entry; the sentence after it says the entry takes effect when the gateway restarts.
     *
     * **Why the restart is in the sentence.** Hermes reads MCP configuration at startup. The old
     * sheet let an owner press *Set up*, read the agent's "done", and then ask it about the phone
     * before the gateway had come back — a sequence with a step that only the config loader knew.
     */
    private fun setupSection(col: LinearLayout, drawn: Drawn, host: String) {
        val status = drawn.status
        val record = drawn.record
        val state = drawn.state

        // The turn the app sent, when this process sent one; otherwise the turn it would send from
        // the address in force. [PhoneSetup.prompt] is a contract with the agent, so it is passed
        // through untouched — the sheet may not improve its copy.
        val sent = PhoneSetupStore.lastPrompt()
        val block = PhoneToolsView.setupBlock(
            state = state,
            prompt = sent ?: PhoneSetupStore.prompt(host),
            failure = record.failure,
        )

        col.addView(VoxSheet.subhead(act, "Set up with your agent"))
        heading(
            col,
            block.heading,
            when (state) {
                PhoneSetup.State.CONNECTED -> R.color.hv_ok
                PhoneSetup.State.UNREACHABLE, PhoneSetup.State.REFUSED, PhoneSetup.State.TIMED_OUT ->
                    R.color.hv_danger
                else -> R.color.hv_warn
            },
        )
        body(col, block.meaning, dim = state == PhoneSetup.State.CONNECTED)
        if (state == PhoneSetup.State.CONNECTED) body(col, drawn.handshake, dim = true)

        // The short path first, so the action is on screen where the state is; the prompt below it
        // is the same turn, for an owner who would rather hand it over himself.
        stateActions(col, state, status.allowed)
        notice?.let { body(col, it) }

        // The agent's own words sit right under the state: when the turn failed or it refused,
        // they are the next clue, not something to scroll past a three-kilobyte prompt to find.
        val reply = record.reply
        if (!reply.isNullOrBlank()) {
            col.addView(VoxSheet.subhead(act, "Your agent says"))
            replyView(col, reply)
        }

        block.prompt?.let { text ->
            if (status.tailnet.isNullOrBlank()) {
                body(
                    col,
                    "Tailscale is not connected, so this turn carries a placeholder address. Connect " +
                        "it and open this sheet again for the real one.",
                    dim = true,
                )
            }
            body(
                col,
                if (sent != null)
                    "This is the turn the app sent. It names the address and the exact config shape " +
                        "to write, and it carries no credential: your agent authenticates with the " +
                        "key it already holds for this host."
                else
                    "This is the turn to hand over. It is generated from the current address, it " +
                        "names the exact config shape to write, and it carries no credential: your " +
                        "agent authenticates with the key it already holds for this host.",
                dim = true,
            )
            configView(col, text)
            action(
                col,
                "COPY SETUP PROMPT",
                primary = false,
                description = "Copy the setup turn to hand to your agent",
            ) {
                copy(text, "setup prompt copied")
            }
            // The restart is the step after the handover, said where the prompt is, not in a
            // footnote below the fold.
            block.restart?.let { body(col, it) }
        }

        if (state == PhoneSetup.State.CONNECTED && status.allowed) {
            // Still useful once connected: the turn updates the entry in place.
            action(col, "ASK AGAIN", primary = false, description = "Send the setup turn again; it will not add a second entry") { setUpAgent() }
        }
    }

    /** The one action each state offers. Connected's *Ask again* stays last, after the proof. */
    private fun stateActions(col: LinearLayout, state: PhoneSetup.State, allowed: Boolean) {
        if (!allowed) return
        when (state) {
            PhoneSetup.State.IDLE ->
                action(col, "SET UP WITH MY AGENT", primary = true, description = "Ask your agent to add this phone") { setUpAgent() }
            PhoneSetup.State.WAITING ->
                action(col, "ASK AGAIN", primary = false, description = "Send the setup turn again") { setUpAgent() }
            PhoneSetup.State.UNREACHABLE ->
                action(col, "TRY AGAIN", primary = true, description = "Ask your agent again") { setUpAgent() }
            PhoneSetup.State.REFUSED ->
                action(col, "TRY AGAIN", primary = true, description = "Ask your agent again after fixing its file access") { setUpAgent() }
            PhoneSetup.State.TIMED_OUT -> {
                action(col, "TRY AGAIN", primary = true, description = "Send the setup turn again") { setUpAgent() }
                action(col, "ROTATE TOKEN", primary = false, description = "Replace the token and ask with the new one") { rotateToken() }
            }
            PhoneSetup.State.CONNECTED -> Unit
        }
    }

    /**
     * The state's own heading — the one line the sheet is loudest about, because it is the step the
     * owner is on. A heading a screen reader can jump to, in the colour of the state's weight.
     */
    private fun heading(col: LinearLayout, text: String, colorId: Int) {
        col.addView(
            TextView(act, null, 0, R.style.HvRowTitle).apply {
                this.text = text
                textSize = 20f
                setTextColor(color(colorId))
                setPadding(0, dp(10), 0, dp(2))
                androidx.core.view.ViewCompat.setAccessibilityHeading(this, true)
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
    }

    /**
     * One capability group: its name, what it lets the agent do, the warning where the consequence
     * is not obvious, and the three states. Every tier offers all three; the tier only tells the
     * reader what a card will say and whether an undo exists. The loud warning appears only at
     * Allow, because that is the state in which the consequence happens without a card.
     */
    private fun grantRow(col: LinearLayout, group: PhoneGrants.Group, state: PhoneGrants.State) {
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, dp(8))
            contentDescription = "${group.label}: ${state.label}. ${group.lets}"
        }
        box.addView(TextView(act, null, 0, R.style.HvRowTitle).apply { text = group.label })
        box.addView(TextView(act, null, 0, R.style.HvRowValue).apply { text = group.lets })
        box.addView(TextView(act, null, 0, R.style.HvRowValue).apply {
            text = PhoneToolsView.grantMeaning(state)
            setTextColor(color(R.color.hv_text_dim))
        })
        if (!group.built) {
            box.addView(TextView(act, null, 0, R.style.HvRowValue).apply {
                text = "Nothing on this phone yet — your choice is kept for when the capability lands."
                setTextColor(color(R.color.hv_text_dim))
            })
        }
        group.warning?.let { warning ->
            box.addView(TextView(act, null, 0, R.style.HvRowValue).apply {
                text = warning
                setTextColor(color(R.color.hv_warn))
            })
        }
        if (state == PhoneGrants.State.ALLOW) {
            group.allowWarning?.let { warning ->
                box.addView(TextView(act, null, 0, R.style.HvRowValue).apply {
                    text = warning
                    setTextColor(color(R.color.hv_warn))
                })
            }
        }
        val controls = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        for (choice in PhoneGrants.statesFor(group.tier)) {
            controls.addView(stateButton(group, choice, choice == state))
        }
        box.addView(controls)
        col.addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun stateButton(group: PhoneGrants.Group, state: PhoneGrants.State, selected: Boolean): Button =
        Button(act, null, 0, R.style.HvGhostButton).apply {
            text = state.label
            contentDescription = "${group.label}: ${state.label}" + if (selected) " (selected)" else ""
            setTextColor(color(if (selected) R.color.hv_bg else R.color.hv_text))
            background = ContextCompat.getDrawable(
                act,
                if (selected) R.drawable.bg_button_primary else R.drawable.bg_button_ghost,
            )
            minWidth = dp(72)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener {
                if (!selected) {
                    PhoneGrantStore.set(act, group, state)
                    // A group turned on mid-call starts collecting the notifications already on
                    // the phone; outside a call this is a no-op because the board is closed.
                    if (group.id == "notifications" && state != PhoneGrants.State.OFF) {
                        PhoneNotifications.requestResync()
                    }
                    // Turning a group on is the moment its platform access is asked for: the
                    // calendar's runtime permissions, and the folder picker for files. Off does
                    // nothing, and a grant already held is not asked for again.
                    when (group.id) {
                        "calendar" -> if (state != PhoneGrants.State.OFF) requestCalendar()
                        "contacts" -> if (state != PhoneGrants.State.OFF) requestContacts()
                        // The notifications group uses the same system listener as Media. Turning
                        // it on is the moment the owner is offered Android's access screen.
                        "notifications" -> if (
                            state != PhoneGrants.State.OFF && !MediaListen.granted(act)
                        ) openListenerSettings()
                        "files" -> if (
                            state != PhoneGrants.State.OFF && PhoneFileAccess.trees(act).isEmpty()
                        ) pickFolder()
                        // The media group's one special access: without it now_playing can only
                        // say whether audio is playing, so turning the group on is the moment the
                        // system screen is offered — the same shape as Calendar and Files.
                        "media" -> if (
                            state != PhoneGrants.State.OFF && !MediaListen.granted(act)
                        ) openListenerSettings()
                    }
                    paint()
                }
            }
        }

    /** One audit row. An undoable row is the tap target; a refusal is a row like any other. */
    private fun auditRow(col: LinearLayout, entry: PhoneAudit.Entry) {
        val undoable = entry.undoId != null
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setBackgroundColor(color(R.color.hv_surface))
            if (undoable) {
                isClickable = true
                isFocusable = true
                contentDescription = "${PhoneToolsView.auditHeadline(entry)}, " +
                    "${PhoneToolsView.outcomeLine(entry)}. Double tap to undo."
                setOnClickListener { undoEntry(entry) }
            }
        }
        box.addView(TextView(act, null, 0, R.style.HvRowTitle).apply {
            text = PhoneToolsView.auditHeadline(entry)
        })
        box.addView(TextView(act, null, 0, R.style.HvRowValue).apply {
            text = PhoneToolsView.argumentsLine(entry.arguments)
        })
        val outcome = TextView(act, null, 0, R.style.HvRowValue).apply {
            text = buildString {
                append(PhoneToolsView.outcomeLine(entry))
                if (undoable) append(" · tap to undo")
            }
            setTextColor(
                color(
                    when {
                        entry.ok -> R.color.hv_ok
                        entry.outcome == PhoneReason.NOT_GRANTED || entry.outcome == PhoneReason.APPROVAL ->
                            R.color.hv_warn
                        else -> R.color.hv_danger
                    }
                )
            )
        }
        box.addView(outcome)
        col.addView(
            box,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(6) },
        )
    }

    /** The owner's Undo tap. The result is the same shape the agent gets, so the toast is true. */
    private fun undoEntry(entry: PhoneAudit.Entry) {
        val id = entry.undoId ?: return
        val result = PhoneAuditStore.undo(act, id)
        val line = if (result.ok) {
            "Undone · ${result.facts["undone"] ?: entry.action}"
        } else {
            "Could not undo · ${result.reason ?: "no undo"}"
        }
        Toast.makeText(act, line, Toast.LENGTH_SHORT).show()
        paint()
    }

    private fun setUpAgent() {
        if (!AlphaGate.allows(act, AlphaGate.FeatureSet.PHONE_TOOLS)) {
            notice = "Phone tools is off. Select it in Alpha features before asking your agent."
            paint()
            return
        }
        val status = PhoneMcp.status(act)
        val host = hostOrPlaceholder(status)
        if (status.tailnet.isNullOrBlank()) {
            notice = "Connect Tailscale first: the setup turn needs this phone's real tailnet " +
                "address, and without one there is nothing for the agent to reach."
            paint()
            return
        }
        // #430: resolved at the moment of the press from the connection in force — never a client
        // object captured when this sheet was built. A stale object sends the turn to the address
        // the owner just replaced, while the connection test (built from the same prefs) passes.
        val s = PhoneSetupStore.session(act)
        if (s == null) {
            // Nothing configured to send to. Said plainly, and recorded so the state names it too.
            notice = null
            PhoneSetupStore.failedBeforeSend(act, PhoneSetupSend.message(PhoneSetupSend.Failure.NO_CONNECTION))
            paint()
            return
        }
        notice = null
        val token = PhoneMcp.token(act)
        PhoneSetupStore.ask(act, s, host, token) {
            act.runOnUiThread { if (open) paint() }
        }
        paint()
    }

    private fun rotateToken() {
        if (!AlphaGate.allows(act, AlphaGate.FeatureSet.PHONE_TOOLS)) {
            notice = "Phone tools is off, so there is no token to rotate. Select it in Alpha features."
            paint()
            return
        }
        PhoneSetupStore.rotate(act)
        notice = "New token generated. The old token no longer works and the old connection proof " +
            "is gone. Ask your agent again so it gets the new token."
        paint()
    }

    private fun openDndSettings() {
        try {
            act.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
        } catch (_: Throwable) {
            Toast.makeText(act, "Android does not expose that screen on this device", Toast.LENGTH_LONG).show()
        }
    }

    /** The notification-access screen, where the owner grants what `now_playing` needs. */
    private fun openListenerSettings() {
        try {
            act.startActivity(MediaListen.settingsIntent())
        } catch (_: Throwable) {
            Toast.makeText(act, "Android does not expose that screen on this device", Toast.LENGTH_LONG).show()
        }
    }

    /** The system folder picker. The grant is persistable, so it outlives this sheet. */
    private fun pickFolder() {
        // Android 11+ will not grant the Download folder itself: the picker opens there and shows
        // "Can't use this folder" if the owner taps it. So the ask is a subfolder, said plainly
        // before the picker opens rather than discovered as a refusal inside it.
        notice = "Pick a folder inside Download — for example Download/Work. Android will not grant " +
            "Download itself, so a subfolder is what your agent gets."
        paint()
        try {
            act.startActivityForResult(PhoneFileAccess.intent(), REQ_FOLDER)
        } catch (_: Throwable) {
            notice = "Android does not expose a folder picker on this device, so no folder can be granted."
            paint()
        }
    }

    /** Ask Android for the calendar's two runtime permissions; the system dialog decides. */
    private fun requestCalendar() {
        if (PhoneCalendarAccess.permission(act) == null) return
        try {
            ActivityCompat.requestPermissions(act, PhoneCalendarAccess.PERMISSIONS, REQ_CALENDAR)
        } catch (_: Throwable) {
            notice = "Android would not open the permission dialog; grant Calendar in system settings."
            paint()
        }
    }

    /** Ask Android for contacts read access; the system dialog decides. */
    private fun requestContacts() {
        if (ContactsAccess.permission(act) == null) return
        try {
            ActivityCompat.requestPermissions(act, arrayOf(ContactsAccess.PERMISSION), REQ_CONTACTS)
        } catch (_: Throwable) {
            notice = "Android would not open the permission dialog; grant Contacts in system settings."
            paint()
        }
    }

    private fun hostOrPlaceholder(status: PhoneMcp.Status): String =
        status.tailnet?.takeIf { it.isNotBlank() }
            ?: status.url?.substringAfter("http://")?.substringBefore(':').orEmpty()
                .takeIf { it.isNotBlank() }
            ?: "<tailnet-ip>"

    private fun body(col: LinearLayout, text: String, dim: Boolean = false) {
        col.addView(TextView(act, null, 0, R.style.HvRowValue).apply {
            this.text = text
            if (!dim) setTextColor(color(R.color.hv_text))
            setPadding(0, dp(8), 0, dp(4))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun row(col: LinearLayout, title: String, value: String) {
        val row = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        row.addView(TextView(act, null, 0, R.style.HvRowTitle).apply { text = title })
        row.addView(TextView(act, null, 0, R.style.HvRowValue).apply {
            text = value
            contentDescription = "$title: $value"
        })
        col.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun replyView(col: LinearLayout, text: String) {
        col.addView(TextView(act, null, 0, R.style.HvRowValue).apply {
            this.text = text
            setTextIsSelectable(true)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(color(R.color.hv_surface))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(6) })
    }

    private fun configView(col: LinearLayout, block: String) {
        col.addView(TextView(act, null, 0, R.style.TextVox_Console).apply {
            text = block
            setTextIsSelectable(true)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(color(R.color.hv_surface))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(6) })
    }

    private fun disclosure(col: LinearLayout, text: String, description: String, onTap: () -> Unit) {
        action(col, text, primary = false, description = description, onTap = onTap)
    }

    private fun action(col: LinearLayout, text: String, primary: Boolean, description: String, onTap: () -> Unit) {
        val row = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        row.addView(button(text, primary, description, onTap))
        col.addView(row)
    }

    private fun button(text: String, primary: Boolean, description: String, onTap: () -> Unit): Button =
        Button(act, null, 0, R.style.HvGhostButton).apply {
            this.text = text
            contentDescription = description
            setTextColor(color(if (primary) R.color.hv_bg else R.color.hv_text))
            background = ContextCompat.getDrawable(act, if (primary) R.drawable.bg_button_primary else R.drawable.bg_button_ghost)
            setPadding(dp(18), dp(10), dp(18), dp(10))
            setOnClickListener { onTap() }
        }

    /** Copy [text] and say what was copied, so the toast is true for both blocks. */
    private fun copy(text: String, toast: String = "config copied") {
        val cm = act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("vox phone config", text))
        Toast.makeText(act, toast, Toast.LENGTH_SHORT).show()
    }

    companion object {
        /** How many Recent actions rows the sheet renders. The store keeps [PhoneAudit.MAX]. */
        private const val AUDIT_ROWS = 20

        /**
         * What the sheet would draw, from a status and a record read at [now]. Pure, so the poll's
         * rule is proved without a device ([PhoneToolsSheetPollTest]): the two lines that age are
         * rendered here, and everything else is a data class, so two reads a second apart are equal
         * unless something the owner can see has changed.
         */
        internal fun drawn(
            status: PhoneMcp.Status,
            record: PhoneSetup.Record,
            tokenTag: String,
            now: Long,
        ): Drawn = Drawn(
            status = status,
            state = PhoneSetup.state(record, now, tokenTag),
            record = record,
            lastRequest = PhoneToolsView.lastRequest(status.lastRequest, status.lastRequestAt, now),
            handshake = PhoneToolsView.handshakeLine(record.handshakeAt, now),
        )

        /**
         * The poll's rule, pure: a tick repaints only when what the sheet would draw differs from
         * what it already shows. A second passing is not on its own a reason to rebuild the column
         * (#451) — that rebuild is what reset the scroll and dropped a drag in flight.
         */
        internal fun repaints(shown: Drawn?, next: Drawn): Boolean = next != shown

        @Volatile private var current: PhoneToolsSheet? = null

        /** The system folder picker's request code. */
        private const val REQ_FOLDER = 4301

        /** The calendar's runtime-permission request code. */
        private const val REQ_CALENDAR = 4302

        /** The contacts runtime-permission request code. */
        private const val REQ_CONTACTS = 4303

        /**
         * Hand the folder picker's answer to the open sheet. Called from the activity that holds
         * the sheet; false means the result belonged to something else. The grant itself is taken
         * and persisted here, so a result that arrives after the sheet closed is not lost.
         */
        fun handleActivityResult(
            activity: AppCompatActivity,
            requestCode: Int,
            resultCode: Int,
            data: Intent?,
        ): Boolean {
            if (requestCode != REQ_FOLDER) return false
            val sheet = current
            if (resultCode != Activity.RESULT_OK || data?.data == null) {
                sheet?.let {
                    it.notice = "No folder was granted, so Files still refuses with no_folder."
                    it.paint()
                }
                return true
            }
            val tree = PhoneFileAccess.accept(activity, data)
            val granted = if (tree == null) 0 else PhoneFileAccess.trees(activity).size
            sheet?.let {
                it.notice = if (tree != null) {
                    val note = if (granted > 1) {
                        " $granted folders are granted now, so the agent names one when it calls."
                    } else {
                        ""
                    }
                    "Folder granted · ${tree.label} — Files can now list and read one named file " +
                        "inside it.$note"
                } else {
                    "Android did not make that folder grant persistable, so no folder is granted."
                }
                it.paint()
            }
            return true
        }

        /** Hand a runtime-permission answer to the open sheet. False when it was not ours. */
        fun handlePermissionResult(activity: AppCompatActivity, requestCode: Int): Boolean = when (
            requestCode
        ) {
            REQ_CALENDAR -> {
                val missing = PhoneCalendarAccess.permission(activity)
                current?.let {
                    it.notice = if (missing == null) {
                        "Calendar granted — Agenda and Create event can now run."
                    } else {
                        "Calendar not granted — $missing is still not allowed, so Calendar actions refuse."
                    }
                    it.paint()
                }
                true
            }
            REQ_CONTACTS -> {
                val missing = ContactsAccess.permission(activity)
                current?.let {
                    it.notice = if (missing == null) {
                        "Contacts granted — a contact's number can now be looked up by name."
                    } else {
                        "Contacts not granted — $missing is still not allowed, so the lookup refuses."
                    }
                    it.paint()
                }
                true
            }
            else -> false
        }

        /** Called from an activity's onResume, so a grant made in system settings is visible at once. */
        fun refreshOpen() {
            current?.refresh()
        }
    }
}

