package com.hermesvox

/**
 * SoulAudio — the caller's utterance, packaged for the soul's ears, pure JVM.
 *
 * Gemma 4 E2B carries its own audio encoder, so the soul can hear HOW something was said, not
 * only the words Whisper wrote down: a laugh, a sigh, a clipped "fine". LiteRT-LM takes audio as
 * encoded file bytes (`Content.AudioBytes`), so the capture's float PCM is written as a 16 kHz
 * mono 16-bit WAV. Only the utterance's last [MAX_SECONDS] are sent: tone lives in the delivery,
 * the soul already has the full words from the transcript, and every second rides the soul's
 * rolling conversation as tokens.
 *
 * The audio never leaves the phone: it goes to the on-device model and nowhere else.
 */
object SoulAudio {
    /** Settings switch (Enhanced Realtime): the soul hears the caller's tone. Default on. */
    const val PREF = "er_soul_hears"
    const val MAX_SECONDS = 8
    /** Below this there is too little voice to read a tone from. */
    const val MIN_SECONDS = 0.4

    /** A WAV (16-bit PCM, mono) of the utterance's tail, or null when it is too short. */
    fun wav(samples: FloatArray?, sampleRate: Int = 16000): ByteArray? {
        if (samples == null || sampleRate <= 0 || samples.size < sampleRate * MIN_SECONDS) return null
        val n = minOf(samples.size, sampleRate * MAX_SECONDS)
        val from = samples.size - n
        val data = n * 2
        val out = java.nio.ByteBuffer.allocate(44 + data).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        out.put("RIFF".toByteArray()).putInt(36 + data).put("WAVE".toByteArray())
        out.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            .putInt(sampleRate).putInt(sampleRate * 2).putShort(2).putShort(16)
        out.put("data".toByteArray()).putInt(data)
        for (i in from until samples.size) {
            val v = (samples[i].coerceIn(-1f, 1f) * 32767f).toInt()
            out.putShort(v.toShort())
        }
        return out.array()
    }
}
