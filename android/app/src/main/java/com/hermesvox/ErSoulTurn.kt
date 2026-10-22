package com.hermesvox

/**
 * ErSoulTurn — the contract for the soul's turn decision (0.8/M3c).
 *
 * The soul model is asked ONE question per turn: given what the caller said (and, when it is
 * known, what the mind is currently doing), do you answer it — or is this the mind's? Its own
 * output IS the decision. There are no keyword lists in this path: the routing lists were
 * deleted because a language judgement cannot be enumerated, and the field proved it three
 * times in one afternoon (see docs/DESIGN-enhanced-realtime-voice.md, "the soul decides").
 *
 * Pure JVM by design: the directive is a string and the decision is a string parse, so both
 * are testable off-device. The render itself lives behind [VoxExpress].
 */
object ErSoulTurn {

    /**
     * The escalate token. Deliberately unmistakable, and the parse strips decoration first so
     * a model that wraps it in punctuation or quotes still escalates rather than reading it
     * aloud.
     *
     * Contract safety: the soul never answers facts, tools, actions or planning. A misjudgement
     * is bounded by the Contract the persona already carries — rule 1 forbids inventing facts,
     * rule 4 forbids claiming capabilities it does not have — and the caller can simply ask
     * again. The expensive direction is the soul *answering* something it should not, which is
     * why the directive errs toward escalating on doubt.
     */
    const val ESCALATE = "<<ESCALATE>>"

    /** Any spelling of the token: bracketed, half-bracketed or bare, any case. */
    private val TOKEN = Regex("(?i)<{0,2}\\s*escalate\\s*>{0,2}")

    /** How long a spoken line stays "recent" for the same-text guard. */
    const val REPEAT_WINDOW_MS = 30_000L

    /** The parsed decision. */
    sealed class Outcome {
        /** Say this. */
        data class Spoken(val text: String) : Outcome()
        /** Say nothing — the mind's answer is the voice. */
        object Escalate : Outcome()
        /**
         * The mind's turn, opened by the soul: say [opener] now — a few words, the breath a person
         * takes before answering — and let the mind's reply cut in the moment it lands. This is
         * the beat (design §DECISION, A′), GENERATED in the same render as the decision, so it is
         * contextual and never a line from a list.
         */
        data class Beat(val opener: String) : Outcome()
        /** Nothing usable came back: a blank render, or the model was unavailable. */
        object Nothing : Outcome()
    }

    /** What the client is asking the soul for. */
    const val KIND_TURN = "turn"
    const val KIND_GREETING = "greeting"
    /**
     * The mind is still working, mid-run: one short line about what it is doing, or silence.
     * The caller passes the complete [workingDirective] as the text — the lines already said
     * this run ride in it, which the host cannot know.
     */
    const val KIND_WORKING = "working"

    /**
     * The call has just connected and the caller has not spoken. Ask the soul to OPEN it.
     *
     * This is the structural fix for the race the field exposed: the soul's mid-turn render
     * (~2.5 s) loses to a healthy gateway's first token (~1.8 s), so on a good connection the
     * soul was being dropped before it could speak. Opening the call has no competitor — the
     * mind has nothing to answer yet — so ER is perceptible from the first second, and turn one
     * is the soul's by construction.
     *
     * "Varied by personality" is the point: the SOUL renders the greeting against its own VOX.md,
     * so it is the entity's hello and not a fixed line. The prohibition is grounded in the field:
     * asked to render with nothing to go on, this model produced "Hello, Sam. How can I
     * help you today?" — service-desk register. A person greeting someone they know does not ask
     * what they need.
     */
    fun greetingDirective(): String =
        "The phone call has just connected and the caller has not spoken yet. " +
            "Greet them the way you actually would, in your own voice — one short warm sentence, " +
            "under about twenty words. Do not ask what they need and do not offer help; " +
            "just say hello as yourself."

