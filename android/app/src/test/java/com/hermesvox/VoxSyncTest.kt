package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The three owner rules, off-device:
 *
 *  a. Presence off → no fetch, no write, no read for the workflow, no reason to report.
 *  b. The first call on a profile with no mirror gets one through the resync path, and
 *     only a validated document is written; a failed ask leaves Presence quiet with a
 *     reason.
 *  c. Two profiles are two documents, and the file the soul reads follows the active one
 *     (VoxMirror.read(root, profile) — covered by VoxMirrorTest).
 */
class VoxSyncTest {

    private fun root(): File = Files.createTempDirectory("voxsync").toFile()

    /** A document the validator accepts, built from the shipped skeleton. */
    private fun doc(name: String = "Cinder"): String = VoxSoul.SKELETON
        .replace("<your name>", name)
        .replace("<2-3 sentences, who you are with me>", "I am the voice of the agent.")
        .replace("<tone, diction, catchphrases>", "Warm, dry, brief.")
        .replace("<how you address me, what we are>", "I call you by your name.")
        .replace("<your kind of joke>", "Dry asides.")
        .replace("<what you're proud of>", "The work.")

    /** A counting stand-in for the entity. */
    private class Ask(var reply: String?) {
        val notes = ArrayList<String?>()
        val calls: Int get() = notes.size
        fun ask(note: String?): String? { notes += note; return reply }
    }

    // ---- (a) the gate ----

    @Test fun presenceOffIsNotQuietModeItIsNoWorkflow() {
        assertEquals(VoxSync.Need.OFF, VoxSync.plan(false, doc()))
        assertEquals(VoxSync.Need.OFF, VoxSync.plan(false, null))
        assertEquals(VoxSync.Need.READY, VoxSync.plan(true, doc()))
        assertEquals(VoxSync.Need.FETCH, VoxSync.plan(true, null))
        assertEquals(VoxSync.Need.FETCH, VoxSync.plan(true, "not a document"))
    }

    @Test fun withPresenceOffNothingIsAskedReadOrWritten() {
        val r = root()
        val entity = Ask(doc())
        val a = VoxSync.obtain(r, "home", presenceOn = false) { entity.ask(it) }

        assertEquals(VoxSync.Need.OFF, a.need)
        assertFalse(a.ready)
        assertNull("nothing to report about a mirror with Presence off", a.reason)
        assertEquals("no request may leave the phone", 0, entity.calls)
        assertNull(VoxMirror.read(r, "home"))
        assertFalse(File(r, "vox").exists())
    }

    @Test fun withPresenceOffAnExplicitResyncStillDoesNothing() {
        val r = root()
        val entity = Ask(doc())
        val a = VoxSync.refetch(r, "home", presenceOn = false, ask = { entity.ask(it) })
        assertEquals(VoxSync.Need.OFF, a.need)
        assertEquals(0, entity.calls)
        assertNull(VoxMirror.read(r, "home"))
    }

    // ---- (b) the first call per profile ----

    @Test fun theFirstCallOnAProfileAsksOnceAndMirrorsTheDocument() {
        val r = root()
        val entity = Ask(doc("Cinder"))
        val a = VoxSync.obtain(r, "home", presenceOn = true) { entity.ask(it) }

        assertTrue(a.ready)
        assertEquals(VoxSync.Need.FETCH, a.need)
        assertEquals(1, entity.calls)
        assertNull("a first ask carries no corrective note", entity.notes.single())
        val mirrored = VoxMirror.read(r, "home")
        assertEquals("the validator's normalized document is what is mirrored", doc(), mirrored)
        assertTrue(mirrored!!.startsWith("# Contract"))
        assertTrue(File(r, "vox/home/VOX.md").isFile)

        // The next call on the same profile does not ask again.
        val again = VoxSync.obtain(r, "home", presenceOn = true) { entity.ask(it) }
        assertEquals(VoxSync.Need.READY, again.need)
        assertTrue(again.ready)
        assertEquals(1, entity.calls)
    }

    @Test fun aSecondProfileIsAskedForItsOwnDocument() {
        val r = root()
        val entity = Ask(doc("Cinder"))
        assertTrue(VoxSync.obtain(r, "home", presenceOn = true) { entity.ask(it) }.ready)

        entity.reply = doc("Wren")
        assertTrue(VoxSync.obtain(r, "work", presenceOn = true) { entity.ask(it) }.ready)

        assertEquals(2, entity.calls)
        assertTrue(VoxMirror.read(r, "home")!!.contains("Name: Cinder"))
        assertTrue(VoxMirror.read(r, "work")!!.contains("Name: Wren"))
        assertTrue(File(r, "vox/home/VOX.md").isFile)
        assertTrue(File(r, "vox/work/VOX.md").isFile)
    }

