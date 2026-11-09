package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #174: saved connections, one active, switched as a whole. */
class ProfilesTest {

    private val home = Profiles.Profile("home", "https://gw.example", "enc:aaa", agentName = "Hermes")
    private val work = Profiles.Profile("work", "https://gw.example/p/work", "enc:bbb", scope = "phone")

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

    @Test fun upsertReplacesByName() {
        var list = Profiles.upsert(emptyList(), home)
        list = Profiles.upsert(list, work)
        list = Profiles.upsert(list, work.copy(url = "https://other/p/work"))
        assertEquals(2, list.size)
        assertEquals("https://other/p/work", Profiles.find(list, "WORK")!!.url)
        list = Profiles.upsert(list, work.copy(name = "office"), replacing = "work")
        assertEquals(listOf("home", "office"), list.map { it.name })
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

    @Test fun aRenameChangesOnlyTheLabel() {
        val list = listOf(home, work)
        val r = Profiles.rename(list, "work", "Office")
        assertTrue(r.changed)
        assertNull(r.error)
        assertEquals(listOf("home", "Office"), r.list.map { it.name })
        // Every connection field is byte-identical; only the label moved.
        assertEquals(work.copy(name = "Office"), Profiles.find(r.list, "office"))
    }

    @Test fun renamingTheActiveProfileMovesTheActiveName() {
        val r = Profiles.rename(listOf(home, work), "work", "Office", active = "WORK")
        assertTrue(r.changed)
        assertEquals("Office", r.active)
        // Renaming a different profile leaves the active name alone.
        val other = Profiles.rename(listOf(home, work), "home", "House", active = "work")
        assertTrue(other.changed)
        assertEquals("work", other.active)
    }

    @Test fun aRenameRejectsADuplicateCaseInsensitivelyAndSaysSo() {
        val r = Profiles.rename(listOf(home, work), "work", "HOME")
        assertFalse(r.changed)
        assertEquals(Profiles.RenameError.DUPLICATE, r.error)
        assertEquals(listOf(home, work), r.list)
    }

    @Test fun aRenameRejectsABlankOrInvalidLabelAndSaysSo() {
        assertEquals(Profiles.RenameError.BLANK,
            Profiles.rename(listOf(home), "home", "   ").error)
        assertEquals(Profiles.RenameError.BAD_CHARS,
            Profiles.rename(listOf(home), "home", "work/2").error)
        assertEquals(Profiles.RenameError.BAD_CHARS,
            Profiles.rename(listOf(home), "home", "!!").error)
        assertEquals(Profiles.RenameError.TOO_LONG,
            Profiles.rename(listOf(home), "home", "a".repeat(Profiles.LABEL_MAX + 1)).error)
    }

    @Test fun anUnknownOrNoOpRenameChangesNothing() {
        val list = listOf(home, work)
        val unknown = Profiles.rename(list, "ghost", "Office")
        assertFalse(unknown.changed)
        assertEquals(Profiles.RenameError.UNKNOWN, unknown.error)
        assertEquals(list, unknown.list)

        val noop = Profiles.rename(list, "home", "home")
        assertFalse(noop.changed)
        assertEquals(Profiles.RenameError.UNCHANGED, noop.error)
        assertEquals(list, noop.list)
    }

    @Test fun anAddressSuggestsItsProfileSegmentOrHostLabel() {
        assertEquals("work", Profiles.suggestName("https://gw.example/p/work"))
        assertEquals("gw", Profiles.suggestName("https://gw.example"))
        assertEquals("hermes", Profiles.suggestName("https://hermes.example.com:8642/v1/models"))
        assertEquals("profile", Profiles.suggestName(""))
    }
}
