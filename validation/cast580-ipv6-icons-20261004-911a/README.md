# Cast receiver icon URI verification

This packet records the focused verification of the IPv6 icon-authority correction on the existing microG Cast submission. Production commit: `86b26c5c25ed9be9058d5a6804722dc015df9500`; existing PR: https://github.com/microg/GmsCore/pull/3845.

Only `play-services-cast/src/main/java/com/google/android/gms/cast/CastDevice.java` changed on that delivery branch. Its baseline Git blob is `33cb88ad94360d13148b4ef707397c3f843aa20e`; the repaired blob is `413c987d4b46039fe925a26eaa91567b633ddba5`. Original CRLF bytes are preserved.

## Actual execution

The included main program compiled and invoked the production CastDevice constructor and WebImage using Android API 28's real Uri implementation and compiled production microG support classes. Baseline construction failed six assertions across unscoped and scoped IPv6 addresses. The corrected class passed IPv4, IPv6, scoped IPv6, null-icon, raw-address, service-port, and encoded-path/query checks.

The corrected constructor's emitted URL also fetched 12 exact bytes from an HTTP server bound to `[::1]:8008`. The observed request was `GET /setup/icon.png?size=32 HTTP/1.1`; the URL fragment was omitted normally. Both original console logs are retained. This is a local constructor and HTTP-fetch result, not physical-receiver acceptance or proof of scoped network routing.

## Runtime and replay

Android runtime artifact: `org.robolectric:android-all:9-robolectric-4913185-2`. Its downloaded JAR matched Maven SHA-1 `92c6ca3712d7a06f76f70655ec81162ff372fb19`.

Restore it when needed from:
https://repo.maven.apache.org/maven2/org/robolectric/android-all/9-robolectric-4913185-2/android-all-9-robolectric-4913185-2.jar

`run-checks.sh` retains the executed commands and original cloud paths as defaults. `CAST_RUNTIME`, `CAST_REPAIR`, `CAST_JAVAC`, and `CAST_SUPPORT` may be overridden for another workspace. Supply these existing inputs:

- JDK 17 with javac and java.
- The pinned Android runtime JAR under CAST_RUNTIME.
- This packet's CastDeviceIconCheck.java under CAST_RUNTIME.
- Baseline CastDevice.java under CAST_RUNTIME/baseline, recovered from source commit `41238598f23d8357c50036988e54cc5ae5045e61`.
- Repaired production source under CAST_REPAIR at its ordinary repository path, recovered from the production commit above.
- Built production classes.jar files for play-services-base and play-services-basement at the module paths shown in the runner.

The runner creates its class-output directories, expects the baseline failure, then executes the corrected constructor and loopback fetch. It makes no phone, external receiver, inference or bounty-platform request. The heavyweight reproducible JAR is a runtime dependency, not part of this source packet.

