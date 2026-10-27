"""
Prepare release notes from android/release-notes-template.md.

Fills {{VERSION}}, {{PREVIOUS}} and {{CORE_VERSION}} from the code and git tags;
{{SUMMARY}}, {{CHANGES}}, {{SUMMARY_EN}} and {{CHANGES_EN}} are left for you to write.

    python android/tools/release_notes.py            # writes android/build/release-notes.md
    python android/tools/release_notes.py --check FILE

--check exits with an error if FILE still contains {{...}} placeholders; the
release workflow runs it on the tag message before publishing.
"""
from __future__ import annotations

import argparse
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
TEMPLATE = ROOT / "android" / "release-notes-template.md"
DEFAULT_OUT = ROOT / "android" / "build" / "release-notes.md"
PLACEHOLDER = re.compile(r"\{\{[A-Z_]+\}\}")


def _read(pattern: str, path: Path) -> str:
    match = re.search(pattern, path.read_text(encoding="utf-8"), re.M)
    if not match:
        sys.exit(f"Cannot find {pattern!r} in {path}")
    return match.group(1)


def current_version() -> tuple[str, str]:
    """(version name, upstream core version), as build.gradle.kts computes them."""
    core = _read(r'^__version__\s*=\s*"([^"]+)"', ROOT / "proxy" / "__init__.py")
    revision = _read(r"^val androidRevision = (\d+)", ROOT / "android" / "app" / "build.gradle.kts")
    return f"{core}-android.{revision}", core


def previous_version(version: str) -> str:
    tags = subprocess.run(
        ["git", "tag", "--list", "android-v*", "--sort=-v:refname"],
        cwd=ROOT, capture_output=True, text=True, check=True,
    ).stdout.split()
    older = [t.removeprefix("android-v") for t in tags if t != f"android-v{version}"]
    if not older:
        sys.exit("No earlier android-v* tag found")
    return older[0]


def check(path: Path) -> int:
    text = path.read_text(encoding="utf-8")
    if not text.strip():
        print(f"{path}: empty; tag with `git tag -a ... -F notes.md`", file=sys.stderr)
        return 1
    left = sorted(set(PLACEHOLDER.findall(text)))
    if left:
        print(f"{path}: fill in {', '.join(left)}", file=sys.stderr)
        return 1
    print(f"{path}: no placeholders left")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("-o", "--output", type=Path, default=DEFAULT_OUT)
    ap.add_argument("--check", type=Path, metavar="FILE",
                    help="fail if FILE still has {{...}} placeholders")
    args = ap.parse_args()
    if args.check:
        return check(args.check)

    version, core = current_version()
    notes = (TEMPLATE.read_text(encoding="utf-8")
             .replace("{{VERSION}}", version)
             .replace("{{PREVIOUS}}", previous_version(version))
             .replace("{{CORE_VERSION}}", core))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(notes, encoding="utf-8", newline="\n")
    left = sorted(set(PLACEHOLDER.findall(notes)))
    print(f"{args.output}: notes for {version}; write {', '.join(left)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
