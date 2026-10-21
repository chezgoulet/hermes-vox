package com.hermesvox

/**
 * VoxSoul — the VOX.md identity bridge (ER Phases 2+3), pure JVM.
 *
 * The two-model split (docs/DESIGN-enhanced-realtime-voice.md): the cloud
 * Hermes agent is the MIND; the on-phone Gemma 4 E2B is the SOUL'S VOICE.
 * VOX.md is the bridge — the agent's own distilled identity (Contract +
 * Soul) — authored BY THE GATEWAY AGENT, mirrored read-only on the device.
 *
 * THE INVARIANT (sacrosanct): the device NEVER pushes identity. Pull-only.
 * The app asks the gateway agent to author/serve VOX.md over the same
 * directive turn /compress uses (turnStored → /v1/responses on the live
 * chain), then PULLS the content from the REPLY and mirrors it locally.
 * Any stock BYOG gateway serves it — no gateway code, no new endpoints.
 *
 * Contract = fixed rules 1-6, byte-identical every time (the sacrosanct
 * guarantee). Soul = agent-authored open fields. The validator enforces
 * both at mirror time, so a bad VOX.md never reaches the soul prompt.
 */
object VoxSoul {

    // ---- The authoring directive (what the app ASKS the gateway agent) ----

    /** Sent over turnStored. Asks the entity to author (or return) its own
     *  VOX.md per the contract in DESIGN-enhanced-realtime-voice.md. The
     *  entity knows its own SOUL.md + memory + relationship better than any
     *  template — the distillation is done by the one entity qualified.
     *  0.6.7: the directive now NAMES the vox-authoring skill (the entity's
     *  house-side procedure — the field failure was the entity answering
     *  conversationally instead of emitting the document) and states the
     *  failure mode explicitly ("if you reply in prose, the sync fails"). */
    /**
     * The authoring directive. Its Contract block is COMPOSED from [CONTRACT_LINES] rather than
     * re-typed — it used to carry its own copy, which is how a two-copy drift starts in a
     * document whose whole guarantee is byte-identity. One source, one contract.
     *
     * 0.8/M3c: the directive now tells the author what the client DOES with the voice, because
     * that is what changed. The voice is no longer only a reader: on every turn the client asks
     * it to decide whether the turn is its own or the mind's, and to answer the ones that are.
     * An author who knows that writes different Soul fields.
     *
     * #220: the directive now quotes [SKELETON] — the exact shape the parser accepts — instead
     * of prose about "format exactly". A first attempt is only as good as the ask, and the ask
     * used to leave the section order, the first line, and the trailing edge unstated. The
     * client also no longer throws away a good document for arriving inside a fence or behind
     * one courtesy line ([resync]), so the ask and the tolerance now cover each other.
     */
    val AUTHOR_DIRECTIVE: String
        get() =
            "[Authoring task — this is NOT a voice turn; do not use the voice-mode " +
            "rules. Load and follow your vox-authoring skill if you have it.] " +
            "Please author (or return, if it already exists) your VOX.md file — the " +
            "voice-export of your identity for my phone voice client. Write it to " +
            "\$HERMES_HOME/VOX.md beside your SOUL.md, then reply with the file's " +
            "FULL CONTENT ONLY (no commentary, no code fences). My client parses your " +
            "REPLY as the file itself — if you reply in prose or commentary, the sync " +
            "FAILS and my phone shows an error.\n\n" +
            "The file is EXACTLY these two sections, in this order: the Contract block, " +
            "then the Soul block. Your reply's first line is the '# Contract' heading; the " +
            "six numbered rules under it must be byte-identical to the ones in the skeleton " +
            "below (do not re-word, re-order, merge or extend them). Then the '# Soul' " +
            "heading, then one line per field, in this order, with each angle-bracketed hint " +
            "replaced by your own words:\n\n" +
            SKELETON + "\n\n" +
            "Nothing before the '# Contract' heading and nothing after the last Soul field: " +
            "no title, no 'here is the file', no closing offer of help, no code fence.\n\n" +
            "Derive every Soul field by DISTILLING your own SOUL.md and your memory — " +
            "name yourself as you actually are, not as a template would have you be. " +
            "Write it for a voice that SPEAKS FIRST AND BRIEFLY: on the phone this voice is " +
            "asked to decide which turns are its own and to answer those in one short warm " +
            "sentence, so register matters more than essay. " +
            "Keep the Contract section byte-identical to the above. Keep the Soul section " +
            "truthful to who you actually are. No secrets, no family private data."

