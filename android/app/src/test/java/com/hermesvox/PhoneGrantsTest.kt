package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #429 step 1: the capability registry, which is the single source for the schema, the Phone tools
 * screen and the audit.
 *
 * The properties that matter are the design's promises, asserted rather than described:
 *
 *  * **every group defaults to Off**, and Off advertises nothing;
 *  * **thirteen groups**, each with one tier, a one-line description and a unique id;
 *  * **Allow exists for Tier 0 only** — a stored Allow on Tier 1/2 reads back as Ask;
 *  * **no allow-all**; the one bulk shortcut touches Tier 0 alone.
 */
class PhoneGrantsTest {

    @Test fun every_group_starts_off() {
        assertEquals(PhoneGrants.State.OFF, PhoneGrants.DEFAULT)
        for (group in PhoneGrants.GROUPS) {
            assertEquals(
                "${group.id} must default to Off",
                PhoneGrants.State.OFF,
                PhoneGrants.stateOf(PhoneGrants.ALL_OFF, group),
            )
        }
        assertEquals(
            "a phone that was never asked offers only undo and status",
            listOf("undo", "status"),
            PhoneGrants.advertised(PhoneGrants.ALL_OFF),
        )
    }

    @Test fun the_thirteen_groups_are_defined_once_with_unique_ids() {
        val ids = PhoneGrants.GROUPS.map { it.id }
        assertEquals(PhoneGrants.GROUPS.size, ids.toSet().size)
        assertEquals(
            listOf(
                "timers-alarms", "dnd-volume", "notes", "media", "device-state", "clipboard",
                "files", "calendar", "notifications", "messages", "calls", "apps", "contacts",
            ),
            ids,
        )
        for (group in PhoneGrants.GROUPS) {
            assertTrue("${group.id} needs a label", group.label.isNotBlank())
            assertTrue("${group.id} needs a one-line description", group.lets.isNotBlank())
            assertTrue("${group.id} needs a tier", group.tier in 0..2)
        }
    }

    @Test fun the_two_read_groups_are_instant_and_offer_allow() {
        for ((id, actions) in listOf(
            "notifications" to listOf("notifications", "notification_read"),
            "contacts" to listOf("contact"),
        )) {
            val group = PhoneGrants.GROUPS.first { it.id == id }
            assertEquals("$id is instant", PhoneTiers.INSTANT, group.tier)
            assertEquals("$id is built", actions, group.actions)
            assertTrue(
                "$id offers Allow because it is instant",
                PhoneGrants.State.ALLOW in PhoneGrants.statesFor(group.tier),
            )
            val on = PhoneGrants.Source { g ->
                if (g.id == id) PhoneGrants.State.ALLOW else PhoneGrants.State.OFF
            }
            assertEquals(actions + PhoneGrants.ALWAYS, PhoneGrants.advertised(on))
        }
    }

    @Test fun the_warnings_land_where_the_consequence_is_not_obvious() {
        for (id in listOf("files", "notifications")) {
            val group = PhoneGrants.GROUPS.first { it.id == id }
            assertNotNull("$id must carry its consequence in plain words", group.warning)
            assertTrue("$id's warning must say 'your agent could'", group.warning!!.contains("Your agent could"))
        }
    }

    @Test fun allow_is_offered_for_tier_zero_only() {
        assertEquals(
            listOf(PhoneGrants.State.OFF, PhoneGrants.State.ASK, PhoneGrants.State.ALLOW),
            PhoneGrants.statesFor(PhoneTiers.INSTANT),
        )
        assertEquals(
            listOf(PhoneGrants.State.OFF, PhoneGrants.State.ASK),
            PhoneGrants.statesFor(PhoneTiers.REPORTED),
        )
        assertEquals(
            listOf(PhoneGrants.State.OFF, PhoneGrants.State.ASK),
            PhoneGrants.statesFor(PhoneTiers.APPROVAL),
        )
        assertTrue(PhoneGrants.permits(PhoneTiers.INSTANT, PhoneGrants.State.ALLOW))
        assertFalse(PhoneGrants.permits(PhoneTiers.REPORTED, PhoneGrants.State.ALLOW))
        assertFalse(PhoneGrants.permits(PhoneTiers.APPROVAL, PhoneGrants.State.ALLOW))
    }

    @Test fun a_stored_allow_on_a_tier_one_group_reads_back_as_ask() {
        val notes = PhoneGrants.GROUPS.first { it.id == "notes" }
        assertEquals(
            "a grant that cannot be given is not a grant",
            PhoneGrants.State.ASK,
            PhoneGrants.effective(notes, PhoneGrants.State.ALLOW),
        )
        val timers = PhoneGrants.GROUPS.first { it.id == "timers-alarms" }
        assertEquals(PhoneGrants.State.ALLOW, PhoneGrants.effective(timers, PhoneGrants.State.ALLOW))
    }

    @Test fun setting_one_group_on_adds_exactly_its_actions() {
        val notes = PhoneGrants.Source { g ->
            if (g.id == "notes") PhoneGrants.State.ASK else PhoneGrants.State.OFF
        }
        assertEquals(listOf("note", "undo", "status"), PhoneGrants.advertised(notes))

        val both = PhoneGrants.Source { g ->
            when (g.id) {
                "notes" -> PhoneGrants.State.ASK
                "timers-alarms" -> PhoneGrants.State.ALLOW
                else -> PhoneGrants.State.OFF
            }
        }
        assertEquals(listOf("timer", "alarm", "note", "undo", "status"), PhoneGrants.advertised(both))
    }

    @Test fun the_one_bulk_shortcut_touches_tier_zero_only() {
        val before = PhoneGrants.GROUPS.associate { it.id to PhoneGrants.State.OFF }
        val after = PhoneGrants.instantAll(before)
        for (group in PhoneGrants.GROUPS) {
            if (group.tier == PhoneTiers.INSTANT) {
                assertEquals("${group.id} is instant", PhoneGrants.State.ALLOW, after[group.id])
            } else {
                assertEquals("${group.id} must stay Off", PhoneGrants.State.OFF, after[group.id])
            }
        }
    }

    @Test fun planned_actions_resolve_to_their_group_but_are_never_advertised() {
        val apps = PhoneGrants.groupOf("uninstall")
        assertEquals("apps", apps?.id)
        assertEquals(PhoneTiers.APPROVAL, PhoneGrants.tierOf("uninstall"))
        assertFalse("a planned name is never in a group's offerable set", "uninstall" in apps!!.actions)
        for (g in PhoneGrants.GROUPS) {
            for (planned in g.planned) {
                assertFalse("$planned is planned, not built", planned in g.actions)
                assertFalse(
                    "$planned must not be advertised even when its group is allowed",
                    planned in PhoneGrants.advertised(PhoneGrants.Source { PhoneGrants.State.ALLOW }),
                )
            }
        }
        assertNull(PhoneGrants.groupOf("fly"))
        assertNull(PhoneGrants.tierOf("fly"))
    }

    @Test fun the_summary_names_every_group_and_its_state() {
        val onlyTimers = PhoneGrants.Source { g ->
            if (g.id == "timers-alarms") PhoneGrants.State.ALLOW else PhoneGrants.State.OFF
        }
        val summary = PhoneGrants.summary(onlyTimers)
        for (group in PhoneGrants.GROUPS) {
            assertTrue("$summary must name ${group.id}", summary.contains("${group.id}="))
        }
        assertTrue(summary.contains("timers-alarms=allow"))
        assertFalse(summary.contains("notes=allow"))
    }
}
