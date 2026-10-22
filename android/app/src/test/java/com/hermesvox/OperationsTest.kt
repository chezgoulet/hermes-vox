package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** #281: the state line's words for a tool step, from the stream's own two facts. */
class OperationsTest {

    @Test fun builtInToolIsNamedInPlainLanguageWithItsTarget() {
        val s = Operations.step("web_search", """{"query":"trail conditions Mount Monadnock"}""")
        assertEquals("Searching the web · trail conditions Mount Monadnock", Operations.running(s, 0))
        assertEquals("Searched the web · trail conditions Mount Monadnock", Operations.finished(s))
    }

    @Test fun urlIsNamedByHostAndPathByFile() {
        assertEquals("Reading a web page · example.org",
            Operations.running(Operations.step("web_extract", """{"urls":["x"],"url":"https://www.example.org/a/b"}"""), 0))
        assertEquals("Reading a file · notes.md",
            Operations.running(Operations.step("read_file", """{"path":"/srv/notes/notes.md"}"""), 0))
    }

    @Test fun calendarAndEmailToolsAreNamedByWhatTheyTouch() {
        assertEquals("Checking your calendar · today",
            Operations.running(Operations.step("mcp_google_calendar_list_events", """{"date":"today"}"""), 0))
        assertEquals("Changing your calendar",
            Operations.running(Operations.step("mcp_google_calendar_delete_event", "{}"), 0))
        assertEquals("Sending an email · Can't make it today",
            Operations.running(Operations.step("mcp_gmail_send", """{"subject":"Can't make it today","body":"long private text"}"""), 0))
        assertEquals("Drafting an email", Operations.running(Operations.step("gmail_create_draft", ""), 0))
    }

    @Test fun contentIsNeverTheTarget() {
        // An email body or a file's contents is not what the line names.
        val s = Operations.step("write_file", """{"content":"secret plans"}""")
        assertEquals("Writing a file", Operations.running(s, 0))
    }

    @Test fun unknownToolIsHumanisedFromItsName() {
        assertEquals("Using github: list issues", Operations.running(Operations.step("mcp_github_list_issues", "{}"), 0))
        assertEquals("Using home assistant", Operations.running(Operations.step("home_assistant", "not json"), 0))
    }

    @Test fun memoryWriteIsNotACheck() {
        assertEquals("Updating memory", Operations.running(Operations.step("memory", """{"action":"add","content":"x"}"""), 0))
        assertEquals("Checking memory", Operations.running(Operations.step("memory", "{}"), 0))
    }

    @Test fun longStepShowsThatItIsStillMoving() {
        val s = Operations.step("terminal", """{"command":"gws calendar events list"}""")
        assertEquals("Running a command · gws calendar events list", Operations.running(s, Operations.LONG_MS - 1))
        assertEquals("Running a command · gws calendar events list · still working, 10 s", Operations.running(s, 12_400))
        assertEquals("Running a command · gws calendar events list · still working, 15 s", Operations.running(s, 15_000))
    }

    @Test fun longTargetIsCutToOneShortLine() {
        val s = Operations.step("web_search", """{"query":"a\nvery long query that goes on and on and on forever"}""")
        val line = Operations.running(s, 0)
        assertEquals("Searching the web · a very long query that goes on and on a…", line)
    }

    @Test fun failureIsOnlyWhatHermesReportsAsOne() {
        assertEquals("permission denied", Operations.failure("""{"error":"permission denied"}"""))
        assertEquals("exit code 2", Operations.failure("""{"output":"","exit_code":2,"error":null}"""))
        assertEquals("quota", Operations.failure("""{"error":{"message":"quota"}}"""))
        assertNull(Operations.failure("""{"output":"ok","exit_code":0,"error":null}"""))
        // Prose is never read for failure words.
        assertNull(Operations.failure("Error: this text merely mentions an error"))
        assertNull(Operations.failure(""))
    }

    @Test fun failedLineSaysWhatFailedAndWhy() {
        val s = Operations.step("mcp_gmail_send", "{}")
        assertEquals("✕ Sending an email failed — permission denied", Operations.failed(s, "permission denied"))
    }
}
