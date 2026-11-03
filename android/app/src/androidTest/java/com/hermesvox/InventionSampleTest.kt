@file:OptIn(com.google.ai.edge.litertlm.ExperimentalApi::class)

package com.hermesvox

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * #256 — what the presence model actually invents. OBSERVATION ONLY: it gates nothing, changes no
 * bound, and is never packaged into the app. The host driver is `scripts/invention-sample.sh`;
 * the classifier is `scripts/invention-report.py`; the output shape is in
 * `docs/INVENTION-MEASUREMENT.md`.
 *
 * It renders the presence model with the app's REAL inputs — the soul persona built from this
 * phone's VOX.md (`VoxSoul.soulPrompt`), the app's directives (`ErSoulTurn.greetingDirective`,
 * `directive`, `workingDirective`), the app's sampler defaults and the app's 256-token cap — at
 * the three things presence is asked to do (greet, answer, narrate) across the six contexts the
 * issue names. Each sample gets a fresh conversation, so one render never primes the next; a
 * repeat across samples is therefore the model's own habit, not context bleed.
 *
 * Every sample is one JSONL record: the directive and the raw render together (the issue's
 * requirement), the parse the app would make of it, and the app's own verdicts on it
 * (`unvouchedNumbers`, `claimsResult`) so the report can compare its classes with the app's.
 *
 *   -e inv.samples N     samples per (mode, context), default 20
 *   -e inv.modes  a,b    greet,answer,narrate (default all)
 *   -e inv.backend cpu|gpu
 *   -e inv.results PATH  internal file the records are appended to
 */
@RunWith(AndroidJUnit4::class)
class InventionSampleTest {

    private val args = InstrumentationRegistry.getArguments()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** One moment the model is asked to speak in. [caller]/[tool]/[waited] feed the app's builders. */
    data class Ctx(val name: String, val caller: String, val tool: String?, val waited: Long?)

    @Test
    fun sample() {
        val n = (args.getString("inv.samples") ?: "20").toInt()
        val modes = (args.getString("inv.modes") ?: "greet,answer,narrate").split(",").map { it.trim() }
        val backend = if (args.getString("inv.backend") == "gpu") Backend.GPU() else Backend.CPU()
        val out = File(args.getString("inv.results") ?: File(context.filesDir, "invention/samples.jsonl").path)
            .also { it.parentFile?.mkdirs() }

        val model = modelFile() ?: run {
            emit(out, JSONObject().put("error", "no presence model on this phone (install it in the app, or push it to the bench dir)"))
            return
        }
        val voxMd = VoxMirror.read(context) ?: ""
        val persona = VoxSoul.soulPrompt(voxMd)
        val engine = Engine(EngineConfig(
            modelPath = model.absolutePath, backend = backend,
            maxNumTokens = SoulBudget.MAX_TOKENS,
            cacheDir = File(context.cacheDir, "invention-cache").apply { mkdirs() }.path,
        ))
        engine.initialize()
        val run = System.currentTimeMillis()
        try {
            for (mode in modes) for (ctx in contextsFor(mode)) {
                val directive = when (mode) {
                    "greet" -> ErSoulTurn.greetingDirective()
                    "answer" -> ErSoulTurn.directive(ctx.caller, ctx.tool)
                    else -> ErSoulTurn.workingDirective(ctx.caller, ctx.tool, emptyList(), ctx.waited)
                }
                val vouched = ErSoulTurn.vouchedNumbers(directive)
                for (i in 1..n) {
                    val t0 = System.nanoTime()
                    val (raw, err) = render(engine, persona, directive)
                    val ms = (System.nanoTime() - t0) / 1_000_000
                    val outcome = ErSoulTurn.parse(raw)
                    val spoken = when (outcome) {
                        is ErSoulTurn.Outcome.Spoken -> outcome.text
                        is ErSoulTurn.Outcome.Beat -> outcome.opener
                        else -> ""
                    }
                    emit(out, JSONObject()
                        .put("run", run)
                        .put("model", model.name)
                        .put("backend", if (backend is Backend.GPU) "gpu" else "cpu")
                        .put("vox_md", voxMd.isNotBlank())
                        .put("mode", mode)
                        .put("context", ctx.name)
                        .put("sample", i)
                        .put("directive", directive)
                        .put("raw", raw)
                        .put("outcome", ErSoulTurn.decisionName(outcome))
                        .put("spoken", spoken)
                        .put("ms", ms)
                        .put("app_unvouched_numbers", JSONArray(ErSoulTurn.unvouchedNumbers(spoken, vouched)))
                        .put("app_claims_result", spoken.isNotEmpty() && ErSoulTurn.claimsResult(spoken))
                        .apply { if (err != null) put("error", err) })
                }
            }
        } finally {
            try { engine.close() } catch (_: Throwable) {}
        }
    }

    /** Greet has one moment (the call connecting); answer and narrate take all six. */
    private fun contextsFor(mode: String): List<Ctx> =
        if (mode == "greet") listOf(Ctx("call-start", "", null, null)) else CONTEXTS

    private fun modelFile(): File? {
        val installed = File(ModelCatalog.modelDir(context, "gemma-e2b"), "gemma-4-E2B-it.litertlm")
        if (installed.exists()) return installed
        val bench = File(context.getExternalFilesDir(null), "bench/gemma-4-E2B-it.litertlm")
        return bench.takeIf { it.exists() }
    }

    /** One fresh conversation, the app's config: its persona, its 256-token cap, its sampler. */
    private fun render(engine: Engine, persona: String, directive: String): Pair<String, String?> {
        val sb = StringBuilder()
        var err: String? = null
        val done = CountDownLatch(1)
        engine.createConversation(ConversationConfig(
            systemInstruction = Contents.of(persona),
            maxOutputToken = 256,
        )).use { conv ->
            conv.sendMessageAsync(directive, object : MessageCallback {
                override fun onMessage(m: Message) {
                    for (c in m.contents.contents) if (c is com.google.ai.edge.litertlm.Content.Text) sb.append(c.text)
                }
                override fun onDone() { done.countDown() }
                override fun onError(t: Throwable) { err = "${t.javaClass.simpleName}: ${t.message}"; done.countDown() }
            })
            if (!done.await(120, TimeUnit.SECONDS)) {
                try { conv.cancelProcess() } catch (_: Throwable) {}
                err = "render timeout 120s"
            }
        }
        return sb.toString() to err
    }

    private fun emit(out: File, o: JSONObject) {
        out.appendText(o.toString() + "\n")
        try { java.io.FileOutputStream(out, true).use { it.fd.sync() } } catch (_: Throwable) {}
    }

    companion object {
        /** The six moments #256 names. Every number here is the client's, so it is sourced. */
        val CONTEXTS = listOf(
            Ctx("tool-named", "What's the weather like in Burlington this weekend?",
                "web_search (weather Burlington weekend)", 6),
            Ctx("tool-returned-count", "Do I have anything from the school in my inbox?",
                "gmail search, which returned 14 messages", 9),
            Ctx("long-wait", "Can you go through my calendar and find a free afternoon next week?",
                "calendar_list_events", 48),
            Ctx("short-wait", "Can you go through my calendar and find a free afternoon next week?",
                "calendar_list_events", 3),
            Ctx("no-tool", "How has your day been?", null, null),
            Ctx("unknown-result", "Did my package from the bookshop arrive yet?",
                "checking the delivery tracker", 12),
        )
    }
}
