#!/usr/bin/env python3
"""Exercise the real supervisor with disposable process stubs; no Docker or service credentials."""
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import time
import unittest

ENTRYPOINT = Path(__file__).with_name("entrypoint.sh")
STUB = r'''
import json, os, pathlib, signal, sys, time
name = pathlib.Path(sys.argv[0]).name
root = pathlib.Path(os.environ["STUB_STATE"])
if name == "caddy" and sys.argv[1] == "validate":
    (root / "validated").touch()
    sys.exit(int(os.environ.get("STUB_VALIDATE_EXIT", "0")))
(root / (name + ".started")).write_text(json.dumps({"pid":os.getpid(), "argv":sys.argv[1:]}))
def stop(signum, frame):
    (root / (name + ".terminated")).touch()
    if os.environ.get("STUB_IGNORE_TERM") != name:
        sys.exit(0)
signal.signal(signal.SIGTERM, stop)
signal.signal(signal.SIGINT, stop)
while True:
    requested = root / (name + ".exit")
    if requested.exists():
        sys.exit(int(requested.read_text()))
    time.sleep(0.02)
'''


class SupervisorTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="decisionrail-render-supervisor-")
        self.root = Path(self.temp.name)
        for name in ("java", "caddy"):
            executable = self.root / name
            executable.write_text(f"#!{sys.executable}\n" + STUB)
            executable.chmod(0o755)
        self.env = dict(os.environ, PATH=str(self.root) + os.pathsep + os.environ["PATH"],
                        STUB_STATE=str(self.root), RENDER_EXTERNAL_HOSTNAME="demo.onrender.com",
                        RENDER_TRUSTED_PROXIES="127.0.0.1/32", RENDER_SHUTDOWN_SECONDS="1")
        self.env.pop("PORT", None)
        self.processes = []

    def tearDown(self):
        for process in self.processes:
            if process.poll() is None:
                process.terminate()
                process.wait(timeout=5)
            process.communicate(timeout=5)
        self.temp.cleanup()

    def start(self, *args, **environment):
        process = subprocess.Popen(["/bin/bash", str(ENTRYPOINT), *args],
                                   env=dict(self.env, **environment),
                                   stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        self.processes.append(process)
        return process

    def await_file(self, name):
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline:
            path = self.root / name
            if path.exists():
                return path
            time.sleep(0.02)
        self.fail(f"Process did not reach {name}")

    def running(self, **environment):
        process = self.start(**environment)
        for name in ("java", "caddy"):
            self.await_file(name + ".started")
        return process

    def test_help_and_bad_arguments_do_not_execute_commands(self):
        self.assertEqual(self.start("--help", RENDER_EXTERNAL_HOSTNAME="").wait(timeout=5), 0)
        self.assertEqual(self.start("--bogus", RENDER_EXTERNAL_HOSTNAME="").wait(timeout=5), 2)
        self.assertFalse((self.root / "validated").exists())

    def test_invalid_environment_cannot_inject_configuration(self):
        for invalid in ({"RENDER_EXTERNAL_HOSTNAME": "demo.onrender.com\nrespond 200"},
                        {"RENDER_EXTERNAL_HOSTNAME": "https://demo.onrender.com"},
                        {"RENDER_EXTERNAL_HOSTNAME": "bad-.onrender.com"},
                        {"RENDER_TRUSTED_PROXIES": "127.0.0.1\nadmin :2019"},
                        {"RENDER_TRUSTED_PROXIES": "0.0.0.0/0"},
                        {"RENDER_TRUSTED_PROXIES": "::/00"},
                        {"RENDER_TRUSTED_PROXIES": "   "},
                        {"RENDER_TRUSTED_PROXIES": ""},
                        {"PORT": "8080"}, {"PORT": "18012"},
                        {"PORT": "10000;touch injected"}, {"RENDER_SHUTDOWN_SECONDS": "26"}):
            with self.subTest(invalid=invalid):
                self.assertEqual(self.start(**invalid).wait(timeout=5), 2)
        self.assertFalse((self.root / "validated").exists())

    def test_invalid_caddy_config_never_starts_either_service(self):
        self.assertEqual(self.start(STUB_VALIDATE_EXIT="42").wait(timeout=5), 42)
        self.assertTrue((self.root / "validated").exists())
        self.assertFalse((self.root / "java.started").exists())
        self.assertFalse((self.root / "caddy.started").exists())

    def test_term_reaches_both_children_and_invariants_are_fixed(self):
        process = self.running(PUBLIC_DEMO_ENABLED="false", SESSION_COOKIE_SECURE="false",
                               SERVER_ADDRESS="0.0.0.0", SERVER_PORT="10000")
        args = json.loads((self.root / "java.started").read_text())["argv"]
        for arg in ("--spring.profiles.active=render", "--server.address=127.0.0.1",
                    "--server.port=8080", "--server.forward-headers-strategy=native",
                    r"--server.tomcat.remoteip.internal-proxies=127\.0\.0\.1",
                    "--app.public-demo.enabled=true", "--server.servlet.session.cookie.secure=true",
                    "--app.ui.secure-cookies=true", "--app.demo-enabled=false",
                    "--app.events.fault-injection-enabled=false"):
            self.assertIn(arg, args)
        process.terminate()
        self.assertEqual(process.wait(timeout=5), 143)
        for name in ("java", "caddy"):
            self.assertTrue((self.root / (name + ".terminated")).exists())

    def test_either_child_failure_stops_the_other(self):
        for failed, companion in (("java", "caddy"), ("caddy", "java")):
            with self.subTest(failed=failed):
                for marker in self.root.glob("*.started"):
                    marker.unlink()
                for marker in self.root.glob("*.terminated"):
                    marker.unlink()
                process = self.running()
                (self.root / (failed + ".exit")).write_text("17")
                self.assertEqual(process.wait(timeout=5), 17)
                self.assertTrue((self.root / (companion + ".terminated")).exists())
                (self.root / (failed + ".exit")).unlink()

    def test_term_between_spawn_and_pid_assignment_still_reaches_child(self):
        for variable, child_name in (("java_pid", "java"), ("caddy_pid", "caddy")):
            with self.subTest(child=child_name):
                for marker in self.root.glob("*.started"):
                    marker.unlink()
                for marker in self.root.glob("*.terminated"):
                    marker.unlink()
                injection = self.root / "signal-before-pid.sh"
                # DEBUG runs immediately before the PID assignment. Wait for the stub's own signal
                # handler, then deliver TERM in the actual fork/assignment gap, without timing luck.
                injection.write_text(
                    f"trap 'if [[ \"$BASH_COMMAND\" == \"{variable}=\\$!\" ]]; then "
                    f"while [[ ! -s \"$STUB_STATE/{child_name}.started\" ]]; do sleep 0.01; done; "
                    "kill -TERM $$; fi' DEBUG\n")
                process = self.start(BASH_ENV=str(injection))
                try:
                    self.assertEqual(process.wait(timeout=5), 143)
                    child = json.loads(self.await_file(child_name + ".started").read_text())["pid"]
                    self.assertTrue((self.root / (child_name + ".terminated")).exists())
                    with self.assertRaises(ProcessLookupError):
                        os.kill(child, 0)
                finally:
                    # A failing regression must not leave the deliberately orphaned stub alive.
                    for marker in self.root.glob("*.started"):
                        try:
                            os.kill(json.loads(marker.read_text())["pid"], signal.SIGTERM)
                        except ProcessLookupError:
                            pass

    def test_unexpected_clean_exit_is_failure(self):
        process = self.running()
        (self.root / "caddy.exit").write_text("0")
        self.assertEqual(process.wait(timeout=5), 1)
        self.assertTrue((self.root / "java.terminated").exists())

    def test_uncooperative_child_is_killed_after_grace(self):
        process = self.running(STUB_IGNORE_TERM="caddy")
        child = json.loads((self.root / "caddy.started").read_text())["pid"]
        process.terminate()
        self.assertEqual(process.wait(timeout=5), 143)
        with self.assertRaises(ProcessLookupError):
            os.kill(child, 0)


if __name__ == "__main__":
    unittest.main()
