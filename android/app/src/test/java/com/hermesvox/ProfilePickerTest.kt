package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #174/#436: the profile switcher is a **picker, not an editor**. Profiles are configured on
 * the host and Vox picks among them, so there is no add and no edit — and a host that served
 * none says so plainly instead of offering to create one.
 *
 * The readers are Android-bound, so the sheet and the dialogs are pinned at source level, the
 * same way [AlphaGateTest] pins the rest of the wiring.
 */
class ProfilePickerTest {

    private val src = File("src/main/java/com/hermesvox")
    private val sheet = File(src, "ProfileSheet.kt").readText()
    private val dialogs = File(src, "ProfileDialogs.kt").readText()

    @Test fun the_picker_offers_no_add_and_no_edit() {
        for (word in listOf("Add a profile", "Edit a profile", "Enter an address manually",
                "addProfile", "pickToEdit", "showEditor", "onAdd", "onEdit", "Delete")) {
            assertFalse("$word in the sheet", sheet.contains(word))
            assertFalse("$word in the dialogs", dialogs.contains(word))
        }
        // Nothing in the picker writes a profile: the registry only follows the host's answer.
        assertFalse("the sheet never writes", sheet.contains("Profiles.upsert"))
        assertFalse("the picker never writes", dialogs.contains("Profiles.upsert"))
        assertFalse("no profile is created from a typed address here",
            dialogs.contains("SecureStore.encrypt"))
    }

    /** The one row grammar is unchanged: title, one line, an IN USE chip, and a tap that switches. */
    @Test fun the_picker_is_the_list_the_one_in_use_and_the_switch() {
        assertTrue(sheet.contains("chip = if (r.active) \"in use\" else null"))
        assertTrue(sheet.contains("onSwitch(p)"))
        assertTrue("the switch is the tap", sheet.contains("private val onSwitch: (Profiles.Profile) -> Unit"))
        // The list arrives from the host and can be asked again, in place.
        assertTrue(sheet.contains("\"Refresh from host\""))
        assertTrue(dialogs.contains("onRefresh = { refreshFromHost(ProfilesAsk.AskKind.REFRESH) }"))
    }

    /** A host with no profiles says so — and the line names where profiles are made. */
    @Test fun a_host_with_no_profiles_says_so() {
        assertEquals(
            "No profiles yet. Profiles are set up on the host, not on this phone.",
            ProfileSheet.NO_PROFILES,
        )
        assertTrue("the empty state is what an empty list draws",
            sheet.contains("if (shown.isEmpty()) body.addView(empty())"))
        assertTrue(sheet.contains("text = NO_PROFILES"))
        // Nothing in the empty state offers to create one.
        val empty = sheet.substringAfter("private fun empty(): View =").substringBefore("private fun actionRow")
        assertFalse("no add affordance", empty.contains("Add"))
        assertFalse("no detour to an editor", empty.contains("Editor"))
    }

    /**
     * The connection form shares the entity dialog's layout with the picker's old editor. The
     * form stays exactly as it was — first run and Settings → Entity still need it — and the
     * editor went; this pins which of the two still inflates it.
     */
    @Test fun the_connection_form_keeps_the_shared_layout_and_the_editor_is_gone() {
        val settings = File(src, "SettingsActivity.kt").readText()
        assertTrue("the connection form is untouched",
            settings.contains("val view = layoutInflater.inflate(R.layout.dialog_entity, null)"))
        assertTrue(settings.contains("val reason = SessionScope.validationError(escope.text.toString())"))
        assertFalse("the profile editor no longer inflates it",
            dialogs.contains("R.layout.dialog_entity"))
    }
}
