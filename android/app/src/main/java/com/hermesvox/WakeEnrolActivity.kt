package com.hermesvox

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.hermesvox.wake.EnrolmentPrompts
import com.hermesvox.wake.EnrolmentStore
import com.hermesvox.wake.InertWakeWordEngine
import com.hermesvox.wake.KeystoreTakeCipher
import com.hermesvox.wake.TakeKind
import com.hermesvox.wake.TakeMeasure
import com.hermesvox.wake.WakeWordEngine
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * WakeEnrolActivity — records the owner's takes of "Hey Hermes", plus ~15 s of
 * free speech and ~15 s of their room, for the future personal verifier (#45, D6;
 * design in docs/WAKE-WORD.md §3).
 *
 * Foundation only: the mic opens while this screen is visible and a take is being
 * recorded, and closes when the take ends or the screen pauses. No service, no
 * background listening, and nothing leaves the phone. Takes are measured
 * ([TakeMeasure]) before they are kept, sealed with the Keystore, and stored under
 * noBackupFilesDir ([KeystoreTakeCipher.store]).
 */
class WakeEnrolActivity : AppCompatActivity() {
    private val prefs by lazy { getSharedPreferences("hv", Context.MODE_PRIVATE) }
    private val store by lazy { KeystoreTakeCipher.store(this) }
    private val engine: WakeWordEngine = InertWakeWordEngine

    private lateinit var count: TextView
    private lateinit var prompt: TextView
    private lateinit var result: TextView
    private lateinit var record: Button
    private lateinit var promptList: LinearLayout
    private lateinit var why: TextView

    private val steps = EnrolmentPrompts.steps
    /** Index into [steps]. */
    private var selected = 0
    @Volatile private var recording = false
    /** Set when a take in flight must not be kept (screen paused, takes deleted). */
    @Volatile private var discardNext = false
    private var recorder: Thread? = null
    private val busy: Boolean get() = recorder?.isAlive == true

    override fun onCreate(savedInstanceState: Bundle?) {
        if (prefs.getString("theme", "system") == "dark") androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_wake_enrol)
        count = findViewById(R.id.wk_count)
        prompt = findViewById(R.id.wk_prompt)
        result = findViewById(R.id.wk_result)
        record = findViewById(R.id.wk_record)
        promptList = findViewById(R.id.wk_prompts)
        why = findViewById(R.id.wk_why)

        findViewById<android.view.View>(R.id.wk_back).setOnClickListener { finish() }
        findViewById<TextView>(R.id.wk_status).text =
            "Not listening. This preview only records your takes of \"${EnrolmentPrompts.PHRASE}\", " +
            "15 seconds of you talking and 15 seconds of your room, so the wake word can later " +
            "learn your voice. Nothing runs in the background and nothing is sent anywhere. " +
            "(Detector: ${engine.id}.)"
        findViewById<TextView>(R.id.wk_consent_text).text =
            "These recordings — your takes of the phrase, 15 seconds of you talking freely, and " +
            "15 seconds of your room — are recordings of your voice: biometric data. They are " +
            "encrypted with a key held by this phone, kept only in this app's private storage, " +
            "never backed up, never uploaded, and used only for the wake word. You can delete " +
            "them at any time here."
        findViewById<Button>(R.id.wk_consent).setOnClickListener { store.grantConsent(); render() }
        findViewById<Button>(R.id.wk_delete).setOnClickListener { confirmDeleteAll() }
        record.setOnClickListener { if (recording) recording = false else startTake() }

