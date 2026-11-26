#!/usr/bin/env python3
"""Verify that model downloads resume after a truncated HTTP response."""

import hashlib
import os
import subprocess
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path


PAYLOAD = bytes(range(256)) * 4096
INTERRUPT_AT = len(PAYLOAD) // 2
HELPER = Path(__file__).with_name("download-verified-artifact.sh")


class InterruptedDownloadHandler(BaseHTTPRequestHandler):
    def do_GET(self):
        self.server.request_ranges.append(self.headers.get("Range"))
        requested_range = self.headers.get("Range")

        if requested_range is None:
            self.send_response(200)
            self.send_header("Content-Length", str(len(PAYLOAD)))
            self.end_headers()
            self.wfile.write(PAYLOAD[:INTERRUPT_AT])
            self.wfile.flush()
            self.close_connection = True
            return

        prefix = "bytes="
        if not requested_range.startswith(prefix) or not requested_range.endswith("-"):
            self.send_error(400, "Unsupported range")
            return

        offset = int(requested_range[len(prefix) : -1])
        body = PAYLOAD[offset:]
        self.send_response(206)
        self.send_header("Content-Range", f"bytes {offset}-{len(PAYLOAD) - 1}/{len(PAYLOAD)}")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, _format, *_args):
        return


class VerifiedDownloadTest(unittest.TestCase):
    def test_resumes_interrupted_download_and_reuses_verified_cache(self):
        server = ThreadingHTTPServer(("127.0.0.1", 0), InterruptedDownloadHandler)
        server.request_ranges = []
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()

        try:
            url = f"http://127.0.0.1:{server.server_port}/artifact"
            digest = hashlib.sha256(PAYLOAD).hexdigest()

            with tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                destination = root / "first" / "artifact.bin"
                environment = os.environ.copy()
                environment["DOWNLOAD_CACHE_DIR"] = str(root / "cache")
                environment["DOWNLOAD_RETRY_DELAY_SECONDS"] = "0"
                environment["DOWNLOAD_MAX_ATTEMPTS"] = "3"

                result = subprocess.run(
                    ["sh", str(HELPER), url, str(destination), digest],
                    capture_output=True,
                    text=True,
                    env=environment,
                    timeout=60,
                    check=False,
                )
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(destination.read_bytes(), PAYLOAD)
                self.assertEqual(
                    server.request_ranges,
                    [None, f"bytes={INTERRUPT_AT}-"],
                )

                cached_destination = root / "second" / "artifact.bin"
                cached_result = subprocess.run(
                    ["sh", str(HELPER), url, str(cached_destination), digest],
                    capture_output=True,
                    text=True,
                    env=environment,
                    timeout=60,
                    check=False,
                )
                self.assertEqual(cached_result.returncode, 0, cached_result.stderr)
                self.assertEqual(cached_destination.read_bytes(), PAYLOAD)
                self.assertEqual(len(server.request_ranges), 2)
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=5)


if __name__ == "__main__":
    unittest.main(verbosity=2)
