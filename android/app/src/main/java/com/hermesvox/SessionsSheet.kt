package com.hermesvox

import android.app.Dialog
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * SessionsSheet — the card `/sessions` opens (#437): the conversations the host already has,
 * and the one you are in marked.
 *
 * It is a sheet on [VoxSheet], the same scaffold Alpha, Share, File, Profiles and the model
 * switcher stand on, and its rows are the same grammar (title, one line under it, an optional
 * chip). What it shows is what the host serves to this key, **newest activity first**
 * ([Sessions.order]) — the order a person picks from.
 *
 * Three states and no fourth: rows, "more", or one line naming why there is nothing. A long
 * list pages with the host's own `has_more`/`offset` — a **More** row, never a silent cut —
 * and a re-ask is a row of its own, so the card can be brought up to date without closing it.
 *
 * The pick is the caller's ([MainActivity.adoptSession]): this sheet only says which row. It
 * closes on the tap, because the answer to a pick is the conversation behind it.
 *
 * [fetch] is the wire ([SessionsClient]), [inUse] is the id the app is talking in — the
 * adopted session, or the conversation this app has been writing — so the chip cannot claim a
 * session the phone is not in.
 */
class SessionsSheet(
    private val act: AppCompatActivity,
    private val fetch: (limit: Int, offset: Int) -> SessionsClient.Fetched,
    private val inUse: () -> String,
    private val onPick: (Sessions.Row) -> Unit,
) {

    private var dialog: Dialog? = null
    private var body: LinearLayout? = null
    private var subtitle: TextView? = null

    /** What the host served so far, in screen order. */
    private var rows: List<Sessions.Row> = emptyList()
    private var listing: Sessions.Listing? = null
    private var hasMore = false
    private var nextOffset = 0
    private var loading = false

    private fun dp(v: Int) = (v * act.resources.displayMetrics.density).toInt()
    private fun color(id: Int) = ContextCompat.getColor(act, id)
    private fun drawable(id: Int) = ContextCompat.getDrawable(act, id)

    fun isShowing(): Boolean = dialog?.isShowing == true

    fun show() {
        if (dialog?.isShowing == true) { reload(); return }
        val d = VoxSheet.dialog(act)
        val root = VoxSheet.root(act)
        VoxSheet.header(root, "SESSIONS", "Close sessions", { d.dismiss() })
        val sub = TextView(act, null, 0, R.style.HvRowValue).apply {
            setPadding(0, dp(6), 0, 0)
            setTextColor(color(R.color.hv_text_dim))
        }
        root.addView(sub)
        subtitle = sub
        val list = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(act).apply { addView(list); isFocusable = false },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        d.setContentView(root)
        d.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        VoxSheet.keys(d)
        d.setOnDismissListener {
            dialog = null; body = null; subtitle = null
            // A listing belongs to the visit that asked for it: the next opening asks again,
            // so a card can never show another host's answer (the per-profile rule, §6).
            rows = emptyList(); listing = null; hasMore = false; nextOffset = 0; loading = false
        }
        dialog = d
        body = list
        d.show()
        root.isFocusableInTouchMode = true
        root.requestFocus()
        reload()
    }

    fun dismiss() { dialog?.dismiss() }

    /** Forget what was listed — a profile switch, a new conversation. Nothing survives it. */
    fun invalidate() {
        rows = emptyList(); listing = null; hasMore = false; nextOffset = 0
        if (isShowing()) reload() else dialog?.dismiss()
    }

    /** Ask the host from the first page. */
    fun reload() {
        if (!isShowing() || loading) return
        loading = true
        paint()
        Thread({
            val answer = Sessions.listing(fetch(Sessions.LIMIT, 0))
            onUi {
                listing = answer
                rows = (answer as? Sessions.Listing.Ok)?.let { Sessions.order(it.page.rows) }.orEmpty()
                nextOffset = (answer as? Sessions.Listing.Ok)?.let { it.page.offset + it.page.rows.size } ?: 0
                hasMore = (answer as? Sessions.Listing.Ok)?.page?.hasMore == true
                loading = false
                paint()
            }
        }, "sessions-list").start()
    }

    /** The next page, appended — the host's own offset, never a guess. */
    private fun more() {
        if (loading || !hasMore) return
        loading = true
        paint()
        Thread({
            val answer = Sessions.listing(fetch(Sessions.LIMIT, nextOffset))
            onUi {
                if (answer is Sessions.Listing.Ok) {
                    val seen = rows.map { it.id }.toHashSet()
                    // A session written to between two pages can move up a page; the id is the
                    // identity, so it is drawn once.
                    rows = rows + Sessions.order(answer.page.rows.filter { it.id !in seen })
                    nextOffset = answer.page.offset + answer.page.rows.size
                    hasMore = answer.page.hasMore
                } else {
                    // A failure on the second page does not erase the first: the note says why.
                    listing = answer
                    hasMore = false
                }
                loading = false
                paint()
            }
        }, "sessions-more").start()
    }

    private fun onUi(block: () -> Unit) {
        act.runOnUiThread { if (!act.isFinishing && !act.isDestroyed) block() }
    }

    private fun paint() {
        val list = body ?: return
        list.removeAllViews()
        val state = listing
        // The one line under the title: what the host served, or the cause of there being
        // nothing (a refused key, no answer, no sessions) — never a blank card.
        subtitle?.text = if (state == null) "Asking the host…" else Sessions.summary(state)
        val inUseId = inUse()
        for (r in rows) list.addView(sessionRow(r, r.id == inUseId))
        if (hasMore) {
            list.addView(actionRow("More sessions", "Ask the host for the next page") { more() })
        }
        list.addView(actionRow("Ask again", "Refresh what this host serves") { reload() })
    }

    private fun sessionRow(r: Sessions.Row, current: Boolean): View {
        val detail = Sessions.line(r, System.currentTimeMillis())
        return row(
            title = r.label,
            detail = detail,
            chip = if (current) "in use" else null,
            // One announcement per row, in reading order.
            description = listOf(
                r.label, detail, if (current) "in use" else null,
                if (current) "the session you are talking in" else "resume this session",
            ).filterNotNull().joinToString(", "),
        ) {
            dismiss()
            onPick(r)
        }
    }

    private fun actionRow(title: String, line: String, onTap: () -> Unit): View =
        row(title = title, detail = line, chip = null, description = "$title, $line", onTap = onTap)

    /** One row in the commands/model sheets' grammar: title, one line, an optional chip. */
    private fun row(
        title: String,
        detail: String,
        chip: String?,
        description: String,
        onTap: () -> Unit,
    ): View {
        val r = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true; isFocusable = true
            background = drawable(R.drawable.bg_command_row)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            minimumHeight = dp(56)
            contentDescription = description
            setOnClickListener { onTap() }
        }
        val text = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        text.addView(TextView(act, null, 0, R.style.HvRowTitle).apply {
            this.text = title
            // A session is a title the host wrote, not a typed command: bold, not monospace.
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        if (detail.isNotBlank()) text.addView(TextView(act, null, 0, R.style.HvRowValue).apply {
            this.text = detail
            setTextColor(color(R.color.hv_text))
        })
        r.addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (chip != null) r.addView(TextView(act, null, 0, R.style.HvRowAction).apply {
            this.text = chip
            background = drawable(R.drawable.bg_chip)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO   // said in the row's own description
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { marginStart = dp(8) })
        return LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(3), 0, dp(3))
            addView(r)
        }
    }
}
