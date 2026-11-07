package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The header keeps the other two switches reachable (#second phone pass): a profile switch
 * keeps the profile's name in the title (and the call screen uses the same words), and a
 * session switch still says the session's title. Sessions and Profiles are ordinary parts of
 * the app since D39, so nothing gated stands in front of either.
 */
class HeaderSwitchesTest {

    private val src = "src/main/java/com/hermesvox"
    private val main = File(src, "MainActivity.kt").readText()

    @Test fun aProfileSwitchKeepsTheProfileAndASessionSwitchNamesTheSession() {
        // The profile's name survives in the title once there is a choice...
        assertEquals("HERMES · WORK", Profiles.headerLabel("Hermes", "work", 2))
        // ...and the call screen uses the same words, in the same order.
        assertEquals("Hermes · work", Header.callTitle("Hermes", "work", 2))
        // A session switch is announced with the session's own title.
        val notice = HeaderNotice()
        assertTrue(notice.arm("Trail conditions", 0L))
        assertEquals("Trail conditions", notice.text(0L))
        // An untitled session says so rather than showing nothing.
        assertEquals("an untitled session", Sessions.Row(id = "s1").label)
        // Sessions and Profiles left the Alpha gate (D39): no gate stands in front of either,
        // and the gate has no set left that could name them.
        assertFalse(main.contains("Sessions.refusal(AlphaGate"))
        assertFalse(AlphaGate.FeatureSet.entries.any { it.name == "SESSIONS" || it.name == "PROFILES" })
    }
}
