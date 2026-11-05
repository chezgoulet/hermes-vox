#!/usr/bin/env python3
"""approval-harness.py — a scripted gateway for #282/#376 (approvals on the phone).

The real Hermes API server raises `approval.request` when a flagged tool call needs
sign-off, on TWO surfaces, and the difference matters:

    POST /v1/responses              **does not carry approvals** on gateway 0.22.6
                                    (no approval notifier is registered for that route)
    POST /v1/runs + GET /v1/runs/{id}  the run parks in `waiting_for_approval` with the
                                    request in `pending_controls` (session authority) or
                                    under `approval` (plain lane)

This harness is the deterministic stand-in used to prove the PHONE half, in the shape
of both lanes, so the app's arm can be exercised without a gateway turn at all:

    POST /v1/responses          SSE: response.created -> approval.request
                                     -> (waits for the answer) -> output_text.delta
                                     -> response.completed
    POST /v1/runs               202 {"run_id"}; after --arm-delay the run reports
                                waiting_for_approval with one pending approval
    GET  /v1/runs/{id}          the status projection the app's approval arm polls
    POST /v1/runs/{id}/approval reads {"choice", "request_id"}, logs it, releases
                                the waiting run/status
    GET  /v1/capabilities       advertises approval_events / run_approval_response
    GET  /v1/models, /v1/health, /api/model/options

It is deliberately tiny, stdlib-only, and prints one line per decision, so a field
report can quote it without a screenshot. Run it on the gateway host (or a laptop the
phone can reach), point the app at it, and either send any text turn (the SSE lane) or
use the debug-only approval arm (`--es arm approval`, the runs lane).

    python3 scripts/approval-harness.py --port 8765
    # phone/emulator endpoint: http://<host>.<tailnet>.ts.net:8765
    # key: harness-key  (or pass --key "" to disable auth)

The app's debug build has an intent harness, so no UI tapping is needed to START a
turn or the arm:

    adb shell am start -n com.hermesvox/.DebugHarnessActivity \
        --es url "http://sasquatch.leopard-vibes.ts.net:8765" \
        --es key "harness-key" --es text "please run the flagged action"

    adb shell am start -n com.hermesvox/.DebugHarnessActivity --es arm approval

Then answer on the phone (tap NO or say "no") and watch this process print:

    event=arm-run run_id=... prompt=...
    event=approval-request run_id=... request_id=... choices=once,deny
    event=approval-http run_id=... request_id=... choice=deny
"""
from __future__ import annotations

import argparse
import json
import ssl
import sys
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import unquote, urlparse

RUNS: dict[str, dict] = {}
RUNS_LOCK = threading.Lock()


def log(line: str) -> None:
    print(f"{time.strftime('%H:%M:%S')} {line}", flush=True)


class State:
    key = "harness-key"
    choices: list[str] = ["once", "session", "deny"]
    command = "rm -rf /tmp/vox-approval-probe"
    description = "recursive delete in a temporary folder"
    reason = "the command can remove files"
    approval_wait_s = 330.0
    counts: dict[str, int] = {}
    # The runs lane (#376): how long the scripted "agent" works before it parks on the
    # approval, and which of the gateway's two wire shapes it reports.
    arm_delay_s = 1.5
    runs_shape = "authority"   # authority | plain


