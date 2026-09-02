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

    def test_read_env_value_does_not_collide_with_readonly_caller_variable(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            candidate = pathlib.Path(directory) / "production.env"
            candidate.write_text("SAMPLE_VALUE=expected\n", encoding="utf-8")

            result = subprocess.run(
                [
                    "bash",
                    "-c",
                    'readonly env_file="caller-value"; source "$1"; read_env_value "$2" SAMPLE_VALUE',
                    "runtime-test",
                    str(RUNTIME_LIB),
                    str(candidate),
                ],
                check=True,
                capture_output=True,
                text=True,
            )

            self.assertEqual("expected", result.stdout)
            self.assertEqual("", result.stderr)

    def test_resolve_compose_leaves_an_inaccessible_invocation_directory(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            blocked = root / "blocked"
            project = root / "project"
            binary_directory = root / "bin"
            blocked.mkdir()
            project.mkdir()
            binary_directory.mkdir()
            docker = binary_directory / "docker"
            docker.write_text(
                '#!/usr/bin/env bash\n[[ "$1 $2" == "compose version" ]]\n',
                encoding="utf-8",
            )
            os.chmod(docker, 0o700)

            try:
                result = subprocess.run(
                    [
                        "bash",
                        "-c",
                        'cd "$2"; chmod 000 "$2"; source "$1"; resolve_compose "$3"; pwd -P',
                        "runtime-test",
                        str(RUNTIME_LIB),
                        str(blocked),
                        str(project),
                    ],
                    check=True,
                    capture_output=True,
                    text=True,
                    env={
                        **os.environ,
                        "PATH": f"{binary_directory}:/usr/bin:/bin",
                    },
                )
            finally:
                os.chmod(blocked, 0o700)

            self.assertEqual(str(project.resolve()), result.stdout.strip())
            self.assertEqual("", result.stderr)


if __name__ == "__main__":
    unittest.main()
