from __future__ import annotations

import http.client
import json
import os
import pathlib
import shutil
import subprocess
import time
import unittest
import urllib.error
import urllib.request
import uuid


REPOSITORY_ROOT = pathlib.Path(__file__).resolve().parents[3]
CADDY_CONFIG = REPOSITORY_ROOT / "infra/oci/caddy/geuneul.caddy.example"
CADDY_IMAGE = "caddy:2.10.2@sha256:c3d7ee5d2b11f9dc54f947f68a734c84e9c9666c92c88a7f30b9cba5da182adb"


class CaddyAccessLogIntegrationTest(unittest.TestCase):
    @unittest.skipUnless(
        os.environ.get("GEUNEUL_CADDY_INTEGRATION") == "1",
        "set GEUNEUL_CADDY_INTEGRATION=1 to run the Docker integration test",
    )
    def test_presigned_query_is_absent_while_normal_request_is_logged(self) -> None:
        if shutil.which("docker") is None:
            self.fail("Docker is required when GEUNEUL_CADDY_INTEGRATION=1")
        container = f"geuneul-caddy-log-test-{uuid.uuid4().hex[:12]}"
        config = CADDY_CONFIG.read_text(encoding="utf-8")
        replacements = {
            "{$GEUNEUL_SITE_ADDRESS}": ":8080",
            "{$GEUNEUL_FRONTEND_ORIGIN}": "https://frontend.example.test",
            "{$GEUNEUL_OBJECT_STORAGE_HOST}": "127.0.0.1:1",
            "{$GEUNEUL_BACKEND_UPSTREAM}": "http://127.0.0.1:1",
        }
        for source, target in replacements.items():
            config = config.replace(source, target)

        subprocess.run(
            [
                "docker",
                "run",
                "--detach",
                "--name",
                container,
                "--publish",
                "127.0.0.1::8080",
                "--env",
                f"TEST_CADDYFILE={config}",
                "--entrypoint",
                "sh",
                CADDY_IMAGE,
                "-ec",
                'printf "%s" "$TEST_CADDYFILE" >/tmp/Caddyfile; exec caddy run --config /tmp/Caddyfile --adapter caddyfile',
            ],
            check=True,
            capture_output=True,
            text=True,
        )
        try:
            port = self._wait_for_port(container)
            self._wait_until_serving(f"http://127.0.0.1:{port}/health/ordinary-visible")
            request = urllib.request.Request(
                f"http://127.0.0.1:{port}/object-storage/photo.jpg?X-Amz-Credential=presigned-secret-sentinel",
                data=b"x",
                method="PUT",
                headers={"Origin": "https://frontend.example.test"},
            )
            self._request(request)
            time.sleep(0.5)
            logs = subprocess.run(
                ["docker", "logs", container],
                check=True,
                capture_output=True,
                text=True,
            )
            combined = logs.stdout + logs.stderr
            self.assertIn("/health/ordinary-visible", combined)
            self.assertNotIn("presigned-secret-sentinel", combined)
            entries = [json.loads(line) for line in combined.splitlines() if line.startswith("{")]
            access_uris = [
                entry.get("request", {}).get("uri", "")
                for entry in entries
                if entry.get("logger", "").startswith("http.log.access")
            ]
            self.assertTrue(any("/health/ordinary-visible" in uri for uri in access_uris))
            self.assertFalse(any("/object-storage/" in uri for uri in access_uris))
            self.assertTrue(
                any(
                    entry.get("request", {}).get("uri")
                    == "/object-storage/photo.jpg?REDACTED"
                    for entry in entries
                    if entry.get("logger", "").startswith("http.log.error")
                )
            )
        finally:
            subprocess.run(
                ["docker", "rm", "--force", container],
                check=False,
                capture_output=True,
                text=True,
            )

    @staticmethod
    def _request(request: str | urllib.request.Request) -> None:
        try:
            urllib.request.urlopen(request, timeout=2).read()
        except urllib.error.HTTPError as error:
            error.close()

    @classmethod
    def _wait_until_serving(cls, request: str) -> None:
        for _ in range(40):
            try:
                cls._request(request)
                return
            except (urllib.error.URLError, http.client.RemoteDisconnected):
                time.sleep(0.1)
        raise AssertionError("Caddy test server did not accept HTTP requests")

    @staticmethod
    def _wait_for_port(container: str) -> int:
        for _ in range(40):
            result = subprocess.run(
                ["docker", "port", container, "8080/tcp"],
                check=False,
                capture_output=True,
                text=True,
            )
            if result.returncode == 0 and result.stdout.strip():
                return int(result.stdout.strip().rsplit(":", 1)[1])
            time.sleep(0.1)
        raise AssertionError("Caddy test container did not publish port 8080")


if __name__ == "__main__":
    unittest.main()
