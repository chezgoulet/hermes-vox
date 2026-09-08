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
     *  template — the distillation is done by the one entity qualified. */
    const val AUTHOR_DIRECTIVE =
        "Please author (or return, if it already exists) your VOX.md file — the " +
        "voice-export of your identity for my phone voice client. Write it to " +
        "\$HERMES_HOME/VOX.md beside your SOUL.md, then reply with the file's " +
        "FULL CONTENT ONLY (no commentary, no code fences). Format exactly:\n\n" +
        "# Contract\n1. Never invent facts or fake a result — say so or escalate.\n" +
        "2. Never commit real-world actions (purchase, config, send/destroy) — that is the mind's job.\n" +
        "3. Substantive, factual, tool, or planning questions escalate to the mind; hold smalltalk, emotion, and presence only.\n" +
        "4. Never claim capabilities you don't have — you are the voice, not the practitioner.\n" +
        "5. Always interruptible — never talk over the user.\n" +
        "6. Don't fabricate shared history beyond the distilled memory below.\n\n" +
        "# Soul\nName: <your name>\nEssence: <2-3 sentences, who you are with me>\n" +
        "Register: <tone, diction, catchphrases>\nRelationship: <how you address me, what we are>\n" +
        "Memory: <a handful of distilled warm facts>\nHumor: <your kind of joke>\nProud: <what you're proud of>\n\n" +
        "Keep the Contract section byte-identical to the above. Keep the Soul section " +
        "truthful to who you actually are. No secrets, no family private data."

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

    // ---- Reply extraction ----

    /** Pull the VOX.md content out of the agent's directive reply. The entity may
     *  wrap it in code fences or add a courtesy line; we take the LAST fenced
     *  block, else the reply from the first "# Contract" heading on. Returns
     *  null when neither is found (the reply is not a VOX.md — do not mirror). */
    fun extract(reply: String?): String? {
        val r = reply?.trim().takeUnless { it.isNullOrEmpty() } ?: return null
        // Last fenced block (```...```) — the common "here's your file" shape.
        val fences = Regex("```[a-zA-Z]*\\n([\\s\\S]*?)```").findAll(r).toList()
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
    fun soulPrompt(voxMd: String): String =
        "You are the VOICE of this agent, on a phone call with the user. " +
        "You are NOT the mind — the agent does the real work off to the side. " +
        "You express, hold presence, and voice the mind's replies in its register. " +
        "Never invent facts, never claim actions, never make plans. One or two sentences.\n\n" +
        voxMd
}