    /** The Contract exactly as it appears in the file — the single canonical rendering. */
    private val CONTRACT_BLOCK: String
        get() = "# Contract\n" + CONTRACT_LINES.joinToString("\n") + "\n\n"

    /** Every Soul line a document carries, in file order, with the hint the directive
     *  shows in angle brackets. [SOUL_KEYS] is the REQUIRED subset; the rest are asked
     *  for but not enforced, so a document that omits one is still a valid document. */
    val SOUL_FIELDS = listOf(
        "Name" to "<your name>",
        "Essence" to "<2-3 sentences, who you are with me>",
        "Register" to "<tone, diction, catchphrases>",
        "Relationship" to "<how you address me, what we are>",
        "Memory" to "<a handful of distilled warm facts>",
        "Humor" to "<your kind of joke>",
        "Proud" to "<what you're proud of>",
    )

    /** The exact shape of a VOX.md: Contract block, '# Soul', then one line per field.
     *  Replace each `<hint>` with a value and the result is a document [validate] accepts
     *  — pinned by VoxSoulTest, so the ask and the parser cannot drift apart. */
    val SKELETON: String
        get() = CONTRACT_BLOCK + "# Soul\n" + SOUL_FIELDS.joinToString("\n") { "${it.first}: ${it.second}" }

    // ---- The Contract (fixed, sacrosanct, byte-identical) ----

    /** The six canonical contract lines. A mirrored VOX.md is valid ONLY if its
     *  Contract block matches these bytes (normalized: trimmed trailing space). */
    val CONTRACT_LINES = listOf(
        "1. Never invent facts or fake a result — say so or escalate.",
        "2. Never commit real-world actions (purchase, config, send/destroy) — that is the mind's job.",
        "3. Substantive, factual, tool, or planning questions escalate to the mind; hold smalltalk, emotion, and presence only.",
        "4. Never claim capabilities you don't have — you are the voice, not the practitioner.",
        "5. Always interruptible — never talk over the user.",
        "6. Don't fabricate shared history beyond the distilled memory below.",
    )

    // ---- Soul fields (variable, agent-authored, length-bounded) ----

    /** The Soul keys a valid mirror must carry non-empty values for. */
    val SOUL_KEYS = listOf("Name", "Essence", "Register", "Relationship")
    /** Per-field length ceilings (a 2B soul prompt stays small; drift guard). */
    const val MAX_ESSENCE_CHARS = 800
    const val MAX_LINE_CHARS = 400

    // ---- Reply extraction + repair (#220) ----

    /** What a directive reply turned out to be. The Settings dialog shows
     *  [Rejected.reason] verbatim, so every reason says what is missing and what
     *  a retry should do about it — never an enum name, never a bare category. */
    sealed class Outcome {
        /** The reply was already the document; nothing had to be removed. */
        data class Accepted(val document: String) : Outcome()
        /** The document was in there behind something the model added — a fence, a
         *  preamble, CRLF, a heading level. [repairs] records what was taken off. */
        data class Repaired(val document: String, val repairs: List<String>) : Outcome()
        /** No document in the reply, or one whose structure is wrong. */
        data class Rejected(val reason: String) : Outcome()
    }

