"""EPV1 authenticated, lossless encrypted photo/video storage in PNG pixels.

Design: all content and identifying metadata are encrypted; only random file ID,
part number/count, cipher size, KDF salt/parameters, and nonce are visible.
This module never calls Google Photos or transmits a password.
"""
from __future__ import annotations

import hashlib
import json
import math
import mimetypes
import os
from pathlib import Path
import re
import secrets
import struct
import tempfile
from dataclasses import dataclass
from typing import Callable, Iterable

from argon2.low_level import Type, hash_secret_raw
from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from PIL import Image, UnidentifiedImageError

MAGIC = b'EPV1'
VERSION = 1
HEADER_STRUCT = struct.Struct('>4sBBH16sII16s12sIIII')
HEADER_LEN = HEADER_STRUCT.size  # 76 bytes
MAX_PART_BYTES = 8 * 1024 * 1024
MAX_META_BYTES = 4096
ARGON2_MEMORY_KIB = 64 * 1024
ARGON2_TIME_COST = 3
ARGON2_PARALLELISM = 1
MAX_TOTAL_PARTS = 1_000_000
PIXEL_LIMIT = 2048 * 2048


class VaultError(Exception):
    """A missing, invalid, corrupt, or unsupported vault item."""


@dataclass(frozen=True)
class Header:
    file_id: bytes
    part_index: int
    part_count: int
    salt: bytes
    nonce: bytes
    encrypted_length: int
    memory_kib: int
    time_cost: int
    parallelism: int
    raw: bytes

    @property
    def group_id(self) -> str:
        return self.file_id.hex()


def _password_bytes(password: str) -> bytes:
    if not isinstance(password, str) or not password:
        raise VaultError('Password must not be empty')
    return password.encode('utf-8')


def _derive_key(password: str, h: Header) -> bytes:
    if h.memory_kib != ARGON2_MEMORY_KIB or h.time_cost != ARGON2_TIME_COST or h.parallelism != ARGON2_PARALLELISM:
        raise VaultError('Unsupported Argon2id parameters')
    return hash_secret_raw(_password_bytes(password), h.salt,
                           time_cost=h.time_cost, memory_cost=h.memory_kib,
                           parallelism=h.parallelism, hash_len=32, type=Type.ID,
                           version=19)


def _unpack_header(data: bytes) -> Header:
    if len(data) < HEADER_LEN:
        raise VaultError('Too few bytes for EPV1 header')
    (magic, version, flags, hlen, fid, idx, count, salt, nonce, clen,
     mem, iterations, parallelism) = HEADER_STRUCT.unpack_from(data)
    if magic != MAGIC or version != VERSION or flags != 0 or hlen != HEADER_LEN:
        raise VaultError('Not a valid EPV1 container')
    if not 1 <= count <= MAX_TOTAL_PARTS or idx >= count:
        raise VaultError('Invalid part index/count')
    if not 16 <= clen <= MAX_PART_BYTES + MAX_META_BYTES + 4 + 16:
        raise VaultError('Invalid ciphertext size')
    if (mem, iterations, parallelism) != (ARGON2_MEMORY_KIB, ARGON2_TIME_COST, ARGON2_PARALLELISM):
        raise VaultError('Unsupported KDF parameters')
    return Header(fid, idx, count, salt, nonce, clen, mem, iterations, parallelism, data[:HEADER_LEN])


def _make_header(fid: bytes, idx: int, count: int, salt: bytes,
                 nonce: bytes, clen: int) -> Header:
    raw = HEADER_STRUCT.pack(MAGIC, VERSION, 0, HEADER_LEN, fid, idx, count,
                              salt, nonce, clen, ARGON2_MEMORY_KIB,
                              ARGON2_TIME_COST, ARGON2_PARALLELISM)
    return _unpack_header(raw)


def _png_size(byte_count: int) -> tuple[int, int]:
    for width in (512, 1024, 2048):
        height = max(512 if width == 512 else 256, math.ceil(byte_count / (width * 3)))
        if height <= width:
            return width, height
    raise VaultError('Chunk exceeds maximum image capacity')


def _write_png(payload: bytes, output: Path) -> None:
    width, height = _png_size(len(payload))
    padding = width * height * 3 - len(payload)
    pixel_data = payload + secrets.token_bytes(padding)
    im = Image.frombytes('RGB', (width, height), pixel_data)
    # No image metadata and no lossy conversion. PNG is a real RGB image.
    im.save(output, format='PNG', compress_level=0)
    im.close()


def _read_png(path: Path) -> bytes:
    try:
        with Image.open(path) as im:
            if im.format != 'PNG':
                raise VaultError(f'Not PNG: {path}')
            if im.width * im.height > PIXEL_LIMIT:
                raise VaultError(f'PNG too large: {path}')
            if im.mode != 'RGB':
                raise VaultError(f'PNG RGB pixels changed / unsupported mode: {path}')
            return im.tobytes()
    except (UnidentifiedImageError, OSError, ValueError) as exc:
        raise VaultError(f'Cannot read PNG {path}: {exc}') from exc


