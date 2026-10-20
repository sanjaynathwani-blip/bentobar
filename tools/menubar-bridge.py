#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""PROTOTYPE ONLY: what the app menu bar needs from the shell, brought to the Googlebook's own loopback.

Run this in the Googlebook's Linux terminal with adb connected to the Googlebook itself (wireless debugging). The
terminal app shows ports that listen in Linux on Android's 127.0.0.1, so BentoBar reaches both of these there:

- :39222, Chrome's remote-debugging socket (the Tab menu: its tabs, and switching between them). Only the shell may
  open it: this forwards it with adb (to port 9222 here) and relays to it.
- :39223, an app's published quick actions (the Actions menu): GET /shortcuts?pkg=PACKAGE answers with its
  manifest and dynamic shortcuts as JSON, from `cmd shortcut get-shortcuts`, which only the shell and the home
  screen may read.

    python3 tools/menubar-bridge.py            (ADB_SERIAL=host:port picks the device; Ctrl+C stops it)

WARNING: while this runs, EVERY app on the Googlebook can reach both ports, not only BentoBar. The terminal shows a
port on Android's loopback only when it listens on all addresses here, so they can't be bound more narrowly. On
:39222 any app gets Chrome's full DevTools: every tab's address, and running JavaScript in any tab, which can read
its cookies. On :39223 any app can list any app's quick actions. Run it only to try the prototype, and stop it
(Ctrl+C) when done. Only debug builds of BentoBar connect to it; a release build never does.
"""
import json
import os
import re
import socket
import subprocess
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

CHROME_PORT = 39222
SHORTCUTS_PORT = 39223
FORWARD_PORT = 9222
ADB = ["adb"] + (["-s", os.environ["ADB_SERIAL"]] if os.environ.get("ADB_SERIAL") else [])

# ShortcutInfo's flags (frameworks/base ShortcutInfo.java): what the launcher's right-click menu shows.
FLAG_DYNAMIC, FLAG_MANIFEST, FLAG_DISABLED = 0x1, 0x20, 0x40


def shell(*args):
    return subprocess.run(ADB + ["shell", *args], capture_output=True, text=True, timeout=10).stdout


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


def relay_chrome():
    subprocess.run(ADB + ["forward", f"tcp:{FORWARD_PORT}", "localabstract:chrome_devtools_remote"], check=True)
    server = socket.socket()
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind(("0.0.0.0", CHROME_PORT))
    server.listen(16)
    while True:
        client, _ = server.accept()
        try:
            upstream = socket.create_connection(("127.0.0.1", FORWARD_PORT))
        except OSError:
            client.close()
            continue
        threading.Thread(target=pipe, args=(client, upstream), daemon=True).start()
        threading.Thread(target=pipe, args=(upstream, client), daemon=True).start()


def parse_intent(text):
    """The last intent of a shortcut, from Intent.toString() plus its persisted extras ("/null" or PersistableBundle)."""
    # A shortcut's back stack lists several intents: start at the last "Intent {" so its fields aren't the first one's.
    m = re.search(r"Intent \{ (.*?) \}/(null|PersistableBundle\[\{(.*?)\}\])\]?$", text[text.rfind("Intent {"):])
    if not m:
        return None
    body, extras = m.group(1), m.group(3)
    out = {}
    for key, name in (("act", "action"), ("dat", "data"), ("typ", "type"), ("pkg", "package"), ("flg", "flags")):
        if found := re.search(rf"(?:^| ){key}=(\S+)", body):
            out[name] = found.group(1)
    if found := re.search(r"(?:^| )cmp=(\S+)", body):
        pkg, cls = found.group(1).split("/", 1)
        out["component"] = [pkg, pkg + cls if cls.startswith(".") else cls]
    if found := re.search(r"(?:^| )cat=\[(.*?)\]", body):
        out["categories"] = [c.strip() for c in found.group(1).split(",")]
    if extras:
        out["extras"] = dict(kv.split("=", 1) for kv in re.split(r", (?=\w+=)", extras) if "=" in kv)
    return out


def shortcuts(pkg, user):
    """The shortcuts a launcher shows for [pkg]: manifest and dynamic, enabled ones, manifest first, each by rank."""
    found = []
    for block in shell("cmd", "shortcut", "get-shortcuts", "--user", user, "--flags", "15", pkg).split("ShortcutInfo {")[1:]:
        flags = int(re.search(r"flags=0x([0-9a-f]+)", block).group(1), 16)
        if not flags & (FLAG_DYNAMIC | FLAG_MANIFEST) or flags & FLAG_DISABLED:
            continue
        label = re.search(r"shortLabel=(.*?), resId=", block)
        intents = re.search(r"intents=\[(.*)", block)
        icon = re.search(r"iconRes=(\d+)", block)
        found.append({
            "id": re.match(r"id=(.*?), flags=", block).group(1),
            "label": label.group(1) if label else "",
            "kind": "manifest" if flags & FLAG_MANIFEST else "dynamic",
            "rank": int(re.search(r"rank=(\d+)", block).group(1)),
            "iconRes": int(icon.group(1)) if icon else 0,
            "intent": parse_intent(intents.group(1)) if intents else None,
        })
    return sorted(found, key=lambda s: (s["kind"] != "manifest", s["rank"]))


class Shortcuts(BaseHTTPRequestHandler):
    user = "0"

    def do_GET(self):
        url = urlparse(self.path)
        pkg = parse_qs(url.query).get("pkg", [""])[0]
        if url.path != "/shortcuts" or not re.fullmatch(r"[A-Za-z0-9_.]+", pkg):
            self.send_error(404)
            return
        body = json.dumps(shortcuts(pkg, self.user)).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass


def main():
    Shortcuts.user = shell("am", "get-current-user").strip() or "0"
    threading.Thread(target=relay_chrome, daemon=True).start()
    print(f"Chrome's tabs on :{CHROME_PORT}, quick actions on :{SHORTCUTS_PORT} (user {Shortcuts.user})", flush=True)
    ThreadingHTTPServer(("0.0.0.0", SHORTCUTS_PORT), Shortcuts).serve_forever()


if __name__ == "__main__":
    main()
