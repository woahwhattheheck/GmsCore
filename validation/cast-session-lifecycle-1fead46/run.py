#!/usr/bin/env python3
"""Compile the actual two product files, plus host collaborators, and run bounded assertions."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys

parser = argparse.ArgumentParser()
parser.add_argument("stage", choices=["before", "current", "after"])
parser.add_argument("--source-dir", type=Path, help="Directory containing the two actual product Java files")
args = parser.parse_args()
root = Path(__file__).resolve().parent
product = args.source_dir.resolve() if args.source_dir else root / {"before": "baseline", "current": "current-head", "after": "candidate"}[args.stage]
sources = [product / "SessionManagerImpl.java", product / "SessionImpl.java"]
classes = root / "build" / args.stage
classes.mkdir(parents=True, exist_ok=True)
logs = root / "results"
logs.mkdir(exist_ok=True)
inputs = sources + sorted((root / "stubs").rglob("*.java")) + [root / "LifecycleRegression.java"]
metadata = {"stage": args.stage, "source_files": {str(p): hashlib.sha256(p.read_bytes()).hexdigest() for p in sources}}
compile_command = ["java", "-m", "jdk.compiler/com.sun.tools.javac.Main", "-d", str(classes)] + [str(p) for p in inputs]
compiled = subprocess.run(compile_command, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=30)
metadata["compile_exit"] = compiled.returncode
(logs / (args.stage + "-compile.txt")).write_text(compiled.stdout)
if compiled.returncode:
    print(compiled.stdout, end="")
    (logs / (args.stage + "-metadata.json")).write_text(json.dumps(metadata, indent=2) + "\n")
    sys.exit(compiled.returncode)
ran = subprocess.run(["java", "-cp", str(classes), "com.google.android.gms.cast.framework.internal.LifecycleRegression"], text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=15)
metadata["run_exit"] = ran.returncode
(logs / (args.stage + ".txt")).write_text(ran.stdout)
(logs / (args.stage + "-metadata.json")).write_text(json.dumps(metadata, indent=2) + "\n")
print(json.dumps(metadata, indent=2))
print(ran.stdout, end="")
sys.exit(ran.returncode)
