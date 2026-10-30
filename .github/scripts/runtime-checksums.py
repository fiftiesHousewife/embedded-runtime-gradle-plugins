"""The runtime archive must be the same bytes for the same inputs, on every agent and for every release.

record <directory> <version> <directory> <version>: fails unless each target's runtime archive is identical at
both versions (the second from a clean build), then prints one line per target: the archive's SHA-256 and a
digest of its contents (each entry's name, type, mode and bytes), which tells a compression difference from a
difference in what was archived.

compare <file>...: fails unless every agent's record gives each target the same archive.
"""
import hashlib
import pathlib
import re
import sys
import tarfile
import zipfile

ARCHIVE = re.compile(r"^(?P<app>.+)-runtime-(?P<version>[^-]+)-(?P<target>[a-z]+-[a-z0-9]+)\.(?P<ext>tar\.gz|zip)$")


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def contents(path):
    lines = []
    if path.name.endswith(".zip"):
        with zipfile.ZipFile(path) as archive:
            for entry in archive.infolist():
                mode = entry.external_attr >> 16
                lines.append(f"{entry.filename} {mode:o} {sha256(archive.read(entry)) if not entry.is_dir() else '-'}")
    else:
        with tarfile.open(path) as archive:
            for entry in archive:
                data = archive.extractfile(entry).read() if entry.isfile() else b""
                lines.append(f"{entry.name} {entry.type!r} {entry.mode:o} {entry.linkname} {sha256(data)}")
    return sha256("\n".join(lines).encode())


def runtimes(directory, version):
    found = {}
    for path in pathlib.Path(directory).iterdir():
        match = ARCHIVE.match(path.name)
        if match and match["version"] == version:
            found[match["target"]] = path
    return found


def record(first_directory, first, second_directory, second):
    before, after = runtimes(first_directory, first), runtimes(second_directory, second)
    if not before or before.keys() != after.keys():
        sys.exit(f"runtime archives differ in targets: {sorted(before)} at {first}, {sorted(after)} at {second}")
    failed = False
    for target in sorted(before):
        archive = sha256(before[target].read_bytes())
        if archive != sha256(after[target].read_bytes()):
            print(f"::error::{target}: the runtime archive changed between {first} and {second}", file=sys.stderr)
            failed = True
        print(target, archive, contents(before[target]))
    if failed:
        sys.exit(1)


def compare(files):
    seen = {}
    for file in files:
        for line in pathlib.Path(file).read_text().splitlines():
            target, archive, content = line.split()
            seen.setdefault(target, []).append((pathlib.Path(file).stem, archive, content))
    failed = False
    for target, records in sorted(seen.items()):
        archives = {archive for _, archive, _ in records}
        same_contents = len({content for _, _, content in records}) == 1
        print(f"{target}: {'the same' if len(archives) == 1 else 'DIFFERENT'} on " +
              ", ".join(f"{agent} {archive[:12]}" for agent, archive, _ in records))
        if len(archives) > 1:
            why = "compression only: the contents are the same" if same_contents else "the contents differ"
            print(f"::error::{target}: agents built different runtime archives ({why})", file=sys.stderr)
            failed = True
    if failed:
        sys.exit(1)


if __name__ == "__main__":
    command, *arguments = sys.argv[1:]
    record(*arguments) if command == "record" else compare(arguments)
