#!/usr/bin/env python3
"""A gateway that accepts the connection and never answers (#226 captures).

This exists for ONE reason: the commands screen's *running* and *failed* states have
to be produced by something that really takes time and really fails, not by a flag in
the app. `scripts/capture-commands.sh` points the app at this port over `adb reverse`
and taps `/reconnect`; the app opens a TLS connection, the gateway never replies, and
`GatewayClient.TIMEOUT_MS` (20 s) expires — a genuine long-running command that then
genuinely fails.

It speaks no protocol on purpose. Answering with a fake /v1/health would make the
capture a mock of a gateway rather than a measurement of the app's own timeout, and
the app refuses cleartext to anything but a tailnet name anyway (see
res/xml/network_security_config.xml), so a localhost HTTP stub could not serve it.

    python3 scripts/commands-slow-gateway.py            # 127.0.0.1:8642
    PORT=8643 python3 scripts/commands-slow-gateway.py
"""
import os
import socket
import sys
import time

PORT = int(os.environ.get("PORT", "8642"))
HOLD_S = float(os.environ.get("HOLD_S", "120"))


def serve():
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("127.0.0.1", PORT))
    srv.listen(16)
    sys.stderr.write("slow-gateway: listening on 127.0.0.1:%d (holds %.0fs)\n" % (PORT, HOLD_S))
    held = []
    while True:
        conn, addr = srv.accept()
        held.append(conn)          # keep it open: accept, then say nothing
        sys.stderr.write("slow-gateway: accepted %s, holding\n" % (addr[0],))
        time.sleep(0)              # let the accept loop keep running


if __name__ == "__main__":
    try:
        serve()
    except KeyboardInterrupt:
        pass
