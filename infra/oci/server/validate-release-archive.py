#!/usr/bin/env python3
"""Extract a bounded release archive into an empty private directory."""

from __future__ import annotations

import gzip
import os
import pathlib
import shutil
import stat
import sys
import tarfile

MAX_ENTRIES = 1_000
MAX_FILE_BYTES = 512 * 1024 * 1024
MAX_PATH_BYTES = 4_096
MAX_TOTAL_BYTES = 1024 * 1024 * 1024
MAX_TAR_STREAM_BYTES = MAX_TOTAL_BYTES + MAX_ENTRIES * (MAX_PATH_BYTES + 1024)
TAR_BLOCK_BYTES = 512


def fail(message: str) -> None:
    raise SystemExit(f"geuneul archive validation error: {message}")


def normalized_parts(name: str, is_directory: bool) -> tuple[str, ...]:
    if not name or "\x00" in name or len(name.encode("utf-8")) > MAX_PATH_BYTES:
        fail("unsafe archive path")
    path = pathlib.PurePosixPath(name)
    if path.is_absolute() or ".." in path.parts:
        fail("unsafe archive path")
    parts = tuple(part for part in path.parts if part not in ("", "."))
    if not parts and not is_directory:
        fail("unsafe archive path")
    return parts


def ensure_directory(root: pathlib.Path, parts: tuple[str, ...]) -> pathlib.Path:
    current = root
    for part in parts:
        current = current / part
        try:
            current.mkdir(mode=0o755)
        except FileExistsError:
            metadata = current.lstat()
            if not stat.S_ISDIR(metadata.st_mode) or stat.S_ISLNK(metadata.st_mode):
                fail("archive path collides with a non-directory")
    return current


class BoundedReader:
    def __init__(self, raw: gzip.GzipFile) -> None:
        self.raw = raw
        self.count = 0

    def read(self, size: int) -> bytes:
        chunk = self.raw.read(size)
        self.count += len(chunk)
        if self.count > MAX_TAR_STREAM_BYTES:
            fail("decompressed tar stream limit exceeded")
        return chunk


def read_exact(reader: BoundedReader, size: int) -> None:
    remaining = size
    while remaining:
        chunk = reader.read(min(remaining, 1024 * 1024))
        if not chunk:
            fail("truncated archive")
        remaining -= len(chunk)


def prevalidate_tar_stream(archive_name: str) -> None:
    seen: set[tuple[str, ...]] = set()
    count = 0
    total = 0
    with gzip.open(archive_name, "rb") as compressed:
        reader = BoundedReader(compressed)
        zero_blocks = 0
        while True:
            header = reader.read(TAR_BLOCK_BYTES)
            if not header:
                fail("archive has no complete end marker")
            if len(header) != TAR_BLOCK_BYTES:
                fail("truncated archive header")
            if header == bytes(TAR_BLOCK_BYTES):
                zero_blocks += 1
                if zero_blocks < 2:
                    continue
                while True:
                    trailing = reader.read(1024 * 1024)
                    if not trailing:
                        return
                    if any(trailing):
                        fail("archive has non-zero data after its end marker")
            elif zero_blocks:
                fail("archive has an incomplete end marker")

            try:
                member = tarfile.TarInfo.frombuf(header, encoding="utf-8", errors="surrogateescape")
            except tarfile.HeaderError as error:
                fail(f"invalid tar header: {error}")
            count += 1
            if count > MAX_ENTRIES:
                fail("entry limit exceeded")
            if not (member.isfile() or member.isdir()):
                fail("archive contains an extension or non-regular entry")
            parts = normalized_parts(member.name, member.isdir())
            if parts in seen:
                fail("archive contains duplicate paths")
            seen.add(parts)
            if member.isdir():
                if member.size != 0:
                    fail("archive directory has unexpected content")
            else:
                if member.size < 0 or member.size > MAX_FILE_BYTES:
                    fail("individual file limit exceeded")
                total += member.size
                if total > MAX_TOTAL_BYTES:
                    fail("uncompressed archive limit exceeded")
            padded_size = ((member.size + TAR_BLOCK_BYTES - 1) // TAR_BLOCK_BYTES) * TAR_BLOCK_BYTES
            read_exact(reader, padded_size)


def main(archive_name: str, destination_name: str) -> None:
    destination_input = pathlib.Path(destination_name)
    metadata = destination_input.lstat()
    if not stat.S_ISDIR(metadata.st_mode) or stat.S_ISLNK(metadata.st_mode):
        fail("destination must be a regular directory")
    if any(destination_input.iterdir()):
        fail("destination must be empty")

    prevalidate_tar_stream(archive_name)
    destination = destination_input.resolve(strict=True)
    with tarfile.open(archive_name, "r|gz") as archive:
        for member in archive:
            if not (member.isfile() or member.isdir()) or member.issym() or member.islnk():
                fail("archive contains a non-regular entry")
            parts = normalized_parts(member.name, member.isdir())
            if member.isdir():
                ensure_directory(destination, parts)
                continue
            parent = ensure_directory(destination, parts[:-1])
            target = parent / parts[-1]
            source = archive.extractfile(member)
            if source is None:
                fail("regular archive file has no content")
            flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL
            if hasattr(os, "O_NOFOLLOW"):
                flags |= os.O_NOFOLLOW
            mode = 0o755 if member.mode & 0o111 else 0o644
            descriptor = os.open(target, flags, mode)
            with source, os.fdopen(descriptor, "wb") as output:
                shutil.copyfileobj(source, output, length=1024 * 1024)
            if target.stat().st_size != member.size:
                fail("archive file size changed during extraction")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        fail("usage: validate-release-archive.py ARCHIVE DESTINATION")
    main(sys.argv[1], sys.argv[2])