def authorized(handler: BaseHTTPRequestHandler) -> bool:
    if not State.key:
        return True
    got = handler.headers.get("Authorization", "")
    return got == f"Bearer {State.key}"


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.0"   # close-delimited SSE body; no chunk framing to fake

    def log_message(self, *_args):  # silence the default access log
        pass

    # ---- helpers ---------------------------------------------------------

    def _body(self) -> bytes:
        n = int(self.headers.get("Content-Length", "0") or 0)
        return self.rfile.read(n) if n else b""

    def _json(self, code: int, obj: dict) -> None:
        raw = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def _sse(self, name: str, obj: dict) -> None:
        payload = json.dumps(obj)
        self.wfile.write(f"event: {name}\ndata: {payload}\n\n".encode())
        self.wfile.flush()

    # ---- routes ----------------------------------------------------------

    def do_GET(self) -> None:  # noqa: N802 (BaseHTTPRequestHandler API)
        path = urlparse(self.path).path
        if path == "/v1/health":
            return self._json(200, {"status": "ok", "platform": "approval-harness", "version": "1"})
        if path == "/v1/models":
            return self._json(200, {"object": "list", "data": [
                {"id": "hermes-agent", "object": "model", "created": 0, "owned_by": "hermes"}]})
        if path == "/api/model/options":
            return self._json(200, {"providers": [{
                "slug": "hermes", "name": "Hermes", "models": ["hermes-agent"],
                "total_models": 1, "is_current": True, "authenticated": True,
                "source": "approval-harness", "capabilities": [], "warning": None}]})
        if path == "/v1/capabilities":
            return self._json(200, {
                "object": "hermes.api_server.capabilities",
                "features": {"approval_events": True, "run_approval_response": True,
                             "run_status": True, "run_events_sse": True,
                             "tool_progress_events": True},
                "endpoints": {
                    "responses": {"method": "POST", "path": "/v1/responses"},
                    "runs": {"method": "POST", "path": "/v1/runs"},
                    "run_status": {"method": "GET", "path": "/v1/runs/{run_id}"},
                    "run_approval": {"method": "POST", "path": "/v1/runs/{run_id}/approval"},
                },
            })
        if path.startswith("/v1/runs/") and not path.endswith("/approval"):
            return self._runs_status(unquote(path.split("/")[3]))
        self._json(404, {"error": {"message": f"not found: {path}"}})

    def do_POST(self) -> None:  # noqa: N802
        path = urlparse(self.path).path
        if path == "/v1/responses":
            return self._responses()
        if path == "/v1/runs":
            return self._runs_create()
        if path.startswith("/v1/runs/") and path.endswith("/approval"):
            return self._approval(unquote(path.split("/")[3]))
        self._json(404, {"error": {"message": f"not found: {path}"}})

    # ---- the runs lane (#376): the shape the app's approval arm polls ---------

    def _runs_create(self) -> None:
        """POST /v1/runs -> 202 {run_id}. The scripted agent parks on the approval
        after --arm-delay seconds, exactly as a real one does after its tool call."""
        if not authorized(self):
            return self._json(401, {"error": {"message": "bad key"}})
        try:
            body = json.loads(self._body() or b"{}")
        except ValueError:
            return self._json(400, {"error": {"message": "invalid JSON"}})
        prompt = str(body.get("input", "") or "")
        run_id = f"run_harness_{uuid.uuid4().hex[:10]}"
        request_id = f"req_{uuid.uuid4().hex[:10]}"
        generation = 1
        with RUNS_LOCK:
            RUNS[run_id] = {
                "request_id": request_id, "event": threading.Event(), "choice": None,
                "choices": list(State.choices), "generation": generation, "lane": "runs",
                "ready_at": time.time() + State.arm_delay_s, "announced": False,
            }
        log(f"event=arm-run run_id={run_id} request_id={request_id} "
            f"prompt={prompt[:140]!r}")
        self._json(202, {"run_id": run_id, "status": "started"})

    def _runs_status(self, run_id: str) -> None:
        """GET /v1/runs/{id} — the projection the arm reads. `pending_controls` is the
        session-authority shape; `approval` is the plain runs lane's."""
        if not authorized(self):
            return self._json(401, {"error": {"message": "bad key"}})
        with RUNS_LOCK:
            run = RUNS.get(run_id)
            if run is None or run.get("lane") != "runs":
                return self._json(404, {"error": {"message": f"unknown run: {run_id}"}})
            ready = time.time() >= run["ready_at"]
            if run["choice"] is None:
                status = "waiting_for_approval" if ready else "running"
            else:
                status = "completed"
            if status == "waiting_for_approval" and not run["announced"]:
                run["announced"] = True
                log(f"event=approval-request run_id={run_id} request_id={run['request_id']} "
                    f"choices={','.join(run['choices'])} shape={State.runs_shape}")
            payload: dict = {
                "object": "hermes.run", "run_id": run_id, "status": status,
                "execution_generation": run["generation"],
                "pending_controls": [],
                "output": "" if run["choice"] is None
                          else f"Approval answered: {run['choice']}. The run continued.",
            }
            if status == "waiting_for_approval":
                if State.runs_shape == "authority":
                    payload["pending_controls"] = [{
                        "kind": "approval", "prompt_id": run["request_id"],
                        "execution_generation": run["generation"],
                        "command": State.command, "description": State.description,
                        "reason": State.reason, "choices": list(run["choices"]),
                    }]
                else:
                    payload["approval"] = {
                        "event": "approval.request", "run_id": run_id,
                        "command": State.command, "description": State.description,
                        "reason": State.reason, "choices": list(run["choices"]),
                        "timestamp": time.time(),
                    }
        self._json(200, payload)

    def _responses(self) -> None:
        if not authorized(self):
            return self._json(401, {"error": {"message": "bad key"}})
        try:
            body = json.loads(self._body() or b"{}")
        except ValueError:
            return self._json(400, {"error": {"message": "invalid JSON"}})
        if body.get("stream") is not True:
            return self._json(400, {"error": {"message": "this harness only streams"}})

        run_id = f"resp_harness_{uuid.uuid4().hex[:10]}"
        request_id = f"req_{uuid.uuid4().hex[:10]}"
        answered = threading.Event()
        with RUNS_LOCK:
            RUNS[run_id] = {
                "request_id": request_id, "event": answered, "choice": None,
                "choices": list(State.choices),
            }

        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        self.end_headers()
        self._sse("response.created", {"type": "response.created", "response": {
            "id": run_id, "status": "in_progress"}})
        self._sse("approval.request", {
            "event": "approval.request", "run_id": run_id, "request_id": request_id,
            "command": State.command, "description": State.description,
            "reason": State.reason, "choices": list(State.choices),
            "timestamp": time.time(),
        })
        log(f"event=approval-request run_id={run_id} request_id={request_id} "
            f"choices={','.join(State.choices)} command={State.command!r}")

        if not answered.wait(State.approval_wait_s):
            log(f"event=approval-timeout run_id={run_id} request_id={request_id} "
                f"waited_s={State.approval_wait_s}")
            choice = "timeout"
        else:
            with RUNS_LOCK:
                choice = RUNS.get(run_id, {}).get("choice") or "unknown"

        # The run continues after an answer (or reports the timeout) — the phone must
        # see the same turn finish, not a stalled stream.
        text = (f"Approval answered: {choice}. The run continued." if choice != "timeout"
                else "Approval timed out.")
        self._sse("response.output_text.delta", {
            "type": "response.output_text.delta", "item_id": f"msg_{run_id}",
            "delta": text,
        })
        self._sse("response.completed", {
            "type": "response.completed",
            "response": {"id": run_id, "status": "completed"},
        })
        self.wfile.write(b"data: [DONE]\n\n")
        self.wfile.flush()
        with RUNS_LOCK:
            RUNS.pop(run_id, None)
        log(f"event=run-completed run_id={run_id} choice={choice}")

    def _approval(self, run_id: str) -> None:
        if not authorized(self):
            return self._json(401, {"error": {"message": "bad key"}})
        try:
            body = json.loads(self._body() or b"{}")
        except ValueError:
            return self._json(400, {"error": {"message": "invalid JSON"}})
        choice = str(body.get("choice", ""))
        request_id = str(body.get("request_id", "") or "")
        with RUNS_LOCK:
            run = RUNS.get(run_id)
            if run is None:
                return self._json(409, {"error": {"message": "run has no pending approval", "code": "approval_not_pending"}})
            if request_id and request_id != run["request_id"]:
                log(f"event=approval-mismatch run_id={run_id} sent={request_id} expected={run['request_id']}")
                return self._json(409, {"error": {"message": "request_id does not match"}})
            if run.get("lane") == "runs" and run["choice"] is not None:
                # The request was already resolved: the real gateway answers 409 here, so a
                # second POST is a visible defect, not a second decision.
                log(f"event=approval-duplicate run_id={run_id} request_id={request_id or '-'} "
                    f"choice={choice} status=409 (the app must suppress this locally)")
                return self._json(409, {"error": {"message": "run has no pending approval",
                                                  "code": "approval_not_pending"}})
            run["choice"] = choice
            run["event"].set()
            count = State.counts.get(run_id, 0) + 1
            State.counts[run_id] = count
        log(f"event=approval-http run_id={run_id} request_id={request_id or '-'} "
            f"choice={choice} count={count}")
        if count > 1:
            log(f"event=approval-duplicate run_id={run_id} request_id={request_id or '-'} "
                f"count={count} (the app must suppress this locally)")
        if run.get("lane") == "runs":
            # The runs lane keeps the record: GET /v1/runs/{id} has to keep answering, and
            # a replay of the same request_id must stay a 409/duplicate, not a fresh run.
            log(f"event=run-completed run_id={run_id} choice={choice}")
        self._json(200, {"object": "hermes.run.approval_response", "run_id": run_id,
                         "choice": choice, "request_id": request_id, "resolved": 1})


