"""Local Cast V2 fixture receiver. Exercises VLC transport/conversion, not Chromecast decoding.

Pass a scratch directory containing cert.pem, key.pem and fixture.mkv. Forward emulator
tcp:18080 with adb. converted.mkv points to the latest video response for decoded-frame checks.
"""
import http.server
import json
import pathlib
import socket
import ssl
import struct
import sys
import threading
import time
import urllib.request

root = pathlib.Path(sys.argv[1])
(root / "converted.mkv").unlink(missing_ok=True)
state = dict(loads=0, pauses=0, plays=0, bytes=0, videoLoads=0, loadBytes=0)
active_response = None
lock = threading.Lock()


def varint(n):
    result = bytearray()
    while n > 127:
        result.append((n & 127) | 128)
        n >>= 7
    result.append(n)
    return bytes(result)


def field(n, value):
    if isinstance(value, int):
        return varint(n << 3) + varint(value)
    if isinstance(value, str):
        value = value.encode()
    return varint((n << 3) | 2) + varint(len(value)) + value


def unpack(data):
    pos = 0
    fields = {}

    def number():
        nonlocal pos
        n = shift = 0
        while True:
            b = data[pos]
            pos += 1
            n |= (b & 127) << shift
            if b < 128:
                return n
            shift += 7

    while pos < len(data):
        tag = number()
        if tag & 7 == 0:
            value = number()
        else:
            assert tag & 7 == 2
            length = number()
            value = data[pos:pos + length]
            pos += length
        fields[tag >> 3] = value
    return fields


def receive(sock, size):
    data = b""
    while len(data) < size:
        chunk = sock.recv(size - len(data))
        if not chunk:
            raise EOFError()
        data += chunk
    return data


def capture(url, load, save):
    global active_response
    # The emulator's advertised IP is private to its virtual network; adb forwards this port.
    from urllib.parse import urlsplit
    parsed = urlsplit(url)
    url = "http://127.0.0.1:18080" + parsed.path + ("?" + parsed.query if parsed.query else "")
    try:
        with urllib.request.urlopen(url, timeout=30) as response:
            with lock:
                if load != state["loads"]:
                    return
                active_response = response
            output = (root / f"converted-{load}.mkv").open("wb") if save else None
            if save:
                latest = root / "converted.mkv"
                latest.unlink(missing_ok=True)
                latest.symlink_to(f"converted-{load}.mkv")
            try:
                while chunk := response.read(16 * 1024):
                    if output:
                        output.write(chunk)
                        output.flush()
                    with lock:
                        state["bytes"] += len(chunk)
                        if load == state["loads"]:
                            state["loadBytes"] += len(chunk)
            finally:
                if output:
                    output.close()
    except Exception as error:
        print("Capture ended:", type(error).__name__, flush=True)


def cast_connection(sock):
    global active_response
    media = None
    player_state = "IDLE"
    started = time.monotonic()

    def send(namespace, payload, dest="sender-0", binary=False):
        data = (field(1, 0) + field(2, "receiver-0" if "receiver" in namespace else "transport-1")
                + field(3, dest) + field(4, namespace) + field(5, int(binary))
                + field(7 if binary else 6, payload if binary else json.dumps(payload)))
        sock.sendall(struct.pack("!I", len(data)) + data)

    try:
        while True:
            size, = struct.unpack("!I", receive(sock, 4))
            fields = unpack(receive(sock, size))
            namespace = fields[4].decode()
            sender = fields[2].decode()
            if namespace.endswith("deviceauth"):
                # Structurally valid test response. This server is only a local protocol fixture.
                send(namespace, field(2, field(1, b"") + field(2, b"")), sender, True)
                continue
            message = json.loads(fields.get(6, b"{}"))
            kind = message.get("type")
            request_id = message.get("requestId", 0)
            if namespace.endswith("heartbeat"):
                send(namespace, {"type": "PONG"}, sender)
            elif namespace.endswith("receiver"):
                send(namespace, {"type": "RECEIVER_STATUS", "requestId": request_id,
                                 "status": {"applications": [{"appId": "CC1AD845", "transportId": "transport-1",
                                                             "sessionId": "test-session"}]}}, sender)
            elif namespace.endswith("media"):
                if kind == "LOAD":
                    media = message["media"]
                    player_state = "PLAYING"
                    started = time.monotonic()
                    with lock:
                        state["loads"] += 1
                        state["loadBytes"] = 0
                        load = state["loads"]
                        is_video = media.get("contentType", "").startswith("video/")
                        if is_video:
                            state["videoLoads"] += 1
                        save_video = is_video
                        previous = active_response
                        active_response = None
                    # A real receiver abandons the old HTTP request on LOAD. Leaving two readers
                    # attached stole bytes from VLC's single stream and corrupted the fixture output.
                    if previous is not None:
                        try:
                            previous.fp.raw._sock.shutdown(socket.SHUT_RDWR)
                        except (AttributeError, OSError):
                            pass
                    threading.Thread(target=capture, args=(media["contentId"], load, save_video), daemon=True).start()
                elif kind == "PAUSE":
                    player_state = "PAUSED"
                    with lock:
                        state["pauses"] += 1
                elif kind == "PLAY":
                    player_state = "PLAYING"
                    with lock:
                        state["plays"] += 1
                elif kind == "STOP":
                    player_state = "IDLE"
                send(namespace, {"type": "MEDIA_STATUS", "requestId": request_id, "status": [{
                    "mediaSessionId": 1, "playerState": player_state, "currentTime": time.monotonic() - started,
                    "idleReason": "CANCELLED" if player_state == "IDLE" else "", "media": media,
                    "supportedMediaCommands": 15, "volume": {"level": 1.0, "muted": False}}]}, sender)
                print(kind, json.dumps(state), flush=True)
    except (EOFError, OSError):
        pass
    finally:
        sock.close()


class Http(http.server.BaseHTTPRequestHandler):
    def do_HEAD(self):
        self.serve(False)

    def do_GET(self):
        self.serve(True)

    def serve(self, body):
        if self.path == "/status":
            with lock:
                data = json.dumps(state).encode()
            self.send_response(200)
            self.send_header("Content-Length", len(data))
            self.end_headers()
            if body:
                self.wfile.write(data)
            return
        if self.path != "/fixture.mkv" or self.headers.get("X-Cast-Test") != "fixture":
            self.send_error(403)
            return
        data = (root / "fixture.mkv").read_bytes()
        total = len(data)
        requested = self.headers.get("Range")
        start, end = 0, total - 1
        if requested:
            a, b = requested.removeprefix("bytes=").split("-")
            start, end = int(a), int(b) if b else total - 1
        data = data[start:end + 1]
        self.send_response(206 if requested else 200)
        self.send_header("Content-Type", "video/x-matroska")
        self.send_header("Content-Length", len(data))
        self.send_header("Accept-Ranges", "bytes")
        if requested:
            self.send_header("Content-Range", f"bytes {start}-{end}/{total}")
        self.end_headers()
        if body:
            try:
                self.wfile.write(data)
            except (BrokenPipeError, ConnectionResetError):
                pass

    def log_message(self, *args):
        pass


threading.Thread(target=http.server.ThreadingHTTPServer(("127.0.0.1", 18081), Http).serve_forever,
                 daemon=True).start()
context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
context.load_cert_chain(root / "cert.pem", root / "key.pem")
with socket.create_server(("127.0.0.1", 18009)) as server:
    print("Fixture receiver ready", flush=True)
    while True:
        client, _ = server.accept()
        threading.Thread(target=cast_connection, args=(context.wrap_socket(client, server_side=True),), daemon=True).start()
