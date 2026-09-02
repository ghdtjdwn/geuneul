from __future__ import annotations

import os
import pathlib
import subprocess
import tempfile
import unittest


REPOSITORY_ROOT = pathlib.Path(__file__).resolve().parents[3]
RUNTIME_LIB = REPOSITORY_ROOT / "infra/oci/scripts/runtime-lib.sh"


class RuntimeLibraryTest(unittest.TestCase):
    def test_file_metadata_helpers_are_portable(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            candidate = pathlib.Path(directory) / "production.env"
            candidate.write_text("abc", encoding="utf-8")
            os.chmod(candidate, 0o600)

            result = subprocess.run(
                [
                    "bash",
                    "-c",
                    'source "$1"; printf "%s %s" "$(portable_file_mode "$2")" "$(portable_file_size "$2")"',
                    "runtime-test",
                    str(RUNTIME_LIB),
                    str(candidate),
                ],
                check=True,
                capture_output=True,
                text=True,
            )

            self.assertEqual("600 3", result.stdout)


if __name__ == "__main__":
    unittest.main()
