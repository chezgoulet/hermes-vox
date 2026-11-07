package com.hermesvox

import java.io.File

/**
 * Unpacks canonical archives that wrap their contents in a single top-level
 * directory (`sherpa-onnx-whisper-tiny.en/…`) so the pipeline finds
 * `encoder.onnx` at the model root.
 *
 * Top-level rather than a method on [ModelDownloader] so it is testable without
 * an Android Context.
 */
internal fun hoist(dir: File) {
    val entries = dir.listFiles() ?: return
    val sub = entries.firstOrNull { it.isDirectory } ?: return
    // Only hoist when there is exactly one top-level dir (a wrapping folder), not
    // a flat layout.
    if (entries.size != 1) return
    sub.listFiles()?.forEach { f ->
        val dest = File(dir, f.name)
        // Same directory, same filesystem: a rename moves the entry, where a copy
        // doubled peak disk while the wrapping folder was still there.
        if (f.renameTo(dest)) return@forEach
        // Only if the rename is refused (unexpected, e.g. a bind mount).
        if (f.isDirectory) f.copyRecursively(dest, true) else f.copyTo(dest, true)
        f.deleteRecursively()
    }
    sub.deleteRecursively()
}

/** The files a streaming transducer export (Nemotron) must have at its model root after
 *  [hoist]: the upstream tarball's own names, kept as-is (no renaming needed). */
internal val STREAMING_TRANSDUCER_FILES =
    listOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt")
