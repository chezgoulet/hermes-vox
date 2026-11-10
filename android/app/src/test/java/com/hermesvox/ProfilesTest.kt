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

    @Test fun theHeaderNamesTheConnectionOnce() {
        // A named profile is its own label — the agent's name is not prefixed to it.
        assertEquals("HOME", Profiles.headerLabel("Hermes", "home"))
        // The built-in connection is the agent, and the word "default" is never shown.
        assertEquals("HERMES", Profiles.headerLabel("Hermes", "default"))
        assertEquals("HERMES", Profiles.headerLabel("Hermes", ""))
    }

    @Test fun aSavedProfileCanBeForgottenAndTheActiveOneCannot() {
        // Forgotten: the row goes, the rest of the registry is untouched.
        val r = Profiles.remove(listOf(home, work), "work", active = "home")
        assertTrue(r.removed)
        assertEquals(listOf("home"), r.list.map { it.name })

        // The connection in use is refused — a live session would point at an address the
        // app no longer has. The sheet says "switch first".
        val refused = Profiles.remove(listOf(home, work), "home", active = "home")
        assertFalse(refused.removed)
        assertEquals(Profiles.RemoveError.ACTIVE, refused.error)
        assertEquals(2, refused.list.size)

        // A name that was never saved is not an error state to act on, only to report.
        assertEquals(Profiles.RemoveError.UNKNOWN,
            Profiles.remove(listOf(home, work), "elsewhere", active = "home").error)

        // Names compare as they do everywhere else in the registry.
        assertEquals(listOf("home"), Profiles.remove(listOf(home, work), "WORK", active = "home").list.map { it.name })
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

    @Test fun aRenameIsOfferedOnlyWhenTheLabelDoesNotMatchTheAddress() {
        assertEquals("work", Profiles.suggestedLabel("https://gw.example/p/work", "Office"))
        assertEquals("gw", Profiles.suggestedLabel("https://gw.example", "Vox Test"))
        assertNull(Profiles.suggestedLabel("https://gw.example/p/work", "Work"))
        assertNull(Profiles.suggestedLabel("https://gw.example/p/work", " work "))
    }

    /** The label's promise is ASCII: a homoglyph label must not be accepted. */
    @Test fun aRenameRejectsANonAsciiLabel() {
        assertEquals(Profiles.RenameError.BAD_CHARS,
            Profiles.rename(listOf(home), "home", "\u03a9mega").error)
        assertEquals(Profiles.RenameError.BAD_CHARS,
            Profiles.rename(listOf(home), "home", "caf\u00e9").error)
    }

    /** After an address change the entry carries the live connection, not its old one. */
    @Test fun anAdoptedConnectionCarriesTheNewAddress() {
        val renamed = Profiles.rename(listOf(home, work), "work", "Office").list
        val live = work.copy(
            name = "Office",
            url = "https://new.example/p/office",
            keyEnc = "enc:new",
            own = mapOf("tts_voice" to "bright"),
        )
        val out = Profiles.adoptConnection(renamed, "Office", live)
        assertEquals(2, out.size)                      // replaced, never appended
        val got = Profiles.find(out, "office")!!
        assertEquals("https://new.example/p/office", got.url)
        assertEquals("enc:new", got.keyEnc)
        assertEquals("bright", got.own["tts_voice"])
    }

    @Test fun anAddressSuggestsItsProfileSegmentOrHostLabel() {
        assertEquals("work", Profiles.suggestName("https://gw.example/p/work"))
        assertEquals("gw", Profiles.suggestName("https://gw.example"))
        assertEquals("hermes", Profiles.suggestName("https://hermes.example.com:8642/v1/models"))
        assertEquals("profile", Profiles.suggestName(""))
    }
}
