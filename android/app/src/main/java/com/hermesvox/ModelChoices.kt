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
 * `/api/model/options` and fanned out **provider → category → model**.
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
        /** True / false only when the gateway says so; null is "not stated". */
        val installed: Boolean?,
        val current: Boolean,
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

    /** At or below this many models a provider opens straight to its models (under category
     *  headers): a category step in front of six rows costs a tap and saves nothing. */
    const val FLAT_MAX = 8

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
            val models = (p["models"] as? JArr)?.items.orEmpty().mapNotNull { m ->
                model(m, isCurrent, currentModel)
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

    private fun model(node: JNode, providerCurrent: Boolean, currentModel: String): Model? {
        val id: String
        var label = ""
        var explicit = emptyList<String>()
        var size: String? = null
        var context: String? = null
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
                context = (int(node, "context_length") ?: int(node, "context_window")
                    ?: int(node, "max_context"))?.let { contextLabel(it) }
                installed = bool(node, "installed") ?: bool(node, "downloaded") ?: bool(node, "local")
            }
            else -> return null
        }
        if (id.isBlank()) return null
        return Model(
            id = id,
            name = label.ifEmpty { displayName(id) },
            category = categoryOf(id, explicit),
            size = size ?: sizeOf(id),
            context = context,
            installed = installed,
            current = providerCurrent && id.equals(currentModel, ignoreCase = true),
        )
    }

    // ---- what a person chooses by ----------------------------------------------------------

    private fun tokens(id: String): List<String> =
        id.substringAfterLast('/').lowercase().split(Regex("[-_.:\\s]+")).filter { it.isNotEmpty() }

    /** The category the gateway states, else the one the model's own name claims. */
    fun categoryOf(id: String, explicit: List<String> = emptyList()): Category {
        val said = explicit.map { it.lowercase() }
        fun saysAny(vararg w: String) = said.any { s -> w.any { s.contains(it) } }
        if (saysAny("code")) return Category.CODE
        if (saysAny("vision", "image", "multimodal")) return Category.VISION
        if (saysAny("reason", "think")) return Category.REASONING
        if (saysAny("fast", "speed", "cheap")) return Category.FAST
        val t = tokens(id)
        val whole = id.lowercase()
        if (t.any { it.startsWith("coder") || it == "code" || it.startsWith("codestral") ||
                it.startsWith("devstral") || it.startsWith("codex") }) return Category.CODE
        if (t.any { it == "vl" || it == "vision" || it.startsWith("omni") } || whole.contains("-vl")) return Category.VISION
        if (t.any { it == "r1" || it.startsWith("reason") || it.startsWith("think") || it == "qwq" ||
                it == "o1" || it == "o3" || it == "o4" }) return Category.REASONING
        if (t.any { it in setOf("flash", "mini", "nano", "lite", "haiku", "small", "turbo", "fast",
                "instant", "air", "tiny") }) return Category.FAST
        return Category.GENERAL
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

    private fun contextLabel(tokens: Int): String = when {
        tokens >= 1_000_000 -> "${tokens / 1_000_000}M context"
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
     *  [withCategory] adds what it is for, for rows shown outside their category header. */
    fun detail(m: Model, withCategory: Boolean = false): String = listOfNotNull(
        if (withCategory) m.category.label else null,
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

    /** Whether opening [p] shows its categories first (a second fan-out) or its models. */
    fun showsCategories(p: Provider): Boolean = p.models.size > FLAT_MAX && byCategory(p).size > 1

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
