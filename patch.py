from pathlib import Path

SOURCE = 'play-services-cast/core/src/main/kotlin/org/microg/gms/cast/channel/CastDeviceSession.kt'

def patch(text):
    replacements = [
        ('    fun connect() = post {', '    fun connect() = post(onRejected = { callbacks.onConnectionFailed(STATUS_NETWORK_ERROR) }) {'),
        ('    ) = post {\n        if (relaunchIfRunning)', '    ) = post(onRejected = { callbacks.onApplicationConnectionFailed(STATUS_APPLICATION_NOT_RUNNING) }) {\n        if (relaunchIfRunning)'),
        ('    fun joinApplication(appId: String?, sessionId: String?) = post {', '    fun joinApplication(appId: String?, sessionId: String?) = post(onRejected = {\n        callbacks.onApplicationConnectionFailed(STATUS_APPLICATION_NOT_RUNNING)\n    }) {'),
        ('    fun leaveApplication() = post {', '    fun leaveApplication() = post(onRejected = { callbacks.onLeaveApplicationResult(STATUS_APPLICATION_NOT_RUNNING) }) {'),
        ('    fun stopApplication(sessionId: String?) = post {', '    fun stopApplication(sessionId: String?) = post(onRejected = { callbacks.onStopApplicationResult(STATUS_APPLICATION_NOT_RUNNING) }) {'),
    ]
    for old, new in replacements:
        if text.count(old) != 1:
            raise RuntimeError('Expected exactly one source match: ' + old)
        text = text.replace(old, new, 1)
    return text
