package com.hermesvox

import com.hermesvox.ProfilesAsk.AskDecision
import com.hermesvox.ProfilesAsk.AskKind
import com.hermesvox.ProfilesAsk.Failure
import com.hermesvox.ProfilesAsk.Parsed
import com.hermesvox.ProfilesAsk.Result
import com.hermesvox.core.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #384 — the profiles conversation (SPEC-conversational-bridge §A), proven off-device.
 *
 * The ask, the request body, the parser, the fallback decision, the probe plan and the
 * cache rules are all pure JVM. The transport (HTTP, bearer, timeout) and the probe sockets
 * are exercised by hand against a live host and are not faked here: what is pinned is the
 * contract — the one block the parser accepts, the line every failure produces, and the
 * rule that a name only becomes usable after a 200.
 */
class ProfilesAskTest {

    private val base = "https://gw.example"
    private val now = 1_793_650_000_000L

    /** A chat-completions answer whose assistant text is [text]. */
    private fun chat(text: String, extra: String = ""): String =
        """{"id":"resp_1","object":"chat.completion","choices":[{"index":0,""" +
            """"message":{"role":"assistant","content":${Json.encode(text)}}$extra}]}"""

    /** The canonical reply the spec shows. */
    private val canonical = """
        Here is the list.
        ```vox-profiles
        default=home
        profile=home
        profile=work
        ```
        Anything else?
    """.trimIndent()

    private fun ok(text: String): ProfilesAsk.Block {
        val p = ProfilesAsk.parse(text)
        assertTrue("wanted a block, got $p", p is Parsed.Ok)
        return (p as Parsed.Ok).block
    }

    // ---- the block: exactly what the spec says, never a made-up name ----

    @Test fun theCanonicalBlockIsRead() {
        val b = ok(canonical)
        assertEquals(listOf("home", "work"), b.served.map { it.name })
        assertEquals("home", b.default)
        assertTrue(b.served.first { it.name == "home" }.isDefault)
        assertFalse(b.served.first { it.name == "work" }.isDefault)
    }

    /** Prose before and after the block is ignored, including another fenced example. */
    @Test fun proseAroundTheBlockIsIgnored() {
        val text = """
            I can answer that. For example, a command looks like:
            ```
            hermes profile list
            ```
            ```vox-profiles
            profile=work
            ```
            That is the whole list.
        """.trimIndent()
        assertEquals(listOf("work"), ok(text).served.map { it.name })
    }

    @Test fun noBlockIsTheFallback() {
        assertTrue(ProfilesAsk.parse("home and work are the profiles.") is Parsed.None)
        assertTrue(ProfilesAsk.parse("") is Parsed.None)
        assertTrue(ProfilesAsk.parse("```\nprofile=home\n```") is Parsed.None)   // wrong label
        assertTrue(ProfilesAsk.parse("```vox-profiles\nprofile=home\n") is Parsed.None) // unterminated
        // Two blocks are ambiguous: choosing one would be inventing an answer.
        assertTrue(ProfilesAsk.parse("```vox-profiles\nprofile=a\n```\n```vox-profiles\nprofile=b\n```") is Parsed.None)
    }

    /** Unknown line types are not honoured; a block that contains only them named nothing. */
    @Test fun unknownLineTypesAreNotHonoured() {
        val b = ok(
            """
            ```vox-profiles
            format=1
            note=these are yours
            profile=home
            ```
            """.trimIndent()
        )
        assertEquals(listOf("home"), b.served.map { it.name })
        val onlyUnknown = ProfilesAsk.parse("```vox-profiles\nformat=1\nnote=hello\n```")
        assertTrue(onlyUnknown is Parsed.Echoed)
        assertEquals(Failure.NO_NAMES, (ProfilesAsk.fromChat(200, chat("```vox-profiles\nformat=1\n```")) as Result.Failed).failure)
    }

    @Test fun illegalNamesAreDroppedAndNeverInvented() {
        val b = ok(
            """
            ```vox-profiles
            default=Work
            profile=Work
            profile=a/b
            profile=.hidden
            profile=
            profile=ok_1
            ```
            """.trimIndent()
        )
        assertEquals(listOf("ok_1"), b.served.map { it.name })
        assertEquals("", b.default)
        assertEquals(5, b.dropped)

        // Nothing survives the charset: that is its own line, not an empty list.
        val none = ProfilesAsk.fromChat(200, chat("```vox-profiles\nprofile=UPPER\nprofile=../etc\n```"))
        assertEquals(Failure.NO_NAMES, (none as Result.Failed).failure)
        assertEquals(ProfilesAsk.FALLBACK_NO_NAMES, ProfilesAsk.line(none))
    }

