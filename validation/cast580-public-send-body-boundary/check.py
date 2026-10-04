import argparse, hashlib, json, shutil, subprocess, sys
from pathlib import Path
import xml.etree.ElementTree as ET

CHANNEL = Path("play-services-cast/core/src/main/kotlin/org/microg/gms/cast/channel/CastChannel.kt")
SESSION = Path("play-services-cast/core/src/main/kotlin/org/microg/gms/cast/channel/CastDeviceSession.kt")
TEST = Path("play-services-cast/core/src/test/kotlin/org/microg/gms/cast/channel/CastDeviceSessionSendCompletionTest.kt")
PROTO = Path("play-services-cast/core/src/main/proto/cast_channel.proto")
RESULTS = Path("validation-results")
XML = Path("play-services-cast/core/build/test-results/testDebugUnitTest")
CLASS = "org.microg.gms.cast.channel.CastDeviceSessionSendCompletionTest"
EXACT = "textAndBinarySendsAcceptExactly65536EncodedBodyBytes"
OVER = "textAndBinarySendsReject65537EncodedBodyBytesWithoutCorruptingNextSend"
BLOBS = {
    CHANNEL: "23641cce0a9db0ed14e10b2f0da4fefbe3e766b0",
    SESSION: "6a6e7eb42948c9322652f4ef48379f16cc96b32d",
    TEST: "73910114226762930282cf872a41f32d10649584",
    PROTO: "ff1e9e55ed7a2352795f40bbbf79ba8bcc97dab7",
}
GUARD = b"        if (bytes.size > MAX_PAYLOAD_SIZE) throw MessageTooLargeException(bytes.size)"

def git_blob(data):
    return hashlib.sha1(b"blob " + str(len(data)).encode() + b"\0" + data).hexdigest()

def original_channel():
    data = subprocess.check_output(["git", "show", "HEAD:" + CHANNEL.as_posix()])
    assert git_blob(data) == BLOBS[CHANNEL]
    return data

def source():
    RESULTS.mkdir(exist_ok=True)
    receipt = {"head": subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip(), "files": {}}
    for path, expected in BLOBS.items():
        data = path.read_bytes()
        observed = git_blob(data)
        assert observed == expected, (str(path), observed, expected)
        receipt["files"][str(path)] = {"git_blob": observed, "sha256": hashlib.sha256(data).hexdigest()}
    (RESULTS / "source.json").write_text(json.dumps(receipt, indent=2) + "\n")
    print(json.dumps(receipt))

def result(label, status):
    dest = RESULTS / label
    dest.mkdir(parents=True, exist_ok=True)
    selected = [XML / ("TEST-" + CLASS + ".xml")]
    if label == "current":
        selected.append(XML / "TEST-org.microg.gms.cast.channel.CastChannelFramingTest.xml")
        assert status == 0, status
    else:
        assert status != 0, "Mutation must fail an executed test"
    roots = [ET.parse(p).getroot() for p in selected]
    cases = [case for suite in roots for case in suite.findall("testcase")]
    failures = [case.attrib["name"] for case in cases if case.find("failure") is not None]
    errors = [case.attrib["name"] for case in cases if case.find("error") is not None]
    skipped = [case.attrib["name"] for case in cases if case.find("skipped") is not None]
    if label == "current":
        assert len(cases) == 12 and not failures and not errors and not skipped
        assert {EXACT, OVER}.issubset({c.attrib["name"] for c in cases})
    else:
        expected = EXACT if label == "strict-cap" else OVER
        assert len(cases) == 2 and failures == [expected] and not errors and not skipped, (len(cases), failures, errors, skipped)
    for p in selected:
        shutil.copy2(p, dest / p.name)
    receipt = {"label": label, "gradle_exit": status, "tests": len(cases), "failures": failures, "errors": errors, "skipped": skipped}
    (dest / "result.json").write_text(json.dumps(receipt, indent=2) + "\n")
    print(json.dumps(receipt))

def mutate(label):
    data = original_channel()
    assert data.count(GUARD) == 1
    replacement = GUARD.replace(b"> MAX_PAYLOAD_SIZE", b">= MAX_PAYLOAD_SIZE") if label == "strict-cap" else b""
    CHANNEL.write_bytes(data.replace(GUARD, replacement))

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("action", choices=["source", "result", "mutate", "restore"])
    parser.add_argument("label", nargs="?")
    parser.add_argument("status", nargs="?", type=int)
    args = parser.parse_args()
    if args.action == "source": source()
    elif args.action == "result": result(args.label, args.status)
    elif args.action == "mutate": mutate(args.label)
    else: CHANNEL.write_bytes(original_channel())

if __name__ == "__main__":
    main()
