"""Screenshots of the CamLeon browser pages for the README, with the live picture replaced by a
placeholder (the live camera shows whatever is in front of the phone — not for a public repo).

usage:  python docs/tools/screenshots.py http://<phone-ip>:8080 [placeholder.svg|.png] [out-dir]
        defaults: docs/tools/placeholder-court.svg, docs/img

Needs Google Chrome on this PC. Opens every page in headless Chrome (DevTools protocol), waits for it
to settle, swaps the <video> for the placeholder image and captures the page at 2x. The app screenshot
(docs/img/app.png) is taken separately: `adb exec-out screencap -p > app.png` and the placeholder pasted
over the preview area.
"""
import sys, os, json, time, socket, base64, struct, subprocess, threading, functools, urllib.request
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer

CHROME = r"C:\Program Files\Google\Chrome\Application\chrome.exe"
PORT_CHROME = 9400
PAGES = [  # name, path, css width, css height (the control page is long: 1700 shows preview … settings)
    ("remote-landscape", "/remote", 1280, 720),
    ("remote-portrait", "/remote", 500, 1000),
    ("control-page", "/", 820, 1700),
    ("recordings", "/recordings", 820, 245),   # header + two entries
]

class Ws:
    """Minimal WebSocket client for the DevTools protocol (frames up to 2^63 bytes, no masking on receive)."""
    def __init__(self, url):
        host, rest = url[5:].split("/", 1); host, port = host.split(":")
        self.s = socket.create_connection((host, int(port)))
        key = base64.b64encode(os.urandom(16)).decode()
        self.s.send(f"GET /{rest} HTTP/1.1\r\nHost: {host}:{port}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n\r\n".encode())
        buf = b""
        while b"\r\n\r\n" not in buf: buf += self.s.recv(4096)
        self.buf = buf.split(b"\r\n\r\n", 1)[1]; self.id = 0
    def _read(self, n):
        while len(self.buf) < n:
            d = self.s.recv(1 << 20)
            if not d: raise EOFError
            self.buf += d
        out, self.buf = self.buf[:n], self.buf[n:]; return out
    def call(self, method, params=None):
        self.id += 1; mid = self.id
        msg = json.dumps({"id": mid, "method": method, "params": params or {}}).encode()
        n = len(msg); mask = os.urandom(4)
        hdr = bytes([0x81]) + (bytes([0x80 | n]) if n < 126 else bytes([0x80 | 126]) + struct.pack(">H", n) if n < 65536 else bytes([0x80 | 127]) + struct.pack(">Q", n))
        self.s.send(hdr + mask + bytes(b ^ mask[i % 4] for i, b in enumerate(msg)))
        while True:
            data = b""
            while True:
                b0, b1 = self._read(2); n = b1 & 0x7f
                if n == 126: n = struct.unpack(">H", self._read(2))[0]
                elif n == 127: n = struct.unpack(">Q", self._read(8))[0]
                data += self._read(n)
                if b0 & 0x80: break
            m = json.loads(data)
            if m.get("id") == mid: return m.get("result", {})

def chrome(args, profile):
    return subprocess.Popen([CHROME, "--headless=new", f"--user-data-dir={profile}", "--hide-scrollbars",
                             "--autoplay-policy=no-user-gesture-required"] + args, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

def connect(port):
    for _ in range(50):
        try:
            pages = json.load(urllib.request.urlopen(f"http://127.0.0.1:{port}/json"))
            return Ws([x for x in pages if x["type"] == "page"][0]["webSocketDebuggerUrl"])
        except Exception: time.sleep(0.2)
    raise RuntimeError("Chrome did not come up")

def render_placeholder(svg, png, tmp):
    p = chrome(["--window-size=1920,1080", f"--screenshot={png}", "file:///" + os.path.abspath(svg).replace("\\", "/")], os.path.join(tmp, "render"))
    p.wait()

def shoot(base, img_url, out_dir, tmp, name, path, w, h):
    p = chrome([f"--remote-debugging-port={PORT_CHROME}", f"--window-size={w},{h}", "about:blank"], os.path.join(tmp, name))
    try:
        ws = connect(PORT_CHROME)
        ws.call("Emulation.setDeviceMetricsOverride", {"width": w, "height": h, "deviceScaleFactor": 2, "mobile": False})
        ws.call("Page.navigate", {"url": base + path}); time.sleep(7)
        # swap the live <video> for the placeholder: same box, same rounding, same object-fit
        ws.call("Runtime.evaluate", {"expression": f"""(() => {{
            const v = document.getElementById('pv'); if (!v) return 'no video';
            const i = document.createElement('img'); i.src = '{img_url}'; i.id = 'pv'; i.className = v.className;
            i.setAttribute('style', v.getAttribute('style') || '');
            const cs = getComputedStyle(v), r = v.getBoundingClientRect();
            if (cs.position !== 'fixed') {{ i.style.width = r.width + 'px'; i.style.height = (r.width * 9 / 16) + 'px'; i.style.display = 'block'; }}
            i.style.borderRadius = cs.borderRadius; i.style.objectFit = cs.objectFit || 'contain'; i.style.background = '#000';
            v.replaceWith(i); return 'ok'; }})()"""})
        time.sleep(1.5)
        r = ws.call("Page.captureScreenshot", {"format": "png"})
        open(os.path.join(out_dir, name + ".png"), "wb").write(base64.b64decode(r["data"]))
        print(name, "ok")
    finally:
        p.kill()

if __name__ == "__main__":
    base = sys.argv[1]
    here = os.path.dirname(os.path.abspath(__file__))
    src = sys.argv[2] if len(sys.argv) > 2 else os.path.join(here, "placeholder-court.svg")
    out = sys.argv[3] if len(sys.argv) > 3 else os.path.join(here, "..", "img")
    tmp = os.path.join(os.environ.get("TEMP", "."), "camleon-screenshots"); os.makedirs(tmp, exist_ok=True)
    serve_dir = os.path.join(tmp, "serve"); os.makedirs(serve_dir, exist_ok=True)
    png = os.path.join(serve_dir, "placeholder.png")
    if src.lower().endswith(".svg"): render_placeholder(src, png, tmp)
    else: open(png, "wb").write(open(src, "rb").read())
    # the page is served by the phone, so the placeholder must come from an http:// URL too (file:// is blocked)
    # port 0 = any free port: a fixed one could silently be served by a stale server (Windows lets a
    # second server bind the same port when allow_reuse_address is set)
    srv = ThreadingHTTPServer(("127.0.0.1", 0), functools.partial(SimpleHTTPRequestHandler, directory=serve_dir))
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    try:
        for name, path, w, h in PAGES: shoot(base, f"http://127.0.0.1:{srv.server_address[1]}/placeholder.png", out, tmp, name, path, w, h)
    finally:
        srv.shutdown()