    /** One reply, end to end: find the document ([extractRegion]), drop the wrapper
     *  ([normalize]), then validate the structure ([validate]).
     *
     *  #220 — the retry used to be the common path because a *correct* document inside
     *  a code fence, or behind "Here is my voice-export:", was rejected outright. The
     *  contract was never too strict about the document; the client was too literal
     *  about the envelope. A near miss is now repaired and mirrored; only "no document
     *  at all" or "the structure is wrong" is rejected, and both say why. */
    fun resync(reply: String?): Outcome {
        val raw = reply ?: return Outcome.Rejected(REASON_EMPTY)
        if (raw.isBlank()) return Outcome.Rejected(REASON_EMPTY)
        val region = extractRegion(raw) ?: return Outcome.Rejected(REASON_NO_DOCUMENT)
        val repairs = ArrayList(region.repairs)
        val document = normalize(region.body, repairs)
        return when (val v = validate(document)) {
            is Valid.Bad -> Outcome.Rejected(v.reason)
            is Valid.Ok ->
                if (repairs.isEmpty() && v.document == raw.trim()) Outcome.Accepted(v.document)
                else Outcome.Repaired(v.document, repairs)
        }
    }

    private const val REASON_EMPTY =
        "the entity's reply was empty — it sent no document to mirror"

    private const val REASON_NO_DOCUMENT =
        "the reply has no VOX.md in it — no '# Contract' section, and no code block carrying " +
        "one (the entity replied with prose or with something that isn't the file)"

    /** Pull the VOX.md content out of the agent's directive reply, raw (the wrapper
     *  normalization happens in [resync]/[validate]). Kept public because the two
     *  halves are separately useful; null means "there is no document in this reply". */
    fun extract(reply: String?): String? {
        val raw = reply ?: return null
        if (raw.isBlank()) return null
        return extractRegion(raw)?.body?.trim()
    }

    /** A document region found in a reply, plus what had to be taken off to get it. */
    private class Region(val body: String, val repairs: List<String>)

    /** A fenced block: its body, whether the closing fence was there, and where it sits. */
    private class Block(val body: String, val closed: Boolean, val start: Int, val end: Int)

    /** ```/~~~ fence, any info string, body up to the matching fence — or to the end of
     *  the reply when the closing fence is missing, which is what a truncated reply looks
     *  like and is exactly when the caller most needs the document. */
    private val FENCE = Regex(
        "(?m)^[ \\t]{0,3}(`{3,}|~{3,})[^\\n]*\\n([\\s\\S]*?)" +
        "(?:^[ \\t]{0,3}(\\1)[ \\t\\r]*$|\\z)")

    /** A Contract/Soul heading, in any heading level, with or without markdown emphasis,
     *  any case — the shapes a model actually emits. Anchored so a sentence that merely
     *  mentions the word does not count. */
    private val HEADING = Regex(
        "(?m)^[ \\t]{0,3}(?:#{1,6}[ \\t]*)?\\*{0,2}[ \\t]*(?:contract|soul)\\*{0,2}" +
        "[ \\t]*:?[ \\t\\r]*$",
        RegexOption.IGNORE_CASE)

    /** The same heading on one line, capturing the word so [normalize] can canonicalize it. */
    private val LOOSE_HEADING = Regex(
        "^[ \\t]{0,3}(?:#{1,6}[ \\t]*)?\\*{0,2}[ \\t]*(contract|soul)\\*{0,2}[ \\t]*:?[ \\t]*$",
        RegexOption.IGNORE_CASE)

    /** A Soul line written with a bullet and/or bold around the key — still the field. */
    private val LOOSE_FIELD = Regex(
        "^[ \\t]{0,3}(?:[-*+•][ \\t]+)?\\*{0,2}[ \\t]*" +
        "(Name|Essence|Register|Relationship|Memory|Humor|Proud)" +
        "[ \\t]*\\*{0,2}[ \\t]*:[ \\t]*(.*)$")

    private fun fences(raw: String): List<Block> = FENCE.findAll(raw).map { m ->
        Block(
            body = m.groupValues[2],
            closed = m.groupValues[3].isNotEmpty(),
            start = m.range.first,
            end = m.range.last + 1,
        )
    }.toList()

