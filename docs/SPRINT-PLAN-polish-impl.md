# Sprint 6 — Polish / UX — Implementation Plan (byte-precise)

**Repo:** `chezgoulet/hermes-vox` · **Branch:** `feature/polish` · **Language:** Kotlin (Android) + Go (gomobile `mobile` package). **No source files are edited by this plan — it is a build spec.**

Ground rules reused throughout: secrets stay in `SecureStore`/`SharedPreferences` (never echo the key); auth for every HTTP call is `Authorization: Bearer <stored key>` against the same `<entity url>` the session already holds; one coherent commit per workstream; `assembleRelease testReleaseUnitTest` green after each.

---

## Key architectural facts discovered

- The chat/slash "voice without a call" path is `MainActivity.send()` → `VoiceController.sendText()` → `runStreamedTurn()` → `settleReply()` → `speak()`. Nothing checks `callLive`.
- `callLive` is **private** to `MainActivity` (`MainActivity.kt:132`). `VoiceController` cannot see it today.
- The slash-command dispatcher is `MainActivity.showCommands()` (`MainActivity.kt:476-493`). Currently ships `/clear /reset /status /models /help`, and for everything except clear/reset it does `send(cmd)` → the bug.
- The gateway catalog/health/model-lock endpoints are **not yet implemented** in the Go layer (`voice/`, `mobile/`). They must be added (WS4b). `/api/model/options`, `/v1/health`, `POST /api/sessions/{id}/model`, `DELETE /v1/responses/{id}`.
- Settings is a single `SettingsActivity` bound to one flat `activity_settings.xml`, with a central `restoreDefaults(group)` helper already present (`SettingsActivity.kt:351-375`) and per-group reset rows already bound (`SettingsActivity.kt:106-109`, `230-233`).

---

## WS1 — Settings rework (nested sub-views) ⭐

### Approach (lowest-risk, preserves all existing `findViewById` bindings)

Keep it a **single Activity** (`SettingsActivity`) inflating a **single** `activity_settings.xml`, but restructure that XML into a **scrollable group list** plus N **section containers** that are shown/hidden by visibility. All existing row id bindings remain intact because every id still exists in the inflated layout. Navigation is signaled by a `section` field + `showSection()`; the header back button becomes "back to group list" when in a section.

List of group rows (the new flat main screen) — in this order:

| Group row (new id) | Label | Target |
|---|---|---|
| `row_grp_models` | **Models — voice engines (needed, offline)** | sec_models (promote: **WS2**, sits directly under Entity/Mode) |
| `row_grp_entity` | Entity & Connection | sec_entity |
| `row_grp_speech` | Speech & Mic | sec_speech |
| `row_grp_stt` | STT & Transcription | sec_stt |
| `row_grp_tts` | TTS & Voice | sec_tts |
| `row_grp_appearance` | Appearance & Presence | sec_appearance |
| `row_grp_about` | About & Diagnostics | sec_about |

### Which existing rows move where

- **sec_entity / Entity & Connection:** `row_test_conn`, `row_entity`, `row_reset`, `row_mode`, `row_mode_reset` (the Mode restore row), **new** `row_restore_entity`.
- **sec_speech / Speech & Mic:** `row_bargein`, `row_mic_aec`, `row_mic_ns`, `row_mic_partial`, `row_mic_vad`, `row_mic_silence`, `row_mic_early`, `row_mic_min_speech`, `row_mic_max`, `row_mic_reset` (GROUP_MIC restore). *(Barge-in is a mic/listen behavior → Speech & Mic.)*
- **sec_stt / STT & Transcription:** `row_stt`, `row_stt_model`, `row_stt_reset` (GROUP_STT).
- **sec_tts / TTS & Voice:** `row_tts`, `row_tts_reset`, `row_voice`, `row_voice_reset`, `row_speak` (Speak responses toggle → now lives with voice). *(GROUP_TTS now also restores the voice register.)*
- **sec_models / Models:** `row_models`, `set_models_val` (launches `ModelsActivity`; see WS2).
- **sec_appearance / Appearance & Presence:** `row_theme`, `row_layout`, `row_particle_theme`, `row_particle_cycle`.
- **sec_about / About & Diagnostics:** version row (`set_about_val`), `row_debug`, `row_devconsole`, `row_logtranscripts`.

