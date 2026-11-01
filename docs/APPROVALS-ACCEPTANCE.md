# Approvals on the phone — acceptance script (#282)

Two ways to exercise the surface. **Neither touches the Pixel's release install or its
app data**: use the emulator, or a build with a distinct `applicationIdSuffix`. The
commands below use the emulator (`emulator-5560` on sasquatch).

The app-side proof is the log line

```
event=approval decision=deny choice=deny run=<run_id> request=<request_id> cause=touch
```

and, from the gateway side, `POST /v1/runs/<run_id>/approval` with the same ids.

## A. Scripted gateway (works today; no gateway credentials)

The real `/v1/responses` writer on Hermes 0.22.5 does not yet emit `approval.request`
(the upstream hook in `docs/NOTES-api-events.md`). This harness emits the same event and
records the answer, so the phone half is testable now.

```bash
# 1. Build and install the debug APK on the emulator (never the Pixel).
cd android && ./gradlew assembleDebug
adb -s emulator-5560 install -r app/build/outputs/apk/debug/hermes-vox-0.9.1.apk

# 2. Start the scripted gateway on the host (the phone reaches it over the tailnet).
cd .. && python3 scripts/approval-harness.py --port 8765 --key harness-key \
  --choices once,deny

# 3. In another shell, drive a turn through the app's debug intent harness.
adb -s emulator-5560 logcat -c
adb -s emulator-5560 shell am force-stop com.hermesvox
adb -s emulator-5560 shell am start -n com.hermesvox/.MainActivity \
  --es url "http://sasquatch.leopard-vibes.ts.net:8765" \
  --es key harness-key --es model hermes-agent \
  --es text "please run the flagged action"

# 4. The card shows the action + exactly --choices. Tap NO (or say "no" during a call).
#    Expect the harness to print:
#      event=approval-http ... choice=deny count=1
#    and logcat to print:
adb -s emulator-5560 logcat -d -s HermesVox:* | grep event=approval
#      event=approval decision=deny choice=deny run=... request=... cause=touch
#    The run then continues and completes (the harness sends output_text + completed).
```

Observed on 2026-11-01 (emulator, subset `once,deny`): the card rendered only `ONCE`
and `NO`; a double tap produced **one** POST (`count=1`); the run completed with
`Approval answered: deny. The run continued.` A temporary short timeout produced
`event=approval decision=timeout choice=deny ... cause=timeout`, one POST, and the same
completion.

## B. Real gateway (the acceptance session)

Once the gateway's `/v1/responses` stream emits `approval.request` (or if your gateway
already does), point the phone at that gateway and trigger a flagged action:

1. Start a call (or a text turn) and send:
   `Run the shell command: rm -rf /tmp/vox-approval-probe`
   (a harmless temp path that Hermes flags as a recursive delete).
2. The call must stop reading as “thinking”: the line says what is being approved and
   why, with exactly the choices the event offered.
3. Say **“no”** (hands-free) or tap **NO**. Then confirm:
   - the run continues and reports the refusal rather than stalling;
   - `adb logcat -s HermesVox:* | grep event=approval` shows
     `event=approval decision=deny choice=deny run=… request=… cause=voice|touch`;
   - the gateway's run status leaves `waiting_for_approval`.
4. Repeat once and say **“approve”** (or tap ONCE) — the least-privilege yes is sent;
   the run continues.
5. Leave a request unanswered: after the window the phone logs
   `event=approval decision=timeout choice=deny … cause=timeout`, posts `deny`, and the
   transcript keeps `! Approval timed out — denied: …`.

`POST /v1/runs/{run_id}/approval` must be observed exactly once per `request_id`; a
duplicate tap or a second voice match is a local no-op.
