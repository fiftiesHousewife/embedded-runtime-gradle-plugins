"""Turns failed JUnit results into GitHub Actions error annotations, so a failure can be read from
the run's summary (and its public annotations API) without downloading logs. Also posts one notice
per test suite with how many tests ran and how many were skipped, since a passing job alone doesn't
say whether its tests ran (functional tests skip on Windows unless they opt in)."""
import glob
import html
import pathlib
import re

counts = {}
for path in glob.glob("plugins/*/build/test-results/*/*.xml"):
    text = open(path, encoding="utf-8").read()
    parts = pathlib.PurePath(path).parts
    suite = f"{parts[1]}/{parts[4]}"  # e.g. java-app/functionalTest
    total, skipped = counts.get(suite, (0, 0))
    header = re.search(r'tests="(\d+)" skipped="(\d+)"', text)
    if header:
        counts[suite] = (total + int(header.group(1)), skipped + int(header.group(2)))
    for case in re.finditer(r'<testcase name="([^"]*)" classname="([^"]*)"[^>]*>\s*<failure message="(.*?)"', text, re.S):
        name, cls, message = case.group(1), case.group(2), html.unescape(case.group(3))
        summary = " ".join(message.split())[:900].replace("%", "%25").replace("\r", "").replace("\n", " ")
        print(f"::error title={cls.split('.')[-1]} > {name}::{summary}")

for suite, (total, skipped) in sorted(counts.items()):
    print(f"::notice title=Tests {suite}::{total - skipped} ran, {skipped} skipped")