Every section keeps the flat rows' **exact existing ids and handlers** — the only code change is *which container a row lives in* + a **new** `row_restore_<section>` row per section wired to `restoreDefaults()`.

### Layout changes (`res/layout/activity_settings.xml`)

1. Give the header `<TextView>` an id so the title can change per section: `android:id="@+id/set_title"` (and remove its hardcoded `android:text="Settings"`).
2. Wrap everything currently inside the `<ScrollView>` in a frame of two containers:
   - `grp_list` (`LinearLayout`, block containing the 7 `row_grp_*` above only).
   - One container per group, e.g. `sec_entity`, `sec_speech`, `sec_stt`, `sec_tts`, `sec_models`, `sec_appearance`, `sec_about` (each `LinearLayout`, `android:visibility="gone"`), containing that group's re-parented rows + a `row_restore_*` row at the bottom.
3. Reuse the existing row templates exactly: a group row is an `HvRow` with an `HvRowTitle` + `HvRowValue` (or `HvRowAction` `"open ›"`); a restore row is an `HvRow` with `HvRowTitle` `"Restore defaults (<group>)"` + `HvRowAction` `"reset"`.
4. Top-of-list promo stays `row_test_conn` **inside** sec_entity (WS3 changes its handler text, not its place).

### Activity changes (`SettingsActivity.kt`)

```kotlin
// new fields
private var currentSection: String? = null
companion object {
    const val SECTION_ENTITY="entity"; const val SECTION_SPEECH="speech"
    const val SECTION_STT="stt"; const val SECTION_TTS="tts"
    const val SECTION_MODELS="models"; const val SECTION_APPEARANCE="appearance"
    const val SECTION_ABOUT="about"
}
```

`showSection(section)` signal helper (called from each `row_grp_*` onClick):

```kotlin
private fun showSection(section: String) {
    currentSection = section
    findViewById<android.view.View>(R.id.grp_list).visibility = android.view.View.GONE
    val ids = mapOf(
        SECTION_ENTITY to R.id.sec_entity, SECTION_SPEECH to R.id.sec_speech,
        SECTION_STT to R.id.sec_stt, SECTION_TTS to R.id.sec_tts,
        SECTION_MODELS to R.id.sec_models, SECTION_APPEARANCE to R.id.sec_appearance,
        SECTION_ABOUT to R.id.sec_about)
    ids.values.forEach { findViewById<android.view.View>(it).visibility = android.view.View.GONE }
    val v = findViewById<android.view.View>(ids[section]!!)
    v.visibility = android.view.View.VISIBLE
    (findViewById<android.widget.ScrollView>(R.id.settings_scroll)).scrollTo(0, 0)
    findViewById<TextView>(R.id.set_title).text = when (section) {
        SECTION_ENTITY -> "Entity & Connection"; SECTION_SPEECH -> "Speech & Mic"
        SECTION_STT -> "STT & Transcription"; SECTION_TTS -> "TTS & Voice"
        SECTION_MODELS -> "Voice models"; SECTION_APPEARANCE -> "Appearance & Presence"
        else -> "About & Diagnostics"
    }
}
```

Wire the group rows in `onCreate` (after `bindParticles()`), and give the back button section-aware behavior:

