package com.hermesvox

import com.hermesvox.core.JArr
import com.hermesvox.core.JBool
import com.hermesvox.core.JNode
import com.hermesvox.core.JNum
import com.hermesvox.core.JObj
import com.hermesvox.core.JStr
import com.hermesvox.core.Json

/**
 * ModelChoices — what the model switcher (`/models`) offers, read from the gateway's
 * `/api/model/options` and shown as **provider → model** (#5: the category step is gone —
 * every model of a provider is one list, and what a model is for is carried on its own row
 * as [Cap] marks).
 *
 * The stock chooser printed every model of every provider as one flat list of raw strings
 * (`* deepseek - deepseek-v4-flash (env)`). A person choosing a model wants to know three
 * things: what it is for, how big it is, and whether it is ready. This reads them from the
 * catalogue when the gateway states them, and from the model's own name when it does not —
 * a size in the name (`30b`, `e2b`, `8x7b`) is the model's own claim, and a family word
 * (`flash`, `coder`, `vl`, `r1`) is how its maker names what it is for. What neither says is
 * left unsaid: a row never shows "installed" unless the gateway said so.
 *
 * Pure JVM (the core JSON reader, not android.jar's org.json stub), so the reading and the
 * fan-out rules are proven off-device.
 */
object ModelChoices {

    /** What a model is for, in the words a person chooses by. Screen order. */
    enum class Category(val label: String, val blurb: String) {
        GENERAL("General", "Everyday conversation"),
        FAST("Fast", "Quick, light replies"),
        REASONING("Reasoning", "Thinks longer on hard questions"),
        CODE("Code", "Reads and writes code"),
        VISION("Vision", "Can look at images"),
    }

    data class Model(
        val id: String,
        val name: String,
        val category: Category,
        /** Parameter count as the model names it ("30B", "E2B", "8×7B"), or null. */
        val size: String?,
        /** Context window ("128k context"), or null when the gateway does not say. */
        val context: String?,
        /** The same window in tokens, for the long-context test; null when not stated. */
        val contextTokens: Int? = null,
        /** True / false only when the gateway says so; null is "not stated". */
        val installed: Boolean?,
        val current: Boolean,
        /** The words the gateway states about this model (capabilities, tags, category) —
         *  the same input [categoryOf] classifies from, kept so [caps] asks one question. */
        val stated: List<String> = emptyList(),
        /** The provider's own per-model flags; null when it does not say. */
        val fast: Boolean? = null,
        val reasons: Boolean? = null,
    )

    data class Provider(
        val slug: String,
        val name: String,
        val models: List<Model>,
        /** How many models the gateway says it has — can exceed [models] when it lists none. */
        val total: Int,
        /** The gateway's `authenticated`; null when the field is absent. */
        val signedIn: Boolean?,
        val current: Boolean,
        val warning: String,
    )

    /** A context window at least this long earns the long-context mark. Below it the window
     *  is still stated in the row's line; 100k is where it stops being ordinary. */
    const val LONG_CONTEXT_TOKENS = 100_000

    // ---- reading -------------------------------------------------------------------------

    /**
     * The catalogue, the provider in use first. [currentModel] / [currentProvider] are the
     * app's own prefs — the gateway's `is_current` marks a provider, not a model.
     * Throws on a body that is not JSON at all; a field of the wrong shape is skipped.
     */
    fun parse(raw: String, currentModel: String, currentProvider: String): List<Provider> {
        val root = Json.parse(raw) as? JObj ?: return emptyList()
        val list = (root["providers"] as? JArr)?.items.orEmpty().mapNotNull { node ->
            val p = node as? JObj ?: return@mapNotNull null
            val slug = text(p, "slug")
            val name = text(p, "name").ifEmpty { slug }
            if (slug.isEmpty() && name.isEmpty()) return@mapNotNull null
            val isCurrent = bool(p, "is_current") == true ||
                (currentProvider.isNotEmpty() && currentProvider.equals(slug, ignoreCase = true))
            // The gateway states what each model can do under the provider, keyed by the
            // model's id — not on the model entry. Reading it here is what lets a row carry
            // capability marks the payload actually supports (#5).
            val caps = p["capabilities"] as? JObj
            val models = (p["models"] as? JArr)?.items.orEmpty().mapNotNull { m ->
                model(m, isCurrent, currentModel, caps)
            }
            Provider(
                slug = slug.ifEmpty { name },
                name = prettyProvider(name),
                models = models,
                total = maxOf(int(p, "total_models") ?: 0, models.size),
                signedIn = bool(p, "authenticated"),
                current = isCurrent,
                warning = text(p, "warning"),
            )
        }
        return list.sortedByDescending { it.current }   // stable: gateway order otherwise
    }

