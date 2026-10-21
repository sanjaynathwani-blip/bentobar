#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""PROTOTYPE ONLY: what the app menu bar needs from the shell, brought to the Googlebook's own loopback.

Run this in the Googlebook's Linux terminal with adb connected to the Googlebook itself (wireless debugging). The
terminal app shows ports that listen in Linux on Android's 127.0.0.1, so BentoBar reaches both of these there:

- :39222, Chrome's remote-debugging socket (the Tabs menu switches tabs there). Only the shell may open it: this
  forwards it with adb (to port 9222 here) and relays to it.
- :39223, this bridge's own answers:
  - GET /tabs, the Tabs menu's list: the tabs in Chrome's own windows that Chrome has loaded, most recently used
    first. Chrome's /json/list also names many tabs no window shows, and the pages of installed web apps, which
    have windows of their own, so this keeps only the page targets Chrome has running whose display mode is
    "browser".
  - GET /shortcuts?pkg=PACKAGE, an app's published quick actions (the Actions menu): its manifest and dynamic
    shortcuts as JSON, from `cmd shortcut get-shortcuts`, which only the shell and the home screen may read.

    python3 tools/menubar-bridge.py            (ADB_SERIAL=host:port picks the device; Ctrl+C stops it)

WARNING: while this runs, EVERY app on the Googlebook can reach both ports, not only BentoBar. The terminal shows a
port on Android's loopback only when it listens on all addresses here, so they can't be bound more narrowly. On
:39222 any app gets Chrome's full DevTools: every tab's address, and running JavaScript in any tab, which can read
its cookies. On :39223 any app can list Chrome's loaded tabs and any app's quick actions. Run it only to try the
prototype, and stop it (Ctrl+C) when done. Only debug builds of BentoBar connect to it; a release build never does.
"""
import base64
import html
import http.client
import json
import os
import re
import socket
import struct
import subprocess
import threading
from concurrent.futures import ThreadPoolExecutor
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

CHROME_PORT = 39222
BRIDGE_PORT = 39223
FORWARD_PORT = 9222
ADB = ["adb"] + (["-s", os.environ["ADB_SERIAL"]] if os.environ.get("ADB_SERIAL") else [])

# ShortcutInfo's flags (frameworks/base ShortcutInfo.java): what the launcher's right-click menu shows.
FLAG_DYNAMIC, FLAG_MANIFEST, FLAG_DISABLED = 0x1, 0x20, 0x40


def shell(*args):
    """The command's output; raises when adb fails (not connected), so a failure never reads as an empty answer."""
    done = subprocess.run(ADB + ["shell", *args], capture_output=True, text=True, timeout=10)
    if done.returncode != 0:
        raise RuntimeError(f"adb shell failed ({done.returncode}): {done.stderr.strip()[:200]}")
    return done.stdout


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


def chrome_get(path):
    """Chrome's answer to an HTTP request on its debugging socket: it answers only a request that names a local host."""
    conn = http.client.HTTPConnection("127.0.0.1", FORWARD_PORT, timeout=5)
    try:
        conn.request("GET", path, headers={"Host": f"localhost:{FORWARD_PORT}"})
        return json.loads(conn.getresponse().read())
    finally:
        conn.close()