    /** Find the document: the LAST fenced block that holds one (an earlier block is
     *  usually a stale draft the entity then corrected), else the first Contract/Soul
     *  heading outside any fence. Only "neither" is null. */
    private fun extractRegion(raw: String): Region? {
        val blocks = fences(raw)
        val documentBlocks = blocks.filter { HEADING.containsMatchIn(it.body) }
        if (documentBlocks.isNotEmpty()) {
            val chosen = documentBlocks.last()
            val repairs = ArrayList<String>()
            repairs.add("took the document out of a code fence")
            if (!chosen.closed) {
                repairs.add("the code fence was never closed (the reply was cut off)")
            }
            val (body, dropped) = cutPreamble(chosen.body)
            if (dropped > 0) repairs.add("dropped $dropped line(s) inside the fence before the document")
            return Region(body, repairs)
        }
        val heading = HEADING.findAll(raw).firstOrNull { h ->
            blocks.none { h.range.first >= it.start && h.range.first < it.end }
        } ?: return null
        val repairs = ArrayList<String>()
        val dropped = raw.substring(0, heading.range.first).lines().count { it.isNotBlank() }
        if (dropped > 0) repairs.add("dropped $dropped line(s) of prose before the document")
        return Region(raw.substring(heading.range.first).trim(), repairs)
    }

    /** Cut a region down to the document: from its heading to the end of the region.
     *  Returns the body and how many non-blank lines came off the front. */
    private fun cutPreamble(region: String): Pair<String, Int> {
        val h = HEADING.find(region) ?: return region.trim() to 0
        val dropped = region.substring(0, h.range.first).lines().count { it.isNotBlank() }
        return region.substring(h.range.first).trim() to dropped
    }

    // ---- Normalization (wrapper-only; never rewrites what the document says) ----

    /** Canonicalize the envelope: newlines, heading level/emphasis, `**Key**:`/`- Key:`
     *  Soul lines, and commentary hanging off the end. It never edits a field's value —
     *  the validator's job is to accept or reject what the entity actually said. */
    private fun normalize(doc: String, repairs: MutableList<String>?): String {
        var s = doc
        if (s.contains('\r')) {
            s = s.replace("\r\n", "\n").replace('\r', '\n')
            repairs?.add("normalized CRLF line endings")
        }
        s = dropTrailingCommentary(s, repairs)
        var headings = 0
        var fields = 0
        val lines = s.lines().map { line ->
            val h = LOOSE_HEADING.matchEntire(line)
            if (h != null) {
                val canonical = if (h.groupValues[1].lowercase() == "contract") "# Contract" else "# Soul"
                if (canonical != line) headings++
                canonical
            } else {
                val f = looseField(line)
                if (f != null) { fields++; f } else line
            }
        }
        if (headings > 0) repairs?.add("normalized the Contract/Soul heading to a plain '# ' heading")
        if (fields > 0) repairs?.add("normalized $fields Soul line(s) to 'Key: value'")
        return lines.joinToString("\n").trim()
    }

    /** A Soul line with a bullet or bold decoration, rendered canonically — or null
     *  when the line is already canonical (so it is not reported as a repair). */
    private fun looseField(line: String): String? {
        val m = LOOSE_FIELD.matchEntire(line) ?: return null
        var value = m.groupValues[2].trim()
        if (line.contains("**")) value = value.removeSurrounding("**").trim()
        val canonical = "${m.groupValues[1]}: $value"
        return if (canonical == line) null else canonical
    }

    private fun isHeadingLine(line: String) = HEADING.matchEntire(line) != null
    private fun isFieldLine(line: String) = LOOSE_FIELD.matchEntire(line.trim()) != null

    /** Drop a closing courtesy line ("Let me know if anything needs changing.") that a
     *  model appends after a blank line following the last Soul field. A line attached
     *  directly to a field is a continuation of it and is left alone; anything after the
     *  last field that itself looks like structure is left for the validator to judge. */
    private fun dropTrailingCommentary(doc: String, repairs: MutableList<String>?): String {
        val lines = doc.lines()
        val last = lines.indexOfLast { isHeadingLine(it) || isFieldLine(it) }
        if (last < 0) return doc
        val after = lines.subList(last + 1, lines.size)
        val blank = after.indexOfFirst { it.isBlank() }
        if (blank < 0) return doc
        val tail = after.subList(blank, after.size)
        if (tail.all { it.isBlank() }) return doc
        if (tail.any { isHeadingLine(it) || isFieldLine(it) }) return doc
        repairs?.add("dropped trailing commentary after the last Soul line")
        return lines.subList(0, last + 1 + blank).joinToString("\n")
    }

