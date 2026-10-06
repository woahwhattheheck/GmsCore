# Dott session init-reply lifecycle validation

This is an isolated prospective validation controller for the existing
[microg/GmsCore PR #3841](https://github.com/microg/GmsCore/pull/3841).
The canonical source branch and PR description are not modified by this controller.
The test counts in `manifest.json` and `expected-tests.json` are frozen expectations;
they are not executed results.

Product checkout is exactly `5f56c5bc037056f54c7d2dfe2edc03d8c57e0da8`,
tree `cab6c7c21a2cff55e2513074e1b878d0d56badae`. The candidate is a disposable
two-file overlay: provider fail-closed behavior plus its setup guide. The source
receipt gives the exact before/after blobs and projected whole-source tree.
No caller-context VM is executed on the server, and no binary request transport
support is added.

The same twelve actual provider lifecycle cases execute twice with real Gradle
8.13, Java 17, Android compile SDK 35 and Robolectric 4.12.2. Eight cases use the
actual provider, core, store, preferences and HandleProxy with a controlled native
factory and public recording VM. Four cases substitute a partial reply only at
the core reply boundary after real empty-reply initialization. The normal core
produces both reply fields or neither; partial replies are explicitly synthetic.

Original expectations are six rejection failures plus six passing controls.
Candidate expectations are twelve passes, no errors or skips. Case names must
match exactly. Actual JUnit XML, Gradle exits, timings and source receipts are
uploaded even when the validation fails. Missing XML, fixture errors, compile
errors or dependency failures cannot be presented as the expected product
regressions. The candidate source tree is projected separately from the declared
test/dependency overlay.

Robolectric and Mockito-inline are added only to the disposable module test
configuration. Gradle stages the exact API 29
`org.robolectric:android-all-instrumented:10-robolectric-5803371-i6` JAR. Robolectric
uses its offline local dependency resolver. The actual staged Android runtime and
resolved test/compiler artifact hashes are recorded. The large runtime JAR bytes
are not included in the evidence upload. Version pins and actual hash receipts do
not establish a preexisting dependency-lock guarantee for the project.

Before lifecycle execution, the same hosted invocation records actual
`:buildEnvironment` output and selected root buildscript artifact coordinates,
bytes and SHA256 values. Root `build.gradle` blob
`a42ad581950ee347fe962e9110c18e1d59ebd880` remains unchanged. Its requested Kotlin
version can differ from Gradle's selected version through transitive dependency
resolution. A missing POM for one requested version alone does not prove the
complete graph cannot resolve. Actual selected artifacts establish the executed
graph; no Kotlin/Compose version or declared bootstrap dependency is changed.

The validation-only branch uses the already registered `.github/workflows/build.yml`
path. Its sole event is a push to `validation/dott3841-init-reply-5f56-20261006`.
There is no manual dispatch and no second dispatch after branch publication.
Actions are pinned to commits, permissions are `contents: read`, product and
controller checkouts disable persisted credentials, and cache writes are disabled.

The controlled APK descriptor probe uses real Linux file descriptors exposed by
the Robolectric ParcelFileDescriptor implementation. This establishes JVM
resource ownership under an Android shadow. It does not establish native Binder
descriptor transfer or device behavior. A close exception proves a close attempt
and native-VM cleanup; it cannot guarantee the OS descriptor was closed.

Remaining runtime uncertainties include Kotlin test friend paths, final lazy-field
reflection, Mockito inline mocks inside the Robolectric sandbox, project-class
shadow instrumentation and the test manifest/provider setup. Static API review
does not remove these uncertainties. Actual run evidence must establish them.
This validation does not run a downloaded Google VM, JNI, Play Integrity, Dott,
device tests, remote unlock or ride workflows.

Primary resolver source: Robolectric tag `robolectric-4.12.2`,
`DefaultSdkProvider.java` blob `0f9272c03e1f0b4a79442a47ccf3d43233159db7` pins API29
to Android `10`/build `5803371` and instrumented version `6`;
`LegacyDependencyResolver.java` blob `b82d656a9a0d13a849d349c19b5babe6186badcb` selects
LocalDependencyResolver for `robolectric.dependency.dir`.
