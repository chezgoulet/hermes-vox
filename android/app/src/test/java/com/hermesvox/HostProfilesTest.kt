package com.hermesvox

import com.hermesvox.HostProfiles.Answer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #371: the switcher populates itself from the host, and degrades to the manual path when
 * the host does not advertise its profiles.
 *
 * Everything here is the client half against a *fake* response: the real `GET /v1/profiles`
 * does not exist yet, so what can be proven off-device is the contract — the shape we parse,
 * the merge rules, and the decision to fall back. The fetch itself (HTTP, headers, timeout)
 * needs the live route and is not exercised here.
 */
class HostProfilesTest {

    private val base = "https://gw.example"
    private val home = Profiles.Profile("home", base, "enc:aaa", agentName = "Hermes")
    private val work = Profiles.Profile(
        "work", "$base/p/work", "enc:bbb", agentName = "Work", scope = "phone", provider = "openrouter"
    )

    /** The shape the gateway lane is asked to serve. */
    private val served = """
        {"object":"list","default":"home","profiles":[
          {"name":"home","default":true},
          {"name":"work","default":false},
          {"name":"lab"}
        ]}
    """.trimIndent()

    private fun advertised(body: String = served): Answer.Advertised =
        HostProfiles.answer(200, body) as Answer.Advertised

    // ---- the decision: 200 populates, everything else says one line ----

    @Test fun aServedListIsReadAndTheDefaultIsNamed() {
        val a = advertised()
        assertEquals(listOf("home", "work", "lab"), a.served.map { it.name })
        assertEquals("home", a.default)
        assertTrue(a.served.first { it.name == "home" }.isDefault)
        assertEquals("3 profiles from this host", HostProfiles.line(a))
    }

    /**
     * The contract's fallback list: 404/501 (the route is not there yet), auth and server
     * errors, no answer at all, and a 200 whose body is not the shape. All of them are
     * silence — the switcher keeps the manual path and says exactly one line, so there is
     * no empty list and no spinner that never settles.
     */
    @Test fun everyFailureSaysTheOneLineAndKeepsTheManualPath() {
        val bodies = listOf("", "not json", "{}", "[]", """{"profiles":[]}""", """{"profiles":[{"default":true}]}""",
            """{"profiles":["home"]}""", """{"profiles":{"name":"home"}}""", """{"profiles":[{"name":""}]}""")
        for (code in listOf(404, 501, 401, 403, 500, 502, -1)) {
            val a = HostProfiles.answer(code, served)
            assertTrue("$code", a is Answer.Silent)
            assertEquals(HostProfiles.FALLBACK_LINE, HostProfiles.line(a))
            assertEquals("this host does not advertise its profiles", HostProfiles.line(a))
        }
        for (body in bodies) {
            val a = HostProfiles.answer(200, body)
            assertTrue(body, a is Answer.Silent)
            assertEquals(HostProfiles.FALLBACK_LINE, HostProfiles.line(a))
            // Nothing is added, nothing is removed, nothing claims a profile is unavailable.
            val m = HostProfiles.merge(listOf(home, work), base, "enc:aaa", a)
            assertEquals(listOf(home, work), m.list)
            assertTrue(m.added.isEmpty() && m.unavailable.isEmpty() && !m.changed)
        }
    }

    @Test fun anUnknownFieldIsIgnoredAndTheListStillPopulates() {
        val a = advertised(
            """{"object":"list","profiles":[{"name":"home","default":true,"future":{"deep":[1,2,3]},"weight":7}]}"""
        )
        assertEquals(listOf("home"), a.served.map { it.name })
        assertEquals("1 profile from this host", HostProfiles.line(a))
    }

    /** `id` and the OpenAI-list `data` container are read too, so the gateway lane can
     *  match `/v1/models`' shape without a client change. */
    @Test fun theAlternateSpellingsAreUnderstood() {
        val a = advertised("""{"object":"list","default":"work","data":[{"id":"home"},{"id":"work"}]}""")
        assertEquals(listOf("home", "work"), a.served.map { it.name })
        assertEquals("work", a.default)
        assertTrue(a.served.first { it.name == "work" }.isDefault)
    }

    /** A changed shape must not crash the screen — and a name the list does not contain
     *  names nothing, so no profile is treated as the root by guesswork. */
    @Test fun aRewrittenShapeDegradesToTheFallbackNotACrash() {
        assertNull(HostProfiles.parse("""{"profiles":[{"name":null}]}"""))
        assertNull(HostProfiles.parse("""{"profiles":[{"name":42}]}"""))
        assertNull(HostProfiles.parse("""{"profiles":[null]}"""))
        val unknownDefault = advertised("""{"profiles":[{"name":"home"}],"default":"elsewhere"}""")
        assertEquals("", unknownDefault.default)
        assertFalse(unknownDefault.served.single().isDefault)
    }

    // ---- the merge: add without typing, never repoint or re-key ----

    @Test fun listedProfilesBecomeEntriesWithoutTheUserTypingAnything() {
        val m = HostProfiles.merge(listOf(home), base, "enc:aaa", advertised())
        assertEquals(listOf("home", "work", "lab"), m.list.map { it.name })
        assertEquals(listOf("work", "lab"), m.added)
        val lab = Profiles.find(m.list, "lab")!!
        assertEquals("$base/p/lab", lab.url)
        // The key is the one this host already authenticated — it never left its host.
        assertEquals("enc:aaa", lab.keyEnc)
        // The name is display only; scope and provider stay undeclared because the host
        // did not report them. They are NOT copied from the active connection.
        assertEquals("lab", lab.agentName)
        assertEquals("", lab.scope)
        assertEquals("", lab.provider)
        assertEquals("hermes-agent", lab.model)
    }

