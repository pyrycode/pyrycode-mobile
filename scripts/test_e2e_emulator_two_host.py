import base64
import json
import os
from pathlib import Path
import shlex
import stat
import subprocess
import tempfile
import unittest


def b64url(obj):
    return base64.urlsafe_b64encode(json.dumps(obj).encode()).decode().rstrip("=")


class EmulatorTwoHostTest(unittest.TestCase):
    """#847: the second test daemon's seed, pairing code and name guard."""

    def setUp(self):
        self.script = Path(__file__).with_name("e2e-emulator.sh").read_text()

    def function(self, name):
        start = self.script.index(name + "() {")
        return self.script[start:self.script.index("\n}\n", start) + 3]

    def run_function(self, name, *args):
        body = "set -euo pipefail\n" + self.function(name) + name + ' "$@"\n'
        return subprocess.run(["bash", "-c", body, "bash", *args], capture_output=True, text=True)

    def test_name_guard_accepts_only_e2e_instances(self):
        for name in ("e2e-live", "e2e-emulator", "e2e-auto-1a2b3c4d", "e2e-auto-1a2b3c4d-b"):
            with self.subTest(name=name):
                self.assertEqual(0, self.run_function("two_host_name_ok", name).returncode)
        for name in ("pyry", "", "e2e-", "e2e-../x", "e2e-a/b", "E2E-live", "prod-e2e-x"):
            with self.subTest(name=name):
                self.assertNotEqual(0, self.run_function("two_host_name_ok", name).returncode)

    def test_seed_merges_one_promoted_row_and_keeps_the_rest(self):
        with tempfile.TemporaryDirectory() as tmp:
            instance = Path(tmp) / "e2e-live"
            instance.mkdir(mode=0o700)
            existing = {
                "conversations": [
                    {"id": "keep", "name": "other", "cwd": "/h", "is_promoted": False, "last_used_at": "2026-01-01T00:00:00Z"},
                    {"id": "dup", "name": "stale", "cwd": "/h", "is_promoted": False, "last_used_at": "2026-01-01T00:00:00Z"},
                ],
                "workspace_labels": {"/h": "Home"},
            }
            (instance / "conversations.json").write_text(json.dumps(existing))
            result = self.run_function("seed_collision_conversation", str(instance), "dup", "e2e847-a-1", "/home/op")
            self.assertEqual(0, result.returncode, result.stderr)
            path = instance / "conversations.json"
            doc = json.loads(path.read_text())
            self.assertEqual({"/h": "Home"}, doc["workspace_labels"])
            rows = {row["id"]: row for row in doc["conversations"]}
            self.assertEqual({"keep", "dup"}, set(rows))
            self.assertEqual(existing["conversations"][0], rows["keep"])
            seeded = rows["dup"]
            self.assertEqual(("e2e847-a-1", "/home/op", True), (seeded["name"], seeded["cwd"], seeded["is_promoted"]))
            self.assertNotIn("current_session_id", seeded)
            self.assertEqual(0o600, stat.S_IMODE(path.stat().st_mode))
            self.assertEqual(["conversations.json"], sorted(p.name for p in instance.iterdir()))

    def test_seed_creates_a_private_instance_directory(self):
        with tempfile.TemporaryDirectory() as tmp:
            instance = Path(tmp) / "e2e-auto-1a2b3c4d-b"
            result = self.run_function("seed_collision_conversation", str(instance), "id-1", "e2e847-b-1", "/home/op")
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(0o700, stat.S_IMODE(instance.stat().st_mode))
            doc = json.loads((instance / "conversations.json").read_text())
            self.assertEqual(["id-1"], [row["id"] for row in doc["conversations"]])

    def test_seed_refuses_an_unreadable_registry_without_echoing_it(self):
        with tempfile.TemporaryDirectory() as tmp:
            instance = Path(tmp) / "e2e-live"
            instance.mkdir()
            (instance / "conversations.json").write_text("secret-ish not json")
            result = self.run_function("seed_collision_conversation", str(instance), "id-1", "n", "/h")
            self.assertNotEqual(0, result.returncode)
            self.assertNotIn("secret-ish", result.stdout + result.stderr)
            self.assertEqual("secret-ish not json", (instance / "conversations.json").read_text())

    def test_phone_pair_code_swaps_only_the_relay(self):
        payload = {"server": "srv-b", "relay": "wss://relay.example/v1/server", "token": "tok-b",
                   "server_static_pubkey": "A" * 43 + "="}
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp) / "pair-b.out"
            out.write_text("some QR art\n" + b64url(payload) + "\nStatic-key fp: x\n")
            result = self.run_function("phone_pair_code", str(out), "wss://relay.example")
            self.assertEqual(0, result.returncode, result.stderr)
            values = {}
            for line in result.stdout.splitlines():
                key, _, value = line.partition("=")
                values[key] = shlex.split(value)[0]
            self.assertEqual({"SERVER_ID_B", "PAIR_CODE_B"}, set(values))
            self.assertEqual("srv-b", values["SERVER_ID_B"])
            self.assertNotIn("=", values["PAIR_CODE_B"])
            code = values["PAIR_CODE_B"]
            decoded = json.loads(base64.urlsafe_b64decode(code + "=" * (-len(code) % 4)))
            self.assertEqual(dict(payload, relay="wss://relay.example"), decoded)

    def test_phone_pair_code_fails_without_a_payload_line(self):
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp) / "pair-b.out"
            out.write_text("error: no daemon\n")
            result = self.run_function("phone_pair_code", str(out), "wss://relay.example")
            self.assertNotEqual(0, result.returncode)
            self.assertEqual("", result.stdout)

    def test_pair_token_prints_only_the_peer_token(self):
        # #848: the second-client peer needs its own token and nothing else from its `pyry pair`.
        payload = {"server": "srv-a", "relay": "ws://127.0.0.1:1/v1/server", "token": "peer tok'en",
                   "server_static_pubkey": "a2V5"}
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp) / "pair-peer.out"
            out.write_text("QR\n\n" + b64url(payload) + "\n")
            result = self.run_function("pair_token", str(out))
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(["PEER_TOKEN=" + shlex.quote("peer tok'en")], result.stdout.splitlines())
            out.write_text("error: no daemon\n")
            result = self.run_function("pair_token", str(out))
            self.assertNotEqual(0, result.returncode)
            self.assertEqual("", result.stdout)

    def test_cleanup_tolerates_an_unset_second_daemon(self):
        cleanup = self.script[self.script.index("cleanup() {"):self.script.index("trap cleanup EXIT INT TERM")]
        with tempfile.TemporaryDirectory() as tmp:
            env = {k: v for k, v in os.environ.items() if k != "DAEMON_B_PID"}
            env.update(WORK_DIR=tmp, ISO_HOME="", WATCHER_PID="", DAEMON_PID="", RELAY_PID="")
            result = subprocess.run(["bash", "-c", "set -euo pipefail\nlog() { :; }\n" + cleanup + "\ncleanup\n"],
                                    env=env, capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stderr)


if __name__ == "__main__":
    unittest.main()
