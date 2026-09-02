#!/usr/bin/env python3
from __future__ import annotations

import hashlib
import json
import os
import sys
from pathlib import Path


def main() -> int:
    if len(sys.argv) != 3:
        raise SystemExit("usage: object-inventory.py OBJECT_ROOT OUTPUT_JSON")

    root = Path(sys.argv[1]).resolve(strict=True)
    output = Path(sys.argv[2])
    records: list[dict[str, object]] = []

    for directory, _, filenames in os.walk(root):
        for filename in filenames:
            path = Path(directory, filename)
            if path.is_symlink() or not path.is_file():
                raise RuntimeError(f"unsupported object path: {path}")
            relative = path.relative_to(root).as_posix()
            digest = hashlib.sha256()
            with path.open("rb") as handle:
                for chunk in iter(lambda: handle.read(1024 * 1024), b""):
                    digest.update(chunk)
            records.append({"key": relative, "size": path.stat().st_size, "sha256": digest.hexdigest()})

    records.sort(key=lambda item: str(item["key"]))
    output.write_text(json.dumps(records, ensure_ascii=False, separators=(",", ":")) + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