    @Test fun theCharsetBoundariesAreTheSpecs() {
        assertEquals(listOf("a"), ok("```vox-profiles\nprofile=a\n```").served.map { it.name })
        assertEquals(listOf("a1-b_2"), ok("```vox-profiles\nprofile=a1-b_2\n```").served.map { it.name })
        val longest = "a" + "b".repeat(31)                 // 32 chars: the maximum
        assertEquals(listOf(longest), ok("```vox-profiles\nprofile=$longest\n```").served.map { it.name })
        val tooLong = "a" + "b".repeat(32)                 // 33: dropped
        assertTrue(ProfilesAsk.parse("```vox-profiles\nprofile=$tooLong\n```") is Parsed.Echoed)
        assertTrue(ProfilesAsk.parse("```vox-profiles\nprofile=-lead\n```") is Parsed.Echoed)

        // The registry accepts the spec's longest name, so a valid host name is never
        // silently skipped for being longer than the old UI cap.
        val applied = ProfilesAsk.settle(
            emptyList(), base, "enc:aaa",
            Result.Named(listOf(HostProfiles.Served(longest)), "", ProfilesAsk.Source.CONVERSATION),
            "", mapOf(longest to true),
        )
        assertEquals(listOf(longest), applied.merge.added)
    }

    @Test fun duplicatesAreReadOnce() {
        val b = ok(
            """
            ```vox-profiles
            profile=home
            profile=home
            default=home
            default=work
            profile=work
            ```
            """.trimIndent()
        )
        assertEquals(listOf("home", "work"), b.served.map { it.name })
        // The first default line wins; the second is ignored, not a second default.
        assertEquals("home", b.default)
        assertEquals(1, b.served.count { it.isDefault })
    }

    @Test fun anEmptyBlockNamedNothing() {
        val p = ProfilesAsk.parse("```vox-profiles\n```")
        assertTrue(p is Parsed.Echoed)
        assertEquals(0, (p as Parsed.Echoed).dropped)
    }

    /** A default that names a profile this reply does not list names nothing. */
    @Test fun aDefaultOutsideTheListIsIgnoredNotInvented() {
        val b = ok("```vox-profiles\ndefault=office\nprofile=home\nprofile=work\n```")
        assertEquals(listOf("home", "work"), b.served.map { it.name })
        assertEquals("", b.default)
        assertFalse(b.served.any { it.isDefault })
    }

    /** The default may also be listed only once, as the spec's own sample shows. */
    @Test fun theDefaultIsAnnotatedOnItsOwnProfileLine() {
        val b = ok("```vox-profiles\ndefault=home\nprofile=home\nprofile=work\n```")
        assertTrue(b.served.first { it.name == "home" }.isDefault)
        assertEquals("home", b.default)
    }

    // ---- the fallback decision: every transport outcome ends on one line ----

    @Test fun everyTransportFailureHasItsStatedLine() {
        val body = chat(canonical)
        for (code in listOf(404, 401, 500)) {
            val r = ProfilesAsk.fromChat(code, body) as Result.Failed
            assertEquals("$code", Failure.NO_BLOCK, r.failure)
            assertEquals(code, r.code)
            assertEquals(ProfilesAsk.FALLBACK_NO_BLOCK, ProfilesAsk.line(r))
        }
        val timedOut = ProfilesAsk.fromChat(-1, "", timedOut = true) as Result.Failed
        assertEquals(Failure.TIMEOUT, timedOut.failure)
        assertEquals(ProfilesAsk.FALLBACK_TIMEOUT, ProfilesAsk.line(timedOut))
        // A transport failure that is not the 45 s budget is still silence, not "slow".
        val noAnswer = ProfilesAsk.fromChat(-1, "") as Result.Failed
        assertEquals(Failure.NO_BLOCK, noAnswer.failure)

        val lines = listOf(ProfilesAsk.FALLBACK_NO_BLOCK, ProfilesAsk.FALLBACK_NO_NAMES, ProfilesAsk.FALLBACK_TIMEOUT)
        assertEquals("this host did not answer with a profile list", lines[0])
        assertEquals("this host named no usable profiles", lines[1])
        assertEquals("the host did not answer in time", lines[2])
    }

