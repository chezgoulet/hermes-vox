package com.hermesvox

/**
 * SttLanguage — the rules behind Settings → Hearing → "Spoken language".
 *
 * The row exists on Pierre's branch, and it was a lie: it offered a language for
 * every recogniser, including the `.en` Whisper checkpoints and the English-only
 * transducers, which cannot decode anything but English. A choice stored there was
 * silently discarded by [ModelCatalog.whisperLanguage] on the next call.
 *
 * Here the row is a consequence of the selected recogniser instead of a second
 * opinion about it:
 *
 *  - **hidden** unless the recogniser that will actually run is multilingual
 *    (the multilingual Whisper exports — [ModelCatalog.isMultilingualStt]);
 *  - when it is shown, the chosen code is the one passed to the recogniser
 *    (`OfflineWhisperStt` reads [ModelCatalog.KEY_STT_LANGUAGE]);
 *  - when an English-only model is selected while a non-English language is
 *    stored, [englishOnlyMismatch] is true and Settings offers the matching
 *    multilingual model **with its real download size** instead of leaving the
 *    choice to be dropped in silence ([multilingualSibling]).
 *
 * Pure Kotlin (no Android deps) so every branch above is testable off-device.
 */
object SttLanguage {

    /** One entry of the picker. [code] is what the recogniser receives. */
    data class Choice(val code: String, val label: String)

    /**
     * The offered languages, in the order a French-first owner meets them. "" is
     * Whisper's own auto-detect — the default, and what an English-only model
     * effectively does.
     */
    val CHOICES: List<Choice> = listOf(
        Choice("", "Auto-detect"),
        Choice("fr", "Français (France)"),
        Choice("en", "English"),
        Choice("es", "Español"),
        Choice("de", "Deutsch"),
        Choice("it", "Italiano"),
    )

    /** The human label for a stored code; an unknown code is shown as itself, never
     *  disguised as a language this build knows. */
    fun label(code: String?): String {
        val c = code.orEmpty().trim().lowercase()
        return CHOICES.firstOrNull { it.code == c }?.label ?: if (c.isEmpty()) "Auto-detect" else c
    }

    /**
     * The recogniser that will actually run: the streaming arm when one is selected
     * (it replaces the shipped model), else the shipped model.
     */
    fun chosenModel(shippedModel: String?, streamingModel: String?): String? =
        if (ModelCatalog.isStreamingStt(streamingModel)) streamingModel else shippedModel

    /** Is the language row shown? Only for the on-device recogniser, and only when
     *  that recogniser can decode a language at all. */
    fun rowVisible(backend: String?, shippedModel: String?, streamingModel: String?): Boolean =
        backend?.trim() == ModelCatalog.BACKEND_ONDEVICE &&
            ModelCatalog.isMultilingualStt(chosenModel(shippedModel, streamingModel))

    /**
     * The stored language cannot be honoured: an English-only recogniser is selected
     * and the owner picked something other than English. This is the state the old
     * row hid — the pref looked saved and was thrown away at decode time.
     */
    fun englishOnlyMismatch(shippedModel: String?, streamingModel: String?, code: String?): Boolean {
        val model = chosenModel(shippedModel, streamingModel) ?: return false
        if (ModelCatalog.isMultilingualStt(model)) return false
        val c = code.orEmpty().trim().lowercase()
        return c.isNotEmpty() && c != "en"
    }

    /**
     * The multilingual model to offer when [modelId] cannot hear the chosen language.
     * Same size tier where one exists (tiny/base/small), else the balanced default —
     * the honest "closest model that does hear you", never a promise of an identical
     * download.
     */
    fun multilingualSibling(modelId: String?): String = when (modelId?.trim()) {
        "whisper-tiny" -> "whisper-tiny-multi"
        "whisper-base" -> "whisper-base-multi"
        "whisper-small" -> "whisper-small-multi"
        else -> "whisper-base-multi"
    }
}
