package com.hermesvox

import com.hermesvox.core.JArr
import com.hermesvox.core.JNum
import com.hermesvox.core.JObj
import com.hermesvox.core.JStr
import com.hermesvox.core.Json
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #229's bench: the sttbench corpus, replayed frame by frame (traces from
 * tools/endpointbench/make_traces.py — Silero VAD + a streaming recogniser's partials), against
 * the semantic endpointer and against today's fixed 800 ms pause. Measured: how often a turn
 * is committed before the speaker is done (a false endpoint — worse than a slow one, the issue
 * says), and how long after the last word a correct commit lands.
 */
class EndpointBenchTest {

    private class Clip(val id: String, val category: String, val speech: List<Boolean>, val partial: List<String>)

    private val clips: List<Clip> by lazy {
        val root = Json.parse(File("src/test/resources/endpointbench/traces.json").readText()) as JObj
        (root["clips"] as JArr).items.map { n ->
            val o = n as JObj
            val fr = (o["frames"] as JArr).items.map { it as JObj }
            Clip((o["id"] as JStr).value, (o["category"] as JStr).value,
                fr.map { ((it["s"] as JNum).raw.toInt()) == 1 }, fr.map { (it["p"] as JStr).value })
        }
    }

    private class Result(val falseEndpoints: Int, val latenciesMs: List<Long>, val missed: Int)

    /** Walk one clip; [final] says whether to commit after this frame. Frames are 100 ms. */
    private fun run(decide: (silenceMs: Long, partial: String, stableMs: Long) -> Boolean): Result {
        var falses = 0; var missed = 0
        val lat = ArrayList<Long>()
        for (c in clips) {
            val lastSpeech = c.speech.indexOfLast { it }
            if (lastSpeech < 0) continue
            var seen = false; var silence = 0L; var committed = false; var stable = 0L
            for (i in c.speech.indices) {
                if (c.speech[i]) { seen = true; silence = 0 } else if (seen) silence += 100
                stable = if (i > 0 && c.partial[i] == c.partial[i - 1]) stable + 100 else 0
                if (seen && !c.speech[i] && decide(silence, c.partial[i], stable)) {
                    if (i < lastSpeech) falses++ else lat += (i - lastSpeech) * 100L
                    committed = true
                    if (i < lastSpeech) { seen = false; silence = 0; committed = false; continue }   // the next segment starts
                    break
                }
            }
            if (!committed) missed++
        }
        return Result(falses, lat.sorted(), missed)
    }

    private fun median(l: List<Long>) = if (l.isEmpty()) -1 else l[l.size / 2]

    private val baseline by lazy { run { s, _, _ -> s > 800 } }
    private val balanced by lazy { run { s, p, st -> SemanticEndpoint.decide(s, p, st, SemanticEndpoint.Eagerness.BALANCED) == SemanticEndpoint.Verdict.FINAL } }

    @Test fun theBenchHasTheCorpus() {
        assertTrue(clips.size >= 20)
        assertTrue(clips.any { it.category == "pause" })
    }

    @Test fun semanticEndpointingNeverCommitsEarlierThanTheSilenceRuleWrongly() {
        println("endpoint bench: baseline 800ms false=${baseline.falseEndpoints} medianMs=${median(baseline.latenciesMs)} missed=${baseline.missed}")
        println("endpoint bench: balanced     false=${balanced.falseEndpoints} medianMs=${median(balanced.latenciesMs)} missed=${balanced.missed}")
        for (e in SemanticEndpoint.Eagerness.values()) {
            val r = run { s, p, st -> SemanticEndpoint.decide(s, p, st, e) == SemanticEndpoint.Verdict.FINAL }
            println("endpoint bench: ${e.key.padEnd(9)} false=${r.falseEndpoints} medianMs=${median(r.latenciesMs)} missed=${r.missed}")
        }
        assertTrue("false endpoints ${balanced.falseEndpoints} > baseline ${baseline.falseEndpoints}",
            balanced.falseEndpoints <= baseline.falseEndpoints)
        assertTrue(balanced.missed == 0)
        // The frontier: patient trades latency for fewer false endpoints, eager the reverse.
        val patient = run { s, p, st -> SemanticEndpoint.decide(s, p, st, SemanticEndpoint.Eagerness.PATIENT) == SemanticEndpoint.Verdict.FINAL }
        val eager = run { s, p, st -> SemanticEndpoint.decide(s, p, st, SemanticEndpoint.Eagerness.EAGER) == SemanticEndpoint.Verdict.FINAL }
        assertTrue(patient.falseEndpoints < balanced.falseEndpoints && median(eager.latenciesMs) < median(balanced.latenciesMs))
    }

    @Test fun andItCommitsSoonerAfterAFinishedSentence() {
        assertTrue("median ${median(balanced.latenciesMs)} ms not under baseline ${median(baseline.latenciesMs)} ms",
            median(balanced.latenciesMs) < median(baseline.latenciesMs))
    }

    @Test fun theWordsDecideHowLongAPauseMustBe() {
        val e = SemanticEndpoint.Eagerness.BALANCED
        assertTrue(SemanticEndpoint.decide(600, "what time is it", 600, e) == SemanticEndpoint.Verdict.FINAL)
        assertTrue("still being revised", SemanticEndpoint.decide(600, "what time is it", 100, e) != SemanticEndpoint.Verdict.FINAL)
        assertTrue(SemanticEndpoint.decide(200, "what time is it", 200, e) == SemanticEndpoint.Verdict.PROVISIONAL)
        assertTrue(SemanticEndpoint.decide(600, "i was thinking about the", 600, e) != SemanticEndpoint.Verdict.FINAL)
        assertTrue(SemanticEndpoint.decide(e.ceilingMs, "and um", 0, e) == SemanticEndpoint.Verdict.FINAL)   // the timeout

        assertTrue(!SemanticEndpoint.finished(""))
    }
}
