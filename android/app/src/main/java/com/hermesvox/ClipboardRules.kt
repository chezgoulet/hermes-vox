package com.hermesvox

/**
 * ClipboardRules — what the two clipboard actions do when the platform is asked, as a pure
 * decision.
 *
 * Android 10's restriction is on clipboard **reads**: only the focused app or the default IME may
 * read a clip. A write through `setPrimaryClip` is permitted without focus. The old rule here had
 * that backwards and refused a write whenever no activity was resumed — which, in picture-in-picture,
 * is the whole call: the activity is paused, so `launch`/`open` left both actions `unavailable`.
 *
 * So neither action predicts what the platform will do:
 *
 *  * [write] attempts the write and reports the platform's answer. A `SecurityException` or any
 *    other throw is the platform's refusal, carried as [PhoneEngine.PhonePlatform.ClipWrite.Refused]
 *    with its reason — not a guess made before asking.
 *  * [read] asks for the clip and reports what came back. [inFront] never replaces the question; it
 *    only shapes the sentence when the platform answered "no clip", where lack of focus is the
 *    likely cause but not a proven one.
 *
 * Pure JVM (no Android types), so [ClipboardRulesTest] proves the rule off-device; [LocalActions]
 * supplies the `ClipboardManager` calls.
 */
object ClipboardRules {

    /** What one look at the clipboard found, before the focus-shaped sentence is chosen. */
    sealed class Reading {
        /** Text the platform handed over. */
        data class Text(val text: String) : Reading()

        /** The platform returned no clip at all. */
        data object NoClip : Reading()

        /** There is a clip, but its first item holds no text (an image, or private content). */
        data object NoText : Reading()
    }

    /**
     * Write, and report what the platform did. [attempt] is the platform's `setPrimaryClip`; it is
     * always called, whatever [AppFocus] says, because background writes are allowed.
     */
    fun write(attempt: () -> Unit): PhoneEngine.PhonePlatform.ClipWrite = try {
        attempt()
        PhoneEngine.PhonePlatform.ClipWrite.Done
    } catch (t: Throwable) {
        PhoneEngine.PhonePlatform.ClipWrite.Refused(
            "Android refused the clipboard write (${t.javaClass.simpleName}); nothing was written",
        )
    }

    /**
     * Read, and report what the platform answered. [read] is always called. [inFront] is used only
     * for the [Reading.NoClip] case: the platform does not say *why* it returned nothing, so the
     * sentence names focus as the likely cause rather than claiming to know it.
     */
    fun read(
        inFront: Boolean,
        read: () -> Reading,
    ): PhoneEngine.PhonePlatform.ClipRead = try {
        when (val found = read()) {
            is Reading.Text -> PhoneEngine.PhonePlatform.ClipRead.Text(found.text)
            Reading.NoText -> PhoneEngine.PhonePlatform.ClipRead.Unavailable(
                "the clipboard holds no text (it may be an image or another app's private " +
                    "content), so there was nothing to return",
            )
            Reading.NoClip -> if (inFront) {
                PhoneEngine.PhonePlatform.ClipRead.Empty
            } else {
                PhoneEngine.PhonePlatform.ClipRead.Unavailable(
                    "Android returned no clipboard and Vox is not the app on screen; the likely " +
                        "reason is that Android 10+ only lets the focused app read a clip, so " +
                        "nothing was read",
                )
            }
        }
    } catch (t: Throwable) {
        PhoneEngine.PhonePlatform.ClipRead.Unavailable(
            "Android refused the clipboard read (${t.javaClass.simpleName}); nothing was read",
        )
    }
}