```kotlin
findViewById<android.view.View>(R.id.settings_back).setOnClickListener {
    if (currentSection != null) { showGroupList() } else { finish(); overridePendingTransition(R.anim.fade_in, R.anim.fade_out) }
}
private fun showGroupList() {
    currentSection = null
    findViewById<android.view.View>(R.id.grp_list).visibility = android.view.View.VISIBLE
    val ids = listOf(R.id.sec_entity,R.id.sec_speech,R.id.sec_stt,R.id.sec_tts,
        R.id.sec_models,R.id.sec_appearance,R.id.sec_about)
    ids.forEach { findViewById<android.view.View>(it).visibility = android.view.View.GONE }
    findViewById<TextView>(R.id.set_title).text = "Settings"
}
```

### restoreDefaults(group) wiring (`SettingsActivity.kt`)

Add cases to the existing `restoreDefaults(group)` (`:351-375`) so each sub-view restores on its `row_restore_*` (each bound via a new `bindRestoreRow(R.id.row_restore_<sec>, <GROUP>, "<label>")` in `onCreate`):

```kotlin
GROUP_ENTITY -> e.putString("model", "hermes-agent")
    .putString(ModelCatalog.KEY_VOICE_MODE, ModelCatalog.MODE_REALTIME)   // url/key untouched (identity)
GROUP_MIC   -> (unchanged: the existing VAD + toggles block, :354-362)
GROUP_STT   -> (unchanged: :363-365)
GROUP_TTS   -> e.putString("tts", "system").putString("voice", "system")  // now also resets the register
GROUP_APPEARANCE -> e.putString("theme", "system").putString("layout_mode", "presence")
    .putString("particles_theme", "aura").putBoolean("particles_cycle", true)
GROUP_ABOUT -> e.putBoolean("dev_console", false).putBoolean("log_transcripts", false)
GROUP_MODELS -> e.putString(ModelCatalog.KEY_SOURCE, ModelCatalog.DEFAULT_SOURCE)  // source only; files untouched
```

Then in the re-sync `when(group)` tail (`:371-374`) keep `GROUP_MIC -> bindMicSettings()` and `else -> refreshFlowVals()`, and add `GROUP_APPEARANCE -> bindParticles()`. **No behavior change at defaults** — every default value matches the shipped value.

---

## WS2 — Models download view prominence

Two edits.

1. **Settings order (`activity_settings.xml`):** `row_grp_models` is placed as the **first** group row in `grp_list` (immediately under the header / Entity-Mode area). Move the existing `ModelsActivity` launch (`SettingsActivity.kt:122-127`, bound to `row_models`) into `sec_models`, and make its group-row subtitle reflect need: `set_models_val` already renders `"$installed/${ModelCatalog.blessed.size} installed · on-device, offline"` — change label to `"$installed/${ModelCatalog.blessed.size} installed · needed for your voice"`.

2. **ModelsActivity prominence (`ModelsActivity.kt` + `activity_models.xml`):** make the *required* set explicit and the flow obvious.
   - Add a subtitle `TextView` under the header: `"These 3 power your offline voice: Silero VAD, Piper, Whisper base.en"` (derive from `ModelCatalog.blessed.filter { it.recommended }`).
   - Reorder `blessed`-driven cards so `recommended` cards render first: in `ModelsActivity.buildCards()`, iterate `ModelCatalog.blessed.sortedBy { if (it.recommended) 0 else 1 }` (instead of raw order) — keeps the "needed now" models on top.
   - Keep the existing per-card progress/state/download/installed logic untouched (already correct).
   - The `m_download_all` button already downloads `recommended` set; change its text to `"Install the required voice models"` for clarity (behavior unchanged).

---

## WS3 — Connection test plain language

Edit the handler in `SettingsActivity.kt:165-174` and add a human-readable result in `VoiceController`.

**`VoiceController.kt`** — add `testConnectionHuman()` (keep `testConnection()` as-is for the raw log): 

