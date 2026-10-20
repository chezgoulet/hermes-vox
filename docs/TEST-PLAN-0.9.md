# Hermes Vox 0.9 — device test pass

What to put in front of a phone before 0.9 is cut. Ordered by risk, not by feature:
the upgrade path first, because that is the only thing every existing user will hit
within a minute of installing.

**What this list is not.** CI already covers the gate, the stream-parity fixtures, the
unit suite and the reproducible APK (two clean builds, byte-compared). Do not re-test
those by hand. F-Droid (#165) is its own track and does not block the APK.

**Which mode you are testing (D13).** Realtime is the floor: it has to hear you, answer,
stop when you speak, and survive an upgrade. Test it for those and nothing more.
Enhanced Realtime is where the product is going — length, register, turn-taking,
presence, delivery — so spend the polish checks there. If a *basic* fails in Realtime,
that is a blocker; if a *polish* feels rough in Realtime, it is out of scope by decision.

---

## 0. Before you start

- [ ] Build: `testing` at 0.9.0 / versionCode 143 (the bump landed for this pass).
- [ ] Installing it: the **release-signed APK from the nightly release**, over your
      installed 0.8.1, without uninstalling. A debug or locally built APK will refuse
      to install over a release-signed app — that is the signature, not a bug.
- [ ] The phone is on the tailnet (`tun0` up, the gateway reachable). A tailnet ICMP
      ping failing means nothing — Tailscale drops it by design.
- [ ] A Bluetooth headset is paired. The route was field-confirmed on 0.8.1, so this is
      a regression check now, not a first verification.
- [ ] Budget: ~45 minutes, including one continuous 20-minute call.

Run `adb logcat -c` before each block and keep the log; almost every check below names
the line that proves it.

---

## 1. The upgrade — every existing user's path (highest risk)

- [ ] **1.1 Install over 0.8.1.** No uninstall.
      *Pass:* installs; settings, models and session state survive; nothing re-downloads.
- [ ] **1.2 The what's-new banner.** *Pass:* appears exactly once, on the first launch
      after the upgrade; carries the HTTPS alert; tapping it lands on Entity &
      Connection; force-stop and relaunch → gone; a fresh install → never shown.
- [ ] **1.3 The old endpoint.** Point at `http://192.168.1.11:8642`.
      *Pass:* refused in well under a second, and **the user sees** copy naming the
      cause and the fix. ⚠️ #182: the streaming path may fail to produce or show it —
      either way, record what you actually see.
- [ ] **1.4 The documented fix.** On the gateway: `tailscale serve --bg 8080`, enter
      `https://<machine>.<tailnet>.ts.net`. *Pass:* a turn succeeds.
- [ ] **1.5 The exemption is narrow.** `http://<machine>.<tailnet>.ts.net:8642` is
      allowed by the network security config. *Pass:* it works — proving the break hits
      LAN IPs and public hosts only, and the tailnet fallback survived.
- [ ] **1.6 Session continuity.** The conversation from before the upgrade still
      resolves; no "unknown previous_response_id".

---

## 2. The new core (Kotlin) — the wire, not the parser

The Go core is gone. Parity is proven by fixtures; only a live gateway proves the wire.

- [ ] **2.1 Plain turn** — *Pass:* reply streams; `event=turn … outcome=` is not
      `reply-error`; `fullReply` non-zero.
- [ ] **2.2 Tool-using turn** — *Pass:* tool progress renders on the being **and the
      answer is spoken** (a 0.9 fix: a turn that used a tool now speaks its answer).
- [ ] **2.3 Cancel and barge** — *Pass:* audio stops at once, the run is cancelled, and
      the words you spoke become the next turn (`event=barge-carry`).
- [ ] **2.4 A slow turn (30 s+)** — *Pass:* no timeout, no phantom `done=true`.
- [ ] **2.5 A dropped stream** — kill Wi-Fi mid-reply. *Pass:* the assembled text is
      delivered rather than lost, and the app recovers without a restart.
- [ ] **2.6 Wrong key** — *Pass:* an auth message, not a hang and not a silent empty
      reply. (The Settings test now agrees with the live path — #183.)
- [ ] **2.7 `/compress`** — *Pass:* the command sheet and the typed form agree.
- [ ] **2.8 Five turns** — *Pass:* context kept, `session_turns` climbs, no fork.

---

## 3. Audio

- [ ] **3.1 The cue matrix (D7).** ringer normal / vibrate / silent, DND on, media
      volume 0, system haptics off, wired headphones, Bluetooth.
      *Pass:* policy followed; haptic cue on vibrate; silent stays silent unless opted
      in; **never while TTS is speaking**.
      *Log:* `event=cue cue=… reason=… ringer=… dnd=… vol=… level=… haptics=…`
- [ ] **3.2 Preview and level** in Settings → Sound cues.
- [ ] **3.3 Headset route (#149) — regression.** Cover the phone's mic and talk.
      *Pass:* `event=comm-route action=set ok=true type=…`, transcript comes from the
      headset, `action=cleared` on hang-up. Also: attach mid-call; try A2DP-only.
- [ ] **3.4 The voice is Supertonic** — `SherpaTts loaded: supertonic speakers=10`.
- [ ] **3.5 Barge floor in a real room** — TV on, someone else talking.

---

## 4. Enhanced Realtime / Gemma

- [ ] **4.1** `GemmaExpress loaded: … backend=gpu` — `cpu` means the fallback ran.
- [ ] **4.2** The soul opens the call, in its own voice, from its own VOX.md.
- [ ] **4.3** The beat lands fast; when the soul answered, the mind goes quiet.
- [ ] **4.4** `express-probe … warm=`, `soul(answer= … escalate= … nothing= …)`.

---

## 5. Lifecycle and endurance

- [ ] **5.1** Screen off during a call — the line survives.
- [ ] **5.2** Backgrounded — the call continues; the foreground notification is present.
- [ ] **5.3 A 20-minute continuous call.** *Pass:* no thermal throttle inside the first
      ten minutes, RSS stable, no dropped turn. This is also the missing half of #179:
      RTF under 1.0 with VAD + TTS + presence resident, `MemAvailable` never below 1 GB,
      presence first token under 500 ms.
- [ ] **5.4** Hang up — route cleared, mic indicator out, service stopped.

---

## 6. Accessibility and layout

- [ ] **6.1** Onboarding at Font size = Largest and Display size one step up (#130).
- [ ] **6.2** Light theme — no invisible text.
- [ ] **6.3** TalkBack — the being, status, buttons and the crawl announce something.
- [ ] **6.4** Keyboard — the connect form's button is not buried by the IME.

---

## 7. Open questions to settle while the phone is in your hand

- [ ] **#182** — does the guided cleartext message reach the screen on a *microphone*
      turn, or only in theory?
- [ ] **Spoken or not?** A typed turn comes back `outcome=text-only` and is not voiced.
      Is that intended for typed input, or a regression?
- [ ] **#179 co-residency** — the numbers in 5.3.
- [ ] **Thermal** — the only existing measurement is ≤3.3 minutes per cell.

---

## 8. Record the result

For anything that fails, capture: the block number, what you did, the log lines, and
whether it was a microphone turn or harness-driven. A failure with the log is a bug
fix; a failure without it is a conversation.