    @Test fun theDefaultProfileAnswersAtTheHostItself() {
        assertEquals(base, HostProfiles.urlFor(base, "home", isDefault = true))
        assertEquals("$base/p/work", HostProfiles.urlFor(base, "work", isDefault = false))
        // Asking through a named profile still expands to the right host base.
        assertEquals("$base/p/lab", HostProfiles.urlFor("$base/p/work", "lab", isDefault = false))
        assertEquals(base, HostProfiles.rootBase("$base/p/work/"))
        assertEquals(base, HostProfiles.rootBase("$base"))
    }

    @Test fun aSavedProfileIsNeverRepointedReKeyedOrRescoped() {
        val edited = work.copy(agentName = "", scope = "phone")
        val m = HostProfiles.merge(listOf(home, edited), base, "enc:aaa", advertised())
        val w = Profiles.find(m.list, "work")!!
        // The host's answer is about names, not addresses: the saved connection stands.
        assertEquals("$base/p/work", w.url)
        assertEquals("enc:bbb", w.keyEnc)
        assertEquals("phone", w.scope)
        assertEquals("openrouter", w.provider)
        // Only a blank display name is filled in.
        assertEquals("work", w.agentName)
        assertFalse(m.changed && m.added.contains("work"))
    }

    @Test fun aConnectionSavedUnderAnotherNameIsNotDuplicated() {
        // The seeded first profile is named after the agent, and addresses the host root —
        // exactly where the host's default profile answers.
        val seeded = Profiles.Profile("Hermes", base, "enc:aaa", agentName = "Hermes")
        val m = HostProfiles.merge(listOf(seeded), base, "enc:aaa", advertised())
        assertEquals(listOf("Hermes", "work", "lab"), m.list.map { it.name })
        assertTrue(m.unavailable.isEmpty())
    }

    @Test fun aProfileTheHostNoLongerServesIsKeptAndMarkedUnavailable() {
        val gone = Profiles.Profile("old", "$base/p/old", "enc:bbb")
        val m = HostProfiles.merge(listOf(home, gone), base, "enc:aaa", advertised())
        assertTrue(Profiles.find(m.list, "old") != null)   // never deleted: the key is the user's
        assertEquals("enc:bbb", Profiles.find(m.list, "old")!!.keyEnc)
        assertEquals(listOf("old"), m.unavailable)
    }

    @Test fun aProfileOnAnotherHostIsNotJudgedByThisAnswer() {
        val other = Profiles.Profile("office", "https://other.example/p/office", "enc:ccc")
        val otherPort = Profiles.Profile("lab2", "https://gw.example:8443/p/lab2", "enc:ddd")
        val gone = Profiles.Profile("old", "$base/p/old", "enc:eee")
        val m = HostProfiles.merge(listOf(home, other, otherPort, gone), base, "enc:aaa", advertised())
        // Only a profile on THIS origin can be called unavailable. Another gateway, and the
        // same host on another port, are somebody else's answer.
        assertEquals(listOf("old"), m.unavailable)
        assertTrue(Profiles.find(m.list, "office") != null)
        assertTrue(Profiles.find(m.list, "lab2") != null)
        assertEquals(listOf("work", "lab"), m.added)
        assertTrue(HostProfiles.sameOrigin("$base/p/work", base))
        assertFalse(HostProfiles.sameOrigin("https://other.example", base))
        assertFalse(HostProfiles.sameOrigin("https://gw.example:8443", base))
        assertFalse(HostProfiles.sameOrigin("", base))
    }

    @Test fun anUnusableNameIsSkippedRatherThanFailingTheList() {
        val long = "x".repeat(Profiles.NAME_MAX + 1)
        val a = advertised("""{"profiles":[{"name":"home"},{"name":"$long"},{"name":"work"}]}""")
        val m = HostProfiles.merge(emptyList(), base, "enc:aaa", a)
        assertEquals(listOf("home", "work"), m.added)
        assertNull(Profiles.find(m.list, long))
    }

    @Test fun aDuplicateNameIsReadOnce() {
        val a = advertised("""{"profiles":[{"name":"home","default":true},{"name":"HOME"}]}""")
        assertEquals(listOf("home"), a.served.map { it.name })
    }

    /** A refresh adds profiles; it must not move the user off the connection they are on.
     *  Nothing in a [HostProfiles.Merge] names an active profile — switching is the caller's,
     *  and a switch is the only thing that ends a call. */
    @Test fun aLateAnswerDoesNotSwitchTheActiveConnection() {
        val m = HostProfiles.merge(listOf(home), base, "enc:aaa", advertised())
        assertEquals(home.name, m.list.first().name)
        assertEquals(home.url, m.list.first().url)
    }

    // ---- the invariant that a switch ends the call, however the profile arrived ----

    @Test fun aSwitchFromAHostPopulatedProfileStillEndsTheCall() {
        for (live in listOf(true, false)) {
            val plan = ConversationReset.plan(ConversationReset.Trigger.PROFILE_SWITCH, callLive = live)
            assertEquals(live, plan.endCall)
            assertFalse("a switch does not re-arm the call", plan.rearm)
            assertTrue(plan.deleteServerCopy.not())
        }
        // The call that a host-populated profile ends is the same teardown as any other:
        // every trigger goes through `endCall()`, which is what clears `callLive`.
        for (t in ConversationReset.Trigger.values())
            assertTrue("$t", ConversationReset.plan(t, callLive = true).endCall)
    }
}
