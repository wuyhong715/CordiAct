#!/usr/bin/env python3
"""Reject debug-only components in the merged manifest and packaged Android code."""
import argparse
import re
import sys
import zipfile
from pathlib import Path
from xml.etree import ElementTree

PROBES = ("AutomationProbeReceiver", "FollowLifecycleProbeActivity")
DEX = re.compile(r"classes(?:[2-9]|[1-9][0-9]+)?\.dex\Z")


def scan(member, label, magic, encodings):
    markers = tuple((name, name.encode(encoding)) for name in PROBES for encoding in encodings)
    overlap = max(len(marker) for _, marker in markers) - 1
    header = member.read(8)
    if not magic(header):
        raise ValueError(f"missing or invalid header in {label}")
    tail = header
    while block := member.read(64 * 1024):
        payload = tail + block
        for name, marker in markers:
            if marker in payload:
                raise ValueError(f"{label} contains debug-only probe {name}")
        tail = payload[-overlap:]
    # Read through EOF so zipfile also verifies each inspected member's CRC.


def verify(manifest, apk):
    root = ElementTree.parse(manifest).getroot()
    if root.tag != "manifest" or root.find("application") is None:
        raise ValueError("invalid merged Android manifest")
    for element in root.iter():
        if any(name in value for value in element.attrib.values() for name in PROBES):
            raise ValueError("merged release manifest contains a debug-only probe")
    with zipfile.ZipFile(apk) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise ValueError("APK contains duplicate member names")
        if "AndroidManifest.xml" not in names or "classes.dex" not in names:
            raise ValueError("APK is missing its manifest or primary DEX")
        with archive.open("AndroidManifest.xml") as member:
            scan(member, "APK manifest", lambda h: len(h) == 8 and h[:4] == b"\x03\x00\x08\x00", ("ascii", "utf-16le"))
        for name in names:
            if DEX.fullmatch(name):
                with archive.open(name) as member:
                    scan(member, name, lambda h: re.fullmatch(rb"dex\n[0-9]{3}\x00", h), ("ascii",))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", type=Path)
    parser.add_argument("apk", type=Path)
    args = parser.parse_args()
    try:
        verify(args.manifest, args.apk)
    except (OSError, ValueError, RuntimeError, ElementTree.ParseError, zipfile.BadZipFile) as error:
        print(f"Release probe verification failed: {error}", file=sys.stderr)
        return 1
    print("Release manifest and DEX files contain no debug-only probes")
    return 0


if __name__ == "__main__":
    sys.exit(main())
