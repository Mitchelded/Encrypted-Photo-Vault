"""EPV1 CLI: python -m epv encrypt ... / restore ... / inspect ..."""
from __future__ import annotations

import argparse
import getpass
import os
from pathlib import Path
import sys

from .codec import VaultError, compare_png_sets, encrypt_file, inspect_files, restore_files


def password_from_args(args: argparse.Namespace) -> str:
    if args.password_env:
        password = os.environ.get(args.password_env)
        if not password:
            raise VaultError(f'Environment variable {args.password_env!r} is empty/unset')
        return password
    return getpass.getpass('EPV password: ')


def main() -> int:
    p = argparse.ArgumentParser(prog='epv', description='Encrypt any file into Google Photos-compatible PNG pixels (EPV1)')
    sub = p.add_subparsers(dest='command', required=True)
    enc = sub.add_parser('encrypt', help='Encrypt files/directories into an output directory')
    enc.add_argument('sources', nargs='+', type=Path)
    enc.add_argument('-o', '--out', type=Path, required=True)
    enc.add_argument('--password-env', metavar='VARIABLE', help='Read password from environment instead of prompting')
    dec = sub.add_parser('restore', help='Restore encrypted files from an EPV PNG directory or listed PNGs')
    dec.add_argument('sources', nargs='+', type=Path)
    dec.add_argument('-o', '--out', type=Path, required=True)
    dec.add_argument('--password-env', metavar='VARIABLE')
    ins = sub.add_parser('inspect', help='List EPV groups / report missing parts; does not require password')
    ins.add_argument('sources', nargs='+', type=Path)
    cmp = sub.add_parser('cloudcheck', help='Compare EPV PNG pixels before/after Google Photos download')
    cmp.add_argument('original_dir', type=Path)
    cmp.add_argument('downloaded_dir', type=Path)
    args = p.parse_args()
    try:
        if args.command == 'cloudcheck':
            parts, files = compare_png_sets(args.original_dir, args.downloaded_dir)
            print(f'PASS: all {parts} EPV PNGs ({files} files) retain identical RGB pixels.')
            return 0
        paths = []
        for source in args.sources:
            if source.is_dir():
                if args.command == 'encrypt':
                    # Avoid encrypting existing output directory if it is nested under a source.
                    excluded = args.out.resolve()
                    paths += [x for x in source.rglob('*') if x.is_file() and x.resolve() != excluded and excluded not in x.resolve().parents]
                else:
                    paths += [p for p in source.rglob('*') if p.is_file() and p.suffix.lower() == '.png']
            else:
                paths.append(source)
        if args.command == 'inspect':
            groups = inspect_files(paths)
            if not groups:
                print('No EPV1 PNGs found.')
                return 1
            for fid, items in sorted(groups.items()):
                total = items[0][1].part_count
                indexes = {hdr.part_index for _, hdr in items}
                print(f'{fid}: parts={len(indexes)}/{total}, complete={len(indexes) == total}, duplicates={len(items)-len(indexes)}')
            return 0
        password = password_from_args(args)
        if args.command == 'encrypt':
            if not paths:
                raise VaultError('No input files')
            for path in paths:
                print(f'Encrypting {path}...')
                output = encrypt_file(path, args.out, password,
                                      lambda n, count: print(f'  PNG {n}/{count}'))
                print(f'  OK: {len(output)} PNG(s)')
        else:
            for path in restore_files(paths, args.out, password,
                                      lambda n, count: print(f'  Chunk {n}/{count}')):
                print(f'Restored: {path}')
        return 0
    except (VaultError, OSError, ValueError) as exc:
        print(f'ERROR: {exc}', file=sys.stderr)
        return 2


if __name__ == '__main__':
    raise SystemExit(main())
