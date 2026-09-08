package com.hermesvox

/**
 * ErGemmaGuard — safety rails around the on-device Gemma presence (0.6.3),
 * pure JVM. The three failure modes this guards:
 *
 *  1. RUNAWAY GENERATION: a 2B told to "render naturally" can ramble. Without
 *     a cap, a filler becomes a monologue. The guard bounds output tokens.
 *  2. MAIN-THREAD REENTRY: express() is runBlocking; if a host ever calls it
 *     on main again, the ANR returns. The guard detects and refuses (falls
 *     back to the instant renderer) rather than freezing the UI.
 *  3. REPEAT RATE: the same filler twice inside a window reads as a stuck
 *     record. The guard enforces the spacing the cap alone doesn't.
 *
 * Pure JVM: every rail is a testable predicate, not a side effect.
 */
object ErGemmaGuard {

    /** Max output characters a single Gemma render may return. 2B "one or two
     *  sentences" is ~200 chars; 600 is generous headroom. */
    const val MAX_RENDER_CHARS = 600

    /** Min ms between two soul renders (the stuck-record spacing). */
    const val MIN_RENDER_SPACING_MS = 1200L

    /** True when the caller's thread is (almost certainly) the main thread of
     *  an Android app. On JVM tests this returns false. */
    fun onMainThread(): Boolean = try {
        Class.forName("android.os.Looper").getMethod("myLooper")
            .invoke(null) != null &&
            Class.forName("android.os.Looper").getMethod("getMainLooper")
                .invoke(null) == Class.forName("android.os.Looper").getMethod("myLooper").invoke(null)
    } catch (_: Throwable) { false }

    /** Rail 1+2 for the render entry: returns the text to speak (possibly the
     *  fallback), or null when the render must be refused outright. */
    fun checkRender(text: String, nowMs: Long, lastRenderAtMs: Long, onMain: Boolean = onMainThread()): String? {
        if (onMain) return null                       // rail 2: refuse, never freeze
        if (text.isBlank()) return null
        if (nowMs - lastRenderAtMs < MIN_RENDER_SPACING_MS) return null  // rail 3
        return if (text.length <= MAX_RENDER_CHARS) text else text.take(MAX_RENDER_CHARS)
    }
}
