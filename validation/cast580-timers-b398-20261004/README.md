# Cast session canceled-timer retention

This focused JVM reproduction exercises the real `CastDeviceSession`, `CastChannel`, and generated Wire protocol classes. It writes Cast frames into memory, decodes their request IDs, serializes receiver replies, and delivers those replies through `CastDeviceSession.onMessage`. It does not open a socket, run Android, use a physical receiver, or test the Binder/framework integration.

## Source identity and production change

- Repository: `woahwhattheheck/GmsCore`.
- Existing upstream PR: https://github.com/microg/GmsCore/pull/3845.
- Baseline commit: `13acaa56bc6672e4508e185b82a57d212c2ac2d8`.
- Production path: `play-services-cast/core/src/main/kotlin/org/microg/gms/cast/channel/CastDeviceSession.kt`.
- Baseline blob: `2a97f5205fca15efe72113c662d6c85534e317ae`.
- Guarded candidate blob: `b26326bcc735591b818a7de01b2a19a957316ba9`.
- Guarded candidate SHA-256: `e5264a10529c5d564582166317ccda5b9d2e9d8689460dba60579551aa52c0d4` (19,193 bytes; CRLF preserved).
- Unchanged `CastChannel.kt` blob: `933fb0d2b3dd1549bad5594f6dff5373326e8031`.
- Unchanged `cast_channel.proto` blob: `ff1e9e55ed7a2352795f40bbbf79ba8bcc97dab7`; `generated/` contains the reused Wire 6.4.6 output for that schema.

The production patch imports `android.os.Build` and enables `removeOnCancelPolicy` in the existing executor initializer only when `SDK_INT >= LOLLIPOP`. It preserves the shutdown policy and every request/reply method. The module supports API 19; Android documents the setter as added in API 21: https://developer.android.com/reference/java/util/concurrent/ScheduledThreadPoolExecutor#setRemoveOnCancelPolicy(boolean).

Only the production patch belongs on the original PR. The SDK fixture, runner, and captured source copies are validation artifacts.

### Published source linkage

The publisher composed only this Build import and guarded executor-initializer change over the then-current original PR branch, preserving the other contributors' newer methods. Published commit: `65244806766f6a8c8ccbbb84c0a599d458a25584`, with sole parent `7fc64067e52aa643836f3114e7c08a774073dabb`. Published `CastDeviceSession.kt` blob: `87e024e3d7a0004f50081b67f3875188ac878e99`; the publisher read back the complete content and PR head. Source: https://github.com/woahwhattheheck/GmsCore/commit/65244806766f6a8c8ccbbb84c0a599d458a25584.

The runtime evidence below is for the captured baseline and guarded candidate blobs listed above. It does not claim that the other contributors' intervening method changes were executed by this reproduction.

## What was executed

`CastDeviceSessionTimerTest.kt` contains two checks:

1. Queue 256 real `GET_STATUS` requests (10-second deadline) and 256 real `LAUNCH` requests (30-second deadline). Decode the 512 outgoing protocol frames and deliver matching status/error replies. Check all 512 callback outcomes, zero pending requests, cancellation of every timeout, and the remaining executor queue size.
2. Invoke the existing private `requestReceiver` entry point on the session executor with its timeout parameter set to 50 ms. Check that an unanswered request calls its callback with null exactly once and clears its pending entry. Production timeout constants are unchanged.

| Source | Host SDK constant | Canceled timers retained after 512 replies | Callbacks | Pending requests | JUnit result |
| --- | ---: | ---: | ---: | ---: | --- |
| Baseline | 21 | 512 | 512 | 0 | 1 intended queue assertion failure out of 2 tests |
| Guarded candidate | 21 | 0 | 512 | 0 | 2 passed |
| Guarded candidate | 19 | 512 | 512 | 0 | 2 passed; prior behavior preserved |
| Guarded candidate | 20 | 512 | 512 | 0 | 2 passed; prior behavior preserved |

The deadline check completed once in every run. SDK 19/20 controls reused the same compiled candidate classes. `AndroidBuildFixture.kt` supplies only SDK constants on the host JVM; these controls exercise the production branch condition, not Android runtime/linkage behavior. Baseline and candidate elapsed request/reply observations were 243 ms and 173 ms, respectively. These are single-run observations and do not establish a throughput speedup. Queue counts do not establish captured-closure or heap retention.

## Reproduce

The captured `run_focused.py` is the actual runner used, adapted from the existing Cast destination runner. It pins read-only dependency paths from the shared cloud environment. `compiler-classpath.txt` records that environment's exact compiler paths. On another machine, map its `java`, `cache`, and `gradle_lib` constants, and the compiler-classpath entries, to local copies of the same dependencies; no full Android SDK or Gradle build is required. Place `org.json:json:20231013` at `deps/json-20231013.jar`.

Runtime: OpenJDK 17.0.20.1+1; Kotlin compiler 2.2.21 targeting JVM 1.8. Compiler classpath: `kotlin-compiler-embeddable`, `kotlin-stdlib`, `kotlin-script-runtime`, and `kotlin-daemon-embeddable` 2.2.21; `kotlin-reflect` 1.6.10; `kotlinx-coroutines-core-jvm` 1.8.0; JetBrains annotations 13.0. Application/test classpath: Kotlin stdlib 2.3.0; Wire runtime JVM 6.4.6; Okio JVM 3.17.0; JetBrains annotations 13.0; JSON 20231013; JUnit 4.13.2; Hamcrest core 1.3. Compiler and test JVM heap limits were 512 MiB and 256 MiB.

Run from this directory:

```bash
python run_focused.py base 21
# Expected exit 1: exactly the canceled-queue assertion fails.
python run_focused.py work 21
# Expected exit 0: two tests pass.
python run_focused.py work 19 --reuse-classes
python run_focused.py work 20 --reuse-classes
# Expected exit 0: two tests pass in each compatibility control.
```

The runner compiles the captured production files, both host test files, and `generated/**/*.kt`, then invokes `org.junit.runner.JUnitCore org.microg.gms.cast.channel.CastDeviceSessionTimerTest`. Its `cast.timer.sdk` and `cast.timer.expectedRetained` system properties select the host condition and expected observation. `*-junit.log` files retain the exact outputs; `validation.json` records measurements, identities, limitations, and SHA-256 hashes. No fixture or validation dependency is proposed for the production source tree.
