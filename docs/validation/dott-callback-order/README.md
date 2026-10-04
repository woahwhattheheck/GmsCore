# Reproduce the Dott callback-order regression

This evidence package contains pinned original and repaired source extracts, a focused loopback fixture, disposable platform stubs, the recorded logs, and a source manifest. It is intended for a separate evidence artifact or same-fork evidence branch, not as copied production classes in the sponsor PR.

Requirements: an installed JDK 17 and the Kotlin 2.0.21 compiler/runtime jars distributed in the Gradle 8.13 `lib` directory. The script performs no dependency downloads.

From the extracted package, run:

```sh
python3 replay/run.py --jdk-home /path/to/jdk17 --kotlin-lib /path/to/gradle-8.13/lib
```

The script compiles the exact source extracts, runs the same four behavior cases and three controlled latency samples against each version, and writes `replay-results.json`. An expected reproduction reports original `2/4` and repaired `4/4`. Timing is observational; only the latch and callback/cleanup assertions decide pass/fail.

The Java fixture uses the actual service, remote handle, initialization, session, HTTP, and fallback classes. Stubs replace Android/AIDL/context/preference collaborators; they do not implement the production ordering or HTTP logic. See `CALLBACK_ORDER_VALIDATION.md` for the measured result and exact validation boundary.

The one-file product change is in `callback-order.patch`. `publication-manifest.json` lists the exact product file independently of the evidence files. All paths in the runner are supplied as command arguments or resolved relative to the extracted package.
