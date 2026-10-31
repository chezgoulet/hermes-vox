package com.hermesvox

import android.app.Application

/**
 * VoxApplication — the place a process-wide, once-per-install fact belongs.
 *
 * Right now that is the #222/D16 preference migration: the retired `voice_mode` choice is
 * translated into `presence_enabled` before any activity, service or controller can read it.
 * The migration is idempotent (see [PresenceGate.migrate]), so the Application class is a
 * guarantee of ordering, not a correctness crutch.
 */
class VoxApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        PresenceGate.migrate(getSharedPreferences("hv", MODE_PRIVATE))
    }
}
