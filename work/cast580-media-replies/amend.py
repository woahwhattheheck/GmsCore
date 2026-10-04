from pathlib import Path
import hashlib
import json

root = Path('play-services-cast/core/src')
p = root / 'main/java/org/microg/gms/cast/CastMediaRouteController.java'
raw = p.read_bytes()
eol = b'\r\n' if b'\r\n' in raw else b'\n'
def replace_once(old, new):
    global raw
    a, b = (x.encode().replace(b'\n', eol) for x in (old, new))
    assert raw.count(a) == 1
    raw = raw.replace(a, b, 1)
replace_once('    private long nextMediaRequestId = 1;', '''    // Randomize the initial request id as required by the Cast media protocol.
    private final long firstMediaRequestId = 1 + (UUID.randomUUID().getLeastSignificantBits() & 0x3fffffffL);
    private long nextMediaRequestId = firstMediaRequestId;''')
replace_once('''        // Nonzero ids are replies, not unsolicited status notifications. A reply whose
        // request expired or completed must not restore an older playback snapshot.
        if (requestId != 0 && pending == null) return;
        if (pending != null && pending.receiverMediaSessionId > 0
                && pending.receiverMediaSessionId != mediaSessionId) {''', '''        // Status from another sender also carries a nonzero request id. Ignore only
        // our own completed/expired requests, while retaining other senders' updates.
        if (pending == null && requestId >= firstMediaRequestId && requestId < nextMediaRequestId) return;
        if (pending != null && pending.receiverMediaSessionId > 0
                && pending.receiverMediaSessionId != mediaSessionId
                && !(pending.stopsPlayback && mediaSessionId == 0)) {''')
p.write_bytes(raw)
q = root / 'test/java/org/microg/gms/cast/RemotePlaybackResponseOwnershipTest.java'
s = q.read_text()
s = s.replace('        track(controller, new CastMediaRouteController.PendingControl(', '        long requestId = track(controller, new CastMediaRouteController.PendingControl(')
s = s.replace('String reply = "{\\"type\\":\\"MEDIA_STATUS\\",\\"requestId\\":7,\\"status\\":" + status + "}";', 'String reply = "{\\"type\\":\\"MEDIA_STATUS\\",\\"requestId\\":" + requestId + ",\\"status\\":" + status + "}";')
s = s.replace('''        callbacks(controller).onTextMessage(MEDIA,
                "{\\"type\\":\\"MEDIA_STATUS\\",\\"requestId\\":7,\\"status\\":[{\\"mediaSessionId\\":42,\\"playerState\\":\\"PAUSED\\"}]}");''', '''        long requestId = completeRequest(controller);
        callbacks(controller).onTextMessage(MEDIA,
                "{\\"type\\":\\"MEDIA_STATUS\\",\\"requestId\\":" + requestId + ",\\"status\\":[{\\"mediaSessionId\\":42,\\"playerState\\":\\"PAUSED\\"}]}");''')
s = s.replace('''        callbacks(controller).onTextMessage(MEDIA, "{\\"type\\":\\"MEDIA_STATUS\\",\\"requestId\\":7,\\"status\\":[]}");
        assertCurrentItem(controller);''', '''        long requestId = completeRequest(controller);
        callbacks(controller).onTextMessage(MEDIA, "{\\"type\\":\\"MEDIA_STATUS\\",\\"requestId\\":" + requestId + ",\\"status\\":[]}");
        assertCurrentItem(controller);''')
s = s.replace('\\"requestId\\":7,\\"status\\":[]}', '\\"requestId\\":" + requestId + ",\\"status\\":[]}')
s = s.replace('\\"requestId\\":7,\\"status\\":[{\\"mediaSessionId\\":43', '\\"requestId\\":" + requestId + ",\\"status\\":[{\\"mediaSessionId\\":43')
s = s.replace('''    private static void track(CastMediaRouteController controller, CastMediaRouteController.PendingControl item) throws Exception {
        pending(controller).put(7L, item);
    }''', '''    private static long track(CastMediaRouteController controller, CastMediaRouteController.PendingControl item) throws Exception {
        long requestId = field("nextMediaRequestId").getLong(controller);
        field("nextMediaRequestId").setLong(controller, requestId + 1);
        pending(controller).put(requestId, item);
        return requestId;
    }

    private static long completeRequest(CastMediaRouteController controller) throws Exception {
        RecordingCallback result = new RecordingCallback();
        long requestId = track(controller, new CastMediaRouteController.PendingControl("new-session", null, false, false, 43, result));
        callbacks(controller).onTextMessage(MEDIA, "{\\"type\\":\\"LOAD_FAILED\\",\\"requestId\\":" + requestId + "}");
        assertEquals(1, result.errors);
        return requestId;
    }''')
anchor = '    @Test public void supportedRequestsAllowNullResultCallback() {'
extra = '''    @Test public void otherSenderNonzeroStatusStillUpdatesPlayback() throws Exception {
        CastMediaRouteController controller = currentItem();
        long otherId = field("nextMediaRequestId").getLong(controller) + 1000;
        callbacks(controller).onTextMessage(MEDIA,
                "{\\"type\\":\\"MEDIA_STATUS\\",\\"requestId\\":" + otherId + ",\\"status\\":[{\\"mediaSessionId\\":44,\\"playerState\\":\\"PAUSED\\"}]}");
        assertEquals(44, field("mediaSessionId").getLong(controller));
        assertEquals(MediaItemStatus.PLAYBACK_STATE_PAUSED, field("mediaPlaybackState").getInt(controller));
    }

    @Test public void stopAcknowledgementAfterSpontaneousEndStillSucceeds() throws Exception {
        CastMediaRouteController controller = currentItem();
        RecordingCallback result = new RecordingCallback();
        long requestId = track(controller, new CastMediaRouteController.PendingControl("new-session", null, false, true, 43, result));
        CastDeviceSession.Callbacks callbacks = callbacks(controller);
        callbacks.onTextMessage(MEDIA, "{\\"type\\":\\"MEDIA_STATUS\\",\\"requestId\\":0,\\"status\\":[]}");
        callbacks.onTextMessage(MEDIA, "{\\"type\\":\\"MEDIA_STATUS\\",\\"requestId\\":" + requestId + ",\\"status\\":[]}");
        assertEquals(1, result.successes);
        assertEquals(0, result.errors);
        assertEquals(0, field("mediaSessionId").getLong(controller));
    }

'''
assert s.count(anchor) == 1
s = s.replace(anchor, extra + anchor)
assert s.count('@Test') == 13
assert '\\"requestId\\":7' not in s
q.write_text(s)
out = Path('cast580-evidence')
git_blob = lambda data: hashlib.sha1(b'blob ' + str(len(data)).encode() + b'\0' + data).hexdigest()
(out / p.name).write_bytes(p.read_bytes())
(out / q.name).write_bytes(q.read_bytes())
(out / 'source.json').write_text(json.dumps({'base':'4609569f0e5a53e7b321ee93f23a078c6f4cecd6','files':{str(p):git_blob(p.read_bytes()),str(q):git_blob(q.read_bytes())}}, indent=2)+'\n')
