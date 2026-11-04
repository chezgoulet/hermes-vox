package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The model switcher's reading of /api/model/options, and its fan-out rules. */
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

    @Test fun theProviderInUseComesFirstAndTheRestKeepTheGatewaysOrder() {
        assertEquals(listOf("deepseek", "openrouter", "ollama", "anthropic", "weird"), all.map { it.slug })
        assertTrue(all.first().current)
        assertEquals("DeepSeek", all.first().name)   // a lower-case slug-as-name is made readable
        assertEquals("OpenRouter", all[1].name)       // a name the gateway cased is kept
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

    @Test fun sizesAreReadOnlyWhenTheNameStatesOne() {
        assertEquals("30B · 3B active", ModelChoices.sizeOf("qwen3:30b-a3b"))
        assertEquals("E2B", ModelChoices.sizeOf("gemma-4-e2b"))
        assertEquals("8×7B", ModelChoices.sizeOf("mixtral-8x7b"))
        assertNull(ModelChoices.sizeOf("gpt-5"))
        assertNull(ModelChoices.sizeOf("glm-4.6"))
    }

    /** The fan-out: a second level only when it saves taps. */
    @Test fun aSmallProviderOpensOnItsModelsAndALargeOneOnItsCategories() {
        val deepseek = all.first()
        val openrouter = all[1]
        assertFalse("two models do not need a category step", ModelChoices.showsCategories(deepseek))
        assertTrue("ten models across kinds get one", ModelChoices.showsCategories(openrouter))
        val cats = ModelChoices.byCategory(openrouter).map { it.first }
        assertEquals(cats.sortedBy { it.ordinal }, cats)   // screen order
        assertEquals(openrouter.models.size, ModelChoices.byCategory(openrouter).sumOf { it.second.size })
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
