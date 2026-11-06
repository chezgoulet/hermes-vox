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
        assertEquals(listOf("deepseek", "openrouter", "ollama", "anthropic", "weird"), all.map { it.slug })
        assertTrue(all.first().current)
        assertEquals("DeepSeek", all.first().name)   // a lower-case slug-as-name is made readable
        assertEquals("OpenRouter", all[1].name)       // a name the gateway cased is kept
        // The empty, unauthenticated provider is still shown — at the end, where it is
        // reachable and still says why it cannot be chosen.
        val anthropic = all.first { it.slug == "anthropic" }
        assertTrue(anthropic.models.isEmpty())
        assertEquals("Not signed in on the gateway", ModelChoices.providerLine(anthropic))
        // Authenticated is the second rank, not "has models": `weird` has a model but no
        // authentication, so it sits with the rest, below the host's configured providers.
        assertEquals(listOf(0, 1, 1, 2, 2), all.map { ModelChoices.rank(it) })
    }

    /** A provider the host is signed in to leads even before the gateway lists its models. */
    @Test fun anAuthenticatedProviderWithNoListedModelsStillSitsAboveTheRest() {
        val raw = """
            {"providers":[
              {"slug":"signed-in-empty","name":"Signed in","authenticated":true,"total_models":5,"models":[]},
              {"slug":"not-signed-in","name":"Not signed in","authenticated":false,"models":[]},
              {"slug":"unknown","name":"Unknown","models":[]}
            ]}
        """.trimIndent()
        val ps = ModelChoices.parse(raw, "", "")
        assertEquals(listOf("signed-in-empty", "not-signed-in", "unknown"), ps.map { it.slug })
        assertEquals(listOf(1, 2, 2), ps.map { ModelChoices.rank(it) })
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

    /** #5: there is no category level — every model of a provider is one list. */
    @Test fun aProvidersModelsAreOneList() {
        val openrouter = all.first { it.slug == "openrouter" }
        assertEquals(10, openrouter.models.size)
        assertEquals(10, openrouter.models.map { it.id }.toSet().size)
    }

    /**
     * The marks come only from the capability flags the gateway states. A model whose name
     * merely sounds fast, or like code, or like vision, gets nothing: the payload did not say
     * so, and the row does not invent it.
     */
    @Test fun theMarksSayWhatTheGatewayStatesAndNothingElse() {
        // These live payloads carry no capability map at all, so a name like "flash",
        // "coder" or "vl" earns no mark.
        assertEquals(emptyList<ModelChoices.Cap>(),
            ModelChoices.caps(all[1].models.first { it.id == "gpt-5-mini" }))
        assertEquals(emptyList<ModelChoices.Cap>(),
            ModelChoices.caps(all[1].models.first { it.id.startsWith("qwen3-coder") }))
        assertEquals(emptyList<ModelChoices.Cap>(),
            ModelChoices.caps(all[1].models.first { it.id.startsWith("qwen3-vl") }))
    }

    /** The gateway's own flags are read from the provider's map, and its answer wins. */
    @Test fun theGatewaysFlagsAreTheOnlySourceOfAMark() {
        val raw = """
            {"providers":[{"slug":"p","name":"P","is_current":true,"authenticated":true,
              "models":["deepseek-flash","gpt-5","mystery","deepseek-r1"],
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
        // A model the provider does not describe earns nothing, even when its name says "r1".
        assertEquals(emptyList<ModelChoices.Cap>(), caps("deepseek-r1"))
        // A model whose provider says it reasons, and says that can be turned off.
        val mystery = p.models.first { it.id == "mystery" }
        assertEquals(listOf(ModelChoices.Cap.REASONING), ModelChoices.caps(mystery))
        assertEquals("can reason, can be turned off", ModelChoices.says(mystery, ModelChoices.Cap.REASONING))
        assertEquals(true, mystery.reasoningAdjustable)
        assertEquals(false, p.models.first { it.id == "deepseek-flash" }.fast)
    }

    /** A catalogue that puts the same flags on the model entry is read the same way. */
    @Test fun perModelFlagsAreReadFromTheModelEntryToo() {
        val raw = """
            {"providers":[{"slug":"p","models":[
              {"id":"fast-one","fast":true,"reasoning":false},
              {"id":"thinker","fast":false,"reasoning":true,"can_disable_reasoning":true},
              {"id":"silent"}]}]}
        """.trimIndent()
        val models = ModelChoices.parse(raw, "", "").first().models
        assertEquals(listOf(ModelChoices.Cap.FAST), ModelChoices.caps(models.first { it.id == "fast-one" }))
        assertEquals(listOf(ModelChoices.Cap.REASONING), ModelChoices.caps(models.first { it.id == "thinker" }))
        assertEquals("can reason, can be turned off",
            ModelChoices.says(models.first { it.id == "thinker" }, ModelChoices.Cap.REASONING))
        assertEquals(emptyList<ModelChoices.Cap>(), ModelChoices.caps(models.first { it.id == "silent" }))
    }

    /** A long window is still stated in the row's detail line; it is not a capability mark. */
    @Test fun longContextIsStatedInTheLineNotAsAMark() {
        val raw = """
            {"providers":[{"slug":"p","models":[
              {"id":"ctx32k","context_length":32768},
              {"id":"ctx128k","context_length":131072},
              {"id":"ctx-none"}]}]}
        """.trimIndent()
        val models = ModelChoices.parse(raw, "", "").first().models
        assertEquals(emptyList<ModelChoices.Cap>(), ModelChoices.caps(models.first { it.id == "ctx32k" }))
        assertEquals(emptyList<ModelChoices.Cap>(), ModelChoices.caps(models.first { it.id == "ctx128k" }))
        assertEquals("128k context", models.first { it.id == "ctx128k" }.context)
        assertEquals(emptyList<ModelChoices.Cap>(), ModelChoices.caps(models.first { it.id == "ctx-none" }))
    }

    /** Every mark has words for a screen reader, and the vocabulary has no invented ability. */
    @Test fun everyMarkHasWordsAndTheVocabularyOnlySaysWhatThePayloadCan() {
        for (cap in ModelChoices.Cap.entries) {
            assertTrue(cap.says.isNotBlank())
            assertTrue("${cap.name}: '${cap.says}' is a sentence fragment", cap.says.length in 4..24)
        }
        // #5's suggested set, pruned to the fields /api/model/options actually reports:
        // reasoning and fast. No tools, vision, code or long-context claim.
        assertEquals(listOf("REASONING", "FAST"), ModelChoices.Cap.entries.map { it.name })
    }

    @Test fun aProviderWithNothingToChooseSaysWhy() {
        val anthropic = all.first { it.slug == "anthropic" }
        assertEquals(false, anthropic.signedIn)
        assertEquals("Not signed in on the gateway", ModelChoices.providerLine(anthropic))
        assertEquals("2 models", ModelChoices.providerLine(all.first()))
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
