package com.hermesvox

import com.hermesvox.core.Entity
import com.hermesvox.core.JObj
import com.hermesvox.core.Json
import com.hermesvox.core.StreamRoute
import com.hermesvox.core.TestServer
import com.hermesvox.core.TurnRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * M6 (0.9.1 review): the VOX.md first-call ask (and the Settings resync) must be declared
 * like the live session — the profile's scope and provider — or the document stored under
 * a profile is authored outside its scope.
 */
class EntitySessionTest {

    private val prefs = mapOf(
        "model" to "hermes-agent",
        "provider" to "openrouter",
        SessionScope.PREF to "agent:vox:profile-b",
    )

    @Test
    fun `a built session carries the profile's scope and provider on its turns`() {
        val old = StreamRoute.active
        StreamRoute.active = TurnRoute.RESPONSES
        try {
            TestServer { _, w ->
                w.send(200, "OK", """{"id":"resp_v","output":[{"type":"message","content":[{"type":"output_text","text":"ok"}]}]}""")
            }.use { srv ->
                val s = EntitySession.build(srv.url, "k", { prefs[it].orEmpty() })
                s.turnStored("[[vox:author]]")
                val r = srv.requests.single()
                assertEquals("agent:vox:profile-b", r.header(Entity.SESSION_KEY_HEADER))
                val body = Json.parse(r.body) as JObj
                assertEquals("openrouter", Json.str(body, "provider"))
                assertEquals("hermes-agent", Json.str(body, "model"))
            }
        } finally {
            StreamRoute.active = old
        }
    }

    @Test
    fun `blank prefs declare nothing and use the default model`() {
        assertEquals("hermes-agent", EntitySession.model { "" })
        assertEquals("", EntitySession.scope { "" })
    }

    @Test
    fun `no caller builds a bare session for an entity turn`() {
        val main = File("src/main/java/com/hermesvox/MainActivity.kt").readText()
        val vox = main.substringAfter("private fun startVoxFetch(").substringBefore("\n    }\n")
        assertTrue(vox, vox.contains("EntitySession.build("))
        val settings = File("src/main/java/com/hermesvox/SettingsActivity.kt").readText()
        val resync = settings.substringBefore("VoxSync.refetch(").substringAfterLast("kotlin.concurrent.thread {")
        assertTrue(resync, resync.contains("EntitySession.build("))
        assertTrue(main.substringAfter("private fun connectFromPrefs()").contains("EntitySession.build("))
    }
}
