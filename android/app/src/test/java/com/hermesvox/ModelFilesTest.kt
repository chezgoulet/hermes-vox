package com.hermesvox

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelFilesTest {

    private fun tempDir(): File = Files.createTempDirectory("vox-hoist").toFile()

    @Test
    fun hoist_moves_the_wrapped_contents_up_and_removes_the_wrapper() {
        val dir = tempDir()
        val wrap = File(dir, "sherpa-onnx-whisper-tiny.en").apply { mkdirs() }
        File(wrap, "encoder.onnx").writeText("encoder")
        File(wrap, "tokens.txt").writeText("tokens")

        hoist(dir)

        assertEquals("encoder", File(dir, "encoder.onnx").readText())
        assertEquals("tokens", File(dir, "tokens.txt").readText())
        assertTrue("wrapper should be gone", !wrap.exists())
    }

    @Test
    fun hoist_leaves_a_flat_layout_alone() {
        val dir = tempDir()
        File(dir, "model.onnx").writeText("flat")
        File(dir, "tokens.txt").writeText("tokens")

        hoist(dir)

        assertEquals("flat", File(dir, "model.onnx").readText())
    }

    @Test
    fun hoist_does_nothing_when_there_are_two_top_level_entries() {
        val dir = tempDir()
        val wrap = File(dir, "wrapped").apply { mkdirs() }
        File(wrap, "a.onnx").writeText("a")
        File(dir, "loose.txt").writeText("loose")

        hoist(dir)

        assertTrue("ambiguous layout must be left untouched", File(wrap, "a.onnx").exists())
    }
}
