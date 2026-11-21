#!/usr/bin/env python3
"""Reject accidental secrets and private local files in tracked repository content.

Findings print only paths and categories, never actual secret values.
This checks the current checkout, not old Git history: rotate anything leaked
in an earlier commit and clean history separately when necessary.
"""
from __future__ import annotations
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BLOCKED_NAMES = {".env", "google-services.json", "service-account.json", "credentials.json", "local.properties"}
BLOCKED_SUFFIXES = {".jks", ".keystore", ".p12", ".pfx", ".pem", ".key", ".sqlite", ".sqlite3", ".db"}
PATTERNS = {
    "private key": re.compile(r"-----BEGIN (?:RSA |EC |DSA |OPENSSH )?PRIVATE KEY-----"),
    "GitHub access token": re.compile(r"(?:ghp|gho|ghu|ghs|ghr)_[A-Za-z0-9_]{30,}|github_pat_[A-Za-z0-9_]{40,}"),
    "Google API key": re.compile(r"AIza[0-9A-Za-z_-]{35}"),
    "AWS access key": re.compile(r"(?:AKIA|ASIA)[0-9A-Z]{16}"),
    "OpenAI API key": re.compile(r"sk-(?:proj-)?[A-Za-z0-9_-]{30,}"),
    "Slack access token": re.compile(r"xox[baprs]-[A-Za-z0-9-]{20,}"),
    "hard-coded signing password": re.compile(
        r"\b(?:storePassword|keyPassword)\s*=\s*\x22(?!android\x22)[^\x22\r\n]{6,}\x22"
    ),
}
SAMPLE_EMAIL = re.compile(r"\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b")

def main() -> int:
    tracked = subprocess.check_output(["git", "ls-files", "-z"], cwd=ROOT).split(b"\0")
    findings: list[tuple[str, int, str]] = []
    for raw in tracked:
        if not raw:
            continue
        rel = raw.decode("utf-8")
        path = ROOT / rel
        if not path.is_file():
            continue
        name = path.name.lower()
        if (name in BLOCKED_NAMES or (name.startswith(".env.") and name != ".env.example")
                or name.startswith("service-account") or name.endswith(".credentials.json")
                or path.suffix.lower() in BLOCKED_SUFFIXES):
            findings.append((rel, 0, "private local file tracked by Git"))
            continue
        blob = path.read_bytes()
        if b"\0" in blob:
            continue
        content = blob.decode("utf-8", errors="replace")
        for label, pattern in PATTERNS.items():
            for match in pattern.finditer(content):
                line = content.count("\n", 0, match.start()) + 1
                findings.append((rel, line, label))
        if rel.startswith(("docs/samples/", "app/src/debug/assets/")):
            for match in SAMPLE_EMAIL.finditer(content):
                line = content.count("\n", 0, match.start()) + 1
                findings.append((rel, line, "email in demo fixture"))
    if findings:
        for rel, line, label in findings:
            loc = f"{rel}:{line}" if line else rel
            print(f"SECURITY CHECK FAILED: {loc}: {label}", file=sys.stderr)
        return 1
    print("Security preflight passed: no obvious secrets or private artifacts in tracked files.")
    print("NOTE: Old commits are not scanned. Rotate any previously exposed credentials.")
    return 0

if __name__ == "__main__":
    sys.exit(main())