def cdp(path, method, params=None, timeout=5.0):
    """One DevTools call on the websocket at [path], its result; raises when Chrome doesn't answer within [timeout]."""
    with socket.create_connection(("127.0.0.1", FORWARD_PORT), timeout=timeout) as s:
        key = base64.b64encode(os.urandom(16)).decode()
        s.sendall((f"GET {path} HTTP/1.1\r\nHost: localhost:{FORWARD_PORT}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                   f"Sec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n\r\n").encode())
        buf = b""
        while b"\r\n\r\n" not in buf:
            if not (data := s.recv(4096)):
                raise OSError("closed")
            buf += data
        head, buf = buf.split(b"\r\n\r\n", 1)
        if b" 101 " not in head.split(b"\r\n")[0]:
            raise OSError(head.split(b"\r\n")[0].decode(errors="replace"))
        # A client's frame is masked: one text frame, final.
        data, mask = json.dumps({"id": 1, "method": method, "params": params or {}}).encode(), os.urandom(4)
        n = len(data)
        size = bytes([0x80 | n]) if n < 126 else bytes([0x80 | 126]) + struct.pack(">H", n) if n < 65536 else bytes([0x80 | 127]) + struct.pack(">Q", n)
        s.sendall(b"\x81" + size + mask + bytes(b ^ mask[i % 4] for i, b in enumerate(data)))

        def take(k):
            nonlocal buf
            while len(buf) < k:
                if not (more := s.recv(65536)):
                    raise OSError("closed")
                buf += more
            out, buf = buf[:k], buf[k:]
            return out

        message = b""
        while True:
            first, second = take(2)
            n = second & 0x7F
            if n == 126:
                n = struct.unpack(">H", take(2))[0]
            elif n == 127:
                n = struct.unpack(">Q", take(8))[0]
            message += take(n)
            if first & 0x80:
                answer = json.loads(message)
                if answer.get("id") == 1:
                    return answer.get("result", {})
                message = b""  # an event, not the answer


def in_browser_window(target_id):
    """False for a page in an installed web app's own window (a TWA), whose display mode isn't "browser". A page that
    doesn't answer (Chrome freezes some tabs in the background) counts as a browser tab."""
    try:
        result = cdp(f"/devtools/page/{target_id}", "Runtime.evaluate",
                     {"expression": 'matchMedia("(display-mode: browser)").matches', "returnByValue": True}, timeout=1.5)
        return result.get("result", {}).get("value") is not False
    except (OSError, ValueError):
        return True


def loaded_tabs():
    """The tabs in Chrome's own windows that Chrome has running, most recently used first, with /json/list's ids
    (the ones /json/activate takes). A tab Chrome hasn't loaded since it started is left out."""
    live = [t for t in cdp("/devtools/browser", "Target.getTargets")["targetInfos"] if t["type"] == "page"]
    with ThreadPoolExecutor(8) as pool:
        tabs = [t for t, ok in zip(live, pool.map(in_browser_window, [t["targetId"] for t in live])) if ok]
    # The two lists' ids differ: match on address and title, each running page once.
    left = {}
    for t in tabs:
        left[(t["url"], t["title"])] = left.get((t["url"], t["title"]), 0) + 1
    found = []
    for t in chrome_get("/json/list"):
        key = (t.get("url", ""), html.unescape(t.get("title", "")))
        if t.get("type") == "page" and left.get(key):
            left[key] -= 1
            found.append({"id": t["id"], "title": key[1], "url": key[0]})
    return found


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


class Bridge(BaseHTTPRequestHandler):
    user = "0"

    def do_GET(self):
        url = urlparse(self.path)
        pkg = parse_qs(url.query).get("pkg", [""])[0]
        if url.path == "/tabs":
            try:
                body = json.dumps(loaded_tabs()).encode()
            except (OSError, ValueError, KeyError) as e:
                # The forward is gone or Chrome isn't running: an error, so the menu says the list isn't reachable.
                print(f"tabs: {e}", flush=True)
                self.send_error(502)
                return
        elif url.path == "/shortcuts" and re.fullmatch(r"[A-Za-z0-9_.]+", pkg):
            try:
                body = json.dumps(shortcuts(pkg, self.user)).encode()
            except (RuntimeError, subprocess.TimeoutExpired) as e:
                # adb dropped: an error, not an empty list, so the app keeps the shortcuts it read itself.
                print(f"shortcuts {pkg}: {e}", flush=True)
                self.send_error(502)
                return
        else:
            self.send_error(404)
            return
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass


def main():
    Bridge.user = shell("am", "get-current-user").strip() or "0"
    threading.Thread(target=relay_chrome, daemon=True).start()
    print(f"Chrome on :{CHROME_PORT}, its loaded tabs and quick actions on :{BRIDGE_PORT} (user {Bridge.user})", flush=True)
    ThreadingHTTPServer(("0.0.0.0", BRIDGE_PORT), Bridge).serve_forever()


if __name__ == "__main__":
    main()