    @Test fun anInvalidReplyIsNeverWritten() {
        val r = root()
        val drifted = doc().replace(VoxSoul.CONTRACT_LINES[2], "3. Anything goes.")
        val entity = Ask(drifted)
        val a = VoxSync.obtain(r, "home", presenceOn = true) { entity.ask(it) }

        assertFalse(a.ready)
        assertTrue(a.outcome is VoxSoul.Outcome.Rejected)
        assertTrue("the reason names the drifted rule", a.reason!!.contains("Contract rule 3"))
        assertNull(VoxMirror.read(r, "home"))
    }

    @Test fun aProseReplyLeavesTheProfileWithoutAMirror() {
        val r = root()
        val entity = Ask("Sure — I wrote my VOX.md and it describes my voice.")
        val a = VoxSync.obtain(r, "home", presenceOn = true) { entity.ask(it) }
        assertFalse(a.ready)
        assertTrue(a.reason!!.startsWith("the reply has no VOX.md in it"))
        assertNull(VoxMirror.read(r, "home"))
    }

    @Test fun aStaleInvalidMirrorIsReplacedByAFreshDocument() {
        val r = root()
        // The user has an old mirror the current validator refuses (a Memory section).
        val stale = doc() + "\nMemory: something the voice must not carry"
        assertTrue(VoxMirror.write(r, "home", stale))
        assertEquals(VoxSync.Need.FETCH, VoxSync.plan(true, VoxMirror.read(r, "home")))

        val entity = Ask(doc())
        assertTrue(VoxSync.obtain(r, "home", presenceOn = true) { entity.ask(it) }.ready)
        assertFalse(VoxMirror.read(r, "home")!!.contains("Memory:"))
    }

    @Test fun anUnwritableMirrorIsNotASilentSuccess() {
        val r = root()
        // The profile's directory path is occupied by a file, so nothing can be written.
        val blocker = File(File(r, "vox"), VoxMirror.dirName("home"))
        blocker.parentFile!!.mkdirs()
        blocker.writeText("not a directory")
        val a = VoxSync.obtain(r, "home", presenceOn = true) { doc() }
        assertFalse(a.ready)
        assertTrue(a.reason!!.contains("could not be written"))
    }

    // ---- migration before the first ask ----

    @Test fun anUpgradeKeepsTheMirrorItAlreadyHad() {
        val r = root()
        VoxMirror.legacyFile(r).parentFile!!.mkdirs()
        VoxMirror.legacyFile(r).writeText(doc("Cinder"))
        val entity = Ask(doc("SOMEONE ELSE"))

        val a = VoxSync.obtain(r, "home", presenceOn = true) { entity.ask(it) }

        assertEquals(VoxSync.Need.READY, a.need)
        assertTrue(a.ready)
        assertEquals("the working mirror must survive the upgrade", 0, entity.calls)
        assertTrue(VoxMirror.read(r, "home")!!.contains("Name: Cinder"))
        assertFalse(VoxMirror.legacyFile(r).exists())
    }

    // ---- the Settings button (explicit resync) ----

    @Test fun anExplicitResyncAlwaysAsksEvenWhenAMirrorExists() {
        val r = root()
        val entity = Ask(doc("Cinder"))
        assertTrue(VoxSync.obtain(r, "home", presenceOn = true) { entity.ask(it) }.ready)

        entity.reply = doc("Cinder, revised")
        val a = VoxSync.refetch(r, "home", presenceOn = true, ask = { entity.ask(it) })
        assertTrue(a.ready)
        assertEquals(2, entity.calls)
        assertTrue(VoxMirror.read(r, "home")!!.contains("Cinder, revised"))
    }

    @Test fun aRetryNoteRidesTheSameAsk() {
        val r = root()
        val entity = Ask(doc())
        VoxSync.refetch(r, "home", presenceOn = true, ask = { entity.ask(it) }, retryNote = "not a usable VOX.md")
        assertTrue(entity.notes.single()!!.startsWith("not a usable VOX.md"))
    }

    @Test fun theSyncLineNamesTheProfileAndTheVerdict() {
        val r = root()
        val lines = ArrayList<String>()
        VoxSync.obtain(r, "home", presenceOn = true, log = { lines += it }) { doc() }
        assertEquals(1, lines.size)
        assertTrue(lines.single().startsWith("event=vox-resync profile=home doc="))
        assertTrue(lines.single().contains("result=ok"))

        // A refusal is logged with its reason, and the profile it was for.
        val other = ArrayList<String>()
        VoxSync.obtain(root(), "work", presenceOn = true, log = { other += it }) { "no document here" }
        assertTrue(other.single().contains("profile=work"))
        assertTrue(other.single().contains("result=invalid:"))
    }
}
