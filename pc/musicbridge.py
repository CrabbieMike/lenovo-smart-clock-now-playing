#!/usr/bin/env python3
"""PC side of Music Clock: exposes the current MPRIS player over HTTP for the clock."""
import json
import socket
import subprocess
import threading
import time
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, HTTPServer

import dbus

PORT, DISCOVERY_PORT = 8765, 8766
bus = dbus.SessionBus()
last = None  # bus name of the player we last used, so pausing doesn't switch players
anchors = {}  # player -> (title, reported pos, when): some players (BitChord) only update Position on pause


def player():
    """The playing MPRIS player, else the last one used, else the first one."""
    global last
    names = [n for n in bus.list_names() if n.startswith("org.mpris.MediaPlayer2.")]
    if not names:
        return None
    playing = [n for n in names if prop(n, "PlaybackStatus") == "Playing"]
    if playing:
        # Firefox's own entry reports Position 0 and no length; prefer a duplicate (e.g. plasma-browser-integration) that tracks it.
        last = max(playing, key=lambda n: int(prop(n, "Position") or 0) > 0)
        return last
    return last if last in names else names[0]


def prop(name, key):
    try:
        return bus.get_object(name, "/org/mpris/MediaPlayer2").Get(
            "org.mpris.MediaPlayer2.Player", key, dbus_interface="org.freedesktop.DBus.Properties")
    except dbus.DBusException:
        return None


def state():
    n = player()
    meta = prop(n, "Metadata") if n else None
    if not meta:
        return {"none": True}
    artist = meta.get("xesam:artist") or []
    title, playing = str(meta.get("xesam:title", "")), prop(n, "PlaybackStatus") == "Playing"
    length, pos = int(meta.get("mpris:length", 0)) / 1e6, int(prop(n, "Position") or 0) / 1e6
    a, now = anchors.get(n), time.monotonic()
    if playing and a and a[:2] == (title, pos):
        pos += now - a[2]  # reported value hasn't moved: count forward ourselves
        if length:
            pos = min(pos, length)
    else:
        anchors[n] = (title, pos, now)
    return {
        "player": n.split(".", 3)[-1],
        "title": title,
        "artist": ", ".join(map(str, artist)),
        "playing": playing,
        "pos": pos,
        "len": length,
        "art": str(meta.get("mpris:artUrl", "")),  # the URL itself doubles as the change token
    }


def art():
    n = player()
    url = str((prop(n, "Metadata") or {}).get("mpris:artUrl", "")) if n else ""
    if not url.startswith(("file://", "http://", "https://")):
        return b""
    try:
        with urllib.request.urlopen(url, timeout=3) as r:
            return r.read()
    except Exception:
        return b""


class Handler(BaseHTTPRequestHandler):
    def reply(self, body, ctype="application/json"):
        self.send_response(200)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path == "/state":
            self.reply(json.dumps(state()).encode())
        elif self.path == "/art":
            self.reply(art(), "image/jpeg")
        else:
            self.send_error(404)

    def do_POST(self):
        url = urllib.parse.urlsplit(self.path)
        if url.path in ("/volume_up", "/volume_down"):
            # System volume rather than MPRIS Volume, which browsers don't implement.
            step = "5%+" if url.path == "/volume_up" else "5%-"
            subprocess.run(["wpctl", "set-volume", "-l", "1.0", "@DEFAULT_AUDIO_SINK@", step])
            return self.reply(b"{}")
        method = {"/play_pause": "PlayPause", "/next": "Next", "/previous": "Previous",
                  "/seek": "SetPosition"}.get(url.path)
        if not method:
            return self.send_error(404)
        n = player()
        if n:
            try:
                args = ()
                if method == "SetPosition":
                    meta = prop(n, "Metadata")
                    f = min(max(float(urllib.parse.parse_qs(url.query)["f"][0]), 0.0), 1.0)
                    args = (meta["mpris:trackid"], dbus.Int64(int(f * int(meta["mpris:length"]))))
                getattr(bus.get_object(n, "/org/mpris/MediaPlayer2"), method)(
                    *args, dbus_interface="org.mpris.MediaPlayer2.Player")
            except (dbus.DBusException, KeyError, ValueError):
                pass
        self.reply(b"{}")

    def log_message(self, *a):
        pass


def discovery():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.bind(("", DISCOVERY_PORT))
    reply = ("musicbridge " + socket.gethostname()).encode()
    while True:
        data, addr = s.recvfrom(64)
        if data == b"musicbridge?":
            s.sendto(reply, addr)


if __name__ == "__main__":
    threading.Thread(target=discovery, daemon=True).start()
    print(f"Music bridge on port {PORT} (discovery on UDP {DISCOVERY_PORT})")
    HTTPServer(("", PORT), Handler).serve_forever()
