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
 *  * **Off, Ask and Allow are offered for every tier** — one meaning each, honoured as stored;
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
            "notifications" to listOf("notifications", "notification_read", "notification_dismiss"),
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

    /**
     * The two read groups put other people's words into the agent's conversation, so the row has
     * to say the content goes to the agent rather than implying it stays on the phone.
     */
    @Test fun the_two_reads_say_the_content_reaches_the_agent() {
        val clipboard = PhoneGrants.GROUPS.first { it.id == "clipboard" }
        assertNotNull("clipboard must say what a read costs", clipboard.warning)
        for (word in listOf("password", "code", "your agent", "Vox is in front")) {
            assertTrue(
                "the clipboard warning must name '$word': ${clipboard.warning}",
                clipboard.warning!!.contains(word),
            )
        }

        val notifications = PhoneGrants.GROUPS.first { it.id == "notifications" }
        assertNotNull("notifications must say where the content goes", notifications.warning)
        for (word in listOf("lock screen", "go to your agent", "Codes, messages")) {
            assertTrue(
                "the notifications warning must name '$word': ${notifications.warning}",
                notifications.warning!!.contains(word),
            )
        }
    }

    /**
     * The defect this replaced: Allow existed for Tier 0 only, and a stored Allow on a Tier 1 or
     * Tier 2 group was quietly read back as Ask. Every tier now offers the same three words.
     */
    @Test fun every_tier_offers_the_same_three_states() {
        for (tier in listOf(PhoneTiers.INSTANT, PhoneTiers.REPORTED, PhoneTiers.APPROVAL)) {
            assertEquals(
                "tier $tier must offer Off, Ask and Allow",
                listOf(PhoneGrants.State.OFF, PhoneGrants.State.ASK, PhoneGrants.State.ALLOW),
                PhoneGrants.statesFor(tier),
            )
        }
    }

    /**
     * A stored value is the policy: no tier normalises Allow to Ask, and no group reads back a
     * state other than the one written. The row and the engine therefore cannot disagree.
     */
    @Test fun a_stored_state_is_honoured_as_stored_for_every_group() {
        for (group in PhoneGrants.GROUPS) {
            for (wanted in PhoneGrants.statesFor(group.tier)) {
                val source = PhoneGrants.Source { g ->
                    if (g.id == group.id) wanted else PhoneGrants.State.OFF
                }
                assertEquals(
                    "${group.id} must read back ${wanted.id}",
                    wanted,
                    PhoneGrants.stateOf(source, group),
                )
            }
        }
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

    /**
     * The M8 rule survives in its corrected form: an entry in the enum is a capability the phone
     * must be able to keep, and now every action at Ask has a card to keep it. So Ask advertises
     * the group's whole action list — nothing is withheld for want of an approval path.
     */
    @Test fun ask_advertises_every_action_because_every_action_can_raise_a_card() {
        for (group in PhoneGrants.GROUPS) {
            if (group.actions.isEmpty()) continue
            val on = PhoneGrants.Source { g ->
                if (g.id == group.id) PhoneGrants.State.ASK else PhoneGrants.State.OFF
            }
            assertEquals(
                "${group.id} at Ask must offer its actions behind a card",
                group.actions + PhoneGrants.ALWAYS,
                PhoneGrants.advertised(on),
            )
        }
    }

    /**
     * The row has to say what Allow means where that is not obvious. A Messages row set to Allow
     * is telling the owner that Vox may compose a text and open the messaging app without asking.
     */
    @Test fun a_consequential_group_says_what_allow_means_on_its_row() {
        for (id in listOf("messages", "calls", "contacts", "notifications", "clipboard", "files", "apps")) {
            val group = PhoneGrants.GROUPS.first { it.id == id }
            val warning = group.allowWarning
            assertNotNull("$id must say what Allow means", warning)
            assertTrue("$id's Allow warning must say 'without asking': $warning", warning!!.contains("without asking"))
        }
        val messages = PhoneGrants.GROUPS.first { it.id == "messages" }
        assertTrue(
            "the Messages warning must name the text: ${messages.allowWarning}",
            messages.allowWarning!!.contains("text"),
        )
        val apps = PhoneGrants.GROUPS.first { it.id == "apps" }
        assertTrue(
            "the Apps warning must name the removal: ${apps.allowWarning}",
            apps.allowWarning!!.contains("remove an app"),
        )
    }

    /**
     * L7 of the 0.9.1 review: the one bulk shortcut must not grant the privacy-read groups. A tap
     * that turned Notifications, Contacts and Apps on at once would hand over other people's words
     * and the uninstall request path without the owner deciding to.
     */
    @Test fun the_one_bulk_shortcut_touches_tier_zero_but_not_the_private_groups() {
        assertEquals(
            "the exempt set is exactly the groups a bulk tap must not decide",
            setOf("notifications", "contacts", "apps"),
            PhoneGrants.BULK_EXEMPT,
        )
        for (id in PhoneGrants.BULK_EXEMPT) {
            val group = PhoneGrants.GROUPS.first { it.id == id }
            assertEquals("$id is Tier 0, so the exemption has to be explicit", PhoneTiers.INSTANT, group.tier)
        }

        val before = PhoneGrants.GROUPS.associate { it.id to PhoneGrants.State.OFF }
        val after = PhoneGrants.instantAll(before)
        for (group in PhoneGrants.GROUPS) {
            when {
                group.id in PhoneGrants.BULK_EXEMPT -> assertEquals(
                    "${group.id} stays a decision of its own",
                    PhoneGrants.State.OFF,
                    after[group.id],
                )
                group.tier == PhoneTiers.INSTANT ->
                    assertEquals("${group.id} is instant", PhoneGrants.State.ALLOW, after[group.id])
                else ->
                    assertEquals("${group.id} must stay Off", PhoneGrants.State.OFF, after[group.id])
            }
        }
    }

    @Test fun uninstall_resolves_to_apps_and_install_is_no_longer_an_action() {
        val apps = PhoneGrants.groupOf("uninstall")
        assertEquals("apps", apps?.id)
        assertEquals(PhoneTiers.APPROVAL, PhoneGrants.tierOf("uninstall"))
        assertTrue("uninstall is built, and its card owns it", "uninstall" in apps!!.actions)
        assertTrue(
            "the card's action is offered when its group is on",
            "uninstall" in PhoneGrants.advertised(PhoneGrants.Source { PhoneGrants.State.ALLOW }),
        )
        // `install` left the surface with the apps-and-dismiss lane: no group, no tier, no promise.
        assertNull("install belongs to no group", PhoneGrants.groupOf("install"))
        assertNull("install is not a phone action", PhoneGrants.tierOf("install"))
        assertFalse(
            "install must not be advertised even when every group is allowed",
            "install" in PhoneGrants.advertised(PhoneGrants.Source { PhoneGrants.State.ALLOW }),
        )
        assertNull(PhoneGrants.groupOf("fly"))
        assertNull(PhoneGrants.tierOf("fly"))
    }

    @Test fun a_planned_action_is_declared_by_its_group_and_never_advertised() {
        // `planned` is not dead: the files group declares the one capability it will hold but does
        // not implement, and `PhoneTool.parse` reads it to refuse the call with that group's name.
        val files = PhoneGrants.GROUPS.first { it.id == "files" }
        assertEquals(listOf("delete"), files.planned)
        assertTrue("a planned name is not a built action", "delete" !in files.actions)
        assertEquals("delete belongs to files", "files", PhoneGrants.groupOf("delete")?.id)
        assertEquals("and keeps its consequential tier", PhoneTiers.APPROVAL, PhoneGrants.tierOf("delete"))

        val all = PhoneGrants.Source { PhoneGrants.State.ALLOW }
        for (group in PhoneGrants.GROUPS) {
            for (planned in group.planned) {
                assertTrue(
                    "$planned is planned for ${group.id}, so it is never advertised",
                    planned !in PhoneGrants.advertised(all),
                )
            }
        }
        for (gone in listOf("purchase", "pay", "transfer")) {
            assertNull("$gone is no longer a declared capability", PhoneGrants.groupOf(gone))
            assertNull("$gone has no tier", PhoneGrants.tierOf(gone))
        }
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
