#!/usr/bin/env python3
"""Verify the project license and source/build guide packaged in an actual APK."""
from pathlib import Path
import argparse
import zipfile

ROOT = Path(__file__).resolve().parents[4]
REQUIRED_ASSETS = {
    "assets/LICENSE": ROOT / "LICENSE",
    "assets/AUDIO_SOURCE.md": ROOT / "docs/AUDIO_SOURCE.md",
}


def verify(apk):
    with zipfile.ZipFile(apk) as package:
        for asset, source in REQUIRED_ASSETS.items():
            if package.read(asset) != source.read_bytes():
                raise ValueError(f"{apk}: {asset} is incomplete or stale")
    print(f"PASS {apk}: verbatim project license and source/build guide")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path, nargs="+")
    for apk in parser.parse_args().apk:
        verify(apk)
