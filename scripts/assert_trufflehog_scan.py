#!/usr/bin/env python3
"""Fail closed on TruffleHog source errors or a vacuous scan.

TruffleHog versions used in CI emit a 'finished scanning' metrics line;
scanning zero bytes is not equivalent to finding no secrets.
"""
import pathlib
import re
import sys


def assert_scanned(log: str) -> tuple[int, int]:
    if "encountered errors during scan" in log or "error chunking dir" in log:
        raise ValueError("TruffleHog reported a scan/source error")
    summaries = [line for line in log.splitlines() if "finished scanning" in line]
    if not summaries:
        raise ValueError("No TruffleHog completed-scan summary was emitted")
    summary = summaries[-1]
    chunks = re.search(r'"chunks"\s*:\s*(\d+)', summary)
    length = re.search(r'"bytes"\s*:\s*(\d+)', summary)
    if not chunks or not length:
        raise ValueError("TruffleHog did not report scanned chunks and bytes")
    count, size = int(chunks.group(1)), int(length.group(1))
    if count <= 0 or size <= 0:
        raise ValueError("TruffleHog inspected zero content; security gate fails closed")
    return count, size


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit("Usage: assert_trufflehog_scan.py <log-path>")
    try:
        count, size = assert_scanned(pathlib.Path(sys.argv[1]).read_text())
        print(f"Container secret scan inspected {count} chunks / {size} bytes")
    except (ValueError, OSError) as e:
        sys.exit("SECURITY FAILURE: " + str(e))
