package com.hermesvox

import android.content.Context

/**
 * ModelLicences — per-model acceptance of a [ModelLicence], asked for on the download screen
 * before the transfer starts and required by [ModelDownloader] to proceed.
 *
 * The stored value is the licence NAME the user accepted, not a bare flag: if a model's
 * licence ever changes, the old acceptance no longer matches and the user is asked again.
 */
object ModelLicences {
    private fun key(id: String) = "model_licence_accepted_$id"

    /** The licence name accepted for [id], or null. */
    private fun accepted(context: Context, id: String): String? =
        context.getSharedPreferences("hv", Context.MODE_PRIVATE).getString(key(id), null)

    /** True when [id] needs no licence step, or its current licence was accepted. */
    fun isAccepted(context: Context, id: String): Boolean =
        matches(ModelCatalog.blessed.firstOrNull { it.id == id }, accepted(context, id))

    /** Pure: [stored] (the accepted licence name, or null) covers [spec]'s current licence. */
    internal fun matches(spec: ModelSpec?, stored: String?): Boolean =
        spec?.licence == null || stored == spec.licence.name

    fun accept(context: Context, spec: ModelSpec) {
        val licence = spec.licence ?: return
        context.getSharedPreferences("hv", Context.MODE_PRIVATE).edit().putString(key(spec.id), licence.name).apply()
    }

    /** Pure gate: null when [spec] may be downloaded, else the reason it may not. */
    fun refusal(spec: ModelSpec, isAccepted: (String) -> Boolean): String? =
        if (spec.licence != null && !isAccepted(spec.id))
            "the ${spec.licence.name} has not been accepted — tap Download to review it"
        else null
}
