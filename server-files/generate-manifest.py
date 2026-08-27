#!/usr/bin/env python3
"""
Generate manifest.json from a directory of files.

Usage:
    python3 generate-manifest.py \
        --client-version 1.0.0 \
        --cache-version 2026-05-24 \
        --release-tag v1.0.0 \
        --files Kryos.jar:CLIENT cache/main_file_cache.dat2:CACHE \
        > manifest.json

Each --files entry is "path:TYPE" where TYPE is one of:
    LAUNCHER, CLIENT, CACHE, RESOURCE

The script computes SHA-256 + file size for each file, and writes
GitHub Releases download URLs for the given --release-tag.
"""

import argparse
import hashlib
import json
import os
import sys

REPO = "Nexum038/kryos-launcher-files"


def sha256_of(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--launcher-version", default="1.0.0")
    parser.add_argument("--client-version", required=True)
    parser.add_argument("--cache-version", required=True)
    parser.add_argument("--release-tag", required=True,
                        help="GitHub release tag, e.g. v1.2.0")
    parser.add_argument("--repo", default=REPO)
    parser.add_argument("--files", nargs="+", required=True,
                        help="Files as path:TYPE (e.g. Kryos.jar:CLIENT)")
    args = parser.parse_args()

    files_data = []
    for spec in args.files:
        if ":" not in spec:
            print(f"ERROR: malformed --files entry: {spec}", file=sys.stderr)
            sys.exit(1)
        path, type_ = spec.rsplit(":", 1)
        if not os.path.exists(path):
            print(f"ERROR: file not found: {path}", file=sys.stderr)
            sys.exit(1)
        sha = sha256_of(path)
        size = os.path.getsize(path)
        name = os.path.basename(path)
        url = f"https://github.com/{args.repo}/releases/download/{args.release_tag}/{name}"
        files_data.append({
            "name": name,
            "type": type_.upper(),
            "url": url,
            "sha256": sha,
            "size": size,
        })

    manifest = {
        "launcherVersion": args.launcher_version,
        "clientVersion": args.client_version,
        "cacheVersion": args.cache_version,
        "files": files_data,
    }
    print(json.dumps(manifest, indent=2))


if __name__ == "__main__":
    main()
