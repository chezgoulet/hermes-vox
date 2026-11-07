package com.hermesvox

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * L9 (0.9.1 review): saving Settings → Entity during a live call must end the call.
 *
 * The call was opened against the old endpoint and scope; the controller keeps them, so
 * the next turn would go to the old gateway while the screen says the new one. The save
 * therefore uses the same teardown a profile switch uses ([ConversationReset.Trigger.SETTINGS],
 * which ends a live call and does not re-arm it) and rebuilds the session from the prefs
 * just written.
 */
class EntitySaveTest {

    private fun src(path: String): String = File(path).takeIf { it.isFile }?.readText()
        ?: File("app/$path").takeIf { it.isFile }?.readText()
        ?: error("missing $path")

    @Test
    fun `the entity save hook ends the call through the one teardown`() {
        val main = src("src/main/java/com/hermesvox/MainActivity.kt")
        val hook = main.substringAfter("fun onEntitySaved()").substringBefore("\n        }")
        assertTrue("the hook must use the shared teardown", hook.contains("resetActiveConversation()"))
        assertTrue("the session must be rebuilt from the saved prefs", hook.contains("connectFromPrefs()"))
        val reset = main.substringAfter("fun resetActiveConversation()").substringBefore("\n        }")
        assertTrue("the teardown is the Settings reset (ends a live call, stays ended)",
            reset.contains("ConversationReset.Trigger.SETTINGS"))
    }

    @Test
    fun `Settings calls the hook after writing the entity`() {
        val settings = src("src/main/java/com/hermesvox/SettingsActivity.kt")
        val save = settings.substringAfter("val u = eurl.text.toString().trim()").substringBefore("refreshEntityVal()")
        val write = save.indexOf("prefs.edit().putString(\"url\", u)")
        val hook = save.indexOf("MainActivity.onEntitySaved()")
        assertTrue("the entity save must call the hook", hook > 0)
        assertTrue("the hook runs after the new values are stored", hook > write)
    }

    @Test
    fun `a Settings save with a live call is planned as end call, no re-arm`() {
        val plan = ConversationReset.plan(ConversationReset.Trigger.SETTINGS, callLive = true)
        assertTrue("the call ends", plan.endCall)
        assertTrue("it stays ended", !plan.rearm)
    }
}
