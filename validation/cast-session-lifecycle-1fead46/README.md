# Cast session ownership regression packet

This packet compiles and executes the actual `SessionManagerImpl.java` and
`SessionImpl.java` against minimal handwritten host collaborators. It contains
seven deterministic lifecycle scenarios with 39 assertions.

The baseline compiled successfully and produced **29 passing / 10 failing
assertions**. The final candidate compiled successfully and produced **39 passing /
0 failing assertions**. The ten failing assertions describe four failing
scenarios; they are not ten independently discovered defects.

## Source provenance

- `baseline/` contains byte-for-byte copies from commit
  `de7c5de9f2e7c314a7b367bb4d0334672a1d471e`.
- `candidate/` contains the final candidate copies. Both files were checked for
  byte equality with the actual edited product files.
- The final after run used `--source-dir` pointing directly at those actual product
  files, rather than compiling the frozen copies. Their recorded SHA-256 hashes
  match `candidate/` exactly.
- The original source directory is
  `play-services-cast-framework/core/src/main/java/com/google/android/gms/cast/framework/internal/`.
- `results/source-manifest.json` records the baseline commit, source hashes, and
  equality checks. `results/before-metadata.json` and `results/after-metadata.json`
  record the exact compile inputs and compile/run exit codes.

The harness does not extract, rewrite, substitute, or mirror either production
class's lifecycle algorithms. The Java compiler receives the two source files as
ordinary source inputs alongside the collaborators and assertion program.

## Reproduce

Requirements: Python 3 and a Java 17 runtime that includes the `jdk.compiler`
module. No `javac` launcher, Android SDK, Gradle, JUnit, network access, or downloaded
dependencies are needed. The captured run used OpenJDK 17.0.20.

Run these commands separately from the packet directory:

```sh
python3 run.py before
python3 run.py after
```

Expected exits are **1 for before** and **0 for after**. The before exit is the
intentional result of regression assertions failing, not a compilation failure.
Do not join the two commands with `&&`, since that would skip the after run.

To compile the same assertions against another actual product source directory:

```sh
python3 run.py after --source-dir /path/to/source/com/google/android/gms/cast/framework/internal
```

The directory must contain both Java files. By default the script uses
`baseline/` for before and `candidate/` for after. It invokes the compiler as
`java -m jdk.compiler/com.sun.tools.javac.Main`, creates separate class directories
under `build/`, and captures compiler/runtime output under `results/`. Compilation
has a 30-second limit and execution has a 15-second limit. The worker callback has
a 2-second join limit, and the explicit host queue drain has a 100-action bound.
Build outputs and `.class` files are excluded by `.gitignore`.

Both captured compilations exited 0. Each compiler log includes the ordinary
unchecked/unsafe-operation note from the minimal generic host collaborators; no
compilation errors occurred.

## Scenarios and observed results

| Scenario | Baseline | Final candidate |
| --- | --- | --- |
| Connected A is replaced by connected B; A reports its end late | B remains current and the old A listener event still fires, but B's saved record is erased | B's saved record, current pointer, selected route, and old listener event are preserved |
| Connected A is replaced by B while B is still starting and has not saved | A's persisted record is cleared when A ends; B remains current and selected | Same correct cleanup is preserved |
| Rapid A1 -> B -> A2, with A1 and A2 sharing both route ID and application session ID | A1's late end erases A2's saved record and selects the default route | A2's distinct object ownership protects its saved record and selected route |
| A ends on a real host worker thread; A2 starts on the same route before queued cleanup is drained | The queued old cleanup unselects A2 | Ownership is rechecked when the queued action runs; A2 stays selected |
| Saved recovery explicitly enabled; seeded saved session enters a real resume attempt and then fails | Saved state/current pointer clear and the default route is selected | Same correct cleanup is preserved |
| Saved recovery explicitly disabled, with valid saved state and a recoverable provider | Preferences are read, recoverability and route availability are queried, and the saved route is selected both initially and on a later route-added callback | No preference access or write, recoverability query, route lookup, or selection occurs; saved state remains intact |
| Normal active session is ended through the manager and reports completion | Saved state/current pointer clear; the default route is selected once | Same correct cleanup is preserved |

Full captured output is in `results/before.txt` and `results/after.txt`.

## Runtime scope

The two production Java classes execute real method calls and state transitions.
The assertion program drives route-selection callbacks and proxy notifications.
The host collaborators supply object wrappers, in-memory preferences, interface
signatures, a recoverable session provider, route state/counters, options, and a
main-thread queue. Client proxy methods remain inert unless a scenario explicitly
calls a production notification method.

This is **not an Android, Binder IPC, emulator, physical receiver, or device
performance test**. Preferences are in host RAM; their disk persistence is not
exercised. The queue scenario uses a real Java worker thread plus an explicit
bounded drain, establishing one controlled ordering rather than measuring real
Android handler scheduling or cross-thread stress behavior. Network discovery,
receiver launch, receiver shutdown, and Cast transport behavior are outside this
packet's scope.

## Shared branch composition and publication

The shared submission advanced during this work. `current-head/` preserves the
exact two source files read from commit `13acaa56bc6672e4508e185b82a57d212c2ac2d8`;
their Git blob IDs were checked against the provider response. The same blobs
were verified on the subsequent parent `11cb6fcef03ac23877a23547224047c5a1ecdf55`.
That source includes the previous current-session persistence guard. It passes
30 assertions and fails 9, including leaving A's obsolete record when B is still
starting. Run `python3 run.py current` to reproduce that expected exit 1.

The completed repair extends that guard to explicit saved-record ownership and
was published without force at commit
`1e66e22a4ade4d9e68e703688706bde80fc26933`, parent
`11cb6fcef03ac23877a23547224047c5a1ecdf55`, on the existing branch
`bounty/cast580-w01-20261001` and existing microg/GmsCore PR #3845. The other
contributors' discovery, routing, artwork, and heartbeat changes remain in its
ancestry. Both published source contents were read back and matched the tested
candidate bytes exactly. No additional sponsor PR or BountyHub claim was created.

A separate Android Gradle compile target was attempted locally. It stopped at
SDK resolution with `SDK location not found`; this harness does not contain an
Android SDK. That attempt is not an Android compile pass. The existing GitHub
Gradle build for the published source is run 37184675209 and was queued at this
packet's capture. Consult that run directly for its current status.

Attribution: GPT-6 Astra Pro / ASTRA-BH-1FEAD46 / ChatGPT cloud harness
1fead46c005a. Existing persistence work by Astra-4614 was retained and refined.
