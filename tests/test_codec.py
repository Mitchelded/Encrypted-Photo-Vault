from pathlib import Path
import hashlib
import random

import pytest
from PIL import Image

from epv.codec import encrypt_file, restore_files, inspect_files, VaultError, MAX_PART_BYTES


@pytest.mark.parametrize('size', [0, 1, 13000, 600000, MAX_PART_BYTES + 33])
def test_round_trip(tmp_path, size):
    content = random.Random(size).randbytes(size)
    src = tmp_path / 'данные' / 'клип.mp4'
    src.parent.mkdir()
    src.write_bytes(content)
    parts = encrypt_file(src, tmp_path / 'images', 'test 🤫 пароль')
    assert len(parts) == max(1, (size + MAX_PART_BYTES - 1) // MAX_PART_BYTES)
    assert all(Image.open(p).format == 'PNG' for p in parts)
    rng = random.Random(9)
    rng.shuffle(parts)
    result = restore_files(parts, tmp_path / 'recovered', 'test 🤫 пароль')
    assert len(result) == 1
    assert result[0].name == src.name
    assert result[0].read_bytes() == content


def test_randomization(tmp_path):
    original = tmp_path / 'original.jpg'
    original.write_bytes(b'hello' * 1000)
    a = encrypt_file(original, tmp_path / 'a', 'password')
    b = encrypt_file(original, tmp_path / 'b', 'password')
    assert hashlib.sha256(a[0].read_bytes()).digest() != hashlib.sha256(b[0].read_bytes()).digest()
    assert Image.open(a[0]).tobytes() != Image.open(b[0]).tobytes()
    assert b'hello' not in a[0].read_bytes()


def test_bad_password_does_not_write(tmp_path):
    original = tmp_path / 'file.txt'
    original.write_bytes(b'secret')
    imgs = encrypt_file(original, tmp_path / 'imgs', 'password1')
    with pytest.raises(VaultError, match='Wrong password'):
        restore_files(imgs, tmp_path / 'restored', 'password2')
    assert not list((tmp_path / 'restored').iterdir())


def test_missing_chunk(tmp_path):
    original = tmp_path / 'video.mp4'
    original.write_bytes(b'v' * (MAX_PART_BYTES + 2))
    imgs = encrypt_file(original, tmp_path / 'imgs', 'secret')
    assert len(imgs) == 2
    with pytest.raises(VaultError, match='incomplete'):
        restore_files(imgs[:1], tmp_path / 'out', 'secret')


def test_duplicate_chunk(tmp_path):
    original = tmp_path / 'a'
    original.write_bytes(b'a' * 10)
    imgs = encrypt_file(original, tmp_path / 'imgs', 'secret')
    with pytest.raises(VaultError, match='incomplete|Duplicate'):
        restore_files(imgs * 2, tmp_path / 'out', 'secret')


def test_modified_pixel(tmp_path):
    original = tmp_path / 'data.bin'
    original.write_bytes(b'X' * 1000)
    image = encrypt_file(original, tmp_path / 'imgs', 'secret')[0]
    with Image.open(image) as im:
        b = bytearray(im.tobytes())
        b[200] ^= 1
        Image.frombytes('RGB', im.size, bytes(b)).save(image)
    with pytest.raises(VaultError, match='modified PNG'):
        restore_files([image], tmp_path / 'out', 'secret')


def test_unrelated_png_ignored(tmp_path):
    png = tmp_path / 'regular.png'
    Image.new('RGB', (32, 32), 'red').save(png)
    assert not inspect_files([png])


def test_restore_name_collision(tmp_path):
    f = tmp_path / 'data.txt'
    f.write_text('hello')
    imgs = encrypt_file(f, tmp_path / 'imgs', 'pw')
    out = tmp_path / 'out'
    first = restore_files(imgs, out, 'pw')
    second = restore_files(imgs, out, 'pw')
    assert first[0].name != second[0].name
    assert first[0].read_bytes() == second[0].read_bytes()


def test_cloudcheck_roundtrip(tmp_path):
    from epv.codec import compare_png_sets
    orig = tmp_path / 'a'
    download = tmp_path / 'b'
    download.mkdir()
    f = tmp_path / 'sample.jpg'
    f.write_bytes(b'Photo content' * 100)
    imgs = encrypt_file(f, orig, 'secret')
    import shutil
    copied = download / 'renamed.png'
    shutil.copyfile(imgs[0], copied)
    assert compare_png_sets(orig, download) == (1, 1)
    with Image.open(copied) as im:
        data = bytearray(im.tobytes())
        data[200] ^= 1
        Image.frombytes('RGB', im.size, bytes(data)).save(copied)
    with pytest.raises(VaultError, match='RGB pixels changed'):
        compare_png_sets(orig, download)