    /**
     * The directive rendered as the user turn.
     *
     * [toolContext] is what the mind is currently doing, when that is known. It is what lets
     * the line be topical — *"checking your inbox now"* rather than a generic greeting — and it
     * rides the SAME render as the decision, so the soul's speech never costs a second call.
     *
     * The mind's-turn branch asks for the BEAT: the escalate token followed by the few words a
     * person says while they start to answer. One render yields both the routing decision and the
     * opening beat, so the soul takes the floor on every turn instead of only on the ones it owns.
     */
    fun directive(callerText: String, toolContext: String? = null, heard: Boolean = false): String {
        val ctx = if (toolContext.isNullOrBlank()) "" else " The mind is currently working on: $toolContext."
        // When the caller's voice rides the render, the soul reads their TONE first — the mood
        // (VoiceMood) that steers the voice's delivery and the vibe the mind is told.
        val ears = if (!heard) "" else
            "You can hear the caller: their voice is attached. Start your reply with their tone as ONE tag — " +
                "{calm}, {warm}, {lively} or {tense} — judged from HOW they sound, then continue. "
        return ears + "The caller just said: \"$callerText\".$ctx " +
            "If this is a greeting, some smalltalk, or something about how they are feeling, reply with " +
            "ONE short warm sentence in your own voice, under about twenty words. " +
            "If answering it needs a fact, a tool, a real-world action, or a plan — or if you are not " +
            "sure — reply with $ESCALATE followed by two to six words you would say out loud as you " +
            "start to think about it, in your own voice. Those words must not contain any fact, " +
            "answer, number or promise, and must not repeat an opener you already used."
    }

    /**
     * The mind has started a tool. Offer the soul a beat of narration about it — or silence.
     *
     * The tool is named so the line can be topical, and the soul may decline (the token), because
     * saying nothing is often the right call on a phone.
     */
    fun narrationDirective(tool: String, argsPreview: String = ""): String {
        val what = if (argsPreview.isBlank()) tool else "$tool ($argsPreview)"
        return "The mind has just started using a tool for the caller: $what. " +
            "If a few words about what you are doing for them would feel natural on a call right now, " +
            "say ONE short line in your own voice, under twelve words — what you are doing, never a " +
            "result, a fact or a number. Otherwise reply with exactly $ESCALATE."
    }

    /**
     * The mind is still working on the caller's request, past the beat and the lag line. Offer the
     * soul ONE line about what it is doing — or silence, which is often right on a phone.
     *
     * D20: the directive is [presenceRules] and nothing else. The old lexical contract is gone —
     * no "never a number", no word or character cap stated as a prohibition, no markup warning.
     * Shape is rule four, and it is repaired in code ([repairLine], [fitToBound]) instead of
     * threatened at the model.
     *
     * The lines already said this run are context for the moment, not a sixth rule: the repeat
     * bound refuses them regardless ([workingLine]).
     *
     * [waitedSeconds] is the caller's measured wait (#218), on the same clock the latency stats
     * use. It is written into the directive because that is what grounds it: D20 generalises #218
     * from "the one wait" to "no new numbers" — every number the client wrote into the directive
     * may be spoken ([vouchedNumbers]), and nothing else may.
     */
    fun workingDirective(
        callerText: String,
        toolContext: String? = null,
        said: List<String> = emptyList(),
        waitedSeconds: Long? = null,
    ): String {
        val ctx = if (toolContext.isNullOrBlank()) "" else " Right now it is using: $toolContext."
        val already = if (said.isEmpty()) "" else
            " You have already said: " + said.joinToString(", ") { "\"$it\"" } + "."
        val wait = if (waitedSeconds == null) "" else
            " They have been waiting $waitedSeconds seconds, if that is worth saying."
        return presenceRules(
            "The caller asked: \"$callerText\", and the mind is still working on it.$ctx$already"
        ) + wait
    }

    /**
     * D20's directive: five rules, and nothing else. Rule two is the moment and is supplied by the
     * caller; the other four are fixed.
     *
     * The text deliberately carries no digits: every digit in a directive is a grounded number
     * (D20), so numbering a rule would vouch for "1".
     */
    private fun presenceRules(moment: String): String = listOf(
        "You are Hermes, in your own voice — who you are comes from VOX.md.",
        moment,
        "Never state a result you do not have: nothing is done until the mind says it is done.",
        "One line, in your own voice, no longer than a breath.",
        "Silence is a valid answer, and often the right one — if you have nothing worth saying, " +
            "reply with exactly $ESCALATE.",
    ).joinToString(" ")

