#!/usr/bin/env python3
# TCP forwarder: listen on 127.0.0.1:8642, forward to the Hermes gateway.
# This lets the Android emulator reach the Odroid gateway THROUGH THE HOST's
# network stack via `adb reverse tcp:8642 tcp:8642` (the emulator connects to its
# own 127.0.0.1:8642, adb forwards it to the host's 127.0.0.1:8642, we forward it
# out to the gateway over the host's route). No guest networking config needed.
import socket, threading, sys

LISTEN = ("127.0.0.1", 8642)
UPSTREAM = ("100.84.47.125", 8642)

def handle(conn, addr):
    upstream = None
    try:
        upstream = socket.create_connection(UPSTREAM, timeout=10)
    except Exception as e:
        sys.stderr.write(f"[proxy] connect {UPSTREAM} failed: {e}\n"); sys.stderr.flush()
        conn.close(); return
    try:
        def pump(src, dst):
            try:
                while True:
                    data = src.recv(65536)
                    if not data: break
                    dst.sendall(data)
            except Exception:
                pass
            finally:
                try: dst.shutdown(socket.SHUT_WR)
                except Exception: pass
        t1 = threading.Thread(target=pump, args=(conn, upstream), daemon=True)
        t2 = threading.Thread(target=pump, args=(upstream, conn), daemon=True)
        t1.start(); t2.start(); t1.join(); t2.join()
    except Exception:
        pass
    finally:
        try: upstream.close()
        except Exception: pass
        try: conn.close()
        except Exception: pass

def main():
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    s.bind(LISTEN)
    s.listen(32)
    sys.stderr.write(f"[proxy] listening on {LISTEN[0]}:{LISTEN[1]} -> {UPSTREAM[0]}:{UPSTREAM[1]}\n"); sys.stderr.flush()
    while True:
        conn, addr = s.accept()
        threading.Thread(target=handle, args=(conn, addr), daemon=True).start()

if __name__ == "__main__":
    main()
