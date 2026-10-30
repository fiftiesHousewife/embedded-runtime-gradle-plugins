"""The runtime archive must be the same bytes for the same inputs, on every agent and for every release.

record <directory> <version> <directory> <version>: fails unless each target's runtime archive is identical at
both versions (the second from a clean build), then prints one line per target: the archive's SHA-256 and a
digest of its contents (each entry's name, type, mode and bytes), which tells a compression difference from a
difference in what was archived.

compare <file>... [--report <file>...]: fails unless the agents before --report built each target the same
archive, naming the entries that differ; the agents after it are compared and reported, never failed. A
Windows agent's jlink writes CRLF into `release`, backslashed pointers for duplicate legal notices, and
generates java.lang.invoke classes differently, so its runtime is different bytes.

The record's lines are `<target> <archive SHA-256> <contents digest>`, then `entry <target> <entry line>` for
every entry, which is how compare finds what differs.
"""
import base64
import hashlib
import os
import pathlib
import re
import subprocess
import sys
import tarfile
import tempfile
import zipfile

ARCHIVE = re.compile(r"^(?P<app>.+)-runtime-(?P<version>[^-]+)-(?P<target>[a-z]+-[a-z0-9]+)\.(?P<ext>tar\.gz|zip)$")


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def preview(data):
    """The start of a small file, so a report can show how two copies differ."""
    return base64.b64encode(data[:120]).decode() if len(data) < 4096 else "-"


def modules(data):
    """lib/modules resource by resource, with $JIMAGE (the host JDK's jimage), when it is set."""
    tool = os.environ.get("JIMAGE")
    if not tool:
        return []
    with tempfile.TemporaryDirectory() as work:
        image = pathlib.Path(work, "modules")
        image.write_bytes(data)
        out = pathlib.Path(work, "out")
        subprocess.run([tool, "extract", "--dir", str(out), str(image)], check=True)
        return [f"modules:{path.relative_to(out).as_posix()} f {sha256(path.read_bytes())} {preview(path.read_bytes())}"
                for path in sorted(out.rglob("*")) if path.is_file()]


def entries(path):
    lines = []
    if path.name.endswith(".zip"):
        with zipfile.ZipFile(path) as archive:
            for entry in archive.infolist():
                mode = entry.external_attr >> 16
                data = archive.read(entry) if not entry.is_dir() else b""
                lines.append(f"{entry.filename} {mode:o} {sha256(data)} {preview(data)}")
                if entry.filename.endswith("lib/modules"):
                    lines += modules(data)
    else:
        with tarfile.open(path) as archive:
            for entry in archive:
                data = archive.extractfile(entry).read() if entry.isfile() else b""
                lines.append(f"{entry.name} {entry.type.decode()}{entry.mode:o}{entry.linkname} {sha256(data)} {preview(data)}")
                if entry.name.endswith("lib/modules"):
                    lines += modules(data)
    return lines


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
        listed = entries(before[target])
        print(target, archive, sha256("\n".join(listed).encode()))
        for line in listed:
            print("entry", target, line)
    if failed:
        sys.exit(1)


def differences(first, second):
    """`name (what differs)` for each entry the two listings don't share; `kind` is `mode` or `bytes`."""
    mine, theirs = (dict(line.split(" ", 1) for line in listing) for listing in (first, second))
    found = []
    for name in sorted(mine.keys() | theirs.keys()):
        a, b = mine.get(name), theirs.get(name)
        if a is not None and b is not None and a.split()[:2] == b.split()[:2]:
            continue
        if a is None or b is None:
            found.append(f"{name} (only one has it)")
        elif a.split()[0] != b.split()[0]:
            found.append(f"{name} (mode {a.split()[0]} vs {b.split()[0]})")
        else:
            found.append(f"{name} (bytes{shown(a, b)})")
    return found


def shown(a, b):
    """For a small file, how each copy starts."""
    first, second = a.split()[-1], b.split()[-1]
    if "-" in (first, second):
        return ""
    return f": {base64.b64decode(first)!r} vs {base64.b64decode(second)!r}"


def read(files):
    seen, listed = {}, {}
    for file in files:
        agent = pathlib.Path(file).stem
        for line in pathlib.Path(file).read_text().splitlines():
            if line.startswith("entry "):
                _, target, entry = line.split(" ", 2)
                listed.setdefault((agent, target), []).append(entry)
            else:
                target, archive, _ = line.split()
                seen.setdefault(target, {})[agent] = archive
    return seen, listed


def compare(files):
    required = files[:files.index("--report")] if "--report" in files else files
    reported = files[len(required) + 1:]
    seen, listed = read(required + reported)
    agents = [pathlib.Path(file).stem for file in required]
    failed = False
    for target, archives in sorted(seen.items()):
        reference = agents[0]
        print(f"{target}: " + ", ".join(f"{agent} {archive[:12]}" for agent, archive in archives.items()))
        for other, archive in archives.items():
            if other == reference or archive == archives[reference]:
                continue
            found = differences(listed[(reference, target)], listed[(other, target)])
            if other in agents:
                failed = True
                files_ = [f for f in found if not f.startswith("modules:")]
                resources = [f for f in found if f.startswith("modules:")]
                what = (f"{len(found)} entries differ: " + "; ".join(files_[:6] + resources[:4])) if found else \
                    "compression only"
                print(f"::error::{target}, {reference} against {other}: {what}", file=sys.stderr)
            else:
                names = [f.split(" (")[0] for f in found]
                print(f"{target}: {other} differs from {reference} in {len(found)} entries, as expected: " +
                      ", ".join(names[:5]))
    if failed:
        sys.exit(1)


if __name__ == "__main__":
    command, *arguments = sys.argv[1:]
    record(*arguments) if command == "record" else compare(arguments)
