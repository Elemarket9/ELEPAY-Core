"""Run isolated retry contracts without Maven, Spring context or a database.

A temporary no-op Service annotation replaces only Spring's component marker.
This is an arithmetic/control-flow check, never an application integration test.
Usage: python scripts/check-reliability-safety.py [--jdk PATH]
"""
import argparse
from pathlib import Path
import shutil
import subprocess
import tempfile

parser = argparse.ArgumentParser()
parser.add_argument("--jdk", type=Path)
args = parser.parse_args()
root = Path(__file__).resolve().parent.parent
def executable(name):
    if args.jdk:
        return str(args.jdk / "bin" / (name + (".exe" if __import__("os").name == "nt" else "")))
    found = shutil.which(name)
    if not found:
        raise SystemExit(name + " is required")
    return found

build_root = (root / "target").resolve()
build_root.mkdir(exist_ok=True)
with tempfile.TemporaryDirectory(prefix="retry-contract-", dir=build_root) as directory:
    work = Path(directory).resolve()
    if work.parent != build_root:
        raise RuntimeError("Temporary test output escaped build directory")
    annotation = work / "org/springframework/stereotype/Service.java"
    annotation.parent.mkdir(parents=True)
    annotation.write_text("package org.springframework.stereotype; public @interface Service {}", encoding="utf-8")
    output = work / "classes"
    output.mkdir()
    sources = [
        annotation,
        root / "src/main/java/com/elepay/reliability/BackoffAndJitterRetry.java",
        root / "src/main/java/com/elepay/reliability/DatabaseFailoverRecoveryService.java",
        root / "src/test/java/com/elepay/reliability/ReliabilitySafetyContract.java",
    ]
    subprocess.run([executable("javac"), "--release", "17", "-d", str(output),
                    *map(str, sources)], check=True)
    subprocess.run([executable("java"), "-cp", str(output),
                    "com.elepay.reliability.ReliabilitySafetyContract"], check=True)
