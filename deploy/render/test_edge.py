#!/usr/bin/env python3
"""Run the real Caddy configuration against a disposable loopback echo server.

Usage: CADDY_BINARY=/path/to/caddy python3 deploy/render/test_edge.py
Requires Caddy 2.11.4; fails rather than skips when it is unavailable. No real application, database,
credentials, Docker stack or privileged ports are used. Only the upstream port is substituted in a
private copy so this check cannot touch a development app at 8080.
"""
import http.client
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import tempfile
import threading
import time
import unittest


class Echo(BaseHTTPRequestHandler):
    def do_GET(self):
        self.answer()

    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        self.rfile.read(length)
        self.answer()

    def answer(self):
        status = 404 if self.path == "/v1/unknown" else 200
        body = json.dumps({"path": self.path, "headers": dict(self.headers)}).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        try:
            self.wfile.write(body)
        except BrokenPipeError:
            pass

    def log_message(self, *args):
        pass


class EdgeTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.binary = os.environ.get("CADDY_BINARY") or shutil.which("caddy")
        if not cls.binary:
            raise RuntimeError("Caddy 2.11.4 is required; set CADDY_BINARY to its executable.")
        version = subprocess.check_output([cls.binary, "version"], text=True)
        if not version.startswith("v2.11.4 "):
            raise RuntimeError("Run this check with the image's pinned Caddy 2.11.4.")
        cls.backend = ThreadingHTTPServer(("127.0.0.1", 0), Echo)
        cls.backend.daemon_threads = True
        cls.worker = threading.Thread(target=cls.backend.serve_forever, daemon=True)
        cls.worker.start()

    @classmethod
    def tearDownClass(cls):
        cls.backend.shutdown()
        cls.backend.server_close()
        cls.worker.join(timeout=5)

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="decisionrail-render-edge-")
        self.root = Path(self.temp.name)
        self.process = None
        self.log = (self.root / "caddy.log").open("w+")

    def tearDown(self):
        if self.process and self.process.poll() is None:
            self.process.terminate()
            self.process.wait(timeout=5)
        self.log.close()
        self.temp.cleanup()

    def start(self, proxies="127.0.0.1/32"):
        with socket.socket() as probe:
            probe.bind(("127.0.0.1", 0))
            self.port = probe.getsockname()[1]
        template = Path(__file__).with_name("Caddyfile").read_text()
        config = self.root / "Caddyfile"
        config.write_text(template.replace("127.0.0.1:8080", f"127.0.0.1:{self.backend.server_port}"))
        env = dict(os.environ, PORT=str(self.port), RENDER_EXTERNAL_HOSTNAME="demo.onrender.com",
                   RENDER_TRUSTED_PROXIES=proxies, XDG_CONFIG_HOME=str(self.root / "config"),
                   XDG_DATA_HOME=str(self.root / "data"))
        self.process = subprocess.Popen([self.binary, "run", "--config", str(config), "--adapter", "caddyfile"],
                                        env=env, stdout=self.log, stderr=self.log)
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline:
            if self.process.poll() is not None:
                self.log.seek(0)
                self.fail("Caddy startup failed: " + self.log.read())
            try:
                with socket.create_connection(("127.0.0.1", self.port), timeout=.1):
                    return
            except OSError:
                time.sleep(.02)
        self.fail("Caddy did not start within five seconds")

    def request(self, path, headers=None, body=None):
        connection = http.client.HTTPConnection("127.0.0.1", self.port, timeout=5)
        try:
            connection.request("POST" if body is not None else "GET", path,
                               body=body, headers=headers or {})
            response = connection.getresponse()
            return response.status, dict(response.getheaders()), response.read()
        finally:
            connection.close()

    def test_trusted_platform_header_is_reauthored_and_other_headers_are_discarded(self):
        self.start()
        status, _, body = self.request("/ui/identity", {
            "CF-Connecting-IP": "198.51.100.42", "X-Forwarded-For": "203.0.113.99",
            "Forwarded": "for=203.0.113.98;proto=http;host=attacker.example",
            "X-Forwarded-Proto": "http", "X-Forwarded-Host": "attacker.example",
            "X-Forwarded-Port": "99", "X-Forwarded-Prefix": "/wrong", "X-Forwarded-Ssl": "off",
            "X-Real-IP": "203.0.113.97", "Host": "attacker.example"})
        self.assertEqual(status, 200)
        headers = {k.lower(): v for k, v in json.loads(body)["headers"].items()}
        for key, value in {"x-forwarded-for": "198.51.100.42", "x-forwarded-proto": "https",
                           "x-forwarded-host": "demo.onrender.com", "x-forwarded-port": "443",
                           "host": "demo.onrender.com"}.items():
            self.assertEqual(headers.get(key), value)
        for key in ("forwarded", "x-forwarded-prefix", "x-forwarded-ssl", "cf-connecting-ip", "x-real-ip"):
            self.assertNotIn(key, headers)

    def test_untrusted_peer_cannot_supply_a_client_address(self):
        self.start("192.0.2.0/24")
        status, _, body = self.request("/ui/identity", {"CF-Connecting-IP": "198.51.100.42",
                                                       "X-Forwarded-For": "203.0.113.99"})
        self.assertEqual(status, 200)
        headers = {k.lower(): v for k, v in json.loads(body)["headers"].items()}
        self.assertEqual(headers["x-forwarded-for"], "127.0.0.1")

    def test_allowlist_security_headers_and_api_status_survive(self):
        self.start()
        for path in ("/", "/dashboard/payments/example", "/ui/identity", "/v1/payments",
                     "/actuator/health", "/actuator/health/readiness", "/actuator/info"):
            with self.subTest(path=path):
                status, headers, _ = self.request(path)
                self.assertEqual(status, 200)
                self.assertEqual(headers["X-Frame-Options"], "DENY")
                self.assertEqual(headers["Referrer-Policy"], "no-referrer")
                self.assertEqual(headers["X-Content-Type-Options"], "nosniff")
                self.assertEqual(headers["Strict-Transport-Security"], "max-age=31536000")
                self.assertIn("frame-ancestors 'none'", headers["Content-Security-Policy"])
                self.assertNotIn("Server", headers)
        for path in ("/actuator/prometheus", "/env", "/metrics", "/unlisted"):
            self.assertEqual(self.request(path)[0], 404)
        status, headers, _ = self.request("/v1/unknown")
        self.assertEqual(status, 404)
        self.assertEqual(headers["Content-Type"], "application/json")

    def test_body_limit_applies_to_json(self):
        self.start()
        self.assertEqual(self.request("/v1/payments", body=b"{}", headers={"Content-Type": "application/json"})[0], 200)
        self.assertEqual(self.request("/v1/payments", body=b"x" * 65537,
                                      headers={"Content-Type": "application/json"})[0], 413)


if __name__ == "__main__":
    unittest.main()
