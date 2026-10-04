from pathlib import Path
import json
import subprocess
import sys

root = Path(__file__).resolve().parent
variant = sys.argv[1]
sdk = int(sys.argv[2]) if len(sys.argv) > 2 else 21
label = f"{variant}-api{sdk}"
cache = Path('/workspace/scratch/a07bfe951f29/android-build-runtime/gradle-user-home/caches/modules-2/files-2.1')
gradle_lib = Path('/workspace/scratch/975ea8d599a2/build-runtime/gradle-8.13/lib')
java = '/workspace/scratch/a07bfe951f29/android-build-runtime/jdk-17.0.20.1+1/bin/java'

dependencies = []
for group, artifact, version in [
    ('org.jetbrains.kotlin', 'kotlin-stdlib', '2.3.0'),
    ('com.squareup.wire', 'wire-runtime-jvm', '6.4.6'),
    ('com.squareup.okio', 'okio-jvm', '3.17.0'),
    ('org.jetbrains', 'annotations', '13.0'),
]:
    matches = list(cache.glob(f'{group}/{artifact}/{version}/*/*.jar'))
    assert len(matches) == 1, (artifact, matches)
    dependencies += matches
dependencies += [root / 'deps/json-20231013.jar', gradle_lib / 'junit-4.13.2.jar', gradle_lib / 'hamcrest-core-1.3.jar']
classpath = ':'.join(map(str, dependencies))
sources = [
    root / variant / 'play-services-cast/core/src/main/kotlin/org/microg/gms/cast/channel/CastChannel.kt',
    root / variant / 'play-services-cast/core/src/main/kotlin/org/microg/gms/cast/channel/CastDeviceSession.kt',
    root / 'repro/CastDeviceSessionTimerTest.kt',
    root / 'repro/AndroidBuildFixture.kt',
]
sources += sorted((root / 'generated').rglob('*.kt'))
classes = root / f'{variant}-classes'
classes.mkdir(exist_ok=True)
command = [java, '-Xmx512m', '-cp', (root / 'compiler-classpath.txt').read_text(),
           'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect',
           '-jvm-target', '1.8', '-cp', classpath, '-d', str(classes), *map(str, sources)]
if '--reuse-classes' in sys.argv:
    assert (classes / 'org/microg/gms/cast/channel/CastDeviceSession.class').is_file()
    compiled = subprocess.CompletedProcess(command, 0, '', '')
else:
    compiled = subprocess.run(command, text=True, capture_output=True)
(root / f'{label}-compile.log').write_text(compiled.stdout + compiled.stderr)
print(f'{variant} compilation exit={compiled.returncode}')
if compiled.returncode:
    print(compiled.stdout, compiled.stderr)
    sys.exit(compiled.returncode)

command = [java, '-Xmx256m', f'-Dcast.timer.sdk={sdk}', f'-Dcast.timer.expectedRetained={0 if sdk >= 21 else 512}', '-cp', f'{classes}:{classpath}', 'org.junit.runner.JUnitCore',
           'org.microg.gms.cast.channel.CastDeviceSessionTimerTest']
tested = subprocess.run(command, text=True, capture_output=True)
(root / f'{label}-junit.log').write_text(tested.stdout + tested.stderr)
(root / f'{label}-result.json').write_text(json.dumps({
    'source_variant': variant,
    'host_fixture_sdk': sdk,
    'android_execution': False,
    'compilation_reused': '--reuse-classes' in sys.argv,
    'source_base': '13acaa56bc6672e4508e185b82a57d212c2ac2d8',
    'compiler': 'Kotlin JVM 2.2.21',
    'jdk': 'OpenJDK 17.0.20.1+1',
    'wire': '6.4.6, generated from unchanged repository cast_channel.proto',
    'scope': 'actual CastDeviceSession, CastChannel and Wire classes; in-memory channel output; host-only Android Build SDK constants; no Android/physical receiver execution',
    'compile_exit': compiled.returncode,
    'junit_exit': tested.returncode,
    'junit_output': tested.stdout,
}, indent=2))
print(tested.stdout, tested.stderr)
sys.exit(tested.returncode)
