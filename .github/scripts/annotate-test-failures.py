"""Turns failed JUnit results into GitHub Actions error annotations, so a failure can be read from
the run's summary (and its public annotations API) without downloading logs."""
import glob
import html
import re

for path in glob.glob("build-src/*/build/test-results/*/*.xml"):
    text = open(path, encoding="utf-8").read()
    for case in re.finditer(r'<testcase name="([^"]*)" classname="([^"]*)"[^>]*>\s*<failure message="(.*?)"', text, re.S):
        name, cls, message = case.group(1), case.group(2), html.unescape(case.group(3))
        summary = " ".join(message.split())[:900].replace("%", "%25").replace("\r", "").replace("\n", " ")
        print(f"::error title={cls.split('.')[-1]} > {name}::{summary}")
