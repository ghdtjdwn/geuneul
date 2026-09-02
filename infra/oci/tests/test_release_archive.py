from __future__ import annotations

import io
import subprocess
import sys
import tarfile
import tempfile
import unittest
from pathlib import Path


VALIDATOR = Path(__file__).resolve().parents[1] / "server" / "validate-release-archive.py"


class ReleaseArchiveValidationTest(unittest.TestCase):
    def test_extracts_regular_files(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp = Path(temp_dir)
            archive = temp / "release.tar.gz"
            destination = temp / "destination"
            destination.mkdir()
            self._write_archive(archive, [("release-manifest", b"source_sha=abc\n", tarfile.REGTYPE)])

            subprocess.run(
                [sys.executable, str(VALIDATOR), str(archive), str(destination)],
                check=True,
            )

            self.assertEqual((destination / "release-manifest").read_bytes(), b"source_sha=abc\n")

    def test_rejects_parent_path_traversal(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp = Path(temp_dir)
            archive = temp / "release.tar.gz"
            destination = temp / "destination"
            destination.mkdir()
            self._write_archive(archive, [("../escape", b"blocked", tarfile.REGTYPE)])

            result = subprocess.run(
                [sys.executable, str(VALIDATOR), str(archive), str(destination)],
                capture_output=True,
                text=True,
            )

            self.assertNotEqual(result.returncode, 0)
            self.assertIn("unsafe archive path", result.stderr)
            self.assertFalse((temp / "escape").exists())

    def test_rejects_symbolic_links(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp = Path(temp_dir)
            archive = temp / "release.tar.gz"
            destination = temp / "destination"
            destination.mkdir()
            self._write_archive(archive, [("link", b"target", tarfile.SYMTYPE)])

            result = subprocess.run(
                [sys.executable, str(VALIDATOR), str(archive), str(destination)],
                capture_output=True,
                text=True,
            )

            self.assertNotEqual(result.returncode, 0)
            self.assertIn("non-regular", result.stderr)

    @staticmethod
    def _write_archive(
        archive: Path,
        members: list[tuple[str, bytes, bytes]],
    ) -> None:
        with tarfile.open(archive, "w:gz", format=tarfile.USTAR_FORMAT) as handle:
            for name, payload, member_type in members:
                info = tarfile.TarInfo(name)
                info.type = member_type
                if member_type == tarfile.SYMTYPE:
                    info.linkname = payload.decode("utf-8")
                    info.size = 0
                    handle.addfile(info)
                else:
                    info.size = len(payload)
                    handle.addfile(info, io.BytesIO(payload))


if __name__ == "__main__":
    unittest.main()
