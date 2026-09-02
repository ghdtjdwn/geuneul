from __future__ import annotations

import hashlib
import os
import pathlib
import subprocess
import tempfile
import time
import unittest


REPOSITORY_ROOT = pathlib.Path(__file__).resolve().parents[3]
VERIFIER = REPOSITORY_ROOT / "infra/oci/scripts/verify-backup-object.sh"


class BackupObjectVerificationTest(unittest.TestCase):
    payload = b"verified-off-host-backup"
    backup_name = "geuneul-20260902T123456Z.dump"

    def test_verifies_present_unexpired_nonempty_object_and_sha256(self) -> None:
        result = self._run_verifier()

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("SHA-256 verified", result.stdout)

    def test_rejects_missing_object(self) -> None:
        result = self._run_verifier(extra_env={"MOCK_HEAD_MISSING": "1"})

        self.assertNotEqual(0, result.returncode)
        self.assertIn("missing or HEAD was denied", result.stderr)

    def test_rejects_remote_digest_mismatch(self) -> None:
        result = self._run_verifier(marker_digest=hashlib.sha256(b"different").hexdigest())

        self.assertNotEqual(0, result.returncode)
        self.assertIn("SHA-256 does not match", result.stderr)

    def test_rejects_zero_sized_remote_object(self) -> None:
        result = self._run_verifier(extra_env={"MOCK_REMOTE_SIZE": "0"})

        self.assertNotEqual(0, result.returncode)
        self.assertIn("size does not match", result.stderr)

    def test_rejects_expired_marker_before_remote_access(self) -> None:
        eight_days_ago = int(time.time()) - 8 * 86400
        result = self._run_verifier(marker_epoch=eight_days_ago)

        self.assertNotEqual(0, result.returncode)
        self.assertIn("expired or from the future", result.stderr)

    def _run_verifier(
        self,
        *,
        marker_digest: str | None = None,
        marker_epoch: int | None = None,
        extra_env: dict[str, str] | None = None,
    ) -> subprocess.CompletedProcess[str]:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp = pathlib.Path(temp_dir)
            env_file = temp / "production.env"
            marker = temp / "backup.marker"
            mock_docker = temp / "docker"
            env_file.write_text(
                "\n".join(
                    (
                        "S3_ENDPOINT=https://object.example.test",
                        "S3_REGION=ap-seoul-1",
                        "BACKUP_BUCKET_NAME=geuneul-backups",
                        "BACKUP_RETENTION_DAYS=7",
                        "BACKUP_AWS_ACCESS_KEY_ID=test-access-key",
                        "BACKUP_AWS_SECRET_ACCESS_KEY=test-secret-key",
                    )
                )
                + "\n",
                encoding="utf-8",
            )
            digest = marker_digest or hashlib.sha256(self.payload).hexdigest()
            marker.write_text(
                f"{marker_epoch or int(time.time())}\t{self.backup_name}\t{len(self.payload)}\t{digest}\n",
                encoding="utf-8",
            )
            os.chmod(env_file, 0o600)
            os.chmod(marker, 0o600)
            mock_docker.write_text(
                """#!/usr/bin/env bash
set -Eeuo pipefail
if [[ "$*" == *"s3api head-object"* ]]; then
  [[ -z "${MOCK_HEAD_MISSING:-}" ]] || exit 44
  printf '%s\n' "$MOCK_REMOTE_SIZE"
elif [[ "$*" == *"s3 cp"* ]]; then
  printf '%s' "$MOCK_REMOTE_PAYLOAD"
else
  exit 45
fi
""",
                encoding="utf-8",
            )
            os.chmod(mock_docker, 0o700)
            environment = os.environ.copy()
            environment.update(
                {
                    "PATH": f"{temp}{os.pathsep}{environment['PATH']}",
                    "MOCK_REMOTE_SIZE": str(len(self.payload)),
                    "MOCK_REMOTE_PAYLOAD": self.payload.decode("ascii"),
                }
            )
            environment.update(extra_env or {})
            return subprocess.run(
                ["bash", str(VERIFIER), str(env_file), str(marker)],
                check=False,
                capture_output=True,
                text=True,
                env=environment,
            )


if __name__ == "__main__":
    unittest.main()
