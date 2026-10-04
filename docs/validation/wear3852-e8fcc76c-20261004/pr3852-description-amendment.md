This PR integrates the current-generation Wear OS support work for microG issue #2843, preserving the existing Wear source ancestry and adding the current integration fixes.

The source covers Bluetooth watch connections and lifecycle, pairing/setup and terms consent, the explicit account-transfer flow, notification mirroring and media controls, and Wearable Data Layer APIs including channels, RPC, capabilities and data-item queries.

Validation is pinned to the source actually executed:

- Current repaired product source `e8fcc76cda77165de370ae599ab4d649e6f03a4f`: **105/105 Wear core tests and 3/3 Wear API tests passed**, with zero failures, errors, or skips. Instrumentation-test Java compiled and `assembleVtmDefaultDebug` succeeded in the same combined Gradle invocation.
- Wear core `lintDebug` passed with **0 errors and 14 warnings**. The seven integration errors were fixed by keeping conditional asset removal on the concrete concurrent-map API, guarding Android-21 errno references, and handling revoked Bluetooth permission during service shutdown.
- The assembled APK is **107,564,514 bytes**, SHA-256 `5cc6dbfd201df2100da93d68cd1bc78d4a76362303887357bf4155e53d82b18d`.
- [Successful execution 37209801957](https://github.com/woahwhattheheck/GmsCore/actions/runs/37209801957) and [source-pinned report, XML counts, logs, and artifact hashes](https://github.com/woahwhattheheck/GmsCore/blob/bounty/wear2843-clean-submit-20261004/docs/validation/wear3852-e8fcc76c-20261004.md).

The earlier 81-test result and earlier APK remain historical evidence for source `73793ddc4e084017a7098aade07703c40ab825bd`. Later same-source executions at `1062b78d` are retained separately; their test totals are duplicate coverage and are not additive. The current combined result above includes the later callback, asset-storage, zero-length send, send-offset, completion-drain, and integration lint changes at its exact source pin.

No physical-watch pairing or feature acceptance is claimed. The shutdown permission handler prevents the exception from escaping the service callback; complete cleanup after a permission exception is not asserted.