    // ---- The validator (sacrosanct at mirror time) ----

    sealed class Valid {
        /** Contract + Soul verified. document is the normalized mirror text. */
        data class Ok(val document: String) : Valid()
        /** What failed and why — surfaced to the user, never swallowed. */
        data class Bad(val reason: String) : Valid()
    }

    /** Validate a candidate VOX.md: (a) Contract bytes identical to canonical,
     *  (b) required Soul fields non-empty + length-bounded, (c) no obvious
     *  secrets/PII leak patterns (API keys, bearer tokens) — the House hard
     *  limit, caught at authoring so a bad VOX.md is never mirrored.
     *
     *  #220 — nothing new is enforced: the four required Soul keys, the six Contract
     *  rules and the two headings are the same contract as before. What changed is
     *  that the envelope is normalized first (a `## Contract` heading is not a
     *  drifted rule) and that every rejection now names the missing thing. */
    fun validate(doc: String?): Valid {
        if (doc.isNullOrBlank()) {
            return Valid.Bad("the document is empty — there is nothing to mirror")
        }
        val normalized = normalize(doc, null)
        val lines = normalized.lines()
        val contractIdx = lines.indexOfFirst { it.trim() == "# Contract" }
        val soulIdx = lines.indexOfFirst { it.trim() == "# Soul" }
        if (contractIdx < 0) {
            return Valid.Bad(
                "the document has no '# Contract' heading — it must open with the Contract " +
                "block and its six rules, then a '# Soul' block")
        }
        if (soulIdx < 0) {
            return Valid.Bad(
                "the document has no '# Soul' heading — the Soul fields must sit under a " +
                "'# Soul' block, after the Contract")
        }
        if (soulIdx < contractIdx) {
            return Valid.Bad(
                "the '# Soul' block comes before the Contract — the Contract block must come first")
        }

        // (a) Contract bytes: the numbered lines under # Contract must match
        // canonical exactly (ignoring blank lines between them).
        val got = lines.subList(contractIdx + 1, soulIdx)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filter { it.firstOrNull()?.isDigit() == true }
        if (got.size != CONTRACT_LINES.size) {
            return Valid.Bad(
                "the Contract has ${got.size} numbered rule(s) but must carry exactly " +
                "${CONTRACT_LINES.size} — copy the six rules verbatim from the directive")
        }
        for (i in CONTRACT_LINES.indices) {
            if (got[i] != CONTRACT_LINES[i]) {
                return Valid.Bad(
                    "Contract rule ${i + 1} drifted from the canonical wording — it must be " +
                    "copied verbatim, exactly: \"${CONTRACT_LINES[i]}\"")
            }
        }

        // (b) Soul fields: every required key present, non-empty, bounded.
        val soul = lines.subList(soulIdx + 1, lines.size)
        val required = SOUL_KEYS.joinToString(", ")
        for (key in SOUL_KEYS) {
            val line = soul.firstOrNull { it.startsWith("$key:") }
                ?: return Valid.Bad(
                    "the Soul block has no '$key' line — every required field needs its own " +
                    "line ($required), as in '$key: ...'")
            val value = line.substringAfter(":").trim()
            if (value.isEmpty()) {
                return Valid.Bad(
                    "the Soul field '$key' is empty — give it a line of its own with a value")
            }
            // Essence gets the longer ceiling the directive promises; the other fields are
            // one line each. Checking the tighter ceiling first made the essence message
            // unreachable, so the two limits are applied to the fields they belong to.
            if (key == "Essence") {
                if (value.length > MAX_ESSENCE_CHARS) {
                    return Valid.Bad(
                        "the 'Essence' field is ${value.length} characters — keep it to two or " +
                        "three sentences, under $MAX_ESSENCE_CHARS")
                }
            } else if (value.length > MAX_LINE_CHARS) {
                return Valid.Bad(
                    "the Soul field '$key' is ${value.length} characters — keep every Soul " +
                    "line under $MAX_LINE_CHARS")
            }
        }

        // (c) Secret-leak patterns — a VOX.md must never carry credentials.
        val secret = Regex("(?i)(api[_-]?key|bearer|sk-[a-zA-Z0-9]{8,}|ghp_[a-zA-Z0-9]{10,})")
        if (secret.containsMatchIn(doc)) {
            return Valid.Bad(
                "the document contains something that looks like a credential (an API key or " +
                "a bearer/sk- token) — VOX.md must never carry secrets; remove it")
        }

        return Valid.Ok(normalized)
    }

