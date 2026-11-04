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
import com.hermesvox.core.HermesSession

/**
 * PhoneToolsSheet — the owner's side of the phone-as-MCP-server (#421 steps 3–5).
 *
 * **The setup path is a turn, not a pasted block.** **Set up with my agent** composes the prompt in
 * [PhoneSetup], creates the credential if there is not one, and sends that prompt as an ordinary
 * stored turn through the app's session. The turn and the agent's reply are shown here, so a change
 * to the agent's own configuration is legible.
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
    private val session: HermesSession? = null,
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
                "Your agent can set timers and change volume on this phone. It cannot read your " +
                    "messages or make calls."
            else
                "Let your agent set timers and change volume on this phone — and nothing else.",
        )
        body(
            col,
            "The listener is open only while a call is. Hanging up closes it; there is no " +
                "listening socket the rest of the time.",
            dim = true,
        )

        val status = PhoneMcp.status(act)
        val now = System.currentTimeMillis()
        val record = PhoneSetupStore.read(act)
        val state = PhoneSetup.state(record, now, PhoneMcp.tokenTag(act))

        col.addView(VoxSheet.subhead(act, "This phone"))
        row(col, "Reachability", PhoneToolsView.reachability(status.tailnet))
        row(col, "Server", PhoneToolsView.server(status.tailnet, status.listening, status.url))
        row(col, "Do Not Disturb access", PhoneToolsView.dnd(status.dndGranted))
        if (!status.dndGranted) {
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
        row(col, "Token", PhoneToolsView.token(status.token))

        col.addView(VoxSheet.subhead(act, "Set up with your agent"))
        body(
            col,
            "Set up sends one ordinary turn to your agent and asks it to add this phone to its own " +
                "MCP configuration. You do not edit any files. It is idempotent: if a `vox` entry " +
                "already exists, the turn says to update it in place, not to add a second one.",
        )

        row(col, "Setup", PhoneToolsView.setupHeadline(state))
        body(col, PhoneToolsView.setupNext(state), dim = true)
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
                action(col, "SET UP WITH MY AGENT", primary = true, description = "Ask your agent to add this phone") { setUpAgent() }
            PhoneSetup.State.WAITING ->
                action(col, "ASK AGAIN", primary = false, description = "Send the setup turn again") { setUpAgent() }
            PhoneSetup.State.CONNECTED ->
                action(col, "ASK AGAIN", primary = false, description = "Send the setup turn again; it will not add a second entry") { setUpAgent() }
            PhoneSetup.State.UNREACHABLE ->
                action(col, "TRY AGAIN", primary = true, description = "Ask your agent again") { setUpAgent() }
            PhoneSetup.State.REFUSED ->
                action(col, "TRY AGAIN", primary = true, description = "Ask your agent again after fixing its file access") { setUpAgent() }
            PhoneSetup.State.TIMED_OUT -> {
                action(col, "TRY AGAIN", primary = true, description = "Send the setup turn again") { setUpAgent() }
                action(col, "ROTATE TOKEN", primary = false, description = "Replace the token and ask with the new one") { rotateToken() }
            }
        }

        if (record.requestedAt > 0L) {
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

    private fun setUpAgent() {
        val status = PhoneMcp.status(act)
        val host = hostOrPlaceholder(status)
        if (status.tailnet.isNullOrBlank()) {
            notice = "Connect Tailscale first: the setup turn needs this phone's real tailnet " +
                "address, and without one there is nothing for the agent to reach."
            paint()
            return
        }
        val s = session ?: PhoneSetupStore.session(act)
        if (s == null) {
            notice = "Connect the app to your gateway first (Entity & Connection), then try again."
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
        @Volatile private var current: PhoneToolsSheet? = null

        /** Called from an activity's onResume, so a grant made in system settings is visible at once. */
        fun refreshOpen() {
            current?.refresh()
        }
    }
}

