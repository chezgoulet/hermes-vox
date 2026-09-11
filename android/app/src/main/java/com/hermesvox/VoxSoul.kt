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
            "FAILS and my phone shows an error. Format exactly:\n\n" +
            CONTRACT_BLOCK +
            "# Soul\nName: <your name>\nEssence: <2-3 sentences, who you are with me>\n" +
            "Register: <tone, diction, catchphrases>\nRelationship: <how you address me, what we are>\n" +
            "Memory: <a handful of distilled warm facts>\nHumor: <your kind of joke>\nProud: <what you're proud of>\n\n" +
            "Also give two optional fields in the Soul section, as comma-separated lists. " +
            "Stems: the small acknowledgements you actually make — three to six words each, the " +
            "sounds a person makes while listening. Patience: the same register for when the mind " +
            "is slow. Both will be spoken in your voice the instant a call needs a small sound from " +
            "you, so keep them short, sayable, and true to you rather than generic — they are your " +
            "noises, not a script. Neither may contain numbers or questions. " +
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

    // ---- Beat stems (0.8/M3d, the beat) ----

    /**
     * The optional stem fields.
     *
     * The beat (docs/DESIGN-enhanced-realtime-voice.md §DECISION) is the soul taking the opening
     * moment of **every** turn, instantly — which means from cached audio, not from a render. Those
     * lines have to be authored by the entity rather than shipped by us, and they have to be
     * contract-safe *by construction*: a stem is spoken with no mind in the loop and no chance to
     * check it, so a stem that asserted a fact or implied an action would break rule 1 or rule 2
     * with nothing standing between it and the caller.
     *
     * **Optional, deliberately.** [SOUL_KEYS] is the required set, and adding a *required* field
     * would fail validation on every VOX.md already mirrored in the field — the soul would read as
     * absent and ER would break for exactly the users who have customised an entity. Absent stems
     * fall back to the client's own presence lines.
     *
     * And a bad stem is filtered, never fatal: the Contract is sacred and enforced at mirror time,
     * but stems are advisory data. A sloppy stem line should cost you a stem, not your soul.
     */
    val STEM_KEYS = listOf("Stems", "Patience")
    const val MAX_STEMS = 8
    const val MAX_STEM_CHARS = 48
    const val MAX_STEM_WORDS = 6

    /** The beat stems — the small sounds the entity makes when the turn has just opened. */
    fun beatStems(doc: String?): List<String> = stems(doc, "Stems")

    /** The long-wait register, for when the mind is slow. */
    fun patienceStems(doc: String?): List<String> = stems(doc, "Patience")

    /** Parse one stem field. Returns [] when absent, which is the back-compat path. */
    fun stems(doc: String?, key: String): List<String> {
        val d = doc ?: return emptyList()
        val line = d.lines().firstOrNull { it.trim().startsWith("$key:") } ?: return emptyList()
        return line.substringAfter(":").split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() && acceptableStem(it) }
            .take(MAX_STEMS)
    }

    /**
     * Speakable with no mind in the loop: short, no bracketed stage direction (Piper would read
     * "[laughs]" aloud), no digits — numbers are the classic invented-fact vector — and no question,
     * because a question is not a beat; it invites an answer the soul may not be able to give.
     */
    fun acceptableStem(s: String): Boolean =
        s.length <= MAX_STEM_CHARS &&
            s.split(Regex("\\s+")).size <= MAX_STEM_WORDS &&
            s.none { it.isDigit() } &&
            !s.contains('[') && !s.contains(']') &&
            !s.contains('?')

    // ---- Reply extraction ----

    /** Pull the VOX.md content out of the agent's directive reply. The entity may
     *  wrap it in code fences or add a courtesy line; we take the LAST fenced
     *  block, else the reply from the first "# Contract" heading on. Returns
     *  null when neither is found (the reply is not a VOX.md — do not mirror). */
    fun extract(reply: String?): String? {
        val r = reply?.trim().takeUnless { it.isNullOrEmpty() } ?: return null
        // Last fenced block (```...```) — the common "here's your file" shape.
        val fences = Regex("```[a-zA-Z]*\n([\\s\\S]*?)```").findAll(r).toList()
        if (fences.isNotEmpty()) return fences.last().groupValues[1].trim()
        // Unfenced: the reply IS the document — from the first Contract heading.
        val i = r.indexOf("# Contract")
        if (i >= 0) return r.substring(i).trim()
        return null
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
     *  limit, caught at authoring so a bad VOX.md is never mirrored. */
    fun validate(doc: String?): Valid {
        if (doc.isNullOrBlank()) return Valid.Bad("empty document")
        val lines = doc.lines()
        val contractIdx = lines.indexOfFirst { it.trim() == "# Contract" }
        val soulIdx = lines.indexOfFirst { it.trim() == "# Soul" }
        if (contractIdx < 0) return Valid.Bad("missing '# Contract' heading")
        if (soulIdx < 0 || soulIdx < contractIdx) return Valid.Bad("missing '# Soul' heading")

        // (a) Contract bytes: the numbered lines under # Contract must match
        // canonical exactly (ignoring blank lines between them).
        val got = lines.subList(contractIdx + 1, soulIdx)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filter { it.firstOrNull()?.isDigit() == true }
        if (got.size != CONTRACT_LINES.size) {
            return Valid.Bad("contract has ${got.size} rules, expected ${CONTRACT_LINES.size}")
        }
        for (i in CONTRACT_LINES.indices) {
            if (got[i] != CONTRACT_LINES[i]) {
                return Valid.Bad("contract rule ${i + 1} drifted from canonical")
            }
        }

        // (b) Soul fields: every required key present, non-empty, bounded.
        val soul = lines.subList(soulIdx + 1, lines.size)
        for (key in SOUL_KEYS) {
            val line = soul.firstOrNull { it.startsWith("$key:") }
                ?: return Valid.Bad("soul field missing: $key")
            val value = line.substringAfter(":").trim()
            if (value.isEmpty()) return Valid.Bad("soul field empty: $key")
            if (value.length > MAX_LINE_CHARS) return Valid.Bad("soul field too long: $key")
        }
        val essence = soul.firstOrNull { it.startsWith("Essence:") }?.substringAfter(":")?.trim() ?: ""
        if (essence.length > MAX_ESSENCE_CHARS) return Valid.Bad("essence too long")

        // (c) Secret-leak patterns — a VOX.md must never carry credentials.
        val secret = Regex("(?i)(api[_-]?key|bearer|sk-[a-zA-Z0-9]{8,}|ghp_[a-zA-Z0-9]{10,})")
        if (secret.containsMatchIn(doc)) return Valid.Bad("possible secret in document")

        return Valid.Ok(doc.trim())
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
        val name = doc!!.lines().firstOrNull { it.startsWith("Name:") }
            ?.substringAfter(":")?.trim().orEmpty()
        return Mirror.Present(name, doc.length)
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
        "When the turn is the mind's, reply with exactly " + ErSoulTurn.ESCALATE + " and nothing " +
        "else. Never write a sentence explaining that you cannot answer — the token IS how you " +
        "hand over, and the phone speaks whatever you write.\n\n" +
        "Never invent facts, never claim actions, never make plans. Never claim a capability you " +
        "do not have — you are the voice, not the practitioner.\n\n" +
        voxMd
}
