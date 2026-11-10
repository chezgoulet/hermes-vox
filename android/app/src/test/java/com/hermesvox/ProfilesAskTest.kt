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
    /** A reply written on Windows is the same reply; the fence regex must not reject CRLF. */
    @Test fun aWindowsLineEndedReplyIsStillABlock() {
        val b = ok("```vox-profiles\r\ndefault=home\r\nprofile=home\r\nprofile=work\r\n```\r\n")
        assertEquals(listOf("home", "work"), b.served.map { it.name })
        assertEquals("home", b.default)
    }

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
        assertEquals(0, (onlyUnknown as Parsed.Echoed).dropped)
        assertEquals(2, onlyUnknown.seen)
        // A block that names nothing is its own failure, distinct from a block of bad names.
        assertEquals(Failure.EMPTY_BLOCK, (ProfilesAsk.fromChat(200, chat("```vox-profiles\nformat=1\n```")) as Result.Failed).failure)
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
        // `Work` is not illegal: Hermes ids are lowercase, so the parser canonicalises it.
        assertEquals(listOf("work", "ok_1"), b.served.map { it.name })
        assertEquals("work", b.default)
        assertEquals(3, b.dropped)                     // a/b, .hidden, empty

        // Nothing survives the charset: that is its own line, with the count that diagnoses it.
        val none = ProfilesAsk.fromChat(200, chat(
            "```vox-profiles\nprofile=../etc\nprofile=a/b\nprofile=.hidden\nprofile=\n```"
        ))
        assertEquals(Failure.UNUSABLE, (none as Result.Failed).failure)
        assertEquals(4, none.dropped)
        assertEquals("the host named 4 profiles, none in a usable form", ProfilesAsk.line(none))
    }

    @Test fun theCharsetBoundariesAreTheSpecs() {
        assertEquals(listOf("a"), ok("```vox-profiles\nprofile=a\n```").served.map { it.name })
        assertEquals(listOf("a1-b_2"), ok("```vox-profiles\nprofile=a1-b_2\n```").served.map { it.name })
        val longest = "a" + "b".repeat(63)                 // 64 chars: the host's maximum
        assertEquals(listOf(longest), ok("```vox-profiles\nprofile=$longest\n```").served.map { it.name })
        val tooLong = "a" + "b".repeat(64)                 // 65: dropped
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
        assertEquals(0, p.seen)
        val r = ProfilesAsk.fromChat(200, chat("```vox-profiles\n```")) as Result.Failed
        assertEquals(Failure.EMPTY_BLOCK, r.failure)
        assertEquals(ProfilesAsk.FALLBACK_EMPTY_BLOCK, ProfilesAsk.line(r))
    }

    /** A one-profile host may answer with only its default line. */
    /** A default line naming something the host does not call `default` is probed as a profile. */
    @Test fun aDefaultLineThatIsNotTheBuiltInNameIsProbedUnderP() {
        val b = ok("```vox-profiles\ndefault=none\n```")
        val probe = ProfilesAsk.probes(base, b.served).single()
        assertEquals("$base/p/none/v1/models", probe.url)
        // The built-in does answer at the root.
        assertEquals("$base/v1/models", ProfilesAsk.probes(base, listOf(
            HostProfiles.Served("default", isDefault = true),
        )).single().url)
    }

    @Test fun aDefaultLineAloneIsTheWholeServedList() {
        val b = ok("```vox-profiles\ndefault=default\n```")
        assertEquals(listOf("default"), b.served.map { it.name })
        assertEquals("default", b.default)
        assertTrue(b.served.single().isDefault)

        val r = ProfilesAsk.fromChat(200, chat("```vox-profiles\ndefault=default\n```")) as Result.Named
        assertEquals("1 profile from this host", ProfilesAsk.line(r))
    }

    /** When no `profile=`/`default=` line exists, a bare line that matches the rule is a name. */
    @Test fun aBareListIsReadAsNames() {
        val b = ok("```vox-profiles\nhome\nwork\nlab_2\n```")
        assertEquals(listOf("home", "work", "lab_2"), b.served.map { it.name })
        assertEquals("", b.default)
        assertFalse(b.served.any { it.isDefault })
    }

    @Test fun bulletsAndTheListMarkerAreTolerated() {
        val b = ok(
            """
            ```vox-profiles
            - home
            * work
            + lab
            ◆ office
            ```
            """.trimIndent()
        )
        assertEquals(listOf("home", "work", "lab", "office"), b.served.map { it.name })

        val keyed = ok("```vox-profiles\n- profile=home\n◆ default=home\n```")
        assertEquals(listOf("home"), keyed.served.map { it.name })
        assertEquals("home", keyed.default)
    }

    @Test fun aColonSeparatorIsAccepted() {
        val b = ok("```vox-profiles\ndefault: home\nprofile: home\nprofile: work\n```")
        assertEquals(listOf("home", "work"), b.served.map { it.name })
        assertEquals("home", b.default)
    }

    @Test fun wrappingQuotesBackticksAndTrailingPunctuationAreStripped() {
        val b = ok(
            "```vox-profiles\n" +
                "default=`home`,\n" +
                "profile=\"home\";\n" +
                "profile='work'\n" +
                "profile=◆lab\n" +
                "```"
        )
        assertEquals(listOf("home", "work", "lab"), b.served.map { it.name })
        assertEquals("home", b.default)
    }

    @Test fun uppercaseNamesAreCanonicalisedNotEmptyRejected() {
        val b = ok("```vox-profiles\ndefault=Home\nprofile=HOME\nprofile=Geek\n```")
        assertEquals(listOf("home", "geek"), b.served.map { it.name })
        assertEquals("home", b.default)
        assertTrue(b.served.first { it.name == "home" }.isDefault)
    }

    @Test fun slashesAndDotsAreStillRejected() {
        val p = ProfilesAsk.parse("```vox-profiles\nprofile=a/b\nprofile=.hidden\n```")
        assertTrue(p is Parsed.Echoed)
        assertEquals(2, (p as Parsed.Echoed).dropped)
        val r = ProfilesAsk.fromChat(200, chat("```vox-profiles\nprofile=a/b\nprofile=.hidden\n```")) as Result.Failed
        assertEquals(Failure.UNUSABLE, r.failure)
        assertEquals("the host named 2 profiles, none in a usable form", ProfilesAsk.line(r))
        assertEquals("the host named 1 profile, none in a usable form",
            ProfilesAsk.line(Result.Failed(Failure.UNUSABLE, 200, dropped = 1)))
    }

    /** The log can distinguish an empty block from a block of rejected names. */
    @Test fun aFailureCarriesTheCountsTheLogPrints() {
        val unusable = ProfilesAsk.fromChat(200, chat(
            "```vox-profiles\nprofile=a/b\nprofile=.hidden\nformat=1\n```"
        )) as Result.Failed
        assertEquals(Failure.UNUSABLE, unusable.failure)
        assertEquals(2, unusable.dropped)
        assertEquals(3, unusable.seen)

        val empty = ProfilesAsk.fromChat(200, chat("```vox-profiles\n\nformat=1\n```")) as Result.Failed
        assertEquals(Failure.EMPTY_BLOCK, empty.failure)
        assertEquals(0, empty.dropped)
        assertEquals(1, empty.seen)
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

        val lines = listOf(
            ProfilesAsk.FALLBACK_NO_BLOCK,
            ProfilesAsk.FALLBACK_EMPTY_BLOCK,
            ProfilesAsk.FALLBACK_TIMEOUT,
        )
        assertEquals("this host did not answer with a profile list", lines[0])
        assertEquals("the host's reply had a block but no profile names", lines[1])
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
        val served = listOf(HostProfiles.Served("default", true), HostProfiles.Served("work"))
        val ps = ProfilesAsk.probes("$base/p/work", served)
        assertEquals("$base/v1/models", ps[0].url)
        assertEquals("$base/p/work/v1/models", ps[1].url)
        assertTrue(ps[0].isDefault)
        assertFalse(ps[1].isDefault)
    }

    /**
     * The bug this pins: the picker appended `/v1/models` to a probe URL that already ended
     * in it, asked `/v1/models/v1/models`, took the 404 as proof, and told the owner his
     * working profile was unreachable. A probe plan must be idempotent — however much of the
     * address is already carried, the URL is the same — and it must carry exactly one route.
     */
    @Test fun aProbeIsIdempotentAndEndsInExactlyOneModelsPath() {
        val served = listOf(HostProfiles.Served("default", true), HostProfiles.Served("work"))
        val once = ProfilesAsk.probes(base, served)
        assertEquals(listOf("$base/v1/models", "$base/p/work/v1/models"), once.map { it.url })
        for (suffix in listOf(
            "/", "/v1/models", "/v1/models/", "/v1/chat/completions", "/v1/runs",
            "/v1/responses", "/p/work", "/p/work/v1/models", "/p/work/v1/chat/completions",
            "/v1/models?limit=1",
        )) {
            assertEquals(suffix, once, ProfilesAsk.probes(base + suffix, served))
        }
        for (p in once) assertEquals(p.url, 1, Regex("/v1/models").findAll(p.url).count())
    }

    /** A persisted profile URL is data, not a promise: a saved `…/v1/models` must still
     *  probe the profile it names, not a route beneath the route. */
    @Test fun aSavedProfileUrlCarryingAnEndpointStillProbesCorrectly() {
        val savedDefault = Profiles.Profile("default", "$base/v1/models", "enc:aaa")
        val default = ProfilesAsk.probes(savedDefault.url, listOf(HostProfiles.Served("default", true)))
        assertEquals("$base/v1/models", default.single().url)

        val savedNamed = Profiles.Profile("work", "$base/v1/models", "enc:bbb")
        val named = ProfilesAsk.probes(savedNamed.url, listOf(HostProfiles.Served("work")))
        assertEquals("$base/p/work/v1/models", named.single().url)

        // The addressing base is what a switch writes, and it is the host again.
        assertEquals(base, HostProfiles.rootBase(savedDefault.url))
        assertEquals(base, HostProfiles.rootBase(savedNamed.url))
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
        val served = listOf(HostProfiles.Served("default", true), HostProfiles.Served("work"))
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

    /**
     * The ask is a turn the user pays for, so a fresh answer is rendered, not refreshed.
     * The window is fifteen minutes, stated here so a later edit has to change a named
     * number on purpose; the one-minute refresh guard is unchanged beside it.
     */
    @Test fun aFreshCacheSendsNoTurnAndAHumanRefreshDoes() {
        assertEquals(15 * 60_000L, ProfilesAsk.CACHE_FRESH_MS)
        assertEquals(60_000L, ProfilesAsk.REFRESH_MIN_MS)

        // A connect inside the window reads the cache: the sheet shows its age, no turn.
        assertEquals(AskDecision.READ_CACHE, ProfilesAsk.decide(AskKind.CONNECT, now - 1L, now, false))
        assertEquals(AskDecision.READ_CACHE, ProfilesAsk.decide(AskKind.CONNECT, now - 14 * 60_000L, now, false))
        assertEquals(AskDecision.READ_CACHE,
            ProfilesAsk.decide(AskKind.CONNECT, now - ProfilesAsk.CACHE_FRESH_MS + 1L, now, false))
        assertEquals(AskDecision.READ_CACHE, ProfilesAsk.decide(AskKind.CONNECT, now, now, false))

        // At the boundary and beyond it, a connect asks again. No stamp means never asked.
        assertEquals(AskDecision.NOW,
            ProfilesAsk.decide(AskKind.CONNECT, now - ProfilesAsk.CACHE_FRESH_MS, now, false))
        assertEquals(AskDecision.NOW, ProfilesAsk.decide(AskKind.CONNECT, now - 60 * 60_000L, now, false))
        assertEquals(AskDecision.NOW, ProfilesAsk.decide(AskKind.CONNECT, 0L, now, false))

        // Opening the sheet is still always free, and a live call still defers.
        assertEquals(AskDecision.READ_CACHE, ProfilesAsk.decide(AskKind.SWITCHER_OPEN, now - 1_000L, now, false))
        assertEquals(AskDecision.DEFER, ProfilesAsk.decide(AskKind.CONNECT, now - 1_000L, now, true))

        // A refresh is what sends the turn, even inside the fresh window — but the existing
        // "checked less than a minute ago" guard still holds it back inside a minute.
        assertEquals(AskDecision.NOW, ProfilesAsk.decide(AskKind.REFRESH, now - 2 * 60_000L, now, false))
        assertEquals(AskDecision.REFUSE_RECENT, ProfilesAsk.decide(AskKind.REFRESH, now - 59_999L, now, false))
        assertEquals(AskDecision.NOW, ProfilesAsk.decide(AskKind.REFRESH, now - ProfilesAsk.REFRESH_MIN_MS, now, false))
    }

    @Test fun freshnessIsShownNotHidden() {
        assertEquals("profiles from this host · checked 4 min ago", ProfilesAsk.freshness(now - 4 * 60_000L, now))
        assertEquals("profiles from this host · checked 4 min ago", ProfilesAsk.freshness(now - 4 * 60_000L - 59_000L, now))
        assertEquals("profiles from this host · checked just now", ProfilesAsk.freshness(now - 20_000L, now))
        assertEquals("profiles from this host · checked 45 min ago", ProfilesAsk.freshness(now - 45 * 60_000L, now))
        assertEquals("profiles from this host · checked 1 h ago", ProfilesAsk.freshness(now - 65 * 60_000L, now))
    }

    // ---- the ask itself ----

    /** The whole turn, pinned word for word: a change to the boundary is a deliberate edit. */
    private val askText =
        "[[vox:profiles]] This is a question, not a task. Which Hermes profiles does this " +
            "gateway serve to this key? Reply with one fenced vox-profiles block and nothing " +
            "else. Inside it: one optional `default=<name>` line and one `profile=<name>` " +
            "line per profile, including the default. Names are lowercase letters, digits, " +
            "`-` or `_`, at most 64 characters. No other lines, no commentary, no table. If " +
            "you cannot answer, say plainly that the list cannot be produced. Do not modify " +
            "this host, its configuration, its profiles, its memories, its sessions or its " +
            "files. Do not run any command that changes them. If producing the list appears " +
            "to need a change to the host, do not make it: say what would be needed instead."

    @Test fun aStreamedAnswerIsReadFromItsDeltasAndAWholeOneStillWorks() {
        // The streamed shape: one JSON object per data: line, [DONE] at the end.
        val sse = """
            data: {"choices":[{"delta":{"role":"assistant"}}]}

            data: {"choices":[{"delta":{"content":"```vox-profiles\n"}}]}

            data: {"choices":[{"delta":{"content":"default=default"}}]}

            data: [DONE]
        """.trimIndent()
        assertTrue(ProfilesAsk.assistantTextFromStream(sse)!!.contains("default=default"))

        // A server that ignores stream:true and answers whole is still read.
        val whole = """{"choices":[{"message":{"content":"```vox-profiles\nprofile=work\n```"}}]}"""
        assertTrue(ProfilesAsk.assistantTextFromStream(whole)!!.contains("profile=work"))

        // A tool call carries no text; it contributes nothing rather than corrupting the block.
        val tool = """data: {"choices":[{"delta":{"tool_calls":[{"id":"t1"}]}}]}""" + "\n\ndata: [DONE]"
        assertNull(ProfilesAsk.assistantTextFromStream(tool))
        assertNull(ProfilesAsk.assistantTextFromStream(""))
    }

    @Test fun theRequestIsOnePlainChatTurnWithTheMarkersExactText() {
        val expected = askText
        assertEquals(expected, ProfilesAsk.ASK)
        assertEquals("[[vox:profiles]]", ProfilesAsk.MARKER)
        assertEquals("/v1/chat/completions", ProfilesAsk.PATH)
        assertEquals(45_000, ProfilesAsk.TIMEOUT_MS)

        val body = ProfilesAsk.requestBody("hermes-agent")
        val root = Json.parse(body) as com.hermesvox.core.JObj
        assertEquals("hermes-agent", (root["model"] as com.hermesvox.core.JStr).value)
        // #533: streamed, because that is the route that registers the approval notifier.
        // A non-streaming turn could run a tool the phone never saw and could not answer.
        assertEquals(true, (root["stream"] as com.hermesvox.core.JBool).value)
        val msg = ((root["messages"] as com.hermesvox.core.JArr).items.single() as com.hermesvox.core.JObj)
        assertEquals("user", (msg["role"] as com.hermesvox.core.JStr).value)
        assertEquals(expected, (msg["content"] as com.hermesvox.core.JStr).value)
        // A blank model still names the one route the app uses.
        assertTrue(ProfilesAsk.requestBody("").contains("\"model\":\"hermes-agent\""))
    }

    /**
     * The turn is a question with a boundary. An agent reading it may be a full operator
     * with a terminal, so the prohibition and the permitted "I cannot" answer are pinned
     * here: a later edit cannot quietly drop either and leave an instruction with latitude
     * again. This is the test that would have caught the turn that deleted a profile.
     */
    @Test fun theAskIsAQuestionThatForbidsChangesToTheHost() {
        val ask = ProfilesAsk.ASK
        // The marker and the label the parser keys on survive byte-identical.
        assertTrue(ask, ask.startsWith(ProfilesAsk.MARKER + " "))
        assertTrue(ask, ask.contains(ProfilesAsk.LABEL))
        assertEquals("[[vox:profiles]]", ProfilesAsk.MARKER)
        assertEquals("vox-profiles", ProfilesAsk.LABEL)

        // The shape travels in the ask, because the answering profile may never have been
        // taught the skill that used to carry it.
        assertTrue("the ask must name the profile line", ask.contains("profile=<name>"))
        assertTrue("the ask must name the default line", ask.contains("default=<name>"))
        assertTrue("the ask must state the host's 64-character ceiling", ask.contains("at most 64 characters"))
        assertTrue("the ask must rule out commentary", ask.contains("no commentary"))

        // It says what it is, in words that cannot be read as a task.
        assertTrue(ask, ask.contains("This is a question, not a task"))

        // An explicit prohibition, naming every surface the incident touched.
        assertTrue("the ask needs a 'do not' prohibition", Regex("\\bdo not\\b", RegexOption.IGNORE_CASE).containsMatchIn(ask))
        assertTrue(ask, ask.contains("Do not modify this host"))
        for (surface in listOf("configuration", "profiles", "memories", "sessions", "files")) {
            assertTrue("the prohibition must name the host's $surface", ask.contains(surface))
        }
        // And the command that actually ran is covered in the same words.
        assertTrue(ask, ask.contains("Do not run any command that changes them"))

        // Saying the list cannot be produced is a complete answer…
        assertTrue(ask, ask.contains("say plainly that the list cannot be produced"))
        // …and a change the list seems to need is named, never made.
        assertTrue(ask, ask.contains("do not make it"))
        assertTrue(ask, ask.contains("say what would be needed"))
    }

    /**
     * No verb in the turn may read as an instruction to change something. The app already
     * issues no destructive request (`NoDestructiveRequestsTest`); this holds the same line
     * on the one text the app sends into an agent that has its own tools.
     */
    @Test fun theAskCarriesNoVerbThatAsksForAChange() {
        for (verb in listOf("delete", "remove", "create", "write", "edit")) {
            assertFalse(
                "the ask must not contain '$verb': an agent may read it as permission to act",
                Regex("\\b" + verb + "\\b", RegexOption.IGNORE_CASE).containsMatchIn(ProfilesAsk.ASK),
            )
        }
    }

    /**
     * The route comes first, always. When `GET /v1/profiles` answers, the refresh is over:
     * the conversational turn is the other branch and is never sent. The documented-shape
     * answer and every non-answer are both exercised, so "the route was tried" cannot be
     * mistaken for "the turn may still run".
     */
    @Test fun aFastPathAnswerSuppressesTheConversationalTurn() {
        val served = """{"object":"list","default":"home","profiles":[{"name":"home","default":true},{"name":"work"}]}"""
        val answered = ProfilesAsk.step(200, served)
        assertTrue("a 200 in the documented shape must end the refresh", answered is ProfilesAsk.Step.Answered)
        answered as ProfilesAsk.Step.Answered
        assertEquals(ProfilesAsk.Source.FAST_PATH, answered.result.source)
        assertEquals(listOf("home", "work"), answered.result.served.map { it.name })
        assertEquals("home", answered.result.default)

        // Anything less than an answer takes the Ask branch, which is the only one that
        // may send a turn.
        for ((code, body) in listOf(
            404 to served, 401 to served, 500 to served,
            200 to "{}", 200 to "not json",
            200 to """{"profiles":[{"name":"../x"}]}""",
        )) {
            assertEquals("$code ${body.take(20)}", ProfilesAsk.Step.Ask, ProfilesAsk.step(code, body))
        }
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