        selected = firstMissing()
        render()
    }

    override fun onPause() {
        super.onPause()
        // The mic never outlives the visible screen; a take cut short is dropped.
        if (recording) { recording = false; discardNext = true }
    }

    private fun firstMissing(): Int = steps.indexOfFirst { !store.has(it) }.coerceAtLeast(0)

    private fun render() {
        val consented = store.hasConsent()
        findViewById<android.view.View>(R.id.wk_consent_box).visibility =
            if (consented) android.view.View.GONE else android.view.View.VISIBLE
        val takes = store.count()
        val speech = store.has(TakeKind.SPEECH)
        val room = store.has(TakeKind.ROOM)
        count.text = "$takes of ${EnrolmentPrompts.takes} takes · free speech ${if (speech) "✓" else "○"}" +
            " · room ${if (room) "✓" else "○"}" +
            when {
                store.verifierMaterialComplete() -> " — enough to personalise"
                !EnrolmentPrompts.isReady(takes) -> " — ${EnrolmentPrompts.MIN_TAKES} takes needed"
                else -> " — record both long takes to finish"
            }
        promptList.removeAllViews()
        steps.forEachIndexed { i, step ->
            promptList.addView(TextView(this).apply {
                this.text = (if (store.has(step)) "✓ " else "○ ") + "${i + 1}. ${step.prompt}"
                textSize = 14f
                setPadding(0, 10, 0, 10)
                setTextColor(ContextCompat.getColor(this@WakeEnrolActivity,
                    if (i == selected) R.color.hv_text else R.color.hv_text_dim))
                setOnClickListener { if (!recording) { selected = i; result.text = ""; render() } }
            })
        }
        val step = steps[selected]
        prompt.text = when (step.kind) {
            TakeKind.PHRASE -> "Take ${selected + 1}: say \"${EnrolmentPrompts.PHRASE}\" — " +
                step.prompt.replaceFirstChar { it.lowercase() }
            TakeKind.SPEECH -> "Free speech: ${step.prompt.replaceFirstChar { it.lowercase() }}"
            TakeKind.ROOM -> "Your room: ${step.prompt.replaceFirstChar { it.lowercase() }}"
        }
        why.text = when (step.kind) {
            TakeKind.PHRASE -> ""
            TakeKind.SPEECH -> EnrolmentPrompts.SPEECH_WHY
            TakeKind.ROOM -> EnrolmentPrompts.ROOM_WHY
        }
        why.visibility = if (step.kind == TakeKind.PHRASE) android.view.View.GONE else android.view.View.VISIBLE
        record.isEnabled = consented
        record.text = when {
            recording -> "Stop"
            store.has(step) -> "Record again"
            else -> "Record"
        }
    }

    private fun startTake() {
        if (!store.hasConsent() || busy) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        val step = steps[selected]
        recording = true
        discardNext = false
        result.text = when (step.kind) {
            TakeKind.PHRASE -> "Listening… tap Stop when you've said it."
            TakeKind.SPEECH -> "Recording… keep talking; it stops by itself at ${step.kind.maxMs / 1000} s."
            TakeKind.ROOM -> "Recording the room… stay quiet; it stops by itself at ${step.kind.maxMs / 1000} s."
        }
        render()
        recorder = Thread({ captureTake(step) }, "wake-enrol").apply { start() }
    }

    /** Off the main thread: capture up to the step's [TakeKind.maxMs], then measure + seal. */
    private fun captureTake(step: EnrolmentPrompts.Step) {
        val sr = WakeWordEngine.SAMPLE_RATE
        val minBuf = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val pcm = ByteArrayOutputStream()
        var failure: String? = null
        try {
            // VOICE_RECOGNITION: the capture a wake listener will use while idle (no
            // TTS to cancel), so the takes match what the detector will hear.
            val r = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, sr,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, 4096))
            if (r.state != AudioRecord.STATE_INITIALIZED) { r.release(); failure = "The microphone is busy." }
            else try {
                val cap = sr * 2 * step.kind.maxMs / 1000
                val buf = ByteArray(maxOf(minBuf, 4096))
                r.startRecording()
                while (recording && pcm.size() < cap) {
                    val n = r.read(buf, 0, minOf(buf.size, cap - pcm.size()))
                    if (n < 0) { failure = "The microphone stopped ($n)."; break }
                    pcm.write(buf, 0, n)
                }
            } finally {
                try { r.stop() } catch (_: Exception) {}
                r.release()
            }
        } catch (e: SecurityException) {
            failure = "Microphone permission is off."
        }
        recording = false
        if (discardNext) { runOnUiThread { result.text = "Take discarded."; render() }; return }
        val bytes = pcm.toByteArray()
        val samples = ShortArray(bytes.size / 2).also {
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(it)
        }
        val saved = when {
            failure != null -> null
            step.kind == TakeKind.PHRASE -> store.save(step.index, samples, sr)
            else -> store.save(step.kind, samples, sr)
        }
        val message = when (saved) {
            null -> failure
            is EnrolmentStore.SaveResult.Saved -> "Kept — ${describe(saved.measure)}"
            is EnrolmentStore.SaveResult.Rejected -> "${saved.reason}. ${describe(saved.measure)}"
            EnrolmentStore.SaveResult.NoConsent -> "Not saved — consent is off."
        }
        runOnUiThread {
            result.text = message
            if (saved is EnrolmentStore.SaveResult.Saved) selected = firstMissing()
            render()
        }
    }

    private fun describe(m: TakeMeasure): String =
        "%.1f s, level %.0f dBFS, peak %.0f dBFS".format(m.durationMs / 1000.0, m.rmsDbfs, m.peakDbfs)

    private fun confirmDeleteAll() {
        if (busy) { result.text = "Finish the take first."; return }
        AlertDialog.Builder(this)
            .setTitle("Delete all recordings?")
            .setMessage("Removes every phrase take, the free-speech and room recordings, your consent, and the key that encrypts them. You can record again later.")
            .setPositiveButton("Delete") { _, _ ->
                store.deleteAll()
                selected = 0
                result.text = "All recordings deleted."
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startTake()
            else result.text = "Recording takes needs the microphone permission."
        }
    }

    companion object {
        private const val REQ_MIC = 45
    }
}