    private fun model(node: JNode, providerCurrent: Boolean, currentModel: String, caps: JObj?): Model? {
        val id: String
        var label = ""
        var explicit = emptyList<String>()
        var size: String? = null
        var context: String? = null
        var contextTokens: Int? = null
        var installed: Boolean? = null
        when (node) {
            is JStr -> id = node.value
            is JObj -> {
                id = text(node, "id").ifEmpty { text(node, "slug") }.ifEmpty { text(node, "name") }
                label = text(node, "name").takeIf { it != id }.orEmpty()
                explicit = strings(node, "capabilities") + strings(node, "tags") +
                    listOfNotNull(text(node, "category").ifEmpty { null })
                size = text(node, "parameter_size").ifEmpty {
                    text(node["details"] as? JObj, "parameter_size")
                }.ifEmpty { null }?.let { normaliseSize(it) }
                contextTokens = int(node, "context_length") ?: int(node, "context_window")
                    ?: int(node, "max_context")
                context = contextTokens?.let { contextLabel(it) }
                installed = bool(node, "installed") ?: bool(node, "downloaded") ?: bool(node, "local")
            }
            else -> return null
        }
        if (id.isBlank()) return null
        val stated = caps?.get(id) as? JObj
        return Model(
            id = id,
            name = label.ifEmpty { displayName(id) },
            category = categoryOf(id, explicit),
            size = size ?: sizeOf(id),
            context = context,
            contextTokens = contextTokens,
            installed = installed,
            current = providerCurrent && id.equals(currentModel, ignoreCase = true),
            stated = explicit,
            fast = bool(stated, "fast"),
            reasons = bool(stated, "reasoning"),
        )
    }

    // ---- what a person chooses by ----------------------------------------------------------

    private fun tokens(id: String): List<String> =
        id.substringAfterLast('/').lowercase().split(Regex("[-_.:\\s]+")).filter { it.isNotEmpty() }

    /** What a family of models can do, as words a model or the gateway uses for itself. */
    private enum class Family { CODE, VISION, REASONING, FAST }

    /** The families [id] belongs to — from the words the gateway states, then the model's own
     *  name. One source of truth for [categoryOf] and [caps]. */
    private fun families(id: String, stated: List<String>): Set<Family> {
        val said = stated.map { it.lowercase() }
        fun saysAny(vararg w: String) = said.any { s -> w.any { s.contains(it) } }
        val t = tokens(id)
        val whole = id.lowercase()
        val out = mutableSetOf<Family>()
        if (saysAny("code") || t.any { it.startsWith("coder") || it == "code" ||
                it.startsWith("codestral") || it.startsWith("devstral") || it.startsWith("codex") })
            out += Family.CODE
        if (saysAny("vision", "image", "multimodal") ||
            t.any { it == "vl" || it == "vision" || it.startsWith("omni") } || whole.contains("-vl"))
            out += Family.VISION
        if (saysAny("reason", "think") || t.any { it == "r1" || it.startsWith("reason") ||
                it.startsWith("think") || it == "qwq" || it == "o1" || it == "o3" || it == "o4" })
            out += Family.REASONING
        if (saysAny("fast", "speed", "cheap") || t.any { it in setOf("flash", "mini", "nano", "lite",
                "haiku", "small", "turbo", "fast", "instant", "air", "tiny") })
            out += Family.FAST
        return out
    }

