# Wearable validation source packets

This directory makes the existing frozen 61-test source snapshot retrievable with normal Git and the GitHub contents/blob APIs. It contains the original patch bytes and their reconstruction manifest.

The artifact branch retains the entire base commit `32bc8954ff872d0e1a05ddfae81561b6dbecef69` and adds only these delivery files. **The artifact commit is not the validation source tree.** Export the packets, then apply them in order on the existing clean base worktree.

## Exact inputs

| Packet | Bytes | SHA-256 | Resulting source tree |
| --- | ---: | --- | --- |
| `WEAR2843_OWNER_TREE_FROM_MASTER_20261001.patch` | 955273 | `d21ccf2390342d36cd8d24fcef641c0afb23d570a430e699725f8392af530400` | `8fdf4c0efc0b4be907957ab0be9b552bce5c20b8` |
| `WEAR2843_CURRENT_FIX_DELTA.patch` | 87317 | `0595ce41a7cff13f2ee7308b5ab468395c3afe30efa4ae38f20e417ace43bcc9` | `fc70e055d9120e6962ef97652a9d8715e916a0db` |

The final tree reproduces the source of the original local integration commit `e6582576386a2ab93df538f4914a0473e659a612`. It does not claim that the original local commit itself is remotely fetchable.

## Retrieve and reconstruct

Run in the existing clean Git worktree that is already at the pinned base. Preserve any unrelated dirty work rather than resetting it. Export the packets outside the worktree so they do not change its reconstructed source tree.

```bash
set -euo pipefail

test "$(git rev-parse HEAD)" = "32bc8954ff872d0e1a05ddfae81561b6dbecef69"
test -z "$(git status --porcelain)"

git fetch https://github.com/woahwhattheheck/GmsCore.git bounty/wear2843-validation-source-20261003
wear_packet_commit=$(git rev-parse FETCH_HEAD)
wear_packet_dir=$(mktemp -d)

git show "$wear_packet_commit:work/wear2843-validation-source-20261003/WEAR2843_OWNER_TREE_FROM_MASTER_20261001.patch" > "$wear_packet_dir/WEAR2843_OWNER_TREE_FROM_MASTER_20261001.patch"
git show "$wear_packet_commit:work/wear2843-validation-source-20261003/WEAR2843_CURRENT_FIX_DELTA.patch" > "$wear_packet_dir/WEAR2843_CURRENT_FIX_DELTA.patch"

printf '%s  %s\n' \
  d21ccf2390342d36cd8d24fcef641c0afb23d570a430e699725f8392af530400 "$wear_packet_dir/WEAR2843_OWNER_TREE_FROM_MASTER_20261001.patch" \
  0595ce41a7cff13f2ee7308b5ab468395c3afe30efa4ae38f20e417ace43bcc9 "$wear_packet_dir/WEAR2843_CURRENT_FIX_DELTA.patch" | sha256sum --check --strict

git apply --check --index "$wear_packet_dir/WEAR2843_OWNER_TREE_FROM_MASTER_20261001.patch"
git apply --index "$wear_packet_dir/WEAR2843_OWNER_TREE_FROM_MASTER_20261001.patch"
test "$(git write-tree)" = "8fdf4c0efc0b4be907957ab0be9b552bce5c20b8"

git apply --check --index "$wear_packet_dir/WEAR2843_CURRENT_FIX_DELTA.patch"
git apply --index "$wear_packet_dir/WEAR2843_CURRENT_FIX_DELTA.patch"
test "$(git write-tree)" = "fc70e055d9120e6962ef97652a9d8715e916a0db"
```

Stop on a failed command, byte hash, or tree comparison. The existing owner retains execution of the validation tasks below.

## Build inputs and pending validation

The unchanged baseline declares Gradle 8.13, Android Gradle Plugin 8.13.2, Kotlin plugin 1.9.22, SDK 35 and build-tools 35.0.0 (min SDK 19, target SDK 29). Its normal CI uses Temurin 17. Record the actual JDK and SDK used by the validation runner; the earlier handoff also referenced a JDK 21 environment.

The manifest pins the baseline build-file blob hashes. No dependency or toolchain source was changed by this delivery.

```bash
GRADLE_MICROG_VERSION_WITHOUT_GIT=1 ./gradlew \
  :play-services-wearable-core:testDebugUnitTest \
  :play-services-wearable:testDebugUnitTest \
  :play-services-wearable-core:compileDebugAndroidTestJavaWithJavac
```

The source inventory expects 58 core test methods plus 3 API methods. Only the actual JUnit XML establishes execution counts and outcomes. Preserve command, exit code, source-tree SHA, toolchain and XML results. Android instrumentation compilation is not instrumentation execution.

The separate FD successor (`cc0fdd0a2c4612626d00fbda530ebdfb05f15c45`, tree `24af669342627fba128f27e1c7ff062a57bd1d10`, 62 expected methods) is excluded from this packet.

## Delivery status

The two patch bodies have been checked against the original byte counts and SHA-256 values. This source-delivery task has run no Gradle validation, changed no application source, and opened no issue-level PR or claim.

The artifact-only commit uses GitHub's documented `[skip ci]` marker to avoid launching a duplicate push-triggered build for packet delivery. The repository's base source and workflow files remain unchanged. The independent source validation above remains required. Documentation: https://docs.github.com/en/actions/how-tos/manage-workflow-runs/skip-workflow-runs
