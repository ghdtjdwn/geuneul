from __future__ import annotations

import hashlib
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


OCI_DIR = Path(__file__).resolve().parents[1]
OBJECT_INVENTORY = OCI_DIR / "scripts" / "object-inventory.py"
VERIFY_MIGRATION = OCI_DIR / "scripts" / "verify-object-migration.py"


class ObjectMigrationTest(unittest.TestCase):
    def test_inventory_is_sorted_and_hashes_file_contents(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir) / "objects"
            root.mkdir()
            (root / "z-last.txt").write_bytes(b"last")
            (root / "nested").mkdir()
            (root / "nested" / "first.bin").write_bytes(b"first\x00object")
            output = Path(temp_dir) / "inventory.json"

            subprocess.run(
                [sys.executable, str(OBJECT_INVENTORY), str(root), str(output)],
                check=True,
            )

            records = json.loads(output.read_text(encoding="utf-8"))
            self.assertEqual([record["key"] for record in records], ["nested/first.bin", "z-last.txt"])
            self.assertEqual(records[0]["size"], len(b"first\x00object"))
            self.assertEqual(records[0]["sha256"], hashlib.sha256(b"first\x00object").hexdigest())

    def test_verifier_accepts_identical_s3_and_local_inventories(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp = Path(temp_dir)
            local = [{"key": "photo/a.jpg", "size": 3, "sha256": hashlib.sha256(b"abc").hexdigest()}]
            s3 = [{"Key": "photo/a.jpg", "Size": 3}]
            paths = self._write_inputs(temp, s3, s3, local, local)

            result = subprocess.run(
                [sys.executable, str(VERIFY_MIGRATION), *(str(path) for path in paths)],
                check=True,
                capture_output=True,
                text=True,
            )

            self.assertIn("verified 1 objects and 3 bytes with SHA-256", result.stdout)

    def test_verifier_rejects_content_mismatch_with_same_key_and_size(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            temp = Path(temp_dir)
            source = [{"key": "photo/a.jpg", "size": 3, "sha256": hashlib.sha256(b"abc").hexdigest()}]
            target = [{"key": "photo/a.jpg", "size": 3, "sha256": hashlib.sha256(b"xyz").hexdigest()}]
            s3 = [{"Key": "photo/a.jpg", "Size": 3}]
            paths = self._write_inputs(temp, s3, s3, source, target)

            result = subprocess.run(
                [sys.executable, str(VERIFY_MIGRATION), *(str(path) for path in paths)],
                capture_output=True,
                text=True,
            )

            self.assertNotEqual(result.returncode, 0)
            self.assertIn("size/SHA-256 inventories differ", result.stderr)

    @staticmethod
    def _write_inputs(
        temp: Path,
        source_s3: list[dict[str, object]],
        target_s3: list[dict[str, object]],
        source_local: list[dict[str, object]],
        target_local: list[dict[str, object]],
    ) -> tuple[Path, Path, Path, Path]:
        payloads = (source_s3, target_s3, source_local, target_local)
        paths: list[Path] = []
        for index, payload in enumerate(payloads):
            path = temp / f"inventory-{index}.json"
            path.write_text(json.dumps(payload), encoding="utf-8")
            paths.append(path)
        return tuple(paths)  # type: ignore[return-value]


if __name__ == "__main__":
    unittest.main()
