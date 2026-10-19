#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""PROTOTYPE ONLY: brings Chrome's remote-debugging socket to the Googlebook's own loopback, for the app menu bar's
Tab menu (net/ChromeTabsLink.kt).

Chrome lists its tabs and switches between them on the abstract socket `chrome_devtools_remote`, which only the
shell may open. Run this in the Googlebook's Linux terminal with adb connected to the Googlebook itself (wireless
debugging): it forwards Chrome's socket to port 9222 here and relays 0.0.0.0:39222 to it. The terminal app shows
ports that listen in Linux on Android's 127.0.0.1, so BentoBar reaches Chrome at 127.0.0.1:39222.

    python3 tools/chrome-tabs-relay.py            (ADB_SERIAL=host:port picks the device; Ctrl+C stops it)
"""
import os
import socket
import subprocess
import threading

RELAY_PORT = 39222
FORWARD_PORT = 9222


def pipe(a, b):
    try:
        while data := a.recv(65536):
            b.sendall(data)
    except OSError:
        pass
    finally:
        for s in (a, b):
            try:
                s.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass


def main():
    serial = os.environ.get("ADB_SERIAL")
    adb = ["adb"] + (["-s", serial] if serial else [])
    subprocess.run(adb + ["forward", f"tcp:{FORWARD_PORT}", "localabstract:chrome_devtools_remote"], check=True)
    server = socket.socket()
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind(("0.0.0.0", RELAY_PORT))
    server.listen(16)
    print(f"relaying :{RELAY_PORT} to Chrome's debugging socket (adb forward tcp:{FORWARD_PORT})", flush=True)
    while True:
        client, _ = server.accept()
        try:
            upstream = socket.create_connection(("127.0.0.1", FORWARD_PORT))
        except OSError:
            client.close()
            continue
        threading.Thread(target=pipe, args=(client, upstream), daemon=True).start()
        threading.Thread(target=pipe, args=(upstream, client), daemon=True).start()


if __name__ == "__main__":
    main()