    @Test fun a200ThatIsNotAnAnswerIsTheFallback() {
        val bodies = listOf(
            "",                                             // empty
            "not json",                                     // a proxy's page
            "{}",                                           // no choices
            """{"choices":[]}""",                           // no choice
            """{"choices":[{}]}""",                         // no message
            chat("""Sure! The profiles are home and work."""),  // prose instead of a block
            chat("""I can't share that. [[vox:profiles]]"""),    // a refusal in prose
        )
        for (body in bodies) {
            val r = ProfilesAsk.fromChat(200, body)
            assertEquals(body, Failure.NO_BLOCK, (r as Result.Failed).failure)
        }
        // A tool call carries no text: the reply is not a profile list.
        val tool = """{"choices":[{"message":{"role":"assistant","content":null,""" +
            """"tool_calls":[{"id":"c1","type":"function","function":{"name":"profile_list","arguments":"{}"}}]}}]}"""
        assertEquals(Failure.NO_BLOCK, (ProfilesAsk.fromChat(200, tool) as Result.Failed).failure)
        assertNull(ProfilesAsk.assistantText(tool))
    }

    @Test fun anAssistantTextArrayIsRead() {
        val body = """{"choices":[{"message":{"role":"assistant","content":[""" +
            """{"type":"text","text":"```vox-profiles\nprofile=home\n```"}]}}]}"""
        val r = ProfilesAsk.fromChat(200, body) as Result.Named
        assertEquals(listOf("home"), r.served.map { it.name })
        assertEquals(ProfilesAsk.Source.CONVERSATION, r.source)
    }

    @Test fun aParsedReplyCarriesItsSourceAndCount() {
        val r = ProfilesAsk.fromChat(200, chat(canonical)) as Result.Named
        assertEquals(listOf("home", "work"), r.served.map { it.name })
        assertEquals("home", r.default)
        assertEquals(ProfilesAsk.Source.CONVERSATION, r.source)
        assertEquals("2 profiles from this host", ProfilesAsk.line(r))
    }

    // ---- the fast path: opportunistic, and never the only way ----

    @Test fun theProfilesRouteIsUsedOnlyWhenItAnswersInTheDocumentedShape() {
        val served = """{"object":"list","default":"home","profiles":[{"name":"home","default":true},{"name":"work"}]}"""
        val fast = ProfilesAsk.fastPath(200, served)!!
        assertEquals(ProfilesAsk.Source.FAST_PATH, fast.source)
        assertEquals(listOf("home", "work"), fast.served.map { it.name })
        assertEquals("home", fast.default)

        // Everything else is silence and the caller falls through to the conversational ask.
        assertNull(ProfilesAsk.fastPath(404, served))
        assertNull(ProfilesAsk.fastPath(401, served))
        assertNull(ProfilesAsk.fastPath(500, served))
        assertNull(ProfilesAsk.fastPath(200, "{}"))
        assertNull(ProfilesAsk.fastPath(200, "not json"))

        // A host-supplied name gets the same charset rule before it reaches a probe URL.
        val dirty = ProfilesAsk.fastPath(200, """{"profiles":[{"name":"../x"},{"name":"home"}]}""")!!
        assertEquals(listOf("home"), dirty.served.map { it.name })
        assertNull(ProfilesAsk.fastPath(200, """{"profiles":[{"name":"../x"}]}"""))
        assertTrue(ProfilesAsk.allowed("home_1"))
        assertFalse(ProfilesAsk.allowed("../x"))
    }

    // ---- probes: the list is a hint, a probe is the proof ----

    @Test fun theDefaultProbesAtTheRootAndNamedOnesUnderP() {
        val served = listOf(HostProfiles.Served("home", true), HostProfiles.Served("work"))
        val ps = ProfilesAsk.probes("$base/p/work", served)
        assertEquals("$base/v1/models", ps[0].url)
        assertEquals("$base/p/work/v1/models", ps[1].url)
        assertTrue(ps[0].isDefault)
        assertFalse(ps[1].isDefault)
    }

    @Test fun onlyA200IsUsable() {
        assertTrue(ProfilesAsk.probeSucceeded(200))
        for (code in listOf(401, 403, 404, 500, 502, -1)) assertFalse("$code", ProfilesAsk.probeSucceeded(code))
    }

