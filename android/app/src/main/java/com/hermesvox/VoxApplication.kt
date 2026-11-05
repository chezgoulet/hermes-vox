package com.hermesvox

import android.app.Application

/**
 * VoxApplication — the place a process-wide, once-per-install fact belongs.
 *
 * Right now that is two preference migrations: #402's Alpha gate (an upgrade keeps what was
 * running, a fresh install starts with the gate shut), then #222/D16's — the retired
 * `voice_mode` choice is translated into `presence_enabled` before anything can read it.
 * The migration is idempotent (see [PresenceGate.migrate]), so the Application class is a
 * guarantee of ordering, not a correctness crutch.
 */
class VoxApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val prefs = getSharedPreferences("hv", MODE_PRIVATE)
        // M4 (0.9.1 review): the cleartext rule asks the tailnet detector live, and fails
        // closed until this process has installed it.
        com.hermesvox.core.CleartextPolicy.tunnelUp = { Tailnet.address() != null }
        // #402: the Alpha gate first — it tells an upgrade from a fresh install by the Presence
        // key that PresenceGate.migrate is about to write.
        AlphaGate.migrate(prefs, presenceModelInstalled = ModelCatalog.isInstalled(this, "gemma-e2b"))
        // #325: a quiet call the last process did not live to end is ended now — a crash must
        // never leave the phone in Do Not Disturb.
        CallQuiet.recover(this, prefs)
        PresenceGate.migrate(prefs)
        // L6 (0.9.1 review): an encryption that fell back to plaintext last time is retried
        // now, and a pre-0.9.1 unversioned ciphertext is moved to the marked form.
        upgradeStoredKey(prefs, "key")
        upgradeStoredKey(prefs, KEY_STT_REMOTE_KEY)
        // #403: stage one of the hardware check, once per install, before any model is fetched.
        // It records and advises; it never enables anything.
        HardwareCheck.runOnce(this, prefs)
        // The VOX.md workflow is inert with Presence off (owner rule a), and the one-way
        // migration of the pre-profile device-wide mirror is part of that workflow: an
        // install that never turned Presence on keeps its file exactly where it is, and the
        // next run with Presence on migrates it then.
        if (PresenceGate.enabled(prefs)) VoxMirror.migrate(this)
    }

    /** Rewrite one stored secret in the marked ciphertext form when it is not already. */
    private fun upgradeStoredKey(prefs: android.content.SharedPreferences, key: String) {
        val stored = prefs.getString(key, null) ?: return
        val next = SecureStore.storageUpgrade(stored, SecureStore::decrypt, SecureStore::encrypt) ?: return
        prefs.edit().putString(key, next).apply()
        VoxLog.d("event=secure-store upgraded pref=$key")
    }
}
