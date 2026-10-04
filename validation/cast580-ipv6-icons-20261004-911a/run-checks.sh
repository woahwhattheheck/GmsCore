#!/usr/bin/env bash
set -eu
CAST_RUNTIME=${CAST_RUNTIME:-/workspace/scratch/911a1e959a68/cast-ipv6-runtime}
CAST_REPAIR=${CAST_REPAIR:-/workspace/scratch/911a1e959a68/cast-ipv6-repair}
CAST_JAVAC=${CAST_JAVAC:-/workspace/scratch/a07bfe951f29/android-build-runtime/jdk-17.0.20.1+1/bin/javac}
CAST_SUPPORT=${CAST_SUPPORT:-/workspace/scratch/f51438b77d4b/bounty-build}
CAST_CP="$CAST_RUNTIME/android-all-9-robolectric-4913185-2.jar:$CAST_SUPPORT/play-services-base/build/intermediates/runtime_library_classes_jar/debug/bundleLibRuntimeToJarDebug/classes.jar:$CAST_SUPPORT/play-services-basement/build/intermediates/runtime_library_classes_jar/debug/bundleLibRuntimeToJarDebug/classes.jar"
mkdir -p "$CAST_RUNTIME/baseline/classes" "$CAST_RUNTIME/fixed/classes"
"$CAST_JAVAC" -proc:none -d "$CAST_RUNTIME/baseline/classes" -cp "$CAST_CP" "$CAST_RUNTIME/baseline/CastDevice.java" "$CAST_RUNTIME/CastDeviceIconCheck.java"
"$CAST_JAVAC" -proc:none -d "$CAST_RUNTIME/fixed/classes" -cp "$CAST_CP" "$CAST_REPAIR/play-services-cast/src/main/java/com/google/android/gms/cast/CastDevice.java" "$CAST_RUNTIME/CastDeviceIconCheck.java"
if java -cp "$CAST_RUNTIME/baseline/classes:$CAST_CP" CastDeviceIconCheck > "$CAST_RUNTIME/baseline.log" 2>&1; then
    printf '%s\n' 'Unexpected baseline pass' >&2
    exit 1
fi
rg -q 'AssertionError: 6 failures' "$CAST_RUNTIME/baseline.log"
java -cp "$CAST_RUNTIME/fixed/classes:$CAST_CP" CastDeviceIconCheck --loopback > "$CAST_RUNTIME/fixed.log" 2>&1
cat "$CAST_RUNTIME/fixed.log"
