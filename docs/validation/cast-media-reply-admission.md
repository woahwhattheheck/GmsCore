# Cast media reply admission

A tracked SEEK or STOP could receive a MEDIA_STATUS response for another native media item. The controller rejected the response, but first replaced the current native ID, logical item ID, playback state, position and duration. The matching request ID did not make that other item the requested item.

The controller now checks a positive native-ID mismatch before changing state. The remaining response handling stays in its previous order. An authoritative empty status still clears ended playback; empty STOP acknowledgements, matching item replies, LOAD replies and unsolicited updates retain their behavior.

Google's [media message contract](https://developers.google.com/cast/docs/media/messages) distinguishes the request ID used for response correlation from the media session ID identifying a particular playback.

## Executed check — 2026-10-04

Source baseline: `8ab006657480b789354f9ee3f14ee63875c6c2e9`.

| Exact source | Controller blob | Result |
| --- | --- | --- |
| Before | `d615486989a895cabaf0374a35ac9b474251a375` | Compiled; 14 passed, 2 failed |
| After | `ed549ff958a2479e396ba494a6c074f89e6493a2` | Compiled; 16 passed, 0 failures |

Both runs used the same maintained `RemotePlaybackResponseOwnershipTest` blob `8b8216324b105d9afb160d04e8e3669bfcb0810c`. Two added cases exercise mismatched current SEEK/STOP replies and assert that the complete current item remains intact with exactly one error callback, including repeat delivery. One added control preserves current-item empty-status clearing. The previous 13 cases are unchanged.

Execution used the complete controller source, OpenJDK 17.0.20.1, Java 8 target, JUnit 4.13.2, AndroidX and Robolectric 4.14.1 / API 28. The controller and maintained test were compiled with javac and run through JUnitCore using retained compiled peer modules. Android Intent, Bundle and MediaItemStatus executed through Robolectric. No replacement Android classes or copied controller implementation were used.

This is a focused controller run. It is not a new complete Gradle build, APK, Binder/transport integration, performance benchmark, physical Chromecast demonstration, sponsor acceptance or payout. The original PR and BountyHub submission retain those remaining acceptance steps.

The normal maintained-module selector for subsequent integration is:
```sh
./gradlew :play-services-cast-core:testDebugUnitTest --tests org.microg.gms.cast.RemotePlaybackResponseOwnershipTest
```
That Gradle command was not executed for this receipt.