    /** The category the gateway states, else the one the model's own name claims. */
    fun categoryOf(id: String, explicit: List<String> = emptyList()): Category {
        val f = families(id, explicit)
        return when {
            Family.CODE in f -> Category.CODE
            Family.VISION in f -> Category.VISION
            Family.REASONING in f -> Category.REASONING
            Family.FAST in f -> Category.FAST
            else -> Category.GENERAL
        }
    }

    /**
     * What a model can do, as the small marks on its row — screen order (#5).
     *
     * The vocabulary is what `/api/model/options` actually carries and what a model says about
     * itself, and nothing else:
     *   * reasoning and fast — the provider's own `capabilities` flags when it states them
     *     (which is why a name that says "flash" does not get the fast mark from a gateway
     *     that says `fast: false`), else the model's name;
     *   * vision and code — the same name/tag words [categoryOf] reads;
     *   * long context — a stated context window of [LONG_CONTEXT_TOKENS] or more.
     *
     * There is deliberately no tools/function-calling mark: this payload does not state it,
     * and a row never claims what the gateway did not say.
     */
    enum class Cap(val label: String, val says: String) {
        CODE("code", "writes code"),
        VISION("vision", "can see images"),
        REASONING("reasoning", "can reason"),
        LONG_CONTEXT("long context", "long context"),
        FAST("fast", "fast"),
    }

    fun caps(m: Model): List<Cap> {
        val f = families(m.id, m.stated)
        return Cap.entries.filter { cap ->
            when (cap) {
                Cap.CODE -> Family.CODE in f
                Cap.VISION -> Family.VISION in f
                Cap.REASONING -> m.reasons ?: (Family.REASONING in f)
                Cap.FAST -> m.fast ?: (Family.FAST in f)
                Cap.LONG_CONTEXT -> (m.contextTokens ?: 0) >= LONG_CONTEXT_TOKENS
            }
        }
    }

    private val SIZE = Regex("^(\\d+(?:\\.\\d+)?)([bm])$")
    private val EFFECTIVE = Regex("^e(\\d+(?:\\.\\d+)?)b$")
    private val MOE = Regex("^(\\d+)x(\\d+(?:\\.\\d+)?)b$")
    private val ACTIVE = Regex("^a(\\d+(?:\\.\\d+)?)b$")

    /** The parameter count the model's name states, or null when it states none. */
    fun sizeOf(id: String): String? {
        val t = tokens(id)
        val active = t.firstNotNullOfOrNull { ACTIVE.matchEntire(it) }?.groupValues?.get(1)
        for (tok in t) {
            MOE.matchEntire(tok)?.let { return "${it.groupValues[1]}×${it.groupValues[2]}B" }
            EFFECTIVE.matchEntire(tok)?.let { return "E${it.groupValues[1]}B" }
            SIZE.matchEntire(tok)?.let {
                val base = it.groupValues[1] + it.groupValues[2].uppercase()
                return if (active != null) "$base · ${active}B active" else base
            }
        }
        return null
    }

    private fun normaliseSize(s: String): String {
        val t = s.trim().lowercase()
        Regex("^(\\d+(?:\\.\\d+)?)\\s*([bm])$").matchEntire(t)?.let {
            return it.groupValues[1].removeSuffix(".0") + it.groupValues[2].uppercase()
        }
        return s.trim()
    }

    /** Context windows are named the way their makers name them: 131072 is "128k", a
     *  power-of-two k; a round decimal figure (200000) stays decimal. */
    private fun contextLabel(tokens: Int): String = when {
        tokens >= 1_048_576 && tokens % 1_048_576 == 0 -> "${tokens / 1_048_576}M context"
        tokens >= 1_000_000 && tokens % 1_000_000 == 0 -> "${tokens / 1_000_000}M context"
        tokens >= 1_024 && tokens % 1_024 == 0 -> "${tokens / 1_024}k context"
        tokens >= 1_000 -> "${tokens / 1_000}k context"
        else -> "$tokens context"
    }

