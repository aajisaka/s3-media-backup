# AGENTS.md

Orientation for an AI coding agent picking this repo up cold. It records the things
that are expensive to rediscover: environment setup, verified commands, and the
non-obvious constraints behind decisions that look arbitrary in the code.

Read `README.md` first for what the app does, how to build it, and how to configure
AWS. This file is about *working on* the code.

The split, so the two files do not drift back into restating each other:

- **README** owns setup and user-facing behaviour — prerequisites, build and install
  steps, AWS configuration, S3 object layout, what gets skipped, troubleshooting.
- **AGENTS.md** owns *why the code is the way it is* and the invariants that are easy
  to break. Point at the README rather than restating it.

---

## Environment

A fresh shell needs these before **any** `./gradlew` or `adb` command. Without them
Gradle fails with "SDK location not found" or picks the wrong JDK.

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/build-tools/36.0.0:$PATH"
```

JDK 17 is mandatory (AGP 9.4 rejects other versions) and Gradle comes from `./gradlew`
— never install a global one. The SDK packages to install, including the
canary-channel platform, are in [README → Prerequisites](README.md#prerequisites).

`local.properties` holds `sdk.dir` and is gitignored. It must exist locally or a fresh
clone fails with "SDK location not found".

This repository is **public**. Do not commit machine-specific identifiers: no home
directory paths, no device serials, no email addresses, no bucket names. Use generic
placeholders in docs.

---

## Commands

```bash
./gradlew :app:testDebugUnitTest        # 27 unit tests, ~5s
./gradlew assemble                      # debug + release APKs
./gradlew :app:lint                     # static analysis (abortOnError = true)
./gradlew clean
```

The APK to install is `app/build/outputs/apk/release/app-release.apk`.

`assemble` runs `lintVitalRelease`, so a release build fails on any lint error. Keep
lint clean rather than disabling checks — see "Lint" below for a case where the right
fix was removing a suppression.

### Verification loop

Build output alone is weak evidence for this app. Most changes here have failed at
runtime, not compile time. Always finish with:

```bash
./gradlew :app:testDebugUnitTest assemble :app:lint

adb install -r app/build/outputs/apk/release/app-release.apk
adb logcat -c
adb shell am start -n com.example.mediabackup/.MainActivity
sleep 5
adb logcat -d | grep -E "FATAL EXCEPTION|NoClassDefFoundError|NoSuchMethodError"
adb exec-out screencap -p > /tmp/screen.png     # then view the image
```

The `NoClassDefFoundError` / `NoSuchMethodError` grep matters because dependency
upgrades here are runtime-verified only. Viewing the screenshot is the fastest way to
confirm a UI change — it caught two bugs that compiled and linted cleanly.

---

## Architecture

```
MediaScanner ──► BackupRepository ──► S3Uploader ──► S3
 (MediaStore)     (diff vs Room index)  (streaming PUT / multipart)
                        │
                        ▼
              BackupRepository.status : StateFlow<BackupStatus>
                        │
                        ├──► BackupWorker  (notification, via collection)
                        └──► HomeScreen    (Compose, via collectAsStateWithLifecycle)