```kotlin
/** Plain-language connection test (WS3): success/failure copy + the raw reason
 *  in small debug text. Same entity url + bearer key as testConnection(). */
fun testConnectionHuman(): String {
    val u = prefString("url", ""); val k = prefString("key", "")
    if (u.isBlank()) return "Couldn't reach the gateway. Check that your network is on and the address is right.\n\n(no endpoint set)"
    var ping = true; var pingRe = ""
    try {
        val c = java.net.URL(u.trimEnd('/') + "/v1/models").openConnection() as java.net.HttpURLConnection
        c.requestMethod = "GET"; c.connectTimeout = 8000; c.readTimeout = 8000
        c.setRequestProperty("Authorization", "Bearer " + k)
        if (c.responseCode != 200) { ping = false; pingRe = "HTTP ${c.responseCode}" }
    } catch (e: Throwable) { ping = false; pingRe = e.message ?: "unknown" }
    var stream = true; var streamRe = ""
    try {
        val c = java.net.URL(u.trimEnd('/') + "/v1/responses").openConnection() as java.net.HttpURLConnection
        c.requestMethod = "POST"; c.connectTimeout = 8000; c.readTimeout = 8000; c.doOutput = true
        c.setRequestProperty("Authorization", "Bearer " + k)
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write("{\"model\":\"\",\"input\":\"hello\",\"stream\":true}".toByteArray()) }
        val code = c.responseCode
        if (code < 200 || code >= 400) { stream = false; streamRe = "HTTP $code" }
    } catch (e: Throwable) { stream = false; streamRe = e.message ?: "unknown" }
    VoxLog.d("conn-test: ping=$ping($pingRe) stream=$stream($streamRe)")
    val ok = ping && stream
    if (ok) return "Connected to your agent. Everything's working."
    val debug = listOfNotNull(if (!ping) "ping: $pingRe" else null, if (!stream) "stream: $streamRe" else null).joinToString(" · ")
    return "Couldn't reach the gateway. Check that your network is on and the address is right.\n\n(debug: $debug)"
}
```

**`SettingsActivity.kt:165-174`** — replace the handler body:

```kotlin
findViewById<android.view.View>(R.id.row_test_conn)?.setOnClickListener {
    val c = com.hermesvox.VoiceController(this, com.hermesvox.mobile.HermesSession(
        prefs.getString("url","").orEmpty(),
        SecureStore.decrypt(prefs.getString("key","").orEmpty()).orEmpty(), ""))
    val msg = c.testConnectionHuman()
    findViewById<TextView>(R.id.set_test_val)?.let {
        val ok = msg.startsWith("Connected")
        it.text = if (ok) "ok" else "FAILED"
        it.setTextColor(if (ok) 0xFF35D07F.toInt() else 0xFFFF5B5B.toInt())
    }
    androidx.appcompat.app.AlertDialog.Builder(this)
        .setTitle(if (msg.startsWith("Connected")) "Connection OK" else "Connection issue")
        .setMessage(msg)
        .setPositiveButton("OK", null).show()
}
```

---

## WS4 — Actions actually work

### 4a. The SPEAK GATE (fixes the 14:52 slash-command-speaks bug)

Add a voice-channel gate to `VoiceController` and drive it from the host. Default **false** = a reply with no open voice channel is **text-only**.

**`VoiceController.kt`**:

```kotlin
// NEW state + setter (near the other @Volatile flags, ~line 57)
/** Speak gate (WS4a): voice a reply only when a call / voice channel is open.
 *  Set by the host: MainActivity -> == callLive; RealtimeActivity -> true. */
@Volatile private var voiceChannelOpen = false
fun setVoiceChannelOpen(open: Boolean) { voiceChannelOpen = open }
private fun shouldSpeak(): Boolean = voiceChannelOpen && speakEnabled()
```

Gate the **three** speak entry points:

1. `:415` `if (speakEnabled() && tts?.supportsStreaming == true) streamBegin()` → `if (shouldSpeak() && tts?.supportsStreaming == true) streamBegin()`
2. `:522` `} else if (speakEnabled()) {` → `} else if (shouldSpeak()) {`
3. `:653` `speakGlue()` `if (!speakEnabled()) return` → `if (!shouldSpeak()) return`

