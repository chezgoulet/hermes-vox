package com.hermesvox

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings

/**
 * SoundCuePlayer — the Android half of #156: it reads the phone's state, asks
 * [CuePolicy] what a cue may do, and then either renders a [CueSynth] buffer into an
 * AudioTrack, taps the vibrator, or stays silent. Every judgement lives in the pure
 * policy/synth objects; this class is transport.
 *
 * WHAT IT OWNS
 *  - one AudioTrack at a time, 22.05 kHz mono 16-bit, MODE_STATIC, usage
 *    USAGE_ASSISTANCE_SONIFICATION (the sonification usage, not notification: these are
 *    the app's own UI sounds, never an alert), riding STREAM_MUSIC so the hardware
 *    media volume stays the master.
 *  - the generated-buffer cache: one ShortArray per (cue × timbre). Nothing is written
 *    to disk and the app ships no cue asset.
 *  - the TTS suppression flag. [setVoiceActive] is fed from MainActivity's frame tick
 *    (the same `voicing` witness the avatar uses: real audio on the track, with a hold
 *    that bridges the synth gaps between phrases), so a cue can never land under the
 *    being's voice. It is a companion flag because Settings' preview needs it too.
 *  - haptics through VibratorManager on API 31+ and the legacy Vibrator below, gated by
 *    Settings.System.HAPTIC_FEEDBACK_ENABLED.
 *
 * Failures are swallowed (a cue is never allowed to break a turn) but always logged
 * with the decision's reason token, so a device report says what was decided and why.
 */
class SoundCuePlayer(context: Context) {

    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("hv", Context.MODE_PRIVATE)
    private val am = app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
    private val handler = Handler(Looper.getMainLooper())

    /** One buffer per (cue, timbre), generated once. */
    private val cache = HashMap<String, ShortArray>()

    @Volatile private var track: AudioTrack? = null
    @Volatile private var trackId = 0

    private val vibrator: Vibrator? by lazy { resolveVibrator() }

    /**
     * Play [cue]. [preview] is the Settings button: it bypasses the on/off toggle (the
     * user tapped it to hear exactly this) but nothing else — a preview is still silent
     * under TTS, DND, a silent ringer, or zero media volume.
     *
     * @return the decision, so the caller can tell the user what happened.
     */
    fun play(cue: CueSynth.Cue, preview: Boolean = false): CuePolicy.Decision {
        val world = world(preview)
        val decision = CuePolicy.decide(world)
        VoxLog.d(
            "event=cue cue=${cue.name.lowercase()} action=${decision.action.name.lowercase()} " +
                "reason=${decision.reason} preview=$preview ringer=${ringerName(world.ringerMode)} " +
                "dnd=${world.dnd} vol=${world.mediaVolume}/${maxVolume()} level=${world.levelPercent} " +
                "haptics=${world.hapticsEnabled} timbre=${timbre().name.lowercase()}")
        try {
            when (decision.action) {
                CuePolicy.Action.AUDIO -> playAudio(cue, if (cue == CueSynth.Cue.PING) decision.volume * CuePolicy.Ambient.PING_GAIN else decision.volume)
                CuePolicy.Action.HAPTIC -> playHaptic(cue)
                CuePolicy.Action.SILENT -> Unit
            }
        } catch (t: Throwable) {
            // A cue must never break a turn or a Settings tap.
            VoxLog.w("event=cue-fail cue=${cue.name.lowercase()} ${t.javaClass.simpleName}: ${t.message}")
        }
        return decision
    }

    /** Stop and release anything currently sounding. */
    fun release() {
        trackId++
        val t = track
        track = null
        handler.removeCallbacksAndMessages(null)
        try { t?.pause(); t?.flush(); t?.release() } catch (_: Throwable) {}
        voiceActive = false
    }

    // ---- the world, as this phone reports it --------------------------------------