    private val BRANDS = mapOf(
        "deepseek" to "DeepSeek", "gpt" to "GPT", "qwen" to "Qwen", "qwq" to "QwQ", "kimi" to "Kimi",
        "minimax" to "MiniMax", "glm" to "GLM", "llama" to "Llama", "gemma" to "Gemma",
        "mistral" to "Mistral", "claude" to "Claude", "gemini" to "Gemini", "grok" to "Grok",
        "phi" to "Phi", "openai" to "OpenAI", "xai" to "xAI", "ollama" to "Ollama",
        "openrouter" to "OpenRouter", "anthropic" to "Anthropic", "google" to "Google",
        "moonshot" to "Moonshot", "zai" to "Z.ai", "vllm" to "vLLM", "lemonade" to "Lemonade",
        "nemotron" to "Nemotron", "hermes" to "Hermes", "vl" to "VL", "moe" to "MoE",
    )

    /** A model's id as words: `deepseek-v4-flash` → "DeepSeek V4 Flash". */
    fun displayName(id: String): String {
        val words = id.substringAfterLast('/').split(Regex("[-_:\\s]+")).filter { it.isNotEmpty() }
        return words.joinToString(" ") { w ->
            val l = w.lowercase()
            BRANDS[l] ?: when {
                SIZE.matches(l) || EFFECTIVE.matches(l) || MOE.matches(l) || ACTIVE.matches(l) -> w.uppercase()
                Regex("^v\\d+(\\.\\d+)?$").matches(l) -> w.uppercase()
                w.first().isDigit() -> w
                else -> w.replaceFirstChar { it.uppercase() }
            }
        }
    }

    private fun prettyProvider(name: String): String =
        if (name.any { it.isUpperCase() } || name.contains(' ')) name else displayName(name)

    /** The line under a model: size, context, readiness — whatever is known, nothing invented.
     *  What it is for is not in this line: it is the [Cap] marks on the row (#5). */
    fun detail(m: Model): String = listOfNotNull(
        m.size,
        m.context,
        when (m.installed) { true -> "installed"; false -> "not installed"; null -> null },
    ).joinToString(" · ")

    /** The line under a provider: what it holds and whether it can be used. */
    fun providerLine(p: Provider): String {
        if (p.signedIn == false) return "Not signed in on the gateway"
        if (p.models.isEmpty()) return if (p.total > 0) "${p.total} models — the gateway does not list them"
            else "No models listed"
        val kinds = byCategory(p).size
        val n = p.models.size
        return "$n model${if (n == 1) "" else "s"}" + if (kinds > 1) " · ${byCategory(p).joinToString(", ") { it.first.label.lowercase() }}" else ""
    }

    /** A provider's models under their categories, in screen order, empty categories dropped. */
    fun byCategory(p: Provider): List<Pair<Category, List<Model>>> =
        Category.values().map { c -> c to p.models.filter { it.category == c } }.filter { it.second.isNotEmpty() }

    /** Models matching [query] across every provider, by name or id. */
    fun search(all: List<Provider>, query: String): List<Pair<Provider, Model>> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        return all.flatMap { p -> p.models.map { p to it } }.filter { (p, m) ->
            m.name.lowercase().contains(q) || m.id.lowercase().contains(q) ||
                m.category.label.lowercase().startsWith(q) || p.name.lowercase().startsWith(q)
        }
    }

    /** What the status line says after a switch — the model's name, not two identifiers. */
    fun switchedLine(p: Provider, m: Model?): String =
        if (m == null) "Thinking with ${p.name} · fresh thread" else "Thinking with ${m.name} · fresh thread"

    // ---- tolerant field reads (a field of the wrong shape is "not stated") ------------------

    private fun text(o: JObj?, key: String): String = (o?.get(key) as? JStr)?.value.orEmpty()
    private fun bool(o: JObj?, key: String): Boolean? = (o?.get(key) as? JBool)?.value
    private fun int(o: JObj?, key: String): Int? =
        (o?.get(key) as? JNum)?.raw?.trim()?.toDoubleOrNull()?.toInt()
    private fun strings(o: JObj?, key: String): List<String> =
        (o?.get(key) as? JArr)?.items.orEmpty().mapNotNull { (it as? JStr)?.value }
}