With the gate closed, `streamBegin` never runs (`streamed=false`), so `settleReply`'s `if (streamed && …)` is false and `else if (shouldSpeak())` is false → the `else` branch (`:524-525`) does `stopStreaming(); onState("idle"); releaseTurnGate()` → **text-only**. No audio is generated.

**Drive the flag:**

- `MainActivity.startCall()` after `callLive = true` (`:201`): `liveController?.setVoiceChannelOpen(true)` (or set on the `c` built at `:173`).
- `MainActivity.talk()` (Walkie PTT): after `c.start(...)` (`:414`) → `c.setVoiceChannelOpen(true)`.
- `MainActivity.resumeLiveCallIfAny()` after `callLive = true` (`:249`): `c.setVoiceChannelOpen(true)`.
- `MainActivity.endCall()` (`:209`): before `liveController?.stop()` → `liveController?.setVoiceChannelOpen(false)`.
- `MainActivity.resetActiveConversation()` (`:322`): `liveController?.setVoiceChannelOpen(false)`.
- `MainActivity.onStop()` when `!callLive` (`:614`): `liveController?.setVoiceChannelOpen(false)`.
- `RealtimeActivity.startVoice()` after `c.start(...)` (`:61`): `c.setVoiceChannelOpen(true)` (the Realtime screen is always a live voice channel).

### 4b. Slash commands as NATIVE mini-UIs

#### Step 1 — add gateway clients (Go)

New file **`voice/gateway.go`** (same `baseURL` + `apiKey` auth as the responses client):

```go
package voice

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"time"
)

// HermesGatewayClient talks to the Hermes gateway's model/health/session
// endpoints that the app's on-device catalog CANNOT provide: /api/model/options
// (the full provider/model catalog), /v1/health, and the session model lock.
// Same baseURL + bearer key as HermesResponsesClient.
type HermesGatewayClient struct {
	baseURL string
	apiKey  string
	http    *http.Client
}

func NewHermesGatewayClient(baseURL, apiKey string) *HermesGatewayClient {
	return &HermesGatewayClient{baseURL: baseURL, apiKey: apiKey,
		http: &http.Client{Timeout: 20 * time.Second}}
}

func (g *HermesGatewayClient) get(path string) (string, int, error) {
	req, err := http.NewRequest(http.MethodGet, g.baseURL+path, nil)
	if err != nil { return "", 0, err }
	if g.apiKey != "" { req.Header.Set("Authorization", "Bearer "+g.apiKey) }
	resp, err := g.http.Do(req)
	if err != nil { return "", 0, err }
	defer resp.Body.Close()
	b, _ := io.ReadAll(resp.Body)
	return string(b), resp.StatusCode, nil
}

// ModelOptions returns the raw /api/model/options JSON
// ({providers:[{slug,name,models[],total_models,is_current,authenticated,source,capabilities,warning}]}).
func (g *HermesGatewayClient) ModelOptions() (string, error) {
	b, code, err := g.get("/api/model/options")
	if err != nil { return "", err }
	if code != 200 { return "", fmt.Errorf("hermes model options %d", code) }
	return b, nil
}

// Health returns the raw /v1/health JSON (status/version).
func (g *HermesGatewayClient) Health() (string, error) {
	b, code, err := g.get("/v1/health")
	if err != nil { return "", err }
	if code != 200 { return "", fmt.Errorf("hermes health %d", code) }
	return b, nil
}

// SetSessionModel locks the session's inference backend (the same path the
// dashboard/Browser picker uses). Body is the gateway's expected model lock.
func (g *HermesGatewayClient) SetSessionModel(sessionID, model string) error {
	body, _ := json.Marshal(map[string]any{"model": model})
	req, err := http.NewRequest(http.MethodPost, g.baseURL+"/api/sessions/"+sessionID+"/model", bytes.NewReader(body))
	if err != nil { return err }
	req.Header.Set("Content-Type", "application/json")
	if g.apiKey != "" { req.Header.Set("Authorization", "Bearer "+g.apiKey) }
	resp, err := g.http.Do(req)
	if err != nil { return err }
	defer resp.Body.Close()
	if resp.StatusCode != 200 && resp.StatusCode != 201 && resp.StatusCode != 202 && resp.StatusCode != 204 {
		b, _ := io.ReadAll(resp.Body)
		return fmt.Errorf("hermes session model %s: %s", resp.Status, string(b))
	}
	return nil
}

// DeleteResponse drops a stored server-side response (the /new session reset).
func (g *HermesGatewayClient) DeleteResponse(id string) error {
	req, err := http.NewRequest(http.MethodDelete, g.baseURL+"/v1/responses/"+id, nil)
	if err != nil { return err }
	if g.apiKey != "" { req.Header.Set("Authorization", "Bearer "+g.apiKey) }
	resp, err := g.http.Do(req)
	if err != nil { return err }
	defer resp.Body.Close()
	if resp.StatusCode != 200 && resp.StatusCode != 204 {
		b, _ := io.ReadAll(resp.Body)
		return fmt.Errorf("hermes delete response %s: %s", resp.Status, string(b))
	}
	return nil
}
```

