package com.hermesvox

import com.hermesvox.core.ApprovalArm
import com.hermesvox.core.Streams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #413 second pass, the three nits: a preference nothing writes is no longer read, a barge
 * `/stop` no longer spawns its own thread, and the debug arm lets go of its run id.
 */
class ReviewNitsTest {

    private fun src(path: String): String =
        listOf(File(path), File("app/$path")).firstOrNull { it.isFile }?.readText()
            ?: error("$path not found from ${File(".").absolutePath}")

    /** `presence` (a boolean) was read in MainActivity and written nowhere: always true. */
    @Test fun noCodeReadsABooleanPresencePrefThatNothingWrites() {
        val main = src("src/main/java/com/hermesvox/MainActivity.kt")
        assertFalse(main.contains("getBoolean(\"presence\""))
        val writers = File(if (File("src/main").isDirectory) "src/main" else "app/src/main")
            .walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { it.readText().contains("putBoolean(\"presence\"") }.toList()
        assertTrue("if a writer appears, the read may come back: $writers", writers.isEmpty())
    }

    /** The stops share a bounded pool whose idle workers exit; no thread per barge. */
    @Test fun theRunStopsShareABoundedPool() {
        val pool = Streams.stopExecutor
        assertEquals(2, pool.corePoolSize)
        assertEquals(2, pool.maximumPoolSize)
        assertTrue("idle stop workers must exit", pool.allowsCoreThreadTimeOut())
        val stream = src("src/main/java/com/hermesvox/core/Stream.kt")
        assertFalse("no thread per stop", stream.contains("Thread({ stopRunNow"))
    }

    @Test fun theArmLetsGoOfItsOwnRunOnly() {
        assertEquals("", ApprovalArm.armedAfter("run_arm_1", "run_arm_1"))
        assertEquals("run_arm_1", ApprovalArm.armedAfter("run_arm_1", "run_turn_7"))
        assertEquals("", ApprovalArm.armedAfter("", ""))
        assertEquals("", ApprovalArm.armedAfter("", "run_turn_7"))
    }

    /** Every place the arm's run can end clears it: terminal status, watch timeout, answer. */
    @Test fun theControllerClearsTheArmWhereverItsRunEnds() {
        val vc = src("src/main/java/com/hermesvox/VoiceController.kt")
        val watch = vc.substringAfter("private fun watchArmRun(").substringBefore("private fun sleepArm(")
        assertEquals("terminal status and timeout", 2,
            Regex("armRunId = ApprovalArm.armedAfter\\(armRunId, runId\\)").findAll(watch).count())
        val submit = vc.substringAfter("private fun submitApproval(").substringBefore("execSubmit {")
        assertTrue(submit.contains("armRunId = ApprovalArm.armedAfter(armRunId, answer.request.runId)"))
    }
}
