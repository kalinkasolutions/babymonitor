#!/usr/bin/env python3
"""Check that a downloaded APK was built from the source you have.

The release is built by a workflow whose output is reproducible: the same commit, built again,
produces the same bytes. So anyone can check the published APK against a build of their own
rather than taking the release page's word for it.

    git checkout <the commit the APK says it is>
    cd android && ./gradlew clean assembleRelease
    ./verify-apk.py ~/Downloads/babymonitor-0.2.apk

What is compared is every entry inside the two archives — the compiled code, the resources, the
native libraries — and not the files as a whole, because the published APK carries a signature
block that a local build has no way to reproduce and should not. That block is the signature, and
`apksigner verify --print-certs` is what checks it. This checks the other half: that what was
signed is what the source builds.
"""

from __future__ import annotations

import sys
import zipfile
from pathlib import Path

# Where Gradle leaves the unsigned build, relative to this script.
REBUILD = Path(__file__).parent / "app/build/outputs/apk/release/app-release-unsigned.apk"


def entries(path: Path) -> dict[str, tuple[int, int, int]]:
    """Every entry by name, with the checksum and size of what is in it."""
    with zipfile.ZipFile(path) as archive:
        return {
            item.filename: (item.CRC, item.file_size, item.compress_type)
            for item in archive.infolist()
        }


def main() -> int:
    if len(sys.argv) not in (2, 3):
        print(f"usage: {sys.argv[0]} <downloaded.apk> [rebuilt.apk]", file=sys.stderr)
        return 2

    published = Path(sys.argv[1])
    rebuilt = Path(sys.argv[2]) if len(sys.argv) == 3 else REBUILD

    for path in (published, rebuilt):
        if not path.is_file():
            print(f"Not there: {path}", file=sys.stderr)
            if path == REBUILD:
                print("Build it first: ./gradlew clean assembleRelease", file=sys.stderr)
            return 2

    theirs, ours = entries(published), entries(rebuilt)

    missing = sorted(set(theirs) - set(ours))
    extra = sorted(set(ours) - set(theirs))
    changed = sorted(name for name in set(theirs) & set(ours) if theirs[name] != ours[name])

    if not (missing or extra or changed):
        print(f"Match. All {len(theirs)} entries in {published.name} are the ones this source builds.")
        print("\nThat is what was signed. Who signed it is a separate question:")
        print(f"    apksigner verify --print-certs {published.name}")
        return 0

    print(f"MISMATCH. {published.name} is not what this source builds.\n")
    for label, names in (
        ("only in the download", missing),
        ("only in the rebuild", extra),
        ("different contents", changed),
    ):
        if names:
            print(f"  {label} ({len(names)}):")
            for name in names[:20]:
                print(f"    {name}")
            if len(names) > 20:
                print(f"    ... and {len(names) - 20} more")
            print()

    print("Innocent explanations first: a different commit, a different Gradle or JDK version,")
    print("or local edits. Check those before concluding anything about the download.")
    return 1


if __name__ == "__main__":
    sys.exit(main())
