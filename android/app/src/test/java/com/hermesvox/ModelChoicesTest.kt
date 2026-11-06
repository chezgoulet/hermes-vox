package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The model switcher's reading of /api/model/options: provider → model, and the marks. */
class ModelChoicesTest {

    /** The live gateway's shape (models as plain strings) plus a local provider that sends
     *  objects, and a provider that lists nothing. */
    private val raw = """
        {"providers":[
          {"slug":"openrouter","name":"OpenRouter","is_current":false,"authenticated":true,"total_models":10,
           "models":["kimi-k3","minimax-m3","qwen3-coder-480b-a35b","qwen3-vl-235b-a22b","gpt-5-mini",
                     "gpt-5","o4-mini","glm-4.6","gemini-2.5-flash","deepseek-r1"]},
          {"slug":"deepseek","name":"deepseek","is_current":true,"authenticated":true,"total_models":2,
           "models":["deepseek-v4-flash","deepseek-v4"]},
          {"slug":"ollama","name":"ollama","authenticated":true,"models":[
             {"id":"gemma4:e2b","name":"Gemma 4 E2B","details":{"parameter_size":"5.1B"},"installed":true,"context_length":32768},
             {"id":"llama3.1:8b","installed":false}]},
          {"slug":"anthropic","name":"Anthropic","authenticated":false,"total_models":0,"models":[]},
          {"slug":"weird","name":"Weird","models":[42, null, {"id":""}, "ok-model"]}
        ]}
    """.trimIndent()

    private val all = ModelChoices.parse(raw, currentModel = "deepseek-v4-flash", currentProvider = "deepseek")

    /**
     * The picker's order (#6): the one in use, then the providers this host can offer, then
     * the rest — and within a rank the gateway's own order is kept.
     */
    @Test fun theProviderInUseLeadsThenTheConfiguredOnesThenTheRest() {
        assertEquals(listOf("deepseek", "openrouter", "ollama", "weird", "anthropic"), all.map { it.slug })
        assertTrue(all.first().current)
        assertEquals("DeepSeek", all.first().name)   // a lower-case slug-as-name is made readable
        assertEquals("OpenRouter", all[1].name)       // a name the gateway cased is kept
        // The empty, unauthenticated provider is still shown — at the end, where it is
        // reachable and still says why it cannot be chosen.
        assertTrue(all.last().models.isEmpty())
        assertEquals("Not signed in on the gateway", ModelChoices.providerLine(all.last()))
        assertEquals(listOf(0, 1, 1, 1, 2), all.map { ModelChoices.rank(it) })
    }

    /**
     * #6 in the shape the owner's host serves: 54 providers, most of them empty and unsigned,
     * with the one in use fifteenth in the payload. Every provider is still rendered; the
     * interesting ones are at the top; and nothing is dropped without being named.
     */
    @Test fun everyProviderInAFiftyFourProviderPayloadIsShownWithTheUsefulOnesFirst() {
        val sb = StringBuilder("{\"providers\":[")
        for (i in 0 until 14) {
            if (i > 0) sb.append(',')
            sb.append("{\"slug\":\"empty-$i\",\"name\":\"Empty $i\",\"authenticated\":false," +
                "\"total_models\":0,\"models\":[]}")
        }
        sb.append(",{\"slug\":\"deepseek\",\"name\":\"DeepSeek\",\"is_current\":true," +
            "\"authenticated\":true,\"total_models\":2," +
            "\"models\":[\"deepseek-flash\",\"deepseek-v4-pro\"]," +
            "\"capabilities\":{\"deepseek-flash\":{\"fast\":false,\"reasoning\":true}," +
            "\"deepseek-v4-pro\":{\"fast\":false,\"reasoning\":true}}}")
        sb.append(",{\"slug\":\"opencode-go\",\"name\":\"OpenCode Go\",\"authenticated\":true," +
            "\"total_models\":3,\"models\":[\"kimi-k3\",\"glm-5.2\",\"grok-4.7\"]," +
            "\"capabilities\":{\"kimi-k3\":{\"fast\":false,\"reasoning\":true}," +
            "\"glm-5.2\":{\"fast\":true,\"reasoning\":true}," +
            "\"grok-4.7\":{\"fast\":false,\"reasoning\":true}}}")
        for (i in 14 until 52) sb.append(",{\"slug\":\"empty-$i\",\"name\":\"Empty $i\"," +
            "\"authenticated\":false,\"total_models\":0,\"models\":[]}")
        sb.append("]}")
        val read = ModelChoices.read(sb.toString(), "deepseek-flash", "deepseek")
        assertEquals("every provider the payload carries is shown", 54, read.providers.size)
        assertEquals("nothing was dropped", emptyList<String>(), read.dropped)
        assertEquals("deepseek", read.providers.first().slug)
        assertEquals("opencode-go", read.providers[1].slug)
        assertEquals(2, read.providers.first().models.size)
        assertTrue(read.providers.first().models.first().current)
        // The remaining fifty-two keep the payload's order — nothing is silently re-filed.
        assertEquals((0 until 14).map { "empty-$it" } + (14 until 52).map { "empty-$it" },
            read.providers.drop(2).map { it.slug })
    }