def _header_from_file(path: Path) -> Header | None:
    try:
        with Image.open(path) as im:
            if im.format != 'PNG' or im.width * im.height > PIXEL_LIMIT or im.mode != 'RGB':
                return None
            raw = im.tobytes()
        if not raw.startswith(MAGIC):
            return None
        return _unpack_header(raw)
    except (UnidentifiedImageError, OSError, ValueError, VaultError):
        return None


def _sha256_file(path: Path) -> tuple[int, str]:
    digest = hashlib.sha256()
    size = 0
    with path.open('rb') as f:
        for buf in iter(lambda: f.read(1024 * 1024), b''):
            digest.update(buf)
            size += len(buf)
    return size, digest.hexdigest()


def encrypt_file(source: str | Path, destination: str | Path, password: str,
                 progress: Callable[[int, int], None] | None = None) -> list[Path]:
    """Encrypt one file into independent PNG parts. Roll back generated files on failure."""
    _password_bytes(password)
    src, out = Path(source), Path(destination)
    if not src.is_file():
        raise VaultError(f'No such file: {src}')
    out.mkdir(parents=True, exist_ok=True)
    size, digest = _sha256_file(src)
    meta = {'n': src.name, 's': size, 'h': digest,
            'm': mimetypes.guess_type(src.name)[0] or 'application/octet-stream'}
    meta_json = json.dumps(meta, ensure_ascii=False, separators=(',', ':')).encode('utf-8')
    if len(meta_json) > MAX_META_BYTES:
        raise VaultError('File name/metadata too large')
    prefix = len(meta_json).to_bytes(4, 'big') + meta_json
    count = max(1, math.ceil(size / MAX_PART_BYTES))
    if count > MAX_TOTAL_PARTS:
        raise VaultError('Too many PNG parts')
    fid, salt = secrets.token_bytes(16), secrets.token_bytes(16)
    sample = _make_header(fid, 0, count, salt, secrets.token_bytes(12), 16)
    key = _derive_key(password, sample)
    cipher = AESGCM(key)
    created: list[Path] = []
    try:
        with src.open('rb') as f:
            for idx in range(count):
                chunk = f.read(MAX_PART_BYTES)
                plain = prefix + chunk
                nonce = secrets.token_bytes(12)
                h = _make_header(fid, idx, count, salt, nonce, len(plain) + 16)
                blob = h.raw + cipher.encrypt(nonce, plain, h.raw)
                target = out / f'EPV1_{fid.hex()}_{idx + 1:06d}.png'
                if target.exists():
                    raise VaultError(f'Refusing to overwrite existing PNG: {target}')
                # Write to a temporary file first, then publish an atomic finished PNG.
                with tempfile.NamedTemporaryFile(prefix='.epv-', suffix='.png', dir=out, delete=False) as tf:
                    temp = Path(tf.name)
                try:
                    _write_png(blob, temp)
                    os.replace(temp, target)
                finally:
                    temp.unlink(missing_ok=True)
                created.append(target)
                if progress:
                    progress(idx + 1, count)
        return created
    except BaseException:
        for p in created:
            p.unlink(missing_ok=True)
        raise


def inspect_files(files: Iterable[str | Path]) -> dict[str, list[tuple[Path, Header]]]:
    """Collect EPV1 images; silently skip unrelated images."""
    groups: dict[str, list[tuple[Path, Header]]] = {}
    for name in files:
        path = Path(name)
        header = _header_from_file(path)
        if header is not None:
            groups.setdefault(header.group_id, []).append((path, header))
    return groups


def _safe_filename(name: object) -> str:
    if not isinstance(name, str) or not name or name != Path(name).name:
        raise VaultError('Unsafe recovered file name')
    clean = re.sub(r'[<>:"/\\|?*\x00-\x1f]', '_', name)
    clean = clean.strip(' .')
    if not clean or clean in ('.', '..'):
        raise VaultError('Unsafe recovered file name')
    return clean[:240]


