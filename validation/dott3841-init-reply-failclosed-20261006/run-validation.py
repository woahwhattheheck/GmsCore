#!/usr/bin/env python3
"""Two-phase real Gradle provider-lifecycle execution; no device/PI claim."""
import argparse, datetime, hashlib, json, os, pathlib, shutil, subprocess, time
import xml.etree.ElementTree as ET

def sha256(data):
    return hashlib.sha256(data).hexdigest()

def gitblob(data):
    return hashlib.sha1(b"blob " + str(len(data)).encode() + b"\0" + data).hexdigest()

def call(args, cwd, **kw):
    return subprocess.check_output(args, cwd=cwd, text=True, **kw).strip()

def save(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2) + "\n")

def locked(controller, lock):
    path = controller / lock["path"]
    data = path.read_bytes()
    if len(data) != lock["bytes"] or sha256(data) != lock["sha256"]:
        raise RuntimeError("Controller byte lock changed: " + lock["path"])
    if "gitBlob" in lock and gitblob(data) != lock["gitBlob"]:
        raise RuntimeError("Controller Git blob changed: " + lock["path"])
    return data

def parse_xml(directory):
    cases = {}
    paths = sorted(directory.glob("TEST-*.xml"))
    for path in paths:
        root = ET.parse(path).getroot()
        for case in root.iter("testcase"):
            name = case.attrib["classname"] + "#" + case.attrib["name"]
            if name in cases:
                raise RuntimeError("Duplicate actual JUnit test case: " + name)
            cases[name] = {
                "failures": len(case.findall("failure")),
                "errors": len(case.findall("error")),
                "skipped": len(case.findall("skipped")),
                "seconds": case.attrib.get("time"),
            }
    return cases, paths

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--product", required=True)
    parser.add_argument("--controller", required=True)
    parser.add_argument("--evidence", required=True)
    args = parser.parse_args()
    product = pathlib.Path(args.product).resolve()
    controller = pathlib.Path(args.controller).resolve()
    evidence = pathlib.Path(args.evidence).resolve()
    evidence.mkdir(parents=True, exist_ok=True)
    packet = controller / "validation/dott3841-init-reply-failclosed-20261006"
    manifest = json.loads((packet / "manifest.json").read_text())
    started = time.monotonic()
    receipt = {
        "started": datetime.datetime.now(datetime.timezone.utc).isoformat(),
        "controllerCommit": call(["git", "rev-parse", "HEAD"], controller),
        "controllerTree": call(["git", "rev-parse", "HEAD^{tree}"], controller),
        "githubRunId": os.environ.get("GITHUB_RUN_ID"),
        "githubRunAttempt": os.environ.get("GITHUB_RUN_ATTEMPT"),
        "event": os.environ.get("GITHUB_EVENT_NAME"),
        "sourceCommit": call(["git", "rev-parse", "HEAD"], product),
        "sourceTree": call(["git", "rev-parse", "HEAD^{tree}"], product),
        "manifestSha256": sha256((packet / "manifest.json").read_bytes()),
        "limits": "Controlled recording VM and Android/Robolectric fixtures; no Google VM/JNI, actual Binder IPC, Play Integrity, Dott or device result.",
    }
    save(evidence / "start.json", receipt)
    if receipt["controllerCommit"] != os.environ["GITHUB_SHA"]:
        raise RuntimeError("Controller checkout changed")
    if receipt["sourceCommit"] != manifest["source"]["commit"] or receipt["sourceTree"] != manifest["source"]["tree"]:
        raise RuntimeError("Original source commit/tree changed")
    if not manifest["expectedCases"] or not manifest["originalExpectedFailures"]:
        raise RuntimeError("No frozen meaningful red/green test matrix")
    for lock in manifest["controllerFileLocks"]:
        locked(controller, lock)
    task = ":play-services-droidguard-core:testDebugUnitTest"
    base_build = product / "play-services-droidguard/core/build.gradle"
    base_build_bytes = base_build.read_bytes()
    if gitblob(base_build_bytes) != manifest["baseBuildGitBlob"]:
        raise RuntimeError("Original Gradle test configuration changed")
    root_build = product / "build.gradle"
    root_build_bytes = root_build.read_bytes()
    if gitblob(root_build_bytes) != manifest["rootBuildGitBlob"]:
        raise RuntimeError("Original root Gradle bootstrap configuration changed")
    versions = subprocess.check_output(["./gradlew", "--version"], cwd=product, text=True)
    (evidence / "gradle-version.txt").write_text(versions)
    if "Gradle 8.13" not in versions:
        raise RuntimeError("Executed Gradle differs from pinned wrapper version")
    with (evidence / "java-version.txt").open("wb") as version_log:
        subprocess.run(["java", "-version"], stdout=version_log, stderr=subprocess.STDOUT, check=True)
    results = {}
    for phase in ("original", "candidate"):
        phase_dir = evidence / phase
        phase_dir.mkdir()
        base_build.write_bytes(base_build_bytes)
        # Restore only our declared production overlays; no product ref is moved.
        for change in manifest["sourcePostimages"]:
            source_path = product / change["sourcePath"]
            original = subprocess.check_output(
                ["git", "show", "HEAD:" + change["sourcePath"]], cwd=product)
            if gitblob(original) != change["beforeGitBlob"]:
                raise RuntimeError("Source preimage changed: " + change["sourcePath"])
            data = locked(controller, change["controllerLock"]) if phase == "candidate" else original
            source_path.write_bytes(data)
        expected_changes = sorted(c["sourcePath"] for c in manifest["sourcePostimages"]) if phase == "candidate" else []
        actual_changes = sorted(call(["git", "diff", "--name-only", "HEAD"], product).splitlines())
        if actual_changes != expected_changes:
            raise RuntimeError("Tracked production files changed outside the declared source projection")
        index_env = {**os.environ, "GIT_INDEX_FILE": str(phase_dir / "projection.index")}
        subprocess.check_call(["git", "read-tree", "HEAD"], cwd=product, env=index_env)
        for change in manifest["sourcePostimages"]:
            path = change["sourcePath"]
            oid = call(["git", "hash-object", "-w", path], product)
            subprocess.check_call(
                ["git", "update-index", "--add", "--cacheinfo", change["mode"] + "," + oid + "," + path],
                cwd=product, env=index_env)
        projected = call(["git", "write-tree"], product, env=index_env)
        if projected != manifest["projectedTrees"][phase]:
            raise RuntimeError("Production source projection changed: " + phase)
        production_receipt = {"phase": phase, "parentCommit": receipt["sourceCommit"],
            "parentTree": receipt["sourceTree"], "projectedTree": projected,
            "sourcePostimages": [{
                "path": c["sourcePath"],
                "gitBlob": gitblob((product / c["sourcePath"]).read_bytes()),
                "sha256": sha256((product / c["sourcePath"]).read_bytes())
            } for c in manifest["sourcePostimages"]]}
        save(phase_dir / "production-source.json", production_receipt)
        # The test/runtime dependencies are an explicit validation overlay only.
        for test in manifest["validationTestFiles"]:
            destination = product / test["sourcePath"]
            destination.parent.mkdir(parents=True, exist_ok=True)
            if test["sourcePath"] in call(["git", "ls-tree", "-r", "--name-only", "HEAD"], product).splitlines():
                raise RuntimeError("Validation test would overwrite product source")
            destination.write_bytes(locked(controller, test["controllerLock"]))
        dependency_overlay = controller / manifest["dependencyOverlay"]["path"]
        locked(controller, manifest["dependencyOverlay"])
        base_build.write_bytes(base_build_bytes + (
            "\n// Disposable validation overlay only.\napply from: " +
            json.dumps(str(dependency_overlay)) + "\n").encode())
        save(phase_dir / "validation-overlay.json", {
            "baseBuildGitBlob": manifest["baseBuildGitBlob"],
            "executedBuildSha256": sha256(base_build.read_bytes()),
            "dependencyOverlay": manifest["dependencyOverlay"],
            "testFiles": manifest["validationTestFiles"],
        })
        if phase == "original":
            diagnostic_command = ["./gradlew", "--no-daemon", "--console=plain", "--max-workers=1",
                "--no-parallel", ":buildEnvironment", ":play-services-droidguard-core:recordDottValidationBootstrap"]
            with (evidence / "bootstrap-buildEnvironment.log").open("wb") as log:
                diagnostic = subprocess.run(diagnostic_command, cwd=product, stdout=log, stderr=subprocess.STDOUT)
            save(evidence / "bootstrap-execution.json", {
                "command": diagnostic_command, "exitCode": diagnostic.returncode,
                "rootBuildGitBlob": gitblob(root_build.read_bytes()),
                "rootBuildSha256": sha256(root_build.read_bytes()),
                "declaredBootstrapDependenciesChanged": False,
            })
            if root_build.read_bytes() != root_build_bytes:
                raise RuntimeError("Root Gradle bootstrap source changed during diagnostics")
            if diagnostic.returncode != 0:
                raise RuntimeError("Bootstrap or dependency diagnostic failed before lifecycle execution; inspect actual log")
            dependency_rows = json.loads((evidence / "selected-root-buildscript-classpath.json").read_text())
            root_rows = [row for row in dependency_rows if row["configuration"] == "rootBuildscriptClasspath"]
            if not root_rows:
                raise RuntimeError("No actual selected root buildscript classpath artifact hash record")
        xml_dir = product / "play-services-droidguard/core/build/test-results/testDebugUnitTest"
        report_dir = product / "play-services-droidguard/core/build/reports/tests/testDebugUnitTest"
        # Remove only this invocation's prior JUnit outputs to prevent stale attribution.
        if xml_dir.exists():
            shutil.rmtree(xml_dir)
        if report_dir.exists():
            shutil.rmtree(report_dir)
        command = ["./gradlew", "--no-daemon", "--console=plain", "--max-workers=1",
            "--no-parallel", "-Dorg.gradle.jvmargs=-Xmx3g -XX:MaxMetaspaceSize=768m",
            "-Pkotlin.compiler.execution.strategy=in-process", task]
        for name in manifest["testClasses"]:
            command.extend(["--tests", name])
        phase_start = time.monotonic()
        with (phase_dir / "gradle.log").open("wb") as log:
            completed = subprocess.run(command, cwd=product, stdout=log, stderr=subprocess.STDOUT)
        cases, reports = parse_xml(xml_dir)
        permitted_changes = sorted(set(expected_changes + ["play-services-droidguard/core/build.gradle"]))
        actual_changes = sorted(call(["git", "diff", "--name-only", "HEAD"], product).splitlines())
        if actual_changes != permitted_changes:
            raise RuntimeError("Executed source changed outside the source/test dependency overlays")
        for source in production_receipt["sourcePostimages"]:
            if sha256((product / source["path"]).read_bytes()) != source["sha256"]:
                raise RuntimeError("Executed product postimage changed during tests")
        for test in manifest["validationTestFiles"]:
            if (product / test["sourcePath"]).read_bytes() != locked(controller, test["controllerLock"]):
                raise RuntimeError("Executed lifecycle test changed during tests")
        if root_build.read_bytes() != root_build_bytes:
            raise RuntimeError("Root Gradle bootstrap source changed during lifecycle execution")
        if reports:
            shutil.copytree(xml_dir, phase_dir / "junit")
        failures = sorted(name for name, value in cases.items() if value["failures"])
        errors = sorted(name for name, value in cases.items() if value["errors"])
        skipped = sorted(name for name, value in cases.items() if value["skipped"])
        expected_failure = sorted(manifest["originalExpectedFailures"]) if phase == "original" else []
        problems = []
        if sorted(cases) != sorted(manifest["expectedCases"]):
            problems.append("Actual JUnit cases differ from the frozen same-case matrix")
        if errors or skipped:
            problems.append("Actual JUnit errors/skips occurred")
        if failures != expected_failure:
            problems.append("Actual failure names differ from the exact expected differential")
        if phase == "candidate" and completed.returncode != 0:
            problems.append("Candidate Gradle command failed")
        if phase == "original" and completed.returncode == 0:
            problems.append("Original did not fail its declared regression cases")
        results[phase] = {
            "command": command, "exitCode": completed.returncode,
            "seconds": round(time.monotonic() - phase_start, 3),
            "productionSource": production_receipt, "cases": cases,
            "tests": len(cases), "failureCases": failures, "errorCases": errors,
            "skippedCases": skipped, "problems": problems,
        }
        save(phase_dir / "execution.json", results[phase])
    dependency_command = ["./gradlew", "--no-daemon", "--console=plain", "--max-workers=1",
        "--no-parallel", ":play-services-droidguard-core:recordDottValidationDependencies"]
    with (evidence / "dependency-resolution.log").open("wb") as log:
        resolved = subprocess.run(dependency_command, cwd=product, stdout=log, stderr=subprocess.STDOUT)
    if resolved.returncode != 0 or not (evidence / "resolved-gradle-dependencies.json").is_file():
        raise RuntimeError("No actual resolved dependency hash record")
    android_runtime_jars = []
    runtime_cache = pathlib.Path(os.environ["DOTT_ANDROID_RUNTIME_DIR"])
    for jar in sorted(runtime_cache.glob("*.jar")):
        data = jar.read_bytes()
        android_runtime_jars.append({"artifact": str(jar.relative_to(runtime_cache)), "bytes": len(data), "sha256": sha256(data)})
    save(evidence / "robolectric-android-runtime-jars.json", android_runtime_jars)
    if [row["artifact"] for row in android_runtime_jars] != [manifest["robolectricAndroidRuntime"]["fileName"]]:
        raise RuntimeError("Actual Robolectric Android runtime JAR differs from the pinned API 29 artifact")
    summary = {
        **receipt, "ended": datetime.datetime.now(datetime.timezone.utc).isoformat(),
        "seconds": round(time.monotonic() - started, 3), "phases": results,
        "sourceRefChanges": 0, "deviceOrPIExecution": False,
    }
    save(evidence / "summary.json", summary)
    locks = []
    for path in sorted(evidence.rglob("*")):
        if path.is_file() and path.name != "SHA256SUMS" and path.suffix != ".index":
            data = path.read_bytes()
            locks.append({"path": str(path.relative_to(evidence)), "bytes": len(data), "sha256": sha256(data)})
    save(evidence / "artifact-locks.json", locks)
    (evidence / "SHA256SUMS").write_text("".join(x["sha256"] + "  " + x["path"] + "\n" for x in locks))
    print(json.dumps({phase: {key: result[key] for key in ("tests", "failureCases", "errorCases", "skippedCases", "exitCode", "problems")} for phase, result in results.items()}, indent=2))
    if any(result["problems"] for result in results.values()):
        raise SystemExit(1)

if __name__ == "__main__":
    main()