    /** An entry the app cannot read is named, with why — it never just disappears. */
    @Test fun whatCannotBeReadIsNamedNotSilentlyOmitted() {
        val read = ModelChoices.read(raw, "", "")
        assertTrue(read.dropped.toString(), read.dropped.any { it.contains("weird") && it.contains("model") })
        assertEquals(listOf("ok-model"), read.providers.first { it.slug == "weird" }.models.map { it.id })
        // A body of the wrong shape says so instead of returning a silent empty list.
        assertEquals(listOf("the body is not a JSON object"), ModelChoices.read("[]", "", "").dropped)
        assertEquals(listOf("the body has no providers list"), ModelChoices.read("{}", "", "").dropped)
    }

    @Test fun theCurrentModelIsMarkedOnlyUnderItsOwnProvider() {
        val current = all.flatMap { p -> p.models.filter { it.current }.map { p.slug to it.id } }
        assertEquals(listOf("deepseek" to "deepseek-v4-flash"), current)
    }

    @Test fun rowsCarryWhatAPersonChoosesByNotTheRawIdentifier() {
        val flash = all.first().models.first()
        assertEquals("DeepSeek V4 Flash", flash.name)
        assertEquals(ModelChoices.Category.FAST, flash.category)

        val coder = all[1].models.first { it.id.startsWith("qwen3-coder") }
        assertEquals(ModelChoices.Category.CODE, coder.category)
        assertEquals("480B · 35B active", coder.size)

        val gemma = all.first { it.slug == "ollama" }.models.first()
        assertEquals("Gemma 4 E2B", gemma.name)              // the gateway's own name wins
        assertEquals("5.1B", gemma.size)                     // its stated size beats the name's
        assertEquals("5.1B · 32k context · installed", ModelChoices.detail(gemma))
        val llama = all.first { it.slug == "ollama" }.models[1]
        assertEquals("8B · not installed", ModelChoices.detail(llama))
    }

    /** Honesty: what the catalogue does not say, the row does not claim. */
    @Test fun nothingIsInventedWhenTheGatewayIsSilent() {
        val kimi = all[1].models.first { it.id == "kimi-k3" }
        assertNull(kimi.installed)
        assertNull(kimi.size)
        assertNull(kimi.context)
        assertEquals("", ModelChoices.detail(kimi))
        assertEquals(ModelChoices.Category.GENERAL, kimi.category)
    }

    @Test fun categoriesComeFromTheModelsOwnWords() {
        val c = ModelChoices::categoryOf
        assertEquals(ModelChoices.Category.REASONING, c("deepseek-r1", emptyList()))
        assertEquals(ModelChoices.Category.REASONING, c("o4-mini", emptyList()))   // a reasoning family, even if small
        assertEquals(ModelChoices.Category.VISION, c("qwen3-vl-235b-a22b", emptyList()))
        assertEquals(ModelChoices.Category.FAST, c("gemini-2.5-flash", emptyList()))
        assertEquals(ModelChoices.Category.GENERAL, c("gpt-5", emptyList()))
        // The gateway's own capability list beats the name.
        assertEquals(ModelChoices.Category.CODE, c("gpt-5", listOf("code")))
    }

    @Test fun contextIsNamedTheWayItsMakersNameIt() {
        fun ctx(n: Int) = ModelChoices.parse(
            "{\"providers\":[{\"slug\":\"x\",\"models\":[{\"id\":\"m\",\"context_length\":$n}]}]}", "", "")
            .first().models.first().context
        assertEquals("128k context", ctx(131072))
        assertEquals("200k context", ctx(200000))
        assertEquals("1M context", ctx(1048576))
    }

    @Test fun sizesAreReadOnlyWhenTheNameStatesOne() {
        assertEquals("30B · 3B active", ModelChoices.sizeOf("qwen3:30b-a3b"))
        assertEquals("E2B", ModelChoices.sizeOf("gemma-4-e2b"))
        assertEquals("8×7B", ModelChoices.sizeOf("mixtral-8x7b"))
        assertNull(ModelChoices.sizeOf("gpt-5"))
        assertNull(ModelChoices.sizeOf("glm-4.6"))
    }

    /**
     * #5: there is no category level. Every model of a provider is one list, and the
     * categories survive only as the provider row's summary line.
     */
    @Test fun aProvidersModelsAreOneListAndTheCategoriesOnlyDescribeIt() {
        val openrouter = all.first { it.slug == "openrouter" }
        assertEquals(10, openrouter.models.size)
        // Every model is reachable in that one list: nothing is filed behind a header.
        assertEquals(openrouter.models.size, ModelChoices.byCategory(openrouter).sumOf { it.second.size })
        val cats = ModelChoices.byCategory(openrouter).map { it.first }
        assertEquals(cats.sortedBy { it.ordinal }, cats)   // screen order
    }