#### Step 2 — expose via HermesSession (gomobile)

**`mobile/session.go`** — add a `gw` handle + bridge methods (constructed in `NewHermesSession`):

```go
type HermesSession struct {
	conv    *voice.Conversation
	streams *voice.HermesResponsesClient
	runs    *voice.HermesRunClient
	gw      *voice.HermesGatewayClient
	lastID  string
}

// NewHermesSession: add  gw: voice.NewHermesGatewayClient(baseURL, apiKey),
// before the return.

// ModelOptions (string, error)
func (s *HermesSession) ModelOptions() (string, error) {
	if s == nil || s.gw == nil { return "", fmt.Errorf("voice: no Hermes session") }
	return s.gw.ModelOptions()
}

// GatewayHealth (string, error)
func (s *HermesSession) GatewayHealth() (string, error) {
	if s == nil || s.gw == nil { return "", fmt.Errorf("voice: no Hermes session") }
	return s.gw.Health()
}

// SetSessionModel error — session override > channel > global.
func (s *HermesSession) SetSessionModel(sessionID, model string) error {
	if s == nil || s.gw == nil { return fmt.Errorf("voice: no Hermes session") }
	return s.gw.SetSessionModel(sessionID, model)
}

// DeleteResponse error — /new clears the server-side chain.
func (s *HermesSession) DeleteResponse(id string) error {
	if s == nil || s.gw == nil { return fmt.Errorf("voice: no Hermes session") }
	return s.gw.DeleteResponse(id)
}

// CurrentResponseID string — the last chained response id (for /new delete).
func (s *HermesSession) CurrentResponseID() string {
	if s == nil { return "" }
	return s.lastID
}
```

#### Step 3 — rewrite `showCommands()` in `MainActivity.kt:476-493`

```kotlin
private fun showCommands() {
    val cmds = arrayOf("/models", "/health", "/new", "/reconnect", "/clear", "/reset", "/status", "/help")
    androidx.appcompat.app.AlertDialog.Builder(this)
        .setTitle("Commands")
        .setItems(cmds) { _, w ->
            when (cmds[w]) {
                "/models"    -> showModelChooser()
                "/health"    -> showHealthCard()
                "/status"    -> showHealthCard()
                "/new"       -> newSession()
                "/reconnect" -> reconnect()
                "/clear", "/reset" -> {
                    liveController?.stop(); liveController = null
                    session?.resetConversation()
                    input.text.clear(); replyBuf = ""; reply.setText("")
                    avatar.setState("idle"); setStatus(getString(R.string.hv_connected), false)
                }
                else         -> showHelpCard()   // /help
            }
        }
        .show()
}
```

