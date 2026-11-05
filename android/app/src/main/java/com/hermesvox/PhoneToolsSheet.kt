package com.hermesvox

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
 * **Connected is earned by the handshake.** The headline stays *waiting for your agent to connect*
 * until the phone's own [PhoneMcpServer] has seen an authenticated MCP `initialize`; only then is
 * the state [PhoneSetup.State.CONNECTED]. The agent's reply is displayed beneath every state,
 * because when it fails, its own words are the next clue.
 *
 * **Failure states each name a next step**, including a retry and a token rotation. The manual
 * config block still exists — behind a disclosure, labelled fallback, never the default path — for
 * an owner whose agent cannot edit its own configuration.
 *
 * **Permissions are shown, not assumed.** Do Not Disturb is a special access, so the sheet shows
 * whether it is granted and opens the right system screen; an action that needs it still refuses in
 * [PhoneEngine] with `reason:"permission"`.
 */
class PhoneToolsSheet(
    private val act: AppCompatActivity,
    private val firstRun: Boolean = false,
    private val onDismiss: () -> Unit = {},
) {
    private var dialog: Dialog? = null
    private var open = false
    private var expanded = false
    private var notice: String? = null
    private var poll: Runnable? = null
    private val main = Handler(Looper.getMainLooper())

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
                poll?.let { main.removeCallbacks(it) }
                poll = null
                if (current === this) current = null
                onDismiss()
            }
        }
        d.setContentView(build())
        d.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        if (d.isShowing) return
        d.show()
    }

    private fun schedulePoll() {
        if (poll != null) return
        val tick = object : Runnable {
            override fun run() {
                if (!open) return
                val state = PhoneSetupStore.state(act)
                // The clock is what moves waiting → timed out, and what ages "last request".
                if (state == PhoneSetup.State.WAITING || state == PhoneSetup.State.TIMED_OUT) paint()
                main.postDelayed(this, 1_000L)
            }
        }
        poll = tick
        main.postDelayed(tick, 1_000L)
    }

    private fun build(): View {
        val root = VoxSheet.root(act)
        VoxSheet.header(root, "PHONE TOOLS", "Close phone tools", onClose = { dialog?.dismiss() })

        val scroll = ScrollView(act).apply { isFillViewport = true }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(col, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

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
            "Off hides a group from your agent. Ask lets it try, and every use waits for you. " +
                "Allow runs it instantly and reports it in Recent actions — and Allow is offered " +
                "for instant, reversible groups only. ${PhoneToolsView.grantSummary(states)}.",
            dim = true,
        )
        body(
            col,
            "Approval cards arrive with the capabilities that need them. Until then an Ask action " +
                "is refused with a reason, never run, and the refusal is recorded below.",
            dim = true,
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

        val status = PhoneMcp.status(act)
        val now = System.currentTimeMillis()
        val record = PhoneSetupStore.read(act)
        val state = PhoneSetup.state(record, now, PhoneMcp.tokenTag(act))

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
        row(col, "Volume permission", PhoneToolsView.volume(status.volumeGranted))
        row(col, "Last request", PhoneToolsView.lastRequest(status.lastRequest, status.lastRequestAt, now))
        row(col, "Token", PhoneToolsView.token(status.token, status.allowed))

        col.addView(VoxSheet.subhead(act, "Set up with your agent"))
        body(
            col,
            "Set up sends one ordinary turn to your agent and asks it to add this phone to its own " +
                "MCP configuration. You do not edit any files. It is idempotent: if a `vox` entry " +
                "already exists, the turn says to update it in place, not to add a second one.",
        )

        row(col, "Setup", PhoneToolsView.setupHeadline(state, record.failure, status.allowed))
        body(col, PhoneToolsView.setupNext(state, status.allowed), dim = true)
        notice?.let { body(col, it) }

        if (state == PhoneSetup.State.CONNECTED) {
            body(col, PhoneToolsView.handshakeLine(record.handshakeAt, now), dim = true)
        }

        val reply = record.reply
        if (!reply.isNullOrBlank()) {
            col.addView(VoxSheet.subhead(act, "Your agent says"))
            replyView(col, reply)
        }

        when (state) {
            PhoneSetup.State.IDLE ->
                if (status.allowed) action(col, "SET UP WITH MY AGENT", primary = true, description = "Ask your agent to add this phone") { setUpAgent() }
            PhoneSetup.State.WAITING ->
                if (status.allowed) action(col, "ASK AGAIN", primary = false, description = "Send the setup turn again") { setUpAgent() }
            PhoneSetup.State.CONNECTED ->
                if (status.allowed) action(col, "ASK AGAIN", primary = false, description = "Send the setup turn again; it will not add a second entry") { setUpAgent() }
            PhoneSetup.State.UNREACHABLE ->
                if (status.allowed) action(col, "TRY AGAIN", primary = true, description = "Ask your agent again") { setUpAgent() }
            PhoneSetup.State.REFUSED ->
                if (status.allowed) action(col, "TRY AGAIN", primary = true, description = "Ask your agent again after fixing its file access") { setUpAgent() }
            PhoneSetup.State.TIMED_OUT -> {
                if (status.allowed) {
                    action(col, "TRY AGAIN", primary = true, description = "Send the setup turn again") { setUpAgent() }
                    action(col, "ROTATE TOKEN", primary = false, description = "Replace the token and ask with the new one") { rotateToken() }
                }
            }
        }

        if (status.allowed && record.requestedAt > 0L) {
            val sent = PhoneSetupStore.lastPrompt()
            disclosure(
                col,
                if (expanded) "HIDE THE SETUP TURN" else "SHOW THE SETUP TURN",
                "Show or hide the setup turn, including the address and token",
            ) {
                expanded = !expanded
                paint()
            }
            if (expanded) {
                body(
                    col,
                    if (sent != null)
                        "This is the turn the app sent. It carries the real address and token, which " +
                            "is why it is behind a disclosure."
                    else
                        "The turn is regenerated from the current address and token for this screen. " +
                            "It carries the real address and token, which is why it is behind a disclosure.",
                    dim = true,
                )
                configView(
                    col,
                    sent ?: PhoneSetupStore.prompt(hostOrPlaceholder(status), PhoneMcp.token(act)),
                )
            }
        }

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
     * One capability group: its name, what it lets the agent do, the warning where the consequence
     * is not obvious, and the states its tier permits. Allow is rendered only where the registry
     * permits it, so the control and the policy cannot disagree.
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
        val controls = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        for (choice in PhoneGrants.statesFor(group.tier)) {
            controls.addView(stateButton(group, choice, choice == state))
        }
        box.addView(controls)
        PhoneToolsView.allowAbsent(group.tier)?.let { why ->
            box.addView(TextView(act, null, 0, R.style.HvRowValue).apply {
                text = why
                setTextColor(color(R.color.hv_text_dim))
            })
        }
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
        expanded = false
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

    private fun copy(text: String) {
        val cm = act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("vox phone config", text))
        Toast.makeText(act, "config copied", Toast.LENGTH_SHORT).show()
    }

    companion object {
        /** How many Recent actions rows the sheet renders. The store keeps [PhoneAudit.MAX]. */
        private const val AUDIT_ROWS = 20

        @Volatile private var current: PhoneToolsSheet? = null

        /** Called from an activity's onResume, so a grant made in system settings is visible at once. */
        fun refreshOpen() {
            current?.refresh()
        }
    }
}

