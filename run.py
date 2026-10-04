from pathlib import Path
import difflib, hashlib, json, os, shutil, subprocess, urllib.request
from patch import SOURCE, patch

ROOT = Path.cwd()
OUT = ROOT / 'evidence'
OUT.mkdir(exist_ok=True)
BASE = '4609569f0e5a53e7b321ee93f23a078c6f4cecd6'
FIXTURE = '86fe3c265d3712a21852f9465d40aeece2ad21e5'
PREFIX = 'validation/cast580-timers-b398-20261004/'
TEST = 'CastDeviceSessionOperationCompletionTest.kt'

def command(args, **kwargs):
    return subprocess.run(args, check=True, text=True, capture_output=True, **kwargs).stdout

def blob(data):
    return hashlib.sha1(b'blob ' + str(len(data)).encode() + b'\0' + data).hexdigest()

subject = ROOT / 'subject'
command(['git', 'init', str(subject)])
command(['git', '-C', str(subject), 'remote', 'add', 'origin', 'https://github.com/woahwhattheheck/GmsCore.git'])
for rev in (BASE, FIXTURE):
    command(['git', '-C', str(subject), 'fetch', '--depth=1', 'origin', rev])
command(['git', '-C', str(subject), 'checkout', '--detach', BASE])
original = (subject / SOURCE).read_bytes()
assert blob(original) == '87b5bb5f0e95aa0be7c7d46274b1cde04f290dfe'
updated = patch(original.decode()).encode()
(OUT / 'CastDeviceSession.kt').write_bytes(updated)
shutil.copyfile(TEST, OUT / TEST)
(OUT / 'change.patch').write_text(''.join(difflib.unified_diff(original.decode().splitlines(True), updated.decode().splitlines(True), fromfile='a/'+SOURCE, tofile='b/'+SOURCE)))
for name in command(['git', '-C', str(subject), 'ls-tree', '-r', '--name-only', FIXTURE, PREFIX + 'generated']).splitlines():
    dest = ROOT / name
    dest.parent.mkdir(parents=True, exist_ok=True)
    dest.write_bytes(subprocess.run(['git', '-C', str(subject), 'show', FIXTURE + ':' + name], check=True, capture_output=True).stdout)
fixture = ROOT / 'AndroidBuildFixture.kt'
fixture.write_text('package android.os\nobject Build { object VERSION { const val SDK_INT = 35 }; object VERSION_CODES { const val LOLLIPOP = 21 } }\n')

deps = ROOT / 'deps'
deps.mkdir(exist_ok=True)
def jar(group, artifact, version):
    dest = deps / f'{artifact}-{version}.jar'
    if not dest.exists():
        url = 'https://repo.maven.apache.org/maven2/' + group.replace('.', '/') + f'/{artifact}/{version}/{artifact}-{version}.jar'
        urllib.request.urlretrieve(url, dest)
    return str(dest)

compiler = [jar(*item) for item in [
 ('org.jetbrains.kotlin','kotlin-compiler-embeddable','2.2.21'),
 ('org.jetbrains.kotlin','kotlin-stdlib','2.2.21'),
 ('org.jetbrains.kotlin','kotlin-script-runtime','2.2.21'),
 ('org.jetbrains.kotlin','kotlin-daemon-embeddable','2.2.21'),
 ('org.jetbrains.kotlin','kotlin-reflect','1.6.10'),
 ('org.jetbrains.kotlinx','kotlinx-coroutines-core-jvm','1.8.0'),
 ('org.jetbrains','annotations','13.0')]]
runtime = [jar(*item) for item in [
 ('org.jetbrains.kotlin','kotlin-stdlib','2.3.0'),
 ('com.squareup.wire','wire-runtime-jvm','6.4.6'),
 ('com.squareup.okio','okio-jvm','3.17.0'),
 ('org.jetbrains','annotations','13.0'),
 ('org.json','json','20231013'),
 ('junit','junit','4.13.2'),
 ('org.hamcrest','hamcrest-core','1.3')]]
classpath = os.pathsep.join(runtime)
channel = subject / SOURCE.replace('CastDeviceSession.kt', 'CastChannel.kt')
old_test = subject / 'play-services-cast/core/src/test/kotlin/org/microg/gms/cast/channel/CastDeviceSessionSendCompletionTest.kt'
common = [str(channel), str(ROOT / TEST), str(old_test), str(fixture)] + [str(p) for p in (ROOT / PREFIX / 'generated').rglob('*.kt')]
assert len(common) > 4
results = {}
for variant, data in [('before', original), ('after', updated)]:
    source = ROOT / variant / 'CastDeviceSession.kt'
    source.parent.mkdir(exist_ok=True)
    source.write_bytes(data)
    classes = ROOT / (variant + '-classes')
    classes.mkdir(exist_ok=True)
    compile_cmd = ['java','-Xmx512m','-cp',os.pathsep.join(compiler),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','1.8','-cp',classpath,'-d',str(classes),str(source),*common]
    compiled = subprocess.run(compile_cmd, text=True, capture_output=True, timeout=120)
    (OUT / (variant + '-compile.log')).write_text(compiled.stdout + compiled.stderr)
    if compiled.returncode:
        print(compiled.stdout, compiled.stderr)
        raise RuntimeError(variant + ' compilation failed')
    run_cmd = ['java','-Xmx256m','-cp',str(classes)+os.pathsep+classpath,'org.junit.runner.JUnitCore','org.microg.gms.cast.channel.CastDeviceSessionOperationCompletionTest','org.microg.gms.cast.channel.CastDeviceSessionPendingCloseControlTest','org.microg.gms.cast.channel.CastDeviceSessionSendCompletionTest']
    result = subprocess.run(run_cmd, text=True, capture_output=True, timeout=30)
    (OUT / (variant + '-junit.log')).write_text(result.stdout + result.stderr)
    results[variant] = {'exit': result.returncode, 'stdout': result.stdout, 'stderr': result.stderr, 'source_blob': blob(data)}
    print(variant, result.returncode, result.stdout, result.stderr)
receipt = {'source_base': BASE, 'fixture_base': FIXTURE, 'source_path': SOURCE, 'test_blob': blob((ROOT/TEST).read_bytes()), 'java': subprocess.run(['java','-version'],text=True,capture_output=True).stderr, 'results': results, 'scope': 'Actual CastDeviceSession, CastChannel, generated Wire protocol and JUnit; host-only Android Build constants, in-memory output, no Android/phone/physical receiver run.', 'dependencies': {p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in deps.glob('*.jar')}}
(OUT / 'receipt.json').write_text(json.dumps(receipt, indent=2))
assert results['before']['exit'] != 0 and 'Tests run: 15,  Failures: 10' in results['before']['stdout'], 'Unexpected baseline result'
assert results['after']['exit'] == 0 and 'OK (15 tests)' in results['after']['stdout'], 'Candidate failed'
