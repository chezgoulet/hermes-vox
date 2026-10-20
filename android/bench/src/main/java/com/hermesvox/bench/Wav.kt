package com.hermesvox.bench

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal RIFF/WAVE reader for the bench corpus (16 kHz mono 16-bit PCM, or
 * 32-bit float). Stereo is downmixed; other sample rates are returned as-is and
 * rejected by the caller, so a mis-encoded clip fails loudly instead of being
 * silently mis-timed.
 */
object Wav {

    class Clip(val samples: FloatArray, val sampleRate: Int)

    fun read(file: File): Clip {
        val b = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        require(b.int == 0x46464952) { "${file.name}: not RIFF" }        // "RIFF"
        b.int                                                           // riff size
        require(b.int == 0x45564157) { "${file.name}: not WAVE" }        // "WAVE"
        var channels = 1
        var sampleRate = 0
        var bits = 16
        var format = 1
        var dataOff = -1
        var dataLen = 0
        while (b.remaining() >= 8) {
            val id = b.int
            val size = b.int
            val next = b.position() + size + (size and 1)
            when (id) {
                0x20746d66 -> {                                          // "fmt "
                    format = b.short.toInt() and 0xffff
                    channels = b.short.toInt() and 0xffff
                    sampleRate = b.int
                    b.int                                                    // byte rate
                    b.short                                                   // block align
                    bits = b.short.toInt() and 0xffff
                }
                0x61746164 -> {                                          // "data"
                    dataOff = b.position()
                    dataLen = size
                }
            }
            b.position(minOf(next, b.capacity()))
        }
        require(dataOff >= 0 && sampleRate > 0) { "${file.name}: missing fmt/data chunk" }
        val frames = dataLen / (channels * (bits / 8))
        val out = FloatArray(frames)
        val bb = ByteBuffer.wrap(b.array(), dataOff, dataLen).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frames) {
            var acc = 0f
            for (c in 0 until channels) {
                acc += when {
                    format == 3 && bits == 32 -> bb.float
                    bits == 16 -> bb.short / 32768f
                    bits == 32 -> bb.int / 2147483648f
                    bits == 8 -> ((bb.get().toInt() and 0xff) - 128) / 128f
                    else -> error("${file.name}: unsupported ${bits}-bit format $format")
                }
            }
            out[i] = acc / channels
        }
        return Clip(out, sampleRate)
    }
}