def main() -> int:
    ap = argparse.ArgumentParser(description="Scripted approval gateway for Hermes Vox #282/#376.")
    ap.add_argument("--host", default="0.0.0.0")
    ap.add_argument("--port", type=int, default=8765)
    ap.add_argument("--key", default="harness-key", help="bearer key ('' disables auth)")
    ap.add_argument("--choices", default="once,session,deny",
                    help="comma-separated gateway choices (deny is always honoured)")
    ap.add_argument("--command", default=State.command)
    ap.add_argument("--description", default=State.description)
    ap.add_argument("--reason", default=State.reason)
    ap.add_argument("--approval-wait", type=float, default=State.approval_wait_s,
                    help="seconds the run waits for the phone before reporting a timeout")
    # #376: the runs lane, which the app's debug-only approval arm polls.
    ap.add_argument("--arm-delay", type=float, default=State.arm_delay_s,
                    help="seconds a scripted /v1/runs run 'works' before parking on the approval")
    ap.add_argument("--runs-shape", choices=("authority", "plain"), default=State.runs_shape,
                    help="pending_controls (session authority) or approval (plain runs lane)")
    ap.add_argument("--tls-cert", default="")
    ap.add_argument("--tls-key", default="")
    args = ap.parse_args()

    State.key = args.key
    State.choices = [c.strip() for c in args.choices.split(",") if c.strip()]
    if "deny" not in State.choices:
        State.choices.append("deny")
    State.command, State.description = args.command, args.description
    State.reason = args.reason
    State.approval_wait_s = args.approval_wait
    State.arm_delay_s = args.arm_delay
    State.runs_shape = args.runs_shape

    server = ThreadingHTTPServer((args.host, args.port), Handler)
    server.daemon_threads = True
    if args.tls_cert and args.tls_key:
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        ctx.load_cert_chain(args.tls_cert, args.tls_key)
        server.socket = ctx.wrap_socket(server.socket, server_side=True)
        scheme = "https"
    else:
        scheme = "http"
    log(f"event=harness-ready url={scheme}://{args.host}:{args.port} "
        f"key={'set' if State.key else 'none'} choices={','.join(State.choices)} "
        f"runs_shape={State.runs_shape} arm_delay_s={State.arm_delay_s}")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