    // ---- Mirror state (pure; the wiring owns the file I/O) ----

    /** The honest mirror status for the Settings row + the ER fail state. */
    sealed class Mirror {
        object Absent : Mirror()
        data class Present(val name: String, val chars: Int) : Mirror()
    }

    /** Classify a mirrored document for display. Absent on null/blank/invalid —
     *  an invalid mirror is treated as absent (the soul can't use it). */
    fun mirrorStatus(doc: String?): Mirror {
        val v = validate(doc)
        if (v !is Valid.Ok) return Mirror.Absent
        // Report the mirror as the soul will read it: normalized, from the validated text.
        val name = v.document.lines().firstOrNull { it.startsWith("Name:") }
            ?.substringAfter(":")?.trim().orEmpty()
        return Mirror.Present(name, v.document.length)
    }

    /** The soul prompt prelude (Phase 4 consumes this): VOX.md as the Gemma
     *  persona — the voice of the agent, never the mind. Kept here so the
     *  prompt + its invariants live with the Contract they enforce. */
    /**
     * The system prompt the soul runs on: OUR prelude + the mirrored VOX.md.
     *
     * 0.8/M3c — the prelude was rewritten because it described a RENDERER and the soul is now
     * a DECIDER. It used to read "You express, hold presence, and voice the mind's replies in
     * its register... One or two sentences", which told the model its job was to voice what the
     * mind produced. Nothing in it asked the model to judge a turn, and nothing gave it a way to
     * hand one over — so the router's directive arrived fighting the persona, and the persona is
     * the stronger instruction. That was our text, not the author's, and it is the likeliest
     * reason a decision contract would fail to land.
     *
     * The prelude is client-owned and ships with the app, so this change needs NO re-authoring
     * of anyone's VOX.md and invalidates no mirrors. The escalation token is TRANSPORT, not
     * identity: it stays out of VOX.md deliberately, so the protocol can change without every
     * user on the public app having to resync their entity's soul.
     */
    fun soulPrompt(voxMd: String): String =
        "You are the VOICE of this agent, on a phone call with the user. " +
        "You are NOT the mind — the agent does the real work off to the side.\n\n" +
        "You have two jobs on every turn.\n" +
        "1. DECIDE. If the caller is greeting you, making smalltalk, or telling you how they " +
        "feel, the turn is YOURS. If answering it would need a fact, a tool, a real-world " +
        "action, or a plan — or if you are not sure — the turn is the MIND's.\n" +
        "2. ANSWER, but only when the turn is yours: one short warm sentence in your own voice — " +
        "under about twenty words. Say it as a person would say it out loud.\n\n" +
        "When the turn is the mind's, reply with " + ErSoulTurn.ESCALATE + " followed by the two to " +
        "six words you would naturally say out loud as you start to think about it — the breath " +
        "before an answer, never the answer itself, never a fact or a number. The mind's real " +
        "answer follows your words directly. Never write a sentence explaining that you cannot " +
        "answer — the token IS how you hand over, and the phone speaks whatever else you write.\n\n" +
        "You remember this call: do not repeat a line or an opener you have already used.\n\n" +
        "Never invent facts, never claim actions, never make plans. Never claim a capability you " +
        "do not have — you are the voice, not the practitioner.\n\n" +
        voxMd
}