    /** The marks come from what the payload says, then from the model's own name. */
    @Test fun theMarksSayWhatAModelCanDoAndOnlyWhatIsKnown() {
        assertEquals(listOf(ModelChoices.Cap.CODE),
            ModelChoices.caps(all[1].models.first { it.id.startsWith("qwen3-coder") }))
        assertEquals(listOf(ModelChoices.Cap.VISION),
            ModelChoices.caps(all[1].models.first { it.id.startsWith("qwen3-vl") }))
        // A name that says "flash" gets the fast mark when nothing contradicts it...
        assertEquals(listOf(ModelChoices.Cap.FAST),
            ModelChoices.caps(all[1].models.first { it.id == "gpt-5-mini" }))
        // ...and a plain name gets no mark at all rather than an invented one.
        assertEquals(emptyList<ModelChoices.Cap>(),
            ModelChoices.caps(all[1].models.first { it.id == "gpt-5" }))
    }

    /** The gateway's own flags beat the name, both ways, and are read from the provider. */
    @Test fun theGatewaysFlagsBeatTheModelsName() {
        val raw = """
            {"providers":[{"slug":"p","name":"P","is_current":true,"authenticated":true,
              "models":["deepseek-flash","gpt-5","mystery"],
              "capabilities":{
                "deepseek-flash":{"fast":false,"reasoning":true},
                "gpt-5":{"fast":true,"reasoning":false},
                "mystery":{"reasoning":true,"can_disable_reasoning":true}}}]}
        """.trimIndent()
        val p = ModelChoices.parse(raw, "", "").first()
        fun caps(id: String) = ModelChoices.caps(p.models.first { it.id == id })
        // "flash" says fast; the gateway says it is not — the gateway wins.
        assertEquals(listOf(ModelChoices.Cap.REASONING), caps("deepseek-flash"))
        // "gpt-5" says nothing; the gateway says it is fast and does not reason.
        assertEquals(listOf(ModelChoices.Cap.FAST), caps("gpt-5"))
        // A model whose name says nothing and whose provider says it reasons.
        assertEquals(listOf(ModelChoices.Cap.REASONING), caps("mystery"))
        // The flags live under the provider, not on the model entry: a model the provider
        // does not describe keeps only its name's claim, never a borrowed one.
        assertEquals(true, p.models.first { it.id == "mystery" }.reasons)
        assertEquals(false, p.models.first { it.id == "deepseek-flash" }.fast)
    }

    /** A long window is a stated fact, and only a stated one earns the mark. */
    @Test fun longContextIsOnlyClaimedWhenTheGatewayStatesIt() {
        val raw = """
            {"providers":[{"slug":"p","models":[
              {"id":"ctx32k","context_length":32768},
              {"id":"ctx128k","context_length":131072},
              {"id":"ctx-none"}]}]}
        """.trimIndent()
        val models = ModelChoices.parse(raw, "", "").first().models
        assertEquals(emptyList<ModelChoices.Cap>(), ModelChoices.caps(models.first { it.id == "ctx32k" }))
        assertEquals(listOf(ModelChoices.Cap.LONG_CONTEXT), ModelChoices.caps(models.first { it.id == "ctx128k" }))
        assertEquals(emptyList<ModelChoices.Cap>(), ModelChoices.caps(models.first { it.id == "ctx-none" }))
        assertEquals(ModelChoices.LONG_CONTEXT_TOKENS, 100_000)
    }

    /** Every mark has words for a screen reader, and the vocabulary has no invented ability. */
    @Test fun everyMarkHasWordsAndTheVocabularyOnlySaysWhatThePayloadCan() {
        for (cap in ModelChoices.Cap.entries) {
            assertTrue(cap.says.isNotBlank())
            assertTrue("${cap.name}: '${cap.says}' is a sentence fragment", cap.says.length in 4..24)
        }
        // #5's suggested vocabulary, pruned to what /api/model/options supports: no tools.
        assertEquals(listOf("CODE", "VISION", "REASONING", "LONG_CONTEXT", "FAST"),
            ModelChoices.Cap.entries.map { it.name })
    }

    @Test fun aProviderWithNothingToChooseSaysWhy() {
        val anthropic = all.first { it.slug == "anthropic" }
        assertEquals(false, anthropic.signedIn)
        assertEquals("Not signed in on the gateway", ModelChoices.providerLine(anthropic))
        assertEquals("2 models · general, fast", ModelChoices.providerLine(all.first()))
    }

    @Test fun malformedEntriesAreSkippedNotFatal() {
        assertEquals(listOf("ok-model"), all.first { it.slug == "weird" }.models.map { it.id })
        assertTrue(ModelChoices.parse("{}", "", "").isEmpty())
    }

    @Test fun searchFindsAModelByWhatItIsCalled() {
        assertEquals(listOf("deepseek-v4-flash", "gemini-2.5-flash"),
            ModelChoices.search(all, "flash").map { it.second.id })
        assertTrue(ModelChoices.search(all, "  ").isEmpty())
    }

    @Test fun theOutcomeIsSaidInWords() {
        val p = all.first()
        assertEquals("Thinking with DeepSeek V4 Flash · fresh thread", ModelChoices.switchedLine(p, p.models.first()))
        assertFalse(ModelChoices.switchedLine(p, p.models.first()).contains("->"))
    }
}