def _restore_group(entries: list[tuple[Path, Header]], out: Path, password: str,
                   progress: Callable[[int, int], None] | None) -> Path:
    first = entries[0][1]
    if len(entries) != first.part_count:
        raise VaultError(f'{first.group_id}: incomplete set ({len(entries)}/{first.part_count})')
    seen: set[int] = set()
    for _, h in entries:
        if (h.file_id != first.file_id or h.part_count != first.part_count or
            h.salt != first.salt or h.memory_kib != first.memory_kib or
            h.time_cost != first.time_cost or h.parallelism != first.parallelism):
            raise VaultError('Inconsistent file group headers')
        if h.part_index in seen:
            raise VaultError('Duplicate part index')
        seen.add(h.part_index)
    if seen != set(range(first.part_count)):
        raise VaultError('Missing chunk(s)')
    entries = sorted(entries, key=lambda entry: entry[1].part_index)
    key = _derive_key(password, first)
    cipher = AESGCM(key)
    out.mkdir(parents=True, exist_ok=True)
    filename: str | None = None
    reference_meta: dict | None = None
    received = 0
    digest = hashlib.sha256()
    with tempfile.NamedTemporaryFile(prefix='.epv-restore-', dir=out,
                                     delete=False) as tmp:
        temp = Path(tmp.name)
    try:
        with temp.open('wb') as dst:
            for pos, (path, h) in enumerate(entries):
                raw = _read_png(path)
                if raw[:HEADER_LEN] != h.raw:
                    raise VaultError(f'PNG changed during restore: {path}')
                encrypted = raw[HEADER_LEN:HEADER_LEN + h.encrypted_length]
                if len(encrypted) != h.encrypted_length:
                    raise VaultError(f'Truncated encrypted chunk: {path}')
                try:
                    plain = cipher.decrypt(h.nonce, encrypted, h.raw)
                except InvalidTag as exc:
                    raise VaultError(f'Wrong password or modified PNG: {path.name}') from exc
                if len(plain) < 4:
                    raise VaultError('Truncated metadata')
                ml = int.from_bytes(plain[:4], 'big')
                if not 1 <= ml <= MAX_META_BYTES or len(plain) < 4 + ml:
                    raise VaultError('Invalid metadata size')
                try:
                    meta = json.loads(plain[4:4 + ml].decode('utf-8'))
                except (ValueError, UnicodeError) as exc:
                    raise VaultError('Invalid encrypted metadata') from exc
                if not isinstance(meta, dict) or not isinstance(meta.get('s'), int) or meta['s'] < 0 or not isinstance(meta.get('h'), str):
                    raise VaultError('Invalid metadata structure')
                if reference_meta is None:
                    reference_meta = meta
                    filename = _safe_filename(meta.get('n'))
                elif meta != reference_meta:
                    raise VaultError('Inconsistent encrypted metadata')
                chunk = plain[4 + ml:]
                received += len(chunk)
                if received > reference_meta['s']:
                    raise VaultError('Recovered data exceeds expected size')
                digest.update(chunk)
                dst.write(chunk)
                if progress:
                    progress(pos + 1, len(entries))
        if reference_meta is None or received != reference_meta['s'] or digest.hexdigest() != reference_meta['h']:
            raise VaultError('Recovered file SHA-256/length mismatch')
        assert filename is not None
        result = out / filename
        if result.exists():
            result = out / f'{Path(filename).stem}_{first.group_id[:8]}{Path(filename).suffix}'
            if result.exists():
                raise VaultError(f'Refusing to overwrite: {result}')
        os.replace(temp, result)
        return result
    finally:
        temp.unlink(missing_ok=True)


def restore_files(files: Iterable[str | Path], destination: str | Path, password: str,
                  progress: Callable[[int, int], None] | None = None) -> list[Path]:
    _password_bytes(password)
    groups = inspect_files(files)
    if not groups:
        raise VaultError('No EPV1 PNG images found')
    out = Path(destination)
    results: list[Path] = []
    for fid, entries in sorted(groups.items()):
        try:
            results.append(_restore_group(entries, out, password, progress))
        except VaultError as exc:
            raise VaultError(f'Cannot restore {fid}: {exc}') from exc
    return results


def restore_directory(source: str | Path, destination: str | Path, password: str,
                      progress: Callable[[int, int], None] | None = None) -> list[Path]:
    folder = Path(source)
    if not folder.is_dir():
        raise VaultError(f'No such directory: {folder}')
    return restore_files(folder.rglob('*.png'), destination, password, progress)


def compare_png_sets(original: str | Path, downloaded: str | Path) -> tuple[int, int]:
    """Compare EPV PNG RGB pixels before and after a cloud round trip.

    PNG file hashes may change when non-pixel metadata changes. This comparison
    checks the actual bytes used by the EPV1 decoder, keyed by embedded file ID
    and chunk index (not filename).
    """
    def index(folder: str | Path) -> dict[tuple[str, int], Path]:
        directory = Path(folder)
        if not directory.is_dir():
            raise VaultError(f'Not a directory: {directory}')
        found: dict[tuple[str, int], Path] = {}
        for group, entries in inspect_files(p for p in directory.rglob('*') if p.is_file() and p.suffix.lower() == '.png').items():
            for path, h in entries:
                key = (group, h.part_index)
                if key in found:
                    raise VaultError(f'Duplicate EPV part {key} in {directory}')
                found[key] = path
        return found
    before, after = index(original), index(downloaded)
    if not before:
        raise VaultError('No original EPV PNGs')
    missing = sorted(before.keys() - after.keys())
    extra = sorted(after.keys() - before.keys())
    if missing or extra:
        raise VaultError(f'PNG sets differ: missing={missing[:5]}, extra={extra[:5]}')
    for k, source in before.items():
        target = after[k]
        if hashlib.sha256(_read_png(source)).digest() != hashlib.sha256(_read_png(target)).digest():
            raise VaultError(f'RGB pixels changed in {k}: {target}')
    return len(before), len({key[0] for key in before})
