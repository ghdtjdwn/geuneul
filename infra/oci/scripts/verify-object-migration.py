#!/usr/bin/env python3
from __future__ import annotations

import json
import sys
from pathlib import Path


def load_aws(path: Path) -> dict[str, int]:
    payload = json.loads(path.read_text(encoding="utf-8"))
    return {item["Key"]: int(item["Size"]) for item in (payload or [])}


def load_local(path: Path) -> dict[str, tuple[int, str]]:
    payload = json.loads(path.read_text(encoding="utf-8"))
    return {item["key"]: (int(item["size"]), item["sha256"]) for item in payload}


def main() -> int:
    if len(sys.argv) != 5:
        raise SystemExit(
            "usage: verify-object-migration.py SOURCE_S3.json TARGET_S3.json SOURCE_LOCAL.json TARGET_LOCAL.json"
        )

    source_s3 = load_aws(Path(sys.argv[1]))
    target_s3 = load_aws(Path(sys.argv[2]))
    source_local = load_local(Path(sys.argv[3]))
    target_local = load_local(Path(sys.argv[4]))

    expected_source = {key: size for key, (size, _) in source_local.items()}
    expected_target = {key: size for key, (size, _) in target_local.items()}
    if source_s3 != expected_source:
        raise RuntimeError("AWS inventory does not match the downloaded source files")
    if target_s3 != expected_target:
        raise RuntimeError("OCI inventory does not match the verification download")
    if source_local != target_local:
        raise RuntimeError("source and target object size/SHA-256 inventories differ")

    total_bytes = sum(size for size, _ in source_local.values())
    print(f"verified {len(source_local)} objects and {total_bytes} bytes with SHA-256")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
