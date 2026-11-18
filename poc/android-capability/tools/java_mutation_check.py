#!/usr/bin/env python3
"""Mutation probe for the plain-Java parts of the test app.

Each mutant re-introduces one bug that a review finding is about (F1, F3, F4)
into a temporary copy of the sources and runs tools/run-jvm-checks.sh against
it. Every mutant must make the checks fail. The real sources are never
modified. Run with: python3 -I tools/java_mutation_check.py
"""
import pathlib
import shutil
import subprocess
import sys
import tempfile

HERE = pathlib.Path(__file__).resolve().parent.parent
MAIN = HERE / "app-project/testapp/src/main/java/de/evp/pakepoc"
RUN = HERE / "tools/run-jvm-checks.sh"

MUTANTS = [
    ("F1: export and write not serialised", "SnapshotWriter.java",
     "    synchronized boolean persist(Source source)", "    boolean persist(Source source)"),
    ("F4: every call writes", "SnapshotWriter.java",
     "if (Arrays.equals(snapshot, lastDurable)) return false;", ""),
    ("F1: failed write recorded as durable", "SnapshotWriter.java",
     "        sink.write(snapshot);\n        lastDurable = snapshot;",
     "        lastDurable = snapshot;\n        sink.write(snapshot);"),
    ("F1: stale owner export written", "SnapshotWriter.java",
     "if (snapshot == null) return false;", "if (snapshot == null) snapshot = new byte[0];"),
    ("reset: durable state kept after delete", "SnapshotWriter.java",
     "        lastDurable = null;\n        delete.run();", "        delete.run();"),
    ("F3: only per-read timeouts", "HttpRequestReader.java",
     "            if (left <= 0) throw new SecurityException(\"SlowRequest\");\n            s.setSoTimeout((int) Math.min(left, PER_READ_TIMEOUT_MS));",
     "            s.setSoTimeout(PER_READ_TIMEOUT_MS);"),
    ("F3: budget starts at worker pickup", "HttpRequestReader.java",
     "acceptedAt + READ_BUDGET_MS", "clock.now() + READ_BUDGET_MS"),
    ("F3: body read without deadline", "HttpRequestReader.java",
     "int k = in.read(r.body, at, n - at);",
     "int k = s.getInputStream().read(r.body, at, n - at);"),
    ("limits: header size unchecked", "HttpRequestReader.java",
     "if (hb.size() > MAX_HEADER) throw new SecurityException(\"HeaderTooLarge\");", ""),
    ("limits: body size unchecked", "HttpRequestReader.java",
     "if (n > MAX_BODY) throw new SecurityException(\"TooLarge\");", ""),
]


def run(src: pathlib.Path) -> bool:
    r = subprocess.run(["bash", str(RUN), str(src)], capture_output=True, text=True, timeout=300)
    return r.returncode == 0


def main() -> int:
    if not run(MAIN):
        print("baseline FAILED – fix the checks first")
        return 1
    print("baseline passes")
    survived = 0
    for name, fname, old, new in MUTANTS:
        with tempfile.TemporaryDirectory() as tmp:
            dst = pathlib.Path(tmp)
            for f in ("SnapshotWriter.java", "HttpRequestReader.java"):
                shutil.copy(MAIN / f, dst / f)
            text = (dst / fname).read_text()
            if text.count(old) != 1:
                print(f"PATTERN  {name}: pattern not found exactly once")
                return 1
            (dst / fname).write_text(text.replace(old, new))
            if run(dst):
                survived += 1
                print(f"SURVIVED {name}")
            else:
                print(f"killed   {name}")
    print("JAVA MUTATION RESULT " + ("PASS" if survived == 0 else f"FAIL ({survived} survived)"))
    return 0 if survived == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
