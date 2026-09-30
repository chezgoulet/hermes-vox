package com.hermesvox

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/**
 * ModelDownloads — the process-wide model download queue, owned by [ModelDownloadService].
 *
 * Downloads used to live inside the Models screen: leaving it mid-download failed the transfer,
 * and "Download all" started every model at once on one shared downloader (one Cancel stopped
 * them all). Now the queue belongs to the process, a foreground service keeps the process alive
 * while it runs (with a progress notification), and screens only OBSERVE it: leave the Models
 * screen, lock the phone, come back — the download is still going, and the card shows where it
 * is.
 *
 * One model at a time, in the order asked for: a phone's link is not faster for splitting it,
 * and a 2.6 GB Gemma racing an 80 MB voice just delays the voice. Cancel pauses: the partial
 * stays on disk and the next Download resumes it (DownloadResume).
 */
object ModelDownloads {

    /** What one model's download is doing, for the UI. */
    sealed class State {
        object Idle : State()
        object Queued : State()
        data class Running(val phase: ModelDownloader.Phase, val done: Long, val total: Long) : State()
        /** Stopped with bytes kept on disk; [done] of [total] (total may be unknown: -1). */
        data class Paused(val done: Long, val total: Long, val reason: String?) : State()
        object Installed : State()
    }

    fun interface Observer { fun onState(id: String, state: State) }

    private val main = Handler(Looper.getMainLooper())
    private val observers = CopyOnWriteArrayList<Observer>()
    private val states = HashMap<String, State>()
    private val queue = ArrayDeque<ModelSpec>()
    @Volatile private var current: ModelSpec? = null
    @Volatile private var cancelCurrent = false
    private var worker: Thread? = null
    private var engine: ModelDownloader? = null
    private val lock = Any()

    /** Observers are called on the main thread. Register in onStart, remove in onStop. */
    fun observe(o: Observer) { observers.add(o) }
    fun unobserve(o: Observer) { observers.remove(o) }

    /** The model's state now, including a paused partial left on disk by an earlier session. */
    fun state(context: Context, spec: ModelSpec): State = synchronized(lock) {
        states[spec.id]?.let { return it }
        if (ModelCatalog.isInstalled(context, spec.id)) return State.Installed
        val have = engineFor(context).partialBytes(spec)
        return if (have > 0) State.Paused(have, (spec.sizeMB * 1048576).toLong(), null) else State.Idle
    }

    /** True while anything is queued or running (the service's lifetime). */
    fun busy(): Boolean = synchronized(lock) { current != null || queue.isNotEmpty() }

    /** The model currently transferring, for the notification. */
    fun running(): Pair<ModelSpec, State>? = synchronized(lock) {
        val c = current ?: return null
        c to (states[c.id] ?: State.Queued)
    }

    /** Queue [spec] (no-op if installed, queued or running) and make sure the service runs. */
    fun enqueue(context: Context, spec: ModelSpec) {
        synchronized(lock) {
            if (ModelCatalog.isInstalled(context, spec.id)) { set(spec.id, State.Installed); return }
            if (current?.id == spec.id || queue.any { it.id == spec.id }) return
            queue.addLast(spec)
            set(spec.id, State.Queued)
        }
        ModelDownloadService.start(context)
    }

    /** Pause [id]: stop it if running (its bytes are kept), or take it out of the queue. */
    fun cancel(context: Context, id: String) {
        synchronized(lock) {
            if (current?.id == id) { cancelCurrent = true; return }
            val spec = queue.firstOrNull { it.id == id } ?: return
            queue.remove(spec)
            val have = engineFor(context).partialBytes(spec)
            set(id, if (have > 0) State.Paused(have, (spec.sizeMB * 1048576).toLong(), null) else State.Idle)
        }
    }

    /** Called by the service: run the queue on one worker until it is empty. */
    internal fun drain(context: Context, onEmpty: () -> Unit, onProgress: () -> Unit) {
        synchronized(lock) {
            if (worker?.isAlive == true) return
            worker = Thread({
                while (true) {
                    val spec = synchronized(lock) {
                        val next = queue.removeFirstOrNull()
                        current = next; cancelCurrent = false
                        next
                    } ?: break
                    runOne(context, spec, onProgress)
                }
                synchronized(lock) { current = null }
                main.post(onEmpty)
            }, "model-downloads").apply { isDaemon = false; start() }
        }
    }

    private fun runOne(context: Context, spec: ModelSpec, onProgress: () -> Unit) {
        val eng = engineFor(context)
        VoxLog.d("event=model-dl-start id=${spec.id} resumeFrom=${eng.partialBytes(spec)}")
        var lastUi = 0L
        val result = try {
            eng.run(spec, { cancelCurrent }) { phase, done, total ->
                val now = System.currentTimeMillis()
                if (phase != ModelDownloader.Phase.DOWNLOAD || now - lastUi > 400) {
                    lastUi = now
                    set(spec.id, State.Running(phase, done, total))
                    onProgress()
                }
            }
        } catch (e: Throwable) { e.message ?: "download failed" }
        VoxLog.d("event=model-dl-end id=${spec.id} result=${result ?: "installed"}")
        synchronized(lock) {
            if (result == null) set(spec.id, State.Installed)
            else {
                val have = eng.partialBytes(spec)
                set(spec.id, State.Paused(have, (spec.sizeMB * 1048576).toLong(), if (result == "cancelled") null else result))
            }
            current = null
        }
        onProgress()
    }

    private fun set(id: String, s: State) {
        synchronized(lock) { states[id] = s }
        main.post { for (o in observers) o.onState(id, s) }
    }

    private fun engineFor(context: Context): ModelDownloader =
        engine ?: ModelDownloader(context).also { engine = it }
}
