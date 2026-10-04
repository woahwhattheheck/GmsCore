from pathlib import Path
import hashlib
import json

root = Path('play-services-cast/core/src')
p = root / 'main/java/org/microg/gms/cast/CastMediaRouteController.java'
raw = p.read_bytes()
git_blob = lambda data: hashlib.sha1(b'blob ' + str(len(data)).encode() + b'\0' + data).hexdigest()
assert git_blob(raw) == 'e4468d06ed636a532df70ba29f0c632f6c430b7e', 'source changed; do not replace peer edits'
eol = b'\r\n' if b'\r\n' in raw else b'\n'
def replace_once(old, new):
    global raw
    a = old.encode().replace(b'\n', eol)
    b = new.encode().replace(b'\n', eol)
    assert raw.count(a) == 1, 'ambiguous patch anchor'
    raw = raw.replace(a, b, 1)
replace_once('    private static final String MEDIA_NAMESPACE = "urn:x-cast:com.google.cast.media";',
'''    private static final String MEDIA_NAMESPACE = "urn:x-cast:com.google.cast.media";
    // MediaRouter permits a null callback when the caller does not need a result.
    private static final MediaRouter.ControlRequestCallback NO_RESULT_CALLBACK = new MediaRouter.ControlRequestCallback() {};''')
replace_once('''        switch (action) {
            case MediaControlIntent.ACTION_PLAY:''',
'''        if (callback == null) callback = NO_RESULT_CALLBACK;
        switch (action) {
            case MediaControlIntent.ACTION_PLAY:''')
replace_once('''        PendingControl pending = requestId != 0 ? takePendingControl(requestId) : null;
        JSONArray statuses = json.optJSONArray("status");''',
'''        PendingControl pending = requestId != 0 ? takePendingControl(requestId) : null;
        // Nonzero ids are replies, not unsolicited status notifications. A reply whose
        // request expired or completed must not restore an older playback snapshot.
        if (requestId != 0 && pending == null) return;
        if (pending != null && pending.receiverMediaSessionId > 0
                && pending.receiverMediaSessionId != mediaSessionId) {
            pending.callback.onError("Remote playback item was replaced", null);
            return;
        }
        JSONArray statuses = json.optJSONArray("status");''')
p.write_bytes(raw)
q = root / 'test/java/org/microg/gms/cast/RemotePlaybackResponseOwnershipTest.java'
assert not q.exists(), 'test path already exists'
q.write_bytes(Path('work/cast580-media-replies/RemotePlaybackResponseOwnershipTest.java').read_bytes())
out = Path('cast580-evidence')
out.mkdir(exist_ok=True)
(out / p.name).write_bytes(raw)
(out / q.name).write_bytes(q.read_bytes())
(out / 'source.json').write_text(json.dumps({'base': '4609569f0e5a53e7b321ee93f23a078c6f4cecd6', 'files': {str(p):git_blob(raw),str(q):git_blob(q.read_bytes())}}, indent=2)+'\n')
