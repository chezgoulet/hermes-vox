package com.hermesvox

/**
 * ErSoulTurn — the contract for the soul's renders (0.8/M3c, reshaped by D30).
 *
 * D30: the soul is a companion, not a gatekeeper. Every voice turn goes to the mind; on a turn
 * the soul is asked only for the BEAT — two to six words the caller hears while the mind starts
 * — or silence. Its full lines are kept for the moments no mind turn competes with: the
 * greeting, and the working asides while a long run is under way. There are no keyword lists
 * in this path: the routing lists were deleted because a language judgement cannot be
 * enumerated (see docs/DESIGN-enhanced-realtime-voice.md, "the soul decides").
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
     * On a turn the token LEADS the beat (or stands alone for silence): the soul never answers a
     * turn, so a render that comes back without the token is a model that ignored the format and
     * is not spoken ([turnBeat]). The mind answers either way.
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
     * The turn directive: ask for the BEAT and nothing else (D30).
     *
     * [toolContext] is what the mind is currently doing, when that is known. It is what lets
     * the beat be topical rather than generic.
     *
     * Every turn is the mind's. The soul's only job is the two to six words a person says while
     * they start to answer — the escalate token followed by those words — or the token alone for
     * silence. It is never asked to answer the turn: an answer here is the gatekeeper D30 retired,
     * because a 2B "helpfully" replying to a factual question was the reporter's lost turn.
     */
    fun directive(callerText: String, toolContext: String? = null, heard: Boolean = false): String {
        val ctx = if (toolContext.isNullOrBlank()) "" else " The mind is currently working on: $toolContext."
        // When the caller's voice rides the render, the soul reads their TONE first — the mood
        // (VoiceMood) that steers the voice's delivery and the vibe the mind is told.
        val ears = if (!heard) "" else
            "You can hear the caller: their voice is attached. Start your reply with their tone as ONE tag " +
                "in curly braces, written exactly as one of {calm}, {warm}, {lively} or {tense} — judged from " +
                "HOW they sound, then continue. "
        return ears + "The caller just said: \"$callerText\".$ctx " +
            "The mind will answer them; you do not. Reply with $ESCALATE followed by two to six " +
            "words you would say out loud as you start to think about it, in your own voice — or " +
            "with exactly $ESCALATE to stay silent. Those words must not contain any fact, answer " +
            "or promise, and must not repeat an opener you already used."
    }

    /**
     * The words a turn render may speak: the beat, and only the beat (D30). A render without the
     * token ([Outcome.Spoken]) ignored the format — on a turn that is an answer the soul was not
     * asked for, and it is not spoken, because the mind is answering. The beat itself already
     * passed [opener] (no markup, no claimed result, at most [MAX_OPENER_WORDS] words).
     */
    fun turnBeat(o: Outcome): String? = (o as? Outcome.Beat)?.opener

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
            "result you do not have. Otherwise reply with exactly $ESCALATE."
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
     * use. It is written into the directive because that is what grounds it: every number the
     * client wrote into the directive is the client's fact ([vouchedNumbers]). D29/#406 dropped
     * the no-new-numbers rule — a number nobody supplied is no longer refused, only observed
     * ([unvouchedNumbers]) so #256 can measure what the model does with that freedom.
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
            " The caller has been waiting $waitedSeconds seconds."
        // The wait and the run's lines are part of rule two — the moment — not extra rules. The
        // directive is the five sentences of [presenceRules] and no other instruction.
        return presenceRules(
            "The caller asked: \"$callerText\", and the mind is still working on it.$ctx$already$wait"
        )
    }

    /**
     * D20's directive: five rules, and nothing else. Rule two is the moment and is supplied by the
     * caller; the other four are fixed.
     *
     * The text deliberately carries no digits: every digit in a directive is a grounded number
     * (D20), so numbering a rule would make the #256 observation count "1" as grounded.
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
     * The mid-run aside to speak, or null for silence. Silence is an escalate token in any form or
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
     * Numbers are not judged (D29/#406): the no-new-numbers rule is dropped. What a line may not
     * do, unconditionally, is claim a result the mind has not given ([REASON_UNBACKED_RESULT]).
     */
    fun workingLine(
        rendered: String?,
        saidThisRun: List<String> = emptyList(),
    ): String? = workingLine(parse(rendered), saidThisRun)

    /** [workingLine] for an already-parsed decision (the host's soul hook parses). */
    fun workingLine(
        decision: Outcome,
        saidThisRun: List<String> = emptyList(),
    ): String? {
        val text = (decision as? Outcome.Spoken)?.text ?: return null
        return judge(text, saidThisRun).line
    }

    /**
     * Why [workingLine] would refuse this render, or null when it would speak. Diagnostics only:
     * the refusal is the same either way, but `reason=silent` cannot tell "the soul chose
     * silence" from "the guard threw away a good line", and the field log depends on the
     * vocabulary.
     *
     * Reasons, in the order the guard applies them: `not-spoken` (escalate or empty render),
     * `empty` (nothing survived repair), `digits-or-markup`
     * (bracket markup — the name is unchanged for log continuity), [REASON_UNBACKED_RESULT] (a
     * claimed result the mind has not given), `too-many-words` (over [MAX_OPENER_WORDS] with no
     * clause end to cut at), `too-long` (> [MAX_OPENER_CHARS] after the cut), `repeat` (same line
     * already said this run).
     */
    /** [workingReject] for raw model output (the same parse the caller uses). */
    fun workingReject(
        rendered: String?,
        saidThisRun: List<String> = emptyList(),
    ): String? =
        // A blank render is "empty", not "not-spoken": parse() turns both into Nothing, and the
        // difference is the diagnosis the log needs.
        if (rendered.isNullOrBlank()) "empty"
        else workingReject(parse(rendered), saidThisRun)

    fun workingReject(
        decision: Outcome,
        saidThisRun: List<String> = emptyList(),
    ): String? {
        val text = (decision as? Outcome.Spoken)?.text ?: return "not-spoken"
        return judge(text, saidThisRun).reason
    }

    /**
     * What [workingLine] would repair before speaking, as log tokens (`cut`, `markup`, `fence`,
     * `title`, `courtesy`). Empty when nothing was repaired, and empty when the line is refused:
     * the field log must be able to tell "shortened" from "silenced" — a refused line is not a
     * repair, and reporting one would hide the refusal.
     *
     * The overloads mirror [workingLine]/[workingReject], and all three use the same [judge], so
     * the logged repair cannot drift from the spoken line.
     */
    fun workingRepairs(
        rendered: String?,
        saidThisRun: List<String> = emptyList(),
    ): List<String> =
        if (rendered.isNullOrBlank()) emptyList()
        else workingRepairs(parse(rendered), saidThisRun)

    fun workingRepairs(
        decision: Outcome,
        saidThisRun: List<String> = emptyList(),
    ): List<String> {
        val text = (decision as? Outcome.Spoken)?.text ?: return emptyList()
        val judged = judge(text, saidThisRun)
        return if (judged.line != null) judged.repairs else emptyList()
    }

    /** The guard's one decision, so [workingLine], [workingReject] and [workingRepairs] cannot drift apart. */
    private class Judged(
        val line: String?,
        val reason: String?,
        val repairs: List<String> = emptyList(),
    )

    /**
     * Repair the shape, then refuse only content. Content is judged first, on the repaired line
     * before any cut: a line that claims a result is refused even if the cut would have dropped
     * that clause — repair may remove formatting, never a claim. After content,
     * the shape is fitted (`too-many-words` when there is no clause end), then the character
     * backstop (`too-long`), then repeat.
     */
    private fun judge(text: String, said: List<String>): Judged {
        val repairs = ArrayList<String>()
        val repaired = repairLine(text, repairs) ?: return Judged(null, "empty")
        if (hasMarkup(repaired)) return Judged(null, "digits-or-markup")
        if (claimsResult(repaired)) return Judged(null, REASON_UNBACKED_RESULT)
        val fitted = fitToBound(repaired, repairs) ?: return Judged(null, "too-many-words")
        if (fitted.length > MAX_OPENER_CHARS) return Judged(null, "too-long")
        if (said.any { norm(it) == norm(fitted) }) return Judged(null, "repeat")
        return Judged(speakable(fitted), null, repairs)
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
     * D20's one new reason: a claimed result the working lane cannot have — "I've booked it" before
     * the mind has said so. Content, never shape; it is refused, not repaired. Named its own token
     * so a field log separates a lie from a formatting defect. D29/#406: this is the one content
     * guard left, and it is unconditional — a safety property, not a truthfulness nicety.
     */
    const val REASON_UNBACKED_RESULT = "result-unbacked"

    /**
     * The BEAT's opener: strict on purpose and untouched by D20's repair. The beat plays while the
     * mind is about to answer, so bracket markup, an over-long line or a claimed result costs the
     * beat rather than being spoken mid-thought (the turn/beat lane is frozen). D29/#406: a number
     * no longer costs it; the false-action-claim guard now runs here too, which can only refuse
     * more lines, never fewer.
     *
     * A trailing ellipsis gives the voice a trailing-off prosody, so the cut into the mind's answer
     * sounds like a person continuing rather than being interrupted.
     */
    fun opener(raw: String): String? {
        val line = raw.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return null
        val t = line.trim('"', '\'', '`', '*', '_', ' ', '-', '—', ':')
        if (t.isEmpty() || t.length > MAX_OPENER_CHARS) return null
        if (hasMarkup(t)) return null
        if (claimsResult(t)) return null
        val words = t.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty() || words.size > MAX_OPENER_WORDS) return null
        return speakable(t)
    }

    /**
     * D20: the numbers the client wrote into [directive] this run. The client composed them, so
     * they are grounded. Since D29/#406 this is an OBSERVATION, not a voucher list: nothing is
     * refused for being outside it.
     *
     * The runs are compared as written ("090" is not "90"), because the value the client offers is
     * the value the line must carry.
     */
    fun vouchedNumbers(directive: String): Set<String> = digitRuns(directive).toSet()

    /**
     * #256's observation (D29/#406): the digit runs in a spoken [line] that the directive did not
     * carry — numbers the model supplied itself. Logged, never refused; the measurement exists so
     * we know what the unrestricted model does.
     */
    fun unvouchedNumbers(line: String, vouched: Set<String>): List<String> =
        digitRuns(line).filterNot { it in vouched }

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
    fun repairLine(raw: String?): String? = repairLine(raw, null)

    private fun repairLine(raw: String?, repairs: MutableList<String>?): String? {
        // Field-2: a mood marker leading a line (`<<CALM>>`, `{calm}`, …) is never spoken; a
        // line that was only a marker goes. Stripped before the markup refusal sees it.
        val unmarked = VoiceMood.clean(raw).second
        if (unmarked != raw.orEmpty()) repairs?.addOnce("markup")
        val all = unmarked.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val lines = all.filterNot { FENCE_MARKER.matches(it) }
        if (lines.size != all.size) repairs?.addOnce("fence")
        if (lines.isEmpty()) return null
        // A title line is dropped only when something else can be the line; a render that is only
        // a heading is repaired to its text rather than thrown away.
        val withoutTitles = lines.filterNot { isTitleLine(it) }
        if (withoutTitles.size != lines.size) repairs?.addOnce("title")
        var line = withoutTitles.ifEmpty { lines }.first()
        val marked = stripMarkers(line)
        if (marked != line) repairs?.addOnce("markup")
        line = marked
        val bulleted = stripBullet(line)
        if (bulleted != line) repairs?.addOnce("markup")
        line = bulleted
        val courteous = stripCourtesy(line)
        if (courteous != line) repairs?.addOnce("courtesy")
        line = courteous
        line = line.trim('"', '\'', ' ', '-', '—', ':')
        return line.ifEmpty { null }
    }

    /** One log token per kind of repair, in the order the repairs happened. */
    private fun MutableList<String>.addOnce(kind: String) {
        if (kind !in this) add(kind)
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
    /**
     * F8 (2026-11-04 review): the mind's own progress narration, as a spoken working line.
     *
     * The gateway streams `phase=commentary` text — the mind saying in its own words what it is
     * doing — and the app used to log it and nothing else while the soul guessed about the same
     * tool. This turns that paragraph into the one line the phone can say: whitespace collapsed,
     * cut to the beat's budget at a clause end ([fitToBound]), or null when there is nothing
     * speakable in it.
     *
     * It is NOT a soul render, so the content guards that apply to one are not applied here: the
     * text is the mind's own, and [ErSoulTurn.claimsResult] still runs over it in the glue.
     */
    fun commentaryLine(raw: String?): String? {
        val t = raw?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
        if (t.isEmpty()) return null
        return fitToBound(t)
    }

    fun fitToBound(text: String): String? = fitToBound(text, null)

    private fun fitToBound(text: String, repairs: MutableList<String>?): String? {
        val tokens = Regex("\\S+").findAll(text).toList()
        if (tokens.size <= MAX_OPENER_WORDS) return text
        val limit = tokens[MAX_OPENER_WORDS - 1].range.last + 1
        val cut = (0 until limit).lastOrNull { text[it] in CLAUSE_ENDS } ?: return null
        val end = if (text[cut] in TERMINALS) cut + 1 else cut
        val candidate = text.substring(0, end).trim().trimEnd(',', ';', ':', '—', '–').trim()
        if (candidate.isEmpty()) return null
        val words = candidate.split(Regex("\\s+")).count { it.isNotEmpty() }
        if (words !in 1..MAX_OPENER_WORDS) return null
        repairs?.addOnce("cut")
        return candidate
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
    internal fun claimsResult(t: String): Boolean = DONE_CLAIM.containsMatchIn(t)

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
     * The decision's name in the log vocabulary — ONE mapping, used by the render site
     * (`event=er-soul decision=`) and by the handoff receipt ([ErHandoff.receipt]), so the two
     * lines can never disagree about what the soul chose.
     */
    fun decisionName(o: Outcome): String = when (o) {
        is Outcome.Spoken -> "answer"
        is Outcome.Beat -> "beat"
        Outcome.Escalate -> "escalate"
        Outcome.Nothing -> "nothing"
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