#### Step 4 — the native mini-UI panels

Add to `MainActivity.kt` (runs on the main thread; network on a background `kotlin.concurrent.thread`):

```kotlin
/** /models — native chooser from the gateway catalog (GET /api/model/options).
 *  Sets the session's backend via POST /api/sessions/{id}/model (session override);
 *  the entity's memory/skills/context/SOUL persist (not the entity being swapped). */
private fun showModelChooser() {
    val s = session ?: run { setStatus("Connect first", true); return }
    setStatus("Loading gateway models…", true)
    thread {
        val raw = try { s.modelOptions() } catch (e: Throwable) { null }
        runOnUiThread {
            setStatus(getString(R.string.hv_connected), false)
            if (raw.isNullOrBlank()) { toast("Couldn't reach the model catalog"); return@runOnUiThread }
            val providers = org.json.JSONObject(raw).optJSONArray("providers") ?: return@runOnUiThread
            val labels = mutableListOf<String>(); val ids = mutableListOf<String>()
            for (p in 0 until providers.length()) {
                val prov = providers.optJSONObject(p) ?: continue
                val pname = prov.optString("name").ifEmpty { prov.optString("slug") }
                val cur = prov.optBoolean("is_current", false)
                val auth = prov.optString("source", "")
                val models = prov.optJSONArray("models")
                if (models == null || models.length() == 0) {
                    labels += ((if (cur) "✓ " else "") + pname + " · " + prov.optInt("total_models", 0) + " models")
                    ids += prov.optString("slug")
                } else {
                    for (m in 0 until models.length()) {
                        val mm = models.optJSONObject(m) ?: continue
                        val mLabel = mm.optString("name").ifEmpty { mm.optString("id") }
                        val mId = mm.optString("id").ifEmpty { mm.optString("slug") }
                        labels += ((if (cur) "✓ " else "") + pname + " · " + mLabel + (if (auth.isNotBlank()) " ($auth)" else ""))
                        ids += mId
                    }
                }
            }
            if (labels.isEmpty()) { toast("No models in catalog"); return@runOnUiThread }
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Gateway models (choose your agent's brain)")
                .setSingleChoiceItems(labels.toTypedArray(), 0) { d, which ->
                    d.dismiss()
                    thread {
                        val ok = try {
                            // session_id: the app session's current response id (the gateway's
                            // session handle for this entity); see risk note.
                            s.setSessionModel(session?.currentResponseID().orEmpty(), ids[which]); true
                        } catch (e: Throwable) { false }
                        runOnUiThread {
                            toast(if (ok) "Model set → ${ids[which]}" else "Couldn't set the model")
                            s.resetConversation()   // re-init the session under the new backend
                        }
                    }
                }
                .setNegativeButton("Cancel", null).show()
        }
    }
}

/** /health — native health card (GET /v1/health). */
private fun showHealthCard() {
    val s = session ?: run { setStatus("Connect first", true); return }
    thread {
        val raw = try { s.gatewayHealth() } catch (e: Throwable) { null }
        runOnUiThread {
            val card = if (raw.isNullOrBlank()) "Health: unreachable\n(debug: check network + address)"
                        else try {
                            val o = org.json.JSONObject(raw)
                            "Status: " + o.optString("status", "?") +
                            "\nVersion: " + o.optString("version", o.optString("service_version", "?"))
                        } catch (e: Throwable) { "Health: ok\n$raw" }
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Agent health").setMessage(card).setPositiveButton("OK", null).show()
        }
    }
}

/** /new — reset the app session chain + DELETE /v1/responses/{id} + fresh state. */
private fun newSession() {
    val s = session
    val id = s?.currentResponseID().orEmpty()
    if (id.isNotBlank()) thread { try { s?.deleteResponse(id) } catch (_: Throwable) {} }
    resetActiveConversation()   // stop, clear chain + UI (existing helper)
    setStatus("New session started", true)
}

/** /reconnect — re-ping /v1/health (via gateway) + re-init the session. */
private fun reconnect() {
    setStatus("Reconnecting…", true)
    thread {
        val u = prefs.getString("url", "").orEmpty()
        val k = storedKey(); val m = prefs.getString("model", "hermes-agent").orEmpty()
        val ok = try { com.hermesvox.mobile.HermesSession(u, k, m).gatewayHealth(); true } catch (e: Throwable) { false }
        runOnUiThread {
            resetActiveConversation()
            connectFromPrefs()   // re-init session from prefs
            setStatus(if (ok) "Reconnected" else "Reconnect failed — check network + address", true)
        }
    }
}
```

