package com.hermesvox

/**
 * SttWindows — the pure rule that turns ONE utterance of any length into
 * recognizer-sized windows, so the committed turn is a transcription of the
 * WHOLE utterance.
 *
 * Why: Whisper's encoder sees at most 30 s (3000 feature frames). sherpa-onnx
 * 1.13.6 pads the features with `tail_paddings` frames and then clamps to 3000
 * (offline-recognizer-whisper-impl.h: `actual_frames = min(num_frames +
 * tail_padding_frames, max_num_frames)`), so anything past 30 s is silently
 * dropped — and a clip near 30 s gets almost no tail padding, which is what
 * lets Whisper find its end-of-text token (without it the final words drop).
 * B2 lifted the utterance ceiling ("you talk until you're done"), so a long
 * turn has to be windowed here.
 *
 * The rule: a clip up to [windowMs] is one window. A longer clip is cut at the
 * QUIETEST 20 ms frame within the last [SEARCH_MS] of each window (a word gap —
 * never mid-word when a gap exists), and the next window starts at the cut.
 * [WHISPER_WINDOW_MS] = 25 s leaves >= 5 s of the 30 s for tail padding.
 * Mirrored exactly by tools/sttbench/run_bench.py:windows().
 */
object SttWindows {

    /** Whisper window: 30 s encoder limit minus >= 5 s of tail-padding room. */
    const val WHISPER_WINDOW_MS = 25_000

    /** Where a cut may land: the last 4 s of each window. */
    const val SEARCH_MS = 4_000

    /** Energy-scan hop (20 ms). */
    private const val HOP_MS = 20

    /** Half-open [start, end) sample spans covering [0, n) in order. */
    fun spans(samples: FloatArray, sampleRate: Int, windowMs: Int = WHISPER_WINDOW_MS): List<Pair<Int, Int>> {
        val n = samples.size
        val win = (sampleRate.toLong() * windowMs / 1000).toInt()
        if (win <= 0 || n <= win) return listOf(0 to n)
        val search = (sampleRate.toLong() * minOf(SEARCH_MS, windowMs) / 1000).toInt()
        val hop = maxOf(1, sampleRate * HOP_MS / 1000)
        val out = ArrayList<Pair<Int, Int>>()
        var start = 0
        while (n - start > win) {
            val lo = start + win - search
            val hi = start + win
            var best = Double.MAX_VALUE
            var cut = hi
            var i = lo
            while (i + hop <= hi) {
                var e = 0.0
                for (k in i until i + hop) e += samples[k].toDouble() * samples[k]
                if (e < best) { best = e; cut = i + hop / 2 }
                i += hop
            }
            out.add(start to cut)
            start = cut
        }
        out.add(start to n)
        return out
    }

    /** Join per-window texts: trimmed, blanks dropped, single-spaced. */
    fun join(parts: List<String?>): String =
        parts.mapNotNull { it?.trim()?.takeIf { t -> t.isNotEmpty() } }.joinToString(" ")
}