    private fun world(preview: Boolean) = CuePolicy.World(
        enabled = prefs.getBoolean(CuePolicy.PREF_ENABLED, CuePolicy.DEFAULT_ENABLED),
        silentOptIn = prefs.getBoolean(CuePolicy.PREF_SILENT, CuePolicy.DEFAULT_SILENT),
        levelPercent = prefs.getInt(CuePolicy.PREF_LEVEL, CuePolicy.DEFAULT_LEVEL),
        mediaVolume = am?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0,
        ringerMode = am?.ringerMode ?: CuePolicy.RINGER_NORMAL,
        // DND is the notification interruption filter, not an AudioManager field.
        dnd = nm != null && nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL,
        hapticsEnabled = hapticsEnabled(),
        ttsSpeaking = voiceActive,
        preview = preview,
        scope = prefs.getString(CuePolicy.PREF_SCOPE, CuePolicy.DEFAULT_SCOPE) ?: CuePolicy.DEFAULT_SCOPE,
        // Presence speaks only in Enhanced Realtime with its switch on (the skill's "Modes").
        presenceOn = prefs.getString(ModelCatalog.KEY_VOICE_MODE, ModelCatalog.MODE_REALTIME) == ModelCatalog.MODE_ENHANCED &&
            prefs.getBoolean("er_presence", true),
    )

    private fun maxVolume(): Int = am?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 0

    /** System haptics AND a vibrator: either one missing means "no haptic cue". */
    private fun hapticsEnabled(): Boolean {
        val on = try {
            Settings.System.getInt(app.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) != 0
        } catch (_: Throwable) { true }
        return on && vibrator?.hasVibrator() == true
    }

    private fun resolveVibrator(): Vibrator? = try {
        if (Build.VERSION.SDK_INT >= 31) {
            (app.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            app.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    } catch (_: Throwable) { null }

    private fun ringerName(mode: Int): String = when (mode) {
        CuePolicy.RINGER_SILENT -> "silent"
        CuePolicy.RINGER_VIBRATE -> "vibrate"
        CuePolicy.RINGER_NORMAL -> "normal"
        else -> "mode-$mode"
    }

    // ---- audio ---------------------------------------------------------------------

    /** The being's archetype decides the colour of every family (see CueSynth). */
    private fun timbre(): CueSynth.Timbre =
        CueSynth.timbreFor(prefs.getString("particles_theme", "aura"))

    private fun pcm(cue: CueSynth.Cue): ShortArray {
        val t = timbre()
        return cache.getOrPut("${cue.name}:${t.name}") { CueSynth.pcm(cue, t) }
    }

    private fun playAudio(cue: CueSynth.Cue, volume: Float) {
        val samples = pcm(cue)
        if (samples.isEmpty()) return
        // One cue at a time: a new transition retires the previous chirp rather than
        // stacking two of them.
        releaseTrack()

        val id = ++trackId
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build())
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(CueSynth.SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build())
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(samples.size * 2)
            .build()
        t.write(samples, 0, samples.size)
        t.setVolume(volume.coerceIn(0f, 1f))
        t.play()
        track = t
        // Static mode does not release itself; free it once the buffer has played.
        handler.postDelayed({
            if (id == trackId) { track = null; releaseTrack(t) }
        }, CueSynth.durationMs(cue).toLong() + 150L)
    }

    private fun releaseTrack() {
        val t = track
        track = null
        if (t != null) releaseTrack(t)
    }

    private fun releaseTrack(t: AudioTrack) {
        try { t.pause(); t.flush(); t.release() } catch (_: Throwable) {}
    }

    // ---- haptics -------------------------------------------------------------------

    private fun playHaptic(cue: CueSynth.Cue) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        val (timings, amplitudes) = CueSynth.haptic(cue)
        if (Build.VERSION.SDK_INT >= 26) {
            val effect = VibrationEffect.createWaveform(timings, amplitudes, -1)
            if (Build.VERSION.SDK_INT >= 33) {
                // Label it as TOUCH feedback: the cue is a UI signal, so it follows the
                // same intensity/feedback settings as a tap instead of pretending to be
                // a notification (which DND and the ringer would treat differently).
                v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH))
            } else {
                v.vibrate(effect)
            }
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(timings, -1)
        }
    }

    companion object {
        /**
         * The being's voice is audible right now: every cue is suppressed until it
         * stops. Set from MainActivity's frame tick (the `voicing` witness), read by
         * every player instance so a Settings preview respects it too.
         */
        @Volatile private var voiceActive = false

        fun setVoiceActive(on: Boolean) { voiceActive = on }
    }
}