    @Test fun probesRunAtMostEightAtATime() {
        val served = (1..9).map { HostProfiles.Served("p$it") }
        val waves = ProfilesAsk.batches(ProfilesAsk.probes(base, served))
        assertEquals(listOf(8, 1), waves.map { it.size })
        assertEquals(9, waves.flatten().size)
        assertEquals(ProfilesAsk.PROBE_CONCURRENCY, 8)
        assertEquals(ProfilesAsk.PROBE_TIMEOUT_MS, 10_000)
    }

    @Test fun onlyProvenNamesAreAddedAndUnreachableOnesAreReported() {
        val served = listOf(
            HostProfiles.Served("home", true),
            HostProfiles.Served("work"),
            HostProfiles.Served("lab"),
        )
        val result = Result.Named(served, "home", ProfilesAsk.Source.CONVERSATION)
        val applied = ProfilesAsk.settle(
            emptyList(), base, "enc:aaa", result, "",
            mapOf("home" to true, "work" to true, "lab" to false),
        )
        assertEquals(listOf("home", "work"), applied.usable.map { it.name })
        assertEquals(listOf("lab"), applied.unreachable)
        assertEquals(listOf("home", "work"), applied.merge.list.map { it.name })
        assertNull(Profiles.find(applied.merge.list, "lab"))   // a failed probe never adds
        assertEquals("2 profiles added · 1 not reachable", applied.line)
        assertEquals("2 profiles added · 1 not reachable",
            ProfilesAsk.line(result, added = 2, unreachable = 1))

        // Every name refused: nothing added, and still one honest line, never an empty list.
        val none = ProfilesAsk.settle(emptyList(), base, "enc:aaa", result, "",
            mapOf("home" to false, "work" to false, "lab" to false))
        assertEquals(emptyList<String>(), none.merge.added)
        assertEquals("0 profiles added · 3 not reachable", none.line)
    }

    @Test fun aFailedProbeMarksASavedProfileUnavailableButNeverDeletesIt() {
        val gone = Profiles.Profile("work", "$base/p/work", "enc:bbb", scope = "phone", provider = "openrouter")
        val result = Result.Named(listOf(HostProfiles.Served("work")), "", ProfilesAsk.Source.CONVERSATION)
        val applied = ProfilesAsk.settle(listOf(gone), base, "enc:aaa", result, "",
            mapOf("work" to false))
        val kept = Profiles.find(applied.merge.list, "work")!!
        assertEquals(listOf("work"), applied.merge.unavailable)
        assertEquals("$base/p/work", kept.url)     // never repointed
        assertEquals("enc:bbb", kept.keyEnc)       // never re-keyed
        assertEquals("phone", kept.scope)          // never rescoped
        assertEquals("openrouter", kept.provider)
    }

    @Test fun theDefaultThatFailedItsProbeLeavesNoDefaultClaimed() {
        val served = listOf(HostProfiles.Served("home", true), HostProfiles.Served("work"))
        val applied = ProfilesAsk.settle(emptyList(), base, "enc:aaa",
            Result.Named(served, "home", ProfilesAsk.Source.CONVERSATION), "",
            mapOf("home" to false, "work" to true))
        assertEquals(listOf("work"), applied.usable.map { it.name })
        assertEquals("$base/p/work", Profiles.find(applied.merge.list, "work")!!.url)
        assertNull(Profiles.find(applied.merge.list, "home"))
    }

    // ---- cache and refresh ----

    @Test fun theSwitcherNeverAsks() {
        assertEquals(AskDecision.READ_CACHE, ProfilesAsk.decide(AskKind.SWITCHER_OPEN, 0L, now, false))
        assertEquals(AskDecision.READ_CACHE, ProfilesAsk.decide(AskKind.SWITCHER_OPEN, now - 1_000L, now, false))
        assertEquals(AskDecision.READ_CACHE, ProfilesAsk.decide(AskKind.SWITCHER_OPEN, now - 1_000L, now, true))
    }

    @Test fun aManualRefreshInsideAMinuteIsRefused() {
        assertEquals(AskDecision.REFUSE_RECENT, ProfilesAsk.decide(AskKind.REFRESH, now - 59_999L, now, false))
        assertEquals(AskDecision.REFUSE_RECENT, ProfilesAsk.decide(AskKind.REFRESH, now - 1L, now, false))
        assertEquals(AskDecision.NOW, ProfilesAsk.decide(AskKind.REFRESH, now - 60_000L, now, false))
        assertEquals(AskDecision.NOW, ProfilesAsk.decide(AskKind.REFRESH, now - 600_000L, now, false))
        assertEquals(AskDecision.NOW, ProfilesAsk.decide(AskKind.REFRESH, 0L, now, false))
    }

