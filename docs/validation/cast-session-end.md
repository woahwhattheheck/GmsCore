# Reentrant session completion

`SessionImpl.notifySessionEnded` announces `onSessionEnding` before completing the session. A listener can synchronously call `notifySessionEnded` again. The inner call then completes the session, but the outer call previously announced a second completion and queued cleanup again.

The two-line guard checks whether that callback already completed the session. Ordinary termination and the existing proxy-error fallback are unchanged.

## Focused JVM reproduction

Executed October 4, 2026 on OpenJDK 17.0.20, compiling the complete production `SessionImpl` with Java 8 language/API compatibility. Minimal Android/Binder types and recording session-manager/router/proxy collaborators supplied the boundaries; the test did not copy the state machine or change private state.

The connected-session case set an ending listener to `session.notifySessionEnded(71)` and then invoked `session.notifySessionEnded(72)`:

| Observation | Before | After |
| --- | --- | --- |
| Completion error codes | 71, 72 | 71 |
| Cleanup dispatches | 2 | 1 |
| Final session state | Disconnected | Disconnected |

The same five cases produced **4 passing / 1 failing before**, **5 passing / 0 failing after**. The four controls covered ordinary completion, repeated terminal calls, `end(true)` followed by completion, and a proxy `RemoteException` during `end(false)` (original exception rethrown, `INTERNAL_ERROR` delivered).

Original source blob: `5de4cf7828b53593b8a98b937309d95acbec5a82` at PR head `b282f0d516b6fd093bd754d0dedac3de2bad2723`.
Candidate source blob: `1463df7a706b8b734006f39f0c6741b91fd91f42`.
Only the two added Java lines change production behavior; CRLF is preserved.

This is synchronous class-level lifecycle evidence. Android/Binder integration, a physical receiver, cross-thread safety, and same-object lifecycle reuse were not exercised.
