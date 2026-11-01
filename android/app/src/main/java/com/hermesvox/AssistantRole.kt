package com.hermesvox

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings

/**
 * #329: the phone's digital assistant is whatever app holds `android.app.role.ASSISTANT`
 * (RoleManager.ROLE_ASSISTANT, API 29); the holder receives the assist gesture and the
 * assist intent.
 *
 * What is Android's and what is not, because the #329 report is a Galaxy S26 Ultra and a
 * side button:
 *
 *  * Android's part — the ROLE. It decides which app the assist gesture reaches, and
 *    Android owns the consent dialog that grants it. This app can only ask, through that
 *    dialog, when the user taps the Settings row.
 *  * The maker's part — the BUTTON. Whether a long press on the side/power button is wired
 *    to "the assistant", to the power menu, or to the maker's own assistant is a One UI /
 *    OEM setting. Nothing in this app can bind a physical key, and an app that claimed to
 *    would be lying.
 *
 * Vox declares no VoiceInteractionService. Handling Intent.ACTION_ASSIST is what makes the
 * app eligible for the role, and it means the gesture opens the normal call screen: no
 * always-on listener, no wake word, no overlay.
 */
object AssistantRole {

    /**
     * RoleManager.ROLE_ASSISTANT, spelled out because the constant is API 29 and this class
     * also has to run — and say "not available" — on API 24-28.
     */
    const val ROLE = "android.app.role.ASSISTANT"

    enum class State {
        /** Vox holds it: the assist gesture opens Vox. */
        HELD,

        /** Another app holds it (Android always has one once an eligible app exists). */
        OTHER,

        /** Below API 29, or this build has no assistant role. */
        UNAVAILABLE,
    }

    fun state(context: Context): State {
        val rm = roleManager(context) ?: return State.UNAVAILABLE
        if (!rm.isRoleAvailable(ROLE)) return State.UNAVAILABLE
        return if (rm.isRoleHeld(ROLE)) State.HELD else State.OTHER
    }

    private fun roleManager(context: Context): RoleManager? =
        if (Build.VERSION.SDK_INT < 29) null else context.getSystemService(RoleManager::class.java)

    /**
     * The system's consent dialog ("Use Hermes Vox as your digital assistant?"). Nothing is
     * granted by showing it: only the user's tap in Android's own UI hands over the role.
     * This is the ONLY way the app ever asks, and it is reached from one Settings row.
     */
    fun requestIntent(context: Context): Intent? {
        val rm = roleManager(context) ?: return null
        if (!rm.isRoleAvailable(ROLE)) return null
        return rm.createRequestRoleIntent(ROLE)
    }

    /**
     * Android's own settings — the only place the role can be taken away again (there is no
     * public API to drop a role). Prefers the build's digital-assistant page, falls back to
     * the default-apps list, which always exists on API 24+.
     */
    fun systemSettingsIntent(context: Context): Intent {
        val assistant = Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)
        if (assistant.resolveActivity(context.packageManager) != null) return assistant
        return Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
    }

    /** One line for the Settings row: what the role means right now. Pure, so it is tested. */
    fun summary(state: State, otherLabel: String?): String = when (state) {
        State.HELD -> "On — the assistant gesture opens Vox"
        State.OTHER -> otherLabel
            ?.let { "Off — $it is the phone's assistant" }
            ?: "Off — another app is the phone's assistant"
        State.UNAVAILABLE -> "Not available on this Android build"
    }

    /**
     * The app that receives the assist gesture now, when it is not us. Resolving
     * ACTION_ASSIST is the public way to name it: the role holder is the app Android
     * resolves that intent to. Returns null when the answer would be meaningless (we hold
     * the role, the platform resolver answered, or nothing resolves).
     */
    fun otherHolderLabel(context: Context): String? {
        if (state(context) != State.OTHER) return null
        val probe = Intent(Intent.ACTION_ASSIST).addCategory(Intent.CATEGORY_DEFAULT)
        val resolved = context.packageManager.resolveActivity(probe, PackageManager.MATCH_DEFAULT_ONLY)
            ?: return null
        val pkg = resolved.activityInfo?.packageName ?: return null
        if (pkg == context.packageName || pkg == "android") return null
        return runCatching { resolved.loadLabel(context.packageManager)?.toString() }
            .getOrNull()?.takeIf { it.isNotBlank() }
    }
}
