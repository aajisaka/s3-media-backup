# AGENTS.md

Orientation for an AI coding agent picking this repo up cold. It records the things
that are expensive to rediscover: environment setup, verified commands, and the
non-obvious constraints behind decisions that look arbitrary in the code.

Read `README.md` first for what the app does and how to configure AWS. This file is
about *working on* the code.

---

## Environment

A fresh shell needs these before **any** `./gradlew` or `adb` command. Without them
Gradle fails with "SDK location not found" or picks the wrong JDK.

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/build-tools/35.0.0:$PATH"
```

| | |
| --- | --- |
| JDK | 17 (Zulu). AGP 8.7.3 requires 17. |
| Android SDK | `/opt/homebrew/share/android-commandlinetools`, via `brew install --cask android-commandlinetools` |
| SDK packages | `platform-tools`, `platforms;android-35`, `build-tools;35.0.0` |
| Gradle | 8.11.1 via `./gradlew`. Never install a global Gradle. |

`local.properties` holds `sdk.dir` and is **gitignored**. It must exist locally; if a
fresh clone fails to build, recreate it:

```properties
sdk.dir=/opt/homebrew/share/android-commandlinetools
```

This repository is **public**. Do not commit machine-specific identifiers: no home
directory paths, no device serials, no email addresses, no bucket names. Use generic
placeholders in docs.

---

## Commands

```bash
./gradlew :app:testDebugUnitTest        # 18 unit tests, ~5s
./gradlew assemble                      # debug + release APKs
./gradlew :app:lint                     # static analysis (abortOnError = true)
./gradlew clean
```

Artifacts:

| Path | Notes |
| --- | --- |
| `app/build/outputs/apk/release/app-release.apk` | The one to install. Signed with the local debug key. |
| `app/build/outputs/apk/debug/app-debug.apk` | Larger, debug tooling. |

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

### Dependency versions

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

To re-run the CVE review: resolve the graph and query OSV.dev (GitHub Advisory
Database + NVD) for every `group:artifact:version`. Validate the scanner against a
known-bad package such as `log4j-core:2.14.1` before trusting a clean result.

### S3 keys

`<prefix>/<photos|videos>/<yyyy>/<MM>/<filename>`

Two different files can share a display name in the same month, and writing both to
one key silently destroys one. `BackupSettings.s3KeysFor()` appends `~<mediaStoreId>`
to the second and later members of a collision group.

Two invariants worth preserving:

- Collisions are resolved over the **full scan**, not just pending files. Resolving
  over pending files only would let a new file land on the key of one already in S3.
- The **lowest MediaStore id keeps the plain key**, so a name that is unique today
  does not change key when a same-named file appears later.

Changing the key scheme orphans already-uploaded objects and re-uploads everything.
Treat it as a migration, not a refactor.

### Skip logic

The Room `uploaded_media` table stores a fingerprint of `id:size:takenAtMillis`.
Skipping is **local-only** — no `HeadObject` per file, so an incremental run makes no
S3 requests until it has something to send.

Two consequences to keep in mind:

- Clearing app data wipes the index, so everything re-uploads (overwriting identical
  content — no duplicates, just bandwidth).
- **Trap:** deleting objects out-of-band (`aws s3 rm ... --recursive`) leaves the
  index claiming those files are backed up. App data must be cleared too, or the app
  will report success for objects that no longer exist. Documented in the README.

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
`RuntimeException: Method ... not mocked`. That is why the two pieces of pure logic
were extracted to top-level functions:

- `resolveKeys()` — `data/SettingsRepository.kt`
- `mediaPermissionsFor()` — `data/Permissions.kt`

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