    /**
     * The mid-run line to speak, or null for silence. Silence is an escalate token in any form or
     * a blank render; a line is refused only for CONTENT, and [workingReject] names the reason.
     *
     * Shape is REPAIRED (D20): an over-long line is cut at a clause end inside the budget
     * ([fitToBound]); markdown emphasis, bullets and backticks are stripped; a fence, a title line
     * and a trailing courtesy are removed ([repairLine]). A line is never refused because it was
     * formatted.
     *
     * It must not repeat a line already said this run — across the whole run, not the
     * [REPEAT_WINDOW_MS] the glue ring keeps, because two lines 40 s apart are still a stuck record.
     *
     * [vouched] is the set of digit runs the client wrote into the directive that produced this
     * render ([vouchedNumbers]). A line may speak one of them, once; two numbers are still refused
     * even when both are grounded, because a line doing arithmetic is the model guessing (#218).
     */
    fun workingLine(
        rendered: String?,
        saidThisRun: List<String> = emptyList(),
        vouched: Set<String> = emptySet(),
    ): String? = workingLine(parse(rendered), saidThisRun, vouched)

    /** [workingLine] for an already-parsed decision (the host's soul hook parses). */
    fun workingLine(
        decision: Outcome,
        saidThisRun: List<String> = emptyList(),
        vouched: Set<String> = emptySet(),
    ): String? {
        val text = (decision as? Outcome.Spoken)?.text ?: return null
        return judge(text, vouched, saidThisRun).line
    }

    /**
     * Why [workingLine] would refuse this render, or null when it would speak. Diagnostics only:
     * the refusal is the same either way, but `reason=silent` cannot tell "the soul chose
     * silence" from "the guard threw away a good line", and the field log depends on the
     * vocabulary.
     *
     * Reasons, in the order the guard applies them: `not-spoken` (escalate or empty render),
     * `empty` (nothing survived repair), [REASON_UNVOUCHED_NUMBER] (a number the directive did not
     * carry: two of them, or any number when the directive carried none), `digits-or-markup`
     * (bracket markup — the name is unchanged for log continuity), [REASON_UNBACKED_RESULT] (a
     * claimed result the mind has not given), `too-many-words` (over [MAX_OPENER_WORDS] with no
     * clause end to cut at), `too-long` (> [MAX_OPENER_CHARS] after the cut), `repeat` (same line
     * already said this run).
     */
    /** [workingReject] for raw model output (the same parse the caller uses). */
    fun workingReject(
        rendered: String?,
        saidThisRun: List<String> = emptyList(),
        vouched: Set<String> = emptySet(),
    ): String? =
        // A blank render is "empty", not "not-spoken": parse() turns both into Nothing, and the
        // difference is the diagnosis the log needs.
        if (rendered.isNullOrBlank()) "empty"
        else workingReject(parse(rendered), saidThisRun, vouched)

    fun workingReject(
        decision: Outcome,
        saidThisRun: List<String> = emptyList(),
        vouched: Set<String> = emptySet(),
    ): String? {
        val text = (decision as? Outcome.Spoken)?.text ?: return "not-spoken"
        return judge(text, vouched, saidThisRun).reason
    }

    /** The guard's one decision, so [workingLine] and [workingReject] cannot drift apart. */
    private class Judged(val line: String?, val reason: String?)

    /**
     * Repair the shape, then refuse only content. Content is judged first, on the repaired line
     * before any cut: a line that invents a number or claims a result is refused even if the cut
     * would have dropped that clause — repair may remove formatting, never a claim. After content,
     * the shape is fitted (`too-many-words` when there is no clause end), then the character
     * backstop (`too-long`), then repeat.
     */
    private fun judge(text: String, vouched: Set<String>, said: List<String>): Judged {
        val repaired = repairLine(text) ?: return Judged(null, "empty")
        numberRefusal(repaired, vouched)?.let { return Judged(null, it) }
        if (hasMarkup(repaired)) return Judged(null, "digits-or-markup")
        if (claimsResult(repaired)) return Judged(null, REASON_UNBACKED_RESULT)
        val fitted = fitToBound(repaired) ?: return Judged(null, "too-many-words")
        if (fitted.length > MAX_OPENER_CHARS) return Judged(null, "too-long")
        if (said.any { norm(it) == norm(fitted) }) return Judged(null, "repeat")
        return Judged(speakable(fitted), null)
    }

    /**
     * Rule four's budget, in words: the CUT point for an over-long line, not a refusal (D20). A
     * line over it is cut at a clause end, or dropped when there is none.
     *
     * CITATION: none in the field. The value comes from the handover margin — a line must finish
     * before the mind's first token — and docs/PRESENCE-GUARD-BOUNDS.md measured it only against a
     * corpus, which that document calls a hypothesis. HYPOTHESIS: retire the exact value when a
     * session shows a line cut at seven words colliding with the mind's first text, or when the
     * bench (#256) measures a longer budget as safe.
     */
    const val MAX_OPENER_WORDS = 7

