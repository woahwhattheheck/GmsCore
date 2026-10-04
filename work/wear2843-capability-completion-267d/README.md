# Wear capability query completion

This additive packet fixes two observed defects in the existing Wear #2843 source:

- `getAllCapabilities` built the successful response data but never delivered the callback.
- `shouldIncludeNode` included disconnected and unknown nodes after its reachable-filter scan found no connected match.

The production delta adds the success callback and returns false for the unmatched reachable filter. Three focused Robolectric regressions exercise the actual service entrypoint, main queue, and SQLite capability queries: empty success, populated all/reachable results, and an empty reachable result. They use the existing dependencies.

## Apply to the existing source

Export this directory outside the target worktree. Reconstruct the existing `fc70e055d9120e6962ef97652a9d8715e916a0db` source with the two original packets described in `work/wear2843-validation-source-20261003/README.md`. The artifact commit itself is not that source tree.

Verify the patch SHA-256 from `manifest.json`, then apply the patch with `git apply --check --index` followed by `git apply --index`. On the unchanged `fc70e055` source, the resulting tree must be `47f914257cac8f6ed8cc52d615c0861068540121`.

On a newer combined source, inspect the same two method bodies and apply the narrow delta while preserving the newer edits. Do not replace the complete service file or rewrite the original PR3204 contribution history. The RPC, channel identity, BLE, and asset changes remain with their existing owners.

## Execute in the existing cloud runner

```bash
GRADLE_MICROG_VERSION_WITHOUT_GIT=1 ./gradlew --no-daemon --console=plain \
  :play-services-wearable-core:testDebugUnitTest \
  --tests org.microg.gms.wearable.CapabilityServiceTest
```

Record the actual source tree, command, exit code, and JUnit XML. Source diff and patch-application checks passed at packet creation; Gradle execution is pending the existing `WEAR-CLOUD-998E` runner. The patch does not establish a full combined build, physical watch behavior, upstream acceptance, or bounty award.

This is a component delivery for the existing issue integration. It creates no replacement issue-level PR or BountyHub claim.