    @Test fun theAskRunsOnConnectAndWaitsForALiveCall() {
        assertEquals(AskDecision.NOW, ProfilesAsk.decide(AskKind.CONNECT, 0L, now, false))
        // A connect during a call is deferred, not dropped: the app re-asks at hang-up.
        assertEquals(AskDecision.DEFER, ProfilesAsk.decide(AskKind.CONNECT, 0L, now, true))
        assertEquals(AskDecision.DEFER, ProfilesAsk.decide(AskKind.REFRESH, 0L, now, true))
        assertEquals(AskDecision.DEFER, ProfilesAsk.decide(AskKind.REFRESH, now - 90_000L, now, true))
        // The call ended: the deferred refresh is now allowed.
        assertEquals(AskDecision.NOW, ProfilesAsk.decide(AskKind.REFRESH, now - 90_000L, now, false))
    }

    @Test fun freshnessIsShownNotHidden() {
        assertEquals("profiles from this host · checked 4 min ago", ProfilesAsk.freshness(now - 4 * 60_000L, now))
        assertEquals("profiles from this host · checked 4 min ago", ProfilesAsk.freshness(now - 4 * 60_000L - 59_000L, now))
        assertEquals("profiles from this host · checked just now", ProfilesAsk.freshness(now - 20_000L, now))
        assertEquals("profiles from this host · checked 45 min ago", ProfilesAsk.freshness(now - 45 * 60_000L, now))
        assertEquals("profiles from this host · checked 1 h ago", ProfilesAsk.freshness(now - 65 * 60_000L, now))
    }

    // ---- the ask itself ----

    @Test fun theRequestIsOnePlainChatTurnWithTheMarkersExactText() {
        val expected = "[[vox:profiles]] Which Hermes profiles does this gateway serve to this key? " +
            "Reply with one fenced vox-profiles block and nothing else."
        assertEquals(expected, ProfilesAsk.ASK)
        assertEquals("[[vox:profiles]]", ProfilesAsk.MARKER)
        assertEquals("/v1/chat/completions", ProfilesAsk.PATH)
        assertEquals(45_000, ProfilesAsk.TIMEOUT_MS)

        val body = ProfilesAsk.requestBody("hermes-agent")
        val root = Json.parse(body) as com.hermesvox.core.JObj
        assertEquals("hermes-agent", (root["model"] as com.hermesvox.core.JStr).value)
        assertEquals(false, (root["stream"] as com.hermesvox.core.JBool).value)
        val msg = ((root["messages"] as com.hermesvox.core.JArr).items.single() as com.hermesvox.core.JObj)
        assertEquals("user", (msg["role"] as com.hermesvox.core.JStr).value)
        assertEquals(expected, (msg["content"] as com.hermesvox.core.JStr).value)
        // A blank model still names the one route the app uses.
        assertTrue(ProfilesAsk.requestBody("").contains("\"model\":\"hermes-agent\""))
    }

    // ---- the invariant that a switch ends the call, however the profile arrived ----

    @Test fun aSwitchFromAHostPopulatedProfileStillEndsTheCall() {
        val applied = ProfilesAsk.settle(
            emptyList(), base, "enc:aaa",
            Result.Named(listOf(HostProfiles.Served("work")), "", ProfilesAsk.Source.CONVERSATION),
            "", mapOf("work" to true),
        )
        val hostProfile = Profiles.find(applied.merge.list, "work")!!
        for (live in listOf(true, false)) {
            val plan = ConversationReset.plan(ConversationReset.Trigger.PROFILE_SWITCH, callLive = live)
            assertEquals(live, plan.endCall)
            assertFalse("a switch does not re-arm the call", plan.rearm)
        }
        // The refresh itself never switches: nothing in the merge names an active profile.
        val refresh = ProfilesAsk.settle(
            listOf(hostProfile), base, "enc:aaa",
            Result.Named(listOf(HostProfiles.Served("work")), "", ProfilesAsk.Source.CONVERSATION),
            "", mapOf("work" to true),
        )
        assertFalse(refresh.merge.changed)
        assertEquals(hostProfile, refresh.merge.list.single())
    }
}