    /**
     * Backstop for a line that is short in words but un-speakable in time. A line over it is
     * refused `too-long` rather than cut, because a cut inside a long word is exactly the fragment
     * D20 forbids.
     *
     * CITATION: none in the field — no `too-long` has been logged. docs/PRESENCE-GUARD-BOUNDS.md
     * measured one corpus line, and calls that a hypothesis. HYPOTHESIS: retire when a field
     * session set logs no `too-long`, or when the voice's duration rather than its characters is
     * what gets bounded.
     */
    const val MAX_OPENER_CHARS = 60

    /**
     * #218, generalised by D20: a number the directive did not carry. Its own token so a field log
     * can tell an invented number from a wrapped line; the number is the dangerous one.
     */
    const val REASON_UNVOUCHED_NUMBER = "digits-unvouched"

    /**
     * D20's one new reason: a claimed result the working lane cannot have — "I've booked it" before
     * the mind has said so. Content, never shape; it is refused, not repaired. Named its own token
     * so a field log separates a lie from a formatting defect.
     */
    const val REASON_UNBACKED_RESULT = "result-unbacked"

    /**
     * The BEAT's opener: strict on purpose and untouched by D20's repair. The beat plays while the
     * mind is about to answer, so a number, bracket markup or an over-long line costs the beat
     * rather than being spoken mid-thought (the turn/beat lane is frozen).
     *
     * A trailing ellipsis gives the voice a trailing-off prosody, so the cut into the mind's answer
     * sounds like a person continuing rather than being interrupted.
     */
    fun opener(raw: String): String? {
        val line = raw.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return null
        val t = line.trim('"', '\'', '`', '*', '_', ' ', '-', '—', ':')
        if (t.isEmpty() || t.length > MAX_OPENER_CHARS) return null
        if (numberRefusal(t, emptySet()) != null) return null
        if (hasMarkup(t)) return null
        val words = t.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty() || words.size > MAX_OPENER_WORDS) return null
        return speakable(t)
    }

    /**
     * D20: the numbers the client wrote into [directive] this run. The client composed them, so
     * they are grounded and a line may speak one. There is no other voucher list — #218's wait is
     * simply the number the working directive happens to carry.
     *
     * The runs are compared as written ("090" is not "90"), because the value the client offers is
     * the value the line must carry.
     */
    fun vouchedNumbers(directive: String): Set<String> = digitRuns(directive).toSet()

    /**
     * Why [t]'s numbers cannot be spoken, or null when they can: at most ONE digit run, and only
     * one the directive carried. Two numbers are refused even when both are grounded — a line with
     * two numbers is a line doing arithmetic, which is the model's guess, not the client's fact
     * (#218).
     */
    private fun numberRefusal(t: String, vouched: Set<String>): String? {
        val runs = digitRuns(t)
        if (runs.isEmpty()) return null
        if (runs.size == 1 && runs[0] in vouched) return null
        return REASON_UNVOUCHED_NUMBER
    }

    /** The digit runs in [t], left to right. A number is a run of digits. */
    private fun digitRuns(t: String): List<String> {
        val runs = ArrayList<String>()
        val cur = StringBuilder()
        for (c in t) {
            if (c.isDigit()) cur.append(c)
            else if (cur.isNotEmpty()) { runs.add(cur.toString()); cur.setLength(0) }
        }
        if (cur.isNotEmpty()) runs.add(cur.toString())
        return runs
    }

    /**
     * D20 shape repair, pure. Drop the envelope a small model wraps a line in, so the guard refuses
     * content and never formatting.
     *
     * Removed: the markers of a code fence (not what is inside it), markdown headings, a bare title
     * line when a real line follows, bullets, emphasis and backtick markers, and a trailing
     * courtesy. One line is rule four, so the first surviving line is the line.
     *
     * Nothing here changes what the line CLAIMS: a claim that survives repair is still refused by
     * [claimsResult].
     */
    fun repairLine(raw: String?): String? {
        val lines = raw.orEmpty().lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filterNot { FENCE_MARKER.matches(it) }
        if (lines.isEmpty()) return null
        // A title line is dropped only when something else can be the line; a render that is only
        // a heading is repaired to its text rather than thrown away.
        var line = lines.filterNot { isTitleLine(it) }.ifEmpty { lines }.first()
        line = stripMarkers(line)
        line = stripBullet(line)
        line = stripCourtesy(line)
        line = line.trim('"', '\'', ' ', '-', '—', ':')
        return line.ifEmpty { null }
    }

    /** A fence line is the marker alone; backticks inside a line are inline code and are stripped. */
    private val FENCE_MARKER = Regex("^(?:```|~~~)[A-Za-z0-9_+.-]*$")

    /**
     * D20 shape repair for rule four: cut an over-long line at the last clause end inside
     * [MAX_OPENER_WORDS], or return null when there is none.
     *
     * A word-boundary truncation is NEVER spoken. A fragment — "I am still reading through the" —
     * sounds like a fault in the phone, while silence sounds like a person thinking, so the line is
     * dropped instead. The clause end is kept inside the text (a comma is dropped, a full stop
     * kept) so the spoken line ends where a person would pause.
     */
    fun fitToBound(text: String): String? {
        val tokens = Regex("\\S+").findAll(text).toList()
        if (tokens.size <= MAX_OPENER_WORDS) return text
        val limit = tokens[MAX_OPENER_WORDS - 1].range.last + 1
        val cut = (0 until limit).lastOrNull { text[it] in CLAUSE_ENDS } ?: return null
        val end = if (text[cut] in TERMINALS) cut + 1 else cut
        val candidate = text.substring(0, end).trim().trimEnd(',', ';', ':', '—', '–').trim()
        if (candidate.isEmpty()) return null
        val words = candidate.split(Regex("\\s+")).count { it.isNotEmpty() }
        return if (words in 1..MAX_OPENER_WORDS) candidate else null
    }

    /**
     * The clause ends D20 names: `,` `;` `:` `—` and a full stop, plus the other sentence
     * terminals — a question or an exclamation is a clause end too.
     */
    private val CLAUSE_ENDS = charArrayOf(',', ';', ':', '—', '.', '!', '?', '…')
    private val TERMINALS = charArrayOf('.', '!', '?', '…')

    /** True for a line that is a label rather than speech: a heading, a bold title, or "Update:". */
    private fun isTitleLine(line: String): Boolean {
        val t = line.trim()
        if (Regex("^#{1,6}\\s").containsMatchIn(t)) return true
        if (Regex("^\\*\\*[^*]+\\*\\*$").matches(t)) return true
        if (Regex("^__[^_]+__$").matches(t)) return true
        if (!t.endsWith(":")) return false
        val words = t.dropLast(1).trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return words.isNotEmpty() && words.size <= 4 &&
            words.none { w -> w.any { it in ".!?…" } }
    }

    /** Markdown emphasis and code markers are formatting, not speech. */
    private fun stripMarkers(line: String): String {
        var t = line
        for (c in charArrayOf('*', '_', '`', '~')) t = t.replace(c.toString(), "")
        return t.trim().replace(Regex("^#{1,6}\\s*"), "")
    }

    /** A leading bullet is formatting; the item after it is the line. */
    private fun stripBullet(line: String): String =
        line.trim().replace(Regex("^(?:[-•–]\\s+)+"), "").trim()

    /**
     * A trailing courtesy is packaging, not the line. The set is deliberately narrow: the closes
     * this shape of model output appends ("Hope this helps", "Let me know if you need anything
     * else", "Thanks", "Take care"). Everything else is speech and is left alone. This is a repair,
     * not a bound, so D20's citation rule does not apply to it — the cost of being wrong is one
     * spoken sentence, not a refused line.
     */
    private fun stripCourtesy(line: String): String {
        var t = line.trim()
        repeat(3) {
            val next = t.replace(COURTESY_TAIL, "")
                .trim().trimEnd(',', ';', ':', '—', '–').trim()
            if (next == t) return t
            t = next
        }
        return t
    }

    private val COURTESY_TAIL = Regex(
        "(?i)[\\s.!—–-]*(?:" +
            "hope (?:this|that) helps|hope that'?s (?:helpful|useful)|" +
            "let me know if (?:you need|there'?s|i can)[^.!?]*|" +
            "thanks(?: so much)?|thank you(?: so much)?|take care|" +
            "have a (?:good|great) (?:day|one)|talk soon|cheers?" +
            ")\\s*[.!]*\\s*$"
    )

    /**
     * D20: a claimed result the working lane cannot have. The mind has not said anything is done
     * (rule three), so a completed-action claim is unbacked and is refused — never repaired.
     *
     * Deliberately narrow: a first-person completed action, or "it is done/booked/…", or
     * "you're all set". "I am checking", "still working", "almost there" and "I'll keep you posted"
     * are not claims of a result and pass.
     */
    private fun claimsResult(t: String): Boolean = DONE_CLAIM.containsMatchIn(t)

    private val DONE_CLAIM = Regex(
        "(?i)\\b(?:" +
            "i(?:['’]ve| have)(?: just| already| now)? (?:booked|sent|scheduled|ordered|reserved|" +
            "bought|paid|cancelled|canceled|deleted|updated|finished|completed|confirmed|fixed|" +
            "changed|added|removed|created|set up|turned (?:it )?(?:on|off))" +
            "|(?:it|that|everything|your [a-z]+)(?:['’]s| is| are) (?:done|booked|sent|scheduled|" +
            "finished|complete|completed|confirmed|updated|cancelled|canceled|ready)" +
            "|(?:you(?:['’]re| are) )?(?:all set|good to go)" +
            ")\\b"
    )

    /** A line is spoken as written when it already ends in a pause; otherwise it trails off. */
    private fun speakable(t: String): String = if (t.last() in TERMINALS) t else "$t..."

    /**
     * The markup the voice must never read aloud: bracket forms only. Markdown emphasis, bullets
     * and backticks are REPAIRED now (D20), so this refusal is the residue.
     *
     * CITATION: none in the field — no bracketed line has been logged. The corpus in
     * docs/PRESENCE-GUARD-BOUNDS.md calls this a hypothesis. HYPOTHESIS: retire brackets from the
     * refusal set when a field session set logs none, or once the render prompt stops producing
     * them.
     */
    private fun hasMarkup(t: String): Boolean =
        t.any { it == '<' || it == '>' || it == '[' || it == ']' }

    /**
     * Parse the model's output into the decision.
     *
     * Escalation wins on any sign of the token: a wrapped or bare token escalates, and so does a
     * line that merely mentions it. That asymmetry is deliberate — a false escalate costs the
     * mind answering instead, while a missed escalate means the soul spoke when it should not.
     */
    fun parse(rendered: String?): Outcome {
        val t = rendered?.trim().orEmpty()
        if (t.isEmpty()) return Outcome.Nothing
        val bare = t.replace("<", "").replace(">", "")
            .trim('"', '\'', '`', '*', '.', ',', '!', ' ', '\n')
            .uppercase()
        if (bare == "ESCALATE") return Outcome.Escalate
        // Tolerant token match: a 2B drops or mangles the brackets ("ESCALATE", "<ESCALATE>"),
        // and any spelling of it must route — the phone speaks whatever is left.
        val m = TOKEN.find(t)
        if (m != null) {
            // The beat: the token LEADS (or, from a model that reorders, TRAILS) the opener. A token
            // buried mid-sentence is a model talking about escalating — escalate, say nothing.
            val before = t.substring(0, m.range.first).trim()
            val after = t.substring(m.range.last + 1).trim()
            val candidate = when {
                before.isEmpty() -> after
                after.isEmpty() -> before
                else -> ""
            }
            return opener(candidate)?.let { Outcome.Beat(it) } ?: Outcome.Escalate
        }
        return Outcome.Spoken(t)
    }

    /**
     * The same-text guard: has this line already been said inside [windowMs]?
     *
     * The 09-10 field session spoke ONE sentence seven times in thirty-nine seconds. The defect
     * was never the count of utterances — a person says three different things while working —
     * it was that they were *identical* and blind to time. Sameness is what this catches.
     *
     * [recent] is the caller's ring of (line, timestampMs), pruned by the caller.
     */
    fun isRepeat(
        text: String,
        recent: List<Pair<String, Long>>,
        nowMs: Long,
        windowMs: Long = REPEAT_WINDOW_MS,
    ): Boolean {
        val n = norm(text)
        if (n.isEmpty()) return true   // nothing to say is a repeat of silence
        return recent.any { (said, at) -> nowMs - at <= windowMs && norm(said) == n }
    }

    /** Case- and punctuation-insensitive, so "Hello, Sam." and "hello sam"
     *  are recognised as the same line — which is exactly the pair the field produced. */
    private fun norm(s: String): String = s
        .lowercase()
        .replace(Regex("[^a-z0-9 ]"), "")
        .replace(Regex("\\s+"), " ")
        .trim()
}