```

- `BackupApp` holds two singletons: `SettingsRepository` and `BackupRepository`.
  `BackupWorker` and `BackupViewModel` both reach them through the Application.
- **All progress flows through `BackupRepository.status`.** There is deliberately no
  callback plumbing — the worker *collects* the flow to update its notification. If
  you add progress that needs to reach the UI, publish it on that StateFlow.
- `MediaItem` is the unit of work; everything downstream is per-file.

---

## Non-obvious constraints

These are the ones that cost real time to work out. Each looks like an arbitrary
choice in the code.

### Build toolchain (AGP 9)

AGP 9 is a major break from 8.x. These are the traps:

- **Built-in Kotlin.** AGP 9 compiles Kotlin itself and *rejects* the
  `org.jetbrains.kotlin.android` plugin with an explicit error. There is deliberately
  no `kotlin-android` entry in the version catalog or either build file. Don't add it
  back.
- **AGP pins KGP and KSP.** AGP resolves its own Kotlin Gradle plugin and KSP. If the
  versions in `libs.versions.toml` don't match, KSP fails at run time with the
  useless-looking `e: [ksp] java.lang.IllegalStateException: unexpected jvm signature
  V`. Check what AGP actually wants before bumping:

  ```bash
  ./gradlew buildEnvironment --console=plain -q | grep -oE 'org.jetbrains.kotlin:kotlin-gradle-plugin:[0-9.]+|com.google.devtools.ksp:symbol-processing-gradle-plugin:[0-9.-]+'
  # AGP 9.4.1 -> kotlin-gradle-plugin 2.2.10, symbol-processing-gradle-plugin 2.2.10-2.0.2
  ```

- **KSP vs built-in Kotlin.** KSP 2.2.10-2.0.2 registers generated sources through the
  `kotlin.sourceSets` DSL, which built-in Kotlin rejects. `gradle.properties` carries
  `android.disallowKotlinSourceSets=false`, AGP's documented escape hatch. Drop it
  once KSP registers generated sources through the variant API.
- **No `kotlin { compilerOptions { jvmTarget } }`.** With built-in Kotlin the Kotlin
  `jvmTarget` defaults to `android.compileOptions.targetCompatibility`, so setting
  `compileOptions` is enough.
- **compileSdk 37 is minor-versioned and canary-only.** There is no
  `platforms;android-37`; only `android-37.0/.1/.2`. The DSL is:

  ```kotlin
  compileSdk {
      version = release(37) {
          minorApiLevel = 2
      }
  }
  ```

  Not `release(37, 2)` and not `minor = 2` — both fail with build-script compilation
  errors.

- **When AGP DSL docs are unclear, read the bytecode.** The API reference pages are
  JS-rendered and often fetch empty. The interfaces live in `gradle-common-api` in the
  Gradle cache and are definitive:

  ```bash
  JAR=$(find ~/.gradle/caches -name "gradle-common-api-*.jar" | head -1)
  javap -cp "$JAR" com.android.build.api.dsl.CompileSdkReleaseSpec
  ```

- Compose BOM **2026.09.00** (compose 1.12.x) needs a higher compileSdk than we use;
  we pin **2026.06.01** (compose 1.11.4). Verify any bump with
  `:app:checkDebugAarMetadata`, which reports the required compileSdk precisely.

### AWS SDK

- Artifacts are **AAR**, not JAR.
- `CognitoCachingCredentialsProvider` lives in **`aws-android-sdk-core`**, which
  arrives transitively with `aws-android-sdk-s3`. There is **no**
  `com.amazonaws:aws-android-sdk-cognitoidentity` artifact to depend on — declaring
  it fails resolution.
- **`TransferUtility` is unusable here.** Its `upload(key, InputStream)` overloads
  call `writeInputStreamToFile()` first, so a 20 GB movie needs 20 GB of free cache.
  That is the entire reason `S3Uploader` hand-rolls `putObject` plus multipart.
- `putObject(bucket, key, InputStream, metadata)` **streams only when
  `metadata.contentLength` is set.** Without it the SDK logs "Stream contents will be
  buffered in memory" and OOMs on large files. `metadataFor()` always sets it.
- Use `AmazonS3Client(credentialsProvider, region)`. The single-argument constructor
  is deprecated.
- `InitiateMultipartUploadRequest` has **no no-arg constructor** — use
  `(bucketName, key)`.
- **Retries must re-open the media stream.** A consumed stream cannot be replayed, so
  `uploadSinglePut` opens the content stream *inside* the retry block. Restructuring
  that is an easy way to silently send truncated bodies.

### Dependency versions and CVE scanning

`aws-android-sdk-s3:2.81.1` declares `appcompat:1.2.0` (2020), and
`aws-android-sdk-core` declares `gson:2.8.9` (2021). `app/build.gradle.kts` lifts
both with Gradle `constraints`. Do not remove them — a constraint can only raise a
version already on the classpath, so they cannot introduce anything new, and without
them `fragment:1.1.0` returns.

**Never downgrade these.** Both sit one patch above a published CVE:

| Dependency | Must stay at or above | Advisory |
| --- | --- | --- |
| `com.google.code.gson:gson` | 2.8.9 | CVE-2022-25647 |
| `junit:junit` (test only) | 4.13.1 | CVE-2020-15250 |

**Dependabot alerts cover the build classpath, not just the app.** This is the single
most misleading thing about the alerts on this repo. Most of them carry
`manifest_path: settings.gradle.kts` — Gradle plugin transitives pulled by AGP (netty
via `io.grpc:grpc-netty`, BouncyCastle, commons-compress, jdom2, jose4j). None of them
ship in the APK.

So a scan of `releaseRuntimeClasspath` and a scan of the buildscript classpath answer
different questions, and only the first one affects users. Scan both:

```bash
./gradlew --init-script <printdeps.gradle> :app:printResolvedDeps   # runtime classpath
./gradlew buildEnvironment --console=plain -q                        # build classpath
```

Then query OSV.dev for every `group:artifact:version`. **Validate the scanner against
a known-bad package such as `log4j-core:2.14.1` before trusting a clean result** —
a silently broken query looks exactly like a clean bill of health.

The remaining ones (BouncyCastle, commons-lang3, httpclient, jose4j, jdom2) are fixed
by pinning the patched versions on the root project's `buildscript` classpath in
`build.gradle.kts` — Gradle's highest-version-wins conflict resolution lifts them.
`buildEnvironment` prints `declared -> resolved`, so it is ambiguous about which
version won; resolve it properly with an init script that reads
`buildscript.configurations.*.incoming.resolutionResult`.

That `buildscript` block is build-tool only and does **not** leak into the app —
re-verify with a runtime classpath scan after touching it.

One alert remains: `kotlin-gradle-plugin` requires KGP 2.4.20-Beta1. That is a beta,
and moving KGP means KSP must move in lockstep or it fails with "unexpected jvm
signature V". Do not chase it. It is also about the Kotlin **build cache**, which this
project does not enable — `org.gradle.caching=true` is Gradle's own cache, a different
mechanism. Revisit when a stable KGP 2.4.20+ ships.

### S3 keys

`<prefix>/<photos|videos>/<yyyy>/<MM>/<filename>`, with `~<mediaStoreId>` appended to
the second and later members of a collision group. The user-facing rule and worked
example are in the README; what matters when editing the code is the two invariants:

- Collisions are resolved over the **full scan**, not just pending files. Resolving
  over pending files only would let a new file land on the key of one already in S3.
- The **lowest MediaStore id keeps the plain key**, so a name that is unique today
  does not change key when a same-named file appears later.

Changing the key scheme orphans already-uploaded objects and re-uploads everything.
Treat it as a migration, not a refactor.

### Skip logic

The Room `uploaded_media` table is the sole source of truth about what is in S3,
because skipping is **local-only** — there is no `HeadObject` per file. That is the
property most likely to be broken by a well-meaning change: adding verification would
also forfeit the guarantee that an incremental run makes no S3 requests at all.

Consequently the index can go stale in two ways — cleared app data, or objects deleted
out-of-band. Both are user-facing and covered in the README.

### Android platform

- `tools:node="merge"` on the `SystemForegroundService` entry — **not `"merged"`**.
  The wrong value fails the manifest merger with a confusing
  `No enum constant NodeOperationType.MERGED`.
- Media permissions are requested **only for enabled media types** via
  `mediaPermissionsFor()`. With photos off the app never holds `READ_MEDIA_IMAGES`.
  Preserve that; it is a deliberate privacy property.
- Android 15+ caps `dataSync` foreground services at ~6 hours per 24. Large first
  runs can be stopped mid-way. This is safe because the index is written per file —
  the next run resumes. Don't "fix" it by moving to a different service type.

### Testing

JVM unit tests **cannot touch `android.net.Uri`** — the stub jar throws
`RuntimeException: Method ... not mocked`. That is why the pure logic is extracted to
top-level functions rather than left inside classes that need Android types:

- `resolveKeys()` — `data/SettingsRepository.kt`
- `mediaPermissionsFor()` — `data/Permissions.kt`
- `formatBytes()` / `formatWhen()` — `ui/Formatting.kt` (kept out of the Compose files
  for the same reason, even though they touch no Android API at all)

`Build.VERSION.SDK_INT` is also pinned to `0` in JVM tests, so any version branching
must take the API level as a **parameter** to be testable.

Keep new pure logic in that shape. Logic mixed into a class that needs `Context`,
`Uri`, or `MediaStore` is effectively untestable here without adding Robolectric,
which is not currently a dependency.

### Lint

`InvalidFragmentVersionForActivityResult` was suppressed as a false positive while
`fragment:1.1.0` was on the classpath. The dependency constraint lifted it to 1.5.4
and the suppression was then removed. The check now passes on its own — prefer fixing
the cause over suppressing, and re-check suppressions when dependencies move.

R8/minification is **off** in release builds, deliberately: it avoids needing keep
rules for the AWS SDK's reflection-based JSON, and the source is public anyway.
Turning it on requires keep rules for the AWS SDK.

---

## Conventions

- Comments explain **why**, not what. Existing density is the reference. Several
  comments mark decisions that look wrong without context — keep those.
- Commit messages: short imperative subject, body explaining the reasoning and
  explicitly what was verified.
- Pure logic goes in a top-level `internal fun` so it can be unit tested; see above.
- New behaviour in `BackupRepository` should publish to `status`, not return values
  the UI has to poll.