**Auth note:** every call uses the same `<entity url>` + `Bearer <stored key>` already held by the session (onboarding verified). None of the panels sends `"/models"` as an agent string.

---

## Risks & verification

**Risks**
1. **`session_id` semantics for `POST /api/sessions/{id}/model`.** The plan doc names the endpoint but not what `{session_id}` is. The design uses the session's current `response_id` as the handle (exposed via `CurrentResponseID()`); if the gateway uses a distinct session id this is wrong → **verify against the gateway/dashboard model-picker call before shipping**; the Go method is parameterized so only the Kotlin argument changes.
2. **Exact JSON field names** of `/api/model/options`, `/v1/health`, and the `SetSessionModel` request body. The Kotlin layers parse defensively (`optString`/`optJSONArray` with `ifEmpty`), so a field-name mismatch degrades to an empty/"?" instead of a crash — verify against live responses and tighten after.
3. **Speak gate default = false.** Any newly-opened voice UI must call `setVoiceChannelOpen(true)` explicitly (RealtimeActivity, startCall, talk, resume) or the reply won't speak. The four host call sites above cover it.
4. **`blessed` reorder in `ModelsActivity`** must not re-run `refreshCard` out of the collection lifecycle (keep the same `cards` map keyed by `spec.id`).

**How to verify**
- **WS1/WS2:** open Settings → each group row opens its sub-view; every control still reads/writes the same prefs; `Restore defaults` in each sub-view resets only its group; at defaults the pipeline behavior is unchanged. `assembleRelease testReleaseUnitTest`.
- **WS3:** Settings → Entity & Connection → Test connection → with a valid URL+key you get the green "Connected to your agent. Everything's working."; with a bad URL/key you get the plain failure copy + the small debug reason.
- **WS4a (the bug):** launch app (no call, `callLive=false`), open Commands → `/status` (or any text turn) → the reply renders as **text only**, no `piper generated/played` in the log. Start a call → a reply is now spoken again (no regression).
- **WS4b:** `/models` shows the real gateway provider/model catalog (not `hermes-agent` only); selecting one calls `POST /api/sessions/{id}/model` and the next turn uses the new backend while memory/skills/context persist. `/health` shows the health card. `/new` resets the chain + deletes the stored response. `/reconnect` re-pings and re-inits.
- **No regression:** realtime loop, chaining, barge-in, model downloads all still work; `git log` shows one commit per workstream.

---

## Commit plan

1. `feat(settings): nest settings into sub-views with per-group restore-defaults` (WS1)
2. `feat(settings): promote the models download view + obvious install flow` (WS2)
3. `fix(conn-test): plain-language connection test copy` (WS3)
4. `fix(voice): gate reply speech on an active call (slash-command text-only)` (WS4a)
5. `feat(commands): native /models · /health · /new · /reconnect mini-UIs (gateway API)` (WS4b — includes `voice/gateway.go` + `mobile/session.go`)

**Open item to confirm with the gateway:** the exact `session_id` for the session-model lock and the request body it expects.
