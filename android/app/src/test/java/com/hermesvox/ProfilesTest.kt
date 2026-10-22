package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #174: saved connections, one active, switched as a whole. */
class ProfilesTest {

    private val home = Profiles.Profile("home", "https://gw.example", "enc:aaa", agentName = "Hermes")
    private val work = Profiles.Profile("work", "https://gw.example/p/work", "enc:bbb", scope = "phone")

    @Test fun theGatewaysAnswersBecomeThreePreciseErrors() {
        assertEquals(Profiles.Check.OK, Profiles.check(200))
        assertEquals(Profiles.Check.NOT_SERVED, Profiles.check(404))
        assertEquals(Profiles.Check.KEY_REJECTED, Profiles.check(401))
        assertEquals(Profiles.Check.KEY_REJECTED, Profiles.check(403))
        assertEquals(Profiles.Check.UNREACHABLE, Profiles.check(-1))
        assertEquals(Profiles.Check.OTHER, Profiles.check(500))
        assertTrue(Profiles.Check.NOT_SERVED.message.startsWith("Not served"))
        assertTrue(Profiles.Check.KEY_REJECTED.message.startsWith("Key rejected"))
    }

    @Test fun aSwitchWritesTheWholeConnection() {
        val w = Profiles.toPrefs(work)
        assertEquals("https://gw.example/p/work", w[Profiles.K_URL])
        assertEquals("enc:bbb", w[Profiles.K_KEY])
        assertEquals("phone", w[Profiles.K_SCOPE])
        // An unset field is written as blank, so nothing of the previous profile survives.
        assertEquals("", w[Profiles.K_AGENT])
        assertEquals("", w[Profiles.K_PROVIDER])
        assertEquals("hermes-agent", w[Profiles.K_MODEL])
    }

    @Test fun theCurrentConnectionCanBeSavedAsAProfile() {
        val prefs = mapOf("url" to " https://gw.example ", "key" to "enc:aaa", "agent_name" to "Hermes")
        val p = Profiles.fromPrefs("home", { prefs[it].orEmpty() })
        assertEquals(home, p)
    }

    @Test fun roundTripKeepsEveryField() {
        val list = listOf(home, work.copy(provider = "openrouter", model = "m"))
        assertEquals(list, Profiles.decode(Profiles.encode(list)))
    }

    @Test fun anUnreadableListIsEmptyNotACrash() {
        assertEquals(emptyList<Profiles.Profile>(), Profiles.decode("not json"))
        assertEquals(emptyList<Profiles.Profile>(), Profiles.decode(""))
        assertEquals(listOf(home), Profiles.decode(Profiles.encode(listOf(home)).replace("]", ",{\"name\":\"\"}]")))
    }

    @Test fun namesAreUniqueIgnoringCase() {
        val list = listOf(home, work)
        assertEquals("A profile called \"Work\" already exists", Profiles.nameError("Work", list))
        assertNull(Profiles.nameError("work", list, editing = "work"))
        assertNull(Profiles.nameError("lab", list))
        assertEquals("Give the profile a name", Profiles.nameError("  ", list))
    }

    @Test fun upsertReplacesByNameAndRemoveDrops() {
        var list = Profiles.upsert(emptyList(), home)
        list = Profiles.upsert(list, work)
        list = Profiles.upsert(list, work.copy(url = "https://other/p/work"))
        assertEquals(2, list.size)
        assertEquals("https://other/p/work", Profiles.find(list, "WORK")!!.url)
        list = Profiles.upsert(list, work.copy(name = "office"), replacing = "work")
        assertEquals(listOf("home", "office"), list.map { it.name })
        assertEquals(listOf("home"), Profiles.remove(list, "Office").map { it.name })
    }

    @Test fun theHeaderNamesTheProfileOnlyWhenThereIsAChoice() {
        assertEquals("HERMES", Profiles.headerLabel("Hermes", "home", 1))
        assertEquals("HERMES · WORK", Profiles.headerLabel("Hermes", "work", 2))
    }

    @Test fun theKeyIsNeverDecodedOrRewritten() {
        // The registry stores what SecureStore produced; it does not touch it.
        val json = Profiles.encode(listOf(work))
        assertTrue(json.contains("\"key\":\"enc:bbb\""))
    }
}
