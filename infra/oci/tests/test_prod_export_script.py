from __future__ import annotations

import os
import subprocess
import tempfile
import unittest
from pathlib import Path


REPOSITORY_ROOT = Path(__file__).resolve().parents[3]
EXPORT_SCRIPT = REPOSITORY_ROOT / "infra" / "scripts" / "prod-export-db.sh"


class ProductionExportScriptTest(unittest.TestCase):
    def test_confirmation_guard_runs_before_any_aws_call(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            fake_bin = Path(temp_dir) / "bin"
            fake_bin.mkdir()
            marker = Path(temp_dir) / "aws-called"
            fake_aws = fake_bin / "aws"
            fake_aws.write_text(
                f"#!/bin/sh\ntouch {marker}\nexit 99\n",
                encoding="utf-8",
            )
            fake_aws.chmod(0o700)
            environment = os.environ.copy()
            environment["PATH"] = f"{fake_bin}:{environment['PATH']}"
            environment.pop("AWS_DB_EXPORT_CONFIRM", None)

            result = subprocess.run(
                [str(EXPORT_SCRIPT), "geuneul-db", str(Path(temp_dir) / "output")],
                env=environment,
                capture_output=True,
                text=True,
            )

            self.assertNotEqual(result.returncode, 0)
            self.assertIn("AWS_DB_EXPORT_CONFIRM", result.stderr)
            self.assertFalse(marker.exists())

    def test_export_contract_is_pinned_and_verifies_the_download(self) -> None:
        script = EXPORT_SCRIPT.read_text(encoding="utf-8")

        self.assertRegex(script, r"postgres:16\.13-bookworm@sha256:[0-9a-f]{64}")
        self.assertRegex(script, r"aws-cli:2\.31\.30@sha256:[0-9a-f]{64}")
        self.assertIn('desiredCount\' "$service_json")" == "0"', script)
        self.assertIn('condition:"SUCCESS"', script)
        self.assertIn("cd /export && sha256sum geuneul.dump", script)
        self.assertIn('--field-separator="$(printf "\\t")"', script)
        self.assertNotIn('--field-separator="\\t"', script)
        self.assertIn("shasum -a 256 --check geuneul.dump.sha256", script)
        self.assertIn("pg_restore --list", script)


if __name__ == "__main__":
    unittest.main()
