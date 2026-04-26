"""Tiny HTTP server with Range support — needed because CheerpJ fetches
JARs with a Range header, and the stdlib http.server does not implement it.
No third-party deps; just subclasses SimpleHTTPRequestHandler."""
import http.server
import os
import re
import sys


class RangeHandler(http.server.SimpleHTTPRequestHandler):
    def do_GET(self):
        range_header = self.headers.get("Range")
        if not range_header:
            return super().do_GET()

        path = self.translate_path(self.path)
        if not os.path.isfile(path):
            return super().do_GET()

        m = re.match(r"bytes=(\d+)-(\d*)", range_header)
        if not m:
            return super().do_GET()

        size = os.path.getsize(path)
        start = int(m.group(1))
        end = int(m.group(2)) if m.group(2) else size - 1
        if start >= size:
            self.send_error(416)
            return
        end = min(end, size - 1)
        length = end - start + 1

        self.send_response(206)
        self.send_header("Content-Type", self.guess_type(path))
        self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
        self.send_header("Content-Length", str(length))
        self.send_header("Accept-Ranges", "bytes")
        self.end_headers()

        with open(path, "rb") as f:
            f.seek(start)
            remaining = length
            while remaining > 0:
                chunk = f.read(min(65536, remaining))
                if not chunk:
                    break
                self.wfile.write(chunk)
                remaining -= len(chunk)

    def end_headers(self):
        # Advertise Range support on every response.
        self.send_header("Accept-Ranges", "bytes")
        # Dev server — never cache, so edits to .class / .html are picked up
        # on normal reload instead of needing Ctrl+Shift+R.
        self.send_header("Cache-Control", "no-store, must-revalidate")
        self.send_header("Pragma", "no-cache")
        self.send_header("Expires", "0")
        super().end_headers()


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8000
    http.server.test(HandlerClass=RangeHandler, port=port, bind="127.0.0.1")
