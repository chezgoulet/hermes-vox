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
        // #402: the Alpha gate first — it tells an upgrade from a fresh install by the Presence
        // key that PresenceGate.migrate is about to write.
        AlphaGate.migrate(prefs, presenceModelInstalled = ModelCatalog.isInstalled(this, "gemma-e2b"))
        PresenceGate.migrate(prefs)
        // #403: stage one of the hardware check, once per install, before any model is fetched.
        // It records and advises; it never enables anything.
        HardwareCheck.runOnce(this, prefs)
    }
}
