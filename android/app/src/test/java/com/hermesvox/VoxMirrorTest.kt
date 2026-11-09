package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The per-profile mirror: `filesDir/vox/<profile>/VOX.md`.
 *
 * Each test runs over a real temporary directory, so the encoding, the write and the
 * one-way migration are proven as they will run on the phone — no Context required.
 */
class VoxMirrorTest {

    private fun root(): File = Files.createTempDirectory("voxmirror").toFile()

    private val cinder = "# Contract\n(whatever)\n\n# Soul\nName: Cinder\n"

    @Test fun eachProfileOwnsItsOwnDocument() {
        val r = root()
        assertTrue(VoxMirror.write(r, "home", cinder))
        assertTrue(VoxMirror.write(r, "work", cinder.replace("# Soul\nName: Cinder", "# Soul\nName: Wren")))

        assertEquals(cinder, VoxMirror.read(r, "home"))
        assertEquals("Wren", VoxMirror.read(r, "work")!!.lines().first { it.startsWith("Name:") }.substringAfter(": ").trim())
        // Two profiles, two files — and neither is the pre-profile path.
        assertTrue(File(r, "vox/home/VOX.md").isFile)
        assertTrue(File(r, "vox/work/VOX.md").isFile)
        assertFalse(VoxMirror.legacyFile(r).exists())
    }

    @Test fun anInstallWithNoProfileUsesItsOwnBucket() {
        val r = root()
        assertEquals(VoxMirror.UNNAMED, VoxMirror.dirName(""))
        assertEquals(VoxMirror.UNNAMED, VoxMirror.dirName("   "))
        assertTrue(VoxMirror.write(r, "", cinder))
        assertEquals(cinder, VoxMirror.read(r, ""))
        assertTrue(File(r, "vox/@/VOX.md").isFile)
    }

    @Test fun aNameCannotEscapeTheVoxDirectory() {
        val r = root()
        for (name in listOf("../escape", "..", ".", "a/b", "a\\b", "/abs", "Name With Spaces", "nul\u0000byte")) {
            val dir = VoxMirror.dirName(name)
            assertFalse("$name -> $dir", dir.contains('/'))
            assertFalse("$name -> $dir", dir.contains('\\'))
            assertFalse("$name -> $dir", dir.contains('\u0000'))
            assertFalse("$name -> $dir", dir.startsWith("."))
            val f = VoxMirror.file(r, name)
            assertEquals(r.canonicalFile, f.canonicalFile.parentFile.parentFile.parentFile)
        }
    }

    @Test fun twoDifferentNamesNeverShareAFile() {
        val names = listOf("home", "work", "Home", "HOME", "a b", "a%20b", "a/b", "a%2fb", "..", "@",
            "%", "a-b", "a_b", "★", "Cinder's phone", "a".repeat(80), "a".repeat(81))
        val dirs = names.map { VoxMirror.dirName(it) }
        assertEquals("the encoding must be injective: $dirs", names.size, dirs.toSet().size)
        // "a%20b" must not collide with the encoding of "a b", and a literal % is escaped.
        assertFalse(VoxMirror.dirName("%").startsWith("%40"))
        assertTrue(VoxMirror.dirName("%").startsWith("%25"))
    }

    @Test fun aLongNameStaysInsideOneFilesystemComponent() {
        val dir = VoxMirror.dirName("élan ".repeat(40))
        assertTrue("encoded component is ${dir.length} chars", dir.length <= 33)
        assertTrue(dir.startsWith("%"))       // the hash shape a passthrough name cannot take
        assertEquals(dir, VoxMirror.dirName("élan ".repeat(40)))   // deterministic
    }

    @Test fun theLegacyMirrorMigratesOnce() {
        val r = root()
        VoxMirror.legacyFile(r).parentFile!!.mkdirs()
        VoxMirror.legacyFile(r).writeText(cinder)

        assertEquals(VoxMirror.Migration.COPIED, VoxMirror.migrate(r, "home"))
        assertEquals(cinder, VoxMirror.read(r, "home"))
        assertFalse("the legacy file is removed only after the copy", VoxMirror.legacyFile(r).exists())
        // Idempotent: a second run has nothing to do and writes nothing.
        assertEquals(VoxMirror.Migration.NONE, VoxMirror.migrate(r, "home"))
        assertEquals(cinder, VoxMirror.read(r, "home"))
    }

    @Test fun migrationNeverOverwritesTheProfileItFinds() {
        val r = root()
        VoxMirror.legacyFile(r).parentFile!!.mkdirs()
        VoxMirror.legacyFile(r).writeText(cinder)
        assertTrue(VoxMirror.write(r, "home", "newer home document"))

        assertEquals(VoxMirror.Migration.ALREADY, VoxMirror.migrate(r, "home"))
        assertEquals("newer home document", VoxMirror.read(r, "home"))
        assertEquals("nothing is deleted when nothing was copied", cinder, VoxMirror.legacyFile(r).readText())
    }

    @Test fun aFailedMigrationKeepsTheOriginal() {
        val r = root()
        // Occupy the profile's directory path with a FILE, so the copy cannot land.
        val blocker = File(File(r, "vox"), VoxMirror.dirName("home"))
        blocker.parentFile!!.mkdirs()
        blocker.writeText("not a directory")
        VoxMirror.legacyFile(r).writeText(cinder)

        assertEquals(VoxMirror.Migration.NONE, VoxMirror.migrate(r, "home"))
        assertTrue("the user's mirror survives a failed copy", VoxMirror.legacyFile(r).isFile)
        assertEquals(cinder, VoxMirror.legacyFile(r).readText())
        assertNull(VoxMirror.read(r, "home"))
    }

    @Test fun anUnreadableOrBlankMirrorReadsAsAbsent() {
        val r = root()
        assertNull(VoxMirror.read(r, "home"))
        VoxMirror.file(r, "home").parentFile!!.mkdirs()
        VoxMirror.file(r, "home").writeText("   ")
        assertNull(VoxMirror.read(r, "home"))
    }

    @Test fun renamingASlotMovesTheDocumentIntact() {
        val r = root()
        assertTrue(VoxMirror.write(r, "home", cinder))
        assertEquals(VoxMirror.RenameSlot.MOVED, VoxMirror.renameSlot(r, "home", "House"))
        assertEquals(cinder, VoxMirror.read(r, "House"))
        assertNull(VoxMirror.read(r, "home"))
    }

    @Test fun renamingASlotWithNoSourceTouchesNothing() {
        val r = root()
        assertEquals(VoxMirror.RenameSlot.NO_SOURCE, VoxMirror.renameSlot(r, "home", "House"))
        assertFalse(VoxMirror.file(r, "House").exists())
    }

    @Test fun renamingASlotOntoAnExistingDocumentLeavesBoth() {
        val r = root()
        assertTrue(VoxMirror.write(r, "home", cinder))
        assertTrue(VoxMirror.write(r, "House", "the destination document"))
        assertEquals(VoxMirror.RenameSlot.DESTINATION_EXISTS, VoxMirror.renameSlot(r, "home", "House"))
        assertEquals(cinder, VoxMirror.read(r, "home"))
        assertEquals("the destination document", VoxMirror.read(r, "House"))
    }
}
