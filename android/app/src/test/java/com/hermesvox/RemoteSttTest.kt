package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM proof of RemoteStt's device-free core (the class itself needs a
 * Context + network, so the unit-testable surface is the encoder + guards +
 * response parser, all `internal` top-level functions):
 *  - wavPcm16: correct RIFF/WAVE PCM16 header + Int16 clip of loud frames.
 *  - URL guards: a blank / scheme-less stt_remote_url means "not configured" —
 *    isAvailable's blank gate, no probe/transcribe target, and NO exception
 *    (the "no protocol" crash ModelCatalog.resolveSource already guards).
 *  - parseSttText: `{"text"}` extraction + the missing-key / blank edge cases.
 */
class RemoteSttTest {

    // --- WAV encoder ---

    @Test fun wav_header_is_standard_pcm16_mono() {
        val sr = 22050
        val samples = floatArrayOf(0f, 0.25f, -0.5f, 1f, -1f)
        val wav = wavPcm16(samples, sr)
        val dataSize = samples.size * 2
        assertEquals("RIFF", String(wav, 0, 4, Charsets.US_ASCII))
        assertEquals(wav.size - 8, leInt(wav, 4))            // RIFF chunk = file - 8
        assertEquals("WAVE", String(wav, 8, 4, Charsets.US_ASCII))
        assertEquals("fmt ", String(wav, 12, 4, Charsets.US_ASCII))
        assertEquals(16, leInt(wav, 16))                     // fmt chunk size
        assertEquals(1, leShort(wav, 20))                    // PCM (no compression)
        assertEquals(1, leShort(wav, 22))                    // mono
        assertEquals(sr, leInt(wav, 24))                     // sample rate
        assertEquals(sr * 2, leInt(wav, 28))                 // byte rate (mono 16-bit)
        assertEquals(2, leShort(wav, 32))                    // block align
        assertEquals(16, leShort(wav, 34))                   // bits per sample
        assertEquals("data", String(wav, 36, 4, Charsets.US_ASCII))
        assertEquals(dataSize, leInt(wav, 40))               // data chunk size
        assertEquals(wav.size - 44, dataSize)                // nothing after the data
    }

    @Test fun samples_clip_at_unit_amplitude_into_int16_range() {
        val wav = wavPcm16(floatArrayOf(1f, -1f, 2f, -2f, 0f, 0.5f), 16000)
        assertEquals(32767, leShort(wav, 44))      // +1.0 -> Int16 max (not a wrap)
        assertEquals(-32768, leShort(wav, 46))     // -1.0 -> Int16 min
        assertEquals(32767, leShort(wav, 48))      // +2.0 clips to +1.0
        assertEquals(-32768, leShort(wav, 50))     // -2.0 clips to -1.0
        assertEquals(0, leShort(wav, 52))          // silence
        val mid = leShort(wav, 54)                 // 0.5 -> ~half scale, positive
        assertTrue("0.5f scaled=$mid", mid in 16000..16500)
    }

    @Test fun sample_rate_is_written_verbatim() {
        val wav8k = wavPcm16(floatArrayOf(0f, 0f), 8000)
        assertEquals(8000, leInt(wav8k, 24))
        assertEquals(16000, leInt(wav8k, 28))
    }

    // --- URL guards (blank => not configured => unavailable, never an exception) ---

    @Test fun blank_or_scheme_less_url_is_not_configured() {
        for (bad in listOf(null, "", "   ", "192.168.1.9:13305", "host.lan")) {
            val base = sttBaseUrl(bad)
            assertTrue("input=\"$bad\"", base.isBlank())
            assertTrue(sttProbeUrls(base).isEmpty())          // nothing to probe
            assertNull(sttTranscribeUrl(base, "/v1"))          // never build a POST target
            assertNull(sttTranscribeUrl(base, ""))
        }
    }

    @Test fun real_http_base_builds_probe_and_transcribe_targets() {
        val base = sttBaseUrl(" http://192.168.1.9:13305/ ")
        assertEquals("http://192.168.1.9:13305", base)
        assertEquals(
            listOf("http://192.168.1.9:13305/v1/models", "http://192.168.1.9:13305/api/v1/models"),
            sttProbeUrls(base)
        )
        // the api prefix derived from whichever probe URL answered 200
        assertEquals("/v1", sttApiPrefix(base, "http://192.168.1.9:13305/v1/models"))
        assertEquals("/api/v1", sttApiPrefix(base, "http://192.168.1.9:13305/api/v1/models"))
        assertEquals("http://192.168.1.9:13305/v1/audio/transcriptions", sttTranscribeUrl(base, "/v1"))
        assertEquals("http://192.168.1.9:13305/api/v1/audio/transcriptions", sttTranscribeUrl(base, "/api/v1"))
    }

    @Test fun base_already_carrying_v1_prefix_probes_once_without_double_v1() {
        val base = sttBaseUrl("http://192.168.1.9:13305/v1")
        assertEquals(listOf("http://192.168.1.9:13305/v1/models"), sttProbeUrls(base))
        assertEquals("", sttApiPrefix(base, "http://192.168.1.9:13305/v1/models"))
        assertEquals("http://192.168.1.9:13305/v1/audio/transcriptions", sttTranscribeUrl(base, ""))
    }

    // --- Response parsing ({"text": ...}) ---

    @Test fun parses_openai_style_text_reply() {
        assertEquals("Hello world", parseSttText("""{"text":"Hello world"}"""))
    }

    @Test fun trims_surrounding_whitespace() {
        assertEquals("hi", parseSttText("""{"text":"  hi  "}"""))
        assertEquals("hi", parseSttText("  { \"text\" : \"hi\" }  "))
    }

    @Test fun missing_key_is_null() {
        assertNull(parseSttText("{}"))
        assertNull(parseSttText("""{"other":"x"}"""))
    }

    @Test fun blank_text_is_null() {
        assertNull(parseSttText("""{"text":""}"""))
        assertNull(parseSttText("""{"text":"   "}"""))
    }

    @Test fun unparseable_body_is_null() {
        assertNull(parseSttText(null))
        assertNull(parseSttText(""))
        assertNull(parseSttText("   "))
        assertNull(parseSttText("not json at all"))
        assertNull(parseSttText("[]"))
        assertNull(parseSttText("""{"text":}"""))
    }

    @Test fun json_escapes_are_decoded() {
        assertEquals("say \"hi\"", parseSttText("""{"text":"say \"hi\""}"""))
        assertEquals("a\nb", parseSttText("""{"text":"a\nb"}"""))
        assertEquals("caf\u00e9", parseSttText("""{"text":"caf\u00e9"}"""))
    }

    private fun leInt(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or
            ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or
            ((b[o + 3].toInt() and 0xFF) shl 24)

    private fun leShort(b: ByteArray, o: Int): Int = (b[o].toInt() and 0xFF) or (b[o + 1].toInt() shl 8)
}
