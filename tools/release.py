#!/usr/bin/env python3
"""The version and changelog steps of a release, for `just release` and the Release workflow.

Usage:
  tools/release.py version          print the current versionName
  tools/release.py bump VERSION     check CHANGELOG.md has changes for VERSION, date its section, and set the version
  tools/release.py notes VERSION    print VERSION's changelog section, the GitHub release notes

VERSION is MAJOR.MINOR.PATCH. bump takes the changes from VERSION's own section if there is one, otherwise it turns
[Unreleased] into VERSION's section. It changes files only when every check passes, and never touches git.
"""
import datetime
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CHANGELOG = ROOT / "CHANGELOG.md"
GRADLE = ROOT / "app" / "build.gradle.kts"
REPO_URL = "https://github.com/keithvassallomt/nc-media-provider"
CATEGORIES = ("Added", "Changed", "Deprecated", "Removed", "Fixed", "Security")

SEMVER = re.compile(r"(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)")
HEADING = re.compile(r"## \[([^\]]+)\](?: - (\d{4}-\d{2}-\d{2}))?\s*")
LINK = re.compile(r"\[[^\]]+\]: \S+\s*")
ITEM = re.compile(r"\s*[-*] \S")
VERSION_CODE = re.compile(r"^(\s*versionCode = )(\d+)$", re.M)
VERSION_NAME = re.compile(r'^(\s*versionName = ")([^"]+)(")$', re.M)


class ReleaseError(Exception):
    pass


class Section:
    def __init__(self, name, date, lines):
        self.name, self.date, self.lines = name, date, lines

    def items(self):
        return [line for line in self.lines if ITEM.match(line)]


def semver(text):
    m = SEMVER.fullmatch(text)
    if not m:
        raise ReleaseError(f"{text!r} isn't a version: use MAJOR.MINOR.PATCH, such as 1.5.0.")
    return tuple(int(part) for part in m.groups())


def parse_changelog():
    """Split CHANGELOG.md into the text before the first section and the sections. The link references are dropped:
    write_changelog makes them again from the headings."""
    lines = CHANGELOG.read_text(encoding="utf-8").splitlines()
    end = len(lines)
    while end and (not lines[end - 1].strip() or LINK.fullmatch(lines[end - 1])):
        end -= 1
    starts = [i for i in range(end) if lines[i].startswith("## ")]
    sections = []
    for n, start in enumerate(starts):
        m = HEADING.fullmatch(lines[start])
        if not m:
            raise ReleaseError(f"CHANGELOG.md line {start + 1} should be '## [VERSION] - YYYY-MM-DD': {lines[start]}")
        stop = starts[n + 1] if n + 1 < len(starts) else end
        sections.append(Section(m.group(1), m.group(2), lines[start + 1:stop]))
    return lines[:starts[0] if starts else end], sections


def write_changelog(preamble, sections):
    out = list(preamble)
    for s in sections:
        out.append(f"## [{s.name}]" + (f" - {s.date}" if s.date else ""))
        out += s.lines
    while out and not out[-1].strip():
        out.pop()
    released = [s.name for s in sections if s.name != "Unreleased"]
    links = []
    if any(s.name == "Unreleased" for s in sections):
        target = f"compare/v{released[0]}...HEAD" if released else "commits/main"
        links.append(f"[unreleased]: {REPO_URL}/{target}")
    for n, name in enumerate(released):
        target = f"compare/v{released[n + 1]}...v{name}" if n + 1 < len(released) else f"releases/tag/v{name}"
        links.append(f"[{name}]: {REPO_URL}/{target}")
    CHANGELOG.write_text("\n".join(out + [""] + links) + "\n", encoding="utf-8")


def check_categories(section):
    for line in section.lines:
        if line.startswith("### ") and line[4:].strip() not in CATEGORIES:
            raise ReleaseError(f"CHANGELOG.md [{section.name}] has '{line.strip()}': "
                               f"use one of {', '.join(CATEGORIES)}.")


def read_gradle():
    text = GRADLE.read_text(encoding="utf-8")
    codes, names = VERSION_CODE.findall(text), VERSION_NAME.findall(text)
    if len(codes) != 1 or len(names) != 1:
        raise ReleaseError(f"Expected one versionCode and one versionName in {GRADLE.relative_to(ROOT)}.")
    return text, int(codes[0][1]), names[0][1]


def bump(version):
    new = semver(version)
    text, code, current = read_gradle()
    if new < semver(current):
        raise ReleaseError(f"{version} is older than the current version, {current}.")

    preamble, sections = parse_changelog()
    unreleased = next((s for s in sections if s.name == "Unreleased"), None)
    section = next((s for s in sections if s.name == version), None)
    for s in sections:
        if s is not section and s.name != "Unreleased" and semver(s.name) >= new:
            raise ReleaseError(f"CHANGELOG.md already has {s.name}, which isn't older than {version}.")
    if section:
        if unreleased and unreleased.items():
            raise ReleaseError(f"CHANGELOG.md lists changes under both [Unreleased] and [{version}]: "
                               f"move them into [{version}].")
    elif unreleased and unreleased.items():
        section = Section(version, None, unreleased.lines)
        unreleased.lines = [""]
        sections.insert(sections.index(unreleased) + 1, section)
    if not section or not section.items():
        raise ReleaseError(f"CHANGELOG.md has no changes for {version}: list them under ## [Unreleased].")
    check_categories(section)

    section.date = datetime.date.today().isoformat()
    write_changelog(preamble, sections)
    count = len(section.items())
    print(f"CHANGELOG.md: [{version}] - {section.date}, {count} change{'' if count == 1 else 's'}")
    if new > semver(current):
        text = VERSION_CODE.sub(lambda m: f"{m.group(1)}{code + 1}", text)
        text = VERSION_NAME.sub(lambda m: f"{m.group(1)}{version}{m.group(3)}", text)
        GRADLE.write_text(text, encoding="utf-8")
        print(f"{GRADLE.relative_to(ROOT)}: versionName {current} to {version}, versionCode {code} to {code + 1}")
    else:
        print(f"{GRADLE.relative_to(ROOT)}: already {version} (versionCode {code})")


def notes(version):
    semver(version)
    section = next((s for s in parse_changelog()[1] if s.name == version), None)
    if not section or not section.items():
        raise ReleaseError(f"CHANGELOG.md has no changes for {version}.")
    lines = list(section.lines)
    while lines and not lines[0].strip():
        lines.pop(0)
    while lines and not lines[-1].strip():
        lines.pop()
    print("\n".join(lines))


def main(args):
    if args == ["version"]:
        print(read_gradle()[2])
    elif len(args) == 2 and args[0] == "bump":
        bump(args[1])
    elif len(args) == 2 and args[0] == "notes":
        notes(args[1])
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    try:
        main(sys.argv[1:])
    except ReleaseError as e:
        sys.exit(f"error: {e}")
