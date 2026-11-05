package com.hermesvox

import com.hermesvox.core.TestServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #437 — browsing the host's sessions: where the list is asked, what its four failure lines
 * are, what the card says, and how a transcript is merged so a re-fetch cannot duplicate it.
 */
class SessionsTest {

    private val now = 1_800_000_000_000L   // a fixed "now": ages are arithmetic, not a clock

    private fun at(minutesAgo: Long): String {
        val ms = now - minutesAgo * 60_000L
        val cal = java.util.GregorianCalendar(java.util.TimeZone.getTimeZone("UTC")).apply {
            timeInMillis = ms
        }
        return String.format(
            "%04d-%02d-%02dT%02d:%02d:%02dZ",
            cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH) + 1,
            cal.get(java.util.Calendar.DAY_OF_MONTH), cal.get(java.util.Calendar.HOUR_OF_DAY),
            cal.get(java.util.Calendar.MINUTE), cal.get(java.util.Calendar.SECOND),
        )
    }

    // ---- where the list is asked ------------------------------------------------------

    /**
     * The one that matters on a profile install: the card is asked at the HOST, not at the
     * profile base. Proven on the wire — a fake gateway records the path it was asked for.
     */
    @Test fun the_list_is_asked_at_the_root_base_even_when_the_profile_is_scoped() {
        var asked: String? = null
        TestServer { r, w ->
            asked = r.path
            w.send(200, "OK", """{"object":"list","data":[],"limit":20,"offset":0,"has_more":false}""")
        }.use { srv ->
            val scoped = srv.url + "/p/home"
            SessionsClient.list(scoped, "k")
            assertEquals("/api/sessions?limit=20&offset=0", asked)
            // The bearer is the profile's own key; the entity scope is not sent (this is not a turn).
            assertEquals("", srv.requests.single().header(SessionScope.HEADER))
        }
    }

    @Test fun a_profile_base_and_the_host_base_are_one_rule() {
        assertEquals("https://host", Sessions.rootBase("https://host/p/home"))
        assertEquals("https://host", Sessions.rootBase("https://host/p/home/"))
        assertEquals("https://host", Sessions.rootBase("https://host"))
        // The default profile answers at the host itself: nothing to strip.
        assertEquals("https://host:8642", Sessions.rootBase("https://host:8642/"))
        // It is the profile listing's own rule, not a second copy of it.
        assertEquals(HostProfiles.rootBase("https://host/p/work"), Sessions.rootBase("https://host/p/work"))
    }

    @Test fun one_session_is_addressed_as_one_path_segment() {
        assertEquals("/api/sessions/vox_abc/messages", Sessions.messagesPath("vox_abc"))
        assertEquals("/api/sessions/20261030_235403_045e2835/messages",
            Sessions.messagesPath("20261030_235403_045e2835"))
        // An id is the host's, but it never travels raw: a slash or a space is encoded.
        assertFalse(Sessions.messagesPath("a/b").contains("a/b"))
        assertFalse(Sessions.messagesPath("a b").contains(" "))
    }

    // ---- 401, transport failure and empty are three different messages ----------------

    @Test fun a_refused_key_is_not_an_empty_list() {
        assertEquals(Sessions.Listing.KeyRejected, Sessions.listing(SessionsClient.Fetched(401, "")))
        assertEquals(Sessions.Listing.KeyRejected, Sessions.listing(SessionsClient.Fetched(403, "")))
        assertEquals(Sessions.KEY_REJECTED, Sessions.summary(Sessions.listing(SessionsClient.Fetched(401, ""))))
        // The line names the cause, and it is not the empty-host line.
        assertTrue(Sessions.KEY_REJECTED.contains("key was not accepted"))
        assertTrue(Sessions.KEY_REJECTED.contains("session list"))
        assertFalse(Sessions.KEY_REJECTED == Sessions.EMPTY)
    }

    @Test fun a_host_that_does_not_answer_says_so() {
        // No answer at all (a transport failure or a timeout is -1).
        assertEquals(Sessions.Listing.NoAnswer, Sessions.listing(SessionsClient.Fetched(-1, "")))
        // An answer that is not a list is the same fact: the host did not answer *with a list*.
        assertEquals(Sessions.Listing.NoAnswer, Sessions.listing(SessionsClient.Fetched(500, "boom")))
        assertEquals(Sessions.Listing.NoAnswer, Sessions.listing(SessionsClient.Fetched(200, "<html>proxy</html>")))
        assertEquals(Sessions.NO_ANSWER, Sessions.summary(Sessions.listing(SessionsClient.Fetched(-1, ""))))
    }

    @Test fun an_empty_host_says_no_sessions_and_not_a_failure() {
        val body = """{"object":"list","data":[],"limit":20,"offset":0,"has_more":false}"""
        assertEquals(Sessions.Listing.Empty, Sessions.listing(SessionsClient.Fetched(200, body)))
        assertEquals(Sessions.EMPTY, Sessions.summary(Sessions.listing(SessionsClient.Fetched(200, body))))
        assertTrue(Sessions.EMPTY == "no sessions on this host")
    }

    @Test fun the_four_lines_are_four_facts() {
        val lines = setOf(Sessions.KEY_REJECTED, Sessions.NO_ANSWER, Sessions.EMPTY, Sessions.TRANSCRIPT_FAILED)
        assertEquals(4, lines.size)
        for (l in lines) assertTrue("'$l' must be a sentence", l.length > 10 && !l.contains('\n'))
        assertEquals(Sessions.TRANSCRIPT_FAILED,
            "the host did not answer with this session's transcript")
    }

    // ---- the list, parsed -------------------------------------------------------------

    @Test fun the_list_carries_the_fields_the_host_serves() {
        val body = """
            {"object":"list","limit":20,"offset":0,"has_more":true,"data":[
              {"id":"20261030_235403_045e2835","source":"telegram","user_id":"cody","model":"hermes-agent",
               "title":"Convert gdrive contents to llm-wiki","started_at":"${at(120)}","ended_at":"${at(60)}"},
              {"id":"vox_03c5c905","source":"api_server","title":"Handle voice input greeting",
               "started_at":"${at(5)}","ended_at":""}
            ]}
        """.trimIndent()
        val page = Sessions.parsePage(body)!!
        assertEquals(2, page.rows.size)
        assertTrue(page.hasMore)
        val first = page.rows[0]
        assertEquals("20261030_235403_045e2835", first.id)
        assertEquals("telegram", first.source)
        assertEquals("Convert gdrive contents to llm-wiki", first.title)
        assertEquals(60L * 60_000L, now - first.activityAt)
        // The host stated no count, so there is none: not a zero we made up.
        assertNull(first.messages)
        // An untitled row says so rather than printing a blank line.
        assertEquals(Sessions.UNTITLED, Sessions.Row("vox_x").label)
    }

    @Test fun the_message_count_is_shown_only_when_the_host_states_one() {
        val body = """
            {"data":[{"id":"a","message_count":12},{"id":"b","messages":1},{"id":"c"}]}
        """.trimIndent()
        val rows = Sessions.parsePage(body)!!.rows
        assertEquals(12, rows[0].messages)
        assertEquals(1, rows[1].messages)
        assertNull("an absent count is absent, not zero", rows[2].messages)
        assertEquals("12 messages", Sessions.line(rows[0], now))
        assertEquals("1 message", Sessions.line(rows[1], now))
        assertEquals("", Sessions.line(rows[2], now))
    }

    @Test fun a_row_without_an_id_is_not_a_session() {
        val page = Sessions.parsePage("""{"data":[{"title":"no id"},{"id":"ok"}]}""")!!
        assertEquals(listOf("ok"), page.rows.map { it.id })
    }

    @Test fun a_full_page_without_has_more_still_offers_more() {
        // The host states has_more; when it does not, a full page may have more behind it.
        val body = """{"limit":2,"data":[{"id":"a"},{"id":"b"}]}"""
        assertTrue(Sessions.parsePage(body)!!.hasMore)
        val short = """{"limit":20,"data":[{"id":"a"}]}"""
        assertFalse(Sessions.parsePage(short)!!.hasMore)
    }

    // ---- newest activity first, in the card's own words -------------------------------

    @Test fun the_card_lists_newest_activity_first() {
        val rows = listOf(
            Sessions.Row("old", source = "telegram", startedAt = at(600), endedAt = at(600)),
            Sessions.Row("new", source = "api_server", startedAt = at(3), endedAt = at(2)),
            Sessions.Row("undated", source = "api_server"),
        )
        assertEquals(listOf("new", "old", "undated"), Sessions.order(rows).map { it.id })
        // A session that started earlier but was written to last sorts by its LAST activity.
        val resumed = Sessions.Row("resumed", startedAt = at(900), endedAt = at(1))
        assertEquals("resumed", Sessions.order(rows + resumed).first().id)
    }

    @Test fun a_row_says_title_source_age_and_count() {
        val row = Sessions.Row(
            id = "vox_1", title = "Handle voice input greeting", source = "api_server",
            startedAt = at(10), endedAt = at(4), messages = 7,
        )
        assertEquals("api server · 4 min ago · 7 messages", Sessions.line(row, now))
        assertEquals("just now", Sessions.age(now - 10_000L, now))
        assertEquals("2 h ago", Sessions.age(now - 120 * 60_000L, now))
        assertEquals("3 d ago", Sessions.age(now - 3 * 24 * 60 * 60_000L, now))
        // No timestamp, no age claim.
        assertEquals("", Sessions.age(0L, now))
    }

    @Test fun the_card_says_how_many_sessions_the_host_served() {
        val one = Sessions.parsePage("""{"data":[{"id":"a"}]}""")!!
        assertEquals("1 session from this host", Sessions.summary(Sessions.Listing.Ok(one)))
        val two = Sessions.parsePage("""{"data":[{"id":"a"},{"id":"b"}]}""")!!
        assertEquals("2 sessions from this host", Sessions.summary(Sessions.Listing.Ok(two)))
    }

    @Test fun a_host_timestamp_is_read_the_way_the_host_writes_it() {
        // ISO with Z, the shape the API serves.
        assertEquals(1_767_323_043_000L, Sessions.parseTime("2026-01-02T03:04:03Z"))
        // No offset means UTC — the host's own ids are UTC, so local time would move every age.
        assertEquals(1_767_323_043_000L, Sessions.parseTime("2026-01-02T03:04:03"))
        assertEquals(1_767_323_043_000L, Sessions.parseTime("2026-01-02 03:04:03"))
        // An explicit offset is honoured.
        assertEquals(1_767_323_043_000L, Sessions.parseTime("2026-01-02T05:04:03+02:00"))
        // Fractions, epoch seconds and epoch milliseconds.
        assertEquals(1_767_323_043_500L, Sessions.parseTime("2026-01-02T03:04:03.500Z"))
        assertEquals(1_767_323_043_000L, Sessions.parseTime("1767323043"))
        assertEquals(1_767_323_043_000L, Sessions.parseTime("1767323043000"))
        // Anything unreadable is 0: the card leaves the age out instead of guessing.
        assertEquals(0L, Sessions.parseTime("yesterday"))
        assertEquals(0L, Sessions.parseTime(""))
        assertEquals(0L, Sessions.parseTime("2026-13-45T99:99:99Z"))
    }

    // ---- the transcript ---------------------------------------------------------------

    @Test fun a_transcript_is_roles_and_text_in_the_hosts_order() {
        val body = """
            {"object":"list","session_id":"vox_1","data":[
              {"role":"user","content":"what is the plan","created_at":"${at(30)}"},
              {"role":"assistant","content":[{"type":"text","text":"the plan is"}],"created_at":"${at(29)}"},
              {"role":"tool","content":"ignored","created_at":"${at(28)}"}
            ],"pagination":{"limit":50,"offset":0}}
        """.trimIndent()
        val messages = Sessions.parseMessages(body)!!
        assertEquals(listOf("user", "assistant", "tool"), messages.map { it.role })
        assertEquals("the plan is", messages[1].content)
        assertEquals(listOf("You: what is the plan", "Agent: the plan is"),
            Sessions.render(messages).map { it.text })
        assertEquals(CrawlHistory.Kind.SAID, Sessions.render(messages)[0].kind)
        assertEquals(CrawlHistory.Kind.REPLY, Sessions.render(messages)[1].kind)
    }

    @Test fun a_transcript_that_is_not_a_list_says_the_host_did_not_answer() {
        assertEquals(Sessions.Transcript.NoAnswer, Sessions.transcript(SessionsClient.Fetched(200, "{}")))
        assertEquals(Sessions.Transcript.NoAnswer, Sessions.transcript(SessionsClient.Fetched(-1, "")))
        assertEquals(Sessions.Transcript.KeyRejected, Sessions.transcript(SessionsClient.Fetched(401, "")))
        // An empty transcript is a real answer: a session with nothing said in it.
        assertEquals(Sessions.Transcript.Ok(emptyList()),
            Sessions.transcript(SessionsClient.Fetched(200, """{"data":[]}""")))
    }

    // ---- merging: the screen must not duplicate what it already showed ----------------

    private fun msg(role: String, text: String, at: String, id: String = "") =
        Sessions.Message(role = role, content = text, createdAt = at, id = id)

    @Test fun a_refetch_never_duplicates_what_is_already_on_screen() {
        val shown = listOf(
            msg("user", "one", at(10)),
            msg("assistant", "two", at(9)),
        )
        // The host serves the same two, in the same shape, plus one written elsewhere meanwhile.
        val fetched = shown + msg("assistant", "three, written at the desk", at(1))
        val merged = Sessions.merge(shown, fetched)
        assertEquals(listOf("one", "two", "three, written at the desk"), merged.map { it.content })
        // And merging the same answer again changes nothing: the rule is idempotent.
        assertEquals(merged, Sessions.merge(merged, fetched))
        assertEquals(merged, Sessions.merge(shown, fetched))
    }

    @Test fun identity_is_the_hosts_id_else_the_timestamp_and_role() {
        assertEquals("id:msg_1", Sessions.identity(msg("user", "x", at(5), "msg_1")))
        assertEquals("at:${at(5)}|user", Sessions.identity(msg("user", "x", at(5))))
        // The same second, two roles: two messages, not one.
        assertTrue(Sessions.identity(msg("user", "x", at(5))) != Sessions.identity(msg("assistant", "x", at(5))))
        assertEquals("", Sessions.identity(msg("assistant", "x", "")))
    }

    @Test fun a_local_echo_is_dropped_once_the_host_serves_it() {
        // The app's own reply, recorded before the host had written it (no id, no timestamp).
        val echo = msg("assistant", "yes, I can do that", "")
        val shown = listOf(msg("user", "can you", at(4)), echo)
        // The host has not recorded it yet: the screen keeps its own line.
        assertEquals(2, Sessions.merge(shown, listOf(msg("user", "can you", at(4)))).size)
        // Now it has: the echo does not become a second copy of the same reply.
        val fetched = listOf(msg("user", "can you", at(4)), msg("assistant", "yes, I can do that", at(3)))
        val merged = Sessions.merge(shown, fetched)
        assertEquals(listOf("can you", "yes, I can do that"), merged.map { it.content })
    }

    @Test fun a_message_the_host_has_not_served_yet_survives_the_merge() {
        val shown = listOf(msg("assistant", "only on the phone", at(2)))
        val merged = Sessions.merge(shown, listOf(msg("user", "older", at(9))))
        assertEquals(listOf("older", "only on the phone"), merged.map { it.content })
    }

    // ---- the gate ---------------------------------------------------------------------

    @Test fun the_last_door_refuses_with_the_alpha_set_off() {
        val off = AlphaGate.Reader { _, _ -> false }
        assertEquals(Sessions.REFUSED_ALPHA, Sessions.refusal(off))
        // The master on but this set unselected is still off.
        val masterOnly = AlphaGate.Reader { k, d ->
            if (k == AlphaGate.KEY_MASTER) true else d
        }
        assertEquals(Sessions.REFUSED_ALPHA, Sessions.refusal(masterOnly))
        val on = AlphaGate.Reader { k, _ -> k == AlphaGate.KEY_MASTER || k == AlphaGate.FeatureSet.SESSIONS.key }
        assertNull(Sessions.refusal(on))
    }

}
